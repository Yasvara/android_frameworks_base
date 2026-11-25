/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.theme

import android.content.om.FabricatedOverlay
import android.util.Log
import android.util.PathParser
import android.util.TypedValue
import com.android.systemui.util.settings.SecureSettings
import org.json.JSONException
import org.json.JSONObject

/**
 * The system icon mask: the path of the icon shape picked in Laras, from
 * `yasvara_theme.iconMask`, applied as `android:string/config_icon_mask` so every adaptive icon
 * outside the launcher (Settings, the share sheet, notifications) takes the launcher's shape.
 * Laras writes the path the launcher reports for the shape, so the two can never differ.
 */
object YasIconMask {
    private const val TAG = "YasIconMask"
    private const val RESOURCE = "android:string/config_icon_mask"

    /** Paths beyond this are refused: a real shape is a few kilobytes at most. */
    private const val MAX_LENGTH = 8192

    /** The mask the user picked for [userId], or null for the stock one (also when unusable). */
    @JvmStatic
    fun read(settings: SecureSettings, userId: Int): String? {
        val raw = settings.getStringForUser(YasThemeKeys.THEME, userId) ?: return null
        val mask =
            try {
                JSONObject(raw).optString("iconMask").takeIf { it.isNotEmpty() }
            } catch (e: JSONException) {
                null
            } ?: return null
        if (mask.length > MAX_LENGTH) {
            Log.w(TAG, "Ignoring an icon mask of ${mask.length} characters")
            return null
        }
        return try {
            PathParser.createPathFromPathData(mask)
            mask
        } catch (e: RuntimeException) {
            Log.w(TAG, "Ignoring an icon mask that is not a path", e)
            null
        }
    }

    /** Fills [overlay] with [mask] as the system icon mask. */
    @JvmStatic
    fun fill(overlay: FabricatedOverlay, mask: String) {
        overlay.setResourceValue(RESOURCE, TypedValue.TYPE_STRING, mask, null)
    }
}
