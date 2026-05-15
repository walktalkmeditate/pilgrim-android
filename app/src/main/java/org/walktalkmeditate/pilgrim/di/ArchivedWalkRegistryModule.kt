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
import org.walktalkmeditate.pilgrim.data.pilgrim.ArchivedWalkRegistry
import org.walktalkmeditate.pilgrim.data.pilgrim.ArchivedWalkRegistryScope
import org.walktalkmeditate.pilgrim.data.pilgrim.DataStoreArchivedWalkRegistry

@Module
@InstallIn(SingletonComponent::class)
abstract class ArchivedWalkRegistryModule {
    @Binds
    @Singleton
    abstract fun bindArchivedWalkRegistry(
        impl: DataStoreArchivedWalkRegistry,
    ): ArchivedWalkRegistry

    companion object {
        @Provides
        @Singleton
        @ArchivedWalkRegistryScope
        fun provideArchivedWalkRegistryScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
