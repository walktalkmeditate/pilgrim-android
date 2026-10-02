// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.ConnectionCountingServer
import org.walktalkmeditate.pilgrim.di.NetworkModule
import org.walktalkmeditate.pilgrim.domain.Clock

/**
 * Port of the 24 service tests in iOS `PilgrimageCatalogServiceTests.swift@7c200bf`,
 * against a MockWebServer that answers by exact path as iOS's
 * `StubURLProtocol` answers by exact URL (pilgrimage-stage spec P1, Test
 * inventory, C5). The file's 12 `PilgrimageCatalogModelTests` belong to
 * U37's models, and its maps-row test to Stage 21-3.
 *
 * After iOS's tests come the spec's additions: the URL rules' exact strings
 * (Stage 5-G's lesson), the 24 h rule's edges and the signed clock, Retry,
 * the fallback, single flight and cancellation (A3, A4), the caps at their
 * edge on both paths, status and redirects (A2), each row rule at its edge,
 * the decoding rules and kotlinx's differences (A7, A9), grouping's corners,
 * the preview's file and failures, and the cache file. Robolectric for the
 * production constructor and client (the builder-test rule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageCatalogServiceTest {

    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val stubs = PathStubs()

    @Volatile private var clockMillis = START_MILLIS

    private val directory: File get() = File(folder.root, "Pilgrimages")

    @Before
    fun setUp() {
        server.dispatcher = stubs
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- iOS's tests ----------------------------------------------------

    /** Never a tag: jsDelivr caches a tag URL for good, so the moving `v1` tag still serves a March index with three routes. */
    @Test
    fun `the index is read from the branch, not a moving tag`() {
        assertEquals("https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@main/index.json", PilgrimageCatalogService.INDEX_URL)
        assertFalse(PilgrimageCatalogService.INDEX_URL.contains("@v1"))
    }

    @Test
    fun `package URLs are pinned to the exact release`() {
        assertEquals(
            "https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@v1.7.0/routes/camino-frances/ways/stage-00.json",
            PilgrimageCatalogService.packageUrl(release = "v1.7.0", routeId = "camino-frances", file = "stage-00.json")?.toString(),
        )
        assertNull(PilgrimageCatalogService.packageUrl(release = "v1.7.0", routeId = "../etc", file = "route.json"))
        assertNull(PilgrimageCatalogService.packageUrl(release = "main", routeId = "camino-frances", file = "route.json"))
        assertNull(PilgrimageCatalogService.packageUrl(release = "v1.7.0", routeId = "camino-frances", file = "../../secret"))
    }

    @Test
    fun `parses only routes that carry a ways entry and a legal slug`() {
        val catalog = parse(fixtureText("index.json"))

        assertEquals("v1.7.0", catalog.release)
        assertEquals("camino-norte has no ways entry; the third id is not a slug", listOf("camino-frances"), catalog.routes.map { it.id })
        val route = catalog.routes.first()
        assertEquals("Camino de Santiago (Frances)", route.name)
        assertEquals("Camino de Santiago (Francés)", route.names["es"])
        assertEquals("ES", route.country)
        assertEquals(2, route.stageCount)
        assertEquals(214_000, route.bytes)
    }

    @Test
    fun `the sparse flag and its density carry through`() {
        val route = parse(fixtureText("index.json")).routes.first()
        assertTrue("the Camino Francés carries a curated place on fewer than half its stages", route.sparse)
        assertEquals(0.4, route.placesPerStage, 0.0001)

        // An index written before the flag existed still parses, as dense.
        val base = fixtureText("index.json")
        val older = base.replace(
            "\"stageCount\": 2, \"bytes\": 214000, \"placesPerStage\": 0.4, \"sparse\": true",
            "\"stageCount\": 2, \"bytes\": 214000",
        )
        assertNotEquals(base, older)
        val olderRoute = parse(older).routes.first()
        assertFalse(olderRoute.sparse)
        assertEquals(0.0, olderRoute.placesPerStage, 0.0)
    }

    @Test
    fun `an absurd place density drops the route`() {
        val base = fixtureText("index.json")
        listOf("\"placesPerStage\": 51", "\"placesPerStage\": -1", "\"placesPerStage\": 1e300").forEach { bad ->
            val json = base.replace("\"placesPerStage\": 0.4", bad)
            assertNotEquals(bad, base, json)
            assertTrue(bad, parse(json).routes.isEmpty())
        }
    }

    /** The catalog's ledger map and Compose's list keys need unique ids; the parse is the one place to make that so. */
    @Test
    fun `a duplicate route id in the index keeps only the first`() {
        val base = fixtureText("index.json")
        val json = base.replace("\"id\": \"camino-norte\",", "\"id\": \"camino-frances\",")
            .replace("\"path\": \"routes/camino-norte\"", "\"path\": \"routes/camino-norte\",\n      \"ways\": { \"stageCount\": 5, \"bytes\": 100000 }")
        assertNotEquals(base, json)

        val catalog = parse(json)

        assertEquals("the second row's id repeats the first; first wins", listOf("camino-frances"), catalog.routes.map { it.id })
        assertEquals(2, catalog.routes.single().stageCount)
    }

    @Test
    fun `a repaired release tag is refused`() {
        val base = fixtureText("index.json")
        listOf("\"release\": \"main\"", "\"release\": \"v1.7\"", "\"release\": \"1.7.0\"").forEach { bad ->
            val json = base.replace("\"release\": \"v1.7.0\"", bad)
            assertNotEquals(bad, base, json)
            assertParseRefused(bad, json)
        }
    }

    @Test
    fun `out of range route numbers drop the route`() {
        val base = fixtureText("index.json")
        listOf(
            "\"distanceKm\": 46.1" to "\"distanceKm\": 20000",
            "\"stageCount\": 2, \"bytes\": 214000" to "\"stageCount\": 900, \"bytes\": 214000",
            "\"stageCount\": 2, \"bytes\": 214000" to "\"stageCount\": 2, \"bytes\": 99000000",
        ).forEach { (from, to) ->
            val json = base.replace(from, to)
            assertNotEquals(to, base, json)
            assertTrue(to, parse(json).routes.isEmpty())
        }
    }

    @Test
    fun `fetches once and then serves the cache for twenty-four hours`() {
        stubIndex(fixture("index.json"))
        makeService().loadBlocking()
        assertEquals(1, server.requestCount)

        clockMillis += 23 * HOUR
        val cached = makeService().loadBlocking()
        assertEquals(listOf("camino-frances"), cached.routes.map { it.id })
        assertEquals("still inside the 24 h window", 1, server.requestCount)

        clockMillis += 2 * HOUR
        makeService().loadBlocking()
        assertEquals("past 24 h, it asks again", 2, server.requestCount)
    }

    @Test
    fun `an index bigger than the cap is never buffered`() {
        val huge = ByteArray(PilgrimageCatalogService.MAX_INDEX_BYTES + 1) { 0x7B }
        // At 16 KiB a second, reading this body would take sixteen seconds.
        stubIndex { declared(huge).throttleBody(16 * 1024L, 1, TimeUnit.SECONDS) }

        val e = runBlocking { withTimeout(10_000) { runCatching { makeService().load() }.exceptionOrNull() } }

        assertEquals(PilgrimageError.CATALOG_UNREACHABLE, (e as PilgrimageException).error)
    }

    /** With no `Content-Length` the check before the drain reads -1 and lets the body through, so only the count while streaming can refuse it. */
    @Test
    fun `an index that never declares its length is refused by the cap counted while streaming`() {
        stubIndex { chunked(ByteArray(PilgrimageCatalogService.MAX_INDEX_BYTES + 1) { 0x7B }) }
        val service = makeService()

        assertOutOfReach { service.loadBlocking() }
        assertNull(service.catalog.value)
    }

    /** A good index body behind a 404: only the status refuses it, and the catalog is out of reach rather than empty. */
    @Test
    fun `an index served as not found is out of reach`() {
        stubIndex { declared(fixture("index.json")).setResponseCode(404) }
        val service = makeService()

        assertOutOfReach { service.loadBlocking() }
        assertNull(service.catalog.value)
    }

    @Test
    fun `a failed fetch with a cached index degrades silently`() {
        stubIndex(fixture("index.json"))
        makeService().loadBlocking()
        stubs.reset()
        clockMillis += 48 * HOUR

        val catalog = makeService().loadBlocking()

        assertEquals("a stale cache still answers when the network does not", listOf("camino-frances"), catalog.routes.map { it.id })
    }

    @Test
    fun `a failed fetch with no cache is out of reach`() {
        val service = makeService()

        assertOutOfReach { service.loadBlocking() }
        assertNull(service.catalog.value)
    }

    /** The index lists the dojo by id; `sections` carries the order they are walked, Shikoku's temple order. */
    @Test
    fun `sections are ordered as their pilgrimage walks them, not as the index lists them`() {
        val catalog = parse(fixtureText("index-pilgrimages.json"))

        assertEquals(
            "the index itself is sorted by id",
            listOf("camino-frances", "shikoku-88-awa", "shikoku-88-iyo", "shikoku-88-sanuki", "shikoku-88-tosa", "st-cuthberts-way"),
            catalog.routes.map { it.id },
        )
        val shikoku = catalog.groups.first { it.id == "shikoku-88" }
        assertEquals("Shikoku 88 Temple Pilgrimage", shikoku.name)
        assertEquals(listOf("shikoku-88-awa", "shikoku-88-tosa", "shikoku-88-iyo", "shikoku-88-sanuki"), shikoku.entries.map { it.id })
    }

    /** Temples 1-23, 23-39, 39-65, 65-88: the ranges run forward, or the list tells the walker to start in the middle. */
    @Test
    fun `the Shikoku sections read as a forward run of temple numbers`() {
        val shikoku = parse(fixtureText("index-pilgrimages.json")).groups.first { it.id == "shikoku-88" }
        val firstTemples = shikoku.entries.mapNotNull { entry ->
            Regex("([0-9]+)-[0-9]+").find(entry.name)?.groupValues?.get(1)?.toInt()
        }

        assertEquals(listOf(1, 23, 39, 65), firstTemples)
    }

    @Test
    fun `a section with no downloadable package is skipped and its pilgrimage survives`() {
        val camino = parse(fixtureText("index-pilgrimages.json")).groups.first { it.id == "camino-de-santiago" }

        assertEquals("camino-primitivo has no route row to group", listOf("camino-frances"), camino.entries.map { it.id })
    }

    /** Kumano's two sections ship metadata only, so listing it would offer a header over nothing. */
    @Test
    fun `a pilgrimage with nothing to walk is not listed`() {
        assertNull(parse(fixtureText("index-pilgrimages.json")).groups.firstOrNull { it.id == "kumano-kodo" })
    }

    /** Still a route someone can walk, under no header: naming one would invent a pilgrimage the dataset never claimed. */
    @Test
    fun `a route belonging to no pilgrimage is still offered`() {
        val loose = parse(fixtureText("index-pilgrimages.json")).groups.last()

        assertNull(loose.name)
        assertEquals(listOf("st-cuthberts-way"), loose.entries.map { it.id })
    }

    /** The grouped list is the only list the picker draws: a route missing from it can't be reached, and one in it twice repeats a list key. */
    @Test
    fun `every route appears in exactly one group`() {
        val catalog = parse(fixtureText("index-pilgrimages.json"))
        val grouped = catalog.groups.flatMap { group -> group.entries.map { it.id } }

        assertEquals(catalog.routes.map { it.id }.sorted(), grouped.sorted())
        assertEquals(grouped.size, grouped.toSet().size)
    }

    /** An index written before the pilgrimage layer still parses, and its routes still reach the picker. */
    @Test
    fun `an index with no pilgrimage block keeps every route under no header`() {
        val catalog = parse(fixtureText("index.json"))

        assertEquals(1, catalog.groups.size)
        assertNull(catalog.groups.first().name)
        assertEquals(catalog.routes.map { it.id }, catalog.groups.first().entries.map { it.id })
    }

    /** The route page lists the stages before anything is downloaded, so a stage of an undownloaded route has a row. */
    @Test
    fun `the route preview arrives before anything is downloaded`() {
        stubs.stub(routePath("v1.7.0")) { declared(fixture("route.json")) }

        val route = makeService().previewBlocking(entry(stageCount = 2), "v1.7.0")

        assertEquals(listOf(0, 1), route.stages.map { it.index })
        assertEquals("Saint-Jean-Pied-de-Port to Roncesvalles", route.stages[0].name)
        assertEquals(1, server.requestCount)

        // Cached beside the index: a second view of the same route is free.
        makeService().previewBlocking(entry(stageCount = 2), "v1.7.0")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a preview that does not match the entry is not walkable`() {
        stubs.stub(routePath("v1.7.0")) { declared(fixture("route.json")) }

        assertRefused(PilgrimageError.NOT_WALKABLE) { makeService().previewBlocking(entry(stageCount = 5), "v1.7.0") }
    }

    @Test
    fun `a preview with no network is out of reach`() {
        assertOutOfReach { makeService().previewBlocking(entry(stageCount = 2), "v1.7.0") }
    }

    // ---- URLs and the identifier rules (P1 §2) ----------------------------

    @Test
    fun `the package URL's exact shape for route json, a two-digit stage, and a three-digit one`() {
        val base = "https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@v1.12.0/routes/camino-frances/ways"
        listOf("route.json", "stage-07.json", "stage-107.json").forEach { file ->
            assertEquals(file, "$base/$file", PilgrimageCatalogService.packageUrl("v1.12.0", "camino-frances", file)?.toString())
        }
    }

    @Test
    fun `no package URL for a release, a route id, or a file name outside its rule`() {
        REFUSED_RELEASES.forEach { assertNull(it, PilgrimageCatalogService.packageUrl(it, "camino-frances", "route.json")) }
        listOf("../etc", "../etc/passwd", "Camino-frances", "camiño", "camino frances", "a".repeat(65), "").forEach {
            assertNull(it, PilgrimageCatalogService.packageUrl("v1.7.0", it, "route.json"))
        }
        listOf("../../secret", "stage-0.json", "stage-1000.json", "stage-07.json\n", "report.json", "cover.jpg", "stage-0a.json", "x/route.json").forEach {
            assertNull(it, PilgrimageCatalogService.packageUrl("v1.7.0", "camino-frances", it))
        }
        listOf("-", "a--b", "a".repeat(64)).forEach {
            assertNotNull("$it passes the slug rule, which enforces no kebab structure", PilgrimageCatalogService.packageUrl("v1.7.0", it, "route.json"))
        }
    }

    @Test
    fun `the release rule is iOS's literal pattern, anchored at both ends`() {
        listOf("v1.12.0", "v01.2.3", "v0.0.0").forEach { assertTrue(it, PilgrimageCatalogService.isValidRelease(it)) }
        REFUSED_RELEASES.forEach { assertFalse(it, PilgrimageCatalogService.isValidRelease(it)) }
    }

    @Test
    fun `the index is asked for at its own path with a plain GET`() {
        stubIndex(fixture("index.json"))
        makeService().loadBlocking()

        val request = server.takeRequest()
        assertEquals(INDEX_PATH, request.path)
        assertEquals("GET", request.method)
    }

    // ---- Loading (P1 §8) --------------------------------------------------

    @Test
    fun `a load inside the twenty-four hours asks nothing`() {
        stubIndex(fixture("index.json"))
        val service = makeService()
        val first = service.loadBlocking()
        clockMillis += 23 * HOUR

        assertEquals(first, service.loadBlocking())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `try again forces a fetch inside the twenty-four hours`() {
        stubIndex(fixture("index.json"))
        val service = makeService()
        service.loadBlocking()
        stubIndex(fixture("index-pilgrimages.json"))
        clockMillis += HOUR

        assertEquals("v1.9.1", service.loadBlocking(force = true).release)
        assertEquals(2, server.requestCount)
        assertEquals("v1.9.1", service.catalog.value?.release)
    }

    @Test
    fun `a cache exactly twenty-four hours old is fetched again`() {
        stubIndex(fixture("index.json"))
        makeService().loadBlocking()

        clockMillis += DAY - 1
        makeService().loadBlocking()
        assertEquals("a millisecond short of a day is fresh", 1, server.requestCount)

        clockMillis += 1
        makeService().loadBlocking()
        assertEquals(2, server.requestCount)
    }

    /** Flow gap 11: the difference is signed, as iOS's `timeIntervalSince` is, never an absolute value. */
    @Test
    fun `a clock set back stays fresh until it passes the fetch time by a day`() {
        stubIndex(fixture("index.json"))
        makeService().loadBlocking()
        val fetchedAt = clockMillis

        clockMillis = fetchedAt - 48 * HOUR
        makeService().loadBlocking()
        assertEquals("a fetch time in the future reads as fresh", 1, server.requestCount)

        clockMillis = fetchedAt + DAY
        makeService().loadBlocking()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a forced load that fails with a stale cache returns the cache with no error`() {
        stubIndex(fixture("index.json"))
        val fetched = makeService().loadBlocking()
        stubs.reset()
        clockMillis += 48 * HOUR
        val service = makeService()

        assertEquals(fetched, service.loadBlocking(force = true))
        assertEquals(fetched, service.catalog.value)
        assertEquals("the retry did reach for the network", 2, server.requestCount)
    }

    @Test
    fun `a parse that keeps no route is cached and fresh for twenty-four hours`() {
        stubIndex("""{"release":"v1.7.0","routes":[]}""".toByteArray())
        val empty = makeService().loadBlocking()
        assertTrue(empty.routes.isEmpty())

        clockMillis += 23 * HOUR

        assertEquals(empty, makeService().loadBlocking())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a catalog served from a fresh cache is published too`() {
        stubIndex(fixture("index.json"))
        val fetched = makeService().loadBlocking()
        val second = makeService()
        assertNull("nothing is read at construction", second.catalog.value)

        second.loadBlocking()

        assertEquals(fetched, second.catalog.value)
    }

    /** P4 §3.4's rust line: the screen still holds a catalog when a reload throws. */
    @Test
    fun `a failed load with no cache on disk leaves the published catalog as it was`() {
        stubIndex(fixture("index.json"))
        val service = makeService()
        val first = service.loadBlocking()
        assertTrue(File(directory, "catalog.json").delete())
        stubs.reset()

        assertOutOfReach { service.loadBlocking(force = true) }
        assertEquals(first, service.catalog.value)
    }

    /** A4: the second load waits, then reads the cache the first wrote; iOS's two loads would both fetch. */
    @Test
    fun `two loads at once fetch once`() {
        stubIndex { declared(fixture("index.json")).setBodyDelay(300, TimeUnit.MILLISECONDS) }
        val service = makeService()

        val loads = runBlocking { List(2) { async(Dispatchers.IO) { service.load() } }.awaitAll() }

        assertEquals(loads[0], loads[1])
        assertEquals(1, server.requestCount)
    }

    /** A3: iOS maps cancellation to out of reach and then serves the cache; Android's load is simply cancelled. */
    @Test
    fun `a cancelled load is cancelled, never served from the cache`() {
        stubIndex(fixture("index.json"))
        makeService().loadBlocking()
        clockMillis += 48 * HOUR
        stubIndex { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val service = makeService()
        var thrown: Throwable? = null

        runBlocking {
            val job = launch(Dispatchers.IO) {
                try {
                    service.load()
                } catch (e: Throwable) {
                    thrown = e
                    throw e
                }
            }
            repeat(2) { assertNotNull("the stale load reached the network", server.takeRequest(10, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
        }

        assertTrue("$thrown", thrown is CancellationException)
        assertNull(service.catalog.value)
    }

    // ---- The fetch: caps, status, redirects (P1 §3) -----------------------

    @Test
    fun `exactly the cap passes and one byte more is refused, on the declared length`() {
        val exact = padded(fixture("index.json"), PilgrimageCatalogService.MAX_INDEX_BYTES)
        stubIndex { declared(exact) }
        assertEquals(listOf("camino-frances"), makeService().loadBlocking().routes.map { it.id })
        assertTrue(directory.deleteRecursively())

        stubIndex { declared(exact + SPACE) }
        assertOutOfReach { makeService().loadBlocking() }
    }

    @Test
    fun `exactly the cap passes and one byte more is refused, counted while streaming`() {
        val exact = padded(fixture("index.json"), PilgrimageCatalogService.MAX_INDEX_BYTES)
        stubIndex { chunked(exact) }
        assertEquals(listOf("camino-frances"), makeService().loadBlocking().routes.map { it.id })
        assertTrue(directory.deleteRecursively())

        stubIndex { chunked(exact + SPACE) }
        assertOutOfReach { makeService().loadBlocking() }
    }

    @Test
    fun `any status but 200 is out of reach`() {
        listOf(201, 206, 404, 500).forEach { code ->
            stubIndex { declared(fixture("index.json")).setResponseCode(code) }
            assertOutOfReach("status $code") { makeService().loadBlocking() }
        }
        stubIndex { MockResponse().setResponseCode(304) }
        assertOutOfReach("status 304") { makeService().loadBlocking() }
    }

    /** A2, owner decision 6: iOS follows a redirect to any HTTPS host. */
    @Test
    fun `a redirect off the CDN host is refused before anything connects to it, and one on it is followed`() {
        ConnectionCountingServer().use { elsewhere ->
            stubIndex { MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url(INDEX_PATH)) }
            assertOutOfReach { makeService().loadBlocking() }
            assertEquals("no connection, let alone a request", 0, elsewhere.connectionsSoFar())
        }

        stubIndex { MockResponse().setResponseCode(302).setHeader("Location", "/moved/index.json") }
        stubs.stub("/moved/index.json") { declared(fixture("index.json")) }
        assertEquals(listOf("camino-frances"), makeService().loadBlocking().routes.map { it.id })
    }

    // ---- The index's rows (P1 §7.2–§7.3, §4) -----------------------------

    @Test
    fun `each row rule at its edge - at the bound is listed, one step past is dropped`() {
        val base = fixtureText("index.json")
        fun ways(stageCount: Any = 2, bytes: Any = 214000, places: Any = 0.4) = base.replace(
            "\"stageCount\": 2, \"bytes\": 214000, \"placesPerStage\": 0.4",
            "\"stageCount\": $stageCount, \"bytes\": $bytes, \"placesPerStage\": $places",
        )
        fun distance(km: String) = base.replace("\"distanceKm\": 46.1", "\"distanceKm\": $km")
        val listed = listOf(
            distance("0"), distance("10000"), ways(stageCount = 1), ways(stageCount = 200),
            ways(bytes = 0), ways(bytes = 52_428_799), ways(places = 0), ways(places = 50),
        )
        val dropped = listOf(
            distance("-0.1"), distance("10000.1"), ways(stageCount = 0), ways(stageCount = 201),
            ways(bytes = -1), ways(bytes = 52_428_800), ways(places = -0.1), ways(places = 50.1),
        )

        (listed + dropped).forEach { assertNotEquals(base, it) }
        listed.forEach { assertEquals(it, listOf("camino-frances"), parse(it).routes.map { route -> route.id }) }
        dropped.forEach { assertTrue(it, parse(it).routes.isEmpty()) }
    }

    @Test
    fun `the name is en whenever the key is there, even empty, else the alphabetically first valid key`() {
        fun named(map: String): List<PilgrimageCatalogEntry> {
            val base = fixtureText("index.json")
            val json = base.replace("\"name\": { \"en\": \"Camino de Santiago (Frances)\", \"es\": \"Camino de Santiago (Francés)\" }", "\"name\": $map")
            assertNotEquals(base, json)
            return parse(json).routes
        }

        assertEquals("ast sorts first", "A", named("""{ "ja": "J", "ast": "A", "es": "E" }""").single().name)
        assertEquals("", named("""{ "es": "E", "en": "" }""").single().name)
        assertTrue("no key a language code, so no name, so no row", named("""{ "EN": "x", "e1": "y", "english": "z" }""").isEmpty())
        assertEquals(mapOf("en" to "N", "es" to "E"), named("""{ "en": "N", "EN": "x", "pt-BR": "y", "es": "E" }""").single().names)
    }

    @Test
    fun `names are cut to 120 characters and labels to 80, and neither is trimmed`() {
        val base = fixtureText("index.json")
        val json = base
            .replace("\"Camino de Santiago (Frances)\"", "\" ${"n".repeat(130)}\"")
            .replace("\"Camino de Santiago (Francés)\"", "\"${"é".repeat(130)}\"")
            .replace("\"country\": \"ES\"", "\"country\": \" ${"c".repeat(90)}\"")
            .replace("\"region\": \"Europe\"", "\"region\": \"${"r".repeat(90)}\"")
            .replace("\"tradition\": \"christian\"", "\"tradition\": \"${"t".repeat(90)}\"")

        val route = parse(json).routes.single()

        assertEquals(" " + "n".repeat(119), route.name)
        assertEquals("é".repeat(120), route.names["es"])
        assertEquals(" " + "c".repeat(79), route.country)
        assertEquals("r".repeat(80), route.region)
        assertEquals("t".repeat(80), route.tradition)
    }

    /** The first row is gone before ids are compared, so it can't shadow a valid row that repeats its id (P1 §7.2). */
    @Test
    fun `an invalid row never shadows a valid one that repeats its id`() {
        val base = fixtureText("index.json")
        val json = base.replace("\"stageCount\": 2, \"bytes\": 214000", "\"stageCount\": 900, \"bytes\": 214000")
            .replace("\"id\": \"camino-norte\",", "\"id\": \"camino-frances\",")
            .replace("\"path\": \"routes/camino-norte\"", "\"path\": \"routes/camino-norte\",\n      \"ways\": { \"stageCount\": 5, \"bytes\": 100000 }")
        assertNotEquals(base, json)

        val route = parse(json).routes.single()

        assertEquals("camino-frances", route.id)
        assertEquals(5, route.stageCount)
    }

    /** P1 C8, pilgrim-ios #121, matched: one undecodable row costs every route, even a row the rules would have dropped. */
    @Test
    fun `one row that does not decode fails the whole index, as on iOS`() {
        val base = fixtureText("index.json")
        listOf(
            "a null in a name map" to base.replace("\"name\": { \"en\": \"Not a route\" }", "\"name\": { \"en\": null }"),
            "a distance that is not a number" to base.replace("\"distanceKm\": 10,", "\"distanceKm\": \"ten\","),
            "a row with no id" to base.replace("\"id\": \"camino-norte\",", ""),
            "a byte count with a fraction" to base.replace("\"bytes\": 100 }", "\"bytes\": 100.5 }"),
        ).forEach { (name, json) ->
            assertNotEquals(name, base, json)
            assertParseRefused(name, json)
        }
    }

    /** The spec's §4.1 row and A9 expected kotlinx to read Infinity and drop the row; it refuses as it reads, as Foundation does (U31). */
    @Test
    fun `a distance of 1e400 fails the whole index, on Android as on iOS`() {
        val base = fixtureText("index.json")
        val json = base.replace("\"distanceKm\": 46.1", "\"distanceKm\": 1e400")
        assertNotEquals(base, json)

        assertParseRefused("1e400", json)
    }

    @Test
    fun `A9 - an integer written with a fraction fails the whole index, where iOS reads it`() {
        val base = fixtureText("index.json")
        val json = base.replace("\"stageCount\": 2, \"bytes\": 214000", "\"stageCount\": 2.0, \"bytes\": 214000")
        assertNotEquals(base, json)

        assertParseRefused("2.0", json)
    }

    @Test
    fun `A9 - a quoted number reads, where iOS refuses the whole index`() {
        val base = fixtureText("index.json")
        val json = base.replace("\"stageCount\": 2, \"bytes\": 214000", "\"stageCount\": \"2\", \"bytes\": 214000")
        assertNotEquals(base, json)

        assertEquals(2, parse(json).routes.single().stageCount)
    }

    @Test
    fun `A9 - a repeated key keeps its last value, where iOS keeps its first`() {
        val base = fixtureText("index.json")
        val json = base.replace("\"release\": \"v1.7.0\",", "\"release\": \"main\",\n  \"release\": \"v1.7.0\",")
        assertNotEquals(base, json)

        assertEquals("iOS would read main and refuse the index", "v1.7.0", parse(json).release)
    }

    @Test
    fun `A7 - a byte count past 32 bits drops its row, never the index`() {
        val base = fixtureText("index-pilgrimages.json")
        val json = base.replace("\"bytes\": 90000,", "\"bytes\": 3000000000,")
        assertNotEquals(base, json)

        val catalog = parse(json)

        assertEquals(5, catalog.routes.size)
        assertNull(catalog.routes.firstOrNull { it.id == "st-cuthberts-way" })
    }

    // ---- Grouping (P1 §7.4) -----------------------------------------------

    /** A6: group ids aren't made unique, so no list may key on one alone. */
    @Test
    fun `two pilgrimages sharing an id each keep their group`() {
        val base = fixtureText("index-pilgrimages.json")
        val json = base.replace("\"id\": \"camino-de-santiago\",", "\"id\": \"shikoku-88\",")
        assertNotEquals(base, json)

        assertEquals(listOf("shikoku-88", "shikoku-88", ""), parse(json).groups.map { it.id })
    }

    @Test
    fun `a pilgrimage with no id or no name it can read leaves its sections to the loose group`() {
        val base = fixtureText("index-pilgrimages.json")
        listOf(
            base.replace("\"id\": \"shikoku-88\",", "\"id\": \"\","),
            base.replace("\"name\": { \"en\": \"Shikoku 88 Temple Pilgrimage\", \"ja\": \"四国八十八箇所\" }", "\"name\": { \"JA\": \"x\" }"),
        ).forEach { json ->
            assertNotEquals(base, json)
            val groups = parse(json).groups
            assertEquals(listOf("camino-de-santiago", ""), groups.map { it.id })
            assertEquals(
                "the loose routes trail in index order",
                listOf("shikoku-88-awa", "shikoku-88-iyo", "shikoku-88-sanuki", "shikoku-88-tosa", "st-cuthberts-way"),
                groups.last().entries.map { it.id },
            )
        }
    }

    @Test
    fun `a section named twice, or by two pilgrimages, appears once, where it is first claimed`() {
        val base = fixtureText("index-pilgrimages.json")
        val json = base.replace(
            "\"sections\": [\"camino-frances\", \"camino-primitivo\"]",
            "\"sections\": [\"camino-frances\", \"camino-frances\", \"shikoku-88-awa\"]",
        )
        assertNotEquals(base, json)

        val camino = parse(json).groups.first { it.id == "camino-de-santiago" }

        assertEquals(listOf("camino-frances"), camino.entries.map { it.id })
    }

    @Test
    fun `with no routes, no pilgrimages key keeps one empty group, and an empty list keeps none`() {
        assertEquals(listOf(PilgrimageGroup(id = "", name = null, entries = emptyList())), parse("""{"release":"v1.7.0","routes":[]}""").groups)
        assertEquals(emptyList<PilgrimageGroup>(), parse("""{"release":"v1.7.0","routes":[],"pilgrimages":[]}""").groups)
        val withRoutes = fixtureText("index.json").replace("\"release\": \"v1.7.0\",", "\"release\": \"v1.7.0\",\n  \"pilgrimages\": [],")
        assertEquals(listOf(null), parse(withRoutes).groups.map { it.name })
    }

    // ---- The route preview (P1 §9) ----------------------------------------

    @Test
    fun `a preview is the bytes as they arrived, kept beside the catalog under its route and release`() {
        stubs.stub(routePath("v1.7.0")) { declared(fixture("route.json")) }
        stubs.stub(routePath("v1.8.0")) { declared(fixture("route.json")) }
        val service = makeService()

        service.previewBlocking(entry(stageCount = 2), "v1.7.0")
        assertTrue(fixture("route.json").contentEquals(File(directory, "route-camino-frances-v1.7.0.json").readBytes()))

        service.previewBlocking(entry(stageCount = 2), "v1.8.0")
        assertEquals("a new release asks again", 2, server.requestCount)
        assertEquals(listOf("route-camino-frances-v1.7.0.json", "route-camino-frances-v1.8.0.json"), directory.list()?.sorted())
    }

    /** It passed the entry check before it was written, so iOS never checks it again. */
    @Test
    fun `a cached preview is served without the entry check`() {
        stubs.stub(routePath("v1.7.0")) { declared(fixture("route.json")) }
        makeService().previewBlocking(entry(stageCount = 2), "v1.7.0")

        assertEquals(2, makeService().previewBlocking(entry(stageCount = 5), "v1.7.0").stageCount)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a preview at the route cap passes and one byte more is out of reach`() {
        val exact = padded(fixture("route.json"), PilgrimageWayImporter.MAX_ROUTE_BYTES)
        stubs.stub(routePath("v1.7.0")) { declared(exact) }
        assertEquals(2, makeService().previewBlocking(entry(stageCount = 2), "v1.7.0").stageCount)
        assertTrue(directory.deleteRecursively())

        stubs.stub(routePath("v1.7.0")) { declared(exact + SPACE) }
        assertOutOfReach { makeService().previewBlocking(entry(stageCount = 2), "v1.7.0") }
    }

    /** pilgrim-ios #121, matched: a 404 is the catalog's "out of reach", where the download says it didn't finish. */
    @Test
    fun `a preview that 404s is out of reach, and one that arrives but doesn't decode is not walkable`() {
        stubs.stub(routePath("v1.7.0")) { declared(fixture("route.json")).setResponseCode(404) }
        assertOutOfReach { makeService().previewBlocking(entry(stageCount = 2), "v1.7.0") }

        stubs.stub(routePath("v1.7.0")) { declared("{}".toByteArray()) }
        assertRefused(PilgrimageError.NOT_WALKABLE) { makeService().previewBlocking(entry(stageCount = 2), "v1.7.0") }
        assertFalse("nothing written for either", directory.exists())
    }

    @Test
    fun `a preview for a release or a route id outside its rule is not walkable, and asks nothing`() {
        assertRefused(PilgrimageError.NOT_WALKABLE) { makeService().previewBlocking(entry(stageCount = 2), "main") }
        assertRefused(PilgrimageError.NOT_WALKABLE) { makeService().previewBlocking(entry(stageCount = 2).copy(id = "../etc"), "v1.7.0") }
        assertEquals(0, server.requestCount)
    }

    // ---- The cache file (P1 §8) -------------------------------------------

    @Test
    fun `the cache file round-trips the parsed catalog, its fetch time to the millisecond`() {
        stubIndex(fixture("index-pilgrimages.json"))
        clockMillis += 123
        val fetched = makeService().loadBlocking()

        assertEquals("the written file, and no temp beside it", listOf("catalog.json"), directory.list()?.toList())
        val file = Json.parseToJsonElement(File(directory, "catalog.json").readText()).jsonObject
        assertEquals(setOf("fetchedAt", "catalog"), file.keys)
        assertEquals(clockMillis, file.getValue("fetchedAt").jsonPrimitive.long)
        val loose = file.getValue("catalog").jsonObject.getValue("groups").jsonArray.last().jsonObject
        assertFalse("a null is left out, as iOS's encoder leaves out a nil", "name" in loose)

        stubs.reset()
        clockMillis += 48 * HOUR
        val offline = makeService()
        assertEquals(fetched, offline.loadBlocking())
        assertEquals(fetched, offline.catalog.value)
    }

    @Test
    fun `a corrupt cache, or one not exactly this format, is no cache at all`() {
        stubIndex(fixture("index.json"))
        makeService().loadBlocking()
        stubs.reset()
        clockMillis += 48 * HOUR
        val cacheFile = File(directory, "catalog.json")
        val written = Json.parseToJsonElement(cacheFile.readText()).jsonObject
        val catalog = written.getValue("catalog").jsonObject

        listOf(
            "not JSON" to "{ not json",
            "no release" to JsonObject(written + ("catalog" to JsonObject(catalog - "release"))).toString(),
            "a key it never writes" to JsonObject(written + ("extra" to JsonPrimitive(1))).toString(),
            "a fetch time that is not a number" to JsonObject(written + ("fetchedAt" to JsonPrimitive("yesterday"))).toString(),
        ).forEach { (name, text) ->
            cacheFile.writeText(text)
            assertOutOfReach(name) { makeService().loadBlocking() }
        }
    }

    // ---- Production wiring (Robolectric) ----------------------------------

    @Test
    fun `nothing happens at construction, and the cache's home is the files dir's Pilgrimages folder`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val service = PilgrimageCatalogService(context, PilgrimageCatalogService.httpClient(server.url("/")), Clock { clockMillis })

        assertNull(service.catalog.value)
        assertEquals(0, server.requestCount)
        val home = File(context.filesDir, "Pilgrimages")
        assertEquals("owner decision 5: filesDir, which a device transfer carries", home, service.directory)
        assertFalse("iOS creates it at init; Android waits for a write", home.exists())
    }

    /** The builder-test rule: the client Hilt hands the service, built for real. */
    @Test
    fun `the production client is the catalog's own - 15 s idle, 30 s in all, no retry, no cache`() {
        val client = NetworkModule.providePilgrimageCatalogHttpClient()

        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(15_000, client.writeTimeoutMillis)
        assertEquals(30_000, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertNull(client.cache)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `the production client sends the request the service builds, answered by the network`() {
        val request = PilgrimageCatalogService.request(PilgrimageCatalogService.INDEX_URL.toHttpUrl())
        assertEquals("GET", request.method)
        assertEquals(PilgrimageCatalogService.INDEX_URL, request.url.toString())
        stubIndex(fixture("index.json"))

        NetworkModule.providePilgrimageCatalogHttpClient().newCall(PilgrimageCatalogService.request(server.url(INDEX_PATH))).execute().use { response ->
            assertEquals(200, response.code)
            assertNull("no cache answered it", response.cacheResponse)
            assertNotNull(response.networkResponse)
        }
    }

    /** Its host rule is the CDN's, so even a hop back to the same test server is off the host. */
    @Test
    fun `the production client keeps redirects on cdn jsdelivr net`() {
        stubIndex { MockResponse().setResponseCode(302).setHeader("Location", server.url("/moved/index.json")) }
        val client = NetworkModule.providePilgrimageCatalogHttpClient()

        assertThrows(IOException::class.java) { client.newCall(PilgrimageCatalogService.request(server.url(INDEX_PATH))).execute() }
        assertEquals(1, server.requestCount)
    }

    // ---- Helpers -----------------------------------------------------------

    private fun makeService(cdn: HttpUrl = server.url("/")) = PilgrimageCatalogService(
        client = PilgrimageCatalogService.httpClient(cdn),
        cdn = cdn,
        resolveDirectory = { directory },
        clock = Clock { clockMillis },
        ioDispatcher = Dispatchers.IO,
    )

    private fun PilgrimageCatalogService.loadBlocking(force: Boolean = false): PilgrimageCatalog = runBlocking { load(force) }

    private fun PilgrimageCatalogService.previewBlocking(entry: PilgrimageCatalogEntry, release: String): PilgrimageRoute =
        runBlocking { routePreview(entry, release) }

    private fun parse(json: String): PilgrimageCatalog = PilgrimageCatalogService.parse(json.toByteArray())

    private fun stubIndex(body: ByteArray) = stubIndex { declared(body) }

    private fun stubIndex(response: () -> MockResponse) = stubs.stub(INDEX_PATH, response)

    /** `setBody` declares its length, as iOS's stub does by default. */
    private fun declared(body: ByteArray): MockResponse = MockResponse().setBody(Buffer().write(body))

    /** `setChunkedBody` declares none, as iOS's stub does when a test passes its own headers. */
    private fun chunked(body: ByteArray): MockResponse = MockResponse().setChunkedBody(Buffer().write(body), 64 * 1024)

    private fun routePath(release: String): String =
        requireNotNull(PilgrimageCatalogService.packageUrl(release, "camino-frances", "route.json")).encodedPath

    private fun entry(stageCount: Int) = PilgrimageCatalogEntry(
        id = "camino-frances",
        name = "Camino",
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 46.1,
        tradition = "christian",
        stageCount = stageCount,
        bytes = 214_000,
        placesPerStage = 0.0,
        sparse = false,
    )

    /** JSON allows trailing whitespace, so the padded file still parses. */
    private fun padded(bytes: ByteArray, size: Int): ByteArray = bytes + ByteArray(size - bytes.size) { ' '.code.toByte() }

    private fun assertOutOfReach(message: String? = null, block: () -> Unit) = assertRefused(PilgrimageError.CATALOG_UNREACHABLE, message, block)

    private fun assertRefused(expected: PilgrimageError, message: String? = null, block: () -> Unit) {
        val e = assertThrows(message, PilgrimageException::class.java) { block() }
        assertEquals(message, expected, e.error)
    }

    private fun assertParseRefused(message: String, json: String) = assertRefused(PilgrimageError.CATALOG_UNREACHABLE, message) { parse(json) }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("honor/pilgrimage/$name")) {
            "missing test resource honor/pilgrimage/$name"
        }.use { it.readBytes() }

    private fun fixtureText(name: String): String = fixture(name).toString(Charsets.UTF_8)

    /**
     * iOS's `StubURLProtocol` (`UnitTests/Helpers/StubURLProtocol.swift@7c200bf`):
     * answers by exact path, and drops the connection on any other, as
     * iOS's stub fails an unknown URL as offline. Every request counts in
     * [MockWebServer.requestCount], answered or not, as in iOS's `requestedURLs`.
     */
    private class PathStubs : Dispatcher() {
        private val responses = ConcurrentHashMap<String, () -> MockResponse>()

        fun stub(path: String, response: () -> MockResponse) {
            responses[path] = response
        }

        fun reset() = responses.clear()

        override fun dispatch(request: RecordedRequest): MockResponse =
            request.path?.let { responses[it] }?.invoke() ?: MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
    }

    private companion object {
        /** iOS's `Date(timeIntervalSince1970: 3_000_000)`. */
        const val START_MILLIS = 3_000_000_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val INDEX_PATH = "/gh/walktalkmeditate/open-pilgrimages@main/index.json"
        val SPACE = byteArrayOf(' '.code.toByte())
        val REFUSED_RELEASES = listOf(
            "main", "v1.7", "1.7.0", "V1.2.3", "v1.2.3.4", "v1.12.0\n", "v1.12.0 ", " v1.12.0", "v1.2.x", "",
            "v١.٢.٣",
        )
    }
}
