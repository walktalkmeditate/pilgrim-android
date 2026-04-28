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
import org.walktalkmeditate.pilgrim.data.practice.DataStorePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesScope

/**
 * Hilt bindings for the practice-preferences layer. Same shape as
 * `SoundsPreferencesModule` (Stage 10-B).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PracticePreferencesModule {

    @Binds
    @Singleton
    abstract fun bindPracticePreferencesRepository(
        impl: DataStorePracticePreferencesRepository,
    ): PracticePreferencesRepository

    companion object {
        @Provides
        @Singleton
        @PracticePreferencesScope
        fun providePracticePreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
