// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class WaveformLoaderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `decodes 16-bit PCM mono WAV and downsamples to N bars`() {
        // Synthesize 16 kHz mono PCM with a 1-second triangle wave.
        val sampleRate = 16_000
        val numSamples = sampleRate
        val pcm = ByteArray(numSamples * 2)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val v = (Math.sin(2.0 * Math.PI * 440.0 * t) * 16_384).toInt().toShort()
            bb.putShort(v)
        }
        val wav = buildWavFile(pcm, sampleRate)
        val f = tmp.newFile("test.wav")
        f.writeBytes(wav)

        val samples = WaveformLoader.load(f, barCount = 64)
        assertEquals(64, samples.size)
        // All samples should be in [0, 1] (RMS-normalized magnitude).
        samples.forEach { assertTrue("sample out of range: $it", it in 0f..1f) }
        // For a constant-amplitude sine, all bars should be roughly equal.
        val avg = samples.average().toFloat()
        samples.forEach { assertTrue(Math.abs(it - avg) < 0.2f) }
    }

    @Test fun `returns flat-line FloatArray on truncated header`() {
        val f = tmp.newFile("bad.wav")
        f.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46))  // just "RIFF"
        val samples = WaveformLoader.load(f, barCount = 64)
        assertEquals(64, samples.size)
        samples.forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test fun `returns flat-line on missing file`() {
        val f = File(tmp.root, "missing.wav")
        val samples = WaveformLoader.load(f, barCount = 32)
        assertEquals(32, samples.size)
        samples.forEach { assertEquals(0f, it, 0.001f) }
    }

    /** Wrap [pcm] in a minimal RIFF WAV header (16-bit mono). */
    private fun buildWavFile(pcm: ByteArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)         // PCM
        header.putShort(1)         // mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)         // block align
        header.putShort(16)        // bits per sample
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
