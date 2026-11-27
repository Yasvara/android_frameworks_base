/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.yasvara.IYasvaraAttestation;
import android.security.yasvara.YasvaraIntegrityContract;
import android.security.yasvara.YasvaraTrickyStore;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;

/**
 * Thin system_server Binder & lifecycle shim for Yasvara integrity.
 *
 * <p>Delegates all cryptographic keybox parsing/validation, RKP CBOR extraction, PIF normalization,
 * and patch-override parsing to Rust (`libyasvara_integrity_jni`). Publishes canonical files in
 * `/data/misc/yasvara_attest/` (0640, system:keystore) for `keystore2` and stages normalized
 * `persist.sys.yasvara.pif.*` properties for Bionic `libc.so` and collector processes.
 *
 * @hide
 */
public class YasvaraAttestationService extends IYasvaraAttestation.Stub {
    private static final String TAG = "YasvaraAttestation";
    private static final int MAX_RESTART_PACKAGES = 64;
    private static final File AKAR_MANAGED_FEATURES_FILE =
            new File(YasvaraIntegrityContract.AKAR_MANAGED_FEATURES_PATH);
    private static final File PIF_FALLBACK_FILE =
            new File(YasvaraIntegrityContract.PIF_FALLBACK_PATH);

    static final String PERMISSION_WRITE = YasvaraIntegrityContract.PERMISSION_WRITE;

    private static final class KeyboxMeta {
        final String normalizedXml;
        final String infoJson;
        final String rkpManufacturer;
        final String stamp;

        KeyboxMeta(String normalizedXml, String infoJson, String rkpManufacturer, String stamp) {
            this.normalizedXml = normalizedXml;
            this.infoJson = infoJson;
            this.rkpManufacturer = rkpManufacturer;
            this.stamp = stamp;
        }
    }

    private final Context mContext;
    private final Object mLock = new Object();
    private final YasvaraKeyboxStore mKeyboxStore = new YasvaraKeyboxStore();
    private KeyboxMeta mKeyboxMeta;
    private boolean mBootDigestStarted;
    private boolean mLegacyCleaned;
    private boolean mLegacyFilesCleaned;
    private long mPolicyGeneration;

    public YasvaraAttestationService(Context context) {
        mContext = context;
        loadFromFile();
        startSync();
        Slog.i(TAG, "started, keyboxInstalled=" + (mKeyboxMeta != null));
    }

