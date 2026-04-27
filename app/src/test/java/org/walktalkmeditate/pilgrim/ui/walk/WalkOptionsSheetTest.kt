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
    fun `renders waypoint row`() {
        composeRule.setContent {
            WalkOptionsSheet(
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Drop Waypoint").assertIsDisplayed()
    }

    @Test
    fun `set intention row is not rendered`() {
        composeRule.setContent {
            WalkOptionsSheet(
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Set Intention").assertDoesNotExist()
    }

    @Test
    fun `waypoint row disabled when canDropWaypoint is false`() {
        composeRule.setContent {
            WalkOptionsSheet(
                waypointCount = 0,
                canDropWaypoint = false,
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Drop Waypoint").assertIsNotEnabled()
    }

    @Test
    fun `waypoint subtitle shows None marked when count is zero`() {
        // Android plurals on en-US never select quantity="zero", so a
        // pluralStringResource(... 0 ...) call would return "0 marked"
        // from the `other` branch. The WalkOptionsSheet special-cases
        // 0 with a non-plural string instead.
        composeRule.setContent {
            WalkOptionsSheet(
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("None marked").assertIsDisplayed()
    }

    @Test
    fun `waypoint subtitle uses plural for non-zero counts`() {
        composeRule.setContent {
            WalkOptionsSheet(
                waypointCount = 3,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("3 marked").assertIsDisplayed()
    }

    @Test
    fun `waypoint click fires onDropWaypoint`() {
        var fired = false
        composeRule.setContent {
            WalkOptionsSheet(
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = { fired = true },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Drop Waypoint").performClick()
        assertTrue(fired)
    }
}
