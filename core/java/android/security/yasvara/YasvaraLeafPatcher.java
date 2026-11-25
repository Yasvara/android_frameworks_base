/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.os.Build;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.ASN1TaggedObject;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.DERUTF8String;
import com.android.internal.org.bouncycastle.asn1.x500.X500Name;
import com.android.internal.org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.crypto.Digest;
import com.android.internal.org.bouncycastle.crypto.digests.SHA256Digest;
import com.android.internal.org.bouncycastle.crypto.digests.SHA384Digest;
import com.android.internal.org.bouncycastle.crypto.digests.SHA512Digest;
import com.android.internal.org.bouncycastle.crypto.params.ECDomainParameters;
import com.android.internal.org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import com.android.internal.org.bouncycastle.crypto.params.RSAKeyParameters;
import com.android.internal.org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters;
import com.android.internal.org.bouncycastle.crypto.signers.ECDSASigner;
import com.android.internal.org.bouncycastle.crypto.signers.RSADigestSigner;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.util.ECUtil;
import com.android.internal.org.bouncycastle.jce.ECNamedCurveTable;
import com.android.internal.org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import com.android.internal.org.bouncycastle.jce.spec.ECParameterSpec;
import com.android.internal.org.bouncycastle.operator.ContentSigner;
import com.android.internal.org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.ArrayList;
import java.util.List;

/**
 * PATCH rung: re-signs the device's real leaf with the keybox private key, updating
 * identity, patch levels, and RootOfTrust (locked + Verified) for Strong Integrity.
 *
 *
 * <p>Every result passes {@link #selfVerify} before it is returned: the new leaf must
 * verify against the keybox issuer, carry the app's original public key, and preserve
 * the original challenge and security levels. The RootOfTrust must show locked + Verified
 * with the original boot key and hash. Otherwise null — the ladder returns the original chain.
 *
 * @hide
 */
public final class YasvaraLeafPatcher {
    private static final String TAG = "YasvaraLeafPatcher";

    private YasvaraLeafPatcher() {}

    // KeyMint attestation-ID tags carrying device identity (cf. Tag.aidl).
    // Rewritten from this process' live Build fields so the leaf can never
    // contradict what the same process reports via Build (new GMS cross-checks
    // cert brand/product against Build.BRAND/Build.PRODUCT). Serial/IMEI/MEID
    // (713/714/715/723) and module hash (724) are always preserved.
    private static final int TAG_ATTEST_BRAND = 710;
    private static final int TAG_ATTEST_DEVICE = 711;
    private static final int TAG_ATTEST_PRODUCT = 712;
    private static final int TAG_ATTEST_MANUFACTURER = 716;
    private static final int TAG_ATTEST_MODEL = 717;

    /** Inputs for one patch operation; all BC/ASN.1 work stays inside. */
    public static final class Request {
        public X509Certificate realLeaf;
        public YasvaraAttestation.Record record;
        public PrivateKey keyboxPrivateKey;
        public List<X509Certificate> keyboxChain;
        /** Optional cached issuer subject from {@link YasvaraKeyboxProvider.Keybox}. */
        public X500Name keyboxIssuer;
        public int osVersion;
        public int osPatch;
        public int vendorPatch;
        public int bootPatch;
        /** Canonical digest; must match the original attestation, never replaces it. */
        public byte[] bootHash;
        /** Optional 32-byte verifiedBootKey used only when hardware key is empty/all-zero. */
        public byte[] bootKey;
        /**
         * Optional attestation-ID story (tag -&gt; string), e.g. the PIF profile for
         * GMS. Null entries fall back to this process' live Build fields.
         */
        public java.util.Map<Integer, String> attestIdOverrides;
    }

