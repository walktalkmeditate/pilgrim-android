// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCacheStore
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterDelta
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterService
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.collective.PostResult
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.widget.WidgetRefreshScheduler

/**
 * Tests the post-finish side-effect bundle that Stage 9-B moved out of
 * [org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel.finishWalk] so the
 * notification-Finish path gets the same treatment as the in-app path.
 *
 * Wall-clock timing: the observer runs side-effects synchronously on
 * Finished (no fixed grace delay since I-1's removal of the VM-side
 * auto-stop race). Tests poll up to 1.5 s for the launched coroutines
 * to complete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkFinalizationObserverTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var transcriptionScheduler: FakeTranscriptionScheduler
    private lateinit var hemisphereDataStore: DataStore<Preferences>
    private lateinit var hemisphereRepo: HemisphereRepository
    private lateinit var hemisphereScope: CoroutineScope
    private lateinit var collectiveDataStoreScope: CoroutineScope
    private lateinit var collectiveDataStore: DataStore<Preferences>
    private lateinit var collectiveCacheStore: CollectiveCacheStore
    private lateinit var collectiveScope: CoroutineScope
    private lateinit var fakeCollectiveService: FakeCollectiveCounterService
    private lateinit var collectiveRepository: CollectiveRepository
    private lateinit var widgetRefreshScheduler: CountingWidgetRefreshScheduler
    private lateinit var stateFlow: MutableStateFlow<WalkState>
    private lateinit var observerScope: CoroutineScope
    private lateinit var observer: WalkFinalizationObserver

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
        transcriptionScheduler = FakeTranscriptionScheduler()
        context.preferencesDataStoreFile(HEMISPHERE_STORE_NAME).delete()
        hemisphereDataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(HEMISPHERE_STORE_NAME) },
        )
        hemisphereScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hemisphereRepo = HemisphereRepository(hemisphereDataStore, FakeLocationSource(), hemisphereScope)

        val unique = "test_collective_${java.util.UUID.randomUUID()}"
        collectiveDataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        collectiveDataStore = PreferenceDataStoreFactory.create(
            scope = collectiveDataStoreScope,
            produceFile = { context.preferencesDataStoreFile(unique) },
        )
        val collectiveJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
        collectiveCacheStore = CollectiveCacheStore(collectiveDataStore, collectiveJson)
        fakeCollectiveService = FakeCollectiveCounterService(context, collectiveJson)
        collectiveScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        collectiveRepository = CollectiveRepository(
            cacheStore = collectiveCacheStore,
            service = fakeCollectiveService,
            scope = collectiveScope,
        )
        widgetRefreshScheduler = CountingWidgetRefreshScheduler()

        stateFlow = MutableStateFlow(WalkState.Idle)
        observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        observer = WalkFinalizationObserver(
            walkState = stateFlow,
            scope = observerScope,
            repository = repository,
            transcriptionScheduler = transcriptionScheduler,
            hemisphereRepository = hemisphereRepo,
            collectiveRepository = collectiveRepository,
            widgetRefreshScheduler = widgetRefreshScheduler,
        )
        // The observer's `init { scope.launch { walkState.collect } }`
        // attaches asynchronously on Dispatchers.IO. If a test mutates
        // stateFlow.value before that collector attaches, the collector's
        // first observed value is the LATEST set (StateFlow conflation),
        // and the firstEmission skip eats it — side-effects never fire.
        // Sleep briefly so the collector definitely attaches and consumes
        // the initial Idle value.
        Thread.sleep(COLLECTOR_ATTACH_WAIT_MS)
    }

    @After
    fun tearDown() {
        observerScope.coroutineContext[Job]?.cancel()
        hemisphereScope.coroutineContext[Job]?.cancel()
        collectiveScope.coroutineContext[Job]?.cancel()
        collectiveDataStoreScope.cancel()
        db.close()
        context.preferencesDataStoreFile(HEMISPHERE_STORE_NAME).delete()
    }

    @Test
    fun `Idle initial emission does not fire side-effects`() = runBlocking {
        // Wait beyond GRACE so any spurious launch would have completed.
        Thread.sleep(WAIT_FOR_GRACE_MS)
        assertEquals(0, widgetRefreshScheduler.callCount)
        assertEquals(0, transcriptionScheduler.scheduledWalkIds.size)
    }

    @Test
    fun `Active to Finished transition fires all four side-effects`() = runBlocking {
        val walkId = 42L
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(
                walkId = walkId,
                startedAt = 0L,
                distanceMeters = 1_500.0,
                totalMeditatedMillis = 60_000L,
            ),
            endedAt = 5_000L,
        )
        Thread.sleep(WAIT_FOR_GRACE_MS)
        assertEquals(listOf(walkId), transcriptionScheduler.scheduledWalkIds)
        assertEquals(1, widgetRefreshScheduler.callCount)
    }

    @Test
    fun `repeated Finished emission for same walkId only fires side-effects once`() = runBlocking {
        val walkId = 99L
        val active = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        val finished = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 100.0),
            endedAt = 1_000L,
        )
        stateFlow.value = active
        stateFlow.value = finished
        // StateFlow conflates equal emissions, but force a second
        // Finished by toggling through Active and back. Tests the
        // dedup-by-walkId guard.
        stateFlow.value = active
        stateFlow.value = finished
        Thread.sleep(WAIT_FOR_GRACE_MS)
        assertEquals(
            "transcription scheduled exactly once per walkId",
            listOf(walkId),
            transcriptionScheduler.scheduledWalkIds,
        )
        assertEquals(
            "widget refresh enqueued exactly once per walkId",
            1,
            widgetRefreshScheduler.callCount,
        )
    }

    @Test
    fun `collective recordWalk includes talkMin from voiceRecordingsFor`() = runBlocking {
        collectiveCacheStore.setOptIn(true)
        collectiveRepository.optIn.first { it }
        val walkId = repository.startWalk(startTimestamp = 0L, intention = null).id
        repository.recordVoice(
            VoiceRecording(
                walkId = walkId,
                startTimestamp = 1_000L,
                endTimestamp = 121_000L,
                durationMillis = 120_000L, // 2 minutes → talkMin = 2
                fileRelativePath = "fake.wav",
            ),
        )
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(
                walkId = walkId,
                startedAt = 0L,
                distanceMeters = 2_500.0,
                totalMeditatedMillis = 180_000L, // 3 minutes
            ),
            endedAt = 200_000L,
        )
        val deadline = System.currentTimeMillis() + 3_000L
        while (fakeCollectiveService.recordedPosts.isEmpty() &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50L)
        }
        assertEquals(1, fakeCollectiveService.recordedPosts.size)
        val posted = fakeCollectiveService.recordedPosts.single()
        assertEquals(1, posted.walks)
        assertTrue("expected non-zero distance, got ${posted.distanceKm}", posted.distanceKm > 0.0)
        assertEquals(3, posted.meditationMin)
        assertEquals(2, posted.talkMin)
    }

    // Voice auto-stop on Finished moved to WalkLifecycleObserver in
    // Stage 9.5-C — see WalkLifecycleObserverTest for the equivalent
    // assertion (Active→Finished stops + commits the row).

    @Test
    fun `collective is not POSTed when opt-in is OFF`() = runBlocking {
        // Default opt-in is OFF — the repo gates the actual POST.
        val walkId = 17L
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 100.0),
            endedAt = 1_000L,
        )
        Thread.sleep(WAIT_FOR_GRACE_MS)
        assertTrue(
            "collective should not POST when opt-in OFF; recorded=${fakeCollectiveService.recordedPosts}",
            fakeCollectiveService.recordedPosts.isEmpty(),
        )
        // The other side-effects still fire.
        assertEquals(1, widgetRefreshScheduler.callCount)
    }

    private companion object {
        const val HEMISPHERE_STORE_NAME = "test_hemisphere_finalize"
        // Cushion for VOICE_INSERT_GRACE_MS (200 ms) + collective-repo's
        // launched POST coroutine + any CI thread contention.
        const val WAIT_FOR_GRACE_MS = 1_500L
        // The observer's collector attaches asynchronously on
        // Dispatchers.IO. Removing this delay (or shortening it
        // significantly) WILL break tests on CI as the collector
        // misses its first emission via StateFlow conflation. Verified
        // against the regression — do not lower without re-testing.
        const val COLLECTOR_ATTACH_WAIT_MS = 300L
    }
}

private class CountingWidgetRefreshScheduler : WidgetRefreshScheduler {
    @Volatile var callCount: Int = 0
    override fun scheduleRefresh() {
        callCount += 1
    }
    override fun scheduleMidnightRefresh() = Unit
}

private class FakeCollectiveCounterService(
    context: Context,
    json: Json,
) : CollectiveCounterService(
    client = OkHttpClient(),
    json = json,
    deviceTokenStore = DeviceTokenStore(context),
    baseUrl = "http://localhost",
) {
    var fetchResult: CollectiveStats = CollectiveStats(0, 0.0, 0, 0)
    var postResult: PostResult = PostResult.Success
    val recordedPosts = mutableListOf<CollectiveCounterDelta>()

    override suspend fun fetch(): CollectiveStats = fetchResult
    override suspend fun post(delta: CollectiveCounterDelta): PostResult {
        recordedPosts += delta
        return postResult
    }
}
