// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

/**
 * Derives a walk's sittings from its `MEDITATION_START` /
 * `MEDITATION_END` events as MEDITATING [ActivityInterval]s, in start
 * order. `walk_events` is the single source of sittings (#223): the
 * metrics cache, `.pilgrim` export, the journey viewer, prompt context,
 * Walk Summary, and the share payload all read sittings through this
 * function, never from `activity_intervals`.
 *
 * The events are normalized first, so the result does not depend on the
 * order they arrive in (SQL returns timestamp ties in no defined order,
 * and imported activities may be unsorted or overlap):
 * - They are sorted by timestamp, with an END before a START in the same
 *   millisecond, so back-to-back sittings `[t0, t1]` and `[t1, t2]` stay
 *   two sittings.
 * - Overlapping sittings merge into one: a START while a sitting is open
 *   extends it, and it closes only when every START has met an END.
 * - A sitting's end must be strictly after its start. A START and an END
 *   in the same millisecond with nothing open are a zero-length sitting
 *   and contribute nothing; an END with nothing open is otherwise ignored.
 *
 * A sitting still open after the last event — the walk finished
 * mid-meditation, and the reducer persists no synthetic END — closes at
 * [closeAt] (pass the walk's `endTimestamp`) when `closeAt` is after its
 * start. With a null [closeAt] (a walk still in progress) it is dropped.
 *
 * [walkId] stamps every returned row's foreign key.
 */
fun deriveActivityIntervals(
    events: List<WalkEvent>,
    walkId: Long,
    closeAt: Long?,
): List<ActivityInterval> {
    val chronological = events.sortedWith(
        compareBy<WalkEvent> { it.timestamp }
            .thenBy { it.eventType != WalkEventType.MEDITATION_END },
    )
    val sittings = mutableListOf<ActivityInterval>()
    var openStarts = 0
    var openedAt = 0L
    // ENDs that found nothing open, all in the millisecond [unmatchedEndsAt].
    // Each one cancels a START of that same millisecond (a zero-length sitting).
    var unmatchedEndsAt: Long? = null
    var unmatchedEnds = 0

    fun addSitting(start: Long, end: Long) {
        if (end > start) {
            sittings += ActivityInterval(
                walkId = walkId,
                startTimestamp = start,
                endTimestamp = end,
                activityType = ActivityType.MEDITATING,
            )
        }
    }

    for (event in chronological) {
        when (event.eventType) {
            WalkEventType.MEDITATION_START -> {
                if (unmatchedEnds > 0 && unmatchedEndsAt == event.timestamp) {
                    unmatchedEnds--
                } else {
                    if (openStarts == 0) openedAt = event.timestamp
                    openStarts++
                }
            }
            WalkEventType.MEDITATION_END -> {
                if (openStarts == 0) {
                    if (unmatchedEndsAt != event.timestamp) {
                        unmatchedEndsAt = event.timestamp
                        unmatchedEnds = 0
                    }
                    unmatchedEnds++
                } else {
                    openStarts--
                    if (openStarts == 0) addSitting(openedAt, event.timestamp)
                }
            }
            WalkEventType.PAUSED,
            WalkEventType.RESUMED,
            WalkEventType.WAYPOINT_MARKED,
            WalkEventType.SEEK_MODE,
            WalkEventType.SEEK_ARRIVAL,
            WalkEventType.HONOR_MODE,
            WalkEventType.HONOR_ARRIVAL,
            WalkEventType.UNKNOWN,
            -> Unit
        }
    }
    if (openStarts > 0 && closeAt != null) addSitting(openedAt, closeAt)
    return sittings
}
