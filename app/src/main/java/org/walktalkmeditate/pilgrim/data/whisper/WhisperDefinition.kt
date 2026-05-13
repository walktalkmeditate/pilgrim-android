// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * iOS parity `WhisperDefinition.swift@db4196e` — one entry in the
 * remote whisper manifest. The audio file lives at
 * `<cdnBase>/whispers/<audioFileName>.m4a` (per iOS); audio playback
 * itself is deferred to a follow-up PR — for the placement MVP we
 * only need the `id`, `title`, `category` fields and the
 * `retiredAt`-based active filter.
 *
 * `retiredAt`: ISO-8601 string; non-null means the whisper is no
 * longer placeable. iOS uses [`Date?`]; Android decodes as nullable
 * String to avoid an unused date parser dependency for the MVP.
 */
@Serializable
data class WhisperDefinition(
    val id: String,
    val title: String,
    val category: WhisperCategory,
    @SerialName("audioFileName")
    val audioFileName: String,
    @SerialName("durationSec")
    val durationSec: Double,
    @SerialName("retiredAt")
    val retiredAt: String? = null,
) {
    val isActive: Boolean get() = retiredAt == null
}
