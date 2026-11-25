/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import kotlin.math.PI
import kotlin.math.sin
import org.yasvara.clocks.Align
import org.yasvara.clocks.ClockDesign
import org.yasvara.clocks.ClockFonts
import org.yasvara.clocks.ClockFrame
import org.yasvara.clocks.R

/**
 * Hours over minutes in Google Sans Flex, with every font axis the user's to set. A new minute
 * makes the digits breathe along the weight axis.
 */
object FlexDesign : ClockDesign("YAS_FLEX", R.string.clock_flex_name, R.string.clock_flex_description) {
    override val smallAlign = Align.START
    override val smallScale = 1f
    override val aspect = 1.1f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val base = frame.style.weight ?: 600f
        val breath = if (frame.change < 1f) sin(PI * frame.change).toFloat() * 250f else 0f
        val weight = (base + if (base > 600f) -breath else breath).coerceIn(100f, 1000f)
        val paint = frame.textPaint(ClockFonts.flex, height * 0.5f, weight = weight)
        paint.textSize = minOf(paint.textSize, fitWidth(paint, "00", width))
        frame.gradient(paint, 0f, height)
        val cap = paint.capHeight()
        val gap = (height - 2 * cap) / 3f
        canvas.drawCentered(frame.time.hourText.padStart(2, '0'), width / 2f, gap + cap / 2f, paint)
        canvas.drawCentered(frame.time.minuteText, width / 2f, height - gap - cap / 2f, paint)
    }

    override fun smallWidth(frame: ClockFrame, height: Float) = textLineWidth(frame, height, 600f)

    override fun drawSmall(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) =
        drawTextLine(canvas, frame, height, 600f, listOf(frame.time.timeText to frame.colors.main))
}
