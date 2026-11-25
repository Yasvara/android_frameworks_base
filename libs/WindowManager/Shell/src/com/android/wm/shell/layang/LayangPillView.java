/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.wm.shell.layang;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.function.Consumer;

/**
 * The pill above a Layang window: a 40x4 dp bar in a 48x24 dp touch target.
 * Accent when the window is focused (D15), neutral at 60% otherwise. No shadow and no border,
 * since both force GPU composition (F7). Drag moves, tap opens the menu, flicks maximize or
 * minimize; the gestures themselves live in {@link LayangWindow}.
 */
final class LayangPillView extends View {
    static final int BAR_W_DP = 40;
    static final int BAR_H_DP = 4;
    static final int TOUCH_W_DP = 48;
    static final int TOUCH_H_DP = 24;

    /** Accessibility actions, mapped to the window menu items (§6.8). */
    static final int A11Y_FULLSCREEN = 1;
    static final int A11Y_MINI = 2;
    static final int A11Y_CLOSE = 3;
    static final int A11Y_BIGGER = 4;
    static final int A11Y_SMALLER = 5;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBar = new RectF();
    private final float mBarW;
    private final float mBarH;
    private final int mAccent;
    private final int mNeutral;
    private Consumer<MotionEvent> mTouchListener;
    private Consumer<Integer> mA11yListener;
    private boolean mFocused;

    LayangPillView(@NonNull Context context) {
        super(context);
        final float d = context.getResources().getDisplayMetrics().density;
        mBarW = BAR_W_DP * d;
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
        mPaint.setColor(focused ? mAccent : mNeutral);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        mBar.set(cx - mBarW / 2f, cy - mBarH / 2f, cx + mBarW / 2f, cy + mBarH / 2f);
        canvas.drawRoundRect(mBar, mBarH / 2f, mBarH / 2f, mPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
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
