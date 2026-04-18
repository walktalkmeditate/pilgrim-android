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

    /**
     * @param id monotonic counter assigned by the emitter (WalkViewModel).
     *           Two errors with identical [message] and [kind] but different
     *           [id]s are NOT equal, which is intentional: a Compose
     *           `LaunchedEffect(error)` keyed on this state needs to
     *           re-fire when the same logical error repeats — otherwise
     *           the auto-dismiss timer wouldn't reset for back-to-back
     *           PermissionDenied errors landing within the dismiss window.
     */
    data class Error(val message: String, val kind: Kind, val id: Long) : VoiceRecorderUiState()

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
