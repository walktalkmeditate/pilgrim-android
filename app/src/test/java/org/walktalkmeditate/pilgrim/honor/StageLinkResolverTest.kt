// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry

internal val caminoFrancesEntry = PilgrimageCatalogEntry(
    id = "camino-frances",
    name = "Camino Francés",
    names = emptyMap(),
    country = "ES",
    region = "Europe",
    distanceKm = 780.0,
    tradition = "christian",
    stageCount = 33,
    bytes = 1_000,
    placesPerStage = 0.0,
    sparse = false,
)

/** Port of iOS `StageLinkResolverTests.swift@3b78728`, the ten cases with their names. */
class StageLinkResolverTest {

    private val catalog = PilgrimageCatalog(release = "v1.12.0", routes = listOf(caminoFrancesEntry))

    private fun resolve(
        routeId: String = "camino-frances",
        index: Int = 13,
        installed: String? = null,
        wayExists: Boolean = false,
        catalog: PilgrimageCatalog?,
    ): StageLinkOutcome = StageLinkResolver.resolve(routeId, index, installed, wayExists, catalog)

    private fun sheet(focus: Int?) = StageLinkOutcome.RouteSheet(HonorRouteLink(caminoFrancesEntry, "v1.12.0", focus))

    @Test
    fun `installed with the stage opens the overview`() {
        assertEquals(
            StageLinkOutcome.Overview("pilgrimage:camino-frances:13"),
            resolve(installed = "camino-frances", wayExists = true, catalog = catalog),
        )
    }

    @Test
    fun `installed with the stage needs no catalog`() {
        assertEquals(
            StageLinkOutcome.Overview("pilgrimage:camino-frances:13"),
            resolve(installed = "camino-frances", wayExists = true, catalog = null),
        )
    }

    @Test
    fun `installed but the stage missing opens the route focused`() {
        assertEquals(sheet(focus = 13), resolve(installed = "camino-frances", wayExists = false, catalog = catalog))
    }

    @Test
    fun `not installed opens the route focused`() {
        assertEquals(sheet(focus = 13), resolve(catalog = catalog))
    }

    @Test
    fun `another route installed opens the route focused`() {
        assertEquals(
            "Replace is the route page's question to ask, not the link's",
            sheet(focus = 13),
            resolve(installed = "camino-norte", catalog = catalog),
        )
    }

    @Test
    fun `the last stage opens the route focused on it`() {
        assertEquals(sheet(focus = 32), resolve(index = 32, catalog = catalog))
    }

    @Test
    fun `a stage past the end opens the route unfocused`() {
        assertEquals(sheet(focus = null), resolve(index = 33, catalog = catalog))
    }

    @Test
    fun `a route absent from the catalog says so`() {
        assertEquals(StageLinkOutcome.NotInCatalog, resolve(routeId = "camino-imaginario", catalog = catalog))
    }

    @Test
    fun `no catalog in hand asks for one`() {
        assertEquals(StageLinkOutcome.NeedsCatalog, resolve(catalog = null))
    }

    @Test
    fun `focus is part of the sheet identity`() {
        val fourteen = HonorRouteLink(caminoFrancesEntry, "v1.12.0", focus = 13)
        val twenty = HonorRouteLink(caminoFrancesEntry, "v1.12.0", focus = 19)
        assertNotEquals(fourteen.id, twenty.id)
        assertNotEquals(HonorRouteLink(caminoFrancesEntry, "v1.12.0", focus = null).id, fourteen.id)
        assertEquals("camino-frances:13", fourteen.id)
        assertEquals("camino-frances:-", HonorRouteLink(caminoFrancesEntry, "v1.12.0", focus = null).id)
    }
}
