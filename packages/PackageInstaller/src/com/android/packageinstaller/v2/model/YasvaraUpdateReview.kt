/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.packageinstaller.v2.model

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.text.format.Formatter
import android.text.Html
import android.util.Log
import com.android.packageinstaller.R

/**
 * Yasvara: what a store update the owner asked to confirm would change.
 * The facts are worked out by YasvaraUpdatePolicyService in system_server; this app builds
 * against system_current, so it talks to the service over raw binder (platform-signed apps
 * may reach it, and the service lets INSTALL_PACKAGES holders read reviews).
 */
object YasvaraUpdateReview {
    private const val TAG = "YasvaraUpdateReview"
    private const val SERVICE = "yasvara_update_policy"
    private const val DESCRIPTOR = "android.security.yasvara.IYasvaraUpdatePolicy"
    // getReview is the 13th method of IYasvaraUpdatePolicy; that AIDL is append-only.
    private const val TRANSACTION_GET_REVIEW = IBinder.FIRST_CALL_TRANSACTION + 12

    /** The review lines for pkg, or empty when there is none (not an Ask or paused update). */
    fun fetch(pkg: String?): List<String> {
        if (pkg == null) return emptyList()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, SERVICE) as IBinder? ?: return emptyList()
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(pkg)
            data.writeInt(Process.myUserHandle().identifier)
            binder.transact(TRANSACTION_GET_REVIEW, data, reply, 0)
            reply.readException()
            reply.createStringArray()?.toList().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "no review for $pkg", t)
            emptyList()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** The review as dialog text, or null when there is nothing to show. */
    fun format(context: Context, lines: List<String>): CharSequence? {
        if (lines.isEmpty()) return null
        val pm = context.packageManager
        val out = StringBuilder()
        val added = mutableListOf<String>()
        var removed = 0
        for (line in lines) {
            val p = line.split('|')
            when (p[0]) {
                "paused" -> out.append(esc(context.getString(R.string.yasvara_paused_message)))
                    .append("<br><br>")
                "version" -> out.append("<b>")
                    .append(esc(context.getString(R.string.yasvara_review_version,
                        p[1].ifEmpty { p[3] }, p[2].ifEmpty { p[4] })))
                    .append("</b><br>")
                "size" -> out.append(esc(context.getString(R.string.yasvara_review_size,
                    Formatter.formatShortFileSize(context, p[1].toLongOrNull() ?: 0),
                    Formatter.formatShortFileSize(context, p[2].toLongOrNull() ?: 0)))).append("<br>")
                "target" -> out.append(esc(context.getString(R.string.yasvara_review_target, p[1], p[2])))
                    .append("<br>")
                "signer" -> if (p[1] == "changed") {
                    out.append("<font color=\"#D32F2F\"><b>")
                        .append(esc(context.getString(R.string.yasvara_review_signer_changed)))
                        .append("</b></font><br>")
                }
                "perm+" -> {
                    val label = try {
                        pm.getPermissionInfo(p[1], 0).loadLabel(pm).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        p[1].substringAfterLast('.')
                    }
                    added += if (p.getOrNull(2) == "dangerous") "<b>⚠ ${esc(label)}</b>" else esc(label)
                }
                "perm-" -> removed++
                "installer" -> if (p[1].isNotEmpty()) {
                    val name = try {
                        pm.getApplicationInfo(p[1], 0).loadLabel(pm).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        p[1]
                    }
                    out.append(esc(context.getString(R.string.yasvara_review_installer, name)))
                        .append("<br>")
                }
            }
        }
        out.append("<br>")
        if (added.isEmpty()) {
            out.append(esc(context.getString(R.string.yasvara_review_no_new_permissions)))
        } else {
            out.append("<b>").append(esc(context.getString(R.string.yasvara_review_new_permissions)))
                .append("</b><br>")
            added.forEach { out.append("• ").append(it).append("<br>") }
        }
        if (removed > 0) {
            out.append("<br>").append(esc(context.resources.getQuantityString(
                R.plurals.yasvara_review_removed_permissions, removed, removed)))
        }
        return Html.fromHtml(out.toString(), Html.FROM_HTML_MODE_LEGACY)
    }

    private fun esc(s: String) = Html.escapeHtml(s)
}
