// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.FakeVoicePlaybackController
import org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper
import org.walktalkmeditate.pilgrim.core.prompt.ActivityContext
import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyle
import org.walktalkmeditate.pilgrim.core.prompt.GeneratedPrompt
import org.walktalkmeditate.pilgrim.core.prompt.PromptStyle
import org.walktalkmeditate.pilgrim.core.prompt.PromptsCoordinator
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.photo.FakePhotoAnalysisScheduler
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository

/**
 * Stage 13-XZ Task 12: state-machine + cache-invalidation coverage for
 * `WalkSummaryViewModel.promptsSheetState`. Uses a hand-rolled
 * [FakePromptsCoordinator] so we can assert call counts without a
 * mocking framework (none in this project).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryViewModelPromptsTest {

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
    private val hemisphereStoreName = "wsvm-prompts-${java.util.UUID.randomUUID()}"

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
        // Stage 7-A: cancel viewModelScope-equivalent collectors BEFORE
        // db.close() to keep Room observers from firing on a closed db.
        persistenceScope.coroutineContext[Job]?.cancel()
        hemisphereScope.coroutineContext[Job]?.cancel()
        db.close()
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        java.io.File(context.filesDir, "datastore/share_cache.preferences_pb").delete()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        walkId: Long,
        coordinator: FakePromptsCoordinator = FakePromptsCoordinator(),
    ): Pair<WalkSummaryViewModel, FakePromptsCoordinator> {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val cachedShareStore = CachedShareStore(context, json)
        val vm = WalkSummaryViewModel(
            context = context,
            repository = repository,
            playback = playback,
            sweeper = sweeper,
            photoAnalysisScheduler = photoAnalysisScheduler,
            hemisphereRepository = hemisphereRepo,
            cachedShareStore = cachedShareStore,
            unitsPreferences = FakeUnitsPreferencesRepository(),
            practicePreferences = FakePracticePreferencesRepository(initialCelestialAwarenessEnabled = true),
            promptsCoordinator = coordinator,
            persistenceScope = persistenceScope,
            savedStateHandle = SavedStateHandle(mapOf(WalkSummaryViewModel.ARG_WALK_ID to walkId)),
        )
        return vm to coordinator
    }

    private suspend fun freshFinishedWalkId(): Long {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(walk, endTimestamp = 60_000L)
        return walk.id
    }

    // --- buildContext / state transitions ---------------------------------

    @Test
    fun openPromptsSheet_buildContextNull_returnsClosed() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, coordinator) = newViewModel(walkId, FakePromptsCoordinator(buildContextResult = null))

        vm.openPromptsSheet()
        advanceUntilIdle()

        assertEquals(PromptsSheetState.Closed, vm.promptsSheetState.value)
        assertEquals(1, coordinator.buildContextCalls.get())
        // generateAll never called since buildContext returned null.
        assertEquals(0, coordinator.generateAllCalls.get())
    }

    @Test
    fun openPromptsSheet_buildsContext_andEntersListing() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val ctx = stubActivityContext()
        val prompts = stubPrompts(6)
        val (vm, coordinator) = newViewModel(
            walkId,
            FakePromptsCoordinator(buildContextResult = ctx, generateAllResult = prompts),
        )

        vm.openPromptsSheet()
        advanceUntilIdle()

        val state = vm.promptsSheetState.value
        assertTrue("expected Listing, got $state", state is PromptsSheetState.Listing)
        val listing = state as PromptsSheetState.Listing
        assertEquals(ctx, listing.context)
        assertEquals(6, listing.prompts.size)
        assertEquals(1, coordinator.buildContextCalls.get())
        assertEquals(1, coordinator.generateAllCalls.get())
    }

    @Test
    fun openPromptsSheet_secondCall_usesCacheNoRebuild() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, coordinator) = newViewModel(
            walkId,
            FakePromptsCoordinator(buildContextResult = stubActivityContext(), generateAllResult = stubPrompts(6)),
        )

        vm.openPromptsSheet()
        advanceUntilIdle()
        vm.closePromptsSheet()
        vm.openPromptsSheet()
        advanceUntilIdle()

        // Cache hit: buildContext / generateAll only invoked on the first open.
        assertEquals(1, coordinator.buildContextCalls.get())
        assertEquals(1, coordinator.generateAllCalls.get())
        assertTrue(vm.promptsSheetState.value is PromptsSheetState.Listing)
    }

    @Test
    fun closePromptsSheet_closes() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, _) = newViewModel(
            walkId,
            FakePromptsCoordinator(buildContextResult = stubActivityContext(), generateAllResult = stubPrompts(6)),
        )

        vm.openPromptsSheet()
        advanceUntilIdle()
        assertTrue(vm.promptsSheetState.value is PromptsSheetState.Listing)

        vm.closePromptsSheet()

        assertEquals(PromptsSheetState.Closed, vm.promptsSheetState.value)
    }

    @Test
    fun openPromptDetail_fromListing_transitions() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val prompts = stubPrompts(6)
        val (vm, _) = newViewModel(
            walkId,
            FakePromptsCoordinator(buildContextResult = stubActivityContext(), generateAllResult = prompts),
        )

        vm.openPromptsSheet()
        advanceUntilIdle()
        vm.openPromptDetail(prompts.first())

        val state = vm.promptsSheetState.value
        assertTrue("expected Detail, got $state", state is PromptsSheetState.Detail)
        assertEquals(prompts.first(), (state as PromptsSheetState.Detail).prompt)
    }

    @Test
    fun openPromptDetail_fromClosed_noop() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, _) = newViewModel(walkId)

        vm.openPromptDetail(stubPrompts(1).first())

        // Stays Closed because there's no listing to attach to.
        assertEquals(PromptsSheetState.Closed, vm.promptsSheetState.value)
    }

    @Test
    fun dismissDetailOrEditor_returnsToListing() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val prompts = stubPrompts(6)
        val (vm, _) = newViewModel(
            walkId,
            FakePromptsCoordinator(buildContextResult = stubActivityContext(), generateAllResult = prompts),
        )

        vm.openPromptsSheet()
        advanceUntilIdle()
        vm.openPromptDetail(prompts.first())
        assertTrue(vm.promptsSheetState.value is PromptsSheetState.Detail)

        vm.dismissDetailOrEditor()

        assertTrue(vm.promptsSheetState.value is PromptsSheetState.Listing)
    }

    @Test
    fun openCustomPromptEditor_fromListing_entersEditor() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, _) = newViewModel(
            walkId,
            FakePromptsCoordinator(buildContextResult = stubActivityContext(), generateAllResult = stubPrompts(6)),
        )

        vm.openPromptsSheet()
        advanceUntilIdle()
        vm.openCustomPromptEditor(editing = null)

        val state = vm.promptsSheetState.value
        assertTrue("expected Editor, got $state", state is PromptsSheetState.Editor)
        assertNull((state as PromptsSheetState.Editor).editing)
    }

    @Test
    fun saveCustomPrompt_callsCoordinatorAndInvalidatesCache_returnsToListing() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, coordinator) = newViewModel(
            walkId,
            FakePromptsCoordinator(buildContextResult = stubActivityContext(), generateAllResult = stubPrompts(6)),
        )

        vm.openPromptsSheet()
        advanceUntilIdle()
        vm.openCustomPromptEditor(editing = null)
        assertTrue(vm.promptsSheetState.value is PromptsSheetState.Editor)

        val style = CustomPromptStyle(title = "Walking notes", icon = "edit", instruction = "Note things.")
        vm.saveCustomPrompt(style)
        advanceUntilIdle()

        assertEquals(listOf(style), coordinator.savedStyles)
        assertTrue(vm.promptsSheetState.value is PromptsSheetState.Listing)
        // Cache invalidated → next open triggers another buildContext.
        vm.closePromptsSheet()
        vm.openPromptsSheet()
        advanceUntilIdle()
        assertEquals(2, coordinator.buildContextCalls.get())
    }

    @Test
    fun deleteCustomPrompt_callsCoordinatorAndInvalidatesCache_returnsToListing() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val style = CustomPromptStyle(title = "x", icon = "edit", instruction = "y")
        val (vm, coordinator) = newViewModel(
            walkId,
            FakePromptsCoordinator(
                buildContextResult = stubActivityContext(),
                generateAllResult = stubPrompts(6),
                initialCustomStyles = listOf(style),
            ),
        )

        vm.openPromptsSheet()
        advanceUntilIdle()
        vm.openCustomPromptEditor(editing = style)
        assertTrue(vm.promptsSheetState.value is PromptsSheetState.Editor)

        vm.deleteCustomPrompt(style)
        advanceUntilIdle()

        assertEquals(listOf(style), coordinator.deletedStyles)
        assertTrue(vm.promptsSheetState.value is PromptsSheetState.Listing)
        vm.closePromptsSheet()
        vm.openPromptsSheet()
        advanceUntilIdle()
        assertEquals(2, coordinator.buildContextCalls.get())
    }

    // --- cache invalidation observers --------------------------------------

    @Test
    fun cacheInvalidation_onPhotoCountChange() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, coordinator) = newViewModel(
            walkId,
            FakePromptsCoordinator(buildContextResult = stubActivityContext(), generateAllResult = stubPrompts(6)),
        )

        vm.openPromptsSheet()
        advanceUntilIdle()
        assertEquals(1, coordinator.buildContextCalls.get())

        // Pin a photo → invalidation collector nulls the cache.
        repository.pinPhoto(
            walkId = walkId,
            photoUri = "content://media/picker/0/com.example/1",
            takenAt = null,
            pinnedAt = 5_000L,
        )
        // Bridge to wall-clock for Room's invalidation tracker (separate
        // from the test dispatcher); wait for the invalidator to actually
        // null the cached context — both the photos flow AND the
        // invalidator collect run inside viewModelScope but Room ticks
        // its tracker on its own executor, so a wall-clock spin is
        // needed before runTest's virtual time can drain the resulting
        // collect work.
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
                while (vm.cachedContextSnapshot() != null) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        advanceUntilIdle()

        // Reopen → cache miss → buildContext called again. Wait
        // wall-clock for state to settle since the launch coroutine
        // is dispatched.
        vm.closePromptsSheet()
        vm.openPromptsSheet()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
                vm.promptsSheetState.first { it is PromptsSheetState.Listing }
            }
        }
        assertEquals(2, coordinator.buildContextCalls.get())
    }

    @Test
    fun cacheInvalidation_onTranscribedRecordingCountChange() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, coordinator) = newViewModel(
            walkId,
            FakePromptsCoordinator(buildContextResult = stubActivityContext(), generateAllResult = stubPrompts(6)),
        )

        // Insert a recording WITHOUT transcription first (transcribed-count
        // stays 0, distinctUntilChanged dedups).
        val recording = VoiceRecording(
            walkId = walkId,
            startTimestamp = 1_500L,
            endTimestamp = 6_500L,
            durationMillis = 5_000L,
            fileRelativePath = "rec/$walkId/r1.wav",
            transcription = null,
        )
        val recId = repository.recordVoice(recording)

        vm.openPromptsSheet()
        advanceUntilIdle()
        assertEquals(1, coordinator.buildContextCalls.get())

        // Now backfill the transcription — count flips 0 → 1, cache invalidates.
        repository.updateVoiceRecording(recording.copy(id = recId, transcription = "Hello world."))
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
                while (vm.cachedContextSnapshot() != null) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        advanceUntilIdle()

        vm.closePromptsSheet()
        vm.openPromptsSheet()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
                vm.promptsSheetState.first { it is PromptsSheetState.Listing }
            }
        }
        assertEquals(2, coordinator.buildContextCalls.get())
    }

    /**
     * Reflective access into [WalkSummaryViewModel._cachedActivityContext]
     * for the cache-invalidation tests below. This is the lowest-cost
     * way to assert "the invalidator nulled the cache" without exposing
     * private state through a public API.
     */
    @Suppress("UNCHECKED_CAST")
    private fun WalkSummaryViewModel.cachedContextSnapshot(): Pair<*, *>? {
        val field = WalkSummaryViewModel::class.java.getDeclaredField("_cachedActivityContext")
        field.isAccessible = true
        val mutableStateFlow = field.get(this) as kotlinx.coroutines.flow.MutableStateFlow<Pair<*, *>?>
        return mutableStateFlow.value
    }

    @Test
    fun cacheInvalidation_doesNotFireOnInitialEmission() = runTest(dispatcher) {
        val walkId = freshFinishedWalkId()
        val (vm, coordinator) = newViewModel(
            walkId,
            FakePromptsCoordinator(buildContextResult = stubActivityContext(), generateAllResult = stubPrompts(6)),
        )

        // Let the invalidation observer's initial combine emission run.
        advanceUntilIdle()

        // Open once — cache should populate.
        vm.openPromptsSheet()
        advanceUntilIdle()
        assertEquals(1, coordinator.buildContextCalls.get())

        // Close and reopen WITHOUT touching photos / recordings.
        // .drop(1) on the invalidator means the initial emission did NOT
        // null the cache, so the second open is a cache hit.
        vm.closePromptsSheet()
        vm.openPromptsSheet()
        advanceUntilIdle()
        assertEquals(1, coordinator.buildContextCalls.get())
    }

    // --- helpers / fixtures ------------------------------------------------

    private fun stubActivityContext(): ActivityContext = ActivityContext(
        recordings = emptyList(),
        meditations = emptyList(),
        durationSeconds = 60L,
        distanceMeters = 0.0,
        startTimestamp = 1_000L,
        placeNames = emptyList(),
        routeSpeeds = emptyList(),
        recentWalkSnippets = emptyList(),
        intention = null,
        waypoints = emptyList(),
        weather = null,
        lunarPhase = null,
        celestial = null,
        photoContexts = emptyList(),
        narrativeArc = null,
    )

    private fun stubPrompts(count: Int): List<GeneratedPrompt> {
        val styles = PromptStyle.entries.toList()
        return (0 until count).map { i ->
            GeneratedPrompt(
                style = if (i < styles.size) styles[i] else null,
                customStyle = null,
                title = "title-$i",
                subtitle = "subtitle-$i",
                text = "text-$i",
                icon = Icons.Outlined.Edit,
            )
        }
    }
}

