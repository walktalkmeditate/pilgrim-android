// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngineEvent.Arrived
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngineEvent.MarkAhead
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngineEvent.MomentReached
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngineEvent.SoftTap
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngineEvent.VoiceStart

/**
 * Ports iOS `UnitTests/Honor/HonorEngineTests.swift@7c200bf` test for test
 * (same cases and numbers), except `testStopCancelsTheBoundStreams`: the
 * Kotlin engine binds no streams, so there is nothing for `stop()` to
 * cancel; the `:tracker` session owns the collectors (U17). An unknown iOS
 * speed (`-1`) is Android's null.
 *
 * Then the Android scenarios the plan and parity spec B add: AE2 on a loop
 * longer than the tracking window, the short loop that arrives at Begin as
 * iOS ships it, the caller's paused clock, the per-fix order, and the
 * qualifiers of the spec's resolutions. Last, a pilgrimage stage: iOS's
 * `testTheEngineReportsWhetherItEverAnchoredOnTheWay`
 * (`PilgrimageStageWalkTests.swift@7c200bf`) and the water event's place
 * in a fix (pilgrimage-stage spec P3 §4–§5).
 */
class HonorEngineTest {

    private val departedAt = Instant.ofEpochSecond(1_000_000)
    private var nowMillis = 1_000_000_000L
    private val clock = Clock { nowMillis }
    private val events = mutableListOf<HonorEngineEvent>()

    /** The iOS tests' `clock = Date(timeIntervalSince1970: 1_000_000 + s)`. */
    private fun clockAt(seconds: Double) {
        nowMillis = ((1_000_000 + seconds) * 1000).toLong()
    }

    private fun way(id: String, route: List<WayPoint>, moments: List<WayMoment> = emptyList()) = Way(
        id = id,
        source = WaySource.OwnWalk(uuid = "5a1e0000-0000-4000-8000-000000000001"),
        title = "test",
        departedAt = departedAt,
        tzIdentifier = null,
        expires = null,
        route = route,
        totalDistanceMeters = 1000.0,
        theirActiveSeconds = 600.0,
        moments = moments,
        weather = null,
    )

    /** Out and back along the equator: 500 m east (6 points), 500 m west (5 points), 60 s per point. */
    private fun outAndBackWay(): Way {
        val out = (0..5).map { i -> WayPoint(lat = 0.0, lon = i * 0.000898, alt = null, t = i * 60.0) }
        val back = (1..5).map { i -> WayPoint(lat = 0.0, lon = (5 - i) * 0.000898, alt = null, t = (5 + i) * 60.0) }
        return way("walk:test", out + back)
    }

