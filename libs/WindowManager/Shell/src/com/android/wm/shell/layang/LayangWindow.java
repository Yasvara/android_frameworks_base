/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wm.shell.layang;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.yasvara.Layang;
import android.app.yasvara.LayangGeometry;
import android.app.yasvara.LayangTaskState;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import com.android.wm.shell.common.DisplayLayout;

/**
 * One Layang window in Shell: the pill, the resize handles, the
 * mini shield, and the gestures that preview a move, scale or reshape on the task leash.
 *
 * <p>The chrome lives in its own container under the task display area, layered relative to
 * the task leash, so it is a sibling of the task and never scales with it (§3). A gesture
 * changes only the leash and the chrome positions, one transaction per vsync with zero
 * allocations and zero binder calls; finger-up commits once through the controller.
 *
 * <p>Corners scale: the app keeps its layout. Edges reshape: the content keeps its scale and the
 * app lays itself out once for the new shape on finger-up.
 */
final class LayangWindow {
    private static final String TAG = "LayangWindow";

    static final int PILL_GAP_DP = 6;
    static final int CORNER_RADIUS_DP = 16;
    /** A flick down needs this much travel, so a quick nudge of the pill only moves it. */
    static final int FLICK_TRAVEL_DP = 96;
    /** Smallest width while scaling: just enough to keep the corners grabbable. */
    static final int MIN_WIDTH_DP = 64;
    /** Corner handles reach this far along each edge; the rest of an edge reshapes. */
    static final int CORNER_ARM_DP = 40;
    /** Smallest side of a reflow window while reshaping. */
    static final int MIN_REFLOW_DP = 120;
    /** The line shown on the grabbed corner or edge: thickness, gap, corner arm, edge length. */
    private static final int HANDLE_DP = 4;
    private static final int HANDLE_GAP_DP = 4;
    private static final int HANDLE_ARM_DP = 28;
    private static final int HANDLE_EDGE_DP = 56;
    /** Scale detents, as fractions of the largest window that fits. */
    private static final float[] SCALE_DETENTS = {0.5f, 0.75f, 1f};
    /** How close, relative to a detent, a size must come to snap onto it. */
    private static final float DETENT_PULL = 0.025f;
    /** A flung mini window travels this long at its release speed before it settles. */
    private static final float MINI_THROW_S = 0.12f;

    private static final int G_NONE = 0;
    private static final int G_PILL = 1;
    private static final int G_MOVE = 2;
    private static final int G_SCALE = 3;
    private static final int G_SHIELD = 4;
    private static final int G_SHIELD_MOVE = 5;
    private static final int G_EDGE = 6;

    static final int EDGE_LEFT = 1;
    static final int EDGE_RIGHT = 2;
    static final int EDGE_BOTTOM = 3;

    private final LayangController mController;
    private final Context mContext;
    private final SurfaceControl mLeash;
    private RunningTaskInfo mTaskInfo;

    /** Committed on-screen rect R, as WM core has it. */
    private final Rect mBounds = new Rect();
    /** Where the window is drawn right now (gesture preview or animation). */
    private final Rect mVisual = new Rect();
    private final Rect mTmp = new Rect();
    /** Pre-allocated scratch buffers for zero-alloc 240 Hz gesture tracking. */
    private final int[] mWidthLimits = new int[2];
    private final float[] mScaleDetents = new float[SCALE_DETENTS.length];
    private final float[] mRatioDetents = new float[4];
    private final Region mInputRegion = new Region();

    /** Floating bounds to come back to from mini or parked. */
    @Nullable private Rect mRestoreBounds;
    /** Bounds before a double-tap made the window as large as it goes. */
    @Nullable private Rect mPreToggleBounds;
    /** Where the window was in portrait [0] and landscape [1], for turning back. */
    private final Rect[] mPlaces = new Rect[2];

    @Nullable private SurfaceControl mDecor;
    @Nullable private SurfaceControlViewHost mPillHost;
    @Nullable private SurfaceControl mPillSurface;
    @Nullable private LayangPillView mPillView;
    @Nullable private LayangInputSurface mFrameInput;
    @Nullable private LayangMenu mMenu;
    /** Fills what a reshaped app has not drawn yet; below the task. */
    @Nullable private SurfaceControl mVeil;
    @Nullable private SurfaceControl mHandleH;
    @Nullable private SurfaceControl mHandleV;
    /** Live preview overlay showing the mini/bubble footprint while adjusting size in menu. */
    @Nullable private SurfaceControl mMiniPreview;

    private final int mPillW;
    private final int mPillH;
    private final int mPillGap;
    private final int mBarH;
    private final int mCornerArm;
    private final int mCornerRadius;
    private final int mHandle;
    private final int mHandleGap;
    private final int mHandleArm;
    private final int mHandleEdge;
    private final int mTouchSlop;
    private final int mFlingVelocity;
    @Nullable private final Vibrator mVibrator;
    /** Handle thickness outside and inside the window (Laras, 2/3 out and 1/3 in). */
    private int mGrip;
    private int mGripInside;
    private int mGripDp = -1;
    /** Live ghost opacity preview from Quick Tuning slider, or negative when inactive. */
    private float mGhostAlphaPreview = -1f;

    private int mGesture = G_NONE;
    private int mCorner;
    private int mEdge;
    private int mDetent = -1;
    private float mDownX;
    private float mDownY;
    private float mLastX;
    private float mLastY;
    @Nullable private VelocityTracker mVelocity;
    @Nullable private Runnable mPendingMenu;
    private boolean mFramePending;
    private boolean mDestroyed;
    private final Choreographer.FrameCallback mFrameCallback = frameTimeNanos -> applyFrame();

