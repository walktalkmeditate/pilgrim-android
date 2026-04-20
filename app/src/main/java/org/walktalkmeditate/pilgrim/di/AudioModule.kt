// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import android.media.AudioManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.audio.AudioCapture
import org.walktalkmeditate.pilgrim.audio.AudioRecordCapture
import org.walktalkmeditate.pilgrim.audio.BellPlayer
import org.walktalkmeditate.pilgrim.audio.BellPlaying
import org.walktalkmeditate.pilgrim.audio.MeditationBellScope
import org.walktalkmeditate.pilgrim.audio.MeditationObservedWalkState
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.WalkController

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    @Singleton
    abstract fun bindAudioCapture(impl: AudioRecordCapture): AudioCapture

    /**
     * Bind the concrete [BellPlayer] as the [BellPlaying] abstraction
     * so [org.walktalkmeditate.pilgrim.audio.MeditationBellObserver]
     * can be unit-tested with a counting fake rather than a real
     * `MediaPlayer`.
     */
    @Binds
    @Singleton
    abstract fun bindBellPlayer(impl: BellPlayer): BellPlaying

    companion object {
        @Provides
        @Singleton
        fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
            context.getSystemService(AudioManager::class.java)

        /**
         * Long-lived scope for the meditation-bell observer. Lives for
         * the app process; `SupervisorJob` so one failed emission
         * doesn't tear the whole scope down. See [MeditationBellScope].
         */
        @Provides
        @Singleton
        @MeditationBellScope
        fun provideMeditationBellScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Expose `WalkController.state` as a narrow
         * `StateFlow<WalkState>` so the bell observer depends only on
         * the read-only flow interface — tests can inject any flow
         * without building a full `WalkController` with its Room +
         * clock dependencies.
         */
        @Provides
        @Singleton
        @MeditationObservedWalkState
        fun provideMeditationObservedWalkState(
            controller: WalkController,
        ): StateFlow<WalkState> = controller.state
    }
}
