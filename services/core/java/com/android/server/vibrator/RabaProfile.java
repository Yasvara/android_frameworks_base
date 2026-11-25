/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.vibrator;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Raba: how each haptic feedback constant feels, as the user set it in Laras.
 *
 * <p>A constant belongs to a category and plays one wave of the chip's RAM bank (a "texture",
 * private primitive {@link #TEXTURE_BASE} + wave) at a strength. The HyperOS profile is stock
 * HyperOS's table on the same bank; Pixel leaves the platform table alone; Custom is HyperOS with
 * the categories the user edited replaced, each played with one texture at one strength (the
 * category's other constants keep their HyperOS weight against its lead). Master strength
 * scales every touch-class vibration on top, 0..1.5 like HyperOS's stepless level.
 *
 * <p>Stored as Settings.System {@link #SETTING}:
 * {@code {"profile":"hyperos|pixel|custom","master":1.0,"reskin":true,
 * "categories":{"taps":{"on":true,"wave":6,"strength":1.0},…}}}. Anything unknown or malformed
 * reads as the default, so an older Laras can't break it.
 */
public final class RabaProfile {

    public static final String SETTING = "yasvara_raba";

    public static final int PROFILE_HYPEROS = 0;
    public static final int PROFILE_PIXEL = 1;
    public static final int PROFILE_CUSTOM = 2;
    private static final String[] PROFILE_KEYS = {"hyperos", "pixel", "custom"};

    /** Primitive id of bank wave w is TEXTURE_BASE + w; mirrors the HAL's StreamPlayer. */
    public static final int TEXTURE_BASE = 0x100;
    public static final int WAVES = 11;
    public static final float MASTER_MAX = 1.5f;
    public static final float STRENGTH_MIN = 0.05f;

    public static final int TAPS = 0;
    public static final int KEYBOARD = 1;
    public static final int SWITCHES = 2;
    public static final int TICKS = 3;
    public static final int EDGES = 4;
    public static final int PRESS = 5;
    public static final int GESTURES = 6;
    public static final int CONFIRM = 7;
    public static final String[] CATEGORY_KEYS =
            {"taps", "keyboard", "switches", "ticks", "edges", "press", "gestures", "confirm"};

    // android.view.HapticFeedbackConstants, mirrored to keep this class free of the platform.
    static final int LONG_PRESS = 0;
    static final int VIRTUAL_KEY = 1;
    static final int KEYBOARD_TAP = 3;
    static final int CLOCK_TICK = 4;
    static final int CALENDAR_DATE = 5;
    static final int CONTEXT_CLICK = 6;
    static final int KEYBOARD_RELEASE = 7;
    static final int VIRTUAL_KEY_RELEASE = 8;
    static final int TEXT_HANDLE_MOVE = 9;
    static final int DRAG_CROSSING = 11;
    static final int GESTURE_START = 12;
    static final int GESTURE_END = 13;
    static final int CONFIRM_ID = 16;
    static final int REJECT = 17;
    static final int SCROLL_TICK = 18;
    static final int SCROLL_ITEM_FOCUS = 19;
    static final int SCROLL_LIMIT = 20;
    static final int TOGGLE_ON = 21;
    static final int TOGGLE_OFF = 22;
    static final int GESTURE_THRESHOLD_ACTIVATE = 23;
    static final int GESTURE_THRESHOLD_DEACTIVATE = 24;
    static final int DRAG_START = 25;
    static final int SEGMENT_TICK = 26;
    static final int SEGMENT_FREQUENT_TICK = 27;
    private static final int CONSTANTS = 28;

    // HyperOS's three strengths (sys.haptic.<type>=effect,0|1|2).
    private static final float S0 = 0.375f;
    private static final float S1 = 0.625f;
    private static final float S2 = 1f;

    /**
     * {constant, category, wave, lead}: stock HyperOS's sys.haptic table, translated to the AOSP
     * constants (HyperOS effect N is wave N + 1). The lead is the row a Custom category is shown
     * and seeded as; the strengths are in {@link #STRENGTHS}, same order.
     */
    private static final int[][] TABLE = {
            {VIRTUAL_KEY, TAPS, 6, 1},              // down
            {VIRTUAL_KEY_RELEASE, TAPS, 6, 0},
            {CONTEXT_CLICK, TAPS, 6, 0},            // tap.light
            {CALENDAR_DATE, TAPS, 4, 0},            // tap.normal
            {KEYBOARD_TAP, KEYBOARD, 8, 1},         // keyboard
            {KEYBOARD_RELEASE, KEYBOARD, 8, 0},
            {TOGGLE_ON, SWITCHES, 10, 1},           // switch
            {TOGGLE_OFF, SWITCHES, 10, 0},
            {SEGMENT_TICK, TICKS, 6, 1},            // mesh.light
            {SEGMENT_FREQUENT_TICK, TICKS, 6, 0},
            {CLOCK_TICK, TICKS, 6, 0},              // gear_light
            {TEXT_HANDLE_MOVE, TICKS, 6, 0},
            {SCROLL_TICK, TICKS, 6, 0},
            {SCROLL_ITEM_FOCUS, TICKS, 6, 0},
            {DRAG_CROSSING, TICKS, 6, 0},
            {SCROLL_LIMIT, EDGES, 8, 1},            // scroll.edge
            {LONG_PRESS, PRESS, 1, 1},              // long.press
            {DRAG_START, PRESS, 3, 0},              // pickup
            {GESTURE_THRESHOLD_ACTIVATE, GESTURES, 3, 1},  // trigger.drawer
            {GESTURE_THRESHOLD_DEACTIVATE, GESTURES, 3, 0},
            {GESTURE_START, GESTURES, 5, 0},        // hold
            {GESTURE_END, GESTURES, 3, 0},
            {CONFIRM_ID, CONFIRM, 7, 1},            // popup.normal
            {REJECT, CONFIRM, 2, 0},                // boundary_time
    };
    private static final float[] STRENGTHS = {
            S2, S0, S2, S2,
            S2, S0,
            S2, S1,
            S1, S0, S2, S0, S0, S1, S1,
            S0,
            S1, S2,
            S0, 0.25f, S0, S0,
            S2, S2,
    };

    /** Under the HyperOS feel, standard primitives other apps compose play these waves. */
    private static final int[][] RESKIN = {
            {1, 6},  // CLICK: tap
            {2, 4},  // THUD: deep
            {7, 8},  // TICK: light
            {8, 3},  // LOW_TICK: firm, at f0
    };

    /** One constant's resolved feel. */
    public static final class Tone {
        public static final Tone OFF = new Tone(0, 0f);
        public final int wave;
        public final float scale;

        Tone(int wave, float scale) {
            this.wave = wave;
            this.scale = scale;
        }

        public boolean isOff() {
            return wave == 0;
        }

        @Override
        public String toString() {
            return isOff() ? "off" : String.format(Locale.ROOT, "wave %d @ %.2f", wave, scale);
        }
    }

    /** A category the user edited in Custom. */
    public static final class Category {
        public final boolean on;
        public final int wave;
        public final float strength;

        public Category(boolean on, int wave, float strength) {
            this.on = on;
            this.wave = wave;
            this.strength = strength;
        }
    }

    public static final RabaProfile DEFAULT =
            new RabaProfile(PROFILE_HYPEROS, 1f, true, new Category[CATEGORY_KEYS.length]);

    private final int mProfile;
    private final float mMaster;
    private final boolean mReskin;
    private final Category[] mCategories;
    /** By constant; null where the platform table applies. */
    private final Tone[] mTones = new Tone[CONSTANTS];

    public RabaProfile(int profile, float master, boolean reskin, Category[] categories) {
        mProfile = profile;
        mMaster = clamp(master, 0f, MASTER_MAX);
        mReskin = reskin;
        mCategories = categories.clone();
        if (profile == PROFILE_PIXEL) return;
        for (int i = 0; i < TABLE.length; i++) {
            final int[] row = TABLE[i];
            final Category c = profile == PROFILE_CUSTOM ? mCategories[row[1]] : null;
            if (c == null) {
                mTones[row[0]] = new Tone(row[2], STRENGTHS[i]);
            } else if (!c.on) {
                mTones[row[0]] = Tone.OFF;
            } else {
                final float weight = STRENGTHS[i] / STRENGTHS[leadRow(row[1])];
                mTones[row[0]] = new Tone(c.wave, clamp(c.strength * weight, STRENGTH_MIN, 1f));
            }
        }
    }

    public int profile() {
        return mProfile;
    }

    public float master() {
        return mMaster;
    }

    /**
     * How much longer and harder textures play, 0..1: master strength above 100 %. Primitive scale
     * ends at full scale, and the bank waves are full scale already, so this is where 100–150 %
     * goes for them.
     */
    public float textureBoost() {
        if (mProfile == PROFILE_PIXEL) return 0f;
        return clamp((mMaster - 1f) / (MASTER_MAX - 1f), 0f, 1f);
    }

    /** Whether standard primitives play bank waves: the HyperOS feel in other apps too. */
    public boolean reskin() {
        return mReskin && mProfile != PROFILE_PIXEL;
    }

    /** {primitive, wave} pairs to apply when {@link #reskin()}. */
    public static int[][] reskinTable() {
        return RESKIN;
    }

    /** The feel of a constant, or null when the platform table applies. */
    public Tone tone(int constant) {
        return constant >= 0 && constant < CONSTANTS ? mTones[constant] : null;
    }

    /** What a category shows before the user edits it: its lead row's wave and strength. */
    public static Category hyperOsCategory(int category) {
        final int lead = leadRow(category);
        return new Category(true, TABLE[lead][2], STRENGTHS[lead]);
    }

    private static int leadRow(int category) {
        for (int i = 0; i < TABLE.length; i++) {
            if (TABLE[i][1] == category && TABLE[i][3] == 1) return i;
        }
        throw new IllegalStateException("No lead for category " + category);
    }

    public static RabaProfile parse(String json) {
        if (json == null || json.isEmpty()) return DEFAULT;
        try {
            final JSONObject o = new JSONObject(json);
            int profile = PROFILE_HYPEROS;
            final String key = o.optString("profile", PROFILE_KEYS[PROFILE_HYPEROS]);
            for (int i = 0; i < PROFILE_KEYS.length; i++) {
                if (PROFILE_KEYS[i].equals(key)) profile = i;
            }
            final float master = finite(o.optDouble("master", 1.0), 1f);
            final Category[] categories = new Category[CATEGORY_KEYS.length];
            final JSONObject cats = o.optJSONObject("categories");
            if (cats != null) {
                for (int i = 0; i < CATEGORY_KEYS.length; i++) {
                    final JSONObject c = cats.optJSONObject(CATEGORY_KEYS[i]);
                    if (c == null) continue;
                    final int wave = c.optInt("wave", 0);
                    if (wave < 1 || wave > WAVES) continue;
                    categories[i] = new Category(c.optBoolean("on", true), wave,
                            clamp(finite(c.optDouble("strength", 1.0), 1f), STRENGTH_MIN, 1f));
                }
            }
            return new RabaProfile(profile, master, o.optBoolean("reskin", true), categories);
        } catch (JSONException e) {
            return DEFAULT;
        }
    }

    @Override
    public String toString() {
        final StringBuilder b = new StringBuilder(String.format(Locale.ROOT,
                "%s master %.2f reskin %b boost %.2f", PROFILE_KEYS[mProfile], mMaster, reskin(),
                textureBoost()));
        for (int c = 0; c < CONSTANTS; c++) {
            if (mTones[c] != null) b.append("\n  ").append(c).append(": ").append(mTones[c]);
        }
        return b.toString();
    }

    private static float finite(double v, float fallback) {
        return Double.isFinite(v) ? (float) v : fallback;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
