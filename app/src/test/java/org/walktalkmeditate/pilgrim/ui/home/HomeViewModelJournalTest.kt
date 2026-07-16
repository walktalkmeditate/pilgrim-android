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
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.home.scenery.WalkThreshold
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

    @Test
    fun `snapshots carry thresholds and foundPlaces from walk history with one bulk icon fetch`() = runTest(dispatcher) {
        // Chronologically: walk1 (#1, wander) → practice gate. walk2 (seek,
        // 2 arrivals, first ever) → seeking gate. walk3 (seek, 1 arrival,
        // arrivalsBefore = 2, no crossing) → no gate, foundPlaces = 1.
        val walk1 = runBlocking { repo.startWalk(startTimestamp = 1_000_000L) }
        val walk2 = runBlocking { repo.startWalk(startTimestamp = 2_000_000L) }
        val walk3 = runBlocking { repo.startWalk(startTimestamp = 3_000_000L) }
        runBlocking {
            repo.finishWalk(walk1, endTimestamp = 1_600_000L)

            repo.recordEvent(
                WalkEvent(walkId = walk2.id, timestamp = 2_000_001L, eventType = WalkEventType.SEEK_MODE),
            )
            repo.addWaypoint(arrivalWaypoint(walk2.id, timestamp = 2_100_000L))
            repo.addWaypoint(arrivalWaypoint(walk2.id, timestamp = 2_200_000L))
            repo.finishWalk(walk2, endTimestamp = 2_600_000L)

            repo.recordEvent(
                WalkEvent(walkId = walk3.id, timestamp = 3_000_001L, eventType = WalkEventType.SEEK_MODE),
            )
            repo.addWaypoint(arrivalWaypoint(walk3.id, timestamp = 3_100_000L))
            // A user-picked waypoint icon must never count as an arrival.
            repo.addWaypoint(
                Waypoint(
                    walkId = walk3.id,
                    timestamp = 3_200_000L,
                    latitude = 0.0,
                    longitude = 0.0,
                    icon = "leaf",
                ),
            )
            repo.finishWalk(walk3, endTimestamp = 3_600_000L)
        }
        val seekFetches = AtomicInteger(0)
        val iconFetches = AtomicInteger(0)
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
                seekFetches.incrementAndGet()
                return super.seekWalkIds()
            }

            override suspend fun waypointIconsByWalk(): Map<Long, List<String?>> {
                iconFetches.incrementAndGet()
                return super.waypointIconsByWalk()
            }
        }
        val v = newVm(repository = spyRepo)
        vm = v
        v.journalState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item !is JournalUiState.Loaded) item = awaitItem()
            val byId = item.snapshots.associateBy { it.id }

            assertEquals(WalkThreshold.Practice, byId.getValue(walk1.id).threshold)
            assertEquals(0, byId.getValue(walk1.id).foundPlaces)

            assertEquals(WalkThreshold.Seeking, byId.getValue(walk2.id).threshold)
            assertEquals(2, byId.getValue(walk2.id).foundPlaces)
            assertTrue(byId.getValue(walk2.id).isSeek)

            assertEquals(null, byId.getValue(walk3.id).threshold)
            assertEquals("the decoy leaf waypoint must not count", 1, byId.getValue(walk3.id).foundPlaces)
            assertTrue(byId.getValue(walk3.id).isSeek)

            // One bulk waypoint-icons query per snapshot build — the same
            // once-per-build cadence as the seek-id fetch, never per-walk
            // faulting.
            assertTrue(iconFetches.get() >= 1)
            assertEquals(seekFetches.get(), iconFetches.get())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `waypoint icon fetch failure degrades cairns and seeking gates without killing the journal`() = runTest(dispatcher) {
        val walk1 = runBlocking { repo.startWalk(startTimestamp = 1_000_000L) }
        val seek = runBlocking { repo.startWalk(startTimestamp = 2_000_000L) }
        runBlocking {
            repo.finishWalk(walk1, endTimestamp = 1_600_000L)
            repo.recordEvent(
                WalkEvent(walkId = seek.id, timestamp = 2_000_001L, eventType = WalkEventType.SEEK_MODE),
            )
            repo.addWaypoint(arrivalWaypoint(seek.id, timestamp = 2_100_000L))
            repo.finishWalk(seek, endTimestamp = 2_600_000L)
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
            override suspend fun waypointIconsByWalk(): Map<Long, List<String?>> =
                throw RuntimeException("simulated waypoint-table read failure")
        }
        val v = newVm(repository = failingRepo)
        vm = v
        v.journalState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item !is JournalUiState.Loaded) item = awaitItem()
            assertEquals(2, item.snapshots.size)
            val byId = item.snapshots.associateBy { it.id }
            // Practice gates need no arrivals — they survive the degrade.
            assertEquals(WalkThreshold.Practice, byId.getValue(walk1.id).threshold)
            // The seek walk loses its cairn data but keeps its glyph.
            assertEquals(0, byId.getValue(seek.id).foundPlaces)
            assertEquals(null, byId.getValue(seek.id).threshold)
            assertTrue(byId.getValue(seek.id).isSeek)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun arrivalWaypoint(walkId: Long, timestamp: Long) = Waypoint(
        walkId = walkId,
        timestamp = timestamp,
        latitude = 0.0,
        longitude = 0.0,
        icon = SeekPersistence.ARRIVAL_WAYPOINT_ICON,
    )

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
