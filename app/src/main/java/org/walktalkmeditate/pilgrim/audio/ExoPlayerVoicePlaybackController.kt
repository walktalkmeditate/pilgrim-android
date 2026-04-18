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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

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
) : VoicePlaybackController {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // Player + currentRecordingId are only read/written on the main
    // thread (mainHandler-serialized), so no additional synchronization
    // is needed beyond that contract.
    private var player: ExoPlayer? = null
    private var currentRecordingId: Long? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                audioFocus.abandon()
                currentRecordingId = null
                _state.value = PlaybackState.Idle
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
            _state.value = PlaybackState.Error(id, error.message ?: "playback failed")
        }
    }

    override fun play(recording: VoiceRecording) {
        mainHandler.post {
            val granted = audioFocus.requestMediaPlayback(onLossListener = ::onAudioFocusLost)
            if (!granted) {
                _state.value = PlaybackState.Error(recording.id, "audio focus denied")
                return@post
            }
            val p = player ?: createPlayer().also { player = it }
            // If the same recording is already Paused, just resume in
            // place rather than rebuilding the MediaItem (keeps the
            // current playback position).
            if (currentRecordingId == recording.id && _state.value is PlaybackState.Paused) {
                p.play()
                _state.value = PlaybackState.Playing(recording.id)
                return@post
            }
            currentRecordingId = recording.id
            val absoluteFile = java.io.File(context.filesDir, recording.fileRelativePath)
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
            _state.value = PlaybackState.Idle
        }
    }

    override fun release() {
        mainHandler.post {
            player?.removeListener(listener)
            player?.release()
            player = null
            audioFocus.abandon()
            currentRecordingId = null
            _state.value = PlaybackState.Idle
        }
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
    }
}
