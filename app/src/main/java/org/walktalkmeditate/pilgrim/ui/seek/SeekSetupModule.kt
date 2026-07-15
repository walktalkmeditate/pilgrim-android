// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.seek

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
}
