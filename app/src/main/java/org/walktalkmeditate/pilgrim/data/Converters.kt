// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.TypeConverter
import org.walktalkmeditate.pilgrim.data.entity.ActivityType
import org.walktalkmeditate.pilgrim.data.entity.WalkEventType

class Converters {
    @TypeConverter
    fun walkEventTypeToString(type: WalkEventType): String = type.name

    @TypeConverter
    fun stringToWalkEventType(name: String): WalkEventType = WalkEventType.valueOf(name)

    @TypeConverter
    fun activityTypeToString(type: ActivityType): String = type.name

    @TypeConverter
    fun stringToActivityType(name: String): ActivityType = ActivityType.valueOf(name)
}
