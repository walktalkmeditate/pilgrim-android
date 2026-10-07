// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.walktalkmeditate.pilgrim.data.honor.WayStore

/**
 * The world of iOS `PilgrimagePackageManagerTests` and its `+Fixtures`
 * extension (`UnitTests/Honor/PilgrimagePackageManagerTests*.swift@7c200bf`):
 * a Ways store and a ledger store in [root], the two-stage fixture package
 * served at v1.7.0, and a manager over them. Packages iOS builds by JSON
 * mutation are built the same way; `route.json` and a stage file always
 * change together, since the download checks a stage's count and name
 * against its route file.
 *
 * iOS's `StubURLProtocol` is a MockWebServer answering by exact path,
 * dropping the connection on any other ([PackageStubs]); its delays are
 * holds the test releases. iOS's `isWalkActive` is [FakeWalkSignals].
 */
internal class PilgrimagePackageHarness(root: File) {

    val server = MockWebServer()
    val stubs = PackageStubs()
    val waysDir = File(root, "Ways")
    val tempRoot = File(root, "pilgrimage-tmp")
    val wayStore = WayStore({ waysDir }, syncDirectory = { true })
    val ledgers = PilgrimageLedgerStore(wayStore)
    val signals = FakeWalkSignals()

    /** The manager's own scope, the app scope's stand-in; cancelled at [close]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val entry = PilgrimageCatalogEntry(
        id = "camino-frances",
        name = "Camino de Santiago (Francés)",
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 46.1,
        tradition = "christian",
        stageCount = 2,
        bytes = 214_000,
        placesPerStage = 0.0,
        sparse = false,
    )

    val norte = entry.copy(id = "camino-norte", name = "Camino del Norte")

    val entryWithThreeStages = entry.copy(stageCount = 3, bytes = 300_000)

    val entryWithOneStage = entry.copy(distanceKm = 24.2, stageCount = 1, bytes = 100_000)

    init {
        server.dispatcher = stubs
        server.start()
        stubWholePackage()
    }

    fun close() {
        stubs.releaseAll()
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        server.shutdown()
    }

    fun makeManager(tiles: PilgrimageTiles? = null) = PilgrimagePackageManager(
        store = wayStore,
        ledgers = ledgers,
        client = PilgrimagePackageManager.httpClient(server.url("/")),
        cdn = server.url("/"),
        signals = signals,
        resolveTempRoot = { tempRoot },
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        tiles = tiles,
    )

    fun entry(routeId: String) = entry.copy(id = routeId)

    // ---- The stub table ---------------------------------------------------------

    /** A package file's path on the CDN, as `packageUrl` names it. */
    fun path(file: String, release: String = RELEASE, routeId: String = ROUTE_ID): String =
        requireNotNull(PilgrimageCatalogService.packageUrl(release, routeId, file)).encodedPath

    fun stub(file: String, body: ByteArray, release: String = RELEASE, routeId: String = ROUTE_ID) =
        stubs.stub(path(file, release, routeId)) { declared(body) }

    fun stub(file: String, release: String = RELEASE, routeId: String = ROUTE_ID, response: () -> MockResponse) =
        stubs.stub(path(file, release, routeId), response)

    fun hold(file: String, release: String = RELEASE, routeId: String = ROUTE_ID): Hold =
        stubs.hold(path(file, release, routeId))

    fun stubWholePackage(release: String = RELEASE) {
        listOf("route.json", "stage-00.json", "stage-01.json").forEach { stub(it, fixture(it), release) }
    }

    /** iOS `stubNorte`: the same two stages, re-slugged by text as a second route. */
    fun stubNorte(release: String = RELEASE) {
        listOf("route.json", "stage-00.json", "stage-01.json").forEach { file ->
            val reslugged = fixtureText(file).replace(ROUTE_ID, NORTE_ID)
            stub(file, reslugged.toByteArray(), release, NORTE_ID)
        }
    }

    /** iOS `stubThreeStagePackage`: only the rollback cases need a previous install larger than its successor. */
    fun stubThreeStagePackage(release: String = RELEASE) {
        stub("route.json", threeStageRoute(), release)
        (0..1).forEach { stub(PilgrimagePackageManager.stageFileName(it), stageFixture(it, count = 3), release) }
        stub("stage-02.json", thirdStage(), release)
    }

