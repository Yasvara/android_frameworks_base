/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.annotation.StringRes

/** Everything a face needs to draw one frame. */
class ClockFrame(
    val time: ClockTime,
    /** The reading before the last change of minute, for faces that animate it. */
    val previous: ClockTime?,
    /** Progress of the change from [previous] to [time], 0 to 1. */
    val change: Float,
    val style: ClockStyle,
    val colors: ClockColors,
    /** 0 awake to 1 on the always-on display. */
    val doze: Float,
    val isLarge: Boolean,
    val density: Float,
) {
    val isDozing get() = doze >= 0.5f

    /** Draws with outlines rather than fills on the always-on display, if the style asks to. */
    val outline get() = isDozing && style.aod == AodLook.OUTLINE

    /** A paint for the time in [typeface], themed and ready for this frame. */
    fun textPaint(
        typeface: Typeface,
        size: Float,
        color: Int = colors.main,
        weight: Float = style.weight ?: 400f,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = size
        this.color = color
        val look = this@ClockFrame.style
        val w = if (isDozing && look.aod == AodLook.THIN) weight.coerceAtMost(250f) else weight
        fontVariationSettings = ClockFonts.variation(w, look.width ?: 100f, look.roundness)
        stroke(this)
    }

    fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        stroke(this)
    }

    /** Applies the gradient colour role across [top]..[bottom] of the face. */
    fun gradient(paint: Paint, top: Float, bottom: Float) {
        if (style.color == ColorRole.GRADIENT && colors.mainEnd != colors.main && !isDozing) {
            paint.shader = LinearGradient(0f, top, 0f, bottom, colors.main, colors.mainEnd, Shader.TileMode.CLAMP)
        }
    }

    private fun stroke(paint: Paint) {
        if (outline) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
        }
    }
}

/**
 * One clock design. A design draws itself on a canvas, which keeps it light (one view per face)
 * and identical everywhere it is drawn: on the lock screen, the always-on display, and in
 * Laras's thumbnails.
 */
abstract class ClockDesign(
    val id: String,
    @StringRes val name: Int,
    @StringRes val description: Int,
) {
    /** Height over width of the large face. */
    abstract val aspect: Float

    /**
     * Where the design's small clock sits and how big it is against the stock small clock,
     * unless the user chooses otherwise. Designs that read as a picture rather than a line of
     * text look best larger and centred.
     */
    open val smallAlign = Align.CENTER
    open val smallScale = 1.5f

    /** True if the design can show seconds, which costs a redraw every second. */
    open val supportsSeconds = false

    /** Draws the large face into a [width] by [height] box. */
    abstract fun drawLarge(canvas: Canvas, frame: ClockFrame, width: Float, height: Float)

    /** The small face's width for [height], drawn by [drawSmall]. */
    open fun smallWidth(frame: ClockFrame, height: Float): Float = height / aspect

    /**
     * The small face, shown above notifications. By default it is the large face scaled to
     * the small clock's height, so every design keeps its character in both sizes; designs
     * that are mostly text draw a line of text instead, through [drawTextLine].
     */
    open fun drawSmall(canvas: Canvas, frame: ClockFrame, width: Float, height: Float) {
        val faceHeight = minOf(height, width * aspect)
        canvas.save()
        canvas.translate(0f, (height - faceHeight) / 2f)
        drawLarge(canvas, frame, faceHeight / aspect, faceHeight)
        canvas.restore()
    }

    /** Width of [text] set by [drawTextLine] in a small face of [height]. */
    protected fun textLineWidth(frame: ClockFrame, height: Float, weight: Float, text: String = frame.time.timeText) =
        frame.textPaint(ClockFonts.flex, height * 0.62f, weight = frame.style.weight ?: weight).measureText(text)

    /** Draws runs of text on one line, each run in its own colour, centred in [height]. */
    protected fun drawTextLine(
        canvas: Canvas,
        frame: ClockFrame,
        height: Float,
        weight: Float,
        runs: List<Pair<String, Int>>,
    ) {
        val paint = frame.textPaint(ClockFonts.flex, height * 0.62f, weight = frame.style.weight ?: weight)
        val metrics = paint.fontMetrics
        val baseline = (height - metrics.descent - metrics.ascent) / 2f
        var left = 0f
        for ((text, color) in runs) {
            paint.color = color
            paint.shader = null
            if (color == frame.colors.main) frame.gradient(paint, 0f, height)
            canvas.drawText(text, left, baseline, paint)
            left += paint.measureText(text)
        }
    }
}
