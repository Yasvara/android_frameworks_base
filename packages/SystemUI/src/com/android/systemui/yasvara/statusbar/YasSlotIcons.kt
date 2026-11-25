/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.statusbar

import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import androidx.lifecycle.lifecycleScope
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import kotlinx.coroutines.launch

/**
 * The system icons of the status bar (alarm, Do Not Disturb, ringer, Bluetooth and the like) in
 * the icon sets that redraw them: Outline uses Material Symbols Outlined, Thin
 * uses Material Symbols Rounded at weight 200. Every other set, and any icon without a
 * counterpart, keeps the stock drawable.
 */
object YasSlotIcons {
    /** Stock SystemUI icon to its Outline and Thin counterparts. */
    private val systemUi: Map<Int, Pair<Int, Int>> = buildMap {
        put(R.drawable.stat_sys_alarm, R.drawable.yas_sb_outline_alarm to R.drawable.yas_sb_thin_alarm)
        put(R.drawable.stat_sys_dnd, R.drawable.yas_sb_outline_do_not_disturb_on to R.drawable.yas_sb_thin_do_not_disturb_on)
        put(R.drawable.stat_sys_ringer_vibrate, R.drawable.yas_sb_outline_vibration to R.drawable.yas_sb_thin_vibration)
        put(R.drawable.stat_sys_ringer_silent, R.drawable.yas_sb_outline_notifications_off to R.drawable.yas_sb_thin_notifications_off)
        put(R.drawable.stat_sys_hotspot, R.drawable.yas_sb_outline_wifi_tethering to R.drawable.yas_sb_thin_wifi_tethering)
        put(R.drawable.stat_sys_data_saver, R.drawable.yas_sb_outline_data_saver_on to R.drawable.yas_sb_thin_data_saver_on)
        put(R.drawable.stat_sys_vpn_ic, R.drawable.yas_sb_outline_vpn_key to R.drawable.yas_sb_thin_vpn_key)
        put(R.drawable.stat_sys_airplane_mode, R.drawable.yas_sb_outline_airplanemode_active to R.drawable.yas_sb_thin_airplanemode_active)
        val bluetooth = R.drawable.yas_sb_outline_bluetooth_connected to R.drawable.yas_sb_thin_bluetooth_connected
        // The sets draw a connected device without its battery level.
        for (res in listOf(
            R.drawable.stat_sys_data_bluetooth_connected,
            R.drawable.stat_sys_data_bluetooth_connected_battery_0,
            R.drawable.stat_sys_data_bluetooth_connected_battery_1,
            R.drawable.stat_sys_data_bluetooth_connected_battery_2,
            R.drawable.stat_sys_data_bluetooth_connected_battery_3,
            R.drawable.stat_sys_data_bluetooth_connected_battery_4,
            R.drawable.stat_sys_data_bluetooth_connected_battery_5,
            R.drawable.stat_sys_data_bluetooth_connected_battery_6,
            R.drawable.stat_sys_data_bluetooth_connected_battery_7,
            R.drawable.stat_sys_data_bluetooth_connected_battery_8,
            R.drawable.stat_sys_data_bluetooth_connected_battery_9,
        )) {
            put(res, bluetooth)
        }
    }

    /** Framework icons the status bar shows: location in use. */
    private val framework: Map<Int, Pair<Int, Int>> =
        mapOf(
            com.android.internal.R.drawable.perm_group_location to
                (R.drawable.yas_sb_outline_location_on to R.drawable.yas_sb_thin_location_on)
        )

    /** [icon] as [view]'s icon set draws it, or null to load it as usual. */
    @JvmStatic
    fun load(view: StatusBarIconView, icon: Icon?): Drawable? {
        if (icon == null || icon.type != Icon.TYPE_RESOURCE) return null
        val style = StatusIconSets.style(StatusIconSets.currentFor(view)) ?: return null
        val pair =
            when (icon.resPackage) {
                view.context.packageName -> systemUi[icon.resId]
                "android" -> framework[icon.resId]
                else -> null
            } ?: return null
        val res =
            when (style) {
                IconStyle.OUTLINE -> pair.first
                IconStyle.THIN -> pair.second
                else -> return null
            }
        return view.context.getDrawable(res)
    }

    /** Reloads [view]'s icon whenever its icon set changes, while it is attached. */
    @JvmStatic
    fun follow(view: StatusBarIconView) {
        view.repeatWhenAttached {
            lifecycleScope.launch {
                StatusIconSets.forView(view).collect {
                    view.updateDrawable()
                    // The sets' icons have their own intrinsic size.
                    view.maybeUpdateIconScaleDimens()
                    view.requestLayout()
                }
            }
        }
    }
}
