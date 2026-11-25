/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.yasvara.clocks.designs

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.pill
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/**
 * Material 3 Expressive shapes, all of radius 1 around the origin, the same family the Yasvara
 * logo is drawn from. Morphs between them are cached, since building one is not cheap.
 */
internal object M3Shapes {
    val circle = RoundedPolygon.circle(numVertices = 8)
    val square = RoundedPolygon(4, radius = 1.3f, rounding = CornerRounding(0.35f))
    val pill = RoundedPolygon.pill(width = 2f, height = 1.3f)
    val cookie4 = RoundedPolygon.star(4, radius = 1f, innerRadius = 0.72f, rounding = CornerRounding(0.35f))
    val cookie6 = RoundedPolygon.star(6, radius = 1f, innerRadius = 0.8f, rounding = CornerRounding(0.2f))
    val cookie9 = RoundedPolygon.star(9, radius = 1f, innerRadius = 0.86f, rounding = CornerRounding(0.12f))
    val clover = RoundedPolygon.star(4, radius = 1f, innerRadius = 0.4f, rounding = CornerRounding(0.45f))
    val burst = RoundedPolygon.star(12, radius = 1f, innerRadius = 0.78f, rounding = CornerRounding(0.08f))

    /** The shapes a face cycles through, by digit. */
    val cycle = listOf(cookie9, clover, circle, cookie4, square, burst, cookie6, pill, clover, cookie4)

    private val morphs = HashMap<Pair<RoundedPolygon, RoundedPolygon>, Morph>()
    private val path = Path()

    fun morph(from: RoundedPolygon, to: RoundedPolygon): Morph =
        morphs.getOrPut(from to to) { Morph(from, to) }

    /** Draws [shape], or the way from [from] to it at [progress], centred on cx, cy. */
    fun draw(
        canvas: Canvas,
        shape: RoundedPolygon,
        from: RoundedPolygon?,
        progress: Float,
        cx: Float,
        cy: Float,
        radius: Float,
        paint: Paint,
        rotation: Float = 0f,
    ) {
        path.rewind()
        if (from != null && from !== shape && progress < 1f) {
            morph(from, shape).toPath(progress, path)
        } else {
            shape.toPath(path)
        }
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotation)
        canvas.scale(radius, radius)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
