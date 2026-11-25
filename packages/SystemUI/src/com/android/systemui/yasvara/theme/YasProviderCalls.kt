/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.theme

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject

/**
 * The Yasvara methods of `content://com.android.systemui.customization`, all named `yas_*`. Only callers that may
 * customize SystemUI get an answer; everyone else gets null, as for an unknown method.
 */
@SysUISingleton
class YasProviderCalls
@Inject
constructor(
    @Application private val context: Context,
    private val transition: YasThemeTransition,
) {
    fun call(method: String, extras: Bundle?): Bundle? {
        val allowed =
            context.checkPermission(
                PERMISSION,
                Binder.getCallingPid(),
                Binder.getCallingUid(),
            ) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return null
        return when (method) {
            YasTransitionContract.METHOD_BEGIN -> transition.begin(extras ?: Bundle.EMPTY)
            YasTransitionContract.METHOD_READY -> {
                transition.ready(extras?.getInt(YasTransitionContract.KEY_TOKEN) ?: 0)
                Bundle.EMPTY
            }
            YasTransitionContract.METHOD_END -> transition.end()
            else -> null
        }
    }

    private companion object {
        /** Declared by SystemUI's manifest; the permission the customization provider asks. */
        const val PERMISSION = "android.permission.CUSTOMIZE_SYSTEM_UI"
    }
}
