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

    /**
     * Replies are filed by the `n` of a `voice-n` id. A stage has no
     * voices, so the walker's reply to its closing line is filed under an
     * index no `voice-n` can produce (`HonorPersistence.swift:12-17@7c200bf`,
     * pilgrimage-stage spec P3 §13.1).
     */
    const val STAGE_REFLECTION_ORIGIN = -1
    const val STAGE_REFLECTION_MOMENT_ID = "stage-reflection"

    /**
     * The moment a reply to the stage's closing line answers: not a moment
     * of the Way, but the stage's end place in a moment's shape, so the
     * reply path takes it unchanged (`HonorPersistence.swift:19-24@7c200bf`).
     * Only its id is read; its place is where the reply is spoken, never
     * when it is filed (P3 correction 7).
     */
    fun stageReflectionMoment(stage: WayStage): WayMoment = WayMoment(
        id = STAGE_REFLECTION_MOMENT_ID,
        frac = 1.0,
        at = stage.end.at,
        kind = WayMomentKind.Waypoint(label = stage.end.name, icon = ARRIVAL_WAYPOINT_ICON),
    )
}
