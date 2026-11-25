/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.sin
import org.yasvara.clocks.ClockDesign
import org.yasvara.clocks.ClockFonts
import org.yasvara.clocks.ClockFrame
import org.yasvara.clocks.R

/**
 * The time riding a wave, echoing the wave-trough Y of the Yasvara mark: each digit sits on
 * the curve, and the wave's phase follows the minutes, so it drifts through the hour. On the
 * always-on display the wave flattens and stays still.
 */
object WaveDesign : ClockDesign("YAS_WAVE", R.string.clock_wave_name, R.string.clock_wave_description) {
    override val aspect = 0.62f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val amplitude = height * 0.12f * (1f - frame.doze)
        val phase = (frame.time.minute / 60f) * 2f * PI.toFloat()
        val mid = height * 0.5f
        fun y(x: Float) = mid + amplitude * sin(2f * PI.toFloat() * x / width + phase)

        val line = frame.fillPaint(frame.colors.muted).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f * frame.density
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val wave = Path()
        var x = 0f
        wave.moveTo(0f, y(0f) + height * 0.28f)
        while (x <= width) {
            wave.lineTo(x, y(x) + height * 0.28f)
            x += 4f * frame.density
        }
        if (!frame.isDozing) canvas.drawPath(wave, line)

        val text = frame.textPaint(ClockFonts.flex, height * 0.5f, weight = frame.style.weight ?: 650f)
        frame.gradient(text, 0f, height)
        val glyphs = frame.time.hourText.padStart(2, '0') + ":" + frame.time.minuteText
        text.textSize = minOf(text.textSize, fitWidth(text, glyphs, width * 0.94f))
        var left = (width - text.measureText(glyphs)) / 2f
        for (c in glyphs) {
            val s = c.toString()
            val w = text.measureText(s)
            canvas.drawCentered(s, left + w / 2f, y(left + w / 2f), text)
            left += w
        }
    }
}
