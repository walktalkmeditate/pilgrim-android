// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Spa
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsCardStyleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `CardHeader renders title and subtitle`() {
        composeRule.setContent {
            PilgrimTheme {
                CardHeader(
                    title = "Atmosphere",
                    subtitle = "Look and feel",
                )
            }
        }
        composeRule.onNodeWithText("Atmosphere").assertIsDisplayed()
        composeRule.onNodeWithText("Look and feel").assertIsDisplayed()
    }

    @Test
    fun `SettingToggle invokes onCheckedChange when tapped`() {
        var current = false
        composeRule.setContent {
            PilgrimTheme {
                SettingToggle(
                    label = "Background sounds",
                    description = "Play soundscape during walks",
                    checked = current,
                    onCheckedChange = { current = it },
                )
            }
        }
        composeRule.onNodeWithText("Background sounds").assertIsDisplayed()
        composeRule.onNodeWithText("Play soundscape during walks").assertIsDisplayed()
        composeRule.onNode(
            androidx.compose.ui.test.isToggleable(),
        ).assertIsOff().performClick()
        composeRule.runOnIdle {
            assertTrue("onCheckedChange should fire and flip current to true", current)
        }
    }

    @Test
    fun `SettingToggle reflects checked state`() {
        composeRule.setContent {
            PilgrimTheme {
                SettingToggle(
                    label = "Haptics",
                    description = "Subtle vibrations on key events",
                    checked = true,
                    onCheckedChange = {},
                )
            }
        }
        composeRule.onNode(
            androidx.compose.ui.test.isToggleable(),
        ).assertIsOn()
    }

    @Test
    fun `SettingNavRow with leading icon renders the label`() {
        composeRule.setContent {
            PilgrimTheme {
                SettingNavRow(
                    label = "Soundscapes",
                    leadingIcon = Icons.Default.Spa,
                    onClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Soundscapes").assertIsDisplayed()
    }

    @Test
    fun `SettingNavRow click fires onClick`() {
        var clicked = false
        composeRule.setContent {
            PilgrimTheme {
                SettingNavRow(
                    label = "Voice guides",
                    detail = "Brother Crane",
                    onClick = { clicked = true },
                )
            }
        }
        composeRule.onNodeWithText("Voice guides").performClick()
        composeRule.runOnIdle {
            assertTrue("onClick should fire", clicked)
        }
    }

    @Test
    fun `SettingPicker invokes onSelect with the picked value`() {
        var picked: Int? = null
        composeRule.setContent {
            PilgrimTheme {
                SettingPicker(
                    label = "Hemisphere",
                    options = listOf("North" to 0, "South" to 1),
                    selected = 0,
                    onSelect = { picked = it },
                )
            }
        }
        composeRule.onNodeWithText("South").performClick()
        composeRule.runOnIdle {
            assertEquals(1, picked)
        }
    }

    @Test
    fun `SettingNavRow detail caption is displayed when supplied`() {
        composeRule.setContent {
            PilgrimTheme {
                SettingNavRow(
                    label = "Voice guides",
                    detail = "Brother Crane",
                    onClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Brother Crane").assertIsDisplayed()
    }

    @Test
    fun `SettingNavRow defaults to chevron icon`() {
        composeRule.setContent {
            PilgrimTheme {
                SettingNavRow(
                    label = "About Pilgrim",
                    onClick = {},
                )
            }
        }
        // useUnmergedTree = true: the row's clickable + onClickLabel
        // merges descendant semantics into a single tappable node, so
        // the icon's testTag is only visible in the unmerged tree.
        composeRule.onNodeWithTag(NAV_ROW_CHEVRON_ICON_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(NAV_ROW_EXTERNAL_ICON_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `SettingNavRow with external uses outbound icon`() {
        composeRule.setContent {
            PilgrimTheme {
                SettingNavRow(
                    label = "Rate Pilgrim",
                    external = true,
                    onClick = {},
                )
            }
        }
        composeRule.onNodeWithTag(NAV_ROW_EXTERNAL_ICON_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(NAV_ROW_CHEVRON_ICON_TAG, useUnmergedTree = true).assertDoesNotExist()
    }
}
