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
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}
