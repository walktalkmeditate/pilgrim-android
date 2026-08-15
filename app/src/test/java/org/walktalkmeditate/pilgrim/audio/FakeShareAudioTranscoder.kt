// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * configured-failure paths delete any partial file before completing —
 * matching [MediaCodecShareAudioTranscoder]'s documented "no partial
 * artifact left behind" contract.
 *
 * Timing honesty: mirrors [MediaCodecShareAudioTranscoder]'s
 * write-to-`.part` + rename-on-success shape. The `.part` twin (via
 * [ShareAudioTranscoder.partFileFor]) is written BEFORE the configured
 * delay, and [outFile] itself only appears at the very end — so a test
 * can genuinely observe "encode started, artifact not yet ready" (the
 * exact window [org.walktalkmeditate.pilgrim.data.share.SharePrepStore]'s
 * single-flight join must cover) instead of the old fake's shortcut of
 * writing [outFile] directly after the delay, which could never
 * exercise that window.
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
        val partFile = ShareAudioTranscoder.partFileFor(outFile)
        try {
            val size = outputBytesFor[wavFile] ?: defaultOutputBytes
            partFile.parentFile?.mkdirs()
            partFile.writeBytes(ByteArray(size))
            delaysMs[wavFile]?.let { delay(it) }
            failures[wavFile]?.let { throw it }
            Files.move(partFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return Result.success(outFile.length())
        } catch (ce: CancellationException) {
            partFile.delete()
            throw ce
        } catch (t: Throwable) {
            partFile.delete()
            return Result.failure(t)
        }
    }
}
