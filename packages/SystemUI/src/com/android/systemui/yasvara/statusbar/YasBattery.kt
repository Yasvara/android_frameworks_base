/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.yasvara.statusbar

import android.animation.ValueAnimator
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import com.android.systemui.statusbar.pipeline.battery.data.repository.BatteryRepository
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryColors
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryFrame
import com.android.systemui.statusbar.pipeline.battery.shared.ui.BatteryGlyph
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.BatteryViewModel.Companion.glyphRepresentation
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Yasvara's battery styles. They are stored in Lineage's
 * `status_bar_battery_style` from 100 up, so a future Lineage style cannot collide with them and a
 * SystemUI that does not know them draws the stock icon.
 */
object YasBatteryStyles {
    /** A landscape pill filled to the level, with the percentage inside. */
    const val CAPSULE = 100

    /** Five blocks; the lit count is the level in fifths. */
    const val SEGMENTS = 101

    /** A thick ring with a gap, wavy while charging. */
    const val RING = 102

    /** The percentage alone, with the charging bolt or saver plus in front of it. */
    const val GLYPH = 103

    fun isYasvara(style: Int) = style in CAPSULE..GLYPH

    /**
     * The percentage mode the status bar follows with [style], from the user's [setting]: Capsule
     * and Glyph draw the percentage as the icon itself, and Segments have no room for it inside,
     * so "inside" puts it beside them.
     */
    fun percentMode(style: Int, setting: Int): Int =
        when (style) {
            CAPSULE,
            GLYPH -> BatteryRepository.SHOW_PERCENT_HIDDEN
            SEGMENTS ->
                if (setting == BatteryRepository.SHOW_PERCENT_INSIDE) {
                    BatteryRepository.SHOW_PERCENT_NEXT_TO
                } else {
                    setting
                }
            else -> setting
        }
}

/**
 * Draws a Yasvara battery [style] at the height it is given, in the same colours as the stock
 * battery ([BatteryColors]: the area's dark or light profile, green while charging, amber in
 * saver, red when critical), so it follows the status bar tint like every other icon. Each state
 * also has a shape of its own (the bolt, the plus, the segment count), never colour alone.
 */
@Composable
fun YasBattery(
    style: Int,
    attribution: BatteryGlyph?,
    levelProvider: () -> Int?,
    showLevelProvider: () -> Boolean,
    colorsProvider: () -> BatteryColors,
    modifier: Modifier,
    contentDescription: String,
) {
    val wave = remember { mutableFloatStateOf(0f) }
    if (style == YasBatteryStyles.RING && attribution == BatteryGlyph.Bolt) {
        WaveClock(wave)
    }
    Canvas(
        modifier =
            modifier
                .layout { measurable, constraints ->
                    val height =
                        if (constraints.hasBoundedHeight) constraints.maxHeight
                        else BatteryFrame.innerHeight.times(density).roundToInt()
                    val units = height / UNIT_HEIGHT
                    val width =
                        (widthInUnits(style, attribution, levelProvider()) * units).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(width, height) { placeable.place(0, 0) }
                }
                // Knocking glyph outlines out of the fill needs a layer of its own.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        contentDescription = contentDescription,
    ) {
        val units = size.height / UNIT_HEIGHT
        val level = levelProvider()
        val colors = colorsProvider()
        scale(units, pivot = Offset.Zero) {
            when (style) {
                YasBatteryStyles.CAPSULE -> drawCapsule(level, attribution, colors)
                YasBatteryStyles.SEGMENTS -> drawSegments(level, attribution, colors)
                YasBatteryStyles.RING ->
                    drawRing(level, attribution, showLevelProvider(), colors, wave.floatValue)
                else -> drawGlyphs(level, attribution, colors)
            }
        }
    }
}

/** Width of [style] in units, for a height of [UNIT_HEIGHT]. */
private fun widthInUnits(style: Int, attribution: BatteryGlyph?, level: Int?): Float =
    when (style) {
        YasBatteryStyles.CAPSULE -> CAPSULE_WIDTH
        YasBatteryStyles.SEGMENTS -> SEGMENTS_WIDTH + CAP_GAP + CAP_WIDTH
        YasBatteryStyles.RING -> UNIT_HEIGHT
        else ->
            glyphRowWidth(glyphsOf(level, attribution), GLYPH_HEIGHT) +
                if (level == null) 0f else PERCENT_GAP + PERCENT_WIDTH * GLYPH_HEIGHT / DIGIT_HEIGHT
    }

