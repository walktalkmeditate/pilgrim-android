// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.mapbox.maps.plugin.gestures.generated.GesturesSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.ui.walk.map.CameraFitPaddingDp
import org.walktalkmeditate.pilgrim.ui.walk.map.easeOutCameraAnimation
import org.walktalkmeditate.pilgrim.ui.walk.map.fitPaddingPx

/**
 * The Mapbox platform objects the camera fit, the gestures, and the seed
 * build (CLAUDE.md platform-object-builder rule): each test calls the
 * production builder rather than reconstructing the chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimMapCameraBuildersTest {

    @Test
    fun `a map is built with no style, so ours is the only style it loads`() {
        // Mapbox's own default (Standard) would load first when the view
        // starts, and a fit could ease under it (OnePlus 13, 2026-10-02).
        val options = pilgrimMapInitOptions(ApplicationProvider.getApplicationContext(), textureView = true)

        assertNull(options.styleUri)
    }

    @Test
    fun `fit ease carries the requested duration`() {
        assertEquals(2_500L, easeOutCameraAnimation(2_500L).duration)
    }

    @Test
    fun `fit ease follows the UIKit ease-out curve`() {
        // cubic-bezier(0, 0, 0.58, 1) at x = 0.5 is y ≈ 0.6846 (iOS's
        // default `.easeOut`); Android's FastOutSlowIn gives about 0.776.
        val interpolator = easeOutCameraAnimation(400L).interpolator
        assertNotNull(interpolator)
        assertEquals(0.6846f, interpolator!!.getInterpolation(0.5f), 0.005f)
    }

    @Test
    fun `fit padding converts dp to px at the EdgeInsets boundary`() {
        val insets = fitPaddingPx(
            CameraFitPaddingDp(top = 40.0, left = 30.0, bottom = 120.0, right = 30.0),
            density = 2.5f,
        )
        assertEquals(listOf(100.0, 75.0, 300.0, 75.0), listOf(insets.top, insets.left, insets.bottom, insets.right))
    }

    @Test
    fun `a map mid-reveal takes no pan or pinch`() {
        val settings = GesturesSettings.Builder().apply { applyPilgrimGestures(interactive = false) }.build()
        assertEquals(
            listOf(false, false, false),
            listOf(settings.scrollEnabled, settings.pinchToZoomEnabled, settings.pinchScrollEnabled),
        )
    }

    @Test
    fun `an interactive map takes pan and pinch`() {
        val settings = GesturesSettings.Builder().apply { applyPilgrimGestures(interactive = true) }.build()
        assertEquals(
            listOf(true, true, true),
            listOf(settings.scrollEnabled, settings.pinchToZoomEnabled, settings.pinchScrollEnabled),
        )
    }

    @Test
    fun `rotate and pitch gestures stay off even on an interactive map`() {
        val settings = GesturesSettings.Builder().apply { applyPilgrimGestures(interactive = true) }.build()
        assertEquals(listOf(false, false), listOf(settings.rotateEnabled, settings.pitchEnabled))
    }

    @Test
    fun `the tap zooms iOS never sets keep their enabled defaults`() {
        val settings = GesturesSettings.Builder().apply { applyPilgrimGestures(interactive = false) }.build()
        assertTrue(
            settings.doubleTapToZoomInEnabled &&
                settings.doubleTouchToZoomOutEnabled &&
                settings.quickZoomEnabled,
        )
    }

    @Test
    fun `a current-location seed lands at the follow zoom`() {
        val camera = buildSeedCamera(
            MapCameraSeed(fix(37.7749, -122.4194), MapCameraSeed.CURRENT_LOCATION_ZOOM),
        )
        assertEquals(16.0, camera.zoom ?: 0.0, 0.0)
    }

    @Test
    fun `a last-walk-end seed lands wider`() {
        val camera = buildSeedCamera(MapCameraSeed(fix(3.0, 4.0), MapCameraSeed.LAST_WALK_END_ZOOM))
        assertEquals(14.0, camera.zoom ?: 0.0, 0.0)
    }

    @Test
    fun `a seed centres on its point`() {
        val center = buildSeedCamera(MapCameraSeed(fix(3.0, 4.0), 14.0)).center
        assertNotNull(center)
        assertEquals(listOf(3.0, 4.0), listOf(center!!.latitude(), center.longitude()))
    }

    @Test
    fun `a seed carries no padding`() {
        // iOS `CameraOptions(center: seed.center, zoom: seed.zoom)`.
        assertNull(buildSeedCamera(MapCameraSeed(fix(3.0, 4.0), 14.0)).padding)
    }

    @Test
    fun `a fly-to lands on the moment at the follow zoom`() {
        val camera = buildFocusCamera(WayCoordinate(lat = 3.0, lon = 4.0), bottomInsetPx = 300.0)

        assertEquals(
            listOf(3.0, 4.0, 16.0),
            listOf(camera.center!!.latitude(), camera.center!!.longitude(), camera.zoom),
        )
    }

    @Test
    fun `a fly-to keeps the sheet's height as its bottom padding`() {
        val padding = buildFocusCamera(WayCoordinate(lat = 3.0, lon = 4.0), bottomInsetPx = 300.0).padding

        assertEquals(listOf(0.0, 0.0, 300.0, 0.0), listOf(padding!!.top, padding.left, padding.bottom, padding.right))
    }

    @Test
    fun `a fly-to with no sheet sets no padding`() {
        assertNull(buildFocusCamera(WayCoordinate(lat = 3.0, lon = 4.0), bottomInsetPx = 0.0).padding)
    }

    @Test
    fun `a seed leaves bearing and pitch to the map`() {
        val camera = buildSeedCamera(MapCameraSeed(fix(3.0, 4.0), 14.0))
        assertEquals(listOf(null, null), listOf(camera.bearing, camera.pitch))
    }

    private fun fix(lat: Double, lng: Double) =
        LocationPoint(timestamp = 0L, latitude = lat, longitude = lng)
}
