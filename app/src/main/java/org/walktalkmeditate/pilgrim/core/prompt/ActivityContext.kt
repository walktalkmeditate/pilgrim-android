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
 * explained to the downstream model by the practice lexicon
 * (`ActivityContext.swift:3-9@7c200bf`).
 */
enum class PracticeMode { Wander, Seek, Honor }

/**
 * What this seek held: when each clearing was reached (epoch ms,
 * sorted). An empty list is a zero-arrival seek, which the lexicon
 * honors rather than hides.
 */
@Immutable
data class SeekStoryContext(val arrivalTimes: List<Long>)

/**
 * What this honor held: the Way's title, null until a caller that can
 * reach the Ways store fills it (and when the Way is gone), and whether
 * its end was reached (iOS `HonorStoryContext`,
 * `ActivityContext.swift:17-37@7c200bf`). A pilgrimage stage adds its
 * route's name ([routeName], never null for a stage, so it alone selects
 * the stage lexicon) and its place on the route ([stageLabel],
 * "stage 1 of 33"); both stay null on every other Way.
 */
@Immutable
data class HonorStoryContext(
    val wayTitle: String?,
    val arrived: Boolean,
    val routeName: String? = null,
    val stageLabel: String? = null,
)

data class WalkPractice(
    val mode: PracticeMode,
    val seekStory: SeekStoryContext?,
    val honorStory: HonorStoryContext? = null,
)

/**
 * Pure mapping from a walk's events to its practice context (iOS
 * `WalkPracticeModel`, `ActivityContext.swift:43-60@7c200bf`). Mode
 * derivation routes through [walkModeFromEvents] so the prompt pipeline
 * and the summary can never disagree about a walk's mode: Honor wins over
 * Seek, and only with the release flag on ([honorEnabled]); with it off
 * an honor walk reads as the walk it would be without its marker.
 */
object WalkPracticeModel {

    fun practice(events: List<WalkEventLike>, honorEnabled: Boolean): WalkPractice =
        when (walkModeFromEvents(events, honorEnabled)) {
            WalkMode.Honor -> WalkPractice(
                mode = PracticeMode.Honor,
                seekStory = null,
                honorStory = HonorStoryContext(
                    wayTitle = null,
                    arrived = events.any { it.type == WalkEventType.HONOR_ARRIVAL },
                ),
            )
            WalkMode.Seek -> {
                val arrivals = events
                    .filter { it.type == WalkEventType.SEEK_ARRIVAL }
                    .map { it.timestamp }
                    .sorted()
                WalkPractice(PracticeMode.Seek, SeekStoryContext(arrivals))
            }
            WalkMode.Wander -> WalkPractice(PracticeMode.Wander, null)
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
    /**
     * U7: the pre-rendered Thought Threads dossier text for this walk
     * (`ThreadsDossierBuilder.build(walkId)?.text`), or `null` when the
     * toggle is off or nothing has been analyzed yet. [PromptAssembler]
     * inserts it verbatim after the recent-walks block and before the
     * attention directives (BEH-74); its mere presence also gates the
     * response contract's thought-thread safety line (BEH-75) —
     * deliberately the ARTIFACT, not the raw preference, so a walk with
     * the toggle on but nothing analyzed never shows a caveat about data
     * that isn't there.
     */
    val threadsDossier: String? = null,
    /**
     * U9: the transcript's detected ISO language code (e.g. "ja"), from
     * one [MlKitLanguageIdClient.detect] pass over the joined recording
     * transcripts — computed once in [PromptsCoordinator.buildContext]
     * and reused by every prompt style's [PromptGenerator.resolvedDerivations]
     * call (BEH-77: derived once, reused across every style). `null`
     * when there's no speech to detect from, or detection itself failed
     * — both read identically downstream as "assume English" (matches
     * [AttentionDirectives]'s own `detectedLanguageCode` default).
     */
    val detectedLanguageCode: String? = null,
    /** Set on an honor walk only, beside [seekStory] (`ActivityContext.swift:77-79@7c200bf`). */
    val honorStory: HonorStoryContext? = null,
) {
    val hasSpeech: Boolean get() = recordings.isNotEmpty()
}
