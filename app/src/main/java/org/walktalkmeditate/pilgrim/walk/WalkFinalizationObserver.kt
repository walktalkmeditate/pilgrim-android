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
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.audio.VoiceRecorderError
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
 * **Voice auto-stop ownership** — this observer is the canonical owner
 * of `voiceRecorder.stop()` on Finished transitions. It runs in
 * [WalkFinalizationScope] (app lifetime, SupervisorJob) so the
 * stop+INSERT cannot be cancelled by VM nav-pop. The VM-side voice
 * state is updated reactively in `WalkViewModel.init` to Idle on
 * Finished, but it does NOT call stop — that would race this observer
 * (the previous design had this race; see commit history). If no
 * recording was active, `voiceRecorder.stop()` returns
 * `NoActiveRecording` and we proceed immediately.
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
    private val voiceRecorder: VoiceRecorder,
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
        // Stop any in-progress voice recording + commit its row before
        // computing talkMin. See class kdoc for the race details. This
        // observer's stop() runs in WalkFinalizationScope (app-lifetime,
        // SupervisorJob) so it cannot be cancelled by VM nav-pop.
        val stopResult = voiceRecorder.stop()
        when {
            stopResult.isSuccess -> {
                try {
                    repository.recordVoice(stopResult.getOrThrow())
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    Log.w(TAG, "auto-stop INSERT failed", t)
                }
            }
            stopResult.exceptionOrNull() is VoiceRecorderError.NoActiveRecording -> {
                // Nothing was recording — common case for walks with
                // no voice notes. Proceed immediately.
            }
            else -> {
                // Unexpected stop failure (audio HAL throw, FS I/O).
                // Log + proceed; the WAV-on-disk is recoverable by
                // OrphanRecordingSweeper.
                Log.w(TAG, "voice auto-stop failed", stopResult.exceptionOrNull())
            }
        }
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
