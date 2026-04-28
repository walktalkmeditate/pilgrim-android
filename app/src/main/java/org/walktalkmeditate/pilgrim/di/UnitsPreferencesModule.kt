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
import org.walktalkmeditate.pilgrim.data.units.DataStoreUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesScope

/**
 * Hilt bindings for the units-preferences layer. Same shape as
 * `SoundsPreferencesModule` (Stage 10-B).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UnitsPreferencesModule {

    @Binds
    @Singleton
    abstract fun bindUnitsPreferencesRepository(
        impl: DataStoreUnitsPreferencesRepository,
    ): UnitsPreferencesRepository

    companion object {
        @Provides
        @Singleton
        @UnitsPreferencesScope
        fun provideUnitsPreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
