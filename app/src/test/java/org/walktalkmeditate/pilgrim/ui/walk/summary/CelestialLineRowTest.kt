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
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.ElementBalance
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.core.celestial.PlanetaryHour
import org.walktalkmeditate.pilgrim.core.celestial.PlanetaryPosition
import org.walktalkmeditate.pilgrim.core.celestial.ZodiacPosition
import org.walktalkmeditate.pilgrim.core.celestial.ZodiacSign
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CelestialLineRowTest {

    @get:Rule val composeRule = createComposeRule()

    private fun mkPos(planet: Planet, sign: ZodiacSign) = PlanetaryPosition(
        planet = planet,
        longitude = sign.ordinal0 * 30.0 + 5.0,
        tropical = ZodiacPosition(sign, 5.0),
        sidereal = ZodiacPosition(sign, 5.0),
        isRetrograde = false,
        isIngress = false,
    )

    private fun mkSnapshot(
        moonSign: ZodiacSign? = ZodiacSign.Cancer,
        hourPlanet: Planet = Planet.Mercury,
        dominantEl: ZodiacSign.Element? = ZodiacSign.Element.Fire,
    ): CelestialSnapshot {
        val positions = buildList {
            add(mkPos(Planet.Sun, ZodiacSign.Leo))
            if (moonSign != null) add(mkPos(Planet.Moon, moonSign))
        }
        return CelestialSnapshot(
            positions = positions,
            planetaryHour = PlanetaryHour(planet = hourPlanet, dayRuler = Planet.Sun),
            elementBalance = ElementBalance(
                counts = ZodiacSign.Element.entries.associateWith { 1 },
                dominant = dominantEl,
            ),
            system = ZodiacSystem.Tropical,
            seasonalMarker = null,
        )
    }

    @Test fun complete_snapshot_renders_all_three_texts() {
        composeRule.setContent { PilgrimTheme { CelestialLineRow(snapshot = mkSnapshot()) } }
        composeRule.onNodeWithText("Moon in Cancer").assertIsDisplayed()
        composeRule.onNodeWithText("Hour of Mercury").assertIsDisplayed()
        composeRule.onNodeWithText("Fire predominates").assertIsDisplayed()
    }

    @Test fun tied_element_omits_predominates_text() {
        composeRule.setContent { PilgrimTheme { CelestialLineRow(snapshot = mkSnapshot(dominantEl = null)) } }
        composeRule.onNodeWithText("Moon in Cancer").assertIsDisplayed()
        composeRule.onNodeWithText("Hour of Mercury").assertIsDisplayed()
        composeRule.onNodeWithText("Fire predominates").assertDoesNotExist()
    }

    @Test fun missing_moon_omits_moon_text() {
        composeRule.setContent { PilgrimTheme { CelestialLineRow(snapshot = mkSnapshot(moonSign = null)) } }
        composeRule.onNodeWithText("Moon in Cancer").assertDoesNotExist()
        composeRule.onNodeWithText("Hour of Mercury").assertIsDisplayed()
        composeRule.onNodeWithText("Fire predominates").assertIsDisplayed()
    }

    @Test fun planetary_hour_always_present() {
        composeRule.setContent { PilgrimTheme { CelestialLineRow(snapshot = mkSnapshot(moonSign = null, dominantEl = null)) } }
        composeRule.onNodeWithText("Hour of Mercury").assertIsDisplayed()
    }
}
