// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class WalkEventType {
    PAUSED,
    RESUMED,
    MEDITATION_START,
    MEDITATION_END,
    WAYPOINT_MARKED,
}

@Entity(
    tableName = "walk_events",
    foreignKeys = [
        ForeignKey(
            entity = Walk::class,
            parentColumns = ["id"],
            childColumns = ["walk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("walk_id"), Index("walk_id", "timestamp")],
)
data class WalkEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    val timestamp: Long,
    @ColumnInfo(name = "event_type")
    val eventType: WalkEventType,
)
