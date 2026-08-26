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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.serialization.json.Json
import org.walktalkmeditate.pilgrim.audio.model.FakeWhisperModelDownloadScheduler
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWork
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWorkSource
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelConfig
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient
import org.walktalkmeditate.pilgrim.core.threads.FakeThreadsPreferencesRepository
import org.walktalkmeditate.pilgrim.core.threads.ThreadsAnalysisEnvironment
import org.walktalkmeditate.pilgrim.core.threads.TranscriptContextAnalyzer
import org.walktalkmeditate.pilgrim.core.threads.TranscriptContextStore
import org.walktalkmeditate.pilgrim.core.threads.WordNetLexicon
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.dao.ActivityIntervalDao
import org.walktalkmeditate.pilgrim.data.dao.AltitudeSampleDao
import org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.dao.WaypointDao
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
    private lateinit var threadsPreferences: FakeThreadsPreferencesRepository
    private lateinit var threadsStore: TranscriptContextStore
    private var languageGuess = LanguageGuess("en", 0.99f)
    private var languageDetectionError: Throwable? = null
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val modelRoot: File
        get() = File(context.filesDir, "whisper-model")

    private val threadsContextsDir: File
        get() = File(context.filesDir, "transcript_contexts")

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
        threadsContextsDir.deleteRecursively()
        // Off by default: every PRE-EXISTING test in this file transcribes
        // without caring about Threads at all, so the toggle stays off
        // unless a test explicitly opts in — this is the same fast
        // bail-out path production takes for a real toggled-off user.
        threadsPreferences = FakeThreadsPreferencesRepository(initialThreadsAfterWalks = false)
        threadsStore = TranscriptContextStore(context, json)
        runner = buildRunner(engine)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        modelRoot.deleteRecursively()
        threadsContextsDir.deleteRecursively()
        db.close()
    }

    private fun buildRunner(engine: WhisperEngine, repository: WalkRepository = this.repository) = TranscriptionRunner(
        context,
        repository,
        engine,
        store,
        downloadScheduler,
        TranscriptContextAnalyzer(
            store = threadsStore,
            environment = ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json)),
            languageIdClient = MlKitLanguageIdClient(
                object : LanguageIdentifierGateway {
                    override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> {
                        languageDetectionError?.let { throw it }
                        return listOf(languageGuess)
                    }
                },
            ),
            preferences = threadsPreferences,
        ),
        threadsPreferences,
    )

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

    // ---- Phase 20 U5: post-persist Threads analysis wiring ----

    @Test
    fun `threads analysis is skipped entirely when the toggle is off`() = runBlocking {
        // threadsPreferences defaults to off in setUp().
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)
        engine.resultSegments = listOf(WhisperSegment("hello there world", 0L, 500L, 0.01f))

        runner.transcribePending(walk.id)

        val updated = repository.getVoiceRecording(recording.id)!!
        assertFalse(threadsStore.hasContext(updated.uuid))
    }

    @Test
    fun `threads analysis writes a context when the toggle is on and language is English`() = runBlocking {
        threadsPreferences.setThreadsAfterWalks(true)
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)
        // 30 words, English, no flagged segments.
        engine.resultSegments = listOf(
            WhisperSegment(
                text = "I was walking along the river this morning and noticed how quiet the trail " +
                    "was with the light moving gently through the leaves above the water and stones",
                t0Ms = 0L,
                t1Ms = 12_000L,
                noSpeechProb = 0.01f,
            ),
        )

        runner.transcribePending(walk.id)

        val updated = repository.getVoiceRecording(recording.id)!!
        assertTrue("a context must be written for a toggled-on English recording", threadsStore.hasContext(updated.uuid))
    }

    @Test
    fun `threads analysis is skipped for a no-speech placeholder recording`() = runBlocking {
        threadsPreferences.setThreadsAfterWalks(true)
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)
        engine.resultSegments = listOf(WhisperSegment("", 0L, 0L, 0.95f))

        runner.transcribePending(walk.id)

        val updated = repository.getVoiceRecording(recording.id)!!
        assertEquals(TranscriptionRunner.NO_SPEECH_PLACEHOLDER, updated.transcription)
        assertFalse(
            "no real transcript exists for a no-speech row — nothing should be analyzed",
            threadsStore.hasContext(updated.uuid),
        )
    }

    @Test
    fun `threads analysis does not write when the detected language is not English`() = runBlocking {
        threadsPreferences.setThreadsAfterWalks(true)
        languageGuess = LanguageGuess("ja", 0.99f)
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)
        engine.resultSegments = listOf(
            WhisperSegment(
                text = "I was walking along the river this morning and noticed how quiet the trail " +
                    "was with the light moving gently through the leaves above the water and stones",
                t0Ms = 0L,
                t1Ms = 12_000L,
                noSpeechProb = 0.01f,
            ),
        )

        runner.transcribePending(walk.id)

        val updated = repository.getVoiceRecording(recording.id)!!
        assertFalse(threadsStore.hasContext(updated.uuid))
    }

    @Test
    fun `an analyzer failure never blocks the already-persisted transcription`() = runBlocking {
        threadsPreferences.setThreadsAfterWalks(true)
        // Language detection itself throws — proves TranscriptionRunner's
        // analyzeThreadsSafely try/catch, not just a store-level no-op.
        languageDetectionError = RuntimeException("boom")
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)
        engine.resultSegments = listOf(WhisperSegment("hello there friend", 0L, 500L, 0.01f))

        val outcome = runner.transcribePending(walk.id)

        assertEquals(Result.success(1), outcome)
        val updated = repository.getVoiceRecording(recording.id)!!
        assertEquals("hello there friend", updated.transcription)
        assertFalse(
            "the thrown error must not have left a context behind",
            threadsStore.hasContext(updated.uuid),
        )
    }

    // ---- U2/BEH-58: persistence retries exactly once (two total attempts) ----

    private fun flakyRepository(failuresBeforeSuccess: Int) = FlakyWalkRepository(
        database = db,
        walkDao = db.walkDao(),
        routeDao = db.routeDataSampleDao(),
        altitudeDao = db.altitudeSampleDao(),
        walkEventDao = db.walkEventDao(),
        activityIntervalDao = db.activityIntervalDao(),
        waypointDao = db.waypointDao(),
        voiceRecordingDao = db.voiceRecordingDao(),
        walkPhotoDao = db.walkPhotoDao(),
        failuresBeforeSuccess = failuresBeforeSuccess,
    )

    @Test
    fun `a transient DB failure on the first attempt succeeds on the retry`() = runBlocking {
        val flaky = flakyRepository(failuresBeforeSuccess = 1)
        val flakyRunner = buildRunner(engine, repository = flaky)
        val walk = flaky.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)
        engine.resultText = "persisted on retry"

        val outcome = flakyRunner.transcribePending(walk.id)

        assertEquals(Result.success(1), outcome)
        assertEquals(2, flaky.updateAttempts)
        assertEquals("persisted on retry", flaky.getVoiceRecording(recording.id)!!.transcription)
    }

    @Test
    fun `two consecutive DB failures give up — not persisted, not counted`() = runBlocking {
        val flaky = flakyRepository(failuresBeforeSuccess = 2)
        val flakyRunner = buildRunner(engine, repository = flaky)
        val walk = flaky.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)

        val outcome = flakyRunner.transcribePending(walk.id)

        assertTrue("expected failure (all attempted recordings failed), was $outcome", outcome.isFailure)
        assertEquals(2, flaky.updateAttempts)
        assertNull(
            "no more than 2 attempts — no backoff loop, no third try",
            flaky.getVoiceRecording(recording.id)!!.transcription,
        )
    }

    private class FlakyWalkRepository(
        database: PilgrimDatabase,
        walkDao: WalkDao,
        routeDao: RouteDataSampleDao,
        altitudeDao: AltitudeSampleDao,
        walkEventDao: WalkEventDao,
        activityIntervalDao: ActivityIntervalDao,
        waypointDao: WaypointDao,
        voiceRecordingDao: VoiceRecordingDao,
        walkPhotoDao: WalkPhotoDao,
        private var failuresBeforeSuccess: Int,
    ) : WalkRepository(
        database, walkDao, routeDao, altitudeDao, walkEventDao,
        activityIntervalDao, waypointDao, voiceRecordingDao, walkPhotoDao,
    ) {
        var updateAttempts = 0
            private set

        override suspend fun updateVoiceRecording(recording: VoiceRecording) {
            updateAttempts++
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--
                throw IOException("simulated transient DB failure")
            }
            super.updateVoiceRecording(recording)
        }
    }

    // ---- flagged-segment quality signal (compressionRatio / noSpeechProb) ----

    @Test
    fun `a segment flagged by noSpeechProb is scrubbed from the marker text`() = runBlocking {
        threadsPreferences.setThreadsAfterWalks(true)
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)
        // "should" appears in a segment whose noSpeechProb clears the 0.6
        // flag threshold; the analysis must scrub it from markers even
        // though the persisted transcription keeps the raw text.
        engine.resultSegments = listOf(
            WhisperSegment("should should should. ", 0L, 500L, 0.95f),
            WhisperSegment(
                "apple banana cherry date fig grape kiwi lemon mango orange peach quince fruit basket",
                500L,
                6000L,
                0.01f,
            ),
        )

        runner.transcribePending(walk.id)

        val updated = repository.getVoiceRecording(recording.id)!!
        val context = threadsStore.readRaw(updated.uuid)
        assertTrue(context != null)
        assertEquals(
            "the flagged 'should should should' fragment must be scrubbed from markers",
            0,
            context!!.markers.discrepancyCount,
        )
    }

    // I2: the REAL WhisperCppEngine.transcribeWithSegments joins raw
    // segment text then trims ONCE at the very ends (see that class) —
    // FakeWhisperEngine's own join does not replicate that trim, so this
    // test uses a one-off engine (same pattern as "per-recording engine
    // failure does not abort the batch" above) that reproduces the real
    // join-then-trim exactly. A flagged FIRST segment's own leading space
    // survives in its raw segment.text but is gone from the trimmed
    // transcript — an untrimmed fragment search would never find it there.
    @Test
    fun `a flagged FIRST segment carrying a leading space is still scrubbed from the marker text`() = runBlocking {
        threadsPreferences.setThreadsAfterWalks(true)
        val walk = repository.startWalk(startTimestamp = 0L)
        val recording = insertRecording(walk.id)
        val segments = listOf(
            WhisperSegment(" should should should.", 0L, 500L, 0.95f),
            WhisperSegment(
                " apple banana cherry date fig grape kiwi lemon mango orange peach quince fruit basket",
                500L,
                6000L,
                0.01f,
            ),
        )
        val joinThenTrimEngine = object : WhisperEngine {
            override suspend fun transcribe(wavPath: java.nio.file.Path) =
                Result.success(TranscriptionResult(text = "", wordsPerMinute = null))
            override suspend fun transcribeWithSegments(wavPath: java.nio.file.Path) = Result.success(
                TranscriptionResult(
                    text = segments.joinToString("") { it.text }.trim(),
                    wordsPerMinute = null,
                    segments = segments,
                ),
            )
            override fun unloadModel() {}
        }
        val customRunner = buildRunner(joinThenTrimEngine)

        customRunner.transcribePending(walk.id)

        val updated = repository.getVoiceRecording(recording.id)!!
        val context = threadsStore.readRaw(updated.uuid)
        assertTrue(context != null)
        assertEquals(
            "the flagged FIRST segment's fragment must still be found and scrubbed even though " +
                "the global transcript trims away its leading space",
            0,
            context!!.markers.discrepancyCount,
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
