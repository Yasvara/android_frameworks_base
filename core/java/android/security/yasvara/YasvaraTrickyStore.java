/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Thin TrickyStore target-list & toggle facade backed by {@link YasvaraIntegrityContract}.
 *
 * <p>All attestation patching and `PURPOSE_ATTEST_KEY` handling run natively in Rust `keystore2`
 * (`system/security/keystore2/src/yasvara`). Zero Java fallback crypto or in-process caches.
 *
 * @hide
 */
public final class YasvaraTrickyStore {
    public static final String KEY_ENABLED = YasvaraIntegrityContract.SECURE_TRICKY_ENABLED;
    public static final String KEY_TARGETS = YasvaraIntegrityContract.SECURE_TRICKY_TARGETS;
    public static final String SELF_TEST_PACKAGE = YasvaraIntegrityContract.PKG_LARAS;

    private YasvaraTrickyStore() {}

    /** True when the master switch is on (default on). */
    public static boolean isEnabled(Context ctx) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), KEY_ENABLED, 1) == 1;
        } catch (Throwable t) {
            return true;
        }
    }

    /** True when {@code pkg} is on the effective target list. */
    public static boolean isTarget(Context ctx, String pkg) {
        return effectiveTargets(ctx).contains(pkg);
    }

    /** Effective target set published to Rust keystore2 and displayed by Laras. */
    public static Set<String> effectiveTargets(Context ctx) {
        Set<String> set = new LinkedHashSet<>();
        try {
            String raw = Settings.Secure.getString(ctx.getContentResolver(), KEY_TARGETS);
            if (TextUtils.isEmpty(raw)) raw = YasvaraIntegrityContract.DEFAULT_TRICKY_TARGETS_TEXT;
            for (String l : raw.split("[,\n]")) {
                String t = l.trim();
                if (!t.isEmpty()) set.add(t);
            }
        } catch (Throwable t) {
            set.clear();
        }
        set.add(YasvaraIntegrityContract.PKG_GMS);
        set.add(SELF_TEST_PACKAGE);
        return set;
    }

    /** Default target seed used by Laras on first run. */
    public static String defaultTargets() {
        return YasvaraIntegrityContract.DEFAULT_TRICKY_TARGETS_TEXT;
    }
}
