// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

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
    indices = [
        Index("walk_id"),
        Index("walk_id", "timestamp"),
        Index("uuid", unique = true),
    ],
)
data class RouteDataSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "altitude_meters")
    val altitudeMeters: Double? = null,
    @ColumnInfo(name = "horizontal_accuracy")
    val horizontalAccuracyMeters: Float? = null,
    @ColumnInfo(name = "vertical_accuracy")
    val verticalAccuracyMeters: Float? = null,
    @ColumnInfo(name = "speed_mps")
    val speedMetersPerSecond: Float? = null,
    @ColumnInfo(name = "direction_degrees")
    val directionDegrees: Float? = null,
)
