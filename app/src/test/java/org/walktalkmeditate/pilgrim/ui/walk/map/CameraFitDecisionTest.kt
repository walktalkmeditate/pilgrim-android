// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds

/**
 * iOS parity `PilgrimMapView.swift:256-295@7c200bf`, the bounds-fit rules
 * after iOS #81 and #89:
 *
 * ```swift
 * let changed = context.coordinator.lastAppliedBounds != bounds
 *     || context.coordinator.lastAppliedBoundsInset != bottomInset
 * let roomForRoute: CGFloat = 160
 * let headroom = max(0, mapView.bounds.height - roomForRoute - 80)
 * let padding = UIEdgeInsets(top: 40, left: 30,
 *                            bottom: 40 + min(bottomInset, headroom), right: 30)
 * let fits = mapView.bounds.height > padding.top + padding.bottom
 *     && mapView.bounds.width > padding.left + padding.right
 * if changed && fits { ... }
 * ```
 */
class CameraFitDecisionTest {

    private val route = MapCameraBounds(swLat = 1.0, swLng = 2.0, neLat = 3.0, neLng = 4.0)
    private val segment = MapCameraBounds(swLat = 1.5, swLng = 2.5, neLat = 2.0, neLng = 3.0)

    private fun decide(
        bounds: MapCameraBounds = route,
        insetDp: Double = 0.0,
        widthDp: Double = 400.0,
        heightDp: Double = 800.0,
        lastApplied: AppliedCameraFit? = null,
    ) = decideCameraFit(bounds, insetDp, widthDp, heightDp, lastApplied)

    private fun fitWithBottom(bottom: Double) = CameraFitDecision.Fit(
        CameraFitPaddingDp(top = 40.0, left = 30.0, bottom = bottom, right = 30.0),
    )

    @Test
    fun `a first application with no record always fits`() {
        assertEquals(fitWithBottom(40.0), decide(lastApplied = null))
    }

    @Test
    fun `new bounds with room fit under the inset while it is inside the headroom`() {
        // headroom = 800 - 160 - 80 = 560, so the whole 200 inset applies.
        assertEquals(
            fitWithBottom(240.0),
            decide(bounds = segment, insetDp = 200.0, lastApplied = AppliedCameraFit(route, 200.0)),
        )
    }

    @Test
    fun `the same bounds and inset again are unchanged`() {
        assertEquals(
            CameraFitDecision.SkipUnchanged,
            decide(insetDp = 200.0, lastApplied = AppliedCameraFit(route, 200.0)),
        )
    }

    @Test
    fun `an inset change with the same bounds refits`() {
        assertEquals(
            fitWithBottom(260.0),
            decide(insetDp = 220.0, lastApplied = AppliedCameraFit(route, 200.0)),
        )
    }

    @Test
    fun `an inset taller than the headroom is clamped to leave the route its room`() {
        // headroom = 400 - 160 - 80 = 160, so the bottom is 40 + 160.
        assertEquals(fitWithBottom(200.0), decide(insetDp = 300.0, heightDp = 400.0))
    }

    @Test
    fun `a raw inset change that clamps to the same padding still refits`() {
        // pilgrim-ios #94, matched as shipped: iOS compares the raw inset.
        assertEquals(
            fitWithBottom(200.0),
            decide(insetDp = 350.0, heightDp = 400.0, lastApplied = AppliedCameraFit(route, 300.0)),
        )
    }

    @Test
    fun `a sub-point inset change refits because there is no tolerance`() {
        // Unlike the follow path's 0.5 guard.
        assertEquals(
            fitWithBottom(240.25),
            decide(insetDp = 200.25, lastApplied = AppliedCameraFit(route, 200.0)),
        )
    }

    @Test
    fun `a tiny bounds change refits because the comparison is exact`() {
        val nudged = route.copy(neLng = route.neLng + 1e-12)
        assertEquals(fitWithBottom(40.0), decide(bounds = nudged, lastApplied = AppliedCameraFit(route, 0.0)))
    }

    @Test
    fun `negative and positive zero are the same bounds, as Swift's == reads them`() {
        val zeroed = MapCameraBounds(swLat = 0.0, swLng = 0.0, neLat = 1.0, neLng = 1.0)
        val negativeZeroed = zeroed.copy(swLat = -0.0)
        assertEquals(
            CameraFitDecision.SkipUnchanged,
            decide(bounds = negativeZeroed, lastApplied = AppliedCameraFit(zeroed, 0.0)),
        )
    }

    @Test
    fun `a zero-height view has no room`() {
        assertEquals(CameraFitDecision.SkipNoRoom, decide(heightDp = 0.0))
    }

    @Test
    fun `a zero-width view has no room`() {
        assertEquals(CameraFitDecision.SkipNoRoom, decide(widthDp = 0.0))
    }

    @Test
    fun `a short view keeps the base padding and ignores the inset`() {
        // H < 240: headroom = max(0, 200 - 240) = 0.
        assertEquals(fitWithBottom(40.0), decide(insetDp = 120.0, heightDp = 200.0))
    }

    @Test
    fun `a view exactly 80 tall has no room`() {
        assertEquals(CameraFitDecision.SkipNoRoom, decide(heightDp = 80.0))
    }

    @Test
    fun `a view just over 80 tall fits`() {
        assertEquals(fitWithBottom(40.0), decide(heightDp = 80.5))
    }

    @Test
    fun `a view exactly 60 wide has no room`() {
        assertEquals(CameraFitDecision.SkipNoRoom, decide(widthDp = 60.0))
    }

    @Test
    fun `an unchanged fit is reported as unchanged even while the view has no room`() {
        assertEquals(
            CameraFitDecision.SkipUnchanged,
            decide(heightDp = 0.0, lastApplied = AppliedCameraFit(route, 0.0)),
        )
    }

    @Test
    fun `the default fit ease matches the iOS cameraDuration default`() {
        // PilgrimMapView.swift:46@7c200bf — `var cameraDuration: TimeInterval = 0.4`.
        assertEquals(400L, DEFAULT_CAMERA_FIT_EASE_MS)
    }
}
