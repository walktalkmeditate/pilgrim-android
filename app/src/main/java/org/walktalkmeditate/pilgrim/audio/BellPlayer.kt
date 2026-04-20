// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.R

/**
 * Plays the bundled temple-bell asset once per [play] call. Used by
 * [MeditationBellObserver] at the boundaries of a meditation session.
 *
 * **Focus lifecycle is scoped per-play, not shared with the
 * [AudioFocusCoordinator].** The coordinator enforces a single-owner
 * invariant across voice-recording and voice-playback concerns, which
 * doesn't match the bell's use case: two bells can legitimately be
 * in flight (user taps Meditate, taps Done a few seconds later),
 * each with its own `AudioFocusRequest` and `MediaPlayer`. Sharing
 * the coordinator would let Player A's cleanup accidentally abandon
 * Player B's focus when A's safety-net timer fires after B has
 * already taken over. Each `play()` builds and manages its own
 * focus request + player, so the two lifecycles never alias.
 *
 * Per-play steps:
 *  1. Build an [AudioFocusRequest] with `USAGE_MEDIA +
 *     CONTENT_TYPE_SONIFICATION + AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`
 *     so concurrent music/podcast briefly attenuates rather than
 *     pauses.
 *  2. Request focus from [AudioManager]. If denied, no-op.
 *  3. Build a [MediaPlayer] manually: `MediaPlayer()` →
 *     `setAudioAttributes(...)` → `setDataSource(rawResourceFd)` →
 *     `prepare()`. This ordering is REQUIRED: `MediaPlayer.create()`
 *     would call `prepare()` internally, after which
 *     `setAudioAttributes` is silently ignored per the Android docs.
 *     (Robolectric's stub doesn't enforce the state machine, which is
 *     why an initial implementation using `create()` passed tests but
 *     would have played without the intended attributes on-device.)
 *  4. Wire completion / error / safety-net cleanup. All three paths
 *     converge through the same idempotent [AtomicBoolean]-guarded
 *     `cleanup` lambda that releases the player, abandons the
 *     per-play focus request, and removes the Handler callback.
 *  5. Start the player.
 *
 * The focus-loss callback (`onLossListener` on the AudioFocusRequest)
 * routes directly into `cleanup` so an incoming phone call stops the
 * bell immediately rather than letting it continue through the ring.
 *
 * See `docs/superpowers/specs/2026-04-20-stage-5b-temple-bell-design.md`.
 */
@Singleton
class BellPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioManager: AudioManager,
) : BellPlaying {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun play() {
        val bellAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // Declared up front so the focus-loss listener can reach the
        // per-play cleanup via a forward reference. The listener fires
        // on a system-chosen thread (possibly Main, possibly a binder
        // thread — Android docs don't guarantee), so the publication
        // of `cleanupRef` must cross threads safely. `AtomicReference`
        // provides the happens-before edge that a plain mutable field
        // wouldn't under the JMM. The AtomicBoolean guard below then
        // dedupes concurrent invocations.
        val cleanedUp = AtomicBoolean(false)
        val cleanupRef = AtomicReference<(() -> Unit)?>(null)

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(bellAttributes)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                    focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) {
                    Log.i(TAG, "bell focus lost — stopping bell")
                    cleanupRef.get()?.invoke()
                }
            }
            .build()

        val granted = audioManager.requestAudioFocus(focusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            Log.w(TAG, "bell focus denied; skipping play")
            return
        }

        // Build MediaPlayer manually so `setAudioAttributes` runs
        // BEFORE `prepare()`. Using `MediaPlayer.create()` here would
        // leave the player in the Prepared state on return, at which
        // point `setAudioAttributes` is a no-op per Android docs —
        // the bell's routing/content-type hints would be silently
        // lost, and on devices where routing depends on those
        // hints the bell could play through the earpiece or miss
        // the sonification ducking policy.
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(bellAttributes)
            val afd = context.resources.openRawResourceFd(R.raw.bell) ?: run {
                Log.w(TAG, "bell resource file descriptor null")
                player.release()
                audioManager.abandonAudioFocusRequest(focusRequest)
                return
            }
            afd.use {
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            player.prepare()
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer setup failed", t)
            try {
                player.release()
            } catch (r: Throwable) {
                Log.w(TAG, "MediaPlayer release after setup failure failed", r)
            }
            audioManager.abandonAudioFocusRequest(focusRequest)
            return
        }

        // Per-play safety-net runnable captured by reference so
        // `cleanup` can remove it and avoid a stale firing after
        // natural completion.
        val safetyNet = Runnable { cleanupRef.get()?.invoke() }

        val cleanup: () -> Unit = {
            if (cleanedUp.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(safetyNet)
                try {
                    player.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "MediaPlayer release failed", t)
                }
                try {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                } catch (t: Throwable) {
                    Log.w(TAG, "abandonAudioFocusRequest failed", t)
                }
            }
        }
        cleanupRef.set(cleanup)

        player.setOnCompletionListener { cleanup() }
        player.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
            cleanup()
            true
        }
        mainHandler.postDelayed(safetyNet, SAFETY_NET_MS)

        try {
            player.start()
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer start failed", t)
            cleanup()
        }
    }

    private companion object {
        const val TAG = "BellPlayer"
        // Bell asset is 3.0s; 5000ms leaves a generous margin for
        // the natural onCompletion callback. If it hasn't fired by
        // then, force-release so neither the MediaPlayer nor the
        // audio-focus request leaks.
        const val SAFETY_NET_MS = 5_000L
    }
}
