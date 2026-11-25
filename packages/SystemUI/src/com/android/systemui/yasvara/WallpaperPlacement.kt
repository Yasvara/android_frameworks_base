/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara

import android.content.Context
import android.graphics.RectF
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where the window manager shows the wallpaper, so a layer drawn by SystemUI (the depth
 * subject) can sit exactly on it (docs/specs/0001, M3).
 *
 * The window manager fits the wallpaper's crop to the display, uses what is left over across
 * for the launcher's page offset, and zooms the whole wallpaper around the display centre:
 * by the maximum zoom at rest and down to the minimum as the shade or bouncer comes in
 * ([com.android.server.wm.WallpaperController.updateWallpaperOffset]). The wallpaper engine
 * reports the page offset and SystemUI's wallpaper controller the zoom.
 */
@SysUISingleton
class WallpaperPlacement @Inject constructor(@Application context: Context) {

    data class State(
        /** The launcher's page offset, 0..1, as the wallpaper engine last got it. */
        val offsetX: Float = 0f,
        val offsetY: Float = 0.5f,
        /** 0 at rest, 1 fully zoomed out. */
        val zoomOut: Float = 0f,
        /**
         * Whether the window manager is showing the lock wallpaper. It is hidden on AOD and
         * comes back a few frames after the lock screen on wake; a layer drawn over it must not
         * show before it does.
         */
        val lockVisible: Boolean = false,
    )

    private val minScale =
        context.resources.getFloat(com.android.internal.R.dimen.config_wallpaperMinScale)
    private val maxScale =
        context.resources.getFloat(com.android.internal.R.dimen.config_wallpaperMaxScale)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setOffsets(x: Float, y: Float) {
        _state.update { it.copy(offsetX = x.coerceIn(0f, 1f), offsetY = y.coerceIn(0f, 1f)) }
    }

    fun setZoomOut(zoomOut: Float) {
        _state.update { it.copy(zoomOut = zoomOut.coerceIn(0f, 1f)) }
    }

    /** Engines drawing the lock wallpaper that are visible; a new one may start before the old ends. */
    private val visibleLockEngines = HashSet<Any>()

    /** Reported by each wallpaper engine that draws the lock wallpaper. */
    fun setLockEngineVisible(engine: Any, visible: Boolean) {
        val shown = synchronized(visibleLockEngines) {
            if (visible) visibleLockEngines += engine else visibleLockEngines -= engine
            visibleLockEngines.isNotEmpty()
        }
        _state.update { it.copy(lockVisible = shown) }
    }

    /** The zoom the window manager applies around the display centre. */
    fun scale(state: State): Float = minScale + (maxScale - minScale) * (1f - state.zoomOut)

    /**
     * Where a crop of the wallpaper [frameWidth] x [frameHeight] lands on a [width] x [height]
     * display before the zoom, into [out].
     */
    fun frameOnDisplay(
        state: State,
        frameWidth: Float,
        frameHeight: Float,
        width: Float,
        height: Float,
        out: RectF,
    ) {
        val fill = maxOf(width / frameWidth, height / frameHeight)
        val w = frameWidth * fill
        val h = frameHeight * fill
        val left = -(w - width) * state.offsetX
        val top = -(h - height) * state.offsetY
        out.set(left, top, left + w, top + h)
    }
}
