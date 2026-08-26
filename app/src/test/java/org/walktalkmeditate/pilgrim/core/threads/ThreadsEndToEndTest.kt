// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyleStore
import org.walktalkmeditate.pilgrim.core.prompt.FaceDetectorClient
import org.walktalkmeditate.pilgrim.core.prompt.ImageLabelerClient
import org.walktalkmeditate.pilgrim.core.prompt.LabeledTag
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.LatLng
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient
import org.walktalkmeditate.pilgrim.core.prompt.PhotoContextAnalyzer
import org.walktalkmeditate.pilgrim.core.prompt.PlaceContext
import org.walktalkmeditate.pilgrim.core.prompt.PlaceRole
import org.walktalkmeditate.pilgrim.core.prompt.PromptGenerator
import org.walktalkmeditate.pilgrim.core.prompt.PromptGeocoder
import org.walktalkmeditate.pilgrim.core.prompt.PromptsCoordinator
import org.walktalkmeditate.pilgrim.core.prompt.TextRecognizerClient
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.photo.BitmapLoader
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository

/**
 * Task U11 deliverable #4: fake-transcription → analyzer → store →
 * dossier assembled through [PromptsCoordinator], with real DI-shaped
 * fakes at every OTHER boundary (geocoder, photo analyzer, practice/units
 * preferences) — the Threads chain itself is never doubled. Distinct from
 * [org.walktalkmeditate.pilgrim.audio.TranscriptionRunnerTest]'s own
 * threads-analysis tests (which stop at "a context was written to the
 * store") and from `PromptsCoordinatorTest`'s U9 tests (which start from
 * an ALREADY-transcribed `VoiceRecording` row, never exercising
 * [TranscriptionRunner]'s own post-persist trigger): this test starts at
 * a [FakeWhisperEngine] result and ends at a rendered prompt string,
 * proving every real link in between — the transcription-completion
 * trigger, the real analyzer, the real file store, the real
 * [ThreadsDossierBuilder], and the real [PromptGenerator]/`PromptAssembler`
 * — is actually wired together, not merely each independently correct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThreadsEndToEndTest {

    private lateinit var context: Application
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var threadsStore: TranscriptContextStore
    private lateinit var threadsPreferences: FakeThreadsPreferencesRepository
    private lateinit var customStyleFile: File
    private lateinit var photoCacheFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var customStyleStore: CustomPromptStyleStore
    private lateinit var photoCacheDataStore: DataStore<Preferences>
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
        threadsContextsDir.deleteRecursively()
        threadsPreferences = FakeThreadsPreferencesRepository(initialThreadsAfterWalks = true)
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

        modelRoot.deleteRecursively()
        val tiny = File(modelRoot, "ggml-tiny.en.bin")
        tiny.parentFile?.mkdirs()
        RandomAccessFile(tiny, "rw").use { it.setLength(WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES) }

        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        customStyleFile = File(context.cacheDir, "e2e-styles-${System.nanoTime()}.preferences_pb")
        photoCacheFile = File(context.cacheDir, "e2e-photo-cache-${System.nanoTime()}.preferences_pb")
        customStyleStore = CustomPromptStyleStore(
            PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { customStyleFile }),
            json, dataStoreScope,
        )
        photoCacheDataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { photoCacheFile })
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        customStyleFile.delete()
        photoCacheFile.delete()
        modelRoot.deleteRecursively()
        threadsContextsDir.deleteRecursively()
        db.close()
    }

    private fun realAnalyzer(): TranscriptContextAnalyzer = TranscriptContextAnalyzer(
        threadsStore,
        ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json)),
        MlKitLanguageIdClient(
            object : LanguageIdentifierGateway {
                override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> =
                    listOf(LanguageGuess("en", 0.99f))
            },
        ),
        threadsPreferences,
    )

    private fun realDossierBuilder(): ThreadsDossierBuilder = ThreadsDossierBuilder(
        threadsStore, realAnalyzer(), threadsPreferences,
        db.voiceRecordingDao(), db.walkDao(),
        db.routeDataSampleDao(), db.walkPhotoDao(), db.altitudeSampleDao(),
    )

    private fun newRunner(engine: FakeWhisperEngine): TranscriptionRunner = TranscriptionRunner(
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
        realAnalyzer(),
        threadsPreferences,
    )

    private fun newCoordinator(): PromptsCoordinator = PromptsCoordinator(
        repository = repository,
        customStyleStore = customStyleStore,
        photoContextAnalyzer = PhotoContextAnalyzer(
            dataStore = photoCacheDataStore,
            json = json,
            bitmapLoader = object : BitmapLoader {
                override suspend fun load(uri: Uri) = null
            },
            imageLabeler = object : ImageLabelerClient {
                override suspend fun label(bitmap: android.graphics.Bitmap) = emptyList<LabeledTag>()
            },
            textRecognizer = object : TextRecognizerClient {
                override suspend fun recognize(bitmap: android.graphics.Bitmap) = emptyList<String>()
            },
            faceDetector = object : FaceDetectorClient {
                override suspend fun detect(bitmap: android.graphics.Bitmap) = 0
            },
        ),
        geocoder = object : PromptGeocoder(context) {
            override suspend fun geocodeStart(coord: LatLng): PlaceContext? = null
            override suspend fun geocodeEnd(coord: LatLng, distanceFromStartMeters: Double): PlaceContext? = null
        },
        promptGenerator = PromptGenerator(context),
        practicePreferences = FakePracticePreferencesRepository(),
        unitsPreferences = FakeUnitsPreferencesRepository(),
        appContext = context,
        threadsDossierBuilder = realDossierBuilder(),
        mlKitLanguageIdClient = MlKitLanguageIdClient(
            object : LanguageIdentifierGateway {
                override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> =
                    listOf(LanguageGuess("en", 0.99f))
            },
        ),
    )

    private val wordyText = "I was walking and I have to say I think about music because I can think about " +
        "music too and I will think about music again since I have so many things I want and need. " +
        "The garden was wide and calm and I noticed the garden every single time I walked beside the garden " +
        "today, tracing its edge with my eyes."

    @Test
    fun `a fake transcription becomes a real stored context becomes a real dossier inside a real assembled prompt`() = runBlocking {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.recordVoice(
            VoiceRecording(
                walkId = walk.id, startTimestamp = 1_000L, endTimestamp = 2_000L, durationMillis = 1_000L,
                fileRelativePath = "voice/r1.opus", transcription = null,
            ),
        )

        // 1. Fake transcription completes...
        val engine = FakeWhisperEngine(resultText = wordyText)
        val outcome = newRunner(engine).transcribePending(walk.id)
        assertTrue("the fake-transcription batch must succeed", outcome.isSuccess)
        assertEquals(1, outcome.getOrNull())

        // 2. ...and the REAL post-persist trigger really did write a REAL
        // stored context — not asserted indirectly via the dossier below.
        val recording = repository.voiceRecordingsFor(walk.id).single()
        assertEquals(wordyText, recording.transcription)
        assertTrue(
            "the real TranscriptionRunner -> analyzeThreadsSafely -> analyzeAndStore chain must land a context",
            threadsStore.hasContext(recording.uuid),
        )

        // 3. The real PromptsCoordinator builds a context carrying the real dossier.
        val coordinator = newCoordinator()
        val activityContext = coordinator.buildContext(walkId = walk.id)
        assertNotNull(activityContext)
        val dossier = activityContext!!.threadsDossier
        assertNotNull("buildContext must surface the real dossier text", dossier)
        assertTrue(dossier!!.startsWith("**Thought threads (on-device linguistic analysis):**"))
        assertTrue("the corpus's repeated 'garden' must actually form a theme", dossier.contains("'garden'"))

        // 4. ...and every generated prompt carries that SAME dossier text
        // verbatim, plus the threads-gated safety line in its response
        // contract — the whole chain, not just the ActivityContext field.
        val prompts = coordinator.generateAll(activityContext)
        assertTrue(prompts.isNotEmpty())
        for (prompt in prompts) {
            assertTrue(
                "prompt \"${prompt.title}\" must carry the dossier text verbatim",
                prompt.text.contains(dossier),
            )
            assertTrue("prompt \"${prompt.title}\" must report hasThreadsDossier", prompt.hasThreadsDossier)
            assertTrue(
                "prompt \"${prompt.title}\" must carry the thought-thread safety line",
                prompt.text.contains("thought-thread marker profiles are descriptive on-device linguistic signals"),
            )
        }
    }
}
