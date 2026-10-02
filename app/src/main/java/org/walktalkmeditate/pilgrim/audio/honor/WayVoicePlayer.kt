// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.honor

import java.io.File

/** How one play handed to [WayVoicePlayer] ended; called at most once, on the main thread. */
interface WayVoicePlaybackListener {

    /** The file played to its end, or the player gave the voice up to another app for good. */
    fun onEnded()

    /** The file would not open, decode, or start, before or during playback. */
    fun onFailed()
}

/**
 * The one player of Way voices and the walker's replies, without iOS's
 * priority rules (those live in
 * [org.walktalkmeditate.pilgrim.audio.walk.WalkAudioArbiter]): iOS
 * `WayVoicePlayer`'s `AVAudioPlayer` handling (`WayVoicePlayer.swift:58-178@7c200bf`)
 * plus Android's platform handlers. A process singleton, so its rate
 * outlives a walk, as iOS's does (pilgrim-ios #105).
 *
 * Call it from the main thread only.
 */
interface WayVoicePlayer {

    /**
     * The rate every play starts at: the last [setRate], for the life of the
     * process. Nothing resets it between walks (spec C §7.4).
     */
    val rate: Float

    /** A play is loaded and sounding, as iOS's `player?.isPlaying`: false while paused by anyone. */
    val isPlaying: Boolean

    /**
     * Starts [file] at [volume], replacing the current play without
     * reporting it. The audio focus held for the replaced play carries over,
     * as iOS's `start()` keeps its session consumer across one run.
     */
    fun play(file: File, volume: Float, listener: WayVoicePlaybackListener)

    fun pause()

    fun resume()

    /** Stops the current play without reporting it, and gives up the audio focus. */
    fun stop()

    /** To [fraction] of the current file, clamped to `0…0.999` so a scrub never lands on its end. */
    fun seek(fraction: Double)

    fun setRate(rate: Float)

    /** Where the current play is in its file, for the offsets a revival restores; 0 with none loaded. */
    fun positionMillis(): Long
}
