// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MilestoneCalloutRowTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersGivenProse() {
        composeRule.setContent {
            PilgrimTheme { MilestoneCalloutRow(prose = "Your longest walk yet") }
        }
        composeRule.onNodeWithText("Your longest walk yet").assertIsDisplayed()
    }

    @Test
    fun rendersDifferentProse() {
        composeRule.setContent {
            PilgrimTheme { MilestoneCalloutRow(prose = "You walked on the Spring Equinox") }
        }
        composeRule.onNodeWithText("You walked on the Spring Equinox").assertIsDisplayed()
    }
}
