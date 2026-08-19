// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

/**
 * UI-layer representation of the VoiceRecorder state machine. The
 * Compose layer switches on this to render the record button color /
 * label and the optional error banner.
 */
sealed class VoiceRecorderUiState {
    data object Idle : VoiceRecorderUiState()

    /**
     * @param startedAtMillis epoch wall-clock reading taken when the
     *        capture session opened, straight off the same
     *        [org.walktalkmeditate.pilgrim.domain.Clock] that stamps a
     *        finished recording's `startTimestamp` / `durationMillis`.
     *        The Talk chip adds `now - startedAtMillis` to the completed
     *        sum so it ticks during a talk instead of freezing until the
     *        row lands (iOS `ActiveWalkViewModel.swift:456-458@2ee1185`
     *        reads `voiceRecordingManagement.recordingStartDate` the same
     *        way). Epoch, not `elapsedRealtime`: a completed row's
     *        duration is `endedAt - startedAt` on the epoch clock
     *        (`VoiceRecorder.finalizeSession`), so any other time base
     *        would make the total jump at the live-to-completed seam.
     */
    data class Recording(val startedAtMillis: Long) : VoiceRecorderUiState()

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
        /**
         * The OS reclaimed audio focus mid-recording (incoming call,
         * another capture app). The recording was finalized and the audio
         * captured so far was saved; the banner tells the user it stopped.
         */
        Interrupted,
        /** Anything else (FS error, Room insert failure, concurrent state). */
        Other,
    }
}
