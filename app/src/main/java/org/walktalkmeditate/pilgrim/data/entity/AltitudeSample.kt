// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Barometric altitude samples — kept distinct from [RouteDataSample]
 * because a barometer produces higher-accuracy altitude independent of
 * GPS fix quality. Matches iOS where CMAltimeter samples are stored
 * separately from CoreLocation fixes.
 */
@Entity(
    tableName = "altitude_samples",
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
data class AltitudeSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    val timestamp: Long,
    @ColumnInfo(name = "altitude_meters")
    val altitudeMeters: Double,
    @ColumnInfo(name = "vertical_accuracy")
    val verticalAccuracyMeters: Float? = null,
)
