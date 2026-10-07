// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry

/**
 * Port of iOS `UnitTests/Honor/PilgrimageTilesDescriptorsTests.swift@7c200bf`,
 * all five, names kept (spec D C1 §13.3). Then the Android additions: the
 * Swift probe's per-zoom counts on the long corridor, the part filter, and
 * `tileTouches` missing a tile a thin part crosses, as iOS ships it (C1-D1).
 */
class PilgrimageTilesDescriptorsTest {

    private val longLine = (0..300).map { i -> WayCoordinate(lat = 33.8, lon = 135.5 + i.toDouble() * 0.001) }

    // ---- iOS's tests ----------------------------------------------------------

    /**
     * The SDK downloads whole packs in fixed zoom bands (0–5, 6–10, 11–14,
     * 15–16), not the tiles a corridor touches. Streets ends at 14: the
     * 15–16 band adds building footprints only.
     */
    @Test
    fun `the range is the one band whose packs follow the corridor`() {
        assertEquals(11..14, PilgrimageTilesDescriptors.STREETS_ZOOM)
    }

    /**
     * The estimate counts z11 cells because that is what the store
     * downloads; the range has to start on that band's root or the count
     * speaks for packs the region never pulls.
     */
    @Test
    fun `the pack root is the range's floor`() {
        assertEquals(11, PilgrimageTilesDescriptors.PACK_ROOT_ZOOM)
        assertEquals(PilgrimageTilesDescriptors.PACK_ROOT_ZOOM, PilgrimageTilesDescriptors.STREETS_ZOOM.first)
    }

    /**
     * Version 1 saved regions from z0 with the DEM named. The version is
     * hashed into every corridor so those read as unsaved; Android keeps 2
     * though no Android phone ever held a version-1 region (C1 §1).
     */
    @Test
    fun `the region version moved past the descriptors that shipped first`() {
        assertEquals(2, PilgrimageTilesDescriptors.REGION_VERSION)
    }

    @Test
    fun `glyphs rasterize ideographs locally`() {
        assertTrue(PilgrimageTilesDescriptors.RASTERIZES_IDEOGRAPHS_LOCALLY)
    }

    @Test
    fun `tile count grows fourfold per zoom on a long corridor`() {
        val rings = WayGeometry.corridor(around = longLine, halfWidthMeters = 500.0)
        val z13 = PilgrimageTilesDescriptors.tileCount(rings, zooms = 13..13)
        val z15 = PilgrimageTilesDescriptors.tileCount(rings, zooms = 15..15)
        assertTrue(z13 > 5)
        assertEquals(4.0, z15.toDouble() / z13.toDouble(), 1.5)
    }

    // ---- Android additions (spec D C1 §5) --------------------------------------

    /** The Swift probe's counts for the same corridor; a closed range sums its zooms. */
    @Test
    fun `the long corridor counts what iOS counts at every zoom`() {
        val rings = WayGeometry.corridor(around = longLine, halfWidthMeters = 500.0)
        val perZoom = (11..15).map { PilgrimageTilesDescriptors.tileCount(rings, zooms = it..it) }
        assertEquals(listOf(3, 10, 16, 30, 58), perZoom)
        assertEquals(59, PilgrimageTilesDescriptors.tileCount(rings, zooms = PilgrimageTilesDescriptors.STREETS_ZOOM))
    }

    @Test
    fun `no part of more than three coordinates counts nothing`() {
        val triangle = listOf(
            WayCoordinate(lat = 42.0, lon = 0.0),
            WayCoordinate(lat = 42.1, lon = 0.05),
            WayCoordinate(lat = 42.0, lon = 0.1),
        )
        assertEquals(0, PilgrimageTilesDescriptors.tileCount(emptyList(), zooms = 11..14))
        assertEquals(0, PilgrimageTilesDescriptors.tileCount(listOf(triangle, emptyList()), zooms = 11..14))
    }

    /**
     * At z2 a 0.01°-wide strip from (62, −10) to (70, 10) crosses three
     * tiles: (1, 1), where it starts; (2, 1), through that tile's
     * north-west corner region; and (2, 0), where it ends. In (2, 1) it
     * holds no probe (the corner it passes is 0.5° off the line, the centre
     * is at 41°N 45°E) and none of its vertices, so `tileTouches` misses
     * the tile, though its comment says the estimate errs high. Matched as
     * shipped and filed (pilgrim-ios #124 item 12, C1-D1).
     */
    @Test
    fun `a thin part that crosses a tile with no probe or vertex inside it is missed, as iOS ships it`() {
        val start = WayCoordinate(lat = 62.0, lon = -10.0)
        val end = WayCoordinate(lat = 70.0, lon = 10.0)
        val halfWidth = 0.005
        val dLon = end.lon - start.lon
        val dLat = end.lat - start.lat
        val length = sqrt(dLon * dLon + dLat * dLat)
        val normalLon = -dLat / length * halfWidth
        val normalLat = dLon / length * halfWidth
        fun shifted(point: WayCoordinate, side: Double) =
            WayCoordinate(lat = point.lat + side * normalLat, lon = point.lon + side * normalLon)
        val strip = listOf(
            shifted(start, -1.0),
            shifted(end, -1.0),
            shifted(end, 1.0),
            shifted(start, 1.0),
            shifted(start, -1.0),
        )
        assertEquals(2, PilgrimageTilesDescriptors.tileCount(listOf(strip), zooms = 2..2))
    }
}
