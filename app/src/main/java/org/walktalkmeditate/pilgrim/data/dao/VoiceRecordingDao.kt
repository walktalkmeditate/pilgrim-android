// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

@Dao
interface VoiceRecordingDao {
    @Insert
    suspend fun insert(recording: VoiceRecording): Long

    @Update
    suspend fun update(recording: VoiceRecording)

    @Delete
    suspend fun delete(recording: VoiceRecording)

    @Query("SELECT * FROM voice_recordings WHERE id = :id")
    suspend fun getById(id: Long): VoiceRecording?

    @Query("SELECT * FROM voice_recordings WHERE walk_id = :walkId ORDER BY start_timestamp ASC")
    suspend fun getForWalk(walkId: Long): List<VoiceRecording>

    /**
     * Live-updating flow for the Walk Summary recordings list. Emits
     * a new List on every insert/update/delete that touches the given
     * walk's recordings.
     */
    @Query("SELECT * FROM voice_recordings WHERE walk_id = :walkId ORDER BY start_timestamp ASC")
    fun observeForWalk(walkId: Long): Flow<List<VoiceRecording>>

    /**
     * All recordings across all walks, newest first. Preview API for a
     * future Recordings tab; not read by Stage 2-A.
     */
    @Query("SELECT * FROM voice_recordings ORDER BY start_timestamp DESC")
    fun observeAll(): Flow<List<VoiceRecording>>

    @Query("SELECT COUNT(*) FROM voice_recordings WHERE walk_id = :walkId")
    suspend fun countForWalk(walkId: Long): Int
}
