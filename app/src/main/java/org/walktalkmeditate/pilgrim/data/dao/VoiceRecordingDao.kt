// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.core.threads.WalkLite
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * U6: narrow two-column projection for [ThreadsBackfillRunner][org.walktalkmeditate.pilgrim.core.threads.ThreadsBackfillRunner]'s
 * snapshot — mirrors iOS `DataManager.transcribedRecordingsSnapshot()`'s
 * `(uuid, transcript)` tuple (BEH-61).
 */
data class TranscribedRecordingSnapshot(val uuid: String, val transcription: String)

/**
 * U7: one row of the recording→walk join — [VoiceRecordingDao.recordingWalkLiteIndex]'s
 * raw Room projection shape (parity spec DAT-62: the recording→walk join
 * is what makes `ThreadStore.build`'s `distinctWalkIds` possible — a bare
 * walk list has no such attribution). [toWalkLite] converts the raw
 * `start_timestamp` column into the [Instant] the pure `core/threads`
 * layer expects, keeping that layer free of any Room coupling.
 */
data class RecordingWalkLiteRow(
    val uuid: String,
    val walkId: Long,
    val startTimestamp: Long,
    val intention: String?,
    val weatherCondition: String?,
) {
    fun toWalkLite(): WalkLite = WalkLite(
        walkId = walkId,
        startedAt = Instant.ofEpochMilli(startTimestamp),
        intention = intention,
        weatherCondition = weatherCondition,
    )
}

/**
 * U7: one row of [VoiceRecordingDao.recordingTimestampIndex] — the
 * RECORDING's own start timestamp, a distinct granularity from
 * [RecordingWalkLiteRow.startTimestamp] (that walk's start) — parity
 * spec DAT-64 keeps both indices separate on purpose.
 */
data class RecordingTimestampRow(val uuid: String, val startTimestamp: Long)

/** U7: one row of [VoiceRecordingDao.recordingPaceIndex]. */
data class RecordingPaceRow(val uuid: String, val wordsPerMinute: Double?)

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

    /**
     * U7: recording uuid → its owning walk's [org.walktalkmeditate.pilgrim.core.threads.WalkLite] —
     * the recording-uuid-keyed join [org.walktalkmeditate.pilgrim.core.threads.ThreadStore.build]
     * requires (parity spec DAT-62: a bare walk list alone cannot produce
     * `distinctWalkIds`). Bounded projection — no full `Walk`/`VoiceRecording`
     * materialization.
     */
    @Query(
        "SELECT vr.uuid AS uuid, w.id AS walkId, w.start_timestamp AS startTimestamp, " +
            "w.intention AS intention, w.weather_condition AS weatherCondition " +
            "FROM voice_recordings vr JOIN walks w ON vr.walk_id = w.id",
    )
    suspend fun recordingWalkLiteIndex(): List<RecordingWalkLiteRow>

    /**
     * U7: recording uuid → that RECORDING's own start timestamp — a
     * distinct granularity from [recordingWalkLiteIndex]'s walk-level
     * date (DAT-64). Reserved for the senses block's own 30-day window
     * and coordinate lookups (a later unit); kept as its own bounded
     * projection now so that consumer never needs a fourth query shape.
     */
    @Query("SELECT uuid, start_timestamp AS startTimestamp FROM voice_recordings")
    suspend fun recordingTimestampIndex(): List<RecordingTimestampRow>

    /**
     * U7: recording uuid → wordsPerMinute — the iOS `(context, wordsPerMinute)`
     * tuple [org.walktalkmeditate.pilgrim.core.threads.ThreadsDossierFormatter]'s
     * pace-correlation clause is built from.
     */
    @Query("SELECT uuid, words_per_minute AS wordsPerMinute FROM voice_recordings")
    suspend fun recordingPaceIndex(): List<RecordingPaceRow>
}
