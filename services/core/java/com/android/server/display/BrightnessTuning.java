/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display;

import android.content.ContentResolver;
import android.os.UserHandle;
import android.provider.Settings;

import java.io.PrintWriter;

/**
 * User brightness tuning from Settings.Secure. Every value is clamped to its range and an unset or
 * unparsable key means the build's own tuning, so no keys behaves exactly like no tuning.
 *
 * The profile picks speed and debounce; sunlight and limits are independent of it.
 */
final class BrightnessTuning {
    static final String PROFILE = "yasvara_brightness_profile";
    static final String SPEED_UP = "yasvara_brightness_speed_up";
    static final String SPEED_DOWN = "yasvara_brightness_speed_down";
    static final String DEBOUNCE_UP = "yasvara_brightness_debounce_up_ms";
    static final String DEBOUNCE_DOWN = "yasvara_brightness_debounce_down_ms";
    static final String SUN = "yasvara_brightness_sun";
    static final String SUN_LUX = "yasvara_brightness_sun_lux";
    static final String FLOOR_NITS = "yasvara_brightness_floor_nits";
    static final String CEILING_NITS = "yasvara_brightness_ceiling_nits";

    static final String[] KEYS = {PROFILE, SPEED_UP, SPEED_DOWN, DEBOUNCE_UP, DEBOUNCE_DOWN, SUN,
            SUN_LUX, FLOOR_NITS, CEILING_NITS};

    static final int PROFILE_BALANCED = 0;
    static final int PROFILE_GENTLE = 1;
    static final int PROFILE_RESPONSIVE = 2;
    static final int PROFILE_CUSTOM = 3;

    static final int SUN_OFF = 0;
    static final int SUN_CURVE = 1;
    static final int SUN_FIXED = 2;

    /** No override: the caller keeps its configured debounce. */
    static final long DEBOUNCE_DEFAULT = -1;

    private static final float SPEED_MIN = 0.5f;
    private static final float SPEED_MAX = 4f;
    private static final long DEBOUNCE_MIN = 100;
    private static final long DEBOUNCE_MAX = 5000;
    private static final float SUN_LUX_MIN = 2000f;
    private static final float SUN_LUX_MAX = 20000f;
    private static final float LIMIT_MIN_GAP_NITS = 50f;

    // {speed up, speed down, debounce up, debounce down} per preset.
    private static final float[] GENTLE = {0.6f, 0.6f, 1000, 3000};
    private static final float[] RESPONSIVE = {1.6f, 2f, 250, 1000};

    private final float mMinNits;
    private final float mMaxNits;

    private int mProfile = PROFILE_BALANCED;
    private float mSpeedUp = 1f;
    private float mSpeedDown = 1f;
    private long mDebounceUp = DEBOUNCE_DEFAULT;
    private long mDebounceDown = DEBOUNCE_DEFAULT;
    private int mSun = SUN_CURVE;
    private float mSunLux = Float.NaN;
    private float mFloorNits = Float.NaN;
    private float mCeilingNits = Float.NaN;

    BrightnessTuning(float minNits, float maxNits) {
        mMinNits = minNits;
        mMaxNits = maxNits;
    }

