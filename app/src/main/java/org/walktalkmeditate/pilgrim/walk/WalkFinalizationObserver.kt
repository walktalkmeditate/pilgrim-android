// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.util.Log
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.TranscriptionScheduler
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepository
import org.walktalkmeditate.pilgrim.data.collective.CollectiveWalkSnapshot
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.widget.WidgetRefreshScheduler

/**
 * App-scoped observer that fires the post-finish side-effect bundle on
 * every transition to [WalkState.Finished]:
 *  - schedule transcription for any voice recordings on the walk,
 *  - cache the device hemisphere for the next walk's seasonal vignette,
 *  - contribute to the collective counter (when opt-in is on),
 *  - kick the home-screen widget refresh.
 *
 * Centralizing here means the user gets the same finalize behavior
 * whether they tap Finish in the in-app UI (via `WalkViewModel`) or in
 * the foreground-service notification (via `WalkTrackingService`). Without
 * this observer, the notification path would silently lose collective
 * contributions and stale the widget for users who don't promptly
 * return to the app.
 *
 * **Voice auto-stop ownership** — Stage 9.5-C moved voice auto-stop into
 * the new [WalkLifecycleObserver], which fires on every in-progress →
 * terminal transition (Finished AND the discardWalk-driven Idle
 * transition). This observer no longer touches the recorder; it only
 * runs the post-finish side-effect bundle when a finalized walk row
 * exists.
 *
 * Per-walkId dedup defends against any future code path that might call
 * a hypothetical `externalFinalize` directly, plus the (today
 * impossible) case where the reducer were ever changed to re-emit
 * Finished.
 *
 * Discards its first collection — observing the CURRENT state of a
 * `@Singleton` controller at app-init time is not a transition (it is
 * always cold-process `Idle` because [WalkController] initializes its
 * state flow to `Idle`).
 *
 * Same eager-instantiation pattern as `MeditationBellObserver`,
 * `VoiceGuideOrchestrator`, etc. — referenced from
 * [org.walktalkmeditate.pilgrim.PilgrimApp.onCreate] so Hilt builds it
 * at app start instead of lazily on first use.
 */
@Singleton
class WalkFinalizationObserver @Inject constructor(
    @WalkFinalizationObservedState walkState: StateFlow<@JvmSuppressWildcards WalkState>,
    @WalkFinalizationScope private val scope: CoroutineScope,
    private val repository: WalkRepository,
    private val transcriptionScheduler: TranscriptionScheduler,
    private val hemisphereRepository: HemisphereRepository,
    private val collectiveRepository: CollectiveRepository,
    private val widgetRefreshScheduler: WidgetRefreshScheduler,
) {
    // Set is unbounded by design — one Long per finished walk for the
    // process lifetime. 8 bytes × 100k walks = 800 KB worst case;
    // realistically processes don't survive long enough for this to
    // matter (Doze + foreground-service restarts kill the process
    // every few hours).
    private val finalizedWalkIds: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    init {
        scope.launch {
            var firstEmission = true
            walkState.collect { state ->
                if (firstEmission) {
                    firstEmission = false
                    return@collect
                }
                if (state !is WalkState.Finished) return@collect
                val walkId = state.walk.walkId
                if (!finalizedWalkIds.add(walkId)) return@collect
                // Fork: don't block the state-collector while we wait
                // out the grace + run the side-effects. Otherwise a
                // long network call to the collective endpoint would
                // wedge the observer for the next transition (e.g.,
                // user starts a new walk while the previous finalize
                // is still in flight).
                scope.launch { runFinalize(walkId, state) }
            }
        }
    }

    private suspend fun runFinalize(walkId: Long, state: WalkState.Finished) {
        // Voice auto-stop on Finished now lives in WalkLifecycleObserver,
        // which subscribes to the SAME state flow on the SAME app-lifetime
        // scope and fires on Active|Paused|Meditating → Idle|Finished. By
        // the time we read voiceRecordingsFor() below, that observer's
        // stop()+INSERT may or may not have completed (no ordering
        // guarantee between two collectors on the same flow), but the
        // collective POST tolerates a stale-by-one talkMin — same
        // tolerance the pre-Stage-9.5-C design had against any race
        // with the VM-side auto-stop, except now the latency window is
        // even narrower because both observers run on Dispatchers.IO.
        Log.i(TAG, "finalizing walk=$walkId")
        // Hemisphere refresh: read-only, idempotent. Repo internally
        // try/catches SecurityException on missing location permission;
        // outer catch is paranoia for any other throwable.
        try {
            hemisphereRepository.refreshFromLocationIfNeeded()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.w(TAG, "hemisphere refresh failed", t)
        }
        try {
            transcriptionScheduler.scheduleForWalk(walkId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.w(TAG, "scheduleForWalk($walkId) failed", t)
        }
        try {
            val talkMin = (
                repository.voiceRecordingsFor(walkId)
                    .sumOf { it.durationMillis } / 60_000L
                ).toInt()
            collectiveRepository.recordWalk(
                CollectiveWalkSnapshot(
                    distanceKm = state.walk.distanceMeters / 1_000.0,
                    meditationMin = (state.walk.totalMeditatedMillis / 60_000L).toInt(),
                    talkMin = talkMin,
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.w(TAG, "collective recordWalk failed", t)
        }
        try {
            widgetRefreshScheduler.scheduleRefresh()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.w(TAG, "widget refresh scheduling failed", t)
        }
    }

    private companion object {
        const val TAG = "WalkFinalizeObserver"
    }
}
