// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.scenery

import android.app.Application
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShapePathTest {

    @Test
    fun footprint_path_bounds_within_size() {
        val size = Size(12f, 18f)
        val p = footprintPath(size)
        val b = p.getBounds()
        assertTrue("non-empty path", b.width > 0f && b.height > 0f)
        assertTrue("left >= 0", b.left >= 0f)
        assertTrue("top >= 0", b.top >= 0f)
        assertTrue("right <= width", b.right <= size.width + 0.01f)
        assertTrue("bottom <= height", b.bottom <= size.height + 0.01f)
    }

    @Test
    fun torii_path_bounds_within_size() {
        val size = Size(16f, 14f)
        val p = toriiGatePath(size)
        val b = p.getBounds()
        assertTrue("non-empty path", b.width > 0f && b.height > 0f)
        assertTrue(b.left >= 0f)
        assertTrue(b.top >= 0f)
        assertTrue(b.right <= size.width + 0.01f)
        assertTrue(b.bottom <= size.height + 0.01f)
    }

    @Test
    fun footprint_scales_with_size() {
        val small = footprintPath(Size(12f, 18f)).getBounds()
        val large = footprintPath(Size(60f, 90f)).getBounds()
        assertTrue("large path is wider than small", large.width > small.width)
        assertTrue("large path is taller than small", large.height > small.height)
    }
}
