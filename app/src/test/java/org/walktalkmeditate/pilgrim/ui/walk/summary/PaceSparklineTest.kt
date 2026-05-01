// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

class PaceSparklineTest {
    private fun sample(t: Long, speed: Float?): RouteDataSample = RouteDataSample(
        walkId = 1L, timestamp = t, latitude = 0.0, longitude = 0.0,
        altitudeMeters = 0.0, speedMetersPerSecond = speed,
    )

    @Test fun emptySamples_returnsEmpty() {
        assertTrue(computePaceSparklinePoints(emptyList(), 0L, 1000L).isEmpty())
    }

    @Test fun underThreeSamples_returnsEmpty() {
        val samples = listOf(sample(100L, 1.5f), sample(200L, 1.5f))
        assertTrue(computePaceSparklinePoints(samples, 0L, 1000L).isEmpty())
    }

    @Test fun monotonicAcceleration_yieldsAscendingY() {
        // Higher speed at later timestamp → lower y (top of frame is fastest).
        // y = 1f - (avgSpeed / maxSpeed) * 0.85f → speed=max gives y=0.15.
        val samples = listOf(
            sample(100L, 1.0f),
            sample(200L, 2.0f),
            sample(300L, 3.0f),
        )
        val points = computePaceSparklinePoints(samples, 0L, 400L)
        assertEquals(3, points.size)
        // Last point (highest speed) has lowest y.
        assertTrue(points.last().yFraction < points.first().yFraction)
    }

    @Test fun mixedSpeeds_filtersBelowThreshold() {
        // Speeds at or below 0.3 m/s drop out.
        val samples = listOf(
            sample(100L, 0.2f),
            sample(150L, 1.0f),
            sample(200L, 1.5f),
            sample(250L, 2.0f),
            sample(300L, 0.1f),
        )
        val points = computePaceSparklinePoints(samples, 0L, 400L)
        // Filtered list has 3 above-threshold samples → 3 points.
        assertEquals(3, points.size)
    }

    @Test fun maxSpeedZero_returnsEmpty() {
        // All below threshold → filtered list empty → guard returns empty.
        val samples = listOf(
            sample(100L, 0.1f),
            sample(200L, 0.2f),
            sample(300L, 0.0f),
        )
        assertTrue(computePaceSparklinePoints(samples, 0L, 400L).isEmpty())
    }
}
