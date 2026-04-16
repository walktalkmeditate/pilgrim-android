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
)
