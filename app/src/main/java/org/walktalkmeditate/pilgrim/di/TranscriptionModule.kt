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
import org.walktalkmeditate.pilgrim.audio.model.BackgroundDataRestrictionProbe
import org.walktalkmeditate.pilgrim.audio.model.ConnectivityBackgroundDataRestrictionProbe
import org.walktalkmeditate.pilgrim.audio.model.ConnectivityUnmeteredNetworkProbe
import org.walktalkmeditate.pilgrim.audio.model.FreeSpaceProbe
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWorkSource
import org.walktalkmeditate.pilgrim.audio.model.PendingTranscriptionWalkSource
import org.walktalkmeditate.pilgrim.audio.model.StatFsFreeSpaceProbe
import org.walktalkmeditate.pilgrim.audio.model.UnmeteredNetworkProbe
import org.walktalkmeditate.pilgrim.audio.model.WalkRepositoryPendingTranscriptionWalkSource
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelConfig
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelDownloadScheduler
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelDownloadSpec
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelScope
import org.walktalkmeditate.pilgrim.audio.model.WorkManagerWhisperModelDownloadScheduler

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

    @Binds
    @Singleton
    abstract fun bindWhisperModelDownloadScheduler(
        impl: WorkManagerWhisperModelDownloadScheduler,
    ): WhisperModelDownloadScheduler

    /**
     * The scheduler doubles as the store's work source — one WorkInfo
     * observation feeds both the UI-facing scheduling surface and the
     * state composition.
     */
    @Binds
    @Singleton
    abstract fun bindModelDownloadWorkSource(
        impl: WorkManagerWhisperModelDownloadScheduler,
    ): ModelDownloadWorkSource

    @Binds
    @Singleton
    abstract fun bindFreeSpaceProbe(impl: StatFsFreeSpaceProbe): FreeSpaceProbe

    @Binds
    @Singleton
    abstract fun bindPendingTranscriptionWalkSource(
        impl: WalkRepositoryPendingTranscriptionWalkSource,
    ): PendingTranscriptionWalkSource

    @Binds
    @Singleton
    abstract fun bindUnmeteredNetworkProbe(
        impl: ConnectivityUnmeteredNetworkProbe,
    ): UnmeteredNetworkProbe

    @Binds
    @Singleton
    abstract fun bindBackgroundDataRestrictionProbe(
        impl: ConnectivityBackgroundDataRestrictionProbe,
    ): BackgroundDataRestrictionProbe

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

        @Provides
        @Singleton
        fun provideWhisperModelDownloadSpec(): WhisperModelDownloadSpec =
            WhisperModelDownloadSpec(
                url = WhisperModelConfig.CDN_URL,
                expectedBytes = WhisperModelConfig.EXPECTED_BYTES,
                expectedSha256 = WhisperModelConfig.EXPECTED_SHA256,
            )
    }
}
