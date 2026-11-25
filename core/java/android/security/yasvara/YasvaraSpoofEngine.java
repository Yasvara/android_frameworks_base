/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.security.yasvara;

import android.app.ActivityThread;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified spoof engine for Yasvara.
 *
 * <p>Single hook point: {@link android.app.Instrumentation#newApplication} calls
 * {@link #apply(Context)} once per app process, before {@code Application.onCreate}.
 * Precedence per process:
 * <ol>
 *   <li>Per-app profile from {@link YasvaraSpoofProfiles} (Netflix / games).</li>
 *   <li>Feature rule: Photos unlimited (Pixel XL marlin), Snapchat (Pixel XL).</li>
 *   <li>Integrity rule: GMS / Play Store get the PIF config (full spoof).</li>
 *   <li>Otherwise: preserve the device's build fields.</li>
 * </ol>
 *
 * <p>Spoof mode ({@code yasvara_spoof_mode}): 0 = auto (full for GMS/Photos/Snap,
 * model-only for media+games to protect Widevine L1), 1 = full everywhere,
 * 2 = model-only everywhere. Camera packages are always exempt.
 *
 * @hide
 */
public final class YasvaraSpoofEngine {
    private static final String TAG = "YasvaraSpoof";

    // Settings.Secure keys (string literals: readable from any app process).
    static final String KEY_PIF_ENABLED = "yasvara_pif_enabled";
    static final String KEY_PIF_CONFIG = "yasvara_pif_config";
    static final String KEY_SPOOF_MODE = "yasvara_spoof_mode";
    static final String KEY_PHOTOS_SPOOF = "yasvara_photos_spoof";
    static final String KEY_SNAP_SPOOF = "yasvara_snap_spoof";
    static final String KEY_KEYBOX_STAMP = "yasvara_keybox_stamp";
    static final String KEY_PATCH_OVERRIDE = "yasvara_trickystore_patch_override";
    private static final String DG_STAMP_FILE = ".yasvara_dg_stamp";

    /** 0 = auto, 1 = full fingerprint, 2 = model-only (Widevine-safe). */
    static final int MODE_AUTO = 0;
    static final int MODE_FULL = 1;
    static final int MODE_MODEL_ONLY = 2;

    public static final String PKG_GMS = "com.google.android.gms";
    public static final String PKG_GSF = "com.google.android.gsf";
    private static final String PROC_GMS_UNSTABLE = PKG_GMS + ".unstable";
    public static final String PKG_VENDING = "com.android.vending";
    private static final String PKG_PHOTOS = "com.google.android.apps.photos";
    private static final String PKG_SNAPCHAT = "com.snapchat.android";
    private static final String PKG_SI = "com.google.android.settings.intelligence";

    private static final String[] CAMERA_EXEMPT_PREFIX = {
        "com.android.camera",
        "com.google.android.GoogleCamera",
        "com.google.android.cameralite",
        "com.google.android.apps.cameralite",
    };

    /** Fields safe for model-only spoofing (keeps native FINGERPRINT -> Widevine L1). */
    private static final String[] MODEL_ONLY_KEYS = {
        "BRAND", "MANUFACTURER", "MODEL", "PRODUCT", "DEVICE",
    };

    /** Full PIF field set applied to DroidGuard / Play Store. */
    private static final String[] FULL_KEYS = {
        "BRAND", "DEVICE", "FINGERPRINT", "ID", "MANUFACTURER",
        "MODEL", "PRODUCT", "SECURITY_PATCH", "DEVICE_INITIAL_SDK_INT",
        "TYPE", "TAGS", "RELEASE", "INCREMENTAL",
    };

    /** Fields that live in {@link Build.VERSION} rather than {@link Build}. */
    private static final String[] VERSION_KEYS = {
        "SECURITY_PATCH", "DEVICE_INITIAL_SDK_INT", "RELEASE", "INCREMENTAL",
    };

    /**
     * Photos grants free original-quality backup only to 2016 Pixels. Besides the build fields it
     * checks system features: any 2017+ Pixel feature (declared by the GMS sysconfig on this
     * build) means "newer Pixel" and wins over the marlin build fields, while the 2016/Nexus
     * features have to be present. Both sets are rewritten in the Photos process only.
     */
    private static final String[] FEATURES_2016 = {
        "com.google.android.feature.PIXEL_2016_EXPERIENCE",
        "com.google.android.apps.photos.NEXUS_PRELOAD",
        "com.google.android.apps.photos.nexus_preload",
    };

    /** True in the Photos process while the marlin feature rule is active. */
    private static volatile boolean sPhotosFeatures;

    /** True in DroidGuard's process while it gets the PIF profile. */
    private static volatile boolean sIsDroidGuard;

    /** Processes whose identity property reads mirror their spoofed Build fields. */
    private static volatile int sPropSpoofGate = 0; // 0 = unknown, 1 = yes, 2 = no

    /** Optional PIF board/vendor YYYYMM API level (never inferred from DEVICE_INITIAL_SDK_INT). */
    private static volatile String sSpoofedApiLevel;

    /**
     * The device's real Android release ("17"), captured at class init. Class init always
     * precedes {@link #apply}, so this is read before {@link #setProp} can overwrite
     * {@code Build.VERSION.RELEASE} with a PIF value in a spoofed process.
     */
    private static final String PLATFORM_RELEASE = readPlatformRelease();

    private YasvaraSpoofEngine() {}

    private static String readPlatformRelease() {
        try {
            String prop = SystemProperties.get("ro.build.version.release", "").trim();
            if (parseVersionNumber(prop) != null) return prop;
        } catch (Throwable ignored) {
        }
        try {
            String rel = Build.VERSION.RELEASE;
            if (rel != null && parseVersionNumber(rel.trim()) != null) return rel.trim();
        } catch (Throwable ignored) {
        }
        return "17";
    }

    /** Entry point from Instrumentation.newApplication. Never throws. */
    public static void apply(Context context) {
        try {
            if (context == null) return;
            final String packageName = context.getPackageName();
            if (TextUtils.isEmpty(packageName)) return;
            if (Process.isIsolated() || isCameraExempt(packageName)) return;

            String processName = Application.getProcessName();
            if (TextUtils.isEmpty(processName)) processName = packageName;

            final boolean isGms = PKG_GMS.equals(packageName);
            final boolean isGsf = PKG_GSF.equals(packageName);
            final boolean isVending = PKG_VENDING.equals(packageName);
            sPropSpoofGate = 2;

            final int mode = getInt(context, KEY_SPOOF_MODE, MODE_AUTO);
            final boolean forceFull = mode == MODE_FULL;
            final boolean forceModelOnly = mode == MODE_MODEL_ONLY;

            // 1. Per-app profile (Laras for media apps, Pentas for games).
            final YasvaraSpoofProfiles.Profile assigned =
                    YasvaraSpoofProfiles.profileFor(context.getContentResolver(), packageName);
            if (assigned != null) {
                applyProfile(assigned.props, forceFull ? FULL_KEYS : MODEL_ONLY_KEYS);
                return;
            }

            // 2. Feature rules: Photos unlimited + Snapchat.
            if (PKG_PHOTOS.equals(packageName)) {
                if (getInt(context, KEY_PHOTOS_SPOOF, 1) == 1) {
                    applyProfile(YasvaraSpoofProfiles.builtInProfile("marlin").props,
                            forceModelOnly ? MODEL_ONLY_KEYS : FULL_KEYS);
                    sPhotosFeatures = true;
                }
                return;
            }
            if (PKG_SNAPCHAT.equals(packageName)) {
                if (getInt(context, KEY_SNAP_SPOOF, 1) == 1) {
                    applyProfile(YasvaraSpoofProfiles.builtInProfile("marlin").props,
                            forceModelOnly ? MODEL_ONLY_KEYS : FULL_KEYS);
                }
                return;
            }

            // 3. Integrity rule: every GMS/GSF process plus Play Store gets the PIF
            // config (full spoof) when PIF is explicitly enabled. rkpdapp is excluded
            // so RKP requests stay aligned with hardware DICE UDS keys.
            if (isGms || isGsf || isVending) {
                final boolean isGmsUnstable = isGms && PROC_GMS_UNSTABLE.equals(processName);
                final boolean isMainPackageProcess = packageName.equals(processName);
                final boolean pifEnabled = isPifEnabled(context);
                final Map<String, String> pif = pifEnabled ? getPifConfig(context) : null;

                // In-process auto-purge in DroidGuard (.unstable) or main GMS/Vending process.
                if (isGmsUnstable || (isVending && isMainPackageProcess)
                        || (isGms && isMainPackageProcess)) {
                    maybePurgeDroidGuard(context, pifEnabled, pif);
                }

                if (pifEnabled && pif != null && !pif.isEmpty()) {
                    applyProfile(pif, FULL_KEYS);
                    String apiLevel = resolveBoardApiLevel(pif);
                    sSpoofedApiLevel = apiLevel.isEmpty() ? null : apiLevel;
                    sPropSpoofGate = 1;
                    sIsDroidGuard = isGmsUnstable;
                } else {
                    sSpoofedApiLevel = null;
                    sPropSpoofGate = 2;
                }
                return;
            }

            // 4. Indexing quirk (same as upstream practice).
            if (PKG_SI.equals(packageName)) {
                setProp("FINGERPRINT", String.valueOf(Build.TIME));
            }
        } catch (Throwable t) {
            Log.w(TAG, "apply failed", t);
        }
    }

    /**
     * Called from {@code AndroidKeyStoreSpi.engineGetCertificateChain}. DroidGuard judges the
     * device by its key attestation when it can get one, and this device's attestation shows an
     * unlocked, non-Pixel device whatever the build fields say. Without a keybox to stand in for
     * it (TrickyStore off), failing the chain makes DroidGuard fall back to the build fields,
     * which carry the PIF profile.
     */
    public static void onEngineGetCertificateChain() {
        if (!sIsDroidGuard) return;
        final Context ctx = ActivityThread.currentApplication();
        final int enabled = (ctx != null)
                ? getInt(ctx, YasvaraTrickyStore.KEY_ENABLED, 1)
                : SystemProperties.getInt("persist.sys.yasvara.trickystore", 1);
        if (enabled == 1) return;
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a spoofed value for {@code key}, or null to fall through to the real
     * property. Only ever fires inside GMS / Play Store processes, so system_server
     * and every other app keep seeing the truth. Never throws, never blocks, never
     * touches {@code SystemProperties} itself (no recursion).
     *
     * <p>Every spoofed value mirrors this process' own live {@link Build} fields
     * (already PIF-spoofed where it matters). Boot state and the vbmeta digest fall
     * through to native readers; Java never substitutes a boot claim or caches a digest.
     * Called from {@code SystemProperties.get*}.
     */
    /** True if the package receives full PIF spoofing (GMS, GSF, Play Store, Laras self-test). */
    public static boolean isCollectorPackage(String pkg) {
        return PKG_GMS.equals(pkg) || PKG_GSF.equals(pkg)
                || PKG_VENDING.equals(pkg) || YasvaraUpdatePolicy.LARAS_PACKAGE.equals(pkg);
    }

    public static String mapProp(String key) {
        try {
            // Fast-path: 1 volatile read for non-collector processes; 3-char prefix check for non-ro props.
            if (sPropSpoofGate != 1 || key == null || !key.startsWith("ro.")) return null;
            if (key.startsWith("ro.product.") && !key.startsWith("ro.product.build.")) {
                if (key.endsWith(".brand")) return Build.BRAND;
                if (key.endsWith(".model")) return Build.MODEL;
                if (key.endsWith(".manufacturer")) return Build.MANUFACTURER;
                if (key.endsWith(".device")) return Build.DEVICE;
                if (key.endsWith(".name")) return Build.PRODUCT;
                if (key.endsWith(".first_api_level")) {
                    return Build.VERSION.DEVICE_INITIAL_SDK_INT > 0
                            ? String.valueOf(Build.VERSION.DEVICE_INITIAL_SDK_INT)
                            : null;
                }
                return null;
            }
            if (key.endsWith(".build.fingerprint")) return Build.FINGERPRINT;
            if (key.endsWith(".security_patch")) return Build.VERSION.SECURITY_PATCH;
            if (key.endsWith(".build.id") || "ro.build.display.id".equals(key)) return Build.ID;
            if (key.endsWith(".build.tags")) return Build.TAGS;
            if (key.endsWith(".build.type")) return Build.TYPE;
            if (key.endsWith(".build.version.release")) return Build.VERSION.RELEASE;
            if (key.endsWith(".build.version.incremental")) return Build.VERSION.INCREMENTAL;
            switch (key) {
                case "ro.build.product": return Build.DEVICE;
                case "ro.board.first_api_level":
                case "ro.vendor.first_api_level":
                case "ro.odm.first_api_level":
                    return Build.VERSION.DEVICE_INITIAL_SDK_INT > 0
                            ? String.valueOf(Build.VERSION.DEVICE_INITIAL_SDK_INT)
                            : null;
                case "ro.board.api_level":
                case "ro.vendor.api_level":
                case "ro.llndk.api_level":
                    // Never fall back to DEVICE_INITIAL_SDK_INT: Android 15+ uses YYYYMM (e.g. 202404).
                    return sSpoofedApiLevel;
                default: return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * True only after applyProfile() has populated Build.* in GMS / GSF / Play Store.
     * Before apply() runs, returns false so early SystemProperties.get() calls fall through
     * to Bionic's yasvara_prop_hide.cpp instead of reading unspoofed Java Build.* fields.
     */
    private static boolean propSpoofGate() {
        return sPropSpoofGate == 1;
    }

    /** True in GMS, GSF, or Play Store processes. */
    public static boolean isVendingOrGms() {
        int g = sPropSpoofGate;
        if (g != 0) return g == 1;
        try {
            String pkg = ActivityThread.currentPackageName();
            return PKG_GMS.equals(pkg) || PKG_GSF.equals(pkg) || PKG_VENDING.equals(pkg);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Filters {@code PackageManager.getInstalledPackages} for Play Store App Access Risk
     * ({@code ahak} cases 7 and 11 call {@code getInstalledPackages(4)} / {@code GET_SERVICES}).
     * Returning only system packages prevents {@code UNKNOWN_INSTALLED} and
     * {@code FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION} detection while keeping normal Play Store
     * package management intact.
     */
    public static List<PackageInfo> filterInstalledPackages(List<PackageInfo> list, long flags) {
        try {
            if (list == null || flags != PackageManager.GET_SERVICES || !propSpoofGate()) {
                return list;
            }
            List<PackageInfo> out = new ArrayList<>(list.size());
            for (PackageInfo pi : list) {
                if (pi != null && pi.applicationInfo != null
                        && (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    out.add(pi);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return list;
        }
    }

    /**
     * Spoofs {@code PackageManager.getInstallerPackageName} inside GMS / Play Store so all
     * installed packages appear recognized by Play Store ({@code KNOWN_INSTALLED}).
     */
    public static String spoofInstallerPackageName(String installer) {
        try {
            if (propSpoofGate()) {
                return PKG_VENDING;
            }
        } catch (Throwable ignored) {
        }
        return installer;
    }

    /**
     * Spoofs {@code PackageManager.getInstallSourceInfo} inside GMS / Play Store so both
     * {@code ainv.m9556e} and {@code agny} (case 14/15) see {@code com.android.vending} and
     * {@code PACKAGE_SOURCE_STORE}.
     */
    public static InstallSourceInfo spoofInstallSourceInfo(InstallSourceInfo info) {
        try {
            if (info != null && propSpoofGate()) {
                return new InstallSourceInfo(
                        PKG_VENDING,
                        info.getInitiatingPackageSigningInfo(),
                        null,
                        PKG_VENDING,
                        PKG_VENDING,
                        PackageInstaller.PACKAGE_SOURCE_STORE);
            }
        } catch (Throwable ignored) {
        }
        return info;
    }

    /**
     * Called from {@code ApplicationPackageManager.hasSystemFeature}. Returns the spoofed answer,
     * or null to fall through to the real feature set.
     */
    public static Boolean hasSystemFeature(String name) {
        if (name == null) return null;
        if (propSpoofGate() && ("android.hardware.keystore.app_attest_key".equals(name)
                || "android.software.device_id_attestation".equals(name))) {
            return Boolean.FALSE;
        }
        if (!sPhotosFeatures) return null;
        for (String f : FEATURES_2016) {
            if (f.equals(name)) return Boolean.TRUE;
        }
        return isNewPixelFeature(name) ? Boolean.FALSE : null;
    }

    /** Called from {@code ApplicationPackageManager.getSystemAvailableFeatures}. */
    public static FeatureInfo[] filterSystemFeatures(FeatureInfo[] features) {
        if (features == null) return null;
        final boolean gate = propSpoofGate();
        if (!sPhotosFeatures && !gate) return features;
        final List<FeatureInfo> out = new ArrayList<>(features.length + FEATURES_2016.length);
        for (FeatureInfo fi : features) {
            if (fi.name != null) {
                if (gate && ("android.hardware.keystore.app_attest_key".equals(fi.name)
                        || "android.software.device_id_attestation".equals(fi.name))) {
                    continue;
                }
                if (sPhotosFeatures && isNewPixelFeature(fi.name)) {
                    continue;
                }
            }
            out.add(fi);
        }
        if (sPhotosFeatures) {
            for (String f : FEATURES_2016) {
                boolean present = false;
                for (FeatureInfo fi : out) {
                    if (f.equals(fi.name)) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    FeatureInfo fi = new FeatureInfo();
                    fi.name = f;
                    out.add(fi);
                }
            }
        }
        return out.toArray(new FeatureInfo[0]);
    }

    /** PIXEL_2017_EXPERIENCE, PIXEL_EXPERIENCE, photos.PIXEL_2019_PRELOAD, ... (not 2016). */
    private static boolean isNewPixelFeature(String name) {
        return name.contains(".PIXEL_") && !name.contains("PIXEL_2016");
    }

    private static boolean isCameraExempt(String pkg) {
        for (String prefix : CAMERA_EXEMPT_PREFIX) {
            if (pkg.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Purges DroidGuard's SQLite token cache (dg.db) and VM bytecode cache (app_dgp)
     * inside com.google.android.gms.unstable whenever any attestation-relevant state
     * (PIF toggle/profile, TrickyStore toggle, patch override, or keybox) changes.
     */
    private static void maybePurgeDroidGuard(
            Context context, boolean pifEnabled, Map<String, String> pif) {
        try {
            UserManager um = context.getSystemService(UserManager.class);
            if (um != null && !um.isUserUnlocked()) {
                // Wait until CE storage is unlocked by user.
                return;
            }

            ContentResolver cr = context.getContentResolver();
            int tsEnabled = getInt(context, YasvaraTrickyStore.KEY_ENABLED, 1);
            String tsTargets = Settings.Secure.getString(cr, YasvaraTrickyStore.KEY_TARGETS);
            String patchOverride = Settings.Secure.getString(cr, KEY_PATCH_OVERRIDE);
            String kbStamp = Settings.Secure.getString(cr, KEY_KEYBOX_STAMP);

            StringBuilder state = new StringBuilder(320);
            state.append("pif=").append(pifEnabled ? "1" : "0")
                 .append("|fp=").append(pif != null ? pif.getOrDefault("FINGERPRINT", "") : "")
                 .append("|sp=").append(pif != null ? pif.getOrDefault("SECURITY_PATCH", "") : "")
                 .append("|id=").append(pif != null ? pif.getOrDefault("ID", "") : "")
                 .append("|rel=").append(pif != null ? pif.getOrDefault("RELEASE", "") : "")
                 .append("|brand=").append(pif != null ? pif.getOrDefault("BRAND", "") : "")
                 .append("|dev=").append(pif != null ? pif.getOrDefault("DEVICE", "") : "")
                 .append("|model=").append(pif != null ? pif.getOrDefault("MODEL", "") : "")
                 .append("|sdk=").append(pif != null ? pif.getOrDefault("DEVICE_INITIAL_SDK_INT", "") : "")
                 .append("|ts=").append(tsEnabled)
                 .append("|targets=").append(tsTargets != null ? tsTargets : "")
                 .append("|po=").append(patchOverride != null ? patchOverride : "")
                 .append("|kb=").append(kbStamp != null ? kbStamp : "");
            String currentStamp = Integer.toHexString(state.toString().hashCode());

            File dataDir = context.getDataDir();
            if (dataDir == null) return;
            File stampFile = new File(dataDir, DG_STAMP_FILE);

            if (stampFile.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(stampFile))) {
                    String savedStamp = r.readLine();
                    if (currentStamp.equals(savedStamp != null ? savedStamp.trim() : "")) {
                        return; // State unchanged, dg.db cache is valid.
                    }
                } catch (Throwable ignored) {
                }
            }

            // State changed: delete DroidGuard & GMS/Play Integrity verdict caches.
            context.deleteDatabase("dg.db");
            context.deleteDatabase("gmscompliance.db");
            Context deCtx = context.createDeviceProtectedStorageContext();
            if (deCtx != null) {
                deCtx.deleteDatabase("dg.db");
                deCtx.deleteDatabase("gmscompliance.db");
            }

            purgeIntegrityDirs(dataDir);
            if (deCtx != null && deCtx.getDataDir() != null) {
                purgeIntegrityDirs(deCtx.getDataDir());
            }

            // Write new stamp.
            try (FileWriter w = new FileWriter(stampFile, false)) {
                w.write(currentStamp);
            }
            Log.i(TAG, "Purged DroidGuard/Integrity caches on state change: " + currentStamp);
        } catch (Throwable t) {
            Log.w(TAG, "maybePurgeDroidGuard failed", t);
        }
    }

    static void purgeIntegrityDirs(File rootDir) {
        if (rootDir == null || !rootDir.exists()) return;
        deleteDirRecursive(new File(rootDir, "app_dgp"));
        deleteDirRecursive(new File(rootDir, "app_dg_cache"));
        deleteDirRecursive(new File(rootDir, "files/gmscompliance"));
        deleteFileByNameRecursive(rootDir, "gmscompliance.pb");
        purgeMatchingFiles(new File(rootDir, "files"));
        purgeMatchingFiles(new File(rootDir, "files/finsky/shared"));
        purgeMatchingFiles(new File(rootDir, "shared_prefs"));
        purgeMatchingFiles(new File(rootDir, "cache"));
    }

    private static void purgeMatchingFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) continue;
            String name = child.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("droidguard") || name.contains("play_integrity")
                    || name.contains("express_integrity") || name.contains("device_integrity")
                    || name.contains("gmscompliance") || name.startsWith("compliance_product_id")
                    || "dg_shared_preferences.xml".equals(name)) {
                child.delete();
            }
        }
    }

    private static void deleteFileByNameRecursive(File dir, String targetName) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                deleteFileByNameRecursive(child, targetName);
            } else if (targetName.equals(child.getName())) {
                child.delete();
            }
        }
    }

    private static void deleteDirRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursive(child);
                }
            }
        }
        file.delete();
    }

    /** True when PIF spoofing is enabled in Settings.Secure or vendor persist properties. */
    public static boolean isPifEnabled(Context ctx) {
        if (ctx != null) {
            return getInt(ctx, KEY_PIF_ENABLED, 1) == 1;
        }
        return SystemProperties.getInt("persist.sys.yasvara.pif", 1) == 1;
    }

    /** Reads the PIF JSON ({FINGERPRINT, MODEL, SECURITY_PATCH, ...}) from Secure, fallback, or staged persist props. */
    public static Map<String, String> getPifConfig(Context ctx) {
        try {
            String raw = null;
            boolean settingsReadable = true;
            if (ctx != null) {
                try {
                    raw = Settings.Secure.getString(ctx.getContentResolver(), KEY_PIF_CONFIG);
                } catch (Throwable t) {
                    settingsReadable = false;
                }
            } else {
                settingsReadable = false;
            }
            if (!TextUtils.isEmpty(raw)) {
                Map<String, String> out = parseAndNormalizePifJson(raw);
                return (out == null || out.isEmpty()) ? null : out;
            }

            if (!settingsReadable) {
                Map<String, String> staged = new HashMap<>();
                putPropIfAbsent(staged, "BRAND", "persist.sys.yasvara.pif.brand");
                putPropIfAbsent(staged, "DEVICE", "persist.sys.yasvara.pif.device");
                putPropIfAbsent(staged, "FINGERPRINT", "persist.sys.yasvara.pif.fingerprint");
                putPropIfAbsent(staged, "ID", "persist.sys.yasvara.pif.build_id");
                putPropIfAbsent(staged, "MANUFACTURER", "persist.sys.yasvara.pif.manufacturer");
                putPropIfAbsent(staged, "MODEL", "persist.sys.yasvara.pif.model");
                putPropIfAbsent(staged, "PRODUCT", "persist.sys.yasvara.pif.name");
                putPropIfAbsent(staged, "SECURITY_PATCH", "persist.sys.yasvara.pif.security_patch");
                putPropIfAbsent(staged, "DEVICE_INITIAL_SDK_INT", "persist.sys.yasvara.pif.first_api_level");
                putPropIfAbsent(staged, "TYPE", "persist.sys.yasvara.pif.build_type");
                putPropIfAbsent(staged, "TAGS", "persist.sys.yasvara.pif.build_tags");
                putPropIfAbsent(staged, "RELEASE", "persist.sys.yasvara.pif.release");
                putPropIfAbsent(staged, "INCREMENTAL", "persist.sys.yasvara.pif.incremental");
                putPropIfAbsent(staged, "BOARD_API_LEVEL", "persist.sys.yasvara.pif.api_level");
                deriveFieldsFromFingerprint(staged);
                if (!staged.isEmpty()) {
                    return staged;
                }
            }

            raw = readFallback();
            if (!TextUtils.isEmpty(raw)) {
                Map<String, String> out = parseAndNormalizePifJson(raw);
                return (out == null || out.isEmpty()) ? null : out;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Parses PIF JSON, derives Build fields from FINGERPRINT before fallback defaults, and normalizes SDK fields. */
    static Map<String, String> parseAndNormalizePifJson(String raw) {
        return parseAndNormalizePifJson(raw, PLATFORM_RELEASE);
    }

    /**
     * As {@link #parseAndNormalizePifJson(String)}, healing codename/Canary RELEASE values
     * against {@code platformRelease} (the device's real Android release, e.g. "17").
     */
    static Map<String, String> parseAndNormalizePifJson(String raw, String platformRelease) {
        if (TextUtils.isEmpty(raw)) return null;
        try {
            Map<String, String> out = new HashMap<>();
            JSONObject root = new JSONObject(raw);
            JSONObject o = root.optJSONObject("pif");
            if (o == null) o = root;
            String[] keys = {
                "BRAND", "DEVICE", "FINGERPRINT", "ID", "MANUFACTURER",
                "MODEL", "PRODUCT", "SECURITY_PATCH", "DEVICE_INITIAL_SDK_INT",
                "FIRST_API_LEVEL", "TYPE", "TAGS", "RELEASE", "INCREMENTAL",
                "BOARD_API_LEVEL", "API_LEVEL"
            };
            for (String k : keys) {
                String v = o.optString(k, "").trim();
                if (!TextUtils.isEmpty(v)) out.put(k, v);
            }
            deriveFieldsFromFingerprint(out, platformRelease);
            if (!out.containsKey("DEVICE_INITIAL_SDK_INT")) {
                String firstApi = out.getOrDefault("FIRST_API_LEVEL", "");
                if (TextUtils.isEmpty(firstApi)) {
                    String apiLevel = out.getOrDefault("API_LEVEL", "");
                    try {
                        if (!TextUtils.isEmpty(apiLevel) && Integer.parseInt(apiLevel) <= 100) {
                            firstApi = apiLevel;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (!TextUtils.isEmpty(firstApi)) {
                    out.put("DEVICE_INITIAL_SDK_INT", firstApi);
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void putPropIfAbsent(Map<String, String> out, String key, String propName) {
        if (out.containsKey(key)) return;
        String val = SystemProperties.get(propName, "").trim();
        if (!TextUtils.isEmpty(val)) {
            out.put(key, val);
        }
    }

    /** Returns YYYYMM board/vendor/llndk api_level if explicitly present, never an SDK int. */
    public static String resolveBoardApiLevel(Map<String, String> pif) {
        if (pif == null) return "";
        String board = pif.getOrDefault("BOARD_API_LEVEL", "").trim();
        if (!board.isEmpty()) return board;
        String api = pif.getOrDefault("API_LEVEL", "").trim();
        if (api.length() == 6) {
            try {
                if (Integer.parseInt(api) >= 200000) return api;
            } catch (NumberFormatException ignored) {
            }
        }
        return "";
    }

    /**
     * Resolves KeyMint TAG_OS_VERSION (tag 705) from a PIF profile map.
     * Matches keystore2/src/yasvara/mod.rs logic for parity on Canary and Trunk Stable builds.
     */
    public static int resolvePifOsVersion(Map<String, String> pif) {
        return resolvePifOsVersion(pif, PLATFORM_RELEASE);
    }

    /**
     * As {@link #resolvePifOsVersion(Map)}. When FINGERPRINT carries a codename release
     * segment (":CANARY/"), a missing, codename or stale RELEASE (e.g. "16" cached before the
     * device moved to Android 17) is healed via {@link #inferReleaseFromFingerprintOrPlatform}.
     */
    static int resolvePifOsVersion(Map<String, String> pif, String platformRelease) {
        if (pif == null || pif.isEmpty()) return -1;
        String healed = healedCodenameRelease(pif.get("FINGERPRINT"), pif.get("RELEASE"),
                pif.get("ID"), platformRelease);
        if (healed != null) {
            Integer healedVer = parseVersionNumber(healed);
            if (healedVer != null) return healedVer;
        }
        Integer relVer = parseVersionNumber(pif.get("RELEASE"));
        if (relVer != null) return relVer;

        Integer buildIdVer = inferVersionFromBuildId(pif.get("ID"));
        if (buildIdVer != null) return buildIdVer;

        // Pure codenames without numeric info default to 0 (AOSP Keymaster).
        return 0;
    }

    /** Parses numeric release string ("16", "16.1", "14.0.1") -> major*10000 + minor*100 + sub. */
    public static Integer parseVersionNumber(String rel) {
        if (rel == null) return null;
        String trimmed = rel.trim();
        if (trimmed.isEmpty()) return null;
        try {
            String[] parts = trimmed.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int sub = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return major * 10000 + minor * 100 + sub;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Infers OS version from Build ID when RELEASE is a codename (e.g. "CANARY", "Baklava"). */
    public static Integer inferVersionFromBuildId(String buildId) {
        if (buildId == null) return null;
        String id = buildId.trim();
        if (id.isEmpty()) return null;

        // Google Trunk Stable & Pixel Canary
        if (id.startsWith("CP2") || id.startsWith("CP3")
                || id.startsWith("CP4") || id.startsWith("DP1")) {
            return 170000;
        }
        if (id.startsWith("ZP11.")) {
            // Rolling Android Canary: ZP11.25* = Android 16 (2025), ZP11.26* = Android 17 (2026)+.
            if (id.length() >= 7 && Character.isDigit(id.charAt(5)) && Character.isDigit(id.charAt(6))) {
                int yy = (id.charAt(5) - '0') * 10 + (id.charAt(6) - '0');
                if (yy >= 26) return (yy - 9) * 10000;
            }
            return 160000;
        }
        if (id.startsWith("BP2") || id.startsWith("BP3")
                || id.startsWith("BP4") || id.startsWith("BD") || id.startsWith("CP1")) {
            return 160000;
        }
        if (id.startsWith("AP3") || id.startsWith("AP4")
                || id.startsWith("AD") || id.startsWith("BP1")) {
            return 150000;
        }
        if (id.startsWith("AP1") || id.startsWith("AP2")) {
            return 140000;
        }

        // Standard OEM build ID prefixes (U=14, T=13, S=12, R=11, Q=10)
        switch (id.charAt(0)) {
            case 'U': return 140000;
            case 'T': return 130000;
            case 'S': return 120000;
            case 'R': return 110000;
            case 'Q': return 100000;
            default: return null;
        }
    }

    /**
     * Expands FINGERPRINT (brand/product/device:release/id/incremental:type/tags) into its
     * constituent Build and Build.VERSION fields when not explicitly specified in the PIF config.
     * Prevents GMS/DroidGuard cant.java from cross-checking a user/release-keys fingerprint
     * against a userdebug/test-keys Build.TYPE/TAGS.
     */
    private static void deriveFieldsFromFingerprint(Map<String, String> out) {
        deriveFieldsFromFingerprint(out, PLATFORM_RELEASE);
    }

    private static void deriveFieldsFromFingerprint(Map<String, String> out,
            String platformRelease) {
        String fp = out.get("FINGERPRINT");
        if (fp == null) return;
        int colon = fp.indexOf(':');
        if (colon < 0) return;
        String[] head = fp.substring(0, colon).split("/");
        String[] rest = fp.substring(colon + 1).split("/");
        if (rest.length < 4) return;
        String[] incrementalAndType = rest[2].split(":", 2);
        if (incrementalAndType.length != 2) return;
        if (head.length == 3) {
            out.putIfAbsent("BRAND", head[0]);
            out.putIfAbsent("PRODUCT", head[1]);
            out.putIfAbsent("DEVICE", head[2]);
        }
        out.putIfAbsent("RELEASE", rest[0]);
        out.putIfAbsent("ID", rest[1]);
        out.putIfAbsent("INCREMENTAL", incrementalAndType[0]);
        out.putIfAbsent("TYPE", incrementalAndType[1]);
        out.putIfAbsent("TAGS", rest[3]);
        // Codename fingerprint (":CANARY/"): heal a missing, codename or stale RELEASE
        // (legacy cached profiles carry "16" while the device runs 17).
        String healed = healedCodenameRelease(fp, out.get("RELEASE"), out.get("ID"),
                platformRelease);
        if (healed != null) {
            out.put("RELEASE", healed);
            return;
        }
        String rel = out.get("RELEASE");
        if (parseVersionNumber(rel) == null) {
            Integer inferred = inferVersionFromBuildId(out.get("ID"));
            if (inferred != null && inferred > 0) {
                out.put("RELEASE", String.valueOf(inferred / 10000));
            }
        }
    }

    /** Release segment of FINGERPRINT (brand/product/device:RELEASE/id/...), or null. */
    static String fingerprintRelease(String fp) {
        if (fp == null) return null;
        int colon = fp.indexOf(':');
        if (colon < 0) return null;
        int slash = fp.indexOf('/', colon + 1);
        if (slash < 0) return null;
        String rel = fp.substring(colon + 1, slash).trim();
        return rel.isEmpty() ? null : rel;
    }

    /** Build ID segment of FINGERPRINT (…:release/ID/incremental:…), or null. */
    private static String fingerprintBuildId(String fp) {
        if (fp == null) return null;
        int colon = fp.indexOf(':');
        if (colon < 0) return null;
        String[] rest = fp.substring(colon + 1).split("/");
        if (rest.length < 2) return null;
        String id = rest[1].trim();
        return id.isEmpty() ? null : id;
    }

    /**
     * Returns the RELEASE a profile must carry when its FINGERPRINT has a non-numeric
     * release segment (":CANARY/", ":Baklava/") and {@code current} is missing, non-numeric,
     * or a different major than the inferred one; null when {@code current} is fine as is
     * (numeric fingerprint release, matching major, or nothing inferable).
     */
    static String healedCodenameRelease(String fp, String current, String buildId,
            String platformRelease) {
        String fpRel = fingerprintRelease(fp);
        if (fpRel == null || parseVersionNumber(fpRel) != null) return null;
        String id = (buildId == null || buildId.trim().isEmpty()) ? fingerprintBuildId(fp) : buildId;
        String inferred = inferReleaseFromFingerprintOrPlatform(fpRel, id, platformRelease);
        if (inferred == null) return null;
        Integer cur = parseVersionNumber(current);
        if (cur != null && cur / 10000 == Integer.parseInt(inferred)) return null;
        return inferred;
    }

    /**
     * Major Android release for a codename fingerprint release segment. Named dessert
     * codenames map to their fixed release. "CANARY" (and unknown codenames) is a rolling
     * channel that is never behind the newest stable release, so it resolves to the larger of
     * the build-ID inference and the device's own platform release: a ZP11.* Canary cached as
     * "16" becomes "17" on an Android 17 device. Returns null when nothing is inferable.
     */
    public static String inferReleaseFromFingerprintOrPlatform(String fpRelease, String buildId,
            String platformRelease) {
        Integer named = releaseForCodename(fpRelease);
        if (named != null) return String.valueOf(named);
        Integer fromId = inferVersionFromBuildId(buildId);
        Integer platform = parseVersionNumber(platformRelease);
        int idMajor = fromId != null ? fromId / 10000 : 0;
        int platformMajor = platform != null ? platform / 10000 : 0;
        int best = Math.max(idMajor, platformMajor);
        return best > 0 ? String.valueOf(best) : null;
    }

    private static Integer releaseForCodename(String codename) {
        if (codename == null) return null;
        switch (codename.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "cinnamonbun": return 17;
            case "baklava": return 16;
            case "vanillaicecream": return 15;
            case "upsidedowncake": return 14;
            case "tiramisu": return 13;
            default: return null;
        }
    }

    private static void applyProfile(Map<String, String> profile, String[] keys) {
        for (String k : keys) {
            String v = profile.get(k);
            if (v != null) setProp(k, v);
        }
    }

    private static int getInt(Context ctx, String key, int def) {
        try {
            int v = Settings.Secure.getInt(ctx.getContentResolver(), key, Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE) return v;
        } catch (Throwable ignored) {
        }
        // Fall back to vendor persist defaults (see vendor/yasvara/spoof).
        try {
            String persist = persistFor(key);
            if (persist != null) {
                return SystemProperties.getInt(persist, def);
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static String persistFor(String key) {
        if (KEY_PIF_ENABLED.equals(key)) return "persist.sys.yasvara.pif";
        if (KEY_PHOTOS_SPOOF.equals(key)) return "persist.sys.yasvara.photos_spoof";
        if (KEY_SNAP_SPOOF.equals(key)) return "persist.sys.yasvara.snap_spoof";
        if (YasvaraTrickyStore.KEY_ENABLED.equals(key)) return "persist.sys.yasvara.trickystore";
        return null;
    }

    private static String readFallback() {
        try (BufferedReader r = new BufferedReader(
                new FileReader("/product/etc/yasvara/pif_fallback.json"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void setProp(String key, Object value) {
        try {
            Class<?> owner = Build.class;
            for (String k : VERSION_KEYS) {
                if (k.equals(key)) {
                    owner = Build.VERSION.class;
                    break;
                }
            }
            Field f = owner.getDeclaredField(key);
            f.setAccessible(true);
            Class<?> type = f.getType();
            if (type == String.class) {
                f.set(null, String.valueOf(value));
            } else if (type == int.class || type == Integer.class) {
                f.setInt(null, Integer.parseInt(String.valueOf(value)));
            } else if (type == long.class || type == Long.class) {
                f.setLong(null, Long.parseLong(String.valueOf(value)));
            }
            f.setAccessible(false);
        } catch (Throwable t) {
            Log.w(TAG, "setProp " + key + " failed", t);
        }
    }
}