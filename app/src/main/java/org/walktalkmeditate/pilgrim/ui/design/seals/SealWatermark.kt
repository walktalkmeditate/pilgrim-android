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
 * The box is fitted over every sample, but each line keeps only the
 * vertices it needs to stay within [SIMPLIFY_TOLERANCE] of all of them
 * (Douglas-Peucker): under half a pixel at a 1,024 px render, which no
 * seal reaches (the 220 dp reveal is 880 px at 4x, the share 512 px), so
 * the stroke draws as the full line would. That and the arrays keep a
 * thousand-walk goshuin book small; equality is by content.
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

        /** How far a kept line may stray from the samples, in seal widths. */
        const val SIMPLIFY_TOLERANCE = 1f / 2048f

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
            return SealWatermark(
                walkLine = simplified(unitLine(walk)),
                wayLine = wayPoints?.let { simplified(unitLine(it)) },
            )
        }

        /**
         * Douglas-Peucker over `x, y` pairs: the first and last points stay,
         * and a point between stays when it lies farther than
         * [SIMPLIFY_TOLERANCE] from the segment joining its kept neighbours.
         */
        internal fun simplified(line: FloatArray): FloatArray {
            val count = line.size / 2
            if (count <= 2) return line
            val keep = BooleanArray(count)
            keep[0] = true
            keep[count - 1] = true
            val toleranceSquared = SIMPLIFY_TOLERANCE.toDouble() * SIMPLIFY_TOLERANCE
            val spans = ArrayDeque<Pair<Int, Int>>()
            spans.addLast(0 to count - 1)
            while (spans.isNotEmpty()) {
                val (first, last) = spans.removeLast()
                var farthest = -1
                var farthestSquared = toleranceSquared
                for (i in first + 1 until last) {
                    val d = distanceSquaredToSegment(line, i, first, last)
                    if (d > farthestSquared) {
                        farthest = i
                        farthestSquared = d
                    }
                }
                if (farthest < 0) continue
                keep[farthest] = true
                spans.addLast(first to farthest)
                spans.addLast(farthest to last)
            }
            val out = FloatArray(keep.count { it } * 2)
            var j = 0
            for (i in 0 until count) {
                if (!keep[i]) continue
                out[j++] = line[i * 2]
                out[j++] = line[i * 2 + 1]
            }
            return out
        }

        private fun distanceSquaredToSegment(line: FloatArray, point: Int, first: Int, last: Int): Double {
            val px = line[point * 2].toDouble()
            val py = line[point * 2 + 1].toDouble()
            val ax = line[first * 2].toDouble()
            val ay = line[first * 2 + 1].toDouble()
            val dx = line[last * 2] - ax
            val dy = line[last * 2 + 1] - ay
            val lengthSquared = dx * dx + dy * dy
            val t = if (lengthSquared == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val ex = px - (ax + t * dx)
            val ey = py - (ay + t * dy)
            return ex * ex + ey * ey
        }
    }
}

/**
 * The watermark fitted over every stored route sample of the walk, and
 * the route of the Way it honored (null on every other seal, and once
 * the Way's link is gone: owner decision 3, where iOS's cached seal keeps it).
 */
fun sealWatermark(walkRoute: List<LocationPoint>, honoredWay: Way?): SealWatermark? =
    SealWatermark.of(
        walk = walkRoute.map { SealRoutePoint(latitude = it.latitude, longitude = it.longitude) },
        way = honoredWay?.route?.map { SealRoutePoint(latitude = it.lat, longitude = it.lon) },
    )
