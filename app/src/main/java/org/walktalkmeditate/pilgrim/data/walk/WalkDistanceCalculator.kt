// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

/**
 * Cumulative haversine distance over consecutive [RouteDataSample]
 * points — Room-entity entry point for the `.pilgrim` exporter (Stage
 * 10-I). Walk-summary code already calls [walkDistanceMeters] on a
 * [LocationPoint] list; this is the parallel entry point for code
 * holding raw Room rows. Both routes share the same canonical
 * `domain.haversineMeters` math so cross-platform byte-equivalent
 * distances are guaranteed.
 */
object WalkDistanceCalculator {

    fun computeDistanceMeters(samples: List<RouteDataSample>): Double {
        if (samples.size < 2) return 0.0
        return walkDistanceMeters(samples.map { it.toLocationPoint() })
    }

    private fun RouteDataSample.toLocationPoint(): LocationPoint = LocationPoint(
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = horizontalAccuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
    )
}
