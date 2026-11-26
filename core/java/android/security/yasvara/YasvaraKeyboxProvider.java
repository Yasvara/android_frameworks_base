/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1OctetString;
import com.android.internal.org.bouncycastle.asn1.ASN1Primitive;
import com.android.internal.org.bouncycastle.asn1.x500.X500Name;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Google {@code keybox.xml} files. Pure parsing: nothing here reads or stores a keybox.
 * The keybox itself lives in system_server ({@link YasvaraAttestationService}); Laras uses this
 * class to validate an import and to describe the public certificate chain.
 *
 * <p>Format (Google keybox):
 * <pre>{@code
 * <AndroidAttestation>
 *   <NumberOfKeyboxes>1</NumberOfKeyboxes>
 *   <Keybox DeviceID="...">
 *     <Key algorithm="ecdsa">
 *       <PrivateKey format="pem">...</PrivateKey>
 *       <CertificateChain>
 *         <NumberOfCertificates>3</NumberOfCertificates>
 *         <Certificate format="pem">-----BEGIN CERTIFICATE-----...</Certificate>
 *       </CertificateChain>
 *     </Key>
 *   </Keybox>
 * </AndroidAttestation>
 * }</pre>
 * Bare base64 certificates (no PEM armour) are accepted too.
 *
 * @hide
 */
public final class YasvaraKeyboxProvider {
    private static final String TAG = "YasvaraKeybox";

    /** Why {@link #parse} produced no usable keybox. */
    public static final int ERROR_NONE = 0;
    public static final int ERROR_EMPTY = 1;
    public static final int ERROR_NOT_XML = 2;
    public static final int ERROR_NO_CERTIFICATE = 3;
    public static final int ERROR_BAD_CERTIFICATE = 4;
    public static final int ERROR_CHAIN_TOO_SHORT = 5;
    public static final int ERROR_INVALID_KEYBOX = 6;

    /** RKP ProvisioningInfo extension OID carrying CBOR map with key 3 = manufacturer. */
    public static final String PROVISIONING_INFO_OID = "1.3.6.1.4.1.11129.2.1.30";

    private static final int MAX_PROVISIONING_BYTES = 64 * 1024;
    private static final int MAX_CBOR_DEPTH = 32;
    private static final int MAX_PROP_VALUE_BYTES = 91;

    /**
     * One {@code <Key>}: its algorithm ("ecdsa" or "rsa"), certificate chain (leaf first), the
     * PEM of its private key (null when stripped), and the RKP provisioning manufacturer if any.
     */
    public static final class Keybox {
        public final String algorithm;
        public final List<X509Certificate> chain;
        public final String privateKeyPem;
        public final String rkpManufacturer;
        private volatile X500Name mIssuerName;

        Keybox(String algorithm, List<X509Certificate> chain, String privateKeyPem) {
            this.algorithm = algorithm;
            this.chain = Collections.unmodifiableList(chain);
            this.privateKeyPem = privateKeyPem;
            this.rkpManufacturer = extractRkpManufacturer(this.chain);
        }

        public boolean hasPrivateKey() {
            return privateKeyPem != null;
        }

        public X509Certificate leaf() {
            return chain.get(0);
        }

        /** Cached issuer subject; parsing the leaf DER on every patch is avoidable. */
        public X500Name issuerName() {
            X500Name name = mIssuerName;
            if (name != null) return name;
            try {
                name = new X509CertificateHolder(leaf().getEncoded()).getSubject();
                mIssuerName = name;
            } catch (Throwable t) {
                Log.w(TAG, "cannot read keybox issuer", t);
            }
            return name;
        }
    }

    /** Result of {@link #parse}: the keyboxes found, or the reason there are none. */
    public static final class ParseResult {
        public final List<Keybox> keyboxes;
        public final int error;

        ParseResult(List<Keybox> keyboxes, int error) {
            this.keyboxes = Collections.unmodifiableList(keyboxes);
            this.error = error;
        }

