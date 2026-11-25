/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.swara;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.UidObserver;
import android.app.yasvara.ISwaraListener;
import android.app.yasvara.ISwaraManager;
import android.app.yasvara.Swara;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.AudioSystem;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Swara per-app audio: rules per (user, package), applied per uid.
 * <ul>
 *   <li>Volume, mute and the mic go to AudioFlinger's rule table through
 *       {@code AudioSystem.setParameters("swara_*")} (frameworks/av/services/swara).</li>
 *   <li>The output device becomes a dynamic policy mix: uid AND usage media, game or unknown,
 *       so calls, notifications and alarms keep normal routing.</li>
 * </ul>
 * Everything runs on one handler thread; binder calls only validate and post.
 */
public final class SwaraService extends ISwaraManager.Stub {
    private static final String TAG = "Swara";
    private static final long NOTIFY_DELAY_MS = 150;

    private static final int[] ROUTED_USAGES = {
            AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
    };

    @Nullable
    private static SwaraService sInstance;

    private final Context mContext;
    private final Handler mHandler;
    private final SwaraStore mStore = new SwaraStore();
    private final Object mLock = new Object();
    private final RemoteCallbackList<ISwaraListener> mListeners = new RemoteCallbackList<>();

    /** Loaded rules per user. Guarded by mLock. */
    private final SparseArray<ArrayMap<String, SwaraRule>> mRules = new SparseArray<>();
    /** Uids that have an entry in AudioFlinger's table, so a cleared rule is cleared there. */
    private final ArraySet<Integer> mNativeUids = new ArraySet<>();

    /** Playing and recording uids, from the audio callbacks. Handler thread only. */
    private final ArraySet<Integer> mPlayingUids = new ArraySet<>();
    private final ArraySet<Integer> mRecordingUids = new ArraySet<>();

    @Nullable
    private AudioPolicy mRoutingPolicy;
    /** uid → "type/address" the current routing policy was built for. */
    private final ArrayMap<Integer, String> mRoutes = new ArrayMap<>();
    /** Uids whose chosen device is not connected right now. */
    private final ArraySet<Integer> mUnavailable = new ArraySet<>();

    private final Runnable mNotify = this::notifyListeners;

    public SwaraService(@NonNull Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread("swara", Process.THREAD_PRIORITY_DEFAULT);
        thread.start();
        mHandler = thread.getThreadHandler();
        sInstance = this;
        mHandler.post(this::initWhenAudioReady);
    }

    /** For other system services in system_server (Layang window volume, D6). */
    @Nullable
    public static SwaraService getInstance() {
        return sInstance;
    }

    /**
     * SystemServer starts this before AudioService is published; AudioManager would hold a null
     * service. Wait for "audio", and never let a failure here take system_server down.
     */
    private void initWhenAudioReady() {
        if (android.os.ServiceManager.checkService(Context.AUDIO_SERVICE) == null) {
            mHandler.postDelayed(this::initWhenAudioReady, 500);
            return;
        }
        try {
            init();
        } catch (RuntimeException e) {
            Slog.e(TAG, "Swara init failed; apps play at the stream volume", e);
        }
    }

