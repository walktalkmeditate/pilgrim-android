// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import android.content.res.Resources
import org.walktalkmeditate.pilgrim.R

/**
 * The persistence vocabulary for honor walks (iOS
 * `HonorPersistence.swift@7c200bf`), shaped like
 * [org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence]: a
 * [org.walktalkmeditate.pilgrim.domain.WalkEventType.HONOR_MODE] event at
 * recording start, and on reaching the end of the Way a
 * [org.walktalkmeditate.pilgrim.domain.WalkEventType.HONOR_ARRIVAL] event
 * plus a waypoint carrying [ARRIVAL_WAYPOINT_ICON].
 */
object HonorPersistence {

    /**
     * Reserved icon key for the arrival waypoint — the iOS SF Symbol name,
     * stored verbatim so `.pilgrim` archives round-trip honor arrivals
     * across platforms. Must never collide with `WaypointMarkingSheet`'s
     * presets, the custom-note "mappin", or
     * [org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence.ARRIVAL_WAYPOINT_ICON].
     */
    const val ARRIVAL_WAYPOINT_ICON = "signpost.right.fill"

    /** Matches by icon only, like iOS `isArrivalWaypoint(_: WaypointInterface)`. */
    fun isArrivalWaypoint(icon: String?): Boolean = icon == ARRIVAL_WAYPOINT_ICON

    /**
     * "Walked their way: <title>", for an own walk too: iOS writes the same
     * words when the Way is your own earlier walk (parity spec A §17).
     */
    fun arrivalWaypointLabel(resources: Resources, wayTitle: String): String =
        resources.getString(R.string.honor_arrival_label, wayTitle)
}
