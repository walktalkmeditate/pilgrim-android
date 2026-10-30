// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.net.URI
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.WayImporter
import org.walktalkmeditate.pilgrim.data.honor.WayStore

/**
 * Port of iOS `HonorLinkTests.swift@3b78728` (its stash test belongs to
 * the link routing), with the four additions shared-walk spec S2's
 * resolution 1 asks for and the derived S2 §11.1 rows where the platforms'
 * parsers differ.
 */
class HonorLinkTest {

    @Test
    fun `accepted forms`() {
        listOf(
            "https://honor.pilgrimapp.org/Qoi4YmPHLN",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN?utm=x#m3",
            "https://walk.pilgrimapp.org/Qoi4YmPHLN",
            "walk.pilgrimapp.org/Qoi4YmPHLN",
            "Qoi4YmPHLN",
            "  Qoi4YmPHLN\n",
        ).forEach { assertEquals(it, SHARE, HonorLink.parse(text = it)) }
        assertEquals(SHARE, HonorLink.parse(URI("https://honor.pilgrimapp.org/Qoi4YmPHLN")))
    }

    @Test
    fun rejections() {
        listOf(
            "https://example.com/Qoi4YmPHLN",
            "https://walk.pilgrimapp.org/",
            "https://walk.pilgrimapp.org/short",
            "https://walk.pilgrimapp.org/Qoi4YmPHLN/audio/1.m4a",
            "Qoi4YmPHL",
            "Qoi4YmPHLN1",
            "",
        ).forEach { assertNull(it, HonorLink.parse(text = it)) }
    }

    @Test
    fun `the host is case-insensitive`() {
        assertEquals(SHARE, HonorLink.parse(text = "https://HONOR.pilgrimapp.org/Qoi4YmPHLN"))
        assertEquals(SHARE, HonorLink.parse(URI("https://HONOR.pilgrimapp.org/Qoi4YmPHLN")))
    }

    /** `$` matches before a trailing newline, so an id pasted with one used to pass every shape check (`HonorLinkTests.swift:85-95@3b78728`). */
    @Test
    fun `a trailing newline is rejected`() {
        assertEquals(HonorLinkDestination.Unrecognised, HonorLink.parse(URI("https://honor.pilgrimapp.org/Qoi4YmPHLN%0A")))
        assertEquals(HonorLinkDestination.Unrecognised, HonorLink.parse(URI("https://honor.pilgrimapp.org/camino-frances/14%0A")))
        assertFalse(WayImporter.isShareId("Qoi4YmPHLN\n"))
        assertFalse(WayStore.isValidId("share:Qoi4YmPHLN\n"))
        assertFalse(WayStore.isValidId("walk:${UUID.randomUUID()}\n"))
    }

    // S2 resolution 1: the four additions.

    @Test
    fun `the scheme is never checked`() {
        listOf(
            "ftp://walk.pilgrimapp.org/Qoi4YmPHLN",
            "http://walk.pilgrimapp.org/Qoi4YmPHLN",
            "HTTPS://honor.pilgrimapp.org/Qoi4YmPHLN",
            "honor.pilgrimapp.org/Qoi4YmPHLN",
        ).forEach { assertEquals(it, SHARE, HonorLink.parse(text = it)) }
    }

    @Test
    fun `percent-escapes are decoded before the id check`() {
        assertEquals(SHARE, HonorLink.parse(text = "https://honor.pilgrimapp.org/%51oi4YmPHLN"))
        assertNull("an escaped slash decodes into a character the id refuses", HonorLink.parse(text = "https://honor.pilgrimapp.org/Qoi4%2FYmPHL"))
        assertNull(HonorLink.parse(text = "https://honor.pilgrimapp.org/Qoi4YmPHLN%0A"))
    }

    @Test
    fun `the query and the fragment are ignored`() {
        assertEquals(SHARE, HonorLink.parse(text = "https://walk.pilgrimapp.org/Qoi4YmPHLN?id=Other12345"))
        assertEquals(SHARE, HonorLink.parse(text = "https://walk.pilgrimapp.org/Qoi4YmPHLN#Other12345"))
        assertNull("an id in the query is not a path", HonorLink.parse(text = "https://honor.pilgrimapp.org/?id=Qoi4YmPHLN"))
    }

    // iOS 18's `URL(string:)` percent-encodes what RFC 3986 refuses, so these parse there; `URI` alone would throw.
    @Test
    fun `a query or fragment that URI would refuse is ignored as iOS ignores it`() {
        listOf(
            "https://walk.pilgrimapp.org/Qoi4YmPHLN?utm=50%",
            "https://walk.pilgrimapp.org/Qoi4YmPHLN?q=a b",
            "https://walk.pilgrimapp.org/Qoi4YmPHLN#m3#x",
            "https://walk.pilgrimapp.org/Qoi4YmPHLN?x={y}|^`\\\"<>",
            "walk.pilgrimapp.org/Qoi4YmPHLN#a b",
        ).forEach { assertEquals(it, SHARE, HonorLink.parse(text = it)) }
    }

    @Test
    fun `the same characters in the path still fail the id`() {
        listOf(
            "https://walk.pilgrimapp.org/Qoi4 YmPHLN?q=a",
            "https://walk.pilgrimapp.org/Qoi4{mPHLN#x",
            "https://walk.pilgrimapp.org/Qoi4YmPHLN%?utm=x",
        ).forEach { assertNull(it, HonorLink.parse(text = it)) }
    }

