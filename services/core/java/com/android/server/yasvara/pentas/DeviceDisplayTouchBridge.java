/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-FileCopyrightText: Paranoid Android
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.pentas;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * In-process system_server bridge for vendor display color mode, single-tap-to-wake,
 * and touch panel orientation relay. Replaces the persistent XiaomiParts APK and
 * Pentas's TouchOrientationRelay with zero extra ART process overhead.
 *
 * Every hardware hook probes availability first and silently no-ops on devices
 * that do not ship the corresponding Xiaomi HAL or touch sysfs node.
 */
final class DeviceDisplayTouchBridge {

    private static final String TAG = "YasvaraDisplayTouch";

    // vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature
    private static final String DISPLAY_FEATURE_SERVICE =
            "vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature/default";
    private static final String DISPLAY_FEATURE_DESC =
            "vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature";
    private static final int TRANSACT_SET_FEATURE = IBinder.FIRST_CALL_TRANSACTION + 6; // 7
    private static final int EXPERT_MODE = 26;
    private static final int EXPERT_VALUE = 0;
    private static final int EXPERT_COOKIE = 10;
    private static final int DEFAULT_COLOR_MODE =
            SystemProperties.getInt("persist.sys.sf.native_mode", 0);

    // Single-tap-to-wake
    private static final String KEY_SINGLE_TAP_TO_WAKE = "single_tap_to_wake";
    private static final String NODE_SINGLE_TAP_TO_WAKE =
            "/sys/class/touch/touch_dev/single_tap_to_wake";

    // vendor.xiaomi.hw.touchfeature.ITouchFeature
    private static final String TOUCH_FEATURE_SERVICE =
            "vendor.xiaomi.hw.touchfeature.ITouchFeature/default";
    private static final String TOUCH_FEATURE_DESC =
            "vendor.xiaomi.hw.touchfeature.ITouchFeature";
    private static final int TRANSACT_SET_TOUCH_MODE = 9;
    private static final int MODE_PANEL_ORIENTATION = 8;

    private final Context mContext;
    private final Handler mHandler;
    private final AmbientDisplayConfiguration mAmbientConfig;

    private boolean mHasDisplayFeature;
    private boolean mHasWakeGesture;
    private boolean mHasTouchFeature;

    @Nullable private volatile IBinder mDisplayFeatureBinder;
    @Nullable private volatile IBinder mTouchFeatureBinder;
    private boolean mIsDozing;
    private int mLastRotation = -1;

    private final Runnable mRestoreColorRunnable = this::applyCurrentColorMode;

    private final IServiceCallback mDisplayFeatureRegCb = new IServiceCallback.Stub() {
        @Override
        public void onRegistration(String name, IBinder binder) {
            if (binder == null) return;
            mHandler.post(() -> {
                if (!binder.isBinderAlive()) return;
                mDisplayFeatureBinder = Binder.allowBlocking(binder);
                linkDeath(binder, true);
                applyCurrentColorMode();
            });
        }
    };

    private final IServiceCallback mTouchFeatureRegCb = new IServiceCallback.Stub() {
        @Override
        public void onRegistration(String name, IBinder binder) {
            if (binder == null) return;
            mHandler.post(() -> {
                if (!binder.isBinderAlive()) return;
                mTouchFeatureBinder = Binder.allowBlocking(binder);
                linkDeath(binder, false);
                pushTouchOrientation(true);
            });
        }
    };

    DeviceDisplayTouchBridge(@NonNull Context context, @NonNull Handler handler) {
        mContext = context;
        mHandler = handler;
        mAmbientConfig = new AmbientDisplayConfiguration(context);
    }

