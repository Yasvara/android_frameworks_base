/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle

/** The palette a face takes its main colour from. */
enum class ColorRole { PRIMARY, SECONDARY, TERTIARY, NEUTRAL, GRADIENT, MONO }

/** How a face looks on the always-on display. */
enum class AodLook { OUTLINE, THIN, SAME }

enum class Align { START, CENTER, END }

/**
 * The user's customization of a face, read from the clock's axes: SystemUI stores them in the
 * clock face setting and hands them to previews, so no other storage is needed. Axis values are
 * floats; enums are stored by ordinal. Every value is clamped, so a hand-edited setting cannot
 * break a face.
 */
data class ClockStyle(
    val scale: Float = 1f,
    val color: ColorRole = ColorRole.PRIMARY,
    /** Lightness 0 (black) to 100 (white), or null to follow the wallpaper. */
    val tone: Float? = null,
    /** Font weight 100..1000, or null for the face's own. */
    val weight: Float? = null,
    /** Font width 25..151, or null for the face's own. */
    val width: Float? = null,
    /** Font roundness 0..100. */
    val roundness: Float = 0f,
    val seconds: Boolean = false,
    val aod: AodLook = AodLook.OUTLINE,
    val align: Align = Align.CENTER,
    /** Where the small clock (shown with notifications) sits, or null for the design's own. */
    val smallAlign: Align? = null,
    /** The small clock's size against the stock small clock, or null for the design's own. */
    val smallScale: Float? = null,
) {
    companion object {
        const val SCALE = "yas.scale"
        const val COLOR = "yas.color"
        const val TONE = "yas.tone"
        const val WEIGHT = "wght"
        const val WIDTH = "wdth"
        const val ROUNDNESS = "ROND"
        const val SECONDS = "yas.seconds"
        const val AOD = "yas.aod"
        const val ALIGN = "yas.align"
        const val SMALL_ALIGN = "yas.small.align"
        const val SMALL_SCALE = "yas.small.scale"

        /** Axes that move or resize the small clock, so its constraints must be rebuilt. */
        val LAYOUT_AXES = listOf(SMALL_ALIGN, SMALL_SCALE)

        val SCALE_RANGE = 0.6f..1.4f
        val SMALL_SCALE_RANGE = 0.8f..2.4f

        fun from(axes: ClockAxisStyle) = ClockStyle(
            scale = axes[SCALE]?.coerceIn(SCALE_RANGE) ?: 1f,
            color = enumAt(axes[COLOR], ColorRole.PRIMARY),
            tone = axes[TONE]?.takeIf { it >= 0f }?.coerceAtMost(100f),
            weight = axes[WEIGHT]?.coerceIn(100f, 1000f),
            width = axes[WIDTH]?.coerceIn(25f, 151f),
            roundness = axes[ROUNDNESS]?.coerceIn(0f, 100f) ?: 0f,
            seconds = (axes[SECONDS] ?: 0f) >= 0.5f,
            aod = enumAt(axes[AOD], AodLook.OUTLINE),
            align = enumAt(axes[ALIGN], Align.CENTER),
            smallAlign = axes[SMALL_ALIGN]?.let { enumAt(it, Align.START) },
            smallScale = axes[SMALL_SCALE]?.coerceIn(SMALL_SCALE_RANGE),
        )

        private inline fun <reified E : Enum<E>> enumAt(value: Float?, default: E): E {
            val index = value?.toInt() ?: return default
            return enumValues<E>().getOrElse(index) { default }
        }
    }
}
