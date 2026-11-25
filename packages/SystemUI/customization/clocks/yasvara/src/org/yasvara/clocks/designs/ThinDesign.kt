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

/** Large light numerals on one line, the date beneath. */
object ThinDesign : ClockDesign("YAS_THIN", R.string.clock_thin_name, R.string.clock_thin_description) {
    override val smallAlign = Align.START
    override val smallScale = 1f
    override val aspect = 0.62f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val time = frame.textPaint(ClockFonts.flex, height * 0.6f, weight = frame.style.weight ?: 150f)
        time.textSize = minOf(time.textSize, fitWidth(time, frame.time.timeText, width))
        frame.gradient(time, 0f, height * 0.75f)
        canvas.drawCentered(frame.time.timeText, width / 2f, height * 0.4f, time)

        val date = frame.textPaint(ClockFonts.flex, height * 0.1f, color = frame.colors.muted, weight = 450f)
        canvas.drawCentered(frame.time.dateText, width / 2f, height * 0.86f, date)
    }

    override fun smallWidth(frame: ClockFrame, height: Float) = textLineWidth(frame, height, 150f)

    override fun drawSmall(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) =
        drawTextLine(canvas, frame, height, 150f, listOf(frame.time.timeText to frame.colors.main))
}
