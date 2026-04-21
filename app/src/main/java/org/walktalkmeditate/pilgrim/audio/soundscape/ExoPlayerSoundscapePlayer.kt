// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes as SystemAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
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
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ExoPlayer-backed [SoundscapePlayer] for long-form looping ambient
 * playback. Single-file loop via `Player.REPEAT_MODE_ONE`; gapless-
 * ness depends on the audio file having appropriate loop metadata
 * (audio-engineering fix, not app-code fix).
 *
 * Audio focus: standalone `AudioFocusRequest(AUDIOFOCUS_GAIN)` —
 * long-term ownership. When voice-guide fires
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, the OS sends
 * `LOSS_TRANSIENT_CAN_DUCK` to our listener and auto-ducks the
 * stream (because `setWillPauseWhenDucked(false)` configures the
 * OS to handle the duck). We rely on the OS auto-duck rather than
 * manually dipping volume — simpler than iOS's 0.5s fade curve but
 * semantically correct.
 *
 * Focus-loss handling:
 *  - `LOSS` → stop + abandon (another app took focus permanently).
 *  - `LOSS_TRANSIENT` → pause; next `GAIN` resumes.
 *  - `LOSS_TRANSIENT_CAN_DUCK` → no-op (OS auto-ducks).
 *  - `GAIN` → resume if we were paused-on-transient-loss.
 *
 * `handleAudioFocus = false` on ExoPlayer — we own the focus path.
 * `BECOMING_NOISY` receiver stops playback on headphone unplug
 * (Stage 5-E lesson — ExoPlayer's built-in noisy receiver is
 * part of its focus handling and gets disabled with
 * `handleAudioFocus = false`).
 */
