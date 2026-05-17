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
import org.walktalkmeditate.pilgrim.data.onboarding.DataStoreOnboardingPreferencesRepository
import org.walktalkmeditate.pilgrim.data.onboarding.OnboardingPreferencesRepository
import org.walktalkmeditate.pilgrim.data.onboarding.OnboardingPreferencesScope

@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingModule {
    @Binds
    @Singleton
    abstract fun bindOnboardingPreferencesRepository(
        impl: DataStoreOnboardingPreferencesRepository,
    ): OnboardingPreferencesRepository

    companion object {
        @Provides
        @Singleton
        @OnboardingPreferencesScope
        fun provideOnboardingPreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
