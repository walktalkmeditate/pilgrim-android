// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import java.time.ZoneId
import java.util.Locale
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Verbatim port of iOS `PromptAssembler.swift`. Stitches together the
 * pieces produced by [ContextFormatter] into the single LLM-facing
 * prompt the AI Prompts sheet sends to Anthropic.
 *
 * **Section order (matches iOS exactly — U12 template, spec
 * `docs/parity/2026-07-26-port-prompt-pipeline-u12.md`):**
 *  1. Voice preamble (+ [WalkCharacter] note appended when non-null)
 *  2. `---` divider
 *  3. `**Context:** ...metadata...` (one line, light-crossing phrase
 *     when start/end time-of-day buckets differ)
 *  4. ` | <weather>` appended inline to (3) when present
 *  5. Celestial block (`**Celestial Context (...):** ...`)
 *  6. Practice lexicon (`**About this practice:** ...`, always present)
 *  7. Intention prologue block
 *  8. Location block (`**Location:** ...`)
 *  9. Pace block (`**Pace:** ...`)
 *  10. Pauses block (`**Pauses:** ...`)
 *  11. Elevation block (`**Elevation:** ...`)
 *  12. Waypoints block (`**Waypoints marked during walk:**`)
 *  13. Photos block (`**Photos pinned along the walk:**`)
 *  14. Walking transcription block
 *  15. Meditation sessions block
 *  16. Recent walk context block
 *  17. Attention directives (`**Attend to:**` bullets, gated)
 *  18. `---` divider
 *  19. Voice instruction (with intention tail when intention is set)
 *  20. Response contract (`**How to respond:**` bullets, always present)
 *
 * Each gated block is rendered iff its source data is present /
 * non-empty / above its own threshold; the iOS template uses the same
 * gates and we mirror them line-for-line so a prompt produced on
 * Android against the same `ActivityContext` matches the iOS output
 * byte-for-byte (modulo the documented divergences below).
 *
 * **Android divergences from iOS** (per Stage 13-XZ spec § Non-goals):
 *
 *  - **`Animals:` line dropped.** [PhotoContext] has no `animals` field
 *    on Android — ML Kit lacks an equivalent of iOS's
 *    `VNRecognizeAnimalsRequest`. Test
 *    `assemble_photosBlock_NO_animalsLine` regression-guards.
 *  - **`Focal area:` line dropped.** [PhotoContext] has no
 *    `salientRegion` field — ML Kit has no saliency API. A
 *    constant-center placeholder would feed misleading attention-arc
 *    hints to the LLM. Test `assemble_photosBlock_NO_focalAreaLine`
 *    regression-guards.
 *  - **`Visual narrative:` + `Color progression:` arc block dropped.**
 *    Android's [NarrativeArc] is a no-op stub returning [NarrativeArc.EMPTY]
 *    (Task 4) because cross-photo arc derivation depends on the
 *    saliency feature dropped above. Rendering "consistently_close" /
 *    other hardcoded sentinels would mislead the LLM about what the
 *    walker actually photographed. Test
 *    `assemble_photosBlock_NO_visualNarrativeBlock` regression-guards.
 *
 * Per-photo `Scene:` / `Text found:` / `People:` / `Outdoor:` lines
 * are retained — they reflect real ML Kit output. Per-photo
 * `dominantColor` is stored on [PhotoContext] but iOS doesn't render it
 * per-photo either (only via the dropped arc block), so we keep parity.
 *
 * **`PhotoContextEntry.coordinate` nullability divergence.** iOS's
 * `PhotoContextEntry.coordinate` is non-optional; Android's is
 * `LatLng?` (null when no route samples bracket the photo's
 * timestamp). When null the assembler omits the `, GPS: ...` segment
 * from the photo header. Test
 * `assemble_photosBlock_nullCoordinate_omitsGpsSegment` covers this.
 *
 * **Pure-function design.** [imperial] + [zone] are passed in by the
 * caller (the Task 12 walk-summary VM reads
 * `UnitsPreferences.distanceUnits` once at sheet-open, and resolves
 * the zone via `ZoneId.systemDefault()`). Keeping them as function
 * parameters means the assembler stays Hilt-free and trivially
 * unit-testable without an Android `Context`.
 *
 * **Pre-formatted weather.** [ActivityContext.weather] is `String?`
 * — already a `"Weather: ..."` line produced by
 * [ContextFormatter.formatWeather]. The assembler does not call
 * `formatWeather` itself; the [Task 9 PromptGenerator] is responsible
 * for resolving the `Walk` row + `WeatherCondition` label closure into
 * the formatted string before constructing [ActivityContext]. The
 * separate [assemble] `weatherLabel` parameter is still required for
 * the recent-walks block, where each [WalkSnippet] carries a raw
 * [WeatherCondition] code that must be resolved at render time.
 */
