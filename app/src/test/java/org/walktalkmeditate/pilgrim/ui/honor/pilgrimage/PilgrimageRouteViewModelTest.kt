// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.launch
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
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
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
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesCorridor
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Phase as TilesPhase
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.TileRegionLoadingError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.TileStage
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayJson
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
 * redraw notice shown once, the page's holds (its opening reload's, each
 * install's, counted, and none for a Remove), the stage taps, Replace,
 * Update, Remove, and a download that outlives its page. Then the maps row
 * (offline-maps spec D C4 §1): iOS's held-row test, the wait for the
 * store, the cold face, AE10, #121 item 5's Update case, a save outliving
 * its page, the failure lines, what a save holds, the stage values off the
 * main thread, and the flag. Robolectric for the strings; waits are
 * wall-clock, as the manager runs on real threads.
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
    fun `busy is any download or map save running, or this page's own hold`() {
        val downloading = PilgrimagePackageManager.Phase.Downloading(done = 1, total = 3)
        val failed = PilgrimagePackageManager.Phase.Failed(PilgrimageError.INCOMPLETE)
        val idle = PilgrimagePackageManager.Phase.Idle
        val saving = TilesPhase.Saving(done = 2, total = 4)
        val refused = TilesPhase.Failed(PilgrimageError.WALK_IN_PROGRESS)
        assertTrue(PilgrimageRouteModel.isBusy(downloading, TilesPhase.Idle, held = false))
        assertTrue(PilgrimageRouteModel.isBusy(idle, TilesPhase.Idle, held = true))
        assertTrue("a save holds the button and Remove", PilgrimageRouteModel.isBusy(idle, saving, held = false))
        assertFalse(PilgrimageRouteModel.isBusy(idle, TilesPhase.Idle, held = false))
        assertFalse(PilgrimageRouteModel.isBusy(failed, TilesPhase.Idle, held = false))
        assertFalse("a failed save holds nothing", PilgrimageRouteModel.isBusy(idle, refused, held = false))
    }

    /**
     * iOS `testTheMapsRowIsHeldOnlyWhileThePackageDownloads`
     * (`PilgrimageCatalogServiceTests.swift:415-423@7c200bf`): during an
     * Update the stage lines are being rewritten, so a save started then
     * hashes soon-to-be-stale corridors. Only a download holds the row,
     * never `isBusy`, which includes a save in flight and would take the
     * row's own cancel with it.
     */
    @Test
    fun `the maps row is held only while the package downloads`() {
        assertTrue(PilgrimageRouteModel.mapsRowIsHeld(PilgrimagePackageManager.Phase.Downloading(done = 1, total = 34)))
        assertFalse(PilgrimageRouteModel.mapsRowIsHeld(PilgrimagePackageManager.Phase.Idle))
        assertFalse(PilgrimageRouteModel.mapsRowIsHeld(PilgrimagePackageManager.Phase.Failed(PilgrimageError.INCOMPLETE)))
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

        waitUntil("the refusal lands") { pageOf(vm).failure != null && !pageOf(vm).isHeld }
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
        assertTrue(PilgrimageRouteModel.isBusy(vm.phase.value, vm.tilesPhase.value, page.isHeld))
        vm.onDownloadTapped()
        vm.onRemoveTapped()
        assertNull(vm.alert.value)
        assertEquals("the held taps started nothing", 0, pageOf(vm).holds)
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
        assertNull("and the maps row stays hidden with it", vm.mapsRow.value)
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
        assertFalse(PilgrimageRouteModel.isBusy(vm.phase.value, vm.tilesPhase.value, page.isHeld))
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
     * Android's own hold: between the tap and the phase, the guard reads the
     * database, and a second tap there would be refused as a second
     * download, "the download didn't finish", while the first carries on.
     */
    @Test
    fun `a quick second tap is held by the page's own hold`() {
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
        assertTrue(PilgrimageRouteModel.isBusy(vm.phase.value, vm.tilesPhase.value, pageOf(vm).isHeld))
        vm.onDownloadTapped()
        guardHeld.countDown()

        waitUntil("the download lands") { pageOf(vm).isInstalled && !pageOf(vm).isHeld }
        assertNull(pageOf(vm).failure)
        runBlocking { world.manager.installed() }
        assertEquals("one download: its entry check and its commit check", 2, guardReads.get())
    }

    /**
     * Android's async opening read: until the first reload has read what
     * is installed, the page reads "Download" with nothing installed, so a
     * tap there would download beside the other route without "Replace?".
     * iOS's opening reload is synchronous, so its page has no such moment.
     */
    @Test
    fun `a tap before the first reload lands does nothing, so Download never skips Replace`() {
        withNorteInstalled()
        world.holdCatalog()
        val busy = world.holdManager()

        val vm = world.routeViewModel()

        assertTapsHeldUntilTheFirstReload(vm, busy)
    }

    /** P4 §4.11, after a kill mid-download: the launch sweep keeps the manager busy, so the restored page's reload waits. */
    @Test
    fun `a page restored while the manager is busy is held until its first reload, then asks Replace`() {
        withNorteInstalled()
        world.holdCatalog()
        world.newProcessCatalogService()
        val busy = world.holdManager()

        val vm = world.routeViewModel()
        waitUntil("the catalog is read") { vm.state.value is PilgrimageRouteUiState.Ready }

        assertTapsHeldUntilTheFirstReload(vm, busy)
    }

    /**
     * Counted holds. With the page's own download streaming, a stage's
     * "Download this route first?" starts a second install, which the
     * manager refuses at once (pilgrim-ios #119, matched). Its reload must
     * leave the first install's hold, or the page would read an idle
     * "Download" between that install's commit and the reload after it.
     */
    @Test
    fun `an install refused while the page's own runs leaves that one's hold`() {
        val hold = world.harness.hold("stage-01.json")
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)
        vm.onDownloadTapped()
        hold.awaitArrival()

        vm.open(0)
        waitUntil("Download this route first?") { vm.alert.value == PilgrimageRouteAlert.DOWNLOAD_FIRST }
        vm.confirmDownloadFirst()
        waitUntil("the second install is refused and reloaded") {
            pageOf(vm).failure == PilgrimageError.INCOMPLETE && pageOf(vm).holds == 1
        }

        assertTrue(
            "held whatever the phase says",
            PilgrimageRouteModel.isBusy(PilgrimagePackageManager.Phase.Idle, TilesPhase.Idle, pageOf(vm).isHeld),
        )
        hold.release()
        waitUntil("the first install lands") { pageOf(vm).isInstalled && !pageOf(vm).isHeld }
        assertNull("its success clears the refusal's line, as iOS's does", pageOf(vm).failure)
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

        waitUntil("the swap lands") { pageOf(vm).isInstalled && !pageOf(vm).isHeld }
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
        waitUntil("the update lands") { pageOf(vm).installed?.release == "v1.8.0" && !pageOf(vm).isHeld }
        val updated = pageOf(vm)
        assertEquals("On your phone", PilgrimageRouteModel.buttonLabel(resources, updated.isInstalled, updated.hasUpdate))
        vm.onDownloadTapped()
        assertEquals("a current route's button does nothing", 0, pageOf(vm).holds)
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
        assertFalse(
            "a Remove dims nothing, as iOS's synchronous one",
            PilgrimageRouteModel.isBusy(vm.phase.value, vm.tilesPhase.value, pageOf(vm).isHeld),
        )

        waitUntil("the remove lands") { !pageOf(vm).isInstalled }
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


    // ---- Spec D C4 §1: the maps row ----

    /** C4 §1.2, A1: no row until the estimate and the store's answer are in, as iOS's synchronous reload draws none without them. */
    @Test
    fun `the maps row waits for the store's first answer, then reads the estimate`() {
        world.install()
        world.holdCatalog()
        val vm = world.routeViewModel()
        assertTrue(settled(vm).isInstalled)

        waitUntil("the row asks the store") { world.onTiles { world.tilesLoader.firstAnswerRequests } == 1 }
        assertNull("no row before the store has answered", vm.mapsRow.value)
        world.answerStore()

        val row = mapsRowOf(vm)
        val stages = installedStages(count = 2)
        assertEquals(stages, row.stages)
        assertEquals(PilgrimageTilesManager.Status.None, row.status)
        assertEquals(PilgrimageTilesCorridor.packCount(stages) * PilgrimageTilesManager.SEED_BYTES_PER_PACK, row.estimateBytes)
        assertEquals("four z11 cells at the seed", "Save maps for the way · ~16 MB", rowText(row))
    }

    /**
     * C4 §1.2, A3: past the wait, the cache is read anyway, iOS's own cold
     * face, and the store's regions answer corrects it. A failed first
     * answer gives the wait's `false` at once, as the bound passing does.
     */
    @Test
    fun `a store with no answer draws iOS's cold face, and its regions answer corrects it`() {
        world.install()
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)
        waitUntil("the row asks the store") { world.onTiles { world.tilesLoader.firstAnswerRequests } == 1 }

        world.onTiles { world.tilesLoader.failRegions() }

        assertEquals(PilgrimageTilesManager.Status.None, mapsRowOf(vm).status)
        world.onTiles {
            installedStages(count = 2).forEach { world.tilesLoader.seed(it.id, it.corridorHash) }
            world.tilesLoader.seedStylePacks()
            world.tilesLoader.releaseRegions()
        }
        val saved = mapsRowOf(vm, "the regions answer lands") { it.status is PilgrimageTilesManager.Status.Saved }
        assertEquals("maps saved · 1 MB", rowText(saved))
    }

    /**
     * C4 correction 7: a stage Way that doesn't load is skipped, as iOS's
     * `compactMap` skips it, so "of" counts the ones that do. With the
     * packs missing a whole save reads "n of n" (D4, matched).
     */
    @Test
    fun `the row counts only the stage Ways that load`() {
        world.install()
        world.answerStore()
        File(world.harness.waysDir, WayStore.stageWayId(ROUTE_ID, 1)).deleteRecursively()
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)

        val row = mapsRowOf(vm)
        assertEquals(listOf(WayStore.stageWayId(ROUTE_ID, 0)), row.stages.map { it.id })
        world.onTiles {
            world.tilesLoader.seed(row.stages[0].id, row.stages[0].corridorHash)
            world.tilesLoader.releaseRegions()
        }

        val counted = mapsRowOf(vm, "the regions answer lands") { it.status is PilgrimageTilesManager.Status.Partial }
        assertEquals("Save maps for the way · 1 of 1 saved", rowText(counted))
    }

    /**
     * AE10 from the route row (C4 §6): all 33 stages saved, then an Update
     * to 30 that redraws stage 12. Once the page reloads on the new release
     * the row reads 29 of 30. The Update's posted removals land before that
     * reload, on a page its own install holds, so no "of 33" is ever
     * published, and the old release never comes back.
     */
    @Test
    fun `after an Update the row reads 29 of 30 saved once the page reloads on the new release`() {
        withAllThirtyThreeSaved()
        withAnUpdateToThirty()
        world.holdCatalog()
        val vm = world.routeViewModel()
        assertTrue(settled(vm).hasUpdate)
        assertEquals("maps saved · 3 MB", rowText(mapsRowOf(vm)))
        val seen = recordRows(vm)

        vm.onDownloadTapped()

        waitUntil("the update lands") { pageOf(vm).installed?.release == "v1.8.0" && !pageOf(vm).isHeld }
        val row = mapsRowOf(vm, "the new release's row") { it.release == "v1.8.0" }
        assertEquals("Save maps for the way · 29 of 30 saved", rowText(row))
        assertEquals(30, row.stages.size)
        assertEquals((30..32).map { WayStore.stageWayId(ROUTE_ID, it) }.toSet(), world.onTiles { world.tilesLoader.removedIds.toSet() })
        assertEquals("once everything posted has run", row, world.onTiles { vm.mapsRow.value })
        assertTrue(
            "no stale 'of 33': ${seen.map { it?.release to it?.status }}",
            seen.none { (it?.status as? PilgrimageTilesManager.Status.Partial)?.of == 33 },
        )
        val fresh = seen.indexOfFirst { it?.release == "v1.8.0" }
        assertTrue("the old release never comes back", seen.drop(fresh).none { it?.release == RELEASE })
    }

    /**
     * pilgrim-ios #121 item 5, with C4 §1.7's maps consequence, matched: a
     * page opened while an Update downloads keeps the old release's stage
     * values, held until the commit; then the Update's removals read
     * against them, "30 of 33 saved", and nothing reloads.
     */
    @Test
    fun `a page opened mid-Update keeps the old release's stages and reads 30 of 33 after the commit`() {
        withAllThirtyThreeSaved()
        withAnUpdateToThirty()
        val hold = world.harness.hold("stage-05.json", release = "v1.8.0")
        val update = world.manager.update(world.harness.entry.copy(stageCount = 30), "v1.8.0")
        hold.awaitArrival()
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)
        assertEquals("maps saved · 3 MB", rowText(mapsRowOf(vm)))
        assertTrue("held while the package downloads", PilgrimageRouteModel.mapsRowIsHeld(vm.phase.value))

        hold.release()
        update.awaitBlocking()

        val row = mapsRowOf(vm, "the removals read against the old lines") {
            it.status == PilgrimageTilesManager.Status.Partial(saved = 30, of = 33)
        }
        assertEquals("Save maps for the way · 30 of 33 saved", rowText(row))
        assertEquals("still the opening's release", RELEASE, row.release)
        assertFalse(PilgrimageRouteModel.mapsRowIsHeld(vm.phase.value) || pageOf(vm).isHeld)
    }

    /** C4 §1.5: the save runs in the manager's scope, so a page reopened mid-save shows it live, and a later one reads it saved. */
    @Test
    fun `a save outlives its page, a page reopened shows it live, and one opened after it ends reads maps saved`() {
        world.install()
        world.answerStore()
        world.onTiles { world.tilesLoader.seedStylePacks() }
        world.holdCatalog()
        val first = world.routeViewModel()
        settled(first)
        mapsRowOf(first)

        first.onSaveMaps()
        awaitLoad()
        leave(first)

        val second = world.routeViewModel()
        settled(second)
        mapsRowOf(second)
        waitUntil("the live phase") { second.tilesPhase.value == TilesPhase.Saving(done = 2, total = 4) }
        assertEquals("maps · stage 0 of 2", savingLine(second.tilesPhase.value))
        world.onTiles { world.tilesLoader.completeNextRegion() }
        waitUntil("stage 1 saved") { second.tilesPhase.value == TilesPhase.Saving(done = 3, total = 4) }
        assertEquals("maps · stage 1 of 2", savingLine(second.tilesPhase.value))
        leave(second)

        awaitLoad()
        world.onTiles { world.tilesLoader.completeNextRegion() }
        waitUntil("the save ends with no page open") { world.tiles.phase.value == TilesPhase.Idle }

        val third = world.routeViewModel()
        settled(third)
        assertEquals("maps saved · 1 MB", rowText(mapsRowOf(third)))
    }

    /**
     * C4 §1.5, C4-1 matched: a refusal lives on the manager's phase, so its
     * line outlives the walk and the page, until the next save starts, a
     * cancel, or a Remove, whose `remove` cancels first.
     */
    @Test
    fun `a refused save reads finish your walk first until the next save, a cancel or a Remove`() {
        world.install()
        world.answerStore()
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)
        mapsRowOf(vm)
        val refused = TilesPhase.Failed(PilgrimageError.WALK_IN_PROGRESS)

        world.tilesSignals.screenUp = true
        vm.onSaveMaps()
        waitUntil("the refusal") { vm.tilesPhase.value == refused }
        assertEquals("finish your walk first", resources.getString(PilgrimageCopy.line(refused.error)))
        world.tilesSignals.screenUp = false

        val later = world.routeViewModel()
        settled(later)
        mapsRowOf(later)
        waitUntil("a page opened after the walk still says it") { later.tilesPhase.value == refused }

        later.onSaveMaps()
        waitUntil("the next save clears it") { later.tilesPhase.value is TilesPhase.Saving }
        awaitLoad()
        world.onTiles { later.onCancelMaps() }
        waitUntil("the cancel") { later.tilesPhase.value == TilesPhase.Idle }

        world.tilesSignals.screenUp = true
        later.onSaveMaps()
        waitUntil("refused again") { later.tilesPhase.value == refused }
        world.tilesSignals.screenUp = false
        later.onRemoveTapped()
        later.confirmRemove()
        waitUntil("the Remove's cancel clears it") { later.tilesPhase.value == TilesPhase.Idle }
        waitUntil("the row goes with the route") { !pageOf(later).isInstalled && later.mapsRow.value == null }
    }

    /** pilgrim-ios #122 item 5, matched: a full disk's line names voices, under the idle face. */
    @Test
    fun `a save stopped by a full disk reads the share importer's voices line`() {
        world.install()
        world.answerStore()
        world.onTiles {
            world.tilesLoader.seedStylePacks()
            world.tilesLoader.nextRegionFailure = TileRegionLoadingError.DISK_FULL
        }
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)
        mapsRowOf(vm)

        vm.onSaveMaps()
        awaitLoad()
        world.onTiles { world.tilesLoader.completeNextRegion() }

        waitUntil("the failure") { vm.tilesPhase.value == TilesPhase.Failed(PilgrimageError.DISK_FULL) }
        assertEquals(
            "not enough space on this phone to save these voices",
            resources.getString(PilgrimageCopy.line(PilgrimageError.DISK_FULL)),
        )
        assertEquals("the idle face stays over it", PilgrimageTilesManager.Status.None, vm.mapsRow.value?.status)
    }

    /** C4 §1.4: a save holds Remove and the download button, never the maps row, whose cancel stays live. */
    @Test
    fun `a save holds Remove and the download button, never the maps row`() {
        world.install()
        world.answerStore()
        world.holdCatalog()
        val vm = world.routeViewModel()
        settled(vm)
        mapsRowOf(vm)

        vm.onSaveMaps()
        waitUntil("the save runs") { vm.tilesPhase.value is TilesPhase.Saving }
        val page = pageOf(vm)
        assertTrue(PilgrimageRouteModel.isBusy(vm.phase.value, vm.tilesPhase.value, page.isHeld))
        vm.onRemoveTapped()
        assertNull("Remove is held", vm.alert.value)
        assertFalse("the row is not", PilgrimageRouteModel.mapsRowIsHeld(vm.phase.value) || page.isHeld)

        awaitLoad()
        world.onTiles { vm.onCancelMaps() }
        waitUntil("the cancel") { vm.tilesPhase.value == TilesPhase.Idle }
        vm.onRemoveTapped()
        assertEquals(PilgrimageRouteAlert.REMOVE, vm.alert.value)
    }

    /** C1 §12, A1: the stages' values are built on IO, and the page keeps only them, never a decoded Way. */
    @Test
    fun `the stage values are built off the main thread, and no decoded Way is kept`() {
        world.install()
        world.answerStore()
        world.holdCatalog()
        val decodes = CopyOnWriteArrayList<String>()
        val store = WayStore({ world.harness.waysDir }, syncDirectory = { true }, decodeWay = { text ->
            decodes += Thread.currentThread().name
            WayJson.decode(text)
        })
        val vm = world.routeViewModel(wayStore = store)
        settled(vm)

        val row = mapsRowOf(vm)

        assertEquals(installedStages(count = 2), row.stages)
        assertEquals("each stage decoded once", 2, decodes.size)
        assertTrue("on IO, never the main thread: $decodes", decodes.all { it.startsWith("DefaultDispatcher-worker") })
        val kept = listOf(PilgrimageRouteViewModel::class.java, PilgrimageMapsRowState::class.java) +
            PilgrimageRouteViewModel::class.java.declaredClasses
        kept.forEach(::assertKeepsNoWay)
    }

    /** R21, C4 §5: with the flag off the page draws no row and never resolves the tiles manager. */
    @Test
    fun `with the flag off there is no maps row, and the tiles manager is never resolved`() {
        world.install()
        world.answerStore()
        world.holdCatalog()

        val vm = world.routeViewModel(flags = FixedReleaseFlags(honor = false))

        assertTrue(settled(vm).isInstalled)
        assertNull(vm.mapsRow.value)
        assertEquals(0, world.tilesResolutions.get())
        assertEquals(TilesPhase.Idle, vm.tilesPhase.value)
    }

    @Test
    fun `a page for a route not installed draws no maps row and never resolves the tiles manager`() {
        withNorteInstalled()
        world.holdCatalog()

        val vm = world.routeViewModel()

        assertFalse(settled(vm).isInstalled)
        assertNull(vm.mapsRow.value)
        assertEquals(0, world.tilesResolutions.get())
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
            page != null && page.route != null && !page.isLoadingStages && !page.isHeld
        }
        return pageOf(vm)
    }

    /** Two routes listed and the Norte on the phone, so a Download of the Francés has to ask "Replace?". */
    private fun withNorteInstalled() {
        world.stubIndex(PilgrimageScreensWorld.index(routes = listOf(ROUTE_ID to "Francés", NORTE_ID to "Norte")))
        world.harness.stubNorte()
        world.install(NORTE_ID)
    }

    /** While [busy] keeps the page's first reload waiting, its taps start nothing; once it lands, Download asks "Replace?". */
    private fun assertTapsHeldUntilTheFirstReload(vm: PilgrimageRouteViewModel, busy: PilgrimageScreensWorld.ManagerHold) {
        val opening = pageOf(vm)
        assertNull("nothing is read yet", opening.installed)
        assertTrue(PilgrimageRouteModel.isBusy(vm.phase.value, vm.tilesPhase.value, opening.isHeld))
        vm.onDownloadTapped()
        vm.onRemoveTapped()
        assertNull(vm.alert.value)
        assertEquals("only the opening's hold", 1, pageOf(vm).holds)

        busy.release()

        val page = settled(vm)
        assertEquals(NORTE_ID, page.installed?.routeId)
        assertEquals("no tap reached the guard", 1, busy.guardReads)
        assertEquals(NORTE_ID, runBlocking { world.manager.installed() }?.routeId)
        assertFalse("nothing landed beside the Norte", world.harness.wayStore.routeFile(ROUTE_ID)!!.exists())
        vm.onDownloadTapped()
        assertEquals(PilgrimageRouteAlert.REPLACE, vm.alert.value)
    }

    /** The row once it has landed and [until] holds of it, as the page last published it. */
    private fun mapsRowOf(
        vm: PilgrimageRouteViewModel,
        what: String = "the maps row lands",
        until: (PilgrimageMapsRowState) -> Boolean = { true },
    ): PilgrimageMapsRowState {
        var row: PilgrimageMapsRowState? = null
        waitUntil(what) { vm.mapsRow.value?.takeIf(until).also { row = it } != null }
        return row!!
    }

    /** What the row's idle face reads: the saved line beside the check, or the button's label. */
    private fun rowText(row: PilgrimageMapsRowState): String =
        (row.status as? PilgrimageTilesManager.Status.Saved)?.let { PilgrimageMapsRowModel.savedLine(resources, it.bytes) }
            ?: PilgrimageMapsRowModel.label(resources, row.status, row.estimateBytes)

    private fun savingLine(phase: TilesPhase): String =
        (phase as TilesPhase.Saving).let { PilgrimageMapsRowModel.savingLine(resources, it.done, it.total) }

    private fun installedStages(count: Int): List<TileStage> = PilgrimageTilesCorridor.stages(world.harness.wayStore, ROUTE_ID, count)

    /** The save parked on a pack or region load the test then completes on the tiles thread. */
    private fun awaitLoad() = waitUntil("the save reaches a load") { world.onTiles { world.tilesLoader.hasPendingWork } }

    /** The page going, by Back or a swipe. */
    private fun leave(vm: PilgrimageRouteViewModel) = runBlocking { vm.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }

    /** Every row value the page publishes from here on, each as it is set. */
    private fun recordRows(vm: PilgrimageRouteViewModel): List<PilgrimageMapsRowState?> {
        val seen = CopyOnWriteArrayList<PilgrimageMapsRowState?>()
        vm.viewModelScope.launch(Dispatchers.Unconfined) { vm.mapsRow.collect { seen += it } }
        return seen
    }

    /** The Francés at [RELEASE] with 33 stages on the phone, every stage's region and both packs saved, and the store answered. */
    private fun withAllThirtyThreeSaved() {
        world.stubIndex(PilgrimageScreensWorld.index(stageCount = 33))
        world.stubStages(stageCount = 33, release = RELEASE)
        world.install(entry = world.harness.entry.copy(stageCount = 33))
        world.onTiles {
            installedStages(count = 33).forEach { world.tilesLoader.seed(it.id, it.corridorHash) }
            world.tilesLoader.seedStylePacks()
            world.tilesLoader.releaseRegions()
        }
    }

    /** AE10's release: 30 stages, stage 12 redrawn. */
    private fun withAnUpdateToThirty() {
        world.stubIndex(PilgrimageScreensWorld.index(release = "v1.8.0", stageCount = 30))
        world.stubStages(stageCount = 30, release = "v1.8.0", redrawn = setOf(12))
    }

    /** No field the page keeps for its row is typed to hold a decoded Way: a `Way`, a `List<Way>`, and so on. */
    private fun assertKeepsNoWay(type: Class<*>) {
        val way = Regex("""\b${Regex.escape(Way::class.java.name)}\b""")
        type.declaredFields.forEach { field ->
            assertFalse("${type.simpleName}.${field.name} can hold a Way", way.containsMatchIn(field.genericType.typeName))
        }
    }

    /** The stage Way [action] opens, subscribed before it runs, as the screen subscribes before any tap. */
    private fun openedBy(vm: PilgrimageRouteViewModel, action: () -> Unit): String = runBlocking {
        val opened = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { vm.opened.first() } }
        action()
        opened.await()
    }
}
