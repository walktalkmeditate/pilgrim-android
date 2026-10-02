// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [wgs84MidLatitudeMeters] against Apple's `CLLocation.distance(from:)`.
 *
 * How the literals were made: `honor/golden/capture/capture.sh` runs
 * `harness pairs`, which measures each pair with
 * `CLLocation(latitude: lat1, longitude: lon1).distance(from:
 * CLLocation(latitude: lat2, longitude: lon2))` right after a pair at a far
 * latitude, so CoreLocation computes the radii for this pair rather than
 * reusing cached ones (the value with no history; see the golden README).
 * They are `honor/golden/expected/cl-distance-pairs.txt`, captured
 * 2026-09-29 on macOS 26.7.1 (Swift 6.3.3); the iOS 26.5 simulator's
 * CoreLocation writes the same bytes. The pairs run from 0.9 m to 323 m,
 * north, east, and oblique, from 33.87°S to 64.14°N, plus one across the
 * prime meridian, one across the antimeridian, and one of 123 km.
 */
class GeoDistanceTest {

    private data class Pair(val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double, val apple: Double)

    private val pairs = listOf(
        Pair(42.88, -8.54, 42.88, -8.5396333, 29.959195561844528),
        Pair(42.88, -8.54, 42.8803774, -8.54, 41.92551782078658),
        Pair(42.88, -8.54, 42.8801905, -8.5397398, 29.99617535063043),
        Pair(0.0, 32.61, 0.0, 32.6126951, 300.017159637391),
        Pair(0.0, 32.61, 0.0026951, 32.61, 298.00873076843425),
        Pair(60.17, 24.94, 60.1702694, 24.9405406, 42.444585976686845),
        Pair(-33.87, 151.21, -33.8716169, 151.2129031, 322.9787411160678),
        Pair(64.14, -21.94, 64.1400898, -21.9364962, 170.88191971604442),
        Pair(47.6, -122.33, 47.6000377, -122.3299443, 5.9257260331304815),
        Pair(51.5, -0.12, 51.5, -0.1199872, 0.8888386115339582),
        Pair(51.4779, -0.0002, 51.4781, 0.0003, 41.25270567000189),
        Pair(-16.5, 179.9998, -16.5003, -179.9997, 62.86355661182696),
        Pair(35.0, 135.0, 35.9, 135.8, 123473.50391501632),
    )

    @Test
    fun `matches CLLocation distance to the last bits`() {
        for (p in pairs) {
            val meters = wgs84MidLatitudeMeters(p.lat1, p.lon1, p.lat2, p.lon2)
            assertEquals("(${p.lat1}, ${p.lon1}) to (${p.lat2}, ${p.lon2})", p.apple, meters, p.apple * 1e-14)
        }
    }

    @Test
    fun `is symmetric`() {
        for (p in pairs) {
            assertEquals(
                wgs84MidLatitudeMeters(p.lat1, p.lon1, p.lat2, p.lon2),
                wgs84MidLatitudeMeters(p.lat2, p.lon2, p.lat1, p.lon1),
                p.apple * 1e-15,
            )
        }
    }

    @Test
    fun `haversine is off by up to half a percent, which is why Honor does not use it`() {
        val northAtTheEquator = pairs[4]
        val haversine = haversineMeters(
            northAtTheEquator.lat1,
            northAtTheEquator.lon1,
            northAtTheEquator.lat2,
            northAtTheEquator.lon2,
        )
        val relative = abs(haversine - northAtTheEquator.apple) / northAtTheEquator.apple
        assertTrue("haversine is off by $relative", relative > 0.005)
    }
}
