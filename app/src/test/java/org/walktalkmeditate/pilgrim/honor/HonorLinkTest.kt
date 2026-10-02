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
 * Port of iOS `HonorLinkTests.swift@7c200bf` (its stash test belongs to
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
        ).forEach { assertEquals(it, ID, HonorLink.parse(text = it)) }
        assertEquals(ID, HonorLink.parse(URI("https://honor.pilgrimapp.org/Qoi4YmPHLN")))
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
        assertEquals(ID, HonorLink.parse(text = "https://HONOR.pilgrimapp.org/Qoi4YmPHLN"))
        assertEquals(ID, HonorLink.parse(URI("https://HONOR.pilgrimapp.org/Qoi4YmPHLN")))
    }

    /** `$` matches before a trailing newline, so an id pasted with one used to pass every shape check. */
    @Test
    fun `a trailing newline is rejected`() {
        assertNull(HonorLink.parse(URI("https://honor.pilgrimapp.org/Qoi4YmPHLN%0A")))
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
        ).forEach { assertEquals(it, ID, HonorLink.parse(text = it)) }
    }

    @Test
    fun `percent-escapes are decoded before the id check`() {
        assertEquals(ID, HonorLink.parse(text = "https://honor.pilgrimapp.org/%51oi4YmPHLN"))
        assertNull("an escaped slash decodes into a character the id refuses", HonorLink.parse(text = "https://honor.pilgrimapp.org/Qoi4%2FYmPHL"))
        assertNull(HonorLink.parse(text = "https://honor.pilgrimapp.org/Qoi4YmPHLN%0A"))
    }

    @Test
    fun `the query and the fragment are ignored`() {
        assertEquals(ID, HonorLink.parse(text = "https://walk.pilgrimapp.org/Qoi4YmPHLN?id=Other12345"))
        assertEquals(ID, HonorLink.parse(text = "https://walk.pilgrimapp.org/Qoi4YmPHLN#Other12345"))
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
        ).forEach { assertEquals(it, ID, HonorLink.parse(text = it)) }
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
        assertEquals("qoi4ymphln", HonorLink.parse(text = "https://honor.pilgrimapp.org/qoi4ymphln"))
        assertEquals(ID, HonorLink.parse(text = "https://HONOR.PILGRIMAPP.ORG/Qoi4YmPHLN"))
    }

    // S2 §11.1: the rows where Kotlin's and Foundation's defaults differ.

    @Test
    fun `pasted text is trimmed with Swift's whitespace set, not Kotlin's`() {
        assertEquals(ID, HonorLink.parse(text = cp(0x0085) + ID + cp(0x200B)))
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
        assertEquals(ID, HonorLink.parse(text = "https://u@walk.pilgrimapp.org:8443/Qoi4YmPHLN"))
        assertEquals(ID, HonorLink.parse(text = "https://walk.pilgrimapp.org//Qoi4YmPHLN//"))
    }

    private fun cp(code: Int): String = Char(code).toString()

    private companion object {
        const val ID = "Qoi4YmPHLN"
    }
}
