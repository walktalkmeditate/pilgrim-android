// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.seek

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.seek.SeekPreferencesRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository

/**
 * Suppression seam for the sonar ping (iOS
 * `SeekSoundPlayer.canPingOverCurrentAudio@c1745e8`): pings skip —
 * never queue, never duck — while a whisper or voice-guide prompt is
 * speaking, and while a talk recording is active so the mic capture is
 * never perturbed.
 *
 * U5 defines the seam; **U9 binds the real providers**:
 *  - whisper → `data/whisper/WhisperPlayer` (needs an any-channel
 *    is-playing surface; the current public `isPlaying` StateFlow
 *    tracks the preview channel only),
 *  - voice guide → `audio/voiceguide/VoiceGuidePlayer.state == Playing`,
 *  - talk recording → the walk UI's `voiceRecorderState is Recording`
 *    signal (no recorder-level singleton flow exists today).
 */
class SeekPingGate(
    private val isWhisperPlaying: () -> Boolean,
    private val isVoiceGuidePlaying: () -> Boolean,
    private val isTalkRecordingActive: () -> Boolean,
) {
    fun allowsPing(): Boolean =
        !isWhisperPlaying() && !isVoiceGuidePlaying() && !isTalkRecordingActive()
}

/**
 * Pocket guidance channel for Seek: the sonar ping and the reveal
 * bowl. Port of iOS `SeekSoundPlayer.swift@c1745e8`; full behavior
 * contract in `docs/parity/2026-07-14-port-seek-audio-u5.md`.
 *
 * Assets: `R.raw.seek_ping` / `R.raw.seek_bowl` are byte-identical
 * copies of the iOS bundle's `seek-ping.aac` / `seek-bowl.aac` —
 * CC0 third-party audio (ping: freesound.org/s/701071, bowl:
 * freesound.org/s/150453).
 *
 * Focus model (conscious divergence from iOS's `.playback +
 * .mixWithOthers` consumer, which neither ducks nor can be denied):
 * one `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` request held from
 * [prepare] to [stop] — the session-long-holder pattern from the
 * soundscape player — so sub-second pings don't duck-pump concurrent
 * audio at pulse cadence. Denied focus (e.g. phone call) skips the
 * play attempt AND the coupled haptic (no phantom buzz — BellPlayer
 * precedent).
 *
 * Interruption mapping (iOS drops its `AVAudioPlayer`s on `.began`
 * and lazily re-arms after `.ended` — players never auto-resume):
 *  - `AUDIOFOCUS_LOSS_TRANSIENT` → began: drop players, flag
 *    interrupted, keep the focus request (the OS returns GAIN).
 *  - `AUDIOFOCUS_GAIN` → ended: clear the flag; the next play re-arms.
 *  - `AUDIOFOCUS_LOSS` → permanent takeover (no GAIN ever follows):
 *    full stop so the next play can lazily re-activate with a fresh
 *    request instead of staying muted for the rest of the walk.
 *  - `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` → informational; the OS
 *    auto-ducks (`setWillPauseWhenDucked(false)`).
 *
 * Haptic coupling: the tick/aligned haptic rides each [playPing] call
 * (once per pulse, not per double-ping half). It fires when the ping
 * plays OR is skipped by policy (toggles, [SeekPingGate], interruption
 * flag) — iOS fires the pulse haptic regardless of what `playPing`
 * decided — but NOT when the platform rejected an actual attempt
 * (focus denied, arm/start failure), which is an Android-only failure
 * mode.
 *
 * Not Hilt-wired in U5: [SeekPingGate]'s real providers cross module
 * seams the engine-wiring unit (U9) owns.
 */
