/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.tempel;

/** JNI bindings to libtempel_jni (pure-Rust Tempel NFC engine). */
final class TempelNative {
    static final int KIND_PAYMENT_WALLET = 0;
    static final int KIND_UID_TYPE_A = 1;
    static final int KIND_ISODEP_HCE = 2;
    static final int KIND_NDEF_T4T = 3;
    static final int KIND_FELICA_HCEF = 4;
    static final int KIND_UID_TYPE_B = 5;
    static final int KIND_SHIELD = 6;

    private TempelNative() {}

    static void load() {
        System.loadLibrary("tempel_jni");
    }

    static native boolean nativeLoadVault(byte[] blob);
    static native byte[] nativeSaveVault();
    static native String nativeDump();
    static native String[] nativeListProfiles();

    static native int nativeUpsertProfile(
            int id, int kind, String name, int colorArgb, int iconId,
            int gate, int revertType, int revertSec, boolean showLock,
            String walletPkg, byte[] uid, byte[] atqa, int sak, int ratsTb1,
            byte[] hist, byte[] appData, byte[] sysCode, byte[] pmm, byte[] payload);
    static native boolean nativeSetApduRules(int profileId, String rulesText);
    static native boolean nativeRemoveProfile(int id);
    static native void nativeSetDefaultPayment(int id);
    static native int nativeGetActiveId();
    static native int nativeGetDefaultPaymentId();
    static native byte[] nativeGetPayload(int id);

    static native long nativeActivate(int id, long nowMs);
    static native boolean nativeCheckRevert(long nowMs);
    static native boolean nativeOnTapCompleted();

    static native byte[] nativeBuildActiveListenA();
    static native byte[] nativeBuildActiveListenB();
    static native byte[] nativeBuildResetListenB();
    static native byte[] nativeBuildActiveListenF();
    static native byte[] nativeBuildResetListenF();
    static native byte[] nativeBuildMfc2f45Info(int sector, boolean isKeyB);
    static native byte[] nativeBuildMfc2f45DefaultKeys();

    static native byte[] nativeProcessApdu(byte[] apdu);
    static native byte[] nativeBuildNdef(int kind, String a, String b, String c);

    static native String nativeClassifyTag(
            int techMask, int uidLen, int atqa0, int atqa1, int sak,
            boolean hasNdef, boolean isEMoney, int totalSectors, int readableSectors);
    static native String nativeImportDump(int format, String text, byte[] bin);
    static native String nativeExportProfile(int profileId, int format);
    static native String nativeDecodeEMoney(int issuerCode, byte[] infoResp, byte[] balResp);

    static native byte[] nativeBuildMfcBlock0(byte[] uid, int sak, int atqa0, int atqa1);
    static native boolean nativeValidateMfcAccessBits(int b6, int b7, int b8);
    static native byte[] nativeBuildWriteFrame(int op, int arg1, int arg2, byte[] data);
    static native byte[] nativeBuildNtagNdefPages(int variant, byte[] ndefMsg);
}
