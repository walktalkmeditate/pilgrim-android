// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.collective.CollectiveMilestone
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Behavioural tests for the milestone overlay surfaced by
 * [PracticeSummaryHeader]. The overlay must:
 *  - Ring the bell exactly once per milestone *number* (the
 *    [CollectiveMilestone] data class re-instantiates often in
 *    production so equality is not a reliable de-dup signal — the
 *    `number` field is).
 *  - Auto-dismiss 8s after the milestone arrives, regardless of whether
 *    the user has acknowledged the banner.
 *  - Render the verbatim sacred-numbers copy from
 *    [CollectiveMilestone.forNumber].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PracticeSummaryHeaderMilestoneTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bellFiresOncePerNumber_evenAcrossRecompose() {
        var bellCount = 0
        val milestone = mutableStateOf<CollectiveMilestone?>(
            CollectiveMilestone.forNumber(108),
        )
        composeRule.setContent {
            PilgrimTheme {
                PracticeSummaryHeader(
                    walkCount = 10,
                    totalDistanceMeters = 0.0,
                    totalMeditationSeconds = 0L,
                    firstWalkInstant = null,
                    distanceUnits = UnitSystem.Metric,
                    collectiveStats = null,
                    milestone = milestone.value,
                    onMilestoneShown = { bellCount++ },
                    onMilestoneDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, bellCount)

        milestone.value = CollectiveMilestone.forNumber(108)
        composeRule.waitForIdle()
        assertEquals(1, bellCount)

        milestone.value = CollectiveMilestone.forNumber(1_080)
        composeRule.waitForIdle()
        assertEquals(2, bellCount)
    }

    @Test
    fun eightSecondAutoDismiss() {
        var dismissCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PilgrimTheme {
                PracticeSummaryHeader(
                    walkCount = 10,
                    totalDistanceMeters = 0.0,
                    totalMeditationSeconds = 0L,
                    firstWalkInstant = null,
                    distanceUnits = UnitSystem.Metric,
                    collectiveStats = null,
                    milestone = CollectiveMilestone.forNumber(108),
                    onMilestoneShown = {},
                    onMilestoneDismiss = { dismissCount++ },
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(7_999L, ignoreFrameDuration = true)
        assertEquals(0, dismissCount)
        composeRule.mainClock.advanceTimeBy(2L, ignoreFrameDuration = true)
        assertEquals(1, dismissCount)
    }

    @Test
    fun milestoneTextRendersVerbatim() {
        composeRule.setContent {
            PilgrimTheme {
                PracticeSummaryHeader(
                    walkCount = 10,
                    totalDistanceMeters = 0.0,
                    totalMeditationSeconds = 0L,
                    firstWalkInstant = null,
                    distanceUnits = UnitSystem.Metric,
                    collectiveStats = null,
                    milestone = CollectiveMilestone.forNumber(108),
                    onMilestoneShown = {},
                    onMilestoneDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("108 walks. One for each bead on the mala.")
            .assertIsDisplayed()
    }
}