    LayangWindow(@NonNull LayangController controller, @NonNull RunningTaskInfo taskInfo,
            @NonNull SurfaceControl leash) {
        mController = controller;
        mContext = controller.getContext();
        mTaskInfo = taskInfo;
        mLeash = leash;
        mBounds.set(taskInfo.configuration.windowConfiguration.getBounds());
        mVisual.set(mBounds);
        final DisplayLayout dl = controller.displayLayout(taskInfo.displayId);
        final int dpi = dl.densityDpi();
        mPillW = LayangGeometry.dpToPx(LayangPillView.TOUCH_W_DP, dpi);
        mPillH = LayangGeometry.dpToPx(LayangPillView.TOUCH_H_DP, dpi);
        mBarH = LayangGeometry.dpToPx(LayangPillView.BAR_H_DP, dpi);
        mPillGap = LayangGeometry.dpToPx(PILL_GAP_DP, dpi);
        mCornerArm = LayangGeometry.dpToPx(CORNER_ARM_DP, dpi);
        mCornerRadius = LayangGeometry.dpToPx(CORNER_RADIUS_DP, dpi);
        mHandle = LayangGeometry.dpToPx(HANDLE_DP, dpi);
        mHandleGap = LayangGeometry.dpToPx(HANDLE_GAP_DP, dpi);
        mHandleArm = LayangGeometry.dpToPx(HANDLE_ARM_DP, dpi);
        mHandleEdge = LayangGeometry.dpToPx(HANDLE_EDGE_DP, dpi);
        refreshGrip(dpi);
        final ViewConfiguration vc = ViewConfiguration.get(mContext);
        mTouchSlop = vc.getScaledTouchSlop();
        mFlingVelocity = vc.getScaledMaximumFlingVelocity() / 4;
        mVibrator = mContext.getSystemService(Vibrator.class);
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    void create() {
        final SurfaceControl.Builder b = new SurfaceControl.Builder()
                .setName("Layang decor #" + mTaskInfo.taskId)
                .setContainerLayer()
                .setCallsite("LayangWindow.create");
        mController.getRootTdaOrganizer().attachToDisplayArea(mTaskInfo.displayId, b);
        mDecor = b.build();
        final SurfaceControl.Builder vb = new SurfaceControl.Builder()
                .setName("Layang veil #" + mTaskInfo.taskId)
                .setColorLayer()
                .setCallsite("LayangWindow.create");
        mController.getRootTdaOrganizer().attachToDisplayArea(mTaskInfo.displayId, vb);
        mVeil = vb.build();
        mHandleH = handleLayer("h");
        mHandleV = handleLayer("v");

        final Display display = mController.getDisplayController()
                .getDisplay(mTaskInfo.displayId);
        mPillView = new LayangPillView(mContext);
        mPillView.setTouchListener(this::onPillTouch);
        mPillView.setAccessibilityActionListener(this::onAccessibilityAction);
        mPillHost = new SurfaceControlViewHost(mContext,
                display != null ? display : mContext.getDisplay(),
                (android.window.InputTransferToken) null, "LayangPill");
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(mPillW, mPillH,
                TYPE_APPLICATION, FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("LayangPill #" + mTaskInfo.taskId);
        lp.setTrustedOverlay();
        mPillHost.setView(mPillView, lp);
        mPillSurface = mPillHost.getSurfacePackage().getSurfaceControl();

        mFrameInput = new LayangInputSurface(mController.getWindowSession(),
                mController.getMainExecutor(), mController.getBgExecutor(),
                mTaskInfo.displayId, mDecor, "LayangFrame #" + mTaskInfo.taskId,
                this::onFrameTouch);

        final boolean night = (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final float[] accent = rgb(mContext.getColor(android.R.color.system_accent1_300));
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        t.reparent(mPillSurface, mDecor)
                .setLayer(mPillSurface, 2)
                .show(mPillSurface)
                .setLayer(mFrameInput.getSurface(), 1)
                .show(mFrameInput.getSurface())
                .setLayer(mHandleH, 3)
                .setLayer(mHandleV, 3)
                .setColor(mHandleH, accent)
                .setColor(mHandleV, accent)
                .setCornerRadius(mHandleH, mHandle / 2f)
                .setCornerRadius(mHandleV, mHandle / 2f)
                .hide(mHandleH)
                .hide(mHandleV)
                .setRelativeLayer(mDecor, mLeash, 1)
                .show(mDecor)
                .setRelativeLayer(mVeil, mLeash, -1)
                .setColor(mVeil, rgb(mContext.getColor(night
                        ? android.R.color.system_neutral1_800
                        : android.R.color.system_neutral1_100)))
                .setCornerRadius(mVeil, mCornerRadius)
                .hide(mVeil);
        applyLeashShape(t, mBounds);
        layoutDecor(t, mBounds);
        t.apply();
        mController.getTransactionPool().release(t);
        updateChromeState();
    }

    @NonNull
    private SurfaceControl handleLayer(@NonNull String which) {
        return new SurfaceControl.Builder()
                .setName("Layang handle " + which + " #" + mTaskInfo.taskId)
                .setColorLayer()
                .setParent(mDecor)
                .setCallsite("LayangWindow.create")
                .build();
    }

    private static float[] rgb(int color) {
        return new float[] {Color.red(color) / 255f, Color.green(color) / 255f,
                Color.blue(color) / 255f};
    }

    void destroy() {
        if (mDestroyed) {
            return;
        }
        mDestroyed = true;
        if (mFramePending) {
            mController.getChoreographer().removeFrameCallback(mFrameCallback);
            mFramePending = false;
        }
        endGesture();
        cancelPendingMenu();
        if (mMenu != null) {
            mMenu.dismissImmediate();
            mMenu = null;
        }
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        if (mFrameInput != null) {
            mFrameInput.close(t);
            mFrameInput = null;
        }
        if (mPillHost != null) {
            mPillHost.release();
            mPillHost = null;
            mPillSurface = null;
        }
        if (mMiniPreview != null) {
            if (mMiniPreview.isValid()) {
                t.remove(mMiniPreview);
                mMiniPreview.release();
            }
            mMiniPreview = null;
        }
        if (mHandleH != null) {
            if (mHandleH.isValid()) {
                t.remove(mHandleH);
                mHandleH.release();
            }
            mHandleH = null;
        }
        if (mHandleV != null) {
            if (mHandleV.isValid()) {
                t.remove(mHandleV);
                mHandleV.release();
            }
            mHandleV = null;
        }
        if (mDecor != null) {
            if (mDecor.isValid()) {
                t.remove(mDecor);
                mDecor.release();
            }
            mDecor = null;
        }
        if (mVeil != null) {
            if (mVeil.isValid()) {
                t.remove(mVeil);
                mVeil.release();
            }
            mVeil = null;
        }
        // The task left Layang (or closed): drop the rounded shape we put on its leash.
        if (mLeash.isValid()) {
            t.setCornerRadius(mLeash, 0);
        }
        t.apply();
        mController.getTransactionPool().release(t);
    }

    void onTaskInfoChanged(@NonNull RunningTaskInfo info) {
        final LayangTaskState oldState = mTaskInfo.layangState;
        mTaskInfo = info;
        final Rect b = info.configuration.windowConfiguration.getBounds();
        if (mGesture == G_NONE && !b.equals(mBounds)
                && !mController.getTransitionHandlerAnimating(info.taskId)
                && !mController.isCommitPending(info.taskId)) {
            onBoundsCommitted(b, null);
        }
        if (mPillView != null) {
            mPillView.setWindowFocused(info.isFocused);
        }
        if (oldState == null || info.layangState == null
                || oldState.getMode() != info.layangState.getMode()
                || oldState.getFlags() != info.layangState.getFlags()) {
            updateChromeState();
        }
    }

    /** WM core has the new bounds: reset the shape and move the chrome (in t, if given). */
    void onBoundsCommitted(@NonNull Rect bounds, @Nullable SurfaceControl.Transaction t) {
        if (mDestroyed) {
            return;
        }
        mBounds.set(bounds);
        mVisual.set(bounds);
        final SurfaceControl.Transaction tx = t != null ? t
                : mController.getTransactionPool().acquire();
        applyLeashShape(tx, bounds);
        layoutDecor(tx, bounds);
        if (mVeil != null && mVeil.isValid()) {
            tx.hide(mVeil);
        }
        if (mLeash.isValid()) {
            tx.setAlpha(mLeash, restAlpha());
        }
        if (t == null) {
            tx.apply();
            mController.getTransactionPool().release(tx);
        }
        updateInputRegion();
    }

    // ---------------------------------------------------------------------------------------
    // Chrome & live previews
    // ---------------------------------------------------------------------------------------

    /** Restores the resting shape unless a gesture or animation is drawing the window. */
    void reapplyShape(@NonNull SurfaceControl.Transaction t) {
        if (mDestroyed || !mLeash.isValid() || mGesture != G_NONE
                || mController.getTransitionHandlerAnimating(getTaskId())) {
            return;
        }
        applyLeashShape(t, mBounds);
        if (!isParked()) {
            t.setAlpha(mLeash, restAlpha());
        }
    }

    /** Rounded corners (D16) and the crop that makes them, on the task leash. */
    void applyLeashShape(@NonNull SurfaceControl.Transaction t, @NonNull Rect bounds) {
        if (!mLeash.isValid()) {
            return;
        }
        t.setWindowCrop(mLeash, bounds.width(), bounds.height());
        t.setCornerRadius(mLeash, mCornerRadius);
    }

    /**
     * Opacity at rest: a ghost window is see-through at the cached ghost alpha (20..80 %),
     * never above the untrusted-touch limit (F12). Zero IPC.
     */
    float restAlpha() {
        if (mGhostAlphaPreview >= 0f) {
            return mGhostAlphaPreview;
        }
        final LayangTaskState s = mTaskInfo.layangState;
        if (s == null || !s.isGhost()) {
            return 1f;
        }
        return mController.getGhostAlpha();
    }

    /** Live ghost opacity change from ContentObserver. */
    void onGhostAlphaChanged() {
        if (mDestroyed || !mLeash.isValid() || isParked()) {
            return;
        }
        final LayangTaskState s = mTaskInfo.layangState;
        if (s != null && s.isGhost() && mGhostAlphaPreview < 0f) {
            final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
            t.setAlpha(mLeash, restAlpha());
            t.apply();
            mController.getTransactionPool().release(t);
        }
    }

    /** Live grip thickness change from ContentObserver. */
    void onGripSettingChanged() {
        if (mDestroyed) {
            return;
        }
        updateInputRegion();
    }

    /** Live transparency preview while dragging the Ghost Opacity slider in Quick Tuning. */
    void previewGhostAlpha(float alpha) {
        if (mDestroyed || !mLeash.isValid()) {
            return;
        }
        mGhostAlphaPreview = Math.max(0.2f, Math.min(Layang.MAX_GHOST_ALPHA, alpha));
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        t.setAlpha(mLeash, mGhostAlphaPreview);
        t.apply();
        mController.getTransactionPool().release(t);
    }

    void clearGhostPreview() {
        if (mGhostAlphaPreview < 0f) {
            return;
        }
        mGhostAlphaPreview = -1f;
        if (mDestroyed || !mLeash.isValid()) {
            return;
        }
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        t.setAlpha(mLeash, restAlpha());
        t.apply();
        mController.getTransactionPool().release(t);
    }

    /** Shows a live corner footprint overlay representing the mini/bubble size for widthDp. */
    void showMiniSizePreview(int widthDp) {
        if (mDestroyed) {
            return;
        }
        final DisplayLayout dl = mController.displayLayout(mTaskInfo.displayId);
        mController.computeMiniBounds(mBounds, dl, widthDp, mTmp);
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        if (mMiniPreview == null || !mMiniPreview.isValid()) {
            final SurfaceControl.Builder b = new SurfaceControl.Builder()
                    .setName("Layang mini preview #" + mTaskInfo.taskId)
                    .setColorLayer()
                    .setCallsite("LayangWindow.miniPreview");
            mController.getRootTdaOrganizer().attachToDisplayArea(mTaskInfo.displayId, b);
            mMiniPreview = b.build();
            t.setColor(mMiniPreview, rgb(mContext.getColor(android.R.color.system_accent1_300)))
                    .setCornerRadius(mMiniPreview, mCornerRadius)
                    .setLayer(mMiniPreview, Integer.MAX_VALUE - 2)
                    .setAlpha(mMiniPreview, 0.38f);
        }
        t.setPosition(mMiniPreview, mTmp.left, mTmp.top)
                .setWindowCrop(mMiniPreview, mTmp.width(), mTmp.height())
                .show(mMiniPreview)
                .apply();
        mController.getTransactionPool().release(t);
    }

    void hideMiniSizePreview() {
        if (mMiniPreview == null) {
            return;
        }
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        if (mMiniPreview.isValid()) {
            t.remove(mMiniPreview);
            mMiniPreview.release();
        }
        mMiniPreview = null;
        t.apply();
        mController.getTransactionPool().release(t);
    }

    void setPillAlpha(@NonNull SurfaceControl.Transaction t, float alpha) {
        if (mDestroyed || mPillSurface == null || !mPillSurface.isValid()) {
            return;
        }
        t.setAlpha(mPillSurface, Math.max(0f, Math.min(1f, alpha)));
    }

    private boolean isParked() {
        final LayangTaskState s = mTaskInfo.layangState;
        return s != null && s.getMode() == LayangTaskState.MODE_PARKED;
    }

    /** Positions the pill, the frame input and the handle lines for a window drawn at v. */
    void layoutDecor(@NonNull SurfaceControl.Transaction t, @NonNull Rect v) {
        if (mDestroyed || mDecor == null || !mDecor.isValid()
                || mPillSurface == null || !mPillSurface.isValid() || mFrameInput == null) {
            return;
        }
        final float barCenterY = v.top - mPillGap - mBarH / 2f;
        t.setPosition(mPillSurface, v.centerX() - mPillW / 2f, barCenterY - mPillH / 2f);
        final SurfaceControl frameSc = mFrameInput.getSurface();
        if (frameSc.isValid()) {
            t.setPosition(frameSc, v.left - mGrip, v.top - mGrip);
            // A container layer has no bounds of its own: without a crop input sees an empty
            // frame and drops the touch region.
            t.setWindowCrop(frameSc, v.width() + 2 * mGrip, v.height() + 2 * mGrip);
        }
        layoutHandles(t, v);
    }

    /** The accent line on the grabbed corner (an L) or edge, only while it is held. */
    private void layoutHandles(@NonNull SurfaceControl.Transaction t, @NonNull Rect v) {
        if (mHandleH == null || mHandleV == null || !mHandleH.isValid() || !mHandleV.isValid()) {
            return;
        }
        final int th = mHandle;
        final int gap = mHandleGap;
        if (mGesture == G_SCALE) {
            final boolean right = (mCorner & 1) != 0;
            final boolean bottom = (mCorner & 2) != 0;
            final int x = right ? v.right + gap : v.left - gap - th;
            final int y = bottom ? v.bottom + gap : v.top - gap - th;
            final int armX = right ? v.right + gap + th - mHandleArm : x;
            final int armY = bottom ? v.bottom + gap + th - mHandleArm : y;
            placeHandle(t, mHandleH, armX, y, mHandleArm, th);
            placeHandle(t, mHandleV, x, armY, th, mHandleArm);
        } else if (mGesture == G_EDGE && mEdge == EDGE_BOTTOM) {
            final int len = Math.min(mHandleEdge, v.width());
            placeHandle(t, mHandleH, v.centerX() - len / 2, v.bottom + gap, len, th);
            t.hide(mHandleV);
        } else if (mGesture == G_EDGE) {
            final int len = Math.min(mHandleEdge, v.height());
            final int x = mEdge == EDGE_LEFT ? v.left - gap - th : v.right + gap;
            placeHandle(t, mHandleV, x, v.centerY() - len / 2, th, len);
            t.hide(mHandleH);
        } else {
            t.hide(mHandleH);
            t.hide(mHandleV);
        }
    }

    private static void placeHandle(@NonNull SurfaceControl.Transaction t,
            @NonNull SurfaceControl sc, int x, int y, int w, int h) {
        t.setPosition(sc, x, y).setWindowCrop(sc, w, h).show(sc);
    }

    /** Visibility of the chrome and the touch region for the current mode and flags. */
    private void updateChromeState() {
        final LayangTaskState s = mTaskInfo.layangState;
        if (mDestroyed || s == null || mDecor == null || !mDecor.isValid()
                || mPillSurface == null || !mPillSurface.isValid()) {
            return;
        }
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        final boolean parked = s.getMode() == LayangTaskState.MODE_PARKED;
        final boolean mini = s.getMode() == LayangTaskState.MODE_MINI;
        if (parked) {
            t.hide(mDecor);
        } else {
            t.show(mDecor);
        }
        if (mini) {
            t.hide(mPillSurface);
        } else {
            t.show(mPillSurface);
        }
        if (!parked && mLeash.isValid()) {
            t.setAlpha(mLeash, restAlpha());
        }
        t.apply();
        mController.getTransactionPool().release(t);
        if (mPillView != null) {
            mPillView.setWindowFocused(mTaskInfo.isFocused);
        }
        updateInputRegion();
    }

    /** Reads the cached handle thickness; true when it changed. Zero IPC. */
    private boolean refreshGrip(int dpi) {
        final int dp = mController.getGripDp();
        if (dp == mGripDp) {
            return false;
        }
        mGripDp = dp;
        final int total = LayangGeometry.dpToPx(dp, dpi);
        mGrip = Math.round(total * 2f / 3f);
        mGripInside = total - mGrip;
        return true;
    }

    /** Whether edges reshape this window: any app but a game whose shape is locked. */
    private static boolean canReshape(@NonNull LayangTaskState s) {
        return !s.isGame() || s.hasFlag(LayangTaskState.FLAG_ASPECT_UNLOCKED)
                || s.hasFlag(LayangTaskState.FLAG_REFLOW);
    }

    /** How far the corner handles reach into the window along each edge. */
    private int cornerReach() {
        return Math.min(mCornerArm, Math.min(mBounds.width(), mBounds.height()) / 2);
    }

    /**
     * L-shaped corner handles and, where the window can be reshaped, left, right and bottom
     * edge bands while floating; a full shield while mini (§4.5). The top edge stays the pill's.
     */
    private void updateInputRegion() {
        if (mDestroyed || mFrameInput == null) {
            return;
        }
        if (refreshGrip(mController.displayLayout(mTaskInfo.displayId).densityDpi())
                && mDecor != null && mGesture == G_NONE) {
            final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
            layoutDecor(t, mBounds);
            t.apply();
            mController.getTransactionPool().release(t);
        }
        final LayangTaskState s = mTaskInfo.layangState;
        final Region r = mInputRegion;
        r.setEmpty();
        final int w = mBounds.width();
        final int h = mBounds.height();
        final int g = mGrip;
        if (s != null && s.getMode() == LayangTaskState.MODE_MINI) {
            r.set(g, g, g + w, g + h);
        } else if (s != null && s.getMode() == LayangTaskState.MODE_FLOATING && !s.isGhost()
                && !s.hasFlag(LayangTaskState.FLAG_LOCKED)) {
            final int band = g + mGripInside;
            final int arm = g + cornerReach();
            final int fw = w + 2 * g;
            final int fh = h + 2 * g;
            r.op(0, 0, arm, band, Region.Op.UNION);
            r.op(0, 0, band, arm, Region.Op.UNION);
            r.op(fw - arm, 0, fw, band, Region.Op.UNION);
            r.op(fw - band, 0, fw, arm, Region.Op.UNION);
            r.op(0, fh - band, arm, fh, Region.Op.UNION);
            r.op(0, fh - arm, band, fh, Region.Op.UNION);
            r.op(fw - arm, fh - band, fw, fh, Region.Op.UNION);
            r.op(fw - band, fh - arm, fw, fh, Region.Op.UNION);
            if (canReshape(s)) {
                r.op(0, arm, band, fh - arm, Region.Op.UNION);
                r.op(fw - band, arm, fw, fh - arm, Region.Op.UNION);
                r.op(arm, fh - band, fw - arm, fh, Region.Op.UNION);
            }
        }
        mFrameInput.setRegion(r);
    }

    // ---------------------------------------------------------------------------------------
    // Gestures (zero-alloc hot path at 240 Hz)
    // ---------------------------------------------------------------------------------------

    private void onPillTouch(@NonNull MotionEvent e) {
        final LayangTaskState s = mTaskInfo.layangState;
        if (s == null) {
            return;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mGesture != G_NONE) {
                    return;
                }
                beginGesture(G_PILL, e);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mGesture != G_PILL && mGesture != G_MOVE) {
                    return;
                }
                track(e);
                if (mGesture == G_PILL && passedSlop(e)
                        && !s.hasFlag(LayangTaskState.FLAG_LOCKED)) {
                    mGesture = G_MOVE;
                    cancelPendingMenu();
                }
                if (mGesture == G_MOVE) {
                    mVisual.set(mBounds);
                    mVisual.offset(Math.round(e.getRawX() - mDownX),
                            Math.round(e.getRawY() - mDownY));
                    scheduleFrame();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mGesture == G_PILL) {
                    endGesture();
                    onPillTap();
                } else if (mGesture == G_MOVE) {
                    track(e);
                    finishMove(e);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelGesture();
                break;
        }
    }

    /** One tap opens the menu after the double-tap timeout; a second tap toggles the size. */
    private void onPillTap() {
        final LayangTaskState s = mTaskInfo.layangState;
        if (mPendingMenu != null) {
            cancelPendingMenu();
            if (s != null && !s.hasFlag(LayangTaskState.FLAG_LOCKED)) {
                toggleSize();
            }
            return;
        }
        mPendingMenu = () -> {
            mPendingMenu = null;
            showMenu();
        };
        mController.getMainExecutor().executeDelayed(mPendingMenu,
                ViewConfiguration.getDoubleTapTimeout());
    }

    private void cancelPendingMenu() {
        if (mPendingMenu != null) {
            mController.getMainExecutor().removeCallbacks(mPendingMenu);
            mPendingMenu = null;
        }
    }

    private void onFrameTouch(@NonNull MotionEvent e) {
        final LayangTaskState s = mTaskInfo.layangState;
        if (s == null) {
            return;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (mGesture != G_NONE) {
                    return;
                }
                if (s.getMode() == LayangTaskState.MODE_MINI) {
                    beginGesture(G_SHIELD, e);
                } else if (s.getMode() == LayangTaskState.MODE_FLOATING
                        && !s.hasFlag(LayangTaskState.FLAG_LOCKED)) {
                    final float lx = e.getRawX() - mBounds.left;
                    final float ly = e.getRawY() - mBounds.top;
                    final int w = mBounds.width();
                    final int h = mBounds.height();
                    final int reach = cornerReach();
                    final boolean cornerX = lx < reach || lx > w - reach;
                    final boolean cornerY = ly < reach || ly > h - reach;
                    if ((cornerX && cornerY) || !canReshape(s)) {
                        mCorner = (ly > h / 2f ? 2 : 0) | (lx > w / 2f ? 1 : 0);
                        beginGesture(G_SCALE, e);
                    } else {
                        final float left = lx;
                        final float right = w - lx;
                        final float bottom = h - ly;
                        mEdge = bottom < Math.min(left, right) ? EDGE_BOTTOM
                                : left < right ? EDGE_LEFT : EDGE_RIGHT;
                        beginGesture(G_EDGE, e);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (mGesture == G_NONE) {
                    return;
                }
                track(e);
                if (mGesture == G_SHIELD && passedSlop(e)) {
                    mGesture = G_SHIELD_MOVE;
                }
                if (mGesture == G_SHIELD_MOVE) {
                    mVisual.set(mBounds);
                    mVisual.offset(Math.round(e.getRawX() - mDownX),
                            Math.round(e.getRawY() - mDownY));
                    scheduleFrame();
                } else if (mGesture == G_SCALE) {
                    previewScale(e.getRawX() - mDownX, e.getRawY() - mDownY);
                    scheduleFrame();
                } else if (mGesture == G_EDGE) {
                    previewEdge(e.getRawX() - mDownX, e.getRawY() - mDownY);
                    scheduleFrame();
                }
                break;
            case MotionEvent.ACTION_UP:
                track(e);
                if (mGesture == G_SHIELD) {
                    endGesture();
                    mController.setMode(getTaskId(), LayangTaskState.MODE_FLOATING);
                } else if (mGesture == G_SHIELD_MOVE) {
                    finishShieldMove();
                } else if (mGesture == G_SCALE) {
                    finishScale();
                } else if (mGesture == G_EDGE) {
                    finishEdge();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelGesture();
                break;
        }
    }

    private void beginGesture(int gesture, @NonNull MotionEvent e) {
        if (mMenu != null) {
            mMenu.dismiss();
            mMenu = null;
        }
        mGesture = gesture;
        mDetent = -1;
        mDownX = mLastX = e.getRawX();
        mDownY = mLastY = e.getRawY();
        mVisual.set(mBounds);
        if (mVelocity == null) {
            mVelocity = VelocityTracker.obtain();
        }
        mVelocity.clear();
        track(e);
        if (gesture == G_SCALE || gesture == G_EDGE) {
            // Show the handle line at once, before the first move.
            scheduleFrame();
        }
        final LayangTaskState s = mTaskInfo.layangState;
        if (!mTaskInfo.isFocused && s != null && s.isInteractive()
                && (gesture == G_PILL || gesture == G_SCALE || gesture == G_EDGE)) {
            mController.raise(this);
        }
    }

    /** After a raise: the transition may have reset the leash, so draw the gesture again. */
    void redrawAfterRaise() {
        if (mGesture != G_NONE) {
            scheduleFrame();
        }
    }

    /** Zero-allocation velocity tracking in raw display coordinates. */
    private void track(@NonNull MotionEvent e) {
        mLastX = e.getRawX();
        mLastY = e.getRawY();
        if (mVelocity != null) {
            final float ox = mLastX - e.getX();
            final float oy = mLastY - e.getY();
            e.offsetLocation(ox, oy);
            mVelocity.addMovement(e);
            e.offsetLocation(-ox, -oy);
        }
    }

    private boolean passedSlop(@NonNull MotionEvent e) {
        final float dx = e.getRawX() - mDownX;
        final float dy = e.getRawY() - mDownY;
        return dx * dx + dy * dy > (float) mTouchSlop * mTouchSlop;
    }

    /**
     * Uniform scale around the corner opposite to the grabbed one (aspect kept, §4.1), pulled
     * onto 50, 75 and 100 % of the largest size that fits. Zero-alloc.
     */
    private void previewScale(float dx, float dy) {
        final DisplayLayout dl = mController.displayLayout(mTaskInfo.displayId);
        final int w0 = mBounds.width();
        final int h0 = mBounds.height();
        final float aspect = (float) h0 / Math.max(1, w0);
        final int sx = (mCorner & 1) != 0 ? 1 : -1;
        final int sy = (mCorner & 2) != 0 ? 1 : -1;
        // Follow whichever axis the finger moved more, in width units.
        final float byX = w0 + sx * dx;
        final float byY = (h0 + sy * dy) / aspect;
        float w = Math.abs(sx * dx) >= Math.abs(sy * dy / aspect) ? byX : byY;
        computeWidthLimits(dl, mWidthLimits);
        w = Math.max(mWidthLimits[0], Math.min(mWidthLimits[1], w));
        for (int i = 0; i < SCALE_DETENTS.length; i++) {
            mScaleDetents[i] = SCALE_DETENTS[i] * mWidthLimits[1];
        }
        w = pullToDetent(w, mScaleDetents, DETENT_PULL * mWidthLimits[1]);
        final int nw = Math.round(w);
        final int nh = Math.round(w * aspect);
        final int ax = sx > 0 ? mBounds.left : mBounds.right;
        final int ay = sy > 0 ? mBounds.top : mBounds.bottom;
        final int left = sx > 0 ? ax : ax - nw;
        final int top = sy > 0 ? ay : ay - nh;
        mVisual.set(left, top, left + nw, top + nh);
    }

    /**
     * Moves one edge. The content keeps its scale, so the app's virtual screen grows or
     * shrinks with the window; it never gets narrower than a phone layout (320 dp). Pulled
     * onto 9:20, 3:4, 1:1 and 16:9. An app with a fixed orientation keeps it. Zero-alloc.
     */
    private void previewEdge(float dx, float dy) {
        final DisplayLayout dl = mController.displayLayout(mTaskInfo.displayId);
        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi());
        final int top = mController.topInset(dl);
        final int min = minReshapeSide(dl);
        final Rect v = mVisual;
        v.set(mBounds);
        switch (mEdge) {
            case EDGE_LEFT:
                v.left = clamp(Math.round(v.left + dx), margin, v.right - min);
                break;
            case EDGE_RIGHT:
                v.right = clamp(Math.round(v.right + dx), v.left + min, dl.width() - margin);
                break;
            default:
                v.bottom = clamp(Math.round(v.bottom + dy), v.top + min,
                        dl.height() - margin);
                break;
        }
        final int fixed = fixedOrientation();
        float ratio = (float) v.width() / Math.max(1, v.height());
        if (fixed == Configuration.ORIENTATION_PORTRAIT && ratio > 1f) {
            ratio = 1f;
        } else if (fixed == Configuration.ORIENTATION_LANDSCAPE && ratio < 1f) {
            ratio = 1f;
        }
        mRatioDetents[0] = (float) Math.min(dl.width(), dl.height())
                / Math.max(1, Math.max(dl.width(), dl.height()));
        mRatioDetents[1] = 0.75f;
        mRatioDetents[2] = 1f;
        mRatioDetents[3] = 16f / 9f;
        ratio = pullToDetent(ratio, mRatioDetents, DETENT_PULL * ratio);
        if (Math.abs(ratio - (float) v.width() / Math.max(1, v.height())) > 0.001f) {
            switch (mEdge) {
                case EDGE_LEFT:
                    v.left = Math.max(margin, v.right - Math.round(v.height() * ratio));
                    break;
                case EDGE_RIGHT:
                    v.right = Math.min(dl.width() - margin,
                            v.left + Math.round(v.height() * ratio));
                    break;
                default:
                    v.bottom = Math.min(dl.height() - margin,
                            v.top + Math.round(v.width() / ratio));
                    break;
            }
        }
        if (v.top < top) {
            v.offset(0, top - v.top);
        }
    }

    /** Smallest side while reshaping: 320 dp of the app's layout, at the window's scale. */
    private int minReshapeSide(@NonNull DisplayLayout dl) {
        final LayangTaskState s = mTaskInfo.layangState;
        final int floor = LayangGeometry.dpToPx(MIN_WIDTH_DP, dl.densityDpi());
        if (s == null || s.hasFlag(LayangTaskState.FLAG_REFLOW)) {
            return Math.max(floor, LayangGeometry.dpToPx(MIN_REFLOW_DP, dl.densityDpi()));
        }
        final int phone = LayangGeometry.dpToPx(Layang.READABLE_MIN_SW_DP, dl.densityDpi());
        return Math.max(floor, Math.round(phone * mController.scaleOf(this)));
    }

    /** The manifest orientation of the top activity, or ORIENTATION_UNDEFINED when free. */
    int fixedOrientation() {
        final ActivityInfo ai = mTaskInfo.topActivityInfo;
        if (ai == null) {
            return Configuration.ORIENTATION_UNDEFINED;
        }
        if (ActivityInfo.isFixedOrientationLandscape(ai.screenOrientation)) {
            return Configuration.ORIENTATION_LANDSCAPE;
        }
        if (ActivityInfo.isFixedOrientationPortrait(ai.screenOrientation)) {
            return Configuration.ORIENTATION_PORTRAIT;
        }
        return Configuration.ORIENTATION_UNDEFINED;
    }

    /** Snaps value onto the first target within pull of it; ticks once per landing. */
    private float pullToDetent(float value, @NonNull float[] targets, float pull) {
        int hit = -1;
        for (int i = 0; i < targets.length; i++) {
            if (Math.abs(value - targets[i]) <= pull) {
                hit = i;
                break;
            }
        }
        if (hit != mDetent) {
            mDetent = hit;
            if (hit >= 0) {
                tick();
            }
        }
        return hit >= 0 ? targets[hit] : value;
    }

    void tick() {
        if (mVibrator != null) {
            mVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(Math.max(lo, hi), v));
    }

    /** Writes {min, max} width of the window while scaling into out[0..1]. */
    private void computeWidthLimits(@NonNull DisplayLayout dl, @NonNull int[] out) {
        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi());
        final int min = LayangGeometry.dpToPx(MIN_WIDTH_DP, dl.densityDpi());
        final float aspect = (float) mBounds.height() / Math.max(1, mBounds.width());
        final int maxByW = dl.width() - 2 * margin;
        final int maxByH = Math.round((dl.height() - margin - mController.topInset(dl))
                / aspect);
        out[0] = min;
        out[1] = Math.max(min, Math.min(maxByW, maxByH));
    }

    private void finishMove(@NonNull MotionEvent e) {
        final DisplayLayout dl = mController.displayLayout(mTaskInfo.displayId);
        mVelocity.computeCurrentVelocity(1000);
        final float vy = mVelocity.getYVelocity();
        final float dy = mLastY - mDownY;
        endGesture();
        // A move only moves: no edge or flick turns a window fullscreen (OEM-like, §6.1).
        final int flickTravel = LayangGeometry.dpToPx(FLICK_TRAVEL_DP, dl.densityDpi());
        if (vy > mFlingVelocity && dy > flickTravel) {
            mController.setMode(getTaskId(), LayangTaskState.MODE_MINI);
            return;
        }
        commitVisual(dl);
    }

    /**
     * A mini window sticks to the nearer side, or to the side it is flung towards, at the
     * height it was let go; settles with a subtle HyperOS 4 bounce spring.
     */
    private void finishShieldMove() {
        final DisplayLayout dl = mController.displayLayout(mTaskInfo.displayId);
        mVelocity.computeCurrentVelocity(1000);
        final float vx = mVelocity.getXVelocity();
        final float vy = mVelocity.getYVelocity();
        endGesture();
        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi());
        final boolean right = Math.abs(vx) > mFlingVelocity / 2f ? vx > 0
                : mVisual.centerX() > dl.width() / 2;
        final Rect target = new Rect(mVisual);
        target.offsetTo(right ? dl.width() - margin - target.width() : margin,
                Math.round(target.top + vy * MINI_THROW_S));
        LayangGeometry.clamp(target, dl.width(), dl.height(), margin,
                mController.topInset(dl));
        mController.commitBounds(this, target, LayangTransitionHandler.KIND_SNAP);
    }

    private void finishScale() {
        final DisplayLayout dl = mController.displayLayout(mTaskInfo.displayId);
        endGesture();
        commitVisual(dl);
    }

    /** The preview stays as left until the app has drawn its new shape. */
    private void finishEdge() {
        endGesture();
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        layoutHandles(t, mVisual);
        t.apply();
        mController.getTransactionPool().release(t);
        if (mVisual.equals(mBounds)) {
            restoreShape();
            return;
        }
        mController.commitShape(this, new Rect(mVisual), mController.scaleOf(this),
                false /* animate */);
    }

    /**
     * Clamps and snaps the preview and commits it as the new R. A snap that moved it settles
     * with the spring; otherwise the commit is a jump cut.
     */
    private void commitVisual(@NonNull DisplayLayout dl) {
        final Rect target = new Rect(mVisual);
        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi());
        LayangGeometry.clamp(target, dl.width(), dl.height(), margin,
                mController.topInset(dl));
        snap(target, dl);
        if (target.equals(mBounds)) {
            restoreVisual();
            return;
        }
        final boolean settle = !target.equals(mVisual);
        if (!settle) {
            applyFrame();
        }
        mController.commitBounds(this, target,
                settle ? LayangTransitionHandler.KIND_SNAP : LayangTransitionHandler.KIND_COMMIT);
    }

    /** Double-tap on the pill: as large as it fits, and back to the size before. */
    private void toggleSize() {
        final DisplayLayout dl = mController.displayLayout(mTaskInfo.displayId);
        computeWidthLimits(dl, mWidthLimits);
        final int max = mWidthLimits[1];
        final boolean atMax = mBounds.width() >= max - mTouchSlop;
        final Rect target;
        if (atMax && mPreToggleBounds != null) {
            target = new Rect(mPreToggleBounds);
            mPreToggleBounds = null;
        } else {
            if (!atMax) {
                mPreToggleBounds = new Rect(mBounds);
            }
            final int w = Math.round(atMax ? max * SCALE_DETENTS[0] : max);
            final int h = Math.round(w * (float) mBounds.height() / Math.max(1, mBounds.width()));
            target = new Rect(mBounds.centerX() - w / 2, mBounds.centerY() - h / 2,
                    mBounds.centerX() - w / 2 + w, mBounds.centerY() - h / 2 + h);
        }
        LayangGeometry.clamp(target, dl.width(), dl.height(),
                LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi()),
                mController.topInset(dl));
        mController.commitBounds(this, target, true /* animate */);
    }

