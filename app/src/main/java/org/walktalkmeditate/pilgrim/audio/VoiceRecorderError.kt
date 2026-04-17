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

    /**
     * Capture loop terminated without writing any PCM. Happens when the
     * user taps record + stop faster than AudioRecord's first buffer
     * fills, or when Android 14+ silently kills AudioRecord for a
     * backgrounded app (FGS type=location doesn't cover mic access; see
     * the Stage 2-B spec's forward-carry note). Empty .wav is deleted
     * from disk before this error is returned.
     */
    data object EmptyRecording : VoiceRecorderError() {
        private fun readResolve(): Any = EmptyRecording
        override val message: String = "recording ended with no audio captured"
    }
}
