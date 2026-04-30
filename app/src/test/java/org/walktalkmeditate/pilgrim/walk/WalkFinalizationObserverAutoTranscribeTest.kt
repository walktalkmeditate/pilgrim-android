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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsCaching
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.widget.WidgetRefreshScheduler

/**
 * Stage 10-D: tests the autoTranscribe gate added to
 * [WalkFinalizationObserver]. The pref reflects iOS parity (default OFF for
 * fresh installs, ON for upgraders via the migration in Task 1). Each test
 * exercises a different pref state at finalize time.
 *
 * Critical case: `autoTranscribe flip mid-finalize uses value at scheduling
 * time` proves the observer reads `voicePreferences.autoTranscribe.value`
 * LIVE when scheduling, not at construction time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkFinalizationObserverAutoTranscribeTest {

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
    private lateinit var fakeCollectiveService: FakeCollectiveCounterServiceForAutoTranscribe
    private lateinit var collectiveRepository: CollectiveRepository
    private lateinit var widgetRefreshScheduler: NoopWidgetRefreshScheduler
    private lateinit var stateFlow: MutableStateFlow<WalkState>
    private lateinit var observerScope: CoroutineScope

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
        fakeCollectiveService = FakeCollectiveCounterServiceForAutoTranscribe(context, collectiveJson)
        collectiveScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        collectiveRepository = CollectiveRepository(
            cacheStore = collectiveCacheStore,
            service = fakeCollectiveService,
            scope = collectiveScope,
        )
        widgetRefreshScheduler = NoopWidgetRefreshScheduler()

        stateFlow = MutableStateFlow(WalkState.Idle)
        observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    private fun buildObserver(voicePrefs: FakeVoicePreferencesRepository): WalkFinalizationObserver {
        val observer = WalkFinalizationObserver(
            walkState = stateFlow,
            scope = observerScope,
            repository = repository,
            transcriptionScheduler = transcriptionScheduler,
            hemisphereRepository = hemisphereRepo,
            collectiveRepository = collectiveRepository,
            widgetRefreshScheduler = widgetRefreshScheduler,
            voicePreferences = voicePrefs,
            walkMetricsCache = NoopWalkMetricsCache,
        )
        // Sleep so the IO-attached collector consumes the initial Idle
        // before we start mutating stateFlow. Same pattern + value as the
        // sibling WalkFinalizationObserverTest.
        Thread.sleep(COLLECTOR_ATTACH_WAIT_MS)
        return observer
    }

    @Test
    fun `autoTranscribe = true schedules transcription`() = runBlocking {
        val voicePrefs = FakeVoicePreferencesRepository(initialAutoTranscribe = true)
        buildObserver(voicePrefs)
        val walkId = 11L
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 100.0),
            endedAt = 1_000L,
        )
        Thread.sleep(WAIT_FOR_GRACE_MS)
        assertEquals(listOf(walkId), transcriptionScheduler.scheduledWalkIds)
    }

    @Test
    fun `autoTranscribe = false skips scheduling`() = runBlocking {
        val voicePrefs = FakeVoicePreferencesRepository(initialAutoTranscribe = false)
        buildObserver(voicePrefs)
        val walkId = 22L
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 100.0),
            endedAt = 1_000L,
        )
        Thread.sleep(WAIT_FOR_GRACE_MS)
        assertEquals(emptyList<Long>(), transcriptionScheduler.scheduledWalkIds)
    }

    @Test
    fun `runFinalize awaits autoTranscribe disk value, ignores synchronous seed`() = runBlocking {
        // Simulates the Eagerly-seed-vs-disk-load race window: the
        // StateFlow .value reports `false` (the default seed) while
        // the disk-loaded value is `true` (the user's actual pref).
        // The observer must read awaitAutoTranscribe(), NOT .value.
        val voicePrefs = object : VoicePreferencesRepository {
            override val voiceGuideEnabled = MutableStateFlow(false)
            override val autoTranscribe = MutableStateFlow(false)
            override suspend fun setVoiceGuideEnabled(enabled: Boolean) = Unit
            override suspend fun setAutoTranscribe(enabled: Boolean) = Unit
            override suspend fun awaitAutoTranscribe(): Boolean = true
        }
        val observer = WalkFinalizationObserver(
            walkState = stateFlow,
            scope = observerScope,
            repository = repository,
            transcriptionScheduler = transcriptionScheduler,
            hemisphereRepository = hemisphereRepo,
            collectiveRepository = collectiveRepository,
            widgetRefreshScheduler = widgetRefreshScheduler,
            voicePreferences = voicePrefs,
            walkMetricsCache = NoopWalkMetricsCache,
        )
        @Suppress("UNUSED_VARIABLE") val keepAlive = observer
        Thread.sleep(COLLECTOR_ATTACH_WAIT_MS)
        val walkId = 44L
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 100.0),
            endedAt = 1_000L,
        )
        Thread.sleep(WAIT_FOR_GRACE_MS)
        assertEquals(listOf(walkId), transcriptionScheduler.scheduledWalkIds)
    }

    @Test
    fun `autoTranscribe flip mid-finalize uses value at scheduling time`() = runBlocking {
        val voicePrefs = FakeVoicePreferencesRepository(initialAutoTranscribe = false)
        buildObserver(voicePrefs)
        // Flip BEFORE the Finished transition. If the observer captured
        // the construction-time value, this test fails — the gate must
        // read .value live at finalize.
        voicePrefs.setAutoTranscribe(true)
        val walkId = 33L
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 100.0),
            endedAt = 1_000L,
        )
        Thread.sleep(WAIT_FOR_GRACE_MS)
        assertEquals(listOf(walkId), transcriptionScheduler.scheduledWalkIds)
    }

    private companion object {
        const val HEMISPHERE_STORE_NAME = "test_hemisphere_finalize_autotranscribe"
        // Bumped to 3 s — see WalkFinalizationObserverTest companion comment.
        const val WAIT_FOR_GRACE_MS = 3_000L
        const val COLLECTOR_ATTACH_WAIT_MS = 300L
    }
}

private class NoopWidgetRefreshScheduler : WidgetRefreshScheduler {
    override fun scheduleRefresh() = Unit
    override fun scheduleMidnightRefresh() = Unit
}

private object NoopWalkMetricsCache : WalkMetricsCaching {
    override suspend fun computeAndPersist(walkId: Long) = Unit
}

private class FakeCollectiveCounterServiceForAutoTranscribe(
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
