/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import java.util.Arrays;

/**
 * Thin canonical boot-digest wire contract facade referencing {@link YasvaraIntegrityContract}.
 * Authoritative boot-hash extraction & selection run in Rust (`yasvara_integrity_core`).
 *
 * @hide
 */
public final class YasvaraBootDigest {
    public static final String PROPERTY = YasvaraIntegrityContract.PROP_BOOT_DIGEST;
    public static final String SOURCE_PROPERTY = YasvaraIntegrityContract.PROP_BOOT_SOURCE;
    public static final String PROBE_ALIAS = YasvaraIntegrityContract.BOOT_DIGEST_PROBE_ALIAS;

    private YasvaraBootDigest() {}

    public static boolean isAllZero(byte[] value) {
        if (value == null || value.length == 0) return true;
        int acc = 0;
        for (byte b : value) acc |= (b & 0xff);
        return acc == 0;
    }

    /** Strict canonical check: lowercase non-zero 64/128 hex matching Rust `integrity-core`. */
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

    public static String normalize(String value) {
        if (value == null || value.length() > 512) return null;
        StringBuilder out = new StringBuilder(128);
        int nonZero = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':' || Character.isWhitespace(c)) continue;
            if (c >= 'A' && c <= 'F') c += 'a' - 'A';
            int n = nibble(c);
            if (n < 0 || out.length() == 128) return null;
            nonZero |= n;
            out.append(c);
        }
        int outLen = out.length();
        return ((outLen == 64 || outLen == 128) && nonZero != 0) ? out.toString() : null;
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
