// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * iOS parity `CachedWhisper.swift@db4196e` — one whisper in the geo
 * cache (fetched from `GET /api/whispers?lat=&lon=&radius=`). Note
 * the wire `category` field is a raw lowercase string (not the enum)
 * — iOS keeps it as `String` so an unknown future category value
 * doesn't fail decoding. We mirror that: store as `String` and
 * resolve via [resolvedCategory] at use time.
 */
@Serializable
data class CachedWhisper(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("whisper_id") val whisperId: String,
    val category: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    val resolvedCategory: WhisperCategory? get() =
        WhisperCategory.fromApiValue(category)
}
