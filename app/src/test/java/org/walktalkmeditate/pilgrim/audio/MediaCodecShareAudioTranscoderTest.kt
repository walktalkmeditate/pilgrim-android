// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per the house platform-object builder rule: this class constructs a
 * REAL [MediaFormat] and a REAL [MediaMuxer] via
 * [MediaCodecShareAudioTranscoder]'s own builder functions — not
 * fakes. Robolectric cannot drive an actual hardware/software encode
 * loop, so the full [MediaCodecShareAudioTranscoder.transcode] happy
 * path (real PCM in, real AAC out) is NOT covered here; it is a
 * device-QA line item in a later unit. What IS covered here: the
 * exact [MediaFormat] keys the production code sets, that a real
 * [MediaMuxer] constructs against the production path function, the
 * WAV header parser against real fixture bytes (valid + malformed),
 * and the `transcode()` early-exit-on-malformed-WAV path (which never
 * touches a codec, so it validly runs under Robolectric end to end).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaCodecShareAudioTranscoderTest {

    private lateinit var tempDir: Path

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("share-transcoder-test")
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    // ---- MediaFormat construction (real builder, house rule) ----

    @Test
    fun `buildAacFormat sets AAC-LC mono 16kHz 64kbps exactly`() {
        val format = MediaCodecShareAudioTranscoder.buildAacFormat(sampleRateHz = 16_000, channelCount = 1)

        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
        assertEquals(16_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        assertEquals(64_000, format.getInteger(MediaFormat.KEY_BIT_RATE))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AACObjectLC, format.getInteger(MediaFormat.KEY_AAC_PROFILE))
    }

    @Test
    fun `buildAacFormat carries the source sample rate through, not a hardcoded one`() {
        val format = MediaCodecShareAudioTranscoder.buildAacFormat(sampleRateHz = 22_050, channelCount = 1)

        assertEquals(22_050, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
    }

    // ---- alignDownToFrame (U4 review fix #6: frame-aligned chunk sizes) ----

    @Test
    fun `alignDownToFrame clamps down to the nearest whole frame`() {
        assertEquals(8, MediaCodecShareAudioTranscoder.alignDownToFrame(chunkSize = 9, bytesPerFrame = 4))
        assertEquals(0, MediaCodecShareAudioTranscoder.alignDownToFrame(chunkSize = 3, bytesPerFrame = 4))
        assertEquals(12, MediaCodecShareAudioTranscoder.alignDownToFrame(chunkSize = 12, bytesPerFrame = 4))
    }

    @Test
    fun `alignDownToFrame does not divide by zero when bytesPerFrame is non-positive`() {
        assertEquals(9, MediaCodecShareAudioTranscoder.alignDownToFrame(chunkSize = 9, bytesPerFrame = 0))
        assertEquals(9, MediaCodecShareAudioTranscoder.alignDownToFrame(chunkSize = 9, bytesPerFrame = -1))
    }

    // ---- partFileFor (U4 review fix #1: write-to-temp, atomic-rename) ----

    @Test
    fun `partFileFor appends a part suffix to the same directory`() {
        val outFile = tempDir.resolve("share-prep").resolve("abc-123.m4a").toFile()

        val partFile = ShareAudioTranscoder.partFileFor(outFile)

        assertEquals(outFile.parentFile, partFile.parentFile)
        assertEquals("abc-123.m4a.part", partFile.name)
    }

    // ---- MediaMuxer construction (real builder, house rule) ----

    @Test
    fun `createMuxer constructs a real MPEG_4 muxer against the production path function`() {
        val outFile = tempDir.resolve("out.m4a").toFile()

        val muxer = MediaCodecShareAudioTranscoder.createMuxer(outFile)
        try {
            assertTrue("MediaMuxer constructor should have created the output file", outFile.exists())
        } finally {
            muxer.release()
        }
    }

    // ---- WAV header parsing: valid fixture (via the real WavWriter) ----

    @Test
    fun `parse reads sample rate channels bit depth and data span from a real WavWriter fixture`() {
        val path = tempDir.resolve("valid.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)
        writer.openForWriting()
        writer.append(shortArrayOf(1, 2, 3, 4, 5), count = 5)
        val dataBytes = writer.closeAndPatchHeader()

        val info = WavPcmHeaderParser.parse(path.toFile())

        assertEquals(16_000, info.sampleRateHz)
        assertEquals(1, info.channelCount)
        assertEquals(16, info.bitsPerSample)
        assertEquals(44L, info.dataOffset)
        assertEquals(dataBytes, info.dataSize)
        assertEquals(10L, info.dataSize) // 5 shorts * 2 bytes
    }

    @Test
    fun `parse on an empty (zero-sample) WavWriter fixture reports zero data size`() {
        val path = tempDir.resolve("empty.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)
        writer.openForWriting()
        writer.closeAndPatchHeader()

        val info = WavPcmHeaderParser.parse(path.toFile())

        assertEquals(0L, info.dataSize)
        assertEquals(44L, info.dataOffset)
    }

    // ---- WAV header parsing: malformed fixtures (hand-rolled bytes) ----

    @Test
    fun `parse rejects a file with bad RIFF magic`() {
        val path = tempDir.resolve("bad-magic.wav")
        val bytes = validHeaderBytes(dataBytes = 4)
        bytes[0] = 'X'.code.toByte() // corrupt "RIFF" -> "XIFF"
        Files.write(path, bytes)

        assertThrowsInvalidFormat { WavPcmHeaderParser.parse(path.toFile()) }
    }

    @Test
    fun `parse rejects a non-PCM format code`() {
        val path = tempDir.resolve("non-pcm.wav")
        val bytes = validHeaderBytes(dataBytes = 4)
        // audioFormat field is at offset 20, little-endian int16. 3 = IEEE float.
        bytes[20] = 3
        bytes[21] = 0
        Files.write(path, bytes)

        assertThrowsInvalidFormat { WavPcmHeaderParser.parse(path.toFile()) }
    }

    @Test
    fun `parse rejects a truncated data chunk`() {
        val path = tempDir.resolve("truncated.wav")
        // Header declares 1000 bytes of data but the file only has 4.
        val bytes = validHeaderBytes(dataBytes = 1000).copyOfRange(0, 44 + 4)
        Files.write(path, bytes)

        assertThrowsInvalidFormat { WavPcmHeaderParser.parse(path.toFile()) }
    }

    @Test
    fun `parse rejects a file too short to contain a RIFF header`() {
        val path = tempDir.resolve("too-short.wav")
        Files.write(path, ByteArray(8))

        assertThrowsInvalidFormat { WavPcmHeaderParser.parse(path.toFile()) }
    }

    // ---- transcode(): malformed WAV never reaches the codec, so this runs end to end ----

    @Test
    fun `transcode on a malformed WAV returns failure and leaves no output file`() = runBlocking {
        val wavFile = tempDir.resolve("bad.wav").toFile()
        wavFile.writeBytes(ByteArray(4)) // nowhere near a valid RIFF header
        val outFile = tempDir.resolve("share-prep").resolve("out.m4a").toFile()
        val transcoder = MediaCodecShareAudioTranscoder()

        val result = transcoder.transcode(wavFile, outFile)

        assertTrue(result.isFailure)
        assertFalse(outFile.exists())
    }

    // ---- transcode(): a zero-data WAV never reaches the codec either (U4 review fix #5) ----

    @Test
    fun `transcode on a zero-data WAV returns failure and leaves no output file`() = runBlocking {
        val path = tempDir.resolve("zero-data.wav")
        val writer = WavWriter(path, sampleRateHz = 16_000)
        writer.openForWriting()
        writer.closeAndPatchHeader()
        val outFile = tempDir.resolve("share-prep").resolve("out.m4a").toFile()
        val transcoder = MediaCodecShareAudioTranscoder()

        val result = transcoder.transcode(path.toFile(), outFile)

        assertTrue(result.isFailure)
        assertFalse(outFile.exists())
        assertFalse(
            "the .part temp file must not be left behind either",
            ShareAudioTranscoder.partFileFor(outFile).exists(),
        )
    }

    private fun assertThrowsInvalidFormat(block: () -> Unit) {
        try {
            block()
            fail("expected InvalidWavFormatException")
        } catch (_: InvalidWavFormatException) {
            // expected
        }
    }

    /** Hand-rolled canonical 44-byte PCM header + [dataBytes] zero bytes, mirroring [WavWriter]'s layout. */
    private fun validHeaderBytes(dataBytes: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(16_000)
            putInt(32_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
        return header + ByteArray(dataBytes)
    }
}
