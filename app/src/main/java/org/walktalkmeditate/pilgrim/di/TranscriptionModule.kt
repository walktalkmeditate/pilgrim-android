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
import org.walktalkmeditate.pilgrim.audio.TranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.WhisperCppEngine
import org.walktalkmeditate.pilgrim.audio.WhisperEngine
import org.walktalkmeditate.pilgrim.audio.WorkManagerTranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.model.ConnectivityUnmeteredNetworkProbe
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWorkSource
import org.walktalkmeditate.pilgrim.audio.model.NoOpModelDownloadWorkSource
import org.walktalkmeditate.pilgrim.audio.model.UnmeteredNetworkProbe
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelScope

@Module
@InstallIn(SingletonComponent::class)
abstract class TranscriptionModule {

    @Binds
    @Singleton
    abstract fun bindWhisperEngine(impl: WhisperCppEngine): WhisperEngine

    @Binds
    @Singleton
    abstract fun bindTranscriptionScheduler(
        impl: WorkManagerTranscriptionScheduler,
    ): TranscriptionScheduler

    /** Placeholder until U9's download scheduler replaces this binding. */
    @Binds
    @Singleton
    abstract fun bindModelDownloadWorkSource(
        impl: NoOpModelDownloadWorkSource,
    ): ModelDownloadWorkSource

    @Binds
    @Singleton
    abstract fun bindUnmeteredNetworkProbe(
        impl: ConnectivityUnmeteredNetworkProbe,
    ): UnmeteredNetworkProbe

    companion object {
        /**
         * Long-lived scope for
         * [org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore]'s
         * `stateIn` collection. `SupervisorJob` so one failed emission
         * doesn't tear the scope down; `Dispatchers.Default` because the
         * work is Flow composition with explicit
         * `withContext(Dispatchers.IO)` at the filesystem probes.
         */
        @Provides
        @Singleton
        @WhisperModelScope
        fun provideWhisperModelScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
