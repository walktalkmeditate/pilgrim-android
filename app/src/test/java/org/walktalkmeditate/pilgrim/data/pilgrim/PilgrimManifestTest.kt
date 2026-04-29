// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PilgrimManifestTest {

    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Test
    fun `round trips manifest with empty arrays for Android-unsupported surfaces`() {
        val original = PilgrimManifest(
            schemaVersion = "1.0",
            exportDate = Instant.ofEpochSecond(1_700_000_000),
            appVersion = "0.1.0",
            walkCount = 23,
            preferences = PilgrimPreferences(
                distanceUnit = "km",
                altitudeUnit = "m",
                speedUnit = "km/h",
                energyUnit = "kcal",
                celestialAwareness = true,
                zodiacSystem = "tropical",
                beginWithIntention = false,
            ),
            customPromptStyles = emptyList(),
            intentions = emptyList(),
            events = emptyList(),
        )
        val encoded = json.encodeToString(PilgrimManifest.serializer(), original)
        val decoded = json.decodeFromString(PilgrimManifest.serializer(), encoded)
        assertEquals(original, decoded)
    }
}
