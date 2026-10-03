// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.HonorStageOutcome
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCopy
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.NORTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.RELEASE
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.ROUTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.WALKED_AT
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.awaitBlocking
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.waitUntil
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRoute
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRouteStage
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours

/**
 * The route page (pilgrimage-stage spec P4 §4): the six `PilgrimageRouteModel`
 * cases of iOS's `PilgrimageCatalogModelTests` (`PilgrimageCatalogServiceTests.swift@7c200bf`,
 * names kept), then the spec's additions: the next row's rule in its
 * order, the progress line's three forms, "stage d of n", the summary's
 * fallback, and the ViewModel against the real manager and catalog service.
 * The guard refusing on entry and flipping before the commit, a page
 * opened mid-download showing its live phase and keeping its opening state
 * after the commit (pilgrim-ios #121, matched), process-death restore, the
 * redraw notice shown once, the in-flight flag, the stage taps, Replace,
 * Update, Remove, and a download that outlives its page. Robolectric for
 * the strings; waits are wall-clock, as the manager runs on real threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageRouteViewModelTest {

    @get:Rule val folder = TemporaryFolder()

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private lateinit var world: PilgrimageScreensWorld

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        world = PilgrimageScreensWorld(folder.root)
    }

    @After
    fun tearDown() {
        world.close()
        Dispatchers.resetMain()
    }

    // ---- iOS PilgrimageCatalogModelTests: the route page's model ----

    private fun stage(index: Int) = PilgrimageRouteStage(
        index = index,
        name = "Saint-Jean-Pied-de-Port to Roncesvalles",
        distanceKm = 24.2,
        gainMeters = 1_419.0,
        hours = WayStageHours(min = 7.0, max = 9.0),
        difficulty = "hard",
    )

    private val arrived = HonorStageOutcome(progressFrac = 1.0, arrived = true)

    @Test
    fun `stage line reads distance, climb, hours and difficulty`() {
        val line = PilgrimageRouteModel.stageLine(resources, stage(0), UnitSystem.Metric)
        assertTrue(line, line.startsWith(StageFormat.distance(24_200.0, UnitSystem.Metric)))
        assertTrue(line, line.contains(StageFormat.altitude(1_419.0, UnitSystem.Metric)))
        assertTrue(line, line.contains("7 to 9 hours"))
        assertTrue(line, line.endsWith("hard"))
    }

    @Test
    fun `a stage with one hour figure does not say it twice`() {
        val single = PilgrimageRouteStage(0, "n", 10.0, 40.0, WayStageHours(min = 4.0, max = 4.0), "easy")
        assertTrue(PilgrimageRouteModel.stageLine(resources, single, UnitSystem.Metric).contains("4 hours"))
        assertFalse(PilgrimageRouteModel.stageLine(resources, single, UnitSystem.Metric).contains("4 to 4"))
    }

    /** One formatter, two callers: the stage list and the morning card must not drift apart, and a non-finite figure never traps. */
    @Test
    fun `the stage facts formatter is the one both callers use`() {
        val facts = WayStageFacts.line(resources, 24.2, 1_419.0, WayStageHours(7.0, 9.0), "hard", UnitSystem.Metric)
        assertEquals(PilgrimageRouteModel.stageLine(resources, stage(0), UnitSystem.Metric), facts)
        assertEquals(
            "an empty difficulty adds no trailing separator",
            "${StageFormat.distance(10_000.0, UnitSystem.Metric)} · ${StageFormat.altitude(0.0, UnitSystem.Metric)} up · 4 hours",
            WayStageFacts.line(resources, 10.0, 0.0, WayStageHours(4.0, 4.0), "", UnitSystem.Metric),
        )
        assertTrue(
            "clamped, never trapped",
            WayStageFacts.line(resources, 10.0, 40.0, WayStageHours(Double.NaN, Double.POSITIVE_INFINITY), "easy", UnitSystem.Metric)
                .contains("0 to 100 hours"),
        )
    }

    @Test
    fun `the next row offers resumes and finally congratulates`() {
        var led = PilgrimageLedger(routeId = ROUTE_ID)
        assertEquals("start with stage 1", nextRow(ledger = null, stageCount = 33))
        led = led.recorded(0, "a", 24.2, arrived, Instant.now())
        assertEquals("next: stage 2", nextRow(led, 33))
        led = led.recorded(1, "b", 21.9, HonorStageOutcome(progressFrac = 0.58, arrived = false), Instant.now())
        assertEquals("continue from where you stopped", nextRow(led, 33))
        for (index in 1 until 33) {
            led = led.recorded(index, "s", 20.0, arrived, Instant.now())
        }
        assertEquals("you have walked the whole way", nextRow(led, 33))
    }

    @Test
    fun `the button says what it will do`() {
        assertEquals("Download", PilgrimageRouteModel.buttonLabel(resources, isInstalled = false, hasUpdate = false))
        assertEquals("Update", PilgrimageRouteModel.buttonLabel(resources, isInstalled = true, hasUpdate = true))
        assertEquals("On your phone", PilgrimageRouteModel.buttonLabel(resources, isInstalled = true, hasUpdate = false))
    }

    @Test
    fun `the redraw notice is the spec's words`() {
        assertEquals("the route's stages were redrawn; your kilometres are kept.", PilgrimageRouteModel.redrawNotice(resources))
    }

    // ---- The spec's additions: the model ----

    /** P4 §4.4, correction 14: the rule's order, which iOS's own test leaves open. */
    @Test
    fun `a begun stage 1 reads continue, at any fraction, and a stage 1 never begun reads start, whatever came after`() {
        val partway = PilgrimageLedger(ROUTE_ID).recorded(0, "a", 24.2, HonorStageOutcome(progressFrac = 0.3, arrived = false), WALKED_AT)
        assertEquals("continue from where you stopped", nextRow(partway, 33))

        val atTheStart = PilgrimageLedger(ROUTE_ID).recorded(0, "a", 24.2, HonorStageOutcome(progressFrac = 0.0, arrived = false), WALKED_AT)
        assertEquals("a stop at 0 is a stage begun", 0.0, atTheStart.stages.getValue("0").stoppedAtFrac)
        assertEquals("continue from where you stopped", nextRow(atTheStart, 33))

        val later = PilgrimageLedger(ROUTE_ID).recorded(4, "e", 20.0, arrived, WALKED_AT)
        assertEquals("start with stage 1", nextRow(later, 33))

        assertEquals("no stages is the whole way", "you have walked the whole way", nextRow(ledger = null, stageCount = 0))
    }

    @Test
    fun `the next row opens the next stage, and the first once every stage is walked`() {
        val both = PilgrimageLedger(ROUTE_ID).recorded(0, "a", 24.2, arrived, WALKED_AT).recorded(1, "b", 21.9, arrived, WALKED_AT)
        val half = PilgrimageLedger(ROUTE_ID).recorded(0, "a", 24.2, arrived, WALKED_AT)
        val route = route(stages = listOf(stage(0), stage(1).copy(index = 1)))

        assertEquals(1, PilgrimageRouteModel.nextIndex(page(ledger = half, route = route)))
        assertEquals(0, PilgrimageRouteModel.nextIndex(page(ledger = both, route = route)))
        assertEquals(0, PilgrimageRouteModel.nextIndex(page(ledger = null, route = null)))
        assertNull("nothing walked or listed opens nothing", PilgrimageRouteModel.nextIndex(page(ledger = both, route = null)))
    }

    /** P4 §3.1's three forms, in the stage formatter's figures. */
    @Test
    fun `the progress line reads the count, the stage you are on, or the whole way`() {
        val distance = { meters: Double -> StageFormat.distance(meters, UnitSystem.Metric) }
        val one = PilgrimageLedger(ROUTE_ID).recorded(0, "a", 24.2, arrived, WALKED_AT)
        val both = one.recorded(1, "b", 21.9, arrived, WALKED_AT)

        assertEquals("33 stages", PilgrimageLedger.progressLine(resources, ledger = null, stageCount = 33, distance))
        assertEquals("stage 2 of 33 · 24.2 km walked", PilgrimageLedger.progressLine(resources, one, stageCount = 33, distance))
        assertEquals("you have walked the whole way · 46.1 km", PilgrimageLedger.progressLine(resources, both, stageCount = 2, distance))
    }

    /** P4 §4.3's table: both sides drop `route.json`; a failed phase is never drawn. */
    @Test
    fun `the footer counts stages from the manager's phase`() {
        fun progress(phase: PilgrimagePackageManager.Phase) = PilgrimageRouteModel.downloadProgress(resources, phase)

        assertEquals("stage 0 of 33", progress(PilgrimagePackageManager.Phase.Downloading(done = 0, total = 34)))
        assertEquals("stage 0 of 33", progress(PilgrimagePackageManager.Phase.Downloading(done = 1, total = 34)))
        assertEquals("stage 5 of 33", progress(PilgrimagePackageManager.Phase.Downloading(done = 6, total = 34)))
        assertEquals("stage 33 of 33", progress(PilgrimagePackageManager.Phase.Downloading(done = 34, total = 34)))
        assertNull(progress(PilgrimagePackageManager.Phase.Idle))
        assertNull(progress(PilgrimagePackageManager.Phase.Failed(PilgrimageError.INCOMPLETE)))
    }

    /** P4 §4.2, pilgrim-ios #121: the fallback capitalizes as Foundation does, and keeps its separator with no region. */
    @Test
    fun `the summary is the route's, else its tradition and region`() {
        val withSummary = route(stages = emptyList()).copy(summary = "The Way of St James.")
        assertEquals("The Way of St James.", PilgrimageRouteModel.summary(resources, page(route = withSummary)))
        assertEquals("Christian · Europe", PilgrimageRouteModel.summary(resources, page(route = null)))
        assertEquals(
            "Buddhist · ",
            PilgrimageRouteModel.summary(resources, page(route = null, entry = entry.copy(tradition = "buddhist", region = null))),
        )
        assertNull(PilgrimageRouteModel.summary(resources, page(route = null, entry = entry.copy(tradition = null))))
    }

    /** Probed on macOS 26: Foundation's `capitalized`, rows a word splitter on spaces alone would miss. */
    @Test
    fun `capitalized follows Foundation`() {
        mapOf(
            "christian" to "Christian",
            "shinto-buddhist" to "Shinto-Buddhist",
            "CAMINO" to "Camino",
            "o'brien" to "O'brien",
            "rock’n’roll" to "Rock’n’roll",
            "1st" to "1St",
            "a_b" to "A_B",
            "mixed  two" to "Mixed  Two",
            "aΣΣ" to "Aσσ",
            "ßtraße" to "Sstraße",
            "éCOLE" to "École",
            "" to "",
        ).forEach { (text, expected) -> assertEquals(text, expected, PilgrimageRouteModel.capitalized(text)) }
    }

    @Test
    fun `busy is any download running, or this page's own action`() {
        val downloading = PilgrimagePackageManager.Phase.Downloading(done = 1, total = 3)
        assertTrue(PilgrimageRouteModel.isBusy(downloading, actionInFlight = false))
        assertTrue(PilgrimageRouteModel.isBusy(PilgrimagePackageManager.Phase.Idle, actionInFlight = true))
        assertFalse(PilgrimageRouteModel.isBusy(PilgrimagePackageManager.Phase.Idle, actionInFlight = false))
        assertFalse(PilgrimageRouteModel.isBusy(PilgrimagePackageManager.Phase.Failed(PilgrimageError.INCOMPLETE), actionInFlight = false))
    }

    // ---- The spec's additions: the ViewModel ----

    @Test
    fun `a page opened from the held catalog takes its entry and release at once, and previews its stages`() {
        world.holdCatalog()

        val vm = world.routeViewModel()

        val opening = pageOf(vm)
        assertEquals(ROUTE_ID, opening.entry.id)
        assertEquals(RELEASE, opening.release)
        val page = settled(vm)
        assertFalse(page.isInstalled)
        assertEquals(listOf(0, 1), page.stages.map { it.index })
        assertEquals("Download", PilgrimageRouteModel.buttonLabel(resources, page.isInstalled, page.hasUpdate))
    }

    /** P4 correction 5: no door reaches a route page mid-walk, so the refusing guard is the test's. */
    @Test
    fun `a guard that refuses reads finish your walk first, in the footer`() {
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)
        world.harness.signals.screenUp = true

        vm.onDownloadTapped()

        val page = settled(vm)
        assertEquals(PilgrimageError.WALK_IN_PROGRESS, page.failure)
        assertEquals("finish your walk first", resources.getString(PilgrimageCopy.line(page.failure!!)))
        assertNull("not an alert", vm.alert.value)
        assertNull(runBlocking { world.manager.installed() })
    }

    @Test
    fun `a walk begun while the stages stream is refused at the commit, and nothing is installed`() {
        val hold = world.harness.hold("stage-01.json")
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)

        vm.onDownloadTapped()
        hold.awaitArrival()
        world.harness.signals.screenUp = true
        hold.release()

        waitUntil("the refusal lands") { pageOf(vm).failure != null && !pageOf(vm).actionInFlight }
        assertEquals(PilgrimageError.WALK_IN_PROGRESS, pageOf(vm).failure)
        assertNull(runBlocking { world.manager.installed() })
        assertFalse(pageOf(vm).isInstalled)
    }

    /** P4 §4.10 step 4: the progress is the manager's, so any page shows any download from its first frame. */
    @Test
    fun `a page opened while a download runs shows its live phase, and its button and overflow are held`() {
        val hold = world.harness.hold("stage-01.json")
        val download = world.manager.download(world.harness.entry, RELEASE)
        hold.awaitArrival()
        world.holdCatalog()

        val vm = world.routeViewModel()

        assertEquals(PilgrimagePackageManager.Phase.Downloading(done = 2, total = 3), vm.phase.value)
        assertEquals("stage 1 of 2", PilgrimageRouteModel.downloadProgress(resources, vm.phase.value))
        val page = settled(vm)
        assertTrue(PilgrimageRouteModel.isBusy(vm.phase.value, page.actionInFlight))
        vm.onDownloadTapped()
        vm.onRemoveTapped()
        assertNull(vm.alert.value)
        assertFalse(pageOf(vm).actionInFlight)
        hold.release()
        download.awaitBlocking()
    }

    /** P4 §4.10 step 5, pilgrim-ios #121 item 5: nothing reloads when the phase ends. */
    @Test
    fun `a page opened mid-download keeps what it read on opening after the commit, as iOS's does`() {
        val hold = world.harness.hold("stage-01.json")
        val download = world.manager.download(world.harness.entry, RELEASE)
        hold.awaitArrival()
        world.holdCatalog()
        val vm = world.routeViewModel()
        assertNull("the download hasn't committed", settled(vm).installed)

        hold.release()
        download.awaitBlocking()

        waitUntil("the phase is idle") { vm.phase.value == PilgrimagePackageManager.Phase.Idle }
        assertNotNull(runBlocking { world.manager.installed() })
        val page = pageOf(vm)
        assertNull("still the opening's state", page.installed)
        assertEquals("Download", PilgrimageRouteModel.buttonLabel(resources, page.isInstalled, page.hasUpdate))
        vm.open(0)
        waitUntil("a stage asks for the download") { vm.alert.value == PilgrimageRouteAlert.DOWNLOAD_FIRST }
    }

    /** P4 §4.11, A-3: the page comes back from its id in a new process, the phase idle, the notice it had shown still up. */
    @Test
    fun `after process death the page restores from its id, idle, with the redraw notice it had shown`() {
        world.holdCatalog()
        world.newProcessCatalogService()
        val saved = SavedStateHandle(
            mapOf(PilgrimageRouteViewModel.ARG_ROUTE_ID to ROUTE_ID, PilgrimageRouteViewModel.KEY_REDRAW_NOTICE to true),
        )

        val vm = world.routeViewModel(saved = saved)

        val page = settled(vm)
        assertEquals(ROUTE_ID, page.entry.id)
        assertEquals(RELEASE, page.release)
        assertTrue(page.showRedrawNotice)
        assertEquals(PilgrimagePackageManager.Phase.Idle, vm.phase.value)
        assertFalse(PilgrimageRouteModel.isBusy(vm.phase.value, page.actionInFlight))
    }

    @Test
    fun `a route the catalog no longer lists closes the page, held, restored, or out of reach`() {
        world.holdCatalog()
        assertEquals(PilgrimageRouteUiState.Gone, world.routeViewModel(routeId = NORTE_ID).state.value)

        world.newProcessCatalogService()
        val restored = world.routeViewModel(routeId = NORTE_ID)
        waitUntil("the catalog is read") { restored.state.value != PilgrimageRouteUiState.Resolving }
        assertEquals(PilgrimageRouteUiState.Gone, restored.state.value)

        File(world.catalogDirectory, "catalog.json").delete()
        world.unstubIndex()
        world.newProcessCatalogService()
        val unreachable = world.routeViewModel()
        waitUntil("the catalog is tried") { unreachable.state.value != PilgrimageRouteUiState.Resolving }
        assertEquals(PilgrimageRouteUiState.Gone, unreachable.state.value)
    }

    /** P4 §4.3: shown at the reload that finds it, cleared in the file at once, and kept in the page's saved state. */
    @Test
    fun `the redraw notice shows once and is cleared at once`() {
        world.harness.ledgers.save(PilgrimageLedger(ROUTE_ID).recorded(0, "a", 24.2, arrived, WALKED_AT).copy(redrawNoticePending = true))
        world.holdCatalog()
        val saved = SavedStateHandle(mapOf(PilgrimageRouteViewModel.ARG_ROUTE_ID to ROUTE_ID))

        val first = world.routeViewModel(saved = saved)

        assertTrue(settled(first).showRedrawNotice)
        assertNull(world.harness.ledgers.load(ROUTE_ID)?.redrawNoticePending)
        assertEquals(true, saved.get<Boolean>(PilgrimageRouteViewModel.KEY_REDRAW_NOTICE))
        assertFalse("a later opening never says it", settled(world.routeViewModel()).showRedrawNotice)
    }

    /**
     * Android's own flag: between the tap and the phase, the guard reads the
     * database, and a second tap there would be refused as a second
     * download, "the download didn't finish", while the first carries on.
     */
    @Test
    fun `a quick second tap is held by the page's own in-flight flag`() {
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)
        val guardHeld = CountDownLatch(1)
        val guardReads = AtomicInteger()
        world.harness.signals.onCheck = {
            guardReads.incrementAndGet()
            guardHeld.await(10, TimeUnit.SECONDS)
        }

        vm.onDownloadTapped()
        waitUntil("the guard is reading") { guardReads.get() == 1 }
        assertEquals("the phase hasn't moved yet", PilgrimagePackageManager.Phase.Idle, vm.phase.value)
        assertTrue(PilgrimageRouteModel.isBusy(vm.phase.value, pageOf(vm).actionInFlight))
        vm.onDownloadTapped()
        guardHeld.countDown()

        waitUntil("the download lands") { pageOf(vm).isInstalled && !pageOf(vm).actionInFlight }
        assertNull(pageOf(vm).failure)
        runBlocking { world.manager.installed() }
        assertEquals("one download: its entry check and its commit check", 2, guardReads.get())
    }

    /** P4 §4.8, pilgrim-ios #119 item 3: no busy check; an installed stage whose Way won't read asks for a download too. */
    @Test
    fun `a stage opens its overview only when installed and its Way reads, and asks for the download otherwise`() {
        world.holdCatalog()
        val notInstalled = world.routeViewModel()
        settled(notInstalled)
        notInstalled.open(0)
        waitUntil("Download this route first?") { notInstalled.alert.value == PilgrimageRouteAlert.DOWNLOAD_FIRST }

        world.install()
        val vm = world.routeViewModel()
        assertTrue(settled(vm).isInstalled)
        assertEquals(WayStore.stageWayId(ROUTE_ID, 1), openedBy(vm) { vm.open(1) })

        File(world.harness.waysDir, WayStore.stageWayId(ROUTE_ID, 1)).deleteRecursively()
        vm.open(1)
        waitUntil("a missing stage asks for the download") { vm.alert.value == PilgrimageRouteAlert.DOWNLOAD_FIRST }
    }

    @Test
    fun `with every stage walked, the next row opens stage 1`() {
        world.install()
        world.harness.ledgers.save(
            PilgrimageLedger(ROUTE_ID).recorded(0, "a", 24.2, arrived, WALKED_AT).recorded(1, "b", 21.9, arrived, WALKED_AT),
        )
        world.holdCatalog()
        val vm = world.routeViewModel()
        val page = settled(vm)

        assertEquals("you have walked the whole way", nextRow(page.ledger, page.entry.stageCount))
        assertEquals(WayStore.stageWayId(ROUTE_ID, 0), openedBy(vm) { vm.openNext() })
    }

    /** P4 §4.7–§4.8: "Replace?" first, through the button or the Download-first alert; then the routes swap. */
    @Test
    fun `with another route installed, Download asks Replace first, and Replace swaps the routes`() {
        world.stubIndex(PilgrimageScreensWorld.index(routes = listOf(ROUTE_ID to "Francés", NORTE_ID to "Norte")))
        world.harness.stubNorte()
        world.install(NORTE_ID)
        world.holdCatalog()
        val vm = world.routeViewModel()
        assertEquals(NORTE_ID, settled(vm).installed?.routeId)

        vm.open(0)
        waitUntil("Download this route first?") { vm.alert.value == PilgrimageRouteAlert.DOWNLOAD_FIRST }
        vm.confirmDownloadFirst()
        assertEquals("the Download-first alert takes the button's gate", PilgrimageRouteAlert.REPLACE, vm.alert.value)
        vm.dismissAlert()
        vm.onDownloadTapped()
        assertEquals(PilgrimageRouteAlert.REPLACE, vm.alert.value)

        vm.confirmReplace()

        waitUntil("the swap lands") { pageOf(vm).isInstalled && !pageOf(vm).actionInFlight }
        assertNull(pageOf(vm).failure)
        assertEquals(ROUTE_ID, runBlocking { world.manager.installed() }?.routeId)
        assertFalse(world.harness.wayStore.routeFile(NORTE_ID)!!.exists())
    }

    @Test
    fun `an update asks nothing, and the page then reads On your phone`() {
        world.install()
        world.stubIndex(PilgrimageScreensWorld.index(release = "v1.8.0"))
        world.harness.stubWholePackage(release = "v1.8.0")
        world.holdCatalog()
        val vm = world.routeViewModel()
        val page = settled(vm)
        assertTrue(page.hasUpdate)
        assertEquals("Update", PilgrimageRouteModel.buttonLabel(resources, page.isInstalled, page.hasUpdate))

        vm.onDownloadTapped()

        assertNull(vm.alert.value)
        waitUntil("the update lands") { pageOf(vm).installed?.release == "v1.8.0" && !pageOf(vm).actionInFlight }
        val updated = pageOf(vm)
        assertEquals("On your phone", PilgrimageRouteModel.buttonLabel(resources, updated.isInstalled, updated.hasUpdate))
        vm.onDownloadTapped()
        assertFalse("a current route's button does nothing", pageOf(vm).actionInFlight)
    }

    /** P4 §4.8: Remove leaves the stage list as the removed package drew it, and the kept ledger fills the circles. */
    @Test
    fun `Remove asks first, then the page reads Download and keeps its stages and its walked circles`() {
        world.install()
        world.harness.ledgers.save(PilgrimageLedger(ROUTE_ID).recorded(0, "a", 24.2, arrived, WALKED_AT))
        world.holdCatalog()
        val vm = world.routeViewModel()
        assertTrue(settled(vm).isInstalled)

        vm.onRemoveTapped()
        assertEquals(PilgrimageRouteAlert.REMOVE, vm.alert.value)
        vm.confirmRemove()

        waitUntil("the remove lands") { !pageOf(vm).isInstalled && !pageOf(vm).actionInFlight }
        val page = pageOf(vm)
        assertNull(vm.alert.value)
        assertEquals(listOf(0, 1), page.stages.map { it.index })
        assertEquals(true, page.ledger?.stages?.get("0")?.completed)
        assertEquals("Download", PilgrimageRouteModel.buttonLabel(resources, page.isInstalled, page.hasUpdate))
    }

    /** P4 correction 2: the page going, by Back or a swipe, never cancels the manager's work. */
    @Test
    fun `a download outlives the page that started it`() {
        val hold = world.harness.hold("stage-01.json")
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)

        vm.onDownloadTapped()
        hold.awaitArrival()
        runBlocking { vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
        hold.release()

        waitUntil("the download lands without its page") { runBlocking { world.manager.installed() } != null }
    }

    // ---- Helpers ----

    private val entry = PilgrimageCatalogEntry(
        id = ROUTE_ID,
        name = "Camino de Santiago (Francés)",
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 46.1,
        tradition = "christian",
        stageCount = 2,
        bytes = 214_000,
        placesPerStage = 0.0,
        sparse = false,
    )

    private fun route(stages: List<PilgrimageRouteStage>) = PilgrimageRoute(
        id = ROUTE_ID,
        name = "Camino de Santiago (Francés)",
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 46.1,
        stageCount = stages.size,
        tradition = "christian",
        summary = null,
        stages = stages,
    )

    private fun page(
        ledger: PilgrimageLedger? = null,
        route: PilgrimageRoute? = null,
        entry: PilgrimageCatalogEntry = this.entry,
    ) = PilgrimageRoutePage(entry = entry, release = RELEASE, route = route, ledger = ledger)

    private fun nextRow(ledger: PilgrimageLedger?, stageCount: Int) = PilgrimageRouteModel.nextRow(resources, ledger, stageCount)

    private fun pageOf(vm: PilgrimageRouteViewModel): PilgrimageRoutePage =
        (vm.state.value as? PilgrimageRouteUiState.Ready)?.page ?: error("the page isn't ready: ${vm.state.value}")

    /** Ready, its stages read (the package's, or the preview's), and no action of its own running. */
    private fun settled(vm: PilgrimageRouteViewModel): PilgrimageRoutePage {
        waitUntil("the page settles") {
            val page = (vm.state.value as? PilgrimageRouteUiState.Ready)?.page
            page != null && page.route != null && !page.isLoadingStages && !page.actionInFlight
        }
        return pageOf(vm)
    }

    /** The stage Way [action] opens, subscribed before it runs, as the screen subscribes before any tap. */
    private fun openedBy(vm: PilgrimageRouteViewModel, action: () -> Unit): String = runBlocking {
        val opened = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { vm.opened.first() } }
        action()
        opened.await()
    }
}
