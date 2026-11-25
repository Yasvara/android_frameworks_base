/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;

import java.io.PrintWriter;

/**
 * Lets manual brightness reach the HBM range in sunlight. The light sensor only runs while the
 * slider sits at the top of the normal range, so it costs nothing otherwise.
 */
final class ManualSunlightController implements SensorEventListener {
    private static final int SENSOR_RATE_MICROS = 250000;
    // The under-display sensor dips for a few seconds on content changes, so average over longer.
    private static final long HORIZON_MILLIS = 5000;
    private static final int MAX_SAMPLES = 64;
    private static final float EXIT_RATIO = 0.75f;
    private static final float LUX_STEP_RATIO = 0.2f;

    private final Handler mHandler;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;
    private final float mMinLux;
    private final Runnable mOnChange;

    private final long[] mTimes = new long[MAX_SAMPLES];
    private final float[] mLux = new float[MAX_SAMPLES];
    private int mStart;
    private int mCount;

    private boolean mEnabled;
    private long mEnableTime;
    private boolean mActive;
    private float mReportedLux;

    ManualSunlightController(Handler handler, SensorManager sensorManager, Sensor lightSensor,
            float minLux, Runnable onChange) {
        mHandler = handler;
        mSensorManager = sensorManager;
        mLightSensor = lightSensor;
        mMinLux = minLux;
        mOnChange = onChange;
    }

    void setEnabled(boolean enabled) {
        if (enabled == mEnabled || mLightSensor == null) {
            return;
        }
        mEnabled = enabled;
        mCount = 0;
        if (enabled) {
            mEnableTime = SystemClock.uptimeMillis();
            mSensorManager.registerListener(this, mLightSensor, SENSOR_RATE_MICROS, mHandler);
        } else {
            mSensorManager.unregisterListener(this);
            mActive = false;
        }
    }

    boolean isActive() {
        return mEnabled && mActive;
    }

    float getLux() {
        return mReportedLux;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mEnabled) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        push(now, event.values[0]);
        // Wait for a full horizon so one bright frame at enable cannot trigger it.
        if (now - mEnableTime < HORIZON_MILLIS) {
            return;
        }
        final float lux = getMeanLux(now);
        final boolean active = mActive ? lux >= mMinLux * EXIT_RATIO : lux >= mMinLux;
        boolean changed = active != mActive;
        mActive = active;
        if (active && Math.abs(lux - mReportedLux) > mReportedLux * LUX_STEP_RATIO) {
            mReportedLux = lux;
            changed = true;
        }
        if (changed) {
            mOnChange.run();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void push(long time, float lux) {
        while (mCount > 0 && time - mTimes[mStart] > HORIZON_MILLIS) {
            mStart = (mStart + 1) % MAX_SAMPLES;
            mCount--;
        }
        if (mCount == MAX_SAMPLES) {
            mStart = (mStart + 1) % MAX_SAMPLES;
            mCount--;
        }
        final int index = (mStart + mCount) % MAX_SAMPLES;
        mTimes[index] = time;
        mLux[index] = lux;
        mCount++;
    }

    // Time-weighted, each sample holding until the next one.
    private float getMeanLux(long now) {
        double sum = 0;
        long span = 0;
        for (int i = 0; i < mCount; i++) {
            final int index = (mStart + i) % MAX_SAMPLES;
            final long end = i + 1 < mCount ? mTimes[(index + 1) % MAX_SAMPLES] : now;
            final long duration = Math.max(end - mTimes[index], 1);
            sum += mLux[index] * duration;
            span += duration;
        }
        return span > 0 ? (float) (sum / span) : 0f;
    }

    void dump(PrintWriter pw) {
        pw.println("ManualSunlightController:");
        pw.println("  mEnabled=" + mEnabled);
        pw.println("  mActive=" + mActive);
        pw.println("  mReportedLux=" + mReportedLux);
        pw.println("  mMinLux=" + mMinLux);
    }
}
