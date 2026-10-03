// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.honor

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.audio.seek.SeekHaptics
import org.walktalkmeditate.pilgrim.walk.honor.HonorHapticsPort

/**
 * The Honor haptics (parity spec C §13, correction 13), none of them tied
 * to a voice or its playback:
 * - a reached place, rest, sitting, photo, or waypoint: iOS `.waypointDropped`,
 *   a light impact (`HapticManager.swift:103-106@7c200bf`);
 * - the soft tap off the Way: iOS `.honorOffWay`, a soft impact (`:203-206`);
 * - arrival: Seek's three rising taps, the same pattern on purpose (`:208-215`);
 * - water ahead on a stage: iOS `.honorWaterAhead`, one Core Haptics
 *   transient at 0.4, else a soft impact (`:217-223`, `:241-248`), both the
 *   tick at [WATER_SCALE] here (pilgrimage-stage spec P3 §8).
 *
 * iOS drops every Honor haptic outside the foreground
 * (`ActiveWalkViewModel+Honor.swift:460-463@7c200bf`); Android fires them
 * with the screen off, the R6 divergence Seek already takes.
 */
@Singleton
class HonorHaptics @Inject constructor(
    private val vibrator: Vibrator,
    private val seekHaptics: SeekHaptics,
) : HonorHapticsPort {

    override fun momentReached() = impact(Impact.LIGHT)

    override fun softTap() = impact(Impact.SOFT)

    override fun waterAhead() = impact(Impact.WATER)

    override fun arrival() = seekHaptics.arrival()

    /** One tap: the primitive where the device has it, else a 30 ms one-shot at the same strength. */
    private fun impact(impact: Impact) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val primitive = when (impact) {
                Impact.LIGHT -> VibrationEffect.Composition.PRIMITIVE_CLICK
                Impact.SOFT, Impact.WATER -> VibrationEffect.Composition.PRIMITIVE_TICK
            }
            if (vibrator.areAllPrimitivesSupported(primitive)) {
                try {
                    vibrator.vibrate(VibrationEffect.startComposition().addPrimitive(primitive, impact.scale).compose())
                    return
                } catch (e: RuntimeException) {
                    Log.w(TAG, "primitive composition failed; falling back to a one-shot", e)
                }
            }
        }
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(TAP_MS, SeekHaptics.amplitudeFor(impact.scale)))
        } catch (e: RuntimeException) {
            Log.w(TAG, "one-shot haptic failed", e)
        }
    }

    /** UIKit's two impact styles Honor uses, and the water notice's single transient. */
    private enum class Impact(val scale: Float) {
        LIGHT(LIGHT_SCALE),
        SOFT(SOFT_SCALE),
        WATER(WATER_SCALE),
    }

    internal companion object {
        private const val TAG = "HonorHaptics"

        /** UIKit's light impact: the crisp click, held low. */
        const val LIGHT_SCALE = 0.5f

        /** UIKit's soft impact: the rounder tick Seek already uses for iOS's soft taps, a little quieter. */
        const val SOFT_SCALE = 0.4f

        /**
         * iOS `playHonorWaterAhead`'s transient at intensity 0.4, the whisper's
         * softness once (`HapticManager.swift:241-248@7c200bf`); its soft-impact
         * fallback maps to the same tick.
         */
        const val WATER_SCALE = 0.4f

        /** The BellPlayer and Seek one-shot length. */
        const val TAP_MS = 30L
    }
}
