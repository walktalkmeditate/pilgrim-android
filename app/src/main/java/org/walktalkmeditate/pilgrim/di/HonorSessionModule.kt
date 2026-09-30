// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.walktalkmeditate.pilgrim.audio.honor.ExoPlayerWayVoicePlayer
import org.walktalkmeditate.pilgrim.audio.honor.HonorHaptics
import org.walktalkmeditate.pilgrim.audio.honor.WayVoicePlayer
import org.walktalkmeditate.pilgrim.audio.soundscape.ExoPlayerSoundscapePlayer
import org.walktalkmeditate.pilgrim.audio.soundscape.WayVoiceSoundscapeDuck
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGate
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateSource
import org.walktalkmeditate.pilgrim.audio.walk.WalkAudioArbiter
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl
import org.walktalkmeditate.pilgrim.walk.honor.HonorArrivalRecorder
import org.walktalkmeditate.pilgrim.walk.honor.HonorGatePort
import org.walktalkmeditate.pilgrim.walk.honor.HonorHapticsPort
import org.walktalkmeditate.pilgrim.walk.honor.SoundscapeDuckPort
import org.walktalkmeditate.pilgrim.walk.honor.WayVoicePort

/**
 * The Honor session's collaborators, the walk audio arbiter, and the UI's
 * audio gates it reads. Resolve these only in `:tracker`, through
 * `Provider`s, and only with the release flag on: arrival binds to the
 * tracker's controller, which the UI process must never build, and the
 * arbiter changes whisper timing.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HonorSessionModule {

    @Binds
    abstract fun bindArrivalRecorder(impl: WalkControllerImpl): HonorArrivalRecorder

    @Binds
    abstract fun bindWayVoicePort(impl: WalkAudioArbiter): WayVoicePort

    @Binds
    abstract fun bindSoundscapeDuckPort(impl: WalkAudioArbiter): SoundscapeDuckPort

    @Binds
    abstract fun bindHonorGatePort(impl: WalkAudioArbiter): HonorGatePort

    @Binds
    abstract fun bindHonorHapticsPort(impl: HonorHaptics): HonorHapticsPort

    @Binds
    abstract fun bindWayVoicePlayer(impl: ExoPlayerWayVoicePlayer): WayVoicePlayer

    @Binds
    abstract fun bindWayVoiceSoundscapeDuck(impl: ExoPlayerSoundscapePlayer): WayVoiceSoundscapeDuck

    @Binds
    abstract fun bindUiAudioGateSource(impl: UiAudioGate): UiAudioGateSource
}
