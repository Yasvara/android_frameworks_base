/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject

/**
 * Turns Yasvara's lock screen customizations off when SystemUI keeps crashing soon after it
 * starts, so a bad clock, widget or depth layer can never lock the user out of their device.
 *
 * Every start counts a strike, and staying up for [STABLE_MS] clears them. At [MAX_STRIKES]
 * safe mode trips and stays on, customizations keep their values but are not drawn, until the
 * user turns it off again from Laras.
 */
@SysUISingleton
class LockSafeMode
@Inject
constructor(
    private val globalSettings: GlobalSettings,
    @Background private val background: DelayableExecutor,
) : CoreStartable {

    private val strikes = globalSettings.getInt(KEY_STRIKES, 0) + 1

    /**
     * Whether lock screen customizations must fall back to the stock lock screen. Known from
     * construction, before the lock screen asks, and fixed for this run of SystemUI.
     */
    val isActive = globalSettings.getInt(KEY_SAFE_MODE, 0) != 0 || strikes >= MAX_STRIKES

    override fun start() {
        background.execute {
            if (isActive) {
                globalSettings.putInt(KEY_SAFE_MODE, 1)
                globalSettings.putInt(KEY_STRIKES, 0)
            } else {
                globalSettings.putInt(KEY_STRIKES, strikes)
                background.executeDelayed({ globalSettings.putInt(KEY_STRIKES, 0) }, STABLE_MS)
            }
        }
    }

    companion object {
        const val KEY_SAFE_MODE = "yasvara_lock_safe_mode"
        const val KEY_STRIKES = "yasvara_lock_strikes"
        const val MAX_STRIKES = 3
        const val STABLE_MS = 60_000L
    }
}
