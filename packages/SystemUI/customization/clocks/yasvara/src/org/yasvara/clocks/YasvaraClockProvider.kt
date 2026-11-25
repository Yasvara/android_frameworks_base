/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import android.content.Context
import com.android.systemui.customization.clocks.TimeKeeper
import com.android.systemui.customization.clocks.TimeKeeperImpl
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMessageBuffers
import com.android.systemui.plugins.keyguard.ui.clocks.ClockMetadata
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPickerConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockProvider
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.android.systemui.plugins.keyguard.ui.clocks.ThemeConfig
import org.yasvara.clocks.designs.AccentDesign
import org.yasvara.clocks.designs.AnalogDesign
import org.yasvara.clocks.designs.BubbleDesign
import org.yasvara.clocks.designs.CodeDesign
import org.yasvara.clocks.designs.DotsDesign
import org.yasvara.clocks.designs.FlexDesign
import org.yasvara.clocks.designs.LabelDesign
import org.yasvara.clocks.designs.OrbitDesign
import org.yasvara.clocks.designs.RingDesign
import org.yasvara.clocks.designs.ShapeDesign
import org.yasvara.clocks.designs.StackDesign
import org.yasvara.clocks.designs.ThinDesign
import org.yasvara.clocks.designs.WaveDesign
import org.yasvara.clocks.designs.WordsDesign

/** Provides Yasvara's clock faces. */
class YasvaraClockProvider
@JvmOverloads
constructor(
    private val context: Context,
    private val timeKeeperFactory: () -> TimeKeeper = { TimeKeeperImpl() },
) : ClockProvider {

    override fun initialize(buffers: ClockMessageBuffers?) {}

    override fun getClocks() = DESIGNS.map { ClockMetadata(it.id) }

    override fun createClock(ctx: Context, settings: ClockSettings): ClockController =
        YasvaraClockController(
            ctx,
            design(settings),
            ClockStyle.from(settings.axes),
            settings.seedColor,
            timeKeeperFactory(),
        )

    override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig {
        val design = design(settings)
        val style = ClockStyle.from(settings.axes)
        return ClockPickerConfig(
            design.id,
            context.getString(design.name),
            context.getString(design.description),
            ClockThumbnail(context, design, style, ThemeConfig(true, settings.seedColor)),
            isReactiveToTone = true,
        )
    }

    private fun design(settings: ClockSettings) =
        DESIGNS.firstOrNull { it.id == settings.clockId }
            ?: throw IllegalArgumentException("${settings.clockId} is not a Yasvara clock")

    companion object {
        /** Every design, in the order pickers list them. */
        val DESIGNS: List<ClockDesign> = listOf(
            FlexDesign,
            ShapeDesign,
            StackDesign,
            WaveDesign,
            WordsDesign,
            DotsDesign,
            RingDesign,
            AccentDesign,
            ThinDesign,
            AnalogDesign,
            LabelDesign,
            BubbleDesign,
            OrbitDesign,
            CodeDesign,
        )
    }
}
