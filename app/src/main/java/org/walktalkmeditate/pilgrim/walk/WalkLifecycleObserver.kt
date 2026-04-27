// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.audio.VoiceRecorderError
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * App-scoped observer that owns voice-recorder auto-stop on every
 * in-progress → terminal transition (Active|Paused|Meditating →
 * Idle|Finished).
 *
 * Stage 9.5-C factored this out of [WalkFinalizationObserver], which only
 * fired on Finished. Without a separate Idle handler, the discardWalk
 * path (Active → Idle, walk row already cascade-deleted) would leak the
 * in-flight recording AND attempt to insert a VoiceRecording row whose
 * parent Walk no longer exists — a guaranteed FK violation.
 *
 * Routes:
 *  - Finished (normal end): stop + commit row. Same behavior as the
 *    pre-Stage-9.5-C `WalkFinalizationObserver` block.
 *  - Idle after in-progress (discard): stop + DROP the row. Parent
 *    Walk row has already been removed by the controller's PurgeWalk
 *    effect; inserting would FK-fail. The on-disk WAV is recoverable
 *    via [org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper]
 *    if the user changes their mind (extremely unlikely UX-wise).
 *
 * Discards its first emission — observing the CURRENT state of a
 * `@Singleton` controller at app-init time is not a transition (it is
 * always cold-process `Idle` since [WalkController] initializes its
 * state flow to `Idle`).
 *
 * Same eager-instantiation pattern as [WalkFinalizationObserver]: must
 * be referenced from [org.walktalkmeditate.pilgrim.PilgrimApp.onCreate]
 * so Hilt builds it at app start instead of lazily on first use.
 */
@Singleton
class WalkLifecycleObserver @Inject constructor(
    @WalkFinalizationObservedState walkState: StateFlow<@JvmSuppressWildcards WalkState>,
    @WalkFinalizationScope private val scope: CoroutineScope,
    private val voiceRecorder: VoiceRecorder,
    private val repository: WalkRepository,
) {
    init {
        scope.launch {
            var firstEmission = true
            var prevWasInProgress = false
            walkState.collect { state ->
                val nowInProgress = state is WalkState.Active ||
                    state is WalkState.Paused ||
                    state is WalkState.Meditating
                if (firstEmission) {
                    firstEmission = false
                    prevWasInProgress = nowInProgress
                    return@collect
                }
                val wasInProgress = prevWasInProgress
                prevWasInProgress = nowInProgress

                // Finished is unconditional: stop + commit. The recorder
                // returns NoActiveRecording (gracefully handled below)
                // when nothing was running, so this is idempotent for
                // the common no-voice-notes walk. Why unconditional?
                // StateFlow conflation can elide the in-progress
                // emission(s) when finishWalk dispatches faster than
                // the collector resumes, leaving the observer to see
                // only `Finished` without ever observing
                // `Active|Paused|Meditating`. A transition latch would
                // wrongly skip the stop+commit in that race window.
                //
                // Idle DOES require having previously seen in-progress,
                // because cold-start Idle is not a transition (and the
                // controller initializes its state flow to Idle, so the
                // first emission is always Idle).
                when (state) {
                    is WalkState.Finished -> handleVoiceStop(commitRow = true)
                    WalkState.Idle -> if (wasInProgress) {
                        handleVoiceStop(commitRow = false)
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun handleVoiceStop(commitRow: Boolean) {
        val stopResult = voiceRecorder.stop()
        when {
            stopResult.isSuccess && commitRow -> {
                try {
                    repository.recordVoice(stopResult.getOrThrow())
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    Log.w(TAG, "auto-stop INSERT failed", t)
                }
            }
            stopResult.isSuccess && !commitRow -> {
                Log.i(TAG, "discard auto-stop: dropping recording (parent walk purged)")
            }
            stopResult.exceptionOrNull() is VoiceRecorderError.NoActiveRecording -> {
                // Common case for walks with no voice notes.
            }
            else -> {
                Log.w(TAG, "voice auto-stop failed", stopResult.exceptionOrNull())
            }
        }
    }

    private companion object {
        const val TAG = "WalkLifecycleObserver"
    }
}
