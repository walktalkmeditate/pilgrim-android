// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

class MapCameraBoundsTest {
    private fun sample(t: Long, lat: Double, lng: Double) = RouteDataSample(
        walkId = 1L, timestamp = t, latitude = lat, longitude = lng, altitudeMeters = 0.0,
    )

    @Test fun emptySamples_returnsNull() {
        assertNull(computeBoundsForTimeRange(emptyList(), 0L, 100L))
    }

    @Test fun samplesOutsideTimeRange_returnsNull() {
        val samples = listOf(sample(50L, 1.0, 1.0), sample(150L, 2.0, 2.0))
        assertNull(computeBoundsForTimeRange(samples, 200L, 300L))
    }

    @Test fun singleSampleInRange_yieldsZeroSpanWithMinPadding() {
        val samples = listOf(sample(100L, 1.0, 2.0))
        val bounds = computeBoundsForTimeRange(samples, 0L, 200L)
        assertNotNull(bounds)
        // 0 span * 0.15 + 0.001 = 0.001 padding either side
        assertEquals(0.999, bounds!!.swLat, 0.0001)
        assertEquals(1.001, bounds.neLat, 0.0001)
        assertEquals(1.999, bounds.swLng, 0.0001)
        assertEquals(2.001, bounds.neLng, 0.0001)
    }

    @Test fun multiSampleInRange_yieldsBoundsWithFifteenPercentPadding() {
        val samples = listOf(
            sample(100L, 1.0, 1.0),
            sample(200L, 3.0, 5.0),
        )
        val bounds = computeBoundsForTimeRange(samples, 50L, 250L)
        assertNotNull(bounds)
        // span lat = 2.0, pad = 2.0 * 0.15 + 0.001 = 0.301
        // span lng = 4.0, pad = 4.0 * 0.15 + 0.001 = 0.601
        assertEquals(0.699, bounds!!.swLat, 0.0001)
        assertEquals(3.301, bounds.neLat, 0.0001)
        assertEquals(0.399, bounds.swLng, 0.0001)
        assertEquals(5.601, bounds.neLng, 0.0001)
    }
}
