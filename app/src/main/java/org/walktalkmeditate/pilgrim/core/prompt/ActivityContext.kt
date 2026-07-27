// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.domain.WalkEventLike
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.walkModeFromEvents

/**
 * How the walk was undertaken — each mode carries its own ritual grammar,
 * explained to the downstream model by the practice lexicon.
 *
 * Deliberately not [WalkMode]: that enum carries a third value
 * (`Together`) the shipped iOS lexicon has no prose for. A two-case
 * enum keeps [PromptAssembler.practiceLexicon]'s `when` exhaustive
 * without inventing text (spec D3).
 */
enum class PracticeMode { Wander, Seek }

/**
 * What this seek held: when each clearing was reached (epoch ms,
 * sorted). An empty list is a zero-arrival seek, which the lexicon
 * honors rather than hides.
 */
@Immutable
data class SeekStoryContext(val arrivalTimes: List<Long>)

data class WalkPractice(val mode: PracticeMode, val seekStory: SeekStoryContext?)

/**
 * Pure mapping from a walk's events to its practice context (iOS
 * `WalkPracticeModel@9a418e4`). Mode derivation routes through
 * [walkModeFromEvents] so the prompt pipeline and the seek-summary
 * path can never disagree about a walk's mode.
 */
object WalkPracticeModel {

    fun practice(events: List<WalkEventLike>): WalkPractice {
        if (walkModeFromEvents(events) != WalkMode.Seek) {
            return WalkPractice(PracticeMode.Wander, null)
        }
        val arrivals = events
            .filter { it.type == WalkEventType.SEEK_ARRIVAL }
            .map { it.timestamp }
            .sorted()
        return WalkPractice(PracticeMode.Seek, SeekStoryContext(arrivals))
    }
}

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
    val mode: PracticeMode,
    val seekStory: SeekStoryContext?,
    val pauses: List<PauseContext>,
    val ascentMeters: Double?,
    val descentMeters: Double?,
) {
    val hasSpeech: Boolean get() = recordings.isNotEmpty()
}
