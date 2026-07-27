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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.FakeVoicePlaybackController
import org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper
import org.walktalkmeditate.pilgrim.data.PhotoPinRef
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
import org.walktalkmeditate.pilgrim.ui.walk.reliquary.ReliquaryState

/**
 * Task 2.2: VM-side [WalkSummaryViewModel.reliquaryState] flow +
 * permission snapshot + [WalkSummaryViewModel.onForegrounded].
 *
 * Path B (simplified): Android's architecture uses Room's hot Flow
 * exclusively for pinned photos — there is no MediaStore scan/fetch
 * trigger, so [ReliquaryState.Loading] never appears in production from
 * this VM. `isFetching` is hard-wired to `false` in the combine; these
 * tests verify the three reachable production states + the
 * `onForegrounded` permission-tracking contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryViewModelReliquaryStateTest {

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
    private val dispatcher = UnconfinedTestDispatcher()
    private val hemisphereStoreName = "wsvm-reliquary-${java.util.UUID.randomUUID()}"

    /**
     * All VMs created in a test are tracked here so tearDown can cancel
     * their viewModelScope BEFORE db.close() — prevents Room observers
     * from firing onto a closed DB and poisoning later tests with
     * UncaughtExceptionsBeforeTest on a misleading stack pointer.
     */
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
        java.io.File(context.filesDir, "datastore/share_cache.preferences_pb").delete()
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
            cachedShareStore = CachedShareStore(context, json),
            unitsPreferences = FakeUnitsPreferencesRepository(),
            practicePreferences = practicePreferences,
            promptsCoordinator = stubPromptsCoordinator(),
            sealRevealStore = SealRevealStore(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    produceFile = {
                        java.io.File(
                            context.cacheDir,
                            "reliquary-test-seal-${java.util.UUID.randomUUID()}.preferences_pb",
                        )
                    },
                ),
            ),
            walkSharingTracker = WalkSharingTracker(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    produceFile = {
                        java.io.File(
                            context.cacheDir,
                            "reliquary-test-sharing-${java.util.UUID.randomUUID()}.preferences_pb",
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
            transcriptionScheduler = object : org.walktalkmeditate.pilgrim.audio.TranscriptionScheduler { override fun scheduleForWalk(walkId: Long) {}; override fun rescheduleForWalk(walkId: Long) {} },
            waveformCache = org.walktalkmeditate.pilgrim.audio.WaveformCache(
                fileSystem = org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem(context),
            ),
            routeCatalogService = org.walktalkmeditate.pilgrim.data.collective.routes.bootstrapRouteCatalogService(
                context,
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ),
            contributionLedger =
                org.walktalkmeditate.pilgrim.data.collective.routes.inMemoryContributionLedger(),
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
                            "reliquary-test-styles-${java.util.UUID.randomUUID()}.preferences_pb",
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
                            "reliquary-test-photo-${java.util.UUID.randomUUID()}.preferences_pb",
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

    // --- reliquaryState combine -------------------------------------------

    @Test
    fun `reliquaryState emits ToggleOff when walkReliquaryEnabled is false`() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val prefs = FakePracticePreferencesRepository(
            initialCelestialAwarenessEnabled = true,
            initialWalkReliquaryEnabled = false,
        )
        val (vm, _) = newViewModel(walkId, prefs)

        vm.onForegrounded(permissionGranted = true)
        advanceUntilIdle()

        vm.reliquaryState.test(timeout = 5.seconds) {
            val state = awaitItem()
            assertEquals(ReliquaryState.ToggleOff, state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reliquaryState emits PermissionDenied when toggle on but permission denied`() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val prefs = FakePracticePreferencesRepository(
            initialCelestialAwarenessEnabled = true,
            initialWalkReliquaryEnabled = true,
        )
        val (vm, _) = newViewModel(walkId, prefs)

        vm.onForegrounded(permissionGranted = false)
        advanceUntilIdle()

        vm.reliquaryState.test(timeout = 5.seconds) {
            val state = awaitItem()
            assertEquals(ReliquaryState.PermissionDenied, state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reliquaryState emits Populated when toggle on, permission granted, photos present`() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val prefs = FakePracticePreferencesRepository(
            initialCelestialAwarenessEnabled = true,
            initialWalkReliquaryEnabled = true,
        )
        val (vm, _) = newViewModel(walkId, prefs)

        repository.pinPhotos(
            walkId = walkId,
            refs = listOf(PhotoPinRef(uri = "content://media/external/images/media/1", takenAt = null)),
            pinnedAt = 2_000L,
        )
        vm.onForegrounded(permissionGranted = true)
        advanceUntilIdle()

        vm.reliquaryState.test(timeout = 5.seconds) {
            var latestState = awaitItem()
            // Drain until we see Populated — the initial value is ToggleOff
            // (from the stateIn initialValue) and may arrive before the
            // combine fires with the updated inputs.
            while (latestState !is ReliquaryState.Populated) latestState = awaitItem()
            val finalState: ReliquaryState = latestState
            @Suppress("KotlinConstantConditions")
            assertTrue(
                "expected Populated with non-empty candidates",
                finalState is ReliquaryState.Populated && finalState.candidates.isNotEmpty(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- onForegrounded permission tracking -------------------------------

    @Test
    fun `onForegrounded denied-to-granted transition updates permission snapshot`() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val prefs = FakePracticePreferencesRepository(
            initialCelestialAwarenessEnabled = true,
            initialWalkReliquaryEnabled = true,
        )
        val (vm, _) = newViewModel(walkId, prefs)

        vm.onForegrounded(permissionGranted = false)
        advanceUntilIdle()

        vm.reliquaryState.test(timeout = 5.seconds) {
            val deniedState = awaitItem()
            assertEquals(ReliquaryState.PermissionDenied, deniedState)

            // Simulate the user granting permission and the screen resuming.
            vm.onForegrounded(permissionGranted = true)
            advanceUntilIdle()

            val grantedState = awaitItem()
            // No photos pinned → Populated(empty), not PermissionDenied.
            assertTrue(
                "expected Populated after permission granted, got $grantedState",
                grantedState is ReliquaryState.Populated,
            )
            assertFalse(
                "Populated must not be PermissionDenied after grant",
                grantedState is ReliquaryState.PermissionDenied,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
