/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import android.graphics.RectF
import org.yasvara.clocks.ClockDesign
import org.yasvara.clocks.ClockFonts
import org.yasvara.clocks.ClockFrame
import org.yasvara.clocks.R

/** The time on one large rounded label and the date on a smaller one beneath it. */
object LabelDesign : ClockDesign("YAS_LABEL", R.string.clock_label_name, R.string.clock_label_description) {
    override val aspect = 0.62f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val fill = frame.fillPaint(frame.colors.container)
        val timeBox = RectF(0f, 0f, width, height * 0.66f)
        if (!frame.isDozing || frame.outline) {
            canvas.drawRoundRect(timeBox, timeBox.height() / 2f, timeBox.height() / 2f, fill)
        }
        val time = frame.textPaint(
            ClockFonts.flex, timeBox.height() * 0.62f, color = frame.colors.onContainer,
            weight = frame.style.weight ?: 650f,
        )
        time.textSize = minOf(time.textSize, fitWidth(time, frame.time.timeText, width * 0.78f))
        canvas.drawCentered(frame.time.timeText, width / 2f, timeBox.centerY(), time)

        val date = frame.textPaint(ClockFonts.flex, height * 0.12f, color = frame.colors.onContainer, weight = 550f)
        val dateWidth = date.measureText(frame.time.dateText) + height * 0.2f
        val dateBox = RectF((width - dateWidth) / 2f, height * 0.72f, (width + dateWidth) / 2f, height)
        val dateFill = frame.fillPaint(frame.colors.accent).apply { alpha = if (frame.isDozing) 0 else 90 }
        canvas.drawRoundRect(dateBox, dateBox.height() / 2f, dateBox.height() / 2f, dateFill)
        canvas.drawCentered(frame.time.dateText, width / 2f, dateBox.centerY(), date)
    }
}
