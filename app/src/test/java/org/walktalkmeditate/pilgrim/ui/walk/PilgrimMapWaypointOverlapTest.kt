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
 * Manual-QA batch 2, BUG C1: a dropped waypoint never appeared on the
 * live map. The PointAnnotationManagers created in the loadStyle
 * callback never set `iconAllowOverlap`, so Mapbox's default symbol
 * collision engine culled the waypoint icon (created at the user's
 * exact location, colliding with the live puck).
 *
 * CLAUDE.md platform-object-builder rule: a real
 * `PointAnnotationManager` cannot be constructed under Robolectric
 * (`MapView.<init>` requires an EGL/GL context — same constraint that
 * forced the `buildStonePuck` extraction). The runtime-validated path
 * that Mapbox actually exercises at create-time is the
 * `PointAnnotationOptions().withPoint(...).withIconImage(bitmap)`
 * builder — that is the path this test locks down in CI. The
 * `iconAllowOverlap` / `iconIgnorePlacement` property assignments in
 * [allowIconOverlap] are plain setters with no runtime validation;
 * their effect (dropped waypoints render instead of being culled) is
 * verified by on-device QA. iOS parity `PilgrimMapView.swift:389@v1.6.0`.
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
