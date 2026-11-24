/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display;

import android.os.SystemClock;
import android.os.SystemProperties;

import com.android.server.display.config.HighBrightnessModeData;

/**
 * Auto-brightness ramp shaped like the panel's stock tuning: brightening finishes within four
 * seconds whatever the distance, darkening decays exponentially and slows down in the dark, though
 * up to eight times faster than stock. A large rise shortly after the light sensor starts ramps fast.
 *
 * lab/display-hal/tools/ab_sim.py models this class against stock.
 */
final class PerceptualRampCurve implements RampAnimator.RateCurve {
    static final boolean ENABLED =
            SystemProperties.getBoolean("ro.yasvara.display.perceptual_ramp", false);

    // How long after the light sensor starts a large rise may skip the slow ramp and debounce.
    static final long FAST_WINDOW_MILLIS = 10000;
    static final float FAST_MIN_RATIO = 0.6f;
    static final float SKIP_DEBOUNCE_MIN_LUX = 20f;

    private static final long FAST_RESET_MILLIS = 2000;
    private static final float FAST_MIN_NITS = 55.5f;
    private static final float FAST_SECONDS = 0.8f;

    private static final float BRIGHTEN_LOW_NITS = 35f;
    private static final float BRIGHTEN_MID_NITS = 87.45f;
    private static final float BRIGHTEN_HIGH_NITS = 265f;

    private static final float TIME_START = 0f;
    private static final float TIME_LOW = 0.8f;
    private static final float TIME_MID = 1.8f;
    private static final float TIME_END = 4f;

    // Decay per 1/24 s below each luminance step.
    private static final float[] DARKEN_NITS = {800f, 251f, 150f, 100f, 70f, 50f, 40f, 30f, 28.5f};
    private static final float[] DARKEN_DECAY =
            {0.9887f, 0.992f, 0.995f, 0.9965f, 0.9973f, 0.9979f, 0.9982f, 0.999f, 0.996f};
    private static final float DARKEN_STEPS_PER_SECOND = 24f;

    // Stock takes 25 s from 100 to 24 nits, long enough that its under-display sensor never
    // settles the target before the screen gets there.
    private static final float DARKEN_SPEEDUP = 8f;
    // Keeps the bright end from dropping abruptly, in e-folds per second.
    private static final double DARKEN_MAX_SPEED = 0.8;

    private static final float MIN_RATE = 1f / 16383f;

    private final DisplayDeviceConfig mConfig;
    private final float mTransitionBrightness;
    private final float mTransitionNits;
    private final float mMaxNits;

    private float mSpeedUp = 1f;
    private float mSpeedDown = 1f;
    private float mAutoBrightness;
    private long mSensorEnableTime;
    private boolean mFast;
    private float mFastGoal;
    private long mFastStartTime;

    PerceptualRampCurve(DisplayDeviceConfig config) {
        mConfig = config;
        final HighBrightnessModeData hbm = config.getHighBrightnessModeData();
        mTransitionBrightness = hbm != null ? hbm.transitionPoint : 1f;
        mTransitionNits = config.getNitsFromBacklight(mTransitionBrightness);
        mMaxNits = config.getNitsFromBacklight(1f);
    }

    /** Scales the brightening and darkening rates; the post-start fast ramp keeps its pace. */
    void setSpeed(float up, float down) {
        mSpeedUp = up;
        mSpeedDown = down;
    }

    /** Reports the latest auto-brightness target and when the light sensor last started. */
    void update(float autoBrightness, long sensorEnableTime) {
        mAutoBrightness = autoBrightness;
        mSensorEnableTime = sensorEnableTime;
    }

    @Override
    public float getRate(float start, float current, float target) {
        final float rate;
        if (updateFast(current)) {
            rate = target > current
                    ? (float) ((Math.exp(target) - Math.exp(start)) / FAST_SECONDS
                            / Math.exp(current))
                    : (float) Math.abs(Math.log(target / start) / FAST_SECONDS * current);
        } else {
            rate = target > current
                    ? getBrighteningRate(start, current, target) * mSpeedUp
                    : getDarkeningRate(current) * mSpeedDown;
        }
        return Float.isFinite(rate) ? Math.max(rate, MIN_RATE) : MIN_RATE;
    }

