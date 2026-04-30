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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
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
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.share.ShareService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkShareViewModelTest {

    private lateinit var context: Application
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var server: MockWebServer
    private lateinit var service: ShareService
    private lateinit var cachedStore: CachedShareStore
    private val dispatcher = UnconfinedTestDispatcher()
    private val nextTs = AtomicLong(1_700_000_000_000L)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

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
        )
        cachedStore = CachedShareStore(context, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        Dispatchers.resetMain()
        File(context.filesDir, "datastore/share_device_token.preferences_pb").delete()
        File(context.filesDir, "datastore/share_cache.preferences_pb").delete()
    }

    private fun vm(walkId: Long): WalkShareViewModel = WalkShareViewModel(
        repository = repository,
        shareService = service,
        cachedShareStore = cachedStore,
        savedStateHandle = SavedStateHandle(mapOf(WalkShareViewModel.ARG_WALK_ID to walkId)),
    )

    private suspend fun seedWalkWithRoute(): Long {
        val walk = repository.startWalk(startTimestamp = nextTs.getAndAdd(60_000L))
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = walk.startTimestamp, latitude = 45.0, longitude = -70.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = walk.startTimestamp + 30_000L, latitude = 45.001, longitude = -70.001),
        )
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
        // Wait for Loaded.
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) { vm.uiState.first { it is WalkShareUiState.Loaded } }
        }
        vm.toggleDistance(false)
        vm.toggleDuration(false)
        vm.toggleElevation(false)
        vm.toggleActivityBreakdown(false)
        vm.toggleSteps(false)
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) { vm.canShare.first { !it } }
        }
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
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) { vm.uiState.first { it is WalkShareUiState.Loaded } }
        }
        vm.events.test(timeout = 10.seconds) {
            vm.share()
            val ev = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000L) { awaitItem() }
            }
            assertTrue("expected Success, got $ev", ev is WalkShareEvent.Success)
            assertEquals("https://walk.pilgrimapp.org/abc123", (ev as WalkShareEvent.Success).url)
            cancelAndIgnoreRemainingEvents()
        }
        // Cached.
        val cached = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) { vm.cachedShare.first { it != null } }
        }
        assertEquals("https://walk.pilgrimapp.org/abc123", cached?.url)
    }

    @Test
    fun `share 429 emits RateLimited, does NOT cache, re-enables the Share button`() = runTest(dispatcher) {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val walkId = seedWalkWithRoute()
        val vm = vm(walkId)
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) { vm.uiState.first { it is WalkShareUiState.Loaded } }
        }
        vm.events.test(timeout = 10.seconds) {
            vm.share()
            val ev = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(10_000L) { awaitItem() }
            }
            assertEquals(WalkShareEvent.RateLimited, ev)
            cancelAndIgnoreRemainingEvents()
        }
        // No cache entry written on rate-limit.
        assertEquals(null, vm.cachedShare.value)
        // isSharing resets via the share() finally block so the user
        // can retry tomorrow (iOS parity — no client-side lockout).
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000L) { vm.isSharing.first { !it } }
        }
        assertEquals(false, vm.isSharing.value)
    }
}
