/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.pentas;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;

/**
 * In-process system_server thermal & charging policy bridge for {@code mi_thermal_engine}.
 *
 * Replaces Pentas's persistent ART process ({@code ThermalController}) so {@code org.yasvara.pentas}
 * runs at 0 KB while idle. All heavy thermal/charging control loops remain in the Rust
 * {@code mi_thermal_engine} daemon; this bridge only translates user Settings changes and
 * foreground-app transitions into properties and sysfs switches.
 *
 * Silently stays inactive on devices without {@code /sys/class/thermal/thermal_message/sconfig}.
 */
final class ThermalPolicyBridge {

    private static final String TAG = "YasvaraThermal";

    // ── Settings.System keys (shared with Pentas & SystemUI QS tiles) ───────
    private static final String KEY_THERMAL_PROFILE = "mithermal_thermal_profile";
    private static final String KEY_TEMP_LIMIT = "mithermal_temp_limit";
    private static final String KEY_TEMP_OFFSET = "mithermal_temp_offset";
    private static final String KEY_CHARGE_MAX_WATT = "mithermal_charge_max_watt";
    private static final String KEY_CHARGE_MIN_WATT = "mithermal_charge_min_watt";
    private static final String KEY_APP_PROFILES = "mithermal_app_profiles";
    private static final String KEY_CUSTOM_PROFILES = "mithermal_custom_profiles";
    private static final String KEY_BYPASS_CHARGING = "mithermal_bypass_charging";
    private static final String KEY_HBM_ENABLED = "hbm_force_enabled";
    private static final String KEY_HBM_TIMEOUT = "hbm_timeout_minutes";
    private static final String KEY_DC_DIMMING = "dc_dimming_enabled";

    private static final String KEY_CHG_PROFILES = "mithermal_charging_profiles";
    private static final String KEY_CHG_GLOBAL_PROFILE = "mithermal_global_charging_profile";
    private static final String KEY_CHG_APP_PROFILES = "mithermal_charging_app_profiles";

    // ── mi_thermal_engine properties ────────────────────────────────────────
    private static final String PROP_TEMP_LIMIT = "persist.sys.mithermal.temp_limit";
    private static final String PROP_TEMP_OFFSET = "persist.sys.mithermal.temp_offset";
    private static final String PROP_MAX_FCC = "persist.sys.mithermal.max_fcc";
    private static final String PROP_MIN_FCC = "persist.sys.mithermal.min_fcc";
    private static final String PROP_SS_PROFILE = "persist.sys.mithermal.ss_profile";

    private static final String PROP_CUSTOM_SEQ = "persist.sys.mithermal.custom.seq";
    private static final String PROP_CUSTOM_COUNT = "persist.sys.mithermal.custom.n";
    private static final String PROP_CUSTOM_PREFIX = "persist.sys.mithermal.custom.";
    private static final String PROP_CUSTOM_GPU = "persist.sys.mithermal.custom.gpu";
    private static final String PROP_CUSTOM_MON = "persist.sys.mithermal.custom.mon";
    private static final String PROP_CUSTOM_SIC = "persist.sys.mithermal.custom.sic";

    private static final String PROP_CHG_MODE = "persist.sys.mithermal.charging_profile";
    private static final String PROP_CHG_SEQ = "persist.sys.mithermal.charging.seq";
    private static final String PROP_CHG_COUNT = "persist.sys.mithermal.charging.n";
    private static final String PROP_CHG_PREFIX = "persist.sys.mithermal.charging.";

    private static final int PROP_CHUNK_SIZE = 88;
    private static final int PROP_CHUNK_MAX = 64;
    private static final int CUSTOM_PROFILE_BASE = 1000;
    private static final int MAX_CLUSTERS = 4;
    private static final int CUSTOM_GPU_MAX = 4;
    private static final int PROFILE_PERFORMANCE = 2;
    private static final int PROFILE_BATTERY_SAVER = 16;

    private static final int MIN_WATT = 5;
    private static final int DEFAULT_MAX_WATT = 100;
    private static final int DEFAULT_TEMP_LIMIT_DC = 380;
    private static final long FCC_HARD_MIN_UA = 100_000L;
    private static final long FCC_HARD_MAX_UA = 25_000_000L;
    private static final double UA_PER_WATT = 222_000.0;
    private static final long POLICY_FCC_DEFAULT_UA = 20_000_000L;

