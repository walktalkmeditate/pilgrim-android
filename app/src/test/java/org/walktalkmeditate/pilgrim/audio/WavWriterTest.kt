// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WavWriterTest {

    private lateinit var tempDir: java.nio.file.Path

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("wavwriter-test")
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `empty file has byte-exact RIFF WAVE 16kHz mono 16-bit header`() {
        val path = tempDir.resolve("empty.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)

        writer.openForWriting()
        val bytes = writer.closeAndPatchHeader()

        assertEquals(0L, bytes)
        val file = Files.readAllBytes(path)
        assertEquals(44, file.size)
        assertEquals("RIFF", String(file, 0, 4, Charsets.US_ASCII))
        assertEquals(36, readLeInt(file, 4))               // chunk size = 44 - 8
        assertEquals("WAVE", String(file, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(file, 12, 4, Charsets.US_ASCII))
        assertEquals(16, readLeInt(file, 16))              // fmt subchunk size for PCM
        assertEquals(1, readLeShort(file, 20))             // audio format = PCM
        assertEquals(1, readLeShort(file, 22))             // mono
        assertEquals(16_000, readLeInt(file, 24))          // sample rate
        assertEquals(32_000, readLeInt(file, 28))          // byte rate = 16000 * 1 * 2
        assertEquals(2, readLeShort(file, 32))             // block align
        assertEquals(16, readLeShort(file, 34))            // bits per sample
        assertEquals("data", String(file, 36, 4, Charsets.US_ASCII))
        assertEquals(0, readLeInt(file, 40))               // data size
    }

    @Test
    fun `one sample patches data_size to 2 and chunk_size to 38`() {
        val path = tempDir.resolve("one.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)

        writer.openForWriting()
        writer.append(shortArrayOf(0x1234), count = 1)
        val bytes = writer.closeAndPatchHeader()

        assertEquals(2L, bytes)
        val file = Files.readAllBytes(path)
        assertEquals(46, file.size)
        assertEquals(38, readLeInt(file, 4))               // chunk size = 46 - 8
        assertEquals(2, readLeInt(file, 40))               // data size
        // 0x1234 → LE 0x34 0x12
        assertEquals(0x34.toByte(), file[44])
        assertEquals(0x12.toByte(), file[45])
    }

    @Test
    fun `three samples encoded little-endian in order`() {
        val path = tempDir.resolve("three.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)

        writer.openForWriting()
        writer.append(shortArrayOf(0x0001, 0x7FFF, 0x8001.toShort()), count = 3)
        val bytes = writer.closeAndPatchHeader()

        assertEquals(6L, bytes)
        val file = Files.readAllBytes(path)
        val expectedPcm = byteArrayOf(
            0x01, 0x00,                         // 0x0001 LE
            0xFF.toByte(), 0x7F,                // 0x7FFF LE
            0x01, 0x80.toByte(),                // 0x8001 LE
        )
        assertArrayEquals(expectedPcm, file.sliceArray(44 until 50))
    }

    @Test
    fun `append with partial count writes only that many samples`() {
        val path = tempDir.resolve("partial.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)

        writer.openForWriting()
        writer.append(shortArrayOf(1, 2, 3, 4, 5), count = 2)
        val bytes = writer.closeAndPatchHeader()

        assertEquals(4L, bytes)
        val file = Files.readAllBytes(path)
        assertEquals(48, file.size)
        // samples 1 and 2 encoded LE
        assertEquals(0x01.toByte(), file[44])
        assertEquals(0x00.toByte(), file[45])
        assertEquals(0x02.toByte(), file[46])
        assertEquals(0x00.toByte(), file[47])
    }

    @Test
    fun `sample rate and byte rate track the constructor param`() {
        val path = tempDir.resolve("rate.wav")
        val writer = WavWriter(path, sampleRateHz = 44_100)

        writer.openForWriting()
        writer.closeAndPatchHeader()

        val file = Files.readAllBytes(path)
        assertEquals(44_100, readLeInt(file, 24))
        assertEquals(44_100 * 2, readLeInt(file, 28))       // byte rate = rate * 1 * 2
    }

    @Test
    fun `append without open throws IllegalStateException and leaves file absent`() {
        val path = tempDir.resolve("never-opened.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)

        try {
            writer.append(shortArrayOf(1), count = 1)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertFalse("no file should have been created", Files.exists(path))
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readLeShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
}
