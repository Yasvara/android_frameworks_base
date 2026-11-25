/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.systemui.keyguard.ui.preview

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.ArrayMap
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.traceSection
import com.android.systemui.Flags
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.app.tracing.coroutines.runBlockingTraced as runBlocking
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.MainImmediate
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.CLOCK_SIZE_DYNAMIC
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.CLOCK_SIZE_SMALL
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_CLOCK_SIZE
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_HIDE_SMART_SPACE
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_INITIALLY_SELECTED_SLOT_ID
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_QUICK_AFFORDANCE_ID
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_SLOT_ID
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_YAS_CLOCK_FACE
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_YAS_CLOCK_PLACEMENT
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_YAS_PROTOCOL
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_DEFAULT_PREVIEW
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_HIDE_SMART_SPACE
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_PREVIEW_CLOCK_SIZE
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_PREVIEW_QUICK_AFFORDANCE_SELECTED
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_SLOT_SELECTED
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_START_CUSTOMIZING_QUICK_AFFORDANCES
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_YAS_DRAFT
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.MESSAGE_ID_YAS_DRAFT_CLEAR
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.YAS_PROTOCOL_VERSION
import com.android.systemui.util.kotlin.logD
import com.android.systemui.yasvara.ClockPlacement
import com.android.systemui.yasvara.preview.StatusBarPreviewManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.json.JSONException
import org.json.JSONObject

@SysUISingleton
class KeyguardRemotePreviewManager
@Inject
constructor(
    private val previewFactory: KeyguardPreviewFactory,
    @MainImmediate private val mainImmediateScope: CoroutineScope,
    @MainImmediate private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundHandler: Handler,
    // Yasvara: previews of surfaces other than the lock screen.
    private val statusBarPreviewManager: StatusBarPreviewManager,
) {
    private val activePreviews: ArrayMap<Pair<IBinder?, Int>, PreviewLifecycleObserver> =
        ArrayMap<Pair<IBinder?, Int>, PreviewLifecycleObserver>()

    fun preview(request: Bundle?): Bundle? {
        if (request == null) {
            return null
        }
        // Yasvara: a host asking for another surface gets that renderer instead.
        if (request.containsKey(StatusBarPreviewManager.KEY_YAS_SURFACE)) {
            return statusBarPreviewManager.preview(request)
        }

        var observer: PreviewLifecycleObserver? = null
        return try {
            val preview = if (Flags.doNotUseRunBlocking()) {
                traceSection("$TAG#previewRepositoryFactory.create") {
                    previewFactory.create(request)
                }
            } else {
                runBlocking("$TAG#previewRepositoryFactory.create", mainDispatcher) {
                    previewFactory.create(request)
                }
            }

            observer =
                PreviewLifecycleObserver(
                    mainImmediateScope,
                    mainDispatcher,
                    preview,
                    ::destroyObserver,
                )

            logD(TAG) { "Created observer $observer" }

            // Destroy any previous renderer associated with this token.
            activePreviews[preview.id]?.let { destroyObserver(it) }
            activePreviews[preview.id] = observer
            preview.renderer.render()
            preview.repository.hostToken?.linkToDeath(observer, 0)
            val result = Bundle()
            result.putParcelable(KEY_PREVIEW_SURFACE_PACKAGE, preview.renderer.surfacePackage)
            val messenger = Messenger(Handler(backgroundHandler.looper, observer))
            // NOTE: The process on the other side can retain messenger indefinitely.
            // (e.g. GC might not trigger and cleanup the reference)
            val msg = Message.obtain()
            msg.replyTo = messenger
            result.putParcelable(KEY_PREVIEW_CALLBACK, msg)
            result.putInt(KEY_YAS_PROTOCOL, YAS_PROTOCOL_VERSION)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Unable to generate preview", e)
            observer?.let { destroyObserver(it) }
            null
        }
    }

    private fun destroyObserver(observer: PreviewLifecycleObserver) {
        observer.onDestroy()?.let { identifier ->
            if (activePreviews[identifier] === observer) {
                activePreviews.remove(identifier)
            }
        }
    }

    companion object {
        internal const val TAG = "KeyguardRemotePreviewManager"
        @VisibleForTesting const val KEY_PREVIEW_SURFACE_PACKAGE = "surface_package"
        @VisibleForTesting const val KEY_PREVIEW_CALLBACK = "callback"
    }
}

/**
 * Handles messages from the other process and handles cleanup.
 *
 * NOTE: The other process might hold on to reference of this class indefinitely. It's entirely
 * possible that GC won't trigger and we'll leak this for all times even if [onDestroy] was called.
 * This helps make sure no non-Singleton objects are retained beyond destruction to prevent leaks.
 */
