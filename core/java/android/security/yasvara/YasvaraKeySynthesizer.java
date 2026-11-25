/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.EcCurve;
import android.hardware.security.keymint.KeyOrigin;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyParameterValue;
import android.hardware.security.keymint.KeyPurpose;
import android.hardware.security.keymint.SecurityLevel;
import android.hardware.security.keymint.Tag;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.keystore2.Authorization;
import android.system.keystore2.IKeystoreSecurityLevel;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1Boolean;
import com.android.internal.org.bouncycastle.asn1.ASN1Encodable;
import com.android.internal.org.bouncycastle.asn1.ASN1EncodableVector;
import com.android.internal.org.bouncycastle.asn1.ASN1Enumerated;
import com.android.internal.org.bouncycastle.asn1.ASN1Integer;
import com.android.internal.org.bouncycastle.asn1.ASN1Primitive;
import com.android.internal.org.bouncycastle.asn1.ASN1Sequence;
import com.android.internal.org.bouncycastle.asn1.DERNull;
import com.android.internal.org.bouncycastle.asn1.DEROctetString;
import com.android.internal.org.bouncycastle.asn1.DERSequence;
import com.android.internal.org.bouncycastle.asn1.DERSet;
import com.android.internal.org.bouncycastle.asn1.DERTaggedObject;
import com.android.internal.org.bouncycastle.asn1.x500.X500Name;
import com.android.internal.org.bouncycastle.asn1.x509.Extension;
import com.android.internal.org.bouncycastle.asn1.x509.KeyUsage;
import com.android.internal.org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import com.android.internal.org.bouncycastle.asn1.x509.Time;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;
import com.android.internal.org.bouncycastle.cert.X509v3CertificateBuilder;
import com.android.internal.org.bouncycastle.operator.ContentSigner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;

import javax.security.auth.x500.X500Principal;

/**
 * Synthesizes key generation and X.509 attestation certificate chains for target packages
 * (Play Store, Play Services, Wallet) when attestation is requested.
 *
 * <p>Prevents KM_ERROR_CANNOT_ATTEST_IDS failure on unlocked or customized bootloaders by
 * fulfilling device-properties attestation with signed Keybox certificates directly,
 * preserving hardware root-of-trust verifiedBootHash and setting deviceLocked=true.
 *
 * @hide
 */
public final class YasvaraKeySynthesizer {
    private static final String TAG = "YasvaraKeySynth";

    private static final int MAX_ATTESTATION_CHALLENGE_SIZE = 128;
    private static final X500Name DEFAULT_SUBJECT = new X500Name("CN=Android Keystore Key");
    private static volatile byte[] sCachedSelfAppId;

    private YasvaraKeySynthesizer() {}

    /** Parsed key generation parameters. */
    public static final class KeyGenParameters {
        public int keySize;
        public int algorithm;
        public byte[] certificateSerial;
        public long certificateNotBefore;
        public long certificateNotAfter;
        public byte[] certificateSubject;

        public BigInteger rsaPublicExponent = RSAKeyGenParameterSpec.F4;
        public int ecCurve = EcCurve.P_256;
        public String ecCurveName = "secp256r1";

        public List<Integer> purpose = new ArrayList<>();
        public List<Integer> digest = new ArrayList<>();
        public List<Integer> mgfDigest = new ArrayList<>();

        public byte[] attestationChallenge;
        public byte[] brand;
        public byte[] device;
        public byte[] product;
        public byte[] manufacturer;
        public byte[] model;

        public int securityLevel = SecurityLevel.TRUSTED_ENVIRONMENT;
        public boolean noAuthRequired;

