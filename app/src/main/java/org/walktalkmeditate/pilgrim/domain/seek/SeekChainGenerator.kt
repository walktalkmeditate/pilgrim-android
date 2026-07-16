// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.random.nextInt
import org.walktalkmeditate.pilgrim.domain.EARTH_RADIUS_METERS
import org.walktalkmeditate.pilgrim.domain.haversineMeters

/**
 * Starting assumptions from the plan (origin R4) — tuned on real walks,
 * not commitments. Regions are 80–120 m across, so radii are 40–60 m.
 */
object SeekTuning {
    const val PACE_MINUTES_PER_MILE = 24.5
    const val RESERVE_FRACTION = 0.25
    val CLEARING_RADIUS_RANGE = 40.0..60.0
    const val MIN_START_DISTANCE_METERS = 250.0
    const val MIN_SPACING_METERS = 300.0

    /**
     * Streets aren't crow-flies: walked distance runs ~1.25× the straight
     * line, so placement converts the walking budget into crow-flies reach.
     */
    const val STREET_WINDING_FACTOR = 1.25

    /**
     * The seek is one-way: the final clearing lands near the walking
     * limit, and the way home belongs to the walker.
     */
    val FINAL_CLEARING_FRACTION = 0.85..1.0
    const val ALONG_JITTER_FRACTION = 0.06
    const val LATERAL_WANDER_FRACTION = 0.12
    const val METERS_PER_MILE = 1609.344
    const val PLACEMENT_ATTEMPTS = 12

    /**
     * Floor for a rerolled remainder budget so a regenerated chain is
     * never degenerate — the single source for both the engine's estimate
     * and [SeekChain.regeneratingRemainder]'s clamp. (iOS hosts this in
     * `SeekEngineTuning`; spec D5.)
     */
    const val REROLL_MIN_BUDGET_METERS = MIN_START_DISTANCE_METERS * 2.5
}

/**
 * Generates the random clearing chain for a seek. Pure and deterministic
 * under an injected [Random]; production callers pass a [SeekSeededGenerator]
 * built from [SeekSeed].
 */
object SeekChainGenerator {

    fun clearingCountBand(durationMinutes: Int): IntRange = when {
        durationMinutes < 45 -> 1..1
        durationMinutes < 90 -> 1..2
        else -> 2..3
    }

    fun walkableBudgetMeters(durationMinutes: Int): Double {
        val walkingMinutes = durationMinutes * (1 - SeekTuning.RESERVE_FRACTION)
        return walkingMinutes / SeekTuning.PACE_MINUTES_PER_MILE * SeekTuning.METERS_PER_MILE
    }

    fun generate(durationMinutes: Int, start: SeekPoint, rng: Random): SeekChain {
        val clamped = durationMinutes.coerceIn(1, 240)
        val budget = walkableBudgetMeters(clamped)
        val count = rng.nextInt(clearingCountBand(clamped))
        return SeekChain(
            clearings = placeChain(count, budget, start, rng),
            budgetMeters = budget,
        )
    }

    internal fun placeChain(
        count: Int,
        budgetMeters: Double,
        from: SeekPoint,
        rng: Random,
    ): List<SeekClearing> {
        var bestCandidate = emptyList<SeekClearing>()
        var bestScore = Double.NEGATIVE_INFINITY

        repeat(SeekTuning.PLACEMENT_ATTEMPTS) {
            val candidate = placeOutbound(count, budgetMeters, from, rng)
            val score = constraintScore(candidate, budgetMeters, from)
            if (score >= 0) return candidate
            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidate
            }
        }
        return bestCandidate
    }

    /**
     * One construction for every case, fresh seek and reroll alike: the
     * chain wanders outward along a random bearing with lateral drift,
     * and the final clearing lands near the crow-flies reach of the
     * walking budget. The seek is one-way — no leg home is budgeted.
     */
    private fun placeOutbound(
        count: Int,
        budgetMeters: Double,
        from: SeekPoint,
        rng: Random,
    ): List<SeekClearing> {
        val reach = budgetMeters / SeekTuning.STREET_WINDING_FACTOR
        val heading = rng.nextDouble(0.0, 360.0)
        val side = if (rng.nextBoolean()) 1.0 else -1.0
        val lastFraction = rng.nextDouble(
            SeekTuning.FINAL_CLEARING_FRACTION.start,
            SeekTuning.FINAL_CLEARING_FRACTION.endInclusive,
        )

        return (1..count).map { index ->
            val isLast = index == count
            val jitter = if (isLast) {
                0.0
            } else {
                rng.nextDouble(-SeekTuning.ALONG_JITTER_FRACTION, SeekTuning.ALONG_JITTER_FRACTION)
            }
            val along = max(
                (lastFraction * index / count + jitter) * reach,
                SeekTuning.MIN_START_DISTANCE_METERS,
            )
            val lateral = if (isLast) {
                0.0
            } else {
                side * rng.nextDouble(0.2, 1.0) * reach * SeekTuning.LATERAL_WANDER_FRACTION
            }
            val onTrack = destination(from, bearingDegrees = heading, distanceMeters = along)
            val point = destination(onTrack, bearingDegrees = heading + 90, distanceMeters = lateral)
            SeekClearing(
                center = point,
                radiusMeters = rng.nextDouble(
                    SeekTuning.CLEARING_RADIUS_RANGE.start,
                    SeekTuning.CLEARING_RADIUS_RANGE.endInclusive,
                ),
            )
        }
    }

    /**
     * Non-negative when every constraint holds; otherwise the (negative)
     * worst violation, so a best-effort candidate can be kept when the
     * attempt budget runs out. Generation must never fail outright.
     */
    private fun constraintScore(
        clearings: List<SeekClearing>,
        budgetMeters: Double,
        from: SeekPoint,
    ): Double {
        var worst = 0.0

        for (clearing in clearings) {
            worst = min(worst, distance(from, clearing.center) - SeekTuning.MIN_START_DISTANCE_METERS)
        }
        for (i in clearings.indices) {
            for (j in i + 1 until clearings.size) {
                worst = min(
                    worst,
                    distance(clearings[i].center, clearings[j].center) - SeekTuning.MIN_SPACING_METERS,
                )
            }
        }

        var pathLength = 0.0
        var cursor = from
        for (clearing in clearings) {
            pathLength += distance(cursor, clearing.center)
            cursor = clearing.center
        }
        val reach = budgetMeters / SeekTuning.STREET_WINDING_FACTOR
        worst = min(worst, reach * 1.1 - pathLength)

        return worst
    }

    // Spherical math (pure; no map-SDK dependency in this model).

    fun distance(from: SeekPoint, to: SeekPoint): Double =
        haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)

    fun destination(from: SeekPoint, bearingDegrees: Double, distanceMeters: Double): SeekPoint {
        val angular = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        return SeekPoint(latitude = Math.toDegrees(lat2), longitude = Math.toDegrees(lon2))
    }

    fun bearingDegrees(from: SeekPoint, to: SeekPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return Math.toDegrees(atan2(y, x))
    }
}