    // ── Sysfs / proc nodes ──────────────────────────────────────────────────
    private static final String NODE_SCONFIG = "/sys/class/thermal/thermal_message/sconfig";
    private static final String NODE_THERMAL_BOOST = "/sys/class/thermal/thermal_message/boost";
    private static final String NODE_SCREEN_STATE =
            "/sys/class/thermal/thermal_message/screen_state";
    private static final String NODE_POWERSAVE_MODE =
            "/sys/class/thermal/power_save/powersave_mode";
    private static final String NODE_SCHED_BOOST = "/proc/sys/walt/sched_boost";
    private static final String NODE_BYPASS_CHARGE =
            "/sys/class/xm_power/charger/smart_charge/bypass_charge";
    private static final String NODE_SMART_NIGHT =
            "/sys/class/xm_power/charger/smart_charge/smart_night";
    private static final String NODE_DISP_PARAM = "/sys/class/mi_display/disp-DSI-0/disp_param";
    private static final String DISP_PARAM_HBM_ON = "01 1";
    private static final String DISP_PARAM_HBM_OFF = "01 0";
    private static final String DISP_PARAM_DC_ON = "08 1";
    private static final String DISP_PARAM_DC_OFF = "08 0";

    private static final int[] PROFILE_SCONFIG = {
        -1, //  0: Auto
         0, //  1: Default (GLOBAL)
         6, //  2: Performance (NOLIMITS)
        19, //  3: Balanced Gaming (MGAME)
        18, //  4: Heavy Gaming (TGAME)
        20, //  5: Genshin Impact
        25, //  6: Honkai Star Rail
        26, //  7: High FPS
        15, //  8: Camera
        16, //  9: 4K Recording
        11, // 10: Video
        14, // 11: Video Chat
        10, // 12: Navigation
         5, // 13: Phone Call
         9, // 14: AR/VR
         1, // 15: Data Transfer
        -2, // 16: Battery Saver
    };

    private static final String[] PROFILE_SS_NAMES = {
        "default", "default", "performance", "gaming", "heavy_gaming",
        "genshin", "hsr", "high_fps", "camera", "recording_4k", "video",
        "video_chat", "navigation", "phone_call", "arvr", "data_transfer",
        "battery_saver",
    };

    private final Context mContext;
    private final Handler mHandler;
    private boolean mActive;
    private int mMaxWatt = DEFAULT_MAX_WATT;

    // ── Cached settings ─────────────────────────────────────────────────────
    private int mThermalProfile = 0;
    private int mTempLimit = DEFAULT_TEMP_LIMIT_DC;
    private int mTempOffset = 0;
    private int mChargeMaxWatt = DEFAULT_MAX_WATT;
    private int mChargeMinWatt = MIN_WATT;
    private final ArrayMap<String, Integer> mAppProfiles = new ArrayMap<>();

    // ── Applied state ───────────────────────────────────────────────────────
    private String mForegroundPackage = "";
    private int mActiveProfileIndex = -1;
    private int mAppliedSconfig = -1;
    private int mOriginalSconfig = 0;
    private boolean mGameBoostActive;
    private int mCustomRevision;
    private int mAppliedCustomRevision = -1;

    // Charging profile cache
    @Nullable private String mAppliedChargingId;
    private boolean mAppliedChargingWasCustom;
    private int mChargingRevision;
    private int mAppliedChargingRevision = -1;

    // Battery saver
    private boolean mBatterySaverActive;
    private float mSavedPeakRefreshRate = 120f;

    // HBM & DC dimming
    private boolean mHbmActive;
    private int mSavedBrightness = 128;
    private int mSavedAutoBrightness = 0;
    private int mHbmTimeoutMinutes = 5;
    private boolean mDcDimmingActive;

    private final Runnable mHbmTimeoutRunnable = this::onHbmTimeout;

