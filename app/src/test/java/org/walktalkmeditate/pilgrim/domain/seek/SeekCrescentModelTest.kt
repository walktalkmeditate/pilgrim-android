// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the crescent's pure math against the port spec
 * (`docs/parity/2026-07-14-port-seek-crescent-u7.md`; iOS
 * `SeekFogModel.swift` wisp parts + `SeekWispVisibilityTests.swift`
 * @c1745e8).
 */
class SeekCrescentModelTest {

    // Span ladder

    @Test
    fun `span opens strictly as the fog nears`() {
        val spans = (1..SeekFogModel.farthestBucket).map { SeekCrescentModel.spanDegrees(it) }
        for ((nearer, farther) in spans.zip(spans.drop(1))) {
            assertTrue("the crescent must open monotonically on approach", nearer > farther)
        }
    }

    @Test
    fun `span clamps to the extremes`() {
        val sliver = SeekCrescentModel.spanDegrees(SeekFogModel.farthestBucket)
        val open = SeekCrescentModel.spanDegrees(1)
        assertEquals("no fix yet = farthest sliver", sliver, SeekCrescentModel.spanDegrees(null), 0.0)
        assertEquals(sliver, SeekCrescentModel.spanDegrees(99), 0.0)
        assertEquals("dissolved-adjacent stays fully open", open, SeekCrescentModel.spanDegrees(0), 0.0)
        assertEquals(96.0, open, 0.0)
        assertEquals(48.0, sliver, 0.0)
    }

    @Test
    fun `span ladder inherits the fog bucket hysteresis`() {
        // Jitter across the 150 m boundary: the fog bucket holds (U6
        // hysteresis), so the span keyed on activeFogBucket holds too.
        val chain = SeekChain(
            clearings = listOf(
                SeekClearing(center = SeekPoint(latitude = 42.01, longitude = -8.0), radiusMeters = 50.0),
            ),
            budgetMeters = 3000.0,
        )
        var bucket: Int? = SeekFogModel.fogState(
            chain = chain, activeIndex = 0, phase = SeekEnginePhase.GUIDING,
            distanceToActiveMeters = 130.0,
        ).activeFogBucket
        val heldSpan = SeekCrescentModel.spanDegrees(bucket)
        for (distance in listOf(145.0, 155.0, 148.0, 152.0, 160.0, 149.0)) {
            bucket = SeekFogModel.fogState(
                chain = chain, activeIndex = 0, phase = SeekEnginePhase.GUIDING,
                distanceToActiveMeters = distance, previousActiveBucket = bucket,
            ).activeFogBucket
            assertEquals(
                "jitter at $distance m must not move the span",
                heldSpan,
                SeekCrescentModel.spanDegrees(bucket),
                0.0,
            )
        }
    }

    // Crescent point

    private val walker = SeekPoint(latitude = 42.0, longitude = -8.0)
    private val northClearing = SeekPoint(latitude = 42.01, longitude = -8.0)

    @Test
    fun `guiding with a fix rides the walker and aims at the clearing`() {
        val crescent = SeekCrescentModel.crescentPoint(walker, northClearing, SeekEnginePhase.GUIDING)
        assertNotNull(crescent)
        assertEquals("the crescent rides the puck, never floats away", walker, crescent!!.position)
        assertEquals("clearing due north aims north", 0.0, crescent.bearingDegrees, 1.0)
    }

    @Test
    fun `bearing normalizes to positive degrees`() {
        val westClearing = SeekPoint(latitude = 42.0, longitude = -8.01)
        val crescent = SeekCrescentModel.crescentPoint(walker, westClearing, SeekEnginePhase.GUIDING)
        assertEquals("due west normalizes to 270, not -90", 270.0, crescent!!.bearingDegrees, 1.0)
    }

    @Test
    fun `hides when arrived or revealing`() {
        assertNull(SeekCrescentModel.crescentPoint(walker, northClearing, SeekEnginePhase.ARRIVED))
        assertNull(SeekCrescentModel.crescentPoint(walker, northClearing, SeekEnginePhase.REVEALING))
    }

    @Test
    fun `hides without a walker fix`() {
        assertNull(SeekCrescentModel.crescentPoint(null, northClearing, SeekEnginePhase.GUIDING))
    }

    // Flare + rest opacity

    @Test
    fun `flare peak grows with closeness and clamps`() {
        assertEquals(0.75, SeekCrescentModel.flarePeak(aligned = false, closeness = 0.0), 1e-9)
        assertEquals(0.825, SeekCrescentModel.flarePeak(aligned = false, closeness = 0.5), 1e-9)
        assertEquals(0.9, SeekCrescentModel.flarePeak(aligned = false, closeness = 1.0), 1e-9)
        assertEquals("negative closeness clamps", 0.75, SeekCrescentModel.flarePeak(false, -3.0), 1e-9)
        assertEquals("overshoot clamps", 0.9, SeekCrescentModel.flarePeak(false, 7.0), 1e-9)
    }

    @Test
    fun `aligned pulse outshines any closeness`() {
        assertEquals(1.0, SeekCrescentModel.flarePeak(aligned = true, closeness = 0.0), 0.0)
        assertEquals(1.0, SeekCrescentModel.flarePeak(aligned = true, closeness = 1.0), 0.0)
    }

