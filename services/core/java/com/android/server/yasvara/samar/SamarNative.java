/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.samar;

/** Bindings to libsamar_jni; the policy and simulation live there. */
final class SamarNative {
    static final int STATE_ACTIVE = 1;
    static final int STATE_MOVING = 1 << 1;
    static final int STATE_LAYERS = 1 << 2;
    static final int STATE_GLOBAL_ON = 1 << 3;

    private SamarNative() {}

    static void load() {
        System.loadLibrary("samar_jni");
    }

    static native boolean nativeDecode(String text);
    static native String nativeEncode();
    static native String nativeDump();

    static native void nativeBind(String pkg, int appId);
    static native void nativeUnbind(String pkg);
    static native void nativeSetExempt(int appId, boolean exempt);

    static native int nativeResolve(int appId, boolean emergency);
    static native boolean nativeFix(int appId, boolean emergency, int source, double[] out);
    static native int nativeSky(int appId, boolean emergency, long nowNs, int[] ids, float[] values);
    static native int nativeLayers(int appId);

    static native void nativeStep(long nowNs);
    static native int nativeState();

    static native void nativeSetGlobalOn(boolean on);
    static native double nativePlace(String target, double lat, double lng, long nowNs);
    static native void nativeSetAltitude(String target, double altitude);
    static native void nativeSetJoystick(String target, float x, float y);
    static native void nativeSetSpeed(String target, int preset);
    static native double[] nativePosition(String target);

    static native int nativeMode(String pkg);
    static native void nativeSetMode(String pkg, int mode);
    static native void nativeSetLayers(String target, int mask, int value);
    static native long nativeLayerOverride(String target);
    static native String[] nativeConfiguredApps();

    static native boolean nativeAddPlace(String name, double lat, double lng);
    static native boolean nativeRemovePlace(int index);
    static native String[] nativePlaceNames();
    static native double[] nativePlaceCoords();

    static native double[] nativeParse(String text, double refLat, double refLng);
}
