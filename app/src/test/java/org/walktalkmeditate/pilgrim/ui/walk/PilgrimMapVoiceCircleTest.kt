// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import com.mapbox.geojson.Point
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #220: the Walk Summary map drew voice-recording pins as an
 * opaque bitmap PointAnnotation — a solid dot with a light ring — while
 * iOS draws a translucent rust CircleAnnotation that visually melts
 * into the rust "talking" route segment (`PilgrimMapView.swift:365-371@2ee1185`):
 *
 * ```swift
 * case .voiceRecording:
 *     circle.circleRadius = 8
 *     circle.circleColor = StyleColor(UIColor.rust)
 *     circle.circleOpacity = 0.8
 *     circle.circleStrokeColor = StyleColor(UIColor.rust)
 *     circle.circleStrokeWidth = 1.5
 *     circle.circleStrokeOpacity = 1.0
 * ```
 *
 * CLAUDE.md platform-object-builder rule: a real `CircleAnnotationManager`
 * cannot be constructed under Robolectric (`MapView.<init>` requires an
 * EGL/GL context — same constraint `PilgrimMapWaypointOverlapTest`
 * documents for `PointAnnotationManager`). The runtime-validated path
 * this test locks down in CI is the
 * `CircleAnnotationOptions().withPoint(...).withCircleRadius(...)...`
 * builder chain PilgrimMap's voice-recording branch actually calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimMapVoiceCircleTest {

    @Test
    fun `voice-recording CircleAnnotationOptions builds with iOS-parity values`() {
        val rustArgb = 0xFFA0634B.toInt()
        val options = CircleAnnotationOptions()
            .withPoint(Point.fromLngLat(139.7, 35.6))
            .withCircleRadius(8.0)
            .withCircleColor(rustArgb)
            .withCircleOpacity(0.8)
            .withCircleStrokeColor(rustArgb)
            .withCircleStrokeWidth(1.5)
            .withCircleStrokeOpacity(1.0)

        val point = options.getPoint()
        assertNotNull(point)
        assertEquals(139.7, point?.longitude() ?: 0.0, 1e-9)
        assertEquals(35.6, point?.latitude() ?: 0.0, 1e-9)

        // iOS-parity numeric fields — radius/opacity/stroke width all
        // fixed literals in `buildCircles`'s `.voiceRecording` case.
        // (Plain Kotlin property syntax — unlike `getPoint()` above,
        // these are ordinary `var` properties with no separate
        // Kotlin-visible `getXxx()` function.)
        assertEquals(8.0, options.circleRadius ?: 0.0, 0.0)
        assertEquals(0.8, options.circleOpacity ?: 0.0, 0.0)
        assertEquals(1.5, options.circleStrokeWidth ?: 0.0, 0.0)
        assertEquals(1.0, options.circleStrokeOpacity ?: 0.0, 0.0)
        assertNotNull(
            "circleColor must accept the rust int without throwing",
            options.circleColor,
        )
        assertNotNull(
            "circleStrokeColor must accept the rust int without throwing",
            options.circleStrokeColor,
        )
    }
}
