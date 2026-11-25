/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.padat;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.IServiceManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.Service;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BinderInternal;
import com.android.server.FgThread;
import com.android.server.ServiceThread;
import com.android.server.art.ArtManagerLocal;
import com.android.server.art.model.DexoptResult;
import com.android.server.pm.DexOptHelper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import yasvara.padat.IPadat;

/**
 * Padat realtime per-app F2FS compression coordinator.
 *
 * <p>Thin Java service in {@code system_server}: delegates all Smart Probe analysis, policy
 * persistence, and atomic F2FS compression work to the lazy native Rust daemon {@code padatd}
 * ({@code yasvara.padat}), and bridges Android 17 lifecycle hooks (APK install staging,
 * {@code ACTION_PACKAGE_ADDED}, ART {@code DexoptDoneCallback}, Pentas game auto-pause,
 * and idle+charging block-release maintenance).
 */
public final class PadatService extends IYasvaraPadat.Stub {
    private static final String TAG = "PadatService";

    public static final String SERVICE_NAME = "yasvara_padat";
    public static final String SHELL_ALIAS = "padat";
    private static final String DAEMON_SERVICE = "yasvara.padat";
    static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_PADAT";
    private static final String KEY_GAME_FOCUS = "pentas_game_focus";

    static final int SCOPE_APK_LIB = 0x01;
    static final int SCOPE_ART_OAT = 0x02;
    static final int SCOPE_EXT_DATA = 0x04;
    static final int SCOPE_OBB = 0x08;
    static final int SCOPE_INTERNAL = 0x10;
    static final int SCOPE_TIER1 = SCOPE_APK_LIB | SCOPE_ART_OAT;
    static final int SCOPE_FULL = SCOPE_APK_LIB | SCOPE_ART_OAT | SCOPE_EXT_DATA | SCOPE_OBB;

