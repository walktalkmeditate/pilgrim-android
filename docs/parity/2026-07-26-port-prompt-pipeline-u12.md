# Prompt Pipeline Upgrade (U12) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U12) · **Requirements:** R9
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (v1.9.0 tip, 2026-07-21). All Swift quotes cite `file@9a418e4`. Feature anchor: PR #55 (merge `f769028 feat/whisper-base-model`), which reshaped the whole `Pilgrim/Models/Prompt/` tree — `PromptAssembler.swift` (+145/-x), new `AttentionDirectives.swift`, `WalkCharacter.swift`, `PromptVoice.swift` gains `responseConstraints`, `ActivityContext.swift` gains practice/pauses/elevation, `ContextFormatter.swift` gains `formatPauses`/`formatElevation`/light-crossing metadata.
> **Authority:** SHIPPED Swift over plan docs. The pin state below is the end state and is what Android ports.
> **Android files:** modify `core/prompt/PromptAssembler.kt`, `ContextFormatter.kt`, `ActivityContext.kt`, `WalkPromptVoice.kt`, `voices/*.kt`, `PromptsCoordinator.kt`; create `core/prompt/WalkCharacter.kt`, `AttentionDirectives.kt`, `PauseContext.kt`. Tests: extend `PromptAssemblerTest`, `ContextFormatterTest`, `VoiceStringsTest`, `PromptsCoordinatorTest`; create `WalkCharacterTest`, `AttentionDirectivesTest`, `PracticeLexiconTest`, `PromptResponseContractTest` (mirroring the iOS test-file split).
> **Android templates:** `PromptAssembler.kt`'s byte-for-byte section discipline + documented-divergence regression pins; `WalkEventReplay.kt` (`walkModeFromEvents`) for practice derivation; `WalkMetricsMath` pause-pairing semantics; `AltitudeCalculator` for ascent/descent.

## P1. Template skeleton — the new assemble() order

**iOS** (`Pilgrim/Models/Prompt/PromptAssembler.swift@9a418e4`, `assemble` + section builders, verbatim):

```swift
static func assemble(context: ActivityContext, voice: PromptVoice) -> String {
    let metadata = ContextFormatter.formatMetadata(
        duration: context.duration,
        distance: context.distance,
        startDate: context.startDate,
        lunarPhase: context.lunarPhase,
        pauseDuration: context.pauses.reduce(0) { $0 + $1.duration }
    )

    var preamble = voice.preamble(hasSpeech: context.hasSpeech)
    if let characterNote = WalkCharacter.note(context: context) {
        preamble += " \(characterNote)"
    }

    var sections = "\(preamble)\n\n---\n\n**Context:** \(metadata)"
    if let weather = context.weather {
        sections += " | \(weather)"
    }
    sections += contextDossier(context: context)
    sections += walkRecord(context: context)

    var fullInstruction = voice.instruction(hasSpeech: context.hasSpeech)
    if let intention = context.intention {
        fullInstruction += " Ground your response in the walker's stated intention: '\(intention)'. Return to it. Help them see how their walk — its pace, its pauses, its moments — spoke to this purpose."
    }

    sections += "\n\n---\n\n\(fullInstruction)"
    sections += "\n\n\(responseContract(voice: voice, hasSpeech: context.hasSpeech))"
    return sections
}
```

`contextDossier` (order: celestial → **practice lexicon (unconditional)** → intention → location → pace → **pauses** → **elevation** → waypoints → photos):

```swift
private static func contextDossier(context: ActivityContext) -> String {
    var sections = ""

    if let celestial = context.celestial {
        sections += "\n\n\(ContextFormatter.formatCelestial(celestial))"
    }

    sections += "\n\n\(practiceLexicon(context: context))"

    if let intention = context.intention {
        sections += "\n\n**The walker's intention:** \"\(intention)\"\nThis intention was set deliberately before the walk began. It represents what the walker chose to carry with them. Let it be the lens through which you interpret everything below."
    }

    if let location = ContextFormatter.formatPlaceNames(context.placeNames) {
        sections += "\n\n\(location)"
    }

    if let pace = ContextFormatter.formatPaceContext(speeds: context.routeSpeeds) {
        sections += "\n\n\(pace)"
    }

    if let pauses = ContextFormatter.formatPauses(context.pauses) {
        sections += "\n\n\(pauses)"
    }

    if let elevation = ContextFormatter.formatElevation(ascent: context.ascent, descent: context.descent) {
        sections += "\n\n\(elevation)"
    }
    // … waypoints and photos blocks unchanged from the already-ported template …
```

`walkRecord` (transcription → meditations → recent walks → **Attend to** directives):

