// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.walktalkmeditate.pilgrim.data.sounds.DataStoreSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesScope

/**
 * Hilt bindings for the sounds-preferences layer. Same shape as
 * `AppearanceModule` (Stage 9.5-E).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SoundsPreferencesModule {

    @Binds
    @Singleton
    abstract fun bindSoundsPreferencesRepository(
        impl: DataStoreSoundsPreferencesRepository,
    ): SoundsPreferencesRepository

    companion object {
        @Provides
        @Singleton
        @SoundsPreferencesScope
        fun provideSoundsPreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