    /** Called once on {@code mHandler} during PentasService startup. */
    void start() {
        final ContentResolver cr = mContext.getContentResolver();

        // 1. Display color mode (IDisplayFeature AIDL)
        mHasDisplayFeature = ServiceManager.isDeclared(DISPLAY_FEATURE_SERVICE)
                || ServiceManager.checkService(DISPLAY_FEATURE_SERVICE) != null;
        if (mHasDisplayFeature) {
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.DISPLAY_COLOR_MODE),
                    false,
                    new ContentObserver(mHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            applyCurrentColorMode();
                        }
                    },
                    UserHandle.USER_ALL);
            try {
                ServiceManager.registerForNotifications(
                        DISPLAY_FEATURE_SERVICE, mDisplayFeatureRegCb);
            } catch (Exception e) {
                Slog.w(TAG, "registerForNotifications(IDisplayFeature) failed", e);
            }
            applyCurrentColorMode();
        }

        // 2. Single-tap-to-wake (touch driver sysfs node)
        mHasWakeGesture = new File(NODE_SINGLE_TAP_TO_WAKE).exists();
        if (mHasWakeGesture) {
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(KEY_SINGLE_TAP_TO_WAKE),
                    false,
                    new ContentObserver(mHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            applySingleTapToWake();
                        }
                    },
                    UserHandle.USER_ALL);
            applySingleTapToWake();
        }

        // 3. Touch panel orientation relay (ITouchFeature AIDL)
        mHasTouchFeature = ServiceManager.isDeclared(TOUCH_FEATURE_SERVICE)
                || ServiceManager.checkService(TOUCH_FEATURE_SERVICE) != null;
        if (mHasTouchFeature) {
            try {
                ServiceManager.registerForNotifications(
                        TOUCH_FEATURE_SERVICE, mTouchFeatureRegCb);
            } catch (Exception e) {
                Slog.w(TAG, "registerForNotifications(ITouchFeature) failed", e);
            }
            pushTouchOrientation(true);
        }
    }

    /** Called on {@code mHandler} on ACTION_SCREEN_ON / ACTION_SCREEN_OFF. */
    void onScreenStateChanged(boolean screenOn) {
        if (!mHasDisplayFeature) return;
        if (screenOn) {
            if (mIsDozing) {
                mIsDozing = false;
                mHandler.removeCallbacks(mRestoreColorRunnable);
                mHandler.postDelayed(mRestoreColorRunnable, 100L);
            }
        } else {
            final int user = ActivityManager.getCurrentUser();
            if (!mAmbientConfig.alwaysOnEnabled(user)) {
                mIsDozing = false;
                return;
            }
            // Standard mode in AOD avoids illuminating black OLED pixels.
            mIsDozing = true;
            mHandler.removeCallbacks(mRestoreColorRunnable);
            setDisplayFeature(2, 2, 255); // STANDARD
        }
    }

    /** Called on {@code mHandler} from PentasService's DisplayListener. */
    void onDisplayChanged() {
        if (mHasTouchFeature) {
            pushTouchOrientation(false);
        }
    }

    /** Called on {@code mHandler} on ACTION_USER_SWITCHED / ACTION_USER_UNLOCKED. */
    void onUserChanged() {
        if (mHasDisplayFeature) applyCurrentColorMode();
        if (mHasWakeGesture) applySingleTapToWake();
    }

    // ── Display Color Mode ──────────────────────────────────────────────────

    private void applyCurrentColorMode() {
        if (!mHasDisplayFeature || mIsDozing) return;
        final int user = ActivityManager.getCurrentUser();
        final int colorMode = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.DISPLAY_COLOR_MODE,
                DEFAULT_COLOR_MODE,
                user);
        switch (colorMode) {
            case 258: // VIVID
                setDisplayFeature(0, 2, 255);
                break;
            case 256: // SATURATED
                setDisplayFeature(1, 2, 255);
                break;
            case 257: // STANDARD
                setDisplayFeature(2, 2, 255);
                break;
            case 269: // ORIGINAL (expert)
                setDisplayFeature(26, 1, 0);
                setDisplayFeature(EXPERT_MODE, EXPERT_VALUE, EXPERT_COOKIE);
                break;
            case 268: // P3 (expert)
                setDisplayFeature(26, 2, 0);
                setDisplayFeature(EXPERT_MODE, EXPERT_VALUE, EXPERT_COOKIE);
                break;
            case 267: // SRGB (expert)
                setDisplayFeature(26, 3, 0);
                setDisplayFeature(EXPERT_MODE, EXPERT_VALUE, EXPERT_COOKIE);
                break;
            default:
                // Unknown or stock AOSP mode id — leave vendor feature untouched.
                break;
        }
    }

    @Nullable
    private IBinder getDisplayFeatureBinder() {
        IBinder b = mDisplayFeatureBinder;
        if (b != null && b.isBinderAlive()) return b;
        b = ServiceManager.checkService(DISPLAY_FEATURE_SERVICE);
        if (b == null) {
            mDisplayFeatureBinder = null;
            return null;
        }
        b = Binder.allowBlocking(b);
        mDisplayFeatureBinder = b;
        linkDeath(b, true);
        return b;
    }

    private void setDisplayFeature(int mode, int value, int cookie) {
        final IBinder binder = getDisplayFeatureBinder();
        if (binder == null) return;
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DISPLAY_FEATURE_DESC);
            data.writeInt(0); // displayId
            data.writeInt(mode);
            data.writeInt(value);
            data.writeInt(cookie);
            binder.transact(TRANSACT_SET_FEATURE, data, reply, 0);
            reply.readException();
        } catch (RemoteException e) {
            mDisplayFeatureBinder = null;
            Slog.w(TAG, "IDisplayFeature.setFeature(" + mode + "," + value + "," + cookie
                    + ") failed", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ── Single-Tap-to-Wake ──────────────────────────────────────────────────

    private void applySingleTapToWake() {
        final int user = ActivityManager.getCurrentUser();
        final boolean enabled = Settings.Secure.getIntForUser(
                mContext.getContentResolver(), KEY_SINGLE_TAP_TO_WAKE, 0, user) != 0;
        try (FileOutputStream fos = new FileOutputStream(NODE_SINGLE_TAP_TO_WAKE)) {
            fos.write((enabled ? "1" : "0").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write " + NODE_SINGLE_TAP_TO_WAKE, e);
        }
    }

    // ── Touch Panel Orientation Relay ───────────────────────────────────────

    @Nullable
    private IBinder getTouchFeatureBinder() {
        IBinder b = mTouchFeatureBinder;
        if (b != null && b.isBinderAlive()) return b;
        b = ServiceManager.checkService(TOUCH_FEATURE_SERVICE);
        if (b == null) {
            mTouchFeatureBinder = null;
            return null;
        }
        b = Binder.allowBlocking(b);
        mTouchFeatureBinder = b;
        linkDeath(b, false);
        return b;
    }

    private void pushTouchOrientation(boolean force) {
        final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        final Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
        if (d == null) return;
        final int rotation = d.getRotation();
        if (!force && rotation == mLastRotation) return;

        final IBinder binder = getTouchFeatureBinder();
        if (binder == null) return;

        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOUCH_FEATURE_DESC);
            data.writeInt(0); // primary display
            data.writeInt(MODE_PANEL_ORIENTATION);
            data.writeInt(rotation);
            binder.transact(TRANSACT_SET_TOUCH_MODE, data, reply, 0);
            reply.readException();
            final int rc = reply.readInt();
            if (rc == 0) {
                mLastRotation = rotation;
            } else {
                Slog.w(TAG, "ITouchFeature orientation=" + rotation + " failed rc=" + rc);
            }
        } catch (RemoteException e) {
            mTouchFeatureBinder = null;
            Slog.w(TAG, "ITouchFeature orientation transact failed", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void linkDeath(@NonNull IBinder binder, boolean isDisplay) {
        try {
            binder.linkToDeath(() -> mHandler.post(() -> {
                if (isDisplay) {
                    if (mDisplayFeatureBinder == binder) mDisplayFeatureBinder = null;
                } else {
                    if (mTouchFeatureBinder == binder) {
                        mTouchFeatureBinder = null;
                        mLastRotation = -1;
                    }
                }
            }), 0);
        } catch (RemoteException ignored) {
        }
    }
}
