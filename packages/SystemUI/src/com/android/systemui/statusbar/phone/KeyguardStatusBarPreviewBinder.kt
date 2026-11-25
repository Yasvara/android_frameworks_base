/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnAttach
import com.android.app.displaylib.PerDisplayRepository
import com.android.keyguard.CarrierTextController
import com.android.keyguard.CarrierTextManager
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent
import com.android.systemui.res.R
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.statusbar.phone.domain.interactor.DarkIconInteractor.Companion.toIsAreaDark
import com.android.systemui.statusbar.phone.fragment.getStatusBarIconBlocklist
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.statusbar.pipeline.battery.ui.binder.UnifiedBatteryViewBinder
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.view.SystemStatusIconsLayoutHelper
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsInCompose
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle

/**
 * Builds the lock screen status bar for a keyguard preview from the same parts the real one uses:
 * carrier text, the live status icons and the battery, all following the user's settings.
 *
 * Unlike [KeyguardStatusBarViewController] it follows no keyguard state, since a preview always
 * shows the lock screen at rest.
 */
@SysUISingleton
class KeyguardStatusBarPreviewBinder
@Inject
constructor(
    private val carrierTextManagerBuilder: CarrierTextManager.Builder,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val statusBarIconController: StatusBarIconController,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryViewModelFactory: BatteryViewModel.BasedOnUserSetting.Factory,
    private val perDisplaySubcomponentRepo: PerDisplayRepository<SystemUIDisplaySubcomponent>,
    private val secureSettings: SecureSettings,
) {
    /** Adds the status bar to the top of [parent]; the returned handle removes it again. */
    fun bind(context: Context, parent: ViewGroup): DisposableHandle {
        val view =
            LayoutInflater.from(context).inflate(R.layout.keyguard_status_bar, parent, false)
                as KeyguardStatusBarView
        // A preview has no user to switch to.
        view.findViewById<View>(R.id.user_switcher_container).visibility = View.GONE
        view.findViewById<View>(R.id.multi_user_avatar).visibility = View.GONE
        parent.addView(view)

        CarrierTextController(
                view.findViewById(R.id.keyguard_carrier_text),
                carrierTextManagerBuilder,
                keyguardUpdateMonitor,
            )
            .init()

        val icons =
            tintedIconManagerFactory.create(
                view.findViewById(R.id.statusIcons),
                StatusBarLocation.KEYGUARD,
            )
        icons.setBlockList(getStatusBarIconBlocklist(context.resources, secureSettings))
        statusBarIconController.addIconGroup(icons)

        if (NewStatusBarIcons.isEnabled && !SystemStatusIconsInCompose.isEnabled) {
            val battery = ComposeView(context)
            UnifiedBatteryViewBinder.bind(
                battery,
                batteryViewModelFactory,
                view.darkChangeFlow().toIsAreaDark(),
            )
            // Compose cannot measure before it has a window, and the preview is measured first.
            view.doOnAttach {
                view.findViewById<ViewGroup>(R.id.system_icons).addView(battery, -1)
            }
            SystemStatusIconsLayoutHelper.configurePaddingForNewStatusBarIcons(
                view.findViewById(R.id.statusIcons)
            )
        }

        view.onThemeChanged(icons)
        view.setDisplayCutout(
            context.display.cutout,
            perDisplaySubcomponentRepo.getOrDefault(context.displayId)
                .statusBarContentInsetsProvider,
        )

        return DisposableHandle {
            statusBarIconController.removeIconGroup(icons)
            parent.removeView(view)
        }
    }
}
