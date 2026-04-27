// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * Pure state machine for a single walk. Given (state, action), returns the
 * next state plus a single side-effect the caller should execute
 * (typically: persist a location sample, persist an event, or finalize the
 * walk in Room). Invalid transitions are no-ops — the reducer returns the
 * same state with `WalkEffect.None` rather than throwing.
 */
object WalkReducer {
    fun reduce(state: WalkState, action: WalkAction): Pair<WalkState, WalkEffect> =
        when (state) {
            WalkState.Idle -> reduceIdle(action)
            is WalkState.Active -> reduceActive(state, action)
            is WalkState.Paused -> reducePaused(state, action)
            is WalkState.Meditating -> reduceMeditating(state, action)
            is WalkState.Finished -> reduceFinished(state, action)
        }

    private fun reduceIdle(action: WalkAction): Pair<WalkState, WalkEffect> =
        when (action) {
            is WalkAction.Start -> startFresh(action) to WalkEffect.None
            else -> WalkState.Idle to WalkEffect.None
        }

    /**
     * Finished is a resettable terminal: a fresh Start action transitions
     * directly to a new Active walk. Everything else is a no-op so stale
     * location samples or pause events from the previous walk cannot bleed
     * into the new one.
     */
    private fun reduceFinished(
        state: WalkState.Finished,
        action: WalkAction,
    ): Pair<WalkState, WalkEffect> =
        when (action) {
            is WalkAction.Start -> startFresh(action) to WalkEffect.None
            else -> state to WalkEffect.None
        }

    private fun startFresh(action: WalkAction.Start): WalkState.Active =
        WalkState.Active(WalkAccumulator(walkId = action.walkId, startedAt = action.at))

    private fun reduceActive(
        state: WalkState.Active,
        action: WalkAction,
    ): Pair<WalkState, WalkEffect> =
        when (action) {
            is WalkAction.LocationSampled -> {
                val delta = state.walk.lastLocation
                    ?.let { haversineMeters(it, action.point) } ?: 0.0
                val next = state.walk.copy(
                    lastLocation = action.point,
                    distanceMeters = state.walk.distanceMeters + delta,
                )
                WalkState.Active(next) to WalkEffect.PersistLocation(
                    walkId = state.walk.walkId,
                    point = action.point,
                )
            }
            is WalkAction.Pause ->
                WalkState.Paused(state.walk, pausedAt = action.at) to WalkEffect.PersistEvent(
                    walkId = state.walk.walkId,
                    eventType = WalkEventType.PAUSED,
                    timestamp = action.at,
                )
            is WalkAction.MeditateStart ->
                WalkState.Meditating(state.walk, meditationStartedAt = action.at) to WalkEffect.PersistEvent(
                    walkId = state.walk.walkId,
                    eventType = WalkEventType.MEDITATION_START,
                    timestamp = action.at,
                )
            is WalkAction.Finish ->
                WalkState.Finished(state.walk, endedAt = action.at) to WalkEffect.FinalizeWalk(
                    walkId = state.walk.walkId,
                    endTimestamp = action.at,
                )
            is WalkAction.Discard ->
                WalkState.Idle to WalkEffect.PurgeWalk(walkId = state.walk.walkId)
            else -> state to WalkEffect.None
        }

    private fun reducePaused(
        state: WalkState.Paused,
        action: WalkAction,
    ): Pair<WalkState, WalkEffect> =
        when (action) {
            is WalkAction.Resume -> {
                val pausedForMillis = (action.at - state.pausedAt).coerceAtLeast(0)
                val next = state.walk.copy(
                    totalPausedMillis = state.walk.totalPausedMillis + pausedForMillis,
                )
                WalkState.Active(next) to WalkEffect.PersistEvent(
                    walkId = state.walk.walkId,
                    eventType = WalkEventType.RESUMED,
                    timestamp = action.at,
                )
            }
            is WalkAction.Finish -> {
                val pausedForMillis = (action.at - state.pausedAt).coerceAtLeast(0)
                val finalized = state.walk.copy(
                    totalPausedMillis = state.walk.totalPausedMillis + pausedForMillis,
                )
                WalkState.Finished(finalized, endedAt = action.at) to WalkEffect.FinalizeWalk(
                    walkId = state.walk.walkId,
                    endTimestamp = action.at,
                )
            }
            is WalkAction.Discard ->
                WalkState.Idle to WalkEffect.PurgeWalk(walkId = state.walk.walkId)
            else -> state to WalkEffect.None
        }

    private fun reduceMeditating(
        state: WalkState.Meditating,
        action: WalkAction,
    ): Pair<WalkState, WalkEffect> =
        when (action) {
            is WalkAction.MeditateEnd -> {
                val meditatedMillis = (action.at - state.meditationStartedAt).coerceAtLeast(0)
                val next = state.walk.copy(
                    totalMeditatedMillis = state.walk.totalMeditatedMillis + meditatedMillis,
                )
                WalkState.Active(next) to WalkEffect.PersistEvent(
                    walkId = state.walk.walkId,
                    eventType = WalkEventType.MEDITATION_END,
                    timestamp = action.at,
                )
            }
            is WalkAction.Finish -> {
                val meditatedMillis = (action.at - state.meditationStartedAt).coerceAtLeast(0)
                val finalized = state.walk.copy(
                    totalMeditatedMillis = state.walk.totalMeditatedMillis + meditatedMillis,
                )
                WalkState.Finished(finalized, endedAt = action.at) to WalkEffect.FinalizeWalk(
                    walkId = state.walk.walkId,
                    endTimestamp = action.at,
                )
            }
            is WalkAction.Discard ->
                WalkState.Idle to WalkEffect.PurgeWalk(walkId = state.walk.walkId)
            else -> state to WalkEffect.None
        }
}
