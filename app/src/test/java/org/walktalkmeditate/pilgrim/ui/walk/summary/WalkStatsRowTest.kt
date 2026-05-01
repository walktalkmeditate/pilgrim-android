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
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStatsRowTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersDistanceAlone_whenNoElevation() {
        composeRule.setContent {
            PilgrimTheme {
                WalkStatsRow(
                    distanceMeters = 1_500.0,
                    ascendMeters = 0.0,
                    units = UnitSystem.Metric,
                )
            }
        }
        composeRule.onNodeWithText("Distance").assertIsDisplayed()
        composeRule.onAllNodesWithText("Elevation").assertCountEquals(0)
    }

    @Test
    fun rendersDistanceAndElevation_whenAscendOverThreshold() {
        composeRule.setContent {
            PilgrimTheme {
                WalkStatsRow(
                    distanceMeters = 5_000.0,
                    ascendMeters = 100.0,
                    units = UnitSystem.Metric,
                )
            }
        }
        composeRule.onNodeWithText("Distance").assertIsDisplayed()
        composeRule.onNodeWithText("Elevation").assertIsDisplayed()
        composeRule.onNodeWithText("100 m").assertIsDisplayed()
    }

    @Test
    fun ascendUnderOneMeter_hidesElevation() {
        composeRule.setContent {
            PilgrimTheme {
                WalkStatsRow(
                    distanceMeters = 5_000.0,
                    ascendMeters = 0.5,
                    units = UnitSystem.Metric,
                )
            }
        }
        composeRule.onAllNodesWithText("Elevation").assertCountEquals(0)
    }
}
