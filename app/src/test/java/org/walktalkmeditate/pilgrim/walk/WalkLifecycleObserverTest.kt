// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher
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
    private lateinit var observedFlow: CountingStateFlow<WalkState>
    private lateinit var observerScope: CoroutineScope
    private lateinit var observer: WalkLifecycleObserver
    private val seekSessionStore = org.walktalkmeditate.pilgrim.walk.seek.SeekSessionStore()
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
        observedFlow = CountingStateFlow(stateFlow)
        // TestRealTimeDispatcher (not Dispatchers.IO) — a cached pool of
        // dedicated daemon threads that Default/IO-pool saturation can't
        // starve. The observer's launched stop()+reset runs promptly even
        // when Gradle saturates the runner with sibling test classes, so
        // the wall-clock waits below resolve well within their failsafe
        // bound. On Dispatchers.IO the wait raced pool starvation (#161
        // bumped the bound 5s→15s and it still flaked — a wider timeout
        // can't fix a starvation race; a never-starved dispatcher does).
        // Canonical fix for the ci-realtime-withtimeout flake family — see
        // [TestRealTimeDispatcher].
        observerScope = CoroutineScope(SupervisorJob() + TestRealTimeDispatcher.instance)
        val sweeper = OrphanRecordingSweeper(
            context = context,
            repository = repository,
            transcriptionScheduler = FakeTranscriptionScheduler(),
        )
        observer = WalkLifecycleObserver(
            walkState = observedFlow,
            scope = observerScope,
            voiceRecorder = voiceRecorder,
            repository = repository,
            orphanSweeper = sweeper,
            seekSessionStore = seekSessionStore,
        )
        // The observer's `init { scope.launch { walkState.collect } }`
        // subscribes asynchronously on its scope dispatcher and swallows
        // its FIRST collected value unconditionally (the firstEmission latch
        // — at app start that's the cold-process Idle no-op). If a test
        // mutates stateFlow.value before the collector has consumed that
        // first value, StateFlow conflation collapses the real
        // transition into emission #1 and the latch eats it — side
        // effects never fire. The old blind Thread.sleep flaked on
        // saturated CI runners. CountingStateFlow.processed increments
        // only after the collector returns from handling a value, so
        // awaiting >= 1 is an exact handshake: the initial Idle has been
        // consumed and the latch is spent before the test mutates state.
        runBlocking {
            withTimeout(COLLECTOR_SUBSCRIBE_TIMEOUT_MS) {
                observedFlow.processed.first { it >= 1 }
            }
        }
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

        // Wait deterministically for the observer's stop() side-effect
        // to land: audioLevel resets to 0f inside VoiceRecorder.stop().
        // Earlier this loop polled with Thread.sleep, which busy-burns
        // CPU and races against the deadline on saturated CI runners
        // (the flake this replaces). `StateFlow.first { it == 0f }`
        // subscribes and suspends, returning the instant the recorder
        // actually stops — yielding to the observer's IO-dispatched
        // launch instead of competing with it for the JVM scheduler.
        withTimeout(WAIT_FOR_OBSERVER_MS) {
            voiceRecorder.audioLevel.first { it == 0f }
        }
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

        // Deterministic suspending wait (see the Active→Idle test for
        // the rationale — replaces a flaky wall-clock polling loop).
        withTimeout(WAIT_FOR_OBSERVER_MS) {
            voiceRecorder.audioLevel.first { it == 0f }
        }
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
        //
        // setUp already awaited the firstEmission handshake (processed >= 1),
        // so the initial Idle has been consumed-and-skipped before we get
        // here — the latch is spent, and no terminal transition fires in
        // this test, so no stop() can be pending. (Previously this slept the
        // full WAIT_FOR_OBSERVER_MS = 15s "to be sure"; the handshake already
        // guarantees it, so that was pure dead time.)
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
        // startLiveRecordingFor already blocks until audioLevel > 0 (it
        // checks the burst arrived), so the recorder is provably capturing
        // here — no sleep needed.
        assertTrue(
            "Cold-start observer must not interfere with subsequent recordings",
            voiceRecorder.audioLevel.value > 0f,
        )
    }

    @Test
    fun `terminal transitions retire a pending seek session`() = runBlocking {
        seekSessionStore.set(
            org.walktalkmeditate.pilgrim.walk.seek.SeekPendingSession(
                chain = org.walktalkmeditate.pilgrim.domain.seek.SeekChain(
                    clearings = listOf(
                        org.walktalkmeditate.pilgrim.domain.seek.SeekClearing(
                            center = org.walktalkmeditate.pilgrim.domain.seek.SeekPoint(35.0, 135.0),
                            radiusMeters = 50.0,
                        ),
                    ),
                    budgetMeters = 1_000.0,
                ),
                durationMinutes = 30,
                tint = null,
                seededAtEpochMillis = 1L,
            ),
        )

        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = 1L, startedAt = 0L),
            endedAt = 100L,
        )

        val deadline = System.currentTimeMillis() + WAIT_FOR_OBSERVER_MS
        while (
            System.currentTimeMillis() < deadline &&
            seekSessionStore.pending.value != null
        ) {
            Thread.sleep(20L)
        }
        assertEquals(
            "a finished walk must retire the pending seek session",
            null,
            seekSessionStore.pending.value,
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
        // Failsafe upper bound for the deterministic firstEmission
        // handshake (observedFlow.processed >= 1); it returns the
        // instant the collector consumes the initial Idle, so this only
        // bites on a wedged runner (a real bug — should fail).
        const val COLLECTOR_SUBSCRIBE_TIMEOUT_MS = 15_000L
        // Failsafe upper bound for the observer's side-effect waits
        // (`audioLevel.first { it == 0f }` and the Finished-path INSERT
        // poll). With the observer on [TestRealTimeDispatcher] its handler
        // runs on a never-starved pool, so these resolve in milliseconds;
        // the bound only bites if a side effect never lands (a real bug).
        // Do NOT bump it — #161 tried 5s→15s and it flaked again; a wider
        // timeout can't fix a starvation race, the dispatcher swap does.
        const val WAIT_FOR_OBSERVER_MS = 15_000L
    }
}
