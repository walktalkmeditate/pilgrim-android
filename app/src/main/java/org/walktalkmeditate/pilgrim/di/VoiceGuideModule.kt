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
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.audio.voiceguide.ExoPlayerVoiceGuidePlayer
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuideObservedWalkState
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuidePlaybackScope
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuidePlayer
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuideSelectedPackId
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideCatalogScope
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideDownloadScheduler
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideSelectionRepository
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideSelectionScope
import org.walktalkmeditate.pilgrim.data.voiceguide.WorkManagerVoiceGuideDownloadScheduler
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * Voice-guide layer bindings: scheduler interface → WorkManager
 * implementation, plus the two long-lived [CoroutineScope]s
 * used by the catalog and selection repositories. Mirrors the
 * shape of `AudioModule` (abstract class with `@Binds` + nested
 * companion `@Provides`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceGuideModule {

    @Binds
    @Singleton
    abstract fun bindDownloadScheduler(
        impl: WorkManagerVoiceGuideDownloadScheduler,
    ): VoiceGuideDownloadScheduler

    @Binds
    @Singleton
    abstract fun bindVoiceGuidePlayer(impl: ExoPlayerVoiceGuidePlayer): VoiceGuidePlayer

    companion object {
        /**
         * Long-lived scope for
         * [org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideCatalogRepository]'s
         * `stateIn` collection + [org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideDownloadObserver]'s
         * auto-select observer. `SupervisorJob` so one failed emission
         * doesn't tear the whole scope down; `Dispatchers.Default`
         * because the work is pure Flow composition + occasional
         * filesystem reads via explicit `withContext(Dispatchers.IO)`
         * where appropriate.
         */
        @Provides
        @Singleton
        @VoiceGuideCatalogScope
        fun provideVoiceGuideCatalogScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Long-lived scope for [VoiceGuideSelectionRepository]'s `stateIn`. */
        @Provides
        @Singleton
        @VoiceGuideSelectionScope
        fun provideVoiceGuideSelectionScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Long-lived scope for [org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuideOrchestrator]'s
         * state-observer + per-session scheduler coroutines. `SupervisorJob`
         * so one scheduler's failure doesn't tear the whole scope down.
         */
        @Provides
        @Singleton
        @VoiceGuidePlaybackScope
        fun provideVoiceGuidePlaybackScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Expose `WalkController.state` as a narrow `StateFlow<WalkState>`
         * so the voice-guide orchestrator depends only on the read-only
         * observation interface — tests can inject any `MutableStateFlow`
         * without building a full `WalkController` with its Room + clock
         * dependencies. Mirrors the shape of
         * `@MeditationObservedWalkState` (Stage 5-B).
         */
        @Provides
        @Singleton
        @VoiceGuideObservedWalkState
        fun provideVoiceGuideObservedWalkState(
            controller: WalkController,
        ): StateFlow<WalkState> = controller.state

        /**
         * Narrow the selection repository to its read-only
         * `selectedPackId` flow so the orchestrator depends only on
         * the observation interface — tests inject a
         * `MutableStateFlow<String?>` directly.
         */
        @Provides
        @Singleton
        @VoiceGuideSelectedPackId
        fun provideVoiceGuideSelectedPackId(
            selection: VoiceGuideSelectionRepository,
        ): StateFlow<String?> = selection.selectedPackId
    }
}
