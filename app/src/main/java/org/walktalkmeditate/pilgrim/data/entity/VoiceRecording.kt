// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "voice_recordings",
    foreignKeys = [
        ForeignKey(
            entity = Walk::class,
            parentColumns = ["id"],
            childColumns = ["walk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("walk_id"),
        Index("uuid", unique = true),
    ],
)
data class VoiceRecording(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    @ColumnInfo(name = "start_timestamp")
    val startTimestamp: Long,
    @ColumnInfo(name = "end_timestamp")
    val endTimestamp: Long,
    @ColumnInfo(name = "duration_millis")
    val durationMillis: Long,
    @ColumnInfo(name = "file_relative_path")
    val fileRelativePath: String,
    val transcription: String? = null,
    @ColumnInfo(name = "words_per_minute")
    val wordsPerMinute: Double? = null,
    @ColumnInfo(name = "is_enhanced")
    val isEnhanced: Boolean = false,
) {
    init {
        // Reject end < start first so a wall-clock regression (NTP resync,
        // DST edge, user-set clock) during a recording surfaces as a
        // construction error instead of a negative durationMillis that
        // would silently undercount SUM aggregates. Stage 2-B finalize
        // should clamp or refuse to commit in that case.
        require(endTimestamp >= startTimestamp) {
            "endTimestamp ($endTimestamp) must be >= startTimestamp ($startTimestamp) " +
                "for walk $walkId, recording $uuid (wall-clock regression?)"
        }
        // durationMillis is stored redundantly (end - start) for fast
        // SUM aggregates, which means construction paths in Stage 2-B
        // (AudioRecord → file finalize) and Stage 2-D (transcription
        // update) must keep the three fields consistent. Catch any
        // drift at write time rather than in a future analytics query.
        require(durationMillis == endTimestamp - startTimestamp) {
            "durationMillis ($durationMillis) must equal endTimestamp - startTimestamp " +
                "(${endTimestamp - startTimestamp}) for walk $walkId, recording $uuid"
        }
    }
}
