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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.walktalkmeditate.pilgrim.audio.AudioFocusCoordinator
import org.walktalkmeditate.pilgrim.audio.FakeAudioCapture
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.audio.VoiceRecorderError
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.sensor.fakeStepCounter
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl

/**
 * Exercises the single-source [WalkViewModel.voiceRecordings] flow that
 * powers both [WalkViewModel.recordingsCount] and the new
 * [WalkViewModel.talkMillis] derivation. Mirrors the [WalkViewModelTest]
 * harness — UnconfinedTestDispatcher + real in-memory Room — so behavior
 * is verified end-to-end through the DAO rather than against a fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkViewModelVoiceRecordingsTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: TestClock
    private lateinit var controller: WalkController
    private lateinit var voiceRecorder: VoiceRecorder
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
        clock = TestClock(initial = 1_000L)
        controller = WalkControllerImpl(repository, clock, fakeStepCounter())
        val fakeAudioCapture = FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 }))
        val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        voiceRecorder = VoiceRecorder(context, fakeAudioCapture, audioFocus, clock)
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
    fun `talkMillis is 0L when no walk in progress`() = runTest(dispatcher) {
        viewModel.talkMillis.test(timeout = 10.seconds) {
            assertEquals(0L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `talkMillis sums durationMillis across rows for the active walk`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()

        viewModel.talkMillis.test(timeout = 10.seconds) {
            assertEquals(0L, awaitItem())
            repository.recordVoice(
                VoiceRecording(
                    walkId = walkId,
                    startTimestamp = 1_000L,
                    endTimestamp = 6_000L,
                    durationMillis = 5_000L,
                    fileRelativePath = "recordings/x/a.wav",
                ),
            )
            assertEquals(5_000L, awaitItem())

            repository.recordVoice(
                VoiceRecording(
                    walkId = walkId,
                    startTimestamp = 7_000L,
                    endTimestamp = 14_500L,
                    durationMillis = 7_500L,
                    fileRelativePath = "recordings/x/b.wav",
                ),
            )
            assertEquals(12_500L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // #217: the chip used to derive from Room rows alone, so it sat frozen
    // for the whole recording. iOS recomputes
    // `completed + Date().timeIntervalSince(recordingStart)` on its 1 Hz
    // walk timer (ActiveWalkViewModel.swift:455-458@2ee1185).
    @Test
    fun `talkMillis ticks second by second while a recording is in flight`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()
        repository.recordVoice(
            VoiceRecording(
                walkId = walkId,
                startTimestamp = 1_000L,
                endTimestamp = 5_000L,
                durationMillis = 4_000L,
                fileRelativePath = "recordings/x/done.wav",
            ),
        )

        clock.advanceTo(10_000L)
        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }

        val trace = mutableListOf<Long>()
        viewModel.talkMillis.test(timeout = 10.seconds) {
            awaitTotal(4_000L, trace)

            clock.advanceTo(11_000L)
            advanceTimeBy(TICK_MS)
            awaitTotal(5_000L, trace)

            clock.advanceTo(12_000L)
            advanceTimeBy(TICK_MS)
            awaitTotal(6_000L, trace)

            cancelAndIgnoreRemainingEvents()
        }
        assertMonotonic(trace)
    }

    // The row lands asynchronously through Room's invalidation tracker, so
    // a naive "completed rows only" total dips by the whole recording for
    // the frames between the recorder going Idle and the row arriving.
    @Test
    fun `talkMillis never dips across the stop seam`() = runTest(dispatcher) {
        controller.startWalk(intention = null)

        clock.advanceTo(10_000L)
        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
        viewModel.audioLevel.first { it > 0f }

        val trace = mutableListOf<Long>()
        viewModel.talkMillis.test(timeout = 10.seconds) {
            clock.advanceTo(13_000L)
            advanceTimeBy(TICK_MS)
            awaitTotal(3_000L, trace)

            viewModel.toggleRecording()
            viewModel.voiceRecorderState.first { it !is VoiceRecorderUiState.Recording }
            advanceTimeBy(TICK_MS)
            trace += cancelAndConsumeRemainingEvents()
                .filterIsInstance<app.cash.turbine.Event.Item<Long>>()
                .map { it.value }
        }

        assertMonotonic(trace)
        assertEquals(
            "the finished recording must stay counted across the seam: $trace",
            3_000L,
            trace.last(),
        )
    }

    @Test
    fun `talkMillis stays frozen at the recorded total after the recording ends`() =
        runTest(dispatcher) {
            controller.startWalk(intention = null)

            clock.advanceTo(10_000L)
            viewModel.toggleRecording()
            viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
            viewModel.audioLevel.first { it > 0f }

            clock.advanceTo(13_000L)
            viewModel.toggleRecording()
            viewModel.voiceRecorderState.first { it !is VoiceRecorderUiState.Recording }

            viewModel.talkMillis.test(timeout = 10.seconds) {
                awaitTotal(3_000L, mutableListOf())
                // Six more heartbeats with no recording: a frozen total is
                // the correct behavior once the talk is over.
                clock.advanceTo(19_000L)
                advanceTimeBy(TICK_MS * 6)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `talkMillis stays 0L across ticks on a walk with no recordings`() = runTest(dispatcher) {
        controller.startWalk(intention = null)

        viewModel.talkMillis.test(timeout = 10.seconds) {
            assertEquals(0L, awaitItem())
            clock.advanceTo(60_000L)
            advanceTimeBy(TICK_MS * 10)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `talkMillis resets to 0L for a fresh walk after the previous finishes`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val firstWalkId = requireActiveWalkId()
        repository.recordVoice(
            VoiceRecording(
                walkId = firstWalkId,
                startTimestamp = 1_000L,
                endTimestamp = 5_000L,
                durationMillis = 4_000L,
                fileRelativePath = "recordings/x/a.wav",
            ),
        )

        viewModel.talkMillis.test(timeout = 10.seconds) {
            assertEquals(4_000L, awaitItem())

            // Finish keeps walkId in state (mirroring routePoints' "keeps
            // the same subscription" semantics) so the flow stays at
            // 4_000L. The reset signal is the next walk's distinct walkId
            // tripping flatMapLatest.
            clock.advanceTo(6_000L)
            controller.finishWalk()
            controller.startWalk(intention = null)
            assertEquals(0L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recordingsCount and talkMillis update in lockstep on each insert`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()

        // combine() of both downstream flows proves they propagate from
        // ONE upstream emission — if voiceRecordings ever stopped being a
        // shared single source (e.g. someone refactored each downstream
        // back to its own observeVoiceRecordings subscription), this test
        // would still pass, but the test serves as a behavioral regression
        // guard: both flows MUST update together for any single insert.
        combine(viewModel.recordingsCount, viewModel.talkMillis) { count, millis ->
            count to millis
        }.test(timeout = 10.seconds) {
            assertEquals(0 to 0L, awaitItem())

            repository.recordVoice(
                VoiceRecording(
                    walkId = walkId,
                    startTimestamp = 0L,
                    endTimestamp = 5_000L,
                    durationMillis = 5_000L,
                    fileRelativePath = "recordings/x/a.wav",
                ),
            )
            awaitPair(1 to 5_000L)

            repository.recordVoice(
                VoiceRecording(
                    walkId = walkId,
                    startTimestamp = 6_000L,
                    endTimestamp = 9_000L,
                    durationMillis = 3_000L,
                    fileRelativePath = "recordings/x/b.wav",
                ),
            )
            awaitPair(2 to 8_000L)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // AF11: an OS audio-focus loss finalizes the in-flight recording; the VM
    // persists the captured audio and surfaces an Interrupted banner. The
    // recorder emits on a real executor thread (covered by VoiceRecorderTest),
    // so this drives the VM handler directly for determinism.
    @Test
    fun `interruption with audio persists the recording and surfaces Interrupted`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()
        val recording = VoiceRecording(
            walkId = walkId,
            startTimestamp = 1_000L,
            endTimestamp = 3_000L,
            durationMillis = 2_000L,
            fileRelativePath = "recordings/x/interrupted.wav",
        )

        viewModel.handleRecordingInterruption(Result.success(recording))

        val state = viewModel.voiceRecorderState.value
        assertTrue("expected Error state, got $state", state is VoiceRecorderUiState.Error)
        assertEquals(
            VoiceRecorderUiState.Kind.Interrupted,
            (state as VoiceRecorderUiState.Error).kind,
        )
        viewModel.recordingsCount.test(timeout = 10.seconds) {
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `interruption before any audio surfaces Interrupted without persisting`() = runTest(dispatcher) {
        controller.startWalk(intention = null)

        viewModel.handleRecordingInterruption(Result.failure(VoiceRecorderError.EmptyRecording))

        val state = viewModel.voiceRecorderState.value
        assertEquals(
            VoiceRecorderUiState.Kind.Interrupted,
            (state as VoiceRecorderUiState.Error).kind,
        )
        viewModel.recordingsCount.test(timeout = 10.seconds) {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun requireActiveWalkId(): Long =
        controller.state.value.let { state ->
            when (state) {
                is WalkState.Active -> state.walk.walkId
                else -> error("expected Active state, got $state")
            }
        }
}

/** One period of `WalkViewModel.TICK_INTERVAL_MS`, plus a nudge past it. */
private const val TICK_MS = 1_001L

