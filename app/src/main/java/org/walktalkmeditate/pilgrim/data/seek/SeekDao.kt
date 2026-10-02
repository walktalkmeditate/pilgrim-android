// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.seek

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.domain.seek.SeekEnginePhase

/**
 * The seek session table (plan U25). `:tracker` is its one writer; the UI
 * only reads it. Every write is an insert, a targeted UPDATE, or a delete:
 * never `REPLACE`. Guarded updates return the number of rows changed, so a
 * caller knows whether its compare-and-set won.
 */
@Dao
interface SeekDao {

    /** Once per walk; a second insert for the same walk fails. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: SeekSessionEntity)

    @Query("SELECT * FROM seek_sessions WHERE walk_id = :walkId")
    suspend fun getSession(walkId: Long): SeekSessionEntity?

    @Query("SELECT * FROM seek_sessions WHERE walk_id = :walkId")
    fun observeSession(walkId: Long): Flow<SeekSessionEntity?>

    @Update(entity = SeekSessionEntity::class)
    suspend fun updateDisplay(state: SeekDisplayState): Int

    /**
     * Every phase but ARRIVED, which only [recordArrival] writes. Leaving a
     * clearing forgets when it was reached.
     */
    @Query("UPDATE seek_sessions SET phase = :phase, arrived_at = NULL WHERE walk_id = :walkId")
    suspend fun setPhase(walkId: Long, phase: SeekEnginePhase): Int

    /**
     * Arrival's compare-and-set: the clearing at [activeIndex] is reached
     * once. The literals are the [SeekEnginePhase] names the converter stores.
     */
    @Query(
        "UPDATE seek_sessions SET phase = 'ARRIVED', arrived_at = :atMillis " +
            "WHERE walk_id = :walkId AND phase = 'GUIDING' AND active_index = :activeIndex",
    )
    suspend fun recordArrival(walkId: Long, activeIndex: Int, atMillis: Long): Int

    /** Applies [seq] only past the last applied one, so a replayed "Seek anew" changes nothing. */
    @Query(
        "UPDATE seek_sessions SET last_command_seq = :seq " +
            "WHERE walk_id = :walkId AND last_command_seq < :seq",
    )
    suspend fun applyCommandSeq(walkId: Long, seq: Long): Int

    /** The UI's sonar settings, applied only past the last applied [seq]. */
    @Query(
        "UPDATE seek_sessions SET sonar_enabled = :sonarEnabled, sonar_volume = :sonarVolume, " +
            "sounds_enabled = :soundsEnabled, last_preference_seq = :seq " +
            "WHERE walk_id = :walkId AND last_preference_seq < :seq",
    )
    suspend fun applyPreferences(
        walkId: Long,
        seq: Long,
        sonarEnabled: Boolean,
        sonarVolume: Float,
        soundsEnabled: Boolean,
    ): Int

    @Query("UPDATE seek_sessions SET gate_generation = gate_generation + 1 WHERE walk_id = :walkId")
    suspend fun bumpGateGeneration(walkId: Long): Int

    /**
     * 1 while [walkId] has a seek session on an unfinished walk, else 0.
     * Every session write checks it inside its own transaction, so nothing
     * lands after `finishWalkAtomic` or on a walk deleted a moment before.
     */
    @Query(
        "SELECT COUNT(*) FROM seek_sessions s INNER JOIN walks w ON w.id = s.walk_id " +
            "WHERE s.walk_id = :walkId AND w.end_timestamp IS NULL",
    )
    suspend fun countLiveSessionOnUnfinishedWalk(walkId: Long): Int

    /** The latest unfinished walk's seek session, if it has one; `:tracker` reaches the UI through Room only. */
    @Query(
        "SELECT s.walk_id AS walk_id, s.gate_generation AS gate_generation " +
            "FROM seek_sessions s INNER JOIN walks w ON w.id = s.walk_id " +
            "WHERE w.end_timestamp IS NULL ORDER BY s.walk_id DESC LIMIT 1",
    )
    fun observeLiveSessionKey(): Flow<SeekLiveSessionKey?>

    @Query("DELETE FROM seek_sessions WHERE walk_id = :walkId")
    suspend fun deleteSession(walkId: Long): Int
}
