// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.proximity

/**
 * iOS parity `ProximityEvent.swift@db4196e`. Hot stream events from
 * [ProximityDetectionService]. The UI handler MUST filter on
 * `direction == Entered` — iOS emits Exited too (to re-arm dedup)
 * but the view silently drops it.
 */
data class ProximityEvent(
    val target: ProximityTarget,
    val distanceMeters: Double,
    val direction: Direction,
) {
    enum class Direction { Entered, Exited }
}
