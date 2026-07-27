// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelState
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelVariant
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Render + interaction tests for [ModelDownloadSheetContent] — the
 * stateless inner surface of [ModelDownloadSheet] (U11 spec section 4).
 * The ModalBottomSheet wrapper is not composed here (Robolectric window
 * flakiness); [ModelDownloadViewModelTest] covers the VM plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelDownloadSheetContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        modelState: WhisperModelState = WhisperModelState.Enqueued,
        cellularOverride: Boolean = false,
        dataSaverRestricted: Boolean = false,
        onToggleCellularOverride: (Boolean) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            PilgrimTheme {
                ModelDownloadSheetContent(
                    modelState = modelState,
                    cellularOverride = cellularOverride,
                    dataSaverRestricted = dataSaverRestricted,
                    onToggleCellularOverride = onToggleCellularOverride,
                    onRetry = onRetry,
                )
            }
        }
    }

    @Test
    fun `always shows title and the one-time download size`() {
        render()
        composeRule.onNodeWithText("Transcription Model").assertExists()
        composeRule.onNodeWithText("One-time download • about 148 MB").assertExists()
    }

    @Test
    fun `downloading shows live byte progress`() {
        render(
            modelState = WhisperModelState.Downloading(
                bytesDownloaded = 42_000_000L,
                totalBytes = 148_000_000L,
            ),
        )
        composeRule.onNodeWithText("Downloading — 42 of 148 MB").assertExists()
    }

    @Test
    fun `waiting unmetered shows the wifi explanation`() {
        render(modelState = WhisperModelState.WaitingUnmetered)
        composeRule
            .onNodeWithText(
                "The download waits for an unmetered Wi-Fi connection. " +
                    "Turn on Use mobile data to download now.",
            )
            .assertExists()
    }

    @Test
    fun `use mobile data toggle fires the callback with the flipped value`() {
        var lastValue: Boolean? = null
        render(cellularOverride = false, onToggleCellularOverride = { lastValue = it })
        composeRule.onAllNodes(isToggleable())[0].performClick()
        composeRule.runOnIdle { assertEquals(true, lastValue) }
    }

    @Test
    fun `failed checksum shows body and retry fires`() {
        var retries = 0
        render(modelState = WhisperModelState.FailedChecksum, onRetry = { retries++ })
        composeRule
            .onNodeWithText("The downloaded file did not verify. Retry the download.")
            .assertExists()
        composeRule.onNodeWithText("Retry download").performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `failed storage carries the free-space copy`() {
        render(modelState = WhisperModelState.FailedStorage)
        composeRule
            .onNodeWithText(
                "Not enough free space to finish the download. " +
                    "Free up about 148 MB and retry.",
            )
            .assertExists()
        composeRule.onNodeWithText("Retry download").assertExists()
    }

    @Test
    fun `data saver note shows only when the probe restricts background data`() {
        render(dataSaverRestricted = true)
        composeRule
            .onNodeWithText(
                "Data Saver is on. Background downloads over mobile data " +
                    "may pause until you open the app.",
            )
            .assertExists()
    }

    @Test
    fun `data saver note hidden when unrestricted`() {
        render(dataSaverRestricted = false)
        composeRule
            .onNodeWithText("Data Saver is on.", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `ready base reads model ready and legacy tiny still reads waiting`() {
        render(modelState = WhisperModelState.Ready(WhisperModelVariant.Base))
        composeRule.onNodeWithText("Model ready").assertExists()
    }

    @Test
    fun `legacy tiny reads waiting to download`() {
        render(modelState = WhisperModelState.Ready(WhisperModelVariant.LegacyTiny))
        composeRule.onNodeWithText("Waiting to download").assertExists()
    }
}
