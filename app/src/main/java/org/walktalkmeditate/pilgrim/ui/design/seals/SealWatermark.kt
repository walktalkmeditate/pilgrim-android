// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.honor.Way

/** One coordinate of a route the watermark draws, in raw degrees. */
data class SealRoutePoint(val latitude: Double, val longitude: Double)

/**
 * The seal's ghost-route watermark (iOS `drawGhostRoute`,
 * `SealRenderer.swift:113-164@7c200bf`, parity spec G §6): the walk's own
 * line on every seal whose walk has two or more route samples, and on an
 * honor seal the Way's line beneath it.
 *
 * Both lines share one fit, so the drift between the Way and the walk
 * that honored it survives: a square box in raw degrees over both lines
 * together (`span = max(latSpan, lonSpan, 0.0001)`, no cos-latitude
 * correction), its midpoint on the seal's centre, its longer side
 * spanning `2 × 0.7 × 0.44` of the seal, north up before the seal's own
 * rotation turns it. The fit is done once, here: [walkLine] and
 * [wayLine] hold `x, y` pairs in the seal's unit square, so a renderer
 * multiplies by its canvas size and draws.
 *
 * Arrays keep a thousand-walk goshuin book small; equality is by content.
 */
@Immutable
class SealWatermark private constructor(
    val walkLine: FloatArray,
    val wayLine: FloatArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SealWatermark) return false
        return walkLine.contentEquals(other.walkLine) && wayLine.contentEquals(other.wayLine)
    }

    override fun hashCode(): Int = 31 * walkLine.contentHashCode() + wayLine.contentHashCode()

    companion object {
        /** iOS draws the Way's line at 3%, beneath the walk's. */
        const val WAY_ALPHA = 0.03f

        /** iOS draws the walk's line at 5.5%, on top. */
        const val WALK_ALPHA = 0.055f

        /** One unit at iOS's 512 render: 1/512 of the seal at any size. */
        const val STROKE_FRACTION = 1f / 512f

        private const val FIT_RADIUS_FRACTION = 0.7 * 0.44
        private const val MIN_SPAN_DEGREES = 0.0001

        /**
         * Null for a walk with fewer than two route samples, which gets
         * neither line even when its Way exists. A [way] of fewer than two
         * points is dropped.
         */
        fun of(walk: List<SealRoutePoint>, way: List<SealRoutePoint>?): SealWatermark? {
            if (walk.size < 2) return null
            val wayPoints = way?.takeIf { it.size >= 2 }
            val fitted = if (wayPoints == null) walk else walk + wayPoints
            val minLat = fitted.minOf { it.latitude }
            val maxLat = fitted.maxOf { it.latitude }
            val minLon = fitted.minOf { it.longitude }
            val maxLon = fitted.maxOf { it.longitude }
            val span = maxOf(maxLat - minLat, maxLon - minLon, MIN_SPAN_DEGREES)
            val midLat = (minLat + maxLat) / 2
            val midLon = (minLon + maxLon) / 2
            val scale = FIT_RADIUS_FRACTION * 2 / span

            fun unitLine(points: List<SealRoutePoint>): FloatArray {
                val out = FloatArray(points.size * 2)
                points.forEachIndexed { i, point ->
                    out[i * 2] = (0.5 + (point.longitude - midLon) * scale).toFloat()
                    out[i * 2 + 1] = (0.5 - (point.latitude - midLat) * scale).toFloat()
                }
                return out
            }
            return SealWatermark(walkLine = unitLine(walk), wayLine = wayPoints?.let(::unitLine))
        }
    }
}

/**
 * The watermark from every stored route sample of the walk, unsampled,
 * and the route of the Way it honored (null on every other seal, and once
 * the Way's link is gone: owner decision 3, where iOS's cached seal keeps it).
 */
fun sealWatermark(walkRoute: List<LocationPoint>, honoredWay: Way?): SealWatermark? =
    SealWatermark.of(
        walk = walkRoute.map { SealRoutePoint(latitude = it.latitude, longitude = it.longitude) },
        way = honoredWay?.route?.map { SealRoutePoint(latitude = it.lat, longitude = it.lon) },
    )
