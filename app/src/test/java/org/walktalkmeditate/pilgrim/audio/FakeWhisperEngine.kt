// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.nio.file.Path
import java.util.Collections
import kotlinx.coroutines.delay

/**
 * Test double for [WhisperEngine]. Returns a canned [TranscriptionResult]
 * after an optional delay; can be flipped to return a [Throwable] via
 * [failure] to drive the per-recording-failure path.
 *
 * The real [WhisperCppEngine] cannot be unit-tested — JNI loading needs
 * a device. Stage 2-F's instrumented test exercises the real engine.
 */
class FakeWhisperEngine(
    var resultText: String = "hello world from the fake engine",
    var failure: Throwable? = null,
    var delayMs: Long = 0L,
) : WhisperEngine {

    val transcribeCalls: MutableList<Path> = Collections.synchronizedList(mutableListOf())

    override suspend fun transcribe(wavPath: Path): Result<TranscriptionResult> {
        transcribeCalls.add(wavPath)
        if (delayMs > 0) delay(delayMs)
        failure?.let { return Result.failure(it) }
        return Result.success(TranscriptionResult(text = resultText, wordsPerMinute = null))
    }
}
