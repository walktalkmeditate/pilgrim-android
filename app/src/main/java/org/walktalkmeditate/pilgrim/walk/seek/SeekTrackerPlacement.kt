// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.seek

import org.walktalkmeditate.pilgrim.data.seek.SeekSessionEntity
import org.walktalkmeditate.pilgrim.domain.seek.SeekChain
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainCodec
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint

/**
 * Where a seek walk's engine, senses, glance, and arrival writes run once
 * the walk records (plan U25). The pre-departure boot stays in the UI
 * either way: `:tracker` doesn't exist before ACTION_START.
 */
enum class SeekPlacement {
    /** Today's placement: the UI process's orchestrator adopts its pre-departure engine. */
    UI_PROCESS,

    /**
     * The release flag on: Begin hands the session to `:tracker`, which
     * restarts the engine from it, and the UI draws fog, pulse, and phase
     * from Room.
     */
    TRACKER,
    ;

    companion object {
        /** Flag-dark by construction: only the release flag moves Seek into `:tracker`. */
        fun of(releaseFlagOn: Boolean): SeekPlacement = if (releaseFlagOn) TRACKER else UI_PROCESS
    }
}

/**
 * The sonar settings `:tracker` plays by. It can't read the UI's preferences
 * (reads freeze in a cached process), so they ride the start intent and
 * every later change arrives by intent and lands in the session row.
 */
data class SeekSonarSettings(
    val sonarEnabled: Boolean,
    val sonarVolume: Float,
    val soundsEnabled: Boolean,
)

/**
 * The pre-departure session as `:tracker` restarts it at Begin: the durable
 * facts (the chain as the ready screen left it, a pre-departure reroll
 * included, its duration, tint, seed, and intention), where the engine
 * stood (its clearing, its last distance and the fog's bucket, and the
 * walker's last fix), so the fog and crescent hold across the hand-off,
 * when its next pulse was due, so the sonar keeps its cadence, and the
 * sonar settings at Begin. It rides ACTION_START, and `:tracker` writes it
 * into the walk's seek session row inside the start's mutex, the row a
 * revival rebuilds from.
 */
data class SeekStart(
    val chain: SeekChain,
    val activeIndex: Int,
    val durationMinutes: Int,
    val tintHex: String?,
    val seed: Long,
    val seededAtEpochMillis: Long,
    val intention: String?,
    val distanceToActiveMeters: Double?,
    val fogBucket: Int?,
    val walker: SeekPoint?,
    val nextPulseDueAtMillis: Long?,
    val sonar: SeekSonarSettings,
) {
    fun sessionRow(walkId: Long) = SeekSessionEntity(
        walkId = walkId,
        chain = SeekChainCodec.encode(chain),
        durationMinutes = durationMinutes,
        tintHex = tintHex,
        seed = seed,
        seededAt = seededAtEpochMillis,
        intention = intention,
        activeIndex = activeIndex.coerceIn(0, (chain.clearings.size - 1).coerceAtLeast(0)),
        distanceToActiveMeters = distanceToActiveMeters,
        fogBucket = fogBucket,
        walkerLatitude = walker?.latitude,
        walkerLongitude = walker?.longitude,
        nextPulseDueAt = nextPulseDueAtMillis,
        sonarEnabled = sonar.sonarEnabled,
        sonarVolume = sonar.sonarVolume,
        soundsEnabled = sonar.soundsEnabled,
    )
}

/**
 * Begin's side of the hand-off, which the walk screen's Start calls with
 * the release flag on. Implemented by [SeekOrchestrator]; the default does
 * nothing, as with the flag off.
 */
interface SeekHandOff {

    /**
     * The staged session as `:tracker` needs it, or null with none staged.
     * From here the UI's sonar and haptics are quiet, so the pre-departure
     * engine and `:tracker`'s never both sound, until the walk takes the
     * session or [cancel] gives it back. A start that only timed out may
     * still land, so it stays quiet.
     */
    suspend fun begin(): SeekStart?

    /** The start failed: the ready screen's sonar and haptics speak again. */
    fun cancel()

    companion object {
        val None: SeekHandOff = object : SeekHandOff {
            override suspend fun begin(): SeekStart? = null
            override fun cancel() = Unit
        }
    }
}
