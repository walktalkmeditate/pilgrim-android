// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository

/**
 * Stage 14 rewrite — covers the basic Empty/Loaded transitions on
 * [HomeViewModel.journalState] and the hemisphere proxy. Text-field
 * formatter assertions moved to the chrome layer (Bucket 14-B).
 */
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
    private var vm: HomeViewModel? = null

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
        // Stage 7-A: cancel viewModelScope BEFORE db.close() so the
        // VM's combine collector unwinds before its DAO source closes.
        vm?.viewModelScope?.coroutineContext?.get(Job)?.cancel()
        db.close()
        hemisphereScope.coroutineContext[Job]?.cancel()
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        units: FakeUnitsPreferencesRepository = FakeUnitsPreferencesRepository(),
    ): HomeViewModel {
        val cachedShareStore = CachedShareStore(
            context,
            Json { ignoreUnknownKeys = true },
        )
        return HomeViewModel(
            context = context,
            repository = repository,
            clock = clock,
            hemisphereRepository = hemisphereRepo,
            unitsPreferences = units,
            cachedShareStore = cachedShareStore,
            practicePreferences = FakePracticePreferencesRepository(),
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        ).also { vm = it }
    }

    @Test
    fun `Empty when no finished walks exist`() = runTest(dispatcher) {
        val v = newViewModel()
        v.journalState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is JournalUiState.Loading) item = awaitItem()
            assertEquals(JournalUiState.Empty, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded with one snapshot when one finished walk exists`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val v = newViewModel()

        v.journalState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            assertEquals(1, loaded.snapshots.size)
            assertEquals(walk.id, loaded.snapshots[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded skips in-progress walks (endTimestamp null)`() = runTest(dispatcher) {
        val finished = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(finished, endTimestamp = 5_600_000L) }
        runBlocking { repository.startWalk(startTimestamp = 6_000_000L) }

        val v = newViewModel()

        v.journalState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            assertEquals(1, loaded.snapshots.size)
            assertEquals(finished.id, loaded.snapshots[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded snapshots ordered most-recent-first`() = runTest(dispatcher) {
        val older = runBlocking { repository.startWalk(startTimestamp = 1_000_000L) }
        runBlocking { repository.finishWalk(older, endTimestamp = 1_600_000L) }
        val newer = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(newer, endTimestamp = 5_600_000L) }

        val v = newViewModel()

        v.journalState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            assertEquals(listOf(newer.id, older.id), loaded.snapshots.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded snapshot distance computed from route samples`() = runTest(dispatcher) {
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

        val v = newViewModel()

        v.journalState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            // ~111 m for 0.001° longitude at the equator.
            assertEquals(true, loaded.snapshots[0].distanceM > 0.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hemisphere StateFlow proxies repository`() = runTest(dispatcher) {
        val v = newViewModel()
        assertEquals(Hemisphere.Northern, v.hemisphere.value)
        hemisphereRepo.setOverride(Hemisphere.Southern)
        val observed = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
                v.hemisphere.first { it == Hemisphere.Southern }
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

        val v = newViewModel()
        val observed = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
                v.hemisphere.first { it == Hemisphere.Southern }
            }
        }
        assertEquals(Hemisphere.Southern, observed)
    }

    private suspend fun awaitLoaded(
        turbine: app.cash.turbine.ReceiveTurbine<JournalUiState>,
    ): JournalUiState.Loaded {
        var item = turbine.awaitItem()
        while (item is JournalUiState.Loading || item is JournalUiState.Empty) {
            item = turbine.awaitItem()
        }
        assertNotNull(item)
        return item as JournalUiState.Loaded
    }

    private val hemisphereStoreName: String = "home-vm-hemisphere-test-${java.util.UUID.randomUUID()}"
}

private class FakeHomeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
}
