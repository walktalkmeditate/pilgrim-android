// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.walktalkmeditate.pilgrim.audio.FakeWhisperEngine
import org.walktalkmeditate.pilgrim.audio.TranscriptionRunner
import org.walktalkmeditate.pilgrim.audio.model.FakeWhisperModelDownloadScheduler
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWork
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWorkSource
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelConfig
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc
import org.walktalkmeditate.pilgrim.core.prompt.ActivityContext
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient
import org.walktalkmeditate.pilgrim.core.prompt.PracticeMode
import org.walktalkmeditate.pilgrim.core.prompt.PromptGenerator
import org.walktalkmeditate.pilgrim.core.prompt.PromptStyle
import org.walktalkmeditate.pilgrim.core.prompt.RecordingContext
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * R12 toggle-sovereignty sweep (Task U11 step 4): with `threadsAfterWalks`
 * OFF, three independent guarantees, each proven against the REAL
 * production classes (never a hand-rolled stand-in for the gate itself):
 *
 *  1. A prompt assembled for a walk that WOULD qualify for a dossier
 *     carries zero threads content — proven non-vacuously against the
 *     SAME walk toggled on.
 *  2. The full fake-transcription pipeline ([TranscriptionRunner] with a
 *     [FakeWhisperEngine]) writes zero [TranscriptContextStore] files and
 *     never moves its `changeCount`.
 *  3. [ThreadIntentionSuggestions] ("Recurring" intention chips) render
 *     empty after [ThreadsFullWipe.wipe] — AE5's chips clause — proven
 *     against suggestions that were genuinely non-empty immediately
 *     beforehand, not merely empty because nothing was ever seeded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsToggleSovereigntyTest {

    private lateinit var context: Application
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var threadsStore: TranscriptContextStore
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val threadsContextsDir: File
        get() = File(context.filesDir, "transcript_contexts")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        threadsContextsDir.deleteRecursively()
        threadsStore = TranscriptContextStore(context, json)
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
            transcriptContextStore = threadsStore,
        )
    }

    @After
    fun tearDown() {
        db.close()
        threadsContextsDir.deleteRecursively()
        File(context.filesDir, "whisper-model").deleteRecursively()
    }

    private fun realAnalyzer(preferences: ThreadsPreferencesRepository): TranscriptContextAnalyzer =
        TranscriptContextAnalyzer(
            threadsStore,
            ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json)),
            MlKitLanguageIdClient(
                object : LanguageIdentifierGateway {
                    override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> =
                        listOf(LanguageGuess("en", 0.99f))
                },
            ),
            preferences,
        )

    private fun realBuilder(preferences: ThreadsPreferencesRepository): ThreadsDossierBuilder =
        ThreadsDossierBuilder(
            threadsStore, realAnalyzer(preferences), preferences,
            db.voiceRecordingDao(), db.walkDao(),
            db.routeDataSampleDao(), db.walkPhotoDao(), db.altitudeSampleDao(),
        )

    private val wordyText = "I was walking and I have to say I think about music because I can think about " +
        "music too and I will think about music again since I have so many things I want and need. " +
        "The river was wide and calm and I noticed the river every single time I walked beside the river " +
        "today, tracing its edge with my eyes."

    // ------------------------------------------------------------------
    // 1. Prompt byte-identical to a no-threads build
    // ------------------------------------------------------------------

    @Test
    fun `toggle off yields zero threads content in the assembled prompt, though the same walk qualifies when on`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.recordVoice(
            VoiceRecording(
                walkId = walk.id, startTimestamp = 1_000L, endTimestamp = 2_000L, durationMillis = 1_000L,
                fileRelativePath = "voice/r1.opus", transcription = wordyText, wordsPerMinute = 120.0,
            ),
        )
        val generator = PromptGenerator(context)
        val lunarPhase = MoonCalc.moonPhase(Instant.ofEpochMilli(walk.startTimestamp))

        // Built FIRST, deliberately: unlike production — where
        // PromptsCoordinator.buildContext now installs TranscriptNlp
        // itself before any directive ever runs (the C2 fix) — this test
        // calls PromptGenerator.generate directly below, bypassing that
        // entry point entirely. A real toggle-on analysis
        // (TranscriptContextAnalyzer.analyzeAndStore's own self-heal
        // install, exercised for real here, not faked) installs the same
        // lexicon as a side effect of genuine production code, so this
        // test never needs a manual TranscriptNlp.install shortcut of its
        // own — the way an earlier version of this file did.
        val onPreferences = FakeThreadsPreferencesRepository(initialThreadsAfterWalks = true)
        val onDossier = realBuilder(onPreferences).build(walk.id)
        assertNotNull("the identical walk must produce a dossier once the toggle is on", onDossier)

        val offPreferences = FakeThreadsPreferencesRepository(initialThreadsAfterWalks = false)
        val offDossier = realBuilder(offPreferences).build(walk.id)
        assertNull("the toggle-off builder must never produce a dossier", offDossier)

        val offContext = ActivityContext(
            recordings = listOf(
                RecordingContext(uuid = "r1", timestamp = 1_000L, startCoordinate = null, endCoordinate = null, wordsPerMinute = 120.0, text = wordyText),
            ),
            meditations = emptyList(), durationSeconds = 60L, distanceMeters = 100.0, startTimestamp = walk.startTimestamp,
            placeNames = emptyList(), routeSpeeds = emptyList(), recentWalkSnippets = emptyList(), intention = null,
            waypoints = emptyList(), weather = null, lunarPhase = lunarPhase, celestial = null, photoContexts = emptyList(),
            narrativeArc = null, mode = PracticeMode.Wander, seekStory = null, pauses = emptyList(),
            ascentMeters = 0.0, descentMeters = 0.0, threadsDossier = offDossier?.text, detectedLanguageCode = null,
        )
        val offPrompt = generator.generate(PromptStyle.Contemplative, offContext, imperial = false)

        for (marker in listOf(
            "Thought threads", "**Noticed:**", "modal lean:",
            "thought-thread marker profiles", "Recording 1:",
        )) {
            assertFalse(
                "toggle-off prompt must carry zero threads content — found \"$marker\"",
                offPrompt.text.contains(marker),
            )
        }
        assertFalse("hasThreadsDossier must be false with the toggle off", offPrompt.hasThreadsDossier)

        // Non-vacuous: the SAME walk, toggled on, really does qualify —
        // proving the off-result above is the toggle's doing, not an
        // artifact of a corpus too thin to ever produce a dossier.
        val onContext = offContext.copy(threadsDossier = onDossier!!.text)
        val onPrompt = generator.generate(PromptStyle.Contemplative, onContext, imperial = false)
        assertTrue(
            "the toggle-on prompt for the identical walk must actually carry threads content",
            onPrompt.text.contains("Thought threads"),
        )

        // The only field difference between the two ActivityContexts is
        // threadsDossier — PromptAssembler.assemble is a pure function of
        // its context, so the off/on text must differ ONLY where the
        // dossier itself was spliced in; everything else — including the
        // exact wording of the response contract, which gains the
        // thought-thread safety line only when the artifact is present —
        // is what "byte-identical to a no-threads build" means here: a
        // dossier-free ActivityContext produces a prompt indistinguishable
        // from one built by a codebase that never had this feature at all.
        assertEquals(
            "a second dossier-free build of the identical context must be byte-for-byte deterministic",
            offPrompt.text,
            generator.generate(PromptStyle.Contemplative, offContext, imperial = false).text,
        )
    }

    // ------------------------------------------------------------------
    // 2. Zero context writes on the full pipeline path
    // ------------------------------------------------------------------

    @Test
    fun `the full fake-transcription pipeline writes zero contexts when the toggle is off`() = runBlocking {
        val modelRoot = File(context.filesDir, "whisper-model")
        modelRoot.deleteRecursively()
        val tiny = File(modelRoot, "ggml-tiny.en.bin")
        tiny.parentFile?.mkdirs()
        java.io.RandomAccessFile(tiny, "rw").use { it.setLength(WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES) }

        val threadsPreferences = FakeThreadsPreferencesRepository(initialThreadsAfterWalks = false)
        val engine = FakeWhisperEngine(resultText = wordyText)
        val runner = TranscriptionRunner(
            context, repository, engine,
            WhisperModelStore(
                context = context,
                workSource = object : ModelDownloadWorkSource {
                    override fun observe(): Flow<ModelDownloadWork?> = flowOf(null)
                },
                unmeteredProbe = { true },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ),
            FakeWhisperModelDownloadScheduler(),
            realAnalyzer(threadsPreferences),
            threadsPreferences,
        )

        val walk = repository.startWalk(startTimestamp = 0L)
        repository.recordVoice(
            VoiceRecording(
                walkId = walk.id, startTimestamp = 1_000L, endTimestamp = 2_000L, durationMillis = 1_000L,
                fileRelativePath = "voice/r1.opus", transcription = null,
            ),
        )
        repository.recordVoice(
            VoiceRecording(
                walkId = walk.id, startTimestamp = 2_000L, endTimestamp = 3_000L, durationMillis = 1_000L,
                fileRelativePath = "voice/r2.opus", transcription = null,
            ),
        )
        val beforeChangeCount = threadsStore.changeCount.value

        val outcome = runner.transcribePending(walk.id)

        assertTrue("the transcription batch itself must still succeed with the toggle off", outcome.isSuccess)
        assertEquals(2, outcome.getOrNull())
        assertEquals(
            "toggle-off must leave the store's changeCount untouched by the whole batch",
            beforeChangeCount,
            threadsStore.changeCount.value,
        )
        assertEquals(
            "toggle-off must leave zero context files on disk after a real transcription batch",
            emptyList<String>(),
            threadsStore.allUuids(),
        )
    }

    // ------------------------------------------------------------------
    // 3. Chips render empty after the internal full-wipe (AE5)
    // ------------------------------------------------------------------

    @Test
    fun `Recurring chips render empty after the internal full-wipe, though they were non-empty beforehand`() = runBlocking {
        val onPreferences = FakeThreadsPreferencesRepository(initialThreadsAfterWalks = true)
        val analyzer = realAnalyzer(onPreferences)
        val suggestions = ThreadIntentionSuggestions(threadsStore, onPreferences, db.voiceRecordingDao())

        val gardenText = "The garden keeps calling me back this week. I noticed the garden again today " +
            "and thought about how much has changed since spring, the light longer now than before."
        val walkA = repository.startWalk(startTimestamp = 0L)
        val recA = repository.recordVoice(
            VoiceRecording(
                walkId = walkA.id, startTimestamp = 1_000L, endTimestamp = 2_000L, durationMillis = 1_000L,
                fileRelativePath = "voice/a.opus", transcription = gardenText,
            ),
        )
        val walkB = repository.startWalk(startTimestamp = 5 * 86_400_000L)
        val recB = repository.recordVoice(
            VoiceRecording(
                walkId = walkB.id, startTimestamp = walkB.startTimestamp + 1_000L, endTimestamp = walkB.startTimestamp + 2_000L,
                durationMillis = 1_000L, fileRelativePath = "voice/b.opus", transcription = gardenText,
            ),
        )
        val recAUuid = requireNotNull(repository.getVoiceRecording(recA)).uuid
        val recBUuid = requireNotNull(repository.getVoiceRecording(recB)).uuid
        assertNotNull(analyzer.analyzeAndStore(recAUuid, gardenText))
        assertNotNull(analyzer.analyzeAndStore(recBUuid, gardenText))

        val now = Instant.ofEpochMilli(walkB.startTimestamp + 10_000L)
        val before = suggestions.current(now)
        assertTrue(
            "the fixture must genuinely qualify before the wipe — got $before",
            before.any { it.contains("garden") },
        )

        ThreadsFullWipe(threadsStore, onPreferences).wipe(listOf(recAUuid, recBUuid))

        val after = suggestions.current(now)
        assertEquals(
            "chips must render empty immediately after Delete All Data (AE5) — the context " +
                "directory the memo reads from is now empty",
            emptyList<String>(),
            after,
        )
    }
}
