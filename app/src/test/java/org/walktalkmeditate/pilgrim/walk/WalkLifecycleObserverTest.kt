// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import org.walktalkmeditate.pilgrim.audio.VoiceRecorderError
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.honor.HonorReplies
import org.walktalkmeditate.pilgrim.honor.TheirSitting
import java.time.Instant

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
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocus: AudioFocusCoordinator
    private lateinit var sweeper: OrphanRecordingSweeper
    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var fakeAudioCapture: FakeAudioCapture
    private lateinit var stateFlow: MutableStateFlow<WalkState>
    private lateinit var observedFlow: CountingStateFlow<WalkState>
    private lateinit var observerScope: CoroutineScope
    private val seekSessionStore = org.walktalkmeditate.pilgrim.walk.seek.SeekSessionStore()
    private val waysDirectory by lazy { java.io.File(context.filesDir, "lifecycle-observer-ways") }
    private val store by lazy { WayStore({ waysDirectory }) }
    private val replies by lazy { HonorReplies(store) }
    private val theirSitting = TheirSitting()
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
        audioManager = context.getSystemService(AudioManager::class.java)
        audioFocus = AudioFocusCoordinator(audioManager)
        voiceRecorder = VoiceRecorder(context, fakeAudioCapture, audioFocus, testClock)

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
        sweeper = OrphanRecordingSweeper(
            context = context,
            repository = repository,
            transcriptionScheduler = FakeTranscriptionScheduler(),
        )
        stateFlow = MutableStateFlow(WalkState.Idle)
        observedFlow = CountingStateFlow(stateFlow)
        attachObserver(observedFlow, voiceRecorder)
    }

    private fun attachObserver(walkState: CountingStateFlow<WalkState>, recorder: VoiceRecorder) {
        WalkLifecycleObserver(
            walkState = walkState,
            scope = observerScope,
            voiceRecorder = recorder,
            repository = repository,
            orphanSweeper = sweeper,
            seekSessionStore = seekSessionStore,
            honorReplies = replies,
            theirSitting = theirSitting,
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
                walkState.processed.first { it >= 1 }
            }
        }
    }

    @After
    fun tearDown() {
        observerScope.coroutineContext[Job]?.cancel()
        db.close()
        waysDirectory.deleteRecursively()
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

    // ─── Discard mid-recording (U8 audit B, iOS PR #84 counterpart) ─────
    // Each state change goes through [transitionTo], which waits for the
    // observer to consume it: back-to-back Active → Idle writes would
    // otherwise conflate into "still Idle" and no transition would fire.
    // Each wait is on the partial file disappearing, not on audioLevel == 0:
    // the capture loop's `finally` zeroes the level before the recorder
    // abandons focus and before the observer deletes the WAV.

    @Test
    fun `discard mid-recording deletes the partial file and abandons focus`() = runBlocking {
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
        val path = startLiveRecordingFor(walkId)

        transitionTo(WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L)))
        repository.deleteWalkById(walkId)
        transitionTo(WalkState.Idle)

        awaitDeleted(path)
        assertSessionReleased(voiceRecorder, fakeAudioCapture)
        assertTrue(repository.voiceRecordingsFor(walkId).isEmpty())
    }

    @Test
    fun `Idle never commits a row even while the parent walk still exists`() = runBlocking {
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
        val path = startLiveRecordingFor(walkId)

        transitionTo(WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L)))
        transitionTo(WalkState.Idle)

        awaitDeleted(path)
        assertNotNull(
            "the parent walk row still exists, so an attempted insert would have landed",
            repository.getWalk(walkId),
        )
        assertTrue(
            "Idle must drop the take rather than commit it",
            repository.voiceRecordingsFor(walkId).isEmpty(),
        )
    }

    @Test
    fun `Meditating to Idle (discard from Meditating) cleans up like Active`() = runBlocking {
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
        val path = startLiveRecordingFor(walkId)

        transitionTo(WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L)))
        transitionTo(
            WalkState.Meditating(
                WalkAccumulator(walkId = walkId, startedAt = 0L),
                meditationStartedAt = 30_000L,
            ),
        )
        repository.deleteWalkById(walkId)
        transitionTo(WalkState.Idle)

        awaitDeleted(path)
        assertSessionReleased(voiceRecorder, fakeAudioCapture)
        assertTrue(repository.voiceRecordingsFor(walkId).isEmpty())
    }

    @Test
    fun `discard before any audio was captured leaves no file and releases focus`() = runBlocking {
        val silentCapture = FakeAudioCapture(bursts = emptyList())
        val silentRecorder = VoiceRecorder(context, silentCapture, audioFocus, testClock)
        val silentSource = MutableStateFlow<WalkState>(WalkState.Idle)
        val silentObserved = CountingStateFlow(silentSource)
        attachObserver(silentObserved, silentRecorder)
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
        val path = silentRecorder.start(walkId = walkId, walkUuid = UUID.randomUUID().toString())
            .getOrThrow()
        assertTrue("the header-only WAV exists while the take is open", Files.exists(path))

        transitionTo(
            WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L)),
            silentSource,
            silentObserved,
        )
        repository.deleteWalkById(walkId)
        transitionTo(WalkState.Idle, silentSource, silentObserved)

        awaitDeleted(path)
        assertSessionReleased(silentRecorder, silentCapture)
    }

    @Test
    fun `discard with no recording open leaves another holder's focus alone`() = runBlocking {
        assertTrue(audioFocus.requestMediaPlayback())
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id

        transitionTo(WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L)))
        repository.deleteWalkById(walkId)
        transitionTo(WalkState.Idle)
        awaitObserverHandlersDone()

        assertNull(
            "a discard with nothing recording must not abandon focus it never took",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
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
        // full WAIT_FOR_OBSERVER_MS "to be sure"; the handshake already
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

    // ─── A reply still recording at walk end (parity spec D §7.2–§7.3) ──────

    @Test
    fun `a share's reply still recording at walk end is filed`() = runBlocking {
        store.save(honorWay(SHARE_WAY_ID))
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
        startLiveRecordingFor(walkId)
        replies.arm(walkId = walkId, wayId = SHARE_WAY_ID, momentId = "voice-2")

        transitionTo(WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L)))
        transitionTo(WalkState.Finished(WalkAccumulator(walkId = walkId, startedAt = 0L), endedAt = 100_000L))
        awaitReplyFiled()

        assertEquals(
            mapOf(2 to repository.voiceRecordingsFor(walkId).single().fileRelativePath),
            store.replies(SHARE_WAY_ID),
        )
    }

    @Test
    fun `a first honoring's reply still recording at walk end is lost, though its Way is listed by then`() =
        runBlocking {
            val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
            startLiveRecordingFor(walkId)
            replies.arm(walkId = walkId, wayId = OWN_WAY_ID, momentId = "voice-2")
            // The tracker's finalize step lists the Way before the UI's take lands.
            store.save(honorWay(OWN_WAY_ID))

            transitionTo(WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L)))
            transitionTo(WalkState.Finished(WalkAccumulator(walkId = walkId, startedAt = 0L), endedAt = 100_000L))
            awaitReplyTaken()

            assertEquals(1 to emptyMap<Int, String>(), repository.voiceRecordingsFor(walkId).size to store.replies(OWN_WAY_ID))
        }

    // ─── A card's "Sit?" offer (parity spec E §12) ───────────────────────────

    @Test
    fun `a sitting ended from the notification withdraws the card's offer, as every end does on iOS`() = runBlocking {
        val walk = WalkAccumulator(walkId = 7L, startedAt = 0L)
        transitionTo(WalkState.Active(walk))
        theirSitting.offer(walkId = 7L, minutes = 5, nowMillis = 1_000L)

        transitionTo(WalkState.Meditating(walk, meditationStartedAt = 2_000L))
        val duringTheSitting = theirSitting.offer.value
        transitionTo(WalkState.Active(walk))

        assertEquals(5 to null, duringTheSitting?.minutes to theirSitting.offer.value)
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

    /** The walk-end take is saved, then its reply origin taken, in that order. */
    /** The origin clears before the mapping is written, so a filed reply is awaited on the write itself. */
    private fun awaitReplyFiled() {
        val deadline = System.currentTimeMillis() + WAIT_FOR_OBSERVER_MS
        while (replies.filed.value == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        assertEquals("the walk-end take never filed its reply", 1L, replies.filed.value)
    }

    private fun awaitReplyTaken() {
        val deadline = System.currentTimeMillis() + WAIT_FOR_OBSERVER_MS
        while (replies.pending.value != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        assertNull("the walk-end take never took its reply origin", replies.pending.value)
    }

    private fun honorWay(id: String) = Way(
        id = id,
        source = if (id == SHARE_WAY_ID) {
            WaySource.Share(id = "AbCdEf1234", pageUrl = "https://walk.pilgrimapp.org/AbCdEf1234")
        } else {
            WaySource.OwnWalk("0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50")
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

    private fun awaitDeleted(path: Path) {
        val deadline = System.currentTimeMillis() + WAIT_FOR_OBSERVER_MS
        while (Files.exists(path) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        assertFalse("the partial recording was never deleted: $path", Files.exists(path))
    }

    /**
     * Publishes [state] and waits until the observer has consumed it. Every
     * earlier write went through here too, so the collector is caught up and
     * the next processed count identifies this value exactly.
     */
    private suspend fun transitionTo(
        state: WalkState,
        source: MutableStateFlow<WalkState> = stateFlow,
        observed: CountingStateFlow<WalkState> = observedFlow,
    ) {
        val consumedBefore = observed.processed.value
        source.value = state
        withTimeout(WAIT_FOR_OBSERVER_MS) {
            observed.processed.first { it > consumedBefore }
        }
    }

    /**
     * The observer forks each terminal transition's voice handling into a
     * child of [observerScope]; a child leaves the scope's children once it
     * completes, so only the long-lived collector remains.
     */
    private fun awaitObserverHandlersDone() {
        val scopeJob = checkNotNull(observerScope.coroutineContext[Job])
        val deadline = System.currentTimeMillis() + WAIT_FOR_OBSERVER_MS
        while (scopeJob.children.count() > 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        assertEquals("the observer's voice handling never finished", 1, scopeJob.children.count())
    }

    private fun assertSessionReleased(recorder: VoiceRecorder, capture: FakeAudioCapture) {
        val requested = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull("the recorder took focus when the take opened", requested)
        assertSame(
            "the recorder abandons the exact focus request it took",
            requested.audioFocusRequest,
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
        assertFalse(recorder.isRecording.value)
        assertNull(recorder.recordingStartedAtMillis)
        assertTrue(
            "the session is released, so a second stop finds nothing open",
            recorder.stop().exceptionOrNull() is VoiceRecorderError.NoActiveRecording,
        )
        assertEquals("the microphone is stopped exactly once", 1, capture.stopCallCount.get())
    }

    private fun startLiveRecordingFor(walkId: Long): Path {
        testClock.current = 0L
        val path = voiceRecorder.start(walkId = walkId, walkUuid = UUID.randomUUID().toString())
            .getOrThrow()
        // Wait for the capture loop to drain the burst (proves capture
        // executor actually started).
        val captureDeadline = System.currentTimeMillis() + CAPTURE_START_TIMEOUT_MS
        while (voiceRecorder.audioLevel.value == 0f &&
            System.currentTimeMillis() < captureDeadline
        ) {
            Thread.sleep(20L)
        }
        check(voiceRecorder.audioLevel.value > 0f) {
            "FakeAudioCapture burst did not arrive within " +
                "${CAPTURE_START_TIMEOUT_MS}ms — test infra broken"
        }
        return path
    }

    private companion object {
        const val OWN_WAY_ID = "walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
        const val SHARE_WAY_ID = "share:AbCdEf1234"

        // Failsafe upper bound for the deterministic firstEmission
        // handshake (observedFlow.processed >= 1); it returns the
        // instant the collector consumes the initial Idle, so this only
        // bites on a wedged runner (a real bug — should fail).
        const val COLLECTOR_SUBSCRIBE_TIMEOUT_MS = 30_000L
        // Failsafe upper bound for the observer's side-effect waits
        // (`audioLevel.first { it == 0f }` and the Finished-path INSERT
        // poll). With the observer on [TestRealTimeDispatcher] its handler
        // runs on a never-starved pool, so these resolve in milliseconds;
        // the bound only bites if a side effect never lands (a real bug).
        //
        // History: #161 bumped 5s→15s and it still flaked, which is why
        // the dispatcher swap above is the actual fix — a wider bound
        // cannot cure starvation. These are failsafes, not tuned grace
        // windows: the wait returns the instant the value lands, so a
        // generous ceiling is free on green and only delays a genuine
        // failure. Do not tidy them back down.
        const val WAIT_FOR_OBSERVER_MS = 30_000L
        // Same failsafe reasoning for the capture-executor start probe.
        // The recorder's capture thread competes with a sibling Gradle
        // fork for a small CI runner's cores.
        const val CAPTURE_START_TIMEOUT_MS = 30_000L
    }
}
