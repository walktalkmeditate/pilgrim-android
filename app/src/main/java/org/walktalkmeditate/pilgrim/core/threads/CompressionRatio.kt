// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Android's stand-in for whisper.cpp's un-exposed per-segment
 * `compression_ratio` (the public JNI-reachable API has no getter for it —
 * only [WhisperSegment.noSpeechProb] comes straight from the engine).
 * Computed from the segment's own decoded text: UTF-8 byte length over the
 * length of that same byte string deflate-compressed — the same heuristic
 * OpenAI's reference Whisper decoder uses to flag repetitive hallucination,
 * since degenerate looping text ("the the the the...") compresses far
 * better than ordinary prose.
 */
object CompressionRatio {

    fun of(text: String): Double {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return 0.0
        val compressedSize = deflate(bytes).size
        if (compressedSize == 0) return 0.0
        return bytes.size.toDouble() / compressedSize
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val output = ByteArrayOutputStream(bytes.size)
            val buffer = ByteArray(DEFLATE_BUFFER_SIZE)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                output.write(buffer, 0, written)
            }
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private const val DEFLATE_BUFFER_SIZE = 1024
}