private fun DrawScope.drawCapsule(level: Int?, attribution: BatteryGlyph?, colors: BatteryColors) {
    val pill = Path().apply {
        addRoundRect(RoundRect(0f, 0f, CAPSULE_WIDTH, UNIT_HEIGHT, CornerRadius(UNIT_HEIGHT / 2)))
    }
    drawPath(pill, colors.backgroundWithGlyph)
    if (level != null && level > 0) {
        val filled = CAPSULE_WIDTH * level.coerceAtMost(100) / 100f
        val rtl = layoutDirection == LayoutDirection.Rtl
        clipPath(pill) {
            clipRect(
                left = if (rtl) CAPSULE_WIDTH - filled else 0f,
                right = if (rtl) CAPSULE_WIDTH else filled,
            ) {
                drawRect(colors.fill, size = Size(CAPSULE_WIDTH, UNIT_HEIGHT))
            }
        }
    }
    drawGlyphRow(
        glyphs = glyphsOf(level, attribution).map { it to colors.glyph },
        center = Offset(CAPSULE_WIDTH / 2, UNIT_HEIGHT / 2),
        height = CAPSULE_TEXT_HEIGHT,
        maxWidth = CAPSULE_WIDTH - 2 * CAPSULE_PADDING,
    )
}

private fun DrawScope.drawSegments(level: Int?, attribution: BatteryGlyph?, colors: BatteryColors) {
    val lit = if (level == null) 0 else ceil(level.coerceIn(0, 100) / 20f).toInt()
    val width = (SEGMENTS_WIDTH - (SEGMENT_COUNT - 1) * SEGMENT_GAP) / SEGMENT_COUNT
    val rtl = layoutDirection == LayoutDirection.Rtl
    val bodyLeft = if (rtl) CAP_WIDTH + CAP_GAP else 0f
    for (i in 0 until SEGMENT_COUNT) {
        // Segments fill from the side away from the cap.
        val slot = if (rtl) SEGMENT_COUNT - 1 - i else i
        val left = bodyLeft + slot * (width + SEGMENT_GAP)
        val first = slot == 0
        val last = slot == SEGMENT_COUNT - 1
        val outer = CornerRadius(SEGMENT_OUTER_RADIUS)
        val inner = CornerRadius(SEGMENT_INNER_RADIUS)
        drawPath(
            Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left,
                        top = 0f,
                        right = left + width,
                        bottom = UNIT_HEIGHT,
                        topLeftCornerRadius = if (first) outer else inner,
                        bottomLeftCornerRadius = if (first) outer else inner,
                        topRightCornerRadius = if (last) outer else inner,
                        bottomRightCornerRadius = if (last) outer else inner,
                    )
                )
            },
            if (i < lit) colors.fill else colors.backgroundOnly,
        )
    }
    val capLeft = if (rtl) 0f else SEGMENTS_WIDTH + CAP_GAP
    val cap = BatteryFrame.capPathSpec
    translate(capLeft, (UNIT_HEIGHT - cap.viewportHeight.value) / 2) {
        scale(scaleX = if (rtl) -1f else 1f, scaleY = 1f, pivot = Offset(CAP_WIDTH / 2, 0f)) {
            drawPath(cap.path, if (lit == SEGMENT_COUNT) colors.fill else colors.backgroundOnly)
        }
    }
    attribution?.let {
        drawKnockedOut(it, Offset(bodyLeft + SEGMENTS_WIDTH / 2, UNIT_HEIGHT / 2), 9f, colors.attribution)
    }
}

