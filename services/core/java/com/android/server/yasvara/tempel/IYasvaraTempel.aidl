/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.tempel;

/**
 * Tempel NFC card vault, profile switcher, HCE APDU dispatcher, hardware 40-key MIFARE
 * batch verifier, and multi-tech physical tag writer frame engine.
 */
interface IYasvaraTempel {
    /**
     * Tab-delimited profile rows:
     * id\tkind\tname\tcolor\ticon\tgate\trevertType\trevertSec\tlock\twalletPkg\tuidHex\tatqaHex\tsakHex\tscHex\tpmmHex\tpayloadLen
     */
    String[] listProfiles();

    int getActiveProfileId();
    int getDefaultPaymentProfileId();
    void setDefaultPaymentProfile(int profileId);

    /** Inserts (id == 0) or updates a profile. Returns assigned profile ID, or 0 on failure. */
    int upsertProfile(
        int id,
        int kind,
        String name,
        int colorArgb,
        int iconId,
        int gate,
        int revertType,
        int revertSec,
        boolean showOnLockscreen,
        String walletPkg,
        in byte[] uid,
        in byte[] atqa,
        int sak,
        int ratsTb1,
        in byte[] histBytes,
        in byte[] appData,
        in byte[] systemCode,
        in byte[] pmm,
        in byte[] payload
    );

    /** Sets scripted ISO-DEP APDU rules ("REQHEX -> RSPHEX" per line) on an existing profile. */
    boolean setApduRules(int profileId, String rulesText);

    boolean removeProfile(int profileId);
    byte[] getProfilePayload(int profileId);

    /** Arms profileId (or 0 to disarm back to default payment wallet). Returns true on NFCC OK. */
    boolean activateProfile(int profileId);

    /** Enables or disables Observe Mode shield directly. */
    boolean setShieldEnabled(boolean enabled);
    boolean isShieldEnabled();

    /** Returns live NFCC capability summary string (state, observeMode, walletPkg). */
    String getCapabilitiesSummary();

    /** Zero-alloc Rust Type-4A NDEF / scripted ISO-DEP HCE responder for TempelHostApduService. */
    byte[] processApdu(in byte[] commandApdu);

    /** Notifies service that an HCE/RF tap completed (triggers AfterTap auto-revert if set). */
    void notifyTapCompleted();

    /** Builds NDEF message bytes: kind 0=URI, 1=Text, 2=Wi-Fi, 3=vCard. */
    byte[] buildNdef(int kind, String a, String b, String c);

    /** Classifies a scanned card: preset\tverdict\tcanEmulate\tcanWrite\tencSectors\ttotalSectors. */
    String classifyTag(
        int techMask,
        int uidLen,
        int atqa0,
        int atqa1,
        int sak,
        boolean hasNdef,
        boolean isEMoney,
        int totalSectors,
        int readableSectors
    );

    /** Imports .mct (0), Flipper .nfc (1), or raw .bin (2). Returns tab-delimited fields or null. */
    String importDump(int format, String text, in byte[] bin);

    /** Exports saved profile to .mct (0) or Flipper .nfc (1). */
    String exportProfile(int profileId, int format);

    /** Decodes Indonesian e-Money read-only APDU response: issuer\tbalanceIdr\tcardNumber. */
    String decodeEMoney(int issuerCode, in byte[] infoResp, in byte[] balanceResp);

    /** Builds spec-compliant 16-byte MIFARE Block 0 (auto BCC for 4B UID). */
    byte[] buildMfcBlock0(in byte[] uid, int sak, int atqa0, int atqa1);

    /** Validates 3-byte MIFARE sector trailer access bits (anti-brick guard). */
    boolean validateMfcAccessBits(int b6, int b7, int b8);

    /**
     * Builds validated physical card writer frame:
     * op 0=Gen3 Block0, 1=Gen3 UID7, 2=Gen4 GetCfg, 3=Gen4 Read, 4=Gen4 Write (access-guarded),
     * 5=Gen4 SetCfg, 6=NTAG WritePage (lock-guarded), 7=NFC-V Read, 8=NFC-V Write,
     * 9=FeliCa Lite-S Read, 10=FeliCa Lite-S Write.
     */
    byte[] buildWriteFrame(int op, int arg1, int arg2, in byte[] data);

    /** Packs NDEF TLV into [page, d0, d1, d2, d3] * N for NTAG/Ultralight writing. */
    byte[] buildNtagNdefPages(int variant, in byte[] ndefMsg);

    /**
     * Runs SN220U hardware 40-key MIFARE batch verifier (NCI 2F 45) for a connected MIFARE tag.
     * Pass customKeys (multiple of 6 bytes, max 240 bytes) or null/empty to test built-in 20 defaults.
     * Returns the 6-byte matching key, or null if not found / unsupported.
     */
    byte[] verifyMfcSectorKeys(int sector, boolean isKeyB, in byte[] customKeys);
}
