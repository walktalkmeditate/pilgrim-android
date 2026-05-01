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
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkActivityInsightsCardTest {

    @get:Rule val composeRule = createComposeRule()

    private fun meditation(start: Long, end: Long) = ActivityInterval(
        walkId = 1L, startTimestamp = start, endTimestamp = end,
        activityType = ActivityType.MEDITATING,
    )

    @Test
    fun rendersMeditationOnce_withSingularPhrase() {
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityInsightsCard(
                    talkMillis = 0L,
                    activeMillis = 60_000L,
                    meditationIntervals = listOf(meditation(0L, 25 * 60_000L)),
                )
            }
        }
        composeRule.onNodeWithText("Meditated once for 25 min").assertIsDisplayed()
    }

    @Test
    fun rendersMeditationMultiple_withCountAndLongest() {
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityInsightsCard(
                    talkMillis = 0L,
                    activeMillis = 60_000L,
                    meditationIntervals = listOf(
                        meditation(0L, 5 * 60_000L),
                        meditation(10 * 60_000L, 30 * 60_000L), // 20 min — longest
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Meditated 2 times (longest: 20 min)").assertIsDisplayed()
    }

    @Test
    fun rendersTalkPercentage() {
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityInsightsCard(
                    talkMillis = 11 * 60_000L, // 11 min
                    activeMillis = 100 * 60_000L, // 100 min → 11%
                    meditationIntervals = emptyList(),
                )
            }
        }
        composeRule.onNodeWithText("Talked for 11% of the walk").assertIsDisplayed()
    }
}
