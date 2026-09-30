// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

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
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.honor.HonorCardStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.HonorWalkMarkerEntity
import org.walktalkmeditate.pilgrim.data.honor.WayArrival
import org.walktalkmeditate.pilgrim.data.honor.WayLink
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.WAY_ID

/** The Honor step after the walk's finish is recorded, its retry at launch, and the staging sweep. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorFinalizerTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: HonorHarness
    private val dao get() = h.db.honorDao()
    private val way = HonorHarness.way()
    private val arrival = WayArrival(theirSeconds = 600.0, yourSeconds = 540.0)

    @Before
    fun setUp() {
        h = HonorHarness(folder.root)
    }

    @After
    fun tearDown() {
        h.close()
    }

    /** A walk with Honor as `finishWalkAtomic` leaves it: finished, its kind recorded, its live rows and staging in place. */
    private suspend fun finishedHonorWalk(
        kind: HonorFinishKind?,
        arrival: WayArrival? = this.arrival,
        endTimestamp: Long? = 9_000L,
    ): Walk {
        val uuid = UUID.randomUUID().toString()
        val id = h.db.walkDao().insert(Walk(uuid = uuid, startTimestamp = 1_000L, endTimestamp = endTimestamp))
        h.db.walkEventDao().insert(WalkEvent(walkId = id, timestamp = 1_000L, eventType = WalkEventType.HONOR_MODE))
        dao.insertSession(
            HonorSessionEntity(
                walkId = id,
                wayId = WAY_ID,
                sourceKind = HonorSourceKind.OWN_WALK,
                voicesEnabled = true,
                softTapEnabled = false,
                phase = if (arrival != null) HonorPhase.ARRIVED else HonorPhase.WALKING,
                arrivalTheirSeconds = arrival?.theirSeconds,
                arrivalYourSeconds = arrival?.yourSeconds,
                finishKind = kind,
            ),
        )
        dao.upsertMomentState(HonorMomentStateEntity(walkId = id, momentId = "voice-1", reachedAt = 2_000L, heard = true))
        dao.insertCardStateIfAbsent(HonorCardStateEntity(walkId = id, momentId = "voice-1", touched = true))
        h.store.stage(uuid, way)
        return h.db.walkDao().getById(id)!!
    }

    private suspend fun assertNoLiveRows(walk: Walk) {
        assertNull("session row", dao.getSession(walk.id))
        assertTrue("moment rows", dao.getMomentStates(walk.id).isEmpty())
        assertTrue("card rows", dao.getCardStates(walk.id).isEmpty())
    }

    private fun wayFile() = File(folder.root, "Ways/$WAY_ID/way.json")

    // Clean finish

    @Test
    fun `a clean finish links with the arrival numbers, lists the Way, marks the walk, and removes the live rows`() = runBlocking {
        val walk = finishedHonorWalk(HonorFinishKind.CLEAN)

        assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(walk.id))

        assertEquals(WayLink(WAY_ID, 600.0, 540.0), h.store.wayLink(walk.uuid))
        assertEquals(way, h.store.load(WAY_ID))
        assertNull("promoted, so no longer staged", h.store.staged(walk.uuid))
        assertEquals(HonorWalkMarkerEntity(walk.uuid, finishedAt = 9_000L, finishKind = HonorFinishKind.CLEAN), dao.getMarker(walk.uuid))
        assertNoLiveRows(walk)
        assertEquals("the walk's events are untouched", 1, h.repository.eventsFor(walk.id).size)
    }

    @Test
    fun `running the step again changes nothing`() = runBlocking {
        val walk = finishedHonorWalk(HonorFinishKind.CLEAN)
        h.finalizer.finalize(walk.id)
        val link = h.store.wayLink(walk.uuid)
        val marker = dao.getMarker(walk.uuid)
        val wayJson = wayFile().readText()
        val accepted = h.store.acceptedAt(WAY_ID)
        h.clock.millis += 60_000

        assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(walk.id))
        assertEquals(0, h.finalizer.finalizePending())

        assertEquals(link, h.store.wayLink(walk.uuid))
        assertEquals(marker, dao.getMarker(walk.uuid))
        assertEquals(wayJson, wayFile().readText())
        assertEquals(accepted, h.store.acceptedAt(WAY_ID))
    }

    @Test
    fun `a clean finish overwrites an earlier build of the Way and keeps its first acceptance`() = runBlocking {
        h.store.save(way.copy(title = "an earlier build"))
        val firstAccepted = h.store.acceptedAt(WAY_ID)
        h.clock.millis += 86_400_000
        val walk = finishedHonorWalk(HonorFinishKind.CLEAN)

        h.finalizer.finalize(walk.id)

        assertEquals("Morning loop", h.store.load(WAY_ID)!!.title)
        assertEquals(firstAccepted, h.store.acceptedAt(WAY_ID))
    }

    @Test
    fun `a clean finish before arrival links with no numbers`() = runBlocking {
        val walk = finishedHonorWalk(HonorFinishKind.CLEAN, arrival = null)

        h.finalizer.finalize(walk.id)

        assertEquals(WayLink(WAY_ID), h.store.wayLink(walk.uuid))
    }

    // Recovery

    @Test
    fun `recovering a first honoring links nothing, drops the numbers, and leaves the staging for the sweep`() = runBlocking {
        val walk = finishedHonorWalk(HonorFinishKind.RECOVERED)

        assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(walk.id))

        assertNull("the Way was never listed", h.store.wayLink(walk.uuid))
        assertNull(h.store.load(WAY_ID))
        assertNotNull(h.store.staged(walk.uuid))
        assertEquals(HonorFinishKind.RECOVERED, dao.getMarker(walk.uuid)!!.finishKind)
        assertNoLiveRows(walk)
        assertTrue(h.repository.eventsFor(walk.id).any { it.eventType == WalkEventType.HONOR_MODE })
    }

    @Test
    fun `recovering a repeat honoring links the listed build with no delta`() = runBlocking {
        h.store.save(way.copy(title = "the previous honoring's build"))
        val walk = finishedHonorWalk(HonorFinishKind.RECOVERED)

        h.finalizer.finalize(walk.id)

        assertEquals(WayLink(WAY_ID, theirSeconds = null, yourSeconds = null), h.store.wayLink(walk.uuid))
        assertEquals("never re-saved", "the previous honoring's build", h.store.load(WAY_ID)!!.title)
    }

    @Test
    fun `a finished walk with no recorded kind is finalized as a recovery`() = runBlocking {
        val walk = finishedHonorWalk(kind = null)

        h.finalizer.finalize(walk.id)

        assertEquals(HonorFinishKind.RECOVERED, dao.getMarker(walk.uuid)!!.finishKind)
        assertNull(h.store.wayLink(walk.uuid))
    }

    // Failure and retry

    @Test
    fun `a link that fails leaves the live rows, and the launch retry completes the step`() = runBlocking {
        val walk = finishedHonorWalk(HonorFinishKind.CLEAN)
        val links = File(folder.root, "Ways/links").apply {
            parentFile!!.mkdirs()
            writeText("not a folder")
        }

        assertEquals(HonorFinalizeOutcome.PENDING, h.finalizer.finalize(walk.id))
        assertNotNull("the numbers wait in the session row", dao.getSession(walk.id)!!.arrivalTheirSeconds)
        assertNull(dao.getMarker(walk.uuid))

        links.delete()
        h.finalizer.runAtLaunch()

        assertEquals(WayLink(WAY_ID, 600.0, 540.0), h.store.wayLink(walk.uuid))
        assertNotNull(dao.getMarker(walk.uuid))
        assertNoLiveRows(walk)
    }

    @Test
    fun `the repository's hook reports a failed step instead of throwing, and the walk stays finished`() = runBlocking {
        val walk = finishedHonorWalk(HonorFinishKind.CLEAN)
        File(folder.root, "Ways/links").apply {
            parentFile!!.mkdirs()
            writeText("not a folder")
        }

        assertEquals(HonorFinalizeOutcome.PENDING, h.repository.runHonorFinalize(walk.id))
        assertNotNull(h.repository.getWalk(walk.id)!!.endTimestamp)
    }

    @Test
    fun `a walk still on is not finalized`() = runBlocking {
        val walk = finishedHonorWalk(kind = null, endTimestamp = null)

        assertEquals(HonorFinalizeOutcome.NOT_FINISHED, h.finalizer.finalize(walk.id))

        assertNotNull(dao.getSession(walk.id))
        assertNull(h.store.wayLink(walk.uuid))
    }

    @Test
    fun `a walk with nothing live, or gone, is done`() = runBlocking {
        val walk = finishedHonorWalk(HonorFinishKind.CLEAN)
        h.db.walkDao().deleteById(walk.id)

        assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(walk.id))
        assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(walkId = 424_242L))
    }

    // The staging sweep

    private fun stagingDir(uuid: String) = File(folder.root, "Ways/staging/$uuid")

    private fun age(dir: File) {
        val old = h.clock.millis - HonorFinalizer.STAGING_GRACE_MILLIS - 1_000
        dir.walkTopDown().forEach { it.setLastModified(old) }
    }

    @Test
    fun `the sweep removes old staging nothing needs, and keeps the rest`() = runBlocking {
        val orphanOld = UUID.randomUUID().toString().also { h.store.stage(it, way); age(stagingDir(it)) }
        val orphanYoung = UUID.randomUUID().toString().also { h.store.stage(it, way) }
        val live = UUID.randomUUID().toString().also {
            h.db.walkDao().insert(Walk(uuid = it, startTimestamp = 1_000L))
            h.store.stage(it, way)
            age(stagingDir(it))
        }
        val recovered = UUID.randomUUID().toString().also {
            h.db.walkDao().insert(Walk(uuid = it, startTimestamp = 1_000L, endTimestamp = 2_000L))
            h.store.stage(it, way)
            age(stagingDir(it))
        }
        val pending = finishedHonorWalk(HonorFinishKind.CLEAN).also { age(stagingDir(it.uuid)) }
        val halfWritten = UUID.randomUUID().toString().also {
            stagingDir(it).mkdirs()
            File(stagingDir(it), ".way.json.${UUID.randomUUID()}.tmp").writeText("{")
            age(stagingDir(it))
        }

        assertEquals(3, h.finalizer.sweepStaging())

        assertFalse(stagingDir(orphanOld).exists())
        assertFalse(stagingDir(recovered).exists())
        assertFalse(stagingDir(halfWritten).exists())
        assertNotNull(h.store.staged(orphanYoung))
        assertNotNull(h.store.staged(live))
        assertNotNull("a pending step still needs it", h.store.staged(pending.uuid))
    }

    @Test
    fun `the sweep clears old temp files a killed write left, and spares young ones`() = runBlocking {
        h.store.link(UUID.randomUUID().toString(), WAY_ID, arrival = null)
        val linksDir = File(folder.root, "Ways/links")
        val stale = File(linksDir, ".x.json.${UUID.randomUUID()}.tmp").apply { writeText("{") }
        age(stale)
        val inFlight = File(linksDir, ".y.json.${UUID.randomUUID()}.tmp").apply { writeText("{") }

        h.finalizer.sweepStaging()

        assertFalse(stale.exists())
        assertTrue(inFlight.exists())
    }
}
