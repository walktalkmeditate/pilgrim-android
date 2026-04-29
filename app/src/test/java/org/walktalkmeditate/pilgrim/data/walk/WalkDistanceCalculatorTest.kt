// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

class WalkDistanceCalculatorTest {

    @Test
    fun `empty samples returns zero`() {
        assertEquals(0.0, WalkDistanceCalculator.computeDistanceMeters(emptyList()), 0.0)
    }

    @Test
    fun `single sample returns zero`() {
        assertEquals(0.0, WalkDistanceCalculator.computeDistanceMeters(listOf(sample(0, 0.0, 0.0))), 0.0)
    }

    @Test
    fun `two samples one degree apart return ~111 km`() {
        val samples = listOf(
            sample(0, 0.0, 0.0),
            sample(1000, 1.0, 0.0),
        )
        // 1° latitude ≈ 111 km at any longitude
        val distance = WalkDistanceCalculator.computeDistanceMeters(samples)
        assertTrue("expected ~111000m, got $distance", abs(distance - 111000.0) < 200.0)
    }

    @Test
    fun `co-located samples return zero distance`() {
        val samples = listOf(
            sample(0, 47.6, -122.3),
            sample(1000, 47.6, -122.3),
            sample(2000, 47.6, -122.3),
        )
        assertEquals(0.0, WalkDistanceCalculator.computeDistanceMeters(samples), 0.001)
    }

    private fun sample(timestamp: Long, lat: Double, lng: Double) = RouteDataSample(
        walkId = 1L, timestamp = timestamp, latitude = lat, longitude = lng,
    )
}
