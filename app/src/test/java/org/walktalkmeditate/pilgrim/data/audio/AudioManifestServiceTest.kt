// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for [AudioManifestService] — mirrors
 * `VoiceGuideManifestServiceTest` shape. Uses real `Dispatchers`
 * + joins the service scope's children to bridge async work to the
 * test thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioManifestServiceTest {

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var json: Json
    private lateinit var scope: CoroutineScope

    private val cacheFile: File
        get() = File(context.filesDir, "audio_manifest.json")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheFile.delete()
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
        cacheFile.delete()
    }

    private fun buildService(url: String = server.url("/manifest.json").toString()) =
        AudioManifestService(
            context = context,
            httpClient = httpClient,
            json = json,
            scope = scope,
            manifestUrl = url,
        )

    private fun awaitScope() = runBlocking {
        scope.coroutineContext[Job]?.children?.forEach { it.join() }
    }

    private fun buildServiceAndWaitForInit(
        url: String = server.url("/manifest.json").toString(),
    ): AudioManifestService = buildService(url).also { awaitScope() }

    private fun manifest(
        version: String = "2026-01-01",
        assets: List<AudioAsset> = emptyList(),
    ) = AudioManifest(version = version, assets = assets)

    private fun soundscape(id: String = "forest") = AudioAsset(
        id = id,
        type = AudioAssetType.SOUNDSCAPE,
        name = id,
        displayName = id,
        durationSec = 120.0,
        r2Key = "soundscape/$id.aac",
        fileSizeBytes = 1024L,
    )

    private fun bell(id: String = "chime") = AudioAsset(
        id = id,
        type = AudioAssetType.BELL,
        name = id,
        displayName = id,
        durationSec = 3.0,
        r2Key = "bell/$id.aac",
        fileSizeBytes = 256L,
    )

    @Test fun `init with no cache emits empty list`() {
        val service = buildService()
        assertTrue(service.assets.value.isEmpty())
    }

    @Test fun `init loads existing valid cache into assets`() {
        val cached = manifest(
            version = "cached-1",
            assets = listOf(soundscape("seeded"), bell("seeded-bell")),
        )
        cacheFile.writeText(json.encodeToString(cached))

        val service = buildServiceAndWaitForInit()
        assertEquals(2, service.assets.value.size)
        assertEquals(1, service.soundscapes().size)
        assertEquals("seeded", service.soundscapes().first().id)
    }

    @Test fun `init with corrupt cache treats as absent and leaves file alone`() {
        cacheFile.writeText("{ not json")

        val service = buildServiceAndWaitForInit()
        assertTrue(service.assets.value.isEmpty())
        assertTrue(cacheFile.exists())
    }

    @Test fun `syncIfNeeded fetches remote and writes cache when no local exists`() {
        val remote = manifest(version = "v1", assets = listOf(soundscape("a")))
        server.enqueue(MockResponse().setBody(json.encodeToString(remote)))
        val service = buildServiceAndWaitForInit()

        service.syncIfNeeded()
        awaitScope()

        assertEquals(1, service.assets.value.size)
        assertEquals("a", service.assets.value.first().id)
        assertTrue(cacheFile.exists())
        val onDisk = json.decodeFromString<AudioManifest>(cacheFile.readText())
        assertEquals("v1", onDisk.version)
    }

    @Test fun `network failure leaves cached state intact`() {
        val cached = manifest(version = "good", assets = listOf(soundscape("keep")))
        cacheFile.writeText(json.encodeToString(cached))
        server.enqueue(MockResponse().setResponseCode(503))
        val service = buildServiceAndWaitForInit()

        service.syncIfNeeded()
        awaitScope()

        assertEquals(1, service.assets.value.size)
        assertEquals("keep", service.assets.value.first().id)
    }

    @Test fun `version unchanged does not rewrite cache file`() {
        val same = manifest(version = "v1", assets = listOf(soundscape("x")))
        cacheFile.writeText(json.encodeToString(same))
        val originalMtime = cacheFile.lastModified()
        Thread.sleep(1_100)
        server.enqueue(MockResponse().setBody(json.encodeToString(same)))
        val service = buildServiceAndWaitForInit()

        service.syncIfNeeded()
        awaitScope()

        assertEquals(originalMtime, cacheFile.lastModified())
    }

    @Test fun `version changed overwrites cache atomically`() {
        val old = manifest(version = "v1", assets = listOf(soundscape("a")))
        cacheFile.writeText(json.encodeToString(old))
        val new = manifest(version = "v2", assets = listOf(soundscape("b")))
        server.enqueue(MockResponse().setBody(json.encodeToString(new)))
        val service = buildServiceAndWaitForInit()

        service.syncIfNeeded()
        awaitScope()

        val onDisk = json.decodeFromString<AudioManifest>(cacheFile.readText())
        assertEquals("v2", onDisk.version)
        assertEquals("b", service.assets.value.first().id)
        val tmp = File(cacheFile.parentFile, "audio_manifest.json.tmp")
        assertFalse(tmp.exists())
    }

    @Test fun `concurrent syncIfNeeded calls dedupe to single request`() {
        val remote = manifest(version = "v1", assets = listOf(soundscape("a")))
        server.enqueue(MockResponse().setBody(json.encodeToString(remote)))
        val service = buildServiceAndWaitForInit()

        service.syncIfNeeded()
        service.syncIfNeeded()
        service.syncIfNeeded()
        awaitScope()

        assertEquals(1, server.requestCount)
    }

    @Test fun `soundscapes() filters to type soundscape only`() {
        val cached = manifest(
            version = "v1",
            assets = listOf(
                soundscape("s1"),
                bell("b1"),
                soundscape("s2"),
                bell("b2"),
            ),
        )
        cacheFile.writeText(json.encodeToString(cached))
        val service = buildServiceAndWaitForInit()

        assertEquals(2, service.soundscapes().size)
        assertEquals(setOf("s1", "s2"), service.soundscapes().map { it.id }.toSet())
    }

    @Test fun `asset byId returns null for unknown`() {
        val cached = manifest(version = "v1", assets = listOf(soundscape("alpha")))
        cacheFile.writeText(json.encodeToString(cached))
        val service = buildServiceAndWaitForInit()

        assertNull(service.asset(id = "missing"))
        assertNotNull(service.asset(id = "alpha"))
    }
}
