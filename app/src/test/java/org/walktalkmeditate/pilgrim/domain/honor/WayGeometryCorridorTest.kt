// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageWayImporter

/**
 * Port of iOS `UnitTests/Honor/WayGeometryCorridorTests.swift@7c200bf`, all
 * nine, names and fixtures kept (spec D C1 §13.1–§13.2). Then the Android
 * additions of C1 §13.6 and the edge facts C1 §2–§4 pins from the Swift
 * probe: the empty line, a closed loop, a line that crosses itself, the
 * strict keep rule, `simplified` returning the input's own points, and the
 * stage fixture's corridor to the bit.
 */
class WayGeometryCorridorTest {

    private fun straight(km: Double, lat: Double = 42.0): List<WayCoordinate> {
        val metersPerDegreeLon = 111_320 * cos(lat * Math.PI / 180)
        val steps = (km * 10).toInt()
        return (0..steps).map { i -> WayCoordinate(lat = lat, lon = i.toDouble() * 100 / metersPerDegreeLon) }
    }

    private fun offset(from: WayCoordinate, northMeters: Double, eastMeters: Double): WayCoordinate = WayCoordinate(
        lat = from.lat + northMeters / 111_320,
        lon = from.lon + eastMeters / (111_320 * cos(from.lat * Math.PI / 180)),
    )

    private data class Xy(val x: Double, val y: Double)