/**
 * Hand-rolled fake (no mocking framework in repo). Subclasses
 * [PromptsCoordinator] so the VM accepts it as the injected
 * dependency, but every method is overridden to return the
 * pre-canned values + bump call counters.
 *
 * The base constructor needs *some* arguments; we pass throwaway
 * stubs that won't be consulted because every public method is
 * overridden.
 */
private class FakePromptsCoordinator(
    private val buildContextResult: ActivityContext? = null,
    private val generateAllResult: List<GeneratedPrompt> = emptyList(),
    initialCustomStyles: List<CustomPromptStyle> = emptyList(),
) : PromptsCoordinator(
    repository = ThrowingWalkRepository,
    customStyleStore = ThrowingCustomPromptStyleStore,
    photoContextAnalyzer = ThrowingPhotoContextAnalyzer,
    geocoder = ThrowingGeocoder,
    promptGenerator = ThrowingPromptGenerator,
    practicePreferences = FakePracticePreferencesRepository(),
    unitsPreferences = FakeUnitsPreferencesRepository(),
    appContext = ApplicationProvider.getApplicationContext<Application>(),
) {
    val buildContextCalls = AtomicInteger(0)
    val generateAllCalls = AtomicInteger(0)
    val savedStyles = mutableListOf<CustomPromptStyle>()
    val deletedStyles = mutableListOf<CustomPromptStyle>()
    private val _customStyles = MutableStateFlow(initialCustomStyles)

    override val customStyles: StateFlow<List<CustomPromptStyle>> get() = _customStyles

    override suspend fun buildContext(walkId: Long, zone: ZoneId): ActivityContext? {
        buildContextCalls.incrementAndGet()
        return buildContextResult
    }

    override suspend fun generateAll(walkId: Long, zone: ZoneId): List<GeneratedPrompt> {
        generateAllCalls.incrementAndGet()
        return generateAllResult
    }

    override suspend fun saveCustomStyle(style: CustomPromptStyle) {
        savedStyles += style
        _customStyles.value = (_customStyles.value.filterNot { it.id == style.id } + style)
    }

    override suspend fun deleteCustomStyle(style: CustomPromptStyle) {
        deletedStyles += style
        _customStyles.value = _customStyles.value.filterNot { it.id == style.id }
    }
}

