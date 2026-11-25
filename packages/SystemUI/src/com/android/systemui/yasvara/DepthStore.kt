/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara

import android.content.Context
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Where depth wallpapers live (docs/specs/0013 §6): SystemUI's device-encrypted storage, so the
 * lock screen can draw the subject before the first unlock after a reboot.
 *
 * One entry per picture, named by its fingerprint: the SHA-256 of the wallpaper's original file.
 * Setting a picture again finds its depth at once, whatever wallpaper id it gets. An entry holds
 * `matte.png`, the subject's alpha over the whole original picture (white, alpha = subject),
 * optionally `subject.png`, the subject itself at the matte's size with its colour fixed where it
 * is see-through (S4), and `meta.json` from Laras; its mtime is when the lock screen last used it, and the least recently
 * used entries beyond [MAX_ENTRIES] are dropped. Laras sends entries through
 * [DepthAssetsProvider]; this validates and keeps them.
 */
class DepthStore(context: Context) {
    private val root = File(context.createDeviceProtectedStorageContext().filesDir, "depth")

    /** Bumps whenever an entry is saved or removed, so readers reload. */
    val version: StateFlow<Int> get() = _version
    private val _version = MutableStateFlow(0)

    /**
     * A saved depth: [matte] is [width] x [height], over the whole picture; [subject], when Laras
     * sent one, is the cut-out subject at the same size.
     */
    class Entry(val fingerprint: String, val matte: File, val width: Int, val height: Int, val subject: File?)

    fun entry(userId: Int, fingerprint: String): Entry? {
        if (!isFingerprint(fingerprint)) return null
        val dir = File(userDir(userId), fingerprint)
        val matte = File(dir, MATTE).takeIf { it.isFile } ?: return null
        val bounds = bounds(matte) ?: return null
        val subject = File(dir, SUBJECT).takeIf { it.isFile }
        return Entry(fingerprint, matte, bounds.outWidth, bounds.outHeight, subject)
    }

    /** Marks [fingerprint] as just shown, so it is the last to be dropped. */
    fun touch(userId: Int, fingerprint: String) {
        if (!isFingerprint(fingerprint)) return
        File(File(userDir(userId), fingerprint), META).setLastModified(System.currentTimeMillis())
    }

    /** Fingerprints with a saved depth, most recently used first. */
    fun fingerprints(userId: Int): List<String> =
        entries(userId).sortedByDescending { File(it, META).lastModified() }.map { it.name }

