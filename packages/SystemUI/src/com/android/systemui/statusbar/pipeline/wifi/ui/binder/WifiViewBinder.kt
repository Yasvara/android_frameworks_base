/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.wifi.ui.binder

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewVisibilityHelper
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel
import com.android.systemui.yasvara.preview.StatusBarPreviews
import com.android.systemui.yasvara.statusbar.StatusIconSets
import com.android.systemui.yasvara.statusbar.YasWifiIcon
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Binds a wifi icon in the status bar to its view-model.
 *
 * To use this properly, users should maintain a one-to-one relationship between the [View] and the
 * view-binding, binding each view only once. It is okay and expected for the same instance of the
 * view-model to be reused for multiple view/view-binder bindings.
 */
@OptIn(InternalCoroutinesApi::class)
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
object WifiViewBinder {

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @JvmStatic
    fun bind(view: ViewGroup, viewModel: LocationBasedWifiViewModel): ModernStatusBarViewBinding {
        val groupView = view.requireViewById<ViewGroup>(R.id.wifi_group)
        val iconView = view.requireViewById<ImageView>(R.id.wifi_signal)
        val dotView = view.requireViewById<StatusBarIconView>(R.id.status_bar_dot)
        val activityInView = view.requireViewById<ImageView>(R.id.wifi_in)
        val activityOutView = view.requireViewById<ImageView>(R.id.wifi_out)
        val activityContainerView = view.requireViewById<View>(R.id.inout_container)
        // Yasvara: the chosen icon set draws the level.
        val yasWifi = YasWifiIcon(iconView)
        var yasShown: Boolean? = null

        view.isVisible = true
        iconView.isVisible = true

        // TODO(b/238425913): We should log this visibility state.
        @StatusBarIconView.VisibleState
        val visibilityState: MutableStateFlow<Int> = MutableStateFlow(STATE_HIDDEN)

        val iconTint: MutableStateFlow<Int> = MutableStateFlow(viewModel.defaultColor)
        val decorTint: MutableStateFlow<Int> = MutableStateFlow(viewModel.defaultColor)

        var isCollecting: Boolean = false

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                isCollecting = true

                launch {
                    visibilityState.collect { visibilityState ->
                        // for b/296864006, we can not hide all the child views if visibilityState
                        // is STATE_HIDDEN. Because hiding all child views would cause the
                        // getWidth() of this view return 0, and that would cause the translation
                        // calculation fails in StatusIconContainer. Therefore, like class
                        // MobileIconBinder, instead of set the child views visibility to View.GONE,
                        // we set their visibility to View.INVISIBLE to make them invisible but
                        // keep the width.
                        ModernStatusBarViewVisibilityHelper.setVisibilityState(
                            visibilityState,
                            groupView,
                            dotView,
                        )
                    }
                }

                // Yasvara: the icon set, and in a preview the Wi-Fi its host simulates.
                launch { StatusIconSets.forView(view).collect(yasWifi::setIconSet) }

                launch {
                    (StatusBarPreviews.of(view)?.wifiIcon(viewModel.wifiIcon) ?: viewModel.wifiIcon)
                        .collect { wifiIcon ->
                            view.isVisible = wifiIcon is WifiIcon.Visible
                            yasShown = wifiIcon is WifiIcon.Visible
                            if (wifiIcon is WifiIcon.Visible) {
                                yasWifi.show(wifiIcon)
                            }
                        }
                }

                launch {
                    iconTint.collect { tint ->
                        val tintList = ColorStateList.valueOf(tint)
                        iconView.imageTintList = tintList
                        activityInView.imageTintList = tintList
                        activityOutView.imageTintList = tintList
                        dotView.setDecorColor(tint)
                    }
                }

                launch { decorTint.collect { tint -> dotView.setDecorColor(tint) } }

                launch {
                    viewModel.isActivityInViewVisible.distinctUntilChanged().collect { visible ->
                        activityInView.isVisible = visible
                    }
                }

                launch {
                    viewModel.isActivityOutViewVisible.distinctUntilChanged().collect { visible ->
                        activityOutView.isVisible = visible
                    }
                }

                launch {
                    viewModel.isActivityContainerVisible.distinctUntilChanged().collect { visible ->
                        activityContainerView.isVisible = visible
                    }
                }

                try {
                    awaitCancellation()
                } finally {
                    isCollecting = false
                }
            }
        }

        return object : ModernStatusBarViewBinding {
            override fun getShouldIconBeVisible(): Boolean {
                return yasShown ?: (viewModel.wifiIcon.value is WifiIcon.Visible)
            }

            override fun onVisibilityStateChanged(@StatusBarIconView.VisibleState state: Int) {
                visibilityState.value = state
            }

            override fun onIconTintChanged(newTint: Int, contrastTint: Int /* unused */) {
                iconTint.value = newTint
            }

            override fun onDecorTintChanged(newTint: Int) {
                decorTint.value = newTint
            }

            override fun isCollecting(): Boolean {
                return isCollecting
            }
        }
    }
}
