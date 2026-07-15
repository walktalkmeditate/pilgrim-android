// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val EARTH_RADIUS_METERS = 6_371_000.0

fun haversineMeters(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
    val lat1 = Math.toRadians(lat1Deg)
    val lat2 = Math.toRadians(lat2Deg)
    val deltaLat = Math.toRadians(lat2Deg - lat1Deg)
    val deltaLon = Math.toRadians(lon2Deg - lon1Deg)
    val h = sin(deltaLat / 2) * sin(deltaLat / 2) +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    return EARTH_RADIUS_METERS * 2 * atan2(sqrt(h), sqrt(1 - h))
}

fun haversineMeters(a: LocationPoint, b: LocationPoint): Double =
    haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
