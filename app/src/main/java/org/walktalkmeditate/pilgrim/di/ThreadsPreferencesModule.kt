// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.walktalkmeditate.pilgrim.core.threads.DataStoreThreadsPreferencesRepository
import org.walktalkmeditate.pilgrim.core.threads.ThreadsPreferencesRepository
import org.walktalkmeditate.pilgrim.core.threads.ThreadsPreferencesScope

/** Follows [VoicePreferencesModule]'s shape: dedicated DataStore file,
 * corruption-resets-to-empty, its own long-lived scope. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ThreadsPreferencesModule {
    @Binds
    @Singleton
    abstract fun bindThreadsPreferencesRepository(
        impl: DataStoreThreadsPreferencesRepository,
    ): ThreadsPreferencesRepository

    companion object {
        @Provides
        @Singleton
        @ThreadsPreferencesScope
        fun provideThreadsPreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        @Singleton
        @JvmStatic
        @ThreadsPreferencesDataStore
        fun provideThreadsPreferencesDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("threads_preferences") },
        )
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ThreadsPreferencesDataStore
