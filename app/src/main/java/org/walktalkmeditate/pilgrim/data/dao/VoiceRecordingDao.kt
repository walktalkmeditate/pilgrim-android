// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * U6: narrow two-column projection for [ThreadsBackfillRunner][org.walktalkmeditate.pilgrim.core.threads.ThreadsBackfillRunner]'s
 * snapshot — mirrors iOS `DataManager.transcribedRecordingsSnapshot()`'s
 * `(uuid, transcript)` tuple (BEH-61).
 */
data class TranscribedRecordingSnapshot(val uuid: String, val transcription: String)

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

    /**
     * Update the transcription text for an existing recording. Used by
     * the Walk Summary tap-to-edit affordance and by retranscribe (the
     * worker writes the new transcript via this path). Setting
     * [transcription] to null effectively re-queues the row for the
     * transcription worker, since [observeForWalk] subscribers will see
     * the pending-state placeholder again.
     */
    @Query("UPDATE voice_recordings SET transcription = :transcription WHERE id = :id")
    suspend fun updateTranscription(id: Long, transcription: String?)

    @Query("DELETE FROM voice_recordings WHERE walk_id = :walkId")
    suspend fun deleteByWalkId(walkId: Long): Int

    /**
     * Walks still holding untranscribed recordings, for the U9
     * model-download success re-kick — each id gets its
     * `transcribe-walk-<id>` re-enqueued once the model verifies.
     */
    @Query("SELECT DISTINCT walk_id FROM voice_recordings WHERE transcription IS NULL")
    suspend fun walkIdsWithNullTranscription(): List<Long>

    /**
     * U6: every already-transcribed recording, across all walks — the
     * one-time backfill's own raw material. No `ORDER BY`: the runner
     * sorts by uuid itself so the sort discipline lives with the
     * consumer that depends on it for checkpoint determinism, not
     * silently in the query.
     */
    @Query("SELECT uuid, transcription FROM voice_recordings WHERE transcription IS NOT NULL")
    suspend fun transcribedSnapshot(): List<TranscribedRecordingSnapshot>
}
