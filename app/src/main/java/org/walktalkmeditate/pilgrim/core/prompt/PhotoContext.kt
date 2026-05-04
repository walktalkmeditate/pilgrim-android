// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Per-photo analysis result produced by [PhotoContextAnalyzer]. Cached
 * in DataStore by URI; consumed by the prompt assembler via
 * [PhotoContextEntry] to render `Scene:` / `Text found:` / `People:` /
 * `Outdoor:` / dominant-color lines into the LLM context.
 *
 * Android divergences from the iOS spec (documented in
 * `docs/superpowers/specs/2026-05-04-stage-13-xz-design.md`):
 *  - **No `animals` field.** ML Kit has no equivalent of
 *    `VNRecognizeAnimalsRequest`. The `Animals:` line is dropped from
 *    the prompt template entirely.
 *  - **No `salientRegion` field.** ML Kit has no saliency API; a
 *    constant-center placeholder would feed misleading attention-arc
 *    information to the LLM via `PhotoNarrativeArcBuilder` (always
 *    "consistently_close"). The `Focal area:` line + cross-photo
 *    "Visual narrative" / "Color progression" arc block are dropped.
 *  - **`outdoor` derived from image-labeling tag intersection** (proxy
 *    for iOS `VNDetectHorizonRequest`). See the OUTDOOR_TAG_SET inside
 *    [PhotoContextAnalyzer].
 *
 * Pure data with primitives + `List<String>`; `@Immutable` is safe and
 * lets Compose lift recompositions across consumers.
 */
@Serializable
@Immutable
data class PhotoContext(
    val tags: List<String>,
    val detectedText: List<String>,
    val people: Int,
    val outdoor: Boolean,
    val dominantColor: String,
)
