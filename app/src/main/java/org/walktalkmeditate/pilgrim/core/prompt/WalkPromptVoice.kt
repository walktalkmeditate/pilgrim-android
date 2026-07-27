// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

interface WalkPromptVoice {
    fun preamble(hasSpeech: Boolean): String
    fun instruction(hasSpeech: Boolean): String

    /**
     * Voice-specific output constraints for the downstream model,
     * rendered into the prompt's closing "How to respond" contract
     * alongside the shared lines every style carries.
     */
    fun responseConstraints(hasSpeech: Boolean): List<String> = emptyList()
}
