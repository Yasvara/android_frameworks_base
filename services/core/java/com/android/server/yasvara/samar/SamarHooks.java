/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.samar;

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Entry points for the location, GNSS, app-op and telephony code in system_server. Every call is
 * a volatile read and returns at once while Samar has nothing configured.
 */
public final class SamarHooks {
    /** Layer bits, as stored by the policy. */
    public static final int LAYER_RADIO = 1;
    public static final int LAYER_BLE = 1 << 1;
    public static final int LAYER_MEDIA = 1 << 2;
    public static final int LAYER_LAN = 1 << 3;

    private static final int SOURCE_GNSS = 0;
    private static final int SOURCE_NETWORK = 1;
    private static final int SOURCE_FUSED = 2;
    private static final int MAX_SATELLITES = 128;

    /** Location and GNSS components that deliver simulated data. */
    public interface Listener {
        /** A new simulated step is ready. */
        void onSamarTick();

        /** Which clients are overridden may have changed. */
        void onSamarChanged();
    }

    private static final CopyOnWriteArrayList<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static volatile int sState;

    private SamarHooks() {}

    public static void addListener(Listener listener) {
        sListeners.addIfAbsent(listener);
    }

    /** Whether this uid is served simulated locations right now. */
    public static boolean overrides(int uid, boolean emergency) {
        if ((sState & SamarNative.STATE_ACTIVE) == 0) return false;
        return SamarNative.nativeResolve(UserHandle.getAppId(uid), emergency) != 0;
    }

    /**
     * The simulated fix this uid should receive from {@code provider}, or null when it sees the
     * real location. Fixes are shared by every client within a step.
     */
    @Nullable
    public static Location location(int uid, boolean emergency, String provider) {
        if ((sState & SamarNative.STATE_ACTIVE) == 0) return null;
        final int source;
        switch (provider) {
            case LocationManager.NETWORK_PROVIDER:
                source = SOURCE_NETWORK;
                break;
            case LocationManager.FUSED_PROVIDER:
                source = SOURCE_FUSED;
                break;
            default:
                source = SOURCE_GNSS;
        }
        final double[] f = new double[9];
        if (!SamarNative.nativeFix(UserHandle.getAppId(uid), emergency, source, f)) return null;

        final Location l = new Location(
                LocationManager.PASSIVE_PROVIDER.equals(provider) ? LocationManager.GPS_PROVIDER
                        : provider);
        l.setLatitude(f[0]);
        l.setLongitude(f[1]);
        if (!Double.isNaN(f[2])) l.setAltitude(f[2]);
        l.setAccuracy((float) f[3]);
        if (!Double.isNaN(f[4])) l.setVerticalAccuracyMeters((float) f[4]);
        if (!Double.isNaN(f[5])) l.setSpeed((float) f[5]);
        if (!Double.isNaN(f[6])) l.setSpeedAccuracyMetersPerSecond((float) f[6]);
        if (!Double.isNaN(f[7])) l.setBearing((float) f[7]);
        if (!Double.isNaN(f[8])) l.setBearingAccuracyDegrees((float) f[8]);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        l.setElapsedRealtimeUncertaintyNanos(1_000_000);
        if (source == SOURCE_GNSS) {
            final Bundle extras = new Bundle();
            extras.putInt("satellites", usedSatellites(uid, emergency));
            l.setExtras(extras);
        }
        return l;
    }

    private static int usedSatellites(int uid, boolean emergency) {
        final int[] ids = new int[MAX_SATELLITES];
        final float[] values = new float[MAX_SATELLITES * 4];
        final int n = SamarNative.nativeSky(UserHandle.getAppId(uid), emergency,
                SystemClock.elapsedRealtimeNanos(), ids, values);
        int used = 0;
        for (int i = 0; i < n; i++) {
            if (((ids[i] >> 16) & 1) != 0) used++;
        }
        return used;
    }

    /** The simulated satellite view for this uid, or null when it sees the real sky. */
    @Nullable
    public static GnssStatus gnssStatus(int uid) {
        if ((sState & SamarNative.STATE_ACTIVE) == 0) return null;
        final int[] ids = new int[MAX_SATELLITES];
        final float[] values = new float[MAX_SATELLITES * 4];
        final int n = SamarNative.nativeSky(UserHandle.getAppId(uid), false,
                SystemClock.elapsedRealtimeNanos(), ids, values);
        if (n < 0) return null;
        final GnssStatus.Builder b = new GnssStatus.Builder();
        for (int i = 0; i < n; i++) {
            final int id = ids[i];
            final boolean used = ((id >> 16) & 1) != 0;
            b.addSatellite(id >>> 24, id & 0xffff, values[i * 4], values[i * 4 + 1],
                    values[i * 4 + 2], /* hasEphemeris */ values[i * 4] >= 20f,
                    /* hasAlmanac */ true, used,
                    /* hasCarrierFrequency */ true, values[i * 4 + 3],
                    /* hasBasebandCn0DbHz */ true, basebandCn0(values[i * 4]));
        }
        return b.build();
    }

    /** The receiver reports baseband C/N0 a few dB under the antenna value, as this chipset does. */
    private static float basebandCn0(float cn0) {
        return cn0 <= 0f ? 0f : Math.max(0f, cn0 - 3.2f - (cn0 * 7919 % 10) * 0.08f);
    }

    /** Privacy layers in force for this uid. */
    public static int layers(int uid) {
        if ((sState & SamarNative.STATE_LAYERS) == 0) return 0;
        return SamarNative.nativeLayers(UserHandle.getAppId(uid));
    }

    /** Wi-Fi scan results, connected BSSID and cell identity are withheld from this uid. */
    public static boolean hidesRadio(int uid) {
        return (layers(uid) & LAYER_RADIO) != 0;
    }

    /** An app-op that a privacy layer answers with "ignored" for this uid. */
    public static boolean restrictsOp(int code, int uid) {
        if ((sState & SamarNative.STATE_LAYERS) == 0) return false;
        final int layer;
        switch (code) {
            case AppOpsManager.OP_WIFI_SCAN:
                layer = LAYER_RADIO;
                break;
            case AppOpsManager.OP_BLUETOOTH_SCAN:
                layer = LAYER_BLE;
                break;
            case AppOpsManager.OP_ACCESS_MEDIA_LOCATION:
                layer = LAYER_MEDIA;
                break;
            default:
                return false;
        }
        return (layers(uid) & layer) != 0;
    }

    static int state() {
        return sState;
    }

    /** {@code scopeChanged}: which uids are overridden may differ; joystick moves leave it. */
    static void publish(int state, boolean scopeChanged) {
        final int old = sState;
        sState = state;
        if (scopeChanged || ((old ^ state) & SamarNative.STATE_ACTIVE) != 0) {
            for (Listener l : sListeners) l.onSamarChanged();
        }
    }

    static void dispatchTick() {
        for (Listener l : sListeners) l.onSamarTick();
    }
}
