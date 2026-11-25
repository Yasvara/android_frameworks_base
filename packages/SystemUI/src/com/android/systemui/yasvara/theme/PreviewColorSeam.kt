/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.theme

import android.content.Context
import android.content.om.FabricatedOverlay
import android.content.om.OverlayManager
import android.content.om.OverlayManagerTransaction
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Bundle
import android.os.Trace
import android.util.Log
import android.util.SparseIntArray
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.widget.RemoteViews

/**
 * Gives a preview its own copy of SystemUI's resources with draft colours in place of the
 * applied `android:color/system_*` values, so the real views, inflated against it, show a
 * colour scheme that is not applied yet.
 *
 * Two ways to load the colours are kept while the M0 spike decides between them (§11 Q6):
 * [Seam.COLOR_RESOURCES], the ARSC table [RemoteViews.ColorResources] builds for widgets, and
 * [Seam.FRRO], a self-targeting fabricated overlay loaded through [ResourcesProvider.loadOverlay].
 */
object PreviewColorSeam {
    private const val TAG = "PreviewColorSeam"
    private const val FRRO_NAME = "yas_preview_colors"

    /** Request keys: parallel arrays of `android.R.color` ids and ARGB values, and the seam. */
    const val KEY_COLOR_IDS = "yas_color_ids"
    const val KEY_COLOR_VALUES = "yas_color_values"
    const val KEY_COLOR_SEAM = "yas_color_seam"

    enum class Seam(val key: String) {
        COLOR_RESOURCES("colorres"),
        FRRO("frro"),
    }

    /**
     * Returns [base] unchanged when [request] carries no colours, else a context with its own
     * resources and theme showing them. The theme is rebuilt from those resources, because a
     * theme created on the shared resources would keep resolving colour attributes there.
     */
    fun wrap(app: Context, base: Context, request: Bundle): Context {
        val ids = request.getIntArray(KEY_COLOR_IDS) ?: return base
        val values = request.getIntArray(KEY_COLOR_VALUES) ?: return base
        if (ids.isEmpty() || ids.size != values.size) return base
        val seam = Seam.entries.firstOrNull { it.key == request.getString(KEY_COLOR_SEAM) }
            ?: Seam.COLOR_RESOURCES
        return try {
            Trace.beginSection("PreviewColorSeam#${seam.key}")
            val own = base.createConfigurationContext(base.resources.configuration)
            when (seam) {
                Seam.COLOR_RESOURCES -> colorResources(own, ids, values)
                Seam.FRRO -> own.resources.addLoaders(frro(app, ids, values))
            }
            ContextThemeWrapper(own, app.applicationInfo.theme)
        } catch (e: Exception) {
            // A preview in applied colours beats no preview.
            Log.w(TAG, "Cannot load draft colours through $seam", e)
            base
        } finally {
            Trace.endSection()
        }
    }

    private fun colorResources(context: Context, ids: IntArray, values: IntArray) {
        val mapping = SparseIntArray(ids.size)
        ids.forEachIndexed { i, id -> mapping.put(id, values[i]) }
        val colors =
            RemoteViews.ColorResources.create(context, mapping)
                ?: error("ColorResources rejected the colour table")
        colors.apply(context)
    }

    private fun frro(app: Context, ids: IntArray, values: IntArray): ResourcesLoader {
        val overlayManager = app.getSystemService(OverlayManager::class.java)
        val overlay = FabricatedOverlay(FRRO_NAME, "android")
        ids.forEachIndexed { i, id ->
            overlay.setResourceValue(
                app.resources.getResourceName(id),
                TypedValue.TYPE_INT_COLOR_ARGB8,
                values[i],
                null,
            )
        }
        overlayManager.commit(
            OverlayManagerTransaction.newInstance().apply { registerFabricatedOverlay(overlay) }
        )
        val info =
            overlayManager.getOverlayInfosForTarget("android").first {
                it.overlayName == FRRO_NAME
            }
        return ResourcesLoader().apply { addProvider(ResourcesProvider.loadOverlay(info)) }
    }
}
