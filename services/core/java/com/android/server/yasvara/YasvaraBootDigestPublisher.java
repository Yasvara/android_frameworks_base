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
import android.security.yasvara.YasvaraBootDigest;
import android.security.yasvara.YasvaraIntegrityContract;
import android.util.Slog;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

/**
 * Publishes the canonical immutable per-boot measurement (`ro.yasvara.boot.digest`).
 * Delegates certificate chain signature/challenge/RootOfTrust validation and digest selection
 * to Rust (`YasvaraIntegrityNative`) — zero BouncyCastle and immune to pre-NTP RTC clock skew.
 */
final class YasvaraBootDigestPublisher {
    private static final String TAG = "YasvaraBootDigest";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_MS = 5_000;

    private final Context mContext;
    private final Runnable mOnPublished;
    private final HandlerThread mThread = new HandlerThread(TAG);
    private Handler mHandler;
    private int mAttempts;
    private boolean mRegistered;
    private boolean mStopped;
    private boolean mUserUnlocked;

    private YasvaraBootDigestPublisher(Context context, Runnable onPublished) {
        mContext = context;
        mOnPublished = onPublished;
    }

    static void start(Context context, Runnable onPublished) {
        YasvaraBootDigestPublisher publisher = new YasvaraBootDigestPublisher(context, onPublished);
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
            Slog.w(TAG, "Cannot query unlock state; retaining unlock receiver", e);
        }
        mAttempt.run();
    }

    private final Runnable mAttempt = new Runnable() {
        @Override public void run() {
            if (mStopped) return;
            String existing = SystemProperties.get(YasvaraIntegrityContract.PROP_BOOT_DIGEST, "");
            if (!existing.isEmpty()) {
                if (YasvaraBootDigest.decodeCanonical(existing) == null) {
                    Slog.e(TAG, "Invalid immutable boot digest; leaving hardware attestation intact");
                }
                finish();
                return;
            }
            mAttempts++;
            String bootloader = SystemProperties.get(
                    YasvaraIntegrityContract.PROP_BOOT_VBMETA_DIGEST, "");
            byte[] attested = captureHardwareHash();
            String[] result = YasvaraIntegrityNative.nativeSelectBootDigest(bootloader, attested);
            if (result != null && result.length == 1 && "CONFLICT".equals(result[0])) {
                Slog.e(TAG, "Bootloader and hardware-attested boot hashes disagree; not publishing");
                finish();
                return;
            }
            if (result != null && result.length == 2) {
                String digest = result[0];
                String selectedSource = result[1];
                try {
                    String source = SystemProperties.get(
                            YasvaraIntegrityContract.PROP_BOOT_SOURCE, "");
                    if (source.isEmpty()) {
                        SystemProperties.set(
                                YasvaraIntegrityContract.PROP_BOOT_SOURCE, selectedSource);
                    } else if (!source.equals(selectedSource)) {
                        Slog.e(TAG, "Incomplete boot publication has different provenance; aborting");
                        finish();
                        return;
                    }
                    SystemProperties.set(YasvaraIntegrityContract.PROP_BOOT_DIGEST, digest);
                    Slog.i(TAG, "Published measured boot digest from " + selectedSource);
                    if (mOnPublished != null) {
                        try {
                            mOnPublished.run();
                        } catch (Throwable t) {
                            Slog.w(TAG, "onPublished callback failed", t);
                        }
                    }
                    finish();
                    return;
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Cannot publish boot digest; check property permissions", e);
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
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    YasvaraIntegrityContract.BOOT_DIGEST_PROBE_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .build();
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(spec);
            generator.generateKeyPair();
            Certificate[] chain = store.getCertificateChain(
                    YasvaraIntegrityContract.BOOT_DIGEST_PROBE_ALIAS);
            if (chain == null || chain.length < 2) {
                return null;
            }
            byte[][] derChain = new byte[chain.length][];
            for (int i = 0; i < chain.length; i++) {
                derChain[i] = chain[i].getEncoded();
            }
            return YasvaraIntegrityNative.nativeExtractHardwareBootHash(derChain, challenge);
        } catch (Exception e) {
            Slog.w(TAG, "Hardware boot measurement not available", e);
            return null;
        } finally {
            if (store != null) {
                try {
                    store.deleteEntry(YasvaraIntegrityContract.BOOT_DIGEST_PROBE_ALIAS);
                } catch (Exception e) {
                    Slog.w(TAG, "Cannot delete temporary boot-attestation key", e);
                }
            }
        }
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
