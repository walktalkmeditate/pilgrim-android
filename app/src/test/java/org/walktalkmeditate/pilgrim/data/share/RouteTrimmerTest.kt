// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors `UnitTests/RouteTrimmerTests.swift` scenario-for-scenario
 * (pin `3f9f9e8`). [RouteTrimmer] shaves walked distance off both ends
 * of a route for doorstep privacy; `canTrim` must never disagree with
 * what `trim` actually does (RouteTrimmer.swift:8-29).
 */
class RouteTrimmerTest {

    /** ~111m per 0.001 degrees latitude. */
    private fun straightRoute(points: Int, stepDegrees: Double = 0.001): List<SharePayload.RoutePoint> =
        (0 until points).map { i ->
            SharePayload.RoutePoint(lat = 35.0 + i * stepDegrees, lon = -105.0, alt = 2000.0, ts = (1000 + i * 30).toLong())
        }

    @Test
    fun `trim with zero meters returns the route unchanged`() {
        val route = straightRoute(points = 10)
        assertEquals(10, RouteTrimmer.trim(route, meters = 0.0).size)
    }

    @Test
    fun `trim removes distance off both ends`() {
        val route = straightRoute(points = 20) // ~2.1km total, 111m steps
        val trimmed = RouteTrimmer.trim(route, meters = 150.0)
        assertTrue("expected fewer points after trim", trimmed.size < 20)
        assertTrue("first point should move inward", trimmed.first().lat > route.first().lat)
        assertTrue("last point should move inward", trimmed.last().lat < route.last().lat)
        assertTrue("trimmed route must keep at least 2 points", trimmed.size >= 2)
    }

    @Test
    fun `short walk shares untrimmed`() {
        val route = straightRoute(points = 4) // ~333m total < 4 * 150
        assertEquals(4, RouteTrimmer.trim(route, meters = 150.0).size)
    }

    @Test
    fun `canTrim reflects the 4x distance threshold`() {
        assertFalse(RouteTrimmer.canTrim(straightRoute(points = 4), meters = 150.0))
        assertTrue(RouteTrimmer.canTrim(straightRoute(points = 20), meters = 150.0))
    }

    @Test
    fun `clustered endpoint collision is honestly reported`() {
        // Route with ~1000m + ~1000m + ~1m segments: total ~2km, but
        // endpoints cluster together at the end, causing trim's
        // start/end pointers to collide. canTrim must return false AND
        // trim must return the route unchanged.
        val route = listOf(
            SharePayload.RoutePoint(lat = 35.0, lon = -105.0, alt = 2000.0, ts = 1000L),
            SharePayload.RoutePoint(lat = 35.009, lon = -105.0, alt = 2000.0, ts = 1030L), // ~1000m from point 0
            SharePayload.RoutePoint(lat = 35.018, lon = -105.0, alt = 2000.0, ts = 1060L), // ~1000m from point 1
            SharePayload.RoutePoint(lat = 35.018009, lon = -105.0, alt = 2000.0, ts = 1090L), // ~1m from point 2
        )
        assertFalse(RouteTrimmer.canTrim(route, meters = 150.0))
        assertEquals(4, RouteTrimmer.trim(route, meters = 150.0).size)
    }

    @Test
    fun `canTrim always agrees with trim across adversarial geometries`() {
        val geometries: List<Pair<String, List<SharePayload.RoutePoint>>> = listOf(
            "uniform 20-point" to straightRoute(points = 20),
            "clustered endpoints" to listOf(
                SharePayload.RoutePoint(lat = 35.0, lon = -105.0, alt = 2000.0, ts = 1000L),
                SharePayload.RoutePoint(lat = 35.009, lon = -105.0, alt = 2000.0, ts = 1030L),
                SharePayload.RoutePoint(lat = 35.018, lon = -105.0, alt = 2000.0, ts = 1060L),
                SharePayload.RoutePoint(lat = 35.018009, lon = -105.0, alt = 2000.0, ts = 1090L),
            ),
            "short 4-point walk" to straightRoute(points = 4),
            "10-point with huge end segment" to (0 until 9).map { i ->
                SharePayload.RoutePoint(lat = 35.0 + i * 0.001, lon = -105.0, alt = 2000.0, ts = (1000 + i * 30).toLong())
            } + SharePayload.RoutePoint(lat = 35.009, lon = -105.0 + 0.045, alt = 2000.0, ts = 1270L), // ~5km from previous
        )

        for ((name, route) in geometries) {
            val canTrimResult = RouteTrimmer.canTrim(route, meters = 150.0)
            val trimResult = RouteTrimmer.trim(route, meters = 150.0)
            val trimChanged = trimResult.size < route.size
            assertEquals("Mismatch for $name: canTrim=$canTrimResult, trim changed=$trimChanged", canTrimResult, trimChanged)
        }
    }

    @Test
    fun `degenerate routes of 0, 1, 2, and 3 points are all untrimmable`() {
        val empty = emptyList<SharePayload.RoutePoint>()
        assertEquals(0, RouteTrimmer.trim(empty, meters = 150.0).size)
        assertFalse(RouteTrimmer.canTrim(empty, meters = 150.0))

        val onePoint = listOf(SharePayload.RoutePoint(lat = 35.0, lon = -105.0, alt = 2000.0, ts = 1000L))
        assertEquals(1, RouteTrimmer.trim(onePoint, meters = 150.0).size)
        assertFalse(RouteTrimmer.canTrim(onePoint, meters = 150.0))

        val twoPoints = listOf(
            SharePayload.RoutePoint(lat = 35.0, lon = -105.0, alt = 2000.0, ts = 1000L),
            SharePayload.RoutePoint(lat = 35.001, lon = -105.0, alt = 2000.0, ts = 1030L),
        )
        assertEquals(2, RouteTrimmer.trim(twoPoints, meters = 150.0).size)
        assertFalse(RouteTrimmer.canTrim(twoPoints, meters = 150.0))

        val threePoints = listOf(
            SharePayload.RoutePoint(lat = 35.0, lon = -105.0, alt = 2000.0, ts = 1000L),
            SharePayload.RoutePoint(lat = 35.001, lon = -105.0, alt = 2000.0, ts = 1030L),
            SharePayload.RoutePoint(lat = 35.002, lon = -105.0, alt = 2000.0, ts = 1060L),
        )
        assertEquals(3, RouteTrimmer.trim(threePoints, meters = 150.0).size)
        assertFalse(RouteTrimmer.canTrim(threePoints, meters = 150.0))
    }

    @Test
    fun `degenerate 200m walk cannot trim and route stays untouched`() {
        // 4 points, ~66.6m per step (0.0006 degrees) = ~200m total,
        // comfortably under the 4 * 150 = 600m required floor.
        val route = straightRoute(points = 4, stepDegrees = 0.0006)
        assertFalse(RouteTrimmer.canTrim(route, meters = 150.0))
        assertEquals(route, RouteTrimmer.trim(route, meters = 150.0))
    }
}