    /**
     * Walks the closed ring in the local-metre frame and returns true only
     * when every turn bends the same way. A bowtie carries five coordinates
     * exactly like a quad does, so counting them proves nothing about
     * self-intersection; the sign of the cross products does.
     */
    private fun isConvex(ring: List<WayCoordinate>): Boolean {
        val first = ring.firstOrNull() ?: return false
        if (ring.size <= 3) return false
        val lonScale = cos(first.lat * Math.PI / 180)
        val vertices = ring.dropLast(1).map { Xy(x = it.lon * lonScale, y = it.lat) }
        val edges = mutableListOf<Xy>()
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            val edge = Xy(x = b.x - a.x, y = b.y - a.y)
            // A zero-length edge has no direction to turn from.
            if (edge.x != 0.0 || edge.y != 0.0) edges += edge
        }
        if (edges.size <= 2) return false
        var sign = 0.0
        for (i in edges.indices) {
            val current = edges[i]
            val next = edges[(i + 1) % edges.size]
            val cross = current.x * next.y - current.y * next.x
            if (cross == 0.0) continue
            if (sign == 0.0) {
                sign = cross
            } else if ((cross > 0) != (sign > 0)) {
                return false
            }
        }
        return sign != 0.0
    }

    /**
     * Shoelace formula in the same local-metre frame `isConvex` projects
     * into: positive for a counterclockwise ring, RFC 7946's rule for an
     * exterior ring. `isConvex` accepts either winding, so this is what
     * actually pins `corridor`'s orientation.
     */
    private fun signedArea(ring: List<WayCoordinate>): Double {
        val first = ring.firstOrNull() ?: return 0.0
        val lonScale = cos(first.lat * Math.PI / 180)
        val points = ring.map { Xy(x = it.lon * lonScale, y = it.lat) }
        var sum = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2
    }

    private fun assertEveryPointInside(what: String, line: List<WayCoordinate>, parts: List<List<WayCoordinate>>) {
        val outside = line.firstOrNull { !WayGeometry.corridorContains(parts, it) }
        if (outside != null) fail("$what $outside outside its own corridor")
    }

    // ---- iOS's tests ----------------------------------------------------------

    @Test
    fun `every part is a closed convex ring`() {
        val line = straight(km = 3.0)
        val parts = WayGeometry.corridor(around = line, halfWidthMeters = 500.0)
        assertEquals("one quad for the simplified two-point line, one square per end", 3, parts.size)
        for (part in parts) {
            assertEquals(5, part.size)
            assertEquals(part.first().lat, part.last().lat, 0.0)
            assertEquals(part.first().lon, part.last().lon, 0.0)
            assertTrue("every part is convex, so no part can self-intersect", isConvex(part))
            assertTrue("RFC 7946 exterior rings wind counterclockwise", signedArea(part) > 0)
        }
    }

    /**
     * `isConvex` above accepts either winding, so it cannot catch a flip to
     * clockwise on its own: this pins `signedArea` itself against a ring
     * whose orientation is known by construction, not derived from `corridor`.
     */
    @Test
    fun `signed area is negative for a hand built clockwise square`() {
        val clockwiseSquare = listOf(
            WayCoordinate(lat = 0.0, lon = 0.0),
            WayCoordinate(lat = 1.0, lon = 0.0),
            WayCoordinate(lat = 1.0, lon = 1.0),
            WayCoordinate(lat = 0.0, lon = 1.0),
            WayCoordinate(lat = 0.0, lon = 0.0),
        )
        assertTrue(signedArea(clockwiseSquare) < 0)
    }

    @Test
    fun `a straight line is covered to half width and not beyond`() {
        val line = straight(km = 3.0)
        val parts = WayGeometry.corridor(around = line, halfWidthMeters = 500.0)
        val mid = line[15]
        assertTrue(WayGeometry.corridorContains(parts, mid))
        assertTrue(WayGeometry.corridorContains(parts, offset(mid, northMeters = 480.0, eastMeters = 0.0)))
        assertFalse(WayGeometry.corridorContains(parts, offset(mid, northMeters = 520.0, eastMeters = 0.0)))
        // The end square reaches half a width past the endpoint; a full width does not.
        assertTrue(WayGeometry.corridorContains(parts, offset(line.last(), northMeters = 0.0, eastMeters = 480.0)))
        assertFalse(WayGeometry.corridorContains(parts, offset(line.last(), northMeters = 0.0, eastMeters = 1_020.0)))
    }

    @Test
    fun `a right angle bend keeps its outer corner and every point on the line`() {
        val lat = 42.0
        val east = straight(km = 2.0, lat = lat)
        val north = (1..20).map { i -> offset(east.last(), northMeters = i.toDouble() * 100, eastMeters = 0.0) }
        val line = east + north
        val parts = WayGeometry.corridor(around = line, halfWidthMeters = 500.0)
        // 300 m outside the bend on the diagonal: inside the vertex square.
        assertTrue(WayGeometry.corridorContains(parts, offset(east.last(), northMeters = -212.0, eastMeters = 212.0)))
        // The vertex square is axis-aligned, so its corner reaches 707 m on
        // the diagonal: 700 m out is still covered, and that generosity at a
        // bend is the price of a part that cannot self-intersect.
        assertTrue(WayGeometry.corridorContains(parts, offset(east.last(), northMeters = -495.0, eastMeters = 495.0)))
        // 520 m on each axis clears the square and both rectangles.
        assertFalse(WayGeometry.corridorContains(parts, offset(east.last(), northMeters = -520.0, eastMeters = 520.0)))
        assertEveryPointInside("route point", line, parts)
    }

    /** The failure the single ring had: a hairpin whose inner offsets crossed. */
    @Test
    fun `a hairpin covers its own points with no self intersecting part`() {
        val lat = 42.0
        val out = straight(km = 1.0, lat = lat)
        val back = (1..10).map { i -> offset(out.last(), northMeters = 60.0, eastMeters = -i.toDouble() * 100) }
        val line = out + back
        val parts = WayGeometry.corridor(around = line, halfWidthMeters = 500.0)
        assertEveryPointInside("hairpin point", line, parts)
        for (part in parts) {
            assertEquals("quads and squares only", 5, part.size)
            assertTrue("every part is convex, so no part can self-intersect", isConvex(part))
        }
    }

    @Test
    fun `simplification drops wiggles under tolerance`() {
        val line = straight(km = 1.0).toMutableList()
        for (i in 1 until line.size step 2) {
            line[i] = offset(line[i], northMeters = 10.0, eastMeters = 0.0)
        }
        assertEquals(2, WayGeometry.simplified(line, toleranceMeters = 25.0).size)
    }

    /**
     * The checked-in `stage-00.json` is a short synthetic stage, not the
     * real Francés day; what matters is that a decoded Way's route goes
     * through the same path a real one will.
     */
    @Test
    fun `a decoded stage corridor covers its whole line including the ends`() {
        val way = PilgrimageWayImporter.way(
            from = PilgrimagePackageHarness.fixture("stage-00.json"),
            routeId = "camino-frances",
            stageIndex = 0,
        )
        val line = way.route.map { WayCoordinate(lat = it.lat, lon = it.lon) }
        val parts = WayGeometry.corridor(around = line, halfWidthMeters = 500.0)
        assertEveryPointInside("route point", line, parts)
    }

    @Test
    fun `one point becomes one square`() {
        val parts = WayGeometry.corridor(around = listOf(WayCoordinate(lat = 42.0, lon = 0.0)), halfWidthMeters = 500.0)
        assertEquals(1, parts.size)
        assertEquals(5, parts[0].size)
    }

    /**
     * A zero-length segment has no perpendicular, so it yields no rectangle,
     * but both its vertices still yield squares, and the point is covered.
     */
    @Test
    fun `two identical points yield squares and no rectangle`() {
        val point = WayCoordinate(lat = 42.0, lon = 0.0)
        val parts = WayGeometry.corridor(around = listOf(point, point), halfWidthMeters = 500.0)
        assertEquals(2, parts.size)
        for (part in parts) {
            assertEquals(5, part.size)
        }
        assertTrue(WayGeometry.corridorContains(parts, point))
    }

    // ---- Android additions (spec D C1 §2–§4, §13.6) ----------------------------

    @Test
    fun `an empty line has no corridor`() {
        assertEquals(
            emptyList<List<WayCoordinate>>(),
            WayGeometry.corridor(around = emptyList(), halfWidthMeters = 500.0),
        )
    }

    @Test
    fun `three identical points simplify to two and give two squares`() {
        val point = WayCoordinate(lat = 42.0, lon = 0.0)
        assertEquals(2, WayGeometry.simplified(listOf(point, point, point), toleranceMeters = 25.0).size)
        assertEquals(2, WayGeometry.corridor(around = listOf(point, point, point), halfWidthMeters = 500.0).size)
    }

    @Test
    fun `fewer than three points pass through simplification as the same list`() {
        val two = listOf(WayCoordinate(lat = 42.0, lon = 0.0), WayCoordinate(lat = 42.0, lon = 0.0))
        assertSame(two, WayGeometry.simplified(two, toleranceMeters = 25.0))
    }

    /** The probe's 200 m line with its midpoint 25 m off is dropped; 26 m is kept. */
    @Test
    fun `a point exactly at the tolerance is dropped and one past it is kept`() {
        val lonScale = 111_320.0 * cos(0.0)
        val start = WayCoordinate(lat = 0.0, lon = 0.0)
        val end = WayCoordinate(lat = 0.0, lon = 200 / lonScale)
        val at25 = WayCoordinate(lat = 25 / 111_320.0, lon = 100 / lonScale)
        val at26 = WayCoordinate(lat = 26 / 111_320.0, lon = 100 / lonScale)
        assertEquals(2, WayGeometry.simplified(listOf(start, at25, end), toleranceMeters = 25.0).size)
        assertEquals(3, WayGeometry.simplified(listOf(start, at26, end), toleranceMeters = 25.0).size)
    }

    @Test
    fun `simplification keeps the input's own points, never projected copies`() {
        val start = WayCoordinate(lat = 0.0, lon = 0.0)
        val odd = WayCoordinate(lat = 0.123456789123, lon = 0.987654321987)
        val end = WayCoordinate(lat = 0.0, lon = 200 / 111_320.0)
        val kept = WayGeometry.simplified(listOf(start, odd, end), toleranceMeters = 25.0)
        assertEquals(3, kept.size)
        assertSame(start, kept[0])
        assertSame(odd, kept[1])
        assertSame(end, kept[2])
    }

    /** First point == last: the whole-line segment has no length, so the distance is to its start. */
    @Test
    fun `a closed loop simplifies through its degenerate segment and still covers its points`() {
        val start = WayCoordinate(lat = 0.0, lon = 0.0)
        val loop = listOf(start, WayCoordinate(lat = 0.01, lon = 0.0), WayCoordinate(lat = 0.01, lon = 0.01), start)
        assertEquals(4, WayGeometry.simplified(loop, toleranceMeters = 25.0).size)
        assertEveryPointInside("loop point", loop, WayGeometry.corridor(around = loop, halfWidthMeters = 500.0))
    }

    @Test
    fun `a line that crosses itself yields only convex parts and covers every point`() {
        val out = straight(km = 1.0)
        val up = (1..5).map { i -> offset(out.last(), northMeters = i.toDouble() * 100, eastMeters = 0.0) }
        val left = (1..5).map { i -> offset(up.last(), northMeters = 0.0, eastMeters = -i.toDouble() * 100) }
        // South across the first leg at 500 m east of the start.
        val down = (1..10).map { i -> offset(left.last(), northMeters = -i.toDouble() * 100, eastMeters = 0.0) }
        val line = out + up + left + down
        val parts = WayGeometry.corridor(around = line, halfWidthMeters = 500.0)
        assertEveryPointInside("crossing point", line, parts)
        for (part in parts) {
            assertEquals(5, part.size)
            assertTrue(isConvex(part))
            assertTrue(signedArea(part) > 0)
        }
    }

    /**
     * The manager tests' stage 0 (31 points along latitude 42), against the
     * Swift probe's `%.17g` output: Swift's operation order reproduces
     * iOS's rings to the bit (C1 §3, §13.6).
     */
    @Test
    fun `the stage fixture's corridor matches iOS to the bit`() {
        val route = (0..30).map { i -> WayCoordinate(lat = 42.0, lon = i * 0.001209) }
        val parts = WayGeometry.corridor(around = route, halfWidthMeters = 500.0)
        val south = 41.995508444125043
        val north = 42.004491555874957
        fun ring(west: Double, east: Double) = listOf(
            WayCoordinate(lat = south, lon = west),
            WayCoordinate(lat = south, lon = east),
            WayCoordinate(lat = north, lon = east),
            WayCoordinate(lat = north, lon = west),
            WayCoordinate(lat = south, lon = west),
        )
        assertEquals(
            listOf(
                ring(west = 0.0, east = 0.036269999999999997),
                ring(west = -0.0060439845921953653, east = 0.0060439845921953653),
                ring(west = 0.030226015407804632, east = 0.042313984592195361),
            ),
            parts,
        )
    }

    @Test
    fun `a ring of three coordinates or fewer contains nothing`() {
        val triangle = listOf(
            WayCoordinate(lat = 0.0, lon = 0.0),
            WayCoordinate(lat = 1.0, lon = 0.5),
            WayCoordinate(lat = 0.0, lon = 1.0),
        )
        assertFalse(WayGeometry.ringContains(triangle, WayCoordinate(lat = 0.2, lon = 0.5)))
        assertFalse(WayGeometry.corridorContains(emptyList(), WayCoordinate(lat = 0.0, lon = 0.0)))
    }

    /** Even-odd, half-open: a point on the west edge is inside, on the east edge outside. */
    @Test
    fun `the ray cast counts the west edge in and the east edge out`() {
        val square = listOf(
            WayCoordinate(lat = 0.0, lon = 0.0),
            WayCoordinate(lat = 0.0, lon = 1.0),
            WayCoordinate(lat = 1.0, lon = 1.0),
            WayCoordinate(lat = 1.0, lon = 0.0),
            WayCoordinate(lat = 0.0, lon = 0.0),
        )
        assertTrue(WayGeometry.ringContains(square, WayCoordinate(lat = 0.5, lon = 0.5)))
        assertTrue(WayGeometry.ringContains(square, WayCoordinate(lat = 0.5, lon = 0.0)))
        assertFalse(WayGeometry.ringContains(square, WayCoordinate(lat = 0.5, lon = 1.0)))
        assertFalse(WayGeometry.ringContains(square, WayCoordinate(lat = 1.5, lon = 0.5)))
    }
}
