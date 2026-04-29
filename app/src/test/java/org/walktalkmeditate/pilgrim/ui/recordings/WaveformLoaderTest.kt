// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    @Test fun `returns flat-line when fmt chunk is shorter than 16 bytes`() {
        // A non-standard or corrupt WAV may declare a fmt chunk smaller than
        // the 16 bytes we read into; without an explicit guard, getShort(14)
        // would throw BufferUnderflowException and silently produce a flat
        // waveform. The guard makes the rejection deterministic.
        val pcm = ByteArray(64) { 0 }
        val header = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(28 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(8)              // fmt chunk size declared as 8 (< 16)
        header.putShort(1)            // PCM
        header.putShort(1)            // mono
        header.putInt(16_000)         // sample rate (still under-sized, total 8 bytes)
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        val wav = header.array() + pcm
        val f = tmp.newFile("short-fmt.wav")
        f.writeBytes(wav)

        val samples = WaveformLoader.load(f, barCount = 32)
        assertEquals(32, samples.size)
        samples.forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test fun `returns flat-line on missing file`() {
        val f = File(tmp.root, "missing.wav")
        val samples = WaveformLoader.load(f, barCount = 32)
        assertEquals(32, samples.size)
        samples.forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test fun `streams a 60-second WAV without holding it all in memory`() {
        // 60 s mono 16 kHz 16-bit ≈ 1.83 MB of PCM. Each bar's bucket is
        // ~30 KB — well above the 8 KB read buffer, so this exercises the
        // multi-read-per-bucket path of the streaming loader.
        val sampleRate = 16_000
        val numSamples = sampleRate * 60
        val pcm = ByteArray(numSamples * 2)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val v = (Math.sin(2.0 * Math.PI * 220.0 * t) * 16_384).toInt().toShort()
            bb.putShort(v)
        }
        val wav = buildWavFile(pcm, sampleRate)
        val f = tmp.newFile("long.wav")
        f.writeBytes(wav)

        val samples = WaveformLoader.load(f, barCount = 64)
        assertEquals(64, samples.size)
        samples.forEach { assertTrue("sample out of range: $it", it in 0f..1f) }
        // Constant-amplitude sine ⇒ all bars within a tight band of the mean.
        val avg = samples.average().toFloat()
        samples.forEach { assertTrue(Math.abs(it - avg) < 0.2f) }
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
