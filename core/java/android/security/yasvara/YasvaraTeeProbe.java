/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.content.Context;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.ECGenParameterSpec;

/**
 * TEE health: can this device currently produce a hardware attestation?
 *
 * <p>Unlike Evo's one-shot latch, health is re-probed on demand: after any attestation
 * failure, and at most every {@link #PROBE_COOLDOWN_MS}. A transient failure at boot
 * therefore recovers by itself, and Laras can force a probe via
 * {@code yasvara_trickystore_tee_reprobe} (Advanced → "Re-probe TEE").
 *
 * <p>The probe key alias ({@link #PROBE_ALIAS}) is excluded from protection in
 * {@link YasvaraTrickyStore} so the probe always sees the raw TEE answer.
 *
 * @hide
 */
public final class YasvaraTeeProbe {
    private static final String TAG = "YasvaraTeeProbe";

    /** Alias the probe uses; never protected, never filtered. */
    public static final String PROBE_ALIAS = "YasvaraTeeProbe";

    static final String KEY_REPROBE = "yasvara_trickystore_tee_reprobe";

    private static final long PROBE_COOLDOWN_MS = 15 * 60 * 1000L;

    private static volatile long sLastProbeMs = 0L;
    private static volatile Boolean sLastResult = null;
    private static volatile long sLastReprobeRequest = 0L;

    private YasvaraTeeProbe() {}

    /**
     * True when the TEE looks broken right now. Probes immediately on first call, after a
     * previous failure, when Laras requests a re-probe, or when the cooldown expired.
     * Never throws.
     */
    public static synchronized boolean isBroken(Context ctx) {
        long now = System.currentTimeMillis();
        long requested = reprobeRequested(ctx);
        boolean forced = requested > sLastReprobeRequest;
        if (forced) sLastReprobeRequest = requested;
        if (!forced && sLastResult != null && !sLastResult
                && now - sLastProbeMs < PROBE_COOLDOWN_MS) {
            return false;
        }
        if (!forced && sLastResult != null && sLastResult
                && now - sLastProbeMs < PROBE_COOLDOWN_MS) {
            return true;
        }
        boolean broken = runProbe();
        sLastResult = broken;
        sLastProbeMs = now;
        return broken;
    }

    /** Last known answer without probing; null when never probed. */
    public static Boolean lastKnown() {
        return sLastResult;
    }

    private static long reprobeRequested(Context ctx) {
        try {
            return Settings.Secure.getLong(
                    ctx.getContentResolver(), KEY_REPROBE, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static boolean runProbe() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    PROBE_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(new byte[16])
                    .build();
            kpg.initialize(spec);
            kpg.generateKeyPair();
            try {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                java.security.cert.Certificate[] chain = ks.getCertificateChain(PROBE_ALIAS);
                boolean ok = chain != null && chain.length > 0
                        && YasvaraAttestation.parse((java.security.cert.X509Certificate) chain[0])
                                != null;
                if (!ok) Log.w(TAG, "probe produced no attestation extension");
                return !ok;
            } finally {
                try {
                    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                    ks.load(null);
                    ks.deleteEntry(PROBE_ALIAS);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "probe failed, TEE considered broken", t);
            return true;
        }
    }
}
