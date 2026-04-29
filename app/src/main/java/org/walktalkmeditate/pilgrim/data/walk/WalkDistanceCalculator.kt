// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

/**
 * Cumulative haversine distance over consecutive [RouteDataSample]
 * points. Used by the walk-summary VM (live distance) and the
 * `.pilgrim` exporter (Stage 10-I) to populate `PilgrimStats.distance`.
 *
 * Earth radius matches the inline implementation that previously
 * lived in `WalkSummaryViewModel.buildState()` so the extracted helper
 * yields byte-identical results.
 */
object WalkDistanceCalculator {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun computeDistanceMeters(samples: List<RouteDataSample>): Double {
        if (samples.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until samples.size) {
            total += haversine(
                samples[i - 1].latitude,
                samples[i - 1].longitude,
                samples[i].latitude,
                samples[i].longitude,
            )
        }
        return total
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}
