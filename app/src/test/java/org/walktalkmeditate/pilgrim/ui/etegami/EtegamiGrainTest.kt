// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtegamiGrainTest {

    @Test
    fun `count matches requested size`() {
        val dots = EtegamiGrain.dots(seed = 42L, count = 100, width = 1080, height = 1920)
        assertEquals(100, dots.size)
    }

    @Test
    fun `zero count produces empty list`() {
        val dots = EtegamiGrain.dots(seed = 0L, count = 0, width = 10, height = 10)
        assertTrue(dots.isEmpty())
    }

    @Test
    fun `all dots within canvas bounds`() {
        val width = 1080
        val height = 1920
        val dots = EtegamiGrain.dots(seed = 7L, count = 500, width = width, height = height)
        dots.forEach { d ->
            assertTrue("x=${d.x} out of bounds", d.x in 0f..width.toFloat())
            assertTrue("y=${d.y} out of bounds", d.y in 0f..height.toFloat())
        }
    }

    @Test
    fun `radius within documented range`() {
        val dots = EtegamiGrain.dots(seed = 3L, count = 500, width = 100, height = 100)
        dots.forEach { d ->
            assertTrue("radius=${d.radius} below min", d.radius >= 0.5f)
            assertTrue("radius=${d.radius} above max", d.radius <= 1.5f)
        }
    }

    @Test
    fun `same seed produces identical list`() {
        val a = EtegamiGrain.dots(seed = 99L, count = 200, width = 1080, height = 1920)
        val b = EtegamiGrain.dots(seed = 99L, count = 200, width = 1080, height = 1920)
        assertEquals(a, b)
    }

    @Test
    fun `different seeds produce different lists`() {
        val a = EtegamiGrain.dots(seed = 1L, count = 200, width = 1080, height = 1920)
        val b = EtegamiGrain.dots(seed = 2L, count = 200, width = 1080, height = 1920)
        assertNotEquals(a, b)
    }

    @Test
    fun `non-positive width rejected`() {
        try {
            EtegamiGrain.dots(seed = 0L, count = 10, width = 0, height = 100)
            org.junit.Assert.fail("expected IllegalArgumentException for width = 0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `non-positive height rejected`() {
        try {
            EtegamiGrain.dots(seed = 0L, count = 10, width = 100, height = -5)
            org.junit.Assert.fail("expected IllegalArgumentException for height < 0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `negative count rejected`() {
        try {
            EtegamiGrain.dots(seed = 0L, count = -1, width = 100, height = 100)
            org.junit.Assert.fail("expected IllegalArgumentException for count < 0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