        public KeyGenParameters(Collection<KeyParameter> args) {
            if (args == null) return;
            for (KeyParameter kp : args) {
                if (kp == null || kp.value == null) continue;
                switch (kp.tag) {
                    case Tag.KEY_SIZE -> keySize = kp.value.getInteger();
                    case Tag.ALGORITHM -> algorithm = kp.value.getAlgorithm();
                    case Tag.CERTIFICATE_SERIAL -> certificateSerial = kp.value.getBlob();
                    case Tag.CERTIFICATE_NOT_BEFORE -> certificateNotBefore = kp.value.getDateTime();
                    case Tag.CERTIFICATE_NOT_AFTER -> certificateNotAfter = kp.value.getDateTime();
                    case Tag.CERTIFICATE_SUBJECT -> certificateSubject = kp.value.getBlob();
                    case Tag.RSA_PUBLIC_EXPONENT ->
                            rsaPublicExponent = BigInteger.valueOf(kp.value.getLongInteger());
                    case Tag.EC_CURVE -> {
                        ecCurve = kp.value.getEcCurve();
                        ecCurveName = getEcCurveName(ecCurve);
                    }
                    case Tag.PURPOSE -> purpose.add(kp.value.getKeyPurpose());
                    case Tag.DIGEST -> digest.add(kp.value.getDigest());
                    case Tag.RSA_OAEP_MGF_DIGEST -> mgfDigest.add(kp.value.getDigest());
                    case Tag.ATTESTATION_CHALLENGE -> attestationChallenge = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_BRAND -> brand = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_DEVICE -> device = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_PRODUCT -> product = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_MANUFACTURER -> manufacturer = kp.value.getBlob();
                    case Tag.ATTESTATION_ID_MODEL -> model = kp.value.getBlob();
                    case Tag.HARDWARE_TYPE -> securityLevel = kp.value.getSecurityLevel();
                    case Tag.NO_AUTH_REQUIRED -> noAuthRequired = kp.value.getBoolValue();
                }
            }

            if (algorithm == Algorithm.EC) {
                if (keySize <= 0) keySize = 256;
                if (ecCurveName == null || ecCurveName.isEmpty()) {
                    ecCurve = EcCurve.P_256;
                    ecCurveName = "secp256r1";
                }
            } else if (algorithm == Algorithm.RSA) {
                if (keySize <= 0) keySize = 2048;
            }
            if (securityLevel <= 0) {
                securityLevel = SecurityLevel.TRUSTED_ENVIRONMENT;
            }
        }
    }

    public static String getEcCurveName(int curve) {
        return switch (curve) {
            case EcCurve.CURVE_25519 -> "CURVE_25519";
            case EcCurve.P_224 -> "secp224r1";
            case EcCurve.P_256 -> "secp256r1";
            case EcCurve.P_384 -> "secp384r1";
            case EcCurve.P_521 -> "secp521r1";
            default -> "secp256r1";
        };
    }

    /** Serializable synthesis request passed across Binder. */
    public static final class SynthesisRequest {
        public int algorithm;
        public int keySize;
        public int ecCurve;
        public String ecCurveName;
        public int securityLevel;
        public boolean noAuthRequired;
        public long creationDateTime;
        public int osVersion;
        public int osPatchLevel;
        public int vendorPatchLevel;
        public int bootPatchLevel;
        public byte[] publicKeyEncoded;
        public byte[] attestationChallenge;
        public byte[] certificateSubject;
        public byte[] certificateSerial;
        public long certificateNotBefore;
        public long certificateNotAfter;
        public List<Integer> purpose = new ArrayList<>();
        public List<Integer> digest = new ArrayList<>();
        public List<Integer> mgfDigest = new ArrayList<>();
        public byte[] brand;
        public byte[] device;
        public byte[] product;
        public byte[] manufacturer;
        public byte[] model;
        public byte[] applicationId;

