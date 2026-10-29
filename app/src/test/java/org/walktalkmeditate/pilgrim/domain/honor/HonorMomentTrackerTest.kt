// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.Clock
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
        assertEquals("water's own hour", 42.0, snapshot.lastWaterSeconds!!, 0.0)

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
        t.restore(
            HonorMomentTracker.Snapshot(
                reached = emptySet(),
                queue = emptyList(),
                firedMarks = emptySet(),
                lastNoticeSeconds = null,
                lastWaterSeconds = null,
                lastStampSeconds = null,
                firedStamps = emptySet(),
            ),
        )
        assertEquals(
            "a minute in: a clock restored as 0 would still be inside its hour",
            listOf("a"),
            markAhead(t.waterAt(250.0, progress = 0.25, clock = 60.0)),
        )
    }

    // iOS PR #91's clocks (`HonorMomentTracker.swift:62-81,179-189,226-229@e551b11`)

    private fun clocks(notice: Double?, water: Double?, stamp: Double?) = HonorMomentTracker.Snapshot(
        reached = emptySet(),
        queue = emptyList(),
        firedMarks = emptySet(),
        lastNoticeSeconds = notice,
        lastWaterSeconds = water,
        lastStampSeconds = stamp,
        firedStamps = emptySet(),
    )

    @Test
    fun `water writes its own hour and the shared minute, and no temple clock`() {
        val t = markTracker(water("a", frac = 0.3))

        t.waterAt(100.0, progress = 0.1, clock = 42.0)

        val snapshot = t.snapshot()
        assertEquals(
            listOf<Double?>(42.0, 42.0, null),
            listOf(snapshot.lastNoticeSeconds, snapshot.lastWaterSeconds, snapshot.lastStampSeconds),
        )
    }

    /** The shared minute binds water too, though in a water-only walk its own hour always covers it. */
    @Test
    fun `a notice under a minute ago holds water, even with water's own hour free`() {
        val t = markTracker(water("a", frac = 0.5))
        t.restore(clocks(notice = 100.0, water = null, stamp = 100.0))

        assertEquals(
            "30 s after another notice",
            emptyList<String>(),
            markAhead(t.waterAt(250.0, progress = 0.25, clock = 130.0)),
        )
        assertEquals(
            "exactly a minute after it the line is free",
            listOf("a"),
            markAhead(t.waterAt(260.0, progress = 0.26, clock = 160.0)),
        )
    }

    @Test
    fun `a restore keeps each clock its own, and a null stays null`() {
        val t = markTracker(water("a", frac = 0.5))
        val restored = clocks(notice = 500.0, water = null, stamp = 450.0)

        t.restore(restored)

        assertEquals(restored, t.snapshot())
    }

    // iOS PR #91's stamp tests (`HonorMomentTrackerTests.swift:215-490@e551b11`), names kept

    /**
     * 10 km straight east, so a frac is a kilometre: the stamp rule works in
     * kilometres where the water rule works in metres.
     */
    private val longGeometry = WayGeometry(
        (0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.00898, alt = null, t = i * 600.0) },
    )

    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

    /** A fixed wall clock in the temple's own zone: [hour]:[minute] on April [day], 2026, in Tokyo. */
    private fun tokyoMillis(hour: Int, minute: Int, day: Int = 12): Long =
        LocalDateTime.of(2026, 4, day, hour, minute).atZone(tokyo).toInstant().toEpochMilli()

    private fun office(hour: Int, minute: Int) = HonorMomentTracker.StampOffice(
        closesMinutes = 17 * 60,
        zone = tokyo,
        clock = Clock { tokyoMillis(hour, minute) },
    )

    /**
     * A numbered fudasho as the dataset writes one: the number lives in the composed `text`, the
     * romanized name in `label`.
     */
    private fun temple(number: Int, frac: Double) = WayMoment(
        id = "temple-$number",
        frac = frac,
        at = WayCoordinate(lat = 0.0, lon = frac * 10_000 / 111_320),
        kind = WayMomentKind.Waypoint(label = "Kirihata-ji", icon = "seal"),
        text = "Temple $number · Koyasan Shingon · stamp available (¥500)",
    )

    /** iOS `stampTracker(_:marks:at:)`: [at] null is a route with no hours. */
    private fun stampTracker(
        moments: List<WayMoment>,
        marks: List<WayMark> = emptyList(),
        at: Pair<Int, Int>? = 15 to 30,
    ) = HonorMomentTracker(
        moments = moments,
        marks = marks,
        geometry = longGeometry,
        voicesEnabled = false,
        stamp = at?.let { (hour, minute) -> office(hour, minute) },
    )

    /** iOS `stampTracker(_:marks:clock:)`: read against a clock the test moves on between fixes. */
    private fun stampTrackerOn(clock: Clock, moments: List<WayMoment>, marks: List<WayMark> = emptyList()) =
        HonorMomentTracker(
            moments = moments,
            marks = marks,
            geometry = longGeometry,
            voicesEnabled = false,
            stamp = HonorMomentTracker.StampOffice(closesMinutes = 17 * 60, zone = tokyo, clock = clock),
        )

    /** An on-way fountain on the 10 km line, [atKm] from its start. */
    private fun fountain(atKm: Double) = WayMark(
        id = "fuente-$atKm",
        kind = WayMarkKind.WATER,
        name = "Fuente",
        at = WayCoordinate(lat = 0.0, lon = atKm * 1000 / 111_320),
        frac = atKm / 10,
        offLineMeters = 10.0,
    )

    private fun stampAhead(actions: List<Action>): List<String> =
        actions.filterIsInstance<Action.StampAhead>().map { it.temple.id }

    /**
     * The iOS tests' `update(location: coord(meters), progressFrac:, gates: .init(),
     * isStationary: false, activeSeconds:, isOnWay:)`.
     */
    private fun HonorMomentTracker.noticeAt(
        meters: Double,
        progress: Double,
        seconds: Double,
        onWay: Boolean = true,
    ): List<Action> = update(
        coord(meters),
        progressFrac = progress,
        gates = open,
        isStationary = false,
        activeSeconds = seconds,
        isOnWay = onWay,
    )

    /** Every route but Shikoku's. Nothing, ever, whatever the hour. */
    @Test
    fun testARouteThatStatesNoStampHoursNeverSpeaksOfTemples() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)), at = null)
        assertEquals(emptyList<String>(), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
    }

    @Test
    fun testTheNoticeOpensTwoHoursBeforeClosingAndShutsWithTheOffice() {
        val early = stampTracker(listOf(temple(10, frac = 0.5)), at = 14 to 59)
        assertEquals(
            "14:59: the afternoon is still young",
            emptyList<String>(),
            stampAhead(early.noticeAt(1000.0, progress = 0.1, seconds = 0.0)),
        )
        val onTheHour = stampTracker(listOf(temple(10, frac = 0.5)), at = 15 to 0)
        assertEquals(
            "15:00 exactly: two hours to closing",
            listOf("temple-10"),
            stampAhead(onTheHour.noticeAt(1000.0, progress = 0.1, seconds = 0.0)),
        )
        val closed = stampTracker(listOf(temple(10, frac = 0.5)), at = 17 to 0)
        assertEquals(
            "17:00: no stamp left to be had",
            emptyList<String>(),
            stampAhead(closed.noticeAt(1000.0, progress = 0.1, seconds = 0.0)),
        )
    }

    @Test
    fun testATempleInsideAKilometreHasStoppedBeingADecision() {
        val total = longGeometry.totalMeters
        val near = stampTracker(listOf(temple(10, frac = 0.5)))
        assertEquals(
            "999 m short: the walker can see it and is already arriving",
            emptyList<String>(),
            stampAhead(near.noticeAt(4000.0, progress = 0.5 - 999 / total, seconds = 0.0)),
        )

        val far = stampTracker(listOf(temple(10, frac = 0.5)))
        val hit = far.noticeAt(4000.0, progress = 0.5 - 1001 / total, seconds = 0.0)

        assertEquals(listOf("temple-10"), stampAhead(hit))
        assertEquals(1001.0, (hit.first() as Action.StampAhead).meters, 1.0)
    }

    @Test
    fun testEachTempleSpeaksOnce() {
        val t = stampTracker(listOf(temple(10, frac = 0.5), temple(11, frac = 0.9)))
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        assertEquals(
            "still 3 km ahead ten minutes later: spoken for, and still the nearest choice, so temple 11 waits",
            emptyList<String>(),
            stampAhead(t.noticeAt(2000.0, progress = 0.2, seconds = 600.0)),
        )
        assertEquals(
            "an hour and a half on, temple 11, 4 km ahead, speaks",
            listOf("temple-11"),
            stampAhead(t.noticeAt(5000.0, progress = 0.5, seconds = 5400.0)),
        )
    }

    @Test
    fun testATempleAlreadyBehindIsNeverAnnounced() {
        val t = stampTracker(listOf(temple(10, frac = 0.3)))
        assertEquals(
            "a temple you have walked past cannot still be stamped today",
            emptyList<String>(),
            stampAhead(t.noticeAt(6000.0, progress = 0.6, seconds = 0.0)),
        )
    }

    /**
     * A seal means a stamp is available, as at Camino pilgrim offices and Kumano shrines;
     * only "Temple N" keeps an office that shuts.
     */
    @Test
    fun testASealThatIsNotANumberedTempleStaysSilent() {
        val pilgrimOffice = WayMoment(
            id = "wp-sjpp-pilgrim-office",
            frac = 0.5,
            at = WayCoordinate(lat = 0.0, lon = 5000.0 / 111_320),
            kind = WayMomentKind.Waypoint(label = "Pilgrim Welcome Office", icon = "seal"),
            text = "Pilgrim office · 172 m · stamp available",
        )
        val t = stampTracker(listOf(pilgrimOffice))
        assertEquals(emptyList<String>(), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
    }

    @Test
    fun testAWalkerOffTheWayIsToldNothing() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)))
        assertEquals(
            "off the way the distance ahead would be a guess",
            emptyList<String>(),
            stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0, onWay = false)),
        )
    }

    /** One caption line, one notice at a time: the temple takes it, and the fountain speaks a breath later. */
    @Test
    fun testTheTempleTakesTheLineAheadOfTheFountain() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)), marks = listOf(fountain(atKm = 1.25)))
        val actions = t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)
        assertEquals(listOf("temple-10"), stampAhead(actions))
        assertEquals("never two notices on one fix", emptyList<String>(), markAhead(actions))

        val tooSoon = HonorTuning.NOTICE_GAP_SECONDS / 2
        assertEquals(
            "nor one replacing another still on the line",
            emptyList<String>(),
            markAhead(t.noticeAt(1020.0, progress = 0.102, seconds = tooSoon)),
        )
        assertEquals(
            "the fountain keeps its own hour, not the temple's",
            listOf("fuente-1.25"),
            markAhead(t.noticeAt(1080.0, progress = 0.108, seconds = HonorTuning.NOTICE_GAP_SECONDS)),
        )
    }

    /** Water at 14:59 used to hold every temple for an hour; only the shared minute stands between them now. */
    @Test
    fun testAFountainDoesNotSilenceATemple() {
        var clock = 14 to 59
        val t = stampTrackerOn(
            Clock { tokyoMillis(clock.first, clock.second) },
            listOf(temple(10, frac = 0.5)),
            marks = listOf(fountain(atKm = 1.2)),
        )
        assertEquals(listOf("fuente-1.2"), markAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))

        clock = 15 to 0
        assertEquals(
            "a minute after the water, the temple speaks",
            listOf("temple-10"),
            stampAhead(t.noticeAt(1060.0, progress = 0.106, seconds = HonorTuning.NOTICE_GAP_SECONDS)),
        )
    }

    @Test
    fun testATempleDoesNotSilenceAFountain() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)), marks = listOf(fountain(atKm = 3.2)))
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        assertEquals(
            "twenty minutes after the temple, the fountain still speaks",
            listOf("fuente-3.2"),
            markAhead(t.noticeAt(3000.0, progress = 0.3, seconds = 1200.0)),
        )
    }

    /** A first temple at 16:10 used to hold the next past 17:00; now the next may speak from 16:20. */
    @Test
    fun testTwoTemplesInTheLastHoursBothSpeakOnceEach() {
        var clock = 16 to 10
        val t = stampTrackerOn(
            Clock { tokyoMillis(clock.first, clock.second) },
            listOf(temple(10, frac = 0.22), temple(11, frac = 0.9)),
        )
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))

        clock = 16 to 20
        assertEquals(
            "ten minutes on, 600 m short of temple 10, temple 11 is the choice",
            listOf("temple-11"),
            stampAhead(t.noticeAt(1600.0, progress = 0.16, seconds = HonorTuning.STAMP_QUIET_SECONDS)),
        )

        clock = 16 to 50
        assertEquals(
            "each has had its say",
            emptyList<String>(),
            stampAhead(t.noticeAt(5000.0, progress = 0.5, seconds = 2400.0)),
        )
    }

    /** Only the nearest temple still a choice may speak, so the one after it waits until the walker is nearly at it. */
    @Test
    fun testATempleAlreadyNamedHoldsTheNextUntilTheWalkerIsNearlyThere() {
        val t = stampTracker(listOf(temple(10, frac = 0.5), temple(11, frac = 0.6), temple(12, frac = 0.9)))
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        assertEquals(
            "temple 10 is 3 km on and still the walker's choice; 11 and 12 are not news yet",
            emptyList<String>(),
            stampAhead(t.noticeAt(2000.0, progress = 0.2, seconds = 600.0)),
        )
        assertEquals(
            "500 m short of temple 10, the next one is the choice",
            listOf("temple-11"),
            stampAhead(t.noticeAt(4500.0, progress = 0.45, seconds = 2400.0)),
        )
    }

    /**
     * A temple named a step outside the kilometre is inside it a minute later; the next waits ten
     * minutes after the last.
     */
    @Test
    fun testTheNextTempleWaitsTenMinutesAfterTheLast() {
        val total = longGeometry.totalMeters
        val t = stampTracker(listOf(temple(10, frac = 0.5), temple(11, frac = 0.9)))
        assertEquals(
            listOf("temple-10"),
            stampAhead(t.noticeAt(3999.0, progress = 0.5 - 1001 / total, seconds = 0.0)),
        )
        assertEquals(
            "inside the caption's minute",
            emptyList<String>(),
            stampAhead(
                t.noticeAt(4032.0, progress = 0.5 - 968 / total, seconds = HonorTuning.NOTICE_GAP_SECONDS / 2),
            ),
        )
        assertEquals(
            "the line is free, but the last temple spoke a minute ago",
            emptyList<String>(),
            stampAhead(t.noticeAt(4066.0, progress = 0.5 - 934 / total, seconds = HonorTuning.NOTICE_GAP_SECONDS)),
        )
        assertEquals(
            emptyList<String>(),
            stampAhead(
                t.noticeAt(4665.0, progress = 0.5 - 335 / total, seconds = HonorTuning.STAMP_QUIET_SECONDS - 1),
            ),
        )
        assertEquals(
            listOf("temple-11"),
            stampAhead(t.noticeAt(4666.0, progress = 0.5 - 334 / total, seconds = HonorTuning.STAMP_QUIET_SECONDS)),
        )
    }

    /** Awa stage-00 from 15.0 km at 4 km/h: temple 6 spoke at 1.1 km and temple 7 two minutes later; now ten. */
    @Test
    fun testTwoTemplesAKilometreApartDoNotSpeakMinutesApart() {
        val total = longGeometry.totalMeters
        val t = stampTracker(listOf(temple(6, frac = 0.5), temple(7, frac = 0.5 + 1130 / total)))
        assertEquals(
            listOf("temple-6"),
            stampAhead(t.noticeAt(3900.0, progress = 0.5 - 1100 / total, seconds = 0.0)),
        )
        assertEquals(
            "temple 6 is inside the kilometre and temple 7 is the choice, but not two minutes on",
            emptyList<String>(),
            stampAhead(t.noticeAt(4033.0, progress = 0.5 - 967 / total, seconds = 120.0)),
        )

        val hit = t.noticeAt(4567.0, progress = 0.5 - 433 / total, seconds = HonorTuning.STAMP_QUIET_SECONDS)

        assertEquals(listOf("temple-7"), stampAhead(hit))
        assertEquals(1563.0, (hit.first() as Action.StampAhead).meters, 1.0)
    }

    // The spec's Android additions (Behavior rows 11, 12, 14, 15; annex A.3.2, A.3.4 probes)

    /**
     * Row 11, annex probe 1: the nearest temple inside a kilometre is passed over, and a temple
     * skipped never speaks later.
     */
    @Test
    fun `the nearest temple inside a kilometre is passed over for the next, and never speaks later`() {
        val t = stampTracker(listOf(temple(10, frac = 0.15), temple(11, frac = 0.9)), at = 15 to 0)
        val hit = t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)
        assertEquals(listOf("temple-11"), stampAhead(hit))
        assertEquals(0.8 * longGeometry.totalMeters, (hit.single() as Action.StampAhead).meters, 1.0)

        assertEquals(emptyList<String>(), stampAhead(t.noticeAt(1200.0, progress = 0.12, seconds = 600.0)))
        assertEquals(emptyList<String>(), stampAhead(t.noticeAt(2000.0, progress = 0.2, seconds = 1200.0)))
    }

    /** Row 12, annex probe 7: off the way nothing fires and no clock moves; the first fix back on the way speaks. */
    @Test
    fun `off the way no clock moves, and the first fix back on the way speaks`() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)))

        t.noticeAt(1000.0, progress = 0.1, seconds = 0.0, onWay = false)

        val off = t.snapshot()
        assertEquals(
            listOf<Any?>(null, null, null, emptySet<String>()),
            listOf(off.lastNoticeSeconds, off.lastWaterSeconds, off.lastStampSeconds, off.firedStamps),
        )
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1010.0, progress = 0.101, seconds = 30.0)))
    }

    /**
     * Row 14, annex probe 4: after the close nothing is spoken or marked, so an unfired temple speaks
     * in the next day's window.
     */
    @Test
    fun `after the office shuts nothing is spoken or marked, and an unfired temple speaks in the next day's window`() {
        var day = 12 to (17 to 0)
        val t = stampTrackerOn(
            Clock { tokyoMillis(day.second.first, day.second.second, day = day.first) },
            listOf(temple(10, frac = 0.5)),
        )

        assertEquals(emptyList<String>(), stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)))
        assertEquals(emptySet<String>(), t.snapshot().firedStamps)
        assertNull(t.snapshot().lastStampSeconds)

        day = 13 to (15 to 0)
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1010.0, progress = 0.101, seconds = 79_200.0)))
    }

    /**
     * Annex probe 3, kept upstream as deliberate (`HonorMomentTracker.swift:33-36@e551b11`): the
     * window is the close's own day.
     */
    @Test
    fun `the window reads the closing time alone and never wraps past midnight`() {
        var clock = 23 to 30
        val office = HonorMomentTracker.StampOffice(
            closesMinutes = 30,
            zone = tokyo,
            clock = Clock { tokyoMillis(clock.first, clock.second) },
        )
        val t = HonorMomentTracker(
            moments = listOf(temple(10, frac = 0.5)),
            geometry = longGeometry,
            voicesEnabled = false,
            stamp = office,
        )

        assertEquals(
            "23:30 is before a 00:30 close, but on the day before",
            emptyList<String>(),
            stampAhead(t.noticeAt(1000.0, progress = 0.1, seconds = 0.0)),
        )
        clock = 0 to 10
        assertEquals(listOf("temple-10"), stampAhead(t.noticeAt(1010.0, progress = 0.101, seconds = 60.0)))
    }

    /** Row 15, the shared minute's documented cost (`HonorMomentTracker.swift:68-72@e551b11`), intended upstream. */
    @Test
    fun `a fountain 50 m ahead when a temple speaks is passed before the line frees, and never speaks`() {
        val t = stampTracker(listOf(temple(10, frac = 0.5)), marks = listOf(fountain(atKm = 1.05)))
        val heard = listOf(
            t.noticeAt(1000.0, progress = 0.1, seconds = 0.0),
            t.noticeAt(1040.0, progress = 0.104, seconds = 30.0),
            t.noticeAt(1080.0, progress = 0.108, seconds = 60.0),
        ).flatMap(::markAhead)
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun `a restore brings back the temples spoken and every clock, keeping only temples it scans`() {
        val live = stampTracker(listOf(temple(10, frac = 0.5), temple(11, frac = 0.9)))
        live.noticeAt(1000.0, progress = 0.1, seconds = 42.0)
        val snapshot = live.snapshot()
        assertEquals(setOf("temple-10"), snapshot.firedStamps)
        assertEquals(
            listOf<Double?>(42.0, null, 42.0),
            listOf(snapshot.lastNoticeSeconds, snapshot.lastWaterSeconds, snapshot.lastStampSeconds),
        )

        val revived = stampTracker(listOf(temple(10, frac = 0.5), temple(11, frac = 0.9)))
        revived.restore(snapshot.copy(firedStamps = snapshot.firedStamps + "wp-sjpp-pilgrim-office"))

        assertEquals(snapshot, revived.snapshot())
        assertEquals(
            "temple 10 stays named",
            emptyList<String>(),
            stampAhead(revived.noticeAt(2000.0, progress = 0.2, seconds = 642.0)),
        )
    }

    @Test
    fun `a tracker with no office restores no temples spoken`() {
        val live = stampTracker(listOf(temple(10, frac = 0.5)))
        live.noticeAt(1000.0, progress = 0.1, seconds = 0.0)

        val silent = stampTracker(listOf(temple(10, frac = 0.5)), at = null)
        silent.restore(live.snapshot())

        assertEquals(emptySet<String>(), silent.snapshot().firedStamps)
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
