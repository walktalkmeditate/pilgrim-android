// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import android.os.Handler
import android.os.SystemClock

/**
 * The soundscape's volume, decided on [handler]'s looper: the walker's
 * level, the dip under another consumer's may-duck request, and a Way
 * voice's duck ([WayVoiceSoundscapeDuck]). With no Way voice it does what
 * the player always did: the walker's level, dipped at once to
 * [DUCK_FRACTION] of it and restored at once.
 *
 * A Way voice's duck is iOS's: an absolute level ramped over
 * [DUCK_RAMP_MS] and held, while may-duck changes only record themselves
 * (the Way voice's own focus request causes one). A may-duck change during
 * a ramp re-aims the ramp instead of jumping, so the regain that follows
 * the Way voice giving up its focus never cuts a restore short.
 */
internal class SoundscapeLevel(
    private val handler: Handler,
    private val knob: Knob,
) {

    /** The player's volume, when there is a player. Called on [handler]'s looper. */
    interface Knob {
        fun get(): Float?

        fun set(volume: Float)
    }

    /** The walker's level, from the soundscape volume preference. */
    @Volatile
    var userVolume: Float = FULL_VOLUME
        private set

    /** iOS `targetVolume`: the walker's level, or a Way voice's duck or restore. */
    @Volatile
    var targetVolume: Float = FULL_VOLUME
        private set

    private var mayDucked = false
    private var wayVoiceHold = false
    private var ramp: Ramp? = null

    /** Any thread. As before, it lands at once and ignores a may-duck dip in force. */
    fun setUserVolume(volume: Float) {
        userVolume = volume
        targetVolume = volume
        handler.post {
            cancelRamp()
            knob.set(volume)
        }
    }

    /**
     * A soundscape starts, at the walker's level: iOS `play(_:volume:)`
     * overwrites the target whatever duck a Way voice holds (iOS C-D4,
     * pilgrim-ios #104, `SoundscapePlayer.swift:68-70@7c200bf`).
     */
    fun onPlay() {
        targetVolume = userVolume
        wayVoiceHold = false
        cancelRamp()
        knob.set(userVolume)
    }

    /** The focus was granted afresh or given up: no other consumer's duck is in force. */
    fun onFocusReset() {
        mayDucked = false
    }

    /** Another consumer's may-duck request began, or ended with the regain. */
    fun onMayDuck(ducked: Boolean) {
        mayDucked = ducked
        if (wayVoiceHold) return
        val level = levelFor(targetVolume)
        if (ramp != null) rampTo(level) else knob.set(level)
    }

    /** Any thread. With no player, only [targetVolume] changes, as on iOS. */
    fun holdDuck(level: Float) {
        targetVolume = level
        handler.post {
            wayVoiceHold = true
            rampTo(levelFor(level))
        }
    }

    /** Any thread. */
    fun releaseDuck(level: Float) {
        targetVolume = level
        handler.post {
            wayVoiceHold = false
            rampTo(levelFor(level))
        }
    }

    fun cancelRamp() {
        ramp?.let(handler::removeCallbacks)
        ramp = null
    }

    private fun levelFor(target: Float): Float =
        if (mayDucked && !wayVoiceHold) (target * DUCK_FRACTION).coerceIn(0f, 1f) else target

    /** From wherever the volume is now, as iOS's `setVolume(_:fadeDuration:)` replacing a fade does. */
    private fun rampTo(level: Float) {
        cancelRamp()
        val from = knob.get() ?: return
        val next = Ramp(from = from, to = level, startedAt = SystemClock.uptimeMillis())
        ramp = next
        handler.postDelayed(next, RAMP_STEP_MS)
    }

    private inner class Ramp(val from: Float, val to: Float, val startedAt: Long) : Runnable {
        override fun run() {
            if (ramp !== this) return
            val progress = ((SystemClock.uptimeMillis() - startedAt).toFloat() / DUCK_RAMP_MS).coerceIn(0f, 1f)
            knob.set(from + (to - from) * progress)
            if (progress < 1f) {
                handler.postDelayed(this, RAMP_STEP_MS)
            } else {
                ramp = null
            }
        }
    }

    companion object {
        /**
         * The walker's level before the orchestrator applies the
         * preference (0.4 by default, iOS parity) ahead of the first play.
         */
        const val FULL_VOLUME = 1.0f

        /**
         * The dip under another consumer's `GAIN_TRANSIENT_MAY_DUCK` (the
         * voice guide, a whisper, another app), relative to the walker's
         * level so a quiet soundscape isn't whip-sawed up first.
         */
        const val DUCK_FRACTION = 0.3f

        /** iOS `setVolume(_, animated: true)` fades over 0.5 s (`SoundscapePlayer.swift:117-125@7c200bf`). */
        const val DUCK_RAMP_MS = 500L

        const val RAMP_STEP_MS = 20L
    }
}
