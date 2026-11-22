/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.yasvara;

import android.content.ContentResolver;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * Canonical AOSP Settings keys used across Yasvara framework, SystemUI, Settings, and Laras.
 * Stored natively in AOSP Settings.System / Settings.Secure / Settings.Global with zero extra
 * ContentProvider or framework JAR overhead.
 */
public final class YasvaraSettings {
    private YasvaraSettings() {}

    public static final class System {
        private System() {}

        public static final String STATUS_BAR_CLOCK = "status_bar_clock";
        public static final String STATUS_BAR_CLOCK_AUTO_HIDE = "status_bar_clock_auto_hide";
        public static final String STATUS_BAR_AM_PM = "status_bar_am_pm";
        public static final String STATUS_BAR_BATTERY_STYLE = "status_bar_battery_style";
        public static final String STATUS_BAR_SHOW_BATTERY_PERCENT = "status_bar_show_battery_percent";
        public static final String STATUS_BAR_QUICK_QS_PULLDOWN = "status_bar_quick_qs_pulldown";
        public static final String DOUBLE_TAP_SLEEP_GESTURE = "double_tap_sleep_gesture";
        public static final String LOCKSCREEN_PIN_SCRAMBLE_LAYOUT = "lockscreen_pin_scramble_layout";
        public static final String LOCKSCREEN_ROTATION = "lockscreen_rotation";
        public static final String AUTO_BRIGHTNESS_ONE_SHOT = "auto_brightness_one_shot";
        public static final String PROXIMITY_ON_WAKE = "proximity_on_wake";
        public static final String ENABLE_TASKBAR = "enable_taskbar";
        public static final String NAVIGATION_BAR_HINT = "navigation_bar_hint";
        public static final String CLICK_PARTIAL_SCREENSHOT = "click_partial_screenshot";
        public static final String VOLUME_UP_AND_DOWN_MUTE = "volume_up_and_down_mute";
        public static final String SWAP_VOLUME_KEYS_ON_ROTATION = "swap_volume_keys_on_rotation";
        public static final String BUTTON_BACKLIGHT_ONLY_WHEN_PRESSED = "button_backlight_only_when_pressed";
        public static final String CAMERA_SLEEP_ON_RELEASE = "camera_sleep_on_release";
        public static final String CAMERA_LAUNCH = "camera_launch";
        public static final String TORCH_LONG_PRESS_POWER_GESTURE = "torch_long_press_power_gesture";
        public static final String TORCH_LONG_PRESS_POWER_TIMEOUT = "torch_long_press_power_timeout";
        public static final String KEY_BACK_LONG_PRESS_ACTION = "key_back_long_press_action";
        public static final String KEY_HOME_LONG_PRESS_ACTION = "key_home_long_press_action";
        public static final String KEY_HOME_DOUBLE_TAP_ACTION = "key_home_double_tap_action";
        public static final String KEY_MENU_ACTION = "key_menu_action";
        public static final String KEY_MENU_LONG_PRESS_ACTION = "key_menu_long_press_action";
        public static final String KEY_ASSIST_ACTION = "key_assist_action";
        public static final String KEY_ASSIST_LONG_PRESS_ACTION = "key_assist_long_press_action";
        public static final String KEY_APP_SWITCH_ACTION = "key_app_switch_action";
        public static final String KEY_APP_SWITCH_LONG_PRESS_ACTION = "key_app_switch_long_press_action";
        public static final String KEY_EDGE_LONG_SWIPE_ACTION = "key_edge_long_swipe_action";
        public static final String CAMERA_WAKE_SCREEN = "camera_wake_screen";
        public static final String HOME_WAKE_SCREEN = "home_wake_screen";
        public static final String BACK_WAKE_SCREEN = "back_wake_screen";
        public static final String MENU_WAKE_SCREEN = "menu_wake_screen";
        public static final String ASSIST_WAKE_SCREEN = "assist_wake_screen";
        public static final String APP_SWITCH_WAKE_SCREEN = "app_switch_wake_screen";
        public static final String VOLUME_WAKE_SCREEN = "volume_wake_screen";
        public static final String VOLUME_ANSWER_CALL = "volume_answer_call";
    }

    public static final class Secure {
        private Secure() {}

        public static final String BERRY_BLACK_THEME = "berry_black_theme";
        public static final String QS_SHOW_BRIGHTNESS_SLIDER = "qs_show_brightness_slider";
        public static final String QS_BRIGHTNESS_SLIDER_POSITION = "qs_brightness_slider_position";
        public static final String QS_SHOW_AUTO_BRIGHTNESS = "qs_show_auto_brightness";
        public static final String VOLUME_PANEL_ON_LEFT = "volume_panel_on_left";
        public static final String ADVANCED_REBOOT = "advanced_reboot";
        public static final String POWER_MENU_ACTIONS = "power_menu_actions";
        public static final String BUTTON_BRIGHTNESS = "button_brightness";
        public static final String BUTTON_BACKLIGHT_TIMEOUT = "button_backlight_timeout";
        public static final String KEYBOARD_BRIGHTNESS = "keyboard_brightness";
        public static final String RING_HOME_BUTTON_BEHAVIOR = "ring_home_button_behavior";
        public static final int RING_HOME_BUTTON_BEHAVIOR_DO_NOTHING = 0x1;
        public static final int RING_HOME_BUTTON_BEHAVIOR_ANSWER = 0x2;
        public static final int RING_HOME_BUTTON_BEHAVIOR_DEFAULT = RING_HOME_BUTTON_BEHAVIOR_DO_NOTHING;
    }

    public static final class Global {
        private Global() {}

        public static final String WAKE_WHEN_PLUGGED_OR_UNPLUGGED = "wake_when_plugged_or_unplugged";
        public static final String TRUST_RESTRICT_USB = "trust_restrict_usb";
    }

    /** Custom key action enum (replaces DeviceKeysConstants.Action). */
    public enum KeyAction {
        NOTHING,
        MENU,
        APP_SWITCH,
        SEARCH,
        VOICE_SEARCH,
        IN_APP_SEARCH,
        LAUNCH_CAMERA,
        SLEEP,
        LAST_APP,
        SPLIT_SCREEN,
        KILL_APP,
        PLAY_PAUSE_MUSIC,
        SCREENSHOT,
        PARTIAL_SCREENSHOT,
        CYCLE_RINGER_MODE;

        public static KeyAction fromIntSafe(int id) {
            if (id < 0 || id >= values().length) {
                return NOTHING;
            }
            return values()[id];
        }

        public static KeyAction fromSettings(ContentResolver cr, String setting, KeyAction def) {
            return fromIntSafe(Settings.System.getIntForUser(cr, setting, def.ordinal(),
                    UserHandle.USER_CURRENT));
        }
    }

    public static final int KEY_MASK_HOME = 0x01;
    public static final int KEY_MASK_BACK = 0x02;
    public static final int KEY_MASK_MENU = 0x04;
    public static final int KEY_MASK_ASSIST = 0x08;
    public static final int KEY_MASK_APP_SWITCH = 0x10;
    public static final int KEY_MASK_CAMERA = 0x20;
    public static final int KEY_MASK_VOLUME = 0x40;
}
