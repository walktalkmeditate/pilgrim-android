// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.awaitBlocking
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.honor.WaySweeper

/**
 * Settings → Ways and its Data card row (shared-walk spec S4 §2–§5): a
 * port of iOS `WaysListModelTests.swift@7c200bf`, plus the list's order,
 * details, sweeps, deletes, and the R6 hiding; and the package footer
 * with iOS's Ways list case from `PilgrimageStageWalkTests.swift`
 * (pilgrimage-stage spec P2 §11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaysListViewModelTest {

    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources get() = context.resources
    private val dispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private var clockMillis = now.toEpochMilli()
    private lateinit var store: WayStore
    private val cancelled = mutableListOf<String>()
    private val shown = MutableStateFlow(true)
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = WayStore(
            { File(folder.root, "Ways") },
            Clock { clockMillis },
            syncDirectory = { true },
            allocatedBytes = { it.length() },
        )
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
    }

    private fun list(
        installedRouteName: suspend () -> String? = { null },
        store: WayStore = this.store,
    ) = WaysListViewModel(
        store = store,
        sweeper = WaySweeper(store, { emptySet() }, { cancelled += it }, Clock { clockMillis }, dispatcher),
        cancelGather = { cancelled += it },
        installedRouteName = installedRouteName,
        availability = WaysAvailability(shown, shownAtFirst = true),
        zone = { ZoneId.of("America/Los_Angeles") },
        locale = { Locale.US },
        ioDispatcher = dispatcher,
    ).also { viewModels += it }

    private fun row() = WaysRowViewModel(store, WaysAvailability(shown, shownAtFirst = true), dispatcher).also { viewModels += it }

    private fun way(id: String, source: WaySource, expires: Instant? = null, moments: List<WayMoment> = emptyList()) = Way(
        id = id,
        source = source,
        title = "the way of $id",
        // 03:00 UTC on Aug 1 is still Jul 31 in Los Angeles.
        departedAt = Instant.parse("2026-08-01T03:00:00Z"),
        tzIdentifier = null,
        expires = expires,
        route = listOf(WayPoint(0.0, 0.0, null, 0.0), WayPoint(0.0, 0.001, null, 60.0)),
        totalDistanceMeters = 111.0,
        theirActiveSeconds = 60.0,
        moments = moments,
        weather = null,
    )

    private fun share(code: String, expires: Instant? = null, voices: Int = 0) = way(
        id = "share:$code",
        source = WaySource.Share(code, "https://walk.pilgrimapp.org/$code"),
        expires = expires,
        moments = (1..voices).map {
            WayMoment(id = "voice-$it", frac = 0.5, at = null, kind = WayMomentKind.Voice(0.6, 3.0, VoiceKind.SPOKEN, WayMedia.File("audio/$it.m4a")))
        },
    )

    private fun own(): Way {
        val uuid = UUID.randomUUID().toString()
        return way("walk:$uuid", WaySource.OwnWalk(uuid))
    }

    private fun stage(index: Int, routeId: String = "kumano-kodo") =
        way(WayStore.stageWayId(routeId, index), WaySource.Pilgrimage(routeId = routeId, stageIndex = index))

    private fun saveInOrder(vararg ways: Way) = ways.forEach {
        store.save(it)
        clockMillis += 1_000
    }

    private fun giveMedia(id: String, bytes: Int = 1) {
        File(folder.root, "Ways/$id/media/audio/1.m4a").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(bytes))
    }

    private fun rows(vm: WaysListViewModel) = (vm.state.value as WaysListUiState.Loaded).rows

    // iOS `WaysListModelTests`.

    @Test
    fun `listable drops pilgrimage stages and nothing else`() {
        val shared = share("9mYhRL7GWx")
        val own = own()
        val stages = (0 until 4).map { stage(it, routeId = "kumano-kodo-nakahechi") }

        assertEquals(listOf(shared.id, own.id), WaysListModel.listable(listOf(shared) + stages + own).map { it.id })
    }

    @Test
    fun `the Data card row counts and sizes the Ways it is given`() {
        assertEquals("1 way · 2.3 MB", WaysListModel.rowDetail(resources, WaysTotals(1, 2_340_000)))
        assertEquals("3 ways · 12.0 MB", WaysListModel.rowDetail(resources, WaysTotals(3, 12_000_000)))
        assertEquals("0 ways · 0.0 MB", WaysListModel.rowDetail(resources, WaysTotals(0, 0)))
    }

    @Test
    fun `sizes read in decimal megabytes with a point whatever the phone's locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("2 ways · 1.5 MB", WaysListModel.rowDetail(resources, WaysTotals(2, 1_500_000)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** iOS `testTheFooterNamesTheRouteAndItsStagesOnlyWhenThereAreAny`. */
    @Test
    fun `the footer names the route and its stages only when there are any`() {
        assertEquals(
            "the Nakahechi (Central Route) keeps its 4 stages on its route page",
            WaysListModel.packageFooter(resources, routeName = "Nakahechi (Central Route)", stageCount = 4),
        )
        assertEquals(
            "the Kohechi keeps its 1 stage on its route page",
            WaysListModel.packageFooter(resources, routeName = "Kohechi", stageCount = 1),
        )
        assertNull(WaysListModel.packageFooter(resources, routeName = "Nakahechi (Central Route)", stageCount = 0))
        assertNull(
            "stages with no installed route to name — a Replace cut short — say nothing",
            WaysListModel.packageFooter(resources, routeName = null, stageCount = 4),
        )
    }

    // iOS `PilgrimageStageWalkTests.swift`: the Ways list case (pilgrimage-stage spec P2 §Test inventory).

    /**
     * iOS `testTheWaysListNeitherShowsAnInstalledStageNorTakesItWithTheRest`.
     * Both are in the store, which `list()` no longer shows for the stage,
     * stepped over before its `way.json` is read (P2 A-11), so the store's
     * hold on it is read through the stage listing (C-9).
     */
    @Test
    fun `the Ways list neither shows an installed stage nor takes it with the rest`() {
        val stage = stage(0, routeId = "camino-frances")
        val shared = way(
            id = "share:9mYhRL7GWx",
            source = WaySource.Share("9mYhRL7GWx", "https://walk.pilgrimapp.org/9mYhRL7GWx"),
        ).copy(title = "Rúa do Franco → Obradoiro")
        store.save(stage)
        store.save(shared)
        assertEquals("both are in the store", listOf(stage.id), store.stageWayIds())
        assertEquals("both are in the store", listOf(shared.id), store.list().map { it.id })

        val listed = WaysListModel.listable(store.list())
        assertEquals("only the shared walk is offered", listOf("share:9mYhRL7GWx"), listed.map { it.id })

        listed.forEach { store.delete(it.id) }
        assertNotNull(
            "delete-all left the package's stage where the route screen can still find it",
            store.load(WayStore.stageWayId("camino-frances", 0)),
        )
        assertNull(store.load("share:9mYhRL7GWx"))
    }

    // The package footer (P2 §11).

    private fun footer(vm: WaysListViewModel): String? {
        val loaded = vm.state.value as WaysListUiState.Loaded
        return WaysListModel.packageFooter(resources, loaded.packageRouteName, loaded.packageStageCount)
    }

    /**
     * iOS's `all.count - ways.count`: every package-owned Way in the store,
     * whichever route it came from, named by the one installed (pilgrim-ios
     * #120 item 6, matched as shipped).
     */
    @Test
    fun `the footer counts every route's stages under the installed route's name`() = runTest(dispatcher) {
        saveInOrder(share("9mYhRL7GWx"), stage(0), stage(1), stage(2), stage(0, routeId = "camino-frances"), own())

        val vm = list(installedRouteName = { "Nakahechi (Central Route)" })

        assertEquals("the stages never list", 2, rows(vm).size)
        assertEquals("the Nakahechi (Central Route) keeps its 4 stages on its route page", footer(vm))
    }

    @Test
    fun `no footer with no installed route to name the stages, and none with no stages`() = runTest(dispatcher) {
        saveInOrder(stage(0), share("9mYhRL7GWx"))
        assertNull("stages, and no route installed", footer(list()))

        store.delete(stage(0).id)
        assertNull("a route installed, and no stages", footer(list(installedRouteName = { "Kohechi" })))
    }

    @Test
    fun `the footer follows a delete, after no ways yet when the list is empty`() = runTest(dispatcher) {
        val shared = share("9mYhRL7GWx")
        saveInOrder(shared, stage(0))
        val vm = list(installedRouteName = { "Kohechi" })

        vm.delete(shared.id)

        assertEquals(emptyList<WayListRow>(), rows(vm))
        assertEquals("the Kohechi keeps its 1 stage on its route page", footer(vm))
    }

    @Test
    fun `a failed installed read names no route, and the list still loads`() = runTest(dispatcher) {
        saveInOrder(share("9mYhRL7GWx"), stage(0))

        val vm = list(installedRouteName = { throw java.io.IOException("the live-session read failed") })

        assertEquals(1, rows(vm).size)
        assertNull(footer(vm))
    }

    /**
     * The spec's own example, through the real package manager: two stages
     * walked of one route, then a Replace with another two-stage route.
     * The walked pair stays (`retireMany`), so the footer counts four, under
     * the name the installed `route.json` carries (the harness's re-slugged
     * copy keeps the first route's name), never the catalog entry's
     * "Camino del Norte".
     */
    @Test
    fun `after a Replace the footer counts the walked stages it kept, named by the installed route file`() = runTest(dispatcher) {
        val packages = PilgrimagePackageHarness(File(folder.root, "packages"))
        try {
            val manager = packages.makeManager()
            manager.download(packages.entry, PilgrimagePackageHarness.RELEASE).awaitBlocking()
            (0..1).forEach { index ->
                packages.wayStore.link(
                    UUID.randomUUID().toString(),
                    WayStore.stageWayId(PilgrimagePackageHarness.ROUTE_ID, index),
                    arrival = null,
                )
            }
            packages.stubNorte()
            manager.replace(packages.norte, PilgrimagePackageHarness.RELEASE).awaitBlocking()

            val vm = list(
                installedRouteName = { runBlocking { manager.installed() }?.route?.name },
                store = packages.wayStore,
            )

            assertEquals(PilgrimagePackageHarness.NORTE_ID, runBlocking { manager.installed() }?.routeId)
            assertEquals("the Camino de Santiago (Francés) keeps its 4 stages on its route page", footer(vm))
        } finally {
            packages.close()
        }
    }

    // The list.

    @Test
    fun `an empty store reads no ways yet`() = runTest(dispatcher) {
        assertEquals(WaysListUiState.Loaded(emptyList()), list().state.value)
    }

    @Test
    fun `own and shared Ways are listed newest acceptance first, stages never`() = runTest(dispatcher) {
        val older = share("Older12345")
        val own = own()
        val newer = share("Newer12345")
        saveInOrder(older, stage(0), own, newer)

        assertEquals(listOf(newer.id, own.id, older.id), rows(list()).map { it.wayId })
    }

    @Test
    fun `each row reads its medium date in the phone's zone and its size`() = runTest(dispatcher) {
        val shared = share("Qoi4YmPHLN", voices = 1)
        store.save(shared)
        giveMedia(shared.id, bytes = 2_340_000)

        val row = rows(list()).single()

        assertEquals("Jul 31, 2026", row.date)
        assertEquals(store.diskUsage(shared.id), row.bytes)
        assertEquals("Jul 31, 2026 · 2.3 MB", WaysListModel.detail(resources, row))
    }

    // Matched as shipped (pilgrim-ios #109): keyed on the media folder, not the expiry.
    @Test
    fun `a Way with voices and nothing in its media folder reads voices returned to the trail`() = runTest(dispatcher) {
        val gathered = share("Gathered12", voices = 1)
        val never = share("NeverGot12", voices = 2)
        val quiet = share("QuietWay12")
        saveInOrder(gathered, never, quiet)
        giveMedia(gathered.id)

        val details = rows(list()).associate { it.wayId to WaysListModel.detail(resources, it) }

        assertEquals("Jul 31, 2026 · 0.0 MB", details[gathered.id])
        assertEquals("Jul 31, 2026 · voices returned to the trail", details[never.id])
        assertEquals("a quiet way keeps its size", "Jul 31, 2026 · 0.0 MB", details[quiet.id])
    }

    @Test
    fun `each load sweeps first and cancels what it swept`() = runTest(dispatcher) {
        val expired = share("Expired123", expires = now.minusSeconds(60))
        val live = share("LiveShare1", expires = now.plusSeconds(86_400))
        saveInOrder(expired, live)

        val vm = list()

        assertEquals(listOf(live.id), rows(vm).map { it.wayId })
        assertEquals(listOf(expired.id), cancelled)
        assertNull(store.load(expired.id))
    }

    // iOS's `delete(id:)`: the download first, then the folder and every link.
    @Test
    fun `a swipe deletes at once, cancelling its downloads, then its folder and links, and reloads`() = runTest(dispatcher) {
        val shared = share("Qoi4YmPHLN", voices = 1)
        val other = share("Other12345")
        saveInOrder(shared, other)
        val walk = UUID.randomUUID().toString()
        store.link(walk, shared.id, arrival = null)
        val replyRecording = File(folder.root, "files/recordings/$walk/reply.wav").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        store.setReply(shared.id, originN = 1, relativePath = "recordings/$walk/reply.wav")
        val vm = list()

        vm.delete(shared.id)

        assertEquals(listOf(shared.id), cancelled)
        assertNull(store.load(shared.id))
        assertNull("every link to it goes", store.wayLink(walk))
        assertTrue("the walk's reply recording stays", replyRecording.exists())
        assertEquals(listOf(other.id), rows(vm).map { it.wayId })
    }

    @Test
    fun `Delete all takes every Way the list shows, and never a stage`() = runTest(dispatcher) {
        val shared = share("Qoi4YmPHLN")
        val own = own()
        val stage = stage(2)
        saveInOrder(shared, own, stage)
        val vm = list()

        vm.deleteAll()

        assertEquals(emptyList<WayListRow>(), rows(vm))
        assertEquals(setOf(shared.id, own.id), cancelled.toSet())
        assertNotNull(store.load(stage.id))
    }

    // Spec correction 14, an R6 addition.
    @Test
    fun `the list leaves once a walk starts or one waits for its Honor step`() = runTest(dispatcher) {
        val vm = list()
        val collector = backgroundScope.launch { vm.hidden.collect {} }
        assertFalse(vm.hidden.value)

        shown.value = false

        assertTrue(vm.hidden.value)
        collector.cancel()
    }

    @Test
    fun `availability needs the flag, no walk on, and no Honor step pending`() = runTest(dispatcher) {
        assertFalse(WaysAvailability(flowOf(false), shownAtFirst = false).shown.first())
        assertTrue(WaysAvailability(flowOf(true), shownAtFirst = true).shown.first())
    }

    // The Data card row (S4 §2).

    @Test
    fun `the Data card counts the Ways the list shows, over their whole folders, without sweeping`() = runTest(dispatcher) {
        val expired = share("Expired123", expires = now.minusSeconds(60), voices = 1)
        saveInOrder(expired, own(), stage(0))
        giveMedia(expired.id, bytes = 1_000_000)
        val row = row()

        assertNull("blank until counted, as iOS's starts empty", row.totals.value)
        row.refresh()

        val totals = row.totals.value!!
        assertEquals("the card doesn't sweep (pilgrim-ios #115, matched)", 2, totals.count)
        assertEquals(store.diskUsage(expired.id) + store.diskUsage(store.list().first { it.source is WaySource.OwnWalk }.id), totals.bytes)
        assertTrue(cancelled.isEmpty())
        assertNotNull(store.load(expired.id))
    }

    @Test
    fun `the row is shown while availability allows, with no Ways at all included`() = runTest(dispatcher) {
        val row = row()
        val collector = backgroundScope.launch { row.shown.collect {} }

        row.refresh()

        assertTrue(row.shown.value)
        assertEquals(WaysTotals(0, 0), row.totals.value)
        shown.value = false
        assertFalse(row.shown.value)
        collector.cancel()
    }

    // S4 §2.1: iOS's row is unconditional, so it is there before Room's first answer.
    @Test
    fun `the row is there on its first frame, before availability has answered`() = runTest(dispatcher) {
        val silent = MutableSharedFlow<Boolean>()

        assertTrue(WaysRowViewModel(store, WaysAvailability(silent, shownAtFirst = true), dispatcher).also { viewModels += it }.shown.value)
        assertFalse(
            "with the flag off it never shows",
            WaysRowViewModel(store, WaysAvailability(silent, shownAtFirst = false), dispatcher).also { viewModels += it }.shown.value,
        )
    }

    // iOS picks the word by `count == 1` (`WaysListView.swift:18-20@7c200bf`), in every locale.
    @Test
    @Config(qualifiers = "fr")
    fun `French plural rules don't make zero singular`() {
        assertEquals("0 ways · 0.0 MB", WaysListModel.rowDetail(resources, WaysTotals(0, 0)))
        assertEquals("1 way · 0.0 MB", WaysListModel.rowDetail(resources, WaysTotals(1, 0)))
        assertEquals("2 ways · 0.0 MB", WaysListModel.rowDetail(resources, WaysTotals(2, 0)))
    }

    @Test
    @Config(qualifiers = "ja")
    fun `Japanese plural rules don't make one plural`() {
        assertEquals("1 way · 0.0 MB", WaysListModel.rowDetail(resources, WaysTotals(1, 0)))
        assertEquals("3 ways · 0.0 MB", WaysListModel.rowDetail(resources, WaysTotals(3, 0)))
    }
}
