/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/**
 * Talks to {@code YasvaraAttestationService} in system_server, where the keybox private
 * key lives. Cached binder handle with a retry throttle; null when the service is not
 * up (early boot), unreachable (isolated processes), or erroring — callers then use
 * the in-process ladder instead.
 *
 * @hide
 */
public final class YasvaraServiceClient {
    private static final String TAG = "YasvaraServiceClient";
    private static final String SERVICE_NAME = "yasvara_attestation";

    private static final long RETRY_MS = 30 * 1000L;
    private static final int MAX_CHAIN_BYTES = 64 * 1024;

    private static volatile IYasvaraAttestation sService;
    private static volatile long sLastLookupMs = 0L;

    private YasvaraServiceClient() {}

    /** Binder handle, or null when unavailable. Never throws. */
    public static IYasvaraAttestation service() {
        try {
            IYasvaraAttestation s = sService;
            if (s != null) {
                if (s.asBinder().isBinderAlive()) return s;
                sService = null;
            }
            long now = System.currentTimeMillis();
            if (now - sLastLookupMs < RETRY_MS) return null;
            sLastLookupMs = now;
            IBinder b = ServiceManager.getService(SERVICE_NAME);
            if (b == null) return null;
            s = IYasvaraAttestation.Stub.asInterface(b);
            sService = s;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Asks system_server to serve this chain. Returns the served chain, the input when
     * the service declines, or null when the service is unreachable (use fallback).
     */
    public static Certificate[] protect(String pkg, int uid, Certificate[] chain) {
        try {
            IYasvaraAttestation s = service();
            if (s == null) return null;
            String joined = encode(chain);
            if (joined == null) return chain;
            String out = s.protectChain(pkg, uid, joined);
            if (out == null || out.equals(joined)) return chain;
            Certificate[] decoded = decode(out);
            return decoded != null ? decoded : chain;
        } catch (Throwable t) {
            sService = null;
            return null;
        }
    }

    /**
     * Asks system_server to synthesize an attestation chain. Returns the synthesized
     * chain, or null when the service is unreachable or declines (use fallback).
     */
    public static Certificate[] synthesize(String pkg, int uid, byte[] requestData) {
        try {
            IYasvaraAttestation s = service();
            if (s == null) return null;
            String out = s.synthesizeChain(pkg, uid, requestData);
            if (out == null || out.isEmpty()) return null;
            return decode(out);
        } catch (Throwable t) {
            sService = null;
            return null;
        }
    }

    static String encode(Certificate[] chain) {
        try {
            StringBuilder sb = new StringBuilder();
            int total = 0;
            for (Certificate c : chain) {
                byte[] der = c.getEncoded();
                total += der.length;
                if (total > MAX_CHAIN_BYTES) return null;
                if (sb.length() > 0) sb.append('\n');
                sb.append(android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP));
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "encode failed", t);
            return null;
        }
    }

    static Certificate[] decode(String joined) {
        try {
            String[] parts = joined.split("\n");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            java.util.ArrayList<Certificate> out = new java.util.ArrayList<>(parts.length);
            for (String p : parts) {
                String t = p.trim();
                if (t.isEmpty()) continue;
                byte[] der = android.util.Base64.decode(t, android.util.Base64.DEFAULT);
                if (der.length > MAX_CHAIN_BYTES) return null;
                out.add(cf.generateCertificate(new java.io.ByteArrayInputStream(der)));
            }
            return out.isEmpty() ? null : out.toArray(new Certificate[0]);
        } catch (Throwable t) {
            Log.w(TAG, "decode failed", t);
            return null;
        }
    }
}