private fun DrawScope.drawRing(
    level: Int?,
    attribution: BatteryGlyph?,
    showLevel: Boolean,
    colors: BatteryColors,
    wavePhase: Float,
) {
    val center = Offset(UNIT_HEIGHT / 2, UNIT_HEIGHT / 2)
    val radius = (UNIT_HEIGHT - RING_STROKE) / 2 - RING_WAVE_AMPLITUDE
    val circumference = 2 * PI.toFloat() * radius
    val capDegrees = RING_STROKE / 2 / circumference * 360f
    val gapDegrees = RING_GAP / circumference * 360f
    val progress = (level ?: 0).coerceIn(0, 100) / 100f
    val wavy = attribution == BatteryGlyph.Bolt
    val stroke = Stroke(RING_STROKE, cap = StrokeCap.Round)

    fun arc(color: Color, from: Float, sweep: Float, wave: Boolean) {
        if (sweep <= 0f) return
        if (!wave) {
            drawArc(
                color, from, sweep, useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = stroke,
            )
            return
        }
        val path = Path()
        val steps = (sweep / RING_WAVE_STEP_DEGREES).toInt().coerceAtLeast(1)
        for (step in 0..steps) {
            val degrees = from + sweep * step / steps
            val radians = degrees / 180f * PI.toFloat()
            val r = radius +
                RING_WAVE_AMPLITUDE * sin(RING_WAVES * radians - 2 * PI.toFloat() * wavePhase)
            val point = Offset(center.x + r * cos(radians), center.y + r * sin(radians))
            if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, color, style = stroke)
    }

    when {
        progress >= 1f -> arc(colors.fill, -90f, 360f, wavy)
        progress <= 0f -> arc(colors.backgroundOnly, -90f, 360f, wave = false)
        else -> {
            val sweep = 360f * progress
            // A dot at least, so a nearly empty battery still shows where it starts.
            arc(colors.fill, -90f + capDegrees, (sweep - 2 * capDegrees).coerceAtLeast(0.01f), wavy)
            arc(
                colors.backgroundOnly,
                -90f + sweep + gapDegrees + capDegrees,
                360f - sweep - 2 * gapDegrees - 2 * capDegrees,
                wave = false,
            )
        }
    }

    val inner = 2 * (radius - RING_STROKE / 2)
    when {
        attribution != null ->
            drawGlyphRow(listOf(attribution to colors.attribution), center, inner * 0.72f, inner)
        showLevel && level != null && level < 100 ->
            drawGlyphRow(
                level.glyphRepresentation().map { it to colors.attribution },
                center,
                inner * 0.62f,
                inner * 0.92f,
            )
    }
}

private fun DrawScope.drawGlyphs(level: Int?, attribution: BatteryGlyph?, colors: BatteryColors) {
    // The level keeps the plain icon colour while a bolt or plus explains the state, and turns
    // the state colour (red when critical) when it stands alone.
    val digitColor = if (attribution == null) colors.fill else colors.attribution
    val glyphs = glyphsOf(level, attribution).map { glyph ->
        glyph to if (glyph == attribution) colors.fill else digitColor
    }
    val rowWidth = glyphRowWidth(glyphs.map { it.first }, GLYPH_HEIGHT)
    drawGlyphRow(
        glyphs,
        Offset(rowWidth / 2, UNIT_HEIGHT / 2),
        GLYPH_HEIGHT,
        maxWidth = Float.MAX_VALUE,
    )
    if (level != null) {
        val scale = GLYPH_HEIGHT / DIGIT_HEIGHT
        translate(rowWidth + PERCENT_GAP, (UNIT_HEIGHT - GLYPH_HEIGHT) / 2) {
            drawPercent(digitColor, scale)
        }
    }
}

/** A percent sign in the proportions of the battery digits, [scale]d from a 9-unit height. */
private fun DrawScope.drawPercent(color: Color, scale: Float) {
    val stroke = Stroke(PERCENT_STROKE * scale)
    val ring = 1.35f * scale
    drawCircle(color, ring, Offset(1.6f * scale, 2.0f * scale), style = stroke)
    drawCircle(color, ring, Offset((PERCENT_WIDTH - 1.6f) * scale, 7.0f * scale), style = stroke)
    drawLine(
        color,
        Offset((PERCENT_WIDTH - 0.6f) * scale, 0.4f * scale),
        Offset(0.6f * scale, 8.6f * scale),
        strokeWidth = PERCENT_STROKE * scale,
        cap = StrokeCap.Round,
    )
}

/** The glyphs for [level], after the [attribution] if any; a question mark when unknown. */
private fun glyphsOf(level: Int?, attribution: BatteryGlyph?): List<BatteryGlyph> =
    when {
        level == null -> listOf(BatteryGlyph.Question)
        attribution == null -> level.glyphRepresentation()
        else -> listOf(attribution) + level.glyphRepresentation()
    }

private fun glyphRowWidth(glyphs: List<BatteryGlyph>, height: Float): Float {
    if (glyphs.isEmpty()) return 0f
    val scale = height / DIGIT_HEIGHT
    return (glyphs.sumOf { it.width.toDouble() }.toFloat() + GLYPH_SPACING * (glyphs.size - 1)) *
        scale
}

