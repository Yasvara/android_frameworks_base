/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import android.graphics.Paint
import org.yasvara.clocks.ClockDesign
import org.yasvara.clocks.ClockFrame
import org.yasvara.clocks.R

/**
 * An analog clock on an expressive dial: the dial is a Material shape filled with the
 * container colour, the hands are rounded strokes, and the minute hand carries the accent.
 */
object AnalogDesign : ClockDesign("YAS_ANALOG", R.string.clock_analog_name, R.string.clock_analog_description) {
    override val aspect = 1f
    override val supportsSeconds = true

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = width / 2f
        if (!frame.isDozing || frame.outline) {
            M3Shapes.draw(canvas, M3Shapes.cookie9, null, 1f, cx, cy, radius * 0.98f, frame.fillPaint(frame.colors.container))
        }
        val ticks = frame.fillPaint(frame.colors.muted)
        for (i in 0 until 12) {
            canvas.save()
            canvas.rotate(i * 30f, cx, cy)
            canvas.drawCircle(cx, cy - radius * 0.78f, radius * (if (i % 3 == 0) 0.035f else 0.018f), ticks)
            canvas.restore()
        }
        val t = frame.time
        val hourAngle = (t.hour24 % 12) * 30f + t.minute * 0.5f
        val minuteAngle = t.minute * 6f + t.second * 0.1f
        hand(canvas, frame, cx, cy, hourAngle, radius * 0.45f, radius * 0.09f, frame.colors.main)
        hand(canvas, frame, cx, cy, minuteAngle, radius * 0.68f, radius * 0.06f, frame.colors.accent)
        if (frame.style.seconds && !frame.isDozing) {
            hand(canvas, frame, cx, cy, t.second * 6f, radius * 0.72f, radius * 0.018f, frame.colors.main)
        }
        canvas.drawCircle(cx, cy, radius * 0.05f, frame.fillPaint(frame.colors.main))
    }

    private fun hand(
        canvas: Canvas, frame: ClockFrame, cx: Float, cy: Float,
        angle: Float, length: Float, thickness: Float, color: Int,
    ) {
        val paint = frame.fillPaint(color).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (frame.outline) thickness * 0.5f else thickness
            strokeCap = Paint.Cap.ROUND
        }
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawLine(cx, cy, cx, cy - length, paint)
        canvas.restore()
    }
}
