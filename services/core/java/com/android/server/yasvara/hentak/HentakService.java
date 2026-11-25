/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.hentak;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.vibrator.EffectStrength;
import android.hardware.vibrator.IVibrator;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.net.Uri;
import android.os.Binder;
import android.os.FileObserver;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.IVibratorManagerService;
import android.provider.Settings;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationConfig;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.yasvara.framefx.FramefxBroker;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/**
 * Hentak: game haptics from the game's own audio.
 *
 * <p>Arms the game of the live Pentas session in audioserver ({@code hentak_uids=}), which runs a
 * transient detector on that game's playback tracks and streams a haptic envelope straight to the
 * vibrator HAL's "hentak" instance, scaled by the user's hardware feedback intensity
 * ({@code hentak_gain=}). Without that instance audioserver sends each hit, blast or rumble here
 * instead, and they play as compositions through the usual vibration path.
 *
 * <p>Per-game settings are Pentas's, Settings.System per user: {@code pentas_hentak}
 * {@code {"<pkg>": 1|2}} (on, strong; absent is off) and {@code pentas_hentak_sens}
 * {@code {"<pkg>": "0.5".."1.5"}}. Strong raises the HAL's sustain past the motor's knee: harder
 * hits, more coil heat, so Pentas shows a caution and the HAL's coil model still caps it.
 *
 * <p>Every game with Hentak on also taps its own sound calls (framefx/sounds, the Lapis section's
 * sound bit): audioserver asks here for the armed game's ring and settles hits by what the game
 * started just before them.
 */
public final class HentakService extends Binder {

    private static final String TAG = "Hentak";
    public static final String SERVICE_NAME = "yasvara_hentak";

    // Mirrors frameworks/av/services/hentak.
    private static final String DESCRIPTOR = "org.yasvara.hentak.IHentak";
    private static final int TRANSACTION_PULSE = IBinder.FIRST_CALL_TRANSACTION;
    /** uid -> i32 pid of the process whose sound-call ring follows, 0 for none; the descriptor. */
    private static final int TRANSACTION_SOUNDS = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int KIND_IMPACT = 0;
    private static final int KIND_BLAST = 1;
    private static final int KIND_RUMBLE = 2;

    private static final String KEY_MODE = "pentas_hentak";
    private static final String KEY_SENSITIVITY = "pentas_hentak_sens";
    private static final String KEY_GAME_FOCUS = "pentas_game_focus";

    static final int MODE_OFF = 0;
    static final int MODE_ON = 1;
    static final int MODE_STRONG = 2;

    // Mirrors StreamPlayer::kDriveId in vendor/qcom/opensource/vibrator.
    private static final int HAL_DRIVE_ID = 0x4D;
    private static final String HAL_NAME = IVibrator.DESCRIPTOR + "/default";

    /**
     * Sound catalogs from the game's own files (frameworks/av/services/hentak/rust, hentak_scan):
     * init runs the scan when this property names a game; the result appears here.
     */
    private static final String SCAN_PROP = "sys.yasvara.hentak.scan";
    private static final File CATALOG_DIR = new File("/data/misc/hentak");

    /** A rumble is re-armed only this close to the end of the last one, so it doesn't stutter. */
    private static final int RUMBLE_MS = 240;
    private static final int RUMBLE_REARM_MS = 70;
    /** An impact is never cut by a rumble that lands within this window. */
    private static final int IMPACT_HOLD_MS = 70;
    /** Blast tail after the punches: steps falling off over ~300 ms. */
    private static final long[] BLAST_TAIL_MS = {60, 60, 60, 60, 60};
    private static final int[] BLAST_TAIL_AMP = {200, 150, 105, 70, 40};

