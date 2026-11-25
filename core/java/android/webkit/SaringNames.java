/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.webkit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads the name table netd filters lookups with (vendor/yasvara/saring), for requests a
 * WebView makes. Same hashing and the same verdicts; lookups allocate nothing.
 *
 * @hide
 */
public final class SaringNames {
    private static final int HEADER = 32;
    private static final int MAGIC = 0x474e5253; // "SRNG"
    private static final int MAX_LEVELS = 16;
    private static final int DENY = 1;
    private static final int ALLOW = 1 << 1;
    private static final int BLOCK = 1 << 2;
    private static final int THIRD = 1 << 3;
    private static final int POPUP = 1 << 4;
    /** Second-level labels that registries hand out under a country code. */
    private static final String[] SECOND_LEVEL = {"co", "com", "net", "org", "ac", "go", "gov",
            "edu", "or", "web", "sch", "my", "biz", "mil"};

    private final ByteBuffer mTable;
    private final int mBits;
    private final int mEntriesAt;
    private final int mCount;

    private SaringNames(ByteBuffer table, int bits, int entriesAt, int count) {
        mTable = table;
        mBits = bits;
        mEntriesAt = entriesAt;
        mCount = count;
    }

    /** Returns null for anything that is not a whole table. */
    public static SaringNames open(ByteBuffer blob) {
        final ByteBuffer table = blob.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (table.capacity() < HEADER || table.getInt(0) != MAGIC || table.getInt(4) != 1) {
            return null;
        }
        final int bits = table.getInt(8);
        final int count = table.getInt(12);
        if (bits < 1 || bits > 24 || count < 0) return null;
        final long entriesAt = (HEADER + ((1L << bits) + 1) * 4 + 7) & ~7L;
        if (table.capacity() != entriesAt + count * 8L) return null;
        return new SaringNames(table, bits, (int) entriesAt, count);
    }

    private int probe(long key, boolean whole) {
        final int bucket = HEADER + (int) (key >>> (64 - mBits)) * 4;
        int at = mTable.getInt(bucket);
        final int end = Math.min(mTable.getInt(bucket + 4), mCount);
        int mask = 0;
        for (; at >= 0 && at < end; at++) {
            final long e = mTable.getLong(mEntriesAt + at * 8);
            final long base = e & ~0xFL;
            if (base == key) {
                if (whole || (e & 1) != 0) mask |= 1 << ((e >>> 1) & 7);
            } else if (Long.compareUnsigned(base, key) > 0) {
                break;
            }
        }
        return mask;
    }

    /** Bit per kind that has a rule for the host in {@code s[start, end)} or a parent of it. */
    private int mask(String s, int start, int end) {
        if (end > start && s.charAt(end - 1) == '.') end--;
        if (end <= start || end - start > 253) return 0;
        long h = 0;
        int mask = 0;
        int labelEnd = end;
        for (int level = 0; level < MAX_LEVELS; level++) {
            int labelStart = s.lastIndexOf('.', labelEnd - 1) + 1;
            if (labelStart < start) labelStart = start;
            if (labelStart == labelEnd) return 0;
            long x = h ^ 0xcbf29ce484222325L;
            for (int i = labelStart; i < labelEnd; i++) {
                char c = s.charAt(i);
                if (c > 0x7f) return 0;
                if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
                x = (x ^ c) * 0x100000001b3L;
            }
            x ^= x >>> 32;
            x *= 0xd6e8feb86659fd93L;
            h = x ^ (x >>> 32);
            mask |= probe(h & ~0xFL, labelStart == start);
            if (labelStart == start) break;
            labelEnd = labelStart - 1;
        }
        return mask;
    }

