/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.yasvara.IYasvaraAttestation;
import android.security.yasvara.YasvaraKeySynthesizer;
import android.security.yasvara.YasvaraKeyboxProvider;
import android.security.yasvara.YasvaraLadder;
import android.security.yasvara.YasvaraServiceClient;
import android.security.yasvara.YasvaraSpoofEngine;
import android.security.yasvara.YasvaraTrickyStore;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Base64;
import android.util.Log;
import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * System-side attestation protection, running in system_server.
 *
 * <p>Publishes the keybox in {@code /data/misc/yasvara_attest/keybox.xml} (0640,
 * system:keystore), shared read-only with keystore2 under a dedicated SELinux label.
 * Init owns directory setup; the service never accesses keystore2's database directory.
 * Target processes send only their public chain; the ladder runs here and the rewritten
 * chain comes back. This service never publishes private material to Settings.Secure.
 *
 * <p>Reads are open to any caller (every target app needs them); writes require
 * {@code yasvara.permission.WRITE_ATTESTATION} (Laras, platform-signed + allowlisted).
 *
 * @hide
 */
public class YasvaraAttestationService extends IYasvaraAttestation.Stub {
    private static final String TAG = "YasvaraAttestation";

    static final String PERMISSION_WRITE = "yasvara.permission.WRITE_ATTESTATION";

    private static final int MAX_CHAIN_BYTES = 64 * 1024;
    /** Upper bound on one restartPackages() call; Laras sends a handful. */
    private static final int MAX_RESTART_PACKAGES = 64;

    /** The one active PIF profile. Everything else is derived from this. */
    private static final String PIF_CONFIG_KEY = "yasvara_pif_config";
    /** User toggle gating whether PIF identity spoofing is active (default 0 = off). */
    private static final String PIF_ENABLED_KEY = "yasvara_pif_enabled";
    /** Optional global/per-app security patch override JSON ({pkg|"*": "YYYY-MM-DD"}). */
    private static final String PATCH_OVERRIDE_KEY = "yasvara_trickystore_patch_override";
    /** Keybox change stamp observed by DroidGuard cache invalidation. */
    private static final String KEYBOX_STAMP_KEY = "yasvara_keybox_stamp";
    /** Rung telemetry key in Settings.Secure. */
    private static final String RUNG_KEY = "yasvara_trickystore_rung";

    /** Staging prefix the native libc hook reads; one source, never hand-edited. */
    private static final String NATIVE_PIF_PREFIX = "persist.sys.yasvara.pif.";
    /** Master switch mirrored for keystore2; Settings stays the only input. */
    private static final String NATIVE_ENABLED_PROPERTY = "persist.sys.yasvara.trickystore";
    /** Optional global security patch override mirrored for keystore2. */
    private static final String NATIVE_PATCH_OVERRIDE_PROPERTY =
            "persist.sys.yasvara.trickystore.patch_override";

    private static final String[] NATIVE_PIF_KEYS = {
        "manufacturer", "brand", "model", "device", "name",
        "fingerprint", "release", "build_id", "security_patch", "first_api_level",
        "build_type", "build_tags", "api_level", "os_version",
        "incremental", "flavor", "description"
    };

    private boolean mBootDigestStarted;

    private final Context mContext;
    private final Object mLock = new Object();
    private final YasvaraKeyboxStore mKeyboxStore = new YasvaraKeyboxStore();
    private List<YasvaraKeyboxProvider.Keybox> mKeyboxes = new ArrayList<>();

    public YasvaraAttestationService(Context context) {
        mContext = context;
        loadFromFile();
        startPifSync();
        Slog.i(TAG, "started, keyboxes=" + mKeyboxes.size());
    }

    // --- Single-source PIF: the active profile (Settings.Secure) is the only input; it
    // is fanned out to persist.sys.yasvara.pif.* — read by the native libc hook, the Java
    // spoof and keystore2 alike — so the attestation and native reads never drift. ---

