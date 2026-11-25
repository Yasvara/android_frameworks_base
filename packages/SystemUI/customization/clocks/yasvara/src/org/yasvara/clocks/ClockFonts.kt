/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import android.graphics.Typeface
import android.graphics.fonts.FontFamily
import android.graphics.fonts.SystemFonts

/**
 * The typefaces faces draw with. Only fonts that ship in the system image under an open licence
 * are used: Google Sans Flex and Roboto Flex (both variable) and the system monospace.
 */
object ClockFonts {
    /** Google Sans Flex, variable in weight, width, roundness and optical size. */
    val flex: Typeface by lazy { systemFont("GoogleSansFlex") ?: roboto }

    /** Roboto Flex, variable in weight, width and optical size. */
    val roboto: Typeface by lazy { systemFont("RobotoFlex") ?: Typeface.DEFAULT }

    val mono: Typeface by lazy { Typeface.MONOSPACE }

    /** Finds a font file by name among the installed fonts, so no path is hard-coded. */
    private fun systemFont(fileName: String): Typeface? {
        val font = SystemFonts.getAvailableFonts().firstOrNull {
            it.file?.name?.startsWith(fileName) == true
        } ?: return null
        return runCatching {
            Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                .setSystemFallback("sans-serif")
                .build()
        }.getOrNull()
    }

    /** Font variation settings for [weight], [width] and [roundness] (Google Sans Flex axes). */
    fun variation(weight: Float, width: Float = 100f, roundness: Float = 0f) =
        "'wght' ${weight.toInt()}, 'wdth' ${width.toInt()}, 'ROND' ${roundness.toInt()}"
}
