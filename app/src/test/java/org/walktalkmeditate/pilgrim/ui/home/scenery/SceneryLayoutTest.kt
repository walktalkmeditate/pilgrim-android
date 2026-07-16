// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneryLayoutTest {

    // ---- Parallax: (centerY − viewportCenter) / halfViewport × weight ----

    @Test
    fun `parallax is zero at the viewport center`() {
        val x = sceneryParallaxXPx(
            sceneryCenterYPx = 1000f,
            scrollOffsetPx = 500f,
            viewportHeightPx = 1000f,
            weightPx = 16f,
        )
        assertEquals(0f, x, 1e-4f)
    }

    @Test
    fun `parallax reaches plus weight at the bottom edge`() {
        // viewport center = 0 + 500; bottom edge = 1000 → dist/half = +1.
        val x = sceneryParallaxXPx(
            sceneryCenterYPx = 1000f,
            scrollOffsetPx = 0f,
            viewportHeightPx = 1000f,
            weightPx = 12f,
        )
        assertEquals(12f, x, 1e-4f)
    }

    @Test
    fun `parallax reaches minus weight at the top edge`() {
        // viewport center = 2000 + 500; top edge = 2000 → dist/half = −1.
        val x = sceneryParallaxXPx(
            sceneryCenterYPx = 2000f,
            scrollOffsetPx = 2000f,
            viewportHeightPx = 1000f,
            weightPx = 9f,
        )
        assertEquals(-9f, x, 1e-4f)
    }

    @Test
    fun `parallax scales linearly past the edges without clamping`() {
        // iOS-verbatim: no clamp — an item one full viewport below the
        // center drifts 2× the weight (it is offscreen anyway).
        val x = sceneryParallaxXPx(
            sceneryCenterYPx = 1500f,
            scrollOffsetPx = 0f,
            viewportHeightPx = 1000f,
            weightPx = 8f,
        )
        assertEquals(16f, x, 1e-4f)
    }

    @Test
    fun `parallax is safe before the viewport is measured`() {
        val x = sceneryParallaxXPx(
            sceneryCenterYPx = 500f,
            scrollOffsetPx = 0f,
            viewportHeightPx = 0f,
            weightPx = 16f,
        )
        assertEquals(0f, x, 1e-4f)
    }

    // ---- Age fade: scenery multiplies its dot's fade; seeking exempt ----

    @Test
    fun `scenery age alpha matches the dot fade for non-gates`() {
        assertEquals(0.5f, sceneryAgeAlpha(gateKind = null, dotFade = 0.5f), 1e-4f)
        assertEquals(1f, sceneryAgeAlpha(gateKind = null, dotFade = 1f), 1e-4f)
    }

    @Test
    fun `practice gates dim with their walk`() {
        assertEquals(
            0.62f,
            sceneryAgeAlpha(gateKind = WalkThreshold.Practice, dotFade = 0.62f),
            1e-4f,
        )
    }

    @Test
    fun `seeking gates refuse the age fade`() {
        // Old stone grows older, not fainter (6e80a91).
        assertEquals(
            1f,
            sceneryAgeAlpha(gateKind = WalkThreshold.Seeking, dotFade = 0.5f),
            1e-4f,
        )
    }

    // ---- Placement: dot-relative at every depth (3f9d3db bug class) ----

    private fun center(dotXPx: Float, dotYPx: Float, side: ScenerySide = ScenerySide.Right) =
        sceneryCenterPx(
            dotXPx = dotXPx,
            dotYPx = dotYPx,
            scenerySizePx = 30f,
            side = side,
            jitterPx = 5f,
            clearancePx = 40f,
            liftPx = 4f,
        )

    @Test
    fun `scenery displacement from its dot is constant across scroll depths`() {
        // The 3f9d3db invariant: displacement is a pure offset from the
        // dot's center — never proportional to the dot's y. A shallow dot
        // (row 0) and a deep dot (row 89 ≈ 24k px) displace identically.
        val shallow = center(dotXPx = 200f, dotYPx = 85f)
        val deep = center(dotXPx = 200f, dotYPx = 24_130f)
        val shallowDelta = Offset(shallow.x - 200f, shallow.y - 85f)
        val deepDelta = Offset(deep.x - 200f, deep.y - 24_130f)
        assertEquals(shallowDelta.x, deepDelta.x, 1e-4f)
        assertEquals(shallowDelta.y, deepDelta.y, 1e-4f)
    }

    @Test
    fun `right side scenery sits clearance plus half size plus jitter from the dot`() {
        // 200 + (40 + 30/2) + 5 = 260; y = dotY − 4.
        val c = center(dotXPx = 200f, dotYPx = 500f)
        assertEquals(260f, c.x, 1e-4f)
        assertEquals(496f, c.y, 1e-4f)
    }

    @Test
    fun `left side scenery mirrors the clearance and keeps the jitter additive`() {
        // 200 − (40 + 15) + 5 = 150 — jitter shifts, never mirrors
        // (iOS: xOffset + placement.offset).
        val c = center(dotXPx = 200f, dotYPx = 500f, side = ScenerySide.Left)
        assertEquals(150f, c.x, 1e-4f)
        assertEquals(496f, c.y, 1e-4f)
    }
}
