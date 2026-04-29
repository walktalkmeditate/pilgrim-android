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

    /**
     * Current playback speed multiplier in [0.5, 2.0]. Stage 10-D added
     * the speed-toggle scrubber on the voice card; the value here is the
     * COERCED rate (what's actually playing), not what the caller requested.
     */
    val playbackSpeed: StateFlow<Float>

    /**
     * Live playback position in milliseconds. While the underlying
     * player is in `Player.STATE_READY` and playing, the controller posts
     * a 100ms tick to update this StateFlow; while paused/idle/error it
     * holds the last sampled value (or 0 after `release` / before the
     * first `play`).
     */
    val playbackPositionMillis: StateFlow<Long>

    fun play(recording: VoiceRecording)
    fun pause()
    fun stop()

    /**
     * Release native resources. Idempotent. After [release], the next
     * [play] call rebuilds the underlying player.
     *
     * NOTE: in the current Stage 2-E binding the controller is
     * `@Singleton`, so [release] is only intended for process-tear-down
     * paths (e.g., low-memory triggers in a future stage). The
     * `WalkSummaryViewModel.onCleared` path uses [stop] — calling
     * [release] from a per-screen lifecycle would race against a
     * subsequent screen's [play].
     */
    fun release()

    /**
     * Set playback speed. The rate is coerced into [0.5, 2.0]; the
     * underlying player is set with `pitch = 1.0f` so audio doesn't
     * pitch-shift (chipmunk effect). The reported [playbackSpeed]
     * StateFlow reflects the COERCED rate.
     */
    fun setPlaybackSpeed(rate: Float)

    /**
     * Seek to a fraction in [0, 1] of the current recording's duration.
     * No-op if no recording is loaded or if the player can't yet report
     * a duration (e.g. mid-prepare). The fraction is coerced; the
     * resolved millisecond target is also clamped to [0, duration].
     */
    fun seek(fraction: Float)
}

sealed class PlaybackState {
    data object Idle : PlaybackState()
    data class Playing(val recordingId: Long) : PlaybackState()
    data class Paused(val recordingId: Long) : PlaybackState()
    data class Error(val recordingId: Long, val message: String) : PlaybackState()
}
