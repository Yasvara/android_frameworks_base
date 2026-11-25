/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.vibrator;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.vibrator.EffectStrength;
import android.hardware.vibrator.IVibrator;
import android.net.Uri;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.IServiceCallback;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.provider.Settings;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The user's Raba profile ({@link RabaProfile}) for the vibrator service: haptic feedback
 * constants resolve through it, touch-class vibrations take its master strength, and the HAL
 * gets its reskin table.
 */
final class RabaHaptics {
    private static final String TAG = "Raba";

    // Mirrors StreamPlayer::kReskinId in vendor/qcom/opensource/vibrator.
    private static final int HAL_RESKIN_ID = 0x5200;
    private static final int HAL_BOOST_ID = 0x5300;
    private static final String HAL_NAME = IVibrator.DESCRIPTOR + "/default";
    private static final int MAX_DELAY_MS = 1000;

    private static volatile RabaProfile sProfile = RabaProfile.DEFAULT;

    private static Context sContext;
    /** Last state the HAL took, so an unchanged setting doesn't resend; a restart does. */
    private static boolean sHalReskin;
    private static int sHalBoost;
    @Nullable private static IBinder sHal;

    private RabaHaptics() {}

    /** Starts following the current user's setting. Called once, from systemReady. */
    static void start(@NonNull Context context, @NonNull Handler handler) {
        sContext = context;
        context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(RabaProfile.SETTING), false,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange, @Nullable Uri uri) {
                        reload();
                    }
                }, UserHandle.USER_ALL);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                reload();
            }
        }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, handler);
        handler.post(RabaHaptics::reload);
        // Each (re)start of the HAL, however long it was down; it forgets the channels.
        try {
            ServiceManager.registerForNotifications(HAL_NAME, new IServiceCallback.Stub() {
                @Override
                public void onRegistration(String name, IBinder binder) {
                    handler.post(() -> pushHal(binder));
                }
            });
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "No HAL registration notices: " + e.getMessage());
        }
    }

    @NonNull
    static RabaProfile profile() {
        return sProfile;
    }

    /** Whether the user turned this constant's category off. */
    static boolean isSilenced(int constant) {
        final RabaProfile.Tone tone = sProfile.tone(constant);
        return tone != null && tone.isOff();
    }

    /**
     * The constant's texture at its strength, or null when the platform table applies (Pixel,
     * constants outside the categories, or a HAL without the bank).
     */
    @Nullable
    static VibrationEffect effectFor(int constant, @NonNull VibratorInfo info) {
        final RabaProfile.Tone tone = sProfile.tone(constant);
        if (tone == null || tone.isOff()) return null;
        final int primitive = RabaProfile.TEXTURE_BASE + tone.wave;
        if (!info.isPrimitiveSupported(primitive)) return null;
        // Built directly: Composition.addPrimitive only takes the public primitive ids.
        return new VibrationEffect.Composed(
                List.of(new PrimitiveSegment(primitive, tone.scale, /* delay= */ 0)),
                /* repeatIndex= */ -1);
    }

    /**
     * A single-vibrator composition of texture primitives. {@code validate()} rejects their ids,
     * and the framework runs it on every rebuild (wrapping, scaling, adapting), so these take the
     * paths below instead. Only well formed ones pass; the HAL checks the ids against its bank.
     */
    static boolean isTextureEffect(@Nullable CombinedVibration vibration) {
        return vibration instanceof CombinedVibration.Mono mono
                && isTextureComposition(mono.getEffect());
    }

    static boolean isTextureComposition(@Nullable VibrationEffect effect) {
        if (!(effect instanceof VibrationEffect.Composed composed)) return false;
        if (composed.getRepeatIndex() != -1 || composed.getSegments().isEmpty()) return false;
        for (VibrationEffectSegment s : composed.getSegments()) {
            if (!(s instanceof PrimitiveSegment p)) return false;
            final int wave = p.getPrimitiveId() - RabaProfile.TEXTURE_BASE;
            if (wave < 1 || wave > RabaProfile.WAVES) return false;
            if (!(p.getScale() >= 0f && p.getScale() <= 1f)) return false;
            if (p.getDelay() < 0 || p.getDelay() > MAX_DELAY_MS) return false;
        }
        return true;
    }

    /** CombinedVibration.createParallel() without its validate(). */
    @NonNull
    static CombinedVibration mono(@NonNull VibrationEffect effect) {
        final Parcel p = Parcel.obtain();
        try {
            p.writeInt(MONO_TOKEN);
            effect.writeToParcel(p, 0);
            p.setDataPosition(0);
            return CombinedVibration.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    private static final int MONO_TOKEN = monoToken();

    private static int monoToken() {
        final Parcel p = Parcel.obtain();
        try {
            CombinedVibration.createParallel(VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                    .writeToParcel(p, 0);
            p.setDataPosition(0);
            return p.readInt();
        } finally {
            p.recycle();
        }
    }

    /** VibrationScaler's steps for a texture composition: primitives only take the two scales. */
    @NonNull
    static VibrationEffect scaleTextures(@NonNull VibrationEffect.Composed composed, float factor,
            float adaptive) {
        final List<VibrationEffectSegment> segments = new ArrayList<>(composed.getSegments().size());
        for (VibrationEffectSegment s : composed.getSegments()) {
            segments.add(((PrimitiveSegment) s).scale(factor).applyAdaptiveScale(adaptive));
        }
        return new VibrationEffect.Composed(segments, composed.getRepeatIndex());
    }

    /** CombinedVibration.adapt() for a texture vibration, rebuilt without validate(). */
    @Nullable
    static CombinedVibration adaptTextures(@NonNull CombinedVibration vibration,
            @NonNull CombinedVibration.VibratorAdapter adapter) {
        final VibrationEffect effect = ((CombinedVibration.Mono) vibration).getEffect();
        final int[] ids = adapter.getAvailableVibratorIds();
        VibrationEffect adapted = effect;
        for (int id : ids) {
            final VibrationEffect e = adapter.adaptToVibrator(id, effect);
            if (e == null) return null;
            // Mono plays the same effect on every vibrator; one that needs another can't.
            if (adapted != effect && !adapted.equals(e)) return null;
            if (!e.equals(effect)) adapted = e;
        }
        return adapted == effect ? vibration : mono(adapted);
    }

    /** Whether the master strength applies to vibrations of this usage. */
    static boolean isTouchClass(@VibrationAttributes.Usage int usage) {
        return usage == VibrationAttributes.USAGE_TOUCH
                || usage == VibrationAttributes.USAGE_IME_FEEDBACK
                || usage == VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
    }

    /**
     * Master strength for a prebaked effect, which only has three strengths: below 100 % it caps
     * the strength the intensity setting chose, from 125 % it raises it to strong.
     */
    static int effectStrength(@VibrationAttributes.Usage int usage, int strength) {
        if (!isTouchClass(usage)) return strength;
        final float master = sProfile.master();
        if (master >= 1.25f) return VibrationEffect.EFFECT_STRENGTH_STRONG;
        if (master < 0.5f) return Math.min(strength, VibrationEffect.EFFECT_STRENGTH_LIGHT);
        if (master < 0.85f) return Math.min(strength, VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        return strength;
    }

    /** Linear master scale for a touch-class composition or waveform; 1 for the rest. */
    static float masterScale(@VibrationAttributes.Usage int usage) {
        return isTouchClass(usage) ? sProfile.master() : 1f;
    }

    static void dump(PrintWriter pw) {
        pw.println("Raba: " + sProfile.toString().replace("\n", "\n  "));
        pw.println("  HAL reskin: " + sHalReskin + ", texture boost: " + sHalBoost + " %");
    }

    /** Handler thread. */
    private static void reload() {
        final String json = Settings.System.getStringForUser(sContext.getContentResolver(),
                RabaProfile.SETTING, ActivityManager.getCurrentUser());
        final RabaProfile profile = RabaProfile.parse(json);
        sProfile = profile;
        pushHal(null);
    }

    /** Handler thread. {@code binder}: a HAL that just registered; null for the current one. */
    private static void pushHal(@Nullable IBinder binder) {
        if (binder == null) binder = ServiceManager.checkService(HAL_NAME);
        // Down: its registration notice sends it.
        if (binder == null) return;
        final RabaProfile profile = sProfile;
        final boolean reskin = profile.reskin();
        final int boost = Math.round(profile.textureBoost() * 100);
        final boolean fresh = binder != sHal;
        if (!fresh && reskin == sHalReskin && boost == sHalBoost) return;
        final IVibrator hal = IVibrator.Stub.asInterface(binder);
        try {
            if (fresh || reskin != sHalReskin) {
                for (int[] pair : RabaProfile.reskinTable()) {
                    if (reskin) {
                        hal.alwaysOnEnable(HAL_RESKIN_ID + pair[0], pair[1], EffectStrength.MEDIUM);
                    } else {
                        hal.alwaysOnDisable(HAL_RESKIN_ID + pair[0]);
                    }
                }
            }
            if (fresh || boost != sHalBoost) {
                hal.alwaysOnEnable(HAL_BOOST_ID, boost, EffectStrength.MEDIUM);
            }
        } catch (RemoteException | RuntimeException e) {
            // Died meanwhile (its successor's notice sends again), or a HAL without these
            // channels, which plays standard primitives and textures as they are.
            Slog.d(TAG, "HAL channels unavailable: " + e.getMessage());
            return;
        }
        sHal = binder;
        sHalReskin = reskin;
        sHalBoost = boost;
        Slog.i(TAG, "HAL reskin " + (reskin ? "on" : "off") + ", texture boost " + boost + " %");
    }
}
