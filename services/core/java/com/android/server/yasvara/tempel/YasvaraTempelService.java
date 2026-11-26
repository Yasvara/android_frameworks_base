/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.tempel;

import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.util.DumpUtils;
import com.android.server.IoThread;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Owns the Tempel NFC vault, active card profile, NFCC switching (Type-A/B/F, ISO-DEP routing,
 * Payment ROLE_WALLET, Observe Mode shield), hardware 2F 45 40-key MIFARE verifier, and
 * zero-alloc Rust HCE APDU dispatcher.
 *
 * Zero extra threads, zero wakelocks, and zero NCI traffic when idle in default payment mode.
 */
public final class YasvaraTempelService extends IYasvaraTempel.Stub {
    public static final String SERVICE_NAME = "yasvara_tempel";
    private static final String TAG = "Tempel";
    private static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_TEMPEL";
    private static final long SAVE_DELAY_MS = 350L;

    // RF_DISCOVER_CMD payload: Poll A (00 01), Poll B (01 01), Poll F (02 01),
    // Listen A (80 01), Listen B (81 01), Listen F (82 01).
    private static final byte[] RF_DISCOVER_ALL = new byte[]{
            0x06,
            0x00, 0x01,
            0x01, 0x01,
            0x02, 0x01,
            (byte) 0x80, 0x01,
            (byte) 0x81, 0x01,
            (byte) 0x82, 0x01,
    };

    private final Context mContext;
    private final Handler mIoHandler;
    private final Object mLock = new Object();
    private final File mDir = new File(Environment.getDataSystemDirectory(), "yasvara");
    private final AtomicFile mVaultFile = new AtomicFile(new File(mDir, "tempel.vault"));

    // Cached reflection handles for framework-nfc APEX (resolved lazily, zero build coupling).
    private Object mNfcService;
    private Object mCardEmuService;
    private Method mGetStateM;
    private Method mEmulateAM;
    private Method mSendVendorM;
    private Method mRegVendorCbM;
    private Method mUnregVendorCbM;
    private Method mSetObserveM;
    private Method mIsObserveSupportedM;
    private Method mIsObserveEnabledM;
    private Method mOverwriteRoutingM;

    private boolean mDirtyTypeBF;
    private boolean mRoutingOverridden;
    private boolean mShieldActive;
    private int mActiveKind = TempelNative.KIND_PAYMENT_WALLET;

    private final Runnable mSaveTask = this::saveVaultNow;
    private final Runnable mRevertTask = this::onRevertTimer;

    public YasvaraTempelService(Context context) {
        mContext = context;
        mIoHandler = IoThread.getHandler();
        TempelNative.load();
        loadVault();
        seedDefaultPaymentIfEmpty();
        registerNfcStateReceiver();
    }

