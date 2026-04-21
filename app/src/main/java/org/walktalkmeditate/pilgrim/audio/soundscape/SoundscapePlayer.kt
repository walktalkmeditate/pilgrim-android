// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays a single looping ambient soundscape file in the background
 * during meditation. Unlike [org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuidePlayer],
 * there is no single-fire completion contract — ambient plays
 * indefinitely until [stop] or [release] is called. Audio-focus
 * loss (phone call, other media) may pause playback; focus regain
 * resumes it within the same session.
 */
interface SoundscapePlayer {
    val state: StateFlow<State>

    /**
     * Begin playing [file] on loop. If already playing, stops the
     * previous play and starts the new one.
     */
    fun play(file: File)

    /** Stop playback and abandon audio focus. */
    fun stop()

    /** Release native ExoPlayer resources. Safe to call multiple times. */
    fun release()

    sealed class State {
        data object Idle : State()
        data object Playing : State()

        /**
         * Paused because the OS revoked audio focus transiently
         * (incoming call, navigation prompt). Will auto-resume on
         * focus regain within the same session.
         */
        data object Paused : State()

        data class Error(val reason: String) : State()
    }
}
