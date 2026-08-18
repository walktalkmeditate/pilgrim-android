// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeShareAudioTranscoder
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.share.SharePhotoEncoder
import org.walktalkmeditate.pilgrim.data.share.SharePrepStore
import org.walktalkmeditate.pilgrim.data.share.ShareRepairStore
import org.walktalkmeditate.pilgrim.data.share.ShareService
import org.walktalkmeditate.pilgrim.data.share.TourPhotoExporter
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkShareViewModelTest {

    private lateinit var context: Application
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var server: MockWebServer
    private lateinit var service: ShareService
    private lateinit var cachedStore: CachedShareStore
    private lateinit var prepStore: SharePrepStore
    private lateinit var manifestScope: CoroutineScope
    private lateinit var manifestService: AudioManifestService
    private val dispatcher = UnconfinedTestDispatcher()
    private val nextTs = AtomicLong(1_700_000_000_000L)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Fold-in dependency (FOLD-4): this file's scenarios are all
     * classic (Interactive never toggled on, matching the file's own
     * pre-existing convention documented on [vm]), so no test ever
     * reads through this — it stays at the walker's default "no
     * soundscape selected".
     */
    private val selectedSoundscapeId = MutableStateFlow<String?>(null)

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
        server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        service = ShareService(
            client = client,
            json = json,
            deviceTokenStore = DeviceTokenStore(context),
            baseUrl = server.url("").toString().trimEnd('/'),
            repairStore = ShareRepairStore(context, json),
        )
        cachedStore = CachedShareStore(context, json)
        prepStore = SharePrepStore(context, FakeShareAudioTranscoder(), VoiceRecordingFileSystem(context))
        // Unseeded (no local manifest cache file) — assets stay empty,
        // which is exactly this file's "no soundscape resolvable" needs.
        manifestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manifestService = AudioManifestService(
            context = context,
            httpClient = client,
            json = json,
            scope = manifestScope,
            manifestUrl = server.url("/manifest.json").toString(),
        )
    }

    @After
    fun tearDown() {
        manifestScope.cancel()
        server.shutdown()
        db.close()
        Dispatchers.resetMain()
        File(context.filesDir, "datastore/share_device_token.preferences_pb").delete()
        File(context.filesDir, "datastore/share_cache.preferences_pb").delete()
        File(context.filesDir, "datastore/share_repair.preferences_pb").delete()
        File(context.cacheDir, "share-prep").deleteRecursively()
    }

    private val fakePhotoEncoder = object : SharePhotoEncoder {
        override fun encodeBase64(uriString: String): String? = "BASE64:$uriString"
    }

    private fun vm(walkId: Long): WalkShareViewModel = WalkShareViewModel(
        context = context,
        repository = repository,
        shareService = service,
        cachedShareStore = cachedStore,
        photoEncoder = fakePhotoEncoder,
        // Phase 19 dependencies: this file's scenarios are all classic
        // (Interactive never toggled on), so none of the three is ever
        // reached — they are constructed rather than faked so the
        // classic path is proven against the REAL collaborators it will
        // have in production, which is the whole point of the AE1
        // "interactive-off touches nothing new" guarantee.
        sharePrepStore = prepStore,
        tourPhotoExporter = TourPhotoExporter(context, prepStore),
        shareRepairStore = ShareRepairStore(context, json),
        selectedSoundscapeId = selectedSoundscapeId,
        audioManifestService = manifestService,
        unitsPreferences = FakeUnitsPreferencesRepository(),
        savedStateHandle = SavedStateHandle(mapOf(WalkShareViewModel.ARG_WALK_ID to walkId)),
    )

    /**
     * [events] carry timestamps as OFFSETS from the walk's (dynamic,
     * per-test) start — mirroring the route samples below — and are
     * stamped with the real walkId + absolute timestamp before insert.
     */
    private suspend fun seedWalkWithRoute(events: List<WalkEvent> = emptyList()): Long {
        val walk = repository.startWalk(startTimestamp = nextTs.getAndAdd(60_000L))
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = walk.startTimestamp, latitude = 45.0, longitude = -70.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = walk.startTimestamp + 30_000L, latitude = 45.001, longitude = -70.001),
        )
        events.forEach { e ->
            repository.recordEvent(e.copy(walkId = walk.id, timestamp = walk.startTimestamp + e.timestamp))
        }
        repository.finishWalk(walk, endTimestamp = walk.startTimestamp + 60_000L)
        return walk.id
    }

    @Test
    fun `uiState transitions Loading then Loaded for a seeded walk`() = runTest(dispatcher) {
        val walkId = seedWalkWithRoute()
        val vm = vm(walkId)
        vm.uiState.test(timeout = 10.seconds) {
            // First emission is Loading (initialValue).
            var item = awaitItem()
            while (item is WalkShareUiState.Loading) item = awaitItem()
            assertTrue(item is WalkShareUiState.Loaded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState is NotFound for missing walkId`() = runTest(dispatcher) {
        val vm = vm(walkId = 9_999L)
        vm.uiState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is WalkShareUiState.Loading) item = awaitItem()
            assertEquals(WalkShareUiState.NotFound, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updateJournal silently truncates at 140 chars`() = runTest(dispatcher) {
        val vm = vm(seedWalkWithRoute())
        vm.updateJournal("x".repeat(200))
        assertEquals(140, vm.journal.value.length)
    }

    @Test
    fun `canShare false when all toggles off`() = runTest(dispatcher) {
        val walkId = seedWalkWithRoute()
        val vm = vm(walkId)
        // canShare = combine(_isSharing, _toggledStatsCount, _uiState):
        // all in-memory flows + Room-on-test-dispatcher uiState, no
        // network or DataStore. The prior real-wall-clock hatch
        // (withContext(Default.limitedParallelism(1)){withTimeout})
        // parked the test body on a real thread while the VM's combine
        // collectors advanced on virtual time, so canShare.first{!it}
        // returned on the initial false before the toggle-off
        // propagated and .value then read true — the
        // ci-realtime-withtimeout flake. Await purely in virtual time
        // (gated by predicate), as the sibling uiState test does.
        vm.uiState.first { it is WalkShareUiState.Loaded }
        vm.toggleDistance(false)
        vm.toggleDuration(false)
        vm.toggleElevation(false)
        vm.toggleActivityBreakdown(false)
        vm.toggleSteps(false)
        vm.canShare.first { !it }
        assertEquals(false, vm.canShare.value)
    }

    @Test
    fun `share happy path emits Success and caches the URL`() = runTest(dispatcher) {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"url":"https://walk.pilgrimapp.org/abc123","id":"abc123"}""")
                .addHeader("Content-Type", "application/json"),
        )
        val walkId = seedWalkWithRoute()
        val vm = vm(walkId)
        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(5_000L) { vm.uiState.first { it is WalkShareUiState.Loaded } }
        }
        vm.events.test(timeout = 10.seconds) {
            vm.share()
            val ev = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
                withTimeout(10_000L) { awaitItem() }
            }
            assertTrue("expected Success, got $ev", ev is WalkShareEvent.Success)
            assertEquals("https://walk.pilgrimapp.org/abc123", (ev as WalkShareEvent.Success).url)
            cancelAndIgnoreRemainingEvents()
        }
        // Cached.
        val cached = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(5_000L) { vm.cachedShare.first { it != null } }
        }
        assertEquals("https://walk.pilgrimapp.org/abc123", cached?.url)
    }

    @Test
    fun `share 429 emits RateLimited, does NOT cache, re-enables the Share button`() = runTest(dispatcher) {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val walkId = seedWalkWithRoute()
        val vm = vm(walkId)
        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(5_000L) { vm.uiState.first { it is WalkShareUiState.Loaded } }
        }
        vm.events.test(timeout = 10.seconds) {
            vm.share()
            val ev = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
                withTimeout(10_000L) { awaitItem() }
            }
            assertEquals(WalkShareEvent.RateLimited, ev)
            cancelAndIgnoreRemainingEvents()
        }
        // No cache entry written on rate-limit.
        assertEquals(null, vm.cachedShare.value)
        // isSharing resets via the share() finally block so the user
        // can retry tomorrow (iOS parity — no client-side lockout).
        withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(5_000L) { vm.isSharing.first { !it } }
        }
        assertEquals(false, vm.isSharing.value)
    }

    // --- activity_intervals: derived from walk events, not the dead table ---
    //
    // Regression for the device-QA finding: a walk with a
    // MEDITATION_START/END pair shared an interactive story page with
    // `meditation: []`, because `activity_intervals` has no production
    // writer. loadInputs must derive ShareInputs.activityIntervals from
    // the walk's own event log (deriveActivityIntervals), not the
    // always-empty repository.activityIntervalsFor read.

    @Test
    fun `meditation interval reaches ShareInputs, derived from walk events`() = runTest(dispatcher) {
        val walkId = seedWalkWithRoute(
            events = listOf(
                WalkEvent(walkId = 0L, timestamp = 10_000L, eventType = WalkEventType.MEDITATION_START),
                WalkEvent(walkId = 0L, timestamp = 40_000L, eventType = WalkEventType.MEDITATION_END),
            ),
        )
        val walkStart = repository.getWalk(walkId)!!.startTimestamp
        val vm = vm(walkId)
        val loaded = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(5_000L) {
                vm.uiState.first { it is WalkShareUiState.Loaded } as WalkShareUiState.Loaded
            }
        }

        val meditation = loaded.inputs.activityIntervals.filter { it.activityType == ActivityType.MEDITATING }
        assertEquals(1, meditation.size)
        assertEquals(walkStart + 10_000L, meditation[0].startTimestamp)
        assertEquals(walkStart + 40_000L, meditation[0].endTimestamp)
    }

    @Test
    fun `dangling MEDITATION_START with no END is closed at the walk's end timestamp`() = runTest(dispatcher) {
        val walkId = seedWalkWithRoute(
            events = listOf(
                WalkEvent(walkId = 0L, timestamp = 50_000L, eventType = WalkEventType.MEDITATION_START),
            ),
        )
        val walk = repository.getWalk(walkId)!!
        val vm = vm(walkId)
        val loaded = withContext(org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher.instance) {
            withTimeout(5_000L) {
                vm.uiState.first { it is WalkShareUiState.Loaded } as WalkShareUiState.Loaded
            }
        }

        val meditation = loaded.inputs.activityIntervals.filter { it.activityType == ActivityType.MEDITATING }
        assertEquals(1, meditation.size)
        assertEquals(walk.startTimestamp + 50_000L, meditation[0].startTimestamp)
        assertEquals(walk.endTimestamp, meditation[0].endTimestamp)
    }
}
