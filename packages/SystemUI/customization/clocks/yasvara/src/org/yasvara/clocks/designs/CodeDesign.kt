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

/** The time written as a line of code, keywords and strings in their own colours. */
object CodeDesign : ClockDesign("YAS_CODE", R.string.clock_code_name, R.string.clock_code_description) {
    override val aspect = 0.42f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val parts = listOf(
            "val " to frame.colors.accent,
            "time" to frame.colors.main,
            " = " to frame.colors.muted,
            "\"${frame.time.timeText}\"" to frame.colors.main,
        )
        val date = listOf(
            "// " to frame.colors.muted,
            frame.time.dateText to frame.colors.muted,
        )
        val paint = frame.textPaint(ClockFonts.mono, height * 0.3f, weight = 400f)
        val line = parts.joinToString("") { it.first }
        paint.textSize = minOf(paint.textSize, fitWidth(paint, line, width))
        draw(canvas, parts, paint, (width - paint.measureText(line)) / 2f, height * 0.48f)
        paint.textSize *= 0.55f
        draw(canvas, date, paint, (width - paint.measureText(line)) / 2f + 0f, height * 0.85f)
    }

    private fun draw(canvas: Canvas, parts: List<Pair<String, Int>>, paint: android.graphics.Paint, x: Float, y: Float) {
        var left = x
        for ((text, color) in parts) {
            paint.color = color
            canvas.drawText(text, left, y, paint)
            left += paint.measureText(text)
        }
    }
}
