/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.statusbar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.LayoutDirection
import android.view.View
import android.widget.ImageView
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.yasvara.preview.StatusBarPreviews
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Status bar icon sets: each draws the signal and Wi-Fi levels in a look of its
 * own, from the same view-model state as the stock icons, so a switch is one redraw. `aosp`
 * keeps the stock drawables.
 */
object StatusIconSets {
    const val AOSP = "aosp"
    const val WEDGE = "yas-wedge"
    const val BLOCKS = "yas-blocks"
    const val DOTS = "yas-dots"
    const val OUTLINE = "yas-outline"
    const val THIN = "yas-mono-thin"

    val IDS = setOf(AOSP, WEDGE, BLOCKS, DOTS, OUTLINE, THIN)

    /**
     * The set the user chose, as the status bar binders read it. Set by
     * [com.android.systemui.yasvara.theme.StatusIconSetsStarter]; `aosp` until then, and in any
     * process that never starts it.
     */
    val current = MutableStateFlow(AOSP)

    /** The style of set [id], or null for the stock icons. */
    fun style(id: String): IconStyle? =
        when (id) {
            WEDGE -> IconStyle.WEDGE
            BLOCKS -> IconStyle.BLOCKS
            DOTS -> IconStyle.DOTS
            OUTLINE -> IconStyle.OUTLINE
            THIN -> IconStyle.THIN
            else -> null
        }

    /** The set [view] shows now; see [forView]. */
    fun currentFor(view: View): String = StatusBarPreviews.of(view)?.iconSet?.value ?: current.value

    /**
     * The set [view] shows: the user's, or the one its status bar preview drafts. Call once the
     * view is attached, so a preview can be found.
     */
    fun forView(view: View): Flow<String> {
        val draft = StatusBarPreviews.of(view)?.iconSet ?: flowOf(null)
        return combine(current, draft) { chosen, drafted -> drafted ?: chosen }.distinctUntilChanged()
    }
}

enum class IconStyle {
    /** The classic Android shapes, redrawn: a wedge and a fan filled to the level. */
    WEDGE,

    /** Square-cut bars and bands with tight gaps, like the Segments battery. */
    BLOCKS,

    /** A dot matrix, like the Titik clock. */
    DOTS,

    /** Outlined shapes, filled to the level: empty parts stay drawn, never faint. */
    OUTLINE,

    /** Hairline strokes, for large fonts. */
    THIN,
}

/** Shows the mobile signal in the chosen set, or the stock [SignalDrawable]. */
class YasSignalIcon(private val view: ImageView, private val stock: SignalDrawable) {
    private var set = StatusIconSets.AOSP
    private var drawable: YasSignalDrawable? = null
    private var packed: Int? = null

    fun show(packedState: Int) {
        packed = packedState
        stock.level = packedState
        // The carrier change animation stays the stock one.
        val yas = drawable.takeIf { SignalDrawable.getState(packedState) != STATE_CARRIER_CHANGE }
        view.setImageDrawable(yas ?: stock)
        yas?.level = packedState
    }

    fun setIconSet(id: String) {
        if (id == set) return
        set = id
        drawable = StatusIconSets.style(id)?.let { YasSignalDrawable(view.context, it) }
        packed?.let(::show)
    }

    private companion object {
        /** `SignalDrawable.STATE_CARRIER_CHANGE`. */
        const val STATE_CARRIER_CHANGE = 3
    }
}

/** Shows the Wi-Fi level in the chosen set, or the stock icon. */
class YasWifiIcon(private val view: ImageView) {
    private var set = StatusIconSets.AOSP
    private var normal: YasWifiDrawable? = null
    private var error: YasWifiDrawable? = null
    private var icon: WifiIcon.Visible? = null

    fun show(icon: WifiIcon.Visible) {
        this.icon = icon
        val yas = (if (icon.noInternet) error else normal).takeIf { icon.level >= 0 }
        if (yas == null) {
            IconViewBinder.bind(icon.icon, view)
            return
        }
        view.setImageDrawable(yas)
        view.contentDescription = icon.contentDescription.description
        yas.level = icon.level
    }

    fun setIconSet(id: String) {
        if (id == set) return
        set = id
        val style = StatusIconSets.style(id)
        normal = style?.let { YasWifiDrawable(view.context, it, error = false) }
        error = style?.let { YasWifiDrawable(view.context, it, error = true) }
        icon?.let(::show)
    }
}

/**
 * A status bar icon drawn at a level in viewport units, scaled into its bounds and tinted like
 * the stock icons: `ImageView.imageTintList` gives the colour, and the parts beyond the level
 * are drawn at [EMPTY_ALPHA] of it.
 */
