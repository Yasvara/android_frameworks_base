/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.x500.X500Name;
import com.android.internal.org.bouncycastle.cert.X509CertificateHolder;

import java.io.ByteArrayInputStream;
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

    /**
     * One {@code <Key>}: its algorithm ("ecdsa" or "rsa"), certificate chain (leaf first) and the
     * PEM of its private key, which is null when the keybox was stripped of it.
     */
    public static final class Keybox {
        public final String algorithm;
        public final List<X509Certificate> chain;
        public final String privateKeyPem;
        private volatile X500Name mIssuerName;

        Keybox(String algorithm, List<X509Certificate> chain, String privateKeyPem) {
            this.algorithm = algorithm;
            this.chain = Collections.unmodifiableList(chain);
            this.privateKeyPem = privateKeyPem;
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
}
