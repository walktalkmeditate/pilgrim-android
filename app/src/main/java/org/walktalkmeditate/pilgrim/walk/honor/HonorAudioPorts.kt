// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/*
 * What the Honor session drives, implemented in U18 (the walk audio arbiter,
 * the Way voice player, the haptics). The session calls every port from its
 * own dispatcher, in the order it persists and then performs each engine
 * event; an implementation hops to its player's thread itself.
 */

/** How one play handed to [WayVoicePort] ended. Called at most once per play, from any thread. */
interface WayVoiceListener {

    /** The file played to its end: iOS's `onFinished` after a natural end. */
    fun onFinished()

    /**
     * The file would not open, decode, or start, before or during playback:
     * iOS's `finish(notify: true)` (`WayVoicePlayer.swift:166-178@7c200bf`).
     */
    fun onFailed()
}

/**
 * The one player of Way voices and the walker's replies (iOS `WayVoicePlaying`,
 * `WayVoicePlayer.swift:5-16@7c200bf`). One play at a time: a new [play] or
 * [playReply] replaces the current one without calling its listener, and
 * [stop] never calls it either. The player may hold a play behind a guide
 * prompt before its first sound (parity spec C §2).
 */
interface WayVoicePort {

    /**
     * [gain] is relative to the Way voice's level: 1 for a spoken voice, 0.5
     * for ambience (iOS `voiceVolume(for:)`, `ActiveWalkViewModel+Honor.swift:342-345@7c200bf`).
     */
    fun play(file: File, gain: Float, listener: WayVoiceListener)

    /** The walker's own earlier reply, at the full voice level (`ActiveWalkViewModel+Replies.swift:74@7c200bf`). */
    fun playReply(file: File, listener: WayVoiceListener)

    fun pause()

    fun resume()

    fun stop()

    /** To [fraction] of the current file, clamped by the player to `0…0.999` (`WayVoicePlayer.swift:115-119@7c200bf`). */
    fun seek(fraction: Double)

    /**
     * Applies to the current play and every later one for the life of the
     * process: the session never resets it between walks, as iOS doesn't
     * (pilgrim-ios #105).
     */
    fun setRate(rate: Float)
}

/**
 * The soundscape under a Way voice (parity spec C §6, correction 12). The
 * session calls [duckForWayVoice] when it hands the player a voice or a
 * reply while nothing was held, and [restoreAfterWayVoice] on every exit
 * that leaves the player empty: a natural end, a failure, a skip, a drop, a
 * stop, and teardown. A voice replacing another keeps the duck. The level,
 * the 0.5 s ramps, and waiting while a voice is parked behind a prompt are
 * the implementation's.
 */
interface SoundscapeDuckPort {

    fun duckForWayVoice()

    fun restoreAfterWayVoice()
}

/** The four Honor haptics (parity spec C §13, correction 13); none waits for a voice. Water ahead is stage-only. */
interface HonorHapticsPort {

    /** A place, rest, sitting, photo, or waypoint reached: iOS `.waypointDropped`, a light impact. */
    fun momentReached()

    /** iOS `.honorOffWay`, a soft impact. */
    fun softTap()

    /** Seek's three rising taps, after arrival's rows commit. */
    fun arrival()
}

/**
 * The two engine gates that live outside the walk's own state (parity
 * spec D §3.4): a recording in the UI process, and a community whisper
 * actually playing. Pause and sitting come from the walk state itself.
 */
data class HonorExternalGates(
    val recording: Boolean = false,
    val externalAudio: Boolean = false,
)

/**
 * U18's gate model publishes these; after a revival it reports both held
 * until the UI re-sends its gates for the bumped generation, or a short
 * wait passes.
 */
interface HonorGatePort {

    val gates: StateFlow<HonorExternalGates>
}
