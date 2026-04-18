// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.nio.file.Path

interface WhisperEngine {
    suspend fun transcribe(wavPath: Path): Result<TranscriptionResult>
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
