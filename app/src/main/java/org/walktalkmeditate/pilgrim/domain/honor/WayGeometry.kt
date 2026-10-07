// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The only place Honor does geometry: a port of iOS
 * `Pilgrim/Models/Honor/WayGeometry.swift@7c200bf`. Cumulative haversine
 * distances over the Way's route; every other consumer talks in fracs
 * (0..1 of the total length) or seconds since departure.
 *
 * Two distance models, as on iOS (parity spec A §6, §8): lengths are
 * haversine at 6,371,000 m ([distanceMeters]); projecting a point onto the
 * line ([nearest], [lowestFrac]) is a local equirectangular plane,
 * 111,320 m per degree with longitude scaled by the query's latitude.
 * A stage's tile corridor ([corridor], [simplified]) has its own local
 * metres, from the line's first point, and [ringContains] works in raw
 * degrees (spec D C1 §2–§4).
 */
class WayGeometry(val points: List<WayPoint>) {

    /** `cumulative[i]` is the metres from the first point to point `i`. */
    val cumulative: List<Double>
    val totalMeters: Double
    val totalSeconds: Double

    init {
        var running = 0.0
        val cumulativeMeters = ArrayList<Double>(points.size)
        points.forEachIndexed { index, point ->
            if (index > 0) running += distanceMeters(points[index - 1], point)
            cumulativeMeters += running
        }
        cumulative = cumulativeMeters
        totalMeters = running
        totalSeconds = points.lastOrNull()?.let { it.t - points.first().t } ?: 0.0
    }

    /** A frac and the metres from the query to the line there. */
    data class Projection(val frac: Double, val meters: Double)

    private val hasLength: Boolean get() = points.size > 1 && totalMeters > 0

    /** Linear in degrees between the segment's ends. Empty route: (0, 0); no length: the first point. */
    fun coordinate(atFrac: Double): WayCoordinate {
        val first = points.firstOrNull() ?: return WayCoordinate(lat = 0.0, lon = 0.0)
        if (!hasLength) return WayCoordinate(lat = first.lat, lon = first.lon)
        val (i, u) = segment(atDistance = atFrac.coerceIn(0.0, 1.0) * totalMeters)
        val a = points[i]
        val b = points[i + 1]
        return WayCoordinate(lat = a.lat + (b.lat - a.lat) * u, lon = a.lon + (b.lon - a.lon) * u)
    }

    /**
     * The polyline between two fracs: an interpolated point at each end and
     * every route point strictly inside, so consecutive slices share their
     * boundary coordinate and draw as one line. Reversed fracs are swapped.
     */
    fun slice(fromFrac: Double, toFrac: Double): List<WayCoordinate> {
        if (!hasLength) return points.map { WayCoordinate(lat = it.lat, lon = it.lon) }
        val a = min(fromFrac, toFrac).coerceIn(0.0, 1.0) * totalMeters
        val b = max(fromFrac, toFrac).coerceIn(0.0, 1.0) * totalMeters
        val coordinates = mutableListOf(coordinate(atFrac = a / totalMeters))
        points.forEachIndexed { index, point ->
            if (cumulative[index] > a && cumulative[index] < b) {
                coordinates += WayCoordinate(lat = point.lat, lon = point.lon)
            }
        }
        coordinates += coordinate(atFrac = b / totalMeters)
        return coordinates
    }

    /**
     * On a stationary plateau, the moment the walker moved on (the end of
     * the pause), so a companion anchored at a rest departs with the
     * honoring walker. No length: 0.
     */
    fun elapsed(atFrac: Double): Double {
        if (!hasLength) return 0.0
        val (i, u) = segment(atDistance = atFrac.coerceIn(0.0, 1.0) * totalMeters)
        val a = points[i]
        val b = points[i + 1]
        return (a.t + (b.t - a.t) * u) - points[0].t
    }

