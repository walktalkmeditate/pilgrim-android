// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.FakeTileRegionLoader
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.FakeWalkSignals
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.InMemoryTilesCalibration
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRoute
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesCorridor
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.TileStage
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.recordingHandler
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace

/**
 * Settings → Maps and its Data card row (offline-maps spec D C4 §3): a port
 * of iOS `OfflineMapsViewModelTests.swift@7c200bf` (its 4, the load's input
 * built by U43's corridor from iOS's fixture lines), then the installed
 * read, Delete (asked first, cancelled, done, and stopping a save running
 * elsewhere), the wait for the store and past it, the row's availability
 * and its flag, the screen leaving mid-walk, a failed answer asked again,
 * a store that can't open, and AE10's Update.
 *
 * The tiles manager runs over C2's fake loader. Its thread is a standard
 * test dispatcher on the test's scheduler, so its posted work (a Delete's
 * removal) waits for `runCurrent()`, as production's waits for the main
 * thread; the ViewModels run unconfined on the same scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineMapsViewModelTest {

    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources get() = context.resources
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val tilesMain = StandardTestDispatcher(scheduler)
    private val tilesEscaped = CopyOnWriteArrayList<Throwable>()
    private val tilesScope = CoroutineScope(SupervisorJob() + tilesMain + recordingHandler(tilesEscaped))
    private val loader = FakeTileRegionLoader()
    private val tiles = PilgrimageTilesManager(loader, InMemoryTilesCalibration(), FakeWalkSignals(), tilesMain, tilesScope)
    private val shown = MutableStateFlow(true)
    private val viewModels = mutableListOf<ViewModel>()
    private lateinit var wayStore: WayStore
    private var installedRoute: PilgrimagePackageManager.Installed? = null
    private var installedFailure: Exception? = null
    private var installedReads = 0
    private var tilesResolutions = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        wayStore = WayStore({ File(folder.root, "Ways") }, syncDirectory = { true })
    }

    @After
    fun tearDown() {
        val outlived = cancelViewModels()
        tilesScope.cancel()
        scheduler.runCurrent()
        Dispatchers.resetMain()
        assertTrue("ViewModel work outlived its cancel: $outlived", outlived.isEmpty())
        assertTrue("escaped the tiles manager's posted work: $tilesEscaped", tilesEscaped.isEmpty())
    }

    /**
     * Cancelled, then the scheduler run, never joined: a read left waiting
     * for the store resumes on the tiles thread, which only the scheduler
     * runs, so a join would wait for it forever. Returns what still runs.
     */
    private fun cancelViewModels(): List<Job> {
        val jobs = viewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        jobs.forEach { it.cancel() }
        scheduler.runCurrent()
        return jobs.filterNot { it.isCompleted }
    }

    private fun maps() = InstalledMaps(
        installed = {
            installedReads++
            installedFailure?.let { throw it }
            installedRoute
        },
        wayStore = wayStore,
        tiles = Provider { tiles.also { tilesResolutions++ } },
        ioDispatcher = dispatcher,
        tilesDispatcher = tilesMain,
    )

    private fun screen(availability: WaysAvailability = WaysAvailability(shown, shownAtFirst = true)) =
        OfflineMapsViewModel(availability, maps()).also { viewModels += it }

    private fun row(
        availability: WaysAvailability = WaysAvailability(shown, shownAtFirst = true),
        honorEnabled: Boolean = true,
    ) = MapsRowViewModel(availability, FixedReleaseFlags(honor = honorEnabled), maps()).also { viewModels += it }

    /**
     * iOS's fixture (`OfflineMapsViewModelTests.swift:20-30`): [count] stages
     * of `camino-frances`, each a 0.01° line east along latitude 42 from
     * 0.05° times its index; a [redrawn] one's line starts 0.01° further east.
     */
    private fun stageWay(index: Int, count: Int = 3, redrawn: Boolean = false): Way {
        val west = index * 0.05 + if (redrawn) 0.01 else 0.0
        val stage = WayStage(
            routeId = ROUTE_ID, index = index, count = count, name = "s", theme = "t", narrative = "n", closing = "c",
            warnings = emptyList(), distanceKm = 1.0, gainMeters = 0.0, hours = WayStageHours(min = 1.0, max = 1.0),
            difficulty = "easy",
            start = WayStagePlace(name = "a", at = WayCoordinate(lat = 42.0, lon = 0.0)),
            end = WayStagePlace(name = "b", at = WayCoordinate(lat = 42.0, lon = 0.01)),
        )
        return Way(
            id = WayStore.stageWayId(ROUTE_ID, index),
            source = WaySource.Pilgrimage(routeId = ROUTE_ID, stageIndex = index),
            title = "s",
            departedAt = Instant.EPOCH,
            tzIdentifier = null,
            expires = null,
            route = listOf(
                WayPoint(lat = 42.0, lon = west, alt = null, t = 0.0),
                WayPoint(lat = 42.0, lon = west + 0.01, alt = null, t = 60.0),
            ),
            totalDistanceMeters = 1000.0,
            theirActiveSeconds = 600.0,
            moments = emptyList(),
            weather = null,
            spans = null,
            marks = null,
            stage = stage,
        )
    }

    private fun stages(count: Int = 3): List<TileStage> = (0 until count).map { PilgrimageTilesCorridor.stage(stageWay(it, count)) }

    /** [count] stages on the phone under iOS's route name at [release], as `installed()` reads them. */
    private fun install(count: Int = 3, release: String = "v1.7.0", redrawn: Int? = null) {
        (0 until count).forEach { wayStore.save(stageWay(it, count, redrawn = it == redrawn)) }
        installedRoute = PilgrimagePackageManager.Installed(
            routeId = ROUTE_ID,
            release = release,
            route = PilgrimageRoute(
                id = ROUTE_ID, name = ROUTE_NAME, names = emptyMap(), country = "ES", region = "Europe", distanceKm = 3.0,
                stageCount = count, tradition = "christian", summary = null, stages = emptyList(),
            ),
        )
    }

    /** Every stage saved on its own line, as a whole save leaves them. */
    private fun seedAllSaved(count: Int = 3) {
        stages(count).forEach { loader.seed(it.id, it.corridorHash) }
    }

    private fun saved(bytes: Long, savedStages: Int, totalStages: Int = 3) =
        OfflineMapsUiState.Saved(ROUTE_NAME, bytes, savedStages, totalStages)

    /** Both style packs, each load completed once the save has reached it. */
    private fun TestScope.completePacks() {
        repeat(2) {
            loader.completeNextPack()
            runCurrent()
        }
    }

    /** Every detail the row publishes from here on. */
    private fun TestScope.recorded(vm: MapsRowViewModel): List<OfflineMapsUiState> {
        val seen = mutableListOf<OfflineMapsUiState>()
        backgroundScope.launch { vm.detail.collect { seen += it } }
        return seen
    }

    // ---- iOS OfflineMapsViewModelTests -----------------------------------------

    @Test
    fun `the row says none saved or the route and its bytes`() {
        assertEquals("none saved", OfflineMapsModel.rowDetail(resources, null))
        val saved = OfflineMapsUiState.Saved("Camino de Santiago (Frances)", bytes = 26_100_000, savedStages = 33, totalStages = 33)
        assertEquals("Camino de Santiago (Frances) · 26 MB", OfflineMapsModel.rowDetail(resources, saved))
    }

    @Test
    fun `the copy is the spec's`() {
        assertEquals("no maps saved", resources.getString(R.string.settings_maps_empty))
        assertEquals("Delete maps?", resources.getString(R.string.settings_maps_delete_title))
        assertEquals(
            "Removes the saved basemap. The route's stages stay on your phone.",
            resources.getString(R.string.settings_maps_delete_message),
        )
    }

    /** A partial save is still bytes on the phone, so the row reports it, and the screen says how many stages. */
    @Test
    fun `load reads the installed route through the tiles manager`() {
        val stages = stages()
        loader.seed(stages[0].id, stages[0].corridorHash, bytes = 5_000_000)

        val loaded = OfflineMapsModel.load(ROUTE_NAME, ROUTE_ID, stages, tiles)

        assertEquals(saved(bytes = 5_000_000, savedStages = 1), loaded)
        assertEquals("one store read for the whole route, not one per stage", 1, loader.regionsReadCount)
        assertNull(OfflineMapsModel.load("x", ROUTE_ID, emptyList(), tiles))
    }

    /** An Update makes every hash stale at once; the bytes stay on the phone, so Delete has to stay reachable. */
    @Test
    fun `stale regions still count toward bytes, so Delete is reachable`() {
        val stages = stages()
        loader.seed(stages[0].id, corridorHash = "stale", bytes = 5_000_000)

        assertEquals(saved(bytes = 5_000_000, savedStages = 0), OfflineMapsModel.load(ROUTE_NAME, ROUTE_ID, stages, tiles))
    }

    // ---- The model's edges ------------------------------------------------------

    @Test
    fun `regions with no bytes are nothing saved`() {
        val stages = stages()
        loader.seed(stages[0].id, stages[0].corridorHash, bytes = 0)

        assertNull(OfflineMapsModel.load(ROUTE_NAME, ROUTE_ID, stages, tiles))
    }

    @Test
    fun `another route's regions are not this route's bytes`() {
        val foreign = WayStore.stageWayId("camino-norte", 0)
        loader.seed(foreign, corridorHash = "x", bytes = 9_000_000)

        assertNull(OfflineMapsModel.load(ROUTE_NAME, ROUTE_ID, stages(), tiles))
    }

    // C4-3, matched: "stages" stays plural at 1.
    @Test
    fun `the saved line reads its megabytes and stages, plural at one`() {
        assertEquals("26 MB · 12 of 33 stages", OfflineMapsModel.savedLine(resources, saved(26_100_000, 12, 33)))
        assertEquals("1 MB · 1 of 1 stages", OfflineMapsModel.savedLine(resources, saved(400_000, 1, 1)))
    }

    // ---- The installed read (iOS `loadInstalled`, C4 §3.2, A9) ------------------

    @Test
    fun `the installed route's stages are read off the main thread and asked of the manager once`() = runTest(dispatcher) {
        install()
        seedAllSaved()
        loader.releaseRegions()
        val readsBefore = loader.regionsReadCount

        val read = maps().reads().first()

        assertEquals(InstalledMaps.Read(saved(bytes = 300_000, savedStages = 3), ROUTE_ID), read)
        assertEquals(1, loader.regionsReadCount - readsBefore)
    }

    // C1 §11: T is the stage Ways that load, not the route file's count.
    @Test
    fun `a stage Way that doesn't load is left out of T`() = runTest(dispatcher) {
        install()
        seedAllSaved()
        loader.releaseRegions()
        wayStore.delete(WayStore.stageWayId(ROUTE_ID, 1))

        assertEquals(saved(bytes = 300_000, savedStages = 2, totalStages = 2), maps().reads().first().state)
    }

    @Test
    fun `nothing installed reads none saved, with no route to delete`() = runTest(dispatcher) {
        loader.releaseRegions()

        assertEquals(InstalledMaps.Read(OfflineMapsUiState.Empty, routeId = null), maps().reads().first())
    }

    // iOS's `installed()` can't throw; Android's can, and a throw is never a destructive path.
    @Test
    fun `an installed read that throws reads as nothing installed`() = runTest(dispatcher) {
        install()
        seedAllSaved()
        loader.releaseRegions()
        installedFailure = IOException("the store root couldn't be resolved")

        assertEquals(InstalledMaps.Read(OfflineMapsUiState.Empty, routeId = null), maps().reads().first())
    }

    // ---- The Maps screen (C4 §3.3) -----------------------------------------------

    @Test
    fun `the screen draws nothing until its first read, then the route, its bytes and its stages`() = runTest(dispatcher) {
        install()
        val vm = screen()
        runCurrent()
        assertEquals(OfflineMapsUiState.Loading, vm.state.value)

        seedAllSaved()
        loader.releaseRegions()
        runCurrent()

        assertEquals(saved(bytes = 300_000, savedStages = 3), vm.state.value)
    }

    @Test
    fun `Delete asks first, and Cancel keeps the maps`() = runTest(dispatcher) {
        install()
        seedAllSaved()
        loader.releaseRegions()
        val vm = screen()
        runCurrent()

        vm.onDeleteTapped()
        assertTrue(vm.confirmingDelete.value)
        vm.onDeleteCancelled()
        runCurrent()

        assertFalse(vm.confirmingDelete.value)
        assertTrue(loader.removedIds.isEmpty())
        assertEquals(saved(bytes = 300_000, savedStages = 3), vm.state.value)
    }

    /** The removal is posted, so the screen's "no maps saved" comes from the regions-changed it fires, not from the call. */
    @Test
    fun `Delete removes the route's maps, and the screen reads no maps saved once regions-changed arrives`() = runTest(dispatcher) {
        install()
        seedAllSaved()
        loader.releaseRegions()
        val vm = screen()
        runCurrent()

        vm.onDeleteTapped()
        vm.onDeleteConfirmed()
        assertFalse(vm.confirmingDelete.value)
        assertEquals("the call returns before the removal runs", saved(bytes = 300_000, savedStages = 3), vm.state.value)
        runCurrent()

        assertEquals(stages().map { it.id }.toSet(), loader.removedIds.toSet())
        assertEquals(OfflineMapsUiState.Empty, vm.state.value)
    }

    @Test
    fun `stale regions an Update left stay deletable`() = runTest(dispatcher) {
        install()
        stages().forEach { loader.seed(it.id, corridorHash = "the old release's line") }
        loader.releaseRegions()
        val vm = screen()
        runCurrent()
        assertEquals(saved(bytes = 300_000, savedStages = 0), vm.state.value)

        vm.onDeleteTapped()
        vm.onDeleteConfirmed()
        runCurrent()

        assertEquals(OfflineMapsUiState.Empty, vm.state.value)
    }

    // C4 correction 9: iOS's `remove` cancels first; a save the route page started stops, its done regions going too.
    @Test
    fun `Delete during a save running elsewhere cancels it`() = runTest(dispatcher) {
        install()
        loader.releaseRegions()
        val vm = screen()
        runCurrent()
        val save = tiles.save(ROUTE_ID, stages())
        runCurrent()
        assertTrue(tiles.phase.value is PilgrimageTilesManager.Phase.Saving)
        completePacks()
        loader.completeNextRegion()
        runCurrent()
        assertEquals("one region in", saved(bytes = 100_000, savedStages = 1), vm.state.value)
        val parked = loader.pendingRegions.single()

        vm.onDeleteTapped()
        vm.onDeleteConfirmed()
        runCurrent()

        assertEquals(PilgrimageTilesManager.Phase.Idle, tiles.phase.value)
        assertTrue("the load in flight is cancelled", parked.handle.isCancelled)
        assertTrue(save.isCompleted)
        assertEquals(listOf(stages()[0].id), loader.removedIds)
        assertEquals(OfflineMapsUiState.Empty, vm.state.value)
    }

    // C2.N U47: plain model state, so a new model after process death never restores it.
    @Test
    fun `the Delete question is not kept past the model`() = runTest(dispatcher) {
        install()
        seedAllSaved()
        loader.releaseRegions()
        val first = screen()
        runCurrent()
        first.onDeleteTapped()
        assertTrue(first.confirmingDelete.value)

        val restored = screen()
        runCurrent()

        assertFalse(restored.confirmingDelete.value)
    }

    @Test
    fun `with nothing saved Delete asks nothing`() = runTest(dispatcher) {
        install()
        loader.releaseRegions()
        val vm = screen()
        runCurrent()
        assertEquals(OfflineMapsUiState.Empty, vm.state.value)

        vm.onDeleteTapped()

        assertFalse(vm.confirmingDelete.value)
    }

    // Owner decision 6: a Delete mid-walk would blank the walker's offline basemap.
    @Test
    fun `the screen leaves once a walk starts or one waits for its Honor step`() = runTest(dispatcher) {
        val vm = screen()
        backgroundScope.launch { vm.hidden.collect {} }
        assertFalse(vm.hidden.value)

        shown.value = false

        assertTrue(vm.hidden.value)
    }

    @Test
    fun `a save landing elsewhere ticks the screen up a region at a time`() = runTest(dispatcher) {
        install()
        loader.releaseRegions()
        val vm = screen()
        runCurrent()
        assertEquals(OfflineMapsUiState.Empty, vm.state.value)
        tiles.save(ROUTE_ID, stages())
        runCurrent()
        completePacks()

        loader.completeNextRegion()
        runCurrent()
        assertEquals(saved(bytes = 100_000, savedStages = 1), vm.state.value)
        loader.completeNextRegion()
        runCurrent()

        assertEquals(saved(bytes = 200_000, savedStages = 2), vm.state.value)
        tiles.cancel()
        runCurrent()
    }

    // ---- The Data card's row (C4 §3.1) -------------------------------------------

    // C4 correction 18: a deliberate improvement on iOS, whose card says "none saved" until the store answers.
    @Test
    fun `Settings right after launch shows the saved figure, never a flash of none saved`() = runTest(dispatcher) {
        install()
        val vm = row()
        val seen = recorded(vm)

        vm.refresh()
        runCurrent()
        assertEquals(OfflineMapsUiState.Loading, vm.detail.value)
        seedAllSaved()
        loader.releaseRegions()
        runCurrent()

        assertEquals(saved(bytes = 300_000, savedStages = 3), vm.detail.value)
        assertFalse("never none saved: $seen", OfflineMapsUiState.Empty in seen)
    }

    // A3: past the bound, iOS's cold face, which the store's regions answer corrects.
    @Test
    fun `past the store's wait the row reads none saved, and the regions answer corrects it`() = runTest(dispatcher) {
        install()
        val vm = row()
        vm.refresh()
        runCurrent()

        advanceTimeBy(PilgrimageTilesManager.STORE_WAIT_MILLIS + 1)
        runCurrent()
        assertEquals(OfflineMapsUiState.Empty, vm.detail.value)
        seedAllSaved()
        loader.releaseRegions()
        runCurrent()

        assertEquals(saved(bytes = 300_000, savedStages = 3), vm.detail.value)
    }

    @Test
    fun `the row reads again on each entry to Settings and on each regions-changed`() = runTest(dispatcher) {
        install()
        loader.releaseRegions()
        val vm = row()
        vm.refresh()
        runCurrent()
        assertEquals(OfflineMapsUiState.Empty, vm.detail.value)

        seedAllSaved()
        vm.refresh()
        runCurrent()
        assertEquals("a return to Settings", saved(bytes = 300_000, savedStages = 3), vm.detail.value)

        tiles.remove(ROUTE_ID)
        runCurrent()
        assertEquals("a Delete's regions-changed", OfflineMapsUiState.Empty, vm.detail.value)
    }

    // Outside runTest, whose last step runs the store's wait out: the read is still waiting at the teardown, which must cancel it without a join.
    @Test
    fun `the row's detail is blank until its first read`() {
        install()
        val vm = row()
        vm.refresh()
        scheduler.runCurrent()

        assertEquals(OfflineMapsUiState.Loading, vm.detail.value)
    }

    /** Owner decision 6 through the real availability: the flag on, no walk on, and no Honor step pending. */
    @Test
    fun `the row shows with the flag on and no walk, and hides mid-walk and while an Honor step is pending`() = runTest(dispatcher) {
        val db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        try {
            val repository = WalkRepository(
                database = db,
                walkDao = db.walkDao(),
                routeDao = db.routeDataSampleDao(),
                altitudeDao = db.altitudeSampleDao(),
                walkEventDao = db.walkEventDao(),
                activityIntervalDao = db.activityIntervalDao(),
                waypointDao = db.waypointDao(),
                voiceRecordingDao = db.voiceRecordingDao(),
                walkPhotoDao = db.walkPhotoDao(),
            )
            val vm = row(WaysAvailability(FixedReleaseFlags(honor = true), repository, db.honorDao()))
            val collector = backgroundScope.launch { vm.shown.collect {} }
            runCurrent()
            assertTrue("no walk", vm.shown.value)

            val walking = db.walkDao().insert(Walk(startTimestamp = 1_000L))
            runCurrent()
            assertFalse("mid-walk", vm.shown.value)

            db.walkDao().update(db.walkDao().getById(walking)!!.copy(endTimestamp = 2_000L))
            db.honorDao().insertSession(
                HonorSessionEntity(
                    walkId = walking,
                    wayId = WayStore.stageWayId(ROUTE_ID, 0),
                    sourceKind = HonorSourceKind.PILGRIMAGE,
                    voicesEnabled = true,
                    softTapEnabled = false,
                ),
            )
            runCurrent()
            assertFalse("the finished walk's Honor step pending", vm.shown.value)

            db.honorDao().deleteSession(walking)
            runCurrent()
            assertTrue(vm.shown.value)
            collector.cancel()
        } finally {
            cancelViewModels()
            db.close()
        }
    }

    // R21, C4 §5: with the flag off there is no row, and nothing from Settings reaches the tiles or package managers.
    @Test
    fun `with the flag off the row is hidden and resolves neither the tiles manager nor the installed route`() = runTest(dispatcher) {
        install()
        val vm = row(WaysAvailability(flowOf(false), shownAtFirst = false), honorEnabled = false)
        backgroundScope.launch { vm.shown.collect {} }

        vm.refresh()
        runCurrent()

        assertFalse(vm.shown.value)
        assertEquals(OfflineMapsUiState.Loading, vm.detail.value)
        assertEquals(0 to 0, tilesResolutions to installedReads)
    }

    // ---- A failed answer, and a store that can't open (C2.12 point 7, A-C3-11) ----

    // The manager asks the store afresh after a failed answer, so a read that got one asks again.
    @Test
    fun `after a failed first answer the row reads none saved, and the next entry to Settings asks the store again`() = runTest(dispatcher) {
        install()
        val vm = row()
        vm.refresh()
        runCurrent()
        loader.failRegions()
        runCurrent()
        assertEquals(OfflineMapsUiState.Empty, vm.detail.value)

        vm.refresh()
        runCurrent()
        assertEquals("the store is asked again", 2, loader.firstAnswerRequests)
        seedAllSaved()
        loader.releaseRegions()
        runCurrent()

        assertEquals(saved(bytes = 300_000, savedStages = 3), vm.detail.value)
    }

    @Test
    fun `after a failed first answer the screen reads no maps saved, and its next read asks the store again`() = runTest(dispatcher) {
        install()
        val vm = screen()
        runCurrent()
        loader.failRegions()
        runCurrent()
        assertEquals(OfflineMapsUiState.Empty, vm.state.value)

        tiles.save(ROUTE_ID, stages())
        runCurrent()
        completePacks()
        loader.completeNextRegion()
        runCurrent()
        assertEquals("a save's region landing asks the store again", 2, loader.firstAnswerRequests)
        loader.releaseRegions()
        runCurrent()

        assertEquals(saved(bytes = 100_000, savedStages = 1), vm.state.value)
        tiles.cancel()
        runCurrent()
    }

    // U45's loader reads a store that can't open as empty, and answers it FAILED: iOS's cold face, never a crash.
    @Test
    fun `a store that can't open reads none saved on the row and no maps saved on the screen`() = runTest(dispatcher) {
        install()
        seedAllSaved()
        loader.storeOpens = false
        val row = row()
        row.refresh()
        val screen = screen()
        runCurrent()

        loader.failRegions()
        runCurrent()
        assertEquals(OfflineMapsUiState.Empty, row.detail.value)
        assertEquals(OfflineMapsUiState.Empty, screen.state.value)
        screen.onDeleteTapped()
        assertFalse("nothing to delete", screen.confirmingDelete.value)

        row.refresh()
        runCurrent()
        assertEquals("the next entry asks again", 2, loader.firstAnswerRequests)
    }

    // ---- AE10 on Settings (C4 §6) ----------------------------------------------------

    /**
     * Every stage of 33 saved, then an Update to 30 that redraws stage 12.
     * The stage values are cached per installed (route, release), so the
     * new release's 30 Ways are read: T is 30, and stage 12's region,
     * complete under the old line's hash, no longer counts. The bytes cover
     * every region with the prefix until the posted removals of 30–32 land.
     */
    @Test
    fun `after an Update Settings reads the new release's stages, 29 of 30`() = runTest(dispatcher) {
        install(count = 33)
        seedAllSaved(count = 33)
        loader.releaseRegions()
        val row = row()
        row.refresh()
        val screen = screen()
        runCurrent()
        assertEquals(saved(bytes = 3_300_000, savedStages = 33, totalStages = 33), screen.state.value)

        install(count = 30, release = "v1.8.0", redrawn = 12)
        (30 until 33).forEach { wayStore.delete(WayStore.stageWayId(ROUTE_ID, it)) }
        row.refresh()
        runCurrent()
        assertEquals("before the removals land", saved(bytes = 3_300_000, savedStages = 29, totalStages = 30), row.detail.value)
        tiles.removeRegions(ROUTE_ID, atOrAbove = 30)
        runCurrent()

        val after = saved(bytes = 3_000_000, savedStages = 29, totalStages = 30)
        assertEquals(after to after, screen.state.value to row.detail.value)
        assertEquals("3 MB · 29 of 30 stages", OfflineMapsModel.savedLine(resources, after))
        assertEquals("$ROUTE_NAME · 3 MB", OfflineMapsModel.rowDetail(resources, after))
    }

    private companion object {
        const val ROUTE_ID = "camino-frances"
        const val ROUTE_NAME = "Camino de Santiago (Frances)"
    }
}
