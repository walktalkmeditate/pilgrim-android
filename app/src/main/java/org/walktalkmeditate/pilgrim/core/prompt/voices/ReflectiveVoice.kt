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
            // The marker profile is deliberately not named here. It reaches
            // the prompt only when the ThreadsPreferences threadsAfterWalks
            // toggle is on, and this instruction — unlike
            // PromptAssembler.responseContract — has no way to know whether
            // it did. Licensing evidence that may be switched off invites
            // the model to reach for it anyway.
            "Please analyze these walking reflections for patterns, recurring themes, and emotional undercurrents. Where the walk's own record supports it — the stated intention, a word that recurs, a shift in pace — name what connects the moments; where it does not, say less rather than reaching. Note any genuine tension the record shows, and do not manufacture one. Offer observations that help me understand myself better."
        } else {
            "Read the shape of this walk — its pace, its pauses, its waypoints — as you would read a text. Where the walk's own record supports it, name the patterns you find; where it does not, say less rather than reaching. What might the walker have been processing? What does the choice of silence itself suggest? Offer observations that help them understand themselves."
        }

    override fun responseConstraints(hasSpeech: Boolean): List<String> = listOf(
        "Offer observations, not advice; name patterns tentatively rather than diagnosing.",
        "Avoid therapy clichés — write in connected prose, not lists.",
    )
}
