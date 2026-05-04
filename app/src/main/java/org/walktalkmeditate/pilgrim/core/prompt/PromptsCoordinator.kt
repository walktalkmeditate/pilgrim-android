// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import android.content.Context
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshotCalc
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.core.prompt.voices.CustomPromptStyleVoice
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.haversineMeters

/**
 * Single-entry-point facade for the Stage 13-XZ AI Prompts surface.
 *
 * Aggregates every dependency the post-walk prompts pipeline needs —
 * [WalkRepository] sub-fetches, [CustomPromptStyleStore] hot StateFlow,
 * [PhotoContextAnalyzer] (cached), [PromptGeocoder] (rate-limited),
 * [PromptGenerator] (built-in + custom voices), and the practice / units
 * preference reads — so the consuming `WalkSummaryViewModel` (Task 12)
 * gains one constructor parameter rather than six.
 *
 * `buildContext` orchestrates the full [ActivityContext] in parallel
 * via `coroutineScope` + `async`, then runs geocoding (rate-limited),
 * photo analysis (cached), and recent-walk-snippet aggregation. Per-walk
 * celestial / lunar / weather fields are pre-formatted inline so the
 * downstream [PromptAssembler] stays a pure-string composer.
 *
 * `generateAll` returns the six built-in prompts plus one prompt per
 * persisted [CustomPromptStyle], in the order the styles store reports
 * them.
 *
 * **Custom-icon resolution.** Stage 13-XZ Task 16 owns the Material
 * icon table that maps a [CustomPromptStyle.icon] key to an
 * [ImageVector]. Until that lands, this coordinator stubs the resolver
 * with a single neutral pencil icon. Task 12 will inject the real
 * lookup at the UI seam if it wants per-style icons; the stub is
 * harmless because Task 10 callers consume the prompt's [text] field,
 * not its icon.
 */
