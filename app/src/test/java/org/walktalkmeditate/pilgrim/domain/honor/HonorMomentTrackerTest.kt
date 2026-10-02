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
 * for test (the first eleven, same cases and numbers), then pins the
 * per-fix order and the id tiebreak parity spec B §3 and §8.1 state. The
 * six water tests in that file's extension are stage-only and wait for
 * Stage 21-2 with `waterAhead` itself. Distances run on the default
 * haversine, which moves no iOS number across a threshold here.
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
