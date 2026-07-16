// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.ui.home.scenery.WalkThreshold

/**
 * Per-walk Journal struct. Mirrors iOS `WalkSnapshot`. Built once per
 * Flow emission in `HomeViewModel.buildSnapshots` so all UI surfaces
 * (dot, expand card, scenery) read the same precomputed values.
 *
 * `@Immutable` per Stage 4-C / 13-Cel cascade lesson — has computed
 * properties; even though current fields are all stable, mark
 * explicitly so future List<>/Map<> fields don't silently regress
 * Compose stability.
 */
@Immutable
data class WalkSnapshot(
    val id: Long,
    val uuid: String,
    val startMs: Long,
    val distanceM: Double,
    val durationSec: Double,
    val averagePaceSecPerKm: Double,
    val cumulativeDistanceM: Double,
    val talkDurationSec: Long,
    val meditateDurationSec: Long,
    val favicon: String?,
    val isShared: Boolean,
    val weatherCondition: String?,
    /**
     * iOS v1.6.0 — true when the walk's UUID is in
     * [org.walktalkmeditate.pilgrim.data.pilgrim.ArchivedWalkRegistry].
     * The Home walk dot renders as a hollow fog ring (no halo / no
     * favicon / no activity arcs) and the expand card collapses to a
     * "Released" tag w/ "full record removed" footer.
     */
    val isArchived: Boolean = false,
    /**
     * True when the walk carries a `SEEK_MODE` event (iOS
     * `WalkSnapshot.isSeek`, origin R18). Drives the quick-view
     * walk-mode glyph: seek = print + dissolving trail, wander =
     * grounded pair. Populated by one bulk event fetch in
     * `HomeViewModel.buildSnapshots`, never per-walk faulting.
     */
    val isSeek: Boolean = false,
    /**
     * Seek arrivals recorded on this walk — a found place earns the
     * walk a cairn on the ink scroll (iOS `WalkSnapshot.foundPlaces`,
     * `HomeViewModel.swift:18-20@c1745e8`). Populated from the U12
     * bulk waypoint-icons read, never per-walk faulting.
     */
    val foundPlaces: Int = 0,
    /**
     * Once-ever gates — threshold walks earn a torii on the ink
     * scroll (iOS `WalkSnapshot.threshold`,
     * `HomeViewModel.swift:21-24@c1745e8`). Practice gates (first
     * walk, every tenth) stand vermilion; seeking gates (first
     * unknown, unknown milestones) stand weathered stone. Recomputed
     * from history on every snapshot build, never stored.
     */
    val threshold: WalkThreshold? = null,
) {
    /** Walk-only duration (total minus talk minus meditate, floored at 0). */
    val walkOnlyDurationSec: Long
        get() = (durationSec.toLong() - talkDurationSec - meditateDurationSec).coerceAtLeast(0L)

    val hasTalk: Boolean get() = talkDurationSec > 0L
    val hasMeditate: Boolean get() = meditateDurationSec > 0L
}
