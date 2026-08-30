// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import java.time.Instant
import kotlin.math.floor

/** A candidate place-cluster's summary — winner selection happens in
 * [DossierSensesTracks.bestCluster]. */
private data class PlaceCluster(val mentionCount: Int, val walkCount: Int, val spread: Double)

/** One placeResonance cluster candidate member: a qualifying recording's
 * uuid/walk id/coordinate, carrying its OWN appearance's within-recording
 * mention count through to [DossierSensesTracks.bestCluster] — a
 * cluster's mentionCount is the SUM of its members' own counts (iOS
 * `DossierSensesTracks.swift:81@0172e2b`: `near.reduce(0) { $0 +
 * $1.appearance.mentionCount }`), never the number of qualifying
 * recordings. */
private data class PlaceMember(
    val recordingUuid: String,
    val walkId: Long,
    val coordinate: Coordinate,
    val mentionCount: Int,
)

/** One steepest-run candidate — winner selection happens in
 * [DossierSensesTracks.steepestSustainedAscent]. */
private data class AscentRun(val start: Instant, val end: Instant, val gain: Double, val averageRate: Double)

/** One rate-bearing gap between two consecutive (already-smoothed)
 * elevation samples; `dt <= 0` pairs never become a segment at all.
 * [startAltitude]/[endAltitude] are the SMOOTHED altitudes at each
 * endpoint (not a delta) so a multi-segment run's gain can telescope to
 * `endAltitude - startAltitude` across its own first and last segment —
 * summing every segment's own delta instead silently drops whatever a
 * `dt <= 0` excluded gap inside the run would otherwise have
 * contributed. */
private data class ClimbSegment(
    val start: Instant,
    val end: Instant,
    val startAltitude: Double,
    val endAltitude: Double,
    val rate: Double,
)

/** The app's stored `Walk.weatherCondition` vocabulary, collapsed into
 * the 7 buckets weatherWeave reasons about. `UNKNOWN` is a first-class
 * case (a failed/absent read), not a null sentinel. */
enum class WeatherBucket { RAIN, SNOW, CLEAR, CLOUD, WIND, FOG, UNKNOWN }

/**
 * The eight sense implementations, ported from
 * `Pilgrim/Models/Threads/DossierSensesTracks.swift` (parity spec
 * `docs/parity/2026-08-26-threads-senses-port.md`). Dispatched by
 * [DossierSenses.evaluate] in [DossierSenses.Sense] declaration order.
 */
internal object DossierSensesTracks {

    // ------------------------------------------------------------------
    // 1/8 — placeResonance (cross-walk, place-tied)
    // ------------------------------------------------------------------

