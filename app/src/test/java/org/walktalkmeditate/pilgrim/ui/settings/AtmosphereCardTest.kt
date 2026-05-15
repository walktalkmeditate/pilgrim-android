// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
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
    fun `renders appearance nav row with current selection`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.Dark,
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
    }

    @Test
    fun `tapping appearance nav row fires OpenAppearance`() {
        var fired: SettingsAction? = null
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = { fired = it },
                )
            }
        }
        composeRule.onNodeWithText("Appearance").performClick()
        composeRule.runOnIdle {
            assertEquals(SettingsAction.OpenAppearance, fired)
        }
    }

    @Test
    fun `constellation mode shows Constellation as detail`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.Constellation,
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText("Constellation").assertIsDisplayed()
    }

    @Test
    fun `renders sounds toggle row with description`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText("Sounds").assertIsDisplayed()
        composeRule.onNodeWithText("Bells, voice guides, haptics, and ambient soundscapes").assertIsDisplayed()
    }

    @Test
    fun `tapping sounds toggle fires onSetSoundsEnabled with inverted value`() {
        var lastValue: Boolean? = null
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
                    soundsEnabled = true,
                    onSetSoundsEnabled = { lastValue = it },
                    onAction = {},
                )
            }
        }
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
                    soundsEnabled = true,
                    onSetSoundsEnabled = {},
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText("Bells & Soundscapes").assertIsDisplayed()
    }

    @Test
    fun `bells and soundscapes nav row is hidden when sounds disabled`() {
        composeRule.setContent {
            PilgrimTheme {
                AtmosphereCard(
                    currentMode = AppearanceMode.System,
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
