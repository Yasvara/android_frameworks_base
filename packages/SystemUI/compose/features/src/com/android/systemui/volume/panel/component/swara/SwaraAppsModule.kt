/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.volume.panel.component.swara

import com.android.systemui.volume.panel.component.shared.model.VolumePanelComponents
import com.android.systemui.volume.panel.domain.AlwaysAvailableCriteria
import com.android.systemui.volume.panel.domain.ComponentAvailabilityCriteria
import com.android.systemui.volume.panel.shared.model.VolumePanelUiComponent
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/** Volume Panel "Apps". */
@Module
interface SwaraAppsModule {

    @Binds
    @IntoMap
    @StringKey(VolumePanelComponents.SWARA_APPS)
    fun bindVolumePanelUiComponent(component: SwaraAppsComponent): VolumePanelUiComponent

    @Binds
    @IntoMap
    @StringKey(VolumePanelComponents.SWARA_APPS)
    fun bindComponentAvailabilityCriteria(
        criteria: AlwaysAvailableCriteria
    ): ComponentAvailabilityCriteria
}