    /**
     * Silent until cross-walk backfill completes (a hard content gate,
     * not a loading-spinner concern). Two windowing granularities: the
     * per-RECORDING instant (via [SenseInput.recordingTimestamps],
     * inclusive both ends) decides which mentions/coordinates
     * participate; the WALK date (`appearance.date`) decides the
     * distinct-walk count — the two must never collapse to one index.
     */
    fun placeResonance(input: SenseInput, suppressed: Set<String>): SenseLine? {
        if (!input.backfillComplete) return null
        val windowStart = input.walkStart.minus(ThreadStore.RECURRENCE_WINDOW)

        fun inWindow(recordingUuid: String): Boolean {
            val instant = input.recordingTimestamps[recordingUuid] ?: return false
            return !instant.isBefore(windowStart) && !instant.isAfter(input.walkEnd)
        }

        fun qualifiedCoordinate(recordingUuid: String): Coordinate? {
            val fix = input.fixes[recordingUuid] ?: return null
            return fix.coordinate.takeIf { DossierSenses.qualifies(fix) }
        }

        // Baseline: median pairwise distance across ALL in-window mention
        // recordings, any theme — the specificity guard's denominator.
        val mentionCoordinates = linkedMapOf<String, Coordinate>()
        for (thread in input.threads) {
            for (appearance in thread.appearances) {
                if (!inWindow(appearance.recordingUuid)) continue
                qualifiedCoordinate(appearance.recordingUuid)?.let { mentionCoordinates[appearance.recordingUuid] = it }
            }
        }
        // Determinism via UUID-STRING lexicographic sort — Android's
        // recording uuid is already the canonical string form, so plain
        // String ordering IS uuidString ordering.
        val ordered = mentionCoordinates.entries.sortedBy { it.key }.map { it.value }
        if (ordered.size < 2) return null
        val pairwise = mutableListOf<Double>()
        for (i in 0 until ordered.size - 1) {
            for (j in (i + 1) until ordered.size) {
                pairwise += DossierSenses.distanceMeters(ordered[i], ordered[j])
            }
        }
        val baseline = DossierSenses.median(pairwise)

        // Candidate cap BEFORE suppression filter — `.take(4)` then
        // filter, never the reverse (which would shrink the pool and let
        // a 5th-ranked thread surface that iOS never reaches).
        for (thread in DossierSenses.activeThreads(input).take(DossierSenses.PLACE_CANDIDATE_THEME_CAP)) {
            if (thread.lemma in suppressed) continue

            val distinctWalks = thread.appearances
                .filter { !it.date.isBefore(windowStart) && !it.date.isAfter(input.walkEnd) }
                .map { it.walkId }
                .distinct()
            if (distinctWalks.size < 2) continue

            val members = thread.appearances
                .filter { inWindow(it.recordingUuid) }
                .mapNotNull { appearance ->
                    qualifiedCoordinate(appearance.recordingUuid)?.let { coordinate ->
                        PlaceMember(appearance.recordingUuid, appearance.walkId, coordinate, appearance.mentionCount)
                    }
                }
            val cluster = bestCluster(members) ?: continue

            // Strict: a walker whose every recording shares one spot has
            // baseline 0 — nothing can be "more specific" than routine.
            // Never special-case baseline == 0 to pass.
            if (!(cluster.spread < baseline / 2.0)) continue

            val times = if (cluster.mentionCount == 2) "twice" else "${cluster.mentionCount} times"
            return SenseLine(
                text = "'${thread.displayTerm}' has surfaced on ${distinctWalks.size} walks — " +
                    "$times near the same stretch of ground.",
                lemma = thread.lemma,
            )
        }
        return null
    }

    /**
     * Deterministic seed-centered clustering. For each member in
     * uuid-string order as seed, the candidate cluster is everything
     * within [DossierSenses.PLACE_CLUSTER_RADIUS_METERS] (inclusive).
     * The cluster's mentionCount is the SUM of each near member's own
     * appearance mentionCount — NOT the number of qualifying
     * recordings; a single recording mentioning a lemma 3 times
     * outweighs three recordings mentioning it once each. Winner by
     * highest mentionCount, then smallest spread, then earliest seed in
     * iteration order — STRICT inequalities only, so the first winner
     * is kept on exact ties.
     */
    private fun bestCluster(members: List<PlaceMember>): PlaceCluster? {
        var best: PlaceCluster? = null
        for (seed in members.sortedBy { it.recordingUuid }) {
            val near = members.filter { DossierSenses.distanceMeters(seed.coordinate, it.coordinate) <= DossierSenses.PLACE_CLUSTER_RADIUS_METERS }
            val mentionCount = near.sumOf { it.mentionCount }
            val walkCount = near.map { it.walkId }.distinct().size
            if (mentionCount < 2 || walkCount < 2) continue
            var spread = 0.0
            for (i in near.indices) {
                for (j in (i + 1) until near.size) {
                    spread = maxOf(spread, DossierSenses.distanceMeters(near[i].coordinate, near[j].coordinate))
                }
            }
            val current = best
            if (current == null || mentionCount > current.mentionCount ||
                (mentionCount == current.mentionCount && spread < current.spread)
            ) {
                best = PlaceCluster(mentionCount = mentionCount, walkCount = walkCount, spread = spread)
            }
        }
        return best
    }

    // ------------------------------------------------------------------
    // 2/8 — moonLine (once per lunation)
    // ------------------------------------------------------------------

