// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec

/**
 * Plain-JUnit coverage for [EtegamiBitmapRenderer.indexAtTimestamp].
 *
 * Intentionally NOT a Robolectric test — the function is pure
 * (operates on `EtegamiSpec` + a `List<SmoothedSegment>` and a `Long`)
 * so dragging in `RobolectricTestRunner` would only grow the
 * Robolectric-fork surface in CI, which previously perturbed Gradle's
 * test-fork slicing enough to surface a latent coroutine leak in
 * `WalkViewModelTest` (Stage 7-C merge CI incident, 2026-04-24).
 */
class EtegamiIndexAtTimestampTest {

    private val emptySeal = SealSpec(
        uuid = "seal",
        startMillis = 0L,
        distanceMeters = 0.0,
        durationSeconds = 0.0,
        displayDistance = "0.0",
        unitLabel = "km",
        ink = Color.Black,
    )

    private fun spec(routePoints: List<LocationPoint>) = EtegamiSpec(
        walkUuid = "w",
        startedAtEpochMs = 0L,
        hourOfDay = 10,
        routePoints = routePoints,
        sealSpec = emptySeal,
        moonPhase = null,
        distanceMeters = 0.0,
        durationMillis = 0L,
        elevationGainMeters = 0.0,
        topText = null,
        activityMarkers = emptyList(),
        units = org.walktalkmeditate.pilgrim.data.units.UnitSystem.Metric,
    )

    @Test
    fun `clamps the last route point exactly to the last smoothed index`() {
        // 5 route points ⇒ smoothed length is (5-1)*8 + 1 = 33 (indices
        // 0..32). `closestOrig` for the last-timestamp marker is 4;
        // the raw multiply yields 4*8 = 32, which lands exactly on
        // `smoothed.size - 1`. The `coerceIn` is load-bearing at
        // this boundary — a silent drift in DEFAULT_SUBDIVISIONS
        // or the Catmull-Rom output length would show up as a
        // one-off index and misplace every end-of-walk glyph.
        val route = (0..4).map {
            LocationPoint(
                timestamp = it * 1_000L,
                latitude = 45.0 + it * 0.0001,
                longitude = -70.0 + it * 0.0001,
            )
        }
        val smoothed = EtegamiRouteGeometry.smooth(route)
        val idx = EtegamiBitmapRenderer.indexAtTimestamp(spec(route), smoothed, 4_000L)
        assertEquals(smoothed.size - 1, idx)
    }

    @Test
    fun `clamps out-of-range timestamps to route endpoints`() {
        val route = (0..2).map {
            LocationPoint(
                timestamp = it * 1_000L,
                latitude = 45.0,
                longitude = -70.0,
            )
        }
        val smoothed = EtegamiRouteGeometry.smooth(route)
        val s = spec(route)
        // Below-range → first index.
        assertEquals(0, EtegamiBitmapRenderer.indexAtTimestamp(s, smoothed, -9_999L))
        // Above-range → last smoothed index.
        assertEquals(
            smoothed.size - 1,
            EtegamiBitmapRenderer.indexAtTimestamp(s, smoothed, 999_999L),
        )
    }
}
