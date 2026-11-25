/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.theme

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.yasvara.statusbar.StatusIconSets
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/**
 * The theme components the current user chose in `yasvara_theme`. Every field
 * falls back to its default on its own: a missing, unknown or malformed value never takes the
 * others down, and while theme safe mode is on every field is at its default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class YasThemeRepository
@Inject
constructor(
    private val secureSettings: SecureSettings,
    selectedUser: SelectedUserInteractor,
    safeMode: ThemeSafeMode,
    @Application scope: CoroutineScope,
    @Background background: CoroutineDispatcher,
) {
    private val theme: StateFlow<YasTheme> =
        combine(
                selectedUser.selectedUser.flatMapLatest { userId ->
                    secureSettings
                        .observerFlow(userId, YasThemeKeys.THEME)
                        .onStart { emit(Unit) }
                        .map { YasTheme.parse(secureSettings.getStringForUser(YasThemeKeys.THEME, userId)) }
                },
                safeMode.active,
            ) { theme, safe ->
                if (safe) YasTheme.DEFAULT else theme
            }
            .distinctUntilChanged()
            .flowOn(background)
            .stateIn(scope, SharingStarted.Eagerly, YasTheme.DEFAULT)

    /** The status bar icon set id ([StatusIconSets]). */
    val statusIcons: StateFlow<String> =
        theme.map { it.statusIcons }.stateIn(scope, SharingStarted.Eagerly, StatusIconSets.AOSP)
}

/** The fields of `yasvara_theme` SystemUI applies so far. */
data class YasTheme(val statusIcons: String) {
    companion object {
        val DEFAULT = YasTheme(statusIcons = StatusIconSets.AOSP)

        fun parse(raw: String?): YasTheme {
            if (raw.isNullOrBlank()) return DEFAULT
            val json =
                try {
                    JSONObject(raw)
                } catch (e: JSONException) {
                    return DEFAULT
                }
            return YasTheme(
                statusIcons =
                    json.optString("statusIcons").takeIf { it in StatusIconSets.IDS }
                        ?: DEFAULT.statusIcons
            )
        }
    }
}

/**
 * Hands the icon set to the status bar binders, which are static and outside Dagger's reach
 * ([StatusIconSets.current]).
 */
@SysUISingleton
class StatusIconSetsStarter
@Inject
constructor(
    private val repository: YasThemeRepository,
    @Application private val scope: CoroutineScope,
) : CoreStartable {
    override fun start() {
        scope.launch { repository.statusIcons.collect { StatusIconSets.current.value = it } }
    }
}
