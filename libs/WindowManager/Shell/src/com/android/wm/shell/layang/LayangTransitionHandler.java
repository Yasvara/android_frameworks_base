/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wm.shell.layang;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.yasvara.LayangGeometry;
import android.app.yasvara.LayangTaskState;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.SparseIntArray;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.TransactionPool;
import com.android.wm.shell.shared.TransitionUtil;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;

/**
 * Plays every transition that only touches Layang windows.
 *
 * <ul>
 * <li>A gesture commit is a jump cut: the start transaction carries WM's new scale and this
 * handler resets the in-flight leash transform in the same apply, so nothing jumps (S7).
 * <li>Mode changes, entering and leaving morph the window rect with a low-bounce spring.
 * <li>Opening grows the window in place, closing shrinks it away, parking shrinks it into
 * the status-bar chip.
 * </ul>
 */
final class LayangTransitionHandler implements Transitions.TransitionHandler {
    static final int KIND_COMMIT = 1;
    static final int KIND_ENTER = 2;
    static final int KIND_MODE = 3;
    static final int KIND_PARK = 4;
    static final int KIND_EXIT = 5;
    static final int KIND_CLOSE = 6;
    static final int KIND_OPEN = 7;
    static final int KIND_MINIMIZE = 9;
    /** Touching the chrome raised the window; a gesture may be drawing it, so hands off. */
    static final int KIND_RAISE = 10;
    /** Requested by WM core (service fallback, launches, back, reorders): inferred. */
    static final int KIND_SERVER = 8;

    private final LayangController mController;
    private final TransactionPool mPool;
    private final ShellExecutor mMainExecutor;
    private final Choreographer mChoreographer;
    /** transition → {taskId, kind} for transitions this handler started or claimed. */
    private final ArrayMap<IBinder, int[]> mExpected = new ArrayMap<>();
    /** taskId → number of running animations. */
    private final SparseIntArray mAnimating = new SparseIntArray();
    private float mAnimScale = 1f;

    LayangTransitionHandler(@NonNull LayangController controller, @NonNull TransactionPool pool,
            @NonNull ShellExecutor mainExecutor, @NonNull Choreographer choreographer) {
        mController = controller;
        mPool = pool;
        mMainExecutor = mainExecutor;
        mChoreographer = choreographer;
    }

    void expect(@NonNull IBinder transition, int taskId, int kind) {
        mExpected.put(transition, new int[] {taskId, kind});
    }

    boolean isAnimating(int taskId) {
        return mAnimating.get(taskId) > 0;
    }

