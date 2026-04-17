// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/**
 * Minimal RIFF/WAVE writer for 16-bit mono PCM. Writes a placeholder
 * 44-byte header on [openForWriting], appends little-endian PCM on
 * [append], and patches the two size fields on [closeAndPatchHeader].
 *
 * Not thread-safe — caller is the single-thread VoiceRecorder
 * capture executor.
 */
class WavWriter(
    private val path: Path,
    private val sampleRateHz: Int,
) {
    private var file: RandomAccessFile? = null
    private var dataBytesWritten: Long = 0

    fun openForWriting() {
        check(file == null) { "WavWriter already open for $path" }
        val f = RandomAccessFile(path.toFile(), "rw")
        f.setLength(0)
        f.write(buildHeader(dataSize = 0))
        file = f
        dataBytesWritten = 0
    }

    fun append(samples: ShortArray, count: Int) {
        val f = checkNotNull(file) { "WavWriter not open" }
        require(count in 0..samples.size) { "count=$count out of range [0, ${samples.size}]" }
        if (count == 0) return
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        f.write(bytes.array())
        dataBytesWritten += count * 2L
    }

    /**
     * Patches data-size and chunk-size fields, closes the file, and
     * returns the total number of PCM data bytes written.
     *
     * Throws IllegalStateException if the recording exceeds the 4 GB
     * unsigned-32-bit limit of the RIFF size fields. At 16 kHz mono
     * 16-bit that's ~37 hours in a single file — far beyond any
     * realistic voice-note duration, but a hard ceiling worth naming.
     */
    fun closeAndPatchHeader(): Long {
        val f = checkNotNull(file) { "WavWriter not open" }
        file = null
        val chunkSize = HEADER_BYTES + dataBytesWritten - 8L
        check(dataBytesWritten <= MAX_U32 && chunkSize <= MAX_U32) {
            "WAV data exceeds RIFF 4 GB limit: dataBytes=$dataBytesWritten"
        }
        try {
            f.seek(CHUNK_SIZE_OFFSET.toLong())
            f.write(leU32(chunkSize))
            f.seek(DATA_SIZE_OFFSET.toLong())
            f.write(leU32(dataBytesWritten))
        } finally {
            f.close()
        }
        return dataBytesWritten
    }

    private fun buildHeader(dataSize: Int): ByteArray {
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val chunkSize = HEADER_BYTES + dataSize - 8
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(chunkSize)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)                            // fmt subchunk size for PCM
        b.putShort(1)                           // audio format = PCM
        b.putShort(channels.toShort())
        b.putInt(sampleRateHz)
        b.putInt(byteRate)
        b.putShort(blockAlign.toShort())
        b.putShort(bitsPerSample.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataSize)
        return b.array()
    }

    private fun leInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    /**
     * Write a 32-bit unsigned little-endian int, accepting any Long in
     * [0, 2^32 − 1]. The underlying byte pattern is what the RIFF spec
     * requires; Kotlin's signed Int semantics don't matter once the
     * value's on disk.
     */
    private fun leU32(value: Long): ByteArray {
        val bytes = ByteArray(4)
        bytes[0] = (value and 0xFF).toByte()
        bytes[1] = (value ushr 8 and 0xFF).toByte()
        bytes[2] = (value ushr 16 and 0xFF).toByte()
        bytes[3] = (value ushr 24 and 0xFF).toByte()
        return bytes
    }

    companion object {
        const val HEADER_BYTES = 44
        const val CHUNK_SIZE_OFFSET = 4
        const val DATA_SIZE_OFFSET = 40
        const val MAX_U32 = 0xFFFF_FFFFL
    }
}
