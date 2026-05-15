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
import org.walktalkmeditate.pilgrim.data.intention.DataStoreIntentionHistoryRepository
import org.walktalkmeditate.pilgrim.data.intention.IntentionHistoryRepository
import org.walktalkmeditate.pilgrim.data.intention.IntentionHistoryScope

/**
 * Hilt bindings for the intention-history layer. Same shape as
 * `PracticePreferencesModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class IntentionHistoryModule {

    @Binds
    @Singleton
    abstract fun bindIntentionHistoryRepository(
        impl: DataStoreIntentionHistoryRepository,
    ): IntentionHistoryRepository

    companion object {
        @Provides
        @Singleton
        @IntentionHistoryScope
        fun provideIntentionHistoryScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
