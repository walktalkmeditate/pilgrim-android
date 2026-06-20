// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

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
                WalkSummaryTopBar(startTimestamp = ts, hemisphere = Hemisphere.Northern, onDone = {})
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
                    hemisphere = Hemisphere.Northern,
                    onDone = { doneTaps += 1 },
                )
            }
        }
        composeRule.onNodeWithText("Done").performClick()
        assertTrue(doneTaps == 1)
    }

    @Test
    fun nullTimestamp_rendersEmptyTitleSlot_doneStillWorks() {
        var doneTaps = 0
        composeRule.setContent {
            PilgrimTheme {
                WalkSummaryTopBar(
                    startTimestamp = null,
                    hemisphere = Hemisphere.Northern,
                    onDone = { doneTaps += 1 },
                )
            }
        }
        // No epoch placeholder leaks through during Loading / NotFound.
        composeRule.onAllNodesWithText("January 1, 1970").assertCountEquals(0)
        composeRule.onNodeWithText("Done").performClick()
        assertTrue(doneTaps == 1)
    }

    // iOS WalkSummaryView.swift:160-170 — ` · <kanji>` suffix on turnings.

    @Test
    fun cardinalTurnings_appendKanji() {
        assertEquals("March 20, 2026 · 春分", summaryDateTitle("March 20, 2026", SeasonalMarker.SpringEquinox))
        assertEquals("June 21, 2026 · 夏至", summaryDateTitle("June 21, 2026", SeasonalMarker.SummerSolstice))
        assertEquals("September 22, 2026 · 秋分", summaryDateTitle("September 22, 2026", SeasonalMarker.AutumnEquinox))
        assertEquals("December 21, 2026 · 冬至", summaryDateTitle("December 21, 2026", SeasonalMarker.WinterSolstice))
    }

    @Test
    fun crossQuarterMarkers_leaveTitleUnchanged() {
        assertEquals("May 5, 2026", summaryDateTitle("May 5, 2026", SeasonalMarker.Beltane))
        assertEquals("August 7, 2026", summaryDateTitle("August 7, 2026", SeasonalMarker.Lughnasadh))
    }

    @Test
    fun nonTurningDays_leaveTitleUnchanged() {
        assertEquals("April 15, 2026", summaryDateTitle("April 15, 2026", null))
    }
}
