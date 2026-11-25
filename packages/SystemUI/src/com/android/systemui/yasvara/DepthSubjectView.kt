/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.graphics.ColorUtils
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.scrim.ScrimView
import com.android.systemui.statusbar.LightRevealScrim

/**
 * Draws the depth subject over the lock screen, exactly where it sits in the wallpaper, so the
 * wallpaper seems to stand in front of the clock.
 *
 * [KeyguardRootView] draws it right above the clock and the other glanceable views, and draws
 * what must stay readable and reachable above it ([drawsAbove]): the lock icon and fingerprint
 * sensor, the shortcuts, the indication text, the status bar, and the clock when the clock is
 * to be in front. Nothing is cut out of the subject, so no hole can show when it moves.
 */
@SuppressLint("ViewConstructor")
class DepthSubjectView(
    context: Context,
    private val parallax: WallpaperParallax,
    private val placement: WallpaperPlacement,
) :
    View(context), WallpaperParallax.Listener {

    var subject: DepthRepository.Subject? = null
        set(value) {
            field = value
            occlusionKey = null
            invalidate()
        }

    var config = DepthRepository.Config()
        set(value) {
            field = value
            invalidate()
        }

    /** How much of the lock screen is showing: 0 on AOD, the bouncer or under the shade. */
    var shownFraction = 1f
        set(value) {
            field = value
            invalidate()
        }

    /** Where the window manager shows the wallpaper; the subject stays on it. */
    var wallpaper = WallpaperPlacement.State()
        set(value) {
            val appeared = value.lockVisible && !field.lockVisible
            field = value
            occlusionKey = null
            if (!value.lockVisible) {
                reveal.cancel()
                revealed = 0f
            } else if (appeared) {
                reveal.start()
            }
            invalidate()
        }

    /**
     * Follows the lock wallpaper coming into view: on wake the lock screen shows a few frames
     * before the window manager shows the wallpaper, and the subject must not stand on black.
     */
    private var revealed = 0f
    private val reveal = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = REVEAL_MS
        addUpdateListener {
            revealed = it.animatedValue as Float
            invalidate()
        }
    }

    /** False in previews, which neither move nor fade. */
    var live = true

    @Volatile private var tiltX = 0f
    @Volatile private var tiltY = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dest = RectF()
    private val frame = RectF()
    private var occlusionKey: Rect? = null
    private var clockBehind = false
    private val scrimPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val scrims = IntArray(SCRIMS.size)

    /**
     * The light reveal scrim hides the wallpaper behind black on AOD and uncovers it on wake
     * (from the fingerprint sensor or the power button). It lies under the lock screen views,
     * so the subject takes its shape out of itself to be uncovered with the wallpaper.
     */
    private var lightReveal: LightRevealScrim? = null
    private var revealDrawn = 1f
    private val revealPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    /**
     * The scrims under the lock screen dim the wallpaper (a light tint at rest, more as the
     * shade comes in); the subject sits above them, so it follows them to keep one colour.
     */
    private val scrimWatcher = ViewTreeObserver.OnPreDrawListener {
        if (live && scrimColors(scrims)) invalidate()
        if (live && revealShown() != revealDrawn) invalidate()
        true
    }

    /** How much of the wallpaper the light reveal scrim lets through, its alpha included. */
    private fun revealShown(): Float {
        val scrim = lightReveal
            ?: rootView.findViewById<LightRevealScrim>(R.id.light_reveal_scrim)?.also { lightReveal = it }
            ?: return 1f
        if (!scrim.isShown || scrim.alpha == 0f) return 1f
        return scrim.revealAmount * scrim.alpha + (1f - scrim.alpha)
    }

    /** Settles whether the clock goes behind before drawing, so the order is right this frame. */
    private val layering = ViewTreeObserver.OnPreDrawListener {
        updateClockLayer()
        true
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(layering)
        if (live) {
            parallax.addListener(this)
            viewTreeObserver.addOnPreDrawListener(scrimWatcher)
        }
    }

    override fun onDetachedFromWindow() {
        parallax.removeListener(this)
        viewTreeObserver.removeOnPreDrawListener(scrimWatcher)
        viewTreeObserver.removeOnPreDrawListener(layering)
        reveal.cancel()
        super.onDetachedFromWindow()
    }

    /** Whether [child], a sibling, is drawn over the subject rather than under it. */
    fun drawsAbove(child: View): Boolean =
        child.id in PROTECTED || (!clockBehind && child.id in CLOCKS)

    private fun updateClockLayer() {
        // Only the lock screen's subject shares a parent with the clock.
        val parent = parent as? KeyguardRootView ?: return
        val subject = subject
        val behind = subject != null && when (config.clock) {
            DepthClockLayer.BEHIND -> true
            DepthClockLayer.FRONT -> false
            DepthClockLayer.AUTO -> autoClockBehind(parent, subject)
        }
        if (behind != clockBehind) {
            clockBehind = behind
            parent.invalidateDrawingOrder()
        }
    }

    override fun onParallax(x: Float, y: Float) {
        tiltX = x
        tiltY = y
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val subject = subject ?: return
        val alpha = config.opacity * (if (live) shownFraction * revealed else 1f)
        if (alpha <= 0f) return
        val parent = parent as? ViewGroup ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        placeFrame(subject)
        dest.set(
            frame.left + subject.bounds.left * frame.width(),
            frame.top + subject.bounds.top * frame.height(),
            frame.left + subject.bounds.right * frame.width(),
            frame.top + subject.bounds.bottom * frame.height(),
        )

        revealDrawn = if (live) revealShown() else 1f
        val scrim = lightReveal.takeIf { revealDrawn < 1f }
        if (scrim != null) canvas.saveLayer(null, null)
        canvas.save()
        // The window manager zooms the wallpaper around the display centre (more at rest, less
        // as the shade or bouncer comes in); the lock screen may be zoomed itself already.
        val rootScale = (parent as View).scaleX.takeIf { it > 0f } ?: 1f
        val zoom = placement.scale(if (live) wallpaper else AT_REST) / rootScale
        canvas.scale(zoom, zoom, w / 2f, h / 2f)

        // Follow the wallpaper's parallax, with the subject moving further than it.
        if (live && parallax.isEnabled(lock = true) && (tiltX != 0f || tiltY != 0f)) {
            val shift = parallax.maxShiftPx
            val scale = 1f + 2.2f * shift / minOf(w, h)
            val depth = 1f + config.parallax
            canvas.translate(-tiltX * shift * depth, -tiltY * shift * depth)
            canvas.scale(scale, scale, w / 2f, h / 2f)
        }
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        if (live && scrims.any { Color.alpha(it) != 0 }) {
            canvas.saveLayer(dest, null)
            canvas.drawBitmap(subject.bitmap, null, dest, paint)
            for (color in scrims) {
                if (Color.alpha(color) == 0) continue
                scrimPaint.color = color
                canvas.drawRect(dest, scrimPaint)
            }
            canvas.restore()
        } else {
            canvas.drawBitmap(subject.bitmap, null, dest, paint)
        }
        canvas.restore()
        if (scrim != null) {
            // The scrim's own drawing (black where the wallpaper is still hidden), used as an
            // eraser. Both views fill the window from its origin.
            revealPaint.alpha = (scrim.alpha * 255).toInt().coerceIn(0, 255)
            canvas.saveLayer(null, revealPaint)
            scrim.drawReveal(canvas)
            canvas.restore()
            canvas.restore()
        }
    }

    /** Where the wallpaper crop the subject was cut from lies in this view, before the zoom. */
    private fun placeFrame(subject: DepthRepository.Subject) {
        // Previews (Laras) draw the wallpaper as the window manager does at rest.
        placement.frameOnDisplay(if (live) wallpaper else AT_REST, subject.frameAspect, 1f,
            width.toFloat(), height.toFloat(), frame)
    }

    /**
     * The colour each scrim under the lock screen paints, as its drawable does: the tint
     * (blended into the main colour by the tint's alpha) at the scrim's alpha. True if changed.
     */
    private fun scrimColors(out: IntArray): Boolean {
        var changed = false
        SCRIMS.forEachIndexed { i, id ->
            val scrim = rootView.findViewById<ScrimView>(id)
            val color = if (scrim == null || !scrim.isShown) 0 else {
                val tint = scrim.tint
                val rgb = if (scrim.shouldBlendWithMainColor()) {
                    ColorUtils.blendARGB(scrim.colors.mainColor, tint, Color.alpha(tint) / 255f)
                } else tint
                val a = (scrim.viewAlpha * scrim.alpha * 255).toInt().coerceIn(0, 255)
                ColorUtils.setAlphaComponent(rgb, a)
            }
            if (out[i] != color) {
                out[i] = color
                changed = true
            }
        }
        return changed
    }

    /**
     * Puts the subject over the clock when it covers a telling part of it: enough to be seen,
     * not so much that the time is lost. Measured on the subject's small coverage mask.
     */
    private fun autoClockBehind(parent: ViewGroup, subject: DepthRepository.Subject): Boolean {
        val clock = listOf(
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
        ).mapNotNull { parent.findViewById<View>(it) }.firstOrNull { it.isShown && it.width > 0 }
            ?: return false
        val rect = Rect()
        clock.getDrawingRect(rect)
        parent.offsetDescendantRectToMyCoords(clock, rect)
        if (rect == occlusionKey) return clockBehind
        occlusionKey = Rect(rect)
        val map = subject.coverage
        placeFrame(subject)
        if (frame.isEmpty) return false
        val sx = map.width / frame.width()
        val sy = map.height / frame.height()
        val x0 = ((rect.left - frame.left) * sx).toInt().coerceIn(0, map.width - 1)
        val x1 = ((rect.right - frame.left) * sx).toInt().coerceIn(x0 + 1, map.width)
        val y0 = ((rect.top - frame.top) * sy).toInt().coerceIn(0, map.height - 1)
        val y1 = ((rect.bottom - frame.top) * sy).toInt().coerceIn(y0 + 1, map.height)
        var covered = 0
        var total = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            total++
            if ((map.getPixel(x, y) ushr 24) > 128) covered++
        }
        val fraction = if (total == 0) 0f else covered / total.toFloat()
        // Hysteresis keeps a clock near a threshold from flipping back and forth.
        val (low, high) = if (clockBehind) (MIN_COVER - HYSTERESIS) to (MAX_COVER + HYSTERESIS)
        else MIN_COVER to MAX_COVER
        return fraction in low..high
    }

    companion object {
        val VIEW_ID = generateViewId()
        private val AT_REST = WallpaperPlacement.State()
        /** The scrims between the wallpaper and the lock screen, bottom first. */
        private val SCRIMS = intArrayOf(R.id.scrim_behind, R.id.scrim_notifications)
        private const val MIN_COVER = 0.08f
        private const val MAX_COVER = 0.45f
        private const val HYSTERESIS = 0.03f
        private const val REVEAL_MS = 180L
        private val CLOCKS = intArrayOf(
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
        )
        private val PROTECTED = intArrayOf(
            R.id.device_entry_icon_view,
            R.id.start_button,
            R.id.end_button,
            R.id.keyguard_indication_area,
            R.id.keyguard_header,
            R.id.ambient_indication_container,
        )
    }
}
