// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.media.AudioFormat
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * iOS's waveform binning (`WaveformGenerator.swift@db4196e`): each bar its
 * stretch's peak, the loudest scaled to 1, fed by the recorder's WAV and by
 * a decoder's PCM for a shared Way's `.m4a`. Robolectric can't run a real
 * AAC decode, so the decoder's output is fed here as the codec hands it
 * over; the decode itself is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WaveformGeneratorTest {

    @get:Rule val folder = TemporaryFolder()

    private fun pcm16(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it.toShort()) }
            flip()
        }

    private fun pcmFloat(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putFloat(it) }
            flip()
        }

    private fun format(channels: Int, encoding: Int? = null) = MediaFormat().apply {
        setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)
        encoding?.let { setInteger(MediaFormat.KEY_PCM_ENCODING, it) }
    }

    private fun wav(vararg samples: Int): File {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it.toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2)
        }
        return folder.newFile("voice.wav").apply { writeBytes(header.array() + data.array()) }
    }

    @Test
    fun `each bar is its stretch's peak, scaled so the loudest reads one`() {
        val bins = PeakBins(bars = 3, totalFrames = 6)
        listOf(100, -400, 50, 200, 0, -100).forEach { bins.add(kotlin.math.abs(it)) }

        assertArrayEquals(floatArrayOf(1f, 0.5f, 0.25f), bins.normalized(), 1e-6f)
    }

    @Test
    fun `frames past the last whole stretch are dropped, and silence stays silent`() {
        val bins = PeakBins(bars = 2, totalFrames = 5)
        listOf(1, 2, 3, 4, 1000).forEach(bins::add)

        assertArrayEquals(floatArrayOf(0.5f, 1f), bins.normalized(), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), PeakBins(bars = 2, totalFrames = 4).normalized(), 0f)
    }

    @Test
    fun `a decoder's stereo frames take their louder channel`() {
        val bins = PeakBins(bars = 2, totalFrames = 2)
        val frames = PcmFrames(bins, format(channels = 2))

        frames.add(pcm16(100, -300, 600, 50))

        assertArrayEquals(floatArrayOf(0.5f, 1f), bins.normalized(), 1e-6f)
    }

    @Test
    fun `float PCM is read as the format says, after a format change too`() {
        val bins = PeakBins(bars = 3, totalFrames = 3)
        val frames = PcmFrames(bins, format(channels = 1))
        frames.add(pcm16(16_384))

        frames.format(format(channels = 1, encoding = AudioFormat.ENCODING_PCM_FLOAT))
        frames.add(pcmFloat(-1f, 0.25f))

        assertArrayEquals(floatArrayOf(16_384f / 32_767f, 1f, 8_191f / 32_767f), bins.normalized(), 1e-4f)
    }

    @Test
    fun `the recorder's WAV bins through the same peaks`() = runBlocking {
        val bars = WaveformGenerator.generate(wav(100, -400, 50, 200, 0, -100), bars = 3)

        assertArrayEquals(floatArrayOf(1f, 0.5f, 0.25f), bars, 1e-6f)
    }

    @Test
    fun `a file that is neither a WAV nor something the platform decodes has no bars`() = runBlocking {
        val garbage = folder.newFile("1.m4a").apply { writeBytes(ByteArray(64) { 7 }) }

        assertNull(WaveformGenerator.generate(garbage, bars = 10))
    }
}
