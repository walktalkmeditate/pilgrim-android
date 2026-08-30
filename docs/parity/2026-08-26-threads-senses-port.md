# Parity Spec: threads-senses

| field | value |
|---|---|
| **iOS pin** | `0172e2b` (main, per CLAUDE.md) |
| **Android HEAD** | `3c61991b` |
| **Generated** | 2026-08-26 |
| **Type** | port |
| **Generator** | ios-parity skill |

---

## iOS source map

- `Pilgrim/Models/Threads/DossierSenses.swift` — 205 LOC — pure sense engine: 16 tuning constants, input/output value types, the 8-case `Sense` priority enum, the `lines()` dispatcher (cap + lemma dedup), and shared helpers (`qualifies`, `distance`, `median`, `timesPhrase`, `ordinalWord`, `activeThreads`).
- `Pilgrim/Models/Threads/DossierSensesTracks.swift` — 431 LOC — the eight sense implementations: placeResonance, moonLine, intentionLineage, markerColoring, photoAdjacency, climbAnchoring (with smoothing + steepest-run finder), speechShape, weatherWeave (with bucket/skyPhrase tables).
- `Pilgrim/Models/Threads/LunationCalendar.swift` — 67 LOC — synodic-month windows (`Lunation`) minted from LunarPhase's epoch; `lunation(containing:)`, `mostRecentClosed(asOf:)`, and the 12-name month→moon-name table.
- `Pilgrim/Models/LunarPhase.swift` — 48 LOC — moon-phase math: `synodicMonth` + `knownNewMoon` epoch constants (the single source LunationCalendar derives from), age/illumination/8-bucket phase name.
- `Pilgrim/Models/Weather/WeatherService.swift` — 153 LOC — `WeatherCondition` storable vocabulary (10 rawValue strings) that weatherWeave's bucket table collapses; the rest of the file (fetch/icon/label) is out of slice.
- `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift` — 480 LOC — senses assembly: `DossierSensesFetchBundle`, `gatherSensesBundle`, `MemoKey` memoization, `appendSensesBlock` (**Noticed:** append + moon-state write), `resolveFixes`, `makeSensesInput`, plus the DEBUG `DossierSensesFieldReport` harness.
- `Pilgrim/AppDelegate.swift` — 269 LOC — field-report harness registration in `runPostDoneLaunchTasks()` (fire-and-forget after `.done`), demo-mode early-return path that excludes it.
- `Pilgrim/Models/Data/DataManager+VoiceRecording.swift` — 310 LOC — `voiceRecordingTimestampIndex` (per-recording instants), `voiceRecordingWalkIndex` (recording→walk join over the frozen legacy relationship column), transcription-save path that triggers/clears theme analysis.
- `Pilgrim/Models/Data/DataManager+Query.swift` — 242 LOC — `routeFixNear` (bounded ±90 s route-sample fetch, cap 240, (gap, accuracy)-min pick) and `walkSensesSnapshot` (one row per walk in range).

---

## Behavior

### Dispatcher — `DossierSenses.lines` (cap / priority / dedup / Output)

**Purity contract** *(consensus: behavior + edge-cases)* — the engine is a pure function of its `Input`; no store, no singleton, no clock:

```swift
/// Pure sense engine for the dossier's `Noticed:` block. Binding purity
/// contract (spec principle 8): no DataManager, CoreStore, or singleton
/// access — every input arrives as an argument, fetched by the builder, so
/// every line stays traceable to enumerable, deterministic inputs. `Date()`
/// is never called here; time arrives as data.
```

> `Pilgrim/Models/Threads/DossierSenses.swift:4-8@0172e2b`

A Kotlin port must not reach for `Clock.System.now()` or a repository inside a sense function — time and data arrive as arguments only, so goldens stay deterministic.

**Priority order is the enum's declaration order** *(consensus: all 4 lenses)* — and a ninth sense was deliberately cut:

```swift
/// Declaration order IS the spec's binding priority order — reordering
/// cases reorders the block. `questionDensity` was cut at the ship gate
/// (2026-08-25): real-device history fired it once, and the line was a
/// Whisper punctuation artifact ("151 of today's sentences were
/// questions"), not genuine question density — the spec's own
/// contingency was to cut at the gate, not patch.
enum Sense: CaseIterable {
    case placeResonance, moonLine, markerColoring, intentionLineage,
         climbAnchoring, weatherWeave, photoAdjacency, speechShape
}
```

> `Pilgrim/Models/Threads/DossierSenses.swift:102-111@0172e2b`

Exactly 8 cases, in exactly this order. Do NOT port `questionDensity` — any older design doc or planning note describing 9 senses is stale as of the 2026-08-25 ship gate.

**Cap is an early `break`, not a truncation** *(consensus: behavior + edge-cases + ui-visual)* — senses past the cap are never evaluated at all:

```swift
var used = Set<String>()
var lines: [String] = []
var reportedLunationIndex: Int?
for sense in Sense.allCases {
    guard lines.count < lineCap else { break }
    guard let line = evaluate(sense, input, used) else { continue }
```

> `Pilgrim/Models/Threads/DossierSenses.swift:119-124@0172e2b`

`lineCap = 3`, strict `<`, `break` not `continue`. An evaluate-all-then-slice port changes "never evaluated" into "evaluated then discarded" — today safe only because moonLine (rank 2) can never be cap-skipped, but fragile under any reordering (`Pilgrim/Models/Threads/DossierSenses.swift:122-124@0172e2b`, ui-visual).

**Dispatcher-level lemma dedup — belt over the senses' own suppression** *(consensus: behavior + edge-cases + ui-visual)*:

```swift
// Belt over the senses' own suppression: a theme named at a
// higher rank never reappears, whatever a sense returns.
if let lemma = line.lemma {
    guard !used.contains(lemma) else { continue }
    used.insert(lemma)
}
```

> `Pilgrim/Models/Threads/DossierSenses.swift:125-130@0172e2b`

