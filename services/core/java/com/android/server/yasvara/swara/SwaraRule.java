/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.yasvara.swara;

import android.annotation.NonNull;
import android.app.yasvara.Swara;
import android.os.Bundle;

import java.util.Locale;
import java.util.Objects;

/** One app's Swara rule. Mutable; owned by SwaraService under its lock. */
final class SwaraRule {
    float volume = 1f;
    boolean muted;
    boolean micMuted;
    float micGain = 1f;
    boolean shareMic;
    int outputType;
    @NonNull String outputAddress = "";
    boolean resetOnClose;

    /** Below this a level is a mute (1%). */
    static final float MIN_VOLUME = 0.01f;

    /** A stored 0% (older rules, or a level that never moved) becomes a mute at full level. */
    void normalize() {
        if (volume < MIN_VOLUME) {
            volume = 1f;
            muted = true;
        }
    }

    boolean isDefault() {
        return volume == 1f && !muted && !micMuted && micGain == 1f && !shareMic
                && outputType == 0 && !resetOnClose;
    }

    /** True when AudioFlinger needs a table entry (the routing and reset parts do not). */
    boolean needsNative() {
        return volume != 1f || muted || micMuted || micGain != 1f || shareMic;
    }

    void merge(@NonNull Bundle b) {
        if (b.containsKey(Swara.KEY_MUTED)) muted = b.getBoolean(Swara.KEY_MUTED);
        if (b.containsKey(Swara.KEY_VOLUME)) {
            final float v = clamp(b.getFloat(Swara.KEY_VOLUME), 0f, 1f);
            if (v < MIN_VOLUME) {
                // 0% is a mute, kept apart from the level so unmuting brings the level back
                // and the UI says "Muted" instead of a silent 0% nobody notices.
                muted = true;
            } else {
                volume = v;
            }
        }
        normalize();
        if (b.containsKey(Swara.KEY_MIC_MUTED)) micMuted = b.getBoolean(Swara.KEY_MIC_MUTED);
        if (b.containsKey(Swara.KEY_MIC_GAIN)) {
            micGain = clamp(b.getFloat(Swara.KEY_MIC_GAIN), 0f, Swara.MAX_MIC_GAIN);
        }
        if (b.containsKey(Swara.KEY_SHARE_MIC)) shareMic = b.getBoolean(Swara.KEY_SHARE_MIC);
        if (b.containsKey(Swara.KEY_OUTPUT_TYPE)) {
            outputType = b.getInt(Swara.KEY_OUTPUT_TYPE);
            outputAddress = Objects.requireNonNullElse(
                    b.getString(Swara.KEY_OUTPUT_ADDRESS), "");
        }
        if (b.containsKey(Swara.KEY_RESET_ON_CLOSE)) {
            resetOnClose = b.getBoolean(Swara.KEY_RESET_ON_CLOSE);
        }
    }

    void writeTo(@NonNull Bundle b) {
        b.putFloat(Swara.KEY_VOLUME, volume);
        b.putBoolean(Swara.KEY_MUTED, muted);
        b.putBoolean(Swara.KEY_MIC_MUTED, micMuted);
        b.putFloat(Swara.KEY_MIC_GAIN, micGain);
        b.putBoolean(Swara.KEY_SHARE_MIC, shareMic);
        b.putInt(Swara.KEY_OUTPUT_TYPE, outputType);
        b.putString(Swara.KEY_OUTPUT_ADDRESS, outputAddress);
        b.putBoolean(Swara.KEY_RESET_ON_CLOSE, resetOnClose);
        b.putBoolean(Swara.KEY_HAS_RULE, !isDefault());
    }

    /** The setParameters string for AudioFlinger (frameworks/av/services/swara). */
    @NonNull
    String toNative(int uid) {
        return String.format(Locale.ROOT,
                "swara_uid=%d;swara_gain=%.4f;swara_muted=%d;swara_mic_muted=%d;"
                        + "swara_mic_gain=%.4f;swara_share_mic=%d",
                uid, volume, muted ? 1 : 0, micMuted ? 1 : 0, micGain, shareMic ? 1 : 0);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "volume=%.2f muted=%b mic_muted=%b mic_gain=%.2f share_mic=%b output=%d/%s"
                        + " reset_on_close=%b",
                volume, muted, micMuted, micGain, shareMic, outputType, outputAddress,
                resetOnClose);
    }

    private static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? 1f : Math.max(lo, Math.min(hi, v));
    }
}
