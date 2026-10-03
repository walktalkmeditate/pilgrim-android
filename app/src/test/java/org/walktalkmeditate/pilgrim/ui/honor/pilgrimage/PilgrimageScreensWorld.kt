// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogService
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.awaitBlocking
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.declared
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.Clock

/**
 * The catalog and route page over the package harness's world: one
 * MockWebServer answering the index as well as the package and its
 * preview, a catalog service caching into [root], and the manager the
 * harness builds. Every ViewModel made here is cancelled and joined at
 * [close], before the harness's scope and server go.
 */
internal class PilgrimageScreensWorld(private val root: File) {

    val harness = PilgrimagePackageHarness(root)
    val manager = harness.makeManager()
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
    }

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
    ) = PilgrimageRouteViewModel(
        savedState = saved,
        catalogs = catalogs,
        packages = manager,
        ledgerStore = harness.ledgers,
        wayStore = harness.wayStore,
        unitsPreferences = units,
        ioDispatcher = Dispatchers.IO,
    ).also { viewModels += it }

    fun install(routeId: String = PilgrimagePackageHarness.ROUTE_ID, release: String = PilgrimagePackageHarness.RELEASE) {
        manager.download(harness.entry(routeId), release).awaitBlocking()
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
        ): ByteArray {
            val rows = routes.joinToString(",") { (id, name) ->
                """{"id": "$id", "name": {"en": "$name"}, "region": "Europe", "country": "ES", "distanceKm": 46.1,
                   "tradition": "christian", "ways": {"stageCount": 2, "bytes": 214000}}"""
            }
            return """{"release": "$release", "routes": [$rows]}""".toByteArray(Charsets.UTF_8)
        }
    }
}
