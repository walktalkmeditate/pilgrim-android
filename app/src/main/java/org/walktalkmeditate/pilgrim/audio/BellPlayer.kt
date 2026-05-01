// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository

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
    private val soundsPreferences: SoundsPreferencesRepository,
    private val vibrator: Vibrator,
) : BellPlaying {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun play() {
        playInternal(scale = 1.0f)
    }

    override fun play(scale: Float) {
        playInternal(scale = scale)
    }

    /**
     * iOS-faithful bell + haptic. iOS fires `.medium` impact haptic
     * AFTER the bell audio starts (BellPlayer.swift:26,30). Mirrored
     * here by calling [playInternal] (which ends with `MediaPlayer.start()`)
     * and only THEN firing the haptic.
     *
     * The haptic is gated on:
     *  1. Caller intent: [withHaptic] must be `true`. Sites that
     *     deliberately don't want haptic (milestone overlay) opt out
     *     by passing `withHaptic = false`.
     *  2. User preference: [SoundsPreferencesRepository.bellHapticEnabled].
     *     Even when the caller asks for haptic, a user who has flipped
     *     the bell-haptic toggle off stays haptic-silent.
     *
     * Stage 12-C: see Item C of `2026-04-30-stage-12-…-design.md`.
     */
    override fun play(scale: Float, withHaptic: Boolean) {
        // Audio-success gate matches iOS BellPlayer.swift:21-35: iOS only
        // fires the haptic AFTER `p.play()` succeeds; if AVAudioPlayer
        // throws (focus denied, file missing, other-app holding exclusive
        // audio), control jumps to the catch and the haptic skips.
        // Without this gate Android emits a "phantom haptic" with no
        // audio when focus is denied (e.g. user is on a phone call).
        val audioStarted = playInternal(scale = scale)
        if (audioStarted && withHaptic && soundsPreferences.bellHapticEnabled.value) {
            fireMediumImpact()
        }
    }

    /**
     * Returns `true` only when [MediaPlayer.start] returned successfully.
     * Used by [play] (scale, withHaptic) to gate the haptic on audio
     * success — iOS `BellPlayer.swift:21-35` only fires haptic AFTER
     * `p.play()` succeeds; if the AVAudioPlayer throws, the haptic
     * skips. Without this gate Android would emit a "phantom haptic"
     * when audio focus is denied (e.g. user is on a phone call).
     */
    private fun playInternal(scale: Float): Boolean {
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
        //
        // [playerRef] and [safetyNetRef] are populated AFTER focus is
        // granted and the MediaPlayer is built. The [cleanup] lambda
        // null-checks both so it's safe to invoke any time after
        // `cleanupRef.set(cleanup)`: if focus was rejected and the
        // listener fires synchronously, both refs are null and cleanup
        // only abandons the focus request. Once the player is
        // constructed, `playerRef.set(player)` makes it eligible for
        // release on the next cleanup call. Same for the safety-net.
        val cleanedUp = AtomicBoolean(false)
        val cleanupRef = AtomicReference<(() -> Unit)?>(null)
        val playerRef = AtomicReference<MediaPlayer?>(null)
        val safetyNetRef = AtomicReference<Runnable?>(null)

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(bellAttributes)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                // `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` is deliberately
                // NOT in the stop-cases list. `setWillPauseWhenDucked(false)`
                // above (the default) tells the OS to auto-duck this
                // player's volume for transient focus loss — rather
                // than converting the duckable loss to a full
                // `LOSS_TRANSIENT` that the app must react to. The
                // listener receives `CAN_DUCK` as informational; the
                // OS handles the volume reduction automatically.
                // For a 3-second bell, doing nothing is correct: the
                // OS is already attenuating. Treating CAN_DUCK as a
                // full stop would both silence the bell unnecessarily
                // when another app nearby requests ducking focus and
                // contradict the declared request mode.
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                    focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    Log.i(TAG, "bell focus lost — stopping bell")
                    cleanupRef.get()?.invoke()
                }
            }
            .build()

        // Build the cleanup lambda BEFORE requesting focus. The OS may
        // call back the focus-loss listener (or reject the request)
        // between `requestAudioFocus()` returning and our subsequent
        // setup; if `cleanupRef` were null at that moment, the listener
        // would no-op, leaving the focus request and any in-flight
        // MediaPlayer to leak until the safety-net fires 5s later.
        val cleanup: () -> Unit = {
            if (cleanedUp.compareAndSet(false, true)) {
                safetyNetRef.get()?.let { mainHandler.removeCallbacks(it) }
                playerRef.get()?.let { mp ->
                    try {
                        mp.release()
                    } catch (t: Throwable) {
                        Log.w(TAG, "MediaPlayer release failed", t)
                    }
                }
                try {
                    audioManager.abandonAudioFocusRequest(focusRequest)
                } catch (t: Throwable) {
                    Log.w(TAG, "abandonAudioFocusRequest failed", t)
                }
            }
        }
        cleanupRef.set(cleanup)

        val granted = audioManager.requestAudioFocus(focusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            Log.w(TAG, "bell focus denied; skipping play")
            // Idempotent: cleanup abandons the (possibly already
            // rejected) focus request and short-circuits via the
            // cleanedUp guard if the listener happened to race here.
            cleanup()
            return false
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
        playerRef.set(player)

        // Focus could have been revoked between requestAudioFocus returning
        // GRANTED and now (the listener fires on a system thread). If cleanup
        // already ran via the listener, playerRef was null at that moment so
        // the MediaPlayer never got released. Re-check now and release.
        if (cleanedUp.get()) {
            try {
                player.release()
            } catch (t: Throwable) {
                Log.w(TAG, "MediaPlayer release after racing cleanup failed", t)
            }
            return false
        }

        try {
            player.setAudioAttributes(bellAttributes)
            val afd = context.resources.openRawResourceFd(R.raw.bell) ?: run {
                Log.w(TAG, "bell resource file descriptor null")
                cleanup()
                return false
            }
            afd.use {
                player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            player.prepare()
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer setup failed", t)
            cleanup()
            return false
        }

        // Second re-check: focus revoke can fire any time during the
        // synchronous setAudioAttributes/setDataSource/prepare block
        // above (the listener runs on a system thread). If cleanup
        // already ran via the listener, it released the player via
        // `playerRef.get()` (which is now non-null since line 174) and
        // marked cleanedUp. We must not wire listeners + post the
        // safety-net + start() against a released MediaPlayer — start()
        // would throw IllegalStateException, caught below, but the
        // listener+handler wiring would briefly pin a released-player
        // reference. Cheaper to bail here.
        if (cleanedUp.get()) return false

        // Per-play safety-net runnable captured by reference so
        // `cleanup` can remove it and avoid a stale firing after
        // natural completion.
        val safetyNet = Runnable { cleanupRef.get()?.invoke() }
        safetyNetRef.set(safetyNet)

        // Wrap listener wiring + safetyNet posting in try/catch:
        // focus revoke can still fire on a system thread between the
        // re-check above and these lines, releasing the MediaPlayer.
        // setOnCompletionListener / setOnErrorListener on a player in
        // the End state throw IllegalStateException — without this
        // catch, the exception would propagate to the caller (Main
        // thread, milestone overlay's onMilestoneShown) and crash the
        // process. cleanup() in the catch is idempotent.
        try {
            player.setOnCompletionListener { cleanup() }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                cleanup()
                true
            }
            mainHandler.postDelayed(safetyNet, SAFETY_NET_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer listener wiring failed", t)
            cleanup()
            return false
        }

        // Apply user's bellVolume preference (Eagerly StateFlow — `.value`
        // is the current cached pref, no suspend hop needed). MediaPlayer's
        // setVolume is legal in the Prepared state per the docs, but
        // calling it AFTER start() would briefly play at 1.0f before
        // the volume change lands; setting it here avoids that flash.
        //
        // Defensive: `coerceIn` does NOT sanitize NaN
        // (`Float.NaN.coerceIn(0f, 1f) == NaN`). The repository's
        // `setBellVolume` does not currently clamp, so a future bug
        // could persist NaN and reach this read. Treat NaN as silence —
        // safer than letting NaN propagate into `MediaPlayer.setVolume`.
        // Same handling applied to [scale] for the same reason.
        // [scale] multiplies the user pref so the milestone overlay
        // (scale=0.4) can mirror iOS's volume-0.4 milestone bell while
        // still respecting Android's user-volume control: a muted user
        // (bellVolume=0) stays muted because 0 × anything = 0.
        val rawBellVolume = soundsPreferences.bellVolume.value
        val userBellVolume = if (rawBellVolume.isNaN()) 0f else rawBellVolume.coerceIn(0f, 1f)
        val safeScale = if (scale.isNaN()) 0f else scale.coerceIn(0f, 1f)
        val effectiveVolume = (safeScale * userBellVolume).coerceIn(0f, 1f)
        try {
            player.setVolume(effectiveVolume, effectiveVolume)
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer setVolume failed", t)
            // Non-fatal: the bell will still play at the player's default
            // volume. Continue to start().
        }

        return try {
            player.start()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer start failed", t)
            cleanup()
            false
        }
    }

    /**
     * Mirror iOS's `.medium` impact haptic. Two-tier strategy:
     *
     *  1. **API 30+ (R)**: probe `areAllPrimitivesSupported(PRIMITIVE_CLICK)`.
     *     If supported, build a single-PRIMITIVE_CLICK composition and
     *     vibrate it — closest tactile match to iOS .medium on devices
     *     with primitive-aware vibrators (most modern OEMs since 2021).
     *  2. **API 28–29 (P/Q) OR API 30+ without PRIMITIVE_CLICK**: fall
     *     through to a 30 ms `createOneShot` with `DEFAULT_AMPLITUDE`
     *     — empirically the closest one-shot match to iOS .medium and
     *     short enough that older actuators don't smear it.
     *
     * Probe + fallback both wrapped: a buggy OEM that reports
     * `PRIMITIVE_CLICK` supported but rejects compose() at runtime
     * silently degrades to the one-shot rather than killing the bell
     * call. Logged at WARN so device QA can surface the failure.
     */
    private fun fireMediumImpact() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                try {
                    val composition = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                        .compose()
                    vibrator.vibrate(composition)
                    return
                } catch (t: Throwable) {
                    Log.w(TAG, "primitive composition failed; falling back to waveform", t)
                }
            }
            fireWaveformFallback()
        } else {
            fireWaveformFallback()
        }
    }

    private fun fireWaveformFallback() {
        try {
            // minSdk = 28; createOneShot is API 26+, so always available.
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    HAPTIC_ONESHOT_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "vibrator oneshot failed", t)
        }
    }

    private companion object {
        const val TAG = "BellPlayer"
        // Bell asset is 3.0s; 5000ms leaves a generous margin for
        // the natural onCompletion callback. If it hasn't fired by
        // then, force-release so neither the MediaPlayer nor the
        // audio-focus request leaks.
        const val SAFETY_NET_MS = 5_000L

        // 30 ms is short enough to feel tap-like (matches iOS .medium
        // tactile envelope) without smearing on slow OEM actuators.
        const val HAPTIC_ONESHOT_MS = 30L
    }
}
