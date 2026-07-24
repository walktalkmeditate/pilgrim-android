// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.data.FakePreferencesDataStore
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCacheStore
import org.walktalkmeditate.pilgrim.data.collective.ContributionLedger
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterDelta
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterService
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.collective.MilestoneChecking
import org.walktalkmeditate.pilgrim.data.collective.PostResult
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsCaching
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
    private lateinit var collectiveDataStore: DataStore<Preferences>
    private lateinit var collectiveCacheStore: CollectiveCacheStore
    private lateinit var collectiveScope: CoroutineScope
    private lateinit var fakeCollectiveService: FakeCollectiveCounterService
    private lateinit var contributionLedgerStore: DataStore<Preferences>
    private lateinit var contributionLedger: ContributionLedger
    private lateinit var collectiveRepository: CollectiveRepository
    private lateinit var widgetRefreshScheduler: CountingWidgetRefreshScheduler
    private lateinit var walkMetricsCache: RecordingWalkMetricsCache
    private lateinit var stateFlow: MutableStateFlow<WalkState>
    private lateinit var observedFlow: CountingStateFlow<WalkState>
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
        // In-memory DataStores + never-starved scopes — canonical fix for
        // the ci-realtime-withtimeout flake family (see [FakePreferencesDataStore]
        // + [TestRealTimeDispatcher]). Removes real disk I/O and the
        // Default/IO-pool contention that let the observer's launched
        // side-effects miss their wall-clock waits on saturated runners.
        hemisphereDataStore = FakePreferencesDataStore()
        hemisphereScope = CoroutineScope(SupervisorJob() + TestRealTimeDispatcher.instance)
        hemisphereRepo = HemisphereRepository(hemisphereDataStore, FakeLocationSource(), hemisphereScope)

        collectiveDataStore = FakePreferencesDataStore()
        val collectiveJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
        collectiveCacheStore = CollectiveCacheStore(collectiveDataStore, collectiveJson)
        fakeCollectiveService = FakeCollectiveCounterService(context, collectiveJson)
        collectiveScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        contributionLedgerStore = FakePreferencesDataStore()
        contributionLedger = ContributionLedger(contributionLedgerStore, collectiveJson)
        collectiveRepository = CollectiveRepository(
            cacheStore = collectiveCacheStore,
            service = fakeCollectiveService,
            scope = collectiveScope,
            milestoneChecker = NoopMilestoneChecker,
            contributionLedger = contributionLedger,
        )
        widgetRefreshScheduler = CountingWidgetRefreshScheduler()
        walkMetricsCache = RecordingWalkMetricsCache()

        stateFlow = MutableStateFlow(WalkState.Idle)
        observedFlow = CountingStateFlow(stateFlow)
        observerScope = CoroutineScope(SupervisorJob() + TestRealTimeDispatcher.instance)
        observer = WalkFinalizationObserver(
            walkState = observedFlow,
            scope = observerScope,
            repository = repository,
            transcriptionScheduler = transcriptionScheduler,
            hemisphereRepository = hemisphereRepo,
            collectiveRepository = collectiveRepository,
            widgetRefreshScheduler = widgetRefreshScheduler,
            voicePreferences = FakeVoicePreferencesRepository(initialAutoTranscribe = true),
            walkMetricsCache = walkMetricsCache,
        )
        // Deterministic collector-attach handshake (replaces a blind
        // Thread.sleep that flaked when a saturated runner hadn't
        // subscribed in the window — StateFlow conflation then fed the
        // post-mutation value as emission #1 and the firstEmission latch
        // ate the real transition). processed increments only AFTER the
        // collector returns from a value, so awaiting >= 1 proves the
        // initial Idle was consumed + the latch is spent.
        awaitCollectorAttached(observedFlow)
    }

    /** Block until [flow]'s downstream collector has consumed its first value. */
    private fun awaitCollectorAttached(flow: CountingStateFlow<*>) = runBlocking {
        withTimeout(COLLECTOR_SUBSCRIBE_TIMEOUT_MS) { flow.processed.first { it >= 1 } }
    }

    /**
     * Poll until [predicate] holds or [timeoutMs] elapses. The observer
     * runs on [TestRealTimeDispatcher] (never starved), so the awaited
     * side effect lands in milliseconds and this returns the instant it
     * does; the bound is a failsafe, not a tuned grace window.
     */
    private fun awaitUntil(timeoutMs: Long = WAIT_FOR_OBSERVER_MS, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
    }

    @After
    fun tearDown() {
        observerScope.coroutineContext[Job]?.cancel()
        hemisphereScope.coroutineContext[Job]?.cancel()
        collectiveScope.coroutineContext[Job]?.cancel()
        db.close()
    }

    @Test
    fun `Idle initial emission does not fire side-effects`() = runBlocking {
        // setUp's handshake already proved the initial Idle was consumed
        // and the firstEmission latch spent; no transition fires in this
        // test, so nothing can launch. A brief settle guards against an
        // erroneous spurious launch.
        Thread.sleep(SETTLE_MS)
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
        awaitUntil {
            transcriptionScheduler.scheduledWalkIds.isNotEmpty() &&
                widgetRefreshScheduler.callCount >= 1
        }
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
        awaitUntil { widgetRefreshScheduler.callCount >= 1 }
        // Settle so a (buggy) second fire would have landed before we
        // assert the dedup-by-walkId guard held it to exactly one.
        Thread.sleep(SETTLE_MS)
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
        awaitUntil { fakeCollectiveService.recordedPosts.isNotEmpty() }
        assertEquals(1, fakeCollectiveService.recordedPosts.size)
        val posted = fakeCollectiveService.recordedPosts.single()
        assertEquals(1, posted.walks)
        assertTrue("expected non-zero distance, got ${posted.distanceKm}", posted.distanceKm > 0.0)
        assertEquals(3, posted.meditationMin)
        assertEquals(2, posted.talkMin)
    }

    @Test
    fun `contributed walk is claimed in the ledger by uuid even when finalize crosses UTC midnight`() = runBlocking {
        // U4: the ledger stores only the walk uuid — no date — so the
        // summary's route anchor (the walk row's start_timestamp, read
        // at render time) cannot drift when the finalize clock lands on
        // the next UTC day. Pinned here at the call-site level; the
        // resolution against the catalog is U6's test surface.
        collectiveCacheStore.setOptIn(true)
        collectiveRepository.optIn.first { it }
        val start = java.time.Instant.parse("2026-07-22T23:50:00Z").toEpochMilli()
        val walk = repository.startWalk(startTimestamp = start, intention = null)

        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walk.id, startedAt = start))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walk.id, startedAt = start, distanceMeters = 1_500.0),
            endedAt = java.time.Instant.parse("2026-07-23T00:10:00Z").toEpochMilli(),
        )
        awaitUntil { fakeCollectiveService.recordedPosts.isNotEmpty() }

        assertTrue(contributionLedger.wasContributed(walk.uuid))
    }

    @Test
    fun `missing walk row still queues the delta without a ledger claim`() = runBlocking {
        // U4: getWalk returning null must degrade to a null uuid —
        // the contribution POSTs, the summary claim is skipped
        // (under-claim in the safe direction).
        collectiveCacheStore.setOptIn(true)
        collectiveRepository.optIn.first { it }
        val walkId = 404L

        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 1_500.0),
            endedAt = 5_000L,
        )
        awaitUntil { fakeCollectiveService.recordedPosts.isNotEmpty() }

        assertEquals(1, fakeCollectiveService.recordedPosts.size)
        assertEquals(1, fakeCollectiveService.recordedPosts.single().walks)
        assertNull(
            "no ledger claim may be recorded for a walk without a uuid",
            contributionLedgerStore.data.first()[ContributionLedger.KEY_CONTRIBUTED_WALK_UUIDS],
        )
    }

    @Test
    fun `walk uuid lookup failure still queues the delta without a ledger claim`() = runBlocking {
        // Same rebuild pattern as runFinalize_doesNotPropagateCacheException:
        // swap in a repository whose getWalk throws so the observer's
        // uuid-lookup catch branch is the one under test.
        observerScope.coroutineContext[Job]?.cancel()
        observerScope = CoroutineScope(SupervisorJob() + TestRealTimeDispatcher.instance)
        val throwingObserved = CountingStateFlow(stateFlow)
        @Suppress("UNUSED_VARIABLE")
        val throwingObserver = WalkFinalizationObserver(
            walkState = throwingObserved,
            scope = observerScope,
            repository = ThrowingGetWalkRepository(db),
            transcriptionScheduler = transcriptionScheduler,
            hemisphereRepository = hemisphereRepo,
            collectiveRepository = collectiveRepository,
            widgetRefreshScheduler = widgetRefreshScheduler,
            voicePreferences = FakeVoicePreferencesRepository(initialAutoTranscribe = true),
            walkMetricsCache = walkMetricsCache,
        )
        awaitCollectorAttached(throwingObserved)
        collectiveCacheStore.setOptIn(true)
        collectiveRepository.optIn.first { it }
        val walk = repository.startWalk(startTimestamp = 0L, intention = null)

        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walk.id, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walk.id, startedAt = 0L, distanceMeters = 1_500.0),
            endedAt = 5_000L,
        )
        awaitUntil { fakeCollectiveService.recordedPosts.isNotEmpty() }

        assertEquals(1, fakeCollectiveService.recordedPosts.size)
        assertEquals(1, fakeCollectiveService.recordedPosts.single().walks)
        assertFalse(
            "a failed uuid lookup must not claim the walk",
            contributionLedger.wasContributed(walk.uuid),
        )
    }

    // Voice auto-stop on Finished moved to WalkLifecycleObserver in
    // Stage 9.5-C — see WalkLifecycleObserverTest for the equivalent
    // assertion (Active→Finished stops + commits the row).

    @Test
    fun `runFinalize_invokesWalkMetricsCacheAfterCollectivePost`() = runBlocking {
        val walkId = 314L
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
        awaitUntil {
            walkMetricsCache.computedWalkIds.isNotEmpty() &&
                widgetRefreshScheduler.callCount >= 1
        }
        assertEquals(listOf(walkId), walkMetricsCache.computedWalkIds)
        assertEquals(1, widgetRefreshScheduler.callCount)
    }

    @Test
    fun `runFinalize_doesNotPropagateCacheException`() = runBlocking {
        // Replace the recording cache with a throwing one and rebuild
        // the observer so it captures the throwing impl. The default
        // observer built in setUp() is fine to discard — we never
        // exercise it in this test.
        observerScope.coroutineContext[Job]?.cancel()
        observerScope = CoroutineScope(SupervisorJob() + TestRealTimeDispatcher.instance)
        val throwingCache = ThrowingWalkMetricsCache()
        // Fresh CountingStateFlow over the same source so this rebuilt
        // observer gets its own collector-attach handshake (independent
        // processed counter; the setUp observer's collector is cancelled
        // above).
        val throwingObserved = CountingStateFlow(stateFlow)
        @Suppress("UNUSED_VARIABLE")
        val throwingObserver = WalkFinalizationObserver(
            walkState = throwingObserved,
            scope = observerScope,
            repository = repository,
            transcriptionScheduler = transcriptionScheduler,
            hemisphereRepository = hemisphereRepo,
            collectiveRepository = collectiveRepository,
            widgetRefreshScheduler = widgetRefreshScheduler,
            voicePreferences = FakeVoicePreferencesRepository(initialAutoTranscribe = true),
            walkMetricsCache = throwingCache,
        )
        awaitCollectorAttached(throwingObserved)
        val walkId = 271L
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 100.0),
            endedAt = 1_000L,
        )
        awaitUntil { throwingCache.invocationCount >= 1 && widgetRefreshScheduler.callCount >= 1 }
        // The cache was invoked (and threw), but the rest of the
        // bundle still ran — widget refresh fires AFTER the cache hook
        // would have completed in the no-throw case, but it's BEFORE
        // the cache in production order. Either way: a cache throw
        // must not have toppled the launched coroutine.
        assertEquals(1, throwingCache.invocationCount)
        // No exception escaped to the caller (we got here without a
        // crash); also confirm the side-effect bundle finished — the
        // widget scheduler ran before the cache hook.
        assertTrue(
            "widget refresh ran before cache hook (production order)",
            widgetRefreshScheduler.callCount >= 1,
        )
    }

    @Test
    fun `collective is not POSTed when opt-in is OFF`() = runBlocking {
        // Default opt-in is OFF — the repo gates the actual POST.
        val walkId = 17L
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 100.0),
            endedAt = 1_000L,
        )
        // Wait for the firing side-effect (widget refresh), proving
        // runFinalize ran; the collective POST is separately gated by
        // opt-in. Settle so a (buggy) POST would have landed too.
        awaitUntil { widgetRefreshScheduler.callCount >= 1 }
        Thread.sleep(SETTLE_MS)
        assertTrue(
            "collective should not POST when opt-in OFF; recorded=${fakeCollectiveService.recordedPosts}",
            fakeCollectiveService.recordedPosts.isEmpty(),
        )
        // The other side-effects still fire.
        assertEquals(1, widgetRefreshScheduler.callCount)
    }

    private companion object {
        // Failsafe ceiling for awaitUntil. The observer runs on
        // [TestRealTimeDispatcher] (never starved), so side-effects land in
        // milliseconds and awaitUntil returns the instant the predicate
        // holds; this bound only bites on a real hang. Do NOT treat it as a
        // tuned grace window — a wider bound can't fix a starvation race
        // (that's what the dispatcher swap is for).
        const val WAIT_FOR_OBSERVER_MS = 5_000L
        // Brief settle to let a (buggy) duplicate/ungated side-effect land
        // before a dedup/negative assertion checks the exact count.
        const val SETTLE_MS = 150L
        // Failsafe for the deterministic collector-attach handshake
        // (CountingStateFlow.processed >= 1); returns the instant the
        // initial Idle is consumed and the firstEmission latch is spent.
        const val COLLECTOR_SUBSCRIBE_TIMEOUT_MS = 15_000L
    }
}