        public byte[] toByteArray() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(algorithm);
            out.writeInt(keySize);
            out.writeInt(ecCurve);
            out.writeUTF(ecCurveName != null ? ecCurveName : "");
            out.writeInt(securityLevel);
            out.writeBoolean(noAuthRequired);
            out.writeLong(creationDateTime);
            out.writeInt(osVersion);
            out.writeInt(osPatchLevel);
            out.writeInt(vendorPatchLevel);
            out.writeInt(bootPatchLevel);
            writeBytes(out, publicKeyEncoded);
            writeBytes(out, attestationChallenge);
            writeBytes(out, certificateSubject);
            writeBytes(out, certificateSerial);
            out.writeLong(certificateNotBefore);
            out.writeLong(certificateNotAfter);
            out.writeInt(purpose.size());
            for (int p : purpose) out.writeInt(p);
            out.writeInt(digest.size());
            for (int d : digest) out.writeInt(d);
            out.writeInt(mgfDigest.size());
            for (int m : mgfDigest) out.writeInt(m);
            writeBytes(out, brand);
            writeBytes(out, device);
            writeBytes(out, product);
            writeBytes(out, manufacturer);
            writeBytes(out, model);
            writeBytes(out, applicationId);
            out.flush();
            return baos.toByteArray();
        }

