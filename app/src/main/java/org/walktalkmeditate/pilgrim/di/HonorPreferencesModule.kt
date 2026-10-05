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
import org.walktalkmeditate.pilgrim.data.honor.ConnectivityInternetConnectionProbe
import org.walktalkmeditate.pilgrim.data.honor.DataStoreHonorPreferencesRepository
import org.walktalkmeditate.pilgrim.data.honor.HonorPreferencesRepository
import org.walktalkmeditate.pilgrim.data.honor.HonorPreferencesScope
import org.walktalkmeditate.pilgrim.data.honor.InternetConnectionProbe

/**
 * Hilt bindings for the Honor preferences, in `SeekPreferencesModule`'s
 * shape, and the connectivity reading behind the stage overview's
 * once-ever offline note, which writes one of them.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HonorPreferencesModule {

    @Binds
    @Singleton
    abstract fun bindHonorPreferencesRepository(
        impl: DataStoreHonorPreferencesRepository,
    ): HonorPreferencesRepository

    @Binds
    abstract fun bindInternetConnectionProbe(impl: ConnectivityInternetConnectionProbe): InternetConnectionProbe

    companion object {
        @Provides
        @Singleton
        @HonorPreferencesScope
        fun provideHonorPreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
