/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.app.ActivityThread;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;

import java.util.Map;

/**
 * The power-user install and privacy switches, read straight from {@code Settings.Secure} in
 * system_server. Laras writes them; the framework check sites read them through here.
 *
 * <p>Each switch only ever <i>relaxes</i> a guard the platform already applies, and every guard
 * it relaxes is one the device owner can already defeat over adb (downgrade, signature checks,
 * verification, and so on). None of them touch another app's data or the checks that protect
 * one app from another; they change what <i>this</i> device will install and show on its own
 * screen. Everything is off by default and reads false until the owner turns it on.
 *
 * <p>Values are cached and refreshed by a single observer, so a check site pays only a volatile
 * read. The window-secure switch is not here: the platform already plumbs it as
 * {@link Settings.Secure#DISABLE_SECURE_WINDOWS}.
 *
 * @hide
 */
public final class YasvaraSuperUser {
    private static final String TAG = "YasvaraSuperUser";

    /** Allow installing an older version over a newer one. */
    public static final String KEY_ALLOW_DOWNGRADE = "yasvara_su_allow_downgrade";
    /** Install even when the new package is signed by a different key than the installed one. */
    public static final String KEY_DISABLE_SIGNATURE_VERIFICATION = "yasvara_su_disable_sigverify";
    /** Install packages that target an SDK below the platform's minimum. */
    public static final String KEY_BYPASS_MIN_TARGET_SDK = "yasvara_su_bypass_min_sdk";
    /** Skip the package verifier (Play Protect scan) for every install. */
    public static final String KEY_DISABLE_PACKAGE_VERIFICATION = "yasvara_su_disable_pkgverify";
    /**
     * With {@link #KEY_DISABLE_SIGNATURE_VERIFICATION}, also let a non-privileged system app
     * without a shared uid be replaced by a build signed with another key; also allows system
     * app updates on adoptable storage.
     */
    public static final String KEY_ALLOW_REPLACE_SYSTEM = "yasvara_su_allow_replace_system";
    /** Skip the free-space requirement that aborts an install early. */
    public static final String KEY_NO_STORAGE_RESTRICT = "yasvara_su_no_storage_restrict";
    /** Do not tell apps when the screen is being captured (screen-capture callback). */
    public static final String KEY_HIDE_CAPTURE_STATUS = "yasvara_su_hide_capture_status";
    /** Report the device as encrypted to apps even when it is not. */
    public static final String KEY_SPOOF_ENCRYPTED = "yasvara_su_spoof_encrypted";
    /** Install packages marked {@code android:testOnly} without the test install flag. */
    public static final String KEY_ALLOW_TEST_ONLY = "yasvara_su_allow_test_only";

    /**
     * Newline- or comma-separated packages "detached" from the Play Store (v0).
     *
     * @deprecated YasvaraUpdatePolicyService migrates this list into "Never" rules on
     * boot and clears it; it is only read until that migration has run.
     */
    @Deprecated
    public static final String KEY_APP_DETACH = "yasvara_su_app_detach";

    private static final String[] KEYS = {
        KEY_ALLOW_DOWNGRADE,
        KEY_DISABLE_SIGNATURE_VERIFICATION,
        KEY_BYPASS_MIN_TARGET_SDK,
        KEY_DISABLE_PACKAGE_VERIFICATION,
        KEY_ALLOW_REPLACE_SYSTEM,
        KEY_NO_STORAGE_RESTRICT,
        KEY_HIDE_CAPTURE_STATUS,
        KEY_SPOOF_ENCRYPTED,
        KEY_ALLOW_TEST_ONLY,
    };

    private static final Object sLock = new Object();
    private static volatile Map<String, Boolean> sCache;
    private static volatile java.util.Set<String> sDetach = java.util.Collections.emptySet();

    private YasvaraSuperUser() {}

    /** True when the owner has turned [key] on. Never throws; false until the cache is warm. */
    public static boolean isEnabled(String key) {
        Map<String, Boolean> cache = sCache;
        if (cache == null) cache = init();
        Boolean value = cache == null ? null : cache.get(key);
        return value != null && value;
    }

    private static Map<String, Boolean> init() {
        synchronized (sLock) {
            if (sCache != null) return sCache;
            try {
                Context context = ActivityThread.currentActivityThread() == null ? null
                        : ActivityThread.currentActivityThread().getSystemContext();
                if (context == null) return null;
                final Context ctx = context;
                Handler handler = new Handler(Looper.getMainLooper());
                ContentObserver observer = new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        refresh(ctx);
                    }
                };
                for (String key : KEYS) {
                    ctx.getContentResolver().registerContentObserver(
                            Settings.Secure.getUriFor(key), false, observer);
                }
                ctx.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(KEY_APP_DETACH), false, observer);
                refresh(ctx);
            } catch (Throwable t) {
                Log.w(TAG, "init failed", t);
                return null;
            }
            return sCache;
        }
    }

    /** True when any package is detached, so hot callers can skip the per-package check. */
    public static boolean hasDetached() {
        if (sCache == null) init();
        return !sDetach.isEmpty();
    }

    /** True when [pkg] is detached from Play Store updates. */
    public static boolean isDetached(String pkg) {
        return pkg != null && sDetach.contains(pkg);
    }

    private static void refresh(Context context) {
        try {
            ArrayMap<String, Boolean> map = new ArrayMap<>(KEYS.length);
            for (String key : KEYS) {
                map.put(key, Settings.Secure.getInt(context.getContentResolver(), key, 0) != 0);
            }
            sCache = map;
            String detach = Settings.Secure.getString(context.getContentResolver(), KEY_APP_DETACH);
            if (detach == null || detach.isEmpty()) {
                sDetach = java.util.Collections.emptySet();
            } else {
                java.util.HashSet<String> set = new java.util.HashSet<>();
                for (String line : detach.split("[,\n]")) {
                    String t = line.trim();
                    if (!t.isEmpty()) set.add(t);
                }
                sDetach = set;
            }
        } catch (Throwable t) {
            Log.w(TAG, "refresh failed", t);
        }
    }
}
