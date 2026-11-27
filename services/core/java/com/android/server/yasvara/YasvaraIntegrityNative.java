/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara;

/**
 * Razor-thin JNI bridge to `libyasvara_integrity_jni.so` (loaded once inside `system_server`).
 *
 * <p>All keybox XML/PEM/chain/SPKI/CBOR validation, PIF normalization, patch-override resolution,
 * and hardware boot-hash extraction run in shared Rust (`yasvara_integrity_core` +
 * `yasvara_integrity_attest`) — guaranteed byte-for-byte parity with `keystore2` and `libc.so`.
 *
 * @hide
 */
final class YasvaraIntegrityNative {
    static {
        System.loadLibrary("yasvara_integrity_jni");
    }

    private YasvaraIntegrityNative() {}

    static native String nativeInspectKeybox(String rawXml, boolean requirePrivateKey);

    static native String[] nativeNormalizePif(String rawPif, String platformRelease);

    static native String nativeGlobalPatchOverride(String rawJson);

    static native byte[] nativeExtractHardwareBootHash(byte[][] chainDer, byte[] expectedChallenge);

    static native String[] nativeSelectBootDigest(String bootloaderRaw, byte[] attestedHash);

    static native int nativeInferVersionFromBuildId(String buildId);

    static native String nativeInferRelease(
            String fpRelease, String buildId, String platformRelease);

    static native byte[] nativeCompilePolicySnapshot(
            boolean trickyEnabled,
            boolean pifEnabled,
            boolean akarYielded,
            long generation,
            String rawPif,
            String platformRelease,
            String rkpManufacturer,
            String[] targets,
            String rawPatchOverrideJson,
            String rawAppSpoofMapJson);

    static native String[] nativePackRkpExemptSlots(String rawAppSpoofMapJson);
}