abstract class LevelIconDrawable(
    context: Context,
    widthDp: Float,
    heightDp: Float,
    private val viewportWidth: Float,
    private val viewportHeight: Float,
) : Drawable() {
    private val width = (widthDp * context.resources.displayMetrics.density).roundToInt()
    private val height = (heightDp * context.resources.displayMetrics.density).roundToInt()
    private var tint: ColorStateList? = null
    private var alphaValue = 255

    protected val full = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val empty = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Full colour, for strokes: outlines and hairlines. */
    protected val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    /** Like [stroke], at [EMPTY_ALPHA]. */
    protected val emptyStroke = Paint(stroke)

    override fun getIntrinsicWidth() = width

    override fun getIntrinsicHeight() = height

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun isStateful() = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean {
        invalidateSelf()
        return tint?.isStateful == true
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha() = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {
        for (paint in listOf(full, empty, stroke, emptyStroke)) paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun onLevelChange(level: Int): Boolean {
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val color = tint?.getColorForState(state, tint!!.defaultColor) ?: Color.WHITE
        val alpha = alphaValue * Color.alpha(color) / 255
        for (paint in listOf(full, stroke)) {
            paint.color = color
            paint.alpha = alpha
        }
        for (paint in listOf(empty, emptyStroke)) {
            paint.color = color
            paint.alpha = (alpha * EMPTY_ALPHA).roundToInt()
        }
        val b = bounds
        val scale = min(b.width() / viewportWidth, b.height() / viewportHeight)
        canvas.save()
        canvas.translate(
            b.left + (b.width() - viewportWidth * scale) / 2,
            b.top + (b.height() - viewportHeight * scale) / 2,
        )
        canvas.scale(scale, scale)
        if (layoutDirection == LayoutDirection.RTL) {
            canvas.translate(viewportWidth, 0f)
            canvas.scale(-1f, 1f)
        }
        drawIcon(canvas)
        canvas.restore()
    }

    /** Draws the icon in viewport units, with [full], [empty], [stroke] and [emptyStroke]. */
    protected abstract fun drawIcon(canvas: Canvas)

    /** An exclamation mark centred on [x]: no internet, or not the data SIM. */
    protected fun drawExclamation(canvas: Canvas, x: Float, weight: Float = 1.6f) {
        val half = weight / 2
        canvas.drawRoundRect(x - half, 3.4f, x + half, 8.7f, half, half, full)
        canvas.drawCircle(x, 11.0f, half + 0.15f, full)
    }

    companion object {
        /** Material's disabled-content alpha: clearly there, clearly not lit. */
        const val EMPTY_ALPHA = 0.38f
    }
}

/**
 * The mobile signal of an icon set. Its level is [SignalDrawable]'s packed state, so the binder
 * feeds both the same value: the level, the number of levels, and the cut state (no data).
 */
class YasSignalDrawable(context: Context, private val style: IconStyle) :
    LevelIconDrawable(context, 17f, 12f, W, H) {

    private val wedge =
        Path().apply {
            moveTo(0f, H)
            lineTo(W, H)
            lineTo(W, 0f)
            close()
        }
    private val rounded = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawIcon(canvas: Canvas) {
        val packed = level
        val bars = ((packed shr 8 and 0xff) - 1).coerceIn(4, 5)
        val lit = (packed and 0xff).coerceIn(0, bars)
        val cut = SignalDrawable.getState(packed) == STATE_CUT
        when (style) {
            IconStyle.WEDGE -> drawWedge(canvas, lit, bars, cut)
            IconStyle.BLOCKS -> drawBlocks(canvas, lit, bars, cut)
            IconStyle.DOTS -> drawDots(canvas, lit, bars, cut)
            IconStyle.OUTLINE -> drawOutline(canvas, lit, bars, cut)
            IconStyle.THIN -> drawThin(canvas, lit, bars, cut)
        }
    }

    private fun drawWedge(canvas: Canvas, lit: Int, bars: Int, cut: Boolean) {
        canvas.save()
        if (cut) canvas.clipOutRect(WEDGE_CUT, 0f, W, H)
        rounded.set(empty)
        rounded.pathEffect = CornerPathEffect(1.6f)
        canvas.drawPath(wedge, rounded)
        if (lit > 0) {
            canvas.save()
            canvas.clipRect(0f, 0f, W * lit / bars, H)
            rounded.set(full)
            rounded.pathEffect = CornerPathEffect(1.6f)
            canvas.drawPath(wedge, rounded)
            canvas.restore()
        }
        canvas.restore()
        if (cut) drawExclamation(canvas, (WEDGE_CUT + W) / 2 + 0.3f)
    }

    private fun drawBlocks(canvas: Canvas, lit: Int, bars: Int, cut: Boolean) {
        val gap = 1.3f
        val width = (W - (bars - 1) * gap) / bars
        for (i in 0 until bars) {
            val left = i * (width + gap)
            if (cut && i == bars - 1) {
                drawExclamation(canvas, left + width / 2)
                continue
            }
            val top = H - H * (i + 1) / bars
            canvas.drawRoundRect(left, top, left + width, H, 0.5f, 0.5f, if (i < lit) full else empty)
        }
    }

    private fun drawDots(canvas: Canvas, lit: Int, bars: Int, cut: Boolean) {
        val radius = if (bars == 4) 1.15f else 1.0f
        val dx = (W - 2 * radius) / (bars - 1)
        val dy = (H - 2 * radius) / (bars - 1)
        for (i in 0 until bars) {
            val x = radius + i * dx
            if (cut && i == bars - 1) {
                drawExclamation(canvas, x)
                continue
            }
            for (j in 0..i) {
                canvas.drawCircle(x, H - radius - j * dy, radius, if (i < lit) full else empty)
            }
        }
    }

    private fun drawOutline(canvas: Canvas, lit: Int, bars: Int, cut: Boolean) {
        val gap = if (bars == 4) 2.0f else 1.35f
        val width = (W - (bars - 1) * gap) / bars
        val step = if (bars == 4) 2.5f else 2.0f
        stroke.strokeWidth = OUTLINE_STROKE
        for (i in 0 until bars) {
            val left = i * (width + gap)
            if (cut && i == bars - 1) {
                drawExclamation(canvas, left + width / 2)
                continue
            }
            val top = step * (bars - 1 - i)
            val r = width / 2
            if (i < lit) {
                canvas.drawRoundRect(left, top, left + width, H, r, r, full)
            } else {
                val inset = OUTLINE_STROKE / 2
                canvas.drawRoundRect(
                    left + inset, top + inset, left + width - inset, H - inset,
                    r - inset, r - inset, stroke,
                )
            }
        }
    }

    private fun drawThin(canvas: Canvas, lit: Int, bars: Int, cut: Boolean) {
        val half = THIN_STROKE / 2
        val dx = (W - THIN_STROKE) / (bars - 1)
        stroke.strokeWidth = THIN_STROKE
        emptyStroke.strokeWidth = THIN_STROKE
        for (i in 0 until bars) {
            val x = half + i * dx
            if (cut && i == bars - 1) {
                drawExclamation(canvas, x, weight = THIN_STROKE)
                continue
            }
            val length = (H - THIN_STROKE) * (i + 1) / bars
            canvas.drawLine(x, H - half, x, H - half - length, if (i < lit) stroke else emptyStroke)
        }
    }

    private companion object {
        const val W = 16f
        const val H = 12f
        const val WEDGE_CUT = 12.4f
        const val OUTLINE_STROKE = 0.9f
        const val THIN_STROKE = 1.2f

        /** `SignalDrawable.STATE_CUT`: no data connection on this SIM. */
        const val STATE_CUT = 2
    }
}

/**
 * The Wi-Fi level (0-4) of an icon set. The [error] variant, for no internet, is wider and
 * carries an exclamation mark beside the fan, as the stock one does.
 */
class YasWifiDrawable(context: Context, private val style: IconStyle, private val error: Boolean) :
    LevelIconDrawable(
        context,
        if (error) 21.25f else 17f,
        12.58f,
        if (error) W + ERROR_EXTRA else W,
        H,
    ) {

    private val rounded = Paint(Paint.ANTI_ALIAS_FLAG)
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()

    override fun drawIcon(canvas: Canvas) {
        val lit = level.coerceIn(0, 4)
        when (style) {
            IconStyle.WEDGE -> drawWedge(canvas, lit)
            IconStyle.BLOCKS -> drawBlocks(canvas, lit)
            IconStyle.DOTS -> drawDots(canvas, lit)
            IconStyle.OUTLINE -> drawOutline(canvas, lit)
            IconStyle.THIN -> drawThin(canvas, lit)
        }
        if (error) {
            drawExclamation(
                canvas,
                W + ERROR_EXTRA - 1.2f,
                weight = if (style == IconStyle.THIN) 1.2f else 1.6f,
            )
        }
    }

    private fun drawWedge(canvas: Canvas, lit: Int) {
        val cx = W / 2
        val cy = 12f
        val radius = 11.5f
        rounded.set(empty)
        rounded.pathEffect = CornerPathEffect(1.4f)
        canvas.drawPath(sector(cx, cy, radius), rounded)
        if (lit > 0) {
            rounded.set(full)
            rounded.pathEffect = CornerPathEffect(1.4f)
            canvas.drawPath(sector(cx, cy, radius * lit / 4), rounded)
        }
    }

    private fun drawBlocks(canvas: Canvas, lit: Int) {
        val cx = W / 2
        val cy = 11.6f
        val bands = listOf(0f to 2.1f, 3.0f to 5.1f, 6.0f to 8.1f, 9.0f to 11.1f)
        bands.forEachIndexed { i, (inner, outer) ->
            rounded.set(if (i < lit) full else empty)
            rounded.pathEffect = CornerPathEffect(0.35f)
            val path = if (inner == 0f) sector(cx, cy, outer) else band(cx, cy, inner, outer)
            canvas.drawPath(path, rounded)
        }
    }

    private fun drawDots(canvas: Canvas, lit: Int) {
        val cx = W / 2
        val cy = 10.95f
        val radius = 1.0f
        canvas.drawCircle(cx, cy, radius, if (lit > 0) full else empty)
        val arcs = listOf(3.6f to 3, 6.8f to 4, 9.9f to 6)
        arcs.forEachIndexed { i, (r, count) ->
            val paint = if (i + 1 < lit) full else empty
            for (k in 0 until count) {
                val degrees = -90f - DOT_HALF_ANGLE + 2 * DOT_HALF_ANGLE * k / (count - 1)
                val radians = Math.toRadians(degrees.toDouble())
                canvas.drawCircle(
                    cx + r * cos(radians).toFloat(),
                    cy + r * sin(radians).toFloat(),
                    radius,
                    paint,
                )
            }
        }
    }

    private fun drawOutline(canvas: Canvas, lit: Int) {
        val cx = W / 2
        val cy = 11.0f
        stroke.strokeWidth = OUTLINE_STROKE
        if (lit > 0) {
            canvas.drawCircle(cx, cy, 1.3f, full)
        } else {
            canvas.drawCircle(cx, cy, 1.3f - OUTLINE_STROKE / 2, stroke)
        }
        listOf(4.0f, 6.9f, 9.8f).forEachIndexed { i, r ->
            val arc = arc(cx, cy, r, 45f)
            if (i + 1 < lit) {
                band.set(stroke)
                band.strokeWidth = OUTLINE_BAND
                canvas.drawPath(arc, band)
            } else {
                // The band's own outline, drawn just inside where the lit band would be.
                band.set(stroke)
                band.strokeWidth = OUTLINE_BAND - OUTLINE_STROKE
                outline.reset()
                band.getFillPath(arc, outline)
                canvas.drawPath(outline, stroke)
            }
        }
    }

    private fun drawThin(canvas: Canvas, lit: Int) {
        val cx = W / 2
        val cy = 11.2f
        stroke.strokeWidth = THIN_STROKE
        emptyStroke.strokeWidth = THIN_STROKE
        canvas.drawCircle(cx, cy, 1.0f, if (lit > 0) full else empty)
        listOf(4.1f, 7.1f, 10.1f).forEachIndexed { i, r ->
            canvas.drawPath(arc(cx, cy, r, 45f), if (i + 1 < lit) stroke else emptyStroke)
        }
    }

    private fun sector(cx: Float, cy: Float, radius: Float) =
        Path().apply {
            moveTo(cx, cy)
            arcTo(RectF(cx - radius, cy - radius, cx + radius, cy + radius), 225f, 90f)
            close()
        }

    private fun band(cx: Float, cy: Float, inner: Float, outer: Float) =
        Path().apply {
            arcTo(RectF(cx - outer, cy - outer, cx + outer, cy + outer), 225f, 90f, true)
            arcTo(RectF(cx - inner, cy - inner, cx + inner, cy + inner), 315f, -90f)
            close()
        }

    private fun arc(cx: Float, cy: Float, radius: Float, halfAngle: Float) =
        Path().apply {
            arcTo(
                RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                -90f - halfAngle,
                2 * halfAngle,
                true,
            )
        }

    private companion object {
        const val W = 16.2f
        const val H = 12f
        const val ERROR_EXTRA = 4.05f
        const val DOT_HALF_ANGLE = 42f
        const val OUTLINE_STROKE = 0.8f
        const val OUTLINE_BAND = 1.8f
        const val THIN_STROKE = 1.2f
    }
}
