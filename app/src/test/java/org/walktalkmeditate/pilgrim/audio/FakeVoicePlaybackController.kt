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

    private val _playbackSpeed = MutableStateFlow(1.0f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playbackPositionMillis = MutableStateFlow(0L)
    override val playbackPositionMillis: StateFlow<Long> = _playbackPositionMillis.asStateFlow()

    val playCalls: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val pauseCalls = AtomicInteger(0)
    val stopCalls = AtomicInteger(0)
    val releaseCalls = AtomicInteger(0)
    val setSpeedCalls: MutableList<Float> = Collections.synchronizedList(mutableListOf())
    val seekCalls: MutableList<Float> = Collections.synchronizedList(mutableListOf())

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
        _playbackPositionMillis.value = 0L
    }

    override fun release() {
        releaseCalls.incrementAndGet()
        _state.value = PlaybackState.Idle
        _playbackPositionMillis.value = 0L
    }

    override fun setPlaybackSpeed(rate: Float) {
        setSpeedCalls.add(rate)
        _playbackSpeed.value = rate.coerceIn(0.5f, 2.0f)
    }

    override fun seek(fraction: Float) {
        seekCalls.add(fraction)
    }

    /** Test helper: simulate the player reaching STATE_ENDED. */
    fun completePlayback() {
        _state.value = PlaybackState.Idle
        _playbackPositionMillis.value = 0L
    }
}
