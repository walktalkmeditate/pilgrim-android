// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.nio.file.Path

interface WhisperEngine {
    suspend fun transcribe(wavPath: Path): Result<TranscriptionResult>

    /**
     * Release the loaded model's native memory (~75 MB) so it doesn't stay
     * resident after a transcription batch. A no-op when no model is
     * loaded. Implementations must serialize this against [transcribe] so
     * the context is never freed mid-inference. The next [transcribe]
     * lazily reloads.
     */
    fun unloadModel()
}

data class TranscriptionResult(
    val text: String,
    val wordsPerMinute: Double?,
)

sealed class WhisperError : Exception() {
    data class ModelLoadFailed(override val cause: Throwable? = null) : WhisperError()
    data class AudioReadFailed(override val cause: Throwable? = null) : WhisperError()
    data class InferenceFailed(val nativeCode: Int) : WhisperError() {
        override val message: String = "whisper inference failed (code=$nativeCode)"
    }
}