/**
 * Draws [glyphs] in a row centred on [center], scaled so a digit is [height] tall, or smaller
 * when the row would be wider than [maxWidth].
 */
private fun DrawScope.drawGlyphRow(
    glyphs: List<Pair<BatteryGlyph, Color>>,
    center: Offset,
    height: Float,
    maxWidth: Float,
) {
    if (glyphs.isEmpty()) return
    val natural = glyphRowWidth(glyphs.map { it.first }, height)
    val fit = if (natural > maxWidth) maxWidth / natural else 1f
    val scale = height / DIGIT_HEIGHT * fit
    var x = center.x - natural * fit / 2
    for ((glyph, color) in glyphs) {
        translate(x, center.y - glyph.height * scale / 2) {
            scale(scale, pivot = Offset.Zero) { drawPath(glyph.path, color) }
        }
        x += (glyph.width + GLYPH_SPACING) * scale
    }
}

/** Draws [glyph] centred on [center], cut out of what is below it so it reads on any fill. */
private fun DrawScope.drawKnockedOut(glyph: BatteryGlyph, center: Offset, height: Float, color: Color) {
    val scale = height / glyph.height
    translate(center.x - glyph.width * scale / 2, center.y - height / 2) {
        scale(scale, pivot = Offset.Zero) {
            drawPath(glyph.path, Color.Black, style = Stroke(KNOCKOUT_STROKE), blendMode = BlendMode.Clear)
            drawPath(glyph.path, color)
        }
    }
}

/**
 * Advances [phase] ten times a second while the display is on, and not at all while it is off
 * or animations are turned off: the wave is decoration, never worth a frame the screen cannot
 * show.
 */
@Composable
private fun WaveClock(phase: MutableFloatState) {
    val view = LocalView.current
    LaunchedEffect(view) {
        if (!ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
        displayOn(view).collectLatest { on ->
            while (on) {
                delay(WAVE_FRAME_MS)
                withFrameMillis { now -> phase.floatValue = (now % WAVE_PERIOD_MS) / WAVE_PERIOD_MS.toFloat() }
            }
        }
    }
}

/** Whether [view]'s display is on, over time. */
private fun displayOn(view: View): Flow<Boolean> =
    callbackFlow {
            val displays = view.context.getSystemService(DisplayManager::class.java)
            val displayId = view.display?.displayId ?: Display.DEFAULT_DISPLAY
            fun send() {
                trySend(displays.getDisplay(displayId)?.state == Display.STATE_ON)
            }
            val listener =
                object : DisplayManager.DisplayListener {
                    override fun onDisplayChanged(id: Int) {
                        if (id == displayId) send()
                    }

                    override fun onDisplayAdded(id: Int) {}

                    override fun onDisplayRemoved(id: Int) {}
                }
            displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            send()
            awaitClose { displays.unregisterDisplayListener(listener) }
        }
        .distinctUntilChanged()

/** Every style is drawn on a canvas this many units tall, like the stock battery. */
private const val UNIT_HEIGHT = 13f

/** Height of the battery digit glyphs as drawn by [BatteryGlyph]. */
private const val DIGIT_HEIGHT = 9f
private const val GLYPH_SPACING = 0.8f
private const val KNOCKOUT_STROKE = 2f

private const val CAPSULE_WIDTH = 27f
private const val CAPSULE_PADDING = 3f
private const val CAPSULE_TEXT_HEIGHT = 8.4f

private const val SEGMENT_COUNT = 5
private const val SEGMENTS_WIDTH = 24f
private const val SEGMENT_GAP = 1.3f
private const val SEGMENT_OUTER_RADIUS = 3.5f
private const val SEGMENT_INNER_RADIUS = 1.2f
private const val CAP_GAP = 1f
private const val CAP_WIDTH = 1.5f

private const val RING_STROKE = 2.2f
private const val RING_GAP = 1.2f
private const val RING_WAVE_AMPLITUDE = 0.45f
private const val RING_WAVES = 9
private const val RING_WAVE_STEP_DEGREES = 3f
private const val WAVE_FRAME_MS = 100L
private const val WAVE_PERIOD_MS = 1600L

private const val GLYPH_HEIGHT = 9.4f
private const val PERCENT_WIDTH = 6f
private const val PERCENT_GAP = 1.2f
private const val PERCENT_STROKE = 1.25f
