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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
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
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
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

    private fun newViewModel(walkId: Long) = WalkSummaryViewModel(
        repository = repository,
        playback = playback,
        sweeper = sweeper,
        hemisphereRepository = hemisphereRepo,
        savedStateHandle = SavedStateHandle(mapOf("walkId" to walkId)),
    )

    @After
    fun tearDown() {
        db.close()
        hemisphereScope.coroutineContext[Job]?.cancel()
        context.preferencesDataStoreFile(HEMISPHERE_STORE_NAME).delete()
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

    private companion object {
        const val HEMISPHERE_STORE_NAME = "walk-summary-vm-hemisphere-test"
    }
}
