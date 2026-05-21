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
import org.walktalkmeditate.pilgrim.audio.voiceguide.DataStoreVoiceGuideProgressRepository
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuideProgressRepository

/**
 * Dedicated DataStore file for [VoiceGuideProgressRepository]. Lives
 * in its own file (not the shared `pilgrim_prefs` store) so a corrupt
 * write to one preference cannot tombstone the unrelated voice-guide
 * progress, and so its clear-on-walk-finish path doesn't accidentally
 * wipe anything else.
 */
private val Context.voiceGuideProgressDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "voice_guide_progress")

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceGuideProgressModule {
    @Binds
    @Singleton
    abstract fun bindVoiceGuideProgressRepository(
        impl: DataStoreVoiceGuideProgressRepository,
    ): VoiceGuideProgressRepository

    companion object {
        @Provides
        @Singleton
        @JvmStatic
        @VoiceGuideProgressDataStore
        fun provideVoiceGuideProgressDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = context.voiceGuideProgressDataStore
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideProgressDataStore
