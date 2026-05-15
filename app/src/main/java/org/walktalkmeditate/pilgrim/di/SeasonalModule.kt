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
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepositoryScope
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereStore

@Module
@InstallIn(SingletonComponent::class)
abstract class SeasonalModule {

    @Binds
    @Singleton
    abstract fun bindHemisphereStore(impl: HemisphereRepository): HemisphereStore

    companion object {
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
}
