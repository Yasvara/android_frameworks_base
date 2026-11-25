/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import org.json.JSONException
import org.json.JSONObject

/**
 * Receives depth wallpapers from Laras (docs/specs/0013 §6). Guarded by CUSTOMIZE_SYSTEM_UI, which
 * only privileged customization apps hold; everything it receives is validated by [DepthStore].
 * Files come as descriptors, so their size is not bound by a Binder transaction.
 *
 * Methods (ContentResolver.call), `arg` = the picture's fingerprint (SHA-256 of the wallpaper's
 * original file, lowercase hex):
 * - `put_depth`: extras `matte` (ParcelFileDescriptor of a PNG over the whole original picture,
 *   white, alpha = subject), optionally `subject` (a PNG of the cut-out subject, the same size,
 *   its colour fixed where it is see-through) and `meta` (JSON object as a string). Returns `ok`.
 * - `remove_depth`: forgets that picture's depth.
 * - `list_depth`: no arg; returns `fingerprints`, most recently shown first.
 *
 * `openFile` reads a saved matte back, read-only: `content://<authority>/depth/<fingerprint>/matte.png`.
 * Laras starts a picture's project from it when it has none of its own (a depth made before
 * the library existed, or after Laras' data was cleared), so earlier work is not lost.
 */
class DepthAssetsProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        val userId = UserHandle.getUserId(Binder.getCallingUid())
        val store = DepthAssets.store(context)
        val identity = Binder.clearCallingIdentity()
        try {
            return when (method) {
                METHOD_PUT -> {
                    val fingerprint = arg ?: return null
                    val matte = extras?.getParcelable(KEY_MATTE, ParcelFileDescriptor::class.java)
                        ?: return null
                    val meta = try {
                        JSONObject(extras.getString(KEY_META) ?: "{}")
                    } catch (e: JSONException) {
                        return null
                    }
                    val subject = extras.getParcelable(KEY_SUBJECT, ParcelFileDescriptor::class.java)
                    val ok = try {
                        store.write(userId, fingerprint, matte, subject, meta)
                    } finally {
                        matte.close()
                        subject?.close()
                    }
                    Bundle().apply { putBoolean(KEY_OK, ok) }
                }
                METHOD_REMOVE -> {
                    store.remove(userId, arg ?: return null)
                    Bundle().apply { putBoolean(KEY_OK, true) }
                }
                METHOD_LIST -> Bundle().apply {
                    putStringArray(KEY_FINGERPRINTS, store.fingerprints(userId).toTypedArray())
                }
                else -> null
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        if (mode != "r") throw SecurityException("Depth mattes are read-only")
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != PATH_DEPTH || segments[2] != PATH_MATTE) return null
        val userId = UserHandle.getUserId(Binder.getCallingUid())
        val identity = Binder.clearCallingIdentity()
        try {
            val entry = DepthAssets.store(context).entry(userId, segments[1]) ?: return null
            return ParcelFileDescriptor.open(entry.matte, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0

    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        const val METHOD_PUT = "put_depth"
        const val METHOD_REMOVE = "remove_depth"
        const val METHOD_LIST = "list_depth"
        const val KEY_MATTE = "matte"
        const val KEY_SUBJECT = "subject"
        const val KEY_META = "meta"
        const val KEY_OK = "ok"
        const val KEY_FINGERPRINTS = "fingerprints"
        private const val PATH_DEPTH = "depth"
        private const val PATH_MATTE = "matte.png"
    }
}

/** The one [DepthStore] of the SystemUI process, shared by the provider and the lock screen. */
object DepthAssets {
    @Volatile private var store: DepthStore? = null

    fun store(context: android.content.Context): DepthStore =
        store ?: synchronized(this) {
            store ?: DepthStore(context.applicationContext).also { store = it }
        }
}
