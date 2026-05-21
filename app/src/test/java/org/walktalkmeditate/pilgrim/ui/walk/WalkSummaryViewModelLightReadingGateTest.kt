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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.FakeVoicePlaybackController
import org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.photo.FakePhotoAnalysisScheduler
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.seal.SealRevealStore
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.sharing.WalkSharingTracker
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository

/**
 * Task 2.2: VM-side [WalkSummaryViewModel.hasRevealedLightReading] flow +
 * [WalkSummaryViewModel.markCurrentWalkShared].
 *
 * Tests that the flow correctly reflects WalkSharingTracker state and that
 * markCurrentWalkShared() persists via the tracker, flipping the flow to true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryViewModelLightReadingGateTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var playback: FakeVoicePlaybackController
    private lateinit var scheduler: FakeTranscriptionScheduler
    private lateinit var sweeper: OrphanRecordingSweeper
    private lateinit var hemisphereDataStore: DataStore<Preferences>
    private lateinit var hemisphereScope: CoroutineScope
    private lateinit var hemisphereRepo: HemisphereRepository
    private lateinit var persistenceScope: CoroutineScope
    private lateinit var photoAnalysisScheduler: FakePhotoAnalysisScheduler
    private lateinit var walkSharingTracker: WalkSharingTracker
    private val dispatcher = UnconfinedTestDispatcher()
    private val hemisphereStoreName = "wsvm-light-reading-gate-${java.util.UUID.randomUUID()}"

    private val createdViewModels = mutableListOf<WalkSummaryViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
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
        playback = FakeVoicePlaybackController()
        scheduler = FakeTranscriptionScheduler()
        sweeper = OrphanRecordingSweeper(context, repository, scheduler)
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        hemisphereScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hemisphereDataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(hemisphereStoreName) },
        )
        hemisphereRepo = HemisphereRepository(hemisphereDataStore, FakeLocationSource(), hemisphereScope)
        persistenceScope = CoroutineScope(SupervisorJob() + dispatcher)
        photoAnalysisScheduler = FakePhotoAnalysisScheduler()
        // In-memory DataStore: markCurrentWalkShared() -> tracker write
        // -> hasRevealedLightReading flip must propagate synchronously
        // under the test dispatcher. A real file-backed DataStore runs
        // its actor on a real dispatcher and defeats Turbine's bound on
        // CPU-starved CI (the ci-realtime-withtimeout flake family).
        walkSharingTracker = WalkSharingTracker(
            dataStore = org.walktalkmeditate.pilgrim.data.FakePreferencesDataStore(),
        )
    }

    @After
    fun tearDown() {
        for (vm in createdViewModels) {
            vm.viewModelScope.coroutineContext[Job]?.cancel()
        }
        createdViewModels.clear()
        persistenceScope.coroutineContext[Job]?.cancel()
        hemisphereScope.coroutineContext[Job]?.cancel()
        db.close()
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        walkId: Long,
        practicePreferences: FakePracticePreferencesRepository = FakePracticePreferencesRepository(
            initialCelestialAwarenessEnabled = true,
        ),
    ): Pair<WalkSummaryViewModel, FakePracticePreferencesRepository> {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val vm = WalkSummaryViewModel(
            context = context,
            repository = repository,
            playback = playback,
            sweeper = sweeper,
            photoAnalysisScheduler = photoAnalysisScheduler,
            hemisphereRepository = hemisphereRepo,
            cachedShareStore = CachedShareStore(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    produceFile = {
                        java.io.File(
                            context.cacheDir,
                            "light-reading-gate-share-${java.util.UUID.randomUUID()}.preferences_pb",
                        )
                    },
                ),
                json = json,
            ),
            unitsPreferences = FakeUnitsPreferencesRepository(),
            practicePreferences = practicePreferences,
            promptsCoordinator = stubPromptsCoordinator(),
            sealRevealStore = SealRevealStore(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    produceFile = {
                        java.io.File(
                            context.cacheDir,
                            "light-reading-gate-seal-${java.util.UUID.randomUUID()}.preferences_pb",
                        )
                    },
                ),
            ),
            walkSharingTracker = walkSharingTracker,
            photoExifReader = org.walktalkmeditate.pilgrim.data.photo.PhotoExifReader(
                context = context,
            ),
            photoLibraryScanner = org.walktalkmeditate.pilgrim.data.photo.PhotoLibraryScanner(
                context = context,
            ),
            transcriptionScheduler = object : org.walktalkmeditate.pilgrim.audio.TranscriptionScheduler { override fun scheduleForWalk(walkId: Long) {} },
            waveformCache = org.walktalkmeditate.pilgrim.audio.WaveformCache(
                fileSystem = org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem(context),
            ),
            persistenceScope = persistenceScope,
            savedStateHandle = SavedStateHandle(mapOf(WalkSummaryViewModel.ARG_WALK_ID to walkId)),
        )
        createdViewModels += vm
        return vm to practicePreferences
    }

    private fun stubPromptsCoordinator(): org.walktalkmeditate.pilgrim.core.prompt.PromptsCoordinator {
        val ctx = context
        return object : org.walktalkmeditate.pilgrim.core.prompt.PromptsCoordinator(
            repository = repository,
            customStyleStore = org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyleStore(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    produceFile = {
                        java.io.File(
                            ctx.cacheDir,
                            "light-reading-gate-styles-${java.util.UUID.randomUUID()}.preferences_pb",
                        )
                    },
                ),
                json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ),
            photoContextAnalyzer = org.walktalkmeditate.pilgrim.core.prompt.PhotoContextAnalyzer(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    produceFile = {
                        java.io.File(
                            ctx.cacheDir,
                            "light-reading-gate-photo-${java.util.UUID.randomUUID()}.preferences_pb",
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
            geocoder = org.walktalkmeditate.pilgrim.core.prompt.PromptGeocoder(ctx),
            promptGenerator = org.walktalkmeditate.pilgrim.core.prompt.PromptGenerator(ctx),
            practicePreferences = FakePracticePreferencesRepository(),
            unitsPreferences = FakeUnitsPreferencesRepository(),
            appContext = ctx,
        ) {
            override suspend fun buildContext(walkId: Long, zone: java.time.ZoneId) = null
            override suspend fun generateAll(walkId: Long, zone: java.time.ZoneId) =
                emptyList<org.walktalkmeditate.pilgrim.core.prompt.GeneratedPrompt>()
        }
    }

    private suspend fun freshFinishedWalkId(): Long {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        return walk.id
    }

    // --- hasRevealedLightReading ------------------------------------------

    @Test
    fun hasRevealedLightReading_isFalse_byDefault() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, _) = newViewModel(walkId)
        vm.hasRevealedLightReading.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun hasRevealedLightReading_isTrue_whenUuidAlreadyShared() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val walkUuid = db.walkDao().getById(walkId)!!.uuid
        walkSharingTracker.markShared(walkUuid)
        advanceUntilIdle()
        val (vm, _) = newViewModel(walkId)
        // hasRevealedLightReading = state.flatMapLatest { if Loaded ->
        // hasSharedFlow(uuid) else flowOf(false) } with initialValue
        // false. Before state reaches Loaded the chain emits false;
        // under a CPU-starved full-suite shard state is still Loading
        // when Turbine subscribes, so the first awaitItem is false and
        // assertEquals(true, awaitItem()) fails (the
        // ci-realtime-withtimeout flake). Await Loaded first, then
        // drain to true (predicate-gated, not a clock).
        vm.state.first { it is WalkSummaryUiState.Loaded }
        vm.hasRevealedLightReading.test {
            var revealed = awaitItem()
            while (!revealed) revealed = awaitItem()
            assertEquals(true, revealed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun markCurrentWalkShared_flipsHasRevealedToTrue() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, _) = newViewModel(walkId)
        // markCurrentWalkShared() early-returns when state.value !is
        // Loaded (a no-op), so calling it before the Room-backed state
        // flow loads silently drops the write and the flip never
        // arrives. In isolation state reaches Loaded fast enough by
        // luck; under a CPU-starved full-suite shard it does not — the
        // ci-realtime-withtimeout flake. Await Loaded deterministically
        // first (gated by predicate, not a clock); the in-memory
        // tracker then propagates the flip synchronously.
        vm.state.first { it is WalkSummaryUiState.Loaded }
        vm.hasRevealedLightReading.test {
            assertEquals(false, awaitItem())
            vm.markCurrentWalkShared()
            var revealed = awaitItem()
            while (!revealed) revealed = awaitItem()
            assertEquals(true, revealed)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
