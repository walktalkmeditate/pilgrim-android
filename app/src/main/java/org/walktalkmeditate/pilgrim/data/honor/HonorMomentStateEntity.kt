// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * How a voice stopped. Only [FINISHED] and [FAILED] are iOS's `onFinished`
 * paths, the two after which an untouched card retires on its own
 * (parity spec D §5.2); [DROPPED] is a waiting voice abandoned before it
 * ever played.
 */
enum class HonorVoiceEnd { FINISHED, FAILED, SKIPPED, INTERRUPTED, REPLACED, DROPPED }

/**
 * One moment of the honored Way on one walk: the moment tracker's
 * `reached` set and `queue`, and each voice's start, end, and "heard"
 * (marked at hand-off, before any sound, parity spec C §9). Written only
 * by `:tracker`; deleted by the finalize step.
 */
@Entity(
    tableName = "honor_moment_states",
    primaryKeys = ["walk_id", "moment_id"],
    foreignKeys = [
        ForeignKey(
            entity = Walk::class,
            parentColumns = ["id"],
            childColumns = ["walk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HonorMomentStateEntity(
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    @ColumnInfo(name = "moment_id")
    val momentId: String,
    @ColumnInfo(name = "reached_at")
    val reachedAt: Long? = null,
    /** Place in the tracker's FIFO of waiting voices; null when not waiting. */
    @ColumnInfo(name = "queue_position")
    val queuePosition: Int? = null,
    @ColumnInfo(name = "voice_started_at")
    val voiceStartedAt: Long? = null,
    @ColumnInfo(name = "voice_ended_at")
    val voiceEndedAt: Long? = null,
    @ColumnInfo(name = "voice_end")
    val voiceEnd: HonorVoiceEnd? = null,
    val heard: Boolean = false,
)
