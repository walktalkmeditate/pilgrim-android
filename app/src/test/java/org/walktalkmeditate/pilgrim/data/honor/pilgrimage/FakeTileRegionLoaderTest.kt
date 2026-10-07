// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate

/** Port of iOS `FakeTileRegionLoaderTests.swift@7c200bf` (spec D §C2.T), names kept; only the types adapt. */
class FakeTileRegionLoaderTest {

    private val rings = listOf(
        listOf(
            WayCoordinate(lat = 42.0, lon = 0.0), WayCoordinate(lat = 42.0, lon = 0.01),
            WayCoordinate(lat = 42.01, lon = 0.01), WayCoordinate(lat = 42.01, lon = 0.0),
            WayCoordinate(lat = 42.0, lon = 0.0),
        ),
    )

    private val request = TileRegionRequest(
        id = "pilgrimage:camino-frances:0",
        rings = rings,
        corridorHash = "h",
        acceptExpired = true,
    )

    @Test
    fun `a load is recorded and completes into the store`() {
        val fake = FakeTileRegionLoader()
        var result: TileLoadResult<TileRegionSummary>? = null
        fake.loadRegion(request, progress = { _, _ -> }) { result = it }
        assertEquals(listOf(request), fake.regionRequests)
        assertTrue("nothing is stored until the load completes", fake.regions().isEmpty())
        fake.completeNextRegion()
        assertEquals(request.id, (result as TileLoadResult.Success<TileRegionSummary>).value.id)
        assertEquals("h", fake.regions().first().corridorHash)
        assertTrue(fake.regions().first().isComplete)
    }

    @Test
    fun `a failure is delivered once and stores nothing`() {
        val fake = FakeTileRegionLoader()
        var result: TileLoadResult<TileRegionSummary>? = null
        fake.loadRegion(request, progress = { _, _ -> }) { result = it }
        fake.nextRegionFailure = TileRegionLoadingError.DISK_FULL
        fake.completeNextRegion()
        assertEquals(TileLoadResult.Failure(TileRegionLoadingError.DISK_FULL), result)
        assertTrue(fake.regions().isEmpty())
        assertNull("one failure, not a sticky one", fake.nextRegionFailure)
    }

    /** Production always answers a refresh asynchronously; a fake that answered inline would let a test prove a launch sweep ran before the store had spoken. */
    @Test
    fun `refresh regions answers only when released and once`() {
        val fake = FakeTileRegionLoader()
        var answered = 0
        fake.refreshRegions { answered += 1 }
        assertEquals("nothing answers until the store speaks", 0, answered)
        fake.releaseRegions()
        assertEquals(1, answered)
        fake.releaseRegions()
        assertEquals("a completion is delivered once", 1, answered)
    }

    @Test
    fun `seed and remove and packs`() {
        val fake = FakeTileRegionLoader()
        fake.seed(id = "pilgrimage:camino-frances:1", corridorHash = "h", complete = false)
        assertFalse(fake.regions().first().isComplete)
        fake.removeRegion("pilgrimage:camino-frances:1")
        assertTrue(fake.regions().isEmpty())
        assertEquals(listOf("pilgrimage:camino-frances:1"), fake.removedIds)
        assertFalse(fake.hasStylePack(StylePackRequest.LIGHT))
        var packResult: TileLoadResult<Unit>? = null
        fake.loadStylePack(StylePackRequest.LIGHT) { packResult = it }
        fake.completeNextPack()
        assertEquals(TileLoadResult.Success(Unit), packResult)
        assertTrue(fake.hasStylePack(StylePackRequest.LIGHT))
    }
}
