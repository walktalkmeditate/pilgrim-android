// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt.voices

import org.walktalkmeditate.pilgrim.core.prompt.WalkPromptVoice

object ReflectiveVoice : WalkPromptVoice {
    override fun preamble(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "These are voice recordings captured during a walk, transcribed as spoken. They represent unfiltered thoughts, observations, and feelings that surfaced while moving."
        } else {
            "A walk taken without words. The walker moved through the world in observation, letting thoughts form and dissolve without voicing them."
        }

    override fun instruction(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "Please analyze these walking reflections for patterns, recurring themes, and emotional undercurrents. What connections do you see between the different moments? What might I be processing or working through? What contradictions or tensions are present? Offer observations that help me understand myself better."
        } else {
            "Read the shape of this walk — its pace, its pauses, its waypoints — as you would read a text. What patterns do you see? What might the walker have been processing? What does the choice of silence itself suggest? Offer observations that help them understand themselves."
        }
}
