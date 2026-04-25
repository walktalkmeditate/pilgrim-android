// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.util.Log
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
 * **Voice-INSERT race** — `WalkViewModel.init` has an auto-stop observer
 * (also subscribed to `controller.state`) that, on Finished, stops the
 * voice recorder and INSERTs the in-progress recording row on `Dispatchers.IO`.
 * If `runFinalize` ran synchronously on the state emission, the
 * transcription scheduler + collective `talkMin` would race against that
 * INSERT and miss the last recording. We give the VM auto-stop a head
 * start by waiting [FINALIZE_GRACE_MS] before running. By then the
 * single-row INSERT has committed (typical IO time is well under 100 ms;
 * a 1 s grace is generous).
 *
 * For the notification-path Finish (where no VM is alive to auto-stop a
 * voice recording), the grace is just dead time — `talkMin` will read
 * 0 for the in-progress recording. Acceptable edge case; the recording
 * itself remains on disk and `OrphanRecordingSweeper` (Stage 2-E case d)
 * will pick it up on the user's next summary-screen open.
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
        delay(FINALIZE_GRACE_MS)
        Log.i(TAG, "finalizing walk=$walkId after grace")
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

        // Long enough for the WalkViewModel's auto-stop voice-recorder
        // INSERT (typically <100ms) to commit before we query
        // voiceRecordingsFor for the collective talkMin. Short enough
        // that the user doesn't perceive the home-screen widget
        // refresh lag after a notification-Finish.
        const val FINALIZE_GRACE_MS = 1_000L
    }
}