    /** Re-reads every key; returns whether any effective value changed. */
    boolean reload(ContentResolver resolver) {
        final int profile = clamp(getInt(resolver, PROFILE, PROFILE_BALANCED),
                PROFILE_BALANCED, PROFILE_CUSTOM);
        float speedUp = 1f;
        float speedDown = 1f;
        long debounceUp = DEBOUNCE_DEFAULT;
        long debounceDown = DEBOUNCE_DEFAULT;
        switch (profile) {
            case PROFILE_GENTLE, PROFILE_RESPONSIVE -> {
                final float[] preset = profile == PROFILE_GENTLE ? GENTLE : RESPONSIVE;
                speedUp = preset[0];
                speedDown = preset[1];
                debounceUp = (long) preset[2];
                debounceDown = (long) preset[3];
            }
            case PROFILE_CUSTOM -> {
                speedUp = clamp(getFloat(resolver, SPEED_UP, 1f), SPEED_MIN, SPEED_MAX);
                speedDown = clamp(getFloat(resolver, SPEED_DOWN, 1f), SPEED_MIN, SPEED_MAX);
                debounceUp = getDebounce(resolver, DEBOUNCE_UP);
                debounceDown = getDebounce(resolver, DEBOUNCE_DOWN);
            }
            default -> { }
        }

        final int sun = clamp(getInt(resolver, SUN, SUN_CURVE), SUN_OFF, SUN_FIXED);
        float sunLux = getFloat(resolver, SUN_LUX, Float.NaN);
        if (!Float.isNaN(sunLux)) {
            sunLux = clamp(sunLux, SUN_LUX_MIN, SUN_LUX_MAX);
        }

        float ceiling = getFloat(resolver, CEILING_NITS, Float.NaN);
        if (!Float.isNaN(ceiling)) {
            ceiling = clamp(ceiling, Math.min(mMinNits + LIMIT_MIN_GAP_NITS, mMaxNits), mMaxNits);
        }
        float floor = getFloat(resolver, FLOOR_NITS, Float.NaN);
        if (!Float.isNaN(floor)) {
            final float top = Float.isNaN(ceiling) ? mMaxNits : ceiling - LIMIT_MIN_GAP_NITS;
            floor = clamp(floor, mMinNits, Math.max(top, mMinNits));
        }

        final boolean changed = profile != mProfile
                || Float.compare(speedUp, mSpeedUp) != 0
                || Float.compare(speedDown, mSpeedDown) != 0
                || debounceUp != mDebounceUp || debounceDown != mDebounceDown
                || sun != mSun || Float.compare(sunLux, mSunLux) != 0
                || Float.compare(floor, mFloorNits) != 0
                || Float.compare(ceiling, mCeilingNits) != 0;
        mProfile = profile;
        mSpeedUp = speedUp;
        mSpeedDown = speedDown;
        mDebounceUp = debounceUp;
        mDebounceDown = debounceDown;
        mSun = sun;
        mSunLux = sunLux;
        mFloorNits = floor;
        mCeilingNits = ceiling;
        return changed;
    }

    float getSpeedUp() {
        return mSpeedUp;
    }

    float getSpeedDown() {
        return mSpeedDown;
    }

    long getDebounceUp() {
        return mDebounceUp;
    }

    long getDebounceDown() {
        return mDebounceDown;
    }

    int getSunMode() {
        return mSun;
    }

    float getSunLux(float defaultLux) {
        return Float.isNaN(mSunLux) ? defaultLux : mSunLux;
    }

    /** NaN when unset. */
    float getFloorNits() {
        return mFloorNits;
    }

    /** NaN when unset. */
    float getCeilingNits() {
        return mCeilingNits;
    }

    boolean hasLimits() {
        return !Float.isNaN(mFloorNits) || !Float.isNaN(mCeilingNits);
    }

    private static long getDebounce(ContentResolver resolver, String key) {
        final int value = getInt(resolver, key, (int) DEBOUNCE_DEFAULT);
        return value < 0 ? DEBOUNCE_DEFAULT : clamp(value, DEBOUNCE_MIN, DEBOUNCE_MAX);
    }

    private static int getInt(ContentResolver resolver, String key, int def) {
        final String value = Settings.Secure.getStringForUser(resolver, key,
                UserHandle.USER_CURRENT);
        try {
            return value == null ? def : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float getFloat(ContentResolver resolver, String key, float def) {
        final String value = Settings.Secure.getStringForUser(resolver, key,
                UserHandle.USER_CURRENT);
        try {
            final float parsed = value == null ? def : Float.parseFloat(value.trim());
            return Float.isFinite(parsed) ? parsed : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    void dump(PrintWriter pw) {
        pw.println("BrightnessTuning:");
        pw.println("  profile=" + mProfile);
        pw.println("  speed=" + mSpeedUp + "/" + mSpeedDown);
        pw.println("  debounce=" + mDebounceUp + "/" + mDebounceDown);
        pw.println("  sun=" + mSun + " lux=" + mSunLux);
        pw.println("  limits=" + mFloorNits + ".." + mCeilingNits
                + " (panel " + mMinNits + ".." + mMaxNits + ")");
    }
}
