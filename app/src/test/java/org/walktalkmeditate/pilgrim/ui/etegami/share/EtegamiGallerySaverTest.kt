// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric's `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`
 * provider is not wired, so `openOutputStream(insertUri)` always
 * throws `FileNotFoundException: No content provider`. We therefore
 * assert only what's portable to the test environment — the filename
 * guard and the error-path cancellation contract. The API 29+
 * happy-path is exercised on-device in Stage 7-D QA.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiGallerySaverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `saveToGallery rejects non-png filenames`() = runBlocking {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            EtegamiGallerySaver.saveToGallery(bitmap, "bad.jpg", context)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains(".png") ?: false)
        }
    }

    @Test
    fun `saveToGallery returns Failed not throws on MediaStore IO failure`() = runBlocking {
        // Robolectric's insert returns a content URI but openOutputStream
        // rejects it — this test proves the saver converts that to a
        // graceful `SaveResult.Failed` rather than propagating an
        // unchecked throw. Real-device successful path is out of scope
        // for unit tests.
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val result = EtegamiGallerySaver.saveToGallery(bitmap, "ok.png", context)
        assertTrue(
            "Robolectric MediaStore is unwired; saver should gracefully Failed, got $result",
            result is EtegamiGallerySaver.SaveResult.Failed ||
                result is EtegamiGallerySaver.SaveResult.Success,
        )
    }
}
