// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.core.prompt.ActivityContext
import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyle
import org.walktalkmeditate.pilgrim.core.prompt.GeneratedPrompt

/**
 * Stage 13-XZ: state machine for the AI Prompts sheet on Walk Summary.
 *
 * Transitions:
 *  - [Closed] → [Loading] (on first `openPromptsSheet`; one-shot
 *    [PromptsCoordinator.buildContext] + `generateAll` in flight).
 *  - [Loading] → [Listing] (build succeeded; cache populated).
 *  - [Loading] → [Closed] (build returned null; rare — walk row missing).
 *  - [Listing] → [Detail] / [Editor] (user tap).
 *  - [Detail] / [Editor] → [Listing] (dismiss).
 *  - any → [Closed] (sheet dismiss).
 *
 * Subsequent `openPromptsSheet` calls hit the cache and go straight to
 * [Listing] unless the cache invalidator has nulled it (pinned-photo
 * count or transcribed-recording count changed since last build).
 */
sealed interface PromptsSheetState {
    /** Sheet is closed; no work in flight. */
    data object Closed : PromptsSheetState

    /** Sheet open; ActivityContext is being built. UI shows loading state. */
    data object Loading : PromptsSheetState

    /** Listing the available prompts (6 built-in + N custom). */
    @Immutable
    data class Listing(
        val context: ActivityContext,
        val prompts: List<GeneratedPrompt>,
    ) : PromptsSheetState

    /** Detail dialog over the listing. */
    @Immutable
    data class Detail(
        val listing: Listing,
        val prompt: GeneratedPrompt,
    ) : PromptsSheetState

    /** Custom-prompt editor dialog over the listing. `editing == null` means create new. */
    @Immutable
    data class Editor(
        val listing: Listing,
        val editing: CustomPromptStyle?,
    ) : PromptsSheetState
}
