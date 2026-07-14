// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Parity: iOS `SeekStillnessDetectorTests.swift@c1745e8` restricted to the
 * displacement-only path — the committed Android baseline (port spec D3,
 * docs/parity/2026-07-14-port-seek-engine-u3.md).
 */
class SeekStillnessDetectorTest {

    private val t0 = 1_000_000L
    private val base = SeekPoint(latitude = 42.0, longitude = -8.0)

    private fun makeDetector(baseWindowMillis: Long = 60_000L): SeekStillnessDetector =
        SeekStillnessDetector(baseWindowMillis).also { it.start() }

    private fun fix(metersNorth: Double = 0.0, accuracy: Float? = 10f): LocationPoint {
        val point = SeekChainGenerator.destination(
            from = base,
            bearingDegrees = 0.0,
            distanceMeters = metersNorth,
        )
        return LocationPoint(
            timestamp = t0,
            latitude = point.latitude,
            longitude = point.longitude,
            horizontalAccuracyMeters = accuracy,
        )
    }

    @Test
    fun `window is lengthened by the displacement multiplier`() {
        val detector = makeDetector(baseWindowMillis = 60_000L)
        assertEquals(90_000L, detector.windowDurationMillis)
    }

    @Test
    fun `still fixes begin then complete after window`() {
        val detector = makeDetector()
        detector.recordLocation(fix())
        detector.recordLocation(fix(metersNorth = 3.0))

        assertEquals(SeekStillnessDetector.Update.BEGAN, detector.evaluate(t0))
        assertEquals(SeekStillnessDetector.Update.NONE, detector.evaluate(t0 + 89_999))
        assertEquals(SeekStillnessDetector.Update.COMPLETED, detector.evaluate(t0 + 90_000))
        assertEquals(
            "completion fires once",
            SeekStillnessDetector.Update.NONE,
            detector.evaluate(t0 + 91_000),
        )
    }

    @Test
    fun `fewer than two good fixes never begins`() {
        val detector = makeDetector()
        detector.recordLocation(fix())
        assertEquals(SeekStillnessDetector.Update.NONE, detector.evaluate(t0))
        assertEquals(SeekStillnessDetector.Update.NONE, detector.evaluate(t0 + 300_000))
    }

    @Test
    fun `displacement veto reads as not still`() {
        val detector = makeDetector()
        detector.recordLocation(fix())
        detector.recordLocation(fix(metersNorth = 30.0))
        assertEquals(SeekStillnessDetector.Update.NONE, detector.evaluate(t0))
    }

    @Test
    fun `veto re-anchors at newest fix so a settling walker can begin fresh`() {
        val detector = makeDetector()
        detector.recordLocation(fix())
        detector.recordLocation(fix(metersNorth = 30.0))
        assertEquals(SeekStillnessDetector.Update.NONE, detector.evaluate(t0))

        detector.recordLocation(fix(metersNorth = 32.0))
        assertEquals(SeekStillnessDetector.Update.BEGAN, detector.evaluate(t0 + 5_000))
        assertEquals(
            SeekStillnessDetector.Update.COMPLETED,
            detector.evaluate(t0 + 5_000 + 90_000),
        )
    }

    @Test
    fun `veto breaks a running window and the restarted window counts from the new begin`() {
        val detector = makeDetector()
        detector.recordLocation(fix())
        detector.recordLocation(fix(metersNorth = 3.0))
        assertEquals(SeekStillnessDetector.Update.BEGAN, detector.evaluate(t0))

        detector.recordLocation(fix(metersNorth = 40.0))
        assertEquals(SeekStillnessDetector.Update.NONE, detector.evaluate(t0 + 30_000))

        detector.recordLocation(fix(metersNorth = 42.0))
        assertEquals(SeekStillnessDetector.Update.BEGAN, detector.evaluate(t0 + 35_000))
        assertEquals(SeekStillnessDetector.Update.NONE, detector.evaluate(t0 + 35_000 + 89_999))
        assertEquals(
            SeekStillnessDetector.Update.COMPLETED,
            detector.evaluate(t0 + 35_000 + 90_000),
        )
    }

    @Test
    fun `low accuracy fixes do not feed displacement`() {
        val detector = makeDetector()
        detector.recordLocation(fix(accuracy = 80f))
        detector.recordLocation(fix(metersNorth = 30.0, accuracy = 80f))
        assertEquals(
            "bad-accuracy movement must neither vote nor veto",
            SeekStillnessDetector.Update.NONE,
            detector.evaluate(t0),
        )

        detector.recordLocation(fix())
        detector.recordLocation(fix(metersNorth = 3.0))
        assertEquals(SeekStillnessDetector.Update.BEGAN, detector.evaluate(t0 + 5_000))
    }

    @Test
    fun `null accuracy fixes do not feed displacement`() {
        val detector = makeDetector()
        detector.recordLocation(fix(accuracy = null))
        detector.recordLocation(fix(metersNorth = 3.0, accuracy = null))
        assertEquals(SeekStillnessDetector.Update.NONE, detector.evaluate(t0))
    }

    @Test
    fun `suspend freezes evaluation and resume restarts window`() {
        val detector = makeDetector()
        detector.recordLocation(fix())
        detector.recordLocation(fix(metersNorth = 3.0))
        assertEquals(SeekStillnessDetector.Update.BEGAN, detector.evaluate(t0))

        detector.suspend()
        assertEquals(
            "suspended detector stays silent",
            SeekStillnessDetector.Update.NONE,
            detector.evaluate(t0 + 120_000),
        )

        detector.resume()
        detector.recordLocation(fix())
        detector.recordLocation(fix(metersNorth = 2.0))
        val resumeTime = t0 + 120_000
        assertEquals(SeekStillnessDetector.Update.BEGAN, detector.evaluate(resumeTime))
        assertEquals(
            SeekStillnessDetector.Update.COMPLETED,
            detector.evaluate(resumeTime + 90_000),
        )
    }

    @Test
    fun `resume discards the pre-suspension anchor`() {
        val detector = makeDetector()
        detector.recordLocation(fix())
        detector.suspend()
        detector.resume()
        detector.recordLocation(fix(metersNorth = 30.0))
        detector.recordLocation(fix(metersNorth = 32.0))
        assertEquals(
            "displacement is measured from the post-resume anchor, not the stale one",
            SeekStillnessDetector.Update.BEGAN,
            detector.evaluate(t0),
        )
    }

    @Test
    fun `stop silences evaluation`() {
        val detector = makeDetector()
        detector.recordLocation(fix())
        detector.recordLocation(fix(metersNorth = 3.0))
        detector.stop()
        assertEquals(SeekStillnessDetector.Update.NONE, detector.evaluate(t0))
    }
}
