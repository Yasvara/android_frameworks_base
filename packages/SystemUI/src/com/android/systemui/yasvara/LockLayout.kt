/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara

import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the clock sits on the lock screen. */
enum class ClockPlacement {
    TOP,
    /** Just above the lock icon or fingerprint sensor, within thumb reach. */
    BOTTOM,
}

/**
 * The user's arrangement of the lock screen (see docs/specs/0001, placement). Sections read it
 * when they build their constraints, and a change re-applies the keyguard blueprint so the
 * elements animate to their new places.
 */
@SysUISingleton
class LockLayout
@Inject
constructor(
    private val secureSettings: SecureSettings,
    private val safeMode: LockSafeMode,
    private val blueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
    @Application private val scope: CoroutineScope,
    @Background private val background: CoroutineDispatcher,
) : CoreStartable {

    val clockPlacement: StateFlow<ClockPlacement> =
        secureSettings
            .observerFlow(UserHandle.USER_ALL, KEY_CLOCK_PLACEMENT)
            .map { readClockPlacement() }
            .flowOn(background)
            .stateIn(scope, SharingStarted.Eagerly, readClockPlacement())

    override fun start() {
        scope.launch {
            clockPlacement.drop(1).collect { refresh() }
        }
        // A clock face places itself when constraints are built; a restyle that moves it (such
        // as the small clock's size or alignment) needs them built again.
        scope.launch {
            secureSettings
                .observerFlow(UserHandle.USER_ALL, Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE)
                .flowOn(background)
                .collect { refresh() }
        }
    }

    private fun refresh() {
        blueprintInteractor.get().refreshBlueprint(IntraBlueprintTransition.Type.DefaultTransition)
    }

    private fun readClockPlacement(): ClockPlacement {
        if (safeMode.isActive) return ClockPlacement.TOP
        val raw = secureSettings.getIntForUser(KEY_CLOCK_PLACEMENT, 0, UserHandle.USER_CURRENT)
        return ClockPlacement.entries.getOrElse(raw) { ClockPlacement.TOP }
    }

    companion object {
        const val KEY_CLOCK_PLACEMENT = "yasvara_lock_clock_placement"

        /** Vertical bias of the large clock between the top of the screen and the lock icon. */
        fun ClockPlacement.largeClockBias() = if (this == ClockPlacement.BOTTOM) 1f else 0.5f
    }
}
