// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity: iOS `SeekLiveActivityTests.swift@c1745e8` (glance model half)
 * plus the plan's Android-only speed-floor bracketing. Port spec:
 * docs/parity/2026-07-14-port-seek-glance-u10.md.
 */
class SeekGlanceModelTest {

    // ─── Distance buckets ─────────────────────────────────────────────

    @Test
    fun `distance bucket floors to hundred meter steps`() {
        assertEquals(0, SeekGlanceModel.distanceBucket(0.0))
        assertEquals(0, SeekGlanceModel.distanceBucket(99.9))
        assertEquals(100, SeekGlanceModel.distanceBucket(100.0))
        assertEquals(100, SeekGlanceModel.distanceBucket(150.0))
        assertEquals(900, SeekGlanceModel.distanceBucket(999.0))
        assertEquals(1900, SeekGlanceModel.distanceBucket(1999.0))
    }

    @Test
    fun `distance bucket caps at max`() {
        assertEquals(2000, SeekGlanceModel.distanceBucket(2000.0))
        assertEquals(2000, SeekGlanceModel.distanceBucket(2050.0))
        assertEquals(2000, SeekGlanceModel.distanceBucket(12_000.0))
    }

    @Test
    fun `distance bucket clamps negative to zero`() {
        assertEquals(0, SeekGlanceModel.distanceBucket(-5.0))
    }

    @Test
    fun `distance bucket is monotonic`() {
        val buckets = generateSequence(0.0) { it + 25.0 }
            .takeWhile { it <= 3_000.0 }
            .map { SeekGlanceModel.distanceBucket(it) }
            .toList()
        assertEquals(buckets, buckets.sorted())
    }

    // ─── Direction hint quadrants ─────────────────────────────────────

    @Test
    fun `direction hint quadrants`() {
        assertEquals(SeekDirectionHint.AHEAD, SeekGlanceModel.directionHint(0.0, 0.0))
        assertEquals(SeekDirectionHint.RIGHT, SeekGlanceModel.directionHint(0.0, 90.0))
        assertEquals(SeekDirectionHint.BEHIND, SeekGlanceModel.directionHint(0.0, 180.0))
        assertEquals(SeekDirectionHint.LEFT, SeekGlanceModel.directionHint(0.0, 270.0))
    }

    @Test
    fun `direction hint cone boundaries`() {
        assertEquals(SeekDirectionHint.AHEAD, SeekGlanceModel.directionHint(0.0, 45.0))
        assertEquals(SeekDirectionHint.RIGHT, SeekGlanceModel.directionHint(0.0, 46.0))
        assertEquals(SeekDirectionHint.AHEAD, SeekGlanceModel.directionHint(0.0, 315.0))
        assertEquals(SeekDirectionHint.LEFT, SeekGlanceModel.directionHint(0.0, 314.0))
        assertEquals(SeekDirectionHint.BEHIND, SeekGlanceModel.directionHint(0.0, 135.0))
        assertEquals(SeekDirectionHint.RIGHT, SeekGlanceModel.directionHint(0.0, 134.0))
    }

    @Test
    fun `direction hint is course relative across north wrap`() {
        assertEquals(SeekDirectionHint.AHEAD, SeekGlanceModel.directionHint(350.0, 10.0))
        assertEquals(SeekDirectionHint.AHEAD, SeekGlanceModel.directionHint(10.0, 350.0))
        assertEquals(SeekDirectionHint.RIGHT, SeekGlanceModel.directionHint(350.0, 80.0))
        assertEquals(SeekDirectionHint.LEFT, SeekGlanceModel.directionHint(90.0, 350.0))
    }

    // ─── Glance assembly ──────────────────────────────────────────────

    @Test
    fun `invalid course hides hint but keeps distance`() {
        val glance = SeekGlanceModel.glance(
            distanceToActiveMeters = 420.0,
            courseDegrees = -1.0,
            speedMetersPerSecond = 1.4,
            bearingToClearingDegrees = 90.0,
            phase = SeekEnginePhase.GUIDING,
        )
        assertEquals(400, glance?.distanceBucketMeters)
        assertNull(glance?.directionHint)
        assertEquals(false, glance?.isComplete)
    }

    @Test
    fun `null course hides hint but keeps distance`() {
        val glance = SeekGlanceModel.glance(
            distanceToActiveMeters = 420.0,
            courseDegrees = null,
            speedMetersPerSecond = 1.4,
            bearingToClearingDegrees = 90.0,
            phase = SeekEnginePhase.GUIDING,
        )
        assertEquals(400, glance?.distanceBucketMeters)
        assertNull(glance?.directionHint)
    }

