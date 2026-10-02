// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_CAMERA_EASE_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_ZOOM_PLANT_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.RevealPhase

/**
 * Contract tests for [PilgrimMap]'s reveal camera: what each phase fits,
 * how long each ease takes, and when the map takes gestures. The Mapbox
 * `easeTo` itself is exercised on-device (no Robolectric shadow for the
 * camera API).
 */
@RunWith(JUnit4::class)
class PilgrimMapRevealTest {

    private val route = MapCameraBounds(swLat = 1.0, swLng = 2.0, neLat = 3.0, neLng = 4.0)
    private val segment = MapCameraBounds(swLat = 1.5, swLng = 2.5, neLat = 2.0, neLng = 3.0)

    @Test
    fun revealZoomPlantMs_matchesIosCameraDuration() {
        // iOS cameraDuration = 0.1 → 100ms.
        assertEquals(100L, REVEAL_ZOOM_PLANT_MS)
    }

    @Test
    fun revealCameraEaseMs_matchesIosCameraDuration() {
        // WalkSummaryView.swift:432@7c200bf — `cameraDuration = 2.5`.
        assertEquals(2_500L, REVEAL_CAMERA_EASE_MS)
    }

    @Test
    fun hiddenPhase_fitsNothing() {
        assertNull(cameraFitTarget(false, RevealPhase.Hidden, null, route))
    }

    @Test
    fun zoomedPhase_fitsNothing_theCentrePlantOwnsTheCamera() {
        assertNull(cameraFitTarget(false, RevealPhase.Zoomed, null, route))
    }

    @Test
    fun revealedPhase_fitsTheRoute() {
        assertEquals(route, cameraFitTarget(false, RevealPhase.Revealed, null, route))
    }

    @Test
    fun revealedPhase_withATappedSegment_fitsTheSegment() {
        assertEquals(segment, cameraFitTarget(false, RevealPhase.Revealed, segment, route))
    }

    @Test
    fun followMap_neverFits() {
        assertNull(cameraFitTarget(true, null, null, route))
    }

    @Test
    fun mapWithoutAReveal_fitsItsRoute() {
        assertEquals(route, cameraFitTarget(false, null, segment, route))
    }

    @Test
    fun summaryFits_includingSegmentTaps_easeOverTheRevealDuration() {
        // pilgrim-ios #95: iOS never resets `cameraDuration` after the reveal.
        assertEquals(REVEAL_CAMERA_EASE_MS, cameraFitEaseMs(RevealPhase.Revealed))
    }

    @Test
    fun fitsWithoutAReveal_easeOverTheIosDefault() {
        assertEquals(400L, cameraFitEaseMs(null))
    }

    @Test
    fun summaryMap_takesNoGesturesWhileHidden() {
        assertFalse(isMapInteractive(RevealPhase.Hidden))
    }

    @Test
    fun summaryMap_takesNoGesturesWhileZoomed() {
        assertFalse(isMapInteractive(RevealPhase.Zoomed))
    }

    @Test
    fun summaryMap_takesGesturesOnceRevealed() {
        assertTrue(isMapInteractive(RevealPhase.Revealed))
    }

    @Test
    fun liveWalkMap_takesGestures() {
        // ActiveWalkView+Map.swift@7c200bf leaves `isInteractive` at true.
        assertTrue(isMapInteractive(null))
    }
}
