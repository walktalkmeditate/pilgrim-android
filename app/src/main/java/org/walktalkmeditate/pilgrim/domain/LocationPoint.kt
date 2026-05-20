// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import androidx.compose.runtime.Immutable

@Immutable
data class LocationPoint(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    /**
     * GPS-derived altitude in meters above the WGS84 ellipsoid, when the
     * fix carries one (`Location.hasAltitude()`). Plumbed into
     * `RouteDataSample.altitudeMeters` and an `AltitudeSample` row so
     * `WalkSummaryViewModel.altitudeSamples` resolves a non-zero
     * `ascendMeters` and the Elevation stat + ElevationProfile render
     * on the post-walk summary.
     */
    val altitudeMeters: Double? = null,
    val verticalAccuracyMeters: Float? = null,
)
