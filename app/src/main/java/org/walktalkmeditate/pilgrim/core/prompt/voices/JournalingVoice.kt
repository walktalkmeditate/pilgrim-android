// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt.voices

import org.walktalkmeditate.pilgrim.core.prompt.WalkPromptVoice

object JournalingVoice : WalkPromptVoice {
    override fun preamble(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "The following are raw, unedited voice recordings from a walk. They capture thoughts as they occurred — scattered, honest, and in the moment."
        } else {
            "The following is a walk taken without voice recordings. No words were spoken — only footsteps, pauses, and marked waypoints tell the story."
        }

    override fun instruction(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "Help me turn these scattered walking thoughts into a coherent journal entry. Organize the themes, add transitions between ideas, and create a narrative flow while preserving my authentic voice. The result should read as a thoughtful, personal journal entry that I could return to and understand. Include a brief summary of the walk's key themes at the end."
        } else {
            "Help the walker create a journal entry from this silent walk. Use the walk's metadata — its timing, distance, pace, waypoints, and any meditation sessions — to reconstruct a narrative. What was the walk like? What might the walker have been thinking? Create a reflective entry they could return to, written in second person ('You walked...')."
        }
}
