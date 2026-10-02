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
 * [Uri]: the tapped column of shared-walk spec S2 §11.1. The platform's
 * own parse decodes each path segment and drops the empty ones, as
 * Foundation's `pathComponents` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorLinkUriTest {

    @Test
    fun `the tapped forms iOS opens`() {
        listOf(
            "https://honor.pilgrimapp.org/Qoi4YmPHLN",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN?utm=x#m3",
            "https://HONOR.pilgrimapp.org/Qoi4YmPHLN",
            "https://honor.pilgrimapp.org/%51oi4YmPHLN",
            "https://honor.pilgrimapp.org//Qoi4YmPHLN//",
            "https://u@honor.pilgrimapp.org:8443/Qoi4YmPHLN",
        ).forEach { assertEquals(it, ID, HonorLink.parse(Uri.parse(it))) }
    }

    @Test
    fun `the tapped forms that open the app and do nothing`() {
        listOf(
            "https://honor.pilgrimapp.org/",
            "https://honor.pilgrimapp.org/abc",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN%0A",
            "https://honor.pilgrimapp.org/Qoi4%2FYmPHL",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN%",
            "https://honor.pilgrimapp.org/?id=Qoi4YmPHLN",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/audio/1.m4a",
            "https://honor.pilgrimapp.org/Qoi4YmPHL",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN1",
            "https://example.com/Qoi4YmPHLN",
            "https://honor.pilgrimapp.org.evil.com/Qoi4YmPHLN",
            "mailto:Qoi4YmPHLN@honor.pilgrimapp.org",
        ).forEach { assertNull(it, HonorLink.parse(Uri.parse(it))) }
    }

    @Test
    fun `the id keeps its case`() {
        assertEquals("qoi4ymphln", HonorLink.parse(Uri.parse("https://honor.pilgrimapp.org/qoi4ymphln")))
    }

    private companion object {
        const val ID = "Qoi4YmPHLN"
    }
}
