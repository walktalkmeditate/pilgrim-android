// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeTranscriptionScheduler
import org.walktalkmeditate.pilgrim.core.threads.AutoTranscriptionSkipReason
import org.walktalkmeditate.pilgrim.core.threads.FakeAutoTranscriptionSkipState
import org.walktalkmeditate.pilgrim.data.FakePreferencesDataStore
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCacheStore
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterDelta
import org.walktalkmeditate.pilgrim.data.collective.CollectiveCounterService
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveStats
import org.walktalkmeditate.pilgrim.data.collective.ContributionLedger
import org.walktalkmeditate.pilgrim.data.collective.MilestoneChecking
import org.walktalkmeditate.pilgrim.data.collective.PostResult
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsCaching
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.widget.WidgetRefreshScheduler

/**
 * U6: the auto-transcription enqueue-site gate added to
 * [WalkFinalizationObserver] — order is autoTranscribe pref, THEN
 * non-empty recordings, THEN [org.walktalkmeditate.pilgrim.core.threads.BatteryGate]
 * (parity spec BEH-82/UI-16): a walk with autoTranscribe off, or with no
 * voice recordings, must never touch the battery gate or the skip-state
 * at all — only a walk that would otherwise actually schedule can be
 * "skipped".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoTranscriptionBatteryGateTest {

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
    private lateinit var fakeCollectiveService: FakeCollectiveCounterServiceForBatteryGate
    private lateinit var collectiveRepository: CollectiveRepository
    private lateinit var widgetRefreshScheduler: NoopWidgetRefreshSchedulerForBatteryGate
    private lateinit var skipState: FakeAutoTranscriptionSkipState
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
        fakeCollectiveService = FakeCollectiveCounterServiceForBatteryGate(context, collectiveJson)
        collectiveScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        collectiveRepository = CollectiveRepository(
            cacheStore = collectiveCacheStore,
            service = fakeCollectiveService,
            scope = collectiveScope,
            milestoneChecker = NoopMilestoneCheckerForBatteryGate,
            contributionLedger = ContributionLedger(FakePreferencesDataStore(), collectiveJson),
        )
        widgetRefreshScheduler = NoopWidgetRefreshSchedulerForBatteryGate()
        skipState = FakeAutoTranscriptionSkipState()

        stateFlow = MutableStateFlow(WalkState.Idle)
        observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Default to a healthy, high battery level so tests opt IN to the
        // low-battery condition explicitly rather than depending on
        // whatever the previous test (or no test) left stuck.
        stickBattery(level = 80, scale = 100)
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

    @Suppress("DEPRECATION")
    private fun stickBattery(level: Int, scale: Int, status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING) {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, scale)
            putExtra(BatteryManager.EXTRA_STATUS, status)
        }
        context.sendStickyBroadcast(intent)
    }

    /** A real Walk row — [VoiceRecording] carries a foreign key to it. */
    private suspend fun newWalk(): Long = db.walkDao().insert(org.walktalkmeditate.pilgrim.data.entity.Walk(startTimestamp = 0L))

    private suspend fun insertRecording(walkId: Long) {
        repository.recordVoice(
            VoiceRecording(
                walkId = walkId,
                startTimestamp = 0L,
                endTimestamp = 1_000L,
                durationMillis = 1_000L,
                fileRelativePath = "recordings/w/rec.wav",
                transcription = null,
            ),
        )
    }

    private fun buildObserver(autoTranscribe: Boolean): WalkFinalizationObserver {
        val observer = WalkFinalizationObserver(
            walkState = stateFlow,
            scope = observerScope,
            context = context,
            repository = repository,
            transcriptionScheduler = transcriptionScheduler,
            hemisphereRepository = hemisphereRepo,
            collectiveRepository = collectiveRepository,
            widgetRefreshScheduler = widgetRefreshScheduler,
            voicePreferences = FakeVoicePreferencesRepository(initialAutoTranscribe = autoTranscribe),
            walkMetricsCache = NoopWalkMetricsCacheForBatteryGate,
            autoTranscriptionSkipState = skipState,
        )
        Thread.sleep(COLLECTOR_ATTACH_WAIT_MS)
        return observer
    }

    private fun finishWalk(walkId: Long) {
        stateFlow.value = WalkState.Active(WalkAccumulator(walkId = walkId, startedAt = 0L))
        stateFlow.value = WalkState.Finished(
            WalkAccumulator(walkId = walkId, startedAt = 0L, distanceMeters = 100.0),
            endedAt = 1_000L,
        )
        Thread.sleep(WAIT_FOR_GRACE_MS)
    }

    @Test
    fun `autoTranscribe off never consults the battery gate or the skip-state`() = runBlocking {
        stickBattery(level = 1, scale = 100) // pathologically low — must never even be read
        buildObserver(autoTranscribe = false)
        val walkId = newWalk()
        insertRecording(walkId)

        finishWalk(walkId)

        assertEquals(emptyList<Long>(), transcriptionScheduler.scheduledWalkIds)
        assertNull(skipState.skipReason.value)
        assertEquals(0, skipState.setSkippedCalls)
    }

    @Test
    fun `autoTranscribe on with no recordings never consults the battery gate`() = runBlocking {
        stickBattery(level = 1, scale = 100) // pathologically low — must never even be read
        buildObserver(autoTranscribe = true)
        val walkId = newWalk()
        // No recordings inserted for this walk.

        finishWalk(walkId)

        assertEquals(emptyList<Long>(), transcriptionScheduler.scheduledWalkIds)
        assertNull(skipState.skipReason.value)
        assertEquals(0, skipState.setSkippedCalls)
    }

    @Test
    fun `autoTranscribe on, recordings present, battery healthy - schedules and never sets the skip reason`() =
        runBlocking {
            stickBattery(level = 80, scale = 100)
            buildObserver(autoTranscribe = true)
            val walkId = newWalk()
            insertRecording(walkId)

            finishWalk(walkId)

            assertEquals(listOf(walkId), transcriptionScheduler.scheduledWalkIds)
            assertNull(skipState.skipReason.value)
            assertEquals(0, skipState.setSkippedCalls)
        }

    @Test
    fun `autoTranscribe on, recordings present, battery low - skips scheduling and sets lowBattery`() = runBlocking {
        stickBattery(level = 10, scale = 100)
        buildObserver(autoTranscribe = true)
        val walkId = newWalk()
        insertRecording(walkId)

        finishWalk(walkId)

        assertEquals(emptyList<Long>(), transcriptionScheduler.scheduledWalkIds)
        assertEquals(AutoTranscriptionSkipReason.LowBattery, skipState.skipReason.value)
        assertEquals(1, skipState.setSkippedCalls)
    }

    @Test
    fun `exactly 20 percent battery - boundary is exclusive, still skips`() = runBlocking {
        stickBattery(level = 20, scale = 100)
        buildObserver(autoTranscribe = true)
        val walkId = newWalk()
        insertRecording(walkId)

        finishWalk(walkId)

        assertEquals(emptyList<Long>(), transcriptionScheduler.scheduledWalkIds)
        assertEquals(AutoTranscriptionSkipReason.LowBattery, skipState.skipReason.value)
    }

    @Test
    fun `low battery while charging still schedules - charging always allows`() = runBlocking {
        stickBattery(level = 5, scale = 100, status = BatteryManager.BATTERY_STATUS_CHARGING)
        buildObserver(autoTranscribe = true)
        val walkId = newWalk()
        insertRecording(walkId)

        finishWalk(walkId)

        assertEquals(listOf(walkId), transcriptionScheduler.scheduledWalkIds)
        assertNull(skipState.skipReason.value)
    }

    private companion object {
        const val HEMISPHERE_STORE_NAME = "test_hemisphere_finalize_battery_gate"
        const val WAIT_FOR_GRACE_MS = 3_000L
        const val COLLECTOR_ATTACH_WAIT_MS = 300L
    }
}

private class NoopWidgetRefreshSchedulerForBatteryGate : WidgetRefreshScheduler {
    override fun scheduleRefresh() = Unit
    override fun scheduleMidnightRefresh() = Unit
}

private object NoopWalkMetricsCacheForBatteryGate : WalkMetricsCaching {
    override suspend fun computeAndPersist(walkId: Long) = Unit
}

private class FakeCollectiveCounterServiceForBatteryGate(
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

private object NoopMilestoneCheckerForBatteryGate : MilestoneChecking {
    override suspend fun check(totalWalks: Int) = Unit
}
