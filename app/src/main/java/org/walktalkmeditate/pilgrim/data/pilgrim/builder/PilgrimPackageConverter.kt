// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import android.util.Log
import java.time.Instant
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonCoordinates
import org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonFeature
import org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonFeatureCollection
import org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonGeometry
import org.walktalkmeditate.pilgrim.data.pilgrim.GeoJsonProperties
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimActivity
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimCustomPromptStyle
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimEvent
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimManifest
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPause
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimPreferences
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimSchema
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimStats
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimVoiceRecording
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.walk.AltitudeCalculator
import org.walktalkmeditate.pilgrim.data.walk.WalkDistanceCalculator
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

/**
 * Walk → PilgrimWalk export converter. Field-for-field port of iOS
 * `PilgrimPackageConverter.convert(walk:system:celestialEnabled:includePhotos:)`.
 *
 * Most schema fields that iOS reads from the Walk object directly
 * are computed on Android from related entities (distance from
 * route, durations from activity intervals, ascent from altitude).
 * Fields Android doesn't track (weather, heart rates, workout
 * events, reflection) emit as null/empty arrays.
 */
object PilgrimPackageConverter {

    /**
     * Result bundle: the walk JSON + photo skip count from the
     * conversion (photos that couldn't derive GPS from the route).
     * The builder accumulates `skippedCount` across walks for the
     * post-share alert.
     */
    data class ConvertResult(
        val walk: PilgrimWalk,
        val skippedPhotoCount: Int,
    )

    fun convert(bundle: WalkExportBundle, includePhotos: Boolean): ConvertResult {
        val walk = bundle.walk
        val startInstant = Instant.ofEpochMilli(walk.startTimestamp)
        val endTimestamp = walk.endTimestamp ?: walk.startTimestamp
        val endInstant = Instant.ofEpochMilli(endTimestamp)

        val pauses = computePauses(bundle.walkEvents, walkEnd = endInstant)
        val pauseDurationSec = pauses.sumOf { (it.endDate.toEpochMilli() - it.startDate.toEpochMilli()) / 1000.0 }
        val activeDurationSec = ((endTimestamp - walk.startTimestamp) / 1000.0 - pauseDurationSec).coerceAtLeast(0.0)

        val (ascent, descent) = AltitudeCalculator.computeAscentDescent(bundle.altitudeSamples)

        val talkSec = sumActivityDuration(bundle.activityIntervals, ActivityType.TALKING)
        val meditateSec = sumActivityDuration(bundle.activityIntervals, ActivityType.MEDITATING)

        val photoResult = PilgrimPackagePhotoConverter.exportPhotos(
            walkPhotos = bundle.walkPhotos,
            routeSamples = bundle.routeSamples,
            includePhotos = includePhotos,
        )

        val pilgrimWalk = PilgrimWalk(
            schemaVersion = PilgrimSchema.VERSION,
            id = walk.uuid,
            type = "walking",
            startDate = startInstant,
            endDate = endInstant,
            stats = PilgrimStats(
                distance = WalkDistanceCalculator.computeDistanceMeters(bundle.routeSamples),
                steps = null,
                activeDuration = activeDurationSec,
                pauseDuration = pauseDurationSec,
                ascent = ascent,
                descent = descent,
                burnedEnergy = null,
                talkDuration = talkSec,
                meditateDuration = meditateSec,
            ),
            weather = null,
            route = buildRouteGeoJson(bundle.routeSamples, bundle.waypoints),
            pauses = pauses,
            activities = bundle.activityIntervals.map { it.toPilgrimActivity() },
            voiceRecordings = bundle.voiceRecordings.map { it.toPilgrimVoiceRecording() },
            intention = walk.intention,
            reflection = null,
            heartRates = emptyList(),
            workoutEvents = emptyList(),
            favicon = walk.favicon,
            isRace = false,
            isUserModified = false,
            finishedRecording = walk.endTimestamp != null,
            photos = photoResult.photos,
        )
        return ConvertResult(walk = pilgrimWalk, skippedPhotoCount = photoResult.skippedCount)
    }

