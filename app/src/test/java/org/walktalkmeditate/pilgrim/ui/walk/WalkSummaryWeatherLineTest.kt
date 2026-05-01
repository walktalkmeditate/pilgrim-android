// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Stage 12-A weather line tests. The composable always renders when
 * called — caller is responsible for the null-condition / null-temp
 * guard. Two tests cover the format paths:
 *  - metric: ", N°C" with zero-decimal rounding
 *  - imperial: Celsius → Fahrenheit conversion + "°F" suffix
 *
 * `Locale.US` pinning matters for the temperature digit rendering — a
 * default-locale "%.0f" on Arabic/Persian/Hindi locales produces
 * non-ASCII digits and breaks both the visual contract and the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryWeatherLineTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersWeatherLineWithLabelAndCelsius() {
        composeRule.setContent {
            PilgrimTheme {
                WalkSummaryWeatherLine(
                    condition = WeatherCondition.LIGHT_RAIN,
                    temperatureCelsius = 12.5,
                    imperial = false,
                )
            }
        }
        composeRule.onNodeWithText("Light rain, 13°C").assertIsDisplayed()
    }

    @Test
    fun rendersImperialWhenRequested() {
        composeRule.setContent {
            PilgrimTheme {
                WalkSummaryWeatherLine(
                    condition = WeatherCondition.CLEAR,
                    temperatureCelsius = 0.0,
                    imperial = true,
                )
            }
        }
        composeRule.onNodeWithText("Clear, 32°F").assertIsDisplayed()
    }

    @Test
    fun rendersImperialConversionRoundsHalfUp() {
        // 25°C → 77°F exactly (25 * 9/5 + 32 = 77.0). Pins the
        // multiplication-then-add ordering matches iOS
        // `WeatherSnapshot.formatTemperature(_:imperial:)`.
        composeRule.setContent {
            PilgrimTheme {
                WalkSummaryWeatherLine(
                    condition = WeatherCondition.PARTLY_CLOUDY,
                    temperatureCelsius = 25.0,
                    imperial = true,
                )
            }
        }
        composeRule.onNodeWithText("Partly cloudy, 77°F").assertIsDisplayed()
    }
}
