// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.FakeVoicePlaybackController
import org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.collective.ContributionLedger
import org.walktalkmeditate.pilgrim.data.collective.routes.CollectiveRouteCatalogService
import org.walktalkmeditate.pilgrim.data.collective.routes.bootstrapRouteCatalogService
import org.walktalkmeditate.pilgrim.data.collective.routes.inMemoryContributionLedger
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.data.walk.RouteActivity
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotationKind
import org.walktalkmeditate.pilgrim.data.sharing.WalkSharingTracker
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestone
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryViewModelTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var playback: FakeVoicePlaybackController
    private lateinit var scheduler: FakeTranscriptionScheduler
    private lateinit var sweeper: OrphanRecordingSweeper
    private lateinit var hemisphereDataStore: DataStore<Preferences>
    private lateinit var hemisphereLocation: FakeLocationSource
    private lateinit var hemisphereRepo: HemisphereRepository
    private lateinit var hemisphereScope: CoroutineScope
    private lateinit var persistenceScope: CoroutineScope
    private lateinit var routeCatalogScope: CoroutineScope
    private lateinit var routeCatalogService: CollectiveRouteCatalogService
    private lateinit var contributionLedger: ContributionLedger
    private lateinit var modelStoreScope: CoroutineScope
    private lateinit var modelStore: org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore
    // U7 edit-path wiring (BEH-59 carry): real store + real WordNet/VADER
    // analysis, matching TranscriptContextAnalyzerTest's established
    // pattern — only the language client is faked (always English).
    private lateinit var threadsStore: org.walktalkmeditate.pilgrim.core.threads.TranscriptContextStore
    private lateinit var threadsPreferences: org.walktalkmeditate.pilgrim.core.threads.FakeThreadsPreferencesRepository
    private lateinit var threadsAnalyzer: org.walktalkmeditate.pilgrim.core.threads.TranscriptContextAnalyzer
    private val dispatcher = UnconfinedTestDispatcher()

    /**
     * Stage 7-A leak pattern (the sibling WalkSummaryViewModel*Test
     * classes already carry it — this file was the one member missing
     * it): every VM constructed in a test parks here so tearDown can
     * cancel AND JOIN its `viewModelScope` before `Dispatchers.resetMain()`.
     * The VM's Eagerly `state` sharing coroutine runs on
     * Dispatchers.Main.immediate and hops through
     * `withContext(Dispatchers.Default)` mid-`buildState`; if it
     * outlives the test, its resume back into Dispatchers.Main races
     * the @After resetMain / next-@Before setMain delegate swap on
     * TestMainDispatcher — isDispatchNeeded can read the real main
     * (true) and dispatch can land on the next test's
     * UnconfinedTestDispatcher, whose dispatch() throws
     * UnsupportedOperationException (TestCoroutineDispatchers.kt:106)
     * into whichever runTest is active. Join (not just cancel) is what
     * closes the window: dispatch happens even for cancelled
     * coroutines, so only full completion guarantees no further Main
     * dispatches.
     */
    private val createdViewModels = mutableListOf<WalkSummaryViewModel>()

    private val modelRoot: java.io.File
        get() = java.io.File(context.filesDir, "whisper-model")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        // Pipe Room's query + transaction executors through the test
        // dispatcher so in-flight Room coroutines are drained by
        // runTest's virtual-time scheduling before @After's db.close()
        // runs — otherwise a transaction that was suspended on
        // Room's default arch_disk_io pool wakes up after db.close()
        // and throws `IllegalStateException: The database ':memory:'
        // is not open.` The uncaught exception gets captured by
        // kotlinx-coroutines-test and re-raised as
        // UncaughtExceptionsBeforeTest in a LATER test (possibly a
        // different class), with a misleading stack pointer. Fork-
        // layout changes from adding Robolectric classes elsewhere
        // in the tree can surface this; the dispatcher-piping fix
        // resolves it for good.
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
        run {
            val threadsJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
            java.io.File(context.filesDir, "transcript_contexts").deleteRecursively()
            threadsStore = org.walktalkmeditate.pilgrim.core.threads.TranscriptContextStore(context, threadsJson)
            val threadsEnvironment = org.walktalkmeditate.pilgrim.core.threads.ThreadsAnalysisEnvironment(
                context,
                org.walktalkmeditate.pilgrim.core.threads.WordNetLexicon(context, threadsJson),
            )
            threadsPreferences = org.walktalkmeditate.pilgrim.core.threads.FakeThreadsPreferencesRepository()
            val threadsLanguageClient = org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient(
                object : org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway {
                    override suspend fun identifyPossibleLanguages(
                        text: String,
                    ): List<org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess> =
                        listOf(org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess("en", 0.99f))
                },
            )
            threadsAnalyzer = org.walktalkmeditate.pilgrim.core.threads.TranscriptContextAnalyzer(
                threadsStore,
                threadsEnvironment,
                threadsLanguageClient,
                threadsPreferences,
            )
        }
        playback = FakeVoicePlaybackController()
        scheduler = FakeTranscriptionScheduler()
        sweeper = OrphanRecordingSweeper(context, repository, scheduler)
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        hemisphereDataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(hemisphereStoreName) },
        )
        hemisphereLocation = FakeLocationSource()
        hemisphereScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hemisphereRepo = HemisphereRepository(hemisphereDataStore, hemisphereLocation, hemisphereScope)
        // Pipe persistence-scope writes through the same test dispatcher
        // so DAO calls land on Room's test executor (set up at line ~95).
        // SupervisorJob mirrors the production provider's failure isolation.
        persistenceScope = CoroutineScope(SupervisorJob() + dispatcher)
        // U6: real service over the bundled bootstrap asset (never
        // fetches — syncIfNeeded is not called and the URL resolves
        // nowhere). Tests that need the catalog await initialLoad.
        routeCatalogScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        routeCatalogService = bootstrapRouteCatalogService(context, routeCatalogScope)
        contributionLedger = inMemoryContributionLedger()
        // U11: real model store over the Robolectric filesDir
        // (TranscriptionRunnerTest pattern). Gating tests that need the
        // retranscribe gate open call installLegacyTiny() and await the
        // probe; everything else runs with the gate closed (Absent).
        modelRoot.deleteRecursively()
        modelStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        modelStore = org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore(
            context = context,
            workSource = object : org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWorkSource {
                override fun observe(): kotlinx.coroutines.flow.Flow<org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWork?> =
                    kotlinx.coroutines.flow.flowOf(null)
            },
            unmeteredProbe = { true },
            scope = modelStoreScope,
        )
    }

    private fun installLegacyTiny() {
        val tiny = java.io.File(modelRoot, "ggml-tiny.en.bin")
        tiny.parentFile?.mkdirs()
        java.io.RandomAccessFile(tiny, "rw").use {
            it.setLength(
                org.walktalkmeditate.pilgrim.audio.model.WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES,
            )
        }
    }

    /**
     * Bridge the store's real-IO probe to virtual-time runTest on the
     * dedicated real-clock dispatcher (house pattern — never
     * Thread.sleep on the shared Default pool).
     */
    private suspend fun awaitRetranscribeEnabled(vm: WalkSummaryViewModel) {
        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                vm.retranscribeEnabled.first { it }
            }
        }
    }

    private lateinit var photoAnalysisScheduler: org.walktalkmeditate.pilgrim.data.photo.FakePhotoAnalysisScheduler

    private fun newViewModel(
        walkId: Long,
        repositoryOverride: WalkRepository = repository,
        routeCatalogServiceOverride: CollectiveRouteCatalogService = routeCatalogService,
        transcriptionSchedulerOverride: org.walktalkmeditate.pilgrim.audio.TranscriptionScheduler =
            object : org.walktalkmeditate.pilgrim.audio.TranscriptionScheduler {
                override fun scheduleForWalk(walkId: Long) {}
                override fun rescheduleForWalk(walkId: Long) {}
            },
        practicePreferences: org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository =
            // Stage 10-C: light reading is gated on celestialAwarenessEnabled.
            // The legacy tests in this file all assert non-null lightReading
            // (or don't care) — flip the default ON so they pass without
            // changes. The OFF-suppression path has its own dedicated test.
            org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(
                initialCelestialAwarenessEnabled = true,
            ),
        autoTranscriptionSkipStateOverride: org.walktalkmeditate.pilgrim.core.threads.FakeAutoTranscriptionSkipState =
            org.walktalkmeditate.pilgrim.core.threads.FakeAutoTranscriptionSkipState(),
        threadsAnalyzerOverride: org.walktalkmeditate.pilgrim.core.threads.TranscriptContextAnalyzer = threadsAnalyzer,
    ): WalkSummaryViewModel {
        photoAnalysisScheduler = org.walktalkmeditate.pilgrim.data.photo.FakePhotoAnalysisScheduler()
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val cachedShareStore = org.walktalkmeditate.pilgrim.data.share.CachedShareStore(
            dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                produceFile = {
                    java.io.File(
                        context.cacheDir,
                        "vmtest-share-${java.util.UUID.randomUUID()}.preferences_pb",
                    )
                },
            ),
            json = json,
        )
        val vm = WalkSummaryViewModel(
            context = context,
            repository = repositoryOverride,
            playback = playback,
            sweeper = sweeper,
            photoAnalysisScheduler = photoAnalysisScheduler,
            hemisphereRepository = hemisphereRepo,
            cachedShareStore = cachedShareStore,
            unitsPreferences = org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            practicePreferences = practicePreferences,
            promptsCoordinator = newStubPromptsCoordinator(),
            sealRevealStore = org.walktalkmeditate.pilgrim.data.seal.SealRevealStore(
                dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    produceFile = {
                        java.io.File(
                            context.cacheDir,
                            "vmtest-seal-reveal-${java.util.UUID.randomUUID()}.preferences_pb",
                        )
                    },
                ),
            ),
            walkSharingTracker = WalkSharingTracker(
                dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    produceFile = {
                        java.io.File(
                            context.cacheDir,
                            "vmtest-sharing-${java.util.UUID.randomUUID()}.preferences_pb",
                        )
                    },
                ),
            ),
            photoExifReader = org.walktalkmeditate.pilgrim.data.photo.PhotoExifReader(
                context = context,
            ),
            photoLibraryScanner = org.walktalkmeditate.pilgrim.data.photo.PhotoLibraryScanner(
                context = context,
            ),
            transcriptionScheduler = transcriptionSchedulerOverride,
            waveformCache = org.walktalkmeditate.pilgrim.audio.WaveformCache(
                fileSystem = org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem(context),
            ),
            whisperModelStore = modelStore,
            routeCatalogService = routeCatalogServiceOverride,
            contributionLedger = contributionLedger,
            persistenceScope = persistenceScope,
            autoTranscriptionSkipState = autoTranscriptionSkipStateOverride,
            threadsAnalyzer = threadsAnalyzerOverride,
            savedStateHandle = SavedStateHandle(mapOf("walkId" to walkId)),
        )
        createdViewModels += vm
        return vm
    }

    /**
     * Stage 13-XZ: legacy `WalkSummaryViewModelTest` cases don't exercise
     * the prompts surface — wire a minimal subclass that stays inert
     * (buildContext returns null so any accidental `openPromptsSheet`
     * resolves to Closed without touching the production graph).
     * State-machine + cache-invalidation coverage lives in
     * `WalkSummaryViewModelPromptsTest`.
     */
    private fun newStubPromptsCoordinator(): org.walktalkmeditate.pilgrim.core.prompt.PromptsCoordinator {
        val ctxApp = context
        return object : org.walktalkmeditate.pilgrim.core.prompt.PromptsCoordinator(
            repository = repository,
            customStyleStore = org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyleStore(
                dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    produceFile = {
                        java.io.File(
                            ctxApp.cacheDir,
                            "vmtest-styles-${java.util.UUID.randomUUID()}.preferences_pb",
                        )
                    },
                ),
                json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ),
            photoContextAnalyzer = org.walktalkmeditate.pilgrim.core.prompt.PhotoContextAnalyzer(
                dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    produceFile = {
                        java.io.File(
                            ctxApp.cacheDir,
                            "vmtest-photo-${java.util.UUID.randomUUID()}.preferences_pb",
                        )
                    },
                ),
                json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                bitmapLoader = object : org.walktalkmeditate.pilgrim.data.photo.BitmapLoader {
                    override suspend fun load(uri: android.net.Uri) = null
                },
                imageLabeler = object : org.walktalkmeditate.pilgrim.core.prompt.ImageLabelerClient {
                    override suspend fun label(bitmap: android.graphics.Bitmap) =
                        emptyList<org.walktalkmeditate.pilgrim.core.prompt.LabeledTag>()
                },
                textRecognizer = object : org.walktalkmeditate.pilgrim.core.prompt.TextRecognizerClient {
                    override suspend fun recognize(bitmap: android.graphics.Bitmap) = emptyList<String>()
                },
                faceDetector = object : org.walktalkmeditate.pilgrim.core.prompt.FaceDetectorClient {
                    override suspend fun detect(bitmap: android.graphics.Bitmap) = 0
                },
            ),
            geocoder = org.walktalkmeditate.pilgrim.core.prompt.PromptGeocoder(ctxApp),
            promptGenerator = org.walktalkmeditate.pilgrim.core.prompt.PromptGenerator(ctxApp),
            practicePreferences = org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(),
            unitsPreferences = org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            appContext = ctxApp,
        ) {
            override suspend fun buildContext(walkId: Long, zone: java.time.ZoneId) = null
            override suspend fun generateAll(walkId: Long, zone: java.time.ZoneId) =
                emptyList<org.walktalkmeditate.pilgrim.core.prompt.GeneratedPrompt>()
        }
    }

    @After
    fun tearDown() {
        // Join BEFORE db.close() (Room observers on a closed db) and
        // before resetMain (the TestMainDispatcher swap race above).
        kotlinx.coroutines.runBlocking {
            for (vm in createdViewModels) {
                vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
        createdViewModels.clear()
        db.close()
        hemisphereScope.coroutineContext[Job]?.cancel()
        persistenceScope.coroutineContext[Job]?.cancel()
        routeCatalogScope.coroutineContext[Job]?.cancel()
        modelStoreScope.coroutineContext[Job]?.cancel()
        modelRoot.deleteRecursively()
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        // cachedShareStore now uses a per-test DataStore file (unique
        // UUID), so the old shared global share_cache.preferences_pb
        // cleanup band-aid is no longer needed — isolation, not a
        // racing delete, is what prevents the Stage 7-A Robolectric +
        // SharingStarted.Eagerly cross-test pollution.
        Dispatchers.resetMain()
    }

    @Test
    fun `NotFound state when walk row is missing`() = runTest(dispatcher) {
        val vm = newViewModel(walkId = 999L)

        vm.state.test(timeout = 10.seconds) {
            // Might be Loading first, then NotFound
            var item = awaitItem()
            if (item is WalkSummaryUiState.Loading) item = awaitItem()
            assertTrue(item is WalkSummaryUiState.NotFound)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded state reports totalElapsed, distance, and zero paused when no events`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 61_000L)
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 1_100L, latitude = 0.0, longitude = 0.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 60_900L, latitude = 0.0, longitude = 0.001),
        )

        val vm = newViewModel(walkId = walk.id)

        vm.state.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val loaded = item as WalkSummaryUiState.Loaded
            val s = loaded.summary
            assertEquals(60_000L, s.totalElapsedMillis)
            assertEquals(60_000L, s.activeWalkingMillis)
            assertEquals(0L, s.totalPausedMillis)
            assertEquals(0L, s.totalMeditatedMillis)
            // ~111 m for 0.001 degree at equator.
            assertEquals(111.0, s.distanceMeters, 1.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `paused and meditation pairs subtract correctly from activeWalking`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        // 10-second pause mid-walk
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 10_000L, eventType = WalkEventType.PAUSED))
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 20_000L, eventType = WalkEventType.RESUMED))
        // 5-second meditation later
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 40_000L, eventType = WalkEventType.MEDITATION_START))
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 45_000L, eventType = WalkEventType.MEDITATION_END))

        val vm = newViewModel(walkId = walk.id)

        vm.state.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val s = (item as WalkSummaryUiState.Loaded).summary
            assertEquals(60_000L, s.totalElapsedMillis)
            assertEquals(10_000L, s.totalPausedMillis)
            assertEquals(5_000L, s.totalMeditatedMillis)
            // activeWalking = 60 - 10 - 5 = 45 seconds
            assertEquals(45_000L, s.activeWalkingMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `walk finished from Paused state — dangling PAUSED counted via closeAt`() = runTest(dispatcher) {
        // Regression test for the "dangling pause inflates activeWalking" bug.
        // User paused at t=30s, finished at t=60s (reducer writes no synthetic RESUMED).
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 30_000L, eventType = WalkEventType.PAUSED))

        val vm = newViewModel(walkId = walk.id)

        vm.state.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val s = (item as WalkSummaryUiState.Loaded).summary
            assertEquals(60_000L, s.totalElapsedMillis)
            // Pause from 30s → walk end (60s) = 30 seconds paused
            assertEquals(30_000L, s.totalPausedMillis)
            // activeWalking = 60 - 30 = 30 seconds (not 60!)
            assertEquals(30_000L, s.activeWalkingMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `walk finished from Meditating state — dangling MEDITATION_START counted via closeAt`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        repository.recordEvent(WalkEvent(walkId = walk.id, timestamp = 40_000L, eventType = WalkEventType.MEDITATION_START))

        val vm = newViewModel(walkId = walk.id)

        vm.state.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val s = (item as WalkSummaryUiState.Loaded).summary
            assertEquals(20_000L, s.totalMeditatedMillis)
            assertEquals(40_000L, s.activeWalkingMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Note: Room Flow observation tests + sweep delegation tests are
    // intentionally omitted from the VM layer. observeVoiceRecordings
    // is exhaustively covered by VoiceRecordingDataLayerTest, and the
    // sweeper's behavior is covered by OrphanRecordingSweeperTest. The
    // VM tests below verify only the public delegation API surface
    // (no runTest needed — the calls are synchronous on a fake).

    @Test
    fun `playRecording delegates to the controller`() {
        val walk = kotlinx.coroutines.runBlocking { repository.startWalk(startTimestamp = 0L) }
        val recording = insertSimpleRecording(walk.id)
        val vm = newViewModel(walkId = walk.id)

        vm.playRecording(recording)

        assertEquals(listOf(recording.id), playback.playCalls)
    }

    @Test
    fun `pausePlayback delegates to the controller`() {
        val walk = kotlinx.coroutines.runBlocking { repository.startWalk(startTimestamp = 0L) }
        val recording = insertSimpleRecording(walk.id)
        val vm = newViewModel(walkId = walk.id)

        vm.playRecording(recording)
        vm.pausePlayback()

        assertEquals(1, playback.pauseCalls.get())
    }

    @Test
    fun `stopPlayback delegates to the controller`() {
        val vm = newViewModel(walkId = 1L)

        vm.stopPlayback()

        assertEquals(1, playback.stopCalls.get())
    }

    @Test
    fun `onCleared stops playback without releasing the singleton`() {
        val walk = kotlinx.coroutines.runBlocking { repository.startWalk(startTimestamp = 0L) }
        val vm = newViewModel(walkId = walk.id)

        val store = androidx.lifecycle.ViewModelStore()
        store.put("vm", vm)
        store.clear()

        // Stop, NOT release — the @Singleton VoicePlaybackController
        // outlives the ViewModel and must remain ready for the next
        // walk-summary screen.
        assertEquals(1, playback.stopCalls.get())
        assertEquals(0, playback.releaseCalls.get())
    }

    private fun insertSimpleRecording(
        walkId: Long,
        transcription: String? = null,
        fileRelativePath: String? = null,
    ): VoiceRecording {
        val walk = kotlinx.coroutines.runBlocking { repository.getWalk(walkId)!! }
        val start = nextTimestamp.getAndAdd(60_000L)
        val recording = VoiceRecording(
            walkId = walkId,
            startTimestamp = start,
            endTimestamp = start + 5_000L,
            durationMillis = 5_000L,
            fileRelativePath = fileRelativePath ?: "recordings/${walk.uuid}/rec-$start.wav",
            transcription = transcription,
        )
        val id = kotlinx.coroutines.runBlocking { repository.recordVoice(recording) }
        return recording.copy(id = id)
    }

    private val nextTimestamp = java.util.concurrent.atomic.AtomicLong(1_000_000L)

    // --- Stage 4-B: goshuin seal reveal plumbing ---------------------

    @Test
    fun `Loaded state carries sealSpec with walk uuid and raw seed fields`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 5_100_000L, latitude = 0.0, longitude = 0.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 5_200_000L, latitude = 0.0, longitude = 0.001),
        )
        repository.finishWalk(walk, endTimestamp = 5_600_000L)

        val vm = newViewModel(walkId = walk.id)

        vm.state.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val loaded = item as WalkSummaryUiState.Loaded
            val spec = loaded.summary.sealSpec
            assertEquals(walk.uuid, spec.uuid)
            assertEquals(walk.startTimestamp, spec.startMillis)
            assertTrue("distanceMeters=${spec.distanceMeters}", spec.distanceMeters > 0.0)
            assertTrue("displayDistance should be non-empty", spec.displayDistance.isNotEmpty())
            assertTrue("unitLabel ${spec.unitLabel} should be m or km", spec.unitLabel in setOf("m", "km"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sealSpec hemisphere comes from the walk's first route coordinate`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 5_100_000L, latitude = -33.8688, longitude = 151.2093),
        )
        repository.finishWalk(walk, endTimestamp = 5_600_000L)

        val vm = newViewModel(walkId = walk.id)
        vm.state.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            // Southern route point → southern seal hemisphere, regardless of
            // the device hemisphere (iOS keys the seal off routePoints.first).
            assertTrue((item as WalkSummaryUiState.Loaded).summary.sealSpec.southernHemisphere)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded state carries a LightReading computed from first location`() = runTest(dispatcher) {
        // Stage 6-B: the VM wraps LightReading.from in runCatching, so a
        // regression that breaks the factory would silently set
        // lightReading = null. Assert non-null on a walk with a real
        // GPS sample — sun should also populate since we have lat/lon.
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 5_100_000L, latitude = 48.8566, longitude = 2.3522),
        )
        repository.finishWalk(walk, endTimestamp = 5_600_000L)

        val vm = newViewModel(walkId = walk.id)

        vm.state.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val loaded = item as WalkSummaryUiState.Loaded
            val reading = loaded.summary.lightReading
            assertNotNull("LightReading should be computed for a walk with GPS samples", reading)
            assertNotNull("moon should be populated", reading!!.moon)
            assertNotNull("sun should be populated when location is present", reading.sun)
            assertNotNull("planetaryHour should be populated", reading.planetaryHour)
            assertTrue("koan text should be non-blank", reading.koan.text.isNotBlank())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lightReadingDisplay is NOT gated on celestialAwarenessEnabled (BUG 7)`() = runTest(dispatcher) {
        // iOS parity `WalkSummaryView.swift:86,129-131@v1.6.0` — Light
        // Reading is shown when `lightReading != nil &&
        // hasRevealedLightReading`. It is NOT gated on
        // `celestialAwarenessEnabled` (only the celestial snapshot row
        // + milestone seasonal branch are). The previous AND-gate on
        // that pref (default OFF) made the card never reveal even after
        // the walk was shared — the manual-QA BUG-7 finding. With the
        // pref OFF the display flow must still emit the reading.
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 5_100_000L, latitude = 48.8566, longitude = 2.3522),
        )
        repository.finishWalk(walk, endTimestamp = 5_600_000L)

        val vm = newViewModel(
            walkId = walk.id,
            practicePreferences = org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(
                initialCelestialAwarenessEnabled = false,
            ),
        )

        vm.lightReadingDisplay.test(timeout = 10.seconds) {
            // Seeds with null (initialValue), then emits the reading
            // once the state resolves to Loaded — pref is OFF but the
            // reading is no longer suppressed.
            var item = awaitItem()
            while (item == null) item = awaitItem()
            assertNotNull(
                "Light Reading must be exposed regardless of celestialAwarenessEnabled",
                item,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Light Reading card visible after markCurrentWalkShared with celestial pref OFF (BUG 7)`() = runTest(dispatcher) {
        // The screen gates the card on `hasRevealedLightReading &&
        // lightReadingDisplay != null` (iOS-parity). With the celestial
        // pref OFF, sharing the walk must flip the reveal latch AND
        // leave the reading non-null so the card actually appears.
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 5_100_000L, latitude = 48.8566, longitude = 2.3522),
        )
        repository.finishWalk(walk, endTimestamp = 5_600_000L)

        val vm = newViewModel(
            walkId = walk.id,
            practicePreferences = org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(
                initialCelestialAwarenessEnabled = false,
            ),
        )

        // Reading is exposed even with the pref OFF. Collect via
        // Turbine (active subscription) — reading `.value` does not
        // drive the upstream `state` flow's Loading→Loaded resolution.
        vm.lightReadingDisplay.test(timeout = 10.seconds) {
            var reading = awaitItem()
            while (reading == null) reading = awaitItem()
            assertNotNull("Light Reading must be non-null with celestial pref OFF", reading)
            cancelAndIgnoreRemainingEvents()
        }

        vm.hasRevealedLightReading.test {
            assertEquals(false, awaitItem())
            vm.markCurrentWalkShared()
            advanceUntilIdle()
            assertEquals(
                "Sharing the walk must reveal the Light Reading card even with celestial pref OFF",
                true,
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hemisphere StateFlow proxies the repository`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.finishWalk(walk, endTimestamp = 5_600_000L)
        val vm = newViewModel(walkId = walk.id)
        assertEquals(Hemisphere.Northern, vm.hemisphere.value)
        hemisphereRepo.setOverride(Hemisphere.Southern)
        // Bridge to real-dispatcher time since the repo's StateFlow
        // collects on Dispatchers.Default, not the runTest virtual clock.
        val observed = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                vm.hemisphere.first { it == Hemisphere.Southern }
            }
        }
        assertEquals(Hemisphere.Southern, observed)
    }

    // --- Stage 7-A: photo reliquary ----------------------------------

    @Test
    fun `pinPhotos with empty list is a no-op`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val vm = newViewModel(walkId = walk.id)

        vm.pinPhotos(emptyList())

        // No coroutine scheduled → repo count remains zero.
        assertEquals(0, repository.countPhotosFor(walk.id))
    }

    @Test
    fun `pinPhotos writes picked URIs through to the repository`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val vm = newViewModel(walkId = walk.id)

        val uri1 = android.net.Uri.parse("content://media/picker/0/com.example/1")
        val uri2 = android.net.Uri.parse("content://media/picker/0/com.example/2")
        vm.pinPhotos(listOf(uri1, uri2))

        // Bridge virtual-time → Dispatchers.IO for the VM's launch(IO).
        val rows = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                vm.pinnedPhotos.first { it.size == 2 }
            }
        }
        assertEquals(2, rows.size)
        assertEquals(
            setOf(uri1.toString(), uri2.toString()),
            rows.map { it.photoUri }.toSet(),
        )
    }

    @Test
    fun `pinPhotos dedups duplicate URIs within a single batch`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val vm = newViewModel(walkId = walk.id)

        val uri = android.net.Uri.parse("content://media/picker/0/com.example/1")
        vm.pinPhotos(listOf(uri, uri, uri))

        val rows = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                vm.pinnedPhotos.first { it.isNotEmpty() }
            }
        }
        assertEquals(1, rows.size)
        assertEquals(uri.toString(), rows.first().photoUri)
    }

    @Test
    fun `pinPhotos skips URIs already pinned to this walk`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val existing = "content://media/picker/0/com.example/existing"
        repository.pinPhoto(
            walkId = walk.id,
            photoUri = existing,
            takenAt = null,
            pinnedAt = 1_000L,
        )
        val vm = newViewModel(walkId = walk.id)

        // Subscribe to pinnedPhotos so the WhileSubscribed StateFlow
        // actually emits the seed row — the VM's dedup reads
        // pinnedPhotos.value, which stays at initialValue when nothing
        // observes. In production the UI subscribes; in this test we
        // stand in for the UI with a small collector.
        val observer = launch(Dispatchers.Default) {
            vm.pinnedPhotos.collect { }
        }
        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) { vm.pinnedPhotos.first { it.isNotEmpty() } }
        }

        vm.pinPhotos(
            listOf(
                android.net.Uri.parse(existing),
                android.net.Uri.parse("content://media/picker/0/com.example/new"),
            ),
        )

        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (repository.countPhotosFor(walk.id) < 2) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        assertEquals(
            "dedup should have filtered the already-pinned URI",
            2,
            repository.countPhotosFor(walk.id),
        )
        observer.cancel()
    }

    @Test
    fun `pinPhotos schedules photo analysis for the walk after insertion`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val vm = newViewModel(walkId = walk.id)

        vm.pinPhotos(
            listOf(
                android.net.Uri.parse("content://media/picker/0/com.example/1"),
                android.net.Uri.parse("content://media/picker/0/com.example/2"),
            ),
        )
        // Give the IO launch a beat to hit the scheduler.
        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (photoAnalysisScheduler.scheduleForWalkCalls.isEmpty()) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }

        assertEquals(listOf(walk.id), photoAnalysisScheduler.scheduleForWalkCalls)
    }

    @Test
    fun `pinPhotos with empty list does not schedule analysis`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val vm = newViewModel(walkId = walk.id)

        vm.pinPhotos(emptyList())

        assertTrue(photoAnalysisScheduler.scheduleForWalkCalls.isEmpty())
    }

    @Test
    fun `runStartupSweep schedules photo analysis for the walk`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val vm = newViewModel(walkId = walk.id)

        vm.runStartupSweep()

        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (photoAnalysisScheduler.scheduleForWalkCalls.isEmpty()) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        assertTrue(walk.id in photoAnalysisScheduler.scheduleForWalkCalls)
    }

    @Test
    fun `unpinPhoto removes the pinned row`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val id = repository.pinPhoto(
            walkId = walk.id,
            photoUri = "content://media/picker/0/com.example/1",
            takenAt = null,
            pinnedAt = 2_000L,
        )
        val vm = newViewModel(walkId = walk.id)
        val initial = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) { vm.pinnedPhotos.first { it.size == 1 } }
        }
        assertEquals(1, initial.size)

        vm.unpinPhoto(initial.first().copy(id = id))

        val after = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) { vm.pinnedPhotos.first { it.isEmpty() } }
        }
        assertTrue(after.isEmpty())
    }

    // --- Stage 4-D: milestone propagation ----------------------------

    @Test
    fun `Loaded state carries FirstWalk milestone for the only finished walk`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.finishWalk(walk, endTimestamp = 5_600_000L)

        val vm = newViewModel(walkId = walk.id)
        vm.state.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is WalkSummaryUiState.Loading) item = awaitItem()
            val loaded = item as WalkSummaryUiState.Loaded
            assertEquals(GoshuinMilestone.FirstWalk, loaded.summary.milestone)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Stage 7-D: share + save events ------------------------------

    private fun fixtureEtegamiSpec(walkUuid: String = "test-walk-uuid"): org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSpec {
        val seal = org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec(
            uuid = walkUuid,
            startMillis = 1_700_000_000_000L,
            distanceMeters = 1_000.0,
            durationSeconds = 600.0,
            displayDistance = "1.0",
            unitLabel = "km",
            ink = androidx.compose.ui.graphics.Color.Black,
        )
        return org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSpec(
            walkUuid = walkUuid,
            startedAtEpochMs = 1_700_000_000_000L,
            hourOfDay = 10,
            routePoints = listOf(
                org.walktalkmeditate.pilgrim.domain.LocationPoint(
                    timestamp = 1_700_000_000_000L, latitude = 45.0, longitude = -70.0,
                ),
                org.walktalkmeditate.pilgrim.domain.LocationPoint(
                    timestamp = 1_700_000_060_000L, latitude = 45.0001, longitude = -70.0001,
                ),
            ),
            sealSpec = seal,
            moonPhase = null,
            distanceMeters = 1_000.0,
            durationMillis = 600_000L,
            elevationGainMeters = 0.0,
            topText = null,
            activityMarkers = emptyList(),
            units = org.walktalkmeditate.pilgrim.data.units.UnitSystem.Metric,
        )
    }

    @Test
    fun `shareEtegami emits DispatchShare with an image-png chooser Intent`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.finishWalk(walk, endTimestamp = 5_600_000L)
        val vm = newViewModel(walkId = walk.id)

        vm.etegamiEvents.test(timeout = 10.seconds) {
            vm.shareEtegami(fixtureEtegamiSpec(walk.uuid))
            val ev = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
                withTimeout(10_000L) { awaitItem() }
            }
            assertTrue(
                "expected DispatchShare, got $ev",
                ev is WalkSummaryViewModel.EtegamiShareEvent.DispatchShare,
            )
            val chooser = (ev as WalkSummaryViewModel.EtegamiShareEvent.DispatchShare).chooser
            assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
            val inner = chooser.getParcelableExtra<android.content.Intent>(
                android.content.Intent.EXTRA_INTENT,
            )!!
            assertEquals("image/png", inner.type)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `etegamiBusy tracks the in-flight action and resets to null on completion`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.finishWalk(walk, endTimestamp = 5_600_000L)
        val vm = newViewModel(walkId = walk.id)

        // Before any action: null.
        assertNull(vm.etegamiBusy.value)

        // Fire save. The VM's inner `finally { bitmap.recycle() }` and
        // outer `finally { _etegamiBusy.value = null; mutex.unlock() }`
        // both run AFTER the event is emitted — and all three live on
        // `Dispatchers.Default`, not the test dispatcher. Reading
        // `etegamiBusy.value` immediately after awaiting the event
        // races the finally blocks. Instead, await the StateFlow
        // predicate explicitly so we're observing actual completion,
        // not a race-window snapshot.
        //
        // 30s real-time timeout because the save runs on a real
        // `Dispatchers.Default` (escaping the runTest virtual clock),
        // and a saturated GitHub Actions runner can take >10s to
        // schedule the finally block. Memory entry "CI real-time
        // withTimeout flake family" — 10s wasn't enough on the
        // 2026-06-02 main-branch CI run.
        vm.saveEtegamiToGallery(fixtureEtegamiSpec(walk.uuid))
        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(30_000L) { vm.etegamiBusy.first { it == null } }
        }
        assertNull(vm.etegamiBusy.value)
    }

    @Test
    fun `notifyEtegamiSaveNeedsPermission emits SaveNeedsPermission without rendering`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.finishWalk(walk, endTimestamp = 5_600_000L)
        val vm = newViewModel(walkId = walk.id)

        vm.etegamiEvents.test(timeout = 10.seconds) {
            vm.notifyEtegamiSaveNeedsPermission()
            assertEquals(
                WalkSummaryViewModel.EtegamiShareEvent.SaveNeedsPermission,
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Stage 13-A: hero stats (talkMillis, activeMillis, ascendMeters) ---

    @Test
    fun talkMillis_sumsVoiceRecordingDurations() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertVoiceRecording(walkId, startOffset = 1_000L, durationMillis = 5_000L)
        insertVoiceRecording(walkId, startOffset = 10_000L, durationMillis = 3_000L)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(8_000L, loaded.summary.talkMillis)
    }

    @Test
    fun ascendMeters_sumsPositiveAltitudeDeltas() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertAltitude(walkId, 1_000L, 100.0)
        insertAltitude(walkId, 2_000L, 110.0)
        insertAltitude(walkId, 3_000L, 105.0)
        insertAltitude(walkId, 4_000L, 120.0)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(25.0, loaded.summary.ascendMeters, 0.0001)
    }

    @Test
    fun ascendMeters_zeroForFlatRoute() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertAltitude(walkId, 1_000L, 100.0)
        insertAltitude(walkId, 2_000L, 100.0)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(0.0, loaded.summary.ascendMeters, 0.0001)
    }

    @Test
    fun activeMillis_excludesPausedTime_includesMeditation() = runTest(dispatcher) {
        val walkId = createFinishedWalk(
            durationMillis = 60_000L,
            events = listOf(
                // 10s paused
                WalkEvent(walkId = 0L, timestamp = 5_000L, eventType = WalkEventType.PAUSED),
                WalkEvent(walkId = 0L, timestamp = 15_000L, eventType = WalkEventType.RESUMED),
                // 10s meditating
                WalkEvent(walkId = 0L, timestamp = 30_000L, eventType = WalkEventType.MEDITATION_START),
                WalkEvent(walkId = 0L, timestamp = 40_000L, eventType = WalkEventType.MEDITATION_END),
            ),
        )

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        // 60s total - 10s pause = 50s active (meditation included)
        assertEquals(50_000L, loaded.summary.activeMillis)
    }

    // --- Stage 13-B: routeSegments classification ---------------------

    @Test
    fun routeSegments_classifiesWalkOnlyAsSingleSegment() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertRouteSample(walkId, t = 1_000L, lat = 1.0, lng = 1.0)
        insertRouteSample(walkId, t = 5_000L, lat = 2.0, lng = 2.0)
        insertRouteSample(walkId, t = 10_000L, lat = 3.0, lng = 3.0)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(1, loaded.summary.routeSegments.size)
        assertEquals(RouteActivity.Walking, loaded.summary.routeSegments[0].activity)
    }

    @Test
    fun routeSegments_splitsAtMeditationBoundaries() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertRouteSample(walkId, t = 1_000L, lat = 1.0, lng = 1.0)
        insertRouteSample(walkId, t = 20_000L, lat = 2.0, lng = 2.0)
        insertRouteSample(walkId, t = 40_000L, lat = 3.0, lng = 3.0)
        insertMeditationEvents(walkId, startTimestamp = 15_000L, endTimestamp = 25_000L)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(3, loaded.summary.routeSegments.size)
        assertEquals(RouteActivity.Walking, loaded.summary.routeSegments[0].activity)
        assertEquals(RouteActivity.Meditating, loaded.summary.routeSegments[1].activity)
        assertEquals(RouteActivity.Walking, loaded.summary.routeSegments[2].activity)
    }

    @Test
    fun routeSegments_splitsAtVoiceRecordingBoundaries() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertRouteSample(walkId, t = 1_000L, lat = 1.0, lng = 1.0)
        insertRouteSample(walkId, t = 20_000L, lat = 2.0, lng = 2.0)
        insertRouteSample(walkId, t = 40_000L, lat = 3.0, lng = 3.0)
        insertVoiceRecording(walkId, startOffset = 15_000L, durationMillis = 10_000L)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(3, loaded.summary.routeSegments.size)
        assertEquals(RouteActivity.Walking, loaded.summary.routeSegments[0].activity)
        assertEquals(RouteActivity.Talking, loaded.summary.routeSegments[1].activity)
    }

    @Test
    fun routeSegments_meditationOverridesTalking() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertRouteSample(walkId, t = 10_000L, lat = 1.0, lng = 1.0)
        insertRouteSample(walkId, t = 20_000L, lat = 2.0, lng = 2.0)
        insertMeditationEvents(walkId, startTimestamp = 5_000L, endTimestamp = 25_000L)
        insertVoiceRecording(walkId, startOffset = 10_000L, durationMillis = 10_000L)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(1, loaded.summary.routeSegments.size)
        assertEquals(RouteActivity.Meditating, loaded.summary.routeSegments[0].activity)
    }

    @Test
    fun voiceRecordings_populatedFromRepo() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertVoiceRecording(walkId, startOffset = 1_000L, durationMillis = 5_000L)
        insertVoiceRecording(walkId, startOffset = 10_000L, durationMillis = 3_000L)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(2, loaded.summary.voiceRecordings.size)
    }

    @Test
    fun meditationIntervals_derivedFromEvents_excludesUnrelatedEventTypes() = runTest(dispatcher) {
        // meditationIntervals is reconstructed from MEDITATION_START/END
        // pairs in the walk's event log (activity_intervals has no
        // production writer — see deriveActivityIntervals). A PAUSED/
        // RESUMED pair in the same log must not leak in as a meditation
        // interval.
        val walkId = createFinishedWalk(
            durationMillis = 60_000L,
            events = listOf(
                WalkEvent(walkId = 0L, timestamp = 5_000L, eventType = WalkEventType.MEDITATION_START),
                WalkEvent(walkId = 0L, timestamp = 15_000L, eventType = WalkEventType.MEDITATION_END),
                WalkEvent(walkId = 0L, timestamp = 20_000L, eventType = WalkEventType.PAUSED),
                WalkEvent(walkId = 0L, timestamp = 30_000L, eventType = WalkEventType.RESUMED),
            ),
        )

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(1, loaded.summary.meditationIntervals.size)
        assertEquals(ActivityType.MEDITATING, loaded.summary.meditationIntervals[0].activityType)
    }

    /**
     * User product decision 2026-08-18: voice-recording pins are gone
     * from the Walk Summary map entirely, even though this walk HAS a
     * voice recording ([insertVoiceRecording] below) that would have
     * produced one before the change (formerly
     * `walkAnnotations_populated_includesStartEndMeditationVoice`,
     * asserting a 4th `WalkMapAnnotationKind.VoiceRecording` entry).
     * `computeWalkMapAnnotations` no longer has a `voiceRecordings`
     * parameter at all (see its doc comment), so there is no
     * `WalkMapAnnotationKind.VoiceRecording` type left to check against
     * — the annotation count staying at 3 despite a recording being
     * present is the proof that none reaches the map from this
     * pipeline.
     */
    @Test
    fun walkAnnotations_populated_excludesVoiceRecording() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertRouteSample(walkId, t = 1_000L, lat = 1.0, lng = 1.0)
        insertRouteSample(walkId, t = 30_000L, lat = 2.0, lng = 2.0)
        insertRouteSample(walkId, t = 60_000L, lat = 3.0, lng = 3.0)
        insertMeditationEvents(walkId, startTimestamp = 28_000L, endTimestamp = 32_000L)
        insertVoiceRecording(walkId, startOffset = 55_000L, durationMillis = 5_000L)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        val annotations = loaded.summary.walkAnnotations
        assertEquals(3, annotations.size)
        assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.StartPoint })
        assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.EndPoint })
        assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.Meditation })
    }

    // --- Stage 13-EFG: altitudeSamples + selectedFavicon -----------------

    @Test
    fun setFavicon_persistsAndUpdatesFlow() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        val vm = newViewModel(walkId)
        awaitLoaded(vm)
        assertNull(vm.selectedFavicon.value)

        vm.setFavicon(WalkFavicon.LEAF)
        advanceUntilIdle()

        assertEquals(WalkFavicon.LEAF, vm.selectedFavicon.value)
        // The DAO write runs on Dispatchers.IO (real thread, not the
        // virtual-time test dispatcher) — advanceUntilIdle alone won't
        // wait for it. Bridge to wall-clock the same way the
        // pinPhotos suite does.
        val persisted = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                var w = repository.getWalk(walkId)
                while (w?.favicon == null) {
                    kotlinx.coroutines.delay(10)
                    w = repository.getWalk(walkId)
                }
                w
            }
        }
        assertEquals("leaf", persisted.favicon)

        // Tap same → deselects
        vm.setFavicon(WalkFavicon.LEAF)
        advanceUntilIdle()

        assertNull(vm.selectedFavicon.value)
        val persistedNull = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                var w = repository.getWalk(walkId)
                while (w?.favicon != null) {
                    kotlinx.coroutines.delay(10)
                    w = repository.getWalk(walkId)
                }
                w
            }
        }
        assertNull(persistedNull?.favicon)
    }

    @Test
    fun setFavicon_persistsAfterViewModelCleared() = runTest(dispatcher) {
        // Regression: setFavicon must run on persistenceScope (process
        // lifetime), NOT viewModelScope. Otherwise a tap-then-back-nav
        // sequence cancels the in-flight DAO call and the user's
        // selection is lost on reload. iOS uses CoreStore's background
        // queue for the same reason.
        //
        // We force the test to actually distinguish viewModelScope from
        // persistenceScope by gating the DAO call on a CompletableDeferred.
        // Without the gate, UnconfinedTestDispatcher inlines the DAO call
        // before the test can issue cancel — the assertion would pass
        // under either implementation (the trap the closing reviewer
        // caught). With the gate: the launch suspends past the cancel
        // point, so cancellation actually decides whether the write
        // lands.
        val gate = CompletableDeferred<Unit>()
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        val gatingDao = object : org.walktalkmeditate.pilgrim.data.dao.WalkDao by db.walkDao() {
            override suspend fun updateFavicon(walkId: Long, favicon: String?) {
                gate.await()
                db.walkDao().updateFavicon(walkId, favicon)
            }
            // Explicit forward — Kotlin `by` interface delegation generates
            // forwarders at compile time, which can race Room's KSP
            // regeneration of new query methods. Forwarding manually avoids
            // a class-load hang seen on incremental rebuilds.
            override suspend fun getRecentFinishedBefore(currentStart: Long, limit: Int): List<org.walktalkmeditate.pilgrim.data.entity.Walk> =
                db.walkDao().getRecentFinishedBefore(currentStart, limit)
        }
        val gatingRepo = WalkRepository(
            database = db,
            walkDao = gatingDao,
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
        val vm = newViewModel(walkId, repositoryOverride = gatingRepo)
        awaitLoaded(vm)

        vm.setFavicon(WalkFavicon.STAR)
        // Launch is now suspended at gate.await(). Cancel viewModelScope.
        vm.viewModelScope.coroutineContext[Job]?.cancel()
        runCurrent()
        // Release the gate. If the launch was on viewModelScope, it has
        // already been cancelled — gate.await() throws CancellationException
        // and the DAO call never runs. If on persistenceScope, the launch
        // is still alive — it proceeds past the gate and writes.
        gate.complete(Unit)

        val persisted = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                var w = repository.getWalk(walkId)
                while (w?.favicon == null) {
                    kotlinx.coroutines.delay(10)
                    w = repository.getWalk(walkId)
                }
                w
            }
        }
        assertEquals("star", persisted.favicon)
    }

    @Test
    fun altitudeSamples_populatedFromRepo() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertAltitude(walkId, 1_000L, 100.0)
        insertAltitude(walkId, 2_000L, 110.0)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(2, loaded.summary.altitudeSamples.size)
    }

    // --- Stage 13-Cel: celestial snapshot + callout integration -------

    @Test
    fun celestialSnapshot_populated_in_buildState() = runTest(dispatcher) {
        // Snapshot is unconditionally computed at build time (cheap,
        // deterministic from walk.startTimestamp). The display gating
        // happens at the screen level via celestialSnapshotDisplay,
        // not by skipping computation here.
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertNotNull(loaded.summary.celestialSnapshot)
        // Seven classical planets — covers the full Chaldean set used
        // by PlanetaryHourCalc + the celestial line UI.
        assertEquals(7, loaded.summary.celestialSnapshot!!.positions.size)
    }

    @Test
    fun celestialSnapshotDisplay_null_when_pref_off() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        val vm = newViewModel(
            walkId = walkId,
            practicePreferences = org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(
                initialCelestialAwarenessEnabled = false,
            ),
        )
        awaitLoaded(vm)

        assertNull(vm.celestialSnapshotDisplay.value)
    }

    @Test
    fun celestialSnapshotDisplay_nonNull_when_pref_on() = runTest(dispatcher) {
        // newViewModel default flips celestialAwarenessEnabled = true
        // (Stage 10-C convenience for the legacy lightReading suite).
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        val vm = newViewModel(walkId)
        awaitLoaded(vm)

        // WhileSubscribed flow + .value read: subscribe a small
        // collector so the upstream actually starts emitting, then
        // wait for a non-null snapshot.
        val snap = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                vm.celestialSnapshotDisplay.first { it != null }
            }
        }
        assertNotNull(snap)
    }

    @Test
    fun walkSummaryCalloutProseDisplay_null_when_no_chain_applies() = runTest(dispatcher) {
        // Single finished walk, default fixture: no past walks (so
        // both Long*Improvement gates fail — they require nonzero
        // priors), no SeasonalMarker (depends on date/sun longitude;
        // with current=startTimestamp 0L the marker is unlikely to
        // hit), and TotalDistance threshold isn't crossed by a single
        // ~zero-distance walk. Chain returns null prose.
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        val vm = newViewModel(walkId)
        awaitLoaded(vm)

        // WhileSubscribed: subscribe so the combine actually fires.
        // Tolerate both initial null and post-collect null — the
        // expectation is "no prose ever materializes."
        val prose = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                // Drain: subscribe then read .value once the upstream
                // has had a chance to settle.
                val job = launch { vm.walkSummaryCalloutProseDisplay.collect { } }
                kotlinx.coroutines.delay(50)
                val v = vm.walkSummaryCalloutProseDisplay.value
                job.cancel()
                v
            }
        }
        assertNull(prose)
    }

    @Test
    fun longestMeditation_callout_fires_on_strict_improvement() = runTest(dispatcher) {
        // Prior walk with 300s persisted meditation column; current
        // walk with 600s of MEDITATION_START/END events (live event-
        // replay total feeds the callout for the current walk —
        // Walk.meditationSeconds may not be populated yet for a
        // freshly-finished walk; see WalkSummaryViewModel comment on
        // currentMeditationSeconds). Strict-improvement-over-nonzero
        // gate fires → chain returns LongestMeditation prose.
        val priorWalk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(priorWalk, endTimestamp = 60_000L)
        db.walkDao().updateAggregates(
            id = priorWalk.id,
            distanceMeters = 0.0,
            meditationSeconds = 300L,
        )

        val currentWalk = repository.startWalk(startTimestamp = 100_000L)
        // 600s = 600_000ms of meditation, between t=100_000 and
        // t=700_000 wall-clock (relative to walk start). End the walk
        // after the meditation window closes.
        repository.recordEvent(
            WalkEvent(
                walkId = currentWalk.id,
                timestamp = 100_000L,
                eventType = WalkEventType.MEDITATION_START,
            ),
        )
        repository.recordEvent(
            WalkEvent(
                walkId = currentWalk.id,
                timestamp = 700_000L,
                eventType = WalkEventType.MEDITATION_END,
            ),
        )
        repository.finishWalk(currentWalk, endTimestamp = 760_000L)

        val vm = newViewModel(currentWalk.id)
        awaitLoaded(vm)

        val prose = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                vm.walkSummaryCalloutProseDisplay.first { it != null }
            }
        }
        assertEquals(
            context.getString(
                org.walktalkmeditate.pilgrim.R.string.summary_milestone_longest_meditation,
            ),
            prose,
        )
    }

    // --- U11: seek summary + seeking-seal milestone -------------------

    private suspend fun insertArrivalWaypoint(
        walkId: Long,
        timestamp: Long,
        ordinal: Int,
        lat: Double = 0.0,
        lng: Double = 0.0,
    ) {
        repository.addWaypoint(
            org.walktalkmeditate.pilgrim.data.entity.Waypoint(
                walkId = walkId,
                timestamp = timestamp,
                latitude = lat,
                longitude = lng,
                label = org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
                    .arrivalWaypointLabel(context.resources, ordinal),
                icon = org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
                    .ARRIVAL_WAYPOINT_ICON,
            ),
        )
    }

    @Test
    fun `seek summary populated with provenance from the SEEK_MODE event`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L, intention = "find calm")
        repository.recordEvent(
            WalkEvent(walkId = walk.id, timestamp = 1_000L, eventType = WalkEventType.SEEK_MODE),
        )
        repository.recordEvent(
            WalkEvent(walkId = walk.id, timestamp = 30_000L, eventType = WalkEventType.SEEK_ARRIVAL),
        )
        insertArrivalWaypoint(walk.id, timestamp = 30_000L, ordinal = 1)
        insertArrivalWaypoint(walk.id, timestamp = 50_000L, ordinal = 2, lat = 0.01)
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 1_000L, latitude = 0.0, longitude = 0.0),
        )
        repository.finishWalk(walk, endTimestamp = 60_000L)

        val vm = newViewModel(walkId = walk.id)
        val loaded = awaitLoaded(vm)

        val seek = loaded.summary.seekSummary
        assertNotNull("seeded walk with arrivals must carry the seek story", seek)
        assertEquals(2, seek!!.groups.size)
        assertEquals(listOf("First clearing", "Second clearing"), seek.groups.map { it.label })
        assertEquals(1_000L, seek.seededAtEpochMs)
        assertTrue(seek.intentionWasVoiced)
        // Arrival waypoints render as hour-lit halos, not pins, on the
        // summary map (iOS WalkSummaryView.swift:693-710@c1745e8).
        assertEquals(
            2,
            loaded.summary.walkAnnotations.count { it.kind is WalkMapAnnotationKind.SeekArrival },
        )
        assertTrue(
            loaded.summary.walkAnnotations.none {
                (it.kind as? WalkMapAnnotationKind.Waypoint)?.iconKey ==
                    org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence.ARRIVAL_WAYPOINT_ICON
            },
        )
    }

    @Test
    fun `wander walk has null seekSummary`() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertNull(loaded.summary.seekSummary)
    }

    @Test
    fun `zero-arrival seek walk has null seekSummary`() = runTest(dispatcher) {
        val walkId = createFinishedWalk(
            durationMillis = 60_000L,
            events = listOf(
                WalkEvent(walkId = 0L, timestamp = 1_000L, eventType = WalkEventType.SEEK_MODE),
            ),
        )

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertNull(
            "zero-arrival seeks render the standard summary",
            loaded.summary.seekSummary,
        )
    }

    @Test
    fun `seeded walk with two arrivals carries a seeking-seal milestone`() = runTest(dispatcher) {
        // U12 handoff: foundPlaceCount must reach detectMilestoneFor —
        // with the default 0, seeking seals never caption on the summary
        // reveal. Prior wander walk keeps FirstWalk (displayPriority 0)
        // off the seek walk, so FirstUnknown surfaces as primary.
        val prior = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(prior, endTimestamp = 60_000L)

        val seekWalk = repository.startWalk(startTimestamp = 100_000L)
        repository.recordEvent(
            WalkEvent(walkId = seekWalk.id, timestamp = 100_000L, eventType = WalkEventType.SEEK_MODE),
        )
        insertArrivalWaypoint(seekWalk.id, timestamp = 130_000L, ordinal = 1)
        insertArrivalWaypoint(seekWalk.id, timestamp = 150_000L, ordinal = 2, lat = 0.01)
        repository.finishWalk(seekWalk, endTimestamp = 200_000L)

        val vm = newViewModel(walkId = seekWalk.id)
        val loaded = awaitLoaded(vm)

        assertEquals(GoshuinMilestone.FirstUnknown, loaded.summary.milestone)
    }

    // --- U6: walk-summary collective line ------------------------------
    // Parity spec docs/parity/2026-07-23-port-collective-trail-u6.md.
    // The gate + date-anchor semantics are pinned fixture-level in
    // CollectiveTrailSectionTest; these pin the VM plumbing iOS has no
    // ViewModel for (resolve in the parent, walk-row anchor, no clock).

    private suspend fun createContributableWalk(
        startTimestamp: Long,
        endTimestamp: Long = startTimestamp + 3_600_000L,
        latitudeSpan: Double = 0.001,
    ): org.walktalkmeditate.pilgrim.data.entity.Walk {
        val walk = repository.startWalk(startTimestamp = startTimestamp)
        repository.recordLocation(
            RouteDataSample(
                walkId = walk.id,
                timestamp = startTimestamp + 1_000L,
                latitude = 0.0,
                longitude = 0.0,
            ),
        )
        repository.recordLocation(
            RouteDataSample(
                walkId = walk.id,
                timestamp = startTimestamp + 2_000L,
                latitude = latitudeSpan,
                longitude = 0.0,
            ),
        )
        repository.finishWalk(walk, endTimestamp = endTimestamp)
        return walk
    }

    private suspend fun awaitCollectiveLine(vm: WalkSummaryViewModel): String {
        return withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                vm.collectiveContributionLine.first { it != null }!!
            }
        }
    }

    @Test
    fun `collective line resolves a contributed walk against its start days entry`() = runTest(dispatcher) {
        val start = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
        val walk = createContributableWalk(start)
        contributionLedger.record(walk.uuid)
        routeCatalogService.initialLoad.await()

        val vm = newViewModel(walkId = walk.id)
        val loaded = awaitLoaded(vm)
        val line = awaitCollectiveLine(vm)

        val catalog = routeCatalogService.catalog.value
        val walkKm = loaded.summary.distanceMeters / 1_000.0
        // The bundled artifact agrees with the parity fixture on ids
        // (CollectiveRouteBundledArtifactTest), so the fixture's pinned
        // day holds here too.
        assertEquals("camino-primitivo", catalog.entry(start)?.id)
        assertEquals(
            catalog.contributionLine(start, walkKm, UnitSystem.Metric),
            line,
        )
        // Reopened weeks later it says the same thing: the resolve
        // carries no clock, so another day's entry is never consulted.
        val reopened = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli()
        assertNotEquals(
            catalog.contributionLine(reopened, walkKm, UnitSystem.Metric),
            line,
        )
    }

    @Test
    fun `collective line is null for a walk that never contributed`() = runTest(dispatcher) {
        val start = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
        val walk = createContributableWalk(start)
        routeCatalogService.initialLoad.await()

        val vm = newViewModel(walkId = walk.id)
        awaitLoaded(vm)

        vm.walkWasContributed.test(timeout = 10.seconds) {
            assertFalse(awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        vm.collectiveContributionLine.test(timeout = 10.seconds) {
            assertNull(awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `collective line appears when the ledger write lands after the summary opens`() = runTest(dispatcher) {
        // Regression for the finalize race: WalkFinalizationObserver's
        // ledger write is a late step in an async chain, so the
        // auto-opened summary can subscribe first. The reactive
        // derivation must fill the line the moment the claim lands
        // rather than leaving it blank for the visit.
        val start = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
        val walk = createContributableWalk(start)
        routeCatalogService.initialLoad.await()

        val vm = newViewModel(walkId = walk.id)
        val loaded = awaitLoaded(vm)
        val expected = routeCatalogService.catalog.value.contributionLine(
            epochMillis = start,
            walkKm = loaded.summary.distanceMeters / 1_000.0,
            units = UnitSystem.Metric,
        )

        vm.collectiveContributionLine.test(timeout = 10.seconds) {
            assertNull(awaitItem())
            contributionLedger.record(walk.uuid)
            assertEquals(expected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `collective line stays null when the catalog never loads`() = runTest(dispatcher) {
        // A missing bootstrap leaves the service on EMPTY forever —
        // the Android analogue of iOS's forever-nil catalog. The gate
        // must render nothing rather than a partial line.
        val brokenService = bootstrapRouteCatalogService(
            context,
            routeCatalogScope,
            bootstrapAssetPath = "collective/absent-bootstrap.json",
        )
        brokenService.initialLoad.await()
        val start = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
        val walk = createContributableWalk(start)
        contributionLedger.record(walk.uuid)

        val vm = newViewModel(walkId = walk.id, routeCatalogServiceOverride = brokenService)
        awaitLoaded(vm)

        assertTrue(
            withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
                withTimeout(10_000L) { vm.walkWasContributed.first { it } }
            },
        )
        vm.collectiveContributionLine.test(timeout = 10.seconds) {
            assertNull(awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `midnight-spanning walk anchors the collective line to its start day`() = runTest(dispatcher) {
        val start = Instant.parse("2026-10-07T23:30:00Z").toEpochMilli()
        val end = Instant.parse("2026-10-08T00:30:00Z").toEpochMilli()
        val walk = createContributableWalk(start, endTimestamp = end)
        contributionLedger.record(walk.uuid)
        routeCatalogService.initialLoad.await()

        val vm = newViewModel(walkId = walk.id)
        val loaded = awaitLoaded(vm)
        val line = awaitCollectiveLine(vm)

        val catalog = routeCatalogService.catalog.value
        val walkKm = loaded.summary.distanceMeters / 1_000.0
        val startDayNoon = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
        val endDayNoon = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
        // Guard: the two days must resolve different entries or the
        // anchor assertion below proves nothing.
        assertNotEquals(catalog.entry(startDayNoon)?.id, catalog.entry(endDayNoon)?.id)
        assertEquals(catalog.contributionLine(startDayNoon, walkKm, UnitSystem.Metric), line)
        assertNotEquals(catalog.contributionLine(endDayNoon, walkKm, UnitSystem.Metric), line)
    }

    @Test
    fun `milestone callout and the collective line render together`() = runTest(dispatcher) {
        val start = Instant.parse("2026-10-07T12:00:00Z").toEpochMilli()
        // ~11.1 km of route crosses the 10 km TotalDistance threshold
        // on this first walk, so the callout prose fires without any
        // seasonal-marker dependency (celestial pref off below).
        val walk = createContributableWalk(start, latitudeSpan = 0.1)
        contributionLedger.record(walk.uuid)
        routeCatalogService.initialLoad.await()

        val vm = newViewModel(
            walkId = walk.id,
            practicePreferences = org.walktalkmeditate.pilgrim.data.practice
                .FakePracticePreferencesRepository(initialCelestialAwarenessEnabled = false),
        )
        awaitLoaded(vm)

        val prose = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                vm.walkSummaryCalloutProseDisplay.first { it != null }
            }
        }
        val line = awaitCollectiveLine(vm)
        assertNotNull(prose)
        assertNotNull(line)
    }

    private suspend fun createFinishedWalk(
        durationMillis: Long,
        events: List<WalkEvent> = emptyList(),
    ): Long {
        val walk = repository.startWalk(startTimestamp = 0L)
        events.forEach { e ->
            repository.recordEvent(e.copy(walkId = walk.id))
        }
        repository.finishWalk(walk, endTimestamp = durationMillis)
        return walk.id
    }

    // --- U11 retranscribe gating (spec section 5) -----------------------

    @Test
    fun `retranscribeRecording is a no-op while the model is absent`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val recId = insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)
        repository.updateVoiceRecordingTranscription(recId, "precious transcription")

        val vm = newViewModel(walkId = walk.id, transcriptionSchedulerOverride = scheduler)
        assertFalse("gate must start closed with no model on disk", vm.retranscribeEnabled.value)
        vm.retranscribeRecording(recId)
        advanceUntilIdle()

        assertEquals(
            "the destructive null write must never land pre-Ready",
            "precious transcription",
            repository.getVoiceRecording(recId)?.transcription,
        )
        assertTrue(scheduler.scheduledWalkIds.isEmpty())
    }

    @Test
    fun `retranscribeRecording clears and reschedules once the model is ready`() =
        runTest(dispatcher) {
            installLegacyTiny()
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val recId = insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)
            repository.updateVoiceRecordingTranscription(recId, "old transcription")

            val vm = newViewModel(walkId = walk.id, transcriptionSchedulerOverride = scheduler)
            awaitRetranscribeEnabled(vm)
            vm.retranscribeRecording(recId)

            val cleared = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
                withTimeout(10_000L) {
                    var row = repository.getVoiceRecording(recId)
                    while (row?.transcription != null) {
                        delay(25L)
                        row = repository.getVoiceRecording(recId)
                    }
                    row
                }
            }
            assertNull(cleared?.transcription)
            assertEquals(listOf(walk.id), scheduler.scheduledWalkIds)
        }

    // --- U7 edit-path wiring (BEH-59 carry) -------------------------------

    @Test
    fun `saveTranscription eagerly analyzes when the threads toggle is on`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val recId = insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)
        val uuid = repository.getVoiceRecording(recId)!!.uuid

        val vm = newViewModel(walkId = walk.id)
        vm.saveTranscription(recId, "The quiet mountain trail held a long stillness today")

        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (!threadsStore.hasContext(uuid)) delay(25L)
            }
        }
    }

    @Test
    fun `saveTranscription removes any stale context when the threads toggle is off`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val recId = insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)
        val uuid = repository.getVoiceRecording(recId)!!.uuid
        threadsAnalyzer.analyzeAndStore(uuid, "prior text analyzed while the toggle was on")
        assertTrue(threadsStore.hasContext(uuid))
        threadsPreferences.setThreadsAfterWalks(false)

        val vm = newViewModel(walkId = walk.id)
        vm.saveTranscription(recId, "an edit made while the feature is off")

        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (threadsStore.hasContext(uuid)) delay(25L)
            }
        }
    }

    @Test
    fun `retranscribeRecording removes the stale context after the null write`() = runTest(dispatcher) {
        installLegacyTiny()
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        val recId = insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)
        val uuid = repository.getVoiceRecording(recId)!!.uuid
        repository.updateVoiceRecordingTranscription(recId, "old transcription")
        threadsAnalyzer.analyzeAndStore(uuid, "old transcription")
        assertTrue(threadsStore.hasContext(uuid))

        val vm = newViewModel(walkId = walk.id, transcriptionSchedulerOverride = scheduler)
        awaitRetranscribeEnabled(vm)
        vm.retranscribeRecording(recId)

        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (threadsStore.hasContext(uuid)) delay(25L)
            }
        }
    }

    @Test
    fun `transcribePendingRecordings no-ops pre-Ready and schedules once ready`() =
        runTest(dispatcher) {
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val recId = insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)

            val vm = newViewModel(walkId = walk.id, transcriptionSchedulerOverride = scheduler)
            vm.transcribePendingRecordings()
            assertTrue(scheduler.scheduledWalkIds.isEmpty())

            installLegacyTiny()
            modelStore.invalidate()
            awaitRetranscribeEnabled(vm)
            vm.transcribePendingRecordings()

            assertEquals(listOf(walk.id), scheduler.scheduledWalkIds)
            // Manual transcribe never touches existing rows — the worker
            // only fills WHERE transcription IS NULL.
            assertNull(repository.getVoiceRecording(recId)?.transcription)
        }

    // --- Post-press "Transcribing…" feedback (v1.3.0 QA finding) --------

    @Test
    fun `manual transcribe marks pending rows and clears when the transcript lands`() =
        runTest(dispatcher) {
            installLegacyTiny()
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val pendingId =
                insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)
            val transcribedId =
                insertVoiceRecording(walk.id, startOffset = 10_000L, durationMillis = 5_000L)
            repository.updateVoiceRecordingTranscription(transcribedId, "already done")

            val vm = newViewModel(walkId = walk.id, transcriptionSchedulerOverride = scheduler)
            awaitRetranscribeEnabled(vm)

            vm.transcribePendingRecordings()
            awaitManualTranscribing(vm, setOf(pendingId))

            // A second press while in-flight re-schedules but never
            // grows the optimistic set beyond the pending row.
            vm.transcribePendingRecordings()
            assertEquals(2, scheduler.scheduledWalkIds.size)
            advanceUntilIdle()
            assertEquals(setOf(pendingId), vm.manualTranscribing.value)

            repository.updateVoiceRecordingTranscription(pendingId, "fresh transcript")
            awaitManualTranscribing(vm, emptySet())
        }

    // --- U6: transcribe-all clears the skip flag only on non-empty results ---

    @Test
    fun `transcribePendingRecordings landing a result clears the skip flag`() =
        runTest(dispatcher) {
            installLegacyTiny()
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val pendingId =
                insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)
            val skipState = org.walktalkmeditate.pilgrim.core.threads.FakeAutoTranscriptionSkipState()
            skipState.setSkipped()

            val vm = newViewModel(
                walkId = walk.id,
                transcriptionSchedulerOverride = scheduler,
                autoTranscriptionSkipStateOverride = skipState,
            )
            awaitRetranscribeEnabled(vm)

            vm.transcribePendingRecordings()
            awaitManualTranscribing(vm, setOf(pendingId))

            repository.updateVoiceRecordingTranscription(pendingId, "fresh transcript")
            awaitManualTranscribing(vm, emptySet())

            assertNull(
                "a non-empty transcribe-all result must clear the skip flag",
                skipState.skipReason.value,
            )
        }

    @Test
    fun `transcribePendingRecordings with no landed results leaves the skip flag up`() =
        runTest(dispatcher) {
            installLegacyTiny()
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)
            val skipState = org.walktalkmeditate.pilgrim.core.threads.FakeAutoTranscriptionSkipState()
            skipState.setSkipped()

            val vm = newViewModel(
                walkId = walk.id,
                transcriptionSchedulerOverride = scheduler,
                autoTranscriptionSkipStateOverride = skipState,
            )
            awaitRetranscribeEnabled(vm)

            vm.transcribePendingRecordings()
            advanceUntilIdle()
            // No landed transcription simulated — an all-failed retry.

            assertEquals(
                "an all-failed retry must leave the banner up",
                org.walktalkmeditate.pilgrim.core.threads.AutoTranscriptionSkipReason.LowBattery,
                skipState.skipReason.value,
            )
        }

    @Test
    fun `retranscribeRecording (single) landing a result does NOT clear the skip flag`() =
        runTest(dispatcher) {
            installLegacyTiny()
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val recId = insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)
            repository.updateVoiceRecordingTranscription(recId, "old transcription")
            val skipState = org.walktalkmeditate.pilgrim.core.threads.FakeAutoTranscriptionSkipState()
            skipState.setSkipped()

            val vm = newViewModel(
                walkId = walk.id,
                transcriptionSchedulerOverride = scheduler,
                autoTranscriptionSkipStateOverride = skipState,
            )
            awaitRetranscribeEnabled(vm)
            vm.retranscribeRecording(recId)
            awaitManualTranscribing(vm, setOf(recId))

            repository.updateVoiceRecordingTranscription(recId, "fresh transcript")
            awaitManualTranscribing(vm, emptySet())

            assertEquals(
                "a single-file retry must never touch the skip flag — only transcribe-all does (iOS parity)",
                org.walktalkmeditate.pilgrim.core.threads.AutoTranscriptionSkipReason.LowBattery,
                skipState.skipReason.value,
            )
        }

    @Test
    fun `manual transcribe pre-Ready leaves the transcribing set empty`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 0L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)

        val vm = newViewModel(walkId = walk.id, transcriptionSchedulerOverride = scheduler)
        vm.transcribePendingRecordings()
        advanceUntilIdle()

        assertTrue(vm.manualTranscribing.value.isEmpty())
    }

    @Test
    fun `retranscribeRecording marks the row transcribing after the null write`() =
        runTest(dispatcher) {
            installLegacyTiny()
            val walk = repository.startWalk(startTimestamp = 0L)
            repository.finishWalk(walk, endTimestamp = 60_000L)
            val recId = insertVoiceRecording(walk.id, startOffset = 1_000L, durationMillis = 5_000L)
            repository.updateVoiceRecordingTranscription(recId, "old transcription")

            val vm = newViewModel(walkId = walk.id, transcriptionSchedulerOverride = scheduler)
            awaitRetranscribeEnabled(vm)
            vm.retranscribeRecording(recId)

            awaitManualTranscribing(vm, setOf(recId))
            assertNull(repository.getVoiceRecording(recId)?.transcription)

            repository.updateVoiceRecordingTranscription(recId, "fresh transcript")
            awaitManualTranscribing(vm, emptySet())
        }

    /** Same real-clock polling bridge as [awaitRetranscribeEnabled]. */
    private suspend fun awaitManualTranscribing(vm: WalkSummaryViewModel, expected: Set<Long>) {
        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                while (vm.manualTranscribing.value != expected) {
                    delay(25L)
                }
            }
        }
    }

    private suspend fun insertVoiceRecording(
        walkId: Long,
        startOffset: Long,
        durationMillis: Long,
    ): Long {
        val walk = repository.getWalk(walkId)!!
        val rec = VoiceRecording(
            walkId = walkId,
            startTimestamp = walk.startTimestamp + startOffset,
            endTimestamp = walk.startTimestamp + startOffset + durationMillis,
            durationMillis = durationMillis,
            fileRelativePath = "recordings/${walk.uuid}/rec-$startOffset.wav",
            transcription = null,
        )
        return repository.recordVoice(rec)
    }

    private suspend fun insertAltitude(walkId: Long, ts: Long, alt: Double) {
        db.altitudeSampleDao().insert(
            org.walktalkmeditate.pilgrim.data.entity.AltitudeSample(
                walkId = walkId,
                timestamp = ts,
                altitudeMeters = alt,
            ),
        )
    }

    private suspend fun insertRouteSample(walkId: Long, t: Long, lat: Double, lng: Double) {
        db.routeDataSampleDao().insert(
            RouteDataSample(
                walkId = walkId,
                timestamp = t,
                latitude = lat,
                longitude = lng,
                altitudeMeters = 0.0,
            ),
        )
    }

    /**
     * Seeds a MEDITATION_START/END event pair directly (bypassing
     * [createFinishedWalk]'s events param) for tests that need to add
     * meditation after other post-hoc setup (route samples, etc.) —
     * mirrors how the walk summary VM derives `meditationIntervals` /
     * `routeSegments` / `walkAnnotations` from the event log rather than
     * the (never production-written) `activity_intervals` table.
     */
    private suspend fun insertMeditationEvents(walkId: Long, startTimestamp: Long, endTimestamp: Long) {
        repository.recordEvent(
            WalkEvent(walkId = walkId, timestamp = startTimestamp, eventType = WalkEventType.MEDITATION_START),
        )
        repository.recordEvent(
            WalkEvent(walkId = walkId, timestamp = endTimestamp, eventType = WalkEventType.MEDITATION_END),
        )
    }

    private suspend fun awaitLoaded(vm: WalkSummaryViewModel): WalkSummaryUiState.Loaded {
        return withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(10_000L) {
                vm.state.first { it is WalkSummaryUiState.Loaded } as WalkSummaryUiState.Loaded
            }
        }
    }

    // UUID-suffixed so parallel test forks can't collide on file path.
    private val hemisphereStoreName: String = "walk-summary-vm-hemisphere-test-${java.util.UUID.randomUUID()}"
}
