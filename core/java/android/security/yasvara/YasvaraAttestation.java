/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;

import java.io.ByteArrayInputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads an Android key-attestation extension (OID 1.3.6.1.4.1.11129.2.1.17) and decides
 * whether the device's own chain is already good enough to leave alone.
 *
 * <p>PASSTHROUGH applies only when ALL hold: every certificate verifies against its
 * issuer, the root is self-signed, RootOfTrust says locked + verified(0), the OS patch
 * level is recent, and the attestation challenge the app asked for is present. Anything
 * else means the ladder must intervene (or leave the chain untouched when it cannot).
 *
 * @hide
 */
public final class YasvaraAttestation {
    private static final String TAG = "YasvaraAttestation";

    /**
     * Fast in-process gate: returns true ONLY when {@code cert} carries an Android attestation
     * extension that has NOT yet been patched to locked + Verified by native keystore2.
     *
     * <p>Non-attested keys (no OID 1.3.6.1.4.1.11129.2.1.17) and chains already patched by
     * native keystore2 return {@code false} immediately with zero Binder IPC overhead.
     */
    public static boolean needsFallbackPatch(Certificate cert) {
        if (!(cert instanceof X509Certificate)) return false;
        Record r = parse((X509Certificate) cert);
        if (r == null) return false;
        return !(Boolean.TRUE.equals(r.deviceLocked)
                && r.verifiedBootState != null
                && r.verifiedBootState == 0
                && !YasvaraBootDigest.isAllZero(r.verifiedBootHash)
                && !YasvaraBootDigest.isAllZero(r.verifiedBootKey));
    }

    static final ASN1ObjectIdentifier ATTESTATION_OID =
            new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");

    // TEE-enforced tag numbers (KeyMint KeyDescription).
    private static final int TAG_OS_VERSION = 705;
    private static final int TAG_OS_PATCH = 706;
    private static final int TAG_ROOT_OF_TRUST = 704;

    /** Parsed view of one attested leaf. Null fields mean "not present / unparsable". */
    public static final class Record {
        public byte[] challenge;
        public byte[] verifiedBootKey;
        public Boolean deviceLocked;
        public Integer verifiedBootState;
        public byte[] verifiedBootHash;
        public Integer osVersion;
        public Integer osPatchLevel;
        /** Index 7 (teeEnforced) of the KeyDescription, for the patcher to reuse. */
        public ASN1Encodable[] keyDescription;
        public ASN1Sequence teeEnforced;
    }

    private YasvaraAttestation() {}

