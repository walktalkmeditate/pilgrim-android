// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [EtegamiShareIntentFactory.buildFromUri] under
 * Robolectric — pure-intent plumbing tests with no FileProvider
 * dependency. The [EtegamiShareIntentFactory.buildFromFile] path
 * goes through `FileProvider.getUriForFile` which doesn't match
 * paths correctly under Robolectric+macOS (`/var → /private/var`
 * symlink); that path is exercised on-device in stage QA.
 *
 * `application = Application::class` override keeps `PilgrimApp`
 * out of the test fixture — its onCreate loads the native Mapbox
 * library, which Robolectric can't resolve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiShareIntentFactoryTest {

    private val uri: Uri = Uri.parse(
        "content://org.walktalkmeditate.pilgrim.debug.fileprovider/etegami_cache/test.png",
    )

    @Test
    fun `buildFromUri returns a chooser Intent wrapping ACTION_SEND with image-png type`() {
        val chooser = EtegamiShareIntentFactory.buildFromUri(uri, "test.png", "Share")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(inner)
        assertEquals(Intent.ACTION_SEND, inner!!.action)
        assertEquals("image/png", inner.type)
    }

    @Test
    fun `inner intent carries EXTRA_STREAM and a matching ClipData newRawUri`() {
        val chooser = EtegamiShareIntentFactory.buildFromUri(uri, "test.png", "Share")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        val stream = inner.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(uri, stream)
        val clip = inner.clipData
        assertNotNull("ClipData is mandatory for modern chooser previews", clip)
        assertEquals(1, clip!!.itemCount)
        assertEquals(uri, clip.getItemAt(0).uri)
        assertEquals("test.png", clip.description.label)
    }

    @Test
    fun `inner intent grants read URI permission`() {
        val chooser = EtegamiShareIntentFactory.buildFromUri(uri, "test.png", "Share")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(
            "FLAG_GRANT_READ_URI_PERMISSION missing",
            (inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
        assertTrue(
            "chooser-level FLAG_GRANT_READ_URI_PERMISSION missing",
            (chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
    }

    @Test
    fun `chooser title is applied`() {
        val chooser = EtegamiShareIntentFactory.buildFromUri(uri, "test.png", "Share etegami")
        assertEquals("Share etegami", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
    }
}