/**
 * Collect until the total reaches [expected], recording everything seen
 * in [trace] so the caller can assert the path taken as well as the
 * destination.
 *
 * A settled `talkMillis` is reached over more than one emission: `stateIn`
 * hands every new collector its 0L seed first, and the five-way `combine`
 * behind it lands a dispatch after the sibling single-`map` flows fed by
 * the same Room emission. Asserting only `awaitItem()` would pin the test
 * to that dispatch count; asserting the trace is monotonic (see
 * [assertMonotonic]) is the property that actually matters.
 */
private suspend fun app.cash.turbine.ReceiveTurbine<Long>.awaitTotal(
    expected: Long,
    trace: MutableList<Long>,
) {
    while (true) {
        val value = awaitItem()
        trace += value
        if (value == expected) return
    }
}

private fun assertMonotonic(trace: List<Long>) {
    assertEquals("talk total regressed: $trace", trace.sorted(), trace)
}

/**
 * Collect until both flows reflect the same insert. `recordingsCount` is
 * one `map` off the shared row flow while `talkMillis` is a five-way
 * `combine`, so the count can land a dispatch ahead of the total — the
 * guarantee under test is that a single insert moves BOTH, not that they
 * arrive in the same emission.
 */
private suspend fun app.cash.turbine.ReceiveTurbine<Pair<Int, Long>>.awaitPair(
    expected: Pair<Int, Long>,
) {
    val seen = mutableListOf<Pair<Int, Long>>()
    while (true) {
        val value = awaitItem()
        seen += value
        if (value == expected) return
        assertTrue("expected to converge on $expected, saw $seen", seen.size < 5)
    }
}

private class TestClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
