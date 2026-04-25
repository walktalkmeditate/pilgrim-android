// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stage 8-A: downsamples a long GPS route to at most [maxPoints]
 * points using Ramer-Douglas-Peucker with a binary-search epsilon,
 * falling back to stride sampling if RDP overshoots. Port of iOS
 * `RouteDownsampler.swift` — same algorithm, same 200-point target,
 * same strideSample fallback.
 *
 * Pure function; testable without Android infra.
 */
internal object RouteDownsampler {

    fun downsample(
        points: List<SharePayload.RoutePoint>,
        maxPoints: Int = ShareConfig.DOWNSAMPLE_TARGET_POINTS,
    ): List<SharePayload.RoutePoint> {
        if (points.size <= maxPoints) return points
        val eps = findEpsilon(points, target = maxPoints)
        val simplified = ramerDouglasPeucker(points, eps)
        return if (simplified.size <= maxPoints) simplified else strideSample(simplified, maxPoints)
    }

    private fun strideSample(
        points: List<SharePayload.RoutePoint>,
        target: Int,
    ): List<SharePayload.RoutePoint> {
        if (target <= 1) return listOf(points.first())
        val step = (points.size - 1).toDouble() / (target - 1)
        val result = ArrayList<SharePayload.RoutePoint>(target)
        for (i in 0 until target - 1) {
            result += points[(i * step).toInt().coerceAtMost(points.lastIndex)]
        }
        result += points.last()
        return result
    }

    private fun ramerDouglasPeucker(
        points: List<SharePayload.RoutePoint>,
        epsilon: Double,
    ): List<SharePayload.RoutePoint> {
        if (points.size <= 2) return points
        val first = points.first()
        val last = points.last()
        var maxDist = 0.0
        var maxIdx = 0
        for (i in 1 until points.lastIndex) {
            val d = perpendicularDistance(points[i], first, last)
            if (d > maxDist) {
                maxDist = d
                maxIdx = i
            }
        }
        return if (maxDist > epsilon) {
            val left = ramerDouglasPeucker(points.subList(0, maxIdx + 1), epsilon)
            val right = ramerDouglasPeucker(points.subList(maxIdx, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun perpendicularDistance(
        p: SharePayload.RoutePoint,
        a: SharePayload.RoutePoint,
        b: SharePayload.RoutePoint,
    ): Double {
        val dx = b.lat - a.lat
        val dy = b.lon - a.lon
        val denom = sqrt(dx * dx + dy * dy)
        if (denom == 0.0) {
            val ex = p.lat - a.lat
            val ey = p.lon - a.lon
            return sqrt(ex * ex + ey * ey)
        }
        val num = abs(dx * (a.lon - p.lon) - (a.lat - p.lat) * dy)
        return num / denom
    }

    private fun findEpsilon(
        points: List<SharePayload.RoutePoint>,
        target: Int,
    ): Double {
        var low = 0.0
        var high = 1.0
        repeat(MAX_BINARY_SEARCH_ITERATIONS) {
            val mid = (low + high) / 2.0
            val simplified = ramerDouglasPeucker(points, mid)
            when {
                simplified.size > target -> low = mid
                simplified.size < target -> high = mid
                else -> return mid
            }
        }
        return high
    }

    private const val MAX_BINARY_SEARCH_ITERATIONS = 32
}
