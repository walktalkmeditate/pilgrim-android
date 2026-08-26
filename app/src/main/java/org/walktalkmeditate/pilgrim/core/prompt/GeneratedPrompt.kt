// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.UUID

/**
 * A generated AI prompt — built-in voice OR custom — with display fields
 * pre-resolved at construction time. Mirrors iOS `GeneratedPrompt`'s
 * computed-property contract:
 *  - `title`  = customStyle?.title ?? style?.titleRes (resolved)
 *  - `subtitle` = customStyle?.instruction ?? style?.descRes (resolved)
 *  - `icon`   = customStyle?.icon (Material lookup) ?? style?.icon
 *
 * Resolution happens in [PromptGenerator] (Task 9) which has Context for
 * `getString` + the icon-key lookup map for custom styles.
 */
@Immutable
data class GeneratedPrompt(
    val id: String = UUID.randomUUID().toString(),
    val style: PromptStyle?,
    val customStyle: CustomPromptStyle?,
    val title: String,
    val subtitle: String,
    val text: String,
    val icon: ImageVector,
    /**
     * U10 clipboard hardening: whether [text] was assembled with a
     * Thought Threads dossier folded in (`ActivityContext.threadsDossier
     * != null` at generation time — [PromptGenerator] is the single
     * resolution point). [PromptDetailDialog] marks the clip
     * `EXTRA_IS_SENSITIVE` on API 33+ when this is `true`. Defaults
     * `false` so pre-existing construction sites (tests, other callers)
     * keep compiling unchanged.
     */
    val hasThreadsDossier: Boolean = false,
)
