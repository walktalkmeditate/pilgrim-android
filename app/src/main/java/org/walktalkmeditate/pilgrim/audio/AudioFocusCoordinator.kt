// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps AudioManager focus request/abandon so the [AudioFocusRequest]
 * reference is owned in one place — `abandonAudioFocusRequest` must
 * receive the SAME instance passed to `requestAudioFocus`, so letting
 * VoiceRecorder's state machine hold it would open the door to leaks
 * when an error path skips the abandon.
 */
@Singleton
class AudioFocusCoordinator @Inject constructor(
    private val audioManager: AudioManager,
) {
    private var activeRequest: AudioFocusRequest? = null

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
        activeRequest = request
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() = abandonIfHeld()

    private fun abandonIfHeld() {
        val req = activeRequest ?: return
        activeRequest = null
        audioManager.abandonAudioFocusRequest(req)
    }
}
