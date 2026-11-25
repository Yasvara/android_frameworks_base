/*
 * Copyright (C) 2025 The Android Open Source Project
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
 */

package com.android.systemui.keyguard.data.repository

import android.app.WallpaperColors
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Display.DEFAULT_DISPLAY
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.shared.model.ClockSizeSetting
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.android.systemui.shared.clocks.ClockRegistry
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_CLOCK_ID
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_CLOCK_STYLE
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_COLORS
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_DISPLAY_ID
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_HIDE_CLOCK
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_HIGHLIGHT_QUICK_AFFORDANCES
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_HOST_TOKEN
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_VIEW_HEIGHT
import com.android.systemui.shared.quickaffordance.shared.model.KeyguardPreviewConstants.KEY_VIEW_WIDTH
import com.android.systemui.yasvara.theme.PreviewColorSeam
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

@AssistedFactory
interface KeyguardPreviewRepositoryFactory {
    fun create(bundle: Bundle): KeyguardPreviewRepository
}

class KeyguardPreviewRepository
@AssistedInject
constructor(
    @Application context: Context,
    private val clockRepository: KeyguardClockRepository,
    private val clockRegistry: ClockRegistry,
    private val displayManager: DisplayManager,
    @Assisted val request: Bundle,
) {
    val hostToken: IBinder? = request.getBinder(KEY_HOST_TOKEN)
    val targetWidth: Int = request.getInt(KEY_VIEW_WIDTH)
    val targetHeight: Int = request.getInt(KEY_VIEW_HEIGHT)
    val shouldHighlightSelectedAffordance: Boolean =
        request.getBoolean(KEY_HIGHLIGHT_QUICK_AFFORDANCES, false)

    val displayId = request.getInt(KEY_DISPLAY_ID, DEFAULT_DISPLAY)
    val display: Display? = displayManager.getDisplay(displayId)
    val previewContext: Context =
        (display?.let { ContextThemeWrapper(context.createDisplayContext(it), context.getTheme()) }
                ?: context)
            // Yasvara: draft colours the host previews before applying them.
            .let { PreviewColorSeam.wrap(context, it, request) }

    /** [shouldHideClock] here means that we never create and bind the clock views */
    val shouldHideClock: Boolean = request.getBoolean(KEY_HIDE_CLOCK, false)
    val wallpaperColors: WallpaperColors? = request.getParcelable(KEY_COLORS)

    val requestedClockSize: MutableStateFlow<ClockSizeSetting?> = MutableStateFlow(null)

    val overrideClockSettings: ClockSettings? = run {
        val clockId = request.getString(KEY_CLOCK_ID)
        if (clockId == null) return@run null

        // clock seed color handled by KeyguardPreviewRenderer.updateClockAppearance
        return@run ClockSettings(
            clockId = clockId,
            axes =
                ClockAxisStyle {
                    request.getBundle(KEY_CLOCK_STYLE)?.let { bundle ->
                        bundle.keySet().forEach { key -> put(key, bundle.getFloat(key)) }
                    }
                },
        )
    }

    /**
     * A clock face the host is trying out before saving it; while it is null the preview shows
     * the clock the request names, or else the saved one.
     */
    val draftClockSettings: MutableStateFlow<ClockSettings?> = MutableStateFlow(null)

    /**
     * Whether going from [shown] to [next] needs a new clock: another face or seed colour, or
     * an axis that moves the face (its constraints are set only when a clock is placed).
     */
    fun needsNewClock(shown: ClockSettings?, next: ClockSettings?): Boolean {
        if (shown == null || next == null) return shown !== next
        return shown.clockId != next.clockId ||
            shown.seedColor != next.seedColor ||
            LAYOUT_AXES.any { shown.axes[it] != next.axes[it] }
    }

    private companion object {
        /** Axes of Yasvara faces that change where the face is placed. */
        val LAYOUT_AXES = listOf("yas.small.align", "yas.small.scale")
    }

    /**
     * A new clock is built only when the draft names another face or seed colour; a draft that
     * only restyles the face is applied to the clock already shown (see
     * [com.android.systemui.keyguard.ui.preview.KeyguardPreviewRenderer.onClockAxesDraft]), so
     * dragging a slider does not rebuild the clock on every step.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val previewClock: Flow<ClockController> =
        draftClockSettings.distinctUntilChanged { a, b -> !needsNewClock(a, b) }.flatMapLatest { firstDraft ->
            val settings = firstDraft?.let { draftClockSettings.value } ?: overrideClockSettings
            if (settings != null) {
                flow {
                    emit(clockRegistry.createPreviewClockAsync(previewContext, settings).await())
                }
            } else {
                clockRepository.currentClockId.map {
                    // We should create a new instance for each collect call cause in preview, the
                    // same clock will be attached to a different parent view at the same time.
                    clockRegistry.createCurrentClock(previewContext)
                }
            }
        }
}
