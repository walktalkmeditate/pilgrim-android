// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import android.app.Application
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
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
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.FakeWalkSignals
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.HonorStageOutcome
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogService
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedgerStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.awaitBlocking
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageStageIdentity
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.honor.WaySweeper
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.STAGE_ID
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.WAY_ID
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.fix

/** The Honor step after the walk's finish is recorded, a stage's ledger record, its retry at launch, and the staging sweep. */
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
        staged: Way = way,
        sourceKind: HonorSourceKind = HonorSourceKind.OWN_WALK,
    ): Walk {
        val uuid = UUID.randomUUID().toString()
        val id = h.db.walkDao().insert(Walk(uuid = uuid, startTimestamp = 1_000L, endTimestamp = endTimestamp))
        h.db.walkEventDao().insert(WalkEvent(walkId = id, timestamp = 1_000L, eventType = WalkEventType.HONOR_MODE))
        dao.insertSession(
            HonorSessionEntity(
                walkId = id,
                wayId = staged.id,
                sourceKind = sourceKind,
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
        h.store.stage(uuid, staged)
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

    // A pilgrimage stage's ledger record (pilgrimage-stage spec P2 §9–§10, C-7, C-17; P3 §10.2)

    private val stage = HonorHarness.stage()

    /**
     * A stage walk as `finishWalkAtomic` leaves it: its row naming the stage
     * as Start recorded it and holding the engine's last word ([outcome]
     * null when it never anchored), its copy staged at Begin, and no package
     * installed unless the test saves one.
     */
    private suspend fun finishedStageWalk(
        kind: HonorFinishKind = HonorFinishKind.CLEAN,
        outcome: HonorStageOutcome? = HonorStageOutcome(progressFrac = 0.5, arrived = false),
        endTimestamp: Long = 9_000L,
        routeId: String = "camino-frances",
    ): Walk {
        val uuid = UUID.randomUUID().toString()
        val id = h.db.walkDao().insert(Walk(uuid = uuid, startTimestamp = 1_000L, endTimestamp = endTimestamp))
        dao.insertSession(
            HonorSessionEntity(
                walkId = id,
                wayId = STAGE_ID,
                sourceKind = HonorSourceKind.PILGRIMAGE,
                voicesEnabled = true,
                softTapEnabled = false,
                phase = if (outcome?.arrived == true) HonorPhase.ARRIVED else HonorPhase.WALKING,
                startFrac = outcome?.let { 0.1 },
                progressFrac = outcome?.progressFrac ?: 0.0,
                finishKind = kind,
                stageRouteId = routeId,
                stageIndex = 4,
                stageName = "Larrasoaña to Pamplona",
                stageDistanceKm = 15.6,
            ),
        )
        h.store.stage(uuid, stage)
        return h.db.walkDao().getById(id)!!
    }

    private val ledgerFile get() = File(folder.root, "Ways/pilgrimage/camino-frances/ledger.json")

    /** A folder where the ledger's file goes, so its write fails (U33's `a failed write throws`). */
    private fun blockTheLedger(): File = File(ledgerFile, "in-the-way").apply {
        parentFile!!.mkdirs()
        writeText("x")
    }

    private fun entry(): PilgrimageLedger.Entry? = h.ledgers.load("camino-frances")?.stages?.get("4")

    private suspend fun HonorSession.beginAt(walk: Walk, lon: Double, lat: Double = 0.0, scope: CoroutineScope = h.serviceScope) =
        start(scope, walk.id, h.controller.state, fix(lon, h.clock.millis, lat = lat)).also { awaitIdle() }

    private suspend fun HonorSession.walkTo(vararg lons: Double, lat: Double = 0.0) {
        for (lon in lons) {
            h.clock.millis += 1_000
            onFix(fix(lon, h.clock.millis, lat = lat))
        }
        awaitIdle()
    }

    @Test
    fun `a clean stage finish links, then records the stage its row took at Start, with no package to read`() = runBlocking {
        val walk = finishedStageWalk()

        assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(walk.id))

        assertEquals(WayLink(STAGE_ID), h.store.wayLink(walk.uuid))
        assertEquals(
            PilgrimageLedger.Entry(
                name = "Larrasoaña to Pamplona",
                distanceKm = 15.6,
                walkedAt = Instant.ofEpochSecond(9),
                kmWalked = 15.6 * 0.5,
                completed = false,
                stoppedAtFrac = 0.5,
            ),
            entry(),
        )
        assertNotNull(dao.getMarker(walk.uuid))
        assertNoLiveRows(walk)
        assertNull("a package Way is never listed at walk end", h.store.load(STAGE_ID))
    }

    @Test
    fun `an arrived stage is recorded completed, credited its whole distance`() = runBlocking {
        val walk = finishedStageWalk(outcome = HonorStageOutcome(progressFrac = 0.97, arrived = true))

        h.finalizer.finalize(walk.id)

        assertEquals(Triple(true, 15.6, null), entry()!!.let { Triple(it.completed, it.kmWalked, it.stoppedAtFrac) })
    }

    @Test
    fun `AE8 a stage begun more than 60 m away and never anchored writes no ledger entry, but the walk is linked`() =
        runBlocking {
            h.store.save(stage)
            val walk = h.startHonorWalk(stage)
            val session = h.newSession(FakePorts())
            session.beginAt(walk, lon = 0.0, lat = A_KILOMETRE_NORTH)
            session.walkTo(0.001, 0.002, 0.003, 0.004, lat = A_KILOMETRE_NORTH)
            assertNull("an approach, not a stage walked", dao.getSession(walk.id)!!.stageOutcome())
            assertTrue("no water caption", dao.getNotices(walk.id).isEmpty())
            assertTrue("no place reached, so no card", dao.getMomentStates(walk.id).none { it.reachedAt != null })

            h.controller.finishWalk()
            session.awaitIdle()

            assertEquals(WayLink(STAGE_ID), h.store.wayLink(walk.uuid))
            assertNotNull(dao.getMarker(walk.uuid))
            assertTrue("no arrival", h.repository.eventsFor(walk.id).none { it.eventType == WalkEventType.HONOR_ARRIVAL })
            assertNull(h.ledgers.load("camino-frances"))
            assertFalse("nor any ledger folder", ledgerFile.parentFile!!.exists())
        }

    @Test
    fun `AE8 a stage anchored midway, then ended early with the UI process dead, offers to continue from where it stopped`() =
        runBlocking {
            (0..3).forEach { index ->
                h.ledgers.record(
                    PilgrimageStageIdentity("camino-frances", index, "stage $index", 20.0),
                    HonorStageOutcome(progressFrac = 1.0, arrived = true),
                    Instant.ofEpochSecond(1_600_000_000L + index * 86_400L),
                )
            }
            h.store.save(stage)
            val walk = h.startHonorWalk(stage)
            val session = h.newSession(FakePorts())
            session.beginAt(walk, lon = 0.003)
            session.walkTo(0.004, 0.005)

            // The notification's Finish, handled in `:tracker` alone.
            h.controller.finishWalk()
            session.awaitIdle()

            val next = h.ledgers.load("camino-frances")!!.next(stageCount = 33)!!
            assertEquals(4, next.index)
            assertEquals("a stop the route page reads as \"continue from where you stopped\"", 0.5, next.resumeFrac!!, 0.01)
        }

    @Test
    fun `a tracker killed after finishWalkAtomic leaves the record to the next launch's retry, which writes it once`() =
        runBlocking {
            h.store.save(stage)
            val walk = h.startHonorWalk(stage)
            val trackerProcess = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val session = h.newSession(FakePorts())
            session.beginAt(walk, lon = 0.005, scope = trackerProcess)
            // Finish's first write lands, then the kill, before any Honor step.
            h.repository.finishWalkAtomic(walk.id, endTimestamp = h.clock.millis, finishKind = HonorFinishKind.CLEAN)
            trackerProcess.coroutineContext[Job]!!.cancelAndJoin()
            assertNull(h.ledgers.load("camino-frances"))

            h.finalizer.runAtLaunch()
            val recorded = ledgerFile.readText()
            ledgerFile.setLastModified(LONG_AGO_MILLIS)
            h.finalizer.runAtLaunch()
            h.finalizer.finalize(walk.id)

            assertEquals(0.5, entry()!!.stoppedAtFrac!!, 0.01)
            assertNotNull(dao.getMarker(walk.uuid))
            assertNull(dao.getSession(walk.id))
            assertEquals("no second write", LONG_AGO_MILLIS to recorded, ledgerFile.lastModified() to ledgerFile.readText())
        }

    /** A kill after the record but before the marker: the retry records again, and the file comes out the same. */
    @Test
    fun `a record that landed without its marker is replayed by the launch retry to the same bytes`() = runBlocking {
        val walk = finishedStageWalk()
        val row = dao.getSession(walk.id)!!
        h.ledgers.record(row.stageIdentity()!!, row.stageOutcome(), Instant.ofEpochMilli(walk.endTimestamp!!))
        val recorded = ledgerFile.readText()

        h.finalizer.runAtLaunch()

        assertNotNull(dao.getMarker(walk.uuid))
        assertNoLiveRows(walk)
        assertEquals("the replay writes what the first record wrote", recorded, ledgerFile.readText())
    }

    @Test
    fun `a failed ledger write leaves no marker, keeps the outcome and the staged copy, and the launch retry records it`() =
        runBlocking {
            val walk = finishedStageWalk()
            val blocked = blockTheLedger()

            assertEquals(HonorFinalizeOutcome.PENDING, h.finalizer.finalize(walk.id))
            assertNull(dao.getMarker(walk.uuid))
            assertEquals(HonorStageOutcome(0.5, arrived = false), dao.getSession(walk.id)!!.stageOutcome())
            assertEquals("the link lands first", WayLink(STAGE_ID), h.store.wayLink(walk.uuid))
            assertNotNull(h.store.staged(walk.uuid))

            blocked.parentFile!!.deleteRecursively()
            h.finalizer.runAtLaunch()

            assertEquals(0.5, entry()!!.stoppedAtFrac!!, 0.0)
            assertNotNull(dao.getMarker(walk.uuid))
            assertNoLiveRows(walk)
            assertNull(h.store.staged(walk.uuid))
        }

    @Test
    fun `a stage's record is dated the walk's end, never the clock`() = runBlocking {
        h.clock.millis = 1_800_000_000_000L
        val walk = finishedStageWalk(endTimestamp = 1_700_000_123_456L)

        h.finalizer.finalize(walk.id)

        assertEquals(Instant.ofEpochSecond(1_700_000_123L), entry()!!.walkedAt)
    }

    @Test
    fun `a record the store can never write counts as done`() = runBlocking {
        val walk = finishedStageWalk(routeId = "Camino Francés")

        assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(walk.id))

        assertNotNull(dao.getMarker(walk.uuid))
        assertNoLiveRows(walk)
        assertFalse(File(folder.root, "Ways/pilgrimage").exists())
    }

    @Test
    fun `a recovered stage walk is linked and recorded only when its stage still loads`() = runBlocking {
        val gone = finishedStageWalk(kind = HonorFinishKind.RECOVERED)
        assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(gone.id))
        assertNull(h.store.wayLink(gone.uuid))
        assertNull(h.ledgers.load("camino-frances"))
        assertNull("its staged copy is no stand-in for the package", h.store.staged(gone.uuid))

        h.store.save(stage)
        val kept = finishedStageWalk(kind = HonorFinishKind.RECOVERED, outcome = HonorStageOutcome(0.4, arrived = false))
        h.finalizer.finalize(kept.id)

        assertEquals(WayLink(STAGE_ID), h.store.wayLink(kept.uuid))
        assertEquals(0.4, entry()!!.stoppedAtFrac!!, 0.0)
        assertEquals(HonorFinishKind.RECOVERED, dao.getMarker(kept.uuid)!!.finishKind)
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

    // Owner decision 2 of the pilgrimage-stage spec: the copy Begin staged is discarded, never promoted.
    @Test
    fun `a stage walk's staged copy is discarded at its finish, clean or recovered, and never listed over its package`() =
        runBlocking {
            h.store.save(HonorHarness.stage())
            val packageWay = File(folder.root, "Ways/$STAGE_ID/way.json")
            val installed = packageWay.readText()
            val atTheDoor = HonorHarness.stage(title = "as it stood at Begin")
            val clean = finishedHonorWalk(HonorFinishKind.CLEAN, staged = atTheDoor, sourceKind = HonorSourceKind.PILGRIMAGE)
            val recovered = finishedHonorWalk(HonorFinishKind.RECOVERED, staged = atTheDoor, sourceKind = HonorSourceKind.PILGRIMAGE)

            h.finalizer.finalize(clean.id)
            h.finalizer.finalize(recovered.id)

            assertEquals(WayLink(STAGE_ID, 600.0, 540.0), h.store.wayLink(clean.uuid))
            assertEquals(WayLink(STAGE_ID), h.store.wayLink(recovered.uuid))
            assertEquals("the package's own file", installed, packageWay.readText())
            assertFalse(stagingDir(clean.uuid).exists())
            assertFalse(stagingDir(recovered.uuid).exists())
        }

    @Test
    fun `the sweep holds a stage walk's staging while its step is pending, as it holds an own walk's`() = runBlocking {
        val walk = finishedStageWalk()
        blockTheLedger()
        h.finalizer.finalize(walk.id)
        age(stagingDir(walk.uuid))

        assertEquals(0, h.finalizer.sweepStaging())

        assertNotNull(h.store.staged(walk.uuid))
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

    @Test
    fun `the sweep clears a killed write's temp inside staging a walk still needs, and a stale media download`() =
        runBlocking {
            val live = UUID.randomUUID().toString().also {
                h.db.walkDao().insert(Walk(uuid = it, startTimestamp = 1_000L))
                h.store.stage(it, way)
            }
            val stagingTemp = File(stagingDir(live), ".way.json.${UUID.randomUUID()}.tmp").apply { writeText("{") }
            age(stagingTemp)
            h.store.save(expiredShare())
            val stalePartial = h.store.mediaPartialFile(SHARE_ID, "audio/1.m4a")!!.apply { writeBytes(byteArrayOf(1)) }
            age(stalePartial)
            val gathering = h.store.mediaPartialFile(SHARE_ID, "audio/2.m4a")!!.apply { writeBytes(byteArrayOf(2)) }

            h.finalizer.sweepStaging()

            assertFalse(stagingTemp.exists())
            assertNotNull("the walk's staged Way stays", h.store.staged(live))
            assertFalse(stalePartial.exists())
            assertTrue("a download in flight is spared", gathering.exists())
        }

    // The expiry sweep at launch (shared-walk spec S3 §14, correction 10)

    private fun expiredShare() = HonorHarness.way().copy(
        id = SHARE_ID,
        source = WaySource.Share(id = SHARE_ID.removePrefix("share:"), pageUrl = "https://walk.pilgrimapp.org/x"),
        expires = Instant.ofEpochMilli(h.clock.millis - 1),
    )

    private fun shareMedia() = File(folder.root, "Ways/$SHARE_ID/media/audio/1.m4a")

    private fun sweeper(swept: MutableList<String>, liveRowsWhenSwept: MutableList<Int>) = WaySweeper(
        store = h.store,
        heldWayIds = {
            liveRowsWhenSwept += dao.liveSessionWayIds().size
            dao.liveSessionWayIds().toSet()
        },
        cancelGather = { swept += it },
        clock = h.clock,
        ioDispatcher = Dispatchers.IO,
    )

    @Test
    fun `the launch runs the expiry sweep after recovery's links, so a crash-recovered honoring keeps its Way`() =
        runBlocking {
            h.store.save(expiredShare())
            shareMedia().apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))
            val uuid = UUID.randomUUID().toString()
            val walkId = h.db.walkDao().insert(Walk(uuid = uuid, startTimestamp = 1_000L, endTimestamp = 9_000L))
            dao.insertSession(
                HonorSessionEntity(
                    walkId = walkId,
                    wayId = SHARE_ID,
                    sourceKind = HonorSourceKind.SHARE,
                    voicesEnabled = true,
                    softTapEnabled = false,
                    finishKind = HonorFinishKind.RECOVERED,
                ),
            )
            val swept = mutableListOf<String>()
            val liveRowsWhenSwept = mutableListOf<Int>()
            val sweeper = sweeper(swept, liveRowsWhenSwept)
            val finalizer = HonorFinalizer(h.db, h.store, h.clock, Dispatchers.IO, expirySweep = { sweeper.sweep() })

            finalizer.runAtLaunch()

            assertEquals("the sweep ran once the step had cleared the live rows", listOf(0), liveRowsWhenSwept)
            assertEquals(WayLink(SHARE_ID), h.store.wayLink(uuid))
            assertNotNull("walked, so its way.json stays", h.store.load(SHARE_ID))
            assertFalse("and only its media goes", shareMedia().exists())
            assertEquals("its gather is cancelled", listOf(SHARE_ID), swept)
        }

    // S3 §13 trigger 1: iOS sweeps on every launch, whatever else its launch did.
    @Test
    fun `the launch's expiry sweep runs even when the steps before it fail`() = runBlocking {
        var swept = 0
        // The staging sweep's first touch of the store throws.
        val unreadable = WayStore({ throw IOException("the store's folder is unreadable") })
        val finalizer = HonorFinalizer(h.db, unreadable, h.clock, Dispatchers.IO, expirySweep = { swept++ })

        finalizer.runAtLaunch()

        assertEquals(1, swept)
    }

    @Test
    fun `a failing expiry sweep is deferred to the next launch, not thrown`() = runBlocking {
        val finalizer = HonorFinalizer(h.db, h.store, h.clock, Dispatchers.IO, expirySweep = { error("the store is unreadable") })

        finalizer.runAtLaunch()
    }

    @Test
    fun `a sweep while the UI restarts mid-walk leaves the live walk's Way whole, media and all`() = runBlocking {
        h.store.save(expiredShare())
        shareMedia().apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))
        val walkId = h.db.walkDao().insert(Walk(uuid = UUID.randomUUID().toString(), startTimestamp = 1_000L))
        dao.insertSession(
            HonorSessionEntity(
                walkId = walkId,
                wayId = SHARE_ID,
                sourceKind = HonorSourceKind.SHARE,
                voicesEnabled = true,
                softTapEnabled = false,
            ),
        )
        val swept = mutableListOf<String>()

        assertTrue(sweeper(swept, mutableListOf()).sweep().isEmpty())

        assertTrue(shareMedia().exists())
        assertNotNull(h.store.load(SHARE_ID))
        assertTrue(swept.isEmpty())
    }

    // The pilgrimage packages' launch work (pilgrimage-stage spec P2 §12, gaps 9 and 14)

    @Test
    fun `the launch runs the package work after the pending steps, so their records land before any stage retires`() =
        runBlocking {
            val walk = finishedHonorWalk(HonorFinishKind.CLEAN)
            val liveRowsAtPackages = mutableListOf<Int>()
            val finalizer = HonorFinalizer(
                h.db,
                h.store,
                h.clock,
                Dispatchers.IO,
                packageLaunchWork = { liveRowsAtPackages += dao.liveSessionWayIds().size },
            )

            finalizer.runAtLaunch()

            assertEquals("the package work ran once the step had cleared the live rows", listOf(0), liveRowsAtPackages)
            assertNotNull(dao.getMarker(walk.uuid))
        }

    @Test
    fun `the launch's package work runs even when the steps before it fail, and its own failure is deferred`() = runBlocking {
        val ran = mutableListOf<String>()
        val unreadable = WayStore({ throw IOException("the store's folder is unreadable") })
        val finalizer = HonorFinalizer(
            h.db,
            unreadable,
            h.clock,
            Dispatchers.IO,
            expirySweep = { ran += "expiry sweep" },
            packageLaunchWork = {
                ran += "packages"
                error("the package folder is unreadable")
            },
        )

        finalizer.runAtLaunch()

        assertEquals(listOf("packages", "expiry sweep"), ran)
    }

    /**
     * UI process only: `:tracker` finalizes but never launches, and with the
     * flag off nothing builds the package manager or the tiles manager, whose
     * loader is the only code that opens Mapbox's store (spec D C3 §11).
     */
    @Test
    fun `the package and tiles managers are built only by a launch with the release flag on, never by the tracker's finalize`() =
        runBlocking {
            val tempRoot = File(folder.root, "pilgrimage-tmp")
            val killedDownload = File(tempRoot, "pilgrimage-killed").apply { mkdirs() }
            val built = AtomicInteger()
            val tilesBuilt = AtomicInteger()
            val tiles = PilgrimageTilesHarness()
            val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            fun finalizer(honor: Boolean) = HonorFinalizer(
                h.db,
                h.store,
                h.clock,
                ledgers = h.ledgers,
                waySweeper = Provider { noSweep() },
                releaseFlags = FixedReleaseFlags(honor),
                packageManager = Provider {
                    built.incrementAndGet()
                    packageManager(tempRoot, managerScope)
                },
                tilesManager = Provider {
                    tilesBuilt.incrementAndGet()
                    tiles.manager
                },
            )

            finalizer(honor = false).runAtLaunch()
            assertEquals("flag off", 0, built.get())
            assertEquals("flag off, no tiles", 0, tilesBuilt.get())
            assertTrue(killedDownload.exists())

            val withTheFlag = finalizer(honor = true)
            val walk = finishedHonorWalk(HonorFinishKind.CLEAN)
            withTheFlag.finalize(walk.id)
            withTheFlag.finalizePending()
            assertEquals("the tracker's path", 0, built.get())
            assertEquals("the tracker's path, no tiles", 0, tilesBuilt.get())

            withTheFlag.runAtLaunch()
            assertEquals(1, built.get())
            assertEquals(1, tilesBuilt.get())
            assertFalse("the launch swept the temp set a kill left", killedDownload.exists())
            tiles.close()
            managerScope.cancel()
        }

    // The tiles at launch (spec D §C2.10): the store warmed, then the package read, then the reconcile on its answer

    @Test
    fun `the launch reconciles the saved maps with the route its package read found`() = runBlocking {
        val packages = PilgrimagePackageHarness(File(folder.root, "packages"))
        val tiles = PilgrimageTilesHarness()
        try {
            packages.makeManager().download(packages.entry, PilgrimagePackageHarness.RELEASE).awaitBlocking()
            val saved = listOf(0, 1, 2).map { "pilgrimage:camino-frances:$it" } + "pilgrimage:camino-norte:0"
            saved.forEach { tiles.loader.seed(it, corridorHash = "h") }

            launchFinalizer(tiles, packages.makeManager()).runAtLaunch()
            tiles.drain()
            assertTrue("the sweep waits for the store's answer", tiles.loader.removedIds.isEmpty())
            tiles.loader.releaseRegions()

            assertEquals(
                "the installed route keeps its two stages' maps",
                setOf("pilgrimage:camino-frances:2", "pilgrimage:camino-norte:0"),
                tiles.loader.removedIds.toSet(),
            )
        } finally {
            tiles.close()
            packages.close()
        }
    }

    /** The package read is clean and finds nothing, so every saved map goes, as on iOS (spec D D3, matched). */
    @Test
    fun `a launch that finds nothing installed reconciles with nothing, and every saved map goes`() = runBlocking {
        val tiles = PilgrimageTilesHarness()
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            tiles.loader.seed("pilgrimage:camino-frances:0", corridorHash = "h")
            tiles.loader.seed("pilgrimage:camino-norte:3", corridorHash = "h")

            launchFinalizer(tiles, packageManager(File(folder.root, "pilgrimage-tmp"), managerScope)).runAtLaunch()
            tiles.drain()
            tiles.loader.releaseRegions()

            assertEquals(setOf("pilgrimage:camino-frances:0", "pilgrimage:camino-norte:3"), tiles.loader.removedIds.toSet())
        } finally {
            tiles.close()
            managerScope.cancel()
        }
    }

    /** An Android-only path: iOS's launch read can't throw. The store still warms, so "the day" after a restart finds it read. */
    @Test
    fun `a package read that throws skips the reconcile, still warms the store, and the expiry sweep still runs`() = runBlocking {
        val tiles = PilgrimageTilesHarness()
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sweeps = AtomicInteger()
        try {
            tiles.loader.seed("pilgrimage:camino-frances:0", corridorHash = "h")
            val unreadable = WayStore({ throw IOException("the store's folder is unreadable") })
            val throwing = packageManager(File(folder.root, "pilgrimage-tmp"), managerScope, store = unreadable)

            launchFinalizer(tiles, throwing, sweeps = sweeps).runAtLaunch()
            tiles.drain()

            assertEquals("the store's first read was asked for", 1, tiles.loader.firstAnswerRequests)
            assertEquals("no reconcile asked the store", 0, tiles.loader.regionsReadCount)
            tiles.loader.releaseRegions()
            assertTrue("nothing swept", tiles.loader.removedIds.isEmpty())
            assertEquals(1, sweeps.get())
        } finally {
            tiles.close()
            managerScope.cancel()
        }
    }

    /** Through the injected constructor, flag on, as the UI process's launch builds it; [sweeps] counts the expiry sweeps. */
    private fun launchFinalizer(
        tiles: PilgrimageTilesHarness,
        packages: PilgrimagePackageManager,
        sweeps: AtomicInteger = AtomicInteger(),
    ) = HonorFinalizer(
        h.db,
        h.store,
        h.clock,
        ledgers = h.ledgers,
        waySweeper = Provider {
            sweeps.incrementAndGet()
            noSweep()
        },
        releaseFlags = FixedReleaseFlags(honor = true),
        packageManager = Provider { packages },
        tilesManager = Provider { tiles.manager },
    )

    private fun noSweep() = WaySweeper(
        store = h.store,
        heldWayIds = { emptySet() },
        cancelGather = {},
        clock = h.clock,
        ioDispatcher = Dispatchers.IO,
    )

    private fun packageManager(tempRoot: File, scope: CoroutineScope, store: WayStore = h.store): PilgrimagePackageManager {
        val cdn = PilgrimageCatalogService.CDN_ORIGIN.toHttpUrl()
        return PilgrimagePackageManager(
            store = store,
            ledgers = PilgrimageLedgerStore(store),
            client = PilgrimagePackageManager.httpClient(cdn),
            cdn = cdn,
            signals = FakeWalkSignals(),
            resolveTempRoot = { tempRoot },
            scope = scope,
            ioDispatcher = Dispatchers.IO,
        )
    }

    private companion object {
        const val SHARE_ID = "share:Qoi4YmPHLN"

        /** About 1.1 km north of the stage's line, which runs along the equator. */
        const val A_KILOMETRE_NORTH = 0.01

        /** A whole second, so every file system keeps it exactly. */
        const val LONG_AGO_MILLIS = 1_600_000_000_000L
    }
}
