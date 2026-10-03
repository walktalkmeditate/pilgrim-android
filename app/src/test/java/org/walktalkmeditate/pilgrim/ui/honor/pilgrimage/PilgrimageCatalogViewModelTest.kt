// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.mockwebserver.MockResponse
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.HonorStageOutcome
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCopy
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.declared
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.waitUntil
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRoute
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * The catalog (pilgrimage-stage spec P4 §3, P1 §10): the six
 * `PilgrimageCatalogModelTests` of iOS's `PilgrimageCatalogServiceTests.swift@7c200bf`
 * (names kept), then the spec's additions: `hasUpdate` by inequality, the
 * three faces with a zero-route catalog unreachable, the rust line only
 * over a held list, the first grapheme, and the ViewModel's loads (the
 * unreachable copy offline with no cache, a failed retry, the installed
 * route and every route's ledger, the reload when it is on top again, a
 * restored catalog's first return counted as one, only the latest of two
 * loads writing, and "try again" as the one forced load). Robolectric for the strings; the
 * manager and the service run on real IO threads, so waits are wall-clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageCatalogViewModelTest {

    @get:Rule val folder = TemporaryFolder()

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private lateinit var world: PilgrimageScreensWorld

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        world = PilgrimageScreensWorld(folder.root)
    }

    @After
    fun tearDown() {
        world.close()
        Dispatchers.resetMain()
    }

    // ---- iOS PilgrimageCatalogModelTests ----

    private val entry = PilgrimageCatalogEntry(
        id = "camino-frances",
        name = "Camino de Santiago (Francés)",
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 764.0,
        tradition = "christian",
        stageCount = 33,
        bytes = 2_140_000,
        placesPerStage = 0.0,
        sparse = false,
    )

    private val sparseEntry = entry.copy(placesPerStage = 0.4, sparse = true)

    private fun card(entry: PilgrimageCatalogEntry, ledger: PilgrimageLedger?, isInstalled: Boolean) =
        PilgrimageCatalogModel.card(resources, entry, ledger, isInstalled, UnitSystem.Metric)

    @Test
    fun `a card without a package just counts the stages`() {
        assertEquals(
            "ES · ${StageFormat.distance(764_000.0, UnitSystem.Metric)} · 33 stages",
            card(entry, ledger = null, isInstalled = false),
        )
    }

    @Test
    fun `a card with a package carries its progress and leaves install to the badge`() {
        val led = PilgrimageLedger(routeId = "camino-frances")
            .recorded(0, "a", 24.2, HonorStageOutcome(progressFrac = 1.0, arrived = true), Instant.now())
        val line = card(entry, led, isInstalled = true)
        assertTrue(line, line.contains("stage 2 of 33"))
        assertFalse(line, line.contains("on your phone"))
    }

    /** One route is on the phone at a time, so the badge is the mark for which one, and it stays out of the card. */
    @Test
    fun `the badge marks the installed route and nothing else`() {
        assertNull(PilgrimageCatalogModel.installBadge(isInstalled = false, hasUpdate = false))
        assertNull(PilgrimageCatalogModel.installBadge(isInstalled = false, hasUpdate = true))
        assertEquals(
            "on your phone",
            PilgrimageCatalogModel.installBadge(isInstalled = true, hasUpdate = false)?.let { resources.getString(it.label) },
        )
    }

    /** `hasUpdate` means an update is waiting; the old card's "updated" read as though the route were current. */
    @Test
    fun `a waiting update does not claim to be already updated`() {
        val badge = PilgrimageCatalogModel.installBadge(isInstalled = true, hasUpdate = true)
        assertEquals("update ready", badge?.let { resources.getString(it.label) })
        assertNotEquals(
            "a waiting update and a current package must not share one glyph",
            badge?.glyph,
            PilgrimageCatalogModel.installBadge(isInstalled = true, hasUpdate = false)?.glyph,
        )
    }

    /** The glyph carries the state on screen, so the words have to survive where a screen reader reaches them. */
    @Test
    fun `every badge spells itself out for VoiceOver`() {
        for (hasUpdate in listOf(true, false)) {
            val badge = PilgrimageCatalogModel.installBadge(isInstalled = true, hasUpdate = hasUpdate)
            assertFalse(badge?.let { resources.getString(it.label) }.isNullOrEmpty())
            assertFalse(badge?.glyph?.name.isNullOrEmpty())
        }
    }

    @Test
    fun `a sparse route says so without hiding itself`() {
        assertEquals("few places marked yet", PilgrimageCatalogModel.sparseNote(resources, sparseEntry))
        assertNull(PilgrimageCatalogModel.sparseNote(resources, entry))
        // The note is its own quiet line, never folded into the card line.
        assertFalse(card(sparseEntry, ledger = null, isInstalled = false).contains("few places marked yet"))
    }

    // ---- The spec's additions: the model ----

    @Test
    fun `the card leaves out an empty country and prints the walker's unit`() {
        assertEquals("764 km · 33 stages", card(entry.copy(country = ""), ledger = null, isInstalled = false))
        assertEquals("764 km · 1 stage", card(entry.copy(country = null, stageCount = 1), ledger = null, isInstalled = false))
        assertEquals(
            "ES · 474.73 mi · 33 stages",
            PilgrimageCatalogModel.card(resources, entry, ledger = null, isInstalled = false, units = UnitSystem.Imperial),
        )
    }

    /** P1 §10: an installed route never walked still counts its stages, from the index's count. */
    @Test
    fun `an installed route never walked reads its stage count, and a walked one its progress`() {
        assertEquals("ES · 764 km · 33 stages", card(entry, ledger = null, isInstalled = true))
        val led = PilgrimageLedger(routeId = "camino-frances")
            .recorded(0, "a", 24.2, HonorStageOutcome(progressFrac = 1.0, arrived = true), Instant.now())
        assertEquals("ES · 764 km · stage 2 of 33 · 24.2 km walked", card(entry, led, isInstalled = true))
        assertEquals("a ledger is ignored for a route not installed", "ES · 764 km · 33 stages", card(entry, led, isInstalled = false))
    }

    /** P1 C17, pilgrim-ios #121: "update ready" means "different", an older catalog included. */
    @Test
    fun `an update is any release that differs, an older one included`() {
        val installed = installed(release = "v1.12.0")

        assertTrue(PilgrimageCatalogModel.hasUpdate(installed, entry, catalogRelease = "v1.11.0"))
        assertTrue(PilgrimageCatalogModel.hasUpdate(installed, entry, catalogRelease = "v1.13.0"))
        assertFalse(PilgrimageCatalogModel.hasUpdate(installed, entry, catalogRelease = "v1.12.0"))
        assertFalse("another route's install", PilgrimageCatalogModel.hasUpdate(installed, entry.copy(id = "camino-norte"), "v1.11.0"))
        assertFalse(PilgrimageCatalogModel.hasUpdate(installed = null, entry, catalogRelease = "v1.11.0"))
        assertTrue("no catalog compares against an empty release", PilgrimageCatalogModel.hasUpdate(installed, entry, catalogRelease = null))
    }

    /** P4 §3.2, correction 6: three faces, and an index with no routes is unreachable too. */
    @Test
    fun `the spinner shows only while nothing is held, the list whenever a route is, and the unreachable copy otherwise`() {
        val listed = PilgrimageCatalog("v1.7.0", listOf(entry))
        val empty = PilgrimageCatalog("v1.7.0", emptyList())

        assertEquals(PilgrimageCatalogFace.Spinner, PilgrimageCatalogModel.face(isLoading = true, catalog = null, failure = null))
        assertEquals(
            "a reload over a held list keeps the list",
            PilgrimageCatalogFace.Listing(listed, failure = null),
            PilgrimageCatalogModel.face(isLoading = true, catalog = listed, failure = null),
        )
        assertEquals(
            PilgrimageCatalogFace.Unreachable(PilgrimageError.CATALOG_UNREACHABLE),
            PilgrimageCatalogModel.face(isLoading = false, catalog = null, failure = PilgrimageError.CATALOG_UNREACHABLE),
        )
        assertEquals(
            PilgrimageCatalogFace.Unreachable(PilgrimageError.CATALOG_UNREACHABLE),
            PilgrimageCatalogModel.face(isLoading = false, catalog = empty, failure = null),
        )
        assertEquals(
            "the rust line rides only above a list",
            PilgrimageCatalogFace.Listing(listed, PilgrimageError.CATALOG_UNREACHABLE),
            PilgrimageCatalogModel.face(isLoading = false, catalog = listed, failure = PilgrimageError.CATALOG_UNREACHABLE),
        )
    }

    /** P4 §3.3: Swift's `prefix(1)` is one grapheme, which `take(1)` would split. */
    @Test
    fun `the plate's initial is the name's first grapheme`() {
        assertEquals("C", PilgrimageCatalogModel.initial("Camino de Santiago"))
        assertEquals("Ō", PilgrimageCatalogModel.initial("Ōhechi"))
        assertEquals("🇯🇵", PilgrimageCatalogModel.initial("🇯🇵 Kumano"))
        assertEquals("", PilgrimageCatalogModel.initial(""))
    }

    // ---- The spec's additions: the ViewModel ----

    @Test
    fun `offline with no cache, the catalog reads the routes are out of reach right now`() {
        world.unstubIndex()

        val vm = loaded(world.catalogViewModel())

        assertNull(vm.catalog.value)
        val face = PilgrimageCatalogModel.face(vm.state.value.isLoading, vm.catalog.value, vm.state.value.failure)
        assertEquals(PilgrimageCatalogFace.Unreachable(PilgrimageError.CATALOG_UNREACHABLE), face)
        assertEquals("the routes are out of reach right now", resources.getString(lineOf(face)))
    }

    /** P4 §3.4: a forced load with no disk cache throws even while the list is held; the rust line is its one voice. */
    @Test
    fun `a retry that fails with the list held shows the rust line over the list`() {
        val vm = loaded(world.catalogViewModel())
        assertNotNull(vm.catalog.value)
        File(world.catalogDirectory, "catalog.json").delete()
        world.unstubIndex()

        vm.load(force = true)
        loaded(vm)

        val face = PilgrimageCatalogModel.face(vm.state.value.isLoading, vm.catalog.value, vm.state.value.failure)
        assertEquals(PilgrimageCatalogFace.Listing(vm.catalog.value!!, PilgrimageError.CATALOG_UNREACHABLE), face)
    }

    @Test
    fun `a load reads what is installed and the ledger of every listed route, the first of an id kept`() {
        world.stubIndex(
            PilgrimageScreensWorld.index(routes = listOf("camino-frances" to "Francés", "camino-norte" to "Norte")),
        )
        world.install()
        world.harness.ledgers.save(walked("camino-frances"))
        world.harness.ledgers.save(walked("camino-norte"))

        val state = loaded(world.catalogViewModel()).state.value

        assertEquals("camino-frances", state.installed?.routeId)
        assertEquals(setOf("camino-frances", "camino-norte"), state.ledgers.keys)
        assertNull(state.failure)
    }

    /** iOS's `onAppear` after `hasAppearedOnce`: the opening's own load covers the first time on top. */
    @Test
    fun `coming back on top reloads, but the first time on top doesn't`() {
        val vm = loaded(world.catalogViewModel())
        assertNull(vm.state.value.installed)
        world.install()

        vm.resumed()
        loaded(vm)
        assertNull("the first time on top is the opening's own load", vm.state.value.installed)

        vm.resumed()
        waitUntil("the reload reads the install") { vm.state.value.installed != null }
        assertEquals("camino-frances", vm.state.value.installed?.routeId)
    }

    /**
     * P4 A-3: a catalog restored under its route page had been on top
     * before the kill, so that page's closing is a return, and reloads, as
     * it would in an unbroken process.
     */
    @Test
    fun `a catalog restored under its route page reloads on its first return to the top`() {
        val saved = SavedStateHandle()
        loaded(world.catalogViewModel(saved)).resumed()
        world.newProcessCatalogService()
        val restoredState = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })

        val restored = loaded(world.catalogViewModel(restoredState))
        assertNull(restored.state.value.installed)
        world.install()
        restored.resumed()

        waitUntil("the reload reads the install the route page made") { restored.state.value.installed != null }
    }

    /**
     * Only the latest load writes. The first opening's fetch fails slowly
     * with no cache while the walker, back with Wi-Fi on, starts another:
     * the older failure neither drops the spinner for the unreachable face
     * nor leaves its rust line over the list the newer one loads.
     */
    @Test
    fun `an older load that fails after a newer one began writes nothing`() {
        val firstFetch = world.harness.stubs.hold(PilgrimageScreensWorld.INDEX_PATH)
        val secondAnswer = CountDownLatch(1)
        val fetches = AtomicInteger()
        world.harness.stubs.stub(PilgrimageScreensWorld.INDEX_PATH) {
            if (fetches.getAndIncrement() == 0) {
                MockResponse().setResponseCode(HTTP_UNAVAILABLE)
            } else {
                secondAnswer.await(10, TimeUnit.SECONDS)
                declared(PilgrimageScreensWorld.index())
            }
        }
        val vm = world.catalogViewModel()
        firstFetch.awaitArrival()

        vm.load()
        firstFetch.release()
        waitUntil("the newer load is fetching") { fetches.get() == 2 }
        waitUntil("the older load has ended") { vm.viewModelScope.coroutineContext.job.children.count() == 1 }

        assertEquals("still the spinner", PilgrimageCatalogFace.Spinner, faceOf(vm))
        secondAnswer.countDown()
        loaded(vm)
        assertEquals(PilgrimageCatalogFace.Listing(vm.catalog.value!!, failure = null), faceOf(vm))
    }

    /** P4 §3.4: only "try again" reaches past a fresh cache. */
    @Test
    fun `try again forces a fetch, and a reload on top never does`() {
        val vm = loaded(world.catalogViewModel())
        val fetched = world.harness.server.requestCount

        vm.resumed()
        vm.resumed()
        loaded(vm)
        assertEquals("a reload reads the day-old cache", fetched, world.harness.server.requestCount)

        vm.load(force = true)
        waitUntil("the forced fetch") { world.harness.server.requestCount == fetched + 1 }
        loaded(vm)
    }

    // ---- Helpers ----

    private fun loaded(vm: PilgrimageCatalogViewModel): PilgrimageCatalogViewModel {
        waitUntil("the load ends") { !vm.state.value.isLoading }
        return vm
    }

    private fun faceOf(vm: PilgrimageCatalogViewModel): PilgrimageCatalogFace =
        PilgrimageCatalogModel.face(vm.state.value.isLoading, vm.catalog.value, vm.state.value.failure)

    private fun lineOf(face: PilgrimageCatalogFace): Int =
        PilgrimageCopy.line((face as PilgrimageCatalogFace.Unreachable).error)

    private fun walked(routeId: String) = PilgrimageLedger(routeId)
        .recorded(0, "s", 24.2, HonorStageOutcome(progressFrac = 1.0, arrived = true), PilgrimagePackageHarness.WALKED_AT)

    private fun installed(release: String) = PilgrimagePackageManager.Installed(
        routeId = "camino-frances",
        release = release,
        route = PilgrimageRoute(
            id = "camino-frances",
            name = "Camino de Santiago (Francés)",
            names = emptyMap(),
            country = "ES",
            region = "Europe",
            distanceKm = 764.0,
            stageCount = 33,
            tradition = "christian",
            summary = null,
            stages = emptyList(),
        ),
    )

    private companion object {
        const val HTTP_UNAVAILABLE = 503
    }
}
