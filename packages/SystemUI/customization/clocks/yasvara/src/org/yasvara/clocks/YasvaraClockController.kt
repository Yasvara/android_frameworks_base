/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import android.content.Context
import android.icu.util.TimeZone
import com.android.systemui.customization.clocks.TimeKeeper
import com.android.systemui.plugins.keyguard.VRect
import com.android.systemui.plugins.keyguard.data.model.AlarmData
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.ZenData
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAnimations
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEventListeners
import com.android.systemui.plugins.keyguard.ui.clocks.ClockEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockFaceEvents
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPositionAnimationArgs
import com.android.systemui.plugins.keyguard.ui.clocks.ClockTickRate
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import java.io.PrintWriter
import java.util.Locale

/** A Yasvara clock: one [ClockDesign] drawn as a large and a small face. */
class YasvaraClockController(
    private val context: Context,
    private val design: ClockDesign,
    style: ClockStyle,
    seedColor: Int?,
    private val timeKeeper: TimeKeeper,
) : ClockController {
    private var is24Hour = TimeFormatKind.getFromContext(context) == TimeFormatKind.FULL_DAY
    private var locale: Locale = context.resources.configuration.locales[0]

    override val smallClock = Face(isLarge = false, style, seedColor)
    override val largeClock = Face(isLarge = true, style, seedColor)

    override val config = ClockConfig(
        design.id,
        context.getString(design.name),
        context.getString(design.description),
    )

    override val eventListeners = ClockEventListeners()

    override val events = object : ClockEvents {
        override fun onTimeZoneChanged(timeZone: TimeZone) {
            timeKeeper.timeZone = timeZone
            tick()
        }

        override fun onTimeFormatChanged(formatKind: TimeFormatKind) {
            is24Hour = formatKind == TimeFormatKind.FULL_DAY
            tick()
        }

        override fun onLocaleChanged(locale: Locale) {
            this@YasvaraClockController.locale = locale
            tick()
        }

        override fun onWeatherDataChanged(data: WeatherData) {}

        override fun onAlarmDataChanged(data: AlarmData) {}

        override fun onZenDataChanged(data: ZenData) {}
    }

    override fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float) {
        for (face in listOf(smallClock, largeClock)) {
            face.events.onThemeChanged(face.theme.copy(isDarkTheme = isDarkTheme))
            face.animations.doze(dozeFraction)
        }
        tick()
    }

    override fun dump(pw: PrintWriter) {
        pw.println("YasvaraClockController: ${design.id} style=${largeClock.view.style}")
    }

    private fun tick() {
        timeKeeper.updateTime()
        val now = ClockTime(timeKeeper.time, timeKeeper.timeZone, is24Hour, locale)
        smallClock.view.setTime(now)
        largeClock.view.setTime(now)
    }

    inner class Face(isLarge: Boolean, style: ClockStyle, seedColor: Int?) : ClockFaceController {
        override val view = ClockFaceView(context, design, isLarge, style).apply {
            // SystemUI finds and places the faces by these ids.
            id = if (isLarge) ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE
            else ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
        }

        override val layout = YasvaraClockFaceLayout(view)

        override val config = ClockFaceConfig(
            tickRate = if (style.seconds && design.supportsSeconds) ClockTickRate.PER_SECOND
            else ClockTickRate.PER_MINUTE,
        )

        override var theme = ThemeConfig(isDarkTheme = true, seedColor = seedColor)

        override val events = object : ClockFaceEvents {
            override fun onTimeTick() = tick()

            override fun onThemeChanged(theme: ThemeConfig) {
                this@Face.theme = theme
                view.theme = theme
            }

            override fun onFontSettingChanged(fontSizePx: Float) {
                view.fontSizePx = fontSizePx
            }

            @Deprecated("No longer necessary, pending removal")
            override fun onTargetRegionChanged(targetRegion: VRect) {}

            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) {}
        }

        override val animations = object : ClockAnimations {
            override fun enter() {}

            override fun doze(fraction: Float) {
                view.doze = fraction
            }

            override fun fold(fraction: Float) {}

            override fun charge() = view.pulse()

            override fun onPositionAnimated(anim: ClockPositionAnimationArgs) {}

            override fun onPickerCarouselSwiping(swipingFraction: Float) {}

            override fun onFidgetTap(x: Float, y: Float) = view.pulse()

            // A picker changing the style live, without recreating the clock.
            override fun onFontAxesChanged(style: ClockAxisStyle) {
                view.style = ClockStyle.from(style)
            }
        }
    }
}
