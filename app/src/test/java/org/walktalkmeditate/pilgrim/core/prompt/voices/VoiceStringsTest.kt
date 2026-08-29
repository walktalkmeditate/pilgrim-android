// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt.voices

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyle
import org.walktalkmeditate.pilgrim.core.prompt.StandardPreamble

class VoiceStringsTest {

    @Test
    fun standardPreamble_hasSpeech_true() {
        assertEquals(
            "These are voice recordings captured during a walk, transcribed as spoken. They represent unfiltered thoughts, observations, and feelings that surfaced while moving.",
            StandardPreamble.text(hasSpeech = true),
        )
    }

    @Test
    fun standardPreamble_hasSpeech_false() {
        assertEquals(
            "This walk was taken in silence — no words were spoken, only movement. The walker chose presence over expression, letting the body speak through pace, pauses, and the places it was drawn to.",
            StandardPreamble.text(hasSpeech = false),
        )
    }

    @Test
    fun contemplative_silentPreamble_isStandardPreamble() {
        assertEquals(
            StandardPreamble.text(hasSpeech = false),
            ContemplativeVoice.preamble(hasSpeech = false),
        )
    }

    @Test
    fun customStyle_preamble_isStandardPreamble_bothBranches() {
        val voice = CustomPromptStyleVoice(
            CustomPromptStyle(title = "Letters", icon = "envelope", instruction = "Write a letter."),
        )
        assertEquals(StandardPreamble.text(hasSpeech = true), voice.preamble(hasSpeech = true))
        assertEquals(StandardPreamble.text(hasSpeech = false), voice.preamble(hasSpeech = false))
    }

    @Test
    fun customStyle_responseConstraints_empty() {
        val voice = CustomPromptStyleVoice(
            CustomPromptStyle(title = "Letters", icon = "envelope", instruction = "Write a letter."),
        )
        assertEquals(emptyList<String>(), voice.responseConstraints(hasSpeech = true))
        assertEquals(emptyList<String>(), voice.responseConstraints(hasSpeech = false))
    }

    @Test
    fun contemplative_responseConstraints() {
        assertEquals(
            listOf(
                "Write in unhurried prose — no bullet points, no headings.",
                "Ask at most one question, and let it be one worth carrying.",
                "Do not summarize the walk back to the walker; they were there.",
            ),
            ContemplativeVoice.responseConstraints(hasSpeech = true),
        )
    }

    @Test
    fun reflective_responseConstraints() {
        assertEquals(
            listOf(
                "Offer observations, not advice; name patterns tentatively rather than diagnosing.",
                "Avoid therapy clichés — write in connected prose, not lists.",
            ),
            ReflectiveVoice.responseConstraints(hasSpeech = false),
        )
    }

    @Test
    fun creative_responseConstraints() {
        assertEquals(
            listOf(
                "Reply with the piece itself — no introduction, no explanation of your choices.",
                "Let the walk's rhythm shape the form; brevity is welcome.",
            ),
            CreativeVoice.responseConstraints(hasSpeech = true),
        )
    }

    @Test
    fun gratitude_responseConstraints() {
        assertEquals(
            listOf(
                "Root every thanksgiving in something specific from this walk — no generic blessings.",
                "Warm but plain language, in prose; never saccharine.",
            ),
            GratitudeVoice.responseConstraints(hasSpeech = true),
        )
    }

    @Test
    fun philosophical_responseConstraints() {
        assertEquals(
            listOf(
                "Invoke thinkers or traditions only when they genuinely illuminate — never name-drop.",
                "Write as a letter from a thoughtful friend, not a lecture, and end with one question that opens rather than closes.",
            ),
            PhilosophicalVoice.responseConstraints(hasSpeech = false),
        )
    }

    @Test
    fun journaling_responseConstraints_hasSpeech_true() {
        assertEquals(
            listOf(
                "Write the entry in the walker's own first-person voice, keeping their phrasing where it lives.",
                "No meta-commentary about what you are doing — only the entry itself, ready to be reread years from now.",
            ),
            JournalingVoice.responseConstraints(hasSpeech = true),
        )
    }

    @Test
    fun journaling_responseConstraints_hasSpeech_false() {
        assertEquals(
            listOf(
                "Keep the entry in second person, as a witness would write it.",
                "No meta-commentary about what you are doing — only the entry itself, ready to be reread years from now.",
            ),
            JournalingVoice.responseConstraints(hasSpeech = false),
        )
    }

