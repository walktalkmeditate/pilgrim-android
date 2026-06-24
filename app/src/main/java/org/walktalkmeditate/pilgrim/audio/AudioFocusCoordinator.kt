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

    /**
     * Request transient focus for voice CAPTURE (the recorder). Uses
     * USAGE_VOICE_COMMUNICATION which ducks media playback during the
     * recording window. Returns true if focus was granted.
     *
     * The optional [onLossListener] is invoked when the OS reclaims focus
     * mid-recording (incoming call, another capture app). The recorder
     * finalizes the in-flight recording when notified. A transient
     * CAN_DUCK loss (a notification ding) deliberately does NOT trigger
     * it — a recording shouldn't abort for something it can simply talk
     * over.
     */
    fun requestTransient(onLossListener: (() -> Unit)? = null): Boolean =
        request(
            usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
            onLossListener = onLossListener,
            treatDuckAsLoss = false,
        )

    /**
     * Request transient focus for voice PLAYBACK. Uses USAGE_MEDIA so
     * the OS routes audio through the loudspeaker (not the earpiece —
     * USAGE_VOICE_COMMUNICATION would do that, which is wrong for a
     * walk-summary listen-back). Returns true if focus was granted.
     *
     * The optional [onLossListener] is invoked when the OS reclaims
     * focus (incoming call, navigation prompt, another media app). The
     * caller should pause its own playback when notified — ExoPlayer's
     * built-in focus handling is intentionally disabled so the
     * coordinator stays the single owner.
     */
    fun requestMediaPlayback(onLossListener: (() -> Unit)? = null): Boolean =
        request(
            usage = AudioAttributes.USAGE_MEDIA,
            onLossListener = onLossListener,
            treatDuckAsLoss = true,
        )

    private fun request(
        usage: Int,
        onLossListener: (() -> Unit)? = null,
        treatDuckAsLoss: Boolean = true,
    ): Boolean {
        abandonIfHeld()
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val builder = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
        if (onLossListener != null) {
            builder.setOnAudioFocusChangeListener { focusChange ->
                if (isFinalizingFocusLoss(focusChange, treatDuckAsLoss)) {
                    onLossListener.invoke()
                }
            }
        }
        val request = builder.build()
        val result = audioManager.requestAudioFocus(request)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // Only record the request if focus was actually granted —
        // otherwise a future abandon() would submit a never-held request
        // to AudioManager, and the coordinator's "is focus held" state
        // would be subtly wrong.
        if (granted) activeRequest.set(request)
        return granted
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

    internal companion object {
        /**
         * Whether an [AudioManager] focus-change code means the holder
         * should give up the audio. A permanent or transient LOSS always
         * counts; a transient CAN_DUCK loss counts only when
         * [treatDuckAsLoss] is set — playback ducks/pauses for it, but a
         * capture session keeps recording through a momentary duck (a
         * notification) rather than aborting the take.
         */
        internal fun isFinalizingFocusLoss(focusChange: Int, treatDuckAsLoss: Boolean): Boolean =
            focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                (treatDuckAsLoss && focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
    }
}
