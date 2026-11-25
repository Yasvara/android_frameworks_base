/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import android.graphics.Paint
import org.yasvara.clocks.ClockDesign
import org.yasvara.clocks.ClockFonts
import org.yasvara.clocks.ClockFrame
import org.yasvara.clocks.R

/** The time as a sentence, largest where it fits, the hour word in the accent colour. */
object WordsDesign : ClockDesign("YAS_WORDS", R.string.clock_words_name, R.string.clock_words_description) {
    override val aspect = 0.8f

    override fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val words = TimeWords.say(frame.time).split(' ')
        val paint = frame.textPaint(ClockFonts.flex, height / 3f, weight = frame.style.weight ?: 700f)
        // Shrink until the sentence wraps into the box.
        var lines = wrap(words, paint, width)
        while (lines.size * paint.fontSpacing > height && paint.textSize > 8f) {
            paint.textSize *= 0.92f
            lines = wrap(words, paint, width)
        }
        val accent = Paint(paint).apply { color = frame.colors.accent }
        val lineHeight = paint.fontSpacing
        var baseline = (height - lines.size * lineHeight) / 2f - paint.fontMetrics.ascent
        for ((i, line) in lines.withIndex()) {
            val p = if (i == 0) accent else paint
            canvas.drawText(line, 0f, baseline, p)
            baseline += lineHeight
        }
    }

    private fun wrap(words: List<String>, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= width || line.isEmpty()) {
                line = candidate
            } else {
                lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }
}