    private void startSync() {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                startBootDigestPublisher();
                if (!syncPif(false)) {
                    handler.postDelayed(this, 1000);
                    return;
                }
                syncControl();
                cleanupLegacySettings();
                try {
                    final Uri pifConfigUri = Settings.Secure.getUriFor(
                            YasvaraIntegrityContract.SECURE_PIF_CONFIG);
                    final Uri pifEnabledUri = Settings.Secure.getUriFor(
                            YasvaraIntegrityContract.SECURE_PIF_ENABLED);
                    final Uri appSpoofMapUri = Settings.Secure.getUriFor(
                            YasvaraIntegrityContract.SECURE_APP_SPOOF_MAP);
                    ContentObserver observer = new ContentObserver(handler) {
                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            if (appSpoofMapUri.equals(uri)) {
                                syncAppSpoofExemptions();
                                publishPolicySnapshot();
                            } else if (pifConfigUri.equals(uri) || pifEnabledUri.equals(uri)) {
                                if (syncPif(true)) {
                                    bumpPurgeGen();
                                    restartIntegrityProcesses(true);
                                }
                            } else {
                                syncControl();
                                bumpPurgeGen();
                                restartIntegrityProcesses(false);
                            }
                        }
                    };
                    for (String key : new String[] {
                            YasvaraIntegrityContract.SECURE_PIF_CONFIG,
                            YasvaraIntegrityContract.SECURE_PIF_ENABLED,
                            YasvaraIntegrityContract.SECURE_TRICKY_PATCH_OVERRIDE,
                            YasvaraIntegrityContract.SECURE_TRICKY_ENABLED,
                            YasvaraIntegrityContract.SECURE_TRICKY_TARGETS,
                            YasvaraIntegrityContract.SECURE_APP_SPOOF_MAP}) {
                        mContext.getContentResolver().registerContentObserver(
                                Settings.Secure.getUriFor(key), false, observer);
                    }
                    Slog.i(TAG, "integrity sync ready");
                } catch (Throwable t) {
                    Slog.w(TAG, "observer registration failed", t);
                    handler.postDelayed(this, 1000);
                }
            }
        });
    }

    private void cleanupLegacySettings() {
        if (mLegacyCleaned) return;
        mLegacyCleaned = true;
        final long token = Binder.clearCallingIdentity();
        try {
            ContentResolver cr = mContext.getContentResolver();
            int removed = 0;
            for (String key : YasvaraIntegrityContract.LEGACY_SECURE_KEYS) {
                if (Settings.Secure.getString(cr, key) != null) {
                    cr.delete(Settings.Secure.getUriFor(key), null, null);
                    removed++;
                }
            }
            if (removed > 0) {
                Slog.i(TAG, "Cleaned " + removed + " obsolete legacy Settings.Secure key(s)");
            }
        } catch (Throwable t) {
            Slog.w(TAG, "cleanupLegacySettings failed", t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void syncControl() {
        try {
            boolean enabled = YasvaraTrickyStore.isEnabled(mContext);
            SystemProperties.set(YasvaraIntegrityContract.PROP_TRICKY_ENABLED, enabled ? "1" : "0");

            String rawOverride = Settings.Secure.getString(
                    mContext.getContentResolver(),
                    YasvaraIntegrityContract.SECURE_TRICKY_PATCH_OVERRIDE);
            String trimmedOverride = rawOverride != null ? rawOverride.trim() : "";
            String globalPatchOverride = trimmedOverride.isEmpty()
                    ? ""
                    : nonNull(YasvaraIntegrityNative.nativeGlobalPatchOverride(trimmedOverride));
            SystemProperties.set(
                    YasvaraIntegrityContract.PROP_PATCH_OVERRIDE, globalPatchOverride);

            syncAppSpoofExemptions();
            KeyboxMeta meta;
            synchronized (mLock) {
                meta = mKeyboxMeta;
            }
            updateRkpManufacturer(meta != null ? meta.rkpManufacturer : "", true);
            publishPolicySnapshot();
        } catch (Throwable t) {
            Slog.w(TAG, "syncControl failed; keystore2 keeps its previous control state", t);
        }
    }

    private void syncAppSpoofExemptions() {
        final long token = Binder.clearCallingIdentity();
        try {
            String rawMap = nonNull(Settings.Secure.getString(
                    mContext.getContentResolver(),
                    YasvaraIntegrityContract.SECURE_APP_SPOOF_MAP));
            String[] slots = YasvaraIntegrityNative.nativePackRkpExemptSlots(rawMap);
            if (slots == null) return;
            for (int i = 0; i < slots.length
                    && i < YasvaraIntegrityContract.RKP_EXEMPT_MAX_SLOTS; i++) {
                String prop = YasvaraIntegrityContract.PROP_RKP_EXEMPT_PREFIX + i;
                String val = nonNull(slots[i]);
                if (!val.equals(SystemProperties.get(prop, ""))) {
                    SystemProperties.set(prop, val);
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "syncAppSpoofExemptions failed", t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void publishPolicySnapshot() {
        final long token = Binder.clearCallingIdentity();
        try {
            ContentResolver cr = mContext.getContentResolver();
            boolean trickyEnabled = YasvaraTrickyStore.isEnabled(mContext);
            boolean pifSettingEnabled = Settings.Secure.getInt(
                    cr, YasvaraIntegrityContract.SECURE_PIF_ENABLED, 0) == 1;
            boolean akarYielded = isExternalPlayIntegrityModuleActive();
            String rawPif = Settings.Secure.getString(
                    cr, YasvaraIntegrityContract.SECURE_PIF_CONFIG);
            if (rawPif == null || rawPif.trim().isEmpty()) {
                rawPif = nonNull(readFallbackPif());
            }
            String platformRelease = SystemProperties.get("ro.build.version.release",
                    Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "17");
            Collection<String> targetList = YasvaraTrickyStore.effectiveTargets(mContext);
            String[] targets = targetList.toArray(new String[0]);
            String rawOverride = nonNull(Settings.Secure.getString(
                    cr, YasvaraIntegrityContract.SECURE_TRICKY_PATCH_OVERRIDE));
            String rawAppSpoofMap = nonNull(Settings.Secure.getString(
                    cr, YasvaraIntegrityContract.SECURE_APP_SPOOF_MAP));

            synchronized (mLock) {
                String rkpMfg = mKeyboxMeta != null ? nonNull(mKeyboxMeta.rkpManufacturer) : "";
                long gen = ++mPolicyGeneration;
                byte[] snapshot = YasvaraIntegrityNative.nativeCompilePolicySnapshot(
                        trickyEnabled,
                        pifSettingEnabled,
                        akarYielded,
                        gen,
                        rawPif,
                        platformRelease,
                        rkpMfg,
                        targets,
                        rawOverride,
                        rawAppSpoofMap);
                if (snapshot == null || snapshot.length == 0) {
                    Slog.w(TAG, "nativeCompilePolicySnapshot returned empty buffer");
                    return;
                }
                mKeyboxStore.publishBytes(YasvaraIntegrityContract.POLICY_FILENAME, snapshot);
                if (!mLegacyFilesCleaned) {
                    mLegacyFilesCleaned = true;
                    mKeyboxStore.deleteConfig(YasvaraIntegrityContract.TARGETS_FILENAME);
                    mKeyboxStore.deleteConfig(YasvaraIntegrityContract.PATCH_OVERRIDE_FILENAME);
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "publishPolicySnapshot failed", t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static boolean isExternalPlayIntegrityModuleActive() {
        try {
            if (!AKAR_MANAGED_FEATURES_FILE.isFile()) return false;
            String text = new String(
                    Files.readAllBytes(AKAR_MANAGED_FEATURES_FILE.toPath()),
                    StandardCharsets.UTF_8);
            for (String token : text.trim().split(",")) {
                if (YasvaraIntegrityContract.AKAR_FEATURE_PLAY_INTEGRITY.equals(token.trim())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean syncPif(boolean publishSnapshot) {
        try {
            int pifEnabled = Settings.Secure.getInt(
                    mContext.getContentResolver(),
                    YasvaraIntegrityContract.SECURE_PIF_ENABLED, 0);
            if (pifEnabled != 1 || isExternalPlayIntegrityModuleActive()) {
                SystemProperties.set(YasvaraIntegrityContract.PROP_PIF_ENABLED, "0");
                clearNativePif();
                if (publishSnapshot) publishPolicySnapshot();
                Slog.i(TAG, "PIF disabled or yielded to Akar module: native staging cleared");
                return true;
            }
            String rawPif = Settings.Secure.getString(
                    mContext.getContentResolver(), YasvaraIntegrityContract.SECURE_PIF_CONFIG);
            if (rawPif == null || rawPif.trim().isEmpty()) {
                rawPif = readFallbackPif();
            }
            if (rawPif == null || rawPif.trim().isEmpty()) {
                SystemProperties.set(YasvaraIntegrityContract.PROP_PIF_ENABLED, "0");
                clearNativePif();
                if (publishSnapshot) publishPolicySnapshot();
                Slog.w(TAG, "Empty PIF config; cleared profile staging");
                return true;
            }
            String platformRelease = SystemProperties.get("ro.build.version.release",
                    Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "17");
            String[] flatPairs = YasvaraIntegrityNative.nativeNormalizePif(rawPif, platformRelease);
            if (flatPairs == null || flatPairs.length == 0 || (flatPairs.length & 1) != 0) {
                SystemProperties.set(YasvaraIntegrityContract.PROP_PIF_ENABLED, "0");
                clearNativePif();
                if (publishSnapshot) publishPolicySnapshot();
                Slog.w(TAG, "Invalid PIF config; cleared profile staging");
                return true;
            }
            if (!stageNativePif(flatPairs)) {
                SystemProperties.set(YasvaraIntegrityContract.PROP_PIF_ENABLED, "0");
                clearNativePif();
                if (publishSnapshot) publishPolicySnapshot();
                Slog.w(TAG, "Partial PIF staging failure; reverted pif.enabled=0");
                return false;
            }
            SystemProperties.set(YasvaraIntegrityContract.PROP_PIF_ENABLED, "1");
            if (publishSnapshot) publishPolicySnapshot();
            Slog.i(TAG, "PIF synced via Rust normalizer");
            return true;
        } catch (Throwable t) {
            Slog.w(TAG, "syncPif failed", t);
            return false;
        }
    }

    private void startBootDigestPublisher() {
        if (!mBootDigestStarted) {
            try {
                YasvaraBootDigestPublisher.start(mContext, this::onBootDigestPublished);
                mBootDigestStarted = true;
            } catch (Throwable t) {
                Slog.w(TAG, "startBootDigestPublisher deferred", t);
            }
        }
    }

    /**
     * Closes the early-boot race where GMS or Play Store launches before `ro.yasvara.boot.digest`
     * is published (receiving an unpatched hardware chain). Zero overhead when no collector has
     * started yet; otherwise bumps `purge_gen` and restarts the early-started collector processes.
     */
    private void onBootDigestPublished() {
        if (!hasRunningIntegrityCollector()) {
            return;
        }
        bumpPurgeGen();
        int killed = restartIntegrityProcesses(false);
        Slog.i(TAG, "Restarted " + killed + " early-started integrity collector(s) after boot digest");
    }

    private boolean hasRunningIntegrityCollector() {
        final long token = Binder.clearCallingIdentity();
        try {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return false;
            for (ActivityManager.RunningAppProcessInfo proc : procs) {
                if (proc == null || proc.processName == null) continue;
                String name = proc.processName;
                if (YasvaraIntegrityContract.PROC_GMS_UNSTABLE.equals(name)
                        || YasvaraIntegrityContract.PKG_VENDING.equals(name)
                        || YasvaraIntegrityContract.PKG_GMS.equals(name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return false;
    }

    private boolean stageNativePif(String[] flatPairs) {
        boolean allOk = true;
        for (int i = 0; i + 1 < flatPairs.length; i += 2) {
            if (!setNative(flatPairs[i], flatPairs[i + 1])) {
                allOk = false;
            }
        }
        return allOk;
    }

    private void clearNativePif() {
        for (String k : YasvaraIntegrityContract.NATIVE_PIF_STAGING_KEYS) {
            setNative(k, "");
        }
    }

    private boolean setNative(String key, String value) {
        try {
            SystemProperties.set(
                    YasvaraIntegrityContract.PROP_PIF_PREFIX + key, value == null ? "" : value);
            return true;
        } catch (Throwable t) {
            Slog.w(TAG, "setNative " + key + " failed", t);
            return false;
        }
    }

    private static String readFallbackPif() {
        try {
            if (!PIF_FALLBACK_FILE.isFile()) return null;
            return new String(Files.readAllBytes(PIF_FALLBACK_FILE.toPath()),
                    StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public String getStatus() {
        final String digest = SystemProperties.get(YasvaraIntegrityContract.PROP_BOOT_DIGEST, "");
        final String bootSource = SystemProperties.get(YasvaraIntegrityContract.PROP_BOOT_SOURCE, "");
        final boolean pifEnabled = "1".equals(
                SystemProperties.get(YasvaraIntegrityContract.PROP_PIF_ENABLED, "0"));
        final boolean trickyEnabled = "1".equals(
                SystemProperties.get(YasvaraIntegrityContract.PROP_TRICKY_ENABLED, "1"));
        final boolean keyboxLoaded;
        final String rkpManufacturer;
        synchronized (mLock) {
            keyboxLoaded = mKeyboxMeta != null;
            rkpManufacturer = mKeyboxMeta != null ? nonNull(mKeyboxMeta.rkpManufacturer) : "";
        }
        final int purgeGen = readPurgeGen();
        try {
            return new JSONObject()
                    .put("bootDigestReady", !digest.isEmpty())
                    .put("bootSource", bootSource)
                    .put("keyboxLoaded", keyboxLoaded)
                    .put("rkpManufacturer", rkpManufacturer)
                    .put("pifEnabled", pifEnabled)
                    .put("trickystoreEnabled", trickyEnabled)
                    .put("purgeGen", purgeGen)
                    .toString();
        } catch (JSONException e) {
            return "";
        }
    }

    @Override
    public int purgeIntegrityCache() {
        enforceWrite();
        bumpPurgeGen();
        int killed = restartIntegrityProcesses(true);
        Slog.i(TAG, "purgeIntegrityCache bumped gen and restarted " + killed + " process(es)");
        return killed;
    }

    private int readPurgeGen() {
        final long token = Binder.clearCallingIdentity();
        try {
            return Settings.Secure.getInt(
                    mContext.getContentResolver(),
                    YasvaraIntegrityContract.SECURE_INTEGRITY_PURGE_GEN, 0);
        } catch (Throwable ignored) {
            return 0;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void bumpPurgeGen() {
        final long token = Binder.clearCallingIdentity();
        try {
            int next = Settings.Secure.getInt(
                    mContext.getContentResolver(),
                    YasvaraIntegrityContract.SECURE_INTEGRITY_PURGE_GEN, 0) + 1;
            Settings.Secure.putInt(
                    mContext.getContentResolver(),
                    YasvaraIntegrityContract.SECURE_INTEGRITY_PURGE_GEN, next);
        } catch (Throwable t) {
            Slog.w(TAG, "bumpPurgeGen failed", t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public String getKeyboxInfo() {
        synchronized (mLock) {
            return mKeyboxMeta != null ? mKeyboxMeta.infoJson : "";
        }
    }

    @Override
    public String inspectKeybox(String rawXml, boolean requirePrivateKey) {
        enforceWrite();
        String raw = rawXml == null ? "" : rawXml;
        String json = YasvaraIntegrityNative.nativeInspectKeybox(raw, requirePrivateKey);
        if (json == null || json.isEmpty()) {
            return "{\"valid\":false,\"error\":"
                    + YasvaraIntegrityContract.KEYBOX_ERR_INVALID_KEYBOX + "}";
        }
        // Strip normalizedXml (which carries private keys) before returning over Binder.
        try {
            JSONObject o = new JSONObject(json);
            o.remove("normalizedXml");
            return o.toString();
        } catch (JSONException e) {
            return "{\"valid\":false,\"error\":"
                    + YasvaraIntegrityContract.KEYBOX_ERR_INVALID_KEYBOX + "}";
        }
    }

    @Override
    public void setKeybox(String xml) {
        enforceWrite();
        KeyboxMeta meta = inspectKeyboxStrict(xml);
        synchronized (mLock) {
            try {
                mKeyboxStore.install(meta.normalizedXml, () -> mKeyboxMeta = meta);
                Slog.i(TAG, "keybox installed via Rust validator");
            } catch (IOException e) {
                Slog.e(TAG, "setKeybox failed", e);
                throw new IllegalStateException("keybox store failed: " + e.getMessage());
            }
        }
        updateKeyboxStamp(meta.stamp);
        updateRkpManufacturer(meta.rkpManufacturer, true);
        publishPolicySnapshot();
        bumpPurgeGen();
        restartIntegrityProcesses(true);
    }

    @Override
    public void clearKeybox() {
        enforceWrite();
        synchronized (mLock) {
            try {
                mKeyboxStore.clear(() -> mKeyboxMeta = null);
                Slog.i(TAG, "keybox cleared");
            } catch (IOException e) {
                Slog.e(TAG, "clearKeybox failed", e);
                throw new IllegalStateException("keybox clear failed: " + e.getMessage());
            }
        }
        updateKeyboxStamp("");
        updateRkpManufacturer("", true);
        publishPolicySnapshot();
        bumpPurgeGen();
        restartIntegrityProcesses(true);
    }

    private void updateRkpManufacturer(String mfg, boolean writeSettings) {
        String val = mfg != null ? mfg : "";
        try {
            SystemProperties.set(YasvaraIntegrityContract.PROP_RKP_MANUFACTURER, val);
        } catch (Throwable t) {
            Slog.w(TAG, "set " + YasvaraIntegrityContract.PROP_RKP_MANUFACTURER + " failed", t);
        }
        if (!writeSettings) return;
        final long token = Binder.clearCallingIdentity();
        try {
            String current = Settings.Secure.getString(
                    mContext.getContentResolver(), YasvaraIntegrityContract.SECURE_RKP_MANUFACTURER);
            if (!val.equals(current != null ? current : "")) {
                Settings.Secure.putString(
                        mContext.getContentResolver(),
                        YasvaraIntegrityContract.SECURE_RKP_MANUFACTURER, val);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "updateRkpManufacturer failed", t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void updateKeyboxStamp(String stamp) {
        String val = stamp != null ? stamp : "";
        final long token = Binder.clearCallingIdentity();
        try {
            String current = Settings.Secure.getString(
                    mContext.getContentResolver(), YasvaraIntegrityContract.SECURE_KEYBOX_STAMP);
            if (!val.equals(current != null ? current : "")) {
                Settings.Secure.putString(
                        mContext.getContentResolver(),
                        YasvaraIntegrityContract.SECURE_KEYBOX_STAMP, val);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "updateKeyboxStamp failed", t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private int restartIntegrityProcesses(boolean fullGms) {
        if (fullGms) {
            return killPackageProcesses(List.of(
                    YasvaraIntegrityContract.PKG_VENDING,
                    YasvaraIntegrityContract.PKG_GMS,
                    YasvaraIntegrityContract.PKG_GSF), null, -1);
        }
        int killed = killPackageProcesses(List.of(YasvaraIntegrityContract.PKG_VENDING), null, -1);
        killed += killPackageProcesses(List.of(YasvaraIntegrityContract.PKG_GMS),
                YasvaraIntegrityContract.PROC_GMS_UNSTABLE, -1);
        return killed;
    }

    @Override
    public int restartPackages(String[] packages) {
        enforceWrite();
        if (packages == null || packages.length == 0) return 0;
        if (packages.length > MAX_RESTART_PACKAGES) {
            throw new IllegalArgumentException("too many packages: " + packages.length);
        }
        ArraySet<String> targets = new ArraySet<>();
        for (String p : packages) {
            if (p == null) continue;
            String t = p.trim();
            if (!t.isEmpty()) targets.add(t);
        }
        if (targets.isEmpty()) return 0;
        Slog.i(TAG, "restartPackages " + targets + " from uid " + Binder.getCallingUid());
        return killPackageProcesses(targets, null, Binder.getCallingPid());
    }

    private int killPackageProcesses(Collection<String> packages, String onlyProcess,
            int skipPid) {
        int killed = 0;
        final long token = Binder.clearCallingIdentity();
        try {
            ArrayMap<String, Integer> appIds = new ArrayMap<>();
            ArraySet<String> unmatchedPkgs = new ArraySet<>();
            PackageManager pm = mContext.getPackageManager();
            for (String pkg : packages) {
                try {
                    int appId = UserHandle.getAppId(pm.getPackageUid(pkg,
                            PackageManager.MATCH_UNINSTALLED_PACKAGES));
                    if (appId >= Process.FIRST_APPLICATION_UID
                            && appId <= Process.LAST_APPLICATION_UID) {
                        appIds.put(pkg, appId);
                    } else {
                        Slog.w(TAG, "not restarting non-app package " + pkg);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    unmatchedPkgs.add(pkg);
                }
            }
            if (appIds.isEmpty() && unmatchedPkgs.isEmpty()) return 0;

            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am == null) return 0;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return 0;
            for (ActivityManager.RunningAppProcessInfo proc : procs) {
                if (proc == null || proc.processName == null || proc.pid <= 0) continue;
                if (proc.pid == skipPid || proc.pid == Process.myPid()) continue;
                if (onlyProcess != null && !onlyProcess.equals(proc.processName)) continue;
                final int procAppId = UserHandle.getAppId(proc.uid);
                if (procAppId < Process.FIRST_APPLICATION_UID
                        || procAppId > Process.LAST_APPLICATION_UID) {
                    continue;
                }
                boolean shouldKill = false;
                for (int i = 0; i < appIds.size(); i++) {
                    if (appIds.valueAt(i) != procAppId) continue;
                    if (ownsProcess(appIds.keyAt(i), proc)) {
                        shouldKill = true;
                        break;
                    }
                }
                if (!shouldKill && !unmatchedPkgs.isEmpty()) {
                    for (int i = 0; i < unmatchedPkgs.size(); i++) {
                        if (ownsProcess(unmatchedPkgs.valueAt(i), proc)) {
                            shouldKill = true;
                            break;
                        }
                    }
                }
                if (shouldKill) {
                    Process.killProcess(proc.pid);
                    killed++;
                }
            }
            if (onlyProcess == null) {
                for (int i = 0; i < appIds.size(); i++) {
                    am.killBackgroundProcesses(appIds.keyAt(i));
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "killPackageProcesses " + packages + " failed", t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return killed;
    }

    private static boolean ownsProcess(String pkg, ActivityManager.RunningAppProcessInfo proc) {
        final String name = proc.processName;
        if (name.equals(pkg) || name.startsWith(pkg + ":") || name.startsWith(pkg + ".")) {
            return true;
        }
        if (proc.pkgList != null) {
            for (String p : proc.pkgList) {
                if (pkg.equals(p)) return true;
            }
        }
        return false;
    }

    private void enforceWrite() {
        if (mContext.checkCallingPermission(PERMISSION_WRITE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("requires " + PERMISSION_WRITE);
        }
    }

    private void loadFromFile() {
        KeyboxMeta loaded = null;
        synchronized (mLock) {
            try {
                mKeyboxStore.load(YasvaraAttestationService::inspectKeyboxStrict,
                        meta -> mKeyboxMeta = meta);
                loaded = mKeyboxMeta;
            } catch (IOException | IllegalArgumentException e) {
                Slog.e(TAG, "loadFromFile failed; check canonical directory setup and keybox", e);
            }
        }
        if (loaded != null) {
            updateRkpManufacturer(loaded.rkpManufacturer, false);
        }
    }

    private static KeyboxMeta inspectKeyboxStrict(String rawXml) {
        if (rawXml == null || rawXml.isEmpty()) {
            throw new IllegalArgumentException("keybox is empty");
        }
        String json = YasvaraIntegrityNative.nativeInspectKeybox(rawXml, true);
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException("keybox validation failed");
        }
        try {
            JSONObject o = new JSONObject(json);
            if (!o.optBoolean("valid", false)) {
                throw new IllegalArgumentException(
                        "keybox rejected by Rust validator (code=" + o.optInt("error", -1) + ")");
            }
            return new KeyboxMeta(
                    o.optString("normalizedXml", ""),
                    o.optString("infoJson", ""),
                    o.optString("rkpManufacturer", ""),
                    o.optString("stamp", ""));
        } catch (JSONException e) {
            throw new IllegalArgumentException("keybox metadata malformed", e);
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    boolean hasKeyboxesForTest() {
        synchronized (mLock) {
            return mKeyboxMeta != null;
        }
    }
}
