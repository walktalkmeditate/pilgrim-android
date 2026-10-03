// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import android.database.sqlite.SQLiteException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.WayArrival
import org.walktalkmeditate.pilgrim.data.honor.WayLink
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.sensor.fakeStepCounter
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.WAY_ID
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.fix

/** The Honor parts of the tracker's controller: the start mutex, the flag, arrival's compare-and-set, and the finish kind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkControllerHonorTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: HonorHarness

    @Before
    fun setUp() {
        h = HonorHarness(folder.root)
    }

    @After
    fun tearDown() {
        h.close()
    }

    private fun honorRequest(uuid: String, wayId: String = WAY_ID) = WalkStartRequest(
        intention = "for my father",
        mode = WalkMode.Honor,
        walkUuid = uuid,
        honor = HonorStart(wayId, HonorSettings(voicesEnabled = false, softTapEnabled = false)),
    )

    @Test
    fun `an Honor start writes one HONOR_MODE at the start instant and one session row, under the minted uuid`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        h.store.stage(uuid, HonorHarness.way())

        val walk = h.controller.startWalk(honorRequest(uuid))

        assertEquals("the walk row carries Begin's uuid", uuid, h.repository.getWalk(walk.id)!!.uuid)
        val markers = h.repository.eventsFor(walk.id).filter { it.eventType == WalkEventType.HONOR_MODE }
        assertEquals(listOf(walk.startTimestamp), markers.map { it.timestamp })
        val session = h.db.honorDao().getSession(walk.id)!!
        assertEquals(WAY_ID, session.wayId)
        assertEquals(HonorSourceKind.OWN_WALK, session.sourceKind)
        assertFalse("the settings snapshot rides the row", session.voicesEnabled)
        assertEquals(HonorPhase.WALKING, session.phase)
        assertEquals(WalkMode.Honor, (h.controller.state.value as WalkState.Active).walk.mode)
    }

    @Test
    fun `with the release flag off an Honor start is a wander, with no marker and no session`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        h.store.stage(uuid, HonorHarness.way())
        val flagOff = h.newController(honorEnabled = false)

        val walk = flagOff.startWalk(honorRequest(uuid))

        assertTrue(h.repository.eventsFor(walk.id).isEmpty())
        assertNull(h.db.honorDao().getSession(walk.id))
        assertEquals(WalkMode.Wander, (flagOff.state.value as WalkState.Active).walk.mode)
    }

    @Test
    fun `Honor mode without a Way is a wander, as iOS writes no marker without one`() = runBlocking {
        val walk = h.controller.startWalk(intention = null, mode = WalkMode.Honor)

        assertTrue(h.repository.eventsFor(walk.id).isEmpty())
        assertEquals(WalkMode.Wander, (h.controller.state.value as WalkState.Active).walk.mode)
    }

    @Test
    fun `a Way that doesn't load refuses the start before any row`() = runBlocking {
        val uuid = UUID.randomUUID().toString()

        assertThrows(IllegalStateException::class.java) { runBlocking { h.controller.startWalk(honorRequest(uuid)) } }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { h.controller.startWalk(honorRequest(uuid, wayId = "walk:../../../etc/passwd-0000000000000")) }
        }

        assertTrue(h.repository.allWalks().isEmpty())
        assertTrue(h.controller.state.value is WalkState.Idle)
    }

    @Test
    fun `a replayed start whose uuid already has a walk is refused without a second row`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        h.store.stage(uuid, HonorHarness.way())
        h.controller.startWalk(honorRequest(uuid))
        h.controller.finishWalk()

        assertThrows(IllegalStateException::class.java) { runBlocking { h.controller.startWalk(honorRequest(uuid)) } }

        assertEquals(1, h.repository.allWalks().size)
    }

    @Test
    fun `a failed marker write starts the walk as a wander with neither marker nor session`() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        h.store.stage(uuid, HonorHarness.way())
        val failingEvents = FailingEventDao(h.db.walkEventDao())
        val fragile = WalkControllerImpl(
            WalkRepository(
                database = h.db,
                walkDao = h.db.walkDao(),
                routeDao = h.db.routeDataSampleDao(),
                altitudeDao = h.db.altitudeSampleDao(),
                walkEventDao = failingEvents,
                activityIntervalDao = h.db.activityIntervalDao(),
                waypointDao = h.db.waypointDao(),
                voiceRecordingDao = h.db.voiceRecordingDao(),
                walkPhotoDao = h.db.walkPhotoDao(),
                wayStore = h.store,
            ),
            h.clock,
            fakeStepCounter(),
            FixedReleaseFlags(honor = true),
        )

        val walk = fragile.startWalk(honorRequest(uuid))

        assertNull(h.db.honorDao().getSession(walk.id))
        assertTrue(h.repository.eventsFor(walk.id).isEmpty())
        assertEquals(WalkMode.Wander, (fragile.state.value as WalkState.Active).walk.mode)
    }

    @Test
    fun `arrival flips the phase once and writes the event, then the reserved waypoint at the arrival fix`() = runBlocking {
        val walk = h.startHonorWalk()
        val arrival = WayArrival(theirSeconds = 600.0, yourSeconds = 540.0)

        val label = "Walked their way: Morning loop"
        assertTrue(h.controller.recordHonorArrival(walk.id, arrival, 1_050.0, label, fix(0.01, 5_000L)))
        assertFalse(
            "a second arrival is refused",
            h.controller.recordHonorArrival(walk.id, arrival, 1_090.0, label, fix(0.01, 6_000L)),
        )

        assertEquals(1, h.repository.eventsFor(walk.id).count { it.eventType == WalkEventType.HONOR_ARRIVAL })
        val waypoint = h.repository.waypointsFor(walk.id).single()
        assertEquals(HonorPersistence.ARRIVAL_WAYPOINT_ICON, waypoint.icon)
        assertEquals("Walked their way: Morning loop", waypoint.label)
        assertEquals(0.01, waypoint.longitude, 0.0)
        val session = h.db.honorDao().getSession(walk.id)!!
        assertEquals(HonorPhase.ARRIVED, session.phase)
        assertEquals(600.0, session.arrivalTheirSeconds!!, 0.0)
        assertEquals(540.0, session.arrivalYourSeconds!!, 0.0)
        assertEquals("the first arrival's walked metres are kept", 1_050.0, session.arrivalWalkedMeters!!, 0.0)
    }

    @Test
    fun `arrival with no fix yet still writes the event and skips the waypoint`() = runBlocking {
        val walk = h.startHonorWalk()

        assertTrue(h.controller.recordHonorArrival(walk.id, WayArrival(1.0, 1.0), 1.0, "label", at = null))

        assertEquals(1, h.repository.eventsFor(walk.id).count { it.eventType == WalkEventType.HONOR_ARRIVAL })
        assertTrue(h.repository.waypointsFor(walk.id).isEmpty())
    }

    @Test
    fun `arrival after the finish is refused and writes nothing`() = runBlocking {
        val walk = h.startHonorWalk()
        h.controller.finishWalk()

        assertFalse(h.controller.recordHonorArrival(walk.id, WayArrival(1.0, 1.0), 1.0, "label", fix(0.01, 1L)))

        assertTrue(h.repository.eventsFor(walk.id).none { it.eventType == WalkEventType.HONOR_ARRIVAL })
        assertTrue(h.repository.waypointsFor(walk.id).isEmpty())
    }

    @Test
    fun `a clean finish runs the Honor step before the state flips, keyed by the walk's uuid`() = runBlocking {
        val walk = h.startHonorWalk()
        h.controller.recordHonorArrival(walk.id, WayArrival(600.0, 540.0), 1_000.0, "label", fix(0.01, 1L))

        h.controller.finishWalk()

        assertEquals(WayLink(WAY_ID, 600.0, 540.0), h.store.wayLink(walk.uuid))
        assertEquals(HonorFinishKind.CLEAN, h.db.honorDao().getMarker(walk.uuid)!!.finishKind)
        assertNotNull("the own-walk Way is listed", h.store.load(WAY_ID))
        assertNull("the staging went with the promotion", h.store.staged(walk.uuid))
        assertNull("no live rows", h.db.honorDao().getSession(walk.id))
        assertTrue(h.controller.state.value is WalkState.Finished)
    }

    @Test
    fun `a failing Honor step never stops the walk ending, and the launch retry completes it`() = runBlocking {
        val walk = h.startHonorWalk()
        h.controller.recordHonorArrival(walk.id, WayArrival(600.0, 540.0), 1_000.0, "label", fix(0.01, 1L))
        val blocker = File(folder.root, "Ways/links").apply { writeText("a file where the links folder goes") }

        h.controller.finishWalk()

        assertTrue(h.controller.state.value is WalkState.Finished)
        assertNotNull(h.repository.getWalk(walk.id)!!.endTimestamp)
        assertNotNull("the live rows wait for the retry", h.db.honorDao().getSession(walk.id))
        assertNull(h.db.honorDao().getMarker(walk.uuid))

        blocker.delete()
        h.finalizer.runAtLaunch()

        assertEquals(WayLink(WAY_ID, 600.0, 540.0), h.store.wayLink(walk.uuid))
        assertEquals(HonorFinishKind.CLEAN, h.db.honorDao().getMarker(walk.uuid)!!.finishKind)
        assertNull(h.db.honorDao().getSession(walk.id))
    }

    @Test
    fun `the tracker's own recovery records a recovered finish`() = runBlocking {
        val walk = h.startHonorWalk()
        h.controller.recordHonorArrival(walk.id, WayArrival(600.0, 540.0), 1_000.0, "label", fix(0.01, 1L))
        val revived = h.newController()

        revived.recoverStaleWalks()

        assertEquals(HonorFinishKind.RECOVERED, h.db.honorDao().getMarker(walk.uuid)!!.finishKind)
        assertNull("the Way was never listed, so no link", h.store.wayLink(walk.uuid))
        assertNotNull("the staging stays for the launch sweep", h.store.staged(walk.uuid))
    }

    @Test
    fun `the finish kind's default is recovery, and the first kind recorded wins`() = runBlocking {
        val walk = h.startHonorWalk()

        h.repository.finishWalkAtomic(walk.id, endTimestamp = 9_000L)
        h.repository.finishWalkAtomic(walk.id, endTimestamp = 9_000L, finishKind = HonorFinishKind.CLEAN)

        assertEquals(HonorFinishKind.RECOVERED, h.db.honorDao().getSession(walk.id)!!.finishKind)
    }
}

private class FailingEventDao(private val delegate: WalkEventDao) : WalkEventDao {
    override suspend fun insert(event: WalkEvent): Long = throw SQLiteException("disk I/O error")
    override suspend fun getForWalk(walkId: Long) = delegate.getForWalk(walkId)
    override fun observeForWalk(walkId: Long) = delegate.observeForWalk(walkId)
    override suspend fun walkIdsWithEvent(eventTypeName: String) = delegate.walkIdsWithEvent(eventTypeName)
    override suspend fun deleteByWalkId(walkId: Long) = delegate.deleteByWalkId(walkId)
}