    @Test
    fun `resting opacity holds steady under reduce motion`() {
        assertEquals(0.55, SeekCrescentModel.restingOpacity(reduceMotion = false), 0.0)
        assertEquals(0.8, SeekCrescentModel.restingOpacity(reduceMotion = true), 0.0)
    }

    // Viewport release (iOS SeekWispVisibilityTests parity)

    private fun release(
        wasReleased: Boolean = false,
        centerX: Double?,
        centerY: Double?,
        radius: Double = 50.0,
        width: Double = 400.0,
        height: Double = 800.0,
    ): Boolean = SeekCrescentVisibilityModel.shouldRelease(
        wasReleased = wasReleased,
        fogCenterX = centerX,
        fogCenterY = centerY,
        fogRadiusPx = radius,
        viewWidthPx = width,
        viewHeightPx = height,
    )

    @Test
    fun `fog centered on screen releases`() {
        assertTrue(release(centerX = 200.0, centerY = 400.0))
    }

    @Test
    fun `fog far off screen stays shown`() {
        assertFalse(release(centerX = 3000.0, centerY = 400.0))
    }

    @Test
    fun `unprojectable fog never releases`() {
        // Mapbox's pixelForCoordinate collapses every off-view coordinate
        // to (-1, -1); the renderer maps that to null. iOS field
        // regression 174e9e0: the sentinel used to read as a circle
        // grazing the top-left corner, releasing the crescent on the
        // first camera event and pinning it released forever.
        assertFalse("off-screen fog must not release", release(wasReleased = false, centerX = null, centerY = null))
    }

    @Test
    fun `released fog that clamps off view holds released - no edge strobe`() {
        // The clamp fires the instant the center crosses the raw edge,
        // losing HOW far past it the fog lies. Finite → null → finite
        // while released must hold — flipping back on null bypasses the
        // ±24 px hysteresis band and replays the handoff exhale on every
        // edge crossing during a pan.
        assertTrue(release(wasReleased = true, centerX = 399.0, centerY = 400.0, radius = 100.0))
        assertTrue(
            "clamped-off-view center must hold the released state",
            release(wasReleased = true, centerX = null, centerY = null, radius = 100.0),
        )
        assertTrue(release(wasReleased = true, centerX = 399.0, centerY = 400.0, radius = 100.0))
    }

    @Test
    fun `fog edge overlap counts even with the center off screen`() {
        // Center 40 px past the right edge with a 100 px radius: the rim
        // reaches well inside the release inset.
        assertTrue(release(centerX = 440.0, centerY = 400.0, radius = 100.0))
    }

    @Test
    fun `fog must reach past the inset to release`() {
        // Rim touches the raw edge but not the inset rect — not yet "seen".
        assertFalse(release(centerX = 445.0, centerY = 400.0, radius = 50.0))
    }

    @Test
    fun `released fog just outside the edge stays released`() {
        assertTrue(release(wasReleased = true, centerX = 460.0, centerY = 400.0))
    }

    @Test
    fun `released fog beyond the outset returns`() {
        assertFalse(release(wasReleased = true, centerX = 600.0, centerY = 400.0))
    }

    @Test
    fun `dead zone position keeps whichever state it had`() {
        // Between the release inset and the return outset, both states hold.
        assertFalse(release(wasReleased = false, centerX = 430.0, centerY = 400.0))
        assertTrue(release(wasReleased = true, centerX = 430.0, centerY = 400.0))
    }

    @Test
    fun `zero view size keeps the current state`() {
        for (wasReleased in listOf(true, false)) {
            assertEquals(
                wasReleased,
                release(wasReleased = wasReleased, centerX = 10.0, centerY = 10.0, width = 0.0, height = 0.0),
            )
        }
    }

    @Test
    fun `non-finite center keeps the current state`() {
        for (wasReleased in listOf(true, false)) {
            assertEquals(
                wasReleased,
                release(wasReleased = wasReleased, centerX = Double.NaN, centerY = 400.0),
            )
        }
    }

    @Test
    fun `tiny view smaller than the inset never releases`() {
        assertFalse(release(centerX = 10.0, centerY = 10.0, width = 30.0, height = 30.0))
    }

    @Test
    fun `circle intersection measures corner distance diagonally`() {
        // 30√2 ≈ 42.4 from the corner: outside a 40 radius, inside a 45.
        assertFalse(
            SeekCrescentVisibilityModel.circleIntersects(
                centerX = 130.0, centerY = 130.0, radius = 40.0,
                left = 0.0, top = 0.0, right = 100.0, bottom = 100.0,
            ),
        )
        assertTrue(
            SeekCrescentVisibilityModel.circleIntersects(
                centerX = 130.0, centerY = 130.0, radius = 45.0,
                left = 0.0, top = 0.0, right = 100.0, bottom = 100.0,
            ),
        )
    }

    @Test
    fun `degenerate rect never intersects`() {
        assertFalse(
            SeekCrescentVisibilityModel.circleIntersects(
                centerX = 0.0, centerY = 0.0, radius = 1000.0,
                left = 50.0, top = 50.0, right = 10.0, bottom = 10.0,
            ),
        )
    }
}
