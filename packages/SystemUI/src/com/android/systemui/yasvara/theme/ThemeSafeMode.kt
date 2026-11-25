/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.theme

import android.database.ContentObserver
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns one family of Yasvara customizations off when SystemUI keeps failing because of it, so
 * a bad colour spec or theme can never leave the user with a crashing phone.
 *
 * Every SystemUI start while the family is in use counts a strike, and staying up for
 * [STABLE_MS] clears them; a failure the family's own code catches counts one too. At
 * [MAX_STRIKES] safe mode trips and stays on: the family's settings keep their values but are
 * ignored, until the user asks Laras to try again, which clears [safeKey].
 */
abstract class StrikeCounter(
    private val globalSettings: GlobalSettings,
    private val background: DelayableExecutor,
    private val strikesKey: String,
    private val safeKey: String,
    private val inUse: () -> Boolean,
) : CoreStartable {

    private val strikesAtStart = globalSettings.getInt(strikesKey, 0)

    @Volatile
    private var tripped =
        globalSettings.getInt(safeKey, 0) != 0 || strikesAtStart + 1 >= MAX_STRIKES

    private val _active = MutableStateFlow(tripped)

    /** Whether the family must fall back to stock behaviour. */
    val isActive: Boolean
        get() = tripped

    /** [isActive] over time, for hooks that re-evaluate when the user restores the family. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    override fun start() {
        // Laras clears the flag when the user asks to try again.
        globalSettings.registerContentObserverAsync(
            globalSettings.getUriFor(safeKey),
            false,
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    if (tripped && globalSettings.getInt(safeKey, 0) == 0) {
                        Log.i(TAG, "$safeKey cleared, trying again")
                        tripped = false
                        _active.value = false
                    }
                }
            },
        )
        background.execute {
            if (tripped) {
                trip("crash loop")
                return@execute
            }
            if (!inUse()) return@execute
            globalSettings.putInt(strikesKey, strikesAtStart + 1)
            background.executeDelayed({ globalSettings.putInt(strikesKey, 0) }, STABLE_MS)
        }
    }

    /** Counts a failure the family caught itself (a hook that threw, a file that failed). */
    fun recordFailure(reason: String) {
        background.execute {
            val strikes = globalSettings.getInt(strikesKey, 0) + 1
            Log.w(TAG, "$strikesKey: failure ($reason), $strikes strikes")
            if (strikes >= MAX_STRIKES) trip(reason) else globalSettings.putInt(strikesKey, strikes)
        }
    }

    private fun trip(reason: String) {
        if (globalSettings.getInt(safeKey, 0) == 0) Log.w(TAG, "$safeKey tripped: $reason")
        tripped = true
        _active.value = true
        globalSettings.putInt(safeKey, 1)
        globalSettings.putInt(strikesKey, 0)
    }

    fun dump(pw: PrintWriter) {
        pw.println("  $safeKey=${if (tripped) 1 else 0} strikesAtStart=$strikesAtStart")
    }

    companion object {
        private const val TAG = "YasSafeMode"
        const val MAX_STRIKES = 3
        const val STABLE_MS = 60_000L
    }
}

/** theme components (`yasvara_theme`) and the Yasvara theme overlays. */
@SysUISingleton
class ThemeSafeMode
@Inject
constructor(
    globalSettings: GlobalSettings,
    secureSettings: SecureSettings,
    @Background background: DelayableExecutor,
) :
    StrikeCounter(
        globalSettings,
        background,
        KEY_STRIKES,
        KEY_SAFE,
        inUse = { secureSettings.getString(YasThemeKeys.THEME) != null },
    ) {
    companion object {
        const val KEY_STRIKES = "yasvara_theme_strikes"
        const val KEY_SAFE = "yasvara_theme_safe"
    }
}

/** the Yasvara colour engine (`yasvara_color`). */
@SysUISingleton
class ColorSafeMode
@Inject
constructor(
    globalSettings: GlobalSettings,
    secureSettings: SecureSettings,
    @Background background: DelayableExecutor,
) :
    StrikeCounter(
        globalSettings,
        background,
        KEY_STRIKES,
        KEY_SAFE,
        inUse = { secureSettings.getString(YasThemeKeys.COLOR) != null },
    ) {
    companion object {
        const val KEY_STRIKES = "yasvara_color_strikes"
        const val KEY_SAFE = "yasvara_color_safe"
    }
}
