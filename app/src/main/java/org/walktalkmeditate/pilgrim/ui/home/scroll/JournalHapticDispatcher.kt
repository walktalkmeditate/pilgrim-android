// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches a [HapticEvent] to the system Vibrator using
 * [VibrationEffect.Composition] primitives. Reduce-motion gating is
 * HANDLER-TIME (per-call `Settings.Global` read) so a Quick-Settings
 * flip mid-scroll takes effect on the next dispatch — distinct from
 * `LocalReduceMotion` which is composition-time.
 *
 * 50 ms min-interval guard defends against scroll-fling / multi-finger
 * haptic flooding (Open Question 3).
 */
@Singleton
class JournalHapticDispatcher internal constructor(
    private val context: Context,
    private val soundsEnabledProvider: () -> Boolean,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        soundsEnabledProvider = { true },
    )

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private var lastDotDispatchNs: Long = 0L
    private val minIntervalNs: Long = 50_000_000L // 50 ms

    fun dispatch(event: HapticEvent) {
        if (event is HapticEvent.None) return
        if (!soundsEnabledProvider()) return
        if (isReduceMotion()) return

        // kaijutsu PR #86 review: milestones bypass the throttle (rare +
        // important events). Dot throttle remains to defend against
        // scroll-fling flooding when crossing many dots in <50 ms.
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (event !is HapticEvent.Milestone) {
            if (nowNs - lastDotDispatchNs < minIntervalNs) return
            lastDotDispatchNs = nowNs
        }

        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Pre-API 30: no Composition. Fall back to a single oneShot.
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createOneShot(8L, VibrationEffect.DEFAULT_AMPLITUDE))
            return
        }

        val effect: VibrationEffect = buildEffect(event) ?: return
        v.vibrate(effect)
    }

    private fun buildEffect(event: HapticEvent): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val composition = VibrationEffect.startComposition()
        when (event) {
            is HapticEvent.LightDot -> {
                if (!supports(VibrationEffect.Composition.PRIMITIVE_TICK)) return fallback(0.4f)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1.0f)
            }
            is HapticEvent.HeavyDot -> {
                if (!supports(VibrationEffect.Composition.PRIMITIVE_CLICK)) return fallback(0.7f)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
            }
            is HapticEvent.Milestone -> {
                val canHeavy = supports(VibrationEffect.Composition.PRIMITIVE_CLICK)
                val canLow = supports(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
                if (!canHeavy) return fallback(0.9f)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                if (canLow) composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.6f, 30)
            }
            is HapticEvent.None -> return null
        }
        return composition.compose()
    }

    private fun supports(primitive: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val v = vibrator ?: return false
        return v.areAllPrimitivesSupported(primitive)
    }

    private fun fallback(amplitude: Float): VibrationEffect =
        VibrationEffect.createOneShot(
            12L,
            (amplitude * VibrationEffect.DEFAULT_AMPLITUDE).toInt().coerceAtLeast(1),
        )

    private fun isReduceMotion(): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        ) == 0f
    } catch (_: Settings.SettingNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
