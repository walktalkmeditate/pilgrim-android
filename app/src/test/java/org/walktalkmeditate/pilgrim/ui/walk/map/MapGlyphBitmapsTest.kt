// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.walktalkmeditate.pilgrim.data.cairn.CairnTier
import org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory

/**
 * The rasterizer's pixel and cache contracts (iOS parity
 * `MapGlyphImageBuilderTests.swift@9a418e4`, port spec
 * docs/parity/2026-07-27-port-map-glyphs-u14-u15.md L7). A silent
 * regression here blurs or mistints every whisper/cairn pin, so
 * dimensions, tint response, cache identity, and the shipped size
 * table are pinned directly.
 *
 * [GraphicsMode.Mode.NATIVE] is required: the project's Robolectric
 * default is LEGACY shadows whose Canvas draws nothing, so every
 * pixel assertion would vacuously pass/fail on blank bitmaps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapGlyphBitmapsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearCache() {
        MapGlyphBitmaps.clearCache()
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return out
    }

    @Test
    fun `wisp output is dp times density at two densities`() {
        val at2x = requireNotNull(
            MapGlyphBitmaps.wisp(context, TINT_RED, sizeDp = 28f, density = 2f),
        )
        assertEquals(56, at2x.width)
        assertEquals(56, at2x.height)
        val at3halfX = requireNotNull(
            MapGlyphBitmaps.wisp(context, TINT_RED, sizeDp = 28f, density = 3.5f),
        )
        assertEquals(98, at3halfX.width)
        assertEquals(98, at3halfX.height)
    }

    @Test
    fun `cairn output is dp times density at two densities`() {
        val at1x = requireNotNull(
            MapGlyphBitmaps.cairn(context, CairnTier.Faint, sizeDp = 24f, density = 1f),
        )
        assertEquals(24, at1x.width)
        assertEquals(24, at1x.height)
        val at420dpi = requireNotNull(
            MapGlyphBitmaps.cairn(context, CairnTier.Faint, sizeDp = 24f, density = 2.625f),
        )
        assertEquals(63, at420dpi.width)
        assertEquals(63, at420dpi.height)
    }

    @Test
    fun `rasters are ARGB_8888`() {
        val wisp = requireNotNull(MapGlyphBitmaps.wisp(context, TINT_RED, 28f, 2f))
        assertEquals(Bitmap.Config.ARGB_8888, wisp.config)
        val cairn = requireNotNull(MapGlyphBitmaps.cairn(context, CairnTier.Eternal, 36f, 2f))
        assertEquals(Bitmap.Config.ARGB_8888, cairn.config)
    }

    @Test
    fun `every mood tint produces a distinct non-null wisp`() {
        val rendered = WhisperCategory.entries.map { category ->
            val bitmap = MapGlyphBitmaps.wisp(
                context, category.borderColor.toArgb(), WHISPER_GLYPH_SIZE_DP, density = 2f,
            )
            assertNotNull("no wisp rendered for mood $category", bitmap)
            category to pixels(requireNotNull(bitmap))
        }
        rendered.forEach { (category, px) ->
            assertTrue("wisp for $category rendered blank", px.any { it ushr 24 != 0 })
        }
        for (i in rendered.indices) {
            for (j in i + 1 until rendered.size) {
                assertFalse(
                    "moods ${rendered[i].first} and ${rendered[j].first} rendered identically",
                    rendered[i].second.contentEquals(rendered[j].second),
                )
            }
        }
    }

    @Test
    fun `every tier produces a distinct non-null bitmap`() {
        val rendered = CairnTier.entries.map { tier ->
            val bitmap = MapGlyphBitmaps.cairn(context, tier, sizeDp = 48f, density = 1f)
            assertNotNull("no cairn rendered for tier $tier", bitmap)
            tier to pixels(requireNotNull(bitmap))
        }
        rendered.forEach { (tier, px) ->
            assertTrue("cairn for $tier rendered blank", px.any { it ushr 24 != 0 })
        }
        for (i in rendered.indices) {
            for (j in i + 1 until rendered.size) {
                assertFalse(
                    "tiers ${rendered[i].first} and ${rendered[j].first} rendered identically",
                    rendered[i].second.contentEquals(rendered[j].second),
                )
            }
        }
    }

    @Test
    fun `identical key returns the cached instance`() {
        assertSame(
            MapGlyphBitmaps.wisp(context, TINT_RED, 28f, 2f),
            MapGlyphBitmaps.wisp(context, TINT_RED, 28f, 2f),
        )
        assertSame(
            MapGlyphBitmaps.cairn(context, CairnTier.Great, 32f, 2f),
            MapGlyphBitmaps.cairn(context, CairnTier.Great, 32f, 2f),
        )
    }

    @Test
    fun `distinct densities produce distinct cache entries`() {
        assertNotSame(
            MapGlyphBitmaps.wisp(context, TINT_RED, 28f, 2f),
            MapGlyphBitmaps.wisp(context, TINT_RED, 28f, 3f),
        )
        assertNotSame(
            MapGlyphBitmaps.cairn(context, CairnTier.Faint, 24f, 2f),
            MapGlyphBitmaps.cairn(context, CairnTier.Faint, 24f, 3f),
        )
    }

    @Test
    fun `density change evicts the previous density's entries`() {
        val wispAtA = requireNotNull(MapGlyphBitmaps.wisp(context, TINT_RED, 28f, 2f))
        MapGlyphBitmaps.cairn(context, CairnTier.Faint, 24f, 2f)
        assertEquals(2, MapGlyphBitmaps.cacheSize())

        MapGlyphBitmaps.wisp(context, TINT_RED, 28f, 3f)
        assertEquals(1, MapGlyphBitmaps.cacheSize())

        val wispAtAAgain = requireNotNull(MapGlyphBitmaps.wisp(context, TINT_RED, 28f, 2f))
        assertNotSame(wispAtA, wispAtAAgain)
    }

    @Test
    fun `translucent wisp tint is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MapGlyphBitmaps.wisp(context, 0x80FF0000.toInt(), 28f, 2f)
        }
    }

    @Test
    fun `every glyph fills a conservative fraction of its canvas`() {
        val fractions = buildMap {
            val wisp = requireNotNull(MapGlyphBitmaps.wisp(context, TINT_RED, 48f, 1f))
            put("wisp", coverageFraction(wisp))
            CairnTier.entries.forEach { tier ->
                val cairn = requireNotNull(MapGlyphBitmaps.cairn(context, tier, 48f, 1f))
                put("cairn-$tier", coverageFraction(cairn))
            }
        }
        println("glyph coverage fractions: $fractions")
        fractions.forEach { (name, fraction) ->
            assertTrue(
                "$name fills only $fraction of its canvas (floor $MIN_COVERAGE_FRACTION) — " +
                    "a vector <group> translate likely shoved the art off-canvas",
                fraction >= MIN_COVERAGE_FRACTION,
            )
        }
    }

    private fun coverageFraction(bitmap: Bitmap): Float {
        val px = pixels(bitmap)
        return px.count { it ushr 24 != 0 } / px.size.toFloat()
    }

    @Test
    fun `cairn cache is insensitive to a theme flip`() {
        val day = MapGlyphBitmaps.cairn(context, CairnTier.Sacred, 34f, 2f)
        val nightConfig = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        }
        val nightContext = context.createConfigurationContext(nightConfig)
        assertSame(day, MapGlyphBitmaps.cairn(nightContext, CairnTier.Sacred, 34f, 2f))
    }

    @Test
    fun `missing drawable degrades to null without crashing`() {
        assertNull(
            MapGlyphBitmaps.rendered(
                context = context,
                drawableId = 0,
                tintArgb = null,
                key = "missing-glyph",
                sizeDp = 24f,
                density = 2f,
            ),
        )
    }

    @Test
    fun `size table matches the shipped iOS map sizes`() {
        assertEquals(28f, WHISPER_GLYPH_SIZE_DP, 0f)
        assertEquals(24f, cairnGlyphSizeDp(CairnTier.Faint), 0f)
        assertEquals(26f, cairnGlyphSizeDp(CairnTier.Small), 0f)
        assertEquals(28f, cairnGlyphSizeDp(CairnTier.Medium), 0f)
        assertEquals(30f, cairnGlyphSizeDp(CairnTier.Large), 0f)
        assertEquals(32f, cairnGlyphSizeDp(CairnTier.Great), 0f)
        assertEquals(34f, cairnGlyphSizeDp(CairnTier.Sacred), 0f)
        assertEquals(36f, cairnGlyphSizeDp(CairnTier.Eternal), 0f)
    }

    private companion object {
        const val TINT_RED = 0xFFFF0000.toInt()

        /**
         * Guards translate-group clipping regressions (art shoved off
         * the raster), not visual quality. Measured at 48px on
         * 2026-07-27: wisp 0.112 (the minimum), cairns 0.251-0.479 —
         * pinned at half the measured minimum for headroom.
         */
        const val MIN_COVERAGE_FRACTION = 0.05f
    }
}