    /**
     * Saves the matte read from [matte] (a PNG, at most [MAX_BYTES]), the [subject] cut out (a
     * PNG of the same size, at most [MAX_SUBJECT_BYTES]) and [meta] as the depth of
     * [fingerprint]; false if any is not sane. Replaces an earlier entry for the same picture.
     */
    fun write(
        userId: Int,
        fingerprint: String,
        matte: ParcelFileDescriptor,
        subject: ParcelFileDescriptor?,
        meta: JSONObject,
    ): Boolean {
        if (!isFingerprint(fingerprint)) return false
        val user = userDir(userId).apply { mkdirs() }
        val staging = File(user, ".$fingerprint.new").apply { deleteRecursively(); mkdirs() }
        try {
            val png = File(staging, MATTE)
            if (!copy(matte, png, MAX_BYTES)) return false
            val bounds = bounds(png) ?: return false
            if (subject != null) {
                val cut = File(staging, SUBJECT)
                if (!copy(subject, cut, MAX_SUBJECT_BYTES)) return false
                val size = bounds(cut) ?: return false
                if (size.outWidth != bounds.outWidth || size.outHeight != bounds.outHeight) return false
            }
            meta.put(KEY_FINGERPRINT, fingerprint)
                .put(KEY_WIDTH, bounds.outWidth)
                .put(KEY_HEIGHT, bounds.outHeight)
                .put(KEY_SAVED, System.currentTimeMillis())
            writeAtomically(File(staging, META), meta.toString().toByteArray())
            val dir = File(user, fingerprint)
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) return false
        } catch (e: IOException) {
            Log.w(TAG, "Could not save depth $fingerprint", e)
            return false
        } finally {
            staging.deleteRecursively()
        }
        prune(userId)
        _version.value++
        return true
    }

    fun remove(userId: Int, fingerprint: String) {
        if (!isFingerprint(fingerprint)) return
        File(userDir(userId), fingerprint).deleteRecursively()
        _version.value++
    }

    fun clear(userId: Int) {
        userDir(userId).deleteRecursively()
        _version.value++
    }

    /**
     * Before a user had one mask, tied to a wallpaper id and made over the cropped
     * wallpaper. Moves it under [fingerprint] if it was made for [wallpaperId] and the cropped
     * wallpaper is the whole original ([wholePicture]), so it lines up; drops it otherwise.
     */
    fun adoptLegacy(userId: Int, wallpaperId: Int, fingerprint: String, wholePicture: Boolean): Entry? {
        val user = userDir(userId)
        val mask = File(user, LEGACY_MASK)
        val meta = File(user, LEGACY_META)
        if (!meta.exists()) return null
        val madeFor = runCatching {
            JSONObject(AtomicFile(meta).readFully().toString(Charsets.UTF_8)).getInt(LEGACY_KEY_ID)
        }.getOrDefault(-1)
        var adopted: Entry? = null
        if (madeFor == wallpaperId && wholePicture && isFingerprint(fingerprint)) {
            val dir = File(user, fingerprint).apply { mkdirs() }
            if (mask.renameTo(File(dir, MATTE))) {
                writeAtomically(File(dir, META), JSONObject().put(KEY_LEGACY, true).toString().toByteArray())
                adopted = entry(userId, fingerprint)
            }
        }
        Log.i(TAG, "Legacy depth mask for wallpaper $madeFor " + if (adopted != null) "adopted" else "dropped")
        mask.delete()
        meta.delete()
        return adopted
    }

    private fun userDir(userId: Int) = File(root, userId.toString())

    private fun entries(userId: Int): List<File> =
        userDir(userId).listFiles { f -> f.isDirectory && isFingerprint(f.name) }?.toList().orEmpty()

    private fun prune(userId: Int) {
        entries(userId)
            .sortedByDescending { File(it, META).lastModified() }
            .drop(MAX_ENTRIES)
            .forEach {
                Log.i(TAG, "Dropping least recently used depth ${it.name}")
                it.deleteRecursively()
            }
    }

    /** Copies at most [limit] bytes from [from]; false if there was more. */
    private fun copy(from: ParcelFileDescriptor, to: File, limit: Long): Boolean {
        FileInputStream(from.fileDescriptor).use { input ->
            to.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var n = input.read(buffer)
                while (n >= 0) {
                    total += n
                    if (total > limit) return false
                    output.write(buffer, 0, n)
                    n = input.read(buffer)
                }
            }
        }
        return true
    }

    private fun bounds(png: File): BitmapFactory.Options? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.path, bounds)
        return bounds.takeIf {
            it.outMimeType == "image/png" && it.outWidth in MIN_SIDE..MAX_SIDE &&
                it.outHeight in MIN_SIDE..MAX_SIDE
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    companion object {
        private const val TAG = "DepthStore"
        private const val MATTE = "matte.png"
        private const val SUBJECT = "subject.png"
        private const val META = "meta.json"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_SAVED = "savedAt"
        private const val KEY_LEGACY = "legacy"
        private const val LEGACY_MASK = "mask.png"
        private const val LEGACY_META = "mask.json"
        private const val LEGACY_KEY_ID = "wallpaperId"
        private const val MAX_ENTRIES = 32
        private const val MAX_BYTES = 32L * 1024 * 1024
        /** A 4096 x 4096 subject, uncompressed, is 64 MB; a PNG of one is far less. */
        private const val MAX_SUBJECT_BYTES = 64L * 1024 * 1024
        private const val MIN_SIDE = 16
        private const val MAX_SIDE = 8192
        private val HEX = Regex("[0-9a-f]{64}")

        fun isFingerprint(s: String) = HEX.matches(s)
    }
}
