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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Assert.assertNotNull
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
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.data.walk.RouteActivity
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotationKind
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
    private val dispatcher = UnconfinedTestDispatcher()

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
    }

    private lateinit var photoAnalysisScheduler: org.walktalkmeditate.pilgrim.data.photo.FakePhotoAnalysisScheduler

    private fun newViewModel(
        walkId: Long,
        repositoryOverride: WalkRepository = repository,
        practicePreferences: org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository =
            // Stage 10-C: light reading is gated on celestialAwarenessEnabled.
            // The legacy tests in this file all assert non-null lightReading
            // (or don't care) — flip the default ON so they pass without
            // changes. The OFF-suppression path has its own dedicated test.
            org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(
                initialCelestialAwarenessEnabled = true,
            ),
    ): WalkSummaryViewModel {
        photoAnalysisScheduler = org.walktalkmeditate.pilgrim.data.photo.FakePhotoAnalysisScheduler()
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val cachedShareStore = org.walktalkmeditate.pilgrim.data.share.CachedShareStore(context, json)
        return WalkSummaryViewModel(
            context = context,
            repository = repositoryOverride,
            playback = playback,
            sweeper = sweeper,
            photoAnalysisScheduler = photoAnalysisScheduler,
            hemisphereRepository = hemisphereRepo,
            cachedShareStore = cachedShareStore,
            unitsPreferences = org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            practicePreferences = practicePreferences,
            persistenceScope = persistenceScope,
            savedStateHandle = SavedStateHandle(mapOf("walkId" to walkId)),
        )
    }

    @After
    fun tearDown() {
        db.close()
        hemisphereScope.coroutineContext[Job]?.cancel()
        persistenceScope.coroutineContext[Job]?.cancel()
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        // Stage 8-A: WalkSummaryViewModel.cachedShareFlow opens the
        // share_cache DataStore eagerly via SharingStarted.Eagerly.
        // Without this cleanup, cached entries from one test would
        // leak into another's `cachedShare` observer (Stage 7-A
        // Robolectric+Eagerly cross-test pollution lesson). Matches
        // the cleanup already in WalkShareViewModelTest +
        // CachedShareStoreTest.
        java.io.File(context.filesDir, "datastore/share_cache.preferences_pb").delete()
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
    fun `celestialAwarenessEnabled = false suppresses lightReadingDisplay`() = runTest(dispatcher) {
        // Stage 10-C: the underlying lightReading is ALWAYS computed
        // (so the toggle is observable while the summary is open) but
        // the VM exposes a separate `lightReadingDisplay` flow that
        // gates on `celestialAwarenessEnabled`. The screen renders
        // from this flow, not from `summary.lightReading`.
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
            // The display flow seeds with null (initialValue) and
            // stays null because the gate is OFF.
            assertNull(
                "celestialAwarenessEnabled = false should suppress lightReadingDisplay",
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
        val observed = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
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
        val rows = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
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

        val rows = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
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
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) { vm.pinnedPhotos.first { it.isNotEmpty() } }
        }

        vm.pinPhotos(
            listOf(
                android.net.Uri.parse(existing),
                android.net.Uri.parse("content://media/picker/0/com.example/new"),
            ),
        )

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
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
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
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

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
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
        val initial = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) { vm.pinnedPhotos.first { it.size == 1 } }
        }
        assertEquals(1, initial.size)

        vm.unpinPhoto(initial.first().copy(id = id))

        val after = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) { vm.pinnedPhotos.first { it.isEmpty() } }
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
            val ev = withContext(Dispatchers.Default.limitedParallelism(1)) {
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
        vm.saveEtegamiToGallery(fixtureEtegamiSpec(walk.uuid))
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000L) { vm.etegamiBusy.first { it == null } }
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
        insertActivityInterval(
            walkId,
            startTimestamp = 15_000L,
            endTimestamp = 25_000L,
            type = ActivityType.MEDITATING,
        )

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
        insertActivityInterval(
            walkId,
            startTimestamp = 5_000L,
            endTimestamp = 25_000L,
            type = ActivityType.MEDITATING,
        )
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
    fun meditationIntervals_filtered_excludesNonMeditationTypes() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertActivityInterval(walkId, startTimestamp = 5_000L, endTimestamp = 15_000L,
            type = ActivityType.MEDITATING)
        insertActivityInterval(walkId, startTimestamp = 20_000L, endTimestamp = 30_000L,
            type = ActivityType.WALKING)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        assertEquals(1, loaded.summary.meditationIntervals.size)
        assertEquals(ActivityType.MEDITATING, loaded.summary.meditationIntervals[0].activityType)
    }

    @Test
    fun walkAnnotations_populated_includesStartEndMeditationVoice() = runTest(dispatcher) {
        val walkId = createFinishedWalk(durationMillis = 60_000L)
        insertRouteSample(walkId, t = 1_000L, lat = 1.0, lng = 1.0)
        insertRouteSample(walkId, t = 30_000L, lat = 2.0, lng = 2.0)
        insertRouteSample(walkId, t = 60_000L, lat = 3.0, lng = 3.0)
        insertActivityInterval(walkId, startTimestamp = 28_000L, endTimestamp = 32_000L,
            type = ActivityType.MEDITATING)
        insertVoiceRecording(walkId, startOffset = 55_000L, durationMillis = 5_000L)

        val vm = newViewModel(walkId)
        val loaded = awaitLoaded(vm)

        val annotations = loaded.summary.walkAnnotations
        assertEquals(4, annotations.size)
        assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.StartPoint })
        assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.EndPoint })
        assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.Meditation })
        assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.VoiceRecording })
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
        val persisted = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
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
        val persistedNull = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
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

        val persisted = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
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

    private suspend fun insertActivityInterval(
        walkId: Long,
        startTimestamp: Long,
        endTimestamp: Long,
        type: ActivityType,
    ) {
        db.activityIntervalDao().insert(
            org.walktalkmeditate.pilgrim.data.entity.ActivityInterval(
                walkId = walkId,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                activityType = type,
            ),
        )
    }

    private suspend fun awaitLoaded(vm: WalkSummaryViewModel): WalkSummaryUiState.Loaded {
        return withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000L) {
                vm.state.first { it is WalkSummaryUiState.Loaded } as WalkSummaryUiState.Loaded
            }
        }
    }

    // UUID-suffixed so parallel test forks can't collide on file path.
    private val hemisphereStoreName: String = "walk-summary-vm-hemisphere-test-${java.util.UUID.randomUUID()}"
}
