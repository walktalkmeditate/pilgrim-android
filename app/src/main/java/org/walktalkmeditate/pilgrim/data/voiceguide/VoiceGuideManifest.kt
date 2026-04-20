// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import kotlinx.serialization.Serializable

/**
 * Top-level voice-guide manifest document published at
 * [VoiceGuideConfig.MANIFEST_URL]. Ports iOS's
 * `VoiceGuideManifest` exactly — field names + order match the JSON
 * keys iOS produces. `kotlinx.serialization` uses the Kotlin property
 * name as the JSON key by default, which matches the camelCase keys
 * iOS produces — no `@SerialName` annotations needed.
 */
@Serializable
data class VoiceGuideManifest(
    val version: String,
    val packs: List<VoiceGuidePack>,
)

/**
 * One voice-guide pack (e.g., "Morning Walk"). Contains the pack's
 * metadata + pre-rendered prompts for walk narration and (optionally)
 * guided meditation.
 *
 * [totalSizeBytes] and [VoiceGuidePrompt.fileSizeBytes] are `Long`
 * (not `Int`): a pack with many prompts can plausibly exceed 2 GB at
 * edge cases, and `Long` costs nothing on the wire.
 */
@Serializable
data class VoiceGuidePack(
    val id: String,
    val version: String,
    val name: String,
    val tagline: String,
    val description: String,
    val theme: String,
    val iconName: String,
    val type: String,
    val walkTypes: List<String>,
    val scheduling: PromptDensity,
    val totalDurationSec: Double,
    val totalSizeBytes: Long,
    val prompts: List<VoiceGuidePrompt>,
    val meditationScheduling: PromptDensity? = null,
    val meditationPrompts: List<VoiceGuidePrompt>? = null,
) {
    /** Mirrors iOS's `hasMeditationGuide` computed property. */
    val hasMeditationGuide: Boolean
        get() = !meditationPrompts.isNullOrEmpty()
}

/**
 * Per-pack prompt-scheduling parameters. Consumers of this data
 * (Stage 5-D scheduler) use it to pick how often and how densely
 * to surface prompts during a walk or meditation session.
 */
@Serializable
data class PromptDensity(
    val densityMinSec: Int,
    val densityMaxSec: Int,
    val minSpacingSec: Int,
    val initialDelaySec: Int,
    val walkEndBufferSec: Int,
)

/**
 * One audio prompt within a pack. [r2Key] is the CDN object key
 * (Cloudflare R2) — consumers resolve it to a download URL at fetch
 * time in Stage 5-D.
 */
@Serializable
data class VoiceGuidePrompt(
    val id: String,
    val seq: Int,
    val durationSec: Double,
    val fileSizeBytes: Long,
    val r2Key: String,
    val phase: String? = null,
)
