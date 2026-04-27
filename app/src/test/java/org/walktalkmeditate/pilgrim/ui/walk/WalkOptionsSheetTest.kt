// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
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
    fun `pre-walk renders only Set Intention row not Drop Waypoint`() {
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = true,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = false,
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Set Intention").assertIsDisplayed()
        composeRule.onNodeWithText("Drop Waypoint").assertDoesNotExist()
    }

    @Test
    fun `in-walk renders only Drop Waypoint row not Set Intention`() {
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = false,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Drop Waypoint").assertIsDisplayed()
        composeRule.onNodeWithText("Set Intention").assertDoesNotExist()
    }

    @Test
    fun `pre-walk Set Intention subtitle shows persisted draft when set`() {
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = true,
                intention = "find peace",
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = false,
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("find peace").assertIsDisplayed()
    }

    @Test
    fun `pre-walk Set Intention subtitle shows fallback when null`() {
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = true,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = false,
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("A line for this walk").assertIsDisplayed()
    }

    @Test
    fun `Set Intention click fires onSetIntention`() {
        var fired = false
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = true,
                intention = null,
                onSetIntention = { fired = true },
                waypointCount = 0,
                canDropWaypoint = false,
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Set Intention").performClick()
        assertTrue(fired)
    }

    @Test
    fun `waypoint subtitle shows None marked when count is zero`() {
        // Android plurals on en-US never select quantity="zero", so a
        // pluralStringResource(... 0 ...) call would return "0 marked"
        // from the `other` branch. The WalkOptionsSheet special-cases
        // 0 with a non-plural string instead.
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = false,
                intention = null,
                onSetIntention = {},
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
                canSetIntention = false,
                intention = null,
                onSetIntention = {},
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
                canSetIntention = false,
                intention = null,
                onSetIntention = {},
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
