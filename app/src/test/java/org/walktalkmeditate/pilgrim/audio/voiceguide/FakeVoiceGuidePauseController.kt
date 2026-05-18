// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trivial in-memory [VoiceGuidePauseController] for VM unit tests.
 * `pause()` / `resume()` flip the flag exactly like the real
 * orchestrator's flag path (without the player stop, which the VM
 * does not observe).
 */
class FakeVoiceGuidePauseController(
    initialPackName: String? = null,
    initialPaused: Boolean = false,
) : VoiceGuidePauseController {

    private val _activePackName = MutableStateFlow(initialPackName)
    override val activePackName: StateFlow<String?> = _activePackName.asStateFlow()

    private val _isPaused = MutableStateFlow(initialPaused)
    override val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    var pauseCount: Int = 0
        private set
    var resumeCount: Int = 0
        private set

    fun setActivePackName(name: String?) {
        _activePackName.value = name
    }

    override fun pause() {
        pauseCount += 1
        _isPaused.value = true
    }

    override fun resume() {
        resumeCount += 1
        _isPaused.value = false
    }
}
