// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

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
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryTopBarTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersFormattedLongDate() {
        // 2026-03-16 12:00 UTC → "March 16, 2026" in any non-east-of-UTC zone.
        // ZoneId.systemDefault on Robolectric defaults to America/Los_Angeles
        // unless overridden — the timestamp 1773_4_xx covers that.
        val ts = java.time.LocalDate.of(2026, 3, 16)
            .atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        composeRule.setContent {
            PilgrimTheme {
                WalkSummaryTopBar(startTimestamp = ts, onDone = {})
            }
        }
        composeRule.onNodeWithText("March 16, 2026").assertIsDisplayed()
    }

    @Test
    fun doneButtonInvokesCallback() {
        var doneTaps = 0
        composeRule.setContent {
            PilgrimTheme {
                WalkSummaryTopBar(
                    startTimestamp = 1_700_000_000_000L,
                    onDone = { doneTaps += 1 },
                )
            }
        }
        composeRule.onNodeWithText("Done").performClick()
        assertTrue(doneTaps == 1)
    }
}
