/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.theme

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.IWindowManager
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.window.ScreenCaptureInternal
import android.window.ScreenCaptureInternal.CaptureArgs
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.platform.AndroidUiDispatcher
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.yasvara.theme.YasTransitionContract.KEY_CALLBACK
import com.android.systemui.yasvara.theme.YasTransitionContract.KEY_COVER
import com.android.systemui.yasvara.theme.YasTransitionContract.KEY_EXPECT_THEME_CHANGE
import com.android.systemui.yasvara.theme.YasTransitionContract.KEY_FINGERPRINT
import com.android.systemui.yasvara.theme.YasTransitionContract.KEY_TIMEOUT_MS
import com.android.systemui.yasvara.theme.YasTransitionContract.KEY_TOKEN
import com.android.systemui.yasvara.theme.YasTransitionContract.KEY_X
import com.android.systemui.yasvara.theme.YasTransitionContract.KEY_Y
import com.android.systemui.yasvara.theme.YasTransitionContract.MESSAGE_END
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Hides a theme change behind a snapshot of the screen, then reveals the new theme through a
 * growing circle.
 *
 * An overlay change reaches every process at a different time, so a plain apply shows a few
 * hundred milliseconds of old and new colours side by side. [begin] freezes the display under a
 * snapshot before anything is written; once SystemUI has redrawn in the new theme and the caller
 * reports [ready], or the timeout passes, the snapshot is cut away from the touch point outwards.
 * The cover never takes input and is always removed, at the latest by a watchdog.
 */
