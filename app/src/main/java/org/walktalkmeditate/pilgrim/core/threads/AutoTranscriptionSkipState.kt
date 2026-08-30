// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why auto-transcription was skipped for the most recently finished walk
 * — a single case today, matching iOS `AutoTranscriptionSkipReason`
 * (EDG-55/UI-43): absence is expressed via nullability on [skipReason],
 * never a `none` case, so a future second reason only adds a case rather
 * than reshaping every reader.
 */
enum class AutoTranscriptionSkipReason { LowBattery }

/**
 * Process-wide (not per-walk) skip-reason holder — mirrors iOS
 * `TranscriptionService.shared.autoTranscriptionSkippedReason`'s
 * @MainActor @Published singleton scope. Written ONLY by the
 * auto-transcription enqueue site
 * ([org.walktalkmeditate.pilgrim.walk.WalkFinalizationObserver]) on a
 * battery-gate failure; cleared at the five sites the parity spec pins
 * (walk start, walk cancel/discard, active-walk dismiss with no pending
 * summary, walk-summary dismiss, and a non-empty manual transcribe-all
 * retry) — see [org.walktalkmeditate.pilgrim.walk.AutoTranscriptionSkipClearObserver]
 * and `WalkSummaryViewModel` for the call sites.
 */
interface AutoTranscriptionSkipState {
    val skipReason: StateFlow<AutoTranscriptionSkipReason?>
    fun setSkipped(reason: AutoTranscriptionSkipReason = AutoTranscriptionSkipReason.LowBattery)
    fun clear()
}

@Singleton
class DefaultAutoTranscriptionSkipState @Inject constructor() : AutoTranscriptionSkipState {
    private val _skipReason = MutableStateFlow<AutoTranscriptionSkipReason?>(null)
    override val skipReason: StateFlow<AutoTranscriptionSkipReason?> = _skipReason.asStateFlow()

    override fun setSkipped(reason: AutoTranscriptionSkipReason) {
        _skipReason.value = reason
    }

    override fun clear() {
        _skipReason.value = null
    }
}
