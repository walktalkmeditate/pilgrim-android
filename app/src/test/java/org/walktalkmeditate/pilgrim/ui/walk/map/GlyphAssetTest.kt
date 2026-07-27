// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.cairn.CairnTier

/**
 * The glyph masters' name contract and render sanity (iOS parity
 * `GlyphAssetTests.swift@9a418e4`): every whisper/cairn surface loads
 * these drawables by id, so a missing or renamed asset must fail here
 * rather than silently rendering nothing on the map. Pixel-level
 * legibility on the dark basemap stays a device check (port spec U13).
 *
 * [GraphicsMode.Mode.NATIVE] is required: this project's Robolectric
 * default is LEGACY shadows, whose Canvas draws nothing — every pixel
 * assertion below would vacuously fail on blank bitmaps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlyphAssetTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val tierDrawables = mapOf(
        CairnTier.Faint to R.drawable.glyph_cairn_faint,
        CairnTier.Small to R.drawable.glyph_cairn_small,
        CairnTier.Medium to R.drawable.glyph_cairn_medium,
        CairnTier.Large to R.drawable.glyph_cairn_large,
        CairnTier.Great to R.drawable.glyph_cairn_great,
        CairnTier.Sacred to R.drawable.glyph_cairn_sacred,
        CairnTier.Eternal to R.drawable.glyph_cairn_eternal,
    )

    private val allGlyphDrawables =
        listOf(R.drawable.glyph_whisper_wisp) + tierDrawables.values

    private fun load(id: Int): Drawable =
        requireNotNull(context.getDrawable(id)) { "drawable $id failed to inflate" }

    private fun rasterize(drawable: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return out
    }

    @Test
    fun `every tier maps to a drawable`() {
        assertEquals(CairnTier.entries.toSet(), tierDrawables.keys)
    }

    @Test
    fun `all eight glyph drawables inflate`() {
        allGlyphDrawables.forEach { id ->
            assertNotNull("missing glyph drawable $id", context.getDrawable(id))
        }
    }

    @Test
    fun `wisp renders differently under two tints`() {
        fun tinted(color: Int): Bitmap {
            val wisp = load(R.drawable.glyph_whisper_wisp).mutate()
            wisp.setTint(color)
            return rasterize(wisp, 48)
        }
        val red = pixels(tinted(Color.RED))
        val blue = pixels(tinted(Color.BLUE))
        assertTrue("tinted wisp must be non-blank", red.any { Color.alpha(it) != 0 })
        assertFalse(
            "wisp must respond to tint (template behavior)",
            red.contentEquals(blue),
        )
    }

    @Test
    fun `each cairn tier rasterizes non-blank at map size`() {
        tierDrawables.forEach { (tier, id) ->
            val bitmap = rasterize(load(id), MAP_SIZE_PX)
            assertTrue(
                "tier $tier rendered fully transparent at ${MAP_SIZE_PX}px",
                pixels(bitmap).any { Color.alpha(it) != 0 },
            )
        }
    }

    @Test
    fun `adjacent cairn tiers produce distinct bitmaps`() {
        val ordered = CairnTier.entries.map { pixels(rasterize(load(tierDrawables.getValue(it)), 48)) }
        CairnTier.entries.zipWithNext().forEachIndexed { index, (lower, upper) ->
            assertFalse(
                "tiers $lower and $upper rendered identical bitmaps",
                ordered[index].contentEquals(ordered[index + 1]),
            )
        }
    }

    private companion object {
        /** Smallest live pin footprint: iOS's 12dp faint baseline doubled per #57. */
        const val MAP_SIZE_PX = 24
    }
}