    /**
     * Once-per-lunation is an INDEX comparison, not a boolean flag (a
     * flag would mis-handle a user skipping a lunation entirely).
     * Lunation membership is half-open `[start, end)`. A fourth guard —
     * at least one WORDED walk in the closed lunation — is distinct from
     * `currentWalkHasWords` (the current walk lives in the OPEN
     * lunation). The theme-less fallback still fires (and still burns
     * the once-per-lunation budget) when no un-suppressed theme has an
     * in-lunation appearance.
     */
    fun moonLine(input: SenseInput, suppressed: Set<String>): SenseLine? {
        val moon = input.moon ?: return null
        if (moon.lastReportedIndex == moon.lunationIndex) return null
        if (!moon.currentWalkHasWords) return null

        fun inLunation(date: Instant): Boolean = !date.isBefore(moon.start) && date.isBefore(moon.end)

        val walkCount = moon.allWalkDates.count { inLunation(it) }
        val wordedCount = moon.wordedWalkDates.count { inLunation(it) }
        if (wordedCount < 1) return null

        var text = "The ${moon.moonName} has set: $walkCount walk${if (walkCount == 1) "" else "s"}, " +
            "$wordedCount with recorded words"

        val topTheme = input.threads
            .mapNotNull { thread ->
                if (thread.lemma in suppressed) return@mapNotNull null
                val walks = thread.appearances.filter { inLunation(it.date) }.map { it.walkId }.distinct().size
                if (walks < 1) return@mapNotNull null
                Triple(thread.lemma, thread.displayTerm, walks)
            }
            // Max walks; on a tie, the lexicographically SMALLEST lemma
            // wins — the crossed-index comparator idiom (each side pairs
            // one candidate's count with the OTHER's lemma).
            .minWithOrNull(compareBy({ -it.third }, { it.first }))
            ?: return SenseLine(text = "$text.", lemma = null)

        text += "; '${topTheme.second}' walked in ${topTheme.third} of them."
        return SenseLine(text = text, lemma = topTheme.first)
    }

    // ------------------------------------------------------------------
    // 3/8 — markerColoring (current walk)
    // ------------------------------------------------------------------

    /**
     * First-match-wins nested loop — NOT best-of-all-candidates (the
     * opposite strategy from [photoAdjacency]'s global-best scan).
     * Iterates active themes in lemma-alphabetical order, then the
     * current walk's recordings in array order, returning on the FIRST
     * qualifying pair.
     */
    fun markerColoring(input: SenseInput, suppressed: Set<String>): SenseLine? {
        for (thread in DossierSenses.activeThreads(input)) {
            if (thread.lemma in suppressed) continue
            for (recording in input.currentRecordings) {
                val theme = recording.themes.firstOrNull { it.lemma == thread.lemma } ?: continue
                val text = markerLine(theme, thread.displayTerm, recording.text) ?: continue
                return SenseLine(text = text, lemma = thread.lemma)
            }
        }
        return null
    }

    /**
     * Merged ±15-token windows (IndexSet-equivalent union — overlapping
     * mention windows count each token once), an absolute floor, a
     * density gate, and TWO different denominators: the gate compares
     * window density against OVERALL density, but the DISPLAYED ratio
     * uses the REST of the transcript (falling back to overall only when
     * the rest holds zero absolutist words — a descriptive line may
     * understate, never overstate). `Int(ratio)` truncates toward zero.
     */
    private fun markerLine(theme: Theme, displayTerm: String, text: String): String? {
        val tokens = TranscriptNlp.wordTokenOffsets(text)
        if (tokens.isEmpty()) return null

        val windowIndices = sortedSetOf<Int>()
        for (mention in theme.mentions) {
            val anchor = tokens.indexOfLast { it.start <= mention.start }
            if (anchor < 0) continue
            val lo = maxOf(0, anchor - DossierSenses.MARKER_WINDOW_RADIUS)
            val hi = minOf(tokens.size - 1, anchor + DossierSenses.MARKER_WINDOW_RADIUS)
            for (i in lo..hi) windowIndices += i
        }

        val windowTokens = windowIndices.map { tokens[it] }
        val windowAbsolutist = windowTokens.count { it.token in MarkerLexicons.absolutist }
        if (windowAbsolutist < DossierSenses.MARKER_MIN_WINDOW_ABSOLUTIST) return null

        val totalAbsolutist = tokens.count { it.token in MarkerLexicons.absolutist }
        val windowDensity = windowAbsolutist.toDouble() / windowTokens.size.toDouble()
        val overallDensity = totalAbsolutist.toDouble() / tokens.size.toDouble()
        if (overallDensity <= 0.0 || windowDensity < DossierSenses.MARKER_MIN_DENSITY_RATIO * overallDensity) return null

        val restTokenCount = tokens.size - windowTokens.size
        val restAbsolutist = totalAbsolutist - windowAbsolutist
        val restDensity = if (restTokenCount > 0) restAbsolutist.toDouble() / restTokenCount.toDouble() else 0.0
        val ratio = if (restDensity > 0.0) windowDensity / restDensity else windowDensity / overallDensity

        return "Absolutist words cluster around '$displayTerm' — " +
            "${DossierSenses.timesPhrase(ratio.toInt())} the density of the rest of the walk's speech."
    }

