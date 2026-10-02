// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.seek

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.seek.SeekEnginePhase

/**
 * The seek session of one walk with the release flag on (plan U25),
 * written only by `:tracker`. It holds the durable facts Begin handed
 * over (the chain, duration, tint, seed, and intention), the engine's
 * progress a revival restarts from, the display the UI draws its fog and
 * pulse from, and the sonar settings the UI sent, so a revived tracker
 * needs nothing from the UI process. iOS keeps all of it in its one view
 * model and never resumes a walk.
 *
 * The row stays after the walk ends and goes with it: deleting the walk
 * cascades it, and the `.pilgrim` archive strip removes it with the rest
 * of the walk's detail.
 *
 * Times are epoch milliseconds on the wall clock the engine's timers use.
 */
@Entity(
    tableName = "seek_sessions",
    foreignKeys = [
        ForeignKey(
            entity = Walk::class,
            parentColumns = ["id"],
            childColumns = ["walk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SeekSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    /** The chain as it stands, a reroll's regenerated remainder included, as [org.walktalkmeditate.pilgrim.domain.seek.SeekChainCodec] writes it. */
    val chain: String,
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int,
    /** The celestial fog tint fixed at setup, or null under an ordinary sky. */
    @ColumnInfo(name = "tint_hex")
    val tintHex: String?,
    /** The setup's [org.walktalkmeditate.pilgrim.domain.seek.SeekSeed], as its 64 bits: provenance only, never drawn from again. */
    val seed: Long,
    @ColumnInfo(name = "seeded_at")
    val seededAt: Long,
    /** The intention voiced at setup, which a reroll re-asks with. */
    val intention: String?,
    @ColumnInfo(name = "active_index")
    val activeIndex: Int = 0,
    /** Only arrival's compare-and-set writes [SeekEnginePhase.ARRIVED]. */
    val phase: SeekEnginePhase = SeekEnginePhase.GUIDING,
    /** When the active clearing was reached; its grace runs from here after a revival. */
    @ColumnInfo(name = "arrived_at")
    val arrivedAt: Long? = null,
    @ColumnInfo(name = "distance_to_active_meters")
    val distanceToActiveMeters: Double? = null,
    /** The active fog's bucket as the tracker last drew it, the UI's hysteresis reference. */
    @ColumnInfo(name = "fog_bucket")
    val fogBucket: Int? = null,
    @ColumnInfo(name = "walker_latitude")
    val walkerLatitude: Double? = null,
    @ColumnInfo(name = "walker_longitude")
    val walkerLongitude: Double? = null,
    /** Advances once per sonar pulse; the UI flares the map when it moves. */
    @ColumnInfo(name = "pulse_token")
    val pulseToken: Int = 0,
    @ColumnInfo(name = "pulse_aligned")
    val pulseAligned: Boolean = false,
    @ColumnInfo(name = "pulse_closeness")
    val pulseCloseness: Double = 0.0,
    /** When the engine's next pulse was due; an engine that takes over keeps that cadence. */
    @ColumnInfo(name = "next_pulse_due_at")
    val nextPulseDueAt: Long? = null,
    @ColumnInfo(name = "sonar_enabled")
    val sonarEnabled: Boolean,
    @ColumnInfo(name = "sonar_volume")
    val sonarVolume: Float,
    /** The master Sounds switch, which `:tracker` can't read from the UI's preferences. */
    @ColumnInfo(name = "sounds_enabled")
    val soundsEnabled: Boolean,
    /** The highest "Seek anew" sequence number applied, so a replayed one changes nothing. */
    @ColumnInfo(name = "last_command_seq")
    val lastCommandSeq: Long = 0,
    /** The highest sonar-settings sequence number applied. */
    @ColumnInfo(name = "last_preference_seq")
    val lastPreferenceSeq: Long = 0,
    /** Bumped at each session start and revival; the UI re-sends its audio gates when it changes. */
    @ColumnInfo(name = "gate_generation")
    val gateGeneration: Long = 0,
)

/**
 * The display columns of a session row, written together after each pulse
 * and each change the map shows. The phase is left out: only arrival's
 * compare-and-set writes ARRIVED, and every other phase has its own write.
 */
data class SeekDisplayState(
    @ColumnInfo(name = "walk_id") val walkId: Long,
    val chain: String,
    @ColumnInfo(name = "active_index") val activeIndex: Int,
    @ColumnInfo(name = "distance_to_active_meters") val distanceToActiveMeters: Double?,
    @ColumnInfo(name = "fog_bucket") val fogBucket: Int?,
    @ColumnInfo(name = "walker_latitude") val walkerLatitude: Double?,
    @ColumnInfo(name = "walker_longitude") val walkerLongitude: Double?,
    @ColumnInfo(name = "pulse_token") val pulseToken: Int,
    @ColumnInfo(name = "pulse_aligned") val pulseAligned: Boolean,
    @ColumnInfo(name = "pulse_closeness") val pulseCloseness: Double,
    @ColumnInfo(name = "next_pulse_due_at") val nextPulseDueAt: Long?,
)

/** The live seek session the UI's sonar settings go to, and the generation that asks for them again. */
data class SeekLiveSessionKey(
    @ColumnInfo(name = "walk_id") val walkId: Long,
    @ColumnInfo(name = "gate_generation") val gateGeneration: Long,
)

fun SeekSessionEntity.displayState() = SeekDisplayState(
    walkId = walkId,
    chain = chain,
    activeIndex = activeIndex,
    distanceToActiveMeters = distanceToActiveMeters,
    fogBucket = fogBucket,
    walkerLatitude = walkerLatitude,
    walkerLongitude = walkerLongitude,
    pulseToken = pulseToken,
    pulseAligned = pulseAligned,
    pulseCloseness = pulseCloseness,
    nextPulseDueAt = nextPulseDueAt,
)
