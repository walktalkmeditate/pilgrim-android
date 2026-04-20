// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays a single voice-guide prompt file. One file in flight at a
 * time. The caller supplies an `onFinished` callback that fires
 * exactly once per [play] — on natural completion, audio-focus loss,
 * [stop], [release], or playback error. This single-fire contract
 * lets the scheduler keep its play history correct regardless of
 * why playback ended.
 */
interface VoiceGuidePlayer {
    val state: StateFlow<State>

    /**
     * Begin playing [file]. If a prior play is in flight, it is
     * stopped first and its `onFinished` fires before this one
     * starts. [onFinished] is invoked exactly once for this play.
     */
    fun play(file: File, onFinished: () -> Unit)

    /** Stop any in-flight playback. Fires the pending `onFinished`. */
    fun stop()

    /** Release native resources. Safe to call multiple times. */
    fun release()

    sealed class State {
        data object Idle : State()
        data object Playing : State()
        data class Error(val reason: String) : State()
    }
}
