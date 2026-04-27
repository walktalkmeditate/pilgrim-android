// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkOptionsSheetTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `renders intention and waypoint rows`() {
        composeRule.setContent {
            WalkOptionsSheet(
                intention = null,
                waypointCount = 0,
                canDropWaypoint = true,
                onSetIntention = {},
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Set Intention").assertIsDisplayed()
        composeRule.onNodeWithText("Drop Waypoint").assertIsDisplayed()
    }

    @Test
    fun `intention subtitle shows the persisted intention when set`() {
        composeRule.setContent {
            WalkOptionsSheet(
                intention = "walk well",
                waypointCount = 0,
                canDropWaypoint = true,
                onSetIntention = {},
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("walk well").assertIsDisplayed()
    }

    @Test
    fun `intention subtitle shows fallback when null`() {
        composeRule.setContent {
            WalkOptionsSheet(
                intention = null,
                waypointCount = 0,
                canDropWaypoint = true,
                onSetIntention = {},
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("No intention set").assertIsDisplayed()
    }

    @Test
    fun `waypoint row disabled when canDropWaypoint is false`() {
        composeRule.setContent {
            WalkOptionsSheet(
                intention = null,
                waypointCount = 0,
                canDropWaypoint = false,
                onSetIntention = {},
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Drop Waypoint").assertIsNotEnabled()
    }

    @Test
    fun `intention click fires onSetIntention`() {
        var fired = false
        composeRule.setContent {
            WalkOptionsSheet(
                intention = null,
                waypointCount = 0,
                canDropWaypoint = true,
                onSetIntention = { fired = true },
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Set Intention").performClick()
        assertTrue(fired)
    }
}
