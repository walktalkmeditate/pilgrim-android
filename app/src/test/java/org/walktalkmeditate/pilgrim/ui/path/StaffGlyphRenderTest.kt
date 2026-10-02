// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import android.app.Application
import android.graphics.Color as AndroidColor
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The staff is one shape stroked once (iOS `StaffGlyph().stroke(…)`,
 * parity spec G §4): at the journal's 0.3 alpha the pixel where the shaft
 * crosses the crossbar is no darker than the shaft elsewhere.
 *
 * [GraphicsMode.Mode.NATIVE]: the project's LEGACY Canvas draws nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StaffGlyphRenderTest {

    @Test
    fun `where the strokes cross the alpha does not double`() {
        val width = 80
        val height = 140
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
            drawStaffGlyph(Color.Black.copy(alpha = 0.3f), strokeWidthPx = 10f, cap = StrokeCap.Butt, tiltPx = 0f)
        }
        val bitmap = image.asAndroidBitmap()

        // The crossbar runs along y = 0.18h; the shaft leans from (0.65w, 0) to (0.35w, h).
        val crossY = (height * 0.18f).toInt()
        val crossX = (width * 0.65f - width * 0.30f * 0.18f).toInt()
        val shaftY = 100
        val shaftX = (width * 0.65f - width * 0.30f * shaftY / height).toInt()
        val crossing = AndroidColor.alpha(bitmap.getPixel(crossX, crossY))
        val shaft = AndroidColor.alpha(bitmap.getPixel(shaftX, shaftY))

        assertEquals("the shaft alone is at 0.3", 77f, shaft.toFloat(), 2f)
        assertEquals("the crossing is no darker", shaft.toFloat(), crossing.toFloat(), 2f)
    }
}