    @Test
    fun contemplative_preamble_hasSpeech_true() {
        assertEquals(
            "During a walking meditation, these words arose naturally from the rhythm of movement and breath. They were not planned or curated — they emerged as the body moved through space.",
            ContemplativeVoice.preamble(hasSpeech = true),
        )
    }

    @Test
    fun contemplative_preamble_hasSpeech_false() {
        assertEquals(
            "This walk was taken in silence — no words were spoken, only movement. The walker chose presence over expression, letting the body speak through pace, pauses, and the places it was drawn to.",
            ContemplativeVoice.preamble(hasSpeech = false),
        )
    }

    @Test
    fun contemplative_instruction_hasSpeech_true() {
        assertEquals(
            "Please receive these walking thoughts with gentleness. Help me sit with what emerged, without rushing to analyze or fix. What was my body and spirit trying to tell me through these words? What wants to be noticed, held, or simply acknowledged? Respond in a contemplative, unhurried tone.",
            ContemplativeVoice.instruction(hasSpeech = true),
        )
    }

    @Test
    fun contemplative_instruction_hasSpeech_false() {
        assertEquals(
            "Reflect on what this silent walk might reveal. What does its rhythm suggest? Its pauses, its waypoints, its duration? Help the walker see what their body and feet were saying when their voice was still. Respond in a contemplative, unhurried tone.",
            ContemplativeVoice.instruction(hasSpeech = false),
        )
    }

    @Test
    fun reflective_preamble_hasSpeech_true() {
        assertEquals(
            "These are voice recordings captured during a walk, transcribed as spoken. They represent unfiltered thoughts, observations, and feelings that surfaced while moving.",
            ReflectiveVoice.preamble(hasSpeech = true),
        )
    }

    @Test
    fun reflective_preamble_hasSpeech_false() {
        assertEquals(
            "A walk taken without words. The walker moved through the world in observation, letting thoughts form and dissolve without voicing them.",
            ReflectiveVoice.preamble(hasSpeech = false),
        )
    }

    @Test
    fun reflective_instruction_hasSpeech_true() {
        assertEquals(
            "Please analyze these walking reflections for patterns, recurring themes, and emotional undercurrents. Where the walk's own record supports it — the stated intention, a word that recurs, a shift in pace — name what connects the moments; where it does not, say less rather than reaching. Note any genuine tension the record shows, and do not manufacture one. Offer observations that help me understand myself better.",
            ReflectiveVoice.instruction(hasSpeech = true),
        )
    }

    @Test
    fun reflective_instruction_hasSpeech_false() {
        assertEquals(
            "Read the shape of this walk — its pace, its pauses, its waypoints — as you would read a text. Where the walk's own record supports it, name the patterns you find; where it does not, say less rather than reaching. What might the walker have been processing? What does the choice of silence itself suggest? Offer observations that help them understand themselves.",
            ReflectiveVoice.instruction(hasSpeech = false),
        )
    }

    @Test
    fun creative_preamble_hasSpeech_true() {
        assertEquals(
            "A walker spoke these words into the open air while moving through the world. They are raw material — fragments of observation, feeling, and thought gathered by a body in motion.",
            CreativeVoice.preamble(hasSpeech = true),
        )
    }

    @Test
    fun creative_preamble_hasSpeech_false() {
        assertEquals(
            "A silent walk — no spoken words, only footsteps marking time and space. The raw material here is movement itself: the distance covered, the pace kept, the places the walker paused or marked.",
            CreativeVoice.preamble(hasSpeech = false),
        )
    }

    @Test
    fun creative_instruction_hasSpeech_true() {
        assertEquals(
            "Transform these walking fragments into something creative. You might compose a poem, write a short prose piece, create a series of haiku, or craft a brief narrative. Let the rhythm of the walk inform the rhythm of the writing. Preserve the essence but elevate the expression.",
            CreativeVoice.instruction(hasSpeech = true),
        )
    }

    @Test
    fun creative_instruction_hasSpeech_false() {
        assertEquals(
            "Transform this silent walk into something creative. Let the rhythm of the steps become the rhythm of the writing. You might compose a poem from the walk's shape, write a meditation on silence and motion, or craft a piece that gives voice to what the walker's feet were saying. Preserve the quietness but give it form.",
            CreativeVoice.instruction(hasSpeech = false),
        )
    }