    // ------------------------------------------------------------------
    // 4/8 — intentionLineage (cross-walk)
    // ------------------------------------------------------------------

    /**
     * `nil` and empty-string intentions are identically silence; the
     * stoplist applies symmetrically to today's AND every historical
     * intention.
     *
     * [SpokenStoplist.nonContentLemmas] is the shared content-word
     * definition, so a lineage claim cannot rest on a word the theme
     * layer already discards — "fourth walk carrying some form of 'day'"
     * is a sentence about nothing.
     */
    private fun intentionLemmas(intention: String): Set<String> =
        TranscriptNlp.contentLemmaMentions(intention).map { it.lemma }.toSet() - SpokenStoplist.nonContentLemmas

    /**
     * Lemmas shared with today, on ≥ [DossierSenses.LINEAGE_MIN_WALKS]
     * distinct walks, not suppressed; max-by-count, tie → lexicographically
     * smallest lemma (the SAME crisscross-min idiom as [moonLine] — the
     * tie-break must be implemented identically in both). The window here
     * is INCLUSIVE on both ends, unlike moonLine's half-open lunation
     * check. The emitted "last 30 days" is hardcoded copy, not derived
     * from [ThreadStore.RECURRENCE_WINDOW] at format time — replicate the
     * hardcode, don't "fix" it to derive.
     */
    fun intentionLineage(input: SenseInput, suppressed: Set<String>): SenseLine? {
        val windowStart = input.walkStart.minus(ThreadStore.RECURRENCE_WINDOW)
        val inWindow = input.walkSnapshots.filter {
            !it.startDate.isBefore(windowStart) && !it.startDate.isAfter(input.walkEnd)
        }
        val today = inWindow.firstOrNull { it.walkId == input.currentWalkId } ?: return null
        val todayIntention = today.intention
        if (todayIntention.isNullOrEmpty()) return null
        val todayLemmas = intentionLemmas(todayIntention)
        if (todayLemmas.isEmpty()) return null

        val familyWalks = mutableMapOf<String, MutableSet<Long>>()
        for (walk in inWindow) {
            val intention = walk.intention
            if (intention.isNullOrEmpty()) continue
            for (lemma in intentionLemmas(intention)) {
                familyWalks.getOrPut(lemma) { mutableSetOf() } += walk.walkId
            }
        }

        val candidate = familyWalks.entries
            .filter { (lemma, walks) ->
                lemma in todayLemmas && walks.size >= DossierSenses.LINEAGE_MIN_WALKS && lemma !in suppressed
            }
            .minWithOrNull(compareBy({ -it.value.size }, { it.key }))
            ?: return null

        return SenseLine(
            text = "${DossierSenses.ordinalWord(candidate.value.size)} walk in the last 30 days " +
                "carrying some form of '${candidate.key}'.",
            lemma = candidate.key,
        )
    }

    // ------------------------------------------------------------------
    // 5/8 — climbAnchoring (current walk)
    // ------------------------------------------------------------------

    /**
     * Cheap pre-gate first (`totalAscent >= 50`, a WHOLE-walk floor,
     * separate from the per-run 20m floor inside the finder), then
     * interval-OVERLAP match (not containment — a recording that started
     * before the climb and continued into it is the common mid-climb
     * case). First-fit across lemma-ordered active threads.
     */
    fun climbAnchoring(input: SenseInput, suppressed: Set<String>): SenseLine? {
        if (input.totalAscent < DossierSenses.CLIMB_MIN_TOTAL_ASCENT_METERS) return null
        val run = steepestSustainedAscent(input.elevationSeries) ?: return null

        for (thread in DossierSenses.activeThreads(input)) {
            if (thread.lemma in suppressed) continue
            val onClimb = input.currentRecordings.any { recording ->
                recording.themes.any { it.lemma == thread.lemma } &&
                    !recording.start.isAfter(run.end) && !recording.end.isBefore(run.start)
            }
            if (onClimb) {
                return SenseLine(text = "'${thread.displayTerm}' was spoken on the day's steepest climb.", lemma = thread.lemma)
            }
        }
        return null
    }

