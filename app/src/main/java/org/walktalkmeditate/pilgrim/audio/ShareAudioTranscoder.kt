// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * WAV(PCM) -> AAC-LC transcoder for the interactive-share prep
 * pipeline (Decision 1 of
 * `docs/plans/2026-08-14-001-feat-walk-with-me-interactive-share-plan.md`:
 * mono, 64 kbps, source sample rate, M4A container). Android-original —
 * iOS records AAC directly and never transcodes.
 *
 * Implementations must be cancellation-cooperative between encode
 * buffers and must never leave a partial [File] behind: on failure or
 * cancellation, [outFile] does not exist when [transcode] returns or
 * throws. The stronger invariant callers (notably
 * [org.walktalkmeditate.pilgrim.data.share.SharePrepStore]) rely on:
 * **[outFile] exists if and only if a complete encode produced it.**
 * Implementations satisfy this by writing to [partFileFor]'s sibling
 * temp path and renaming into place only after a fully-successful
 * encode — never writing [outFile] directly.
 */
interface ShareAudioTranscoder {
    /**
     * Encodes [wavFile] (16-bit PCM WAV, as produced by [WavWriter]) to
     * [outFile] (AAC-LC in an MPEG-4/M4A container). On success,
     * [outFile] exists and the result carries its size in bytes.
     */
    suspend fun transcode(wavFile: File, outFile: File): Result<Long>

    companion object {
        /**
         * Sibling temp path used while an encode targeting [outFile] is
         * in progress. The ONE function every writer (real + fake) and
         * reader (the orphan sweep's stray-file guard) uses to agree on
         * where an in-progress artifact lives.
         */
        fun partFileFor(outFile: File): File = File(outFile.parentFile, "${outFile.name}.part")
    }
}

/**
 * [MediaCodec] + [MediaMuxer] implementation. Robolectric cannot drive
 * an actual hardware/software encode loop (R12 of the interactive-share
 * plan) — [MediaCodecShareAudioTranscoderTest] exercises the
 * [MediaFormat]/[MediaMuxer] builder calls and the WAV header parse
 * only; the real encode is device-QA'd in a later unit.
 */
class MediaCodecShareAudioTranscoder @Inject constructor() : ShareAudioTranscoder {

    override suspend fun transcode(wavFile: File, outFile: File): Result<Long> =
        withContext(Dispatchers.IO) {
            val partFile = ShareAudioTranscoder.partFileFor(outFile)
            try {
                val wav = WavPcmHeaderParser.parse(wavFile)
                if (wav.dataSize <= 0L) {
                    throw InvalidWavFormatException("WAV has no PCM data: ${wavFile.name}")
                }
                outFile.parentFile?.mkdirs()
                partFile.delete()
                encode(wav, wavFile, partFile)
                val bytes = partFile.length()
                renameIntoPlace(partFile, outFile)
                Result.success(bytes)
            } catch (ce: CancellationException) {
                partFile.delete()
                throw ce
            } catch (t: Throwable) {
                partFile.delete()
                Result.failure(t)
            }
        }

    /**
     * Same-directory rename, atomic on the common case (POSIX
     * filesystems, which is what Android's cache dir sits on).
     * Mirrors [org.walktalkmeditate.pilgrim.audio.model.WhisperModelDownloadWorker]'s
     * atomic-move-with-fallback idiom.
     */
    private fun renameIntoPlace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun encode(wav: WavPcmInfo, wavFile: File, targetFile: File) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            val muxer = createMuxer(targetFile)
            try {
                codec.configure(
                    buildAacFormat(wav.sampleRateHz, wav.channelCount),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                codec.start()
                RandomAccessFile(wavFile, "r").use { raf ->
                    raf.seek(wav.dataOffset)
                    runEncodeLoop(codec, muxer, raf, wav)
                }
            } finally {
                releaseQuietly(muxer)
            }
        } finally {
            releaseQuietly(codec)
        }
    }

    /**
     * Alternates feeding input PCM and draining encoded output until
     * the codec signals end-of-stream. Cooperative cancellation is
     * checked once per round via [yield] — "between buffers", not
     * mid-buffer, since the underlying [MediaCodec] calls are plain
     * blocking Java calls with no suspension points of their own.
     */
    private suspend fun runEncodeLoop(
        codec: MediaCodec,
        muxer: MediaMuxer,
        raf: RandomAccessFile,
        wav: WavPcmInfo,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false
        var inputDone = false
        var outputDone = false
        var bytesRemaining = wav.dataSize
        var presentationTimeUs = 0L
        val bytesPerFrame = (wav.bitsPerSample / 8) * wav.channelCount

        while (!outputDone) {
            yield()

            if (!inputDone) {
                val inputBufferId = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferId >= 0) {
                    val buffer = codec.getInputBuffer(inputBufferId)!!
                    buffer.clear()
                    val chunkSize = alignDownToFrame(
                        minOf(buffer.remaining().toLong(), bytesRemaining).toInt(),
                        bytesPerFrame,
                    )
                    if (chunkSize <= 0) {
                        codec.queueInputBuffer(
                            inputBufferId, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        val bytes = ByteArray(chunkSize)
                        val n = raf.read(bytes)
                        if (n <= 0) {
                            codec.queueInputBuffer(
                                inputBufferId, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            buffer.put(bytes, 0, n)
                            codec.queueInputBuffer(inputBufferId, 0, n, presentationTimeUs, 0)
                            bytesRemaining -= n
                            presentationTimeUs += (n / bytesPerFrame).toLong() * 1_000_000L / wav.sampleRateHz
                        }
                    }
                }
            }

            var outputBufferId = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            while (outputBufferId != MediaCodec.INFO_TRY_AGAIN_LATER) {
                when {
                    outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "MediaCodec reported an output format change twice" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputBufferId >= 0 -> {
                        val encoded = codec.getOutputBuffer(outputBufferId)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // Codec-config buffers carry no sample data (e.g. AAC's
                            // AudioSpecificConfig) — MediaMuxer must not receive them.
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0) {
                            check(muxerStarted) { "encoder produced sample data before the muxer started" }
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputBufferId, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
                if (outputDone) break
                outputBufferId = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            }
        }
    }

    private fun releaseQuietly(codec: MediaCodec) {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
            // Never reached the executing state (e.g. configure/start
            // threw before this finally ran) — release() below still runs.
        }
        codec.release()
    }

    private fun releaseQuietly(muxer: MediaMuxer) {
        try {
            muxer.stop()
        } catch (_: IllegalStateException) {
            // Never started (e.g. a zero-byte WAV never produced a format
            // change) — release() below still runs.
        }
        muxer.release()
    }

    internal companion object {
        const val BIT_RATE = 64_000
        private const val TIMEOUT_US = 10_000L

        internal fun buildAacFormat(sampleRateHz: Int, channelCount: Int): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }

        internal fun createMuxer(outFile: File): MediaMuxer =
            MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        /**
         * Clamps [chunkSize] down to a whole number of PCM frames so a
         * feed never splits a frame across two input buffers. Returns
         * [chunkSize] unchanged when [bytesPerFrame] is non-positive
         * (guards the modulo below — a degenerate header value should
         * never crash the encode loop with an arithmetic exception).
         */
        internal fun alignDownToFrame(chunkSize: Int, bytesPerFrame: Int): Int {
            if (bytesPerFrame <= 0) return chunkSize
            return chunkSize - (chunkSize % bytesPerFrame)
        }
    }
}

