// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds

/**
 * The applier's bookkeeping (rules F5/F6 in the Stage 21-0 port spec)
 * against a fake surface. iOS records the fit only once `camera(for:)`
 * returned and `ease` was called (`PilgrimMapView.swift:277-294@7c200bf`);
 * a throw leaves the record alone so a later pass tries again. That
 * ordering is the iOS #89 fix for the Honor overview's globe. Android's
 * throw is a null camera, and its camera can arrive after a newer pass.
 */
class CameraFitApplierTest {

    /** A camera the fake hands back: the bounds it was computed for. */
    private data class FakeCamera(val bounds: MapCameraBounds)

    private class FakeSurface : CameraFitSurface<FakeCamera> {
        var answerImmediately = true
        var answerWithCamera = true
        private val pending = mutableListOf<() -> Unit>()
        val computedPaddings = mutableListOf<CameraFitPaddingDp>()
        val eases = mutableListOf<Pair<FakeCamera, Long>>()

        override fun computeCamera(
            bounds: MapCameraBounds,
            padding: CameraFitPaddingDp,
            onResult: (FakeCamera?) -> Unit,
        ) {
            computedPaddings += padding
            if (answerImmediately) {
                onResult(if (answerWithCamera) FakeCamera(bounds) else null)
            } else {
                pending += { onResult(FakeCamera(bounds)) }
            }
        }

        override fun ease(camera: FakeCamera, durationMs: Long) {
            eases += camera to durationMs
        }

        /** Delivers the [index]th deferred answer, in request order. */
        fun land(index: Int) = pending[index]()
    }

    private val route = MapCameraBounds(swLat = 1.0, swLng = 2.0, neLat = 3.0, neLng = 4.0)
    private val segment = MapCameraBounds(swLat = 1.5, swLng = 2.5, neLat = 2.0, neLng = 3.0)

    private val surface = FakeSurface()
    private val applier = CameraFitApplier(surface)

    private fun apply(
        bounds: MapCameraBounds = route,
        insetDp: Double = 0.0,
        widthDp: Double = 400.0,
        heightDp: Double = 320.0,
        durationMs: Long = 2_500L,
    ) = applier.apply(bounds, insetDp, widthDp, heightDp, durationMs)

    @Test
    fun `a fit eases to the computed camera over the given duration`() {
        apply(durationMs = 2_500L)
        assertEquals(listOf(FakeCamera(route) to 2_500L), surface.eases)
    }

    @Test
    fun `a fit is recorded once the ease is issued, without waiting for it to end`() {
        apply(insetDp = 12.0)
        assertEquals(AppliedCameraFit(route, 12.0), applier.lastApplied)
    }

    @Test
    fun `the surface is asked for the decision's clamped padding`() {
        // headroom = 320 - 240 = 80, so a 200 inset clamps to 80.
        apply(insetDp = 200.0, heightDp = 320.0)
        assertEquals(
            listOf(CameraFitPaddingDp(top = 40.0, left = 30.0, bottom = 120.0, right = 30.0)),
            surface.computedPaddings,
        )
    }

    @Test
    fun `an unchanged second pass neither computes nor eases again`() {
        apply()
        apply()
        assertEquals(1, surface.eases.size)
    }

    @Test
    fun `a null camera leaves the record untouched`() {
        surface.answerWithCamera = false
        apply()
        assertNull(applier.lastApplied)
    }

    @Test
    fun `a null camera is retried by the next pass with the same inputs`() {
        // The iOS #89 regression pin: set before the attempt, one failure
        // would read as applied for good and strand the map.
        surface.answerWithCamera = false
        apply()
        surface.answerWithCamera = true
        apply()
        assertEquals(listOf(FakeCamera(route) to 2_500L), surface.eases)
    }

    @Test
    fun `a no-room pass records nothing, and the next pass with room fits`() {
        apply(heightDp = 0.0)
        assertNull(applier.lastApplied)
        apply(heightDp = 320.0)
        assertEquals(AppliedCameraFit(route, 0.0), applier.lastApplied)
    }

    @Test
    fun `a camera from an older pass is ignored once a newer pass fit`() {
        surface.answerImmediately = false
        apply(bounds = route)
        apply(bounds = segment)
        surface.land(1)
        surface.land(0)
        assertEquals(listOf(FakeCamera(segment) to 2_500L), surface.eases)
    }

    @Test
    fun `a stale camera never overwrites the record of the newer fit`() {
        surface.answerImmediately = false
        apply(bounds = route)
        apply(bounds = segment)
        surface.land(1)
        surface.land(0)
        assertEquals(AppliedCameraFit(segment, 0.0), applier.lastApplied)
    }

    @Test
    fun `a camera landing late for an older pass is ignored even when it lands first`() {
        surface.answerImmediately = false
        apply(bounds = route)
        apply(bounds = segment)
        surface.land(0)
        assertEquals(emptyList<Pair<FakeCamera, Long>>(), surface.eases)
    }

    @Test
    fun `returning to the applied bounds drops a fit still in flight`() {
        // A segment tap and its deselect before the map has a size: the
        // deselect wants the route that is already applied, so the late
        // segment camera must not land.
        apply(bounds = route)
        surface.answerImmediately = false
        apply(bounds = segment)
        apply(bounds = route)
        surface.land(0)
        assertEquals(listOf(FakeCamera(route) to 2_500L), surface.eases)
    }

    @Test
    fun `a no-room pass drops a fit still in flight`() {
        surface.answerImmediately = false
        apply(heightDp = 320.0)
        apply(heightDp = 0.0)
        surface.land(0)
        assertNull(applier.lastApplied)
    }

    @Test
    fun `cancelling drops a fit still in flight`() {
        surface.answerImmediately = false
        apply()
        applier.cancelPending()
        surface.land(0)
        assertEquals(emptyList<Pair<FakeCamera, Long>>(), surface.eases)
    }
}
