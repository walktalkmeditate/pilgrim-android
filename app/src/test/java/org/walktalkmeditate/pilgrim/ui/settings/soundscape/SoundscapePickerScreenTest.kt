// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.soundscape

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundscapePickerScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private fun asset(id: String, name: String = id) = AudioAsset(
        id = id,
        type = AudioAssetType.SOUNDSCAPE,
        name = name,
        displayName = name,
        durationSec = 120.0,
        r2Key = "soundscape/$id.aac",
        fileSizeBytes = 1_000L,
    )

    @Test fun `empty state shows placeholder copy`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    SoundscapePickerContent(
                        soundscapes = emptyList(),
                        onRowTap = {},
                        onRowDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText(
            "No soundscapes yet. Check your connection — the catalog will load when you're online.",
        ).assertIsDisplayed()
    }

    @Test fun `NotDownloaded row shows asset name and status`() {
        val state = SoundscapeState.NotDownloaded(
            asset = asset("rain", name = "Rain on Leaves"),
            isSelected = false,
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    SoundscapePickerContent(
                        soundscapes = listOf(state),
                        onRowTap = {},
                        onRowDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Rain on Leaves").assertIsDisplayed()
        composeRule.onNodeWithText("Not downloaded").assertIsDisplayed()
    }

    @Test fun `Downloading row shows progress status`() {
        val state = SoundscapeState.Downloading(
            asset = asset("forest", name = "Forest"),
            isSelected = false,
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    SoundscapePickerContent(
                        soundscapes = listOf(state),
                        onRowTap = {},
                        onRowDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Forest").assertIsDisplayed()
        composeRule.onNodeWithText("Downloading\u2026").assertIsDisplayed()
    }

    @Test fun `Downloaded selected row shows Selected label`() {
        val state = SoundscapeState.Downloaded(
            asset = asset("brook", name = "Brook"),
            isSelected = true,
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    SoundscapePickerContent(
                        soundscapes = listOf(state),
                        onRowTap = {},
                        onRowDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Brook").assertIsDisplayed()
        composeRule.onNodeWithText("Selected").assertIsDisplayed()
        composeRule.onNodeWithText("Downloaded").assertIsDisplayed()
    }

    @Test fun `Failed row shows failure status`() {
        val state = SoundscapeState.Failed(
            asset = asset("broken"),
            isSelected = false,
            reason = "download_failed",
        )
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    SoundscapePickerContent(
                        soundscapes = listOf(state),
                        onRowTap = {},
                        onRowDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Download failed").assertIsDisplayed()
    }

    @Test fun `tap on NotDownloaded row invokes onRowTap`() {
        val state = SoundscapeState.NotDownloaded(
            asset = asset("rain", name = "Rain on Leaves"),
            isSelected = false,
        )
        var tapped: SoundscapeState? = null
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    SoundscapePickerContent(
                        soundscapes = listOf(state),
                        onRowTap = { tapped = it },
                        onRowDelete = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Rain on Leaves").performClick()
        composeRule.waitForIdle()
        assert(tapped === state) { "expected tapped == state, got $tapped" }
    }
}
