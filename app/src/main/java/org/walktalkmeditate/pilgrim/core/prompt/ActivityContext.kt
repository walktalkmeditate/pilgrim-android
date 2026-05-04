// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase

@Immutable
data class ActivityContext(
    val recordings: List<RecordingContext>,
    val meditations: List<MeditationContext>,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val startTimestamp: Long,
    val placeNames: List<PlaceContext>,
    val routeSpeeds: List<Double>,
    val recentWalkSnippets: List<WalkSnippet>,
    val intention: String?,
    val waypoints: List<WaypointContext>,
    val weather: String?,
    val lunarPhase: MoonPhase?,
    val celestial: CelestialSnapshot?,
    val photoContexts: List<PhotoContextEntry>,
    val narrativeArc: NarrativeArc?,
) {
    val hasSpeech: Boolean get() = recordings.isNotEmpty()
}
