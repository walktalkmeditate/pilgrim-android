// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.FakeShareAudioTranscoder
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifest
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.share.ExpiryOption
import org.walktalkmeditate.pilgrim.data.share.PrepState
import org.walktalkmeditate.pilgrim.data.share.RepairSlot
import org.walktalkmeditate.pilgrim.data.share.SharePhotoEncoder
import org.walktalkmeditate.pilgrim.data.share.SharePrepStore
import org.walktalkmeditate.pilgrim.data.share.ShareRepairStore
import org.walktalkmeditate.pilgrim.data.share.ShareService
import org.walktalkmeditate.pilgrim.data.share.SlotIdentity
import org.walktalkmeditate.pilgrim.data.share.SlotKind
import org.walktalkmeditate.pilgrim.data.share.SlotStatus
import org.walktalkmeditate.pilgrim.data.share.TourBuilder
import org.walktalkmeditate.pilgrim.data.share.TourPhotoExporter
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * Mirrors `UnitTests/WalkShareInteractiveTests.swift`'s orchestration
 * half (pin `3f9f9e8`) end-to-end through the real Android stack —
 * Room, the real [SharePrepStore] over a [FakeShareAudioTranscoder],
 * the real [ShareService] over [MockWebServer], the real
 * [ShareRepairStore] over DataStore. The Swift suite's payload-shape
 * scenarios (exclusion filtering, the talk clamp, kept-window
 * waypoints, trim honesty, transcript exclusion) live in
 * `SharePayloadTourTest` where the builder is the unit under test; what
 * this file owns is the state machine, the lock, consent, and repair.
 *
 * Everything the VM does after `share()` runs on a real
 * [Dispatchers.IO] thread, so assertions await through
 * [TestRealTimeDispatcher] rather than virtual time (the house pattern
 * from `WalkShareViewModelTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkShareInteractiveTest {

    private lateinit var context: Application
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var server: MockWebServer
    private lateinit var service: ShareService
    private lateinit var cachedStore: CachedShareStore
    private lateinit var repairStore: ShareRepairStore
    private lateinit var prepStore: SharePrepStore
    private lateinit var exporter: TourPhotoExporter
    private lateinit var manifestScope: CoroutineScope
    private lateinit var manifestService: AudioManifestService
    private val transcoder = FakeShareAudioTranscoder()
    private val dispatcher = UnconfinedTestDispatcher()
    private val nextTs = AtomicLong(1_700_000_000_000L)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Fold-in dependency (FOLD-4): the walker's selected soundscape id.
     * Defaults to silence (matches [org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeSelectionRepository]'s
     * un-selected default); the soundscape-seam tests set and reset it.
     */
    private val selectedSoundscapeId = MutableStateFlow<String?>(null)

    private val manifestCacheFile: File get() = File(context.filesDir, "audio_manifest.json")

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
        val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        repairStore = ShareRepairStore(context, json)
        service = ShareService(
            client = client,
            json = json,
            deviceTokenStore = DeviceTokenStore(context),
            baseUrl = server.url("").toString().trimEnd('/'),
            repairStore = repairStore,
        )
        cachedStore = CachedShareStore(context, json)
        prepStore = SharePrepStore(context, transcoder, VoiceRecordingFileSystem(context))
        exporter = TourPhotoExporter(context, prepStore)
        selectedSoundscapeId.value = null
        manifestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manifestCacheFile.delete()
        manifestCacheFile.writeText(
            json.encodeToString(AudioManifest(version = "v1", assets = listOf(seededSoundscapeAsset()))),
        )
        manifestService = AudioManifestService(
            context = context,
            httpClient = client,
            json = json,
            scope = manifestScope,
            manifestUrl = server.url("/manifest.json").toString(),
        )
        // The local-cache load is dispatched onto the real Dispatchers.IO
        // regardless of manifestScope's own dispatcher (SoundSettingsViewModelTest/
        // SoundscapeOrchestratorTest precedent) — block the real test
        // thread until it lands so every test starts with the seeded
        // asset already resolvable.
        runBlocking { manifestService.initialLoad.await() }
    }

    @After
    fun tearDown() {
        // Every VM this test built is cleared BEFORE the Main swap. Each
        // one owns several `SharingStarted.Eagerly` collectors on
        // viewModelScope; left running they keep dispatching to the
        // test dispatcher this method is about to reset, which turns
        // into cross-test interference (the ci-vm-scope-leak family).
        vmStore.clear()
        manifestScope.cancel()
        manifestCacheFile.delete()
        server.shutdown()
        db.close()
        Dispatchers.resetMain()
        // The DataStore files are deliberately NOT deleted here. The
        // `preferencesDataStore` delegate caches its instance per
        // CLASSLOADER, not per Context, so deleting the backing file out
        // from under a live instance leaves it serving a stale in-memory
        // snapshot to every later test in this class (the same trap
        // ShareRepairStoreTest documents). Isolation comes from the
        // per-walk key instead: every seeded walk gets a fresh Room-side
        // uuid, so no two tests ever share a record.
        File(context.cacheDir, "share-prep").deleteRecursively()
    }

    private fun seededSoundscapeAsset(id: String = SEEDED_SOUNDSCAPE_ID) = AudioAsset(
        id = id,
        type = AudioAssetType.SOUNDSCAPE,
        name = id,
        displayName = id,
        durationSec = 300.0,
        r2Key = "soundscape/$id.m4a",
        fileSizeBytes = 1_000_000L,
    )

    /**
     * Swappable so a test can install an encoder that throws (the
     * classic branch's pre-POST work sits OUTSIDE `completeShare`'s own
     * catch ladder) or one that blocks (a deterministic "the attempt is
     * genuinely mid-flight" window for the cancellation scenarios).
     */
    private var photoEncode: (String) -> String? = { "BASE64:$it" }

    private val fakePhotoEncoder = object : SharePhotoEncoder {
        override fun encodeBase64(uriString: String): String? = photoEncode(uriString)
    }

    private val vmStore = ViewModelStore()
    private var vmCount = 0

    private fun vm(
        walkId: Long,
        repairStoreOverride: ShareRepairStore = repairStore,
        serviceOverride: ShareService = service,
    ): WalkShareViewModel = WalkShareViewModel(
        context = context,
        repository = repository,
        shareService = serviceOverride,
        cachedShareStore = cachedStore,
        photoEncoder = fakePhotoEncoder,
        sharePrepStore = prepStore,
        tourPhotoExporter = exporter,
        shareRepairStore = repairStoreOverride,
        selectedSoundscapeId = selectedSoundscapeId,
        audioManifestService = manifestService,
        unitsPreferences = FakeUnitsPreferencesRepository(),
        savedStateHandle = SavedStateHandle(mapOf(WalkShareViewModel.ARG_WALK_ID to walkId)),
    ).also { vmStore.put("walk-share-${vmCount++}", it) }

    /** [service]'s twin over a different repair store, for the store-failure scenarios. */
    private fun shareServiceOver(store: ShareRepairStore) = ShareService(
        client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
        json = json,
        deviceTokenStore = DeviceTokenStore(context),
        baseUrl = server.url("").toString().trimEnd('/'),
        repairStore = store,
    )

    // ---- fixtures -------------------------------------------------------

    private data class Seed(val walkId: Long, val walkUuid: String, val recordings: List<VoiceRecording>)

    /**
     * A finished walk with a two-point route plus [recordingDurations]
     * voice recordings (in order). Each recording gets a real WAV path;
     * the fake transcoder never reads it, matching the real one's
     * contract at the only seam that matters here (it writes the `.part`
     * then renames [SharePrepStore.artifactFile] into place).
     */
    private suspend fun seedWalk(
        recordingDurations: List<Long> = emptyList(),
        photoUris: List<String> = emptyList(),
        // ~111m of latitude per point, so 20 points is well past the
        // 4x-150m threshold RouteTrimmer needs before it will trim.
        routePoints: Int = 2,
    ): Seed {
        val walk = repository.startWalk(startTimestamp = nextTs.getAndAdd(600_000L))
        repeat(routePoints) { i ->
            repository.recordLocation(
                RouteDataSample(
                    walkId = walk.id,
                    timestamp = walk.startTimestamp + i * 30_000L,
                    latitude = 45.0 + i * 0.001,
                    longitude = -70.0,
                ),
            )
        }
        val recordings = recordingDurations.mapIndexed { index, durationMs ->
            val start = walk.startTimestamp + 60_000L + index * 120_000L
            val recording = VoiceRecording(
                walkId = walk.id,
                startTimestamp = start,
                endTimestamp = start + durationMs,
                durationMillis = durationMs,
                fileRelativePath = "recordings/${walk.uuid}/voice-$index.wav",
                transcription = "a transcript with plenty of words in it so this classifies as spoken speech",
            )
            // Keep the Room-assigned id: `deleteVoiceRecording` matches on
            // the primary key, so a test deleting the object it built
            // (id = 0) would silently delete nothing.
            recording.copy(id = repository.recordVoice(recording))
        }
        photoUris.forEachIndexed { index, uri ->
            db.walkPhotoDao().insert(
                WalkPhoto(
                    walkId = walk.id,
                    photoUri = uri,
                    pinnedAt = walk.startTimestamp + index * 1_000L,
                    takenAt = walk.startTimestamp + 30_000L + index * 1_000L,
                    capturedLat = 45.0,
                    capturedLng = -70.0,
                ),
            )
        }
        repository.finishWalk(walk, endTimestamp = walk.startTimestamp + 600_000L)
        return Seed(walk.id, walk.uuid, recordings)
    }

    private fun liveShare(url: String, id: String) = CachedShare(
        url = url,
        id = id,
        expiryEpochMs = System.currentTimeMillis() + 86_400_000L,
        shareDateEpochMs = System.currentTimeMillis(),
        expiryOption = ExpiryOption.Season,
    )

    /** A DataStore that is simply unavailable — every read and write fails. */
    private val throwingDataStore = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("datastore unavailable") }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("datastore unavailable")
    }

    /**
     * An in-memory Preferences store whose WRITES can be switched off
     * while reads keep working — the "record is readable but the device
     * can no longer be written to" case a repair pass hits at its very
     * first bookkeeping write, with a live page already on the other end.
     */
    private class WriteFailingDataStore : DataStore<Preferences> {
        private val prefs = MutableStateFlow(emptyPreferences())

        @Volatile
        var failWrites = false

        override val data: Flow<Preferences> = prefs

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("datastore unavailable for writes")
            return transform(prefs.value).also { prefs.value = it }
        }
    }

    /**
     * An empty DataStore whose first read takes [delayMs] of REAL time —
     * a cold DataStore file read on a busy device, held open long enough
     * for a test to observe what the screen would render meanwhile. Real
     * time (not `runTest`'s virtual clock) because the coroutine under
     * observation runs on the ViewModel's own dispatchers.
     */
    private fun slowEmptyDataStore(delayMs: Long) = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            withContext(TestRealTimeDispatcher.instance) { delay(delayMs) }
            emit(emptyPreferences())
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            emptyPreferences()
    }

    private suspend fun <T> awaitReal(timeoutMs: Long = 15_000L, block: suspend () -> T): T =
        withContext(TestRealTimeDispatcher.instance) { withTimeout(timeoutMs) { block() } }

    private suspend fun WalkShareViewModel.awaitLoaded() =
        awaitReal { uiState.first { it is WalkShareUiState.Loaded } }

    /**
     * Waits for the Share gate AND the observable section state to have
     * caught up — TOGETHER, sampled in the same instant.
     *
     * They settle independently (the gate is recomputed from source
     * flows, the section is a derived `stateIn` one dispatch behind), and
     * awaiting them one after another is not the same thing as awaiting
     * both: with Interactive OFF the gate is already true, so an
     * `await gate; await rows` pair can return on the PRE-toggle `true`
     * and hand back a ViewModel whose prep counter has not reached zero
     * yet. `share()` reads that counter from source and silently
     * no-ops — the card never leaves Idle and the next await burns its
     * whole timeout. Polling `.value` (never conflated) for the
     * conjunction is what makes "ready" mean ready.
     */
    private suspend fun WalkShareViewModel.awaitReadyToShare(expectedRows: Int = 0) = awaitReal {
        while (true) {
            val rows = interactiveSection.value.rows
            val rowsSettled = expectedRows == 0 ||
                (rows.size == expectedRows && rows.all { it.availability is RecordingAvailability.Available })
            if (rowsSettled && canShare.value) return@awaitReal
            delay(5)
        }
    }

    private suspend fun WalkShareViewModel.awaitCard(predicate: (ShareCardState) -> Boolean): ShareCardState =
        awaitReal { shareCardState.first(predicate) }

    private fun enqueueShareCreated(id: String = "abc123", url: String = "https://walk.pilgrimapp.org/abc123") {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"url":"$url","id":"$id"}""")
                .addHeader("Content-Type", "application/json"),
        )
    }

    private fun enqueueOk(count: Int) = repeat(count) { server.enqueue(MockResponse().setResponseCode(200)) }

    private fun drainRequests(): List<RecordedRequest> =
        generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()

    private fun String.asJsonObject(): JsonObject = json.parseToJsonElement(this).jsonObject

    // ---- AE-happy: toggle-on -> prep -> ready -> share ------------------

    @Test
    fun `interactive happy path uploads every recording, lands Shared, clears the record and the artifacts`() =
        runTest(dispatcher) {
            val seed = seedWalk(recordingDurations = listOf(60_000L, 90_000L))
            val vm = vm(seed.walkId)
            vm.awaitLoaded()

            vm.setInteractiveEnabled(true)
            vm.awaitReadyToShare(expectedRows = 2)
            val rows = vm.interactiveSection.value.rows
            assertEquals(2, rows.size)
            assertTrue(
                "every row resolves to a real transcoded size once prep lands",
                rows.all { it.availability is RecordingAvailability.Available },
            )

            enqueueShareCreated()
            enqueueOk(2)
            val events = mutableListOf<WalkShareEvent>()
            val watcher = launch(dispatcher) { vm.events.collect { events += it } }
            vm.share()
            vm.awaitCard { it is ShareCardState.Success }
            awaitReal { vm.isSharing.first { !it } }
            watcher.cancel()

            assertEquals(
                "a fully-landed interactive share reveals the page, exactly as the classic branch does",
                listOf(WalkShareEvent.Success("https://walk.pilgrimapp.org/abc123")),
                events,
            )

            val requests = drainRequests()
            assertEquals(3, requests.size)
            val post = requests.first()
            assertEquals("POST", post.method)
            val body = post.body.readUtf8().asJsonObject()
            assertEquals(2, body["tour"]!!.jsonObject["recordings"]!!.jsonArray.size)
            assertEquals(
                listOf("/api/share/abc123/audio/1", "/api/share/abc123/audio/2"),
                requests.drop(1).map { it.path },
            )

            assertNull("a fully-landed share leaves no repair record", repairStore.load(seed.walkUuid))
            assertFalse(
                "success is terminal for the prep cache — artifacts are cleaned up",
                File(context.cacheDir, "share-prep/${seed.walkUuid}").exists(),
            )
        }

    // ---- AE2: kill after voice 2 ----------------------------------------

    @Test
    fun `a failed slot leaves an accurate record that a fresh ViewModel repairs without re-encoding`() =
        runTest(dispatcher) {
            val seed = seedWalk(recordingDurations = listOf(60_000L, 60_000L, 60_000L))
            val vm = vm(seed.walkId)
            vm.awaitLoaded()
            vm.setInteractiveEnabled(true)
            vm.awaitReadyToShare(expectedRows = 3)

            enqueueShareCreated()
            enqueueOk(2)
            // Slot 3 fails both attempts (one auto-retry, ShareService.MEDIA_MAX_ATTEMPTS).
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(500))

            val events = mutableListOf<WalkShareEvent>()
            val watcher = launch(dispatcher) { vm.events.collect { events += it } }

            vm.share()
            val partial = vm.awaitCard { it is ShareCardState.Partial } as ShareCardState.Partial
            assertEquals(1, partial.failedCount)
            assertEquals("https://walk.pilgrimapp.org/abc123", partial.url)

            // The lock releases as the attempt unwinds, so any event it
            // was going to emit has already been emitted by here.
            awaitReal { vm.isSharing.first { !it } }
            watcher.cancel()
            assertTrue(
                "a partial must not auto-present the page — that yanks the walker off the card carrying " +
                    "\"Carry the missing files\" (iOS guards the reveal on .success alone, " +
                    "WalkShareView.swift:361@3f9f9e8): $events",
                events.none { it is WalkShareEvent.Success },
            )

            val record = requireNotNull(repairStore.load(seed.walkUuid))
            assertEquals("abc123", record.shareId)
            assertEquals(
                "the record must count only what is ACTUALLY still missing",
                listOf(SlotStatus.UPLOADED, SlotStatus.UPLOADED, SlotStatus.PENDING),
                record.slots.sortedBy { it.n }.map { it.status },
            )

            drainRequests()
            transcoder.calls.clear()

            // A fresh VM over the same stores — the process-death case.
            val reopened = vm(seed.walkId)
            reopened.awaitLoaded()
            val restored = reopened.awaitCard { it is ShareCardState.Partial } as ShareCardState.Partial
            assertEquals(1, restored.failedCount)

            val repairEvents = mutableListOf<WalkShareEvent>()
            val repairWatcher = launch(dispatcher) { reopened.events.collect { repairEvents += it } }

            enqueueOk(1)
            reopened.retryFailedMedia()
            reopened.awaitCard { it is ShareCardState.Success }
            awaitReal { reopened.isSharing.first { !it } }
            repairWatcher.cancel()

            assertEquals(
                "a completed repair reveals the page like any other success — iOS's reveal fires on " +
                    "uploadingMedia -> success too (WalkShareView.swift:361-366@3f9f9e8): $repairEvents",
                listOf(WalkShareEvent.Success("https://walk.pilgrimapp.org/abc123")),
                repairEvents,
            )

            val retryRequests = drainRequests()
            assertEquals(1, retryRequests.size)
            assertEquals(
                "the repair must land under the CACHED slot n, never a re-derived position",
                "/api/share/abc123/audio/3",
                retryRequests.single().path,
            )
            assertTrue("a surviving artifact is reused, never re-encoded", transcoder.calls.isEmpty())
            assertNull(repairStore.load(seed.walkUuid))
        }

    // ---- AE3: exclusion leaves no trace ---------------------------------

    @Test
    fun `an excluded recording never uploads and never appears in the payload`() = runTest(dispatcher) {
        val seed = seedWalk(recordingDurations = listOf(60_000L, 90_000L))
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.setInteractiveEnabled(true)
        vm.awaitReadyToShare(expectedRows = 2)

        vm.toggleRowInclude(candidateId = 1)
        awaitReal { vm.tourCandidates.first { candidates -> candidates.none { it.includeInShare && it.id == 1 } } }

        enqueueShareCreated()
        enqueueOk(1)
        vm.share()
        vm.awaitCard { it is ShareCardState.Success }

        val requests = drainRequests()
        assertEquals("only the included recording is PUT", 2, requests.size)
        assertEquals("/api/share/abc123/audio/1", requests[1].path)

        val body = requests.first().body.readUtf8().asJsonObject()
        assertEquals(1, body["tour"]!!.jsonObject["recordings"]!!.jsonArray.size)
        val talk = body["activity_intervals"]!!.jsonArray.filter {
            it.jsonObject["type"]!!.jsonPrimitive.content == "talk"
        }
        assertEquals("the excluded recording leaves no talk interval", 1, talk.size)
        assertEquals(
            "and no minutes in the total — 60s kept, 90s excluded",
            60.0,
            body["stats"]!!.jsonObject["talk_duration"]!!.jsonPrimitive.double,
            0.001,
        )
    }

    @Test
    fun `an excluded row stays available so it can be included again`() = runTest(dispatcher) {
        // Excluding deletes the artifact (port plan Decision 3); if the row
        // lost its Available state it would also lose its include control
        // (UI-24) and be stranded excluded forever.
        val seed = seedWalk(recordingDurations = listOf(60_000L))
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.setInteractiveEnabled(true)
        vm.awaitReadyToShare(expectedRows = 1)

        vm.toggleRowInclude(candidateId = 0)
        awaitReal { vm.interactiveSection.first { section -> section.rows.none { it.includeInShare } } }
        val row = vm.interactiveSection.value.rows.single()
        assertTrue("still Available", row.availability is RecordingAvailability.Available)
        assertTrue("so its include control is still rendered", rowShowsControls(row))

        vm.toggleRowInclude(candidateId = 0)
        awaitReal { vm.interactiveSection.first { section -> section.rows.all { it.includeInShare } } }
    }

    @Test
    fun `an exclusion survives an accidental Interactive toggle-off`() = runTest(dispatcher) {
        // iOS `prepareInteractive()` leaves `tourCandidates` alone —
        // toggling Interactive off and on again never rebuilds the
        // walker's per-row choices (`WalkShareViewModel.swift:217-228@3f9f9e8`).
        val seed = seedWalk(recordingDurations = listOf(60_000L, 90_000L))
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.setInteractiveEnabled(true)
        vm.awaitReadyToShare(expectedRows = 2)

        vm.toggleRowInclude(candidateId = 1)
        awaitReal { vm.tourCandidates.first { candidates -> candidates.none { it.includeInShare && it.id == 1 } } }

        vm.setInteractiveEnabled(false)
        // The toggle-off's cancel-and-cleanup and the toggle-on's fresh
        // prep are independent coroutines on IO; letting the cleanup
        // finish first keeps this test about the consent choice rather
        // than about their interleaving.
        awaitReal {
            prepStore.state.first { it[seed.walkUuid].isNullOrEmpty() }
            while (File(context.cacheDir, "share-prep/${seed.walkUuid}").exists()) delay(5)
        }
        vm.setInteractiveEnabled(true)
        // Await the flow this test asserts on, and await it settling on
        // the far side of the re-prep: toggling off drops every known
        // size, so `canShare` and the section can both still be reading
        // pre-toggle values for a beat.
        awaitReal {
            vm.tourCandidates.first { candidates ->
                candidates.size == 2 && candidates.all { it.unavailableReason == null }
            }
        }

        assertFalse(
            "a consent choice must not be silently undone by an off/on tap",
            vm.tourCandidates.value.single { it.id == 1 }.includeInShare,
        )
        assertTrue("the untouched row is unaffected", vm.tourCandidates.value.single { it.id == 0 }.includeInShare)
    }

    // ---- the route work hoisted out of the per-emission path -------------

    @Test
    fun `the interactive section answers from the route prepared at load`() = runTest(dispatcher) {
        // Every emission of `interactiveSection` used to re-run the RDP
        // downsample (once for trim eligibility, once per photo export
        // list) on the Main-confined transform. The route depends only
        // on immutable inputs, so it is computed once, off Main, in
        // loadInputs.
        val seed = seedWalk(photoUris = listOf("content://media/1"), routePoints = 20)
        val vm = vm(seed.walkId)
        vm.awaitLoaded()

        val prepared = requireNotNull(vm.preparedRoute) { "loadInputs must prepare the route before publishing Loaded" }
        assertTrue("a ~2km route is long enough to trim", prepared.canTrim)
        awaitReal { vm.interactiveSection.first { it.canTrim } }

        // The emissions a walker produces by the dozen — none of them
        // may replace the prepared route.
        repeat(4) {
            vm.toggleTrim(false)
            vm.toggleTrim(true)
            vm.togglePhotos(true)
            vm.togglePhotos(false)
        }
        assertSame("the route work happens once per load, not once per emission", prepared, vm.preparedRoute)
        assertTrue(vm.interactiveSection.value.canTrim)
    }

    // ---- fold-in: trim toggle outcome (iOS PR #63) ------------------------

    @Test
    fun `a too-short walk displays the trim toggle off while the stored intent survives`() = runTest(dispatcher) {
        // Fold-in (FOLD-5, iOS `InteractiveShareSection.swift:56-59@2ee1185`):
        // the default seedWalk() route (2 points, ~111m) sits well under
        // RouteTrimmer's 4x150m=600m floor, so canTrim is false for this
        // VM's whole life (computed once at load, per "the route work
        // hoisted out" test above).
        val seed = seedWalk()
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.setInteractiveEnabled(true)
        vm.awaitReadyToShare()

        awaitReal { vm.interactiveSection.first { !it.canTrim } }
        assertFalse(
            "a too-short walk must display the toggle OFF even though the default stored intent is on",
            vm.interactiveSection.value.trimEnabled,
        )
        assertTrue(
            "the walker never touched Trim — the stored intent must survive the false display, " +
                "so the toggle can reappear checked on its own once the walk is trimmable",
            vm.trimEnabled.value,
        )
    }

    // ---- fold-in: soundscape URL (iOS PR #61/#62) ------------------------

    @Test
    fun `an interactive share with a selected soundscape carries its resolved URL on the tour`() =
        runTest(dispatcher) {
            val seed = seedWalk()
            selectedSoundscapeId.value = SEEDED_SOUNDSCAPE_ID
            val vm = vm(seed.walkId)
            vm.awaitLoaded()
            vm.setInteractiveEnabled(true)
            vm.awaitReadyToShare()

            enqueueShareCreated()
            vm.share()
            vm.awaitCard { it is ShareCardState.Success }

            val body = drainRequests().single().body.readUtf8().asJsonObject()
            assertEquals(
                "https://cdn.pilgrimapp.org/audio/soundscape/$SEEDED_SOUNDSCAPE_ID.aac",
                body["tour"]!!.jsonObject["soundscape_url"]!!.jsonPrimitive.content,
            )
        }

    @Test
    fun `an interactive share with silence carries no soundscape_url`() = runTest(dispatcher) {
        val seed = seedWalk()
        // selectedSoundscapeId already defaults to null; set explicitly
        // so the test reads as a deliberate silence choice, not an
        // unset fixture.
        selectedSoundscapeId.value = null
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.setInteractiveEnabled(true)
        vm.awaitReadyToShare()

        enqueueShareCreated()
        vm.share()
        vm.awaitCard { it is ShareCardState.Success }

        val body = drainRequests().single().body.readUtf8().asJsonObject()
        assertFalse(
            "silence must never resolve to a link — the key stays entirely absent",
            body["tour"]!!.jsonObject.containsKey("soundscape_url"),
        )
    }

    // ---- consent: the pre-POST dropped-photo pause ----------------------

    @Test
    fun `a short photo export pauses for consent before anything is POSTed, and declining sends nothing`() =
        runTest(dispatcher) {
            // The URI resolves to nothing under Robolectric, so the export
            // comes up short deterministically and without network — the same
            // trick `testShareEntersPhotosDroppedWhenExportComesUpShort` uses
            // with a PhotoCandidate fixture whose localIdentifier never
            // resolves (`WalkShareInteractiveTests.swift:528-540@3f9f9e8`).
            val seed = seedWalk(photoUris = listOf("content://media/external/images/media/999999"))
            val vm = vm(seed.walkId)
            vm.awaitLoaded()

            vm.setInteractiveEnabled(true)
            assertTrue("Interactive means carry the media — photos auto-enable once", vm.includePhotos.value)
            vm.awaitReadyToShare()

            vm.share()
            val dropped = vm.awaitCard { it is ShareCardState.PhotosDropped } as ShareCardState.PhotosDropped
            assertEquals(0, dropped.prepared)
            assertEquals(1, dropped.dropped)
            assertEquals("nothing exists server-side during the consent pause", 0, server.requestCount)
            assertTrue("the form stays frozen through the pause", isShareInFlight(vm.shareCardState.value))
            assertFalse("but dismissal is NOT locked — nothing is server-side", isDismissLocked(vm.shareCardState.value))

            vm.cancelDroppedPhotoShare()
            assertEquals(ShareCardState.Idle, vm.shareCardState.value)
            awaitReal { vm.isSharing.first { !it } }
            assertEquals("declining cancels wholly — nothing sent", 0, server.requestCount)
            assertNull(cachedStore.observe(seed.walkUuid).first())
        }

    @Test
    fun `share without them resumes past the pause and claims the locking state synchronously`() =
        runTest(dispatcher) {
            val seed = seedWalk(photoUris = listOf("content://media/external/images/media/999999"))
            val vm = vm(seed.walkId)
            vm.awaitLoaded()
            vm.setInteractiveEnabled(true)
            vm.awaitReadyToShare()
            vm.share()
            vm.awaitCard { it is ShareCardState.PhotosDropped }

            enqueueShareCreated()
            // The paused attempt's coroutine releases the shared lock as
            // it unwinds, a beat after it publishes PhotosDropped — the
            // same ordering iOS has (`shareTask` is nil'd by the Task
            // body after `await share()` returns,
            // `WalkShareViewModel+ShareOrchestration.swift:11-14@3f9f9e8`).
            awaitReal { vm.isSharing.first { !it } }

            vm.continueShareWithoutDroppedPhotos()
            assertEquals(
                "the prompt's buttons must vanish within the same frame, before any await",
                ShareCardState.Uploading,
                vm.shareCardState.value,
            )
            // A same-frame double tap is a no-op: the lock is already claimed.
            vm.continueShareWithoutDroppedPhotos()

            vm.awaitCard { it is ShareCardState.Success }
            assertEquals("exactly one POST, no photo PUTs (none exported)", 1, server.requestCount)

            // iOS `testInteractivePhotoMetaUsesOnlyExportedPhotos`'s
            // second half (`:262-263@3f9f9e8`): "the interactive branch
            // must never fall back to mapping pinnedPhotos". Nothing
            // exported, so the key is absent — not a base64 JPEG of the
            // pinned photo the classic branch would have embedded.
            val body = drainRequests().single().body.readUtf8().asJsonObject()
            assertFalse("no photo metadata may be invented for an empty export", body.containsKey("photos"))
        }

    @Test
    fun `a share cancelled before the POST returns to Idle with nothing sent`() = runTest(dispatcher) {
        // iOS `testShareCancelledBeforePostReturnsToIdle`
        // (`WalkShareInteractiveTests.swift:519-526@3f9f9e8`):
        // "cancelling before the POST must never leave a live-looking
        // state behind". `cancelDroppedPhotoShare` is Android's cancel
        // entry point — same body as iOS's `cancelShare()`, a
        // `shareJob?.cancel()`.
        val seed = seedWalk(photoUris = listOf("content://media/1"))
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.togglePhotos(true)
        vm.awaitReadyToShare()

        // Held inside the pre-POST photo encode, so the cancel lands
        // exactly where iOS's does: after the attempt is genuinely
        // running, before anything exists server-side.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        photoEncode = { uri ->
            entered.countDown()
            release.await()
            "BASE64:$uri"
        }

        vm.share()
        assertEquals(ShareCardState.Uploading, vm.shareCardState.value)
        awaitReal { while (entered.count > 0L) delay(5) }

        vm.cancelDroppedPhotoShare()
        release.countDown()

        awaitReal { vm.shareCardState.first { it == ShareCardState.Idle } }
        awaitReal { vm.isSharing.first { !it } }
        assertEquals("nothing was sent", 0, server.requestCount)
        assertNull("and nothing was cached", cachedStore.observe(seed.walkUuid).first())
    }

    @Test
    fun `declining while a resume is in flight cancels it`() = runTest(dispatcher) {
        // iOS `testDeclineCancelsInFlightResume` (`:559-568@3f9f9e8`):
        // "declining while a resume is in flight must cancel it —
        // completeShare's pre-POST checkpoint returns idle before
        // geocoding or POSTing ever run".
        val seed = seedWalk(
            recordingDurations = listOf(60_000L),
            photoUris = listOf("content://media/external/images/media/999999"),
        )
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.setInteractiveEnabled(true)
        vm.awaitReadyToShare(expectedRows = 1)

        vm.share()
        vm.awaitCard { it is ShareCardState.PhotosDropped }
        awaitReal { vm.isSharing.first { !it } }

        // The resume has to re-encode this recording (its artifact is
        // gone), and that encode is held open — so the decline below
        // lands while the resume is genuinely mid-flight.
        val recording = seed.recordings.single()
        transcoder.delaysMs[VoiceRecordingFileSystem(context).absolutePath(recording.fileRelativePath)] = 10_000L
        assertTrue(prepStore.artifactFile(seed.walkUuid, recording.uuid).delete())
        transcoder.calls.clear()

        vm.continueShareWithoutDroppedPhotos()
        assertEquals(ShareCardState.Uploading, vm.shareCardState.value)
        awaitReal { while (transcoder.calls.isEmpty()) delay(5) }

        vm.cancelDroppedPhotoShare()

        awaitReal { vm.shareCardState.first { it == ShareCardState.Idle } }
        awaitReal { vm.isSharing.first { !it } }
        assertEquals("the declined resume never reaches the POST", 0, server.requestCount)
    }

    // ---- per-row choices -------------------------------------------------

    @Test
    fun `toggling an unavailable row does nothing at all`() = runTest(dispatcher) {
        // iOS `testToggleIncludeSkipsUnavailableCandidates`
        // (`:145-158@3f9f9e8`): "an unavailable candidate can never be
        // toggled on by the user" — and on Android it must not reach the
        // prep store either, since include/exclude drives real encodes
        // and deletes.
        val seed = seedWalk(recordingDurations = listOf(60_000L, 60_000L))
        val fileSystem = VoiceRecordingFileSystem(context)
        transcoder.failures[fileSystem.absolutePath(seed.recordings[1].fileRelativePath)] =
            RuntimeException("no encoder for this one")

        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.setInteractiveEnabled(true)
        // Await the failing encode itself, not just "one row is
        // unavailable yet" — which is also true while that encode has
        // not started.
        awaitReal {
            prepStore.state.first { it[seed.walkUuid]?.get(seed.recordings[1].uuid) == PrepState.Failed }
        }
        awaitReal {
            vm.tourCandidates.first { candidates ->
                candidates.size == 2 && candidates.count { it.unavailableReason != null } == 1
            }
        }
        transcoder.calls.clear()

        vm.toggleRowInclude(candidateId = 1)
        vm.toggleRowInclude(candidateId = 1)
        // Both would-be effects are asynchronous (a cancel-and-delete,
        // then a re-encode), so give them room to have happened before
        // asserting they did not.
        awaitReal { delay(300) }

        assertFalse(
            "an unavailable row can never be toggled on",
            vm.tourCandidates.value.single { it.id == 1 }.includeInShare,
        )
        assertTrue("and never triggers a re-encode", transcoder.calls.isEmpty())
        assertEquals(
            "nor clears the prep state that made it unavailable",
            PrepState.Failed,
            prepStore.state.value[seed.walkUuid]?.get(seed.recordings[1].uuid),
        )

        // The available row still toggles both ways (iOS's second half).
        vm.toggleRowInclude(candidateId = 0)
        awaitReal { vm.tourCandidates.first { c -> c.none { it.id == 0 && it.includeInShare } } }
        vm.toggleRowInclude(candidateId = 0)
        awaitReal { vm.tourCandidates.first { c -> c.any { it.id == 0 && it.includeInShare } } }
    }

    @Test
    fun `rapid Interactive off-on taps still settle with every row ready to share`() = runTest(dispatcher) {
        // The toggle's two halves — toggle-off's cancel-and-delete and
        // toggle-on's transcode pass — used to launch as independent IO
        // coroutines with no ordering between them. A cleanup landing
        // inside a fresh prep cancels its encodes and clears their state,
        // which strands the rows on "audio removed" with the Share gate
        // shut forever (a state cleared by cancellation is neither Ready
        // nor Failed, so it never counts as resolved).
        //
        // The device-QA protocol for this is ten double-taps; the encode
        // delay is what makes each tap land while the previous pass is
        // genuinely still working.
        val seed = seedWalk(recordingDurations = listOf(60_000L, 90_000L))
        val fileSystem = VoiceRecordingFileSystem(context)
        seed.recordings.forEach { transcoder.delaysMs[fileSystem.absolutePath(it.fileRelativePath)] = 100L }

        val vm = vm(seed.walkId)
        vm.awaitLoaded()

        vm.setInteractiveEnabled(true)
        // Await a genuine in-flight encode, so the first toggle-off below
        // has something to cancel rather than racing an empty store.
        awaitReal {
            prepStore.state.first { states -> states[seed.walkUuid]?.values?.any { it == PrepState.Preparing } == true }
        }
        // No awaits from here on: the ordering has to come from the VM,
        // not from the test.
        repeat(TOGGLE_DOUBLE_TAPS) {
            vm.setInteractiveEnabled(false)
            vm.setInteractiveEnabled(true)
        }

        vm.awaitReadyToShare(expectedRows = 2)
        assertTrue("the walker is not stranded with a shut Share gate", vm.canShare.value)
        assertTrue(
            "and every row carries a real transcoded size: ${vm.interactiveSection.value.rows}",
            vm.interactiveSection.value.rows.all { it.availability is RecordingAvailability.Available },
        )
    }

    @Test
    fun `photos auto-enable exactly once — the walker's off stays off`() = runTest(dispatcher) {
        // iOS `testInteractiveAutoEnablesPhotosOnce` (`:56-66@3f9f9e8`):
        // "auto-enable happens once; the walker's off stays off".
        val seed = seedWalk(photoUris = listOf("content://media/1"))
        val vm = vm(seed.walkId)
        vm.awaitLoaded()

        vm.setInteractiveEnabled(true)
        assertTrue("Interactive means carry the media", vm.includePhotos.value)

        vm.togglePhotos(false)
        vm.setInteractiveEnabled(false)
        vm.setInteractiveEnabled(true)

        assertFalse("the latch never re-fires", vm.includePhotos.value)
    }

    @Test
    fun `the Share gate stays shut while the tour is over its caps`() = runTest(dispatcher) {
        // iOS `testShareButtonDisabledWhenTourInvalid` (`:293-301@3f9f9e8`):
        // 13 candidates against a 12-recording cap.
        val seed = seedWalk(recordingDurations = List(TourBuilder.MAX_RECORDINGS + 1) { 60_000L })
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.setInteractiveEnabled(true)

        awaitReal {
            vm.interactiveSection.first { section ->
                section.rows.size == TourBuilder.MAX_RECORDINGS + 1 &&
                    section.rows.all { it.availability is RecordingAvailability.Available }
            }
        }
        awaitReal { vm.canShare.first { !it } }

        assertTrue(
            "the gate and the copy read the same validation call",
            vm.interactiveSection.value.validationErrorText != null,
        )
    }

    // ---- the single in-flight lock --------------------------------------

    @Test
    fun `a double-tapped share launches exactly one attempt`() = runTest(dispatcher) {
        val seed = seedWalk()
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.awaitReadyToShare()

        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"url":"https://walk.pilgrimapp.org/abc123","id":"abc123"}""")
                .addHeader("Content-Type", "application/json")
                // Holds the attempt open long enough for the second tap to
                // land while the first is genuinely in flight.
                .setBodyDelay(400, TimeUnit.MILLISECONDS),
        )

        vm.share()
        vm.share()
        vm.share()

        vm.awaitCard { it is ShareCardState.Success }
        assertEquals("the compareAndSet lock admits exactly one attempt", 1, server.requestCount)
    }

    // ---- AE1: interactive-off never touches the new stores ---------------

    @Test
    fun `a classic share touches neither the prep pipeline nor the media upload path`() = runTest(dispatcher) {
        val seed = seedWalk(recordingDurations = listOf(60_000L), photoUris = listOf("content://media/1"))
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.togglePhotos(true)
        vm.awaitReadyToShare()

        enqueueShareCreated()
        vm.share()
        vm.awaitCard { it is ShareCardState.Success }

        val requests = drainRequests()
        assertEquals("a classic share is exactly one POST — no media PUTs", 1, requests.size)
        val body = requests.single().body.readUtf8().asJsonObject()
        assertFalse("no tour key", body.containsKey("tour"))
        assertFalse("no pauses key", body.containsKey("pauses"))
        assertEquals(
            "classic photos still embed their base64 bytes",
            "BASE64:content://media/1",
            body["photos"]!!.jsonArray.single().jsonObject["data"]!!.jsonPrimitive.content,
        )

        assertTrue("no transcode was ever started", transcoder.calls.isEmpty())
        assertNull("no repair record", repairStore.load(seed.walkUuid))
        assertFalse("no prep artifacts", File(context.cacheDir, "share-prep/${seed.walkUuid}").exists())
    }

    @Test
    fun `a classic re-share clears a stale repair record left by an earlier interactive attempt`() =
        runTest(dispatcher) {
            // iOS: "A fresh share must never inherit a previous share's
            // failed-media record — this walk may have had a `.partial`
            // share before."
            // (`WalkShareViewModel+ShareOrchestration.swift:157-163@3f9f9e8`)
            val seed = seedWalk()
            repairStore.prePopulate(
                walkUuid = seed.walkUuid,
                shareId = "an-older-share",
                slots = listOf(RepairSlot(SlotKind.AUDIO, 1, SlotIdentity.Audio("gone"), SlotStatus.PENDING)),
            )
            val vm = vm(seed.walkId)
            vm.awaitLoaded()
            vm.awaitReadyToShare()

            enqueueShareCreated()
            vm.share()
            vm.awaitCard { it is ShareCardState.Success }

            assertNull(repairStore.load(seed.walkUuid))
        }

    // ---- repair edge cases ----------------------------------------------

    @Test
    fun `a repair whose artifact was evicted re-encodes it from the source WAV`() = runTest(dispatcher) {
        val seed = seedWalk(recordingDurations = listOf(60_000L))
        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        vm.setInteractiveEnabled(true)
        vm.awaitReadyToShare(expectedRows = 1)

        enqueueShareCreated()
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        vm.share()
        vm.awaitCard { it is ShareCardState.Partial }
        drainRequests()

        // The cache evicted the artifact between attempts.
        assertTrue(prepStore.artifactFile(seed.walkUuid, seed.recordings.single().uuid).delete())
        transcoder.calls.clear()

        enqueueOk(1)
        vm.retryFailedMedia()
        vm.awaitCard { it is ShareCardState.Success }

        assertEquals("the missing artifact is re-encoded, not failed", 1, transcoder.calls.size)
        assertEquals("/api/share/abc123/audio/1", drainRequests().single().path)
    }

    @Test
    fun `a repair whose identity no longer resolves reports itself unavailable instead of looping`() =
        runTest(dispatcher) {
            val seed = seedWalk(recordingDurations = listOf(60_000L))
            val vm = vm(seed.walkId)
            vm.awaitLoaded()
            vm.setInteractiveEnabled(true)
            vm.awaitReadyToShare(expectedRows = 1)

            enqueueShareCreated()
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(500))
            vm.share()
            vm.awaitCard { it is ShareCardState.Partial }
            drainRequests()
            // requestCount is cumulative for the server's whole lifetime
            // (the POST plus the two failed PUT attempts) — the repair
            // assertion below is about what happens AFTER this point.
            val requestsBeforeRepair = server.requestCount

            // The recording itself is gone by the time the walker taps
            // "Carry the missing files" — its identity resolves to nothing.
            repository.deleteVoiceRecording(seed.recordings.single())
            prepStore.artifactFile(seed.walkUuid, seed.recordings.single().uuid).delete()

            val reopened = vm(seed.walkId)
            reopened.awaitLoaded()
            reopened.awaitCard { it is ShareCardState.Partial }
            reopened.retryFailedMedia()

            awaitReal { reopened.repairUnavailable.first { it } }
            val card = reopened.shareCardState.value
            assertTrue(card is ShareCardState.Partial)
            assertEquals(1, (card as ShareCardState.Partial).failedCount)
            assertEquals("nothing was PUT for an unresolvable slot", requestsBeforeRepair, server.requestCount)
        }

    @Test
    fun `a repair that throws stays on the repair card instead of offering a second share`() = runTest(dispatcher) {
        // Unwinding to runGuarded's generic Error card would replace the
        // repair offer with a "Try Again" that calls share() — a SECOND
        // POST over the very page this pass was repairing. The page is
        // live either way, so every throw past the record resolution has
        // to land back on Partial.
        val seed = seedWalk(recordingDurations = listOf(60_000L))
        cachedStore.put(walkUuid = seed.walkUuid, share = liveShare(url = "https://walk.pilgrimapp.org/live", id = "live"))

        val dataStore = WriteFailingDataStore()
        val failingRepairStore = ShareRepairStore(dataStore, json)
        failingRepairStore.prePopulate(
            walkUuid = seed.walkUuid,
            shareId = "live",
            slots = listOf(
                RepairSlot(SlotKind.AUDIO, 1, SlotIdentity.Audio(seed.recordings.single().uuid), SlotStatus.PENDING),
            ),
        )
        dataStore.failWrites = true

        val vm = vm(
            seed.walkId,
            repairStoreOverride = failingRepairStore,
            serviceOverride = shareServiceOver(failingRepairStore),
        )
        vm.awaitLoaded()
        vm.awaitCard { it is ShareCardState.Partial }

        val events = mutableListOf<WalkShareEvent>()
        val watcher = launch(dispatcher) { vm.events.collect { events += it } }
        val requestsBeforeRepair = server.requestCount

        vm.retryFailedMedia()
        awaitReal { vm.isSharing.first { !it } }
        watcher.cancel()

        val card = vm.shareCardState.value
        assertTrue("a failed repair must never unwind onto the Error card: $card", card is ShareCardState.Partial)
        assertEquals("https://walk.pilgrimapp.org/live", (card as ShareCardState.Partial).url)
        assertEquals("and must still say what is missing", 1, card.failedCount)
        assertEquals("nothing new is sent — least of all a second POST", requestsBeforeRepair, server.requestCount)
        assertTrue("the live page is not reported as a failed share: $events", events.none { it is WalkShareEvent.Failed })
    }

    // ---- restoration ----------------------------------------------------

    @Test
    fun `an existing non-expired share short-circuits straight to Shared`() = runTest(dispatcher) {
        val seed = seedWalk(recordingDurations = listOf(60_000L))
        cachedStore.put(
            walkUuid = seed.walkUuid,
            share = CachedShare(
                url = "https://walk.pilgrimapp.org/prior",
                id = "prior",
                expiryEpochMs = System.currentTimeMillis() + 86_400_000L,
                shareDateEpochMs = System.currentTimeMillis(),
                expiryOption = ExpiryOption.Season,
            ),
        )
        val vm = vm(seed.walkId)
        vm.awaitLoaded()

        val card = vm.awaitCard { it is ShareCardState.Success }
        assertEquals("https://walk.pilgrimapp.org/prior", (card as ShareCardState.Success).url)
        assertTrue(isSharedState(card))
        assertEquals("nothing is re-POSTed on re-entry", 0, server.requestCount)
    }

    @Test
    fun `a repair record naming a different share is cleared on load`() = runTest(dispatcher) {
        val seed = seedWalk(recordingDurations = listOf(60_000L))
        cachedStore.put(
            walkUuid = seed.walkUuid,
            share = CachedShare(
                url = "https://walk.pilgrimapp.org/current",
                id = "current",
                expiryEpochMs = System.currentTimeMillis() + 86_400_000L,
                shareDateEpochMs = System.currentTimeMillis(),
                expiryOption = ExpiryOption.Season,
            ),
        )
        repairStore.prePopulate(
            walkUuid = seed.walkUuid,
            shareId = "some-older-share",
            slots = listOf(RepairSlot(SlotKind.AUDIO, 1, SlotIdentity.Audio("whatever"), SlotStatus.PENDING)),
        )

        val vm = vm(seed.walkId)
        vm.awaitLoaded()
        val card = vm.awaitCard { it is ShareCardState.Success }

        assertEquals("https://walk.pilgrimapp.org/current", (card as ShareCardState.Success).url)
        assertNull("the stale record must not survive the load", repairStore.load(seed.walkUuid))
    }

    @Test
    fun `re-entering a shared walk never pairs an actionable Idle card with the live page`() = runTest(dispatcher) {
        // ShareStatusSection renders `Idle` as a LIVE "Share Walk"
        // button, and the screen treats a non-expired cached share as
        // `isShared` — so publishing the cache before the restore has
        // finished puts a working Share button on top of a page that
        // already exists. One tap there is a second POST.
        val seed = seedWalk(recordingDurations = listOf(60_000L))
        cachedStore.put(walkUuid = seed.walkUuid, share = liveShare(url = "https://walk.pilgrimapp.org/live", id = "live"))

        // The restore's repair-record read is held open, so the window
        // the screen would render is wide enough to observe rather than
        // a sub-millisecond one a sampling assertion could skip past.
        val vm = vm(seed.walkId, repairStoreOverride = ShareRepairStore(slowEmptyDataStore(300L), json))
        val cardsSeenWithALivePage = mutableListOf<ShareCardState>()
        val watcher = launch(dispatcher) {
            vm.cachedShare.collect { cached ->
                if (cached?.isExpiredAt() == false) cardsSeenWithALivePage += vm.shareCardState.value
            }
        }

        vm.awaitLoaded()
        vm.awaitCard { it is ShareCardState.Success }
        awaitReal { vm.cachedShare.first { it != null } }
        watcher.cancel()

        assertTrue("the screen saw the live page at all", cardsSeenWithALivePage.isNotEmpty())
        assertTrue(
            "a live cached share must never become visible while the card is still an actionable Idle: " +
                "$cardsSeenWithALivePage",
            cardsSeenWithALivePage.none { it == ShareCardState.Idle },
        )
    }

    @Test
    fun `a repair-record read that throws cannot kill the cached-share observer`() = runTest(dispatcher) {
        // The restore path's only I/O is the repair-record read. One
        // throw inside `collect { }` ends the observer for the life of
        // the ViewModel (the Stage 5-D house rule), so this walk would
        // never see another cached-share write.
        val seed = seedWalk()
        cachedStore.put(walkUuid = seed.walkUuid, share = liveShare(url = "https://walk.pilgrimapp.org/one", id = "one"))

        val vm = vm(seed.walkId, repairStoreOverride = ShareRepairStore(throwingDataStore, json))
        vm.awaitLoaded()

        val card = vm.awaitCard { it is ShareCardState.Success } as ShareCardState.Success
        assertEquals(
            "an unreadable repair record still leaves a live page — restore into its card, not an actionable Idle",
            "https://walk.pilgrimapp.org/one",
            card.url,
        )

        cachedStore.put(walkUuid = seed.walkUuid, share = liveShare(url = "https://walk.pilgrimapp.org/two", id = "two"))
        awaitReal { vm.cachedShare.first { it?.id == "two" } }
    }

    // ---- failure handling outside the POST -------------------------------

    @Test
    fun `an unexpected throw before the POST lands on the error card instead of the crash handler`() =
        runTest(dispatcher) {
            // `photoPayloadFor` (classic branch) runs OUTSIDE
            // completeShare's own catch ladder, as do the interactive
            // export list, the payload build, and the whole repair pass.
            // Whatever throws there, the walker must get the same error
            // card any ShareError produces — not a dead process.
            val seed = seedWalk(photoUris = listOf("content://media/1"))
            val vm = vm(seed.walkId)
            vm.awaitLoaded()
            vm.togglePhotos(true)
            vm.awaitReadyToShare()

            val failed = CompletableDeferred<WalkShareEvent.Failed>()
            val watcher = launch(dispatcher) {
                vm.events.collect { if (it is WalkShareEvent.Failed) failed.complete(it) }
            }
            photoEncode = { throw IllegalStateException("photo encoder blew up") }

            vm.share()

            val card = vm.awaitCard { it is ShareCardState.Error } as ShareCardState.Error
            assertEquals(context.getString(R.string.share_modal_error_unknown), card.message)
            assertEquals("nothing reached the server", 0, server.requestCount)
            awaitReal { vm.isSharing.first { !it } }
            // The walker is told, not left staring at a spinner.
            awaitReal { failed.await() }
            watcher.cancel()
        }

    @Test
    fun `a record for the current share restores Partial so the repair offer survives re-entry`() =
        runTest(dispatcher) {
            val seed = seedWalk(recordingDurations = listOf(60_000L))
            cachedStore.put(
                walkUuid = seed.walkUuid,
                share = CachedShare(
                    url = "https://walk.pilgrimapp.org/live",
                    id = "live",
                    expiryEpochMs = System.currentTimeMillis() + 86_400_000L,
                    shareDateEpochMs = System.currentTimeMillis(),
                    expiryOption = ExpiryOption.Season,
                ),
            )
            repairStore.prePopulate(
                walkUuid = seed.walkUuid,
                shareId = "live",
                slots = listOf(
                    RepairSlot(SlotKind.AUDIO, 1, SlotIdentity.Audio(seed.recordings.single().uuid), SlotStatus.PENDING),
                ),
            )

            val vm = vm(seed.walkId)
            vm.awaitLoaded()
            val card = vm.awaitCard { it is ShareCardState.Partial } as ShareCardState.Partial

            assertEquals("https://walk.pilgrimapp.org/live", card.url)
            assertEquals(1, card.failedCount)
        }

    private companion object {
        /** The device-QA toggle-race protocol's double-tap count (`docs/qa/2026-08-15-phase19-walk-with-me-qa.md`). */
        const val TOGGLE_DOUBLE_TAPS = 10

        /** Fold-in (FOLD-4): the one soundscape asset seeded into every test's manifest cache. */
        const val SEEDED_SOUNDSCAPE_ID = "walk-with-me-test-scape"
    }
}
