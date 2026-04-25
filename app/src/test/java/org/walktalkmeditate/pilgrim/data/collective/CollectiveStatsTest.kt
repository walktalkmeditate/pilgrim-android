// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectiveStatsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `decodes the worker's full response shape`() {
        val raw = """
            {"total_walks":42,"total_distance_km":12.5,"total_meditation_min":30,
             "total_talk_min":15,"last_walk_at":"2026-04-25T01:00:00Z",
             "streak_days":3,"streak_date":"2026-04-25"}
        """.trimIndent()
        val stats = json.decodeFromString<CollectiveStats>(raw)
        assertEquals(42, stats.totalWalks)
        assertEquals(12.5, stats.totalDistanceKm, 0.001)
        assertEquals(30, stats.totalMeditationMin)
        assertEquals(15, stats.totalTalkMin)
        assertEquals("2026-04-25T01:00:00Z", stats.lastWalkAt)
        assertEquals(3, stats.streakDays)
    }

    @Test
    fun `decodes a fresh-database response with nulls`() {
        val raw = """
            {"total_walks":0,"total_distance_km":0,"total_meditation_min":0,
             "total_talk_min":0,"last_walk_at":null,"streak_days":null,
             "streak_date":null}
        """.trimIndent()
        val stats = json.decodeFromString<CollectiveStats>(raw)
        assertEquals(0, stats.totalWalks)
        assertNull(stats.lastWalkAt)
        assertNull(stats.streakDays)
    }

    @Test
    fun `walkedInLastHour true when lastWalkAt within an hour`() {
        val now = 1_700_000_000_000L
        val twentyMinAgo = Instant.ofEpochMilli(now - 20 * 60 * 1000L).toString()
        val stats = baseStats().copy(lastWalkAt = twentyMinAgo)
        assertTrue(stats.walkedInLastHour(now))
    }

    @Test
    fun `walkedInLastHour false when lastWalkAt over an hour ago`() {
        val now = 1_700_000_000_000L
        val twoHoursAgo = Instant.ofEpochMilli(now - 2 * 3600 * 1000L).toString()
        val stats = baseStats().copy(lastWalkAt = twoHoursAgo)
        assertFalse(stats.walkedInLastHour(now))
    }

    @Test
    fun `walkedInLastHour false when lastWalkAt null`() {
        assertFalse(baseStats().copy(lastWalkAt = null).walkedInLastHour())
    }

    @Test
    fun `walkedInLastHour false on malformed lastWalkAt`() {
        assertFalse(baseStats().copy(lastWalkAt = "not-a-date").walkedInLastHour())
    }

    private fun baseStats() = CollectiveStats(
        totalWalks = 1,
        totalDistanceKm = 1.0,
        totalMeditationMin = 0,
        totalTalkMin = 0,
    )
}
