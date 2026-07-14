// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.FakePreferencesDataStore
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HomeViewModelJournalTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repo: WalkRepository
    private lateinit var hemisphereDataStore: DataStore<Preferences>
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
        repo = WalkRepository(
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
        // In-memory DataStore + test-dispatcher scope so the hemisphere
        // chain resolves in runTest virtual time — canonical fix for the
        // ci-realtime-withtimeout flake family (see [FakePreferencesDataStore]).
        hemisphereScope = CoroutineScope(SupervisorJob() + dispatcher)
        hemisphereDataStore = FakePreferencesDataStore()
        hemisphereRepo = HemisphereRepository(
            hemisphereDataStore,
            FakeLocationSource(),
            hemisphereScope,
        )
    }

    @After
    fun tearDown() {
        // Stage 7-A flake-fix: cancel viewModelScope BEFORE db.close().
        vm?.viewModelScope?.coroutineContext?.get(Job)?.cancel()
        db.close()
        hemisphereScope.coroutineContext[Job]?.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `journalState emits Empty when no finished walks`() = runTest(dispatcher) {
        val v = newVm()
        vm = v
        v.journalState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is JournalUiState.Loading) item = awaitItem()
            assertEquals(JournalUiState.Empty, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `journalState emits Loaded with one snapshot for one finished walk`() = runTest(dispatcher) {
        val walk = runBlocking { repo.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repo.finishWalk(walk, endTimestamp = 5_600_000L) }
        val v = newVm()
        vm = v
        v.journalState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item !is JournalUiState.Loaded) item = awaitItem()
            assertEquals(1, item.snapshots.size)
            assertEquals(walk.id, item.snapshots[0].id)
            assertTrue(item.snapshots[0].walkOnlyDurationSec >= 0L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `snapshots flag isSeek only for walks with a SEEK_MODE event`() = runTest(dispatcher) {
        val wander = runBlocking { repo.startWalk(startTimestamp = 1_000_000L) }
        val seek = runBlocking { repo.startWalk(startTimestamp = 2_000_000L) }
        val arrivalOnly = runBlocking { repo.startWalk(startTimestamp = 3_000_000L) }
        runBlocking {
            repo.finishWalk(wander, endTimestamp = 1_600_000L)
            repo.recordEvent(
                WalkEvent(walkId = seek.id, timestamp = 2_000_001L, eventType = WalkEventType.SEEK_MODE),
            )
            repo.finishWalk(seek, endTimestamp = 2_600_000L)
            // A stray SEEK_ARRIVAL without SEEK_MODE must not flag the
            // walk — only the mode marker means "this was a seek".
            repo.recordEvent(
                WalkEvent(walkId = arrivalOnly.id, timestamp = 3_000_001L, eventType = WalkEventType.SEEK_ARRIVAL),
            )
            repo.finishWalk(arrivalOnly, endTimestamp = 3_600_000L)
        }
        val bulkFetches = AtomicInteger(0)
        val spyRepo = object : WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        ) {
            override suspend fun seekWalkIds(): Set<Long> {
                bulkFetches.incrementAndGet()
                return super.seekWalkIds()
            }
        }
        val v = newVm(repository = spyRepo)
        vm = v
        v.journalState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item !is JournalUiState.Loaded) item = awaitItem()
            val byId = item.snapshots.associateBy { it.id }
            assertFalse(byId.getValue(wander.id).isSeek)
            assertTrue(byId.getValue(seek.id).isSeek)
            assertFalse(byId.getValue(arrivalOnly.id).isSeek)
            // The flag comes from the bulk id fetch (one call per
            // snapshot build), never from per-walk event faulting.
            assertTrue(bulkFetches.get() >= 1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `seek fetch failure degrades glyphs to wander without killing the journal`() = runTest(dispatcher) {
        val walk = runBlocking { repo.startWalk(startTimestamp = 5_000_000L) }
        runBlocking {
            repo.recordEvent(
                WalkEvent(walkId = walk.id, timestamp = 5_000_001L, eventType = WalkEventType.SEEK_MODE),
            )
            repo.finishWalk(walk, endTimestamp = 5_600_000L)
        }
        val failingRepo = object : WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        ) {
            override suspend fun seekWalkIds(): Set<Long> =
                throw RuntimeException("simulated event-table read failure")
        }
        val v = newVm(repository = failingRepo)
        vm = v
        v.journalState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item !is JournalUiState.Loaded) item = awaitItem()
            assertEquals(1, item.snapshots.size)
            assertFalse(item.snapshots[0].isSeek)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun newVm(repository: WalkRepository = repo): HomeViewModel {
        val clock = object : Clock {
            override fun now(): Long = 10_000_000L
        }
        val cachedShareStore = CachedShareStore(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            Json { ignoreUnknownKeys = true },
        )
        return HomeViewModel(
            context = ApplicationProvider.getApplicationContext(),
            repository = repository,
            clock = clock,
            hemisphereRepository = hemisphereRepo,
            unitsPreferences = FakeUnitsPreferencesRepository(),
            cachedShareStore = cachedShareStore,
            practicePreferences = FakePracticePreferencesRepository(),
            archivedRegistry = org.walktalkmeditate.pilgrim.data.pilgrim.FakeArchivedWalkRegistry(),
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
    }
}
