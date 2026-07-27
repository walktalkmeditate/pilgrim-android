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
import org.walktalkmeditate.pilgrim.data.voice.DataStoreVoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesScope

@Module
@InstallIn(SingletonComponent::class)
abstract class VoicePreferencesModule {
    @Binds
    @Singleton
    abstract fun bindVoicePreferencesRepository(
        impl: DataStoreVoicePreferencesRepository,
    ): VoicePreferencesRepository

    companion object {
        @Provides
        @Singleton
        @VoicePreferencesScope
        fun provideVoicePreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Voice-pref repo gets its own DataStore file (separate from
         * the shared `pilgrim_prefs` store) so the autoTranscribe
         * upgrade-migration probe can reason about a single namespace.
         * Factory with corruption handler so a truncated
         * preferences_pb (mid-write OS kill) resets to empty
         * preferences instead of crashing every read — same shape as
         * WidgetModule / CollectiveModule.
         */
        @Provides
        @Singleton
        @JvmStatic
        @VoicePreferencesDataStore
        fun provideVoicePreferencesDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("voice_preferences") },
        )
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoicePreferencesDataStore
