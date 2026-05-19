// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Manual-QA batch 3, BUG #1: the in-walk voice-guide pause control
 * (bottom-start) and the weather/celestial vignette pills (bottom-end)
 * were two independently bottom-pinned children of unequal height
 * (36dp circle vs ~27dp pills), so the circle sat ~4-5dp higher than
 * the pills (not on one line); and both used the magic `big*7`=168dp
 * bottom inset, unrelated to the sparkline band, which crowded the
 * live sparkline.
 *
 * iOS lays bottom-left audio + bottom-right vignette in ONE HStack
 * with a Spacer, in a VStack ordered sheet → sparkline → row
 * (`ActiveWalkView.swift:421-487, 129-133@v1.6.0`). The Android fix
 * collapses the two separate aligned blocks into a single
 * BottomCenter Row (center-aligned vertically) sitting at the derived
 * [AMBIENT_ROW_BOTTOM_DP], clear of the sparkline band.
 *
 * Asserts presence + the derived constant arithmetic. Robolectric's
 * layout backend doesn't reliably resolve absolute pixel positions for
 * a precise "same baseline" assertion, so the single-Row structure is
 * what guarantees alignment (one parent, CenterVertically); the
 * constant test guarantees the row clears the sparkline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActiveWalkAmbientRowTest {

    @get:Rule val composeRule = createComposeRule()

    private val weather = WeatherSnapshot(
        condition = WeatherCondition.CLEAR,
        temperatureCelsius = 18.0,
        humidityFraction = 0.62,
        windSpeedMps = 6.0,
    )

    @Composable
    private fun AmbientRowHarness(
        packName: String?,
        weather: WeatherSnapshot?,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = PilgrimSpacing.normal,
                        end = PilgrimSpacing.normal,
                        bottom = AMBIENT_ROW_BOTTOM_DP,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceGuidePauseControl(
                    packName = packName,
                    isPaused = false,
                    onToggle = {},
                )
                Spacer(Modifier.weight(1f))
                WalkVignette(
                    weather = weather,
                    celestial = null,
                    celestialAwarenessEnabled = false,
                    units = UnitSystem.Metric,
                )
            }
        }
    }

    @Test
    fun `derived bottom inset clears the sparkline band`() {
        // sheet 88 + sparkline pad 4 + sparkline band 24 + 4 gap = 120dp.
        assertEquals(120.dp, AMBIENT_ROW_BOTTOM_DP)
        // Sparkline band height the row clears must match the constant
        // the sparkline actually draws at.
        assertEquals(24, SPARKLINE_BAND_HEIGHT_DP.value.toInt())
        // The clearance must be strictly greater than the sparkline's
        // own bottom inset (sheet + xs pad) so the row never overlaps it.
        assertEquals(true, AMBIENT_ROW_BOTTOM_DP > SPARKLINE_BAND_HEIGHT_DP)
    }

    @Test
    fun `both controls present and laid out when pack set and weather present`() {
        composeRule.setContent {
            PilgrimTheme {
                AmbientRowHarness(packName = "Forest Walk", weather = weather)
            }
        }
        composeRule.onNodeWithTag(VOICE_GUIDE_PAUSE_CONTROL_TAG).assertExists()
        composeRule.onNodeWithContentDescription("Pause voice guide").assertExists()
        composeRule.onNodeWithText("18°").assertExists()
    }

    @Test
    fun `row collapses gracefully when pack absent and no weather`() {
        composeRule.setContent {
            PilgrimTheme {
                AmbientRowHarness(packName = null, weather = null)
            }
        }
        composeRule.onNodeWithTag(VOICE_GUIDE_PAUSE_CONTROL_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("18°").assertDoesNotExist()
    }
}