`continue` (not `break`) — still-lower senses keep firing. Every sense also self-filters on `suppressed` internally; this second check is the safety net for a future sense that forgets. Lines with `lemma == nil` (speechShape always; moonLine's theme-less form) bypass the dedup entirely — they never register in `used` and can never be suppressed.

**`reportedLunationIndex` is set only at append** *(consensus: behavior + ui-visual + data)*:

```swift
lines.append(line.text)
if sense == .moonLine {
    reportedLunationIndex = input.moon?.lunationIndex
}
```

> `Pilgrim/Models/Threads/DossierSenses.swift:131-134@0172e2b`

Set only after the line survived BOTH the cap break-guard and the lemma-dedup guard in the same iteration — never merely because `moonLine(...)` returned non-nil. This is what keeps the once-per-lunation budget honest (the persistence write downstream is gated on this value; see Builder).

**Injectable `evaluate` test seam** *(synthesizer source-verification addition — not flagged by any lens)*:

```swift
/// `evaluate` is a test seam (same style as ThreadsBackfill's
/// `snapshotProvider`); production callers use the default dispatch.
static func lines(
    input: Input,
    evaluate: (Sense, Input, Set<String>) -> SenseLine? = { DossierSenses.evaluate($0, input: $1, suppressed: $2) }
) -> Output {
```

> `Pilgrim/Models/Threads/DossierSenses.swift:113-118@0172e2b`

The public `evaluate(_:input:suppressed:)` switch (`Pilgrim/Models/Threads/DossierSenses.swift:139-150@0172e2b`) is also called directly by the field-report harness — Android's dispatcher should expose the same per-sense entry point.

### Sense 1/8 — placeResonance (cross-walk, place-tied)

**Hard gates before any work** *(consensus: behavior + edge-cases + ui-visual)*:

```swift
static func placeResonance(input: Input, suppressed: Set<String>) -> SenseLine? {
    guard input.backfillComplete else { return nil }
    let windowStart = input.walkStart.addingTimeInterval(-ThreadStore.recurrenceWindow)
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:14-16@0172e2b`

Unconditionally silent until cross-walk backfill completes — a hard content gate, not a loading-spinner concern. Window = `walkStart - ThreadStore.recurrenceWindow` (30 days, shared constant — see Data).

**Two windowing granularities inside one sense** — recording-instant vs walk-date:

```swift
func inWindow(_ uuid: UUID) -> Bool {
    guard let instant = input.recordingTimestamps[uuid] else { return false }
    return instant >= windowStart && instant <= input.walkEnd
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:17-20@0172e2b`

Coordinates/baseline use per-RECORDING instants via `recordingTimestamps` (inclusive both ends), while the distinct-walk count below uses `appearance.date` (the walk date). Do not collapse to one index (`Pilgrim/Models/Data/DataManager+VoiceRecording.swift:156-160@0172e2b` documents the distinction).

**Baseline: median pairwise distance across ALL in-window mention coordinates, any theme**:

```swift
// Baseline spread: median pairwise distance across ALL in-window
// mention recordings, any theme — the specificity guard's denominator.
```

```swift
let ordered = mentionCoordinates.sorted { $0.key.uuidString < $1.key.uuidString }.map(\.value)
guard ordered.count >= 2 else { return nil }
var pairwise: [Double] = []
for i in 0..<(ordered.count - 1) {
    for j in (i + 1)..<ordered.count {
        pairwise.append(distance(ordered[i], ordered[j]))
    }
}
let baseline = median(pairwise)
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:25-26,35-43@0172e2b`

Upper-triangular double loop (`i < j`, half-open ranges) — inclusive ranges or `j` starting at `i` would double-count/self-distance and skew the baseline low (edge-cases). Ordering is by `UUID.uuidString` lexicographic compare — `java.util.UUID.compareTo` orders by signed 64-bit longs, a DIFFERENT order; Kotlin must sort by `toString()` (edge-cases, `Pilgrim/Models/Threads/DossierSensesTracks.swift:35,58@0172e2b`). Only fixes passing `qualifies()` contribute (`qualifiedCoordinate`, `Pilgrim/Models/Threads/DossierSensesTracks.swift:21-24@0172e2b`).

**Candidate cap BEFORE suppression filter** *(consensus: edge-cases + ui-visual)*:

```swift
for thread in activeThreads(in: input).prefix(placeCandidateThemeCap)
where !suppressed.contains(thread.lemma) {
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:45-46@0172e2b`

`prefix(4)` applies to the lemma-ordered active threads first, then the `where` filters — a Kotlin `.filter { it !in suppressed }.take(4)` reverses the order and would let a 5th-ranked thread iOS never reaches surface (ui-visual).

**Layered ≥2 count gates** — `distinctWalks.count >= 2` window-wide (by `appearance.date`, `Pilgrim/Models/Threads/DossierSensesTracks.swift:47-52@0172e2b`), then inside `bestCluster` each candidate needs `mentionCount >= 2, walkCount >= 2` re-checked among just the clustered members (`Pilgrim/Models/Threads/DossierSensesTracks.swift:83@0172e2b`).

**Specificity guard — strict, self-excluding at baseline 0** *(consensus: behavior + edge-cases)*:

```swift
guard let cluster = bestCluster(members: members),
      // Strict: a walker whose every recording shares one spot has
      // baseline 0 — nothing can be "more specific" than routine.
      cluster.spread < baseline / 2 else { continue }
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:59-62@0172e2b`

Strict `<`, never `<=` — a walker whose whole recent history sits at one spot has baseline 0, which is unsatisfiable BY DESIGN (self-exclusion, not a bug). Do not special-case baseline == 0 to pass.

**`bestCluster` — deterministic seed-centered clustering with a three-way ordered tie-break** *(consensus: behavior + edge-cases + ui-visual)*:

```swift
let near = members.filter { distance(seed.coordinate, $0.coordinate) <= placeClusterRadius }
```

```swift
if best == nil
    || mentionCount > best!.mentionCount
    || (mentionCount == best!.mentionCount && spread < best!.spread) {
    best = PlaceCluster(mentionCount: mentionCount, walkCount: walkCount, spread: spread)
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:80,90-94@0172e2b`

For each member in UUID-string order as seed, the candidate cluster is everything within `placeClusterRadius` (150 m, inclusive `<=`); winner by highest mentionCount, then smallest spread, then earliest seed in iteration order (strict `>` / `<` only — first winner kept on exact ties). A Kotlin `maxByOrNull { it.mentionCount }` drops the spread tie-break. Spread = max pairwise distance within the cluster (`Pilgrim/Models/Threads/DossierSensesTracks.swift:85-89@0172e2b`).

**Emission** — the count phrase is a LOCAL inline ternary, NOT the shared `timesPhrase` (see UI / Visual):

```swift
let times = cluster.mentionCount == 2 ? "twice" : "\(cluster.mentionCount) times"
return SenseLine(
    text: "'\(thread.displayTerm)' has surfaced on \(distinctWalks.count) walks — \(times) near the same stretch of ground.",
    lemma: thread.lemma
)
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:63-67@0172e2b`

### Sense 2/8 — moonLine (once per lunation)

**Three preconditions + half-open lunation membership** *(consensus: behavior + edge-cases + ui-visual)*:

```swift
static func moonLine(input: Input, suppressed: Set<String>) -> SenseLine? {
    guard let moon = input.moon,
          moon.lastReportedIndex != moon.lunationIndex,
          moon.currentWalkHasWords else { return nil }
    // Lunation membership is [start, end): LunationCalendar mints end ==
    // next start, and the boundary instant belongs to the next moon.
    func inLunation(_ date: Date) -> Bool { date >= moon.start && date < moon.end }
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:104-110@0172e2b`

Once-per-lunation is an INDEX comparison (`lastReportedIndex != lunationIndex`), not a boolean flag — a boolean would mis-handle a user skipping a lunation entirely (edge-cases). `[start, end)` half-open: an inclusive-end `<=` would double-count a walk landing exactly on a lunation boundary into both lunations.

**Fourth guard — at least one worded walk in the closed lunation** *(synthesizer source-verification addition — not flagged by any lens)*:

```swift
let walkCount = moon.allWalkDates.filter(inLunation).count
let wordedCount = moon.wordedWalkDates.filter(inLunation).count
guard wordedCount >= 1 else { return nil }
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:111-113@0172e2b`

The current walk lives in the OPEN lunation, so `currentWalkHasWords` alone does not imply the CLOSED lunation had any worded walk — this guard is a distinct condition an Android port must keep.

**Top theme — max walks, tie-broken toward the lexicographically FIRST (smallest) lemma, via the crisscross-min idiom; theme-less fallback still fires**:

```swift
let topTheme = input.threads
    .compactMap { thread -> (lemma: String, displayTerm: String, walks: Int)? in
        guard !suppressed.contains(thread.lemma) else { return nil }
        let walks = Set(thread.appearances.filter { inLunation($0.date) }.map(\.walkUUID)).count
        guard walks >= 1 else { return nil }
        return (thread.lemma, thread.displayTerm, walks)
    }
    .min { ($0.walks, $1.lemma) > ($1.walks, $0.lemma) }
guard let topTheme else {
    return SenseLine(text: text + ".", lemma: nil)
}
text += "; '\(topTheme.displayTerm)' walked in \(topTheme.walks) of them."
return SenseLine(text: text, lemma: topTheme.lemma)
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:116-128@0172e2b`

The crossed-index comparator (each side pairs one candidate's count with the OTHER's lemma) reads like a copy-paste bug — it is not. Semantics (derivation verified during synthesis; see Lens disagreements): most walks wins; on a walk-count tie the lexicographically smaller lemma wins. `maxByOrNull { it.walks }` in Kotlin keeps the FIRST max encountered — wrong on ties. When no un-suppressed theme has an in-lunation appearance, the sense still returns a theme-less line ending in `.` with `lemma: nil` — a legitimate, frequent shipped state that bypasses lemma dedup AND still burns the lunation budget (reportedLunationIndex is still set on append). Forgetting this fallback would make moonLine wrongly return nil and never persist the index on lunations with zero themed walks (behavior).

### Sense 3/8 — markerColoring (current walk)

**First-match-wins nested loop — NOT best-of-all-candidates** *(consensus: behavior + ui-visual)*:

```swift
static func markerColoring(input: Input, suppressed: Set<String>) -> SenseLine? {
    for thread in activeThreads(in: input) where !suppressed.contains(thread.lemma) {
        for recording in input.currentRecordings {
            guard let theme = recording.themes.first(where: { $0.lemma == thread.lemma }),
                  let text = markerLine(theme: theme, displayTerm: thread.displayTerm,
                                        text: recording.text) else { continue }
            return SenseLine(text: text, lemma: thread.lemma)
        }
    }
    return nil
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:169-179@0172e2b`

Iterates active themes in lemma-alphabetical order, then the current walk's recordings in array order, returning on the FIRST qualifying pair. This is the OPPOSITE strategy from photoAdjacency's global-best scan in the same file — do not collapse both onto one generic "pick best" helper (behavior).

**`markerLine` — merged ±15-token windows, absolute floor, density gate, and two DIFFERENT denominators**:

```swift
let tokens = TranscriptNLP.wordTokenOffsets(in: text)
guard !tokens.isEmpty else { return nil }
var windowIndices = IndexSet()
for mention in theme.mentions {
    guard let index = tokens.lastIndex(where: { $0.start <= mention.start }) else { continue }
    windowIndices.insert(
        integersIn: max(0, index - markerWindowRadius)...min(tokens.count - 1, index + markerWindowRadius)
    )
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:182-190@0172e2b`

Window anchor is `lastIndex(where: { $0.start <= mention.start })` — the last token starting at or before the mention offset; `firstIndex` shifts the window (edge-cases). Windows across all mentions merge via IndexSet union (overlaps counted once).

```swift
guard windowAbsolutist >= markerMinWindowAbsolutist else { return nil }
let totalAbsolutist = tokens.filter { MarkerLexicons.absolutist.contains($0.token) }.count
let windowDensity = Double(windowAbsolutist) / Double(windowTokens.count)
let overallDensity = Double(totalAbsolutist) / Double(tokens.count)
guard overallDensity > 0, windowDensity >= markerMinDensityRatio * overallDensity else { return nil }
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:194-198@0172e2b`

Two gates: absolute floor `windowAbsolutist >= 3` (without it a 1-word transcript triggers on density alone) AND `windowDensity >= 2.0 × overallDensity` — the GATE compares against OVERALL density. But the DISPLAYED ratio uses the REST of the transcript *(consensus: behavior + edge-cases)*:

```swift
// Vs-rest ratio matches the line's own claim; when the rest holds no
// absolutist words at all, the vs-overall ratio under-claims — a
// descriptive line may understate, never overstate.
let ratio = restDensity > 0 ? windowDensity / restDensity : windowDensity / overallDensity
return "Absolutist words cluster around '\(displayTerm)' — \(timesPhrase(Int(ratio))) the density of the rest of the walk's speech."
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:202-206@0172e2b`

Two different denominators on purpose. Reusing the vs-overall value for the displayed ratio is "the single easiest correctness bug to introduce in this sense" (edge-cases). `Int(ratio)` TRUNCATES toward zero — `roundToInt()` would report one integer higher for e.g. ratio 2.97 (iOS says "twice", a naive round says "three times"). The `timesPhrase(Int(ratio))` call is algebraically guaranteed n ≥ 2 by the `markerMinDensityRatio` gate.

### Sense 4/8 — intentionLineage (cross-walk)

**Gates — today in window, non-empty intention, non-empty non-scaffold lemmas** *(consensus: behavior + ui-visual + data)*:

```swift
static func intentionLemmas(in intention: String) -> Set<String> {
    Set(TranscriptNLP.contentLemmas(in: intention)).subtracting(SpokenStoplist.scaffoldLemmas)
}

static func intentionLineage(input: Input, suppressed: Set<String>) -> SenseLine? {
    let windowStart = input.walkStart.addingTimeInterval(-ThreadStore.recurrenceWindow)
    let inWindow = input.walkSnapshots.filter { $0.startDate >= windowStart && $0.startDate <= input.walkEnd }
    guard let today = inWindow.first(where: { $0.walkUUID == input.currentWalkUUID }),
          let todayIntention = today.intention, !todayIntention.isEmpty else { return nil }
    let todayLemmas = intentionLemmas(in: todayIntention)
    guard !todayLemmas.isEmpty else { return nil }
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:136-146@0172e2b`

`nil` intention and empty-string intention are identically silence — a Kotlin port null-checking only would let an empty-but-non-null string through (ui-visual). An all-scaffold intention (e.g. "just walking") disqualifies the whole sense for that walk (data). The scaffold filter applies symmetrically to today's AND every historical intention (`Pilgrim/Models/Threads/DossierSensesTracks.swift:148-153@0172e2b`); asymmetric application breaks lemma matching (edge-cases). Note the window here is inclusive on BOTH ends (`>= windowStart && <= walkEnd`) — unlike moonLine's half-open lunation check; one shared "in-window" helper must preserve the per-sense distinction (behavior).

**Candidate pick — same crisscross-min idiom as moonLine**:

```swift
let candidate = familyWalks
    .filter { todayLemmas.contains($0.key) && $0.value.count >= lineageMinWalks && !suppressed.contains($0.key) }
    .min { ($0.value.count, $1.key) > ($1.value.count, $0.key) }
guard let candidate else { return nil }
return SenseLine(
    text: "\(ordinalWord(candidate.value.count)) walk in the last 30 days carrying some form of '\(candidate.key)'.",
    lemma: candidate.key
)
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:154-161@0172e2b`

Lemmas shared with today, on ≥ `lineageMinWalks` (3) distinct walks (`familyWalks` is per-lemma `Set<UUID>` of walkUUIDs), not suppressed; max-by-count, tie → lexicographically smallest lemma. Implement the tie-break identically in BOTH senses — a port correct in moonLine but `maxByOrNull` here diverges between the two (behavior). The emitted line hardcodes the literal "30 days" — it is NOT derived from `ThreadStore.recurrenceWindow` at format time; replicate the same hardcode-vs-derive choice (consensus: edge-cases + ui-visual; see Open questions).

### Sense 5/8 — climbAnchoring (current walk)

**Cheap pre-gate first, then interval-OVERLAP match (not containment)** *(consensus: behavior + edge-cases)*:

```swift
static func climbAnchoring(input: Input, suppressed: Set<String>) -> SenseLine? {
    guard input.totalAscent >= climbMinTotalAscent,
          let run = steepestSustainedAscent(in: input.elevationSeries) else { return nil }
    for thread in activeThreads(in: input) where !suppressed.contains(thread.lemma) {
        let onClimb = input.currentRecordings.contains { recording in
            recording.themes.contains { $0.lemma == thread.lemma }
                && recording.start <= run.end && recording.end >= run.start
        }
        if onClimb {
            return SenseLine(
                text: "'\(thread.displayTerm)' was spoken on the day's steepest climb.",
                lemma: thread.lemma
            )
        }
    }
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:261-274@0172e2b`

`totalAscent >= 50` (whole walk) is separate from `climbMinRunGain = 20` (per-run, inside the finder) — conflating them mis-classifies flat-with-one-bump and hilly-but-fragmented walks (edge-cases). `onClimb` is overlap (`recording.start <= run.end && recording.end >= run.start`) — containment would miss a recording that started before the climb and continued into it, the common mid-climb case (behavior). First-fit across lemma-ordered active threads.

**Smoothing — CENTERED moving average, clamped at edges**:

```swift
/// Centered moving average — raw GPS elevation is noisy per sample and
/// unsmoothed gradients false-positive on jitter (spec Track 3).
static func smoothedAltitudes(_ series: [ElevationSample]) -> [ElevationSample] {
    let half = climbSmoothingWindow / 2
    return series.indices.map { i in
        let lo = max(0, i - half)
        let hi = min(series.count - 1, i + half)
        let mean = series[lo...hi].map(\.altitude).reduce(0, +) / Double(hi - lo + 1)
        return ElevationSample(timestamp: series[i].timestamp, altitude: mean)
    }
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:279-289@0172e2b`

Window 5 → `half = 5 / 2 = 2` (integer division): 2 before, self, 2 after. A TRAILING moving average (a common library default) shifts smoothed altitude in time and changes which segment crosses the threshold (edge-cases).

**Steepest-run finder — top-decile threshold over positive rates only, force-close at series end** *(consensus: behavior + edge-cases)*:

```swift
let positive = segments.map(\.rate).filter { $0 > 0 }.sorted()
guard !positive.isEmpty else { return nil }
let threshold = positive[Int(Double(positive.count - 1) * climbTopDecile)]
```

```swift
for (index, segment) in segments.enumerated() {
    if segment.rate >= threshold && segment.rate > 0 {
        if runStartIndex == nil { runStartIndex = index }
        if index == segments.count - 1 { closeRun(endingAt: index) }
    } else if runStartIndex != nil {
        closeRun(endingAt: index - 1)
    }
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:303-305,326-333@0172e2b`

Rate is per-segment altitude delta over `dt` (m/s, `dt > 0` segments only — `Pilgrim/Models/Threads/DossierSensesTracks.swift:297-302@0172e2b`); "the series carries no distance, and 'steepest' stays deterministic without one" (`Pilgrim/Models/Threads/DossierSensesTracks.swift:291-293@0172e2b`). Threshold index is `Int((count-1) * 0.9)` — truncated nearest-rank-down, not interpolated; `count * 0.9` or rounding shifts the rank on short walks (edge-cases). Descents/flats are excluded BEFORE ranking. The `index == segments.count - 1` force-close is load-bearing: dropping it silently discards the steepest run whenever the walk ends mid-climb — exactly the scenario this sense most wants (behavior). `closeRun` requires `gain >= climbMinRunGain, duration > 0`; best run wins by max `averageRate` (`Pilgrim/Models/Threads/DossierSensesTracks.swift:308-324@0172e2b`).

### Sense 6/8 — weatherWeave (cross-walk)

**Per-walk bucketing — nil condition lands in `.unknown`**:

```swift
var buckets: [UUID: WeatherBucket] = [:]
for row in inWindow {
    buckets[row.walkUUID] = row.weatherCondition.map(bucket(forStoredCondition:)) ?? .unknown
}
let known = buckets.values.filter { $0 != .unknown }
guard !known.isEmpty else { return nil }
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:398-403@0172e2b`

Same inclusive 30-day window as intentionLineage (`Pilgrim/Models/Threads/DossierSensesTracks.swift:396-397@0172e2b`).

**The mode-strict guard — a 2026-08-25 ship-gate tightening; do NOT port the older >50% majority rule** *(consensus: all 4 lenses)*:

```swift
// Ship gate (2026-08-25): the guard tightened from "majority" (>50%)
// to "mode" — 4/4 real-device firings were a plurality tautology
// ("under cloud" without any condition holding a true majority). The
// shared category now fires only when its count is strictly below
// the window's highest condition count; a tie with the mode counts
// as the mode and still suppresses (conservative).
let conditionCounts = Dictionary(grouping: known, by: { $0 }).mapValues(\.count)
let modeCount = conditionCounts.values.max() ?? 0
```

```swift
guard let shared = themeBuckets.first,
      shared != .unknown,
      themeBuckets.allSatisfy({ $0 == shared }),
      conditionCounts[shared, default: 0] < modeCount,
      let phrase = skyPhrase(shared) else { continue }
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:404-411,420-424@0172e2b`

Strict `<` against `modeCount` — a tie with the mode still suppresses. A port implementing `conditionCounts[shared] > known.count / 2` (older spec wording) behaves completely differently whenever no condition holds a true majority — the exact real-device scenario that triggered the tightening. Additionally: `walkUUIDs.count >= 2` (`Pilgrim/Models/Threads/DossierSensesTracks.swift:418@0172e2b`) and EVERY one of the theme's walk buckets must be identically the same known bucket (`allSatisfy` — unanimous, not 80%; any walk missing weather data maps to `.unknown` via `buckets[$0] ?? .unknown` at `Pilgrim/Models/Threads/DossierSensesTracks.swift:419@0172e2b` and breaks unanimity). First qualifying theme in lemma order wins; emission at `Pilgrim/Models/Threads/DossierSensesTracks.swift:425-427@0172e2b` (see UI / Visual for the "Both walks" head and preposition split).

### Sense 7/8 — photoAdjacency (current walk, place-tied)

**Scores ALL candidates, keeps the single global best — the opposite of markerColoring** *(consensus: behavior + edge-cases + ui-visual)*:

```swift
let placedPhotos = input.photos.compactMap { photo -> (capturedAt: Date, coordinate: Coordinate)? in
    photo.coordinate.map { (photo.capturedAt, $0) }
}
guard !placedPhotos.isEmpty else { return nil }
```

```swift
guard let fix = input.fixes[recording.uuid], qualifies(fix) else { continue }
for photo in placedPhotos {
    let separation = distance(fix.coordinate, photo.coordinate)
    guard separation <= photoTieRadius else { continue }
    let gap = intervalGap(photo.capturedAt, start: recording.start, end: recording.end)
    guard gap <= photoTieMaxInterval else { continue }
    // Place first, time second, then capture order — the tie
    // is about ground shared, not clocks.
    if best == nil
        || (separation, gap, photo.capturedAt) < (best!.distance, best!.gap, best!.capturedAt) {
        best = (separation, gap, photo.capturedAt, thread.lemma, thread.displayTerm)
    }
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:215-218,224-235@0172e2b`

BOTH gates required: separation ≤ 75 m AND gap ≤ 600 s — either alone produces far more ties (edge-cases). The recording's own route fix must pass `qualifies()` (hygiene) before any photo is considered (`Pilgrim/Models/Threads/DossierSensesTracks.swift:224@0172e2b`, synthesizer-verified). Tie-break is a true lexicographic tuple compare `(separation, gap, capturedAt)` against ONE running global best across all (thread, recording, photo) triples — not scoped per thread; a hand-rolled if/else-if chain that checks fields independently, or a `(gap, separation)` ordering, silently picks a different winner (edge-cases, behavior). The `best!` force-unwrap is safe only because it's short-circuited by `best == nil` in the same `||` expression — don't split the null-check from the comparison (edge-cases).

**`intervalGap` — zero inside the span, else min distance to either edge** *(consensus: behavior + edge-cases)*:

```swift
static func intervalGap(_ instant: Date, start: Date, end: Date) -> TimeInterval {
    if instant >= start && instant <= end { return 0 }
    return min(abs(instant.timeIntervalSince(start)), abs(instant.timeIntervalSince(end)))
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:244-247@0172e2b`

Inside-span check is inclusive BOTH ends. A midpoint- or start-only distance would fail the 600 s guard for a photo captured mid-way through a long recording (behavior). Emission at `Pilgrim/Models/Threads/DossierSensesTracks.swift:240-241@0172e2b`.

### Sense 8/8 — speechShape (current walk)

**All-worded-recordings front-loading + strict remainder + truncated minutes** *(consensus: behavior + edge-cases + ui-visual)*:

```swift
static func speechShape(input: Input, suppressed: Set<String>) -> SenseLine? {
    let worded = input.currentRecordings.filter { $0.wordCount > 0 }
    guard !worded.isEmpty else { return nil }
    let span = input.walkEnd.timeIntervalSince(input.walkStart)
    guard span > 0 else { return nil }
    let firstThirdEnd = input.walkStart.addingTimeInterval(span / 3)
    guard worded.allSatisfy({ $0.end <= firstThirdEnd }),
          let lastEnd = worded.map(\.end).max() else { return nil }
    let remainder = input.walkEnd.timeIntervalSince(lastEnd)
    guard remainder > speechShapeMinWordlessRemainder else { return nil }
    let minutes = Int(remainder / 60)
    return SenseLine(
        text: "All the words came in the first third; the last \(minutes) minutes were wordless.",
        lemma: nil
    )
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:342-357@0172e2b`

`allSatisfy` — EVERY worded recording must end at-or-before the exact first-third mark; a single late word anywhere disqualifies the whole sense. Checking only the LAST recording's end changes the claim from "all words front-loaded" to "words trailed off" — materially more permissive (ui-visual). The 1800 s remainder guard is strict `>`: exactly 30:00 produces silence, not a line. Minutes truncate (`Int(remainder / 60)`), never round. `lemma: nil` always — speechShape never participates in lemma dedup in either direction; a port defaulting lemma to `""` instead of true null would accidentally join the dedup set (behavior). The `span > 0` guard is synthesizer-verified from source.

### Coordinate hygiene + geometry helpers

**`qualifies` — the single GPS-hygiene gate, with DIFFERENT operators on its two halves** *(consensus: data + edge-cases + ui-visual)*:

```swift
static func qualifies(_ fix: RouteFix) -> Bool {
    fix.gapSeconds <= hygieneMaxGap && fix.horizontalAccuracy < hygieneMaxAccuracy
}
```

> `Pilgrim/Models/Threads/DossierSenses.swift:165-167@0172e2b`

`gapSeconds <= 90` INCLUSIVE; `horizontalAccuracy < 100` EXCLUSIVE. Exactly 90.0 s must accept; exactly 100.0 m must reject. Both placeResonance and photoAdjacency route every location claim through this one function — an operator flip changes two senses at once. The hygiene is enforced twice: `routeFixNear` bounds its SQL window to ±90 s, then `qualifies` re-checks the gap AND adds the accuracy check, which has NO SQL-side counterpart — a low-accuracy fix inside the time window IS returned by the query and only rejected here (data, `Pilgrim/Models/Data/DataManager+Query.swift:181-212@0172e2b`).

**`distance` — delegates to CLLocation's geodesic; no custom haversine anywhere in the slice**:

```swift
static func distance(_ a: Coordinate, _ b: Coordinate) -> CLLocationDistance {
    CLLocation(latitude: a.latitude, longitude: a.longitude)
        .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
}
```

> `Pilgrim/Models/Threads/DossierSenses.swift:169-172@0172e2b`

Every distance threshold (150 m / 75 m / 100 m) was tuned against CLLocation's ellipsoidal geodesic — Android should use `Location.distanceBetween` (also ellipsoidal) and verify, not assume, equivalence against a spherical `SphericalUtil`-style substitute (edge-cases; see Open questions).

**`median` — even count averages the two middle elements**:

```swift
static func median(_ values: [Double]) -> Double {
    let sorted = values.sorted()
    guard !sorted.isEmpty else { return 0 }
    let mid = sorted.count / 2
    return sorted.count.isMultiple(of: 2) ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid]
}
```

> `Pilgrim/Models/Threads/DossierSenses.swift:174-179@0172e2b`

`mid = count / 2` (integer division), pair = `sorted[mid - 1]`/`sorted[mid]`. An off-by-one shifts the placeResonance baseline (edge-cases). Empty → 0.

**`activeThreads` — lemma-alphabetical order decides which theme wins four senses**:

```swift
/// Threads with an appearance on the current walk, in the dossier thread
/// section's own order (ThreadStore.build sorts by lemma).
static func activeThreads(in input: Input) -> [WalkThread] {
    input.threads.filter { thread in
        thread.appearances.contains { $0.walkUUID == input.currentWalkUUID }
    }
}
```

> `Pilgrim/Models/Threads/DossierSenses.swift:157-163@0172e2b`

Preserves `ThreadStore.build`'s lemma sort (not recency, not frequency); markerColoring, climbAnchoring, weatherWeave and photoAdjacency iterate it directly, so alphabetical order among qualifying candidates decides which theme's name ships in 4 of the 8 possible lines — a "better UX" recency sort silently changes winners (ui-visual).

### LunationCalendar + LunarPhase constants

**One derived length, one epoch, one minting expression** *(consensus: behavior + data + edge-cases)*:

```swift
private static let lunationLength = LunarPhase.synodicMonth * 86400

/// Every boundary Date is minted by this one expression, so
/// `lunation(at: n).end == lunation(at: n + 1).start` holds exactly —
/// never `start + length`, which drifts by a ulp and splits boundaries.
private static func newMoonDate(at index: Int) -> Date {
    LunarPhase.knownNewMoon.addingTimeInterval(Double(index) * lunationLength)
}
```

> `Pilgrim/Models/Threads/LunationCalendar.swift:17-24@0172e2b`

`lunationLength` is DERIVED from `LunarPhase.synodicMonth` at load time — two independently-typed literals in a Kotlin port would let a future edit desynchronize phase math from lunation arithmetic with no compiler error (behavior). Every boundary comes from `epoch + index × length` — chaining `start + length` off a previous result accumulates ulp drift and splits/overlaps boundaries, which moonLine's exact `date < moon.end` check would then classify inconsistently (behavior, edge-cases).

**`lunation(at:)` — fullMoon minted as the window midpoint** *(synthesizer source-verification addition)*:

```swift
static func lunation(at index: Int) -> Lunation {
    Lunation(
        index: index,
        start: newMoonDate(at: index),
        end: newMoonDate(at: index + 1),
        fullMoon: newMoonDate(at: index).addingTimeInterval(lunationLength / 2)
    )
}
```

> `Pilgrim/Models/Threads/LunationCalendar.swift:26-33@0172e2b`

`end` is `newMoonDate(at: index + 1)` — never `start + length`. `fullMoon = start + lunationLength / 2` is what `moonName` reads.

**`lunation(containing:)` — floor division plus TWO correction guards; both are required** *(consensus: behavior + edge-cases)*:

```swift
/// The floor division can land one off at exact boundary instants
/// (Double round-off) — the two correction guards make the close
/// instant belong to the next lunation, deterministically.
static func lunation(containing date: Date) -> Lunation {
    var index = Int(floor(date.timeIntervalSince(LunarPhase.knownNewMoon) / lunationLength))
    if date >= newMoonDate(at: index + 1) { index += 1 }
    if date < newMoonDate(at: index) { index -= 1 }
    return lunation(at: index)
}
```

> `Pilgrim/Models/Threads/LunationCalendar.swift:35-43@0172e2b`

The guards look like redundant defensive code; they are not — Kotlin's IEEE 754 Double can round differently at the ULP level, and omitting either misclassifies dates within epsilon of a boundary (edge-cases).

**`mostRecentClosed` — the only lunation moonLine may report; the open one is never eligible**:

```swift
/// The lunation that most recently closed — the only moon that may
/// invite. Once the next one closes, the previous moves to Past recaps.
static func mostRecentClosed(asOf date: Date) -> Lunation {
    lunation(at: lunation(containing: date).index - 1)
}
```

> `Pilgrim/Models/Threads/LunationCalendar.swift:45-49@0172e2b`

Must go through `lunation(containing:).index - 1` — re-deriving "the closed lunation" from `now` any other way bypasses the correction guards and risks off-by-one at boundaries (behavior).

**The 12-name moon table — VERBATIM, 0-indexed by (calendar month − 1)** *(consensus: edge-cases + data)*:

```swift
/// Traditional full-moon month names, January through December.
static let monthMoonNames = [
    "Wolf Moon", "Snow Moon", "Worm Moon", "Pink Moon",
    "Flower Moon", "Strawberry Moon", "Buck Moon", "Sturgeon Moon",
    "Corn Moon", "Hunter's Moon", "Beaver Moon", "Cold Moon"
]
```

> `Pilgrim/Models/Threads/LunationCalendar.swift:51-56@0172e2b`

| month | name | month | name |
|---|---|---|---|
| 1 Jan | Wolf Moon | 7 Jul | Buck Moon |
| 2 Feb | Snow Moon | 8 Aug | Sturgeon Moon |
| 3 Mar | Worm Moon | 9 Sep | Corn Moon |
| 4 Apr | Pink Moon | 10 Oct | Hunter's Moon |
| 5 May | Flower Moon | 11 Nov | Beaver Moon |
| 6 Jun | Strawberry Moon | 12 Dec | Cold Moon |

September is "Corn Moon" (not the folk-almanac "Harvest Moon") and October keeps the apostrophe in "Hunter's Moon" — transcription slips here are user-visible (edge-cases).

**`moonName` — the walker's local timezone names the moon; do NOT "fix" toward UTC** *(consensus: behavior + data + edge-cases)*:

```swift
/// The moon's name derives from the calendar month of its full-moon
/// instant in the given timezone (spec: timezone moon naming) — the
/// same set moon can honestly carry different names in Lisbon and
/// Auckland, because the walker's sky is the one that counts.
static func moonName(for lunation: Lunation, in timeZone: TimeZone = .current) -> String {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = timeZone
    return monthMoonNames[calendar.component(.month, from: lunation.fullMoon) - 1]
}
```

> `Pilgrim/Models/Threads/LunationCalendar.swift:58-66@0172e2b`

Explicit Gregorian calendar; timezone defaults to `.current` (device-local) at read time — not UTC, not stored/cached. A UTC month computation renames moons for walkers whose local full-moon instant crosses a month boundary — documented as intentional device-local behavior (edge-cases). The `- 1` subtraction pairs with the 0-indexed table; an off-by-one shifts EVERY name to the adjacent month.

**LunarPhase constants — the two values everything derives from** *(consensus: behavior + data + edge-cases)*:

```swift
static let synodicMonth = 29.53058770576
static let knownNewMoon = DateComponents(
    calendar: .init(identifier: .gregorian),
    timeZone: TimeZone(identifier: "UTC"),
    year: 2000, month: 1, day: 6, hour: 18, minute: 14
).date!
```

> `Pilgrim/Models/LunarPhase.swift:18-23@0172e2b`

Epoch = **2000-01-06 18:14:00 UTC**, Gregorian. `synodicMonth` = **29.53058770576** days, specified to 11 decimal digits. Any transcription slip on either — or building the epoch in local time — shifts every lunation index, boundary, and moon name the app has ever computed by a fixed offset, with no independent cross-check (behavior). Truncating the constant (e.g. 29.53059) accumulates drift over the ~330 lunations since 2000 (edge-cases). **Android drift alert**: the shipped `MoonCalc.SYNODIC_DAYS` does NOT equal this value — see Android implementation notes + Open questions.

**`lunarAge` — negative-remainder correction is NOT dead code in Kotlin either**:

```swift
private static func lunarAge(for date: Date) -> Double {
    let daysSinceRef = date.timeIntervalSince(knownNewMoon) / 86400
    let age = daysSinceRef.truncatingRemainder(dividingBy: synodicMonth)
    return age < 0 ? age + synodicMonth : age
}
```

> `Pilgrim/Models/LunarPhase.swift:25-29@0172e2b`

Kotlin's `%` on Double has the same fmod sign-follows-dividend semantics as Swift's `truncatingRemainder` — dropping the correction "defensively" reproduces the exact bug Swift guards against for pre-epoch dates (behavior, edge-cases). Not reached by the senses slice directly (senses uses index-based LunationCalendar math), but it shares both constants.

**Phase-name buckets and `isWaxing` — shared-constant context, not consumed by senses**:

```swift
var isWaxing: Bool { age < Self.synodicMonth / 2 }
```

```swift
case 0 ..< eighth:                    return "New Moon"
case eighth ..< (2 * eighth):         return "Waxing Crescent"
...
case (6 * eighth) ..< (7 * eighth):   return "Last Quarter"
default:                              return "Waning Crescent"
```

> `Pilgrim/Models/LunarPhase.swift:16,36-46@0172e2b`

8 names spanning one-eighth each, half-open ranges (each boundary instant belongs to the LATER phase; closed ranges in a Kotlin `when` would overlap at boundaries and the textually-first branch would silently win — edge-cases). `isWaxing` strict `<` at the midpoint. Illumination = `0.5 * (1 - cos(2 * .pi * age / synodicMonth))` (`Pilgrim/Models/LunarPhase.swift:31-33@0172e2b`). This age-bucket name (e.g. "Waxing Gibbous") is a DIFFERENT naming scheme from LunationCalendar's month-based `moonName` — the moon line uses only the latter; don't conflate the two "moon name" concepts (behavior).

### Weather vocabulary + buckets

**The 10-string storable vocabulary**:

```swift
enum WeatherCondition: String, Codable, CaseIterable {
    case clear, partlyCloudy, overcast
    case lightRain, heavyRain, thunderstorm
    case snow, fog, wind, haze
}
```

> `Pilgrim/Models/Weather/WeatherService.swift:5-8@0172e2b`

Exactly 10 lowerCamelCase rawValue strings persisted to `Walk.weatherCondition`. Android's stored condition must serialize to these exact strings or every bucket lookup silently falls to unknown (data). (Android's `WeatherCondition.kt` already matches — see Android implementation notes.)