    private boolean updateFast(float current) {
        final long now = SystemClock.uptimeMillis();
        if (mFast && now - mFastStartTime >= FAST_RESET_MILLIS) {
            mFast = false;
        }
        final float goalNits = mConfig.getNitsFromBacklight(mAutoBrightness);
        final float currentNits = mConfig.getNitsFromBacklight(current);
        if (!mFast && now <= mSensorEnableTime + FAST_WINDOW_MILLIS
                && goalNits >= currentNits * (1f + FAST_MIN_RATIO)
                && goalNits >= currentNits + FAST_MIN_NITS) {
            mFast = true;
            mFastGoal = mAutoBrightness;
            mFastStartTime = now;
        }
        if (mFast && (mAutoBrightness < mFastGoal
                || (mAutoBrightness > mFastGoal && current >= mFastGoal))) {
            mFast = false;
        }
        return mFast;
    }

    private float getBrighteningRate(float start, float current, float target) {
        final float nits = mConfig.getNitsFromBacklight(current);
        final float startNits = mConfig.getNitsFromBacklight(start);
        final float targetNits = mConfig.getNitsFromBacklight(target);
        if (startNits >= BRIGHTEN_LOW_NITS) {
            if (startNits >= BRIGHTEN_HIGH_NITS || targetNits < BRIGHTEN_HIGH_NITS) {
                return getExpRate(startNits, targetNits, nits, TIME_START, TIME_END);
            }
            return nits >= BRIGHTEN_MID_NITS
                    ? getExpRate(BRIGHTEN_MID_NITS, targetNits, nits, TIME_MID, TIME_END)
                    : getExpRate(startNits, BRIGHTEN_MID_NITS, nits, TIME_START, TIME_MID);
        }
        if (targetNits < BRIGHTEN_LOW_NITS) {
            return toBrightness(targetNits - startNits) / TIME_END;
        }
        if (targetNits < BRIGHTEN_HIGH_NITS) {
            return nits < BRIGHTEN_LOW_NITS
                    ? (toBrightness(BRIGHTEN_LOW_NITS) - start) / TIME_MID
                    : getExpRate(BRIGHTEN_LOW_NITS, targetNits, nits, TIME_MID, TIME_END);
        }
        if (nits < BRIGHTEN_LOW_NITS) {
            return (toBrightness(BRIGHTEN_LOW_NITS) - start) / TIME_LOW;
        }
        return nits >= BRIGHTEN_MID_NITS
                ? getExpRate(BRIGHTEN_MID_NITS, targetNits, nits, TIME_MID, TIME_END)
                : getExpRate(BRIGHTEN_LOW_NITS, BRIGHTEN_MID_NITS, nits, TIME_LOW, TIME_MID);
    }

    private float getExpRate(float startNits, float endNits, float nits, float startTime,
            float endTime) {
        final double growth = Math.log(toBrightness(endNits) / toBrightness(startNits))
                / (endTime - startTime);
        return (float) (growth * toBrightness(nits));
    }

    private float getDarkeningRate(float current) {
        final float nits = mConfig.getNitsFromBacklight(current);
        final double nitsPerSecond = nits * Math.min(DARKEN_MAX_SPEED,
                DARKEN_SPEEDUP * DARKEN_STEPS_PER_SECOND
                        * Math.abs(Math.log(DARKEN_DECAY[getDarkenIndex(nits)])));
        final float brightnessPerNit = nits > mTransitionNits
                ? (1f - mTransitionBrightness) / (mMaxNits - mTransitionNits)
                : mTransitionBrightness / mTransitionNits;
        return (float) (nitsPerSecond * brightnessPerNit);
    }

    private static int getDarkenIndex(float nits) {
        int index = 1;
        while (index < DARKEN_NITS.length && nits < DARKEN_NITS[index]) {
            index++;
        }
        return index - 1;
    }

    private float toBrightness(float nits) {
        return mConfig.getBrightnessFromBacklight(mConfig.getBacklightFromNits(nits));
    }
}
