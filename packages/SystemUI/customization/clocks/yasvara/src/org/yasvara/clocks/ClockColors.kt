/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import android.content.Context
import android.graphics.Color
import com.android.systemui.monet.ColorScheme
import com.android.systemui.monet.TonalPalette
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig

/**
 * The colours a face draws with. On a dark wallpaper ([ThemeConfig.isDarkTheme]) content is
 * light and containers are deep; on a light wallpaper the other way round.
 */
class ClockColors(
    /** The time itself. */
    val main: Int,
    /** A second colour for accents, such as one of the digits. */
    val accent: Int,
    /** Secondary text such as the date. */
    val muted: Int,
    /** Fills behind content: bubbles, shapes, labels. */
    val container: Int,
    /** Content drawn on [container]. */
    val onContainer: Int,
    /** Where [ColorRole.GRADIENT] ends; equal to [main] otherwise. */
    val mainEnd: Int,
) {
    /** These colours on the way to the always-on display, [fraction] 1 being fully there. */
    fun dozed(fraction: Float, aod: Int): ClockColors {
        if (fraction <= 0f) return this
        return ClockColors(
            main = lerp(main, aod, fraction),
            accent = lerp(accent, aod, fraction),
            muted = lerp(muted, AOD_MUTED, fraction),
            container = lerp(container, Color.TRANSPARENT, fraction),
            onContainer = lerp(onContainer, aod, fraction),
            mainEnd = lerp(mainEnd, aod, fraction),
        )
    }

    companion object {
        private const val AOD_MUTED = 0xFFB0B0B0.toInt()

        fun resolve(context: Context, theme: ThemeConfig, style: ClockStyle): ClockColors {
            val dark = theme.isDarkTheme
            val seed = theme.seedColor
                ?: context.resources.getColor(android.R.color.system_accent1_500, null)
            val scheme = ColorScheme(seed, dark)
            val palette = when (style.color) {
                ColorRole.SECONDARY -> scheme.accent2
                ColorRole.TERTIARY -> scheme.accent3
                ColorRole.NEUTRAL, ColorRole.MONO -> scheme.neutral1
                ColorRole.PRIMARY, ColorRole.GRADIENT -> scheme.accent1
            }
            // A shade runs from 0 (white) to 1000 (black); tone is lightness in percent.
            val mainShade = style.tone?.let { (100f - it) * 10f } ?: if (dark) 100f else 700f
            val main = when (style.color) {
                ColorRole.MONO -> if (dark) Color.WHITE else Color.BLACK
                else -> palette.getAtTone(mainShade)
            }
            val accentPalette = if (style.color == ColorRole.TERTIARY) scheme.accent1 else scheme.accent3
            return ClockColors(
                main = main,
                accent = accentPalette.getAtTone(if (dark) 200f else 600f),
                muted = scheme.neutral2.getAtTone(if (dark) 200f else 600f),
                container = palette.containerOf(dark),
                onContainer = palette.getAtTone(if (dark) 100f else 900f),
                mainEnd = if (style.color == ColorRole.GRADIENT) {
                    scheme.accent3.getAtTone(mainShade)
                } else {
                    main
                },
            )
        }

        private fun TonalPalette.containerOf(dark: Boolean) = getAtTone(if (dark) 700f else 150f)

        fun lerp(from: Int, to: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            fun ch(shift: Int) =
                (((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * f)
                    .toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
