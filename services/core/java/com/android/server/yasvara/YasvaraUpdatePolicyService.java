/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara;

import static android.security.yasvara.YasvaraUpdatePolicy.POLICY_ASK;
import static android.security.yasvara.YasvaraUpdatePolicy.POLICY_AUTO;
import static android.security.yasvara.YasvaraUpdatePolicy.POLICY_DELAY;
import static android.security.yasvara.YasvaraUpdatePolicy.POLICY_NEVER;
import static android.security.yasvara.YasvaraUpdatePolicy.POLICY_SKIP;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.os.Environment;
import android.os.StatFs;
import android.os.Binder;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.yasvara.IYasvaraUpdatePolicy;
import android.security.yasvara.YasvaraSuperUser;
import android.security.yasvara.YasvaraUpdatePolicy;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Keeps and enforces the per-app update policy: Auto, Delay N days, Ask, Never.
 *
 * <p>{@code PackageInstallerSession} asks {@link #decide} once the APK of an update session is
 * parsed. The decision covers every store, because it keys on the package being updated and not
 * on the installer; adb, root, system and the device's own package installer UI are exempt
 * before the call is made. The gate path takes {@link #mLock} briefly and never does IO: rule
 * changes and first-seen records are written by a debounced job on the background thread.
 *
 * <p>The installer of record is never touched, so licence and integrity checks that ask where
 * an app came from keep seeing the store. A paused app that is hidden from its store gets Laras
 * as update owner instead: Play skips apps it doesn't own, so it stops offering, downloading and
 * retrying updates the gate would refuse anyway.
 */
public final class YasvaraUpdatePolicyService extends IYasvaraUpdatePolicy.Stub {
    private static final String TAG = "YasvaraUpdatePolicy";

    static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_UPDATE_POLICY";

    public static final int DECISION_ALLOW = 0;
    public static final int DECISION_DENY = 1;
    public static final int DECISION_ASK = 2;

    private static final File FILE = new File("/data/system/yasvara/update_policy.xml");
    private static final int FILE_VERSION = 2;
    private static final long WRITE_DELAY_MS = 500;
    private static final int MAX_FIRST_SEEN = 8;
    private static final int MAX_HISTORY = 20;

    private static final File VAULT_ROOT = new File("/data/system/yasvara/vault");
    private static final String META = "meta.properties";
    private static final int VAULT_KEEP_PER_APP = 2;
    private static final long VAULT_MAX_BYTES = 4L << 30;
    private static final long VAULT_MIN_FREE_PERCENT = 5;
    private static final long VAULT_ORPHAN_MS = TimeUnit.DAYS.toMillis(30);
    /** Result sink for a rollback started without a status receiver (adb, tests). */
    private static final String ACTION_ROLLBACK_DONE = "com.android.server.yasvara.ROLLBACK_DONE";
    private static final long NOTIFY_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);

    /** Core apps (§4.8): detachable only from the Laras Lab, reverted when they crash-loop. */
    private static final Set<String> CORE = Set.of("com.android.vending",
            "com.google.android.gms", "com.google.android.gsf");
    private static final int REVERT_STRIKES = 3;
    private static final long REVERT_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    /** A crash this soon after a rollback offers to clear the app's data (§4.4). */
    private static final long ROLLBACK_CRASH_WINDOW_MS = TimeUnit.MINUTES.toMillis(2);
    /** A store retrying a denied update more often than this a day is logged once (§9). */
    private static final int RETRY_LOG_THRESHOLD = 20;
    private static final long STALE_CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);

    /**
     * How a paused app is hidden from its store: "owner" hands update ownership to Laras (Play
     * skips it but labels it as not from Play); "spoof" shows Play the highest int version, so
     * Play sees nothing newer and keeps treating the app as its own.
     */
    private static final String PROP_HIDE = "persist.sys.yasvara.update_hide";

    private static volatile YasvaraUpdatePolicyService sInstance;

    /** userId -> package -> Allow once expiry, for packages hidden by version spoof. */
    private volatile SparseArray<Map<String, Long>> mSpoofed = new SparseArray<>();

    private final Context mContext;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final AtomicFile mFile = new AtomicFile(FILE);
    private final Object mLock = new Object();

    /** userId -> package -> state. Guarded by mLock. */
    private final SparseArray<ArrayMap<String, AppState>> mUsers = new SparseArray<>();

    private final Runnable mWriteRunnable = this::writeNow;

    /** "user:pkg" -> review lines of the update waiting for confirmation. Guarded by mLock. */
    private final ArrayMap<String, String[]> mReviews = new ArrayMap<>();
    /** "user:pkg" -> last owner notification. Guarded by mLock. */
    private final ArrayMap<String, Long> mLastNotified = new ArrayMap<>();
    /** "user:pkg" -> recent crash times (elapsed) of a detached core app. Guarded by mLock. */
    private final ArrayMap<String, ArrayDeque<Long>> mStrikes = new ArrayMap<>();
    /** "user:pkg" -> when a rollback of it was started (elapsed). Guarded by mLock. */
    private final ArrayMap<String, Long> mRolledBack = new ArrayMap<>();
    /** "user:pkg" -> {day, denials that day}. Guarded by mLock. */
    private final ArrayMap<String, long[]> mDenials = new ArrayMap<>();
    /** "user:pkg" of paused apps whose pending confirmation is an Allow once offer. Guarded by mLock. */
    private final ArraySet<String> mPausedAsks = new ArraySet<>();
    /**
     * Package -> update owner it had before Laras took it ("" for none). Update ownership is
     * per package, not per user. Guarded by mLock.
     */
    private final ArrayMap<String, String> mSavedOwners = new ArrayMap<>();

    private static final class AppState {
        int policy = POLICY_AUTO;
        int delayDays;
        /** POLICY_SKIP: the refused versionCode. */
        long skipVc;
        /** POLICY_NEVER: keep the store as update owner, so it still lists updates. */
        boolean shown;
        /** Allow once: wall-clock expiry, and the version installed when it was granted. */
        long allowUntil;
        long allowBaseVc;
        /** Save the installed version before store updates even on Auto (§4.4 opt-in). */
        boolean vault;
        /** When policy was last changed (wall clock); drives the stale reminder (§9). */
        long since;
        /** The stale reminder for this policy was shown. */
        boolean reminded;
        /** versionCode -> first time an installer offered it, oldest first. */
        final LinkedHashMap<Long, Long> firstSeen = new LinkedHashMap<>();
        /** Newest first. */
        final ArrayDeque<String> history = new ArrayDeque<>();

        boolean isEmpty() {
            return policy == POLICY_AUTO && !vault && allowUntil == 0 && firstSeen.isEmpty()
                    && history.isEmpty();
        }
    }

    public YasvaraUpdatePolicyService(Context context) {
        mContext = context;
        synchronized (mLock) {
            readLocked();
            rebuildSpoofedLocked();
        }
        sInstance = this;
        registerPackageReceivers();
        mHandler.post(this::migrateLegacyDetachList);
        mHandler.post(this::syncAllOwners);
        mHandler.post(this::pruneOrphanVaults);
        mHandler.postDelayed(this::checkStale, TimeUnit.MINUTES.toMillis(5));
        Slog.i(TAG, "started, users=" + mUsers.size());
    }

    /** The running service, or null before system_server registered it. */
    public static YasvaraUpdatePolicyService getInstance() {
        return sInstance;
    }

    // ---------------------------------------------------------------------------------------
    // system_server callers
    // ---------------------------------------------------------------------------------------

    /**
     * Decides whether a store may install versionCode of pkg over the installed one, and records
     * the attempt. Only called for sessions from non-exempt installers. confirmed is true once
     * the owner accepted the confirmation an Ask decision asked for.
     */
    public int decide(String pkg, int userId, long fromVersionCode, long versionCode,
            String installer, int installerUid, boolean confirmed) {
        // Same-version sessions add splits or repair the install; they change no code version.
        if (versionCode <= fromVersionCode) return DECISION_ALLOW;
        // Outside mLock: it reaches into the activity manager.
        final boolean installerOnTop = isOnTop(installerUid);
        final int decision;
        final boolean notify;
        synchronized (mLock) {
            // Apps on Auto keep no state at all: nothing to record, nothing to write.
            AppState state = stateLocked(userId, pkg, false);
            if (state == null || state.policy == POLICY_AUTO) return DECISION_ALLOW;
            long now = System.currentTimeMillis();
            Long seen = state.firstSeen.get(versionCode);
            if (seen == null) {
                seen = now;
                state.firstSeen.put(versionCode, now);
                trimFirstSeen(state);
            }
            switch (state.policy) {
                case POLICY_NEVER:
                    if (confirmed || state.allowUntil > now) {
                        decision = DECISION_ALLOW;
                    } else if (installerOnTop) {
                        // The owner is looking at the store, most likely after tapping Update:
                        // offer Allow once instead of a bare failure.
                        decision = DECISION_ASK;
                        mPausedAsks.add(reviewKey(userId, pkg));
                    } else {
                        decision = DECISION_DENY;
                    }
                    break;
                case POLICY_SKIP:
                    decision = versionCode <= state.skipVc ? DECISION_DENY : DECISION_ALLOW;
                    break;
                case POLICY_DELAY:
                    decision = now - seen >= TimeUnit.DAYS.toMillis(state.delayDays)
                            ? DECISION_ALLOW : DECISION_DENY;
                    break;
                case POLICY_ASK:
                    decision = confirmed ? DECISION_ALLOW : DECISION_ASK;
                    break;
                default:
                    decision = DECISION_ALLOW;
            }
            addHistory(state, now + "|" + fromVersionCode + "|" + versionCode + "|"
                    + installer + "|" + decisionName(state.policy, decision));
            scheduleWriteLocked();
            if (decision == DECISION_DENY) countDenialLocked(pkg, userId, installer, now);
            // Delay deferrals and skips stay silent; Never and Ask tell the owner, rate limited.
            // A paused app's Allow once offer is already on screen.
            notify = (decision == DECISION_DENY && state.policy == POLICY_NEVER)
                    || (decision == DECISION_ASK && state.policy == POLICY_ASK);
        }
        if (decision != DECISION_ALLOW) {
            Slog.i(TAG, "update of " + pkg + " to " + versionCode + " by " + installer
                    + " for user " + userId + ": " + (decision == DECISION_DENY ? "deny" : "ask"));
        }
        if (notify) {
            mHandler.post(() -> sendUpdateEvent(pkg, userId, versionCode, installer,
                    decision == DECISION_ASK ? "ask" : "blocked"));
        }
        return decision;
    }

    /** True when the installed version of pkg is saved before a store update (§4.4). */
    public boolean shouldCaptureVault(String pkg, int userId) {
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            return s != null && (s.policy != POLICY_AUTO || s.vault);
        }
    }

    private static boolean hideBySpoof() {
        return "spoof".equals(SystemProperties.get(PROP_HIDE, "owner"));
    }

    /**
     * True when the Play Store should see pkg at the highest int version (see PROP_HIDE). Cheap:
     * one volatile read for packages without a paused rule.
     */
    public static boolean isVersionSpoofed(String pkg, int userId) {
        YasvaraUpdatePolicyService svc = sInstance;
        if (svc == null || pkg == null) return false;
        Map<String, Long> apps = svc.mSpoofed.get(userId);
        Long allowUntil = apps == null ? null : apps.get(pkg);
        return allowUntil != null && allowUntil <= System.currentTimeMillis();
    }

    private void rebuildSpoofedLocked() {
        SparseArray<Map<String, Long>> out = new SparseArray<>();
        if (hideBySpoof()) {
            for (int u = 0; u < mUsers.size(); u++) {
                ArrayMap<String, AppState> apps = mUsers.valueAt(u);
                ArrayMap<String, Long> hidden = new ArrayMap<>();
                for (int i = 0; i < apps.size(); i++) {
                    AppState s = apps.valueAt(i);
                    if (s.policy == POLICY_NEVER && !s.shown) hidden.put(apps.keyAt(i), s.allowUntil);
                }
                if (!hidden.isEmpty()) out.put(mUsers.keyAt(u), hidden);
            }
        }
        mSpoofed = out;
    }

    /** True when uid has an activity on screen, e.g. the owner is looking at the store. */
    public static boolean isOnTop(int uid) {
        ActivityManagerInternal am = LocalServices.getService(ActivityManagerInternal.class);
        return am != null && am.getUidProcessState(uid) <= ActivityManager.PROCESS_STATE_TOP;
    }

    /**
     * AppErrors: a process of pkg crashed. A core app with a rule that crashes
     * {@link #REVERT_STRIKES} times in {@link #REVERT_WINDOW_MS} goes back to Auto and Laras
     * tells the owner why (§4.8). Never blocks the crash path.
     */
    public static void noteCrash(String pkg, int userId) {
        YasvaraUpdatePolicyService svc = sInstance;
        if (svc == null || pkg == null) return;
        final long now = SystemClock.elapsedRealtime();
        svc.mHandler.post(() -> {
            if (CORE.contains(pkg)) svc.onCoreCrash(pkg, userId, now);
            svc.onRolledBackCrash(pkg, userId, now);
        });
    }

    /** §4.4: a crash soon after a rollback, likely newer data in an older app. Once per rollback. */
    private void onRolledBackCrash(String pkg, int userId, long now) {
        synchronized (mLock) {
            Long started = mRolledBack.remove(reviewKey(userId, pkg));
            if (started == null || now - started > ROLLBACK_CRASH_WINDOW_MS) return;
            mLastNotified.remove(reviewKey(userId, pkg));
        }
        Slog.w(TAG, pkg + " crashed right after a rollback; offering to clear its data");
        sendUpdateEvent(pkg, userId, 0, null, "rollback_crash");
    }

    private void countDenialLocked(String pkg, int userId, String installer, long now) {
        final long day = now / TimeUnit.DAYS.toMillis(1);
        long[] c = mDenials.computeIfAbsent(reviewKey(userId, pkg), k -> new long[2]);
        if (c[0] != day) {
            c[0] = day;
            c[1] = 0;
        }
        if (++c[1] == RETRY_LOG_THRESHOLD + 1) {
            Slog.w(TAG, installer + " retried the denied update of " + pkg + " more than "
                    + RETRY_LOG_THRESHOLD + " times today; still denying");
        }
    }

    /** §9: one reminder for an app paused for STALE_REMINDER_DAYS, then never again. */
    private void checkStale() {
        final long now = System.currentTimeMillis();
        final long limit = TimeUnit.DAYS.toMillis(YasvaraUpdatePolicy.STALE_REMINDER_DAYS);
        final ArrayList<Pair<Integer, String>> due = new ArrayList<>();
        synchronized (mLock) {
            for (int u = 0; u < mUsers.size(); u++) {
                ArrayMap<String, AppState> apps = mUsers.valueAt(u);
                for (int i = 0; i < apps.size(); i++) {
                    AppState s = apps.valueAt(i);
                    if (s.policy != POLICY_NEVER || s.reminded || s.since == 0
                            || now - s.since < limit) {
                        continue;
                    }
                    s.reminded = true;
                    due.add(Pair.create(mUsers.keyAt(u), apps.keyAt(i)));
                }
            }
            if (!due.isEmpty()) scheduleWriteLocked();
        }
        for (Pair<Integer, String> d : due) {
            synchronized (mLock) {
                mLastNotified.remove(reviewKey(d.first, d.second));
            }
            sendUpdateEvent(d.second, d.first, 0, null, "stale");
        }
        mHandler.postDelayed(this::checkStale, STALE_CHECK_INTERVAL_MS);
    }

    private void onCoreCrash(String pkg, int userId, long now) {
        final String key = reviewKey(userId, pkg);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            if (s == null || s.policy == POLICY_AUTO) {
                mStrikes.remove(key);
                return;
            }
            ArrayDeque<Long> times = mStrikes.computeIfAbsent(key, k -> new ArrayDeque<>());
            times.addLast(now);
            while (now - times.peekFirst() > REVERT_WINDOW_MS) times.removeFirst();
            Slog.w(TAG, "detached core app " + pkg + " crashed (" + times.size() + "/"
                    + REVERT_STRIKES + ")");
            if (times.size() < REVERT_STRIKES) return;
            mStrikes.remove(key);
            setPolicyLocked(userId, pkg, POLICY_AUTO, 0);
            scheduleWriteLocked();
            // A new decision: the notice must not be swallowed by the 24 h rate limit.
            mLastNotified.remove(key);
        }
        Slog.w(TAG, "reverted " + pkg + " for user " + userId + " to Auto after "
                + REVERT_STRIKES + " crashes");
        sendUpdateEvent(pkg, userId, 0, null, "reverted");
    }

    // ---------------------------------------------------------------------------------------
    // Binder API
    // ---------------------------------------------------------------------------------------

    @Override
    public int getPolicy(String pkg, int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            return s == null ? POLICY_AUTO : s.policy;
        }
    }

    @Override
    public int getDelayDays(String pkg, int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            return s == null || s.policy != POLICY_DELAY ? 0 : s.delayDays;
        }
    }

    @Override
    public void setPolicy(String pkg, int policy, int delayDays, int userId) {
        userId = enforceAccess(userId);
        if (pkg == null || pkg.isEmpty() || !YasvaraUpdatePolicy.isValid(policy)) {
            throw new IllegalArgumentException("bad policy " + policy + " for " + pkg);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setPolicyLocked(userId, pkg, policy, delayDays);
                scheduleWriteLocked();
            }
            postSyncOwner(pkg);
            Slog.i(TAG, "policy of " + pkg + " for user " + userId + " = " + policy
                    + (policy == POLICY_DELAY ? " (" + delayDays + "d)" : ""));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String[] getManagedPackages(int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            ArrayMap<String, AppState> apps = mUsers.get(userId);
            if (apps == null) return new String[0];
            ArrayList<String> out = new ArrayList<>();
            for (int i = 0; i < apps.size(); i++) {
                if (apps.valueAt(i).policy != POLICY_AUTO) out.add(apps.keyAt(i));
            }
            return out.toArray(new String[0]);
        }
    }

    @Override
    public void allowOnce(String pkg, long versionCode, int userId) {
        userId = enforceAccess(userId);
        final long installedVc = installedVersion(pkg, userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            if (s == null || s.policy != POLICY_NEVER) return;
            if (versionCode == 0) {
                s.allowUntil = 0;
            } else {
                s.allowUntil = System.currentTimeMillis() + YasvaraUpdatePolicy.ALLOW_ONCE_WINDOW_MS;
                s.allowBaseVc = installedVc;
            }
            scheduleWriteLocked();
        }
        // The store must own the app again to offer the update; the expiry takes it back.
        postSyncOwner(pkg);
        if (versionCode != 0) {
            mHandler.postDelayed(() -> syncOwner(pkg),
                    YasvaraUpdatePolicy.ALLOW_ONCE_WINDOW_MS + 1000);
        }
    }

    @Override
    public long getAllowOnceVersion(String pkg, int userId) {
        enforceAccess(userId);
        return 0;
    }

    @Override
    public long getAllowOnceUntil(String pkg, int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            return s == null || s.allowUntil <= System.currentTimeMillis() ? 0 : s.allowUntil;
        }
    }

    @Override
    public void setShownInStore(String pkg, boolean shown, int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            if (s == null || s.policy != POLICY_NEVER || s.shown == shown) return;
            s.shown = shown;
            scheduleWriteLocked();
        }
        postSyncOwner(pkg);
    }

    @Override
    public boolean isShownInStore(String pkg, int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            return s != null && s.policy == POLICY_NEVER && s.shown;
        }
    }

    @Override
    public void skipVersion(String pkg, long versionCode, int userId) {
        userId = enforceAccess(userId);
        if (pkg == null || pkg.isEmpty() || versionCode <= 0) {
            throw new IllegalArgumentException("bad skip " + versionCode + " for " + pkg);
        }
        synchronized (mLock) {
            setPolicyLocked(userId, pkg, POLICY_SKIP, 0);
            stateLocked(userId, pkg, true).skipVc = versionCode;
            scheduleWriteLocked();
        }
        postSyncOwner(pkg);
        Slog.i(TAG, "skipping " + pkg + " " + versionCode + " for user " + userId);
    }

    @Override
    public long getSkippedVersion(String pkg, int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            return s == null || s.policy != POLICY_SKIP ? 0 : s.skipVc;
        }
    }

    @Override
    public String[] getHistory(String pkg, int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            return s == null ? new String[0] : s.history.toArray(new String[0]);
        }
    }

    @Override
    public long getFirstSeen(String pkg, long versionCode, int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            Long t = s == null ? null : s.firstSeen.get(versionCode);
            return t == null ? 0 : t;
        }
    }

    @Override
    public void setVaultEnabled(String pkg, boolean enabled, int userId) {
        userId = enforceAccess(userId);
        if (pkg == null || pkg.isEmpty()) throw new IllegalArgumentException("no package");
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, enabled);
            if (s == null) return;
            s.vault = enabled;
            if (s.isEmpty()) removeStateLocked(userId, pkg);
            scheduleWriteLocked();
        }
    }

    @Override
    public boolean isVaultEnabled(String pkg, int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            return s != null && (s.policy != POLICY_AUTO || s.vault);
        }
    }

    @Override
    public long getPolicySince(String pkg, int userId) {
        userId = enforceAccess(userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            return s == null || s.policy == POLICY_AUTO ? 0 : s.since;
        }
    }

    @Override
    public void clearAppData(String pkg, int userId) {
        userId = enforceAccess(userId);
        final int user = userId;
        final long ident = Binder.clearCallingIdentity();
        try {
            mHandler.post(() -> clearAppDataBlocking(pkg, user));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private int enforceAccess(int userId) {
        mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE, TAG);
        int callingUser = UserHandle.getCallingUserId();
        if (userId == UserHandle.USER_CURRENT || userId == callingUser) return callingUser;
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, TAG);
        return userId;
    }


    // ---------------------------------------------------------------------------------------
    // Version vault
    // ---------------------------------------------------------------------------------------

    /**
     * Saves the installed APK set of pkg before a store replaces it. Called on the install
     * thread for apps that have a rule, once the update is allowed. Never throws: a failed
     * capture must not fail the update.
     */
    public void captureVault(String pkg, int userId, long installedVc, String versionName,
            File codeDir, long newVc) {
        if (pkg == null || codeDir == null || installedVc == newVc) return;
        try {
            File dir = vaultDir(userId, pkg, installedVc);
            if (dir.isDirectory()) return;
            File[] apks = codeDir.listFiles((d, n) -> n.endsWith(".apk"));
            if (apks == null || apks.length == 0) return;
            long need = 0;
            for (File f : apks) need += f.length();
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            if (fs.getAvailableBytes() - need < fs.getTotalBytes() * VAULT_MIN_FREE_PERCENT / 100) {
                Slog.w(TAG, "vault: low storage, not saving " + pkg + " " + installedVc);
                return;
            }
            File tmp = new File(dir.getParentFile(), installedVc + ".tmp");
            deleteRecursive(tmp);
            if (!tmp.mkdirs()) throw new IOException("mkdirs " + tmp);
            for (File f : apks) {
                Files.copy(f.toPath(), new File(tmp, f.getName()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Properties meta = new Properties();
            meta.setProperty("versionName", versionName == null ? "" : versionName);
            meta.setProperty("bytes", Long.toString(need));
            meta.setProperty("savedAt", Long.toString(System.currentTimeMillis()));
            try (FileOutputStream out = new FileOutputStream(new File(tmp, META))) {
                meta.store(out, null);
            }
            if (!tmp.renameTo(dir)) throw new IOException("rename " + tmp);
            Slog.i(TAG, "vault: saved " + pkg + " " + installedVc + " (" + need + " bytes)");
            pruneVault(userId, pkg);
        } catch (Throwable t) {
            Slog.w(TAG, "vault: capture of " + pkg + " failed", t);
        }
    }

    @Override
    public String[] listVault(String pkg, int userId) {
        userId = enforceAccess(userId);
        ArrayList<String> out = new ArrayList<>();
        for (File dir : vaultVersions(userId, pkg)) {
            Properties meta = readMeta(dir);
            out.add(dir.getName() + "|" + meta.getProperty("versionName", "") + "|"
                    + meta.getProperty("bytes", "0") + "|" + meta.getProperty("savedAt", "0"));
        }
        return out.toArray(new String[0]);
    }

    @Override
    public void deleteVault(String pkg, long versionCode, int userId) {
        userId = enforceAccess(userId);
        File appDir = new File(userVaultDir(userId), sanitize(pkg));
        deleteRecursive(versionCode == 0 ? appDir : new File(appDir, Long.toString(versionCode)));
    }

    @Override
    public long getVaultBytes(int userId) {
        userId = enforceAccess(userId);
        return sizeOf(userVaultDir(userId));
    }

    @Override
    public void rollback(String pkg, long versionCode, boolean clearData, boolean pin, int userId,
            IntentSender statusReceiver) {
        userId = enforceAccess(userId);
        final File dir = vaultDir(userId, pkg, versionCode);
        final File[] apks = dir.listFiles((d, n) -> n.endsWith(".apk"));
        if (apks == null || apks.length == 0) {
            throw new IllegalArgumentException("no saved version " + versionCode + " of " + pkg);
        }
        final int user = userId;
        final long ident = Binder.clearCallingIdentity();
        try {
            Context userContext = mContext.createContextAsUser(UserHandle.of(user), 0);
            String installer = null;
            try {
                installer = userContext.getPackageManager().getInstallSourceInfo(pkg)
                        .getInstallingPackageName();
            } catch (PackageManager.NameNotFoundException e) {
                // Uninstalled since it was saved: install it fresh, attributed to the system.
            }
            if (pin) {
                synchronized (mLock) {
                    setPolicyLocked(user, pkg, POLICY_NEVER, 0);
                    scheduleWriteLocked();
                }
                postSyncOwner(pkg);
            }
            if (clearData) clearAppDataBlocking(pkg, user);
            synchronized (mLock) {
                mRolledBack.put(reviewKey(user, pkg), SystemClock.elapsedRealtime());
            }
            PackageInstaller installerApi = userContext.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(pkg);
            params.setRequestDowngrade(true);
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
            // Keep the store as installer of record: licence and integrity checks ask for it.
            if (installer != null) params.setInstallerPackageName(installer);
            int sessionId = installerApi.createSession(params);
            try (PackageInstaller.Session session = installerApi.openSession(sessionId)) {
                for (File apk : apks) {
                    try (FileInputStream in = new FileInputStream(apk);
                            java.io.OutputStream out = session.openWrite(apk.getName(), 0,
                                    apk.length())) {
                        android.os.FileUtils.copy(in, out);
                        session.fsync(out);
                    }
                }
                session.commit(statusReceiver != null ? statusReceiver
                        : PendingIntent.getBroadcast(mContext, 0,
                                new Intent(ACTION_ROLLBACK_DONE).setPackage(mContext.getPackageName()),
                                // commit() fills in the status extras, so it must be mutable.
                                PendingIntent.FLAG_MUTABLE).getIntentSender());
            }
            synchronized (mLock) {
                AppState state = stateLocked(user, pkg, true);
                addHistory(state, System.currentTimeMillis() + "|0|" + versionCode
                        + "|android|rolledback");
                scheduleWriteLocked();
            }
            Slog.i(TAG, "rollback of " + pkg + " to " + versionCode + " for user " + user
                    + " (clearData=" + clearData + ", pin=" + pin + ")");
        } catch (Exception e) {
            Slog.e(TAG, "rollback of " + pkg + " failed", e);
            throw new IllegalStateException("rollback failed: " + e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void clearAppDataBlocking(String pkg, int userId) {
        final CountDownLatch done = new CountDownLatch(1);
        try {
            ActivityManager.getService().clearApplicationUserData(pkg, false,
                    new IPackageDataObserver.Stub() {
                        @Override
                        public void onRemoveCompleted(String packageName, boolean succeeded) {
                            done.countDown();
                        }
                    }, userId);
            done.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Slog.w(TAG, "clearing data of " + pkg + " failed", e);
        }
    }

    private static File userVaultDir(int userId) {
        return new File(VAULT_ROOT, Integer.toString(userId));
    }

    private static File vaultDir(int userId, String pkg, long versionCode) {
        return new File(new File(userVaultDir(userId), sanitize(pkg)), Long.toString(versionCode));
    }

    /** Saved versions of pkg, newest first. */
    private static List<File> vaultVersions(int userId, String pkg) {
        File[] dirs = new File(userVaultDir(userId), sanitize(pkg)).listFiles(
                f -> f.isDirectory() && !f.getName().endsWith(".tmp"));
        ArrayList<File> out = new ArrayList<>();
        if (dirs == null) return out;
        Collections.addAll(out, dirs);
        out.sort((a, b) -> Long.compare(savedAt(b), savedAt(a)));
        return out;
    }

    /** Keeps VAULT_KEEP_PER_APP versions per app and VAULT_MAX_BYTES per user, oldest first. */
    private static void pruneVault(int userId, String pkg) {
        List<File> versions = vaultVersions(userId, pkg);
        for (int i = VAULT_KEEP_PER_APP; i < versions.size(); i++) {
            deleteRecursive(versions.get(i));
        }
        File root = userVaultDir(userId);
        long total = sizeOf(root);
        if (total <= VAULT_MAX_BYTES) return;
        ArrayList<File> all = new ArrayList<>();
        File[] apps = root.listFiles(File::isDirectory);
        if (apps != null) {
            for (File app : apps) all.addAll(vaultVersions(userId, app.getName()));
        }
        all.sort((a, b) -> Long.compare(savedAt(a), savedAt(b)));
        for (File f : all) {
            if (total <= VAULT_MAX_BYTES) break;
            total -= sizeOf(f);
            deleteRecursive(f);
        }
    }

    /** Drops vaults of apps uninstalled for more than VAULT_ORPHAN_MS. */
    private void pruneOrphanVaults() {
        File[] users = VAULT_ROOT.listFiles(File::isDirectory);
        if (users == null) return;
        long now = System.currentTimeMillis();
        for (File userDir : users) {
            int userId;
            try {
                userId = Integer.parseInt(userDir.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            File[] apps = userDir.listFiles(File::isDirectory);
            if (apps == null) continue;
            for (File app : apps) {
                if (isInstalled(app.getName(), userId)) continue;
                if (now - app.lastModified() > VAULT_ORPHAN_MS) deleteRecursive(app);
            }
        }
    }

    private boolean isInstalled(String pkg, int userId) {
        try {
            mContext.getPackageManager().getPackageInfoAsUser(pkg, 0, userId);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static long savedAt(File dir) {
        try {
            return Long.parseLong(readMeta(dir).getProperty("savedAt", "0"));
        } catch (NumberFormatException e) {
            return dir.lastModified();
        }
    }

    private static Properties readMeta(File dir) {
        Properties meta = new Properties();
        try (FileInputStream in = new FileInputStream(new File(dir, META))) {
            meta.load(in);
        } catch (IOException e) {
            // Missing meta: the directory name still gives the version code.
        }
        return meta;
    }

    /** Package names are already safe path segments; this only guards against "..". */
    private static String sanitize(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.contains("/") || pkg.startsWith(".")) {
            throw new IllegalArgumentException("bad package " + pkg);
        }
        return pkg;
    }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        File[] children = f.listFiles();
        long total = 0;
        if (children != null) for (File c : children) total += sizeOf(c);
        return total;
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }

    // ---------------------------------------------------------------------------------------
    // Update review
    // ---------------------------------------------------------------------------------------

    /**
     * Works out what an update waiting for the owner's confirmation would change, from the
     * staged base APK. Kept in memory only; replaced by the next update of the same app.
     */
    public void recordReview(String pkg, int userId, String installer, File stagedBaseApk,
            long stagedBytes) {
        try {
            PackageManager pm = mContext.getPackageManager();
            int flags = PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNING_CERTIFICATES;
            PackageInfo next = pm.getPackageArchiveInfo(stagedBaseApk.getPath(), flags);
            PackageInfo cur = pm.getPackageInfoAsUser(pkg, flags, userId);
            if (next == null || cur == null) return;
            ArrayList<String> out = new ArrayList<>();
            synchronized (mLock) {
                if (mPausedAsks.remove(reviewKey(userId, pkg))) out.add("paused");
            }
            out.add("version|" + nz(cur.versionName) + "|" + nz(next.versionName) + "|"
                    + cur.getLongVersionCode() + "|" + next.getLongVersionCode());
            long installedBytes = 0;
            File[] installedApks = new File(cur.applicationInfo.sourceDir).getParentFile()
                    .listFiles((d, n) -> n.endsWith(".apk"));
            if (installedApks != null) for (File f : installedApks) installedBytes += f.length();
            out.add("size|" + installedBytes + "|" + stagedBytes);
            if (cur.applicationInfo.targetSdkVersion != next.applicationInfo.targetSdkVersion) {
                out.add("target|" + cur.applicationInfo.targetSdkVersion + "|"
                        + next.applicationInfo.targetSdkVersion);
            }
            out.add("signer|" + (sameSigner(cur, next) ? "same" : "changed"));
            ArraySet<String> before = toSet(cur.requestedPermissions);
            ArraySet<String> after = toSet(next.requestedPermissions);
            for (String perm : after) {
                if (before.contains(perm)) continue;
                out.add("perm+|" + perm + "|" + (isDangerous(pm, perm) ? "dangerous" : "normal"));
            }
            for (String perm : before) {
                if (!after.contains(perm)) out.add("perm-|" + perm);
            }
            out.add("installer|" + nz(installer));
            synchronized (mLock) {
                mReviews.put(reviewKey(userId, pkg), out.toArray(new String[0]));
            }
        } catch (Throwable t) {
            Slog.w(TAG, "review of " + pkg + " failed", t);
        }
    }

    @Override
    public String[] getReview(String pkg, int userId) {
        if (mContext.checkCallingOrSelfPermission(PERMISSION_MANAGE)
                != PackageManager.PERMISSION_GRANTED) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INSTALL_PACKAGES, TAG);
        }
        int callingUser = UserHandle.getCallingUserId();
        if (userId == UserHandle.USER_CURRENT) userId = callingUser;
        if (userId != callingUser) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, TAG);
        }
        synchronized (mLock) {
            String[] review = mReviews.get(reviewKey(userId, pkg));
            return review == null ? new String[0] : review;
        }
    }

    private static String reviewKey(int userId, String pkg) {
        return userId + ":" + pkg;
    }

    private static boolean sameSigner(PackageInfo cur, PackageInfo next) {
        if (cur.signingInfo == null || next.signingInfo == null) return false;
        Signature[] now = cur.signingInfo.getApkContentsSigners();
        Signature[] then = next.signingInfo.getSigningCertificateHistory();
        if (then == null) then = next.signingInfo.getApkContentsSigners();
        if (now == null || then == null) return false;
        for (Signature a : now) {
            for (Signature b : then) {
                if (a.equals(b)) return true;
            }
        }
        return false;
    }

    private static boolean isDangerous(PackageManager pm, String perm) {
        try {
            PermissionInfo info = pm.getPermissionInfo(perm, 0);
            return info.getProtection() == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static ArraySet<String> toSet(String[] values) {
        ArraySet<String> set = new ArraySet<>();
        if (values != null) Collections.addAll(set, values);
        return set;
    }

    private static String nz(String s) {
        return s == null ? "" : s.replace('|', '/');
    }

    // ---------------------------------------------------------------------------------------
    // Owner notifications, posted by Laras
    // ---------------------------------------------------------------------------------------

    private void sendUpdateEvent(String pkg, int userId, long versionCode, String installer,
            String decision) {
        final String key = reviewKey(userId, pkg);
        final long now = System.currentTimeMillis();
        synchronized (mLock) {
            Long last = mLastNotified.get(key);
            if (last != null && now - last < NOTIFY_INTERVAL_MS) return;
            mLastNotified.put(key, now);
        }
        try {
            Intent intent = new Intent(YasvaraUpdatePolicy.ACTION_UPDATE_EVENT)
                    .setPackage(YasvaraUpdatePolicy.LARAS_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(YasvaraUpdatePolicy.EXTRA_PACKAGE, pkg)
                    .putExtra(YasvaraUpdatePolicy.EXTRA_VERSION_CODE, versionCode)
                    .putExtra(YasvaraUpdatePolicy.EXTRA_INSTALLER, installer)
                    .putExtra(YasvaraUpdatePolicy.EXTRA_DECISION, decision);
            mContext.sendBroadcastAsUser(intent, UserHandle.of(userId), PERMISSION_MANAGE);
        } catch (Throwable t) {
            Slog.w(TAG, "update event for " + pkg + " not sent", t);
        }
    }

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------

    private AppState stateLocked(int userId, String pkg, boolean create) {
        if (pkg == null) return null;
        ArrayMap<String, AppState> apps = mUsers.get(userId);
        if (apps == null) {
            if (!create) return null;
            apps = new ArrayMap<>();
            mUsers.put(userId, apps);
        }
        AppState s = apps.get(pkg);
        if (s == null && create) {
            s = new AppState();
            apps.put(pkg, s);
        }
        return s;
    }

    private void removeStateLocked(int userId, String pkg) {
        ArrayMap<String, AppState> apps = mUsers.get(userId);
        if (apps == null) return;
        apps.remove(pkg);
        if (apps.isEmpty()) mUsers.remove(userId);
    }

    private void setPolicyLocked(int userId, String pkg, int policy, int delayDays) {
        AppState old = stateLocked(userId, pkg, false);
        if (policy == POLICY_AUTO) {
            // Back to stock: drop history and first-seen records too; only the vault opt-in stays.
            boolean vault = old != null && old.vault;
            removeStateLocked(userId, pkg);
            if (vault) stateLocked(userId, pkg, true).vault = true;
            return;
        }
        AppState s = stateLocked(userId, pkg, true);
        if (s.policy != policy) {
            s.since = System.currentTimeMillis();
            s.reminded = false;
        }
        s.policy = policy;
        s.delayDays = policy == POLICY_DELAY ? YasvaraUpdatePolicy.clampDelay(delayDays) : 0;
        if (policy != POLICY_NEVER) {
            s.allowUntil = 0;
            s.shown = false;
        }
        if (policy != POLICY_SKIP) s.skipVc = 0;
        if (s.isEmpty()) removeStateLocked(userId, pkg);
    }

    // ---------------------------------------------------------------------------------------
    // Update ownership: hiding paused apps from stores
    // ---------------------------------------------------------------------------------------

    /** True when some user wants pkg hidden from its store right now. */
    private boolean wantsLarasOwnerLocked(String pkg) {
        if (hideBySpoof()) return false;
        final long now = System.currentTimeMillis();
        for (int u = 0; u < mUsers.size(); u++) {
            AppState s = mUsers.valueAt(u).get(pkg);
            if (s != null && s.policy == POLICY_NEVER && !s.shown && s.allowUntil <= now) {
                return true;
            }
        }
        return false;
    }

    private void postSyncOwner(String pkg) {
        mHandler.post(() -> syncOwner(pkg));
    }

    private void syncAllOwners() {
        final ArraySet<String> pkgs = new ArraySet<>();
        synchronized (mLock) {
            pkgs.addAll(mSavedOwners.keySet());
            for (int u = 0; u < mUsers.size(); u++) pkgs.addAll(mUsers.valueAt(u).keySet());
        }
        for (String pkg : pkgs) syncOwner(pkg);
    }

    /**
     * Makes the update owner of pkg match its rules: Laras while it is hidden from its store,
     * otherwise whatever owner it had before. Runs on mHandler.
     */
    private void syncOwner(String pkg) {
        final String current;
        try {
            current = mContext.getPackageManager().getInstallSourceInfo(pkg)
                    .getUpdateOwnerPackageName();
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
            return;
        }
        final String laras = YasvaraUpdatePolicy.LARAS_PACKAGE;
        final boolean larasInstalled = isInstalled(laras, UserHandle.USER_SYSTEM);
        final String target;
        synchronized (mLock) {
            if (wantsLarasOwnerLocked(pkg)) {
                if (laras.equals(current) || !larasInstalled) return;
                if (!mSavedOwners.containsKey(pkg)) {
                    mSavedOwners.put(pkg, current == null ? "" : current);
                }
                target = laras;
            } else {
                String saved = mSavedOwners.remove(pkg);
                // Only undo our own change; an owner someone else set since stays.
                if (saved == null || !laras.equals(current)) {
                    if (saved != null) scheduleWriteLocked();
                    return;
                }
                target = saved.isEmpty() ? null : saved;
            }
            scheduleWriteLocked();
        }
        PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        pm.commitPackageStateMutation(null, pkg, state -> state.setUpdateOwner(target));
        pm.writeSettings(true);
        Slog.i(TAG, "update owner of " + pkg + ": " + current + " -> " + target);
    }

    private long installedVersion(String pkg, int userId) {
        try {
            return mContext.getPackageManager().getPackageInfoAsUser(pkg, 0, userId)
                    .getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** An update of pkg landed: end its Allow once grant or Skip rule, and re-hide it. */
    private void onPackageReplaced(String pkg, int userId) {
        final long vc = installedVersion(pkg, userId);
        synchronized (mLock) {
            AppState s = stateLocked(userId, pkg, false);
            if (s == null) return;
            if (s.allowUntil != 0 && vc > s.allowBaseVc) {
                s.allowUntil = 0;
                scheduleWriteLocked();
            }
            if (s.policy == POLICY_SKIP && vc > s.skipVc) {
                setPolicyLocked(userId, pkg, POLICY_AUTO, 0);
                scheduleWriteLocked();
            }
        }
        // A store update by a non-owner drops the owner, so this also takes ownership back.
        syncOwner(pkg);
    }

    private static void trimFirstSeen(AppState s) {
        Iterator<Long> it = s.firstSeen.keySet().iterator();
        while (s.firstSeen.size() > MAX_FIRST_SEEN && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static void addHistory(AppState s, String line) {
        s.history.addFirst(line);
        while (s.history.size() > MAX_HISTORY) s.history.removeLast();
    }

    private static String decisionName(int policy, int decision) {
        switch (decision) {
            case DECISION_DENY:
                return policy == POLICY_DELAY ? "deferred"
                        : policy == POLICY_SKIP ? "skipped" : "blocked";
            case DECISION_ASK: return "ask";
            default: return policy == POLICY_ASK ? "approved" : "allowed";
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    private void registerPackageReceivers() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getData() == null) return;
                String pkg = intent.getData().getSchemeSpecificPart();
                int userId = getSendingUserId();
                if (Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction())) {
                    onPackageReplaced(pkg, userId);
                    return;
                }
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                // The vault outlives the app for VAULT_ORPHAN_MS; start that clock now.
                File vault = new File(userVaultDir(userId), pkg);
                if (vault.isDirectory()) vault.setLastModified(System.currentTimeMillis());
                final boolean goneEverywhere = !isInstalled(pkg, UserHandle.USER_SYSTEM);
                synchronized (mLock) {
                    if (stateLocked(userId, pkg, false) == null) return;
                    removeStateLocked(userId, pkg);
                    if (goneEverywhere) mSavedOwners.remove(pkg);
                    scheduleWriteLocked();
                }
            }
        }, UserHandle.ALL, filter, null, mHandler);
    }

    /** Moves the v0 Settings.Secure detach list (user 0) into POLICY_NEVER rules, once. */
    private void migrateLegacyDetachList() {
        try {
            String raw = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    YasvaraSuperUser.KEY_APP_DETACH, UserHandle.USER_SYSTEM);
            if (raw == null || raw.trim().isEmpty()) return;
            int moved = 0;
            synchronized (mLock) {
                for (String line : raw.split("[,\n]")) {
                    String pkg = line.trim();
                    if (pkg.isEmpty()) continue;
                    AppState s = stateLocked(UserHandle.USER_SYSTEM, pkg, false);
                    if (s != null && s.policy != POLICY_AUTO) continue;
                    setPolicyLocked(UserHandle.USER_SYSTEM, pkg, POLICY_NEVER, 0);
                    moved++;
                }
                scheduleWriteLocked();
            }
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    YasvaraSuperUser.KEY_APP_DETACH, null, UserHandle.USER_SYSTEM);
            Slog.i(TAG, "migrated " + moved + " detached packages from the v0 list");
        } catch (Throwable t) {
            Slog.w(TAG, "legacy detach migration failed", t);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------

    private void scheduleWriteLocked() {
        // Every rule change ends here, so the spoof view stays current.
        rebuildSpoofedLocked();
        mHandler.removeCallbacks(mWriteRunnable);
        mHandler.postDelayed(mWriteRunnable, WRITE_DELAY_MS);
    }

    private void writeNow() {
        FileOutputStream out = null;
        try {
            FILE.getParentFile().mkdirs();
            out = mFile.startWrite();
            TypedXmlSerializer xml = Xml.resolveSerializer(out);
            xml.startDocument(null, true);
            xml.startTag(null, "update-policy");
            xml.attributeInt(null, "version", FILE_VERSION);
            synchronized (mLock) {
                for (int u = 0; u < mUsers.size(); u++) {
                    xml.startTag(null, "user");
                    xml.attributeInt(null, "id", mUsers.keyAt(u));
                    ArrayMap<String, AppState> apps = mUsers.valueAt(u);
                    for (int i = 0; i < apps.size(); i++) {
                        writeApp(xml, apps.keyAt(i), apps.valueAt(i));
                    }
                    xml.endTag(null, "user");
                }
                for (int i = 0; i < mSavedOwners.size(); i++) {
                    xml.startTag(null, "owner");
                    xml.attribute(null, "pkg", mSavedOwners.keyAt(i));
                    xml.attribute(null, "was", mSavedOwners.valueAt(i));
                    xml.endTag(null, "owner");
                }
            }
            xml.endTag(null, "update-policy");
            xml.endDocument();
            mFile.finishWrite(out);
        } catch (Throwable t) {
            Slog.e(TAG, "write failed", t);
            if (out != null) mFile.failWrite(out);
        }
    }

    private static void writeApp(TypedXmlSerializer xml, String pkg, AppState s)
            throws java.io.IOException {
        xml.startTag(null, "app");
        xml.attribute(null, "pkg", pkg);
        xml.attributeInt(null, "policy", s.policy);
        if (s.delayDays != 0) xml.attributeInt(null, "delay", s.delayDays);
        if (s.skipVc != 0) xml.attributeLong(null, "skip", s.skipVc);
        if (s.shown) xml.attributeBoolean(null, "shown", true);
        if (s.allowUntil != 0) {
            xml.attributeLong(null, "allowUntil", s.allowUntil);
            xml.attributeLong(null, "allowBase", s.allowBaseVc);
        }
        if (s.vault) xml.attributeBoolean(null, "vault", true);
        if (s.since != 0) xml.attributeLong(null, "since", s.since);
        if (s.reminded) xml.attributeBoolean(null, "reminded", true);
        for (Map.Entry<Long, Long> e : s.firstSeen.entrySet()) {
            xml.startTag(null, "seen");
            xml.attributeLong(null, "vc", e.getKey());
            xml.attributeLong(null, "at", e.getValue());
            xml.endTag(null, "seen");
        }
        Iterator<String> it = s.history.descendingIterator();
        while (it.hasNext()) {
            xml.startTag(null, "event");
            xml.attribute(null, "v", it.next());
            xml.endTag(null, "event");
        }
        xml.endTag(null, "app");
    }

    private void readLocked() {
        if (!mFile.exists()) return;
        try (FileInputStream in = mFile.openRead()) {
            TypedXmlPullParser xml = Xml.resolvePullParser(in);
            int userId = UserHandle.USER_SYSTEM;
            AppState app = null;
            int type;
            while ((type = xml.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.END_TAG && "app".equals(xml.getName())) {
                    app = null;
                    continue;
                }
                if (type != XmlPullParser.START_TAG) continue;
                switch (xml.getName()) {
                    case "user":
                        userId = xml.getAttributeInt(null, "id", UserHandle.USER_SYSTEM);
                        break;
                    case "app": {
                        String pkg = xml.getAttributeValue(null, "pkg");
                        if (pkg == null) break;
                        app = stateLocked(userId, pkg, true);
                        int policy = xml.getAttributeInt(null, "policy", POLICY_AUTO);
                        app.policy = YasvaraUpdatePolicy.isValid(policy) ? policy : POLICY_AUTO;
                        app.delayDays = app.policy == POLICY_DELAY
                                ? YasvaraUpdatePolicy.clampDelay(
                                        xml.getAttributeInt(null, "delay",
                                                YasvaraUpdatePolicy.DEFAULT_DELAY_DAYS))
                                : 0;
                        app.skipVc = app.policy == POLICY_SKIP
                                ? xml.getAttributeLong(null, "skip", 0) : 0;
                        if (app.policy == POLICY_SKIP && app.skipVc <= 0) app.policy = POLICY_AUTO;
                        app.shown = app.policy == POLICY_NEVER
                                && xml.getAttributeBoolean(null, "shown", false);
                        app.allowUntil = xml.getAttributeLong(null, "allowUntil", 0);
                        app.allowBaseVc = xml.getAttributeLong(null, "allowBase", 0);
                        app.vault = xml.getAttributeBoolean(null, "vault", false);
                        // Rules from before "since" existed count from this boot.
                        app.since = app.policy == POLICY_AUTO ? 0
                                : xml.getAttributeLong(null, "since", System.currentTimeMillis());
                        app.reminded = xml.getAttributeBoolean(null, "reminded", false);
                        break;
                    }
                    case "owner": {
                        String pkg = xml.getAttributeValue(null, "pkg");
                        String was = xml.getAttributeValue(null, "was");
                        if (pkg != null) mSavedOwners.put(pkg, was == null ? "" : was);
                        break;
                    }
                    case "seen":
                        if (app != null) {
                            app.firstSeen.put(xml.getAttributeLong(null, "vc", 0),
                                    xml.getAttributeLong(null, "at", 0));
                        }
                        break;
                    case "event":
                        if (app != null) {
                            String v = xml.getAttributeValue(null, "v");
                            if (v != null) addHistory(app, v);
                        }
                        break;
                }
            }
        } catch (Throwable t) {
            // Fail open: a damaged file must never stop apps from updating.
            Slog.e(TAG, "read failed, starting with every app on Auto", t);
            mUsers.clear();
            // Saved owners stay: syncAllOwners hands those apps back to their store.
        }
    }
}
