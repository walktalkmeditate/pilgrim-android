// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decodes a 16-bit PCM mono WAV file and downsamples to [barCount] RMS-normalized
 * magnitudes in [0, 1]. Used by [WaveformBar] to render the seek bar.
 *
 * VoiceRecorder writes 16 kHz mono 16-bit PCM in WAV — this decoder targets that
 * exact format. Other formats fall back to a flat-line FloatArray.
 */
object WaveformLoader {
    fun load(file: File, barCount: Int): FloatArray {
        if (!file.exists() || file.length() < 44) return FloatArray(barCount)
        return try {
            val bytes = file.readBytes()
            val pcm = parsePcm(bytes) ?: return FloatArray(barCount)
            downsampleRms(pcm, barCount)
        } catch (t: Throwable) {
            FloatArray(barCount)
        }
    }

    /** Parse RIFF/WAVE header; return PCM samples as ShortArray, or null on malformed input. */
    private fun parsePcm(bytes: ByteArray): ShortArray? {
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF chunk
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()) return null
        if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
            bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()) return null
        // Walk chunks looking for "fmt " then "data".
        var pos = 12
        var bitsPerSample = -1
        var channels = -1
        var dataOffset = -1
        var dataSize = -1
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = bb.getInt(pos + 4)
            when (chunkId) {
                "fmt " -> {
                    val audioFormat = bb.getShort(pos + 8).toInt()
                    if (audioFormat != 1) return null   // 1 = PCM
                    channels = bb.getShort(pos + 10).toInt()
                    bitsPerSample = bb.getShort(pos + 22).toInt()
                }
                "data" -> {
                    dataOffset = pos + 8
                    dataSize = chunkSize
                    break
                }
            }
            pos += 8 + chunkSize
        }
        if (dataOffset < 0 || bitsPerSample != 16 || channels != 1) return null
        val sampleCount = dataSize / 2
        val out = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            out[i] = bb.getShort(dataOffset + i * 2)
        }
        return out
    }

    /** RMS-bucket a ShortArray PCM stream into [barCount] normalized magnitudes. */
    private fun downsampleRms(pcm: ShortArray, barCount: Int): FloatArray {
        if (pcm.isEmpty()) return FloatArray(barCount)
        val out = FloatArray(barCount)
        val perBucket = pcm.size.toDouble() / barCount
        for (i in 0 until barCount) {
            val start = (i * perBucket).toInt()
            val end = ((i + 1) * perBucket).toInt().coerceAtMost(pcm.size)
            if (end <= start) continue
            var sumSq = 0.0
            for (j in start until end) {
                val s = pcm[j].toDouble() / Short.MAX_VALUE
                sumSq += s * s
            }
            val rms = sqrt(sumSq / (end - start))
            out[i] = rms.toFloat().coerceIn(0f, 1f)
        }
        return out
    }
}
