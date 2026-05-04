// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

interface WalkPromptVoice {
    fun preamble(hasSpeech: Boolean): String
    fun instruction(hasSpeech: Boolean): String
}
