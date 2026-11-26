/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.android.server.yasvara.kait;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import io.github.libxposed.service.HookedProcess;
import io.github.libxposed.service.IHotReloadCallback;
import io.github.libxposed.service.IXposedService;

import org.yasvara.kait.IYasvaraKait;
import org.yasvara.kait.ipc.HotReloadOutcome;
import org.yasvara.kait.ipc.IHotReloadOutcomeReceiver;
import org.yasvara.kait.ipc.IProcessChannel;
import org.yasvara.kait.ipc.IRemotePreferenceCallback;
import org.yasvara.kait.ipc.LoadedModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Kait's in-ROM control plane inside {@code system_server}.
 *
 * <p>Manages user module choices ({@link KaitConfig}), cached zero-copy module loading
 * ({@link ModuleLoader}), multi-user scoping, live hot reload (libxposed API 102), live remote
 * preference broadcasting, and an automatic crash circuit breaker.
 */
final class KaitManagerService extends IYasvaraKait.Stub {
    private static final String TAG = "YasvaraKait";
    static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_KAIT";
    static final String VIRTUAL_PREFS_ROOT = "/data/misc/yasvara_kait/prefs";
    private static final String FRAMEWORK_VERSION = "Kait 2.0";

    /** Rapid post-attach death threshold: 3 crashes within 30s, each within 5s of attach. */
    private static final long CRASH_RAPID_DEATH_MS = 5_000L;
    private static final long CRASH_WINDOW_MS = 30_000L;
    private static final int CRASH_TRIP_COUNT = 3;

