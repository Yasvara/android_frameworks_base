/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import org.yasvara.clocks.ClockDesign
import org.yasvara.clocks.ClockFonts
import org.yasvara.clocks.ClockFrame
import org.yasvara.clocks.R

/**
 * An oversized, condensed hour with the minutes set over its lower half. Made to be partly
 * covered by a depth wallpaper's subject and still read at a glance.
 */
object StackDesign : ClockDesign("YAS_STACK", R.string.clock_stack_name, R.string.clock_stack_description) {
    override val smallScale = 2.2f
    override val aspect = 1.15f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val hour = frame.textPaint(
            ClockFonts.flex, height, color = frame.colors.container,
            weight = frame.style.weight ?: 800f,
        ).apply { fontVariationSettings = ClockFonts.variation(frame.style.weight ?: 800f, frame.style.width ?: 30f, frame.style.roundness) }
        if (frame.isDozing) hour.color = frame.colors.muted
        val text = frame.time.hourText.padStart(2, '0')
        hour.textSize = minOf(fitWidth(hour, text, width * 0.98f), height * 1.2f)
        canvas.drawCentered(text, width / 2f, height * 0.46f, hour)

        val minutes = frame.textPaint(ClockFonts.flex, height * 0.3f, weight = 600f)
        frame.gradient(minutes, height * 0.55f, height)
        canvas.drawCentered(frame.time.minuteText, width / 2f, height * 0.78f, minutes)
    }
}
