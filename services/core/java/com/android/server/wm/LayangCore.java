/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.res.Configuration.DENSITY_DPI_UNDEFINED;

import static com.android.server.wm.TaskFragment.FLAG_FORCE_HIDDEN_FOR_LAYANG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.yasvara.Layang;
import android.app.yasvara.LayangTaskState;
import android.app.yasvara.LayangWindowInfo;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.os.Binder;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Yasvara Layang: the WM-core half of floating windows.
 *
 * <p>The Layang state is a fact of the root task ({@link Task#mLayangState}). Everything else
 * WM needs is derived from it here, so there is one owner of geometry and mode (§4.2):
 * always-on-top, focusability, forced hiding for parked windows, the size-compat route that
 * scales a game's virtual screen V into the task bounds R (§4.1), the system-bar policy (§4.6)
 * and the top-app boost for games (§5.1).
 *
 * <p>Methods without a "Locked" suffix take the global lock themselves and are meant for
 * {@code YasvaraLayangService}; the rest run with the lock held.
 */
public final class LayangCore {
    private static final String TAG = "LayangCore";

    private static ActivityTaskManagerService sAtm;
    @Nullable
    private static Runnable sChangeCallback;
    private static boolean sChangePosted;

    /** Game packages per user, pushed by the service (Pentas list + rules). */
    private static final SparseArray<Set<String>> sGamePackages = new SparseArray<>();
    /** Packages with RULE_ALWAYS enabled per user, pushed by the service. */
    private static final SparseArray<Set<String>> sAlwaysPackages = new SparseArray<>();
    /** Callback into YasvaraLayangService to build placement for RULE_ALWAYS without re-locking. */
    public interface AlwaysLaunchResolver {
        @Nullable
        android.app.ActivityOptions resolve(@NonNull String pkg, int userId,
                @NonNull android.content.pm.ActivityInfo ai, int displayW, int displayH,
                int densityDpi, int topInsetPx);
    }
    @Nullable
    private static AlwaysLaunchResolver sAlwaysResolver;
    /** Root task currently inside WindowOrganizerController.applyChanges entering Layang. */
    @Nullable
    private static Task sPreApplyingRoot;
    /** Number of live root tasks with mLayangState != null (O(1) fast-path in rankTaskLayers). */
    private static int sLayangTaskCount;
    /** Root task ids that currently get ACTIVITY_STATE_FLAG_LAYANG_TOP. */
    private static final ArraySet<Integer> sBoostedTasks = new ArraySet<>();
    private static final ArrayList<Task> sTmpBoost = new ArrayList<>();
    private static final ArraySet<Integer> sNextBoost = new ArraySet<>();
    private static final ArraySet<Integer> sChangedBoost = new ArraySet<>();
    private static final boolean[] sAnyFloating = new boolean[1];
    private static final Task[] sFullscreenTask = new Task[1];

    private LayangCore() {}

    static void init(@NonNull ActivityTaskManagerService atm) {
        sAtm = atm;
    }

    /** Marks tr's root task as entering Layang while WindowOrganizerController#applyChanges runs. */
    static void beginEnterLocked(@NonNull Task tr) {
        sPreApplyingRoot = tr.getRootTask();
    }

    static void endEnterLocked() {
        sPreApplyingRoot = null;
    }

    // ------------------------------------------------------------------------------------
    // State queries (lock held)
    // ------------------------------------------------------------------------------------

    /** The Layang state of the root task that contains wc, or null. */
    @Nullable
    static LayangTaskState getState(@Nullable WindowContainer<?> wc) {
        if (wc == null) {
            return null;
        }
        final Task task = wc.asTask() != null ? wc.asTask()
                : wc.asActivityRecord() != null ? wc.asActivityRecord().getTask() : null;
        if (task == null) {
            return null;
        }
        final Task root = task.getRootTask();
        return root != null ? root.mLayangState : null;
    }

    static boolean isLayang(@Nullable WindowContainer<?> wc) {
        if (wc == null) {
            return false;
        }
        final Task task = wc.asTask() != null ? wc.asTask()
                : wc.asActivityRecord() != null ? wc.asActivityRecord().getTask() : null;
        if (task == null) {
            return false;
        }
        final Task root = task.getRootTask();
        return root != null && (root.mLayangState != null || root == sPreApplyingRoot);
    }

    /**
     * Whether r is laid out for the virtual screen and scaled by WM (§4.1): every Layang app
     * except a reflow one, so entering, leaving and scaling never change its configuration.
     */
    static boolean isLayangScaled(@NonNull ActivityRecord r) {
        final LayangTaskState state = getState(r);
        return state != null && !state.hasFlag(LayangTaskState.FLAG_REFLOW)
                && r.isActivityTypeStandardOrUndefined()
                && r.getTask() != null
                && r.getTask().getRootTask().getWindowingMode() == WINDOWING_MODE_FREEFORM;
    }

    /** An activity being started with a Layang launch, or living in a Layang task. */
    static boolean isLayangCandidate(@NonNull ActivityRecord r) {
        return r.mLayangLaunchPending || isLayang(r);
    }

    /**
     * Virtual size V of a scaled activity: the state's custom size or the display's, as
     * {long, short} in px (§4.1). The orientation is applied by AppCompatDisplayInsets.
     */
    static void getVirtualSize(@NonNull ActivityRecord r, @NonNull int[] outLongShort) {
        final LayangTaskState state = getState(r);
        int vLong = state != null ? state.getVirtualLong() : 0;
        int vShort = state != null ? state.getVirtualShort() : 0;
        if (vLong <= 0 || vShort <= 0) {
            final DisplayContent dc = r.mDisplayContent != null ? r.mDisplayContent
                    : sAtm.mRootWindowContainer.getDefaultDisplay();
            vLong = Math.max(dc.mBaseDisplayWidth, dc.mBaseDisplayHeight);
            vShort = Math.min(dc.mBaseDisplayWidth, dc.mBaseDisplayHeight);
        }
        outLongShort[0] = vLong;
        outLongShort[1] = vShort;
    }

    // ------------------------------------------------------------------------------------
    // Applying state (lock held)
    // ------------------------------------------------------------------------------------

    /**
     * Sets the Layang state of the root task of task and derives the WM properties from it.
     * Returns WindowOrganizerController TRANSACT_EFFECTS_* bits.
     */
    static int applyStateLocked(@NonNull Task task, @Nullable LayangTaskState state) {
        final Task root = task.getRootTask();
        if (root == null) {
            return 0;
        }
        if (state != null && (root.isActivityTypeHomeOrRecents()
                || root.getActivityType() == android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM)) {
            Slog.w(TAG, "Refusing Layang on " + root);
            return 0;
        }
        final LayangTaskState old = root.mLayangState;
        if (java.util.Objects.equals(old, state)) {
            return 0;
        }
        root.mLayangState = state;
        if (old == null && state != null) {
            sLayangTaskCount++;
        } else if (old != null && state == null) {
            sLayangTaskCount = Math.max(0, sLayangTaskCount - 1);
        }
        int effects = WindowOrganizerController.TRANSACT_EFFECTS_CLIENT_CONFIG;

        if (state != null && root.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
            root.setWindowingMode(WINDOWING_MODE_FREEFORM);
        }
        root.setAlwaysOnTop(state != null && state.getMode() != LayangTaskState.MODE_PARKED);
        root.setFocusable(state == null || state.isInteractive());
        if (root.setForceHidden(FLAG_FORCE_HIDDEN_FOR_LAYANG,
                state != null && state.getMode() == LayangTaskState.MODE_PARKED)) {
            effects |= WindowOrganizerController.TRANSACT_EFFECTS_LIFECYCLE;
        }
        if (old == null || state == null || old.isInteractive() != state.isInteractive()) {
            effects |= WindowOrganizerController.TRANSACT_EFFECTS_LIFECYCLE;
        }
        if ((old != null && old.isGhost()) != (state != null && state.isGhost())
                && root.getDisplayContent() != null) {
            root.getDisplayContent().getInputMonitor().updateInputWindowsLw(true /* force */);
        }

        // Only a reflow window lowers its density at task level; a scaled one keeps the display's
        // (optional resolution-scale density is applied in AppCompatSizeCompatModePolicy).
        final int density = state != null && state.hasFlag(LayangTaskState.FLAG_REFLOW)
                ? state.getDensityDpi() : 0;
        final int wantDensity = density > 0 ? density : DENSITY_DPI_UNDEFINED;
        if (root.getRequestedOverrideConfiguration().densityDpi != wantDensity) {
            final android.content.res.Configuration c =
                    new android.content.res.Configuration(root.getRequestedOverrideConfiguration());
            c.densityDpi = wantDensity;
            root.onRequestedOverrideConfigurationChanged(c);
        }

        // Scale route: activities re-resolve. AppCompatSizeCompatModePolicy creates the Layang
        // display insets on entry and drops them when the task leaves Layang (§4.1).
        final boolean scaledChanged = isScaled(old) != isScaled(state)
                || (old != null && state != null && (old.getVirtualLong() != state.getVirtualLong()
                        || old.getVirtualShort() != state.getVirtualShort()
                        || old.getDensityDpi() != state.getDensityDpi()));
        if (scaledChanged) {
            root.forAllActivities(ar -> {
                ar.mAppCompatController.getSizeCompatModePolicy().dropLayangDisplayInsets();
                ar.recomputeConfiguration();
            });
        }
        if (state == null) {
            root.forAllActivities(ar -> {
                ar.mLayangLaunchPending = false;
            });
        }
        root.forAllActivities(ar -> {
            if (ar.hasProcess()) {
                sAtm.mTaskSupervisor.onProcessActivityStateChanged(ar.app, true /* forceBatch */);
            }
        });
        sAtm.mRootWindowContainer.invalidateTaskLayersAndUpdateOomAdjIfNeeded();
        if (old == null || state == null) {
            Slog.i(TAG, (state != null ? "enter " : "leave ") + root + " " + state);
        }
        notifyChangedLocked();
        return effects;
    }

    /** A root task left freeform by some other path; it stops being a Layang window. */
    static void onRootTaskWindowingModeChanged(@NonNull Task root) {
        if (root.mLayangState != null && root.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
            applyStateLocked(root, null);
        }
    }

    private static boolean isScaled(@Nullable LayangTaskState s) {
        return s != null && !s.hasFlag(LayangTaskState.FLAG_REFLOW);
    }

    /**
     * Resolves implicit Layang launches (lock held):
     * 1. Trampoline/splash inheritance: a game splash activity inside Layang launching the real
     *    game activity in a new task (singleTask/singleInstance/NEW_TASK) keeps floating.
     * 2. RULE_ALWAYS: apps marked "Always open floating" launched from Home/Launcher.
     */
    @Nullable
    static android.app.ActivityOptions maybeResolveLayangOptionsLocked(
            @NonNull ActivityRecord r, @Nullable ActivityRecord source,
            @Nullable android.app.ActivityOptions options, @Nullable Task targetTask) {
        if (options != null && options.getLayangLaunch() != null) {
            return options;
        }
        if (!r.isActivityTypeStandardOrUndefined() || r.isActivityTypeHomeOrRecents()
                || r.getActivityType() == android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM) {
            return options;
        }
        // 1. Same-package or same-UID trampoline from an active Layang window.
        if (source != null && isLayang(source)) {
            final boolean sameApp = java.util.Objects.equals(r.packageName, source.packageName)
                    || (r.getUid() > 0 && r.getUid() == source.getUid());
            if (sameApp && (targetTask == null || !isLayang(targetTask))) {
                final Task srcRoot = source.getRootTask();
                final LayangTaskState srcState = srcRoot != null ? srcRoot.mLayangState : null;
                if (srcState != null) {
                    final android.app.ActivityOptions out = options != null
                            ? options : android.app.ActivityOptions.makeBasic();
                    out.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
                    final Rect srcBounds = srcRoot.getBounds();
                    if (!srcBounds.isEmpty()) {
                        out.setLaunchBounds(new Rect(srcBounds));
                    }
                    out.setLayangLaunch(srcState);
                    return out;
                }
            }
        }
        // 2. RULE_ALWAYS: normal launch from Home/Launcher (or external caller with no source).
        final Set<String> always = sAlwaysPackages.get(r.mUserId);
        if (always != null && always.contains(r.packageName)
                && (source == null || source.isActivityTypeHomeOrRecents())
                && (targetTask == null || !isLayang(targetTask))) {
            final AlwaysLaunchResolver resolver = sAlwaysResolver;
            final DisplayContent dc = sAtm != null && sAtm.mRootWindowContainer != null
                    ? sAtm.mRootWindowContainer.getDefaultDisplay() : null;
            if (resolver != null && dc != null && r.info != null) {
                final Rect db = dc.getBounds();
                final int dpi = dc.getConfiguration().densityDpi;
                final int topInset = computeTopInsetLocked(dc);
                final android.app.ActivityOptions resolved = resolver.resolve(
                        r.packageName, r.mUserId, r.info, db.width(), db.height(), dpi, topInset);
                if (resolved != null && resolved.getLayangLaunch() != null) {
                    if (options != null) {
                        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
                        if (resolved.getLaunchBounds() != null) {
                            options.setLaunchBounds(resolved.getLaunchBounds());
                        }
                        options.setLayangLaunch(resolved.getLayangLaunch());
                        return options;
                    }
                    return resolved;
                }
            }
        }
        return options;
    }

    /**
     * When a Layang task (e.g. a {@code singleInstance} launcher/extractor trampoline like
     * Mobile Legends' {@code MobaGameMainActivityWithExtractor}) launches an opaque activity of
     * the same app into a separate task, hand off Layang ownership cleanly instead of leaving
     * the dead/black launcher task floating behind the real game window.
     */
    private static void handoffTrampolineLocked(@NonNull Task targetRoot,
            @Nullable ActivityRecord source, @NonNull ActivityRecord r) {
        if (source == null || !isLayang(source) || !r.occludesParent()) {
            return;
        }
        final Task srcRoot = source.getRootTask();
        if (srcRoot == null || srcRoot == targetRoot || srcRoot.mLayangState == null) {
            return;
        }
        final boolean sameApp = java.util.Objects.equals(r.packageName, source.packageName)
                || (r.getUid() > 0 && r.getUid() == source.getUid());
        if (!sameApp) {
            return;
        }
        if (srcRoot.mTransitionController.isCollecting()) {
            srcRoot.mTransitionController.collect(srcRoot);
        }
        Slog.i(TAG, "trampoline handoff " + srcRoot + " -> " + targetRoot);
        applyStateLocked(srcRoot, null);
        if (srcRoot.getWindowingMode() != WINDOWING_MODE_FULLSCREEN) {
            srcRoot.setRootTaskWindowingMode(WINDOWING_MODE_FULLSCREEN);
        }
        srcRoot.setBounds(null);
        srcRoot.moveToBack("layang-trampoline-handoff", null);
    }

    /** Applies a Layang launch to an existing recycled task so non-resizable games stay floating. */
    static void applyLaunchToTaskLocked(@NonNull Task task,
            @Nullable android.app.ActivityOptions options,
            @Nullable ActivityRecord source, @NonNull ActivityRecord r) {
        final LayangTaskState state = options != null ? options.getLayangLaunch() : null;
        if (state == null) {
            return;
        }
        final Task root = task.getRootTask();
        if (root == null) {
            return;
        }
        handoffTrampolineLocked(root, source, r);
        beginEnterLocked(root);
        try {
            if (root.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
                root.setRootTaskWindowingMode(WINDOWING_MODE_FREEFORM);
            }
            final Rect bounds = options.getLaunchBounds();
            if (bounds != null && !bounds.isEmpty()) {
                root.setBounds(bounds);
            }
        } finally {
            endEnterLocked();
        }
        applyStateLocked(root, state);
    }

    /** Applies a Layang launch from ActivityOptions once the started activity has its task. */
    static void onActivityStartedLocked(@NonNull ActivityRecord r,
            @Nullable android.app.ActivityOptions options,
            @Nullable ActivityRecord source) {
        final LayangTaskState state = options != null ? options.getLayangLaunch() : null;
        if (state == null) {
            return;
        }
        final Task task = r.getTask();
        final Task root = task != null ? task.getRootTask() : null;
        if (root == null) {
            Slog.w(TAG, "Layang launch of " + r + " has no root task; ignored");
            r.mLayangLaunchPending = false;
            return;
        }
        applyLaunchToTaskLocked(root, options, source, r);
    }

    private static int computeTopInsetLocked(@NonNull DisplayContent dc) {
        final int dpi = dc.getConfiguration().densityDpi;
        final int pillH = android.app.yasvara.LayangGeometry.dpToPx(24, dpi);
        final android.view.DisplayCutout cutout = dc.getDisplayInfo().displayCutout;
        final int cutoutTop = cutout != null ? cutout.getSafeInsetTop() : 0;
        final Rect stable = new Rect();
        dc.getStableRect(stable);
        // If status bar is hidden (immersive), cutout + pill is enough; otherwise stay below bar.
        final WindowState statusBar = dc.getDisplayPolicy() != null
                ? dc.getDisplayPolicy().getStatusBar() : null;
        final boolean barVisible = statusBar != null && statusBar.isVisible();
        return (barVisible ? Math.max(stable.top, cutoutTop) : cutoutTop) + pillH;
    }

    /** Windows of a ghost Layang task are not touchable (§4.5). */
    static boolean isGhostWindow(@NonNull WindowState w) {
        if (w.mActivityRecord == null) {
            return false;
        }
        final LayangTaskState s = getState(w.mActivityRecord);
        return s != null && s.isGhost();
    }

    /**
     * A scaled Layang app asked for the other orientation: its virtual screen turns, so the
     * window turns around its centre with the same scale (§4.6). Posted, so the app's binder
     * call returns first; the change is one server transition.
     */
    static void onRequestedOrientationChangedLocked(@NonNull ActivityRecord r) {
        if (!isLayangScaled(r)) {
            return;
        }
        final Task root = r.getTask().getRootTask();
        if (root.getTopNonFinishingActivity() != r) {
            return;
        }
        final int orientation = r.getRequestedConfigurationOrientation();
        if (orientation == android.content.res.Configuration.ORIENTATION_UNDEFINED) {
            return;
        }
        final Rect b = root.getBounds();
        final boolean wantLandscape =
                orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (b.isEmpty() || (b.width() > b.height()) == wantLandscape) {
            return;
        }
        final DisplayContent dc = root.getDisplayContent();
        if (dc == null) {
            return;
        }
        final Rect turned = new Rect(b.centerX() - b.height() / 2, b.centerY() - b.width() / 2,
                b.centerX() - b.height() / 2 + b.height(), b.centerY() - b.width() / 2 + b.width());
        final Rect display = dc.getBounds();
        final int margin = android.app.yasvara.LayangGeometry.dpToPx(Layang.MARGIN_DP,
                dc.getConfiguration().densityDpi);
        final int topInset = computeTopInsetLocked(dc);
        // Turned, a window can be taller than the display: shrink it to fit below pill/cutout.
        android.app.yasvara.LayangGeometry.fit(turned, display.width(), display.height(),
                margin, topInset);
        final WindowContainerToken token = root.mRemoteToken.toWindowContainerToken();
        final int taskId = root.mTaskId;
        sAtm.mH.post(() -> {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setBounds(token, turned);
            applyTransaction(wct, taskId);
        });
    }

    /** A launch, Recents or moveTaskToFront raises a parked window: it floats again. */
    static void onMoveTaskToFrontLocked(@NonNull Task root) {
        final LayangTaskState s = root.mLayangState;
        if (s != null && s.getMode() == LayangTaskState.MODE_PARKED) {
            applyStateLocked(root, s.withMode(LayangTaskState.MODE_FLOATING));
        }
    }

    /** Called when a root task is removed. */
    static void onTaskRemovedLocked(@NonNull Task task) {
        if (task.mLayangState != null) {
            task.mLayangState = null;
            sLayangTaskCount = Math.max(0, sLayangTaskCount - 1);
            sBoostedTasks.remove(task.mTaskId);
            notifyChangedLocked();
        }
    }

    /** Any Layang task's bounds or z-order changed through a transaction. */
    static void onTaskChangedLocked(@NonNull Task task) {
        if (isLayang(task)) {
            notifyChangedLocked();
        }
    }

    // ------------------------------------------------------------------------------------
    // Policies (lock held)
    // ------------------------------------------------------------------------------------

    /**
     * Legacy bars (F4): Layang tasks never make WM force the system bars of the fullscreen app.
     */
    static boolean countsAsFreeformForLegacyBars(@NonNull Task task) {
        return getState(task) == null;
    }

    /** Whether r's process gets ACTIVITY_STATE_FLAG_LAYANG_TOP (§5.1). */
    static boolean isBoosted(@NonNull Task task) {
        final Task root = task.getRootTask();
        return root != null && sBoostedTasks.contains(root.mTaskId);
    }

    /**
     * Recomputes which tasks get the game boost, after layer ranking. While a FLOATING Layang
     * window is visible: the fullscreen game below and the floating games, most recent first,
     * at most {@link Layang#MAX_BOOSTED_GAMES} (§5.1).
     */
    static void updateBoostLocked(@NonNull RootWindowContainer root) {
        // Zero-overhead fast path when idle (no Layang windows active and nothing boosted).
        if (sLayangTaskCount == 0 && sBoostedTasks.isEmpty()) {
            return;
        }
        final DisplayContent dc = root.getDefaultDisplay();
        if (dc == null) {
            return;
        }
        final TaskDisplayArea tda = dc.getDefaultTaskDisplayArea();
        sTmpBoost.clear();
        sAnyFloating[0] = false;
        sFullscreenTask[0] = null;
        tda.forAllRootTasks(t -> {
            if (!t.isVisibleRequested()) {
                return;
            }
            final LayangTaskState s = t.mLayangState;
            if (s != null) {
                if (s.getMode() == LayangTaskState.MODE_FLOATING) {
                    sAnyFloating[0] = true;
                    if (isGameTask(t)) {
                        sTmpBoost.add(t);
                    }
                }
            } else if (sFullscreenTask[0] == null
                    && t.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                    && t.getActivityType() == ACTIVITY_TYPE_STANDARD) {
                sFullscreenTask[0] = t;
            }
        }, true /* traverseTopToBottom */);
        if (sTmpBoost.size() > 1) {
            sTmpBoost.sort((a, b) -> Long.compare(b.lastActiveTime, a.lastActiveTime));
        }
        if (sFullscreenTask[0] != null && isGameTask(sFullscreenTask[0])) {
            sTmpBoost.add(0, sFullscreenTask[0]);
        }
        sNextBoost.clear();
        if (sAnyFloating[0]) {
            for (int i = 0; i < sTmpBoost.size() && sNextBoost.size() < Layang.MAX_BOOSTED_GAMES;
                    i++) {
                sNextBoost.add(sTmpBoost.get(i).mTaskId);
            }
        }
        sTmpBoost.clear();
        sFullscreenTask[0] = null;
        if (sNextBoost.equals(sBoostedTasks)) {
            sNextBoost.clear();
            return;
        }
        sChangedBoost.clear();
        sChangedBoost.addAll(sBoostedTasks);
        sChangedBoost.addAll(sNextBoost);
        for (int i = 0; i < sNextBoost.size(); i++) {
            if (sBoostedTasks.contains(sNextBoost.valueAt(i))) {
                sChangedBoost.remove(sNextBoost.valueAt(i));
            }
        }
        sBoostedTasks.clear();
        sBoostedTasks.addAll(sNextBoost);
        sNextBoost.clear();
        for (int i = 0; i < sChangedBoost.size(); i++) {
            final Task t = root.anyTaskForId(sChangedBoost.valueAt(i));
            if (t != null) {
                t.forAllActivities(ar -> {
                    if (ar.hasProcess()) {
                        root.mTaskSupervisor.onProcessActivityStateChanged(ar.app,
                                true /* forceBatch */);
                    }
                });
            }
        }
        sChangedBoost.clear();
    }

    private static boolean isGameTask(@NonNull Task t) {
        final LayangTaskState s = t.mLayangState;
        if (s != null) {
            return s.isGame();
        }
        final ActivityRecord top = t.getTopNonFinishingActivity();
        if (top == null) {
            return false;
        }
        final ApplicationInfo ai = top.info.applicationInfo;
        if (ai.category == ApplicationInfo.CATEGORY_GAME) {
            return true;
        }
        final Set<String> games = sGamePackages.get(top.mUserId);
        return games != null && games.contains(top.packageName);
    }

    // ------------------------------------------------------------------------------------
    // Service API (takes the lock)
    // ------------------------------------------------------------------------------------

    /** Runs callback on the WM handler, coalesced, whenever the Layang window set changes. */
    public static void setChangeCallback(@Nullable Runnable callback) {
        synchronized (sAtm.mGlobalLock) {
            sChangeCallback = callback;
        }
    }

    public static void setGamePackages(int userId, @NonNull Set<String> packages) {
        synchronized (sAtm.mGlobalLock) {
            sGamePackages.put(userId, new ArraySet<>(packages));
            // The service starts before WM exists; the next rankTaskLayers picks these up.
            if (sAtm.mRootWindowContainer != null) {
                sAtm.mRootWindowContainer.invalidateTaskLayersAndUpdateOomAdjIfNeeded();
            }
        }
    }

    public static void setAlwaysPackages(int userId, @NonNull Set<String> packages) {
        synchronized (sAtm.mGlobalLock) {
            if (packages.isEmpty()) {
                sAlwaysPackages.remove(userId);
            } else {
                sAlwaysPackages.put(userId, new ArraySet<>(packages));
            }
        }
    }

    public static void setAlwaysLaunchResolver(@Nullable AlwaysLaunchResolver resolver) {
        synchronized (sAtm.mGlobalLock) {
            sAlwaysResolver = resolver;
        }
    }

    /** Snapshot of every Layang window on the default display, top to bottom. */
    @NonNull
    public static List<LayangWindowInfo> getWindows() {
        final ArrayList<LayangWindowInfo> out = new ArrayList<>();
        synchronized (sAtm.mGlobalLock) {
            final DisplayContent dc = sAtm.mRootWindowContainer != null
                    ? sAtm.mRootWindowContainer.getDefaultDisplay() : null;
            if (dc == null) {
                return out;
            }
            final Task focused = dc.getFocusedRootTask();
            dc.getDefaultTaskDisplayArea().forAllRootTasks(t -> {
                final LayangTaskState s = t.mLayangState;
                if (s == null) {
                    return;
                }
                final ComponentName cn = t.realActivity != null ? t.realActivity
                        : t.getBaseIntent() != null ? t.getBaseIntent().getComponent() : null;
                final String pkg = cn != null ? cn.getPackageName() : "";
                String label = null;
                if (t.getTaskDescription() != null) {
                    label = t.getTaskDescription().getLabel();
                }
                out.add(new LayangWindowInfo(t.mTaskId, t.mUserId, pkg, label, s,
                        t.getBounds(), t == focused, t.isVisibleRequested(),
                        t.lastActiveTime));
            }, true /* traverseTopToBottom */);
        }
        return out;
    }

    @Nullable
    private static Task anyTask(int taskId) {
        return sAtm.mRootWindowContainer != null
                ? sAtm.mRootWindowContainer.anyTaskForId(taskId) : null;
    }

    @Nullable
    public static LayangTaskState getTaskState(int taskId) {
        synchronized (sAtm.mGlobalLock) {
            final Task t = anyTask(taskId);
            return t != null && t.getRootTask() != null ? t.getRootTask().mLayangState : null;
        }
    }

    /** Root task token of taskId for building a WindowContainerTransaction, or null. */
    @Nullable
    public static WindowContainerToken getRootTaskToken(int taskId) {
        synchronized (sAtm.mGlobalLock) {
            final Task t = anyTask(taskId);
            final Task root = t != null ? t.getRootTask() : null;
            return root != null && root.isAttached() ? root.mRemoteToken.toWindowContainerToken()
                    : null;
        }
    }

    /** Bounds of the root task of taskId, or null. */
    @Nullable
    public static Rect getTaskBounds(int taskId) {
        synchronized (sAtm.mGlobalLock) {
            final Task t = anyTask(taskId);
            final Task root = t != null ? t.getRootTask() : null;
            return root != null ? new Rect(root.getBounds()) : null;
        }
    }

    /** Display bounds and density of the default display: {w, h, densityDpi, rotation}. */
    @NonNull
    public static int[] getDefaultDisplayInfo() {
        synchronized (sAtm.mGlobalLock) {
            final DisplayContent dc = sAtm.mRootWindowContainer != null
                    ? sAtm.mRootWindowContainer.getDefaultDisplay() : null;
            if (dc == null) {
                return new int[] {0, 0, 0, 0};
            }
            final Rect b = dc.getBounds();
            return new int[] {b.width(), b.height(), dc.getConfiguration().densityDpi,
                    dc.getRotation()};
        }
    }

    /**
     * Applies wct from system_server as a server-started TRANSIT_CHANGE so Shell can animate
     * it. Used by the service when no Shell is registered, or for operations Shell does not
     * own. The caller must have checked permissions.
     */
    public static void applyTransaction(@NonNull WindowContainerTransaction wct, int taskId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (sAtm.mGlobalLock) {
                final Task t = anyTask(taskId);
                sAtm.mWindowOrganizerController.applyLayangTransaction(wct,
                        t != null ? t.getRootTask() : null);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // ------------------------------------------------------------------------------------

    private static void notifyChangedLocked() {
        if (sChangeCallback == null || sChangePosted) {
            return;
        }
        sChangePosted = true;
        final Runnable cb = sChangeCallback;
        sAtm.mH.postDelayed(() -> {
            synchronized (sAtm.mGlobalLock) {
                sChangePosted = false;
            }
            cb.run();
        }, 16);
    }

    static void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        pw.println(prefix + "Layang: boosted=" + sBoostedTasks + " games=" + sGamePackages);
    }
}
