/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.animation.PathInterpolator
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import kotlin.math.min

/** Draws one face of a [ClockDesign]: the large one, or the small one above notifications. */
@SuppressLint("ViewConstructor")
class ClockFaceView(
    context: Context,
    val design: ClockDesign,
    private val isLarge: Boolean,
    style: ClockStyle,
) : View(context) {
    var style: ClockStyle = style
        set(value) {
            if (field == value) return
            field = value
            updateColors()
            requestLayout()
        }

    var theme = ThemeConfig(isDarkTheme = true, seedColor = null)
        set(value) {
            field = value
            updateColors()
        }

    /** The base text size SystemUI gives the face; faces scale from it. */
    var fontSizePx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var doze = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var colors = ClockColors.resolve(context, theme, style)
    private var time: ClockTime? = null
    private var previous: ClockTime? = null
    private var change = 1f
    private val density = resources.displayMetrics.density

    private val changeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CHANGE_MS
        interpolator = EMPHASIZED
        addUpdateListener {
            change = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Shows [now]; a new minute animates in faces that animate it, unless on the always-on display. */
    fun setTime(now: ClockTime) {
        val last = time
        time = now
        contentDescription = now.timeText
        val newMinute = last != null && (last.minute != now.minute || last.hour24 != now.hour24)
        if (newMinute && doze < 0.5f && isAttachedToWindow) {
            previous = last
            changeAnimator.start()
        } else if (last == null || last.timeText != now.timeText) {
            changeAnimator.cancel()
            change = 1f
        }
        if (last == null || last.timeText != now.timeText) requestLayout()
        invalidate()
    }

    /** A short pulse, when the device starts charging. */
    fun pulse() {
        if (doze >= 0.5f) return
        animate().cancel()
        animate().scaleX(1.04f).scaleY(1.04f).setDuration(PULSE_MS).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(PULSE_MS).start()
        }.start()
    }

    private fun updateColors() {
        colors = ClockColors.resolve(context, theme, style)
        invalidate()
    }

    private fun frame(): ClockFrame? {
        val now = time ?: return null
        val aod = theme.getAodColor(context)
        return ClockFrame(now, previous, change, style, colors.dozed(doze, aod), doze, isLarge, density)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val frame = frame()
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val maxWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.widthPixels
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val maxHeight = if (heightMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        if (!isLarge) {
            val height = if (heightMode == MeasureSpec.EXACTLY) maxHeight else smallHeight()
            val width = frame?.let { design.smallWidth(it, height.toFloat()).toInt() } ?: 0
            setMeasuredDimension(min(width, maxWidth), height)
            return
        }
        val box = boxWidth(maxWidth, maxHeight)
        val width = if (widthMode == MeasureSpec.EXACTLY || style.align != Align.CENTER) maxWidth else box
        setMeasuredDimension(width, (box * design.aspect).toInt())
    }

    /** The large face's width: its natural size, scaled by the user and kept on screen. */
    private fun boxWidth(maxWidth: Int, maxHeight: Int): Int {
        val base = if (fontSizePx > 0f) fontSizePx * 2.4f else 360f * density
        var width = min(base * style.scale, maxWidth.toFloat())
        if (width * design.aspect > maxHeight) width = maxHeight / design.aspect
        return width.toInt()
    }

    private fun smallHeight() =
        if (fontSizePx > 0f) (fontSizePx * 1.3f).toInt() else (114 * density).toInt()

    override fun onDraw(canvas: Canvas) {
        val frame = frame() ?: return
        if (!isLarge) {
            design.drawSmall(canvas, frame, width.toFloat(), height.toFloat())
            return
        }
        val box = min(width, boxWidth(width, height)).toFloat()
        val left = when (style.align) {
            Align.START -> 0f
            Align.CENTER -> (width - box) / 2f
            Align.END -> width - box
        }
        canvas.save()
        canvas.translate(left, 0f)
        design.drawLarge(canvas, frame, box, box * design.aspect)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        changeAnimator.cancel()
        change = 1f
    }

    private companion object {
        const val CHANGE_MS = 650L
        const val PULSE_MS = 180L
        val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
