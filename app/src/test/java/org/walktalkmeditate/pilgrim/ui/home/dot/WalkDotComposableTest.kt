// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WalkDotComposableTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tap fires onTap callback`() {
        var tapped = false
        composeRule.setContent {
            WalkDot(
                snapshot = WalkSnapshot(
                    id = 1L, uuid = "u", startMs = 0L, distanceM = 1000.0,
                    durationSec = 600.0, averagePaceSecPerKm = 360.0,
                    cumulativeDistanceM = 1000.0, talkDurationSec = 0L,
                    meditateDurationSec = 0L, favicon = null, isShared = false,
                    weatherCondition = null,
                ),
                sizeDp = 12f,
                color = Color.Black,
                opacity = 1f,
                isNewest = true,
                contentDescription = "test-dot",
                onTap = { tapped = true },
            )
        }
        composeRule.onNodeWithContentDescription("test-dot").performClick()
        assertTrue(tapped)
    }
}
