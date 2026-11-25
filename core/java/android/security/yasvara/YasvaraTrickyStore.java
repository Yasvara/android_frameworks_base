/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.app.ActivityThread;
import android.content.Context;
import android.hardware.security.keymint.KeyParameter;
import android.provider.Settings;
import android.system.keystore2.IKeystoreSecurityLevel;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;
import android.text.TextUtils;
import android.util.Log;

import java.security.cert.Certificate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for in-process attestation protection.
 *
 * <p>Called from {@code AndroidKeyStoreSpi.engineGetCertificateChain /
 * engineGetCertificate}. The only per-app input is <b>intent</b>: a package on the
 * target list wants Protect (identity/patch updates preserving hardware boot state);
 * anything else gets Real (its own attestation, untouched). The technique is picked
 * by {@link YasvaraLadder}
 * from device state — never by the user, never per app.
 *
 * <p>Package resolution uses the package that owns <i>this</i> process, not "any package
 * of the uid", so one target in a shared uid cannot rewrite its neighbours.
 *
 * @hide
 */
public final class YasvaraTrickyStore {
    private static final String TAG = "YasvaraTrickyStore";

    public static final String KEY_ENABLED = "yasvara_trickystore_enabled";
    public static final String KEY_TARGETS = "yasvara_trickystore_targets";

    /** Default targets: DroidGuard, Play Store, Wallet. Users add banking apps. */
    private static final String DEFAULT_TARGETS =
            YasvaraSpoofEngine.PKG_GMS + "\n"
            + YasvaraSpoofEngine.PKG_VENDING + "\n"
            + "com.google.android.apps.walletnfcrel\n";

    /**
     * Laras' own package is always a target while TrickyStore is on, so its Integrity check sees
     * exactly the chain that target apps see.
     */
    public static final String SELF_TEST_PACKAGE = YasvaraUpdatePolicy.LARAS_PACKAGE;

    /** Re-entrancy guard: the TEE probe itself walks through this hook. */
    private static final ThreadLocal<Boolean> sInLadder = new ThreadLocal<>();

    private static final ConcurrentHashMap<KeyId, KeyEntryResponse> sKeyEntryCache =
            new ConcurrentHashMap<>();

    public static final class KeyId {
        public final int uid;
        public final String alias;

        public KeyId(int uid, String alias) {
            this.uid = uid;
            this.alias = alias != null ? alias : "";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof KeyId)) return false;
            KeyId other = (KeyId) o;
            return uid == other.uid && alias.equals(other.alias);
        }

        @Override
        public int hashCode() {
            return 31 * uid + alias.hashCode();
        }
    }

    public static void cacheKeyEntry(int uid, String alias, KeyEntryResponse response) {
        if (alias == null || response == null) return;
        sKeyEntryCache.put(new KeyId(uid, alias), response);
    }

    public static KeyEntryResponse onGetKeyEntry(KeyDescriptor descriptor) {
        if (sKeyEntryCache.isEmpty()) return null;
        try {
            if (descriptor == null || descriptor.alias == null) return null;
            return sKeyEntryCache.get(new KeyId(android.os.Process.myUid(), descriptor.alias));
        } catch (Throwable t) {
            return null;
        }
    }

    public static void onDeleteKey(KeyDescriptor descriptor) {
        if (sKeyEntryCache.isEmpty()) return;
        try {
            if (descriptor == null || descriptor.alias == null) return;
            sKeyEntryCache.remove(new KeyId(android.os.Process.myUid(), descriptor.alias));
        } catch (Throwable ignored) {
        }
    }

    public static KeyMetadata onGenerateKey(
            IKeystoreSecurityLevel level,
            KeyDescriptor descriptor,
            Collection<KeyParameter> args,
            int flags,
            byte[] entropy) {
        // Native keystore2 (src/system/security/keystore2/src/yasvara/mod.rs) handles
        // prepare_keymint_params and leaf patching while keeping a real usable private key
        // in TrustZone for Signature.sign() / createOperation().
        return null;
    }

    private YasvaraTrickyStore() {}

    /** Returns a replacement chain, or the original when Real / disabled / failing. */
    public static Certificate[] maybeSpoofChain(Certificate[] original, String alias) {
        try {
            if (Boolean.TRUE.equals(sInLadder.get())) return original;
            if (original == null || original.length == 0) return original;
            if (YasvaraTeeProbe.PROBE_ALIAS.equals(alias)
                    || YasvaraBootDigest.PROBE_ALIAS.equals(alias)) return original;
            // Fast-path: non-attested keys and chains already patched by native keystore2
            // return immediately in-process with zero Binder IPC and zero Base64 overhead.
            if (!YasvaraAttestation.needsFallbackPatch(original[0])) {
                return original;
            }
            if (!sKeyEntryCache.isEmpty() && alias != null
                    && sKeyEntryCache.containsKey(new KeyId(android.os.Process.myUid(), alias))) {
                return original;
            }
            Context ctx = currentContext();
            if (ctx == null || !isEnabled(ctx)) return original;
            String pkg = currentPackage();
            if (TextUtils.isEmpty(pkg) || !isTarget(ctx, pkg)) return original;
            sInLadder.set(Boolean.TRUE);
            try {
                // Phase 2 path: system_server holds the private key. When it is
                // unreachable (early boot, isolated process) fall back to the
                // in-process ladder, which uses the Secure keybox (Phase 1).
                Certificate[] served = YasvaraServiceClient.protect(
                        pkg, android.os.Process.myUid(), original);
                if (served != null) return served;
                return YasvaraLadder.serve(ctx, pkg, original);
            } finally {
                sInLadder.set(Boolean.FALSE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "maybeSpoofChain failed", t);
            return original;
        }
    }

    /** True when the master switch is on (default on). */
    public static boolean isEnabled(Context ctx) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), KEY_ENABLED, 1) == 1;
        } catch (Throwable t) {
            return true;
        }
    }

    /** True when {@code pkg} wants Protect. */
    public static boolean isTarget(Context ctx, String pkg) {
        return effectiveTargets(ctx).contains(pkg);
    }

    /**
     * The one target set: the user's list (or the defaults) plus the packages that are always
     * covered. Also published to keystore2 so both sides agree.
     */
    public static Set<String> effectiveTargets(Context ctx) {
        Set<String> set = new LinkedHashSet<>();
        try {
            String raw = Settings.Secure.getString(ctx.getContentResolver(), KEY_TARGETS);
            if (TextUtils.isEmpty(raw)) raw = DEFAULT_TARGETS;
            for (String l : raw.split("[,\n]")) {
                String t = l.trim();
                if (!t.isEmpty()) set.add(t);
            }
        } catch (Throwable t) {
            set.clear();
        }
        // GMS is always covered when TrickyStore is on (DroidGuard lives there).
        set.add(YasvaraSpoofEngine.PKG_GMS);
        set.add(SELF_TEST_PACKAGE);
        return set;
    }

    /** Default seed used by Laras on first run. */
    public static String defaultTargets() {
        return DEFAULT_TARGETS;
    }


    private static Context currentContext() {
        try {
            return ActivityThread.currentApplication();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String currentPackage() {
        try {
            Context ctx = currentContext();
            return ctx == null ? "" : ctx.getPackageName();
        } catch (Throwable t) {
            return "";
        }
    }
}
