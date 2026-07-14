// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors iOS `UnitTests/Seek/SeekFogStateTests.swift@c1745e8` including
 * the wisp cases (Android: crescent, U7). Port specs
 * `docs/parity/2026-07-14-port-seek-fog-u6.md` and
 * `docs/parity/2026-07-14-port-seek-crescent-u7.md`.
 */
class SeekFogModelTest {

    private fun makeChain(count: Int): SeekChain {
        val clearings = (0 until count).map { index ->
            SeekClearing(
                center = SeekPoint(latitude = 42.0 + index * 0.01, longitude = -8.5),
                radiusMeters = 50.0,
            )
        }
        return SeekChain(clearings = clearings, budgetMeters = 4000.0)
    }

    private fun state(
        count: Int = 3,
        activeIndex: Int = 0,
        phase: SeekEnginePhase = SeekEnginePhase.GUIDING,
        distance: Double? = null,
        previousBucket: Int? = null,
    ): SeekFogState = SeekFogModel.fogState(
        chain = makeChain(count),
        activeIndex = activeIndex,
        phase = phase,
        distanceToActiveMeters = distance,
        previousActiveBucket = previousBucket,
    )

    // Count-hiding invariant (origin R6)

    @Test
    fun `chain of three with first active exposes exactly one circle`() {
        val fog = state(count = 3, activeIndex = 0)
        assertEquals(1, fog.circles.size)
        assertEquals("seek-fog-0", fog.circles[0].id)
        assertFalse(fog.circles[0].isHalo)
    }

    @Test
    fun `unrevealed future clearings render nothing`() {
        val fog = state(count = 3, activeIndex = 1)
        assertEquals(listOf("seek-fog-0", "seek-fog-1"), fog.circles.map { it.id })
    }

    // Active clearing fog

    @Test
    fun `active clearing bucket derived from distance`() {
        val expectations = listOf(
            2000.0 to 5, 1200.0 to 5, 900.0 to 4, 600.0 to 4,
            400.0 to 3, 200.0 to 2, 100.0 to 1, 0.0 to 1,
        )
        for ((distance, expected) in expectations) {
            val fog = state(distance = distance)
            assertEquals("distance $distance", expected, fog.circles[0].opacityBucket)
        }
    }

    @Test
    fun `active clearing with no fix yet renders thickest fog`() {
        assertEquals(SeekFogModel.farthestBucket, state(distance = null).circles[0].opacityBucket)
    }

    @Test
    fun `arrived phase dissolves active fog`() {
        val fog = state(activeIndex = 0, phase = SeekEnginePhase.ARRIVED, distance = 20.0)
        assertEquals(0, fog.circles[0].opacityBucket)
        assertFalse(fog.circles[0].isHalo)
        assertEquals(
            SeekFogModel.DISSOLVED_OPACITY,
            SeekFogModel.opacity(bucket = 0, isHalo = false),
            0.0,
        )
    }

    @Test
    fun `revealing phase dissolves active fog`() {
        val fog = state(phase = SeekEnginePhase.REVEALING, distance = 20.0)
        assertEquals(0, fog.circles[0].opacityBucket)
    }

    // Halos

    @Test
    fun `found clearings render as halos`() {
        val fog = state(count = 3, activeIndex = 2, distance = 500.0)
        assertEquals(
            listOf("seek-fog-0", "seek-fog-1"),
            fog.circles.filter { it.isHalo }.map { it.id },
        )
        assertFalse(fog.circles[2].isHalo)
    }

    @Test
    fun `complete phase halos only no fog`() {
        val fog = state(count = 3, activeIndex = 2, phase = SeekEnginePhase.COMPLETE)
        assertEquals(3, fog.circles.size)
        assertTrue(fog.circles.all { it.isHalo })
    }

    @Test
    fun `empty chain complete renders nothing`() {
        val fog = SeekFogModel.fogState(
            chain = SeekChain(clearings = emptyList(), budgetMeters = 0.0),
            activeIndex = 0,
            phase = SeekEnginePhase.COMPLETE,
            distanceToActiveMeters = null,
        )
        assertTrue(fog.circles.isEmpty())
    }

    // Buckets

