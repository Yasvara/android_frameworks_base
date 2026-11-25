/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_BOUNDS;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_DISPLAY;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.pm.ActivityInfo.WindowLayout;
import android.graphics.Rect;

import com.android.server.wm.LaunchParamsController.LaunchParams;
import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;

/**
 * Yasvara Layang: places a Layang launch before any other modifier, so the
 * first frame is already at the final rect R and nothing resizes the window afterwards (the
 * Infinity "bounds war", §3). Registered last, so it runs first and finishes the calculation.
 */
final class LayangLaunchParamsModifier implements LaunchParamsModifier {
    private final ActivityTaskSupervisor mSupervisor;

    LayangLaunchParamsModifier(ActivityTaskSupervisor supervisor) {
        mSupervisor = supervisor;
    }

    @Override
    public int onCalculate(@Nullable Task task, @Nullable WindowLayout layout,
            @Nullable ActivityRecord activity, @Nullable ActivityRecord source,
            @Nullable ActivityOptions options, @Nullable ActivityStarter.Request request,
            int phase, LaunchParams currentParams, LaunchParams outParams) {
        if (options == null || options.getLayangLaunch() == null) {
            return RESULT_SKIP;
        }
        // Layang windows live on the default display (non-goal: secondary displays).
        outParams.mPreferredTaskDisplayArea =
                mSupervisor.mRootWindowContainer.getDefaultTaskDisplayArea();
        if (phase == PHASE_DISPLAY) {
            return RESULT_DONE;
        }
        outParams.mWindowingMode = WINDOWING_MODE_FREEFORM;
        if (phase < PHASE_BOUNDS) {
            return RESULT_DONE;
        }
        final Rect bounds = options.getLaunchBounds();
        if (bounds != null && !bounds.isEmpty()) {
            outParams.mBounds.set(bounds);
            outParams.mBoundsSet = true;
            outParams.mBoundsSetFromOptions = true;
        }
        return RESULT_DONE;
    }
}
