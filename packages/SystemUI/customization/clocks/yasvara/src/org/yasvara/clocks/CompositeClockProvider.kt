/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import android.content.Context
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMessageBuffers
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMetadata
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPickerConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockProvider
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings

/**
 * Presents several providers as the one built-in provider SystemUI's clock registry takes. The
 * first provider must offer the default clock. [isEnabled] can hide the others, so their clocks
 * fall back to the default one (as in lock screen safe mode) without losing the user's choice.
 */
class CompositeClockProvider(
    private val primary: ClockProvider,
    private val others: List<ClockProvider>,
    private val isEnabled: () -> Boolean = { true },
) : ClockProvider {
    private val providers get() = if (isEnabled()) listOf(primary) + others else listOf(primary)

    override fun initialize(buffers: ClockMessageBuffers?) {
        primary.initialize(buffers)
        others.forEach { it.initialize(buffers) }
    }

    override fun getClocks(): List<ClockMetadata> = providers.flatMap { it.getClocks() }

    override fun createClock(ctx: Context, settings: ClockSettings): ClockController? {
        val provider = providerOf(settings) ?: return null
        // A face that fails to build must never take the lock screen down with it.
        return runCatching { provider.createClock(ctx, settings) }
            .onFailure { android.util.Log.e(TAG, "Unable to create ${settings.clockId}", it) }
            .getOrNull()
    }

    override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig =
        (providerOf(settings) ?: primary).getClockPickerConfig(settings)

    private fun providerOf(settings: ClockSettings) =
        providers.firstOrNull { provider -> provider.getClocks().any { it.clockId == settings.clockId } }

    private companion object {
        const val TAG = "CompositeClockProvider"
    }
}
