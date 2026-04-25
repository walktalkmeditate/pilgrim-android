// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CollectiveStats(
    @SerialName("total_walks") val totalWalks: Int,
    @SerialName("total_distance_km") val totalDistanceKm: Double,
    @SerialName("total_meditation_min") val totalMeditationMin: Int,
    @SerialName("total_talk_min") val totalTalkMin: Int,
    @SerialName("last_walk_at") val lastWalkAt: String? = null,
    @SerialName("streak_days") val streakDays: Int? = null,
    @SerialName("streak_date") val streakDate: String? = null,
) {
    fun walkedInLastHour(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val ts = lastWalkAt ?: return false
        return try {
            val instant = Instant.parse(ts)
            (nowEpochMs - instant.toEpochMilli()) < ONE_HOUR_MS
        } catch (_: DateTimeParseException) {
            false
        }
    }

    private companion object {
        const val ONE_HOUR_MS = 3_600_000L
    }
}
