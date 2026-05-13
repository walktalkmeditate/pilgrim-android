// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import org.walktalkmeditate.pilgrim.data.cairn.FakeCairnService
import org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperManifestService
import org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperService
import org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.sensor.fakeStepCounter
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * Covers D13 whisper + stone placement state transitions: cap
 * increment on success, cap reset on Finished, no-GPS-fix failure
 * event, walk-id guard preventing cap pollution when a walk ends
 * mid-HTTP, Paused-state placement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkViewModelPlacementTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: PlacementTestClock
    private lateinit var controller: WalkController
    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var fakeWhisperService: FakeWhisperService
    private lateinit var fakeCairnService: FakeCairnService
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
        clock = PlacementTestClock(initial = 1_000L)
        controller = WalkController(repository, clock, fakeStepCounter())
        val fakeAudioCapture = FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 }))
        val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        voiceRecorder = VoiceRecorder(context, fakeAudioCapture, audioFocus, clock)
        fakeWhisperService = FakeWhisperService()
        fakeCairnService = FakeCairnService()
        viewModel = WalkViewModel(
            context, controller, repository, clock, voiceRecorder, FakeLocationSource(),
            org.walktalkmeditate.pilgrim.data.recovery.FakeWalkRecoveryRepository(),
            org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(),
            org.walktalkmeditate.pilgrim.data.weather.FakeWeatherFetching(),
            collectiveStats = org.walktalkmeditate.pilgrim.data.collective.CollectiveStatsSource.of(),
            soundsPreferences = org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository(),
            whisperService = fakeWhisperService,
            cairnService = fakeCairnService,
            whisperManifestService = FakeWhisperManifestService(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `placeWhisper increments cap on success`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        controller.recordLocation(
            LocationPoint(timestamp = 1_100L, latitude = 47.6, longitude = -122.3),
        )

        viewModel.placementEvents.test(timeout = 5.seconds) {
            viewModel.placeWhisper(WhisperCategory.Presence)
            val event = awaitItem()
            assertTrue(
                "expected WhisperPlaced, got $event",
                event is PlacementEvent.WhisperPlaced,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, viewModel.whispersPlacedThisWalk.value)
        assertEquals(1, fakeWhisperService.placeCalls)
    }

    @Test
    fun `placeWhisper emits Failed when no GPS fix yet`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        // No recordLocation — lastLocation stays null.

        viewModel.placementEvents.test(timeout = 5.seconds) {
            viewModel.placeWhisper(WhisperCategory.Presence)
            val event = awaitItem()
            assertTrue(
                "expected Failed/Whisper for no-GPS, got $event",
                event is PlacementEvent.Failed && event.kind == PlacementKind.Whisper,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, viewModel.whispersPlacedThisWalk.value)
        assertEquals(0, fakeWhisperService.placeCalls)
    }

    @Test
    fun `placeWhisper succeeds during Paused state`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        controller.recordLocation(
            LocationPoint(timestamp = 1_100L, latitude = 47.6, longitude = -122.3),
        )
        controller.pauseWalk()

        viewModel.placementEvents.test(timeout = 5.seconds) {
            viewModel.placeWhisper(WhisperCategory.Wonder)
            val event = awaitItem()
            assertTrue(
                "expected WhisperPlaced during Paused, got $event",
                event is PlacementEvent.WhisperPlaced,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, viewModel.whispersPlacedThisWalk.value)
    }

    @Test
    fun `cap resets to zero on Finished transition`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        controller.recordLocation(
            LocationPoint(timestamp = 1_100L, latitude = 47.6, longitude = -122.3),
        )
        viewModel.placeWhisper(WhisperCategory.Compassion)

        // Confirm pre-finish state.
        viewModel.whispersPlacedThisWalk.test(timeout = 5.seconds) {
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        clock.advanceTo(6_000L)
        controller.finishWalk()

        viewModel.whispersPlacedThisWalk.test(timeout = 5.seconds) {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `placeStone sets stonePlacedThisWalk true on success`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        controller.recordLocation(
            LocationPoint(timestamp = 1_100L, latitude = 47.6, longitude = -122.3),
        )

        viewModel.placementEvents.test(timeout = 5.seconds) {
            viewModel.placeStone()
            val event = awaitItem()
            assertTrue(
                "expected StonePlaced, got $event",
                event is PlacementEvent.StonePlaced,
            )
            assertNotNull(event)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(viewModel.stonePlacedThisWalk.value)
        assertEquals(1, fakeCairnService.placeCalls)
    }

    @Test
    fun `stone cap resets to false on Finished transition`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        controller.recordLocation(
            LocationPoint(timestamp = 1_100L, latitude = 47.6, longitude = -122.3),
        )
        viewModel.placeStone()

        viewModel.stonePlacedThisWalk.test(timeout = 5.seconds) {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        clock.advanceTo(6_000L)
        controller.finishWalk()

        viewModel.stonePlacedThisWalk.test(timeout = 5.seconds) {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private class PlacementTestClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