@Singleton
class PromptsCoordinator @Inject constructor(
    private val repository: WalkRepository,
    private val customStyleStore: CustomPromptStyleStore,
    private val photoContextAnalyzer: PhotoContextAnalyzer,
    private val geocoder: PromptGeocoder,
    private val promptGenerator: PromptGenerator,
    private val practicePreferences: PracticePreferencesRepository,
    private val unitsPreferences: UnitsPreferencesRepository,
    @ApplicationContext private val appContext: Context,
) {

    /** Hot StateFlow surface from the underlying store. */
    val customStyles: StateFlow<List<CustomPromptStyle>> get() = customStyleStore.styles

    /**
     * Build the full [ActivityContext] for [walkId] — orchestrates every
     * sub-fetch (location samples, recordings, intervals, waypoints,
     * photos, photo analysis, geocoding, recent-walk snippets, celestial
     * snapshot, lunar phase) and pre-formats the weather string.
     *
     * Returns null when no walk row exists for [walkId].
     */
    suspend fun buildContext(walkId: Long, zone: ZoneId = ZoneId.systemDefault()): ActivityContext? {
        val walk = repository.getWalk(walkId) ?: return null

        val fetches = coroutineScope {
            val samplesAsync = async { repository.locationSamplesFor(walkId) }
            val recordingsAsync = async { repository.voiceRecordingsFor(walkId) }
            val intervalsAsync = async { repository.activityIntervalsFor(walkId) }
            val waypointsAsync = async { repository.waypointsFor(walkId) }
            val photosAsync = async { repository.photosFor(walkId) }
            val recentAsync = async {
                repository.recentFinishedWalksBefore(walk.startTimestamp, RECENT_WALKS_LOOKBACK)
            }
            SubFetches(
                locationSamples = samplesAsync.await(),
                recordings = recordingsAsync.await(),
                intervals = intervalsAsync.await(),
                waypoints = waypointsAsync.await(),
                photos = photosAsync.await(),
                recentWalks = recentAsync.await(),
            )
        }
        val locationSamples = fetches.locationSamples
        val recordings = fetches.recordings
        val intervals = fetches.intervals
        val waypoints = fetches.waypoints
        val photos = fetches.photos
        val recentWalksRaw = fetches.recentWalks

        val routeSamples = locationSamples.toRouteSamples()
        val placeNames = geocodePlaceNames(locationSamples)
        val photoContexts = analyzePhotos(photos, routeSamples)
        val meditationContexts = intervals
            .filter { it.activityType == ActivityType.MEDITATING }
            .map { interval ->
                MeditationContext(
                    startDate = interval.startTimestamp,
                    endDate = interval.endTimestamp,
                    durationSeconds = (interval.endTimestamp - interval.startTimestamp) / 1000L,
                )
            }
        val recordingContexts = recordings.map { recording ->
            RecordingContext(
                uuid = recording.uuid,
                timestamp = recording.startTimestamp,
                startCoordinate = RouteSampleProjection.closestCoordinate(routeSamples, recording.startTimestamp),
                endCoordinate = RouteSampleProjection.closestCoordinate(routeSamples, recording.endTimestamp),
                wordsPerMinute = recording.wordsPerMinute,
                text = recording.transcription.orEmpty(),
            )
        }
        val waypointContexts = waypoints.mapNotNull { wp ->
            val label = wp.label ?: return@mapNotNull null
            WaypointContext(
                label = label,
                icon = wp.icon,
                timestamp = wp.timestamp,
                coordinate = LatLng(wp.latitude, wp.longitude),
            )
        }
        val recentSnippets = recentWalkSnippets(recentWalksRaw, zone)
        val celestialEnabled = practicePreferences.celestialAwarenessEnabled.value
        val zodiacSystem = practicePreferences.zodiacSystem.value
        val celestial = if (celestialEnabled) {
            CelestialSnapshotCalc.snapshot(walk.startTimestamp, zone, zodiacSystem)
        } else {
            null
        }
        val lunarPhase = MoonCalc.moonPhase(Instant.ofEpochMilli(walk.startTimestamp))
        val imperial = unitsPreferences.distanceUnits.value == UnitSystem.Imperial
        val weather = ContextFormatter.formatWeather(walk, weatherLabelResolver(), imperial)
        val routeSpeeds = computeRouteSpeeds(locationSamples)

        return ActivityContext(
            recordings = recordingContexts,
            meditations = meditationContexts,
            durationSeconds = (walk.endTimestamp ?: walk.startTimestamp).let { end ->
                ((end - walk.startTimestamp) / 1000L).coerceAtLeast(0L)
            },
            distanceMeters = walk.distanceMeters ?: 0.0,
            startTimestamp = walk.startTimestamp,
            placeNames = placeNames,
            routeSpeeds = routeSpeeds,
            recentWalkSnippets = recentSnippets,
            intention = walk.intention,
            waypoints = waypointContexts,
            weather = weather,
            lunarPhase = lunarPhase,
            celestial = celestial,
            photoContexts = photoContexts,
            narrativeArc = if (photoContexts.isEmpty()) null else NarrativeArc.EMPTY,
        )
    }

    /**
     * Convenience: build the context and render every built-in prompt
     * plus one per persisted custom style. Returns empty when no walk
     * matches [walkId].
     */
    suspend fun generateAll(walkId: Long, zone: ZoneId = ZoneId.systemDefault()): List<GeneratedPrompt> {
        val context = buildContext(walkId, zone) ?: return emptyList()
        val imperial = unitsPreferences.distanceUnits.value == UnitSystem.Imperial
        val weatherLabel = weatherLabelResolver()
        val builtins = promptGenerator.generateAll(
            activityContext = context,
            imperial = imperial,
            weatherLabel = weatherLabel,
            zone = zone,
        )
        val customs = customStyleStore.styles.value.map { custom ->
            promptGenerator.generateCustom(
                customStyle = custom,
                activityContext = context,
                imperial = imperial,
                customIconResolver = ::resolveCustomIcon,
                weatherLabel = weatherLabel,
                zone = zone,
            )
        }
        return builtins + customs
    }

    suspend fun saveCustomStyle(style: CustomPromptStyle) = customStyleStore.save(style)

    suspend fun deleteCustomStyle(style: CustomPromptStyle) = customStyleStore.delete(style)

    /** Wraps a custom style as a [WalkPromptVoice] for callers that want to assemble manually. */
    fun customVoiceFor(style: CustomPromptStyle): WalkPromptVoice = CustomPromptStyleVoice(style)

    private suspend fun geocodePlaceNames(samples: List<RouteDataSample>): List<PlaceContext> {
        val start = samples.firstOrNull() ?: return emptyList()
        val end = samples.lastOrNull()
        val startCoord = LatLng(start.latitude, start.longitude)
        val startPlace = geocoder.geocodeStart(startCoord)
        val endPlace = if (end != null && end !== start) {
            val distance = haversineMeters(start.toLocationPoint(), end.toLocationPoint())
            geocoder.geocodeEnd(LatLng(end.latitude, end.longitude), distance)
        } else {
            null
        }
        return listOfNotNull(startPlace, endPlace)
    }

    private suspend fun analyzePhotos(
        photos: List<WalkPhoto>,
        samples: List<RouteSample>,
    ): List<PhotoContextEntry> = coroutineScope {
        photos.mapIndexed { idx, photo ->
            idx to async { photoContextAnalyzer.analyze(Uri.parse(photo.photoUri)) }
        }.map { (idx, deferred) ->
            val photo = photos[idx]
            val anchorMs = photo.takenAt ?: photo.pinnedAt
            PhotoContextEntry(
                index = idx,
                distanceIntoWalkMeters = RouteSampleProjection.distanceAtTimestamp(samples, anchorMs),
                time = anchorMs,
                coordinate = RouteSampleProjection.closestCoordinate(samples, anchorMs),
                context = deferred.await(),
            )
        }
    }

    private suspend fun recentWalkSnippets(
        recentWalks: List<Walk>,
        zone: ZoneId,
    ): List<WalkSnippet> {
        if (recentWalks.isEmpty()) return emptyList()
        val celestialEnabled = practicePreferences.celestialAwarenessEnabled.value
        val zodiacSystem = practicePreferences.zodiacSystem.value
        val snippets = mutableListOf<WalkSnippet>()
        for (walk in recentWalks) {
            if (snippets.size == MAX_RECENT_SNIPPETS) break
            val recordings = repository.voiceRecordingsFor(walk.id)
            val transcripts = recordings.mapNotNull { it.transcription?.takeIf { text -> text.isNotBlank() } }
            if (transcripts.isEmpty()) continue
            val preview = transcripts
                .joinToString(separator = " ")
                .truncatedAtWordBoundary()
            val celestialSummary = if (celestialEnabled) {
                summarizeCelestial(walk, zone, zodiacSystem)
            } else {
                null
            }
            snippets += WalkSnippet(
                date = walk.startTimestamp,
                placeName = null,
                weatherCondition = walk.weatherCondition,
                celestialSummary = celestialSummary,
                transcriptionPreview = preview,
            )
        }
        return snippets
    }

    private fun summarizeCelestial(walk: Walk, zone: ZoneId, system: ZodiacSystem): String? {
        val snapshot: CelestialSnapshot = CelestialSnapshotCalc.snapshot(walk.startTimestamp, zone, system)
        val sun = snapshot.position(Planet.Sun) ?: return null
        val moon = snapshot.position(Planet.Moon) ?: return null
        val tropical = system == ZodiacSystem.Tropical
        val sunSign = (if (tropical) sun.tropical else sun.sidereal).sign.displayName
        val moonSign = (if (tropical) moon.tropical else moon.sidereal).sign.displayName
        return "Sun in $sunSign, Moon in $moonSign"
    }

    private fun computeRouteSpeeds(samples: List<RouteDataSample>): List<Double> =
        samples.zipWithNext().mapNotNull { (a, b) ->
            val dtSeconds = (b.timestamp - a.timestamp) / 1000.0
            if (dtSeconds <= 0.0) return@mapNotNull null
            val distance = haversineMeters(a.toLocationPoint(), b.toLocationPoint())
            distance / dtSeconds
        }

    private fun weatherLabelResolver(): (WeatherCondition) -> String =
        { appContext.getString(it.labelRes) }

    /**
     * Stub icon resolver — Stage 13-XZ Task 16 owns the 20-icon
     * Material lookup table. Returns a neutral pencil icon so the
     * GeneratedPrompt has a non-null vector; consumers (Task 14
     * PromptListSheet) don't surface custom icons through this seam.
     */
    private fun resolveCustomIcon(@Suppress("UNUSED_PARAMETER") iconKey: String): ImageVector =
        Icons.Outlined.Edit

    private companion object {
        const val RECENT_WALKS_LOOKBACK = 20
        const val MAX_RECENT_SNIPPETS = 3
    }
}

private data class SubFetches(
    val locationSamples: List<RouteDataSample>,
    val recordings: List<VoiceRecording>,
    val intervals: List<ActivityInterval>,
    val waypoints: List<Waypoint>,
    val photos: List<WalkPhoto>,
    val recentWalks: List<Walk>,
)

private fun List<RouteDataSample>.toRouteSamples(): List<RouteSample> = map {
    RouteSample(timestampMs = it.timestamp, latitude = it.latitude, longitude = it.longitude)
}

private fun RouteDataSample.toLocationPoint(): LocationPoint =
    LocationPoint(timestamp = timestamp, latitude = latitude, longitude = longitude)
