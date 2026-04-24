// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtegamiPngWriterTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun cleanup() {
        EtegamiPngWriter.cacheRoot(context).deleteRecursively()
    }

    @Test
    fun `writeToCache produces a PNG file with the PNG magic header`() = runBlocking {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val file = EtegamiPngWriter.writeToCache(bitmap, "test-small.png", context)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        val header = file.inputStream().use { it.readNBytes(8) }
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            header,
        )
    }

    @Test
    fun `writeToCache overwrites an existing file idempotently`() = runBlocking {
        val b1 = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val b2 = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val f1 = EtegamiPngWriter.writeToCache(b1, "overwrite.png", context)
        val size1 = f1.length()
        val f2 = EtegamiPngWriter.writeToCache(b2, "overwrite.png", context)
        assertEquals(f1.absolutePath, f2.absolutePath)
        assertTrue(f2.length() > 0)
        assertTrue(size1 > 0)
    }

    @Test
    fun `writeToCache rejects non-png filenames`() = runBlocking {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            EtegamiPngWriter.writeToCache(bitmap, "bad.jpg", context)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains(".png") ?: false)
        }
    }

    @Test
    fun `writeToCache cleans up the tmp file on success`() = runBlocking {
        val bitmap = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
        EtegamiPngWriter.writeToCache(bitmap, "clean.png", context)
        val tmpFiles = EtegamiPngWriter.cacheRoot(context)
            .listFiles { f -> f.name.endsWith(".tmp") }
        assertTrue("no .tmp stragglers expected", tmpFiles.isNullOrEmpty())
    }
}
