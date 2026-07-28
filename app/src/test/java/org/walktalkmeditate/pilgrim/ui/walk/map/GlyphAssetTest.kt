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
import org.walktalkmeditate.pilgrim.ui.walk.glyphRes

/**
 * The glyph masters' name contract and render sanity (iOS parity
 * `GlyphAssetTests.swift@9a418e4` + the clearing assertions @b4decad):
 * every whisper/cairn/clearing surface loads these drawables by id, so
 * a missing or renamed asset must fail here rather than silently
 * rendering nothing on the map. Pixel-level legibility on the dark
 * basemap stays a device check (port spec U13).
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

    /**
     * Glyphs that take their colour from a tint at render time (iOS
     * `tintedGlyphNames@b4decad`) — white-fill template drawables whose
     * baked root tint a caller's `setTint` replaces.
     */
    private val tintedGlyphDrawables =
        listOf(R.drawable.glyph_whisper_wisp, R.drawable.glyph_seek_clearing)

    private val allGlyphDrawables = tintedGlyphDrawables + tierDrawables.values

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
    fun `production glyph mapping matches the pinned table`() {
        // Double-entry with CairnTier.glyphRes (U16) — the production
        // analogue of iOS CairnTier.glyphAssetName must agree with this
        // test's independently pinned map.
        tierDrawables.forEach { (tier, id) ->
            assertEquals("tier $tier drifted from the pinned drawable", id, tier.glyphRes)
        }
    }

    @Test
    fun `all nine glyph drawables inflate`() {
        allGlyphDrawables.forEach { id ->
            assertNotNull("missing glyph drawable $id", context.getDrawable(id))
        }
    }

    @Test
    fun `tinted glyphs render differently under two tints`() {
        // iOS `testTintedGlyphsAreTemplateAssets` — Android has no
        // renderingMode to inspect, so tint RESPONSE is the template
        // contract: a baked (non-template) drawable renders identically
        // under any setTint and fails here.
        tintedGlyphDrawables.forEach { id ->
            fun tinted(color: Int): Bitmap {
                val glyph = load(id).mutate()
                glyph.setTint(color)
                return rasterize(glyph, 48)
            }
            val red = pixels(tinted(Color.RED))
            val blue = pixels(tinted(Color.BLUE))
            assertTrue("tinted glyph $id must be non-blank", red.any { Color.alpha(it) != 0 })
            assertFalse(
                "glyph $id must respond to tint (template behavior)",
                red.contentEquals(blue),
            )
        }
    }

    /**
     * The clearing's drawable wraps its path in a `<group>` scale +
     * translate. A mistranslated matrix (SVG translate-then-scale vs
     * VectorDrawable's pivot composition) would rasterise the tree
     * off-canvas or microscopic while every inflate-and-tint assertion
     * above still passed — so measure the pixels (iOS
     * `testClearingGlyphGeometrySurvivesTheAssetPipeline@b4decad`).
     *
     * Reference geometry from the iOS test (rsvg over the same master):
     * ink spans 75.7% of the box in width, 57.9% in height, centred;
     * shipped accuracy 0.04.
     */
    @Test
    fun `clearing ink geometry survives the translation`() {
        val side = 150
        val bitmap = rasterize(load(R.drawable.glyph_seek_clearing), side)
        val ink = requireNotNull(inkBounds(bitmap)) { "clearing rendered fully transparent" }
        val width = (ink.right - ink.left + 1) / side.toFloat()
        val height = (ink.bottom - ink.top + 1) / side.toFloat()
        val midX = (ink.left + ink.right + 1) / 2f / side
        val midY = (ink.top + ink.bottom + 1) / 2f / side
        assertEquals("clearing ink width drifted", 0.757f, width, 0.04f)
        assertEquals("clearing ink height drifted", 0.579f, height, 0.04f)
        assertEquals("clearing is not horizontally centred", 0.5f, midX, 0.04f)
        assertEquals("clearing is not vertically centred", 0.5f, midY, 0.04f)
    }

    /** Bounding box of everything more than faintly opaque (alpha > 8). */
    private fun inkBounds(bitmap: Bitmap): android.graphics.Rect? {
        val px = pixels(bitmap)
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(px[y * bitmap.width + x]) > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX >= minX && maxY >= minY) {
            android.graphics.Rect(minX, minY, maxX, maxY)
        } else {
            null
        }
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
