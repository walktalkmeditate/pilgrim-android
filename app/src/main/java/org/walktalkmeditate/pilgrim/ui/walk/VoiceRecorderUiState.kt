// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

/**
 * UI-layer representation of the VoiceRecorder state machine. The
 * Compose layer switches on this to render the record button color /
 * label and the optional error banner.
 */
sealed class VoiceRecorderUiState {
    data object Idle : VoiceRecorderUiState()
    data object Recording : VoiceRecorderUiState()
    data class Error(val message: String, val kind: Kind) : VoiceRecorderUiState()

    enum class Kind {
        /** RECORD_AUDIO not granted at tap time. */
        PermissionDenied,
        /** AudioRecord failed to initialize (mic busy, OEM quirk). */
        CaptureInitFailed,
        /** User tapped stop before any PCM was captured. Silent path — do not banner. */
        Cancelled,
        /** Anything else (FS error, Room insert failure, concurrent state). */
        Other,
    }
}
