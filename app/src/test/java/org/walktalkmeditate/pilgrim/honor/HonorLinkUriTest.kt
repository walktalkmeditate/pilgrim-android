// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HonorLink.parse] over a link the OS hands the app, as an intent's
 * [Uri]: the tapped column of shared-walk spec S2 §11.1 and the stage-links
 * spec's grammar (`HonorLinkTests.swift@3b78728`). The platform's own parse
 * decodes each path segment and drops the empty ones, as Foundation's
 * `pathComponents` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorLinkUriTest {

    @Test
    fun `the tapped share forms iOS opens`() {
        listOf(
            "https://honor.pilgrimapp.org/Qoi4YmPHLN",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN?utm=x#m3",
            "https://HONOR.pilgrimapp.org/Qoi4YmPHLN",
            "https://honor.pilgrimapp.org/%51oi4YmPHLN",
            "https://honor.pilgrimapp.org//Qoi4YmPHLN//",
            "https://u@honor.pilgrimapp.org:8443/Qoi4YmPHLN",
        ).forEach { assertEquals(it, SHARE, HonorLink.parse(Uri.parse(it))) }
    }

    // iOS `testStageLinksCountFromOne`.
    @Test
    fun `a tapped stage link counts from one`() {
        listOf(
            "https://honor.pilgrimapp.org/camino-frances/14",
            "https://honor.pilgrimapp.org/camino-frances/14/",
            "https://honor.pilgrimapp.org/camino-frances/14?utm_source=pilgrimages#x",
            "https://HONOR.pilgrimapp.org/camino-frances/14",
            "https://honor.pilgrimapp.org//camino-frances//14",
        ).forEach { assertEquals(it, HonorLinkDestination.Stage("camino-frances", 13), HonorLink.parse(Uri.parse(it))) }
        assertEquals(HonorLinkDestination.Stage("shikoku-88-tosa", 0), HonorLink.parse(Uri.parse("https://honor.pilgrimapp.org/shikoku-88-tosa/1")))
        assertEquals(HonorLinkDestination.Stage("camino-frances", 199), HonorLink.parse(Uri.parse("https://honor.pilgrimapp.org/camino-frances/200")))
    }

    // iOS `testMalformedStageLinksOnTheHonorHostAreUnrecognised`, `testTrailingNewlineIsRejected`, and every honor path that once did nothing.
    @Test
    fun `any other tapped path on the honor host is unrecognised`() {
        listOf(
            "https://honor.pilgrimapp.org/camino-frances/0",
            "https://honor.pilgrimapp.org/camino-frances/014",
            "https://honor.pilgrimapp.org/camino-frances/201",
            "https://honor.pilgrimapp.org/camino-frances/+14",
            "https://honor.pilgrimapp.org/camino-frances/14.",
            "https://honor.pilgrimapp.org/camino-frances/14)",
            "https://honor.pilgrimapp.org/Camino-Frances/14",
            "https://honor.pilgrimapp.org/camino-frances",
            "https://honor.pilgrimapp.org/a/b/c",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/extra",
            "https://honor.pilgrimapp.org/camino-frances/14%0A",
            "https://honor.pilgrimapp.org/abc",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN%0A",
            "https://honor.pilgrimapp.org/Qoi4%2FYmPHL",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN%",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/audio/1.m4a",
            "https://honor.pilgrimapp.org/Qoi4YmPHL",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN1",
        ).forEach { assertEquals(it, HonorLinkDestination.Unrecognised, HonorLink.parse(Uri.parse(it))) }
    }

    // iOS `testTheHonorHostRootIsNotALink`, `testStageLinksLiveOnlyOnTheHonorHost`.
    @Test
    fun `the honor root, other hosts, and a stage shape on the walk host are no link`() {
        listOf(
            "https://honor.pilgrimapp.org/",
            "https://honor.pilgrimapp.org",
            "https://honor.pilgrimapp.org/?id=Qoi4YmPHLN",
            "https://walk.pilgrimapp.org/camino-frances/14",
            "https://example.com/camino-frances/14",
            "https://example.com/Qoi4YmPHLN",
            "https://honor.pilgrimapp.org.evil.com/Qoi4YmPHLN",
            "mailto:Qoi4YmPHLN@honor.pilgrimapp.org",
        ).forEach { assertNull(it, HonorLink.parse(Uri.parse(it))) }
    }

    @Test
    fun `the id keeps its case`() {
        assertEquals(HonorLinkDestination.Share("qoi4ymphln"), HonorLink.parse(Uri.parse("https://honor.pilgrimapp.org/qoi4ymphln")))
    }

    private companion object {
        val SHARE = HonorLinkDestination.Share("Qoi4YmPHLN")
    }
}
