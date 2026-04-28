// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsSelected
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
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AtmosphereCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders all three segments`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    onSelectMode = {},
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText("Auto").assertExists()
        composeRule.onNodeWithText("Light").assertExists()
        composeRule.onNodeWithText("Dark").assertExists()
    }

    @Test
    fun `current selection is marked selected`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.Dark,
                    onSelectMode = {},
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText("Dark").assertIsSelected()
    }

    @Test
    fun `tapping a segment fires onSelectMode with the right value`() {
        var picked: AppearanceMode? = null
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    onSelectMode = { picked = it },
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText("Light").performClick()
        composeRule.runOnIdle {
            assertEquals(AppearanceMode.Light, picked)
        }
    }

    @Test
    fun `renders sounds toggle row with description`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    onSelectMode = {},
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText("Sounds").assertExists()
        composeRule.onNodeWithText("Bells, voice guides, haptics, and ambient soundscapes").assertExists()
    }

    @Test
    fun `tapping sounds toggle fires onSetSoundsEnabled with inverted value`() {
        var lastValue: Boolean? = null
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    onSelectMode = {},
                    soundsEnabled = true,
                    onSetSoundsEnabled = { lastValue = it },
                    onAction = {},
                )
            }
        }
        // The label text "Sounds" sits in a Column without a click
        // target — only the M3 Switch is toggleable. Find the Switch
        // via `isToggleable()` semantics rather than label text.
        composeRule.onNode(isToggleable()).performClick()
        composeRule.runOnIdle {
            assertEquals(false, lastValue)
        }
    }

    @Test
    fun `bells and soundscapes nav row is visible when sounds enabled`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    onSelectMode = {},
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText("Bells & Soundscapes").assertExists()
    }

    @Test
    fun `bells and soundscapes nav row is hidden when sounds disabled`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    onSelectMode = {},
                    soundsEnabled = false,
                    onSetSoundsEnabled = {},
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText("Bells & Soundscapes").assertDoesNotExist()
    }

    @Test
    fun `tapping bells and soundscapes row fires OpenBellsAndSoundscapes`() {
        var fired: SettingsAction? = null
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    onSelectMode = {},
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = { fired = it },
                )
            }
        }
        composeRule.onNodeWithText("Bells & Soundscapes").performClick()
        composeRule.runOnIdle {
            assertEquals(SettingsAction.OpenBellsAndSoundscapes, fired)
        }
    }
}
