// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * Mutable-by-copy walk accumulator shared across Active / Paused / Meditating
 * / Finished states. Totals count the active-walking time contributions and
 * deductions (paused, meditating) that have *already completed* — the
 * currently-ongoing pause or meditation is not yet folded in.
 */
data class WalkAccumulator(
    val walkId: Long,
    val startedAt: Long,
    val lastLocation: LocationPoint? = null,
    val distanceMeters: Double = 0.0,
    val totalPausedMillis: Long = 0,
    val totalMeditatedMillis: Long = 0,
    /**
     * The contemplative posture this walk was started with (iOS
     * `ActiveWalkViewModel.mode`). Rides the accumulator so every
     * in-progress and Finished state exposes it — the service
     * notification (U10), the seek orchestrator (U9), and the options
     * sheet all read it from [WalkState]. Walks stay ordinary in Room;
     * restore paths re-derive this from the SEEK_MODE walk event.
     */
    val mode: WalkMode = WalkMode.Wander,
)
