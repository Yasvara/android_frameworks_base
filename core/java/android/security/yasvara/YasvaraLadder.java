/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Minimal-intervention ladder. For a Protect-intent package holding a real chain:
 *
 * <pre>
 *   real chain already locked+verified+recent? ──yes──► PASSTHROUGH (touch nothing)
 *                    │no
 *   TEE attestation matches canonical boot digest? ──yes──► PATCH (keep real root state)
 *                    │no
 *   SYNTHESIZE ── deferred to Phase 4 (grus TEE attests fine) ──► ORIGINAL + event
 *   any step throws / self-verify fails ──► ORIGINAL + event
 * </pre>
 *
 * <p>The technique is picked from device state on every call; the only per-app input is
 * intent (Protect vs Real). Advanced force-technique ({@code yasvara_trickystore_force})
 * exists for debugging only: "passthrough", "patch", "synthesize".
 *
 * @hide
 */
public final class YasvaraLadder {
    private static final String TAG = "YasvaraLadder";

    static final String KEY_KEYBOX = "yasvara_trickystore_keybox";
    static final String KEY_FORCE = "yasvara_trickystore_force";

    private YasvaraLadder() {}

    /** Runs the ladder; always returns a chain (rewritten or the original). Never throws. */
    public static Certificate[] serve(Context ctx, String pkg, Certificate[] realChain) {
        return serveWithKeyboxes(ctx, pkg, realChain, loadKeyboxes(ctx));
    }

    /**
     * Same ladder with an explicit keybox set. The system service uses this with the
     * keybox from its private file; the in-process path uses {@link #serve} (Secure).
     */
    public static Certificate[] serveWithKeyboxes(Context ctx, String pkg,
            Certificate[] realChain, List<YasvaraKeyboxProvider.Keybox> keyboxes) {
        try {
            if (ctx == null || realChain == null || realChain.length == 0) return realChain;
            if (keyboxes == null || keyboxes.isEmpty()) {
                YasvaraTelemetry.report(ctx, pkg,
                        YasvaraTelemetry.RUNG_ORIGINAL, "no-keybox");
                return realChain;
            }
            if (!(realChain[0] instanceof X509Certificate)) {
                YasvaraTelemetry.report(ctx, pkg,
                        YasvaraTelemetry.RUNG_FAILED, "non-x509-leaf");
                return realChain;
            }
            X509Certificate leaf = (X509Certificate) realChain[0];

            YasvaraKeyboxProvider.Keybox selected = selectKeybox(leaf, keyboxes);
            if (selected != null
                    && YasvaraLeafPatcher.issuedBy(realChain, selected.leaf())) {
                YasvaraTelemetry.report(ctx, pkg,
                        YasvaraTelemetry.RUNG_PASSTHROUGH, "already-patched");
                return realChain;
            }

            YasvaraAttestation.Record record = YasvaraAttestation.parse(leaf);
            if (record == null) {
                // No parseable attestation: is the TEE even answering?
                boolean broken = YasvaraTeeProbe.isBroken(ctx);
                YasvaraTelemetry.report(ctx, pkg, broken
                        ? YasvaraTelemetry.RUNG_FAILED : YasvaraTelemetry.RUNG_ORIGINAL,
                        broken ? "tee-broken" : "no-attestation");
                return realChain;
            }

            byte[] bootHash = YasvaraIdentity.bootHash();
            if (bootHash == null
                    || (!YasvaraBootDigest.isAllZero(record.verifiedBootHash)
                            && !YasvaraAttestation.sameBytes(bootHash, record.verifiedBootHash))) {
                YasvaraTelemetry.report(ctx, pkg, YasvaraTelemetry.RUNG_ORIGINAL,
                        bootHash == null ? "boot-digest-unavailable" : "boot-digest-mismatch");
                return realChain;
            }

            String force = forceTechnique(ctx);

            String patchDate = YasvaraIdentity.patchDateFor(ctx, pkg);
            int minYm = YasvaraIdentity.minRecentPatchYyyymm();
            if (force == null || "passthrough".equals(force)) {
                if (YasvaraAttestation.alreadyGood(realChain, record, minYm)) {
                    YasvaraTelemetry.report(ctx, pkg,
                            YasvaraTelemetry.RUNG_PASSTHROUGH, "");
                    return realChain;
                }
                if ("passthrough".equals(force)) {
                    YasvaraTelemetry.report(ctx, pkg,
                            YasvaraTelemetry.RUNG_FAILED, "forced-passthrough-miss");
                    return realChain;
                }
            }
            if ("synthesize".equals(force)) {
                YasvaraTelemetry.report(ctx, pkg,
                        YasvaraTelemetry.RUNG_FAILED, "synthesize-unavailable");
                return realChain;
            }

            Certificate[] patched = patch(ctx, pkg, leaf, record, selected, patchDate,
                    bootHash);
            if (patched != null) {
                YasvaraTelemetry.report(ctx, pkg, YasvaraTelemetry.RUNG_PATCHED, "");
                return patched;
            }
            YasvaraTelemetry.report(ctx, pkg,
                    YasvaraTelemetry.RUNG_FAILED, "patch-failed");
            return realChain;
        } catch (Throwable t) {
            Log.w(TAG, "serve failed", t);
            try {
                YasvaraTelemetry.report(ctx, pkg, YasvaraTelemetry.RUNG_FAILED, "exception");
            } catch (Throwable ignored) {
            }
            return realChain;
        }
    }

