// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The live map hand-rolled follow with a per-GPS-sample `easeTo` that
 * preserved the current bearing, so the camera stayed north-up. iOS
 * hands the camera to the viewport system instead
 * (`PilgrimMapView.swift:216-220@2ee1185`):
 *
 * ```swift
 * mapView.viewport.transition(
 *     to: mapView.viewport.makeFollowPuckViewportState(
 *         options: FollowPuckViewportStateOptions(padding: padding, zoom: 16)
 *     )
 * )
 * ```
 *
 * `bearing` and `pitch` are omitted, so the iOS SDK defaults apply —
 * `bearing: .heading`, `pitch: 45`
 * (`FollowPuckViewportStateOptions.swift:38-44`, mapbox-maps-ios). The
 * Android analogue of `.heading` is
 * `FollowPuckViewportStateBearing.SyncWithLocationPuck` combined with
 * `puckBearing = PuckBearing.HEADING` on the location component.
 *
 * CLAUDE.md platform-object-builder rule: `FollowPuckViewportStateOptions`
 * is a runtime-validated builder, so this test calls the production
 * factory rather than reconstructing the chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimMapFollowViewportTest {

    @Test
    fun `follow options build with the sheet inset as bottom padding`() {
        val options = buildFollowPuckOptions(bottomInsetPx = 420.0)

        val padding = options.padding
        assertNotNull(padding)
        assertEquals(0.0, padding!!.top, 0.0)
        assertEquals(0.0, padding.left, 0.0)
        assertEquals(420.0, padding.bottom, 0.0)
        assertEquals(0.0, padding.right, 0.0)
    }

    @Test
    fun `follow options build at the iOS follow zoom`() {
        // PilgrimMapView.swift:218@2ee1185 — `zoom: 16`.
        val options = buildFollowPuckOptions(bottomInsetPx = 0.0)
        assertEquals(16.0, options.zoom ?: 0.0, 0.0)
    }

    @Test
    fun `follow options sync bearing with the location puck`() {
        // The parity gap: iOS's omitted `bearing` defaults to `.heading`,
        // which rotates the camera with the compass.
        val options = buildFollowPuckOptions(bottomInsetPx = 0.0)
        assertEquals(
            FollowPuckViewportStateBearing.SyncWithLocationPuck,
            options.bearing,
        )
    }

    @Test
    fun `follow options keep the SDK-default pitch both platforms inherit`() {
        // iOS omits `pitch`, inheriting 45 from
        // `FollowPuckViewportStateOptions.init`; Android's builder
        // default is the same 45. Pinned so a future SDK bump that moves
        // one platform's default is caught here.
        val options = buildFollowPuckOptions(bottomInsetPx = 0.0)
        assertEquals(45.0, options.pitch ?: 0.0, 0.0)
    }

    @Test
    fun `a zero inset still produces explicit zero padding`() {
        // Summary/share maps never call this, but the live map's first
        // composition can run before the sheet reports a height; padding
        // must be a real EdgeInsets so the transition is deterministic.
        val options = buildFollowPuckOptions(bottomInsetPx = 0.0)
        assertNotNull(options.padding)
        assertEquals(0.0, options.padding!!.bottom, 0.0)
    }
}
