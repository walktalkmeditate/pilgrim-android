// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import java.time.Instant
import java.time.ZoneId

/**
 * Distills what made this walk distinct — length, hour, moon, stillness —
 * into one preamble sentence, so two different walks never open with
 * identical prose. Ordinary walks yield null; absence of remark is part
 * of the voice. Verbatim port of iOS `WalkCharacter@9a418e4`; [zone]
 * replaces iOS's implicit `Calendar.current` (spec D1).
 */
object WalkCharacter {

    fun note(context: ActivityContext, zone: ZoneId): String? {
        var noun = "a walk"
        var elaboration: String? = null
        if (context.durationSeconds >= 3600L) {
            noun = "a long walk"
            elaboration = " — the kind where thought thins out and something quieter takes over"
        } else if (context.durationSeconds < 900L) {
            noun = "a brief walk"
            elaboration = ", taken anyway — brevity is not smallness"
        }

        var timePhrase: String? = null
        val hour = Instant.ofEpochMilli(context.startTimestamp).atZone(zone).hour
        if (hour >= 20 || hour < 5) {
            timePhrase = "into the night"
        } else if (hour < 9) {
            timePhrase = "begun before the day claimed its shape"
        }

        val tail = mutableListOf<String>()
        context.lunarPhase?.illumination?.let { illumination ->
            if (illumination >= 0.97) {
                tail.add("under a full moon")
            } else if (illumination <= 0.03) {
                tail.add("under a new moon")
            }
        }
        if (context.meditations.isNotEmpty()) {
            tail.add("with stillness folded into it")
        }

        if (elaboration == null && timePhrase == null && tail.isEmpty()) return null

        // The time phrase attaches to the walk noun before any em-dash
        // elaboration — the reverse order reads as garbled prose ("something
        // quieter takes over into the night").
        val sentence = StringBuilder("This was ").append(noun)
        timePhrase?.let { sentence.append(' ').append(it) }
        elaboration?.let { sentence.append(it) }
        if (tail.isNotEmpty()) {
            sentence.append(", ").append(tail.joinToString(separator = ", "))
        }
        return sentence.append('.').toString()
    }
}

/**
 * The one shared preamble custom styles build on. Living here — not
 * hardcoded inside [CustomPromptStyle] — means preamble improvements
 * reach user-authored styles automatically (iOS `StandardPreamble@9a418e4`).
 */
object StandardPreamble {

    fun text(hasSpeech: Boolean): String =
        if (hasSpeech) {
            "These are voice recordings captured during a walk, transcribed as spoken. They represent unfiltered thoughts, observations, and feelings that surfaced while moving."
        } else {
            "This walk was taken in silence — no words were spoken, only movement. The walker chose presence over expression, letting the body speak through pace, pauses, and the places it was drawn to."
        }
}
