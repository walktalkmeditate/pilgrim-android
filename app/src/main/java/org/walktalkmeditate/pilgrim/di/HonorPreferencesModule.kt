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
import org.walktalkmeditate.pilgrim.data.honor.DataStoreHonorPreferencesRepository
import org.walktalkmeditate.pilgrim.data.honor.HonorPreferencesRepository
import org.walktalkmeditate.pilgrim.data.honor.HonorPreferencesScope

/** Hilt bindings for the Honor preferences, in `SeekPreferencesModule`'s shape. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HonorPreferencesModule {

    @Binds
    @Singleton
    abstract fun bindHonorPreferencesRepository(
        impl: DataStoreHonorPreferencesRepository,
    ): HonorPreferencesRepository

    companion object {
        @Provides
        @Singleton
        @HonorPreferencesScope
        fun provideHonorPreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
