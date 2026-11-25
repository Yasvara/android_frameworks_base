/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.yasvara.YasvaraAttestation;
import android.security.yasvara.YasvaraBootDigest;
import android.util.Slog;

import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Publishes a nonpersistent boot measurement; never runs on the system-server main thread. */
final class YasvaraBootDigestPublisher {
    private static final String TAG = "YasvaraBootDigest";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_MS = 5_000;

    private final Context mContext;
    private final HandlerThread mThread = new HandlerThread(TAG);
    private Handler mHandler;
    private int mAttempts;
    private boolean mRegistered;
    private boolean mStopped;
    private boolean mUserUnlocked;

    private YasvaraBootDigestPublisher(Context context) {
        mContext = context;
    }

    /** Call only after ActivityManager/SettingsProvider are available. */
    static void start(Context context) {
        YasvaraBootDigestPublisher publisher = new YasvaraBootDigestPublisher(context);
        publisher.mThread.start();
        publisher.mHandler = new Handler(publisher.mThread.getLooper());
        publisher.mHandler.post(publisher::startOnWorker);
    }

    private final BroadcastReceiver mUnlockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (mStopped || intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                    UserHandle.USER_NULL) != UserHandle.USER_SYSTEM) return;
            mUserUnlocked = true;
            mAttempts = 0;
            mHandler.removeCallbacks(mAttempt);
            mHandler.post(mAttempt);
        }
    };

    private void startOnWorker() {
        try {
            mContext.registerReceiver(mUnlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    null, mHandler, Context.RECEIVER_NOT_EXPORTED);
            mRegistered = true;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Cannot register unlock retry", e);
        }
        try {
            UserManager users = mContext.getSystemService(UserManager.class);
            mUserUnlocked = users != null && users.isUserUnlocked(UserHandle.SYSTEM);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Cannot query unlock state; retaining the unlock receiver", e);
        }
        mAttempt.run();
    }

    private final Runnable mAttempt = new Runnable() {
        @Override public void run() {
            if (mStopped) return;
            String existing = SystemProperties.get(YasvaraBootDigest.PROPERTY, "");
            if (!existing.isEmpty()) {
                if (YasvaraBootDigest.decodeCanonical(existing) == null) {
                    Slog.e(TAG, "Invalid immutable boot digest; leaving hardware attestation intact");
                }
                finish();
                return;
            }
            mAttempts++;
            String bootloader = SystemProperties.get("ro.boot.vbmeta.digest", "");
            byte[] attested = captureHardwareHash();
            YasvaraBootDigest.Selection selection = YasvaraBootDigest.select(bootloader, attested);
            if (selection == null && YasvaraBootDigest.normalize(bootloader) != null
                    && YasvaraBootDigest.encode(attested) != null) {
                Slog.e(TAG, "Bootloader and hardware-attested boot hashes disagree; not publishing");
                finish();
                return;
            }
            if (selection != null) {
                try {
                    // Publish metadata first; the immutable digest is the readiness marker.
                    String source = SystemProperties.get(YasvaraBootDigest.SOURCE_PROPERTY, "");
                    if (source.isEmpty()) {
                        SystemProperties.set(YasvaraBootDigest.SOURCE_PROPERTY, selection.source);
                    } else if (!source.equals(selection.source)) {
                        Slog.e(TAG, "Incomplete boot publication has different provenance; not publishing");
                        finish();
                        return;
                    }
                    SystemProperties.set(YasvaraBootDigest.PROPERTY, selection.digest);
                    Slog.i(TAG, "Published measured boot digest from " + selection.source);
                    finish();
                    return;
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Cannot publish boot digest; check property context/permissions", e);
                }
            }
            if (mAttempts < MAX_ATTEMPTS) {
                mHandler.postDelayed(this, RETRY_MS);
            } else if (!mRegistered || mUserUnlocked) {
                Slog.w(TAG, "Boot measurement unavailable; preserving original certificates");
                finish();
            } else {
                Slog.w(TAG, "Boot measurement unavailable before unlock; waiting for user 0");
            }
        }
    };

    private byte[] captureHardwareHash() {
        KeyStore store = null;
        try {
            store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(YasvaraBootDigest.PROBE_ALIAS,
                    KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .build();
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(spec);
            generator.generateKeyPair();
            Certificate[] chain = store.getCertificateChain(YasvaraBootDigest.PROBE_ALIAS);
            if (chain == null || chain.length < 2 || !(chain[0] instanceof X509Certificate)) {
                return null;
            }
            YasvaraAttestation.Record record = YasvaraAttestation.parse((X509Certificate) chain[0]);
            if (record == null || !Arrays.equals(challenge, record.challenge)
                    || !hardwareLevel(record, 1) || !hardwareLevel(record, 3)
                    || record.deviceLocked == null || record.verifiedBootState == null
                    || record.verifiedBootState < 0 || record.verifiedBootState > 3) return null;
            List<X509Certificate> certificates = new ArrayList<>(chain.length);
            for (Certificate certificate : chain) {
                if (!(certificate instanceof X509Certificate)) return null;
                X509Certificate x509 = (X509Certificate) certificate;
                x509.checkValidity();
                certificates.add(x509);
            }
            if (!YasvaraAttestation.chainSignaturesValid(certificates)) return null;
            return YasvaraBootDigest.encode(record.verifiedBootHash) == null
                    ? null : record.verifiedBootHash.clone();
        } catch (Exception e) {
            Slog.w(TAG, "Hardware boot measurement not available", e);
            return null;
        } finally {
            if (store != null) {
                try {
                    store.deleteEntry(YasvaraBootDigest.PROBE_ALIAS);
                } catch (Exception e) {
                    Slog.w(TAG, "Cannot delete temporary boot-attestation key", e);
                }
            }
        }
    }

    private static boolean hardwareLevel(YasvaraAttestation.Record record, int index) {
        int level = ASN1Enumerated.getInstance(record.keyDescription[index]).intValueExact();
        return level == 1 || level == 2;
    }

    private void finish() {
        mStopped = true;
        mHandler.removeCallbacks(mAttempt);
        if (mRegistered) {
            try {
                mContext.unregisterReceiver(mUnlockReceiver);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Cannot unregister boot-measurement receiver", e);
            }
            mRegistered = false;
        }
        mThread.quitSafely();
    }
}
