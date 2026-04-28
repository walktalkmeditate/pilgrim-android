// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeViewModelTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: FakeHomeClock
    private lateinit var hemisphereDataStore: DataStore<Preferences>
    private lateinit var fakeLocation: FakeLocationSource
    private lateinit var hemisphereRepo: HemisphereRepository
    private lateinit var hemisphereScope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WalkRepository(
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
        clock = FakeHomeClock(initial = 10_000_000L)
        // Hemisphere setup — start clean each test so a prior Southern
        // override from a previous run doesn't leak through.
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        hemisphereDataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(hemisphereStoreName) },
        )
        fakeLocation = FakeLocationSource()
        hemisphereScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hemisphereRepo = HemisphereRepository(hemisphereDataStore, fakeLocation, hemisphereScope)
    }

    @After
    fun tearDown() {
        db.close()
        hemisphereScope.coroutineContext[Job]?.cancel()
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        units: FakeUnitsPreferencesRepository = FakeUnitsPreferencesRepository(),
    ): HomeViewModel =
        HomeViewModel(context, repository, clock, hemisphereRepo, units)

    @Test
    fun `Empty when no finished walks exist`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.uiState.test {
            // Initial Loading, then Empty
            var item = awaitItem()
            while (item is HomeUiState.Loading) item = awaitItem()
            assertEquals(HomeUiState.Empty, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded with one row when one finished walk exists`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()

        vm.uiState.test {
            val loaded = awaitLoaded(this)
            assertEquals(1, loaded.rows.size)
            assertEquals(walk.id, loaded.rows[0].walkId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded skips in-progress walks (endTimestamp null)`() = runTest(dispatcher) {
        val finished = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(finished, endTimestamp = 5_600_000L) }
        runBlocking { repository.startWalk(startTimestamp = 6_000_000L) } // in-progress

        val vm = newViewModel()

        vm.uiState.test {
            val loaded = awaitLoaded(this)
            assertEquals(1, loaded.rows.size)
            assertEquals(finished.id, loaded.rows[0].walkId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded rows ordered most-recent-first`() = runTest(dispatcher) {
        val older = runBlocking { repository.startWalk(startTimestamp = 1_000_000L) }
        runBlocking { repository.finishWalk(older, endTimestamp = 1_600_000L) }
        val newer = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(newer, endTimestamp = 5_600_000L) }

        val vm = newViewModel()

        vm.uiState.test {
            val loaded = awaitLoaded(this)
            assertEquals(listOf(newer.id, older.id), loaded.rows.map { it.walkId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `row recording count reflects VoiceRecording rows`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }
        runBlocking {
            repository.recordVoice(makeRecording(walk.id, 5_100_000L, 5_105_000L))
            repository.recordVoice(makeRecording(walk.id, 5_200_000L, 5_205_000L))
        }

        val vm = newViewModel()

        vm.uiState.test {
            val loaded = awaitLoaded(this)
            assertEquals("2 voice notes", loaded.rows[0].recordingCountText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `row recording count null when walk has no recordings`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()

        vm.uiState.test {
            val loaded = awaitLoaded(this)
            assertNull(loaded.rows[0].recordingCountText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `row intention passes through verbatim`() = runTest(dispatcher) {
        val walk = runBlocking {
            repository.startWalk(startTimestamp = 5_000_000L, intention = "silence")
        }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()

        vm.uiState.test {
            val loaded = awaitLoaded(this)
            assertEquals("silence", loaded.rows[0].intention)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `row intention null when walk has no intention`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()

        vm.uiState.test {
            val loaded = awaitLoaded(this)
            assertNull(loaded.rows[0].intention)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `row distance computed from route samples`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking {
            repository.recordLocation(
                RouteDataSample(walkId = walk.id, timestamp = 5_100_000L, latitude = 0.0, longitude = 0.0),
            )
            repository.recordLocation(
                RouteDataSample(walkId = walk.id, timestamp = 5_200_000L, latitude = 0.0, longitude = 0.001),
            )
            repository.finishWalk(walk, endTimestamp = 5_600_000L)
        }

        val vm = newViewModel()

        vm.uiState.test {
            val loaded = awaitLoaded(this)
            // ~111 m for 0.001 degree longitude at equator; distanceText
            // uses "N m" format under 100 m, "X.XX km" above. 111 m
            // crosses the threshold (fits the `>= 100.0` branch).
            assertTrue(
                "distanceText must be populated for a multi-sample walk, got '${loaded.rows[0].distanceText}'",
                loaded.rows[0].distanceText.isNotBlank(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Stage 3-E coverage ---------------------------------------

    @Test
    fun `Loaded rows carry raw fields for journal-thread synthesis`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking {
            repository.recordLocation(
                RouteDataSample(walkId = walk.id, timestamp = 5_100_000L, latitude = 0.0, longitude = 0.0),
            )
            repository.recordLocation(
                RouteDataSample(walkId = walk.id, timestamp = 5_200_000L, latitude = 0.0, longitude = 0.001),
            )
            repository.finishWalk(walk, endTimestamp = 5_600_000L)
        }

        val vm = newViewModel()

        vm.uiState.test {
            val loaded = awaitLoaded(this)
            val row = loaded.rows.single()
            assertEquals(walk.uuid, row.uuid)
            assertEquals(walk.startTimestamp, row.startTimestamp)
            // 0.001° longitude at equator ≈ 111 m. Accept any positive
            // value — the exact number comes from the haversine impl.
            assertTrue("distanceMeters=${row.distanceMeters}", row.distanceMeters > 0.0)
            assertEquals(600.0, row.durationSeconds, 0.01)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded row distanceText flips to imperial when UnitsPreferences says Imperial`() = runTest(dispatcher) {
        // Stage 10-C canonical regression guard: VM-level proof that the
        // unit toggle reaches the formatted distance text. ~111 m for
        // 0.001° longitude at the equator → "0.07 mi" in Imperial.
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking {
            repository.recordLocation(
                RouteDataSample(walkId = walk.id, timestamp = 5_100_000L, latitude = 0.0, longitude = 0.0),
            )
            repository.recordLocation(
                RouteDataSample(walkId = walk.id, timestamp = 5_200_000L, latitude = 0.0, longitude = 0.001),
            )
            repository.finishWalk(walk, endTimestamp = 5_600_000L)
        }
        val vm = newViewModel(FakeUnitsPreferencesRepository(initial = UnitSystem.Imperial))
        vm.uiState.test {
            val loaded = awaitLoaded(this)
            // 111 m ≈ 0.069 mi → falls below the 0.1-mi threshold
            // → renders as feet ("364 ft" / "365 ft" depending on
            // haversine rounding). Either way, no "km" suffix.
            assertTrue(
                "expected ft suffix in imperial mode but got '${loaded.rows[0].distanceText}'",
                loaded.rows[0].distanceText.endsWith(" ft"),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hemisphere StateFlow proxies repository`() = runTest(dispatcher) {
        val vm = newViewModel()
        // Initial value: Northern by default.
        assertEquals(Hemisphere.Northern, vm.hemisphere.value)
        // Flip via the repository's public override API.
        hemisphereRepo.setOverride(Hemisphere.Southern)
        // Bridge to wall-clock because the repo's StateFlow collects
        // on a real Dispatchers.Default scope, not runTest's virtual.
        val observed = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
                vm.hemisphere.first { it == Hemisphere.Southern }
            }
        }
        assertEquals(Hemisphere.Southern, observed)
    }

    @Test
    fun `Loaded when hemisphere repository infers southern from last-known location`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }
        fakeLocation.lastKnown = LocationPoint(
            timestamp = 0L, latitude = -33.8688, longitude = 151.2093,
        )
        hemisphereRepo.refreshFromLocationIfNeeded()

        val vm = newViewModel()
        // The VM's uiState doesn't bundle hemisphere (it's a sibling
        // flow) — but a subscriber on .hemisphere should observe Southern.
        val observed = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
                vm.hemisphere.first { it == Hemisphere.Southern }
            }
        }
        assertEquals(Hemisphere.Southern, observed)
    }

    // --- helpers ---------------------------------------------------

    private suspend fun awaitLoaded(
        turbine: app.cash.turbine.ReceiveTurbine<HomeUiState>,
    ): HomeUiState.Loaded {
        var item = turbine.awaitItem()
        while (item is HomeUiState.Loading || item is HomeUiState.Empty) {
            item = turbine.awaitItem()
        }
        assertNotNull(item)
        return item as HomeUiState.Loaded
    }

    private fun makeRecording(walkId: Long, start: Long, end: Long): VoiceRecording =
        VoiceRecording(
            walkId = walkId,
            startTimestamp = start,
            endTimestamp = end,
            durationMillis = end - start,
            fileRelativePath = "recordings/test/$start.wav",
        )

    // UUID-suffixed so parallel test forks can't collide on file path.
    private val hemisphereStoreName: String = "home-vm-hemisphere-test-${java.util.UUID.randomUUID()}"
}

private class FakeHomeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
}