    /** Centered moving average (window 5 → 2 before, self, 2 after,
     * clamped at edges) — a TRAILING average would shift smoothed
     * altitude in time and change which segment crosses the threshold. */
    private fun smoothedAltitudes(series: List<ElevationSample>): List<ElevationSample> {
        val half = DossierSenses.CLIMB_SMOOTHING_WINDOW / 2
        return series.indices.map { i ->
            val lo = maxOf(0, i - half)
            val hi = minOf(series.size - 1, i + half)
            val mean = (lo..hi).sumOf { series[it].altitude } / (hi - lo + 1)
            ElevationSample(timestamp = series[i].timestamp, altitude = mean)
        }
    }

    private fun buildSegments(series: List<ElevationSample>): List<ClimbSegment> {
        val segments = mutableListOf<ClimbSegment>()
        for (i in 0 until series.size - 1) {
            val a = series[i]
            val b = series[i + 1]
            val dtSeconds = secondsBetween(a.timestamp, b.timestamp)
            if (dtSeconds <= 0.0) continue
            segments += ClimbSegment(
                start = a.timestamp,
                end = b.timestamp,
                startAltitude = a.altitude,
                endAltitude = b.altitude,
                rate = (b.altitude - a.altitude) / dtSeconds,
            )
        }
        return segments
    }

    /**
     * Top-decile threshold over POSITIVE rates only (descents/flats
     * excluded before ranking); threshold index is `Int((count-1) * 0.9)`
     * — truncated nearest-rank-down, never interpolated or rounded. The
     * in-progress-run force-close at series end is load-bearing: dropping
     * it silently discards the steepest run whenever the walk ends
     * mid-climb. A run's gain is the smoothed END altitude minus the
     * smoothed START altitude (iOS `DossierSensesTracks.swift:313@0172e2b`:
     * `smoothed[endSampleIndex].altitude - smoothed[startSampleIndex].altitude`)
     * — NOT a sum of each surviving segment's own delta. The
     * run-continuation loop below is array-position-based over
     * [ClimbSegment]s, blind to any `dt <= 0` sample excluded from that
     * array; a sum would silently omit an excluded gap's real altitude
     * change, while the endpoint difference telescopes straight across it.
     */
    private fun steepestSustainedAscent(series: List<ElevationSample>): AscentRun? {
        val segments = buildSegments(smoothedAltitudes(series))
        val positive = segments.map { it.rate }.filter { it > 0.0 }.sorted()
        if (positive.isEmpty()) return null
        val threshold = positive[(floor((positive.size - 1) * DossierSenses.CLIMB_TOP_DECILE)).toInt()]

        var best: AscentRun? = null
        var runStartIndex: Int? = null

        fun closeRun(endingAtIndex: Int) {
            val startIndex = runStartIndex ?: return
            runStartIndex = null
            val startSegment = segments[startIndex]
            val endSegment = segments[endingAtIndex]
            val gain = endSegment.endAltitude - startSegment.startAltitude
            val duration = secondsBetween(startSegment.start, endSegment.end)
            if (gain < DossierSenses.CLIMB_MIN_RUN_GAIN_METERS || duration <= 0.0) return
            val averageRate = gain / duration
            val current = best
            if (current == null || averageRate > current.averageRate) {
                best = AscentRun(start = startSegment.start, end = endSegment.end, gain = gain, averageRate = averageRate)
            }
        }

        for (index in segments.indices) {
            val segment = segments[index]
            if (segment.rate >= threshold && segment.rate > 0.0) {
                if (runStartIndex == null) runStartIndex = index
                if (index == segments.size - 1) closeRun(index)
            } else if (runStartIndex != null) {
                closeRun(index - 1)
            }
        }
        return best
    }

    // ------------------------------------------------------------------
    // 6/8 — weatherWeave (cross-walk)
    // ------------------------------------------------------------------

