// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl
import org.walktalkmeditate.pilgrim.walk.honor.HonorArrivalRecorder
import org.walktalkmeditate.pilgrim.walk.honor.HonorExternalGates
import org.walktalkmeditate.pilgrim.walk.honor.HonorGatePort
import org.walktalkmeditate.pilgrim.walk.honor.HonorHapticsPort
import org.walktalkmeditate.pilgrim.walk.honor.SoundscapeDuckPort
import org.walktalkmeditate.pilgrim.walk.honor.WayVoiceListener
import org.walktalkmeditate.pilgrim.walk.honor.WayVoicePort

/**
 * The Honor session's collaborators. Resolve these only in `:tracker`,
 * through the service's `Provider`s: arrival binds to the tracker's
 * controller, which the UI process must never build.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HonorSessionModule {

    @Binds
    abstract fun bindArrivalRecorder(impl: WalkControllerImpl): HonorArrivalRecorder

    @Binds
    abstract fun bindWayVoicePort(impl: UnwiredHonorAudio): WayVoicePort

    @Binds
    abstract fun bindSoundscapeDuckPort(impl: UnwiredHonorAudio): SoundscapeDuckPort

    @Binds
    abstract fun bindHonorHapticsPort(impl: UnwiredHonorAudio): HonorHapticsPort

    @Binds
    abstract fun bindHonorGatePort(impl: UnwiredHonorAudio): HonorGatePort
}

/**
 * Stands in for U18's player, arbiter, and haptics until they land: every
 * play reports that it could not start, so a voice counts as heard and
 * the engine moves on (a failed play on iOS, parity spec C §10), and the
 * gates stay open. U18 replaces these bindings; the flag-off release
 * never builds the session that would use them.
 */
class UnwiredHonorAudio @Inject constructor() :
    WayVoicePort, SoundscapeDuckPort, HonorHapticsPort, HonorGatePort {

    private val open = MutableStateFlow(HonorExternalGates())

    override val gates: StateFlow<HonorExternalGates> = open.asStateFlow()

    override fun play(file: File, gain: Float, listener: WayVoiceListener) = listener.onFailed()

    override fun playReply(file: File, listener: WayVoiceListener) = listener.onFailed()

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() = Unit

    override fun seek(fraction: Double) = Unit

    override fun setRate(rate: Float) = Unit

    override fun duckForWayVoice() = Unit

    override fun restoreAfterWayVoice() = Unit

    override fun momentReached() = Unit

    override fun softTap() = Unit

    override fun arrival() = Unit
}
