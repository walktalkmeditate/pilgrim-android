// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SealShareBitmapWriterTest {

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        java.io.File(context.cacheDir, SealShareBitmapWriter.CACHE_SUBDIR).deleteRecursively()
    }

    @Test
    fun writeToCache_producesNonEmptyPngFile() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val file = SealShareBitmapWriter.writeToCache(bmp, "test-suffix", context)
        assertTrue(file.exists())
        assertTrue("file is non-empty", file.length() > 0)
        assertTrue("filename has png extension", file.name.endsWith(".png"))
        assertTrue("filename contains the suffix", file.name.contains("test-suffix"))
    }

    @Test
    fun writeToCache_isIdempotent_overwritesExisting() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val first = SealShareBitmapWriter.writeToCache(bmp, "stable", context)
        val firstSize = first.length()
        val second = SealShareBitmapWriter.writeToCache(bmp, "stable", context)
        assertTrue(second.exists())
        assertTrue("same filename produces same path", first.absolutePath == second.absolutePath)
        assertTrue("re-write didn't corrupt file", second.length() == firstSize)
    }
}
