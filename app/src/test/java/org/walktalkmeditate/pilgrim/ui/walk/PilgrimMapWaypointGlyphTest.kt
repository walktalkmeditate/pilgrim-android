// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence

/**
 * The honor arrival's signpost shows at iOS's 18 pt on every screen
 * (parity spec correction 23): Mapbox draws a bitmap at its pixel size
 * over the screen density, so the signpost is rasterized at 18 dp ×
 * density, while the other waypoint glyphs keep their fixed raster.
 *
 * [GraphicsMode.Mode.NATIVE]: the LEGACY shadows can't create the bitmaps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PilgrimMapWaypointGlyphTest {

    @get:Rule val composeRule = createComposeRule()

    private fun bitmapsAt(density: Float, honorArrival: Boolean): Map<String, Bitmap> {
        var bitmaps: Map<String, Bitmap> = emptyMap()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                bitmaps = rememberWaypointBitmaps(Color.Gray, honorArrival = honorArrival)
            }
        }
        composeRule.waitForIdle()
        return bitmaps
    }

    @Test
    fun `the signpost is 18 dp at the screen's density, and the other glyphs keep their raster`() {
        val bitmaps = bitmapsAt(density = 2.625f, honorArrival = true)

        val signpost = bitmaps.getValue(HonorPersistence.ARRIVAL_WAYPOINT_ICON)
        assertEquals(47, signpost.width)
        assertEquals(47, signpost.height)
        assertEquals(72, bitmaps.getValue("leaf").width)
        assertEquals(72, bitmaps.getValue("mappin").width)
    }

    @Test
    fun `with the flag off there is no signpost`() {
        assertFalse(HonorPersistence.ARRIVAL_WAYPOINT_ICON in bitmapsAt(density = 3f, honorArrival = false))
    }

    @Test
    fun `the signpost's edge rounds 18 dp to whole pixels`() {
        assertEquals(18, honorArrivalGlyphSizePx(1f))
        assertEquals(54, honorArrivalGlyphSizePx(3f))
        assertEquals(72, honorArrivalGlyphSizePx(4f))
    }
}
