// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * User-defined prompt style. Mirrors iOS `CustomPromptStyle` (Codable
 * struct in `Pilgrim/Models/CustomPromptStyleStore.swift`):
 *  - `id` is a stable UUID (Kotlin `String`); persisted across renames.
 *  - `icon` is a string key — on iOS it's an SF Symbol name; on Android
 *    it maps to a Material icon via a lookup table introduced in the
 *    custom-prompt-editor task.
 *  - `instruction` is the user-typed body sent verbatim as the LLM
 *    instruction (see [voices.CustomPromptStyleVoice]).
 */
@Immutable
@Serializable
data class CustomPromptStyle(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val icon: String,
    val instruction: String,
)
