// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekChainGeneratorTest {

    private val home = SeekPoint(latitude = 42.8782, longitude = -8.5448)

    private fun chain(minutes: Int, seed: ULong): SeekChain =
        SeekChainGenerator.generate(minutes, home, SeekSeededGenerator(seed))

    private fun pathLength(clearings: List<SeekClearing>, from: SeekPoint): Double {
        var total = 0.0
        var cursor = from
        for (clearing in clearings) {
            total += SeekChainGenerator.distance(cursor, clearing.center)
            cursor = clearing.center
        }
        return total
    }

    private fun crowReach(budgetMeters: Double): Double =
        budgetMeters / SeekTuning.STREET_WINDING_FACTOR

    // Clearing count bands

    @Test
    fun `thirty minute seek generates exactly one clearing`() {
        for (seed in 0 until 100) {
            assertEquals(1, chain(30, seed.toULong()).clearings.size)
        }
    }

    @Test
    fun `three hour seek generates two or three and the band varies`() {
        val counts = mutableSetOf<Int>()
        for (seed in 0 until 100) {
            val count = chain(180, seed.toULong()).clearings.size
            assertTrue("seed $seed produced $count", count in 2..3)
            counts.add(count)
        }
        assertEquals("band should actually vary across seeds", setOf(2, 3), counts)
    }

    @Test
    fun `one hour seek stays within band`() {
        val counts = mutableSetOf<Int>()
        for (seed in 0 until 100) {
            counts.add(chain(60, seed.toULong()).clearings.size)
        }
        assertEquals(setOf(1, 2), counts)
    }

    @Test
    fun `duration clamps at one and two hundred forty minutes`() {
        assertEquals(chain(1, 5uL), chain(0, 5uL))
        assertEquals(chain(1, 5uL), chain(-10, 5uL))
        assertEquals(chain(240, 5uL), chain(999, 5uL))
        assertEquals(
            SeekChainGenerator.walkableBudgetMeters(240),
            chain(999, 5uL).budgetMeters,
            1e-9,
        )
    }

    // Chain geometry

    @Test
    fun `single clearing lands near the one way reach`() {
        for (seed in 0 until 50) {
            val result = chain(30, seed.toULong())
            val fraction = SeekChainGenerator.distance(result.clearings[0].center, home) /
                crowReach(result.budgetMeters)
            assertTrue("seed $seed: fraction $fraction", fraction >= 0.84)
            assertTrue("seed $seed: fraction $fraction", fraction <= 1.01)
        }
    }

    @Test
    fun `multi clearing chain final lands near the one way reach`() {
        for (seed in 0 until 50) {
            val result = chain(180, seed.toULong())
            val last = result.clearings.last()
            val fraction = SeekChainGenerator.distance(last.center, home) /
                crowReach(result.budgetMeters)
            assertTrue(
                "seed $seed: the seek is one-way — the final clearing belongs near the walking limit, not near home",
                fraction >= 0.84,
            )
            assertTrue("seed $seed: fraction $fraction", fraction <= 1.01)
        }
    }

    @Test
    fun `chain marches outward never doubling back`() {
        for (seed in 0 until 50) {
            val result = chain(180, seed.toULong())
            var previous = 0.0
            for (clearing in result.clearings) {
                val fromStart = SeekChainGenerator.distance(home, clearing.center)
                assertTrue(
                    "seed $seed: each clearing should be farther out than the last",
                    fromStart > previous,
                )
                previous = fromStart
            }
        }
    }

    @Test
    fun `seeded generation is deterministic`() {
        assertEquals(chain(120, 7uL), chain(120, 7uL))
        assertNotEquals(chain(120, 7uL), chain(120, 8uL))
    }

    // Constraints across seeds, durations, latitudes

    @Test
    fun `constraints hold across seeds durations and latitudes`() {
        for (latitude in listOf(0.0, 45.5, 60.2)) {
            val start = SeekPoint(latitude = latitude, longitude = -8.5)
            for (duration in listOf(30, 60, 120, 180)) {
                for (seed in 0 until 80) {
                    val result = SeekChainGenerator.generate(
                        duration,
                        start,
                        SeekSeededGenerator(seed.toULong()),
                    )
                    assertConstraints(result, start, "lat $latitude dur $duration seed $seed")
                }
            }
        }
    }

    private fun assertConstraints(result: SeekChain, home: SeekPoint, context: String) {
        for (clearing in result.clearings) {
            assertTrue(
                "$context: radius ${clearing.radiusMeters}",
                clearing.radiusMeters in SeekTuning.CLEARING_RADIUS_RANGE,
            )
            assertTrue(
                "$context: clearing too close to start",
                SeekChainGenerator.distance(home, clearing.center) >=
                    SeekTuning.MIN_START_DISTANCE_METERS * 0.9,
            )
        }
        for (i in result.clearings.indices) {
            for (j in i + 1 until result.clearings.size) {
                assertTrue(
                    "$context: clearings too close together",
                    SeekChainGenerator.distance(
                        result.clearings[i].center,
                        result.clearings[j].center,
                    ) >= SeekTuning.MIN_SPACING_METERS * 0.9,
                )
            }
        }
        assertTrue(
            "$context: one-way chain not walkable within budget",
            pathLength(result.clearings, home) <= crowReach(result.budgetMeters) * 1.15,
        )
    }

    @Test
    fun `tiny duration returns best effort chain at the along floor`() {
        for (seed in 0 until 50) {
            val result = chain(1, seed.toULong())
            assertEquals("seed $seed: generation must never fail", 1, result.clearings.size)
            val clearing = result.clearings[0]
            val fromStart = SeekChainGenerator.distance(home, clearing.center)
            assertTrue("seed $seed: along floor should hold at $fromStart", fromStart >= 249.0)
            assertTrue("seed $seed: along floor should cap at $fromStart", fromStart <= 251.0)
            assertTrue(
                "seed $seed: radius ${clearing.radiusMeters}",
                clearing.radiusMeters in SeekTuning.CLEARING_RADIUS_RANGE,
            )
        }
    }

    // Reroll

    @Test
    fun `reroll keeps prefix and replaces active and downstream`() {
        val original = chain(180, 11uL)
        val current = SeekPoint(latitude = home.latitude + 0.01, longitude = home.longitude)
        val rerolled = original.regeneratingRemainder(
            fromActiveIndex = 1,
            current = current,
            remainingBudgetMeters = original.budgetMeters * 0.6,
            rng = SeekSeededGenerator(99uL),
        )
        assertEquals(original.clearings.size, rerolled.clearings.size)
        assertEquals(original.clearings.take(1), rerolled.clearings.take(1))
        assertNotEquals(original.clearings[1], rerolled.clearings[1])
        assertEquals(
            "the chain keeps the original budget",
            original.budgetMeters,
            rerolled.budgetMeters,
            1e-9,
        )
    }

    @Test
    fun `reroll remainder is walkable within remaining budget`() {
        for (seed in 0 until 50) {
            val original = chain(180, seed.toULong())
            val current = SeekPoint(
                latitude = home.latitude + 0.008,
                longitude = home.longitude - 0.004,
            )
            val remaining = original.budgetMeters * 0.6
            val rerolled = original.regeneratingRemainder(
                fromActiveIndex = 1,
                current = current,
                remainingBudgetMeters = remaining,
                rng = SeekSeededGenerator((seed + 1000).toULong()),
            )
            val path = pathLength(rerolled.clearings.drop(1), current)
            assertTrue("seed $seed: path $path", path <= crowReach(remaining) * 1.15)
        }
    }

    @Test
    fun `reroll single clearing seek yields reachable non degenerate clearing`() {
        for (seed in 0 until 50) {
            val original = chain(30, seed.toULong())
            val current = SeekPoint(
                latitude = home.latitude + 0.004,
                longitude = home.longitude + 0.002,
            )
            val rerolled = original.regeneratingRemainder(
                fromActiveIndex = 0,
                current = current,
                remainingBudgetMeters = original.budgetMeters * 0.7,
                rng = SeekSeededGenerator((seed + 2000).toULong()),
            )
            assertEquals(1, rerolled.clearings.size)
            assertTrue(
                "seed $seed: rerolled clearing degenerate at walker's feet",
                SeekChainGenerator.distance(current, rerolled.clearings[0].center) >=
                    SeekTuning.MIN_START_DISTANCE_METERS * 0.9,
            )
        }
    }

    @Test
    fun `reroll invalid index returns chain unchanged`() {
        val original = chain(60, 3uL)
        assertEquals(
            original,
            original.regeneratingRemainder(
                fromActiveIndex = original.clearings.size,
                current = home,
                remainingBudgetMeters = 1000.0,
                rng = SeekSeededGenerator(4uL),
            ),
        )
        assertEquals(
            original,
            original.regeneratingRemainder(
                fromActiveIndex = -1,
                current = home,
                remainingBudgetMeters = 1000.0,
                rng = SeekSeededGenerator(4uL),
            ),
        )
    }

    @Test
    fun `reroll budget floor keeps regenerated remainder non degenerate`() {
        for (seed in 0 until 50) {
            val original = chain(180, seed.toULong())
            val current = SeekPoint(latitude = home.latitude + 0.02, longitude = home.longitude)
            val rerolled = original.regeneratingRemainder(
                fromActiveIndex = 1,
                current = current,
                remainingBudgetMeters = 10.0,
                rng = SeekSeededGenerator((seed + 3000).toULong()),
            )
            val floorReach = crowReach(SeekTuning.REROLL_MIN_BUDGET_METERS)
            val fraction = SeekChainGenerator.distance(current, rerolled.clearings.last().center) /
                floorReach
            assertTrue("seed $seed: final clearing fraction $fraction", fraction >= 0.84)
            assertTrue("seed $seed: final clearing fraction $fraction", fraction <= 1.01)
        }
    }
}
