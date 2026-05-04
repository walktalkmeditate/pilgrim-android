// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSampleProjectionTest {

    @Test
    fun closestCoordinate_emptySamples_returnsNull() {
        assertNull(RouteSampleProjection.closestCoordinate(emptyList(), 1_000L))
    }

    @Test
    fun closestCoordinate_singleSample_returnsThatCoord() {
        val samples = listOf(RouteSample(timestampMs = 500L, latitude = 1.5, longitude = 2.5))
        assertEquals(LatLng(1.5, 2.5), RouteSampleProjection.closestCoordinate(samples, 1_000L))
    }

    @Test
    fun closestCoordinate_picksClosestByAbsDelta() {
        val samples = listOf(
            RouteSample(timestampMs = 100L, latitude = 10.0, longitude = 20.0),
            RouteSample(timestampMs = 200L, latitude = 30.0, longitude = 40.0),
        )
        assertEquals(LatLng(10.0, 20.0), RouteSampleProjection.closestCoordinate(samples, 140L))
        assertEquals(LatLng(30.0, 40.0), RouteSampleProjection.closestCoordinate(samples, 170L))
    }

    @Test
    fun distanceAtTimestamp_emptySamples_returnsZero() {
        assertEquals(0.0, RouteSampleProjection.distanceAtTimestamp(emptyList(), 1_000L), 1e-9)
    }

    @Test
    fun distanceAtTimestamp_singleSample_returnsZero() {
        val samples = listOf(RouteSample(timestampMs = 100L, latitude = 0.0, longitude = 0.0))
        assertEquals(0.0, RouteSampleProjection.distanceAtTimestamp(samples, 1_000L), 1e-9)
    }

    @Test
    fun distanceAtTimestamp_threeSamples_summed() {
        val samples = listOf(
            RouteSample(timestampMs = 100L, latitude = 0.0, longitude = 0.0),
            RouteSample(timestampMs = 200L, latitude = 0.001, longitude = 0.0),
            RouteSample(timestampMs = 300L, latitude = 0.001, longitude = 0.001),
        )
        val actual = RouteSampleProjection.distanceAtTimestamp(samples, 300L)
        assertTrue(
            "expected ~222.64 m but was $actual",
            kotlin.math.abs(actual - 222.64) < 0.5,
        )
    }

    @Test
    fun distanceAtTimestamp_stopsAtTarget() {
        val samples = listOf(
            RouteSample(timestampMs = 100L, latitude = 0.0, longitude = 0.0),
            RouteSample(timestampMs = 200L, latitude = 0.001, longitude = 0.0),
            RouteSample(timestampMs = 300L, latitude = 0.001, longitude = 0.001),
        )
        assertEquals(0.0, RouteSampleProjection.distanceAtTimestamp(samples, 180L), 1e-9)
    }

    @Test
    fun truncate_shorter_returnsAsIs() {
        assertEquals("hello", "hello".truncatedAtWordBoundary(maxLength = 10))
    }

    @Test
    fun truncate_atWord_returnsTrimmedHead() {
        assertEquals(
            "the quick...",
            "the quick brown fox".truncatedAtWordBoundary(maxLength = 10),
        )
    }

    @Test
    fun truncate_noSpace_returnsHardCut() {
        assertEquals("super...", "supercalifragilistic".truncatedAtWordBoundary(maxLength = 5))
    }
}
