// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Geographic coordinate pair used by Stage 13-XZ prompt-context types.
 *
 * The project's existing [org.walktalkmeditate.pilgrim.domain.LocationPoint]
 * carries timestamp + accuracy + speed alongside lat/lon, which is heavier
 * than the prompt-context surface needs (waypoints, geocoder inputs,
 * photo coordinates). [LatLng] is the bare-minimum tuple shared across
 * `WaypointContext`, `RecordingContext`, `PhotoContextEntry`, and
 * `PromptGeocoder` for Stage 13-XZ. Mirrors iOS `CLLocationCoordinate2D`
 * usage in the prompt pipeline.
 */
@Serializable
@Immutable
data class LatLng(
    val latitude: Double,
    val longitude: Double,
)
