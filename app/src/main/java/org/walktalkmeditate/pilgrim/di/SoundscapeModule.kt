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
import org.walktalkmeditate.pilgrim.audio.soundscape.ExoPlayerSoundscapePlayer
import org.walktalkmeditate.pilgrim.audio.soundscape.SoundscapeObservedWalkState
import org.walktalkmeditate.pilgrim.audio.soundscape.SoundscapePlaybackScope
import org.walktalkmeditate.pilgrim.audio.soundscape.SoundscapePlayer
import org.walktalkmeditate.pilgrim.audio.soundscape.SoundscapeSelectedAssetId
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeCatalogScope
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeDownloadScheduler
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeSelectionRepository
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeSelectionScope
import org.walktalkmeditate.pilgrim.data.soundscape.WorkManagerSoundscapeDownloadScheduler
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * Soundscape layer bindings. Parallels [VoiceGuideModule] (Stage
 * 5-D/5-E) — the two audio stacks live side-by-side with independent
 * selection, catalog, and playback state.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SoundscapeModule {

    @Binds
    @Singleton
    abstract fun bindDownloadScheduler(
        impl: WorkManagerSoundscapeDownloadScheduler,
    ): SoundscapeDownloadScheduler

    @Binds
    @Singleton
    abstract fun bindSoundscapePlayer(impl: ExoPlayerSoundscapePlayer): SoundscapePlayer

    companion object {
        /**
         * Long-lived scope for
         * [org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeCatalogRepository]'s
         * `stateIn` collection. Same shape as `VoiceGuideCatalogScope`.
         */
        @Provides
        @Singleton
        @SoundscapeCatalogScope
        fun provideSoundscapeCatalogScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Long-lived scope for [SoundscapeSelectionRepository]'s `stateIn`. */
        @Provides
        @Singleton
        @SoundscapeSelectionScope
        fun provideSoundscapeSelectionScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Long-lived scope for the soundscape orchestrator's observer
         * coroutine + start-delay launches. `SupervisorJob` so one
         * launch failure doesn't tear the whole scope down.
         */
        @Provides
        @Singleton
        @SoundscapePlaybackScope
        fun provideSoundscapePlaybackScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Narrow `WalkController.state` to the read-only
         * `StateFlow<WalkState>` interface so the orchestrator's tests
         * can inject a `MutableStateFlow` without building the full
         * controller.
         */
        @Provides
        @Singleton
        @SoundscapeObservedWalkState
        fun provideSoundscapeObservedWalkState(
            controller: WalkController,
        ): StateFlow<WalkState> = controller.state

        /** Narrow selection repo to its read-only `selectedSoundscapeId` flow. */
        @Provides
        @Singleton
        @SoundscapeSelectedAssetId
        fun provideSoundscapeSelectedAssetId(
            selection: SoundscapeSelectionRepository,
        ): StateFlow<String?> = selection.selectedSoundscapeId
    }
}
