// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.voiceguide

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.voiceguide.PromptDensity
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePack
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePackState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuidePackDetailScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private fun pack(id: String) = VoiceGuidePack(
        id = id, version = "1", name = "Named Pack",
        tagline = "A tagline", description = "Long description here.",
        theme = "", iconName = "", type = "walk", walkTypes = emptyList(),
        scheduling = PromptDensity(0, 0, 0, 0, 0),
        totalDurationSec = 300.0,       // 5 min
        totalSizeBytes = 2L * 1024 * 1024, // 2.0 MB
        prompts = emptyList(),
    )

    @Test fun `Loading state shows spinner without crashing`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePackDetailContent(
                        uiState = VoiceGuidePackDetailViewModel.UiState.Loading,
                        onDownload = {}, onCancel = {}, onRetry = {},
                        onSelect = {}, onDeselect = {}, onDelete = {},
                    )
                }
            }
        }
        // No assertion text — just verify the content composable renders
        // Loading without throwing.
    }

    @Test fun `NotFound state shows the placeholder copy`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePackDetailContent(
                        uiState = VoiceGuidePackDetailViewModel.UiState.NotFound,
                        onDownload = {}, onCancel = {}, onRetry = {},
                        onSelect = {}, onDeselect = {}, onDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("This pack isn't available.").assertIsDisplayed()
    }

    @Test fun `Loaded NotDownloaded shows Download button`() {
        val state = VoiceGuidePackState.NotDownloaded(pack = pack("p"), isSelected = false)
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePackDetailContent(
                        uiState = VoiceGuidePackDetailViewModel.UiState.Loaded(state),
                        onDownload = {}, onCancel = {}, onRetry = {},
                        onSelect = {}, onDeselect = {}, onDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Named Pack").assertIsDisplayed()
        composeRule.onNodeWithText("Download").assertIsDisplayed()
    }

    @Test fun `Loaded Downloading shows Cancel button`() {
        val state = VoiceGuidePackState.Downloading(
            pack = pack("p"), isSelected = false, completed = 1, total = 4,
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePackDetailContent(
                        uiState = VoiceGuidePackDetailViewModel.UiState.Loaded(state),
                        onDownload = {}, onCancel = {}, onRetry = {},
                        onSelect = {}, onDeselect = {}, onDelete = {},
                    )
                }
            }
        }
        // assertExists — Compose test viewport may scroll these rows
        // off-screen in the narrow test Box; the render-logic invariant
        // we want to check is "node is in composition for this state".
        composeRule.onNodeWithText("Cancel download").assertExists()
        composeRule.onNodeWithText("Downloading 1 of 4").assertExists()
    }

    @Test fun `Loaded Downloaded unselected shows Use as my guide button and Delete`() {
        val state = VoiceGuidePackState.Downloaded(pack = pack("p"), isSelected = false)
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePackDetailContent(
                        uiState = VoiceGuidePackDetailViewModel.UiState.Loaded(state),
                        onDownload = {}, onCancel = {}, onRetry = {},
                        onSelect = {}, onDeselect = {}, onDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Use as my guide").assertExists()
        composeRule.onNodeWithText("Delete download").assertExists()
    }

    @Test fun `Loaded Downloaded selected shows Unset button`() {
        val state = VoiceGuidePackState.Downloaded(pack = pack("p"), isSelected = true)
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePackDetailContent(
                        uiState = VoiceGuidePackDetailViewModel.UiState.Loaded(state),
                        onDownload = {}, onCancel = {}, onRetry = {},
                        onSelect = {}, onDeselect = {}, onDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Unset as guide").assertIsDisplayed()
    }

    @Test fun `Loaded Failed shows Retry button`() {
        val state = VoiceGuidePackState.Failed(
            pack = pack("p"), isSelected = false, reason = "download_failed",
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePackDetailContent(
                        uiState = VoiceGuidePackDetailViewModel.UiState.Loaded(state),
                        onDownload = {}, onCancel = {}, onRetry = {},
                        onSelect = {}, onDeselect = {}, onDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Retry download").assertIsDisplayed()
    }
}