@SysUISingleton
class YasThemeTransition
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainHandler: Handler,
    private val windowManagerService: IWindowManager,
    private val configurationController: ConfigurationController,
    private val themeState: YasThemeState,
    dumpManager: DumpManager,
) : Dumpable {

    private var nextToken = 1
    private var session: Session? = null
    private var lastReport = "none"

    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    /** Called on a binder thread; blocks until the cover is on screen, at most [SHOW_WAIT_MS]. */
    fun begin(extras: Bundle): Bundle {
        val x = extras.getFloat(KEY_X, Float.NaN)
        val y = extras.getFloat(KEY_Y, Float.NaN)
        val timeout = extras.getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS).coerceIn(300, 3000)
        val expectThemeChange = extras.getBoolean(KEY_EXPECT_THEME_CHANGE, true)
        val callback = extras.getParcelable(KEY_CALLBACK, Messenger::class.java)

        val token = synchronized(this) { nextToken++ }
        val snapshot = capture()
        if (snapshot == null) {
            mainHandler.post {
                finish(session, "replaced")
                lastReport = "#$token: no cover (capture failed or secure content)"
            }
            return Bundle().apply {
                putInt(KEY_TOKEN, token)
                putBoolean(KEY_COVER, false)
            }
        }

        val shown = CountDownLatch(1)
        val covered = AtomicBoolean(false)
        mainHandler.post {
            finish(session, "replaced")
            try {
                val s = Session(token, snapshot, x, y, timeout, expectThemeChange, callback)
                session = s
                s.show { onScreen ->
                    covered.set(onScreen)
                    shown.countDown()
                }
            } catch (e: RuntimeException) {
                // The cover only hides the change; the theme applies without it.
                Log.w(TAG, "Cannot build the transition cover", e)
                snapshot.recycle()
                lastReport = "#$token: no cover (${e.javaClass.simpleName})"
                shown.countDown()
            }
        }
        shown.await(SHOW_WAIT_MS, TimeUnit.MILLISECONDS)
        return Bundle().apply {
            putInt(KEY_TOKEN, token)
            putBoolean(KEY_COVER, covered.get())
        }
    }

    /** The caller has drawn in the new theme. */
    fun ready(token: Int) {
        mainHandler.post { session?.takeIf { it.token == token }?.onCallerReady() }
    }

    /** What SystemUI has applied, for the caller to compare with what it asked for. */
    fun end(): Bundle = Bundle().apply { putString(KEY_FINGERPRINT, themeState.fingerprint) }

    private fun capture(): Bitmap? =
        try {
            Trace.beginSection("YasThemeTransition#capture")
            val listener = ScreenCaptureInternal.createSyncCaptureListener()
            windowManagerService.captureDisplay(
                Display.DEFAULT_DISPLAY,
                CaptureArgs.Builder().build(),
                listener,
            )
            val buffer = listener.buffer
            when {
                buffer == null -> null
                // Secure windows come out black; a black flash is worse than no cover.
                buffer.containsSecureLayers() -> null.also { buffer.hardwareBuffer.close() }
                else -> buffer.asBitmap()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Display capture failed", e)
            null
        } catch (e: RemoteException) {
            Log.w(TAG, "Display capture failed", e)
            null
        } finally {
            Trace.endSection()
        }

    private fun finish(s: Session?, why: String) {
        if (s == null || s.finished) return
        s.remove()
        if (session === s) session = null
        lastReport = "#${s.token}: $why after ${SystemClock.uptimeMillis() - s.startedAt} ms"
        Log.i(TAG, "Transition $lastReport")
    }

    private inner class Session(
        val token: Int,
        private val snapshot: Bitmap,
        x: Float,
        y: Float,
        private val timeoutMs: Long,
        private val expectThemeChange: Boolean,
        private val callback: Messenger?,
    ) : ConfigurationController.ConfigurationListener {
        val startedAt: Long = SystemClock.uptimeMillis()
        var finished = false
            private set

        private val windowManager: WindowManager
        private val view: RevealView
        private var callerReady = false
        private var themeChanged = !expectThemeChange
        private var revealing = false
        private var animation: Job? = null
        private val timeout = Runnable { reveal("timeout") }
        private val watchdog = Runnable { finish(this, "watchdog") }

        init {
            val display =
                context.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)
            val windowContext =
                context.createWindowContext(display, LayoutParams.TYPE_SCREENSHOT, null)
            windowManager = windowContext.getSystemService(WindowManager::class.java)
            val feather =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    FEATHER_DP,
                    windowContext.resources.displayMetrics,
                )
            val cx = if (x.isNaN()) snapshot.width / 2f else x
            val cy = if (y.isNaN()) snapshot.height / 2f else y
            view = RevealView(windowContext, snapshot, cx, cy, feather)
        }

        /** Adds the cover; [onShown] says whether it reached the screen. */
        fun show(onShown: (Boolean) -> Unit) {
            val lp =
                LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.TYPE_SCREENSHOT,
                        LayoutParams.FLAG_NOT_FOCUSABLE or
                            LayoutParams.FLAG_NOT_TOUCHABLE or
                            LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT,
                    )
                    .apply {
                        title = TAG
                        layoutInDisplayCutoutMode =
                            LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        fitInsetsTypes = 0
                        windowAnimations = 0
                    }
            view.viewTreeObserver.registerFrameCommitCallback { onShown(true) }
            try {
                windowManager.addView(view, lp)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot show the transition cover", e)
                finished = true
                snapshot.recycle()
                onShown(false)
                return
            }
            configurationController.addCallback(this)
            mainHandler.postDelayed(timeout, timeoutMs)
            mainHandler.postDelayed(watchdog, WATCHDOG_MS)
        }

        override fun onThemeChanged() {
            // Reveal only once SystemUI has drawn a frame in the new theme.
            view.postOnAnimation {
                themeChanged = true
                maybeReveal()
            }
        }

        fun onCallerReady() {
            callerReady = true
            maybeReveal()
        }

        private fun maybeReveal() {
            if (callerReady && themeChanged) reveal("settled")
        }

        private fun reveal(why: String) {
            if (revealing || finished) return
            revealing = true
            mainHandler.removeCallbacks(timeout)
            lastReport = "#$token: revealing ($why)"
            sendEnd()
            val motion = MotionScheme.expressive()
            val reduced = !ValueAnimator.areAnimatorsEnabled()
            animation =
                applicationScope.launch(AndroidUiDispatcher.Main) {
                    try {
                        if (reduced) {
                            animate(1f, 0f, animationSpec = tween(REDUCED_FADE_MS)) { v, _ ->
                                view.alpha = v
                            }
                        } else {
                            val spec: FiniteAnimationSpec<Float> = motion.slowSpatialSpec()
                            animate(0f, view.maxRadius, animationSpec = spec) { v, _ ->
                                view.radius = v
                            }
                        }
                    } finally {
                        finish(this@Session, why)
                    }
                }
        }

        private fun sendEnd() {
            val messenger = callback ?: return
            val message =
                Message.obtain(null, MESSAGE_END).apply {
                    data =
                        Bundle().apply {
                            putInt(KEY_TOKEN, token)
                            putString(KEY_FINGERPRINT, themeState.fingerprint)
                        }
                }
            try {
                messenger.send(message)
            } catch (e: RemoteException) {
                Log.w(TAG, "Transition caller went away", e)
            }
        }

        fun remove() {
            if (finished) return
            finished = true
            animation?.cancel()
            mainHandler.removeCallbacks(timeout)
            mainHandler.removeCallbacks(watchdog)
            configurationController.removeCallback(this)
            if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
            snapshot.recycle()
        }
    }

    /**
     * Draws the snapshot with a feathered circular hole at ([cx], [cy]) in one full-screen shader
     * pass, so the growing hole costs no extra layer.
     */
    private class RevealView(
        context: Context,
        snapshot: Bitmap,
        private val cx: Float,
        private val cy: Float,
        private val feather: Float,
    ) : View(context) {
        private val shader =
            RuntimeShader(REVEAL_SHADER).apply {
                setInputShader(
                    "content",
                    BitmapShader(snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                )
                setFloatUniform("center", cx, cy)
                setFloatUniform("feather", feather)
                setFloatUniform("radius", 0f)
            }
        private val paint = Paint().apply { shader = this@RevealView.shader }

        /** Far enough that the hole, feather included, clears every corner. */
        val maxRadius: Float =
            max(
                max(hypot(cx, cy), hypot(snapshot.width - cx, cy)),
                max(hypot(cx, snapshot.height - cy), hypot(snapshot.width - cx, snapshot.height - cy)),
            ) + feather

        var radius = 0f
            set(value) {
                field = value
                shader.setFloatUniform("radius", value)
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPaint(paint)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("YasThemeTransition:")
        pw.println("  active=${session?.token ?: 0} last=$lastReport")
        pw.println("  fingerprint=${themeState.fingerprint}")
    }

    companion object {
        private const val TAG = "YasThemeTransition"
        private const val DEFAULT_TIMEOUT_MS = 1200L
        private const val WATCHDOG_MS = 3000L
        private const val SHOW_WAIT_MS = 500L
        private const val REDUCED_FADE_MS = 150
        private const val FEATHER_DP = 24f

        private const val REVEAL_SHADER =
            """
            uniform shader content;
            uniform float2 center;
            uniform float radius;
            uniform float feather;

            half4 main(float2 p) {
                float d = distance(p, center);
                half keep = half(smoothstep(radius - feather, radius, d));
                return content.eval(p) * keep;
            }
            """
    }
}
