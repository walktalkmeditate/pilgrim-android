// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "walks",
    indices = [
        Index("start_timestamp"),
        Index("end_timestamp"),
        Index("uuid", unique = true),
    ],
)
data class Walk(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "start_timestamp")
    val startTimestamp: Long,
    @ColumnInfo(name = "end_timestamp")
    val endTimestamp: Long? = null,
    val intention: String? = null,
    val favicon: String? = null,
    val notes: String? = null,
    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double? = null,
    @ColumnInfo(name = "meditation_seconds")
    val meditationSeconds: Long? = null,
)