    /** Whether a transition started for taskId has not reached this handler yet. */
    boolean isPending(int taskId) {
        for (int i = mExpected.size() - 1; i >= 0; i--) {
            final int id = mExpected.valueAt(i)[0];
            if (id == taskId || id == -1) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
            @Nullable SurfaceControl.Transaction finishTransaction) {
        mExpected.remove(transition);
    }

    @Override
    public void setAnimScaleSetting(float scale) {
        mAnimScale = scale;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        final RunningTaskInfo trigger = request.getTriggerTask();
        if (trigger == null || trigger.layangState == null) {
            return null;
        }
        final int type = request.getType();
        if (type == TRANSIT_OPEN || type == TRANSIT_CLOSE || type == TRANSIT_TO_FRONT
                || type == TRANSIT_TO_BACK || type == TRANSIT_CHANGE) {
            mExpected.put(transition, new int[] {trigger.taskId,
                    type == TRANSIT_OPEN ? KIND_OPEN : KIND_SERVER});
            return new WindowContainerTransaction();
        }
        return null;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        final int[] expected = mExpected.remove(transition);
        final ArrayList<TransitionInfo.Change> mine = new ArrayList<>();
        boolean others = false;
        for (TransitionInfo.Change c : info.getChanges()) {
            final RunningTaskInfo ti = c.getTaskInfo();
            if (ti == null) {
                continue;
            }
            if (ti.layangState != null || mController.getWindow(ti.taskId) != null) {
                mine.add(c);
            } else {
                others = true;
            }
        }
        if (expected == null && (mine.isEmpty() || others)) {
            return false;
        }
        if (mine.isEmpty()) {
            startT.apply();
            finishCallback.onTransitionFinished(null);
            return true;
        }

        final ArrayList<Anim> anims = new ArrayList<>();
        for (TransitionInfo.Change c : mine) {
            final int kind = expected != null && expected[1] == KIND_RAISE ? KIND_RAISE
                    : expected != null && expected[1] != KIND_SERVER
                    && (expected[0] == -1 || expected[0] == c.getTaskInfo().taskId)
                    ? expected[1] : infer(c);
            final Anim a = prepare(c, kind, info, startT, finishT);
            if (a != null) {
                anims.add(a);
            }
        }
        startT.apply();
        if (anims.isEmpty() || mAnimScale == 0f) {
            for (Anim a : anims) {
                a.finish(finishT);
            }
            finishCallback.onTransitionFinished(null);
            return true;
        }
        run(anims, finishT, finishCallback);
        return true;
    }

    /** Kind of a change nobody announced. */
    private int infer(@NonNull TransitionInfo.Change c) {
        final RunningTaskInfo ti = c.getTaskInfo();
        final LayangTaskState s = ti.layangState;
        switch (c.getMode()) {
            case TRANSIT_OPEN:
                return KIND_OPEN;
            case TRANSIT_CLOSE:
                return KIND_CLOSE;
            case TRANSIT_TO_BACK:
                return s != null && s.getMode() == LayangTaskState.MODE_PARKED
                        ? KIND_PARK : KIND_CLOSE;
            case TRANSIT_TO_FRONT:
                return c.getStartAbsBounds().isEmpty() ? KIND_OPEN : KIND_MODE;
            default:
                if (s == null) {
                    return KIND_EXIT;
                }
                return c.getStartAbsBounds().equals(c.getEndAbsBounds())
                        ? KIND_COMMIT : KIND_MODE;
        }
    }

    /** Sets the first frame in startT and returns the animation, or null for a jump cut. */
    @Nullable
    private Anim prepare(@NonNull TransitionInfo.Change c, int kind, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT) {
        final RunningTaskInfo ti = c.getTaskInfo();
        final SurfaceControl leash = c.getLeash();
        final Point off = info.getRoot(TransitionUtil.rootIndexFor(c, info)).getOffset();
        final LayangWindow w = mController.getWindow(ti.taskId);
        final Rect end = new Rect(c.getEndAbsBounds());
        final Rect start = new Rect(c.getStartAbsBounds());
        // A gesture may have left the window drawn somewhere else than its old bounds.
        if (w != null && !w.getVisual().equals(w.getBounds()) && !w.getVisual().isEmpty()) {
            start.set(w.getVisual());
        }
        final boolean layangAtEnd = ti.layangState != null;
        // On open the window may not exist yet; the radius must not fall back to square.
        final int radius = LayangGeometry.dpToPx(LayangWindow.CORNER_RADIUS_DP,
                mController.displayLayout(ti.displayId).densityDpi());

        if (kind == KIND_RAISE) {
            // Only the z-order changed; the window keeps whatever its gesture is drawing.
            startT.show(leash);
            if (w != null) {
                mMainExecutor.execute(w::redrawAfterRaise);
            }
            return null;
        }
        final Anim a = new Anim(ti.taskId, leash, off, end, w, layangAtEnd, radius);
        switch (kind) {
            case KIND_COMMIT:
                a.setFrame(startT, end, 1f);
                if (w != null && layangAtEnd) {
                    w.onBoundsCommitted(end, startT);
                }
                a.finish(finishT);
                return null;
            case KIND_OPEN:
                a.from.set(scaled(end, 0.86f));
                a.to.set(end);
                a.fromAlpha = 0f;
                break;
            case KIND_CLOSE:
                a.base.set(start.isEmpty() ? end : start);
                a.from.set(a.base);
                a.to.set(scaled(a.base, 0.86f));
                a.toAlpha = 0f;
                a.closing = true;
                break;
            case KIND_PARK: {
                // Shrink into the status-bar chip at the top centre (§6.5).
                final DisplayLayout dl = mController.displayLayout(ti.displayId);
                a.base.set(start.isEmpty() ? end : start);
                a.from.set(a.base);
                final int cx = dl.width() / 2;
                final int cy = dl.stableInsets().top / 2;
                a.to.set(cx - 1, cy - 1, cx + 1, cy + 1);
                a.toAlpha = 0f;
                a.closing = true;
                break;
            }
            case KIND_MINIMIZE: {
                // Content is fullscreen-sized at the end: shrink it from the window's place
                // towards the navigation bar, where Recents opens.
                final DisplayLayout dl = mController.displayLayout(ti.displayId);
                a.base.set(end);
                final Rect from = start.isEmpty() ? end : start;
                final float s = (float) from.width() / Math.max(1, end.width());
                final int h = Math.round(end.height() * s);
                a.from.set(from.left, from.centerY() - h / 2, from.right,
                        from.centerY() - h / 2 + h);
                final int cx = dl.width() / 2;
                final int cy = dl.height();
                a.to.set(cx - 1, cy - 1, cx + 1, cy + 1);
                a.toAlpha = 0f;
                a.closing = true;
                a.minimizing = true;
                break;
            }
            default:
                // ENTER, EXIT, MODE: morph the rect; the content already has its end size.
                a.from.set(start.isEmpty() ? end : start);
                a.to.set(end);
                break;
        }
        a.setFrame(startT, a.from, a.fromAlpha);
        startT.show(leash);
        return a;
    }

    private void run(@NonNull ArrayList<Anim> anims, @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        for (Anim a : anims) {
            mAnimating.put(a.taskId, mAnimating.get(a.taskId) + 1);
        }
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(LayangSpring.DURATION_MS);
        va.setInterpolator(LayangSpring.INTERPOLATOR);
        final Rect tmp = new Rect();
        va.addUpdateListener(animation -> {
            final float f = (float) animation.getAnimatedValue();
            final SurfaceControl.Transaction t = mPool.acquire();
            for (Anim a : anims) {
                lerp(a.from, a.to, f, tmp);
                final float alpha = a.fromAlpha + (a.toAlpha - a.fromAlpha)
                        * Math.max(0f, Math.min(1f, animation.getAnimatedFraction()));
                a.setFrame(t, tmp, alpha);
            }
            t.setFrameTimelineVsync(mChoreographer.getVsyncId());
            t.apply();
            mPool.release(t);
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                for (Anim a : anims) {
                    a.finish(finishT);
                    final int n = mAnimating.get(a.taskId) - 1;
                    if (n <= 0) {
                        mAnimating.delete(a.taskId);
                    } else {
                        mAnimating.put(a.taskId, n);
                    }
                }
                finishCallback.onTransitionFinished(null);
            }
        });
        va.start();
    }

