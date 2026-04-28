// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * Production [VoicePlaybackController] backed by androidx.media3
 * ExoPlayer. The player is lazy-created on first [play] so users who
 * never tap play don't pay the native-resource cost. All player
 * interactions are marshalled onto the main looper (ExoPlayer requires
 * its access thread to match the thread it was built on).
 *
 * Audio focus is explicitly handled by [AudioFocusCoordinator]. We
 * disable ExoPlayer's internal focus management via
 * `setAudioAttributes(handleAudioFocus = false)` so the coordinator
 * remains the single focus owner across VoiceRecorder + playback.
 */
@Singleton
class ExoPlayerVoicePlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFocus: AudioFocusCoordinator,
    private val fileSystem: VoiceRecordingFileSystem,
) : VoicePlaybackController {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _playbackPositionMillis = MutableStateFlow(0L)
    override val playbackPositionMillis: StateFlow<Long> = _playbackPositionMillis.asStateFlow()

    // Player + currentRecordingId are only read/written on the main
    // thread (mainHandler-serialized), so no additional synchronization
    // is needed beyond that contract.
    private var player: ExoPlayer? = null
    private var currentRecordingId: Long? = null

    // Re-posts itself every POSITION_TICK_MS while the player is in
    // STATE_READY + playing. Started/stopped from onPlaybackStateChanged
    // and onIsPlayingChanged. mainHandler.removeCallbacks(this) is the
    // canonical cancel — the Runnable instance is the cancellation key.
    private val positionTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            _playbackPositionMillis.value = p.currentPosition
            mainHandler.postDelayed(this, POSITION_TICK_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                audioFocus.abandon()
                currentRecordingId = null
                stopPositionTicks()
                _playbackPositionMillis.value = 0L
                _state.value = PlaybackState.Idle
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startPositionTicks()
            } else {
                stopPositionTicks()
                // Sample one final position so a paused player surfaces
                // its true position (the last tick may have fired up to
                // POSITION_TICK_MS ago).
                player?.let { _playbackPositionMillis.value = it.currentPosition }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = currentRecordingId ?: return
            Log.w(TAG, "playback error for recording $id", error)
            audioFocus.abandon()
            // Null currentRecordingId so a stale `pause()` arriving on
            // the next frame can't transition state into Paused on a
            // player that ExoPlayer has already reset to STATE_IDLE.
            currentRecordingId = null
            stopPositionTicks()
            _playbackPositionMillis.value = 0L
            _state.value = PlaybackState.Error(id, error.message ?: "playback failed")
        }
    }

    override fun play(recording: VoiceRecording) {
        mainHandler.post {
            // Resume-in-place: same recording, currently Paused. Focus
            // is still held from the original play(); re-requesting it
            // would briefly abandon and re-acquire, which other audio
            // apps observe and which can return DELAYED/FAILED on
            // contended systems.
            if (currentRecordingId == recording.id && _state.value is PlaybackState.Paused) {
                val p = player ?: return@post
                p.play()
                _state.value = PlaybackState.Playing(recording.id)
                return@post
            }
            val granted = audioFocus.requestMediaPlayback(onLossListener = ::onAudioFocusLost)
            if (!granted) {
                _state.value = PlaybackState.Error(recording.id, "audio focus denied")
                return@post
            }
            val p = player ?: createPlayer().also { player = it }
            // Switching recording — reset the previous position before
            // ExoPlayer reports `currentPosition` for the new media item.
            // Otherwise UI bound to playbackPositionMillis would briefly
            // show the old recording's tail before the first tick fires.
            _playbackPositionMillis.value = 0L
            currentRecordingId = recording.id
            val absoluteFile = fileSystem.absolutePath(recording.fileRelativePath)
            p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(absoluteFile)))
            p.prepare()
            p.play()
            _state.value = PlaybackState.Playing(recording.id)
        }
    }

    override fun pause() {
        mainHandler.post {
            val p = player ?: return@post
            val id = currentRecordingId ?: return@post
            p.pause()
            _state.value = PlaybackState.Paused(id)
        }
    }

    override fun stop() {
        mainHandler.post {
            player?.stop()
            audioFocus.abandon()
            currentRecordingId = null
            stopPositionTicks()
            _playbackPositionMillis.value = 0L
            _state.value = PlaybackState.Idle
        }
    }

    override fun release() {
        mainHandler.post {
            stopPositionTicks()
            player?.removeListener(listener)
            player?.release()
            player = null
            audioFocus.abandon()
            currentRecordingId = null
            _playbackPositionMillis.value = 0L
            _state.value = PlaybackState.Idle
        }
    }

    override fun setPlaybackSpeed(rate: Float) {
        mainHandler.post {
            val coerced = rate.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
            // pitch = 1.0f is intentional — without it ExoPlayer pitch-shifts
            // (chipmunk effect at >1.0). Default is 1.0 already, but passing
            // it explicitly documents intent.
            player?.setPlaybackParameters(PlaybackParameters(coerced, 1.0f))
            // Storing the COERCED value: observers should see what's actually
            // playing, not what the caller asked for. UI bindings expecting
            // their `setPlaybackSpeed(2.5f)` to round-trip will see 2.0f.
            _playbackSpeed.value = coerced
        }
    }

    override fun seek(fraction: Float) {
        mainHandler.post {
            val p = player ?: return@post
            // Both guards are necessary: a player with no media item has an
            // undefined duration, and `C.TIME_UNSET == Long.MIN_VALUE` —
            // multiplying by a fraction produces a garbage seek target that
            // crashes ExoPlayer.
            if (p.currentMediaItem == null) return@post
            val dur = p.duration
            if (dur == C.TIME_UNSET) return@post
            val target = (fraction.coerceIn(0f, 1f) * dur).toLong().coerceIn(0L, dur)
            p.seekTo(target)
            // Keep the StateFlow in sync immediately so the UI doesn't lag
            // a tick behind the user's drag-release gesture.
            _playbackPositionMillis.value = target
        }
    }

    private fun startPositionTicks() {
        mainHandler.removeCallbacks(positionTick)
        mainHandler.post(positionTick)
    }

    private fun stopPositionTicks() {
        mainHandler.removeCallbacks(positionTick)
    }

    /**
     * Invoked by [AudioFocusCoordinator] on the focus listener thread
     * when the OS reclaims focus (incoming call, navigation prompt,
     * another media app). Pausing here keeps the player in a state
     * that lets the user manually resume — auto-resuming on focus
     * regain is intentionally NOT done (Stage 2-E is a passive
     * listen-back surface; the user reacts to the interruption).
     */
    private fun onAudioFocusLost() {
        mainHandler.post {
            val p = player ?: return@post
            val id = currentRecordingId ?: return@post
            if (p.isPlaying) {
                p.pause()
                _state.value = PlaybackState.Paused(id)
            }
        }
    }

    private fun createPlayer(): ExoPlayer {
        val attrs = AudioAttributes.Builder()
            // USAGE_MEDIA, NOT USAGE_VOICE_COMMUNICATION — the latter
            // routes audio through the in-call earpiece, which is
            // inaudible when the phone is held away from the ear. Voice
            // notes on the summary screen are media playback even
            // though the *content* is speech.
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(context)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            .build()
            .also { it.addListener(listener) }
    }

    private companion object {
        const val TAG = "VoicePlayback"
        const val POSITION_TICK_MS = 100L
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 2.0f
    }
}
