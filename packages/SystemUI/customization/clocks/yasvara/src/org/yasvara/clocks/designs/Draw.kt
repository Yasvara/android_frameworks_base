/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import android.graphics.Paint

/** Draws [text] centred horizontally on [cx], with its glyphs vertically centred on [cy]. */
internal fun Canvas.drawCentered(text: String, cx: Float, cy: Float, paint: Paint) {
    val bounds = android.graphics.Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    val width = paint.measureText(text)
    drawText(text, cx - width / 2f, cy - bounds.exactCenterY(), paint)
}

/** The text size at which [text] in [paint] is [width] wide. */
internal fun fitWidth(paint: Paint, text: String, width: Float): Float {
    val measured = paint.measureText(text)
    return if (measured <= 0f) paint.textSize else paint.textSize * width / measured
}

/** The cap height of [paint], used to stack lines of digits tightly. */
internal fun Paint.capHeight(): Float {
    val bounds = android.graphics.Rect()
    getTextBounds("0", 0, 1, bounds)
    return bounds.height().toFloat()
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
