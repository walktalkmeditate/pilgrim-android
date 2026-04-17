// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps AudioManager focus request/abandon so the [AudioFocusRequest]
 * reference is owned in one place — `abandonAudioFocusRequest` must
 * receive the SAME instance passed to `requestAudioFocus`, so letting
 * VoiceRecorder's state machine hold it would open the door to leaks
 * when an error path skips the abandon.
 *
 * Thread-safe: the active-request reference is held in an
 * [AtomicReference] so future audio consumers (Stage 2-D transcription
 * previews, Stage 2-E playback) can share this @Singleton without
 * external synchronization.
 */
@Singleton
class AudioFocusCoordinator @Inject constructor(
    private val audioManager: AudioManager,
) {
    private val activeRequest = AtomicReference<AudioFocusRequest?>(null)

    /** Returns true if focus was granted. */
    fun requestTransient(): Boolean {
        abandonIfHeld()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .build()
        val result = audioManager.requestAudioFocus(request)
        activeRequest.set(request)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() = abandonIfHeld()

    private fun abandonIfHeld() {
        // getAndSet(null) is the CAS we want: whichever caller wins the
        // swap is the one that calls abandon; losers no-op. Prevents
        // two concurrent abandons from both passing the same request to
        // abandonAudioFocusRequest.
        val req = activeRequest.getAndSet(null) ?: return
        audioManager.abandonAudioFocusRequest(req)
    }
}
