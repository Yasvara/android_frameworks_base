/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.volume.dialog.swara

import android.content.Intent
import android.os.UserHandle
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.app.tracing.coroutines.launchTraced
import com.android.compose.theme.PlatformTheme
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import com.android.systemui.volume.swara.SwaraApp
import com.android.systemui.volume.swara.SwaraApps
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * Per-app volume in the volume dialog (2026-10-07 design).
 * - A stack of app icons above the settings button, only while two or more apps play or a
 *   playing app is turned down.
 * - Tapping it slides out one slim slider per playing app (up to three) beside the dialog. A
 *   tap on an app's icon mutes it; a level is saved when the finger lifts.
 * The volume keys keep driving the stream slider.
 */
@VolumeDialogScope
class VolumeDialogSwaraViewBinder
@Inject
constructor(private val dialogViewModel: VolumeDialogViewModel) : ViewBinder {

    override fun CoroutineScope.bind(view: View) {
        // Only the vertical dialog has the slots; the horizontal one keeps the panel.
        val chip = view.findViewById<ComposeView>(R.id.volume_dialog_swara_chip) ?: return
        val strip = view.findViewById<ComposeView>(R.id.volume_dialog_swara_strip) ?: return
        launchTraced("VDSwaraVB#addTouchableBounds") {
            dialogViewModel.addTouchableBounds(chip, strip)
        }
        // One service listener for both views, alive while the dialog is.
        val apps =
            SwaraApps.observe(view.context.applicationContext)
                .stateIn(this, SharingStarted.Eagerly, emptyList())
        val expanded = MutableStateFlow(false)

        chip.setContent {
            PlatformTheme {
                val list by apps.collectAsStateWithLifecycle()
                val open by expanded.collectAsStateWithLifecycle()
                val playing = list.filter { it.active }
                val show = playing.size >= 2 || playing.any { it.reduced }
                LaunchedEffect(show) { if (!show) expanded.value = false }
                if (show) {
                    AppStack(playing, open) {
                        dialogViewModel.resetDialogTimeout()
                        expanded.value = !expanded.value
                    }
                }
            }
        }
        strip.setContent {
            PlatformTheme {
                val list by apps.collectAsStateWithLifecycle()
                val open by expanded.collectAsStateWithLifecycle()
                val playing = list.filter { it.active }
                AnimatedVisibility(
                    visible = open && playing.isNotEmpty(),
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                ) {
                    Strip(playing, onTouch = dialogViewModel::resetDialogTimeout)
                }
            }
        }
    }
}

@Composable
private fun AppStack(apps: List<SwaraApp>, open: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.swara_dialog_apps)
    Box(
        modifier =
            Modifier.size(48.dp)
                .clip(CircleShape)
                .background(if (open) colors.primaryContainer else colors.surface)
                .clickable(onClick = onClick)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val shown = apps.take(2)
        Box(Modifier.size(width = 36.dp, height = 24.dp)) {
            shown.forEachIndexed { i, app ->
                AppIcon(
                    app,
                    Modifier.size(24.dp)
                        .offset(x = (i * 12).dp)
                        .border(2.dp, if (open) colors.primaryContainer else colors.surface, CircleShape),
                )
            }
        }
        if (apps.any { it.reduced }) {
            // The G6 sign: something here is turned down.
            Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
            )
        }
    }
}

@Composable
private fun Strip(apps: List<SwaraApp>, onTouch: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            Modifier.fillMaxHeight()
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(colors.surface)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (app in apps.take(MAX_SLIDERS)) {
            AppSlider(app, onTouch)
        }
        if (apps.size > MAX_SLIDERS) {
            Box(
                Modifier.fillMaxHeight().width(32.dp).clip(RoundedCornerShape(16.dp)).clickable {
                    context.sendBroadcastAsUser(
                        Intent("com.android.systemui.action.LAUNCH_VOLUME_PANEL_DIALOG")
                            .setPackage(context.packageName),
                        UserHandle.CURRENT,
                    )
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+${apps.size - MAX_SLIDERS}",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun AppSlider(app: SwaraApp, onTouch: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var level by remember(app.pkg) { mutableFloatStateOf(app.volume) }
    var muted by remember(app.pkg) { mutableStateOf(app.muted) }
    var dragging by remember { mutableStateOf(false) }
    // Follow the service unless the finger is on this slider.
    LaunchedEffect(app.volume, app.muted) {
        if (!dragging) {
            level = app.volume
            muted = app.muted
        }
    }
    val shown = if (muted) 0f else level
    Column(
        modifier = Modifier.fillMaxHeight().width(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val muteLabel =
            stringResource(if (muted) R.string.swara_dialog_unmute else R.string.swara_dialog_mute,
                app.label)
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable {
                onTouch()
                muted = !muted
                SwaraApps.set(app.pkg) { putBoolean(android.app.yasvara.Swara.KEY_MUTED, muted) }
            }.semantics { contentDescription = muteLabel },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(app, Modifier.size(28.dp).alpha(if (muted) 0.4f else 1f))
            if (muted) {
                Icon(
                    painterResource(R.drawable.ic_volume_media_mute),
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(14.dp).align(Alignment.BottomEnd)
                        .clip(CircleShape).background(colors.surface),
                )
            }
        }
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceContainerHighest)
                .pointerInput(app.pkg) {
                    fun at(y: Float) = (1f - y / size.height).coerceIn(0f, 1f)
                    fun commit() {
                        // A level is saved on lift; 0% is stored as a mute by the service.
                        SwaraApps.set(app.pkg) {
                            putFloat(android.app.yasvara.Swara.KEY_VOLUME, level)
                            putBoolean(android.app.yasvara.Swara.KEY_MUTED, level < 0.01f)
                        }
                        muted = level < 0.01f
                    }
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            onTouch()
                            level = at(it.y)
                            muted = false
                        },
                        onDragEnd = {
                            dragging = false
                            commit()
                        },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        level = at(change.position.y)
                        onTouch()
                    }
                }
                .pointerInput(app.pkg) {
                    detectTapGestures { offset ->
                        onTouch()
                        level = (1f - offset.y / size.height).coerceIn(0f, 1f)
                        muted = level < 0.01f
                        SwaraApps.set(app.pkg) {
                            putFloat(android.app.yasvara.Swara.KEY_VOLUME, level)
                            putBoolean(android.app.yasvara.Swara.KEY_MUTED, muted)
                        }
                    }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .fillMaxHeight(shown.coerceAtLeast(0.001f))
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (muted) colors.outlineVariant else colors.primary)
            )
        }
        Text(
            if (muted) stringResource(R.string.swara_dialog_muted_short)
            else "${(level * 100).roundToInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = if (app.reduced || muted) colors.primary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppIcon(app: SwaraApp, modifier: Modifier) {
    val icon = app.icon
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = modifier.clip(CircleShape))
    } else {
        Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer))
    }
}

private const val MAX_SLIDERS = 3