    /** Re-signs the leaf; null when anything is missing or self-verify fails. */
    public static Certificate[] patch(Request req) {
        try {
            if (req == null || req.realLeaf == null || req.record == null
                    || req.keyboxPrivateKey == null
                    || req.keyboxChain == null || req.keyboxChain.isEmpty()
                    || req.record.teeEnforced == null || req.bootHash == null) {
                return null;
            }
            X509Certificate leaf = req.realLeaf;
            X509CertificateHolder leafHolder = new X509CertificateHolder(leaf.getEncoded());
            // Bind the supplied record and canonical digest to the actual original leaf
            // before any signing. A caller-supplied record cannot replace hardware state.
            YasvaraAttestation.Record originalRecord = YasvaraAttestation.parse(leaf);
            if (originalRecord == null
                    || !sameRootOfTrust(originalRecord, req.record)) {
                return null;
            }

            boolean origHashZero = YasvaraBootDigest.isAllZero(originalRecord.verifiedBootHash);
            if (!origHashZero && !YasvaraAttestation.sameBytes(
                    req.bootHash, originalRecord.verifiedBootHash)) {
                return null;
            }
            byte[] effectiveBootHash = origHashZero
                    ? req.bootHash : originalRecord.verifiedBootHash;
            if (YasvaraBootDigest.isAllZero(effectiveBootHash)) {
                return null;
            }
            byte[] effectiveBootKey =
                    (YasvaraBootDigest.isAllZero(originalRecord.verifiedBootKey)
                            && req.bootKey != null && req.bootKey.length == 32
                            && !YasvaraBootDigest.isAllZero(req.bootKey))
                    ? req.bootKey : originalRecord.verifiedBootKey;
            if (effectiveBootKey == null) {
                return null;
            }

            // Build spoofed RootOfTrust: preserve valid hardware boot key/hash (or fallback
            // when TEE zeroes them on unlocked bootloaders),
            // set deviceLocked=TRUE and verifiedBootState=Verified(0).
            ASN1EncodableVector rotElements = new ASN1EncodableVector();
            rotElements.add(new DEROctetString(effectiveBootKey));
            rotElements.add(ASN1Boolean.TRUE);
            rotElements.add(new ASN1Enumerated(0));
            rotElements.add(new DEROctetString(effectiveBootHash));
            DERTaggedObject spoofedRoot = new DERTaggedObject(true, 704, new DERSequence(rotElements));

            // Keep all TEE tags except patch levels, identity tags, and RootOfTrust (replaced).
            ASN1EncodableVector kept = new ASN1EncodableVector();
            java.util.Map<Integer, ASN1TaggedObject> replacedIds = new java.util.HashMap<>();
            final boolean stripHwUniqueIds =
                    req.attestIdOverrides != null && !req.attestIdOverrides.isEmpty();
            for (ASN1Encodable e : originalRecord.teeEnforced) {
                if (!(e instanceof ASN1TaggedObject)) continue;
                int tag = ((ASN1TaggedObject) e).getTagNo();
                if (tag == 704 || tag == 705 || tag == 706 || tag == 718 || tag == 719) continue;
                if (stripHwUniqueIds && (tag == 713 || tag == 714 || tag == 715 || tag == 723)) {
                    continue;
                }
                if (tag == TAG_ATTEST_BRAND || tag == TAG_ATTEST_DEVICE
                        || tag == TAG_ATTEST_PRODUCT || tag == TAG_ATTEST_MANUFACTURER
                        || tag == TAG_ATTEST_MODEL) {
                    replacedIds.put(tag, (ASN1TaggedObject) e);
                    continue;
                }
                kept.add((ASN1TaggedObject) e);
            }

            kept.add(spoofedRoot);
            kept.add(new DERTaggedObject(true, 705, new ASN1Integer(req.osVersion)));
            kept.add(new DERTaggedObject(true, 706, new ASN1Integer(req.osPatch)));

            // Attestation IDs follow the override story when one was supplied
            // (PIF profile for GMS/Play Store); otherwise this process' live
            // Build fields, which can never contradict them. Only tags the TEE
            // actually emitted.
            addIdentityString(kept, replacedIds, TAG_ATTEST_BRAND,
                    idValue(req, TAG_ATTEST_BRAND, Build.BRAND));
            addIdentityString(kept, replacedIds, TAG_ATTEST_DEVICE,
                    idValue(req, TAG_ATTEST_DEVICE, Build.DEVICE));
            addIdentityString(kept, replacedIds, TAG_ATTEST_PRODUCT,
                    idValue(req, TAG_ATTEST_PRODUCT, Build.PRODUCT));
            addIdentityString(kept, replacedIds, TAG_ATTEST_MANUFACTURER,
                    idValue(req, TAG_ATTEST_MANUFACTURER, Build.MANUFACTURER));
            addIdentityString(kept, replacedIds, TAG_ATTEST_MODEL,
                    idValue(req, TAG_ATTEST_MODEL, Build.MODEL));

            kept.add(new DERTaggedObject(true, 718, new ASN1Integer(req.vendorPatch)));
            kept.add(new DERTaggedObject(true, 719, new ASN1Integer(req.bootPatch)));

            ASN1Encodable[] keyDescription = originalRecord.keyDescription.clone();
            keyDescription[7] = new DERSequence(kept);
            Extension hackedExtension = new Extension(YasvaraAttestation.ATTESTATION_OID, false,
                    YasvaraAttestation.octetString(new DERSequence(keyDescription).getEncoded()));

            X500Name issuerName = req.keyboxIssuer;
            if (issuerName == null) {
                issuerName = new X509CertificateHolder(
                        req.keyboxChain.get(0).getEncoded()).getSubject();
            }
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    issuerName,
                    leafHolder.getSerialNumber(),
                    leafHolder.getNotBefore(),
                    leafHolder.getNotAfter(),
                    leafHolder.getSubject(),
                    leafHolder.getSubjectPublicKeyInfo());
            builder.addExtension(hackedExtension);
            for (Object oid : leafHolder.getExtensions().getExtensionOIDs()) {
                String id = oid.toString();
                if (!id.equals(YasvaraAttestation.ATTESTATION_OID.getId())
                        && !"2.5.29.35".equals(id)) {
                    builder.addExtension(
                            leafHolder.getExtension(new ASN1ObjectIdentifier(id)));
                }
            }

            ContentSigner signer = signerFor(leaf.getSigAlgName(), req.keyboxPrivateKey);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate patched = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(builder.build(signer).getEncoded()));