    /**
     * Build the route GeoJSON. One LineString feature aggregating all
     * route samples (with parallel timestamp/speed/direction/accuracy
     * arrays). One Point feature per waypoint (lat/lng + label/icon
     * properties).
     */
    fun buildRouteGeoJson(
        routeSamples: List<RouteDataSample>,
        waypoints: List<Waypoint>,
    ): GeoJsonFeatureCollection {
        val features = mutableListOf<GeoJsonFeature>()

        if (routeSamples.isNotEmpty()) {
            val coords = routeSamples.map { sample ->
                listOf(sample.longitude, sample.latitude, sample.altitudeMeters ?: 0.0)
            }
            val timestamps = routeSamples.map { Instant.ofEpochMilli(it.timestamp) }
            val speeds = routeSamples.map { (it.speedMetersPerSecond ?: -1f).toDouble() }
            val directions = routeSamples.map { (it.directionDegrees ?: -1f).toDouble() }
            val hAccuracies = routeSamples.map { (it.horizontalAccuracyMeters ?: 0f).toDouble() }
            val vAccuracies = routeSamples.map { (it.verticalAccuracyMeters ?: 0f).toDouble() }
            features += GeoJsonFeature(
                geometry = GeoJsonGeometry(
                    type = "LineString",
                    coordinates = GeoJsonCoordinates.LineString(coords),
                ),
                properties = GeoJsonProperties(
                    timestamps = timestamps,
                    speeds = speeds,
                    directions = directions,
                    horizontalAccuracies = hAccuracies,
                    verticalAccuracies = vAccuracies,
                ),
            )
        }

        for (waypoint in waypoints) {
            features += GeoJsonFeature(
                geometry = GeoJsonGeometry(
                    type = "Point",
                    coordinates = GeoJsonCoordinates.Point(listOf(waypoint.longitude, waypoint.latitude)),
                ),
                properties = GeoJsonProperties(
                    markerType = "waypoint",
                    label = waypoint.label,
                    icon = waypoint.icon,
                    timestamp = Instant.ofEpochMilli(waypoint.timestamp),
                ),
            )
        }

        return GeoJsonFeatureCollection(features = features)
    }

    /**
     * Build the manifest. Preferences come from the prefs reads the
     * caller passes in (the converter stays pure; the builder hops
     * into Hilt-injected repos).
     *
     * Android has no `customPromptStyles` storage, no
     * `IntentionHistoryStore`, no `Event` calendar surface — emit
     * empty arrays for all three. iOS imports tolerate the missing
     * fields silently.
     */
    fun buildManifest(
        appVersion: String,
        walkCount: Int,
        distanceUnits: UnitSystem,
        celestialAwareness: Boolean,
        zodiacSystem: String,
        beginWithIntention: Boolean,
        exportInstant: Instant = Instant.now(),
    ): PilgrimManifest {
        val (distanceUnit, altitudeUnit, speedUnit) = when (distanceUnits) {
            UnitSystem.Metric -> Triple("km", "m", "km/h")
            UnitSystem.Imperial -> Triple("mi", "ft", "mph")
        }
        return PilgrimManifest(
            schemaVersion = PilgrimSchema.VERSION,
            exportDate = exportInstant,
            appVersion = appVersion,
            walkCount = walkCount,
            preferences = PilgrimPreferences(
                distanceUnit = distanceUnit,
                altitudeUnit = altitudeUnit,
                speedUnit = speedUnit,
                energyUnit = "kcal",
                celestialAwareness = celestialAwareness,
                zodiacSystem = zodiacSystem,
                beginWithIntention = beginWithIntention,
            ),
            customPromptStyles = emptyList<PilgrimCustomPromptStyle>(),
            intentions = emptyList<String>(),
            events = emptyList<PilgrimEvent>(),
        )
    }

    // MARK: - Reverse Conversion (Import)

