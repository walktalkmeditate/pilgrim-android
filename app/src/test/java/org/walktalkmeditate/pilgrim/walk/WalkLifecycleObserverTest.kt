// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Tests the voice auto-stop side-effect that Stage 9.5-C factored out of
 * [WalkFinalizationObserver] into [WalkLifecycleObserver]. The new observer
 * fires on ANY in-progress → terminal transition (Active|Paused|Meditating
 * → Idle|Finished), where the previous owner only fired on Finished. This
 * is what gives the discardWalk path its voice cleanup — without it, a
 * recording-in-progress Active → Idle would leak a WAV and attempt to
 * insert a VoiceRecording row whose parent Walk has just been
 * cascade-deleted (FK violation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkLifecycleObserverTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var fakeAudioCapture: FakeAudioCapture
    private lateinit var stateFlow: MutableStateFlow<WalkState>
    private lateinit var observerScope: CoroutineScope
    private lateinit var observer: WalkLifecycleObserver
    private val testClock = object : Clock {
        @Volatile var current: Long = 0L
        override fun now(): Long = current
    }

    @Before
    fun setUp() {
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
            walkPhotoDao = db.walkPhotoDao(),
        )
        fakeAudioCapture = FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 }))
        val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        voiceRecorder = VoiceRecorder(context, fakeAudioCapture, audioFocus, testClock)

        stateFlow = MutableStateFlow(WalkState.Idle)
        observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sweeper = OrphanRecordingSweeper(
            context = context,
            repository = repository,
            transcriptionScheduler = FakeTranscriptionScheduler(),
        )
        observer = WalkLifecycleObserver(
            walkState = stateFlow,
            scope = observerScope,
            voiceRecorder = voiceRecorder,
            repository = repository,
            orphanSweeper = sweeper,
        )
        // Same async-collector-attach hazard as WalkFinalizationObserverTest:
        // the observer's `init { scope.launch { walkState.collect } }`
        // attaches on Dispatchers.IO. Sleep so the collector certainly
        // attaches and consumes the initial Idle value before the test
        // mutates state.
        Thread.sleep(COLLECTOR_ATTACH_WAIT_MS)
    }

    @After
    fun tearDown() {
        observerScope.coroutineContext[Job]?.cancel()
        db.close()
    }

    @Test
    fun `Active to Finished transition stops voice recorder and inserts row`() = runBlocking {
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
        startLiveRecordingFor(walkId)

        testClock.current = 90_000L
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 800.0),
            endedAt = 100_000L,
        )

        // Wait for the observer's launched stop()+INSERT to complete.
        val deadline = System.currentTimeMillis() + WAIT_FOR_OBSERVER_MS
        while (
            System.currentTimeMillis() < deadline &&
            repository.voiceRecordingsFor(walkId).isEmpty()
        ) {
            Thread.sleep(20L)
        }
        assertEquals(
            "Finished must auto-stop AND commit the recording row",
            1,
            repository.voiceRecordingsFor(walkId).size,
        )
    }

    @Test
    fun `Active to Idle (discard) stops voice recorder but does NOT insert row`() = runBlocking {
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
        startLiveRecordingFor(walkId)

        testClock.current = 90_000L
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        // Discard path: the controller deletes the walk row first via the
        // PurgeWalk effect, then sets state to Idle. The observer must
        // detect the Active→Idle transition, stop the recorder, and DROP
        // the resulting VoiceRecording (parent walk is gone).
        repository.deleteWalkById(walkId)
        stateFlow.value = WalkState.Idle

        Thread.sleep(WAIT_FOR_OBSERVER_MS)
        // Recorder must have actually stopped (audioLevel resets to 0 in stop()).
        assertEquals(0f, voiceRecorder.audioLevel.value, 0.0001f)
        // No VoiceRecording row inserted (parent walk doesn't exist anymore).
        // Use the all-recordings observer to be sure; voiceRecordingsFor(walkId)
        // would also return empty if the FK had violated and the row never made it.
        val orphanedRows = repository.voiceRecordingsFor(walkId)
        assertTrue(
            "discard path must not insert a VoiceRecording row, found: $orphanedRows",
            orphanedRows.isEmpty(),
        )
    }

    @Test
    fun `Paused to Idle (discard from Paused) stops voice recorder and does NOT insert row`() = runBlocking {
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
        startLiveRecordingFor(walkId)

        testClock.current = 90_000L
        // Walk progressed Active → Paused before the user discarded. The
        // observer's "any in-progress → Idle" branch must fire on the
        // Paused → Idle leg too — otherwise discarding from a paused walk
        // would leak the active recorder.
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Paused(
            WalkAccumulator(walkId = walkId, startedAt = 0L),
            pausedAt = 30_000L,
        )
        repository.deleteWalkById(walkId)
        stateFlow.value = WalkState.Idle

        Thread.sleep(WAIT_FOR_OBSERVER_MS)
        assertEquals(0f, voiceRecorder.audioLevel.value, 0.0001f)
        val orphanedRows = repository.voiceRecordingsFor(walkId)
        assertTrue(
            "Paused→Idle discard path must not insert a VoiceRecording row, found: $orphanedRows",
            orphanedRows.isEmpty(),
        )
    }

    @Test
    fun `cold-start initial Idle does not stop the recorder`() = runBlocking {
        // Mirror the cold-start scenario: process boot, controller's state
        // is Idle, no recording was ever started. The observer's
        // firstEmission latch must skip this without invoking stop().
        Thread.sleep(WAIT_FOR_OBSERVER_MS)
        // No transition fired; nothing to stop. audioLevel stays 0 (the
        // recorder was never started). The real assertion: stop() was NOT
        // called as a side-effect — proven indirectly by no exception
        // being thrown from stop() against a never-started recorder
        // (would log warn but not crash) AND no log entry in the
        // observer indicating a transition was processed.
        // Stronger check: start a recording AFTER the observer attached;
        // it must still be active (the observer must NOT have stopped it).
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
        startLiveRecordingFor(walkId)
        Thread.sleep(50L)
        // Recorder is still capturing (audioLevel > 0 after burst arrives).
        assertTrue(
            "Cold-start observer must not interfere with subsequent recordings",
            voiceRecorder.audioLevel.value > 0f,
        )
    }

    private fun startLiveRecordingFor(walkId: Long) {
        testClock.current = 0L
        voiceRecorder.start(walkId = walkId, walkUuid = java.util.UUID.randomUUID().toString())
            .getOrThrow()
        // Wait for the capture loop to drain the burst (proves capture
        // executor actually started).
        val captureDeadline = System.currentTimeMillis() + 2_000L
        while (voiceRecorder.audioLevel.value == 0f &&
            System.currentTimeMillis() < captureDeadline
        ) {
            Thread.sleep(20L)
        }
        check(voiceRecorder.audioLevel.value > 0f) {
            "FakeAudioCapture burst did not arrive within 2 s — test infra broken"
        }
    }

    private companion object {
        const val COLLECTOR_ATTACH_WAIT_MS = 300L
        const val WAIT_FOR_OBSERVER_MS = 1_500L
    }
}
