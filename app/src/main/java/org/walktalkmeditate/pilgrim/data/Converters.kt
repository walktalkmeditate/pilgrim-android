// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.TypeConverter
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

/**
 * Room type converters for domain enums. Fallback semantics on read:
 * an unknown string (e.g., a newer enum variant persisted by a future
 * version read by an older binary, or an imported `.pilgrim` file that
 * predates a value's rename) returns a safe default instead of throwing.
 * A future fix might surface these as a dedicated `UNKNOWN` sentinel;
 * for now we keep the domain enums closed and pick a conservative default.
 */
class Converters {
    @TypeConverter
    fun walkEventTypeToString(type: WalkEventType): String = type.name

    @TypeConverter
    fun stringToWalkEventType(name: String): WalkEventType =
        WalkEventType.entries.firstOrNull { it.name == name } ?: WalkEventType.PAUSED

    @TypeConverter
    fun activityTypeToString(type: ActivityType): String = type.name

    @TypeConverter
    fun stringToActivityType(name: String): ActivityType =
        ActivityType.entries.firstOrNull { it.name == name } ?: ActivityType.WALKING
}