    /**
     * Holds the frac through a pause. The first segment whose closed time
     * range holds [atElapsed] wins, so a vertex time resolves on the segment
     * ending there. No length: 1, checked before the `<= 0` rule
     * (`WayGeometry.swift:74-88@7c200bf`).
     */
    fun frac(atElapsed: Double): Double {
        if (!hasLength) return 1.0
        val t0 = points[0].t
        if (atElapsed <= 0) return 0.0
        if (atElapsed >= totalSeconds) return 1.0
        for (i in 0 until points.size - 1) {
            val ta = points[i].t - t0
            val tb = points[i + 1].t - t0
            if (atElapsed >= ta && atElapsed <= tb) {
                val u = if (tb > ta) (atElapsed - ta) / (tb - ta) else 0.0
                val d = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * u
                return d / totalMeters
            }
        }
        return 1.0
    }

    /**
     * Closest point on the line to [to], restricted to the part of the line
     * inside [within] when given. A window never leaks into a neighbouring
     * segment through a shared endpoint. Ties keep the lower segment. Empty
     * route, or a window that holds no segment: (0, +∞). No length: frac 0
     * and the haversine distance to the first point.
     */
    fun nearest(to: WayCoordinate, within: ClosedFloatingPointRange<Double>?): Projection {
        val first = points.firstOrNull() ?: return Projection(frac = 0.0, meters = Double.POSITIVE_INFINITY)
        if (!hasLength) {
            val query = WayPoint(lat = to.lat, lon = to.lon, alt = null, t = 0.0)
            return Projection(frac = 0.0, meters = distanceMeters(first, query))
        }
        var best = Projection(frac = 0.0, meters = Double.POSITIVE_INFINITY)
        for (i in 0 until points.size - 1) {
            val fa = cumulative[i] / totalMeters
            val fb = cumulative[i + 1] / totalMeters
            if (within != null && (fb < within.start || fa > within.endInclusive)) continue
            var uLo = 0.0
            var uHi = 1.0
            if (within != null && fb > fa) {
                uLo = max(0.0, (within.start - fa) / (fb - fa))
                uHi = min(1.0, (within.endInclusive - fa) / (fb - fa))
                if (uLo > uHi) continue
            }
            val hit = nearest(onSegment = i, to = to, uLo = uLo, uHi = uHi)
            if (hit.meters < best.meters) best = hit
        }
        return best
    }

    /**
     * The smallest frac at or beyond [fromFrac] whose segment passes within
     * [withinMeters] of [of]: the anchor for a walker who starts mid-Way,
     * and the re-acquire target. Only the first contiguous run of segments
     * in range is scanned and its closest point returned; a later, closer
     * run is never considered, which is what keeps the outbound leg of an
     * out-and-back. Null when nothing is near.
     */
    fun lowestFrac(withinMeters: Double, of: WayCoordinate, fromFrac: Double = 0.0): Projection? {
        if (!hasLength) {
            val hit = nearest(to = of, within = null)
            return if (hit.meters <= withinMeters) Projection(frac = 0.0, meters = hit.meters) else null
        }
        var best: Projection? = null
        for (i in 0 until points.size - 1) {
            val fa = cumulative[i] / totalMeters
            val fb = cumulative[i + 1] / totalMeters
            if (fb < fromFrac) continue
            val uLo = if (fb > fa) min(1.0, max(0.0, (fromFrac - fa) / (fb - fa))) else 0.0
            val hit = nearest(onSegment = i, to = of, uLo = uLo, uHi = 1.0)
            if (hit.meters <= withinMeters) {
                if (best == null || hit.meters < best.meters) best = hit
            } else if (best != null) {
                break
            }
        }
        return best
    }

