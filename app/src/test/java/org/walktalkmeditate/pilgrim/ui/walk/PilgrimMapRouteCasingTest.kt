// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The route line drew as a bare 4dp polyline with no casing. iOS draws
 * TWO stacked line layers
 * (`PilgrimMapView+RouteSource.swift:121-133@2ee1185`):
 *
 * ```swift
 * var casing = LineLayer(id: "pilgrim-route-casing", source: Self.routeSourceId)
 * casing.lineWidth = .constant(10)
 * casing.lineCap = .constant(.round)
 * casing.lineJoin = .constant(.round)
 * casing.lineOpacity = .constant(0.3)
 * casing.lineColor = .constant(StyleColor(.white))
 * try mapView.mapboxMap.addLayer(casing)
 *
 * var layer = LineLayer(id: "pilgrim-route-layer", source: Self.routeSourceId)
 * layer.lineWidth = .constant(6)
 * layer.lineCap = .constant(.round)
 * layer.lineJoin = .constant(.round)
 * layer.lineOpacity = .constant(1.0)
 * ```
 *
 * CLAUDE.md platform-object-builder rule: a real
 * `PolylineAnnotationManager` cannot be constructed under Robolectric
 * (`MapView.<init>` requires an EGL/GL context — same constraint
 * `PilgrimMapWaypointOverlapTest` / `PilgrimMapStartEndCircleTest`
 * document). The runtime-validated path Mapbox exercises at create time
 * is the `PolylineAnnotationOptions` builder chain, so the production
 * factories are called here directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimMapRouteCasingTest {

    private val points = listOf(
        Point.fromLngLat(139.7, 35.6),
        Point.fromLngLat(139.71, 35.61),
    )

    @Test
    fun `route line width matches iOS lineWidth 6`() {
        // PilgrimMapView+RouteSource.swift:130@2ee1185
        assertEquals(6.0, ROUTE_LINE_WIDTH_DP, 0.0)
    }

    @Test
    fun `casing width matches iOS lineWidth 10`() {
        // PilgrimMapView+RouteSource.swift:122@2ee1185
        assertEquals(10.0, ROUTE_CASING_WIDTH_DP, 0.0)
    }

    @Test
    fun `casing layer id matches the id iOS seek fog anchors below`() {
        // PilgrimMapView+RouteSource.swift:121@2ee1185 +
        // PilgrimMapView+SeekFog.swift:277-280@2ee1185.
        assertEquals("pilgrim-route-casing", ROUTE_CASING_LAYER_ID)
    }

    @Test
    fun `route line options build at iOS width with round join`() {
        val options = routeLineOptions(points, 0xFF7A8B6F.toInt())

        assertEquals(6.0, options.lineWidth ?: 0.0, 0.0)
        assertEquals(LineJoin.ROUND, options.lineJoin)
        assertNotNull("lineColor must accept the walking int", options.lineColor)
        // iOS `layer.lineOpacity = .constant(1.0)` is the Mapbox default;
        // leaving it unset keeps the main layer's opacity a constant
        // rather than promoting it to a data-driven expression.
        assertNull(options.lineOpacity)
        assertEquals(2, options.getGeometry()?.coordinates()?.size)
    }

    @Test
    fun `casing options build white at iOS width and opacity`() {
        val options = routeCasingOptions(points)

        assertEquals(10.0, options.lineWidth ?: 0.0, 0.0)
        assertEquals(0.3, options.lineOpacity ?: 0.0, 1e-9)
        assertEquals(LineJoin.ROUND, options.lineJoin)
        assertNotNull("lineColor must accept the white int", options.lineColor)
        assertEquals(2, options.getGeometry()?.coordinates()?.size)
    }

    @Test
    fun `casing colour is opaque white before the opacity multiplier`() {
        // iOS `casing.lineColor = .constant(StyleColor(.white))` — the
        // 0.3 lives in lineOpacity, not in the colour's alpha.
        assertEquals(0xFFFFFFFF.toInt(), ROUTE_CASING_ARGB)
    }

    @Test
    fun `casing mirrors whatever geometry the route line was given`() {
        // Bookkeeping invariant: both factories project the SAME point
        // list, so a casing polyline can never carry stale geometry that
        // the route line does not.
        val line = routeLineOptions(points, 0xFF7A8B6F.toInt())
        val casing = routeCasingOptions(points)

        assertEquals(
            line.getGeometry()?.coordinates(),
            casing.getGeometry()?.coordinates(),
        )
    }
}
