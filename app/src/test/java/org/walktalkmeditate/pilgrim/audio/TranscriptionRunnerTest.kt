// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
import org.walktalkmeditate.pilgrim.audio.model.FakeWhisperModelDownloadScheduler
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWork
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWorkSource
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelConfig
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * Exercises [TranscriptionRunner] against a real Room database with
 * [FakeWhisperEngine] swapped for the JNI-backed engine. The real engine
 * needs a device; Stage 2-F's instrumented test covers it. The model
 * store is real over the Robolectric filesDir: setUp installs a sparse
 * legacy tiny so the U10 model-absent pre-check passes for the batch
 * tests, and the pre-check tests delete it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TranscriptionRunnerTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var engine: FakeWhisperEngine
    private lateinit var storeScope: CoroutineScope
    private lateinit var store: WhisperModelStore
    private lateinit var downloadScheduler: FakeWhisperModelDownloadScheduler
    private lateinit var runner: TranscriptionRunner

    private val modelRoot: File
        get() = File(context.filesDir, "whisper-model")

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
        modelRoot.deleteRecursively()
        installLegacyTiny()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = WhisperModelStore(
            context = context,
            workSource = object : ModelDownloadWorkSource {
                override fun observe(): Flow<ModelDownloadWork?> = flowOf(null)
            },
            unmeteredProbe = { true },
            scope = storeScope,
        )
        downloadScheduler = FakeWhisperModelDownloadScheduler()
        runner = buildRunner(engine)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        modelRoot.deleteRecursively()
        db.close()
    }

    private fun buildRunner(engine: WhisperEngine) =
        TranscriptionRunner(context, repository, engine, store, downloadScheduler)

    private fun installLegacyTiny() {
        val tiny = File(modelRoot, "ggml-tiny.en.bin")
        tiny.parentFile?.mkdirs()
        RandomAccessFile(tiny, "rw").use {
            it.setLength(WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES)
        }
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

    // AF33: the ~75 MB model must be released after the batch so it doesn't
    // stay resident while the user keeps using the app.
    @Test
    fun `transcribePending unloads the model after the batch`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        insertRecording(walk.id)
        insertRecording(walk.id)

        runner.transcribePending(walk.id)

        assertEquals(
            "model must be unloaded exactly once after the batch",
            1,
            engine.unloadModelCalls,
        )
    }

    @Test
    fun `transcribePending unloads the model even when model load fails mid-batch`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        insertRecording(walk.id)
        insertRecording(walk.id)
        engine.failure = WhisperError.ModelLoadFailed()

        val outcome = runner.transcribePending(walk.id)

        assertTrue("ModelLoadFailed aborts the batch as a failure", outcome.isFailure)
        // ModelLoadFailed must abort the batch early via the non-local return
        // through the inline Result.fold — the second recording is never
        // attempted (every remaining one would fail the same way).
        assertEquals(
            "batch aborts after the first ModelLoadFailed",
            1,
            engine.transcribeCalls.size,
        )
        assertEquals(
            "the finally must still unload (no-op when nothing loaded)",
            1,
            engine.unloadModelCalls,
        )
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
            override fun unloadModel() {}
        }
        val customRunner = buildRunner(sequencedEngine)

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

    // U10 self-heal: no usable model + work to do → the download is
    // (re-)enqueued (KEEP) and the batch returns the retry signal
    // instead of spinning on an engine that can never load.
    @Test
    fun `model absent with pending recordings enqueues the download and returns retry failure`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)
        modelRoot.deleteRecursively()

        val outcome = runner.transcribePending(walk.id)

        assertTrue(
            "expected ModelLoadFailed, was $outcome",
            outcome.exceptionOrNull() is WhisperError.ModelLoadFailed,
        )
        assertEquals("download must be (re-)enqueued once", 1, downloadScheduler.ensureEnqueuedCalls)
        assertTrue("engine must never be reached without a model", engine.transcribeCalls.isEmpty())
        assertNull(
            "nothing may be written on the pre-check path",
            repository.getVoiceRecording(recording.id)!!.transcription,
        )
    }

    @Test
    fun `model absent with nothing pending is success zero without an enqueue`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        modelRoot.deleteRecursively()

        val outcome = runner.transcribePending(walk.id)

        assertEquals(Result.success(0), outcome)
        assertEquals(
            "an empty batch must not schedule a 148 MB download",
            0,
            downloadScheduler.ensureEnqueuedCalls,
        )
    }

    // The pre-check must not blur the taxonomy: a PRESENT model whose
    // load genuinely fails keeps the plain ModelLoadFailed escalation —
    // re-enqueueing a download the filesystem already satisfied would
    // mask the real failure.
    @Test
    fun `load failure of a present model escalates without re-enqueueing the download`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        insertRecording(walk.id)
        engine.failure = WhisperError.ModelLoadFailed()

        val outcome = runner.transcribePending(walk.id)

        assertTrue(
            "expected ModelLoadFailed, was $outcome",
            outcome.exceptionOrNull() is WhisperError.ModelLoadFailed,
        )
        assertEquals(0, downloadScheduler.ensureEnqueuedCalls)
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

    // AF32 (iOS PR #45): when EVERY pending recording fails, the batch
    // must not report success(0) — that reads as "completed" and the
    // worker would mark the work succeeded. Report a failure carrying the
    // attempted count so the worker surfaces it honestly instead.
    @Test
    fun `all pending failing returns failure, not success-zero`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        val first = insertRecording(walk.id)
        val second = insertRecording(walk.id)
        engine.failure = IOException("boom") // every transcribe fails

        val outcome = runner.transcribePending(walk.id)

        assertTrue("expected failure, was $outcome", outcome.isFailure)
        val error = outcome.exceptionOrNull()
        assertTrue(
            "expected AllRecordingsFailedException, was $error",
            error is AllRecordingsFailedException,
        )
        assertEquals(2, (error as AllRecordingsFailedException).attempted)
        // Nothing was written.
        val rows = repository.voiceRecordingsFor(walk.id).associateBy { it.id }
        assertNull(rows.getValue(first.id).transcription)
        assertNull(rows.getValue(second.id).transcription)
    }

    // The all-failed signal is gated on `attempted > 0`: a walk with
    // nothing to transcribe is a no-op success, not a failure. No
    // recordings at all → pending is structurally empty (not a filtering
    // artifact of an already-transcribed row).
    @Test
    fun `empty pending returns success zero, not failure`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)

        val outcome = runner.transcribePending(walk.id)

        assertEquals(Result.success(0), outcome)
        // The finally fires for an empty batch too (the loop body never runs),
        // proving unloadModel is structurally outside the loop.
        assertEquals(1, engine.unloadModelCalls)
    }

    // A batch where every row is SKIPPED for a data-integrity reason
    // (blank path here; the path-escape guard behaves identically) never
    // reaches the engine, so attempted==0 → success(0), NOT the all-failed
    // path. Reporting "all failed" + terminal WorkManager failure for rows
    // that were never attempted would be a false signal.
    @Test
    fun `all recordings skipped for blank path report success, not all-failed`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        insertRecording(walk.id, fileRelativePath = "")
        insertRecording(walk.id, fileRelativePath = "")

        val outcome = runner.transcribePending(walk.id)

        assertEquals(Result.success(0), outcome)
        assertTrue(
            "engine must not be reached for blank-path rows",
            engine.transcribeCalls.isEmpty(),
        )
        assertEquals("all-skipped batch still unloads in the finally", 1, engine.unloadModelCalls)
    }

    // No-speech is a *successful* transcription (commits the placeholder
    // + increments the count), so an all-no-speech batch is success, not
    // the all-failed path.
    @Test
    fun `all no-speech recordings still report success`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        insertRecording(walk.id)
        insertRecording(walk.id)
        engine.resultText = "" // blank → NO_SPEECH_PLACEHOLDER, counts as processed

        val outcome = runner.transcribePending(walk.id)

        assertTrue("no-speech must not be treated as failure", outcome.isSuccess)
        assertEquals(2, outcome.getOrNull())
    }

    // AF33: the try/finally must release the model even when the batch is
    // cancelled mid-transcribe (the comment claims this; pin it). unloadModel
    // is non-suspend so the finally runs on the cancelled coroutine.
    @Test
    fun `transcribePending unloads the model when the batch is cancelled mid-flight`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        insertRecording(walk.id)
        engine.delayMs = 10_000L // transcribe parks in delay() so we can cancel mid-flight

        val job = launch { runner.transcribePending(walk.id) }
        while (engine.transcribeCalls.isEmpty()) yield() // wait until transcribe started
        job.cancelAndJoin()

        assertEquals(
            "cancellation still triggers unload via the finally",
            1,
            engine.unloadModelCalls,
        )
    }

    private val timestampCounter = AtomicLong(1_000_000L)

    private fun insertRecording(
        walkId: Long,
        transcription: String? = null,
        durationMillis: Long = 5_000L,
        fileRelativePath: String? = null,
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
            fileRelativePath = fileRelativePath ?: "recordings/test-${start}.wav",
            transcription = transcription,
        )
        val id = repository.recordVoice(recording)
        recording.copy(id = id)
    }
}
