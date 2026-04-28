// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.AudioFocusCoordinator
import org.walktalkmeditate.pilgrim.audio.FakeAudioCapture
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * Mirrors the [WalkViewModelVoiceRecordingsTest] harness so the
 * [WalkViewModel.waypointCount] flow is verified end-to-end through the
 * real Room DAO rather than against a fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkViewModelWaypointCountTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: WaypointTestClock
    private lateinit var controller: WalkController
    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var viewModel: WalkViewModel
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
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
        clock = WaypointTestClock(initial = 1_000L)
        controller = WalkController(repository, clock)
        val fakeAudioCapture = FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 }))
        val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        voiceRecorder = VoiceRecorder(context, fakeAudioCapture, audioFocus, clock)
        viewModel = WalkViewModel(
            context, controller, repository, clock, voiceRecorder, FakeLocationSource(),
            org.walktalkmeditate.pilgrim.data.recovery.FakeWalkRecoveryRepository(),
            org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `waypointCount is 0 when no walk in progress`() = runTest(dispatcher) {
        viewModel.waypointCount.test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `waypointCount increments when a waypoint is dropped`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        // Need a location fix so recordWaypoint() doesn't no-op.
        controller.recordLocation(
            LocationPoint(timestamp = 1_100L, latitude = 0.0, longitude = 0.0),
        )

        viewModel.waypointCount.test {
            assertEquals(0, awaitItem())

            viewModel.dropWaypoint()
            assertEquals(1, awaitItem())

            viewModel.dropWaypoint()
            assertEquals(2, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `waypointCount resets to 0 on next walk`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        controller.recordLocation(
            LocationPoint(timestamp = 1_100L, latitude = 0.0, longitude = 0.0),
        )
        viewModel.dropWaypoint()

        viewModel.waypointCount.test {
            assertEquals(1, awaitItem())

            clock.advanceTo(6_000L)
            controller.finishWalk()
            controller.startWalk(intention = null)
            assertEquals(0, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Suppress("unused")
    private fun requireActiveWalkId(): Long =
        controller.state.value.let { state ->
            when (state) {
                is WalkState.Active -> state.walk.walkId
                else -> error("expected Active state, got $state")
            }
        }
}

private class WaypointTestClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
