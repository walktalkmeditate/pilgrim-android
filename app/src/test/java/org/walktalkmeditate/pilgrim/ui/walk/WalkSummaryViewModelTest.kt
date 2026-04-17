// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.WalkEventType

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryViewModelTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
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
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `NotFound state when walk row is missing`() = runTest(dispatcher) {
        val vm = WalkSummaryViewModel(repository, SavedStateHandle(mapOf("walkId" to 999L)))

        vm.state.test {
            // Might be Loading first, then NotFound
            var item = awaitItem()
            if (item is WalkSummaryUiState.Loading) item = awaitItem()
            assertTrue(item is WalkSummaryUiState.NotFound)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded state reports totalElapsed, distance, and zero paused when no events`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 61_000L)
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 1_100L, latitude = 0.0, longitude = 0.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 60_900L, latitude = 0.0, longitude = 0.001),
        )

        val vm = WalkSummaryViewModel(repository, SavedStateHandle(mapOf("walkId" to walk.id)))

        vm.state.test {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val loaded = item as WalkSummaryUiState.Loaded
            val s = loaded.summary
            assertEquals(60_000L, s.totalElapsedMillis)
            assertEquals(60_000L, s.activeWalkingMillis)
            assertEquals(0L, s.totalPausedMillis)
            assertEquals(0L, s.totalMeditatedMillis)
            // ~111 m for 0.001 degree at equator.
            assertEquals(111.0, s.distanceMeters, 1.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `paused and meditation pairs subtract correctly from activeWalking`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        // 10-second pause mid-walk
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 10_000L, eventType = WalkEventType.PAUSED))
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 20_000L, eventType = WalkEventType.RESUMED))
        // 5-second meditation later
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 40_000L, eventType = WalkEventType.MEDITATION_START))
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 45_000L, eventType = WalkEventType.MEDITATION_END))

        val vm = WalkSummaryViewModel(repository, SavedStateHandle(mapOf("walkId" to walk.id)))

        vm.state.test {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val s = (item as WalkSummaryUiState.Loaded).summary
            assertEquals(60_000L, s.totalElapsedMillis)
            assertEquals(10_000L, s.totalPausedMillis)
            assertEquals(5_000L, s.totalMeditatedMillis)
            // activeWalking = 60 - 10 - 5 = 45 seconds
            assertEquals(45_000L, s.activeWalkingMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `walk finished from Paused state — dangling PAUSED counted via closeAt`() = runTest(dispatcher) {
        // Regression test for the "dangling pause inflates activeWalking" bug.
        // User paused at t=30s, finished at t=60s (reducer writes no synthetic RESUMED).
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 30_000L, eventType = WalkEventType.PAUSED))

        val vm = WalkSummaryViewModel(repository, SavedStateHandle(mapOf("walkId" to walk.id)))

        vm.state.test {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val s = (item as WalkSummaryUiState.Loaded).summary
            assertEquals(60_000L, s.totalElapsedMillis)
            // Pause from 30s → walk end (60s) = 30 seconds paused
            assertEquals(30_000L, s.totalPausedMillis)
            // activeWalking = 60 - 30 = 30 seconds (not 60!)
            assertEquals(30_000L, s.activeWalkingMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `walk finished from Meditating state — dangling MEDITATION_START counted via closeAt`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 40_000L, eventType = WalkEventType.MEDITATION_START))

        val vm = WalkSummaryViewModel(repository, SavedStateHandle(mapOf("walkId" to walk.id)))

        vm.state.test {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val s = (item as WalkSummaryUiState.Loaded).summary
            assertEquals(20_000L, s.totalMeditatedMillis)
            assertEquals(40_000L, s.activeWalkingMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