    private void enforceManage() {
        mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE, "Tempel requires MANAGE_TEMPEL");
    }

    private void loadVault() {
        try {
            final byte[] blob = mVaultFile.readFully();
            synchronized (mLock) {
                if (!TempelNative.nativeLoadVault(blob)) {
                    Slog.w(TAG, "ignoring corrupted tempel.vault");
                }
            }
        } catch (IOException e) {
            // First boot or no profiles created yet.
        }
    }

    private void seedDefaultPaymentIfEmpty() {
        synchronized (mLock) {
            final String[] existing = TempelNative.nativeListProfiles();
            if (existing != null && existing.length > 0) return;
        }
        // Discover current ROLE_WALLET holder on first boot (e.g. GoPay or Google Wallet)
        final long token = Binder.clearCallingIdentity();
        try {
            final RoleManager rm = mContext.getSystemService(RoleManager.class);
            if (rm == null) return;
            final List<String> holders = rm.getRoleHolders(RoleManager.ROLE_WALLET);
            if (holders != null && !holders.isEmpty()) {
                final String pkg = holders.get(0);
                final String label = labelForPackage(pkg);
                synchronized (mLock) {
                    final int id = TempelNative.nativeUpsertProfile(
                            0, TempelNative.KIND_PAYMENT_WALLET, label, 0xFF00AA5B, 1,
                            2, 0, 0, true, pkg,
                            new byte[0], new byte[]{0x04, 0x00}, 0x20, 0,
                            new byte[0], new byte[0], new byte[0], new byte[0], new byte[0]);
                    if (id > 0) {
                        TempelNative.nativeSetDefaultPayment(id);
                        TempelNative.nativeActivate(id, SystemClock.elapsedRealtime());
                    }
                }
                scheduleSave();
            }
        } catch (Throwable t) {
            Slog.w(TAG, "seedDefaultPaymentIfEmpty: " + t.getMessage());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private String labelForPackage(String pkg) {
        try {
            final var pm = mContext.getPackageManager();
            final var ai = pm.getApplicationInfo(pkg, 0);
            final CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    private void registerNfcStateReceiver() {
        final IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int state = intent.getIntExtra(
                        NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF);
                if (state == NfcAdapter.STATE_ON) {
                    mIoHandler.post(() -> onNfcReenabled());
                } else if (state == NfcAdapter.STATE_OFF) {
                    mIoHandler.removeCallbacks(mRevertTask);
                    synchronized (mLock) {
                        mDirtyTypeBF = false;
                        mRoutingOverridden = false;
                        mShieldActive = false;
                        // Clear temporary armed card state while NFC radio is OFF so UI never
                        // displays a card as actively emulating when NFC is disabled.
                        if (mActiveKind != TempelNative.KIND_PAYMENT_WALLET) {
                            TempelNative.nativeActivate(0, SystemClock.elapsedRealtime());
                            mActiveKind = TempelNative.KIND_PAYMENT_WALLET;
                        }
                    }
                }
            }
        }, UserHandle.SYSTEM, filter, null, mIoHandler);
    }

    /** Re-applies sticky profile or cleanly reverts temporary door profile when NFC toggles ON. */
    private void onNfcReenabled() {
        final int activeId;
        final boolean shouldReapply;
        synchronized (mLock) {
            activeId = TempelNative.nativeGetActiveId();
            if (TempelNative.nativeCheckRevert(SystemClock.elapsedRealtime())) {
                shouldReapply = false;
            } else {
                shouldReapply = activeId > 0 && mActiveKind != TempelNative.KIND_PAYMENT_WALLET;
            }
        }
        if (shouldReapply) {
            applyActiveToHardware();
        }
    }

    private void scheduleSave() {
        mIoHandler.removeCallbacks(mSaveTask);
        mIoHandler.postDelayed(mSaveTask, SAVE_DELAY_MS);
    }

    private void saveVaultNow() {
        final byte[] blob;
        synchronized (mLock) {
            blob = TempelNative.nativeSaveVault();
        }
        if (blob == null || blob.length == 0) return;
        mDir.mkdirs();
        FileOutputStream out = null;
        try {
            out = mVaultFile.startWrite();
            out.write(blob);
            mVaultFile.finishWrite(out);
        } catch (IOException e) {
            mVaultFile.failWrite(out);
            Slog.e(TAG, "failed to save tempel.vault", e);
        }
    }

    private void onRevertTimer() {
        final boolean reverted;
        synchronized (mLock) {
            reverted = TempelNative.nativeCheckRevert(SystemClock.elapsedRealtime());
        }
        if (reverted) {
            applyActiveToHardware();
            scheduleSave();
        }
    }

    // -----------------------------------------------------------------------
    // Lazy framework-nfc APEX binder resolution (zero compile-time APEX coupling)
    // -----------------------------------------------------------------------

    private boolean ensureNfcProxyLocked() {
        if (mNfcService != null) return true;
        try {
            final IBinder b = ServiceManager.getService("nfc");
            if (b == null) return false;
            final Class<?> iNfc = Class.forName("android.nfc.INfcAdapter");
            mNfcService = Class.forName("android.nfc.INfcAdapter$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, b);
            mGetStateM = iNfc.getMethod("getState");
            mEmulateAM = iNfc.getMethod("emulateNfcATag",
                    boolean.class, byte.class, byte.class, byte.class,
                    byte[].class, byte.class, byte[].class);
            mSendVendorM = iNfc.getMethod("sendVendorNciMessage",
                    int.class, int.class, int.class, byte[].class);
            final Class<?> cbIface = Class.forName("android.nfc.INfcVendorNciCallback");
            mRegVendorCbM = iNfc.getMethod("registerVendorExtensionCallback", cbIface);
            mUnregVendorCbM = iNfc.getMethod("unregisterVendorExtensionCallback", cbIface);
            mSetObserveM = iNfc.getMethod("setObserveMode", boolean.class, String.class);
            mIsObserveSupportedM = iNfc.getMethod("isObserveModeSupported");
            mIsObserveEnabledM = iNfc.getMethod("isObserveModeEnabled");

            try {
                final Method getCe = iNfc.getMethod("getNfcCardEmulationInterface");
                mCardEmuService = getCe.invoke(mNfcService);
                if (mCardEmuService != null) {
                    mOverwriteRoutingM = mCardEmuService.getClass().getMethod(
                            "overwriteRoutingTable",
                            int.class, String.class, String.class, String.class,
                            String.class, String.class, String.class);
                }
            } catch (Throwable t) {
                Slog.w(TAG, "INfcCardEmulation.overwriteRoutingTable unavailable: " + t.getMessage());
            }
            return true;
        } catch (Throwable t) {
            Slog.e(TAG, "failed to bind nfc service", t);
            mNfcService = null;
            return false;
        }
    }

    private boolean isNfcOnLocked() {
        try {
            if (!ensureNfcProxyLocked()) return false;
            return ((Integer) mGetStateM.invoke(mNfcService)) == NfcAdapter.STATE_ON;
        } catch (Throwable t) {
            mNfcService = null;
            return false;
        }
    }

    private boolean applyActiveToHardware() {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!isNfcOnLocked()) return false;
                final int activeId = TempelNative.nativeGetActiveId();
                final String[] rows = TempelNative.nativeListProfiles();
                String activeRow = null;
                if (rows != null) {
                    final String prefix = activeId + "\t";
                    for (String r : rows) {
                        if (r.startsWith(prefix)) {
                            activeRow = r;
                            break;
                        }
                    }
                }
                if (activeRow == null) {
                    return resetToStockLocked(null);
                }
                final String[] cols = activeRow.split("\t", -1);
                final int kind = Integer.parseInt(cols[1]);
                final String walletPkg = cols[9];
                mActiveKind = kind;

                switch (kind) {
                    case TempelNative.KIND_PAYMENT_WALLET:
                        return resetToStockLocked(walletPkg);

                    case TempelNative.KIND_SHIELD:
                        resetToStockLocked(null);
                        final boolean obsOk = Boolean.TRUE.equals(
                                mSetObserveM.invoke(mNfcService, true, "android"));
                        mShieldActive = obsOk;
                        return obsOk;

                    case TempelNative.KIND_UID_TYPE_A:
                        clearShieldAndTypeBFLocked();
                        return applyListenALocked(false);

                    case TempelNative.KIND_ISODEP_HCE:
                    case TempelNative.KIND_NDEF_T4T:
                        clearShieldAndTypeBFLocked();
                        final byte[] aPacked = TempelNative.nativeBuildActiveListenA();
                        if (aPacked != null) {
                            applyListenAPackedLocked(aPacked);
                        } else {
                            mEmulateAM.invoke(mNfcService, false,
                                    (byte) 0, (byte) 0, (byte) 0, new byte[0], (byte) 0, null);
                        }
                        overrideIsoDepToHostLocked(true);
                        return true;

                    case TempelNative.KIND_UID_TYPE_B:
                        clearShieldAndTypeBFLocked();
                        return applyTypeBOrFLocked(TempelNative.nativeBuildActiveListenB());

                    case TempelNative.KIND_FELICA_HCEF:
                        clearShieldAndTypeBFLocked();
                        return applyTypeBOrFLocked(TempelNative.nativeBuildActiveListenF());

                    default:
                        return false;
                }
            }
        } catch (Throwable t) {
            Slog.e(TAG, "applyActiveToHardware failed", t);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void clearShieldAndTypeBFLocked() throws Throwable {
        if (mShieldActive) {
            mSetObserveM.invoke(mNfcService, false, "android");
            mShieldActive = false;
        }
        if (mDirtyTypeBF) {
            mSendVendorM.invoke(mNfcService, 1, 0x01, 0x06, new byte[]{0x00});
            final byte[] rb = TempelNative.nativeBuildResetListenB();
            final byte[] rf = TempelNative.nativeBuildResetListenF();
            if (rb != null) mSendVendorM.invoke(mNfcService, 1, 0x00, 0x02, rb);
            if (rf != null) mSendVendorM.invoke(mNfcService, 1, 0x00, 0x02, rf);
            mDirtyTypeBF = false;
        }
        if (mRoutingOverridden) {
            overrideIsoDepToHostLocked(false);
        }
    }

    private boolean resetToStockLocked(String walletPkg) throws Throwable {
        clearShieldAndTypeBFLocked();
        final int rc = (Integer) mEmulateAM.invoke(mNfcService, false,
                (byte) 0, (byte) 0, (byte) 0, new byte[0], (byte) 0, null);
        if (walletPkg != null && !walletPkg.isEmpty()) {
            switchWalletRoleLocked(walletPkg);
        }
        return rc == 0;
    }

    private boolean applyListenALocked(boolean routeIsoDep) throws Throwable {
        final byte[] packed = TempelNative.nativeBuildActiveListenA();
        if (packed == null) return false;
        final boolean ok = applyListenAPackedLocked(packed);
        if (ok && routeIsoDep) {
            overrideIsoDepToHostLocked(true);
        }
        return ok;
    }

    private boolean applyListenAPackedLocked(byte[] packed) throws Throwable {
        // Layout: [sdd, plat, sak, rats, uidLen, uid..., histLen, hist...]
        if (packed.length < 6) return false;
        final byte sdd = packed[0];
        final byte plat = packed[1];
        final byte sak = packed[2];
        final byte rats = packed[3];
        final int uidLen = packed[4] & 0xFF;
        if (packed.length < 6 + uidLen) return false;
        final byte[] uid = new byte[uidLen];
        System.arraycopy(packed, 5, uid, 0, uidLen);
        final int histLen = packed[5 + uidLen] & 0xFF;
        byte[] hist = null;
        if (histLen > 0 && packed.length >= 6 + uidLen + histLen) {
            hist = new byte[histLen];
            System.arraycopy(packed, 6 + uidLen, hist, 0, histLen);
        }
        final int rc = (Integer) mEmulateAM.invoke(
                mNfcService, true, sdd, plat, sak, uid, rats, hist);
        return rc == 0;
    }

    private boolean applyTypeBOrFLocked(byte[] setConfigPayload) throws Throwable {
        if (setConfigPayload == null || setConfigPayload.length == 0) return false;
        // Stop discovery -> program volatile RAM Type-B/F params -> restart discovery
        mSendVendorM.invoke(mNfcService, 1, 0x01, 0x06, new byte[]{0x00});
        final int rc = (Integer) mSendVendorM.invoke(
                mNfcService, 1, 0x00, 0x02, setConfigPayload);
        mSendVendorM.invoke(mNfcService, 1, 0x01, 0x03, RF_DISCOVER_ALL);
        mDirtyTypeBF = (rc == 0);
        return rc == 0;
    }

    private void overrideIsoDepToHostLocked(boolean host) {
        if (mCardEmuService == null || mOverwriteRoutingM == null) return;
        try {
            // "DH" = Host (0x00), "default" = restore stock route, null = leave entry unchanged.
            final String proto = host ? "DH" : "default";
            mOverwriteRoutingM.invoke(
                    mCardEmuService, UserHandle.USER_SYSTEM, null, proto, null, null, null, "android");
            mRoutingOverridden = host;
        } catch (Throwable t) {
            final Throwable cause = t.getCause() != null ? t.getCause() : t;
            Slog.w(TAG, "overwriteRoutingTable(" + host + ") failed: " + cause);
        }
    }

    private void switchWalletRoleLocked(String walletPkg) {
        try {
            final RoleManager rm = mContext.getSystemService(RoleManager.class);
            if (rm == null) return;
            final List<String> current = rm.getRoleHolders(RoleManager.ROLE_WALLET);
            if (current != null && current.size() == 1 && walletPkg.equals(current.get(0))) {
                return;
            }
            rm.addRoleHolderAsUser(
                    RoleManager.ROLE_WALLET,
                    walletPkg,
                    0,
                    UserHandle.SYSTEM,
                    mContext.getMainExecutor(),
                    ok -> {
                        if (!ok) Slog.w(TAG, "ROLE_WALLET switch failed for " + walletPkg);
                    });
        } catch (Throwable t) {
            Slog.w(TAG, "switchWalletRoleLocked failed: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // IYasvaraTempel Binder API
    // -----------------------------------------------------------------------

    @Override
    public String[] listProfiles() {
        enforceManage();
        synchronized (mLock) {
            final String[] rows = TempelNative.nativeListProfiles();
            if (rows != null && rows.length > 0) return rows;
        }
        seedDefaultPaymentIfEmpty();
        synchronized (mLock) {
            final String[] rows = TempelNative.nativeListProfiles();
            return rows != null ? rows : new String[0];
        }
    }

    @Override
    public int getActiveProfileId() {
        enforceManage();
        synchronized (mLock) {
            return TempelNative.nativeGetActiveId();
        }
    }

    @Override
    public int getDefaultPaymentProfileId() {
        enforceManage();
        synchronized (mLock) {
            return TempelNative.nativeGetDefaultPaymentId();
        }
    }

    @Override
    public void setDefaultPaymentProfile(int profileId) {
        enforceManage();
        synchronized (mLock) {
            TempelNative.nativeSetDefaultPayment(profileId);
        }
        scheduleSave();
    }

    @Override
    public int upsertProfile(
            int id, int kind, String name, int colorArgb, int iconId,
            int gate, int revertType, int revertSec, boolean showOnLockscreen,
            String walletPkg, byte[] uid, byte[] atqa, int sak, int ratsTb1,
            byte[] histBytes, byte[] appData, byte[] systemCode, byte[] pmm, byte[] payload) {
        enforceManage();
        final int assigned;
        synchronized (mLock) {
            assigned = TempelNative.nativeUpsertProfile(
                    id, kind, name != null ? name : "", colorArgb, iconId,
                    gate, revertType, revertSec, showOnLockscreen,
                    walletPkg != null ? walletPkg : "",
                    uid != null ? uid : new byte[0],
                    atqa != null ? atqa : new byte[]{0x04, 0x00},
                    sak, ratsTb1,
                    histBytes != null ? histBytes : new byte[0],
                    appData != null ? appData : new byte[0],
                    systemCode != null ? systemCode : new byte[0],
                    pmm != null ? pmm : new byte[0],
                    payload != null ? payload : new byte[0]);
        }
        if (assigned > 0) scheduleSave();
        return assigned;
    }

    @Override
    public boolean setApduRules(int profileId, String rulesText) {
        enforceManage();
        final boolean ok;
        synchronized (mLock) {
            ok = TempelNative.nativeSetApduRules(profileId, rulesText != null ? rulesText : "");
        }
        if (ok) scheduleSave();
        return ok;
    }

    @Override
    public boolean removeProfile(int profileId) {
        enforceManage();
        final boolean wasActive;
        final boolean ok;
        synchronized (mLock) {
            wasActive = TempelNative.nativeGetActiveId() == profileId;
            ok = TempelNative.nativeRemoveProfile(profileId);
        }
        if (ok) {
            if (wasActive) {
                mIoHandler.removeCallbacks(mRevertTask);
                applyActiveToHardware();
            }
            scheduleSave();
        }
        return ok;
    }

    @Override
    public byte[] getProfilePayload(int profileId) {
        enforceManage();
        synchronized (mLock) {
            return TempelNative.nativeGetPayload(profileId);
        }
    }

    @Override
    public boolean activateProfile(int profileId) {
        enforceManage();
        if (profileId != 0) {
            final long token = Binder.clearCallingIdentity();
            final boolean on;
            try {
                synchronized (mLock) {
                    on = isNfcOnLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (!on) return false;
        }
        final long delayMs;
        synchronized (mLock) {
            delayMs = TempelNative.nativeActivate(profileId, SystemClock.elapsedRealtime());
            if (delayMs < 0) return false;
        }
        mIoHandler.removeCallbacks(mRevertTask);
        if (delayMs > 0) {
            mIoHandler.postDelayed(mRevertTask, delayMs);
        }
        final boolean ok = applyActiveToHardware();
        scheduleSave();
        return ok;
    }

    @Override
    public boolean setShieldEnabled(boolean enabled) {
        enforceManage();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!isNfcOnLocked()) return false;
                if (enabled) {
                    resetToStockLocked(null);
                }
                final boolean ok = Boolean.TRUE.equals(
                        mSetObserveM.invoke(mNfcService, enabled, "android"));
                if (ok) mShieldActive = enabled;
                return ok;
            }
        } catch (Throwable t) {
            Slog.e(TAG, "setShieldEnabled failed", t);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean isShieldEnabled() {
        enforceManage();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!isNfcOnLocked()) return false;
                return Boolean.TRUE.equals(mIsObserveEnabledM.invoke(mNfcService));
            }
        } catch (Throwable t) {
            return mShieldActive;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public String getCapabilitiesSummary() {
        enforceManage();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                final boolean on = isNfcOnLocked();
                final boolean obsSup = on && Boolean.TRUE.equals(mIsObserveSupportedM.invoke(mNfcService));
                final boolean obsOn = on && Boolean.TRUE.equals(mIsObserveEnabledM.invoke(mNfcService));
                String wallet = "";
                final RoleManager rm = mContext.getSystemService(RoleManager.class);
                if (rm != null) {
                    final List<String> h = rm.getRoleHolders(RoleManager.ROLE_WALLET);
                    if (h != null && !h.isEmpty()) wallet = h.get(0);
                }
                return "nfcOn=" + on
                        + "\tobserveSupported=" + obsSup
                        + "\tobserveEnabled=" + obsOn
                        + "\twallet=" + wallet
                        + "\tactiveId=" + TempelNative.nativeGetActiveId();
            }
        } catch (Throwable t) {
            return "error=" + t.getMessage();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public byte[] processApdu(byte[] commandApdu) {
        enforceManage();
        if (commandApdu == null || commandApdu.length == 0) {
            return new byte[]{0x67, 0x00};
        }
        synchronized (mLock) {
            final byte[] rsp = TempelNative.nativeProcessApdu(commandApdu);
            return rsp != null ? rsp : new byte[]{0x6A, (byte) 0x82};
        }
    }

    @Override
    public void notifyTapCompleted() {
        enforceManage();
        final boolean reverted;
        synchronized (mLock) {
            reverted = TempelNative.nativeOnTapCompleted();
        }
        if (reverted) {
            mIoHandler.removeCallbacks(mRevertTask);
            mIoHandler.post(this::applyActiveToHardware);
            scheduleSave();
        }
    }

    @Override
    public byte[] buildNdef(int kind, String a, String b, String c) {
        enforceManage();
        return TempelNative.nativeBuildNdef(
                kind, a != null ? a : "", b != null ? b : "", c != null ? c : "");
    }

    @Override
    public String classifyTag(
            int techMask, int uidLen, int atqa0, int atqa1, int sak,
            boolean hasNdef, boolean isEMoney, int totalSectors, int readableSectors) {
        enforceManage();
        return TempelNative.nativeClassifyTag(
                techMask, uidLen, atqa0, atqa1, sak, hasNdef, isEMoney, totalSectors, readableSectors);
    }

    @Override
    public String importDump(int format, String text, byte[] bin) {
        enforceManage();
        return TempelNative.nativeImportDump(
                format, text != null ? text : "", bin != null ? bin : new byte[0]);
    }

    @Override
    public String exportProfile(int profileId, int format) {
        enforceManage();
        synchronized (mLock) {
            return TempelNative.nativeExportProfile(profileId, format);
        }
    }

    @Override
    public String decodeEMoney(int issuerCode, byte[] infoResp, byte[] balanceResp) {
        enforceManage();
        return TempelNative.nativeDecodeEMoney(
                issuerCode,
                infoResp != null ? infoResp : new byte[0],
                balanceResp != null ? balanceResp : new byte[0]);
    }

    @Override
    public byte[] buildMfcBlock0(byte[] uid, int sak, int atqa0, int atqa1) {
        enforceManage();
        return TempelNative.nativeBuildMfcBlock0(uid != null ? uid : new byte[0], sak, atqa0, atqa1);
    }

    @Override
    public boolean validateMfcAccessBits(int b6, int b7, int b8) {
        enforceManage();
        return TempelNative.nativeValidateMfcAccessBits(b6, b7, b8);
    }

    @Override
    public byte[] buildWriteFrame(int op, int arg1, int arg2, byte[] data) {
        enforceManage();
        return TempelNative.nativeBuildWriteFrame(op, arg1, arg2, data != null ? data : new byte[0]);
    }

    @Override
    public byte[] buildNtagNdefPages(int variant, byte[] ndefMsg) {
        enforceManage();
        return TempelNative.nativeBuildNtagNdefPages(variant, ndefMsg != null ? ndefMsg : new byte[0]);
    }

    /**
     * Executes NXP SN220U hardware 40-key MIFARE batch verifier (NCI 2F 45 / 6F 45).
     * Registers a transient vendor callback for the duration of this single call, then unregisters.
     */
    @Override
    public byte[] verifyMfcSectorKeys(int sector, boolean isKeyB, byte[] customKeys) {
        enforceManage();
        final long token = Binder.clearCallingIdentity();
        Object cbObj = null;
        try {
            synchronized (mLock) {
                if (!isNfcOnLocked()) return null;
            }
            final byte[] infoPayload = TempelNative.nativeBuildMfc2f45Info(sector, isKeyB);
            final byte[] keysPayload;
            if (customKeys != null && customKeys.length >= 6 && customKeys.length <= 240
                    && (customKeys.length % 6) == 0) {
                keysPayload = new byte[1 + customKeys.length];
                keysPayload[0] = 0x02;
                System.arraycopy(customKeys, 0, keysPayload, 1, customKeys.length);
            } else {
                keysPayload = TempelNative.nativeBuildMfc2f45DefaultKeys();
            }
            if (infoPayload == null || keysPayload == null) return null;

            final CountDownLatch rspLatch = new CountDownLatch(1);
            final CountDownLatch ntfLatch = new CountDownLatch(1);
            final byte[][] foundKey = new byte[1][];
            final boolean[] infoOk = new boolean[1];

            final Binder rawCb = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    final String desc = "android.nfc.INfcVendorNciCallback";
                    if (code == INTERFACE_TRANSACTION) {
                        reply.writeString(desc);
                        return true;
                    }
                    if (code == 1) { // onVendorResponseReceived(gid, oid, payload)
                        data.enforceInterface(desc);
                        final int gid = data.readInt();
                        final int oid = data.readInt();
                        final byte[] p = data.createByteArray();
                        if (gid == 0x0F && oid == 0x45 && p != null && p.length >= 2 && p[0] == 0x01) {
                            infoOk[0] = (p[1] == 0x00);
                            rspLatch.countDown();
                        }
                        return true;
                    }
                    if (code == 2) { // onVendorNotificationReceived(gid, oid, payload)
                        data.enforceInterface(desc);
                        final int gid = data.readInt();
                        final int oid = data.readInt();
                        final byte[] p = data.createByteArray();
                        if (gid == 0x0F && oid == 0x45 && p != null && p.length >= 1) {
                            if (p[0] == 0x02 && p.length >= 7) {
                                final byte[] k = new byte[6];
                                System.arraycopy(p, 1, k, 0, 6);
                                foundKey[0] = k;
                            }
                            if (p[0] == 0x00 || p[0] == 0x02 || p[0] == 0x03) {
                                ntfLatch.countDown();
                            }
                        }
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            };
            cbObj = Class.forName("android.nfc.INfcVendorNciCallback$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, rawCb);
            mRegVendorCbM.invoke(mNfcService, cbObj);

            final int rc1 = (Integer) mSendVendorM.invoke(mNfcService, 1, 0x0F, 0x45, infoPayload);
            if (rc1 != 0 || !rspLatch.await(800, TimeUnit.MILLISECONDS) || !infoOk[0]) {
                return null;
            }
            final int rc2 = (Integer) mSendVendorM.invoke(mNfcService, 1, 0x0F, 0x45, keysPayload);
            if (rc2 != 0) return null;
            ntfLatch.await(2500, TimeUnit.MILLISECONDS);
            return foundKey[0];
        } catch (Throwable t) {
            Slog.w(TAG, "verifyMfcSectorKeys failed: " + t.getMessage());
            return null;
        } finally {
            if (cbObj != null && mNfcService != null && mUnregVendorCbM != null) {
                try {
                    mUnregVendorCbM.invoke(mNfcService, cbObj);
                } catch (Throwable ignored) {
                }
            }
            Binder.restoreCallingIdentity(token);
        }
    }

    String dumpText() {
        synchronized (mLock) {
            return TempelNative.nativeDump() + getCapabilitiesSummary() + "\n";
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        pw.print(dumpText());
    }

    @Override
    public void onShellCommand(
            FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new TempelShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }
}
