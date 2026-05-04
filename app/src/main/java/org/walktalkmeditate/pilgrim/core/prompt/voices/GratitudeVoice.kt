// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt.voices

import org.walktalkmeditate.pilgrim.core.prompt.WalkPromptVoice

object GratitudeVoice : WalkPromptVoice {
    override fun preamble(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "These words were spoken during a walk — a time of moving through the world with awareness. Somewhere in these observations and thoughts are seeds of gratitude, even if not explicitly stated."
        } else {
            "This walk was taken in silence — a choice to simply be present with the world rather than narrate it."
        }

    override fun instruction(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "Help me find the gratitude woven through these walking thoughts. What am I thankful for, even if I didn't say it directly? What blessings are hiding in my observations? What can I appreciate about this moment in my life, this body that walks, this world I moved through? Frame your response as a practice of thanksgiving."
        } else {
            "Find the gratitude hidden in this silent walk. What is the walker thankful for, even without saying it? The body that carried them, the ground beneath their feet, the places they marked as meaningful, the time they gave themselves. Frame your response as a practice of thanksgiving for the walk itself."
        }
}
