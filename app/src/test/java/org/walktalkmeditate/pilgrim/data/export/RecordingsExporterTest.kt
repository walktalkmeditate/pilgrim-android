// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.export

import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingsExporterTest {

    private lateinit var workspace: File

    @Before
    fun setUp() {
        workspace = File(System.getProperty("java.io.tmpdir"), "rec-export-${UUID.randomUUID()}")
        workspace.mkdirs()
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    @Test
    fun `empty source dir returns null and writes nothing`() = runTest {
        val source = File(workspace, "src").also { it.mkdirs() }
        val target = File(workspace, "out")

        val result = RecordingsExporter.export(
            sourceDir = source,
            targetDir = target,
            now = Instant.parse("2026-04-28T12:00:00Z"),
        )

        assertNull("empty source dir should return null", result)
        assertFalse("target dir should not be created", target.exists())
    }

    @Test
    fun `nonexistent source dir returns null`() = runTest {
        val source = File(workspace, "missing")
        val target = File(workspace, "out")

        val result = RecordingsExporter.export(
            sourceDir = source,
            targetDir = target,
            now = Instant.parse("2026-04-28T12:00:00Z"),
        )

        assertNull(result)
        assertFalse(target.exists())
    }

    @Test
    fun `multi-file export produces valid ZIP with all entries`() = runTest {
        val source = File(workspace, "src").also { it.mkdirs() }
        File(source, "a.wav").writeBytes(ByteArray(1024) { it.toByte() })
        File(source, "b.wav").writeBytes(ByteArray(2048) { (it / 2).toByte() })
        val target = File(workspace, "out")

        val result = RecordingsExporter.export(
            sourceDir = source,
            targetDir = target,
            now = Instant.parse("2026-04-28T12:00:00Z"),
        )

        assertNotNull(result)
        ZipFile(result!!).use { zip ->
            val names = zip.entries().toList().map { it.name }.sorted()
            assertEquals(listOf("a.wav", "b.wav"), names)
        }
        assertTrue(result.name.startsWith("pilgrim-recordings-"))
        assertTrue(result.name.endsWith(".zip"))
    }

    @Test
    fun `nested files are preserved as relative paths`() = runTest {
        val source = File(workspace, "src").also { it.mkdirs() }
        val nested = File(source, "walks/123").also { it.mkdirs() }
        File(nested, "voice.wav").writeBytes(ByteArray(64))
        val target = File(workspace, "out")

        val result = RecordingsExporter.export(
            sourceDir = source,
            targetDir = target,
            now = Instant.parse("2026-04-28T12:00:00Z"),
        )

        assertNotNull(result)
        ZipFile(result!!).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(listOf("walks/123/voice.wav"), names)
        }
    }
}
