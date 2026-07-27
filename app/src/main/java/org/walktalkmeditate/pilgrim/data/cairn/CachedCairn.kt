// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.cairn

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * iOS parity `CachedCairn.swift@db4196e` — one cairn in the geo
 * cache (fetched from `GET /api/cairns?lat=&lon=&radius=`). Snake-case
 * JSON keys mapped via `@SerialName`. `createdAt` is ISO-8601 with a
 * fallback to `lastPlacedAt` on the iOS side (handled at use-site,
 * not deserialization).
 *
 * The `tier` derived value is computed once at decode time via
 * `CairnTier.forStoneCount(stoneCount)`. Cheap (linear scan of 7
 * entries) so we don't memoize.
 */
@Serializable
data class CachedCairn(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("stone_count") val stoneCount: Int,
    @SerialName("last_placed_at") val lastPlacedAt: String,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val tier: CairnTier get() = CairnTier.forStoneCount(stoneCount)

    /**
     * iOS parity `CachedCairn.becomingTier@9a418e4` — the tier this
     * cairn becomes when a walker adds one stone. The add-a-stone
     * sheet previews this, not the current tier (AE1-AE3).
     */
    val becomingTier: CairnTier get() = CairnTier.forStoneCount(stoneCount + 1)
}