    /** Turns the window a quarter around its centre; the app keeps its virtual size. */
    void rotate() {
        final DisplayLayout dl = mController.displayLayout(mTaskInfo.displayId);
        final int w = mBounds.height();
        final int h = mBounds.width();
        final Rect target = new Rect(mBounds.centerX() - w / 2, mBounds.centerY() - h / 2,
                mBounds.centerX() - w / 2 + w, mBounds.centerY() - h / 2 + h);
        LayangGeometry.fit(target, dl.width(), dl.height(),
                LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi()),
                mController.topInset(dl));
        mController.commitBounds(this, target, true /* animate */);
    }

    /** Ratio preset (width over height): same area and centre, content at the same scale. */
    void reshape(float ratio) {
        final DisplayLayout dl = mController.displayLayout(mTaskInfo.displayId);
        final float area = (float) mBounds.width() * mBounds.height();
        final int w = Math.round((float) Math.sqrt(area * ratio));
        final int h = Math.round(w / ratio);
        final Rect target = new Rect(mBounds.centerX() - w / 2, mBounds.centerY() - h / 2,
                mBounds.centerX() - w / 2 + w, mBounds.centerY() - h / 2 + h);
        final float scale = mController.scaleOf(this);
        LayangGeometry.fit(target, dl.width(), dl.height(),
                LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi()),
                mController.topInset(dl));
        // Never lay the app out narrower than a phone: zoom out instead.
        final int phone = LayangGeometry.dpToPx(Layang.READABLE_MIN_SW_DP, dl.densityDpi());
        final float s = Math.min(scale,
                (float) Math.min(target.width(), target.height()) / phone);
        mController.commitShape(this, target, s, true /* animate */);
    }

    /**
     * Snap (§6.6): 8 dp to the screen margin, 12 dp magnetic edges between windows, and the
     * display centre lines. Moves only, never resizes.
     */
    private void snap(@NonNull Rect r, @NonNull DisplayLayout dl) {
        final int screen = LayangGeometry.dpToPx(8, dl.densityDpi());
        final int magnet = LayangGeometry.dpToPx(12, dl.densityDpi());
        final int margin = LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi());
        int dx = 0;
        int dy = 0;
        if (Math.abs(r.left - margin) <= screen) dx = margin - r.left;
        else if (Math.abs(dl.width() - margin - r.right) <= screen) dx = dl.width() - margin - r.right;
        else if (Math.abs(r.centerX() - dl.width() / 2) <= magnet) dx = dl.width() / 2 - r.centerX();
        if (Math.abs(dl.height() - margin - r.bottom) <= screen) {
            dy = dl.height() - margin - r.bottom;
        } else if (Math.abs(r.centerY() - dl.height() / 2) <= magnet) {
            dy = dl.height() / 2 - r.centerY();
        }
        for (Rect o : mController.otherWindowBounds(getTaskId())) {
            if (dx == 0) {
                if (Math.abs(r.left - o.right - margin) <= magnet) dx = o.right + margin - r.left;
                else if (Math.abs(o.left - margin - r.right) <= magnet) {
                    dx = o.left - margin - r.right;
                }
            }
            if (dy == 0) {
                if (Math.abs(r.top - o.bottom - margin) <= magnet) dy = o.bottom + margin - r.top;
                else if (Math.abs(o.top - margin - r.bottom) <= magnet) {
                    dy = o.top - margin - r.bottom;
                }
            }
        }
        r.offset(dx, dy);
        LayangGeometry.clamp(r, dl.width(), dl.height(), margin, mController.topInset(dl));
    }

    private void cancelGesture() {
        final boolean edge = mGesture == G_EDGE;
        endGesture();
        if (edge) {
            restoreShape();
        } else {
            restoreVisual();
        }
    }

    private void endGesture() {
        mGesture = G_NONE;
        mDetent = -1;
        if (mVelocity != null) {
            mVelocity.recycle();
            mVelocity = null;
        }
    }

    private void restoreVisual() {
        mVisual.set(mBounds);
        applyFrame();
    }

    /** Undoes an edge preview: the crop, the veil and the position. */
    private void restoreShape() {
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        applyLeashShape(t, mBounds);
        if (mVeil != null && mVeil.isValid()) {
            t.hide(mVeil);
        }
        t.apply();
        mController.getTransactionPool().release(t);
        restoreVisual();
    }

    private void scheduleFrame() {
        if (!mFramePending && !mDestroyed) {
            mFramePending = true;
            mController.getChoreographer().postFrameCallback(mFrameCallback);
        }
    }

    /** One transaction per vsync: leash position and scale, chrome position (§4.4). */
    private void applyFrame() {
        mFramePending = false;
        if (mDestroyed || !mLeash.isValid()) {
            return;
        }
        final SurfaceControl.Transaction t = mController.getTransactionPool().acquire();
        applyVisual(t, mVisual);
        t.setFrameTimelineVsync(mController.getChoreographer().getVsyncId());
        t.apply();
        mController.getTransactionPool().release(t);
    }

    /**
     * Draws the window at v relative to its committed bounds: scaled with inverse-scaled
     * corners while moving or scaling; while reshaping, unscaled and cropped to v, with the
     * veil filling what is not drawn yet.
     */
    void applyVisual(@NonNull SurfaceControl.Transaction t, @NonNull Rect v) {
        if (mDestroyed || !mLeash.isValid()) {
            return;
        }
        if (mGesture == G_EDGE) {
            t.setPosition(mLeash, mBounds.left, mBounds.top);
            t.setScale(mLeash, 1f, 1f);
            mTmp.set(v);
            mTmp.offset(-mBounds.left, -mBounds.top);
            t.setCrop(mLeash, mTmp);
            t.setCornerRadius(mLeash, mCornerRadius);
            if (mVeil != null && mVeil.isValid()) {
                t.setPosition(mVeil, v.left, v.top).setWindowCrop(mVeil, v.width(), v.height())
                        .show(mVeil);
            }
        } else {
            final float scale = (float) v.width() / Math.max(1, mBounds.width());
            t.setPosition(mLeash, v.left, v.top);
            t.setScale(mLeash, scale, scale);
            t.setCornerRadius(mLeash, mCornerRadius / Math.max(0.15f, scale));
        }
        layoutDecor(t, v);
    }

    // ---------------------------------------------------------------------------------------
    // Menu and accessibility
    // ---------------------------------------------------------------------------------------

    void showMenu() {
        cancelPendingMenu();
        if (mMenu != null) {
            mMenu.dismiss();
            mMenu = null;
            return;
        }
        final Display display = mController.getDisplayController()
                .getDisplay(mTaskInfo.displayId);
        mMenu = new LayangMenu(mController, this, display != null ? display
                : mContext.getDisplay(), () -> mMenu = null);
        mMenu.show(mBounds);
    }

    private void onAccessibilityAction(int action) {
        switch (action) {
            case LayangPillView.A11Y_FULLSCREEN:
                mController.toFullscreen(getTaskId());
                break;
            case LayangPillView.A11Y_MINI:
                mController.setMode(getTaskId(), LayangTaskState.MODE_MINI);
                break;
            case LayangPillView.A11Y_CLOSE:
                mController.close(getTaskId());
                break;
            case LayangPillView.A11Y_BIGGER:
            case LayangPillView.A11Y_SMALLER: {
                final DisplayLayout dl = mController.displayLayout(mTaskInfo.displayId);
                final float f = action == LayangPillView.A11Y_BIGGER ? 1.15f : 1 / 1.15f;
                computeWidthLimits(dl, mWidthLimits);
                final int w = Math.max(mWidthLimits[0], Math.min(mWidthLimits[1],
                        Math.round(mBounds.width() * f)));
                final int h = Math.round(w * (float) mBounds.height() / Math.max(1, mBounds.width()));
                mVisual.set(mBounds.centerX() - w / 2, mBounds.centerY() - h / 2,
                        mBounds.centerX() - w / 2 + w, mBounds.centerY() - h / 2 + h);
                commitVisual(dl);
                break;
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------------------------

    int getTaskId() {
        return mTaskInfo.taskId;
    }

    @NonNull
    RunningTaskInfo getTaskInfo() {
        return mTaskInfo;
    }

    @Nullable
    LayangTaskState getState() {
        return mTaskInfo.layangState;
    }

    @NonNull
    SurfaceControl getLeash() {
        return mLeash;
    }

    @NonNull
    Rect getBounds() {
        return mBounds;
    }

    /** Where the window is drawn now; equals the bounds unless a gesture or animation runs. */
    @NonNull
    Rect getVisual() {
        return mVisual;
    }

    void setVisual(@NonNull Rect v) {
        mVisual.set(v);
    }

    /** Keeps the window's place in the display orientation it is leaving. */
    void rememberPlace(boolean landscape, @NonNull Rect bounds) {
        mPlaces[landscape ? 1 : 0] = new Rect(bounds);
    }

    /** The window's place when the display last had this orientation, or null. */
    @Nullable
    Rect getPlace(boolean landscape) {
        return mPlaces[landscape ? 1 : 0];
    }

    void rememberFloatingBounds() {
        mRestoreBounds = new Rect(mBounds);
    }

    /** Floating bounds to restore from mini or parked, kept inside the display. */
    @NonNull
    Rect getRestoreBounds(@NonNull DisplayLayout dl, int topInset) {
        final Rect r = mRestoreBounds != null ? new Rect(mRestoreBounds) : new Rect(mBounds);
        LayangGeometry.clamp(r, dl.width(), dl.height(),
                LayangGeometry.dpToPx(Layang.MARGIN_DP, dl.densityDpi()), topInset);
        return r;
    }

    int getCornerRadius() {
        return mCornerRadius;
    }

    @Override
    public String toString() {
        return "LayangWindow{#" + mTaskInfo.taskId + " " + mTaskInfo.layangState + " "
                + mBounds.toShortString() + (mTaskInfo.isFocused ? " focused" : "")
                + (mGesture != G_NONE ? " gesture=" + mGesture : "") + "}";
    }
}