    private static final VibrationAttributes ATTRS =
            new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION)
                    .build();

    /** Short buzzes from the armed game become a kick at f0 instead of a flat constant. */
    private static final int ADAPT_MAX_MS = 120;

    /** The armed game, for VibratorManagerService's hook. */
    private static volatile int sArmedUid = -1;

    private final Context mContext;
    private final Handler mHandler = BackgroundThread.getHandler();
    /** Looked up on first use: this service starts before vibrator_manager is published. */
    @Nullable private IVibratorManagerService mVibrators;
    private final IBinder mToken = new Binder();

    /** Armed game and its settings; written on the handler, read on binder threads. */
    private volatile int mUid = -1;
    private volatile String mPackage;
    private volatile int mMode = MODE_OFF;
    private volatile float mSensitivity = 1f;
    private volatile float mGain = 1f;
    /** "hentak_nommap=" value last sent: every game with Hentak on, armed or not. */
    private volatile String mNoMmap = "";

    /** Watches CATALOG_DIR for a finished scan; held so it isn't collected. */
    private FileObserver mCatalogs;

    private long mLastImpactMs;
    private long mRumbleEndsMs;
    private int mImpacts, mBlasts, mRumbles, mRejected;

    public HentakService(@NonNull Context context) {
        mContext = context;
        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                reload();
            }
        };
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_GAME_FOCUS), false, observer, UserHandle.USER_ALL);
        context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(KEY_MODE), false, observer, UserHandle.USER_ALL);
        context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(KEY_SENSITIVITY), false, observer, UserHandle.USER_ALL);
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(
                Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY), false, observer,
                UserHandle.USER_ALL);
        context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_INTENSITY), false,
                observer, UserHandle.USER_ALL);
        mHandler.post(this::reload);
        mHandler.post(this::watchAudioServer);
        mHandler.post(this::watchCatalogs);
    }

    /** A scan that finishes while its game is armed takes effect at once. */
    private void watchCatalogs() {
        mCatalogs = new FileObserver(CATALOG_DIR, FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE
                | FileObserver.DELETE) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                final String pkg = mPackage;
                if (pkg != null && (pkg + ".htc").equals(path)) {
                    mHandler.post(() -> {
                        if (pkg.equals(mPackage) && mUid >= 0) {
                            AudioSystem.setParameters(catalogParameter(mUid, pkg));
                        }
                    });
                }
            }
        };
        mCatalogs.startWatching();
    }

    private static String catalogParameter(int uid, String pkg) {
        final File f = new File(CATALOG_DIR, pkg + ".htc");
        return "hentak_catalog=" + uid + ":" + (f.exists() ? f.getPath() : "");
    }

    /** audioserver forgets the armed uid when it restarts. AudioService starts after us. */
    private void watchAudioServer() {
        if (ServiceManager.checkService(Context.AUDIO_SERVICE) == null) {
            mHandler.postDelayed(this::watchAudioServer, 500);
            return;
        }
        try {
            // Own instance: the system context's AudioManager takes one such callback, Swara's.
            new AudioManager(mContext).setAudioServerStateCallback(mHandler::post,
                    new AudioManager.AudioServerStateCallback() {
                        @Override
                        public void onAudioServerUp() {
                            AudioSystem.setParameters("hentak_nommap=" + mNoMmap);
                            if (mUid >= 0) {
                                AudioSystem.setParameters(armParameters(mUid, mSensitivity, mGain));
                                if (mPackage != null) {
                                    AudioSystem.setParameters(catalogParameter(mUid, mPackage));
                                }
                            }
                        }
                    });
        } catch (RuntimeException e) {
            Slog.w(TAG, "No audioserver restart callback", e);
        }
    }

    /** Arms or disarms audioserver and the HAL for the session game. Handler thread. */
    private void reload() {
        final int user = android.app.ActivityManager.getCurrentUser();
        final String pkg = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                KEY_GAME_FOCUS, user);
        int mode = MODE_OFF;
        float sens = 1f;
        int uid = -1;
        if (pkg != null && !pkg.isEmpty()) {
            mode = Math.max(MODE_OFF, Math.min(MODE_STRONG,
                    (int) perApp(KEY_MODE, pkg, user, MODE_OFF)));
            sens = (float) Math.max(0.3, Math.min(2.0, perApp(KEY_SENSITIVITY, pkg, user, 1.0)));
            try {
                uid = mContext.getPackageManager().getPackageUidAsUser(pkg, user);
            } catch (PackageManager.NameNotFoundException e) {
                mode = MODE_OFF;
            }
        }
        if (mode == MODE_OFF) uid = -1;
        // Before arming: a game's MMAP stream is refused or torn down by these.
        final String noMmap = enabledUids(user);
        if (!noMmap.equals(mNoMmap)) {
            mNoMmap = noMmap;
            AudioSystem.setParameters("hentak_nommap=" + noMmap);
        }
        tapSounds(enabledPackages(user));
        final float gain = gain(user);
        if (uid == mUid && mode == mMode && sens == mSensitivity && gain == mGain) return;
        final boolean newGame = uid >= 0 && uid != mUid;

        mPackage = mode == MODE_OFF ? null : pkg;
        mMode = mode;
        mSensitivity = sens;
        mGain = gain;
        mUid = uid;
        sArmedUid = uid;
        AudioSystem.setParameters(uid < 0 ? "hentak_uids=" : armParameters(uid, sens, gain));
        if (newGame) {
            // Last catalog now; the scan (instant when the files are unchanged) replaces it.
            AudioSystem.setParameters(catalogParameter(uid, pkg));
            SystemProperties.set(SCAN_PROP, pkg + "/" + SystemClock.elapsedRealtime());
        }
        setHalDrive(mode == MODE_STRONG ? EffectStrength.STRONG : EffectStrength.LIGHT);
        Slog.i(TAG, mode == MODE_OFF ? "Disarmed" : "Armed " + pkg + " mode " + mode + " sens " + sens);
    }

    /** Has framefx tap the sound calls of exactly these packages, from their next start. */
    private void tapSounds(java.util.Set<String> packages) {
        final FramefxBroker broker = FramefxBroker.get();
        if (broker == null) return;
        final java.util.Set<String> tapped = broker.soundTapped();
        for (String pkg : packages) {
            if (!tapped.contains(pkg)) broker.setSoundTap(pkg, true);
        }
        for (String pkg : tapped) {
            if (!packages.contains(pkg)) broker.setSoundTap(pkg, false);
        }
    }

    /** Every package Pentas has Hentak on for. */
    private java.util.Set<String> enabledPackages(int user) {
        final java.util.Set<String> out = new java.util.HashSet<>();
        final String json = Settings.System.getStringForUser(mContext.getContentResolver(),
                KEY_MODE, user);
        if (json == null || json.isEmpty()) return out;
        try {
            final JSONObject map = new JSONObject(json);
            for (java.util.Iterator<String> it = map.keys(); it.hasNext(); ) {
                final String pkg = it.next();
                if (map.optInt(pkg, MODE_OFF) != MODE_OFF) out.add(pkg);
            }
        } catch (JSONException e) {
            // enabledUids logs it.
        }
        return out;
    }

    /** Uids of every package Pentas has Hentak on for, comma separated. */
    private String enabledUids(int user) {
        final String json = Settings.System.getStringForUser(mContext.getContentResolver(),
                KEY_MODE, user);
        if (json == null || json.isEmpty()) return "";
        final StringBuilder out = new StringBuilder();
        try {
            final JSONObject map = new JSONObject(json);
            for (java.util.Iterator<String> it = map.keys(); it.hasNext(); ) {
                final String pkg = it.next();
                if (map.optInt(pkg, MODE_OFF) == MODE_OFF) continue;
                try {
                    final int uid = mContext.getPackageManager().getPackageUidAsUser(pkg, user);
                    if (out.length() > 0) out.append(',');
                    out.append(uid);
                } catch (PackageManager.NameNotFoundException e) {
                    // Not installed for this user.
                }
            }
        } catch (JSONException e) {
            Slog.w(TAG, "Bad " + KEY_MODE + ": " + e.getMessage());
        }
        return out.toString();
    }

    private static String armParameters(int uid, float sens, float gain) {
        return String.format(Locale.ROOT, "hentak_uids=%d:%.2f;hentak_gain=%.2f", uid, sens, gain);
    }

    /**
     * What VibratorManagerService would apply to PHYSICAL_EMULATION: the hardware feedback
     * intensity, one step from the default scaling the envelope by ~0.7.
     */
    private float gain(int user) {
        // From the config, not the Vibrator: this runs before vibrator_manager is published, and
        // the system context would cache a Vibrator with no service for every caller after it
        // (the power key's and input's haptics among them).
        final int def = new VibrationConfig(mContext.getResources())
                .getDefaultVibrationIntensity(VibrationAttributes.USAGE_PHYSICAL_EMULATION);
        int level = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, -1, user);
        if (level < 0) {
            level = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_INTENSITY, def, user);
        }
        if (level == Vibrator.VIBRATION_INTENSITY_OFF) return 0f;
        return (float) Math.max(0.5, Math.min(1.4, Math.pow(1.4, level - def)));
    }

    /** A number from Pentas's {"<pkg>": value} map; values may be numbers or strings. */
    private double perApp(String key, String pkg, int user, double fallback) {
        final String json = Settings.System.getStringForUser(mContext.getContentResolver(), key, user);
        if (json == null || json.isEmpty()) return fallback;
        try {
            final Object v = new JSONObject(json).opt(pkg);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) return Double.parseDouble((String) v);
        } catch (JSONException | NumberFormatException e) {
            Slog.w(TAG, "Bad " + key + ": " + e.getMessage());
        }
        return fallback;
    }

    private void setHalDrive(byte strength) {
        try {
            final IVibrator hal = IVibrator.Stub.asInterface(ServiceManager.checkService(HAL_NAME));
            if (hal != null) hal.alwaysOnEnable(HAL_DRIVE_ID, android.hardware.vibrator.Effect.CLICK,
                    strength);
        } catch (RemoteException | RuntimeException e) {
            // Older HALs: the drive stays at its default, which is the normal mode.
            Slog.d(TAG, "HAL drive unavailable: " + e.getMessage());
        }
    }

    @Override
    protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags)
            throws RemoteException {
        if (code == TRANSACTION_SOUNDS) {
            if (Binder.getCallingUid() != Process.AUDIOSERVER_UID || reply == null) return false;
            data.enforceInterface(DESCRIPTOR);
            final int uid = data.readInt();
            final FramefxBroker broker = FramefxBroker.get();
            final FramefxBroker.SoundsRing ring =
                    broker != null && uid == mUid ? broker.soundsRing(uid) : null;
            reply.writeInt(ring != null ? ring.pid : 0);
            if (ring != null) reply.writeFileDescriptor(ring.fd);
            return true;
        }
        if (code != TRANSACTION_PULSE) return super.onTransact(code, data, reply, flags);
        if (Binder.getCallingUid() != Process.AUDIOSERVER_UID) return false;
        data.enforceInterface(DESCRIPTOR);
        final int uid = data.readInt();
        final int kind = data.readInt();
        final float intensity = data.readFloat();
        final float sharpness = data.readFloat();
        final long token = Binder.clearCallingIdentity();
        try {
            pulse(uid, kind, intensity, sharpness);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return true;
    }

    private synchronized void pulse(int uid, int kind, float intensity, float sharpness) {
        if (uid != mUid || mMode == MODE_OFF) {
            mRejected++;
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final VibrationEffect effect;
        switch (kind) {
            case KIND_BLAST:
                // Double punch, then a decaying tail.
                effect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.7f, 20)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                                0.4f + 0.6f * clamp(intensity))
                        .addEffect(VibrationEffect.createWaveform(BLAST_TAIL_MS,
                                scaled(BLAST_TAIL_AMP, clamp(intensity)), -1))
                        .compose();
                mLastImpactMs = now;
                mRumbleEndsMs = 0;
                mBlasts++;
                break;
            case KIND_IMPACT: {
                // Expanded so quiet hits stay light and loud ones stand out.
                final float s = (float) Math.pow(clamp(intensity), 1.6);
                final VibrationEffect.Composition c = VibrationEffect.startComposition();
                if (intensity < 0.45f) {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, Math.max(0.15f, s));
                } else if (sharpness > 0.5f) {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, s);
                    // Heavy and sharp, a gunshot: crack, then the body. Fits 600 rpm fire.
                    if (intensity >= 0.75f) {
                        c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f * s, 12);
                    }
                } else {
                    c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, s);
                    if (intensity >= 0.75f) {
                        c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.5f * s);
                    }
                }
                effect = c.compose();
                mLastImpactMs = now;
                mRumbleEndsMs = 0;
                mImpacts++;
                break;
            }
            case KIND_RUMBLE:
                if (now - mLastImpactMs < IMPACT_HOLD_MS) return;
                if (mRumbleEndsMs - now > RUMBLE_REARM_MS) return;
                final int amplitude = Math.max(1, Math.min(255, Math.round(clamp(intensity) * 255)));
                effect = VibrationEffect.createOneShot(RUMBLE_MS, amplitude);
                mRumbleEndsMs = now + RUMBLE_MS;
                mRumbles++;
                break;
            default:
                return;
        }
        if (mVibrators == null) {
            mVibrators = IVibratorManagerService.Stub.asInterface(
                    ServiceManager.getService(Context.VIBRATOR_MANAGER_SERVICE));
            if (mVibrators == null) return;
        }
        try {
            mVibrators.vibrate(Process.SYSTEM_UID, android.content.Context.DEVICE_ID_DEFAULT,
                    mContext.getOpPackageName(), CombinedVibration.createParallel(effect), ATTRS,
                    "hentak", mToken);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "vibrate failed: " + e.getMessage());
        }
    }

    /**
     * Called by VibratorManagerService for every request. For the armed game, a single short
     * on-step (what {@code vibrate(ms)} and most engines send) is replaced by a click or thud of
     * the same strength; anything else passes through.
     */
    @NonNull
    public static CombinedVibration adaptGameVibration(int uid, @NonNull CombinedVibration vib) {
        if (uid != sArmedUid || uid < 0 || !(vib instanceof CombinedVibration.Mono mono)) return vib;
        if (!(mono.getEffect() instanceof VibrationEffect.Composed composed)) return vib;
        final List<VibrationEffectSegment> segments = composed.getSegments();
        if (composed.getRepeatIndex() >= 0) return vib;
        StepSegment on = null;
        for (VibrationEffectSegment s : segments) {
            if (!(s instanceof StepSegment step)) return vib;
            if (step.getAmplitude() == 0f) continue;
            if (on != null) return vib;
            on = step;
        }
        if (on == null || on.getDuration() > ADAPT_MAX_MS) return vib;
        final float amp = on.getAmplitude() == VibrationEffect.DEFAULT_AMPLITUDE
                ? 0.8f : clamp(on.getAmplitude());
        final int primitive = on.getDuration() < 30
                ? VibrationEffect.Composition.PRIMITIVE_CLICK
                : VibrationEffect.Composition.PRIMITIVE_THUD;
        return CombinedVibration.createParallel(VibrationEffect.startComposition()
                .addPrimitive(primitive, amp).compose());
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int[] scaled(int[] amplitudes, float k) {
        final int[] out = new int[amplitudes.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = amplitudes[i] == 0 ? 0 : Math.max(1, Math.round(amplitudes[i] * k));
        }
        return out;
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        synchronized (this) {
            pw.println("Hentak: " + (mPackage == null ? "disarmed" : mPackage + " uid " + mUid
                    + " mode " + mMode + " sens " + mSensitivity));
            pw.println("  impacts " + mImpacts + " blasts " + mBlasts + " rumbles " + mRumbles
                    + " rejected " + mRejected);
        }
        pw.println("  audioserver: " + AudioSystem.getParameters("hentak_dump"));
    }
}
