// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
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

/**
 * Voice-pref repo gets its own DataStore file (separate from the shared
 * `pilgrim_prefs` store) so the autoTranscribe upgrade-migration probe
 * can reason about a single namespace.
 */
private val Context.voicePreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "voice_preferences")

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

        @Provides
        @Singleton
        @JvmStatic
        @VoicePreferencesDataStore
        fun provideVoicePreferencesDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = context.voicePreferencesDataStore
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoicePreferencesDataStore
