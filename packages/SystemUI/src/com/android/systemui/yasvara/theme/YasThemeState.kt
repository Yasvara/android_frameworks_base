/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.theme

import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import java.io.PrintWriter
import javax.inject.Inject

/**
 * What the Yasvara colour and theme features have applied, for `dumpsys` and for Laras to check
 * against what it asked for.
 */
@SysUISingleton
class YasThemeState
@Inject
constructor(
    private val themeSafeMode: ThemeSafeMode,
    private val colorSafeMode: ColorSafeMode,
    dumpManager: DumpManager,
) : Dumpable {

    /** The fingerprint of the applied colour scheme, or null while stock colours are in use. */
    @Volatile var fingerprint: String? = null

    /** What the applied colour scheme was built from, for dumpsys. */
    @Volatile var summary: String? = null

    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("YasThemeState:")
        pw.println("  colorFingerprint=$fingerprint")
        pw.println("  color=${summary ?: "stock"}")
        themeSafeMode.dump(pw)
        colorSafeMode.dump(pw)
    }

    private companion object {
        const val TAG = "YasThemeState"
    }
}
