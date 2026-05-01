// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides a singleton [Vibrator] for the haptic-coupled
 * [org.walktalkmeditate.pilgrim.audio.BellPlayer] (Stage 12-C). Ports
 * iOS's `UIImpactFeedbackGenerator(.medium)` accompaniment to bell
 * strikes.
 *
 * API 31+ (`Build.VERSION_CODES.S`) deprecated the `VIBRATOR_SERVICE`
 * system service in favor of `VibratorManager.defaultVibrator`.
 * Provider forks on `SDK_INT` to use the modern path on API 31+ and
 * the deprecated path on API 28–30 (project min-SDK is 28). The
 * deprecation suppression lives in this module so production code
 * (`BellPlayer.fireMediumImpact`) never sees it.
 */
@Module
@InstallIn(SingletonComponent::class)
object HapticsModule {

    @Provides
    @Singleton
    fun provideVibrator(@ApplicationContext context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Safe-cast for symmetry with the pre-API-31 path. Stripped-down
            // ROMs (Chromebook ARC++, certain Android Auto receivers, AOSP
            // virtual devices) can return null from `getSystemService` even
            // for documented services. A raw `as VibratorManager` would
            // KotlinNPE during Hilt construction.
            val manager = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?: error("VIBRATOR_MANAGER_SERVICE unavailable on this device")
            manager.defaultVibrator
        } else {
            // Safe-cast on the deprecated path: on rare device classes
            // without vibrator hardware (Chromebooks, stripped emulators)
            // the system service can return null. A raw `as Vibrator` would
            // KotlinNPE during Hilt construction, which would also bring
            // down BellPlayer and any singleton that depends on it. Fail
            // fast with a clear message instead — vibrate calls on a real
            // no-vibrator Vibrator are silent no-ops via `hasVibrator()`,
            // so this only fires when the system service itself is absent.
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?: error("VIBRATOR_SERVICE unavailable on this device")
        }
}
