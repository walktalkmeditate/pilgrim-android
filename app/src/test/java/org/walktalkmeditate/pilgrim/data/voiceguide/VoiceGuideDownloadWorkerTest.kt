// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [VoiceGuideDownloadWorker.doWork] directly via
 * `TestListenableWorkerBuilder`. Validates:
 *  - all-missing → all prompts fetched, Result.success
 *  - some present → only missing prompts fetched
 *  - network 503 on a prompt → single retry, skipped on second fail,
 *    worker returns Result.retry (partial pack)
 *  - size mismatch → tmp cleaned up, prompt treated as missing
 *  - no pack id input → Result.failure
 *  - unknown pack id → Result.failure
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideDownloadWorkerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var json: Json
    private lateinit var fileStore: VoiceGuideFileStore
    private lateinit var manifestScope: CoroutineScope
    private lateinit var manifestService: VoiceGuideManifestService

    private val promptsRoot: File
        get() = File(context.filesDir, "voice_guide_prompts")
    private val manifestCache: File
        get() = File(context.filesDir, "voice_guide_manifest.json")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        promptsRoot.deleteRecursively()
        manifestCache.delete()
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fileStore = VoiceGuideFileStore(context)
        manifestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        manifestScope.cancel()
        server.shutdown()
        promptsRoot.deleteRecursively()
        manifestCache.delete()
    }

    private fun prompt(r2Key: String, size: Long) = VoiceGuidePrompt(
        id = r2Key.substringAfterLast('/'),
        seq = 1,
        durationSec = 1.0,
        fileSizeBytes = size,
        r2Key = r2Key,
    )

    private fun pack(id: String, prompts: List<VoiceGuidePrompt>) = VoiceGuidePack(
        id = id, version = "1", name = id, tagline = "", description = "",
        theme = "", iconName = "", type = "walk", walkTypes = emptyList(),
        scheduling = PromptDensity(0, 0, 0, 0, 0),
        totalDurationSec = 0.0, totalSizeBytes = 0L,
        prompts = prompts,
    )

    /** Seed the manifest cache so [VoiceGuideManifestService] reports the pack. */
    private fun seedManifest(packs: List<VoiceGuidePack>) {
        val manifest = VoiceGuideManifest(version = "v1", packs = packs)
        manifestCache.writeText(json.encodeToString(manifest))
        // Construct the service, then wait for its async init to load
        // the cache so subsequent worker invocations see `pack(id = ...)`.
        manifestService = VoiceGuideManifestService(
            context = context,
            httpClient = httpClient,
            json = json,
            scope = manifestScope,
            manifestUrl = server.url("/manifest.json").toString(),
        )
        runBlocking {
            manifestScope.coroutineContext[Job]?.children?.forEach { it.join() }
        }
    }

    private fun buildWorker(packId: String): VoiceGuideDownloadWorker {
        val baseUrl = server.url("/").toString()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = VoiceGuideDownloadWorker(
                appContext = appContext,
                params = workerParameters,
                manifestService = manifestService,
                fileStore = fileStore,
                httpClient = httpClient,
                promptBaseUrl = baseUrl,
            )
        }
        return TestListenableWorkerBuilder<VoiceGuideDownloadWorker>(context)
            .setInputData(workDataOf(VoiceGuideDownloadWorker.KEY_PACK_ID to packId))
            .setWorkerFactory(factory)
            .build()
    }

    @Test fun `missing packId input returns failure`() = runBlocking {
        // Build a manifest-less service to exercise the missing-input path.
        seedManifest(emptyList())
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = VoiceGuideDownloadWorker(
                appContext = appContext,
                params = workerParameters,
                manifestService = manifestService,
                fileStore = fileStore,
                httpClient = httpClient,
                promptBaseUrl = server.url("/").toString(),
            )
        }
        val worker = TestListenableWorkerBuilder<VoiceGuideDownloadWorker>(context)
            .setWorkerFactory(factory)
            .build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test fun `unknown packId returns failure`() = runBlocking {
        seedManifest(emptyList())
        val worker = buildWorker("nonexistent")
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test fun `all prompts missing are fetched and pack succeeds`() = runBlocking {
        val p1 = prompt("p/a.aac", size = 5)
        val p2 = prompt("p/b.aac", size = 3)
        val pk = pack("p", listOf(p1, p2))
        seedManifest(listOf(pk))

        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(5))))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(3))))

        val worker = buildWorker("p")
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(fileStore.isPromptAvailable(p1))
        assertTrue(fileStore.isPromptAvailable(p2))
        assertEquals(2, server.requestCount)
    }

    @Test fun `present prompt is skipped, only missing fetched`() = runBlocking {
        val p1 = prompt("p/a.aac", size = 5)
        val p2 = prompt("p/b.aac", size = 3)
        val pk = pack("p", listOf(p1, p2))
        seedManifest(listOf(pk))

        // Pre-populate p1
        fileStore.fileForPrompt(p1.r2Key).writeBytes(ByteArray(5))

        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(3))))

        val worker = buildWorker("p")
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, server.requestCount)
    }

    @Test fun `503 on a prompt triggers retry, then skip if still failing`() = runBlocking {
        val p1 = prompt("p/a.aac", size = 5)
        val pk = pack("p", listOf(p1))
        seedManifest(listOf(pk))

        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))

        val worker = buildWorker("p")
        val result = worker.doWork()

        // Pack not downloaded → Result.retry, not success or failure.
        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse(fileStore.isPromptAvailable(p1))
        assertEquals(2, server.requestCount)
    }

    @Test fun `size mismatch deletes tmp and leaves prompt missing`() = runBlocking {
        val p1 = prompt("p/a.aac", size = 10) // expect 10 bytes
        val pk = pack("p", listOf(p1))
        seedManifest(listOf(pk))

        // Serve only 5 bytes for the first attempt, only 5 for the retry.
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(5))))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(5))))

        val worker = buildWorker("p")
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse(fileStore.isPromptAvailable(p1))
        // Tmp file must be cleaned up.
        val tmp = File(
            fileStore.fileForPrompt(p1.r2Key).parentFile,
            fileStore.fileForPrompt(p1.r2Key).name + ".tmp",
        )
        assertFalse(tmp.exists())
    }

    @Test fun `retry on first failure then success downloads remaining`() = runBlocking {
        val p1 = prompt("p/a.aac", size = 3)
        val pk = pack("p", listOf(p1))
        seedManifest(listOf(pk))

        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(3))))

        val worker = buildWorker("p")
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(fileStore.isPromptAvailable(p1))
        assertEquals(2, server.requestCount)
    }
}
