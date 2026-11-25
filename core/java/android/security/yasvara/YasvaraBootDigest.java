/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import java.util.Arrays;

/**
 * Wire contract for the measured boot digest shared by framework, keystore2 and libc.
 *
 * <p>The system service publishes one read-only value per boot. Profile settings, rewritten
 * certificates and data retained across an OTA are not measurements of the running slot.
 *
 * @hide
 */
public final class YasvaraBootDigest {
    public static final String PROPERTY = "ro.yasvara.boot.digest";
    public static final String SOURCE_PROPERTY = "ro.yasvara.boot.source";
    public static final String PROBE_ALIAS = "YasvaraBootDigest";

    private YasvaraBootDigest() {}

    /** Returns true if the array is null, empty, or consists entirely of 0x00 bytes. */
    public static boolean isAllZero(byte[] value) {
        if (value == null || value.length == 0) return true;
        int acc = 0;
        for (byte b : value) {
            acc |= (b & 0xff);
        }
        return acc == 0;
    }

    /**
     * Canonical values are exactly lowercase SHA-256 or SHA-512 hex, without separators,
     * and must not be all-zero (00000000...).
     */
    public static byte[] decodeCanonical(String value) {
        if (value == null || (value.length() != 64 && value.length() != 128)) return null;
        byte[] out = new byte[value.length() / 2];
        int nonZero = 0;
        for (int i = 0; i < out.length; i++) {
            int high = nibble(value.charAt(2 * i));
            int low = nibble(value.charAt(2 * i + 1));
            if (high < 0 || low < 0) return null;
            int b = (high << 4) | low;
            nonZero |= b;
            out[i] = (byte) b;
        }
        return nonZero != 0 ? out : null;
    }

    /** Normalizes external property input; consumers must use decodeCanonical instead. */
    public static String normalize(String value) {
        if (value == null || value.length() > 512) return null;
        final int len = value.length();
        // Zero-allocation fast-path when input is already canonical lowercase 64/128 hex
        if (len == 64 || len == 128) {
            int nonZero = 0;
            boolean canonical = true;
            for (int i = 0; i < len; i++) {
                int n = nibble(value.charAt(i));
                if (n < 0) {
                    canonical = false;
                    break;
                }
                nonZero |= n;
            }
            if (canonical) {
                return nonZero != 0 ? value : null;
            }
        }

        StringBuilder out = new StringBuilder(128);
        int nonZero = 0;
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (c == ':' || Character.isWhitespace(c)) continue;
            if (c >= 'A' && c <= 'F') c += 'a' - 'A';
            int n = nibble(c);
            if (n < 0 || out.length() == 128) return null;
            nonZero |= n;
            out.append(c);
        }
        int outLen = out.length();
        if ((outLen != 64 && outLen != 128) || nonZero == 0) return null;
        return out.toString();
    }

    public static String encode(byte[] value) {
        if (value == null || (value.length != 32 && value.length != 64) || isAllZero(value)) {
            return null;
        }
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            out[2 * i] = digits[(value[i] & 0xff) >>> 4];
            out[2 * i + 1] = digits[value[i] & 0xf];
        }
        return new String(out);
    }

    /** Returns no selection on conflicting measurements, rather than concealing a mismatch. */
    public static Selection select(String bootloaderValue, byte[] attestedHash) {
        String bootloader = normalize(bootloaderValue);
        String attested = encode(attestedHash);
        if (bootloader != null && attested != null && !bootloader.equals(attested)) return null;
        if (bootloader != null) return new Selection(bootloader, "bootloader");
        return attested == null ? null : new Selection(attested, "tee");
    }

    public static boolean matches(String canonical, byte[] attestedHash) {
        byte[] decoded = decodeCanonical(canonical);
        return decoded != null && Arrays.equals(decoded, attestedHash);
    }

    public static final class Selection {
        public final String digest;
        public final String source;

        private Selection(String digest, String source) {
            this.digest = digest;
            this.source = source;
        }
    }

    private static int nibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return -1;
    }
}
