// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.audio.TranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.WhisperCppEngine
import org.walktalkmeditate.pilgrim.audio.WhisperEngine
import org.walktalkmeditate.pilgrim.audio.WorkManagerTranscriptionScheduler

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
}
