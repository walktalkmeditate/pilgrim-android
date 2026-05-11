// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

/**
 * Walk Summary Photo Reliquary state machine. Strict precedence per
 * locked decision D6 in the spec:
 *
 *   ToggleOff > PermissionDenied > Loading > Populated(candidates)
 *
 * The `Populated.candidates` list may be empty; an empty-Populated
 * collapses to a height-zero leaf in the UI, distinct from Loading
 * (which renders the deferred skeleton).
 *
 * `@Immutable` annotation per Stage 4-D cascade audit — Compose can't
 * infer cross-module stability for `WalkPhoto`.
 */
@Immutable
sealed class ReliquaryState {
    data object ToggleOff : ReliquaryState()
    data object PermissionDenied : ReliquaryState()
    data object Loading : ReliquaryState()
    data class Populated(val candidates: List<WalkPhoto>) : ReliquaryState()
}

/**
 * Pure precedence resolver. Inputs:
 *  - [toggleEnabled] — `PracticePreferencesRepository.walkReliquaryEnabled`
 *  - [permissionGranted] — `ContextCompat.checkSelfPermission(READ_MEDIA_IMAGES)`
 *    (Android 14+ partial-grant is treated as full-grant per spec non-goal)
 *  - [isFetching] — VM-side fetch-in-flight flag
 *  - [photos] — current Room-observed `pinnedPhotos` list
 *
 * Tested in isolation; the composable wires the live inputs.
 */
internal fun resolveReliquaryState(
    toggleEnabled: Boolean,
    permissionGranted: Boolean,
    isFetching: Boolean,
    photos: List<WalkPhoto>,
): ReliquaryState = when {
    !toggleEnabled -> ReliquaryState.ToggleOff
    !permissionGranted -> ReliquaryState.PermissionDenied
    isFetching && photos.isEmpty() -> ReliquaryState.Loading
    else -> ReliquaryState.Populated(photos)
}
