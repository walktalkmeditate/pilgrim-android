// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import java.time.Instant

/**
 * Stage 8-A: in-memory value type for a cached share record. Mirrors
 * iOS `ShareService.CachedShare` field-for-field; persisted as a
 * JSON blob by [CachedShareStore].
 *
 * [isExpiredAt] is a point-in-time wall-clock check — there is no
 * live ticker that transitions active → expired while the Walk
 * Summary screen stays open; the transition surfaces on the next
 * composition / flow emission (iOS parity).
 */
data class CachedShare(
    val url: String,
    val id: String,
    val expiryEpochMs: Long,
    val shareDateEpochMs: Long,
    val expiryOption: ExpiryOption?,
) {
    fun isExpiredAt(nowEpochMs: Long = Instant.now().toEpochMilli()): Boolean =
        expiryEpochMs <= nowEpochMs
}
