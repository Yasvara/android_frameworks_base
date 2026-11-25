/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wm.shell.layang;

import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.function.Consumer;

/**
 * The pill above a Layang window: a smooth dynamic-width bar (36 dp unfocused → 44 dp focused)
 * in a 48x24 dp touch target. Accent when focused (D15), neutral at 60% otherwise, with smooth
 * color/width transition and tactile press swell. No shadow and no border, since both force GPU
 * composition (F7). Drag moves, tap opens the menu, flicks maximize or minimize; the gestures
 * themselves live in {@link LayangWindow}.
 */
final class LayangPillView extends View {
    static final int BAR_W_DP = 40;
    static final int BAR_W_UNFOCUSED_DP = 36;
    static final int BAR_W_FOCUSED_DP = 44;
    static final int BAR_H_DP = 4;
    static final int TOUCH_W_DP = 48;
    static final int TOUCH_H_DP = 24;

    /** Accessibility actions, mapped to the window menu items (§6.8). */
    static final int A11Y_FULLSCREEN = 1;
    static final int A11Y_MINI = 2;
    static final int A11Y_CLOSE = 3;
    static final int A11Y_BIGGER = 4;
    static final int A11Y_SMALLER = 5;

    private static final long FOCUS_ANIM_MS = 180L;
    private static final long PRESS_ANIM_MS = 130L;
    private static final float PRESS_SCALE = 1.08f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBar = new RectF();
    private final float mBarWUnfocused;
    private final float mBarWFocused;
    private final float mBarH;
    private final int mAccent;
    private final int mNeutral;
    private Consumer<MotionEvent> mTouchListener;
    private Consumer<Integer> mA11yListener;
    private boolean mFocused;
    private boolean mPressed;
    private float mFocusFraction;
    private float mPressFraction;
    private ValueAnimator mFocusAnim;
    private ValueAnimator mPressAnim;

    LayangPillView(@NonNull Context context) {
        super(context);
        final float d = context.getResources().getDisplayMetrics().density;
        mBarWUnfocused = BAR_W_UNFOCUSED_DP * d;
        mBarWFocused = BAR_W_FOCUSED_DP * d;
        mBarH = BAR_H_DP * d;
        mAccent = context.getColor(android.R.color.system_accent1_300);
        final int n = context.getColor(android.R.color.system_neutral1_300);
        mNeutral = (n & 0x00ffffff) | (0x99 << 24);
        mPaint.setColor(mNeutral);
        setContentDescription(context.getString(
                com.android.wm.shell.R.string.layang_pill_description));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setTouchListener(Consumer<MotionEvent> l) {
        mTouchListener = l;
    }

    void setAccessibilityActionListener(Consumer<Integer> l) {
        mA11yListener = l;
    }

    void setWindowFocused(boolean focused) {
        if (mFocused == focused) {
            return;
        }
        mFocused = focused;
        final float target = focused ? 1f : 0f;
        if (mFocusAnim != null) {
            mFocusAnim.cancel();
        }
        if (!isAttachedToWindow()) {
            mFocusFraction = target;
            updatePaintColor();
            invalidate();
            return;
        }
        mFocusAnim = ValueAnimator.ofFloat(mFocusFraction, target);
        mFocusAnim.setDuration(FOCUS_ANIM_MS);
        mFocusAnim.setInterpolator(new DecelerateInterpolator(1.8f));
        mFocusAnim.addUpdateListener(a -> {
            mFocusFraction = (float) a.getAnimatedValue();
            updatePaintColor();
            invalidate();
        });
        mFocusAnim.start();
    }

    private void animatePressed(boolean pressed) {
        if (mPressed == pressed) {
            return;
        }
        mPressed = pressed;
        final float target = pressed ? 1f : 0f;
        if (mPressAnim != null) {
            mPressAnim.cancel();
        }
        mPressAnim = ValueAnimator.ofFloat(mPressFraction, target);
        mPressAnim.setDuration(PRESS_ANIM_MS);
        mPressAnim.setInterpolator(pressed
                ? new DecelerateInterpolator(2f)
                : new OvershootInterpolator(1.5f));
        mPressAnim.addUpdateListener(a -> {
            mPressFraction = (float) a.getAnimatedValue();
            invalidate();
        });
        mPressAnim.start();
    }

    private void updatePaintColor() {
        final float f = Math.max(0f, Math.min(1f, mFocusFraction));
        final int a = Math.round(Color.alpha(mNeutral) + (Color.alpha(mAccent) - Color.alpha(mNeutral)) * f);
        final int r = Math.round(Color.red(mNeutral) + (Color.red(mAccent) - Color.red(mNeutral)) * f);
        final int g = Math.round(Color.green(mNeutral) + (Color.green(mAccent) - Color.green(mNeutral)) * f);
        final int b = Math.round(Color.blue(mNeutral) + (Color.blue(mAccent) - Color.blue(mNeutral)) * f);
        mPaint.setColor(Color.argb(a, r, g, b));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mFocusAnim != null) {
            mFocusAnim.cancel();
            mFocusAnim = null;
        }
        if (mPressAnim != null) {
            mPressAnim.cancel();
            mPressAnim = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float pressScale = 1f + (PRESS_SCALE - 1f) * mPressFraction;
        final float w = (mBarWUnfocused + (mBarWFocused - mBarWUnfocused) * mFocusFraction)
                * pressScale;
        final float h = mBarH * (1f + 0.12f * mPressFraction);
        mBar.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        canvas.drawRoundRect(mBar, h / 2f, h / 2f, mPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            animatePressed(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            animatePressed(false);
        }
        if (mTouchListener != null) {
            mTouchListener.accept(event);
        }
        return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        final Context c = getContext();
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(A11Y_BIGGER,
                c.getString(com.android.wm.shell.R.string.layang_action_bigger)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(A11Y_SMALLER,
                c.getString(com.android.wm.shell.R.string.layang_action_smaller)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(A11Y_FULLSCREEN,
                c.getString(com.android.wm.shell.R.string.layang_action_fullscreen)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(A11Y_MINI,
                c.getString(com.android.wm.shell.R.string.layang_action_mini)));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(A11Y_CLOSE,
                c.getString(com.android.wm.shell.R.string.layang_action_close)));
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action >= A11Y_FULLSCREEN && action <= A11Y_SMALLER && mA11yListener != null) {
            mA11yListener.accept(action);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
