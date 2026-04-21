// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

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
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifest
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundscapeDownloadWorkerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var json: Json
    private lateinit var fileStore: SoundscapeFileStore
    private lateinit var manifestScope: CoroutineScope
    private lateinit var manifestService: AudioManifestService

    private val soundscapeRoot: File
        get() = File(context.filesDir, "audio/soundscape")
    private val manifestCache: File
        get() = File(context.filesDir, "audio_manifest.json")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        soundscapeRoot.deleteRecursively()
        manifestCache.delete()
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fileStore = SoundscapeFileStore(context)
        manifestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        manifestScope.cancel()
        server.shutdown()
        soundscapeRoot.deleteRecursively()
        manifestCache.delete()
    }

    private fun asset(id: String, size: Long = 10L, type: String = AudioAssetType.SOUNDSCAPE) =
        AudioAsset(
            id = id,
            type = type,
            name = id,
            displayName = id,
            durationSec = 120.0,
            r2Key = "soundscape/$id.aac",
            fileSizeBytes = size,
        )

    private fun seedManifest(assets: List<AudioAsset>) {
        manifestCache.writeText(
            json.encodeToString(AudioManifest(version = "v1", assets = assets)),
        )
        manifestService = AudioManifestService(
            context = context as Application,
            httpClient = httpClient,
            json = json,
            scope = manifestScope,
            manifestUrl = server.url("/manifest.json").toString(),
        )
        runBlocking {
            manifestScope.coroutineContext[Job]?.children?.forEach { it.join() }
        }
    }

    private fun buildWorker(assetId: String): SoundscapeDownloadWorker {
        val baseUrl = server.url("/").toString()
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = SoundscapeDownloadWorker(
                appContext = appContext,
                params = workerParameters,
                manifestService = manifestService,
                fileStore = fileStore,
                httpClient = httpClient,
                baseUrl = baseUrl,
            )
        }
        return TestListenableWorkerBuilder<SoundscapeDownloadWorker>(context)
            .setInputData(workDataOf(SoundscapeDownloadWorker.KEY_ASSET_ID to assetId))
            .setWorkerFactory(factory)
            .build()
    }

    @Test fun `missing assetId returns failure`() = runBlocking {
        seedManifest(emptyList())
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = SoundscapeDownloadWorker(
                appContext = appContext,
                params = workerParameters,
                manifestService = manifestService,
                fileStore = fileStore,
                httpClient = httpClient,
                baseUrl = server.url("/").toString(),
            )
        }
        val worker = TestListenableWorkerBuilder<SoundscapeDownloadWorker>(context)
            .setWorkerFactory(factory)
            .build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test fun `unknown assetId returns failure`() = runBlocking {
        seedManifest(emptyList())
        val worker = buildWorker("nonexistent")
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test fun `bell-typed asset (wrong type) returns failure`() = runBlocking {
        // Guard: the worker should only download soundscapes, not bells —
        // even if the id exists in the manifest.
        seedManifest(listOf(asset("chime", type = AudioAssetType.BELL)))
        val worker = buildWorker("chime")
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test fun `already-available asset returns success without fetching`() = runBlocking {
        val a = asset("forest", size = 10)
        seedManifest(listOf(a))
        fileStore.fileFor(a).writeBytes(ByteArray(10))

        val worker = buildWorker("forest")
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, server.requestCount)
    }

    @Test fun `downloads asset when missing and reports success`() = runBlocking {
        val a = asset("river", size = 5)
        seedManifest(listOf(a))

        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(5))))

        val worker = buildWorker("river")
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(fileStore.isAvailable(a))
    }

    @Test fun `503 triggers single retry then reports retry`() = runBlocking {
        val a = asset("rain", size = 5)
        seedManifest(listOf(a))

        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))

        val worker = buildWorker("rain")
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse(fileStore.isAvailable(a))
        assertEquals(2, server.requestCount)
    }

    @Test fun `size mismatch deletes tmp and reports retry`() = runBlocking {
        val a = asset("mismatch", size = 10)
        seedManifest(listOf(a))

        // Serve 5 bytes twice — size doesn't match expected 10.
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(5))))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(5))))

        val worker = buildWorker("mismatch")
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertFalse(fileStore.isAvailable(a))
        val tmp = File(fileStore.fileFor(a).parentFile, fileStore.fileFor(a).name + ".tmp")
        assertFalse(tmp.exists())
    }
}
