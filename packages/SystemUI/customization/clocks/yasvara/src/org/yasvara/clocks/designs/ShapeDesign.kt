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

/**
 * Four digits, each inside an expressive shape chosen by the digit. When a digit changes its
 * shape morphs into the next one and turns a little, so every minute looks slightly different.
 */
object ShapeDesign : ClockDesign("YAS_SHAPE", R.string.clock_shape_name, R.string.clock_shape_description) {
    override val aspect = 1f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val digits = frame.time.digits
        val before = frame.previous?.digits
        val cell = width / 2f
        val radius = cell * 0.46f
        val fill = frame.fillPaint(frame.colors.container)
        val text = frame.textPaint(
            ClockFonts.flex, radius * 1.25f, color = frame.colors.onContainer,
            weight = frame.style.weight ?: 700f,
        )
        for (i in 0 until 4) {
            val cx = cell * (i % 2) + cell / 2f
            val cy = cell * (i / 2) + cell / 2f
            val digit = digits[i] - '0'
            val old = before?.let { it[i] - '0' }
            val changing = old != null && old != digit
            val progress = if (changing) frame.change else 1f
            val turn = if (changing) (1f - frame.change) * -45f else 0f
            if (!frame.isDozing || frame.outline) {
                M3Shapes.draw(
                    canvas, M3Shapes.cycle[digit], old?.let { M3Shapes.cycle[it] }, progress,
                    cx, cy, radius, fill, rotation = turn,
                )
            }
            canvas.drawCentered(digits[i].toString(), cx, cy, text)
        }
    }
}
