// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.audio.ExoPlayerVoicePlaybackController
import org.walktalkmeditate.pilgrim.audio.OrphanSweeperScheduler
import org.walktalkmeditate.pilgrim.audio.VoicePlaybackController
import org.walktalkmeditate.pilgrim.audio.WorkManagerOrphanSweeperScheduler

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    @Binds
    @Singleton
    abstract fun bindVoicePlaybackController(
        impl: ExoPlayerVoicePlaybackController,
    ): VoicePlaybackController

    @Binds
    @Singleton
    abstract fun bindOrphanSweeperScheduler(
        impl: WorkManagerOrphanSweeperScheduler,
    ): OrphanSweeperScheduler
}
