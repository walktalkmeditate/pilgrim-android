// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonCoordinates
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

class PilgrimPackageConverterTest {

    @Test
    fun `convert builds PilgrimWalk with computed stats`() {
        val walkUuid = UUID.randomUUID().toString()
        val bundle = WalkExportBundle(
            walk = Walk(id = 1, uuid = walkUuid, startTimestamp = 1_000, endTimestamp = 1_000_000, intention = "calm"),
            routeSamples = listOf(
                sample(walkId = 1, timestamp = 1_000, lat = 0.0, lng = 0.0),
                sample(walkId = 1, timestamp = 60_000, lat = 0.001, lng = 0.0),
            ),
            altitudeSamples = listOf(
                AltitudeSample(walkId = 1, timestamp = 1_000, altitudeMeters = 100.0),
                AltitudeSample(walkId = 1, timestamp = 60_000, altitudeMeters = 110.0),
            ),
            walkEvents = emptyList(),
            activityIntervals = listOf(
                ActivityInterval(walkId = 1, startTimestamp = 100_000, endTimestamp = 700_000, activityType = ActivityType.MEDITATING),
                ActivityInterval(walkId = 1, startTimestamp = 700_000, endTimestamp = 800_000, activityType = ActivityType.TALKING),
            ),
            waypoints = emptyList(),
            voiceRecordings = emptyList(),
            walkPhotos = emptyList(),
        )
        val result = PilgrimPackageConverter.convert(bundle, includePhotos = false)
        val walk = result.walk
        assertEquals(walkUuid, walk.id)
        assertEquals("walking", walk.type)
        assertEquals(Instant.ofEpochMilli(1_000), walk.startDate)
        assertEquals(Instant.ofEpochMilli(1_000_000), walk.endDate)
        assertTrue("distance ${walk.stats.distance}", kotlin.math.abs(walk.stats.distance - 111.0) < 5.0)
        assertEquals(10.0, walk.stats.ascent, 0.001)
        assertEquals(0.0, walk.stats.descent, 0.001)
        assertEquals(600.0, walk.stats.meditateDuration, 0.001)
        assertEquals(100.0, walk.stats.talkDuration, 0.001)
        assertEquals("calm", walk.intention)
        assertNull(walk.photos)
        assertNull(walk.weather)
        assertNull(walk.reflection)
        assertEquals(0, walk.heartRates.size)
        assertEquals(0, walk.workoutEvents.size)
        assertTrue(walk.finishedRecording)
    }

    @Test
    fun `convert with no end timestamp treats as zero-duration walk`() {
        val walkUuid = UUID.randomUUID().toString()
        val bundle = emptyBundle().copy(
            walk = Walk(id = 1, uuid = walkUuid, startTimestamp = 1_000, endTimestamp = null),
        )
        val walk = PilgrimPackageConverter.convert(bundle, includePhotos = false).walk
        assertEquals(walk.startDate, walk.endDate)
        assertEquals(0.0, walk.stats.activeDuration, 0.001)
    }

    @Test
    fun `pause pairing closes dangling pause at walk end`() {
        val bundle = emptyBundle().copy(
            walk = Walk(id = 1, uuid = UUID.randomUUID().toString(), startTimestamp = 1_000, endTimestamp = 100_000),
            walkEvents = listOf(
                WalkEvent(walkId = 1, timestamp = 50_000, eventType = WalkEventType.PAUSED),
            ),
        )
        val walk = PilgrimPackageConverter.convert(bundle, includePhotos = false).walk
        assertEquals(1, walk.pauses.size)
        assertEquals(Instant.ofEpochMilli(50_000), walk.pauses[0].startDate)
        assertEquals(Instant.ofEpochMilli(100_000), walk.pauses[0].endDate)
        assertEquals("manual", walk.pauses[0].type)
        assertEquals(50.0, walk.stats.pauseDuration, 0.001)
        assertEquals(49.0, walk.stats.activeDuration, 0.001)
    }

    @Test
    fun `pause pairing pairs each PAUSED with next RESUMED`() {
        val bundle = emptyBundle().copy(
            walk = Walk(id = 1, uuid = UUID.randomUUID().toString(), startTimestamp = 1_000, endTimestamp = 100_000),
            walkEvents = listOf(
                WalkEvent(walkId = 1, timestamp = 20_000, eventType = WalkEventType.PAUSED),
                WalkEvent(walkId = 1, timestamp = 30_000, eventType = WalkEventType.RESUMED),
                WalkEvent(walkId = 1, timestamp = 60_000, eventType = WalkEventType.PAUSED),
                WalkEvent(walkId = 1, timestamp = 70_000, eventType = WalkEventType.RESUMED),
            ),
        )
        val walk = PilgrimPackageConverter.convert(bundle, includePhotos = false).walk
        assertEquals(2, walk.pauses.size)
        assertEquals(20.0, walk.stats.pauseDuration, 0.001)
    }

