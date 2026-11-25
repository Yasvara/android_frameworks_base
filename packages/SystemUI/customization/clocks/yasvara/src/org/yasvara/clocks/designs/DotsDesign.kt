/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import org.yasvara.clocks.ClockDesign
import org.yasvara.clocks.ClockFrame
import org.yasvara.clocks.R

/**
 * The time on a grid of dots, from a 5 by 7 dot font drawn here rather than a proprietary
 * dot-matrix typeface. Unlit dots show faintly, except on the always-on display.
 */
object DotsDesign : ClockDesign("YAS_DOTS", R.string.clock_dots_name, R.string.clock_dots_description) {
    override val aspect = 0.42f

    /** Rows of 5 bits, top row first, for 0-9 and the colon. */
    private val GLYPHS = mapOf(
        '0' to intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110),
        '1' to intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
        '2' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111),
        '3' to intArrayOf(0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110),
        '4' to intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010),
        '5' to intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110),
        '6' to intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110),
        '7' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000),
        '8' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110),
        '9' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100),
        ':' to intArrayOf(0b00000, 0b00100, 0b00100, 0b00000, 0b00100, 0b00100, 0b00000),
    )

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val text = frame.time.hourText.padStart(2, '0') + ":" + frame.time.minuteText
        // Each glyph is 5 dots wide with one dot of spacing; the colon is narrower.
        val columns = text.sumOf { if (it == ':') 4 else 6 } - 1
        val pitch = minOf(width / columns, height / 7f)
        val radius = pitch * 0.38f
        val lit = frame.fillPaint(frame.colors.main)
        val unlit = frame.fillPaint(frame.colors.muted).apply { alpha = 46 }
        var left = (width - columns * pitch) / 2f
        val top = (height - 7 * pitch) / 2f
        for (c in text) {
            val rows = GLYPHS[c] ?: continue
            val colOffset = if (c == ':') 1 else 0
            val cols = if (c == ':') 3 else 5
            for (row in 0 until 7) for (col in 0 until cols) {
                val on = rows[row] shr (4 - col - colOffset) and 1 == 1
                if (!on && frame.isDozing) continue
                canvas.drawCircle(
                    left + col * pitch + pitch / 2f, top + row * pitch + pitch / 2f, radius,
                    if (on) lit else unlit,
                )
            }
            left += (cols + 1) * pitch
        }
    }
}
