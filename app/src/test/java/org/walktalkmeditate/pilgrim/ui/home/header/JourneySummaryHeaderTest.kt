// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.header

import android.app.Application
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.home.JourneySummary
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * AF53 (iOS PR #45): the tappable journey stat header (cycles
 * distance/talk/meditation) must expose a Button role + a click action so
 * TalkBack announces and operates it, with the stat lines merged into one
 * focusable node.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JourneySummaryHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `stat header is a single Button-role node with a click action`() {
        composeRule.setContent {
            PilgrimTheme {
                JourneySummaryHeader(
                    summary = JourneySummary(
                        totalDistanceM = 5_000.0,
                        totalTalkSec = 600L,
                        totalMeditateSec = 300L,
                        talkerCount = 2,
                        meditatorCount = 1,
                        walkCount = 3,
                        firstWalkStartMs = 0L,
                    ),
                    units = UnitSystem.Metric,
                    nowMs = 30L * 24 * 60 * 60 * 1000,
                )
            }
        }
        // mergeDescendants collapses the two stat lines into the clickable
        // node, so exactly one click-action node exists; it must be a Button.
        composeRule.onNode(hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }
}
