/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wm.shell.layang;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_SLEEP;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.yasvara.LayangGeometry;
import android.app.yasvara.LayangTaskState;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.SparseArray;
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
 * Plays every transition that only touches Layang windows using a zero-allocation,
 * VSYNC-locked, interruptible closed-form physics spring engine (modeled on HyperOS 4
 * multitasking springs).
 *
 * <ul>
 * <li>A gesture commit is a jump cut: the start transaction carries WM's new scale and this
 * handler resets the in-flight leash transform in the same apply, so nothing jumps (S7).
 * <li>Opening from a sidebar/launcher icon morphs smoothly from {@code OPT_SOURCE_BOUNDS}
 * using aspect-safe cover crop, inverse-scaled squircle-to-window corners, fast sine-out alpha,
 * and late pill fade-in.
 * <li>Mode changes, mini snaps, entering and leaving morph with velocity-preserving springs so
 * rapid interruptions never stutter or flash.
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
    /** Requested by WM core (service fallback, launches, back, reorders): inferred. */
    static final int KIND_SERVER = 8;
    static final int KIND_MINIMIZE = 9;
    /** Touching the chrome raised the window; a gesture may be drawing it, so hands off. */
    static final int KIND_RAISE = 10;
    /** Mini corner fling or magnetic snap settle with subtle organic bounce. */
    static final int KIND_SNAP = 11;

    // HyperOS 4 spring tuning (damping ratio zeta, response period T in seconds).
    private static final float OPEN_ZETA = 0.94f;
    private static final float OPEN_RESPONSE_S = 0.36f;
    private static final float MODE_ZETA = 0.92f;
    private static final float MODE_RESPONSE_S = 0.32f;
    private static final float SNAP_ZETA = 0.80f;
    private static final float SNAP_RESPONSE_S = 0.42f;
    private static final float CLOSE_ZETA = 0.96f;
    private static final float CLOSE_RESPONSE_S = 0.28f;

    private static final float POS_EPSILON_PX = 0.45f;
    private static final float VEL_EPSILON_PX_S = 8.0f;
    private static final float MAX_DURATION_S = 1.15f;
    private static final long CARRY_TTL_MS = 200L;
    private static final float TWO_PI = (float) (2.0 * Math.PI);

    private final LayangController mController;
    private final TransactionPool mPool;
    private final ShellExecutor mMainExecutor;
    private final Choreographer mChoreographer;
    /** transition → {taskId, kind} for transitions this handler started or claimed. */
    private final ArrayMap<IBinder, int[]> mExpected = new ArrayMap<>();
    /** taskId → number of running animations. */
    private final SparseIntArray mAnimating = new SparseIntArray();
    /** Currently playing transition sessions, stepped by one shared Choreographer loop. */
    private final ArrayList<ActiveSession> mActiveSessions = new ArrayList<>();
    /** Velocity & visual rect carried over across an interrupted transition (taskId → state). */
    private final SparseArray<CarryState> mCarryStates = new SparseArray<>();
    /** Reusable scratch rects for zero-alloc per-VSYNC stepping. */
    private final Rect mTmpRect = new Rect();
    private final Rect mTmpCrop = new Rect();

    private boolean mTickScheduled;
    private long mLastFrameNanos;
    private float mAnimScale = 1f;

    private final Choreographer.FrameCallback mFrameCallback = this::onFrame;

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

        final boolean instant = mAnimScale == 0f;
        final ArrayList<Anim> anims = new ArrayList<>(mine.size());
        for (TransitionInfo.Change c : mine) {
            final int kind = expected != null && expected[1] == KIND_RAISE ? KIND_RAISE
                    : expected != null && expected[1] != KIND_SERVER
                    && (expected[0] == -1 || expected[0] == c.getTaskInfo().taskId)
                    ? expected[1] : infer(c);
            final Anim a = prepare(c, kind, info, startT, finishT, instant);
            if (a != null) {
                anims.add(a);
            }
        }
        startT.apply();
        if (anims.isEmpty() || instant) {
            for (Anim a : anims) {
                a.finish(finishT);
            }
            finishCallback.onTransitionFinished(null);
            return true;
        }
        startSession(transition, anims, finishT, finishCallback);
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        final ActiveSession playing = findSession(mergeTarget);
        if (playing == null) {
            return;
        }
        // Sleep or sync proxy: snap-finish immediately so screen-off never stalls.
        if (info.getType() == TRANSIT_SLEEP) {
            finishSession(playing, false /* preserveVelocity */);
            return;
        }
        final int[] expected = mExpected.get(transition);
        if (expected != null && expected[1] == KIND_RAISE) {
            // Pure z-order raise during animation: apply and consume without disturbing springs.
            mExpected.remove(transition);
            startT.apply();
            finishCallback.onTransitionFinished(null);
            return;
        }
        // Check whether the incoming transition touches any of our animating Layang tasks.
        boolean touchesMine = false;
        for (TransitionInfo.Change c : info.getChanges()) {
            final RunningTaskInfo ti = c.getTaskInfo();
            if (ti == null) continue;
            if (ti.layangState != null || mController.getWindow(ti.taskId) != null) {
                touchesMine = true;
                break;
            }
        }
        if (!touchesMine) {
            return;
        }
        // Interrupt the current session cleanly, capturing live position + velocity so the
        // incoming transition's startAnimation (dispatched synchronously on finish) continues
        // seamlessly from the exact current pixel and velocity with zero visual jump.
        finishSession(playing, true /* preserveVelocity */);
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
                return KIND_OPEN;
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
            @NonNull SurfaceControl.Transaction finishT,
            boolean instant) {
        final RunningTaskInfo ti = c.getTaskInfo();
        final SurfaceControl leash = c.getLeash();
        if (!leash.isValid()) {
            return null;
        }
        final Point off = info.getRoot(TransitionUtil.rootIndexFor(c, info)).getOffset();
        final LayangWindow w = mController.getWindow(ti.taskId);
        final Rect end = new Rect(c.getEndAbsBounds());
        final Rect start = new Rect(c.getStartAbsBounds());
        final CarryState carry = consumeCarry(ti.taskId);

        // If an animation was interrupted or a gesture left the window drawn elsewhere, start
        // from that exact visual rect instead of WM's old committed bounds.
        if (carry != null && !carry.visual.isEmpty()) {
            start.set(carry.visual);
        } else if (w != null && !w.getVisual().equals(w.getBounds()) && !w.getVisual().isEmpty()) {
            start.set(w.getVisual());
        }

        final boolean layangAtEnd = ti.layangState != null;
        final DisplayLayout dl = mController.displayLayout(ti.displayId);
        final int radius = LayangGeometry.dpToPx(LayangWindow.CORNER_RADIUS_DP, dl.densityDpi());

        if (kind == KIND_RAISE) {
            startT.show(leash);
            if (w != null) {
                mMainExecutor.execute(w::redrawAfterRaise);
            }
            return null;
        }

        final Anim a = new Anim(ti.taskId, leash, off, end, w, layangAtEnd, radius,
                mTmpRect, mTmpCrop);
        final String pkg = packageOf(ti);
        final Rect sourceBounds = (kind == KIND_OPEN || kind == KIND_ENTER
                || (kind == KIND_MODE && c.getMode() == TRANSIT_TO_FRONT))
                ? mController.consumeSourceBounds(ti.taskId, pkg) : null;

        switch (kind) {
            case KIND_COMMIT:
                a.finish(startT);
                a.finish(finishT);
                return null;
            case KIND_OPEN:
                setupOpenAnim(a, end, sourceBounds, dl);
                break;
            case KIND_ENTER:
                if (sourceBounds != null && !sourceBounds.isEmpty()) {
                    setupOpenAnim(a, end, sourceBounds, dl);
                } else if (start.isEmpty() || c.getMode() == TRANSIT_OPEN
                        || c.getMode() == TRANSIT_TO_FRONT) {
                    setupOpenAnim(a, end, null, dl);
                } else {
                    // Morphing an already-visible fullscreen app into floating: avoid aspect
                    // explosion (e.g. portrait floating over landscape display) by fitting start.
                    a.from.set(aspectSafeFrom(start, end));
                    a.to.set(end);
                    a.setSpring(MODE_ZETA, MODE_RESPONSE_S);
                    a.toAlpha = layangAtEnd && w != null ? w.restAlpha() : 1f;
                }
                break;
            case KIND_CLOSE:
                a.base.set(start.isEmpty() ? end : start);
                a.from.set(a.base);
                a.to.set(scaled(a.base, 0.84f));
                a.fromAlpha = w != null ? w.restAlpha() : 1f;
                a.toAlpha = 0f;
                a.alphaDurationS = 0.18f;
                a.closing = true;
                a.setSpring(CLOSE_ZETA, CLOSE_RESPONSE_S);
                break;
            case KIND_PARK: {
                // Shrink into the status-bar chip at the top centre (§6.5).
                a.base.set(start.isEmpty() ? end : start);
                a.from.set(a.base);
                final int cx = dl.width() / 2;
                final int cy = Math.max(1, dl.stableInsets().top / 2);
                final int sz = Math.max(8, LayangGeometry.dpToPx(16, dl.densityDpi()));
                a.to.set(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2);
                a.fromAlpha = w != null ? w.restAlpha() : 1f;
                a.toAlpha = 0f;
                a.alphaDurationS = 0.22f;
                a.fromRadius = radius;
                a.toRadius = sz / 2f;
                a.closing = true;
                a.setSpring(CLOSE_ZETA, CLOSE_RESPONSE_S);
                break;
            }
            case KIND_MINIMIZE: {
                // Content is fullscreen-sized at the end: shrink it from the window's place
                // towards the navigation bar, where Recents opens.
                a.base.set(end);
                final Rect from = start.isEmpty() ? end : start;
                a.from.set(from);
                final int cx = dl.width() / 2;
                final int cy = dl.height();
                final int sz = Math.max(8, LayangGeometry.dpToPx(24, dl.densityDpi()));
                a.to.set(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2);
                a.fromAlpha = w != null ? w.restAlpha() : 1f;
                a.toAlpha = 0f;
                a.alphaDurationS = 0.20f;
                a.closing = true;
                a.minimizing = true;
                a.setSpring(CLOSE_ZETA, CLOSE_RESPONSE_S);
                break;
            }
            case KIND_SNAP:
                a.from.set(start.isEmpty() ? end : start);
                a.to.set(end);
                a.fromAlpha = layangAtEnd && w != null ? w.restAlpha() : 1f;
                a.toAlpha = a.fromAlpha;
                a.setSpring(SNAP_ZETA, SNAP_RESPONSE_S);
                break;
            case KIND_EXIT:
                a.from.set(start.isEmpty() ? end : start);
                a.to.set(end);
                a.fromRadius = radius;
                a.toRadius = 0f;
                a.fromAlpha = w != null ? w.restAlpha() : 1f;
                a.toAlpha = 1f;
                a.alphaDurationS = 0.14f;
                a.setSpring(MODE_ZETA, MODE_RESPONSE_S);
                break;
            default:
                // KIND_MODE: morph rect cleanly (mini <-> floating, rotate, reshape, IME lift).
                if (sourceBounds != null && !sourceBounds.isEmpty()
                        && c.getMode() == TRANSIT_TO_FRONT) {
                    setupOpenAnim(a, end, sourceBounds, dl);
                } else {
                    a.from.set(start.isEmpty() ? end : aspectSafeFrom(start, end));
                    a.to.set(end);
                    a.fromAlpha = layangAtEnd && w != null ? w.restAlpha() : 1f;
                    a.toAlpha = a.fromAlpha;
                    a.setSpring(MODE_ZETA, MODE_RESPONSE_S);
                }
                break;
        }

        if (instant) {
            a.finish(startT);
            return a;
        }

        a.initKinematics(carry);
        a.applyCurrentFrame(startT);
        startT.show(leash);
        return a;
    }

    private void setupOpenAnim(@NonNull Anim a, @NonNull Rect end, @Nullable Rect sourceBounds,
            @NonNull DisplayLayout dl) {
        final float targetAlpha = a.layangAtEnd && a.window != null ? a.window.restAlpha() : 1f;
        if (sourceBounds != null && !sourceBounds.isEmpty()) {
            // HyperOS 4-style pop-up directly out of the sidebar/launcher icon!
            a.from.set(sourceBounds);
            a.fromRadius = Math.max(a.radius,
                    Math.min(sourceBounds.width(), sourceBounds.height()) * 0.36f);
            a.toRadius = a.radius;
            a.fromAlpha = 0.08f;
            a.toAlpha = targetAlpha;
            a.alphaDurationS = 0.11f;
            a.fadePillIn = true;
        } else {
            a.from.set(scaled(end, 0.84f));
            a.fromRadius = a.radius * 1.15f;
            a.toRadius = a.radius;
            a.fromAlpha = 0f;
            a.toAlpha = targetAlpha;
            a.alphaDurationS = 0.12f;
            a.fadePillIn = true;
        }
        a.to.set(end);
        a.setSpring(OPEN_ZETA, OPEN_RESPONSE_S);
    }

    /**
     * Prevents aspect/scale explosion (e.g. a portrait 600x1300 floating app morphing from a
     * landscape 2772x1280 fullscreen start rect). When start and end have opposite orientations
     * and start is larger, fits start to end's aspect inside start so scale stays <= ~1.2x.
     */
    @NonNull
    private static Rect aspectSafeFrom(@NonNull Rect start, @NonNull Rect end) {
        if (start.isEmpty() || end.isEmpty()) {
            return new Rect(end);
        }
        final boolean startLand = start.width() > start.height();
        final boolean endLand = end.width() > end.height();
        if (startLand == endLand) {
            return new Rect(start);
        }
        final float sx = (float) start.width() / Math.max(1, end.width());
        final float sy = (float) start.height() / Math.max(1, end.height());
        if (Math.max(sx, sy) <= 1.35f) {
            return new Rect(start);
        }
        final float fit = Math.min(sx, sy);
        final int w = Math.max(1, Math.round(end.width() * fit));
        final int h = Math.max(1, Math.round(end.height() * fit));
        return new Rect(start.centerX() - w / 2, start.centerY() - h / 2,
                start.centerX() - w / 2 + w, start.centerY() - h / 2 + h);
    }

    private void startSession(@NonNull IBinder token, @NonNull ArrayList<Anim> anims,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        for (int i = 0; i < anims.size(); i++) {
            final int id = anims.get(i).taskId;
            mAnimating.put(id, mAnimating.get(id) + 1);
        }
        mActiveSessions.add(new ActiveSession(token, anims, finishT, finishCallback));
        scheduleTick();
    }

    private void scheduleTick() {
        if (!mTickScheduled && !mActiveSessions.isEmpty()) {
            mTickScheduled = true;
            mLastFrameNanos = 0L;
            mChoreographer.postFrameCallback(mFrameCallback);
        }
    }

    private void onFrame(long frameTimeNanos) {
        mTickScheduled = false;
        if (mActiveSessions.isEmpty()) {
            return;
        }
        float dt;
        if (mLastFrameNanos == 0L) {
            dt = 1f / 120f;
        } else {
            dt = (frameTimeNanos - mLastFrameNanos) * 1e-9f;
            if (dt < 1f / 240f) dt = 1f / 240f;
            if (dt > 1f / 30f) dt = 1f / 30f;
        }
        mLastFrameNanos = frameTimeNanos;
        final float scale = mAnimScale > 0f ? mAnimScale : 1f;
        final float simDt = dt / scale;

        final SurfaceControl.Transaction t = mPool.acquire();
        ArrayList<ActiveSession> completed = null;
        for (int s = 0; s < mActiveSessions.size(); s++) {
            final ActiveSession session = mActiveSessions.get(s);
            boolean allDone = true;
            for (int i = 0; i < session.anims.size(); i++) {
                final Anim a = session.anims.get(i);
                if (!a.step(simDt)) {
                    allDone = false;
                }
                a.applyCurrentFrame(t);
            }
            if (allDone) {
                if (completed == null) {
                    completed = new ArrayList<>(2);
                }
                completed.add(session);
            }
        }
        t.setFrameTimelineVsync(mChoreographer.getVsyncId());
        t.apply();
        mPool.release(t);

        if (completed != null) {
            for (int i = 0; i < completed.size(); i++) {
                finishSession(completed.get(i), false /* preserveVelocity */);
            }
        }
        if (!mActiveSessions.isEmpty()) {
            mTickScheduled = true;
            mChoreographer.postFrameCallback(mFrameCallback);
        }
    }

    @Nullable
    private ActiveSession findSession(@NonNull IBinder token) {
        for (int i = mActiveSessions.size() - 1; i >= 0; i--) {
            final ActiveSession s = mActiveSessions.get(i);
            if (s.token == token) {
                return s;
            }
        }
        return null;
    }

    private void finishSession(@NonNull ActiveSession session, boolean preserveVelocity) {
        if (session.finished) {
            return;
        }
        session.finished = true;
        mActiveSessions.remove(session);
        final long now = SystemClock.uptimeMillis();
        for (int i = 0; i < session.anims.size(); i++) {
            final Anim a = session.anims.get(i);
            if (preserveVelocity) {
                final Rect cur = new Rect();
                a.currentRect(cur);
                mCarryStates.put(a.taskId,
                        new CarryState(cur, a.vcx, a.vcy, a.vw, a.vh, now));
                if (a.window != null) {
                    a.window.setVisual(cur);
                }
            }
            a.finish(session.finishT);
            final int n = mAnimating.get(a.taskId) - 1;
            if (n <= 0) {
                mAnimating.delete(a.taskId);
            } else {
                mAnimating.put(a.taskId, n);
            }
        }
        if (mActiveSessions.isEmpty() && mTickScheduled) {
            mChoreographer.removeFrameCallback(mFrameCallback);
            mTickScheduled = false;
        }
        session.finishCallback.onTransitionFinished(null);
    }

    @Nullable
    private CarryState consumeCarry(int taskId) {
        final CarryState cs = mCarryStates.get(taskId);
        if (cs == null) {
            return null;
        }
        mCarryStates.remove(taskId);
        if (SystemClock.uptimeMillis() - cs.uptimeMs > CARRY_TTL_MS) {
            return null;
        }
        return cs;
    }

    @Nullable
    private static String packageOf(@NonNull RunningTaskInfo ti) {
        if (ti.baseActivity != null) return ti.baseActivity.getPackageName();
        if (ti.realActivity != null) return ti.realActivity.getPackageName();
        return ti.baseIntent != null && ti.baseIntent.getComponent() != null
                ? ti.baseIntent.getComponent().getPackageName() : null;
    }

    private static Rect scaled(@NonNull Rect r, float s) {
        final int w = Math.max(1, Math.round(r.width() * s));
        final int h = Math.max(1, Math.round(r.height() * s));
        return new Rect(r.centerX() - w / 2, r.centerY() - h / 2,
                r.centerX() - w / 2 + w, r.centerY() - h / 2 + h);
    }

    /** Sine-out ease for smooth HyperOS 4 alpha fades. */
    private static float sineOut(float t) {
        final float clamped = Math.max(0f, Math.min(1f, t));
        return (float) Math.sin(clamped * (Math.PI * 0.5));
    }

    private static final class ActiveSession {
        final IBinder token;
        final ArrayList<Anim> anims;
        final SurfaceControl.Transaction finishT;
        final Transitions.TransitionFinishCallback finishCallback;
        boolean finished;

        ActiveSession(IBinder token, ArrayList<Anim> anims,
                SurfaceControl.Transaction finishT,
                Transitions.TransitionFinishCallback finishCallback) {
            this.token = token;
            this.anims = anims;
            this.finishT = finishT;
            this.finishCallback = finishCallback;
        }
    }

    private static final class CarryState {
        final Rect visual;
        final float vcx;
        final float vcy;
        final float vw;
        final float vh;
        final long uptimeMs;

        CarryState(Rect visual, float vcx, float vcy, float vw, float vh, long uptimeMs) {
            this.visual = visual;
            this.vcx = vcx;
            this.vcy = vcy;
            this.vw = vw;
            this.vh = vh;
            this.uptimeMs = uptimeMs;
        }
    }

    /** One window being driven by the closed-form 4-channel spring. */
    private static final class Anim {
        final int taskId;
        final SurfaceControl leash;
        final Point offset;
        /** Buffer size of the leash during the animation (end size, or start when closing). */
        final Rect base = new Rect();
        final Rect from = new Rect();
        final Rect to = new Rect();
        final Rect end;
        @Nullable final LayangWindow window;
        final boolean layangAtEnd;
        final int radius;
        final Rect tmpRect;
        final Rect tmpCrop;

        float fromAlpha = 1f;
        float toAlpha = 1f;
        float alphaDurationS = 0.14f;
        float fromRadius;
        float toRadius;
        boolean closing;
        boolean minimizing;
        boolean fadePillIn;

        // Spring parameters (damping ratio zeta, natural angular frequency omega).
        float zeta = MODE_ZETA;
        float omega = TWO_PI / MODE_RESPONSE_S;

        // Live kinematic state: center (cx, cy), size (w, h), and velocities.
        float cx, cy, w, h;
        float vcx, vcy, vw, vh;
        float targetCx, targetCy, targetW, targetH;
        float startDist = 1f;
        float elapsedS;
        boolean settled;

        Anim(int taskId, SurfaceControl leash, Point offset, Rect end,
                @Nullable LayangWindow window, boolean layangAtEnd, int radius,
                Rect tmpRect, Rect tmpCrop) {
            this.taskId = taskId;
            this.leash = leash;
            this.offset = offset;
            this.end = end;
            this.window = window;
            this.layangAtEnd = layangAtEnd;
            this.radius = radius;
            this.fromRadius = radius;
            this.toRadius = layangAtEnd ? radius : 0f;
            this.tmpRect = tmpRect;
            this.tmpCrop = tmpCrop;
            base.set(end);
        }

        void setSpring(float dampingRatio, float responseS) {
            this.zeta = dampingRatio;
            this.omega = TWO_PI / Math.max(0.08f, responseS);
        }

        void initKinematics(@Nullable CarryState carry) {
            cx = from.exactCenterX();
            cy = from.exactCenterY();
            w = Math.max(1f, from.width());
            h = Math.max(1f, from.height());
            targetCx = to.exactCenterX();
            targetCy = to.exactCenterY();
            targetW = Math.max(1f, to.width());
            targetH = Math.max(1f, to.height());
            if (carry != null) {
                vcx = carry.vcx;
                vcy = carry.vcy;
                vw = carry.vw;
                vh = carry.vh;
            } else {
                vcx = vcy = vw = vh = 0f;
            }
            startDist = Math.max(1f, Math.abs(targetCx - cx) + Math.abs(targetCy - cy)
                    + Math.abs(targetW - w) + Math.abs(targetH - h));
            elapsedS = 0f;
            settled = false;
        }

        /** Advances the closed-form damped harmonic oscillator by dt seconds; returns true when settled. */
        boolean step(float dt) {
            if (settled) {
                return true;
            }
            elapsedS += dt;
            if (zeta < 0.9999f) {
                final double wd = omega * Math.sqrt(1.0 - zeta * zeta);
                final double env = Math.exp(-zeta * omega * dt);
                final double cos = Math.cos(wd * dt);
                final double sin = Math.sin(wd * dt);
                final double zo = zeta * omega;

                // Center X
                double d = cx - targetCx;
                double b = (vcx + zo * d) / wd;
                cx = (float) (targetCx + env * (d * cos + b * sin));
                vcx = (float) (env * ((b * wd - d * zo) * cos - (d * wd + b * zo) * sin));

                // Center Y
                d = cy - targetCy;
                b = (vcy + zo * d) / wd;
                cy = (float) (targetCy + env * (d * cos + b * sin));
                vcy = (float) (env * ((b * wd - d * zo) * cos - (d * wd + b * zo) * sin));

                // Width
                d = w - targetW;
                b = (vw + zo * d) / wd;
                w = Math.max(1f, (float) (targetW + env * (d * cos + b * sin)));
                vw = (float) (env * ((b * wd - d * zo) * cos - (d * wd + b * zo) * sin));

                // Height
                d = h - targetH;
                b = (vh + zo * d) / wd;
                h = Math.max(1f, (float) (targetH + env * (d * cos + b * sin)));
                vh = (float) (env * ((b * wd - d * zo) * cos - (d * wd + b * zo) * sin));
            } else {
                final double env = Math.exp(-omega * dt);
                // Critically damped
                double d = cx - targetCx;
                double b = vcx + omega * d;
                cx = (float) (targetCx + env * (d + b * dt));
                vcx = (float) (env * (b - omega * (d + b * dt)));

                d = cy - targetCy;
                b = vcy + omega * d;
                cy = (float) (targetCy + env * (d + b * dt));
                vcy = (float) (env * (b - omega * (d + b * dt)));

                d = w - targetW;
                b = vw + omega * d;
                w = Math.max(1f, (float) (targetW + env * (d + b * dt)));
                vw = (float) (env * (b - omega * (d + b * dt)));

                d = h - targetH;
                b = vh + omega * d;
                h = Math.max(1f, (float) (targetH + env * (d + b * dt)));
                vh = (float) (env * (b - omega * (d + b * dt)));
            }

            final boolean posSettled = Math.abs(cx - targetCx) < POS_EPSILON_PX
                    && Math.abs(cy - targetCy) < POS_EPSILON_PX
                    && Math.abs(w - targetW) < POS_EPSILON_PX
                    && Math.abs(h - targetH) < POS_EPSILON_PX;
            final boolean velSettled = Math.abs(vcx) < VEL_EPSILON_PX_S
                    && Math.abs(vcy) < VEL_EPSILON_PX_S
                    && Math.abs(vw) < VEL_EPSILON_PX_S
                    && Math.abs(vh) < VEL_EPSILON_PX_S;
            final boolean alphaSettled = alphaDurationS <= 0f || elapsedS >= alphaDurationS;
            if ((posSettled && velSettled && alphaSettled) || elapsedS >= MAX_DURATION_S) {
                cx = targetCx;
                cy = targetCy;
                w = targetW;
                h = targetH;
                vcx = vcy = vw = vh = 0f;
                settled = true;
            }
            return settled;
        }

        void currentRect(@NonNull Rect out) {
            final int rw = Math.max(1, Math.round(w));
            final int rh = Math.max(1, Math.round(h));
            final int left = Math.round(cx - rw * 0.5f);
            final int top = Math.round(cy - rh * 0.5f);
            out.set(left, top, left + rw, top + rh);
        }

        void applyCurrentFrame(@NonNull SurfaceControl.Transaction t) {
            if (!leash.isValid()) {
                return;
            }
            currentRect(tmpRect);
            final float remDist = Math.abs(targetCx - cx) + Math.abs(targetCy - cy)
                    + Math.abs(targetW - w) + Math.abs(targetH - h);
            final float geomProgress = Math.max(0f, Math.min(1f, 1f - remDist / startDist));
            final float alphaFrac = alphaDurationS <= 0f ? 1f
                    : sineOut(elapsedS / alphaDurationS);
            final float alpha = fromAlpha + (toAlpha - fromAlpha) * alphaFrac;
            final float displayRadius = fromRadius + (toRadius - fromRadius) * geomProgress;
            setFrame(t, tmpRect, alpha, displayRadius, geomProgress);
        }

        void setFrame(@NonNull SurfaceControl.Transaction t, @NonNull Rect r, float alpha,
                float displayRadius, float geomProgress) {
            if (!leash.isValid()) {
                return;
            }
            final int baseW = Math.max(1, base.width());
            final int baseH = Math.max(1, base.height());
            final float scaleX = (float) r.width() / baseW;
            final float scaleY = (float) r.height() / baseH;
            // Aspect-safe cover scale with centered crop so icon pop-ups and aspect changes
            // never stretch, letterbox, or explode to huge scale factors.
            final float scale = Math.max(0.02f, Math.max(scaleX, scaleY));
            final float cropW = Math.min(baseW, r.width() / scale);
            final float cropH = Math.min(baseH, r.height() / scale);
            final int cropL = Math.max(0, Math.round((baseW - cropW) * 0.5f));
            final int cropT = Math.max(0, Math.round((baseH - cropH) * 0.5f));
            final int cropR = Math.max(cropL + 1, Math.min(baseW, Math.round(cropL + cropW)));
            final int cropB = Math.max(cropT + 1, Math.min(baseH, Math.round(cropT + cropH)));
            tmpCrop.set(cropL, cropT, cropR, cropB);

            t.setPosition(leash,
                    r.left - offset.x - cropL * scale,
                    r.top - offset.y - cropT * scale);
            t.setScale(leash, scale, scale);
            t.setAlpha(leash, Math.max(0f, Math.min(1f, alpha)));
            if (layangAtEnd || closing || displayRadius > 0.5f) {
                t.setCrop(leash, tmpCrop);
                // Inverse-scale corner radius into pre-scale buffer coordinates so corners
                // always stay crisp and rounded at every intermediate scale.
                t.setCornerRadius(leash, displayRadius / Math.max(0.08f, scale));
            }
            if (window != null && layangAtEnd && !closing) {
                window.setVisual(r);
                window.layoutDecor(t, r);
                if (fadePillIn) {
                    final float pillAlpha = geomProgress < 0.65f ? 0f
                            : Math.min(1f, (geomProgress - 0.65f) / 0.35f);
                    window.setPillAlpha(t, pillAlpha);
                }
            }
        }

        void finish(@NonNull SurfaceControl.Transaction finishT) {
            if (!leash.isValid()) {
                return;
            }
            finishT.setPosition(leash, end.left - offset.x, end.top - offset.y);
            finishT.setScale(leash, 1f, 1f);
            finishT.setAlpha(leash, layangAtEnd && window != null ? window.restAlpha() : 1f);
            if (layangAtEnd) {
                finishT.setWindowCrop(leash, end.width(), end.height());
                finishT.setCornerRadius(leash, radius);
                if (window != null) {
                    window.setPillAlpha(finishT, 1f);
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
}
