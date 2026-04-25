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
 * **Voice-INSERT race** — both this observer and `WalkViewModel.init`'s
 * auto-stop block see the same Finished emission. The VM's auto-stop
 * lives in `viewModelScope`, which is cancelled the moment the nav-pop
 * fires off Finished — on slow OEM devices the in-flight `voiceRecorder.stop()`
 * + Room INSERT can race the cancellation, leaving an orphan WAV with no
 * row. To eliminate the race entirely, **this observer also calls
 * `voiceRecorder.stop()` and writes the row itself** before computing
 * `talkMin`. Whoever wins the stop returns the recording; the loser
 * gets `NoActiveRecording` (idempotent — already-stopped recorders just
 * report no session). If we lost (someone else won the stop), we wait
 * [VOICE_INSERT_GRACE_MS] for that someone's INSERT to commit before
 * querying. If no recording was active at all, the wait is dead but
 * cheap (200 ms).
 *
 * For the notification-path Finish (where no VM exists), this observer
 * is the ONLY auto-stop. The recording is captured + INSERTed before
 * collective `talkMin` is computed.
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
                // Either nothing was recording, or VM's auto-stop won
                // the race. The former case takes a 200 ms hit on the
                // collective POST latency — acceptable. The latter
                // gives VM's still-in-flight INSERT time to commit.
                delay(VOICE_INSERT_GRACE_MS)
            }
            else -> {
                // Unexpected stop failure (audio HAL throw, FS I/O).
                // Log + proceed; caller's recording-on-disk is recoverable
                // by OrphanRecordingSweeper.
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

        // Cushion for the case where someone else (typically
        // WalkViewModel's auto-stop) won the voiceRecorder.stop()
        // race and is still mid-INSERT. Single-row Room INSERTs are
        // typically <100 ms; 200 ms covers slow OEM disk stalls
        // without making the collective POST feel sluggish.
        const val VOICE_INSERT_GRACE_MS = 200L
    }
}
