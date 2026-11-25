/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.withFrameNanos
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/**
 * Draws every option of a picker with the status bar's own code (`YAS_SAMPLE`),
 * so the host's choices look exactly like what they apply. A second status bar, laid out in the
 * preview window but never shown ([SampleHost]), takes each drafted value in turn; the part it
 * changes is drawn into a bitmap and sent to the host, one message per option.
 *
 * The samples follow the canvas: its simulated states and drafts are copied first.
 */
internal class StatusBarSampler(
    private val host: SampleHost,
    private val canvasDraft: StatusBarDraft,
    private val scope: CoroutineScope,
) {
    private class Request(val values: List<String>, val replyTo: Messenger)

    /** Requests not yet run, by kind: a newer one replaces an older one of its kind. */
    private val pending = LinkedHashMap<String, Request>()
    private var running: String? = null
    private var current: Job? = null
    private var drainer: Job? = null

    /**
     * Samples [values] of [kind] (`battery` or `icons`) for [replyTo]. Kinds run one after
     * another, since they share one status bar; a newer request of the kind running now
     * restarts it.
     */
    fun sample(kind: String, values: List<String>, replyTo: Messenger) {
        if (PARTS[kind] == null) {
            Log.w(TAG, "Unknown sample kind $kind")
            return
        }
        pending.remove(kind)
        pending[kind] = Request(values, replyTo)
        if (running == kind) current?.cancel()
        if (drainer?.isActive != true) drainer = scope.launch { drain() }
    }

    private suspend fun drain() {
        try {
            while (true) {
                val kind = pending.keys.firstOrNull() ?: return
                val request = pending.remove(kind)!!
                running = kind
                // Its own job, so a restart cancels this run and not the queue.
                val run = scope.launch { run(kind, PARTS.getValue(kind), request) }
                current = run
                run.join()
            }
        } finally {
            running = null
            current = null
        }
    }

    private suspend fun run(kind: String, part: Part, request: Request) {
        val base = Bundle(canvasDraft.values.value)
        base.putString(StatusBarDraft.KEY_SIM, part.sim(base.getString(StatusBarDraft.KEY_SIM)))
        host.draft.clear()
        host.draft.update(base)
        Log.i(TAG, "Sampling ${request.values.size} $kind options")
        var sent = 0
        for (value in request.values) {
            host.draft.update(part.draft(value) ?: continue)
            settle()
            val view = host.findViewById<View>(part.viewId)
            if (view == null || !view.isShown || view.width <= 0) {
                Log.d(TAG, "$kind $value: nothing drawn ($view)")
                continue
            }
            val bitmap = capture(view) ?: continue
            if (!send(request.replyTo, kind, value, bitmap)) return
            sent++
        }
        Log.i(TAG, "Sent $sent of ${request.values.size} $kind samples")
    }

    /** Waits until a drafted value has reached the views: bound, recomposed and laid out. */
    private suspend fun settle() {
        repeat(SETTLE_FRAMES) { withFrameNanos {} }
        var extra = 0
        while (host.isLayoutRequested && extra++ < MAX_EXTRA_FRAMES) withFrameNanos {}
    }

    private fun send(replyTo: Messenger, kind: String, value: String, bitmap: Bitmap): Boolean {
        val message =
            Message.obtain(null, MESSAGE_YAS_SAMPLE_RESULT).apply {
                data =
                    Bundle().apply {
                        putString(KEY_KIND, kind)
                        putString(KEY_VALUE, value)
                        putParcelable(KEY_BITMAP, bitmap)
                    }
            }
        return try {
            replyTo.send(message)
            true
        } catch (e: RemoteException) {
            Log.w(TAG, "Sample host went away", e)
            false
        }
    }

    /**
     * Draws [view] alone, as it draws itself in the status bar, without the window's transforms:
     * at the status bar's own pixel size. Through the GPU, since the status bar draws with
     * render nodes a software canvas cannot replay.
     */
    private fun capture(view: View): Bitmap? {
        val width = view.width
        val height = view.height
        val node = RenderNode(TAG).apply { setPosition(0, 0, width, height) }
        val canvas = node.beginRecording(width, height)
        try {
            view.draw(canvas)
        } finally {
            node.endRecording()
        }
        val reader =
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                1,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
            )
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.isOpaque = false
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage() ?: return null
            try {
                val buffer = image.hardwareBuffer ?: return null
                try {
                    return Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    buffer.close()
                }
            } finally {
                image.close()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not draw a sample", e)
            return null
        } finally {
            renderer.destroy()
            reader.close()
            node.discardDisplayList()
        }
    }

    /** A kind of sample: the draft key it varies, the view it draws, the state it shows. */
    private class Part(
        val viewId: Int,
        val draft: (String) -> Bundle?,
        val sim: (String?) -> String? = { it },
    )

    companion object {
        private const val TAG = "StatusBarSampler"
        const val MESSAGE_YAS_SAMPLE_RESULT = 0x59410102
        const val KEY_KIND = "kind"
        const val KEY_VALUE = "value"
        const val KEY_BITMAP = "bitmap"
        private const val SETTLE_FRAMES = 3
        private const val MAX_EXTRA_FRAMES = 6

        private val PARTS =
            mapOf(
                "battery" to
                    Part(R.id.battery_composable_view, { value ->
                        value.toIntOrNull()?.let {
                            Bundle().apply { putInt(StatusBarDraft.KEY_BATTERY_STYLE, it) }
                        }
                    }),
                "icons" to
                    Part(
                        R.id.statusIcons,
                        { value -> Bundle().apply { putString(StatusBarDraft.KEY_ICON_SET, value) } },
                        ::withSignal,
                    ),
            )

        /**
         * [sim] with a mobile signal and Wi-Fi shown when the canvas simulates neither: an icon
         * set is told apart by those, so each sample shows them.
         */
        private fun withSignal(sim: String?): String {
            val json =
                try {
                    sim?.let(::JSONObject) ?: JSONObject()
                } catch (e: JSONException) {
                    JSONObject()
                }
            if (!json.has("mobile")) {
                json.put("mobile", JSONObject().put("level", 3).put("levels", 4).put("type", "5G"))
            }
            if (!json.has("wifi")) json.put("wifi", JSONObject().put("level", 3))
            return json.toString()
        }
    }
}

/**
 * Holds the sampling status bar inside the preview window: laid out and bound like the canvas's,
 * but never drawn on screen and hidden from accessibility.
 */
internal class SampleHost(context: Context) : FrameLayout(context), StatusBarPreviewHost {
    val draft = StatusBarDraft()
    override var preview: StatusBarPreviewScope? = null

    init {
        alpha = 0f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
}