    private static Certificate[] patch(Context ctx, String pkg, X509Certificate leaf,
            YasvaraAttestation.Record record, YasvaraKeyboxProvider.Keybox selected,
            String patchDate, byte[] bootHash) {
        if (selected == null) return null;
        PrivateKey priv = YasvaraKeyMaterial.toPrivateKey(
                selected.privateKeyPem, selected.algorithm);
        if (priv == null) {
            Log.w(TAG, "no usable private key for " + selected.algorithm);
            return null;
        }
        int osPatch = YasvaraIdentity.patchYyyymm(patchDate);
        int longPatch = YasvaraIdentity.patchYyyymmdd(patchDate);
        if (osPatch <= 0 || longPatch <= 0) return null;
        YasvaraLeafPatcher.Request req = new YasvaraLeafPatcher.Request();
        req.realLeaf = leaf;
        req.record = record;
        req.keyboxPrivateKey = priv;
        req.keyboxChain = selected.chain;
        req.keyboxIssuer = selected.issuerName();
        req.osVersion = osVersionInt(ctx, pkg);
        req.osPatch = osPatch;
        // KeyMint vendor/boot patch levels use the full YYYYMMDD date.
        req.vendorPatch = longPatch;
        req.bootPatch = longPatch;
        req.bootHash = bootHash;
        req.bootKey = YasvaraBootDigest.isAllZero(record.verifiedBootKey)
                ? YasvaraIdentity.resolveBootKey(bootHash) : null;
        // Attestation-ID story for the patched leaf (PIF profile for GMS/Play
        // Store when PIF is enabled; live-Build fallback for everyone else).
        req.attestIdOverrides = attestIdsFor(ctx, pkg, selected);
        return YasvaraLeafPatcher.patch(req);
    }

    /**
     * Attestation-ID story for the patched leaf. GMS / Play Store get the PIF
     * profile when PIF is enabled (their Build is PIF-spoofed, so the leaf must agree);
     * non-collector targets align ATTESTATION_ID_MANUFACTURER with the RKP keybox when
     * present, and otherwise fall back to live Build fields in the patcher.
     */
    static java.util.Map<Integer, String> attestIdsFor(Context ctx, String pkg) {
        return attestIdsFor(ctx, pkg, null);
    }

