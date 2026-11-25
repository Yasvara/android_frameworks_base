/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wm.shell.layang;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_LAYANG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.yasvara.ILayangManager;
import android.app.yasvara.ILayangShell;
import android.app.yasvara.Layang;
import android.app.yasvara.LayangGeometry;
import android.app.yasvara.LayangTaskState;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.Display;
import android.view.IWindowSession;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowManagerGlobal;
import android.window.DisplayAreaInfo;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.TransactionPool;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Shell half of Layang floating windows: owns the chrome, gestures and
 * transitions of every task whose WM-core {@link LayangTaskState} is set. Every change goes to
 * WM core as one WindowContainerTransaction inside a Shell transition; nothing here is the
 * source of truth for geometry or mode, so a SystemUI restart loses nothing (§4.2).
 *
 * <p>Created eagerly (WMShellModule#provideIndependentShellComponentsToCreate), never through
 * a late ShellInit callback (F13).
 */
public class LayangController implements ShellTaskOrganizer.TaskListener,
        DisplayChangeController.OnDisplayChangingListener,
        DisplayImeController.ImePositionProcessor, Transitions.TransitionObserver {
    static final String TAG = "LayangController";
    private static final long SOURCE_HINT_TTL_MS = 2500L;

    private final Context mContext;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final Transitions mTransitions;
    private final DisplayController mDisplayController;
    private final RootTaskDisplayAreaOrganizer mRootTdaOrganizer;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mBgExecutor;
    private final Choreographer mChoreographer;
    private final TransactionPool mTransactionPool;
    private final DisplayImeController mImeController;
    private final IWindowSession mWindowSession;
    private final LayangTransitionHandler mTransitionHandler;
    /** Live windows by task id. Read in teardown, so R8 keeps every owner object (§3). */
    private final SparseArray<LayangWindow> mWindows = new SparseArray<>();
    private final ShellStub mShellStub = new ShellStub();
    /** Cached icon/source bounds for pop-up open animations, keyed by taskId or package. */
    private final SparseArray<SourceHint> mTaskSources = new SparseArray<>();
    private final ArrayMap<String, SourceHint> mPkgSources = new ArrayMap<>();

    @Nullable private ILayangManager mService;
    private boolean mInitialized;
    private final DisplayInsetsController mInsetsController;
    /** Whether the default display's status bar shows; an immersive app hides it. */
    private boolean mStatusBarVisible = true;

    /** Cached Settings.Secure values (kept live by one ContentObserver; zero hot-path IPC). */
    private float mGhostAlpha = 0.60f;
    private int mGripDp = Layang.GRIP_DP_DEFAULT;
    private int mMiniWidthDp = Layang.MINI_WIDTH_DP_DEFAULT;

    /** Window lifted above the IME and where it was before (§4.6); not persisted. */
    private int mImeLiftedTask = -1;
    @Nullable private Rect mImeLiftedFrom;
    @Nullable private Rect mImeLiftedTo;

    public LayangController(@NonNull Context context, @NonNull ShellInit shellInit,
            @NonNull ShellTaskOrganizer taskOrganizer, @NonNull Transitions transitions,
            @NonNull DisplayController displayController,
            @NonNull RootTaskDisplayAreaOrganizer rootTdaOrganizer,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor bgExecutor,
            @NonNull Choreographer choreographer, @NonNull TransactionPool transactionPool,
            @NonNull DisplayImeController imeController,
            @NonNull DisplayInsetsController insetsController) {
        mContext = context;
        mInsetsController = insetsController;
        mTaskOrganizer = taskOrganizer;
        mTransitions = transitions;
        mDisplayController = displayController;
        mRootTdaOrganizer = rootTdaOrganizer;
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;
        mChoreographer = choreographer;
        mTransactionPool = transactionPool;
        mImeController = imeController;
        mWindowSession = WindowManagerGlobal.getWindowSession();
        mTransitionHandler = new LayangTransitionHandler(this, transactionPool, mainExecutor,
                choreographer);
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    private void onInit() {
        mInitialized = true;
        refreshCachedSettings(false /* applyLive */);
        registerSettingsObserver();
        mTaskOrganizer.addListenerForType(this, TASK_LISTENER_TYPE_LAYANG);
        mTransitions.addHandler(mTransitionHandler);
        mTransitions.registerObserver(this);
        mDisplayController.addDisplayChangingController(this);
        mImeController.addPositionProcessor(this);
        mInsetsController.addInsetsChangedListener(Display.DEFAULT_DISPLAY,
                new DisplayInsetsController.OnInsetsChangedListener() {
                    @Override
                    public void insetsChanged(InsetsState state) {
                        mStatusBarVisible = isStatusBarVisible(state);
                    }
                });
        mBgExecutor.execute(this::registerWithService);
    }

    private void registerSettingsObserver() {
        final ContentResolver cr = mContext.getContentResolver();
        final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                mMainExecutor.execute(() -> refreshCachedSettings(true /* applyLive */));
            }
        };
        try {
            cr.registerContentObserver(Settings.Secure.getUriFor(Layang.KEY_GHOST_ALPHA),
                    false, observer, UserHandle.USER_ALL);
            cr.registerContentObserver(Settings.Secure.getUriFor(Layang.KEY_GRIP_DP),
                    false, observer, UserHandle.USER_ALL);
            cr.registerContentObserver(Settings.Secure.getUriFor(Layang.KEY_MINI_WIDTH_DP),
                    false, observer, UserHandle.USER_ALL);
        } catch (RuntimeException e) {
            Log.w(TAG, "registerSettingsObserver failed", e);
        }
    }

    private void refreshCachedSettings(boolean applyLive) {
        final ContentResolver cr = mContext.getContentResolver();
        final int ghostPct = Settings.Secure.getIntForUser(cr, Layang.KEY_GHOST_ALPHA, 60,
                UserHandle.USER_CURRENT);
        final float nextGhost = Math.max(0.2f, Math.min(Layang.MAX_GHOST_ALPHA, ghostPct / 100f));
        final int nextGrip = Math.max(Layang.GRIP_DP_MIN, Math.min(Layang.GRIP_DP_MAX,
                Settings.Secure.getIntForUser(cr, Layang.KEY_GRIP_DP, Layang.GRIP_DP_DEFAULT,
                        UserHandle.USER_CURRENT)));
        final int nextMini = Math.max(Layang.MINI_WIDTH_DP_MIN, Math.min(Layang.MINI_WIDTH_DP_MAX,
                Settings.Secure.getIntForUser(cr, Layang.KEY_MINI_WIDTH_DP,
                        Layang.MINI_WIDTH_DP_DEFAULT, UserHandle.USER_CURRENT)));

        final boolean ghostChanged = Math.abs(nextGhost - mGhostAlpha) > 0.001f;
        final boolean gripChanged = nextGrip != mGripDp;
        final boolean miniChanged = nextMini != mMiniWidthDp;
        mGhostAlpha = nextGhost;
        mGripDp = nextGrip;
        mMiniWidthDp = nextMini;

        if (!applyLive || mWindows.size() == 0) {
            return;
        }
        for (int i = 0; i < mWindows.size(); i++) {
            final LayangWindow w = mWindows.valueAt(i);
            if (ghostChanged) {
                w.onGhostAlphaChanged();
            }
            if (gripChanged) {
                w.onGripSettingChanged();
            }
            if (miniChanged) {
                final LayangTaskState s = w.getState();
                if (s != null && s.getMode() == LayangTaskState.MODE_MINI) {
                    final DisplayLayout dl = displayLayout(w.getTaskInfo().displayId);
                    final Rect mini = new Rect();
                    computeMiniBounds(w.getBounds(), dl, mMiniWidthDp, mini);
                    if (!mini.equals(w.getBounds())) {
                        commitBounds(w, mini, LayangTransitionHandler.KIND_MODE);
                    }
                }
            }
        }
    }

    float getGhostAlpha() {
        return mGhostAlpha;
    }

    int getGripDp() {
        return mGripDp;
    }

    int getMiniWidthDp() {
        return mMiniWidthDp;
    }

    /** Compact mini/bubble placement matching YasvaraLayangService without letterboxing. */
    void computeMiniBounds(@NonNull Rect from, @NonNull DisplayLayout dl, int miniWidthDp,
            @NonNull Rect out) {
        final int displayW = dl.width();
        final int displayH = dl.height();
        final int dpi = dl.densityDpi();
        final int topInset = topInset(dl);
        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, dpi);
        final int miniW = LayangGeometry.dpToPx(
                Math.max(Layang.MINI_WIDTH_DP_MIN, Math.min(Layang.MINI_WIDTH_DP_MAX, miniWidthDp)),
                dpi);
        final float aspect = from.width() > 0 ? (float) from.height() / from.width() : 1f;
        int w;
        int h;
        if (aspect >= 1f) {
            w = miniW;
            h = Math.round(miniW * aspect);
            final int maxH = Math.round(displayH * 0.28f);
            if (h > maxH && maxH > 0) {
                final float shrink = (float) maxH / h;
                h = maxH;
                w = Math.max(LayangGeometry.dpToPx(56, dpi), Math.round(w * shrink));
            }
        } else {
            h = miniW;
            w = Math.round(miniW / aspect);
            final int maxW = Math.round(displayW * 0.34f);
            if (w > maxW && maxW > 0) {
                final float shrink = (float) maxW / w;
                w = maxW;
                h = Math.max(LayangGeometry.dpToPx(56, dpi), Math.round(h * shrink));
            }
        }
        final boolean right = from.centerX() >= displayW / 2;
        final boolean bottom = from.centerY() >= displayH / 2;
        final int left = right ? displayW - margin - w : margin;
        final int top = bottom ? displayH - margin - h : Math.max(margin, topInset);
        out.set(left, top, left + w, top + h);
        LayangGeometry.clamp(out, displayW, displayH, margin, topInset);
    }

    private void cacheSourceBounds(int taskId, @Nullable String pkg, @Nullable Rect src) {
        if (src == null || src.isEmpty()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final SourceHint hint = new SourceHint(new Rect(src), now);
        if (taskId >= 0) {
            mTaskSources.put(taskId, hint);
        }
        if (pkg != null && !pkg.isEmpty()) {
            mPkgSources.put(pkg, hint);
        }
        // Prune stale entries so the maps stay tiny.
        for (int i = mTaskSources.size() - 1; i >= 0; i--) {
            if (now - mTaskSources.valueAt(i).uptimeMs > SOURCE_HINT_TTL_MS) {
                mTaskSources.removeAt(i);
            }
        }
        for (int i = mPkgSources.size() - 1; i >= 0; i--) {
            if (now - mPkgSources.valueAt(i).uptimeMs > SOURCE_HINT_TTL_MS) {
                mPkgSources.removeAt(i);
            }
        }
    }

    @Nullable
    Rect consumeSourceBounds(int taskId, @Nullable String pkg) {
        final long now = SystemClock.uptimeMillis();
        final SourceHint byTask = mTaskSources.get(taskId);
        if (byTask != null) {
            mTaskSources.remove(taskId);
            if (pkg != null) {
                mPkgSources.remove(pkg);
            }
            if (now - byTask.uptimeMs <= SOURCE_HINT_TTL_MS) {
                return new Rect(byTask.bounds);
            }
        }
        if (pkg != null) {
            final SourceHint byPkg = mPkgSources.remove(pkg);
            if (byPkg != null && now - byPkg.uptimeMs <= SOURCE_HINT_TTL_MS) {
                return new Rect(byPkg.bounds);
            }
        }
        return null;
    }

    private void registerWithService() {
        final ILayangManager service = Layang.service();
        if (service == null) {
            // system_server publishes the service before SystemUI starts; this only happens
            // when SystemUI races a system_server restart.
            mBgExecutor.executeDelayed(this::registerWithService, 2000);
            return;
        }
        try {
            service.registerShell(mShellStub);
            mService = service;
            Log.i(TAG, "registered with " + Layang.SERVICE_NAME);
        } catch (RemoteException | SecurityException e) {
            Log.e(TAG, "registerShell failed", e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // TaskListener
    // ---------------------------------------------------------------------------------------

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        final LayangWindow old = mWindows.get(taskInfo.taskId);
        if (old != null) {
            old.destroy();
        }
        final LayangWindow w = new LayangWindow(this, taskInfo, leash);
        mWindows.put(taskInfo.taskId, w);
        w.create();
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        final LayangWindow w = mWindows.get(taskInfo.taskId);
        if (w != null) {
            w.onTaskInfoChanged(taskInfo);
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        final LayangWindow w = mWindows.get(taskInfo.taskId);
        if (w != null) {
            mWindows.remove(taskInfo.taskId);
            w.destroy();
        }
    }

    @Override
    public void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
        final LayangWindow w = mWindows.get(taskId);
        if (w == null) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        b.setParent(w.getLeash());
    }

    @Override
    public void reparentChildSurfaceToTask(int taskId, SurfaceControl sc,
            SurfaceControl.Transaction t) {
        final LayangWindow w = mWindows.get(taskId);
        if (w == null) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        t.reparent(sc, w.getLeash());
    }

    // ---------------------------------------------------------------------------------------
    // Actions (main thread). Each one is a single WCT in a Shell transition.
    // ---------------------------------------------------------------------------------------

    /**
     * A new rect of the same shape (move, scale, rotate, mini drag): a scaled app keeps its
     * virtual size, so nothing relayouts; a reflow app gets the density for the new size.
     */
    void commitBounds(@NonNull LayangWindow w, @NonNull Rect bounds, boolean animate) {
        commitBounds(w, bounds, animate
                ? LayangTransitionHandler.KIND_MODE : LayangTransitionHandler.KIND_COMMIT);
    }

    void commitBounds(@NonNull LayangWindow w, @NonNull Rect bounds, int transitionKind) {
        final LayangTaskState s = w.getState();
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(w.getTaskInfo().token, bounds);
        if (s != null && s.hasFlag(LayangTaskState.FLAG_REFLOW)) {
            final LayangTaskState next = s.withDensity(
                    reflowDensity(bounds, displayLayout(w.getTaskInfo().displayId)));
            if (!next.equals(s)) {
                wct.setLayangState(w.getTaskInfo().token, next);
            }
        }
        start(TRANSIT_CHANGE, wct, w.getTaskId(), transitionKind);
    }

    /**
     * A new shape (edge drag, ratio preset): the content stays at scale, so a scaled app's
     * virtual size becomes bounds / scale and the app lays itself out once for it.
     */
    void commitShape(@NonNull LayangWindow w, @NonNull Rect bounds, float scale,
            boolean animate) {
        final LayangTaskState s = w.getState();
        if (s == null || s.hasFlag(LayangTaskState.FLAG_REFLOW) || scale <= 0f) {
            commitBounds(w, bounds, animate);
            return;
        }
        final DisplayLayout dl = displayLayout(w.getTaskInfo().displayId);
        final int vw = Math.round(bounds.width() / scale);
        final int vh = Math.round(bounds.height() / scale);
        final int[] v = virtualSize(Math.max(vw, vh), Math.min(vw, vh), dl);
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(w.getTaskInfo().token, bounds);
        final LayangTaskState next = s.withVirtualSize(v[0], v[1]);
        if (!next.equals(s)) {
            wct.setLayangState(w.getTaskInfo().token, next);
        }
        start(TRANSIT_CHANGE, wct, w.getTaskId(), animate
                ? LayangTransitionHandler.KIND_MODE : LayangTransitionHandler.KIND_COMMIT);
    }

    /** {long, short} of a virtual size, or {0, 0} when it is the display's own. */
    private static int[] virtualSize(int vLong, int vShort, @NonNull DisplayLayout dl) {
        final int dLong = Math.max(dl.width(), dl.height());
        final int dShort = Math.min(dl.width(), dl.height());
        return Math.abs(vLong - dLong) <= 2 && Math.abs(vShort - dShort) <= 2
                ? new int[] {0, 0} : new int[] {vLong, vShort};
    }

    /** Density of a reflow window of these bounds; 0 when it is the display's. */
    private static int reflowDensity(@NonNull Rect bounds, @NonNull DisplayLayout dl) {
        final int dpi = LayangGeometry.readableDensity(bounds.width(), bounds.height(),
                dl.densityDpi());
        return dpi == dl.densityDpi() ? 0 : dpi;
    }

    /** Scale s of a window: its bounds over its virtual size (1 for a reflow window). */
    float scaleOf(@NonNull LayangWindow w) {
        final LayangTaskState s = w.getState();
        if (s == null || s.hasFlag(LayangTaskState.FLAG_REFLOW)) {
            return 1f;
        }
        final DisplayLayout dl = displayLayout(w.getTaskInfo().displayId);
        final int vLong = s.getVirtualLong() > 0 ? s.getVirtualLong()
                : Math.max(dl.width(), dl.height());
        final Rect b = w.getBounds();
        return (float) Math.max(b.width(), b.height()) / Math.max(1, vLong);
    }

    /**
     * Switches a window between scaling and reflow, and remembers it for the app. Leaving
     * reflow keeps the window's shape with the display's short side as the app's width.
     */
    void setReflow(int taskId, boolean reflow) {
        final LayangWindow w = mWindows.get(taskId);
        final LayangTaskState s = w != null ? w.getState() : null;
        if (s == null || s.hasFlag(LayangTaskState.FLAG_REFLOW) == reflow) {
            return;
        }
        final DisplayLayout dl = displayLayout(w.getTaskInfo().displayId);
        final Rect b = w.getBounds();
        final LayangTaskState next;
        if (reflow) {
            next = s.withFlags(LayangTaskState.FLAG_REFLOW, LayangTaskState.FLAG_REFLOW)
                    .withVirtualSize(0, 0).withDensity(reflowDensity(b, dl));
        } else {
            final int dShort = Math.min(dl.width(), dl.height());
            final float scale = (float) Math.min(b.width(), b.height()) / dShort;
            final int vLong = Math.round(Math.max(b.width(), b.height()) / scale);
            final int[] v = virtualSize(vLong, dShort, dl);
            next = s.withFlags(0, LayangTaskState.FLAG_REFLOW).withDensity(0)
                    .withVirtualSize(v[0], v[1]);
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setLayangState(w.getTaskInfo().token, next);
        start(TRANSIT_CHANGE, wct, taskId, LayangTransitionHandler.KIND_COMMIT);
    }

    void rotate(int taskId) {
        final LayangWindow w = mWindows.get(taskId);
        if (w != null) {
            w.rotate();
        }
    }

    void reshape(int taskId, float ratio) {
        final LayangWindow w = mWindows.get(taskId);
        if (w != null) {
            w.reshape(ratio);
        }
    }

    /** Brings a window to the front (and focus) as soon as its chrome is touched. */
    void raise(@NonNull LayangWindow w) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reorder(w.getTaskInfo().token, true /* onTop */);
        start(TRANSIT_TO_FRONT, wct, w.getTaskId(), LayangTransitionHandler.KIND_RAISE);
    }

    void enterLayang(int taskId, @NonNull LayangTaskState state, @NonNull Rect bounds,
            @Nullable Bundle opts) {
        final Rect src = opts != null
                ? opts.getParcelable(Layang.OPT_SOURCE_BOUNDS, Rect.class) : null;
        final String pkg = opts != null ? opts.getString(Layang.OPT_SOURCE_PKG) : null;
        if (src != null && !src.isEmpty()) {
            cacheSourceBounds(taskId, pkg, src);
        }
        // Sentinel pre-launch hint from YasvaraLayangService#openAppInternal for cold starts.
        if (taskId < 0) {
            return;
        }
        RunningTaskInfo info = mTaskOrganizer.getRunningTaskInfo(taskId);
        if (info == null) {
            final LayangWindow existing = mWindows.get(taskId);
            if (existing != null) {
                info = existing.getTaskInfo();
            }
        }
        if (info == null) {
            Log.w(TAG, "enterLayang: task " + taskId + " not in ShellTaskOrganizer");
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setWindowingMode(info.token, WINDOWING_MODE_FREEFORM);
        wct.setBounds(info.token, bounds);
        wct.setLayangState(info.token, state);
        wct.reorder(info.token, true /* onTop */);
        start(TRANSIT_CHANGE, wct, taskId, LayangTransitionHandler.KIND_ENTER);
    }

    void setMode(int taskId, int mode) {
        final LayangWindow w = mWindows.get(taskId);
        if (w == null || !LayangTaskState.isValidMode(mode)) {
            return;
        }
        final LayangTaskState state = w.getState();
        if (state == null || state.getMode() == mode) {
            return;
        }
        final WindowContainerToken token = w.getTaskInfo().token;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        final DisplayLayout dl = displayLayout(w.getTaskInfo().displayId);
        if (mode == LayangTaskState.MODE_MINI) {
            if (state.getMode() == LayangTaskState.MODE_FLOATING) {
                w.rememberFloatingBounds();
            }
            final Rect mini = new Rect();
            computeMiniBounds(w.getBounds(), dl, mMiniWidthDp, mini);
            wct.setBounds(token, mini);
        } else if (mode == LayangTaskState.MODE_FLOATING) {
            wct.setBounds(token, w.getRestoreBounds(dl, topInset(dl)));
            wct.reorder(token, true /* onTop */);
        } else if (state.getMode() == LayangTaskState.MODE_FLOATING) {
            w.rememberFloatingBounds();
        }
        wct.setLayangState(token, state.withMode(mode));
        start(TRANSIT_CHANGE, wct, taskId, mode == LayangTaskState.MODE_PARKED
                ? LayangTransitionHandler.KIND_PARK
                : LayangTransitionHandler.KIND_MODE);
    }

    void setFlags(int taskId, int flags, int mask) {
        final LayangWindow w = mWindows.get(taskId);
        final LayangTaskState state = w != null ? w.getState() : null;
        if (state == null) {
            return;
        }
        final LayangTaskState next = state.withFlags(flags, mask);
        if (next.equals(state)) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setLayangState(w.getTaskInfo().token, next);
        start(TRANSIT_CHANGE, wct, taskId, LayangTransitionHandler.KIND_COMMIT);
    }

    void toFullscreen(int taskId) {
        final LayangWindow w = mWindows.get(taskId);
        if (w == null) {
            return;
        }
        final WindowContainerToken token = w.getTaskInfo().token;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setWindowingMode(token, WINDOWING_MODE_FULLSCREEN);
        wct.setBounds(token, new Rect());
        wct.setLayangState(token, null);
        wct.reorder(token, true /* onTop */);
        start(TRANSIT_CHANGE, wct, taskId, LayangTransitionHandler.KIND_EXIT);
    }

    /** Leaves Layang and sends the task behind everything; it stays in Recents. */
    void minimize(int taskId) {
        final LayangWindow w = mWindows.get(taskId);
        if (w == null) {
            return;
        }
        if (w.getState() != null && w.getState().getMode() == LayangTaskState.MODE_FLOATING) {
            w.rememberFloatingBounds();
        }
        final WindowContainerToken token = w.getTaskInfo().token;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setWindowingMode(token, WINDOWING_MODE_FULLSCREEN);
        wct.setBounds(token, new Rect());
        wct.setLayangState(token, null);
        wct.reorder(token, false /* onTop */);
        start(TRANSIT_TO_BACK, wct, taskId, LayangTransitionHandler.KIND_MINIMIZE);
    }

    void close(int taskId) {
        final LayangWindow w = mWindows.get(taskId);
        if (w == null) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.removeTask(w.getTaskInfo().token);
        start(TRANSIT_CLOSE, wct, taskId, LayangTransitionHandler.KIND_CLOSE);
    }

    /** Closes and reopens an app floating so game GPU render-scale changes take effect. */
    void relaunchFloating(int taskId, @NonNull String pkg, int userId) {
        close(taskId);
        final ILayangManager service = mService;
        if (service == null) {
            return;
        }
        mBgExecutor.executeDelayed(() -> {
            try {
                service.openApp(new ComponentName(pkg, ""), userId, new Bundle());
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "relaunchFloating failed for " + pkg, e);
            }
        }, 320L);
    }

    void closeAll() {
        if (mWindows.size() == 0) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        for (int i = 0; i < mWindows.size(); i++) {
            wct.removeTask(mWindows.valueAt(i).getTaskInfo().token);
        }
        start(TRANSIT_CLOSE, wct, -1, LayangTransitionHandler.KIND_CLOSE);
    }

    void showMenu(int taskId) {
        final LayangWindow w = mWindows.get(taskId);
        if (w != null) {
            w.showMenu();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Display rotation (§4.6): R keeps its size; each orientation remembers its own place.
    // ---------------------------------------------------------------------------------------

    @Override
    public void onDisplayChange(int displayId, int fromRotation, int toRotation,
            @Nullable DisplayAreaInfo newDisplayAreaInfo, WindowContainerTransaction t) {
        if (fromRotation == toRotation || mWindows.size() == 0) {
            return;
        }
        // The layout may already have turned when this runs, so both shapes come from the
        // natural size and the two rotations, never from the layout's current width.
        final DisplayLayout dl = displayLayout(displayId);
        final boolean turned = dl.rotation() % 2 == 1;
        final int natW = turned ? dl.height() : dl.width();
        final int natH = turned ? dl.width() : dl.height();
        final int ow = fromRotation % 2 == 1 ? natH : natW;
        final int oh = fromRotation % 2 == 1 ? natW : natH;
        final int nw = toRotation % 2 == 1 ? natH : natW;
        final int nh = toRotation % 2 == 1 ? natW : natH;
        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi());
        final int top = topInset(dl);
        final float[] c = new float[2];
        for (int i = 0; i < mWindows.size(); i++) {
            final LayangWindow w = mWindows.valueAt(i);
            if (w.getTaskInfo().displayId != displayId) {
                continue;
            }
            final Rect b = w.getBounds();
            w.rememberPlace(ow > oh, b);
            final Rect back = w.getPlace(nw > nh);
            if (back != null) {
                c[0] = back.exactCenterX() / nw;
                c[1] = back.exactCenterY() / nh;
            } else {
                LayangGeometry.centre(b, ow, oh, c);
            }
            final int left = Math.round(c[0] * nw - b.width() / 2f);
            final int topPx = Math.round(c[1] * nh - b.height() / 2f);
            final Rect nb = new Rect(left, topPx, left + b.width(), topPx + b.height());
            // Uniform shrink keeps the shape, so the app never re-layouts.
            LayangGeometry.fit(nb, nw, nh, margin, top);
            t.setBounds(w.getTaskInfo().token, nb);
        }
        // The IME lift belongs to the old orientation.
        mImeLiftedTask = -1;
    }

    // ---------------------------------------------------------------------------------------
    // IME (§4.6): lift the focused window above the keyboard, put it back when it hides.
    // ---------------------------------------------------------------------------------------

    @Override
    public int onImeStartPositioning(int displayId, int hiddenTop, int shownTop,
            boolean showing, boolean isFloating, SurfaceControl.Transaction t) {
        if (isFloating) {
            return IME_ANIMATION_DEFAULT;
        }
        mMainExecutor.execute(() -> {
            if (showing) {
                liftForIme(displayId, shownTop);
            } else {
                dropFromIme();
            }
        });
        return IME_ANIMATION_DEFAULT;
    }

    private void liftForIme(int displayId, int imeTop) {
        LayangWindow focused = null;
        for (int i = 0; i < mWindows.size(); i++) {
            final LayangWindow w = mWindows.valueAt(i);
            final LayangTaskState s = w.getState();
            if (w.getTaskInfo().displayId == displayId && w.getTaskInfo().isFocused
                    && s != null && s.getMode() == LayangTaskState.MODE_FLOATING) {
                focused = w;
                break;
            }
        }
        if (focused == null) {
            return;
        }
        final DisplayLayout dl = displayLayout(displayId);
        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi());
        final Rect b = focused.getBounds();
        if (b.bottom <= imeTop - margin) {
            return;
        }
        final Rect lifted = new Rect(b);
        lifted.offset(0, Math.max(topInset(dl) - b.top, imeTop - margin - b.bottom));
        if (lifted.equals(b)) {
            return;
        }
        mImeLiftedTask = focused.getTaskId();
        mImeLiftedFrom = new Rect(b);
        mImeLiftedTo = new Rect(lifted);
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(focused.getTaskInfo().token, lifted);
        start(TRANSIT_CHANGE, wct, focused.getTaskId(), LayangTransitionHandler.KIND_MODE);
    }

    private void dropFromIme() {
        final LayangWindow w = mImeLiftedTask >= 0 ? mWindows.get(mImeLiftedTask) : null;
        final Rect from = mImeLiftedFrom;
        final Rect to = mImeLiftedTo;
        mImeLiftedTask = -1;
        mImeLiftedFrom = null;
        mImeLiftedTo = null;
        // Only if nobody moved it meanwhile.
        if (w == null || from == null || !w.getBounds().equals(to)) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(w.getTaskInfo().token, from);
        start(TRANSIT_CHANGE, wct, w.getTaskId(), LayangTransitionHandler.KIND_MODE);
    }

    // ---------------------------------------------------------------------------------------
    // Atomic corner/alpha preservation across all transitions (including Recents / Overview,
    // where Layang windows stay visible on top so switching background apps never hides them).
    // ---------------------------------------------------------------------------------------

    @Override
    public void onTransitionReady(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        // Stamp crop, corner radius and ghost alpha atomically into finishTransaction (and
        // stationary startTransaction) so non-Layang handlers and WM core finishTransactions
        // can never leave a Layang window square-cornered or full-opacity for even 1 frame.
        for (int i = 0; i < info.getChanges().size(); i++) {
            final TransitionInfo.Change c = info.getChanges().get(i);
            final RunningTaskInfo ti = c.getTaskInfo();
            if (ti == null || ti.layangState == null) {
                continue;
            }
            final SurfaceControl leash = c.getLeash();
            if (!leash.isValid()) {
                continue;
            }
            final Rect end = c.getEndAbsBounds();
            if (end.isEmpty()) {
                continue;
            }
            final LayangWindow w = mWindows.get(ti.taskId);
            final int radius = w != null ? w.getCornerRadius()
                    : LayangGeometry.dpToPx(LayangWindow.CORNER_RADIUS_DP,
                            displayLayout(ti.displayId).densityDpi());
            final float alpha = w != null ? w.restAlpha()
                    : (ti.layangState.isGhost() ? mGhostAlpha : 1f);
            finishTransaction.setWindowCrop(leash, end.width(), end.height())
                    .setCornerRadius(leash, radius)
                    .setAlpha(leash, alpha);
            if (c.getMode() == TRANSIT_CHANGE && c.getStartAbsBounds().equals(end)
                    && !mTransitionHandler.isAnimating(ti.taskId)) {
                startTransaction.setWindowCrop(leash, end.width(), end.height())
                        .setCornerRadius(leash, radius);
            }
        }
    }

    @Override
    public void onTransitionMerged(@NonNull IBinder merged, @NonNull IBinder playing) {
    }

    @Override
    public void onTransitionFinished(@NonNull IBinder transition, boolean aborted) {
        // Belt-and-suspenders shape guard after any transition finishes.
        if (mWindows.size() > 0) {
            final SurfaceControl.Transaction t = mTransactionPool.acquire();
            for (int i = 0; i < mWindows.size(); i++) {
                mWindows.valueAt(i).reapplyShape(t);
            }
            t.apply();
            mTransactionPool.release(t);
        }
    }

    /** Committed rects of the other visible floating windows, for magnetic snapping. */
    @NonNull
    ArrayList<Rect> otherWindowBounds(int taskId) {
        final ArrayList<Rect> out = new ArrayList<>();
        for (int i = 0; i < mWindows.size(); i++) {
            final LayangWindow w = mWindows.valueAt(i);
            final LayangTaskState s = w.getState();
            if (w.getTaskId() != taskId && s != null
                    && s.getMode() != LayangTaskState.MODE_PARKED) {
                out.add(new Rect(w.getBounds()));
            }
        }
        return out;
    }

    private void start(int type, @NonNull WindowContainerTransaction wct, int taskId, int kind) {
        final IBinder transition = mTransitions.startTransition(type, wct, mTransitionHandler);
        mTransitionHandler.expect(transition, taskId, kind);
    }

    // ---------------------------------------------------------------------------------------
    // Accessors for windows and the transition handler
    // ---------------------------------------------------------------------------------------

    @Nullable
    LayangWindow getWindow(int taskId) {
        return mWindows.get(taskId);
    }

    /** Whether a Layang animation is moving taskId; task-info bounds are stale meanwhile. */
    boolean getTransitionHandlerAnimating(int taskId) {
        return mTransitionHandler.isAnimating(taskId);
    }

    /** Whether a Layang change of taskId is on its way; its preview stays until it lands. */
    boolean isCommitPending(int taskId) {
        return mTransitionHandler.isPending(taskId);
    }

    @Nullable
    ILayangManager getService() {
        return mService;
    }

    Context getContext() {
        return mContext;
    }

    ShellExecutor getMainExecutor() {
        return mMainExecutor;
    }

    ShellExecutor getBgExecutor() {
        return mBgExecutor;
    }

    Choreographer getChoreographer() {
        return mChoreographer;
    }

    TransactionPool getTransactionPool() {
        return mTransactionPool;
    }

    IWindowSession getWindowSession() {
        return mWindowSession;
    }

    RootTaskDisplayAreaOrganizer getRootTdaOrganizer() {
        return mRootTdaOrganizer;
    }

    DisplayController getDisplayController() {
        return mDisplayController;
    }

    @NonNull
    DisplayLayout displayLayout(int displayId) {
        final DisplayLayout dl = mDisplayController.getDisplayLayout(displayId);
        return dl != null ? dl : new DisplayLayout(mContext, mContext.getDisplay());
    }

    /**
     * Top limit of a window: below the status bar while it shows, otherwise only clear of the
     * cutout, with room for the pill above the window.
     */
    int topInset(@NonNull DisplayLayout dl) {
        final android.view.DisplayCutout cutout = dl.getDisplayCutout();
        final int top = mStatusBarVisible ? dl.stableInsets().top
                : cutout != null ? cutout.getSafeInsetTop() : 0;
        return top + LayangGeometry.dpToPx(LayangPillView.TOUCH_H_DP, dl.densityDpi());
    }

    private static boolean isStatusBarVisible(@NonNull InsetsState state) {
        for (int i = state.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            if (source.getType() == WindowInsets.Type.statusBars() && source.isVisible()) {
                return true;
            }
        }
        return false;
    }

    public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        pw.println(prefix + TAG + " initialized=" + mInitialized + " service="
                + (mService != null) + " windows=" + mWindows.size()
                + " ghostAlpha=" + mGhostAlpha + " gripDp=" + mGripDp
                + " miniWidthDp=" + mMiniWidthDp);
        for (int i = 0; i < mWindows.size(); i++) {
            pw.println(prefix + "  " + mWindows.valueAt(i));
        }
    }

    @Override
    public String toString() {
        return TAG;
    }

    private static final class SourceHint {
        final Rect bounds;
        final long uptimeMs;

        SourceHint(Rect bounds, long uptimeMs) {
            this.bounds = bounds;
            this.uptimeMs = uptimeMs;
        }
    }

    /** Requests from YasvaraLayangService, run on the Shell main thread. */
    private final class ShellStub extends ILayangShell.Stub {
        @Override
        public void enterLayang(int taskId, LayangTaskState state, Rect bounds, Bundle opts) {
            mMainExecutor.execute(() -> LayangController.this.enterLayang(taskId, state, bounds,
                    opts));
        }

        @Override
        public void setMode(int taskId, int mode) {
            mMainExecutor.execute(() -> LayangController.this.setMode(taskId, mode));
        }

        @Override
        public void setFlags(int taskId, int flags, int mask) {
            mMainExecutor.execute(() -> LayangController.this.setFlags(taskId, flags, mask));
        }

        @Override
        public void toFullscreen(int taskId) {
            mMainExecutor.execute(() -> LayangController.this.toFullscreen(taskId));
        }

        @Override
        public void close(int taskId) {
            mMainExecutor.execute(() -> LayangController.this.close(taskId));
        }

        @Override
        public void closeAll() {
            mMainExecutor.execute(LayangController.this::closeAll);
        }

        @Override
        public void showMenu(int taskId) {
            mMainExecutor.execute(() -> LayangController.this.showMenu(taskId));
        }
    }
}
