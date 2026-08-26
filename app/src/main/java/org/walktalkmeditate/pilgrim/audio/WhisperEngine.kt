// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.nio.file.Path

interface WhisperEngine {
    suspend fun transcribe(wavPath: Path): Result<TranscriptionResult>

    /**
     * Additive (Phase 20 U5): the same decode as [transcribe], but with
     * [TranscriptionResult.segments] populated so callers can compute
     * Thought Threads' hallucination-flag ranges from per-segment
     * `compressionRatio`/`noSpeechProb` signals. Defaults to delegating
     * to [transcribe] with no segments, so an engine (or test fake) that
     * never produces segment-level signals — or simply hasn't overridden
     * this — degrades to that baseline instead of being forced to
     * implement a new method it has no data for.
     */
    suspend fun transcribeWithSegments(wavPath: Path): Result<TranscriptionResult> = transcribe(wavPath)

    /**
     * Release the loaded model's native memory (~75 MB tiny / ~150 MB
     * base) so it doesn't stay resident after a transcription batch. A
     * no-op when no model is loaded. Implementations must serialize this
     * against [transcribe] so the context is never freed mid-inference.
     * The next [transcribe] lazily reloads.
     */
    fun unloadModel()
}

/**
 * One decoded speech segment. [t0Ms]/[t1Ms] are milliseconds from the
 * start of the audio; [noSpeechProb] is whisper.cpp's own
 * `whisper_full_get_segment_no_speech_prob` (a REAL signal on Android —
 * unlike WhisperKit 0.16 on iOS, which hardcodes it to 0). whisper.cpp's
 * public API has no per-segment `compression_ratio` getter, so that half
 * of the flag test is computed Android-side from [text] via
 * [org.walktalkmeditate.pilgrim.core.threads.CompressionRatio].
 */
data class WhisperSegment(
    val text: String,
    val t0Ms: Long,
    val t1Ms: Long,
    val noSpeechProb: Float,
)

data class TranscriptionResult(
    val text: String,
    val wordsPerMinute: Double?,
    val segments: List<WhisperSegment> = emptyList(),
)

sealed class WhisperError : Exception() {
    data class ModelLoadFailed(override val cause: Throwable? = null) : WhisperError()
    data class AudioReadFailed(override val cause: Throwable? = null) : WhisperError()
    data class InferenceFailed(val nativeCode: Int) : WhisperError() {
        override val message: String = "whisper inference failed (code=$nativeCode)"
    }
}
