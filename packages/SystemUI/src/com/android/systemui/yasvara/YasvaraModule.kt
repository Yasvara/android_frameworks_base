/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara

import com.android.systemui.CoreStartable
import com.android.systemui.yasvara.theme.YasThemeModule
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module(includes = [YasThemeModule::class])
interface YasvaraModule {
    @Binds
    @IntoMap
    @ClassKey(LockSafeMode::class)
    fun bindLockSafeMode(safeMode: LockSafeMode): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(LockLayout::class)
    fun bindLockLayout(layout: LockLayout): CoreStartable
}
