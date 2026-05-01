// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkTimeBreakdownGridTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersAllThreeCards_evenWhenZero() {
        composeRule.setContent {
            PilgrimTheme {
                WalkTimeBreakdownGrid(
                    walkMillis = 0L,
                    talkMillis = 0L,
                    meditateMillis = 0L,
                )
            }
        }
        composeRule.onNodeWithText("Walk").assertIsDisplayed()
        composeRule.onNodeWithText("Talk").assertIsDisplayed()
        composeRule.onNodeWithText("Meditate").assertIsDisplayed()
        composeRule.onAllNodesWithText("0:00").assertCountEquals(3)
    }

    @Test
    fun durationsFormatCorrectly() {
        composeRule.setContent {
            PilgrimTheme {
                WalkTimeBreakdownGrid(
                    walkMillis = 2 * 3_600_000L + 50 * 60_000L,
                    talkMillis = 22 * 60_000L,
                    meditateMillis = 25 * 60_000L,
                )
            }
        }
        composeRule.onNodeWithText("2:50:00").assertIsDisplayed()
        composeRule.onNodeWithText("22:00").assertIsDisplayed()
        composeRule.onNodeWithText("25:00").assertIsDisplayed()
    }
}
