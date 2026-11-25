/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin
import org.yasvara.clocks.ClockDesign
import org.yasvara.clocks.ClockFonts
import org.yasvara.clocks.ClockFrame
import org.yasvara.clocks.R

/**
 * The time inside a ring that fills as the day passes, with an optional seconds dot running
 * around it.
 */
object RingDesign : ClockDesign("YAS_RING", R.string.clock_ring_name, R.string.clock_ring_description) {
    override val aspect = 1f
    override val supportsSeconds = true

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val stroke = width * 0.045f
        val oval = RectF(stroke, stroke, width - stroke, height - stroke)
        val track = frame.fillPaint(frame.colors.container).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val progress = frame.fillPaint(frame.colors.accent).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        if (!frame.isDozing) canvas.drawArc(oval, 0f, 360f, false, track)
        canvas.drawArc(oval, -90f, 360f * frame.time.dayFraction, false, progress)

        if (frame.style.seconds && !frame.isDozing) {
            val angle = Math.toRadians(frame.time.second * 6.0 - 90.0)
            val r = oval.width() / 2f
            canvas.drawCircle(
                width / 2f + r * cos(angle).toFloat(), height / 2f + r * sin(angle).toFloat(),
                stroke * 0.7f, frame.fillPaint(frame.colors.main),
            )
        }

        val time = frame.textPaint(ClockFonts.flex, height * 0.26f, weight = frame.style.weight ?: 600f)
        time.textSize = minOf(time.textSize, fitWidth(time, frame.time.timeText, width * 0.66f))
        frame.gradient(time, height * 0.3f, height * 0.6f)
        canvas.drawCentered(frame.time.timeText, width / 2f, height * 0.47f, time)
        val date = frame.textPaint(ClockFonts.flex, height * 0.07f, color = frame.colors.muted, weight = 500f)
        canvas.drawCentered(frame.time.dateText, width / 2f, height * 0.64f, date)
    }
}
