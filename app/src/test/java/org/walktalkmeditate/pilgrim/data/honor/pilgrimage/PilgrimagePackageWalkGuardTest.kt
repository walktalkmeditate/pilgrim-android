// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.app.Application
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Deferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.NORTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.RELEASE
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.ROUTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.assertRefused
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.awaitBlocking
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager.Phase

/**
 * The package guard (pilgrimage-stage spec P2 §2, §10 item 7, A-1, C-4):
 * iOS's clause, the walk screen up, and the three Android adds, each
 * refusing every operation on entry and every commit before it writes.
 * Live rows that are the only reason run the pending Honor steps once,
 * then the guard reads again. An operation runs its post-commit steps
 * with nothing else between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimagePackageWalkGuardTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: PilgrimagePackageHarness

    @Before
    fun setUp() {
        h = PilgrimagePackageHarness(folder.root)
    }

    @After
    fun tearDown() {
        h.close()
    }

    private enum class Clause { WALK_SCREEN_UP, WALK_ACTIVE, LIVE_ROWS, BEGIN_IN_FLIGHT }

    /** Raised so that the pending steps, if the guard runs them, fail again. */
    private fun FakeWalkSignals.raise(clause: Clause) {
        when (clause) {
            Clause.WALK_SCREEN_UP -> screenUp = true
            Clause.WALK_ACTIVE -> active = true
            Clause.LIVE_ROWS -> {
                finalizeClears = false
                liveWayIds = setOf(LIVE_WAY_ID)
            }
            Clause.BEGIN_IN_FLIGHT -> beginInFlight = true
        }
    }

    private fun FakeWalkSignals.lower() {
        screenUp = false
        active = false
        beginInFlight = false
        liveWayIds = emptySet()
        finalizeClears = true
        finalizeRuns.set(0)
    }

    private fun finalizeRunsFor(clause: Clause) = if (clause == Clause.LIVE_ROWS) 1 else 0

    // ---- On entry ---------------------------------------------------------------

    @Test
    fun `a download is refused on entry for each clause, before anything is asked for`() {
        Clause.entries.forEach { clause ->
            h.signals.lower()
            h.signals.raise(clause)
            val manager = h.makeManager()

            assertRefused(PilgrimageError.WALK_IN_PROGRESS, "$clause") { manager.download(h.entry, RELEASE).awaitBlocking() }

            assertEquals("$clause: the phase isn't touched", Phase.Idle, manager.phase.value)
            assertEquals("$clause", 0, h.server.requestCount)
            assertEquals("$clause", finalizeRunsFor(clause), h.signals.finalizeRuns.get())
        }
    }

    @Test
    fun `a Replace, an Update and a Remove are each refused on entry for each clause, and nothing moves`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubNorte()
        val requestsBefore = h.server.requestCount
        val operations = mapOf<String, () -> Deferred<Unit>>(
            "replace" to { manager.replace(h.norte, RELEASE) },
            "update" to { manager.update(h.entry, "v1.8.0") },
            "remove" to { manager.remove(ROUTE_ID) },
        )
        Clause.entries.forEach { clause ->
            operations.forEach { (name, operation) ->
                h.signals.lower()
                h.signals.raise(clause)

                assertRefused(PilgrimageError.WALK_IN_PROGRESS, "$name, $clause") { operation().awaitBlocking() }

                assertEquals("$name, $clause", finalizeRunsFor(clause), h.signals.finalizeRuns.get())
                assertEquals("$name, $clause: nothing moved", RELEASE, installedRelease(manager))
                assertFalse("$name, $clause: no marker", h.wayStore.replacingFile.exists())
                assertEquals("$name, $clause", Phase.Idle, manager.phase.value)
            }
        }
        assertEquals("no refused operation asked for a file", requestsBefore, h.server.requestCount)
    }

    // ---- Before the commit --------------------------------------------------------------

    @Test
    fun `a download is refused before its commit for each clause, and nothing reaches the store`() {
        Clause.entries.forEach { clause ->
            h.signals.lower()
            val secondStage = h.hold("stage-01.json")
            val manager = h.makeManager()
            val download = manager.download(h.entry, RELEASE)
            secondStage.awaitArrival()
            h.signals.raise(clause)
            secondStage.release()

            assertRefused(PilgrimageError.WALK_IN_PROGRESS, "$clause") { download.awaitBlocking() }

            assertEquals("$clause", Phase.Failed(PilgrimageError.WALK_IN_PROGRESS), manager.phase.value)
            assertEquals("$clause", emptyList<String>(), h.wayStore.stageWayIds())
            assertNull("$clause", manager.installedBlocking())
            assertEquals("$clause", finalizeRunsFor(clause), h.signals.finalizeRuns.get())
        }
    }

    @Test
    fun `an Update is refused before its commit for each clause, and the old install stays`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubWholePackage("v1.8.0")
        Clause.entries.forEach { clause ->
            h.signals.lower()
            val secondStage = h.hold("stage-01.json", "v1.8.0")
            val update = manager.update(h.entry, "v1.8.0")
            secondStage.awaitArrival()
            h.signals.raise(clause)
            secondStage.release()

            assertRefused(PilgrimageError.WALK_IN_PROGRESS, "$clause") { update.awaitBlocking() }

            assertEquals("$clause", RELEASE, installedRelease(manager))
            assertEquals("$clause", 2, h.wayStore.stageWayIds().size)
            assertEquals("$clause", finalizeRunsFor(clause), h.signals.finalizeRuns.get())
        }
    }

    @Test
    fun `a Replace is refused before its commit for each clause, keeping the first route and no marker`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubNorte()
        Clause.entries.forEach { clause ->
            h.signals.lower()
            val secondStage = h.hold("stage-01.json", routeId = NORTE_ID)
            val replace = manager.replace(h.norte, RELEASE)
            secondStage.awaitArrival()
            h.signals.raise(clause)
            secondStage.release()

            assertRefused(PilgrimageError.WALK_IN_PROGRESS, "$clause") { replace.awaitBlocking() }

            assertEquals("$clause", ROUTE_ID, manager.installedBlocking()?.routeId)
            assertEquals("$clause", setOf("pilgrimage:camino-frances:0", "pilgrimage:camino-frances:1"), h.wayStore.stageWayIds().toSet())
            assertFalse("$clause", h.wayStore.replacingFile.exists())
            assertEquals("$clause", finalizeRunsFor(clause), h.signals.finalizeRuns.get())
        }
    }

    // ---- The pending steps, run once ---------------------------------------------------------

    @Test
    fun `live rows a pending step left run the pending steps once, and then a download goes ahead`() {
        h.signals.liveWayIds = setOf(LIVE_WAY_ID)
        val manager = h.makeManager()

        manager.download(h.entry, RELEASE).awaitBlocking()

        assertEquals(1, h.signals.finalizeRuns.get())
        assertEquals(ROUTE_ID, manager.installedBlocking()?.routeId)
    }

    @Test
    fun `live rows a pending step left let a Replace, an Update and a Remove go ahead after one run`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubOneStagePackage("v1.8.0")
        h.stubNorte()

        h.signals.lower()
        h.signals.liveWayIds = setOf(LIVE_WAY_ID)
        manager.update(h.entryWithOneStage, "v1.8.0").awaitBlocking()
        assertEquals("update", 1, h.signals.finalizeRuns.get())
        assertEquals("v1.8.0", installedRelease(manager))

        h.signals.lower()
        h.signals.liveWayIds = setOf(LIVE_WAY_ID)
        manager.replace(h.norte, RELEASE).awaitBlocking()
        assertEquals("replace", 1, h.signals.finalizeRuns.get())
        assertEquals(NORTE_ID, manager.installedBlocking()?.routeId)

        h.signals.lower()
        h.signals.liveWayIds = setOf(LIVE_WAY_ID)
        manager.remove(NORTE_ID).awaitBlocking()
        assertEquals("remove", 1, h.signals.finalizeRuns.get())
        assertNull(manager.installedBlocking())
    }

    @Test
    fun `live rows the pending steps can't clear refuse after their one run`() {
        h.signals.raise(Clause.LIVE_ROWS)
        val manager = h.makeManager()

        assertRefused(PilgrimageError.WALK_IN_PROGRESS) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertEquals(1, h.signals.finalizeRuns.get())
    }

    @Test
    fun `any other clause refuses at once, never running the pending steps`() {
        listOf(Clause.WALK_SCREEN_UP, Clause.WALK_ACTIVE, Clause.BEGIN_IN_FLIGHT).forEach { clause ->
            h.signals.lower()
            h.signals.raise(clause)
            h.signals.liveWayIds = setOf(LIVE_WAY_ID)
            val manager = h.makeManager()

            assertRefused(PilgrimageError.WALK_IN_PROGRESS, "$clause") { manager.download(h.entry, RELEASE).awaitBlocking() }

            assertEquals("$clause", 0, h.signals.finalizeRuns.get())
        }
    }

    // ---- Held through the post-commit steps (C-4) ----------------------------------------------

    /**
     * iOS runs Update's tail sweep, tiles call and reconcile in one main-actor
     * run after the commit. A Remove asked from inside that run can't run
     * while the run goes on, however long it is given; it goes ahead once
     * the reconcile has landed, as it would on iOS right after, and isn't
     * refused, since the download has ended.
     */
    @Test
    fun `an operation asked during an Update's post-commit steps runs only after the last of them`() {
        val tiles = FakeTiles()
        val manager = h.makeManager(tiles)
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.seedTwoStageLedger()
        h.stubOneStagePackage("v1.8.0")
        val askedMeanwhile = CopyOnWriteArrayList<Deferred<Unit>>()
        val ranMeanwhile = CopyOnWriteArrayList<Boolean>()
        val ledgerWhenRemoving = CopyOnWriteArrayList<PilgrimageLedger?>()
        tiles.onRemoveRegions = { _, _ ->
            val remove = manager.remove(ROUTE_ID)
            askedMeanwhile += remove
            Thread.sleep(GRACE_MILLIS)
            ranMeanwhile += remove.isCompleted || tiles.removed.isNotEmpty()
        }
        tiles.onRemove = { ledgerWhenRemoving += h.ledgers.load(ROUTE_ID) }

        manager.update(h.entryWithOneStage, "v1.8.0").awaitBlocking()
        askedMeanwhile.single().awaitBlocking()

        assertEquals("the Remove waited out the tiles step", listOf(false), ranMeanwhile.toList())
        val seen = requireNotNull(ledgerWhenRemoving.single()) { "no ledger when the Remove ran" }
        assertEquals("the reconcile had landed", setOf("0"), seen.stages.keys)
        assertEquals(true, seen.redrawNoticePending)
        assertEquals(listOf(ROUTE_ID to 1), tiles.regionsRemoved.toList())
        assertNull(manager.installedBlocking())
    }

    /** Replace's removal of the route it lets go, the same: a Remove asked meanwhile finds the swap finished. */
    @Test
    fun `an operation asked during a Replace's removal of the old route runs once the swap is done`() {
        val tiles = FakeTiles()
        val manager = h.makeManager(tiles)
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubNorte()
        val askedMeanwhile = CopyOnWriteArrayList<Deferred<Unit>>()
        val ranMeanwhile = CopyOnWriteArrayList<Boolean>()
        val markerWhenRemoving = CopyOnWriteArrayList<Boolean>()
        tiles.onRemove = { routeId ->
            if (routeId == ROUTE_ID) {
                val remove = manager.remove(NORTE_ID)
                askedMeanwhile += remove
                Thread.sleep(GRACE_MILLIS)
                ranMeanwhile += remove.isCompleted
            } else {
                markerWhenRemoving += h.wayStore.replacingFile.exists()
            }
        }

        manager.replace(h.norte, RELEASE).awaitBlocking()
        askedMeanwhile.single().awaitBlocking()

        assertEquals(listOf(false), ranMeanwhile.toList())
        assertEquals("the swap had cleared its marker", listOf(false), markerWhenRemoving.toList())
        assertEquals(listOf(ROUTE_ID, NORTE_ID), tiles.removed.toList())
        assertNull(manager.installedBlocking())
    }

    private fun installedRelease(manager: PilgrimagePackageManager): String? = manager.installedBlocking()?.release

    private companion object {
        const val LIVE_WAY_ID = "share:abcdefghij"

        /** Time an operation asked meanwhile is given to run, if the hold let it: a correct manager never lets it. */
        const val GRACE_MILLIS = 200L
    }
}
