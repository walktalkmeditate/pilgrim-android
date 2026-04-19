// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

class GoshuinMilestonesTest {

    private fun walk(
        id: Long,
        date: LocalDate,
        distance: Double = 1_000.0,
    ): WalkMilestoneInput {
        val ts = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return WalkMilestoneInput(
            walkId = id,
            uuid = "uuid-$id",
            startTimestamp = ts,
            distanceMeters = distance,
        )
    }

    @Test fun `first walk - is FirstWalk milestone`() {
        val w = walk(1L, LocalDate.of(2026, 4, 19))
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = w, allFinished = listOf(w), hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.FirstWalk, m)
    }

    @Test fun `tenth walk - NthWalk(10)`() {
        // Give walk #5 a much greater distance so it (not the most-
        // recent walk #10) wins LongestWalk. Without this the precedence
        // LongestWalk > NthWalk would fire LongestWalk on the 10th walk
        // since all-equal distances tie-break to the most recent.
        val list = (1..10).map { i ->
            walk(i.toLong(), LocalDate.of(2026, 1, i), distance = if (i == 5) 9_999.0 else 1_000.0)
        }.reversed()
        val tenth = list.first()
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = tenth, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.NthWalk(10), m)
    }

    @Test fun `twentieth walk - NthWalk(20)`() {
        // Same fixture trick as the 10th-walk test: walk #5 carries the
        // longest distance so the 20th walk doesn't double up.
        val list = (1..20).map { i ->
            walk(i.toLong(), LocalDate.of(2026, 1, 1).plusDays((i - 1).toLong()), distance = if (i == 5) 9_999.0 else 1_000.0)
        }.reversed()
        val twentieth = list.first()
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = twentieth, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.NthWalk(20), m)
    }

    @Test fun `seventh walk in middle of list - no milestone`() {
        // 7 walks all on the same Winter day (same season-year), all
        // distance 1000.0. The middle walk (index 3 in most-recent-first
        // list = walk #4 in 1-based numbering) has neither first/longest/
        // nth/firstOfSeason. The OLDEST is the firstOfSeason (1st walk),
        // the most-recent is the longest tie-break winner.
        val list = (1..7).map { walk(it.toLong(), LocalDate.of(2026, 1, 1).plusDays((it - 1).toLong()), distance = 1000.0) }.reversed()
        val middle = list[3]
        val m = GoshuinMilestones.detect(walkIndex = 3, walk = middle, allFinished = list, hemisphere = Hemisphere.Northern)
        assertNull(m)
    }

    @Test fun `oldest walk among two - is FirstWalk regardless of distance`() {
        val w1 = walk(1L, LocalDate.of(2026, 1, 1))
        val w2 = walk(2L, LocalDate.of(2026, 1, 2))
        val list = listOf(w2, w1)
        val mOlder = GoshuinMilestones.detect(walkIndex = 1, walk = w1, allFinished = list, hemisphere = Hemisphere.Northern)
        // w1 IS the firstWalk (oldest, walkNumber == 1)
        assertEquals(GoshuinMilestone.FirstWalk, mOlder)
    }

    @Test fun `longest walk - LongestWalk milestone`() {
        val short = walk(1L, LocalDate.of(2026, 1, 1), distance = 1_000.0)
        val long = walk(2L, LocalDate.of(2026, 1, 2), distance = 5_000.0)
        val medium = walk(3L, LocalDate.of(2026, 1, 3), distance = 2_000.0)
        val list = listOf(medium, long, short)
        val m = GoshuinMilestones.detect(walkIndex = 1, walk = long, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.LongestWalk, m)
    }

    @Test fun `longest walk tiebreaker - most recent wins via maxByOrNull stability`() {
        // Two walks with identical max distance. `maxByOrNull` returns
        // the FIRST element with the max — most-recent-first list means
        // the newer walk wins.
        val newer = walk(2L, LocalDate.of(2026, 1, 2), distance = 5_000.0)
        val older = walk(1L, LocalDate.of(2026, 1, 1), distance = 5_000.0)
        val list = listOf(newer, older)
        val mNewer = GoshuinMilestones.detect(walkIndex = 0, walk = newer, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.LongestWalk, mNewer)
        val mOlder = GoshuinMilestones.detect(walkIndex = 1, walk = older, allFinished = list, hemisphere = Hemisphere.Northern)
        // Older is NOT the longest by tiebreaker, so falls through.
        // 2 walks total, walkNumber for older = 1 → FirstWalk wins precedence.
        assertEquals(GoshuinMilestone.FirstWalk, mOlder)
    }

    @Test fun `first of season - second spring walk same year does not count`() {
        // Add a winter walk first so spring1 is not the first walk
        // overall. Then two spring walks: spring1 gets firstOfSeason,
        // spring2 doesn't. Add a 4th walk with much greater distance
        // so neither spring walk gets LongestWalk.
        val winter = walk(1L, LocalDate.of(2026, 1, 15), distance = 100.0)
        val spring1 = walk(2L, LocalDate.of(2026, 3, 21), distance = 100.0)
        val spring2 = walk(3L, LocalDate.of(2026, 4, 1), distance = 100.0)
        val winter2 = walk(4L, LocalDate.of(2026, 1, 16), distance = 9_999.0)
        // Most-recent-first: list[0]=winter2(Jan 16), list[1]=spring2(Apr 1),
        // list[2]=spring1(Mar 21), list[3]=winter(Jan 15)
        val list = listOf(spring2, winter2, spring1, winter)
        val mSpring1 = GoshuinMilestones.detect(walkIndex = 2, walk = spring1, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.FirstOfSeason(Season.Spring), mSpring1)
        val mSpring2 = GoshuinMilestones.detect(walkIndex = 0, walk = spring2, allFinished = list, hemisphere = Hemisphere.Northern)
        assertNull(mSpring2)
    }

    @Test fun `first of season - across years marks each year's first separately`() {
        val s2026 = walk(1L, LocalDate.of(2026, 3, 21), distance = 100.0)
        val winter2027 = walk(2L, LocalDate.of(2027, 1, 1), distance = 9_999.0)
        val s2027 = walk(3L, LocalDate.of(2027, 3, 21), distance = 100.0)
        val list = listOf(s2027, winter2027, s2026)
        val m2026 = GoshuinMilestones.detect(walkIndex = 2, walk = s2026, allFinished = list, hemisphere = Hemisphere.Northern)
        // s2026 is the FIRST walk overall — FirstWalk wins precedence.
        assertEquals(GoshuinMilestone.FirstWalk, m2026)
        val m2027 = GoshuinMilestones.detect(walkIndex = 0, walk = s2027, allFinished = list, hemisphere = Hemisphere.Northern)
        // s2027 is the first spring walk of year 2027 (s2026 was a
        // different year). FirstOfSeason fires.
        assertEquals(GoshuinMilestone.FirstOfSeason(Season.Spring), m2027)
    }

    @Test fun `precedence - FirstWalk overrides LongestWalk on a single walk`() {
        val solo = walk(1L, LocalDate.of(2026, 1, 1), distance = 5_000.0)
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = solo, allFinished = listOf(solo), hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.FirstWalk, m)
    }

    @Test fun `precedence - LongestWalk overrides NthWalk(10)`() {
        // 10 walks where the 10th is also the longest. Precedence picks
        // LongestWalk, not NthWalk(10).
        val list = (1..10).map { i ->
            walk(i.toLong(), LocalDate.of(2026, 1, i), distance = if (i == 10) 9_999.0 else 1_000.0)
        }.reversed()
        val tenth = list.first()
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = tenth, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.LongestWalk, m)
    }

    @Test fun `seasonFor - northern hemisphere months map correctly`() {
        val zone = ZoneId.systemDefault()
        fun ts(year: Int, month: Int) = LocalDate.of(year, month, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(Season.Spring, GoshuinMilestones.seasonFor(ts(2026, 4), Hemisphere.Northern))
        assertEquals(Season.Summer, GoshuinMilestones.seasonFor(ts(2026, 7), Hemisphere.Northern))
        assertEquals(Season.Autumn, GoshuinMilestones.seasonFor(ts(2026, 10), Hemisphere.Northern))
        assertEquals(Season.Winter, GoshuinMilestones.seasonFor(ts(2026, 1), Hemisphere.Northern))
    }

    @Test fun `seasonFor - southern hemisphere flips`() {
        val zone = ZoneId.systemDefault()
        fun ts(year: Int, month: Int) = LocalDate.of(year, month, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(Season.Autumn, GoshuinMilestones.seasonFor(ts(2026, 4), Hemisphere.Southern))
        assertEquals(Season.Winter, GoshuinMilestones.seasonFor(ts(2026, 7), Hemisphere.Southern))
        assertEquals(Season.Spring, GoshuinMilestones.seasonFor(ts(2026, 10), Hemisphere.Southern))
        assertEquals(Season.Summer, GoshuinMilestones.seasonFor(ts(2026, 1), Hemisphere.Southern))
    }

    @Test fun `ordinal - teens use th`() {
        assertEquals("11th", GoshuinMilestones.ordinal(11))
        assertEquals("12th", GoshuinMilestones.ordinal(12))
        assertEquals("13th", GoshuinMilestones.ordinal(13))
        assertEquals("113th", GoshuinMilestones.ordinal(113))
    }

    @Test fun `ordinal - regular suffixes`() {
        assertEquals("1st", GoshuinMilestones.ordinal(1))
        assertEquals("2nd", GoshuinMilestones.ordinal(2))
        assertEquals("3rd", GoshuinMilestones.ordinal(3))
        assertEquals("4th", GoshuinMilestones.ordinal(4))
        assertEquals("21st", GoshuinMilestones.ordinal(21))
        assertEquals("22nd", GoshuinMilestones.ordinal(22))
        assertEquals("23rd", GoshuinMilestones.ordinal(23))
        assertEquals("100th", GoshuinMilestones.ordinal(100))
        assertEquals("101st", GoshuinMilestones.ordinal(101))
    }

    @Test fun `label - exhaustive coverage`() {
        assertEquals("First Walk", GoshuinMilestones.label(GoshuinMilestone.FirstWalk))
        assertEquals("Longest Walk", GoshuinMilestones.label(GoshuinMilestone.LongestWalk))
        assertEquals("10th Walk", GoshuinMilestones.label(GoshuinMilestone.NthWalk(10)))
        assertEquals("21st Walk", GoshuinMilestones.label(GoshuinMilestone.NthWalk(21)))
        assertEquals("First of Spring", GoshuinMilestones.label(GoshuinMilestone.FirstOfSeason(Season.Spring)))
        assertEquals("First of Winter", GoshuinMilestones.label(GoshuinMilestone.FirstOfSeason(Season.Winter)))
    }
}
