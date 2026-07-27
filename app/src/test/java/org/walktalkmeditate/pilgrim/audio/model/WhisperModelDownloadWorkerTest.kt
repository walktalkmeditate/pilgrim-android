// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository

/**
 * Exercises [WhisperModelDownloadWorker.doWork] via
 * `TestListenableWorkerBuilder` + MockWebServer against the U9
 * delivery contract (`docs/parity/2026-07-26-port-model-download-u9.md`):
 * resume (Range/If-Range from the partial), restart (etag change,
 * missing etag, 416), bounded write (Content-Length + streamed
 * oversize), checksum retry → cap terminal, storage precheck, writer
 * mutex handoff, and the success side-effect chain. The injected
 * [WhisperModelDownloadSpec] carries a small payload's real SHA-256,
 * so every path runs the production protocol end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WhisperModelDownloadWorkerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var spec: WhisperModelDownloadSpec
    private lateinit var files: ModelDownloadFiles
    private lateinit var store: RecordingStore
    private lateinit var storeScope: CoroutineScope
    private lateinit var voicePreferences: FakeVoicePreferencesRepository
    private lateinit var transcriptionScheduler: FakeTranscriptionScheduler

    private var availableBytes: Long = Long.MAX_VALUE
    private var pendingWalkIds: List<Long> = emptyList()

    private val payload = ByteArray(PAYLOAD_SIZE) { (it % 251).toByte() }
    private val payloadSha by lazy { sha256Hex(payload) }

    private val modelRoot: File get() = File(context.filesDir, "whisper-model")
    private val modelFile: File get() = File(modelRoot, "base/ggml-base.bin")
    private val markerFile: File get() = File(modelRoot, "base/ggml-base.bin.sha256")
    private val partialFile: File get() = File(modelRoot, "base/ggml-base.bin.part")
    private val etagFile: File get() = File(modelRoot, "base/ggml-base.bin.etag")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelRoot.deleteRecursively()
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        spec = WhisperModelDownloadSpec(
            url = server.url("/models/ggml-base.bin").toString(),
            expectedBytes = PAYLOAD_SIZE.toLong(),
            expectedSha256 = payloadSha,
        )
        files = ModelDownloadFiles()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = RecordingStore(context, storeScope, modelFile, markerFile, partialFile)
        voicePreferences = FakeVoicePreferencesRepository(initialAutoTranscribe = true)
        transcriptionScheduler = FakeTranscriptionScheduler()
        availableBytes = Long.MAX_VALUE
        pendingWalkIds = emptyList()
    }

    @After fun tearDown() {
        storeScope.cancel()
        server.shutdown()
        modelRoot.deleteRecursively()
    }

    private fun buildWorker(
        runAttemptCount: Int = 0,
        progressUpdates: MutableList<Data>? = null,
    ): WhisperModelDownloadWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = WhisperModelDownloadWorker(
                appContext = appContext,
                params = workerParameters,
                httpClient = httpClient,
                spec = spec,
                files = files,
                freeSpaceProbe = { availableBytes },
                store = store,
                voicePreferences = voicePreferences,
                pendingWalks = { pendingWalkIds },
                transcriptionScheduler = transcriptionScheduler,
            )
        }
        return TestListenableWorkerBuilder<WhisperModelDownloadWorker>(context)
            .setWorkerFactory(factory)
            .setRunAttemptCount(runAttemptCount)
            .apply {
                if (progressUpdates != null) {
                    setProgressUpdater { _, _, data ->
                        progressUpdates.add(data)
                        ImmediateVoidFuture
                    }
                }
            }
            .build()
    }

    private fun seedPartial(bytes: ByteArray, etag: String? = null) {
        partialFile.parentFile?.mkdirs()
        partialFile.writeBytes(bytes)
        if (etag != null) etagFile.writeText(etag)
    }

    private fun bodyOf(bytes: ByteArray): MockResponse =
        MockResponse().setBody(okio.Buffer().write(bytes))

    // C4 happy path + C6 ordering: verify → rename → marker LAST → hook.
    @Test fun `full download verifies, renames, writes the marker, and fires the success chain`() {
        server.enqueue(bodyOf(payload).setHeader("ETag", "\"v1\""))
        val progress = mutableListOf<Data>()
        val result = runBlocking { buildWorker(progressUpdates = progress).doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertArrayEquals(payload, modelFile.readBytes())
        assertEquals(payloadSha, markerFile.readText())
        assertFalse(partialFile.exists())
        assertFalse(etagFile.exists())

        assertEquals(1, store.hookCount)
        assertEquals(1, store.invalidateCount)
        assertTrue(store.modelExistedAtHook)
        assertTrue(store.markerExistedAtHook)
        assertFalse(store.partialExistedAtHook)

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertNull(request.getHeader("Range"))

        assertTrue(progress.isNotEmpty())
        assertEquals(
            PAYLOAD_SIZE.toLong(),
            progress.first().getLong(WhisperModelDownloadWorker.KEY_TOTAL_BYTES, -1L),
        )
        assertFalse(progress.first().getBoolean(WhisperModelDownloadWorker.KEY_VERIFYING, true))
        assertTrue(progress.last().getBoolean(WhisperModelDownloadWorker.KEY_VERIFYING, false))
        assertEquals(
            PAYLOAD_SIZE.toLong(),
            progress.last().getLong(WhisperModelDownloadWorker.KEY_BYTES_DOWNLOADED, -1L),
        )
    }

    @Test fun `already delivered returns success without network traffic or a re-fired hook`() {
        modelFile.parentFile?.mkdirs()
        modelFile.writeBytes(payload)
        markerFile.writeText(payloadSha)

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, server.requestCount)
        assertEquals(0, store.hookCount)
    }

    // C3 resume: Range from the partial's length, If-Range with the
    // stored etag, prefix hashed into the digest so the FULL file
    // verifies against the pinned sha.
    @Test fun `resume sends Range from the partial length and completes with a valid full-file digest`() {
        seedPartial(payload.copyOfRange(0, 100), etag = "\"v1\"")
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setBody(okio.Buffer().write(payload.copyOfRange(100, PAYLOAD_SIZE))),
        )

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertArrayEquals(payload, modelFile.readBytes())
        assertEquals(payloadSha, markerFile.readText())

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("bytes=100-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
    }

    // The seeded partial is garbage: success is only possible if the
    // 200 response truly restarted the file from zero.
    @Test fun `etag change answers 200 and the partial is discarded for a full restart`() {
        seedPartial(ByteArray(100) { 0x7F }, etag = "\"v1\"")
        server.enqueue(bodyOf(payload).setHeader("ETag", "\"v2\""))

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertArrayEquals(payload, modelFile.readBytes())
        assertFalse(etagFile.exists())

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("bytes=100-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
    }

    @Test fun `partial without a stored etag restarts from zero with a plain GET`() {
        seedPartial(ByteArray(100) { 0x7F }, etag = null)
        server.enqueue(bodyOf(payload))

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertArrayEquals(payload, modelFile.readBytes())
        assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Range"))
    }

    // C3 + C5: a dropped connection keeps the partial (never terminal),
    // and the next attempt resumes from exactly the bytes that landed.
    @Test fun `interrupted transfer keeps the partial and a later attempt resumes to completion`() {
        server.enqueue(
            bodyOf(payload)
                .setHeader("ETag", "\"v1\"")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val first = runBlocking { buildWorker().doWork() }
        assertEquals(ListenableWorker.Result.retry(), first)
        assertTrue(partialFile.exists())
        val landed = partialFile.length()
        assertTrue(landed in 1 until PAYLOAD_SIZE.toLong())
        assertEquals("\"v1\"", etagFile.readText())

        server.enqueue(
            MockResponse().setResponseCode(206)
                .setBody(okio.Buffer().write(payload.copyOfRange(landed.toInt(), PAYLOAD_SIZE))),
        )
        val second = runBlocking { buildWorker(runAttemptCount = 1).doWork() }

        assertEquals(ListenableWorker.Result.success(), second)
        assertArrayEquals(payload, modelFile.readBytes())
        server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("bytes=$landed-", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Range"))
    }

    @Test fun `corrupt payload below the cap retries with partial and etag deleted`() {
        val corrupt = payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
        server.enqueue(bodyOf(corrupt).setHeader("ETag", "\"v1\""))

        val result = runBlocking { buildWorker(runAttemptCount = 0).doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse(partialFile.exists())
        assertFalse(etagFile.exists())
        assertFalse(modelFile.exists())
        // Marker only ever lands after a successful verify (C4).
        assertFalse(markerFile.exists())
        assertEquals(0, store.hookCount)
    }

    @Test fun `corrupt payload at the attempt cap is terminal checksum with partial and etag deleted`() {
        val corrupt = payload.copyOf().also { it[10] = (it[10] + 1).toByte() }
        server.enqueue(bodyOf(corrupt))

        val result = runBlocking {
            buildWorker(runAttemptCount = WhisperModelDownloadWorker.CHECKSUM_ATTEMPT_CAP).doWork()
        }

        val failure = result as ListenableWorker.Result.Failure
        assertEquals(
            WhisperModelDownloadWorker.REASON_CHECKSUM,
            failure.outputData.getString(WhisperModelDownloadWorker.KEY_FAILURE_REASON),
        )
        assertFalse(partialFile.exists())
        assertFalse(etagFile.exists())
    }

    @Test fun `oversize content length aborts terminally before streaming`() {
        server.enqueue(bodyOf(payload + ByteArray(64)))

        val result = runBlocking { buildWorker().doWork() }

        val failure = result as ListenableWorker.Result.Failure
        assertEquals(
            WhisperModelDownloadWorker.REASON_CHECKSUM,
            failure.outputData.getString(WhisperModelDownloadWorker.KEY_FAILURE_REASON),
        )
        assertFalse(partialFile.exists())
    }

    @Test fun `oversize chunked stream aborts at the byte bound`() {
        server.enqueue(
            MockResponse().setChunkedBody(okio.Buffer().write(payload + ByteArray(64)), 1024),
        )

        val result = runBlocking { buildWorker().doWork() }

        val failure = result as ListenableWorker.Result.Failure
        assertEquals(
            WhisperModelDownloadWorker.REASON_CHECKSUM,
            failure.outputData.getString(WhisperModelDownloadWorker.KEY_FAILURE_REASON),
        )
        assertFalse(partialFile.exists())
    }

    @Test fun `storage precheck failure is terminal storage with no network call and the partial kept`() {
        seedPartial(payload.copyOfRange(0, 100), etag = "\"v1\"")
        availableBytes = 1_000L

        val result = runBlocking { buildWorker().doWork() }

        val failure = result as ListenableWorker.Result.Failure
        assertEquals(
            WhisperModelDownloadWorker.REASON_STORAGE,
            failure.outputData.getString(WhisperModelDownloadWorker.KEY_FAILURE_REASON),
        )
        assertEquals(0, server.requestCount)
        assertEquals(100L, partialFile.length())
        assertTrue(etagFile.exists())
    }

    @Test fun `416 discards the incoherent partial and retries`() {
        seedPartial(ByteArray(100) { 0x11 }, etag = "\"v1\"")
        server.enqueue(MockResponse().setResponseCode(416))

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse(partialFile.exists())
        assertFalse(etagFile.exists())
    }

    @Test fun `server error retries and keeps the partial for a later resume`() {
        seedPartial(payload.copyOfRange(0, 100), etag = "\"v1\"")
        server.enqueue(MockResponse().setResponseCode(503))

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(100L, partialFile.length())
        assertEquals("\"v1\"", etagFile.readText())
    }

    // C6: REPLACE re-kick (recorded on the fake's dedicated list) is
    // gated on the auto-transcribe preference.
    @Test fun `success re-kicks pending transcription walks with REPLACE when auto-transcribe is on`() {
        pendingWalkIds = listOf(7L, 9L)
        server.enqueue(bodyOf(payload))

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(7L, 9L), transcriptionScheduler.rescheduledWalkIds)
        assertTrue(transcriptionScheduler.scheduledWalkIds.isEmpty())
    }

    @Test fun `success does not re-kick transcription when auto-transcribe is off`() {
        runBlocking { voicePreferences.setAutoTranscribe(false) }
        pendingWalkIds = listOf(7L)
        server.enqueue(bodyOf(payload))

        val result = runBlocking { buildWorker().doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(transcriptionScheduler.rescheduledWalkIds.isEmpty())
    }

    // C3 mutex handoff: the replacement blocks until the incumbent
    // unwinds, then re-probes the partial's length — the Range request
    // must reflect bytes the incumbent flushed while unwinding.
    @Test fun `replacement writer blocks on the mutex and re-probes the partial length after handoff`() {
        seedPartial(payload.copyOfRange(0, 100), etag = "\"v1\"")

        val result = runBlocking {
            files.writerMutex.lock()
            val inFlight = async(Dispatchers.Default) { buildWorker().doWork() }
            try {
                delay(200)
                assertEquals(0, server.requestCount)
                partialFile.appendBytes(payload.copyOfRange(100, 150))
                server.enqueue(
                    MockResponse().setResponseCode(206)
                        .setBody(okio.Buffer().write(payload.copyOfRange(150, PAYLOAD_SIZE))),
                )
            } finally {
                files.writerMutex.unlock()
            }
            inFlight.await()
        }

        assertEquals(ListenableWorker.Result.success(), result)
        assertArrayEquals(payload, modelFile.readBytes())
        assertEquals("bytes=150-", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Range"))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(Locale.US, "%02x", it) }

    private companion object {
        const val PAYLOAD_SIZE = 8_192
    }

    /**
     * Real store over the real Robolectric filesystem, with the U10
     * hook + invalidate overridden to record the success chain: at
     * hook time the model and marker must already exist and the
     * partial must be gone (C6 ordering).
     */
    private class RecordingStore(
        context: Context,
        scope: CoroutineScope,
        private val modelFile: File,
        private val markerFile: File,
        private val partialFile: File,
    ) : WhisperModelStore(
        context = context,
        workSource = object : ModelDownloadWorkSource {
            override fun observe(): Flow<ModelDownloadWork?> = flowOf(null)
        },
        unmeteredProbe = { true },
        scope = scope,
    ) {
        var hookCount = 0
        var invalidateCount = 0
        var modelExistedAtHook = false
        var markerExistedAtHook = false
        var partialExistedAtHook = true

        override suspend fun onBaseVerified() {
            hookCount++
            modelExistedAtHook = modelFile.exists()
            markerExistedAtHook = markerFile.exists()
            partialExistedAtHook = partialFile.exists()
        }

        override fun invalidate() {
            invalidateCount++
        }
    }

    private object ImmediateVoidFuture : ListenableFuture<Void> {
        override fun addListener(listener: Runnable, executor: Executor) {
            executor.execute(listener)
        }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): Void? = null
        override fun get(timeout: Long, unit: TimeUnit): Void? = null
    }
}