    @Test
    fun gratitude_preamble_hasSpeech_true() {
        assertEquals(
            "These words were spoken during a walk — a time of moving through the world with awareness. Somewhere in these observations and thoughts are seeds of gratitude, even if not explicitly stated.",
            GratitudeVoice.preamble(hasSpeech = true),
        )
    }

    @Test
    fun gratitude_preamble_hasSpeech_false() {
        assertEquals(
            "This walk was taken in silence — a choice to simply be present with the world rather than narrate it.",
            GratitudeVoice.preamble(hasSpeech = false),
        )
    }

    @Test
    fun gratitude_instruction_hasSpeech_true() {
        assertEquals(
            "Help me find the gratitude woven through these walking thoughts. What am I thankful for, even if I didn't say it directly? What blessings are hiding in my observations? What can I appreciate about this moment in my life, this body that walks, this world I moved through? Frame your response as a practice of thanksgiving.",
            GratitudeVoice.instruction(hasSpeech = true),
        )
    }

    @Test
    fun gratitude_instruction_hasSpeech_false() {
        assertEquals(
            "Find the gratitude hidden in this silent walk. What is the walker thankful for, even without saying it? The body that carried them, the ground beneath their feet, the places they marked as meaningful, the time they gave themselves. Frame your response as a practice of thanksgiving for the walk itself.",
            GratitudeVoice.instruction(hasSpeech = false),
        )
    }

    @Test
    fun philosophical_preamble_hasSpeech_true() {
        assertEquals(
            "Walking has long been a companion to philosophical thought — from Aristotle's peripatetic school to Kierkegaard's daily constitutionals. These words emerged during such a walk, where movement and thought intertwined.",
            PhilosophicalVoice.preamble(hasSpeech = true),
        )
    }

    @Test
    fun philosophical_preamble_hasSpeech_false() {
        assertEquals(
            "Walking in silence has a long philosophical tradition — from Zen walking meditation to Kierkegaard's solitary constitutionals. This walk carries that lineage, choosing wordless presence over verbal reflection.",
            PhilosophicalVoice.preamble(hasSpeech = false),
        )
    }

    @Test
    fun philosophical_instruction_hasSpeech_true() {
        assertEquals(
            "Engage with these walking thoughts philosophically. What deeper questions are being asked? What assumptions about life, meaning, or existence are being explored? Connect my observations to broader wisdom traditions, philosophical concepts, or universal human experiences. Help me think more deeply about what I was already beginning to think.",
            PhilosophicalVoice.instruction(hasSpeech = true),
        )
    }

    @Test
    fun philosophical_instruction_hasSpeech_false() {
        assertEquals(
            "Engage with this silent walk philosophically. What does the act of walking without speaking suggest about the walker's relationship to thought, language, and presence? Connect the walk's physical details — its duration, pace, waypoints — to broader questions about consciousness, embodiment, and meaning.",
            PhilosophicalVoice.instruction(hasSpeech = false),
        )
    }

    @Test
    fun journaling_preamble_hasSpeech_true() {
        assertEquals(
            "The following are raw, unedited voice recordings from a walk. They capture thoughts as they occurred — scattered, honest, and in the moment.",
            JournalingVoice.preamble(hasSpeech = true),
        )
    }

    @Test
    fun journaling_preamble_hasSpeech_false() {
        assertEquals(
            "The following is a walk taken without voice recordings. No words were spoken — only footsteps, pauses, and marked waypoints tell the story.",
            JournalingVoice.preamble(hasSpeech = false),
        )
    }

    @Test
    fun journaling_instruction_hasSpeech_true() {
        assertEquals(
            "Help me turn these scattered walking thoughts into a coherent journal entry. Organize the themes, add transitions between ideas, and create a narrative flow while preserving my authentic voice. The result should read as a thoughtful, personal journal entry that I could return to and understand. Include a brief summary of the walk's key themes at the end.",
            JournalingVoice.instruction(hasSpeech = true),
        )
    }

    @Test
    fun journaling_instruction_hasSpeech_false() {
        assertEquals(
            "Help the walker create a journal entry from this silent walk. Use the walk's metadata — its timing, distance, pace, waypoints, and any meditation sessions — to reconstruct a narrative. What was the walk like? What might the walker have been thinking? Create a reflective entry they could return to, written in second person ('You walked...').",
            JournalingVoice.instruction(hasSpeech = false),
        )
    }
}
