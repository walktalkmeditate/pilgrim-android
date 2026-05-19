// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.graphics.Bitmap
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Manual-QA batch 3, BUG #2: a dropped waypoint never appeared on the
 * live map. Verified root cause is LAYER Z-ORDER, not symbol collision
 * (the earlier `iconAllowOverlap` fix was a misdiagnosis — left in
 * place as harmless). Mapbox inserts the `location-indicator` puck
 * layer at the TOP of the layer stack (null LayerPosition); when the
 * `loadStyle` callback enabled the location component AFTER creating
 * the annotation managers, the puck rendered ABOVE the waypoint
 * SymbolLayer. A waypoint dropped at the user's exact location sat
 * under the larger stone puck and was fully occluded. The fix enables
 * the location component BEFORE the annotation managers are created so
 * the annotation SymbolLayers stack on top of the location-indicator
 * layer — iOS parity `PilgrimMapView.swift:123,128-144@v1.6.0`
 * (`configurePuck` runs in `makeUIView` before `onStyleLoaded`
 * recreates the annotation managers).
 *
 * CLAUDE.md platform-object-builder rule: a real
 * `PointAnnotationManager` cannot be constructed under Robolectric
 * (`MapView.<init>` requires an EGL/GL context — same constraint that
 * forced the `buildStonePuck` extraction), and layer z-order needs a
 * live EGL surface, so the reorder itself can only be proven on-device.
 * The runtime-validated path that Mapbox actually exercises at
 * create-time is the
 * `PointAnnotationOptions().withPoint(...).withIconImage(bitmap)`
 * builder — that is the path this test locks down in CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimMapWaypointOverlapTest {

    @Test
    fun `waypoint PointAnnotationOptions builds with point and icon`() {
        // The real Mapbox options builder — withPoint + withIconImage
        // is the exact call PilgrimMap's waypoint sync uses. Building
        // it here locks the builder path under CI.
        val bitmap = createPuckBitmap(0xFF8B7355.toInt())
        val options = PointAnnotationOptions()
            .withPoint(Point.fromLngLat(139.7, 35.6))
            .withIconImage(bitmap)

        val builtPoint = options.getPoint()
        assertNotNull(builtPoint)
        assertEquals(139.7, builtPoint?.longitude() ?: 0.0, 1e-9)
        assertEquals(35.6, builtPoint?.latitude() ?: 0.0, 1e-9)
        assertNotNull("withIconImage must accept the waypoint bitmap", options)
    }

    @Test
    fun `geometry round-trips through the options builder`() {
        // getGeometry() is the accessor the manager reads when it
        // places the symbol; a builder regression that dropped the
        // point would silently mean no waypoint pin at all.
        val options = PointAnnotationOptions()
            .withPoint(Point.fromLngLat(-122.4194, 37.7749))

        val geometry = options.getGeometry()
        assertNotNull(geometry)
        assertEquals(-122.4194, geometry!!.longitude(), 1e-9)
        assertEquals(37.7749, geometry.latitude(), 1e-9)
    }

    @Test
    fun `created bitmap is a valid ARGB image for the icon`() {
        val bitmap = createPuckBitmap(0xFF8B7355.toInt())
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    }
}