object PromptAssembler {

    /**
     * Assemble the full LLM prompt.
     *
     * @param context Full [ActivityContext] for the walk. `lunarPhase`
     *   must be non-null (matches iOS contract); the caller computes
     *   it from the walk's start timestamp.
     * @param voice Selected [WalkPromptVoice] — provides preamble +
     *   instruction strings, with separate `hasSpeech` variants.
     * @param imperial `true` when the user prefers imperial units;
     *   propagates to distance + pace formatting.
     * @param weatherLabel Resolves a [WeatherCondition] enum to its
     *   user-facing display string. Used only for the recent-walks
     *   block (each [WalkSnippet] carries a raw `weatherCondition`
     *   string code). Defaults to the enum `name` so the assembler
     *   stays callable without an Android `Context` in tests; production
     *   callers wrap a string-resource lookup.
     * @param zone Time zone used for every wall-clock formatting call.
     *   Defaults to system default; tests pin a fixed zone for
     *   determinism.
     * @param directives Precomputed attention directives, or `null` to
     *   compute here. `null` is the right default for single-style
     *   callers; [PromptGenerator.generateAll] computes this once via
     *   [PromptGenerator.resolvedDerivations] and fans it out across
     *   every style so a screen-open pays for one NLP pass, not one per
     *   style (U7/BEH-77).
     * @param detectedLanguageName Precomputed English display name of
     *   the transcript's dominant language (U7), or `null` to omit the
     *   "Detected language" line entirely — Android has no synchronous
     *   on-device detector to fall back to inline the way iOS's
     *   `TranscriptNLP.detectLanguage` does, so unlike [directives] there
     *   is no "compute here" default; see [PromptGenerator.resolvedDerivations].
     */
    fun assemble(
        context: ActivityContext,
        voice: WalkPromptVoice,
        imperial: Boolean,
        weatherLabel: (WeatherCondition) -> String = { it.name },
        zone: ZoneId = ZoneId.systemDefault(),
        directives: List<String>? = null,
        detectedLanguageName: String? = null,
    ): String {
        val lunarPhase = requireNotNull(context.lunarPhase) {
            "ActivityContext.lunarPhase must be non-null when assembling a prompt — " +
                "compute it via MoonCalc.moonPhase(Instant.ofEpochMilli(startTimestamp))."
        }

        val transcription = ContextFormatter.formatRecordings(context.recordings, zone)
        val meditationsBlock = ContextFormatter.formatMeditations(context.meditations, zone)
        val metadata = ContextFormatter.formatMetadata(
            durationSeconds = context.durationSeconds,
            distanceMeters = context.distanceMeters,
            startTimestamp = context.startTimestamp,
            lunarPhase = lunarPhase,
            imperial = imperial,
            zone = zone,
            pauseDurationSeconds = context.pauses.sumOf { it.durationSeconds },
        )
        val location = ContextFormatter.formatPlaceNames(context.placeNames)
        val pace = ContextFormatter.formatPaceContext(context.routeSpeeds, imperial)
        val pauses = ContextFormatter.formatPauses(context.pauses, zone)
        val elevation = ContextFormatter.formatElevation(
            ascent = context.ascentMeters,
            descent = context.descentMeters,
            imperial = imperial,
        )
        val recentWalks = ContextFormatter.formatRecentWalks(
            snippets = context.recentWalkSnippets,
            weatherLabel = weatherLabel,
            zone = zone,
        )

        val preamble = StringBuilder(voice.preamble(context.hasSpeech))
        WalkCharacter.note(context, zone)?.let { characterNote ->
            preamble.append(' ').append(characterNote)
        }
        val instruction = voice.instruction(context.hasSpeech)

        val sections = StringBuilder()
        sections.append(preamble)
            .append("\n\n---\n\n")
            .append("**Context:** ")
            .append(metadata)

        context.weather?.let { weather ->
            sections.append(" | ").append(weather)
        }

        context.celestial?.let { celestial ->
            sections.append("\n\n").append(ContextFormatter.formatCelestial(celestial))
        }

        sections.append("\n\n").append(practiceLexicon(context, zone))

        context.intention?.let { intention ->
            sections.append("\n\n**The walker's intention:** \"")
                .append(intention)
                .append("\"\nThis intention was set deliberately before the walk began. ")
                .append("It represents what the walker chose to carry with them. ")
                .append("Let it be the lens through which you interpret everything below.")
        }

        location?.let { sections.append("\n\n").append(it) }
        pace?.let { sections.append("\n\n").append(it) }
        pauses?.let { sections.append("\n\n").append(it) }
        elevation?.let { sections.append("\n\n").append(it) }

        if (context.waypoints.isNotEmpty()) {
            sections.append("\n\n**Waypoints marked during walk:**\n")
            sections.append(
                context.waypoints.joinToString(separator = "\n") { wp ->
                    val time = ContextFormatter.formatTime(wp.timestamp, zone)
                    val coord = ContextFormatter.formatCoord(
                        wp.coordinate.latitude,
                        wp.coordinate.longitude,
                    )
                    "[$time, GPS: $coord] ${wp.label}"
                },
            )
        }

        if (context.photoContexts.isNotEmpty()) {
            sections.append(formatPhotoSection(context.photoContexts, imperial, zone))
        }

        if (transcription.isNotEmpty()) {
            sections.append("\n\n**Walking Transcription:**\n\n").append(transcription)
        }

        if (transcription.isNotEmpty() && detectedLanguageName != null) {
            sections.append("\n\n**Detected language:** ").append(detectedLanguageName)
        }

        meditationsBlock?.let { block ->
            sections.append("\n\n**Meditation Sessions:**\n\n").append(block)
        }

        recentWalks?.let { block ->
            sections.append("\n\n").append(block)
        }

        context.threadsDossier?.let { dossier ->
            sections.append("\n\n").append(dossier)
        }

        val resolvedDirectives = directives ?: AttentionDirectives.detect(context)
        if (resolvedDirectives.isNotEmpty()) {
            sections.append("\n\n**Attend to:**\n")
                .append(resolvedDirectives.joinToString(separator = "\n") { "- $it" })
        }

        val fullInstruction = StringBuilder(instruction)
        context.intention?.let { intention ->
            fullInstruction.append(" Ground your response in the walker's stated intention: '")
                .append(intention)
                .append("'. Return to it. Help them see how their walk — its pace, its pauses, ")
                .append("its moments — spoke to this purpose.")
        }

        sections.append("\n\n---\n\n").append(fullInstruction)
        sections.append("\n\n").append(
            responseContract(voice, context.hasSpeech, threadsDossier = context.threadsDossier),
        )
        return sections.toString()
    }

