// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import java.io.File
import java.io.FileDescriptor
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.audio.honor.WayVoicePlaybackListener
import org.walktalkmeditate.pilgrim.audio.honor.WayVoicePlayer
import org.walktalkmeditate.pilgrim.audio.soundscape.WayVoiceSoundscapeDuck
import org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperPlayer
import org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory
import org.walktalkmeditate.pilgrim.data.whisper.WhisperDefinition
import org.walktalkmeditate.pilgrim.walk.honor.WayVoiceListener

/** Each fake writes a short line to one shared [log], so a test can read the order across players. */
internal typealias AudioLog = MutableList<String>

internal fun audioLog(): AudioLog = Collections.synchronizedList(mutableListOf())

internal fun whisper(id: String) = WhisperDefinition(
    id = id,
    title = id,
    category = WhisperCategory.Presence,
    audioFileName = id,
    durationSec = 5.0,
)

/** The Way voice player; a test ends each play through its [Play.listener]. */
internal class FakeWayVoicePlayer(private val log: AudioLog) : WayVoicePlayer {

    class Play(val file: File, val volume: Float, val listener: WayVoicePlaybackListener)

    val plays: MutableList<Play> = Collections.synchronizedList(mutableListOf())
    private var loaded = false
    private var lastRate = 1f
    override val rate: Float get() = lastRate
    override var isPlaying = false

    override fun play(file: File, volume: Float, listener: WayVoicePlaybackListener) {
        plays += Play(file, volume, listener)
        loaded = true
        isPlaying = true
        log += "play ${file.nameWithoutExtension} $volume"
    }

    override fun pause() {
        isPlaying = false
        log += "pause"
    }

    override fun resume() {
        isPlaying = loaded
        log += "resume"
    }

    override fun stop() {
        loaded = false
        isPlaying = false
        log += "stop"
    }

    override fun seek(fraction: Double) {
        log += "seek $fraction"
    }

    override fun setRate(rate: Float) {
        lastRate = rate
        log += "rate $rate"
    }

    override fun positionMillis(): Long = 0L
}

/** The soundscape at the walker's 0.4, as iOS's `SoundscapePlayer` starts. */
internal class FakeWayVoiceSoundscape(private val log: AudioLog) : WayVoiceSoundscapeDuck {

    override var targetVolume = 0.4f
    var held = false

    override fun holdDuck(level: Float) {
        targetVolume = level
        held = true
        log += "hold $level"
    }

    override fun releaseDuck(level: Float) {
        targetVolume = level
        held = false
        log += "release $level"
    }

    /** What `SoundscapePlayer.play` does mid-voice: the walker's [level] overwrites the duck. */
    fun startedAt(level: Float) {
        targetVolume = level
        held = false
    }
}

/** A whisper player whose audibility the test sets, as `isAnyChannelPlaying` reports it. */
internal class AudibleWhisperPlayer(private val log: AudioLog) : FakeWhisperPlayer() {

    val audible = MutableStateFlow(false)
    val played: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override val isAnyChannelPlaying: StateFlow<Boolean> get() = audible

    override fun play(definition: WhisperDefinition) {
        super.play(definition)
        played += definition.id
    }

    override fun stop() {
        super.stop()
        audible.value = false
        log += "stop whisper"
    }

    override fun cut() {
        super.cut()
        audible.value = false
        log += "stop whisper"
    }
}

/**
 * A gate token whose process the test can kill: [die] delivers every
 * linked death notice, and a dead token refuses a link as a dead proxy does.
 */
internal class FakeUiBinder(dead: Boolean = false) : IBinder {

    @Volatile var dead: Boolean = dead
        private set
    private val recipients = Collections.synchronizedList(mutableListOf<IBinder.DeathRecipient>())
    val linked: Int get() = recipients.size

    fun die() {
        dead = true
        val notified = recipients.toList()
        recipients.clear()
        notified.forEach { it.binderDied() }
    }

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        if (dead) throw DeadObjectException()
        recipients += recipient
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = recipients.remove(recipient)

    override fun getInterfaceDescriptor(): String? = null

    override fun pingBinder(): Boolean = !dead

    override fun isBinderAlive(): Boolean = !dead

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun dump(fd: FileDescriptor, args: Array<out String>?) = Unit

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) = Unit

    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
}

internal fun started(kind: UiAudioGateKind, seq: Long, token: IBinder?) =
    UiAudioGateSignal(kind = kind, held = true, seq = seq, token = token)

internal fun ended(kind: UiAudioGateKind, seq: Long) =
    UiAudioGateSignal(kind = kind, held = false, seq = seq, token = null)

internal class FakeUiAudioGates : UiAudioGateSource {

    val value = MutableStateFlow(UiAudioGates())

    override val gates: StateFlow<UiAudioGates> = value
}

/** The session's side of one play. */
internal class RecordingVoiceListener : WayVoiceListener {

    var finished = 0
    var failed = 0
    val ends get() = finished + failed

    override fun onFinished() {
        finished += 1
    }

    override fun onFailed() {
        failed += 1
    }
}
