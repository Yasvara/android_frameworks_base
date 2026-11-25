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
    /** Root task ids that currently get ACTIVITY_STATE_FLAG_LAYANG_TOP. */
    private static final ArraySet<Integer> sBoostedTasks = new ArraySet<>();
    private static final ArrayList<Task> sTmpBoost = new ArrayList<>();

    private LayangCore() {}

    static void init(@NonNull ActivityTaskManagerService atm) {
        sAtm = atm;
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
        return getState(wc) != null;
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

        // Only a reflow window lowers its density; a scaled one keeps the display's, so it is
        // never relaunched for a density change.
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
                        || old.getVirtualShort() != state.getVirtualShort()));
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

    /** Applies a Layang launch from ActivityOptions once the started activity has its task. */
    static void onActivityStartedLocked(@NonNull ActivityRecord r,
            @Nullable android.app.ActivityOptions options) {
        final LayangTaskState state = options != null ? options.getLayangLaunch() : null;
        if (state == null) {
            return;
        }
        final Task task = r.getTask();
        if (task == null || task.getRootTask() != task) {
            Slog.w(TAG, "Layang launch of " + r + " is not a root task; ignored");
            r.mLayangLaunchPending = false;
            return;
        }
        applyStateLocked(task, state);
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
        // Turned, a window can be taller than the display: shrink it to fit (scale goes down).
        android.app.yasvara.LayangGeometry.fit(turned, display.width(), display.height(),
                margin, 0);
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
        final DisplayContent dc = root.getDefaultDisplay();
        if (dc == null) {
            return;
        }
        final TaskDisplayArea tda = dc.getDefaultTaskDisplayArea();
        sTmpBoost.clear();
        final boolean[] anyFloating = {false};
        final Task[] fullscreen = {null};
        tda.forAllRootTasks(t -> {
            if (!t.isVisibleRequested()) {
                return;
            }
            final LayangTaskState s = t.mLayangState;
            if (s != null) {
                if (s.getMode() == LayangTaskState.MODE_FLOATING) {
                    anyFloating[0] = true;
                    if (isGameTask(t)) {
                        sTmpBoost.add(t);
                    }
                }
            } else if (fullscreen[0] == null && t.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                    && t.getActivityType() == ACTIVITY_TYPE_STANDARD) {
                fullscreen[0] = t;
            }
        }, true /* traverseTopToBottom */);
        sTmpBoost.sort((a, b) -> Long.compare(b.lastActiveTime, a.lastActiveTime));
        if (fullscreen[0] != null && isGameTask(fullscreen[0])) {
            sTmpBoost.add(0, fullscreen[0]);
        }
        final ArraySet<Integer> next = new ArraySet<>();
        if (anyFloating[0]) {
            for (int i = 0; i < sTmpBoost.size() && next.size() < Layang.MAX_BOOSTED_GAMES; i++) {
                next.add(sTmpBoost.get(i).mTaskId);
            }
        }
        sTmpBoost.clear();
        if (next.equals(sBoostedTasks)) {
            return;
        }
        final ArraySet<Integer> changed = new ArraySet<>(sBoostedTasks);
        changed.addAll(next);
        for (int i = 0; i < next.size(); i++) {
            if (sBoostedTasks.contains(next.valueAt(i))) {
                changed.remove(next.valueAt(i));
            }
        }
        sBoostedTasks.clear();
        sBoostedTasks.addAll(next);
        for (int i = 0; i < changed.size(); i++) {
            final Task t = root.anyTaskForId(changed.valueAt(i));
            if (t != null) {
                t.forAllActivities(ar -> {
                    if (ar.hasProcess()) {
                        root.mTaskSupervisor.onProcessActivityStateChanged(ar.app,
                                true /* forceBatch */);
                    }
                });
            }
        }
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