    private static final long MAINT_MIN_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);
    private static final long MAINT_SCREEN_OFF_DELAY_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long POST_SESSION_SYNC_DELAY_MS = TimeUnit.SECONDS.toMillis(90);
    private static final long DAEMON_WAKE_TIMEOUT_MS = 2500L;
    private static final long DAEMON_WAKE_BACKOFF_MS = 3000L;

    private static volatile PadatService sInstance;

    private final Context mContext;
    /** Lightweight shared handler for fast, non-blocking event/observer callbacks only. */
    private final Handler mEventHandler;
    /** Dedicated background worker thread for all blocking padatd IPCs & filesystem scans. */
    private final ServiceThread mWorkerThread;
    private final Handler mWorkerHandler;
    private final HandlerExecutor mWorkerExecutor;
    private final Object mDaemonLock = new Object();
    private IPadat mDaemon;
    private volatile long mLastDaemonWakeFailMs = 0L;

    private final Object mPolicyLock = new Object();
    private boolean mPolicyLoaded = false;
    private boolean mAutoTier1 = false;
    private final ArrayMap<String, AppPolicyCache> mAppPolicies = new ArrayMap<>();

    private volatile boolean mUserPaused = false;
    private volatile boolean mGamePaused = false;
    private volatile boolean mScreenInteractive = true;
    private volatile boolean mDexoptHooked = false;
    private String mActiveGamePkg = "";
    private volatile String mPendingSessionSyncPkg = "";
    private long mLastMaintElapsedMs = 0L;

    private final Runnable mMaintRunnable = this::runIdleMaintenance;
    private final Runnable mPostSessionSyncRunnable = this::runPostSessionSync;

    private static final class AppPolicyCache {
        final String mode; // "off", "fast", "balanced", "max", "custom"
        final int algo;    // 0=lz4, 1=lz4hc, 2=zstd
        final int level;
        final int clusterKb;
        final int scopeMask;

        AppPolicyCache(String mode, int algo, int level, int clusterKb, int scopeMask) {
            this.mode = mode;
            this.algo = algo;
            this.level = level;
            this.clusterKb = clusterKb;
            this.scopeMask = scopeMask;
        }
    }

    public PadatService(@NonNull Context context) {
        mContext = context;
        mEventHandler = BackgroundThread.getHandler();
        mWorkerThread = new ServiceThread("PadatWorker", Process.THREAD_PRIORITY_BACKGROUND,
                /* allowIo= */ true);
        mWorkerThread.start();
        mWorkerHandler = new Handler(mWorkerThread.getLooper());
        mWorkerExecutor = new HandlerExecutor(mWorkerHandler);
        sInstance = this;

        registerReceivers();
        registerGameFocusObserver();
    }

    private void registerReceivers() {
        final IntentFilter bootFilter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                mWorkerHandler.post(PadatService.this::onBootCompleted);
            }
        }, bootFilter, null, mEventHandler);

        final IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgFilter.addDataScheme("package");
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                final Uri data = intent.getData();
                if (data == null) return;
                final String pkg = data.getSchemeSpecificPart();
                if (TextUtils.isEmpty(pkg)) return;
                mWorkerHandler.post(() -> onPackageInstalledOrUpdated(pkg));
            }
        }, pkgFilter, null, mEventHandler);

        // Foreground broadcasts (SCREEN_ON/OFF, 10s ANR timeout) MUST run on a non-blocking
        // handler and never share a Looper with long-running padatd IPCs.
        final IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                final String act = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(act)) {
                    mScreenInteractive = true;
                    mWorkerHandler.removeCallbacks(mMaintRunnable);
                } else if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                    mScreenInteractive = false;
                    scheduleIdleMaintenanceIfEligible();
                }
            }
        }, screenFilter, null, FgThread.getHandler());
    }

    private void registerGameFocusObserver() {
        try {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(KEY_GAME_FOCUS),
                    false,
                    new ContentObserver(mEventHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            updateGameFocusPause();
                        }
                    },
                    UserHandle.USER_ALL);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to observe " + KEY_GAME_FOCUS, e);
        }
    }

    private void updateGameFocusPause() {
        final String focus = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), KEY_GAME_FOCUS, UserHandle.USER_CURRENT);
        final String cleanFocus = focus != null ? focus.trim() : "";
        final boolean inGame = !cleanFocus.isEmpty();

        // Track opted-in game exit so any in-game asset updates / patches downloaded during the
        // session get incrementally released & compressed 90 seconds after exiting the game!
        // Never perform blocking daemon IPCs on BackgroundThread here; defer policy check to
        // PadatWorker when the delayed runnable fires.
        final String prevGame = mActiveGamePkg;
        mActiveGamePkg = cleanFocus;
        if (!inGame && !prevGame.isEmpty()) {
            schedulePostSessionSyncIfOptedIn(prevGame);
        } else if (inGame && cleanFocus.equals(mPendingSessionSyncPkg)) {
            mWorkerHandler.removeCallbacks(mPostSessionSyncRunnable);
            mPendingSessionSyncPkg = "";
        }

        if (inGame == mGamePaused) return;
        mGamePaused = inGame;
        // Only notify if padatd is currently alive — never wake an idle daemon for a game launch.
        // IPadat.setPaused is oneway so this never blocks BackgroundThread.
        final IPadat d = peekDaemon();
        if (d != null) {
            try {
                d.setPaused(mGamePaused || mUserPaused);
            } catch (RemoteException ignored) {
            }
        }
    }

    private void schedulePostSessionSyncIfOptedIn(@NonNull String pkg) {
        final boolean loaded;
        final AppPolicyCache ap;
        synchronized (mPolicyLock) {
            loaded = mPolicyLoaded;
            ap = mAppPolicies.get(pkg);
        }
        if (loaded) {
            if (ap == null || "off".equals(ap.mode)) return;
            // Only needed when data/obb/internal scopes are enabled (in-game patches).
            if ((ap.scopeMask & (SCOPE_EXT_DATA | SCOPE_OBB | SCOPE_INTERNAL)) == 0) return;
        }
        mPendingSessionSyncPkg = pkg;
        mWorkerHandler.removeCallbacks(mPostSessionSyncRunnable);
        mWorkerHandler.postDelayed(mPostSessionSyncRunnable, POST_SESSION_SYNC_DELAY_MS);
    }

    private void runPostSessionSync() {
        final String pkg = mPendingSessionSyncPkg;
        mPendingSessionSyncPkg = "";
        if (TextUtils.isEmpty(pkg) || mGamePaused) return;
        ensurePolicyLoaded();
        final AppPolicyCache ap = getCachedAppPolicy(pkg);
        if (ap == null || "off".equals(ap.mode)) return;
        if ((ap.scopeMask & (SCOPE_EXT_DATA | SCOPE_OBB | SCOPE_INTERNAL)) == 0) return;
        try {
            final IPadat d = requireDaemon();
            if (d == null) return;
            final String rep = d.syncPackageJson(pkg, ap.algo, ap.level, ap.clusterKb, ap.scopeMask);
            Slog.i(TAG, "Post-session in-game update sync for " + pkg + ": " + rep);
        } catch (Exception e) {
            Slog.w(TAG, "Post-session sync failed for " + pkg + ": " + e.getMessage());
        }
    }

    private void onBootCompleted() {
        ensurePolicyLoaded();
        hookArtDexoptCallback();
        // Clean up any `.padat.*.tmp` orphaned by a sudden power loss.
        try {
            final IPadat d = requireDaemon();
            if (d != null) {
                final long removed = d.cleanupStaleTemps();
                if (removed > 0) {
                    Slog.i(TAG, "Cleaned up " + removed + " stale Padat temp files");
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Boot cleanup skipped: " + e.getMessage());
        }
    }

    private void hookArtDexoptCallback() {
        if (mDexoptHooked) return;
        try {
            final ArtManagerLocal art = DexOptHelper.getArtManagerLocal();
            art.addDexoptDoneCallback(true /* onlyIncludeUpdates */,
                    mWorkerExecutor, this::onDexoptDone);
            mDexoptHooked = true;
            Slog.i(TAG, "Hooked ArtManagerLocal.DexoptDoneCallback for realtime oat/vdex release");
        } catch (Throwable t) {
            Slog.w(TAG, "Unable to hook ArtManagerLocal yet: " + t.getMessage());
        }
    }

    private void onDexoptDone(@NonNull DexoptResult result) {
        ensurePolicyLoaded();
        for (DexoptResult.PackageDexoptResult pkgRes : result.getPackageDexoptResults()) {
            final String pkg = pkgRes.getPackageName();
            if (TextUtils.isEmpty(pkg)) continue;
            handlePackageArtifactsPostDexopt(pkg);
        }
    }

    /**
     * Called from {@code PackageInstallerSession.doWriteInternal} before APK bytes hit disk.
     * Applies per-app directory inheritance on {@code stageDir} for ANY opted-in package (Fast,
     * Balanced, Max, Custom) or when global Auto Tier-1 is enabled, so updated APKs & libs
     * compress in realtime in a single write pass!
     */
    public static void onInstallStage(@Nullable String packageName, @Nullable File stageDir) {
        final PadatService svc = sInstance;
        if (svc == null || TextUtils.isEmpty(packageName) || stageDir == null) return;
        final AppPolicyCache ap = svc.getCachedAppPolicy(packageName);
        final int algo;
        final int level;
        final int clusterKb;
        if (ap != null) {
            if ("off".equals(ap.mode) || (ap.scopeMask & SCOPE_APK_LIB) == 0) return;
            algo = ap.algo;
            level = ap.level;
            clusterKb = ap.clusterKb;
        } else if (svc.mAutoTier1) {
            algo = 0;
            level = 0;
            clusterKb = 16;
        } else {
            return;
        }
        try {
            final IPadat d = svc.requireDaemon();
            if (d != null) {
                d.applyDirProfile(stageDir.getAbsolutePath(), algo, level, clusterKb);
            }
        } catch (Exception e) {
            Slog.w(TAG, "onInstallStage(" + packageName + ") failed: " + e.getMessage());
        }
    }

    private void onPackageInstalledOrUpdated(@NonNull String pkg) {
        ensurePolicyLoaded();
        final AppPolicyCache ap = getCachedAppPolicy(pkg);
        final File codeDir = resolveCodeDir(pkg);
        if (codeDir == null) return;

        try {
            final IPadat d = requireDaemon();
            if (d == null) return;

            if (ap != null && "off".equals(ap.mode)) {
                d.decompressPackageJson(pkg, SCOPE_TIER1, true);
                return;
            }
            if (ap != null) {
                // Covers ALL opted-in presets (fast / balanced / max / custom) on APK update!
                // Even if the installer left appPackageName null during staging, incremental sync
                // compresses new Tier-1 files, re-stamps new codeDir/lib/oat dir inheritance,
                // and releases newly written blocks cleanly.
                final int tier1Scopes = ap.scopeMask & SCOPE_TIER1;
                if (tier1Scopes != 0) {
                    d.syncPackageJson(pkg, ap.algo, ap.level, ap.clusterKb, tier1Scopes);
                }
                return;
            }
            if (mAutoTier1) {
                d.syncPackageJson(pkg, 0, 0, 16, SCOPE_TIER1);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Post-install sync failed for " + pkg + ": " + e.getMessage());
        }
    }

    private void handlePackageArtifactsPostDexopt(@NonNull String pkg) {
        final AppPolicyCache ap = getCachedAppPolicy(pkg);
        final File codeDir = resolveCodeDir(pkg);
        if (codeDir == null) return;
        final File oatDir = new File(codeDir, "oat");
        if (!oatDir.isDirectory()) return;

        try {
            final IPadat d = requireDaemon();
            if (d == null) return;

            if (ap != null && "off".equals(ap.mode)) {
                d.decompressPackageJson(pkg, SCOPE_ART_OAT, true);
                return;
            }
            if (ap != null && (ap.scopeMask & SCOPE_ART_OAT) != 0) {
                d.applyDirProfile(oatDir.getAbsolutePath(), ap.algo, ap.level, ap.clusterKb);
                d.syncPackageJson(pkg, ap.algo, ap.level, ap.clusterKb, SCOPE_ART_OAT);
                return;
            }
            if (mAutoTier1) {
                d.applyDirProfile(oatDir.getAbsolutePath(), 0, 0, 16);
                d.syncPackageJson(pkg, 0, 0, 16, SCOPE_ART_OAT);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Post-dexopt release failed for " + pkg + ": " + e.getMessage());
        }
    }

    private void scheduleIdleMaintenanceIfEligible() {
        final long now = SystemClock.elapsedRealtime();
        if (mLastMaintElapsedMs > 0 && (now - mLastMaintElapsedMs) < MAINT_MIN_INTERVAL_MS) {
            return;
        }
        final BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        if (bm == null || !bm.isCharging()) return;
        mWorkerHandler.removeCallbacks(mMaintRunnable);
        mWorkerHandler.postDelayed(mMaintRunnable, MAINT_SCREEN_OFF_DELAY_MS);
    }

    private void runIdleMaintenance() {
        final BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        if (bm == null || !bm.isCharging() || mGamePaused || mScreenInteractive) return;
        ensurePolicyLoaded();
        try {
            final IPadat d = requireDaemon();
            if (d == null) return;
            d.cleanupStaleTemps();
            // Incrementally sync ALL opted-in packages across ALL their configured scopes
            // (including Android/data, OBB, and internal_files) so in-game asset downloads and
            // WOF auto-reserved patches always reclaim disk space automatically!
            final ArrayMap<String, AppPolicyCache> snapshot;
            synchronized (mPolicyLock) {
                snapshot = new ArrayMap<>(mAppPolicies);
            }
            for (int i = 0; i < snapshot.size(); i++) {
                if (mGamePaused || mScreenInteractive || !bm.isCharging()) {
                    Slog.i(TAG, "Idle maintenance deferred (interactive/unplugged)");
                    return;
                }
                final String pkg = snapshot.keyAt(i);
                final AppPolicyCache ap = snapshot.valueAt(i);
                if (ap == null || "off".equals(ap.mode) || ap.scopeMask == 0) continue;
                d.syncPackageJson(pkg, ap.algo, ap.level, ap.clusterKb, ap.scopeMask);
            }
            if (mGamePaused || mScreenInteractive || !bm.isCharging()) {
                return;
            }
            d.releasePathJson("/data/app");
            if (mGamePaused || mScreenInteractive || !bm.isCharging()) {
                return;
            }
            d.releasePathJson("/data/dalvik-cache");
            mLastMaintElapsedMs = SystemClock.elapsedRealtime();
            Slog.i(TAG, "Idle+charging Padat incremental sync complete ("
                    + snapshot.size() + " opted-in packages)");
        } catch (Exception e) {
            Slog.w(TAG, "Idle maintenance error: " + e.getMessage());
        }
    }

    String syncPackageInternal(@NonNull String pkg) {
        ensurePolicyLoaded();
        final AppPolicyCache ap = getCachedAppPolicy(pkg);
        if (ap == null || "off".equals(ap.mode)) {
            return "{\"error\":\"package_not_opted_in\"}";
        }
        try {
            final IPadat d = requireDaemon();
            return d != null
                    ? d.syncPackageJson(pkg, ap.algo, ap.level, ap.clusterKb, ap.scopeMask)
                    : "{\"error\":\"padatd_unavailable\"}";
        } catch (RemoteException e) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        }
    }

    @Nullable
    private File resolveCodeDir(@NonNull String pkg) {
        try {
            final ApplicationInfo ai = mContext.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai == null || TextUtils.isEmpty(ai.sourceDir)) return null;
            final File parent = new File(ai.sourceDir).getParentFile();
            if (parent != null && parent.getAbsolutePath().startsWith("/data/app/")) {
                return parent;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return null;
    }

    private void ensurePolicyLoaded() {
        synchronized (mPolicyLock) {
            if (mPolicyLoaded) return;
        }
        try {
            final IPadat d = requireDaemon();
            if (d != null) {
                parseAndCachePolicy(d.getPolicyJson());
            }
        } catch (Exception ignored) {
        }
    }

    private void parseAndCachePolicy(@Nullable String json) {
        if (TextUtils.isEmpty(json)) return;
        try {
            final JSONObject root = new JSONObject(json);
            final boolean autoTier1 = root.optBoolean("auto_tier1", false);
            final ArrayMap<String, AppPolicyCache> map = new ArrayMap<>();
            JSONObject apps = root.optJSONObject("packages");
            if (apps == null) {
                apps = root.optJSONObject("apps");
            }
            if (apps != null) {
                final Iterator<String> it = apps.keys();
                while (it.hasNext()) {
                    final String pkg = it.next();
                    final JSONObject o = apps.optJSONObject(pkg);
                    if (o == null) continue;
                    final String mode = o.has("preset")
                            ? o.optString("preset", "off")
                            : o.optString("mode", "off");
                    final int algo = algoNameToId(o.optString("algo", "lz4"));
                    final int level = o.optInt("level", 0);
                    final int clusterKb = o.optInt("cluster_kb", 16);
                    int mask = 0;
                    if (o.optBoolean("apk_lib", true)) mask |= SCOPE_APK_LIB;
                    if (o.optBoolean("art_oat", true)) mask |= SCOPE_ART_OAT;
                    if (o.optBoolean("external_data", false)) mask |= SCOPE_EXT_DATA;
                    if (o.optBoolean("obb", false)) mask |= SCOPE_OBB;
                    if (o.optBoolean("internal_files", false)) mask |= SCOPE_INTERNAL;
                    map.put(pkg, new AppPolicyCache(mode, algo, level, clusterKb, mask));
                }
            }
            synchronized (mPolicyLock) {
                mAutoTier1 = autoTier1;
                mAppPolicies.clear();
                mAppPolicies.putAll(map);
                mPolicyLoaded = true;
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to parse policy JSON: " + e.getMessage());
        }
    }

    @Nullable
    private AppPolicyCache getCachedAppPolicy(@NonNull String pkg) {
        synchronized (mPolicyLock) {
            return mAppPolicies.get(pkg);
        }
    }

    static int algoNameToId(@Nullable String name) {
        if ("lz4hc".equalsIgnoreCase(name)) return 1;
        if ("zstd".equalsIgnoreCase(name)) return 2;
        return 0;
    }

    @Nullable
    IPadat peekDaemon() {
        synchronized (mDaemonLock) {
            if (mDaemon != null && mDaemon.asBinder().isBinderAlive()) {
                return mDaemon;
            }
        }
        final IBinder b = ServiceManager.checkService(DAEMON_SERVICE);
        return b != null ? attachDaemon(b) : null;
    }

    /**
     * Triggers lazy startup of {@code padatd} via a single non-blocking {@code getService2} RPC
     * to {@code servicemanager} (handle 0), avoiding {@code ServiceManager.waitForService}'s
     * unbounded blocking loop if the lazy daemon crashes or is unavailable.
     */
    @Nullable
    private static IBinder triggerLazyStart() {
        try {
            final IServiceManager sm = IServiceManager.Stub.asInterface(
                    Binder.allowBlocking(BinderInternal.getContextObject()));
            if (sm == null) return null;
            final Service svc = sm.getService2(DAEMON_SERVICE);
            if (svc == null || svc.getTag() != Service.serviceWithMetadata) return null;
            return Binder.allowBlocking(svc.getServiceWithMetadata().service);
        } catch (Throwable t) {
            Slog.w(TAG, "triggerLazyStart(" + DAEMON_SERVICE + ") failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Connects to or lazily wakes {@code padatd} with a strict bounded deadline
     * ({@value #DAEMON_WAKE_TIMEOUT_MS}ms). Never calls infinite {@code waitForService}.
     */
    @Nullable
    IPadat requireDaemon() {
        synchronized (mDaemonLock) {
            if (mDaemon != null && mDaemon.asBinder().isBinderAlive()) {
                return mDaemon;
            }
        }
        final IPadat existing = peekDaemon();
        if (existing != null) {
            mLastDaemonWakeFailMs = 0L;
            return existing;
        }
        final long now = SystemClock.uptimeMillis();
        final long lastFail = mLastDaemonWakeFailMs;
        if (lastFail > 0L && now - lastFail < DAEMON_WAKE_BACKOFF_MS) {
            return null;
        }
        IBinder b = triggerLazyStart();
        if (b == null) {
            final long deadline = SystemClock.uptimeMillis() + DAEMON_WAKE_TIMEOUT_MS;
            while (SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(30);
                b = ServiceManager.checkService(DAEMON_SERVICE);
                if (b != null) break;
            }
        }
        if (b == null) {
            mLastDaemonWakeFailMs = SystemClock.uptimeMillis();
            Slog.w(TAG, "padatd unavailable after " + DAEMON_WAKE_TIMEOUT_MS + "ms");
            return null;
        }
        mLastDaemonWakeFailMs = 0L;
        return attachDaemon(b);
    }

    @Nullable
    private IPadat attachDaemon(@NonNull IBinder b) {
        final IPadat proxy = IPadat.Stub.asInterface(b);
        if (proxy == null) return null;
        try {
            b.linkToDeath(() -> {
                synchronized (mDaemonLock) {
                    if (mDaemon == proxy) {
                        mDaemon = null;
                    }
                }
            }, 0);
        } catch (RemoteException e) {
            return null;
        }
        synchronized (mDaemonLock) {
            mDaemon = proxy;
        }
        return proxy;
    }

    private void enforceManage() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.ROOT_UID || uid == Process.SYSTEM_UID || uid == Process.SHELL_UID) {
            return;
        }
        mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE,
                "Caller requires " + PERMISSION_MANAGE);
    }

    @Override
    public String getStatusJson() {
        enforceManage();
        final long ident = Binder.clearCallingIdentity();
        try {
            final IPadat d = requireDaemon();
            return d != null ? d.statusJson() : "{\"error\":\"padatd_unavailable\"}";
        } catch (RemoteException e) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String getPolicyJson() {
        enforceManage();
        final long ident = Binder.clearCallingIdentity();
        try {
            final IPadat d = requireDaemon();
            if (d == null) return "{\"error\":\"padatd_unavailable\"}";
            final String json = d.getPolicyJson();
            parseAndCachePolicy(json);
            return json;
        } catch (RemoteException e) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int setPolicyJson(String json) {
        enforceManage();
        final long ident = Binder.clearCallingIdentity();
        try {
            final IPadat d = requireDaemon();
            if (d == null) return -19; // ENODEV
            final int rc = d.setPolicyJson(json != null ? json : "");
            if (rc == 0) {
                parseAndCachePolicy(json);
            }
            return rc;
        } catch (RemoteException e) {
            return -5;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String getAllStatsJson() {
        enforceManage();
        final long ident = Binder.clearCallingIdentity();
        try {
            final IPadat d = requireDaemon();
            return d != null ? d.allStatsJson() : "[]";
        } catch (RemoteException e) {
            return "[]";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String scanPackageJson(String pkg, int algo, int level, int clusterKb, int scopeMask) {
        enforceManage();
        final long ident = Binder.clearCallingIdentity();
        try {
            final IPadat d = requireDaemon();
            return d != null
                    ? d.scanPackageJson(pkg != null ? pkg : "", algo, level, clusterKb, scopeMask)
                    : "{\"error\":\"padatd_unavailable\"}";
        } catch (RemoteException e) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String compressPackageJson(String pkg, int algo, int level, int clusterKb,
            int scopeMask, boolean allowRunning) {
        enforceManage();
        final long ident = Binder.clearCallingIdentity();
        try {
            final IPadat d = requireDaemon();
            return d != null
                    ? d.compressPackageJson(pkg != null ? pkg : "", algo, level, clusterKb,
                            scopeMask, allowRunning)
                    : "{\"error\":\"padatd_unavailable\"}";
        } catch (RemoteException e) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String decompressPackageJson(String pkg, int scopeMask, boolean allowRunning) {
        enforceManage();
        final long ident = Binder.clearCallingIdentity();
        try {
            final IPadat d = requireDaemon();
            return d != null
                    ? d.decompressPackageJson(pkg != null ? pkg : "", scopeMask, allowRunning)
                    : "{\"error\":\"padatd_unavailable\"}";
        } catch (RemoteException e) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String releasePathJson(String path) {
        enforceManage();
        final long ident = Binder.clearCallingIdentity();
        try {
            final IPadat d = requireDaemon();
            return d != null
                    ? d.releasePathJson(path != null ? path : "")
                    : "{\"error\":\"padatd_unavailable\"}";
        } catch (RemoteException e) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void forceStopPackage(String pkg) {
        enforceManage();
        if (TextUtils.isEmpty(pkg)) return;
        final long ident = Binder.clearCallingIdentity();
        try {
            final ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am != null) {
                am.forceStopPackageAsUser(pkg, UserHandle.USER_CURRENT);
            }
        } catch (Exception e) {
            Slog.w(TAG, "forceStopPackage(" + pkg + ") failed: " + e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setPaused(boolean paused) {
        enforceManage();
        mUserPaused = paused;
        final long ident = Binder.clearCallingIdentity();
        try {
            final IPadat d = peekDaemon();
            if (d != null) {
                d.setPaused(mUserPaused || mGamePaused);
            }
        } catch (RemoteException ignored) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void cancelJob() {
        enforceManage();
        final long ident = Binder.clearCallingIdentity();
        try {
            final IPadat d = peekDaemon();
            if (d != null) {
                d.cancelJob();
            }
        } catch (RemoteException ignored) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    long cleanupStaleTempsInternal() {
        try {
            final IPadat d = requireDaemon();
            return d != null ? d.cleanupStaleTemps() : 0L;
        } catch (RemoteException e) {
            return 0L;
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new PadatShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!com.android.internal.util.DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        pw.println("PadatService:");
        pw.println("  dexoptHooked=" + mDexoptHooked
                + " autoTier1=" + mAutoTier1
                + " userPaused=" + mUserPaused
                + " gamePaused=" + mGamePaused);
        final IPadat d = peekDaemon();
        pw.println("  daemonAlive=" + (d != null));
        if (d != null) {
            try {
                pw.println("  status=" + d.statusJson());
            } catch (RemoteException ignored) {
            }
        }
    }
}