    static java.util.Map<Integer, String> attestIdsFor(
            Context ctx, String pkg, YasvaraKeyboxProvider.Keybox selected) {
        try {
            if (!YasvaraSpoofEngine.isCollectorPackage(pkg)) {
                String rkpMfg = (selected != null && !TextUtils.isEmpty(selected.rkpManufacturer))
                        ? selected.rkpManufacturer
                        : YasvaraSpoofEngine.getRkpManufacturer(ctx);
                if (!TextUtils.isEmpty(rkpMfg)) {
                    return java.util.Collections.singletonMap(716, rkpMfg);
                }
                return null;
            }
            if (!YasvaraSpoofEngine.isPifEnabled(ctx)) {
                return null;
            }
            java.util.Map<String, String> pif = YasvaraSpoofEngine.getPifConfig(ctx);
            if (pif == null || pif.isEmpty()) return null;
            java.util.Map<Integer, String> out = new java.util.HashMap<>();
            putId(out, 710, pif.get("BRAND"));
            putId(out, 711, pif.get("DEVICE"));
            putId(out, 712, pif.get("PRODUCT"));
            putId(out, 716, pif.get("MANUFACTURER"));
            putId(out, 717, pif.get("MODEL"));
            return out.isEmpty() ? null : out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void putId(java.util.Map<Integer, String> out, int tag, String v) {
        if (v != null && !v.isEmpty()) out.put(tag, v);
    }

    /** Prefer a keybox matching the leaf's key algorithm; otherwise use the first. */
    static YasvaraKeyboxProvider.Keybox selectKeybox(X509Certificate leaf,
            List<YasvaraKeyboxProvider.Keybox> keyboxes) {
        if (keyboxes == null || keyboxes.isEmpty()) return null;
        boolean wantRsa = false;
        try {
            wantRsa = "RSA".equalsIgnoreCase(leaf.getPublicKey().getAlgorithm());
        } catch (Throwable ignored) {
        }
        for (YasvaraKeyboxProvider.Keybox kb : keyboxes) {
            if ("rsa".equalsIgnoreCase(kb.algorithm) == wantRsa) return kb;
        }
        return keyboxes.get(0);
    }

    static int osVersionInt(Context ctx, String pkg) {
        try {
            if (YasvaraSpoofEngine.isCollectorPackage(pkg)
                    && YasvaraSpoofEngine.isPifEnabled(ctx)) {
                java.util.Map<String, String> pif = YasvaraSpoofEngine.getPifConfig(ctx);
                int pifOsVer = YasvaraSpoofEngine.resolvePifOsVersion(pif);
                if (pifOsVer >= 0) return pifOsVer;
            }
        } catch (Throwable ignored) {
        }
        Integer hostRelVer = YasvaraSpoofEngine.parseVersionNumber(android.os.Build.VERSION.RELEASE);
        if (hostRelVer != null) return hostRelVer;
        switch (android.os.Build.VERSION.SDK_INT) {
            case android.os.Build.VERSION_CODES.Q: return 100000;
            case android.os.Build.VERSION_CODES.R: return 110000;
            case android.os.Build.VERSION_CODES.S: return 120000;
            case 32: return 120100; // S_V2
            case android.os.Build.VERSION_CODES.TIRAMISU: return 130000;
            case android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE: return 140000;
            case 35: return 150000; // VANILLA_ICE_CREAM
            case 36: return 160000; // Android 16
            case 37: return 170000; // Android 17
            default: return 0;
        }
    }

    static List<YasvaraKeyboxProvider.Keybox> loadKeyboxes(Context ctx) {
        try {
            String raw = Settings.Secure.getString(ctx.getContentResolver(), KEY_KEYBOX);
            if (TextUtils.isEmpty(raw)) return java.util.Collections.emptyList();
            YasvaraKeyboxProvider.ParseResult result = YasvaraKeyboxProvider.parse(raw);
            if (!result.isValid()) return java.util.Collections.emptyList();
            return result.keyboxes;
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    private static String forceTechnique(Context ctx) {
        try {
            String v = Settings.Secure.getString(ctx.getContentResolver(), KEY_FORCE);
            if ("passthrough".equals(v) || "patch".equals(v) || "synthesize".equals(v)) return v;
        } catch (Throwable ignored) {
        }
        return null;
    }
}
