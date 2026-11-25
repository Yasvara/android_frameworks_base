/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import org.yasvara.clocks.Align
import org.yasvara.clocks.ClockDesign
import org.yasvara.clocks.ClockFonts
import org.yasvara.clocks.ClockFrame
import org.yasvara.clocks.R

/** Hours stacked over minutes, flush left, the hour in the accent colour. */
object AccentDesign : ClockDesign("YAS_ACCENT", R.string.clock_accent_name, R.string.clock_accent_description) {
    override val smallAlign = Align.START
    override val smallScale = 1f
    override val aspect = 0.95f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val weight = frame.style.weight ?: 700f
        val hour = frame.textPaint(ClockFonts.flex, height * 0.46f, color = frame.colors.accent, weight = weight)
        val minute = frame.textPaint(ClockFonts.flex, height * 0.46f, weight = weight)
        frame.gradient(minute, height / 2f, height)
        val size = minOf(hour.textSize, fitWidth(hour, "00", width * 0.8f))
        hour.textSize = size
        minute.textSize = size
        val cap = hour.capHeight()
        val gap = (height - 2 * cap) / 3f
        val left = width * 0.1f
        canvas.drawText(frame.time.hourText.padStart(2, '0'), left, gap + cap, hour)
        canvas.drawText(frame.time.minuteText, left, height - gap, minute)
    }

    override fun smallWidth(frame: ClockFrame, height: Float) = textLineWidth(frame, height, 700f)

    override fun drawSmall(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) =
        drawTextLine(
            canvas, frame, height, 700f,
            listOf(frame.time.hourText to frame.colors.accent, ":${frame.time.minuteText}" to frame.colors.main),
        )
}
