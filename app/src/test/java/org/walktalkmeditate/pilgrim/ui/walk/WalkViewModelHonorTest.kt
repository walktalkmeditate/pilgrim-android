// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.honor.HonorReplies
import org.walktalkmeditate.pilgrim.honor.TheirSitting
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.sensor.fakeStepCounter
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl

/**
 * The walk screen's own Honor parts (parity spec D §7, E §8, E §12): "reply
 * here" filed at the walker's stop and at a focus-loss interruption (the
 * walk-end auto-stop is [org.walktalkmeditate.pilgrim.walk.WalkLifecycleObserverTest]'s),
 * and "Sit?" with the meditation screen's caption. Mirrors
 * [WalkViewModelVoiceRecordingsTest]'s harness: a real recorder over a
 * fake capture and real in-memory Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkViewModelHonorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val storeDirectory = File(context.filesDir, "walk-vm-honor-ways")
    private val store = WayStore({ storeDirectory })
    private val replies = HonorReplies(store)
    private val theirSitting = TheirSitting()
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: SteppedClock
    private lateinit var controller: WalkController
    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var viewModel: WalkViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
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
        clock = SteppedClock(initial = 1_000L)
        controller = WalkControllerImpl(repository, clock, fakeStepCounter(), FixedReleaseFlags(honor = true))
        val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        voiceRecorder = VoiceRecorder(context, FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 })), audioFocus, clock)
        viewModel = WalkViewModel(
            context, controller, repository, clock, voiceRecorder, FakeLocationSource(),
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
            releaseFlags = FixedReleaseFlags(honor = true),
            beginHonorWalk = { error("no honor start in this test") },
            honorPreferences = { error("no honor start in this test") },
            honorReplies = replies,
            theirSitting = theirSitting,
        )
    }

    @After
    fun tearDown() {
        runBlocking { withTimeout(10_000L) { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        voiceRecorder.stop()
        db.close()
        storeDirectory.deleteRecursively()
        Dispatchers.resetMain()
    }

    // ---- reply here (D §7) -----------------------------------------------

    @Test
    fun `reply here starts a take, and the walker's stop files it under the voice`() = runTest(dispatcher) {
        store.save(way())
        val walkId = controller.startWalk(intention = null).id

        viewModel.replyHere(walkId = walkId, wayId = OWN_WAY_ID, momentId = "voice-2")
        awaitRecording()
        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Idle }

        assertEquals(mapOf(2 to repository.voiceRecordingsFor(walkId).single().fileRelativePath), store.replies(OWN_WAY_ID))
    }

    @Test
    fun `reply here during an ordinary take re-targets that take (pilgrim-ios #99, matched)`() = runTest(dispatcher) {
        store.save(way())
        val walkId = controller.startWalk(intention = null).id
        viewModel.toggleRecording()
        awaitRecording()

        viewModel.replyHere(walkId = walkId, wayId = OWN_WAY_ID, momentId = "voice-2")
        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Idle }

        val takes = repository.voiceRecordingsFor(walkId)
        assertEquals(1 to mapOf(2 to takes.single().fileRelativePath), takes.size to store.replies(OWN_WAY_ID))
    }

    @Test
    fun `a take a focus loss interrupted files its reply`() = runTest(dispatcher) {
        store.save(way())
        val walkId = controller.startWalk(intention = null).id
        replies.arm(walkId = walkId, wayId = OWN_WAY_ID, momentId = "voice-2")

        viewModel.handleRecordingInterruption(Result.success(recording(walkId, "recordings/w/interrupted.wav")))

        assertEquals(mapOf(2 to "recordings/w/interrupted.wav"), store.replies(OWN_WAY_ID))
    }

    @Test
    fun `on the first honoring of an own walk the walker's reply is lost (pilgrim-ios #98, matched)`() =
        runTest(dispatcher) {
            val walkId = controller.startWalk(intention = null).id

            viewModel.replyHere(walkId = walkId, wayId = OWN_WAY_ID, momentId = "voice-2")
            awaitRecording()
            viewModel.toggleRecording()
            viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Idle }

            assertEquals(1 to emptyMap<Int, String>(), repository.voiceRecordingsFor(walkId).size to store.replies(OWN_WAY_ID))
        }

    @Test
    fun `on a share's first honoring the walker's reply is kept`() = runTest(dispatcher) {
        store.save(way(SHARE_WAY_ID))
        val walkId = controller.startWalk(intention = null).id

        viewModel.replyHere(walkId = walkId, wayId = SHARE_WAY_ID, momentId = "voice-2")
        awaitRecording()
        viewModel.toggleRecording()
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Idle }

        assertEquals(setOf(2), store.replies(SHARE_WAY_ID).keys)
    }

    @Test
    fun `reply here with the microphone refused leaves nothing armed`() = runTest(dispatcher) {
        shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val walkId = controller.startWalk(intention = null).id

        viewModel.replyHere(walkId = walkId, wayId = OWN_WAY_ID, momentId = "voice-2")
        // The origin is armed before the recorder is asked, so once it has
        // refused, the origin is either still armed or already let go.
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Error }

        // The recorder refuses on a real IO thread, so wait on the wall clock.
        val pending = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000L) { replies.pending.first { it == null } }
        }

        assertNull(pending)
    }

    @Test
    fun `a discarded walk files no reply`() = runTest(dispatcher) {
        val walkId = controller.startWalk(intention = null).id
        replies.arm(walkId = walkId, wayId = OWN_WAY_ID, momentId = "voice-2")

        viewModel.discardWalk()

        assertNull(replies.pending.value)
    }

    // ---- Sit? (E §8, §12; owner decision 1) -------------------------------

    @Test
    fun `Sit? while recording stops the recording and starts the sitting`() = runTest(dispatcher) {
        val walkId = controller.startWalk(intention = null).id
        viewModel.toggleRecording()
        awaitRecording()

        clock.advanceTo(3_000L)
        viewModel.startSitting(minutes = 12)
        controller.state.first { it is WalkState.Meditating }

        assertEquals(1, repository.voiceRecordingsFor(walkId).size)
    }

    @Test
    fun `Sit? shows their minutes under the meditation timer`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        backgroundScope.launch { viewModel.theirSittingMinutes.collect {} }

        clock.advanceTo(3_000L)
        viewModel.startSitting(minutes = 12)
        controller.state.first { it is WalkState.Meditating }

        assertEquals(12, viewModel.theirSittingMinutes.first { it != null })
    }

    @Test
    fun `Sit? while paused does nothing`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        controller.pauseWalk()

        viewModel.startSitting(minutes = 12)

        assertTrue(controller.state.value is WalkState.Paused && theirSitting.offer.value == null)
    }

    @Test
    fun `a sitting begun from the sheet shows no caption`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        backgroundScope.launch { viewModel.theirSittingMinutes.collect {} }

        viewModel.startMeditation()
        controller.state.first { it is WalkState.Meditating }

        assertNull(viewModel.theirSittingMinutes.value)
    }

    @Test
    fun `the sitting's end withdraws the caption`() = runTest(dispatcher) {
        controller.startWalk(intention = null)
        clock.advanceTo(3_000L)
        viewModel.startSitting(minutes = 12)
        controller.state.first { it is WalkState.Meditating }

        viewModel.endMeditation()

        assertNull(theirSitting.offer.value)
    }

    private suspend fun awaitRecording() {
        viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
        viewModel.audioLevel.first { it > 0f }
    }

    private fun recording(walkId: Long, path: String) = VoiceRecording(
        walkId = walkId,
        startTimestamp = 1_000L,
        endTimestamp = 2_000L,
        durationMillis = 1_000L,
        fileRelativePath = path,
    )

    private fun way(id: String = OWN_WAY_ID) = Way(
        id = id,
        source = if (id == SHARE_WAY_ID) {
            WaySource.Share(id = "AbCdEf1234", pageUrl = "https://walk.pilgrimapp.org/AbCdEf1234")
        } else {
            WaySource.OwnWalk(SOURCE_UUID)
        },
        title = "the long way",
        departedAt = Instant.ofEpochSecond(1_700_000_000),
        tzIdentifier = "UTC",
        expires = null,
        route = (0..3).map { WayPoint(lat = 0.0, lon = it * 0.001, alt = null, t = it * 60.0) },
        totalDistanceMeters = 333.0,
        theirActiveSeconds = 180.0,
        moments = listOf(
            WayMoment(
                id = "voice-2", frac = 0.5, at = null,
                kind = WayMomentKind.Voice(0.6, 20.0, VoiceKind.SPOKEN, WayMedia.Recording("recordings/v2.wav")),
            ),
        ),
        weather = null,
    )

    private companion object {
        const val SOURCE_UUID = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
        const val OWN_WAY_ID = "walk:$SOURCE_UUID"
        const val SHARE_WAY_ID = "share:AbCdEf1234"
    }
}

private class SteppedClock(initial: Long) : org.walktalkmeditate.pilgrim.domain.Clock {
    @Volatile private var current: Long = initial

    override fun now(): Long = current

    fun advanceTo(millis: Long) {
        current = millis
    }
}