    /**
     * A nil/unmapped stored condition lands in [WeatherBucket.UNKNOWN],
     * which excludes the walk from claims. The ship-gate-tightened
     * (2026-08-25) mode-strict guard: the shared bucket's own count must
     * be STRICTLY below the window's highest condition count — a TIE
     * with the mode still suppresses (conservative). Do NOT port the
     * older ">50% majority" rule. Every one of the theme's walk buckets
     * must be identically the SAME known bucket (`allSatisfy` —
     * unanimous, not 80%); a walk with no weather data maps to
     * `.unknown` and breaks unanimity. First qualifying theme in lemma
     * order wins.
     */
    fun weatherWeave(input: SenseInput, suppressed: Set<String>): SenseLine? {
        val windowStart = input.walkStart.minus(ThreadStore.RECURRENCE_WINDOW)
        val inWindow = input.walkSnapshots.filter {
            !it.startDate.isBefore(windowStart) && !it.startDate.isAfter(input.walkEnd)
        }
        val buckets = mutableMapOf<Long, WeatherBucket>()
        for (row in inWindow) {
            buckets[row.walkId] = row.weatherCondition?.let { bucketForStoredCondition(it) } ?: WeatherBucket.UNKNOWN
        }
        val known = buckets.values.filter { it != WeatherBucket.UNKNOWN }
        if (known.isEmpty()) return null

        val conditionCounts = known.groupingBy { it }.eachCount()
        val modeCount = conditionCounts.values.maxOrNull() ?: 0

        for (thread in DossierSenses.activeThreads(input)) {
            if (thread.lemma in suppressed) continue
            val walkIds = thread.appearances
                .filter { !it.date.isBefore(windowStart) && !it.date.isAfter(input.walkEnd) }
                .map { it.walkId }
                .distinct()
            if (walkIds.size < 2) continue

            val themeBuckets = walkIds.map { buckets[it] ?: WeatherBucket.UNKNOWN }
            val shared = themeBuckets.firstOrNull() ?: continue
            if (shared == WeatherBucket.UNKNOWN) continue
            if (!themeBuckets.all { it == shared }) continue
            if ((conditionCounts[shared] ?: 0) >= modeCount) continue
            val phrase = skyPhrase(shared) ?: continue

            val head = if (walkIds.size == 2) "Both walks" else "All ${walkIds.size} walks"
            return SenseLine(text = "$head where '${thread.displayTerm}' surfaced were $phrase.", lemma = thread.lemma)
        }
        return null
    }

    /**
     * Collapses the app's stored [org.walktalkmeditate.pilgrim.data.weather.WeatherCondition]
     * rawValues. All 10 rawValues map to 6 known buckets (cloud absorbs
     * 3, rain absorbs 3); the `else` branch is dead against the current
     * enum but kept for legacy/corrupted strings — [WeatherBucketTest]
     * enforces totality over the CURRENT vocabulary.
     */
    internal fun bucketForStoredCondition(raw: String): WeatherBucket = when (raw) {
        "clear" -> WeatherBucket.CLEAR
        "partlyCloudy", "overcast", "haze" -> WeatherBucket.CLOUD
        "lightRain", "heavyRain", "thunderstorm" -> WeatherBucket.RAIN
        "snow" -> WeatherBucket.SNOW
        "fog" -> WeatherBucket.FOG
        "wind" -> WeatherBucket.WIND
        else -> WeatherBucket.UNKNOWN
    }

    /** Per-bucket preposition split (under/in) — 6 distinct phrase
     * strings, NOT a `"under \(noun)"` formatter. `.unknown` is the only
     * bucket with no phrase. */
    private fun skyPhrase(bucket: WeatherBucket): String? = when (bucket) {
        WeatherBucket.RAIN -> "under rain"
        WeatherBucket.SNOW -> "under snow"
        WeatherBucket.CLEAR -> "under clear skies"
        WeatherBucket.CLOUD -> "under clouds"
        WeatherBucket.WIND -> "in wind"
        WeatherBucket.FOG -> "in fog"
        WeatherBucket.UNKNOWN -> null
    }

    // ------------------------------------------------------------------
    // 7/8 — photoAdjacency (current walk, place-tied)
    // ------------------------------------------------------------------

