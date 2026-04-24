// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import kotlin.math.max
import kotlin.math.min
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Stage 7-C: pure geometry for the etegami route stroke.
 * Equirectangular projection → Catmull-Rom smoothing → taper.
 *
 * All math lives outside the draw pipeline so it can be asserted via
 * plain JVM tests (Robolectric's Canvas backend is a stub — Stage 3-C
 * lesson). The renderer picks up a `List<SmoothedSegment>` and calls
 * `drawPath` per segment with the per-segment opacity + width.
 */
internal data class SmoothedSegment(
    val x: Float,
    val y: Float,
    /**
     * [0.15, 1.0] ramping in from each end; 1.0 in the middle of the
     * route. Applied to per-segment opacity so the ends fade in/out.
     */
    val taper: Float,
    /**
     * Multiplier on the base stroke width (default 1.0). Reserved for a
     * future altitude-aware width pass; uniform for 7-C MVP.
     */
    val widthMultiplier: Float = 1.0f,
)

internal object EtegamiRouteGeometry {

    /** Render area matches iOS. Internal coordinate basis is the 1080×1920 canvas. */
    const val AREA_OFFSET_X = 100
    const val AREA_OFFSET_TOP = 200
    const val AREA_WIDTH = 880
    const val AREA_HEIGHT = 900
    const val VERTICAL_AVAILABLE_TOP = 120f
    const val VERTICAL_AVAILABLE_BOTTOM = 1280f
    const val DEFAULT_SUBDIVISIONS = 8

    /**
     * Short-circuits on `points.size < 2` (empty result). Otherwise
     * projects lat/lon equirectangularly into the area rect, applies
     * Catmull-Rom with [subdivisions] per original segment, recenters
     * vertically to the middle of the available slot, and stamps a
     * taper coefficient into each point.
     */
    fun smooth(
        points: List<LocationPoint>,
        subdivisions: Int = DEFAULT_SUBDIVISIONS,
    ): List<SmoothedSegment> {
        if (points.size < 2) return emptyList()
        val projected = project(points)
        val smoothed = catmullRom(projected, subdivisions)
        val recentered = verticalRecenter(smoothed)
        return withTaper(recentered)
    }

    private fun project(points: List<LocationPoint>): List<Pair<Float, Float>> {
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val lonSpan = (maxLon - minLon).coerceAtLeast(1e-9)
        val latSpan = (maxLat - minLat).coerceAtLeast(1e-9)
        // Aspect-preserving fit inside AREA_WIDTH × AREA_HEIGHT, centered.
        val scale = min(AREA_WIDTH / lonSpan, AREA_HEIGHT / latSpan).toFloat()
        val projectedWidth = (lonSpan * scale).toFloat()
        val projectedHeight = (latSpan * scale).toFloat()
        val xOffset = AREA_OFFSET_X + (AREA_WIDTH - projectedWidth) / 2f
        val yOffset = AREA_OFFSET_TOP + (AREA_HEIGHT - projectedHeight) / 2f
        return points.map { p ->
            // lat → y inverted (higher latitude → lower y on canvas)
            val x = xOffset + ((p.longitude - minLon) * scale).toFloat()
            val y = yOffset + ((maxLat - p.latitude) * scale).toFloat()
            x to y
        }
    }

    private fun catmullRom(
        points: List<Pair<Float, Float>>,
        subdivisions: Int,
    ): List<Pair<Float, Float>> {
        if (points.size < 2) return points
        // Extend endpoints so the first and last segments produce
        // the same smoothing as interior segments (p0 at index -1 and
        // pN+1 at index N become copies of the real endpoints).
        val p = buildList {
            add(points.first())
            addAll(points)
            add(points.last())
        }
        val out = ArrayList<Pair<Float, Float>>((points.size - 1) * subdivisions + 1)
        for (i in 1 until p.size - 2) {
            val p0 = p[i - 1]
            val p1 = p[i]
            val p2 = p[i + 1]
            val p3 = p[i + 2]
            for (step in 0 until subdivisions) {
                val t = step.toFloat() / subdivisions
                val t2 = t * t
                val t3 = t2 * t
                val x = 0.5f * (
                    2f * p1.first +
                        (-p0.first + p2.first) * t +
                        (2f * p0.first - 5f * p1.first + 4f * p2.first - p3.first) * t2 +
                        (-p0.first + 3f * p1.first - 3f * p2.first + p3.first) * t3
                    )
                val y = 0.5f * (
                    2f * p1.second +
                        (-p0.second + p2.second) * t +
                        (2f * p0.second - 5f * p1.second + 4f * p2.second - p3.second) * t2 +
                        (-p0.second + 3f * p1.second - 3f * p2.second + p3.second) * t3
                    )
                out += x to y
            }
        }
        out += points.last()
        return out
    }

    private fun verticalRecenter(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.isEmpty()) return points
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        val midY = (minY + maxY) / 2f
        val targetMidY = (VERTICAL_AVAILABLE_TOP + VERTICAL_AVAILABLE_BOTTOM) / 2f
        val dy = targetMidY - midY
        return points.map { (x, y) -> x to (y + dy) }
    }

    private fun withTaper(points: List<Pair<Float, Float>>): List<SmoothedSegment> {
        if (points.isEmpty()) return emptyList()
        val n = points.size
        val taperZone = max((n * 0.1f).toInt(), 1)
        return points.mapIndexed { i, (x, y) ->
            val taper = when {
                i < taperZone -> 0.15f + (1f - 0.15f) * (i.toFloat() / taperZone)
                i >= n - taperZone -> {
                    val k = (n - 1 - i).toFloat() / taperZone
                    0.15f + (1f - 0.15f) * k
                }
                else -> 1f
            }.coerceIn(0.15f, 1f)
            SmoothedSegment(x = x, y = y, taper = taper)
        }
    }
}
