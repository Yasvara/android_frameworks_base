/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Single source of truth for the identity a patched leaf claims.
 *
 * <p>Patch levels come from the active PIF profile ({@code yasvara_pif_config
 * SECURITY_PATCH}), so PIF and attestation can never disagree. Optional per-app override
 * ({@code yasvara_trickystore_patch_override}, {@code {package|"*": "YYYY-MM-DD"}}) wins
 * for that package. The boot hash comes only from the immutable canonical property
 * {@code ro.yasvara.boot.digest}; an unavailable or invalid value prevents patching.
 *
 * @hide
 */
public final class YasvaraIdentity {
    private static final String TAG = "YasvaraIdentity";

    static final String KEY_PIF_CONFIG = "yasvara_pif_config";
    static final String KEY_PATCH_OVERRIDE = "yasvara_trickystore_patch_override";

    private YasvaraIdentity() {}

    /** Patch date the engine should claim for {@code pkg}: override, PIF (collectors), or build. */
    public static String patchDateFor(Context ctx, String pkg) {
        try {
            String override = patchOverrideFor(ctx, pkg);
            if (!TextUtils.isEmpty(override)) return override;
            if (YasvaraSpoofEngine.isCollectorPackage(pkg) && YasvaraSpoofEngine.isPifEnabled(ctx)) {
                java.util.Map<String, String> pif = YasvaraSpoofEngine.getPifConfig(ctx);
                if (pif != null) {
                    String d = pif.get("SECURITY_PATCH");
                    if (!TextUtils.isEmpty(d)) return d;
                }
                String staged = SystemProperties.get("persist.sys.yasvara.pif.security_patch", "").trim();
                if (!TextUtils.isEmpty(staged)) return staged;
            }
        } catch (Throwable t) {
            Log.w(TAG, "patchDateFor failed", t);
        }
        return Build.VERSION.SECURITY_PATCH;
    }

    private static String patchOverrideFor(Context ctx, String pkg) {
        try {
            String raw = secure(ctx, KEY_PATCH_OVERRIDE);
            if (TextUtils.isEmpty(raw)) return null;
            JSONObject o = new JSONObject(raw);
            if (!TextUtils.isEmpty(pkg) && o.has(pkg)) {
                String v = o.optString(pkg, "");
                if (!TextUtils.isEmpty(v)) return v;
            }
            String star = o.optString("*", "");
            return TextUtils.isEmpty(star) ? null : star;
        } catch (Throwable t) {
            return null;
        }
    }

    /** "YYYY-MM-DD" (or "YYYY-MM") → yyyymm int; -1 when unparsable. */
    public static int patchYyyymm(String date) {
        try {
            String n = date.trim().replace("-", "");
            int year = Integer.parseInt(n.substring(0, 4));
            int month = Integer.parseInt(n.substring(4, 6));
            return year * 100 + month;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** "YYYY-MM-DD" (or "YYYY-MM") → yyyymmdd int; -1 when unparsable. */
    public static int patchYyyymmdd(String date) {
        try {
            String n = date.trim().replace("-", "");
            int year = Integer.parseInt(n.substring(0, 4));
            int month = Integer.parseInt(n.substring(4, 6));
            int day = n.length() >= 8 ? Integer.parseInt(n.substring(6, 8)) : 1;
            return year * 10000 + month * 100 + day;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** yyyymm of (now − 12 months): the "recent patch" floor both gates use. */
    public static int minRecentPatchYyyymm() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        cal.add(Calendar.MONTH, -12);
        return cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH) + 1;
    }

    /**
     * Returns the canonical 32/64-byte boot digest.
     * Verified boot hash from {@code ro.yasvara.boot.digest} alone. Returns {@code null} when
     * unavailable or invalid rather than fabricating a digest.
     */
    public static byte[] bootHash() {
        try {
            return YasvaraBootDigest.decodeCanonical(
                    SystemProperties.get(YasvaraBootDigest.PROPERTY, ""));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Resolves verifiedBootKey when the TEE reports all-zero bytes (unlocked bootloader): reads
     * {@code ro.boot.vbmeta.public_key_digest} if valid 32-byte digest, else derives SHA-256 of
     * {@code "yasvara.verified-boot-key" + bootHash} matching native keystore2.
     */
    public static byte[] resolveBootKey(byte[] bootHash) {
        try {
            String norm = YasvaraBootDigest.normalize(
                    SystemProperties.get("ro.boot.vbmeta.public_key_digest", ""));
            if (norm != null) {
                byte[] decoded = YasvaraBootDigest.decodeCanonical(norm);
                if (decoded != null && decoded.length == 32) {
                    return decoded;
                }
            }
        } catch (Throwable ignored) {
        }
        if (bootHash == null || YasvaraBootDigest.isAllZero(bootHash)) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("yasvara.verified-boot-key".getBytes(StandardCharsets.UTF_8));
            return md.digest(bootHash);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String secure(Context ctx, String key) {
        try {
            return Settings.Secure.getString(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }
}