    /** A straight kilometre east along the equator, eleven points, 60 s apart. */
    private fun straightWay(moments: List<WayMoment> = emptyList()): Way = way(
        "walk:straight",
        (0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.000898, alt = null, t = i * 60.0) },
        moments,
    )

    /**
     * A closed square loop A→B→C→D→A with sides of [sideMeters]
     * (equirectangular metres at the equator), 60 s per side. Its first and
     * last points coincide.
     */
    private fun squareLoopWay(sideMeters: Double): Way {
        val side = sideMeters / 111_320
        val corners = listOf(0.0 to 0.0, 0.0 to side, side to side, side to 0.0, 0.0 to 0.0)
        return way(
            "walk:loop",
            corners.mapIndexed { i, (lat, lon) -> WayPoint(lat = lat, lon = lon, alt = null, t = i * 60.0) },
        )
    }

    private fun fix(
        lon: Double,
        lat: Double = 0.0,
        accuracy: Double? = 5.0,
        speed: Double? = 1.4,
        at: Double,
    ) = LocationPoint(
        timestamp = ((1_000_000 + at) * 1000).toLong(),
        latitude = lat,
        longitude = lon,
        horizontalAccuracyMeters = accuracy?.toFloat(),
        speedMetersPerSecond = speed?.toFloat(),
        altitudeMeters = 0.0,
        verticalAccuracyMeters = 5f,
        bearingDegrees = 90f,
    )

    private fun makeEngine(way: Way = outAndBackWay(), softTapEnabled: Boolean = true) = HonorEngine(
        way = way,
        softTapEnabled = softTapEnabled,
        voicesEnabled = true,
        clock = clock,
    )

    private fun HonorEngine.process(fix: LocationPoint) {
        events += processLocation(fix)
    }

    private fun arrivals(): List<Arrived> = events.filterIsInstance<Arrived>()

    private fun softTaps(): Int = events.count { it is SoftTap }

    @Test
    fun `anchors at lowest frac and follows the outbound leg`() {
        val engine = makeEngine()
        engine.process(fix(lon = 0.000898 * 0.5, at = 0.0))
        assertEquals("lowest frac within 60 m, not the return leg", 0.05, engine.startFrac ?: -1.0, 0.01)
        assertTrue(engine.isOnWay)
        // Walk out to 250 m: both legs share this pavement; progress must read 0.25, never 0.75.
        engine.process(fix(lon = 0.000898 * 2.5, at = 120.0))
        assertEquals(0.25, engine.progressFrac, 0.02)
        assertEquals(750.0, engine.distanceRemainingMeters, 30.0)
    }

    @Test
    fun `far from the Way anchors at zero and is off the Way`() {
        val engine = makeEngine()
        engine.process(fix(lon = 0.05, lat = 0.05, at = 0.0))
        assertEquals(0.0, engine.startFrac!!, 0.0)
        assertFalse(engine.isOnWay)
        assertTrue(engine.offWayMeters > 1000)
    }

    @Test
    fun `companion runs on active duration from the anchor`() {
        val engine = makeEngine()
        engine.process(fix(lon = 0.000898 * 2, at = 0.0)) // 200 m in → their t = 120 s
        assertEquals(120.0, engine.companionT0, 2.0)
        engine.updateActiveDuration(60.0)
        assertEquals(0.30, engine.companionFrac, 0.02)
        engine.updateActiveDuration(1200.0)
        assertEquals(1.0, engine.companionFrac, 0.0)
    }

    @Test
    fun `arrival needs progress and distance, not just proximity`() {
        val engine = makeEngine()
        // Standing at the start, which is also the end: three fixes must NOT arrive.
        for (i in 0 until 3) engine.process(fix(lon = 0.0, at = i.toDouble()))
        assertTrue(arrivals().isEmpty())
        assertEquals(HonorPhase.WALKING, engine.phase)
        // Walk the whole loop.
        for (i in 1..5) engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        for (i in 1..4) engine.process(fix(lon = 0.000898 * (5 - i), at = (5 + i) * 60.0))
        engine.updateActiveDuration(540.0)
        for (i in 0 until 3) engine.process(fix(lon = 0.00001, at = 600.0 + i))
        assertEquals(HonorPhase.ARRIVED, engine.phase)
        val arrived = arrivals().first()
        assertEquals(600.0, arrived.theirSeconds, 1.0)
        assertEquals(540.0, arrived.yourSeconds, 0.0)
    }

    @Test
    fun `soft tap fires once after sustained drift and re-arms`() {
        val engine = makeEngine()
        engine.process(fix(lon = 0.000898, at = 0.0))
        // 400 m north of the line, for 3 minutes.
        for (s in 10..180 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.000898, lat = 0.0036, at = s.toDouble()))
        }
        assertEquals(1, softTaps())
        clockAt(190.0)
        engine.process(fix(lon = 0.000898, lat = 0.0036, at = 190.0))
        assertEquals("no repeat while still off", 1, softTaps())
        clockAt(200.0)
        engine.process(fix(lon = 0.000898, at = 200.0))
        for (s in 210..340 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.000898, lat = 0.0036, at = s.toDouble()))
        }
        assertEquals("re-armed after returning within 60 m", 2, softTaps())
    }

    @Test
    fun `lowest frac from respects the floor`() {
        val geo = WayGeometry(outAndBackWay().route)
        val probe = WayCoordinate(lat = 0.0, lon = 0.000898 * 2.5)
        assertEquals(0.25, geo.lowestFrac(withinMeters = 60.0, of = probe)?.frac ?: -1.0, 0.01)
        assertEquals(0.75, geo.lowestFrac(withinMeters = 60.0, of = probe, fromFrac = 0.5)?.frac ?: -1.0, 0.01)
    }

    @Test
    fun `re-acquire on the return leg never falls back to the outbound leg`() {
        val engine = makeEngine()
        engine.process(fix(lon = 0.0, at = 0.0))
        for (i in 1..5) engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        for (i in 1..2) engine.process(fix(lon = 0.000898 * (5 - i), at = (5 + i) * 60.0))
        assertEquals(0.7, engine.progressFrac, 0.02)
        // Detour 400 m north for over two minutes, then rejoin at the trailhead,
        // which is frac 0 (outbound) and frac 1 (return) at once.
        for (s in 430..560 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.000898 * 2, lat = 0.0036, at = s.toDouble()))
        }
        clockAt(570.0)
        engine.process(fix(lon = 0.0, at = 570.0))
        assertTrue("the return leg's end, never the outbound leg's start", engine.progressFrac >= 0.95)
        assertTrue(engine.isOnWay)
    }

    @Test
    fun `noisy Begin re-anchors on the first on-Way fix`() {
        val engine = makeEngine()
        engine.process(fix(lon = 0.000898 * 2, lat = 0.0007, at = 0.0)) // 78 m north of the 200 m mark
        assertEquals(0.0, engine.startFrac!!, 0.0)
        assertFalse(engine.isOnWay)
        engine.process(fix(lon = 0.000898 * 2, at = 5.0))
        assertEquals(0.2, engine.startFrac ?: -1.0, 0.02)
        assertEquals(120.0, engine.companionT0, 2.0)
    }

    @Test
    fun `re-acquires after sustained off-Way`() {
        val engine = makeEngine()
        engine.process(fix(lon = 0.0, at = 0.0))
        for (s in 10..130 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.000898 * 4, lat = 0.01, at = s.toDouble()))
        }
        clockAt(140.0)
        engine.process(fix(lon = 0.000898 * 4, at = 140.0))
        assertEquals("global re-acquire takes the lowest frac within 60 m", 0.4, engine.progressFrac, 0.02)
        assertTrue(engine.isOnWay)
    }

    @Test
    fun `inaccurate fixes are ignored`() {
        val engine = makeEngine()
        engine.process(fix(lon = 0.000898, accuracy = 60.0, at = 0.0))
        assertNull("a 60 m fix must not anchor", engine.startFrac)
        engine.process(fix(lon = 0.000898, accuracy = 20.0, at = 1.0))
        assertEquals(0.1, engine.startFrac ?: -1.0, 0.02)
    }

    @Test
    fun `progress never moves back beyond the tolerance`() {
        val engine = makeEngine(way = straightWay())
        engine.process(fix(lon = 0.0, at = 0.0))
        for (i in 1..4) engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        assertEquals(0.4, engine.progressFrac, 0.02)
        engine.process(fix(lon = 0.000898 * 3.5, at = 270.0)) // 50 m back along the line
        assertEquals(
            "clamped to the window's lower edge, not frozen and not further back",
            0.4 - HonorTuning.BACKWARD_TOLERANCE,
            engine.progressFrac,
            0.005,
        )
    }

    @Test
    fun `soft tap disabled never fires`() {
        val engine = makeEngine(softTapEnabled = false)
        engine.process(fix(lon = 0.000898, at = 0.0))
        for (s in 10..300 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.000898, lat = 0.0036, at = s.toDouble()))
        }
        assertEquals(0, softTaps())
    }

    @Test
    fun `stationary jitter never advances the walk`() {
        val engine = makeEngine(way = straightWay())
        engine.process(fix(lon = 0.000898 * 5, at = 0.0))
        assertEquals(0.5, engine.startFrac ?: -1.0, 0.02)
        for (s in 1..600) {
            val jitter = if (s % 2 == 0) 0.0003 else -0.0003 // ~33 m either side
            val speed = if (s % 3 == 0) null else 0.0 // speed is irrelevant to the gate; vary it anyway
            engine.process(fix(lon = 0.000898 * 5 + jitter, speed = speed, at = s.toDouble()))
        }
        assertTrue(engine.distanceWalkedMeters < 50)
        assertTrue(engine.progressFrac < 0.56)
    }

    @Test
    fun `unknown speed still reaches arrival`() {
        val engine = makeEngine(way = straightWay())
        for (i in 0..10) engine.process(fix(lon = 0.000898 * i, speed = null, at = i * 60.0))
        for (i in 0 until 3) engine.process(fix(lon = 0.000898 * 10, speed = null, at = 660.0 + i))
        assertEquals("a stream with no speed values must still arrive", 1, arrivals().size)
    }

    @Test
    fun `mid-Way Begin can still arrive`() {
        val engine = makeEngine(way = straightWay())
        for (i in 6..10) engine.process(fix(lon = 0.000898 * i, at = (i - 6) * 60.0))
        assertEquals(0.6, engine.startFrac ?: -1.0, 0.02)
        for (i in 0 until 3) engine.process(fix(lon = 0.000898 * 10, at = 300.0 + i))
        assertEquals("half of what lay ahead at Begin is enough", 1, arrivals().size)
    }

    @Test
    fun `re-acquire is credited at the Way's own pace`() {
        val engine = makeEngine(way = straightWay())
        engine.process(fix(lon = 0.0, at = 0.0))
        for (i in 1..2) engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        assertEquals(200.0, engine.distanceWalkedMeters, 10.0)
        // Off the Way for over two minutes, then rejoin far ahead at 800 m (outside the 300 m window).
        // Pace credit is earned on WALKING time, so the active-duration
        // stream advances with the clock here exactly as on a real walk.
        for (s in 130..260 step 10) {
            clockAt(s.toDouble())
            engine.updateActiveDuration(s.toDouble())
            engine.process(fix(lon = 0.000898 * 4, lat = 0.0036, at = s.toDouble()))
        }
        clockAt(270.0)
        engine.updateActiveDuration(270.0)
        engine.process(fix(lon = 0.000898 * 8, at = 270.0))
        assertEquals("position corrected", 0.8, engine.progressFrac, 0.02)
        assertEquals("credited at the Way's pace, not the 600 m jump", 200.0 + 233, engine.distanceWalkedMeters, 15.0)
        clockAt(400.0)
        engine.updateActiveDuration(400.0)
        for (i in 0 until 4) engine.process(fix(lon = 0.000898 * 10, at = 400.0 + i))
        assertEquals(633.0, engine.distanceWalkedMeters, 25.0)
        assertEquals("an honest walker who lost signal still arrives", 1, arrivals().size)
    }

    /**
     * The honest case the pace credit exists for: signal lost through a
     * corner, back on the line 450 m later after five minutes. The stretch
     * is credited in full because it is under the Way's pace.
     */
    @Test
    fun `honest off-signal stretch is credited in full`() {
        val engine = makeEngine(way = straightWay())
        engine.process(fix(lon = 0.0, at = 0.0))
        for (i in 1..2) engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        assertEquals(200.0, engine.distanceWalkedMeters, 10.0)
        for (s in 210..500 step 10) {
            clockAt(s.toDouble())
            engine.updateActiveDuration(s.toDouble())
            engine.process(fix(lon = 0.000898 * 3.5, lat = 0.0036, at = s.toDouble()))
        }
        clockAt(510.0)
        engine.updateActiveDuration(510.0)
        engine.process(fix(lon = 0.000898 * 6.5, at = 510.0))
        assertEquals(0.65, engine.progressFrac, 0.02)
        assertEquals(
            "the 450 m jump is under 300 s of the Way's pace, so it counts in full",
            650.0,
            engine.distanceWalkedMeters,
            15.0,
        )
    }

    @Test
    fun `re-acquire credit cannot run ahead of the Way's pace`() {
        val engine = makeEngine(way = straightWay())
        engine.process(fix(lon = 0.0, at = 0.0))
        engine.process(fix(lon = 0.000898, at = 60.0))
        assertEquals(100.0, engine.distanceWalkedMeters, 10.0)
        // Drive 800 m in a car: off the line from 70 s, back on it at 900 m at 210 s.
        for (s in 70..200 step 10) {
            clockAt(s.toDouble())
            engine.updateActiveDuration(s.toDouble())
            engine.process(fix(lon = 0.000898 * 5, lat = 0.0036, at = s.toDouble()))
        }
        clockAt(210.0)
        engine.updateActiveDuration(210.0)
        engine.process(fix(lon = 0.000898 * 9, at = 210.0))
        assertEquals(0.9, engine.progressFrac, 0.02)
        assertEquals(
            "140 s off the Way earns 140 s of the Way's pace, not 800 m",
            100.0 + 233,
            engine.distanceWalkedMeters,
            15.0,
        )
        clockAt(300.0)
        engine.updateActiveDuration(300.0)
        for (i in 0 until 4) engine.process(fix(lon = 0.000898 * 10, at = 300.0 + i))
        assertEquals("433 m earned of 1000 ahead is under the half required", 0, arrivals().size)
    }

    // The re-anchor (A1, A2, A3)

    /**
     * Begin away from the Way, walk eight minutes to the trailhead, join at
     * frac 0: the companion starts where the walker does, and the approach
     * counts toward neither clock.
     */
    @Test
    fun `re-anchor restarts the companion clock and your seconds`() {
        val engine = makeEngine(way = straightWay())
        engine.updateActiveDuration(0.0)
        engine.process(fix(lon = 0.05, lat = 0.05, at = 0.0))
        assertEquals("no Way within 60 m — the fallback anchor", 0.0, engine.startFrac!!, 0.0)

        // Eight minutes of approach walking, still nowhere near the Way.
        engine.updateActiveDuration(480.0)
        clockAt(480.0)
        engine.process(fix(lon = 0.0, at = 480.0))
        assertEquals(0.0, engine.startFrac ?: -1.0, 0.001)
        assertEquals("the companion starts at the re-anchor, not 800 m ahead", 0.0, engine.companionFrac, 0.001)
        assertEquals("the re-anchor zeroes the arrival credit", 0.0, engine.distanceWalkedMeters, 0.001)

        engine.updateActiveDuration(540.0)
        assertEquals("one minute past the re-anchor is one minute of their Way", 0.1, engine.companionFrac, 0.02)
    }

    /**
     * Before the real join, the fallback anchor gives the companion nowhere
     * real to walk to — it must wait at the start rather than racing ahead
     * on the approach walk's own active duration.
     */
    @Test
    fun `companion waits at the start during the approach walk`() {
        val engine = makeEngine(way = straightWay())
        engine.updateActiveDuration(0.0)
        engine.process(fix(lon = 0.05, lat = 0.05, at = 0.0))
        assertEquals("no Way within 60 m — the fallback anchor", 0.0, engine.startFrac!!, 0.0)

        // Four minutes of approach walking, still nowhere near the Way.
        engine.updateActiveDuration(240.0)
        assertEquals("the dot waits at the start until the Way is joined", 0.0, engine.companionFrac, 0.001)

        // The real join, at the Way's own start.
        engine.updateActiveDuration(480.0)
        clockAt(480.0)
        engine.process(fix(lon = 0.0, at = 480.0))
        assertEquals("still at the start the instant it joins", 0.0, engine.companionFrac, 0.001)

        engine.updateActiveDuration(540.0)
        assertEquals("the companion resumes once the Way is actually joined", 0.1, engine.companionFrac, 0.02)
    }

    @Test
    fun `your seconds excludes the approach walk`() {
        val engine = makeEngine(way = straightWay())
        engine.updateActiveDuration(0.0)
        engine.process(fix(lon = 0.05, lat = 0.05, at = 0.0))
        engine.updateActiveDuration(480.0)
        clockAt(480.0)
        engine.process(fix(lon = 0.0, at = 480.0))

        for (i in 1..10) {
            val seconds = 480.0 + i * 60
            engine.updateActiveDuration(seconds)
            engine.process(fix(lon = 0.000898 * i, at = seconds))
        }
        engine.updateActiveDuration(1140.0)
        for (i in 0 until 4) engine.process(fix(lon = 0.000898 * 10, at = 1140.0 + i))

        // 1140 s of walk, 480 s of it spent reaching the trailhead.
        assertEquals(
            "the eight-minute approach is not part of the walker's time on the Way",
            660.0,
            arrivals().firstOrNull()?.yourSeconds ?: -1.0,
            1.0,
        )
    }

    /**
     * A2: the walker has not joined the Way yet, so "off the way" is the
     * wrong word — the soft tap stays quiet until something is anchored.
     */
    @Test
    fun `soft tap stays quiet during the approach walk`() {
        val engine = makeEngine(way = straightWay())
        for (s in 0..200 step 10) {
            clockAt(s.toDouble())
            engine.updateActiveDuration(s.toDouble())
            engine.process(fix(lon = 0.05, lat = 0.05, at = s.toDouble()))
        }
        assertEquals("nothing has been joined, so nothing has been left", 0, softTaps())
    }

    /**
     * A3: a walk paused off the Way must not convert paused time into
     * arrival credit — pace is earned by walking, not by waiting.
     */
    @Test
    fun `paused time off the Way earns no pace credit`() {
        val engine = makeEngine(way = straightWay())
        engine.updateActiveDuration(0.0)
        engine.process(fix(lon = 0.0, at = 0.0))
        for (i in 1..2) {
            engine.updateActiveDuration(i * 60.0)
            engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        }
        assertEquals(200.0, engine.distanceWalkedMeters, 10.0)

        // Five minutes pass on the wall clock with the walk paused: the
        // active-duration stream never moves.
        for (s in 130..500 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.000898 * 4, lat = 0.0036, at = s.toDouble()))
        }
        clockAt(510.0)
        engine.process(fix(lon = 0.000898 * 8, at = 510.0))

        assertEquals("position still corrects", 0.8, engine.progressFrac, 0.02)
        assertEquals("paused time buys no credit", 200.0, engine.distanceWalkedMeters, 1.0)
    }

    // Degenerate geometry

    @Test
    fun `engine on a Way of identical points does not trap`() {
        val route = (0..4).map { i -> WayPoint(lat = 0.0, lon = 0.0, alt = null, t = i * 60.0) }
        val engine = makeEngine(way = way("walk:degenerate", route))

        engine.updateActiveDuration(60.0)
        engine.process(fix(lon = 0.0, at = 0.0))
        engine.process(fix(lon = 0.05, lat = 0.05, at = 60.0))
        engine.updateActiveDuration(600.0)

        assertTrue("the no-segment sentinel must never reach a caller as an infinity", engine.offWayMeters.isFinite())
        assertTrue(engine.offWayMeters <= 100_000)
    }

    @Test
    fun `voice did finish with nothing playing is inert`() {
        val engine = makeEngine(way = straightWay())
        events += engine.voiceDidFinish()
        events += engine.voiceDidFinish()
        assertTrue("a finish for a voice that never started must say nothing", events.isEmpty())
    }

    @Test
    fun `soft tap stops after arrival`() {
        val engine = makeEngine(way = straightWay())
        for (i in 0..10) engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        for (i in 0 until 3) engine.process(fix(lon = 0.000898 * 10, at = 660.0 + i))
        assertEquals(HonorPhase.ARRIVED, engine.phase)
        for (s in 700..900 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.000898 * 10, lat = 0.0036, at = s.toDouble()))
        }
        assertEquals(0, softTaps())
    }

    // Android scenarios: AE2, the short loop, and the caller's paused clock

    /**
     * AE2 on a loop longer than the 300 m tracking window: standing at the
     * trailhead, which is also the end, never arrives, however long the
     * walker stands there, even a few metres up the closing leg as in the
     * short-loop test below; walking the loop arrives exactly once.
     */
    @Test
    fun `a loop longer than the tracking window never arrives at Begin and arrives exactly once later`() {
        val engine = makeEngine(way = squareLoopWay(sideMeters = 250.0))
        val threeMetres = 3.0 / 111_320
        for (i in 0 until 20) engine.process(fix(lon = 0.0, lat = threeMetres, at = i.toDouble()))
        assertEquals(0.0, engine.startFrac!!, 1e-9)
        assertEquals("held on the opening leg", 0.0, engine.progressFrac, 1e-9)
        assertTrue(arrivals().isEmpty())

        val side = 250.0 / 111_320
        val step = side / 5
        var at = 20.0
        for (i in 1..5) engine.process(fix(lon = step * i, lat = 0.0, at = at++))
        for (i in 1..5) engine.process(fix(lon = side, lat = step * i, at = at++))
        for (i in 1..5) engine.process(fix(lon = side - step * i, lat = side, at = at++))
        for (i in 1..4) engine.process(fix(lon = 0.0, lat = side - step * i, at = at++))
        assertTrue("not yet within 30 m of the end", arrivals().isEmpty())
        for (i in 0 until 10) engine.process(fix(lon = 0.0, lat = threeMetres, at = at++))

        assertEquals(1, arrivals().size)
        assertEquals(HonorPhase.ARRIVED, engine.phase)
    }

    /**
     * iOS as shipped, not a goal: on a loop within the 300 m window, the
     * Begin fix is tracked through a window that reaches the closing leg,
     * so a walker a few metres up that leg is credited with the whole loop
     * and arrives on the third fix (pilgrim-ios #100, parity spec B §11.3).
     * Change this test only when iOS fixes the issue and the fix folds in.
     */
    @Test
    fun `a loop within the tracking window arrives at Begin, as iOS ships it (pilgrim-ios #100)`() {
        val engine = makeEngine(way = squareLoopWay(sideMeters = 70.0))
        val beginFix = fix(lon = 0.0, lat = 3.0 / 111_320, at = 0.0)

        engine.process(beginFix)
        assertEquals("anchored on the opening leg", 0.0, engine.startFrac!!, 1e-9)
        assertTrue("but tracked onto the closing leg", engine.progressFrac > 0.98)
        engine.process(beginFix.copy(timestamp = beginFix.timestamp + 1_000))
        assertTrue(arrivals().isEmpty())
        engine.process(beginFix.copy(timestamp = beginFix.timestamp + 2_000))

        assertEquals("arrived on the third fix at the trailhead", 1, arrivals().size)
    }

    /**
     * Owner decision 1: the caller's engine clock excludes paused time,
     * including the pause in progress, so the companion holds still through
     * a pause, and arrival's own time is the companion's clock.
     */
    @Test
    fun `the companion holds through a pause and the arrival delta runs on its clock`() {
        val engine = makeEngine(way = straightWay())
        events += engine.setGates(paused = false, meditating = false, recording = false, externalAudio = false)
        engine.updateActiveDuration(0.0)
        engine.process(fix(lon = 0.0, at = 0.0))
        for (i in 1..2) {
            clockAt(i * 60.0)
            engine.updateActiveDuration(i * 60.0)
            engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        }
        val beforePause = engine.companionFrac
        assertEquals(0.2, beforePause, 1e-9)

        events += engine.setGates(paused = true, meditating = false, recording = false, externalAudio = false)
        for (s in 130..420 step 10) {
            clockAt(s.toDouble())
            engine.updateActiveDuration(120.0)
            engine.process(fix(lon = 0.000898 * 2, at = s.toDouble()))
            assertEquals("frozen at ${s}s of wall time", beforePause, engine.companionFrac, 0.0)
        }
        events += engine.setGates(paused = false, meditating = false, recording = false, externalAudio = false)

        for (i in 3..10) {
            clockAt(300.0 + i * 60)
            engine.updateActiveDuration(i * 60.0)
            engine.process(fix(lon = 0.000898 * i, at = 300.0 + i * 60))
        }
        for (i in 1..3) engine.process(fix(lon = 0.000898 * 10, at = 900.0 + i))

        val arrived = arrivals().single()
        assertEquals("the five-minute pause is not the walker's time", 600.0, arrived.yourSeconds, 0.0)
        assertEquals(600.0, arrived.theirSeconds, 1e-9)
        assertEquals("the companion reached the end on the same clock", 1.0, engine.companionFrac, 0.0)
    }

    // Android scenarios: the order of one fix, and the gates

    @Test
    fun `the arriving fix reports arrival before that fix's moments`() {
        val beyondTheEnd = WayMoment(
            id = "waypoint-1",
            frac = 1.0,
            at = WayCoordinate(lat = 0.0, lon = 0.000898 * 10 + 85.0 / 111_320),
            kind = WayMomentKind.Waypoint(label = "bench", icon = "leaf"),
        )
        val engine = makeEngine(way = straightWay(moments = listOf(beyondTheEnd)))
        for (i in 0..9) engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        engine.process(fix(lon = 0.000898 * 10 - 25.0 / 111_320, at = 600.0))
        engine.process(fix(lon = 0.000898 * 10 - 10.0 / 111_320, at = 601.0))
        assertTrue(events.none { it is Arrived || it is MomentReached })

        val arriving = engine.processLocation(fix(lon = 0.000898 * 10 + 28.0 / 111_320, at = 602.0))

        assertEquals(2, arriving.size)
        assertTrue(arriving[0] is Arrived)
        assertEquals(MomentReached(beyondTheEnd), arriving[1])
    }

    @Test
    fun `a closed gate holds voices while every fix is still processed`() {
        val photo = WayMoment(
            id = "photo-1",
            frac = 0.5,
            at = WayCoordinate(lat = 0.0, lon = 0.000898 * 5),
            kind = WayMomentKind.Photo(media = WayMedia.PhotoAsset("content://media/external/images/media/1")),
        )
        val voice = WayMoment(
            id = "voice-1",
            frac = 0.9,
            at = WayCoordinate(lat = 0.0, lon = 0.000898 * 9),
            kind = WayMomentKind.Voice(
                endFrac = 0.95,
                duration = 30.0,
                kind = VoiceKind.SPOKEN,
                media = WayMedia.Recording("recordings/1.m4a"),
            ),
        )
        val engine = makeEngine(way = straightWay(moments = listOf(photo, voice)))
        events += engine.setGates(paused = true, meditating = false, recording = false, externalAudio = false)

        for (i in 0..10) engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        for (i in 1..3) engine.process(fix(lon = 0.000898 * 10, at = 600.0 + i))

        assertEquals(1.0, engine.progressFrac, 1e-9)
        assertEquals(listOf(MomentReached(photo)), events.filterIsInstance<MomentReached>())
        assertEquals(1, arrivals().size)
        assertTrue("the voice waits behind the gate", events.none { it is VoiceStart })
        assertEquals(
            listOf<HonorEngineEvent>(VoiceStart(voice)),
            engine.setGates(paused = false, meditating = false, recording = false, externalAudio = false),
        )
    }

    // Android scenarios: the spec's qualifiers

    @Test
    fun `a fallback Begin joined beyond the tracking window re-anchors only through the re-acquire`() {
        val engine = makeEngine(way = straightWay())
        clockAt(0.0)
        engine.process(fix(lon = 0.05, lat = 0.05, at = 0.0))
        clockAt(60.0)
        engine.process(fix(lon = 0.000898 * 5, at = 60.0))
        assertEquals("500 m in is beyond the window over [0, 300 m]", 0.0, engine.startFrac!!, 0.0)
        assertFalse(engine.isAnchoredOnWay)
        assertFalse(engine.isOnWay)

        clockAt(120.0)
        engine.process(fix(lon = 0.000898 * 5, at = 120.0))

        assertTrue(engine.isAnchoredOnWay)
        assertEquals(0.5, engine.startFrac!!, 0.01)
        assertEquals(300.0, engine.companionT0, 2.0)
        assertEquals(0.0, engine.distanceWalkedMeters, 0.0)
    }

    @Test
    fun `a failed re-acquire retries every 10 s, not on every fix`() {
        val engine = makeEngine(way = straightWay())
        clockAt(0.0)
        engine.process(fix(lon = 0.0, at = 0.0))
        for (s in 10..130 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.000898 * 2, lat = 0.0036, at = s.toDouble()))
        }
        clockAt(135.0)
        engine.process(fix(lon = 0.000898 * 8, at = 135.0))
        assertFalse("5 s after the failed attempt at 130 s", engine.isOnWay)
        assertEquals(0.0, engine.progressFrac, 0.0)

        clockAt(140.0)
        engine.process(fix(lon = 0.000898 * 8, at = 140.0))
        assertTrue(engine.isOnWay)
        assertEquals(0.8, engine.progressFrac, 0.01)
    }

    @Test
    fun `a fix between 60 and 200 m off restarts the soft tap's count`() {
        val engine = makeEngine(way = straightWay())
        clockAt(0.0)
        engine.process(fix(lon = 0.000898, at = 0.0))
        for (s in 10..100 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.000898, lat = 0.0036, at = s.toDouble()))
        }
        clockAt(110.0)
        engine.process(fix(lon = 0.000898, lat = 0.0009, at = 110.0)) // 100 m off
        for (s in 120..230 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.000898, lat = 0.0036, at = s.toDouble()))
        }
        assertEquals("the count restarted at 120 s", 0, softTaps())
        clockAt(240.0)
        engine.process(fix(lon = 0.000898, lat = 0.0036, at = 240.0))
        assertEquals(1, softTaps())
    }

    @Test
    fun `a fix with no accuracy or a negative one is dropped`() {
        val engine = makeEngine()
        engine.process(fix(lon = 0.000898, accuracy = null, at = 0.0))
        engine.process(fix(lon = 0.000898, accuracy = -1.0, at = 1.0))
        assertNull(engine.startFrac)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a dropped fix neither advances nor breaks the arrival count`() {
        val engine = makeEngine(way = straightWay())
        for (i in 0..10) engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        engine.process(fix(lon = 0.000898 * 10, at = 601.0))
        assertTrue("two inside fixes so far", arrivals().isEmpty())
        engine.process(fix(lon = 0.000898 * 10, accuracy = 60.0, at = 602.0))
        engine.process(fix(lon = 0.000898 * 10, accuracy = null, at = 603.0))
        assertTrue(arrivals().isEmpty())
        engine.process(fix(lon = 0.000898 * 10, at = 604.0))
        assertEquals(1, arrivals().size)
    }

    @Test
    fun `the injected distance decides arrival`() {
        val engine = HonorEngine(
            way = straightWay(),
            softTapEnabled = true,
            voicesEnabled = true,
            clock = clock,
            distance = { _, _ -> 31.0 },
        )
        for (i in 0..10) engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        for (i in 1..5) engine.process(fix(lon = 0.000898 * 10, at = 600.0 + i))
        assertEquals("projection still tracks on the Way's own geometry", 1.0, engine.progressFrac, 1e-9)
        assertTrue(arrivals().isEmpty())
    }

    // A pilgrimage stage

    /** iOS's `stageWay()`: a 1 km stage east along the equator, with water at [waterFracs]. */
    private fun stageWay(vararg waterFracs: Double, moments: List<WayMoment> = emptyList()) = straightWay(moments).copy(
        id = "pilgrimage:camino-frances:0",
        source = WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = 0),
        marks = waterFracs.mapIndexed { i, frac ->
            WayMark(
                id = "wp-osm-water-node$i",
                kind = WayMarkKind.WATER,
                name = "Fuente $i",
                at = WayCoordinate(lat = 0.0, lon = frac * 1000 / 111_320),
                frac = frac,
                offLineMeters = 10.0,
            )
        },
        stage = WayStage(
            routeId = "camino-frances", index = 0, count = 33, name = "Saint-Jean-Pied-de-Port to Roncesvalles",
            theme = "Initiation", narrative = "The Pyrenees are the first question the way asks.",
            closing = "You crossed a border on foot.", warnings = emptyList(), distanceKm = 24.2, gainMeters = 1419.0,
            hours = WayStageHours(min = 7.0, max = 9.0), difficulty = "hard",
            start = WayStagePlace(name = "Saint-Jean-Pied-de-Port", at = WayCoordinate(lat = 0.0, lon = 0.0)),
            end = WayStagePlace(name = "Roncesvalles", at = WayCoordinate(lat = 0.0, lon = 0.00898)),
        ),
    )

    @Test
    fun testTheEngineReportsWhetherItEverAnchoredOnTheWay() {
        val engine = HonorEngine(way = stageWay(), softTapEnabled = false, voicesEnabled = false, clock = clock)
        assertFalse("no fix yet", engine.isAnchoredOnWay)
        // A kilometre north of the line: Begin falls back to frac 0.
        engine.process(fix(lon = 0.0, lat = 0.01, at = 0.0))
        assertFalse("the frac-0 fallback is not a stage joined", engine.isAnchoredOnWay)
        clockAt(60.0)
        engine.process(fix(lon = 0.000898, at = 60.0))
        assertTrue(engine.isAnchoredOnWay)
    }

    @Test
    fun `a stage's water comes after the places a fix reaches, with the metres left`() {
        val orisson = WayMoment(
            id = "wp-orisson",
            frac = 0.3,
            at = WayCoordinate(lat = 0.0, lon = 300.0 / 111_320),
            kind = WayMomentKind.Waypoint(label = "Vierge d'Orisson", icon = "building.columns"),
        )
        val engine = HonorEngine(
            way = stageWay(0.5, moments = listOf(orisson)),
            softTapEnabled = false,
            voicesEnabled = false,
            clock = clock,
        )

        engine.process(fix(lon = 0.000898 * 3, at = 0.0))

        assertEquals(listOf(MomentReached::class, MarkAhead::class), events.map { it::class })
        val water = events.last() as MarkAhead
        assertEquals("wp-osm-water-node0", water.mark.id)
        assertEquals(200.0, water.meters, 2.0)
    }

    @Test
    fun `water is weighed only on a fix, never on a tick or a gate`() {
        val engine = HonorEngine(way = stageWay(0.2, 0.6), softTapEnabled = false, voicesEnabled = false, clock = clock)
        for (i in 0..4) {
            clockAt(i * 100.0)
            engine.process(fix(lon = 0.000898 * i, at = i * 100.0))
        }
        val spoken = events.filterIsInstance<MarkAhead>().map { it.mark.id }
        assertEquals("the first is free, at clock 0", listOf("wp-osm-water-node0"), spoken)
        events.clear()

        engine.updateActiveDuration(3_700.0)
        events += engine.setGates(paused = false, meditating = true, recording = false, externalAudio = false)
        assertTrue("the hour ended on a tick, and nothing spoke", events.isEmpty())

        clockAt(410.0)
        engine.process(fix(lon = 0.000898 * 4, at = 410.0))
        assertEquals(listOf("wp-osm-water-node1"), events.filterIsInstance<MarkAhead>().map { it.mark.id })
    }

    @Test
    fun `an own walk's Way carries no marks and never speaks of water`() {
        val engine = makeEngine(way = straightWay())
        for (i in 0..10) {
            clockAt(i * 60.0)
            engine.updateActiveDuration(i * 4_000.0)
            engine.process(fix(lon = 0.000898 * i, at = i * 60.0))
        }
        assertTrue(events.none { it is MarkAhead })
    }

    @Test
    fun `an empty Way is inert`() {
        val engine = makeEngine(way = way("walk:empty", emptyList()))
        engine.updateActiveDuration(60.0)
        for (s in 0..300 step 10) {
            clockAt(s.toDouble())
            engine.process(fix(lon = 0.0, at = s.toDouble()))
        }
        assertEquals(0.0, engine.startFrac!!, 0.0)
        assertFalse(engine.isAnchoredOnWay)
        assertEquals(100_000.0, engine.offWayMeters, 0.0)
        assertTrue(events.isEmpty())
    }
}
