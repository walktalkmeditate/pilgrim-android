// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

/**
 * One pre-resolved contribution to the calligraphy path renderer.
 *
 * The renderer is pure — all per-walk inputs land here, the caller does
 * the Walk → spec conversion (see [Walk.toStrokeSpec]). That keeps the
 * draw layer testable in isolation and lets the Stage 3-D seasonal
 * engine upgrade [ink] without touching the renderer.
 *
 * @property uuid stable UUID string — seeds the FNV-1a meander hash
 * @property startMillis walk start timestamp in epoch ms — seed + seasonal tint
 * @property distanceMeters total walked distance (haversine sum)
 * @property averagePaceSecPerKm pace in sec/km; 0 → base stroke width fallback
 * @property ink resolved ink color (Stage 3-C picks by month; 3-D adds HSB shifts)
 */
@Immutable
data class CalligraphyStrokeSpec(
    val uuid: String,
    val startMillis: Long,
    val distanceMeters: Double,
    val averagePaceSecPerKm: Double,
    val ink: Color,
)

/**
 * FNV-1a 64-bit hash, ported from iOS's CalligraphyPathRenderer. Mixes
 * the UUID characters (UTF-16 code units), the startMillis, and the
 * integer meters distance.
 *
 * Not byte-identical to iOS (Swift hashes raw UUID bytes + Double
 * bit-pattern) — cross-platform determinism isn't a goal for 3-C.
 * See the spec's "iOS deviation noted" block for the rationale.
 */
internal fun fnv1aHash(spec: CalligraphyStrokeSpec): Long {
    val prime: ULong = 1099511628211UL
    var h: ULong = 14695981039346656037UL
    spec.uuid.forEach { c ->
        h = (h xor c.code.toULong()) * prime
    }
    h = (h xor spec.startMillis.toULong()) * prime
    h = (h xor spec.distanceMeters.toLong().toULong()) * prime
    return (h and 0x7FFFFFFFFFFFFFFFUL).toLong()
}

/** Meander seed in [-1, 1], deterministic for a given spec. */
internal fun meanderSeed(spec: CalligraphyStrokeSpec): Float {
    val h = fnv1aHash(spec)
    return ((h % 2000L).toFloat() / 1000f) - 1f
}

/** Horizontal X offset (in pixels) from the canvas centerX. */
internal fun xOffsetPx(
    spec: CalligraphyStrokeSpec,
    centerXPx: Float,
    maxMeanderPx: Float,
): Float {
    val h = fnv1aHash(spec)
    val normalizedOffset = (h % 1000L).toFloat() / 1000f - 0.5f
    return centerXPx + normalizedOffset * maxMeanderPx * 1.6f
}

/** Fast=300 sec/km → base, slow=900 sec/km → max; pre-taper. */
internal fun paceDrivenWidth(
    averagePaceSecPerKm: Double,
    baseWidthPx: Float,
    maxWidthPx: Float,
): Float {
    if (averagePaceSecPerKm <= 0.0) return baseWidthPx
    val clamped = averagePaceSecPerKm.coerceIn(300.0, 900.0)
    val t = ((clamped - 300.0) / (900.0 - 300.0)).toFloat()
    return baseWidthPx + t * (maxWidthPx - baseWidthPx)
}

/** Oldest walk tapers to 60% of its pace-derived width. Index 0 = newest. */
internal fun taperFactor(index: Int, total: Int): Float {
    if (total <= 1) return 1f
    return 1f - (index.toFloat() / (total - 1).toFloat()) * 0.4f
}

/** Segment opacity: newest 0.35, oldest 0.15, linear. */
internal fun segmentOpacity(index: Int, total: Int): Float {
    if (total <= 1) return 0.35f
    return 0.35f - (index.toFloat() / (total - 1).toFloat()) * 0.2f
}

/**
 * Build a [CalligraphyStrokeSpec] from a finished [Walk] + its GPS samples.
 *
 * Pre-condition: [Walk.endTimestamp] is non-null. Caller should filter.
 */
fun Walk.toStrokeSpec(samples: List<RouteDataSample>, ink: Color): CalligraphyStrokeSpec {
    val distance = walkDistanceMeters(
        samples.map { LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude) },
    )
    val endMs = endTimestamp ?: startTimestamp
    val durationSec = (endMs - startTimestamp) / 1000.0
    val pace = if (distance > 0.0 && durationSec > 0.0) durationSec / (distance / 1000.0) else 0.0
    return CalligraphyStrokeSpec(
        uuid = uuid,
        startMillis = startTimestamp,
        distanceMeters = distance,
        averagePaceSecPerKm = pace,
        ink = ink,
    )
}
