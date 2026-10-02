// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.service.WalkTrackingService.Companion.decideFreshStart
import org.walktalkmeditate.pilgrim.service.WalkTrackingService.Companion.decideHonorSessionAction
import org.walktalkmeditate.pilgrim.service.WalkTrackingService.Companion.decideStartAction
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest
import org.walktalkmeditate.pilgrim.walk.honor.HonorSession

/** What one ACTION_START carries, read once. With the release flag off its Honor extras are never read. */
internal data class TrackerStartExtras(
    val isFreshStart: Boolean,
    val request: WalkStartRequest,
    /** The walker's units at Start, which the Honor glance keeps for the walk; null on a start that carries none. */
    val honorGlanceUnits: UnitSystem?,
)

/**
 * The location job's opening, before the first fix: the restore,
 * [WalkTrackingService.decideStartAction], the walk-uuid replay guard, then
 * the Honor session. Kept out of the service so the redelivery scenarios
 * run against a real controller and Room without Hilt.
 *
 * Room outranks a redelivered start: an unfinished walk already under the
 * start's uuid is adopted, and one that finished (or survives only in its
 * Honor marker) stops the service. Only a new uuid inserts a walk.
 */
internal class WalkTrackingStarter(
    private val controller: WalkController,
    private val repository: WalkRepository,
    /** Null with the release flag off, so nothing Honor runs. */
    private val honorSession: HonorSession?,
    private val lastKnownFix: suspend () -> LocationPoint?,
) {

    /** @return false when no walk is left to track and the service should stop. */
    suspend fun resolve(extras: TrackerStartExtras): Boolean {
        val currentState = controller.state.value
        val restored = currentState is WalkState.Idle && restoreAdopted("revival")
        return when (decideStartAction(currentState, extras.isFreshStart, hasRestoredWalk = restored)) {
            WalkTrackingService.StartAction.StartFresh -> startFresh(extras.request)
            WalkTrackingService.StartAction.AdoptRestored,
            WalkTrackingService.StartAction.IgnoreInProgress,
            -> true
            WalkTrackingService.StartAction.StopNoWalk -> {
                Log.w(TAG, "ACTION_START with no actionable walk (state=${currentState::class.simpleName}, fresh=${extras.isFreshStart})")
                false
            }
        }
    }

    /**
     * After [resolve]: starts or revives the Honor session of the walk now in
     * progress, or ends the one a cached process still holds.
     *
     * @return the session to tap with each fix; null with the release flag off.
     */
    suspend fun startHonor(scope: CoroutineScope): HonorSession? {
        val session = honorSession ?: return null
        // The walk records whatever becomes of its Way: a failure here costs
        // the Honor session, never the location job that follows.
        try {
            when (val action = decideHonorSessionAction(controller.state.value)) {
                WalkTrackingService.HonorSessionAction.Stop -> session.stop()
                is WalkTrackingService.HonorSessionAction.Start -> {
                    // A fresh walk has no fix yet; iOS hands its engine the last
                    // pre-Start fix (spec D §3.3). A revival ignores this.
                    val fix = controller.state.value.inProgressWalk()?.lastLocation ?: lastKnownFix()
                    val result = session.start(scope, action.walkId, controller.state, fix)
                    Log.i(TAG, "Honor session for walk ${action.walkId}: ${result::class.simpleName}")
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.e(TAG, "Honor session did not start (${e::class.simpleName}); the walk records on")
        }
        return session
    }

    private suspend fun startFresh(request: WalkStartRequest): Boolean {
        val uuid = request.walkUuid
        val existing = uuid?.let { repository.walkByUuid(it) }
        val hasMarker = uuid != null && existing == null && repository.hasHonorMarker(uuid)
        return when (decideFreshStart(uuid, existing, hasMarker)) {
            WalkTrackingService.FreshStartAction.Insert -> {
                // A refusal from a fast race leaves another walk in progress,
                // which the pipeline tracks. One that leaves none (an Honor
                // start whose Way no longer loads) has nothing to track.
                try {
                    controller.startWalk(request)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "fresh ACTION_START rejected: ${e.message}")
                }
                controller.state.value.inProgressWalk() != null
            }
            WalkTrackingService.FreshStartAction.AdoptExisting -> {
                restoreAdopted("a redelivered start")
                controller.state.value.inProgressWalk() != null
            }
            WalkTrackingService.FreshStartAction.StopFinished -> {
                Log.i(TAG, "redelivered ACTION_START for a walk that already finished: stopping")
                false
            }
        }
    }

    /** @return true when the controller adopted an unfinished walk from Room. */
    private suspend fun restoreAdopted(reason: String): Boolean = try {
        controller.restoreActiveWalk() != null
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        Log.w(TAG, "restoreActiveWalk on $reason failed", t)
        false
    }

    private companion object {
        const val TAG = "WalkTrackingStarter"
    }
}

/**
 * The walk's accuracy-gated fixes, each handed to the Honor engine before
 * the reducer records it (plan U17; spec B §1.1: Honor reads the same
 * filtered stream as the route).
 */
internal suspend fun collectWalkFixes(
    fixes: Flow<LocationPoint>,
    honor: HonorSession?,
    record: suspend (LocationPoint) -> Unit,
) {
    fixes.collect { point ->
        honor?.onFix(point)
        record(point)
    }
}

internal fun WalkState.inProgressWalk(): WalkAccumulator? = when (this) {
    is WalkState.Active -> walk
    is WalkState.Paused -> walk
    is WalkState.Meditating -> walk
    WalkState.Idle, is WalkState.Finished -> null
}
