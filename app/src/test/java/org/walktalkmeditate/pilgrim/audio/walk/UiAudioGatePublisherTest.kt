// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import android.Manifest
import android.app.Application
import android.media.AudioManager
import android.os.IBinder
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind.PROMPT
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind.RECORDING
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind.WHISPER
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.CountingStateFlow
import org.walktalkmeditate.pilgrim.walk.WalkLifecycleObserver
import org.walktalkmeditate.pilgrim.walk.seek.SeekSessionStore

/**
 * The UI's gate publisher (plan U18): one observer each for the guide's
 * prompt level, the recorder's flag, and the whisper player's (plan U25),
 * fresh Binders and rising numbers, and the re-send whenever the walk's
 * gate generation changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UiAudioGatePublisherTest {

    private data class Sent(val kind: UiAudioGateKind, val held: Boolean, val seq: Long, val token: IBinder?)

    private val sends = MutableStateFlow<List<Sent>>(emptyList())

    private fun record(kind: UiAudioGateKind, held: Boolean, seq: Long, token: IBinder?) {
        sends.update { it + Sent(kind, held, seq, token) }
    }

    private fun TestScope.publisher(
        honorEnabled: Boolean = true,
        recording: StateFlow<Boolean> = MutableStateFlow(false),
        whisper: StateFlow<Boolean> = MutableStateFlow(false),
        refreshes: Flow<UiAudioGateRefresh?> = flowOf(null),
        bootNanos: () -> Long = { 0L },
    ) = UiAudioGatePublisher(
        releaseFlags = FixedReleaseFlags(honor = honorEnabled),
        recording = recording,
        refreshes = refreshes,
        send = ::record,
        bootNanos = bootNanos,
        scope = backgroundScope,
        whisper = whisper,
    )

    @Test
    fun `each change goes out once, a start with a fresh Binder and a rising number`() =
        runTest(UnconfinedTestDispatcher()) {
            val recording = MutableStateFlow(false)
            val publisher = publisher(recording = recording).also { it.start() }

            publisher.onPromptLevel(true)
            publisher.onPromptLevel(true)
            recording.value = true
            publisher.onPromptLevel(false)
            recording.value = false

            val sent = sends.value
            assertEquals(
                listOf(PROMPT to true, RECORDING to true, PROMPT to false, RECORDING to false),
                sent.map { it.kind to it.held },
            )
            assertNotNull(sent[0].token)
            assertNotNull(sent[1].token)
            assertNotSame(sent[0].token, sent[1].token)
            assertNull("an end carries no Binder", sent[2].token)
            assertEquals(sent.map { it.seq }.sorted().distinct(), sent.map { it.seq })
        }

    @Test
    fun `numbers rise past a UI restart, the boot clock their floor`() = runTest(UnconfinedTestDispatcher()) {
        val first = publisher(bootNanos = { 1_000L })
        first.onPromptLevel(true)
        first.onPromptLevel(false)

        val restarted = publisher(bootNanos = { 5_000L })
        restarted.onPromptLevel(true)

        assertEquals(listOf(1_000L, 1_001L, 5_000L), sends.value.map { it.seq })
    }

    @Test
    fun `a whisper the UI plays goes out as the whisper gate, with its own Binder`() =
        runTest(UnconfinedTestDispatcher()) {
            val whisper = MutableStateFlow(false)
            publisher(whisper = whisper).also { it.start() }

            whisper.value = true
            whisper.value = false

            assertEquals(listOf(WHISPER to true, WHISPER to false), sends.value.map { it.kind to it.held })
            assertNotNull(sends.value[0].token)
            assertNull(sends.value[1].token)
        }

    @Test
    fun `every gate goes out again, fresh Binders and the prompt first, when the walk or its gate generation changes`() =
        runTest(UnconfinedTestDispatcher()) {
            val refreshes = MutableStateFlow<UiAudioGateRefresh?>(null)
            val publisher = publisher(refreshes = refreshes).also { it.start() }
            publisher.onPromptLevel(true)
            val firstToken = sends.value.single().token

            refreshes.value = UiAudioGateRefresh(walkId = 7, gateGeneration = 1)
            refreshes.value = UiAudioGateRefresh(walkId = 7, gateGeneration = 1)
            refreshes.value = UiAudioGateRefresh(walkId = 7, gateGeneration = 2)
            refreshes.value = null
            refreshes.value = UiAudioGateRefresh(walkId = 8, gateGeneration = null)

            val resent = sends.value.drop(1)
            assertEquals(
                listOf(PROMPT to true, RECORDING to false, WHISPER to false).let { it + it + it },
                resent.map { it.kind to it.held },
            )
            val promptTokens = resent.filter { it.kind == PROMPT }.map { it.token }
            assertEquals("every re-sent start has its own Binder", 4, (promptTokens + firstToken).toSet().size)
        }

    @Test
    fun `with the release flag off nothing is ever sent`() = runTest(UnconfinedTestDispatcher()) {
        val recording = MutableStateFlow(false)
        val refreshes = MutableStateFlow<UiAudioGateRefresh?>(null)
        val publisher = publisher(honorEnabled = false, recording = recording, refreshes = refreshes)
        publisher.start()

        publisher.onPromptLevel(true)
        recording.value = true
        refreshes.value = UiAudioGateRefresh(walkId = 1, gateGeneration = 1)

        assertEquals(emptyList<Sent>(), sends.value)
    }

    @Test
    fun `the recording observer clears the gate when the walk-end observer auto-stops a take`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java).allowMainThreadQueries().build()
        val repository = WalkRepository(
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
        val clock = object : Clock {
            override fun now(): Long = 0L
        }
        val recorder = VoiceRecorder(
            context,
            FakeAudioCapture(),
            AudioFocusCoordinator(context.getSystemService(AudioManager::class.java)),
            clock,
        )
        val scope = CoroutineScope(SupervisorJob() + TestRealTimeDispatcher.instance)
        val walkState = MutableStateFlow<WalkState>(WalkState.Idle)
        val observed = CountingStateFlow(walkState)
        try {
            WalkLifecycleObserver(
                walkState = observed,
                scope = scope,
                voiceRecorder = recorder,
                repository = repository,
                orphanSweeper = OrphanRecordingSweeper(
                    context = context,
                    repository = repository,
                    transcriptionScheduler = FakeTranscriptionScheduler(),
                ),
                seekSessionStore = SeekSessionStore(),
            )
            // The observer drops its first value; past it, the walk's end is a transition it acts on.
            withTimeout(WAIT_MS) { observed.processed.first { it >= 1 } }
            UiAudioGatePublisher(
                releaseFlags = FixedReleaseFlags(honor = true),
                recording = recorder.isRecording,
                refreshes = flowOf(null),
                send = ::record,
                bootNanos = { 0L },
                scope = scope,
            ).start()
            val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
            val walk = WalkAccumulator(walkId = walkId, startedAt = 0L)
            walkState.value = WalkState.Active(walk)
            recorder.start(walkId = walkId, walkUuid = UUID.randomUUID().toString()).getOrThrow()
            withTimeout(WAIT_MS) { sends.first { sent -> sent.any { it.kind == RECORDING && it.held } } }

            walkState.value = WalkState.Finished(walk, endedAt = 60_000L)

            val last = withTimeout(WAIT_MS) {
                sends.first { sent -> sent.lastOrNull()?.let { it.kind == RECORDING && !it.held } == true }
            }.last()
            assertTrue("the walk's end stopped the take", !recorder.isRecording.value)
            assertNull(last.token)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
        }
    }

    private companion object {
        /** A failsafe, not a grace window: each wait returns the moment its send lands. */
        const val WAIT_MS = 30_000L
    }
}
