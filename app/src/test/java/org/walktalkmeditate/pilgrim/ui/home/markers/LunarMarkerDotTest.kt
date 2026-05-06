// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LunarMarkerDotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun full_moon_renders_at_10dp_size() {
        composeRule.setContent {
            PilgrimTheme {
                LunarMarkerDot(
                    isFullMoon = true,
                    modifier = Modifier.testTag("lunar"),
                )
            }
        }
        composeRule.onNodeWithTag("lunar")
            .assertWidthIsEqualTo(10.dp)
            .assertHeightIsEqualTo(10.dp)
    }

    @Test
    fun new_moon_renders_at_10dp_size() {
        composeRule.setContent {
            PilgrimTheme {
                LunarMarkerDot(
                    isFullMoon = false,
                    modifier = Modifier.testTag("lunar-new"),
                )
            }
        }
        composeRule.onNodeWithTag("lunar-new")
            .assertWidthIsEqualTo(10.dp)
            .assertHeightIsEqualTo(10.dp)
    }
}
