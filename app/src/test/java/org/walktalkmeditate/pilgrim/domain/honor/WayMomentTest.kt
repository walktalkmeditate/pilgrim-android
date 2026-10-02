// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `Way.swift@7c200bf` helpers: the transcript trim and cap
 * (`:68-74`), the card's one line (`:66`, `:76-88`), and the kind
 * accessors (`:90-102`, `:210-217`). The first test ports iOS
 * `HonorWayRenderingTests.testTranscriptLineIsTheFirstSentenceCutAtAWordWhenLong`.
 */
class WayMomentTest {

    @Test
    fun `transcript line is the first sentence cut at a word when long`() {
        assertNull(WayMoment.firstSentence(null, maxCharacters = 120))
        assertNull(WayMoment.firstSentence("  ", maxCharacters = 120))
        assertEquals("Quiet here.", WayMoment.firstSentence("Quiet here. Birds later.", maxCharacters = 120))
        assertEquals("no punctuation at all", WayMoment.firstSentence("no punctuation at all", maxCharacters = 120))
        val long = "one two three four five six seven eight nine ten eleven twelve"
        assertEquals("one two three four…", WayMoment.firstSentence(long, maxCharacters = 20))
    }

    @Test
    fun `the card line allows 120 characters`() {
        val words = List(30) { "word" }.joinToString(" ")
        val line = moment(transcript = words).transcriptLine
        assertEquals(words.take(119) + "…", line)
    }

    @Test
    fun `a cut with no space is hard, and a decimal point ends the sentence`() {
        assertEquals("x".repeat(120) + "…", WayMoment.firstSentence("x".repeat(200), maxCharacters = 120))
        assertEquals("It was 3.", WayMoment.firstSentence("It was 3.5 km", maxCharacters = 120))
        assertEquals("Look!", WayMoment.firstSentence("Look! A heron?", maxCharacters = 120))
    }

    @Test
    fun `trimmed transcript trims, drops the empty, and caps at 600`() {
        assertNull(WayMoment.trimmedTranscript(null))
        assertNull(WayMoment.trimmedTranscript(" \n\t "))
        assertEquals("hello", WayMoment.trimmedTranscript("\n  hello \t"))
        val capped = WayMoment.trimmedTranscript("y".repeat(WayMoment.MAX_TRANSCRIPT_CHARACTERS + 40))
        assertEquals(WayMoment.MAX_TRANSCRIPT_CHARACTERS, capped?.length)
    }

    @Test
    fun `the cap counts characters as Swift does, not UTF-16 units`() {
        // "e" plus a combining acute is one Swift Character in two UTF-16 units.
        val accented = "e\u0301"
        val capped = WayMoment.trimmedTranscript(accented.repeat(WayMoment.MAX_TRANSCRIPT_CHARACTERS + 1))
        assertEquals(accented.repeat(WayMoment.MAX_TRANSCRIPT_CHARACTERS), capped)
        val emoji = "\uD83C\uDF3F" // one herb (U+1F33F), a surrogate pair
        assertEquals(emoji.repeat(3) + "…", WayMoment.firstSentence(emoji.repeat(5), maxCharacters = 3))
    }

    @Test
    fun `kind accessors`() {
        val recording = WayMedia.Recording(relativePath = "Recordings/a/b.m4a")
        val photo = WayMedia.PhotoAsset(localIdentifier = "content://media/1")
        val voice = moment(kind = WayMomentKind.Voice(0.2, 5.0, VoiceKind.SPOKEN, recording))
        val picture = moment(kind = WayMomentKind.Photo(media = photo))
        val rest = moment(kind = WayMomentKind.Rest(minutes = 4))
        assertTrue(voice.isVoice)
        assertFalse(picture.isVoice)
        assertEquals(recording, voice.media)
        assertEquals(photo, picture.media)
        assertNull(rest.media)
        assertNull(moment(kind = WayMomentKind.Waypoint(label = "Oak", icon = "leaf")).media)
        assertNull(moment(kind = WayMomentKind.Meditation(minutes = 9, isEstimate = false)).media)

        val way = way(moments = listOf(voice, picture, rest, voice.copy(id = "voice-2")))
        assertEquals(2, way.voiceCount)
        assertEquals(1, way.photoCount)
        assertFalse(way.isPilgrimageStage)
        assertFalse(way.source.isPackageOwned)
        assertTrue(WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = 0).isPackageOwned)
    }

    private fun moment(
        kind: WayMomentKind = WayMomentKind.Rest(minutes = 1),
        transcript: String? = null,
    ): WayMoment = WayMoment(id = "voice-1", frac = 0.1, at = null, kind = kind, transcript = transcript)

    private fun way(moments: List<WayMoment>): Way = Way(
        id = "walk:t",
        source = WaySource.OwnWalk(uuid = "e621e1f8-c36c-495a-93fc-0c247a3e6e5f"),
        title = "t",
        departedAt = Instant.ofEpochSecond(1_700_000_000),
        tzIdentifier = null,
        expires = null,
        route = emptyList(),
        totalDistanceMeters = 0.0,
        theirActiveSeconds = 0.0,
        moments = moments,
        weather = null,
    )
}
