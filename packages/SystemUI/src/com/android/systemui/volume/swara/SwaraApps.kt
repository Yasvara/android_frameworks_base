/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.volume.swara

import android.app.ActivityManager
import android.app.yasvara.ISwaraListener
import android.app.yasvara.Swara
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** One app as the Volume Panel and the volume dialog show it. */
data class SwaraApp(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val playing: Boolean,
    val recording: Boolean,
    val volume: Float,
    val muted: Boolean,
    val micMuted: Boolean,
    val micGain: Float,
    val shareMic: Boolean,
    val outputType: Int,
    val outputAvailable: Boolean,
) {
    /** Playing or recording now: the only apps that get a row or a slider. */
    val active: Boolean
        get() = playing || recording

    /** Turned down or muted, so it must never be silent without a sign (G6). */
    val reduced: Boolean
        get() = muted || volume < 1f

    val hasMicRule: Boolean
        get() = micMuted || micGain != 1f || shareMic
}

/** SwaraService for SystemUI: the live app list as a flow, and rule writes. */
object SwaraApps {
    private const val TAG = "SwaraApps"
    private const val ICON_PX = 96
    private val icons = LruCache<String, ImageBitmap>(32)

    /** Every app Swara reports, refreshed on each service change; empty without the service. */
    fun observe(context: Context): Flow<List<SwaraApp>> =
        callbackFlow {
                val service = Swara.get()
                if (service == null) {
                    trySend(Unit)
                    awaitClose {}
                    return@callbackFlow
                }
                val listener =
                    object : ISwaraListener.Stub() {
                        override fun onAppsChanged(userId: Int) {
                            trySend(Unit)
                        }
                    }
                runCatching { service.registerListener(listener) }
                    .onFailure { Log.w(TAG, "registerListener", it) }
                trySend(Unit)
                awaitClose { runCatching { service.unregisterListener(listener) } }
            }
            .conflate()
            .map { load(context) }
            .flowOn(Dispatchers.IO)

    /** Merges b into pkg's rule; the call is oneway-cheap, so callers may use any thread. */
    fun set(pkg: String, build: Bundle.() -> Unit) {
        val b = Bundle().apply(build)
        runCatching { Swara.get()?.setRules(pkg, ActivityManager.getCurrentUser(), b) }
            .onFailure { Log.w(TAG, "setRules $pkg", it) }
    }

    /** Laras › Sound › App sound, where closed apps with a rule live. */
    fun openManage(context: Context) {
        val intent =
            Intent("org.yasvara.laras.action.SOUND_APPS")
                .setPackage("org.yasvara.laras")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { context.startActivityAsUser(intent, UserHandle.CURRENT) }
            .onFailure { Log.w(TAG, "Laras missing", it) }
    }

    private fun load(context: Context): List<SwaraApp> {
        val service = Swara.get() ?: return emptyList()
        val userId = ActivityManager.getCurrentUser()
        val bundles = runCatching { service.getApps(userId) }.getOrNull() ?: return emptyList()
        val pm = context.packageManager
        return bundles.mapNotNull { b ->
            val pkg = b.getString(Swara.KEY_PACKAGE) ?: return@mapNotNull null
            val info =
                runCatching {
                        pm.getApplicationInfoAsUser(
                            pkg,
                            PackageManager.ApplicationInfoFlags.of(0),
                            userId,
                        )
                    }
                    .getOrNull()
            val icon =
                icons.get(pkg)
                    ?: info
                        ?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
                        ?.toBitmap(ICON_PX, ICON_PX)
                        ?.asImageBitmap()
                        ?.also { icons.put(pkg, it) }
            SwaraApp(
                pkg = pkg,
                label = info?.loadLabel(pm)?.toString() ?: pkg,
                icon = icon,
                playing = b.getBoolean(Swara.KEY_PLAYING),
                recording = b.getBoolean(Swara.KEY_RECORDING),
                volume = b.getFloat(Swara.KEY_VOLUME, 1f),
                muted = b.getBoolean(Swara.KEY_MUTED),
                micMuted = b.getBoolean(Swara.KEY_MIC_MUTED),
                micGain = b.getFloat(Swara.KEY_MIC_GAIN, 1f),
                shareMic = b.getBoolean(Swara.KEY_SHARE_MIC),
                outputType = b.getInt(Swara.KEY_OUTPUT_TYPE),
                outputAvailable = b.getBoolean(Swara.KEY_OUTPUT_AVAILABLE, true),
            )
        }
    }
}
