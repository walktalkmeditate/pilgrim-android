// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Decodes a 16-bit PCM mono WAV file and downsamples to [barCount] RMS-normalized
 * magnitudes in [0, 1]. Used by [WaveformBar] to render the seek bar.
 *
 * VoiceRecorder writes 16 kHz mono 16-bit PCM in WAV — this decoder targets that
 * exact format. Other formats fall back to a flat-line FloatArray.
 *
 * Streams the PCM samples via [RandomAccessFile] + a fixed 8 KB read buffer,
 * computing RMS per bucket on-the-fly. Cap on transient RAM is the buffer size
 * (8 KB) regardless of input WAV size — a 45-min mono 16 kHz 16-bit recording
 * (~86 MB raw + ~86 MB ShortArray under the old `readBytes()` path) used to OOM
 * on budget devices; the streaming path holds neither the decompressed PCM nor a
 * second sample array in memory.
 */
object WaveformLoader {
    private const val READ_BUFFER_BYTES = 8 * 1024

    fun load(file: File, barCount: Int): FloatArray {
        if (!file.exists() || file.length() < 44) return FloatArray(barCount)
        var raf: RandomAccessFile? = null
        return try {
            raf = RandomAccessFile(file, "r")
            val header = parseHeader(raf) ?: return FloatArray(barCount)
            streamRms(raf, header, barCount)
        } catch (t: Throwable) {
            FloatArray(barCount)
        } finally {
            try {
                raf?.close()
            } catch (_: Throwable) {
                // Ignore — best-effort close on the read-only handle.
            }
        }
    }

    /**
     * Walk the RIFF header chunk-by-chunk and return the data chunk's offset +
     * size. Validates audioFormat=PCM, channels=1, bitsPerSample=16; returns null
     * for any other format (caller falls back to a flat-line FloatArray).
     */
    private fun parseHeader(raf: RandomAccessFile): WavHeader? {
        val fileLen = raf.length()
        if (fileLen < 12) return null
        val riff = ByteArray(12)
        raf.seek(0)
        raf.readFully(riff)
        if (riff[0] != 'R'.code.toByte() || riff[1] != 'I'.code.toByte() ||
            riff[2] != 'F'.code.toByte() || riff[3] != 'F'.code.toByte()
        ) return null
        if (riff[8] != 'W'.code.toByte() || riff[9] != 'A'.code.toByte() ||
            riff[10] != 'V'.code.toByte() || riff[11] != 'E'.code.toByte()
        ) return null

        var bitsPerSample = -1
        var channels = -1
        var dataOffset = -1L
        var dataSize = -1L
        val chunkHeader = ByteArray(8)
        var pos = 12L
        while (pos + 8 <= fileLen) {
            raf.seek(pos)
            raf.readFully(chunkHeader)
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
                .toLong() and 0xFFFFFFFFL
            when (chunkId) {
                "fmt " -> {
                    val fmt = ByteArray(minOf(chunkSize, 16L).toInt())
                    raf.seek(pos + 8)
                    raf.readFully(fmt)
                    val fmtBuf = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    val audioFormat = fmtBuf.getShort(0).toInt()
                    if (audioFormat != 1) return null
                    channels = fmtBuf.getShort(2).toInt()
                    bitsPerSample = fmtBuf.getShort(14).toInt()
                }
                "data" -> {
                    dataOffset = pos + 8
                    dataSize = chunkSize
                    break
                }
            }
            pos += 8L + chunkSize
        }
        if (dataOffset < 0 || bitsPerSample != 16 || channels != 1) return null
        // Clamp dataSize to remaining bytes — guards a corrupt size header
        // that points past EOF.
        val available = fileLen - dataOffset
        val effectiveSize = if (dataSize > available) available else dataSize
        if (effectiveSize <= 0) return null
        return WavHeader(dataOffset = dataOffset, dataSize = effectiveSize)
    }

    private fun streamRms(
        raf: RandomAccessFile,
        header: WavHeader,
        barCount: Int,
    ): FloatArray {
        val totalSampleCount = header.dataSize / 2L
        if (totalSampleCount <= 0L || barCount <= 0) return FloatArray(barCount)
        val out = FloatArray(barCount)
        val readBuf = ByteArray(READ_BUFFER_BYTES)
        for (bar in 0 until barCount) {
            val barStartSample = bar.toLong() * totalSampleCount / barCount
            val barEndSample = (bar + 1).toLong() * totalSampleCount / barCount
            val samplesInBar = (barEndSample - barStartSample).toInt()
            if (samplesInBar <= 0) continue
            raf.seek(header.dataOffset + barStartSample * 2L)
            var sumSq = 0.0
            var samplesRead = 0
            while (samplesRead < samplesInBar) {
                val bytesNeeded = (samplesInBar - samplesRead) * 2
                val toRead = minOf(readBuf.size, bytesNeeded)
                val n = raf.read(readBuf, 0, toRead)
                if (n <= 0) break
                val evenN = n - (n and 1)
                if (evenN <= 0) break
                val bb = ByteBuffer.wrap(readBuf, 0, evenN).order(ByteOrder.LITTLE_ENDIAN)
                val shortsInRead = evenN / 2
                for (j in 0 until shortsInRead) {
                    val s = bb.short.toDouble() / Short.MAX_VALUE
                    sumSq += s * s
                }
                samplesRead += shortsInRead
            }
            out[bar] = if (samplesRead > 0) {
                sqrt(sumSq / samplesRead).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
        }
        return out
    }

    private data class WavHeader(val dataOffset: Long, val dataSize: Long)
}