private val ThrowingWalkRepository: WalkRepository = run {
    val ctx = ApplicationProvider.getApplicationContext<Application>()
    val db = Room.inMemoryDatabaseBuilder(ctx, PilgrimDatabase::class.java).build()
    WalkRepository(
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
}

private val ThrowingCustomPromptStyleStore: org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyleStore =
    org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyleStore(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                java.io.File(
                    ApplicationProvider.getApplicationContext<Application>().cacheDir,
                    "throwing-custom-prompt-store-${System.nanoTime()}.preferences_pb",
                )
            },
        ),
        json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

private val ThrowingPhotoContextAnalyzer: org.walktalkmeditate.pilgrim.core.prompt.PhotoContextAnalyzer =
    org.walktalkmeditate.pilgrim.core.prompt.PhotoContextAnalyzer(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                java.io.File(
                    ApplicationProvider.getApplicationContext<Application>().cacheDir,
                    "throwing-photo-cache-${System.nanoTime()}.preferences_pb",
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
    )

private val ThrowingGeocoder: org.walktalkmeditate.pilgrim.core.prompt.PromptGeocoder =
    org.walktalkmeditate.pilgrim.core.prompt.PromptGeocoder(
        ApplicationProvider.getApplicationContext<Application>(),
    )

private val ThrowingPromptGenerator: org.walktalkmeditate.pilgrim.core.prompt.PromptGenerator =
    org.walktalkmeditate.pilgrim.core.prompt.PromptGenerator(
        ApplicationProvider.getApplicationContext<Application>(),
    )
