// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoshuinViewModelTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
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
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        honorEnabled: Boolean = false,
        wayStore: org.walktalkmeditate.pilgrim.data.honor.WayStore? = null,
    ): GoshuinViewModel =
        GoshuinViewModel(
            repository,
            org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            org.walktalkmeditate.pilgrim.data.pilgrim.FakeArchivedWalkRegistry(),
            org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags(honor = honorEnabled),
            if (wayStore != null) {
                org.walktalkmeditate.pilgrim.honor.honorWalkRecordsForTests(db, context, wayStore)
            } else {
                org.walktalkmeditate.pilgrim.honor.honorWalkRecordsForTests(db, context)
            },
        )

    @Test
    fun `Empty when repository has no walks`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.uiState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is GoshuinUiState.Loading) item = awaitItem()
            assertEquals(GoshuinUiState.Empty, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Empty when only in-progress walks exist`() = runTest(dispatcher) {
        // Unfinished walk (endTimestamp = null) must not appear.
        runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is GoshuinUiState.Loading) item = awaitItem()
            assertEquals(GoshuinUiState.Empty, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded with one seal when one finished walk exists`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            assertEquals(1, loaded.seals.size)
            assertEquals(walk.id, loaded.seals[0].walkId)
            assertEquals(1, loaded.totalCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `seal carries favicon, uuid, distance, and startMillis for filter+share`() =
        runTest(dispatcher) {
            val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
            runBlocking {
                repository.finishWalk(walk, endTimestamp = 5_600_000L)
                repository.setFavicon(walk.id, "flame")
            }

            val vm = newViewModel()
            vm.uiState.test(timeout = 10.seconds) {
                val loaded = awaitLoaded(this)
                val seal = loaded.seals.first { it.walkId == walk.id }
                assertEquals(WalkFavicon.FLAME, seal.favicon)
                assertEquals(walk.uuid, seal.uuid)
                assertEquals(5_000_000L, seal.startMillis)
                assertTrue("distance >= 0", seal.distanceMeters >= 0.0)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `seal hemisphere + season latitude come from the walk's first route coordinate`() =
        runTest(dispatcher) {
            val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
            runBlocking {
                repository.recordLocation(
                    RouteDataSample(walkId = walk.id, timestamp = 5_000_000L, latitude = -33.0, longitude = 151.0),
                )
                repository.finishWalk(walk, endTimestamp = 5_600_000L)
            }

            val vm = newViewModel()
            vm.uiState.test(timeout = 10.seconds) {
                val loaded = awaitLoaded(this)
                val seal = loaded.seals.first { it.walkId == walk.id }
                // Southern route point → southern seal hemisphere (iOS parity),
                // independent of the device hemisphere.
                assertTrue("seal should be southern", seal.sealSpec.southernHemisphere)
                assertEquals(-33.0, seal.firstRouteLatitude, 0.0001)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** An earlier wander walk, then an honor walk that arrived, linked to a stored Way. */
    private fun linkedHonorWalk(store: org.walktalkmeditate.pilgrim.data.honor.WayStore): Long = runBlocking {
        val earlier = repository.startWalk(startTimestamp = 1_000_000L)
        repository.finishWalk(earlier, endTimestamp = 1_600_000L)
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.recordEvent(
            org.walktalkmeditate.pilgrim.data.entity.WalkEvent(
                walkId = walk.id,
                timestamp = 5_000_001L,
                eventType = org.walktalkmeditate.pilgrim.domain.WalkEventType.HONOR_MODE,
            ),
        )
        repository.addWaypoint(
            org.walktalkmeditate.pilgrim.data.entity.Waypoint(
                walkId = walk.id,
                timestamp = 5_300_000L,
                latitude = 0.0,
                longitude = 0.004,
                label = "Walked their way: Morning loop",
                icon = org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence.ARRIVAL_WAYPOINT_ICON,
            ),
        )
        repository.recordLocation(RouteDataSample(walkId = walk.id, timestamp = 5_000_000L, latitude = 0.0, longitude = 0.0))
        repository.recordLocation(RouteDataSample(walkId = walk.id, timestamp = 5_300_000L, latitude = 0.0, longitude = 0.004))
        repository.finishWalk(walk, endTimestamp = 5_600_000L)
        val way = org.walktalkmeditate.pilgrim.data.honor.HonorWalkState(db, store).way()
        store.save(way)
        store.link(walk.uuid, way.id, arrival = null)
        walk.id
    }

    private fun newWayStore() = org.walktalkmeditate.pilgrim.data.honor.WayStore({
        java.io.File(context.cacheDir, "goshuin-ways-${java.util.UUID.randomUUID()}")
    })

    @Test
    fun `an honor seal carries the Way's line and First Honor with the flag on`() = runTest(dispatcher) {
        val store = newWayStore()
        val walkId = linkedHonorWalk(store)

        val vm = newViewModel(honorEnabled = true, wayStore = store)
        vm.uiState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            val seal = loaded.seals.first { it.walkId == walkId }
            assertNotNull("the walk's own line", seal.sealSpec.watermark)
            assertNotNull("the Way's line beneath it", seal.sealSpec.watermark?.wayLine)
            assertEquals(GoshuinMilestone.FirstHonor, seal.milestone)
            assertEquals("First Honor", GoshuinMilestones.label(seal.milestone!!))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an honor seal loses the Way's line when the Way is deleted, and is plain with the flag off`() = runTest(dispatcher) {
        val store = newWayStore()
        val walkId = linkedHonorWalk(store)
        store.delete("walk:${org.walktalkmeditate.pilgrim.data.honor.HonorWalkState.SOURCE_WALK_UUID}")

        newViewModel(honorEnabled = true, wayStore = store).uiState.test(timeout = 10.seconds) {
            val seal = awaitLoaded(this).seals.first { it.walkId == walkId }
            assertNotNull(seal.sealSpec.watermark)
            assertNull("owner decision 3: the line goes with the link", seal.sealSpec.watermark?.wayLine)
            cancelAndIgnoreRemainingEvents()
        }
        newViewModel(honorEnabled = false, wayStore = store).uiState.test(timeout = 10.seconds) {
            val seal = awaitLoaded(this).seals.first { it.walkId == walkId }
            assertNull(seal.sealSpec.watermark?.wayLine)
            assertTrue("no honor seal with the flag off", seal.milestone != GoshuinMilestone.FirstHonor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an open book drops a deleted Way's line from its seal`() = runTest(dispatcher) {
        val store = newWayStore()
        val walkId = linkedHonorWalk(store)

        newViewModel(honorEnabled = true, wayStore = store).uiState.test(timeout = 10.seconds) {
            assertNotNull(awaitLoaded(this).seals.first { it.walkId == walkId }.sealSpec.watermark?.wayLine)
            store.delete("walk:${org.walktalkmeditate.pilgrim.data.honor.HonorWalkState.SOURCE_WALK_UUID}")
            var seal = awaitLoaded(this).seals.first { it.walkId == walkId }
            while (seal.sealSpec.watermark?.wayLine != null) seal = awaitLoaded(this).seals.first { it.walkId == walkId }
            assertNotNull("the walk keeps its own line", seal.sealSpec.watermark)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the seals are built off Main`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }
        val threadNames = java.util.Collections.synchronizedList(mutableListOf<String>())
        val spying = object : WalkRepository(
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
            override suspend fun locationSamplesFor(walkId: Long): List<RouteDataSample> {
                threadNames += Thread.currentThread().name
                return super.locationSamplesFor(walkId)
            }
        }
        val sealDispatcher = java.util.concurrent.Executors
            .newSingleThreadExecutor { Thread(it, "goshuin-seals") }
            .asCoroutineDispatcher()
        val vm = GoshuinViewModel(
            spying,
            org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            org.walktalkmeditate.pilgrim.data.pilgrim.FakeArchivedWalkRegistry(),
            org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags(honor = true),
            org.walktalkmeditate.pilgrim.honor.honorWalkRecordsForTests(db, context),
            sealDispatcher,
        )
        try {
            vm.uiState.test(timeout = 10.seconds) {
                awaitLoaded(this)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue("a walk's samples were read", threadNames.isNotEmpty())
            assertTrue("read and fitted on $threadNames", threadNames.all { it.startsWith("goshuin-seals") })
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            sealDispatcher.close()
        }
    }

    @Test
    fun `seal favicon is null when walk is untagged`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            assertNull(loaded.seals.first { it.walkId == walk.id }.favicon)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `seals ordered most-recent-end-first`() = runTest(dispatcher) {
        val older = runBlocking { repository.startWalk(startTimestamp = 1_000_000L) }
        runBlocking { repository.finishWalk(older, endTimestamp = 1_600_000L) }
        val newer = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(newer, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            assertEquals(listOf(newer.id, older.id), loaded.seals.map { it.walkId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sealSpec ink is Transparent placeholder`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            // VM must not resolve the seasonal tint — theme reads are
            // @Composable-scoped. Composable-layer tests verify tinting.
            assertEquals(Color.Transparent, loaded.seals[0].sealSpec.ink)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sealSpec carries walk uuid, start timestamp, and duration`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            val spec = loaded.seals[0].sealSpec
            assertEquals(walk.uuid, spec.uuid)
            assertEquals(walk.startTimestamp, spec.startMillis)
            assertEquals(600.0, spec.durationSeconds, 0.0001)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shortDateLabel is non-empty`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            assertTrue(
                "shortDateLabel='${loaded.seals[0].shortDateLabel}'",
                loaded.seals[0].shortDateLabel.isNotBlank(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Stage 4-D: milestone propagation -------------------------

    @Test
    fun `Loaded marks single finished walk as FirstWalk milestone`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            assertEquals(GoshuinMilestone.FirstWalk, loaded.seals[0].milestone)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded marks longest walk among 3 with LongestWalk milestone`() = runTest(dispatcher) {
        // Three walks: w1 = oldest (FirstWalk wins by precedence),
        // w2 = max distance (LongestWalk), w3 = no milestone (not 1st,
        // not longest, not 10th, second walk in the same Winter-1970
        // season-year).
        val w1 = runBlocking { repository.startWalk(startTimestamp = 1_000_000L) }
        runBlocking {
            repository.recordLocation(RouteDataSample(walkId = w1.id, timestamp = 1_100_000L, latitude = 0.0, longitude = 0.0))
            repository.recordLocation(RouteDataSample(walkId = w1.id, timestamp = 1_200_000L, latitude = 0.0, longitude = 0.0001))
            repository.finishWalk(w1, endTimestamp = 1_600_000L)
        }
        val w2 = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking {
            repository.recordLocation(RouteDataSample(walkId = w2.id, timestamp = 5_100_000L, latitude = 0.0, longitude = 0.0))
            repository.recordLocation(RouteDataSample(walkId = w2.id, timestamp = 5_200_000L, latitude = 0.0, longitude = 0.05))
            repository.finishWalk(w2, endTimestamp = 5_600_000L)
        }
        val w3 = runBlocking { repository.startWalk(startTimestamp = 9_000_000L) }
        runBlocking {
            repository.recordLocation(RouteDataSample(walkId = w3.id, timestamp = 9_100_000L, latitude = 0.0, longitude = 0.0))
            repository.recordLocation(RouteDataSample(walkId = w3.id, timestamp = 9_200_000L, latitude = 0.0, longitude = 0.005))
            repository.finishWalk(w3, endTimestamp = 9_600_000L)
        }

        val vm = newViewModel()
        vm.uiState.test(timeout = 10.seconds) {
            val loaded = awaitLoaded(this)
            val byId = loaded.seals.associateBy { it.walkId }
            assertEquals(GoshuinMilestone.FirstWalk, byId.getValue(w1.id).milestone)
            assertEquals(GoshuinMilestone.LongestWalk, byId.getValue(w2.id).milestone)
            assertNull(byId.getValue(w3.id).milestone)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun awaitLoaded(
        turbine: app.cash.turbine.ReceiveTurbine<GoshuinUiState>,
    ): GoshuinUiState.Loaded {
        // Only drain `Loading`, not `Empty`. Skipping `Empty` would hide
        // a future regression where the finished-walk filter drops all
        // rows — the test would hang on `awaitItem()` waiting for a
        // `Loaded` that never arrives, instead of failing fast with a
        // clear cast exception.
        var item = turbine.awaitItem()
        while (item is GoshuinUiState.Loading) {
            item = turbine.awaitItem()
        }
        assertNotNull(item)
        return item as GoshuinUiState.Loaded
    }
}
