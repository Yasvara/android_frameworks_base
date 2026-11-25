/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks

import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.customization.clocks.view.DefaultClockFaceLayout
import com.android.systemui.plugins.keyguard.ui.clocks.ClockPreviewConfig
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds

/**
 * Places a Yasvara face. The large face keeps the stock placement; the small face, shown with
 * notifications, takes the size and alignment its design or the user asks for, instead of
 * always sitting small in the top start corner. SystemUI reads the small face's height from
 * these constraints to place notifications below it.
 */
class YasvaraClockFaceLayout(private val face: ClockFaceView) : DefaultClockFaceLayout(face) {
    private val isSmall get() = face.id == ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL

    override fun applyConstraints(constraints: ConstraintSet): ConstraintSet {
        super.applyConstraints(constraints)
        if (isSmall) placeSmall(constraints)
        return constraints
    }

    override fun applyPreviewConstraints(
        clockPreviewConfig: ClockPreviewConfig,
        constraints: ConstraintSet,
    ): ConstraintSet {
        super.applyPreviewConstraints(clockPreviewConfig, constraints)
        if (isSmall) placeSmall(constraints)
        return constraints
    }

    private fun placeSmall(cs: ConstraintSet) {
        val res = face.resources
        val id = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
        val style = face.style
        val scale = style.smallScale ?: face.design.smallScale
        val align = style.smallAlign ?: face.design.smallAlign
        val margin = res.getDimensionPixelSize(clocksR.dimen.clock_padding_start) +
            res.getDimensionPixelSize(clocksR.dimen.status_view_margin_horizontal)
        cs.constrainHeight(id, (res.getDimensionPixelSize(clocksR.dimen.small_clock_height) * scale).toInt())
        cs.connect(id, START, PARENT_ID, START, margin)
        cs.connect(id, END, PARENT_ID, END, margin)
        cs.setHorizontalBias(id, when (align) {
            Align.START -> 0f
            Align.CENTER -> 0.5f
            Align.END -> 1f
        })
    }
}
