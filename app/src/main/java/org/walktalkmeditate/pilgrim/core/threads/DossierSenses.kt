// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.location.Location

/**
 * Pure sense engine for the dossier's `**Noticed:**` block. Ported from
 * `Pilgrim/Models/Threads/DossierSenses.swift` (parity spec
 * `docs/parity/2026-08-26-threads-senses-port.md`). Binding purity
 * contract: no Room, no clock, no singleton — every input arrives as a
 * [SenseInput] argument, gathered by [ThreadsDossierBuilder], so every
 * line stays traceable to enumerable, deterministic inputs.
 * `Instant.now()` is never called here; time arrives as data.
 *
 * The 16 tuning constants below live in ONE block (not scattered inline
 * per file) so a future retune stays a one-place edit — the iOS pin has
 * already been retuned twice in its own lifetime (`lineCap`/climb/weather
 * ship-gate edits cited throughout the parity spec).
 */
object DossierSenses {

    const val LINE_CAP = 3
    const val PLACE_CLUSTER_RADIUS_METERS = 150.0
    const val PLACE_CANDIDATE_THEME_CAP = 4
    const val HYGIENE_MAX_GAP_SECONDS = 90.0
    const val HYGIENE_MAX_ACCURACY_METERS = 100.0
    const val PHOTO_TIE_RADIUS_METERS = 75.0
    const val PHOTO_TIE_MAX_INTERVAL_SECONDS = 600.0
    const val CLIMB_MIN_TOTAL_ASCENT_METERS = 50.0
    const val CLIMB_MIN_RUN_GAIN_METERS = 20.0
    const val CLIMB_SMOOTHING_WINDOW = 5
    const val CLIMB_TOP_DECILE = 0.9
    const val MARKER_WINDOW_RADIUS = 15
    const val MARKER_MIN_WINDOW_ABSOLUTIST = 3
    const val MARKER_MIN_DENSITY_RATIO = 2.0
    const val SPEECH_SHAPE_MIN_WORDLESS_REMAINDER_SECONDS = 30.0 * 60.0
    const val LINEAGE_MIN_WALKS = 3

    /**
     * Declaration order IS the binding priority order — reordering cases
     * reorders the `**Noticed:**` block. Exactly 8 cases; `questionDensity`
     * was deliberately cut at the iOS ship gate (2026-08-25) and must NOT
     * be ported from any older design doc.
     */
    enum class Sense {
        PLACE_RESONANCE, MOON_LINE, MARKER_COLORING, INTENTION_LINEAGE,
        CLIMB_ANCHORING, WEATHER_WEAVE, PHOTO_ADJACENCY, SPEECH_SHAPE,
    }

    /**
     * Cap (early `break`, not evaluate-all-then-truncate — senses past
     * the cap are never evaluated at all), priority, and belt-and-
     * suspenders lemma dedup (a theme named at a higher rank never
     * reappears, whatever a lower-ranked sense returns; `continue`, not
     * `break` — still-lower senses keep firing). Lines with `lemma ==
     * null` (speechShape always; moonLine's theme-less form) bypass the
     * dedup entirely.
     *
     * `reportedLunationIndex` is set ONLY after moonLine's line survived
     * BOTH the cap break-guard and the lemma-dedup guard in the SAME
     * iteration — never merely because the moon-line sense function
     * itself returned non-null. This is what keeps the once-per-lunation
     * budget honest; the caller persists the moon-state key on this
     * value, never on mere eligibility.
     *
     * [evaluate] is a test seam (same style as `ThreadsBackfill`'s
     * `snapshotProvider`) — production callers use the default dispatch.
     */
    fun lines(
        input: SenseInput,
        evaluate: (sense: Sense, input: SenseInput, suppressed: Set<String>) -> SenseLine? = ::evaluate,
    ): SenseOutput {
        val used = mutableSetOf<String>()
        val lines = mutableListOf<String>()
        var reportedLunationIndex: Int? = null
        for (sense in Sense.entries) {
            if (lines.size >= LINE_CAP) break
            val line = evaluate(sense, input, used) ?: continue
            val lemma = line.lemma
            if (lemma != null) {
                if (lemma in used) continue
                used += lemma
            }
            lines += line.text
            if (sense == Sense.MOON_LINE) {
                reportedLunationIndex = input.moon?.lunationIndex
            }
        }
        return SenseOutput(lines = lines, reportedLunationIndex = reportedLunationIndex)
    }

