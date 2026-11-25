/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.saring;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.INetd;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.net.IOemNetd;
import com.android.internal.os.BackgroundThread;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Owner side of name and browser filtering. The name table lives in netd, which refuses
 * lookups on its own from early boot; this service only hands it a new table and its settings.
 * Browsers are steered through the managed configuration every Chromium build reads.
 */
public final class YasvaraSaringService extends IYasvaraSaring.Stub {
    private static final String TAG = "YasvaraSaring";
    public static final String SERVICE_NAME = "yasvara_saring";
    static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_SARING";

    static final String NAMES = "names";
    static final String BROWSER = "browser";
    static final String WEB = "web";
    static final String DNS_ENCRYPTED = "dns_encrypted";
    static final String SAFESEARCH = "safesearch";
    static final String RADIO_GUARD = "radio_guard";
    static final String BOOST = "boost";
    static final String EXEMPT = "exempt";
    static final String BROWSERS = "browsers";
    static final String POPUPS = "popups";
    static final String NOTIFICATIONS = "notifications";
    private static final List<String> LAYERS = Arrays.asList(
            NAMES, BROWSER, WEB, DNS_ENCRYPTED, SAFESEARCH, RADIO_GUARD, BOOST);
    private static final List<String> LISTS = Arrays.asList(EXEMPT, BROWSERS, POPUPS, NOTIFICATIONS);
    private static final String[] RADIO_GUARD_PACKAGES = {
            "com.qti.qcc",
            "com.android.imsserviceentitlement",
            "com.android.devicediagnostics",
            "com.android.devicelockcontroller",
    };

    private static final File DIR = new File("/data/system/yasvara/saring");
    private static final File STORE = new File(DIR, "state");
    private static final File TABLE = new File(DIR, "names.bin");
    private static final String ENTITY = "yasvara.saring";
    private static final int USER = UserHandle.USER_SYSTEM;
    private static final int MAX_TABLE = 64 * 1024 * 1024;
    private static final int CHUNK = 256 * 1024;
    private static final int MAX_VALUES = 2048;
    private static final int MAX_VALUE_CHARS = 256;
    private static final int MAX_PAUSE_SECONDS = 24 * 60 * 60;
    private static final int TABLE_MAGIC = 0x474e5253; // "SRNG"

    private final Context mContext;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArraySet<String> mLayers = new ArraySet<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, ArraySet<String>> mLists = new ArrayMap<>();
    @GuardedBy("mLock")
    private long mPausedUntilElapsed;
    /** Serializes table uploads to netd. */
    private final Object mTableLock = new Object();

