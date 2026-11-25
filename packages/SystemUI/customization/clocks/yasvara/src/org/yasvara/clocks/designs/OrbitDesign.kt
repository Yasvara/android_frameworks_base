/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.yasvara.clocks.ClockDesign
import org.yasvara.clocks.ClockFonts
import org.yasvara.clocks.ClockFrame
import org.yasvara.clocks.R

/** Hours over minutes beside a ring that carries the date, the ring filling as the month goes. */
object OrbitDesign : ClockDesign("YAS_ORBIT", R.string.clock_orbit_name, R.string.clock_orbit_description) {
    override val aspect = 0.62f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val time = frame.textPaint(ClockFonts.flex, height * 0.46f, weight = frame.style.weight ?: 600f)
        time.textSize = minOf(time.textSize, fitWidth(time, "00", width * 0.42f))
        frame.gradient(time, 0f, height)
        val cap = time.capHeight()
        val gap = (height - 2 * cap) / 3f
        val left = width * 0.04f
        canvas.drawText(frame.time.hourText.padStart(2, '0'), left, gap + cap, time)
        canvas.drawText(frame.time.minuteText, left, height - gap, time)

        val size = minOf(height * 0.86f, width * 0.46f)
        val cx = width - size / 2f - width * 0.02f
        val cy = height / 2f
        val stroke = size * 0.07f
        val oval = RectF(cx - size / 2f + stroke, cy - size / 2f + stroke, cx + size / 2f - stroke, cy + size / 2f - stroke)
        val track = frame.fillPaint(frame.colors.container).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        if (!frame.isDozing) canvas.drawArc(oval, 0f, 360f, false, track)
        val month = frame.fillPaint(frame.colors.accent).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(oval, -90f, 360f * frame.time.dayOfMonth / 31f, false, month)
        val day = frame.textPaint(ClockFonts.flex, size * 0.36f, color = frame.colors.main, weight = 700f)
        canvas.drawCentered(frame.time.dayOfMonth.toString(), cx, cy - size * 0.06f, day)
        val name = frame.textPaint(ClockFonts.flex, size * 0.13f, color = frame.colors.muted, weight = 550f)
        canvas.drawCentered(frame.time.dayName.take(3), cx, cy + size * 0.22f, name)
    }
}
