// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt.voices

import org.walktalkmeditate.pilgrim.core.prompt.WalkPromptVoice

object ContemplativeVoice : WalkPromptVoice {
    override fun preamble(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "During a walking meditation, these words arose naturally from the rhythm of movement and breath. They were not planned or curated — they emerged as the body moved through space."
        } else {
            "This walk was taken in silence — no words were spoken, only movement. The walker chose presence over expression, letting the body speak through pace, pauses, and the places it was drawn to."
        }

    override fun instruction(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "Please receive these walking thoughts with gentleness. Help me sit with what emerged, without rushing to analyze or fix. What was my body and spirit trying to tell me through these words? What wants to be noticed, held, or simply acknowledged? Respond in a contemplative, unhurried tone."
        } else {
            "Reflect on what this silent walk might reveal. What does its rhythm suggest? Its pauses, its waypoints, its duration? Help the walker see what their body and feet were saying when their voice was still. Respond in a contemplative, unhurried tone."
        }
}
