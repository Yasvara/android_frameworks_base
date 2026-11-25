/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.systemui.shared.quickaffordance.shared.model

object KeyguardPreviewConstants {
    // Initialization bundle keys
    const val KEY_CLOCK_ID = "clock_id"
    const val KEY_CLOCK_STYLE = "clock_style"
    const val KEY_HIDE_CLOCK = "hide_clock"
    const val KEY_HIGHLIGHT_QUICK_AFFORDANCES = "highlight_quick_affordances"

    // TODO(b/422462849): Can SurfaceViewUtils share these constants?
    const val KEY_HOST_TOKEN = "host_token"
    const val KEY_VIEW_WIDTH = "width"
    const val KEY_VIEW_HEIGHT = "height"
    const val KEY_DISPLAY_ID = "display_id"
    const val KEY_COLORS = "wallpaper_colors"
    // Renders the lock screen as the device shows it, status bar and date line included.
    const val KEY_FULL_LOCK_SCREEN = "full_lock_screen"
    // A Messenger the host passes to learn where lock screen content sits in the preview.
    const val KEY_HOST_CALLBACK = "host_callback"
    const val MESSAGE_ID_LAYOUT = 1
    const val KEY_NOTIFICATION_TOP = "notification_top"

    // Yasvara preview protocol: hosts preview unsaved (draft) customizations and learn where
    // each lock screen element is drawn. A renderer reports the version it speaks in the
    // preview result; a host must not send these messages to one that reports none, because
    // any message a renderer does not know ends the preview.
    const val KEY_YAS_PROTOCOL = "yas_protocol"
    const val YAS_PROTOCOL_VERSION = 2
    // Host -> renderer: preview the customizations in the message data instead of the saved
    // ones. Keys that are absent keep their current draft.
    const val MESSAGE_ID_YAS_DRAFT = 0x59410001
    // Host -> renderer: drop every draft and preview the saved customizations again.
    const val MESSAGE_ID_YAS_DRAFT_CLEAR = 0x59410002
    // Renderer -> host: where lock screen elements are, in display pixels.
    const val MESSAGE_ID_YAS_BOUNDS = 0x59410101
    // A clock face as Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE stores it (JSON).
    const val KEY_YAS_CLOCK_FACE = "clock_face"
    // Where the clock sits: 0 top, 1 bottom (Settings.Secure yasvara_lock_clock_placement).
    const val KEY_YAS_CLOCK_PLACEMENT = "clock_placement"
    // A Bundle of element name to Rect.
    const val KEY_YAS_BOUNDS = "bounds"
    const val YAS_ELEMENT_CLOCK = "clock"

    // Message IDs
    const val MESSAGE_ID_DEFAULT_PREVIEW = 707
    const val MESSAGE_ID_HIDE_SMART_SPACE = 1111
    const val MESSAGE_ID_PREVIEW_QUICK_AFFORDANCE_SELECTED = 1988
    const val MESSAGE_ID_SLOT_SELECTED = 1337
    const val MESSAGE_ID_START_CUSTOMIZING_QUICK_AFFORDANCES = 214
    const val MESSAGE_ID_PREVIEW_CLOCK_SIZE = 1119

    // Message Keys
    const val KEY_HIDE_SMART_SPACE = "hide_smart_space"
    const val KEY_INITIALLY_SELECTED_SLOT_ID = "initially_selected_slot_id"
    const val KEY_QUICK_AFFORDANCE_ID = "quick_affordance_id"
    const val KEY_SLOT_ID = "slot_id"
    const val KEY_CLOCK_SIZE = "clock_size"

    const val KEYGUARD_QUICK_AFFORDANCE_ID_NONE = "none"
    const val CLOCK_SIZE_DYNAMIC = "clock_size_dynamic"
    const val CLOCK_SIZE_SMALL = "clock_size_small"
    const val DIM_ALPHA = 0.3f
}