    private static Rect scaled(@NonNull Rect r, float s) {
        final int w = Math.round(r.width() * s);
        final int h = Math.round(r.height() * s);
        return new Rect(r.centerX() - w / 2, r.centerY() - h / 2,
                r.centerX() - w / 2 + w, r.centerY() - h / 2 + h);
    }

    /**
     * The spring overshoots past 1, so a frame shrinking to a small rect can invert; keep it at
     * least 1 px wide and tall (a negative scale throws in SurfaceControl).
     */
    private static void lerp(@NonNull Rect a, @NonNull Rect b, float f, @NonNull Rect out) {
        final int left = Math.round(a.left + (b.left - a.left) * f);
        final int top = Math.round(a.top + (b.top - a.top) * f);
        out.set(left, top, Math.max(left + 1, Math.round(a.right + (b.right - a.right) * f)),
                Math.max(top + 1, Math.round(a.bottom + (b.bottom - a.bottom) * f)));
    }

    /** One window being animated. Rects are in display coordinates. */
    private static final class Anim {
        final int taskId;
        final SurfaceControl leash;
        final Point offset;
        /** Size the content has during the animation (its end size, or start when closing). */
        final Rect base = new Rect();
        final Rect from = new Rect();
        final Rect to = new Rect();
        final Rect end;
        @Nullable final LayangWindow window;
        final boolean layangAtEnd;
        final int radius;
        float fromAlpha = 1f;
        float toAlpha = 1f;
        boolean closing;
        /** Going to Recents: the task lives on, so its crop and corners are reset at the end. */
        boolean minimizing;

        Anim(int taskId, SurfaceControl leash, Point offset, Rect end,
                @Nullable LayangWindow window, boolean layangAtEnd, int radius) {
            this.taskId = taskId;
            this.leash = leash;
            this.offset = offset;
            this.end = end;
            this.window = window;
            this.layangAtEnd = layangAtEnd;
            this.radius = radius;
            base.set(end);
        }

        void setFrame(@NonNull SurfaceControl.Transaction t, @NonNull Rect r, float alpha) {
            final float scale = (float) r.width() / Math.max(1, base.width());
            t.setPosition(leash, r.left - offset.x, r.top - offset.y);
            t.setScale(leash, scale, scale);
            t.setAlpha(leash, alpha);
            if (layangAtEnd || closing) {
                t.setWindowCrop(leash, base.width(), base.height());
                t.setCornerRadius(leash, radius);
            }
            if (window != null && layangAtEnd && !closing) {
                window.setVisual(r);
                window.layoutDecor(t, r);
            }
        }

        void finish(@NonNull SurfaceControl.Transaction finishT) {
            finishT.setPosition(leash, end.left - offset.x, end.top - offset.y);
            finishT.setScale(leash, 1f, 1f);
            finishT.setAlpha(leash, layangAtEnd && window != null ? window.restAlpha() : 1f);
            if (layangAtEnd) {
                finishT.setWindowCrop(leash, end.width(), end.height());
                finishT.setCornerRadius(leash, radius);
                if (window != null) {
                    window.onBoundsCommitted(end, finishT);
                }
            } else {
                finishT.setCornerRadius(leash, 0);
                if (minimizing) {
                    finishT.setCrop(leash, null);
                    finishT.hide(leash);
                }
            }
        }
    }

    /**
     * M3 Expressive low-bounce spring (D17) as an interpolator: damping ratio 0.85,
     * stiffness 600, sampled over its settle time. ValueAnimator applies the system animator
     * scale, so "Remove animations" ends it at once.
     */
    static final class LayangSpring {
        private static final double DAMPING = 0.85;
        private static final double OMEGA = Math.sqrt(600.0);
        private static final double SETTLE_S = Math.log(500) / (DAMPING * OMEGA);
        static final long DURATION_MS = Math.round(SETTLE_S * 1000);
        static final TimeInterpolator INTERPOLATOR = fraction -> {
            final double t = fraction * SETTLE_S;
            final double wd = OMEGA * Math.sqrt(1 - DAMPING * DAMPING);
            final double env = Math.exp(-DAMPING * OMEGA * t);
            return (float) (1 - env * (Math.cos(wd * t)
                    + (DAMPING * OMEGA / wd) * Math.sin(wd * t)));
        };

        private LayangSpring() {}
    }
}
