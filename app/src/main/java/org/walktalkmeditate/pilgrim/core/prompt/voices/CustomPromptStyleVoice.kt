// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt.voices

import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyle
import org.walktalkmeditate.pilgrim.core.prompt.WalkPromptVoice

/**
 * Wraps a user-defined [CustomPromptStyle] as a [WalkPromptVoice].
 *
 * iOS uses a fixed preamble pair regardless of which custom style is
 * selected — `hasSpeech == true` reuses the canonical Reflective
 * (hasSpeech) preamble, `hasSpeech == false` reuses the canonical
 * Contemplative (silent) preamble. We delegate to those objects rather
 * than duplicating the literals so the strings stay in lockstep.
 *
 * `instruction(hasSpeech)` ignores its parameter and returns the
 * user-typed body unchanged — matching iOS's `func instruction(hasSpeech:) -> String { instruction }`.
 */
class CustomPromptStyleVoice(private val style: CustomPromptStyle) : WalkPromptVoice {
    override fun preamble(hasSpeech: Boolean): String =
        if (hasSpeech) ReflectiveVoice.preamble(hasSpeech = true)
        else ContemplativeVoice.preamble(hasSpeech = false)

    override fun instruction(hasSpeech: Boolean): String = style.instruction
}
