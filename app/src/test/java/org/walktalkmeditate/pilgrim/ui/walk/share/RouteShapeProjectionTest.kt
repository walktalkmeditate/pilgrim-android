// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.junit.Test

class RouteShapeProjectionTest {

    private fun point(lat: Double, lon: Double) =
        LocationPoint(timestamp = 0L, latitude = lat, longitude = lon)

    @Test
    fun `fewer than two points yields empty`() {
        assertTrue(projectRoute(emptyList(), 100f, 100f).isEmpty())
        assertTrue(projectRoute(listOf(point(1.0, 1.0)), 100f, 100f).isEmpty())
    }

    @Test
    fun `stationary route with no extent yields empty`() {
        val same = List(5) { point(45.0, -70.0) }
        assertTrue(projectRoute(same, 100f, 100f).isEmpty())
    }

    @Test
    fun `projected points stay within the canvas bounds`() {
        val pts = listOf(
            point(45.000, -70.000),
            point(45.002, -70.003),
            point(45.001, -70.001),
            point(45.003, -70.002),
        )
        val w = 200f
        val h = 120f
        val projected = projectRoute(pts, w, h)
        assertTrue(projected.size >= 2)
        // Endpoints sit exactly on the canvas edges; allow a half-pixel
        // epsilon for float rounding in the centering offset.
        val eps = 0.5f
        for (o in projected) {
            assertTrue(o.x in -eps..(w + eps))
            assertTrue(o.y in -eps..(h + eps))
        }
    }

    @Test
    fun `latitude axis is flipped — northernmost sample maps to the top`() {
        val south = point(45.000, -70.000)
        val north = point(45.004, -70.000)
        val projected = projectRoute(listOf(south, north), 100f, 100f)
        // North (max lat) projects to a smaller y than south.
        val ySouth = projected.first().y
        val yNorth = projected.last().y
        assertTrue(yNorth < ySouth)
    }

    @Test
    fun `route terminates at the true final sample`() {
        // 450 points force downsampling (step = 450/200 = 2); the last
        // index (449, odd) is skipped by the stride and must be
        // re-appended so the polyline ends at the real endpoint.
        val pts = (0 until 450).map { point(45.0 + it * 1e-5, -70.0 + it * 1e-5) }
        val projected = projectRoute(pts, 300f, 300f)
        val expectedLast = projectRoute(listOf(pts[0], pts.last()), 300f, 300f).last()
        // Endpoint x should equal the right edge of the shape (max lon).
        assertEquals(expectedLast.x, projected.last().x, 0.5f)
    }
}
