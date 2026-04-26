// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design

import android.app.Application
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Geometry-only tests for [moonPhasePath]. We don't exercise the
 * Composable's draw path because Robolectric's Canvas backend is a
 * stub (per the Stage 3-C lesson). The path's bounds + emptiness
 * cover the algorithm's branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoonPhaseGlyphTest {

    @Test
    fun `path is empty when illumination near zero (new moon)`() {
        val path = moonPhasePath(
            illumination = 0.02,
            isWaxing = true,
            center = Offset(100f, 100f),
            radius = 50f,
        )
        assertTrue("expected empty path for near-zero illumination", path.getBounds().isEmpty)
    }

    @Test
    fun `path is full circle when illumination near full`() {
        val path = moonPhasePath(
            illumination = 0.98,
            isWaxing = true,
            center = Offset(100f, 100f),
            radius = 50f,
        )
        val bounds = path.getBounds()
        assertEquals("left", 50f, bounds.left, 0.5f)
        assertEquals("top", 50f, bounds.top, 0.5f)
        assertEquals("right", 150f, bounds.right, 0.5f)
        assertEquals("bottom", 150f, bounds.bottom, 0.5f)
    }

    @Test
    fun `waxing first quarter renders right-half disc`() {
        // illumination = 0.5, waxing = true → lit half is the right
        // semicircle (sun-side from observer's POV). Path should
        // occupy x ∈ [center, center + radius], full y range. ALL FOUR
        // bounds asserted so a future refactor that mirrors the arc
        // (waxing → arc on left) can't pass by accident.
        val path = moonPhasePath(
            illumination = 0.5,
            isWaxing = true,
            center = Offset(100f, 100f),
            radius = 50f,
        )
        val bounds = path.getBounds()
        assertFalse("expected non-empty bounds for half illumination", bounds.isEmpty)
        assertEquals("left", 100f, bounds.left, 0.5f)
        assertEquals("right", 150f, bounds.right, 0.5f)
        assertEquals("top", 50f, bounds.top, 0.5f)
        assertEquals("bottom", 150f, bounds.bottom, 0.5f)
    }

    @Test
    fun `waning last quarter renders left-half disc`() {
        // illumination = 0.5, waxing = false → lit half is the left
        // semicircle (mirror of waxing first quarter).
        val path = moonPhasePath(
            illumination = 0.5,
            isWaxing = false,
            center = Offset(100f, 100f),
            radius = 50f,
        )
        val bounds = path.getBounds()
        assertFalse("expected non-empty bounds for half illumination", bounds.isEmpty)
        assertEquals("left", 50f, bounds.left, 0.5f)
        assertEquals("top", 50f, bounds.top, 0.5f)
        assertEquals("bottom", 150f, bounds.bottom, 0.5f)
    }

    @Test
    fun `mid-cycle illuminations produce non-empty paths`() {
        // Smoke test: every illumination in the in-range bucket
        // (0.05..0.95) must produce a non-empty path. Catches a future
        // refactor that breaks the cubic-bezier branch entirely.
        listOf(0.1, 0.25, 0.5, 0.75, 0.9).forEach { illum ->
            val path = moonPhasePath(
                illumination = illum,
                isWaxing = true,
                center = Offset(100f, 100f),
                radius = 50f,
            )
            assertFalse(
                "expected non-empty path at illumination $illum",
                path.getBounds().isEmpty,
            )
        }
    }
}
