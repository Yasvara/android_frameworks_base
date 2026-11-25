/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara

import android.content.Context
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import kotlin.math.abs

/**
 * Motion parallax for the wallpaper (docs/specs/0001, M4): the wallpaper drifts against the
 * way the phone is tilted, and a depth subject drifts further, so the lock screen reads as
 * layers.
 *
 * One game rotation vector sensor (gyroscope and accelerometer, no magnetometer drift) feeds
 * every listener, and it runs only while a listener is showing, the feature is on, and neither
 * battery saver nor "remove animations" is set. Offsets are in -1..1 on each axis; listeners
 * scale them by [maxShiftPx]. A slow high-pass keeps "straight ahead" at whatever angle the
 * phone is held, so holding it tilted does not leave the wallpaper pushed aside.
 */
@SysUISingleton
class WallpaperParallax
@Inject
constructor(
    @Application private val context: Context,
    private val sensorManager: SensorManager,
    private val secureSettings: SecureSettings,
    private val globalSettings: GlobalSettings,
    private val powerManager: PowerManager,
    private val keyguardStateController: KeyguardStateController,
    private val safeMode: LockSafeMode,
) : SensorEventListener {

    fun interface Listener {
        /** Called on the parallax thread with the new offset. */
        fun onParallax(x: Float, y: Float)
    }

    private val thread = HandlerThread("WallpaperParallax").apply { start() }
    private val handler = Handler(thread.looper)
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private var sensing = false

    @Volatile private var lockEnabled = false
    @Volatile private var homeEnabled = false
    @Volatile private var strengthDp = DEFAULT_STRENGTH_DP

    private val rotation = FloatArray(9)
    private val angles = FloatArray(3)
    private var basePitch = Float.NaN
    private var baseRoll = 0f
    private var x = 0f
    private var y = 0f

    init {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = reload()
        }
        // Registration talks to the settings provider, so it stays off the caller's thread.
        handler.post {
            for (key in listOf(KEY_LOCK, KEY_HOME, KEY_STRENGTH)) {
                secureSettings.registerContentObserverForUserSync(key, observer, UserHandle.USER_ALL)
            }
            globalSettings.registerContentObserverSync(Settings.Global.ANIMATOR_DURATION_SCALE, observer)
            reload()
        }
    }

    /** The largest shift, in pixels, that an offset of 1 stands for. */
    val maxShiftPx: Float
        get() = strengthDp * context.resources.displayMetrics.density

    /** Whether the wallpaper shown on the lock screen ([lock]) or home screen moves. */
    fun isEnabled(lock: Boolean) = !safeMode.isActive && if (lock) lockEnabled else homeEnabled

    /** Whether the one wallpaper shown on both screens is currently on the lock screen. */
    val isLockShowing: Boolean
        get() = keyguardStateController.isShowing

    fun addListener(listener: Listener) {
        listeners += listener
        handler.post { updateSensing() }
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
        handler.post { updateSensing() }
    }

    private fun reload() {
        lockEnabled = secureSettings.getIntForUser(KEY_LOCK, 0, UserHandle.USER_CURRENT) != 0
        homeEnabled = secureSettings.getIntForUser(KEY_HOME, 0, UserHandle.USER_CURRENT) != 0
        strengthDp = secureSettings.getIntForUser(KEY_STRENGTH, DEFAULT_STRENGTH_DP, UserHandle.USER_CURRENT)
            .coerceIn(MIN_STRENGTH_DP, MAX_STRENGTH_DP)
        updateSensing()
        dispatch(if (sensing) x else 0f, if (sensing) y else 0f)
    }

    private fun updateSensing() {
        val animationsOn = globalSettings.getFloat(Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        val want = sensor != null && listeners.isNotEmpty() && (lockEnabled || homeEnabled) &&
            !safeMode.isActive && animationsOn && !powerManager.isPowerSaveMode
        if (want == sensing) return
        sensing = want
        if (want) {
            basePitch = Float.NaN
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        } else {
            sensorManager.unregisterListener(this)
            x = 0f
            y = 0f
            dispatch(0f, 0f)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (powerManager.isPowerSaveMode) {
            updateSensing()
            return
        }
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, angles)
        val pitch = angles[1]
        val roll = angles[2]
        if (basePitch.isNaN()) {
            basePitch = pitch
            baseRoll = roll
        }
        // Follow the way the phone is held, slowly, so only movement shows.
        basePitch += (pitch - basePitch) * DRIFT
        baseRoll += (roll - baseRoll) * DRIFT
        val targetX = ((roll - baseRoll) / MAX_TILT_RAD).coerceIn(-1f, 1f)
        val targetY = ((pitch - basePitch) / MAX_TILT_RAD).coerceIn(-1f, 1f)
        // Smooth out sensor jitter.
        val nx = x + (targetX - x) * SMOOTHING
        val ny = y + (targetY - y) * SMOOTHING
        if (abs(nx - x) < EPSILON && abs(ny - y) < EPSILON) return
        x = nx
        y = ny
        dispatch(x, y)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun dispatch(x: Float, y: Float) {
        listeners.forEach { it.onParallax(x, y) }
    }

    companion object {
        const val KEY_LOCK = "yasvara_parallax_lock"
        const val KEY_HOME = "yasvara_parallax_home"
        const val KEY_STRENGTH = "yasvara_parallax_strength"
        const val DEFAULT_STRENGTH_DP = 18
        const val MIN_STRENGTH_DP = 4
        const val MAX_STRENGTH_DP = 40

        private const val MAX_TILT_RAD = 0.35f
        private const val DRIFT = 0.01f
        private const val SMOOTHING = 0.25f
        private const val EPSILON = 0.001f
    }
}
