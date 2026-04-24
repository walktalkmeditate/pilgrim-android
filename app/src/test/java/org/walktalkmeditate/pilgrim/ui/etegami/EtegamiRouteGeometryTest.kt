// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.LocationPoint

class EtegamiRouteGeometryTest {

    private fun p(lat: Double, lon: Double) = LocationPoint(
        timestamp = 0L, latitude = lat, longitude = lon,
    )

    @Test
    fun `empty input returns empty list`() {
        assertTrue(EtegamiRouteGeometry.smooth(emptyList()).isEmpty())
    }

    @Test
    fun `single point returns empty list — can't interpolate`() {
        assertTrue(EtegamiRouteGeometry.smooth(listOf(p(0.0, 0.0))).isEmpty())
    }

    @Test
    fun `two points produce subdivision plus endpoint segments`() {
        val points = listOf(p(0.0, 0.0), p(0.01, 0.01))
        val smoothed = EtegamiRouteGeometry.smooth(points, subdivisions = 8)
        // 1 segment × 8 subdivisions + terminal endpoint = 9 points.
        assertEquals(9, smoothed.size)
    }

    @Test
    fun `three points produce two subdivision spans plus endpoint`() {
        val points = listOf(p(0.0, 0.0), p(0.01, 0.01), p(0.02, 0.005))
        val smoothed = EtegamiRouteGeometry.smooth(points, subdivisions = 8)
        assertEquals(2 * 8 + 1, smoothed.size)
    }

    @Test
    fun `projected points stay inside the route draw area bounds`() {
        // Synthetic meandering walk ~ 10 points over ~1 km.
        val points = (0..9).map { i ->
            p(lat = 45.0 + i * 0.0001, lon = -70.0 + (i % 3) * 0.0002)
        }
        val smoothed = EtegamiRouteGeometry.smooth(points)
        assertTrue(smoothed.isNotEmpty())
        smoothed.forEach { s ->
            // X stays within the route area rect (small slack for Catmull-Rom overshoot).
            assertTrue(
                "x=${s.x} out of route-area range",
                s.x >= EtegamiRouteGeometry.AREA_OFFSET_X.toFloat() - 10f &&
                    s.x <= (EtegamiRouteGeometry.AREA_OFFSET_X + EtegamiRouteGeometry.AREA_WIDTH).toFloat() + 10f,
            )
            // Y stays within the available vertical slot (recentered to
            // the midline; extent is at most AREA_HEIGHT / 2 above or below).
            val midY = (EtegamiRouteGeometry.VERTICAL_AVAILABLE_TOP +
                EtegamiRouteGeometry.VERTICAL_AVAILABLE_BOTTOM) / 2f
            val halfSlot = (EtegamiRouteGeometry.VERTICAL_AVAILABLE_BOTTOM -
                EtegamiRouteGeometry.VERTICAL_AVAILABLE_TOP) / 2f
            assertTrue(
                "y=${s.y} out of recentered vertical slot",
                s.y >= midY - halfSlot - 20f && s.y <= midY + halfSlot + 20f,
            )
        }
    }

    @Test
    fun `taper is monotonic increasing from start and decreasing to end`() {
        val points = (0..19).map { p(lat = it * 0.0001, lon = 0.0) }
        val smoothed = EtegamiRouteGeometry.smooth(points)
        assertTrue(smoothed.size >= 3)
        // First point: taper minimal; middle points: taper 1.0; last: minimal
        assertTrue(smoothed.first().taper <= 0.5f)
        assertTrue(smoothed.last().taper <= 0.5f)
        val midIndex = smoothed.size / 2
        assertEquals(1f, smoothed[midIndex].taper, 0.001f)
    }

    @Test
    fun `all taper values stay between 0_15 and 1_0`() {
        val points = (0..9).map { p(lat = it * 0.0001, lon = 0.0) }
        val smoothed = EtegamiRouteGeometry.smooth(points)
        smoothed.forEach { s ->
            assertTrue("taper=${s.taper} below 0.15", s.taper >= 0.15f)
            assertTrue("taper=${s.taper} above 1.0", s.taper <= 1.0f)
        }
    }

    @Test
    fun `smoothing is deterministic — same input produces same output`() {
        val points = listOf(p(45.0, -70.0), p(45.001, -70.001), p(45.002, -70.0005))
        val a = EtegamiRouteGeometry.smooth(points)
        val b = EtegamiRouteGeometry.smooth(points)
        assertEquals(a, b)
    }

    @Test
    fun `width multiplier defaults to 1 (altitude-aware width deferred)`() {
        val points = listOf(p(0.0, 0.0), p(0.001, 0.001))
        val smoothed = EtegamiRouteGeometry.smooth(points)
        smoothed.forEach {
            assertEquals(1.0f, it.widthMultiplier, 0.001f)
        }
    }
}