    /**
     * Scores ALL candidates and keeps the single global best — the
     * OPPOSITE strategy from [markerColoring]. BOTH gates required
     * (separation ≤ 75m AND gap ≤ 600s); the recording's own route fix
     * must pass [DossierSenses.qualifies] before any photo is considered.
     * Tie-break is a true lexicographic tuple compare (separation, gap,
     * capturedAt) against ONE running global best across all
     * (thread, recording, photo) triples — not scoped per thread.
     */
    fun photoAdjacency(input: SenseInput, suppressed: Set<String>): SenseLine? {
        val placedPhotos = input.photos.mapNotNull { photo -> photo.coordinate?.let { photo.capturedAt to it } }
        if (placedPhotos.isEmpty()) return null

        var best: PhotoCandidate? = null

        for (thread in DossierSenses.activeThreads(input)) {
            if (thread.lemma in suppressed) continue
            for (recording in input.currentRecordings) {
                if (recording.themes.none { it.lemma == thread.lemma }) continue
                val fix = input.fixes[recording.uuid] ?: continue
                if (!DossierSenses.qualifies(fix)) continue
                for ((capturedAt, coordinate) in placedPhotos) {
                    val separation = DossierSenses.distanceMeters(fix.coordinate, coordinate)
                    if (separation > DossierSenses.PHOTO_TIE_RADIUS_METERS) continue
                    val gap = intervalGap(capturedAt, recording.start, recording.end)
                    if (gap > DossierSenses.PHOTO_TIE_MAX_INTERVAL_SECONDS) continue

                    val candidate = PhotoCandidate(separation, gap, capturedAt, thread.lemma, thread.displayTerm)
                    val current = best
                    // Place first, time second, then capture order — the
                    // tie is about ground shared, not clocks. A true
                    // lexicographic tuple compare against ONE running
                    // global best, not scoped per thread.
                    if (current == null || candidate.isBetterThan(current)) {
                        best = candidate
                    }
                }
            }
        }

        val winner = best ?: return null
        return SenseLine(text = "A photo was taken near where '${winner.displayTerm}' was spoken.", lemma = winner.lemma)
    }

    private data class PhotoCandidate(
        val separation: Double,
        val gap: Double,
        val capturedAt: Instant,
        val lemma: String,
        val displayTerm: String,
    ) {
        fun isBetterThan(other: PhotoCandidate): Boolean = when {
            separation != other.separation -> separation < other.separation
            gap != other.gap -> gap < other.gap
            else -> capturedAt < other.capturedAt
        }
    }

    /** Zero inside the span (inclusive both ends — a midpoint/start-only
     * distance would fail the 600s guard for a photo captured mid-way
     * through a long recording), else the min distance to either edge. */
    private fun intervalGap(instant: Instant, start: Instant, end: Instant): Double {
        if (!instant.isBefore(start) && !instant.isAfter(end)) return 0.0
        return minOf(
            kotlin.math.abs(secondsBetween(start, instant)),
            kotlin.math.abs(secondsBetween(end, instant)),
        )
    }

    // ------------------------------------------------------------------
    // 8/8 — speechShape (current walk)
    // ------------------------------------------------------------------

    /**
     * `allSatisfy` — EVERY worded recording must end at-or-before the
     * exact first-third mark; checking only the last recording changes
     * the claim from "all words front-loaded" to "words trailed off".
     * The wordless-remainder guard is strict `>` (exactly 30:00 produces
     * silence); minutes truncate, never round. `lemma = null` always —
     * speechShape never joins lemma dedup in either direction.
     */
    fun speechShape(input: SenseInput, @Suppress("UNUSED_PARAMETER") suppressed: Set<String>): SenseLine? {
        val worded = input.currentRecordings.filter { it.wordCount > 0 }
        if (worded.isEmpty()) return null
        val spanSeconds = secondsBetween(input.walkStart, input.walkEnd)
        if (spanSeconds <= 0.0) return null
        val firstThirdEnd = input.walkStart.plusSecondsPrecise(spanSeconds / 3.0)
        if (!worded.all { !it.end.isAfter(firstThirdEnd) }) return null
        val lastEnd = worded.maxOf { it.end }
        val remainderSeconds = secondsBetween(lastEnd, input.walkEnd)
        if (remainderSeconds <= DossierSenses.SPEECH_SHAPE_MIN_WORDLESS_REMAINDER_SECONDS) return null
        val minutes = (remainderSeconds / 60.0).toInt()
        return SenseLine(
            text = "All the words came in the first third; the last $minutes minutes were wordless.",
            lemma = null,
        )
    }
}
