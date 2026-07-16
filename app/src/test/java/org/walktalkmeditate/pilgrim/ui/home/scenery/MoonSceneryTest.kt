// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import android.app.Application
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
