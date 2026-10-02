// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.HonorWalkMarkerEntity
import org.walktalkmeditate.pilgrim.data.honor.WayArrival
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.honor.HonorWalkRecords
import org.walktalkmeditate.pilgrim.ui.design.seals.sealWatermark
import org.walktalkmeditate.pilgrim.walk.honor.HonorFinalizeOutcome
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness

/**
 * The summary's Honor section from the walk's events and its Honor record
 * (parity spec G §2–§3, §10; shared-walk spec S4 §12): what it reads
 * before the Honor step, after it, when it failed, after a re-import, for
 * an imported iOS walk, and for a shared Way stored, swept, and deleted.
 * The record is the real reader over real Room rows, files, and the real
 * finalizer, so "the delta appears when the marker lands" is the step
 * itself landing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorSummaryModelTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: HonorHarness
    private lateinit var records: HonorWalkRecords
    private val resources: Resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    /** Three voices and a waypoint: "3 voices along the way". */
    private val way = HonorHarness.way()
    private val arrival = WayArrival(theirSeconds = 900.0, yourSeconds = 600.0)

    @Before
    fun setUp() {
        h = HonorHarness(folder.root)
        records = HonorWalkRecords(h.db.honorDao(), h.store, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        h.close()
    }

    private suspend fun honorWalk(
        uuid: String = UUID.randomUUID().toString(),
        arrived: Boolean = true,
        extraEvents: List<WalkEventType> = emptyList(),
    ): Walk {
        val id = h.db.walkDao().insert(Walk(uuid = uuid, startTimestamp = 1_000L, endTimestamp = 9_000L))
        (listOf(WalkEventType.HONOR_MODE) + extraEvents).forEach { type ->
            h.db.walkEventDao().insert(WalkEvent(walkId = id, timestamp = 1_000L, eventType = type))
        }
        if (arrived) {
            h.db.walkEventDao().insert(WalkEvent(walkId = id, timestamp = 8_000L, eventType = WalkEventType.HONOR_ARRIVAL))
        }
        return h.db.walkDao().getById(id)!!
    }

    /** The live session row as `finishWalkAtomic` leaves it, before the Honor step runs. */
    private suspend fun finishedSession(
        walk: Walk,
        kind: HonorFinishKind = HonorFinishKind.CLEAN,
        arrival: WayArrival? = this.arrival,
        wayId: String = way.id,
        source: HonorSourceKind = HonorSourceKind.OWN_WALK,
    ) {
        h.db.honorDao().insertSession(
            HonorSessionEntity(
                walkId = walk.id,
                wayId = wayId,
                sourceKind = source,
                voicesEnabled = true,
                softTapEnabled = false,
                phase = if (arrival != null) HonorPhase.ARRIVED else HonorPhase.WALKING,
                arrivalTheirSeconds = arrival?.theirSeconds,
                arrivalYourSeconds = arrival?.yourSeconds,
                finishKind = kind,
            ),
        )
    }

    private suspend fun stateFor(walk: Walk, honorEnabled: Boolean = true): HonorSummaryState? {
        val events = h.db.walkEventDao().getForWalk(walk.id).map { it.eventType }
        return HonorSummaryModel.summaryState(events, honorEnabled, records.record(walk.id, walk.uuid))
    }

    private fun HonorSummaryState.title(): String = HonorSummaryModel.title(resources, data)

    private fun HonorSummaryState.counts(): String? = HonorSummaryModel.countsLine(resources, data)

    private fun HonorSummaryState.delta(): String? =
        data.arrivedBeforeTheirsSeconds?.let { HonorSummaryModel.deltaLine(resources, it) }

    // --- An own walk ---

    @Test
    fun `an own-walk honor with an arrival gives the delta, the voices count, and the replies`() = runBlocking {
        h.store.save(way)
        h.store.setReply(way.id, originN = 1, relativePath = "recordings/a/reply-1.wav")
        h.store.setReply(way.id, originN = 3, relativePath = "recordings/a/reply-3.wav")
        val walk = honorWalk()
        finishedSession(walk)
        h.store.stage(walk.uuid, way)
        assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(walk.id))

        val state = stateFor(walk)!!

        assertEquals("in their steps", HonorSummaryModel.kicker(resources))
        assertEquals("Morning loop", state.title())
        assertEquals(300.0, state.data.arrivedBeforeTheirsSeconds!!, 1e-9)
        assertEquals("they arrived 5 minutes after you", state.delta())
        assertEquals("every voice the Way carries, not the heard ones", 3, state.data.voicesAlongTheWay)
        assertEquals("3 voices along the way · 2 replies", state.counts())
        assertEquals(way.id, state.ghost?.wayId)
    }

    @Test
    fun `a recovered walk has no delta`() = runBlocking {
        h.store.save(way)
        val repeat = honorWalk()
        finishedSession(repeat, kind = HonorFinishKind.RECOVERED)
        h.finalizer.finalize(repeat.id)

        val linked = stateFor(repeat)!!
        assertNull("recovery links with no arrival numbers", linked.data.arrivedBeforeTheirsSeconds)
        assertEquals("Morning loop", linked.title())
        assertNotNull(linked.ghost)
    }

    @Test
    fun `a recovered first honoring reads as a removed way`() = runBlocking {
        val first = honorWalk()
        finishedSession(first, kind = HonorFinishKind.RECOVERED)
        h.store.stage(first.uuid, way)

        val pending = stateFor(first)!!
        assertEquals("a way that has been removed", pending.title())

        h.finalizer.finalize(first.id)
        val linked = stateFor(first)!!
        assertEquals("recovery links only a Way already listed (pilgrim-ios #107)", "a way that has been removed", linked.title())
        assertNull(linked.ghost)
    }

    @Test
    fun `a deleted Way shows the removed line and no ghost`() = runBlocking {
        h.store.save(way)
        val walk = honorWalk()
        finishedSession(walk)
        h.finalizer.finalize(walk.id)

        h.store.delete(way.id)
        val state = stateFor(walk)!!

        assertEquals("a way that has been removed", state.title())
        assertNull("the delta goes with the link", state.delta())
        assertNull("no Way, no voices; no link, no replies", state.counts())
        assertNull(state.ghost)
    }

    @Test
    fun `before the marker there is no delta, which then appears`() = runBlocking {
        val walk = honorWalk()
        finishedSession(walk)
        h.store.stage(walk.uuid, way)

        records.observe(walk.id, walk.uuid).map { HonorSummaryModel.summaryState(it) }.test(timeout = 10.seconds) {
            var item = awaitItem()
            assertEquals("the title at once, from the staged Way", "Morning loop", item.title())
            assertEquals("3 voices along the way", item.counts())
            assertNotNull("the ghost at once", item.ghost)
            assertNull("no delta before the marker", item.data.arrivedBeforeTheirsSeconds)

            assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(walk.id))
            while (item.data.arrivedBeforeTheirsSeconds == null) {
                item = awaitItem()
                assertEquals("never placeholder copy on the way", "Morning loop", item.title())
            }
            assertEquals("they arrived 5 minutes after you", item.delta())
            assertEquals("3 voices along the way", item.counts())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `with the Honor step failed the section still shows until the retry`() = runBlocking {
        val walk = honorWalk()
        finishedSession(walk)
        h.store.stage(walk.uuid, way)
        val blocker = File(h.store.baseDirectory, "links").apply {
            parentFile!!.mkdirs()
            writeText("not a folder")
        }

        assertEquals(HonorFinalizeOutcome.PENDING, h.finalizer.finalize(walk.id))
        val pending = stateFor(walk)!!
        assertEquals("Morning loop", pending.title())
        assertEquals("3 voices along the way", pending.counts())
        assertNull(pending.delta())

        blocker.delete()
        assertEquals(1, h.finalizer.finalizePending())
        assertEquals("they arrived 5 minutes after you", stateFor(walk)!!.delta())
    }

    @Test
    fun `once the marker lands the link is the record, though the live rows' delete failed`() = runBlocking {
        h.store.save(way)
        val walk = honorWalk()
        finishedSession(walk)
        // The step wrote its link and its marker, then the delete threw.
        h.store.link(walk.uuid, way.id, arrival)
        h.db.honorDao().insertMarker(
            HonorWalkMarkerEntity(walk.uuid, finishedAt = 9_000L, finishKind = HonorFinishKind.CLEAN),
        )

        assertEquals("they arrived 5 minutes after you", stateFor(walk)!!.delta())
        records.observe(walk.id, walk.uuid).test(timeout = 10.seconds) {
            assertEquals(arrival, awaitItem().arrival)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the seals read each walk's Way by the record's rule, decoding a Way once until its file changes`() = runBlocking {
        h.store.save(way)
        val first = honorWalk()
        finishedSession(first)
        h.finalizer.finalize(first.id)
        val repeat = honorWalk()
        finishedSession(repeat)
        h.finalizer.finalize(repeat.id)
        val pending = honorWalk()
        finishedSession(pending)
        h.store.stage(pending.uuid, HonorHarness.way(title = "Staged loop"))
        val plain = h.db.walkDao().getById(
            h.db.walkDao().insert(Walk(uuid = UUID.randomUUID().toString(), startTimestamp = 1_000L, endTimestamp = 9_000L)),
        )!!

        val ways = records.honoredWays(listOf(first, repeat, pending, plain))

        assertEquals("Morning loop", ways[first.id]?.title)
        assertSame("two walks of one Way share one decode", ways[first.id], ways[repeat.id])
        assertEquals("a pending clean finish reads its staged build", "Staged loop", ways[pending.id]?.title)
        assertNull(ways[plain.id])
        assertSame("an unchanged file is not decoded again", ways[first.id], records.honoredWays(listOf(first))[first.id])

        h.store.save(HonorHarness.way(title = "The long evening loop"))
        assertEquals("a rewritten Way is read again", "The long evening loop", records.honoredWays(listOf(first))[first.id]?.title)
        h.store.delete(way.id)
        assertNull("a deleted Way's line goes", records.honoredWays(listOf(first))[first.id])
    }

    @Test
    fun `a web-editor round trip keeps the delta and the ghost`() = runBlocking {
        val walk = honorWalk()
        finishedSession(walk)
        h.store.stage(walk.uuid, way)
        h.finalizer.finalize(walk.id)

        // The tended re-import: the walk row goes and comes back under a new Room id, same uuid.
        h.db.walkDao().deleteById(walk.id)
        val tended = honorWalk(uuid = walk.uuid)
        assertNotEquals(walk.id, tended.id)

        val state = stateFor(tended)!!
        assertEquals("they arrived 5 minutes after you", state.delta())
        assertEquals(way.id, state.ghost?.wayId)
    }

    @Test
    fun `an imported iOS honor walk with no Way shows the removed line with the flag on, and is a plain walk with it off`() = runBlocking {
        val imported = honorWalk(uuid = UUID.randomUUID().toString().uppercase())

        val on = stateFor(imported, honorEnabled = true)!!
        assertEquals("in their steps", HonorSummaryModel.kicker(resources))
        assertEquals("a way that has been removed", on.title())
        assertNull(on.delta())
        assertNull("voices 0, replies 0", on.counts())
        assertNull(on.ghost)

        assertNull("AE12: a plain walk", stateFor(imported, honorEnabled = false))
    }

    @Test
    fun `a walk with no honor event has no section`() = runBlocking {
        val id = h.db.walkDao().insert(Walk(startTimestamp = 1_000L, endTimestamp = 9_000L))
        val wander = h.db.walkDao().getById(id)!!
        assertNull(stateFor(wander))
    }

    @Test
    fun `a walk carrying both seek and honor events still shows the honor section`() = runBlocking {
        val both = honorWalk(extraEvents = listOf(WalkEventType.SEEK_MODE))
        assertNotNull(stateFor(both))
    }

    @Test
    fun `a 163-character Way title gives iOS's arrival label, uncut, and the summary title whole`() = runBlocking {
        // Two places of 80, joined by " → ": the longest title a share can carry (shared spec S1 §5.3).
        val title = "a".repeat(80) + " → " + "b".repeat(80)
        assertEquals(163, title.length)

        val label = HonorPersistence.arrivalWaypointLabel(resources, title)
        assertEquals("Walked their way: $title", label)
        assertEquals("stored uncut, matched as shipped (pilgrim-ios #117)", 181, label.length)

        val long = way.copy(title = title)
        h.store.save(long)
        val walk = honorWalk()
        finishedSession(walk)
        h.finalizer.finalize(walk.id)
        assertEquals(title, stateFor(walk)!!.title())
    }

    // --- A shared walk (shared-walk spec S4 §12.1–§12.2) ---

    private val shareId = "share:AbCdEf1234"

    private fun sharedVoice(n: Int, lon: Double) = WayMoment(
        id = "voice-$n",
        frac = lon / HonorHarness.END_LON,
        at = WayCoordinate(lat = 0.0, lon = lon),
        kind = WayMomentKind.Voice(
            endFrac = lon / HonorHarness.END_LON,
            duration = 20.0,
            kind = VoiceKind.SPOKEN,
            media = WayMedia.File(path = "audio/$n.m4a"),
        ),
    )

    private val sharedWay = Way(
        id = shareId,
        source = WaySource.Share(id = "AbCdEf1234", pageUrl = "https://walk.pilgrimapp.org/AbCdEf1234"),
        title = "Rúa Nova → Praza do Obradoiro",
        departedAt = Instant.ofEpochSecond(1_600_000_000),
        tzIdentifier = "Europe/Madrid",
        expires = Instant.ofEpochSecond(1_700_000_000),
        route = (0..10).map { WayPoint(lat = 0.0, lon = it * 0.001, alt = null, t = it * 60.0) },
        totalDistanceMeters = 1_113.0,
        theirActiveSeconds = 600.0,
        moments = listOf(sharedVoice(1, 0.002), sharedVoice(2, 0.007)),
        weather = null,
    )

    private val walkRoute = (0..10).map { LocationPoint(timestamp = it * 60_000L, latitude = 0.0001, longitude = it * 0.001) }

    /** A shared Way accepted and gathered, then honored to its end with one reply filed on the way. */
    private suspend fun walkedShare(): Walk {
        h.store.save(sharedWay)
        listOf("audio/1.m4a", "audio/2.m4a").forEach { relative ->
            h.store.mediaFile(shareId, relative)!!.apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(32) { 1 })
            }
        }
        h.store.setReply(shareId, originN = 2, relativePath = "recordings/b/reply-2.wav")
        val walk = honorWalk()
        finishedSession(walk, wayId = shareId, source = HonorSourceKind.SHARE)
        assertEquals(HonorFinalizeOutcome.DONE, h.finalizer.finalize(walk.id))
        return walk
    }

    @Test
    fun `a stored shared Way shows its title, delta, counts, and ghost`() = runBlocking {
        val walk = walkedShare()
        val state = stateFor(walk)!!
        assertEquals("Rúa Nova → Praza do Obradoiro", state.title())
        assertEquals("they arrived 5 minutes after you", state.delta())
        assertEquals("2 voices along the way · 1 reply", state.counts())
        assertEquals(shareId, state.ghost?.wayId)
    }

    @Test
    fun `an expired and walked share keeps everything, counting declared voices, and the seal keeps its line`() = runBlocking {
        val walk = walkedShare()
        h.store.deleteMedia(shareId)

        val state = stateFor(walk)!!
        assertEquals("Rúa Nova → Praza do Obradoiro", state.title())
        assertEquals("the link survives the sweep", "they arrived 5 minutes after you", state.delta())
        assertEquals("declared voices, downloaded or not", "2 voices along the way · 1 reply", state.counts())
        assertNotNull(state.ghost)
        val way = records.record(walk.id, walk.uuid).way
        assertNotNull("the seal keeps the Way's line through expiry", sealWatermark(walkRoute, way)?.wayLine)
    }

    @Test
    fun `a share deleted in Settings reads as removed, and the seal loses its line`() = runBlocking {
        val walk = walkedShare()
        h.store.delete(shareId)

        val state = stateFor(walk)!!
        assertEquals("in their steps", HonorSummaryModel.kicker(resources))
        assertEquals("a way that has been removed", state.title())
        assertNull(state.delta())
        assertNull(state.counts())
        assertNull(state.ghost)
        val way = records.record(walk.id, walk.uuid).way
        val watermark = sealWatermark(walkRoute, way)
        assertNotNull("the walk's own line stays", watermark)
        assertNull("the Way's line goes with the link (owner decision 3)", watermark!!.wayLine)
    }

    // --- The strings (HonorSummarySection.swift:98-118@7c200bf) ---

    @Test
    fun `the delta line truncates to whole minutes, under one either way is together`() {
        assertEquals("you arrived together", HonorSummaryModel.deltaLine(resources, 59.9))
        assertEquals("you arrived together", HonorSummaryModel.deltaLine(resources, -59.9))
        assertEquals("you arrived together", HonorSummaryModel.deltaLine(resources, 0.0))
        assertEquals("they arrived 1 minute after you", HonorSummaryModel.deltaLine(resources, 60.0))
        assertEquals("they arrived 1 minute after you", HonorSummaryModel.deltaLine(resources, 119.9))
        assertEquals("they arrived 2 minutes before you", HonorSummaryModel.deltaLine(resources, -150.0))
        assertEquals("they arrived 1 minute before you", HonorSummaryModel.deltaLine(resources, -60.0))
    }

    @Test
    fun `the counts line puts voices first, picks each word by one, and is absent at zero`() {
        fun counts(voices: Int, replies: Int) = HonorSummaryModel.countsLine(
            resources,
            HonorSummaryData(wayTitle = "t", arrivedBeforeTheirsSeconds = null, voicesAlongTheWay = voices, repliesMade = replies),
        )
        assertEquals("1 voice along the way", counts(1, 0))
        assertEquals("1 reply", counts(0, 1))
        assertEquals("3 replies", counts(0, 3))
        assertEquals("2 voices along the way · 1 reply", counts(2, 1))
        assertNull(counts(0, 0))
    }
}
