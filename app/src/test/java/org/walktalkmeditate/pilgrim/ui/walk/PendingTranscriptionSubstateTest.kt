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
 * a mapper edit cannot silently reroute a cell. The pref-OFF cells key
 * on the store's usability probe (verified base OR transitional tiny
 * on disk), not the Ready display state: usable → ManualPending with
 * the affordance enabled, not usable → ManualPreparing carrying the
 * delivery state (v1.3.0 QA fix — no mute disabled chip).
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
            pendingTranscriptionSubstate(
                autoTranscribe = false,
                modelState = readyBase,
                modelUsable = true,
            ),
        )
        assertEquals(
            PendingTranscriptionSubstate.ManualPending(transcribeEnabled = true),
            pendingTranscriptionSubstate(
                autoTranscribe = false,
                modelState = readyTiny,
                modelUsable = true,
            ),
        )
    }

    @Test
    fun `pref off with no usable model is manual preparing carrying the delivery state`() {
        val nonReady = deliveryPhases +
            listOf(WhisperModelState.FailedChecksum, WhisperModelState.FailedStorage)
        for (state in nonReady) {
            assertEquals(
                "state=$state",
                PendingTranscriptionSubstate.ManualPreparing(state),
                pendingTranscriptionSubstate(
                    autoTranscribe = false,
                    modelState = state,
                    modelUsable = false,
                ),
            )
        }
    }

    // The transitional tiny keeps the manual affordance enabled through
    // the whole base download window — usability gates the OFF cells,
    // not the delivery display.
    @Test
    fun `pref off with the tiny usable during delivery phases keeps the affordance enabled`() {
        for (state in deliveryPhases) {
            assertEquals(
                "state=$state",
                PendingTranscriptionSubstate.ManualPending(transcribeEnabled = true),
                pendingTranscriptionSubstate(
                    autoTranscribe = false,
                    modelState = state,
                    modelUsable = true,
                ),
            )
        }
    }

    @Test
    fun `pref on with delivery-phase states waits on the download carrying the state`() {
        for (state in deliveryPhases) {
            assertEquals(
                "state=$state",
                PendingTranscriptionSubstate.WaitingOnDownload(state),
                pendingTranscriptionSubstate(
                    autoTranscribe = true,
                    modelState = state,
                    modelUsable = false,
                ),
            )
        }
    }

    // Usability never rewrites the pref-ON display cells: the tiny may
    // be serving the engine, but the row still explains the delivery.
    @Test
    fun `pref on with the tiny usable still shows the delivery phases`() {
        for (state in deliveryPhases) {
            assertEquals(
                "state=$state",
                PendingTranscriptionSubstate.WaitingOnDownload(state),
                pendingTranscriptionSubstate(
                    autoTranscribe = true,
                    modelState = state,
                    modelUsable = true,
                ),
            )
        }
    }

    @Test
    fun `pref on with model ready is queued for processing for both variants`() {
        assertEquals(
            PendingTranscriptionSubstate.QueuedForProcessing,
            pendingTranscriptionSubstate(
                autoTranscribe = true,
                modelState = readyBase,
                modelUsable = true,
            ),
        )
        assertEquals(
            PendingTranscriptionSubstate.QueuedForProcessing,
            pendingTranscriptionSubstate(
                autoTranscribe = true,
                modelState = readyTiny,
                modelUsable = true,
            ),
        )
    }

    // --- U6: battery-skip honesty (a skipped recording must never claim
    // "Queued for processing" — extend the substate mapper instead of
    // reusing QueuedForProcessing) ---------------------------------------

    @Test
    fun `pref on, model ready, skipped for battery - shows SkippedForBattery not QueuedForProcessing`() {
        assertEquals(
            PendingTranscriptionSubstate.SkippedForBattery(transcribeEnabled = true),
            pendingTranscriptionSubstate(
                autoTranscribe = true,
                modelState = readyBase,
                modelUsable = true,
                isSkippedForBattery = true,
            ),
        )
    }

    @Test
    fun `pref on, model ready, NOT skipped - still QueuedForProcessing (default param preserved)`() {
        assertEquals(
            PendingTranscriptionSubstate.QueuedForProcessing,
            pendingTranscriptionSubstate(
                autoTranscribe = true,
                modelState = readyBase,
                modelUsable = true,
            ),
        )
    }

    @Test
    fun `skip flag never overrides a delivery-phase state - model not ready yet takes priority`() {
        for (state in deliveryPhases) {
            assertEquals(
                "state=$state",
                PendingTranscriptionSubstate.WaitingOnDownload(state),
                pendingTranscriptionSubstate(
                    autoTranscribe = true,
                    modelState = state,
                    modelUsable = false,
                    isSkippedForBattery = true,
                ),
            )
        }
    }

    @Test
    fun `skip flag never overrides the pref-OFF manual state - already honest, already actionable`() {
        assertEquals(
            PendingTranscriptionSubstate.ManualPending(transcribeEnabled = true),
            pendingTranscriptionSubstate(
                autoTranscribe = false,
                modelState = readyBase,
                modelUsable = true,
                isSkippedForBattery = true,
            ),
        )
    }

    @Test
    fun `pref on maps the two terminals to their actionable substates`() {
        assertEquals(
            PendingTranscriptionSubstate.DownloadFailedChecksum,
            pendingTranscriptionSubstate(
                autoTranscribe = true,
                modelState = WhisperModelState.FailedChecksum,
                modelUsable = false,
            ),
        )
        assertEquals(
            PendingTranscriptionSubstate.DownloadFailedStorage,
            pendingTranscriptionSubstate(
                autoTranscribe = true,
                modelState = WhisperModelState.FailedStorage,
                modelUsable = false,
            ),
        )
    }
}