```swift
private static func walkRecord(context: ActivityContext) -> String {
    var sections = ""

    let transcription = ContextFormatter.formatRecordings(context.recordings)
    if !transcription.isEmpty {
        sections += "\n\n**Walking Transcription:**\n\n\(transcription)"
    }

    if let meditations = ContextFormatter.formatMeditations(context.meditations) {
        sections += "\n\n**Meditation Sessions:**\n\n\(meditations)"
    }

    if let recentWalks = ContextFormatter.formatRecentWalks(context.recentWalkSnippets) {
        sections += "\n\n\(recentWalks)"
    }

    let directives = AttentionDirectives.detect(context: context)
    if !directives.isEmpty {
        let bullets = directives.map { "- \($0)" }.joined(separator: "\n")
        sections += "\n\n**Attend to:**\n\(bullets)"
    }

    return sections
}
```

**Android claims:**
- The assembler's section order becomes: preamble (+ character note) · `---` · `**Context:**` metadata (weather inline) · celestial · **practice lexicon (always)** · intention prologue · location · pace · **pauses** · **elevation** · waypoints · photos · transcription · meditations · recent walks · **`**Attend to:**` bullets (gated)** · `---` · instruction (+ intention tail) · **response contract (always)**.
- `formatMetadata` receives `pauseDurationSeconds = context.pauses.sumOf { it.durationSeconds }` — the **unfiltered** pause total (sub-minute pauses count toward the wall-clock end even though `formatPauses` hides them).
- The character note is appended to the preamble with a single space, whatever the voice (built-in or custom).
- The response contract is appended after the instruction with `\n\n` (no `---` divider before it).
- Photos/waypoints/transcription/meditations/recent-walks blocks are byte-identical to the already-shipped Stage 13-XZ port; the existing divergence pins (no `Animals:`, no `Focal area:`, no `Visual narrative:`/`Color progression:`) stay in force — PR #55 did not touch the photo block, so **no newly-dropped iOS lines exist in this port**.

## P2. Standard preamble — one shared text, custom styles inherit it

**iOS** (`Pilgrim/Models/Prompt/WalkCharacter.swift@9a418e4`):

```swift
/// The one shared preamble custom styles build on. Living here — not
/// hardcoded inside CustomPromptStyle — means preamble improvements reach
/// user-authored styles automatically.
enum StandardPreamble {

    static func text(hasSpeech: Bool) -> String {
        hasSpeech
            ? "These are voice recordings captured during a walk, transcribed as spoken. They represent unfiltered thoughts, observations, and feelings that surfaced while moving."
            : "This walk was taken in silence — no words were spoken, only movement. The walker chose presence over expression, letting the body speak through pace, pauses, and the places it was drawn to."
    }
}
```

Consumers at the pin: `CustomPromptStyleStore.swift` — `func preamble(hasSpeech: Bool) -> String { StandardPreamble.text(hasSpeech: hasSpeech) }` — and `WalkPromptVoices.swift`'s ContemplativeVoice silent branch — `: StandardPreamble.text(hasSpeech: false)`.

