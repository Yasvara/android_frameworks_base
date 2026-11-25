/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.ASN1InputStream;
import com.android.internal.org.bouncycastle.asn1.ASN1ObjectIdentifier;
import com.android.internal.org.bouncycastle.asn1.ASN1Primitive;
import com.android.internal.org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import com.android.internal.org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import com.android.internal.org.bouncycastle.asn1.sec.ECPrivateKey;
import com.android.internal.org.bouncycastle.asn1.x9.ECNamedCurveTable;
import com.android.internal.org.bouncycastle.asn1.x9.X9ECParameters;
import com.android.internal.org.bouncycastle.crypto.params.ECDomainParameters;
import com.android.internal.org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.ec.KeyFactorySpi.EC;
import com.android.internal.org.bouncycastle.jcajce.provider.asymmetric.rsa.KeyFactorySpi;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a keybox {@code <PrivateKey>} PEM into a {@link PrivateKey} that can re-sign a leaf.
 *
 * <p>Accepts SEC-1 "EC PRIVATE KEY", PKCS#1 "RSA PRIVATE KEY" and PKCS#8 "PRIVATE KEY".
 * Returns null (never throws) when the PEM is missing or unparsable — the ladder then
 * falls back to the original chain instead of breaking the app.
 *
 * @hide
 */
public final class YasvaraKeyMaterial {
    private static final String TAG = "YasvaraKeyMaterial";

    private static final Pattern PEM_HEADER = Pattern.compile("-----BEGIN ([^-]+)-----");
    private static final Pattern PEM_ARMOUR =
            Pattern.compile("-----(BEGIN|END) [^-]+-----|\\s+");

    private YasvaraKeyMaterial() {}

    /** Parses {@code pem} for {@code algorithm} ("ecdsa" or "rsa"); null when unusable. */
    public static PrivateKey toPrivateKey(String pem, String algorithm) {
        if (pem == null || pem.trim().isEmpty()) return null;
        try {
            String type = detectPemType(pem);
            byte[] der = android.util.Base64.decode(
                    PEM_ARMOUR.matcher(pem).replaceAll(""), android.util.Base64.DEFAULT);
            boolean wantRsa = "rsa".equalsIgnoreCase(algorithm);
            if ("EC PRIVATE KEY".equals(type) || (!wantRsa && type == null)) {
                return parseEc(der);
            }
            if ("RSA PRIVATE KEY".equals(type) || (wantRsa && type == null)) {
                return parseRsa(der);
            }
            if ("PRIVATE KEY".equals(type) || "ENCRYPTED PRIVATE KEY".equals(type)) {
                PrivateKeyInfo pkInfo = PrivateKeyInfo.getInstance(der);
                if (wantRsa) return new KeyFactorySpi().generatePrivate(pkInfo);
                return new EC().generatePrivate(pkInfo);
            }
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return KeyFactory.getInstance(wantRsa ? "RSA" : "EC").generatePrivate(spec);
        } catch (Throwable t) {
            Log.w(TAG, "unparsable private key for " + algorithm, t);
            return null;
        }
    }

    private static PrivateKey parseEc(byte[] der) throws Exception {
        try (ASN1InputStream in = new ASN1InputStream(der)) {
            ASN1Primitive asn1 = in.readObject();
            try {
                ECPrivateKey ec = ECPrivateKey.getInstance(asn1);
                PrivateKeyInfo pki = new PrivateKeyInfo(
                        new com.android.internal.org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                com.android.internal.org.bouncycastle.asn1.x9.X9ObjectIdentifiers.id_ecPublicKey,
                                ec.getParameters()),
                        ec);
                return KeyFactory.getInstance("EC").generatePrivate(
                        new PKCS8EncodedKeySpec(pki.getEncoded()));
            } catch (Throwable ignored) {
                return new EC().generatePrivate(PrivateKeyInfo.getInstance(asn1));
            }
        }
    }

    private static PrivateKey parseRsa(byte[] der) throws Exception {
        try (ASN1InputStream in = new ASN1InputStream(der)) {
            ASN1Primitive asn1 = in.readObject();
            try {
                RSAPrivateKey rsa = RSAPrivateKey.getInstance(asn1);
                RSAPrivateCrtKeySpec spec = new RSAPrivateCrtKeySpec(
                        rsa.getModulus(), rsa.getPublicExponent(), rsa.getPrivateExponent(),
                        rsa.getPrime1(), rsa.getPrime2(), rsa.getExponent1(),
                        rsa.getExponent2(), rsa.getCoefficient());
                return KeyFactory.getInstance("RSA").generatePrivate(spec);
            } catch (Throwable ignored) {
                return new KeyFactorySpi().generatePrivate(PrivateKeyInfo.getInstance(asn1));
            }
        }
    }

    private static String detectPemType(String pem) {
        Matcher m = PEM_HEADER.matcher(pem);
        return m.find() ? m.group(1).trim().toUpperCase(java.util.Locale.ROOT) : null;
    }
}
