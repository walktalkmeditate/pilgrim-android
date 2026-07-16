// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint

/**
 * CLAUDE.md platform-object-builder rule: the fog/ring layers ride Mapbox's
 * runtime-validated style DSL (CircleLayer + GeoJsonSource + Expression +
 * StyleTransition). Exercise the production builder functions so a
 * construction-time rejection surfaces in CI, not only on-device. What a
 * live style ACCEPTS (addLayer/addSource) remains device-verified per the
 * port spec's smoke-check list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekFogLayerBuilderTest {

    private val fogCircle = SeekFogState.FogCircle(
        id = SeekFogModel.fogCircleId(0),
        center = SeekPoint(latitude = 42.8782, longitude = -8.5448),
        radiusMeters = 50.0,
        opacityBucket = 5,
        isHalo = false,
    )

    @Test
    fun `fog circle layer builds with the pinned ids`() {
        val layer = buildFogCircleLayer(
            fogCircle,
            tintHex = null,
            transitionMillis = SeekFogRendering.FOG_TRANSITION_MILLIS,
        )
        assertEquals("seek-fog-0", layer.layerId)
        assertEquals("seek-fog-0-source", layer.sourceId)
    }

    @Test
    fun `halo and tinted variants build`() {
        val halo = buildFogCircleLayer(
            fogCircle.copy(opacityBucket = 0, isHalo = true),
            tintHex = null,
            transitionMillis = 0L,
        )
        assertNotNull(halo)
        val tinted = buildFogCircleLayer(
            fogCircle,
            tintHex = "#2377A4",
            transitionMillis = SeekFogRendering.FOG_TRANSITION_MILLIS,
        )
        assertNotNull(tinted)
    }

    @Test
    fun `fog source builds a point feature for the clearing center`() {
        val source = buildFogSource(fogCircle)
        assertEquals("seek-fog-0-source", source.sourceId)
    }

    @Test
    fun `pulse ring layer builds with the pinned ids`() {
        val layer = buildPulseRingLayer(SeekFogRendering.DEFAULT_LIGHT_COLOR_ARGB)
        assertEquals(SeekFogRendering.RING_LAYER_ID, layer.layerId)
        assertEquals(SeekFogRendering.RING_SOURCE_ID, layer.sourceId)
    }

    @Test
    fun `radius expression interpolates exponentially over zoom`() {
        val expression = fogRadiusExpression(radiusMeters = 50.0, latitude = 42.8782)
        val rendered = expression.toString()
        assertTrue("interpolate operator missing: $rendered", "interpolate" in rendered)
        assertTrue("exponential base missing: $rendered", "exponential" in rendered)
        assertTrue("zoom input missing: $rendered", "zoom" in rendered)
    }
}
