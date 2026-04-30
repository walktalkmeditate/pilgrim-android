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
import org.walktalkmeditate.pilgrim.data.walk.WalkDistanceCalculator
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

    @Test
    fun `convertToImport restores Walk metadata + uuid`() {
        val pilgrimUuid = UUID.randomUUID().toString()
        val pilgrimWalk = synthesizePilgrimWalk(uuid = pilgrimUuid)
        val pending = PilgrimPackageConverter.convertToImport(pilgrimWalk)
        assertEquals(pilgrimUuid, pending.walk.uuid)
        assertEquals(0L, pending.walk.id)
        assertEquals(pilgrimWalk.startDate.toEpochMilli(), pending.walk.startTimestamp)
        assertEquals(pilgrimWalk.endDate.toEpochMilli(), pending.walk.endTimestamp)
        assertEquals(pilgrimWalk.intention, pending.walk.intention)
    }

    @Test
    fun `convertToImport reconstructs route samples from LineString feature`() {
        val pilgrimWalk = synthesizePilgrimWalk(
            routeFeatures = listOf(
                org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonFeature(
                    geometry = org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonGeometry(
                        type = "LineString",
                        coordinates = GeoJsonCoordinates.LineString(
                            listOf(
                                listOf(-122.3, 47.6, 50.0),
                                listOf(-122.4, 47.7, 55.0),
                            ),
                        ),
                    ),
                    properties = org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonProperties(
                        timestamps = listOf(Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(2_000)),
                        speeds = listOf(1.5, 2.0),
                    ),
                ),
            ),
        )
        val pending = PilgrimPackageConverter.convertToImport(pilgrimWalk)
        assertEquals(2, pending.routeSamples.size)
        assertEquals(-122.3, pending.routeSamples[0].longitude, 0.001)
        assertEquals(47.6, pending.routeSamples[0].latitude, 0.001)
        assertEquals(50.0, pending.routeSamples[0].altitudeMeters!!, 0.001)
        assertEquals(1_000L, pending.routeSamples[0].timestamp)
        assertEquals(1.5f, pending.routeSamples[0].speedMetersPerSecond!!, 0.001f)
    }

    @Test
    fun `convertToImport extracts waypoints from Point features`() {
        val pilgrimWalk = synthesizePilgrimWalk(
            routeFeatures = listOf(
                org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonFeature(
                    geometry = org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonGeometry(
                        type = "Point",
                        coordinates = GeoJsonCoordinates.Point(listOf(-122.35, 47.65)),
                    ),
                    properties = org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonProperties(
                        markerType = "waypoint",
                        label = "Bridge",
                        icon = "bridge",
                        timestamp = Instant.ofEpochMilli(15_000),
                    ),
                ),
            ),
        )
        val pending = PilgrimPackageConverter.convertToImport(pilgrimWalk)
        assertEquals(1, pending.waypoints.size)
        assertEquals("Bridge", pending.waypoints[0].label)
        assertEquals(15_000L, pending.waypoints[0].timestamp)
    }

    @Test
    fun `convertToImport restores pauses as PAUSED+RESUMED event pairs`() {
        val pilgrimWalk = synthesizePilgrimWalk(
            pauses = listOf(
                org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPause(
                    startDate = Instant.ofEpochMilli(20_000),
                    endDate = Instant.ofEpochMilli(30_000),
                    type = "manual",
                ),
            ),
        )
        val pending = PilgrimPackageConverter.convertToImport(pilgrimWalk)
        assertEquals(2, pending.walkEvents.size)
        assertEquals(WalkEventType.PAUSED, pending.walkEvents[0].eventType)
        assertEquals(20_000L, pending.walkEvents[0].timestamp)
        assertEquals(WalkEventType.RESUMED, pending.walkEvents[1].eventType)
        assertEquals(30_000L, pending.walkEvents[1].timestamp)
    }

    @Test
    fun `convertToImport voice recording duration converts seconds to millis`() {
        val pilgrimWalk = synthesizePilgrimWalk(
            voiceRecordings = listOf(
                org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimVoiceRecording(
                    startDate = Instant.ofEpochMilli(5_000),
                    endDate = Instant.ofEpochMilli(65_000),
                    duration = 60.0,
                    transcription = "hello",
                    wordsPerMinute = 120.0,
                    isEnhanced = false,
                ),
            ),
        )
        val pending = PilgrimPackageConverter.convertToImport(pilgrimWalk)
        assertEquals(1, pending.voiceRecordings.size)
        assertEquals(60_000L, pending.voiceRecordings[0].durationMillis)
        assertEquals("hello", pending.voiceRecordings[0].transcription)
        assertEquals("", pending.voiceRecordings[0].fileRelativePath)
    }

    @Test
    fun `convertToImport respects finishedRecording false`() {
        val pilgrimWalk = synthesizePilgrimWalk(uuid = UUID.randomUUID().toString())
            .copy(finishedRecording = false)
        val pending = PilgrimPackageConverter.convertToImport(pilgrimWalk)
        assertNull(pending.walk.endTimestamp)
    }

    @Test
    fun `convertToImport sets endTimestamp when finishedRecording true`() {
        val pilgrimWalk = synthesizePilgrimWalk(uuid = UUID.randomUUID().toString())
        val pending = PilgrimPackageConverter.convertToImport(pilgrimWalk)
        assertEquals(pilgrimWalk.endDate.toEpochMilli(), pending.walk.endTimestamp)
    }

    @Test
    fun `export distance cache hit equals live compute`() {
        // Same clean walk — cache populated vs cleared. Meditation
        // also asserted invariant across both reads.
        val baseWalk = Walk(
            id = 1,
            uuid = UUID.randomUUID().toString(),
            startTimestamp = 0L,
            endTimestamp = 30 * 60_000L,
        )
        // Three samples ~111m apart at equator → ~222m of route.
        val routeSamples = listOf(
            sample(walkId = 1, timestamp = 0L, lat = 0.0, lng = 0.0),
            sample(walkId = 1, timestamp = 60_000L, lat = 0.0, lng = 0.001),
            sample(walkId = 1, timestamp = 120_000L, lat = 0.0, lng = 0.002),
        )
        val intervals = listOf(
            ActivityInterval(
                walkId = 1,
                startTimestamp = 60_000L,
                endTimestamp = 360_000L,
                activityType = ActivityType.MEDITATING,
            ),
        )
        val liveDistance = WalkDistanceCalculator.computeDistanceMeters(routeSamples)

        val cachedBundle = WalkExportBundle(
            walk = baseWalk.copy(distanceMeters = liveDistance, meditationSeconds = 300L),
            routeSamples = routeSamples,
            altitudeSamples = emptyList(),
            walkEvents = emptyList(),
            activityIntervals = intervals,
            waypoints = emptyList(),
            voiceRecordings = emptyList(),
            walkPhotos = emptyList(),
        )
        val liveBundle = cachedBundle.copy(
            walk = baseWalk.copy(distanceMeters = null, meditationSeconds = null),
        )

        val cachedExport = PilgrimPackageConverter.convert(cachedBundle, includePhotos = false).walk
        val liveExport = PilgrimPackageConverter.convert(liveBundle, includePhotos = false).walk

        assertEquals(cachedExport.stats.distance, liveExport.stats.distance, 0.0001)
        assertEquals(
            cachedExport.stats.meditateDuration,
            liveExport.stats.meditateDuration,
            0.0001,
        )
    }

    @Test
    fun `export corrupt meditation clamped regardless of cache state`() {
        // 30-min walk with a 12-min pause via PAUSED/RESUMED → activeDuration = 18min = 1080s.
        // Corrupt 50-min MEDITATING interval. Both paths must clamp to 1080s.
        val walk = Walk(
            id = 1,
            uuid = UUID.randomUUID().toString(),
            startTimestamp = 0L,
            endTimestamp = 30 * 60_000L,
        )
        val intervals = listOf(
            ActivityInterval(
                walkId = 1,
                startTimestamp = 0L,
                endTimestamp = 50 * 60_000L,
                activityType = ActivityType.MEDITATING,
            ),
        )
        val events = listOf(
            WalkEvent(walkId = 1, timestamp = 5 * 60_000L, eventType = WalkEventType.PAUSED),
            WalkEvent(walkId = 1, timestamp = 17 * 60_000L, eventType = WalkEventType.RESUMED),
        )
        val baseBundle = WalkExportBundle(
            walk = walk,
            routeSamples = emptyList(),
            altitudeSamples = emptyList(),
            walkEvents = events,
            activityIntervals = intervals,
            waypoints = emptyList(),
            voiceRecordings = emptyList(),
            walkPhotos = emptyList(),
        )

        val clearedExport = PilgrimPackageConverter.convert(
            baseBundle.copy(walk = walk.copy(meditationSeconds = null)),
            includePhotos = false,
        ).walk
        val cachedExport = PilgrimPackageConverter.convert(
            baseBundle.copy(walk = walk.copy(meditationSeconds = 1080L)),
            includePhotos = false,
        ).walk

        assertEquals(1080.0, clearedExport.stats.meditateDuration, 0.001)
        assertEquals(1080.0, cachedExport.stats.meditateDuration, 0.001)
    }

    @Test
    fun `convertToImport meditation activity maps to MEDITATING domain enum`() {
        val pilgrimWalk = synthesizePilgrimWalk(
            activities = listOf(
                org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimActivity(
                    type = "meditation",
                    startDate = Instant.ofEpochMilli(10_000),
                    endDate = Instant.ofEpochMilli(70_000),
                ),
            ),
        )
        val pending = PilgrimPackageConverter.convertToImport(pilgrimWalk)
        assertEquals(1, pending.activityIntervals.size)
        assertEquals(ActivityType.MEDITATING, pending.activityIntervals[0].activityType)
    }

    private fun synthesizePilgrimWalk(
        uuid: String = UUID.randomUUID().toString(),
        routeFeatures: List<org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonFeature> = emptyList(),
        pauses: List<org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPause> = emptyList(),
        voiceRecordings: List<org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimVoiceRecording> = emptyList(),
        activities: List<org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimActivity> = emptyList(),
    ) = org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk(
        schemaVersion = "1.0",
        id = uuid,
        type = "walking",
        startDate = Instant.ofEpochMilli(1_000),
        endDate = Instant.ofEpochMilli(100_000),
        stats = org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimStats(
            distance = 0.0, activeDuration = 0.0, pauseDuration = 0.0,
            ascent = 0.0, descent = 0.0, talkDuration = 0.0, meditateDuration = 0.0,
        ),
        weather = null,
        route = org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonFeatureCollection(features = routeFeatures),
        pauses = pauses,
        activities = activities,
        voiceRecordings = voiceRecordings,
        intention = "calm",
        reflection = null,
        heartRates = emptyList(),
        workoutEvents = emptyList(),
        favicon = null,
        isRace = false,
        isUserModified = false,
        finishedRecording = true,
        photos = null,
    )

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
