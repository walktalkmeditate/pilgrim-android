// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt.voices

import org.walktalkmeditate.pilgrim.core.prompt.WalkPromptVoice

object PhilosophicalVoice : WalkPromptVoice {
    override fun preamble(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "Walking has long been a companion to philosophical thought — from Aristotle's peripatetic school to Kierkegaard's daily constitutionals. These words emerged during such a walk, where movement and thought intertwined."
        } else {
            "Walking in silence has a long philosophical tradition — from Zen walking meditation to Kierkegaard's solitary constitutionals. This walk carries that lineage, choosing wordless presence over verbal reflection."
        }

    override fun instruction(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "Engage with these walking thoughts philosophically. What deeper questions are being asked? What assumptions about life, meaning, or existence are being explored? Connect my observations to broader wisdom traditions, philosophical concepts, or universal human experiences. Help me think more deeply about what I was already beginning to think."
        } else {
            "Engage with this silent walk philosophically. What does the act of walking without speaking suggest about the walker's relationship to thought, language, and presence? Connect the walk's physical details — its duration, pace, waypoints — to broader questions about consciousness, embodiment, and meaning."
        }
}
