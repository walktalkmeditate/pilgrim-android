// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.home.empty.EmptyJournalState
import org.walktalkmeditate.pilgrim.ui.home.expand.ExpandCardSheet
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Smoke-only Stage 14-BCD task 9 integration assertion. Asserts that
 * the new EmptyJournalState + ExpandCardSheet Composables render
 * standalone with the exact strings/buttons HomeScreen wires together.
 *
 * Full HomeScreen integration with HomeViewModel + WalkRepository
 * fakes is deferred — see plan Step 9.3 fallback note. Stage 7-A
 * precedent (fake-repo HomeViewModel tests with proper teardown
 * discipline) applies whenever this test is widened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JournalScreenIntegrationTest {
    @get:Rule val composeRule = createComposeRule()

    private val snap = WalkSnapshot(
        id = 1L,
        uuid = "u1",
        startMs = 1_700_000_000_000L,
        distanceM = 30_000.0,
        durationSec = 7200.0,
        averagePaceSecPerKm = 240.0,
        cumulativeDistanceM = 30_000.0,
        talkDurationSec = 0L,
        meditateDurationSec = 0L,
        favicon = null,
        isShared = false,
        weatherCondition = null,
    )

    @Test
    fun empty_state_renders_begin_caption() {
        composeRule.setContent { PilgrimTheme { EmptyJournalState() } }
        composeRule.onNodeWithText("Begin").assertIsDisplayed()
    }

    @Test
    fun expand_card_renders_view_details_button() {
        composeRule.setContent {
            PilgrimTheme {
                ExpandCardSheet(
                    snapshot = snap,
                    celestial = null,
                    seasonColor = androidx.compose.ui.graphics.Color(0xFF74B495),
                    units = UnitSystem.Metric,
                    isShared = false,
                    onViewDetails = {},
                    onDismissRequest = {},
                )
            }
        }
        composeRule.onNodeWithText("View details").assertIsDisplayed()
    }
}