/** Parsed subset of a PCM WAV header needed to drive [MediaCodecShareAudioTranscoder]. */
internal data class WavPcmInfo(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long,
)

internal class InvalidWavFormatException(message: String) : Exception(message)

/**
 * Minimal RIFF/WAVE chunk-walking parser for the linear-PCM WAVs
 * [WavWriter] produces. Validates the RIFF/WAVE magic, walks chunks
 * looking for `fmt ` and `data`, and rejects anything that isn't PCM
 * (format code 1) or whose declared chunk size runs past the end of
 * the file (a truncated recording).
 */
internal object WavPcmHeaderParser {
    private const val PCM_FORMAT_CODE = 1

    fun parse(file: File): WavPcmInfo {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) {
                throw InvalidWavFormatException("file too short for a RIFF header: ${file.name}")
            }
            if (raf.readAscii(4) != "RIFF") {
                throw InvalidWavFormatException("missing RIFF magic in ${file.name}")
            }
            raf.skipBytes(4) // overall RIFF chunk size — unused, "data" carries the size we need
            if (raf.readAscii(4) != "WAVE") {
                throw InvalidWavFormatException("missing WAVE magic in ${file.name}")
            }

            var audioFormat: Int? = null
            var sampleRateHz: Int? = null
            var channelCount: Int? = null
            var bitsPerSample: Int? = null
            var dataOffset: Long? = null
            var dataSize: Long? = null

            while (raf.filePointer + 8 <= raf.length()) {
                val chunkId = raf.readAscii(4)
                val chunkSize = raf.readLeU32()
                val chunkStart = raf.filePointer
                val chunkEnd = chunkStart + chunkSize
                if (chunkEnd > raf.length()) {
                    throw InvalidWavFormatException(
                        "chunk '$chunkId' declares $chunkSize bytes but ${file.name} ends earlier",
                    )
                }
                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize < 16) {
                            throw InvalidWavFormatException("fmt chunk too small in ${file.name}")
                        }
                        audioFormat = raf.readLeU16()
                        channelCount = raf.readLeU16()
                        sampleRateHz = raf.readLeU32().toInt()
                        raf.skipBytes(6) // byteRate(4) + blockAlign(2) — derivable, not needed
                        bitsPerSample = raf.readLeU16()
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        dataSize = chunkSize
                    }
                }
                // RIFF chunks are word-aligned: a one-byte pad follows an
                // odd-sized chunk before the next chunk header.
                raf.seek(chunkEnd + (chunkSize % 2))
            }

            if (audioFormat != PCM_FORMAT_CODE) {
                throw InvalidWavFormatException(
                    "unsupported WAV format code $audioFormat in ${file.name} (only PCM=1 is supported)",
                )
            }
            val sr = sampleRateHz ?: throw InvalidWavFormatException("missing fmt chunk in ${file.name}")
            val ch = channelCount ?: throw InvalidWavFormatException("missing fmt chunk in ${file.name}")
            val bits = bitsPerSample ?: throw InvalidWavFormatException("missing fmt chunk in ${file.name}")
            val offset = dataOffset ?: throw InvalidWavFormatException("missing data chunk in ${file.name}")
            val size = dataSize ?: throw InvalidWavFormatException("missing data chunk in ${file.name}")

            return WavPcmInfo(sr, ch, bits, offset, size)
        }
    }

    private fun RandomAccessFile.readAscii(n: Int): String {
        val bytes = ByteArray(n)
        readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readLeU16(): Int {
        val b0 = read()
        val b1 = read()
        if (b0 < 0 || b1 < 0) throw InvalidWavFormatException("unexpected end of file reading a 16-bit field")
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
    }

    private fun RandomAccessFile.readLeU32(): Long {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            throw InvalidWavFormatException("unexpected end of file reading a 32-bit field")
        }
        return (b0.toLong() and 0xFF) or
            ((b1.toLong() and 0xFF) shl 8) or
            ((b2.toLong() and 0xFF) shl 16) or
            ((b3.toLong() and 0xFF) shl 24)
    }
}
