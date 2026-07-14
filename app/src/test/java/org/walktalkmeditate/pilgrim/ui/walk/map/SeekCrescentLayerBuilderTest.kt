// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint

/**
 * CLAUDE.md platform-object-builder rule: the crescent rides Mapbox's
 * runtime-validated style DSL (SymbolLayer + GeoJsonSource +
 * StyleTransition) and an android.graphics bitmap. Exercise the
 * production builder functions so a construction-time rejection surfaces
 * in CI, not only on-device. What a live style ACCEPTS (addLayer /
 * addSource / addImage) remains device-verified per the port spec's
 * smoke-check list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekCrescentLayerBuilderTest {

    private val walker = SeekPoint(latitude = 42.8782, longitude = -8.5448)

    @Test
    fun `crescent layer builds with the pinned ids`() {
        val layer = buildCrescentLayer(
            imageId = SeekCrescentRendering.imageId(48.0, "dawn-golden"),
            bearingDegrees = 135.0,
            breathTransitionMillis = SeekCrescentRendering.BREATH_MILLIS,
        )
        assertEquals(SeekCrescentRendering.LAYER_ID, layer.layerId)
        assertEquals(SeekCrescentRendering.SOURCE_ID, layer.sourceId)
    }

    @Test
    fun `reduce-motion variant builds with a zero transition`() {
        val layer = buildCrescentLayer(
            imageId = "seek-crescent-96-star-night",
            bearingDegrees = 0.0,
            breathTransitionMillis = 0L,
        )
        assertEquals(SeekCrescentRendering.LAYER_ID, layer.layerId)
    }

    @Test
    fun `crescent source builds a point feature at the walker`() {
        val source = buildCrescentSource(walker)
        assertEquals(SeekCrescentRendering.SOURCE_ID, source.sourceId)
    }

    @Test
    fun `crescent bitmap rasterizes at the pixel ratio`() {
        // Dimension + construction only: Robolectric's Canvas backend does
        // not rasterize stroke output (Stage 3-C precedent), so pixel
        // content is not assertable here. Arc GEOMETRY is pinned by the
        // pure crescentSegments tests; the visible result is a device
        // smoke-check item.
        val bitmap = renderCrescentBitmap(
            spanDegrees = 96.0,
            colorArgb = 0xFFC4956A.toInt(),
            pixelRatio = 2.0f,
        )
        assertEquals(168, bitmap.width)
        assertEquals(168, bitmap.height)
    }

    @Test
    fun `every span bucket rasterizes`() {
        for (span in listOf(96.0, 86.0, 72.0, 60.0, 48.0)) {
            val bitmap = renderCrescentBitmap(span, 0xFFD3BCE8.toInt(), pixelRatio = 1.0f)
            assertEquals(84, bitmap.width)
        }
    }
}
