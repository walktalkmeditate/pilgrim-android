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
import kotlinx.coroutines.flow.first
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
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.walk.RouteActivity
import org.walktalkmeditate.pilgrim.data.walk.RouteSegment
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.sensor.fakeStepCounter
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl

/**
 * #218 — the Active Walk route line colored the in-progress talk and
 * meditation as they happen, rather than staying flat green until the
 * summary map. iOS classifies each live fix through
 * `ActiveWalkViewModel.activityType(at:)@2ee1185:583-602`; this covers the
 * end-to-end wiring of the same rules through Room, the recorder state and
 * the meditation event log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkViewModelLiveRouteSegmentsTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: SteppableClock
    private lateinit var controller: WalkController
    private lateinit var viewModel: WalkViewModel
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(context as Application)
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
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
        clock = SteppableClock(initial = 1_000L)
        controller = WalkControllerImpl(repository, clock, fakeStepCounter())
        val voiceRecorder = VoiceRecorder(
            context,
            FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 })),
            AudioFocusCoordinator(context.getSystemService(AudioManager::class.java)),
            clock,
        )
        viewModel = WalkViewModel(
            context, controller, repository, clock, voiceRecorder, FakeLocationSource(),
            org.walktalkmeditate.pilgrim.data.recovery.FakeWalkRecoveryRepository(),
            org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(),
            org.walktalkmeditate.pilgrim.data.weather.FakeWeatherFetching(),
            collectiveStats = org.walktalkmeditate.pilgrim.data.collective.CollectiveStatsSource.of(),
            soundsPreferences = org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository(),
            whisperService = org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperService(),
            cairnService = org.walktalkmeditate.pilgrim.data.cairn.FakeCairnService(),
            whisperManifestService = org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperManifestService(),
            geoCacheService = org.walktalkmeditate.pilgrim.data.proximity.FakeGeoCacheService(),
            proximityService = org.walktalkmeditate.pilgrim.data.proximity.FakeProximityDetectionService(),
            whisperPlayer = org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperPlayer(),
            stonePlayer = org.walktalkmeditate.pilgrim.data.cairn.FakeStonePlayer(),
            intentionHistory = org.walktalkmeditate.pilgrim.data.intention.FakeIntentionHistoryRepository(),
            voiceGuidePauseController = org.walktalkmeditate.pilgrim.audio.voiceguide.FakeVoiceGuidePauseController(),
            soundscapeUiController = FakeWalkSoundscapeUiController(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an ordinary walk stays one walking segment`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()
        recordFixes(walkId, 1_000L, 2_000L, 3_000L)

        val segments = viewModel.liveRouteSegments.awaitSegments(1)
        assertEquals(listOf(RouteActivity.Walking), segments.map { it.activity })
        assertEquals(3, segments.single().points.size)
    }

    @Test
    fun `starting a recording turns the tail to talking`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()
        recordFixes(walkId, 1_000L, 2_000L)

        assertEquals(
            listOf(RouteActivity.Walking),
            viewModel.liveRouteSegments.awaitSegments(1).map { it.activity },
        )

        clock.advanceTo(3_000L)
        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
        recordFixes(walkId, 3_000L, 4_000L)

        val segments = viewModel.liveRouteSegments.awaitSegments(2)
        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Talking),
            segments.map { it.activity },
        )
        // The boundary fix belongs to both so the polylines join.
        assertEquals(listOf(1_000L, 2_000L, 3_000L), segments[0].points.map { it.timestamp })
        assertEquals(listOf(3_000L, 4_000L), segments[1].points.map { it.timestamp })
    }

    // The recording's row lands after the recorder reports Idle. The talk
    // stretch must not flick back to walking in between, or the renderer
    // would tear down and rebuild the polylines it just drew.
    @Test
    fun `the talk stretch survives the stop seam`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()
        recordFixes(walkId, 1_000L, 2_000L)

        clock.advanceTo(3_000L)
        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
        viewModel.audioLevel.first { it > 0f }
        recordFixes(walkId, 3_000L, 4_000L)
        viewModel.liveRouteSegments.awaitSegments(2)

        clock.advanceTo(5_000L)
        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it !is VoiceRecorderUiState.Recording }

        val segments = viewModel.liveRouteSegments.awaitSegments(2)
        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Talking),
            segments.map { it.activity },
        )
        assertEquals(listOf(3_000L, 4_000L), segments[1].points.map { it.timestamp })
    }

    @Test
    fun `an in-progress meditation turns the tail to meditating`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()
        recordFixes(walkId, 1_000L, 2_000L)

        clock.advanceTo(3_000L)
        controller.startMeditation()
        recordFixes(walkId, 3_000L, 4_000L)

        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Meditating),
            viewModel.liveRouteSegments.awaitSegments(2).map { it.activity },
        )
    }

    @Test
    fun `a finished meditation returns the tail to walking`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()
        recordFixes(walkId, 1_000L, 2_000L)

        clock.advanceTo(3_000L)
        controller.startMeditation()
        recordFixes(walkId, 3_000L)

        clock.advanceTo(4_000L)
        controller.endMeditation()
        recordFixes(walkId, 5_000L, 6_000L)

        assertEquals(
            listOf(RouteActivity.Walking, RouteActivity.Meditating, RouteActivity.Walking),
            viewModel.liveRouteSegments.awaitSegments(3).map { it.activity },
        )
    }

    private suspend fun recordFixes(walkId: Long, vararg timestamps: Long) {
        timestamps.forEachIndexed { i, ts ->
            repository.recordLocation(
                RouteDataSample(
                    walkId = walkId,
                    timestamp = ts,
                    latitude = 35.6 + i * 0.0001,
                    longitude = 139.7 + i * 0.0001,
                ),
            )
        }
    }

    private suspend fun kotlinx.coroutines.flow.StateFlow<List<RouteSegment>>.awaitSegments(
        count: Int,
    ): List<RouteSegment> {
        var result: List<RouteSegment> = emptyList()
        test(timeout = 10.seconds) {
            while (true) {
                val value = awaitItem()
                if (value.size == count) {
                    result = value
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
        return result
    }

    private fun requireActiveWalkId(): Long =
        controller.state.value.let { state ->
            when (state) {
                is WalkState.Active -> state.walk.walkId
                is WalkState.Meditating -> state.walk.walkId
                else -> error("expected an in-progress walk, got $state")
            }
        }
}

private class SteppableClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