        public boolean isValid() {
            return error == ERROR_NONE && !keyboxes.isEmpty();
        }
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern KEY_BLOCK =
            Pattern.compile("<Key\\b([^>]*)>(.*?)</Key\\s*>", FLAGS);
    private static final Pattern CERT_BLOCK =
            Pattern.compile("<Certificate\\b[^>]*>(.*?)</Certificate\\s*>", FLAGS);
    private static final Pattern ALGORITHM_ATTR =
            Pattern.compile("algorithm\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE_KEY =
            Pattern.compile("[ \\t]*<PrivateKey\\b[^>]*>.*?</PrivateKey\\s*>[ \\t]*\\r?\\n?", FLAGS);
    private static final Pattern PRIVATE_KEY_BODY =
            Pattern.compile("<PrivateKey\\b[^>]*>(.*?)</PrivateKey\\s*>", FLAGS);
    private static final Pattern PEM_ARMOUR =
            Pattern.compile("-----(BEGIN|END) CERTIFICATE-----|\\s+");

    private static final SecureRandom RANDOM = new SecureRandom();

    private YasvaraKeyboxProvider() {}

    /** Removes every {@code <PrivateKey>} element; the engine only needs the public chain. */
    public static String stripPrivateKeys(String xml) {
        return PRIVATE_KEY.matcher(xml).replaceAll("");
    }

    /**
     * Parses raw XML or base64(XML). Never throws; a keybox with a single certificate is
     * reported as {@link #ERROR_CHAIN_TOO_SHORT} because it cannot chain to a root.
     */
    public static ParseResult parse(String raw) {
        List<Keybox> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw) || raw.trim().isEmpty()) return new ParseResult(out, ERROR_EMPTY);
        String xml;
        try {
            xml = raw.trim().startsWith("<") ? raw
                    : new String(Base64.decode(raw.trim(), Base64.DEFAULT), "UTF-8");
        } catch (Throwable t) {
            return new ParseResult(out, ERROR_NOT_XML);
        }
        if (!xml.trim().startsWith("<") && !xml.contains("<")) {
            return new ParseResult(out, ERROR_NOT_XML);
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            int error = ERROR_NO_CERTIFICATE;
            Matcher keys = KEY_BLOCK.matcher(xml);
            boolean any = false;
            while (keys.find()) {
                any = true;
                error = collect(cf, algorithmOf(keys.group(1)), keys.group(2), out, error);
            }
            // Legacy / hand-edited files without <Key>: treat the whole document as one key.
            if (!any) error = collect(cf, "ecdsa", xml, out, error);
            return new ParseResult(out, out.isEmpty() ? error : ERROR_NONE);
        } catch (Throwable t) {
            Log.w(TAG, "parse failed", t);
            return new ParseResult(out, ERROR_BAD_CERTIFICATE);
        }
    }

    private static String algorithmOf(String attributes) {
        Matcher m = ALGORITHM_ATTR.matcher(attributes);
        if (m.find() && m.group(1).toLowerCase(Locale.ROOT).startsWith("rsa")) return "rsa";
        return "ecdsa";
    }

    private static int collect(CertificateFactory cf, String algorithm, String block,
            List<Keybox> out, int error) {
        List<X509Certificate> chain = new ArrayList<>();
        Matcher certs = CERT_BLOCK.matcher(block);
        boolean sawCertificate = false;
        boolean bad = false;
        while (certs.find()) {
            sawCertificate = true;
            try {
                byte[] der = Base64.decode(PEM_ARMOUR.matcher(certs.group(1)).replaceAll(""),
                        Base64.DEFAULT);
                chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            } catch (Throwable t) {
                bad = true;
            }
        }
        if (bad) return ERROR_BAD_CERTIFICATE;
        if (chain.size() >= 2) {
            Matcher key = PRIVATE_KEY_BODY.matcher(block);
            String pem = key.find() && !key.group(1).trim().isEmpty() ? key.group(1).trim() : null;
            try {
                if (!algorithmMatches(algorithm, chain.get(0).getPublicKey().getAlgorithm())) {
                    throw new IllegalArgumentException("certificate algorithm differs from keybox");
                }
                validateChain(chain);
                if (pem != null) {
                    PrivateKey priv = YasvaraKeyMaterial.toPrivateKey(pem, algorithm);
                    if (priv == null
                            || !algorithmMatches(algorithm, priv.getAlgorithm())) {
                        throw new IllegalArgumentException("unusable private key");
                    }
                    verifyPrivateKey(priv, chain.get(0).getPublicKey(), algorithm);
                }
            } catch (Throwable t) {
                Log.w(TAG, "invalid keybox for " + algorithm, t);
                return ERROR_INVALID_KEYBOX;
            }
            out.add(new Keybox(algorithm, chain, pem));
            return ERROR_NONE;
        }
        return sawCertificate ? ERROR_CHAIN_TOO_SHORT : error;
    }

