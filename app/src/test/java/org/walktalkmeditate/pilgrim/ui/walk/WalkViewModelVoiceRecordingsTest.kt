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
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.walk.WalkController

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
        controller = WalkController(repository, clock)
        val fakeAudioCapture = FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 }))
        val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        voiceRecorder = VoiceRecorder(context, fakeAudioCapture, audioFocus, clock)
        viewModel = WalkViewModel(
            context, controller, repository, clock, voiceRecorder, FakeLocationSource(),
            org.walktalkmeditate.pilgrim.data.recovery.FakeWalkRecoveryRepository(),
            org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `talkMillis is 0L when no walk in progress`() = runTest(dispatcher) {
        viewModel.talkMillis.test(timeout = 5.seconds) {
            assertEquals(0L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `talkMillis sums durationMillis across rows for the active walk`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()

        viewModel.talkMillis.test(timeout = 5.seconds) {
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

        viewModel.talkMillis.test(timeout = 5.seconds) {
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
        }.test(timeout = 5.seconds) {
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
            assertEquals(1 to 5_000L, awaitItem())

            repository.recordVoice(
                VoiceRecording(
                    walkId = walkId,
                    startTimestamp = 6_000L,
                    endTimestamp = 9_000L,
                    durationMillis = 3_000L,
                    fileRelativePath = "recordings/x/b.wav",
                ),
            )
            assertEquals(2 to 8_000L, awaitItem())

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

private class TestClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
