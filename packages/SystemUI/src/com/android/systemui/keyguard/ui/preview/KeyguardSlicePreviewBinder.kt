/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.preview

import android.content.Context
import android.os.Handler
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.keyguard.KeyguardSliceView
import com.android.keyguard.KeyguardSliceViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

/**
 * Adds the lock screen's date line to a keyguard preview when there is no smartspace to show it,
 * as [com.android.systemui.keyguard.ui.view.layout.sections.KeyguardSliceViewSection] does for
 * the real lock screen. The preview gets its own view and controller, so the real one is left
 * untouched.
 */
@SysUISingleton
class KeyguardSlicePreviewBinder
@Inject
constructor(
    @Main private val handler: Handler,
    @Background private val bgHandler: Handler,
    private val activityStarter: ActivityStarter,
    private val configurationController: ConfigurationController,
    private val dumpManager: DumpManager,
    private val displayTracker: DisplayTracker,
    private val keyguardInteractor: KeyguardInteractor,
    private val powerInteractor: PowerInteractor,
    private val smartspaceController: LockscreenSmartspaceController,
) {
    /** Adds the date line below the small clock in [parent], unless smartspace shows it. */
    fun bind(context: Context, parent: ConstraintLayout): DisposableHandle? {
        if (smartspaceController.isEnabled) return null

        // Its text styles resolve against the SystemUI theme, as on the real lock screen.
        val themed = ContextThemeWrapper(context, R.style.Theme_SystemUI)
        val sliceView =
            LayoutInflater.from(themed).inflate(R.layout.keyguard_slice_view, parent, false)
                as KeyguardSliceView
        parent.addView(sliceView)
        KeyguardSliceViewController(
                handler,
                bgHandler,
                sliceView,
                activityStarter,
                configurationController,
                dumpManager,
                displayTracker,
                keyguardInteractor,
                powerInteractor,
            )
            .init()

        ConstraintSet().apply {
            clone(parent)
            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                KeyguardSmartspaceViewModel.getDateWeatherStartMargin(themed),
            )
            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
            )
            constrainHeight(R.id.keyguard_slice_view, ConstraintSet.WRAP_CONTENT)
            connect(
                R.id.keyguard_slice_view,
                ConstraintSet.TOP,
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                ConstraintSet.BOTTOM,
            )
            applyTo(parent)
        }

        return DisposableHandle { parent.removeView(sliceView) }
    }
}
