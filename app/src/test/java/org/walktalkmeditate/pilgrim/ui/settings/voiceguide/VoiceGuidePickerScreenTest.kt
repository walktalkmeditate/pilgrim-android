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
class VoiceGuidePickerScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private fun pack(id: String, name: String = id, tagline: String = "") = VoiceGuidePack(
        id = id, version = "1", name = name, tagline = tagline, description = "",
        theme = "", iconName = "", type = "walk", walkTypes = emptyList(),
        scheduling = PromptDensity(0, 0, 0, 0, 0),
        totalDurationSec = 0.0, totalSizeBytes = 0L,
        prompts = emptyList(),
    )

    @Test fun `empty state shows placeholder copy`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePickerContent(packs = emptyList(), onOpenPack = {})
                }
            }
        }
        composeRule.onNodeWithText("No voice guides yet. Check your connection and pull to refresh.")
            .assertIsDisplayed()
    }

    @Test fun `NotDownloaded pack shows name and tagline`() {
        val state = VoiceGuidePackState.NotDownloaded(
            pack = pack("morning-walk", name = "Morning Walk", tagline = "Begin gently"),
            isSelected = false,
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePickerContent(packs = listOf(state), onOpenPack = {})
                }
            }
        }
        composeRule.onNodeWithText("Morning Walk").assertIsDisplayed()
        composeRule.onNodeWithText("Begin gently").assertIsDisplayed()
        composeRule.onNodeWithText("Not downloaded").assertIsDisplayed()
    }

    @Test fun `Downloading pack shows progress text`() {
        val state = VoiceGuidePackState.Downloading(
            pack = pack("noon-sit", name = "Noon Sit"),
            isSelected = false,
            completed = 2,
            total = 5,
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePickerContent(packs = listOf(state), onOpenPack = {})
                }
            }
        }
        composeRule.onNodeWithText("Downloading 2 of 5").assertIsDisplayed()
    }

    @Test fun `Downloaded selected pack shows Selected label`() {
        val state = VoiceGuidePackState.Downloaded(
            pack = pack("evening-walk", name = "Evening Walk"),
            isSelected = true,
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePickerContent(packs = listOf(state), onOpenPack = {})
                }
            }
        }
        composeRule.onNodeWithText("Evening Walk").assertIsDisplayed()
        composeRule.onNodeWithText("Selected").assertIsDisplayed()
        composeRule.onNodeWithText("Downloaded").assertIsDisplayed()
    }

    @Test fun `Failed pack shows failure status`() {
        val state = VoiceGuidePackState.Failed(
            pack = pack("broken"),
            isSelected = false,
            reason = "download_failed",
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    VoiceGuidePickerContent(packs = listOf(state), onOpenPack = {})
                }
            }
        }
        composeRule.onNodeWithText("Download failed").assertIsDisplayed()
    }
}
