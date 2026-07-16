// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import android.content.res.Resources
import org.walktalkmeditate.pilgrim.R

/**
 * The persistence vocabulary for seek walks (iOS `SeekPersistence.swift`).
 * A seek is marked by a [org.walktalkmeditate.pilgrim.domain.WalkEventType.SEEK_MODE]
 * event written once at recording start; each reached clearing writes a
 * [org.walktalkmeditate.pilgrim.domain.WalkEventType.SEEK_ARRIVAL] event plus
 * a waypoint carrying the reserved icon, so map rendering, summary grouping,
 * and `.pilgrim` round-trips reuse the existing waypoint machinery unchanged.
 */
object SeekPersistence {

    /**
     * Reserved icon key for arrival waypoints — the iOS SF Symbol name,
     * stored verbatim so `.pilgrim` archives round-trip seek-ness across
     * platforms. Must never collide with the user-pickable icons in
     * `WaypointMarkingSheet` (presets plus the custom-note "mappin") —
     * summary grouping tells arrivals apart by exactly this icon string.
     */
    const val ARRIVAL_WAYPOINT_ICON = "sun.haze"

    /**
     * Matches by icon only, like iOS `isArrivalWaypoint(_: WaypointInterface)`
     * — labels are user-visible defaults, never identity.
     */
    fun isArrivalWaypoint(icon: String?): Boolean = icon == ARRIVAL_WAYPOINT_ICON

    /**
     * 1-based ordinal for the NEXT arrival, counted from waypoints already
     * persisted this walk — not the engine's clearing index. After "Seek
     * anew" from inside an unrevealed clearing, the replacement clearing
     * replays the same engine index, which would duplicate labels and
     * inflate the unknowns-found count.
     */
    fun arrivalOrdinal(persistedWaypointIcons: List<String?>): Int =
        persistedWaypointIcons.count(::isArrivalWaypoint) + 1

    /** Label for the arrival waypoint of a clearing, by 1-based ordinal. */
    fun arrivalWaypointLabel(resources: Resources, clearingOrdinal: Int): String =
        when (clearingOrdinal) {
            1 -> resources.getString(R.string.seek_arrival_label_first)
            2 -> resources.getString(R.string.seek_arrival_label_second)
            3 -> resources.getString(R.string.seek_arrival_label_third)
            else -> resources.getString(R.string.seek_arrival_label_nth, clearingOrdinal)
        }
}
