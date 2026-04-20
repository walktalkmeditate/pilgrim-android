// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import android.content.Context
import android.media.AudioAttributes as SystemAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ExoPlayer-backed [VoiceGuidePlayer] with standalone
 * [AudioFocusRequest]. Per-play focus request using
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` so the (future, Stage 5-F)
 * soundscape responds to the loss-listener by dipping its volume
 * — matches iOS's manual-ducking semantics.
 *
 * Standalone (not routed through [AudioFocusCoordinator]) because:
 *  1. The intent's spec calls for `GAIN_TRANSIENT_MAY_DUCK` while
 *     the coordinator only offers `GAIN_TRANSIENT`.
 *  2. The coordinator's internal `abandonIfHeld` on a competing
 *     request wouldn't fire our loss listener (Android's abandon
 *     API doesn't notify), so "voice-memo recording preempts our
 *     guide" wouldn't actually work through it.
 *  3. With a standalone request, OS-level focus contention gives
 *     us correct preemption semantics for incoming calls, nav
 *     prompts, music apps, AND voice-memo recording (which requests
 *     `GAIN_TRANSIENT` — more exclusive than our `MAY_DUCK` — so
 *     the OS delivers `LOSS_TRANSIENT` to us).
 *
 * ExoPlayer (not MediaPlayer) for the actual playback because
 * voice-guide prompts are 10–30s audio files that need clean
 * pause/cancel mid-play; ExoPlayer's pause-then-release cycle is
 * more robust than MediaPlayer's. `handleAudioFocus = false` on
 * the ExoPlayer attributes so our standalone request remains the
 * only focus path.
 *
 * The `onFinished` single-fire contract is enforced via an
 * [AtomicBoolean] guard across all completion paths (natural
 * completion, error, focus loss, stop, release).
 */
@Singleton
class ExoPlayerVoiceGuidePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioManager: AudioManager,
) : VoiceGuidePlayer {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<VoiceGuidePlayer.State>(VoiceGuidePlayer.State.Idle)
    override val state: StateFlow<VoiceGuidePlayer.State> = _state.asStateFlow()

    // Main-looper-serialized: the ExoPlayer instance, the current
    // focus request, and the current `onFinished` callback. All
    // writes and reads happen inside `mainHandler.post { ... }`.
    private var player: ExoPlayer? = null
    private val activeFocusRequest = AtomicReference<AudioFocusRequest?>(null)
    private val pendingOnFinished = AtomicReference<(() -> Unit)?>(null)
    private val completionFired = AtomicBoolean(true) // "armed" only during a play

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _state.value = VoiceGuidePlayer.State.Idle
                fireCompletionOnce()
                abandonFocus()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error", error)
            _state.value = VoiceGuidePlayer.State.Error(error.message ?: "playback failed")
            fireCompletionOnce()
            abandonFocus()
        }
    }

    override fun play(file: File, onFinished: () -> Unit) {
        mainHandler.post {
            // Tear down any prior play — firing its onFinished so the
            // scheduler's play history stays correct — before starting
            // the new one.
            if (!completionFired.get()) {
                fireCompletionOnce()
                internalStop()
            }

            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "file missing or empty: ${file.absolutePath}")
                _state.value = VoiceGuidePlayer.State.Error("file missing")
                // Arm completion with the new callback and fire immediately
                // so the scheduler can advance past this prompt.
                pendingOnFinished.set(onFinished)
                completionFired.set(false)
                fireCompletionOnce()
                return@post
            }

            pendingOnFinished.set(onFinished)
            completionFired.set(false)

            val granted = requestFocus()
            if (!granted) {
                _state.value = VoiceGuidePlayer.State.Error("audio focus denied")
                fireCompletionOnce()
                return@post
            }

            val p = player ?: createPlayer().also { player = it }
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            p.prepare()
            p.play()
            _state.value = VoiceGuidePlayer.State.Playing
        }
    }

    override fun stop() {
        mainHandler.post {
            internalStop()
            fireCompletionOnce()
            abandonFocus()
        }
    }

    override fun release() {
        mainHandler.post {
            val p = player
            player = null
            p?.removeListener(playerListener)
            p?.release()
            fireCompletionOnce()
            abandonFocus()
            _state.value = VoiceGuidePlayer.State.Idle
        }
    }

    /** Must be called on main thread (wrap in mainHandler.post if needed). */
    private fun internalStop() {
        player?.stop()
        _state.value = VoiceGuidePlayer.State.Idle
    }

    private fun createPlayer(): ExoPlayer {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(context)
            // handleAudioFocus = false — our standalone
            // AudioFocusRequest is the only focus owner.
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            .build()
            .also { it.addListener(playerListener) }
    }

    private fun requestFocus(): Boolean {
        val sysAttrs = SystemAudioAttributes.Builder()
            .setUsage(SystemAudioAttributes.USAGE_MEDIA)
            .setContentType(SystemAudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
            .setAudioAttributes(sysAttrs)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ focusChange ->
                // Full loss or transient loss → stop (and fire
                // completion). We do NOT pause — on focus regain the
                // scheduler's next tick will decide whether to play
                // another prompt; auto-resuming mid-prompt is
                // semantically wrong for narration.
                // LOSS_TRANSIENT_CAN_DUCK is informational (Stage 5-B
                // lesson): do nothing — OS-level auto-duck handles it.
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                    focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    mainHandler.post {
                        internalStop()
                        fireCompletionOnce()
                        abandonFocus()
                    }
                }
            }, mainHandler)
            .build()
        val result = audioManager.requestAudioFocus(request)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) activeFocusRequest.set(request)
        return granted
    }

    private fun abandonFocus() {
        val req = activeFocusRequest.getAndSet(null) ?: return
        audioManager.abandonAudioFocusRequest(req)
    }

    private fun fireCompletionOnce() {
        if (!completionFired.compareAndSet(false, true)) return
        val cb = pendingOnFinished.getAndSet(null) ?: return
        cb()
    }

    private companion object {
        const val TAG = "VoiceGuidePlayer"
    }
}
