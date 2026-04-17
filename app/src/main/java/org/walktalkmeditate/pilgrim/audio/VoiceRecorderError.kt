// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

/**
 * Typed failure modes for [VoiceRecorder.start] / [VoiceRecorder.stop].
 * Stage 2-C's UI switches on the concrete subclass to surface a
 * user-appropriate message.
 */
sealed class VoiceRecorderError : Exception() {
    data object PermissionMissing : VoiceRecorderError() {
        private fun readResolve(): Any = PermissionMissing
        override val message: String = "RECORD_AUDIO not granted"
    }

    data object ConcurrentRecording : VoiceRecorderError() {
        private fun readResolve(): Any = ConcurrentRecording
        override val message: String = "a recording is already in progress"
    }

    data object NoActiveRecording : VoiceRecorderError() {
        private fun readResolve(): Any = NoActiveRecording
        override val message: String = "stop() called with no active recording"
    }

    data class AudioCaptureInitFailed(override val cause: Throwable? = null) : VoiceRecorderError() {
        override val message: String = "AudioRecord failed to initialize" +
            (cause?.message?.let { ": $it" } ?: "")
    }

    data class FileSystemError(override val cause: Throwable) : VoiceRecorderError() {
        override val message: String = "failed to create recording file: ${cause.message}"
    }
}
