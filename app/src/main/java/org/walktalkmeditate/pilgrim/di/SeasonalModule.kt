// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepositoryScope

@Module
@InstallIn(SingletonComponent::class)
object SeasonalModule {
    // Application-lifetime scope for HemisphereRepository's StateFlow.
    // SupervisorJob so a collector failure doesn't cancel the scope.
    // Dispatchers.Default is fine — there's no blocking IO here;
    // DataStore handles its own IO internally.
    @Provides
    @Singleton
    @HemisphereRepositoryScope
    fun provideHemisphereScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