    private fun nearest(onSegment: Int, to: WayCoordinate, uLo: Double, uHi: Double): Projection {
        val fa = cumulative[onSegment] / totalMeters
        val fb = cumulative[onSegment + 1] / totalMeters
        val cosLat = cos(to.lat * Math.PI / 180)
        val a = points[onSegment]
        val b = points[onSegment + 1]
        val ax = (a.lon - to.lon) * cosLat
        val ay = a.lat - to.lat
        val bx = (b.lon - to.lon) * cosLat
        val by = b.lat - to.lat
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        val raw = if (lengthSq > 0) -(ax * dx + ay * dy) / lengthSq else 0.0
        val u = min(max(raw, uLo), uHi)
        val px = ax + dx * u
        val py = ay + dy * u
        return Projection(frac = fa + (fb - fa) * u, meters = sqrt(px * px + py * py) * METERS_PER_DEGREE)
    }

    /**
     * The segment holding distance [atDistance], by binary search. The `<=`
     * lands on the last of several equal cumulative distances (a plateau),
     * which is what makes [elapsed] return the end of a pause
     * (`WayGeometry.swift:148-157@7c200bf`).
     */
    private fun segment(atDistance: Double): Pair<Int, Double> {
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= atDistance) lo = mid else hi = mid
        }
        val span = cumulative[hi] - cumulative[lo]
        val u = if (span > 0) (atDistance - cumulative[lo]) / span else 0.0
        return lo to u.coerceIn(0.0, 1.0)
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val METERS_PER_DEGREE = 111_320.0

        /**
         * Haversine at 6,371,000 m, in the Swift source's operation order
         * (`WayGeometry.swift:159-166@7c200bf`) so fracs match iOS to the
         * last bit wherever the math libraries agree; `haversineMeters`
         * converts to radians in a different order.
         */
        fun distanceMeters(from: WayPoint, to: WayPoint): Double {
            val dLat = (to.lat - from.lat) * Math.PI / 180
            val dLon = (to.lon - from.lon) * Math.PI / 180
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(from.lat * Math.PI / 180) * cos(to.lat * Math.PI / 180) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
        }

        /** Initial great-circle bearing, degrees clockwise from true north, in 0 until 360. */
        fun bearing(from: WayCoordinate, to: WayCoordinate): Double {
            val lat1 = from.lat * Math.PI / 180
            val lat2 = to.lat * Math.PI / 180
            val dLon = (to.lon - from.lon) * Math.PI / 180
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            val degrees = atan2(y, x) * 180 / Math.PI
            return (degrees + 360) % 360
        }

        /**
         * The parts a stage's tile region is loaded for: one rectangle per
         * segment of the line simplified at 25 m, and one square per vertex,
         * each [halfWidthMeters] from the line. Convex parts can't
         * self-intersect, so the geometry stays valid on a hairpin; the tile
         * store unions them. Every part is a closed, counterclockwise ring of
         * five coordinates, latitude first.
         *
         * Emitted quad-then-square per vertex, in line order, in Swift's
         * operation order (`WayGeometry.swift:216-262@7c200bf`): the corridor
         * hash reads these coordinates bit for bit, in this order, so any
         * other order would read as a redrawn stage and reload every region.
         */
        fun corridor(around: List<WayCoordinate>, halfWidthMeters: Double): List<List<WayCoordinate>> {
            val h = halfWidthMeters
            val line = simplified(around, toleranceMeters = 25.0)
            val first = line.firstOrNull() ?: return emptyList()
            val latScale = METERS_PER_DEGREE
            val lonScale = METERS_PER_DEGREE * cos(first.lat * Math.PI / 180)
            // Work in local metres, then back to degrees at the end.
            val local = line.map {
                LocalPoint(x = (it.lon - first.lon) * lonScale, y = (it.lat - first.lat) * latScale)
            }
            fun geo(x: Double, y: Double) =
                WayCoordinate(lat = first.lat + y / latScale, lon = first.lon + x / lonScale)
            fun square(v: LocalPoint) = listOf(
                geo(v.x - h, v.y - h),
                geo(v.x + h, v.y - h),
                geo(v.x + h, v.y + h),
                geo(v.x - h, v.y + h),
                geo(v.x - h, v.y - h),
            )
            val parts = ArrayList<List<WayCoordinate>>(2 * local.size)
            for (i in local.indices) {
                if (i + 1 < local.size) {
                    val a = local[i]
                    val b = local[i + 1]
                    var dx = b.x - a.x
                    var dy = b.y - a.y
                    val len = sqrt(dx * dx + dy * dy)
                    // A zero-length segment has no perpendicular and so no
                    // rectangle; its endpoints' squares still cover it.
                    if (len > 0) {
                        dx /= len
                        dy /= len
                        val nx = -dy * h
                        val ny = dx * h
                        // Right side forward, left side back: counterclockwise
                        // like the squares, as RFC 7946 asks of an exterior ring.
                        parts.add(
                            listOf(
                                geo(a.x - nx, a.y - ny),
                                geo(b.x - nx, b.y - ny),
                                geo(b.x + nx, b.y + ny),
                                geo(a.x + nx, a.y + ny),
                                geo(a.x - nx, a.y - ny),
                            ),
                        )
                    }
                }
                parts.add(square(local[i]))
            }
            return parts
        }

        fun corridorContains(rings: List<List<WayCoordinate>>, point: WayCoordinate): Boolean =
            rings.any { ringContains(it, point) }

        /**
         * Douglas–Peucker on local metres from the first point
         * (`WayGeometry.swift:268-305@7c200bf`): both ends kept, an explicit
         * stack, the distance to the clamped segment (to its start when it
         * has no length), the first of equally far points, and a point kept
         * only when strictly farther than [toleranceMeters]. Fewer than three
         * points come back as the same list; the kept points are the input's
         * own, never projected copies.
         */
        fun simplified(points: List<WayCoordinate>, toleranceMeters: Double): List<WayCoordinate> {
            if (points.size <= 2) return points
            val first = points[0]
            val latScale = METERS_PER_DEGREE
            val lonScale = METERS_PER_DEGREE * cos(first.lat * Math.PI / 180)
            val local = points.map {
                LocalPoint(x = (it.lon - first.lon) * lonScale, y = (it.lat - first.lat) * latScale)
            }
            val keep = BooleanArray(points.size)
            keep[0] = true
            keep[points.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(0 to points.size - 1)
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                if (b - a <= 1) continue
                val ax = local[a].x
                val ay = local[a].y
                val dx = local[b].x - ax
                val dy = local[b].y - ay
                val lenSq = dx * dx + dy * dy
                var farthest = -1.0
                var index = a
                for (i in (a + 1) until b) {
                    val px = local[i].x - ax
                    val py = local[i].y - ay
                    val distance = if (lenSq > 0) {
                        val u = max(0.0, min(1.0, (px * dx + py * dy) / lenSq))
                        val cx = px - u * dx
                        val cy = py - u * dy
                        sqrt(cx * cx + cy * cy)
                    } else {
                        sqrt(px * px + py * py)
                    }
                    if (distance > farthest) {
                        farthest = distance
                        index = i
                    }
                }
                if (farthest > toleranceMeters) {
                    keep[index] = true
                    stack.addLast(a to index)
                    stack.addLast(index to b)
                }
            }
            return points.filterIndexed { i, _ -> keep[i] }
        }

        /**
         * Even-odd ray casting east, in raw degrees, over every edge
         * including the closing one (`WayGeometry.swift:307-322@7c200bf`).
         * Good enough for "is this tile centre inside". A ring of three
         * coordinates or fewer contains nothing.
         */
        fun ringContains(ring: List<WayCoordinate>, point: WayCoordinate): Boolean {
            if (ring.size <= 3) return false
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val yi = ring[i].lat
                val xi = ring[i].lon
                val yj = ring[j].lat
                val xj = ring[j].lon
                if ((yi > point.lat) != (yj > point.lat)) {
                    val x = (xj - xi) * (point.lat - yi) / (yj - yi) + xi
                    if (point.lon < x) inside = !inside
                }
                j = i
            }
            return inside
        }

        private class LocalPoint(val x: Double, val y: Double)
    }
}
