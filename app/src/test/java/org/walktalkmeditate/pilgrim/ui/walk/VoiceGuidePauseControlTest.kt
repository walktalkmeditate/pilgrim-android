// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Manual-QA batch 2, BUG B3: there was no in-walk voice-guide
 * play/pause control. Covers the UI gate: the control shows ONLY
 * when a pack is active, flips its icon + a11y label on pause state,
 * and forwards taps. iOS parity `ActiveWalkView.swift:433-443`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuidePauseControlTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `control is absent when no voice-guide pack is active`() {
        composeRule.setContent {
            PilgrimTheme {
                VoiceGuidePauseControl(
                    packName = null,
                    isPaused = false,
                    onToggle = {},
                )
            }
        }
        composeRule.onNodeWithTag(VOICE_GUIDE_PAUSE_CONTROL_TAG).assertDoesNotExist()
    }

    @Test
    fun `shows pause affordance while playing and forwards the tap`() {
        var toggles = 0
        composeRule.setContent {
            PilgrimTheme {
                VoiceGuidePauseControl(
                    packName = "Forest Walk",
                    isPaused = false,
                    onToggle = { toggles++ },
                )
            }
        }
        composeRule.onNodeWithTag(VOICE_GUIDE_PAUSE_CONTROL_TAG).assertExists()
        composeRule.onNodeWithContentDescription("Pause voice guide")
            .assertHasClickAction()
            .performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun `shows resume affordance while paused`() {
        composeRule.setContent {
            PilgrimTheme {
                VoiceGuidePauseControl(
                    packName = "Forest Walk",
                    isPaused = true,
                    onToggle = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Resume voice guide").assertExists()
    }
}