    @Test
    fun `route GeoJSON has LineString and waypoint Points`() {
        val bundle = emptyBundle().copy(
            walk = Walk(id = 1, uuid = UUID.randomUUID().toString(), startTimestamp = 1_000, endTimestamp = 60_000),
            routeSamples = listOf(
                sample(walkId = 1, timestamp = 1_000, lat = 47.6, lng = -122.3),
                sample(walkId = 1, timestamp = 30_000, lat = 47.7, lng = -122.4),
            ),
            waypoints = listOf(
                Waypoint(walkId = 1, timestamp = 15_000, latitude = 47.65, longitude = -122.35, label = "Bridge", icon = "bridge"),
            ),
        )
        val walk = PilgrimPackageConverter.convert(bundle, includePhotos = false).walk
        val features = walk.route.features
        assertEquals(2, features.size)
        assertEquals("LineString", features[0].geometry.type)
        assertTrue(features[0].geometry.coordinates is GeoJsonCoordinates.LineString)
        assertEquals("Point", features[1].geometry.type)
        assertEquals("waypoint", features[1].properties.markerType)
        assertEquals("Bridge", features[1].properties.label)
    }

    @Test
    fun `voice recording duration converts millis to seconds`() {
        val bundle = emptyBundle().copy(
            walk = Walk(id = 1, uuid = UUID.randomUUID().toString(), startTimestamp = 1_000, endTimestamp = 100_000),
            voiceRecordings = listOf(
                VoiceRecording(
                    walkId = 1,
                    startTimestamp = 5_000,
                    endTimestamp = 65_000,
                    durationMillis = 60_000,
                    fileRelativePath = "walks/1/rec.wav",
                    transcription = "hello",
                    wordsPerMinute = 120.5,
                ),
            ),
        )
        val walk = PilgrimPackageConverter.convert(bundle, includePhotos = false).walk
        assertEquals(1, walk.voiceRecordings.size)
        assertEquals(60.0, walk.voiceRecordings[0].duration, 0.001)
        assertEquals("hello", walk.voiceRecordings[0].transcription)
        assertEquals(120.5, walk.voiceRecordings[0].wordsPerMinute!!, 0.001)
    }

    @Test
    fun `talking activity maps to unknown wire type but counts toward talkDuration`() {
        val bundle = emptyBundle().copy(
            walk = Walk(id = 1, uuid = UUID.randomUUID().toString(), startTimestamp = 1_000, endTimestamp = 100_000),
            activityIntervals = listOf(
                ActivityInterval(walkId = 1, startTimestamp = 10_000, endTimestamp = 70_000, activityType = ActivityType.TALKING),
            ),
        )
        val walk = PilgrimPackageConverter.convert(bundle, includePhotos = false).walk
        assertEquals(1, walk.activities.size)
        assertEquals("unknown", walk.activities[0].type)
        assertEquals(60.0, walk.stats.talkDuration, 0.001)
    }

    @Test
    fun `buildManifest emits metric units and empty arrays`() {
        val manifest = PilgrimPackageConverter.buildManifest(
            appVersion = "0.1.0",
            walkCount = 5,
            distanceUnits = UnitSystem.Metric,
            celestialAwareness = true,
            zodiacSystem = "tropical",
            beginWithIntention = false,
            exportInstant = Instant.ofEpochSecond(1_700_000_000),
        )
        assertEquals("1.0", manifest.schemaVersion)
        assertEquals("0.1.0", manifest.appVersion)
        assertEquals(5, manifest.walkCount)
        assertEquals("km", manifest.preferences.distanceUnit)
        assertEquals("m", manifest.preferences.altitudeUnit)
        assertEquals("km/h", manifest.preferences.speedUnit)
        assertEquals("kcal", manifest.preferences.energyUnit)
        assertTrue(manifest.preferences.celestialAwareness)
        assertEquals("tropical", manifest.preferences.zodiacSystem)
        assertTrue(manifest.customPromptStyles.isEmpty())
        assertTrue(manifest.intentions.isEmpty())
        assertTrue(manifest.events.isEmpty())
    }

    @Test
    fun `buildManifest emits imperial units when chosen`() {
        val manifest = PilgrimPackageConverter.buildManifest(
            appVersion = "0.1.0",
            walkCount = 0,
            distanceUnits = UnitSystem.Imperial,
            celestialAwareness = false,
            zodiacSystem = "sidereal",
            beginWithIntention = true,
        )
        assertEquals("mi", manifest.preferences.distanceUnit)
        assertEquals("ft", manifest.preferences.altitudeUnit)
        assertEquals("mph", manifest.preferences.speedUnit)
        assertEquals("kcal", manifest.preferences.energyUnit)
    }

    private fun sample(walkId: Long, timestamp: Long, lat: Double, lng: Double) = RouteDataSample(
        walkId = walkId, timestamp = timestamp, latitude = lat, longitude = lng,
    )

    private fun emptyBundle() = WalkExportBundle(
        walk = Walk(id = 1, uuid = UUID.randomUUID().toString(), startTimestamp = 1_000, endTimestamp = 100_000),
        routeSamples = emptyList(),
        altitudeSamples = emptyList(),
        walkEvents = emptyList(),
        activityIntervals = emptyList(),
        waypoints = emptyList(),
        voiceRecordings = emptyList(),
        walkPhotos = emptyList(),
    )
}
