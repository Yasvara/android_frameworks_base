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

/** Each digit in its own bubble, the minute bubbles in the accent colour. */
object BubbleDesign : ClockDesign("YAS_BUBBLE", R.string.clock_bubble_name, R.string.clock_bubble_description) {
    override val aspect = 0.3f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val gap = width * 0.03f
        val diameter = minOf((width - 3 * gap - gap * 2) / 4f, height)
        val radius = diameter / 2f
        var left = (width - (4 * diameter + 2 * gap + gap * 2)) / 2f
        val text = frame.textPaint(ClockFonts.flex, radius * 1.2f, weight = frame.style.weight ?: 700f)
        for ((i, digit) in frame.time.digits.withIndex()) {
            val isMinute = i >= 2
            val fill = frame.fillPaint(if (isMinute) frame.colors.accent else frame.colors.container)
            if (frame.isDozing && !frame.outline) fill.alpha = 0
            val cx = left + radius
            canvas.drawCircle(cx, height / 2f, radius, fill)
            text.color = if (isMinute && !frame.isDozing) frame.colors.container else frame.colors.onContainer
            canvas.drawCentered(digit.toString(), cx, height / 2f, text)
            left += diameter + gap + if (i == 1) gap * 2 else 0f
        }
    }
}
