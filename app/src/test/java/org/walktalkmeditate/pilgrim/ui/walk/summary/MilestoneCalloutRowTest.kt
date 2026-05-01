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
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestone
import org.walktalkmeditate.pilgrim.ui.goshuin.Season
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MilestoneCalloutRowTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun firstWalk_rendersCorrectProse() {
        composeRule.setContent {
            PilgrimTheme { MilestoneCalloutRow(GoshuinMilestone.FirstWalk) }
        }
        composeRule.onNodeWithText("Your first walk").assertIsDisplayed()
    }

    @Test
    fun longestWalk_rendersCorrectProse() {
        composeRule.setContent {
            PilgrimTheme { MilestoneCalloutRow(GoshuinMilestone.LongestWalk) }
        }
        composeRule.onNodeWithText("Your longest walk yet").assertIsDisplayed()
    }

    @Test
    fun nthWalk_includesOrdinal() {
        composeRule.setContent {
            PilgrimTheme { MilestoneCalloutRow(GoshuinMilestone.NthWalk(5)) }
        }
        composeRule.onNodeWithText("Your 5th walk").assertIsDisplayed()
    }

    @Test
    fun firstOfSeason_includesSeasonName() {
        composeRule.setContent {
            PilgrimTheme {
                MilestoneCalloutRow(GoshuinMilestone.FirstOfSeason(Season.Spring))
            }
        }
        composeRule.onNodeWithText("Your first walk of Spring").assertIsDisplayed()
    }
}
