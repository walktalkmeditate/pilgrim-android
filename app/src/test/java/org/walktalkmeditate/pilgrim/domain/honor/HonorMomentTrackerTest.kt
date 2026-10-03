// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.honor.HonorMomentTracker.Action
import org.walktalkmeditate.pilgrim.domain.honor.HonorMomentTracker.Gates

/**
 * Ports iOS `UnitTests/Honor/HonorMomentTrackerTests.swift@7c200bf` test
 * for test (the first twelve, same cases and numbers), then pins the
 * per-fix order and the id tiebreak parity spec B §3 and §8.1 state; then
 * the six water tests of that file's extension, names kept, and the water
 * cases the pilgrimage-stage spec adds (P3 §2–§3, corrections 4, 5, 13).
 * Distances run on the default haversine, which moves no iOS number
 * across a threshold here.
 */
class HonorMomentTrackerTest {

    /** 1 km straight east; a voice at 300 m, a sitting at 300 m, a second voice at 500 m, a photo at 700 m. */
    private val route = (0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.000898, alt = null, t = i * 60.0) }
    private val geometry = WayGeometry(route)

    private val none = emptyList<Action>()
    private val open = Gates()

    private fun at(meters: Double) = WayCoordinate(lat = 0.0, lon = meters / 111_320)

    private fun coord(meters: Double, lat: Double = 0.0) = WayCoordinate(lat = lat, lon = meters / 111_320)

    /** The iOS tests' `update(location: coord(meters, lat:), progressFrac:, gates:, isStationary:)`. */
    private fun HonorMomentTracker.fixAt(
        meters: Double,
        progress: Double,
        gates: Gates = open,
        stationary: Boolean = false,
        lat: Double = 0.0,
    ): List<Action> = update(coord(meters, lat), progressFrac = progress, gates = gates, isStationary = stationary)

    private fun voice(id: String, frac: Double, endFrac: Double, meters: Double, duration: Double, file: String) =
        WayMoment(
            id = id,
            frac = frac,
            at = at(meters),
            kind = WayMomentKind.Voice(
                endFrac = endFrac,
                duration = duration,
                kind = VoiceKind.SPOKEN,
                media = WayMedia.File(file),
            ),
        )

    private val voice1 = voice("voice-1", frac = 0.3, endFrac = 0.35, meters = 300.0, duration = 40.0, "audio/1.m4a")
    private val sit1 = WayMoment(
        id = "sit-1",
        frac = 0.3,
        at = at(300.0),
        kind = WayMomentKind.Meditation(minutes = 12, isEstimate = false),
    )
    private val voice2 = voice("voice-2", frac = 0.5, endFrac = 0.55, meters = 500.0, duration = 30.0, "audio/2.m4a")
    private val photo1 = WayMoment(
        id = "photo-1",
        frac = 0.7,
        at = at(700.0),
        kind = WayMomentKind.Photo(media = WayMedia.File("photos/1.jpg")),
    )

    private fun tracker(voicesEnabled: Boolean = true) = HonorMomentTracker(
        moments = listOf(voice1, sit1, voice2, photo1),
        geometry = geometry,
        voicesEnabled = voicesEnabled,
    )

    private fun trackerOf(vararg moments: WayMoment) =
        HonorMomentTracker(moments = moments.toList(), geometry = geometry, voicesEnabled = true)

    @Test
    fun `fires once and respects the frac gate`() {
        val t = tracker()
        // Standing at 300 m but progress says 0.1: too early (a crossing path), nothing fires.
        assertEquals(none, t.fixAt(300.0, progress = 0.1))
        assertEquals(listOf(Action.Reached(sit1), Action.VoiceStart(voice1)), t.fixAt(300.0, progress = 0.3))
        assertEquals(none, t.fixAt(300.0, progress = 0.3))
    }

    @Test
    fun `radii differ by kind`() {
        val t = tracker()
        // 50 m north of the photo: inside the 60 m card radius.
        assertEquals(listOf(Action.Reached(photo1)), t.fixAt(700.0, progress = 0.7, lat = 50.0 / 111_320))
        // 50 m north of voice 2: outside the 42 m voice radius.
        assertEquals(none, t.fixAt(500.0, progress = 0.5, lat = 50.0 / 111_320))
    }

    @Test
    fun `queue waits for the playing voice then starts the next`() {
        val t = tracker()
        t.fixAt(300.0, progress = 0.3)
        assertEquals("voice 2 is queued behind voice 1", none, t.fixAt(500.0, progress = 0.5))
        assertEquals(listOf(Action.VoiceStart(voice2)), t.voiceDidFinish(open))
        assertEquals(none, t.voiceDidFinish(open))
    }

    @Test
    fun `sit pauses the playing voice and resumes it`() {
        val t = tracker()
        t.fixAt(300.0, progress = 0.3)
        assertEquals(listOf(Action.VoicePause), t.gatesDidChange(Gates(meditating = true)))
        assertTrue(t.isVoicePaused)
        assertEquals("no repeat", none, t.gatesDidChange(Gates(meditating = true)))
        assertEquals(listOf(Action.VoiceResume), t.gatesDidChange(open))
    }

    @Test
    fun `gated voice waits then starts when the gate clears`() {
        val t = tracker()
        assertEquals(listOf(Action.Reached(sit1)), t.fixAt(300.0, progress = 0.3, gates = Gates(recording = true)))
        assertEquals(listOf(Action.VoiceStart(voice1)), t.gatesDidChange(open))
    }

    @Test
    fun `queued voice drops when moving past but not while stationary`() {
        val t = tracker()
        val recording = Gates(recording = true)
        t.fixAt(300.0, progress = 0.3, gates = recording)
        // 320 m past the spot, standing still: exempt (and 80 m short of the photo, so no card).
        assertEquals(none, t.fixAt(620.0, progress = 0.62, gates = recording, stationary = true))
        // Moving: dropped.
        assertEquals(listOf(Action.VoiceDropped(voice1)), t.fixAt(630.0, progress = 0.63, gates = recording))
        assertEquals(none, t.gatesDidChange(open))
    }

    @Test
    fun `queued voice is kept while another voice plays`() {
        val t = tracker()
        t.fixAt(300.0, progress = 0.3) // voice 1 plays
        t.fixAt(500.0, progress = 0.5) // voice 2 waits
        // 320 m past voice 2 while voice 1 still plays: nothing is dropped.
        assertEquals(none, t.fixAt(820.0, progress = 0.82))
        assertEquals(listOf(Action.VoiceStart(voice2)), t.voiceDidFinish(open))
    }

    @Test
    fun `voices disabled still reaches cards and never starts audio`() {
        val t = tracker(voicesEnabled = false)
        assertEquals(listOf(Action.Reached(sit1)), t.fixAt(300.0, progress = 0.3))
        assertNull(t.playing)
    }

    @Test
    fun `paused voice is dropped when the walker moves on and the next starts later`() {
        val t = tracker()
        val sitting = Gates(meditating = true)
        t.fixAt(300.0, progress = 0.3) // voice 1 plays
        assertEquals(listOf(Action.VoicePause), t.gatesDidChange(sitting))
        t.fixAt(500.0, progress = 0.5, gates = sitting) // voice 2 waits
        // Standing still 320 m past voice 1: the paused voice is exempt from the drop.
        assertEquals(none, t.fixAt(620.0, progress = 0.62, gates = sitting, stationary = true))
        assertTrue(t.isVoicePaused)
        // Moving on: voice 1 is dropped; voice 2 (130 m back) keeps waiting for the gate.
        assertEquals(listOf(Action.VoiceDropped(voice1)), t.fixAt(630.0, progress = 0.63, gates = sitting))
        assertNull(t.playing)
        assertFalse(t.isVoicePaused)
        assertEquals(listOf(Action.VoiceStart(voice2)), t.gatesDidChange(open))
    }

    @Test
    fun `moment without coordinate falls back to its frac`() {
        val rest = WayMoment(id = "rest-1", frac = 0.4, at = null, kind = WayMomentKind.Rest(minutes = 3))
        val t = trackerOf(rest)
        assertEquals("70 m short of the frac's place", none, t.fixAt(330.0, progress = 0.4))
        assertEquals(listOf(Action.Reached(rest)), t.fixAt(400.0, progress = 0.4))
    }

    @Test
    fun `frac tolerance is five percent`() {
        val t = trackerOf(sit1)
        assertEquals(none, t.fixAt(300.0, progress = 0.24))
        assertEquals(listOf(Action.Reached(sit1)), t.fixAt(300.0, progress = 0.26))
    }

    @Test
    fun `every gate holds a voice`() {
        val closed = listOf(
            Gates(paused = true),
            Gates(meditating = true),
            Gates(recording = true),
            Gates(externalAudio = true),
        )
        for (gates in closed) {
            val t = trackerOf(voice1)
            assertEquals("$gates", none, t.fixAt(300.0, progress = 0.3, gates = gates))
            assertEquals("$gates", listOf(Action.VoiceStart(voice1)), t.gatesDidChange(open))
        }
    }

    // Android additions: the order parity spec B §3 and §8.1 fix

    @Test
    fun `one fix reports reached moments, then queued drops, then the paused voice, then at most one start`() {
        val voice3 = voice("voice-3", frac = 0.35, endFrac = 0.37, meters = 350.0, duration = 20.0, "audio/3.m4a")
        val t = trackerOf(voice1, voice3, photo1)
        val sitting = Gates(meditating = true)
        assertEquals(listOf(Action.VoiceStart(voice1)), t.fixAt(300.0, progress = 0.3))
        assertEquals("voice 3 queues behind voice 1", none, t.fixAt(340.0, progress = 0.34))
        assertEquals(listOf(Action.VoicePause), t.gatesDidChange(sitting))
        assertEquals(
            listOf(Action.Reached(photo1), Action.VoiceDropped(voice3), Action.VoiceDropped(voice1)),
            t.fixAt(700.0, progress = 0.7, gates = sitting),
        )
        assertEquals("nothing left to start", none, t.gatesDidChange(open))
    }

    @Test
    fun `moments sharing a frac are reached in id order`() {
        val waypoint = WayMoment(
            id = "waypoint-1",
            frac = 0.7,
            at = at(700.0),
            kind = WayMomentKind.Waypoint(label = "gate", icon = "leaf"),
        )
        val t = trackerOf(waypoint, photo1)
        assertEquals(listOf(Action.Reached(photo1), Action.Reached(waypoint)), t.fixAt(700.0, progress = 0.7))
    }

    // iOS's water tests (`HonorMomentTrackerTests.swift` extension), names kept

    private fun water(id: String, frac: Double, offLine: Double = 10.0) = WayMark(
        id = id,
        kind = WayMarkKind.WATER,
        name = "Fuente $id",
        at = WayCoordinate(lat = 0.0, lon = frac * 1000 / 111_320),
        frac = frac,
        offLineMeters = offLine,
    )

    private fun markTracker(vararg marks: WayMark) =
        HonorMomentTracker(moments = emptyList(), marks = marks.toList(), geometry = geometry, voicesEnabled = false)

    private fun markAhead(actions: List<Action>): List<String> =
        actions.filterIsInstance<Action.MarkAhead>().map { it.mark.id }

    private fun HonorMomentTracker.waterAt(
        meters: Double,
        progress: Double,
        clock: Double,
        onWay: Boolean = true,
        gates: Gates = open,
    ): List<Action> = update(
        coord(meters),
        progressFrac = progress,
        gates = gates,
        isStationary = false,
        activeSeconds = clock,
        isOnWay = onWay,
    )

    @Test
    fun testWaterFiresOnceInsideThreeHundredMetresBeforeIt() {
        val t = markTracker(water("a", frac = 0.5))
        // 400 m short: too early.
        assertEquals(emptyList<String>(), markAhead(t.waterAt(100.0, progress = 0.1, clock = 0.0)))
        // 250 m short: the caption.
        val hit = t.waterAt(250.0, progress = 0.25, clock = 60.0)
        assertEquals(listOf("a"), markAhead(hit))
        assertEquals(250.0, (hit.first() as Action.MarkAhead).meters, 5.0)
        // And never again — not inside the quiet hour, and not after it
        // either while the mark is still ahead.
        assertEquals(emptyList<String>(), markAhead(t.waterAt(300.0, progress = 0.3, clock = 120.0)))
        assertEquals(
            "a mark that has spoken stays silent even once the hour has passed",
            emptyList<String>(),
            markAhead(t.waterAt(300.0, progress = 0.3, clock = 4000.0)),
        )
    }

    @Test
    fun testWaterNeverFiresOnceItIsBehindYou() {
        val t = markTracker(water("a", frac = 0.5))
        assertEquals(
            "a fountain you have already passed is not news",
            emptyList<String>(),
            markAhead(t.waterAt(600.0, progress = 0.6, clock = 0.0)),
        )
    }

    @Test
    fun testAFountainOffTheTrailIsADetourNotADrink() {
        val t = markTracker(water("far", frac = 0.5, offLine = 250.0))
        assertEquals(emptyList<String>(), markAhead(t.waterAt(250.0, progress = 0.25, clock = 0.0)))
    }

    @Test
    fun testOffWayWalkersGetNothing() {
        val t = markTracker(water("a", frac = 0.5))
        assertEquals(emptyList<String>(), markAhead(t.waterAt(250.0, progress = 0.25, clock = 0.0, onWay = false)))
    }

    @Test
    fun testTheFirstIsFreeThenOnePerHourOfWalking() {
        val t = markTracker(water("a", frac = 0.3), water("b", frac = 0.5), water("c", frac = 0.9))
        assertEquals(listOf("a"), markAhead(t.waterAt(100.0, progress = 0.1, clock = 0.0)))
        // b is 200 m ahead, 20 minutes later: inside the quiet hour.
        assertEquals(
            "a skipped mark stays a silent pin",
            emptyList<String>(),
            markAhead(t.waterAt(300.0, progress = 0.3, clock = 1200.0)),
        )
        // c is 200 m ahead, an hour and a half in.
        assertEquals(listOf("c"), markAhead(t.waterAt(700.0, progress = 0.7, clock = 5400.0)))
    }

    @Test
    fun testOnlyWaterSpeaks() {
        val bed = WayMark(
            id = "bed",
            kind = WayMarkKind.BED,
            name = "Albergue",
            at = WayCoordinate(lat = 0.0, lon = 500.0 / 111_320),
            frac = 0.5,
            offLineMeters = 10.0,
        )
        val t = markTracker(bed)
        assertEquals(emptyList<String>(), markAhead(t.waterAt(250.0, progress = 0.25, clock = 0.0)))
    }

    // The pilgrimage-stage spec's water cases (P3 §2–§3)

    @Test
    fun `the Begin fix can be the first notice, its clock at 0, and the hour runs from there`() {
        val t = markTracker(water("a", frac = 0.25), water("b", frac = 0.6))
        assertEquals(listOf("a"), markAhead(t.waterAt(0.0, progress = 0.0, clock = 0.0)))
        assertEquals(0.0, t.snapshot().lastNoticeSeconds!!, 0.0)
        val short = t.waterAt(400.0, progress = 0.4, clock = 3599.6)
        assertEquals("0.4 s short of the hour", emptyList<String>(), markAhead(short))
        assertEquals(
            "strict: exactly an hour after the last notice the next may speak",
            listOf("b"),
            markAhead(t.waterAt(400.0, progress = 0.4, clock = 3600.0)),
        )
    }

    @Test
    fun `a mark skipped in the quiet hour speaks once the hour ends if it is still within 300 m`() {
        val t = markTracker(water("a", frac = 0.3), water("b", frac = 0.5))
        markAhead(t.waterAt(100.0, progress = 0.1, clock = 0.0))
        assertEquals(emptyList<String>(), markAhead(t.waterAt(250.0, progress = 0.25, clock = 1200.0)))

        val late = t.waterAt(350.0, progress = 0.35, clock = 3600.0)

        assertEquals(listOf("b"), markAhead(late))
        assertEquals("the metres left at that fix", 150.0, (late.single() as Action.MarkAhead).meters, 5.0)
    }

    @Test
    fun `a fountain 250 m off the line never announces, the whole way along`() {
        val t = markTracker(water("far", frac = 0.5, offLine = 250.0))
        val heard = (0..100).flatMap { step ->
            markAhead(t.waterAt(step * 10.0, progress = step / 100.0, clock = step * 600.0))
        }
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun `water exactly 60 m off the line is on the way, and a centimetre farther is not`() {
        val t = markTracker(water("past", frac = 0.4, offLine = 60.01), water("edge", frac = 0.5, offLine = 60.0))
        assertEquals(listOf("edge"), markAhead(t.waterAt(250.0, progress = 0.25, clock = 0.0)))
    }

    @Test
    fun `water speaks while recording, sitting, paused, or under a whisper, as iOS does`() {
        val closed = listOf(
            Gates(paused = true),
            Gates(meditating = true),
            Gates(recording = true),
            Gates(externalAudio = true),
        )
        for (gates in closed) {
            val t = markTracker(water("a", frac = 0.5))
            assertEquals("$gates", listOf("a"), markAhead(t.waterAt(250.0, progress = 0.25, clock = 0.0, gates = gates)))
        }
    }

    @Test
    fun `the water sort ties -0_0 with 0_0 and keeps the package's order`() {
        val zero = water("zero", frac = 0.0)
        val negativeZero = water("negative-zero", frac = -0.0)
        val zeroFirst = markTracker(zero, negativeZero).waterAt(0.0, progress = 0.0, clock = 0.0)
        assertEquals(listOf("zero"), markAhead(zeroFirst))
        assertEquals(
            listOf("negative-zero"),
            markAhead(markTracker(negativeZero, zero).waterAt(0.0, progress = 0.0, clock = 0.0)),
        )
    }

    @Test
    fun `water exactly 300 m ahead speaks`() {
        val t = markTracker(water("a", frac = 0.4))

        val hit = t.waterAt(100.0, progress = progressPutting(frac = 0.4, aheadMeters = 300.0), clock = 0.0)

        assertEquals(listOf(300.0), hit.filterIsInstance<Action.MarkAhead>().map { it.meters })
    }

    @Test
    fun `one fix speaks only the nearer of two, and the other waits for the next hour`() {
        val t = markTracker(water("a", frac = 0.3), water("b", frac = 0.35))
        assertEquals(listOf("a"), markAhead(t.waterAt(100.0, progress = 0.1, clock = 0.0)))
        assertEquals(emptyList<String>(), markAhead(t.waterAt(100.0, progress = 0.1, clock = 60.0)))
        assertEquals(listOf("b"), markAhead(t.waterAt(200.0, progress = 0.2, clock = 3600.0)))
    }

    /** The progress that puts a mark at [frac] exactly [aheadMeters] ahead in iOS's arithmetic. */
    private fun progressPutting(frac: Double, aheadMeters: Double): Double {
        var progress = frac - aheadMeters / geometry.totalMeters
        repeat(64) {
            val ahead = (frac - progress) * geometry.totalMeters
            if (ahead == aheadMeters) return progress
            progress = if (ahead > aheadMeters) Math.nextUp(progress) else Math.nextDown(progress)
        }
        error("no progress puts the mark exactly $aheadMeters m ahead")
    }

    @Test
    fun `water comes after the drops and before a voice starts, in one fix`() {
        val t = HonorMomentTracker(
            moments = listOf(voice1),
            marks = listOf(water("a", frac = 0.5)),
            geometry = geometry,
            voicesEnabled = true,
        )
        val actions = t.update(coord(300.0), progressFrac = 0.3, gates = open, isStationary = false)
        assertEquals(listOf(Action.MarkAhead::class, Action.VoiceStart::class), actions.map { it::class })
    }

    @Test
    fun `a restore brings back the water spoken and the quiet clock, keeping only marks it watches`() {
        val live = markTracker(water("a", frac = 0.3), water("b", frac = 0.5))
        live.waterAt(100.0, progress = 0.1, clock = 42.0)
        val snapshot = live.snapshot()
        assertEquals(setOf("a") to 42.0, snapshot.firedMarks to snapshot.lastNoticeSeconds)

        val revived = markTracker(water("a", frac = 0.3), water("b", frac = 0.5))
        revived.restore(snapshot.copy(firedMarks = snapshot.firedMarks + "a-food-mark"))

        assertEquals(snapshot, revived.snapshot())
        assertEquals(
            "a spoken mark stays silent, and b waits for the hour",
            emptyList<String>(),
            markAhead(revived.waterAt(250.0, progress = 0.25, clock = 1_000.0)),
        )
        assertEquals(listOf("b"), markAhead(revived.waterAt(250.0, progress = 0.25, clock = 3_642.0)))
    }

    @Test
    fun `a restored null clock leaves the first notice free`() {
        val t = markTracker(water("a", frac = 0.5))
        t.restore(HonorMomentTracker.Snapshot(emptySet(), emptyList(), emptySet(), lastNoticeSeconds = null))
        assertEquals(listOf("a"), markAhead(t.waterAt(250.0, progress = 0.25, clock = 7_200.0)))
    }

    @Test
    fun `the injected distance decides reach`() {
        val t = HonorMomentTracker(
            moments = listOf(sit1),
            geometry = geometry,
            voicesEnabled = true,
            distance = { _, _ -> 61.0 },
        )
        assertEquals(none, t.fixAt(300.0, progress = 0.3))
    }
}
