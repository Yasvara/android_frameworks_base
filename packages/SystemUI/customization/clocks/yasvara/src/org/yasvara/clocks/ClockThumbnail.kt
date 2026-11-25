/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import java.util.Calendar
import java.util.Locale

/**
 * A picture of a design's large face at 10:09, drawn by the design itself rather than shipped
 * as an image, so it always matches the real clock and follows the style and colours.
 */
class ClockThumbnail(
    context: Context,
    private val design: ClockDesign,
    private val style: ClockStyle,
    theme: ThemeConfig,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val colors = ClockColors.resolve(context, theme, style)
    private val time = ClockTime(
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 9)
            set(Calendar.SECOND, 30)
        }.time,
        android.icu.util.TimeZone.getDefault(),
        is24Hour = true,
        context.resources.configuration.locales[0] ?: Locale.getDefault(),
    )

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        var width = b.width().toFloat()
        var height = width * design.aspect
        if (height > b.height()) {
            height = b.height().toFloat()
            width = height / design.aspect
        }
        val frame = ClockFrame(time, null, 1f, style, colors, 0f, isLarge = true, density)
        canvas.save()
        canvas.translate(b.left + (b.width() - width) / 2f, b.top + (b.height() - height) / 2f)
        design.drawLarge(canvas, frame, width, height)
        canvas.restore()
    }

    override fun getIntrinsicWidth() = (240 * density).toInt()

    override fun getIntrinsicHeight() = (240 * density * design.aspect).toInt()

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
