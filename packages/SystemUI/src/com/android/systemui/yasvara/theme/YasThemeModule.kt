/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.theme

import com.android.systemui.CoreStartable
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import org.yasvara.monet.YasSchemeHook

/** The colour and theme features. */
@Module
interface YasThemeModule {
    @Binds
    @IntoMap
    @ClassKey(ThemeSafeMode::class)
    fun bindThemeSafeMode(safeMode: ThemeSafeMode): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(ColorSafeMode::class)
    fun bindColorSafeMode(safeMode: ColorSafeMode): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(StatusIconSetsStarter::class)
    fun bindStatusIconSetsStarter(starter: StatusIconSetsStarter): CoreStartable

    @Binds fun bindSchemeHook(hook: YasColorHook): YasSchemeHook
}