    private void init() {
        final AudioManager am = mContext.getSystemService(AudioManager.class);
        loadUser(UserHandle.USER_SYSTEM);
        final int current = ActivityManager.getCurrentUser();
        if (current != UserHandle.USER_SYSTEM) loadUser(current);
        applyAllNative();
        updateRouting();

        am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                onPlaybackChanged(configs);
            }
        }, mHandler);
        am.registerAudioRecordingCallback(new AudioManager.AudioRecordingCallback() {
            @Override
            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                onRecordingChanged(configs);
            }
        }, mHandler);
        onPlaybackChanged(am.getActivePlaybackConfigurations());
        onRecordingChanged(am.getActiveRecordingConfigurations());

        // audioserver forgets the table when it restarts (§8).
        am.setAudioServerStateCallback(mHandler::post, new AudioManager.AudioServerStateCallback() {
            @Override
            public void onAudioServerUp() {
                Slog.i(TAG, "audioserver is back; pushing every rule again");
                synchronized (mLock) {
                    mNativeUids.clear();
                }
                applyAllNative();
                mRoutes.clear();
                updateRouting();
            }
        });
        am.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                mRoutes.clear();
                updateRouting();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                mRoutes.clear();
                updateRouting();
            }
        }, mHandler);

        try {
            ActivityManager.getService().registerUidObserver(new UidObserver() {
                @Override
                public void onUidGone(int uid, boolean disabled) {
                    mHandler.post(() -> onUidGoneInternal(uid));
                }
            }, ActivityManager.UID_OBSERVER_GONE, ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "No uid observer; reset when closed will not work", e);
        }

        final IntentFilter pkgs = new IntentFilter();
        pkgs.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        pkgs.addDataScheme("package");
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                onPackageChanged(intent);
            }
        }, UserHandle.ALL, pkgs, null, mHandler);

        final IntentFilter users = new IntentFilter();
        users.addAction(Intent.ACTION_USER_SWITCHED);
        users.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userId < 0) return;
                if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                    synchronized (mLock) {
                        mRules.remove(userId);
                    }
                    mStore.delete(userId);
                } else {
                    loadUser(userId);
                    applyAllNative();
                }
                updateRouting();
            }
        }, UserHandle.ALL, users, null, mHandler);
    }

    // ---------------------------------------------------------------------------------------
    // ISwaraManager
    // ---------------------------------------------------------------------------------------

    @Override
    public Bundle getRules(String packageName, int userId) {
        final int user = enforceAccess(userId);
        final Bundle b = new Bundle();
        synchronized (mLock) {
            final SwaraRule r = rulesFor(user).get(packageName);
            (r != null ? r : new SwaraRule()).writeTo(b);
        }
        return b;
    }

    @Override
    public void setRules(String packageName, int userId, Bundle rules) {
        final int user = enforceAccess(userId);
        if (packageName == null || rules == null) return;
        final Bundle copy = new Bundle(rules);
        mHandler.post(() -> setRulesInternal(packageName, user, copy));
    }

    @Override
    public void resetRules(String packageName, int userId) {
        final int user = enforceAccess(userId);
        if (packageName == null) return;
        mHandler.post(() -> {
            synchronized (mLock) {
                rulesFor(user).remove(packageName);
            }
            onRulesChanged(user, packageName);
        });
    }

    @Override
    public void resetAll(int userId) {
        final int user = enforceAccess(userId);
        mHandler.post(() -> {
            final ArrayList<String> pkgs;
            synchronized (mLock) {
                final ArrayMap<String, SwaraRule> rules = rulesFor(user);
                pkgs = new ArrayList<>(rules.keySet());
                rules.clear();
            }
            for (String pkg : pkgs) applyNative(pkg, user);
            mStore.write(user, new ArrayMap<>());
            updateRouting();
            scheduleNotify();
        });
    }

    @Override
    public List<Bundle> getApps(int userId) {
        final int user = enforceAccess(userId);
        final long ident = Binder.clearCallingIdentity();
        try {
            return snapshotApps(user);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void registerListener(ISwaraListener listener) {
        enforceAccess(UserHandle.getCallingUserId());
        if (listener != null) mListeners.register(listener);
    }

    @Override
    public void unregisterListener(ISwaraListener listener) {
        enforceAccess(UserHandle.getCallingUserId());
        if (listener != null) mListeners.unregister(listener);
    }

    // ---------------------------------------------------------------------------------------
    // Internal API (Layang, shell)
    // ---------------------------------------------------------------------------------------

    /** Layang's window volume (→). */
    public void setAppVolume(@NonNull String packageName, int userId, float volume,
            boolean muted) {
        final Bundle b = new Bundle();
        b.putFloat(Swara.KEY_VOLUME, volume);
        b.putBoolean(Swara.KEY_MUTED, muted);
        mHandler.post(() -> setRulesInternal(packageName, userId, b));
    }

    void setRulesInternal(@NonNull String packageName, int userId, @NonNull Bundle b) {
        synchronized (mLock) {
            final ArrayMap<String, SwaraRule> rules = rulesFor(userId);
            SwaraRule r = rules.get(packageName);
            if (r == null) r = new SwaraRule();
            r.merge(b);
            if (r.isDefault()) {
                rules.remove(packageName);
            } else {
                rules.put(packageName, r);
            }
        }
        onRulesChanged(userId, packageName);
    }

    private void onRulesChanged(int userId, @NonNull String packageName) {
        applyNative(packageName, userId);
        final ArrayMap<String, SwaraRule> copy;
        synchronized (mLock) {
            copy = new ArrayMap<>(rulesFor(userId));
        }
        mStore.write(userId, copy);
        updateRouting();
        scheduleNotify();
    }

    // ---------------------------------------------------------------------------------------
    // Native table
    // ---------------------------------------------------------------------------------------

    private void loadUser(int userId) {
        final ArrayMap<String, SwaraRule> rules = mStore.read(userId);
        synchronized (mLock) {
            mRules.put(userId, rules);
        }
        Slog.i(TAG, "user " + userId + ": " + rules.size() + " rules");
    }

    private void applyAllNative() {
        final ArrayList<int[]> keys = new ArrayList<>();
        final ArrayList<String> pkgs = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mRules.size(); i++) {
                final ArrayMap<String, SwaraRule> rules = mRules.valueAt(i);
                for (int j = 0; j < rules.size(); j++) {
                    keys.add(new int[] {mRules.keyAt(i)});
                    pkgs.add(rules.keyAt(j));
                }
            }
        }
        for (int i = 0; i < pkgs.size(); i++) applyNative(pkgs.get(i), keys.get(i)[0]);
    }

    /** Pushes the package's rule, or clears it, in AudioFlinger. */
    private void applyNative(@NonNull String packageName, int userId) {
        final int uid = uidOf(packageName, userId);
        if (uid < 0) return;
        final SwaraRule r;
        synchronized (mLock) {
            final SwaraRule found = rulesFor(userId).get(packageName);
            r = found != null && found.needsNative() ? found : null;
            if (r != null) {
                mNativeUids.add(uid);
            } else if (!mNativeUids.remove(uid)) {
                return;
            }
        }
        final String kv = r != null ? r.toNative(uid) : "swara_clear=" + uid;
        final int status = AudioSystem.setParameters(kv);
        if (status != AudioSystem.SUCCESS) {
            Slog.w(TAG, "AudioFlinger refused " + kv + ": " + status);
        }
    }

    private void onUidGoneInternal(int uid) {
        final int userId = UserHandle.getUserId(uid);
        final ArrayList<String> reset = new ArrayList<>();
        synchronized (mLock) {
            final ArrayMap<String, SwaraRule> rules = rulesFor(userId);
            for (int i = 0; i < rules.size(); i++) {
                final SwaraRule r = rules.valueAt(i);
                if (r.resetOnClose && (r.volume != 1f || r.muted)
                        && uidOf(rules.keyAt(i), userId) == uid) {
                    r.volume = 1f;
                    r.muted = false;
                    reset.add(rules.keyAt(i));
                }
            }
        }
        for (String pkg : reset) {
            Slog.i(TAG, pkg + " closed; volume reset");
            onRulesChanged(userId, pkg);
        }
    }

    private void onPackageChanged(@NonNull Intent intent) {
        final String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart()
                : null;
        final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
        if (pkg == null || uid < 0) return;
        final int userId = UserHandle.getUserId(uid);
        if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
            final boolean had;
            synchronized (mLock) {
                had = rulesFor(userId).remove(pkg) != null;
                mNativeUids.remove(uid);
            }
            if (had) {
                AudioSystem.setParameters("swara_clear=" + uid);
                onRulesChanged(userId, pkg);
            }
        } else {
            // A reinstall can bring a new uid; the rule follows the package (§8).
            applyNative(pkg, userId);
            updateRouting();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Routing (§3.3)
    // ---------------------------------------------------------------------------------------

    private void updateRouting() {
        final ArrayMap<Integer, String> wanted = new ArrayMap<>();
        synchronized (mLock) {
            for (int i = 0; i < mRules.size(); i++) {
                final int userId = mRules.keyAt(i);
                final ArrayMap<String, SwaraRule> rules = mRules.valueAt(i);
                for (int j = 0; j < rules.size(); j++) {
                    final SwaraRule r = rules.valueAt(j);
                    if (r.outputType == 0) continue;
                    final int uid = uidOf(rules.keyAt(j), userId);
                    if (uid >= 0) wanted.put(uid, r.outputType + "/" + r.outputAddress);
                }
            }
        }
        if (wanted.equals(mRoutes) && (mRoutingPolicy != null || wanted.isEmpty())) return;

        final AudioManager am = mContext.getSystemService(AudioManager.class);
        if (mRoutingPolicy != null) {
            am.unregisterAudioPolicy(mRoutingPolicy);
            mRoutingPolicy = null;
        }
        mRoutes.clear();
        mUnavailable.clear();
        if (wanted.isEmpty()) {
            scheduleNotify();
            return;
        }
        final AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        final AudioPolicy.Builder builder = new AudioPolicy.Builder(mContext);
        boolean any = false;
        for (int i = 0; i < wanted.size(); i++) {
            final int uid = wanted.keyAt(i);
            final String[] want = wanted.valueAt(i).split("/", 2);
            final AudioDeviceInfo device = findDevice(outputs, Integer.parseInt(want[0]),
                    want.length > 1 ? want[1] : "");
            if (device == null) {
                mUnavailable.add(uid);
                continue;
            }
            final AudioMixingRule.Builder rule = new AudioMixingRule.Builder()
                    .addMixRule(AudioMixingRule.RULE_MATCH_UID, uid);
            for (int usage : ROUTED_USAGES) {
                rule.addMixRule(AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE,
                        new AudioAttributes.Builder().setUsage(usage).build());
            }
            try {
                builder.addMix(new AudioMix.Builder(rule.build())
                        .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                        .setDevice(device)
                        .build());
                any = true;
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "No route for uid " + uid, e);
            }
        }
        mRoutes.putAll(wanted);
        if (any) {
            final AudioPolicy policy = builder.build();
            if (am.registerAudioPolicy(policy) == AudioManager.SUCCESS) {
                mRoutingPolicy = policy;
            } else {
                Slog.w(TAG, "Routing policy refused");
            }
        }
        scheduleNotify();
    }

    @Nullable
    private static AudioDeviceInfo findDevice(@NonNull AudioDeviceInfo[] outputs, int type,
            @NonNull String address) {
        AudioDeviceInfo sameType = null;
        for (AudioDeviceInfo d : outputs) {
            if (d.getType() != type) continue;
            if (address.isEmpty() || address.equals(d.getAddress())) return d;
            if (sameType == null) sameType = d;
        }
        // A wired or USB headset can come back under a new address.
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET ? null : sameType;
    }

    // ---------------------------------------------------------------------------------------
    // Live list (§3.4, G6)
    // ---------------------------------------------------------------------------------------

    private void onPlaybackChanged(@NonNull List<AudioPlaybackConfiguration> configs) {
        final ArraySet<Integer> now = new ArraySet<>();
        for (AudioPlaybackConfiguration c : configs) {
            if (c.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
                    && UserHandle.getAppId(c.getClientUid()) >= Process.FIRST_APPLICATION_UID) {
                now.add(c.getClientUid());
            }
        }
        if (!now.equals(mPlayingUids)) {
            mPlayingUids.clear();
            mPlayingUids.addAll(now);
            scheduleNotify();
        }
    }

    private void onRecordingChanged(@NonNull List<AudioRecordingConfiguration> configs) {
        final ArraySet<Integer> now = new ArraySet<>();
        for (AudioRecordingConfiguration c : configs) {
            final int uid;
            try {
                uid = c.getClientUid();
            } catch (SecurityException e) {
                // AudioService hands this service privileged configs (registerRecordingCallback
                // checks OrSelf); an anonymized one still throws here. Never let it reach
                // the handler: uncaught, it kills system_server.
                continue;
            }
            if (UserHandle.getAppId(uid) >= Process.FIRST_APPLICATION_UID) {
                now.add(uid);
            }
        }
        if (!now.equals(mRecordingUids)) {
            mRecordingUids.clear();
            mRecordingUids.addAll(now);
            scheduleNotify();
        }
    }

    @NonNull
    private List<Bundle> snapshotApps(int userId) {
        final ArraySet<Integer> playing = new ArraySet<>();
        final ArraySet<Integer> recording = new ArraySet<>();
        final ArraySet<Integer> unavailable = new ArraySet<>();
        // The sets belong to the handler thread; copy them there.
        final boolean ok = mHandler.runWithScissors(() -> {
            playing.addAll(mPlayingUids);
            recording.addAll(mRecordingUids);
            unavailable.addAll(mUnavailable);
        }, 1000);
        if (!ok) Slog.w(TAG, "getApps: live state timed out");

        final ArrayMap<String, Bundle> apps = new ArrayMap<>();
        final PackageManager pm = mContext.getPackageManager();
        final ArraySet<Integer> live = new ArraySet<>(playing);
        live.addAll(recording);
        for (int uid : live) {
            if (UserHandle.getUserId(uid) != userId) continue;
            final String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs == null || pkgs.length == 0) continue;
            apps.put(pkgs[0], entry(pkgs[0], uid, playing.contains(uid),
                    recording.contains(uid)));
        }
        synchronized (mLock) {
            final ArrayMap<String, SwaraRule> rules = rulesFor(userId);
            for (int i = 0; i < rules.size(); i++) {
                final String pkg = rules.keyAt(i);
                if (!apps.containsKey(pkg)) {
                    apps.put(pkg, entry(pkg, uidOf(pkg, userId), false, false));
                }
            }
            for (int i = 0; i < apps.size(); i++) {
                final Bundle b = apps.valueAt(i);
                final SwaraRule r = rules.get(apps.keyAt(i));
                (r != null ? r : new SwaraRule()).writeTo(b);
                b.putBoolean(Swara.KEY_OUTPUT_AVAILABLE,
                        !unavailable.contains(b.getInt(Swara.KEY_UID)));
            }
        }
        final ArrayList<Bundle> out = new ArrayList<>(apps.values());
        out.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        return out;
    }

    private static int rank(@NonNull Bundle b) {
        if (b.getBoolean(Swara.KEY_PLAYING)) return 0;
        if (b.getBoolean(Swara.KEY_RECORDING)) return 1;
        return 2;
    }

    @NonNull
    private static Bundle entry(@NonNull String pkg, int uid, boolean playing,
            boolean recording) {
        final Bundle b = new Bundle();
        b.putString(Swara.KEY_PACKAGE, pkg);
        b.putInt(Swara.KEY_UID, uid);
        b.putBoolean(Swara.KEY_PLAYING, playing);
        b.putBoolean(Swara.KEY_RECORDING, recording);
        return b;
    }

    private void scheduleNotify() {
        mHandler.removeCallbacks(mNotify);
        mHandler.postDelayed(mNotify, NOTIFY_DELAY_MS);
    }

    private void notifyListeners() {
        final int userId = ActivityManager.getCurrentUser();
        final int n = mListeners.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    mListeners.getBroadcastItem(i).onAppsChanged(userId);
                } catch (RemoteException ignored) {
                    // The callback list drops dead listeners.
                }
            }
        } finally {
            mListeners.finishBroadcast();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Caller holds mLock. Loads the user lazily (a user that was not running at boot). */
    @NonNull
    private ArrayMap<String, SwaraRule> rulesFor(int userId) {
        ArrayMap<String, SwaraRule> rules = mRules.get(userId);
        if (rules == null) {
            rules = mStore.read(userId);
            mRules.put(userId, rules);
        }
        return rules;
    }

    private int uidOf(@NonNull String packageName, int userId) {
        try {
            return mContext.getPackageManager().getPackageUidAsUser(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private int enforceAccess(int userId) {
        mContext.enforceCallingOrSelfPermission(Swara.PERMISSION_MANAGE, TAG);
        final int callingUser = UserHandle.getCallingUserId();
        if (userId == UserHandle.USER_CURRENT) return ActivityManager.getCurrentUser();
        if (userId == callingUser) return callingUser;
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, TAG);
        return userId;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID && uid != Process.SYSTEM_UID) {
            mContext.enforceCallingOrSelfPermission(Swara.PERMISSION_MANAGE, TAG);
        }
        new SwaraShellCommand(this).exec(this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        dumpInternal(pw);
    }

    void dumpInternal(@NonNull PrintWriter pw) {
        pw.println("Swara");
        synchronized (mLock) {
            for (int i = 0; i < mRules.size(); i++) {
                final ArrayMap<String, SwaraRule> rules = mRules.valueAt(i);
                pw.println("  user " + mRules.keyAt(i) + " (" + rules.size() + " rules):");
                for (int j = 0; j < rules.size(); j++) {
                    pw.println("    " + rules.keyAt(j) + " uid="
                            + uidOf(rules.keyAt(j), mRules.keyAt(i)) + " " + rules.valueAt(j));
                }
            }
            pw.println("  native uids: " + mNativeUids);
        }
        mHandler.runWithScissors(() -> {
            pw.println("  playing: " + mPlayingUids + " recording: " + mRecordingUids);
            pw.println("  routes: " + mRoutes + " unavailable: " + mUnavailable
                    + " policy=" + (mRoutingPolicy != null));
        }, 1000);
        pw.println("  AudioFlinger table:");
        final String table = AudioSystem.getParameters("swara_dump");
        for (String line : table.split("\n")) {
            if (!line.isEmpty()) pw.println("    " + line);
        }
    }
}
