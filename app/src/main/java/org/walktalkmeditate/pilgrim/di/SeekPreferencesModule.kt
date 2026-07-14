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
import org.walktalkmeditate.pilgrim.data.seek.DataStoreSeekPreferencesRepository
import org.walktalkmeditate.pilgrim.data.seek.SeekPreferencesRepository
import org.walktalkmeditate.pilgrim.data.seek.SeekPreferencesScope

/**
 * Hilt bindings for the seek-preferences layer. Same shape as
 * `PracticePreferencesModule` (Stage 10-C).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SeekPreferencesModule {

    @Binds
    @Singleton
    abstract fun bindSeekPreferencesRepository(
        impl: DataStoreSeekPreferencesRepository,
    ): SeekPreferencesRepository

    companion object {
        @Provides
        @Singleton
        @SeekPreferencesScope
        fun provideSeekPreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
