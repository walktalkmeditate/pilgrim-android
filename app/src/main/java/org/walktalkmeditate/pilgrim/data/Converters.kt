// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.TypeConverter
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

/**
 * Room type converters for domain enums. Fallback semantics on read:
 * an unknown string (e.g., a newer enum variant persisted by a future
 * version read by an older binary) returns a safe default instead of
 * throwing. Walk events fall back to [WalkEventType.UNKNOWN] (mirrors
 * iOS `EventType.init(rawValue:)` default) — this protects v1.2.0+
 * readers of future vocabulary; already-shipped v1.1.x binaries map
 * unknown names to PAUSED, which stands (in-place downgrades are
 * unsupported). Activity types keep the conservative WALKING default.
 */
class Converters {
    @TypeConverter
    fun walkEventTypeToString(type: WalkEventType): String = type.name

    @TypeConverter
    fun stringToWalkEventType(name: String): WalkEventType =
        WalkEventType.entries.firstOrNull { it.name == name } ?: WalkEventType.UNKNOWN

    @TypeConverter
    fun activityTypeToString(type: ActivityType): String = type.name

    @TypeConverter
    fun stringToActivityType(name: String): ActivityType =
        ActivityType.entries.firstOrNull { it.name == name } ?: ActivityType.WALKING
}
