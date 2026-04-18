// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

class FakeVoicePlaybackController : VoicePlaybackController {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    val playCalls: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val pauseCalls = AtomicInteger(0)
    val stopCalls = AtomicInteger(0)
    val releaseCalls = AtomicInteger(0)

    override fun play(recording: VoiceRecording) {
        playCalls.add(recording.id)
        _state.value = PlaybackState.Playing(recording.id)
    }

    override fun pause() {
        pauseCalls.incrementAndGet()
        val current = (_state.value as? PlaybackState.Playing)?.recordingId ?: return
        _state.value = PlaybackState.Paused(current)
    }

    override fun stop() {
        stopCalls.incrementAndGet()
        _state.value = PlaybackState.Idle
    }

    override fun release() {
        releaseCalls.incrementAndGet()
        _state.value = PlaybackState.Idle
    }

    /** Test helper: simulate the player reaching STATE_ENDED. */
    fun completePlayback() {
        _state.value = PlaybackState.Idle
    }
}
