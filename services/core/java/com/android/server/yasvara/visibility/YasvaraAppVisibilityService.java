/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.visibility;

import android.app.ActivityManager;
import android.app.BroadcastStickyCache;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.UserHandle;
import android.security.yasvara.YasvaraTabirPolicy;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.yasvara.kait.ModuleLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Unified Framework Tabir control plane and persistence service.
 *
 * <p>Maintains global lists, quick surface concealment switches, reusable HMA-OSS-style Blacklist
 * and Whitelist templates, and per-observer rules, compiling them into an immutable lock-free
 * {@link YasvaraTabirPolicy} snapshot on every rule or package change.
 */
public final class YasvaraAppVisibilityService extends IYasvaraAppVisibility.Stub {
    public static final String SERVICE_NAME = "yasvara_app_visibility";

    private static final String TAG = "YasvaraAppVisibility";
    private static final String PERMISSION = "yasvara.permission.MANAGE_APP_VISIBILITY";
    private static final File FILE = new File("/data/system/yasvara/app_visibility.xml");
    private static final String META_XPOSED = "xposedmodule";

    private static final String TEMPLATE_DEFAULT_WHITELIST = "Perbankan Ketat (Whitelist)";
    private static final String TEMPLATE_DEFAULT_ROOT_BLACKLIST = "Alat Root & Hook";

    private static final ArraySet<String> KNOWN_ROOT_TOOLING_PACKAGES = new ArraySet<>(
            Arrays.asList(
                    "com.topjohnwu.magisk",
                    "io.github.vvb2060.magisk",
                    "io.github.huskydg.magisk",
                    "me.weishu.kernelsu",
                    "com.rifsxd.ksunext",
                    "com.sukisu.ultra",
                    "me.bmax.apatch",
                    "me.garfieldhan.apatch.next",
                    "org.lsposed.manager",
                    "de.robv.android.xposed.installer",
                    "org.meowcat.edxposed.manager",
                    "com.tsng.hidemyapplist",
                    "org.frknkrc44.hma_oss",
                    "com.termux",
                    "com.omarea.vtools",
                    "bin.mt.plus",
                    "bin.mt.plus.canary",
                    "rikka.appops",
                    "moe.shizuku.privileged.api",
                    "io.github.muntashirakon.AppManager",
                    "eu.chainfire.supersu",
                    "com.keramidas.TitaniumBackup",
                    "stericson.busybox"
            ));

    private static final class TemplateRule {
        int mode;
        final ArraySet<String> packages = new ArraySet<>();

        TemplateRule(int mode) {
            this.mode = mode;
        }
    }

    private static final class ObserverRule {
        int mode;
        final ArraySet<String> packages = new ArraySet<>();
        final ArraySet<String> templates = new ArraySet<>();

        ObserverRule(int mode) {
            this.mode = mode;
        }
    }

    private final Context mContext;
    private final Handler mHandler = BackgroundThread.getHandler();
    private final AtomicFile mFile = new AtomicFile(FILE);
    private final Object mLock = new Object();

    // Guarded by mLock.
    private int mScope = SCOPE_SELECTED;
    private boolean mHideXposed = true;
    private boolean mHideRomPackages = true;
    private boolean mHideRootTooling = true;
    private int mDevOptionsHideMode = DEV_HIDE_ALL_NON_SYSTEM;

    private final ArraySet<String> mHidden = new ArraySet<>();
    private final ArraySet<String> mObservers = new ArraySet<>();
    private final ArraySet<String> mExceptions = new ArraySet<>();
    private final ArrayMap<String, TemplateRule> mTemplates = new ArrayMap<>();
    private final ArrayMap<String, ObserverRule> mObserverRules = new ArrayMap<>();

    private List<String> mModules = new ArrayList<>();
    private List<String> mDetectedRomPackages = new ArrayList<>();
    private List<String> mDetectedRootPackages = new ArrayList<>();
    private final ArraySet<String> mForceStoppableHiddenPackages = new ArraySet<>();

    /** APK path to whether it holds module code, so a rebuild reopens only changed APKs. */
    private final ArrayMap<String, Boolean> mModuleApks = new ArrayMap<>();

    private final Runnable mRebuildRunnable = this::rebuild;
    private final Runnable mWriteRunnable = this::writeNow;

    public YasvaraAppVisibilityService(Context context) {
        mContext = context;
        synchronized (mLock) {
            if (mFile.exists()) {
                readLocked();
            } else {
                seedDefaultTemplatesLocked();
            }
        }
        rebuild();
        registerReceivers();
    }

    private void seedDefaultTemplatesLocked() {
        TemplateRule strictWhitelist = new TemplateRule(MODE_WHITELIST);
        mTemplates.put(TEMPLATE_DEFAULT_WHITELIST, strictWhitelist);

        TemplateRule rootBlacklist = new TemplateRule(MODE_BLACKLIST);
        rootBlacklist.packages.addAll(KNOWN_ROOT_TOOLING_PACKAGES);
        mTemplates.put(TEMPLATE_DEFAULT_ROOT_BLACKLIST, rootBlacklist);
    }

