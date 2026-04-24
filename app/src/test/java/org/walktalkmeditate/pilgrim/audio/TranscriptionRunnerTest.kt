// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * Exercises [TranscriptionRunner] against a real Room database with
 * [FakeWhisperEngine] swapped for the JNI-backed engine. The real engine
 * needs a device; Stage 2-F's instrumented test covers it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TranscriptionRunnerTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var engine: FakeWhisperEngine
    private lateinit var runner: TranscriptionRunner

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
        engine = FakeWhisperEngine()
        runner = TranscriptionRunner(context, repository, engine)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `transcribePending writes only to rows whose transcription is null`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val pending = insertRecording(walk.id, transcription = null)
        val alreadyDone = insertRecording(
            walkId = walk.id,
            transcription = "previously transcribed text",
        )

        engine.resultText = "fresh result"
        val outcome = runner.transcribePending(walk.id)

        assertEquals(Result.success(1), outcome)
        val rows = repository.voiceRecordingsFor(walk.id).associateBy { it.id }
        assertEquals("fresh result", rows.getValue(pending.id).transcription)
        assertEquals("previously transcribed text", rows.getValue(alreadyDone.id).transcription)
    }

    @Test
    fun `empty whisper text becomes NO_SPEECH_PLACEHOLDER`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)
        engine.resultText = ""

        runner.transcribePending(walk.id)

        val updated = repository.getVoiceRecording(recording.id)
        assertNotNull(updated)
        assertEquals(TranscriptionRunner.NO_SPEECH_PLACEHOLDER, updated!!.transcription)
        assertNull("WPM is meaningless for no-speech rows", updated.wordsPerMinute)
    }

    @Test
    fun `per-recording engine failure does not abort the batch`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val first = insertRecording(walk.id)
        val second = insertRecording(walk.id)
        val sequencedEngine = object : WhisperEngine {
            private var calls = 0
            override suspend fun transcribe(wavPath: java.nio.file.Path): Result<TranscriptionResult> {
                calls++
                return if (calls == 1) Result.failure(IOException("boom"))
                else Result.success(TranscriptionResult("second one worked", null))
            }
        }
        val customRunner = TranscriptionRunner(context, repository, sequencedEngine)

        val outcome = customRunner.transcribePending(walk.id)

        assertEquals(Result.success(1), outcome)
        val rows = repository.voiceRecordingsFor(walk.id).associateBy { it.id }
        assertNull(
            "first row should remain unset (transcribe failed)",
            rows.getValue(first.id).transcription,
        )
        assertEquals("second one worked", rows.getValue(second.id).transcription)
    }

    @Test
    fun `wordsPerMinute computed from word count and durationMillis`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id, durationMillis = 30_000L) // 30 seconds
        engine.resultText = "one two three four five"

        runner.transcribePending(walk.id)

        val updated = repository.getVoiceRecording(recording.id)
        assertNotNull(updated)
        // 5 words / 0.5 minutes = 10 wpm
        assertEquals(10.0, updated!!.wordsPerMinute!!, 0.001)
    }

    @Test
    fun `wordsPerMinute null when text is blank or duration is zero`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val zeroDuration = insertRecording(walk.id, durationMillis = 0L)
        engine.resultText = "five words here for testing"

        runner.transcribePending(walk.id)

        val updated = repository.getVoiceRecording(zeroDuration.id)
        assertNotNull(updated)
        assertNull("zero-duration recording should have null WPM", updated!!.wordsPerMinute)
    }

    @Test
    fun `ModelLoadFailed aborts the batch and bubbles for WorkManager retry`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val first = insertRecording(walk.id)
        val second = insertRecording(walk.id)
        engine.failure = WhisperError.ModelLoadFailed()

        val outcome = runner.transcribePending(walk.id)

        assertTrue("expected failure, was $outcome", outcome.isFailure)
        assertTrue(
            "cause should be ModelLoadFailed, was ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is WhisperError.ModelLoadFailed,
        )
        // Neither row should have been written; the worker will retry
        // the entire batch.
        val rows = repository.voiceRecordingsFor(walk.id).associateBy { it.id }
        assertNull(rows.getValue(first.id).transcription)
        assertNull(rows.getValue(second.id).transcription)
    }

    @Test
    fun `transcribePending returns count of successful transcriptions`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        repeat(3) { insertRecording(walk.id) }

        val outcome = runner.transcribePending(walk.id)

        assertTrue(outcome.isSuccess)
        assertEquals(3, outcome.getOrNull())
    }

    private val timestampCounter = AtomicLong(1_000_000L)

    private fun insertRecording(
        walkId: Long,
        transcription: String? = null,
        durationMillis: Long = 5_000L,
    ): VoiceRecording = runBlocking {
        // Strictly-monotonic timestamps so the DAO's
        // ORDER BY start_timestamp ASC produces deterministic batch
        // order regardless of test wall-clock granularity.
        val start = timestampCounter.getAndAdd(60_000L)
        val end = start + durationMillis
        val recording = VoiceRecording(
            walkId = walkId,
            startTimestamp = start,
            endTimestamp = end,
            durationMillis = durationMillis,
            fileRelativePath = "recordings/test-${start}.wav",
            transcription = transcription,
        )
        val id = repository.recordVoice(recording)
        recording.copy(id = id)
    }
}