    private void onHbmTimeout() {
        if (mHbmActive) {
            Slog.i(TAG, "HBM auto-timeout after " + mHbmTimeoutMinutes + " min");
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    KEY_HBM_ENABLED, 0, ActivityManager.getCurrentUser());
        }
    }

    ThermalPolicyBridge(@NonNull Context context, @NonNull Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    /** Called once on {@code mHandler} during PentasService startup. */
    void start() {
        if (!new File(NODE_SCONFIG).exists()) {
            return;
        }
        mActive = true;
        mMaxWatt = detectMaxWatt();
        mChargeMaxWatt = mMaxWatt;
        mOriginalSconfig = readIntNode(NODE_SCONFIG, 0);
        if ("1".equals(readNode(NODE_SCHED_BOOST))) {
            writeNodeIfExists(NODE_SCHED_BOOST, "0");
            Slog.w(TAG, "Cleared stale sched_boost=1");
        }

        final ContentResolver cr = mContext.getContentResolver();
        final boolean lowPower = Settings.Global.getInt(cr, Settings.Global.LOW_POWER_MODE, 0) == 1;
        mBatterySaverActive = lowPower;
        if (!lowPower && "1".equals(readNode(NODE_POWERSAVE_MODE))) {
            writeNode(NODE_POWERSAVE_MODE, "0");
            Slog.w(TAG, "Cleared stale powersave_mode=1");
        }

        loadSettings();
        loadAppProfiles();
        registerObservers(cr);

        final PowerManager pm = mContext.getSystemService(PowerManager.class);
        writeNode(NODE_SCREEN_STATE, (pm == null || pm.isInteractive()) ? "1" : "0");

        // Prime daemon properties
        SystemProperties.set(PROP_TEMP_LIMIT, Integer.toString(mTempLimit));
        SystemProperties.set(PROP_MAX_FCC, Long.toString(wattToUa(mChargeMaxWatt)));
        SystemProperties.set(PROP_MIN_FCC, Long.toString(wattToUa(mChargeMinWatt)));
        SystemProperties.set(PROP_TEMP_OFFSET, Integer.toString(mTempOffset));

        applyAll();
        Slog.i(TAG, "ThermalPolicyBridge started (maxWatt=" + mMaxWatt + ")");
    }

    /** Called on {@code mHandler} whenever PentasService resolves a new foreground package. */
    void onForegroundChanged(@NonNull String pkg) {
        if (!mActive || pkg.equals(mForegroundPackage)) return;
        mForegroundPackage = pkg;
        reconcile();
        applyChargingProfile();
    }

    /** Called on {@code mHandler} from PentasService's screen receiver. */
    void onScreenStateChanged(boolean screenOn) {
        if (!mActive) return;
        writeNode(NODE_SCREEN_STATE, screenOn ? "1" : "0");
    }

    /** Called on {@code mHandler} on user switch/unlock. */
    void onUserChanged() {
        if (!mActive) return;
        loadSettings();
        loadAppProfiles();
        mCustomRevision++;
        mChargingRevision++;
        applyAll();
    }

    // ── Observers ───────────────────────────────────────────────────────────

    private void registerObservers(@NonNull ContentResolver cr) {
        final ContentObserver sysObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                onSettingChanged(uri != null ? uri.toString() : "");
            }
        };
        final String[] keys = {
            KEY_THERMAL_PROFILE, KEY_TEMP_LIMIT, KEY_TEMP_OFFSET,
            KEY_CHARGE_MAX_WATT, KEY_CHARGE_MIN_WATT,
            KEY_APP_PROFILES, KEY_CUSTOM_PROFILES,
            KEY_CHG_PROFILES, KEY_CHG_GLOBAL_PROFILE, KEY_CHG_APP_PROFILES,
            KEY_BYPASS_CHARGING, KEY_HBM_ENABLED, KEY_HBM_TIMEOUT, KEY_DC_DIMMING,
        };
        for (String key : keys) {
            cr.registerContentObserver(
                    Settings.System.getUriFor(key), false, sysObserver, UserHandle.USER_ALL);
        }

        cr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE),
                false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        onBatterySaverChanged();
                    }
                },
                UserHandle.USER_ALL);

        mContext.registerReceiverAsUser(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        mHandler.postDelayed(() -> {
                            if (resolveActiveChargingProfileId(mForegroundPackage) == null) {
                                applyChargeSpeed();
                            }
                        }, 2000L);
                    }
                },
                UserHandle.ALL,
                new IntentFilter(Intent.ACTION_POWER_CONNECTED),
                null,
                mHandler);
    }

    private void onSettingChanged(@NonNull String uri) {
        loadSettings();
        if (uri.contains(KEY_APP_PROFILES)) {
            loadAppProfiles();
            reconcile();
        } else if (uri.contains(KEY_CUSTOM_PROFILES)) {
            mCustomRevision++;
            reconcile();
        } else if (uri.contains(KEY_CHG_PROFILES)
                || uri.contains(KEY_CHG_GLOBAL_PROFILE)
                || uri.contains(KEY_CHG_APP_PROFILES)) {
            mChargingRevision++;
            applyChargingProfile();
        } else if (uri.contains(KEY_BYPASS_CHARGING)) {
            applyBypassCharging(true);
        } else if (uri.contains(KEY_HBM_ENABLED) || uri.contains(KEY_HBM_TIMEOUT)) {
            applyHbm();
        } else if (uri.contains(KEY_DC_DIMMING)) {
            applyDcDimming();
        } else {
            applyAll();
        }
    }

    // ── Settings loading ────────────────────────────────────────────────────

    private void loadSettings() {
        final ContentResolver cr = mContext.getContentResolver();
        final int user = ActivityManager.getCurrentUser();

        mThermalProfile = Settings.System.getIntForUser(cr, KEY_THERMAL_PROFILE, 0, user);
        if (!isValidProfileIndex(mThermalProfile)) mThermalProfile = 0;

        mTempLimit = Settings.System.getIntForUser(cr, KEY_TEMP_LIMIT, DEFAULT_TEMP_LIMIT_DC, user);
        if (mTempLimit < 250 || mTempLimit > 480) mTempLimit = DEFAULT_TEMP_LIMIT_DC;

        mTempOffset = clamp(Settings.System.getIntForUser(cr, KEY_TEMP_OFFSET, 0, user), -150, 150);
        mChargeMaxWatt = clamp(
                Settings.System.getIntForUser(cr, KEY_CHARGE_MAX_WATT, mMaxWatt, user),
                MIN_WATT, mMaxWatt);
        mChargeMinWatt = clamp(
                Settings.System.getIntForUser(cr, KEY_CHARGE_MIN_WATT, MIN_WATT, user),
                MIN_WATT, mChargeMaxWatt);
    }

    private void loadAppProfiles() {
        mAppProfiles.clear();
        final String json = Settings.System.getStringForUser(
                mContext.getContentResolver(), KEY_APP_PROFILES, ActivityManager.getCurrentUser());
        if (json == null || json.isEmpty()) return;
        try {
            final JSONObject obj = new JSONObject(json);
            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                final String pkg = it.next();
                final int idx = obj.optInt(pkg, 0);
                if (idx != 0 && isValidProfileIndex(idx)) {
                    mAppProfiles.put(pkg, idx);
                }
            }
        } catch (JSONException e) {
            Slog.w(TAG, "Failed to parse " + KEY_APP_PROFILES, e);
        }
    }

    private static boolean isValidProfileIndex(int index) {
        return (index >= 0 && index < PROFILE_SCONFIG.length) || index >= CUSTOM_PROFILE_BASE;
    }

    // ── Apply logic ─────────────────────────────────────────────────────────

    private int resolveIndex() {
        if (mBatterySaverActive) return PROFILE_BATTERY_SAVER;
        final Integer perApp = mAppProfiles.get(mForegroundPackage);
        return perApp != null ? perApp : mThermalProfile;
    }

    private void reconcile() {
        applyProfileIndex(resolveIndex());
    }

    private void applyAll() {
        reconcile();
        SystemProperties.set(PROP_TEMP_LIMIT, Integer.toString(mTempLimit));
        SystemProperties.set(PROP_TEMP_OFFSET, Integer.toString(mTempOffset));
        applyChargingProfile();
        applyBypassCharging(false);
        applyHbm();
        applyDcDimming();
    }

    private void applyProfileIndex(int index) {
        final boolean isCustom = index >= CUSTOM_PROFILE_BASE;
        if (isCustom && index == mActiveProfileIndex
                && mCustomRevision == mAppliedCustomRevision) {
            return;
        }
        if (isCustom) {
            final int customIdx = index - CUSTOM_PROFILE_BASE;
            if (!applyCustomProfileByIndex(customIdx)) {
                Slog.w(TAG, "Custom profile #" + customIdx + " not found, falling back to Default");
                applySconfig(1);
            }
        } else {
            applySconfig(index);
        }
    }

    private void applySconfig(int profileIndex) {
        final int sconfig = PROFILE_SCONFIG[profileIndex];
        if (sconfig == -1) { // Auto
            if (mAppliedSconfig != -1 && mOriginalSconfig >= 0) {
                writeNode(NODE_SCONFIG, Integer.toString(mOriginalSconfig));
                writeNode(NODE_POWERSAVE_MODE, "0");
                mAppliedSconfig = -1;
                mActiveProfileIndex = -1;
                applyGameBoost(false);
                SystemProperties.set(PROP_SS_PROFILE, "default");
            }
            return;
        }

        final int targetSconfig = (sconfig == -2) ? 0 : sconfig;
        final boolean powersave = (sconfig == -2);

        if (targetSconfig != mAppliedSconfig || mActiveProfileIndex != profileIndex) {
            writeNode(NODE_SCONFIG, Integer.toString(targetSconfig));
            writeNode(NODE_POWERSAVE_MODE, powersave ? "1" : "0");
            mAppliedSconfig = targetSconfig;
            mActiveProfileIndex = profileIndex;

            SystemProperties.set(PROP_SS_PROFILE, PROFILE_SS_NAMES[profileIndex]);
            applyGameBoost(isGamingSconfig(sconfig));
        }
    }

    private boolean applyCustomProfileByIndex(int arrayIndex) {
        final String raw = Settings.System.getStringForUser(
                mContext.getContentResolver(), KEY_CUSTOM_PROFILES,
                ActivityManager.getCurrentUser());
        if (raw == null || raw.isEmpty()) return false;
        try {
            final JSONArray arr = new JSONArray(raw);
            if (arrayIndex < 0 || arrayIndex >= arr.length()) return false;
            final JSONObject profile = arr.getJSONObject(arrayIndex);

            // Build positional 4-cluster payload matching ss.rs
            final JSONArray clustersIn = profile.optJSONArray("clusters");
            final JSONArray clustersOut = new JSONArray();
            for (int slot = 0; slot < MAX_CLUSTERS; slot++) {
                final JSONObject slotObj = (clustersIn != null && slot < clustersIn.length())
                        ? clustersIn.optJSONObject(slot) : null;
                final JSONArray levels = slotObj != null ? slotObj.optJSONArray("levels") : null;
                clustersOut.put(new JSONObject().put("levels",
                        levels != null ? levels : new JSONArray()));
            }

            // Build side-channel CSVs: gpu, mon, sic
            final StringBuilder gpuCsv = new StringBuilder();
            final JSONArray gpuArr = profile.optJSONArray("gpu");
            if (gpuArr != null) {
                int added = 0;
                for (int i = 0; i < gpuArr.length() && added < CUSTOM_GPU_MAX; i++) {
                    final JSONObject lv = gpuArr.optJSONObject(i);
                    if (lv == null) continue;
                    final long trig = lv.optLong("trig", 0);
                    final long clr = lv.optLong("clr", 0);
                    final long freq = lv.optLong("freq", 0);
                    if (trig > 0 && freq > 0) {
                        if (added > 0) gpuCsv.append(';');
                        gpuCsv.append(trig).append(',').append(clr).append(',').append(freq);
                        added++;
                    }
                }
            }
            final JSONObject mon = profile.optJSONObject("monitor");
            final String monCsv = mon != null
                    ? mon.optLong("boost", 0) + "," + mon.optLong("hotplug", 0) + ","
                      + mon.optLong("bl", 0) + "," + mon.optInt("blcap", 0)
                    : "0,0,0,0";
            final JSONObject sic = profile.optJSONObject("sic");
            final String sicCsv = sic != null
                    ? sic.optLong("target", 0) + "," + sic.optLong("maxfcc", 0)
                    : "0,0";

            final ArrayMap<String, String> pre = new ArrayMap<>(3);
            pre.put(PROP_CUSTOM_GPU, gpuCsv.toString());
            pre.put(PROP_CUSTOM_MON, monCsv);
            pre.put(PROP_CUSTOM_SIC, sicCsv);

            if (!publishChunked(clustersOut.toString(), PROP_CUSTOM_PREFIX,
                    PROP_CUSTOM_COUNT, PROP_CUSTOM_SEQ, pre)) {
                return false;
            }

            final int customSconfig = PROFILE_SCONFIG[PROFILE_PERFORMANCE];
            writeNode(NODE_SCONFIG, Integer.toString(customSconfig));
            writeNode(NODE_POWERSAVE_MODE, "0");
            SystemProperties.set(PROP_SS_PROFILE, "custom");

            mAppliedSconfig = customSconfig;
            mActiveProfileIndex = CUSTOM_PROFILE_BASE + arrayIndex;
            mAppliedCustomRevision = mCustomRevision;
            applyGameBoost(true);
            return true;
        } catch (JSONException e) {
            Slog.w(TAG, "Failed to apply custom thermal profile #" + arrayIndex, e);
            return false;
        }
    }

    private void applyGameBoost(boolean enable) {
        if (enable == mGameBoostActive) return;
        mGameBoostActive = enable;
        // No WALT sched_boost here: full throttle for a whole session packs every task on the
        // prime/gold clusters with EAS off, +47% power at equal work on onyx.
        writeNodeIfExists(NODE_THERMAL_BOOST, enable ? "1" : "0");
    }

    private static boolean isGamingSconfig(int sconfig) {
        return sconfig == 6 || sconfig == 18 || sconfig == 19
                || sconfig == 20 || sconfig == 25 || sconfig == 26;
    }

    // ── Charging profiles & speed ───────────────────────────────────────────

    private void applyChargingProfile() {
        final String activeId = resolveActiveChargingProfileId(mForegroundPackage);
        if (Objects.equals(activeId, mAppliedChargingId)
                && mChargingRevision == mAppliedChargingRevision
                && (activeId != null) == mAppliedChargingWasCustom) {
            if (activeId == null) applyChargeSpeed();
            return;
        }

        final JSONObject profile = activeId != null ? findChargingProfile(activeId) : null;
        if (profile == null) {
            SystemProperties.set(PROP_CHG_MODE, "global");
            applyChargeSpeed();
            mAppliedChargingId = null;
            mAppliedChargingWasCustom = false;
            mAppliedChargingRevision = mChargingRevision;
            return;
        }

        try {
            // Daemon wire JSON: suspend, resume, tiers, then policy sections (envelope first).
            final JSONObject wire = new JSONObject();
            wire.put("suspend", profile.optLong("suspend", 52_000L));
            wire.put("resume", profile.optLong("resume", 48_000L));
            final JSONArray tiers = profile.optJSONArray("tiers");
            wire.put("tiers", tiers != null ? tiers : new JSONArray());

            final ArrayMap<String, String> pre = new ArrayMap<>();
            long ceilUa = POLICY_FCC_DEFAULT_UA;

            final JSONObject policy = profile.optJSONObject("policy");
            if (policy != null) {
                final JSONObject env = policy.optJSONObject("envelope");
                if (env != null && env.length() > 0) {
                    wire.put("envelope", env);
                    if (env.has("fcc_max")) {
                        ceilUa = env.optLong("fcc_max", 20_000L) * 1000L;
                    }
                }
                final JSONObject jeita = policy.optJSONObject("jeita");
                if (jeita != null && jeita.length() > 0) wire.put("jeita", jeita);
                final JSONObject jp = policy.optJSONObject("jeita_params");
                if (jp != null && jp.length() > 0) wire.put("jeita_params", jp);
                final JSONObject caps = policy.optJSONObject("caps");
                if (caps != null && caps.length() > 0) wire.put("caps", caps);
                final JSONArray ladder = policy.optJSONArray("ladder");
                if (ladder != null && ladder.length() > 0) wire.put("ladder", ladder);
                final JSONObject bypass = policy.optJSONObject("bypass");
                if (bypass != null && bypass.length() > 0) wire.put("bypass", bypass);

                final JSONObject dyn = policy.optJSONObject("dynamics");
                populateDynamicsProps(dyn, pre);
            } else {
                populateDynamicsProps(null, pre);
            }

            pre.put(PROP_CHG_MODE, "custom");
            pre.put(PROP_MAX_FCC, Long.toString(ceilUa));

            if (publishChunked(wire.toString(), PROP_CHG_PREFIX,
                    PROP_CHG_COUNT, PROP_CHG_SEQ, pre)) {
                mAppliedChargingId = activeId;
                mAppliedChargingWasCustom = true;
                mAppliedChargingRevision = mChargingRevision;
            }
        } catch (JSONException e) {
            Slog.w(TAG, "Failed to serialize charging profile " + activeId, e);
        }
    }

    private static void populateDynamicsProps(
            @Nullable JSONObject dyn, @NonNull ArrayMap<String, String> out) {
        out.put("persist.sys.mithermal.chg.cell_sensor",
                dyn != null ? dyn.optString("cell", "battery") : "battery");
        out.put("persist.sys.mithermal.chg.board_sensor",
                dyn != null ? dyn.optString("board", "batt_therm") : "batt_therm");
        out.put("persist.sys.mithermal.chg.pump_sensor",
                dyn != null ? dyn.optString("pump", "charger_therm") : "charger_therm");
        out.put("persist.sys.mithermal.chg.board_offset",
                Integer.toString(dyn != null ? dyn.optInt("board_off", 60) : 60));
        out.put("persist.sys.mithermal.chg.w_cell",
                Integer.toString(dyn != null ? dyn.optInt("w_cell", 800) : 800));
        out.put("persist.sys.mithermal.chg.w_board",
                Integer.toString(dyn != null ? dyn.optInt("w_board", 200) : 200));
        out.put("persist.sys.mithermal.chg.path",
                dyn != null ? dyn.optString("path", "all") : "all");
        out.put("persist.sys.mithermal.chg.suspend_hold",
                Integer.toString(dyn != null ? dyn.optInt("hold", 30) : 30));
        out.put("persist.sys.mithermal.chg.pred_ahead",
                Integer.toString(dyn != null ? dyn.optInt("ahead", 12) : 12));
        out.put("persist.sys.mithermal.chg.pred_gain",
                Integer.toString(dyn != null ? dyn.optInt("gain", 600) : 600));
        out.put("persist.sys.mithermal.chg.pred_cap",
                Integer.toString(dyn != null ? dyn.optInt("cap", 20) : 20));
        out.put("persist.sys.mithermal.chg.slew",
                Integer.toString(dyn != null ? dyn.optInt("slew", 500_000) : 500_000));
        out.put("persist.sys.mithermal.chg.netzero",
                Boolean.toString(dyn != null && dyn.optBoolean("netzero", false)));
        out.put("persist.sys.mithermal.chg.netzero_ma",
                Integer.toString(dyn != null ? dyn.optInt("netzero_ma", 0) : 0));
    }

    @Nullable
    private String resolveActiveChargingProfileId(@Nullable String pkg) {
        final ContentResolver cr = mContext.getContentResolver();
        final int user = ActivityManager.getCurrentUser();
        if (pkg != null && !pkg.isEmpty()) {
            final String appRaw = Settings.System.getStringForUser(cr, KEY_CHG_APP_PROFILES, user);
            if (appRaw != null && !appRaw.isEmpty()) {
                try {
                    final JSONObject map = new JSONObject(appRaw);
                    final String id = map.optString(pkg, "");
                    if (!id.isEmpty()) return id;
                } catch (JSONException ignored) {
                }
            }
        }
        final String global = Settings.System.getStringForUser(cr, KEY_CHG_GLOBAL_PROFILE, user);
        return (global != null && !global.isEmpty()) ? global : null;
    }

    @Nullable
    private JSONObject findChargingProfile(@NonNull String id) {
        final String raw = Settings.System.getStringForUser(
                mContext.getContentResolver(), KEY_CHG_PROFILES, ActivityManager.getCurrentUser());
        if (raw == null || raw.isEmpty()) return null;
        try {
            final JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id", ""))) return o;
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    private void applyChargeSpeed() {
        SystemProperties.set(PROP_MAX_FCC, Long.toString(wattToUa(mChargeMaxWatt)));
        SystemProperties.set(PROP_MIN_FCC, Long.toString(wattToUa(mChargeMinWatt)));
    }

    private void applyBypassCharging(boolean fromExplicitToggle) {
        final int user = ActivityManager.getCurrentUser();
        final int mode = clamp(
                Settings.System.getIntForUser(
                        mContext.getContentResolver(), KEY_BYPASS_CHARGING, 0, user),
                0, 2);

        if (!fromExplicitToggle && mode == 0) {
            final String activeId = resolveActiveChargingProfileId(mForegroundPackage);
            final JSONObject prof = activeId != null ? findChargingProfile(activeId) : null;
            final JSONObject policy = prof != null ? prof.optJSONObject("policy") : null;
            final JSONObject bypass = policy != null ? policy.optJSONObject("bypass") : null;
            if (bypass != null && bypass.optInt("mode", 0) != 0) {
                return;
            }
        }

        if (new File(NODE_BYPASS_CHARGE).exists()) {
            writeNode(NODE_BYPASS_CHARGE, Integer.toString(mode));
        } else {
            writeNodeIfExists(NODE_SMART_NIGHT, mode != 0 ? "1" : "0");
        }
    }

    // ── HBM & DC Dimming ────────────────────────────────────────────────────

    private void applyHbm() {
        final ContentResolver cr = mContext.getContentResolver();
        final int user = ActivityManager.getCurrentUser();
        final boolean enable = Settings.System.getIntForUser(cr, KEY_HBM_ENABLED, 0, user) == 1;
        mHbmTimeoutMinutes = Settings.System.getIntForUser(cr, KEY_HBM_TIMEOUT, 5, user);

        if (enable && !mHbmActive) {
            mSavedBrightness = Settings.System.getIntForUser(
                    cr, Settings.System.SCREEN_BRIGHTNESS, 128, user);
            mSavedAutoBrightness = Settings.System.getIntForUser(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0, user);
            Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, user);
            writeNodeIfExists(NODE_DISP_PARAM, DISP_PARAM_HBM_ON);
            Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS, 255, user);
            mHbmActive = true;

            mHandler.removeCallbacks(mHbmTimeoutRunnable);
            if (mHbmTimeoutMinutes > 0) {
                mHandler.postDelayed(mHbmTimeoutRunnable, mHbmTimeoutMinutes * 60_000L);
            }
        } else if (!enable && mHbmActive) {
            writeNodeIfExists(NODE_DISP_PARAM, DISP_PARAM_HBM_OFF);
            Settings.System.putIntForUser(
                    cr, Settings.System.SCREEN_BRIGHTNESS, mSavedBrightness, user);
            Settings.System.putIntForUser(
                    cr, Settings.System.SCREEN_BRIGHTNESS_MODE, mSavedAutoBrightness, user);
            mHbmActive = false;
            mHandler.removeCallbacks(mHbmTimeoutRunnable);
        }
    }

    private void applyDcDimming() {
        final boolean enable = Settings.System.getIntForUser(
                mContext.getContentResolver(), KEY_DC_DIMMING, 0,
                ActivityManager.getCurrentUser()) == 1;
        if (enable != mDcDimmingActive) {
            writeNodeIfExists(NODE_DISP_PARAM, enable ? DISP_PARAM_DC_ON : DISP_PARAM_DC_OFF);
            mDcDimmingActive = enable;
        }
    }

    // ── Battery Saver ───────────────────────────────────────────────────────

    private void onBatterySaverChanged() {
        final ContentResolver cr = mContext.getContentResolver();
        final int user = ActivityManager.getCurrentUser();
        final boolean lowPower = Settings.Global.getInt(cr, Settings.Global.LOW_POWER_MODE, 0) == 1;

        if (lowPower && !mBatterySaverActive) {
            mBatterySaverActive = true;
            reconcile();
            try {
                mSavedPeakRefreshRate = Settings.System.getFloatForUser(
                        cr, Settings.System.PEAK_REFRESH_RATE, 120f, user);
                Settings.System.putFloatForUser(cr, Settings.System.PEAK_REFRESH_RATE, 60f, user);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failed to lower refresh rate for battery saver", e);
            }
        } else if (!lowPower && mBatterySaverActive) {
            mBatterySaverActive = false;
            reconcile();
            try {
                Settings.System.putFloatForUser(
                        cr, Settings.System.PEAK_REFRESH_RATE, mSavedPeakRefreshRate, user);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failed to restore refresh rate after battery saver", e);
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean publishChunked(
            @NonNull String payload,
            @NonNull String chunkPrefix,
            @NonNull String countProp,
            @NonNull String seqProp,
            @NonNull ArrayMap<String, String> preCommitProps) {
        final int len = payload.length();
        final int count = (len + PROP_CHUNK_SIZE - 1) / PROP_CHUNK_SIZE;
        if (count == 0 || count > PROP_CHUNK_MAX) return false;

        for (int i = 0; i < count; i++) {
            final int start = i * PROP_CHUNK_SIZE;
            final int end = Math.min(len, start + PROP_CHUNK_SIZE);
            SystemProperties.set(chunkPrefix + i, payload.substring(start, end));
        }
        final int prevCount = Math.min(
                (int) SystemProperties.getLong(countProp, 0L), PROP_CHUNK_MAX);
        for (int i = count; i < prevCount; i++) {
            SystemProperties.set(chunkPrefix + i, "");
        }
        for (int i = 0; i < preCommitProps.size(); i++) {
            SystemProperties.set(preCommitProps.keyAt(i), preCommitProps.valueAt(i));
        }
        SystemProperties.set(countProp, Integer.toString(count));
        final long nextSeq = SystemProperties.getLong(seqProp, 0L) + 1L;
        SystemProperties.set(seqProp, Long.toString(nextSeq));
        return true;
    }

    private static int detectMaxWatt() {
        final String[] dtPaths = {
            "/sys/firmware/devicetree/base/soc/mca_strategy_quick_charge/div_max_curr",
            "/sys/firmware/devicetree/base/soc/mca_strategy_quick_wireless_class/div_max_curr",
        };
        for (String path : dtPaths) {
            final File f = new File(path);
            if (!f.exists() || !f.canRead()) continue;
            try (FileInputStream fis = new FileInputStream(f)) {
                final byte[] bytes = fis.readAllBytes();
                int maxMa = 0;
                for (int i = 0; i + 3 < bytes.length; i += 4) {
                    final int v = ((bytes[i] & 0xFF) << 24)
                            | ((bytes[i + 1] & 0xFF) << 16)
                            | ((bytes[i + 2] & 0xFF) << 8)
                            | (bytes[i + 3] & 0xFF);
                    if (v >= 1000 && v <= 50000 && v > maxMa) {
                        maxMa = v;
                    }
                }
                if (maxMa > 0) {
                    final int watt = (int) Math.ceil((maxMa * 1000.0) / UA_PER_WATT);
                    if (watt >= 10) return watt;
                }
            } catch (IOException ignored) {
            }
        }
        return DEFAULT_MAX_WATT;
    }

    private static long wattToUa(int watts) {
        final long ua = (long) (watts * UA_PER_WATT);
        return Math.max(FCC_HARD_MIN_UA, Math.min(FCC_HARD_MAX_UA, ua));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Nullable
    private static String readNode(@NonNull String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            final String line = br.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static int readIntNode(@NonNull String path, int fallback) {
        final String s = readNode(path);
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void writeNode(@NonNull String path, @NonNull String value) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(value.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write " + path + "=" + value + ": " + e.getMessage());
        }
    }

    private static void writeNodeIfExists(@NonNull String path, @NonNull String value) {
        if (new File(path).exists()) {
            writeNode(path, value);
        }
    }
}
