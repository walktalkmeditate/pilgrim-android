// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesFixtures.tileStage
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesFixtures.tileStages
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesHarness.Companion.failure
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Companion.SEED_BYTES_PER_PACK
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Companion.STORE_WAIT_MILLIS
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Phase
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Status

/**
 * Port of iOS `PilgrimageTilesManagerTests.swift@7c200bf`, names kept
 * (spec D §C2.T): 22 of its 24, since the two pack-count tests went to
 * U43 with the pure `packCount`, and the hash-version test keeps only its
 * `isStageSaved` half here. Each stage is U43's [TileStage] built from iOS's
 * fixture; iOS's `isWalkActive` is the walk screen ([FakeWalkSignals.screenUp]);
 * a `Task` is the save's `Deferred`, and iOS's yields are a drain of the
 * manager's thread.
 *
 * Then the Android additions: the walk row as a second clause, the door's
 * and a load's suspending walk read (a cancel or a second tap during it,
 * a read that throws), the re-tap that draws nothing, a loader that throws
 * or calls back twice, a deferred its caller cancels, a partial load, the
 * store wait, and the DataStore calibration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageTilesManagerTest {

    private val h = PilgrimageTilesHarness()
    private val loader = h.loader
    private val manager = h.manager

    // ---- iOS's tests ----------------------------------------------------------

    @Test
    fun `a fresh manager reports nothing saved`() = h.run {
        assertEquals(Status.None, manager.status(tileStages(3)))
    }

    @Test
    fun `the estimate is packs times the route's own bytes per pack and the seed by default`() = h.run {
        val three = tileStages(3)
        val packs = manager.packCount(three).toLong()
        assertEquals(packs * SEED_BYTES_PER_PACK, manager.estimateBytes("camino-frances", three))
        h.calibration.store("camino-frances", 2_700_000L)
        assertEquals(packs * 2_700_000L, manager.estimateBytes("camino-frances", three))
        assertEquals(
            "another route's calibration never leaks",
            packs * SEED_BYTES_PER_PACK,
            manager.estimateBytes("kumano-kodo-nakahechi", tileStages(3, routeId = "kumano-kodo-nakahechi")),
        )
    }

    /** iOS's hash-version test, its `isStageSaved` half; the two hash equalities are U43's. */
    @Test
    fun `the corridor hash carries the region version`() = h.run {
        val stage = tileStage(0)
        loader.seedStylePacks()
        loader.seed(
            id = stage.id,
            corridorHash = PilgrimageTilesCorridor.corridorHash(
                stage.rings,
                version = PilgrimageTilesDescriptors.REGION_VERSION - 1,
            ),
        )
        assertFalse("a first-build region reads as unsaved", manager.isStageSaved(stage))
    }

    @Test
    fun `status counts only complete regions whose corridor still matches`() = h.run {
        val three = tileStages(3)
        loader.seedStylePacks()
        loader.seed(three[0].id, three[0].corridorHash)
        loader.seed(three[1].id, "stale")
        loader.seed(three[2].id, three[2].corridorHash, complete = false)
        assertEquals(Status.Partial(saved = 1, of = 3), manager.status(three))
        assertTrue(manager.isStageSaved(three[0]))
        assertFalse(manager.isStageSaved(three[1]))
        assertFalse(manager.isStageSaved(three[2]))
    }

    /** D4, matched: every region saved with a pack missing reads "n of n". */
    @Test
    fun `status is saved only when every stage and both packs are present`() = h.run {
        val two = tileStages(2)
        two.forEach { loader.seed(it.id, it.corridorHash, bytes = 50_000L) }
        assertEquals("no style packs yet", Status.Partial(saved = 2, of = 2), manager.status(two))
        loader.seedStylePacks()
        assertEquals(Status.Saved(bytes = 100_000L), manager.status(two))
    }

    @Test
    fun `the footprint counts stale bytes of the route from one read`() = h.run {
        val three = tileStages(3)
        loader.seed(three[0].id, three[0].corridorHash, bytes = 5_000_000L)
        loader.seed(three[1].id, "stale", bytes = 2_000_000L)
        loader.seed("pilgrimage:kumano-kodo-nakahechi:0", "h", bytes = 9_000_000L)
        assertEquals(
            PilgrimageTilesManager.Footprint(savedStages = 1, bytes = 7_000_000L),
            manager.footprint("camino-frances", three),
        )
        assertEquals(1, loader.regionsReadCount)
    }

    @Test
    fun `a save loads packs then regions in order and skips what is there`() = h.run {
        val three = tileStages(3)
        loader.stylePacks = setOf(StylePackRequest.LIGHT)
        loader.seed(three[1].id, three[1].corridorHash)
        val save = manager.save("camino-frances", three)
        h.untilPending()
        assertEquals("the light pack was already there", listOf(StylePackRequest.DARK), loader.packRequests)
        loader.completeNextPack()
        h.untilPending()
        assertEquals(listOf(three[0].id), loader.regionRequests.map { it.id })
        loader.completeNextRegion()
        h.untilPending()
        assertEquals("stage 1 was complete and current", listOf(three[0].id, three[2].id), loader.regionRequests.map { it.id })
        loader.completeNextRegion()
        save.await()
        assertEquals(Phase.Idle, manager.phase.value)
        assertEquals(Status.Saved(bytes = 300_000L), manager.status(three))
    }

    @Test
    fun `progress counts packs and stages`() = h.run {
        val two = tileStages(2)
        val save = manager.save("camino-frances", two)
        h.untilPending()
        assertEquals(Phase.Saving(done = 0, total = 4), manager.phase.value)
        loader.completeNextPack()
        h.untilPending()
        loader.completeNextPack()
        h.untilPending()
        assertEquals(Phase.Saving(done = 2, total = 4), manager.phase.value)
        loader.completeNextRegion()
        h.untilPending()
        assertEquals(Phase.Saving(done = 3, total = 4), manager.phase.value)
        loader.completeNextRegion()
        save.await()
    }

    @Test
    fun `cancel keeps what is done and resume starts at the gap`() = h.run {
        val four = tileStages(4)
        loader.seedStylePacks()
        val first = manager.save("camino-frances", four)
        h.untilPending()
        loader.completeNextRegion()
        h.untilPending()
        loader.completeNextRegion()
        h.untilPending()
        manager.cancel()
        assertEquals(PilgrimageError.INCOMPLETE, first.failure())
        assertEquals(Phase.Idle, manager.phase.value)
        assertEquals(Status.Partial(saved = 2, of = 4), manager.status(four))
        assertTrue(loader.pendingRegions.isEmpty() || loader.pendingRegions.first().handle.isCancelled)

        loader.regionRequests.clear()
        val second = manager.save("camino-frances", four)
        // Not `untilPending`: the first save's cancelled load is still queued.
        h.drain()
        assertEquals("resume picks up at the first gap", four[2].id, loader.regionRequests.first().id)
        // The cancelled load's late completion reaches the manager, which
        // ignores it, so three completions finish the two stages left.
        h.untilPending()
        loader.completeNextRegion()
        h.untilPending()
        loader.completeNextRegion()
        h.untilPending()
        loader.completeNextRegion()
        second.await()
    }

    /**
     * The fake's completion only schedules the loop's resumption, and
     * [PilgrimageTilesManager.cancel] runs inline, so the cancel lands
     * exactly between the completion and the loop's next hop.
     */
    @Test
    fun `a cancel landing between a load's completion and the next hop ends the save`() = h.run {
        loader.seedStylePacks()
        val save = manager.save("camino-frances", tileStages(2))
        h.untilPending()
        loader.completeNextRegion()
        manager.cancel()
        val requestsAtCancel = loader.regionRequests.size
        h.drain()
        assertTrue("the save ended rather than hung", save.isCompleted)
        assertEquals(PilgrimageError.INCOMPLETE, save.failure())
        assertEquals(Phase.Idle, manager.phase.value)
        assertEquals("no further region was requested after the cancel", requestsAtCancel, loader.regionRequests.size)
    }

    @Test
    fun `a redrawn stage is reloaded and an unchanged one is not`() = h.run {
        val three = tileStages(3).toMutableList()
        loader.seedStylePacks()
        three.forEach { loader.seed(it.id, it.corridorHash) }
        three[1] = tileStage(1, count = 3, lonOffset = 0.04 + 0.01)
        val save = manager.save("camino-frances", three)
        h.untilPending()
        assertEquals(listOf(three[1].id), loader.regionRequests.map { it.id })
        assertEquals(three[1].corridorHash, loader.regionRequests.first().corridorHash)
        assertTrue(loader.regionRequests.first().acceptExpired)
        loader.completeNextRegion()
        save.await()
    }

    @Test
    fun `a walk starting mid save stops it and keeps what is done`() = h.run {
        val seven = tileStages(7)
        loader.seedStylePacks()
        val save = manager.save("camino-frances", seven)
        repeat(5) {
            h.untilPending()
            loader.completeNextRegion()
        }
        h.untilPending()
        h.signals.screenUp = true
        loader.completeNextRegion()
        assertEquals(PilgrimageError.WALK_IN_PROGRESS, save.failure())
        assertEquals("no seventh load was requested", 6, loader.regionRequests.size)
        assertEquals(Status.Partial(saved = 6, of = 7), manager.status(seven))
        assertEquals(Phase.Failed(PilgrimageError.WALK_IN_PROGRESS), manager.phase.value)
    }

    @Test
    fun `refused while walking before anything is requested`() = h.run {
        h.signals.screenUp = true
        assertEquals(PilgrimageError.WALK_IN_PROGRESS, manager.save("camino-frances", tileStages(2)).failure())
        assertTrue(loader.packRequests.isEmpty())
        assertTrue(loader.regionRequests.isEmpty())
        assertEquals(Phase.Failed(PilgrimageError.WALK_IN_PROGRESS), manager.phase.value)
        assertEquals("the walk screen refuses before the walk row is read", 0, h.signals.activeReads.get())
    }

    @Test
    fun `a second save while saving makes no calls`() = h.run {
        val two = tileStages(2)
        val first = manager.save("camino-frances", two)
        h.untilPending()
        manager.save("camino-frances", two).await()
        assertEquals(1, loader.packRequests.size)
        loader.completeNextPack()
        h.untilPending()
        loader.completeNextPack()
        h.untilPending()
        loader.completeNextRegion()
        h.untilPending()
        loader.completeNextRegion()
        first.await()
    }

    @Test
    fun `a failed load lands in failed keeps earlier regions and clears`() = h.run {
        val three = tileStages(3)
        loader.seedStylePacks()
        val save = manager.save("camino-frances", three)
        h.untilPending()
        loader.completeNextRegion()
        h.untilPending()
        loader.nextRegionFailure = TileRegionLoadingError.FAILED
        loader.completeNextRegion()
        assertEquals(PilgrimageError.INCOMPLETE, save.failure())
        assertEquals(Phase.Failed(PilgrimageError.INCOMPLETE), manager.phase.value)
        assertEquals(Status.Partial(saved = 1, of = 3), manager.status(three))
        manager.cancel()
        assertEquals(Phase.Idle, manager.phase.value)
    }

    @Test
    fun `a failed phase clears on the next save`() = h.run {
        val one = tileStages(1)
        loader.seedStylePacks()
        val failing = manager.save("camino-frances", one)
        h.untilPending()
        loader.nextRegionFailure = TileRegionLoadingError.FAILED
        loader.completeNextRegion()
        assertEquals(PilgrimageError.INCOMPLETE, failing.failure())
        assertEquals(Phase.Failed(PilgrimageError.INCOMPLETE), manager.phase.value)

        val second = manager.save("camino-frances", one)
        h.untilPending()
        assertEquals(
            "both packs were there; the region is loading again",
            Phase.Saving(done = 2, total = 3),
            manager.phase.value,
        )
        loader.completeNextRegion()
        second.await()
    }

    @Test
    fun `disk full surfaces as disk full`() = h.run {
        loader.seedStylePacks()
        val save = manager.save("camino-frances", tileStages(1))
        h.untilPending()
        loader.nextRegionFailure = TileRegionLoadingError.DISK_FULL
        loader.completeNextRegion()
        assertEquals(PilgrimageError.DISK_FULL, save.failure())
    }

    /** The store's pack ceiling refuses before anything downloads; "didn't finish" would send the walker to a retry that refuses the same way. */
    @Test
    fun `a pack ceiling refusal surfaces as map too large`() = h.run {
        loader.seedStylePacks()
        val save = manager.save("camino-frances", tileStages(1))
        h.untilPending()
        loader.nextRegionFailure = TileRegionLoadingError.TILE_COUNT_EXCEEDED
        loader.completeNextRegion()
        assertEquals(PilgrimageError.MAP_TOO_LARGE, save.failure())
        assertEquals(Phase.Failed(PilgrimageError.MAP_TOO_LARGE), manager.phase.value)
    }

    @Test
    fun `a completed save calibrates this route only`() = h.run {
        val two = tileStages(2)
        loader.seedStylePacks()
        loader.bytesPerRegion = 400_000L
        val save = manager.save("camino-frances", two)
        h.untilPending()
        loader.completeNextRegion()
        h.untilPending()
        loader.completeNextRegion()
        save.await()
        assertEquals(800_000L / manager.packCount(two), h.calibration.stored("camino-frances"))
        assertNull(h.calibration.stored("kumano-kodo-nakahechi"))
    }

    /** Writing zero for a route with no bytes or no corridor would turn the next estimate into "~1 MB" for the whole Francés. */
    @Test
    fun `calibrate writes nothing without bytes and packs`() = h.run {
        val two = tileStages(2)
        manager.calibrate("camino-frances", two)
        assertNull("no bytes yet", h.calibration.stored("camino-frances"))

        val lineless = TileStage(
            id = two[0].id,
            index = 0,
            rings = emptyList(),
            corridorHash = PilgrimageTilesCorridor.corridorHash(emptyList()),
        )
        loader.seed(lineless.id, "h", bytes = 5_000_000L)
        manager.calibrate("camino-frances", listOf(lineless))
        assertNull("bytes, but no pack to divide by", h.calibration.stored("camino-frances"))
    }

    /** A store read per stage would be thirty-five on the Francés, each refreshing the loader's cache off the real store. */
    @Test
    fun `a save reads the store once before the loop and once to calibrate`() = h.run {
        loader.seedStylePacks()
        val save = manager.save("camino-frances", tileStages(3))
        repeat(2) {
            h.untilPending()
            loader.completeNextRegion()
        }
        h.untilPending()
        loader.completeNextRegion()
        save.await()
        assertEquals("one snapshot for the loop, one for calibrate", 2, loader.regionsReadCount)
    }

    /** iOS's `objectWillChange` test: a view that read before the store answered needs this to learn it arrived. */
    @Test
    fun `the manager publishes when the loader announces a change`() = h.run {
        loader.seedStylePacks()
        val save = manager.save("camino-frances", tileStages(1))
        h.untilPending()
        manager.regionsChanged.test {
            loader.firePacksChange()
            expectNoEvents()
            loader.completeNextRegion()
            awaitItem()
        }
        save.await()
    }

    // ---- Android: the walk row ----------------------------------------------------

    /** iOS's mid-save test with Android's clause: a walk row a revived `:tracker` holds, its screen not up. */
    @Test
    fun `a walk row appearing mid save stops it before the next load and keeps what is done`() = h.run {
        val seven = tileStages(7)
        loader.seedStylePacks()
        val save = manager.save("camino-frances", seven)
        repeat(5) {
            h.untilPending()
            loader.completeNextRegion()
        }
        h.untilPending()
        h.signals.active = true
        loader.completeNextRegion()
        assertEquals(PilgrimageError.WALK_IN_PROGRESS, save.failure())
        assertEquals("no seventh load was requested", 6, loader.regionRequests.size)
        assertEquals(Status.Partial(saved = 6, of = 7), manager.status(seven))
        assertEquals(Phase.Failed(PilgrimageError.WALK_IN_PROGRESS), manager.phase.value)
    }

    @Test
    fun `a walk row refuses at the door before anything is requested`() = h.run {
        h.signals.active = true
        assertEquals(PilgrimageError.WALK_IN_PROGRESS, manager.save("camino-frances", tileStages(2)).failure())
        assertTrue(loader.packRequests.isEmpty())
        assertTrue(loader.regionRequests.isEmpty())
        assertEquals(Phase.Failed(PilgrimageError.WALK_IN_PROGRESS), manager.phase.value)
    }

    @Test
    fun `a cancel during the door's walk read starts no load and ends the save`() = h.run {
        val read = CompletableDeferred<Unit>()
        h.signals.onActiveRead = { read.await() }
        loader.seed("pilgrimage:kumano-kodo-nakahechi:0", "h")
        manager.reconcile(installed = null)
        h.drain()
        val save = manager.save("camino-frances", tileStages(2))
        h.drain()
        assertEquals(1, h.signals.activeReads.get())

        manager.cancel()
        read.complete(Unit)
        h.drain()

        assertTrue("the save ended rather than hung", save.isCompleted)
        assertEquals(PilgrimageError.INCOMPLETE, save.failure())
        assertEquals(Phase.Idle, manager.phase.value)
        assertTrue(loader.packRequests.isEmpty())
        assertTrue(loader.regionRequests.isEmpty())
        loader.releaseRegions()
        assertEquals(
            "a save that never passed its door leaves the launch sweep",
            listOf("pilgrimage:kumano-kodo-nakahechi:0"),
            loader.removedIds,
        )
    }

    @Test
    fun `a cancel during a load's walk read starts no load and ends the save`() = h.run {
        val read = CompletableDeferred<Unit>()
        h.signals.onActiveRead = { number -> if (number == 2) read.await() }
        val save = manager.save("camino-frances", tileStages(2))
        h.drain()
        assertEquals(Phase.Saving(done = 0, total = 4), manager.phase.value)
        assertEquals("the light pack's walk read is in flight", 2, h.signals.activeReads.get())

        manager.cancel()
        read.complete(Unit)
        h.drain()

        assertTrue("the save ended rather than hung", save.isCompleted)
        assertEquals(PilgrimageError.INCOMPLETE, save.failure())
        assertTrue("no load started after the cancel", loader.packRequests.isEmpty())
        assertEquals(Phase.Idle, manager.phase.value)
    }

    @Test
    fun `a second save during the door's walk read runs no second loop`() = h.run {
        val read = CompletableDeferred<Unit>()
        h.signals.onActiveRead = { number -> if (number == 1) read.await() }
        loader.seedStylePacks()
        val first = manager.save("camino-frances", tileStages(1))
        h.drain()
        manager.save("camino-frances", tileStages(1)).await()
        assertEquals("the second save read nothing", 1, h.signals.activeReads.get())

        read.complete(Unit)
        h.untilPending()
        loader.completeNextRegion()
        first.await()
        assertEquals("one loop, one load", 1, loader.regionRequests.size)
        assertEquals("the door's read and the load's", 2, h.signals.activeReads.get())
    }

    @Test
    fun `a door walk read that throws ends the save as incomplete and lets the next one in`() = h.run {
        h.signals.onActiveRead = { throw SQLiteException("disk I/O error") }
        assertEquals(PilgrimageError.INCOMPLETE, manager.save("camino-frances", tileStages(1)).failure())
        assertEquals(Phase.Failed(PilgrimageError.INCOMPLETE), manager.phase.value)
        assertTrue(loader.packRequests.isEmpty())

        h.signals.onActiveRead = {}
        loader.seedStylePacks()
        val retry = manager.save("camino-frances", tileStages(1))
        h.untilPending()
        loader.completeNextRegion()
        retry.await()
        assertEquals(Phase.Idle, manager.phase.value)
    }

    @Test
    fun `a load's walk read that throws ends the save as incomplete`() = h.run {
        h.signals.onActiveRead = { number -> if (number == 2) throw SQLiteException("disk I/O error") }
        loader.seedStylePacks()
        assertEquals(PilgrimageError.INCOMPLETE, manager.save("camino-frances", tileStages(2)).failure())
        assertEquals(Phase.Failed(PilgrimageError.INCOMPLETE), manager.phase.value)
        assertTrue(loader.regionRequests.isEmpty())
    }

    /**
     * iOS's re-tap runs to its end in one main-actor run, so SwiftUI draws
     * nothing (D7). Android's door reads Room once, then the skips never
     * suspend, so a conflating collector sees no `Saving` either.
     */
    @Test
    fun `an all current re-tap never publishes saving and reads the walk row once`() = h.run {
        val three = tileStages(3)
        loader.seedStylePacks()
        three.forEach { loader.seed(it.id, it.corridorHash) }
        h.signals.onActiveRead = { yield() }
        val seen = mutableListOf<Phase>()
        backgroundScope.launch { manager.phase.collect { seen += it } }
        h.drain()

        manager.save("camino-frances", three).await()
        h.drain()

        assertEquals(listOf<Phase>(Phase.Idle), seen)
        assertEquals(1, h.signals.activeReads.get())
        assertTrue(loader.regionRequests.isEmpty())
        assertEquals("it still calibrates", 300_000L / manager.packCount(three), h.calibration.stored("camino-frances"))
    }

    // ---- Android: what iOS's typed catch never sees --------------------------------

    /** A Mapbox native's `Error` included: the phase carries every failure, and no orphaned continuation is left for a cancel to resume. */
    @Test
    fun `a loader that throws ends the save as failed`() = h.run {
        val throwing = object : TileRegionLoading by loader {
            override fun loadRegion(
                request: TileRegionRequest,
                progress: (completed: Long, required: Long) -> Unit,
                completion: (TileLoadResult<TileRegionSummary>) -> Unit,
            ): TileLoadHandle {
                throw UnsatisfiedLinkError("native")
            }
        }
        val manager = PilgrimageTilesManager(throwing, h.calibration, h.signals, h.main, h.scope)
        loader.seedStylePacks()
        val save = manager.save("camino-frances", tileStages(1))
        h.drain()

        assertTrue("the save ended rather than hung", save.isCompleted)
        assertEquals(Phase.Failed(PilgrimageError.INCOMPLETE), manager.phase.value)
        assertTrue(runCatching { save.await() }.exceptionOrNull() is UnsatisfiedLinkError)
        manager.cancel()
        h.drain()
        assertEquals(Phase.Idle, manager.phase.value)
    }

    @Test
    fun `a loader that throws on a style pack ends the save as failed`() = h.run {
        val throwing = object : TileRegionLoading by loader {
            override fun loadStylePack(pack: StylePackRequest, completion: (TileLoadResult<Unit>) -> Unit): TileLoadHandle =
                throw UnsatisfiedLinkError("native")
        }
        val manager = PilgrimageTilesManager(throwing, h.calibration, h.signals, h.main, h.scope)
        val save = manager.save("camino-frances", tileStages(1))
        h.drain()

        assertTrue("the save ended rather than hung", save.isCompleted)
        assertEquals(Phase.Failed(PilgrimageError.INCOMPLETE), manager.phase.value)
        assertTrue(runCatching { save.await() }.exceptionOrNull() is UnsatisfiedLinkError)
        assertTrue("no region was asked for", loader.regionRequests.isEmpty())
    }

    /**
     * A doubled SDK callback, the second after the next load started: only
     * `pending === cont` keeps it from resuming the first load's wait again
     * and clearing the second's.
     */
    @Test
    fun `a load's completion delivered twice is heard once`() = h.run {
        val two = tileStages(2)
        loader.seedStylePacks()
        val save = manager.save("camino-frances", two)
        h.untilPending()
        val first = loader.pendingRegions.single()
        loader.completeNextRegion()
        h.untilPending()
        assertEquals(Phase.Saving(done = 3, total = 4), manager.phase.value)

        first.completion(TileLoadResult.Success(loader.regions().single()))
        h.drain()

        assertEquals("the second stage still waits on its own load", Phase.Saving(done = 3, total = 4), manager.phase.value)
        assertFalse(save.isCompleted)
        assertFalse(loader.pendingRegions.single().handle.isCancelled)
        loader.completeNextRegion()
        save.await()
        assertEquals(Status.Saved(bytes = 200_000L), manager.status(two))
        assertEquals("one load per stage", 2, loader.regionRequests.size)
    }

    // ---- Android: the deferred is the caller's handle, not the save ------------------

    /** iOS's caller holds a `Task` whose cancellation never reaches the save; a caller cancelling the deferred must not freeze the row in `Saving`. */
    @Test
    fun `cancelling a save's deferred mid load leaves the save to finish, and the next save starts`() = h.run {
        val two = tileStages(2)
        loader.seedStylePacks()
        val save = manager.save("camino-frances", two)
        h.untilPending()
        save.cancel()
        h.drain()
        assertFalse("the load in flight runs on", loader.pendingRegions.single().handle.isCancelled)

        loader.completeNextRegion()
        h.untilPending()
        loader.completeNextRegion()
        h.drain()
        assertEquals(Phase.Idle, manager.phase.value)
        assertEquals(Status.Saved(bytes = 200_000L), manager.status(two))

        val three = tileStages(3)
        val next = manager.save("camino-frances", three)
        h.untilPending()
        assertEquals(three[2].id, loader.regionRequests.last().id)
        loader.completeNextRegion()
        next.await()
        assertEquals(Phase.Idle, manager.phase.value)
    }

    @Test
    fun `cancelling a save's deferred during the door's walk read leaves the save to run`() = h.run {
        val read = CompletableDeferred<Unit>()
        h.signals.onActiveRead = { number -> if (number == 1) read.await() }
        loader.seedStylePacks()
        val one = tileStages(1)
        val save = manager.save("camino-frances", one)
        h.drain()
        save.cancel()
        read.complete(Unit)

        h.untilPending()
        assertEquals(Phase.Saving(done = 2, total = 3), manager.phase.value)
        loader.completeNextRegion()
        h.drain()
        assertEquals(Phase.Idle, manager.phase.value)
        assertTrue(manager.isStageSaved(one[0]))
    }

    @Test
    fun `what escapes a posted hook reaches the scope's handler, and the next one still runs`() = h.run {
        val throwing = object : TileRegionLoading by loader {
            override fun removeRegion(id: String) {
                throw IllegalStateException("store gone")
            }
        }
        val manager = PilgrimageTilesManager(throwing, h.calibration, h.signals, h.main, h.scope)
        loader.seed("pilgrimage:camino-frances:0", "h")
        manager.remove("camino-frances")
        h.drain()
        assertTrue(h.escaped.single() is IllegalStateException)
        h.escaped.clear()

        manager.warm()
        h.drain()
        assertEquals("the scope outlived the failure", 1, loader.firstAnswerRequests)
    }

    @Test
    fun `a scope without an exception handler is refused`() = h.run {
        val bare = CoroutineScope(SupervisorJob() + h.main)
        assertTrue(runCatching { PilgrimageTilesManager(loader, h.calibration, h.signals, h.main, bare) }.exceptionOrNull() is IllegalArgumentException)
        bare.coroutineContext.job.cancelAndJoin()
    }

    @Test
    fun `cancelling the manager's scope cancels the load in flight`() = h.run {
        loader.seedStylePacks()
        manager.save("camino-frances", tileStages(1))
        h.untilPending()
        val inFlight = loader.pendingRegions.single().handle
        h.scope.coroutineContext.job.cancelAndJoin()
        assertTrue(inFlight.isCancelled)
    }

    // ---- Android: a partial load (matched, spec D flow item 7) --------------------

    @Test
    fun `a load that succeeds with resources missing counts as done and reads unsaved`() = h.run {
        val two = tileStages(2)
        loader.seedStylePacks()
        val save = manager.save("camino-frances", two)
        h.untilPending()
        loader.completeNextRegion(partial = true)
        h.untilPending()
        loader.completeNextRegion()
        save.await()
        assertEquals("no error line", Phase.Idle, manager.phase.value)
        assertEquals(Status.Partial(saved = 1, of = 2), manager.status(two))
        assertEquals(
            "the partial region's bytes are calibrated",
            200_000L / manager.packCount(two),
            h.calibration.stored("camino-frances"),
        )

        loader.regionRequests.clear()
        val retap = manager.save("camino-frances", two)
        h.untilPending()
        assertEquals("a re-tap reloads it", listOf(two[0].id), loader.regionRequests.map { it.id })
        loader.completeNextRegion()
        retap.await()
    }

    // ---- Android: the store wait (spec D §C2.12 point 7, C3 §7) -------------------

    @Test
    fun `a reader asked before the store's first answer waits for it, then reads it`() = h.run {
        val stage = tileStage(0)
        loader.seedStylePacks()
        val answer = async { manager.awaitStore() }
        runCurrent()
        assertFalse(answer.isCompleted)
        assertEquals(1, loader.firstAnswerRequests)

        loader.seed(stage.id, stage.corridorHash)
        loader.releaseRegions()

        assertTrue(answer.await())
        assertTrue(manager.isStageSaved(stage))
    }

    @Test
    fun `past the bound the store wait answers unknown, and a later reader waits again`() = h.run {
        val answer = async { manager.awaitStore() }
        advanceTimeBy(STORE_WAIT_MILLIS - 1)
        runCurrent()
        assertFalse(answer.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(answer.await())

        val later = async { manager.awaitStore() }
        runCurrent()
        loader.releaseRegions()
        assertTrue(later.await())
        assertEquals("the store is asked once per process", 1, loader.firstAnswerRequests)
    }

    @Test
    fun `a failed first answer ends the store wait as unknown`() = h.run {
        val answer = async { manager.awaitStore() }
        runCurrent()
        loader.failRegions()
        assertFalse(answer.await())
    }

    /** One failed read at a cold start must not cost "the day" its maps line for the rest of the walk. */
    @Test
    fun `after a failed first answer the next store wait asks again and can read`() = h.run {
        val first = async { manager.awaitStore() }
        runCurrent()
        loader.failRegions()
        assertFalse(first.await())

        val again = async { manager.awaitStore() }
        runCurrent()
        assertFalse("a fresh read, not the failure replayed", again.isCompleted)
        assertEquals(2, loader.firstAnswerRequests)
        loader.releaseRegions()
        assertTrue(again.await())
    }

    @Test
    fun `a first answer request that throws reads as unknown at once, and the next reader asks again`() = h.run {
        var requests = 0
        val throwing = object : TileRegionLoading by loader {
            override fun firstAnswer(completion: (TileStoreRead) -> Unit) {
                requests += 1
                if (requests == 1) throw IllegalStateException("the store won't open")
                loader.firstAnswer(completion)
            }
        }
        val manager = PilgrimageTilesManager(throwing, h.calibration, h.signals, h.main, h.scope)
        val answer = async { manager.awaitStore() }
        runCurrent()
        assertTrue("no time passed", answer.isCompleted)
        assertFalse(answer.await())
        assertTrue("what it threw reached the handler", h.escaped.single() is IllegalStateException)
        h.escaped.clear()

        val later = async { manager.awaitStore() }
        runCurrent()
        assertEquals(2, requests)
        loader.releaseRegions()
        assertTrue(later.await())
    }

    /** A removal is what makes the first read stale at a cold start; neither it nor the packs answer is the store's answer. */
    @Test
    fun `the store wait ignores a regions change and a packs answer that aren't the first answer`() = h.run {
        val answer = async { manager.awaitStore() }
        runCurrent()
        loader.seed("pilgrimage:camino-frances:0", "h")
        loader.removeRegion("pilgrimage:camino-frances:0")
        loader.firePacksChange()
        runCurrent()
        assertFalse(answer.isCompleted)

        loader.releaseRegions()
        assertTrue(answer.await())
    }

    @Test
    fun `after the first answer the store wait answers at once with no new store read`() = h.run {
        val first = async { manager.awaitStore() }
        runCurrent()
        loader.releaseRegions()
        assertTrue(first.await())
        val readsBefore = loader.regionsReadCount

        val again = async { manager.awaitStore() }
        runCurrent()
        assertTrue("no time passed", again.isCompleted)
        assertTrue(again.await())
        assertEquals(1, loader.firstAnswerRequests)
        assertEquals(readsBefore, loader.regionsReadCount)
    }

    @Test
    fun `warm asks for the first answer without waiting, once`() = h.run {
        manager.warm()
        assertEquals("posted", 0, loader.firstAnswerRequests)
        h.drain()
        assertEquals(1, loader.firstAnswerRequests)
        loader.releaseRegions()

        val answer = async { manager.awaitStore() }
        runCurrent()
        assertTrue(answer.isCompleted)
        assertTrue(answer.await())
        assertEquals(1, loader.firstAnswerRequests)
    }

    @Test
    fun `regions changed doesn't replay to a late collector`() = h.run {
        loader.removeRegion("pilgrimage:camino-frances:0")
        manager.regionsChanged.test {
            expectNoEvents()
        }
    }

    // ---- Android: the calibration store ---------------------------------------------

    @Test
    fun `the calibration lands under iOS's key, and a missing or non-positive figure reads as the seed`() = h.run {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writeFailures = CopyOnWriteArrayList<Throwable>()
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + recordingHandler(writeFailures))
        val dataStore = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { context.preferencesDataStoreFile("tiles_calibration_${UUID.randomUUID()}") },
        )
        try {
            val stored = PilgrimageTilesManager(
                loader,
                DataStorePilgrimageTilesCalibration(dataStore, writeScope),
                h.signals,
                h.main,
                h.scope,
            )
            val two = tileStages(2)
            two.forEach { loader.seed(it.id, it.corridorHash, bytes = 400_000L) }
            val expected = 800_000L / stored.packCount(two)

            stored.calibrate("camino-frances", two)

            assertEquals("read back before the disk write lands", expected, stored.bytesPerPack("camino-frances"))
            val key = longPreferencesKey("pilgrimage.tiles.bytesPerPack.camino-frances")
            writeScope.coroutineContext.job.children.toList().joinAll()
            assertEquals(expected, dataStore.data.first()[key])
            dataStore.edit {
                it[longPreferencesKey("pilgrimage.tiles.bytesPerPack.camino-norte")] = 0L
                it[longPreferencesKey("pilgrimage.tiles.bytesPerPack.kumano-kodo-nakahechi")] = -1L
            }
            assertEquals(SEED_BYTES_PER_PACK, stored.bytesPerPack("camino-norte"))
            assertEquals(SEED_BYTES_PER_PACK, stored.bytesPerPack("kumano-kodo-nakahechi"))
            assertEquals(SEED_BYTES_PER_PACK, stored.bytesPerPack("shikoku-henro"))
            assertTrue(writeFailures.isEmpty())
        } finally {
            writeScope.coroutineContext.job.cancelAndJoin()
            storeScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `a quotient of zero is written and reads as the seed`() = h.run {
        val two = tileStages(2)
        loader.seed(two[0].id, two[0].corridorHash, bytes = 1L)
        manager.calibrate("camino-frances", two)
        assertEquals(0L, h.calibration.stored("camino-frances"))
        assertEquals(SEED_BYTES_PER_PACK, manager.bytesPerPack("camino-frances"))
    }

    @Test
    fun `two calibration writes landing out of order leave the newer figure on disk`() = h.run {
        val disk = ScriptedPreferencesStore()
        val firstWrite = CompletableDeferred<Unit>()
        disk.holds.addLast(firstWrite)
        val calibration = DataStorePilgrimageTilesCalibration(disk, h.scope)
        val key = DataStorePilgrimageTilesCalibration.key("camino-frances")

        calibration.store("camino-frances", 1_000L)
        h.drain()
        calibration.store("camino-frances", 2_000L)
        h.drain()
        assertEquals("the second write landed first", 2_000L, disk.data.first()[key])

        firstWrite.complete(Unit)
        h.drain()
        assertEquals("both writes landed", 2, disk.writes)
        assertEquals(2_000L, disk.data.first()[key])
    }

    @Test
    fun `a failed calibration write reaches the handler and keeps the figure in memory`() = h.run {
        val disk = ScriptedPreferencesStore().apply { failure = IOException("no space left on device") }
        val calibration = DataStorePilgrimageTilesCalibration(disk, h.scope)

        calibration.store("camino-frances", 1_000L)
        h.drain()

        assertTrue(h.escaped.single() is IOException)
        h.escaped.clear()
        assertNull("nothing reached the disk", disk.data.first()[DataStorePilgrimageTilesCalibration.key("camino-frances")])
        assertEquals("served from memory for the process", 1_000L, calibration.stored("camino-frances"))
    }
}

/** Preferences in memory whose writes a test can hold or fail, which the real store can't be made to do on cue. */
private class ScriptedPreferencesStore : DataStore<Preferences> {
    private val stored = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = stored

    /** Each write takes the next hold, if any, and waits on it before it reads and transforms. */
    val holds = ArrayDeque<CompletableDeferred<Unit>>()

    /** When set, every write throws it, after its hold. */
    var failure: IOException? = null

    var writes = 0
        private set

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        holds.removeFirstOrNull()?.await()
        failure?.let { throw it }
        return transform(stored.value).also {
            stored.value = it
            writes += 1
        }
    }
}
