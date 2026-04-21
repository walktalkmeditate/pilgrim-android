// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.audio

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Top-level unified audio manifest published at
 * [AudioConfig.MANIFEST_URL]. Ports iOS's `AudioManifest` exactly —
 * same camelCase field names, same flat `assets` list tagged by
 * [AudioAsset.type]. Contains both bells AND soundscapes; Android
 * parses the whole manifest but only uses the soundscape entries
 * today — bells are bundled in-APK per Stage 5-B.
 *
 * If Android ever downloads bells (vs bundling), the infrastructure
 * already recognizes the `bell` type entries.
 */
@Serializable
data class AudioManifest(
    val version: String,
    val assets: List<AudioAsset>,
)

/**
 * One audio asset entry. Bells and soundscapes share this shape on
 * iOS; the `type` field distinguishes them. [r2Key] is the CDN
 * object key but on iOS the download URL is reconstructed from
 * `<baseUrl>/<type>/<id>.aac` — Stage 5-F Android follows that
 * convention for soundscapes.
 *
 * `@Immutable` is an honest contract (these are read-only after
 * deserialization) — same rationale as the voice-guide data models
 * in Stage 5-D. Without the annotation, Compose marks the whole
 * `SoundscapeState` cascade Unstable and every picker row
 * recomposes on every catalog emission.
 */
@Immutable
@Serializable
data class AudioAsset(
    val id: String,
    val type: String,              // "bell" | "soundscape" (forward-compat: unknown types ignored)
    val name: String,
    val displayName: String,
    val durationSec: Double,
    val r2Key: String,
    val fileSizeBytes: Long,
    val usageTags: List<String> = emptyList(),
)

/** Asset type constants so string literals don't scatter through callers. */
object AudioAssetType {
    const val BELL = "bell"
    const val SOUNDSCAPE = "soundscape"
}
