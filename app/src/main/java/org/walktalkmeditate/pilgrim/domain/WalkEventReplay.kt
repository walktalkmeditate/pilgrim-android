// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * Cumulative paused and meditation durations computed by replaying a
 * walk's event log. Handles *dangling* open intervals — when a user
 * taps Finish from Paused or Meditating, the reducer folds the ongoing
 * interval into the in-memory accumulator but does not persist a
 * synthetic close event. Pass `closeAt = walk.endTimestamp` for a
 * finished walk (or any known close point) to correctly account for
 * those dangling intervals; pass null while the walk is still open and
 * the caller will fold ongoing intervals from in-memory state.
 */
data class WalkEventTotals(
    val totalPausedMillis: Long,
    val totalMeditatedMillis: Long,
    val pendingPauseAt: Long? = null,
    val pendingMeditationAt: Long? = null,
)

/**
 * Replays the event list, pairing `PAUSED → RESUMED` and
 * `MEDITATION_START → MEDITATION_END` into completed intervals. Any
 * open interval at the end is either closed at [closeAt] (for a
 * finished walk) or reported back via [WalkEventTotals.pendingPauseAt]
 * / [pendingMeditationAt] (for a still-open walk — the caller — the
 * [WalkController.restoreActiveWalk] path uses these to set the
 * next [WalkState]).
 */
fun replayWalkEventTotals(
    events: List<WalkEventLike>,
    closeAt: Long? = null,
): WalkEventTotals {
    var paused = 0L
    var meditated = 0L
    var pendingPauseAt: Long? = null
    var pendingMeditationAt: Long? = null
    for (event in events) {
        when (event.type) {
            WalkEventType.PAUSED -> pendingPauseAt = event.timestamp
            WalkEventType.RESUMED -> pendingPauseAt?.let {
                paused += (event.timestamp - it).coerceAtLeast(0)
                pendingPauseAt = null
            }
            WalkEventType.MEDITATION_START -> pendingMeditationAt = event.timestamp
            WalkEventType.MEDITATION_END -> pendingMeditationAt?.let {
                meditated += (event.timestamp - it).coerceAtLeast(0)
                pendingMeditationAt = null
            }
            WalkEventType.WAYPOINT_MARKED -> Unit
        }
    }
    if (closeAt != null) {
        pendingPauseAt?.let { paused += (closeAt - it).coerceAtLeast(0) }
        pendingMeditationAt?.let { meditated += (closeAt - it).coerceAtLeast(0) }
        return WalkEventTotals(paused, meditated)
    }
    return WalkEventTotals(
        totalPausedMillis = paused,
        totalMeditatedMillis = meditated,
        pendingPauseAt = pendingPauseAt,
        pendingMeditationAt = pendingMeditationAt,
    )
}

/**
 * Minimal shape needed by [replayWalkEventTotals] — keeps the pure
 * domain function independent of the Room entity class.
 */
interface WalkEventLike {
    val timestamp: Long
    val type: WalkEventType
}
