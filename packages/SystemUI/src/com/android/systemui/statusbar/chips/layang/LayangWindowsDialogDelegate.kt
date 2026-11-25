/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.chips.layang

import android.app.yasvara.Layang
import android.app.yasvara.LayangTaskState
import android.app.yasvara.LayangWindowInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import kotlin.math.roundToInt

/**
 * The Layang chip popup: one row per window with icon, label and state. Tap a
 * row to bring the window forward or restore it; the × closes it. "Open app" picks an app to
 * open floating; "Close all" closes every window.
 */
class LayangWindowsDialogDelegate(
    private val context: Context,
    private val dialogFactory: SystemUIDialog.Factory,
    private val viewModel: LayangChipViewModel,
) : SystemUIDialog.Delegate {

    override fun createDialog(): SystemUIDialog = dialogFactory.create(this, context)

    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.setTitle(R.string.layang_dialog_title)
        dialog.setView(buildList(dialog))
        dialog.setNeutralButton(R.string.layang_dialog_done, null)
        dialog.setNegativeButton(R.string.layang_dialog_close_all) { _, _ -> closeAll() }
        dialog.setPositiveButton(R.string.layang_dialog_open_app) { _, _ ->
            dialogFactory.create(AppPickerDelegate(context, dialogFactory), context).show()
        }
    }

    private fun buildList(dialog: SystemUIDialog): View {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val windows = viewModel.windows.value
        if (windows.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    setText(R.string.layang_dialog_empty)
                    setPadding(dp(24), dp(8), dp(24), dp(8))
                }
            )
        }
        for (w in windows) {
            list.addView(row(dialog, w))
        }
        return ScrollView(context).apply { addView(list) }
    }

    private fun row(dialog: SystemUIDialog, w: LayangWindowInfo): View {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(24), 0, dp(12), 0)
                background = ripple()
                setOnClickListener {
                    dialog.dismiss()
                    bringBack(w.taskId)
                }
            }
        row.addView(
            ImageView(context).apply { setImageDrawable(appIcon(w.packageName, w.userId)) },
            LinearLayout.LayoutParams(dp(36), dp(36)),
        )
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(8), 0)
            }
        texts.addView(
            TextView(context).apply {
                text = w.label ?: w.packageName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                isSingleLine = true
            }
        )
        texts.addView(
            TextView(context).apply {
                setText(stateText(w))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                alpha = 0.7f
            }
        )
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                background = ripple()
                contentDescription =
                    context.getString(R.string.layang_close_window, w.label ?: w.packageName)
                setOnClickListener {
                    row.visibility = View.GONE
                    call { it.close(w.taskId) }
                }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        return row
    }

    private fun stateText(w: LayangWindowInfo): Int =
        when (w.state.mode) {
            LayangTaskState.MODE_MINI -> R.string.layang_state_mini
            LayangTaskState.MODE_PARKED ->
                if (viewModel.autoParked == w.taskId) R.string.layang_state_parked_memory
                else R.string.layang_state_parked
            else -> R.string.layang_state_floating
        }

    private fun bringBack(taskId: Int) {
        call { it.openTask(taskId, Bundle()) }
    }

    private fun closeAll() {
        call { it.closeAll() }
    }

    private fun appIcon(pkg: String, userId: Int): Drawable? =
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfoAsUser(pkg, 0, userId)
            pm.getUserBadgedIcon(pm.getApplicationIcon(info), UserHandle.of(userId))
        } catch (e: Exception) {
            null
        }

    private fun ripple(): Drawable? {
        val tv = TypedValue()
        return if (
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        ) {
            context.getDrawable(tv.resourceId)
        } else null
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                context.resources.displayMetrics,
            )
            .roundToInt()

    /**
     * "Open app" (§6.3 chip popup): every launchable app of the current user with a search
     * box; picking one opens it as a Layang window.
     */
    private class AppPickerDelegate(
        private val context: Context,
        private val dialogFactory: SystemUIDialog.Factory,
    ) : SystemUIDialog.Delegate {
        private data class Entry(val label: String, val component: ComponentName, val icon: Drawable?)

        override fun createDialog(): SystemUIDialog = dialogFactory.create(this, context)

        override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
            dialog.setTitle(R.string.layang_picker_title)
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            val entries =
                launcherApps
                    ?.getActivityList(null, Process.myUserHandle())
                    ?.filter { it.componentName.packageName != context.packageName }
                    ?.map { Entry(it.label.toString(), it.componentName, it.getIcon(0)) }
                    ?.sortedBy { it.label.lowercase() } ?: emptyList()
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).roundToInt()
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val search =
                EditText(context).apply {
                    setHint(R.string.layang_picker_search)
                    isSingleLine = true
                }
            fun fill(query: String) {
                list.removeAllViews()
                for (e in entries) {
                    if (query.isNotEmpty() && !e.label.contains(query, ignoreCase = true)) continue
                    val row =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = dp(52)
                            setPadding(dp(24), 0, dp(24), 0)
                            setOnClickListener {
                                dialog.dismiss()
                                call { s ->
                                    s.openApp(e.component, Process.myUserHandle().identifier,
                                        Bundle())
                                }
                            }
                        }
                    row.addView(
                        ImageView(context).apply { setImageDrawable(e.icon) },
                        LinearLayout.LayoutParams(dp(32), dp(32)),
                    )
                    row.addView(
                        TextView(context).apply {
                            text = e.label
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            setPadding(dp(16), 0, 0, 0)
                        }
                    )
                    list.addView(row)
                }
            }
            fill("")
            search.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

                    override fun afterTextChanged(s: Editable?) = fill(s?.toString() ?: "")
                }
            )
            val root =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                    addView(search)
                    addView(
                        ScrollView(context).apply { addView(list) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)),
                    )
                }
            dialog.setView(root)
            dialog.setNeutralButton(R.string.layang_dialog_done, null)
        }
    }

    companion object {
        private const val TAG = "LayangWindowsDialog"

        /** Runs a service call off the main thread; failures are logged only. */
        private fun call(block: (android.app.yasvara.ILayangManager) -> Unit) {
            Thread {
                    try {
                        Layang.service()?.let(block)
                    } catch (e: Exception) {
                        Log.w(TAG, "Layang call failed", e)
                    }
                }
                .start()
        }
    }
}
