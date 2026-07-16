// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.audio.TalkRecordingActive
import org.walktalkmeditate.pilgrim.audio.seek.SeekHaptics
import org.walktalkmeditate.pilgrim.audio.seek.SeekPingGate
import org.walktalkmeditate.pilgrim.audio.seek.SeekSoundPlayer
import org.walktalkmeditate.pilgrim.audio.seek.SeekSoundPlaying
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuidePlayer
import org.walktalkmeditate.pilgrim.data.seek.SeekPreferencesRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.whisper.WhisperManifestService
import org.walktalkmeditate.pilgrim.data.whisper.WhisperPlayer
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPowerTier
import org.walktalkmeditate.pilgrim.power.SeekPowerTierSource
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.seek.SeekGlancePublisher
import org.walktalkmeditate.pilgrim.walk.seek.SeekObservedWalkState
import org.walktalkmeditate.pilgrim.walk.seek.SeekPowerTiers
import org.walktalkmeditate.pilgrim.walk.seek.SeekProcessForeground
import org.walktalkmeditate.pilgrim.walk.seek.SeekScope
import org.walktalkmeditate.pilgrim.walk.seek.SeekSenses

/**
 * Wiring for the seek session stack (U9): the sonar player with its
 * real suppression providers (U5 spec §2.3 deferred these here), the
 * single-threaded seek scope, the observed walk-state flow, and the
 * production [SeekSenses]. Port spec:
 * `docs/parity/2026-07-14-port-seek-orchestrator-u9.md`.
 */
@Module
@InstallIn(SingletonComponent::class)
object SeekModule {

    /**
     * Single-threaded view of Default: the engine's state confinement
     * contract (U3 spec B15) AND the fog-math-on-Default dispatcher
     * note (U6/U7) in one dispatcher. `SupervisorJob` so one failed
     * session coroutine never tears down the observer.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides
    @Singleton
    @SeekScope
    fun provideSeekScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    @Provides
    @Singleton
    @SeekObservedWalkState
    fun provideSeekObservedWalkState(
        controller: WalkController,
    ): StateFlow<WalkState> = controller.state

    @Provides
    @Singleton
    @SeekPowerTiers
    fun provideSeekPowerTiers(source: SeekPowerTierSource): Flow<SeekPowerTier> = source.tiers

    /**
     * Process-foreground signal bounding the unadopted pre-departure
     * session (see [SeekProcessForeground]). Starts pessimistic (false)
     * and is corrected by the observer's retroactive lifecycle delivery;
     * registration hops to the main thread — `ProcessLifecycleOwner`
     * requires it, and Hilt may build providers off-main. The observer
     * is process-lifetime by design: the orchestrator is an app-scoped
     * singleton.
     */
    @Provides
    @Singleton
    @SeekProcessForeground
    fun provideSeekProcessForeground(): StateFlow<Boolean> {
        val foreground = MutableStateFlow(false)
        val register = {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        foreground.value = true
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        foreground.value = false
                    }
                },
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            register()
        } else {
            Handler(Looper.getMainLooper()).post { register() }
        }
        return foreground
    }

    /**
     * U10 glance transport: the orchestrator's changed glances ride the
     * established UI→tracker intent channel to the notification
     * renderer. Port spec:
     * `docs/parity/2026-07-14-port-seek-glance-u10.md` B3.
     */
    @Provides
    @Singleton
    fun provideSeekGlancePublisher(
        walkActionPublisher: WalkActionPublisher,
    ): SeekGlancePublisher = SeekGlancePublisher(walkActionPublisher::publishSeekGlance)

    /**
     * The sonar/bowl channel with its real suppression gate (iOS
     * `canPingOverCurrentAudio`, `SeekSoundPlayer.swift:127-136
     * @c1745e8`): whisper on EITHER channel, a speaking voice-guide
     * prompt, or an active talk recording each independently skip the
     * ping — never queue, never duck.
     */
    @Provides
    @Singleton
    fun provideSeekSoundPlayer(
        @ApplicationContext context: Context,
        audioManager: AudioManager,
        seekPreferences: SeekPreferencesRepository,
        soundsPreferences: SoundsPreferencesRepository,
        @SeekScope scope: CoroutineScope,
        whisperPlayer: WhisperPlayer,
        voiceGuidePlayer: VoiceGuidePlayer,
        @TalkRecordingActive talkRecordingActive: StateFlow<Boolean>,
        haptics: SeekHaptics,
    ): SeekSoundPlaying = SeekSoundPlayer(
        context = context,
        audioManager = audioManager,
        seekPreferences = seekPreferences,
        soundsPreferences = soundsPreferences,
        scope = scope,
        gate = SeekPingGate(
            isWhisperPlaying = { whisperPlayer.isAnyChannelPlaying.value },
            isVoiceGuidePlaying = {
                voiceGuidePlayer.state.value is VoiceGuidePlayer.State.Playing
            },
            isTalkRecordingActive = { talkRecordingActive.value },
        ),
        haptics = haptics,
    )

    /**
     * Production senses (iOS `SeekSenses` defaults,
     * `ActiveWalkViewModel+Seek.swift:19-30@c1745e8`). The reveal
     * whisper picker mirrors `randomDownloadedRevealWhisper` (`:255-260`):
     * one random, non-retired, locally-downloaded whisper — never a
     * fetch; none available → the ritual proceeds bowl-only.
     */
    @Provides
    @Singleton
    fun provideSeekSenses(
        soundPlayer: SeekSoundPlaying,
        haptics: SeekHaptics,
        whisperPlayer: WhisperPlayer,
        whisperManifestService: WhisperManifestService,
    ): SeekSenses = SeekSenses(
        soundPlayer = soundPlayer,
        arrivalHaptic = haptics::arrival,
        breathInHaptic = haptics::breathIn,
        pickRevealWhisper = {
            whisperManifestService.manifest.value?.whispers.orEmpty()
                .filter { it.isActive && whisperPlayer.isAvailable(it) }
                .randomOrNull()
        },
        playWhisper = whisperPlayer::play,
    )
}
