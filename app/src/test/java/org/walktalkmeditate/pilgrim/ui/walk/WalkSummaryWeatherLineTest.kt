// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
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
        composeRule.onNodeWithText("Light rain, 12°C").assertIsDisplayed()
    }

    // iOS `String(format: "%.0f°C")` (`WeatherService.swift:61-66@7c200bf`):
    // a tie goes to the even neighbour and a negative zero keeps its sign,
    // as macOS printf prints them.
    @Test
    fun aTieRoundsToTheEvenNeighbourAsIosPrintsIt() {
        assertEquals("8°C", formatTemperature(8.5, imperial = false))
        assertEquals("10°C", formatTemperature(9.5, imperial = false))
        assertEquals("10°C", formatTemperature(10.5, imperial = false))
        assertEquals("-2°C", formatTemperature(-2.5, imperial = false))
    }

    @Test
    fun aNegativeTieAtZeroPrintsMinusZero() {
        assertEquals("-0°C", formatTemperature(-0.5, imperial = false))
    }

    @Test
    fun aFahrenheitTieRoundsToTheEvenNeighbour() {
        assertEquals("36°F", formatTemperature(2.5, imperial = true))
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
    fun rendersImperialConversionExactly() {
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
