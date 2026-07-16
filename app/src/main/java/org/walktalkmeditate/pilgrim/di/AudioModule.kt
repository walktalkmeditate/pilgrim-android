// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.AudioCapture
import org.walktalkmeditate.pilgrim.audio.AudioRecordCapture
import org.walktalkmeditate.pilgrim.audio.BellDurationResolver
import org.walktalkmeditate.pilgrim.audio.BellFileResolver
import org.walktalkmeditate.pilgrim.audio.BellPlayer
import org.walktalkmeditate.pilgrim.audio.BellPlaying
import org.walktalkmeditate.pilgrim.audio.MeditationBellScope
import org.walktalkmeditate.pilgrim.audio.MeditationObservedWalkState
import org.walktalkmeditate.pilgrim.audio.TalkRecordingActive
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeFileStore
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.BellTrigger
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

        /**
         * Expose [VoiceRecorder.isRecording] as a narrow qualified
         * `StateFlow<Boolean>` (see [TalkRecordingActive]) so consumers
         * — the seek sonar's suppression gate (U9) and the voice-guide
         * scheduler's `isRecordingVoice` guard — never depend on the
         * recorder class itself.
         */
        @Provides
        @Singleton
        @TalkRecordingActive
        fun provideTalkRecordingActive(
            voiceRecorder: VoiceRecorder,
        ): StateFlow<Boolean> = voiceRecorder.isRecording

        /**
         * Expose `WalkController.bellTriggers` for `MeditationBellObserver`.
         * Narrow `SharedFlow` interface keeps tests injectable — the
         * observer never needs to emit, only collect.
         */
        @Provides
        @Singleton
        fun provideBellTriggers(controller: WalkController): SharedFlow<BellTrigger> =
            controller.bellTriggers

        /**
         * Resolves a bell id to a downloaded asset file via
         * [AudioManifestService] + [SoundscapeFileStore]. Returns null
         * when the bell hasn't been downloaded yet so
         * [BellPlayer.playInternal] can fall back to the bundled
         * `R.raw.bell`.
         */
        @Provides
        @Singleton
        fun provideBellFileResolver(
            manifestService: AudioManifestService,
            fileStore: SoundscapeFileStore,
        ): BellFileResolver = BellFileResolver { id ->
            val asset = manifestService.assets.value
                .firstOrNull { it.id == id && it.type == AudioAssetType.BELL }
            asset?.let { fileStore.fileFor(it) }?.takeIf { it.exists() }
        }

        /**
         * iOS parity `SoundManagement.swift:68-78` — resolve the
         * meditation-start bell's real duration so the soundscape
         * orchestrator doesn't cut a long (user-downloaded) bell short.
         *
         * Builds a short-lived [MediaPlayer], prepares it against the
         * selected bell (downloaded asset via [BellFileResolver], else
         * the bundled `R.raw.bell`), reads `getDuration()`, releases.
         * Any failure (no id, codec error, unknown duration) falls back
         * to [BellDurationResolver.BUNDLED_BELL_MS].
         */
        @Provides
        @Singleton
        fun provideBellDurationResolver(
            @ApplicationContext context: Context,
            soundsPreferences: SoundsPreferencesRepository,
            bellFileResolver: BellFileResolver,
        ): BellDurationResolver = BellDurationResolver {
            val bellId = soundsPreferences.meditationStartBellId.value
            val assetFile = bellId?.let(bellFileResolver::resolve)
            val player = MediaPlayer()
            try {
                if (assetFile != null) {
                    player.setDataSource(assetFile.absolutePath)
                    player.prepare()
                } else {
                    context.resources.openRawResourceFd(R.raw.bell)?.use { afd ->
                        player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        player.prepare()
                    } ?: return@BellDurationResolver BellDurationResolver.BUNDLED_BELL_MS
                }
                val durationMs = player.duration.toLong()
                if (durationMs > 0L) durationMs else BellDurationResolver.BUNDLED_BELL_MS
            } catch (t: Throwable) {
                Log.w("BellDurationResolver", "failed to resolve bell duration", t)
                BellDurationResolver.BUNDLED_BELL_MS
            } finally {
                try {
                    player.release()
                } catch (t: Throwable) {
                    Log.w("BellDurationResolver", "MediaPlayer release failed", t)
                }
            }
        }
    }
}
