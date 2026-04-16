// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "route_data_samples",
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
data class RouteDataSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "horizontal_accuracy")
    val horizontalAccuracyMeters: Float? = null,
    @ColumnInfo(name = "speed_mps")
    val speedMetersPerSecond: Float? = null,
)
