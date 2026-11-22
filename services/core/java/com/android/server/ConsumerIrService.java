/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.Manifest.permission.TRANSMIT_IR;

import android.annotation.EnforcePermission;
import android.annotation.RequiresNoPermission;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.IConsumerIrService;
import android.hardware.ir.ConsumerIrFreqRange;
import android.hardware.ir.IConsumerIr;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

public class ConsumerIrService extends IConsumerIrService.Stub {
    private static final String TAG = "ConsumerIrService";
    private static final String FQ_NAME = IConsumerIr.DESCRIPTOR + "/default";

    private static final int MAX_XMIT_TIME = 2000000; /* in microseconds */
    private static final long IDLE_RELEASE_MS = 10_000L;

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final boolean mHasNativeHal;
    private final Object mHalLock = new Object();
    private final Handler mHandler = BackgroundThread.getHandler();
    private IConsumerIr mAidlService = null;

    private final Runnable mReleaseHal = () -> {
        synchronized (mHalLock) {
            if (mAidlService != null) {
                mAidlService = null;
                Binder.flushPendingCommands();
            }
        }
    };

    ConsumerIrService(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(true);

        // Query VINTF declaration without starting a lazy HAL at boot.
        mHasNativeHal = ServiceManager.isDeclared(FQ_NAME);

        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)) {
            if (!mHasNativeHal) {
                throw new RuntimeException("FEATURE_CONSUMER_IR present, but no IR HAL declared!");
            }
        } else if (mHasNativeHal) {
            throw new RuntimeException("IR HAL declared, but FEATURE_CONSUMER_IR is not set!");
        }
    }

    @Override
    @RequiresNoPermission
    public boolean hasIrEmitter() {
        return mHasNativeHal;
    }

    private IConsumerIr getHalServiceLocked() {
        if (mAidlService != null && mAidlService.asBinder().isBinderAlive()) {
            return mAidlService;
        }
        mAidlService = IConsumerIr.Stub.asInterface(
                ServiceManager.waitForDeclaredService(FQ_NAME));
        return mAidlService;
    }

    private void scheduleReleaseLocked() {
        mHandler.removeCallbacks(mReleaseHal);
        mHandler.postDelayed(mReleaseHal, IDLE_RELEASE_MS);
    }

    private void throwIfNoIrEmitter() {
        if (!mHasNativeHal) {
            throw new UnsupportedOperationException("IR emitter not available");
        }
    }


    @Override
    @EnforcePermission(TRANSMIT_IR)
    public void transmit(String packageName, int carrierFrequency, int[] pattern) {
        super.transmit_enforcePermission();

        long totalXmitTime = 0;

        for (int slice : pattern) {
            if (slice <= 0) {
                throw new IllegalArgumentException("Non-positive IR slice");
            }
            totalXmitTime += slice;
        }

        if (totalXmitTime > MAX_XMIT_TIME ) {
            throw new IllegalArgumentException("IR pattern too long");
        }

        throwIfNoIrEmitter();

        // Right now there is no mechanism to ensure fair queing of IR requests
        synchronized (mHalLock) {
            for (int attempt = 0; attempt < 2; attempt++) {
                IConsumerIr hal = getHalServiceLocked();
                if (hal == null) {
                    Slog.e(TAG, "IR HAL unavailable for transmit");
                    return;
                }
                try {
                    hal.transmit(carrierFrequency, pattern);
                    scheduleReleaseLocked();
                    return;
                } catch (DeadObjectException e) {
                    mAidlService = null;
                } catch (RemoteException ignore) {
                    mAidlService = null;
                    Slog.e(TAG, "Error transmitting frequency: " + carrierFrequency);
                    return;
                }
            }
        }
    }

    @Override
    @EnforcePermission(TRANSMIT_IR)
    public int[] getCarrierFrequencies() {
        super.getCarrierFrequencies_enforcePermission();

        throwIfNoIrEmitter();

        synchronized (mHalLock) {
            for (int attempt = 0; attempt < 2; attempt++) {
                IConsumerIr hal = getHalServiceLocked();
                if (hal == null) {
                    return null;
                }
                try {
                    ConsumerIrFreqRange[] output = hal.getCarrierFreqs();
                    scheduleReleaseLocked();
                    if (output.length <= 0) {
                        Slog.e(TAG, "Error getting carrier frequencies.");
                    }
                    int[] result = new int[output.length * 2];
                    for (int i = 0; i < output.length; i++) {
                        result[i * 2] = output[i].minHz;
                        result[i * 2 + 1] = output[i].maxHz;
                    }
                    return result;
                } catch (DeadObjectException e) {
                    mAidlService = null;
                } catch (RemoteException ignore) {
                    mAidlService = null;
                    return null;
                }
            }
        }
        return null;
    }
}
