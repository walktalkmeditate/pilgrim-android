// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.io.IOException
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageException
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * Stage links in the import coordinator: the cases of iOS's
 * `MainCoordinatorHonorTests.swift:248-649@3b78728` that need no routing
 * (each test names its iOS original), on fakes for the four seams. An
 * await a test must hold is a continuation it resumes, as iOS's
 * `AsyncGate` holds it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HonorImportCoordinatorStageTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val heldShares = mutableMapOf<String, Continuation<Way>>()
    private var installed: String? = null
    private var installedReadFails = false
    private var heldInstalledRead: Continuation<String?>? = null
    private var holdInstalledRead = false
    private var stageWay: Way? = null
    private val stageReads = mutableListOf<String>()
    private var cached: PilgrimageCatalog? = null
    private val loads = mutableListOf<Boolean>()
    private var load: suspend (Boolean) -> PilgrimageCatalog = { throw PilgrimageException(PilgrimageError.CATALOG_UNREACHABLE) }
    private val outcomes = mutableListOf<HonorImportOutcome>()
    private lateinit var imports: HonorImportCoordinator

    private fun TestScope.coordinator(honorEnabled: Boolean = true): HonorImportCoordinator {
        imports = HonorImportCoordinator(
            importShare = { id -> suspendCoroutine { heldShares[id] = it } },
            honorEnabled = honorEnabled,
            scope = backgroundScope,
            media = { error("no stage link gathers here") },
            installedRouteId = {
                if (installedReadFails) throw IOException("route.json could not be read")
                if (holdInstalledRead) suspendCoroutine { heldInstalledRead = it } else installed
            },
            loadStageWay = { id ->
                stageReads += id
                stageWay?.takeIf { id == STAGE_WAY_ID }
            },
            cachedCatalog = { cached },
            loadCatalog = { force ->
                loads += force
                load(force)
            },
        )
        backgroundScope.launch { imports.outcomes.collect { outcomes += it } }
        return imports
    }

    // iOS `testStageLinkForAnInstalledStageOpensItsOverview`.
    @Test
    fun `a stage on the phone opens its overview, with no catalog`() = runTest(dispatcher) {
        installed = ROUTE_ID
        stageWay = stage()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(STAGE_WAY_ID, imports.fetched.value)
        assertNull(imports.routeLink.value)
        assertEquals(listOf(HonorImportOutcome.Listed), outcomes)
        assertTrue("a downloaded stage never waits on the catalog", loads.isEmpty())
    }

    // iOS `testStageLinkForARouteNotOnThePhoneOpensTheRouteFocused`.
    @Test
    fun `a route not on the phone opens its page on the stage, and reads no stage Way`() = runTest(dispatcher) {
        cached = catalog()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(link(focus = INDEX), imports.routeLink.value)
        assertNull(imports.fetched.value)
        assertTrue(stageReads.isEmpty())
    }

    @Test
    fun `another route on the phone opens this route's page, and reads none of the other's stages`() = runTest(dispatcher) {
        installed = "camino-norte"
        cached = catalog()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(link(focus = INDEX), imports.routeLink.value)
        assertTrue(stageReads.isEmpty())
    }

    @Test
    fun `a phone whose installed route can't be read opens the route page`() = runTest(dispatcher) {
        installedReadFails = true
        cached = catalog()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(link(focus = INDEX), imports.routeLink.value)
    }

    // iOS `testStageLinkLoadsTheCatalogWhenNoneIsInHand`.
    @Test
    fun `with no catalog in hand it reaches for the route, loads unforced, then opens the page`() = runTest(dispatcher) {
        var heldLoad: Continuation<PilgrimageCatalog>? = null
        load = { _ -> suspendCoroutine { heldLoad = it } }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        assertEquals(listOf(HonorImportOutcome.ReachingForRoute), outcomes)
        assertEquals(listOf(false), loads)
        checkNotNull(heldLoad).resume(catalog())

        assertEquals(link(focus = INDEX), imports.routeLink.value)
        assertEquals(listOf(HonorImportOutcome.ReachingForRoute, HonorImportOutcome.Listed), outcomes)
    }

    // iOS `testStageLinkForARouteEvenAFreshCatalogLacksSaysSo`.
    @Test
    fun `a route even a fresh catalog lacks is refused after exactly one forced load`() = runTest(dispatcher) {
        cached = catalog(routes = emptyList())
        load = { catalog(routes = emptyList()) }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(listOf(true), loads)
        assertEquals(listOf(HonorImportOutcome.ReachingForRoute, HonorImportOutcome.RouteNotInPilgrim), outcomes)
        assertNull(imports.routeLink.value)
    }

    // iOS `testStageLinkForARouteAddedSinceTheCachedCatalogForcesOneLoad`.
    @Test
    fun `a route added since the cached catalog is found by one forced load`() = runTest(dispatcher) {
        cached = catalog(release = "v1.11.0", routes = emptyList())
        load = { catalog() }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(listOf(true), loads)
        assertEquals(link(focus = INDEX), imports.routeLink.value)
    }

    // iOS `testStageLinkForARouteInTheCachedCatalogNeverLoads`.
    @Test
    fun `a route in the cached catalog never loads`() = runTest(dispatcher) {
        cached = catalog()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertTrue(loads.isEmpty())
        assertEquals(listOf(HonorImportOutcome.Listed), outcomes)
    }

    // iOS `testStageLinkWithNoCatalogInHandLoadsUnforcedThenForcesOnceIfTheRouteIsMissing`.
    @Test
    fun `with no catalog in hand and the route missing, it loads unforced, then forced once, then says so`() = runTest(dispatcher) {
        load = { catalog(routes = emptyList()) }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(listOf(false, true), loads)
        assertEquals(
            listOf(HonorImportOutcome.ReachingForRoute, HonorImportOutcome.ReachingForRoute, HonorImportOutcome.RouteNotInPilgrim),
            outcomes,
        )
    }

    // iOS `testStageLinkWithTheCatalogOutOfReachSaysSo`.
    @Test
    fun `a catalog out of reach says so, and any other load failure reads the same`() = runTest(dispatcher) {
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        assertEquals(
            listOf(HonorImportOutcome.ReachingForRoute, HonorImportOutcome.CatalogFailed(PilgrimageError.CATALOG_UNREACHABLE)),
            outcomes,
        )

        outcomes.clear()
        load = { throw IOException("the cache could not be read") }
        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(
            listOf(HonorImportOutcome.ReachingForRoute, HonorImportOutcome.CatalogFailed(PilgrimageError.CATALOG_UNREACHABLE)),
            outcomes,
        )
        assertNull(imports.routeLink.value)
    }

    // iOS `testStageLinkCancelsAShareImportInFlight`.
    @Test
    fun `a stage link cancels a share import in flight and clears its spinner, and the share lands nothing`() = runTest(dispatcher) {
        cached = catalog()
        coordinator()
        imports.openWay(SHARE_ID)
        assertEquals(HonorImportState.Fetching, imports.state.value)

        imports.openStage(ROUTE_ID, INDEX)
        assertEquals(HonorImportState.Idle, imports.state.value)
        heldShares.getValue(SHARE_ID).resume(share(SHARE_ID))

        assertEquals(link(focus = INDEX), imports.routeLink.value)
        assertNull("the cancelled share import must land nothing", imports.fetched.value)
    }

    // iOS `testAStageLinkReplacedBeforeItsTaskRunsLandsNothing`.
    @Test
    fun `a stage link replaced while it reads the phone lands nothing and says nothing`() = runTest(dispatcher) {
        holdInstalledRead = true
        cached = catalog()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        imports.openWay(SHARE_ID)
        checkNotNull(heldInstalledRead).resume(null)

        assertNull(imports.routeLink.value)
        assertTrue(stageReads.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    // iOS `testACancelledStageLinkLandsNothing`.
    @Test
    fun `a stage link replaced during its catalog load lands nothing`() = runTest(dispatcher) {
        var heldLoad: Continuation<PilgrimageCatalog>? = null
        load = { _ -> suspendCoroutine { heldLoad = it } }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        imports.openWay(SHARE_ID)
        checkNotNull(heldLoad).resume(catalog())

        assertNull("the replaced stage link must land nothing", imports.routeLink.value)
        assertEquals(listOf(HonorImportOutcome.ReachingForRoute), outcomes)
    }

    /** Q8: a `catch (e: Exception)` that swallowed the cancellation would say "the routes are out of reach" for a replaced link. */
    @Test
    fun `a stage link replaced during a load that then fails says nothing`() = runTest(dispatcher) {
        var heldLoad: Continuation<PilgrimageCatalog>? = null
        load = { _ -> suspendCoroutine { heldLoad = it } }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        imports.openWay(SHARE_ID)
        checkNotNull(heldLoad).resumeWithException(PilgrimageException(PilgrimageError.CATALOG_UNREACHABLE))

        assertEquals(listOf(HonorImportOutcome.ReachingForRoute), outcomes)
    }

    // iOS `startWalk` (`MainCoordinatorView.swift:96-98@3b78728`): the walk screen opening cancels the job.
    @Test
    fun `a walk starting cancels a stage resolution, which lands nothing`() = runTest(dispatcher) {
        var heldLoad: Continuation<PilgrimageCatalog>? = null
        load = { _ -> suspendCoroutine { heldLoad = it } }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        imports.cancelImport()
        checkNotNull(heldLoad).resume(catalog())

        assertNull(imports.routeLink.value)
        assertEquals(listOf(HonorImportOutcome.ReachingForRoute), outcomes)
    }

    // iOS `testNewestLinkWinsOverAnOlderShareParkedBehindASummary`, `testNewestLinkWinsWhileTheWaysSheetIsStillClosing`.
    @Test
    fun `the newest request wins - a route link clears a waiting Way, and a Way clears a waiting route link`() = runTest(dispatcher) {
        cached = catalog()
        coordinator()
        imports.openWay(SHARE_ID)
        heldShares.getValue(SHARE_ID).resume(share(SHARE_ID))
        assertEquals("share:$SHARE_ID", imports.fetched.value)

        imports.openStage(ROUTE_ID, INDEX)
        assertEquals(link(focus = INDEX), imports.routeLink.value)
        assertNull(imports.fetched.value)

        imports.openWay(SECOND_ID)
        heldShares.getValue(SECOND_ID).resume(share(SECOND_ID))
        assertEquals("share:$SECOND_ID", imports.fetched.value)
        assertNull(imports.routeLink.value)
    }

    @Test
    fun `consuming a route link takes only the link it names`() = runTest(dispatcher) {
        cached = catalog()
        coordinator()
        imports.openStage(ROUTE_ID, INDEX)

        imports.consumeRouteLink(link(focus = 19))
        assertEquals(link(focus = INDEX), imports.routeLink.value)
        imports.consumeRouteLink(link(focus = INDEX))

        assertNull(imports.routeLink.value)
    }

    // Q3; iOS `testAnOverviewItemSwapDoesNotPromoteAParkedRouteLink`: the outgoing overview's close is no close.
    @Test
    fun `an overview a stage link replaced leaves the stage's state alone as it goes`() = runTest(dispatcher) {
        installed = ROUTE_ID
        stageWay = stage()
        coordinator()
        val outgoing = Any()
        imports.gather(stage().copy(id = "walk:$SOURCE_UUID", source = WaySource.OwnWalk(SOURCE_UUID)), outgoing)

        imports.openStage(ROUTE_ID, INDEX)
        val incoming = Any()
        imports.gather(stage(), incoming)
        imports.overviewClosed(outgoing)

        assertEquals(HonorImportState.Ready, imports.state.value)
    }

    @Test
    fun `with the release flag off a stage link does nothing`() = runTest(dispatcher) {
        cached = catalog()
        coordinator(honorEnabled = false)

        imports.openStage(ROUTE_ID, INDEX)

        assertNull(imports.routeLink.value)
        assertTrue(outcomes.isEmpty())
    }

    private fun catalog(release: String = RELEASE, routes: List<PilgrimageCatalogEntry> = listOf(caminoFrancesEntry)) =
        PilgrimageCatalog(release, routes)

    private fun link(focus: Int?) = HonorRouteLink(caminoFrancesEntry, RELEASE, focus)

    private fun stage() = Way(
        id = STAGE_WAY_ID,
        source = WaySource.Pilgrimage(routeId = ROUTE_ID, stageIndex = INDEX),
        title = "Burgos to Hornillos del Camino",
        departedAt = Instant.parse("2026-08-01T07:00:00Z"),
        tzIdentifier = "Europe/Madrid",
        expires = null,
        route = listOf(WayPoint(42.34, -3.70, 860.0, 0.0), WayPoint(42.33, -3.92, 830.0, 20_000.0)),
        totalDistanceMeters = 20_000.0,
        theirActiveSeconds = 0.0,
        moments = emptyList(),
        weather = null,
    )

    private fun share(id: String) = Way(
        id = "share:$id",
        source = WaySource.Share(id = id, pageUrl = "https://walk.pilgrimapp.org/$id"),
        title = "Rúa do Franco → Obradoiro",
        departedAt = Instant.parse("2026-08-01T07:00:00Z"),
        tzIdentifier = "Europe/Madrid",
        expires = Instant.parse("2099-01-01T00:00:00Z"),
        route = listOf(WayPoint(42.88, -8.545, 250.0, 0.0), WayPoint(42.88, -8.540, 250.0, 400.0)),
        totalDistanceMeters = 408.0,
        theirActiveSeconds = 540.0,
        moments = emptyList(),
        weather = null,
    )

    private companion object {
        const val ROUTE_ID = "camino-frances"
        const val INDEX = 13
        const val STAGE_WAY_ID = "pilgrimage:camino-frances:13"
        const val RELEASE = "v1.12.0"
        const val SHARE_ID = "Qoi4YmPHLN"
        const val SECOND_ID = "Second1234"
        const val SOURCE_UUID = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
    }
}
