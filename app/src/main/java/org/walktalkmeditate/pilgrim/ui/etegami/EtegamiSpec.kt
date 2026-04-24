// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import androidx.compose.runtime.Immutable
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.core.celestial.LightReading
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec

/**
 * Stage 7-C: pure input aggregate for [EtegamiBitmapRenderer]. Assembled
 * in the VM from the existing [WalkSummary] inputs. All fields are
 * Compose-stable (primitives + `String?` + `List<@Immutable T>` + nullable
 * primitives); marked `@Immutable` because the spec lives inside
 * `WalkSummary` which Compose walks transitively for stability.
 */
@Immutable
data class EtegamiSpec(
    val walkUuid: String,
    val startedAtEpochMs: Long,
    val zoneId: ZoneId,
    val routePoints: List<LocationPoint>,
    val sealSpec: SealSpec,
    val moonPhase: MoonPhase?,
    val distanceMeters: Double,
    val durationMillis: Long,
    val elevationGainMeters: Double,
    /**
     * Top text drawn inside the postcard — typically a one-to-three-line
     * intention or reflection. Null when neither is set; renderer skips
     * the text layer entirely (empty is cleaner than a placeholder).
     */
    val topText: String?,
    val activityMarkers: List<ActivityMarker>,
)

@Immutable
data class ActivityMarker(
    val kind: Kind,
    val timestampMs: Long,
) {
    enum class Kind { Meditation, Voice }
}

/**
 * Compose an [EtegamiSpec] from the raw walk inputs available in
 * `WalkSummaryViewModel.buildState`. Pure function — no Android APIs,
 * no side effects. Returns null iff the walk has zero location
 * samples AND no seal spec — at that point the postcard would have
 * nothing to render.
 */
internal fun composeEtegamiSpec(
    walk: Walk,
    routePoints: List<LocationPoint>,
    sealSpec: SealSpec,
    lightReading: LightReading?,
    distanceMeters: Double,
    durationMillis: Long,
    altitudeSamples: List<AltitudeSample>,
    activityIntervals: List<ActivityInterval>,
    voiceRecordings: List<VoiceRecording>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): EtegamiSpec = EtegamiSpec(
    walkUuid = walk.uuid,
    startedAtEpochMs = walk.startTimestamp,
    zoneId = zoneId,
    routePoints = routePoints,
    sealSpec = sealSpec,
    moonPhase = lightReading?.moon,
    distanceMeters = distanceMeters,
    durationMillis = durationMillis,
    elevationGainMeters = elevationGain(altitudeSamples),
    topText = walk.intention?.takeIf { it.isNotBlank() }
        ?: walk.notes?.takeIf { it.isNotBlank() },
    activityMarkers = buildList {
        // Meditation markers from MEDITATING intervals (start timestamps).
        activityIntervals
            .filter { it.activityType == ActivityType.MEDITATING }
            .forEach { add(ActivityMarker(ActivityMarker.Kind.Meditation, it.startTimestamp)) }
        // Voice markers from recording starts.
        voiceRecordings.forEach {
            add(ActivityMarker(ActivityMarker.Kind.Voice, it.startTimestamp))
        }
    }.sortedBy { it.timestampMs },
)

/**
 * Elevation gain = sum of positive deltas between consecutive samples.
 * Ordering is timestamp-asc; if samples are unsorted we sort them
 * once to avoid negative deltas from reordering.
 */
private fun elevationGain(samples: List<AltitudeSample>): Double {
    if (samples.size < 2) return 0.0
    val sorted = samples.sortedBy { it.timestamp }
    var gain = 0.0
    for (i in 1 until sorted.size) {
        val delta = sorted[i].altitudeMeters - sorted[i - 1].altitudeMeters
        if (delta > 0) gain += delta
    }
    return gain
}