**The bucket collapse — total over the vocabulary, with an explicit fallback that looks unreachable** *(consensus: behavior + data + edge-cases)*:

```swift
/// Collapses the app's stored `WeatherCondition` rawValues. Anything
/// unrecognized lands in `unknown`, which excludes the walk from claims —
/// the drift test keeps this total over the storable vocabulary.
static func bucket(forStoredCondition raw: String) -> WeatherBucket {
    switch raw {
    case "clear": return .clear
    case "partlyCloudy", "overcast", "haze": return .cloud
    case "lightRain", "heavyRain", "thunderstorm": return .rain
    case "snow": return .snow
    case "fog": return .fog
    case "wind": return .wind
    default: return .unknown
    }
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:368-381@0172e2b`

All 10 rawValues → 6 known buckets (cloud absorbs 3, rain absorbs 3); the `default` branch is dead against the current enum but must be kept for legacy/corrupted strings — port the fallback AND the exhaustiveness drift test (data). `WeatherBucket` is `rain, snow, clear, cloud, wind, fog, unknown` (`Pilgrim/Models/Threads/DossierSensesTracks.swift:364-366@0172e2b`) — `unknown` is a first-class case that always fails the shared-bucket check, not a null sentinel (data).

### Builder senses assembly

**`DossierSensesFetchBundle` deliberately excludes route fixes — lazy per-recording resolution** *(consensus: behavior + data)*:

