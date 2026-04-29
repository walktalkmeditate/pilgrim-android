// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceRecordingFileSystemTest {

    private lateinit var context: Application
    private lateinit var fileSystem: VoiceRecordingFileSystem

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileSystem = VoiceRecordingFileSystem(context)
    }

    @Test
    fun `absolutePath joins filesDir with relative path`() {
        val abs = fileSystem.absolutePath("recordings/walk-uuid/rec-uuid.wav")
        assertEquals(File(context.filesDir, "recordings/walk-uuid/rec-uuid.wav"), abs)
    }

    @Test
    fun `fileExists false when file missing`() {
        assertFalse(fileSystem.fileExists("recordings/missing.wav"))
    }

    @Test
    fun `fileExists true after writing file`() {
        val rel = "recordings/test.wav"
        val abs = fileSystem.absolutePath(rel)
        abs.parentFile?.mkdirs()
        abs.writeText("hi")
        assertTrue(fileSystem.fileExists(rel))
    }

    @Test
    fun `fileSizeBytes returns 0 when file missing`() {
        assertEquals(0L, fileSystem.fileSizeBytes("recordings/missing.wav"))
    }

    @Test
    fun `fileSizeBytes returns actual size when file exists`() {
        val rel = "recordings/sized.wav"
        val abs = fileSystem.absolutePath(rel)
        abs.parentFile?.mkdirs()
        abs.writeBytes(ByteArray(1024) { it.toByte() })
        assertEquals(1024L, fileSystem.fileSizeBytes(rel))
    }

    @Test
    fun `deleteFile returns true and removes file`() = runTest {
        val rel = "recordings/del.wav"
        val abs = fileSystem.absolutePath(rel)
        abs.parentFile?.mkdirs()
        abs.writeText("bye")
        assertTrue(fileSystem.deleteFile(rel))
        assertFalse(abs.exists())
    }

    @Test
    fun `deleteFile returns false when file already missing`() = runTest {
        assertFalse(fileSystem.deleteFile("recordings/never.wav"))
    }
}
