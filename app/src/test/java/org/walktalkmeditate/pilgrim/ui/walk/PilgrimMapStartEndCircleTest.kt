// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #224: the Walk Summary map drew start AND end as one shared
 * opaque "startEnd" bitmap PointAnnotation — the "big gold discs" at
 * both route ends the user flagged. iOS draws three distinct
 * CircleAnnotations (`PilgrimMapView.swift:372-385,407-413@2ee1185`):
 *
 * ```swift
 * case .startPoint:
 *     circle.circleRadius = 6
 *     circle.circleColor = StyleColor(UIColor.parchment)
 *     circle.circleOpacity = 0.9
 *     circle.circleStrokeColor = StyleColor(UIColor.stone)
 *     circle.circleStrokeWidth = 2
 *     circle.circleStrokeOpacity = 1.0
 * case .endPoint:
 *     circle.circleRadius = 7
 *     circle.circleColor = StyleColor(UIColor.ink)
 *     circle.circleOpacity = 0.9
 *     circle.circleStrokeColor = StyleColor(UIColor.stone)
 *     circle.circleStrokeWidth = 2
 *     circle.circleStrokeOpacity = 1.0
 * // glowCircle(for: .endPoint):
 *     glow.circleRadius = 18
 *     glow.circleColor = StyleColor(UIColor.stone)
 *     glow.circleOpacity = 0.15
 *     glow.circleStrokeWidth = 0
 * ```
 *
 * CLAUDE.md platform-object-builder rule: a real `CircleAnnotationManager`
 * cannot be constructed under Robolectric (`MapView.<init>` requires an
 * EGL/GL context — same constraint `PilgrimMapWaypointOverlapTest`
 * documents for `PointAnnotationManager`). The runtime-validated path
 * this test locks down in CI is the
 * `CircleAnnotationOptions().withPoint(...).withCircleRadius(...)...`
 * builder chain PilgrimMap's start/end branches actually call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimMapStartEndCircleTest {

    private val parchmentArgb = 0xFFF5F0E8.toInt()
    private val inkArgb = 0xFF2C2416.toInt()
    private val stoneArgb = 0xFF8B7355.toInt()

    @Test
    fun `start-point CircleAnnotationOptions builds with iOS-parity values`() {
        val options = CircleAnnotationOptions()
            .withPoint(Point.fromLngLat(139.7, 35.6))
            .withCircleRadius(6.0)
            .withCircleColor(parchmentArgb)
            .withCircleOpacity(0.9)
            .withCircleStrokeColor(stoneArgb)
            .withCircleStrokeWidth(2.0)
            .withCircleStrokeOpacity(1.0)

        val point = options.getPoint()
        assertNotNull(point)
        assertEquals(139.7, point?.longitude() ?: 0.0, 1e-9)
        assertEquals(35.6, point?.latitude() ?: 0.0, 1e-9)

        assertEquals(6.0, options.circleRadius ?: 0.0, 0.0)
        assertEquals(0.9, options.circleOpacity ?: 0.0, 0.0)
        assertEquals(2.0, options.circleStrokeWidth ?: 0.0, 0.0)
        assertEquals(1.0, options.circleStrokeOpacity ?: 0.0, 0.0)
        assertNotNull(
            "circleColor must accept the parchment int without throwing",
            options.circleColor,
        )
        assertNotNull(
            "circleStrokeColor must accept the stone int without throwing",
            options.circleStrokeColor,
        )
    }

    @Test
    fun `end-point CircleAnnotationOptions builds with iOS-parity values`() {
        val options = CircleAnnotationOptions()
            .withPoint(Point.fromLngLat(139.7, 35.6))
            .withCircleRadius(7.0)
            .withCircleColor(inkArgb)
            .withCircleOpacity(0.9)
            .withCircleStrokeColor(stoneArgb)
            .withCircleStrokeWidth(2.0)
            .withCircleStrokeOpacity(1.0)

        assertEquals(7.0, options.circleRadius ?: 0.0, 0.0)
        assertEquals(0.9, options.circleOpacity ?: 0.0, 0.0)
        assertEquals(2.0, options.circleStrokeWidth ?: 0.0, 0.0)
        assertEquals(1.0, options.circleStrokeOpacity ?: 0.0, 0.0)
        assertNotNull(
            "circleColor must accept the ink int without throwing",
            options.circleColor,
        )
    }

    @Test
    fun `end-glow CircleAnnotationOptions builds with iOS-parity values`() {
        val options = CircleAnnotationOptions()
            .withPoint(Point.fromLngLat(139.7, 35.6))
            .withCircleRadius(18.0)
            .withCircleColor(stoneArgb)
            .withCircleOpacity(0.15)
            .withCircleStrokeWidth(0.0)

        assertEquals(18.0, options.circleRadius ?: 0.0, 0.0)
        assertEquals(0.15, options.circleOpacity ?: 0.0, 0.0)
        assertEquals(0.0, options.circleStrokeWidth ?: -1.0, 0.0)
        assertNotNull(
            "circleColor must accept the stone int without throwing",
            options.circleColor,
        )
        // No stroke color set for the glow (iOS never sets one either —
        // `circleStrokeWidth = 0` makes it moot).
        assertNull(options.circleStrokeColor)
    }
}
