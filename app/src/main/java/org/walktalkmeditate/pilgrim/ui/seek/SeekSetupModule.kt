// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.seek

import android.content.Context
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.walktalkmeditate.pilgrim.BuildConfig
import org.walktalkmeditate.pilgrim.audio.seek.SeekHaptics
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks

/**
 * Production bindings for [SeekSetupViewModel]'s two seams. Both are
 * one-line adapters — the seams exist so the stage machine's JVM tests
 * never need a Context or a [android.os.Vibrator].
 */
@Module
@InstallIn(SingletonComponent::class)
object SeekSetupModule {

    @Provides
    fun provideSeekAccuracyChecking(
        @ApplicationContext context: Context,
    ): SeekAccuracyChecking =
        SeekAccuracyChecking { PermissionChecks.isFineLocationGranted(context) }

    @Provides
    fun provideSeekBreathHaptic(seekHaptics: SeekHaptics): SeekBreathHaptic =
        SeekBreathHaptic(seekHaptics::breathIn)

    /**
     * QA-only near-clearing chains: debug builds + explicit device
     * opt-in (`adb shell settings put global pilgrim_seek_qa_near 1`).
     * Release builds are hard-false regardless of the setting.
     */
    @Provides
    fun provideSeekQaFlags(
        @ApplicationContext context: Context,
    ): SeekQaFlags = SeekQaFlags {
        if (!BuildConfig.DEBUG) {
            0
        } else {
            Settings.Global.getInt(context.contentResolver, "pilgrim_seek_qa_near", 0)
                .coerceIn(0, 2)
        }
    }
}