        public static SynthesisRequest fromByteArray(byte[] data) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            SynthesisRequest r = new SynthesisRequest();
            r.algorithm = in.readInt();
            r.keySize = in.readInt();
            r.ecCurve = in.readInt();
            r.ecCurveName = in.readUTF();
            r.securityLevel = in.readInt();
            r.noAuthRequired = in.readBoolean();
            r.creationDateTime = in.readLong();
            r.osVersion = in.readInt();
            r.osPatchLevel = in.readInt();
            r.vendorPatchLevel = in.readInt();
            r.bootPatchLevel = in.readInt();
            r.publicKeyEncoded = readBytes(in);
            r.attestationChallenge = readBytes(in);
            r.certificateSubject = readBytes(in);
            r.certificateSerial = readBytes(in);
            r.certificateNotBefore = in.readLong();
            r.certificateNotAfter = in.readLong();
            int pCount = in.readInt();
            for (int i = 0; i < pCount; i++) r.purpose.add(in.readInt());
            int dCount = in.readInt();
            for (int i = 0; i < dCount; i++) r.digest.add(in.readInt());
            int mCount = in.readInt();
            for (int i = 0; i < mCount; i++) r.mgfDigest.add(in.readInt());
            r.brand = readBytes(in);
            r.device = readBytes(in);
            r.product = readBytes(in);
            r.manufacturer = readBytes(in);
            r.model = readBytes(in);
            r.applicationId = readBytes(in);
            return r;
        }

        private static void writeBytes(DataOutputStream out, byte[] b) throws IOException {
            if (b == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(b.length);
                out.write(b);
            }
        }

        private static byte[] readBytes(DataInputStream in) throws IOException {
            int len = in.readInt();
            if (len < 0) return null;
            byte[] b = new byte[len];
            in.readFully(b);
            return b;
        }
    }

    /**
     * Intercepts generateKey in the calling application process.
     * Returns synthesized KeyMetadata, or null to fall back to hardware Keystore/TEE.
     */
    public static KeyMetadata onGenerateKey(
            IKeystoreSecurityLevel level,
            KeyDescriptor descriptor,
            Collection<KeyParameter> args) {
        try {
            Context ctx = ActivityThread.currentApplication();
            if (ctx == null) return null;
            if (!YasvaraTrickyStore.isEnabled(ctx)) return null;
            String pkg = ctx.getPackageName();
            if (TextUtils.isEmpty(pkg) || !YasvaraTrickyStore.isTarget(ctx, pkg)) return null;

            KeyGenParameters params = new KeyGenParameters(args);
            if (params.attestationChallenge == null
                    || params.attestationChallenge.length > MAX_ATTESTATION_CHALLENGE_SIZE) {
                return null;
            }
            if (params.purpose == null || !params.purpose.contains(KeyPurpose.SIGN)) return null;
            if (params.algorithm != Algorithm.EC && params.algorithm != Algorithm.RSA) {
                Log.w(TAG, "Unsupported algorithm for synthesis: " + params.algorithm);
                return null;
            }

            KeyPair kp = generateKeyPair(params);
            if (kp == null) return null;

            int uid = android.os.Process.myUid();
            byte[] appId = createApplicationId(ctx, uid);

            String patchDate = YasvaraIdentity.patchDateFor(ctx, pkg);
            int osPatch = YasvaraIdentity.patchYyyymm(patchDate);
            int longPatch = YasvaraIdentity.patchYyyymmdd(patchDate);
            int osVer = YasvaraLadder.osVersionInt(ctx, pkg);

            // Populate identity tags from PIF / live Build if not passed directly
            if (params.brand == null) {
                java.util.Map<Integer, String> ids = YasvaraLadder.attestIdsFor(ctx, pkg);
                if (ids != null) {
                    if (ids.containsKey(710)) params.brand = ids.get(710).getBytes(StandardCharsets.UTF_8);
                    if (ids.containsKey(711)) params.device = ids.get(711).getBytes(StandardCharsets.UTF_8);
                    if (ids.containsKey(712)) params.product = ids.get(712).getBytes(StandardCharsets.UTF_8);
                    if (ids.containsKey(716)) params.manufacturer = ids.get(716).getBytes(StandardCharsets.UTF_8);
                    if (ids.containsKey(717)) params.model = ids.get(717).getBytes(StandardCharsets.UTF_8);
                }
            }

            SynthesisRequest req = new SynthesisRequest();
            req.algorithm = params.algorithm;
            req.keySize = params.keySize;
            req.ecCurve = params.ecCurve;
            req.ecCurveName = params.ecCurveName;
            req.securityLevel = params.securityLevel;
            req.noAuthRequired = params.noAuthRequired;
            req.creationDateTime = System.currentTimeMillis();
            req.osVersion = osVer;
            req.osPatchLevel = osPatch;
            req.vendorPatchLevel = longPatch;
            req.bootPatchLevel = longPatch;
            req.publicKeyEncoded = kp.getPublic().getEncoded();
            req.attestationChallenge = params.attestationChallenge;
            req.certificateSubject = params.certificateSubject;
            req.certificateSerial = params.certificateSerial;
            req.certificateNotBefore = params.certificateNotBefore;
            req.certificateNotAfter = params.certificateNotAfter;
            req.purpose = params.purpose;
            req.digest = params.digest;
            req.mgfDigest = params.mgfDigest;
            req.brand = params.brand;
            req.device = params.device;
            req.product = params.product;
            req.manufacturer = params.manufacturer;
            req.model = params.model;
            req.applicationId = appId;

            Certificate[] chain = null;
            try {
                // 1. Primary path: system_server holds the private key (Phase 2)
                byte[] reqBytes = req.toByteArray();
                chain = YasvaraServiceClient.synthesize(pkg, uid, reqBytes);
            } catch (Throwable t) {
                Log.w(TAG, "system_server synthesis failed, trying in-process", t);
            }

            // 2. Fallback path: in-process ladder using Settings.Secure keybox (Phase 1)
            if (chain == null || chain.length == 0) {
                List<YasvaraKeyboxProvider.Keybox> keyboxes = YasvaraLadder.loadKeyboxes(ctx);
                if (keyboxes != null && !keyboxes.isEmpty()) {
                    chain = generateChain(ctx, pkg, uid, req, keyboxes);
                }
            }

            if (chain == null || chain.length == 0) {
                Log.w(TAG, "No keybox chain generated for alias=" + descriptor.alias);
                return null;
            }

            KeyEntryResponse response = buildResponse(level, descriptor, req, chain);
            if (response == null) return null;

            YasvaraTrickyStore.cacheKeyEntry(uid, descriptor.alias, response);
            Log.i(TAG, "Synthesized and cached key for pkg=" + pkg + " alias=" + descriptor.alias);
            return response.metadata;
        } catch (Throwable t) {
            Log.w(TAG, "onGenerateKey unexpected error", t);
            return null;
        }
    }

    /** Entry point called by YasvaraAttestationService in system_server. */
    public static Certificate[] generateChainFromBytes(
            Context ctx, String pkg, int uid, byte[] reqBytes,
            List<YasvaraKeyboxProvider.Keybox> keyboxes) {
        try {
            SynthesisRequest req = SynthesisRequest.fromByteArray(reqBytes);
            return generateChain(ctx, pkg, uid, req, keyboxes);
        } catch (Throwable t) {
            Log.w(TAG, "generateChainFromBytes failed", t);
            return null;
        }
    }

    /** Synthesizes the leaf certificate and appends the Keybox intermediate/root certs. */
    public static Certificate[] generateChain(
            Context ctx, String pkg, int uid, SynthesisRequest req,
            List<YasvaraKeyboxProvider.Keybox> keyboxes) {
        try {
            if (req == null || keyboxes == null || keyboxes.isEmpty()) return null;
            YasvaraKeyboxProvider.Keybox keybox = selectKeybox(req.algorithm, keyboxes);
            if (keybox == null || !keybox.hasPrivateKey()) {
                Log.w(TAG, "No matching keybox with private key found");
                return null;
            }

            PrivateKey privKey = YasvaraKeyMaterial.toPrivateKey(
                    keybox.privateKeyPem, keybox.algorithm);
            if (privKey == null) {
                Log.w(TAG, "Failed to parse keybox private key");
                return null;
            }

            byte[] bootHash = YasvaraIdentity.bootHash();
            byte[] bootKey = YasvaraIdentity.resolveBootKey(bootHash);
            if (bootHash == null || bootKey == null) {
                return null;
            }

            // Tag 704 RootOfTrust
            ASN1EncodableVector rotVector = new ASN1EncodableVector();
            rotVector.add(new DEROctetString(bootKey));
            rotVector.add(ASN1Boolean.TRUE); // deviceLocked = true
            rotVector.add(new ASN1Enumerated(0)); // verifiedBootState = Verified (0)
            rotVector.add(new DEROctetString(bootHash));
            DERTaggedObject rootOfTrust = new DERTaggedObject(true, 704, new DERSequence(rotVector));

            // TEE Enforced encodables
            ASN1EncodableVector teeVector = new ASN1EncodableVector();
            if (req.purpose != null && !req.purpose.isEmpty()) {
                ASN1EncodableVector pv = new ASN1EncodableVector();
                for (int p : req.purpose) pv.add(new ASN1Integer(p));
                teeVector.add(new DERTaggedObject(true, 1, new DERSet(pv)));
            }
            teeVector.add(new DERTaggedObject(true, 2, new ASN1Integer(req.algorithm)));
            teeVector.add(new DERTaggedObject(true, 3, new ASN1Integer(req.keySize)));
            if (req.digest != null && !req.digest.isEmpty()) {
                ASN1EncodableVector dv = new ASN1EncodableVector();
                for (int d : req.digest) dv.add(new ASN1Integer(d));
                teeVector.add(new DERTaggedObject(true, 5, new DERSet(dv)));
            }
            if (req.algorithm == Algorithm.EC && req.ecCurve >= 0) {
                teeVector.add(new DERTaggedObject(true, 10, new ASN1Integer(req.ecCurve)));
            }
            if (req.mgfDigest != null && !req.mgfDigest.isEmpty()) {
                ASN1EncodableVector mgfSet = new ASN1EncodableVector();
                for (int d : req.mgfDigest) {
                    mgfSet.add(new ASN1Integer(d));
                }
                teeVector.add(new DERTaggedObject(true, 203, new DERSet(mgfSet)));
            }
            if (req.noAuthRequired) {
                teeVector.add(new DERTaggedObject(true, 503, DERNull.INSTANCE));
            }
            teeVector.add(new DERTaggedObject(true, 702, new ASN1Integer(0))); // Origin.GENERATED
            teeVector.add(rootOfTrust);
            teeVector.add(new DERTaggedObject(true, 705, new ASN1Integer(req.osVersion)));
            teeVector.add(new DERTaggedObject(true, 706, new ASN1Integer(req.osPatchLevel)));
            teeVector.add(new DERTaggedObject(true, 718, new ASN1Integer(req.vendorPatchLevel)));
            teeVector.add(new DERTaggedObject(true, 719, new ASN1Integer(req.bootPatchLevel)));

            if (req.brand != null && req.brand.length > 0) {
                teeVector.add(new DERTaggedObject(true, 710, new DEROctetString(req.brand)));
            }
            if (req.device != null && req.device.length > 0) {
                teeVector.add(new DERTaggedObject(true, 711, new DEROctetString(req.device)));
            }
            if (req.product != null && req.product.length > 0) {
                teeVector.add(new DERTaggedObject(true, 712, new DEROctetString(req.product)));
            }
            if (req.manufacturer != null && req.manufacturer.length > 0) {
                teeVector.add(new DERTaggedObject(true, 716, new DEROctetString(req.manufacturer)));
            }
            if (req.model != null && req.model.length > 0) {
                teeVector.add(new DERTaggedObject(true, 717, new DEROctetString(req.model)));
            }

            // Software Enforced encodables
            ASN1EncodableVector swVector = new ASN1EncodableVector();
            swVector.add(new DERTaggedObject(true, 701, new ASN1Integer(req.creationDateTime)));
            if (req.applicationId != null && req.applicationId.length > 0) {
                swVector.add(new DERTaggedObject(true, 709, new DEROctetString(req.applicationId)));
            }

            // KeyDescription sequence
            ASN1Encodable[] keyDescriptionEncodables = new ASN1Encodable[] {
                new ASN1Integer(100), // KeyMint 1.0
                new ASN1Enumerated(1), // TrustedEnvironment
                new ASN1Integer(100),
                new ASN1Enumerated(1),
                new DEROctetString(req.attestationChallenge),
                new DEROctetString(new byte[0]),
                new DERSequence(swVector),
                new DERSequence(teeVector)
            };

            Extension attestationExt = new Extension(
                    YasvaraAttestation.ATTESTATION_OID,
                    false,
                    new DEROctetString(new DERSequence(keyDescriptionEncodables)));

            X500Name subject;
            if (req.certificateSubject != null && req.certificateSubject.length > 0) {
                try {
                    subject = new X500Name(new X500Principal(req.certificateSubject).getName());
                } catch (Throwable t) {
                    subject = DEFAULT_SUBJECT;
                }
            } else {
                subject = DEFAULT_SUBJECT;
            }

            BigInteger serial;
            if (req.certificateSerial != null && req.certificateSerial.length > 0) {
                serial = new BigInteger(req.certificateSerial);
            } else {
                serial = BigInteger.valueOf(System.currentTimeMillis());
            }

            long notBefore = req.certificateNotBefore > 0
                    ? req.certificateNotBefore : System.currentTimeMillis() - 86400000L;
            long notAfter = req.certificateNotAfter > 0
                    ? req.certificateNotAfter : System.currentTimeMillis() + 315360000000L;

            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(
                    ASN1Primitive.fromByteArray(req.publicKeyEncoded));

            X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                    keybox.issuerName(),
                    serial,
                    new Time(new Date(notBefore)),
                    new Time(new Date(notAfter)),
                    subject,
                    spki
            );
            certBuilder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature));
            certBuilder.addExtension(attestationExt);

            ContentSigner signer = YasvaraLeafPatcher.signerFor(
                    keybox.leaf().getSigAlgName(), privKey);
            X509CertificateHolder holder = certBuilder.build(signer);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate leaf = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(holder.getEncoded()));

            List<Certificate> fullChain = new ArrayList<>(keybox.chain.size() + 1);
            fullChain.add(leaf);
            fullChain.addAll(keybox.chain);
            return fullChain.toArray(new Certificate[0]);
        } catch (Throwable t) {
            Log.w(TAG, "generateChain failed", t);
            return null;
        }
    }

    private static YasvaraKeyboxProvider.Keybox selectKeybox(
            int algorithm, List<YasvaraKeyboxProvider.Keybox> keyboxes) {
        if (keyboxes == null || keyboxes.isEmpty()) return null;
        boolean wantRsa = (algorithm == Algorithm.RSA);
        for (YasvaraKeyboxProvider.Keybox kb : keyboxes) {
            if ("rsa".equalsIgnoreCase(kb.algorithm) == wantRsa && kb.hasPrivateKey()) {
                return kb;
            }
        }
        for (YasvaraKeyboxProvider.Keybox kb : keyboxes) {
            if (kb.hasPrivateKey()) return kb;
        }
        return keyboxes.get(0);
    }

    private static KeyPair generateKeyPair(KeyGenParameters params) throws Exception {
        if (params.algorithm == Algorithm.EC) {
            ECGenParameterSpec spec = new ECGenParameterSpec(params.ecCurveName);
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(spec);
            return kpg.generateKeyPair();
        } else if (params.algorithm == Algorithm.RSA) {
            RSAKeyGenParameterSpec spec = new RSAKeyGenParameterSpec(
                    params.keySize, params.rsaPublicExponent);
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(spec);
            return kpg.generateKeyPair();
        }
        return null;
    }

    private static byte[] createApplicationId(Context ctx, int uid) {
        final boolean isSelf = (uid == android.os.Process.myUid());
        if (isSelf) {
            byte[] cached = sCachedSelfAppId;
            if (cached != null) return cached;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm == null) return null;
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null || packages.length == 0) return null;

            ASN1EncodableVector packageInfoVector = new ASN1EncodableVector();
            LinkedHashSet<ByteBuffer> digests = new LinkedHashSet<>();
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            for (int i = 0; i < packages.length; i++) {
                String name = packages[i];
                PackageInfo info = null;
                try {
                    info = pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES);
                } catch (Throwable ignored) {
                }
                if (info == null) {
                    try {
                        info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES);
                    } catch (Throwable ignored) {
                    }
                }
                if (info == null) continue;

                ASN1Encodable[] arr = new ASN1Encodable[2];
                arr[0] = new DEROctetString(name.getBytes(StandardCharsets.UTF_8));
                arr[1] = new ASN1Integer(info.getLongVersionCode());
                packageInfoVector.add(new DERSequence(arr));

                android.content.pm.Signature[] sigs = null;
                if (info.signingInfo != null) {
                    sigs = info.signingInfo.hasMultipleSigners()
                            ? info.signingInfo.getApkContentsSigners()
                            : info.signingInfo.getSigningCertificateHistory();
                }
                if (sigs == null || sigs.length == 0) {
                    sigs = info.signatures;
                }
                if (sigs != null) {
                    for (android.content.pm.Signature s : sigs) {
                        if (s != null) {
                            digests.add(ByteBuffer.wrap(md.digest(s.toByteArray())));
                        }
                    }
                }
            }
            if (packageInfoVector.size() == 0) return null;

            ASN1EncodableVector signaturesVector = new ASN1EncodableVector();
            for (ByteBuffer d : digests) {
                signaturesVector.add(new DEROctetString(d.array()));
            }

            ASN1Encodable[] appIdAA = new ASN1Encodable[2];
            appIdAA[0] = new DERSet(packageInfoVector);
            appIdAA[1] = new DERSet(signaturesVector);
            byte[] encoded = new DERSequence(appIdAA).getEncoded();
            if (isSelf) {
                sCachedSelfAppId = encoded;
            }
            return encoded;
        } catch (Throwable t) {
            Log.w(TAG, "createApplicationId failed", t);
            return null;
        }
    }

    private static KeyEntryResponse buildResponse(
            IKeystoreSecurityLevel level,
            KeyDescriptor descriptor,
            SynthesisRequest req,
            Certificate[] chain) {
        try {
            KeyEntryResponse response = new KeyEntryResponse();
            KeyMetadata metadata = new KeyMetadata();
            metadata.keySecurityLevel = req.securityLevel;
            metadata.certificate = chain[0].getEncoded();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (int i = 1; i < chain.length; i++) {
                baos.write(chain[i].getEncoded());
            }
            metadata.certificateChain = baos.toByteArray();

            KeyDescriptor d = new KeyDescriptor();
            d.domain = descriptor.domain;
            d.nspace = descriptor.nspace;
            d.alias = descriptor.alias;
            metadata.key = d;

            List<Authorization> authorizations = new ArrayList<>();
            Authorization a;

            if (req.purpose != null) {
                for (Integer p : req.purpose) {
                    a = new Authorization();
                    a.keyParameter = new KeyParameter();
                    a.keyParameter.tag = Tag.PURPOSE;
                    a.keyParameter.value = KeyParameterValue.keyPurpose(p);
                    a.securityLevel = req.securityLevel;
                    authorizations.add(a);
                }
            }

            if (req.digest != null) {
                for (Integer dig : req.digest) {
                    a = new Authorization();
                    a.keyParameter = new KeyParameter();
                    a.keyParameter.tag = Tag.DIGEST;
                    a.keyParameter.value = KeyParameterValue.digest(dig);
                    a.securityLevel = req.securityLevel;
                    authorizations.add(a);
                }
            }

            if (req.mgfDigest != null) {
                for (Integer dig : req.mgfDigest) {
                    a = new Authorization();
                    a.keyParameter = new KeyParameter();
                    a.keyParameter.tag = Tag.RSA_OAEP_MGF_DIGEST;
                    a.keyParameter.value = KeyParameterValue.digest(dig);
                    a.securityLevel = req.securityLevel;
                    authorizations.add(a);
                }
            }

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.ALGORITHM;
            a.keyParameter.value = KeyParameterValue.algorithm(req.algorithm);
            a.securityLevel = req.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.KEY_SIZE;
            a.keyParameter.value = KeyParameterValue.integer(req.keySize);
            a.securityLevel = req.securityLevel;
            authorizations.add(a);

            if (req.algorithm == Algorithm.EC) {
                a = new Authorization();
                a.keyParameter = new KeyParameter();
                a.keyParameter.tag = Tag.EC_CURVE;
                a.keyParameter.value = KeyParameterValue.ecCurve(req.ecCurve);
                a.securityLevel = req.securityLevel;
                authorizations.add(a);
            }

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.NO_AUTH_REQUIRED;
            a.keyParameter.value = KeyParameterValue.boolValue(true);
            a.securityLevel = req.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.ORIGIN;
            a.keyParameter.value = KeyParameterValue.origin(KeyOrigin.GENERATED);
            a.securityLevel = req.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.OS_VERSION;
            a.keyParameter.value = KeyParameterValue.integer(req.osVersion);
            a.securityLevel = req.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.OS_PATCHLEVEL;
            a.keyParameter.value = KeyParameterValue.integer(req.osPatchLevel);
            a.securityLevel = req.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.VENDOR_PATCHLEVEL;
            a.keyParameter.value = KeyParameterValue.integer(req.vendorPatchLevel);
            a.securityLevel = req.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.BOOT_PATCHLEVEL;
            a.keyParameter.value = KeyParameterValue.integer(req.bootPatchLevel);
            a.securityLevel = req.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.CREATION_DATETIME;
            a.keyParameter.value = KeyParameterValue.longInteger(req.creationDateTime);
            a.securityLevel = req.securityLevel;
            authorizations.add(a);

            a = new Authorization();
            a.keyParameter = new KeyParameter();
            a.keyParameter.tag = Tag.USER_ID;
            a.keyParameter.value = KeyParameterValue.integer(UserHandle.myUserId());
            a.securityLevel = req.securityLevel;
            authorizations.add(a);

            metadata.authorizations = authorizations.toArray(new Authorization[0]);
            metadata.modificationTimeMs = System.currentTimeMillis();
            response.metadata = metadata;
            response.iSecurityLevel = level;
            return response;
        } catch (Throwable t) {
            Log.e(TAG, "buildResponse failed", t);
            return null;
        }
    }
}

