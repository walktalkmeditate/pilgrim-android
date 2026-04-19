// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

/**
 * Per-walk input for the goshuin seal renderer. Same shape as
 * [org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyStrokeSpec]
 * (uuid + startMillis + distance seed + pre-resolved color), plus a
 * pre-formatted [displayDistance] + [unitLabel] for the seal's
 * center-text layer. The caller resolves [ink] via
 * [org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine]
 * at `Intensity.Full`.
 */
@Immutable
data class SealSpec(
    val uuid: String,
    val startMillis: Long,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val displayDistance: String,
    val unitLabel: String,
    val ink: Color,
)

/**
 * FNV-1a 64-bit hash, seeded from the UUID (UTF-16 code units),
 * [SealSpec.startMillis], and integer-meters [SealSpec.distanceMeters].
 * Byte-wise copy of the calligraphy hash — see the Stage 4-A spec
 * for why we don't extract a shared helper yet.
 *
 * NOT byte-identical to the iOS seal hash (iOS uses SHA-256 over
 * route + durations + date). Same divergence story as the calligraphy
 * port: same walk renders differently cross-platform.
 */
internal fun fnv1aHash(spec: SealSpec): Long {
    val prime: ULong = 1099511628211UL
    var h: ULong = 14695981039346656037UL
    spec.uuid.forEach { c ->
        h = (h xor c.code.toULong()) * prime
    }
    h = (h xor spec.startMillis.toULong()) * prime
    h = (h xor spec.distanceMeters.toLong().toULong()) * prime
    return (h and 0x7FFFFFFFFFFFFFFFUL).toLong()
}

/**
 * Stretch the 64-bit FNV-1a hash to 32 deterministic bytes via
 * SplitMix64 iteration. iOS's `SealGeometry` consumes 32 bytes (from
 * SHA-256) across positions 0..31 to drive rotation, ring counts,
 * radial angles, etc. SplitMix64 is the same RNG iOS uses for its
 * seal weather-texture layer.
 */
internal fun sealHashBytes(spec: SealSpec): ByteArray {
    var state = fnv1aHash(spec).toULong()
    val bytes = ByteArray(32)
    for (i in 0 until 4) {
        state += 0x9E3779B97F4A7C15UL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9UL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBUL
        z = z xor (z shr 31)
        val longValue = z.toLong()
        for (b in 0 until 8) {
            bytes[i * 8 + b] = (longValue shr (b * 8)).toByte()
        }
    }
    return bytes
}

/** Read a byte as an unsigned int in [0, 255]. */
internal fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

/**
 * Build a [SealSpec] from a finished [Walk] + its GPS samples and a
 * caller-resolved [ink] color. Caller also formats [displayDistance]
 * + [unitLabel] — locale-aware formatting lives in
 * [org.walktalkmeditate.pilgrim.ui.walk.WalkFormat], which this data
 * class intentionally doesn't depend on.
 */
fun Walk.toSealSpec(
    samples: List<RouteDataSample>,
    ink: Color,
    displayDistance: String,
    unitLabel: String,
): SealSpec {
    val endMs = requireNotNull(endTimestamp) {
        "toSealSpec called on an unfinished walk (uuid=$uuid); filter before calling."
    }
    val distance = walkDistanceMeters(
        samples.map { LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude) },
    )
    val durationSec = (endMs - startTimestamp) / 1000.0
    return SealSpec(
        uuid = uuid,
        startMillis = startTimestamp,
        distanceMeters = distance,
        durationSeconds = durationSec,
        displayDistance = displayDistance,
        unitLabel = unitLabel,
        ink = ink,
    )
}
