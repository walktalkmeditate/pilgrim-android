// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PilgrimWalkTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    @Test
    fun `omits null photos field when serializing`() {
        val walk = sampleWalk(photos = null)
        val encoded = json.encodeToString(PilgrimWalk.serializer(), walk)
        assertFalse("photos key should be absent: $encoded", encoded.contains("\"photos\""))
    }

    @Test
    fun `includes empty photos array when opted in with no pinned`() {
        val walk = sampleWalk(photos = emptyList())
        val encoded = json.encodeToString(PilgrimWalk.serializer(), walk)
        assertTrue("photos key should be present: $encoded", encoded.contains("\"photos\":[]"))
    }

    @Test
    fun `round trips full walk with all optional fields populated`() {
        val original = sampleWalk(
            photos = listOf(
                PilgrimPhoto(
                    localIdentifier = "content://media/external/images/12345",
                    capturedAt = Instant.ofEpochSecond(1_700_000_000),
                    capturedLat = 47.6,
                    capturedLng = -122.3,
                    keptAt = Instant.ofEpochSecond(1_700_000_500),
                    embeddedPhotoFilename = "12345.jpg",
                ),
            ),
            weather = PilgrimWeather(temperature = 18.5, condition = "Clear", humidity = 0.6, windSpeed = 5.0),
        )
        val encoded = json.encodeToString(PilgrimWalk.serializer(), original)
        val decoded = json.decodeFromString(PilgrimWalk.serializer(), encoded)
        assertEquals(original.id, decoded.id)
        assertEquals(original.stats.distance, decoded.stats.distance, 0.001)
        assertEquals(original.weather, decoded.weather)
        assertEquals(1, decoded.photos!!.size)
        assertEquals("12345.jpg", decoded.photos!![0].embeddedPhotoFilename)
    }

    @Test
    fun `pre-reliquary archive without photos field decodes cleanly`() {
        val payload = json.encodeToString(PilgrimWalk.serializer(), sampleWalk(photos = null))
        assertFalse(payload.contains("photos"))
        val decoded = json.decodeFromString(PilgrimWalk.serializer(), payload)
        assertNull(decoded.photos)
    }

    private fun sampleWalk(
        photos: List<PilgrimPhoto>? = null,
        weather: PilgrimWeather? = null,
    ) = PilgrimWalk(
        schemaVersion = "1.0",
        id = "550e8400-e29b-41d4-a716-446655440000",
        type = "walking",
        startDate = Instant.ofEpochSecond(1_700_000_000),
        endDate = Instant.ofEpochSecond(1_700_003_600),
        stats = PilgrimStats(
            distance = 5_000.0,
            steps = 6_500,
            activeDuration = 3_500.0,
            pauseDuration = 100.0,
            ascent = 50.0,
            descent = 45.0,
            burnedEnergy = 350.0,
            talkDuration = 0.0,
            meditateDuration = 600.0,
        ),
        weather = weather,
        route = GeoJsonFeatureCollection(features = emptyList()),
        pauses = emptyList(),
        activities = emptyList(),
        voiceRecordings = emptyList(),
        intention = null,
        reflection = null,
        heartRates = emptyList(),
        workoutEvents = emptyList(),
        favicon = null,
        isRace = false,
        isUserModified = false,
        finishedRecording = true,
        photos = photos,
    )
}
