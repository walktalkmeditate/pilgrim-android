// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Single source of truth for the in-memory walk state and the bridge
 * between the pure reducer and the (impure) persistence layer.
 *
 * Two implementations:
 *  - [WalkControllerImpl] — full in-memory state machine + sensor pipeline
 *    + Room writes. Lives in the `:tracker` process (and in the UI
 *    process while the manifest split is not yet in effect).
 *  - [UiWalkController] — read-only view that derives [state] from Room
 *    flows and forwards every mutation to [WalkControllerImpl] (today
 *    in-process; once the manifest split lands, via an action intent
 *    delivered to [WalkTrackingService] in `:tracker`).
 *
 * Selected at runtime by the process-aware Hilt provider in
 * [org.walktalkmeditate.pilgrim.di.WalkModule].
 *
 * Services and ViewModels observe [state] and call the suspend APIs to
 * transition. Implementations serialize dispatch internally so concurrent
 * callers (e.g. a user pressing pause at the same instant a location
 * sample arrives) reduce one action at a time.
 */
interface WalkController {
    val state: StateFlow<WalkState>

    /**
     * Bell-trigger event stream. iOS dispatches bells via explicit
     * `SoundManagement.onWalkStart()` / `onWalkEnd()` /
     * `onMeditationStart()` / `onMeditationEnd()` calls — not via
     * state-flow observation — so the restore path (which writes
     * directly to `_state`) doesn't fire stray bells. Mirroring that
     * here: every user-initiated transition emits the corresponding
     * trigger. Observers subscribe to this flow instead of the state
     * flow when they need user-intent semantics.
     */
    val bellTriggers: SharedFlow<BellTrigger>

    /**
     * iOS parity `ActiveWalkViewModel.steps` — live step count for the
     * in-progress walk. Null until the first sensor event, or
     * permanently when the sensor / permission is unavailable.
     */
    val liveSteps: StateFlow<Int?>

    /**
     * @param mode the walk's contemplative posture (iOS
     *   `MainCoordinator.startWalk(mode:)@c1745e8`). Rides the intent
     *   channel to the `:tracker` controller, lands on
     *   [org.walktalkmeditate.pilgrim.domain.WalkAccumulator.mode], and —
     *   for [WalkMode.Seek] — makes the reducer persist one SEEK_MODE
     *   event at start.
     */
    suspend fun startWalk(intention: String? = null, mode: WalkMode = WalkMode.Wander): Walk
    suspend fun pauseWalk()
    suspend fun resumeWalk()
    suspend fun startMeditation()

    /**
     * @param endMillis explicit end timestamp; pass `null` to let the
     *   implementation use `clock.now()`. iOS parity
     *   `MeditationView.swift:609-615@db4196e` — the user's Done-tap
     *   is the conceptual end of the meditation; the 6.5s closing
     *   ceremony that plays afterwards should NOT inflate the recorded
     *   interval. Pass `null` for paths that finalize immediately
     *   (notification taps, finishWalk teardown); pass the captured
     *   Done-tap millis for the ceremony path.
     */
    suspend fun endMeditation(endMillis: Long? = null)

    suspend fun finishWalk()
    suspend fun discardWalk()
    suspend fun recordLocation(point: LocationPoint)
    suspend fun setIntention(text: String)
    suspend fun recordWaypoint(label: String? = null, icon: String? = null)

    /**
     * On cold launch, finalize any walk row whose `end_timestamp IS NULL`
     * — these are walks the OS killed (swipe-from-recents → process death,
     * force-stop, low-memory kill) without going through the normal
     * `finishWalk` path. Returns the id of the most-recent recovered walk
     * so the Path tab can show a transient banner; null when nothing
     * needed recovery.
     */
    suspend fun recoverStaleWalks(): Long?

    /**
     * After a process kill mid-walk, the Walk row is still in Room with
     * `end_timestamp IS NULL` but the in-memory state is [WalkState.Idle].
     * This rebuilds in-memory state from persisted facts. Returns the
     * restored [Walk] or null if there was no unfinished walk to resume.
     */
    suspend fun restoreActiveWalk(): Walk?

    companion object {
        /**
         * Single source of truth for the intention character cap. UI
         * surfaces (IntentionSettingDialog) reference this so the
         * controller-side sanitize and the UI-side `take(N)` can never
         * silently desync.
         */
        const val MAX_INTENTION_CHARS = 140
    }
}
