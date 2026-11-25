/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.theme

/** Settings keys owned by the colour and theme features. */
object YasThemeKeys {
    /** Secure: the colour spec JSON; absent means stock AOSP colours. */
    const val COLOR = "yasvara_color"

    /** Secure: the theme component JSON; absent means every component at its default. */
    const val THEME = "yasvara_theme"
}

/**
 * The theme apply transition, as `content://com.android.systemui.customization` calls. Laras
 * keeps a copy of these values; they must not change once shipped.
 */
object YasTransitionContract {
    /**
     * Covers the display with a snapshot of itself. Extras: [KEY_X], [KEY_Y] (display px, the
     * reveal centre), [KEY_TIMEOUT_MS], [KEY_EXPECT_THEME_CHANGE], [KEY_CALLBACK]. Returns
     * [KEY_TOKEN] (0 when refused) and [KEY_COVER] (false when no snapshot could be shown, in
     * which case the caller hides the change on its own).
     */
    const val METHOD_BEGIN = "yas_transition_begin"

    /** The caller has drawn its first frame in the new theme. Extras: [KEY_TOKEN]. */
    const val METHOD_READY = "yas_transition_ready"

    /**
     * Returns the state SystemUI has applied: [KEY_FINGERPRINT]. Also sent as [MESSAGE_END] to
     * [KEY_CALLBACK] when the reveal starts, with [KEY_TOKEN] and [KEY_FINGERPRINT].
     */
    const val METHOD_END = "yas_transition_end"

    const val MESSAGE_END = 0x59410201

    const val KEY_X = "x"
    const val KEY_Y = "y"
    const val KEY_TIMEOUT_MS = "timeout_ms"
    const val KEY_EXPECT_THEME_CHANGE = "expect_theme_change"
    const val KEY_CALLBACK = "callback"
    const val KEY_TOKEN = "token"
    const val KEY_COVER = "cover"
    const val KEY_FINGERPRINT = "fp"
}
