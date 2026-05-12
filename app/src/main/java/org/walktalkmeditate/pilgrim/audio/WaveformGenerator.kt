// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a 16kHz mono 16-bit PCM WAV file and downsamples it into a
 * fixed-length bar array suitable for [WaveformBarView]. iOS parity
 * `WaveformGenerator.swift@db4196e` — same downsample-by-peak strategy,
 * normalized so the loudest bar reads as 1.0.
 *
 * NOT a general WAV decoder: the recorder writes one canonical format
 * (see [AudioRecordCapture.SAMPLE_RATE_HZ] + `ENCODING_PCM_16BIT`),
 * so we don't bother parsing channel count or bit depth — we trust
 * the format and just walk the chunks to find `data`.
 *
 * Returned floats are in [0.0, 1.0]. Returns null on read failure
 * (empty file, malformed header, truncated data chunk).
 */
object WaveformGenerator {

    private const val TAG = "WaveformGenerator"
    private const val BAR_COUNT = 50

    suspend fun generate(file: File, bars: Int = BAR_COUNT): FloatArray? =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists() || file.length() < 44L) return@withContext null
                RandomAccessFile(file, "r").use { raf ->
                    val (dataOffset, dataLength) = locateDataChunk(raf) ?: return@withContext null
                    if (dataLength < 2L) return@withContext null
                    val sampleCount = (dataLength / 2L).toInt()
                    if (sampleCount <= 0) return@withContext null
                    val samplesPerBar = (sampleCount / bars).coerceAtLeast(1)
                    val out = FloatArray(bars)
                    raf.seek(dataOffset)
                    val byteBuf = ByteArray(samplesPerBar * 2)
                    var peakAcrossAllBars = 0
                    for (b in 0 until bars) {
                        val read = raf.read(byteBuf)
                        if (read <= 0) {
                            // Truncated tail: fill remaining bars with 0 and
                            // bail out — don't crash on partial files.
                            break
                        }
                        val bb = ByteBuffer.wrap(byteBuf, 0, read)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        var peakInWindow = 0
                        while (bb.remaining() >= 2) {
                            val s = bb.short.toInt()
                            val mag = abs(s)
                            if (mag > peakInWindow) peakInWindow = mag
                        }
                        out[b] = peakInWindow.toFloat()
                        if (peakInWindow > peakAcrossAllBars) {
                            peakAcrossAllBars = peakInWindow
                        }
                    }
                    if (peakAcrossAllBars <= 0) return@withContext out
                    val inv = 1.0f / peakAcrossAllBars.toFloat()
                    for (i in out.indices) {
                        out[i] = (out[i] * inv).coerceIn(0f, 1f)
                    }
                    out
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to decode waveform for ${file.name}", t)
                null
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