    /**
     * English display name for a detected language [code] (BEH-77/EDG-87)
     * — ALWAYS resolved against a fixed English locale, never the
     * device's default, so the LLM (whose surrounding instructions are
     * themselves in English) never receives a self-localized name like
     * "français" for a French transcript. `null` for a blank/unrecognized
     * result — Java's [Locale] API has no failable initializer the way
     * iOS's `Locale(identifier:)` + `localizedString(forLanguageCode:)`
     * pairing does, so an empty display name is the closest analogue to
     * iOS's `nil`.
     */
    fun languageName(code: String): String? =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() }

    /**
     * Teaches the downstream model the walk's ritual grammar in Pilgrim's
     * own vocabulary, so route and pace data read as practice, not as
     * fitness telemetry. Seek walks carry their story; a zero-arrival seek
     * is named, not hidden.
     */
    internal fun practiceLexicon(context: ActivityContext, zone: ZoneId): String =
        when (context.mode) {
            PracticeMode.Wander ->
                "**About this practice:** This walk was a wander — no destination, no goal; " +
                    "the path chose itself."
            PracticeMode.Seek -> {
                val text = StringBuilder(
                    "**About this practice:** This walk was a Seek. The walker surrendered the " +
                        "choice of destination: a seed cast hidden clearings across the map, " +
                        "veiled in fog, revealed only by nearness and stillness. Arriving is " +
                        "not achievement; it is consent to be led.",
                )
                val arrivals = context.seekStory?.arrivalTimes
                if (arrivals != null) {
                    if (arrivals.isEmpty()) {
                        text.append(
                            " No clearing was reached this time — the seek honors this too; " +
                                "some walks are about the looking.",
                        )
                    } else if (arrivals.size == 1) {
                        val hour = ContextFormatter.timeOfDayDescription(arrivals.first(), zone)
                        text.append(" One clearing was found, reached in the $hour.")
                    } else {
                        val first = ContextFormatter.timeOfDayDescription(arrivals.first(), zone)
                        val last = ContextFormatter.timeOfDayDescription(arrivals.last(), zone)
                        text.append(
                            " ${arrivals.size} clearings were found — the first in the $first, " +
                                "the last in the $last.",
                        )
                    }
                }
                text.toString()
            }
        }

    /**
     * The closing contract every prompt carries: what the response may not
     * do (invent, flatten, switch language) plus the voice's own form
     * constraints. This shapes the *reply's* quality — the part of the
     * feature the walker actually experiences.
     *
     * @param threadsDossier The dossier TEXT when the walk carries one,
     *   null otherwise. Gates the thought-thread safety line on the
     *   ARTIFACT ([ActivityContext.threadsDossier] != null), never the raw
     *   `threadsAfterWalks` preference (BEH-75) — the preference can be on
     *   with nothing yet analyzed, which must not show a caveat about data
     *   that isn't there. The text itself feeds [interpretiveKey], which
     *   teaches only the marker signals this dossier actually printed.
     */
    internal fun responseContract(voice: WalkPromptVoice, hasSpeech: Boolean, threadsDossier: String?): String {
        val lines = voice.responseConstraints(hasSpeech).toMutableList()
        if (hasSpeech) {
            lines.add("Respond in the language the walker speaks in the transcription.")
            lines.add(
                "If more than one voice appears in the transcription, honor it as a " +
                    "conversation — attend to what happened between the speakers, and never " +
                    "guess at names.",
            )
        }
        if (threadsDossier != null) {
            lines.add(
                "The thought-thread marker profiles are descriptive on-device linguistic signals, " +
                    "not assessments — interpret them gently, never produce clinical or diagnostic " +
                    "language, and never treat a single walk's numbers as meaningful on their own.",
            )
            interpretiveKey(threadsDossier)?.let { lines.add(it) }
        }
        lines.add(
            "Draw only on what this walk actually holds — never invent details, events, or " +
                "memories that are not in the context above.",
        )
        return "**How to respond:**\n" + lines.joinToString(separator = "\n") { "- $it" }
    }

    /**
     * How to read the marker signals — but only the ones this dossier
     * actually printed. `ThreadsDossierFormatter.markerLine` prints
     * "Markers unavailable" for a non-English recording and raw counts
     * rather than shares below `DENSITY_FLOOR_WORDS`, and the modal-lean
     * clause sits behind three thresholds and is usually silent. Teaching
     * a taxonomy the dossier withheld hands the model vocabulary with no
     * referent, and it will find something to attach it to.
     *
     * The probes match the formatter's own phrasings. That coupling is
     * the point — it is pinned by the "against real formatter output"
     * tests in PromptResponseContractTest, which run the real formatter,
     * so a phrasing change fails a test rather than silently suppressing
     * the key on every walk.
     *
     * Those tests pin the FORMATTER half only. In production [dossier] is
     * wider: `ThreadsDossierBuilder` appends its `**Noticed:**` senses
     * block to the same string, and
     * `DossierSensesTracks.markerColoring` writes "Absolutist words
     * cluster around …" into it — which misses the `"absolutist words"`
     * probe on capitalization alone. Lowercasing either side would let a
     * sense line teach a density reading the dossier never printed, so
     * the senses half carries its own pinning test beside them.
     *
     * At most one line either way: the contract's accretion budget does
     * not grow to pay for this.
     */
    private fun interpretiveKey(dossier: String): String? {
        val clauses = mutableListOf<String>()
        if (dossier.contains("absolutist words")) {
            clauses.add(
                "Read the absolutist-word share as how fixed the walker's framing was, and " +
                    "self-focus as how far they placed themselves at the centre of it.",
            )
        } else if (dossier.contains("raw counts only")) {
            clauses.add(
                "Read the absolutist and self-focus counts as a bare tally of how fixed the " +
                    "walker's framing was and how far they placed themselves at the centre of it — " +
                    "too few words to read as a rate, so do not weigh them.",
            )
        }
        if (dossier.contains("modal lean:")) {
            clauses.add(
                "Read the modal lean as the frame the walker was working inside — obligation " +
                    "means the frame constrained them, counterfactual means they were already " +
                    "replaying alternatives, possibility and tentative mean it was still open, " +
                    "intention means they had settled on a course, and desire means they were " +
                    "naming a want rather than a plan.",
            )
        }
        if (clauses.isEmpty()) return null
        return (clauses + "None of these has a fixed meaning; read each through this walk's intention and practice.")
            .joinToString(separator = " ")
    }

    private fun formatPhotoSection(
        photos: List<PhotoContextEntry>,
        imperial: Boolean,
        zone: ZoneId,
    ): String {
        val units = if (imperial) UnitSystem.Imperial else UnitSystem.Metric
        val section = StringBuilder("\n\n**Photos pinned along the walk:**")
        for (entry in photos) {
            val distance = WalkFormat.distance(entry.distanceIntoWalkMeters, units)
            val time = ContextFormatter.formatTime(entry.time, zone)
            val header = StringBuilder()
            header.append("\nPhoto ").append(entry.index)
                .append(" (").append(distance).append(", ").append(time)
            entry.coordinate?.let { coord ->
                header.append(", GPS: ")
                    .append(ContextFormatter.formatCoord(coord.latitude, coord.longitude))
            }
            header.append("):")
            section.append(header)

            val ctx = entry.context
            if (ctx.tags.isNotEmpty()) {
                section.append("\n  Scene: ").append(ctx.tags.joinToString(separator = ", "))
            }
            if (ctx.detectedText.isNotEmpty()) {
                section.append("\n  Text found: ")
                    .append(ctx.detectedText.joinToString(separator = ", ") { "\"$it\"" })
            }
            section.append("\n  People: ")
                .append(if (ctx.people == 0) "none" else ctx.people.toString())
            section.append("\n  Outdoor: ").append(if (ctx.outdoor) "yes" else "no")
        }
        return section.toString()
    }

}