    /** iOS `stubOneStagePackage`: stage 0 under its own name, the identity a reconcile keeps, and stage 1 gone. */
    fun stubOneStagePackage(release: String) {
        stub("route.json", oneStageRoute(), release)
        stub("stage-00.json", stageFixture(0, count = 1), release)
    }

    /** iOS `stubTwoStagePackage`: the two fixture stages under another route id, by JSON rather than by text. */
    fun stubTwoStagePackage(routeId: String, release: String = RELEASE) {
        stub("route.json", fixtureJson("route.json").with("id", JsonPrimitive(routeId)).bytes(), release, routeId)
        (0..1).forEach { index ->
            val file = PilgrimagePackageManager.stageFileName(index)
            val stage = fixtureJson(file).getValue("stage").jsonObject.with("routeId", JsonPrimitive(routeId))
            val reslugged = fixtureJson(file)
                .with("id", JsonPrimitive(WayStore.stageWayId(routeId, index)))
                .with("stage", stage)
            stub(file, reslugged.bytes(), release, routeId)
        }
    }

    /** iOS `seedTwoStageLedger`: both stages walked, so a reconcile can keep one and drop the other. */
    fun seedTwoStageLedger() {
        val arrived = HonorStageOutcome(progressFrac = 1.0, arrived = true)
        ledgers.save(
            PilgrimageLedger(ROUTE_ID)
                .recorded(0, "Saint-Jean-Pied-de-Port to Roncesvalles", 24.2, arrived, WALKED_AT)
                .recorded(1, "Roncesvalles to Zubiri", 21.9, arrived, WALKED_AT),
        )
    }

    // ---- JSON mutation ------------------------------------------------------------

    /** iOS `stageFixture(_:count:)`: a checked-in stage re-counted; its name, which the route file names it by, unmoved. */
    fun stageFixture(index: Int, count: Int): ByteArray {
        val file = PilgrimagePackageManager.stageFileName(index)
        val stage = fixtureJson(file).getValue("stage").jsonObject.with("count", JsonPrimitive(count))
        return fixtureJson(file).with("stage", stage).bytes()
    }

    /** The fixture's first stage with one field of its stage block rewritten (iOS `patchedStageZero`). */
    fun patchedStageZero(count: Int? = null, name: String? = null): ByteArray {
        var stage = fixtureJson("stage-00.json").getValue("stage").jsonObject
        if (count != null) stage = stage.with("count", JsonPrimitive(count))
        if (name != null) stage = stage.with("name", JsonPrimitive(name))
        return fixtureJson("stage-00.json").with("stage", stage).bytes()
    }

    private fun threeStageRoute(): ByteArray {
        val route = fixtureJson("route.json")
        val third = JsonObject(
            mapOf(
                "index" to JsonPrimitive(2),
                "name" to JsonPrimitive(THIRD_STAGE_NAME),
                "distanceKm" to JsonPrimitive(20.4),
                "gainMeters" to JsonPrimitive(128.0),
                "hours" to JsonObject(mapOf("min" to JsonPrimitive(5.0), "max" to JsonPrimitive(6.0))),
                "difficulty" to JsonPrimitive("easy"),
            ),
        )
        return route
            .with("stageCount", JsonPrimitive(3))
            .with("stages", JsonArray(route.getValue("stages").jsonArray + third))
            .bytes()
    }

    private fun oneStageRoute(): ByteArray {
        val route = fixtureJson("route.json")
        return route
            .with("stageCount", JsonPrimitive(1))
            .with("stages", JsonArray(route.getValue("stages").jsonArray.dropLast(1)))
            .bytes()
    }

    /** `stage-01.json` reshaped into the third stage of [threeStageRoute], under the name that file gives index 2. */
    private fun thirdStage(): ByteArray {
        val file = fixtureJson("stage-01.json")
        val stage = file.getValue("stage").jsonObject
            .with("index", JsonPrimitive(2))
            .with("count", JsonPrimitive(3))
            .with("name", JsonPrimitive(THIRD_STAGE_NAME))
        return file.with("id", JsonPrimitive("pilgrimage:camino-frances:2")).with("stage", stage).bytes()
    }

    companion object {
        const val RELEASE = "v1.7.0"
        const val ROUTE_ID = "camino-frances"
        const val NORTE_ID = "camino-norte"
        const val THIRD_STAGE_NAME = "Zubiri to Pamplona"
        val WALKED_AT: Instant = Instant.ofEpochSecond(1_800_000_000)

        fun fixture(name: String): ByteArray =
            requireNotNull(PilgrimagePackageHarness::class.java.classLoader?.getResourceAsStream("honor/pilgrimage/$name")) {
                "missing test resource honor/pilgrimage/$name"
            }.use { it.readBytes() }

        fun fixtureText(name: String): String = fixture(name).toString(Charsets.UTF_8)

        fun fixtureJson(name: String): JsonObject = Json.parseToJsonElement(fixtureText(name)).jsonObject

        fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

        fun JsonObject.bytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)

