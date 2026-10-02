// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A voice's bars, iOS parity `WaveformGenerator.swift@db4196e`: each bar
 * its stretch's peak magnitude, normalized so the loudest bar reads 1.0.
 *
 * Reads the recorder's one WAV format (16-bit PCM; channel count and bit
 * depth are trusted, the chunks walked to find `data`), and decodes
 * anything else, a shared Way's `.m4a` above all, through the platform's
 * [MediaExtractor] and [MediaCodec] into the same binning.
 *
 * Returned floats are in [0.0, 1.0]. Returns null on read failure
 * (empty file, malformed header, truncated data chunk, an undecodable
 * file).
 */
object WaveformGenerator {

    private const val TAG = "WaveformGenerator"
    private const val BAR_COUNT = 50
    private const val READ_BUFFER_BYTES = 8 * 1024
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val MICROS_PER_SECOND = 1_000_000L

    /** A decoder that never signals its end, past its input's, gives up after about a second. */
    private const val MAX_IDLE_POLLS_AFTER_INPUT = 100

    suspend fun generate(file: File, bars: Int = BAR_COUNT): FloatArray? =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext null
                if (isWav(file)) wavBars(file, bars) else decodedBars(file, bars)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Structured concurrency: a cancelled load must not be
                // observed as a decode failure. Without this re-throw,
                // the catch (Throwable) below would swallow CE and the
                // caller (WaveformCache) would store EMPTY_SENTINEL,
                // permanently poisoning the cache for this recording.
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to decode waveform (${t::class.simpleName})")
                null
            }
        }

    private fun isWav(file: File): Boolean {
        if (file.length() < 12L) return false
        val header = ByteArray(12)
        RandomAccessFile(file, "r").use { if (it.read(header) != 12) return false }
        return String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE"
    }

    private fun wavBars(file: File, bars: Int): FloatArray? {
        if (file.length() < 44L) return null
        RandomAccessFile(file, "r").use { raf ->
            val (dataOffset, dataLength) = locateDataChunk(raf) ?: return null
            if (dataLength < 2L) return null
            val sampleCount = dataLength / 2L
            val bins = PeakBins(bars, totalFrames = sampleCount)
            raf.seek(dataOffset)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var left = sampleCount * 2
            while (left > 0 && !bins.isFull) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read <= 0) break
                val samples = ByteBuffer.wrap(buffer, 0, read - read % 2).order(ByteOrder.LITTLE_ENDIAN)
                while (samples.remaining() >= 2) bins.add(abs(samples.short.toInt()))
                left -= read
            }
            return bins.normalized()
        }
    }

    /**
     * The first audio track, decoded to PCM and binned frame by frame:
     * the bars' length comes from the track's declared duration, so a
     * file that declares none yields no bars.
     */
    private suspend fun decodedBars(file: File, bars: Int): FloatArray? {
        val extractor = MediaExtractor()
        try {
            // By descriptor: the framework logs a path it fails to open, and a shared Way's carries its share id.
            FileInputStream(file).use { extractor.setDataSource(it.fd) }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (!format.containsKey(MediaFormat.KEY_DURATION) || !format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) return null
            val totalFrames = format.getLong(MediaFormat.KEY_DURATION) * format.getInteger(MediaFormat.KEY_SAMPLE_RATE) /
                MICROS_PER_SECOND
            if (totalFrames <= 0) return null
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val bins = PeakBins(bars, totalFrames)
                drain(extractor, codec, PcmFrames(bins, format))
                return bins.normalized()
            } finally {
                try {
                    codec.stop()
                } catch (_: IllegalStateException) {
                    // Never started; release below still runs.
                }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun drain(extractor: MediaExtractor, codec: MediaCodec, frames: PcmFrames) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idleAfterInput = 0
        while (!frames.bins.isFull && idleAfterInput < MAX_IDLE_POLLS_AFTER_INPUT) {
            kotlin.coroutines.coroutineContext.ensureActive()
            if (!inputDone) {
                val input = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (input >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(input) ?: return, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(input, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val output = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            if (inputDone && output == MediaCodec.INFO_TRY_AGAIN_LATER) idleAfterInput++
            when {
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> frames.format(codec.outputFormat)
                output >= 0 -> {
                    val pcm = codec.getOutputBuffer(output)
                    if (pcm != null && info.size > 0) {
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        frames.add(pcm)
                    }
                    codec.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /**
     * Walk the RIFF chunk list and locate the `data` sub-chunk.
     * Returns its (fileOffset, byteLength). Returns null if the header
     * isn't a recognizable RIFF/WAVE container.
     */
    private fun locateDataChunk(raf: RandomAccessFile): Pair<Long, Long>? {
        val header = ByteArray(12)
        if (raf.read(header) != 12) return null
        if (String(header, 0, 4) != "RIFF") return null
        if (String(header, 8, 4) != "WAVE") return null
        while (raf.filePointer < raf.length() - 8) {
            val chunkHeader = ByteArray(8)
            if (raf.read(chunkHeader) != 8) return null
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (chunkId == "data") {
                return raf.filePointer to chunkSize
            }
            // Skip this chunk's payload. Round odd-sized chunks UP to
            // even (RIFF padding rule).
            val padded = if (chunkSize and 1L == 1L) chunkSize + 1 else chunkSize
            raf.seek(raf.filePointer + padded)
        }
        return null
    }
}

/**
 * iOS's binning: [totalFrames] split into [bars] equal stretches, each bar
 * its stretch's peak magnitude; frames past the last whole stretch are
 * dropped, as the WAV reader always dropped them.
 */
internal class PeakBins(private val bars: Int, totalFrames: Long) {
    private val framesPerBar = (totalFrames / bars).coerceAtLeast(1L)
    private val peaks = IntArray(bars)
    private var frames = 0L

    val isFull: Boolean get() = frames >= framesPerBar * bars

    fun add(magnitude: Int) {
        val bar = frames / framesPerBar
        if (bar < bars && magnitude > peaks[bar.toInt()]) peaks[bar.toInt()] = magnitude
        frames++
    }

    /** Scaled so the loudest bar reads 1; silence stays all zeros. */
    fun normalized(): FloatArray {
        val loudest = peaks.maxOrNull() ?: 0
        if (loudest <= 0) return FloatArray(bars)
        return FloatArray(bars) { (peaks[it].toFloat() / loudest).coerceIn(0f, 1f) }
    }
}

/**
 * A decoder's PCM as frames for [bins]: one magnitude per frame, the
 * loudest of its channels, from 16-bit or float samples, as the decoder's
 * output format says (and says again on a format change).
 */
internal class PcmFrames(val bins: PeakBins, format: MediaFormat) {
    private var channels = 1
    private var float = false

    init {
        format(format)
    }

    fun format(format: MediaFormat) {
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        }
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            float = format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        }
    }

    fun add(pcm: ByteBuffer) {
        val samples = pcm.order(ByteOrder.nativeOrder())
        val frameBytes = channels * (if (float) FLOAT_BYTES else SHORT_BYTES)
        while (samples.remaining() >= frameBytes && !bins.isFull) {
            var loudest = 0
            repeat(channels) {
                val magnitude = if (float) {
                    (abs(samples.float) * Short.MAX_VALUE).toInt().coerceAtMost(-Short.MIN_VALUE.toInt())
                } else {
                    abs(samples.short.toInt())
                }
                if (magnitude > loudest) loudest = magnitude
            }
            bins.add(loudest)
        }
    }

    private companion object {
        const val SHORT_BYTES = 2
        const val FLOAT_BYTES = 4
    }
}
