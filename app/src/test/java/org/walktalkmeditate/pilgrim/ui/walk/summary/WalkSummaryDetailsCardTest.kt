// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryDetailsCardTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersPausedDuration() {
        composeRule.setContent {
            PilgrimTheme {
                WalkSummaryDetailsCard(pausedMillis = 12 * 60_000L + 34_000L) // 12:34
            }
        }
        composeRule.onNodeWithText("Paused").assertIsDisplayed()
        composeRule.onNodeWithText("12:34").assertIsDisplayed()
    }
}
