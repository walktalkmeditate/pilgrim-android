// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * Derived statistics for a walk, given a state snapshot and a wall-clock
 * reference. All times are milliseconds; distance is meters; pace is
 * seconds per kilometer.
 */
object WalkStats {

    fun totalElapsedMillis(state: WalkState, now: Long): Long = when (state) {
        WalkState.Idle -> 0L
        is WalkState.Active -> (now - state.walk.startedAt).coerceAtLeast(0)
        is WalkState.Paused -> (now - state.walk.startedAt).coerceAtLeast(0)
        is WalkState.Meditating -> (now - state.walk.startedAt).coerceAtLeast(0)
        is WalkState.Finished -> (state.endedAt - state.walk.startedAt).coerceAtLeast(0)
    }

    /**
     * Time spent actively walking — total wall-clock time minus cumulative
     * pauses and meditations, including the ongoing one.
     */
    fun activeWalkingMillis(state: WalkState, now: Long): Long {
        val accum = accumulatorOrNull(state) ?: return 0L
        val deductionsSoFar = accum.totalPausedMillis + accum.totalMeditatedMillis
        val ongoingDeduction = when (state) {
            is WalkState.Paused -> (now - state.pausedAt).coerceAtLeast(0)
            is WalkState.Meditating -> (now - state.meditationStartedAt).coerceAtLeast(0)
            else -> 0L
        }
        val wallElapsed = when (state) {
            is WalkState.Finished -> state.endedAt - accum.startedAt
            else -> now - accum.startedAt
        }.coerceAtLeast(0)
        return (wallElapsed - deductionsSoFar - ongoingDeduction).coerceAtLeast(0)
    }

    fun distanceMeters(state: WalkState): Double = accumulatorOrNull(state)?.distanceMeters ?: 0.0

    /**
     * Average pace over the active walking time, in seconds per kilometer.
     * Returns null when distance or duration is too small to be meaningful.
     */
    fun averagePaceSecondsPerKm(state: WalkState, now: Long): Double? {
        val distanceKm = distanceMeters(state) / 1000.0
        if (distanceKm < 0.01) return null
        val activeSec = activeWalkingMillis(state, now) / 1000.0
        if (activeSec < 1.0) return null
        return activeSec / distanceKm
    }

    /**
     * Total meditation time including the in-progress meditation if any.
     * For Active/Paused/Finished, returns the accumulator's
     * totalMeditatedMillis (the reducer adds the just-completed slice on
     * MeditateEnd / Finish). For Meditating, adds (now - startedAt) on
     * top, clamped at zero so clock-skew can't produce a negative running
     * total.
     */
    fun totalMeditatedMillis(state: WalkState, now: Long): Long = when (state) {
        is WalkState.Meditating -> state.walk.totalMeditatedMillis +
            (now - state.meditationStartedAt).coerceAtLeast(0L)
        is WalkState.Active -> state.walk.totalMeditatedMillis
        is WalkState.Paused -> state.walk.totalMeditatedMillis
        is WalkState.Finished -> state.walk.totalMeditatedMillis
        WalkState.Idle -> 0L
    }

    private fun accumulatorOrNull(state: WalkState): WalkAccumulator? = when (state) {
        WalkState.Idle -> null
        is WalkState.Active -> state.walk
        is WalkState.Paused -> state.walk
        is WalkState.Meditating -> state.walk
        is WalkState.Finished -> state.walk
    }
}