    public static boolean isHideableRomPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || "android".equals(pkg)) {
            return false;
        }
        return pkg.startsWith("org.lineageos.")
                || pkg.equals("lineageos.platform")
                || pkg.startsWith("lineageos.platform.")
                || pkg.startsWith("com.android.settings.lineage")
                || pkg.startsWith("org.yasvara.")
                || pkg.startsWith("org.protonaosp.")
                || pkg.startsWith("org.omnirom.")
                || pkg.startsWith("com.crdroid.")
                || pkg.startsWith("co.aospa.")
                || pkg.equals("io.chaldeaprjkt.gamespace");
    }

    @Override
    public int getScope() {
        enforce();
        synchronized (mLock) {
            return mScope;
        }
    }

    @Override
    public void setScope(int scope) {
        enforce();
        if (scope != SCOPE_SELECTED && scope != SCOPE_ALL_USER_APPS) {
            throw new IllegalArgumentException("scope " + scope);
        }
        synchronized (mLock) {
            mScope = scope;
        }
        changed();
    }

    @Override
    public boolean getHideXposed() {
        enforce();
        synchronized (mLock) {
            return mHideXposed;
        }
    }

    @Override
    public void setHideXposed(boolean hide) {
        enforce();
        synchronized (mLock) {
            mHideXposed = hide;
        }
        changed();
    }

    @Override
    public boolean getHideRomPackages() {
        enforce();
        synchronized (mLock) {
            return mHideRomPackages;
        }
    }

    @Override
    public void setHideRomPackages(boolean hide) {
        enforce();
        synchronized (mLock) {
            mHideRomPackages = hide;
        }
        changed();
    }

    @Override
    public boolean getHideRootTooling() {
        enforce();
        synchronized (mLock) {
            return mHideRootTooling;
        }
    }

    @Override
    public void setHideRootTooling(boolean hide) {
        enforce();
        synchronized (mLock) {
            mHideRootTooling = hide;
        }
        changed();
    }

    @Override
    public int getDevOptionsHideMode() {
        enforce();
        synchronized (mLock) {
            return mDevOptionsHideMode;
        }
    }

    @Override
    public void setDevOptionsHideMode(int mode) {
        enforce();
        if (mode != DEV_HIDE_OFF
                && mode != DEV_HIDE_OBSERVERS
                && mode != DEV_HIDE_ALL_NON_SYSTEM) {
            throw new IllegalArgumentException("devOptionsHideMode " + mode);
        }
        synchronized (mLock) {
            mDevOptionsHideMode = mode;
        }
        changed();
    }

    @Override
    public String[] getList(int list) {
        enforce();
        synchronized (mLock) {
            return listLocked(list).toArray(new String[0]);
        }
    }

    @Override
    public void setListed(int list, String[] packages, boolean listed) {
        enforce();
        if (packages == null) return;
        synchronized (mLock) {
            ArraySet<String> target = listLocked(list);
            for (String pkg : packages) {
                if (pkg == null || pkg.isEmpty()) continue;
                if (listed) target.add(pkg);
                else target.remove(pkg);
            }
        }
        changed();
    }

    @Override
    public String[] getXposedModules() {
        enforce();
        refreshRebuildUnderSystemIdentity();
        synchronized (mLock) {
            return mModules.toArray(new String[0]);
        }
    }

    @Override
    public String[] getDetectedRomPackages() {
        enforce();
        refreshRebuildUnderSystemIdentity();
        synchronized (mLock) {
            return mDetectedRomPackages.toArray(new String[0]);
        }
    }

    @Override
    public String[] getDetectedRootPackages() {
        enforce();
        refreshRebuildUnderSystemIdentity();
        synchronized (mLock) {
            return mDetectedRootPackages.toArray(new String[0]);
        }
    }

    @Override
    public String[] getTemplateNames() {
        enforce();
        synchronized (mLock) {
            String[] names = new String[mTemplates.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = mTemplates.keyAt(i);
            }
            Arrays.sort(names);
            return names;
        }
    }

    @Override
    public int getTemplateMode(String name) {
        enforce();
        if (name == null) return MODE_BLACKLIST;
        synchronized (mLock) {
            TemplateRule tpl = mTemplates.get(name);
            return tpl != null ? tpl.mode : MODE_BLACKLIST;
        }
    }

    @Override
    public String[] getTemplatePackages(String name) {
        enforce();
        if (name == null) return new String[0];
        synchronized (mLock) {
            TemplateRule tpl = mTemplates.get(name);
            return tpl != null ? tpl.packages.toArray(new String[0]) : new String[0];
        }
    }

    @Override
    public void putTemplate(String name, int mode, String[] packages) {
        enforce();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Template name must not be empty");
        }
        if (mode != MODE_BLACKLIST && mode != MODE_WHITELIST) {
            throw new IllegalArgumentException("Invalid template mode: " + mode);
        }
        final String cleanName = name.trim();
        synchronized (mLock) {
            TemplateRule tpl = new TemplateRule(mode);
            if (packages != null) {
                for (String pkg : packages) {
                    if (pkg != null && !pkg.isEmpty()) {
                        tpl.packages.add(pkg);
                    }
                }
            }
            mTemplates.put(cleanName, tpl);
        }
        changed();
    }

    @Override
    public void deleteTemplate(String name) {
        enforce();
        if (name == null) return;
        synchronized (mLock) {
            mTemplates.remove(name);
            for (int i = 0; i < mObserverRules.size(); i++) {
                mObserverRules.valueAt(i).templates.remove(name);
            }
        }
        changed();
    }

    @Override
    public String[] getConfiguredObservers() {
        enforce();
        synchronized (mLock) {
            String[] out = new String[mObserverRules.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = mObserverRules.keyAt(i);
            }
            Arrays.sort(out);
            return out;
        }
    }

    @Override
    public int getObserverMode(String observer) {
        enforce();
        if (observer == null) return MODE_GLOBAL;
        synchronized (mLock) {
            ObserverRule rule = mObserverRules.get(observer);
            return rule != null ? rule.mode : MODE_GLOBAL;
        }
    }

    @Override
    public String[] getObserverPackages(String observer) {
        enforce();
        if (observer == null) return new String[0];
        synchronized (mLock) {
            ObserverRule rule = mObserverRules.get(observer);
            return rule != null ? rule.packages.toArray(new String[0]) : new String[0];
        }
    }

    @Override
    public String[] getObserverTemplates(String observer) {
        enforce();
        if (observer == null) return new String[0];
        synchronized (mLock) {
            ObserverRule rule = mObserverRules.get(observer);
            return rule != null ? rule.templates.toArray(new String[0]) : new String[0];
        }
    }

    @Override
    public void setObserverConfig(String observer, int mode, String[] packages,
            String[] templates) {
        enforce();
        if (observer == null || observer.isEmpty()) {
            throw new IllegalArgumentException("Observer package must not be empty");
        }
        if (mode != MODE_GLOBAL && mode != MODE_BLACKLIST && mode != MODE_WHITELIST) {
            throw new IllegalArgumentException("Invalid observer mode: " + mode);
        }
        synchronized (mLock) {
            if (mode == MODE_GLOBAL) {
                mObserverRules.remove(observer);
            } else {
                ObserverRule rule = new ObserverRule(mode);
                if (packages != null) {
                    for (String pkg : packages) {
                        if (pkg != null && !pkg.isEmpty()) {
                            rule.packages.add(pkg);
                        }
                    }
                }
                if (templates != null) {
                    for (String tpl : templates) {
                        if (tpl != null && !tpl.isEmpty()) {
                            rule.templates.add(tpl);
                        }
                    }
                }
                mObserverRules.put(observer, rule);
            }
        }
        changed();
    }

    @Override
    public void clearObserverConfig(String observer) {
        enforce();
        if (observer == null) return;
        synchronized (mLock) {
            mObserverRules.remove(observer);
        }
        changed();
    }

    @Override
    public boolean isHiddenFrom(String target, String observer) {
        enforce();
        if (target == null || observer == null) return false;
        final long ident = Binder.clearCallingIdentity();
        try {
            PackageManager pm = mContext.getPackageManager();
            int targetAppId = UserHandle.getAppId(
                    pm.getApplicationInfo(target, PackageManager.MATCH_ANY_USER).uid);
            int observerAppId = UserHandle.getAppId(
                    pm.getApplicationInfo(observer, PackageManager.MATCH_ANY_USER).uid);
            return AppVisibilityPolicy.shouldHide(observerAppId, targetAppId, target);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean isDevOptionsHiddenFrom(String observer) {
        enforce();
        if (observer == null) return false;
        final long ident = Binder.clearCallingIdentity();
        try {
            PackageManager pm = mContext.getPackageManager();
            int observerAppId = UserHandle.getAppId(
                    pm.getApplicationInfo(observer, PackageManager.MATCH_ANY_USER).uid);
            return AppVisibilityPolicy.shouldConcealDevSettings(observerAppId);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int forceStopHiddenTargets() {
        enforce();
        refreshRebuildUnderSystemIdentity();
        final ArrayList<String> targets;
        synchronized (mLock) {
            targets = new ArrayList<>(mForceStoppableHiddenPackages);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            ActivityManager am = mContext.getSystemService(ActivityManager.class);
            if (am == null) return 0;
            int stopped = 0;
            for (int i = 0; i < targets.size(); i++) {
                String pkg = targets.get(i);
                try {
                    am.forceStopPackage(pkg);
                    stopped++;
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Failed to force-stop " + pkg, e);
                }
            }
            return stopped;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String exportConfigJson() {
        enforce();
        synchronized (mLock) {
            try {
                JSONObject root = new JSONObject();
                root.put("version", 1);
                root.put("scope", mScope);
                root.put("hideXposed", mHideXposed);
                root.put("hideRomPackages", mHideRomPackages);
                root.put("hideRootTooling", mHideRootTooling);
                root.put("devOptionsHideMode", mDevOptionsHideMode);
                root.put("hidden", toJsonArray(mHidden));
                root.put("observers", toJsonArray(mObservers));
                root.put("exceptions", toJsonArray(mExceptions));

                JSONObject templatesObj = new JSONObject();
                for (int i = 0; i < mTemplates.size(); i++) {
                    TemplateRule tpl = mTemplates.valueAt(i);
                    JSONObject tplObj = new JSONObject();
                    tplObj.put("mode", tpl.mode);
                    tplObj.put("packages", toJsonArray(tpl.packages));
                    templatesObj.put(mTemplates.keyAt(i), tplObj);
                }
                root.put("templates", templatesObj);

                JSONObject rulesObj = new JSONObject();
                for (int i = 0; i < mObserverRules.size(); i++) {
                    ObserverRule rule = mObserverRules.valueAt(i);
                    JSONObject ruleObj = new JSONObject();
                    ruleObj.put("mode", rule.mode);
                    ruleObj.put("packages", toJsonArray(rule.packages));
                    ruleObj.put("templates", toJsonArray(rule.templates));
                    rulesObj.put(mObserverRules.keyAt(i), ruleObj);
                }
                root.put("observerRules", rulesObj);
                return root.toString(2);
            } catch (JSONException e) {
                Slog.e(TAG, "exportConfigJson failed", e);
                return "{}";
            }
        }
    }

    @Override
    public boolean importConfigJson(String json) {
        enforce();
        if (json == null || json.trim().isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(json);
            int scope = root.optInt("scope", SCOPE_SELECTED);
            if (scope != SCOPE_SELECTED && scope != SCOPE_ALL_USER_APPS) {
                return false;
            }
            int devMode = root.optInt("devOptionsHideMode", DEV_HIDE_ALL_NON_SYSTEM);
            if (devMode != DEV_HIDE_OFF
                    && devMode != DEV_HIDE_OBSERVERS
                    && devMode != DEV_HIDE_ALL_NON_SYSTEM) {
                return false;
            }
            boolean hideXposed = root.optBoolean("hideXposed", true);
            boolean hideRom = root.optBoolean("hideRomPackages", true);
            boolean hideRoot = root.optBoolean("hideRootTooling", true);

            ArraySet<String> nextHidden = fromJsonArray(root.optJSONArray("hidden"));
            ArraySet<String> nextObservers = fromJsonArray(root.optJSONArray("observers"));
            ArraySet<String> nextExceptions = fromJsonArray(root.optJSONArray("exceptions"));

            ArrayMap<String, TemplateRule> nextTemplates = new ArrayMap<>();
            JSONObject templatesObj = root.optJSONObject("templates");
            if (templatesObj != null) {
                Iterator<String> keys = templatesObj.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    if (name == null || name.trim().isEmpty()) continue;
                    JSONObject tplObj = templatesObj.optJSONObject(name);
                    if (tplObj == null) continue;
                    int tplMode = tplObj.optInt("mode", MODE_BLACKLIST);
                    if (tplMode != MODE_BLACKLIST && tplMode != MODE_WHITELIST) continue;
                    TemplateRule tpl = new TemplateRule(tplMode);
                    tpl.packages.addAll(fromJsonArray(tplObj.optJSONArray("packages")));
                    nextTemplates.put(name.trim(), tpl);
                }
            }

            ArrayMap<String, ObserverRule> nextRules = new ArrayMap<>();
            JSONObject rulesObj = root.optJSONObject("observerRules");
            if (rulesObj != null) {
                Iterator<String> keys = rulesObj.keys();
                while (keys.hasNext()) {
                    String obsPkg = keys.next();
                    if (obsPkg == null || obsPkg.isEmpty()) continue;
                    JSONObject ruleObj = rulesObj.optJSONObject(obsPkg);
                    if (ruleObj == null) continue;
                    int ruleMode = ruleObj.optInt("mode", MODE_GLOBAL);
                    if (ruleMode != MODE_BLACKLIST && ruleMode != MODE_WHITELIST) continue;
                    ObserverRule rule = new ObserverRule(ruleMode);
                    rule.packages.addAll(fromJsonArray(ruleObj.optJSONArray("packages")));
                    rule.templates.addAll(fromJsonArray(ruleObj.optJSONArray("templates")));
                    nextRules.put(obsPkg, rule);
                }
            }

            synchronized (mLock) {
                mScope = scope;
                mHideXposed = hideXposed;
                mHideRomPackages = hideRom;
                mHideRootTooling = hideRoot;
                mDevOptionsHideMode = devMode;

                mHidden.clear();
                mHidden.addAll(nextHidden);
                mObservers.clear();
                mObservers.addAll(nextObservers);
                mExceptions.clear();
                mExceptions.addAll(nextExceptions);

                mTemplates.clear();
                mTemplates.putAll(nextTemplates);
                mObserverRules.clear();
                mObserverRules.putAll(nextRules);
            }
            changed();
            return true;
        } catch (JSONException e) {
            Slog.w(TAG, "importConfigJson invalid JSON", e);
            return false;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        synchronized (mLock) {
            pw.println("scope=" + mScope
                    + " hideXposed=" + mHideXposed
                    + " hideRom=" + mHideRomPackages
                    + " hideRoot=" + mHideRootTooling
                    + " devHideMode=" + mDevOptionsHideMode);
            pw.println("hidden=" + mHidden);
            pw.println("observers=" + mObservers);
            pw.println("exceptions=" + mExceptions);
            pw.println("modules=" + mModules);
            pw.println("detectedRom=" + mDetectedRomPackages);
            pw.println("detectedRoot=" + mDetectedRootPackages);
            pw.println("templates=" + mTemplates.keySet());
            pw.println("observerRules=" + mObserverRules.keySet());
        }
    }

    private void enforce() {
        mContext.enforceCallingOrSelfPermission(PERMISSION, TAG);
    }

    private void refreshRebuildUnderSystemIdentity() {
        final long ident = Binder.clearCallingIdentity();
        try {
            rebuild();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private ArraySet<String> listLocked(int list) {
        switch (list) {
            case LIST_HIDDEN:
                return mHidden;
            case LIST_OBSERVERS:
                return mObservers;
            case LIST_EXCEPTIONS:
                return mExceptions;
            default:
                throw new IllegalArgumentException("list " + list);
        }
    }

    private void changed() {
        refreshRebuildUnderSystemIdentity();
        mHandler.post(mWriteRunnable);
    }

    private void registerReceivers() {
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        BroadcastReceiver onPackage = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                scheduleRebuild();
            }
        };
        mContext.registerReceiverAsUser(onPackage, UserHandle.ALL, packages, null, mHandler);

        // The role service is not up yet this early in boot.
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mContext.unregisterReceiver(this);
                RoleManager roles = mContext.getSystemService(RoleManager.class);
                if (roles != null) {
                    roles.addOnRoleHoldersChangedListenerAsUser(mHandler::post, (role, user) -> {
                        if (RoleManager.ROLE_HOME.equals(role)) scheduleRebuild();
                    }, UserHandle.ALL);
                }
                scheduleRebuild();
            }
        }, UserHandle.SYSTEM, new IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED), null, mHandler);
    }

    private void scheduleRebuild() {
        mHandler.removeCallbacks(mRebuildRunnable);
        mHandler.post(mRebuildRunnable);
    }

    private void rebuild() {
        synchronized (mLock) {
            try {
                rebuildLocked();
            } catch (RuntimeException e) {
                // Keep the rules in force: a failed rebuild must not reveal or hide anything new.
                Slog.e(TAG, "rebuild failed", e);
            }
        }
    }

    private void rebuildLocked() {
        PackageManager pm = mContext.getPackageManager();
        List<PackageInfo> installed = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(
                PackageManager.GET_META_DATA | PackageManager.MATCH_ANY_USER));
        ArrayList<ResolveInfo> homes = new ArrayList<>();
        ComponentName home = pm.getHomeActivities(homes);
        final String homePkg = home != null ? home.getPackageName() : null;

        ArrayMap<String, Integer> pkgToAppId = new ArrayMap<>(installed.size());
        ArrayMap<String, Boolean> pkgIsSystem = new ArrayMap<>(installed.size());

        ArraySet<Integer> exemptCallers = new ArraySet<>();
        ArraySet<Integer> protectedSystemAppIds = new ArraySet<>();
        ArraySet<String> modulesSet = new ArraySet<>();
        ArrayList<String> detectedRom = new ArrayList<>();
        ArrayList<String> detectedRoot = new ArrayList<>();
        ArraySet<String> apks = new ArraySet<>();

        // Pass 1: classify every installed package, exempt callers, and non-ROM system appIds.
        for (PackageInfo info : installed) {
            ApplicationInfo app = info.applicationInfo;
            if (app == null) continue;
            final String pkg = info.packageName;
            if (pkg == null || pkg.isEmpty()) continue;
            final int appId = UserHandle.getAppId(app.uid);
            final boolean sys = app.isSystemApp()
                    || (info.sharedUserId != null && isSharedWithSystem(pm, info));

            pkgToAppId.put(pkg, appId);
            pkgIsSystem.put(pkg, sys);

            final boolean hideableRom = isHideableRomPackage(pkg);
            if (hideableRom) {
                detectedRom.add(pkg);
            }

            if (sys) {
                exemptCallers.add(appId);
                if (!hideableRom && appId >= YasvaraTabirPolicy.FIRST_APPLICATION_UID) {
                    protectedSystemAppIds.add(appId);
                }
                continue;
            }

            if (pkg.equals(homePkg)) {
                exemptCallers.add(appId);
                if (appId >= YasvaraTabirPolicy.FIRST_APPLICATION_UID) {
                    protectedSystemAppIds.add(appId);
                }
            }

            if (app.sourceDir != null) {
                apks.add(app.sourceDir);
            }
            if (isModule(app)) {
                modulesSet.add(pkg);
            }
            if (KNOWN_ROOT_TOOLING_PACKAGES.contains(pkg)) {
                detectedRoot.add(pkg);
            }
        }

        mModuleApks.retainAll(apks);
        ArrayList<String> sortedModules = new ArrayList<>(modulesSet);
        Collections.sort(sortedModules);
        Collections.sort(detectedRom);
        Collections.sort(detectedRoot);
        mModules = sortedModules;
        mDetectedRomPackages = detectedRom;
        mDetectedRootPackages = detectedRoot;

        // Pass 2: build global hidden sets, force-stoppable set, and global caller sets.
        ArraySet<Integer> globalHiddenAppIds = new ArraySet<>();
        ArraySet<String> globalHiddenRomPackages = new ArraySet<>();
        ArraySet<Integer> observerCallers = new ArraySet<>();
        ArraySet<Integer> allowedCallers = new ArraySet<>(exemptCallers);
        mForceStoppableHiddenPackages.clear();

        for (int i = 0; i < pkgToAppId.size(); i++) {
            final String pkg = pkgToAppId.keyAt(i);
            final int appId = pkgToAppId.valueAt(i);
            final boolean sys = pkgIsSystem.valueAt(i);
            if ("android".equals(pkg) || pkg.equals(homePkg)) {
                continue;
            }

            final boolean hideableRom = isHideableRomPackage(pkg);
            if (sys) {
                if ((mHideRomPackages && hideableRom)
                        || (hideableRom && mHidden.contains(pkg))) {
                    globalHiddenRomPackages.add(pkg);
                    if (appId >= YasvaraTabirPolicy.FIRST_APPLICATION_UID
                            && !protectedSystemAppIds.contains(appId)) {
                        globalHiddenAppIds.add(appId);
                    }
                }
                continue;
            }

            boolean hideUserPkg = mHidden.contains(pkg)
                    || (mHideXposed && modulesSet.contains(pkg))
                    || (mHideRootTooling && KNOWN_ROOT_TOOLING_PACKAGES.contains(pkg))
                    || (mHideRomPackages && hideableRom);
            if (hideUserPkg) {
                globalHiddenAppIds.add(appId);
                if (hideableRom) {
                    globalHiddenRomPackages.add(pkg);
                }
                if (!pkg.startsWith("org.yasvara.")) {
                    mForceStoppableHiddenPackages.add(pkg);
                }
            }
            if (mObservers.contains(pkg)) {
                observerCallers.add(appId);
            }
            if (mExceptions.contains(pkg)) {
                allowedCallers.add(appId);
            }
        }
        observerCallers.removeAll(exemptCallers);

        // Pass 3: compile per-observer overrides (Blacklist / Whitelist + Templates).
        ArrayMap<Integer, Integer> overrideModeByCaller = new ArrayMap<>();
        ArrayMap<Integer, ArraySet<Integer>> overrideAppIdsByCaller = new ArrayMap<>();
        ArrayMap<Integer, ArraySet<String>> overrideRomByCaller = new ArrayMap<>();

        for (int i = 0; i < mObserverRules.size(); i++) {
            final String obsPkg = mObserverRules.keyAt(i);
            final ObserverRule rule = mObserverRules.valueAt(i);
            if (rule.mode != MODE_BLACKLIST && rule.mode != MODE_WHITELIST) {
                continue;
            }
            Integer callerObj = pkgToAppId.get(obsPkg);
            if (callerObj == null) continue;
            final int callerAppId = callerObj;
            if (callerAppId < YasvaraTabirPolicy.FIRST_APPLICATION_UID
                    || exemptCallers.contains(callerAppId)) {
                continue;
            }

            ArraySet<String> effectivePkgs = new ArraySet<>(rule.packages);
            for (int t = 0; t < rule.templates.size(); t++) {
                TemplateRule tpl = mTemplates.get(rule.templates.valueAt(t));
                if (tpl != null && tpl.mode == rule.mode) {
                    effectivePkgs.addAll(tpl.packages);
                }
            }

            ArraySet<Integer> targetAppIds = overrideAppIdsByCaller.get(callerAppId);
            ArraySet<String> targetRomPkgs = overrideRomByCaller.get(callerAppId);
            if (targetAppIds == null) {
                targetAppIds = new ArraySet<>();
                targetRomPkgs = new ArraySet<>();
                overrideModeByCaller.put(callerAppId, rule.mode);
                overrideAppIdsByCaller.put(callerAppId, targetAppIds);
                overrideRomByCaller.put(callerAppId, targetRomPkgs);
                if (rule.mode == MODE_BLACKLIST) {
                    targetAppIds.addAll(globalHiddenAppIds);
                    targetRomPkgs.addAll(globalHiddenRomPackages);
                }
            }

            for (int p = 0; p < effectivePkgs.size(); p++) {
                final String targetPkg = effectivePkgs.valueAt(p);
                if ("android".equals(targetPkg)) continue;
                Integer tAppIdObj = pkgToAppId.get(targetPkg);
                if (tAppIdObj == null) continue;
                final int tAppId = tAppIdObj;
                final boolean tSys = Boolean.TRUE.equals(pkgIsSystem.get(targetPkg));
                final boolean tRom = isHideableRomPackage(targetPkg);

                if (rule.mode == MODE_BLACKLIST) {
                    if (targetPkg.equals(homePkg)) continue;
                    if (tSys) {
                        if (tRom) {
                            targetRomPkgs.add(targetPkg);
                            if (tAppId >= YasvaraTabirPolicy.FIRST_APPLICATION_UID
                                    && !protectedSystemAppIds.contains(tAppId)) {
                                targetAppIds.add(tAppId);
                            }
                        }
                    } else {
                        targetAppIds.add(tAppId);
                        if (tRom) targetRomPkgs.add(targetPkg);
                        if (!targetPkg.startsWith("org.yasvara.")) {
                            mForceStoppableHiddenPackages.add(targetPkg);
                        }
                    }
                } else {
                    // MODE_WHITELIST: effectivePkgs are explicitly VISIBLE to this observer.
                    if (tAppId >= YasvaraTabirPolicy.FIRST_APPLICATION_UID) {
                        targetAppIds.add(tAppId);
                    }
                    if (tRom) {
                        targetRomPkgs.add(targetPkg);
                    }
                }
            }
        }

        final int overrideCount = overrideModeByCaller.size();
        int[] overrideCallers = new int[overrideCount];
        int[] overrideModes = new int[overrideCount];
        int[][] overrideAppIds = new int[overrideCount][];
        String[][] overrideRomPkgs = new String[overrideCount][];
        for (int i = 0; i < overrideCount; i++) {
            int caller = overrideModeByCaller.keyAt(i);
            overrideCallers[i] = caller;
            overrideModes[i] = overrideModeByCaller.valueAt(i);
            overrideAppIds[i] = toIntArray(overrideAppIdsByCaller.get(caller));
            overrideRomPkgs[i] = overrideRomByCaller.get(caller).toArray(new String[0]);
        }

        String[] knownHideableRom = mHideRomPackages
                ? detectedRom.toArray(new String[0])
                : new String[0];

        YasvaraTabirPolicy.Builder builder = new YasvaraTabirPolicy.Builder()
                .setExemptCallers(toIntArray(exemptCallers))
                .setProtectedSystemAppIds(toIntArray(protectedSystemAppIds))
                .setKnownHideableRomPackages(knownHideableRom)
                .setObserverOverrides(
                        overrideCallers, overrideModes, overrideAppIds, overrideRomPkgs)
                .setDevHideMode(mDevOptionsHideMode);

        String[] globalRomArr = globalHiddenRomPackages.toArray(new String[0]);
        if (mScope == SCOPE_ALL_USER_APPS) {
            builder.setGlobalAllExcept(
                    toIntArray(globalHiddenAppIds), globalRomArr, toIntArray(allowedCallers));
        } else {
            builder.setGlobalSelected(
                    toIntArray(globalHiddenAppIds), globalRomArr, toIntArray(observerCallers));
        }

        AppVisibilityPolicy.install(builder.build());
        PackageManager.invalidatePackageInfoCache();
        BroadcastStickyCache.invalidateCache(UsbManager.ACTION_USB_STATE);
    }

    private static boolean isSharedWithSystem(PackageManager pm, PackageInfo info) {
        String[] sharing = pm.getPackagesForUid(info.applicationInfo.uid);
        if (sharing == null) return false;
        for (String other : sharing) {
            try {
                if (pm.getApplicationInfo(other, PackageManager.MATCH_ANY_USER).isSystemApp()) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Removed while we looked.
            }
        }
        return false;
    }

    private boolean isModule(ApplicationInfo app) {
        if (app.metaData != null && app.metaData.containsKey(META_XPOSED)) return true;
        if (app.sourceDir == null) return false;
        // sourceDir changes on every update, so an entry never goes stale.
        Boolean known = mModuleApks.get(app.sourceDir);
        if (known == null) {
            known = ModuleLoader.looksLikeModule(app.sourceDir);
            mModuleApks.put(app.sourceDir, known);
        }
        return known;
    }

    private static int[] toIntArray(ArraySet<Integer> values) {
        if (values == null || values.isEmpty()) return new int[0];
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.valueAt(i);
        return out;
    }

    private static JSONArray toJsonArray(ArraySet<String> values) {
        ArrayList<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < sorted.size(); i++) {
            arr.put(sorted.get(i));
        }
        return arr;
    }

    private static ArraySet<String> fromJsonArray(JSONArray arr) {
        ArraySet<String> out = new ArraySet<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (s != null && !s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private void writeNow() {
        FileOutputStream out = null;
        try {
            FILE.getParentFile().mkdirs();
            out = mFile.startWrite();
            TypedXmlSerializer xml = Xml.resolveSerializer(out);
            xml.startDocument(null, true);
            xml.startTag(null, "app-visibility");
            synchronized (mLock) {
                xml.attributeInt(null, "scope", mScope);
                xml.attributeBoolean(null, "hide-xposed", mHideXposed);
                xml.attributeBoolean(null, "hide-rom", mHideRomPackages);
                xml.attributeBoolean(null, "hide-root", mHideRootTooling);
                xml.attributeInt(null, "dev-hide-mode", mDevOptionsHideMode);

                writeList(xml, "hidden", mHidden);
                writeList(xml, "observer", mObservers);
                writeList(xml, "exception", mExceptions);

                for (int i = 0; i < mTemplates.size(); i++) {
                    String name = mTemplates.keyAt(i);
                    TemplateRule tpl = mTemplates.valueAt(i);
                    xml.startTag(null, "template");
                    xml.attribute(null, "name", name);
                    xml.attributeInt(null, "mode", tpl.mode);
                    for (int p = 0; p < tpl.packages.size(); p++) {
                        xml.startTag(null, "pkg");
                        xml.attribute(null, "name", tpl.packages.valueAt(p));
                        xml.endTag(null, "pkg");
                    }
                    xml.endTag(null, "template");
                }

                for (int i = 0; i < mObserverRules.size(); i++) {
                    String obsPkg = mObserverRules.keyAt(i);
                    ObserverRule rule = mObserverRules.valueAt(i);
                    xml.startTag(null, "observer-rule");
                    xml.attribute(null, "pkg", obsPkg);
                    xml.attributeInt(null, "mode", rule.mode);
                    for (int p = 0; p < rule.packages.size(); p++) {
                        xml.startTag(null, "target");
                        xml.attribute(null, "pkg", rule.packages.valueAt(p));
                        xml.endTag(null, "target");
                    }
                    for (int t = 0; t < rule.templates.size(); t++) {
                        xml.startTag(null, "use-template");
                        xml.attribute(null, "name", rule.templates.valueAt(t));
                        xml.endTag(null, "use-template");
                    }
                    xml.endTag(null, "observer-rule");
                }
            }
            xml.endTag(null, "app-visibility");
            xml.endDocument();
            mFile.finishWrite(out);
        } catch (Throwable t) {
            Slog.e(TAG, "write failed", t);
            if (out != null) mFile.failWrite(out);
        }
    }

    private static void writeList(TypedXmlSerializer xml, String tag, ArraySet<String> packages)
            throws IOException {
        for (int i = 0; i < packages.size(); i++) {
            xml.startTag(null, tag);
            xml.attribute(null, "pkg", packages.valueAt(i));
            xml.endTag(null, tag);
        }
    }

    private void readLocked() {
        if (!mFile.exists()) return;
        try (FileInputStream in = mFile.openRead()) {
            TypedXmlPullParser xml = Xml.resolvePullParser(in);
            TemplateRule currentTemplate = null;
            ObserverRule currentObserver = null;
            int type;
            while ((type = xml.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.END_TAG) {
                    String endName = xml.getName();
                    if ("template".equals(endName)) {
                        currentTemplate = null;
                    } else if ("observer-rule".equals(endName)) {
                        currentObserver = null;
                    }
                    continue;
                }
                if (type != XmlPullParser.START_TAG) continue;
                switch (xml.getName()) {
                    case "app-visibility":
                        mScope = xml.getAttributeInt(null, "scope", SCOPE_SELECTED);
                        mHideXposed = xml.getAttributeBoolean(null, "hide-xposed", true);
                        mHideRomPackages = xml.getAttributeBoolean(null, "hide-rom", true);
                        mHideRootTooling = xml.getAttributeBoolean(null, "hide-root", true);
                        mDevOptionsHideMode = xml.getAttributeInt(
                                null, "dev-hide-mode", DEV_HIDE_ALL_NON_SYSTEM);
                        break;
                    case "hidden":
                        addNonEmpty(mHidden, xml.getAttributeValue(null, "pkg"));
                        break;
                    case "observer":
                        addNonEmpty(mObservers, xml.getAttributeValue(null, "pkg"));
                        break;
                    case "exception":
                        addNonEmpty(mExceptions, xml.getAttributeValue(null, "pkg"));
                        break;
                    case "template": {
                        String name = xml.getAttributeValue(null, "name");
                        int mode = xml.getAttributeInt(null, "mode", MODE_BLACKLIST);
                        if (name != null && !name.trim().isEmpty()
                                && (mode == MODE_BLACKLIST || mode == MODE_WHITELIST)) {
                            currentTemplate = new TemplateRule(mode);
                            mTemplates.put(name.trim(), currentTemplate);
                        }
                        break;
                    }
                    case "pkg":
                        if (currentTemplate != null) {
                            addNonEmpty(currentTemplate.packages,
                                    xml.getAttributeValue(null, "name"));
                        }
                        break;
                    case "observer-rule": {
                        String obsPkg = xml.getAttributeValue(null, "pkg");
                        int mode = xml.getAttributeInt(null, "mode", MODE_GLOBAL);
                        if (obsPkg != null && !obsPkg.isEmpty()
                                && (mode == MODE_BLACKLIST || mode == MODE_WHITELIST)) {
                            currentObserver = new ObserverRule(mode);
                            mObserverRules.put(obsPkg, currentObserver);
                        }
                        break;
                    }
                    case "target":
                        if (currentObserver != null) {
                            addNonEmpty(currentObserver.packages,
                                    xml.getAttributeValue(null, "pkg"));
                        }
                        break;
                    case "use-template":
                        if (currentObserver != null) {
                            addNonEmpty(currentObserver.templates,
                                    xml.getAttributeValue(null, "name"));
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "read failed", e);
        }
    }

    private static void addNonEmpty(ArraySet<String> set, String value) {
        if (value != null && !value.isEmpty()) {
            set.add(value);
        }
    }
}
