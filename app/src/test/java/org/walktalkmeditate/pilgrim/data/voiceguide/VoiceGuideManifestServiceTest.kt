// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
 * Integration tests for [VoiceGuideManifestService] covering the
 * cold-start cache path, network sync, atomic cache rewrite, and
 * concurrent-call dedup.
 *
 * Uses real [Dispatchers] throughout — the service hops to
 * `Dispatchers.IO` for OkHttp, which escapes any virtual-clock test
 * dispatcher. We wait for sync completion by joining the service
 * scope's child jobs (`awaitSync`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideManifestServiceTest {

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var json: Json
    private lateinit var scope: CoroutineScope

    private val cacheFile: File
        get() = File(context.filesDir, "voice_guide_manifest.json")

    @Before
    fun setUp() {
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

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        cacheFile.delete()
    }

    private fun buildService(url: String = server.url("/manifest.json").toString()) =
        VoiceGuideManifestService(
            context = context,
            httpClient = httpClient,
            json = json,
            scope = scope,
            manifestUrl = url,
        )

    /** Wait for any in-flight sync coroutine launched on the service scope. */
    private fun awaitSync() = runBlocking {
        scope.coroutineContext[Job]?.children?.forEach { it.join() }
    }

    /**
     * Construct the service and wait for its async init-block disk
     * load to complete, so tests can synchronously observe the
     * cached state. (init now launches `loadLocalManifestFromDisk`
     * on `Dispatchers.IO` to stay off the Hilt-injection thread.)
     */
    private fun buildServiceAndWaitForInit(
        url: String = server.url("/manifest.json").toString(),
    ): VoiceGuideManifestService = buildService(url).also { awaitSync() }

    private fun manifest(
        version: String = "2026-01-01",
        packs: List<VoiceGuidePack> = emptyList(),
    ) = VoiceGuideManifest(version = version, packs = packs)

    private fun samplePack(id: String = "p") = VoiceGuidePack(
        id = id,
        version = "1.0",
        name = "n",
        tagline = "t",
        description = "d",
        theme = "x",
        iconName = "i",
        type = "walk",
        walkTypes = emptyList(),
        scheduling = PromptDensity(60, 120, 30, 15, 60),
        totalDurationSec = 0.0,
        totalSizeBytes = 0L,
        prompts = emptyList(),
    )

    @Test fun `init with no cache emits empty list`() {
        val service = buildService()
        assertTrue(service.packs.value.isEmpty())
    }

    @Test fun `init loads existing valid cache into packs`() {
        val cached = manifest(version = "cached-1", packs = listOf(samplePack("seeded")))
        cacheFile.writeText(json.encodeToString(cached))

        val service = buildServiceAndWaitForInit()
        assertEquals(1, service.packs.value.size)
        assertEquals("seeded", service.packs.value.first().id)
    }

    @Test fun `init with corrupt cache treats as absent and leaves file alone`() {
        cacheFile.writeText("{ not json")

        val service = buildServiceAndWaitForInit()
        assertTrue(service.packs.value.isEmpty())
        // Corrupt file remains until syncIfNeeded rewrites it —
        // nothing eagerly deletes user data.
        assertTrue(cacheFile.exists())
    }

    @Test fun `syncIfNeeded fetches remote and writes cache when no local exists`() {
        val remote = manifest(version = "v1", packs = listOf(samplePack("p1")))
        server.enqueue(MockResponse().setBody(json.encodeToString(remote)))
        val service = buildService()

        service.syncIfNeeded()
        awaitSync()

        assertEquals(1, service.packs.value.size)
        assertEquals("p1", service.packs.value.first().id)
        assertTrue(cacheFile.exists())
        val onDisk = json.decodeFromString<VoiceGuideManifest>(cacheFile.readText())
        assertEquals("v1", onDisk.version)
    }

    @Test fun `network failure leaves cached state intact`() {
        val cached = manifest(version = "good", packs = listOf(samplePack("keep")))
        cacheFile.writeText(json.encodeToString(cached))
        server.enqueue(MockResponse().setResponseCode(503))
        val service = buildServiceAndWaitForInit()

        service.syncIfNeeded()
        awaitSync()

        assertEquals(1, service.packs.value.size)
        assertEquals("keep", service.packs.value.first().id)
        val onDisk = json.decodeFromString<VoiceGuideManifest>(cacheFile.readText())
        assertEquals("good", onDisk.version)
    }

    @Test fun `version unchanged does not rewrite cache file`() {
        val same = manifest(version = "v1", packs = listOf(samplePack("a")))
        cacheFile.writeText(json.encodeToString(same))
        val originalMtime = cacheFile.lastModified()
        // Ensure the filesystem clock can tick past the original mtime.
        Thread.sleep(1_100)
        server.enqueue(MockResponse().setBody(json.encodeToString(same)))
        val service = buildServiceAndWaitForInit()

        service.syncIfNeeded()
        awaitSync()

        assertEquals(originalMtime, cacheFile.lastModified())
    }

    @Test fun `version changed overwrites cache atomically`() {
        val old = manifest(version = "v1", packs = listOf(samplePack("a")))
        cacheFile.writeText(json.encodeToString(old))
        val new = manifest(version = "v2", packs = listOf(samplePack("b")))
        server.enqueue(MockResponse().setBody(json.encodeToString(new)))
        val service = buildServiceAndWaitForInit()

        service.syncIfNeeded()
        awaitSync()

        val onDisk = json.decodeFromString<VoiceGuideManifest>(cacheFile.readText())
        assertEquals("v2", onDisk.version)
        assertEquals("b", service.packs.value.first().id)
        val tmp = File(cacheFile.parentFile, "voice_guide_manifest.json.tmp")
        assertFalse(tmp.exists())
    }

    @Test fun `concurrent syncIfNeeded calls dedupe to single request`() {
        val remote = manifest(version = "v1", packs = listOf(samplePack("p1")))
        server.enqueue(MockResponse().setBody(json.encodeToString(remote)))
        val service = buildService()

        service.syncIfNeeded()
        service.syncIfNeeded()
        service.syncIfNeeded()
        awaitSync()

        assertEquals(1, server.requestCount)
    }

    @Test fun `isSyncing emits true during fetch then false`() = runTest {
        val remote = manifest(version = "v1")
        // Body delay guarantees the `true` state is observable:
        // `_isSyncing` is a StateFlow, which conflates. Without the
        // delay, an in-process MockWebServer can return before
        // Turbine's collector has a chance to observe `true`, and the
        // true → false transition collapses into a single `false`
        // emission, hanging the test on `assertTrue(awaitItem())`.
        server.enqueue(
            MockResponse()
                .setBodyDelay(200, TimeUnit.MILLISECONDS)
                .setBody(json.encodeToString(remote))
        )
        val service = buildService()

        service.isSyncing.test(timeout = 10.seconds) {
            assertFalse(awaitItem())
            service.syncIfNeeded()
            assertTrue(awaitItem())
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `pack byId returns null for unknown id and hits for known`() {
        val cached = manifest(
            version = "v1",
            packs = listOf(samplePack("alpha"), samplePack("beta")),
        )
        cacheFile.writeText(json.encodeToString(cached))
        val service = buildServiceAndWaitForInit()

        assertNull(service.pack(id = "missing"))
        assertNotNull(service.pack(id = "alpha"))
        assertEquals("beta", service.pack(id = "beta")?.id)
    }
}
