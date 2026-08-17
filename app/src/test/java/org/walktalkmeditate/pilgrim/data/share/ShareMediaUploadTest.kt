// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

/**
 * Phase 19 U6. MockWebServer exercises the REAL `okhttp3.Request` builder
 * path inside [ShareService.uploadMedia] end to end (platform-builder
 * rule) — no fake/double stands in for request construction anywhere in
 * this file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareMediaUploadTest {

    private lateinit var server: MockWebServer
    private lateinit var service: ShareService

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val deviceTokenStore = DeviceTokenStore(context)
    private val repairStore = ShareRepairStore(context, json)

    // Unique per test instance (JUnit4 builds a fresh instance per @Test
    // method) — the preferencesDataStore delegate caches in memory per
    // classloader, not per Context, so a shared literal walkUuid across
    // tests leaks repair-record state cross-test even after the backing
    // file is deleted in @After. See ShareRepairStoreTest for the same
    // fix + fuller explanation.
    private val walkUuid = UUID.randomUUID().toString()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        service = ShareService(
            client = client,
            json = json,
            deviceTokenStore = deviceTokenStore,
            baseUrl = server.url("").toString().trimEnd('/'),
            repairStore = repairStore,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        File(context.filesDir, "datastore/share_device_token.preferences_pb").delete()
        File(context.filesDir, "datastore/share_repair.preferences_pb").delete()
    }

    private fun sampleFile(tag: String): File =
        File.createTempFile("share-media-upload-test-$tag", ".bin").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            deleteOnExit()
        }

    private fun photoUploadSlot(n: Int, uri: String = "content://media/photo/$n", ts: Long = 1_000L + n) =
        UploadSlot(n, sampleFile("photo-$n"), SlotIdentity.Photo(uri, ts))

    private fun audioUploadSlot(n: Int, recordingUuid: String = "rec-$n") =
        UploadSlot(n, sampleFile("audio-$n"), SlotIdentity.Audio(recordingUuid))

    @Test
    fun `the public entry point is pinned to the worker contract's retry policy`() {
        assertEquals("ShareService.swift:404@3f9f9e8 (800_000_000 ns)", 800L, ShareService.MEDIA_RETRY_BACKOFF_MS)
        assertEquals("ShareService.swift:384@3f9f9e8 (for attempt in 0..<2)", 2, ShareService.MEDIA_MAX_ATTEMPTS)
    }

    @Test
    fun `photos upload before audio, in index order, with 1-based n in the URL`() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(200)) }
        val photos = listOf(photoUploadSlot(1), photoUploadSlot(2))
        val audio = listOf(audioUploadSlot(1), audioUploadSlot(2))

        val result = service.uploadMedia(walkUuid, "share-1", "https://walk.pilgrimapp.org/share-1", photos, audio)

        val paths = (0 until 4).map { server.takeRequest().path }
        assertEquals(
            listOf(
                "/api/share/share-1/photos/1",
                "/api/share/share-1/photos/2",
                "/api/share/share-1/audio/1",
                "/api/share/share-1/audio/2",
            ),
            paths,
        )
        assertEquals(0, result.failedCount)
        assertEquals("https://walk.pilgrimapp.org/share-1", result.url)
    }

    @Test
    fun `request shape matches the worker contract exactly for audio`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        service.uploadMedia(walkUuid, "abc123defg", "https://x", emptyList(), listOf(audioUploadSlot(3)))

        val recorded = server.takeRequest()
        assertEquals("/api/share/abc123defg/audio/3", recorded.path)
        assertEquals("PUT", recorded.method)
        assertEquals("audio/mp4", recorded.getHeader("Content-Type"))
        assertNotNull("X-Device-Token missing", recorded.getHeader("X-Device-Token"))
    }

    @Test
    fun `request shape matches the worker contract exactly for photos`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        service.uploadMedia(walkUuid, "abc123defg", "https://x", listOf(photoUploadSlot(1)), emptyList())

        val recorded = server.takeRequest()
        assertEquals("/api/share/abc123defg/photos/1", recorded.path)
        assertEquals("PUT", recorded.method)
        assertEquals("image/jpeg", recorded.getHeader("Content-Type"))
        assertNotNull("X-Device-Token missing", recorded.getHeader("X-Device-Token"))
    }

    @Test
    fun `PUT body size matches the source file's byte length`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val file = sampleFile("size-check").apply { writeBytes(ByteArray(777) { it.toByte() }) }
        val slot = UploadSlot(1, file, SlotIdentity.Photo("u", 1L))

        service.uploadMedia(walkUuid, "share-1", "https://x", listOf(slot), emptyList())

        val recorded = server.takeRequest()
        assertEquals(file.length(), recorded.bodySize)
    }

    @Test
    fun `a failing item is retried exactly once then accumulated as failed, batch continues`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500)) // photo/1 attempt 1
        server.enqueue(MockResponse().setResponseCode(500)) // photo/1 attempt 2 (the one automatic retry)
        server.enqueue(MockResponse().setResponseCode(200)) // photo/2
        server.enqueue(MockResponse().setResponseCode(200)) // audio/1

        val photos = listOf(photoUploadSlot(1), photoUploadSlot(2))
        val audio = listOf(audioUploadSlot(1))

        val result = service.uploadMediaBounded(
            walkUuid, "share-1", "https://walk.pilgrimapp.org/share-1", photos, audio, retryBackoffMs = 5L,
        )

        assertEquals(4, server.requestCount)
        assertEquals(1, result.failedPhotoCount)
        assertEquals(0, result.failedAudioCount)
        assertEquals(1, result.failedCount)
        assertTrue(result.repairable)
        assertEquals("https://walk.pilgrimapp.org/share-1", result.url)

        val record = requireNotNull(repairStore.load(walkUuid))
        assertEquals(SlotStatus.PENDING, record.slots.first { it.kind == SlotKind.PHOTO && it.n == 1 }.status)
        assertEquals(SlotStatus.UPLOADED, record.slots.first { it.kind == SlotKind.PHOTO && it.n == 2 }.status)
        assertEquals(SlotStatus.UPLOADED, record.slots.first { it.kind == SlotKind.AUDIO && it.n == 1 }.status)
    }

    @Test
    fun `a retried item that succeeds on the second attempt counts as success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500)) // attempt 1 fails
        server.enqueue(MockResponse().setResponseCode(200)) // retry succeeds

        val result = service.uploadMediaBounded(
            walkUuid, "share-1", "https://x", listOf(photoUploadSlot(1)), emptyList(), retryBackoffMs = 5L,
        )

        assertEquals(2, server.requestCount)
        assertEquals(0, result.failedCount)
        assertNull("full success must clear the repair record", repairStore.load(walkUuid))
    }

    @Test
    fun `full success clears the repair record`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        service.uploadMedia(walkUuid, "share-1", "https://x", listOf(photoUploadSlot(1)), listOf(audioUploadSlot(1)))

        assertNull(repairStore.load(walkUuid))
    }

    @Test
    fun `a clean batch that leaves other slots pending keeps the record`() = runBlocking {
        // A repair pass hands this function only the slots it could
        // resolve; the ones it could not stay PENDING in the record and
        // are never mentioned by the batch. Clearing on "this batch had
        // no failures" would delete the ledger for media that is still
        // genuinely missing — and a kill in that window leaves a Success
        // card over a page short of files, with the repair offer gone.
        repairStore.prePopulate(
            walkUuid,
            "share-1",
            listOf(
                RepairSlot(SlotKind.AUDIO, 1, SlotIdentity.Audio("rec-1"), SlotStatus.PENDING),
                RepairSlot(SlotKind.AUDIO, 2, SlotIdentity.Audio("rec-2"), SlotStatus.PENDING),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val result = service.uploadMedia(walkUuid, "share-1", "https://x", emptyList(), listOf(audioUploadSlot(1)))

        assertEquals("this batch itself resolved cleanly", 0, result.failedCount)
        val record = repairStore.load(walkUuid)
        assertNotNull("the unmentioned pending slot's ledger must survive a clean batch", record)
        assertEquals(
            listOf(SlotStatus.UPLOADED, SlotStatus.PENDING),
            record!!.slots.sortedBy { it.n }.map { it.status },
        )
    }

    @Test
    fun `identity mismatch on an already-recorded slot refuses the upload without a PUT`() = runBlocking {
        // A stale record already claims photo/1 belongs to a DIFFERENT source photo than the one about to be offered.
        repairStore.prePopulate(
            walkUuid,
            "share-1",
            listOf(RepairSlot(SlotKind.PHOTO, 1, SlotIdentity.Photo("content://stale-source", 1L), SlotStatus.PENDING)),
        )
        server.enqueue(MockResponse().setResponseCode(200)) // audio/1's response — must be the ONLY request served

        val photos = listOf(photoUploadSlot(1, uri = "content://a-different-source"))
        val audio = listOf(audioUploadSlot(1))

        val result = service.uploadMedia(walkUuid, "share-1", "https://x", photos, audio)

        assertEquals(1, result.failedPhotoCount)
        assertEquals(0, result.failedAudioCount)
        assertEquals("a refused slot must never reach the network", 1, server.requestCount)

        val record = requireNotNull(repairStore.load(walkUuid))
        assertEquals(
            "the refused slot's ORIGINAL identity must survive untouched",
            SlotIdentity.Photo("content://stale-source", 1L),
            record.slots.first { it.kind == SlotKind.PHOTO && it.n == 1 }.identity,
        )
    }

    @Test
    fun `every slot is durably pending before any PUT is attempted`() = runBlocking {
        // No responses enqueued at all: if a PUT were ever attempted before
        // cancellation landed, requestCount would be nonzero below.
        val photos = listOf(photoUploadSlot(1), photoUploadSlot(2))
        val audio = listOf(audioUploadSlot(1))

        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val deferred = scope.async {
            service.uploadMedia(
                walkUuid, "share-1", "https://x", photos, audio,
                onProgress = { completed, _ -> if (completed == 0) job.cancel() },
            )
        }
        val outcome = runCatching { deferred.await() }

        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertEquals(0, server.requestCount)

        val record = requireNotNull(repairStore.load(walkUuid))
        assertEquals(3, record.slots.size)
        assertTrue(record.slots.all { it.status == SlotStatus.PENDING })
    }

    @Test
    fun `cancellation mid-batch marks the untried tail pending, leaves completed slots completed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)) // photo/1 — allowed to land
        // Nothing else enqueued: photo/2 and audio/1 must never be attempted.

        val photos = listOf(photoUploadSlot(1), photoUploadSlot(2))
        val audio = listOf(audioUploadSlot(1))

        val job = Job()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val deferred = scope.async {
            service.uploadMedia(
                walkUuid, "share-1", "https://x", photos, audio,
                onProgress = { completed, _ -> if (completed == 1) job.cancel() },
            )
        }
        val outcome = runCatching { deferred.await() }

        assertTrue("uploadMedia must propagate CancellationException, never swallow it", outcome.exceptionOrNull() is CancellationException)
        assertEquals("only photo/1's PUT may have been sent", 1, server.requestCount)

        val record = requireNotNull(repairStore.load(walkUuid))
        assertEquals(SlotStatus.UPLOADED, record.slots.first { it.kind == SlotKind.PHOTO && it.n == 1 }.status)
        assertEquals(SlotStatus.PENDING, record.slots.first { it.kind == SlotKind.PHOTO && it.n == 2 }.status)
        assertEquals(SlotStatus.PENDING, record.slots.first { it.kind == SlotKind.AUDIO && it.n == 1 }.status)
    }

    @Test
    fun `an already-uploaded slot from a prior partial call is not re-PUT on a repair pass`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)) // photo/1 succeeds
        server.enqueue(MockResponse().setResponseCode(500)) // photo/2 attempt 1 fails
        server.enqueue(MockResponse().setResponseCode(500)) // photo/2 attempt 2 (the one retry) also fails

        val firstResult = service.uploadMediaBounded(
            walkUuid, "share-1", "https://x", listOf(photoUploadSlot(1), photoUploadSlot(2)), emptyList(),
            retryBackoffMs = 5L,
        )
        assertEquals(1, firstResult.failedPhotoCount)
        assertEquals(3, server.requestCount)
        assertNotNull("a partial result must leave a repair record", repairStore.load(walkUuid))

        // Repair pass: re-offer BOTH slots, exactly as a caller unaware of per-slot status might —
        // photo/1 (already uploaded) must be skipped; photo/2 gets exactly one more real attempt.
        server.enqueue(MockResponse().setResponseCode(200)) // photo/2 succeeds this time

        val secondResult = service.uploadMediaBounded(
            walkUuid, "share-1", "https://x", listOf(photoUploadSlot(1), photoUploadSlot(2)), emptyList(),
            retryBackoffMs = 5L,
        )

        assertEquals(0, secondResult.failedCount)
        assertEquals(
            "only photo/2's single repair attempt should have been sent — photo/1 must not be re-PUT",
            4,
            server.requestCount,
        )
        assertNull("full success on the repair pass must clear the record", repairStore.load(walkUuid))
    }
}
