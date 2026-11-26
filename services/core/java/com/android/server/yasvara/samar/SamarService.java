/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.samar;

import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.util.DumpUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owns the Samar policy: persistence, package bindings, the simulation clock and the binder API. */
public final class SamarService extends IYasvaraSamar.Stub {
    public static final String SERVICE_NAME = "yasvara_samar";
    private static final String TAG = "Samar";
    private static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_SAMAR";

    /** Opts an app into local network restrictions (ConnectivityCompatChanges). */
    private static final long RESTRICT_LOCAL_NETWORK = 365139289L;
    private static final long TICK_MS = 1000;
    private static final long SAVE_DELAY_MS = 500;

    /** Exempt by role: emergency calls and their location must stay real. */
    private static final String[] EXEMPT_ROLES = {RoleManager.ROLE_DIALER, RoleManager.ROLE_EMERGENCY};

    private final Context mContext;
    private final Handler mHandler;
    private final File mDir = new File(Environment.getDataSystemDirectory(), "yasvara");
    private final AtomicFile mFile = new AtomicFile(new File(mDir, "samar.conf"));
    private final AtomicFile mLanFile = new AtomicFile(new File(mDir, "samar_lan.conf"));
    private final Object mLock = new Object();
    private final Set<Integer> mExempt = new ArraySet<>();
    private boolean mTicking;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                SamarNative.nativeStep(SystemClock.elapsedRealtimeNanos());
            }
            SamarHooks.dispatchTick();
            if ((SamarHooks.state() & SamarNative.STATE_ACTIVE) != 0) {
                mHandler.postDelayed(this, TICK_MS);
            } else {
                mTicking = false;
            }
        }
    };

    private final Runnable mSave = this::save;

    public SamarService(Context context) {
        mContext = context;
        SamarNative.load();
        final HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_FOREGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        load();
        bindAllPackages();
        registerReceivers();
        publish(true);
        mHandler.post(this::applyLanOverrides);
    }

    private void load() {
        try {
            final String text = new String(mFile.readFully(), StandardCharsets.UTF_8);
            synchronized (mLock) {
                if (!SamarNative.nativeDecode(text)) Slog.w(TAG, "ignoring unreadable settings");
            }
        } catch (IOException e) {
            // First boot or never configured.
        }
    }

    private void save() {
        final String text;
        synchronized (mLock) {
            text = SamarNative.nativeEncode();
        }
        write(mFile, text);
    }

    private void write(AtomicFile file, String text) {
        mDir.mkdirs();
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(text.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            file.failWrite(out);
            Slog.e(TAG, "failed to write " + file.getBaseFile(), e);
        }
    }

    private void bindAllPackages() {
        final List<ApplicationInfo> apps = mContext.getPackageManager()
                .getInstalledApplicationsAsUser(PackageManager.MATCH_UNINSTALLED_PACKAGES,
                        UserHandle.USER_SYSTEM);
        synchronized (mLock) {
            for (ApplicationInfo ai : apps) {
                SamarNative.nativeBind(ai.packageName, UserHandle.getAppId(ai.uid));
            }
        }
    }

    private void registerReceivers() {
        final IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packages.addDataScheme("package");
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String pkg = intent.getData() != null
                        ? intent.getData().getSchemeSpecificPart() : null;
                if (pkg == null) return;
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                synchronized (mLock) {
                    if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction()) && uid >= 0) {
                        SamarNative.nativeBind(pkg, UserHandle.getAppId(uid));
                    } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
                        SamarNative.nativeUnbind(pkg);
                    }
                }
                publish(true);
                applyLanOverrides();
            }
        }, UserHandle.ALL, packages, null, mHandler);

        final IntentFilter boot = new IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                watchRoles();
            }
        }, UserHandle.SYSTEM, boot, null, mHandler);
    }

    private void watchRoles() {
        final RoleManager roles = mContext.getSystemService(RoleManager.class);
        if (roles == null) return;
        final OnRoleHoldersChangedListener listener = (role, user) -> refreshExempt(roles);
        roles.addOnRoleHoldersChangedListenerAsUser(mHandler::post, listener, UserHandle.ALL);
        refreshExempt(roles);
    }

    private void refreshExempt(RoleManager roles) {
        final PackageManager pm = mContext.getPackageManager();
        final Set<Integer> next = new ArraySet<>();
        for (String role : EXEMPT_ROLES) {
            for (String pkg : roles.getRoleHoldersAsUser(role, UserHandle.SYSTEM)) {
                try {
                    next.add(UserHandle.getAppId(pm.getPackageUidAsUser(pkg, UserHandle.USER_SYSTEM)));
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        synchronized (mLock) {
            for (int id : mExempt) {
                if (!next.contains(id)) SamarNative.nativeSetExempt(id, false);
            }
            for (int id : next) SamarNative.nativeSetExempt(id, true);
            mExempt.clear();
            mExempt.addAll(next);
        }
        publish(true);
    }

    /** Pushes the policy state to the hooks and keeps the clock running while needed. */
    private void publish(boolean scopeChanged) {
        final int state;
        synchronized (mLock) {
            state = SamarNative.nativeState();
        }
        SamarHooks.publish(state, scopeChanged);
        if ((state & SamarNative.STATE_ACTIVE) != 0) {
            mHandler.post(() -> {
                if (!mTicking) {
                    mTicking = true;
                    mTick.run();
                }
            });
        }
    }

    private void changed(boolean scopeChanged) {
        publish(scopeChanged);
        mHandler.removeCallbacks(mSave);
        mHandler.postDelayed(mSave, SAVE_DELAY_MS);
    }

    /** Applies the local network layer as a per-package compat override. */
    private void applyLanOverrides() {
        final Set<String> wanted = new HashSet<>();
        final PackageManager pm = mContext.getPackageManager();
        if ((SamarHooks.state() & SamarNative.STATE_LAYERS) != 0) {
            for (ApplicationInfo ai : pm.getInstalledApplicationsAsUser(0, UserHandle.USER_SYSTEM)) {
                if ((SamarHooks.layers(ai.uid) & SamarHooks.LAYER_LAN) != 0) wanted.add(ai.packageName);
            }
        }
        final Set<String> applied = readLines(mLanFile);
        if (wanted.equals(applied)) return;
        final IPlatformCompat compat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        if (compat == null) return;
        final long ident = Binder.clearCallingIdentity();
        try {
            for (String pkg : applied) {
                if (!wanted.contains(pkg)) compat.clearOverrideForTest(RESTRICT_LOCAL_NETWORK, pkg);
            }
            final CompatibilityChangeConfig enable = new CompatibilityChangeConfig(
                    new android.compat.Compatibility.ChangeConfig(
                            Collections.singleton(RESTRICT_LOCAL_NETWORK), Collections.emptySet()));
            for (String pkg : wanted) {
                if (!applied.contains(pkg)) compat.setOverridesForTest(enable, pkg);
            }
            write(mLanFile, String.join("\n", wanted));
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "local network override failed", e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static Set<String> readLines(AtomicFile file) {
        final Set<String> out = new HashSet<>();
        try {
            for (String line : new String(file.readFully(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) out.add(line.trim());
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private void enforceManage() {
        mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE, TAG);
    }

    private static String target(String target) {
        return target == null ? "" : target;
    }

    @Override
    public boolean isGlobalOn() {
        enforceManage();
        return (SamarHooks.state() & SamarNative.STATE_GLOBAL_ON) != 0;
    }

    @Override
    public void setGlobalOn(boolean on) {
        enforceManage();
        synchronized (mLock) {
            SamarNative.nativeSetGlobalOn(on);
        }
        changed(true);
    }

    @Override
    public double[] getPosition(String target) {
        enforceManage();
        synchronized (mLock) {
            return SamarNative.nativePosition(target(target));
        }
    }

    @Override
    public double setPlace(String target, double lat, double lng) {
        enforceManage();
        final double implied;
        synchronized (mLock) {
            implied = SamarNative.nativePlace(target(target), lat, lng,
                    SystemClock.elapsedRealtimeNanos());
        }
        if (!Double.isNaN(implied)) changed(true);
        return implied;
    }

    @Override
    public void setAltitude(String target, double altitude) {
        enforceManage();
        synchronized (mLock) {
            SamarNative.nativeSetAltitude(target(target), altitude);
        }
        changed(false);
    }

    @Override
    public void setJoystick(String target, float x, float y) {
        enforceManage();
        synchronized (mLock) {
            SamarNative.nativeSetJoystick(target(target), x, y);
        }
        publish(false);
    }

    @Override
    public void setSpeedPreset(String target, int preset) {
        enforceManage();
        synchronized (mLock) {
            SamarNative.nativeSetSpeed(target(target), preset);
        }
        changed(false);
    }

    @Override
    public int getAppMode(String pkg) {
        enforceManage();
        synchronized (mLock) {
            return SamarNative.nativeMode(target(pkg));
        }
    }

    @Override
    public void setAppMode(String pkg, int mode) {
        enforceManage();
        synchronized (mLock) {
            SamarNative.nativeSetMode(target(pkg), mode);
        }
        changed(true);
    }

    @Override
    public String[] getConfiguredApps() {
        enforceManage();
        synchronized (mLock) {
            return SamarNative.nativeConfiguredApps();
        }
    }

    @Override
    public long getLayers(String target) {
        enforceManage();
        synchronized (mLock) {
            return SamarNative.nativeLayerOverride(target(target));
        }
    }

    @Override
    public void setLayers(String target, int mask, int value) {
        enforceManage();
        synchronized (mLock) {
            SamarNative.nativeSetLayers(target(target), mask, value);
        }
        changed(true);
        mHandler.post(this::applyLanOverrides);
    }

    @Override
    public double[] parseLocation(String text, double refLat, double refLng) {
        enforceManage();
        if (text == null || text.length() > 4096) return null;
        return SamarNative.nativeParse(text, refLat, refLng);
    }

    @Override
    public String[] getPlaceNames() {
        enforceManage();
        synchronized (mLock) {
            return SamarNative.nativePlaceNames();
        }
    }

    @Override
    public double[] getPlaceCoords() {
        enforceManage();
        synchronized (mLock) {
            return SamarNative.nativePlaceCoords();
        }
    }

    @Override
    public boolean savePlace(String name, double lat, double lng) {
        enforceManage();
        final boolean ok;
        synchronized (mLock) {
            ok = name != null && name.length() <= 200 && SamarNative.nativeAddPlace(name, lat, lng);
        }
        if (ok) changed(false);
        return ok;
    }

    @Override
    public boolean removePlace(int index) {
        enforceManage();
        final boolean ok;
        synchronized (mLock) {
            ok = SamarNative.nativeRemovePlace(index);
        }
        if (ok) changed(false);
        return ok;
    }

    @Override
    public boolean hidesRadio(int uid) {
        final int caller = UserHandle.getAppId(Binder.getCallingUid());
        if (caller != Process.PHONE_UID && caller != Process.SYSTEM_UID) {
            throw new SecurityException("phone process only");
        }
        return SamarHooks.hidesRadio(uid);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        synchronized (mLock) {
            pw.print(SamarNative.nativeDump());
        }
        pw.println("state=0x" + Integer.toHexString(SamarHooks.state()) + " ticking=" + mTicking
                + " exempt=" + mExempt + " lan=" + readLines(mLanFile).size());
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw new SecurityException("shell only");
        }
        new SamarShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    // Shell entry points: same effects as the binder calls, without the permission check.

    void shellSetGlobalOn(boolean on) {
        synchronized (mLock) {
            SamarNative.nativeSetGlobalOn(on);
        }
        changed(true);
    }

    double shellPlace(String target, double lat, double lng) {
        final double implied;
        synchronized (mLock) {
            implied = SamarNative.nativePlace(target, lat, lng, SystemClock.elapsedRealtimeNanos());
        }
        if (!Double.isNaN(implied)) changed(true);
        return implied;
    }

    void shellJoystick(String target, float x, float y) {
        synchronized (mLock) {
            SamarNative.nativeSetJoystick(target, x, y);
        }
        publish(false);
    }

    void shellSpeed(String target, int preset) {
        synchronized (mLock) {
            SamarNative.nativeSetSpeed(target, preset);
        }
        changed(false);
    }

    void shellMode(String pkg, int mode) {
        synchronized (mLock) {
            SamarNative.nativeSetMode(pkg, mode);
        }
        changed(true);
    }

    void shellLayers(String target, int mask, int value) {
        synchronized (mLock) {
            SamarNative.nativeSetLayers(target, mask, value);
        }
        changed(true);
        mHandler.post(this::applyLanOverrides);
    }

    double[] shellParse(String text) {
        return SamarNative.nativeParse(text, Double.NaN, Double.NaN);
    }

    String shellDump() {
        synchronized (mLock) {
            return SamarNative.nativeDump() + "state=0x" + Integer.toHexString(SamarHooks.state())
                    + "\n";
        }
    }
}