class SeekSoundPlayer(
    private val context: Context,
    private val audioManager: AudioManager,
    private val seekPreferences: SeekPreferencesRepository,
    private val soundsPreferences: SoundsPreferencesRepository,
    private val gate: SeekPingGate,
    private val haptics: SeekHaptics,
    private val doublePingGapMs: Long = DOUBLE_PING_GAP_MS,
    private val completionReleaseDelayMs: Long = COMPLETION_RELEASE_DELAY_MS,
    // Mirrors iOS's injected `makePlayer` seam so tests can count
    // plays on real MediaPlayer instances; production uses the default.
    private val playerFactory: () -> MediaPlayer = { MediaPlayer() },
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // All mutated under [lock]; the focus listener can fire on a
    // system-chosen thread.
    private var pingPlayer: MediaPlayer? = null
    private var bowlPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var isInterrupted = false

    // Two counters, mirroring iOS: `pingGeneration` guards the pending
    // aligned second (bumped by every playPing, stop, and interruption
    // began — `SeekSoundPlayer.swift:79,107,209@c1745e8`);
    // `releaseGeneration` guards the completion release (iOS keeps it
    // in the view model's `seekGeneration`,
    // `ActiveWalkViewModel+Seek.swift:165-172`) so pings and
    // interruptions can't strand a scheduled focus release.
    private var pingGeneration = 0L
    private var releaseGeneration = 0L

    private enum class PingOutcome { PLAYED, POLICY_SKIPPED, PLATFORM_REJECTED }

    /**
     * Activates the focus consumer and arms both players at seek start
     * so the first pulse plays without cold-start latency (iOS
     * `prepare()`). Focus denial or arm failure leaves the player
     * inactive; every later play lazily retries.
     */
    fun prepare() {
        synchronized(lock) {
            if (!activateSessionIfNeededLocked()) return
            armPingPlayerIfNeededLocked()
            armBowlPlayerIfNeededLocked()
            deactivateIfNothingArmedLocked()
        }
    }

    /**
     * `aligned` plays the bundled ping twice with a short
     * generation-guarded gap — any later request, [stop], or an
     * interruption cancels the pending second cleanly. `closeness`
     * (0 far → 1 near, the engine's shared curve) shapes the ping from
     * a whisper over the hill to a drop beside you:
     * volume = `sonarVolume × (0.55 + 0.45 × closeness)`.
     */
    fun playPing(aligned: Boolean, closeness: Float) {
        val clamped = SeekHaptics.clampCloseness(closeness)
        val volumeScale = PING_VOLUME_FLOOR + PING_VOLUME_RANGE * clamped
        val expected: Long
        val outcome: PingOutcome
        synchronized(lock) {
            pingGeneration += 1
            expected = pingGeneration
            if (!sonarAndSoundsEnabled()) {
                fireCoupledHaptic(aligned, clamped)
                return
            }
            outcome = firePingIfAllowedLocked(volumeScale)
        }
        if (outcome != PingOutcome.PLATFORM_REJECTED) fireCoupledHaptic(aligned, clamped)
        if (!aligned) return
        mainHandler.postDelayed(
            {
                synchronized(lock) {
                    if (pingGeneration != expected || !sonarAndSoundsEnabled()) return@postDelayed
                    firePingIfAllowedLocked(volumeScale)
                }
            },
            doublePingGapMs,
        )
    }

    /**
     * Reveal/completion tone. Part of the reveal ritual rather than
     * the sonar guidance channel, so it plays even when the sonar
     * toggle is off — only the master Sounds toggle (and an in-flight
     * interruption) silences it. U9 calls this on `revealedNext`.
     */
    fun playBowl() {
        synchronized(lock) {
            if (!soundsPreferences.soundsEnabled.value || isInterrupted) return
            if (!activateSessionIfNeededLocked()) return
            armBowlPlayerIfNeededLocked()
            val player = bowlPlayer ?: run {
                deactivateIfNothingArmedLocked()
                return
            }
            startPlayerLocked(player, volumeScale = 1f)
        }
    }

    /**
     * `seekComplete` bowl: rings the bowl, then releases the audio
     * consumer once it has had room to ring (iOS
     * `seekCompleteSoundStopDelay = 4.5` — "slightly longer than the
     * completion bowl's ~4 s ring so releasing the audio consumer
     * never clips it") instead of holding focus for the whole walk
     * home. Generation-guarded; [stop] stays idempotent so the regular
     * teardown at walk end is safe.
     */
    fun playCompletionBowl() {
        playBowl()
        val expected: Long
        synchronized(lock) {
            releaseGeneration += 1
            expected = releaseGeneration
        }
        mainHandler.postDelayed(
            {
                synchronized(lock) {
                    if (releaseGeneration == expected) stopLocked()
                }
            },
            completionReleaseDelayMs,
        )
    }

    /** Drops both players and releases the focus consumer. Idempotent. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    // ─── Ping gating ──────────────────────────────────────────────────

    private fun sonarAndSoundsEnabled(): Boolean =
        seekPreferences.sonarEnabled.value && soundsPreferences.soundsEnabled.value

    private fun firePingIfAllowedLocked(volumeScale: Float): PingOutcome {
        if (isInterrupted || !gate.allowsPing()) return PingOutcome.POLICY_SKIPPED
        if (!activateSessionIfNeededLocked()) return PingOutcome.PLATFORM_REJECTED
        armPingPlayerIfNeededLocked()
        val player = pingPlayer ?: run {
            deactivateIfNothingArmedLocked()
            return PingOutcome.PLATFORM_REJECTED
        }
        return if (startPlayerLocked(player, volumeScale)) {
            PingOutcome.PLAYED
        } else {
            PingOutcome.PLATFORM_REJECTED
        }
    }

    private fun fireCoupledHaptic(aligned: Boolean, closeness: Float) {
        if (aligned) haptics.aligned(closeness) else haptics.tick(closeness)
    }

    // ─── Players ──────────────────────────────────────────────────────

    private fun startPlayerLocked(player: MediaPlayer, volumeScale: Float): Boolean {
        val rawVolume = seekPreferences.sonarVolume.value
        val prefVolume = if (rawVolume.isNaN()) 0f else rawVolume.coerceIn(0f, 1f)
        val volume = (prefVolume * volumeScale).coerceIn(0f, 1f)
        return try {
            player.setVolume(volume, volume)
            player.seekTo(0)
            player.start()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "play failed — resetting", t)
            resetAfterFailureLocked()
            false
        }
    }

    private fun resetAfterFailureLocked() {
        releasePlayersLocked()
        abandonFocusLocked()
    }

    private fun armPingPlayerIfNeededLocked() {
        if (pingPlayer == null) pingPlayer = armedPlayer(R.raw.seek_ping)
    }

    private fun armBowlPlayerIfNeededLocked() {
        if (bowlPlayer == null) bowlPlayer = armedPlayer(R.raw.seek_bowl)
    }

    /**
     * Manual `MediaPlayer()` → `setAudioAttributes` → `setDataSource`
     * → `prepare()` ordering — `MediaPlayer.create()` would prepare
     * first, silently dropping the attributes (BellPlayer precedent).
     * The armed player is reused across pings (`seekTo(0)` + `start()`
     * per play); it is only released by [stop], an interruption, or a
     * failure reset.
     */
    private fun armedPlayer(resId: Int): MediaPlayer? {
        var player: MediaPlayer? = null
        return try {
            val p = playerFactory()
            player = p
            p.setAudioAttributes(audioAttrs)
            val afd = context.resources.openRawResourceFd(resId) ?: run {
                Log.w(TAG, "seek resource file descriptor null")
                runCatching { p.release() }
                return null
            }
            afd.use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            p.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                // Never release synchronously from a player callback —
                // post the reset to the main handler (house rule).
                mainHandler.post {
                    synchronized(lock) {
                        if (pingPlayer === mp || bowlPlayer === mp) resetAfterFailureLocked()
                    }
                }
                true
            }
            p.prepare()
            p
        } catch (t: Throwable) {
            Log.w(TAG, "player arm failed", t)
            player?.let { stale -> runCatching { stale.release() } }
            null
        }
    }

    private fun releasePlayersLocked() {
        val stale = listOfNotNull(pingPlayer, bowlPlayer)
        pingPlayer = null
        bowlPlayer = null
        if (stale.isEmpty()) return
        // The focus listener fires on a system-chosen thread; releasing
        // via the main handler keeps every release path off callback
        // stacks (house rule) and off the caller's hot path.
        mainHandler.post {
            stale.forEach { player ->
                runCatching { player.stop() }
                runCatching { player.release() }
            }
        }
    }

    // ─── Session (audio-focus consumer) ───────────────────────────────

    private fun activateSessionIfNeededLocked(): Boolean {
        if (focusRequest != null) return true
        // Defensive: any previously-held request is abandoned before a
        // new one is built (Stage 5-F house rule — idempotent no-op
        // when nothing is held).
        abandonFocusLocked()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttrs)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change -> handleFocusChange(change) }
            .build()
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            focusRequest = request
        } else {
            Log.w(TAG, "seek focus denied; skipping play")
        }
        return granted
    }

    private fun deactivateIfNothingArmedLocked() {
        if (pingPlayer == null && bowlPlayer == null) abandonFocusLocked()
    }

    private fun abandonFocusLocked() {
        val request = focusRequest ?: return
        focusRequest = null
        try {
            audioManager.abandonAudioFocusRequest(request)
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusRequest failed", t)
        }
    }

    private fun stopLocked() {
        pingGeneration += 1
        releaseGeneration += 1
        isInterrupted = false
        releasePlayersLocked()
        abandonFocusLocked()
    }

    private fun handleFocusChange(change: Int) {
        synchronized(lock) {
            if (focusRequest == null) return
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    isInterrupted = true
                    pingGeneration += 1
                    releasePlayersLocked()
                }
                AudioManager.AUDIOFOCUS_GAIN -> isInterrupted = false
                AudioManager.AUDIOFOCUS_LOSS -> stopLocked()
                // CAN_DUCK is informational: setWillPauseWhenDucked(false)
                // tells the OS to auto-duck this player (BellPlayer
                // precedent) — doing nothing is correct.
                else -> Unit
            }
        }
    }

    internal companion object {
        const val TAG = "SeekSoundPlayer"

        // iOS `SeekSoundPlayer.swift:81@c1745e8`:
        // volumeScale = 0.55 + 0.45 × clamp(closeness).
        const val PING_VOLUME_FLOOR = 0.55f
        const val PING_VOLUME_RANGE = 0.45f

        // iOS `doublePingGap = 0.25` (`SeekSoundPlayer.swift:46@c1745e8`).
        const val DOUBLE_PING_GAP_MS = 250L

        // iOS `seekCompleteSoundStopDelay = 4.5`
        // (`ActiveWalkViewModel+Seek.swift:29@c1745e8`).
        const val COMPLETION_RELEASE_DELAY_MS = 4_500L
    }
}
