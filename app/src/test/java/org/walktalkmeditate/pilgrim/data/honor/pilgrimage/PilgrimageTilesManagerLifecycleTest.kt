// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.app.Application
import android.os.Looper
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesFixtures.tileStage
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesFixtures.tileStages
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesHarness.Companion.failure
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.InstalledRoute
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Phase
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Status

/**
 * Port of iOS `PilgrimageTilesManagerTests+Lifecycle.swift@7c200bf`, names
 * kept (spec D §C2.T): all 10. `remove`, `removeRegions` and `reconcile`
 * post to the manager's thread, so each is drained before its assertions.
 *
 * Then the Android additions: AE10 from the engine, a stage index past 32
 * bits, a refusal for the walk row, and `remove` from another thread on
 * the real main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageTilesManagerLifecycleTest {

    private val h = PilgrimageTilesHarness()
    private val loader = h.loader
    private val manager = h.manager

    // ---- iOS's tests ----------------------------------------------------------

    @Test
    fun `remove clears exactly the prefix and leaves packs`() = h.run {
        loader.seedStylePacks()
        tileStages(3).forEach { loader.seed(it.id, "h") }
        loader.seed("pilgrimage:kumano-kodo-nakahechi:0", "h")
        manager.remove("camino-frances")
        h.drain()
        assertEquals(tileStages(3).map { it.id }.toSet(), loader.removedIds.toSet())
        assertNotNull(loader.regions().firstOrNull { it.id == "pilgrimage:kumano-kodo-nakahechi:0" })
        assertEquals(StylePackRequest.entries.toSet(), loader.stylePacks)
    }

    @Test
    fun `remove cancels an in flight save`() = h.run {
        loader.seedStylePacks()
        val save = manager.save("camino-frances", tileStages(2))
        h.untilPending()
        manager.remove("camino-frances")
        h.drain()
        assertEquals(PilgrimageError.INCOMPLETE, save.failure())
        assertEquals(Phase.Idle, manager.phase.value)
    }

    @Test
    fun `retired indices are removed and nothing is downloaded`() = h.run {
        tileStages(5).forEach { loader.seed(it.id, "h") }
        manager.removeRegions("camino-frances", atOrAbove = 3)
        h.drain()
        assertEquals(setOf("pilgrimage:camino-frances:3", "pilgrimage:camino-frances:4"), loader.removedIds.toSet())
        assertTrue(loader.regionRequests.isEmpty())
        assertTrue(loader.packRequests.isEmpty())
    }

    @Test
    fun `reconcile removes foreign and out of range regions and is idempotent`() = h.run {
        tileStages(3).forEach { loader.seed(it.id, "h") }
        loader.seed("pilgrimage:camino-frances:7", "h")
        loader.seed("pilgrimage:kumano-kodo-nakahechi:0", "h")
        manager.reconcile(InstalledRoute(routeId = "camino-frances", stageCount = 3))
        h.drain()
        loader.releaseRegions()
        assertEquals(setOf("pilgrimage:camino-frances:7", "pilgrimage:kumano-kodo-nakahechi:0"), loader.removedIds.toSet())
        val before = loader.removedIds.size
        manager.reconcile(InstalledRoute(routeId = "camino-frances", stageCount = 3))
        h.drain()
        loader.releaseRegions()
        assertEquals("a second run removes nothing", before, loader.removedIds.size)
    }

    /** The launch reconcile is asked while the store is still answering, so it sweeps nothing until the answer lands, and then exactly once. */
    @Test
    fun `reconcile sweeps once the store answers`() = h.run {
        loader.seed("pilgrimage:camino-frances:7", "h")
        loader.seed("pilgrimage:kumano-kodo-nakahechi:0", "h")

        manager.reconcile(InstalledRoute(routeId = "camino-frances", stageCount = 3))
        h.drain()
        assertTrue("the store has not answered yet", loader.removedIds.isEmpty())

        loader.releaseRegions()
        assertEquals(setOf("pilgrimage:camino-frances:7", "pilgrimage:kumano-kodo-nakahechi:0"), loader.removedIds.toSet())

        val before = loader.removedIds.size
        loader.releaseRegions()
        assertEquals("the sweep ran on its answer, not on every later change", before, loader.removedIds.size)
    }

    /** On a phone with saved maps the packs answer lands a round trip ahead of the regions; it says nothing about what is on disk. */
    @Test
    fun `a packs only change does not run the pending sweep`() = h.run {
        loader.seed("pilgrimage:camino-frances:7", "h")
        loader.seed("pilgrimage:kumano-kodo-nakahechi:0", "h")

        manager.reconcile(InstalledRoute(routeId = "camino-frances", stageCount = 3))
        h.drain()
        loader.firePacksChange()
        assertTrue("the packs answer does not speak for what is on disk", loader.removedIds.isEmpty())

        loader.releaseRegions()
        assertEquals(setOf("pilgrimage:camino-frances:7", "pilgrimage:kumano-kodo-nakahechi:0"), loader.removedIds.toSet())
    }

    /** D3, matched: nothing readable installed sweeps every saved map. */
    @Test
    fun `reconcile with nothing installed removes every region`() = h.run {
        tileStages(2).forEach { loader.seed(it.id, "h") }
        manager.reconcile(installed = null)
        h.drain()
        loader.releaseRegions()
        assertEquals(tileStages(2).map { it.id }.toSet(), loader.removedIds.toSet())
    }

    /** An empty store answers "no regions", which signals no change; a sweep waiting on a change would still be waiting when a save wrote its first region. */
    @Test
    fun `a reconcile from an empty launch never sweeps a later save`() = h.run {
        manager.reconcile(installed = null)
        h.drain()
        loader.releaseRegions()
        loader.seedStylePacks()
        val two = tileStages(2)
        val save = manager.save("camino-frances", two)
        h.untilPending()
        loader.completeNextRegion()
        h.untilPending()
        loader.completeNextRegion()
        save.await()
        assertTrue("the launch reconcile has no claim on a later save", loader.removedIds.isEmpty())
        assertEquals(Status.Saved(bytes = 200_000L), manager.status(two))
    }

    /** The store can answer mid-save, long after the launch that asked; the save is the newer truth about what belongs on disk. */
    @Test
    fun `a save cancels a pending launch sweep`() = h.run {
        manager.reconcile(installed = null)
        h.drain()
        loader.seedStylePacks()
        val save = manager.save("camino-frances", tileStages(2))
        h.untilPending()
        loader.completeNextRegion()
        h.untilPending()
        loader.completeNextRegion()
        save.await()

        loader.releaseRegions()
        assertTrue("the save outranks a launch sweep still waiting on the store", loader.removedIds.isEmpty())
    }

    /** A save refused at the door writes nothing, so the launch sweep it would have outranked still has its work. */
    @Test
    fun `a save refused while walking leaves the pending launch sweep`() = h.run {
        loader.seed("pilgrimage:kumano-kodo-nakahechi:0", "h")
        manager.reconcile(installed = null)
        h.drain()
        h.signals.screenUp = true
        assertEquals(PilgrimageError.WALK_IN_PROGRESS, manager.save("camino-frances", tileStages(1)).failure())

        loader.releaseRegions()
        assertEquals("the refusal did not cancel the sweep", listOf("pilgrimage:kumano-kodo-nakahechi:0"), loader.removedIds)
    }

    // ---- Android additions ----------------------------------------------------------

    @Test
    fun `a save refused for a walk row leaves the pending launch sweep`() = h.run {
        loader.seed("pilgrimage:kumano-kodo-nakahechi:0", "h")
        manager.reconcile(installed = null)
        h.drain()
        h.signals.active = true
        assertEquals(PilgrimageError.WALK_IN_PROGRESS, manager.save("camino-frances", tileStages(1)).failure())

        loader.releaseRegions()
        assertEquals(listOf("pilgrimage:kumano-kodo-nakahechi:0"), loader.removedIds)
    }

    /**
     * AE10 at the engine: an Update from 33 stages to 30 that redraws stage
     * 12 retires the three past the end and downloads nothing, so the route
     * reads 29 of 30 and the redrawn stage isn't saved.
     */
    @Test
    fun `an update's retired stages go, its redrawn stage reads unsaved, and nothing loads`() = h.run {
        val before = tileStages(33)
        loader.seedStylePacks()
        before.forEach { loader.seed(it.id, it.corridorHash) }
        assertEquals(Status.Saved(bytes = 3_300_000L), manager.status(before))

        manager.removeRegions("camino-frances", atOrAbove = 30)
        h.drain()
        val after = before.take(30).toMutableList()
        after[12] = tileStage(12, count = 30, lonOffset = 12 * 0.04 + 0.01)

        assertEquals(
            setOf("pilgrimage:camino-frances:30", "pilgrimage:camino-frances:31", "pilgrimage:camino-frances:32"),
            loader.removedIds.toSet(),
        )
        assertEquals(Status.Partial(saved = 29, of = 30), manager.status(after))
        assertFalse(manager.isStageSaved(after[12]))
        assertTrue(loader.regionRequests.isEmpty())
        assertTrue(loader.packRequests.isEmpty())
    }

    /** Swift's `Int` is 64-bit, so an index is compared whole, never narrowed; a suffix that isn't one stays for Update and goes in the sweep. */
    @Test
    fun `a retired index past 32 bits is retired, an unreadable one stays, and the sweep takes both`() = h.run {
        loader.seed("pilgrimage:camino-frances:1", "h")
        loader.seed("pilgrimage:camino-frances:4294967296", "h")
        loader.seed("pilgrimage:camino-frances:x", "h")

        manager.removeRegions("camino-frances", atOrAbove = 3)
        h.drain()
        assertEquals(listOf("pilgrimage:camino-frances:4294967296"), loader.removedIds)

        manager.reconcile(InstalledRoute(routeId = "camino-frances", stageCount = 3))
        h.drain()
        loader.releaseRegions()
        assertEquals(
            setOf("pilgrimage:camino-frances:4294967296", "pilgrimage:camino-frances:x"),
            loader.removedIds.toSet(),
        )
    }

    /**
     * The package manager calls `remove` on IO holding its actor. On the
     * production dispatcher the call posts to the main looper and returns;
     * the save is cancelled and the route's regions go once the looper runs
     * it (spec D §C2.12 point 2, correction 14).
     */
    @Test
    fun `remove from another thread cancels a running save and returns without waiting`() {
        val escaped = mutableListOf<Throwable>()
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + recordingHandler(escaped))
        val loader = FakeTileRegionLoader()
        val manager = PilgrimageTilesManager(
            loader,
            InMemoryTilesCalibration(),
            FakeWalkSignals(),
            Dispatchers.Main.immediate,
            mainScope,
        )
        try {
            val two = tileStages(2)
            loader.seedStylePacks()
            loader.seed("pilgrimage:kumano-kodo-nakahechi:0", "h")
            val save = manager.save("camino-frances", two)
            loader.completeNextRegion()
            assertEquals(Phase.Saving(done = 3, total = 4), manager.phase.value)
            val inFlight = loader.pendingRegions.single().handle

            val caller = thread { manager.remove("camino-frances") }
            caller.join(DEADLOCK_GUARD_MILLIS)

            assertFalse("remove returned without waiting for the main thread", caller.isAlive)
            assertTrue("posted, not yet run", loader.removedIds.isEmpty())
            assertEquals(Phase.Saving(done = 3, total = 4), manager.phase.value)

            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(Phase.Idle, manager.phase.value)
            assertTrue(inFlight.isCancelled)
            assertTrue(save.isCompleted)
            assertEquals(PilgrimageError.INCOMPLETE, runBlocking { save.failure() })
            assertEquals(listOf(two[0].id), loader.removedIds)
            assertNotNull(loader.regions().firstOrNull { it.id == "pilgrimage:kumano-kodo-nakahechi:0" })
            assertTrue(escaped.isEmpty())
        } finally {
            mainScope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private companion object {
        /** Only a hang's backstop: the call returns in microseconds, or deadlocks against the blocked main thread. */
        const val DEADLOCK_GUARD_MILLIS = 5_000L
    }
}
