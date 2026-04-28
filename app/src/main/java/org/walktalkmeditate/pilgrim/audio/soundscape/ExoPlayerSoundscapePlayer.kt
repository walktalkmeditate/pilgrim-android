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
 * `LOSS_TRANSIENT_CAN_DUCK` to our listener. **We manually lower
 * the player's volume** to [DUCK_VOLUME]; the OS does NOT auto-duck
 * when we own focus via a standalone `AudioFocusRequest` with
 * `handleAudioFocus = false` on ExoPlayer — Stage 5-G device-QA
 * confirmed. `setWillPauseWhenDucked(false)` only controls whether
 * to PAUSE vs DUCK, not who performs the duck. Matches iOS's manual
 * volume-dip behavior (minus the fade curve, for now).
 *
 * Focus-loss handling:
 *  - `LOSS` → stop + abandon (another app took focus permanently).
 *  - `LOSS_TRANSIENT` → pause; next `GAIN` resumes.
 *  - `LOSS_TRANSIENT_CAN_DUCK` → dip volume to [DUCK_VOLUME].
 *  - `GAIN` → restore volume AND resume if paused-on-transient-loss.
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

    /**
     * The user's currently-configured soundscape volume in [0, 1],
     * applied at play time and used as the "full" reference for ducking.
     * Driven by [SoundscapeOrchestrator] from
     * [org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository.soundscapeVolume].
     * `@Volatile` because [setVolume] is callable from any thread —
     * the orchestrator's volume-collector runs on the orchestrator's
     * playback scope, not the main thread; the player listener +
     * focus listener run on main. The actual ExoPlayer.volume write
     * is still posted to the main handler.
     */
    @Volatile private var userVolume: Float = FULL_VOLUME

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // REPEAT_MODE_ONE loops internally, so STATE_ENDED should
            // not fire during normal playback. If we see it (rare
            // codec edge case in which REPEAT_MODE_ONE doesn't
            // suppress the end event), tear down cleanly — abandon
            // focus, unregister noisy receiver, and discard the
            // player so the next play() creates a fresh instance.
            // Transitioning to Idle here would silently leak the
            // focus request (Stage 5-F review lesson). Keeping the
            // same ExoPlayer and calling prepare() on it is
            // technically legal per ExoPlayer's contract, but
            // STATE_ENDED is an abnormal terminal for a looping
            // player and reusing the instance relies on codec/
            // version-dependent behavior. Replace it.
            when (playbackState) {
                Player.STATE_READY -> {
                    _state.value = SoundscapePlayer.State.Playing
                }
                Player.STATE_ENDED -> {
                    discardPlayer()
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
            discardPlayer()
            _state.value = SoundscapePlayer.State.Error(
                error.message ?: "playback failed",
            )
            abandonFocus()
            unregisterNoisyReceiver()
            wasPlayingBeforeTransientLoss = false
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
            p.volume = userVolume
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

    /**
     * Null the player and schedule its release on the main handler.
     * Must not call `release()` synchronously from within a listener
     * callback — that would re-enter ExoPlayer's dispatch loop. Used
     * by the `STATE_ENDED` and `onPlayerError` paths so the next
     * `play()` creates a fresh instance via `createPlayer()` instead
     * of reusing an ExoPlayer that's already terminated.
     */
    private fun discardPlayer() {
        val p = player ?: return
        player = null
        mainHandler.post { p.release() }
    }

    /**
     * Dip ExoPlayer volume for duckable transient focus loss. Main thread.
     * Ducks to [DUCK_FRACTION] of the user's configured [userVolume],
     * so users running at low volume don't get whip-sawed up before
     * being ducked back down.
     */
    private fun duckVolume() {
        player?.volume = (userVolume * DUCK_FRACTION).coerceIn(0f, 1f)
    }

    /** Restore ExoPlayer volume when focus returns. Main thread. */
    private fun restoreVolume() {
        player?.volume = userVolume
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        userVolume = clamped
        // Apply live to the running player. Posted to the main handler
        // because ExoPlayer access must happen on its build thread.
        //
        // We deliberately do NOT inspect the duck-state and re-scale
        // here: ducking events are short (sub-second voice-guide
        // prompt) and the next focus event re-applies the right
        // amplitude via [duckVolume] / [restoreVolume], both of
        // which now read the updated [userVolume]. A null player
        // just means no playback in progress; the next [play] picks
        // up the new userVolume.
        mainHandler.post { player?.volume = clamped }
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
                        // OS does NOT auto-duck when we own focus via a
                        // standalone AudioFocusRequest + handleAudioFocus
                        // = false on ExoPlayer. Dip the ExoPlayer volume
                        // manually. Stage 5-G lesson.
                        mainHandler.post { duckVolume() }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        mainHandler.post {
                            restoreVolume()
                            resumeFromTransientLoss()
                        }
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

        /**
         * Default user volume when no preference has been written
         * (e.g., before [setVolume] runs the first time). The
         * orchestrator wires the actual default (0.4 — iOS parity)
         * via [SoundscapePlayer.setVolume] before the first [play].
         */
        const val FULL_VOLUME = 1.0f

        /**
         * Fraction of [userVolume] used when voice-guide (or any
         * app) requests `GAIN_TRANSIENT_MAY_DUCK`. iOS uses ~0.3
         * absolute with a 0.5s fade — we match the proportion
         * relative to the user's chosen volume so a low-volume
         * user doesn't get whip-sawed up before being ducked back
         * down. Fade curve can come later.
         */
        const val DUCK_FRACTION = 0.3f
    }
}
