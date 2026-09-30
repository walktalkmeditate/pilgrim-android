// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal const val EARTH_RADIUS_METERS = 6_371_000.0

private const val WGS84_SEMI_MAJOR_AXIS_METERS = 6_378_137.0
private const val WGS84_FLATTENING = 1 / 298.257223563
private const val WGS84_ECCENTRICITY_SQUARED = WGS84_FLATTENING * (2 - WGS84_FLATTENING)
private const val RADIANS_PER_DEGREE = Math.PI / 180

/**
 * Metres between two points the way Apple's `CLLocation.distance(from:)`
 * measures them, for the Honor call sites iOS measures with it: the moment
 * radii, the voice drops, arrival (parity spec B §16.1, D1–D4), and the
 * card's distance (D11). Apple does not document the formula.
 *
 * Measured on macOS 26.7.1 and the iOS 26.5 simulator
 * (`app/src/test/resources/honor/golden/README.md`), it is the WGS84
 * ellipsoid flattened locally at the pair's mean latitude φ: the
 * meridional radius `a(1 − e²)/w^1.5` north–south and the prime vertical
 * `a/√w · cos φ` east–west, `w = 1 − e² sin² φ`. That reproduces
 * CoreLocation's value to the last two bits for pairs up to 150 km apart;
 * past about 200 km CoreLocation changes formula, which no Honor
 * threshold (all under 300 m) can notice. Haversine on a 6,371 km sphere,
 * Seek's stand-in, is off by up to 0.56 %, which moves a 42 m radius by
 * 23 cm.
 *
 * CoreLocation also keeps those radii and reuses them while the first
 * point's latitude stays within 0.005° of the one they were computed at,
 * so iOS's value depends on its earlier calls and can differ from this
 * one by up to `(|tan φ| + 0.01) · (0.005° + |Δφ|/2)` relative, in
 * radians: 3 cm at 300 m at 42.88°N. This function is stateless, so it is
 * iOS's value whenever CoreLocation recomputes the radii for the pair.
 */
fun wgs84MidLatitudeMeters(lat1Deg: Double, lon1Deg: Double, lat2Deg: Double, lon2Deg: Double): Double {
    val meanLatitude = (lat1Deg + lat2Deg) / 2 * RADIANS_PER_DEGREE
    val sinLatitude = sin(meanLatitude)
    val w = 1 - WGS84_ECCENTRICITY_SQUARED * sinLatitude * sinLatitude
    val meridional = WGS84_SEMI_MAJOR_AXIS_METERS * (1 - WGS84_ECCENTRICITY_SQUARED) / (w * sqrt(w))
    val primeVerticalParallel = WGS84_SEMI_MAJOR_AXIS_METERS / sqrt(w) * cos(meanLatitude)
    return hypot(
        meridional * ((lat2Deg - lat1Deg) * RADIANS_PER_DEGREE),
        primeVerticalParallel * (longitudeDeltaDegrees(lon1Deg, lon2Deg) * RADIANS_PER_DEGREE),
    )
}

/**
 * As CoreLocation takes it: both longitudes into [0°, 360°) first, then
 * the difference wrapped into ±180°. The `+ 360` rounds, so a western pair
 * differs from a plain subtraction in the ninth significant digit.
 */
private fun longitudeDeltaDegrees(lon1Deg: Double, lon2Deg: Double): Double {
    val delta = (if (lon2Deg < 0) lon2Deg + 360 else lon2Deg) - (if (lon1Deg < 0) lon1Deg + 360 else lon1Deg)
    return when {
        delta > 180 -> delta - 360
        delta < -180 -> delta + 360
        else -> delta
    }
}

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
