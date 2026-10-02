// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

/**
 * Hears the guide's prompt level (plan U18, the prompt gate): raised before
 * the player is asked to sound a prompt, lowered when the prompt ends by any
 * path, and held across a prompt that replaces another. Called
 * synchronously, from the orchestrator's thread or the player's.
 */
fun interface VoiceGuidePromptGate {

    fun onPromptLevel(sounding: Boolean)
}
