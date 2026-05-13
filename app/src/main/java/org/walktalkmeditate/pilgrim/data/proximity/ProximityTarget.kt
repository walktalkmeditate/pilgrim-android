// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.proximity

/**
 * iOS parity `ProximityTarget.swift@db4196e` — one location-based
 * target watched by [ProximityDetectionService]. Hashable + equality
 * keyed on [id] ONLY (matches iOS exactly) so set operations dedupe
 * by id; coordinate / radius changes on the same id are invisible.
 *
 * ID format must match iOS: `whisper-<cacheId>` / `cairn-<cacheId>`.
 * This is critical for `suppressTarget()` post-placement — the
 * server-assigned id is wrapped with the matching prefix so the
 * just-placed item is dedup-blocked from immediate re-encounter.
 */
data class ProximityTarget(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,
    val type: Type,
) {
    enum class Type { Whisper, Cairn }

    override fun equals(other: Any?): Boolean = other is ProximityTarget && other.id == id
    override fun hashCode(): Int = id.hashCode()

    companion object {
        fun whisperId(cacheId: String): String = "whisper-$cacheId"
        fun cairnId(cacheId: String): String = "cairn-$cacheId"
    }
}
