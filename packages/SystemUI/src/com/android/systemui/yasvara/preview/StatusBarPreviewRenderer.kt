/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.InputTransferToken
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.compositionContext
import com.android.app.displaylib.PerDisplayRepository
import com.android.internal.policy.SystemBarUtils
import com.android.systemui.compose.ComposeInitializer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.display.dagger.ReferenceSysUIDisplaySubcomponent
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Serves `call("preview", {yas_surface: "status_bar", …})`: the real home status bar, built by
 * the same [com.android.systemui.statusbar.pipeline.shared.ui.composable.StatusBarRootFactory]
 * as the one on screen, drawn into a surface the host embeds.
 *
 * The preview shows the saved settings, which Laras applies as they change, and the device's own
 * state with what the host simulates on top ([StatusBarSim], sent in the draft's `sim`).
 */
@SysUISingleton
class StatusBarPreviewManager
@Inject
constructor(
    @Application private val context: Context,
    @Main private val mainHandler: Handler,
    @Background private val backgroundHandler: Handler,
    private val displaySubcomponents: PerDisplayRepository<SystemUIDisplaySubcomponent>,
    private val batteryRepository: BatteryRepository,
) {
    private val active = mutableMapOf<IBinder, StatusBarPreviewRenderer>()

    /** Returns the preview result, or null when [request] is not for a surface we render. */
    fun preview(request: Bundle): Bundle? {
        if (request.getString(KEY_YAS_SURFACE) != SURFACE_STATUS_BAR) return null
        val hostToken = request.getBinder(KEY_HOST_TOKEN) ?: return null
        val renderer =
            StatusBarPreviewRenderer(
                context,
                mainHandler,
                displaySubcomponents,
                batteryRepository,
                request,
            )
        mainHandler.runWithScissors({ renderer.render() }, RENDER_WAIT_MS)
        val surfacePackage =
            renderer.surfacePackage ?: return null.also { mainHandler.post { renderer.destroy() } }
        synchronized(active) { active.put(hostToken, renderer) }?.let { old ->
            mainHandler.post { old.destroy() }
        }
        val destroy = {
            synchronized(active) { if (active[hostToken] === renderer) active.remove(hostToken) }
            mainHandler.post { renderer.destroy() }
        }
        try {
            hostToken.linkToDeath({ destroy() }, 0)
        } catch (e: RemoteException) {
            destroy()
            return null
        }
        val messenger =
            Messenger(
                Handler(backgroundHandler.looper) { message ->
                    when (message.what) {
                        MESSAGE_YAS_DRAFT -> renderer.draft.update(message.data)
                        MESSAGE_YAS_DRAFT_CLEAR -> renderer.draft.clear()
                        MESSAGE_YAS_SAMPLE -> {
                            val replyTo = message.replyTo
                            val kind = message.data.getString(KEY_SAMPLE_KIND)
                            val values = message.data.getStringArray(KEY_SAMPLE_VALUES)
                            if (replyTo != null && kind != null && values != null) {
                                mainHandler.post { renderer.sample(kind, values.toList(), replyTo) }
                            }
                        }
                        // As for the lock screen preview, anything unknown ends the preview.
                        else -> destroy()
                    }
                    true
                }
            )
        return Bundle().apply {
            putParcelable(KEY_SURFACE_PACKAGE, surfacePackage)
            putParcelable(KEY_CALLBACK, Message.obtain().also { it.replyTo = messenger })
            putInt(KEY_YAS_PROTOCOL, YAS_PROTOCOL_VERSION)
        }
    }

    companion object {
        const val KEY_YAS_SURFACE = "yas_surface"
        const val SURFACE_STATUS_BAR = "status_bar"
        private const val KEY_HOST_TOKEN = "host_token"
        private const val KEY_SURFACE_PACKAGE = "surface_package"
        private const val KEY_CALLBACK = "callback"
        private const val KEY_YAS_PROTOCOL = "yas_protocol"
        /** 3: the host may ask for option samples (`YAS_SAMPLE`). */
        private const val YAS_PROTOCOL_VERSION = 3
        private const val MESSAGE_YAS_DRAFT = 0x59410001
        private const val MESSAGE_YAS_DRAFT_CLEAR = 0x59410002
        private const val MESSAGE_YAS_SAMPLE = 0x59410003
        private const val KEY_SAMPLE_KIND = "kind"
        private const val KEY_SAMPLE_VALUES = "values"
        private const val RENDER_WAIT_MS = 1000L
    }
}

/**
 * Draft values the host previews on the status bar canvas: battery style and percent, icon set
 * and simulated device states. Unknown keys are kept for newer consumers.
 */
class StatusBarDraft {
    private val _values = MutableStateFlow(Bundle.EMPTY)
    val values: StateFlow<Bundle> = _values.asStateFlow()

    fun update(changes: Bundle) {
        _values.value = Bundle(_values.value).apply { putAll(changes) }
    }

    fun clear() {
        _values.value = Bundle.EMPTY
    }

    companion object {
        const val KEY_BATTERY_STYLE = "battery_style"
        const val KEY_BATTERY_PERCENT = "battery_percent"
        const val KEY_ICON_SET = "icon_set"
        const val KEY_SIM = "sim"
    }
}

/**
 * One status bar preview, living on the main thread.
 *
 * The preview runs SystemUI's own status bar code in a window it was not written for, so it must
 * not be able to take SystemUI down: a failure while the preview is built, composed, measured,
 * laid out or drawn closes the preview instead, and the host's canvas stays empty.
 */
class StatusBarPreviewRenderer(
    private val context: Context,
    private val mainHandler: Handler,
    private val displaySubcomponents: PerDisplayRepository<SystemUIDisplaySubcomponent>,
    private val batteryRepository: BatteryRepository,
    private val request: Bundle,
) {
    val draft = StatusBarDraft()
    var surfacePackage: SurfaceControlViewHost.SurfacePackage? = null
        private set

    private var host: SurfaceControlViewHost? = null
    private var scope: CoroutineScope? = null
    private var sampler: StatusBarSampler? = null
    private var makeSampler: (() -> StatusBarSampler)? = null
    private var destroyed = false

    fun render() {
        try {
            build()
        } catch (e: Exception) {
            Log.e(TAG, "Could not build the status bar preview", e)
            destroy()
        }
    }

    private fun build() {
        val display =
            context.getSystemService(DisplayManager::class.java)
                .getDisplay(request.getInt(KEY_DISPLAY_ID, Display.DEFAULT_DISPLAY))
                ?: return
        val previewContext =
            ContextThemeWrapper(context.createDisplayContext(display), context.theme)
        val factory =
            (displaySubcomponents.getOrDefault(display.displayId)
                    as? ReferenceSysUIDisplaySubcomponent)
                ?.statusBarRootFactory
        if (factory == null) {
            Log.w(TAG, "No status bar factory for display ${display.displayId}")
            return
        }

        // The preview composes on a recomposer of its own, whose failures close the preview
        // instead of reaching the main looper.
        val scope =
            CoroutineScope(
                AndroidUiDispatcher.Main +
                    SupervisorJob() +
                    CoroutineExceptionHandler { _, e -> fail(e) }
            )
        this.scope = scope
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }

        val root = PreviewRoot(previewContext, ::fail)
        root.preview = StatusBarPreviewScope(previewContext, draft, batteryRepository, scope)
        root.compositionContext = recomposer
        // The status bar's composables need a lifecycle on the window root.
        root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) =
                    ComposeInitializer.onAttachedToWindow(v)

                override fun onViewDetachedFromWindow(v: View) =
                    ComposeInitializer.onDetachedFromWindow(v)
            }
        )
        root.addView(
            factory.create(root) { statusBar ->
                prepare(statusBar)
                reportBounds(root, statusBar)
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // The sampling status bar is built on the first request only: most sessions never ask.
        makeSampler = {
            val sampleHost = SampleHost(previewContext)
            sampleHost.preview =
                StatusBarPreviewScope(previewContext, sampleHost.draft, batteryRepository, scope)
            sampleHost.addView(
                factory.create(sampleHost, ::prepare),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            root.addView(
                sampleHost,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            StatusBarSampler(sampleHost, draft, scope)
        }

        val width = Point().also(display::getRealSize).x
        val height = SystemBarUtils.getStatusBarHeight(previewContext)
        val targetWidth = request.getInt(KEY_VIEW_WIDTH, width)
        val scale = targetWidth / width.toFloat()
        root.pivotX = 0f
        root.pivotY = 0f
        root.scaleX = scale
        root.scaleY = scale

        val viewHost =
            SurfaceControlViewHost(
                context,
                display,
                request.getBinder(KEY_HOST_TOKEN)?.let { InputTransferToken(it) },
                TAG,
            )
        host = viewHost
        // The window measures and lays out the root once it is attached: Compose cannot measure
        // before it has the window's lifecycle. The host handles taps itself, from the reported
        // bounds; the preview takes no input, so it can never drag the real shade open.
        viewHost.setView(
            root,
            WindowManager.LayoutParams(
                    width,
                    height,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSPARENT,
                )
                .apply { title = TAG },
        )
        surfacePackage = viewHost.surfacePackage
    }

    /** Draws each of [values] for the host's picker; see [StatusBarSampler]. Main thread. */
    fun sample(kind: String, values: List<String>, replyTo: Messenger) {
        if (destroyed) return
        val sampler =
            sampler
                ?: try {
                    makeSampler?.invoke()?.also { sampler = it }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not build the sampling status bar", e)
                    makeSampler = null
                    null
                }
                ?: return
        sampler.sample(kind, values, replyTo)
    }

    /** Closes the preview after a failure in its composition or in a traversal of its window. */
    private fun fail(e: Throwable) {
        Log.e(TAG, "Status bar preview failed; closing it", e)
        // This runs in the middle of a frame or a traversal: release once it has unwound.
        mainHandler.post { destroy() }
    }

    /**
     * Does for the preview what the live status bar's `HomeStatusBarComponent` does when it is
     * created: its binders expect the operator name stub to be inflated before they first run
     * (`HomeStatusBarModule.provideOperatorNameView`). The component itself is not created, since
     * it would register the preview as the display's status bar.
     */
    private fun prepare(statusBar: ViewGroup) {
        statusBar.findViewById<ViewStub>(R.id.operator_name_stub)?.inflate()?.visibility =
            View.GONE
    }

    /**
     * Tells the host where the status bar's parts sit, in the status bar's own pixels (the
     * display's, before the preview is scaled), each time one of them moves or resizes: the clock
     * changes place with its position setting, the icons with the device state.
     */
    private fun reportBounds(root: ViewGroup, statusBar: ViewGroup) {
        val callback = request.getParcelable(KEY_HOST_CALLBACK, Messenger::class.java) ?: return
        var reported: Map<String, Rect>? = null
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val parts = partBounds(root, statusBar)
            if (parts == reported) return@addOnGlobalLayoutListener
            reported = parts
            val bounds = Bundle().apply { parts.forEach { (name, rect) -> putParcelable(name, rect) } }
            val message =
                Message.obtain(null, MESSAGE_YAS_BOUNDS).apply {
                    data = Bundle().apply { putBundle(KEY_YAS_BOUNDS, bounds) }
                }
            try {
                callback.send(message)
            } catch (e: RemoteException) {
                Log.w(TAG, "Preview host went away", e)
            }
        }
    }

    /** Each part's first shown view, in [root]'s coordinates. */
    private fun partBounds(root: ViewGroup, statusBar: ViewGroup): Map<String, Rect> = buildMap {
        for ((name, ids) in ELEMENTS) {
            val view =
                ids.firstNotNullOfOrNull { id ->
                    statusBar.findViewById<View>(id)?.takeIf { it.isShown && it.width > 0 }
                } ?: continue
            val rect = Rect(0, 0, view.width, view.height)
            try {
                root.offsetDescendantRectToMyCoords(view, rect)
            } catch (e: IllegalArgumentException) {
                continue // Detached between the lookup and now.
            }
            put(name, rect)
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        surfacePackage?.release()
        surfacePackage = null
        host?.release()
        host = null
        scope?.cancel()
        scope = null
    }

    private companion object {
        const val TAG = "StatusBarPreview"
        const val KEY_HOST_TOKEN = "host_token"
        const val KEY_VIEW_WIDTH = "width"
        const val KEY_DISPLAY_ID = "display_id"
        const val KEY_HOST_CALLBACK = "host_callback"
        const val KEY_YAS_BOUNDS = "bounds"
        const val MESSAGE_YAS_BOUNDS = 0x59410101

        /**
         * Parts the host can tap on the canvas (`YAS_BOUNDS`), each with the views
         * that can show it; the first one shown is reported.
         */
        val ELEMENTS =
            listOf(
                "clock" to listOf(R.id.clock, R.id.clock_center, R.id.clock_right),
                "notification_icons" to listOf(R.id.notificationIcons),
                "status_icons" to listOf(R.id.statusIcons),
                "battery" to listOf(R.id.battery_composable_view),
                "system_icons" to listOf(R.id.system_icons),
            )
    }
}

/**
 * The preview window's root. A failure while the status bar below it is measured, laid out or
 * drawn is reported to [onFailure] once, instead of reaching the main looper; the root then stays
 * empty until the preview is closed.
 */
private class PreviewRoot(context: Context, private val onFailure: (Throwable) -> Unit) :
    FrameLayout(context), StatusBarPreviewHost {
    private var failed = false

    override var preview: StatusBarPreviewScope? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!failed) {
            try {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            } catch (e: Exception) {
                fail(e)
            }
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (failed) return
        try {
            super.onLayout(changed, left, top, right, bottom)
        } catch (e: Exception) {
            fail(e)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (failed) return
        try {
            super.dispatchDraw(canvas)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        failed = true
        onFailure(e)
    }
}
