/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.preview

import android.content.Context
import android.util.Log
import android.view.View
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.connectivity.WifiIcons
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepository
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.yasvara.statusbar.StatusIconSets
import com.android.systemui.yasvara.statusbar.YasBatteryStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONException
import org.json.JSONObject

/** The root of a status bar preview window: what the status bar inside it should show. */
interface StatusBarPreviewHost {
    val preview: StatusBarPreviewScope?
}

/**
 * Finds the status bar preview a view is drawn in, so the status bar's own binders can show the
 * host's simulated states instead of the device's. Null for every status bar on screen.
 */
object StatusBarPreviews {
    fun of(view: View): StatusBarPreviewScope? {
        var parent = view.parent
        while (parent != null) {
            if (parent is StatusBarPreviewHost) return parent.preview
            parent = parent.parent
        }
        return null
    }
}

/**
 * What one status bar preview shows: the device's own state, with the fields the host simulates
 * replaced ([StatusBarSim]). Lives as long as the preview, on [scope].
 */
class StatusBarPreviewScope(
    private val context: Context,
    draft: StatusBarDraft,
    liveBattery: BatteryRepository,
    val scope: CoroutineScope,
) {
    val sim: StateFlow<StatusBarSim> =
        draft.values
            .map { StatusBarSim.parse(it.getString(StatusBarDraft.KEY_SIM)) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, StatusBarSim.NONE)

    /** The battery style the host drafts (`battery_style`), or null for the user's own. */
    private val batteryStyle: StateFlow<Int?> = draftInt(draft, StatusBarDraft.KEY_BATTERY_STYLE)

    /** The percentage setting the host drafts (`battery_percent`), or null for the user's own. */
    private val batteryPercent: StateFlow<Int?> =
        draftInt(draft, StatusBarDraft.KEY_BATTERY_PERCENT)

    private fun draftInt(draft: StatusBarDraft, key: String): StateFlow<Int?> =
        draft.values
            .map { values -> if (values.containsKey(key)) values.getInt(key) else null }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** The icon set the host drafts (`icon_set`), or null for the user's own. */
    val iconSet: StateFlow<String?> =
        draft.values
            .map { values ->
                values.getString(StatusBarDraft.KEY_ICON_SET)?.takeIf { it in StatusIconSets.IDS }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** [live] with the host's simulated signal level on top. */
    fun mobileIcon(live: Flow<SignalIconModel>): Flow<SignalIconModel> =
        combine(live, sim) { icon, sim ->
            sim.mobile?.let {
                SignalIconModel.CellularTypeIconModel.Cellular(
                    level = it.level,
                    numberOfLevels = it.levels + 1,
                    showExclamationMark = false,
                    carrierNetworkChange = false,
                )
            } ?: icon
        }

    /**
     * [live] with the host's simulated network type on top; a simulated signal without a type
     * shows none.
     */
    fun networkType(live: Flow<Icon.Resource?>): Flow<Icon.Resource?> =
        combine(live, sim) { icon, sim ->
            if (sim.mobile == null) icon else sim.mobile.type?.let(::networkTypeIcon)
        }

    fun roaming(live: Flow<Boolean>): Flow<Boolean> =
        combine(live, sim) { roaming, sim -> sim.mobile?.roaming ?: roaming }

    /** [live] with the host's simulated Wi-Fi on top: a level, or off. */
    fun wifiIcon(live: Flow<WifiIcon>): Flow<WifiIcon> =
        combine(live, sim) { icon, sim ->
            val wifi = sim.wifi ?: return@combine icon
            val level = wifi.level ?: return@combine WifiIcon.Hidden
            WifiIcon.Visible(
                WifiIcons.WIFI_FULL_ICONS[level],
                ContentDescription.Loaded(context.getString(WIFI_CONNECTION_STRENGTH[level])),
                level = level,
            )
        }

    private fun networkTypeIcon(type: String): Icon.Resource? {
        val res =
            when (type) {
                "5G" -> TelephonyIcons.ICON_5G
                "5G+" -> TelephonyIcons.ICON_5G_PLUS
                "LTE" -> TelephonyIcons.ICON_LTE
                "LTE+" -> TelephonyIcons.ICON_LTE_PLUS
                "4G" -> TelephonyIcons.ICON_4G
                "4G+" -> TelephonyIcons.ICON_4G_PLUS
                "3G" -> TelephonyIcons.ICON_3G
                "H" -> TelephonyIcons.ICON_H
                "H+" -> TelephonyIcons.ICON_H_PLUS
                "E" -> TelephonyIcons.ICON_E
                "G" -> TelephonyIcons.ICON_G
                else -> return null
            }
        return Icon.Resource(res, ContentDescription.Loaded(type))
    }

    /** The battery view model of the preview's status bar. */
    val battery: BatteryViewModel.Factory =
        object : BatteryViewModel.Factory {
            private val interactor =
                BatteryInteractor(
                    PreviewBatteryRepository(liveBattery, sim, batteryStyle, batteryPercent, scope),
                    scope,
                )

            override fun create(): BatteryViewModel =
                BatteryViewModel.BasedOnUserSetting(interactor, context)
        }
}

/**
 * Device states a status bar preview shows instead of the real ones (`sim`), for
 * states users cannot easily produce: charging, a nearly empty battery, 5G, roaming. A null field
 * keeps the device's own state. Values outside their range are clamped; a malformed document is
 * ignored as a whole.
 */
data class StatusBarSim(
    val battery: Int? = null,
    val charging: Boolean? = null,
    val saver: Boolean? = null,
    val mobile: Mobile? = null,
    val wifi: Wifi? = null,
    val airplane: Boolean? = null,
) {
    /** A cellular connection: [level] of [levels] bars, a [type] such as "5G", or no service. */
    data class Mobile(val level: Int, val levels: Int, val type: String?, val roaming: Boolean)

    /** Wi-Fi at [level] of 4, or off when [level] is null. */
    data class Wifi(val level: Int?, val activity: String?)

    companion object {
        val NONE = StatusBarSim()
        private const val TAG = "StatusBarPreview"

        fun parse(json: String?): StatusBarSim {
            if (json.isNullOrEmpty()) return NONE
            return try {
                val o = JSONObject(json)
                StatusBarSim(
                    battery = o.optIntOrNull("battery")?.coerceIn(0, 100),
                    charging = o.optBooleanOrNull("charging"),
                    saver = o.optBooleanOrNull("saver"),
                    mobile =
                        o.optJSONObject("mobile")?.let { m ->
                            val levels = m.optInt("levels", 4).coerceIn(4, 5)
                            Mobile(
                                level = m.optInt("level", levels).coerceIn(0, levels),
                                levels = levels,
                                type = m.optString("type").takeIf { it.isNotEmpty() },
                                roaming = m.optBoolean("roaming", false),
                            )
                        },
                    wifi =
                        o.optJSONObject("wifi")?.let { w ->
                            Wifi(
                                level = w.optIntOrNull("level")?.coerceIn(0, 4),
                                activity = w.optString("activity").takeIf { it.isNotEmpty() },
                            )
                        },
                    airplane = o.optBooleanOrNull("airplane"),
                )
            } catch (e: JSONException) {
                Log.w(TAG, "Ignoring a malformed simulated state", e)
                NONE
            }
        }

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null

        private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
            if (has(key) && !isNull(key)) optBoolean(key) else null
    }
}

/**
 * The device's battery, with what the preview's host simulates on top. A simulated level or
 * charger clears the states they would contradict (unknown battery, a paused or incompatible
 * charger); a drafted style or percentage setting replaces the saved one.
 */
private class PreviewBatteryRepository(
    private val live: BatteryRepository,
    private val sim: StateFlow<StatusBarSim>,
    style: StateFlow<Int?>,
    percent: StateFlow<Int?>,
    scope: CoroutineScope,
) : BatteryRepository {

    private fun <T> simOr(live: Flow<T>, simulated: (StatusBarSim) -> T?): Flow<T> =
        combine(live, sim) { value, sim -> simulated(sim) ?: value }.distinctUntilChanged()

    override val isPluggedIn = simOr(live.isPluggedIn) { it.charging }
    override val isPowerSaveEnabled = simOr(live.isPowerSaveEnabled) { it.saver }
    override val isExtremePowerSaveEnabled =
        simOr(live.isExtremePowerSaveEnabled) { if (it.saver != null) false else null }
    override val isBatteryDefenderEnabled =
        simOr(live.isBatteryDefenderEnabled) { if (it.charging != null) false else null }
    override val isIncompatibleCharging =
        simOr(live.isIncompatibleCharging) { if (it.charging != null) false else null }
    override val level: Flow<Int?> =
        combine(live.level, sim) { value, sim -> sim.battery ?: value }.distinctUntilChanged()
    override val isStateUnknown =
        simOr(live.isStateUnknown) { if (it.battery != null) false else null }
    override val batteryIconStyle: StateFlow<Int> =
        combine(live.batteryIconStyle, style) { saved, drafted -> drafted ?: saved }
            .stateIn(scope, SharingStarted.Eagerly, style.value ?: live.batteryIconStyle.value)

    /**
     * The saved mode is already fitted to the saved style; a drafted style is fitted the same
     * way (Yasvara styles that draw the percentage hide the separate one).
     */
    override val showBatteryPercentMode: StateFlow<Int> =
        combine(live.showBatteryPercentMode, percent, style) { saved, draftedPercent, draftedStyle ->
                if (draftedStyle == null && draftedPercent == null) saved
                else {
                    YasBatteryStyles.percentMode(
                        draftedStyle ?: live.batteryIconStyle.value,
                        draftedPercent ?: saved,
                    )
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, live.showBatteryPercentMode.value)
    override val batteryTimeRemainingEstimate = live.batteryTimeRemainingEstimate
}
