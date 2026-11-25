/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app.yasvara;

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * Swara per-app audio: constants shared by SwaraService, SystemUI and Laras.
 *
 * @hide
 */
public final class Swara {
    private Swara() {}

    public static final String SERVICE_NAME = "yasvara_swara";
    public static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_SWARA";

    // Rule keys (§6).
    /** float 0..1, playback volume on top of the stream volume. */
    public static final String KEY_VOLUME = "volume";
    public static final String KEY_MUTED = "muted";
    public static final String KEY_MIC_MUTED = "mic_muted";
    /** float 0..2, mic gain; above 1 it soft-clips. */
    public static final String KEY_MIC_GAIN = "mic_gain";
    /** Record next to a call or another app's capture. */
    public static final String KEY_SHARE_MIC = "share_mic_in_call";
    /** AudioDeviceInfo.TYPE_* for media and game audio, 0 for the system's choice. */
    public static final String KEY_OUTPUT_TYPE = "output_type";
    public static final String KEY_OUTPUT_ADDRESS = "output_address";
    public static final String KEY_RESET_ON_CLOSE = "reset_on_close";

    // getApps entries.
    public static final String KEY_PACKAGE = "package";
    public static final String KEY_UID = "uid";
    public static final String KEY_PLAYING = "playing";
    public static final String KEY_RECORDING = "recording";
    /** The rule differs from the defaults. */
    public static final String KEY_HAS_RULE = "has_rule";
    /** The chosen output device is connected (true when none is chosen). */
    public static final String KEY_OUTPUT_AVAILABLE = "output_available";

    public static final float MAX_MIC_GAIN = 2f;

    @Nullable
    public static ISwaraManager get() {
        final IBinder b = ServiceManager.getService(SERVICE_NAME);
        return b != null ? ISwaraManager.Stub.asInterface(b) : null;
    }
}
