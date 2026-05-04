// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable

/**
 * Per-walk photo entry: pairs a [PhotoContext] (analysis result, cached
 * by URI) with the walk-relative metadata needed to render the prompt
 * section — order in the sequence, distance into the walk at the moment
 * the photo was taken, the wall-clock timestamp, and the closest GPS
 * coordinate (null when no route samples bracket the photo's timestamp).
 *
 * Mirrors the iOS prompt-builder tuple. Coordinate uses the
 * Stage-13-XZ-local [LatLng] rather than the project's
 * `LocationPoint` because the prompt surface only needs lat/lon — speed,
 * accuracy, timestamp would be dead weight here.
 */
@Immutable
data class PhotoContextEntry(
    val index: Int,
    val distanceIntoWalkMeters: Double,
    val time: Long,
    val coordinate: LatLng?,
    val context: PhotoContext,
)
