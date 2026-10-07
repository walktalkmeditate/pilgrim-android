// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.FakeTileRegionLoader
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.FakeWalkSignals
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.InMemoryTilesCalibration
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogService
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.awaitBlocking
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.bytes
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.declared
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.fixtureJson
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.with
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.recordingHandler
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.Clock

/**
 * The catalog and route page over the package harness's world: one
 * MockWebServer answering the index as well as the package and its
 * preview, a catalog service caching into [root], and the manager the
 * harness builds. Every ViewModel made here is cancelled and joined at
 * [close], before the harness's scope and server go.
 *
 * The package manager holds a tiles manager over C2's fake loader, as the
 * flag-on app's does. Its main thread is one real thread, [tilesMain], and
 * the route page's row reads and writes there too, so a test drives the
 * fake there ([onTiles]), as production's callbacks are hopped to the main
 * thread. The store hasn't answered until a test says so ([answerStore]).
 */
internal class PilgrimageScreensWorld(private val root: File) {

    val harness = PilgrimagePackageHarness(root)

    private val tilesExecutor = Executors.newSingleThreadExecutor { Thread(it, "tiles-main") }
    val tilesMain = tilesExecutor.asCoroutineDispatcher()

    /** What escaped the tiles manager's posted work; [close] fails a test that leaves anything here. */
    val tilesEscaped = CopyOnWriteArrayList<Throwable>()
    private val tilesScope = CoroutineScope(SupervisorJob() + tilesMain + recordingHandler(tilesEscaped))
    val tilesLoader = FakeTileRegionLoader()
    val tilesCalibration = InMemoryTilesCalibration()

    /** The tiles manager's walk, apart from the package guard's, so a test can refuse a save and still Remove. */
    val tilesSignals = FakeWalkSignals()
    val tiles = PilgrimageTilesManager(tilesLoader, tilesCalibration, tilesSignals, tilesMain, tilesScope)

    /** How many times a route page has resolved the tiles manager's provider. */
    val tilesResolutions = AtomicInteger()

    val manager = harness.makeManager(tiles = tiles)
    val catalogDirectory = File(root, "Pilgrimages")
    val units = FakeUnitsPreferencesRepository(UnitSystem.Metric)

    @Volatile var clockMillis = START_MILLIS

    private val viewModels = mutableListOf<ViewModel>()

    var catalogs = makeCatalogService()
        private set

    init {
        stubIndex(index())
    }