    @Test
    fun `opacity bucket monotonic in distance`() {
        var previous = 0
        var distance = 0.0
        while (distance <= 2500.0) {
            val bucket = SeekFogModel.opacityBucket(distance)
            assertTrue("distance $distance", bucket >= previous)
            previous = bucket
            distance += 10.0
        }
    }

    @Test
    fun `opacity bucket null distance is farthest`() {
        assertEquals(SeekFogModel.farthestBucket, SeekFogModel.opacityBucket(null))
    }

    // Hysteresis

    @Test
    fun `oscillation across boundary does not flip bucket`() {
        var bucket = SeekFogModel.bucketApplyingHysteresis(distanceMeters = 130.0, currentBucket = 2)
        assertEquals("beyond the margin, change applies", 1, bucket)
        for (distance in listOf(145.0, 155.0, 148.0, 152.0, 160.0, 149.0)) {
            bucket = SeekFogModel.bucketApplyingHysteresis(distance, bucket)
            assertEquals("jitter at $distance m must not flip the bucket", 1, bucket)
        }
    }

    @Test
    fun `oscillation across the 300m boundary does not flip bucket`() {
        // Plan U6 test scenario: ±10% of 300 m (margin 30) must hold steady.
        var bucket = SeekFogModel.bucketApplyingHysteresis(distanceMeters = 250.0, currentBucket = 3)
        assertEquals(2, bucket)
        for (distance in listOf(290.0, 310.0, 329.0, 271.0, 300.0)) {
            bucket = SeekFogModel.bucketApplyingHysteresis(distance, bucket)
            assertEquals("jitter at $distance m must not flip the bucket", 2, bucket)
        }
        assertEquals(3, SeekFogModel.bucketApplyingHysteresis(330.0, bucket))
    }

    @Test
    fun `crossing beyond margin flips bucket`() {
        assertEquals(2, SeekFogModel.bucketApplyingHysteresis(distanceMeters = 165.0, currentBucket = 1))
        assertEquals(1, SeekFogModel.bucketApplyingHysteresis(distanceMeters = 135.0, currentBucket = 2))
    }

    @Test
    fun `within margin keeps current bucket`() {
        assertEquals(1, SeekFogModel.bucketApplyingHysteresis(distanceMeters = 164.0, currentBucket = 1))
        assertEquals(2, SeekFogModel.bucketApplyingHysteresis(distanceMeters = 136.0, currentBucket = 2))
    }

    @Test
    fun `multi bucket jump applies immediately`() {
        assertEquals(1, SeekFogModel.bucketApplyingHysteresis(distanceMeters = 100.0, currentBucket = 5))
        assertEquals(5, SeekFogModel.bucketApplyingHysteresis(distanceMeters = 2000.0, currentBucket = 1))
    }

    @Test
    fun `no current bucket uses raw bucket`() {
        assertEquals(2, SeekFogModel.bucketApplyingHysteresis(distanceMeters = 155.0, currentBucket = null))
    }

    @Test
    fun `invalid current bucket falls back to raw`() {
        assertEquals(2, SeekFogModel.bucketApplyingHysteresis(distanceMeters = 155.0, currentBucket = 0))
        assertEquals(2, SeekFogModel.bucketApplyingHysteresis(distanceMeters = 155.0, currentBucket = 99))
    }

    @Test
    fun `fog state respects previous active bucket`() {
        val held = state(distance = 155.0, previousBucket = 1)
        assertEquals(1, held.circles[0].opacityBucket)
        assertEquals(1, held.activeFogBucket)
        val flipped = state(distance = 165.0, previousBucket = 1)
        assertEquals(2, flipped.circles[0].opacityBucket)
    }

    // Opacity

    @Test
    fun `opacity monotonic across buckets`() {
        var previous = SeekFogModel.opacity(bucket = 0, isHalo = false)
        for (bucket in 1..SeekFogModel.farthestBucket) {
            val opacity = SeekFogModel.opacity(bucket, isHalo = false)
            assertTrue("bucket $bucket", opacity > previous)
            previous = opacity
        }
    }

    @Test
    fun `halo opacity below any active fog`() {
        val halo = SeekFogModel.opacity(bucket = 0, isHalo = true)
        assertEquals(SeekFogModel.HALO_OPACITY, halo, 0.0)
        for (bucket in 1..SeekFogModel.farthestBucket) {
            assertTrue(halo < SeekFogModel.opacity(bucket, isHalo = false))
        }
    }