        /** `setBody` declares its length, as iOS's stub does by default. */
        fun declared(body: ByteArray): MockResponse = MockResponse().setBody(Buffer().write(body))

        /** `setChunkedBody` declares none, as iOS's stub does when a test passes its own headers. */
        fun chunked(body: ByteArray): MockResponse = MockResponse().setChunkedBody(Buffer().write(body), 64 * 1024)

        fun <T> Deferred<T>.awaitBlocking(): T = runBlocking { withTimeout(AWAIT_MILLIS) { await() } }

        fun assertRefused(expected: PilgrimageError, message: String? = null, block: () -> Unit) {
            val e = assertThrows(message, PilgrimageException::class.java) { block() }
            assertEquals(message, expected, e.error)
        }

        /** A wall-clock poll: the manager's steps run on real IO threads. */
        fun waitUntil(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MILLIS)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "timed out waiting until $what" }
                Thread.sleep(POLL_MILLIS)
            }
        }

        private const val AWAIT_MILLIS = 10_000L
        private const val POLL_MILLIS = 5L
    }
}

/** A held answer: [arrived] once the request reaches the server, answered once the test calls [release]. */
internal class Hold {
    private val arrival = CountDownLatch(1)
    private val gate = CountDownLatch(1)

    fun awaitArrival() = check(arrival.await(10, TimeUnit.SECONDS)) { "the held request never arrived" }

    fun release() = gate.countDown()

    fun pass() {
        arrival.countDown()
        gate.await(10, TimeUnit.SECONDS)
    }
}

/**
 * iOS's `StubURLProtocol`: answers by exact path, and drops the connection
 * on any other, as iOS fails an unknown URL as offline. A [hold] keeps a
 * path's answer back until the test lets it go.
 */
internal class PackageStubs : Dispatcher() {
    private val responses = ConcurrentHashMap<String, () -> MockResponse>()
    private val holds = ConcurrentHashMap<String, Hold>()

    fun stub(path: String, response: () -> MockResponse) {
        responses[path] = response
    }

    fun unstub(path: String) {
        responses.remove(path)
    }

    fun hold(path: String): Hold = Hold().also { holds[path] = it }

    fun releaseAll() = holds.values.forEach(Hold::release)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: return dropped()
        holds[path]?.pass()
        return responses[path]?.invoke() ?: dropped()
    }

    private fun dropped() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
}

/**
 * The guard's four inputs, each set by a test (iOS's `isWalkActive`
 * closure is the first). [finalizePending] clears the live rows unless
 * [finalizeClears] says the step fails again. [onCheck] runs at every
 * reading of the walk screen, the guard's first question.
 */
internal class FakeWalkSignals : PilgrimageWalkSignals {
    @Volatile var screenUp = false
    @Volatile var active = false
    @Volatile var liveWayIds: Set<String> = emptySet()
    @Volatile var beginInFlight = false
    @Volatile var finalizeClears = true
    @Volatile var onCheck: () -> Unit = {}
    val finalizeRuns = AtomicInteger()

    override fun walkScreenUp(): Boolean {
        onCheck()
        return screenUp
    }

    /** Every walk-row read, counted from 1. */
    val activeReads = AtomicInteger()

    /**
     * Runs inside each walk-row read, given its number, before the answer:
     * a test suspends it to hold a Room read in flight, or throws from it
     * as a failed read would.
     */
    @Volatile var onActiveRead: suspend (read: Int) -> Unit = {}

    override suspend fun walkActive(): Boolean {
        onActiveRead(activeReads.incrementAndGet())
        return active
    }

    /** When set, the live-session read throws it, as a failed Room read would. */
    @Volatile var liveIdsFailure: Exception? = null

    override suspend fun liveSessionWayIds(): Set<String> {
        liveIdsFailure?.let { throw it }
        return liveWayIds
    }

    override fun beginInFlight(): Boolean = beginInFlight

    override suspend fun finalizePending() {
        finalizeRuns.incrementAndGet()
        if (finalizeClears) liveWayIds = emptySet()
    }
}