    /**
     * Decode an imported [PilgrimWalk] into a [PendingImport] bundle.
     * Caller (importer) runs the actual Room inserts inside a
     * `database.withTransaction { ... }` block, captures the
     * autoGenerated walk id, and rewrites the child entities'
     * `walkId` before bulk-inserting.
     *
     * Drops on import (silently logged in caller):
     * - `weather` — Android Walk has no weather columns.
     * - `reflection` — Android has no reflection storage.
     * - `heartRates` — no HeartRateSample entity.
     * - `workoutEvents` — Android `WalkEventType` doesn't model
     *    iOS lap/marker/segment.
     * - `isRace`, `isUserModified` — no schema columns.
     * - `intentions` from manifest, `events` from manifest,
     *   `customPromptStyles` from manifest — all dropped.
     */
    fun convertToImport(pilgrim: PilgrimWalk): PendingImport {
        val walk = Walk(
            id = 0L,
            uuid = pilgrim.id,
            startTimestamp = pilgrim.startDate.toEpochMilli(),
            endTimestamp = pilgrim.endDate.toEpochMilli(),
            intention = pilgrim.intention,
            favicon = pilgrim.favicon,
            notes = null,
        )

        val (routeSamples, waypoints) = decomposeRoute(pilgrim.route, walkStart = pilgrim.startDate)
        val activityIntervals = pilgrim.activities.map { activity ->
            ActivityInterval(
                walkId = 0L,
                startTimestamp = activity.startDate.toEpochMilli(),
                endTimestamp = activity.endDate.toEpochMilli(),
                activityType = pilgrimActivityToDomain(activity.type),
            )
        }
        val voiceRecordings = pilgrim.voiceRecordings.map { vr ->
            val startMs = vr.startDate.toEpochMilli()
            val endMs = vr.endDate.toEpochMilli()
            VoiceRecording(
                walkId = 0L,
                startTimestamp = startMs,
                endTimestamp = endMs,
                durationMillis = endMs - startMs,
                fileRelativePath = "",
                transcription = vr.transcription,
                wordsPerMinute = vr.wordsPerMinute,
                isEnhanced = vr.isEnhanced,
            )
        }
        val walkEvents = pilgrim.pauses.flatMap { pause ->
            listOf(
                WalkEvent(
                    walkId = 0L,
                    timestamp = pause.startDate.toEpochMilli(),
                    eventType = WalkEventType.PAUSED,
                ),
                WalkEvent(
                    walkId = 0L,
                    timestamp = pause.endDate.toEpochMilli(),
                    eventType = WalkEventType.RESUMED,
                ),
            )
        }
        val walkPhotos = PilgrimPackagePhotoConverter.importPhotos(exported = pilgrim.photos)

        return PendingImport(
            walk = walk,
            routeSamples = routeSamples,
            walkEvents = walkEvents,
            activityIntervals = activityIntervals,
            waypoints = waypoints,
            voiceRecordings = voiceRecordings,
            walkPhotos = walkPhotos,
        )
    }

    private fun decomposeRoute(
        collection: GeoJsonFeatureCollection,
        walkStart: Instant,
    ): Pair<List<RouteDataSample>, List<Waypoint>> {
        val routeSamples = mutableListOf<RouteDataSample>()
        val waypoints = mutableListOf<Waypoint>()

        for (feature in collection.features) {
            when (feature.geometry.type) {
                "LineString" -> {
                    val coords = feature.geometry.coordinates as? GeoJsonCoordinates.LineString
                        ?: continue
                    val timestamps = feature.properties.timestamps ?: emptyList()
                    val speeds = feature.properties.speeds ?: emptyList()
                    val directions = feature.properties.directions ?: emptyList()
                    val hAccuracies = feature.properties.horizontalAccuracies ?: emptyList()
                    val vAccuracies = feature.properties.verticalAccuracies ?: emptyList()

                    for ((index, coord) in coords.coords.withIndex()) {
                        val lng = coord.getOrElse(0) { 0.0 }
                        val lat = coord.getOrElse(1) { 0.0 }
                        val alt = coord.getOrNull(2)
                        val ts = timestamps.getOrNull(index)?.toEpochMilli() ?: walkStart.toEpochMilli()
                        routeSamples += RouteDataSample(
                            walkId = 0L,
                            timestamp = ts,
                            latitude = lat,
                            longitude = lng,
                            altitudeMeters = alt,
                            horizontalAccuracyMeters = hAccuracies.getOrNull(index)?.toFloat(),
                            verticalAccuracyMeters = vAccuracies.getOrNull(index)?.toFloat(),
                            speedMetersPerSecond = speeds.getOrNull(index)?.toFloat(),
                            directionDegrees = directions.getOrNull(index)?.toFloat(),
                        )
                    }
                }
                "Point" -> {
                    if (feature.properties.markerType != "waypoint") continue
                    val coords = feature.geometry.coordinates as? GeoJsonCoordinates.Point
                        ?: continue
                    val lng = coords.coords.getOrElse(0) { 0.0 }
                    val lat = coords.coords.getOrElse(1) { 0.0 }
                    val ts = feature.properties.timestamp?.toEpochMilli() ?: walkStart.toEpochMilli()
                    waypoints += Waypoint(
                        walkId = 0L,
                        timestamp = ts,
                        latitude = lat,
                        longitude = lng,
                        label = feature.properties.label,
                        icon = feature.properties.icon,
                    )
                }
            }
        }
        return routeSamples to waypoints
    }

