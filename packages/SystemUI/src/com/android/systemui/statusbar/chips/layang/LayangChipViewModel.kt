/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.chips.layang

import android.app.yasvara.ILayangListener
import android.app.yasvara.Layang
import android.app.yasvara.LayangWindowInfo
import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.internal.jank.Cuj
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.StatusBarChipLogTags.pad
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel.Companion.createDialogLaunchOnClickCallback
import com.android.systemui.statusbar.chips.uievents.StatusBarChipsUiEventLogger
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The Layang status-bar chip (the owner's design): visible while any floating,
 * mini or parked window exists, ranked below call, screen record, share and cast. Tapping it
 * opens the window list ([LayangWindowsDialogDelegate]).
 */
@SysUISingleton
class LayangChipViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val context: Context,
    private val dialogFactory: SystemUIDialog.Factory,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    @StatusBarChipsLog private val logger: LogBuffer,
    private val uiEventLogger: StatusBarChipsUiEventLogger,
) : OngoingActivityChipViewModel {
    private val instanceId = uiEventLogger.createNewInstanceId()

    /** Window list pushed by YasvaraLayangService; empty when the service is missing. */
    val windows: StateFlow<List<LayangWindowInfo>> =
        callbackFlow {
                val listener =
                    object : ILayangListener.Stub() {
                        override fun onWindowsChanged(windows: List<LayangWindowInfo>) {
                            trySend(windows)
                        }

                        override fun onAutoParked(taskId: Int) {
                            autoParked = taskId
                        }
                    }
                val service = Layang.service()
                try {
                    service?.registerListener(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "registerListener failed", e)
                }
                if (service == null) trySend(emptyList())
                awaitClose {
                    try {
                        service?.unregisterListener(listener)
                    } catch (e: Exception) {
                        Log.w(TAG, "unregisterListener failed", e)
                    }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Task id of the last window parked because of memory pressure, or -1 (§5.6). */
    @Volatile var autoParked: Int = -1

    override val chip: StateFlow<OngoingActivityChipModel> =
        windows
            .map { list ->
                if (list.isEmpty() || !chipEnabled()) {
                    OngoingActivityChipModel.Inactive()
                } else {
                    toChip(list)
                }
            }
            .stateIn(scope, SharingStarted.Lazily, OngoingActivityChipModel.Inactive())

    private fun chipEnabled(): Boolean =
        Settings.Secure.getIntForUser(
            context.contentResolver,
            Layang.KEY_CHIP,
            1,
            UserHandle.USER_CURRENT,
        ) != 0

    private fun toChip(list: List<LayangWindowInfo>): OngoingActivityChipModel.Active {
        return OngoingActivityChipModel.Active(
            key = KEY,
            icon =
                OngoingActivityChipModel.ChipIcon.SingleColorIcon(
                    Icon.Resource(
                        R.drawable.ic_yas_layang,
                        ContentDescription.Resource(R.string.layang_chip_description),
                    )
                ),
            content =
                if (list.size > 1) OngoingActivityChipModel.Content.Text(list.size.toString())
                else OngoingActivityChipModel.Content.IconOnly,
            colors = ColorsModel.AccentThemed,
            clickBehavior =
                OngoingActivityChipModel.ClickBehavior.ExpandAction(
                    createDialogLaunchOnClickCallback(
                        dialogDelegateCreator = { ctx ->
                            LayangWindowsDialogDelegate(ctx, dialogFactory, this)
                        },
                        dialogTransitionAnimator = dialogTransitionAnimator,
                        DIALOG_CUJ,
                        key = KEY,
                        instanceId = instanceId,
                        uiEventLogger = uiEventLogger,
                        logger = logger,
                        tag = TAG,
                    )
                ),
            instanceId = instanceId,
        )
    }

    companion object {
        const val KEY = "Layang"
        private val DIALOG_CUJ =
            DialogCuj(Cuj.CUJ_STATUS_BAR_LAUNCH_DIALOG_FROM_CHIP, tag = "Layang")
        private val TAG = "LayangChipVM".pad()
    }
}