    @Test
    fun `bucket zero is dissolved`() {
        assertEquals(0.0, SeekFogModel.opacity(bucket = 0, isHalo = false), 0.0)
    }

    // Equality (the render early-return key)

    @Test
    fun `identical states compare equal`() {
        val first = state(count = 3, activeIndex = 1, distance = 500.0, previousBucket = 3)
        val second = state(count = 3, activeIndex = 1, distance = 500.0, previousBucket = 3)
        assertEquals(first, second)
    }

    @Test
    fun `different buckets compare not equal`() {
        assertNotEquals(state(distance = 2000.0), state(distance = 100.0))
    }

    @Test
    fun `halo role change compares not equal`() {
        assertNotEquals(
            state(count = 2, activeIndex = 1, distance = 500.0),
            state(count = 2, activeIndex = 1, phase = SeekEnginePhase.COMPLETE),
        )
    }

    // Celestial tint

    @Test
    fun `fog state carries tint and equality honors it`() {
        val chain = SeekChain(
            clearings = listOf(
                SeekClearing(center = SeekPoint(latitude = 42.0, longitude = -8.0), radiusMeters = 50.0),
            ),
            budgetMeters = 3000.0,
        )
        val plain = SeekFogModel.fogState(
            chain = chain, activeIndex = 0, phase = SeekEnginePhase.GUIDING,
            distanceToActiveMeters = 500.0,
        )
        val tinted = SeekFogModel.fogState(
            chain = chain, activeIndex = 0, phase = SeekEnginePhase.GUIDING,
            distanceToActiveMeters = 500.0, tintHex = "#2377A4",
        )
        assertNull(plain.tintHex)
        assertEquals("#2377A4", tinted.tintHex)
        assertNotEquals(plain, tinted)
    }

    // Crescent (U7 — iOS SeekFogStateTests wisp section)

    private val crescentChain = SeekChain(
        clearings = listOf(
            SeekClearing(center = SeekPoint(latitude = 42.01, longitude = -8.0), radiusMeters = 50.0),
        ),
        budgetMeters = 3000.0,
    )

    private val walker = SeekPoint(latitude = 42.0, longitude = -8.0)

    private fun crescentState(
        distance: Double?,
        phase: SeekEnginePhase = SeekEnginePhase.GUIDING,
        walkerPosition: SeekPoint? = walker,
    ): SeekFogState = SeekFogModel.fogState(
        chain = crescentChain,
        activeIndex = 0,
        phase = phase,
        distanceToActiveMeters = distance,
        walkerPosition = walkerPosition,
    )

    @Test
    fun `crescent visible far away rides the walker and aims at the clearing`() {
        val crescent = crescentState(distance = 900.0).crescent
        assertNotNull("crescent should show beyond 150 m", crescent)
        assertEquals("the crescent rides the puck, never floats away", walker, crescent!!.position)
        assertEquals("clearing is due north; the crescent must aim north", 0.0, crescent.bearingDegrees, 1.0)
    }

    @Test
    fun `crescent persists close in - the viewport owns the handoff`() {
        assertNotNull(
            "distance never hides the crescent — the renderer releases it when the fog is actually visible",
            crescentState(distance = 120.0).crescent,
        )
    }

    @Test
    fun `crescent hides when arrived or revealing`() {
        assertNull(crescentState(distance = 900.0, phase = SeekEnginePhase.ARRIVED).crescent)
        assertNull(crescentState(distance = 900.0, phase = SeekEnginePhase.REVEALING).crescent)
    }

    @Test
    fun `crescent hides without a walker position`() {
        assertNull(crescentState(distance = 900.0, walkerPosition = null).crescent)
    }

    @Test
    fun `crescent hides on the complete phase`() {
        assertNull(crescentState(distance = 900.0, phase = SeekEnginePhase.COMPLETE).crescent)
    }

    @Test
    fun `crescent moves with the walker and equality notices it`() {
        val there = crescentState(
            distance = 900.0,
            walkerPosition = SeekPoint(latitude = 42.001, longitude = -8.0),
        )
        assertNotEquals(crescentState(distance = 900.0), there)
    }
}
