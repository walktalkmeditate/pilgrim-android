// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BUG 6: the location puck used the stock Mapbox blue
 * `createDefault2DPuck()` instead of the iOS-parity stone-tinted disc
 * (`PilgrimMapView.swift:231-251@v1.6.0`).
 *
 * CLAUDE.md platform-object-builder rule: [buildStonePuck] constructs
 * a real `LocationPuck2D` via `ImageHolder.from(bitmap)`. That builder
 * path is exercised here against the production functions so a runtime
 * rejection surfaces in CI, not only on-device.
 *
 * Pixel colors are NOT asserted — Robolectric's Canvas draw backend is
 * a stub (Stage 3-C lesson: `canvas.drawCircle` produces no real
 * pixels under Robolectric); the fill colors are verified by on-device
 * QA. The bitmap *shape* (square, ARGB, sized to the puck constant)
 * and the Mapbox builder wiring are what this test locks down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimMapPuckTest {

    @Test fun `createPuckBitmap produces a square ARGB bitmap`() {
        val bmp = createPuckBitmap(0xFF8B7355.toInt())

        assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
        assertEquals("puck bitmap must be square", bmp.width, bmp.height)
        // 22dp * fixed 4x density factor (same as createProximityPinBitmap).
        assertEquals(22 * 4, bmp.width)
    }

    @Test fun `createPuckBitmap returns a fresh mutable bitmap each call`() {
        val a = createPuckBitmap(0xFF8B7355.toInt())
        val b = createPuckBitmap(0xFFB8976E.toInt())
        assertTrue("each call must allocate its own bitmap", a !== b)
        assertTrue("puck bitmap must be mutable for Canvas drawing", a.isMutable)
    }

    @Test fun `buildStonePuck constructs a real LocationPuck2D via ImageHolder`() {
        // Exercises the actual Mapbox builder path (CLAUDE.md rule):
        // ImageHolder.from(Bitmap) + LocationPuck2D(topImage = ...).
        val bmp = createPuckBitmap(0xFF8B7355.toInt())
        val puck = buildStonePuck(bmp)

        assertNotNull(puck.topImage)
        assertSame(
            "the puck must carry the stone-tinted bitmap",
            bmp,
            puck.topImage?.bitmap,
        )
    }
}
