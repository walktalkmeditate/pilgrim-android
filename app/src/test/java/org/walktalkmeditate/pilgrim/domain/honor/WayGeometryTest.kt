// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.haversineMeters

/**
 * Ports iOS `UnitTests/Honor/WayGeometryTests.swift@7c200bf` test for test
 * (the first seven), then pins the edge cases parity spec A §4–§7 lists
 * for each function: empty, one point, plateaus, loops, windows, and the
 * first-run rule of `lowestFrac`. The stage corridor's tests,
 * `WayGeometryCorridorTests`, are [WayGeometryCorridorTest].
 */
class WayGeometryTest {

    /** A 1 km straight line east along the equator, 11 points, 100 m apart, 60 s apart. */
    private fun straight(): WayGeometry = WayGeometry(
        (0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.000898, alt = null, t = i * 60.0) },
    )

    /** Out and back: 500 m east then the same 500 m west, 11 points. */
    private fun outAndBack(): WayGeometry {
        val out = (0..5).map { i -> WayPoint(lat = 0.0, lon = i * 0.000898, alt = null, t = i * 60.0) }
        val back = (1..5).map { i -> WayPoint(lat = 0.0, lon = (5 - i) * 0.000898, alt = null, t = (5 + i) * 60.0) }
        return WayGeometry(out + back)
    }

    @Test
    fun `totals and frac round-trip`() {
        val geo = straight()
        assertEquals(1000.0, geo.totalMeters, 5.0)
        assertEquals(600.0, geo.totalSeconds, 0.0)
        val mid = geo.coordinate(atFrac = 0.5)
        assertEquals(0.00449, mid.lon, 0.00002)
        assertEquals(300.0, geo.elapsed(atFrac = 0.5), 1.0)
        assertEquals(0.5, geo.frac(atElapsed = 300.0), 0.01)
        assertEquals(1.0, geo.frac(atElapsed = 9999.0), 0.0)
        assertEquals(0.0, geo.frac(atElapsed = -5.0), 0.0)
    }

    @Test
    fun `nearest on a straight line`() {
        val geo = straight()
        val probe = WayCoordinate(lat = 0.00018, lon = 0.00449)
        val hit = geo.nearest(to = probe, within = null)
        assertEquals(0.5, hit.frac, 0.01)
        assertEquals(20.0, hit.meters, 2.0)
    }

    @Test
    fun `windowed nearest stays on the outbound leg`() {
        val geo = outAndBack()
        // 250 m along: both legs pass here. Unwindowed search is ambiguous.
        val probe = WayCoordinate(lat = 0.0, lon = 0.000898 * 2.5)
        val windowed = geo.nearest(to = probe, within = 0.20..0.35)
        assertEquals(0.25, windowed.frac, 0.01)
        val returnLeg = geo.nearest(to = probe, within = 0.70..0.85)
        assertEquals(0.75, returnLeg.frac, 0.01)
    }

    @Test
    fun `windowed nearest never leaks past the window`() {
        val geo = outAndBack()
        val probe = WayCoordinate(lat = 0.0, lon = 0.000898 * 2.5)
        val hit = geo.nearest(to = probe, within = 0.0..0.1)
        assertEquals("clamped to the window's edge", 0.1, hit.frac, 0.001)
        assertEquals(150.0, hit.meters, 3.0)
    }

    @Test
    fun `degenerate routes`() {
        val single = WayGeometry(listOf(WayPoint(lat = 1.0, lon = 1.0, alt = null, t = 0.0)))
        assertEquals(0.0, single.totalMeters, 0.0)
        assertEquals(1.0, single.frac(atElapsed = 10.0), 0.0)
        val hit = single.nearest(to = WayCoordinate(lat = 1.0, lon = 1.0), within = null)
        assertEquals(0.0, hit.frac, 0.0)
        assertEquals(0.0, hit.meters, 0.01)
    }

    /**
     * A rest: two points at one place, sixty seconds apart. The inverse map
     * lands on the end of the pause (depart together); the forward map holds
     * the dot at the rest for the whole pause.
     */
    @Test
    fun `a pause maps to the moment they moved on`() {
        val geo = WayGeometry(
            listOf(
                WayPoint(lat = 0.0, lon = 0.0, alt = null, t = 0.0),
                WayPoint(lat = 0.0, lon = 0.000898, alt = null, t = 60.0),
                WayPoint(lat = 0.0, lon = 0.000898, alt = null, t = 120.0),
                WayPoint(lat = 0.0, lon = 0.001796, alt = null, t = 180.0),
            ),
        )
        assertEquals(120.0, geo.elapsed(atFrac = 0.5), 0.5)
        assertEquals(0.5, geo.frac(atElapsed = 70.0), 0.01)
        assertEquals(0.5, geo.frac(atElapsed = 110.0), 0.01)
        assertEquals(0.75, geo.frac(atElapsed = 150.0), 0.01)
    }

    /**
     * Real walks sample every couple of metres; the anchor must land where
     * the walker stands, not at the first segment whose far end is in range.
     */
    @Test
    fun `lowest frac on a finely sampled route lands where the walker stands`() {
        val points = (0..500).map { i -> WayPoint(lat = 0.0, lon = i * 2 / 111_320.0, alt = null, t = i.toDouble()) }
        val geo = WayGeometry(points)
        val probe = WayCoordinate(lat = 0.0, lon = 500 / 111_320.0)
        assertEquals(0.5, geo.lowestFrac(withinMeters = 60.0, of = probe)?.frac ?: -1.0, 0.003)
    }

    // Android additions: the edge cases parity spec A §4–§7 pins per function.

    @Test
    fun `an empty route answers every question without a segment`() {
        val empty = WayGeometry(emptyList())
        assertEquals(emptyList<Double>(), empty.cumulative)
        assertEquals(0.0, empty.totalMeters, 0.0)
        assertEquals(0.0, empty.totalSeconds, 0.0)
        assertEquals(WayCoordinate(lat = 0.0, lon = 0.0), empty.coordinate(atFrac = 0.5))
        assertEquals(emptyList<WayCoordinate>(), empty.slice(fromFrac = 0.0, toFrac = 1.0))
        assertEquals(0.0, empty.elapsed(atFrac = 0.5), 0.0)
        assertEquals(1.0, empty.frac(atElapsed = 10.0), 0.0)
        val hit = empty.nearest(to = WayCoordinate(lat = 1.0, lon = 1.0), within = null)
        assertEquals(0.0, hit.frac, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, hit.meters, 0.0)
        assertNull(empty.lowestFrac(withinMeters = 1_000_000.0, of = WayCoordinate(lat = 1.0, lon = 1.0)))
    }

    @Test
    fun `a one-point route answers from its only point`() {
        val single = WayGeometry(listOf(WayPoint(lat = 1.0, lon = 1.0, alt = null, t = 5.0)))
        assertEquals(listOf(0.0), single.cumulative)
        assertEquals(0.0, single.totalSeconds, 0.0)
        assertEquals(WayCoordinate(lat = 1.0, lon = 1.0), single.coordinate(atFrac = 0.7))
        assertEquals(listOf(WayCoordinate(lat = 1.0, lon = 1.0)), single.slice(fromFrac = 0.2, toFrac = 0.8))
        assertEquals(0.0, single.elapsed(atFrac = 0.7), 0.0)
        val near = WayCoordinate(lat = 1.0, lon = 1.0001)
        val hit = single.lowestFrac(withinMeters = 20.0, of = near)
        assertEquals(0.0, hit?.frac ?: -1.0, 0.0)
        val haversine = haversineMeters(1.0, 1.0, 1.0, 1.0001)
        assertEquals("the degenerate branch measures by haversine", haversine, hit?.meters ?: -1.0, 1e-9)
        assertNull(single.lowestFrac(withinMeters = 5.0, of = near))
    }

    @Test
    fun `duplicate points add no distance`() {
        val geo = WayGeometry(
            listOf(
                WayPoint(lat = 0.0, lon = 0.0, alt = null, t = 0.0),
                WayPoint(lat = 0.0, lon = 0.0, alt = null, t = 30.0),
                WayPoint(lat = 0.0, lon = 0.000898, alt = null, t = 90.0),
            ),
        )
        assertEquals(geo.cumulative[0], geo.cumulative[1], 0.0)
        assertEquals("elapsed lands on the end of the plateau", 30.0, geo.elapsed(atFrac = 0.0), 0.0)
        assertEquals("a stationary segment holds its frac", 0.0, geo.frac(atElapsed = 15.0), 0.0)
    }

    @Test
    fun `a vertex time resolves on the segment that ends there`() {
        val geo = straight()
        assertEquals(geo.cumulative[1] / geo.totalMeters, geo.frac(atElapsed = 60.0), 0.0)
    }

    @Test
    fun `coordinate clamps its frac to the route`() {
        val geo = straight()
        assertNear(WayCoordinate(lat = 0.0, lon = 0.0), geo.coordinate(atFrac = -1.0))
        assertNear(WayCoordinate(lat = 0.0, lon = 10 * 0.000898), geo.coordinate(atFrac = 2.0))
    }

    @Test
    fun `a loop is not a plateau and a tie keeps the lower segment`() {
        // A closed square back to the start, one minute a side.
        val corners = listOf(0.0 to 0.0, 0.0 to 0.001, 0.001 to 0.001, 0.001 to 0.0, 0.0 to 0.0)
        val geo = WayGeometry(
            corners.mapIndexed { i, (lat, lon) -> WayPoint(lat = lat, lon = lon, alt = null, t = i * 60.0) },
        )
        assertEquals(0.875, geo.frac(atElapsed = 210.0), 1e-6)
        assertEquals(210.0, geo.elapsed(atFrac = 0.875), 1e-3)
        val start = WayCoordinate(lat = 0.0, lon = 0.0)
        val tie = geo.nearest(to = start, within = null)
        assertEquals("first and last segment tie; the first wins", 0.0, tie.frac, 0.0)
        assertEquals(1.0, geo.nearest(to = start, within = 0.9..1.0).frac, 1e-9)
        assertEquals(0.0, geo.lowestFrac(withinMeters = 30.0, of = start)?.frac ?: -1.0, 0.0)
    }

    @Test
    fun `a window that holds no segment finds nothing`() {
        val hit = straight().nearest(to = WayCoordinate(lat = 0.0, lon = 0.0045), within = 1.5..2.0)
        assertEquals(0.0, hit.frac, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, hit.meters, 0.0)
    }

    @Test
    fun `lowest frac takes the first run within range, outbound before return`() {
        val geo = outAndBack()
        val probe = WayCoordinate(lat = 0.0, lon = 0.000898 * 2.5)
        assertEquals(0.25, geo.lowestFrac(withinMeters = 60.0, of = probe)?.frac ?: -1.0, 0.01)
        assertEquals(0.75, geo.lowestFrac(withinMeters = 60.0, of = probe, fromFrac = 0.5)?.frac ?: -1.0, 0.01)
        assertNull(geo.lowestFrac(withinMeters = 10.0, of = WayCoordinate(lat = 0.001, lon = 0.0)))
    }

    @Test
    fun `lowest frac never answers below its floor`() {
        val geo = straight()
        val probe = WayCoordinate(lat = 0.0, lon = 2 * 0.000898)
        val hit = geo.lowestFrac(withinMeters = 400.0, of = probe, fromFrac = 0.5)
        assertEquals(0.5, hit?.frac ?: -1.0, 1e-9)
        assertEquals(300.0, hit?.meters ?: -1.0, 2.0)
    }

    @Test
    fun `slice carries both ends and every point strictly inside`() {
        val geo = straight()
        val slice = geo.slice(fromFrac = 0.25, toFrac = 0.55)
        assertEquals(5, slice.size)
        assertNear(geo.coordinate(atFrac = 0.25), slice.first())
        assertEquals(listOf(3, 4, 5).map { WayCoordinate(lat = 0.0, lon = it * 0.000898) }, slice.subList(1, 4))
        assertNear(geo.coordinate(atFrac = 0.55), slice.last())
        assertEquals("reversed fracs are swapped", slice, geo.slice(fromFrac = 0.55, toFrac = 0.25))
        val point = geo.slice(fromFrac = 0.4, toFrac = 0.4)
        assertEquals(2, point.size)
        assertEquals(point[0], point[1])
        assertNear(geo.coordinate(atFrac = 0.4), point[0])
    }

    @Test
    fun `lengths are haversine at 6371 km`() {
        val oneDegree = WayGeometry.distanceMeters(
            WayPoint(lat = 0.0, lon = 0.0, alt = null, t = 0.0),
            WayPoint(lat = 1.0, lon = 0.0, alt = null, t = 0.0),
        )
        assertEquals(6_371_000.0 * Math.PI / 180, oneDegree, 1e-6)
        val a = WayPoint(lat = 42.88, lon = -8.545, alt = null, t = 0.0)
        val b = WayPoint(lat = 42.8812, lon = -8.5402, alt = null, t = 0.0)
        assertEquals(haversineMeters(a.lat, a.lon, b.lat, b.lon), WayGeometry.distanceMeters(a, b), 1e-9)
    }

    @Test
    fun `bearing is clockwise from true north in 0 until 360`() {
        val origin = WayCoordinate(lat = 0.0, lon = 0.0)
        assertEquals(0.0, WayGeometry.bearing(from = origin, to = WayCoordinate(lat = 1.0, lon = 0.0)), 1e-9)
        assertEquals(90.0, WayGeometry.bearing(from = origin, to = WayCoordinate(lat = 0.0, lon = 1.0)), 1e-9)
        assertEquals(180.0, WayGeometry.bearing(from = origin, to = WayCoordinate(lat = -1.0, lon = 0.0)), 1e-9)
        assertEquals(270.0, WayGeometry.bearing(from = origin, to = WayCoordinate(lat = 0.0, lon = -1.0)), 1e-9)
        val northWest = WayGeometry.bearing(from = origin, to = WayCoordinate(lat = 1.0, lon = -1.0))
        assertTrue(northWest > 270.0 && northWest < 360.0)
    }

    private fun assertNear(expected: WayCoordinate, actual: WayCoordinate) {
        assertEquals(expected.lat, actual.lat, 1e-12)
        assertEquals(expected.lon, actual.lon, 1e-12)
    }
}
