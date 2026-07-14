// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.seek

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * The four-part seek haptic vocabulary (plus the dormant fifth),
 * ported from iOS `HapticManager.swift:92-96,229-287@c1745e8`:
 *
 *  - [tick] — one soft transient per sonar pulse, intensity
 *    `0.3 + 0.25 × closeness` ("a pocket metronome felt every pulse
 *    for up to hours, not an alert").
 *  - [aligned] — the "this way" heartbeat: two soft transients 0.18 s
 *    apart at `0.4 + 0.3 × closeness`.
 *  - [arrival] — three rising soft taps at 0 / 0.16 / 0.34 s,
 *    intensities 0.4 → 0.55 → 0.7.
 *  - [breathIn] — slow soft swell for the stillness window: two 0.9 s
 *    continuous segments rising 0.15 → 0.35.
 *  - [breathOut] — the falling mirror of [breathIn]. **Dormant**: iOS
 *    defines it in the vocabulary with no production call site at
 *    c1745e8; mirrored so the vocabularies stay congruent.
 *
 * Delivery tiers per pattern family:
 *  - Tap patterns (tick/aligned/arrival): API 30+ with
 *    `PRIMITIVE_TICK` supported → `VibrationEffect.Composition`
 *    (closest match to iOS's soft low-sharpness transients);
 *    otherwise an amplitude waveform with identical onsets. Devices
 *    without amplitude control play the waveform at fixed strength —
 *    acceptable for 30 ms taps (BellPlayer precedent).
 *  - Breath patterns: always an amplitude waveform (compositions
 *    cannot express a 1.8 s envelope). Without amplitude control the
 *    fallback is a single soft tap — mirroring iOS's own
 *    `UIImpactFeedbackGenerator(.soft)` fallback rather than a 1.8 s
 *    fixed-strength rattle.
 *
 * Deliberate Android divergence: iOS gates seek haptics on the app
 * being foregrounded because iOS discards background CoreHaptics
 * (`ActiveWalkViewModel+Seek.swift:23-25@c1745e8`). Android's
 * [Vibrator] works with the screen off, and screen-off pocket walks
 * are the primary seek use case — so this class carries **no
 * foreground/lifecycle dependency** and fires regardless of app
 * state.
 */
@Singleton
class SeekHaptics @Inject constructor(
    private val vibrator: Vibrator,
) {
    fun tick(closeness: Float) {
        val scale = TICK_FLOOR + TICK_RANGE * clampCloseness(closeness)
        fireTaps(listOf(Tap(onsetGapMs = 0L, scale = scale)))
    }

    fun aligned(closeness: Float) {
        val scale = ALIGNED_FLOOR + ALIGNED_RANGE * clampCloseness(closeness)
        fireTaps(
            listOf(
                Tap(onsetGapMs = 0L, scale = scale),
                Tap(onsetGapMs = ALIGNED_GAP_MS, scale = scale),
            ),
        )
    }

    fun arrival() {
        fireTaps(
            listOf(
                Tap(onsetGapMs = 0L, scale = ARRIVAL_SCALE_FIRST),
                Tap(onsetGapMs = ARRIVAL_GAP_FIRST_MS, scale = ARRIVAL_SCALE_SECOND),
                Tap(onsetGapMs = ARRIVAL_GAP_SECOND_MS, scale = ARRIVAL_SCALE_THIRD),
            ),
        )
    }

    fun breathIn() = fireBreath(rising = true)

    /** Dormant iOS-parity mirror — no production caller at c1745e8. */
    fun breathOut() = fireBreath(rising = false)

    /** [onsetGapMs] is the gap between this tap's onset and the previous one's. */
    private data class Tap(val onsetGapMs: Long, val scale: Float)

    private fun fireTaps(taps: List<Tap>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
        ) {
            try {
                val composition = VibrationEffect.startComposition()
                taps.forEach { tap ->
                    composition.addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                        tap.scale,
                        tap.onsetGapMs.toInt(),
                    )
                }
                vibrator.vibrate(composition.compose())
                return
            } catch (t: Throwable) {
                Log.w(TAG, "primitive composition failed; falling back to waveform", t)
            }
        }
        fireTapWaveform(taps)
    }

    private fun fireTapWaveform(taps: List<Tap>) {
        try {
            if (taps.size == 1) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(TAP_MS, amplitudeFor(taps[0].scale)),
                )
                return
            }
            val timings = mutableListOf(TAP_MS)
            val amplitudes = mutableListOf(amplitudeFor(taps[0].scale))
            taps.drop(1).forEach { tap ->
                timings += (tap.onsetGapMs - TAP_MS).coerceAtLeast(0L)
                amplitudes += 0
                timings += TAP_MS
                amplitudes += amplitudeFor(tap.scale)
            }
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    timings.toLongArray(),
                    amplitudes.toIntArray(),
                    NO_REPEAT,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "tap waveform failed", t)
        }
    }

    private fun fireBreath(rising: Boolean) {
        val quiet = amplitudeFor(BREATH_QUIET_SCALE)
        val full = amplitudeFor(BREATH_FULL_SCALE)
        val amplitudes = if (rising) intArrayOf(quiet, full) else intArrayOf(full, quiet)
        try {
            if (vibrator.hasAmplitudeControl()) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(BREATH_SEGMENT_MS, BREATH_SEGMENT_MS),
                        amplitudes,
                        NO_REPEAT,
                    ),
                )
            } else {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(TAP_MS, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "breath waveform failed", t)
        }
    }

    internal companion object {
        const val TAG = "SeekHaptics"

        // NaN biases to the quiet floor — the least intrusive defensive choice.
        fun clampCloseness(closeness: Float): Float =
            if (closeness.isNaN()) 0f else closeness.coerceIn(0f, 1f)

        /** iOS intensity (0..1) → vibrator amplitude (1..255). */
        fun amplitudeFor(scale: Float): Int =
            (scale * MAX_AMPLITUDE).roundToInt().coerceIn(1, MAX_AMPLITUDE)

        // iOS `playSeekTick` @c1745e8: 0.3 + 0.25 × closeness.
        const val TICK_FLOOR = 0.3f
        const val TICK_RANGE = 0.25f

        // iOS `playSeekAligned` @c1745e8: 0.4 + 0.3 × closeness, 0.18 s apart.
        const val ALIGNED_FLOOR = 0.4f
        const val ALIGNED_RANGE = 0.3f
        const val ALIGNED_GAP_MS = 180L

        // iOS `playSeekArrival` @c1745e8: (0, 0.4), (0.16, 0.55), (0.34, 0.7).
        const val ARRIVAL_SCALE_FIRST = 0.4f
        const val ARRIVAL_SCALE_SECOND = 0.55f
        const val ARRIVAL_SCALE_THIRD = 0.7f
        const val ARRIVAL_GAP_FIRST_MS = 160L
        const val ARRIVAL_GAP_SECOND_MS = 180L

        // iOS `playSeekBreath` @c1745e8: 0.15 → 0.35 across two 0.9 s segments.
        const val BREATH_QUIET_SCALE = 0.15f
        const val BREATH_FULL_SCALE = 0.35f
        const val BREATH_SEGMENT_MS = 900L

        // 30 ms tap matches the BellPlayer one-shot envelope (short
        // enough that older actuators don't smear it).
        const val TAP_MS = 30L
        const val MAX_AMPLITUDE = 255
        const val NO_REPEAT = -1
    }
}
