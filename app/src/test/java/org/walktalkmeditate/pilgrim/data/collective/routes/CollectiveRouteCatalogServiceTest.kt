// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * Ports iOS `UnitTests/CollectiveRouteCatalogServiceTests.swift@9a418e4`
 * (parity spec `docs/parity/2026-07-23-port-route-catalog-service-u3.md`),
 * structured after `VoiceGuideManifestServiceTest`.
 *
 * Three-tier precedence (fetched > cached > bundled bootstrap) is the
 * behaviour most likely to regress silently: every tier produces *a*
 * catalog, so a broken precedence looks like a working app serving
 * stale routes. Bootstrap-tier assertions pin the real bundled asset
 * (`0faeb638520c`) because Robolectric serves the merged assets — the
 * artifact itself is guarded by [CollectiveRouteBundledArtifactTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectiveRouteCatalogServiceTest {

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var scope: CoroutineScope

    private val cacheFile: File
        get() = File(context.filesDir, "collective_routes.json")

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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        cacheFile.delete()
    }

    /**
     * A whole artifact whose version and single route id are both readable
     * back off the published catalog, so "which tier won" is answerable from
     * the service's own state without reaching for the file system.
     */
    private fun artifactJson(version: String, routeId: String): String = """
        {
          "version": "$version",
          "pilgrimages": [
            { "id": "$routeId", "kind": "route", "nameEn": "Route", "companyLine": "Some walked it.", "km": 100 }
          ],
          "horizons": []
        }
    """.trimIndent()

    private fun buildService(
        url: String = server.url("/routes.json").toString(),
        bootstrapAssetPath: String = BOOTSTRAP_ASSET_PATH,
        serviceScope: CoroutineScope = scope,
    ) = CollectiveRouteCatalogService(
        context = context,
        httpClient = httpClient,
        scope = serviceScope,
        catalogUrl = url,
        bootstrapAssetPath = bootstrapAssetPath,
    )

    private fun buildServiceAndAwaitLoad(
        url: String = server.url("/routes.json").toString(),
        bootstrapAssetPath: String = BOOTSTRAP_ASSET_PATH,
    ): CollectiveRouteCatalogService =
        buildService(url, bootstrapAssetPath).also { runBlocking { it.initialLoad.await() } }

    /** Wait for any in-flight coroutine launched on the service scope. */
    private fun awaitSync() = runBlocking {
        scope.coroutineContext[Job]?.children?.forEach { it.join() }
    }

    private fun utcMillis(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    // Three-tier load precedence

    @Test
    fun `init with no cache serves the bundled bootstrap`() {
        val service = buildServiceAndAwaitLoad()

        assertEquals(BOOTSTRAP_VERSION, service.catalog.value.version)
        assertEquals("camino-frances", service.catalog.value.entries.first().id)
    }

    @Test
    fun `cached file wins over the bootstrap`() {
        cacheFile.writeText(artifactJson(version = "cache-v7", routeId = "from-cache"))

        val service = buildServiceAndAwaitLoad()

        assertEquals("cache-v7", service.catalog.value.version)
        assertEquals(listOf("from-cache"), service.catalog.value.entries.map { it.id })
    }

    @Test
    fun `corrupt cached file falls back to the bootstrap`() {
        cacheFile.writeText("not json")

        val service = buildServiceAndAwaitLoad()

        assertEquals(BOOTSTRAP_VERSION, service.catalog.value.version)
        // Corrupt file remains until a sync rewrites it atomically —
        // nothing eagerly deletes user data.
        assertTrue(cacheFile.exists())
    }

    @Test
    fun `entry-less cached file is passed over for the bootstrap`() {
        cacheFile.writeText("""{ "version": "empty-v1", "pilgrimages": [], "horizons": [] }""")

        val service = buildServiceAndAwaitLoad()

        assertEquals(BOOTSTRAP_VERSION, service.catalog.value.version)
    }

    @Test
    fun `missing bootstrap publishes the empty catalog without crashing`() {
        val service = buildServiceAndAwaitLoad(bootstrapAssetPath = "collective/does-not-exist.json")

        assertEquals(CollectiveRouteCatalog.EMPTY, service.catalog.value)
        assertNull(service.dailyLine(utcMillis(2026, 10, 7), 694.5, UnitSystem.Metric))
    }

    @Test
    fun `lookups before initial load return nothing without blocking`() {
        // An unstarted test dispatcher keeps the init-load coroutine
        // unscheduled, standing in for iOS's test holding the main actor:
        // the pre-load state is deterministic, not a race the test wins.
        val idleScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(TestCoroutineScheduler()))
        try {
            val service = buildService(serviceScope = idleScope)
            val millis = utcMillis(2026, 10, 7)

            assertFalse(service.initialLoad.isCompleted)
            assertEquals(CollectiveRouteCatalog.EMPTY, service.catalog.value)
            assertNull(service.dailyLine(millis, 694.5, UnitSystem.Metric))
            assertNull(service.contributionLine(millis, 4.2, UnitSystem.Metric))
        } finally {
            idleScope.cancel()
        }
    }

    // Remote sync

    @Test
    fun `sync remote with a different version replaces the cache and publishes`() {
        cacheFile.writeText(artifactJson(version = "cache-v1", routeId = "from-cache"))
        val remoteBody = artifactJson(version = "remote-v2", routeId = "from-remote")
        server.enqueue(MockResponse().setBody(remoteBody))
        val service = buildServiceAndAwaitLoad()

        service.syncIfNeeded()
        awaitSync()

        assertEquals("remote-v2", service.catalog.value.version)
        assertEquals(listOf("from-remote"), service.catalog.value.entries.map { it.id })
        assertEquals(
            "The cache must hold the exact bytes the CDN served, not a re-encode",
            remoteBody,
            cacheFile.readText(),
        )
        assertFalse(File(cacheFile.parentFile, "collective_routes.json.tmp").exists())
    }

    @Test
    fun `sync fetched catalog wins over the bootstrap when no cache exists`() {
        server.enqueue(MockResponse().setBody(artifactJson(version = "remote-v2", routeId = "from-remote")))
        val service = buildServiceAndAwaitLoad()

        service.syncIfNeeded()
        awaitSync()

        assertEquals(listOf("from-remote"), service.catalog.value.entries.map { it.id })
        assertTrue(cacheFile.exists())
    }

    @Test
    fun `sync remote with an equal version leaves the published catalog untouched`() {
        // Same version, deliberately different contents. Comparing the
        // published entries rather than the version is what proves the sync
        // short-circuited instead of re-publishing an identical-looking catalog.
        val cachedBody = artifactJson(version = "cache-v1", routeId = "from-cache")
        cacheFile.writeText(cachedBody)
        server.enqueue(MockResponse().setBody(artifactJson(version = "cache-v1", routeId = "from-remote")))
        val service = buildServiceAndAwaitLoad()

        service.syncIfNeeded()
        awaitSync()

        assertEquals(listOf("from-cache"), service.catalog.value.entries.map { it.id })
        assertEquals(
            "An unchanged version must not spend a disk write",
            cachedBody,
            cacheFile.readText(),
        )
    }

    @Test
    fun `sync remote with an older version still applies`() {
        // Versions are content-derived and carry no ordering, so a curator
        // reverting to a prior artifact publishes a "lower" one. Comparing for
        // inequality is what lets that rollback reach devices at all.
        cacheFile.writeText(artifactJson(version = "bootstrap-v1", routeId = "from-cache"))
        server.enqueue(MockResponse().setBody(artifactJson(version = "bootstrap-v0", routeId = "rolled-back")))
        val service = buildServiceAndAwaitLoad()

        service.syncIfNeeded()
        awaitSync()

        assertEquals("bootstrap-v0", service.catalog.value.version)
        assertEquals(listOf("rolled-back"), service.catalog.value.entries.map { it.id })
    }

    @Test
    fun `sync failed network response leaves the existing catalog in place`() {
        cacheFile.writeText(artifactJson(version = "cache-v1", routeId = "from-cache"))
        server.enqueue(MockResponse().setResponseCode(503))
        val service = buildServiceAndAwaitLoad()

        service.syncIfNeeded()
        awaitSync()

        assertEquals(listOf("from-cache"), service.catalog.value.entries.map { it.id })
        assertFalse(
            "A failed fetch must still clear the flag, or the catalog freezes for the process",
            service.isSyncing.value,
        )
    }

    @Test
    fun `sync undecodable remote payload leaves the existing catalog in place`() {
        server.enqueue(MockResponse().setBody("not json"))
        val service = buildServiceAndAwaitLoad()

        service.syncIfNeeded()
        awaitSync()

        assertEquals(BOOTSTRAP_VERSION, service.catalog.value.version)
        assertFalse(
            "A payload the app cannot read must never reach the cache",
            cacheFile.exists(),
        )
        assertFalse(service.isSyncing.value)
    }

    @Test
    fun `sync remote decoding to zero entries keeps the current catalog`() {
        // The R5 guard: arrays are optional and elements decode lossily, so a
        // bake dropping a required field parses cleanly into nothing and would
        // cache dark. Rejected like an undecodable payload.
        val cachedBody = artifactJson(version = "cache-v1", routeId = "from-cache")
        cacheFile.writeText(cachedBody)
        server.enqueue(MockResponse().setBody("""{ "version": "fresh-v9", "pilgrimages": [], "horizons": [] }"""))
        val service = buildServiceAndAwaitLoad()

        service.syncIfNeeded()
        awaitSync()

        assertEquals("cache-v1", service.catalog.value.version)
        assertEquals(listOf("from-cache"), service.catalog.value.entries.map { it.id })
        assertEquals(cachedBody, cacheFile.readText())
    }

    @Test
    fun `sync clears the syncing flag when it finishes`() {
        server.enqueue(MockResponse().setBody(artifactJson(version = "remote-v2", routeId = "from-remote")))
        val service = buildServiceAndAwaitLoad()

        service.syncIfNeeded()
        awaitSync()

        assertFalse(service.isSyncing.value)
    }

    @Test
    fun `concurrent syncIfNeeded calls dedupe to a single request`() {
        server.enqueue(MockResponse().setBody(artifactJson(version = "remote-v2", routeId = "from-remote")))
        val service = buildServiceAndAwaitLoad()

        service.syncIfNeeded()
        service.syncIfNeeded()
        service.syncIfNeeded()
        awaitSync()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `isSyncing emits true during fetch then false`() = runTest {
        // Body delay guarantees the `true` state is observable across
        // StateFlow conflation (template lesson).
        server.enqueue(
            MockResponse()
                .setBodyDelay(200, TimeUnit.MILLISECONDS)
                .setBody(artifactJson(version = "remote-v2", routeId = "from-remote")),
        )
        val service = buildServiceAndAwaitLoad()

        service.isSyncing.test(timeout = 10.seconds) {
            assertFalse(awaitItem())
            service.syncIfNeeded()
            assertTrue(awaitItem())
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Lookup passthrough

    @Test
    fun `lookups delegate to the published catalog`() {
        cacheFile.writeText(artifactJson(version = "cache-v1", routeId = "the-route"))
        val service = buildServiceAndAwaitLoad()
        val millis = utcMillis(2026, 10, 7)

        assertEquals(
            "We are 50% of the way to one Route.",
            service.dailyLine(millis, collectiveKm = 50.0, units = UnitSystem.Metric),
        )
        assertEquals(
            "Your 4.2 km against the Route. Some walked it.",
            service.contributionLine(millis, walkKm = 4.2, units = UnitSystem.Metric),
        )
    }

    private companion object {
        const val BOOTSTRAP_ASSET_PATH = "collective/collective-routes-bootstrap.json"
        const val BOOTSTRAP_VERSION = "0faeb638520c"
    }
}
