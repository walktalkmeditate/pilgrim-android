// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.TypeConverter
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.HonorVoiceEnd
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase

/**
 * Room type converters for domain enums. Fallback semantics on read:
 * an unknown string (e.g., a newer enum variant persisted by a future
 * version read by an older binary) returns a safe default instead of
 * throwing. Walk events fall back to [WalkEventType.UNKNOWN] (mirrors
 * iOS `EventType.init(rawValue:)` default) — this protects v1.2.0+
 * readers of future vocabulary; already-shipped v1.1.x binaries map
 * unknown names to PAUSED, which stands (in-place downgrades are
 * unsupported). Activity types keep the conservative WALKING default.
 * The Honor enums fall back to the reading that claims least: a walk
 * still walking, an own-walk source, a recovered finish (no delta), and
 * a voice that failed.
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

    @TypeConverter
    fun honorPhaseToString(phase: HonorPhase): String = phase.name

    @TypeConverter
    fun stringToHonorPhase(name: String): HonorPhase =
        HonorPhase.entries.firstOrNull { it.name == name } ?: HonorPhase.WALKING

    @TypeConverter
    fun honorSourceKindToString(kind: HonorSourceKind): String = kind.name

    @TypeConverter
    fun stringToHonorSourceKind(name: String): HonorSourceKind =
        HonorSourceKind.entries.firstOrNull { it.name == name } ?: HonorSourceKind.OWN_WALK

    @TypeConverter
    fun honorFinishKindToString(kind: HonorFinishKind): String = kind.name

    @TypeConverter
    fun stringToHonorFinishKind(name: String): HonorFinishKind =
        HonorFinishKind.entries.firstOrNull { it.name == name } ?: HonorFinishKind.RECOVERED

    @TypeConverter
    fun honorVoiceEndToString(end: HonorVoiceEnd): String = end.name

    @TypeConverter
    fun stringToHonorVoiceEnd(name: String): HonorVoiceEnd =
        HonorVoiceEnd.entries.firstOrNull { it.name == name } ?: HonorVoiceEnd.FAILED
}
