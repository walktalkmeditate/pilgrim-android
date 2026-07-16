// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MoonSceneryTest {

    @Test
    fun `waxing carve slides the shadow left so the lit limb is right`() {
        assertEquals(-0.6f, moonCarveOffsetFraction(illumination = 0.6f, waxing = true), 1e-6f)
    }

    @Test
    fun `waning carve slides the shadow right so the lit limb is left`() {
        assertEquals(0.6f, moonCarveOffsetFraction(illumination = 0.6f, waxing = false), 1e-6f)
    }

    @Test
    fun `new moon keeps a hairline crescent via the illumination floor`() {
        assertEquals(-0.08f, moonCarveOffsetFraction(illumination = 0f, waxing = true), 1e-6f)
        assertEquals(0.08f, moonCarveOffsetFraction(illumination = 0.03f, waxing = false), 1e-6f)
    }

    @Test
    fun `full moon pushes the shadow disc fully off`() {
        assertEquals(-1f, moonCarveOffsetFraction(illumination = 1f, waxing = true), 1e-6f)
        assertEquals(1f, moonCarveOffsetFraction(illumination = 1f, waxing = false), 1e-6f)
    }

    @Test
    fun `carve path at full moon keeps the whole disc`() {
        val path = moonPhaseCarvePath(
            center = Offset(50f, 50f),
            diameter = 40f,
            illumination = 1f,
            waxing = true,
        )
        val bounds = path.getBounds()
        assertEquals(40f, bounds.width, 0.5f)
        assertEquals(40f, bounds.height, 0.5f)
    }

    @Test
    fun `carve path at new moon leaves a sliver on the lit limb`() {
        val waxing = moonPhaseCarvePath(
            center = Offset(50f, 50f),
            diameter = 40f,
            illumination = 0f,
            waxing = true,
        )
        assertFalse(waxing.isEmpty)
        // Waxing lights the right limb — the sliver's area hugs the
        // right edge of the disc, past its vertical midline.
        val bounds = waxing.getBounds()
        assertEquals(70f, bounds.right, 0.5f)
        assertTrue("sliver keeps the right edge", bounds.right > 50f)
    }

    @Test
    fun `waning carve path leaves the sliver on the left limb`() {
        val waning = moonPhaseCarvePath(
            center = Offset(50f, 50f),
            diameter = 40f,
            illumination = 0f,
            waxing = false,
        )
        assertFalse(waning.isEmpty)
        val bounds = waning.getBounds()
        assertEquals(30f, bounds.left, 0.5f)
        assertTrue("sliver stays off the right edge", bounds.right < 70f)
    }

    @Test
    fun `carve cache reuses the path until the draw geometry changes`() {
        val cache = MoonCarveCache(illumination = 0.6f, waxing = true)
        val center = Offset(50f, 50f)
        val first = cache.pathFor(center = center, diameter = 40f)
        assertSame(first, cache.pathFor(center = center, diameter = 40f))

        val resized = cache.pathFor(center = center, diameter = 48f)
        assertNotSame(first, resized)
        assertSame(resized, cache.pathFor(center = center, diameter = 48f))
        assertNotSame(resized, cache.pathFor(center = Offset(60f, 60f), diameter = 48f))
    }

    @Test
    fun `six moonlight rays fan sixty degrees apart`() {
        assertEquals(6, MOON_RAY_COUNT)
        val t = 4.2f
        val base = moonRayAngleDegrees(0, t)
        for (i in 1 until MOON_RAY_COUNT) {
            assertEquals(base + i * 60f, moonRayAngleDegrees(i, t), 1e-4f)
        }
    }

    @Test
    fun `ray fan wobbles within plus-minus five degrees`() {
        val samples = (0..600).map { it / 10f }
        for (t in samples) {
            val wobble = moonRayAngleDegrees(0, t)
            assertTrue("wobble $wobble at t=$t inside ±5°", wobble in -5f..5f)
        }
        // sin(0.2·t) peaks at t = π/2 ÷ 0.2 — the fan actually reaches +5°.
        val peakT = (Math.PI / 2.0 / 0.2).toFloat()
        assertEquals(5f, moonRayAngleDegrees(0, peakT), 1e-3f)
    }

    @Test
    fun `ray pulse breathes between 0_02 and 0_06`() {
        assertEquals(0.06f, moonRayPulseAlpha((Math.PI / 2.0 / 0.3).toFloat()), 1e-4f)
        assertEquals(0.02f, moonRayPulseAlpha((3.0 * Math.PI / 2.0 / 0.3).toFloat()), 1e-4f)
        for (t in 0..600) {
            val alpha = moonRayPulseAlpha(t / 10f)
            assertTrue("pulse $alpha inside [0.02, 0.06]", alpha in 0.02f..0.06f)
        }
    }

    @Test
    fun `halo breathes 0_02 to 0_04 on a six second cycle`() {
        assertEquals(0.02f, moonHaloAlpha(0f), 1e-4f)
        assertEquals(0.04f, moonHaloAlpha(3f), 1e-4f)
        assertEquals(0.02f, moonHaloAlpha(6f), 1e-4f)
        for (t in 0..600) {
            val alpha = moonHaloAlpha(t / 10f)
            assertTrue("halo $alpha inside [0.02, 0.04]", alpha in 0.02f..0.04f)
        }
    }

    @Test
    fun `frozen clock lands on the iOS reduce-motion frame`() {
        // iOS collapses the halo phaseAnimator to [false] → opacity 0.4 of
        // the 0.05 fill; sceneryTimeSeconds freezes at t = 0 on Android.
        assertEquals(0.05f * 0.4f, moonHaloAlpha(0f), 1e-5f)
        assertEquals(0.04f, moonRayPulseAlpha(0f), 1e-5f)
    }

    @Test
    fun `glow geometry pins the iOS fractions`() {
        assertEquals(0.9f, MOON_HALO_RADIUS_FRACTION, 0f) // iOS size × 1.8 disc
        assertEquals(0.6f, MOON_RAY_LENGTH_FRACTION, 0f)
        assertEquals(0.15f, MOON_RAY_BASE_DROP_FRACTION, 0f)
    }

    @Test
    fun `crescent path keeps the left limb and bites the right`() {
        val crescent = moonCrescentPath(Size(100f, 100f))
        assertFalse(crescent.isEmpty)
        val bounds = crescent.getBounds()
        // Outer disc: center (45, 50), radius 45 — left/top/bottom edges
        // survive the inner bite, the right edge is eaten back to the
        // cusps at x ≈ 78.9.
        assertEquals(0f, bounds.left, 0.5f)
        assertEquals(5f, bounds.top, 0.5f)
        assertEquals(95f, bounds.bottom, 0.5f)
        assertTrue("right edge bitten (was ${bounds.right})", bounds.right in 70f..85f)
    }

    @Test
    fun `crescent cache reuses each scale until the base diameter changes`() {
        val cache = MoonCrescentCache()
        val ghost = cache.pathFor(baseDiameter = 40f, scale = 1.06f)
        val main = cache.pathFor(baseDiameter = 40f, scale = 1f)
        assertNotSame(ghost, main)
        assertSame(ghost, cache.pathFor(baseDiameter = 40f, scale = 1.06f))
        assertSame(main, cache.pathFor(baseDiameter = 40f, scale = 1f))

        val resized = cache.pathFor(baseDiameter = 48f, scale = 1f)
        assertNotSame(main, resized)
        assertSame(resized, cache.pathFor(baseDiameter = 48f, scale = 1f))
    }
}