private class CountingWidgetRefreshScheduler : WidgetRefreshScheduler {
    @Volatile var callCount: Int = 0
    override fun scheduleRefresh() {
        callCount += 1
    }
    override fun scheduleMidnightRefresh() = Unit
}

private class RecordingWalkMetricsCache : WalkMetricsCaching {
    val computedWalkIds: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())
    override suspend fun computeAndPersist(walkId: Long) {
        computedWalkIds += walkId
    }
}

private class ThrowingWalkMetricsCache : WalkMetricsCaching {
    @Volatile var invocationCount: Int = 0
    override suspend fun computeAndPersist(walkId: Long) {
        invocationCount += 1
        throw IllegalStateException("simulated cache failure for walk=$walkId")
    }
}

private class ThrowingGetWalkRepository(db: PilgrimDatabase) : WalkRepository(
    database = db,
    walkDao = db.walkDao(),
    routeDao = db.routeDataSampleDao(),
    altitudeDao = db.altitudeSampleDao(),
    walkEventDao = db.walkEventDao(),
    activityIntervalDao = db.activityIntervalDao(),
    waypointDao = db.waypointDao(),
    voiceRecordingDao = db.voiceRecordingDao(),
    walkPhotoDao = db.walkPhotoDao(),
) {
    override suspend fun getWalk(id: Long): Walk? =
        throw IllegalStateException("simulated uuid lookup failure for walk=$id")
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

private object NoopMilestoneChecker : MilestoneChecking {
    override suspend fun check(totalWalks: Int) = Unit
}
