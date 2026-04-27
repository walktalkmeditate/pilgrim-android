// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
 *    effect; inserting would FK-fail. The on-disk WAV is deleted
 *    directly here since
 *    [org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper]
 *    .sweepAll filters to walks with a row, and the discarded walk
 *    row is cascade-deleted — the sweeper never visits its directory.
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
    @ApplicationContext private val context: Context,
) {
    init {
        scope.launch {
            var firstEmission = true
            walkState.collect { state ->
                if (firstEmission) {
                    firstEmission = false
                    return@collect
                }

                // Both Finished and Idle are unconditional: the recorder
                // returns NoActiveRecording (gracefully handled below)
                // when nothing was running, so this is idempotent for
                // the common no-voice-notes walk. Why unconditional?
                // StateFlow conflation can elide the in-progress
                // emission(s) when finishWalk dispatches faster than
                // the collector resumes, leaving the observer to see
                // only `Finished` without ever observing
                // `Active|Paused|Meditating`. A transition latch would
                // wrongly skip the stop+commit in that race window.
                // Similarly, under high CPU contention, discardWalk's
                // Active → Idle transition can arrive with `prevWasInProgress`
                // stale, skipping the stop for a discard. Unconditional
                // Idle is safe: cold-start Idle is still skipped via
                // firstEmission latch (not a transition), and all
                // in-progress → Idle paths call stop() unconditionally
                // (no-op if no recording was running).
                when (state) {
                    is WalkState.Finished -> handleVoiceStop(commitRow = true)
                    WalkState.Idle -> handleVoiceStop(commitRow = false)
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
                val recording = stopResult.getOrThrow()
                val file = File(context.filesDir, recording.fileRelativePath)
                try {
                    if (file.exists() && !file.delete()) {
                        Log.w(
                            TAG,
                            "discard auto-stop: failed to delete orphan WAV " +
                                recording.fileRelativePath,
                        )
                    } else {
                        Log.i(
                            TAG,
                            "discard auto-stop: deleted orphan WAV " +
                                recording.fileRelativePath,
                        )
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    Log.w(TAG, "discard auto-stop: exception deleting orphan WAV", t)
                }
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
