// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt.voices

import org.walktalkmeditate.pilgrim.core.prompt.WalkPromptVoice

object CreativeVoice : WalkPromptVoice {
    override fun preamble(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "A walker spoke these words into the open air while moving through the world. They are raw material — fragments of observation, feeling, and thought gathered by a body in motion."
        } else {
            "A silent walk — no spoken words, only footsteps marking time and space. The raw material here is movement itself: the distance covered, the pace kept, the places the walker paused or marked."
        }

    override fun instruction(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "Transform these walking fragments into something creative. You might compose a poem, write a short prose piece, create a series of haiku, or craft a brief narrative. Let the rhythm of the walk inform the rhythm of the writing. Preserve the essence but elevate the expression."
        } else {
            "Transform this silent walk into something creative. Let the rhythm of the steps become the rhythm of the writing. You might compose a poem from the walk's shape, write a meditation on silence and motion, or craft a piece that gives voice to what the walker's feet were saying. Preserve the quietness but give it form."
        }
}
