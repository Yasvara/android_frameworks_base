/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.root;

import android.adb.AdbRootService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.MessageQueue;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.UnixSocketAddress;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * The control plane of the ROM's native root: which apps are enrolled, in what
 * tier, until when, and whether root is on at all.
 *
 * <p>It owns {@code /data/misc/yasvara_root/grants.v1}, which {@code yasvara_rootd} re-reads
 * whenever it changes; the service never talks to the daemon over a channel the daemon has to
 * wait for. Revocation is therefore effective on the daemon's next request. The only thing
 * flowing back is a best-effort datagram ("this uid was refused") on
 * {@code events.sock}, from which the owner's notification is made.
 *
 * <p>Apps cannot ask for root; the owner enrols them in Akar (Laras), behind the device
 * credential. Every call here needs {@link #PERMISSION_MANAGE}, and changes need the device
 * unlocked.
 */
public final class YasvaraRootService extends IYasvaraRoot.Stub {
    private static final String TAG = "YasvaraRoot";

    // servicemanager lists every name to every app, whether it may find the service or not.
    public static final String SERVICE_NAME = "yasvara_session";
    static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_ROOT";
    private static final String LARAS_PACKAGE = "org.yasvara.laras";
    private static final String TEST_ADMIN_PACKAGE = "org.yasvara.roottest.admin";
    private static final String TEST_ADMIN_PROP = "debug.yasvara.root.test_admin";
    // The switch before it moved into the grants header. Persisted properties cannot be deleted,
    // so it is only read once, for a header that predates the key.
    private static final String LEGACY_PROP_ENABLED = "persist.yasvara.root.enabled";

    static final File DIR = new File("/data/misc/yasvara_root");
    static final File GRANTS_FILE = new File(DIR, "grants.v1");
    static final File EVENTS_FILE = new File(DIR, "events.sock");
    static final File SVC_AUDIT = new File(DIR, "audit.svc");
    static final String HEADER = "# yasvara-root grants v1";

    // the daemon binds its own socket in the data directory (no /dev/socket entry)
    // and lives in /system/xbin, which only root and `shell` can search.
    private static final File DAEMON_SOCKET_FILE = new File(DIR, "run/ctl.sock");
    // Started and stopped from here, never by a property trigger: a property would name it to
    // every app. The daemon ships in exactly the builds that are not `user`.
    private static final String DAEMON_SERVICE = "yasvara_sessiond";
    private static final int PROTOCOL_MAGIC = 0x55534159; // "YASU", little endian on the wire

    static final int TIER_SHELL = 1;
    static final int TIER_ROOT = 2;
    static final int SCOPE_ALWAYS = 0;
    static final int SCOPE_UNTIL_REBOOT = 1;
    static final int SCOPE_TIMED = 2;
    static final int UID_SHELL = 2000;

    static final int STATE_ENABLED = 1;
    static final int STATE_FROZEN = 2;
    static final int STATE_DAEMON_RUNNING = 4;
    static final int STATE_DAEMON_INSTALLED = 8;

    private static final String LARAS = "org.yasvara.laras";
    static final String ACTION_ROOT_DENIED = "org.yasvara.laras.action.ROOT_DENIED";

    private static final int DEFAULT_IDLE_DAYS = 30;
    private static final int MAX_GRANTS = 256;
    private static final long SVC_AUDIT_MAX = 64 * 1024;
    private static final int MAX_DENIED = 50;

    /** One enrolled uid, as written to a line of grants.v1. */
    static final class Grant {
        int uid;
        int tier = TIER_SHELL;
        boolean global;
        int flags;
        long expiresMs;
        String bootId;
        String signer = "-";
        final ArrayList<String> packages = new ArrayList<>();
        long grantedMs;

        int scope() {
            return bootId != null ? SCOPE_UNTIL_REBOOT : expiresMs != 0 ? SCOPE_TIMED : SCOPE_ALWAYS;
        }

        String toLine() {
            // The trailing granted_ms is ours; the daemon ignores fields it does not know.
            return uid + " " + (tier == TIER_ROOT ? "root" : "shell") + " " + (global ? "global" : "req")
                    + " " + Integer.toHexString(flags) + " " + expiresMs + " "
                    + (bootId == null ? "-" : bootId) + " " + signer + " "
                    + (packages.isEmpty() ? "-" : String.join(",", packages)) + " " + grantedMs;
        }

        static Grant parse(String line) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 7) return null;
            try {
                Grant g = new Grant();
                g.uid = Integer.parseInt(f[0]);
                g.tier = "root".equals(f[1]) ? TIER_ROOT : "shell".equals(f[1]) ? TIER_SHELL : -1;
                if (g.tier < 0) return null;
                g.global = "global".equals(f[2]);
                g.flags = Integer.parseInt(f[3], 16);
                g.expiresMs = Long.parseLong(f[4]);
                g.bootId = "-".equals(f[5]) ? null : f[5];
                g.signer = f[6];
                if (f.length > 7 && !"-".equals(f[7])) {
                    for (String p : f[7].split(",")) if (!p.isEmpty()) g.packages.add(p);
                }
                if (f.length > 8) g.grantedMs = Long.parseLong(f[8]);
                return g;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static final class Denied {
        int uid;
        long lastMs;
        int count;
        String reason;
    }

    private final Context mContext;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final Object mLock = new Object();
    // uid -> grant, in insertion order
    private final LinkedHashMap<Integer, Grant> mGrants = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Denied> mDenied = new LinkedHashMap<>();
    private boolean mEnabled;
    private boolean mFrozen;
    private long mVersion;
    private int mIdleDays = DEFAULT_IDLE_DAYS;
    private boolean mNotifyDenied = true;
    private boolean mLogFull;
    private FileDescriptor mEventFd;
    private final AdbRootService mAdbRoot = new AdbRootService();

    public YasvaraRootService(Context context) {
        mContext = context;
        synchronized (mLock) {
            if (!readFileLocked()) persistLocked();
        }
        registerPackageReceiver();
        registerBootReceiver();
        if (isEnabledInternal()) startEvents();
        mHandler.post(() -> {
            try {
                verifyAll();
            } catch (Throwable t) {
                Slog.e(TAG, "boot check", t);
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // IYasvaraRoot
    // ---------------------------------------------------------------------------------------

    @Override
    public boolean isEnabled() {
        enforceMaybeSelf();
        return isEnabledInternal();
    }

    private boolean isEnabledInternal() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    @Override
    public boolean setEnabled(boolean enabled) {
        enforceMutate();
        if (enabled && !isDaemonInstalled()) return false;
        synchronized (mLock) {
            if (!DIR.isDirectory() && !DIR.mkdirs()) {
                Slog.e(TAG, "no " + DIR);
                return false;
            }
            // The file must exist (and say who is enrolled) before the daemon can run; with
            // enabled=0 a daemon not yet stopped refuses everyone from the next request on.
            mEnabled = enabled;
            persistLocked();
        }
        applyDaemonState(enabled);
        if (enabled) startEvents(); else stopEvents();
        syncAdbRoot(hasShellRootGrant());
        svcAudit("ENABLE", enabled ? "on" : "off");
        return true;
    }

    private static boolean isDaemonInstalled() {
        return !Build.IS_USER;
    }

    /** Asks init to run the daemon or not. ctl.* is never stored, so nothing is left to read. */
    private static void applyDaemonState(boolean on) {
        if (!isDaemonInstalled()) return;
        try {
            SystemProperties.set(on ? "ctl.start" : "ctl.stop", DAEMON_SERVICE);
        } catch (RuntimeException e) {
            Slog.e(TAG, (on ? "start " : "stop ") + DAEMON_SERVICE, e);
        }
    }

    /** At each boot (and system_server restart), once boot has completed: converge on the switch. */
    private void registerBootReceiver() {
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                applyDaemonState(isEnabledInternal());
            }
        }, UserHandle.SYSTEM, new IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED), null, mHandler);
    }

    @Override
    public int getState() {
        enforce();
        int s = 0;
        synchronized (mLock) {
            if (mEnabled) s |= STATE_ENABLED;
            if (mFrozen) s |= STATE_FROZEN;
        }
        if (DAEMON_SOCKET_FILE.exists()) s |= STATE_DAEMON_RUNNING;
        if (isDaemonInstalled()) s |= STATE_DAEMON_INSTALLED;
        return s;
    }

    @Override
    public boolean setFrozen(boolean frozen) {
        enforceMutate();
        synchronized (mLock) {
            if (mFrozen == frozen) return true;
            mFrozen = frozen;
            persistLocked();
        }
        syncAdbRoot(hasShellRootGrant());
        svcAudit("FREEZE", frozen ? "on" : "off");
        return true;
    }

    @Override
    public String[] listGrants() {
        enforce();
        long now = System.currentTimeMillis();
        Map<Integer, Long> last = lastUsedByUid();
        synchronized (mLock) {
            pruneLocked(now, last);
            ArrayList<String> out = new ArrayList<>();
            for (Grant g : mGrants.values()) {
                Long used = last.get(g.uid);
                out.add(g.uid + "|" + g.tier + "|" + g.scope() + "|" + (g.global ? 1 : 0) + "|"
                        + g.flags + "|" + g.expiresMs + "|" + g.grantedMs + "|"
                        + (used == null ? 0 : used) + "|" + String.join(",", g.packages));
            }
            return out.toArray(new String[0]);
        }
    }

    @Override
    public boolean setGrant(String packageName, int userId, int tier, int scope, long durationMs,
            int ns, int flags) {
        enforceMutate();
        if (tier != TIER_SHELL && tier != TIER_ROOT) {
            throw new IllegalArgumentException("tier " + tier);
        }
        if (userId == UserHandle.USER_CURRENT) userId = UserHandle.getCallingUserId();
        Grant g = new Grant();
        g.tier = tier;
        g.global = ns == 1;
        g.flags = flags & 0xf;
        g.grantedMs = System.currentTimeMillis();
        if ("shell".equals(packageName)) {
            // adb shell: switchable like any app, off by default.
            g.uid = UID_SHELL;
            g.signer = "adb";
            g.packages.add("shell");
        } else if (!resolvePackage(g, packageName, userId)) {
            return false;
        }
        if (scope == SCOPE_UNTIL_REBOOT) {
            g.bootId = bootId();
            if (g.bootId == null) return false;
        } else if (scope == SCOPE_TIMED) {
            if (durationMs <= 0) return false;
            g.expiresMs = g.grantedMs + durationMs;
        }
        synchronized (mLock) {
            if (!mGrants.containsKey(g.uid) && mGrants.size() >= MAX_GRANTS) return false;
            if (mLogFull) g.flags |= 4; // GRANT_FLAG_LOG_FULL
            mGrants.put(g.uid, g);
            mDenied.remove(g.uid);
            persistLocked();
        }
        if (g.uid == UID_SHELL) syncAdbRoot(true);
        svcAudit("GRANT", g.uid + " " + (tier == TIER_ROOT ? "root" : "shell") + " " + String.join(",", g.packages));
        return true;
    }

    /**
     * Fills uid, signer and the packages sharing the uid. Runs as system_server, not as the
     * binder caller: package visibility filtering would otherwise hide any app the caller
     * cannot see (the test admin app, or Laras without QUERY_ALL_PACKAGES) and the lookup
     * would fail as "not found".
     */
    private boolean resolvePackage(Grant g, String packageName, int userId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            PackageManager pm = mContext.getPackageManager();
            try {
                g.uid = pm.getPackageUidAsUser(packageName, userId);
                g.signer = signerOf(packageName, userId);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "setGrant: " + packageName + " not found for user " + userId);
                return false;
            }
            if (UserHandle.getAppId(g.uid) < Process.FIRST_APPLICATION_UID) {
                Slog.w(TAG, "setGrant: " + packageName + " is a system uid");
                return false;
            }
            String[] sharing = pm.getPackagesForUid(g.uid);
            if (sharing != null) g.packages.addAll(Arrays.asList(sharing));
            if (!g.packages.contains(packageName)) g.packages.add(packageName);
            Collections.sort(g.packages);
            return true;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean revoke(int uid) {
        enforceMutate();
        return revokeUid(uid, "owner");
    }

    @Override
    public void revokeAll() {
        enforceMutate();
        boolean hadShell;
        synchronized (mLock) {
            hadShell = mGrants.containsKey(UID_SHELL);
            mGrants.clear();
            persistLocked();
        }
        if (hadShell) syncAdbRoot(true);
        svcAudit("REVOKE", "all");
    }

    @Override
    public String[] getAudit(int limit) {
        enforce();
        limit = Math.max(1, Math.min(limit, 500));
        // ts -> line, newest first. The daemon's and the service's files are merged.
        TreeMap<String, String> sorted = new TreeMap<>(Collections.reverseOrder());
        int seq = 0;
        for (File f : auditFiles()) {
            for (String line : readLines(f)) {
                long ts = leadingLong(line);
                // Zero-padded so string order is time order; the sequence keeps equal stamps apart.
                sorted.put(String.format("%016d-%06d", ts, seq++), line);
            }
        }
        ArrayList<String> out = new ArrayList<>();
        for (String line : sorted.values()) {
            out.add(line);
            if (out.size() >= limit) break;
        }
        return out.toArray(new String[0]);
    }

    @Override
    public String[] getDenied() {
        enforce();
        ArrayList<String> out = new ArrayList<>();
        // As system_server: the caller may not be allowed to see the denied app (visibility).
        final long ident = Binder.clearCallingIdentity();
        try {
            PackageManager pm = mContext.getPackageManager();
            synchronized (mLock) {
                ArrayList<Denied> all = new ArrayList<>(mDenied.values());
                Collections.sort(all, (a, b) -> Long.compare(b.lastMs, a.lastMs));
                for (Denied d : all) {
                    String[] pkgs = pm.getPackagesForUid(d.uid);
                    out.add(d.uid + "|" + (pkgs == null || pkgs.length == 0 ? "" : pkgs[0]) + "|"
                            + d.lastMs + "|" + d.count + "|" + d.reason);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return out.toArray(new String[0]);
    }

    @Override
    public void clearDenied() {
        enforce();
        synchronized (mLock) {
            mDenied.clear();
        }
    }

    @Override
    public String selfTest() {
        enforce();
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(DAEMON_SOCKET_FILE.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
            s.setSoTimeout(2000);
            InputStream in = s.getInputStream();
            byte[] b = new byte[16];
            int got = 0;
            while (got < b.length) {
                int n = in.read(b, got, b.length - got);
                if (n < 0) return "the daemon closed the connection";
                got += n;
            }
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            if (bb.getInt(0) != PROTOCOL_MAGIC) return "not the root daemon (bad magic)";
            int version = bb.getInt(8);
            // system_server is not enrolled: a healthy daemon refuses it, which is the point.
            return "ok v" + version + " " + (b[5] == 0 ? "allowed" : "refused:" + reasonName(b[6] & 0xff));
        } catch (IOException e) {
            return "unreachable: " + e.getMessage();
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public int getOption(String key) {
        enforce();
        synchronized (mLock) {
            switch (key) {
                case "idle_days": return mIdleDays;
                case "notify_denied": return mNotifyDenied ? 1 : 0;
                case "log_full": return mLogFull ? 1 : 0;
                default: return -1;
            }
        }
    }

    @Override
    public boolean setOption(String key, int value) {
        enforceMutate();
        synchronized (mLock) {
            switch (key) {
                case "idle_days":
                    mIdleDays = Math.max(0, Math.min(value, 365));
                    break;
                case "notify_denied":
                    mNotifyDenied = value != 0;
                    break;
                case "log_full":
                    // Applies to grants made from now on (the per-grant flag the daemon reads).
                    mLogFull = value != 0;
                    break;
                default:
                    return false;
            }
            persistLocked();
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Access
    // ---------------------------------------------------------------------------------------

    private void enforce() {
        mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE, TAG);
        if (!callerAllowed()) {
            throw new SecurityException("only Laras and the system may manage root");
        }
    }

    /**
     * The permission is signature level, so any platform-signed app can hold it, and one with an
     * exported component becomes a way for any app to enrol itself as root (the root test admin
     * app did exactly that). Management is therefore limited to the callers that have a reason to
     * exist: the system, a root shell, this process, and Laras (a system priv-app that no
     * installed package can replace). The test admin app counts only while the test script has
     * set the `debug.` property, which only root and the shell can set.
     */
    private boolean callerAllowed() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID || uid == Process.myUid()) return true;
        final long ident = Binder.clearCallingIdentity();
        try {
            String[] pkgs = mContext.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null) return false;
            final boolean testAdmin = SystemProperties.getBoolean(TEST_ADMIN_PROP, false);
            for (String p : pkgs) {
                if (LARAS_PACKAGE.equals(p)) return true;
                if (testAdmin && TEST_ADMIN_PACKAGE.equals(p)) return true;
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** isEnabled() is also read by this process itself. */
    private void enforceMaybeSelf() {
        if (android.os.Binder.getCallingPid() == Process.myPid()) return;
        enforce();
    }

    /** Changes need the permission and an unlocked device (the credential was just entered). */
    private void enforceMutate() {
        enforce();
        KeyguardManager km = mContext.getSystemService(KeyguardManager.class);
        if (km != null && km.isDeviceLocked(UserHandle.getCallingUserId())) {
            throw new SecurityException("device is locked");
        }
    }

    // ---------------------------------------------------------------------------------------
    // grants.v1
    // ---------------------------------------------------------------------------------------

    /** False when a policy file predates the switch in its header (written back once then). */
    private boolean readFileLocked() {
        mGrants.clear();
        mEnabled = SystemProperties.getBoolean(LEGACY_PROP_ENABLED, false);
        boolean current = true;
        try {
            if (!GRANTS_FILE.isFile() || GRANTS_FILE.length() > (1 << 20)) return true;
            List<String> lines = Files.readAllLines(GRANTS_FILE.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).startsWith(HEADER)) return true;
            current = false;
            for (String kv : lines.get(0).substring(HEADER.length()).trim().split("\\s+")) {
                int eq = kv.indexOf('=');
                if (eq < 0) continue;
                String k = kv.substring(0, eq), v = kv.substring(eq + 1);
                try {
                    switch (k) {
                        case "enabled": mEnabled = "1".equals(v); current = true; break;
                        case "frozen": mFrozen = "1".equals(v); break;
                        case "version": mVersion = Long.parseLong(v); break;
                        case "idle_days": mIdleDays = Integer.parseInt(v); break;
                        case "notify_denied": mNotifyDenied = !"0".equals(v); break;
                        case "log_full": mLogFull = "1".equals(v); break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            for (int i = 1; i < lines.size() && mGrants.size() < MAX_GRANTS; i++) {
                String l = lines.get(i);
                if (l.isEmpty() || l.startsWith("#")) continue;
                Grant g = Grant.parse(l);
                if (g != null) mGrants.put(g.uid, g);
            }
        } catch (IOException | RuntimeException e) {
            Slog.w(TAG, "could not read " + GRANTS_FILE, e);
            mGrants.clear();
            return true;
        }
        return current;
    }

    /**
     * Writes the whole file with temp + rename, so the daemon sees a new inode and never a
     * half-written policy. Group-readable: the daemon is root with group system and has no
     * dac_override.
     */
    private void persistLocked() {
        mVersion++;
        StringBuilder sb = new StringBuilder(HEADER).append(" enabled=").append(mEnabled ? 1 : 0)
                .append(" frozen=").append(mFrozen ? 1 : 0)
                .append(" version=").append(mVersion)
                .append(" idle_days=").append(mIdleDays)
                .append(" notify_denied=").append(mNotifyDenied ? 1 : 0)
                .append(" log_full=").append(mLogFull ? 1 : 0).append('\n');
        for (Grant g : mGrants.values()) sb.append(g.toLine()).append('\n');
        AtomicFile f = new AtomicFile(GRANTS_FILE);
        FileOutputStream out = null;
        try {
            out = f.startWrite();
            Os.fchmod(out.getFD(), 0660);
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
        } catch (IOException | ErrnoException e) {
            Slog.e(TAG, "could not write " + GRANTS_FILE, e);
            if (out != null) f.failWrite(out);
        }
    }

    private boolean revokeUid(int uid, String why) {
        synchronized (mLock) {
            if (mGrants.remove(uid) == null) return false;
            persistLocked();
        }
        if (uid == UID_SHELL) syncAdbRoot(true);
        svcAudit("REVOKE", uid + " " + why);
        return true;
    }

    /** Drops grants nobody used for the idle period. Lazy: runs when the list is read. */
    private void pruneLocked(long now, Map<Integer, Long> lastUsed) {
        if (mIdleDays <= 0) return;
        long limit = TimeUnit.DAYS.toMillis(mIdleDays);
        boolean changed = false;
        for (Iterator<Grant> it = mGrants.values().iterator(); it.hasNext();) {
            Grant g = it.next();
            Long used = lastUsed.get(g.uid);
            long ref = Math.max(used == null ? 0 : used, g.grantedMs);
            if (ref > 0 && now - ref > limit) {
                it.remove();
                changed = true;
                svcAudit("REVOKE", g.uid + " idle");
            }
        }
        if (changed) persistLocked();
    }

    // ---------------------------------------------------------------------------------------
    // Packages: a grant belongs to an app, not to a number
    // ---------------------------------------------------------------------------------------

    private void registerPackageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Runs on the shared background thread: nothing may escape, or system_server dies.
                try {
                    if (intent.getData() == null) return;
                    String pkg = intent.getData().getSchemeSpecificPart();
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    if (uid < 0) return;
                    switch (intent.getAction()) {
                        case Intent.ACTION_PACKAGE_REMOVED:
                            if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) onRemoved(uid, pkg);
                            break;
                        default:
                            // Added or replaced: a new package under an enrolled uid, or an update
                            // signed by someone else, must not inherit the grant.
                            onChanged(uid, pkg);
                            break;
                    }
                } catch (Throwable t) {
                    Slog.e(TAG, "package event", t);
                }
            }
        }, UserHandle.ALL, filter, null, mHandler);
    }

    private void onRemoved(int uid, String pkg) {
        synchronized (mLock) {
            Grant g = mGrants.get(uid);
            if (g == null) return;
            g.packages.remove(pkg);
            String[] left = mContext.getPackageManager().getPackagesForUid(uid);
            if (g.packages.isEmpty() || left == null || left.length == 0) {
                mGrants.remove(uid);
                svcAudit("REVOKE", uid + " uninstalled " + pkg);
            } else {
                svcAudit("PACKAGE", uid + " removed " + pkg);
            }
            persistLocked();
        }
    }

    private void onChanged(int uid, String pkg) {
        synchronized (mLock) {
            Grant g = mGrants.get(uid);
            if (g == null || uid == UID_SHELL) return;
            if (!g.packages.contains(pkg)) {
                // A package that was not enrolled got an enrolled uid (uid reuse, or a new member
                // of a shared uid): only the same signer may carry on.
                if (!signerMatches(pkg, UserHandle.getUserId(uid), g.signer)) {
                    mGrants.remove(uid);
                    persistLocked();
                    svcAudit("REVOKE", uid + " uid reused by " + pkg);
                }
                return;
            }
            if (!signerMatches(pkg, UserHandle.getUserId(uid), g.signer)) {
                mGrants.remove(uid);
                persistLocked();
                svcAudit("REVOKE", uid + " signer changed " + pkg);
            }
        }
    }

    /** Boot: every grant is checked against what is installed now. */
    private void verifyAll() {
        long now = System.currentTimeMillis();
        Map<Integer, Long> last = lastUsedByUid();
        synchronized (mLock) {
            boolean changed = false;
            for (Iterator<Grant> it = mGrants.values().iterator(); it.hasNext();) {
                Grant g = it.next();
                if (g.uid == UID_SHELL) continue;
                boolean ok = !g.packages.isEmpty();
                for (String p : g.packages) {
                    if (!signerMatches(p, UserHandle.getUserId(g.uid), g.signer)) ok = false;
                }
                if (!ok) {
                    it.remove();
                    changed = true;
                    svcAudit("REVOKE", g.uid + " failed the boot check");
                }
            }
            if (changed) persistLocked();
            pruneLocked(now, last);
        }
        syncAdbRoot(false);
    }

    private boolean hasShellRootGrant() {
        synchronized (mLock) {
            Grant g = mGrants.get(UID_SHELL);
            return g != null && g.tier == TIER_ROOT;
        }
    }

    /**
     * Keeps adbd root state aligned with Akar's ADB shell root grant. When Akar has an active
     * (enabled, unfrozen, non-expired) ROOT grant for UID_SHELL, elevate adbd automatically so
     * `adb shell` comes up as root even immediately after a reboot without needing `adb root`.
     */
    private void syncAdbRoot(boolean allowDisable) {
        boolean wantRoot;
        synchronized (mLock) {
            Grant g = mGrants.get(UID_SHELL);
            long now = System.currentTimeMillis();
            String curBoot = bootId();
            boolean valid = g != null && g.tier == TIER_ROOT
                    && (g.expiresMs == 0 || g.expiresMs > now)
                    && (g.bootId == null || g.bootId.equals(curBoot));
            wantRoot = mEnabled && !mFrozen && valid;
        }
        if (!wantRoot && !allowDisable) return;
        final long ident = Binder.clearCallingIdentity();
        try {
            if (mAdbRoot.isSupported()) {
                mAdbRoot.setEnabled(wantRoot);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "syncAdbRoot(" + wantRoot + ") failed", t);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private String signerOf(String pkg, int userId) throws PackageManager.NameNotFoundException {
        PackageInfo pi = mContext.getPackageManager().getPackageInfoAsUser(
                pkg, PackageManager.GET_SIGNING_CERTIFICATES, userId);
        Signature[] now = pi.signingInfo == null ? null : pi.signingInfo.getApkContentsSigners();
        if (now == null || now.length == 0) throw new PackageManager.NameNotFoundException(pkg);
        return hash(now[0]);
    }

    /** True if the stored signer is the app's current one or any it rotated away from. */
    private boolean signerMatches(String pkg, int userId, String stored) {
        try {
            PackageInfo pi = mContext.getPackageManager().getPackageInfoAsUser(
                    pkg, PackageManager.GET_SIGNING_CERTIFICATES, userId);
            if (pi.signingInfo == null) return false;
            Set<String> known = new HashSet<>();
            Signature[] a = pi.signingInfo.getApkContentsSigners();
            if (a != null) for (Signature s : a) known.add(hash(s));
            Signature[] h = pi.signingInfo.getSigningCertificateHistory();
            if (h != null) for (Signature s : h) known.add(hash(s));
            return known.contains(stored);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String hash(Signature s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Events from the daemon
    // ---------------------------------------------------------------------------------------

    private void startEvents() {
        stopEvents();
        try {
            EVENTS_FILE.delete();
            FileDescriptor fd = Os.socket(OsConstants.AF_UNIX,
                    OsConstants.SOCK_DGRAM | OsConstants.SOCK_CLOEXEC | OsConstants.SOCK_NONBLOCK, 0);
            Os.bind(fd, UnixSocketAddress.createFileSystem(EVENTS_FILE.getPath()));
            // The daemon (root, group system) needs write on the socket file and no dac_override.
            Os.chmod(EVENTS_FILE.getPath(), 0660);
            synchronized (mLock) {
                mEventFd = fd;
            }
            mHandler.getLooper().getQueue().addOnFileDescriptorEventListener(fd,
                    MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT, (f, events) -> {
                        try {
                            drainEvents(f);
                        } catch (Throwable t) {
                            Slog.e(TAG, "event", t);
                        }
                        return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;
                    });
        } catch (ErrnoException | SocketException e) {
            Slog.w(TAG, "event socket", e);
        }
    }

    private void stopEvents() {
        FileDescriptor fd;
        synchronized (mLock) {
            fd = mEventFd;
            mEventFd = null;
        }
        if (fd == null) return;
        mHandler.getLooper().getQueue().removeOnFileDescriptorEventListener(fd);
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
        }
        EVENTS_FILE.delete();
    }

    private void drainEvents(FileDescriptor fd) {
        byte[] buf = new byte[256];
        try {
            while (true) {
                int n = Os.recvfrom(fd, buf, 0, buf.length, 0, null);
                if (n <= 0) break;
                onEvent(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (ErrnoException e) {
            if (e.errno != OsConstants.EAGAIN) Slog.w(TAG, "event recv", e);
        } catch (SocketException e) {
            Slog.w(TAG, "event recv", e);
        }
    }

    /** "D|uid|ts|reason": the daemon refused uid. */
    private void onEvent(String msg) {
        String[] f = msg.split("\\|");
        if (f.length < 4 || !"D".equals(f[0])) return;
        int uid;
        long ts;
        try {
            uid = Integer.parseInt(f[1]);
            ts = Long.parseLong(f[2]);
        } catch (NumberFormatException e) {
            return;
        }
        // Only apps: never the owner's own shell or system uids.
        if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) return;
        boolean notify;
        synchronized (mLock) {
            Denied d = mDenied.remove(uid);
            if (d == null) d = new Denied();
            d.uid = uid;
            d.lastMs = ts;
            d.count++;
            d.reason = f[3];
            mDenied.put(uid, d);
            while (mDenied.size() > MAX_DENIED) {
                mDenied.remove(mDenied.keySet().iterator().next());
            }
            notify = mNotifyDenied;
        }
        if (!notify) return;
        String[] pkgs = mContext.getPackageManager().getPackagesForUid(uid);
        Intent i = new Intent(ACTION_ROOT_DENIED).setPackage(LARAS)
                .putExtra("uid", uid)
                .putExtra("package", pkgs == null || pkgs.length == 0 ? "" : pkgs[0])
                .putExtra("reason", f[3]);
        mContext.sendBroadcastAsUser(i, UserHandle.SYSTEM);
    }

    // ---------------------------------------------------------------------------------------
    // Audit (the daemon writes audit.0/1, this service audit.svc)
    // ---------------------------------------------------------------------------------------

    private List<File> auditFiles() {
        return Arrays.asList(new File(DIR, "audit.1"), new File(DIR, "audit.0"),
                new File(DIR, "audit.svc.1"), SVC_AUDIT);
    }

    private static List<String> readLines(File f) {
        try {
            if (!f.isFile() || f.length() > (512 << 10)) return Collections.emptyList();
            return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private static long leadingLong(String line) {
        int bar = line.indexOf('|');
        try {
            return Long.parseLong(bar < 0 ? line : line.substring(0, bar));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Latest EXEC per uid, from the audit: "ts|EXEC|uid|…". */
    private Map<Integer, Long> lastUsedByUid() {
        java.util.HashMap<Integer, Long> out = new java.util.HashMap<>();
        for (File f : auditFiles()) {
            for (String line : readLines(f)) {
                String[] p = line.split("\\|", 4);
                if (p.length < 3 || !"EXEC".equals(p[1])) continue;
                try {
                    int uid = Integer.parseInt(p[2]);
                    long ts = Long.parseLong(p[0]);
                    Long cur = out.get(uid);
                    if (cur == null || ts > cur) out.put(uid, ts);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private void svcAudit(String event, String detail) {
        String line = (System.currentTimeMillis() + "|" + event + "|0|0|-|-|" + detail)
                .replace('\n', ' ').replace('\r', ' ');
        if (line.length() > 300) line = line.substring(0, 300);
        try {
            if (SVC_AUDIT.length() > SVC_AUDIT_MAX) {
                SVC_AUDIT.renameTo(new File(DIR, "audit.svc.1"));
            }
            Files.write(SVC_AUDIT.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            Os.chmod(SVC_AUDIT.getPath(), 0660);
        } catch (IOException | ErrnoException e) {
            Slog.w(TAG, "audit", e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static String bootId() {
        try {
            return new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),
                    StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static String reasonName(int r) {
        switch (r) {
            case 0: return "ok";
            case 1: return "not_enrolled";
            case 2: return "frozen";
            case 3: return "expired";
            case 4: return "locked";
            case 5: return "busy";
            case 6: return "disabled";
            default: return "code" + r;
        }
    }
}
