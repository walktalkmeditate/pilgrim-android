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
    /** When set, [transcribeWithSegments] returns these instead of the
     * single-segment fallback built from [resultText]. */
    var resultSegments: List<WhisperSegment>? = null,
) : WhisperEngine {

    val transcribeCalls: MutableList<Path> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var unloadModelCalls: Int = 0
        private set

    override suspend fun transcribe(wavPath: Path): Result<TranscriptionResult> {
        transcribeCalls.add(wavPath)
        if (delayMs > 0) delay(delayMs)
        failure?.let { return Result.failure(it) }
        return Result.success(TranscriptionResult(text = resultText, wordsPerMinute = null))
    }

    override suspend fun transcribeWithSegments(wavPath: Path): Result<TranscriptionResult> {
        transcribeCalls.add(wavPath)
        if (delayMs > 0) delay(delayMs)
        failure?.let { return Result.failure(it) }
        val segments = resultSegments
            ?: listOf(WhisperSegment(text = resultText, t0Ms = 0L, t1Ms = 0L, noSpeechProb = 0f))
        val text = segments.joinToString("") { it.text }
        return Result.success(TranscriptionResult(text = text, wordsPerMinute = null, segments = segments))
    }

    override fun unloadModel() {
        unloadModelCalls++
    }
}
