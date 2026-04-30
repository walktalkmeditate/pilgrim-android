// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JUnit. `WeatherCondition.fromRawValue` is a pure lookup over
 * iOS-faithful raw values (`clear`, `partlyCloudy`, …) used by the
 * Stage 12-A migration column `weather_condition`.
 */
class WeatherConditionTest {

    @Test
    fun fromRawValue_resolvesAll10Cases() {
        assertEquals(WeatherCondition.CLEAR, WeatherCondition.fromRawValue("clear"))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, WeatherCondition.fromRawValue("partlyCloudy"))
        assertEquals(WeatherCondition.OVERCAST, WeatherCondition.fromRawValue("overcast"))
        assertEquals(WeatherCondition.LIGHT_RAIN, WeatherCondition.fromRawValue("lightRain"))
        assertEquals(WeatherCondition.HEAVY_RAIN, WeatherCondition.fromRawValue("heavyRain"))
        assertEquals(WeatherCondition.THUNDERSTORM, WeatherCondition.fromRawValue("thunderstorm"))
        assertEquals(WeatherCondition.SNOW, WeatherCondition.fromRawValue("snow"))
        assertEquals(WeatherCondition.FOG, WeatherCondition.fromRawValue("fog"))
        assertEquals(WeatherCondition.WIND, WeatherCondition.fromRawValue("wind"))
        assertEquals(WeatherCondition.HAZE, WeatherCondition.fromRawValue("haze"))
    }

    @Test
    fun fromRawValue_unknownReturnsNull() {
        assertNull(WeatherCondition.fromRawValue("rainOfFrogs"))
        assertNull(WeatherCondition.fromRawValue(null))
    }
}
