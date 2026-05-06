// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExpandCardSheetTest {
    @get:Rule val composeRule = createComposeRule()

    private val snap = WalkSnapshot(
        id = 42L,
        uuid = "uuid-42",
        startMs = 1_700_000_000_000L,
        distanceM = 5_000.0,
        durationSec = 1800.0,
        averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = 5_000.0,
        talkDurationSec = 300L,
        meditateDurationSec = 120L,
        favicon = null,
        isShared = false,
        weatherCondition = null,
    )

    @Test
    fun renders_view_details_button_and_three_pills() {
        var clickedId: Long? = null
        var dismissed = false
        composeRule.setContent {
            PilgrimTheme {
                ExpandCardSheet(
                    snapshot = snap,
                    celestial = null,
                    seasonColor = androidx.compose.ui.graphics.Color(0xFF74B495),
                    units = UnitSystem.Metric,
                    isShared = false,
                    onViewDetails = { clickedId = it },
                    onDismissRequest = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithText("View details").assertIsDisplayed()
        // Pill text formatted as "M:SS LABEL"; assert by substring.
        composeRule.onNodeWithText("walk", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("talk", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("meditate", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("View details").performClick()
        composeRule.runOnIdle {
            assertEquals(42L, clickedId)
            assertTrue(dismissed)
        }
    }
}
