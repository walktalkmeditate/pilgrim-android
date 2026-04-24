// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiSealBitmapRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun seal(uuid: String = "seed-a", distanceMeters: Double = 1_234.0) = SealSpec(
        uuid = uuid,
        startMillis = 1_700_000_000_000L,
        distanceMeters = distanceMeters,
        durationSeconds = 3_600.0,
        displayDistance = "1.2",
        unitLabel = "km",
        ink = Color.Black,
    )

    @Test
    fun `renderToBitmap returns a bitmap of the requested size`() {
        val bitmap = EtegamiSealBitmapRenderer.renderToBitmap(
            spec = seal(),
            ink = Color(0xFF2C241E),
            sizePx = 256,
            context = context,
        )
        assertNotNull(bitmap)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }

    @Test
    fun `renderToBitmap at different sizes returns matching dimensions`() {
        listOf(64, 140, 200, 512).forEach { s ->
            val bitmap = EtegamiSealBitmapRenderer.renderToBitmap(
                spec = seal(),
                ink = Color(0xFF2C241E),
                sizePx = s,
                context = context,
            )
            assertEquals(s, bitmap.width)
            assertEquals(s, bitmap.height)
        }
    }

    @Test
    fun `renderToBitmap is stable across diverse specs (no throw)`() {
        val specs = listOf(
            seal(uuid = "short", distanceMeters = 50.0),
            seal(uuid = "medium", distanceMeters = 2_500.0),
            seal(uuid = "long", distanceMeters = 42_000.0),
            seal(uuid = "zero", distanceMeters = 0.01),  // >0 required by entity
        )
        specs.forEach { s ->
            val bitmap = EtegamiSealBitmapRenderer.renderToBitmap(
                spec = s,
                ink = Color(0xFF2C241E),
                sizePx = 140,
                context = context,
            )
            assertNotNull("render failed for uuid=${s.uuid}", bitmap)
        }
    }

    @Test
    fun `drawCentered places the seal at the given center without error`() {
        val canvas = android.graphics.Canvas(
            android.graphics.Bitmap.createBitmap(800, 800, android.graphics.Bitmap.Config.ARGB_8888),
        )
        EtegamiSealBitmapRenderer.drawCentered(
            canvas = canvas,
            spec = seal(),
            ink = Color(0xFF2C241E),
            cx = 400f,
            cy = 400f,
            sizePx = 140f,
            context = context,
        )
        // Assertion is implicit — drawCentered returning without
        // exception covers the integration.
    }

    @Test
    fun `sizePx must be positive`() {
        try {
            EtegamiSealBitmapRenderer.renderToBitmap(
                spec = seal(),
                ink = Color.Black,
                sizePx = 0,
                context = context,
            )
            org.junit.Assert.fail("expected IllegalArgumentException for sizePx = 0")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