    /** Parses the attestation record of {@code leaf}; null when absent or malformed. */
    public static Record parse(X509Certificate leaf) {
        try {
            byte[] extBytes = leaf.getExtensionValue(ATTESTATION_OID.getId());
            if (extBytes == null) return null;
            X509CertificateHolder holder = new X509CertificateHolder(leaf.getEncoded());
            Extension ext = holder.getExtension(ATTESTATION_OID);
            if (ext == null) return null;
            ASN1Sequence keyDescription =
                    ASN1Sequence.getInstance(ext.getExtnValue().getOctets());
            if (keyDescription.size() < 8) return null;
            Record r = new Record();
            r.keyDescription = keyDescription.toArray();
            r.challenge = octets(keyDescription.getObjectAt(4));
            ASN1Sequence enforced = ASN1Sequence.getInstance(r.keyDescription[7]);
            r.teeEnforced = enforced;
            boolean rootSeen = false;
            for (ASN1Encodable e : enforced) {
                if (!(e instanceof ASN1TaggedObject)) continue;
                ASN1TaggedObject t = (ASN1TaggedObject) e;
                switch (t.getTagNo()) {
                    case TAG_ROOT_OF_TRUST:
                        if (rootSeen || !parseRootOfTrust(t, r)) return null;
                        rootSeen = true;
                        break;
                    case TAG_OS_VERSION:
                        r.osVersion = integer(t);
                        break;
                    case TAG_OS_PATCH:
                        r.osPatchLevel = integer(t);
                        break;
                    default:
                        break;
                }
            }
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "parse failed", t);
            return null;
        }
    }

    private static boolean parseRootOfTrust(ASN1TaggedObject t, Record r) {
        try {
            ASN1Sequence rot = ASN1Sequence.getInstance(t.getBaseObject());
            if (rot.size() < 4 || !(rot.getObjectAt(0) instanceof ASN1OctetString)
                    || !(rot.getObjectAt(1) instanceof ASN1Boolean)
                    || !(rot.getObjectAt(2) instanceof ASN1Enumerated)
                    || !(rot.getObjectAt(3) instanceof ASN1OctetString)) return false;
            int state = ((ASN1Enumerated) rot.getObjectAt(2)).intValueExact();
            byte[] hash = ((ASN1OctetString) rot.getObjectAt(3)).getOctets();
            if (state < 0 || state > 3 || (hash.length != 32 && hash.length != 64)) return false;
            r.verifiedBootKey = ((ASN1OctetString) rot.getObjectAt(0)).getOctets();
            r.deviceLocked = ((ASN1Boolean) rot.getObjectAt(1)).isTrue();
            r.verifiedBootState = state;
            r.verifiedBootHash = hash;
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "root-of-trust parse failed", error);
            return false;
        }
    }

    private static byte[] octets(ASN1Encodable e) {
        try {
            if (e instanceof ASN1OctetString) return ((ASN1OctetString) e).getOctets();
            if (e instanceof ASN1TaggedObject) {
                return octets(((ASN1TaggedObject) e).getBaseObject());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Integer integer(ASN1TaggedObject t) {
        try {
            return integerAt(t.getBaseObject());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer integerAt(ASN1Encodable e) {
        try {
            if (e instanceof com.android.internal.org.bouncycastle.asn1.ASN1Enumerated) {
                return ((com.android.internal.org.bouncycastle.asn1.ASN1Enumerated) e)
                        .getValue().intValue();
            }
            return ASN1Integer.getInstance(e).getValue().intValue();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** True when each certificate verifies against the next and the root is self-signed. */
    public static boolean chainSignaturesValid(List<X509Certificate> chain) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> re =
                    new ArrayList<>(chain.size());
            for (X509Certificate c : chain) {
                re.add((X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(c.getEncoded())));
            }
            for (int i = 0; i + 1 < re.size(); i++) {
                re.get(i).verify(re.get(i + 1).getPublicKey());
            }
            X509Certificate root = re.get(re.size() - 1);
            root.verify(root.getPublicKey());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * PASSTHROUGH gate: the real chain already reports a locked, verified device with a
     * recent patch and intact signatures — rewriting it would only spend keybox lifetime.
     *
     * @param minPatchYyyymm e.g. 202509 for "no older than 2025-09".
     */
    public static boolean alreadyGood(Certificate[] chain, Record record, int minPatchYyyymm) {
        try {
            if (chain == null || chain.length < 2 || record == null) return false;
            if (!Boolean.TRUE.equals(record.deviceLocked)) return false;
            if (record.verifiedBootState == null || record.verifiedBootState != 0) return false;
            if (record.osPatchLevel == null) return false;
            // Patch ints are yyyymm (short) or yyyymmdd (long); compare on the yyyymm prefix.
            int patchYm = record.osPatchLevel > 1000000 ? record.osPatchLevel / 100 : record.osPatchLevel;
            if (patchYm < minPatchYyyymm) return false;
            List<X509Certificate> certs = new ArrayList<>(chain.length);
            for (Certificate c : chain) {
                if (!(c instanceof X509Certificate)) return false;
                certs.add((X509Certificate) c);
            }
            return chainSignaturesValid(certs);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Byte-compare helper that tolerates nulls. */
    public static boolean sameBytes(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    /** DEROctetString factory for the patcher (keeps BC imports in one place). */
    public static DEROctetString octetString(byte[] data) {
        return new DEROctetString(data == null ? new byte[0] : data);
    }
}
