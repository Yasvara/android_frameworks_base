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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_LAYANG;
import static com.android.wm.shell.transition.Transitions.TRANSIT_START_RECENTS_TRANSITION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.yasvara.ILayangManager;
import android.app.yasvara.ILayangShell;
import android.app.yasvara.Layang;
import android.app.yasvara.LayangGeometry;
import android.app.yasvara.LayangTaskState;
import android.content.Context;
import android.graphics.Rect;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.window.DisplayAreaInfo;
import android.window.TransitionInfo;
import android.os.RemoteException;
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

    @Nullable private ILayangManager mService;
    private boolean mInitialized;
    private final DisplayInsetsController mInsetsController;
    /** Whether the default display's status bar shows; an immersive app hides it. */
    private boolean mStatusBarVisible = true;

    /** Window lifted above the IME and where it was before (§4.6); not persisted. */
    private int mImeLiftedTask = -1;
    @Nullable private Rect mImeLiftedFrom;
    @Nullable private Rect mImeLiftedTo;

    /** The recents transition while Overview hides the windows (§6.3). */
    @Nullable private IBinder mRecentsTransition;
    private boolean mHiddenForRecents;

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
     * Animated commits morph from where the window is drawn; the others are jump cuts.
     */
    void commitBounds(@NonNull LayangWindow w, @NonNull Rect bounds, boolean animate) {
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
        start(TRANSIT_CHANGE, wct, w.getTaskId(), animate
                ? LayangTransitionHandler.KIND_MODE : LayangTransitionHandler.KIND_COMMIT);
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

    void enterLayang(int taskId, @NonNull LayangTaskState state, @NonNull Rect bounds) {
        final RunningTaskInfo info = mTaskOrganizer.getRunningTaskInfo(taskId);
        if (info == null) {
            Log.w(TAG, "enterLayang: no task " + taskId);
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
            LayangGeometry.mini(w.getBounds(), dl.width(), dl.height(), dl.densityDpi(),
                    topInset(dl), mini);
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
    // Overview (§6.3): the windows fade out while Recents is open and come back after.
    // ---------------------------------------------------------------------------------------

    @Override
    public void onTransitionReady(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        if (info.getType() == TRANSIT_START_RECENTS_TRANSITION && mWindows.size() > 0) {
            mRecentsTransition = transition;
            setHiddenForRecents(true);
        }
    }

    @Override
    public void onTransitionMerged(@NonNull IBinder merged, @NonNull IBinder playing) {
        if (merged == mRecentsTransition) {
            mRecentsTransition = playing;
        }
    }

    @Override
    public void onTransitionFinished(@NonNull IBinder transition, boolean aborted) {
        if (transition == mRecentsTransition) {
            mRecentsTransition = null;
            setHiddenForRecents(false);
        }
        // Whoever animated the transition may have left a square, uncropped leash.
        if (mWindows.size() > 0) {
            final SurfaceControl.Transaction t = mTransactionPool.acquire();
            for (int i = 0; i < mWindows.size(); i++) {
                mWindows.valueAt(i).reapplyShape(t);
            }
            t.apply();
            mTransactionPool.release(t);
        }
    }

    private void setHiddenForRecents(boolean hidden) {
        if (mHiddenForRecents == hidden) {
            return;
        }
        mHiddenForRecents = hidden;
        final ValueAnimator va = ValueAnimator.ofFloat(hidden ? 1f : 0f, hidden ? 0f : 1f);
        va.setDuration(150);
        final ArrayList<LayangWindow> windows = new ArrayList<>();
        for (int i = 0; i < mWindows.size(); i++) {
            windows.add(mWindows.valueAt(i));
        }
        if (!hidden) {
            final SurfaceControl.Transaction t = mTransactionPool.acquire();
            for (LayangWindow w : windows) {
                w.setOverviewVisible(t, true, 0f);
            }
            t.apply();
            mTransactionPool.release(t);
        }
        va.addUpdateListener(a -> {
            final SurfaceControl.Transaction t = mTransactionPool.acquire();
            for (LayangWindow w : windows) {
                w.setOverviewAlpha(t, (float) a.getAnimatedValue());
            }
            t.apply();
            mTransactionPool.release(t);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                final SurfaceControl.Transaction t = mTransactionPool.acquire();
                for (LayangWindow w : windows) {
                    // Hidden surfaces also stop taking touches meant for Overview.
                    w.setOverviewVisible(t, !mHiddenForRecents, mHiddenForRecents ? 0f : 1f);
                }
                t.apply();
                mTransactionPool.release(t);
            }
        });
        va.start();
    }

    boolean isHiddenForRecents() {
        return mHiddenForRecents;
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
                + (mService != null) + " windows=" + mWindows.size());
        for (int i = 0; i < mWindows.size(); i++) {
            pw.println(prefix + "  " + mWindows.valueAt(i));
        }
    }

    @Override
    public String toString() {
        return TAG;
    }

    /** Requests from YasvaraLayangService, run on the Shell main thread. */
    private final class ShellStub extends ILayangShell.Stub {
        @Override
        public void enterLayang(int taskId, LayangTaskState state, Rect bounds, Bundle opts) {
            mMainExecutor.execute(() -> LayangController.this.enterLayang(taskId, state, bounds));
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