    private void startPifSync() {
        // This service is registered from startBootstrapServices, before
        // ActivityManagerService and the SettingsProvider exist. A direct
        // Settings.Secure read here throws an NPE inside acquireProvider and
        // leaves both pif.json and the native staging empty. Defer to the main
        // looper and retry until the provider is reachable.
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                startBootDigestPublisher();
                if (!syncPif()) {
                    handler.postDelayed(this, 1000);
                    return;
                }
                syncControl();
                try {
                    final Uri pifConfigUri = Settings.Secure.getUriFor(PIF_CONFIG_KEY);
                    final Uri pifEnabledUri = Settings.Secure.getUriFor(PIF_ENABLED_KEY);
                    ContentObserver observer = new ContentObserver(handler) {
                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            if ( pifConfigUri.equals(uri) || pifEnabledUri.equals(uri)) {
                                syncPif();
                                restartIntegrityProcesses(true);
                            } else {
                                syncControl();
                                restartIntegrityProcesses(false);
                            }
                        }
                    };
                    for (String key : new String[] {
                            PIF_CONFIG_KEY, PIF_ENABLED_KEY, PATCH_OVERRIDE_KEY,
                            YasvaraTrickyStore.KEY_ENABLED, YasvaraTrickyStore.KEY_TARGETS}) {
                        mContext.getContentResolver().registerContentObserver(
                                Settings.Secure.getUriFor(key), false, observer);
                    }
                    Slog.i(TAG, "PIF sync ready");
                } catch (Throwable t) {
                    Slog.w(TAG, "PIF observer registration failed", t);
                    handler.postDelayed(this, 1000);
                }
            }
        });
    }

    /**
     * Publishes the master switch and the effective target list for keystore2, derived from
     * the same settings the Java hooks read. The switch is a property; the list is a file next
     * to the keybox so a restart of either side cannot leave them disagreeing.
     */
    private void syncControl() {
        try {
            boolean enabled = YasvaraTrickyStore.isEnabled(mContext);
            SystemProperties.set(NATIVE_ENABLED_PROPERTY, enabled ? "1" : "0");

            String rawOverride = Settings.Secure.getString(
                    mContext.getContentResolver(), PATCH_OVERRIDE_KEY);
            String trimmedOverride = rawOverride != null ? rawOverride.trim() : "";
            String globalPatchOverride = "";
            if (!trimmedOverride.isEmpty()) {
                try {
                    globalPatchOverride = new JSONObject(trimmedOverride).optString("*", "").trim();
                } catch (JSONException ignored) {
                }
            }
            SystemProperties.set(NATIVE_PATCH_OVERRIDE_PROPERTY, globalPatchOverride);

            StringBuilder targets = new StringBuilder();
            for (String pkg : YasvaraTrickyStore.effectiveTargets(mContext)) {
                targets.append(pkg).append('\n');
            }
            synchronized (mLock) {
                mKeyboxStore.publishConfig("targets", targets.toString());
                mKeyboxStore.publishConfig("patch_override", trimmedOverride);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "syncControl failed; keystore2 keeps its previous target list", t);
        }
    }

    private boolean syncPif() {
        try {
            int pifEnabled = Settings.Secure.getInt(
                    mContext.getContentResolver(), PIF_ENABLED_KEY, 0);
            if (pifEnabled != 1) {
                clearNativePif();
                Slog.i(TAG, "PIF disabled: native staging cleared");
                return true;
            }
            Map<String, String> pif = YasvaraSpoofEngine.getPifConfig(mContext);
            if (pif == null || pif.isEmpty()) {
                clearNativePif();
                Slog.w(TAG, "Empty or invalid PIF config; cleared profile staging");
                return true;
            }
            stageNativePif(pif);
            Slog.i(TAG, "PIF synced: native staging updated");
            return true;
        } catch (Throwable t) {
            Slog.w(TAG, "syncPif failed", t);
            return false;
        }
    }

    private void startBootDigestPublisher() {
        if (!mBootDigestStarted) {
            try {
                YasvaraBootDigestPublisher.start(mContext);
                mBootDigestStarted = true;
            } catch (Throwable t) {
                Slog.w(TAG, "startBootDigestPublisher deferred", t);
            }
        }
    }

    private void stageNativePif(Map<String, String> pif) {
        String fp = pif.getOrDefault("FINGERPRINT", "");
        String product = pif.getOrDefault("PRODUCT", "");
        String release = pif.getOrDefault("RELEASE", "");
        String id = pif.getOrDefault("ID", "");
        String incremental = pif.getOrDefault("INCREMENTAL", "");
        String type = pif.getOrDefault("TYPE", "");
        String tags = pif.getOrDefault("TAGS", "");

        setNative("manufacturer", pif.getOrDefault("MANUFACTURER", ""));
        setNative("brand", pif.getOrDefault("BRAND", ""));
        setNative("model", pif.getOrDefault("MODEL", ""));
        setNative("device", pif.getOrDefault("DEVICE", ""));
        setNative("name", product);
        setNative("fingerprint", (fp != null && fp.length() < 92) ? fp : "");
        setNative("release", release);
        setNative("build_id", id);
        setNative("security_patch", pif.getOrDefault("SECURITY_PATCH", ""));
        setNative("first_api_level", pif.getOrDefault("DEVICE_INITIAL_SDK_INT", ""));
        setNative("build_type", type);
        setNative("build_tags", tags);
        // Never copy DEVICE_INITIAL_SDK_INT into api_level: Android 15+ ro.board.api_level /
        // ro.vendor.api_level / ro.llndk.api_level use YYYYMM (e.g. 202404), not SDK ints.
        setNative("api_level", YasvaraSpoofEngine.resolveBoardApiLevel(pif));
        int osVer = YasvaraSpoofEngine.resolvePifOsVersion(pif);
        setNative("os_version", osVer >= 0 ? String.valueOf(osVer) : "");
        setNative("incremental", incremental);
        if (!product.isEmpty() && !type.isEmpty()) {
            String flavor = product + "-" + type;
            setNative("flavor", flavor);
            setNative("description",
                    flavor + " " + release + " " + id + " " + incremental + " " + tags);
        } else {
            setNative("flavor", "");
            setNative("description", "");
        }
    }

    private void clearNativePif() {
        for (String k : NATIVE_PIF_KEYS) {
            setNative(k, "");
        }
    }

    private void setNative(String key, String value) {
        try {
            SystemProperties.set(NATIVE_PIF_PREFIX + key, value == null ? "" : value);
        } catch (Throwable t) {
            Slog.w(TAG, "setNative " + key + " failed", t);
        }
    }

    @Override
    public String protectChain(String pkg, int uid, String base64Chain) {
        try {
            if (base64Chain == null || base64Chain.length() > MAX_CHAIN_BYTES * 2) {
                return base64Chain;
            }
            // The caller proves which of its packages is asking: pkg must belong to the
            // calling uid, otherwise one target in a shared uid could rewrite neighbours.
            if (!ownsPackage(Binder.getCallingUid(), pkg)) return base64Chain;
            if (!YasvaraTrickyStore.isEnabled(mContext)) return base64Chain;
            // Intent gate lives in the shared entry point so both paths agree.
            if (!YasvaraTrickyStore.isTarget(mContext, pkg)) return base64Chain;

            Certificate[] chain = decode(base64Chain);
            if (chain == null) return base64Chain;

            List<YasvaraKeyboxProvider.Keybox> keyboxes;
            synchronized (mLock) {
                keyboxes = new ArrayList<>(mKeyboxes);
            }
            Certificate[] out = YasvaraLadder.serveWithKeyboxes(mContext, pkg, chain, keyboxes);
            if (out == chain) return base64Chain;
            String encoded = encode(out);
            return encoded != null ? encoded : base64Chain;
        } catch (Throwable t) {
            Log.w(TAG, "protectChain failed", t);
            return base64Chain;
        }
    }

    @Override
    public String synthesizeChain(String pkg, int uid, byte[] requestData) {
        try {
            if (requestData == null || requestData.length == 0 || requestData.length > MAX_CHAIN_BYTES * 2) {
                return "";
            }
            if (!ownsPackage(Binder.getCallingUid(), pkg)) return "";
            if (!YasvaraTrickyStore.isEnabled(mContext)) return "";
            if (!YasvaraTrickyStore.isTarget(mContext, pkg)) return "";

            List<YasvaraKeyboxProvider.Keybox> keyboxes;
            synchronized (mLock) {
                keyboxes = new ArrayList<>(mKeyboxes);
            }
            if (keyboxes.isEmpty()) return "";

            Certificate[] chain = YasvaraKeySynthesizer.generateChainFromBytes(
                    mContext, pkg, uid, requestData, keyboxes);
            if (chain == null || chain.length == 0) return "";
            String encoded = encode(chain);
            return encoded != null ? encoded : "";
        } catch (Throwable t) {
            Log.w(TAG, "synthesizeChain failed", t);
            return "";
        }
    }

    @Override
    public String getRung(String pkg) {
        try {
            if (pkg == null) return "";
            String raw = Settings.Secure.getString(mContext.getContentResolver(), RUNG_KEY);
            if (raw == null || raw.isEmpty()) return "";
            JSONObject entry = new JSONObject(raw).optJSONObject(pkg);
            return entry != null ? entry.toString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public String getKeyboxInfo() {
        try {
            synchronized (mLock) {
                if (mKeyboxes.isEmpty()) return "";
                YasvaraKeyboxProvider.Keybox kb = mKeyboxes.get(0);
                X509Certificate leaf = kb.leaf();
                JSONObject o = new JSONObject();
                o.put("algorithm", kb.algorithm);
                o.put("serialHex", leaf.getSerialNumber().toString(16));
                o.put("issuer", leaf.getIssuerX500Principal().getName());
                o.put("notAfter", leaf.getNotAfter().getTime());
                o.put("chainLength", kb.chain.size());
                o.put("hasPrivateKey", kb.hasPrivateKey());
                JSONArray algos = new JSONArray();
                for (YasvaraKeyboxProvider.Keybox k : mKeyboxes) {
                    algos.put(k.algorithm);
                }
                o.put("algorithms", algos);
                o.put("keyCount", mKeyboxes.size());
                return o.toString();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void setKeybox(String xml) {
        enforceWrite();
        String normalized = YasvaraKeyboxStore.normalize(xml);
        List<YasvaraKeyboxProvider.Keybox> keyboxes = parseKeyboxes(normalized);
        synchronized (mLock) {
            try {
                mKeyboxStore.install(normalized, () -> mKeyboxes = keyboxes);
                Slog.i(TAG, "keybox installed, entries=" + mKeyboxes.size());
            } catch (IOException e) {
                Slog.e(TAG, "setKeybox failed", e);
                throw new IllegalStateException("keybox store failed: " + e.getMessage());
            }
        }
        updateKeyboxStamp(keyboxes);
        restartIntegrityProcesses(true);
    }

    @Override
    public void clearKeybox() {
        enforceWrite();
        synchronized (mLock) {
            try {
                mKeyboxStore.clear(() -> mKeyboxes = new ArrayList<>());
                Slog.i(TAG, "keybox cleared");
            } catch (IOException e) {
                Slog.e(TAG, "clearKeybox failed", e);
                throw new IllegalStateException("keybox clear failed: " + e.getMessage());
            }
        }
        updateKeyboxStamp(null);
        restartIntegrityProcesses(true);
    }

    private void updateKeyboxStamp(List<YasvaraKeyboxProvider.Keybox> keyboxes) {
        final long token = Binder.clearCallingIdentity();
        try {
            String stamp = "";
            if (keyboxes != null && !keyboxes.isEmpty()) {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    for (YasvaraKeyboxProvider.Keybox kb : keyboxes) {
                        if (kb.algorithm != null) {
                            md.update(kb.algorithm.getBytes(StandardCharsets.UTF_8));
                        }
                        if (kb.chain != null) {
                            for (X509Certificate cert : kb.chain) {
                                md.update(cert.getEncoded());
                            }
                        }
                    }
                    stamp = Base64.encodeToString(md.digest(), Base64.NO_WRAP)
                            + ":" + keyboxes.size();
                } catch (Throwable ignored) {
                    X509Certificate leaf = keyboxes.get(0).leaf();
                    stamp = (leaf != null && leaf.getSerialNumber() != null)
                            ? leaf.getSerialNumber().toString(16) + ":" + keyboxes.size()
                            : String.valueOf(keyboxes.size());
                }
            }
            String current = Settings.Secure.getString(
                    mContext.getContentResolver(), KEYBOX_STAMP_KEY);
            if (!stamp.equals(current != null ? current : "")) {
                Settings.Secure.putString(
                        mContext.getContentResolver(), KEYBOX_STAMP_KEY, stamp);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "updateKeyboxStamp failed", t);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Kills running GMS / Play Store processes cleanly via SIGKILL without calling
     * forceStopPackage (which would set FLAG_STOPPED and wipe GMS alarms/jobs/FCM).
     *
     * @param fullGms true when PIF or keybox changed (kills all GMS/GSF processes so
     *                Build.* fields and compliance/DroidGuard caches are cleared);
     *                false when only target/override rules changed.
     */
    private void restartIntegrityProcesses(boolean fullGms) {
        if (fullGms) {
            killPackageProcesses(List.of(YasvaraSpoofEngine.PKG_VENDING,
                    YasvaraSpoofEngine.PKG_GMS, YasvaraSpoofEngine.PKG_GSF), null, -1);
        } else {
            killPackageProcesses(List.of(YasvaraSpoofEngine.PKG_VENDING), null, -1);
            killPackageProcesses(List.of(YasvaraSpoofEngine.PKG_GMS),
                    YasvaraSpoofEngine.PKG_GMS + ".unstable", -1);
        }
    }

    /**
     * Laras' "Restart" / per-app apply. Laras runs as its own app uid and can neither
     * signal another app's pids nor reach bound/foreground processes through
     * killBackgroundProcesses (and "com.google.android.gms.unstable" is a process name, not
     * a package), so the kill happens here, where system_server holds CAP_KILL.
     */
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

    /**
     * SIGKILLs every running process owned by {@code packages}, across users. A process is
     * owned when its name is pkg, pkg:* or pkg.* (or it lists pkg in its package list) and
     * its uid has the package's app id, so shared names such as com.google.process.gapps are
     * caught while look-alike names from other uids are not. Only app uids are touched; the
     * processes come back on demand and spoof state is re-read (and the DroidGuard caches
     * re-stamped by YasvaraSpoofEngine) at their next start. No force-stop: FLAG_STOPPED,
     * alarms, jobs and FCM registrations are left alone.
     *
     * @param onlyProcess when non-null, only the process with exactly this name is killed.
     * @param skipPid     a pid never to kill (the binder caller), or -1.
     * @return the number of processes signalled.
     */
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
                    // May be a package installed only for a secondary user or a process name
                    // such as com.google.android.gms.unstable; fall back to process matching.
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
                    Slog.i(TAG, "Killed process " + proc.processName + " (pid=" + proc.pid
                            + ", uid=" + proc.uid + ")");
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

    private boolean ownsPackage(int uid, String pkg) {
        try {
            if (pkg == null || pkg.isEmpty()) return false;
            String[] pkgs = mContext.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) return false;
            for (String p : pkgs) {
                if (pkg.equals(p)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private void loadFromFile() {
        synchronized (mLock) {
            try {
                mKeyboxStore.load(YasvaraAttestationService::parseKeyboxes,
                        keyboxes -> mKeyboxes = keyboxes != null ? keyboxes : new ArrayList<>());
            } catch (IOException | IllegalArgumentException e) {
                // A failed migration must not fall back to a Java-only legacy credential.
                Slog.e(TAG, "loadFromFile failed; check canonical directory setup and keybox", e);
            }
        }
    }

    private static List<YasvaraKeyboxProvider.Keybox> parseKeyboxes(String xml) {
        YasvaraKeyboxProvider.ParseResult result = YasvaraKeyboxProvider.parse(xml);
        if (!result.isValid() || result.keyboxes.stream().noneMatch(k -> k.hasPrivateKey())) {
            throw new IllegalArgumentException("keybox has no usable private key");
        }
        return new ArrayList<>(result.keyboxes);
    }

    private static Certificate[] decode(String joined) {
        try {
            String[] parts = joined.split("\n");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<Certificate> out = new ArrayList<>(parts.length);
            for (String p : parts) {
                String t = p.trim();
                if (t.isEmpty()) continue;
                byte[] der = Base64.decode(t, Base64.DEFAULT);
                if (der.length > MAX_CHAIN_BYTES) return null;
                out.add(cf.generateCertificate(new ByteArrayInputStream(der)));
            }
            return out.isEmpty() ? null : out.toArray(new Certificate[0]);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String encode(Certificate[] chain) {
        try {
            StringBuilder sb = new StringBuilder();
            int total = 0;
            for (Certificate c : chain) {
                byte[] der = c.getEncoded();
                total += der.length;
                if (total > MAX_CHAIN_BYTES) return null;
                if (sb.length() > 0) sb.append('\n');
                sb.append(Base64.encodeToString(der, Base64.NO_WRAP));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** For tests and early-boot callers: is there a usable keybox right now? */
    boolean hasKeyboxesForTest() {
        synchronized (mLock) {
            return !mKeyboxes.isEmpty();
        }
    }
}