@VisibleForTesting(VisibleForTesting.PRIVATE)
class PreviewLifecycleObserver(
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val preview: KeyguardPreview,
    onDestroy: (PreviewLifecycleObserver) -> Unit,
) : Handler.Callback, IBinder.DeathRecipient {

    private var isDestroyedOrDestroying = false
    // These two are null after destruction
    private val repository = preview.repository
    @VisibleForTesting var renderer: KeyguardPreviewRenderer? = preview.renderer
    @VisibleForTesting var onDestroy: ((PreviewLifecycleObserver) -> Unit)? = onDestroy

    override fun handleMessage(message: Message): Boolean {
        if (isDestroyedOrDestroying) {
            return true
        }

        if (renderer == null || onDestroy == null) {
            Log.wtf(TAG, "Renderer/onDestroy should not be null.")
            return true
        }

        when (message.what) {
            MESSAGE_ID_START_CUSTOMIZING_QUICK_AFFORDANCES -> {
                renderer?.onStartCustomizingQuickAffordances(
                    initiallySelectedSlotId =
                        message.data.getString(KEY_INITIALLY_SELECTED_SLOT_ID)
                            ?: SLOT_ID_BOTTOM_START
                )
            }
            MESSAGE_ID_SLOT_SELECTED -> {
                message.data.getString(KEY_SLOT_ID)?.let { slotId ->
                    renderer?.onSlotSelected(slotId = slotId)
                }
            }
            MESSAGE_ID_PREVIEW_QUICK_AFFORDANCE_SELECTED -> {
                val slotId = message.data.getString(KEY_SLOT_ID)
                val quickAffordanceId = message.data.getString(KEY_QUICK_AFFORDANCE_ID)
                if (slotId != null && quickAffordanceId != null) {
                    renderer?.onPreviewQuickAffordanceSelected(
                        slotId = slotId,
                        quickAffordanceId = quickAffordanceId,
                    )
                }
            }
            MESSAGE_ID_DEFAULT_PREVIEW -> {
                renderer?.onDefaultPreview()
            }
            MESSAGE_ID_HIDE_SMART_SPACE -> {
                renderer?.hideSmartspace(message.data.getBoolean(KEY_HIDE_SMART_SPACE))
            }
            MESSAGE_ID_PREVIEW_CLOCK_SIZE -> {
                message.data
                    .getString(KEY_CLOCK_SIZE)
                    ?.let {
                        when (it) {
                            CLOCK_SIZE_DYNAMIC -> ClockSizeSetting.DYNAMIC
                            CLOCK_SIZE_SMALL -> ClockSizeSetting.SMALL
                            else -> null
                        }
                    }
                    ?.also {
                        repository.requestedClockSize.value = it
                        renderer?.onClockSizeSelected(it)
                    }
            }
            MESSAGE_ID_YAS_DRAFT -> onDraft(message.data)
            MESSAGE_ID_YAS_DRAFT_CLEAR -> {
                repository.draftClockSettings.value = null
                renderer?.onClockPlacementDraft(null)
            }
            else -> onDestroy?.invoke(this)
        }

        return true
    }

    /** Previews the drafts in [data]; a malformed draft is ignored rather than ending the preview. */
    private fun onDraft(data: Bundle) {
        if (data.containsKey(KEY_YAS_CLOCK_PLACEMENT)) {
            renderer?.onClockPlacementDraft(
                ClockPlacement.entries.getOrNull(data.getInt(KEY_YAS_CLOCK_PLACEMENT))
            )
        }
        data.getString(KEY_YAS_CLOCK_FACE)?.let { json ->
            try {
                val draft = ClockSettings.fromJson(JSONObject(json))
                val shown = repository.draftClockSettings.value
                repository.draftClockSettings.value = draft
                if (shown != null && !repository.needsNewClock(shown, draft)) {
                    renderer?.onClockAxesDraft(draft.axes)
                }
            } catch (e: JSONException) {
                Log.w(TAG, "Ignoring malformed clock face draft", e)
            }
        }
    }

    override fun binderDied() {
        onDestroy?.invoke(this)
    }

    fun onDestroy(): Pair<IBinder?, Int>? {
        if (isDestroyedOrDestroying) {
            return null
        }

        logD(TAG) { "Destroying $this" }

        isDestroyedOrDestroying = true
        return renderer?.let { rendererToDestroy ->
            this.renderer = null
            this.onDestroy = null
            val hostToken = repository.hostToken
            hostToken?.unlinkToDeath(this, 0)
            scope.launch(context = mainDispatcher) { rendererToDestroy.destroy() }
            preview.id
        }
    }

    companion object {
        private const val TAG = "KeyguardRemotePreviewManager"
    }
}
