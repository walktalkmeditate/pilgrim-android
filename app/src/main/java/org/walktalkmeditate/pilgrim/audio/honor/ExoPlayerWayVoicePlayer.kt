// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.honor

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
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [WayVoicePlayer] on a fresh ExoPlayer per play, so a late callback from
 * a stopped or replaced play is dropped by identity, as iOS's delegate
 * checks `player === self.player` (`WayVoicePlayer.swift:126-141@7c200bf`).
 *
 * One standalone focus request, `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with
 * `USAGE_MEDIA`, taken at a play's start and kept through its pauses and
 * across a replacing play, given up on every other exit: iOS activates
 * its `"honor-voice"` consumer in `start()` and releases it in `finish`
 * (spec C §11.2). ExoPlayer runs with `handleAudioFocus = false`, so this
 * class also owns the BECOMING_NOISY receiver.
 *
 * iOS registers no interruption or route handling for the Way voice
 * (pilgrim-ios #102). Android's platform equivalents (owner decision 2,
 * recorded at the gate), each reported to the play's listener:
 * - a transient loss (a call) pauses a sounding voice, and the regain resumes it;
 * - a permanent loss stops it and reports it ended, so the engine takes its
 *   turn and the next voice asks for focus again;
 * - BECOMING_NOISY pauses it, and nothing but a resume starts it again.
 *
 * A may-duck loss changes nothing: iOS mixes the Way voice with other audio
 * (`.mixWithOthers`, spec C §11.1) and never ducks it, and a guide prompt
 * reaches it through the arbiter's prompt gate instead.
 */
@Singleton
class ExoPlayerWayVoicePlayer internal constructor(
    private val context: Context,
    private val audioManager: AudioManager,
    private val tracks: WayVoiceTrack.Factory,
) : WayVoicePlayer {

    @Inject
    constructor(@ApplicationContext context: Context, audioManager: AudioManager) :
        this(context, audioManager, ExoPlayerWayVoiceTrack.factory(context))

    private val mainHandler = Handler(Looper.getMainLooper())

    private var current: Play? = null
    private var focus: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyReceiverRegistered = false

    override var rate: Float = 1f
        private set

    override val isPlaying: Boolean get() = current?.sounding == true

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            mainHandler.post {
                val play = current ?: return@post
                dropFocusResume()
                pausePlay(play)
                play.listener.onPausedForRoute()
            }
        }
    }

    override fun play(file: File, volume: Float, listener: WayVoicePlaybackListener) {
        checkMainThread()
        current?.let(::retire)
        val next = Play(listener)
        current = next
        if (!file.isFile || !holdFocus()) {
            failSoon(next)
            return
        }
        registerNoisyReceiver()
        next.track = try {
            tracks.start(file, volume, rate, next)
        } catch (e: RuntimeException) {
            Log.w(TAG, "a Way voice would not start: ${e::class.simpleName}")
            failSoon(next)
            return
        }
        next.sounding = true
    }

    override fun pause() {
        checkMainThread()
        dropFocusResume()
        current?.let(::pausePlay)
    }

    override fun resume() {
        checkMainThread()
        dropFocusResume()
        current?.let(::resumePlay)
    }

    override fun stop() {
        checkMainThread()
        val stopped = current ?: return
        current = null
        retire(stopped)
        releaseAudio()
    }

    override fun seek(fraction: Double) {
        checkMainThread()
        current?.track?.seekTo(fraction.coerceIn(0.0, MAX_SEEK_FRACTION))
    }

    override fun setRate(rate: Float) {
        checkMainThread()
        this.rate = rate
        current?.track?.setRate(rate)
    }

    override fun positionMillis(): Long {
        checkMainThread()
        return current?.track?.positionMillis() ?: 0L
    }

    private fun pausePlay(play: Play) {
        if (!play.sounding) return
        play.track?.pause()
        play.sounding = false
    }

    private fun resumePlay(play: Play) {
        val track = play.track ?: return
        track.resume()
        play.sounding = true
    }

    /** Silences a play that is being replaced or stopped; never reports it. */
    private fun retire(play: Play) {
        play.sounding = false
        play.track?.release()
    }

    /** The one exit that reports: an end, a failure, or a permanent focus loss. */
    private fun end(play: Play, report: (WayVoicePlaybackListener) -> Unit) {
        if (current !== play) return
        current = null
        retire(play)
        releaseAudio()
        report(play.listener)
    }

    /** Reported on a later turn, so a caller never sees its own play fail inside [play]. */
    private fun failSoon(play: Play) {
        play.sounding = false
        mainHandler.post { end(play) { it.onFailed(beforeSound = true) } }
    }

    /** A call's hold ends without the regain: the caller's own word, or the headphones going. */
    private fun dropFocusResume() {
        if (!resumeOnFocusGain) return
        resumeOnFocusGain = false
        current?.listener?.onHeld(false)
    }

    private fun releaseAudio() {
        resumeOnFocusGain = false
        unregisterNoisyReceiver()
        abandonFocus()
    }

    private fun holdFocus(): Boolean {
        if (focus != null) return true
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(focusAttributes())
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change -> if (focus === request) onFocusChange(change) }, mainHandler)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focus = request
        return granted
    }

    private fun abandonFocus() {
        val held = focus ?: return
        focus = null
        audioManager.abandonAudioFocusRequest(held)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> current?.takeIf { it.sounding }?.let { play ->
                pausePlay(play)
                resumeOnFocusGain = true
                play.listener.onHeld(true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                current?.let { play ->
                    resumePlay(play)
                    play.listener.onHeld(false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> current?.let { play -> end(play) { it.onEnded() } }
        }
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
        noisyReceiverRegistered = false
        try {
            context.unregisterReceiver(noisyReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "noisy receiver was not registered", e)
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "the Way voice player runs on the main thread" }
    }

    /** One play; its track's callbacks count only while it is still [current]. */
    private inner class Play(val listener: WayVoicePlaybackListener) : WayVoiceTrack.Events {
        var track: WayVoiceTrack? = null
        var sounding = false

        override fun onEnded() = end(this) { it.onEnded() }

        override fun onFailed(beforeSound: Boolean) = end(this) { it.onFailed(beforeSound) }
    }

    internal companion object {
        private const val TAG = "WayVoicePlayer"

        /** iOS `seek(toFraction:)` clamps below the end (`WayVoicePlayer.swift:115-119@7c200bf`). */
        const val MAX_SEEK_FRACTION = 0.999

        private fun focusAttributes(): SystemAudioAttributes = SystemAudioAttributes.Builder()
            .setUsage(SystemAudioAttributes.USAGE_MEDIA)
            .setContentType(SystemAudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** One file's playback: the only part of the Way voice player that touches ExoPlayer. Main thread only. */
internal interface WayVoiceTrack {

    fun pause()

    fun resume()

    fun seekTo(fraction: Double)

    fun setRate(rate: Float)

    fun positionMillis(): Long

    /** Silences the track at once; it reports nothing after this. */
    fun release()

    /** At most one of these per track, on the main thread. */
    interface Events {
        fun onEnded()

        /** [beforeSound] while the file had not yet come ready to play. */
        fun onFailed(beforeSound: Boolean)
    }

    fun interface Factory {
        /** Starts [file] sounding at [volume] and [rate]. */
        fun start(file: File, volume: Float, rate: Float, events: Events): WayVoiceTrack
    }
}

internal class ExoPlayerWayVoiceTrack private constructor(
    private val exo: ExoPlayer,
    private val events: WayVoiceTrack.Events,
    private val mainHandler: Handler,
) : WayVoiceTrack, Player.Listener {

    private var done = false

    /** The file opened and decoded far enough to play: a failure from here on broke off mid-voice. */
    private var wasReady = false

    /** A scrub before the duration is known lands once the file is ready. */
    private var pendingSeekFraction: Double? = null

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                wasReady = true
                pendingSeekFraction?.let { fraction ->
                    pendingSeekFraction = null
                    seekTo(fraction)
                }
            }
            Player.STATE_ENDED -> report { events.onEnded() }
            else -> Unit
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.w(TAG, "a Way voice failed: ${error.errorCodeName}")
        report { events.onFailed(beforeSound = !wasReady) }
    }

    override fun pause() = exo.pause()

    override fun resume() = exo.play()

    override fun seekTo(fraction: Double) {
        val duration = exo.duration
        if (duration == C.TIME_UNSET || duration <= 0L) {
            pendingSeekFraction = fraction
            return
        }
        exo.seekTo((fraction * duration).toLong())
    }

    override fun setRate(rate: Float) = exo.setPlaybackSpeed(rate)

    override fun positionMillis(): Long = exo.currentPosition

    /** Released on a later turn: ExoPlayer must not be released inside its own listener callback. */
    override fun release() {
        if (done) return
        done = true
        exo.removeListener(this)
        exo.pause()
        mainHandler.post { exo.release() }
    }

    private fun report(event: () -> Unit) {
        if (done) return
        done = true
        event()
    }

    companion object {
        private const val TAG = "WayVoiceTrack"

        fun factory(context: Context): WayVoiceTrack.Factory {
            val handler = Handler(Looper.getMainLooper())
            return WayVoiceTrack.Factory { file, volume, rate, events ->
                val exo = ExoPlayer.Builder(context)
                    .setAudioAttributes(playerAttributes(), /* handleAudioFocus = */ false)
                    .build()
                val track = ExoPlayerWayVoiceTrack(exo, events, handler)
                exo.addListener(track)
                exo.setMediaItem(wayVoiceMediaItem(file))
                exo.volume = volume
                exo.setPlaybackSpeed(rate)
                exo.prepare()
                exo.play()
                track
            }
        }

        @VisibleForTesting
        internal fun playerAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        @VisibleForTesting
        internal fun wayVoiceMediaItem(file: File): MediaItem = MediaItem.fromUri(Uri.fromFile(file))
    }
}
