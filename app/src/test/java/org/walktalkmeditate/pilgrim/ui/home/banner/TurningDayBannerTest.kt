// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.banner

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TurningDayBannerTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun null_marker_renders_zero_height() {
        composeRule.setContent {
            PilgrimTheme {
                TurningDayBanner(
                    marker = null,
                    modifier = Modifier.testTag("banner"),
                )
            }
        }
        composeRule.onNodeWithTag("banner").assertExists()
        composeRule.onNodeWithText("Today, day equals night.").assertDoesNotExist()
    }

    @Test
    fun spring_equinox_renders_banner_text_and_kanji() {
        composeRule.setContent {
            PilgrimTheme {
                TurningDayBanner(marker = SeasonalMarker.SpringEquinox)
            }
        }
        composeRule.onNodeWithText("Today, day equals night.").assertIsDisplayed()
        composeRule.onNodeWithText("春分").assertIsDisplayed()
    }

    @Test
    fun cross_quarter_marker_renders_zero_height() {
        composeRule.setContent {
            PilgrimTheme {
                TurningDayBanner(marker = SeasonalMarker.Beltane)
            }
        }
        composeRule.onNodeWithText("Today, day equals night.").assertDoesNotExist()
        composeRule.onNodeWithText("Today the sun stands still.").assertDoesNotExist()
    }
}