            List<Certificate> result = new ArrayList<>(req.keyboxChain.size() + 1);
            result.add(patched);
            result.addAll(req.keyboxChain);
            Certificate[] out = result.toArray(new Certificate[0]);
            if (!selfVerify(leaf, originalRecord, patched, req.keyboxChain.get(0),
                    effectiveBootKey, effectiveBootHash)) {
                Log.w(TAG, "self-verify failed, discarding patched leaf");
                return null;
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "patch failed", t);
            return null;
        }
    }

    /** Override story wins; otherwise this process' live Build value. */
    private static String idValue(Request req, int tag, String buildValue) {
        try {
            if (req != null && req.attestIdOverrides != null) {
                String v = req.attestIdOverrides.get(tag);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Throwable ignored) {
        }
        return buildValue;
    }

    /**
     * Rewrites one attestation-ID tag to this process' live Build value, keeping the
     * TEE's original string encoding. Tags the TEE never emitted are left absent.
     */
    private static void addIdentityString(ASN1EncodableVector kept,
            java.util.Map<Integer, ASN1TaggedObject> replaced, int tag, String value) {
        ASN1TaggedObject orig = replaced.get(tag);
        if (orig == null) return; // Tags the TEE never emitted must stay absent.
        try {
            if (value == null || value.isEmpty()) {
                kept.add(orig);
                return;
            }
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ASN1Encodable base = orig.getBaseObject();
            ASN1Encodable renewal = (base instanceof DERUTF8String)
                    ? new DERUTF8String(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                    : new DEROctetString(bytes);
            kept.add(new DERTaggedObject(true, tag, renewal));
        } catch (Throwable t) {
            Log.w(TAG, "identity tag " + tag + " kept original", t);
            kept.add(orig);
        }
    }

    /**
     * Compare every parsed RootOfTrust field and its complete DER.
     * Used to ensure the caller-supplied record has not forged hardware state.
     */
    private static boolean sameRootOfTrust(YasvaraAttestation.Record original,
            YasvaraAttestation.Record callerRecord) {
        if (original == null || callerRecord == null || original.verifiedBootKey == null
                || original.verifiedBootHash == null || original.deviceLocked == null
                || original.verifiedBootState == null) {
            return false;
        }
        try {
            if (!YasvaraAttestation.sameBytes(original.verifiedBootKey, callerRecord.verifiedBootKey)
                    || !YasvaraAttestation.sameBytes(original.verifiedBootHash, callerRecord.verifiedBootHash)
                    || !original.deviceLocked.equals(callerRecord.deviceLocked)
                    || !original.verifiedBootState.equals(callerRecord.verifiedBootState)) {
                return false;
            }
            ASN1TaggedObject originalRoot = rootOfTrustTag(original.teeEnforced);
            ASN1TaggedObject callerRoot = rootOfTrustTag(callerRecord.teeEnforced);
            return originalRoot != null && callerRoot != null
                    && YasvaraAttestation.sameBytes(originalRoot.getEncoded(), callerRoot.getEncoded());
        } catch (Throwable t) {
            return false;
        }
    }

    private static ASN1TaggedObject rootOfTrustTag(ASN1Sequence enforced) {
        if (enforced == null) return null;
        ASN1TaggedObject root = null;
        for (ASN1Encodable e : enforced) {
            if (!(e instanceof ASN1TaggedObject)) continue;
            ASN1TaggedObject tagged = (ASN1TaggedObject) e;
            if (tagged.getTagNo() != 704) continue;
            if (root != null) return null;
            root = tagged;
        }
        return root;
    }

    /**
     * The patched leaf must verify against the keybox issuer, carry the app's original
     * public key, preserve the original challenge and security levels, and have a spoofed
     * RootOfTrust (locked + Verified) with the original boot key and hash.
     */
    static boolean selfVerify(X509Certificate original, YasvaraAttestation.Record record,
            X509Certificate patched, X509Certificate issuer) {
        return selfVerify(original, record, patched, issuer,
                record != null ? record.verifiedBootKey : null,
                record != null ? record.verifiedBootHash : null);
    }

    static boolean selfVerify(X509Certificate original, YasvaraAttestation.Record originalRecord,
            X509Certificate patched, X509Certificate issuer,
            byte[] expectedBootKey, byte[] expectedBootHash) {
        try {
            patched.verify(issuer.getPublicKey());
            if (!patched.getPublicKey().equals(original.getPublicKey())) return false;
            YasvaraAttestation.Record patchedRecord = YasvaraAttestation.parse(patched);
            if (originalRecord == null || patchedRecord == null) return false;
            if (expectedBootKey == null
                    || YasvaraBootDigest.isAllZero(patchedRecord.verifiedBootHash)) {
                return false;
            }
            if (!YasvaraAttestation.sameBytes(
                    expectedBootKey, patchedRecord.verifiedBootKey)
                    || !YasvaraAttestation.sameBytes(
                    expectedBootHash, patchedRecord.verifiedBootHash)) {
                return false;
            }
            // RootOfTrust must be spoofed to locked + Verified.
            if (!Boolean.TRUE.equals(patchedRecord.deviceLocked)
                    || patchedRecord.verifiedBootState == null
                    || patchedRecord.verifiedBootState != 0) {
                return false;
            }
            if (!YasvaraAttestation.sameBytes(originalRecord.challenge, patchedRecord.challenge)) {
                return false;
            }
            // Only teeEnforced (index 7) may change. In particular, attestation and
            // keymaster security levels (indices 1 and 3) must stay hardware-authored.
            for (int i = 0; i < 7; i++) {
                if (!YasvaraAttestation.sameBytes(
                        originalRecord.keyDescription[i].toASN1Primitive().getEncoded(),
                        patchedRecord.keyDescription[i].toASN1Primitive().getEncoded())) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "selfVerify failed", t);
            return false;
        }
    }

    static ContentSigner signerFor(String sigAlgName, PrivateKey key) throws Exception {
        String digest = digestName(sigAlgName);
        boolean rsa = key instanceof RSAPrivateCrtKey;
        AlgorithmIdentifier sigAlgId = new DefaultSignatureAlgorithmIdentifierFinder()
                .find(digest.replace("-", "") + (rsa ? "withRSA" : "withECDSA"));
        if (key instanceof BCECPrivateKey) {
            BCECPrivateKey bcKey = (BCECPrivateKey) key;
            ECParameterSpec spec = bcKey.getParameters();
            ECDomainParameters domain;
            if (spec != null) {
                domain = new ECDomainParameters(
                        spec.getCurve(), spec.getG(), spec.getN(), spec.getH());
            } else {
                ECNamedCurveParameterSpec named =
                        ECNamedCurveTable.getParameterSpec("secp256r1");
                domain = new ECDomainParameters(
                        named.getCurve(), named.getG(), named.getN(), named.getH());
            }
            return new EcSigner(sigAlgId, digest,
                    new ECPrivateKeyParameters(bcKey.getD(), domain));
        }
        if (key instanceof ECPrivateKey) {
            return new EcSigner(sigAlgId, digest, (ECPrivateKeyParameters)
                    ECUtil.generatePrivateKeyParameter(key));
        }
        if (key instanceof RSAPrivateCrtKey) {
            RSAPrivateCrtKey rsaKey = (RSAPrivateCrtKey) key;
            return new RsaSigner(sigAlgId, digestFor(digest), new RSAPrivateCrtKeyParameters(
                    rsaKey.getModulus(),
                    rsaKey.getPublicExponent(),
                    rsaKey.getPrivateExponent(),
                    rsaKey.getPrimeP(),
                    rsaKey.getPrimeQ(),
                    rsaKey.getPrimeExponentP(),
                    rsaKey.getPrimeExponentQ(),
                    rsaKey.getCrtCoefficient()));
        }
        throw new IllegalArgumentException("unsupported key " + key.getClass());
    }

    /**
     * Keep the leaf's digest when it is one we can produce; SHA-1 and unknown
     * names fall back to SHA-256. The AlgorithmIdentifier is always built from
     * the actual keybox key type, never from a mismatched leaf signature name.
     */
    private static String digestName(String sigAlgName) {
        if (sigAlgName != null) {
            String upper = sigAlgName.toUpperCase(java.util.Locale.ROOT);
            if (upper.contains("SHA384")) return "SHA-384";
            if (upper.contains("SHA512")) return "SHA-512";
        }
        return "SHA-256";
    }

    private static Digest digestFor(String digestName) {
        if ("SHA-384".equals(digestName)) return new SHA384Digest();
        if ("SHA-512".equals(digestName)) return new SHA512Digest();
        return new SHA256Digest();
    }

    /** True when {@code chain} is already issued by this keybox and needs no re-sign. */
    static boolean issuedBy(Certificate[] chain, X509Certificate keyboxLeaf) {
        try {
            if (chain == null || chain.length < 2 || keyboxLeaf == null
                    || !(chain[0] instanceof X509Certificate)
                    || !(chain[1] instanceof X509Certificate)) {
                return false;
            }
            X509Certificate storedIssuer = (X509Certificate) chain[1];
            if (!java.util.Arrays.equals(
                    storedIssuer.getEncoded(), keyboxLeaf.getEncoded())) {
                return false;
            }
            X509Certificate leaf = (X509Certificate) chain[0];
            if (!leaf.getIssuerX500Principal()
                    .equals(keyboxLeaf.getSubjectX500Principal())) {
                return false;
            }
            leaf.verify(keyboxLeaf.getPublicKey());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class EcSigner implements ContentSigner {
        private final AlgorithmIdentifier algId;
        private final String digestName;
        private final ECPrivateKeyParameters key;
        private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

        EcSigner(AlgorithmIdentifier algId, String digestName, ECPrivateKeyParameters key) {
            this.algId = algId;
            this.digestName = digestName;
            this.key = key;
        }

        @Override public AlgorithmIdentifier getAlgorithmIdentifier() { return algId; }
        @Override public OutputStream getOutputStream() { return stream; }
        @Override public byte[] getSignature() {
            try {
                byte[] hash = MessageDigest.getInstance(digestName)
                        .digest(stream.toByteArray());
                ECDSASigner signer = new ECDSASigner();
                signer.init(true, key);
                BigInteger[] sig = signer.generateSignature(hash);
                ASN1EncodableVector v = new ASN1EncodableVector();
                v.add(new ASN1Integer(sig[0]));
                v.add(new ASN1Integer(sig[1]));
                return new DERSequence(v).getEncoded();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class RsaSigner implements ContentSigner {
        private final AlgorithmIdentifier algId;
        private final Digest digest;
        private final RSAKeyParameters key;
        private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

        RsaSigner(AlgorithmIdentifier algId, Digest digest, RSAKeyParameters key) {
            this.algId = algId;
            this.digest = digest;
            this.key = key;
        }

        @Override public AlgorithmIdentifier getAlgorithmIdentifier() { return algId; }
        @Override public OutputStream getOutputStream() { return stream; }
        @Override public byte[] getSignature() {
            try {
                RSADigestSigner signer = new RSADigestSigner(digest);
                signer.init(true, key);
                byte[] data = stream.toByteArray();
                signer.update(data, 0, data.length);
                return signer.generateSignature();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
