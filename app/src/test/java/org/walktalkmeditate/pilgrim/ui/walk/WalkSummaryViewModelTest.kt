// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        context.preferencesDataStoreFile(HEMISPHERE_STORE_NAME).delete()
        hemisphereDataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(HEMISPHERE_STORE_NAME) },
        )
        hemisphereLocation = FakeLocationSource()
        hemisphereScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hemisphereRepo = HemisphereRepository(hemisphereDataStore, hemisphereLocation, hemisphereScope)
    }

    private lateinit var photoAnalysisScheduler: org.walktalkmeditate.pilgrim.data.photo.FakePhotoAnalysisScheduler

    private fun newViewModel(walkId: Long): WalkSummaryViewModel {
        photoAnalysisScheduler = org.walktalkmeditate.pilgrim.data.photo.FakePhotoAnalysisScheduler()
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val cachedShareStore = org.walktalkmeditate.pilgrim.data.share.CachedShareStore(context, json)
        return WalkSummaryViewModel(
            context = context,
            repository = repository,
            playback = playback,
            sweeper = sweeper,
            photoAnalysisScheduler = photoAnalysisScheduler,
            hemisphereRepository = hemisphereRepo,
            cachedShareStore = cachedShareStore,
            savedStateHandle = SavedStateHandle(mapOf("walkId" to walkId)),
        )
    }

    @After
    fun tearDown() {
        db.close()
        hemisphereScope.coroutineContext[Job]?.cancel()
        context.preferencesDataStoreFile(HEMISPHERE_STORE_NAME).delete()
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

        vm.state.test {
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

        vm.state.test {
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

        vm.state.test {
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

        vm.state.test {
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

        vm.state.test {
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

        vm.state.test {
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

        vm.state.test {
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
        vm.state.test {
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
        )
    }

    @Test
    fun `shareEtegami emits DispatchShare with an image-png chooser Intent`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 5_000_000L)
        repository.finishWalk(walk, endTimestamp = 5_600_000L)
        val vm = newViewModel(walkId = walk.id)

        vm.etegamiEvents.test {
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

        vm.etegamiEvents.test {
            vm.notifyEtegamiSaveNeedsPermission()
            assertEquals(
                WalkSummaryViewModel.EtegamiShareEvent.SaveNeedsPermission,
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val HEMISPHERE_STORE_NAME = "walk-summary-vm-hemisphere-test"
    }
}
