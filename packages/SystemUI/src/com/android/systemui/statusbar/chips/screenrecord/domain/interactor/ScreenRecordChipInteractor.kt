/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.chips.screenrecord.domain.interactor

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.content.pm.PackageManager
import android.content.res.Resources
import android.media.projection.StopReason
import android.service.quicksettings.TileService
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.res.R
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.MediaProjectionRepository
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.ScreenRecordRepository
import com.android.systemui.statusbar.chips.StatusBarChipLogTags.pad
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.screenrecord.domain.model.ScreenRecordChipModel
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/** Interactor for the screen recording chip shown in the status bar. */
@SysUISingleton
class ScreenRecordChipInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Application private val context: Context,
    private val globalSettings: GlobalSettings,
    @Background private val backgroundContext: CoroutineContext,
    private val screenRecordRepository: ScreenRecordRepository,
    private val mediaProjectionRepository: MediaProjectionRepository,
    @StatusBarChipsLog private val logger: LogBuffer,
    @Main resources: Resources,
    private val packageManager: PackageManager,
) {
    /** A recorder app whose own projection counts as a screen recording. */
    private val recorderPackage: String? =
        resources.getString(R.string.config_screenRecorderPackage).ifEmpty { null }

    /**
     * The recorder app's own controller for its recording, or null when [state] is not one: its
     * quick-settings tile preferences activity, the screen it offers for the recording. Resolved
     * here, like a tile long-press, since such activities need not take implicit intents.
     */
    fun recorderController(state: ScreenRecordChipModel.Recording): Intent? {
        val pkg = recorderPackage?.takeIf { it == state.hostPackage } ?: return null
        val intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES).setPackage(pkg)
        val activity = packageManager.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo ?: return null
        return intent.setClassName(activity.packageName, activity.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The same controller when the recorder offers it as a service. An activity on top pauses the
     * app being recorded; a service can show its controller over it instead.
     */
    fun recorderControllerService(state: ScreenRecordChipModel.Recording): Intent? {
        val pkg = recorderPackage?.takeIf { it == state.hostPackage } ?: return null
        val intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES).setPackage(pkg)
        val service = packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo ?: return null
        return intent.setClassName(service.packageName, service.name)
    }

    fun openRecorderController(service: Intent) {
        context.startServiceAsUser(service, UserHandle.CURRENT)
    }

    /** The recorder's own timer: a chronometer base, minus the frozen time while paused, or 0. */
    private val recorderTimer: Flow<Long> =
        globalSettings
            .observerFlow(RECORDER_TIMER)
            .onStart { emit(Unit) }
            .map { globalSettings.getLong(RECORDER_TIMER, 0L) }
            .flowOn(backgroundContext)

    private fun isRecorderProjection(state: MediaProjectionState): Boolean =
        recorderPackage != null &&
            state is MediaProjectionState.Projecting &&
            state.hostPackage == recorderPackage

    /**
     * Emits true if we should assume that we're currently screen recording, even if
     * [ScreenRecordRepository.screenRecordState] hasn't emitted [ScreenRecordModel.Recording] yet.
     */
    private val shouldAssumeIsRecording: Flow<Boolean> =
        screenRecordRepository.screenRecordState
            .transformLatest {
                when (it) {
                    is ScreenRecordModel.DoingNothing -> {
                        emit(false)
                    }
                    is ScreenRecordModel.Starting -> {
                        // If we're told that the recording will start in [it.millisUntilStarted],
                        // optimistically assume the recording did indeed start after that time even
                        // if [ScreenRecordRepository.screenRecordState] hasn't emitted
                        // [ScreenRecordModel.Recording] yet. Start 50ms early so that the chip
                        // timer will definitely be showing by the time the recording actually
                        // starts - see b/366448907.
                        delay(it.millisUntilStarted - 50)
                        emit(true)
                    }
                    is ScreenRecordModel.Recording -> {}
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    val screenRecordState: StateFlow<ScreenRecordChipModel> =
        // ScreenRecordRepository has the main "is the screen being recorded?" state, and
        // MediaProjectionRepository has information about what specifically is being recorded (a
        // single app or the entire screen)
        combine(
                screenRecordRepository.screenRecordState,
                mediaProjectionRepository.mediaProjectionState,
                shouldAssumeIsRecording,
                recorderTimer,
            ) { screenRecordState, mediaProjectionState, shouldAssumeIsRecording, timer ->
                if (isRecorderProjection(mediaProjectionState)) {
                    val p = mediaProjectionState as MediaProjectionState.Projecting
                    ScreenRecordChipModel.Recording(
                        hostPackage = p.hostPackage,
                        recordedTask = (p as? MediaProjectionState.Projecting.SingleTask)?.task,
                        recorderTimer = timer,
                    )
                } else if (shouldAssumeIsRecording && screenRecordState is ScreenRecordModel.Starting) {
                    logger.log(
                        TAG,
                        LogLevel.INFO,
                        {},
                        {
                            "State: Recording(hostPackage=null, taskPackage=null) due to force-start"
                        },
                    )
                    ScreenRecordChipModel.Recording(hostPackage = null, recordedTask = null)
                } else {
                    when (screenRecordState) {
                        is ScreenRecordModel.DoingNothing -> {
                            logger.log(TAG, LogLevel.INFO, {}, { "State: DoingNothing" })
                            ScreenRecordChipModel.DoingNothing
                        }

                        is ScreenRecordModel.Starting -> {
                            logger.log(
                                TAG,
                                LogLevel.INFO,
                                { long1 = screenRecordState.millisUntilStarted },
                                { "State: Starting($long1)" },
                            )
                            ScreenRecordChipModel.Starting(screenRecordState.millisUntilStarted)
                        }

                        is ScreenRecordModel.Recording -> {
                            val recordedTask =
                                if (
                                    mediaProjectionState
                                        is MediaProjectionState.Projecting.SingleTask
                                ) {
                                    mediaProjectionState.task
                                } else {
                                    null
                                }
                            val hostPackage =
                                if (mediaProjectionState is MediaProjectionState.Projecting) {
                                    mediaProjectionState.hostPackage
                                } else {
                                    null
                                }
                            logger.log(
                                TAG,
                                LogLevel.INFO,
                                {
                                    str1 = hostPackage
                                    str2 = recordedTask?.baseIntent?.component?.packageName
                                },
                                { "State: Recording(hostPackage=$str1, taskPackage=$str2)" },
                            )
                            ScreenRecordChipModel.Recording(
                                hostPackage = hostPackage,
                                recordedTask = recordedTask,
                            )
                        }
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), ScreenRecordChipModel.DoingNothing)

    /** Stops the recording. */
    fun stopRecording() {
        scope.launch(backgroundContext) {
            // The recorder app finalizes its file when its projection stops.
            val now = screenRecordState.value
            if (recorderPackage != null && now is ScreenRecordChipModel.Recording && now.hostPackage == recorderPackage) {
                mediaProjectionRepository.stopProjecting(StopReason.STOP_PRIVACY_CHIP)
            } else {
                screenRecordRepository.stopRecording(StopReason.STOP_PRIVACY_CHIP)
            }
        }
    }

    companion object {
        private const val RECORDER_TIMER = "rekam_timer"
        private val TAG = "ScreenRecord".pad()
    }
}
