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

    /**
     * Stop playback for a mid-meditation soundscape swap WITHOUT
     * abandoning audio focus. iOS parity SoundscapePlayer.swift:30-33
     * — a crossfade keeps the audio session active; only a true exit
     * (`stop`) deactivates it. Abandoning focus on every swap would
     * preempt the voice guide's `GAIN_TRANSIENT_MAY_DUCK` request and
     * silence the guide (BUG A2). The next [play] reuses the held
     * focus.
     */
    fun stopForSwap()

    /**
     * Set the playback volume in [0.0, 1.0]. Applies live without
     * restarting playback. Implementations clamp out-of-range values.
     * Safe to call from any thread holding a reference; safe to call
     * before [play] (the next [play] inherits the volume).
     */
    fun setVolume(volume: Float)

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

/**
 * The soundscape as a Way voice ducks it (parity spec C §6, correction 12):
 * iOS `SoundscapePlayer`'s `currentTargetVolume` and `setVolume(_, animated: true)`,
 * as `WayVoicePlayer` and the guide prompt it hands its duck to call them
 * (`WayVoicePlayer.swift:145-158,219-237@7c200bf`). Only the walk audio
 * arbiter calls it, so with the release flag off nothing does.
 */
interface WayVoiceSoundscapeDuck {

    /**
     * The level the soundscape is heading to, which a duck records as its
     * "before" (iOS `currentTargetVolume`). Starting a soundscape overwrites
     * it with the walker's level, whatever duck is in force (iOS C-D4).
     */
    val targetVolume: Float

    /**
     * Ramps to the absolute [level] over 0.5 s and holds it there. The
     * soundscape's own may-duck dip stands aside until [releaseDuck], since
     * the Way voice's own focus request sets one off. With no soundscape
     * playing, only [targetVolume] changes.
     */
    fun holdDuck(level: Float)

    /** Ramps back to [level] over 0.5 s, and the soundscape's may-duck handling returns. */
    fun releaseDuck(level: Float)
}