@Singleton
class ExoPlayerSoundscapePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioManager: AudioManager,
) : SoundscapePlayer {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<SoundscapePlayer.State>(SoundscapePlayer.State.Idle)
    override val state: StateFlow<SoundscapePlayer.State> = _state.asStateFlow()

    // Main-looper-serialized fields; ExoPlayer access MUST happen on
    // the thread it was built on.
    private var player: ExoPlayer? = null

    // Cross-thread publication: focus listener fires on the handler
    // thread (we pass mainHandler) but the write happens there too,
    // so @Volatile is mostly defensive. Keep it for clarity.
    private val activeFocusRequest = AtomicReference<AudioFocusRequest?>(null)

    @Volatile private var wasPlayingBeforeTransientLoss: Boolean = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // REPEAT_MODE_ONE loops internally, so STATE_ENDED should
            // not fire during normal playback. If we see it (rare
            // codec edge case in which REPEAT_MODE_ONE doesn't
            // suppress the end event), tear down cleanly — abandon
            // focus, unregister noisy receiver, transition to Error
            // so the state is visible. Transitioning to Idle here
            // would silently leak the focus request (Stage 5-F
            // review lesson).
            when (playbackState) {
                Player.STATE_READY -> {
                    _state.value = SoundscapePlayer.State.Playing
                }
                Player.STATE_ENDED -> {
                    abandonFocus()
                    unregisterNoisyReceiver()
                    _state.value = SoundscapePlayer.State.Error("loop ended unexpectedly")
                    wasPlayingBeforeTransientLoss = false
                }
                // STATE_IDLE, STATE_BUFFERING — no state transition here.
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error", error)
            _state.value = SoundscapePlayer.State.Error(
                error.message ?: "playback failed",
            )
            abandonFocus()
            unregisterNoisyReceiver()
        }
    }

    /**
     * BECOMING_NOISY receiver — headphone unplug / BT disconnect.
     * Without this, removing headphones mid-meditation would blast
     * the soundscape through the loudspeaker. ExoPlayer's built-in
     * receiver is gated on `handleAudioFocus = true`, which we've
     * disabled. Stage 5-E lesson.
     */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "audio becoming noisy — stopping soundscape")
                mainHandler.post {
                    internalStop()
                    abandonFocus()
                }
            }
        }
    }
    private var noisyReceiverRegistered = false

    override fun play(file: File) {
        mainHandler.post {
            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "soundscape file missing: ${file.absolutePath}")
                _state.value = SoundscapePlayer.State.Error("file missing")
                return@post
            }
            // Tear down any in-flight play before starting the new one.
            if (_state.value is SoundscapePlayer.State.Playing ||
                _state.value is SoundscapePlayer.State.Paused
            ) {
                internalStop()
            }
            val granted = requestFocus()
            if (!granted) {
                _state.value = SoundscapePlayer.State.Error("audio focus denied")
                return@post
            }
            val p = player ?: createPlayer().also { player = it }
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.prepare()
            p.play()
            registerNoisyReceiver()
            _state.value = SoundscapePlayer.State.Playing
            wasPlayingBeforeTransientLoss = false
        }
    }

    override fun stop() {
        mainHandler.post {
            internalStop()
            abandonFocus()
        }
    }

    override fun release() {
        mainHandler.post {
            val p = player
            player = null
            p?.removeListener(playerListener)
            p?.release()
            unregisterNoisyReceiver()
            abandonFocus()
            wasPlayingBeforeTransientLoss = false
            _state.value = SoundscapePlayer.State.Idle
        }
    }

    /** Must be called on main thread. */
    private fun internalStop() {
        player?.stop()
        unregisterNoisyReceiver()
        _state.value = SoundscapePlayer.State.Idle
        wasPlayingBeforeTransientLoss = false
    }

    /** Pause for transient focus loss — keep focus request, mark resumable. */
    private fun pauseForTransientLoss() {
        val p = player ?: return
        if (_state.value is SoundscapePlayer.State.Playing) {
            wasPlayingBeforeTransientLoss = true
            p.pause()
            _state.value = SoundscapePlayer.State.Paused
        }
    }

    /** Resume after focus regain if we were paused-on-transient-loss. */
    private fun resumeFromTransientLoss() {
        if (!wasPlayingBeforeTransientLoss) return
        val p = player ?: return
        wasPlayingBeforeTransientLoss = false
        p.play()
        _state.value = SoundscapePlayer.State.Playing
    }

    private fun createPlayer(): ExoPlayer {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            // CONTENT_TYPE_MUSIC for ambient — distinguishes us from
            // voice-guide's CONTENT_TYPE_SPEECH so the OS can route
            // appropriately if spatial-audio features are present.
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(context)
            // handleAudioFocus = false — our standalone request owns focus.
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            .build()
            .also { it.addListener(playerListener) }
    }

    private fun requestFocus(): Boolean {
        // Defensive abandon: if STATE_ENDED or an error path earlier
        // left `activeFocusRequest` non-null without abandon, any new
        // requestFocus would leak the prior request to the OS. No-op
        // when nothing is held. Stage 5-F review lesson.
        abandonFocus()
        val sysAttrs = SystemAudioAttributes.Builder()
            .setUsage(SystemAudioAttributes.USAGE_MEDIA)
            .setContentType(SystemAudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(sysAttrs)
            .setWillPauseWhenDucked(false) // OS auto-ducks when MAY_DUCK fires
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        mainHandler.post {
                            internalStop()
                            abandonFocus()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        mainHandler.post { pauseForTransientLoss() }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // Informational — OS auto-ducks. Do not pause or
                        // change state. Stage 5-B / 5-E lesson.
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        mainHandler.post { resumeFromTransientLoss() }
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

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(noisyReceiver, filter)
        }
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        try {
            context.unregisterReceiver(noisyReceiver)
        } catch (t: IllegalArgumentException) {
            Log.w(TAG, "noisy receiver unregister failed", t)
        }
        noisyReceiverRegistered = false
    }

    private companion object {
        const val TAG = "SoundscapePlayer"
    }
}
