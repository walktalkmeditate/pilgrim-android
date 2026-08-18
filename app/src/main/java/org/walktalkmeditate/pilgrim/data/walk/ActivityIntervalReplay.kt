// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

/**
 * Replays [events], pairing `MEDITATION_START -> MEDITATION_END` into
 * completed [ActivityInterval] rows. Mirrors
 * [org.walktalkmeditate.pilgrim.domain.replayWalkEventTotals]'s pairing
 * and dangling-close semantics exactly — same last-write-wins handling
 * of a repeated START, same walk-end close for a trailing unmatched
 * START — but returns the intervals themselves instead of a millis sum,
 * for callers that need real spans (map pins, route-segment
 * classification, the share payload) rather than a total.
 *
 * `activity_intervals` has no production writer today
 * ([org.walktalkmeditate.pilgrim.data.WalkRepository.recordActivityInterval]
 * has zero callers) — Walk Summary and the share modal both reconstruct
 * MEDITATING intervals at read time via this function instead of
 * reading the (always-empty) table.
 *
 * An unmatched trailing MEDITATION_START — the walk finished, or was
 * paused, mid-meditation — is closed at [closeAt] when given (pass the
 * walk's `endTimestamp` for a finished walk). A dangling START is
 * dropped when [closeAt] is null, matching `replayWalkEventTotals`'s
 * pending-total-for-a-still-open-walk contract; this function has no
 * pending-state out-channel since it returns a flat list.
 *
 * A pair (or a closed dangling start) whose resolved end does not
 * exceed its start — clock skew, or a same-millisecond START/END —
 * contributes no interval, matching `replayWalkEventTotals`'s
 * `coerceAtLeast(0)` zero-contribution rule for the same case.
 *
 * [walkId] stamps every returned row's foreign key. [events] is assumed
 * pre-sorted by timestamp — [org.walktalkmeditate.pilgrim.data.dao.WalkEventDao.getForWalk]
 * already orders `ASC`, and both call sites feed the same fetched list
 * straight into `replayWalkEventTotals`.
 */
fun deriveActivityIntervals(
    events: List<WalkEvent>,
    walkId: Long,
    closeAt: Long?,
): List<ActivityInterval> {
    val intervals = mutableListOf<ActivityInterval>()
    var pendingStart: Long? = null
    for (event in events) {
        when (event.eventType) {
            WalkEventType.MEDITATION_START -> pendingStart = event.timestamp
            WalkEventType.MEDITATION_END -> {
                val start = pendingStart
                if (start != null && event.timestamp > start) {
                    intervals += ActivityInterval(
                        walkId = walkId,
                        startTimestamp = start,
                        endTimestamp = event.timestamp,
                        activityType = ActivityType.MEDITATING,
                    )
                }
                pendingStart = null
            }
            WalkEventType.PAUSED,
            WalkEventType.RESUMED,
            WalkEventType.WAYPOINT_MARKED,
            WalkEventType.SEEK_MODE,
            WalkEventType.SEEK_ARRIVAL,
            WalkEventType.UNKNOWN,
            -> Unit
        }
    }
    val start = pendingStart
    if (start != null && closeAt != null && closeAt > start) {
        intervals += ActivityInterval(
            walkId = walkId,
            startTimestamp = start,
            endTimestamp = closeAt,
            activityType = ActivityType.MEDITATING,
        )
    }
    return intervals
}
