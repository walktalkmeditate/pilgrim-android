// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import android.app.Application
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed so `android.graphics.Path` + `Matrix` work
 * (plain-JVM JUnit would hit native-null calls).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiMoonGlyphTest {

    private val cx = 100f
    private val cy = 100f
    private val r = 20f

    private fun bounds(ill: Double, waxing: Boolean): RectF {
        val path = EtegamiMoonGlyph.terminatorPath(ill, waxing, cx, cy, r)
        val rect = RectF()
        path.computeBounds(rect, true)
        return rect
    }

    @Test
    fun `full moon bounds match the full disk`() {
        val rect = bounds(ill = 1.0, waxing = true)
        // Full moon: lit edge + terminator ellipse coinciding => full disk.
        assertEquals(cx - r, rect.left, 0.5f)
        assertEquals(cx + r, rect.right, 0.5f)
        assertEquals(cy - r, rect.top, 0.5f)
        assertEquals(cy + r, rect.bottom, 0.5f)
    }

    @Test
    fun `new moon collapses to zero area (right-edge only)`() {
        // illumination = 0 → terminator ellipse overlays the lit edge;
        // the "disc" is a degenerate right semicircle. Left bound sits
        // at cx (the disc's y-axis); right bound at cx + r.
        val rect = bounds(ill = 0.0, waxing = true)
        assertEquals(cx, rect.left, 0.5f)
        assertEquals(cx + r, rect.right, 0.5f)
    }

    @Test
    fun `waxing half-moon keeps lit edge on the right`() {
        val rect = bounds(ill = 0.5, waxing = true)
        // Right edge reaches cx + r (lit semicircle arc is unchanged);
        // left edge is at cx (terminator sits on the y-axis).
        assertEquals(cx + r, rect.right, 0.5f)
        assertEquals(cx, rect.left, 0.5f)
    }

    @Test
    fun `waning half-moon mirrors — lit edge on the left`() {
        val rect = bounds(ill = 0.5, waxing = false)
        // After mirror: right is cx, left is cx - r.
        assertEquals(cx, rect.right, 0.5f)
        assertEquals(cx - r, rect.left, 0.5f)
    }

    @Test
    fun `vertical extent independent of illumination`() {
        // The y coordinates come from sin(t) * r regardless of k.
        for (ill in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val rect = bounds(ill, waxing = true)
            assertEquals("ill=$ill top", cy - r, rect.top, 0.5f)
            assertEquals("ill=$ill bottom", cy + r, rect.bottom, 0.5f)
        }
    }

    @Test
    fun `illumination clamped to 0 1 range`() {
        // Out-of-range values must not produce absurdly large bounds.
        val rect = bounds(ill = 2.5, waxing = true)
        assertTrue(rect.width() <= r * 2f + 1f)
    }
}
