/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.app.yasvara;

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * Constants and binder lookup for Layang floating windows. Windows are owned by
 * WM core ({@link LayangTaskState}), animated by the Shell LayangController and managed
 * through {@link ILayangManager}.
 *
 * @hide
 */
public final class Layang {
    public static final String SERVICE_NAME = "yasvara_layang";
    public static final String PERMISSION_MANAGE = "yasvara.permission.MANAGE_LAYANG";

    // ---- Results of the open* calls ----
    public static final int RESULT_OK = 0;
    /** Layang is switched off (Settings.Secure yasvara_layang_enabled = 0, or safe mode). */
    public static final int RESULT_DISABLED = 1;
    /** The app is blocked by a rule or the default blocklist. */
    public static final int RESULT_BLOCKED = 2;
    /** No such app, activity or task. */
    public static final int RESULT_NOT_FOUND = 3;
    /** The start was refused by the system (background start, permissions). */
    public static final int RESULT_FAILED = 4;

    // ---- Open options (ILayangManager#openApp opts) ----
    /** float: scale s of the virtual screen (reflow apps: fraction of the display width). */
    public static final String OPT_SCALE = "scale";
    /** int px, int px: where the window's top-left corner goes; default is placement. */
    public static final String OPT_X = "x";
    public static final String OPT_Y = "y";
    /** boolean: open as a mini window. */
    public static final String OPT_MINI = "mini";
    /** Rect: where the open animation morphs from (icon, card, notification). */
    public static final String OPT_SOURCE_BOUNDS = "source_bounds";
    /** String: package hint for a pre-launch source-bounds handoff to Shell. */
    public static final String OPT_SOURCE_PKG = "source_pkg";

    // ---- Per-app rules (ILayangManager#getRules / setRules) ----
    /** boolean: launches of this app always open floating. */
    public static final String RULE_ALWAYS = "always";
    /** boolean: never float this app. */
    public static final String RULE_BLOCKED = "blocked";
    /** int: reflow (lowered density, relayout on resize) instead of scaling; 1 on, else off. */
    public static final String RULE_READABLE = "readable";
    /** int: game aspect-lock override, -1 default, 0 unlocked, 1 locked. */
    public static final String RULE_ASPECT_LOCK = "aspect_lock";
    /** String: default size "S", "M" or "L"; absent means the global default. */
    public static final String RULE_SIZE = "size";
    /** boolean: treat as a game even without CATEGORY_GAME (or never, when false). */
    public static final String RULE_GAME = "game";
    /** int: per-app floating FPS cap (0 = auto/uncapped, else 30/45/60/90/120). */
    public static final String RULE_FPS = "fps";
    /** float: per-app virtual/render resolution scale (1.0 = native, else 0.85/0.75/0.60). */
    public static final String RULE_RES_SCALE = "res_scale";
    /** boolean, setRules only: forget the remembered position and size. */
    public static final String RULE_RESET_GEOMETRY = "reset_geometry";

    // ---- Settings.Secure keys (§8) ----
    public static final String KEY_ENABLED = "yasvara_layang_enabled";
    public static final String KEY_CHIP = "yasvara_layang_chip";
    public static final String KEY_GAME_ASPECT_LOCK = "yasvara_layang_game_aspect_lock";
    public static final String KEY_DEFAULT_SIZE = "yasvara_layang_default_size";
    public static final String KEY_MINI_GAME_FPS = "yasvara_layang_mini_game_fps";
    public static final String KEY_AUTO_PARK = "yasvara_layang_auto_park";
    public static final String KEY_MULTI_AUDIO_FOCUS = "yasvara_layang_multi_audio_focus";
    public static final String KEY_GHOST_ALPHA = "yasvara_layang_ghost_alpha";
    /** Thickness of the resize handles in dp, 2/3 outside the window and 1/3 inside. */
    public static final String KEY_GRIP_DP = "yasvara_layang_grip_dp";
    /** Width of a mini (bubble) window along its short side in dp (72..140, default 96). */
    public static final String KEY_MINI_WIDTH_DP = "yasvara_layang_mini_width_dp";
    public static final String KEY_REMEMBER = "yasvara_layang_remember";

    // ---- Geometry (§4.1, §6.4) ----
    public static final float MIN_SCALE = 0.05f;
    public static final float MAX_SCALE = 0.95f;
    public static final float SCALE_S = 0.35f;
    public static final float SCALE_M = 0.45f;
    public static final float SCALE_L = 0.55f;
    /** Default scales of a portrait app window, S/M/L. */
    public static final float APP_SCALE_S = 0.45f;
    public static final float APP_SCALE_M = 0.55f;
    public static final float APP_SCALE_L = 0.65f;
    public static final int GRIP_DP_DEFAULT = 24;
    public static final int GRIP_DP_MIN = 16;
    public static final int GRIP_DP_MAX = 40;
    /** Width of a mini window in dp (legacy constant kept for binary compatibility). */
    public static final int MINI_WIDTH_DP = 132;
    /** Default, minimum and maximum short-side width of a mini/bubble window in dp. */
    public static final int MINI_WIDTH_DP_DEFAULT = 96;
    public static final int MINI_WIDTH_DP_MIN = 72;
    public static final int MINI_WIDTH_DP_MAX = 140;
    /** Maximum long/short aspect ratio of a mini window so 9:20 apps stay compact bubbles. */
    public static final float MINI_MAX_ASPECT = 1.65f;
    /** Screen margin of placement in dp. */
    public static final int MARGIN_DP = 12;
    /**
     * Smallest width an app is laid out for: a reflow window's at its lowered density, a
     * reshaped window's virtual screen.
     */
    public static final int READABLE_MIN_SW_DP = 320;
    /** Untrusted-touch limit: a pass-through window must be at most this opaque (F12). */
    public static final float MAX_GHOST_ALPHA = 0.8f;
    /** Most game processes that get top-app priority at once (§5.1). */
    public static final int MAX_BOOSTED_GAMES = 3;

    private Layang() {}

    /** Settings value of {@link #KEY_DEFAULT_SIZE}: 0 = S, 1 = M, 2 = L. */
    public static String sizeForSetting(int value) {
        return value == 0 ? "S" : value == 2 ? "L" : "M";
    }

    /** Scale for a size key "S", "M" or "L"; M otherwise. */
    public static float scaleForSize(@Nullable String size) {
        if ("S".equals(size)) return SCALE_S;
        if ("L".equals(size)) return SCALE_L;
        return SCALE_M;
    }

    public static float clampScale(float s) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    }

    /** Binder handle, or null when the service is not running. Never throws. */
    @Nullable
    public static ILayangManager service() {
        try {
            IBinder b = ServiceManager.getService(SERVICE_NAME);
            return b == null ? null : ILayangManager.Stub.asInterface(b);
        } catch (Throwable t) {
            return null;
        }
    }
}
