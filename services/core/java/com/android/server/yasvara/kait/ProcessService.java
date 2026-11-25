/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.android.server.yasvara.kait;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import io.github.libxposed.service.HookedProcess;
import io.github.libxposed.service.IHotReloadCallback;

import org.yasvara.kait.ipc.IFrameworkService;
import org.yasvara.kait.ipc.IProcessChannel;
import org.yasvara.kait.ipc.LoadedModule;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-process Kait framework service living in {@code system_server}.
 *
 * <p>Tracks the attached {@link IProcessChannel}, loaded module versions/states for live hot
 * reload, and attachment timestamp for the crash circuit breaker.
 */
final class ProcessService extends IFrameworkService.Stub {
    private static final String TAG = "YasvaraKait";

    private final KaitManagerService mOwner;
    final long mTargetId;
    final int mUid;
    final int mPid;
    final String mProcessName;
    final String mPackageName;
    final long mAttachUptimeMs;
    private final List<String> mModules;
    private volatile IProcessChannel mChannel;

    /** Per-module loaded metadata for API 102 hot reload status tracking. */
    static final class ModuleLoadState {
        long versionCode;
        long apkMtime;
        int state = HookedProcess.TARGET_STATE_UP_TO_DATE;
    }

    private final ArrayMap<String, ModuleLoadState> mModernLoaded = new ArrayMap<>();

    ProcessService(KaitManagerService owner, long targetId, int uid, int pid, String processName,
            String packageName, List<String> modules) {
        mOwner = owner;
        mTargetId = targetId;
        mUid = uid;
        mPid = pid;
        mProcessName = processName;
        mPackageName = packageName;
        mAttachUptimeMs = SystemClock.elapsedRealtime();
        mModules = modules;
    }

    private void enforceOwner() {
        if (Binder.getCallingUid() != mUid) {
            throw new SecurityException("not the process this service was issued to");
        }
    }

    boolean servesModule(int uid, String module) {
        return mUid == uid && mModules.contains(module);
    }

    IProcessChannel getChannel() {
        return mChannel;
    }

    /** Returns a {@link HookedProcess} snapshot if this process loaded modern {@code module}. */
    HookedProcess snapshotForModule(String module, long currentVersionCode, long currentApkMtime) {
        synchronized (mModernLoaded) {
            final ModuleLoadState st = mModernLoaded.get(module);
            if (st == null) return null;
            final HookedProcess hp = new HookedProcess();
            hp.targetId = mTargetId;
            hp.uid = mUid;
            hp.pid = mPid;
            hp.processName = mProcessName;
            hp.loadedVersionCode = st.versionCode;
            if (st.state == HookedProcess.TARGET_STATE_RELOADING
                    || st.state == HookedProcess.TARGET_STATE_FAILED) {
                hp.state = st.state;
            } else if (st.versionCode != currentVersionCode || st.apkMtime != currentApkMtime) {
                hp.state = HookedProcess.TARGET_STATE_STALE;
            } else {
                hp.state = HookedProcess.TARGET_STATE_UP_TO_DATE;
            }
            return hp;
        }
    }

    boolean tryMarkReloading(String module) {
        synchronized (mModernLoaded) {
            final ModuleLoadState st = mModernLoaded.get(module);
            if (st == null || st.state == HookedProcess.TARGET_STATE_RELOADING) return false;
            st.state = HookedProcess.TARGET_STATE_RELOADING;
            return true;
        }
    }

    void finishReload(String module, boolean succeeded, long newVersionCode, long newApkMtime) {
        synchronized (mModernLoaded) {
            final ModuleLoadState st = mModernLoaded.get(module);
            if (st == null) return;
            if (succeeded) {
                st.versionCode = newVersionCode;
                st.apkMtime = newApkMtime;
                st.state = HookedProcess.TARGET_STATE_UP_TO_DATE;
            } else {
                st.state = HookedProcess.TARGET_STATE_FAILED;
            }
        }
    }

    private List<LoadedModule> modules(boolean legacy) {
        enforceOwner();
        final long token = Binder.clearCallingIdentity();
        try {
            final List<LoadedModule> out = new ArrayList<>();
            final int userId = UserHandle.getUserId(mUid);
            for (String module : mModules) {
                final KaitManagerService.LoadedBundle bundle =
                        mOwner.buildLoadedModule(module, userId);
                if (bundle != null && bundle.module.code.legacy == legacy) {
                    out.add(bundle.module);
                    if (!legacy) {
                        synchronized (mModernLoaded) {
                            final ModuleLoadState st = new ModuleLoadState();
                            st.versionCode = bundle.module.versionCode;
                            st.apkMtime = bundle.apkMtime;
                            st.state = HookedProcess.TARGET_STATE_UP_TO_DATE;
                            mModernLoaded.put(module, st);
                        }
                    }
                }
            }
            return out;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean isLogMuted() {
        return false;
    }

    @Override
    public List<LoadedModule> getLegacyModules() {
        return modules(true);
    }

    @Override
    public List<LoadedModule> getModules() {
        return modules(false);
    }

    @Override
    public String getPrefsPath(String packageName) {
        return KaitManagerService.VIRTUAL_PREFS_ROOT + "/" + packageName;
    }

    @Override
    public ParcelFileDescriptor openManagerApk() {
        return null;
    }

    @Override
    public IBinder requestManagerService() {
        return null;
    }

    @Override
    public void attachProcessChannel(IProcessChannel channel) {
        enforceOwner();
        mChannel = channel;
    }

    @Override
    public ParcelFileDescriptor openAppDataFile(String path) {
        enforceOwner();
        final long token = Binder.clearCallingIdentity();
        try {
            final File file = mOwner.resolvePrefsFile(path, mModules, UserHandle.getUserId(mUid));
            if (file == null) return null;
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            return null;
        } catch (Throwable t) {
            Slog.w(TAG, "openAppDataFile(" + path + ") failed for " + mProcessName, t);
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public List<String> getModuleScope(String module) {
        enforceOwner();
        if (!mPackageName.equals(module)) {
            throw new SecurityException("only the module itself may read its scope");
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return mOwner.getModuleScope(module, UserHandle.getUserId(mUid));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void setModuleScope(String module, List<String> packages) {
        enforceOwner();
        if (!mPackageName.equals(module)) {
            throw new SecurityException("only the module itself may change its scope");
        }
        mOwner.shrinkModuleScope(module, UserHandle.getUserId(mUid), packages);
    }

    @Override
    public List<HookedProcess> getRunningTargets(String module) {
        enforceOwner();
        if (!mPackageName.equals(module)) {
            throw new SecurityException("only the module itself may query its running targets");
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return mOwner.getRunningTargetsForModule(module, UserHandle.getUserId(mUid));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void hotReloadModule(String module, long targetId, Bundle data,
            IHotReloadCallback callback) {
        enforceOwner();
        if (!mPackageName.equals(module)) {
            throw new SecurityException("only the module itself may trigger hot reload");
        }
        final long token = Binder.clearCallingIdentity();
        try {
            mOwner.hotReloadModule(module, UserHandle.getUserId(mUid), targetId, data, callback);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void notifyRemotePreferencesChanged(String module, String group, Bundle diff) {
        enforceOwner();
        if (!mPackageName.equals(module)) {
            throw new SecurityException("only the module itself may broadcast preference changes");
        }
        if (group == null || diff == null) return;
        final long token = Binder.clearCallingIdentity();
        try {
            mOwner.broadcastRemotePreferencesChanged(
                    module, UserHandle.getUserId(mUid), group, diff);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
