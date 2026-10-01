// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The watermark's fit (iOS `drawGhostRoute`, `SealRenderer.swift:113-164@7c200bf`,
 * parity spec G §6): one square box in raw degrees over both lines, its
 * midpoint on the centre, its longer side `2 × 0.7 × 0.44` of the seal,
 * north up. Unit coordinates: multiply by the canvas size to draw.
 */
class SealWatermarkTest {

    private fun p(lat: Double, lon: Double) = SealRoutePoint(latitude = lat, longitude = lon)

    private fun FloatArray.point(i: Int) = this[i * 2] to this[i * 2 + 1]

    private val longerSide = 2 * 0.7f * 0.44f

    @Test
    fun `a walk with fewer than two samples gets neither line`() {
        assertNull(SealWatermark.of(walk = emptyList(), way = null))
        assertNull(SealWatermark.of(walk = listOf(p(1.0, 1.0)), way = listOf(p(0.0, 0.0), p(0.0, 0.01))))
    }

    @Test
    fun `the walk's line spans the fit, centred, north up`() {
        val watermark = SealWatermark.of(walk = listOf(p(40.0, 10.0), p(40.0, 10.01)), way = null)!!
        assertNull(watermark.wayLine)
        val (x0, y0) = watermark.walkLine.point(0)
        val (x1, y1) = watermark.walkLine.point(1)
        assertEquals(0.5f - longerSide / 2, x0, 1e-5f)
        assertEquals(0.5f + longerSide / 2, x1, 1e-5f)
        assertEquals(0.5f, y0, 1e-5f)
        assertEquals(0.5f, y1, 1e-5f)

        val north = SealWatermark.of(walk = listOf(p(40.0, 10.0), p(40.01, 10.0)), way = null)!!
        assertEquals("north is up: the later, northern point sits higher", 0.5f - longerSide / 2, north.walkLine.point(1).second, 1e-5f)
    }

    @Test
    fun `the fit is in raw degrees with no cos-latitude correction`() {
        val square = SealWatermark.of(walk = listOf(p(60.0, 10.0), p(60.01, 10.01)), way = null)!!
        val (x0, y0) = square.walkLine.point(0)
        val (x1, y1) = square.walkLine.point(1)
        assertEquals("a degree of longitude draws as long as a degree of latitude at 60°", x1 - x0, y0 - y1, 1e-5f)
    }

    @Test
    fun `both lines share one fit, so the drift between them survives`() {
        // The Way runs 0.01° east along lat 0; the walk honored half of it, 0.002° north.
        val watermark = SealWatermark.of(
            walk = listOf(p(0.002, 0.0), p(0.002, 0.005)),
            way = listOf(p(0.0, 0.0), p(0.0, 0.01)),
        )!!
        val way = watermark.wayLine!!
        val scale = longerSide / 0.01f
        assertEquals(0.5f - longerSide / 2, way.point(0).first, 1e-5f)
        assertEquals(0.5f + longerSide / 2, way.point(1).first, 1e-5f)
        assertEquals("the Way sits south of the box's middle", 0.5f + 0.001f * scale, way.point(0).second, 1e-5f)
        assertEquals("the walk starts where the Way does", way.point(0).first, watermark.walkLine.point(0).first, 1e-5f)
        assertEquals("and stops at its middle", 0.5f, watermark.walkLine.point(1).first, 1e-5f)
        assertEquals("the walk sits north of it", 0.5f - 0.001f * scale, watermark.walkLine.point(0).second, 1e-5f)
    }

    @Test
    fun `a Way of fewer than two points is dropped, and the walk fits alone`() {
        val alone = SealWatermark.of(walk = listOf(p(0.0, 0.0), p(0.0, 0.01)), way = null)
        val withStub = SealWatermark.of(walk = listOf(p(0.0, 0.0), p(0.0, 0.01)), way = listOf(p(5.0, 5.0)))
        assertNull(withStub!!.wayLine)
        assertEquals(alone, withStub)
    }

    @Test
    fun `a walk that never moved floors its span and sits at the centre`() {
        val still = SealWatermark.of(walk = listOf(p(1.0, 1.0), p(1.0, 1.0)), way = null)!!
        assertEquals(0.5f to 0.5f, still.walkLine.point(0))
        assertEquals(0.5f to 0.5f, still.walkLine.point(1))
    }

    @Test
    fun `equality is by content, and the Way's line takes part`() {
        val walk = listOf(p(0.0, 0.0), p(0.0, 0.01))
        val way = listOf(p(0.001, 0.0), p(0.001, 0.01))
        assertEquals(SealWatermark.of(walk, way), SealWatermark.of(walk.toList(), way.toList()))
        assertEquals(SealWatermark.of(walk, way).hashCode(), SealWatermark.of(walk, way).hashCode())
        assertNotEquals(SealWatermark.of(walk, way), SealWatermark.of(walk, null))
    }

    @Test
    fun `the stroke is one unit at iOS's 512 render, and the Way's line fainter than the walk's`() {
        assertEquals(1f, SealWatermark.STROKE_FRACTION * 512f, 1e-6f)
        assertEquals(0.03f, SealWatermark.WAY_ALPHA, 0f)
        assertEquals(0.055f, SealWatermark.WALK_ALPHA, 0f)
    }

    @Test
    fun `the watermark is no part of the seal's hash`() {
        val spec = SealSpec(
            uuid = "seal-hash",
            startMillis = 1_700_000_000_000L,
            distanceMeters = 1_234.0,
            durationSeconds = 3_600.0,
            displayDistance = "1.2",
            unitLabel = "km",
            ink = androidx.compose.ui.graphics.Color.Black,
        )
        val marked = spec.copy(watermark = SealWatermark.of(listOf(p(0.0, 0.0), p(0.0, 0.01)), null))
        assertNotNull(marked.watermark)
        assertEquals(fnv1aHash(spec), fnv1aHash(marked))
        assertEquals(sealGeometry(spec).rotationDeg, sealGeometry(marked).rotationDeg, 0f)
    }
}
