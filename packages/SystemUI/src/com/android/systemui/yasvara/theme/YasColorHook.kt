/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.theme

import android.app.WallpaperColors
import android.content.Context
import android.content.om.FabricatedOverlay
import android.os.Trace
import android.util.Log
import android.util.TypedValue
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import lineageos.providers.LineageSettings
import org.yasvara.monet.YasColorSpec
import org.yasvara.monet.YasResolvedScheme
import org.yasvara.monet.YasSchemeHook
import org.yasvara.monet.YasSeedResolver
import org.yasvara.monet.YasvaraMonet

/**
 * Resolves the user's `yasvara_color` spec for ThemeOverlayController. Returns
 * null, so the stock code runs, when there is no spec, it cannot be read, or colour safe mode is
 * on; a spec that throws counts a strike instead of crashing SystemUI.
 */
@SysUISingleton
class YasColorHook
@Inject
constructor(
    @Application private val context: Context,
    private val secureSettings: SecureSettings,
    private val safeMode: ColorSafeMode,
    private val state: YasThemeState,
    private val themeSafeMode: ThemeSafeMode,
) : YasSchemeHook {

    /**
     * The icon mask for [userId], applied in the same overlay transaction as the
     * colours; null for the stock mask, also while theme safe mode is on.
     */
    fun iconMask(userId: Int): String? =
        if (themeSafeMode.isActive) null else YasIconMask.read(secureSettings, userId)

    /** The spec text the last resolve used, per user, to skip evaluating the same spec twice. */
    private val lastSpec = mutableMapOf<Int, String?>()

    override fun resolve(
        userId: Int,
        wallpaper: WallpaperColors?,
        systemContrast: Double,
    ): YasResolvedScheme? {
        val raw = secureSettings.getStringForUser(YasThemeKeys.COLOR, userId)
        synchronized(lastSpec) { lastSpec[userId] = raw }
        if (raw == null || safeMode.isActive) return applied(null)
        val spec = YasColorSpec.parse(raw)
        if (spec == null) {
            Log.w(TAG, "Unreadable ${YasThemeKeys.COLOR}; using stock colours")
            return applied(null)
        }
        val pureBlack =
            LineageSettings.Secure.getIntForUser(
                context.contentResolver,
                LineageSettings.Secure.BERRY_BLACK_THEME,
                0,
                userId,
            ) == 1
        return try {
            Trace.beginSection("YasResolve")
            applied(
                YasvaraMonet.resolve(
                    spec,
                    YasSeedResolver.seed(spec, wallpaper),
                    systemContrast,
                    pureBlack,
                )
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "Colour spec failed; using stock colours", e)
            safeMode.recordFailure("resolve: ${e.javaClass.simpleName}")
            applied(null)
        } finally {
            Trace.endSection()
        }
    }

    /** Whether the stored spec differs from the one the last resolve used. */
    fun changedSinceResolve(userId: Int): Boolean =
        secureSettings.getStringForUser(YasThemeKeys.COLOR, userId) !=
            synchronized(lastSpec) { lastSpec[userId] }

    private fun applied(scheme: YasResolvedScheme?): YasResolvedScheme? {
        state.fingerprint = scheme?.fingerprint
        state.summary = scheme?.let { "${it.spec.style} seed=${YasColorSpec.formatColor(it.seed)} " +
            "contrast=${it.contrast} pureBlack=${it.pureBlack}" }
        return scheme
    }

    companion object {
        private const val TAG = "YasColorHook"

        /** Fills a fabricated overlay with resolved values, as stock fills it with its own. */
        @JvmStatic
        fun fill(overlay: FabricatedOverlay, entries: List<YasResolvedScheme.Entry>) {
            for (entry in entries) {
                overlay.setResourceValue(
                    entry.resourceName,
                    TypedValue.TYPE_INT_COLOR_ARGB8,
                    entry.argb,
                    null,
                )
            }
        }

        /** Calls [hook], treating any failure as "no spec". Never throws. */
        @JvmStatic
        fun resolveSafely(
            hook: YasSchemeHook?,
            userId: Int,
            wallpaper: WallpaperColors?,
            contrast: Double,
        ): YasResolvedScheme? =
            try {
                hook?.resolve(userId, wallpaper, contrast)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Colour hook failed; using stock colours", e)
                null
            }
    }
}
