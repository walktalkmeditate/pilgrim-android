// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.recovery

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Long-lived coroutine scope for [WalkRecoveryRepository]'s internal
 * StateFlow. Bound to app lifetime via Hilt's `SingletonComponent`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WalkRecoveryScope

@Module
@InstallIn(SingletonComponent::class)
object WalkRecoveryModule {

    @Provides
    @Singleton
    @WalkRecoveryScope
    fun provideWalkRecoveryScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WalkRecoveryBindsModule {

    @Binds
    @Singleton
    abstract fun bindWalkRecoveryRepository(
        impl: DataStoreWalkRecoveryRepository,
    ): WalkRecoveryRepository
}
