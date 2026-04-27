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
import org.walktalkmeditate.pilgrim.data.appearance.AppearancePreferencesRepository
import org.walktalkmeditate.pilgrim.data.appearance.AppearancePreferencesScope
import org.walktalkmeditate.pilgrim.data.appearance.DataStoreAppearancePreferencesRepository

/**
 * Hilt bindings for the appearance-preferences layer. `@Binds` for
 * the interface → DataStore impl; `@Provides` for the long-lived
 * [CoroutineScope] backing the StateFlow's `stateIn`. Same shape as
 * `VoiceGuideModule` — abstract class + nested companion object.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppearanceModule {

    @Binds
    @Singleton
    abstract fun bindAppearancePreferencesRepository(
        impl: DataStoreAppearancePreferencesRepository,
    ): AppearancePreferencesRepository

    companion object {
        @Provides
        @Singleton
        @AppearancePreferencesScope
        fun provideAppearancePreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