    /** The per-sense dispatch switch — also called directly by the
     * DEBUG-only field report harness with an empty `suppressed` set and
     * no cap. */
    fun evaluate(sense: Sense, input: SenseInput, suppressed: Set<String>): SenseLine? = when (sense) {
        Sense.PLACE_RESONANCE -> DossierSensesTracks.placeResonance(input, suppressed)
        Sense.MOON_LINE -> DossierSensesTracks.moonLine(input, suppressed)
        Sense.MARKER_COLORING -> DossierSensesTracks.markerColoring(input, suppressed)
        Sense.INTENTION_LINEAGE -> DossierSensesTracks.intentionLineage(input, suppressed)
        Sense.CLIMB_ANCHORING -> DossierSensesTracks.climbAnchoring(input, suppressed)
        Sense.WEATHER_WEAVE -> DossierSensesTracks.weatherWeave(input, suppressed)
        Sense.PHOTO_ADJACENCY -> DossierSensesTracks.photoAdjacency(input, suppressed)
        Sense.SPEECH_SHAPE -> DossierSensesTracks.speechShape(input, suppressed)
    }

    /**
     * The single GPS-hygiene gate — DIFFERENT operators on its two
     * halves: `gapSeconds` inclusive (exactly 90.0s accepts),
     * `horizontalAccuracy` exclusive (exactly 100.0m rejects). Both
     * [DossierSensesTracks.placeResonance] and
     * [DossierSensesTracks.photoAdjacency] route every location claim
     * through this one function.
     */
    fun qualifies(fix: RouteFix): Boolean =
        fix.gapSeconds <= HYGIENE_MAX_GAP_SECONDS && fix.horizontalAccuracy < HYGIENE_MAX_ACCURACY_METERS

    /**
     * Geodesic distance in meters. Every distance threshold (150/75/100m)
     * was tuned against iOS's `CLLocation` geodesic; `Location.distanceBetween`
     * is the closest Android analogue (also ellipsoidal, WGS84 Vincenty
     * inverse) — verified at the three threshold scales in
     * `DossierSensesTest` rather than assumed equivalent (parity spec
     * Open question 6). Deliberately NOT a custom spherical haversine
     * (the spec's own "no custom haversine anywhere in the slice" line).
     */
    fun distanceMeters(a: Coordinate, b: Coordinate): Double {
        val result = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0].toDouble()
    }

    /** Even count averages the two middle elements; `mid = count / 2`
     * (integer division). Empty input returns 0.0. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private val SPELLED_SMALL = mapOf(
        3 to "three", 4 to "four", 5 to "five", 6 to "six",
        7 to "seven", 8 to "eight", 9 to "nine",
    )

    /**
     * The shared "N times" formatter (sole production caller: markerLine)
     * — spells 3-9, bare numerals otherwise. `n == 1` renders the
     * grammatically-broken "1 times" — latent, guarded only by
     * markerLine's own `ratio >= 2` gate; do NOT extend this helper to
     * add a singular case, and do NOT reuse it for
     * [DossierSensesTracks.placeResonance]'s own inline "twice"/"N times"
     * ternary — the two formatters are deliberately separate (placeResonance
     * NEVER spells 3-9; consolidating them would silently change shipped
     * copy).
     */
    fun timesPhrase(n: Int): String = when {
        n == 2 -> "twice"
        SPELLED_SMALL.containsKey(n) -> "${SPELLED_SMALL.getValue(n)} times"
        else -> "$n times"
    }

    private val ORDINAL_WORDS = mapOf(
        3 to "Third", 4 to "Fourth", 5 to "Fifth", 6 to "Sixth", 7 to "Seventh",
        8 to "Eighth", 9 to "Ninth", 10 to "Tenth", 11 to "Eleventh", 12 to "Twelfth",
    )

    /**
     * Capitalized word forms for 3-12 only (sentence-initial in the
     * lineage line). The table STOPS at 12 — 13 falls through to the
     * numeral `"13th"` via the `%100` special case BEFORE the `%10`
     * check — an intentional asymmetry; do not "complete" the table or
     * reorder the checks (both reintroduce the classic 111/112/113
     * suffix bug). The floor of 3 relies on the caller's
     * `LINEAGE_MIN_WALKS >= 3` guard, not its own enforcement.
     */
    fun ordinalWord(n: Int): String {
        ORDINAL_WORDS[n]?.let { return it }
        if (n % 100 in 11..13) return "${n}th"
        return when (n % 10) {
            1 -> "${n}st"
            2 -> "${n}nd"
            3 -> "${n}rd"
            else -> "${n}th"
        }
    }

    /**
     * Threads with an appearance on the CURRENT walk, in
     * [ThreadStore.build]'s own lemma-alphabetical order — the SAME
     * order decides which theme's name ships in FOUR of the 8 possible
     * lines (markerColoring, climbAnchoring, weatherWeave,
     * photoAdjacency all iterate this directly). A "better UX" recency
     * sort here would silently change winners.
     */
    fun activeThreads(input: SenseInput): List<ActiveThread> =
        input.threads.filter { thread -> thread.appearances.any { it.walkId == input.currentWalkId } }
}