    @Test
    fun `stationary speed hides hint but keeps distance`() {
        val glance = SeekGlanceModel.glance(
            distanceToActiveMeters = 420.0,
            courseDegrees = 90.0,
            speedMetersPerSecond = 0.2,
            bearingToClearingDegrees = 90.0,
            phase = SeekEnginePhase.GUIDING,
        )
        assertEquals(400, glance?.distanceBucketMeters)
        assertNull(glance?.directionHint)
    }

    @Test
    fun `speed just below the floor hides hint and just above shows it`() {
        fun glanceAtSpeed(speed: Double) = SeekGlanceModel.glance(
            distanceToActiveMeters = 420.0,
            courseDegrees = 0.0,
            speedMetersPerSecond = speed,
            bearingToClearingDegrees = 90.0,
            phase = SeekEnginePhase.GUIDING,
        )
        assertNull(glanceAtSpeed(0.39)?.directionHint)
        assertEquals(SeekDirectionHint.RIGHT, glanceAtSpeed(0.41)?.directionHint)
    }

    @Test
    fun `moving at the exact speed floor shows hint`() {
        val glance = SeekGlanceModel.glance(
            distanceToActiveMeters = 420.0,
            courseDegrees = 0.0,
            speedMetersPerSecond = SeekGlanceModel.STATIONARY_SPEED_FLOOR_METERS_PER_SECOND,
            bearingToClearingDegrees = 90.0,
            phase = SeekEnginePhase.GUIDING,
        )
        assertEquals(SeekDirectionHint.RIGHT, glance?.directionHint)
    }

    @Test
    fun `missing bearing hides hint but keeps distance`() {
        val glance = SeekGlanceModel.glance(
            distanceToActiveMeters = 420.0,
            courseDegrees = 0.0,
            speedMetersPerSecond = 1.4,
            bearingToClearingDegrees = null,
            phase = SeekEnginePhase.GUIDING,
        )
        assertEquals(400, glance?.distanceBucketMeters)
        assertNull(glance?.directionHint)
    }

    @Test
    fun `complete phase ignores distance inputs`() {
        val glance = SeekGlanceModel.glance(
            distanceToActiveMeters = null,
            courseDegrees = null,
            speedMetersPerSecond = null,
            bearingToClearingDegrees = null,
            phase = SeekEnginePhase.COMPLETE,
        )
        assertEquals(true, glance?.isComplete)
        assertEquals(0, glance?.distanceBucketMeters)
        assertNull(glance?.directionHint)
    }

    @Test
    fun `without distance returns null`() {
        assertNull(
            SeekGlanceModel.glance(
                distanceToActiveMeters = null,
                courseDegrees = 90.0,
                speedMetersPerSecond = 1.4,
                bearingToClearingDegrees = 45.0,
                phase = SeekEnginePhase.GUIDING,
            ),
        )
    }

    @Test
    fun `all null inputs return null`() {
        assertNull(
            SeekGlanceModel.glance(
                distanceToActiveMeters = null,
                courseDegrees = null,
                speedMetersPerSecond = null,
                bearingToClearingDegrees = null,
                phase = SeekEnginePhase.GUIDING,
            ),
        )
    }

    @Test
    fun `arrived phase inside the clearing buckets to close range`() {
        val glance = SeekGlanceModel.glance(
            distanceToActiveMeters = 40.0,
            courseDegrees = null,
            speedMetersPerSecond = null,
            bearingToClearingDegrees = null,
            phase = SeekEnginePhase.ARRIVED,
        )
        assertEquals(0, glance?.distanceBucketMeters)
        assertEquals(false, glance?.isComplete)
    }

    @Test
    fun `structural equality distinguishes bucket hint and completion`() {
        val a = SeekGlanceState(400, SeekDirectionHint.AHEAD, isComplete = false)
        assertEquals(SeekGlanceState(400, SeekDirectionHint.AHEAD, isComplete = false), a)
        assertFalse(a == SeekGlanceState(300, SeekDirectionHint.AHEAD, isComplete = false))
        assertFalse(a == SeekGlanceState(400, SeekDirectionHint.LEFT, isComplete = false))
        assertFalse(a == SeekGlanceState(400, SeekDirectionHint.AHEAD, isComplete = true))
        assertTrue(a != null as SeekGlanceState?)
    }
}
