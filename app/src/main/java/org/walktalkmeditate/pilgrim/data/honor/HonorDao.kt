// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase

/** The engine's columns of a session row, updated together without touching the rest. */
data class HonorEngineState(
    @ColumnInfo(name = "walk_id") val walkId: Long,
    @ColumnInfo(name = "start_frac") val startFrac: Double?,
    @ColumnInfo(name = "anchored_by_fallback") val anchoredByFallback: Boolean,
    @ColumnInfo(name = "anchor_active_seconds") val anchorActiveSeconds: Double,
    @ColumnInfo(name = "companion_t0_seconds") val companionT0Seconds: Double,
    @ColumnInfo(name = "progress_frac") val progressFrac: Double,
    @ColumnInfo(name = "progress_high_water") val progressHighWater: Double,
    @ColumnInfo(name = "walked_frac") val walkedFrac: Double,
    @ColumnInfo(name = "off_way_since") val offWaySince: Long?,
    @ColumnInfo(name = "off_way_active_seconds") val offWayActiveSeconds: Double,
    @ColumnInfo(name = "last_reacquire_attempt") val lastReacquireAttempt: Long?,
    @ColumnInfo(name = "soft_tap_since") val softTapSince: Long?,
    @ColumnInfo(name = "soft_tap_armed") val softTapArmed: Boolean,
    @ColumnInfo(name = "arrival_inside_fixes") val arrivalInsideFixes: Int,
)

/** The playing voice's columns of a session row. */
data class HonorVoiceState(
    @ColumnInfo(name = "walk_id") val walkId: Long,
    @ColumnInfo(name = "playing_moment_id") val playingMomentId: String?,
    @ColumnInfo(name = "voice_paused") val voicePaused: Boolean,
    @ColumnInfo(name = "voice_started_at") val voiceStartedAt: Long?,
    @ColumnInfo(name = "voice_start_offset_millis") val voiceStartOffsetMillis: Long?,
    @ColumnInfo(name = "voice_pause_offset_millis") val voicePauseOffsetMillis: Long?,
    @ColumnInfo(name = "voice_rate") val voiceRate: Double,
)

/**
 * The live Honor tables and the walk marker. Session and moment rows are
 * written by `:tracker` only, card rows by the UI only. Every write is an
 * insert, a targeted UPDATE, or an upsert: never `REPLACE`, which deletes
 * the row first (and would cascade). Guarded updates return the number
 * of rows changed, so a caller knows whether its compare-and-set won.
 */
@Dao
interface HonorDao {

    /** Once per walk, at Start; a second insert for the same walk fails. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: HonorSessionEntity)

    @Query("SELECT * FROM honor_sessions WHERE walk_id = :walkId")
    suspend fun getSession(walkId: Long): HonorSessionEntity?

    @Query("SELECT * FROM honor_sessions WHERE walk_id = :walkId")
    fun observeSession(walkId: Long): Flow<HonorSessionEntity?>

    @Update(entity = HonorSessionEntity::class)
    suspend fun updateEngineState(state: HonorEngineState): Int

    @Update(entity = HonorSessionEntity::class)
    suspend fun updateVoiceState(state: HonorVoiceState): Int

    /**
     * Arrival's compare-and-set: flips the phase once and keeps its numbers
     * for the link. The literals are the [HonorPhase] names the converter stores.
     */
    @Query(
        "UPDATE honor_sessions SET phase = 'ARRIVED', arrival_their_seconds = :theirSeconds, " +
            "arrival_your_seconds = :yourSeconds WHERE walk_id = :walkId AND phase = 'WALKING'",
    )
    suspend fun recordArrival(walkId: Long, theirSeconds: Double, yourSeconds: Double): Int

    /** The first finish kind wins; recovery can't relabel a clean finish, nor the reverse. */
    @Query("UPDATE honor_sessions SET finish_kind = :kind WHERE walk_id = :walkId AND finish_kind IS NULL")
    suspend fun recordFinishKind(walkId: Long, kind: HonorFinishKind): Int

    /** Applies [seq] only past the last applied one, so a replayed command changes nothing. */
    @Query(
        "UPDATE honor_sessions SET last_command_seq = :seq " +
            "WHERE walk_id = :walkId AND last_command_seq < :seq",
    )
    suspend fun applyCommandSeq(walkId: Long, seq: Long): Int

    @Query("UPDATE honor_sessions SET gate_generation = gate_generation + 1 WHERE walk_id = :walkId")
    suspend fun bumpGateGeneration(walkId: Long): Int

    @Upsert
    suspend fun upsertMomentState(state: HonorMomentStateEntity)

    @Query("SELECT * FROM honor_moment_states WHERE walk_id = :walkId ORDER BY moment_id")
    suspend fun getMomentStates(walkId: Long): List<HonorMomentStateEntity>

    @Query("SELECT * FROM honor_moment_states WHERE walk_id = :walkId ORDER BY moment_id")
    fun observeMomentStates(walkId: Long): Flow<List<HonorMomentStateEntity>>

    /**
     * A card's flags only ever turn on, so each setter writes its own column:
     * a whole-row write from a stale copy could clear the other flag.
     */
    @Transaction
    suspend fun markCardTouched(walkId: Long, momentId: String) {
        insertCardStateIfAbsent(HonorCardStateEntity(walkId = walkId, momentId = momentId))
        setCardTouched(walkId, momentId)
    }

    @Transaction
    suspend fun markCardDismissed(walkId: Long, momentId: String) {
        insertCardStateIfAbsent(HonorCardStateEntity(walkId = walkId, momentId = momentId))
        setCardDismissed(walkId, momentId)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardStateIfAbsent(state: HonorCardStateEntity)

    @Query("UPDATE honor_card_states SET touched = 1 WHERE walk_id = :walkId AND moment_id = :momentId")
    suspend fun setCardTouched(walkId: Long, momentId: String)

    @Query("UPDATE honor_card_states SET dismissed = 1 WHERE walk_id = :walkId AND moment_id = :momentId")
    suspend fun setCardDismissed(walkId: Long, momentId: String)

    @Query("SELECT * FROM honor_card_states WHERE walk_id = :walkId ORDER BY moment_id")
    suspend fun getCardStates(walkId: Long): List<HonorCardStateEntity>

    @Query("SELECT * FROM honor_card_states WHERE walk_id = :walkId ORDER BY moment_id")
    fun observeCardStates(walkId: Long): Flow<List<HonorCardStateEntity>>

    /** Repeating the finalize step keeps the first marker. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMarker(marker: HonorWalkMarkerEntity)

    @Query("SELECT * FROM honor_walk_markers WHERE walk_uuid = :walkUuid")
    suspend fun getMarker(walkUuid: String): HonorWalkMarkerEntity?

    @Query("SELECT * FROM honor_walk_markers WHERE walk_uuid = :walkUuid")
    fun observeMarker(walkUuid: String): Flow<HonorWalkMarkerEntity?>

    /** Every live row of [walkId]; the marker is keyed by uuid and stays. */
    @Transaction
    suspend fun deleteLiveRows(walkId: Long) {
        deleteCardStates(walkId)
        deleteMomentStates(walkId)
        deleteSession(walkId)
    }

    @Query("DELETE FROM honor_card_states WHERE walk_id = :walkId")
    suspend fun deleteCardStates(walkId: Long): Int

    @Query("DELETE FROM honor_moment_states WHERE walk_id = :walkId")
    suspend fun deleteMomentStates(walkId: Long): Int

    @Query("DELETE FROM honor_sessions WHERE walk_id = :walkId")
    suspend fun deleteSession(walkId: Long): Int
}
