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
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

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
    fun `pre-walk Finished state still renders the Set Intention row (BUG 1)`() {
        // iOS parity WalkOptionsSheet.swift:46 — after a walk finishes
        // the @Singleton controller stays Finished until the next
        // startWalk(). Opening options to set an intention for the NEXT
        // walk must still show the row. Drive canSetIntention through
        // the production predicate so the sheet + predicate stay in sync.
        val finished = WalkState.Finished(
            WalkAccumulator(walkId = 1L, startedAt = 1_000L),
            endedAt = 5_000L,
        )
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = canSetIntentionForState(finished),
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
    fun `pre-walk Set Intention omits subtitle when null (iOS parity)`() {
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
        // iOS WalkOptionsSheet passes subtitle = currentIntention (nil
        // pre-walk) so no subtitle line is rendered — not a placeholder.
        composeRule.onNodeWithText("A line for this walk").assertDoesNotExist()
        composeRule.onNodeWithText("Set Intention").assertIsDisplayed()
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

    // ---- Seek section (iOS WalkOptionsSheet.swift:107-137@c1745e8,
    // 85373c1; U9 port spec B11) ---------------------------------------

    @Test
    fun `seek section hidden on wander walks`() {
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = true,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = false,
                onDropWaypoint = {},
                onDismiss = {},
                isSeekActive = false,
            )
        }
        composeRule.onNodeWithText("Sonar").assertDoesNotExist()
        composeRule.onNodeWithText("Seek Anew").assertDoesNotExist()
    }

    @Test
    fun `seek section renders pre-departure with sonar controls and reroll`() {
        var rerolled = false
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = true,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                // Pre-departure: no walk row yet — the section must not
                // depend on the in-walk rows (85373c1).
                canDropWaypoint = false,
                onDropWaypoint = {},
                onDismiss = {},
                isSeekActive = true,
                sonarEnabled = true,
                onSeekAnew = { rerolled = true },
            )
        }
        composeRule.onNodeWithText("Sonar").assertIsDisplayed()
        composeRule.onNodeWithText("Sonar Volume").assertIsDisplayed()
        composeRule.onNodeWithText("Seek Anew").performClick()
        assertTrue(rerolled)
    }

    @Test
    fun `sonar volume row hidden while the toggle is off`() {
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = false,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
                isSeekActive = true,
                sonarEnabled = false,
            )
        }
        composeRule.onNodeWithText("Sonar").assertIsDisplayed()
        composeRule.onNodeWithText("Sonar Volume").assertDoesNotExist()
    }

    @Test
    fun `after seek complete the reroll row is disabled with the completed subtitle`() {
        var rerolled = false
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = false,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
                isSeekActive = true,
                isSeekComplete = true,
                onSeekAnew = { rerolled = true },
            )
        }
        composeRule.onNodeWithText("Seek Anew").assertIsDisplayed()
        composeRule.onNodeWithText("The seeking is complete").assertIsDisplayed()
        composeRule.onNodeWithText("Seek Anew").performClick()
        assertTrue("disabled row must swallow taps", !rerolled)
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

    @Test
    fun `soundscape row hidden when no soundscape selected`() {
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = false,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
                soundscapeName = null,
            )
        }
        composeRule.onNodeWithText("Soundscape").assertDoesNotExist()
    }

    @Test
    fun `soundscape row shows Off when not playing`() {
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = false,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
                soundscapeName = "Rain",
                isSoundscapePlaying = false,
            )
        }
        composeRule.onNodeWithText("Soundscape").assertIsDisplayed()
        composeRule.onNodeWithText("Off").assertIsDisplayed()
    }

    @Test
    fun `soundscape row shows name when playing`() {
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = false,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
                soundscapeName = "Rain",
                isSoundscapePlaying = true,
            )
        }
        composeRule.onNodeWithText("Rain").assertIsDisplayed()
    }

    @Test
    fun `soundscape row hidden pre-walk even when a soundscape is selected`() {
        // The Audio section is gated on canDropWaypoint (in-walk) AND a
        // selected soundscape. Pre-walk (canDropWaypoint=false) it must
        // stay hidden even if a name is set.
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = true,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = false,
                onDropWaypoint = {},
                onDismiss = {},
                soundscapeName = "Rain",
                isSoundscapePlaying = true,
            )
        }
        composeRule.onNodeWithText("Soundscape").assertDoesNotExist()
    }

    @Test
    fun `tapping soundscape row fires onToggleSoundscape`() {
        var fired = false
        composeRule.setContent {
            WalkOptionsSheet(
                canSetIntention = false,
                intention = null,
                onSetIntention = {},
                waypointCount = 0,
                canDropWaypoint = true,
                onDropWaypoint = {},
                onDismiss = {},
                soundscapeName = "Rain",
                isSoundscapePlaying = false,
                onToggleSoundscape = { fired = true },
            )
        }
        composeRule.onNodeWithText("Soundscape").performClick()
        assertTrue(fired)
    }
}
