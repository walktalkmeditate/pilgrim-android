// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * Single-recording playback surface for voice notes. Only one
 * [VoiceRecording] can be Playing or Paused at a time; calling [play]
 * while another recording is already active stops the current one
 * before starting the new one.
 *
 * Implementations must route audio focus through [AudioFocusCoordinator]
 * rather than the underlying player's built-in focus handling — the
 * recorder + playback share a single focus owner for the walk.
 */
interface VoicePlaybackController {
    val state: StateFlow<PlaybackState>

    fun play(recording: VoiceRecording)
    fun pause()
    fun stop()

    /**
     * Release native resources. Idempotent. After [release], the next
     * [play] call rebuilds the underlying player. Called from the
     * surface that owns the controller's lifetime (e.g.,
     * `WalkSummaryViewModel.onCleared`).
     */
    fun release()
}

sealed class PlaybackState {
    data object Idle : PlaybackState()
    data class Playing(val recordingId: Long) : PlaybackState()
    data class Paused(val recordingId: Long) : PlaybackState()
    data class Error(val recordingId: Long, val message: String) : PlaybackState()
}
