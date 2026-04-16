// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ActivityType {
    WALKING,
    TALKING,
    MEDITATING,
}

@Entity(
    tableName = "activity_intervals",
    foreignKeys = [
        ForeignKey(
            entity = Walk::class,
            parentColumns = ["id"],
            childColumns = ["walk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("walk_id"), Index("walk_id", "start_timestamp")],
)
data class ActivityInterval(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    @ColumnInfo(name = "start_timestamp")
    val startTimestamp: Long,
    @ColumnInfo(name = "end_timestamp")
    val endTimestamp: Long,
    @ColumnInfo(name = "activity_type")
    val activityType: ActivityType,
)