    fun close() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        harness.close()
        runBlocking { tilesScope.coroutineContext.job.cancelAndJoin() }
        tilesExecutor.shutdown()
        check(tilesEscaped.isEmpty()) { "escaped the tiles manager's posted work: $tilesEscaped" }
    }

    /** [block] on the tiles manager's thread, where the fake loader and `cancel` are driven. */
    fun <T> onTiles(block: () -> T): T = runBlocking(tilesMain) { block() }

    /** The store's first answer, with whatever the fake holds: a read, so a surface waiting on it reads at once. */
    fun answerStore() = onTiles { tilesLoader.releaseRegions() }

    /** A second service over the same cache, as a new process builds one: nothing held in memory. */
    fun newProcessCatalogService(): PilgrimageCatalogService = makeCatalogService().also { catalogs = it }

    private fun makeCatalogService() = PilgrimageCatalogService(
        client = PilgrimageCatalogService.httpClient(harness.server.url("/")),
        cdn = harness.server.url("/"),
        resolveDirectory = { catalogDirectory },
        clock = Clock { clockMillis },
        ioDispatcher = Dispatchers.IO,
    )

    fun stubIndex(body: ByteArray) = harness.stubs.stub(INDEX_PATH) { declared(body) }

    fun unstubIndex() = harness.stubs.unstub(INDEX_PATH)

    /** The service's own `load`, as the catalog screen leaves it held for the route page. */
    fun holdCatalog(): PilgrimageCatalog = runBlocking { catalogs.load() }

    fun catalogViewModel(saved: SavedStateHandle = SavedStateHandle()) = PilgrimageCatalogViewModel(
        savedState = saved,
        catalogs = catalogs,
        packages = manager,
        ledgerStore = harness.ledgers,
        unitsPreferences = units,
        ioDispatcher = Dispatchers.IO,
    ).also { viewModels += it }

    fun routeViewModel(
        routeId: String = PilgrimagePackageHarness.ROUTE_ID,
        saved: SavedStateHandle = SavedStateHandle(mapOf(PilgrimageRouteViewModel.ARG_ROUTE_ID to routeId)),
        flags: ReleaseFlags = FixedReleaseFlags(honor = true),
        wayStore: WayStore = harness.wayStore,
    ) = PilgrimageRouteViewModel(
        savedState = saved,
        catalogs = catalogs,
        packages = manager,
        ledgerStore = harness.ledgers,
        wayStore = wayStore,
        unitsPreferences = units,
        releaseFlags = flags,
        tilesProvider = Provider { tiles.also { tilesResolutions.incrementAndGet() } },
        ioDispatcher = Dispatchers.IO,
        tilesDispatcher = tilesMain,
    ).also { viewModels += it }

    fun install(
        routeId: String = PilgrimagePackageHarness.ROUTE_ID,
        release: String = PilgrimagePackageHarness.RELEASE,
        entry: PilgrimageCatalogEntry = harness.entry(routeId),
    ) {
        manager.download(entry, release).awaitBlocking()
    }

    /**
     * The route at [release] with [stageCount] stages, each the fixture's
     * first stage under its own index and name, its line moved east 0.04°
     * a stage so no two corridors share a hash; a [redrawn] stage's line
     * is moved a further 0.01°, so its corridor is another release's.
     */
    fun stubStages(stageCount: Int, release: String, redrawn: Set<Int> = emptySet()) {
        val route = fixtureJson("route.json")
        val row = route.getValue("stages").jsonArray[0].jsonObject
        val rows = (0 until stageCount).map { index -> row.with("index", JsonPrimitive(index)).with("name", JsonPrimitive("stage $index")) }
        harness.stub("route.json", route.with("stageCount", JsonPrimitive(stageCount)).with("stages", JsonArray(rows)).bytes(), release)
        val first = fixtureJson("stage-00.json")
        for (index in 0 until stageCount) {
            val east = index * 0.04 + if (index in redrawn) 0.01 else 0.0
            val line = first.getValue("route").jsonArray.map { point ->
                val at = point.jsonObject
                at.with("lon", JsonPrimitive(at.getValue("lon").jsonPrimitive.double + east))
            }
            val stage = first.getValue("stage").jsonObject
                .with("index", JsonPrimitive(index))
                .with("count", JsonPrimitive(stageCount))
                .with("name", JsonPrimitive("stage $index"))
            val file = first
                .with("id", JsonPrimitive(WayStore.stageWayId(PilgrimagePackageHarness.ROUTE_ID, index)))
                .with("route", JsonArray(line))
                .with("stage", stage)
            harness.stub(PilgrimagePackageManager.stageFileName(index), file.bytes(), release)
        }
    }

    /**
     * The manager busy, as the launch sweep keeps it after a kill: a Remove
     * of a route nothing installed, held at its guard's first read, so
     * `installed()` waits behind it until [ManagerHold.release]. Every
     * guard read from here on is counted, the holder's included.
     */
    fun holdManager(): ManagerHold {
        val gate = CountDownLatch(1)
        val reads = AtomicInteger()
        harness.signals.onCheck = {
            reads.incrementAndGet()
            gate.await(10, TimeUnit.SECONDS)
        }
        val holder = manager.remove(UNLISTED_ROUTE_ID)
        PilgrimagePackageHarness.waitUntil("the manager is held") { reads.get() == 1 }
        return ManagerHold(gate, reads, holder)
    }

    class ManagerHold(private val gate: CountDownLatch, private val reads: AtomicInteger, private val holder: Deferred<Unit>) {
        val guardReads: Int get() = reads.get()

        fun release() {
            gate.countDown()
            holder.awaitBlocking()
        }
    }

    companion object {
        /** A route no index lists and nothing installs. */
        const val UNLISTED_ROUTE_ID = "camino-ingles"

        const val INDEX_PATH = "/gh/walktalkmeditate/open-pilgrimages@main/index.json"
        const val START_MILLIS = 3_000_000_000L

        /**
         * An index listing [routes] (id to name), each a two-stage route
         * the harness can serve, at [release]. A route with no name has
         * the harness entry's.
         */
        fun index(
            release: String = PilgrimagePackageHarness.RELEASE,
            routes: List<Pair<String, String>> = listOf(PilgrimagePackageHarness.ROUTE_ID to "Camino de Santiago (Francés)"),
            stageCount: Int = 2,
        ): ByteArray {
            val rows = routes.joinToString(",") { (id, name) ->
                """{"id": "$id", "name": {"en": "$name"}, "region": "Europe", "country": "ES", "distanceKm": 46.1,
                   "tradition": "christian", "ways": {"stageCount": $stageCount, "bytes": 214000}}"""
            }
            return """{"release": "$release", "routes": [$rows]}""".toByteArray(Charsets.UTF_8)
        }
    }
}
