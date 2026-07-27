// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt.voices

import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyle
import org.walktalkmeditate.pilgrim.core.prompt.StandardPreamble
import org.walktalkmeditate.pilgrim.core.prompt.WalkPromptVoice

/**
 * Wraps a user-defined [CustomPromptStyle] as a [WalkPromptVoice].
 *
 * Custom styles share [StandardPreamble] (iOS
 * `CustomPromptStyle: PromptVoice@9a418e4`) so preamble improvements
 * reach user-authored styles automatically, and inherit the interface's
 * empty [responseConstraints] — a custom prompt's closing contract is
 * exactly the shared lines every style carries.
 *
 * `instruction(hasSpeech)` ignores its parameter and returns the
 * user-typed body unchanged — matching iOS's `func instruction(hasSpeech:) -> String { instruction }`.
 */
class CustomPromptStyleVoice(private val style: CustomPromptStyle) : WalkPromptVoice {
    override fun preamble(hasSpeech: Boolean): String = StandardPreamble.text(hasSpeech)

    override fun instruction(hasSpeech: Boolean): String = style.instruction
}
