// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory test double for [AutoTranscriptionSkipState]. */
class FakeAutoTranscriptionSkipState : AutoTranscriptionSkipState {
    private val _skipReason = MutableStateFlow<AutoTranscriptionSkipReason?>(null)
    override val skipReason: StateFlow<AutoTranscriptionSkipReason?> = _skipReason.asStateFlow()

    var setSkippedCalls: Int = 0
        private set
    var clearCalls: Int = 0
        private set

    override fun setSkipped(reason: AutoTranscriptionSkipReason) {
        setSkippedCalls++
        _skipReason.value = reason
    }

    override fun clear() {
        clearCalls++
        _skipReason.value = null
    }
}