    private fun pilgrimActivityToDomain(type: String): ActivityType = when (type) {
        "meditation" -> ActivityType.MEDITATING
        // iOS schema's "unknown" was originally TALKING or WALKING on
        // Android; we can't recover the distinction from the wire.
        // Default to MEDITATING-vs-WALKING by absence: anything not
        // "meditation" maps to WALKING (since talkDuration on imported
        // walks is 0 — iOS doesn't track talk duration via activity
        // intervals).
        else -> ActivityType.WALKING
    }

    private fun computePauses(events: List<WalkEvent>, walkEnd: Instant): List<PilgrimPause> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.timestamp }
        val pauses = mutableListOf<PilgrimPause>()
        var pendingPauseAt: Long? = null
        for (event in sorted) {
            when (event.eventType) {
                WalkEventType.PAUSED -> {
                    if (pendingPauseAt != null) {
                        Log.w(TAG, "Consecutive PAUSED events at ${event.timestamp}; ignoring duplicate")
                    } else {
                        pendingPauseAt = event.timestamp
                    }
                }
                WalkEventType.RESUMED -> {
                    val pausedAt = pendingPauseAt
                    if (pausedAt == null) {
                        Log.w(TAG, "RESUMED event with no pending PAUSED at ${event.timestamp}; ignoring")
                    } else {
                        pauses += PilgrimPause(
                            startDate = Instant.ofEpochMilli(pausedAt),
                            endDate = Instant.ofEpochMilli(event.timestamp),
                            type = "manual",
                        )
                        pendingPauseAt = null
                    }
                }
                else -> { /* not a pause/resume — ignore */ }
            }
        }
        pendingPauseAt?.let { pausedAt ->
            pauses += PilgrimPause(
                startDate = Instant.ofEpochMilli(pausedAt),
                endDate = walkEnd,
                type = "manual",
            )
        }
        return pauses
    }

    private fun sumActivityDuration(intervals: List<ActivityInterval>, type: ActivityType): Double =
        intervals.filter { it.activityType == type }
            .sumOf { (it.endTimestamp - it.startTimestamp) / 1000.0 }

    private fun ActivityInterval.toPilgrimActivity(): PilgrimActivity {
        val type = when (activityType) {
            ActivityType.MEDITATING -> "meditation"
            ActivityType.TALKING -> "unknown"
            ActivityType.WALKING -> "unknown"
        }
        return PilgrimActivity(
            type = type,
            startDate = Instant.ofEpochMilli(startTimestamp),
            endDate = Instant.ofEpochMilli(endTimestamp),
        )
    }

    private fun VoiceRecording.toPilgrimVoiceRecording(): PilgrimVoiceRecording =
        PilgrimVoiceRecording(
            startDate = Instant.ofEpochMilli(startTimestamp),
            endDate = Instant.ofEpochMilli(endTimestamp),
            duration = durationMillis / 1000.0,
            transcription = transcription,
            wordsPerMinute = wordsPerMinute,
            isEnhanced = isEnhanced,
        )

    private const val TAG = "PilgrimPackageConverter"
}
