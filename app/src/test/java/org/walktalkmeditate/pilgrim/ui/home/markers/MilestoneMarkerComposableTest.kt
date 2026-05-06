// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
class MilestoneMarkerComposableTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renders_100km_label_in_metric() {
        composeRule.setContent {
            PilgrimTheme {
                MilestoneMarker(distanceM = 100_000.0, units = UnitSystem.Metric)
            }
        }
        composeRule.onNodeWithText("100 km").assertIsDisplayed()
    }

    @Test
    fun renders_62mi_for_100km_in_imperial() {
        composeRule.setContent {
            PilgrimTheme {
                MilestoneMarker(distanceM = 100_000.0, units = UnitSystem.Imperial)
            }
        }
        composeRule.onNodeWithText("62 mi").assertIsDisplayed()
    }
}