    public YasvaraSaringService(Context context) {
        mContext = context;
        for (String list : LISTS) mLists.put(list, new ArraySet<>());
        synchronized (mLock) {
            load();
        }
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                mHandler.post(() -> {
                    syncNetd(true);
                    syncBrowsers();
                });
            }
        }, UserHandle.SYSTEM, new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);
    }

    @Override
    public boolean getLayer(String layer) {
        enforce();
        synchronized (mLock) {
            return mLayers.contains(layer);
        }
    }

    @Override
    public void setLayer(String layer, boolean on) {
        enforce();
        if (!LAYERS.contains(layer)) throw new IllegalArgumentException("Unknown layer " + layer);
        synchronized (mLock) {
            if (on ? !mLayers.add(layer) : !mLayers.remove(layer)) return;
            if (NAMES.equals(layer)) mPausedUntilElapsed = 0;
            save();
        }
        if (SAFESEARCH.equals(layer)) {
            apply(NAMES);
            apply(BROWSER);
        } else if (!WEB.equals(layer)) {
            apply(layer);
        }
    }

    @Override
    public ParcelFileDescriptor openTable() {
        final int uid = Binder.getCallingUid();
        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        synchronized (mLock) {
            if (!mLayers.contains(WEB) || packages == null) return null;
            final ArraySet<String> exempt = mLists.get(EXEMPT);
            for (String pkg : packages) {
                if (exempt.contains(pkg)) return null;
            }
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return ParcelFileDescriptor.open(TABLE, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void pause(int seconds) {
        enforce();
        final int clamped = Math.max(0, Math.min(seconds, MAX_PAUSE_SECONDS));
        synchronized (mLock) {
            mPausedUntilElapsed = clamped == 0 ? 0 : SystemClock.elapsedRealtime() + clamped * 1000L;
        }
        apply(NAMES);
    }

    @Override
    public long install(ParcelFileDescriptor table) {
        enforce();
        if (table == null) return -1;
        final long token = Binder.clearCallingIdentity();
        try (ParcelFileDescriptor fd = table) {
            synchronized (mTableLock) {
                final File incoming = new File(DIR, "names.tmp");
                DIR.mkdirs();
                if (!copy(fd, incoming) || !incoming.renameTo(TABLE)) {
                    incoming.delete();
                    return -1;
                }
                return push();
            }
        } catch (IOException e) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public String[] getList(String list) {
        enforce();
        synchronized (mLock) {
            final ArraySet<String> values = mLists.get(list);
            if (values == null) throw new IllegalArgumentException("Unknown list " + list);
            final String[] out = values.toArray(new String[0]);
            Arrays.sort(out);
            return out;
        }
    }

    @Override
    public void setList(String list, String[] values) {
        enforce();
        if (!LISTS.contains(list)) throw new IllegalArgumentException("Unknown list " + list);
        if (values == null) values = new String[0];
        if (values.length > MAX_VALUES) throw new IllegalArgumentException("Too many values");
        final ArraySet<String> clean = new ArraySet<>();
        for (String v : values) {
            if (v == null || v.isEmpty() || v.length() > MAX_VALUE_CHARS
                    || v.indexOf('\n') >= 0 || v.indexOf('=') >= 0) {
                throw new IllegalArgumentException("Bad value");
            }
            clean.add(v);
        }
        final ArraySet<String> dropped;
        synchronized (mLock) {
            dropped = new ArraySet<>(mLists.get(list));
            dropped.removeAll(clean);
            mLists.put(list, clean);
            save();
        }
        final long token = Binder.clearCallingIdentity();
        try {
            if (BROWSERS.equals(list)) {
                for (String pkg : dropped) setRestrictions(pkg, null);
            }
            apply(EXEMPT.equals(list) ? NAMES : BROWSER);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public long[] getStats() {
        enforce();
        final long[] out = new long[7];
        final long token = Binder.clearCallingIdentity();
        try {
            final long[] netd = oem().saringStats();
            System.arraycopy(netd, 0, out, 0, Math.min(netd.length, 4));
            if (netd.length >= 5) out[5] = netd[4]; // cache_hits
            if (netd.length >= 6) out[6] = netd[5]; // poison_blocked
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "No stats from netd", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        synchronized (mLock) {
            final long left = mPausedUntilElapsed - SystemClock.elapsedRealtime();
            out[4] = left > 0 ? System.currentTimeMillis() + left : 0;
        }
        return out;
    }

    @Override
    public List<String> getLog() {
        enforce();
        return log(false);
    }

    @Override
    public void clearLog() {
        enforce();
        log(true);
    }

    private List<String> log(boolean clear) {
        final long token = Binder.clearCallingIdentity();
        try {
            return Arrays.asList(oem().saringLog(clear));
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "No log from netd", e);
            return List.of();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void enforce() {
        mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE, TAG);
    }

    private void apply(String layer) {
        final long token = Binder.clearCallingIdentity();
        try {
            if (BROWSER.equals(layer)) {
                syncBrowsers();
            } else {
                syncNetd(false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static IOemNetd oem() throws RemoteException {
        final IBinder netd = ServiceManager.getService(Context.NETD_SERVICE);
        if (netd == null) throw new RemoteException("netd is not up");
        return IOemNetd.Stub.asInterface(INetd.Stub.asInterface(netd).getOemNetd());
    }

    /** Sends the settings; with {@code table}, also the stored table when netd holds none. */
    private void syncNetd(boolean table) {
        final boolean on;
        final boolean dnsEncrypted;
        final boolean strictSafeSearch;
        final boolean radioGuard;
        final boolean boost;
        final String[] exempt;
        final int pause;
        synchronized (mLock) {
            on = mLayers.contains(NAMES);
            dnsEncrypted = mLayers.contains(DNS_ENCRYPTED);
            strictSafeSearch = mLayers.contains(SAFESEARCH);
            radioGuard = mLayers.contains(RADIO_GUARD);
            boost = mLayers.contains(BOOST);
            exempt = mLists.get(EXEMPT).toArray(new String[0]);
            final long left = mPausedUntilElapsed - SystemClock.elapsedRealtime();
            pause = left > 0 ? (int) (left / 1000) + 1 : 0;
        }
        final PackageManager pm = mContext.getPackageManager();
        final ArraySet<Integer> appIds = new ArraySet<>();
        for (String pkg : exempt) {
            try {
                appIds.add(UserHandle.getAppId(pm.getPackageUidAsUser(pkg, USER)));
            } catch (PackageManager.NameNotFoundException ignored) {
                // Stays in the list for when the app comes back.
            }
        }
        final int[] ids = new int[appIds.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = appIds.valueAt(i);

        final ArraySet<Integer> blockedSet = new ArraySet<>();
        if (radioGuard) {
            for (String pkg : RADIO_GUARD_PACKAGES) {
                try {
                    blockedSet.add(UserHandle.getAppId(pm.getPackageUidAsUser(pkg, USER)));
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        final int[] blockedIds = new int[blockedSet.size()];
        for (int i = 0; i < blockedIds.length; i++) blockedIds[i] = blockedSet.valueAt(i);

        try {
            final IOemNetd oem = oem();
            if (table && TABLE.isFile() && oem.saringStats()[2] == 0) {
                synchronized (mTableLock) {
                    push();
                }
            }
            oem.saringConfigure(on, ids, pause);
            final int dnsModeFlags = (dnsEncrypted ? 1 : 0) | (boost ? 0x10 : 0);
            oem.saringSetOptions(dnsModeFlags, strictSafeSearch ? 1 : 0, blockedIds);
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Could not configure netd", e);
        }
    }

    /** Caller holds mTableLock. */
    private long push() {
        try (FileInputStream in = new FileInputStream(TABLE)) {
            final IOemNetd oem = oem();
            oem.saringBegin();
            final byte[] chunk = new byte[CHUNK];
            int n;
            while ((n = in.read(chunk)) > 0) {
                oem.saringAppend(n == chunk.length ? chunk : Arrays.copyOf(chunk, n));
            }
            final long names = oem.saringCommit();
            Slog.i(TAG, "Table of " + names + " names in use");
            return names;
        } catch (IOException | RemoteException | RuntimeException e) {
            Slog.e(TAG, "Could not hand the table to netd", e);
            return -1;
        }
    }

    private static boolean copy(ParcelFileDescriptor from, File to) {
        final byte[] buf = new byte[CHUNK];
        long total = 0;
        try (InputStream in = new FileInputStream(from.getFileDescriptor());
                FileOutputStream out = new FileOutputStream(to)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (total == 0 && (n < 32 || magic(buf) != TABLE_MAGIC)) return false;
                total += n;
                if (total > MAX_TABLE) return false;
                out.write(buf, 0, n);
            }
            out.getFD().sync();
            return total > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static int magic(byte[] b) {
        return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
    }

    private void syncBrowsers() {
        final boolean on;
        final boolean strictSafeSearch;
        final String[] browsers;
        final Bundle policy = new Bundle();
        synchronized (mLock) {
            on = mLayers.contains(BROWSER);
            strictSafeSearch = mLayers.contains(SAFESEARCH);
            browsers = mLists.get(BROWSERS).toArray(new String[0]);
            policy.putStringArray("PopupsAllowedForUrls",
                    mLists.get(POPUPS).toArray(new String[0]));
            policy.putStringArray("NotificationsAllowedForUrls",
                    mLists.get(NOTIFICATIONS).toArray(new String[0]));
        }
        // 2 is "block" for each of these content settings.
        policy.putInt("DefaultPopupsSetting", 2);
        policy.putInt("AdsSettingForIntrusiveAdsSites", 2);
        policy.putInt("DefaultNotificationsSetting", 2);
        policy.putInt("DefaultAutomaticDownloadsSetting", 2);
        policy.putInt("SafeBrowsingProtectionLevel", 1);
        // Owner controls SafeSearch; browser search engine itself is never overridden.
        policy.putBoolean("ForceGoogleSafeSearch", strictSafeSearch);
        policy.putInt("ForceYouTubeRestrict", strictSafeSearch ? 2 : 0);
        // The browser must resolve through the platform or the name layer never sees it.
        policy.putString("DnsOverHttpsMode", "off");
        policy.putBoolean("BuiltInDnsClientEnabled", false);
        policy.putBoolean("MetricsReportingEnabled", false);
        policy.putBoolean("UrlKeyedAnonymizedDataCollectionEnabled", false);
        policy.putBoolean("DomainReliabilityAllowed", false);
        policy.putBoolean("FeedbackSurveysEnabled", false);
        policy.putBoolean("UserFeedbackAllowed", false);
        for (String pkg : browsers) setRestrictions(pkg, on ? policy : null);
    }

    private void setRestrictions(String pkg, Bundle values) {
        try {
            mContext.createContextAsUser(UserHandle.of(USER), 0)
                    .getSystemService(DevicePolicyManager.class)
                    .setApplicationRestrictionsBySystem(ENTITY, pkg, values);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Could not set restrictions of " + pkg, e);
        }
    }

    @GuardedBy("mLock")
    private void load() {
        if (!STORE.exists()) return;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new AtomicFile(STORE).openRead(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                final int eq = line.indexOf('=');
                if (eq <= 0) continue;
                final String key = line.substring(0, eq);
                final String value = line.substring(eq + 1);
                if ("layer".equals(key) && LAYERS.contains(value)) {
                    mLayers.add(value);
                } else if (mLists.containsKey(key) && !value.isEmpty()) {
                    mLists.get(key).add(value);
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Could not read " + STORE, e);
        }
    }

    @GuardedBy("mLock")
    private void save() {
        final StringBuilder sb = new StringBuilder();
        for (String layer : mLayers) sb.append("layer=").append(layer).append('\n');
        for (int i = 0; i < mLists.size(); i++) {
            for (String v : mLists.valueAt(i)) {
                sb.append(mLists.keyAt(i)).append('=').append(v).append('\n');
            }
        }
        DIR.mkdirs();
        final AtomicFile file = new AtomicFile(STORE);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            file.failWrite(out);
            Slog.e(TAG, "Could not write " + STORE, e);
        }
    }
}
