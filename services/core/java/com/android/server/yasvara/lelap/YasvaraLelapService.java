/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.lelap;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityTaskManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.TaskStackListener;
import android.app.role.RoleManager;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IDeviceIdleController;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps chosen apps asleep unless the owner has them open or in Recents, and holds the cuts
 * Laras asks for: disabled components and packages, sync authorities, pinned settings, denied
 * jobs, pushes and sensor listeners. The store is the truth; device state follows it at boot,
 * on package and account changes, and whenever something else moves a pinned value.
 *
 * <p>Rules apply to the primary user.
 */
public final class YasvaraLelapService extends IYasvaraLelap.Stub {
    private static final String TAG = "YasvaraLelap";

    public static final String SERVICE_NAME = "yasvara_lelap";
    static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_LELAP";

    static final String STRICT = "strict";
    static final String PUSH = "push";
    static final String JOB = "job";
    static final String SENSOR = "sensor";
    static final String COMPONENT = "component";
    static final String PACKAGE = "package";
    static final String SYNC = "sync";
    static final String SETTING = "setting";
    private static final List<String> KINDS =
            Arrays.asList(STRICT, PUSH, JOB, SENSOR, COMPONENT, PACKAGE, SYNC, SETTING);

    private static final File DIR = new File("/data/system/yasvara");
    private static final File STORE = new File(DIR, "lelap.xml");
    private static final File SENSOR_DENY = new File(DIR, "sensor_deny");
    private static final String SENSOR_GEN_PROP = "sys.yasvara.sensor_deny_gen";
    private static final int USER = UserHandle.USER_SYSTEM;
    private static final int MAX_RULES = 2048;
    private static final int MAX_VALUE_CHARS = 256;
    private static final long RECONCILE_DELAY_MS = 1500;
    private static final int RECENTS_MAX = 100;
    /** Disabling these leaves no way back into Laras. */
    private static final Set<String> NEVER_CUT = Set.of("android", "com.android.systemui",
            "com.android.settings", "com.android.providers.settings", "com.android.phone",
            "org.yasvara.laras");

    private final Context mContext;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArrayMap<String, ArraySet<String>> mRules = new ArrayMap<>();
    /** What a pinned setting held before the pin, keyed by "namespace:key". */
    @GuardedBy("mLock")
    private final ArrayMap<String, String> mSettingBefore = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<String, ContentObserver> mObservers = new ArrayMap<>();
    private volatile boolean mBooted;
    private volatile boolean mUnlocked;

    private final Runnable mReconcile = this::reconcileRecents;

