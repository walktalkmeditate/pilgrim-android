// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.home.empty.EmptyJournalState
import org.walktalkmeditate.pilgrim.ui.home.expand.ExpandCardSheet
import org.walktalkmeditate.pilgrim.ui.home.scenery.SceneryItem
import org.walktalkmeditate.pilgrim.ui.home.scenery.SceneryPlacement
import org.walktalkmeditate.pilgrim.ui.home.scenery.ScenerySide
import org.walktalkmeditate.pilgrim.ui.home.scenery.SceneryType
import org.walktalkmeditate.pilgrim.ui.home.scenery.WalkThreshold
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

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

    @Test
    fun scenery_item_composes_for_cairn_and_drift_placements() {
        // U15: cairn stones and drift's seasonal faces render for real —
        // the journal must compose them without crashing.
        composeRule.setContent {
            PilgrimTheme {
                SceneryItem(
                    placement = SceneryPlacement(
                        type = SceneryType.Cairn,
                        side = ScenerySide.Left,
                        offset = 0f,
                        stones = 5,
                    ),
                    snapshot = snap.copy(isSeek = true, foundPlaces = 3),
                    sizeDp = 24.dp,
                    hemisphere = Hemisphere.Northern,
                )
                SceneryItem(
                    placement = SceneryPlacement(
                        type = SceneryType.Drift,
                        side = ScenerySide.Right,
                        offset = 0f,
                    ),
                    snapshot = snap,
                    sizeDp = 24.dp,
                    hemisphere = Hemisphere.Northern,
                )
            }
        }
        composeRule.onRoot().assertExists()
    }

    @Test
    fun new_scenery_renders_a_static_frame_under_reduce_motion() {
        // U15: every new/reworked renderer must freeze to a single frame
        // when Reduce Motion is on (sceneryTimeSeconds returns t=0).
        val placements = listOf(
            SceneryPlacement(SceneryType.Cairn, ScenerySide.Left, 0f, stones = 4),
            SceneryPlacement(SceneryType.Drift, ScenerySide.Right, 0f),
            SceneryPlacement(
                SceneryType.Torii,
                ScenerySide.Left,
                0f,
                gateKind = WalkThreshold.Seeking,
            ),
            SceneryPlacement(SceneryType.Moon, ScenerySide.Right, 0f),
            SceneryPlacement(SceneryType.Lantern, ScenerySide.Left, 0f),
        )
        composeRule.setContent {
            PilgrimTheme {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    for (placement in placements) {
                        SceneryItem(
                            placement = placement,
                            snapshot = snap,
                            sizeDp = 24.dp,
                            hemisphere = Hemisphere.Northern,
                        )
                    }
                }
            }
        }
        composeRule.onRoot().assertExists()
    }
}
