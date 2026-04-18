// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.Clock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeViewModelTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: FakeHomeClock
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
        )
        clock = FakeHomeClock(initial = 10_000_000L)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `Empty when no finished walks exist`() = runTest(dispatcher) {
        val vm = HomeViewModel(context, repository, clock)

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

        val vm = HomeViewModel(context, repository, clock)

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

        val vm = HomeViewModel(context, repository, clock)

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

        val vm = HomeViewModel(context, repository, clock)

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

        val vm = HomeViewModel(context, repository, clock)

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

        val vm = HomeViewModel(context, repository, clock)

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

        val vm = HomeViewModel(context, repository, clock)

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

        val vm = HomeViewModel(context, repository, clock)

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

        val vm = HomeViewModel(context, repository, clock)

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
}

private class FakeHomeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
}
