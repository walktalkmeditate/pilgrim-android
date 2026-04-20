// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.R

/**
 * Plays the bundled temple-bell asset once per [play] call. Used by
 * [MeditationBellObserver] at the boundaries of a meditation session.
 *
 * Creates a fresh [MediaPlayer] per play (~50–150ms setup overhead,
 * imperceptible at tap-paced bell moments) via
 * [MediaPlayer.create]. Requests ducking audio focus via
 * [AudioFocusCoordinator.requestBellDucking] so a concurrent
 * music/podcast briefly attenuates rather than pauses. Releases the
 * player and abandons focus on completion — a safety-net timeout
 * guarantees cleanup even if the completion callback doesn't fire
 * (MediaPlayer's reliability on some devices is uneven).
 *
 * See `docs/superpowers/specs/2026-04-20-stage-5b-temple-bell-design.md`.
 */
@Singleton
class BellPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFocus: AudioFocusCoordinator,
) : BellPlaying {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun play() {
        val granted = audioFocus.requestBellDucking(onLossListener = {
            Log.i(TAG, "bell focus lost")
        })
        if (!granted) {
            Log.w(TAG, "bell focus denied; skipping play")
            return
        }
        val player = try {
            MediaPlayer.create(context, R.raw.bell) ?: run {
                Log.w(TAG, "MediaPlayer.create returned null")
                audioFocus.abandon()
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer creation failed", t)
            audioFocus.abandon()
            return
        }
        // Route through the media stream (loudspeaker, not earpiece),
        // marked as a sonification cue (not speech, not music).
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        // Single cleanup path — both the natural completion callback,
        // the error callback, and the safety-net timeout route through
        // `cleanup`, which is idempotent via the `cleanedUp` guard.
        var cleanedUp = false
        val cleanup = {
            if (!cleanedUp) {
                cleanedUp = true
                try {
                    player.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "MediaPlayer release failed", t)
                }
                audioFocus.abandon()
            }
        }
        player.setOnCompletionListener { cleanup() }
        player.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
            cleanup()
            true
        }
        // Safety net: if onCompletion / onError don't fire within the
        // bell's duration plus a generous margin, force-release so we
        // don't leak either the MediaPlayer or the audio-focus request.
        mainHandler.postDelayed({ cleanup() }, SAFETY_NET_MS)
        try {
            player.start()
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer start failed", t)
            cleanup()
        }
    }

    private companion object {
        const val TAG = "BellPlayer"
        // Bell asset is 3.0s; 5000ms provides a generous margin for
        // the natural onCompletion callback. If it hasn't fired by
        // then, force-release.
        const val SAFETY_NET_MS = 5_000L
    }
}
