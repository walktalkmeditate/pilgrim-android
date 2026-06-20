// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.ElementBalance
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.PlanetaryHour
import org.walktalkmeditate.pilgrim.core.celestial.PlanetaryPosition
import org.walktalkmeditate.pilgrim.core.celestial.ZodiacPosition
import org.walktalkmeditate.pilgrim.core.celestial.ZodiacSign
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Manual-QA batch 2, BUG B2: the weather/celestial vignette pills
 * always rendered the full info and could not collapse. iOS
 * (`WeatherVignetteView.swift:8-45` / `CelestialVignetteView.swift:7-37`)
 * starts each pill COLLAPSED and toggles expansion on tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkVignetteTest {

    @get:Rule val composeRule = createComposeRule()

    private val weather = WeatherSnapshot(
        condition = WeatherCondition.CLEAR,
        temperatureCelsius = 18.0,
        humidityFraction = 0.62,
        windSpeedMps = 6.0,
    )

    private val celestial = CelestialSnapshot(
        positions = listOf(
            PlanetaryPosition(
                planet = Planet.Sun,
                longitude = 35.0,
                tropical = ZodiacPosition(ZodiacSign.Taurus, 5.0),
                sidereal = ZodiacPosition(ZodiacSign.Aries, 12.0),
                isRetrograde = false,
                isIngress = false,
            ),
            PlanetaryPosition(
                planet = Planet.Moon,
                longitude = 120.0,
                tropical = ZodiacPosition(ZodiacSign.Cancer, 0.0),
                sidereal = ZodiacPosition(ZodiacSign.Gemini, 7.0),
                isRetrograde = false,
                isIngress = false,
            ),
            PlanetaryPosition(
                planet = Planet.Mercury,
                longitude = 50.0,
                tropical = ZodiacPosition(ZodiacSign.Taurus, 20.0),
                sidereal = ZodiacPosition(ZodiacSign.Taurus, 1.0),
                isRetrograde = true,
                isIngress = false,
            ),
        ),
        planetaryHour = PlanetaryHour(planet = Planet.Venus, dayRuler = Planet.Sun),
        elementBalance = ElementBalance(counts = emptyMap(), dominant = null),
        system = ZodiacSystem.Tropical,
        seasonalMarker = null,
    )

    @Test
    fun `weather pill starts collapsed - no condition label, no humidity`() {
        composeRule.setContent {
            PilgrimTheme {
                WalkVignette(
                    weather = weather,
                    celestial = null,
                    celestialAwarenessEnabled = false,
                    units = UnitSystem.Metric,
                )
            }
        }
        // Collapsed = temperature only. Condition label + humidity hidden.
        composeRule.onNodeWithText("18°").assertExists()
        composeRule.onNodeWithText("Clear", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("62%").assertDoesNotExist()
    }

    @Test
    fun `tapping weather pill expands then collapses`() {
        composeRule.setContent {
            PilgrimTheme {
                WalkVignette(
                    weather = weather,
                    celestial = null,
                    celestialAwarenessEnabled = false,
                    units = UnitSystem.Metric,
                )
            }
        }
        composeRule.onNodeWithText("18°").performClick()
        // Expanded adds humidity % + wind descriptor.
        composeRule.onNodeWithText("62%").assertExists()
        composeRule.onNodeWithText("moderate wind").assertExists()

        composeRule.onNodeWithText("18°").performClick()
        composeRule.onNodeWithText("62%").assertDoesNotExist()
    }

    @Test
    fun `celestial pill starts collapsed - symbols only, no retrograde summary`() {
        composeRule.setContent {
            PilgrimTheme {
                WalkVignette(
                    weather = null,
                    celestial = celestial,
                    celestialAwarenessEnabled = true,
                    units = UnitSystem.Metric,
                )
            }
        }
        // Collapsed = planet symbol + moon glyph. The compact sun-sign /
        // retrograde summary (☿Rx) must be absent.
        composeRule.onNodeWithText("Rx", substring = true).assertDoesNotExist()
    }

    @Test
    fun `tapping celestial pill expands to show retrograde summary`() {
        composeRule.setContent {
            PilgrimTheme {
                WalkVignette(
                    weather = null,
                    celestial = celestial,
                    celestialAwarenessEnabled = true,
                    units = UnitSystem.Metric,
                )
            }
        }
        composeRule.onNodeWithText(Planet.Venus.symbol, substring = true).performClick()
        composeRule.onNodeWithText("Rx", substring = true).assertExists()
    }

    /**
     * On a turning day the celestial chip wears a turning-colored corona
     * (iOS CelestialVignetteView.turningHalo). Robolectric's Canvas is a
     * stub so the border/shadow pixels can't be asserted; this exercises
     * the halo modifier path and proves the chip still composes + shows
     * its symbols when a turning marker is injected.
     */
    @Test
    fun `celestial pill renders with a turning halo injected`() {
        composeRule.setContent {
            PilgrimTheme {
                WalkVignette(
                    weather = null,
                    celestial = celestial,
                    celestialAwarenessEnabled = true,
                    units = UnitSystem.Metric,
                    turning = SeasonalMarker.WinterSolstice,
                )
            }
        }
        composeRule.onNodeWithText(Planet.Venus.symbol, substring = true).assertExists()
    }

    @Test
    fun `celestial pill renders without halo on a non-turning day`() {
        composeRule.setContent {
            PilgrimTheme {
                WalkVignette(
                    weather = null,
                    celestial = celestial,
                    celestialAwarenessEnabled = true,
                    units = UnitSystem.Metric,
                    turning = null,
                )
            }
        }
        composeRule.onNodeWithText(Planet.Venus.symbol, substring = true).assertExists()
    }
}
