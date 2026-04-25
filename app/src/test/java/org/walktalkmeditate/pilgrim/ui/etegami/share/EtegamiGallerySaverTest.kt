// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Asserts the saver's invariants that are portable to a Robolectric
 * test environment — the filename guard and the error-path
 * conversion. The API 29+ happy-path is exercised on-device in
 * Stage 7-D QA.
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
    fun `saveToGallery returns Failed not throws when MediaStore insert returns null`() = runBlocking {
        // Override Robolectric's default `media` provider with one
        // whose insert returns null — the real-device failure mode
        // when the system MediaProvider rejects the row (e.g.,
        // out-of-disk, locked storage volume). The saver must map
        // null insert to SaveResult.Failed, not NPE.
        val nullProvider = Robolectric.buildContentProvider(NullInsertProvider::class.java)
            .create("media")
            .get()
        ShadowContentResolver.registerProviderInternal("media", nullProvider)

        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val result = EtegamiGallerySaver.saveToGallery(bitmap, "ok.png", context)
        assertTrue(
            "saver should convert null-insert to Failed, got $result",
            result is EtegamiGallerySaver.SaveResult.Failed,
        )
    }

    class NullInsertProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor? = null
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun getType(uri: Uri): String? = null
    }
}