    private static final Pattern PREF_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}\\.xml");

    static final class LoadedBundle {
        final LoadedModule module;
        final long apkMtime;

        LoadedBundle(LoadedModule module, long apkMtime) {
            this.module = module;
            this.apkMtime = apkMtime;
        }
    }

    private final Context mContext;
    private final KaitConfig mConfig = new KaitConfig();
    private final ModuleLoader mLoader = new ModuleLoader();
    private final AtomicLong mNextTargetId = new AtomicLong(1L);
    private final ArrayMap<Integer, ProcessService> mProcesses = new ArrayMap<>(); // by pid

    // Remote preferences live subscriber registry: "<userId>:<module>:<group>" -> callbacks.
    private final Object mPrefLock = new Object();
    private final ArrayMap<String, List<IRemotePreferenceCallback>> mPrefCallbacks = new ArrayMap<>();

    // Crash circuit breaker: "<userId>:<package>" -> recent rapid death timestamps & bypass set.
    private final Object mCrashLock = new Object();
    private final ArrayMap<String, ArrayDeque<Long>> mRapidDeaths = new ArrayMap<>();
    private final ArraySet<String> mCrashBypassed = new ArraySet<>();

    KaitManagerService(Context context) {
        mContext = context;
        syncHideSwitch();
    }

    private static final String HIDE_SWITCH = "/sys/kernel/yasvara_hide/kait";

    /**
     * The kernel hides Xposed module mappings from app /proc/self/maps only while a module
     * is enabled; with none, those reads cost what stock costs.
     */
    private void syncHideSwitch() {
        // Kernel without the switch: its filter is unconditional. EACCES also arrives as
        // FileNotFoundException, so test for the file instead of swallowing that.
        if (!new File(HIDE_SWITCH).exists()) return;
        try (FileOutputStream out = new FileOutputStream(HIDE_SWITCH)) {
            out.write(mConfig.anyEnabled() ? '1' : '0');
        } catch (IOException e) {
            Slog.w(TAG, HIDE_SWITCH + ": " + e.getMessage());
        }
    }

    // ---- Injection Policy & Crash Circuit Breaker ----------------------------------------

    private static String userPkgKey(int userId, String packageName) {
        return userId + ":" + packageName;
    }

    private static boolean isHardDenylisted(String packageName) {
        if (packageName == null) return true;
        return packageName.endsWith(".duckdetector")
                || packageName.endsWith(".keyattestation")
                || packageName.startsWith("com.google.android.gms")
                || packageName.equals("com.google.android.gsf")
                || packageName.equals("com.android.vending")
                || packageName.equals("com.topjohnwu.magisk")
                || packageName.equals("io.github.a13e300.ksuwebui");
    }

    boolean isCrashBypassed(String packageName, int userId) {
        synchronized (mCrashLock) {
            return mCrashBypassed.contains(userPkgKey(userId, packageName));
        }
    }

    private void recordProcessDeath(ProcessService service) {
        final long now = SystemClock.elapsedRealtime();
        final long lifetimeMs = now - service.mAttachUptimeMs;
        if (lifetimeMs > CRASH_RAPID_DEATH_MS) return;

        final int userId = UserHandle.getUserId(service.mUid);
        final String key = userPkgKey(userId, service.mPackageName);
        synchronized (mCrashLock) {
            ArrayDeque<Long> q = mRapidDeaths.get(key);
            if (q == null) {
                q = new ArrayDeque<>(CRASH_TRIP_COUNT + 1);
                mRapidDeaths.put(key, q);
            }
            while (!q.isEmpty() && now - q.peekFirst() > CRASH_WINDOW_MS) {
                q.removeFirst();
            }
            q.addLast(now);
            if (q.size() >= CRASH_TRIP_COUNT && mCrashBypassed.add(key)) {
                Slog.w(TAG, "Crash circuit breaker tripped for " + service.mPackageName
                        + " (user " + userId + ") after " + q.size()
                        + " rapid exits; bypassing Kait until cleared.");
            }
        }
    }

    private void resetCrashState(String packageName, int userId) {
        final String key = userPkgKey(userId, packageName);
        synchronized (mCrashLock) {
            mRapidDeaths.remove(key);
            mCrashBypassed.remove(key);
        }
    }

    boolean shouldInject(String packageName, int uid, boolean isolated) {
        if (isolated || packageName == null) return false;
        if (isHardDenylisted(packageName)) return false;
        final int appId = UserHandle.getAppId(uid);
        if (appId < Process.FIRST_APPLICATION_UID || appId > Process.LAST_APPLICATION_UID) {
            return false;
        }
        final int userId = UserHandle.getUserId(uid);
        if (isCrashBypassed(packageName, userId)) return false;
        // Self-injection for enabled modules (libxposed UI service + legacy isXposedEnabled).
        if (mConfig.isEnabled(packageName)) return true;
        return mConfig.isScoped(packageName, userId);
    }

    boolean onRendezvous(int uid, int pid, String packageName, String processName,
            boolean isolated, Parcel data, Parcel reply) {
        if (!shouldInject(packageName, uid, isolated)) return false;
        final String niceName = data.readString();
        final IBinder heartbeat = data.readStrongBinder();
        if (niceName == null || heartbeat == null || !niceName.equals(processName)) return false;
        final List<String> modules = mConfig.modulesFor(packageName, UserHandle.getUserId(uid));
        if (modules.isEmpty()) return false;

        final long targetId = mNextTargetId.getAndIncrement();
        final ProcessService service =
                new ProcessService(this, targetId, uid, pid, processName, packageName, modules);
        try {
            heartbeat.linkToDeath(() -> {
                synchronized (mProcesses) {
                    if (mProcesses.get(pid) == service) mProcesses.remove(pid);
                }
                recordProcessDeath(service);
            }, 0);
        } catch (RemoteException e) {
            return false;
        }
        synchronized (mProcesses) {
            mProcesses.put(pid, service);
        }
        reply.writeNoException();
        reply.writeStrongBinder(service);
        Slog.i(TAG, "attached " + processName + " (pid=" + pid + " target=" + targetId
                + ") modules=" + modules);
        return true;
    }

    // ---- Module Loading, Hot Reload & Remote Prefs Push ----------------------------------

    private ApplicationInfo moduleInfo(String module, int userId) {
        final PackageManager pm = mContext.getPackageManager();
        for (int u : new int[] {userId, UserHandle.USER_SYSTEM}) {
            try {
                return pm.getApplicationInfoAsUser(module,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA), u);
            } catch (PackageManager.NameNotFoundException ignored) {
                // try next user
            }
        }
        return null;
    }

    LoadedBundle buildLoadedModule(String module, int userId) {
        final ApplicationInfo info = moduleInfo(module, userId);
        if (info == null || info.sourceDir == null) return null;
        final ModuleLoader.Result r = mLoader.load(info.sourceDir);
        if (r.code == null) {
            Slog.w(TAG, "module " + module + " not loadable: " + r.reason);
            return null;
        }
        r.code.nativeLibraryDir = info.nativeLibraryDir;
        final LoadedModule lm = new LoadedModule();
        lm.packageName = module;
        lm.appId = UserHandle.getAppId(info.uid);
        lm.versionCode = info.longVersionCode;
        lm.apkPath = info.sourceDir;
        lm.code = r.code;
        lm.applicationInfo = info;
        lm.service = new InjectedModuleService(this, module, userId);
        return new LoadedBundle(lm, r.apkMtime);
    }

    boolean isServedTo(String module, int uid) {
        synchronized (mProcesses) {
            for (int i = 0; i < mProcesses.size(); i++) {
                if (mProcesses.valueAt(i).servesModule(uid, module)) return true;
            }
        }
        return false;
    }

    List<HookedProcess> getRunningTargetsForModule(String module, int userId) {
        final ApplicationInfo info = moduleInfo(module, userId);
        final long currentVersion = info != null ? info.longVersionCode : -1L;
        final long currentMtime =
                (info != null && info.sourceDir != null) ? new File(info.sourceDir).lastModified() : 0L;

        final List<HookedProcess> out = new ArrayList<>();
        synchronized (mProcesses) {
            for (int i = 0; i < mProcesses.size(); i++) {
                final ProcessService ps = mProcesses.valueAt(i);
                if (UserHandle.getUserId(ps.mUid) != userId) continue;
                final HookedProcess hp = ps.snapshotForModule(module, currentVersion, currentMtime);
                if (hp != null) out.add(hp);
            }
        }
        return out;
    }

    void hotReloadModule(String module, int userId, long targetId, Bundle data,
            IHotReloadCallback callback) {
        final ProcessService target = findProcessByTargetId(targetId, userId);
        if (target == null) {
            deliverHotReload(callback, IXposedService.HOT_RELOAD_PROCESS_DIED,
                    "Target process is no longer running");
            return;
        }
        final IProcessChannel channel = target.getChannel();
        if (channel == null) {
            deliverHotReload(callback, IXposedService.HOT_RELOAD_UNSUPPORTED,
                    "Target process has no hot reload channel");
            return;
        }
        final ApplicationInfo info = moduleInfo(module, userId);
        if (info == null || info.sourceDir == null) {
            deliverHotReload(callback, IXposedService.HOT_RELOAD_FAILED, "Module APK not found");
            return;
        }
        // Always refresh DEX cache so a newly installed module build is picked up immediately.
        mLoader.invalidate(info.sourceDir);
        final LoadedBundle fresh = buildLoadedModule(module, userId);
        if (fresh == null || fresh.module.code.legacy) {
            deliverHotReload(callback, IXposedService.HOT_RELOAD_UNSUPPORTED,
                    "Module does not support modern hot reload");
            return;
        }
        if (!target.tryMarkReloading(module)) {
            deliverHotReload(callback, IXposedService.HOT_RELOAD_IN_PROGRESS,
                    "Reload already in progress or module not loaded in target");
            return;
        }
        final IHotReloadOutcomeReceiver receiver = new IHotReloadOutcomeReceiver.Stub() {
            @Override
            public void onOutcome(HotReloadOutcome outcome) {
                final int status = outcome != null ? outcome.status : IXposedService.HOT_RELOAD_FAILED;
                final String message = outcome != null ? outcome.message : "No outcome reported";
                final boolean ok = (status == IXposedService.HOT_RELOAD_SUCCEEDED);
                target.finishReload(module, ok, fresh.module.versionCode, fresh.apkMtime);
                deliverHotReload(callback, status, message);
            }
        };
        try {
            channel.hotReload(module, data, fresh.module, receiver);
        } catch (RemoteException e) {
            target.finishReload(module, false, fresh.module.versionCode, fresh.apkMtime);
            deliverHotReload(callback, IXposedService.HOT_RELOAD_FAILED,
                    "Failed to dispatch hot reload: " + e.getMessage());
        }
    }

    private ProcessService findProcessByTargetId(long targetId, int userId) {
        synchronized (mProcesses) {
            for (int i = 0; i < mProcesses.size(); i++) {
                final ProcessService ps = mProcesses.valueAt(i);
                if (ps.mTargetId == targetId && UserHandle.getUserId(ps.mUid) == userId) {
                    return ps;
                }
            }
        }
        return null;
    }

    private static void deliverHotReload(IHotReloadCallback cb, int status, String msg) {
        if (cb == null) return;
        try {
            cb.onHotReloadResult(status, msg);
        } catch (RemoteException ignored) {
            // caller gone
        }
    }

    void registerRemotePrefCallback(String module, int userId, String group,
            IRemotePreferenceCallback callback) {
        final String key = userId + ":" + module + ":" + group;
        final IBinder binder = callback.asBinder();
        try {
            binder.linkToDeath(() -> removeRemotePrefCallback(key, binder), 0);
        } catch (RemoteException e) {
            return; // already dead
        }
        synchronized (mPrefLock) {
            List<IRemotePreferenceCallback> list = mPrefCallbacks.get(key);
            if (list == null) {
                list = new ArrayList<>();
                mPrefCallbacks.put(key, list);
            }
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).asBinder() == binder) return;
            }
            list.add(callback);
        }
    }

    private void removeRemotePrefCallback(String key, IBinder binder) {
        synchronized (mPrefLock) {
            final List<IRemotePreferenceCallback> list = mPrefCallbacks.get(key);
            if (list == null) return;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).asBinder() == binder) list.remove(i);
            }
            if (list.isEmpty()) mPrefCallbacks.remove(key);
        }
    }

    void broadcastRemotePreferencesChanged(String module, int userId, String group, Bundle diff) {
        final String key = userId + ":" + module + ":" + group;
        final List<IRemotePreferenceCallback> snapshot;
        synchronized (mPrefLock) {
            final List<IRemotePreferenceCallback> list = mPrefCallbacks.get(key);
            if (list == null || list.isEmpty()) return;
            snapshot = new ArrayList<>(list);
        }
        for (int i = 0; i < snapshot.size(); i++) {
            final IRemotePreferenceCallback cb = snapshot.get(i);
            try {
                cb.onRemotePreferencesChanged(diff);
            } catch (RemoteException e) {
                removeRemotePrefCallback(key, cb.asBinder());
            }
        }
    }

    List<String> getModuleScope(String module, int userId) {
        return mConfig.getScope(module, userId);
    }

    void shrinkModuleScope(String module, int userId, List<String> keep) {
        if (!new HashSet<>(mConfig.getScope(module, userId)).containsAll(keep)) {
            throw new SecurityException("scope can only be shrunk here; add apps in Laras manager");
        }
        final long token = Binder.clearCallingIdentity();
        try {
            for (String pkg : mConfig.setScope(module, userId, keep)) {
                resetCrashState(pkg, userId);
                restart(pkg, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    File resolvePrefsFile(String path, List<String> modules, int userId) {
        if (path == null) return null;
        final String module;
        final String name;
        final String dataPrefix = "/data/data/";
        final String legacyVirtualPrefix = "/data/misc/yasvara_xposed/prefs/";
        if (path.startsWith(VIRTUAL_PREFS_ROOT + "/")) {
            final String rest = path.substring(VIRTUAL_PREFS_ROOT.length() + 1);
            final int slash = rest.indexOf('/');
            if (slash <= 0) return null;
            module = rest.substring(0, slash);
            name = rest.substring(slash + 1);
        } else if (path.startsWith(legacyVirtualPrefix)) {
            final String rest = path.substring(legacyVirtualPrefix.length());
            final int slash = rest.indexOf('/');
            if (slash <= 0) return null;
            module = rest.substring(0, slash);
            name = rest.substring(slash + 1);
        } else if (path.startsWith(dataPrefix)) {
            final String rest = path.substring(dataPrefix.length());
            final int slash = rest.indexOf('/');
            if (slash <= 0) return null;
            module = rest.substring(0, slash);
            final String tail = rest.substring(slash + 1);
            if (!tail.startsWith("shared_prefs/")) return null;
            name = tail.substring("shared_prefs/".length());
        } else {
            return null;
        }
        if (!modules.contains(module) || !PREF_NAME.matcher(name).matches()) return null;
        for (String root : new String[] {"/data/user/", "/data/user_de/"}) {
            final File f = new File(root + userId + "/" + module + "/shared_prefs/" + name);
            if (f.isFile()) return f;
        }
        return null;
    }

    // ---- IYasvaraKait --------------------------------------------------------------------

    boolean mayManage(int uid, int pid) {
        if (uid == Process.ROOT_UID || uid == Process.SYSTEM_UID || uid == Process.SHELL_UID) {
            return true;
        }
        return mContext.checkPermission(PERMISSION_MANAGE, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void enforceManager() {
        if (!mayManage(Binder.getCallingUid(), Binder.getCallingPid())) {
            throw new SecurityException("requires " + PERMISSION_MANAGE);
        }
    }

    private int[] allUserIds() {
        try {
            final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
            if (umi != null) {
                final int[] ids = umi.getUserIds();
                if (ids != null && ids.length > 0) return ids;
            }
        } catch (Throwable ignored) {
            // fallback
        }
        return new int[] {UserHandle.USER_SYSTEM};
    }

    @Override
    public List<String> listModules(int userId) {
        enforceManager();
        final long token = Binder.clearCallingIdentity();
        try {
            final List<String> out = new ArrayList<>();
            final List<ApplicationInfo> apps = mContext.getPackageManager()
                    .getInstalledApplicationsAsUser(PackageManager.ApplicationInfoFlags.of(0), userId);
            for (ApplicationInfo info : apps) {
                if (info.sourceDir != null && ModuleLoader.looksLikeModule(info.sourceDir)) {
                    out.add(info.packageName);
                }
            }
            for (String known : mConfig.knownModules()) {
                if (!out.contains(known) && moduleInfo(known, userId) != null) out.add(known);
            }
            out.sort(null);
            return out;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public String getModuleProblem(String module, int userId) {
        enforceManager();
        final long token = Binder.clearCallingIdentity();
        try {
            final ApplicationInfo info = moduleInfo(module, userId);
            if (info == null) return "not installed";
            return mLoader.load(info.sourceDir).reason;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean isModuleEnabled(String module) {
        enforceManager();
        return mConfig.isEnabled(module);
    }

    @Override
    public void setModuleEnabled(String module, boolean enabled) {
        enforceManager();
        final long token = Binder.clearCallingIdentity();
        try {
            mConfig.setEnabled(module, enabled);
            syncHideSwitch();
            for (int userId : allUserIds()) {
                for (String pkg : mConfig.getScope(module, userId)) {
                    resetCrashState(pkg, userId);
                    restart(pkg, userId);
                }
                resetCrashState(module, userId);
                restart(module, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public List<String> getScope(String module, int userId) {
        enforceManager();
        return mConfig.getScope(module, userId);
    }

    @Override
    public void setScope(String module, int userId, List<String> packages) {
        enforceManager();
        final long token = Binder.clearCallingIdentity();
        try {
            final List<String> changed = mConfig.setScope(module, userId, packages);
            if (mConfig.isEnabled(module)) {
                for (String pkg : changed) {
                    resetCrashState(pkg, userId);
                    restart(pkg, userId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public List<String> getRecommendedScope(String module, int userId) {
        enforceManager();
        final long token = Binder.clearCallingIdentity();
        try {
            final List<String> out = new ArrayList<>();
            final ApplicationInfo info = moduleInfo(module, userId);
            if (info == null) return out;
            try (ZipFile zip = new ZipFile(info.sourceDir)) {
                final ZipEntry e = zip.getEntry("META-INF/xposed/scope.list");
                if (e != null) {
                    try (InputStream in = zip.getInputStream(e)) {
                        for (String line : new String(in.readAllBytes()).split("\n")) {
                            line = line.trim();
                            if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
                        }
                    }
                }
            } catch (IOException ignored) {
                // no list
            }
            if (out.isEmpty() && info.metaData != null && info.metaData.containsKey("xposedscope")) {
                try {
                    final Resources res = mContext.getPackageManager().getResourcesForApplication(info);
                    for (String s : res.getStringArray(info.metaData.getInt("xposedscope"))) out.add(s);
                } catch (Exception ignored) {
                    // malformed resource reference
                }
            }
            return out;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void restartApp(String packageName, int userId) {
        enforceManager();
        final long token = Binder.clearCallingIdentity();
        try {
            resetCrashState(packageName, userId);
            restart(packageName, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void restart(String pkg, int userId) {
        try {
            ActivityManager.getService().forceStopPackage(pkg, userId);
        } catch (RemoteException e) {
            Slog.w(TAG, "cannot stop " + pkg, e);
        }
    }

    @Override
    public List<String> getInjectedProcesses() {
        enforceManager();
        final List<String> out = new ArrayList<>();
        synchronized (mProcesses) {
            for (ProcessService p : mProcesses.values()) out.add(p.mProcessName + ":" + p.mPid);
        }
        out.sort(null);
        return out;
    }

    @Override
    public List<String> getCrashBypassedPackages(int userId) {
        enforceManager();
        final String prefix = userId + ":";
        final List<String> out = new ArrayList<>();
        synchronized (mCrashLock) {
            for (int i = 0; i < mCrashBypassed.size(); i++) {
                final String k = mCrashBypassed.valueAt(i);
                if (k.startsWith(prefix)) out.add(k.substring(prefix.length()));
            }
        }
        out.sort(null);
        return out;
    }

    @Override
    public void clearCrashBypass(String packageName, int userId) {
        enforceManager();
        if (packageName == null) return;
        resetCrashState(packageName, userId);
    }

    @Override
    public String getFrameworkVersion() {
        enforceManager();
        return FRAMEWORK_VERSION;
    }

    // ---- cmd activity kait (and legacy alias: cmd activity xposed) -----------------------

    int runShell(ShellCommand sc) {
        final PrintWriter pw = sc.getOutPrintWriter();
        final String cmd = sc.getNextArg();
        if (cmd == null) {
            help(pw);
            return 0;
        }
        try {
            switch (cmd) {
                case "version":
                    pw.println(FRAMEWORK_VERSION);
                    return 0;
                case "list": {
                    final int userId = userArg(sc);
                    for (String m : listModules(userId)) {
                        final String problem = getModuleProblem(m, userId);
                        pw.println(m + (isModuleEnabled(m) ? " [on]" : " [off]")
                                + (problem == null ? "" : " (" + problem + ")"));
                    }
                    return 0;
                }
                case "enable":
                case "disable":
                    setModuleEnabled(sc.getNextArgRequired(), cmd.equals("enable"));
                    return 0;
                case "scope": {
                    final String sub = sc.getNextArgRequired();
                    final String module = sc.getNextArgRequired();
                    final int userId = userArg(sc);
                    final List<String> now = new ArrayList<>(getScope(module, userId));
                    switch (sub) {
                        case "list":
                            for (String p : now) pw.println(p);
                            return 0;
                        case "add": {
                            String p;
                            while ((p = sc.getNextArg()) != null) if (!now.contains(p)) now.add(p);
                            setScope(module, userId, now);
                            return 0;
                        }
                        case "remove": {
                            String p;
                            while ((p = sc.getNextArg()) != null) now.remove(p);
                            setScope(module, userId, now);
                            return 0;
                        }
                        case "recommended":
                            for (String p : getRecommendedScope(module, userId)) pw.println(p);
                            return 0;
                        default:
                            help(pw);
                            return 1;
                    }
                }
                case "restart": {
                    final String pkg = sc.getNextArgRequired();
                    restartApp(pkg, userArg(sc));
                    return 0;
                }
                case "status":
                    for (String p : getInjectedProcesses()) pw.println(p);
                    return 0;
                case "crashes": {
                    final int userId = userArg(sc);
                    for (String p : getCrashBypassedPackages(userId)) pw.println(p);
                    return 0;
                }
                case "unbypass": {
                    final String pkg = sc.getNextArgRequired();
                    clearCrashBypass(pkg, userArg(sc));
                    return 0;
                }
                default:
                    help(pw);
                    return 1;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            sc.getErrPrintWriter().println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int userArg(ShellCommand sc) {
        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = sc.getNextOption()) != null) {
            if (opt.equals("--user") || opt.equals("-u")) {
                userId = Integer.parseInt(sc.getNextArgRequired());
            }
        }
        return userId;
    }

    private static void help(PrintWriter pw) {
        pw.println("Kait commands (cmd activity kait ...):");
        pw.println("  version                             print Kait framework version");
        pw.println("  list [--user N]                     installed modules and their state");
        pw.println("  enable|disable <module>");
        pw.println("  scope list|recommended <module> [--user N]");
        pw.println("  scope add|remove <module> [--user N] <package>...");
        pw.println("  restart <package> [--user N]        force-stop so the scope applies");
        pw.println("  status                              processes running with Kait attached");
        pw.println("  crashes [--user N]                  packages auto-bypassed after crash loops");
        pw.println("  unbypass <package> [--user N]       re-enable Kait after crash bypass");
    }
}