```swift
/// Main-actor-fetched inputs for the senses block, gathered before the
/// detached build. Route fixes are NOT here — the builder resolves them
/// lazily, per needed recording, through `resolveRouteFix`.
```

> `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:4-6@0172e2b`

Bundle fields: `walkStart, walkEnd, totalAscent, elevationSeries, photos, walkSnapshots, recordingTimestamps, closedLunation, moonName` (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:7-17@0172e2b`). Eager bulk-fetching fixes at gather time changes the I/O shape and loses the `resolveRouteFix` test seam (data).

**`gatherSensesBundle` — the ONE wall-clock capture point; window-UNION fetch** *(consensus: behavior + data)*:

```swift
@MainActor
static func gatherSensesBundle(walk: WalkInterface, now: Date = Date()) -> DossierSensesFetchBundle {
    let lunation = LunationCalendar.mostRecentClosed(asOf: now)
    let windowStart = walk.startDate.addingTimeInterval(-ThreadStore.recurrenceWindow)
```

```swift
    walkSnapshots: DataManager.walkSensesSnapshot(
        from: min(windowStart, lunation.start),
        to: max(walk.endDate, lunation.end)
    ),
```

> `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:51-54,71-74@0172e2b`

`now: Date = Date()` is the senses pipeline's only ambient-time entry — the captured `now`/lunation must thread all the way through; re-reading the clock later can produce internally inconsistent `moonName` vs `lunationIndex` across a lunation-boundary crossing (behavior). The snapshot fetch spans `[min(windowStart, lunation.start), max(walkEnd, lunation.end)]` — the UNION of the 30-day recurrence window and the closed lunation. Fetching only the 30-day window starves moonLine's counts whenever the closed lunation extends outside it (behavior, data). `MoonInput.allWalkDates`/`wordedWalkDates` are pre-filtered to this fetch range, not all-time — re-deriving them from a differently-bounded query miscounts walks per lunation (data).

**Elevation + photos come from the caller's already-materialized Walk, with the (−1,−1) photo sentinel translated ONCE at the boundary**:

```swift
totalAscent: walk.ascend,
elevationSeries: walk.routeData.map {
    DossierSenses.ElevationSample(timestamp: $0.timestamp, altitude: $0.altitude)
},
photos: walk.walkPhotos.map { photo in
    // (-1, -1) is the schema's unset sentinel, not a place.
    DossierSenses.PhotoPin(
        capturedAt: photo.capturedAt,
        coordinate: photo.capturedLat == -1 && photo.capturedLng == -1
            ? nil
            : DossierSenses.Coordinate(latitude: photo.capturedLat, longitude: photo.capturedLng)
    )
},
```

> `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:58-70@0172e2b`

Not DataManager queries — in-memory relationship reads; whatever Walk aggregate Android passes must have route/photo collections fully loaded, or an un-fetched Room relation silently yields empty series instead of an error (data). Sentinel check is exact equality on BOTH axes simultaneously (not either-axis, not epsilon) so sense functions never see sentinel numbers as coordinates near null island (data).

**`build()` re-reads the moon state fresh every call and folds it into the memo key** *(consensus: behavior + data)*:

```swift
let moonState = defaults.object(forKey: moonLineDefaultsKey) as? Int
let preBuildKey = memoKey(walkUUID: walkUUID, changeCount: preBuildChangeCount,
                          backfillComplete: backfillComplete, moonState: moonState, senses: senses)
if let cached = cachedDossier(key: preBuildKey) {
    return cached
}
```

> `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:96-101@0172e2b`

A memo hits only when the persisted moon state hasn't changed — caching on `changeCount` alone would return a stale dossier missing/containing a moon line after an external write to the key (behavior). `MemoKey`'s six fields are `changeCount, walkUUID, backfillComplete, moonState, lunationIndex, intention` — `lunationIndex` and `intention` close two cache-miss gaps the other four can't detect: a lunation closing while the app stays resident, and an in-session intention edit (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:28-43@0172e2b`). `memoKey` derives them from the bundle: `senses?.closedLunation.index` and `senses?.walkSnapshots.first { $0.walkUUID == walkUUID }?.intention` (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:190-199@0172e2b`). The memo is a single NSLock-protected in-process static — never persisted; Android parity is an in-memory singleton field under a Mutex, NOT DataStore/Room (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:45-46@0172e2b`, data). Build precondition: `guard UserPreferences.threadsAfterWalks.value, !recordings.isEmpty else { return nil }` (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:90@0172e2b`).

**`appendSensesBlock` — all-or-nothing block; write gated strictly on `reportedLunationIndex`** *(consensus: behavior + ui-visual + data)*:

```swift
guard let senses, dossier != nil else { return state.moonState }
let input = makeSensesInput(senses: senses, state: state, resolveRouteFix: resolveRouteFix)
let output = DossierSenses.lines(input: input)
if !output.lines.isEmpty {
    dossier! += "\n\n**Noticed:**\n" + output.lines.joined(separator: "\n")
}
guard let reported = output.reportedLunationIndex else { return state.moonState }
defaults.set(reported, forKey: moonLineDefaultsKey)
return reported
```

> `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:252-260@0172e2b`

Senses evaluate only when BOTH a bundle was supplied AND the base dossier built; zero lines → no header at all — never an empty `**Noticed:**` heading (behavior, ui-visual). The moon-state write happens ONLY when `reportedLunationIndex` is non-nil, i.e. strictly when the moon line fired AND survived cap/dedup — writing on mere eligibility burns the once-per-lunation budget on suppressed lines (behavior, data). `build()`'s `senses:` parameter defaults to nil, so a call site that forgets to gather-and-pass the bundle silently ships a dossier with no Noticed: block and no error — a silent no-op by design (ui-visual, `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:86@0172e2b`).

**`resolveFixes` — the needed-UUID set has two sources; timestamps fall back to the in-memory recording** *(consensus: behavior + data)*:

```swift
for thread in threads {
    for appearance in thread.appearances {
        guard let instant = senses.recordingTimestamps[appearance.recordingUUID],
              instant >= windowStart, instant <= senses.walkEnd else { continue }
        needed.insert(appearance.recordingUUID)
    }
}
for recording in currentRecordings where !recording.themes.isEmpty {
    needed.insert(recording.uuid)
}
var fixes: [UUID: DossierSenses.RouteFix] = [:]
for uuid in needed.sorted(by: { $0.uuidString < $1.uuidString }) {
    let timestamp = senses.recordingTimestamps[uuid]
        ?? currentRecordings.first { $0.uuid == uuid }?.start
    if let timestamp, let fix = resolveRouteFix(timestamp) {
        fixes[uuid] = fix
    }
}
```

> `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:272-289@0172e2b`

In-window mention recordings across ALL threads (the baseline needs them all) plus the current walk's themed recordings; one `resolveRouteFix` call per UUID in uuidString-sorted order — O(needed) individual bounded queries, never one joined bulk fetch (data). The `?? currentRecordings.first {...}?.start` fallback covers a themed recording made during the CURRENT walk that hasn't landed in the persisted timestamp index yet — exactly what markerColoring/climbAnchoring/photoAdjacency need on the walk being summarized right now; omitting it silently drops those fixes (behavior).

**`makeSensesInput` — compactMap drop rule, end-fallback, and the walk-keyed worded-dates collapse** *(consensus: behavior + data)*:

```swift
let currentRecordings: [DossierSenses.CurrentRecording] = state.recordings.compactMap { recording in
    guard let uuid = recording.recordingUUID,
          let context = state.contextsByUUID[uuid] else { return nil }
    return DossierSenses.CurrentRecording(
        uuid: uuid,
        start: recording.timestamp,
        end: recording.endTimestamp ?? recording.timestamp,
```

```swift
var wordedWalkDates: [UUID: Date] = [:]
for (uuid, context) in state.contextsByUUID where context.wordCount > 0 {
    if let walk = state.walkIndex[uuid] {
        wordedWalkDates[walk.walkUUID] = walk.date
    }
}
```

> `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:299-305,317-322@0172e2b`

A recording missing either a UUID or a resolved context participates in NO sense — not even wordCount-only ones like speechShape (behavior). `end` falls back to `start` when `endTimestamp` is nil — replicate, or zero-duration recordings fail climb/photo window checks differently (data). `wordedWalkDates` iterates ALL resolved contexts (not just current-walk) and collapses to one date per WALK via dictionary overwrite — moonLine wants a walk count; a per-recording list inflates `wordedCount` whenever one walk has multiple worded recordings (behavior). MoonInput assembly: `lastReportedIndex: state.moonState`, `currentWalkHasWords: currentRecordings.contains { $0.wordCount > 0 }`, `allWalkDates: senses.walkSnapshots.map(\.startDate)` (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:323-332@0172e2b`).

**`resolveCurrentContexts` — the lazy self-healing analysis fallback** *(consensus: behavior + data)*: a hash-matched store hit is used as-is; on miss the builder synchronously runs `TranscriptContextAnalyzer.analyzeAndStore` itself (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:207-226@0172e2b`). The transcription-save path separately dispatches the SAME analysis on a detached utility Task when `threadsAfterWalks` is on — and actively `removeContext(for:)` when it's off:

```swift
if UserPreferences.threadsAfterWalks.value {
    Task.detached(priority: .utility) {
        TranscriptContextAnalyzer.analyzeAndStore(
            recordingUUID: uuid,
            transcript: transcription,
            flaggedFragments: flaggedFragments,
            store: transcriptContextStore
        )
    }
} else {
    transcriptContextStore.removeContext(for: uuid)
}
```

> `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:107-118@0172e2b`

The two paths are redundant/self-healing, not producer-consumer: a port making the background job the ONLY population path introduces a stale-themes race iOS never has (behavior). The off-toggle branch actively DELETES pre-toggle analysis — easy to drop in a feature-on-only port (data).

### Queries

**`routeFixNear` — bounded ±90 s fetch, cap 240, (gap, accuracy)-min pick, callable OFF-main**:

```swift
public static func routeFixNear(timestamp: Date) -> DossierSenses.RouteFix? {
    threadSafeSyncReturn {
        let windowStart = timestamp.addingTimeInterval(-DossierSenses.hygieneMaxGap)
        let windowEnd = timestamp.addingTimeInterval(DossierSenses.hygieneMaxGap)
        guard let rows = try? dataStack.queryAttributes(
            From<RouteDataSample>()
                .select(
                    NSDictionary.self,
                    .attribute(\._timestamp),
                    .attribute(\._latitude),
                    .attribute(\._longitude),
                    .attribute(\._horizontalAccuracy)
                )
                .where(\._timestamp >= windowStart && \._timestamp <= windowEnd)
                .orderBy(.ascending(\._timestamp))
                .tweak { $0.fetchLimit = 240 }
        ) else { return nil }
```

```swift
        .min { ($0.gapSeconds, $0.horizontalAccuracy) < ($1.gapSeconds, $1.horizontalAccuracy) }
```

> `Pilgrim/Models/Data/DataManager+Query.swift:181-197,210@0172e2b`

The SQL window bound reuses `DossierSenses.hygieneMaxGap` — the query bound and `qualifies()` must share ONE constant on Android too. `gapSeconds` is computed once at resolution time — `abs(sampleTime.timeIntervalSince(timestamp))` (`Pilgrim/Models/Data/DataManager+Query.swift:207@0172e2b`) — and frozen into the RouteFix; never recompute against a different reference instant (data). Winner = lexicographic min by `(gapSeconds, horizontalAccuracy)`. The 240 cap: "~1 Hz logging yields ≤181 samples inside ±90 s, so the ascending-order clip never bites in practice" (`Pilgrim/Models/Data/DataManager+Query.swift:175-178@0172e2b`). This is NOT the same data path as `walk.routeData` (elevationSeries) — a separate, indexed, time-bounded per-recording fetch; do not reuse the full-walk route list (data).

**`walkSensesSnapshot` — one bounded @MainActor query serving three senses**:

```swift
@MainActor
public static func walkSensesSnapshot(from: Date, to: Date) -> [DossierSenses.WalkSnapshotRow] {
```

```swift
    return DossierSenses.WalkSnapshotRow(
        walkUUID: uuid,
        startDate: start,
        intention: row["comment"] as? String,
        weatherCondition: row["weatherCondition"] as? String
    )
```

> `Pilgrim/Models/Data/DataManager+Query.swift:217-218,234-239@0172e2b`

Columns `_uuid, _startDate, _comment, _weatherCondition`; predicate `_startDate in [from, to]` (caller-computed union bounds, not a constant inside the query); ascending order. `intention` is literally `Walk._comment` renamed at the row-mapping boundary — Android's Walk `intention` field maps the same way; `weatherCondition` is the raw stored string, not a typed enum (data). One row per walk serves intentionLineage, weatherWeave, AND moonLine's walk counts — no per-sense separate fetches (data). Android divergence: suspend DAO, not a main-thread read — see Android implementation notes.

**`voiceRecordingTimestampIndex` — unbounded on purpose; per-RECORDING granularity** *(consensus: behavior + data)*:

```swift
/// Recording UUID → the recording's own start instant. The walk-level
/// `voiceRecordingWalkIndex` above returns WALK dates; the senses' 30-day
/// windows and coordinate lookups need per-RECORDING times (spec Track 1).
@MainActor
public static func voiceRecordingTimestampIndex() -> [UUID: Date] {
```

> `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:156-160@0172e2b`

Two columns (`_uuid`, `_startDate`), NO date predicate — every recording in the store; callers window in memory. "Optimizing" it into a date-bounded query breaks any caller needing a timestamp before applying its own filter (data). Conflating this with the walk-level index mis-windows any recording made significantly after its walk's start (behavior).

**`voiceRecordingWalkIndex` — the frozen legacy-column join**:

```swift
/// Recording UUID → owning walk, for thread aggregation. The walk
/// relationship on PilgrimV7.VoiceRecording is a frozen pre-rename-era
/// SQL identifier (⟨frozen legacy column/property⟩, PilgrimV7.swift:244);
/// never rename the entity property to "fix" the name.
```

*(Quote elided per the no-legacy-reference rule: the identifier and its origin must never appear in Android code, comments, or docs — only the semantic fact matters: VoiceRecording has a many-to-one join to its owning Walk, and Android's fresh Room schema uses its own FK naming.)*

> `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:177-180@0172e2b`

Two @MainActor queryAttributes fetches + in-memory NSManagedObjectID join; result shape `[UUID: (walkUUID: UUID, date: Date)]`. The join dance is a CoreData artifact — Android's Room schema stores the owning walk id directly and can resolve this in one query, but the RESULT shape must match exactly (data). It feeds `wordedWalkDates`/`walkIndex` in the senses assembly (behavior). (Note: iOS-repo-internal comment quoted verbatim; the legacy identifier does not migrate to Android's fresh schema.)

### Field report harness (DEBUG ship-gate tool)

**Registration — @MainActor hop from a non-actor completion, strictly after UI unlock** *(behavior)*:

```swift
private func runPostDoneLaunchTasks() {
    #if DEBUG
    // Ship-gate harness (Task 10): only ever produces output under
    // `--senses-field-report` — `runIfRequested` carries that guard
    // itself. The Task hop keeps the @MainActor call clean from this
    // non-actor-annotated completion closure.
    Task { @MainActor in DossierSensesFieldReport.runIfRequested() }
    #endif
    startLaunchRecordingCleanup()
}
```

> `Pilgrim/AppDelegate.swift:151-160@0172e2b`

`appLaunchState = .done` flips BEFORE `runPostDoneLaunchTasks()` — the harness is fire-and-forget relative to UI readiness and must never gate UI unlock (`Pilgrim/AppDelegate.swift:117-120@0172e2b`). The report generator is main-thread-bound on iOS; Android's equivalent must respect its own threading model (Room off main — see Android implementation notes).

**Demo-mode mutual exclusion — by construction, not by flag validation** *(behavior)*:

```swift
#if DEBUG
if CommandLine.arguments.contains("--demo-mode") {
    if CommandLine.arguments.contains("--demo-cairns") {
        GeoCacheService.shared.injectDemoCairns(
            near: 42.8782, longitude: -8.5448
        )
    }
    self.seedDemoData {
        self.appLaunchState = .done
    }
    return
}
#endif
```

> `Pilgrim/AppDelegate.swift:99-111@0172e2b`

The early `return` means the demo-mode path never calls `runPostDoneLaunchTasks()` — `--senses-field-report --demo-mode` together silently produce no field report. The two DEBUG flags are mutually exclusive by construction (behavior; see Open questions for the Android equivalent decision).

**Harness semantics — deliberately decoupled from EVERY production presentation rule** *(consensus: ui-visual + data)*:

```swift
/// Ship-gate harness (spec Ship gate item 1): iterates every walk with
/// transcribed recordings, evaluates every sense uncapped, and prints
/// per-sense firing rates plus each emitted line, so a human can judge
/// degeneration (fires on nearly every walk) and dead senses (nearly never)
/// against a REAL device history. Launch the dev build on the team device
/// with `--senses-field-report` and read the console. The report only
/// EVALUATES senses (moon state passed as nil, no defaults write anywhere
/// on this path) — it never consumes the real once-per-lunation budget.
```

> `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:353-360@0172e2b`

Per walk it calls `DossierSenses.evaluate(sense, input: input, suppressed: [])` for ALL 8 senses — no cap, EMPTY suppressed set (no dedup), `moonState: nil` (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:461-473@0172e2b`). Consequence for QA interpretation: the firing-rate table does NOT approximate production frequency on two independent axes — missing cap/dedup inflates every sense, and `moonState: nil` makes moonLine look near-every-walk instead of once-per-lunation (ui-visual). It never calls the defaults write path, so manual QA can never corrupt the device's real moon-line state — an Android debug tool must copy the same never-write discipline (data). `runIfRequested` additionally no-ops under a test runner: `NSClassFromString("XCTestCase") == nil` (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:364-366@0172e2b`, synthesizer-verified).

**Eligibility + inputs**: every walk ordered by startDate; a walk is eligible iff it has ≥1 recording with a non-empty transcription (`transcribedRecordings`, `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:435-446@0172e2b`); the bundle comes from the same production `gatherSensesBundle`/`makeSensesInput` with a single `now` for the whole report, `backfillComplete: ThreadsBackfill.isComplete`, and per-walk wall-clock build timing (`Date()` diff — a deliberate DEBUG-only exception to the no-clock rule, documented: "Wall-clock, not ContinuousClock — this DEBUG harness prints a human-facing diagnostic, not a pure-module measurement", `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:448-457@0172e2b`). Median build time = same two-middle-average convention (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:410-415@0172e2b`). Output format strings are spec'd verbatim in UI / Visual.

---

## UI / Visual

This slice renders no screens — its entire visible surface is markdown-ish TEXT appended to the dossier string, plus a DEBUG console report. Layout/Dimensions/Colors/Typography/Motion do not apply; the whole section is copy tables + conditional-render rules. Every string below is BINDING — golden-string tests should compare byte-for-byte.

### The **Noticed:** block

```swift
if !output.lines.isEmpty {
    dossier! += "\n\n**Noticed:**\n" + output.lines.joined(separator: "\n")
}
```

> `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:255-257@0172e2b`

- Header is the literal markdown-bold `**Noticed:**` — colon INSIDE the bold markers (design docs said plain `Noticed:`; shipped code is bold — ui-visual).
- Exactly two leading newlines before the header, one newline after it, lines joined by single `\n`, NO bullets.
- Block (header included) omitted entirely when zero senses fire — never an empty heading.
- Max 3 lines (`lineCap`), in Sense priority order.

### Sense line templates (verbatim)

| # | Sense | Template | citation |
|---|---|---|---|
| 1 | placeResonance | `'\(thread.displayTerm)' has surfaced on \(distinctWalks.count) walks — \(times) near the same stretch of ground.` where `times = cluster.mentionCount == 2 ? "twice" : "\(cluster.mentionCount) times"` | `Pilgrim/Models/Threads/DossierSensesTracks.swift:63-65@0172e2b` |
| 2 | moonLine (base) | `The \(moon.moonName) has set: \(walkCount) walk\(walkCount == 1 ? "" : "s"), \(wordedCount) with recorded words` | `Pilgrim/Models/Threads/DossierSensesTracks.swift:114-115@0172e2b` |
| 2a | moonLine, no theme | base + `.` (period appended; `lemma: nil`) | `Pilgrim/Models/Threads/DossierSensesTracks.swift:124-126@0172e2b` |
| 2b | moonLine, with theme | base + `; '\(topTheme.displayTerm)' walked in \(topTheme.walks) of them.` | `Pilgrim/Models/Threads/DossierSensesTracks.swift:127@0172e2b` |
| 3 | markerColoring | `Absolutist words cluster around '\(displayTerm)' — \(timesPhrase(Int(ratio))) the density of the rest of the walk's speech.` | `Pilgrim/Models/Threads/DossierSensesTracks.swift:206@0172e2b` |
| 4 | intentionLineage | `\(ordinalWord(candidate.value.count)) walk in the last 30 days carrying some form of '\(candidate.key)'.` | `Pilgrim/Models/Threads/DossierSensesTracks.swift:159@0172e2b` |
| 5 | climbAnchoring | `'\(thread.displayTerm)' was spoken on the day's steepest climb.` | `Pilgrim/Models/Threads/DossierSensesTracks.swift:271@0172e2b` |
| 6 | weatherWeave | `\(head) where '\(thread.displayTerm)' surfaced were \(phrase).` where `head = walkUUIDs.count == 2 ? "Both walks" : "All \(walkUUIDs.count) walks"` | `Pilgrim/Models/Threads/DossierSensesTracks.swift:425-426@0172e2b` |
| 7 | photoAdjacency | `A photo was taken near where '\(best.displayTerm)' was spoken.` | `Pilgrim/Models/Threads/DossierSensesTracks.swift:240@0172e2b` |
| 8 | speechShape | `All the words came in the first third; the last \(minutes) minutes were wordless.` | `Pilgrim/Models/Threads/DossierSensesTracks.swift:354@0172e2b` |

Notes on the templates:

- Theme terms are wrapped in straight single quotes `'…'`; dashes are em-dashes with surrounding spaces (` — `).
- moonLine pluralizes `walk(s)` on `walkCount == 1` only; `wordedCount` has NO plural handling — it never modifies a countable noun. Don't template both counts symmetrically (ui-visual, `Pilgrim/Models/Threads/DossierSensesTracks.swift:114-115@0172e2b`).
- weatherWeave's `head` ternary is only safe because the immediately preceding `guard walkUUIDs.count >= 2 else { continue }` forces count ≥ 2 — the guard and the ternary must be ported and KEPT TOGETHER, or a future caller produces "All 1 walks" (ui-visual, `Pilgrim/Models/Threads/DossierSensesTracks.swift:418,425@0172e2b`).

### The two DIFFERENT "times" formatters — never consolidate

**placeResonance inline ternary** — bare numerals for 3+:

```swift
let times = cluster.mentionCount == 2 ? "twice" : "\(cluster.mentionCount) times"
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:63@0172e2b`

**shared `timesPhrase`** (sole production caller: markerLine) — spells 3–9:

```swift
private static let spelledSmall = [
    3: "three", 4: "four", 5: "five", 6: "six", 7: "seven", 8: "eight", 9: "nine"
]
```

```swift
static func timesPhrase(_ n: Int) -> String {
    if n == 2 { return "twice" }
    if let word = spelledSmall[n] { return "\(word) times" }
    return "\(n) times"
}
```

> `Pilgrim/Models/Threads/DossierSenses.swift:181-183,189-193@0172e2b`

*(consensus: edge-cases + ui-visual, flagged HIGH)* — a porter who "notices the duplication" and consolidates both call sites would silently change placeResonance's copy for counts 3–9 from digit form (`3 times`) to spelled form (`three times`). iOS ships BOTH formatters; port both, separately. `timesPhrase` has no singular case: n == 1 or n ≥ 10 renders the bare numeral (`1 times` is grammatically broken but latent — the markerLine gate guarantees n ≥ 2; reusing the helper in a new context without that guarantee could ship it — ui-visual). Do NOT "fix" by adding a case for 1 or extending the spelled range (edge-cases).

### `ordinalWord` — mixed forms, by design

```swift
private static let ordinalWords = [
    3: "Third", 4: "Fourth", 5: "Fifth", 6: "Sixth", 7: "Seventh",
    8: "Eighth", 9: "Ninth", 10: "Tenth", 11: "Eleventh", 12: "Twelfth"
]
```

```swift
static func ordinalWord(_ n: Int) -> String {
    if let word = ordinalWords[n] { return word }
    if (11...13).contains(n % 100) { return "\(n)th" }
    switch n % 10 {
    case 1: return "\(n)st"
    case 2: return "\(n)nd"
    case 3: return "\(n)rd"
    default: return "\(n)th"
    }
}
```

> `Pilgrim/Models/Threads/DossierSenses.swift:184-187,195-204@0172e2b`

- Capitalized word forms for 3–12 only (sentence-initial in the lineage line: "Third walk in the last 30 days…").
- The table STOPS at 12: 11th/12th are spelled ("Eleventh"/"Twelfth") but 13 falls through to numeral `13th` via the `(11...13) % 100` special case — an intentional asymmetry. "Completing" the table through 13, or checking `n % 10` before `n % 100`, both diverge and can reintroduce the classic 111/112/113 suffix bug (edge-cases).
- The dict's floor of 3 relies on the caller's `lineageMinWalks >= 3` guard, not its own enforcement (ui-visual).

### `skyPhrase` — per-bucket preposition split (under/in), not a uniform slot

```swift
static func skyPhrase(_ bucket: WeatherBucket) -> String? {
    switch bucket {
    case .rain: return "under rain"
    case .snow: return "under snow"
    case .clear: return "under clear skies"
    case .cloud: return "under clouds"
    case .wind: return "in wind"
    case .fog: return "in fog"
    case .unknown: return nil
    }
}
```

> `Pilgrim/Models/Threads/DossierSensesTracks.swift:383-393@0172e2b`

*(consensus: data + edge-cases + ui-visual)* — rain/snow/clear/cloud use "under"; wind/fog use "in". Port as 6 distinct phrase strings, NOT an `"under \(noun)"` formatter (edge-cases). `.unknown` is the only bucket with no phrase (returns nil → theme skipped). These are user-facing copy embedded in the sense line — do not re-derive from any WeatherCondition display label ("Partly cloudy" etc. is different wording; data).

### Conditional render rules (summary)

- **Block gate** — bundle non-nil AND base dossier non-nil AND ≥1 line, at `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:252-257@0172e2b`.
- **Per-line order/cap/dedup** — Sense declaration order; break at 3; lemma-dedup skip with nil-lemma bypass, at `Pilgrim/Models/Threads/DossierSenses.swift:122-134@0172e2b`.
- **Theme naming order** — lemma-alphabetical `activeThreads` order decides the displayed theme in 4 senses, at `Pilgrim/Models/Threads/DossierSenses.swift:157-163@0172e2b`.
- **moonLine theme-less form** — legitimate frequent state, period-terminated, dedup-immune, at `Pilgrim/Models/Threads/DossierSensesTracks.swift:124-126@0172e2b`.

### Field-report console format (DEBUG dev-tooling copy — verbatim if the harness is ported)

| element | literal | citation |
|---|---|---|
| banner | `"\n===== DOSSIER SENSES FIELD REPORT =====\n"` | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:385@0172e2b` |
| empty history | `"\n(no walk history on this device — nothing to report)\n"` then closer | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:387-388@0172e2b` |
| no transcribed walks | `"\n(no walk carries a transcribed recording — nothing to report)\n"` then closer | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:402-403@0172e2b` |
| per-walk header | `"\nWalk \(walk._startDate.value):\n"` | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:468@0172e2b` |
| per-line | `"  [\(sense)] \(line.text)\n"` (two-space indent) | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:473@0172e2b` |
| per-walk build time | `String(format: "  build: %.3fs\n", buildSeconds)` | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:476@0172e2b` |
| firing-rates header | `"\nFiring rates over \(eligible) walks with words:\n"` | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:406@0172e2b` |
| per-sense rate | `"  \(sense): \(firing[sense] ?? 0)/\(eligible)\n"` | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:408@0172e2b` |
| build summary | `String(format: "\nBuild time — median: %.3fs, max: %.3fs\n", medianSeconds, maxSeconds)` | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:416@0172e2b` |
| closer | `"=======================================\n"` (39 `=`) | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:417@0172e2b` |

These exact banner/indent/format strings are the only spec for what a team member expects to read on-device during the ship gate — free-styling the format loses comparability with the iOS process (ui-visual).

---

## Data

### Entities

#### `DossierSenses.Input` (13 fields — the pure engine's entire world)

| field | type | nullable? | source |
|---|---|---|---|
| currentWalkUUID | UUID | no | `Pilgrim/Models/Threads/DossierSenses.swift:76-90@0172e2b` |
| walkStart / walkEnd | Date | no | same |
| totalAscent | Double | no | same |
| elevationSeries | [ElevationSample] | no (may be empty) | same |
| photos | [PhotoPin] | no (may be empty) | same |
| currentRecordings | [CurrentRecording] | no | same |
| threads | [WalkThread] | no | same |
| backfillComplete | Bool | no | same |
| walkSnapshots | [WalkSnapshotRow] | no | same |
| recordingTimestamps | [UUID: Date] | no | same |
| fixes | [UUID: RouteFix] | no | same |
| moon | MoonInput? | **yes — the only optional** | same |

Dropping `backfillComplete` or `fixes` silently disables placeResonance/climbAnchoring/photoAdjacency instead of erroring (data).

#### `DossierSenses.MoonInput`

| field | type | notes |
|---|---|---|
| lunationIndex | Int | closed lunation's index |
| moonName | String | pre-resolved, timezone-local |
| start / end | Date | closed lunation span, `[start, end)` semantics |
| lastReportedIndex | Int? | nil = never shown |
| currentWalkHasWords | Bool | derived from currentRecordings |
| allWalkDates | [Date] | pre-filtered to the fetch bundle's UNION range, not all-time |
| wordedWalkDates | [Date] | one date per WALK (dictionary-collapsed) |

> `Pilgrim/Models/Threads/DossierSenses.swift:65-74@0172e2b`

#### Supporting value types

| type | fields | source |
|---|---|---|
| `Coordinate` | latitude: Double, longitude: Double — platform-agnostic, no CLLocation/android.location coupling | `Pilgrim/Models/Threads/DossierSenses.swift:28-31@0172e2b` |
| `RouteFix` | coordinate, horizontalAccuracy: Double, gapSeconds: TimeInterval — gapSeconds frozen at resolution time | `Pilgrim/Models/Threads/DossierSenses.swift:33-37@0172e2b` |
| `ElevationSample` | timestamp: Date, altitude: Double — sourced from walk.routeData, NOT the routeFixNear path | `Pilgrim/Models/Threads/DossierSenses.swift:39-42@0172e2b` |
| `PhotoPin` | capturedAt: Date, coordinate: Coordinate? — nil by sentinel translation, not column absence | `Pilgrim/Models/Threads/DossierSenses.swift:44-47@0172e2b` |
| `CurrentRecording` | uuid, start, end, text, wordCount, themes: [Theme] — end falls back to start upstream | `Pilgrim/Models/Threads/DossierSenses.swift:49-56@0172e2b` |
| `WalkSnapshotRow` | walkUUID, startDate, intention: String?, weatherCondition: String? (raw stored rawValue string) | `Pilgrim/Models/Threads/DossierSenses.swift:58-63@0172e2b` |
| `SenseLine` | text: String, lemma: String? — nil lemma = dedup-immune | `Pilgrim/Models/Threads/DossierSenses.swift:92-95@0172e2b` |
| `Output` | lines: [String], reportedLunationIndex: Int? — sole trigger for the persistence write | `Pilgrim/Models/Threads/DossierSenses.swift:97-100@0172e2b` |
| `PlaceCluster` | mentionCount: Int, walkCount: Int, spread: CLLocationDistance | `Pilgrim/Models/Threads/DossierSensesTracks.swift:8-12@0172e2b` |
| `AscentRun` | start, end: Date, gain: Double, averageRate: Double | `Pilgrim/Models/Threads/DossierSensesTracks.swift:254-259@0172e2b` |
| `Lunation` | index: Int, start, end, fullMoon: Date; `id = index` — end minted as `newMoonDate(at: index+1)`, never start+length | `Pilgrim/Models/Threads/LunationCalendar.swift:6-13@0172e2b` |
| `WeatherCondition` | 10 rawValues: clear, partlyCloudy, overcast, lightRain, heavyRain, thunderstorm, snow, fog, wind, haze | `Pilgrim/Models/Weather/WeatherService.swift:5-8@0172e2b` |
| `WeatherBucket` | rain, snow, clear, cloud, wind, fog, unknown (7 cases; unknown is first-class) | `Pilgrim/Models/Threads/DossierSensesTracks.swift:364-366@0172e2b` |
| `DossierSensesFetchBundle` | walkStart, walkEnd, totalAscent, elevationSeries, photos, walkSnapshots, recordingTimestamps, closedLunation: Lunation, moonName: String — NO fixes field, by design | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:7-17@0172e2b` |
| `MemoKey` | changeCount: Int, walkUUID: UUID, backfillComplete: Bool, moonState: Int?, lunationIndex: Int?, intention: String? | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:36-43@0172e2b` |
| `SensesAssemblyState` | walkUUID, recordings, contextsByUUID, threads, walkIndex, backfillComplete, moonState — pure lint-gate parameter bundling, no independent semantics; Android may flatten to parameters | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:231-239@0172e2b` |
| `Sense` | 8 cases in binding order: placeResonance, moonLine, markerColoring, intentionLineage, climbAnchoring, weatherWeave, photoAdjacency, speechShape | `Pilgrim/Models/Threads/DossierSenses.swift:108-111@0172e2b` |

#### Tuning constants — 16 values, one block, verbatim

```swift
static let lineCap = 3
static let placeClusterRadius: CLLocationDistance = 150
static let placeCandidateThemeCap = 4
static let hygieneMaxGap: TimeInterval = 90
static let hygieneMaxAccuracy: Double = 100
static let photoTieRadius: CLLocationDistance = 75
static let photoTieMaxInterval: TimeInterval = 600
static let climbMinTotalAscent: Double = 50
static let climbMinRunGain: Double = 20
static let climbSmoothingWindow = 5
static let climbTopDecile = 0.9
static let markerWindowRadius = 15
static let markerMinWindowAbsolutist = 3
static let markerMinDensityRatio = 2.0
static let speechShapeMinWordlessRemainder: TimeInterval = 30 * 60
static let lineageMinWalks = 3
```

> `Pilgrim/Models/Threads/DossierSenses.swift:11-26@0172e2b`

All 16 live in ONE static block on DossierSenses (not a shared Constants file). Android must centralize them equivalently — scattering inline literals per Kotlin file makes the next iOS retune (two ship-gate edits already hit this file in the pin cycle) impossible to keep in sync (edge-cases, data). The 17th shared value lives OUTSIDE the senses files:

```swift
static let recurrenceWindow: TimeInterval = 30 * 86400
```

> `Pilgrim/Models/Threads/ThreadStore.swift:30@0172e2b`

Read by three senses (placeResonance, intentionLineage, weatherWeave) plus `gatherSensesBundle` and `resolveFixes` — one constant, five call sites; never hand-copy "30 days" per site (data).

### Persistence ops

- `routeFixNear(timestamp:)` at `Pilgrim/Models/Data/DataManager+Query.swift:181-212@0172e2b` — dispatcher: **off-main capable** (`threadSafeSyncReturn`); bounded `[timestamp − 90 s, timestamp + 90 s]`, columns `_timestamp/_latitude/_longitude/_horizontalAccuracy`, ascending, fetchLimit 240, winner = min `(gapSeconds, horizontalAccuracy)`; accuracy NOT filtered SQL-side.
- `walkSensesSnapshot(from:to:)` at `Pilgrim/Models/Data/DataManager+Query.swift:217-241@0172e2b` — dispatcher: **@MainActor** (Android: suspend DAO instead — see notes); columns `_uuid/_startDate/_comment/_weatherCondition`, predicate `[from, to]` inclusive, ascending.
- `voiceRecordingTimestampIndex()` at `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:159-175@0172e2b` — dispatcher: **@MainActor**; unbounded two-column fetch, `[UUID: Date]` out.
- `voiceRecordingWalkIndex()` at `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:177-226@0172e2b` — dispatcher: **@MainActor**; two fetches + in-memory objectID join over the frozen legacy relationship column; `[UUID: (walkUUID, date)]` out. Android: single query on the fresh Room schema, same result shape. Perf note: an N+1 per-recording walk lookup would regress the one-round-trip intent (behavior).
- Transcription save at `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:97-126@0172e2b` — async CoreStore transaction writes `_transcription`, then EITHER detached utility-priority theme analysis (threadsAfterWalks on) OR `removeContext(for: uuid)` (off). One write path shared by batch transcription, retranscribe, and manual edit.
- Dossier memo at `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:45-46@0172e2b` — in-process only (`private static var memo` + NSLock); never persisted; does not survive process death. Cache-presence vs cached-nil distinguished via `String??` (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:177-185@0172e2b`).
- Moon-line write at `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:258-260@0172e2b` — see DataStore keys below.

### Network endpoints

None. This slice is entirely on-device (no changes to the CLAUDE.md ecosystem table).

### File I/O paths

- `<Documents>/Recordings/**` at `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:30-79@0172e2b` — the recordings-file cleanup family (cleanupRecordingFiles / deleteRecordingFile / recordingFileCount [filters `.m4a`] / deleteAllRecordingFiles / cleanupEmptyRecordingsDirectory). Synchronous FileManager calls with NO dispatcher annotation of their own — each caller pays the cost on its own thread; Android callers must already be on `Dispatchers.IO` (data). Adjacent to, not part of, the senses pipeline.

### DataStore / UserDefaults keys

| key | type | semantics | source |
|---|---|---|---|
| `threadsMoonLineLastLunationIndex` | Int (absent = nil) | last lunation index the moon line was actually SHOWN for; read fresh every build via `defaults.object(forKey:) as? Int`; written ONLY when `output.reportedLunationIndex != nil` | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:26,96,258-260@0172e2b` |

Absent must read as null ("never shown") — defaulting to 0 collides with a real lunationIndex of 0 (data). Android already has the matching key: `ThreadsPreferences.MOON_LINE_LAST_LUNATION_INDEX = intPreferencesKey("threadsMoonLineLastLunationIndex")` plus `clearMoonLineIndex()` (see Android implementation notes).

---

## Edge cases & invariants

Cross-lens consensus rows first (**[consensus]**), then singles, sorted by file.

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **[consensus]** `Pilgrim/Models/Threads/DossierSenses.swift:4-8@0172e2b` | `Date() is never called here; time arrives as data` | Sense engine is pure; all state enters as arguments | `Clock.System.now()` or a repository read inside a sense is the convenient wrong thing |
| **[consensus]** `Pilgrim/Models/Threads/DossierSenses.swift:102-111@0172e2b` | `Declaration order IS the spec's binding priority order` | Enum order = output order = cap/dedup priority; exactly 8 cases | Alphabetizing/regrouping a Kotlin enum "for readability"; porting the cut questionDensity from stale docs |
| **[consensus]** `Pilgrim/Models/Threads/DossierSenses.swift:122-124@0172e2b` | `guard lines.count < lineCap else { break }` | Cap-3 is an early break — senses past the cap never execute | Evaluate-all-then-take(3) changes side-effect semantics (reportedLunationIndex) |
| **[consensus]** `Pilgrim/Models/Threads/DossierSenses.swift:125-130@0172e2b` | `guard !used.contains(lemma) else { continue }` | Dispatcher-level lemma dedup: skip line, keep iterating; nil lemma bypasses | Deleting the "redundant" belt; suppressing lemma-nil lines via `""` default |
| **[consensus]** `Pilgrim/Models/Threads/DossierSenses.swift:131-134@0172e2b` | `if sense == .moonLine { reportedLunationIndex = input.moon?.lunationIndex }` | Lunation is "reported" only when the line is APPENDED | Marking reported when `moonLine()` merely returns non-nil burns the budget on suppressed lines |
| **[consensus]** `Pilgrim/Models/Threads/DossierSenses.swift:165-167@0172e2b` | `fix.gapSeconds <= hygieneMaxGap && fix.horizontalAccuracy < hygieneMaxAccuracy` | Mixed operators: gap ≤ 90 inclusive, accuracy < 100 exclusive; shared by 2 senses | Uniform `<=`/`<` on both halves flips boundary acceptance in two senses at once |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:15@0172e2b` | `guard input.backfillComplete else { return nil }` | placeResonance is silent until backfill completes | Treating backfill as a spinner concern, not a content gate |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:45-46@0172e2b` | `.prefix(placeCandidateThemeCap)` before `where !suppressed…` | Cap 4 applies BEFORE suppression filtering | `.filter{}.take(4)` reverses the order and shrinks the pool |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:59-62@0172e2b` | `cluster.spread < baseline / 2` | Strict <; baseline 0 (one-spot walker) is unsatisfiable BY DESIGN | `<=` or a baseline==0 special case fires on routine-only walkers |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:90-93@0172e2b` | `mentionCount > best!.mentionCount \|\| (== && spread < best!.spread)` | Cluster tie-break: count desc, spread asc, earliest seed; strict inequalities | `maxByOrNull` drops the spread key; `>=` prefers later seeds on ties |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:63@0172e2b` | `cluster.mentionCount == 2 ? "twice" : "\(cluster.mentionCount) times"` | placeResonance's counts NEVER spell 3–9; separate from timesPhrase | Consolidating the "duplicate" formatters changes shipped copy |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:108-110@0172e2b` | `date >= moon.start && date < moon.end` | Lunation membership is half-open [start, end) | Inclusive end double-counts a boundary walk into two lunations |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:105-107@0172e2b` | `moon.lastReportedIndex != moon.lunationIndex` | Once-per-lunation = index compare, not boolean | A reset boolean mis-handles skipped lunations (index jumps 2+) |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:123,156@0172e2b` | `.min { ($0.walks, $1.lemma) > ($1.walks, $0.lemma) }` | Max-by-count, tie → lexicographically SMALLEST lemma; identical idiom in moonLine + lineage | "Fixing" the crossed indices, or `maxByOrNull`, changes tie winners; implementing it right in one sense but not the other |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:124-126@0172e2b` | `return SenseLine(text: text + ".", lemma: nil)` | moonLine's theme-less fallback still fires (and still burns the budget) | Assuming every sense carries a lemma; returning nil when no theme qualifies |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:159@0172e2b` | `"…walk in the last 30 days carrying some form of…"` | "30 days" is hardcoded copy, not derived from recurrenceWindow | "Fixing" it to derive from the constant (or vice versa) breaks copy parity |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:143-144@0172e2b` | `let todayIntention = today.intention, !todayIntention.isEmpty` | nil and empty intention are identically silence | Null-check-only port lets `""` through to lemmatization |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:202-206@0172e2b` | `ratio = restDensity > 0 ? windowDensity / restDensity : windowDensity / overallDensity` | Gate uses vs-OVERALL; displayed ratio uses vs-REST (fallback overall); may understate, never overstate | Reusing the gate's denominator for display reports the wrong number almost always |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:206@0172e2b` | `timesPhrase(Int(ratio))` | Displayed multiplier TRUNCATES (2.97 → "twice") | `roundToInt()` reports one bucket higher at boundaries |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:230-235@0172e2b` | `(separation, gap, photo.capturedAt) < (best!.distance, best!.gap, best!.capturedAt)` | Photo tie-break: place first, time second, capture instant third; ONE global best | Time-first ordering; per-thread bests; splitting the nil-check from the compare |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:244-247@0172e2b` | `if instant >= start && instant <= end { return 0 }` | Photo inside the recording span (inclusive both ends) = zero gap | Midpoint/start-only distance fails the 600 s guard on long recordings |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:262-267@0172e2b` | `recording.start <= run.end && recording.end >= run.start` | Climb match is interval OVERLAP, and 50 m total vs 20 m per-run are separate constants | Containment check misses recordings straddling the climb; conflating the two ascent floors |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:348-352@0172e2b` | `worded.allSatisfy({ $0.end <= firstThirdEnd })` … `remainder > speechShapeMinWordlessRemainder` … `Int(remainder / 60)` | ALL worded recordings in first third; strict > 1800 s; minutes truncate | Last-recording-only check; `>=`; rounding minutes |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:353-357@0172e2b` | `lemma: nil` | speechShape never joins lemma dedup in either direction | Modeling lemma as non-null String with `""` default |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:404-411,420-424@0172e2b` | `conditionCounts[shared, default: 0] < modeCount` | Ship-gate (2026-08-25) mode-strict rule; tie with mode suppresses; unanimity across the theme's buckets required | Porting the older >50% majority rule from stale docs; 80%-share leniency |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:418,425@0172e2b` | `guard walkUUIDs.count >= 2` … `count == 2 ? "Both walks" : "All \(count) walks"` | "Both walks" iff exactly 2; only safe under the paired ≥2 guard | Reusing the ternary without the guard yields "All 1 walks" |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:383-393@0172e2b` | `case .wind: return "in wind"` | Preposition split under/in is per-bucket copy, not a template slot | Normalizing to "under X" for consistency |
| **[consensus]** `Pilgrim/Models/Threads/DossierSensesTracks.swift:371-381@0172e2b` | `default: return .unknown` | Bucket map total over the 10-string vocabulary; explicit unreachable-looking fallback kept; drift test enforces totality | Dropping the "dead" default; Android-only condition strings silently vanishing into unknown |
| **[consensus]** `Pilgrim/Models/Threads/LunationCalendar.swift:17@0172e2b` | `lunationLength = LunarPhase.synodicMonth * 86400` | Length DERIVED from the shared synodic constant, never redeclared | Two independent literals desync phase math from lunation math |
| **[consensus]** `Pilgrim/Models/Threads/LunationCalendar.swift:19-24@0172e2b` | `never start + length, which drifts by a ulp and splits boundaries` | All boundaries minted as epoch + index × length | Chaining start+length accumulates FP drift; `end` per instance recomputed differently splits `end == next.start` |
| **[consensus]** `Pilgrim/Models/Threads/LunationCalendar.swift:38-42@0172e2b` | `if date >= newMoonDate(at: index + 1) { index += 1 }` / `if date < newMoonDate(at: index) { index -= 1 }` | BOTH round-off correction guards required | "Looks redundant, delete it" — Kotlin Doubles round differently at the ULP |
| **[consensus]** `Pilgrim/Models/Threads/LunationCalendar.swift:52-56,62-66@0172e2b` | `monthMoonNames[calendar.component(.month, from: lunation.fullMoon) - 1]` | 12 verbatim names (incl. "Hunter's Moon" apostrophe, "Corn Moon" Sep), month resolved in DEVICE-LOCAL zone at read time | UTC month "safe default" renames boundary moons; off-by-one in `- 1` shifts every name |
| **[consensus]** `Pilgrim/Models/LunarPhase.swift:18-23@0172e2b` | `29.53058770576` / `2000-01-06 18:14 UTC` | The two epoch constants, verbatim, UTC | Any digit slip or local-zone epoch shifts every lunation index/boundary/name ever computed |
| **[consensus]** `Pilgrim/Models/LunarPhase.swift:25-29@0172e2b` | `return age < 0 ? age + synodicMonth : age` | Negative-remainder wrap into [0, synodicMonth) | Kotlin `%` on Double truncates toward zero too — dropping the guard reproduces the bug for pre-epoch dates |
| **[consensus]** `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:71-74@0172e2b` | `from: min(windowStart, lunation.start), to: max(walk.endDate, lunation.end)` | Snapshot fetch = UNION of 30-day window and closed lunation | Fetching only the 30-day window starves moonLine's counts |
| **[consensus]** `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:255-257@0172e2b` | `dossier! += "\n\n**Noticed:**\n" + output.lines.joined(separator: "\n")` | Block is all-or-nothing; bold header, exact whitespace, no bullets | "Defensive" always-on header; normalized markdown |
| **[consensus]** `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:258-260@0172e2b` | `guard let reported = output.reportedLunationIndex else { return state.moonState }` | Moon-state write strictly gated on fired-and-survived | Writing on eligibility (`lunationIndex != lastReported`) alone |
| **[consensus]** `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:96-101@0172e2b` | `let moonState = defaults.object(forKey: moonLineDefaultsKey) as? Int` | Moon state re-read fresh EVERY build and folded into the memo key; absent = nil, not 0 | changeCount-only memo returns stale dossiers after external key writes; 0-default collides with lunationIndex 0 |
| **[consensus]** `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:4-6,266-291@0172e2b` | `Route fixes are NOT here — the builder resolves them lazily` | Fixes resolved per-needed-UUID, sorted by uuidString, with in-memory current-recording timestamp fallback | Eager bulk fetch at gather time; dropping the `?? currentRecordings…start` fallback for fresh current-walk recordings |
| **[consensus]** `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:461-473@0172e2b` | `suppressed: []` … `moonState: nil` | Field report is uncapped, undeduped, budget-blind, and NEVER writes | Reading its firing rates as production frequencies; a debug tool that corrupts real moon state |
| **[consensus]** `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:156-160@0172e2b` | `the senses' 30-day windows and coordinate lookups need per-RECORDING times` | Recording-instant index ≠ walk-date index; both exist and serve different checks | One shared date index mis-windows late-in-walk recordings |
| `Pilgrim/Models/Threads/DossierSenses.swift:169-172@0172e2b` | `CLLocation…distance(from:)` | All distance thresholds tuned against CLLocation's geodesic | Substituting spherical haversine unverified at 75/100/150 m scales |
| `Pilgrim/Models/Threads/DossierSenses.swift:174-179@0172e2b` | `(sorted[mid - 1] + sorted[mid]) / 2` | Even-count median averages the two middle elements; mid = count/2 | Off-by-one shifts the placeResonance baseline |
| `Pilgrim/Models/Threads/DossierSenses.swift:189-193@0172e2b` | `return "\(n) times"` | timesPhrase renders "1 times" for n==1 — latent, guarded by callers | Reusing the helper in a context without the n ≥ 2 guarantee |
| `Pilgrim/Models/Threads/DossierSenses.swift:195-204@0172e2b` | `if (11...13).contains(n % 100) { return "\(n)th" }` | Ordinal table stops at 12; 13 → "13th"; %100 check precedes %10 | Completing the table "for symmetry"; reordering the checks (111/112/113 bug) |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:17-20@0172e2b` | `instant >= windowStart && instant <= input.walkEnd` | placeResonance windows recordings by instant, inclusive both ends (unlike lunation half-open) | One shared in-window helper erasing the per-sense inclusive/half-open distinction |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:35,58@0172e2b` | `$0.key.uuidString < $1.key.uuidString` | Determinism via UUID-STRING lexicographic sort | `java.util.UUID.compareTo` (signed-long order) flips seed iteration and tie winners |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:37-42@0172e2b` | `for i in 0..<(ordered.count - 1) { for j in (i + 1)..<ordered.count` | Upper-triangular pairwise loop — no self/double counting | Inclusive ranges or j from i skew the baseline low |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:83@0172e2b` | `guard mentionCount >= 2, walkCount >= 2 else { continue }` | Inner cluster gates re-check ≥2 among clustered members, separate from the outer window-wide ≥2 | Collapsing outer/inner checks misses qualifying-thread-but-loose-cluster |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:111-113@0172e2b` | `guard wordedCount >= 1 else { return nil }` | Closed lunation must contain ≥1 worded walk (current walk is in the OPEN lunation) | Assuming currentWalkHasWords implies wordedCount ≥ 1 |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:114-115@0172e2b` | `walk\(walkCount == 1 ? "" : "s")` | Plural only on walkCount; wordedCount unpluralized | Symmetric templating adds a spurious branch |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:136-138@0172e2b` | `subtracting(SpokenStoplist.scaffoldLemmas)` | Scaffold stoplist applied to today AND history identically | Asymmetric filtering breaks lemma matching |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:169-179@0172e2b` | `return SenseLine(text: text, lemma: thread.lemma)` (first match) | markerColoring is first-fit in (lemma-order thread × list-order recording); NOT best-of | Adding a max-by-ratio pass changes the reported theme |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:184-190@0172e2b` | `tokens.lastIndex(where: { $0.start <= mention.start })` | Window anchor = LAST token starting ≤ mention offset; windows merged via IndexSet union | `firstIndex` or per-mention separate windows shift/double-count tokens |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:194-198@0172e2b` | `windowAbsolutist >= markerMinWindowAbsolutist` … `windowDensity >= markerMinDensityRatio * overallDensity` | Absolute floor (3) AND relative gate (2×) both required | Density-only fires on tiny transcripts |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:215-218@0172e2b` | `photo.coordinate.map { … }` | Only coordinate-bearing photos considered (sentinel already translated upstream) | Passing raw −1/−1 through as a coordinate near null island |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:224,227-229@0172e2b` | `guard let fix = input.fixes[recording.uuid], qualifies(fix)` … `separation <= photoTieRadius` … `gap <= photoTieMaxInterval` | Recording fix must pass hygiene; BOTH 75 m AND 600 s gates required | Distance-only or time-only gating produces far more ties |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:279-289@0172e2b` | `let half = climbSmoothingWindow / 2` | CENTERED window-5 moving average, edge-clamped | Trailing average shifts smoothing in time |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:303-305@0172e2b` | `positive[Int(Double(positive.count - 1) * climbTopDecile)]` | Threshold = nearest-rank-down over POSITIVE rates only | count*0.9 / rounding / including flats shifts the rank |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:326-333@0172e2b` | `if index == segments.count - 1 { closeRun(endingAt: index) }` | In-progress run force-closed at series end | Walks ending mid-climb lose their steepest run |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:308-324@0172e2b` | `guard gain >= climbMinRunGain, duration > 0` … `run.averageRate > best!.averageRate` | Runs need ≥20 m gain; best run by max average rate | Skipping the per-run gain floor; picking by total gain instead of rate |
| `Pilgrim/Models/Threads/DossierSensesTracks.swift:398-401@0172e2b` | `row.weatherCondition.map(bucket(forStoredCondition:)) ?? .unknown` | nil stored condition → unknown → excluded from claims and unanimity | Treating missing weather as skippable instead of unanimity-breaking |
| `Pilgrim/Models/LunarPhase.swift:16,36-46@0172e2b` | `case 0 ..< eighth: return "New Moon"` | Half-open eighth-buckets; `isWaxing` strict < at midpoint; scheme distinct from moonName | Closed ranges overlap at boundaries; conflating the two "moon name" schemes |
| `Pilgrim/Models/Threads/LunationCalendar.swift:45-49@0172e2b` | `lunation(at: lunation(containing: date).index - 1)` | The open lunation is NEVER eligible; closed = containing − 1 through the corrected path | Re-deriving "closed" from now bypasses the correction guards |
| `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:26@0172e2b` | `"threadsMoonLineLastLunationIndex"` | Exact key string; Int; injectable defaults for tests | Key-string drift breaks upgrade continuity with the shipped Android key |
| `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:52,77@0172e2b` | `now: Date = Date()` | ONE wall-clock capture for the whole senses pass | Re-reading the clock mid-build across a lunation boundary |
| `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:62-70@0172e2b` | `photo.capturedLat == -1 && photo.capturedLng == -1` | Sentinel = exact equality on BOTH axes simultaneously | Either-axis or epsilon checks diverge on pathological real coordinates |
| `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:86,252@0172e2b` | `senses: DossierSensesFetchBundle? = nil` … `guard let senses, dossier != nil` | No bundle → silently no Noticed: block (no error signal), by design | A call site forgetting to gather the bundle ships block-less dossiers unnoticed |
| `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:90@0172e2b` | `guard UserPreferences.threadsAfterWalks.value, !recordings.isEmpty` | Whole build gated on the feature toggle + non-empty recordings | Senses evaluated with the feature off |
| `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:299-305@0172e2b` | `end: recording.endTimestamp ?? recording.timestamp` | Missing context/UUID drops the recording from ALL senses; end falls back to start | Letting context-less recordings into wordCount-only senses; nil end crashing window checks |
| `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:317-322@0172e2b` | `wordedWalkDates[walk.walkUUID] = walk.date` | Worded dates collapse to one per WALK (dictionary overwrite) | Per-recording list inflates moonLine's wordedCount |
| `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:364-366@0172e2b` | `NSClassFromString("XCTestCase") == nil` | Field report never runs under a test runner | Harness output polluting instrumented-test logs / CI |
| `Pilgrim/AppDelegate.swift:99-111@0172e2b` | `if CommandLine.arguments.contains("--demo-mode") { … return }` | Demo-mode and field-report are mutually exclusive by construction | Treating the two debug flags as combinable |
| `Pilgrim/AppDelegate.swift:117-120@0172e2b` | `self.appLaunchState = .done` … `self.runPostDoneLaunchTasks()` | UI unlock strictly precedes the harness; fire-and-forget | Gating startup on the report |
| `Pilgrim/Models/Data/DataManager+Query.swift:183-196,207@0172e2b` | `.tweak { $0.fetchLimit = 240 }` … `gapSeconds: abs(sampleTime.timeIntervalSince(timestamp))` | Bounded ±90 s (shared constant), cap 240, gap frozen at resolve time | Recomputing gap later against a different instant; unbounded scans of the largest table |
| `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:164-169@0172e2b` | `From<VoiceRecording>().select(… _uuid, _startDate)` (no where) | Timestamp index is deliberately unbounded; windowing is the caller's job | "Optimizing" in a date bound breaks callers needing pre-filter timestamps |
| `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:107-118@0172e2b` | `} else { transcriptContextStore.removeContext(for: uuid) }` | Saving an edit with Threads OFF actively deletes stale analysis | Feature-on-only port skips the cleanup branch |

---

## Android implementation notes

### Already shipped (U1–U7, on HEAD `3c61991b`) — reuse, don't rebuild

- `app/src/main/java/org/walktalkmeditate/pilgrim/core/threads/` — the full threads engine: `TranscriptNlp`, `ThemeExtractor`, `MarkerAnalyzer`, `MarkerLexicons` (absolutist lexicon for markerLine), `SpokenStoplist` (scaffoldLemmas for intentionLemmas), `TranscriptContext`/`TranscriptContextStore`/`TranscriptContextAnalyzer`, `ThreadStore` (lemma-sorted threads — the `activeThreads` ordering source), `ThreadsBackfill` (`isComplete` = the placeResonance gate), `BatteryGate`, `ThreadsFullWipe`, `VaderSentiment`, `WordNetLexicon`.
- `core/threads/ThreadsPreferences.kt` — `MOON_LINE_LAST_LUNATION_INDEX = intPreferencesKey("threadsMoonLineLastLunationIndex")` (line 215) with `clearMoonLineIndex()` already shipped; the key string matches iOS's `moonLineDefaultsKey` exactly. Nullable read (absent = never shown) is the required semantics.
- `core/threads/ThreadsDossierBuilder.kt` — U7 pre-positioned this slice's seams: `DossierBlock` (line 20), six-field `ThreadsDossierMemoKey` with `moonState`/`lunationIndex` slots currently hardcoded `null` (lines 33-43, 133-134 — "U9's slots") and a `**Noticed:**`-ready dossier pipeline (comment at line 82). This slice fills those slots: real `moonState` from ThreadsPreferences, real `lunationIndex` from the closed lunation, plus the `intention` key component, and appends the block per `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:252-260@0172e2b` semantics.
- `core/threads/ThreadsDossierFormatter.kt` — base dossier text; the senses block appends AFTER it, all-or-nothing.
- `core/prompt/AttentionDirectives.kt` (v2) + `PromptAssembler`/`PromptGenerator`/`ContextFormatter` — downstream consumers of the dossier; no changes expected from this slice beyond the block appearing in the text.
- `data/dao/VoiceRecordingDao.kt` — `recordingTimestampIndex()` (line 144, `RecordingTimestampRow`) and `recordingPaceIndex()` (line 152) already shipped as suspend projections — the Android counterpart of `voiceRecordingTimestampIndex` (unbounded, per-recording instants — keep it unbounded). A recording→walk index projection also exists per the U7 plan; reuse for `walkIndex`/`wordedWalkDates` (result shape `[UUID → (walkUUID, walkDate)]` must match `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:177-226@0172e2b`).
- `data/weather/WeatherCondition.kt` — all 10 rawValues (`clear` … `haze`) verified identical to `Pilgrim/Models/Weather/WeatherService.swift:5-8@0172e2b`; the bucket table ports 1:1, and the exhaustiveness drift test should iterate `WeatherCondition.entries` asserting none map to `unknown`.
- `core/celestial/MoonCalc.kt` + `MoonPhase.kt` — existing moon math (note: Android's name is `MoonPhase`, NOT "LunarPhase").
- `location/FusedLocationSource.kt:206` — 100 m accuracy ceiling already enforced at the capture side; cite as the coordinate-hygiene precedent when implementing `qualifies()` (the sense-side strict `< 100` re-check is still required — capture-side filtering is not a substitute).
- DI: one Hilt module per concern under `di/` (`TranscriptionModule` naming precedent for a `ThreadsModule`); inject the existing configured `Json` (`di/NetworkModule.kt` `provideJson`) — never construct a new one.
- Entities: `VoiceRecording` (uuid unique-indexed, startTimestamp, transcription, wordsPerMinute), `RouteDataSample` (indexed walk_id+timestamp, `horizontalAccuracyMeters`), `Walk` (intention, weatherCondition, weatherTemperature), `WalkPhoto` (takenAt + captured coords) — everything the two new queries need is indexed.
- Test conventions: JUnit4 + Turbine; Robolectric for platform objects; golden-string tests for the templates (pure engine = plain JVM unit tests, no Robolectric needed).

### Planned divergences (by plan — not drift)

1. **Field report = debug source set + explicit developer trigger.** Android has no `--senses-field-report` launch-arg convention. The harness lives in the `debug` source set behind an explicit developer trigger (per the Phase 20 plan), not CommandLine args. Everything else about it ports: uncapped, empty suppressed set, moonState = null, NEVER writes the preference, runs after UI is unlocked, and off the main thread (Room DAOs are suspend — the iOS `@MainActor` requirement inverts into a "never on main" requirement on Android; a main-thread Room read would ANR, matching the concern in `Pilgrim/AppDelegate.swift:151-158@0172e2b`'s hop rationale). Keep the verbatim console format if comparability with iOS ship-gate reports is wanted.
2. **`MoonCalc` epoch promotion + shared-constant invariant.** iOS derives ALL lunation math from `LunarPhase.synodicMonth`/`knownNewMoon` (`Pilgrim/Models/Threads/LunationCalendar.swift:17,22-24@0172e2b`). Android's `MoonCalc` holds the epoch as `private val EPOCH_JD` (Julian Day form, line 39, parsed from `"2000-01-06T18:14:00Z"` — instant matches iOS) and `internal const val SYNODIC_DAYS` (line 36). The port must promote the epoch to `internal` (expose the epoch *Instant*, not the JD double) so the new `LunationCalendar` shares it, and keep lunation-boundary arithmetic in epoch-relative Double seconds (matching iOS `TimeInterval` math) so `lunation(at n).end == lunation(at n+1).start` holds bit-for-bit — do NOT round-trip through epoch-millis per boundary.
   **⚠ Constant-value drift found during synthesis:** `MoonCalc.SYNODIC_DAYS = 29.530588770576` (MoonCalc.kt:36) ≠ iOS `synodicMonth = 29.53058770576` (`Pilgrim/Models/LunarPhase.swift:18@0172e2b`) — an extra "8" digit. Δ ≈ 0.092 s per lunation ≈ 30 s cumulative at the ~330th lunation (2026). Lunation indices/boundaries/moon names would disagree with iOS for instants within that skew of a boundary. See Open questions — resolve BEFORE building LunationCalendar on either constant.
3. **Room DAO equivalents for the two queries.**
   - `routeFixNear` → a `RouteDataSampleDao` suspend query: `WHERE timestamp BETWEEN :ts - 90_000 AND :ts + 90_000 ORDER BY timestamp ASC LIMIT 240` projecting (timestamp, lat, lng, horizontalAccuracyMeters), then the Kotlin side computes frozen `gapSeconds = abs(sampleTime - ts)` and picks the `(gap, accuracy)`-lexicographic min — replicating `Pilgrim/Models/Data/DataManager+Query.swift:181-212@0172e2b` including the shared `hygieneMaxGap` constant for the bound. Resolution stays per-UUID lazy (one bounded query per needed recording, uuidString-sorted) — no bulk join.
   - `walkSensesSnapshot` → a `WalkDao` suspend projection (`walkUUID, startDate, intention, weatherCondition` for `startDate BETWEEN :from AND :to ORDER BY startDate`) — suspend DAO, NOT a main-thread read (iOS is `@MainActor` because CoreStore is; Room forbids main — this is the plan's accepted divergence, see Open questions).
4. **SenseOutput shape pre-positioned.** The U7 builder already models the dossier as `DossierBlock` with memo-key slots — the senses `Output(lines, reportedLunationIndex)` should flow into `appendSensesBlock`-equivalent logic inside the existing builder rather than a new parallel pipeline. `SensesAssemblyState` need not exist on Android (no parameter-count lint gate) — flatten to parameters (data lens).
5. **Tokenizer unit for markerLine offsets.** iOS `TranscriptNLP.wordTokenOffsets` counts grapheme Characters; the Android plan pins ONE unit in `TranscriptNlp` for hash/mention offsets — markerLine's `lastIndex(where: { $0.start <= mention.start })` anchor must use the SAME unit as the stored mention offsets or windows land off-center (plan decision, pre-litigated).
6. **Purity architecture maps cleanly**: pure `DossierSenses` object (no Hilt, no clock, no Room) + a suspend `gatherSensesBundle` in the builder (the one `now` capture) + DataStore read/write at the seams. `Coordinate` stays its own value class — do not couple to `android.location.Location` (data). Distance: `Location.distanceBetween` (ellipsoidal) is the closest analogue of CLLocation's geodesic — verify at the 75/100/150 m scales, don't assume (see Open questions).

### Mapping deltas vs the CLAUDE.md base table (delta entries only)

- iOS UserDefaults (`moonLineDefaultsKey`) → shipped `ThreadsPreferences` DataStore key (same string, nullable Int read).
- iOS `@MainActor` CoreStore reads → suspend Room DAOs (Dispatchers.IO); iOS `threadSafeSyncReturn` off-main capability → plain suspend (routeFixNear must remain callable from the builder's background context).
- iOS `#if DEBUG` + CommandLine args → debug source set + explicit trigger (no args).
- iOS `NSLock` memo → `Mutex`/`synchronized` in-memory singleton (already the U7 builder's pattern); never DataStore/Room-backed.
- iOS `IndexSet` (markerLine window union) → Kotlin `sortedSetOf`/BitSet-equivalent union of Int ranges — overlapping mention windows must count each token once.

---

## Lens disagreements

- **Tie-break direction of the crisscross `.min` idiom at `Pilgrim/Models/Threads/DossierSensesTracks.swift:123,156@0172e2b`** — behavior lens: "tie-broken by smallest lemma"; ui-visual lens: "alphabetically-ASCENDING lemma as the tie-break"; edge-cases lens: "tie-broken toward the lexicographically LAST lemma". **Resolved during synthesis by comparator derivation**: `$0` orders before `$1` iff `($0.walks, $1.lemma) > ($1.walks, $0.lemma)` — on equal walks this reduces to `$0.lemma < $1.lemma`, so `.min` returns the max-walks candidate with the lexicographically SMALLEST lemma. The edge-cases claim is wrong; behavior + ui-visual are correct. The spec renders the resolved semantics. (Golden test: two themes tied on walks — expect the alphabetically-first lemma reported.)
- **Constants count at `Pilgrim/Models/Threads/DossierSenses.swift:11-26@0172e2b`** — data lens claim text says "15 static tuning constants"; edge-cases says "All 16 tunable thresholds". **Resolved**: both lenses' verbatim quotes list the same 16 lines; source-verified 16. The data lens's prose miscounted.
- **Bucket-table domain at `Pilgrim/Models/Threads/DossierSensesTracks.swift:371-381@0172e2b`** — edge-cases literal_value says "7 raw strings -> 6 buckets"; data lens says all 10 rawValues exhaustively covered. **Resolved**: 1+3+3+1+1+1 = 10 raw strings → 6 known buckets; the edge-cases count is wrong (likely counted case lines, not strings).
- **Coverage scope note (behavior lens, `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:87@0172e2b`)** — the behavior pass flagged that `walkSensesSnapshot`/`routeFixNear` implementations were outside its assigned file set. **Resolved**: the data lens read `DataManager+Query.swift` and this synthesis verified both implementations at source; full coverage achieved.
- **Single-lens findings surfaced for awareness (no contradiction)**: the ui-visual-only observation that cap-skip safety currently depends on moonLine's rank (`Pilgrim/Models/Threads/DossierSenses.swift:122-124@0172e2b`); the behavior-only observation of the transcription-analysis dual-path self-healing (`Pilgrim/Models/Data/DataManager+VoiceRecording.swift:105-121@0172e2b`); the data-only file-I/O dispatcher audit note (`Pilgrim/Models/Data/DataManager+VoiceRecording.swift:30-79@0172e2b`). All three are rendered in their sections above.

---

## Open questions

1. **Demo-mode / field-report mutual exclusivity — does Android need an equivalent rule?** On iOS the two DEBUG affordances are mutually exclusive by construction (`Pilgrim/AppDelegate.swift:99-111@0172e2b` early-returns before `runPostDoneLaunchTasks()`), so the field report always runs against REAL history, never seeded demo data. Android has no demo-mode launch arg; if the debug trigger for the field report can coexist with any Android demo/seed tooling, decide whether to enforce "real history only" (recommended — a report over seeded data is meaningless for the ship gate) or document it as a QA discipline.
2. **`walkSensesSnapshot` @MainActor → suspend DAO divergence.** iOS reads on the main actor (`Pilgrim/Models/Data/DataManager+Query.swift:217-218@0172e2b`) because CoreStore is main-bound; Android must NOT (Room forbids main-thread queries). The plan accepts suspend DAO. Confirm the gather step's consistency expectation still holds: iOS's main-actor gather sees one consistent store snapshot; Android's suspend gather should run its DAO reads within one coherent scope (single suspend function, ideally one transaction) so `walkSnapshots`/`recordingTimestamps` can't straddle a mid-walk-save.
3. **Field-report semantics as QA-interpretation guidance.** The report is uncapped, undeduped, and budget-blind (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:461-473@0172e2b`) — its firing-rate table intentionally over-states production frequency (no 3-line cap, no lemma suppression) and makes moonLine look near-every-walk (moonState nil). Anyone using the Android port's report to judge "does X fire too often?" must judge DEGENERATION/DEADNESS, not production rates. Should the Android report print a header line stating this caveat (an additive divergence), or stay byte-comparable with iOS output?
4. **"last 30 days" — hardcode vs derive.** iOS hardcodes the phrase (`Pilgrim/Models/Threads/DossierSensesTracks.swift:159@0172e2b`) independent of `ThreadStore.recurrenceWindow` (`Pilgrim/Models/Threads/ThreadStore.swift:30@0172e2b`). Parity says replicate the hardcode (byte-identical copy; the constant isn't even visible at format time). If Android instead derives the phrase, copy diverges the day iOS retunes either side independently. Recommendation: hardcode, plus a unit test asserting `recurrenceWindow == 30 * 86400` with a comment pointing at the string — drift then fails a test instead of lying to users. Confirm.
5. **⚠ Synodic-month constant mismatch (found during synthesis).** iOS `LunarPhase.synodicMonth = 29.53058770576` (`Pilgrim/Models/LunarPhase.swift:18@0172e2b`); Android `MoonCalc.SYNODIC_DAYS = 29.530588770576` (`core/celestial/MoonCalc.kt:36`). The senses' lunation math must use iOS's exact value for parity — but the shared-constant invariant (one source of truth, per `Pilgrim/Models/Threads/LunationCalendar.swift:3-5@0172e2b`) forbids introducing a second Android constant. Options: (a) align `MoonCalc.SYNODIC_DAYS` to `29.53058770576` and share it (recommended — also aligns MoonPhase display math with iOS; the ≈30 s shift is invisible in phase-name/illumination display), or (b) keep Android's value and accept lunation-boundary divergence from iOS near boundaries (breaks parity for moonLine). Needs user decision before U9/LunationCalendar work starts.
6. **Geodesic distance parity.** All thresholds were tuned against `CLLocation.distance(from:)` (`Pilgrim/Models/Threads/DossierSenses.swift:169-172@0172e2b`). `android.location.Location.distanceBetween` is also ellipsoidal but a different implementation; at 75/100/150 m the delta should be sub-meter — verify with a fixture pair straddling each threshold rather than assuming (edge-cases lens flagged: "should be verified, not assumed equivalent").
7. **`MarkerLexicons.absolutist` and `SpokenStoplist.scaffoldLemmas` content parity.** Both are referenced by this slice (`Pilgrim/Models/Threads/DossierSensesTracks.swift:137,193@0172e2b`) but their word lists live in files outside this read set; Android already ships both files (U1–U7). Confirm the shipped lists are at `0172e2b` parity (any ship-gate edits to the lexicons would change markerColoring/intentionLineage firing identically to a threshold change).

---

> Spec written. Run `superpowers:writing-plans <path>` to break into tasks, or `jutsu swarm doc-review <path>` for a remote QA gate first.
