// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.AudioFocusCoordinator
import org.walktalkmeditate.pilgrim.audio.FakeAudioCapture
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * Exercises the WalkViewModel action surface. Uses UnconfinedTestDispatcher
 * so coroutines launched from viewModelScope run inline; StandardTestDispatcher
 * requires advanceUntilIdle which hangs against our 1-Hz ticker flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkViewModelTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: FakeClock
    private lateinit var controller: WalkController
    private lateinit var fakeAudioCapture: FakeAudioCapture
    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var transcriptionScheduler: FakeTranscriptionScheduler
    private lateinit var viewModel: WalkViewModel
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
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
        clock = FakeClock(initial = 1_000L)
        controller = WalkController(repository, clock)
        fakeAudioCapture = FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 }))
        val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        voiceRecorder = VoiceRecorder(context, fakeAudioCapture, audioFocus, clock)
        transcriptionScheduler = FakeTranscriptionScheduler()
        viewModel = WalkViewModel(context, controller, repository, clock, voiceRecorder, transcriptionScheduler, FakeLocationSource())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `startWalk dispatches Start and the controller becomes Active`() = runTest(dispatcher) {
        controller.state.test {
            assertTrue(awaitItem() is WalkState.Idle)

            viewModel.startWalk(intention = "silence")

            assertTrue(awaitItem() is WalkState.Active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pauseWalk and resumeWalk transition controller state correctly`() = runTest(dispatcher) {
        controller.state.test {
            assertTrue(awaitItem() is WalkState.Idle)

            viewModel.startWalk()
            assertTrue(awaitItem() is WalkState.Active)

            clock.advanceTo(2_000L)
            viewModel.pauseWalk()
            assertTrue(awaitItem() is WalkState.Paused)

            clock.advanceTo(2_500L)
            viewModel.resumeWalk()
            assertTrue(awaitItem() is WalkState.Active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `finishWalk transitions controller state to Finished`() = runTest(dispatcher) {
        controller.state.test {
            assertTrue(awaitItem() is WalkState.Idle)

            viewModel.startWalk()
            assertTrue(awaitItem() is WalkState.Active)

            clock.advanceTo(5_000L)
            viewModel.finishWalk()

            assertTrue(awaitItem() is WalkState.Finished)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `finishWalk schedules transcription for the just-finished walkId`() = runTest(dispatcher) {
        viewModel.startWalk()
        val activeWalkId = controller.state.first { it is WalkState.Active }
            .let { (it as WalkState.Active).walk.walkId }

        clock.advanceTo(5_000L)
        viewModel.finishWalk()

        // viewModelScope's launch + Room hop may suspend across
        // dispatchers. Await Finished (or, equivalently, await the
        // first scheduler invocation) before asserting.
        controller.state.first { it is WalkState.Finished }

        assertEquals(listOf(activeWalkId), transcriptionScheduler.scheduledWalkIds)
    }

    @Test
    fun `uiState emits Active while subscribed`() = runTest(dispatcher) {
        viewModel.uiState.test {
            assertTrue(awaitItem().walkState is WalkState.Idle)

            viewModel.startWalk()

            assertTrue(awaitItem().walkState is WalkState.Active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restoreActiveWalk returns null when no walk is persisted`() = runTest(dispatcher) {
        assertNull(viewModel.restoreActiveWalk())
    }

    @Test
    fun `restoreActiveWalk returns the persisted walk when one exists`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 500L, intention = "grief")

        val restored = viewModel.restoreActiveWalk()

        assertEquals(walk.id, restored?.id)
    }

    @Test
    fun `routePoints is empty when idle and maps samples to LocationPoint when active`() = runTest(dispatcher) {
        // Drive the controller directly: viewModel.startWalk triggers a
        // foreground-service start that Robolectric can't satisfy, and the
        // rollback path there would immediately transition back to
        // Finished. The routePoints flow is driven off controller.state,
        // so testing with a direct controller start is an honest shape.
        viewModel.routePoints.test {
            assertTrue(awaitItem().isEmpty())

            controller.startWalk(intention = null)
            val walkId = requireActiveWalkId()
            repository.recordLocations(
                listOf(
                    RouteDataSample(
                        walkId = walkId,
                        timestamp = 1_100L,
                        latitude = 35.0,
                        longitude = 139.0,
                        horizontalAccuracyMeters = 5.0f,
                        speedMetersPerSecond = 1.2f,
                    ),
                    RouteDataSample(
                        walkId = walkId,
                        timestamp = 1_200L,
                        latitude = 35.001,
                        longitude = 139.001,
                    ),
                ),
            )

            val mapped = awaitItem()
            assertEquals(2, mapped.size)
            assertEquals(35.0, mapped[0].latitude, 1e-9)
            assertEquals(139.001, mapped[1].longitude, 1e-9)
            assertEquals(5.0f, mapped[0].horizontalAccuracyMeters)
            assertEquals(1.2f, mapped[0].speedMetersPerSecond)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `routePoints keeps the same subscription across Active-Paused-Active`() = runTest(dispatcher) {
        // Active → Paused must not cancel the DAO subscription (same walkId
        // after distinctUntilChanged collapses the state-level emissions).
        // If it did, every pause would drop the live polyline from the map.
        viewModel.routePoints.test {
            assertTrue(awaitItem().isEmpty())

            controller.startWalk(intention = null)
            val walkId = requireActiveWalkId()

            repository.recordLocation(
                RouteDataSample(walkId = walkId, timestamp = 1_100L, latitude = 0.0, longitude = 0.0),
            )
            assertEquals(1, awaitItem().size)

            clock.advanceTo(2_000L)
            controller.pauseWalk()
            expectNoEvents()

            repository.recordLocation(
                RouteDataSample(walkId = walkId, timestamp = 2_100L, latitude = 0.001, longitude = 0.0),
            )
            assertEquals(2, awaitItem().size)

            clock.advanceTo(2_500L)
            controller.resumeWalk()
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `routePoints returns to empty when the walk finishes and no new walk starts`() = runTest(dispatcher) {
        viewModel.routePoints.test {
            assertTrue(awaitItem().isEmpty())

            controller.startWalk(intention = null)
            val walkId = requireActiveWalkId()
            repository.recordLocation(
                RouteDataSample(walkId = walkId, timestamp = 1_100L, latitude = 0.0, longitude = 0.0),
            )
            assertEquals(1, awaitItem().size)

            clock.advanceTo(3_000L)
            controller.finishWalk()
            // Finished retains walkId in state (so WalkSummary can read
            // the walk), so the flow keeps the same subscription and the
            // point list survives.
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- Stage 2-C: recording surface ----------

    @Test
    fun `toggleRecording when idle starts recording`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        viewModel.voiceRecorderState.test {
            assertEquals(VoiceRecorderUiState.Idle, awaitItem())
            viewModel.toggleRecording()
            assertEquals(VoiceRecorderUiState.Recording, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleRecording when recording stops and inserts a row`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()

        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
        viewModel.audioLevel.first { it > 0f }

        clock.advanceTo(3_000L)
        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Idle }

        val recordings = repository.voiceRecordingsFor(walkId)
        assertEquals(1, recordings.size)
        assertEquals(walkId, recordings[0].walkId)
    }

    @Test
    fun `stop on empty recording maps to Cancelled Idle and no DB row`() = runTest(dispatcher) {
        // Empty bursts → read() returns -1 immediately → VoiceRecorder.stop
        // returns EmptyRecording, which the ViewModel maps to Idle (silent).
        fakeAudioCapture = FakeAudioCapture(bursts = emptyList())
        val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        voiceRecorder = VoiceRecorder(context, fakeAudioCapture, audioFocus, clock)
        viewModel = WalkViewModel(context, controller, repository, clock, voiceRecorder, transcriptionScheduler, FakeLocationSource())

        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()

        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Idle }

        assertEquals(0, repository.voiceRecordingsFor(walkId).size)
    }

    @Test
    fun `emitPermissionDenied flips state to Error with PermissionDenied kind`() {
        viewModel.emitPermissionDenied()
        val state = viewModel.voiceRecorderState.value
        assertTrue("state should be Error, was $state", state is VoiceRecorderUiState.Error)
        assertEquals(
            VoiceRecorderUiState.Kind.PermissionDenied,
            (state as VoiceRecorderUiState.Error).kind,
        )
    }

    @Test
    fun `AudioCapture init failure maps to Error with CaptureInitFailed kind`() = runTest(dispatcher) {
        fakeAudioCapture = FakeAudioCapture(startThrowable = IllegalStateException("mic busy"))
        val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        voiceRecorder = VoiceRecorder(context, fakeAudioCapture, audioFocus, clock)
        viewModel = WalkViewModel(context, controller, repository, clock, voiceRecorder, transcriptionScheduler, FakeLocationSource())

        controller.startWalk(intention = null)
        viewModel.toggleRecording()

        val errState = viewModel.voiceRecorderState
            .first { it is VoiceRecorderUiState.Error } as VoiceRecorderUiState.Error
        assertEquals(VoiceRecorderUiState.Kind.CaptureInitFailed, errState.kind)
    }

    @Test
    fun `WalkState transitioning to Finished while recording auto-stops`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()

        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
        viewModel.audioLevel.first { it > 0f }

        clock.advanceTo(3_000L)
        controller.finishWalk()

        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Idle }
        assertEquals(1, repository.voiceRecordingsFor(walkId).size)
    }

    @Test
    fun `finishWalk while recording inserts row before scheduling transcription`() =
        runTest(dispatcher) {
            // Regression guard for the scheduler-vs-INSERT race that the
            // .first { !is Recording } wait in finishWalk closes. The
            // scheduler must observe the auto-stopped recording's row.
            controller.startWalk(intention = null)
            val walkId = requireActiveWalkId()

            viewModel.toggleRecording()
            viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
            viewModel.audioLevel.first { it > 0f }

            clock.advanceTo(3_000L)
            viewModel.finishWalk()

            controller.state.first { it is WalkState.Finished }
            viewModel.voiceRecorderState.first { it !is VoiceRecorderUiState.Recording }

            // Both must be true at the moment we observe the schedule
            // call: the auto-stopped recording's row was committed AND
            // the scheduler was invoked with the just-finished walkId.
            assertEquals(1, repository.voiceRecordingsFor(walkId).size)
            assertEquals(listOf(walkId), transcriptionScheduler.scheduledWalkIds)
        }

    @Test
    fun `recordingsCount reflects rows for the active walk`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        val walkId = requireActiveWalkId()

        viewModel.recordingsCount.test {
            assertEquals(0, awaitItem())
            repository.recordVoice(
                VoiceRecording(
                    walkId = walkId,
                    startTimestamp = 1_000L,
                    endTimestamp = 2_000L,
                    durationMillis = 1_000L,
                    fileRelativePath = "recordings/x/a.wav",
                ),
            )
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissRecorderError returns state to Idle from Error`() {
        viewModel.emitPermissionDenied()
        viewModel.dismissRecorderError()
        assertEquals(VoiceRecorderUiState.Idle, viewModel.voiceRecorderState.value)
    }

    @Test
    fun `consecutive identical errors get distinct ids so LaunchedEffect re-keys`() {
        viewModel.emitPermissionDenied()
        val first = viewModel.voiceRecorderState.value as VoiceRecorderUiState.Error
        viewModel.emitPermissionDenied()
        val second = viewModel.voiceRecorderState.value as VoiceRecorderUiState.Error

        // Same message + same kind, but different ids → not equal.
        // Compose `LaunchedEffect(error)` will re-fire and reset the
        // 4s auto-dismiss timer for the second emission.
        assertEquals(first.message, second.message)
        assertEquals(first.kind, second.kind)
        assertTrue("expected distinct ids, got ${first.id} == ${second.id}", first.id != second.id)
        assertTrue("Errors must not be structurally equal", first != second)
    }

    @Test
    fun `initialCameraCenter seeds from lastKnownLocation when available`() = runTest(dispatcher) {
        val cachedFix = org.walktalkmeditate.pilgrim.domain.LocationPoint(
            timestamp = 123L,
            latitude = 37.7749,
            longitude = -122.4194,
        )
        val seededSource = FakeLocationSource(lastKnown = cachedFix)
        val vm = WalkViewModel(
            context, controller, repository, clock, voiceRecorder,
            transcriptionScheduler, seededSource,
        )

        val seen = vm.initialCameraCenter.first { it != null }
        assertEquals(cachedFix, seen)
    }

    @Test
    fun `initialCameraCenter falls back to prior walks last route sample when no cached fix`() = runTest(dispatcher) {
        val priorWalk = repository.startWalk(startTimestamp = 0L)
        repository.recordLocation(
            org.walktalkmeditate.pilgrim.data.entity.RouteDataSample(
                walkId = priorWalk.id, timestamp = 100L,
                latitude = 1.0, longitude = 2.0,
            ),
        )
        repository.recordLocation(
            org.walktalkmeditate.pilgrim.data.entity.RouteDataSample(
                walkId = priorWalk.id, timestamp = 200L,
                latitude = 3.0, longitude = 4.0,
            ),
        )
        repository.finishWalk(priorWalk, endTimestamp = 300L)

        val vm = WalkViewModel(
            context, controller, repository, clock, voiceRecorder,
            transcriptionScheduler, FakeLocationSource(lastKnown = null),
        )

        val seen = vm.initialCameraCenter.first { it != null }
        // Cascade should pick the LAST sample chronologically — where
        // the user finished their prior walk, a better seed than where
        // they started it.
        assertEquals(3.0, seen!!.latitude, 0.0001)
        assertEquals(4.0, seen.longitude, 0.0001)
    }

    private fun requireActiveWalkId(): Long =
        controller.state.value.let { state ->
            when (state) {
                is WalkState.Active -> state.walk.walkId
                else -> error("expected Active state, got $state")
            }
        }
}

private class FakeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
