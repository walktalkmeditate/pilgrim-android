// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelState
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelVariant

/**
 * Exhaustive cell-by-cell pin of the U11 substate matrix
 * (`docs/parity/2026-07-26-port-download-ux-u11.md` section 3): every
 * pref x model-state combination, so a new [WhisperModelState] case or
 * a mapper edit cannot silently reroute a cell.
 */
@RunWith(JUnit4::class)
class PendingTranscriptionSubstateTest {

    private val readyBase = WhisperModelState.Ready(WhisperModelVariant.Base)
    private val readyTiny = WhisperModelState.Ready(WhisperModelVariant.LegacyTiny)
    private val downloading = WhisperModelState.Downloading(
        bytesDownloaded = 42_000_000L,
        totalBytes = 148_000_000L,
    )

    private val deliveryPhases = listOf(
        WhisperModelState.Absent,
        WhisperModelState.Enqueued,
        WhisperModelState.WaitingUnmetered,
        downloading,
        WhisperModelState.Verifying,
    )

    @Test
    fun `pref off with model ready is manual pending with affordance enabled`() {
        assertEquals(
            PendingTranscriptionSubstate.ManualPending(transcribeEnabled = true),
            pendingTranscriptionSubstate(autoTranscribe = false, modelState = readyBase),
        )
        assertEquals(
            PendingTranscriptionSubstate.ManualPending(transcribeEnabled = true),
            pendingTranscriptionSubstate(autoTranscribe = false, modelState = readyTiny),
        )
    }

    @Test
    fun `pref off with any non-ready state is manual pending with affordance disabled`() {
        val nonReady = deliveryPhases +
            listOf(WhisperModelState.FailedChecksum, WhisperModelState.FailedStorage)
        for (state in nonReady) {
            assertEquals(
                "state=$state",
                PendingTranscriptionSubstate.ManualPending(transcribeEnabled = false),
                pendingTranscriptionSubstate(autoTranscribe = false, modelState = state),
            )
        }
    }

    @Test
    fun `pref on with delivery-phase states waits on the download carrying the state`() {
        for (state in deliveryPhases) {
            assertEquals(
                "state=$state",
                PendingTranscriptionSubstate.WaitingOnDownload(state),
                pendingTranscriptionSubstate(autoTranscribe = true, modelState = state),
            )
        }
    }

    @Test
    fun `pref on with model ready is queued for processing for both variants`() {
        assertEquals(
            PendingTranscriptionSubstate.QueuedForProcessing,
            pendingTranscriptionSubstate(autoTranscribe = true, modelState = readyBase),
        )
        assertEquals(
            PendingTranscriptionSubstate.QueuedForProcessing,
            pendingTranscriptionSubstate(autoTranscribe = true, modelState = readyTiny),
        )
    }

    @Test
    fun `pref on maps the two terminals to their actionable substates`() {
        assertEquals(
            PendingTranscriptionSubstate.DownloadFailedChecksum,
            pendingTranscriptionSubstate(
                autoTranscribe = true,
                modelState = WhisperModelState.FailedChecksum,
            ),
        )
        assertEquals(
            PendingTranscriptionSubstate.DownloadFailedStorage,
            pendingTranscriptionSubstate(
                autoTranscribe = true,
                modelState = WhisperModelState.FailedStorage,
            ),
        )
    }
}