    private static boolean algorithmMatches(String algorithm, String keyAlgorithm) {
        boolean wantRsa = "rsa".equalsIgnoreCase(algorithm);
        boolean isRsa = "RSA".equalsIgnoreCase(keyAlgorithm);
        return wantRsa == isRsa;
    }

    /**
     * Each certificate must be issued by the next one. Validity dates are deliberately
     * not enforced so an expired keybox keeps loading.
     */
    private static void validateChain(List<X509Certificate> chain) throws Exception {
        for (int i = 0; i + 1 < chain.size(); i++) {
            X509Certificate certificate = chain.get(i);
            X509Certificate issuer = chain.get(i + 1);
            if (!certificate.getIssuerX500Principal()
                    .equals(issuer.getSubjectX500Principal())) {
                throw new java.security.cert.CertificateException(
                        "issuer does not match next certificate at index " + i);
            }
            certificate.verify(issuer.getPublicKey());
        }
        X509Certificate last = chain.get(chain.size() - 1);
        if (last.getIssuerX500Principal().equals(last.getSubjectX500Principal())) {
            last.verify(last.getPublicKey());
        }
    }

    /** A parsed private key is not necessarily the key for the first certificate. */
    private static void verifyPrivateKey(PrivateKey privateKey, PublicKey publicKey,
            String algorithm) throws Exception {
        byte[] challenge = new byte[32];
        RANDOM.nextBytes(challenge);
        String sigAlg = "rsa".equalsIgnoreCase(algorithm) ? "SHA256withRSA" : "SHA256withECDSA";
        Signature signer = Signature.getInstance(sigAlg);
        signer.initSign(privateKey);
        signer.update(challenge);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance(sigAlg);
        verifier.initVerify(publicKey);
        verifier.update(challenge);
        if (verifier.verify(signature)) return;
        throw new java.security.InvalidKeyException(
                "private key does not correspond to keybox certificate");
    }

    /**
     * Returns the RKP ProvisioningInfo manufacturer (CBOR key 3) across parsed keyboxes,
     * preferring the ECDSA entry since RKP provisioning uses EC keys. Returns {@code ""}.
     */
    public static String rkpManufacturer(List<Keybox> keyboxes) {
        if (keyboxes == null || keyboxes.isEmpty()) return "";
        String fallback = null;
        for (Keybox kb : keyboxes) {
            if (kb == null || TextUtils.isEmpty(kb.rkpManufacturer)) continue;
            if ("ecdsa".equalsIgnoreCase(kb.algorithm)) return kb.rkpManufacturer;
            if (fallback == null) fallback = kb.rkpManufacturer;
        }
        return fallback != null ? fallback : "";
    }

