/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** How the clock stacks against the depth subject. */
enum class DepthClockLayer {
    /** Decided from how much of the clock the subject covers. */
    AUTO,
    /** The clock is drawn over the subject. */
    FRONT,
    /** The subject is drawn over the clock: the depth look. */
    BEHIND,
}

/**
 * The depth wallpaper (docs/specs/0001 M3, 0013 §6): the lock wallpaper's subject, cut out of the
 * original picture with the matte Laras made for it, drawn as a layer of the lock screen so it
 * can stand in front of the clock.
 */
@SysUISingleton
class DepthRepository
@Inject
constructor(
    @Application private val context: Context,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
    private val safeMode: LockSafeMode,
    private val placement: WallpaperPlacement,
    @Application scope: CoroutineScope,
    @Background private val background: CoroutineDispatcher,
) {
    data class Config(
        val enabled: Boolean = false,
        val clock: DepthClockLayer = DepthClockLayer.AUTO,
        val opacity: Float = 1f,
        /** How much further than the wallpaper the subject moves with parallax. */
        val parallax: Float = 0.6f,
    )

    /**
     * The subject, cut out of the wallpaper's crop for this display (the part the window
     * manager fits to the screen, extra width for page scrolling included): [bitmap] covers
     * [bounds], fractions of that crop, which is [frameAspect] wide for 1 high. [coverage] is a
     * small copy of the mask over the whole crop, for measuring overlap.
     */
    class Subject(
        val bitmap: Bitmap,
        val bounds: RectF,
        val coverage: Bitmap,
        val frameAspect: Float,
    )

    private val store = DepthAssets.store(context)
    private val wallpaperManager = context.getSystemService(WallpaperManager::class.java)

    /** Pictures Laras was asked to build depth for. */
    private val requested = HashSet<String>()

    /** Fingerprints by "user:which:wallpaper id", so a wallpaper is hashed once. */
    private val fingerprints = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 8
    }

    val config: StateFlow<Config> =
        secureSettings
            .observerFlow(UserHandle.USER_ALL, KEY_ENABLED, KEY_CLOCK, KEY_OPACITY, KEY_PARALLAX)
            .onStart { emit(Unit) }
            .map { readConfig() }
            .flowOn(background)
            .stateIn(scope, SharingStarted.Eagerly, Config())

    private val wallpaperChanges = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        val listener = WallpaperManager.OnColorsChangedListener { _, _ -> trySend(Unit) }
        wallpaperManager.addOnColorsChangedListener(listener, handler, UserHandle.USER_ALL)
        val users = object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                trySend(Unit)
            }
        }
        userTracker.addCallback(users, Executor { handler.post(it) })
        trySend(Unit)
        awaitClose {
            wallpaperManager.removeOnColorsChangedListener(listener)
            userTracker.removeCallback(users)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val subject: StateFlow<Subject?> =
        combine(config.map { it.enabled }, store.version, wallpaperChanges) { enabled, _, _ ->
                enabled
            }
            .mapLatest { enabled -> if (enabled && !safeMode.isActive) build() else null }
            .flowOn(background)
            .stateIn(scope, SharingStarted.Eagerly, null)

    private fun readConfig(): Config {
        fun int(key: String, default: Int) =
            secureSettings.getIntForUser(key, default, UserHandle.USER_CURRENT)
        return Config(
            enabled = int(KEY_ENABLED, 0) != 0,
            clock = DepthClockLayer.entries.getOrElse(int(KEY_CLOCK, 0)) { DepthClockLayer.AUTO },
            opacity = int(KEY_OPACITY, 100).coerceIn(0, 100) / 100f,
            parallax = int(KEY_PARALLAX, 60).coerceIn(0, 200) / 100f,
        )
    }

    /**
     * Cuts the subject out of the lock wallpaper, or null if that picture has no saved depth.
     * Depth is found by the picture's fingerprint, so a picture set again gets its depth back.
     */
    private fun build(): Subject? {
        val userId = userTracker.userId
        val manager = context.createContextAsUser(UserHandle.of(userId), 0)
            .getSystemService(WallpaperManager::class.java) ?: return null
        val lockId = manager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, userId)
        val which = if (lockId >= 0) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
        val wallpaperId = if (lockId >= 0) lockId else {
            manager.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, userId)
        }
        val file = runCatching { manager.getWallpaperFile(which, /* getCropped= */ false) }
            .onFailure { Log.w(TAG, "No wallpaper file", it) }
            .getOrNull() ?: return null
        file.use { pfd ->
            val picture = Picture.open(pfd) ?: return null
            val fingerprint = fingerprintOf("$userId:$which:$wallpaperId", pfd) ?: return null
            val entry = store.entry(userId, fingerprint)
                ?: store.adoptLegacy(userId, wallpaperId, fingerprint, isWholePicture(manager, which, picture))
                ?: return null.also {
                    Log.i(TAG, "No depth for picture $fingerprint")
                    requestBuild(userId, fingerprint)
                }
            store.touch(userId, fingerprint)
            return try {
                cutOut(picture, entry, manager, which)
            } catch (e: Exception) {
                Log.w(TAG, "Could not cut out the depth subject", e)
                null
            }
        }
    }

    /**
     * Asks Laras to build depth for a new lock picture (docs/specs/0013 §9 Q3), once per picture
     * while SystemUI runs; Laras keeps track of what it built or was told not to.
     */
    private fun requestBuild(userId: Int, fingerprint: String) {
        if (!synchronized(requested) { requested.add(fingerprint) }) return
        val intent = Intent(ACTION_BUILD).setPackage(LARAS).putExtra(EXTRA_FINGERPRINT, fingerprint)
        runCatching { context.sendBroadcastAsUser(intent, UserHandle.of(userId)) }
            .onFailure { Log.w(TAG, "Could not ask Laras for depth", it) }
    }

    /** SHA-256 of the original file, remembered per wallpaper id (which changes on every set). */
    private fun fingerprintOf(key: String, pfd: ParcelFileDescriptor): String? =
        synchronized(fingerprints) { fingerprints[key] }
            ?: runCatching {
                Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                val input = FileInputStream(pfd.fileDescriptor)
                var n = input.read(buffer)
                while (n >= 0) {
                    digest.update(buffer, 0, n)
                    n = input.read(buffer)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.onFailure { Log.w(TAG, "Could not fingerprint the wallpaper", it) }
                .getOrNull()
                ?.also { synchronized(fingerprints) { fingerprints[key] = it } }

    /** True if the cropped wallpaper is the whole original, which old masks were made over. */
    private fun isWholePicture(manager: WallpaperManager, which: Int, picture: Picture): Boolean {
        val cropped = runCatching { manager.peekBitmapDimensions(which, false) }.getOrNull() ?: return false
        val aspect = picture.width / picture.height.toFloat()
        return kotlin.math.abs(cropped.width() / cropped.height().toFloat() - aspect) < 0.01f * aspect
    }

    private fun cutOut(picture: Picture, matte: DepthStore.Entry, manager: WallpaperManager, which: Int): Subject? {
        val metrics = context.resources.displayMetrics
        val width = minOf(metrics.widthPixels, metrics.heightPixels)
        val height = maxOf(metrics.widthPixels, metrics.heightPixels)
        val frame = frame(manager, which, picture, width, height)
        // The matte covers the whole picture, at its proportions.
        val kx = matte.width / picture.width.toFloat()
        val ky = matte.height / picture.height.toFloat()
        fun inMatte(r: Rect) = Rect(
            (r.left * kx).toInt(), (r.top * ky).toInt(),
            ceil(r.right * kx).toInt().coerceAtMost(matte.width),
            ceil(r.bottom * ky).toInt().coerceAtMost(matte.height),
        )

        // A small copy of the matte over the crop finds the subject and measures overlap.
        val fill = maxOf(width / frame.width().toFloat(), height / frame.height().toFloat())
        val coverage = decodeRegion(
            BitmapRegionDecoder.newInstance(matte.matte.path), inMatte(frame),
            (frame.width() * fill / COVERAGE_SCALE).toInt().coerceAtLeast(1),
            (frame.height() * fill / COVERAGE_SCALE).toInt().coerceAtLeast(1),
        )
        val box = coverageBounds(coverage) ?: return null

        // The subject at the size the window manager shows it, zoom included, but never
        // larger than the picture itself.
        val scale = minOf(fill * placement.scale(WallpaperPlacement.State()), 1f)
        val src = Rect(
            frame.left + (box.left * frame.width()).toInt(),
            frame.top + (box.top * frame.height()).toInt(),
            frame.left + (box.right * frame.width()).toInt(),
            frame.top + (box.bottom * frame.height()).toInt(),
        )
        if (src.isEmpty) return null
        val outWidth = (src.width() * scale).toInt().coerceAtLeast(1)
        val outHeight = (src.height() * scale).toInt().coerceAtLeast(1)
        val out = if (matte.subject != null) {
            // Laras cut it out already, its colour fixed where it is see-through (hair over sea).
            decodeRegion(BitmapRegionDecoder.newInstance(matte.subject.path), inMatte(src), outWidth, outHeight)
        } else {
            picture.decode(src, outWidth, outHeight).also { cut ->
                val alpha = decodeRegion(BitmapRegionDecoder.newInstance(matte.matte.path), inMatte(src), outWidth, outHeight)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                Canvas(cut).drawBitmap(alpha, 0f, 0f, paint)
                alpha.recycle()
            }
        }
        val hardware = out.copy(Bitmap.Config.HARDWARE, false) ?: out
        if (hardware !== out) out.recycle()
        return Subject(
            hardware,
            box,
            coverage,
            frame.width() / frame.height().toFloat(),
        )
    }

    /**
     * The crop of the original picture the window manager fits to the display. Without crop
     * hints (older wallpapers) it is the whole picture, which the window manager then centres.
     */
    private fun frame(manager: WallpaperManager, which: Int, picture: Picture, width: Int, height: Int): Rect {
        val whole = Rect(0, 0, picture.width, picture.height)
        val crop = runCatching {
            manager.getBitmapCrops(listOf(Point(width, height)), which, /* originalBitmap= */ true).firstOrNull()
        }.onFailure { Log.w(TAG, "No wallpaper crop", it) }.getOrNull()
        if (crop == null || crop.isEmpty || !crop.intersect(whole)) return whole
        return crop
    }

    /**
     * The wallpaper's original file, read a region at a time so a large picture is never decoded
     * whole. Laras makes the matte over the picture as ImageDecoder shows it, EXIF orientation
     * applied, which is also how the platform crops it; a rotated file is decoded whole instead.
     */
    private class Picture(private val pfd: ParcelFileDescriptor, val width: Int, val height: Int, val rotated: Boolean) {

        /** [region] of the picture, scaled to [outWidth] x [outHeight], as a mutable bitmap. */
        fun decode(region: Rect, outWidth: Int, outHeight: Int): Bitmap {
            rewind(pfd)
            if (!rotated) return decodeRegion(BitmapRegionDecoder.newInstance(pfd), region, outWidth, outHeight)
            val sample = sampleSize(region.width(), outWidth)
            val whole = ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes())) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sample)
            }
            val kx = whole.width / width.toFloat()
            val ky = whole.height / height.toFloat()
            val src = Rect((region.left * kx).toInt(), (region.top * ky).toInt(), (region.right * kx).toInt(), (region.bottom * ky).toInt())
            return scaled(whole, src, outWidth, outHeight).also { whole.recycle() }
        }

        private fun bytes(): ByteBuffer {
            rewind(pfd)
            return ByteBuffer.wrap(FileInputStream(pfd.fileDescriptor).readBytes())
        }

        companion object {
            fun open(pfd: ParcelFileDescriptor): Picture? = runCatching {
                rewind(pfd)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                rewind(pfd)
                val orientation = runCatching {
                    ExifInterface(pfd.fileDescriptor)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                // Transpose, rotate 90, transverse and rotate 270 swap the sides.
                val turned = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
                Picture(
                    pfd,
                    if (turned) bounds.outHeight else bounds.outWidth,
                    if (turned) bounds.outWidth else bounds.outHeight,
                    rotated = orientation > ExifInterface.ORIENTATION_NORMAL,
                )
            }.onFailure { Log.w(TAG, "Unreadable wallpaper", it) }.getOrNull()

            private fun rewind(pfd: ParcelFileDescriptor) {
                Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_SET)
            }
        }
    }

    /** The subject's bounds as fractions of the display, padded a little, or null if empty. */
    private fun coverageBounds(coverage: Bitmap): RectF? {
        val w = coverage.width
        val h = coverage.height
        val row = IntArray(w)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            coverage.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                if ((row[x] ushr 24) > ALPHA_THRESHOLD) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    bottom = y
                }
            }
        }
        if (right < 0) return null
        val pad = 2
        return RectF(
            (left - pad).coerceAtLeast(0) / w.toFloat(),
            (top - pad).coerceAtLeast(0) / h.toFloat(),
            (right + 1 + pad).coerceAtMost(w) / w.toFloat(),
            (bottom + 1 + pad).coerceAtMost(h) / h.toFloat(),
        )
    }

    companion object {
        private const val TAG = "DepthRepository"
        /** Laras' depth build (receiver guarded by STATUS_BAR_SERVICE, which SystemUI holds). */
        private const val LARAS = "org.yasvara.laras"
        private const val ACTION_BUILD = "org.yasvara.laras.depth.action.BUILD"
        private const val EXTRA_FINGERPRINT = "org.yasvara.laras.depth.FINGERPRINT"
        const val KEY_ENABLED = "yasvara_depth_enabled"
        const val KEY_CLOCK = "yasvara_depth_clock"
        const val KEY_OPACITY = "yasvara_depth_opacity"
        const val KEY_PARALLAX = "yasvara_depth_parallax"
        private const val COVERAGE_SCALE = 8
        private const val ALPHA_THRESHOLD = 24

        /** The largest power of two that keeps [from] pixels at least [to] after dividing. */
        private fun sampleSize(from: Int, to: Int): Int {
            var sample = 1
            while (from / (sample * 2) >= to) sample *= 2
            return sample
        }

        /** [region] of [decoder]'s image at [outWidth] x [outHeight], as a mutable bitmap. */
        private fun decodeRegion(decoder: BitmapRegionDecoder, region: Rect, outWidth: Int, outHeight: Int): Bitmap {
            try {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(region.width(), outWidth)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val part = decoder.decodeRegion(region, options)
                return scaled(part, Rect(0, 0, part.width, part.height), outWidth, outHeight).also { part.recycle() }
            } finally {
                decoder.recycle()
            }
        }

        private fun scaled(from: Bitmap, src: Rect, outWidth: Int, outHeight: Int): Bitmap {
            val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(from, src, Rect(0, 0, outWidth, outHeight), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
    }
}
