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
 * The stage-only corridor, simplify, and ring functions are not ported.
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
    }
}