    public YasvaraLelapService(Context context) {
        mContext = context;
        for (String kind : KINDS) mRules.put(kind, new ArraySet<>());
        synchronized (mLock) {
            load();
            publishLocked();
            // Nothing strict was opened in this boot yet.
            for (String pkg : mRules.get(STRICT)) LelapPolicy.sleep(pkg);
        }

        IntentFilter boot = new IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        boot.addAction(Intent.ACTION_BOOT_COMPLETED);
        boot.addAction(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION);
        boot.addAction(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                final String action = intent.getAction();
                if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
                    onBoot();
                } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                    // Widgets are readable only once the user is unlocked.
                    mUnlocked = true;
                    scheduleReconcile();
                    applySync(rules(SYNC), true);
                } else if (PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED.equals(action)) {
                    mHandler.post(() -> pinStrict(false));
                } else {
                    mHandler.post(() -> applySync(rules(SYNC), true));
                }
            }
        }, UserHandle.SYSTEM, boot, null, mHandler);

        IntentFilter pkg = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pkg.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkg.addDataScheme("package");
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                final Uri data = intent.getData();
                if (data == null) return;
                onPackageChanged(data.getSchemeSpecificPart());
            }
        }, UserHandle.SYSTEM, pkg, null, mHandler);
    }

    // Binder

    @Override
    public String[] getRules(String kind) {
        enforce();
        return rules(checkKind(kind)).toArray(new String[0]);
    }

    @Override
    public void setRules(String kind, String[] values) {
        enforce();
        checkKind(kind);
        final ArraySet<String> next = new ArraySet<>();
        if (values != null) {
            for (String v : values) next.add(validate(kind, v));
        }
        final long token = Binder.clearCallingIdentity();
        try {
            final ArraySet<String> added;
            final ArraySet<String> removed;
            synchronized (mLock) {
                final ArraySet<String> old = mRules.get(kind);
                int total = next.size() - old.size();
                for (ArraySet<String> set : mRules.values()) total += set.size();
                if (total > MAX_RULES) throw new IllegalArgumentException("too many rules");
                added = new ArraySet<>(next);
                added.removeAll(old);
                removed = new ArraySet<>(old);
                removed.removeAll(next);
                if (added.isEmpty() && removed.isEmpty()) return;
                if (SETTING.equals(kind)) rememberSettingsLocked(added);
                mRules.put(kind, next);
                save();
                publishLocked();
            }
            apply(kind, added, removed);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public List<String> getEvents() {
        enforce();
        return LelapPolicy.events();
    }

    @Override
    public void clearEvents() {
        enforce();
        LelapPolicy.clearEvents();
    }

    @Override
    public String[] getAsleep() {
        enforce();
        final ArrayList<String> out = new ArrayList<>();
        for (String pkg : rules(STRICT)) {
            if (LelapPolicy.isAsleep(pkg)) out.add(pkg);
        }
        return out.toArray(new String[0]);
    }

    @Override
    public String[] getWidgetKept() {
        enforce();
        final ArrayList<String> out = new ArrayList<>();
        for (String pkg : rules(STRICT)) {
            if (LelapPolicy.isAsleep(pkg) && LelapPolicy.hasWidget(pkg)) out.add(pkg);
        }
        return out.toArray(new String[0]);
    }

    private void enforce() {
        mContext.enforceCallingOrSelfPermission(PERMISSION_MANAGE, TAG);
    }

    private static String checkKind(String kind) {
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("unknown kind " + kind);
        return kind;
    }

    private ArraySet<String> rules(String kind) {
        synchronized (mLock) {
            return new ArraySet<>(mRules.get(kind));
        }
    }

    private String validate(String kind, String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_CHARS
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("bad value");
        }
        switch (kind) {
            case STRICT: {
                final ApplicationInfo info = appInfo(value);
                if (info == null) throw new IllegalArgumentException("not installed: " + value);
                // Privileged and persistent apps carry the platform: push, telephony, the shell.
                if (info.isPrivilegedApp() || (info.flags & ApplicationInfo.FLAG_PERSISTENT) != 0
                        || UserHandle.getAppId(info.uid) < android.os.Process.FIRST_APPLICATION_UID) {
                    throw new IllegalArgumentException("part of the system: " + value);
                }
                if (holdsCoreRole(value)) {
                    throw new IllegalArgumentException("in use by the system: " + value);
                }
                break;
            }
            case PACKAGE:
                if (NEVER_CUT.contains(value)) throw new IllegalArgumentException("refused");
                break;
            case COMPONENT: {
                final ComponentName cn = ComponentName.unflattenFromString(value);
                if (cn == null || NEVER_CUT.contains(cn.getPackageName())) {
                    throw new IllegalArgumentException("refused: " + value);
                }
                break;
            }
            case JOB:
                if (value.indexOf('/') <= 0 || value.endsWith("/")) {
                    throw new IllegalArgumentException("job rule is package/substring");
                }
                break;
            case SETTING:
                if (parseSetting(value) == null) {
                    throw new IllegalArgumentException("setting rule is namespace:key=value");
                }
                break;
            default:
                break;
        }
        return value;
    }

    private boolean holdsCoreRole(String pkg) {
        final RoleManager roles = mContext.getSystemService(RoleManager.class);
        final long token = Binder.clearCallingIdentity();
        try {
            for (String role : new String[] {RoleManager.ROLE_HOME, RoleManager.ROLE_DIALER,
                    RoleManager.ROLE_SMS}) {
                if (roles.getRoleHoldersAsUser(role, UserHandle.SYSTEM).contains(pkg)) return true;
            }
            final String ime = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, USER);
            return ime != null && ime.startsWith(pkg + "/");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private ApplicationInfo appInfo(String pkg) {
        final long token = Binder.clearCallingIdentity();
        try {
            return mContext.getPackageManager().getApplicationInfoAsUser(pkg,
                    PackageManager.MATCH_DISABLED_COMPONENTS, USER);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Policy snapshot

    @GuardedBy("mLock")
    private void publishLocked() {
        final ArraySet<String> strict = new ArraySet<>(mRules.get(STRICT));
        final int[] appIds = new int[strict.size()];
        int n = 0;
        for (String pkg : strict) {
            final ApplicationInfo info = appInfo(pkg);
            if (info != null) appIds[n++] = UserHandle.getAppId(info.uid);
        }
        final ArraySet<String> jobs = mRules.get(JOB);
        final String[][] jobRules = new String[jobs.size()][];
        for (int i = 0; i < jobRules.length; i++) {
            final String rule = jobs.valueAt(i);
            final int slash = rule.indexOf('/');
            jobRules[i] = new String[] {rule.substring(0, slash), rule.substring(slash + 1)};
        }
        LelapPolicy.set(new LelapPolicy(Arrays.copyOf(appIds, n), strict,
                new ArraySet<>(mRules.get(PUSH)), jobRules));
    }

    // Applying rules

    private void apply(String kind, ArraySet<String> added, ArraySet<String> removed) {
        switch (kind) {
            case STRICT:
                for (String pkg : removed) releaseStrict(pkg);
                pinStrict(true);
                mHandler.post(mReconcile);
                break;
            case SENSOR:
                writeSensorDeny();
                break;
            case COMPONENT:
                for (String v : removed) setComponent(v, false);
                for (String v : added) setComponent(v, true);
                break;
            case PACKAGE:
                for (String v : removed) setPackage(v, false);
                for (String v : added) setPackage(v, true);
                break;
            case SYNC:
                applySync(removed, false);
                applySync(added, true);
                break;
            case SETTING:
                for (String v : removed) releaseSetting(v);
                for (String v : added) holdSetting(v);
                break;
            default:
                break;
        }
    }

    private void onBoot() {
        mBooted = true;
        LelapPolicy.setWidgetWindowListener(pkg -> mHandler.postDelayed(
                () -> endWidgetWindow(pkg), LelapPolicy.WIDGET_WINDOW_MS + 500));
        writeSensorDeny();
        for (String v : rules(COMPONENT)) setComponent(v, true);
        for (String v : rules(PACKAGE)) setPackage(v, true);
        for (String v : rules(SETTING)) holdSetting(v);
        pinStrict(true);
        for (String pkg : rules(STRICT)) LelapPolicy.setProviders(pkg, providerComponents(pkg));
        mContext.getSystemService(AppOpsManager.class).startWatchingMode(
                AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, null,
                new AppOpsManager.OnOpChangedInternalListener() {
                    @Override
                    public void onOpChanged(int op, String packageName) {
                        if (LelapPolicy.isStrict(packageName)) {
                            mHandler.post(() -> pinStrict(false));
                        }
                    }
                });
        try {
            ActivityTaskManager.getService().registerTaskStackListener(new TaskStackListener() {
                @Override
                public void onRecentTaskListUpdated() {
                    scheduleReconcile();
                }

                @Override
                public void onTaskRemoved(int taskId) {
                    scheduleReconcile();
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        scheduleReconcile();
    }

    private void onPackageChanged(String pkg) {
        synchronized (mLock) {
            if (mRules.get(STRICT).contains(pkg)) publishLocked();
        }
        if (!mBooted) return;
        for (String v : rules(COMPONENT)) {
            if (v.startsWith(pkg + "/")) setComponent(v, true);
        }
        if (LelapPolicy.isStrict(pkg)) {
            pinStrict(false);
            LelapPolicy.setProviders(pkg, providerComponents(pkg));
        }
    }

    // Strict apps

    /** Restricts every strict package and takes it off the Doze allowlist. */
    private void pinStrict(boolean quiet) {
        if (!mBooted) return;
        final AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        final IDeviceIdleController idle = IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
        List<String> allowlisted = List.of();
        try {
            allowlisted = Arrays.asList(idle.getUserPowerWhitelist());
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Doze allowlist unreadable", e);
        }
        for (String pkg : rules(STRICT)) {
            final ApplicationInfo info = appInfo(pkg);
            if (info == null) continue;
            if (appOps.unsafeCheckOpRawNoThrow(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, info.uid,
                    pkg) != AppOpsManager.MODE_IGNORED) {
                appOps.setMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, info.uid, pkg,
                        AppOpsManager.MODE_IGNORED);
                if (!quiet) LelapPolicy.note("pin", pkg, "background use was re-allowed");
            }
            if (allowlisted.contains(pkg)) {
                try {
                    idle.removePowerSaveWhitelistApp(pkg);
                    if (!quiet) LelapPolicy.note("pin", pkg, "was put on the Doze allowlist");
                } catch (RemoteException | RuntimeException e) {
                    Slog.w(TAG, "Cannot take " + pkg + " off the Doze allowlist", e);
                }
            }
        }
    }

    private void releaseStrict(String pkg) {
        final ApplicationInfo info = appInfo(pkg);
        if (info == null) return;
        mContext.getSystemService(AppOpsManager.class).setMode(
                AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, info.uid, pkg, AppOpsManager.MODE_ALLOWED);
    }

    private void scheduleReconcile() {
        mHandler.removeCallbacks(mReconcile);
        mHandler.postDelayed(mReconcile, RECONCILE_DELAY_MS);
    }

    /** Stops strict packages that are neither in Recents nor in front. */
    private void reconcileRecents() {
        if (!mBooted) return;
        final ArraySet<String> strict = rules(STRICT);
        if (strict.isEmpty()) return;
        final ArraySet<String> recent = new ArraySet<>();
        try {
            for (ActivityManager.RecentTaskInfo task : ActivityTaskManager.getService()
                    .getRecentTasks(RECENTS_MAX, ActivityManager.RECENT_WITH_EXCLUDED, USER)
                    .getList()) {
                addPackage(recent, task.baseIntent.getComponent());
                addPackage(recent, task.realActivity);
                addPackage(recent, task.baseActivity);
                addPackage(recent, task.topActivity);
            }
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Recents unreadable, leaving strict apps alone", e);
            return;
        }
        final ActivityManagerInternal am = LocalServices.getService(ActivityManagerInternal.class);
        for (String pkg : strict) {
            final ApplicationInfo info = appInfo(pkg);
            if (info == null) continue;
            final Set<String> widget = widgetComponents(pkg);
            LelapPolicy.setWidgetComponents(pkg, widget);
            if (LelapPolicy.isAsleep(pkg)
                    && am.getUidProcessState(info.uid) <= ActivityManager.PROCESS_STATE_TOP) {
                // Opened while a widget window had its process alive.
                LelapPolicy.wake(pkg);
            }
            if (LelapPolicy.isAsleep(pkg)) {
                // A widget placed after the app went to sleep, or one that was removed.
                final boolean stopped = (info.flags & ApplicationInfo.FLAG_STOPPED) != 0;
                if (widget != null && stopped) {
                    setStopped(pkg, false);
                } else if (widget == null && !stopped && mUnlocked
                        && am.getUidProcessState(info.uid)
                                > ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
                    stop(pkg);
                }
                continue;
            }
            if (recent.contains(pkg)) continue;
            // A foreground service the owner started (music, a call) outlives its task.
            if (am.getUidProcessState(info.uid)
                    <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
                continue;
            }
            LelapPolicy.sleep(pkg);
            if (widget != null) {
                kill(info.uid);
            } else {
                stop(pkg);
            }
        }
        if (mUnlocked) LelapPolicy.setWidgetsKnown();
    }

    private void stop(String pkg) {
        try {
            ActivityManager.getService().forceStopPackage(pkg, USER);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Cannot stop " + pkg, e);
        }
    }

    /** Ends the processes and leaves the stopped flag alone, which keeps a widget drawn. */
    private void kill(int uid) {
        try {
            ActivityManager.getService().killUid(UserHandle.getAppId(uid), USER, "lelap");
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Cannot kill uid " + uid, e);
        }
    }

    private void setStopped(String pkg, boolean stopped) {
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(pkg, stopped, USER);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Cannot change stopped state of " + pkg, e);
        }
    }

    private void endWidgetWindow(String pkg) {
        if (!LelapPolicy.isAsleep(pkg) || LelapPolicy.inWidgetWindow(pkg)) return;
        final ApplicationInfo info = appInfo(pkg);
        if (info == null) return;
        if (LocalServices.getService(ActivityManagerInternal.class).getUidProcessState(info.uid)
                <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
            return;
        }
        kill(info.uid);
    }

    /**
     * The receivers and services that draw this package's widgets, as "{package/class}", or null
     * when none of its widgets is on a host.
     */
    private Set<String> widgetComponents(String pkg) {
        if (!mUnlocked) return null;
        try {
            if (!mContext.getSystemService(AppWidgetManager.class).isBoundWidgetPackage(pkg,
                    USER)) {
                return null;
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "Widgets of " + pkg + " unreadable", e);
            return null;
        }
        return providerComponents(pkg);
    }

    /** Every receiver and service of the package that can draw a widget, bound or not. */
    private Set<String> providerComponents(String pkg) {
        final ArraySet<String> out = new ArraySet<>();
        final int flags = PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        try {
            final PackageManager pm = mContext.getPackageManager();
            final Intent update = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setPackage(pkg);
            for (ResolveInfo ri : pm.queryBroadcastReceiversAsUser(update, flags, USER)) {
                out.add(ri.activityInfo.getComponentName().toShortString());
            }
            final ServiceInfo[] services = pm.getPackageInfoAsUser(pkg,
                    PackageManager.GET_SERVICES | flags, USER).services;
            if (services != null) {
                for (ServiceInfo si : services) {
                    if (android.Manifest.permission.BIND_REMOTEVIEWS.equals(si.permission)) {
                        out.add(si.getComponentName().toShortString());
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Slog.w(TAG, "Widgets of " + pkg + " unreadable", e);
            return null;
        }
        return out;
    }

    private static void addPackage(ArraySet<String> out, ComponentName cn) {
        if (cn != null) out.add(cn.getPackageName());
    }

    // Components and packages

    private void setComponent(String flat, boolean cut) {
        final ComponentName cn = ComponentName.unflattenFromString(flat);
        if (cn == null) return;
        try {
            mContext.getPackageManager().setComponentEnabledSetting(cn,
                    cut ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            // Not in this version of the package; the rule waits for one that has it.
            Slog.i(TAG, "No component " + flat);
        }
    }

    private void setPackage(String pkg, boolean cut) {
        try {
            mContext.getPackageManager().setApplicationEnabledSetting(pkg,
                    cut ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                            : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0);
        } catch (RuntimeException e) {
            Slog.i(TAG, "No package " + pkg);
        }
    }

    // Sync

    private void applySync(Set<String> authorities, boolean cut) {
        if (authorities.isEmpty() || !mBooted) return;
        final Account[] accounts;
        try {
            accounts = AccountManager.get(mContext).getAccountsAsUser(USER);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Accounts unreadable", e);
            return;
        }
        for (Account account : accounts) {
            for (String authority : authorities) {
                try {
                    if (ContentResolver.getIsSyncableAsUser(account, authority, USER) <= 0) {
                        continue;
                    }
                    if (ContentResolver.getSyncAutomaticallyAsUser(account, authority, USER)
                            == cut) {
                        ContentResolver.setSyncAutomaticallyAsUser(account, authority, !cut,
                                USER);
                    }
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Cannot change sync of " + authority, e);
                }
            }
        }
    }

    // Settings

    /** {namespace, key, value}, or null. The key may itself hold colons. */
    private static String[] parseSetting(String rule) {
        final int colon = rule.indexOf(':');
        final int equals = rule.lastIndexOf('=');
        if (colon <= 0 || equals <= colon + 1) return null;
        final String ns = rule.substring(0, colon);
        if (!ns.equals("global") && !ns.equals("secure") && !ns.equals("system")) return null;
        return new String[] {ns, rule.substring(colon + 1, equals), rule.substring(equals + 1)};
    }

    private String readSetting(String ns, String key) {
        final ContentResolver cr = mContext.getContentResolver();
        switch (ns) {
            case "global": return Settings.Global.getString(cr, key);
            case "secure": return Settings.Secure.getStringForUser(cr, key, USER);
            default: return Settings.System.getStringForUser(cr, key, USER);
        }
    }

    private void writeSetting(String ns, String key, String value) {
        final ContentResolver cr = mContext.getContentResolver();
        switch (ns) {
            case "global": Settings.Global.putString(cr, key, value); break;
            case "secure": Settings.Secure.putStringForUser(cr, key, value, USER); break;
            default: Settings.System.putStringForUser(cr, key, value, USER); break;
        }
    }

    private static Uri settingUri(String ns, String key) {
        switch (ns) {
            case "global": return Settings.Global.getUriFor(key);
            case "secure": return Settings.Secure.getUriFor(key);
            default: return Settings.System.getUriFor(key);
        }
    }

    @GuardedBy("mLock")
    private void rememberSettingsLocked(Set<String> added) {
        for (String rule : added) {
            final String[] s = parseSetting(rule);
            final String id = s[0] + ':' + s[1];
            if (!mSettingBefore.containsKey(id)) mSettingBefore.put(id, readSetting(s[0], s[1]));
        }
    }

    private void holdSetting(String rule) {
        final String[] s = parseSetting(rule);
        if (s == null) return;
        final String id = s[0] + ':' + s[1];
        if (!Objects.equals(readSetting(s[0], s[1]), s[2])) writeSetting(s[0], s[1], s[2]);
        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                final String now = readSetting(s[0], s[1]);
                if (Objects.equals(now, s[2])) return;
                LelapPolicy.note("setting", "", id + " was set to " + now);
                writeSetting(s[0], s[1], s[2]);
            }
        };
        synchronized (mLock) {
            final ContentObserver old = mObservers.put(id, observer);
            if (old != null) mContext.getContentResolver().unregisterContentObserver(old);
        }
        mContext.getContentResolver().registerContentObserver(settingUri(s[0], s[1]), false,
                observer, UserHandle.USER_ALL);
    }

    private void releaseSetting(String rule) {
        final String[] s = parseSetting(rule);
        if (s == null) return;
        final String id = s[0] + ':' + s[1];
        final String before;
        synchronized (mLock) {
            // Still pinned to another value by a rule that replaced this one.
            for (String other : mRules.get(SETTING)) {
                if (other.startsWith(id + '=')) return;
            }
            final ContentObserver observer = mObservers.remove(id);
            if (observer != null) mContext.getContentResolver().unregisterContentObserver(observer);
            before = mSettingBefore.remove(id);
            save();
        }
        writeSetting(s[0], s[1], before);
    }

    // Sensors

    /** SensorService reads the file again when the generation property moves. */
    private void writeSensorDeny() {
        try (FileWriter out = new FileWriter(SENSOR_DENY)) {
            for (String needle : rules(SENSOR)) out.write(needle + "\n");
        } catch (IOException e) {
            Slog.w(TAG, "Cannot write " + SENSOR_DENY, e);
            return;
        }
        SystemProperties.set(SENSOR_GEN_PROP,
                Integer.toString(SystemProperties.getInt(SENSOR_GEN_PROP, 0) + 1));
    }

    // Store

    @GuardedBy("mLock")
    private void save() {
        DIR.mkdirs();
        final AtomicFile file = new AtomicFile(STORE);
        FileOutputStream os = null;
        try {
            os = file.startWrite();
            final TypedXmlSerializer x = Xml.resolveSerializer(os);
            x.startDocument(null, true);
            x.startTag(null, "lelap");
            for (int i = 0; i < mRules.size(); i++) {
                for (String value : mRules.valueAt(i)) {
                    x.startTag(null, "rule");
                    x.attribute(null, "kind", mRules.keyAt(i));
                    x.attribute(null, "value", value);
                    x.endTag(null, "rule");
                }
            }
            for (int i = 0; i < mSettingBefore.size(); i++) {
                x.startTag(null, "before");
                x.attribute(null, "id", mSettingBefore.keyAt(i));
                if (mSettingBefore.valueAt(i) != null) {
                    x.attribute(null, "value", mSettingBefore.valueAt(i));
                }
                x.endTag(null, "before");
            }
            x.endTag(null, "lelap");
            x.endDocument();
            file.finishWrite(os);
        } catch (IOException e) {
            if (os != null) file.failWrite(os);
            Slog.e(TAG, "Cannot save rules", e);
        }
    }

    @GuardedBy("mLock")
    private void load() {
        if (!STORE.exists()) return;
        try (FileInputStream in = new AtomicFile(STORE).openRead()) {
            final TypedXmlPullParser p = Xml.resolvePullParser(in);
            int type;
            while ((type = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) continue;
                if ("rule".equals(p.getName())) {
                    final ArraySet<String> set = mRules.get(p.getAttributeValue(null, "kind"));
                    final String value = p.getAttributeValue(null, "value");
                    if (set != null && value != null) set.add(value);
                } else if ("before".equals(p.getName())) {
                    mSettingBefore.put(p.getAttributeValue(null, "id"),
                            p.getAttributeValue(null, "value"));
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Cannot read rules, starting with none", e);
            for (ArraySet<String> set : mRules.values()) set.clear();
            mSettingBefore.clear();
        }
    }
}