    /** Extracts CBOR map key 3 from OID 1.3.6.1.4.1.11129.2.1.30 on the keybox chain. */
    public static String extractRkpManufacturer(List<X509Certificate> chain) {
        if (chain == null || chain.isEmpty()) return null;
        for (X509Certificate cert : chain) {
            if (cert == null) continue;
            try {
                byte[] extBytes = cert.getExtensionValue(PROVISIONING_INFO_OID);
                if (extBytes == null || extBytes.length > MAX_PROVISIONING_BYTES) continue;
                ASN1OctetString octets = ASN1OctetString.getInstance(
                        ASN1Primitive.fromByteArray(extBytes));
                if (octets == null) continue;
                String mfg = parseProvisioningManufacturer(octets.getOctets());
                if (mfg != null) return mfg;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Parses top-level CBOR map key 3 (UTF-8 text string) from ProvisioningInfo payload. */
    static String parseProvisioningManufacturer(byte[] cbor) {
        if (cbor == null || cbor.length == 0 || cbor.length > MAX_PROVISIONING_BYTES) return null;
        CborReader reader = new CborReader(cbor);
        long mapLen = reader.readHeader(5);
        if (mapLen < 0) return null;
        for (long i = 0; i < mapLen; i++) {
            int keyMajor = reader.peekMajorType();
            if (keyMajor < 0) return null;
            if (keyMajor == 0) {
                long key = reader.readHeader(0);
                if (key < 0) return null;
                if (key == 3L) {
                    if (reader.peekMajorType() != 3) return null;
                    long textLen = reader.readHeader(3);
                    if (textLen < 0 || textLen > reader.remaining()) return null;
                    String raw = reader.readUtf8((int) textLen);
                    String trimmed = raw != null ? raw.trim() : "";
                    if (trimmed.isEmpty()
                            || trimmed.getBytes(StandardCharsets.UTF_8).length > MAX_PROP_VALUE_BYTES) {
                        return null;
                    }
                    return trimmed;
                }
            } else if (!reader.skipItem(0)) {
                return null;
            }
            if (!reader.skipItem(0)) return null;
        }
        return null;
    }

    private static final class CborReader {
        private final byte[] buf;
        private int pos;

        CborReader(byte[] buf) {
            this.buf = buf;
        }

        int remaining() {
            return buf.length - pos;
        }

        int peekMajorType() {
            return pos < buf.length ? ((buf[pos] & 0xFF) >>> 5) : -1;
        }

        long readHeader(int expectedMajor) {
            if (pos >= buf.length) return -1;
            int initial = buf[pos++] & 0xFF;
            if ((initial >>> 5) != expectedMajor) return -1;
            return readArg(initial & 0x1F);
        }

        private long readArg(int ai) {
            if (ai < 24) return ai;
            int extraBytes = switch (ai) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                default -> -1; // Indefinite-length (31) and reserved (28..30) rejected.
            };
            if (extraBytes < 0 || extraBytes > remaining()) return -1;
            long val = 0;
            for (int i = 0; i < extraBytes; i++) {
                val = (val << 8) | (buf[pos++] & 0xFFL);
            }
            return val < 0 ? -1 : val;
        }

        String readUtf8(int len) {
            if (len < 0 || len > remaining()) return null;
            String s = new String(buf, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        boolean skipItem(int depth) {
            if (depth > MAX_CBOR_DEPTH || pos >= buf.length) return false;
            int initial = buf[pos++] & 0xFF;
            int major = initial >>> 5;
            int ai = initial & 0x1F;
            if (major == 7) {
                int skip = switch (ai) {
                    case 24 -> 1;
                    case 25 -> 2;
                    case 26 -> 4;
                    case 27 -> 8;
                    default -> (ai < 24) ? 0 : -1;
                };
                if (skip < 0 || skip > remaining()) return false;
                pos += skip;
                return true;
            }
            long arg = readArg(ai);
            if (arg < 0) return false;
            switch (major) {
                case 0:
                case 1:
                    return true;
                case 2:
                case 3:
                    if (arg > remaining()) return false;
                    pos += (int) arg;
                    return true;
                case 4:
                    for (long i = 0; i < arg; i++) {
                        if (!skipItem(depth + 1)) return false;
                    }
                    return true;
                case 5:
                    for (long i = 0; i < arg; i++) {
                        if (!skipItem(depth + 1) || !skipItem(depth + 1)) return false;
                    }
                    return true;
                case 6:
                    return skipItem(depth + 1);
                default:
                    return false;
            }
        }
    }
}
