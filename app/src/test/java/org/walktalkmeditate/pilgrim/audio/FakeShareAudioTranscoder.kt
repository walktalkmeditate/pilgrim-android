// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.io.File
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Test double for [ShareAudioTranscoder]. Keyed by the source [wavFile]
 * so a test can give different recordings different delays/outcomes in
 * the same run — [delaysMs] and [failures] are read once per call.
 *
 * Honors the real interface contract: [delay] is a genuine suspend
 * call, so a caller that cancels this coroutine unwinds through here
 * normally (no swallowing), and both the cancellation and the
 * configured-failure paths delete any partial [File] before
 * completing — matching [MediaCodecShareAudioTranscoder]'s documented
 * "no partial artifact left behind" contract.
 */
class FakeShareAudioTranscoder(
    var defaultOutputBytes: Int = 1_024,
) : ShareAudioTranscoder {

    val delaysMs: MutableMap<File, Long> = Collections.synchronizedMap(mutableMapOf())
    val failures: MutableMap<File, Throwable> = Collections.synchronizedMap(mutableMapOf())
    val outputBytesFor: MutableMap<File, Int> = Collections.synchronizedMap(mutableMapOf())
    val calls: MutableList<File> = Collections.synchronizedList(mutableListOf())

    override suspend fun transcode(wavFile: File, outFile: File): Result<Long> {
        calls.add(wavFile)
        try {
            delaysMs[wavFile]?.let { delay(it) }
            failures[wavFile]?.let { throw it }
            val size = outputBytesFor[wavFile] ?: defaultOutputBytes
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(ByteArray(size))
            return Result.success(outFile.length())
        } catch (ce: CancellationException) {
            outFile.delete()
            throw ce
        } catch (t: Throwable) {
            outFile.delete()
            return Result.failure(t)
        }
    }
}
