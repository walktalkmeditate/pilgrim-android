// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.FakeShareAudioTranscoder
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore
import org.walktalkmeditate.pilgrim.data.share.ExpiryOption
import org.walktalkmeditate.pilgrim.data.share.RepairSlot
import org.walktalkmeditate.pilgrim.data.share.SharePhotoEncoder
import org.walktalkmeditate.pilgrim.data.share.SharePrepStore
import org.walktalkmeditate.pilgrim.data.share.ShareRepairStore
import org.walktalkmeditate.pilgrim.data.share.ShareService
import org.walktalkmeditate.pilgrim.data.share.SlotIdentity
import org.walktalkmeditate.pilgrim.data.share.SlotKind
import org.walktalkmeditate.pilgrim.data.share.SlotStatus
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
    private val transcoder = FakeShareAudioTranscoder()
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
    }

    @After
    fun tearDown() {
        // Every VM this test built is cleared BEFORE the Main swap. Each
        // one owns several `SharingStarted.Eagerly` collectors on
        // viewModelScope; left running they keep dispatching to the
        // test dispatcher this method is about to reset, which turns
        // into cross-test interference (the ci-vm-scope-leak family).
        vmStore.clear()
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

    private val fakePhotoEncoder = object : SharePhotoEncoder {
        override fun encodeBase64(uriString: String): String? = "BASE64:$uriString"
    }

    private val vmStore = ViewModelStore()
    private var vmCount = 0

    private fun vm(walkId: Long): WalkShareViewModel = WalkShareViewModel(
        context = context,
        repository = repository,
        shareService = service,
        cachedShareStore = cachedStore,
        photoEncoder = fakePhotoEncoder,
        sharePrepStore = prepStore,
        tourPhotoExporter = exporter,
        shareRepairStore = repairStore,
        unitsPreferences = FakeUnitsPreferencesRepository(),
        savedStateHandle = SavedStateHandle(mapOf(WalkShareViewModel.ARG_WALK_ID to walkId)),
    ).also { vmStore.put("walk-share-${vmCount++}", it) }

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
    ): Seed {
        val walk = repository.startWalk(startTimestamp = nextTs.getAndAdd(600_000L))
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = walk.startTimestamp, latitude = 45.0, longitude = -70.0),
        )
        repository.recordLocation(
            RouteDataSample(
                walkId = walk.id,
                timestamp = walk.startTimestamp + 30_000L,
                latitude = 45.001,
                longitude = -70.001,
            ),
        )
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

    private suspend fun <T> awaitReal(timeoutMs: Long = 15_000L, block: suspend () -> T): T =
        withContext(TestRealTimeDispatcher.instance) { withTimeout(timeoutMs) { block() } }

    private suspend fun WalkShareViewModel.awaitLoaded() =
        awaitReal { uiState.first { it is WalkShareUiState.Loaded } }

    /**
     * Waits for the Share gate AND for the observable section state to
     * have caught up. They settle independently — the gate is
     * recomputed from source flows, the section is a derived `stateIn`
     * one dispatch behind — so a test that asserts on rows must await
     * the rows, not infer them from the gate.
     */
    private suspend fun WalkShareViewModel.awaitReadyToShare(expectedRows: Int = 0) = awaitReal {
        canShare.first { it }
        if (expectedRows > 0) {
            interactiveSection.first { section ->
                section.rows.size == expectedRows &&
                    section.rows.all { it.availability is RecordingAvailability.Available }
            }
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
            vm.share()
            vm.awaitCard { it is ShareCardState.Success }

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

            vm.share()
            val partial = vm.awaitCard { it is ShareCardState.Partial } as ShareCardState.Partial
            assertEquals(1, partial.failedCount)
            assertEquals("https://walk.pilgrimapp.org/abc123", partial.url)

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

            enqueueOk(1)
            reopened.retryFailedMedia()
            reopened.awaitCard { it is ShareCardState.Success }

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
}
