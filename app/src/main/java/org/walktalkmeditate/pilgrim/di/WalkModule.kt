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
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkFinalizationObservedState
import org.walktalkmeditate.pilgrim.walk.WalkFinalizationScope

@Module
@InstallIn(SingletonComponent::class)
object WalkModule {

    @Provides
    @Singleton
    @WalkFinalizationScope
    fun provideWalkFinalizationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // PersistenceScope is generic (not walk-specific); housed here for now
    // because WalkSummaryViewModel is the first consumer. Move to a
    // dedicated `CoroutineScopesModule` when a second VM injects it
    // (Settings VM toggles + WalkVM dwell-screen writes are the obvious
    // next candidates per the sweep PR triage).
    @Provides
    @Singleton
    @PersistenceScope
    fun providePersistenceScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    @WalkFinalizationObservedState
    fun provideWalkFinalizationObservedState(
        controller: WalkController,
    ): StateFlow<WalkState> = controller.state
}
