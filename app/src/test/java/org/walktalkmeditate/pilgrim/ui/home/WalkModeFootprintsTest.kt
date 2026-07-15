// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry pins for the seek trail (Stage 3-C rule: prove draw math
 * via pure functions — Robolectric's Canvas backend is a stub). The
 * table is a verbatim port of iOS `WalkModeFootprints.swift:35-42@c1745e8`.
 */
class WalkModeFootprintsTest {

    private val dots = walkModeTrailDots()

    @Test fun `trail has six dots`() {
        assertEquals(6, dots.size)
    }

    @Test fun `trail dissolves upward - y strictly decreasing`() {
        dots.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                "dot at y=${higher.y} must sit above y=${lower.y}",
                higher.y < lower.y,
            )
        }
    }

    @Test fun `trail shrinks - radii never grow and end smaller than they start`() {
        dots.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                "radius ${higher.radiusDp} must not exceed ${lower.radiusDp}",
                higher.radiusDp <= lower.radiusDp,
            )
        }
        assertTrue(dots.last().radiusDp < dots.first().radiusDp)
    }

    @Test fun `trail fades - alpha strictly decreasing`() {
        dots.zipWithNext().forEach { (lower, higher) ->
            assertTrue(
                "alpha ${higher.alpha} must fade below ${lower.alpha}",
                higher.alpha < lower.alpha,
            )
        }
    }

    @Test fun `dots stay inside the frame with positive size and visible alpha`() {
        dots.forEach { dot ->
            assertTrue(dot.x in 0f..1f)
            assertTrue(dot.y in 0f..1f)
            assertTrue(dot.radiusDp > 0f)
            assertTrue(dot.alpha > 0f && dot.alpha <= 1f)
        }
    }

    @Test fun `endpoints pin the iOS dot table`() {
        assertEquals(TrailDot(x = 0.5f, y = 0.85f, radiusDp = 1.6f, alpha = 1.0f), dots.first())
        assertEquals(TrailDot(x = 0.5f, y = 0.05f, radiusDp = 0.7f, alpha = 0.22f), dots.last())
    }

    @Test fun `draw-time radius scale pins the device-QA divergence`() {
        // Verbatim iOS radii read as discrete circles at the journal
        // size on Android (device QA 2026-07-15); the table stays
        // verbatim, the drawn radius shrinks. Scale must stay in (0, 1).
        assertEquals(0.6f, TRAIL_DOT_RADIUS_SCALE, 0f)
    }
}