**Android claims:**
- New `object StandardPreamble { fun text(hasSpeech: Boolean): String }` in `core/prompt/WalkCharacter.kt` (mirrors the iOS file, which hosts both types), strings verbatim.
- `ContemplativeVoice.preamble(hasSpeech = false)` delegates to `StandardPreamble.text(false)`; `CustomPromptStyleVoice.preamble` delegates to `StandardPreamble.text(hasSpeech)` for **both** branches (replacing today's borrow-from-Reflective/Contemplative indirection — strings are byte-identical, only the ownership moves to match iOS).
- `VoiceStringsTest` pins both `StandardPreamble` texts and the delegation equalities.

## P3. Walk character — one distilled preamble sentence, nil for ordinary walks

**iOS** (`Pilgrim/Models/Prompt/WalkCharacter.swift@9a418e4`, verbatim):

```swift
/// Distills what made this walk distinct — length, hour, moon, stillness —
/// into one preamble sentence, so two different walks never open with
/// identical prose. Ordinary walks yield nil; absence of remark is part of
/// the voice.
enum WalkCharacter {

    static func note(context: ActivityContext) -> String? {
        var noun = "a walk"
        var elaboration: String?
        if context.duration >= 3600 {
            noun = "a long walk"
            elaboration = " — the kind where thought thins out and something quieter takes over"
        } else if context.duration < 900 {
            noun = "a brief walk"
            elaboration = ", taken anyway — brevity is not smallness"
        }

        var timePhrase: String?
        let hour = Calendar.current.component(.hour, from: context.startDate)
        if hour >= 20 || hour < 5 {
            timePhrase = "into the night"
        } else if hour < 9 {
            timePhrase = "begun before the day claimed its shape"
        }

        var tail: [String] = []
        if context.lunarPhase.illumination >= 0.97 {
            tail.append("under a full moon")
        } else if context.lunarPhase.illumination <= 0.03 {
            tail.append("under a new moon")
        }
        if !context.meditations.isEmpty {
            tail.append("with stillness folded into it")
        }

        guard elaboration != nil || timePhrase != nil || !tail.isEmpty else { return nil }

        // The time phrase attaches to the walk noun before any em-dash
        // elaboration — the reverse order reads as garbled prose ("something
        // quieter takes over into the night").
        var sentence = "This was \(noun)"
        if let timePhrase {
            sentence += " \(timePhrase)"
        }
        if let elaboration {
            sentence += elaboration
        }
        if !tail.isEmpty {
            sentence += ", \(tail.joined(separator: ", "))"
        }
        return sentence + "."
    }
}
```

Pinned by `UnitTests/WalkCharacterTests.swift@9a418e4`: ordinary 30-min 10:00 half-moon walk → nil; 90-min 21:30 walk → exactly `"This was a long walk into the night — the kind where thought thins out and something quieter takes over."`; and `testAssembler_weavesNoteIntoEveryStyle` / `testCustomStyle_sharesStandardPreambleAndNote` prove the note reaches all six built-ins **and** customs.

**Android claims:**
- `WalkCharacter.note(context: ActivityContext, zone: ZoneId): String?` — `zone` replaces iOS's implicit `Calendar.current` (established Android pattern: every wall-clock read in `core/prompt` takes an injected zone; tests pin `America/New_York`).
- Thresholds verbatim: `durationSeconds >= 3600` long / `< 900` brief; hour `>= 20 || < 5` night / `< 9` before-day; illumination `>= 0.97` full / `<= 0.03` new; any meditations → stillness tail. Composition order: noun → time phrase → elaboration → `, `-joined tail → `.`.
- `context.lunarPhase` is nullable on Android (existing divergence); the moon tail is evaluated only when non-null. The assembler `requireNotNull`s it before this call, so behavior matches iOS in every reachable path.

## P4. Practice lexicon — wander/seek ritual grammar, always present

**iOS** (`PromptAssembler.swift@9a418e4`, verbatim — note: the lexicon is a `static func` inside PromptAssembler, **not** a separate file):

```swift
/// Teaches the downstream model the walk's ritual grammar in Pilgrim's
/// own vocabulary, so route and pace data read as practice, not as
/// fitness telemetry. Seek walks carry their story; a zero-arrival seek
/// is named, not hidden.
static func practiceLexicon(context: ActivityContext) -> String {
    switch context.mode {
    case .wander:
        return "**About this practice:** This walk was a wander — no destination, no goal; the path chose itself."
    case .seek:
        var text = "**About this practice:** This walk was a Seek. The walker surrendered the choice of destination: a seed cast hidden clearings across the map, veiled in fog, revealed only by nearness and stillness. Arriving is not achievement; it is consent to be led."
        if let story = context.seekStory {
            if story.arrivalTimes.isEmpty {
                text += " No clearing was reached this time — the seek honors this too; some walks are about the looking."
            } else if let only = story.arrivalTimes.first, story.arrivalTimes.count == 1 {
                text += " One clearing was found, reached in the \(ContextFormatter.timeOfDayDescription(only))."
            } else if let first = story.arrivalTimes.first, let last = story.arrivalTimes.last {
                text += " \(story.arrivalTimes.count) clearings were found — the first in the \(ContextFormatter.timeOfDayDescription(first)), the last in the \(ContextFormatter.timeOfDayDescription(last))."
            }
        }
        return text
    }
}
```

The practice context types (`ActivityContext.swift@9a418e4`, verbatim):

```swift
/// How the walk was undertaken — each mode carries its own ritual grammar,
/// explained to the downstream model by the practice lexicon.
enum PracticeMode {
    case wander
    case seek
}

/// What this seek held: when each clearing was reached. An empty list is a
/// zero-arrival seek, which the lexicon honors rather than hides.
struct SeekStoryContext {
    let arrivalTimes: [Date]
}

/// Pure mapping from a walk's events to its practice context, mirroring how
/// SeekSummaryModel keeps event interpretation testable outside the view. A
/// `.seekMode` event marks the walk as a seek; `.seekArrival` events carry
/// when each clearing was reached.
enum WalkPracticeModel {

    static func practice(
        events: [(type: WalkEvent.EventType, timestamp: Date)]
    ) -> (mode: PracticeMode, seekStory: SeekStoryContext?) {
        guard events.contains(where: { $0.type == .seekMode }) else {
            return (.wander, nil)
        }
        let arrivals = events
            .filter { $0.type == .seekArrival }
            .map(\.timestamp)
            .sorted()
        return (.seek, SeekStoryContext(arrivalTimes: arrivals))
    }
}
```

**Android claims:**
- `PracticeMode { Wander, Seek }`, `SeekStoryContext(arrivalTimes: List<Long>)` (epoch ms, sorted), and `WalkPracticeModel.practice(events: List<WalkEventLike>): WalkPractice` live in `core/prompt/ActivityContext.kt` (mirroring the iOS file).
- **`PracticeMode` is deliberately not `domain.WalkMode`**: WalkMode carries a third value (`Together`) the shipped iOS lexicon has no prose for; a two-value enum keeps the lexicon `when` exhaustive without inventing text. `WalkPracticeModel.practice` derives the mode via the existing `walkModeFromEvents(events)` so the prompt pipeline and the seek-summary path can never disagree about a walk's mode.
- Lexicon strings verbatim, rendered by `PromptAssembler.practiceLexicon(context, zone)` — `zone` feeds `ContextFormatter.timeOfDayDescription` for arrival hours (iOS uses `Calendar.current` implicitly).
- Wander walks (and Together, unreachable) render the wander line; every prompt — built-in or custom — carries `**About this practice:**` (pinned by `PracticeLexiconTests.testCustomStyle_carriesTheLexicon@9a418e4`).

## P5. Attention directives — deterministic pattern detection, capped at four

**iOS** (`Pilgrim/Models/Prompt/AttentionDirectives.swift@9a418e4`, verbatim in full):

```swift
/// Deterministic pattern detection over a walk's context. The assembler
/// hands the downstream model a dossier; these directives tell it what is
/// remarkable about *this* walk — the difference between handing someone
/// documents and handing them documents plus "compare page 3 to page 9".
enum AttentionDirectives {

    private static let movingThreshold = 0.3
    private static let maxDirectives = 4

    static func detect(context: ActivityContext) -> [String] {
        let directives = [
            stillness(context),
            paceShift(context),
            intentionEcho(context),
            recurringWord(context),
            firstVersusLast(context)
        ].compactMap { $0 }
        return Array(directives.prefix(maxDirectives))
    }

    /// A sustained still stretch that neither a logged meditation nor a
    /// recorded pause accounts for — otherwise the directive would re-brand
    /// the walk's own Pauses line as mystery. Sample spacing is unknown
    /// here, so minutes are estimated from the run's share of all samples —
    /// imprecise, honest enough to point at. Negative speeds are invalid
    /// GPS fixes, not stillness.
    private static func stillness(_ context: ActivityContext) -> String? {
        let speeds = context.routeSpeeds
        guard speeds.count >= 30, context.duration > 0 else { return nil }

        var longestRun = 0
        var currentRun = 0
        for speed in speeds {
            currentRun = (0..<movingThreshold).contains(speed) ? currentRun + 1 : 0
            longestRun = max(longestRun, currentRun)
        }

        let estimatedMinutes = context.duration * (Double(longestRun) / Double(speeds.count)) / 60
        let explainedMinutes = (context.meditations.reduce(0) { $0 + $1.duration }
            + context.pauses.reduce(0) { $0 + $1.duration }) / 60
        guard estimatedMinutes >= 3, estimatedMinutes > explainedMinutes else { return nil }

        return "The route shows about \(Int(estimatedMinutes.rounded())) minutes of stillness in one place — ask what held the walker there."
    }

    /// Average moving speed of the final third against the first third.
    private static func paceShift(_ context: ActivityContext) -> String? {
        let moving = context.routeSpeeds.filter { $0 >= movingThreshold }
        guard moving.count >= 30 else { return nil }

        let third = moving.count / 3
        let first = moving.prefix(third).reduce(0, +) / Double(third)
        let last = moving.suffix(third).reduce(0, +) / Double(third)
        guard first > 0 else { return nil }

        let change = (last - first) / first
        guard abs(change) >= 0.2 else { return nil }

        let percent = Int((abs(change) * 100).rounded())
        return change < 0
            ? "The walker's pace slowed by \(percent)% in the final third — something slowed them; notice what."
            : "The walker's pace quickened by \(percent)% in the final third — something carried them; notice what."
    }

    /// A word from the stated intention resurfacing in the walker's own
    /// spoken words.
    private static func intentionEcho(_ context: ActivityContext) -> String? {
        guard let intention = context.intention, context.hasSpeech else { return nil }
        let spoken = contentWords(in: context.recordings.map(\.text).joined(separator: " "))
        guard let echoed = contentWords(in: intention).first(where: { spoken.contains($0) }) else {
            return nil
        }
        return "The walker's intention spoke of '\(echoed)', and '\(echoed)' surfaces again in their spoken words — trace how it traveled."
    }

    /// The most-repeated content word across all recordings, excluding any
    /// word the intention-echo directive already claimed.
    private static func recurringWord(_ context: ActivityContext) -> String? {
        guard context.hasSpeech else { return nil }
        let intentionWords = context.intention.map { Set(contentWords(in: $0)) } ?? []

        var counts: [String: Int] = [:]
        for word in contentWords(in: context.recordings.map(\.text).joined(separator: " ")) where !intentionWords.contains(word) {
            counts[word, default: 0] += 1
        }

        guard let (word, count) = counts.filter({ $0.value >= 3 })
            .min(by: { ($0.value, $1.key) > ($1.value, $0.key) }) else { return nil }

        return "The word '\(word)' returns \(count) times across the recordings — it may be doing quiet work."
    }

    private static func firstVersusLast(_ context: ActivityContext) -> String? {
        guard context.recordings.count >= 2 else { return nil }
        return "Compare the first recording with the last — measure what changed in the walker between them."
    }

    private static let stopwords: Set<String> = [
        "the", "and", "that", "this", "with", "from", "have", "what", "your",
        "them", "they", "been", "were", "will", "would", "could", "should",
        "about", "into", "just", "like", "know", "then", "there", "when",
        "where", "which", "while", "because", "again", "back", "keep",
        "still", "very", "really", "today", "cannot", "something"
    ]

    private static func contentWords(in text: String) -> [String] {
        text.lowercased()
            .components(separatedBy: CharacterSet.letters.inverted)
            .filter { $0.count > 3 && !stopwords.contains($0) }
    }
}
```

**Android claims (semantics decoded where Swift is idiomatic):**
- Detector order **stillness → paceShift → intentionEcho → recurringWord → firstVersusLast**, non-null results kept, first 4 taken.
- Stillness: `(0..<movingThreshold).contains(speed)` ⇒ Kotlin `speed >= 0.0 && speed < 0.3` — a **negative speed breaks the run** (invalid GPS fix, not stillness). Gates: `speeds.size >= 30 && durationSeconds > 0`, `estimatedMinutes >= 3 && estimatedMinutes > explainedMinutes` where explained = (Σ meditation seconds + Σ pause seconds)/60. Rendered minutes: `estimatedMinutes.roundToInt()`.
- Pace shift: moving = speeds ≥ 0.3, needs ≥ 30 samples; `third = size / 3` (integer division); first/last third averages divided by `third` (iOS divides by `Double(third)`, i.e. the truncated count, not the slice size — identical here since prefix/suffix(third) have exactly `third` elements); fire at |change| ≥ 0.2; `percent = (abs(change) * 100).roundToInt()`.
- Intention echo: iterate the **intention's** content words in order, first one contained in the spoken content-word list wins.
- Recurring word: count spoken content words excluding the intention's word set; among counts ≥ 3, Swift's `min(by: { ($0.value, $1.key) > ($1.value, $0.key) })` decodes to **highest count, tie-broken by lexicographically smallest word** (the tuple comparator sorts descending-count then ascending-key; `min` takes the front). Kotlin: `compareByDescending { it.value } then compareBy { it.key }`, take first.
- `contentWords`: lowercase (locale-independent `String.lowercase()`), split on non-letters (`Regex("\\P{L}+")` ≡ `CharacterSet.letters.inverted`), keep words of length > 3 not in the 30-entry stopword set (verbatim).
- All directive strings verbatim; numbers are ASCII (`Int` interpolation ⇒ Kotlin plain `Int` toString).
- Android `AttentionDirectives.detect(context)` needs no zone — no wall-clock formatting occurs.

## P6. Response contract — the closing rules every prompt carries

**iOS** (`PromptAssembler.swift@9a418e4`, verbatim):

```swift
/// The closing contract every prompt carries: what the response may not
/// do (invent, flatten, switch language) plus the voice's own form
/// constraints. This shapes the *reply's* quality — the part of the
/// feature the walker actually experiences.
static func responseContract(voice: PromptVoice, hasSpeech: Bool) -> String {
    var lines = voice.responseConstraints(hasSpeech: hasSpeech)
    if hasSpeech {
        lines.append("Respond in the language the walker speaks in the transcription.")
        lines.append("If more than one voice appears in the transcription, honor it as a conversation — attend to what happened between the speakers, and never guess at names.")
    }
    lines.append("Draw only on what this walk actually holds — never invent details, events, or memories that are not in the context above.")
    let bullets = lines.map { "- \($0)" }.joined(separator: "\n")
    return "**How to respond:**\n\(bullets)"
}
```

The protocol hook (`PromptVoice.swift@9a418e4`, verbatim):

```swift
protocol PromptVoice {
    func preamble(hasSpeech: Bool) -> String
    func instruction(hasSpeech: Bool) -> String
    /// Voice-specific output constraints for the downstream model, rendered
    /// into the prompt's closing "How to respond" contract alongside the
    /// shared lines every style carries.
    func responseConstraints(hasSpeech: Bool) -> [String]
}

extension PromptVoice {
    func responseConstraints(hasSpeech: Bool) -> [String] { [] }
}
```

Per-voice constraints (`WalkPromptVoices.swift@9a418e4`, all verbatim):

- **Contemplative:** `"Write in unhurried prose — no bullet points, no headings."` · `"Ask at most one question, and let it be one worth carrying."` · `"Do not summarize the walk back to the walker; they were there."`
- **Reflective:** `"Offer observations, not advice; name patterns tentatively rather than diagnosing."` · `"Avoid therapy clichés — write in connected prose, not lists."`
- **Creative:** `"Reply with the piece itself — no introduction, no explanation of your choices."` · `"Let the walk's rhythm shape the form; brevity is welcome."`
- **Gratitude:** `"Root every thanksgiving in something specific from this walk — no generic blessings."` · `"Warm but plain language, in prose; never saccharine."`
- **Philosophical:** `"Invoke thinkers or traditions only when they genuinely illuminate — never name-drop."` · `"Write as a letter from a thoughtful friend, not a lecture, and end with one question that opens rather than closes."`
- **Journaling** (the only hasSpeech-dependent set):

```swift
func responseConstraints(hasSpeech: Bool) -> [String] {
    [
        hasSpeech
            ? "Write the entry in the walker's own first-person voice, keeping their phrasing where it lives."
            : "Keep the entry in second person, as a witness would write it.",
        "No meta-commentary about what you are doing — only the entry itself, ready to be reread years from now."
    ]
}
```

Custom styles (`CustomPromptStyleStore.swift@9a418e4`) do **not** override `responseConstraints` — they get the protocol default `[]`, so a custom prompt's contract is exactly the shared lines (language + multi-voice when spoken, anti-fabrication always). Pinned by `PromptResponseContractTests.testCustomStyle_carriesSharedContract@9a418e4`.

**Android claims:**
- `WalkPromptVoice` gains `fun responseConstraints(hasSpeech: Boolean): List<String> = emptyList()` (interface default ≡ Swift protocol extension).
- Each of the six voice objects overrides it with the verbatim strings above; `CustomPromptStyleVoice` does not override.
- `PromptAssembler.responseContract(voice, hasSpeech)` internal for direct test access; rendered as `**How to respond:**` + `- `-bulleted lines, `\n`-joined; ordering: voice lines → speech-gated pair → anti-fabrication line.

## P7. Body data — pauses and elevation lines

**iOS** (`ContextFormatter.swift@9a418e4`, verbatim):

```swift
/// Pauses under a minute are breath, not story — surfacing "0 min"
/// entries would invite the downstream model to interpret a non-event.
static func formatPauses(_ pauses: [PauseContext]) -> String? {
    let meaningful = pauses.filter { $0.duration >= 60 }
    guard !meaningful.isEmpty,
          let longest = meaningful.max(by: { $0.duration < $1.duration }) else { return nil }
    let totalMinutes = Int((meaningful.reduce(0) { $0 + $1.duration } / 60).rounded())
    let longestMinutes = Int((longest.duration / 60).rounded())
    let when = timeFormatter.string(from: longest.startDate)
    return "**Pauses:** Paused \(meaningful.count) time\(meaningful.count == 1 ? "" : "s") (\(totalMinutes) min total); the longest, \(longestMinutes) min, began at \(when)."
}

static func formatElevation(ascent: Double?, descent: Double?) -> String? {
    let up = ascent ?? 0
    let down = descent ?? 0
    guard up > 10 || down > 10 else { return nil }
    let imperial = UserPreferences.distanceMeasurementType.safeValue == .miles
    func formatted(_ meters: Double) -> String {
        imperial ? "\(Int((meters * 3.28084).rounded())) ft" : "\(Int(meters.rounded())) m"
    }
    var parts: [String] = []
    if up > 10 { parts.append("climbed \(formatted(up))") }
    if down > 10 { parts.append("descended \(formatted(down))") }
    return "**Elevation:** \(parts.joined(separator: ", "))."
}
```

`PauseContext` (`PromptContextTypes.swift@9a418e4`): `struct PauseContext { let startDate: Date; let duration: TimeInterval }`.

**Android claims:**
- `PauseContext(startDate: Long /* epoch ms */, durationSeconds: Long)` — new `core/prompt/PauseContext.kt`, `@Immutable`, matching `MeditationContext`'s field conventions.
- `formatPauses(pauses, zone)`: filter `durationSeconds >= 60`; longest via **last-max-wins** on ties (Swift `max(by:)` returns the last maximal element; Kotlin `maxByOrNull` returns the first — Android iterates with `>=` to preserve iOS tie behavior); totals/longest as `(seconds / 60.0).roundToInt()`; `began at` uses the shared short time formatter with the injected zone; singular/plural `time`/`times`; trailing period.
- `formatElevation(ascent, descent, imperial)`: `imperial` is an **injected parameter** (iOS reads the `UserPreferences` global; Android's `core/prompt` stays preference-free per the Stage 13-Cel pattern — the coordinator passes `UnitsPreferences.distanceUnits`). Gate `> 10` meters per side; `ft = (meters * 3.28084).roundToInt()`, `m = meters.roundToInt()`; `climbed …, descended ….` with trailing period; both-nil or ≤ 10 both sides → null.

## P8. Light crossings — pure formatting over startDate + duration + pauseDuration

**CRITICAL: no new sensor exists.** The "light crossing" is `formatMetadata` comparing the time-of-day bucket at the walk's start against the bucket at `start + duration + pauseDuration`. **iOS** (`ContextFormatter.swift@9a418e4`, verbatim — quoted in full to prove it):

```swift
/// `duration` is active walking time; `pauseDuration` restores the
/// wall-clock end so a paused walk doesn't claim to have ended in a
/// time of day it never saw.
static func formatMetadata(duration: Double, distance: Double, startDate: Date, lunarPhase: LunarPhase? = nil, pauseDuration: Double = 0) -> String {
    let durationMin = Int(duration / 60)
    let distanceStr = distanceFormatter.string(from: Measurement(value: distance, unit: UnitLength.meters))
    let startDescription = timeOfDayDescription(startDate)
    let endDescription = timeOfDayDescription(startDate.addingTimeInterval(duration + pauseDuration))
    let timePhrase = startDescription == endDescription
        ? "\(startDescription) on \(dateTimeFormatter.string(from: startDate))"
        : "began in the \(startDescription), ended in the \(endDescription), on \(dateTimeFormatter.string(from: startDate))"

    let lunar = lunarPhase ?? LunarPhase.current(date: startDate)
    return "Walk duration: \(durationMin) minutes | Distance: \(distanceStr) | Time: \(timePhrase) | Moon: \(lunar.name) (\(Int(round(lunar.illumination * 100)))% illumination)"
}
```

The only inputs are `startDate`, `duration` (active seconds), and `pauseDuration` (seconds) — the end bucket is arithmetic over the walk's own clock, run through the pre-existing six-bucket `timeOfDayDescription`. `BodyDataContextTests@9a418e4` pins: 17:30 + 3h → `"began in the evening"` … `"ended in the night"`; 17:30 + 2h active + 1h paused → also `"ended in the night"`; 30-min morning walk → `"morning on"` with **no** `"began in the"`.

**Android claims:**
- `formatMetadata` gains `pauseDurationSeconds: Long = 0L`; end bucket at `startTimestamp + (durationSeconds + pauseDurationSeconds) * 1000`; same-bucket walks keep the existing `"$timeOfDay on $dateTime"` phrase (zero diff for every already-pinned prompt), crossing walks render `"began in the X, ended in the Y, on $dateTime"`.
- Android keeps `lunarPhase` as a required non-null parameter (existing divergence — Android has no `LunarPhase.current` fallback in the formatter; the coordinator always computes it).
- `durationSeconds` fed to the assembler becomes **active duration** (see P9) so `duration + pauseDuration` reconstructs the wall clock exactly as iOS's `walk.activeDuration` + pause sum does.

## P9. Feeding the context — where pauses, elevation, and practice come from

**iOS** (`Pilgrim/Scenes/Prompts/PromptListView.swift@9a418e4`, `buildActivityContext`, relevant lines):

```swift
return ActivityContext(
    …
    duration: walk.activeDuration,
    …
    mode: practice.mode,
    seekStory: practice.seekStory,
    pauses: walk.pauses.map {
        PauseContext(startDate: $0.startDate, duration: $0.endDate.timeIntervalSince($0.startDate))
    },
    ascent: walk.ascend,
    descent: walk.descend
)

private var practice: (mode: PracticeMode, seekStory: SeekStoryContext?) {
    WalkPracticeModel.practice(events: walk.workoutEvents.map { ($0.eventType, $0.timestamp) })
}
```

**Android claims** (`PromptsCoordinator.buildContext`):
- **Pauses:** Android has no pause entity — pauses are `PAUSED`/`RESUMED` event pairs. Derivation follows `WalkMetricsMath.computeActiveDurationSeconds`'s pairing semantics exactly (first PAUSED opens, RESUMED closes, unpaired trailing PAUSED closes at `walk.endTimestamp`, unmatched RESUMED ignored, negative spans clamped to 0), emitted as `PauseContext(startDate = pausedAtMs, durationSeconds = spanMs / 1000)`.
- **Duration:** `durationSeconds` switches from wall clock to **active duration** via the shared `WalkMetricsMath.computeActiveDurationSeconds(walk, events)` — this is the parity fix that makes `Walk duration: N minutes` show walking time (iOS `walk.activeDuration`) and lets P8's end-bucket arithmetic restore the wall clock.
- **Practice:** `WalkPracticeModel.practice(events)` over the same fetched event list.
- **Elevation:** `AltitudeCalculator.computeAscentDescent(repository.altitudeSamplesFor(walkId))` — the same source the `.pilgrim` exporter uses for `PilgrimStats.ascent`/`descent` (iOS `walk.ascend`/`descend` analogue). Empty/short sample lists yield `0.0 to 0.0`, which P7's `> 10` gate hides.
- The two new fetches (`walkEventsFor`, `altitudeSamplesFor`) join the existing parallel `async` fan-out.

## P10. Divergence table

| # | iOS @ 9a418e4 | Android | Why |
|---|---|---|---|
| D1 | `WalkCharacter`/`practiceLexicon`/`formatPauses` read `Calendar.current` / default-locale formatters | explicit `zone: ZoneId` parameters | Established `core/prompt` pattern (pure functions, deterministic tests); output identical for the device's zone |
| D2 | `formatElevation` reads `UserPreferences.distanceMeasurementType` global | `imperial: Boolean` injected | Stage 13-Cel divergence, already in force for pace/distance |
| D3 | `PracticeMode` is a standalone two-case enum | same — deliberately **not** reusing `domain.WalkMode` (three values incl. `Together`) | keeps the lexicon `when` exhaustive without inventing Together prose; derivation still routes through `walkModeFromEvents` |
| D4 | pauses come from CoreData `walk.pauses` rows | derived from `PAUSED`/`RESUMED` events via `WalkMetricsMath` pairing semantics | Android never had a pause entity; the event log is the single source the active-duration math already trusts |
| D5 | `Swift max(by:)` returns the **last** maximal pause on duration ties | Android iterates with `>=` to match | documented so nobody "fixes" it to `maxByOrNull` (first-wins) later |
| D6 | photo block: `Animals:`, `Focal area:`, `Visual narrative:`/`Color progression:` lines | still dropped (regression-pinned) | pre-existing Stage 13-XZ divergences; PR #55 didn't touch the photo block — no new dropped lines |
| D7 | `ActivityContext.lunarPhase` non-optional; `formatMetadata` has a `LunarPhase.current` fallback | nullable + `requireNotNull` in assembler; no formatter fallback | pre-existing Android contract |
| D8 | `RecordingContext` has no `uuid` | Android keeps its `uuid` field | pre-existing; not rendered into the prompt |

## P11. Test matrix (iOS file → Android file)

| iOS @ 9a418e4 | Android | Coverage |
|---|---|---|
| `WalkCharacterTests.swift` | `WalkCharacterTest.kt` (new) | ordinary→null; long-night **exact sentence**; brief; full/new moon; stillness tail; note woven into all six voices + custom (via assembler) |
| `PracticeLexiconTests.swift` | `PracticeLexiconTest.kt` (new) | wander line; seek surrender; 1-arrival hour; 2-arrival first/last hours; zero-arrival honor; `WalkPracticeModel` wander/sorted-arrivals/empty-story; custom carries lexicon |
| `AttentionDirectivesTests.swift` | `AttentionDirectivesTest.kt` (new) | pace slow/uniform; stillness fires / meditation-explained / pause-explained / negative-speed; intention echo hit/miss; recurring ≥3 / unique; first-vs-last 2/1; cap ≤ 4; assembler gates `**Attend to:**` |
| `PromptResponseContractTests.swift` | `PromptResponseContractTest.kt` (new) | contract on every style incl. custom; anti-fabrication on silent; language/multi-voice lines speech-gated; contemplative/creative voice lines; **exact-string block pin** |
| `BodyDataContextTests.swift` | `ContextFormatterTest.kt` + `PromptAssemblerTest.kt` extensions | formatPauses nil/counts/sub-minute; formatElevation nil/climb (+ imperial + exact strings); metadata evening→night, paused wall-clock end, single-bucket unchanged; assembler includes/omits body sections |
| `PromptGeneratorTests.swift` (reshaped) | `PromptAssemblerTest.kt` updated | exact-string minimal-walk pin re-baselined with lexicon + contract; section-order test extended with the four new markers |
| — | `VoiceStringsTest.kt` extended | `StandardPreamble` pins; per-voice `responseConstraints` pins; Contemplative-silent ≡ StandardPreamble |
| — | `PromptsCoordinatorTest.kt` extended | pauses from events (incl. trailing unpaired PAUSED); active-duration switch; seek mode + sorted arrivals; ascent/descent from altitude samples |
