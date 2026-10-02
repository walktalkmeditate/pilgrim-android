// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.app.Application
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.NORTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.RELEASE
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.ROUTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.assertRefused
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.awaitBlocking
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.fixture
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager.Phase
import org.walktalkmeditate.pilgrim.honor.HonorWalkRecord
import org.walktalkmeditate.pilgrim.ui.walk.summary.HonorSummaryModel

/**
 * Port of iOS `PilgrimagePackageManagerTests+Lifecycle.swift@7c200bf` (8,
 * names kept): what an install and a removal leave, an interrupted
 * Replace, and the refusals while a download is in flight. The walk index
 * test reads Android's link files, which a Remove must not rewrite, where
 * iOS reads its one `index.json`.
 *
 * Then Replace's busy race as iOS ships it (owner decision 4, pilgrim-ios
 * #119), the kill points of P2 §7 a test can stand in for, and the launch
 * work (P2 §12, A-6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimagePackageManagerLifecycleTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: PilgrimagePackageHarness

    private val marker: File get() = h.wayStore.replacingFile

    @Before
    fun setUp() {
        h = PilgrimagePackageHarness(folder.root)
    }

    @After
    fun tearDown() {
        h.close()
    }

    // ---- iOS's tests: what Remove must leave ---------------------------------------

    /**
     * Remove promises "Walks in your journal stay": a walked stage keeps its
     * folder, its reply and its link. iOS reads the summary model's stage
     * fields; Android's arrive with U40, so the Way and its reply are read
     * here, and the summary's title through the model as it stands.
     */
    @Test
    fun `a removed route's walked stage keeps its link, its reply and its stage identity`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        val walk = UUID.randomUUID().toString()
        val stageId = WayStore.stageWayId(ROUTE_ID, 0)
        h.wayStore.link(walk, stageId, arrival = null)
        h.wayStore.setReply(stageId, originN = STAGE_REFLECTION_ORIGIN, relativePath = "Recordings/reply.m4a")

        manager.remove(ROUTE_ID).awaitBlocking()

        assertEquals("the walk still names its stage", stageId, h.wayStore.wayLink(walk)?.wayId)
        val way = requireNotNull(h.wayStore.way(walk)) { "the walked stage went" }
        assertTrue("not the shared-walk lexicon", way.isPilgrimageStage)
        val summary = HonorSummaryModel.summaryState(HonorWalkRecord(way, arrival = null, replies = h.wayStore.replies(stageId)))
        assertEquals("not 'a way that has been removed'", way.title, summary.data.wayTitle)
        assertEquals("Recordings/reply.m4a", h.wayStore.replies(stageId)[STAGE_REFLECTION_ORIGIN])
        assertNull("the stage nobody walked goes whole", h.wayStore.load(WayStore.stageWayId(ROUTE_ID, 1)))
        assertNull("what is kept never reads as installed", manager.installedBlocking())
    }

    /** iOS's `index.json` byte for byte; here, no link file is rewritten, bytes or time. */
    @Test
    fun `removing a route reads the walk links without rewriting any`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        val walk = UUID.randomUUID().toString()
        h.wayStore.link(walk, WayStore.stageWayId(ROUTE_ID, 0), arrival = null)
        val linkFile = File(h.waysDir, "links/$walk.json")
        val bytes = linkFile.readBytes()
        assertTrue(linkFile.setLastModified(LONG_AGO_MILLIS))

        manager.remove(ROUTE_ID).awaitBlocking()

        assertTrue("the link was read, never rewritten", bytes.contentEquals(linkFile.readBytes()))
        assertEquals(LONG_AGO_MILLIS, linkFile.lastModified())
        assertNotNull(h.wayStore.wayLink(walk))
    }

    // ---- iOS's tests: an interrupted Replace ----------------------------------------

    /** Two plain downloads leave the state a kill inside a Replace's two halves leaves; the marker names the route let go. */
    @Test
    fun `a kill between a Replace's two halves is finished on the next read`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubNorte()
        manager.download(h.norte, RELEASE).awaitBlocking()
        marker.writeText(ROUTE_ID)

        val installed = requireNotNull(manager.installedBlocking()) { "nothing installed" }

        assertEquals("the route the pilgrim chose survives", NORTE_ID, installed.routeId)
        assertNull("the abandoned route's stages are taken", h.wayStore.load("pilgrimage:camino-frances:0"))
        assertFalse(h.wayStore.routeFile(ROUTE_ID)!!.exists())
        assertFalse("the marker is cleared once the swap it described is finished", marker.exists())
    }

    @Test
    fun `a Replace that finishes leaves no marker behind`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubNorte()

        manager.replace(h.norte, RELEASE).awaitBlocking()

        assertFalse(marker.exists())
        assertEquals(NORTE_ID, manager.installedBlocking()?.routeId)
    }

    // ---- iOS's tests: refusals while a download is in flight ----------------------------

    /** A Remove between two stages would be undone by the commit after it: the same refusal a second download gets. */
    @Test
    fun `remove is refused while a download is in flight`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubWholePackage("v1.8.0")
        val routeFile = h.hold("route.json", "v1.8.0")
        val update = manager.update(h.entry, "v1.8.0")
        routeFile.awaitArrival()

        assertRefused(PilgrimageError.INCOMPLETE) { manager.remove(ROUTE_ID).awaitBlocking() }

        routeFile.release()
        update.awaitBlocking()
        assertEquals("the update the Remove could not interrupt", "v1.8.0", manager.installedBlocking()?.release)
        assertNotNull(h.wayStore.load("pilgrimage:camino-frances:0"))
    }

    /** The commit would rewrite the Way a walk begun meanwhile is on, so it is refused at the last moment. */
    @Test
    fun `a walk begun while the stages stream aborts the commit`() {
        val secondStage = h.hold("stage-01.json")
        val manager = h.makeManager()
        val download = manager.download(h.entry, RELEASE)
        secondStage.awaitArrival()
        h.signals.screenUp = true
        secondStage.release()

        assertRefused(PilgrimageError.WALK_IN_PROGRESS) { download.awaitBlocking() }

        assertEquals("nothing of the abandoned package reached the store", emptyList<String>(), h.wayStore.stageWayIds())
        assertNull(manager.installedBlocking())
        assertEquals(Phase.Failed(PilgrimageError.WALK_IN_PROGRESS), manager.phase.value)
    }

    // ---- iOS's tests: a stage that disagrees with its route file ---------------------------

    /** A stage counting other than its route would have the morning card read "stage 1 of 3" against a screen of two. */
    @Test
    fun `a stage that counts a different number of stages than its route is refused`() {
        h.stub("stage-00.json", h.patchedStageZero(count = 3))
        val manager = h.makeManager()

        assertRefused(PilgrimageError.NOT_WALKABLE) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertNull(manager.installedBlocking())
        assertEquals(emptyList<String>(), h.wayStore.stageWayIds())
    }

    /** The ledger reconciles by the route file's names; a stage under another would be dropped at the first Update. */
    @Test
    fun `a stage carrying a name its route file never used is refused`() {
        h.stub("stage-00.json", h.patchedStageZero(name = "Somewhere else entirely"))
        val manager = h.makeManager()

        assertRefused(PilgrimageError.NOT_WALKABLE) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertNull(manager.installedBlocking())
        assertEquals(emptyList<String>(), h.wayStore.stageWayIds())
    }

    // ---- Replace's busy race, as shipped (owner decision 4) -----------------------------------

    /**
     * A second Replace while the first downloads finds the first's download
     * in flight: it writes the marker again, is refused as busy, and
     * deletes the marker, so the first runs on with none (P2 §4, D-3,
     * pilgrim-ios #119). The first one's progress stays on screen.
     */
    @Test
    fun `a Replace refused as busy deletes the marker the Replace in flight wrote`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubNorte()
        val norteRoute = h.hold("route.json", routeId = NORTE_ID)
        val first = manager.replace(h.norte, RELEASE)
        norteRoute.awaitArrival()
        assertEquals("the first Replace names the route it lets go", ROUTE_ID, marker.readText())

        assertRefused(PilgrimageError.INCOMPLETE) { manager.replace(h.entry("camino-ingles"), RELEASE).awaitBlocking() }

        assertFalse("the refused Replace leaves no marker", marker.exists())
        assertEquals(Phase.Downloading(done = 0, total = 3), manager.phase.value)
        norteRoute.release()
        first.awaitBlocking()
        assertEquals(NORTE_ID, manager.installedBlocking()?.routeId)
        assertFalse(marker.exists())
    }

    // ---- What a kill leaves (P2 §7) -----------------------------------------------------------

    /** Killed after the marker, before or while the new route downloaded: one package, so the marker just goes. */
    @Test
    fun `a Replace killed before its new route landed leaves the route already there`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        marker.writeText(ROUTE_ID)

        assertEquals(ROUTE_ID, manager.installedBlocking()?.routeId)

        assertFalse(marker.exists())
        assertNotNull(h.wayStore.load("pilgrimage:camino-frances:0"))
    }

    /** Killed between the old route's `route.json` and its `release.txt`: one valid package, and a harmless `release.txt`. */
    @Test
    fun `a Replace killed mid removal leaves the new route and the old release txt`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubNorte()
        manager.download(h.norte, RELEASE).awaitBlocking()
        marker.writeText(ROUTE_ID)
        assertTrue(h.wayStore.routeFile(ROUTE_ID)!!.delete())

        assertEquals(NORTE_ID, manager.installedBlocking()?.routeId)

        assertFalse(marker.exists())
        assertTrue(h.wayStore.releaseFile(ROUTE_ID)!!.exists())
    }

    /**
     * Killed inside a first install's commit: stages with no `route.json`,
     * which nothing installed names (pilgrim-ios #119, no repair, owner
     * decision 3). A Remove of a route not installed reaches 200 stages.
     */
    @Test
    fun `a first install killed mid commit leaves stages nothing names, which a Remove reaches`() {
        val manager = h.makeManager()
        h.wayStore.save(PilgrimageWayImporter.way(from = fixture("stage-00.json"), routeId = ROUTE_ID, stageIndex = 0))

        assertNull(manager.installedBlocking())

        manager.remove(ROUTE_ID).awaitBlocking()
        assertEquals(emptyList<String>(), h.wayStore.stageWayIds())
    }

    // ---- The launch (P2 §12, A-6) ----------------------------------------------------------------

    @Test
    fun `the launch finishes an interrupted Replace`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubNorte()
        manager.download(h.norte, RELEASE).awaitBlocking()
        marker.writeText(ROUTE_ID)

        runBlocking { manager.runAtLaunch() }

        assertFalse(marker.exists())
        assertEquals(listOf(NORTE_ID), h.wayStore.pilgrimageRouteIds().filter { h.wayStore.routeFile(it)!!.exists() })
        assertNull(h.wayStore.load("pilgrimage:camino-frances:0"))
    }

    /** A download begun in the launch's first seconds can be younger than the sweep. */
    @Test
    fun `the launch sweeps every temp set a kill left, sparing the one in flight`() {
        val stale = listOf(File(h.tempRoot, "pilgrimage-killed"), File(h.tempRoot, "pilgrimage-killed-too"))
        stale.forEach { File(it, "route.json").apply { parentFile!!.mkdirs() }.writeBytes(fixture("route.json")) }
        val firstStage = h.hold("stage-00.json")
        val manager = h.makeManager()
        val download = manager.download(h.entry, RELEASE)
        firstStage.awaitArrival()
        val inFlight = h.tempRoot.listFiles().orEmpty().single { it !in stale }

        runBlocking { manager.runAtLaunch() }

        assertEquals(listOf(inFlight), h.tempRoot.listFiles().orEmpty().toList())
        firstStage.release()
        download.awaitBlocking()
        assertEquals(ROUTE_ID, manager.installedBlocking()?.routeId)
        assertEquals(emptyList<File>(), h.tempRoot.listFiles().orEmpty().toList())
    }

    /** iOS's rule: while a download is in flight, the swap that wrote the marker is the one to clear it. */
    @Test
    fun `with a download in flight, the launch leaves a Replace's marker to the swap that wrote it`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubNorte()
        val norteRoute = h.hold("route.json", routeId = NORTE_ID)
        val replace = manager.replace(h.norte, RELEASE)
        norteRoute.awaitArrival()

        runBlocking { manager.runAtLaunch() }

        assertEquals(ROUTE_ID, marker.readText())
        norteRoute.release()
        replace.awaitBlocking()
        assertFalse("the Replace cleared it", marker.exists())
        assertEquals(NORTE_ID, manager.installedBlocking()?.routeId)
    }

    private companion object {
        /** The arrival reflection's reply key (iOS `HonorPersistence.stageReflectionOrigin`; U36 names it on Android). */
        const val STAGE_REFLECTION_ORIGIN = -1
        const val LONG_AGO_MILLIS = 1_000_000_000_000L
    }
}
