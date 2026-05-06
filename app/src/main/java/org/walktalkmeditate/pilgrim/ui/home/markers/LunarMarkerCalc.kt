// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.runtime.Immutable
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@Immutable
data class LunarMarker(
    val idTag: String,
    val xPx: Float,
    val yPx: Float,
    val illumination: Double,
    val isWaxing: Boolean,
)

internal data class LunarEvent(
    val instantMs: Long,
    val illumination: Double,
    val isWaxing: Boolean,
)

/**
 * Half-synodic-month constant. Verbatim iOS literal `14.76` — NOT
 * `MoonCalc.SYNODIC_DAYS / 2.0` (which would be 14.76529...). Stage
 * 14-A Documented iOS deviation #8: the `< 1.5` day window absorbs
 * the rounding. Carrying iOS verbatim keeps cross-platform behavior
 * identical.
 */
private const val HALF_CYCLE_DAYS = 14.76

/**
 * Compute lunar full/new-moon markers in the snapshot date range.
 * Returns empty list when fewer than 2 snapshots (no interval to
 * bracket).
 */
fun computeLunarMarkers(
    snapshots: List<WalkSnapshot>,
    dotPositions: List<DotPosition>,
    viewportWidthPx: Float,
): List<LunarMarker> {
    if (snapshots.size < 2 || dotPositions.size < 2) return emptyList()
    val earliestMs = snapshots.minOf { it.startMs }
    val latestMs = snapshots.maxOf { it.startMs }
    val events = findLunarEvents(earliestMs, latestMs)
    val results = mutableListOf<LunarMarker>()
    events.forEachIndexed { index, event ->
        val triple = interpolatePosition(event.instantMs, snapshots, dotPositions)
            ?: return@forEachIndexed
        val (posA, posB, fraction) = triple
        val y = posA.yPx + fraction.toFloat() * (posB.yPx - posA.yPx)
        val midX = posA.centerXPx + fraction.toFloat() * (posB.centerXPx - posA.centerXPx)
        val markerX = if (midX > viewportWidthPx / 2f) midX - 20f else midX + 20f
        results += LunarMarker(
            idTag = "lunar-$index",
            xPx = markerX,
            yPx = y,
            illumination = event.illumination,
            isWaxing = event.isWaxing,
        )
    }
    return results
}

/**
 * Walks a 1-day step through the date range looking for near-full and
 * near-new moons; refines via ±36h ÷ 6h-step search around each hit.
 */
internal fun findLunarEvents(startMs: Long, endMs: Long): List<LunarEvent> {
    if (endMs <= startMs) return emptyList()
    val out = mutableListOf<LunarEvent>()
    var checkMs = startMs
    val oneDay = Duration.ofDays(1).toMillis()
    val skipDays = (HALF_CYCLE_DAYS.toInt() - 1).coerceAtLeast(1)  // = 13
    val skipMs = Duration.ofDays(skipDays.toLong()).toMillis()
    while (checkMs <= endMs) {
        val phase = MoonCalc.moonPhase(Instant.ofEpochMilli(checkMs))
        val isNearNew = phase.ageInDays < 1.5 || phase.ageInDays > 28.0
        val isNearFull = abs(phase.ageInDays - HALF_CYCLE_DAYS) < 1.5
        if (isNearNew || isNearFull) {
            val refinedMs = refinePeak(checkMs, isFullMoon = isNearFull)
            val refinedPhase = MoonCalc.moonPhase(Instant.ofEpochMilli(refinedMs))
            out += LunarEvent(
                instantMs = refinedMs,
                illumination = refinedPhase.illumination,
                isWaxing = refinedPhase.isWaxing,
            )
            checkMs += skipMs
        } else {
            checkMs += oneDay
        }
    }
    return out
}

/**
 * Refine to the peak of a near-full or near-new moon by sweeping ±36h
 * in 6h steps and picking the timestamp with max score. For full,
 * score = illumination; for new, score = 1 - illumination.
 */
internal fun refinePeak(nearMs: Long, isFullMoon: Boolean): Long {
    var bestMs = nearMs
    var bestScore = -1.0
    val sixHours = Duration.ofHours(6).toMillis()
    var offset = -36L * 60L * 60L * 1000L
    val end = 36L * 60L * 60L * 1000L
    while (offset <= end) {
        val ms = nearMs + offset
        val phase = MoonCalc.moonPhase(Instant.ofEpochMilli(ms))
        val score = if (isFullMoon) phase.illumination else 1.0 - phase.illumination
        if (score > bestScore) {
            bestScore = score
            bestMs = ms
        }
        offset += sixHours
    }
    return bestMs
}

/**
 * Verbatim port of iOS `interpolatePosition(for:positions:)`.
 *
 * Walks `0..(snapshots.size - 2)` looking for the interval bracketing
 * `targetMs`. When found, returns `(posA, posB, fraction)` where
 * fraction is in [0, 1] aligned with the iOS `dateA < dateB` flip.
 *
 * Edge case: when `dateA == dateB`, totalInterval = 0 triggers the
 * `else 0.5` branch. The marker lands at the midpoint between two
 * dot positions which are themselves visually adjacent — verbatim
 * iOS behavior.
 */
internal fun interpolatePosition(
    targetMs: Long,
    snapshots: List<WalkSnapshot>,
    dotPositions: List<DotPosition>,
): Triple<DotPosition, DotPosition, Double>? {
    if (snapshots.size < 2 || dotPositions.size < 2) return null
    for (i in 0 until snapshots.size - 1) {
        val dateA = snapshots[i].startMs
        val dateB = snapshots[i + 1].startMs
        val earlier = kotlin.math.min(dateA, dateB)
        val later = kotlin.math.max(dateA, dateB)
        if (targetMs in earlier..later) {
            val totalInterval = (later - earlier).toDouble()
            val rawFraction = if (totalInterval > 0.0) {
                (targetMs - earlier).toDouble() / totalInterval
            } else {
                0.5
            }
            val adjusted = if (dateA < dateB) rawFraction else 1.0 - rawFraction
            return Triple(dotPositions[i], dotPositions[i + 1], adjusted)
        }
    }
    return null
}