    /** Offsets of the host of an http(s) or ws(s) URL packed as start << 32 | end, or -1. */
    private static long hostOf(String url) {
        final int scheme = url.indexOf("://");
        if (scheme < 2 || scheme > 5) return -1;
        final char first = Character.toLowerCase(url.charAt(0));
        if (first != 'h' && first != 'w') return -1;
        final int start = scheme + 3;
        int end = url.length();
        for (int i = start; i < end; i++) {
            final char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        int hostStart = url.lastIndexOf('@', end - 1) + 1;
        if (hostStart < start) hostStart = start;
        if (hostStart < end && url.charAt(hostStart) == '[') return -1;
        final int port = url.indexOf(':', hostStart);
        if (port >= 0 && port < end) end = port;
        return hostStart < end ? (long) hostStart << 32 | end : -1;
    }

    /** Where the registrable part of the host in {@code s[start, end)} begins. */
    private static int siteStart(String s, int start, int end) {
        final int last = s.lastIndexOf('.', end - 1);
        if (last <= start) return start;
        final int second = s.lastIndexOf('.', last - 1);
        if (second < start) return start;
        if (end - last - 1 == 2) {
            for (String sld : SECOND_LEVEL) {
                if (sld.length() == last - second - 1
                        && s.regionMatches(true, second + 1, sld, 0, sld.length())) {
                    final int third = s.lastIndexOf('.', second - 1);
                    return third < start ? start : third + 1;
                }
            }
        }
        return second + 1;
    }

    /**
     * Whether a request for {@code url} made by a page on {@code pageHost} must be refused.
     * {@code navigation} is a new window or a top-level load.
     */
    public boolean requestBlocked(String url, String pageHost, boolean navigation) {
        final long host = hostOf(url);
        if (host < 0) return false;
        final int start = (int) (host >>> 32);
        int end = (int) host;
        final int mask = mask(url, start, end);
        if ((mask & DENY) != 0) return true;
        if ((mask & ALLOW) != 0) return false;
        if ((mask & BLOCK) != 0) return true;
        if (navigation && (mask & POPUP) != 0) return true;
        if ((mask & THIRD) == 0 || pageHost == null) return false;
        if (url.charAt(end - 1) == '.') end--;
        int pageEnd = pageHost.length();
        if (pageEnd > 0 && pageHost.charAt(pageEnd - 1) == '.') pageEnd--;
        final int site = siteStart(url, start, end);
        final int pageSite = siteStart(pageHost, 0, pageEnd);
        return end - site != pageEnd - pageSite
                || !url.regionMatches(true, site, pageHost, pageSite, end - site);
    }

    /** The host of {@code url}, or null. */
    public static String host(String url) {
        final long host = url == null ? -1 : hostOf(url);
        return host < 0 ? null : url.substring((int) (host >>> 32), (int) host);
    }

    private static final String[] TRACKING_KEYS = {
            "ved", "ei", "oq", "gs_lcrp", "gs_lp", "sclient", "uact",
            "gclid", "gclsrc", "dclid", "fbclid", "igshid", "mc_eid",
            "msclkid", "twclid", "ttclid", "yclid", "_hsmi",
    };

    private static boolean isTrackingKey(String s, int start, int end) {
        final int len = end - start;
        if (len >= 4 && s.regionMatches(true, start, "utm_", 0, 4)) return true;
        for (String key : TRACKING_KEYS) {
            if (len == key.length() && s.regionMatches(true, start, key, 0, len)) return true;
        }
        return false;
    }

    private static boolean isGoogleHost(String url, int start, int end) {
        if (end > start && url.charAt(end - 1) == '.') end--;
        if (end - start > 4 && url.regionMatches(true, start, "www.", 0, 4)) start += 4;
        if (end - start <= 7 || !url.regionMatches(true, start, "google.", 0, 7)) return false;
        final int tldLen = end - (start + 7);
        if (tldLen == 0 || tldLen > 10) return false;
        for (int i = start + 7; i < end; i++) {
            final char c = url.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.')) return false;
        }
        return true;
    }

    /**
     * Unwraps Google click-tracker redirects ({@code google.<tld>/url?q=...}) and strips tracking
     * query parameters. Mirrors {@code vendor/yasvara/saring/src/clean.rs}. Returns null when the
     * URL is already clean (zero allocations on the common path).
     */
    public static String cleanUrl(String url) {
        if (url == null) return null;
        final long host = hostOf(url);
        if (host < 0) return null;
        String work = url;
        boolean unwrapped = false;
        final int q0 = url.indexOf('?');
        if (q0 > 4 && isGoogleHost(url, (int) (host >>> 32), (int) host)
                && url.regionMatches(q0 - 4, "/url", 0, 4)) {
            final int frag0 = url.indexOf('#', q0 + 1);
            final int qEnd = frag0 >= 0 ? frag0 : url.length();
            int pos = q0 + 1;
            while (pos < qEnd) {
                int amp = url.indexOf('&', pos);
                if (amp < 0 || amp > qEnd) amp = qEnd;
                final int eq = url.indexOf('=', pos);
                if (eq > pos && eq < amp) {
                    final int kLen = eq - pos;
                    if ((kLen == 1 && (url.charAt(pos) == 'q' || url.charAt(pos) == 'Q'))
                            || (kLen == 3 && url.regionMatches(true, pos, "url", 0, 3))) {
                        final String decoded = android.net.Uri.decode(url.substring(eq + 1, amp));
                        if (decoded.regionMatches(true, 0, "https://", 0, 8)
                                || decoded.regionMatches(true, 0, "http://", 0, 7)) {
                            work = decoded;
                            unwrapped = true;
                            break;
                        }
                    }
                }
                pos = amp + 1;
            }
        }

        final int qPos = work.indexOf('?');
        if (qPos < 0) return unwrapped ? work : null;
        final int fragPos = work.indexOf('#', qPos + 1);
        final int qEnd = fragPos >= 0 ? fragPos : work.length();

        boolean anyStripped = false;
        int pos = qPos + 1;
        while (pos < qEnd) {
            int amp = work.indexOf('&', pos);
            if (amp < 0 || amp > qEnd) amp = qEnd;
            int eq = work.indexOf('=', pos);
            if (eq < 0 || eq > amp) eq = amp;
            if (isTrackingKey(work, pos, eq)) {
                anyStripped = true;
                break;
            }
            pos = amp + 1;
        }
        if (!anyStripped) return unwrapped ? work : null;

        final StringBuilder sb = new StringBuilder(work.length());
        sb.append(work, 0, qPos);
        boolean kept = false;
        pos = qPos + 1;
        while (pos < qEnd) {
            int amp = work.indexOf('&', pos);
            if (amp < 0 || amp > qEnd) amp = qEnd;
            if (amp > pos) {
                int eq = work.indexOf('=', pos);
                if (eq < 0 || eq > amp) eq = amp;
                if (!isTrackingKey(work, pos, eq)) {
                    sb.append(kept ? '&' : '?').append(work, pos, amp);
                    kept = true;
                }
            }
            pos = amp + 1;
        }
        if (fragPos >= 0) sb.append(work, fragPos, work.length());
        return sb.toString();
    }
}