    @Test
    fun `the id is case-sensitive and only the host is folded`() {
        assertEquals(HonorLinkDestination.Share("qoi4ymphln"), HonorLink.parse(text = "https://honor.pilgrimapp.org/qoi4ymphln"))
        assertEquals(SHARE, HonorLink.parse(text = "https://HONOR.PILGRIMAPP.ORG/Qoi4YmPHLN"))
    }

    // S2 §11.1: the rows where Kotlin's and Foundation's defaults differ.

    @Test
    fun `pasted text is trimmed with Swift's whitespace set, not Kotlin's`() {
        assertEquals(SHARE, HonorLink.parse(text = cp(0x0085) + ID + cp(0x200B)))
        assertNull("Swift keeps U+001C, which Kotlin's trim() drops", HonorLink.parse(text = cp(0x001C) + ID))
    }

    @Test
    fun `extra words and look-alike hosts are refused, though Uri_parse would take them`() {
        listOf(
            "see https://walk.pilgrimapp.org/Qoi4YmPHLN",
            "https://walk.pilgrimapp.org/Qoi4YmPHLN thanks",
            "https://walk.pilgrimapp.org.evil.com/Qoi4YmPHLN",
            "https://walk.pilgrimapp.org./Qoi4YmPHLN",
            "https://evil.walk.pilgrimapp.org/Qoi4YmPHLN",
            "https://walk.pilgrimapp.org/Qoi4YmPHLN%",
        ).forEach { assertNull(it, HonorLink.parse(text = it)) }
    }

    @Test
    fun `user info, a port, and empty path segments are ignored`() {
        assertEquals(SHARE, HonorLink.parse(text = "https://u@walk.pilgrimapp.org:8443/Qoi4YmPHLN"))
        assertEquals(SHARE, HonorLink.parse(text = "https://walk.pilgrimapp.org//Qoi4YmPHLN//"))
    }

    // iOS `testStageLinksCountFromOne`, through `URI`, the paste's parser.
    @Test
    fun `stage links count from one`() {
        listOf(
            "https://honor.pilgrimapp.org/camino-frances/14",
            "https://honor.pilgrimapp.org/camino-frances/14/",
            "https://honor.pilgrimapp.org/camino-frances/14?utm_source=pilgrimages#x",
        ).forEach { assertEquals(it, STAGE_14, HonorLink.parse(URI(it))) }
        assertEquals(HonorLinkDestination.Stage("shikoku-88-tosa", 0), HonorLink.parse(URI("https://honor.pilgrimapp.org/shikoku-88-tosa/1")))
        assertEquals(HonorLinkDestination.Stage("camino-frances", 199), HonorLink.parse(URI("https://honor.pilgrimapp.org/camino-frances/200")))
    }

    // iOS `testMalformedStageLinksOnTheHonorHostAreUnrecognised`, plus the spec's `+14` and a non-ASCII digit row.
    @Test
    fun `a malformed stage link on the honor host is unrecognised`() {
        listOf(
            "https://honor.pilgrimapp.org/camino-frances/0",
            "https://honor.pilgrimapp.org/camino-frances/014",
            "https://honor.pilgrimapp.org/camino-frances/201",
            "https://honor.pilgrimapp.org/camino-frances/+14",
            "https://honor.pilgrimapp.org/camino-frances/%D9%A1%D9%A4",
            "https://honor.pilgrimapp.org/camino-frances/14.",
            "https://honor.pilgrimapp.org/Camino-Frances/14",
            "https://honor.pilgrimapp.org/camino-frances",
            "https://honor.pilgrimapp.org/a/b/c",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/extra",
        ).forEach { assertEquals(it, HonorLinkDestination.Unrecognised, HonorLink.parse(URI(it))) }
    }

    // iOS `testStageLinksLiveOnlyOnTheHonorHost`, `testTheHonorHostRootIsNotALink`.
    @Test
    fun `stage links live only on the honor host, and its root is no link`() {
        assertNull(HonorLink.parse(URI("https://walk.pilgrimapp.org/camino-frances/14")))
        assertNull(HonorLink.parse(URI("https://example.com/camino-frances/14")))
        assertNull(HonorLink.parse(URI("https://honor.pilgrimapp.org/")))
        assertNull(HonorLink.parse(URI("https://honor.pilgrimapp.org")))
    }

    // iOS `testPastedStageLinksAreAccepted`.
    @Test
    fun `pasted stage links are accepted`() {
        assertEquals(STAGE_14, HonorLink.parse(text = "honor.pilgrimapp.org/camino-frances/14"))
        assertEquals(STAGE_14, HonorLink.parse(text = " https://honor.pilgrimapp.org/camino-frances/14\n"))
        assertEquals(STAGE_14, HonorLink.parse(text = "http://HONOR.pilgrimapp.org/camino-frances/14?utm_source=x"))
    }

    // iOS `testPastedTextNeverComesBackUnrecognised`; Review Focus 1, a link pasted with the punctuation around it.
    @Test
    fun `pasted text never comes back unrecognised`() {
        listOf(
            "https://honor.pilgrimapp.org/camino-frances/0",
            "honor.pilgrimapp.org/whatever/else/here",
            "honor.pilgrimapp.org/camino-frances/14.",
            "honor.pilgrimapp.org/camino-frances/14)",
            "(honor.pilgrimapp.org/camino-frances/14)",
            "see honor.pilgrimapp.org/camino-frances/14",
        ).forEach { assertNull(it, HonorLink.parse(text = it)) }
    }

    private fun cp(code: Int): String = Char(code).toString()

    private companion object {
        const val ID = "Qoi4YmPHLN"
        val SHARE = HonorLinkDestination.Share(ID)
        val STAGE_14 = HonorLinkDestination.Stage(routeId = "camino-frances", index = 13)
    }
}
