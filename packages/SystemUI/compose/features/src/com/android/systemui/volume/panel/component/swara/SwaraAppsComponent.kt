/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.volume.panel.component.swara

import android.app.ActivityManager
import android.app.yasvara.ISwaraManager
import android.app.yasvara.Swara
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.key
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.res.R
import com.android.systemui.volume.swara.SwaraApp
import com.android.systemui.volume.swara.SwaraApps
import com.android.systemui.volume.panel.ui.composable.ComposeVolumePanelUiComponent
import com.android.systemui.volume.panel.ui.composable.VolumePanelComposeScope
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Volume Panel "Apps": one row per app that plays, records or has a Swara rule,
 * with its volume, mute, mic and output device. Talks to SwaraService directly; there is no
 * state to share with the rest of the panel.
 */
class SwaraAppsComponent @Inject constructor() : ComposeVolumePanelUiComponent {

    @Composable
    override fun VolumePanelComposeScope.Content(modifier: Modifier) {
        val context = LocalContext.current
        val flow = remember { SwaraApps.observe(context.applicationContext) }
        val all by flow.collectAsStateWithLifecycle(emptyList())
        val userId = remember { ActivityManager.getCurrentUser() }
        val service = remember { Swara.get() } ?: return
        // Only apps that play or record get a row; a closed app with a rule folds into one
        // line, so a quick panel never looks like it holds apps that are not running.
        val apps = all.filter { it.active }.map { it.toRow() }
        val closed = all.count { !it.active && it.reduced }

        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.swara_apps_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp),
            )
            if (apps.isEmpty()) {
                Text(
                    text = stringResource(R.string.swara_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            for (app in apps) {
                key(app.pkg) { AppRowContent(app, service, userId) }
            }
            if (closed > 0) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(20.dp),
                            )
                            .clickable { SwaraApps.openManage(context) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pluralStringResource(R.plurals.swara_apps_closed, closed, closed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.swara_apps_manage),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AppRowContent(app: AppRow, service: ISwaraManager, userId: Int) {
        val context = LocalContext.current
        var volume by remember(app) { mutableFloatStateOf(app.volume) }
        var muted by remember(app) { mutableStateOf(app.muted) }
        var micMuted by remember(app) { mutableStateOf(app.micMuted) }
        var micGain by remember(app) { mutableFloatStateOf(app.micGain) }
        var shareMic by remember(app) { mutableStateOf(app.shareMic) }
        var micMenu by remember { mutableStateOf(false) }
        var outputMenu by remember { mutableStateOf(false) }

        fun send(build: Bundle.() -> Unit) {
            val b = Bundle().apply(build)
            runCatching { service.setRules(app.pkg, userId, b) }
                .onFailure { Log.w(TAG, "setRules ${app.pkg}", it) }
        }

        val reduced = muted || volume < 1f
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    // The icon is the mute toggle (§5.1).
                    IconButton(
                        onClick = {
                            muted = !muted
                            send { putBoolean(Swara.KEY_MUTED, muted) }
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        app.icon?.let {
                            Image(
                                bitmap = it,
                                contentDescription = app.label,
                                modifier = Modifier.size(32.dp).clip(CircleShape),
                                alpha = if (muted) 0.4f else 1f,
                            )
                        }
                    }
                    if (muted) {
                        Icon(
                            painter = painterResource(R.drawable.ic_volume_media_mute),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier =
                                Modifier.size(16.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface),
                        )
                    } else if (app.playing) {
                        Box(
                            Modifier.size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (reduced) {
                        Text(
                            text =
                                if (muted) stringResource(R.string.swara_apps_muted)
                                else "${(volume * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (app.recording) {
                    Box {
                        Chip(
                            icon = if (micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_26dp,
                            active = micMuted || micGain != 1f || shareMic,
                            modifier =
                                Modifier.combinedClickable(
                                    onClick = {
                                        micMuted = !micMuted
                                        send { putBoolean(Swara.KEY_MIC_MUTED, micMuted) }
                                    },
                                    onLongClick = { micMenu = true },
                                ),
                        )
                        DropdownMenu(expanded = micMenu, onDismissRequest = { micMenu = false }) {
                            Column(Modifier.width(260.dp).padding(horizontal = 16.dp)) {
                                Text(
                                    stringResource(R.string.swara_mic_gain, (micGain * 100).roundToInt()),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Slider(
                                    value = micGain,
                                    onValueChange = { micGain = it },
                                    onValueChangeFinished = {
                                        send { putFloat(Swara.KEY_MIC_GAIN, micGain) }
                                    },
                                    valueRange = 0f..Swara.MAX_MIC_GAIN,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.swara_share_mic),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = shareMic,
                                        onCheckedChange = {
                                            shareMic = it
                                            send { putBoolean(Swara.KEY_SHARE_MIC, it) }
                                        },
                                    )
                                }
                                Text(
                                    stringResource(R.string.swara_share_mic_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box {
                    Chip(
                        icon = outputIcon(app.outputType),
                        active = app.outputType != 0,
                        error = !app.outputAvailable,
                        modifier = Modifier.combinedClickable(onClick = { outputMenu = true }),
                    )
                    DropdownMenu(expanded = outputMenu, onDismissRequest = { outputMenu = false }) {
                        for ((type, address, name) in outputs(context)) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        name,
                                        color =
                                            if (type == app.outputType)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                leadingIcon = {
                                    Icon(painterResource(outputIcon(type)), contentDescription = null)
                                },
                                onClick = {
                                    outputMenu = false
                                    send {
                                        putInt(Swara.KEY_OUTPUT_TYPE, type)
                                        putString(Swara.KEY_OUTPUT_ADDRESS, address)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Slider(
                value = if (muted) 0f else volume,
                onValueChange = {
                    volume = it
                    muted = it < 0.01f
                },
                // Saved on lift, so a stray touch while scrolling the panel changes nothing.
                onValueChangeFinished = {
                    send {
                        putFloat(Swara.KEY_VOLUME, volume)
                        putBoolean(Swara.KEY_MUTED, muted)
                    }
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun Chip(icon: Int, active: Boolean, modifier: Modifier, error: Boolean = false) {
        val colors = MaterialTheme.colorScheme
        Box(
            modifier =
                Modifier.size(36.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            error -> colors.errorContainer
                            active -> colors.primaryContainer
                            else -> colors.surfaceContainerHighest
                        }
                    )
                    .then(modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint =
                    when {
                        error -> colors.onErrorContainer
                        active -> colors.onPrimaryContainer
                        else -> colors.onSurfaceVariant
                    },
                modifier = Modifier.size(18.dp),
            )
        }
    }

    private data class AppRow(
        val pkg: String,
        val label: String,
        val icon: androidx.compose.ui.graphics.ImageBitmap?,
        val playing: Boolean,
        val recording: Boolean,
        val volume: Float,
        val muted: Boolean,
        val micMuted: Boolean,
        val micGain: Float,
        val shareMic: Boolean,
        val outputType: Int,
        val outputAvailable: Boolean,
    )

    private data class Output(val type: Int, val address: String, val name: String)

    private fun SwaraApp.toRow() =
        AppRow(pkg, label, icon, playing, recording, volume, muted, micMuted, micGain, shareMic,
            outputType, outputAvailable)

    private companion object {
        const val TAG = "SwaraApps"

        val ROUTABLE =
            setOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
            )

        fun outputIcon(type: Int): Int =
            when (type) {
                0 -> R.drawable.ic_volume_media
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.drawable.ic_speaker_on
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> R.drawable.ic_volume_media_bt
                else -> R.drawable.ic_headset
            }

        fun outputs(context: Context): List<Output> {
            val list = mutableListOf(Output(0, "", context.getString(R.string.swara_output_system)))
            val am = context.getSystemService(AudioManager::class.java) ?: return list
            val seen = mutableSetOf<Int>()
            for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.type !in ROUTABLE || !seen.add(d.type * 31 + d.address.hashCode())) continue
                val name =
                    if (d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                        context.getString(R.string.swara_output_speaker)
                    else d.productName.toString()
                list.add(Output(d.type, d.address, name))
            }
            return list
        }
    }
}
