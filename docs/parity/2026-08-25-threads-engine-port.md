# Parity Spec: threads-engine

| field | value |
|---|---|
| **iOS pin** | `0172e2b` (main, per CLAUDE.md) |
| **Android HEAD** | `07f1ec47` |
| **Generated** | 2026-08-25 |
| **Type** | port |
| **Generator** | ios-parity skill |

**Finding provenance.** 292 lens findings across four lenses — behavior (87), ui-visual (44), data (65), edge-cases (96). Entries are tagged `BEH-n` / `UI-n` / `DAT-n` / `EDG-n` by their order in the lens arrays. Entries tagged **[consensus: …]** were independently produced by 2+ lenses against the same citation or near-identical claim; where lenses reported the same fact, ONE entry carries the strongest quote and every distinct nuance. Nothing substantive was excluded.

**Quote convention.** Quotes are verbatim from the pinned iOS source. Inside markdown tables a line break in the original is rendered as `⏎` and a literal `|` is escaped as `\|`; nothing else is altered. One deliberate exception: two quotes elide a frozen legacy relationship-column identifier per the no-legacy-reference policy (marked `⟨frozen legacy column⟩` — see the single caution in the Data section).

**Android divergence (planned)** callouts mark the places where the Android plan deliberately replaces an iOS mechanism (WordNet/Morphy substrate, synset `related()`, VADER-lite sentiment, ML Kit language-id, offset-unit pinning). The spec records BOTH what iOS does and what Android will do differently.

---

## iOS source map

- `Pilgrim/Models/Threads/TranscriptNLP.swift` — 169 LOC — NLP primitives: single word tokenizer, lemma-mention extraction with offsets, language detection, embedding-based `related()`, the two spoken-stoplists.
- `Pilgrim/Models/Threads/ThemeExtractor.swift` — 70 LOC — Noun-only theme extraction: floors/caps, walking-domain suppression, display-term and salience-ranked selection.
- `Pilgrim/Models/Threads/MarkerLexicons.swift` — 73 LOC — Verbatim linguistic-marker word lists (absolutist, first-person, insight, causation, discrepancy, future/past) plus the six ordered modal families.
- `Pilgrim/Models/Threads/MarkerAnalyzer.swift` — 127 LOC — English-only `MarkerPack` computation: lexicon counts, temporal lean, per-sentence-averaged sentiment; lenient decode.
- `Pilgrim/Models/Threads/TranscriptContext.swift` — 34 LOC — The derived, file-persisted per-recording analysis record; `currentSchemaVersion = 4` discriminant with documented bump history.
- `Pilgrim/Models/Threads/TranscriptContextStore.swift` — 181 LOC — One-JSON-file-per-recording store: serial write queue, in-memory tombstones, changeCount heartbeat, schema-filtered loads, backup exclusion.
- `Pilgrim/Models/Threads/TranscriptContextAnalyzer.swift` — 72 LOC — Orchestrates analyze-and-store: themes from full transcript, markers from hallucination-scrubbed text, flagged-range computation.
- `Pilgrim/Models/Threads/ThreadsBackfill.swift` — 192 LOC — One-time historical sweep: versioned completed-flag, battery/pref gate re-checks per 25-batch, generation counter, legacy-key hygiene.
- `Pilgrim/Models/Threads/ThreadStore.swift` — 98 LOC — Pure in-memory thread aggregation: appearances, first-time/recurring status, 30-day window, salience direction.
- `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift` — 480 LOC — Builds the AI-prompt dossier: six-field memo key, own-write changeCount accounting, orphan pruning, hash-matched self-heal, senses bundle split.
- `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift` — 282 LOC — Renders the dossier text: marker lines, thread lines, modal-lean clause, quiet/absence section — all literal LLM-facing templates.
- `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift` — 84 LOC — "Recurring" intention chips: field gate, 30-day/2-walk qualification, sort→template→dedup→cap pipeline, (changeCount, day) memo.
- `Pilgrim/Models/BatteryGate.swift` — 18 LOC — The single battery formula gating auto-transcription and backfill (unknown-allows, >20%, charging/full).
- `Pilgrim/Models/TranscriptionService.swift` — 534 LOC — Whisper transcription service: 5-state machine, flagged-fragment ASR-quality signal, 2-attempt persistence, model path/variant keys, skip-reason flag.
- `Pilgrim/Models/Data/DataManager+VoiceRecording.swift` — 310 LOC — Recording write path (the Threads-analysis trigger seam), four narrow snapshot queries, `rowUUID` decode helper, file cleanup helpers.
- `Pilgrim/Models/Data/DataManager.swift` — 873 LOC — CoreStore facade: walk save/delete/delete-all with tombstone ordering, ten-entity wipe, moon-key/registry clearing.
- `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift` — 500 LOC — `.pilgrim` archive import: decode off-main, success-gated tombstone clear + backfill reset, archived-walk filtering, heavy-data stripping.
- `Pilgrim/Models/Preferences/UserPreferences.swift` — 184 LOC — UserDefaults-backed preferences incl. `threadsAfterWalks` (default true), `autoTranscribe` (default false), archived-walk registry with serial-queue helpers.
- `Pilgrim/Models/Prompt/AttentionDirectives.swift` — 146 LOC — Five walk-shape detectors (stillness, pace shift, intention echo, recurring word, first-vs-last) with fixed priority order and a cap of 4.
- `Pilgrim/Models/Prompt/PromptAssembler.swift` — 235 LOC — Assembles the LLM prompt: dossier placement, response contract incl. the threads-gated safety line, language naming, practice lexicon.
- `Pilgrim/Models/Prompt/PromptContextTypes.swift` — 67 LOC — Prompt context value types incl. `RecordingContext` with optional `recordingUUID`.
- `Pilgrim/Models/PromptGenerator.swift` — 92 LOC — Per-style prompt generation with shared one-shot derivations (language + directives); word-boundary truncation helper.
- `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift` — 434 LOC — Intention sheet: recording/transcribing/input branches, three suggestion tiers incl. the Recurring chips, custom FlowLayout.
- `Pilgrim/Scenes/Root/MainCoordinatorView.swift` — 208 LOC — App-level wiring: backfill trigger on init, auto-transcription trigger, the skip-flag's five clear-sites, recovery banner.
- `Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift` — 123 LOC — Settings Voice card: Thought Threads toggle routed through `ThreadsBackfill.setEnabled`, model-download progress row.
- `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift` — 248 LOC — Walk-summary recordings list: transcription status/skip banners, transcribe/retry affordances, single-file delete, manual transcript edit seam.

---

## Behavior

### NLP primitives — `Pilgrim/Models/Threads/TranscriptNLP.swift`

- **[BEH-1 + EDG-5 — consensus] Length floor on candidate mentions.** `contentLemmaMentions` only keeps a word whose surface form is longer than 2 characters:
  ```swift
  guard let tag, classes.contains(tag) else { return true }
              let surface = String(text[range]).lowercased()
              guard surface.count > 2 else { return true }
  ```
  > `Pilgrim/Models/Threads/TranscriptNLP.swift:51-53@0172e2b` — a port without the length-3 floor (or using `>= 2`) lets 1–2-letter words leak into theme/marker candidates and shifts downstream counts and salience.

- **[EDG-3] The default `classes` parameter is load-bearing.** `contentLemmaMentions(in:classes: Set<NLTag> = [.noun, .verb, .adjective])` defaults to the full content set; ThemeExtractor overrides to `[.noun]` only. > `Pilgrim/Models/Threads/TranscriptNLP.swift:27-35@0172e2b` — inlining one fixed tag-class set for all callers breaks either theme extraction (nouns-only) or intention-echo/recurring-word (verbs too).

- **[EDG-6] Lemma fallback.** Lemma resolution reads `.0` off `NLTagger.tag(at:unit:scheme:)` and falls back to the surface form: `let lemma = tagger.tag(at: range.lowerBound, unit: .word, scheme: .lemma).0?.rawValue.lowercased() ?? surface`. > `Pilgrim/Models/Threads/TranscriptNLP.swift:54-55@0172e2b` — treating a null lemma as skip/empty instead of falling back to the lowercased surface silently drops mentions instead of counting them under their own surface text.

- **[BEH-2 + EDG-1 — consensus] Relatedness ceiling.** `static let relatedDistanceCeiling = 0.95` is the fixed cosine-distance ceiling for embedding-based word relatedness, shared by intention-echo's third match tier. > `Pilgrim/Models/Threads/TranscriptNLP.swift:9@0172e2b`.
  > **Android divergence (planned):** `related()` is replaced by WordNet synset-based relatedness — the 0.95 cosine ceiling does NOT transfer to that substrate and must not be copied as a literal.

- **[EDG-10 + BEH-3 — consensus] Exact-match short-circuit + locked embedding cache.** `related()` returns true on string equality before touching the embedding; `NLEmbedding` loads are cached per language behind an `NSLock` because `related()` runs inside loops:
  ```swift
  static func related(_ a: String, _ b: String, languageCode: String) -> Bool {
      if a == b { return true }
      guard let embedding = embedding(for: languageCode),
            embedding.contains(a), embedding.contains(b) else { return false }
      return embedding.distance(between: a, and: b, distanceType: .cosine) <= relatedDistanceCeiling
  }

  /// NLEmbedding loads are expensive and related() runs inside loops.
  private static var embeddingCache: [String: NLEmbedding] = [:]
  private static let embeddingLock = NSLock()
  ```
  > `Pilgrim/Models/Threads/TranscriptNLP.swift:115-133@0172e2b` — an Android cache needs an explicit thread-safe map (Mutex/ConcurrentHashMap): a plain mutable Map races under concurrent AttentionDirectives calls or reloads the dictionary per call; the exact-match short-circuit must survive whatever replaces the embedding.

- **[EDG-2] Language-detection confidence floor.** Detection requires `NLLanguageRecognizer` `confidence >= 0.5 else { return nil }` (nil = no language). > `Pilgrim/Models/Threads/TranscriptNLP.swift:23@0172e2b`.
  > **Android divergence (planned):** ML Kit language-id at ≥ 0.5 — the plan reuses the 0.5 number, but ML Kit's confidence scale differs from NLLanguageRecognizer's; the en-only marker gate's firing rate depends on this floor.

- **[EDG-7 + DAT-10 — consensus] The single tokenizer.** Every word count and density in the feature flows through one tokenizer; a second implementation is explicitly forbidden:
  ```swift
  /// The single tokenizer for every word count and density in the feature
  /// — a second implementation means diverging denominators.
  static func wordTokens(in text: String) -> [String] {
      text.lowercased()
          .components(separatedBy: CharacterSet.letters.inverted)
          .filter { !$0.isEmpty }
  }

  static func wordCount(in text: String) -> Int {
      wordTokens(in: text).count
  }
  ```
  > `Pilgrim/Models/Threads/TranscriptNLP.swift:73-83@0172e2b` — Android must route ThemeExtractor's 25-word floor, MarkerAnalyzer densities, and the formatter's `densityFloorWords` through ONE equivalent function; any ad hoc `split()` diverges the denominators inside absolutist %, self-focus %, and salience.

- **[EDG-8] Offsets come from the tokenizer's own output.** `wordTokenOffsets` locates each of `wordTokens`' tokens by forward search — deliberately not a second tokenizer: "A separate letters-only scan (even a careful one) can still diverge from `components(separatedBy:)` on a grapheme that mixes scalar classes". > `Pilgrim/Models/Threads/TranscriptNLP.swift:90-99@0172e2b` — two independent passes (regex Matcher + manual split) can disagree on a combining-mark boundary, producing offsets that point at the wrong substring.

- **[EDG-9] Cursor advances past each match.** `cursor = range.upperBound` after every token match so repeated identical tokens resolve to successive occurrences:
  ```swift
  for token in wordTokens(in: text) {
      guard let range = lowered.range(of: token, range: cursor..<lowered.endIndex) else { continue }
      tokens.append(WordToken(
          token: token,
          start: lowered.distance(from: lowered.startIndex, to: range.lowerBound)
      ))
      cursor = range.upperBound
  }
  ```
  > `Pilgrim/Models/Threads/TranscriptNLP.swift:104-111@0172e2b` — a Kotlin `indexOf(token)` without a forward cursor re-finds the FIRST occurrence of a repeated word every time, misaligning offsets for any transcript with repeated words (the common case).

- **[EDG-4] Running-cursor mention offsets, and the offset UNIT.** Mention offsets accumulate `text.distance(from: lastIndex, to: range.lowerBound)` from the previous mention ("linear instead of re-measuring from startIndex per mention (quadratic on long transcripts)"), and Swift's `distance` counts **extended grapheme clusters**, not UTF-16 code units. > `Pilgrim/Models/Threads/TranscriptNLP.swift:39-63@0172e2b`.
  > **Android divergence (planned):** Kotlin String indexing is UTF-16 — the plan requires picking and PINNING one unit consistently for transcript hash + mention offsets + flagged-range containment; mixing units corrupts every stored `Theme.mentions` start/length on transcripts containing emoji or combining marks.

- **[BEH-4 + DAT-6 + EDG-12/EDG-13 — consensus] Two DIFFERENT stoplists for two different consumers.** `SpokenStoplist.lightNouns` (noun-only THEME extraction) and `SpokenStoplist.scaffoldLemmas` (verb-inclusive recurring-word DIRECTIVE) must stay separate — collapsing them over- or under-suppresses depending on the caller. Both verbatim as of the 2026-08-25 ship gate (lightNouns gained `day`, `days`, `area`):
  ```swift
  static let lightNouns: Set<String> = [
      "thing", "things", "stuff", "kind", "sort", "lot", "bit", "way", "ways",
      "one", "ones", "something", "anything", "everything", "nothing",
      "day", "days", "area"
  ]
  ```
  ```swift
  static let scaffoldLemmas: Set<String> = [
      "be", "have", "do", "get", "go", "come", "make", "take", "know",
      "think", "say", "see", "want", "mean", "feel", "need", "let", "put",
      "keep", "kind", "thing", "stuff", "way", "lot", "bit",
      "can", "could", "should", "would", "must", "might", "may", "will", "ought", "wish"
  ]
  ```
  > `Pilgrim/Models/Threads/TranscriptNLP.swift:140-168@0172e2b` — lightNouns is 18 entries (one lens summary miscounted 17; the verbatim quote all lenses agree on has 18), scaffoldLemmas is 35. scaffoldLemmas deliberately stoplists every modal verb from theme naming because modals belong in the `MarkerPack.modalCounts` channel instead — the port must keep that routing split.

- **[EDG-11] Why the stoplists exist.** "Spoken-English scaffolding NLTagger tags as content words even though a speaker reaches for them out of habit, not meaning — a field-confirmed bug (\"was / have / can / think\" as real-device themes) traced to exactly this gap between lexical class and topical content." > `Pilgrim/Models/Threads/TranscriptNLP.swift:136-139@0172e2b` — without both lists verbatim, Android reproduces the exact shipped bug iOS already fixed.
  > **Android divergence (planned):** the Android theme path uses WordNet + Morphy dictionary POS (not contextual tagging), so the plan makes the `scaffoldLemmas` filter MANDATORY on the THEME path as Android-original compensation — iOS filters themes only through `walkingDomain` + `lightNouns` because NLTagger's contextual noun-tagging already excludes most scaffolding.

### Theme extraction — `Pilgrim/Models/Threads/ThemeExtractor.swift`

- **[BEH-5 + DAT-5 + EDG-14/15/16 — consensus] The three gates.** A 25-word transcript floor, a 6-theme cap, and a 2-mention floor per lemma:
  ```swift
  static let minimumWords = 25
      static let maxThemes = 6
      static let minimumMentions = 2
  ```
  > `Pilgrim/Models/Threads/ThemeExtractor.swift:19-21,31-33,43-44@0172e2b` — `guard wordCount >= minimumWords else { return [] }` (line 33) means a short voice note silently produces ZERO themes, never a degenerate one; `.filter { $0.value.count >= minimumMentions }` (line 44) means a single passing mention never becomes a theme. Any of the three drifting changes which walks get any dossier text at all.

- **[BEH-6 + DAT-5 + EDG-17 — consensus] Walking-domain suppression.** The activity's own narration vocabulary is removed from theme candidates — "without suppression every walk's dominant thread would be the walk itself":
  ```swift
  static let walkingDomain: Set<String> = [
      "walk", "walking", "path", "trail", "hill", "uphill", "downhill",
      "road", "street", "step", "steps", "route", "mile", "kilometer",
      "minute", "left", "right"
  ]
  ```
  > `Pilgrim/Models/Threads/ThemeExtractor.swift:25-29@0172e2b` — 17 entries, verbatim.

- **[EDG-18] Thread identity is exact-lemma in v1 — deliberately.** "Per-transcript synonym merging split cross-walk identity — a lemma folded into a neighbor in one walk read \"first time\" in the next, a false origin claim." > `Pilgrim/Models/Threads/ThemeExtractor.swift:35-41@0172e2b` — an Android port that "improves" grouping with embedding/synset clustering per transcript reintroduces the exact false-origin-claim bug iOS shipped and reverted.

- **[BEH-7 + EDG-19 — consensus] Display-term selection: max count, tie-broken by smallest surface string.** Via the tuple-swap comparator and a force-unwrap:
  ```swift
  let display = surfaceCounts
                  .min { ($0.value, $1.key) > ($1.value, $0.key) }!.key
  ```
  > `Pilgrim/Models/Threads/ThemeExtractor.swift:46-49@0172e2b` — the swap idiom sorts descending by count, ASCENDING by key on ties; a naive `maxByOrNull` on count alone leaves ties to HashMap iteration order (non-deterministic display word across runs). The `!` is safe only because the caller's `minimumMentions >= 2` filter guarantees a non-empty group — the Kotlin port must preserve that invariant or the equivalent call throws on empty.

- **[BEH-8, same EDG-19 family] Final ranking.** Themes sort by (salience desc, lemma asc) before truncation:
  ```swift
  .sorted { ($0.salience, $1.lemma) > ($1.salience, $0.lemma) }
              .prefix(maxThemes)
              .map { $0 }
  ```
  > `Pilgrim/Models/Threads/ThemeExtractor.swift:58-60@0172e2b` — `sortedByDescending` on salience alone leaves ties in undefined order, changing which themes are cut at the 6-cap. [EDG-20] The trailing `.map { $0 }` is Swift ArraySlice→Array ceremony only — Kotlin `.take(maxThemes)` already returns a List; no equivalent step needed.

### Linguistic markers — `Pilgrim/Models/Threads/MarkerLexicons.swift`, `Pilgrim/Models/Threads/MarkerAnalyzer.swift`

- **[BEH-9 + EDG-21 — consensus] Seven license-sensitive lexicons, verbatim or nothing.** The absolutist set is the exact 19-word Al-Mosaiwi & Johnstone 2018 dictionary (Table 1, open access), with a hard legal caveat: "LIWC's proprietary word lists must never be copied here."
  ```swift
  static let absolutist: Set<String> = [
      "absolutely", "all", "always", "complete", "completely", "constant",
      "constantly", "definitely", "entire", "ever", "every", "everyone",
      "everything", "full", "must", "never", "nothing", "totally", "whole"
  ]
  ```
  > `Pilgrim/Models/Threads/MarkerLexicons.swift:3-13@0172e2b` — all seven lexicons below must be copied verbatim into Android; any "equivalent" word list changes marker counts for identical transcripts, and the LIWC prohibition is a licensing constraint, not style.

- **[EDG-22] firstPersonSingular (5):** `static let firstPersonSingular: Set<String> = ["i", "me", "my", "mine", "myself"]` > `Pilgrim/Models/Threads/MarkerLexicons.swift:15@0172e2b` — forgetting `mine` under-counts self-focus %.

- **[EDG-23] insight (20), verbatim:**
  ```swift
  static let insight: Set<String> = [
      "realize", "realized", "realizing", "understand", "understood",
      "understanding", "notice", "noticed", "noticing", "aware", "awareness",
      "clarity", "insight", "learn", "learned", "learning", "recognize",
      "recognized", "sense", "sensed"
  ]
  ```
  > `Pilgrim/Models/Threads/MarkerLexicons.swift:17-22@0172e2b`.

- **[EDG-24] causation (14), verbatim:**
  ```swift
  static let causation: Set<String> = [
      "because", "cause", "caused", "causes", "effect", "hence", "since",
      "therefore", "thus", "reason", "reasons", "why", "consequently", "led"
  ]
  ```
  > `Pilgrim/Models/Threads/MarkerLexicons.swift:24-27@0172e2b`.

- **[EDG-25] discrepancy (14), verbatim:**
  ```swift
  static let discrepancy: Set<String> = [
      "should", "would", "could", "ought", "need", "needed", "want",
      "wanted", "wish", "wished", "hope", "hoped", "rather", "instead"
  ]
  ```
  > `Pilgrim/Models/Threads/MarkerLexicons.swift:29-32@0172e2b` — seven of these also appear in `modalFamilies`; see EDG-30 below.

- **[EDG-26] futureMarkers (12), verbatim:**
  ```swift
  static let futureMarkers: Set<String> = [
      "will", "shall", "gonna", "tomorrow", "soon", "later", "ahead",
      "upcoming", "future", "plan", "plans", "planning"
  ]
  ```
  > `Pilgrim/Models/Threads/MarkerLexicons.swift:34-37@0172e2b` — `"will"` double-counts into both `futureCount` and `modalCounts["will"]` (intention family) by design.

- **[EDG-27] pastMarkers (12), verbatim — and there is NO present-tense list anywhere:**
  ```swift
  static let pastMarkers: Set<String> = [
      "was", "were", "did", "had", "ago", "yesterday", "remember",
      "remembered", "used", "back", "once", "before"
  ]
  ```
  > `Pilgrim/Models/Threads/MarkerLexicons.swift:39-42@0172e2b` — a port that invents a third "present" lexicon (assuming symmetry) adds a signal iOS never computes.

- **[BEH-10 + EDG-28/29 — consensus] Six ORDERED modal families.** Arrays, not Sets — "so a dominant-word tie always resolves to the same word" (doc comment, lines 48-49); the enum's `CaseIterable` declaration order is equally load-bearing:
  ```swift
  enum ModalFamily: String, CaseIterable {
      case possibility, obligation, counterfactual, tentative, intention, desire
  }

  static let modalFamilies: [ModalFamily: [String]] = [
      .possibility: ["can", "could"],
      .obligation: ["should", "must", "ought"],
      .counterfactual: ["would"],
      .tentative: ["might", "may"],
      .intention: ["will"],
      .desire: ["want", "need", "wish"]
  ]
  ```
  > `Pilgrim/Models/Threads/MarkerLexicons.swift:53-71@0172e2b` — Kotlin enum `.entries` preserves declaration order, so this ports cleanly IF Android uses an ordered enum + ordered `List`; a Map/Set reimplementation loses the deterministic-tie guarantee `ThreadsDossierFormatter` depends on.

- **[EDG-30] Cross-lexicon double-counting is deliberate.** should/must/ought/would/want/need/wish appear in BOTH discrepancy and modalFamilies (`"can"` does not appear in discrepancy); discrepancy's inflected forms (needed/wanted/wished) are modal-exempt since modalFamilies is single-token/uninflected only. > `Pilgrim/Models/Threads/MarkerLexicons.swift:57-64@0172e2b` — "deduplicating" overlapping words across channels silently shrinks `discrepancyCount`/`futureCount` vs iOS.

- **[EDG-31] One-family invariant.** `modalWords` is `Set(modalFamilies.values.flatMap { $0 })` and `modalFamily(of:)` does a linear dictionary scan (`modalFamilies.first { $0.value.contains(word) }?.key`) relying on every word belonging to exactly one family — Swift Dictionary iteration order is otherwise unspecified. > `Pilgrim/Models/Threads/MarkerLexicons.swift:66-71@0172e2b` — adding a word to two families makes the lookup iteration-order-dependent.

- **[BEH-11 + DAT-8 + EDG-32 — consensus] Lenient decode of `modalCounts`.** A missing key on disk (pre-v3 file) decodes to an empty dict rather than failing the whole decode:
  ```swift
  init(from decoder: Decoder) throws {
      let container = try decoder.container(keyedBy: CodingKeys.self)
      wordCount = try container.decode(Int.self, forKey: .wordCount)
      ...
      sentiment = try container.decodeIfPresent(Double.self, forKey: .sentiment)
      modalCounts = try container.decodeIfPresent([String: Int].self, forKey: .modalCounts) ?? [:]
  }
  ```
  > `Pilgrim/Models/Threads/MarkerAnalyzer.swift:37-56@0172e2b` — a hard decode failure would hide old-schema files from the stale-orphan sweep (which must decode every version to clean it up). Android's serializer must default missing/newer fields, not throw — a strict kotlinx.serialization data class throws on pre-existing records the moment a field is added. (Encodable/CodingKeys are compiler-synthesized; only the decode path is hand-written.)

- **[BEH-12 + EDG-34 — consensus] English-only gate, exact string equality.** `guard languageCode == "en" else { return nil }` — every other language (including nil) yields nil markers and the pipeline degrades to themes-only. > `Pilgrim/Models/Threads/MarkerAnalyzer.swift:69-72@0172e2b` — an Android detector emitting `"en-US"`/`"en-GB"` variants would make a naively-ported exact-equality gate never fire; conversely, running markers on those variants produces data iOS never computes. `markers` is legitimately nil for whole recordings — model it fully-optional, never default-zeroed.

- **[EDG-33] temporalLean needs BOTH floors.** `if futureCount >= 3, futureCount >= pastCount * 2 { return "future" }` / `if pastCount >= 3, pastCount >= futureCount * 2 { return "past" }`, else `"balanced"`. > `Pilgrim/Models/Threads/MarkerAnalyzer.swift:61-62@0172e2b` — ratio-only would flip a 2-future/0-past transcript to "future" on Android but not iOS.

- **[BEH-13 + DAT-9 + EDG-37 — consensus] Sentiment is per-SENTENCE, averaged — a workaround for a measured NLTagger bug.** "`NLTagger`'s sentiment model degrades to a near-constant score once the tagged string passes roughly 150 words" (confirmed by direct measurement; three different transcripts all converged on −0.60 when tagged as one string). Scoring each sentence in isolation (fresh `.string` assignment per sentence) and averaging keeps the score content-sensitive at any length:
  ```swift
  tokenizer.enumerateTokens(in: text.startIndex..<text.endIndex) { range, _ in
              let sentence = String(text[range])
              guard !sentence.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return true }
              tagger.string = sentence
  ```
  > `Pilgrim/Models/Threads/MarkerAnalyzer.swift:98-126@0172e2b`.
  > **Android divergence (planned):** VADER-lite sentiment, numeric non-parity accepted. The ~150-word chunking constant is NLTagger-specific — do not copy it; decide chunking (or none) against VADER's own behavior. The stored `sentiment: Double?` field's meaning should stay roughly comparable across history (see Open questions).

- **[EDG-35] Count idiom.** Per-lexicon counting is `words.reduce(0) { $0 + (lexicon.contains($1) ? 1 : 0) }` — behaviorally `words.count { lexicon.contains(it) }` in Kotlin; a mis-translated ternary inverts the count. > `Pilgrim/Models/Threads/MarkerAnalyzer.swift:76@0172e2b`.

- **[EDG-36] Increment idiom.** `modalCounts[word, default: 0] += 1` — Kotlin has no subscript-with-default sugar; `map[word] = map[word]!! + 1` throws on first occurrence; use `getOrDefault`/`merge`. > `Pilgrim/Models/Threads/MarkerAnalyzer.swift:81@0172e2b`.

### Derived context: model, store, analyzer — `TranscriptContext.swift`, `TranscriptContextStore.swift`, `TranscriptContextAnalyzer.swift`

- **[BEH-14 + DAT-2 + EDG-38 — consensus] `currentSchemaVersion = 4` is the single freshness discriminant.** "Bump whenever a change to HOW context is derived (extractor filters, marker rules, etc.) makes existing stored files semantically stale — not just when the Codable shape changes." Version history: v2 baseline, v3 added `MarkerPack.modalCounts`, v4 (ship gate 2026-08-25) tightened `SpokenStoplist.lightNouns` (`day`/`days`/`area`). Three visibility rules every reader shares: stale-version files are invisible to `loadAll`, still visible to the stale-inclusive load, and treated as absent by `hasCurrentContext`. > `Pilgrim/Models/Threads/TranscriptContext.swift:6-25@0172e2b` — Android keeps its OWN independent version counter (no schema import per project convention) but must replicate all three visibility rules; losing any one either resurfaces junk themes forever or forgets to re-analyze after a lexicon change.

- **[BEH-15 + DAT-23 — consensus] Tombstones are in-memory only.** `private var tombstones: Set<UUID> = []` — a plain in-process Set, never persisted; `insertTombstones`/`clearTombstones` mutate it under the write queue with no FileManager calls. > `Pilgrim/Models/Threads/TranscriptContextStore.swift:15-17,107-111,129-133@0172e2b` — they protect only against late writes within the SAME process lifetime (the `Task.detached` analysis race is process-bounded on iOS). If Android runs the equivalent analysis under WorkManager (which survives process death), the tombstone set must be persisted to keep the guarantee; if analysis stays in-process coroutines, in-memory matches iOS (see Open questions).

- **[BEH-16 + DAT-16 — consensus] Single-writer serialization of every mutation.** save, delete, insertTombstones, removeContext, clearTombstones, deleteAll all run through one private serial queue via `.sync`: `private let writeQueue = DispatchQueue(label: "org.walktalkmeditate.pilgrim.transcript-contexts")`. The store performs NO dispatch off the caller's thread — callers are responsible for being off-Main. > `Pilgrim/Models/Threads/TranscriptContextStore.swift:16,42-54@0172e2b` — Android needs an equivalent single-writer discipline (Mutex or confined dispatcher); unsynchronized coroutine I/O could interleave a tombstone-check with a concurrent write. [EDG-45] Note the asymmetry: read paths (`load`, `context(for:matching:)`, `hasContext`, `hasCurrentContext`, `loadAll`) touch the filesystem directly with NO serialization against writes (`Pilgrim/Models/Threads/TranscriptContextStore.swift:166-169@0172e2b`) — `.atomic` writes are what prevent torn reads. Port decision recorded in Open questions.

- **[BEH-17 + DAT-15 + EDG-41 — consensus] `save()` reports true when tombstone-blocked.** "Returns true when the context is accounted for — written to disk, or" deliberately blocked; only genuine encode/write failure returns false:
  ```swift
  guard !tombstones.contains(context.recordingUUID) else { return true }
              guard let data = try? JSONEncoder().encode(context) else { return false }
              do {
                  try data.write(to: fileURL(for: context.recordingUUID), options: .atomic)
              } catch {
                  return false
              }
              _changeCount += 1
              return true
  ```
  > `Pilgrim/Models/Threads/TranscriptContextStore.swift:38-54@0172e2b` — ThreadsBackfill and ThreadsDossierBuilder rely on "tombstone-blocked = success"; a seemingly more honest `false` makes the backfill's completed-flag accounting retry forever.

- **[BEH-18 + DAT-22 — consensus] `changeCount` increments exactly ONCE per write-call, not per UUID.**
  ```swift
  func delete(recordingUUIDs: [UUID]) {
      writeQueue.sync {
          for uuid in recordingUUIDs {
              tombstones.insert(uuid)
              try? FileManager.default.removeItem(at: fileURL(for: uuid))
          }
          _changeCount += 1
      }
  }
  ```
  > `Pilgrim/Models/Threads/TranscriptContextStore.swift:93-101@0172e2b` — the monotonic counter is the cache-invalidation heartbeat for ThreadsDossierBuilder AND ThreadIntentionSuggestions, and the builder's memo math (`ownDeleteWrite + freshlySaved.count`) assumes one increment per `delete()` call regardless of batch size. A per-UUID increment still invalidates but breaks the exact arithmetic (see Open questions).

- **[BEH-19] Two existence checks, two callers.** `hasContext` (bare file existence) vs `hasCurrentContext` (existence AND `schemaVersion == currentSchemaVersion`):
  ```swift
  func hasContext(for recordingUUID: UUID) -> Bool {
      FileManager.default.fileExists(atPath: fileURL(for: recordingUUID).path)
  }

  func hasCurrentContext(for recordingUUID: UUID) -> Bool {
      load(recordingUUID: recordingUUID)?.schemaVersion == TranscriptContext.currentSchemaVersion
  }
  ```
  > `Pilgrim/Models/Threads/TranscriptContextStore.swift:63-74@0172e2b` — collapsing them breaks either the self-heal/deletion-assertion callers (need existence) or the backfill sweep (needs freshness).

- **[BEH-20 + DAT-24 + EDG-42 — consensus] Three removal primitives, three semantics.** `delete()` tombstones AND removes the file (real recording deletion); `insertTombstones()` tombstones WITHOUT removing files (paired with a later `deleteAll()` sweep); `removeContext()` removes the file WITHOUT tombstoning:
  ```swift
  /// Removes a stored context without tombstoning — for edits made while
  /// the feature is off, where the old analysis must not linger stale. A
  /// tombstone here would block the future backfill save for this
  /// recording and corrupt its accounting (a blocked save reports
  /// "accounted" while writing nothing).
  func removeContext(for recordingUUID: UUID) {
      writeQueue.sync {
          try? FileManager.default.removeItem(at: fileURL(for: recordingUUID))
          _changeCount += 1
      }
  }
  ```
  > `Pilgrim/Models/Threads/TranscriptContextStore.swift:93-123@0172e2b` — collapsing these into one delete helper either permanently blocks re-analysis after a feature-off edit (over-tombstoning) or lets a late-finishing analysis resurrect data during a bulk delete (under-tombstoning).

- **[BEH-21 + DAT-45 + EDG-44 — consensus] Backup exclusion is applied at init AND re-applied after `deleteAll()` recreates the directory.**
  ```swift
  init(directory: URL) {
      self.directory = directory
      try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      excludeFromBackup()
  }
  ```
  > `Pilgrim/Models/Threads/TranscriptContextStore.swift:22-26,141-150,175-180@0172e2b` — directory removal + recreation resets the resource-value exclusion, so `deleteAll()` ends with `excludeFromBackup()` again. [EDG-44] The `var url = directory; try? url.setResourceValues(values)` idiom looks like a no-op local mutation but persists `isExcludedFromBackup` to the on-disk resource. Android has no per-directory runtime API — the equivalent is the static `data_extraction_rules.xml` / `fullBackupContent` declarations (see Android implementation notes); the exclusion must cover wherever the Android store actually lives or derived psychological data leaks into backups.

- **[BEH-22 + DAT-41 — consensus] Two-track analysis text.** `analyze()` extracts themes from the FULL transcript (mention offsets stay valid for excerpt display), then drops any theme whose EVERY mention falls inside an ASR-flagged fragment — while markers run over a SEPARATELY scrubbed text with fragments blanked:
  ```swift
  let themes = ThemeExtractor.themes(in: transcript, languageCode: language)
              .filter { theme in
                  flaggedRanges.isEmpty || theme.mentions.contains { mention in
                      !flaggedRanges.contains { $0.contains(mention.start) }
                  }
              }

          let analysisText = flaggedFragments.reduce(transcript) {
              $0.replacingOccurrences(of: $1, with: " ")
          }
  ```
  > `Pilgrim/Models/Threads/TranscriptContextAnalyzer.swift:9-36@0172e2b` — "a hallucinated fragment can echo a real theme but never be the only evidence for one." Scrubbing before theme extraction, or reusing one string for both analyzers, silently diverges on any walk with hallucination flags. [EDG-48] The filter is a double negative — kept if AT LEAST ONE mention falls outside every flagged range; flipping the inner negation (or using `allSatisfy`) makes the filter far more lenient. [EDG-49] `replacingOccurrences` scrubs ALL occurrences of a fragment's text — including legitimate instances elsewhere that happen to match — so a range-based excision port produces different marker counts.

- **[BEH-23 + DAT-42 + EDG-47 — consensus] Every occurrence of a flagged fragment, not just the first.** "Every occurrence, not just the first — repeated hallucination is the canonical Whisper failure shape."
  ```swift
  for fragment in fragments where !fragment.isEmpty {
              var searchStart = text.startIndex
              while let range = text.range(of: fragment, range: searchStart..<text.endIndex) {
  ```
  > `Pilgrim/Models/Threads/TranscriptContextAnalyzer.swift:58-71@0172e2b` — a single-`indexOf` port under-flags repeated hallucination, leaving later repeats unprotected. [EDG-46] `let start = text.distance(from: text.startIndex, to: range.lowerBound)` counts grapheme clusters — the same offset-unit pinning requirement as the mention offsets applies here, or the mention-vs-flagged-range containment check misaligns.

### Transcription trigger, flags, banner plumbing — `TranscriptionService.swift`, `DataManager+VoiceRecording.swift`, `MainCoordinatorView.swift`

- **[BEH-55 + UI-42 — consensus] Five-state machine with value-carrying equality.** `TranscriptionService.State` has exactly five cases — `idle`, `downloadingModel(progress: Double)`, `transcribing(current: Int, total: Int)`, `completed`, `failed(String)` — and a custom `==` comparing associated values, not just case identity:
  ```swift
  case (.downloadingModel(let a), .downloadingModel(let b)): return a == b
              case (.transcribing(let a1, let a2), .transcribing(let b1, let b2)): return a1 == b1 && a2 == b2
  ```
  > `Pilgrim/Models/TranscriptionService.swift:71-87@0172e2b` — a Kotlin sealed class gets structural equality free IF progress/current/total are real data fields on the state, not a bare enum discarding them. [UI-42] `.idle` and `.completed` are the two states no surface in this slice renders — intentionally silent everywhere.

- **[BEH-56 + DAT-40 + EDG-52 — consensus] The ASR-quality signal: `flaggedFragments`.** Segments with `compressionRatio > 2.4 || noSpeechProb > 0.6` are flagged; WhisperKit 0.16 hardcodes `noSpeechProb` to 0 (upstream TODO, TextDecoder.swift:993), so compressionRatio is the only effective signal today:
  ```swift
  private static func flaggedFragments(from results: [TranscriptionResult]) -> [String] {
      results.flatMap { $0.segments }
          .filter { $0.compressionRatio > 2.4 || $0.noSpeechProb > 0.6 }
          .map { TranscriptionService.cleanTranscription($0.text) }
  }
  ```
  > `Pilgrim/Models/TranscriptionService.swift:52-64@0172e2b` — cross-file seam: if the Android whisper.cpp binding exposes no per-segment compression_ratio/no_speech_prob equivalent, `flaggedFragments` is permanently `[]` and the analyzer's hallucination suppression becomes a no-op — a real parity gap, not a naming risk. And if whisper.cpp DOES populate a real no_speech_prob, the ported 0.6 branch does live work iOS never exercises. Both recorded in Open questions. [DAT-40] `TranscriptionOutput` (`text: String`, `wordsPerMinute: Double?`, `flaggedFragments: [String]`) is deliberately engine-independent so tests can fake the engine — "Empty for every engine that doesn't produce segment-level quality signals" (`Pilgrim/Models/TranscriptionService.swift:6-14@0172e2b`).

- **[BEH-57] Fragments are cleaned by the SAME helper as the persisted transcript.** `.map { TranscriptionService.cleanTranscription($0.text) }` — deliberately, so a fragment's cleaned text can later be found as a substring inside the persisted transcript. > `Pilgrim/Models/TranscriptionService.swift:58-64,453-461@0172e2b` — a different (or absent) cleaning step for fragments breaks `characterRanges`' substring search entirely: silently zero matches, so no themes are ever suppressed even when fragments are correctly flagged.

- **[BEH-58 + EDG-54 — consensus] Persistence retries exactly once — two total attempts, no backoff.**
  ```swift
  private func persistTranscription(uuid: UUID, text: String, flaggedFragments: [String]) async -> Bool {
      if await persistTranscriptionOnce(uuid: uuid, text: text, flaggedFragments: flaggedFragments) { return true }
      if await persistTranscriptionOnce(uuid: uuid, text: text, flaggedFragments: flaggedFragments) { return true }
      print("[TranscriptionService] Transcription for \(uuid) not saved after retry")
      return false
  }
  ```
  > `Pilgrim/Models/TranscriptionService.swift:417-422@0172e2b` — WPM persistence separately retries via a hardcoded `for _ in 0..<2` (also exactly 2 attempts, line 418-419 family). This function's completion gates whether the Threads-analysis `Task.detached` branch fires at all — Android must trigger analysis on the same two-attempts-then-give-up semantics, not an unbounded WorkManager retry that fires analysis long after the summary UI expects.

- **[EDG-56] Batch failure semantics.** `batchState` reports `.failed` only when EVERY attempted transcription failed (`attempted > 0 && transcriptionFailures == attempted`) OR any persistence failure occurred; only a fully clean (or empty) batch is `.completed`. > `Pilgrim/Models/TranscriptionService.swift:350-353@0172e2b` — `transcriptionFailures > 0` instead of `== attempted` would show the retry prompt on any partial failure instead of only total failure.

- **[BEH-59 + DAT-32 — consensus] THE write-path branch: one function, three UI entry points.** `updateVoiceRecordingTranscription` is the single CoreStore write path for transcript text shared by batch transcription, single retranscribe, AND manual transcript-edit. On success it branches on the preference — feature on: detached analysis; feature off: remove the stored context:
  ```swift
  updateVoiceRecording(uuid: uuid, dataStack: dataStack, completion: { success in
              if success {
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
              }
              completion?(success)
          }
  ```
  > `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:97-126@0172e2b` — "An edit saved while the feature is off must not leave the pre-toggle analysis behind to go stale." Any entry point bypassing this seam (e.g. manual edit) would leave stale derived data for a later re-enable. [DAT-32] "The trigger stays local to this function — the shared helper below also serves WPM/isEnhanced updates, which must never analyze" (`Pilgrim/Models/Data/DataManager+VoiceRecording.swift:81-126@0172e2b`): `updateVoiceRecordingWordsPerMinute`/`updateVoiceRecordingIsEnhanced` route through the same private helper (lines 260-309) but deliberately do NOT trigger analysis — a generalized "update field" repository method with one shared post-write hook needs an explicit opt-out for those callers. [DAT-19] Dispatcher asymmetry inside this very function: the ON branch defers disk I/O off Main via `Task.detached`, but the OFF branch calls `removeContext` inline — which runs on Main because CoreStore completions land on the main queue (`Pilgrim/Models/Data/DataManager+VoiceRecording.swift:105-121@0172e2b`). A line-by-line port copies this bug-for-bug; decision recorded in Open questions. [EDG-95] The shared completion collapses transaction-failed and row-no-longer-exists into one `false` — "callers must not treat either case as \"saved\"" (`Pilgrim/Models/Data/DataManager+VoiceRecording.swift:81-84,289-307@0172e2b`); a sealed-Result Android port must still collapse both to don't-analyze at this call site.

- **[BEH-60] Analysis is detached so it never delays model unload.** `Task.detached(priority: .utility) { TranscriptContextAnalyzer.analyzeAndStore(` — off the transcription loop by design. > `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:107-115@0172e2b` — launching analysis inside the same coroutine/scope as the transcription batch serializes behind it, delaying `unloadModel()` and the release of tens of MB of model memory.

- **[BEH-86] Manual transcript edits pass NO flaggedFragments.** The summary edit UI calls the same write path with the parameter defaulted to empty — a hand-edited transcript is trusted verbatim:
  ```swift
  onTranscriptionSave: { newText in
                  guard let uuid = recording.uuid else { return }
                  transcriptions[uuid] = newText
                  DataManager.updateVoiceRecordingTranscription(uuid: uuid, transcription: newText)
              },
  ```
  > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:89-93@0172e2b` — Android must actively pass an empty list on manual re-analysis; threading stale ASR fragments into an edited transcript would suppress theme mentions that no longer apply.

- **[BEH-82 + UI-16 — consensus] Auto-transcription trigger: independent preference, synchronous battery read, guarded flag set.** `triggerAutoTranscription` gates on `UserPreferences.autoTranscribe` — a DIFFERENT, independently toggleable preference from `threadsAfterWalks` — and non-empty recordings, then reads the battery gate synchronously; only if both prior guards passed AND battery fails is the skip reason set (otherwise the flag is left untouched):
  ```swift
  private func triggerAutoTranscription(for snapshot: TempWalk) {
      guard UserPreferences.autoTranscribe.value,
            !snapshot.voiceRecordings.isEmpty else { return }

      let batteryOK = MainActor.assumeIsolated { BatteryGate.allowsBackgroundWork() }

      if batteryOK {
          Task {
              ...
          }
      } else {
          Task { @MainActor in
              TranscriptionService.shared.autoTranscriptionSkippedReason = .lowBattery
          }
      }
  }
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:157-176@0172e2b` — a walk with autoTranscribe OFF never reaches TranscriptionService automatically; its Threads analysis only ever happens via manual transcribe or the backfill sweep. Never conflate the two preferences. Checking only "was battery low" without the two prior guards shows a false-positive banner on walks where auto-transcription was never going to run.

- **[UI-12/13/14/15 + BEH cluster] The skip-flag has FIVE clear-sites — their placement is the behavior.** `autoTranscriptionSkippedReason` is a `@MainActor @Published` singleton-scoped property; the sites:
  1. `startWalk()` clears unconditionally: `Task { @MainActor in TranscriptionService.shared.autoTranscriptionSkippedReason = nil }` > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:62@0172e2b` [UI-12]
  2. `cancelWalk()` clears unconditionally (same line shape) > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:99@0172e2b` [UI-13]
  3. `handleActiveWalkDismiss()` clears ONLY on the no-pending-snapshot branch — deliberately left set on the success path so it can still surface on the walk summary reached via seal-reveal:
     ```swift
     func handleActiveWalkDismiss() {
         if let snapshot = pendingSnapshot {
             pendingSnapshot = nil
             sealRevealWalk = snapshot
             showSealReveal = true
         } else {
             Task { @MainActor in TranscriptionService.shared.autoTranscriptionSkippedReason = nil }
         }
     }
     ```
     > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:102-109@0172e2b` [UI-14] — clearing unconditionally here erases the banner before the summary screen ever shows it.
  4. `handleSummaryDismiss()` clears unconditionally, paired with the home walk-list reload: `Task { @MainActor in ... = nil }; homeViewModel.loadWalks()` > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:140-143@0172e2b` [UI-15]
  5. `transcribeAll()` on the summary screen clears only when at least one transcription succeeded — see UI-25 below.
  Consolidating these into fewer lifecycle hooks lets the low-battery banner leak from one walk into an unrelated later walk's summary, or erases it prematurely.

- **[UI-25] Manual retry clears the banner only on non-empty results.**
  ```swift
  private func transcribeAll() async {
      let untranscribed = walk.voiceRecordings.filter { recording in
          guard let uuid = recording.uuid else { return false }
          return transcriptions[uuid] == nil && isFileAvailable(recording.fileRelativePath)
      }
      let results = await transcriptionService.transcribeRecordings(untranscribed)
      for (uuid, text) in results {
          transcriptions[uuid] = text
      }
      if !results.isEmpty {
          transcriptionService.autoTranscriptionSkippedReason = nil
      }
  }
  ```
  > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:221-233@0172e2b` — "clear whenever transcribeAll is invoked" would hide the banner even after an all-failed manual retry, masking that the skip condition (or a fresh failure) is unresolved.

- **[BEH-83] Recovery banner: 4-second auto-dismiss with cancel-before-reschedule.**
  ```swift
  self.bannerDismissWork?.cancel()
                  let work = DispatchWorkItem { [weak self] in
                      self?.recoveredWalkDate = nil
                  }
                  self.bannerDismissWork = work
                  DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: work)
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:35-40@0172e2b` — reproduce the literal 4 s and the cancel-before-reschedule pattern, or a stale dismiss fires after a second recovery event re-shows the banner.

### Battery gate — `Pilgrim/Models/BatteryGate.swift`

- **[BEH-53 + EDG-50 — consensus] One formula; unknown means ALLOW; strictly greater than 20%.**
  ```swift
  let level = device.batteryLevel
          let batteryState = device.batteryState
          device.isBatteryMonitoringEnabled = wasMonitoring
          return level < 0 || level > 0.2 || batteryState == .charging || batteryState == .full
  ```
  > `Pilgrim/Models/BatteryGate.swift:9-16@0172e2b` — background work is blocked only when the level is known (`>= 0`) AND at-or-below 20% AND not charging/full. The boundary is exclusive (`> 0.2`, not `>=`): exactly 20% blocks. A negative (unknown) level ALLOWS — Android's BatteryManager equivalent needs the same polarity for its own error/unavailable sentinel, or unreliable-battery devices/emulators silently lose ALL background transcription/backfill. The 20% threshold must be a single shared constant, not duplicated as a literal in UI copy (see UI-24 and Open questions).

- **[BEH-54 + EDG-51 — consensus] The read is stateful on iOS — and should NOT be transliterated.** `allowsBackgroundWork()` saves `isBatteryMonitoringEnabled`, force-enables monitoring long enough to read level/state, then restores the prior flag (`let wasMonitoring = device.isBatteryMonitoringEnabled ... device.isBatteryMonitoringEnabled = wasMonitoring`). > `Pilgrim/Models/BatteryGate.swift:9-15@0172e2b` — Android's `BatteryManager` needs no enable/restore toggle for reads; port the native primitive, and do not leave any registration/receiver equivalent permanently on (the scoping exists to avoid a battery-cost regression).

### Backfill — `Pilgrim/Models/Threads/ThreadsBackfill.swift`

- **[BEH-25 + DAT-50/DAT-51 + EDG-58 — consensus] The versioned completed-flag and its full legacy chain, verbatim.** `static let completedKey = "threadsBackfillCompletedV6"` — the sixth rename; each rename re-arms the one-time sweep unconditionally because the new key is absent regardless of what any old key holds. The superseded keys, all removed on every call:
  ```swift
  private static let legacyCompletedKeyV3 = "threadsBackfillCompletedV3"
  private static let legacyCompletedKeyV4 = "threadsBackfillCompletedV4"
  private static let legacyCompletedKeyV5 = "threadsBackfillCompletedV5"
  private static let legacyCompletedKeys = [
      "threadsBackfillCompleted", "threadsBackfillCompletedV2",
      legacyCompletedKeyV3, legacyCompletedKeyV4, legacyCompletedKeyV5
  ]
  ```
  > `Pilgrim/Models/Threads/ThreadsBackfill.swift:8-40@0172e2b` — [EDG-59] the doc comment (lines 9-33) records why each V2→V6 bump re-arms; "V6 (ship gate, 2026-08-25) adds `day`, `days`, `area` to `SpokenStoplist.lightNouns` (schema v3→v4) … like V4→V5, no moon-line re-arm accompanies it; `performLegacyHygiene` still only watches the V3 key." Android's flag must independently version whenever ITS OWN extractor/lexicon logic changes — the version numbers do not correspond cross-platform; the BEHAVIOR to port is rename-removes-old-keys-and-re-arms, not the literal "V6".

- **[BEH-26 + DAT-38 + EDG-61 — consensus] Legacy hygiene: capture-before-delete, V3-conditional moon-line clear.**
  ```swift
  private static func performLegacyHygiene() {
      let hadV3Key = UserDefaults.standard.object(forKey: legacyCompletedKeyV3) != nil
      legacyCompletedKeys.forEach { UserDefaults.standard.removeObject(forKey: $0) }
      guard hadV3Key else { return }
      UserDefaults.standard.removeObject(forKey: ThreadsDossierBuilder.moonLineDefaultsKey)
  }
  ```
  > `Pilgrim/Models/Threads/ThreadsBackfill.swift:75-88@0172e2b` — runs on every call BEFORE the isComplete check; the V3-presence boolean must be captured before the blanket removal (check-after-delete always reads false). This migration exists only because iOS shipped a specific buggy V3 extractor to real devices — Android never shipped it, so a literal port is dead code reacting to a key that will never exist: mark N/A for fresh Android installs and keep only the key-versioning discipline (see Open questions).

- **[BEH-24 + DAT-36 — consensus] `reset()` carries a main-queue runtime precondition.**
  ```swift
  static func reset() {
      dispatchPrecondition(condition: .onQueue(.main))
      generation += 1
      UserDefaults.standard.set(false, forKey: completedKey)
  }
  ```
  > `Pilgrim/Models/Threads/ThreadsBackfill.swift:48-59@0172e2b` — the doc comment is first-party confirmation that "Importer completions land on the main queue (CoreStore's default), the only place the counter is touched"; the precondition CRASHES a DEBUG build if violated. Kotlin has no built-in analogue — dropping it silently lets a future background-coroutine caller race the generation/isRunning flags with no signal until a corrupted sweep; the port must either preserve single-threaded confinement for the generation counter or explicitly redesign it.

- **[BEH-27] `setEnabled` defers the resweep past the caller's UI transaction.**
  ```swift
  @MainActor
      static func setEnabled(_ enabled: Bool) {
          UserPreferences.threadsAfterWalks.value = enabled
          guard enabled else { return }
          reset()
          Task { @MainActor in runIfNeeded() }
      }
  ```
  > `Pilgrim/Models/Threads/ThreadsBackfill.swift:65-71@0172e2b` — the resweep is scheduled as a NEW Task rather than called inline so it lands after the settings-toggle UI transaction commits; an inline Android call would run the DB snapshot inside the toggle recomposition/animation.

- **[BEH-31] One guard chain for both callers.** `runIfNeeded` is the exact same function called from app-launch restoration (MainCoordinator.init) and the user toggle (setEnabled) — not two code paths:
  ```swift
  @MainActor
      static func runIfNeeded(
          store: TranscriptContextStore = .shared,
          snapshotProvider: @escaping @MainActor () -> [(uuid: UUID, transcript: String)] = {
              DataManager.transcribedRecordingsSnapshot()
          },
          gate: @escaping @MainActor () -> Bool = { BatteryGate.allowsBackgroundWork() },
          onFinish: (@MainActor () -> Void)? = nil
      ) {
  ```
  > `Pilgrim/Models/Threads/ThreadsBackfill.swift:135-149@0172e2b` — giving the settings-toggle call site different guard logic than the launch call site could let one skip the single-flight `isRunning` check the other relies on.

- **[BEH-28 + DAT-37 + EDG-60 — consensus] Gate re-checked before EVERY batch of 25 — not once at sweep start.**
  ```swift
  static let batchSize = 25
  ...
              while batchStart < items.count {
                  guard await MainActor.run(body: { gate() && UserPreferences.threadsAfterWalks.value }) else {
                      gateClosed = true
                      break
                  }
  ```
  > `Pilgrim/Models/Threads/ThreadsBackfill.swift:73,157-163@0172e2b` — a mid-sweep battery drop or toggle-off stops the sweep cleanly; a check-once port keeps grinding through a multi-thousand-recording backfill after the phone drops below 20% or the user disables the feature. Only items failing `hasCurrentContext` are analyzed within each batch (`for item in batch where !store.hasCurrentContext(for: item.uuid)`, DAT-37).

- **[BEH-29] Cooperative yield, not a delay.** `batchStart += batchSize` then `await Task.yield()` after every batch. > `Pilgrim/Models/Threads/ThreadsBackfill.swift:174-175@0172e2b` — Kotlin's `yield()` is the direct analogue; omitting it in a tight loop on Dispatchers.Default can starve sibling coroutines.

- **[BEH-30 + DAT-39 — consensus] Completion needs THREE conditions; stale sweeps self-correct with one more pass.**
  ```swift
  await MainActor.run {
      let stale = generation != startGeneration
      if !stale && allAccounted && !gateClosed {
          UserDefaults.standard.set(true, forKey: completedKey)
      }
      isRunning = false
      if stale {
          Task { @MainActor in
              runIfNeeded(store: store, snapshotProvider: snapshotProvider, gate: gate, onFinish: onFinish)
          }
      }
      onFinish?()
  }
  ```
  > `Pilgrim/Models/Threads/ThreadsBackfill.swift:177-190@0172e2b` — generation unchanged AND every item accounted for AND gate never closed; any single failure leaves the flag false for retry. Checking fewer (e.g. only allAccounted) lets a stale/interrupted sweep falsely mark complete, permanently corrupting downstream "first appearance" origin claims. [DAT-39] `generation` is a plain in-memory `static var` (line 42) — meaningless across process boundaries; a WorkManager-based port needs an explicit persisted epoch alongside the completed flag to keep the same stale-detection guarantee across process death.

- **[DAT-20] The sweep's dispatcher shape is the house pattern.** Main-actor CoreStore snapshot first (`let startGeneration = generation; let items = snapshotProvider()`), then `Task.detached(priority: .utility)` for `pruneStaleOrphans` + the whole analyze/save loop. > `Pilgrim/Models/Threads/ThreadsBackfill.swift:151-176@0172e2b` — preserve the shape on Android: read the Room snapshot on the calling dispatcher, then all file work under `Dispatchers.IO`.

- **[DAT-28] Stale-orphan pruning touches ONLY stale-schema orphans.**
  ```swift
  private static func pruneStaleOrphans(store: TranscriptContextStore, liveUUIDs: Set<UUID>) {
      guard !liveUUIDs.isEmpty else { return }
      let staleOrphans = store.loadAllIncludingStaleVersions()
          .filter { $0.schemaVersion != TranscriptContext.currentSchemaVersion && !liveUUIDs.contains($0.recordingUUID) }
          .map(\.recordingUUID)
      guard !staleOrphans.isEmpty else { return }
      store.delete(recordingUUIDs: staleOrphans)
  }
  ```
  > `Pilgrim/Models/Threads/ThreadsBackfill.swift:90-112@0172e2b` — current-schema orphans are left for `ThreadsDossierBuilder.build`'s own cleanup; the `guard !liveUUIDs.isEmpty` mirrors the builder's empty-index defense (an empty snapshot is treated as a possibly-failed read, never proof of universal orphanhood).

- **[BEH-81] The restoration-path trigger for the whole slice.** `MainCoordinator.init()` unconditionally schedules the sweep on every launch that constructs a coordinator:
  ```swift
  init() {
      checkForRecovery()
      // `init()` isn't itself main-actor-isolated (see the `Task { @MainActor
      // in ... }` hops elsewhere in this file) — hop over the same way to
      // reach the backfill's guaranteed main-actor invocation.
      Task { @MainActor in ThreadsBackfill.runIfNeeded() }
  }
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:21-27@0172e2b` — the Android equivalent (Application.onCreate or a top-level ViewModel init) must call the sweep-equivalent exactly once per process start, unconditionally, on main — runIfNeeded's OWN guards decide whether real work happens. Wiring it from a screen-scoped ViewModel makes the sweep run only if the user visits that screen.

### Thread store — `Pilgrim/Models/Threads/ThreadStore.swift`

- **[BEH-32 + EDG-62 — consensus] Status resolves to THREE outcomes, not two.** `.firstTime`, `.recurring(walksInWindow)`, or `nil` (no status at all) when backfill is incomplete and no earlier appearance exists:
  ```swift
  let earlier = thread.appearances.filter { $0.date < current.date && $0.walkUUID != walkUUID }
          if earlier.isEmpty {
              return backfillComplete ? .firstTime : nil
          }
  ```
  > `Pilgrim/Models/Threads/ThreadStore.swift:67-84@0172e2b` — a sealed-class port modeling only the two named cases (defaulting incomplete-backfill/no-earlier to firstTime) makes false origin claims before the sweep finishes — the exact bug class the iOS comments repeatedly warn about. The enum itself: `case firstTime; case recurring(walksInWindow: Int)` (`Pilgrim/Models/Threads/ThreadStore.swift:17-20@0172e2b`).

- **[BEH-33 + DAT-12 — consensus] 30-day recurrence window, inclusive on both ends, counting DISTINCT walks.**
  ```swift
  static let recurrenceWindow: TimeInterval = 30 * 86400
  ...
          let walksInWindow = Set(
              thread.appearances
                  .filter { $0.date >= windowStart && $0.date <= current.date }
                  .map(\.walkUUID)
          ).count
  ```
  > `Pilgrim/Models/Threads/ThreadStore.swift:30,77-83@0172e2b` — `>` instead of `>=` (or `<` instead of `<=`) changes whether a recording exactly 30 days prior counts.

- **[BEH-34 + EDG-65 — consensus] Trend direction: floor 3, thirds floored at 1, early-third must be positive.**
  ```swift
  guard saliences.count >= directionFloor else { return nil }; let third = max(1, saliences.count / 3); let early = saliences.prefix(third).reduce(0, +) / Double(third); let late = saliences.suffix(third).reduce(0, +) / Double(third); guard early > 0 else { return .steady }
  ```
  > `Pilgrim/Models/Threads/ThreadStore.swift:31-32,86-97@0172e2b` — constants: `directionFloor = 3`, `directionThreshold = 0.25`. Dropping `max(1, …)` produces a 0-sized third for 3-4 appearances (empty-average/divide-by-zero); dropping `early > 0` risks a nonsensical ratio (it returns `.steady`).

- **[BEH-35 + EDG-64 — consensus] Deterministic ordering everywhere.** Appearances sort ascending by `(date, recordingUUID.uuidString)` (both members from the same side — a plain composite key, not the swap trick), and the returned thread list sorts by lemma:
  ```swift
  appearances: appearances.sorted { ($0.date, $0.recordingUUID.uuidString) < ($1.date, $1.recordingUUID.uuidString) }
                  )
              }
              .sorted { $0.lemma < $1.lemma }
  ```
  > `Pilgrim/Models/Threads/ThreadStore.swift:56-64@0172e2b` — date-only sorting leaves same-timestamp appearances in unstable order, which can flip which appearance is treated as FIRST (origin claims) across runs; Kotlin needs explicit `compareBy(...).thenBy(...)`.

- **[EDG-63] Display term again via `.min` with a `>` comparator — plus a fallback iOS's ThemeExtractor version lacks.** `.min { ($0.value, $1.key) > ($1.value, $0.key) }?.key ?? lemma` — max count, alphabetically-first key on ties, falling back to the lemma. > `Pilgrim/Models/Threads/ThreadStore.swift:59-60@0172e2b` — note ThreadStore uses `?.key ?? lemma` where ThemeExtractor force-unwraps; keep both shapes.

- **[BEH-87] The 30-day window is REDECLARED three times, not shared.** `static let recurrenceWindow: TimeInterval = 30 * 86400` appears independently in ThreadStore, ThreadsDossierFormatter (`absenceWindow`), and ThreadIntentionSuggestions (`recurrenceWindow`). > `Pilgrim/Models/Threads/ThreadStore.swift:30@0172e2b` — iOS does not share one named constant; an Android port that centralizes must first verify all three are semantically the same lookback, and one that mirrors the triplication must fix all three together (Open questions).

### Dossier builder — `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift`

- **[BEH-36 + DAT-14 + EDG-67 — consensus] The six-field memo key — each field closes a specific cache-miss gap.**
  ```swift
  struct MemoKey: Equatable {
      let changeCount: Int
      let walkUUID: UUID
      let backfillComplete: Bool
      let moonState: Int?
      let lunationIndex: Int?
      let intention: String?
  }
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:28-43@0172e2b` — `lunationIndex` and `intention` exist specifically because a lunation closing or an in-session intention edit can leave `changeCount` unchanged; omitting either ("they already reach build via senses") serves a stale dossier across a lunation boundary or an intention edit.

- **[BEH-37 + DAT-21 — consensus] The memo is a locked static; `build()` is callable OFF the main actor — but only by documentation.** `private static var memo: (key: MemoKey, dossier: String?)?` + `private static let memoLock = NSLock()`; the doc comment: "Single insertion point for PromptListView. Callable off the main actor: the CoreStore walk index is fetched by the caller on the main actor and passed in; everything else is file I/O and pure computation." > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:19-23,45-46,81-90,180-185@0172e2b` — because build() is NOT actor-confined, the companion-object cache genuinely needs a Mutex/synchronized block (unlike ThreadsBackfill's isRunning, safe only because every caller is MainActor). The safety property is a doc comment, not compiler-checked — the Android equivalent should be a `suspend fun` that structurally forces a dispatcher choice at the call site.

- **[BEH-38 + EDG-70 — consensus] Post-build changeCount = preBuild + OWN confirmed writes — NEVER a fresh re-read.**
  ```swift
  let ownWriteCount = ownDeleteWrite + freshlySaved.count
          let postBuildKey = memoKey(walkUUID: walkUUID, changeCount: preBuildChangeCount + ownWriteCount,
                                     backfillComplete: backfillComplete, moonState: postBuildMoonState, senses: senses)
          memo = (postBuildKey, dossier)
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:154-172@0172e2b` — "an external writer (ThreadsBackfill's sweep, transcription-completion analysis) landing a save inside this same call … would otherwise get folded into the memo as if it were this build's own mutation." A port that "simplifies" by re-reading `store.changeCount` after building absorbs the concurrent write as already-accounted-for, so the NEXT call wrongly cache-hits a dossier that never incorporated it.

- **[EDG-68] The cache value is a double optional.** `cachedDossier(key:) -> String??` — outer optional = cache-present, inner = the actual (possibly-nil) dossier, so a memoized no-dossier result is a valid hit distinct from nothing-cached:
  ```swift
  private static func cachedDossier(key: MemoKey) -> String?? { memoLock.lock(); defer { memoLock.unlock() }; guard let cached = memo, cached.key == key else { return nil }; return cached.dossier }
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:177-185@0172e2b` — Kotlin has no `String??`; a naive `String?` cache cannot distinguish not-cached from cached-and-null and rebuilds every time the dossier is legitimately nil — use an explicit wrapper (sealed CacheResult or a `cached: Boolean` pair).

- **[BEH-39 + DAT-30 + EDG-69 — consensus] Empty walk index ≠ universal orphanhood.**
  ```swift
  let orphans = walkIndex.isEmpty && !all.isEmpty
              ? Set<UUID>()
              : Set(TranscriptContextStore.orphans(in: all, keeping: Set(walkIndex.keys)))
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:108-113@0172e2b` — an empty index alongside non-empty stored contexts is treated as a failed/empty DB read, not proof of orphanhood; without it, a transient read failure mass-deletes every stored linguistic analysis. This guard is mirrored in `ThreadsBackfill.pruneStaleOrphans` — replicate BOTH copies consistently. [DAT-30] The cleanup only runs after `guard UserPreferences.threadsAfterWalks.value, !recordings.isEmpty else { return nil }` (line 90) — an orphan belonging to a different, recording-less walk is not pruned by a build for that walk.

- **[BEH-40 + DAT-31 — consensus] Hash-matched cache hit; silent self-heal on mismatch; double-checked freshlySaved.**
  ```swift
  if let stored = store.context(for: uuid, matching: hash) {
                  return (stored, recording.wordsPerMinute)
              }
              let result = TranscriptContextAnalyzer.analyzeAndStore(
                  recordingUUID: uuid, transcript: recording.text, store: store
              )
              if result.saved && store.hasContext(for: uuid) {
                  freshlySaved[uuid] = result.context
              }
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:201-226@0172e2b` — edits are detected purely by transcript-hash mismatch (not a timestamp); `freshlySaved` requires BOTH `result.saved` AND a follow-up `hasContext` existence check because a tombstone-blocked save also reports `saved == true` — trusting the boolean alone overcounts writes in the memo's own-write accounting.

- **[EDG-71] Duplicate-context trap surfaces as a crash on iOS.** `var contextsByUUID = Dictionary(uniqueKeysWithValues: live.map { ($0.recordingUUID, $0) })` traps at runtime if two contexts share a recordingUUID. > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:126@0172e2b` — Kotlin's `associateBy` silently keeps the LAST duplicate, masking a data-integrity bug Swift surfaces immediately (Open questions: fail-fast vs tolerate).

- **[BEH-41 + DAT-13 — consensus] Two-phase senses gathering.** `@MainActor static func gatherSensesBundle(walk: WalkInterface, now: Date = Date()) -> DossierSensesFetchBundle` does the cheap CoreStore snapshot on main; `build()` itself is unannotated and background-callable. > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:19-23,51-52@0172e2b` — replicate the two-phase shape (cheap snapshot handed to a background-safe builder) rather than one Main-confined function; note the platform inversion: Room forbids main-thread queries by default, so the "gather on main" step likely moves entirely off main on Android.

- **[BEH-42] The pure-senses purity invariant.** "Wall-clock, not ContinuousClock — this DEBUG harness prints a human-facing diagnostic, not a pure-module measurement; the pure senses functions themselves stay Date()-free." > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:451-453@0172e2b` — threading `System.currentTimeMillis()` into a "pure" evaluator (instead of passing `now` as a parameter) makes it untestable/non-deterministic.

- **[EDG-66] Moon-line key + the (-1, -1) photo sentinel.** `static let moonLineDefaultsKey = "threadsMoonLineLastLunationIndex"`; "// (-1, -1) is the schema's unset sentinel, not a place." — `coordinate: photo.capturedLat == -1 && photo.capturedLng == -1 ? nil : DossierSenses.Coordinate(...)`. > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:26,63,66@0172e2b` — treat (-1,-1) as unset, never a plottable null-island coordinate. The key's three touch points are inventoried under Data › keys (DAT-52).

### Dossier formatter — `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift`

All strings in this component are pasted directly into an AI prompt — wording, punctuation, decimal precision, and markdown emphasis are FUNCTIONAL, not cosmetic.

- **[BEH-43 + EDG-72 — consensus] Nine named thresholds, reproduced exactly.**
  ```swift
  static let densityFloorWords = 100
      static let baselineFloorRecordings = 5
      static let absenceWindow: TimeInterval = 30 * 86400
      static let minimumAbsenceWalks = 2
      static let maxAbsenceLines = 2
      static let paceDifferenceThreshold = 0.15
      static let modalBaselineFloorWalks = 3
      static let modalRemarkableMinCount = 10
      static let modalRemarkableRateMultiple = 2.0
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:5-16@0172e2b` — `modalRemarkableRateMultiple` and `paceDifferenceThreshold` are ratio comparisons against a personal baseline; a rounding or percentage-vs-fraction difference silently shifts which walks cross the "remarkable" bar.

- **[UI-32] Non-English replacement line, literal.** `guard let markers = context.markers else { return "Markers unavailable (non-English recording)." }` — the ENTIRE line is replaced; no counts, no baseline. > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:18-21@0172e2b` — degrading gracefully with partial data changes what the AI reads for non-English recordings.

- **[UI-33 + EDG-73 — consensus] Density switch: two structurally different templates.** At `wordCount >= densityFloorWords` (100), percentages; below, raw counts with different sentence structure:
  ```swift
  if markers.wordCount >= densityFloorWords {
              let absolutist = Double(markers.absolutistCount) / Double(markers.wordCount) * 100
              var absolutistPart = String(format: "absolutist words %.1f%% over %d words", absolutist, markers.wordCount)
              if let baseline {
                  absolutistPart += String(format: " (your usual walking baseline ~%.1f%%)", baseline.absolutist * 100)
              }
              parts.append(absolutistPart)
              let firstPerson = Double(markers.firstPersonCount) / Double(markers.wordCount) * 100
              parts.append(String(format: "self-focus %.1f%%", firstPerson))
          } else {
              parts.append("\(markers.wordCount) words — small sample, raw counts only: \(markers.absolutistCount) absolutist, \(markers.firstPersonCount) self-focus")
          }
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:23-34@0172e2b` — always using the percentage template (guarding only divide-by-zero) sends the AI false-precision phrasing on short recordings that iOS deliberately avoids by switching sentence STRUCTURE, not just values.

- **[UI-34] Baseline clause is omitted, never placeholdered.** The `" (your usual walking baseline ~%.1f%%)"` suffix appends only when `personalBaseline` found ≥ `baselineFloorRecordings = 5` qualifying prior recordings (`guard qualifying.count >= baselineFloorRecordings else { return nil }`). > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:26-28,43-55@0172e2b` — rendering a 0%/placeholder baseline for new users fabricates a statistic the AI will cite.

- **[UI-35] The always-appended tail, verbatim — including the self-qualifier.**
  ```swift
  parts.append("insight \(markers.insightCount), causation \(markers.causationCount), discrepancy \(markers.discrepancyCount)")
          parts.append("temporal lean: \(markers.temporalLean) (coarse heuristic)")
          if let sentiment = markers.sentiment {
              parts.append(String(format: "sentiment %.2f", sentiment))
          }
          return parts.joined(separator: "; ")
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:35-40@0172e2b` — "(coarse heuristic)" reads like editorial hedging but is literally what the AI model reads; it shapes the weight given to temporal-lean. The `"; "` join, `%.1f` vs `%.2f` precisions, all functional [EDG-73].

- **[BEH-44 + EDG-74 — consensus] Dominant family/word selection: STRICT greater-than, declaration-ordered iteration.**
  ```swift
  var dominantFamily: (family: MarkerLexicons.ModalFamily, count: Int)?
          for family in MarkerLexicons.ModalFamily.allCases {
              let count = familyTotals[family] ?? 0
              guard count > 0, dominantFamily == nil || count > dominantFamily!.count else { continue }
              dominantFamily = (family, count)
          }
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:91-105@0172e2b` — explicitly documented as depending on `ModalFamily.allCases` and each family's word array being declaration-ordered, with only a STRICTLY greater count replacing the running best (first exact tie always resolves to the same word). [EDG-74] The `dominantFamily == nil || count > dominantFamily!.count` force-unwrap is safe only via `||` short-circuit — Kotlin's `||` short-circuits identically so `dominantFamily == null || count > dominantFamily!!.count` ports, but reordering the operands (a plausible cleanup) crashes on the first non-nil comparison. Same pattern at line 102 for `dominantWord`.

- **[BEH-45 + UI-36 + EDG-75 — consensus] The modal-lean clause: absolute floor AND relative-to-self floor; silent without a baseline; U+00D7 in the text.**
  ```swift
  guard let summary = modalLeanSummary(for: currentRecordings.map(\.context)),
                summary.familyCount >= modalRemarkableMinCount else { return nil }
          guard let baseline = modalBaseline(from: allContexts, walkIndex: walkIndex, excluding: currentWalkUUID),
                let entry = baseline[summary.family], entry.rate > 0,
                summary.familyRate >= modalRemarkableRateMultiple * entry.rate else { return nil }
          return "modal lean: \(summary.family.rawValue) — '\(summary.word)' ×\(summary.count)" +
              " (your usual ~\(Int(entry.averagePerWalk.rounded())) per walk)"
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:158-171@0172e2b` — BOTH gates: count ≥ 10 AND rate ≥ 2.0× the walker's own per-walk baseline (itself requiring ≥ 3 qualifying prior walks). With no baseline it stays silent — defaulting a missing baseline to rate 0 makes the gate trivially pass and fires on a walker's first walks, contradicting "first walks never speak here." The `×` is U+00D7 MULTIPLICATION SIGN, not ASCII `x` — a one-character prompt divergence [EDG-75]. Collapsing the two-part design into one "is notable" boolean loses the deliberately separate absolute+relative structure (doc comment lines 148-157).

- **[BEH-46] Modal baseline groups by WALK; personal baseline groups by RECORDING — deliberately different.**
  ```swift
  let walksRepresented = Set(qualifying.compactMap { walkIndex[$0.recordingUUID] })
          guard walksRepresented.count >= modalBaselineFloorWalks else { return nil }
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:124-130@0172e2b` — a DRY-tempted shared grouping helper breaks the "state signal is per-walk" design: one walk with three chatty recordings would count as three data points toward the modal floor instead of one.

- **[BEH-47 + UI-41 + EDG-78 — consensus] Pace correlation: SIGNED relative change, ±0.15 band, exactly two phrasings, nothing in between.**
  ```swift
  let change = (themeMean - restMean) / restMean

          if change <= -paceDifferenceThreshold { return ", spoken more slowly than the rest of this walk" }
          if change >= paceDifferenceThreshold { return ", spoken more quickly than the rest of this walk" }
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:261-281@0172e2b` — guards first: `guard !inTheme.isEmpty, !rest.isEmpty else { return nil }` and `guard restMean > 0 else { return nil }` (divide-by-zero). Inside the band it produces NO clause — not a neutral placeholder, never the numeric value ("this signal stays non-numeric, dossier-only prose"). An absolute-WPM-difference port makes 0.15 mean something different at every baseline pace. Both phrases attach with a leading comma-space and no capital — the punctuation completes the host sentence.

- **[BEH-48 + UI-40 + EDG-77 — consensus] The absence section: origin-claim-grade gating, verbatim template.**
  ```swift
  if backfillComplete, let quiet = quietLines(threads: threads, currentWalkUUID: currentWalkUUID) {
              section += "\n\n**Quiet this walk:**"
              section += quiet
          }
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:223-233,253-255@0172e2b` — gated on `backfillComplete` exactly like `.firstTime` (an absence claim is as risky as an origin claim), applied independently in the formatter — easy to port the other sections and miss this gate. Qualification: present in ≥ `minimumAbsenceWalks = 2` of the last `absenceWindow` (30-day) walks while absent from the current one, capped at `maxAbsenceLines = 2`, selected by the tuple-swap `(walks desc, lemma asc)` comparator `.sorted { ($0.walks, $1.thread.lemma) > ($1.walks, $0.thread.lemma) }.prefix(maxAbsenceLines)` [EDG-77]. Line template, verbatim: `"\nNotably quiet this walk: '\($0.thread.displayTerm)' — present in \($0.walks) of the walker's recent walks."` joined with no separator.

- **[UI-38] Fixed section order, literal markdown-bold headers, one hand-assembled string.** `"**Thought threads (on-device linguistic analysis):**"` (always present when the dossier is non-nil) → optional `"**Threads across recent walks:**"` → optional `"**Quiet this walk:**"`, concatenated with `\n\n` between sections. > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:184-227@0172e2b` — rewording a header to something "semantically equivalent" changes what the LLM reads as a section cue.

- **[UI-39 + EDG-76 — consensus] Per-thread line: quoted term + up to four independently-optional clauses with literal connectors.**
  ```swift
  var line = "\n'\(thread.displayTerm)'"
                  switch ThreadStore.status(of: thread, atWalk: currentWalkUUID, backfillComplete: backfillComplete) {
                  case .firstTime:
                      line += " — first appearance in the record"
                  case .recurring(let walks):
                      line += " — \(walks) walk\(walks == 1 ? "" : "s") in the last 30 days"
                  case nil:
                      break
                  }
                  if let direction = ThreadStore.salienceDirection(of: thread) {
                      line += ", \(direction.rawValue) across appearances"
                  }
                  if let origin = thread.appearances.first, backfillComplete {
                      line += " (first spoken \(ContextFormatter.shortDateFormatter.string(from: origin.date)))"
                  }
                  if let paceNote = paceCorrelation(of: thread, in: currentRecordings) {
                      line += paceNote
                  }
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:201-218@0172e2b` — every connector (`" — "`, `", "`, `" ("`) and the manual `walks == 1` singular/plural branch is prompt-visible; dropping the leading comma before the salience clause merges two independent facts into a run-on the AI reads as one. Note `backfillComplete` gates BOTH the "(first spoken …)" clause here AND the Quiet section — same reason (historical claims need a complete sweep); wiring them as two independent flags risks gating one and not the other [UI-40 nuance].

- **[UI-37] Modal-lean placement: a trailing line with no header, no "Recording N:" prefix.**
  ```swift
  var section = "**Thought threads (on-device linguistic analysis):**"
          for (index, recording) in currentRecordings.enumerated() {
              section += "\nRecording \(index + 1): \(markerLine(for: recording.context, baseline: baseline))"
          }
          if let modalLine = modalLeanLine(
              currentRecordings: currentRecordings, allContexts: allContexts,
              walkIndex: walkIndex, currentWalkUUID: currentWalkUUID
          ) {
              section += "\n\(modalLine)"
          }
  ```
  > `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:184-193@0172e2b` — promoting it to its own labeled section (a reasonable instinct — it is a per-walk signal, not a per-recording marker) alters where the AI encounters it relative to the numbered lines.

### Intention suggestions — `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift`

- **[BEH-49 + UI-28 + EDG-79 — consensus] `pendingFieldGate` is a literal ship-gate kill-switch; false = ENABLED.**
  ```swift
  /// The human field gate passed 2026-08-24 (spec addendum: "Chips:
      /// cleared to ship") — chips render in IntentionSettingView from 1.12.0.
      /// The header ships as "Recurring"; a softer-variant copy pass is a
      /// tracked fast-follow, judged against real chip words.
      static let pendingFieldGate = false
  ```
  > `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift:10-14@0172e2b` — `current()` short-circuits to `[]` whenever this is true; copying it as `true` by mistake silently produces zero suggestions with no error signal, and omitting the switch entirely loses the one-constant global disable. Ship the literal header "Recurring" — the softer copy variant is an UNSHIPPED fast-follow; shipping it early creates a divergent copy iOS doesn't have.

- **[UI-29] Three silent guards, indistinguishable to the UI.**
  ```swift
  guard !pendingFieldGate else { return [] }
          guard UserPreferences.threadsAfterWalks.value else { return [] }
          // walkIndex is injectable for Task 8's wiring test; production
          // callers pass nil and read the live CoreStore index on the main actor.
          let walkIndex = walkIndex ?? DataManager.voiceRecordingWalkIndex()
          guard !walkIndex.isEmpty else { return [] }
  ```
  > `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift:59-64@0172e2b` — "feature disabled", "no data yet", and "not enough recurrence" all surface identically as `[]`; IntentionSettingView never distinguishes them. Wire the kill-switch too, not just the visible preference — otherwise Android has nothing to flip if iOS re-gates for a staged rollout.

- **[BEH-50 + UI-31 + EDG-80 — consensus] The chip pipeline: ≥2 DISTINCT WALKS in 30 days → sort → template → dedup → cap, in that exact order.**
  ```swift
  var seen: Set<String> = []
          return Array(
              threads
                  .compactMap { thread -> (String, Int)? in
                      ...
                      guard walks.count >= minimumDistinctWalks else { return nil }
                      return (thread.displayTerm, walks.count)
                  }
                  .sorted { ($0.1, $1.0) > ($1.1, $0.0) }
                  .map { "walk with '\($0.0)'" }
                  .filter { seen.insert($0).inserted }
                  .prefix(limit)
          )
  ```
  > `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift:16-44@0172e2b` — constants: `recurrenceWindow: TimeInterval = 30 * 86400`, `minimumDistinctWalks = 2`, `maxSuggestions = 2`. Distinct WALK UUIDs, not recordings. Dedup happens on the FORMATTED phrase, after templating and before capping — two lemmas can share a display term ("move/moving → the move"), and dedup-after-format-then-cap lets a distinct suggestion behind a duplicate still get its chance; deduping before formatting or capping before deduping can return fewer than 2 chips when 2 distinct ones exist. Chip text template, verbatim: `"walk with '\(displayTerm)'"`. [EDG-80] `seen.insert($0).inserted` as a filter predicate translates to `seen.add(it)` in Kotlin directly. [UI-30] These 2/2 constants share no source with the formatter's `minimumAbsenceWalks = 2`/`modalBaselineFloorWalks = 3` — consolidating "how many walks counts as a pattern" couples intentionally-separate signals.

- **[BEH-51 + EDG-81 — consensus] The memo goes stale at MIDNIGHT, not just on writes.**
  ```swift
  let day = Calendar.current.startOfDay(for: asOf)
          let preLoadChangeCount = store.changeCount
          memoLock.lock()
          let cached = memo
          memoLock.unlock()
          if let cached, cached.changeCount == preLoadChangeCount, cached.day == day {
              return cached.suggestions
          }
  ```
  > `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift:46-52,65-73@0172e2b` — the key is (changeCount, day-of-asOf): the 30-day window can shift which walks qualify purely from time passing. A changeCount-only cache serves yesterday's suggestions all day.

- **[BEH-52] Read-then-detach, counter captured BEFORE the load.**
  ```swift
  return await Task.detached(priority: .userInitiated) {
              let threads = ThreadStore.build(contexts: store.loadAll(), walks: walkIndex)
              let suggestions = select(threads: threads, asOf: asOf)
              memoLock.lock()
              memo = (preLoadChangeCount, day, suggestions)
              memoLock.unlock()
              return suggestions
          }.value
  ```
  > `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift:53-64,75-82@0172e2b` — preference + CoreStore walk index read on the main actor, memo checked, then detached at `.userInitiated` for load+aggregate+select. `preLoadChangeCount` is captured BEFORE `store.loadAll()` — same ordering discipline as the dossier builder, same reason: a mid-read external mutation must leave the memo stale, not get absorbed.

### Attention directives — `Pilgrim/Models/Prompt/AttentionDirectives.swift`

- **[BEH-71 + EDG-86 — consensus] Five detectors, fixed array order, cap of 4 — the order is a reachable prioritization.**
  ```swift
  let directives = [
              stillness(context),
              paceShift(context),
              intentionEcho(context, spokenMentions: spokenMentions, detectedLanguageCode: detectedLanguageCode),
              recurringWord(context, spokenMentions: spokenMentions),
              firstVersusLast(context)
          ].compactMap { $0 }
          return Array(directives.prefix(maxDirectives))
  ```
  > `Pilgrim/Models/Prompt/AttentionDirectives.swift:9-10,24-31@0172e2b` — `maxDirectives = 4` with 5 detectors: when all five fire, `firstVersusLast` (last in the array) is the one silently dropped; reordering the array changes which directive gets cut. [BEH cluster] `spokenMentions` is computed ONCE (`context.recordings.map(\.text).joined(separator: " ")` through `contentLemmaMentions`) and shared by intentionEcho + recurringWord.

- **[EDG-82] Stillness thresholds.** `movingThreshold = 0.3`; `guard speeds.count >= 30, context.duration > 0 else { return nil }`; run detection via `currentRun = (0..<movingThreshold).contains(speed) ? currentRun + 1 : 0`; fires only when `estimatedMinutes >= 3` AND `estimatedMinutes > explainedMinutes` (meditation/pause time already explaining it). > `Pilgrim/Models/Prompt/AttentionDirectives.swift:9-10,44,49,56@0172e2b` — `(0..<movingThreshold).contains(speed)` means `speed < 0.3 AND speed >= 0` in one expression; a literal `speed < movingThreshold` port also accepts negative (invalid GPS) speeds the range form implicitly excludes. (The stillness directive's emitted template string was not captured by any lens — read the file before writing the Kotlin string; see Open questions.)

- **[EDG-83] Pace-shift: final third vs first third, moving samples only, ±20%, near-mirror templates.** `guard moving.count >= 30 else { return nil }` … `guard first > 0 else { return nil }; let change = (last - first) / first; guard abs(change) >= 0.2 else { return nil }`. Templates, verbatim: `"The walker's pace slowed by \(percent)% in the final third — something slowed them; notice what."` / `"The walker's pace quickened by \(percent)% in the final third — something carried them; notice what."` > `Pilgrim/Models/Prompt/AttentionDirectives.swift:63-77@0172e2b` — the two branches differ only in slowed/something-slowed-them vs quickened/something-carried-them; a copy-paste error leaves both saying the same thing.

- **[BEH-72 + EDG-84 — consensus] Intention echo: per-WORD-then-per-tier, and only the exact tier says "again".**
  ```swift
  for word in TranscriptNLP.contentLemmaMentions(in: intention) {
              if spoken.contains(where: { $0.lemma == word.lemma && $0.surface == word.surface }) {
                  return "..."
              }
              if let match = spoken.first(where: { $0.lemma == word.lemma }) {
                  return "..."
              }
              if let match = spoken.first(where: { TranscriptNLP.related(word.lemma, $0.lemma, languageCode: language) }) {
                  return "..."
              }
          }
  ```
  > `Pilgrim/Models/Prompt/AttentionDirectives.swift:96-106@0172e2b` — iterate intention words in TEXT order, trying all three tiers for EACH word before the next word — NOT "tier 1 across all words, then tier 2": the two shapes return DIFFERENT echoed words whenever an earlier word matches only at a later tier. Templates [EDG-84]: exact-surface → `"The walker's intention spoke of '\(surface)', and '\(surface)' surfaces again in their spoken words — trace how it traveled."`; lemma-match and related-word both → `"The walker's intention spoke of '\(surface)', and '\(match)' surfaces in their spoken words — trace how it traveled."` (no "again") — collapsing all three to "again" claims literal repetition the design explicitly avoids.

- **[EDG-85] Recurring word: floor of 3, double tie-break, verbatim template.** `guard let (lemma, count) = counts.filter({ $0.value >= 3 }).min(by: { ($0.value, $1.key) > ($1.value, $0.key) }) else { return nil }; let display = surfaces[lemma]?.min(by: { ($0.value, $1.key) > ($1.value, $0.key) })?.key ?? lemma` → `"The word '\(display)' returns \(count) times across the recordings — it may be doing quiet work."` > `Pilgrim/Models/Prompt/AttentionDirectives.swift:134-139@0172e2b` — the same tuple-swap idiom applied twice (winning lemma, then its most frequent surface); intention lemmas and scaffoldLemmas are excluded before counting.

- **[EDG-86] First-vs-last: ≥2 recordings, fixed non-parameterized template.** `guard context.recordings.count >= 2 else { return nil }; return "Compare the first recording with the last — measure what changed in the walker between them."` > `Pilgrim/Models/Prompt/AttentionDirectives.swift:24-31,143-144@0172e2b`.

  > **SUPERSEDED at `e7051bc` (fold-in, 2026-08-29).** iOS PR #72 replaced this unconditional line — it fired on every walk with two recordings and presupposed its own conclusion. The shipped detector now measures before it speaks: a speaking-rate branch (both recordings ≥ 25 words, relative change ≥ ±0.15) tried first, then a subject branch (content lemmas minus scaffolding, smaller set ≥ 12, length ratio ≤ 3.0, overlap coefficient ≤ 0.20), both failing closed. Three parameterized strings replace the one template. The quote above remains accurate for the `0172e2b` pin this spec was written against; Android ships the superseding behavior in `AttentionDirectives.firstVersusLast`.

- **[BEH-73] No actor/dispatcher annotations anywhere in the type.** `detect` runs CPU-bound NLTagger work over the full joined transcript wherever the caller invokes it (`let spokenMentions = context.hasSpeech ? TranscriptNLP.contentLemmaMentions(in: context.recordings.map(\.text).joined(separator: " ")) : []`). > `Pilgrim/Models/Prompt/AttentionDirectives.swift:15-23@0172e2b` — on Android this is exactly the CPU-on-Main trap from project memory (Stages 2-E/5-C/5-D): the prompt-assembly call needs an explicit `withContext(Dispatchers.Default)`.

### Prompt assembly — `PromptAssembler.swift`, `PromptGenerator.swift`, `PromptContextTypes.swift`

- **[BEH-74] The dossier's position in the prompt is fixed: after recent-walks, before the directives block.**
  ```swift
  if let recentWalks = ContextFormatter.formatRecentWalks(context.recentWalkSnippets) {
          sections += "\n\n\(recentWalks)"
      }

      if let dossier = context.threadsDossier {
          sections += "\n\n\(dossier)"
      }

      let resolvedDirectives = directives ?? AttentionDirectives.detect(context: context)
  ```
  > `Pilgrim/Models/Prompt/PromptAssembler.swift:133-144@0172e2b` — an LLM prompt is order-sensitive; appending the dossier elsewhere produces a materially different prompt from identical data.

- **[BEH-75 + EDG-88 — consensus] The safety line is gated on the ARTIFACT, not the preference.** `hasThreadsDossier` derives from `context.threadsDossier != nil`: `sections += "\n\n\(responseContract(voice: voice, hasSpeech: context.hasSpeech, hasThreadsDossier: context.threadsDossier != nil))"` and inside the contract: `if hasThreadsDossier { lines.append("The thought-thread marker profiles are descriptive on-device linguistic signals, not assessments — interpret them gently, never produce clinical or diagnostic language, and never treat a single walk's numbers as meaningful on their own.") }`. > `Pilgrim/Models/Prompt/PromptAssembler.swift:45,183-185@0172e2b` — gating on the raw preference instead shows the caveat on walks with no threads data (pref on, no recordings) or omits it when dossier text exists despite the pref — usually in sync, not identical by construction.

- **[EDG-88] The full response-contract line inventory, verbatim.** Conditional on `hasSpeech`: `"Respond in the language the walker speaks in the transcription."` and `"If more than one voice appears in the transcription, honor it as a conversation — attend to what happened between the speakers, and never guess at names."`; conditional on `hasThreadsDossier`: the safety line above; always-on: `"Draw only on what this walk actually holds — never invent details, events, or memories that are not in the context above."`; header: `"**How to respond:**"`. > `Pilgrim/Models/Prompt/PromptAssembler.swift:177-188@0172e2b` — these are the model's literal safety rails.

- **[EDG-89] The intention is echoed into the prompt TWICE, with different surrounding phrasing.** Closing-instruction form: `" Ground your response in the walker's stated intention: '\(intention)'. Return to it. Help them see how their walk — its pace, its pauses, its moments — spoke to this purpose."`; context-block form: `"**The walker's intention:** \"\(intention)\"\nThis intention was set deliberately before the walk began. It represents what the walker chose to carry with them. Let it be the lens through which you interpret everything below."` > `Pilgrim/Models/Prompt/PromptAssembler.swift:41,60-61@0172e2b` — porting only one occurrence under-weights the intention; the model receives the reinforcement twice by design.

- **[EDG-90] Practice lexicon: Wander/Seek framing with a three-way clearing branch.** `"**About this practice:** This walk was a wander — no destination, no goal; the path chose itself."` / `"**About this practice:** This walk was a Seek. The walker surrendered the choice of destination: a seed cast hidden clearings across the map, veiled in fog, revealed only by nearness and stillness. Arriving is not achievement; it is consent to be led."` plus zero/one/many suffixes: `" No clearing was reached this time — the seek honors this too; some walks are about the looking."` / `" One clearing was found, reached in the \(timeOfDay)."` / `" \(count) clearings were found — the first in the \(timeOfDay), the last in the \(timeOfDay)."` > `Pilgrim/Models/Prompt/PromptAssembler.swift:156-169@0172e2b` — collapsing exactly-one into the many branch grammatically produces "1 clearings were found."

- **[EDG-87] Language names are always English, regardless of device locale.** `static func languageName(forCode code: String?) -> String? { code.flatMap { Locale(identifier: "en").localizedString(forLanguageCode: $0) } }` > `Pilgrim/Models/Prompt/PromptAssembler.swift:106-108@0172e2b` — using the device default Locale sends the LLM a localized language name (e.g. "français") the English prompt instructions don't anticipate; Android must pin `Locale.ENGLISH` (cf. the project's Locale.US formatting lessons).

- **[BEH-76] `RecordingContext.recordingUUID` is optional and mutable — no-UUID means invisible to Threads, still present as prose.**
  ```swift
  struct RecordingContext {
      let text: String
      let timestamp: Date
      let startCoordinate: (lat: Double, lon: Double)?
      let endCoordinate: (lat: Double, lon: Double)?
      let wordsPerMinute: Double?
      var recordingUUID: UUID?
      var endTimestamp: Date?
  }
  ```
  > `Pilgrim/Models/Prompt/PromptContextTypes.swift:3-11@0172e2b` — downstream Threads code silently drops UUID-less recordings from Threads consideration while they still appear in the walk transcription — a designed exclusion, not an error case to log or crash on.

- **[BEH-77] Language + directives are derived ONCE and reused across every prompt style.**
  ```swift
  static func resolvedDerivations(context: ActivityContext) -> (directives: [String], languageName: String?) {
      let languageCode = PromptAssembler.detectedLanguageCode(context: context)
      return (
          directives: AttentionDirectives.detect(context: context, detectedLanguageCode: languageCode),
          languageName: PromptAssembler.languageName(forCode: languageCode)
      )
  }
  ```
  > `Pilgrim/Models/PromptGenerator.swift:49-76@0172e2b` — calling a per-style generate function N times without this shared precomputation re-runs the expensive detection N times — a real regression on the prompt-list screen.

- **[EDG-91] Word-boundary truncation: default 200, always appends "...", grapheme-counted.** `func truncatedAtWordBoundary(maxLength: Int = 200) -> String { guard count > maxLength else { return self }; let truncated = prefix(maxLength); if let lastSpace = truncated.lastIndex(of: " ") { return String(truncated[..<lastSpace]) + "..." }; return String(truncated) + "..." }` > `Pilgrim/Models/PromptGenerator.swift:83-91@0172e2b` — Swift `count`/`prefix` walk grapheme clusters; Kotlin `length`/`take` walk UTF-16 units — the two disagree on where 200 falls for emoji/combining text, and Kotlin can split a surrogate pair where Swift cannot.

### Preferences & keys — `Pilgrim/Models/Preferences/UserPreferences.swift`

- **[BEH-69 + UI-44 + DAT-54 — consensus] The master gate defaults to TRUE; its neighbor defaults to FALSE — the asymmetry is the design.**
  ```swift
  static let autoTranscribe = UserPreference.Required<Bool>(key: "autoTranscribe", defaultValue: false)
      static let threadsAfterWalks = UserPreference.Required<Bool>(key: "threadsAfterWalks", defaultValue: true)
  ```
  > `Pilgrim/Models/Preferences/UserPreferences.swift:94-95@0172e2b` — `threadsAfterWalks` (opt-OUT) is the master gate every Threads code path checks; `autoTranscribe` (opt-IN) determines whether transcripts even exist to analyze. A fresh install has the threads engine armed with nothing to analyze until transcription is enabled. Defaulting the Android DataStore key to false "to be safe" silently disables the entire slice — chips and dossier lines would never appear for any Android user without explicit opt-in, unlike every iOS user.

- **[BEH-70 + DAT-55 — consensus] The archived-walk registry is mutation-guarded.** `[String: Double]` (walk UUID string → archivedAt epoch-seconds), default `[:]`; "// Mutate only via the helpers below — direct .value assignment from user code is not race-safe." — `markWalkArchived`/`unmarkWalkArchived`/`clearArchivedRegistry` run on a dedicated serial queue. > `Pilgrim/Models/Preferences/UserPreferences.swift:83-91,132-184@0172e2b` — whole-dictionary read-modify-write hazard; Android's `dataStore.edit {}` transaction is the natural safe seam PROVIDED no naive read-then-write pattern reintroduces the race.

### Deletion & import hygiene — `DataManager.swift`, `PilgrimPackageImporter.swift`, snapshot queries

- **[DAT-33] Generic delete, Walk-specific harvest.** `deleteObject` computes filePaths/recordingUUIDs generically but only populates them via a `Walk` cast inside the transaction, then tombstones exactly those UUIDs post-commit:
  ```swift
  let walkUUID = (object as? Walk)?.uuid

  dataStack.perform(asynchronous: { (transaction) -> ([String], [UUID]) in

      var filePaths: [String] = []
      var recordingUUIDs: [UUID] = []
      if let walk = object as? Walk,
         let editable = transaction.edit(walk) {
          filePaths = editable._voiceRecordings.value.compactMap { $0._fileRelativePath.value }
          recordingUUIDs = editable._voiceRecordings.value.compactMap { $0._uuid.value }
      }
      transaction.delete(object)
      return (filePaths, recordingUUIDs)
  ```
  > `Pilgrim/Models/Data/DataManager.swift:785-815@0172e2b` — deleting an Event (another conformer of the same generic function) produces empty lists and never touches the context store; a generic "delete entity" repository must replicate the Walk-only special case, not give every entity the same cleanup.

- **[DAT-17] The delete completion does real file I/O on Main.** CoreStore completions land on the main queue, and the success arm runs `cleanupRecordingFiles(relativePaths:)` + `transcriptContextStore.delete(recordingUUIDs:)` + `UserPreferences.unmarkWalkArchived` synchronously inside it. > `Pilgrim/Models/Data/DataManager.swift:801-813@0172e2b` — a genuine shipping Main-thread-I/O pattern; an Android suspend-Room + IO-dispatched cleanup would not reproduce it (arguably a fix, but a behavioral difference to decide explicitly — Open questions).

- **[BEH-65 + DAT-18/DAT-34 — consensus] Delete-All: snapshot, tombstone SYNCHRONOUSLY, then wipe — in that order.**
  ```swift
  // Tombstone every known recording UUID explicitly BEFORE the
                  // wipe: deleteAll() only tombstones files already on disk, so
                  // an in-flight analysis queued before the wipe could still
                  // write afterward. Kept synchronous on purpose — ordering
                  // safety on this rare destructive op beats micro-latency.
                  transcriptContextStore.insertTombstones(for: allRecordingUUIDs)
                  transcriptContextStore.deleteAll()
                  UserPreferences.clearArchivedRegistry()
                  UserDefaults.standard.removeObject(forKey: ThreadsDossierBuilder.moonLineDefaultsKey)
  ```
  > `Pilgrim/Models/Data/DataManager.swift:823-862@0172e2b` — three-step invariant: snapshot all recording UUIDs before deletion starts, tombstone synchronously, then wipe — closing the race where an analysis queued pre-wipe writes post-wipe. The transaction deletes across TEN entity types (`Walk, WalkPause, WalkEvent, RouteDataSample, HeartRateDataSample, VoiceRecording, ActivityInterval, Waypoint, WalkPhoto, Event` — DAT-34) and also runs `cleanupRecordingFiles` + `cleanupEmptyRecordingsDirectory` in the Main-queue completion. The ordering guarantee — not the thread — is the load-bearing part. Forgetting the moon-line key leaves stale lunation state after a Delete-All.

- **[DAT-56] What Delete-All does NOT clear.** Cleared: `archivedWalkRegistry`, `threadsMoonLineLastLunationIndex`. NOT cleared: `threadsBackfillCompletedV6`, `threadsAfterWalks`, `autoTranscribe`, `whisperModelPath`/`whisperModelVariant`. > `Pilgrim/Models/Data/DataManager.swift:854-855@0172e2b` — because the completed flag survives, `ThreadsBackfill.isComplete` stays true after a wipe; recordings added afterward never trigger a fresh sweep until an import or a toggle off/on calls `reset()`. Whether a higher-level "factory reset" UI also intervenes is outside this slice's file list — trace before deciding (Open questions).

- **[BEH-66 + DAT-35 — consensus] Import success path: clear tombstones for DECODED UUIDs, then reset the backfill — both success-gated, in that order.**
  ```swift
  let importedRecordingUUIDs = package.walks
                      .flatMap { $0.voiceRecordings.compactMap(\.uuid) }
                  DispatchQueue.main.async {
                      saveData(package: package) { result in
                          if case .success = result {
                              TranscriptContextStore.shared.clearTombstones(for: importedRecordingUUIDs)
                              ThreadsBackfill.reset()
                          }
                          completion(result)
                      }
                  }
  ```
  > `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:72-92@0172e2b` — imports bypass the transcription choke point, so the reset re-arms the next-launch sweep for imported recordings; origin labels stay suppressed (status nil) until that sweep completes. Resetting unconditionally triggers a pointless full resweep on failed imports; forgetting clearTombstones permanently blocks re-analysis for any imported UUID colliding with a previously-deleted-and-tombstoned one (real cross-device merge scenario). Nuance: the success check is on the OUTER Result — clearTombstones/reset fire for ALL decoded UUIDs even when some walks individually failed to save (Open questions).

- **[BEH-67] Import ordering: decode fully off-main, then write, then cleanup.**
  ```swift
  DispatchQueue.global(qos: .userInitiated).async {
          do {
              let package = try unpackAndDecode(from: url)
              ...
              DispatchQueue.main.async {
                  saveData(package: package) { result in
  ```
  > `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:68-85@0172e2b` — the SEQUENCING must hold on Android regardless of dispatchers; the literal main-thread hop is CoreStore-tailored and may be unnecessary with Room's background-tolerant writes.

- **[BEH-68] The over-clear: tombstones are cleared for recordings of walks that were never imported.** `filterLocallyArchived` silently drops already-archived walks BEFORE saveData, but `importedRecordingUUIDs` was computed from the FULL decoded package:
  ```swift
  private static func filterLocallyArchived(_ walks: [TempWalk]) -> [TempWalk] {
      let localRegistry = UserPreferences.archivedWalkRegistry.value
      return walks.filter { walk in
          guard let uuid = walk.uuid else { return true }
          if localRegistry[uuid.uuidString] != nil {
              print("[PilgrimPackageImporter] Skipping walk \(uuid) — already archived locally")
              return false
          }
          return true
      }
  }
  ```
  > `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:82-83,277-287@0172e2b` — a pre-existing iOS-side minor over-clear; a byte-for-byte port reproduces it faithfully. Preserve-or-fix decision recorded in Open questions.

- **[DAT-29] The importer's orphan gap.** `stripHeavyData` deletes a walk's VoiceRecording rows when applying an archived-walk manifest entry against an existing local Walk — but never calls `TranscriptContextStore.delete`/`removeContext` for those UUIDs:
  ```swift
  let recordings = walk._voiceRecordings.value
      for recording in recordings {
          let path = recording._fileRelativePath.value
          if !path.isEmpty {
              fileURLs.append(docs.appendingPathComponent(path))
          }
      }

      for rec in recordings { transaction.delete(rec) }
  ```
  > `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:443-465@0172e2b` — the one recording-delete route in the slice that leaves a CURRENT-schema context JSON orphaned with no tombstone. It is invisible to `pruneStaleOrphans` by construction (`schemaVersion != currentSchemaVersion` excludes it) and is only swept the next time `ThreadsDossierBuilder.build()` runs for some OTHER walk with recordings. Close-or-reproduce decision in Open questions.

- **[BEH-64] The walk-save path NEVER analyzes.** `persistVoiceRecordings` (fresh walks AND package imports) writes whatever transcription the source object carries without calling the analyzer:
  ```swift
  recording._transcription .= tempRecording.transcription
              recording._wordsPerMinute .= tempRecording.wordsPerMinute
              recording._isEnhanced .= tempRecording.isEnhanced
              recording.⟨frozen legacy relationship property⟩ .= walk
  ```
  > `Pilgrim/Models/Data/DataManager.swift:369-386@0172e2b` — Threads analysis for these recordings happens later via auto-transcription's write path, the backfill sweep (imports rely on this — they bypass the choke point), or manual retranscribe. Do NOT add an analyze-on-insert: it double-analyzes, races the correct triggers, and breaks the reset-after-import design. (The assignment target on the last line is the frozen legacy relationship property — see the caution below.)

- **[BEH-85] Single-file delete keeps the transcription AND the context.** The confirm dialog promises it, and the action deletes ONLY the audio file:
  ```swift
  "Delete this recording file? The transcription will be kept.",
              isPresented: $showDeleteConfirmation
          ) {
              Button("Delete", role: .destructive) {
                  guard let path = pathToDelete else { return }
                  if audioPlayer.currentPath == path {
                      audioPlayer.stop()
                  }
                  DataManager.deleteRecordingFile(relativePath: path)
                  deletedPaths.insert(path)
              }
  ```
  > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:43-56@0172e2b` — never touches the VoiceRecording row, its transcription, or its TranscriptContext/tombstone. Reusing the whole-walk-delete function (which DOES tombstone) here destroys Threads data the UI explicitly promises to preserve.

- **[EDG-96] Filesystem cleanup helpers swallow every error.** `cleanupRecordingFiles`, `cleanupEmptyRecordingsDirectory`, `deleteRecordingFile`, `deleteAllRecordingFiles` all use `try?` with no logging on both deletes and the list-remaining check (`try? FileManager.default.removeItem(at: url)` … `let remaining = (try? FileManager.default.contentsOfDirectory(at: parent, includingPropertiesForKeys: nil)) ?? []`). > `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:34,38,46-48,55,57-59,78@0172e2b` — a failed delete is silently success; Android surfacing these as exceptions would crash call sites that assume no-throw. [EDG-92] Five functions in the same file also force-unwrap `FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!` (`Pilgrim/Models/Data/DataManager+VoiceRecording.swift:31,44,53,65,76@0172e2b`) and [EDG-53] TranscriptionService repeats the identical unwrap at lines 139, 291, 382 — no port needed (`context.filesDir` is non-null by contract; do not use `getExternalFilesDir`, which can be null, and do not transliterate `!!`).

- **[BEH-61 + DAT-61 — consensus] Snapshot queries: @MainActor, narrow two-column projections, full-table, silent [] on failure.**
  ```swift
  @MainActor
      public static func transcribedRecordingsSnapshot() -> [(uuid: UUID, transcript: String)] {
          guard let rows = try? dataStack.queryAttributes(
              From<VoiceRecording>().select(
                  NSDictionary.self,
                  .attribute(\._uuid),
                  .attribute(\._transcription)
              )
          ) else { return [] }
          return rows.compactMap { row in
              guard let uuid = rowUUID(row["id"]),
                    let transcript = row["transcription"] as? String,
                    !transcript.isEmpty else { return nil }
              return (uuid, transcript)
          }
      }
  ```
  > `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:128-154@0172e2b` — no `.where()` predicate, no `.limit()`; non-empty filtering happens in Swift. The narrow projection exists to avoid faulting full rows on Main — a platform inversion for Android: Room forbids Main queries by default, so these snapshot equivalents (and the `gatherSensesBundle` pattern that consumes them) move off-main rather than porting method-for-method (BEH cluster `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:139-147,190-208@0172e2b`). A silent `[]` on store failure is indistinguishable from an empty history — which is exactly why `pruneStaleOrphans` refuses to treat an empty snapshot as proof of orphanhood; Room throws instead of returning empty, so the port must keep the downstream "empty-as-failure ≠ empty-as-truth" guard.

- **[BEH-62 + DAT-63 + EDG-94 — consensus] `rowUUID`: one shared decode helper; malformed ids drop silently.** `static func rowUUID(_ raw: Any?) -> UUID? { (raw as? String).flatMap(UUID.init(uuidString:)) }` — CoreStore stores UUID as a raw string column, and every snapshot query decodes through this helper. > `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:228-235@0172e2b` — portable behavior: malformed/unparseable id → drop the row silently, never crash or substitute a placeholder — consistently across ALL FOUR snapshot queries. [EDG-94] This is `Optional.flatMap`, not `Sequence.flatMap`; the Kotlin analogue `raw?.let { … }` needs `runCatching` since `UUID.fromString` THROWS on malformed input where Swift's failable init returns nil. (Room TypeConverters returning UUID-typed columns sidestep the bug class entirely — the parity point is that all four queries decode identity identically.)

- **[BEH-63 + DAT-62 — consensus] The recording→walk join, and the ONE naming caution for this spec.** `voiceRecordingWalkIndex` joins two separate projections (Walk: objectID+uuid+startDate; VoiceRecording: uuid + its walk relationship) by object ID in Swift, and silently excludes any recording whose owning walk cannot be joined:
  ```swift
  var index: [UUID: (walkUUID: UUID, date: Date)] = [:]
          for row in recordingRows {
              guard let uuid = rowUUID(row["id"]),
                    let walkObjectID = row[⟨frozen legacy column⟩] as? NSManagedObjectID,
                    let walk = walksByObjectID[walkObjectID] else { continue }
              index[uuid] = walk
          }
  ```
  > `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:190-226@0172e2b` — the silent exclusion is LOAD-BEARING: `ThreadStore.build` silently `continue`s past any context whose recordingUUID lacks a walkIndex entry, so orphan-walk recordings never surfacing here is a designed filter, not an oversight to "fix". **Caution (the only one):** the iOS relationship is read via a frozen legacy SQL column identifier inherited from the pre-rename era (`Pilgrim/Models/Data/DataManager+VoiceRecording.swift:177-180,205@0172e2b` [EDG-93]); the port carries ONLY the semantic requirement — VoiceRecording has a many-to-one join to its owning Walk, modeled with Android's own Room foreign-key naming — and that frozen identifier and its origin must never appear in Android code, comments, tests, docs, schema, or commit messages.

- **[DAT-64] Two granularities of date index — keep both.** `voiceRecordingTimestampIndex` (`[UUID: Date]`, per-RECORDING instants, feeds the senses block's 30-day window and coordinate lookups) vs `voiceRecordingWalkIndex` (per-WALK dates, feeds thread aggregation); `voiceRecordingPaceIndex` (`[UUID: Double]` wordsPerMinute) is the same two-column shape:
  ```swift
  public static func voiceRecordingTimestampIndex() -> [UUID: Date] {
      guard let rows = try? dataStack.queryAttributes(
          From<VoiceRecording>().select(NSDictionary.self, .attribute(\._uuid), .attribute(\._startDate))
      ) else { return [:] }
  ```
  > `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:159-175,242-258@0172e2b` — collapsing the two date indices breaks callers needing recording-level vs walk-level granularity for different purposes.

### Chip & toggle surfaces — behavioral seams

- **[BEH-78 + UI-7 — consensus] Chip fetch is composable-scoped, not ViewModel-scoped.**
  ```swift
  .task {
          // current() reads CoreStore on the main actor, then detaches for
          // the store read and thread aggregation — the sheet's appearance
          // never blocks on disk I/O.
          threadSuggestions = await ThreadIntentionSuggestions.current()
      }
  ```
  > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:64-74@0172e2b` — SwiftUI's `.task` auto-cancels when the view disappears mid-await; the Android equivalent must be a composable-scoped `LaunchedEffect(Unit)` (cancelled on dismiss), NOT `viewModelScope.launch` — and keyed so it runs once per sheet appearance, not per recomposition. Distinct from the synchronous `.onAppear` used for celestial suggestions. [UI-7] `threadSuggestions` is declared empty (line 15) and populated only by this task — the sheet always renders with ZERO Recurring chips on first frame; a port must reproduce absent-until-loaded, never a placeholder or a blocking synchronous seed.

- **[BEH-79 + UI-4 — consensus] Chip visibility is gated on LIVE text emptiness, per keystroke.** `if text.isEmpty {` wraps all three suggestion tiers — they disappear the instant any character is typed and reappear the instant the field is cleared. > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:39-54@0172e2b` — a one-way "hasStartedTyping" flag keeps chips hidden after clearing, unlike iOS.

- **[BEH-80] Chip tap OVERWRITES the field.** `Button { text = suggestion } label: {` — replace, not append. > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:143-145@0172e2b` — easy to invert since other chip UIs elsewhere append.

- **[BEH-84 + UI-18 — consensus] The Thought Threads toggle NEVER writes the preference directly.**
  ```swift
  @State private var threadsAfterWalks = UserPreferences.threadsAfterWalks.value
  ...
              settingToggle(
                  label: "Thought Threads",
                  description: "Weave recurring themes from your recordings into AI prompts",
                  isOn: $threadsAfterWalks
              ) { newValue in
                  ThreadsBackfill.setEnabled(newValue)
              }
  ```
  > `Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:8,51-57@0172e2b` — unlike every sibling toggle, onChange routes through `ThreadsBackfill.setEnabled`, which owns the reset+resweep-on-enable side effect. A Settings ViewModel writing DataStore directly loses the resweep: toggle off→on would leave the old completed-flag in place, so analysis gaps from the off period are never backfilled. The `@State` is a one-time snapshot at view construction with no observer wiring to external changes — that too is the iOS behavior.

---

## UI / Visual

### Layout

| Container | Children | Alignment / spacing | Citation |
|---|---|---|---|
| [UI-1] Intention sheet body: `VStack(spacing: 0) { ScrollView { VStack(spacing: 0) {` | header + all conditional sections | ALL vertical spacing comes from each child's own top padding — container spacing is collapsed to 0 | `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:23-25@0172e2b` |
| [UI-9] Chip rows (Recurring, Suggested, Recent) | suggestion chips | custom `FlowLayout(spacing: Constants.UI.Padding.small)` — an 8pt-gap wrapping flow defined at IntentionSettingView.swift:396-434; wrap math: `if x + size.width > width && x > 0` (lines 412-433) | `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:141,175,255@0172e2b` |
| [UI-17] VoiceCard toggles | Voice Guide (+conditional Guide Packs nav row), `Divider()`, Dynamic Voice, Auto-transcribe, Thought Threads | Thought Threads is FOURTH and LAST, immediately after Auto-transcribe, no Divider between them, no nested disclosure row (unlike Voice Guide) | `Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:14-57@0172e2b` |
| [UI-21] Summary recordings section: `VStack(alignment: .leading, spacing: Constants.UI.Padding.small)` | recordingsHeader → transcriptionStatusBanner → autoTranscriptionBanner → recording rows | fixed order; the two banners are INDEPENDENTLY gated (two separately-set properties on the same singleton), not mutually exclusive by construction | `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:28-36@0172e2b` |
| [UI-26] Recording rows | `ForEach(Array(walk.voiceRecordings.enumerated()), id: \.element.uuid)` | EAGER ForEach in a plain (non-lazy) VStack — every row renders immediately regardless of scroll | `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:28-36@0172e2b` — wrapping the port in a LazyColumn inherits Compose stability-cascade requirements the eager iOS ForEach never needed |

Port notes: [UI-1] a Compose Column with theme-inherited nonzero arrangement doubles up with child padding. [UI-9] Compose has no first-party equivalent of this exact Layout-protocol row-wrap math — a LazyRow (single-line, scrolls) or a differently-breaking FlowRow changes wrapping and section height. [UI-17] alphabetizing toggles, grouping "AI features", or adding a drill-down row by analogy with Guide Packs all break the deliberately flat, ordered placement. [UI-21] a one-banner-at-a-time sealed-state renderer must special-case the pair to stay behaviorally identical.

### Dimensions

| Element | iOS value | Token | Notes |
|---|---|---|---|
| [UI-2] Intention header top padding | `12` | (magic — flag) | sits between `Constants.UI.Padding.small = 8` (Constants.swift:11@0172e2b) and `.normal = 16` (Constants.swift:12@0172e2b); rounding to the "nearest" token visibly shifts the header — `header.padding(.top, 12)` > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:26-27@0172e2b` |
| [UI-5] Recurring section top padding | `Constants.UI.Padding.normal` (16) | `PilgrimSpacing`-equivalent normal | separates the chip section from the text field > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:40-43@0172e2b` |
| [UI-10] Suggestion chip padding + width cap | `.padding(.horizontal, 12)` `.padding(.vertical, 6)` `.frame(maxWidth: 250)` | (all three magic — flag) | none map to Padding tokens; substituting 8/16 changes chip proportions and per-row wrap count > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:151-153@0172e2b` |
| [UI-8/UI-9] Chip row + section spacing | `Constants.UI.Padding.small` (8) | small | section header→chips VStack spacing and FlowLayout gap |
| [UI-24] Skip-banner vertical padding | `.padding(.vertical, 4)` | (magic — flag) | no Padding token match > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:193-205@0172e2b` |

### Colors

| Role | iOS source | Notes |
|---|---|---|
| [UI-11] Recurring chip capsule | `Color.moss.opacity(0.15)` | background tint is the ONLY differentiator between the three tiers — identical text color/styling otherwise; one default-styled shared chip composable makes the tiers visually indistinguishable > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:156,190,270@0172e2b` |
| [UI-11] Suggested (celestial) chip capsule | `Color.dawn.opacity(0.15)` | same citation |
| [UI-11] Recent (history) chip capsule | `Color.parchmentSecondary.opacity(0.4)` | same citation |
| [UI-8] Recurring section header text | `.fog.opacity(0.5)` | > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:136-139@0172e2b` |
| [UI-19] Model-download progress tint | `.tint(.stone)`, caption in `.fog` | > `Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:59-70@0172e2b` |
| [UI-22/23] Transcribe/Retry affordances | `.foregroundColor(.stone)` | > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:134-141,149-191@0172e2b` |
| [UI-24] Skip banner | `battery.25` icon in `.dawn`, text in `.fog` | > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:193-205@0172e2b` |

### Typography & literal copy

| Role | iOS | Literal string / notes |
|---|---|---|
| [UI-8] Recurring section header | `Constants.Typography.caption` = `Font.custom("Lato-Regular", size: 12)` (Constants.swift:69@0172e2b) | exactly `Recurring` — the shipped string; the softer copy variant is a still-pending iOS fast-follow, do NOT ship it early > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:136-139@0172e2b` |
| [UI-18] Thought Threads toggle | settingToggle label + description | exactly `Thought Threads` / `Weave recurring themes from your recordings into AI prompts` > `Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:51-57@0172e2b` |
| [UI-19] Model download row | caption, `.minimumScaleFactor(0.7)`, `.lineLimit(1)` | `Downloading model \(Int(progress * 100))%` > `Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:59-70@0172e2b` |
| [UI-22] Transcribe affordance | `Label("Transcribe", systemImage: "text.badge.plus")`, caption, `.minimumScaleFactor(0.7)` | > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:134-141@0172e2b` |
| [UI-23] Transcribing banner | caption | `Transcribing \(current)/\(total)` > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:149-191@0172e2b` |
| [UI-23] Retry button | `Constants.Typography.button` = `Font.custom("Lato-Bold", size: 17)` (Constants.swift:68@0172e2b) | `Retry` |
| [UI-24] Skip banner | caption | exactly `Auto-transcription skipped — battery below 20%` with `Image(systemName: "battery.25")` — `20%` is a HARDCODED string disconnected from BatteryGate's threshold constant > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:193-205@0172e2b` |
| [BEH-85] Delete confirm | dialog title | `Delete this recording file? The transcription will be kept.` > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:43-56@0172e2b` |

### Motion

| Animation | iOS | Android note |
|---|---|---|
| [UI-20] Guide Packs row appear/disappear | `.animation(.easeInOut(duration: 0.2), value: voiceGuideEnabled)` — the card's ONLY animation | toggling Thought Threads has NO animation modifier at all; do not add an animated expand/collapse to a row that doesn't exist > `Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:84@0172e2b` |

### Conditional render rules

- **[UI-3]** Exactly one of `voiceRecordingView` / `transcribingView` / (`textInputSection` + suggestion sections) renders at a time, keyed on `recorder.isRecording` / `recorder.isTranscribing`; the Recurring chips exist ONLY inside the third branch — `if recorder.isRecording { voiceRecordingView … } else if recorder.isTranscribing { transcribingView … } else { textInputSection …` > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:29-35@0172e2b`. Hoisting chips outside the gate shows stale chips under the waveform/"Transcribing…" UI.
- **[UI-5]** The Recurring row renders only once `!threadSuggestions.isEmpty` — `if !threadSuggestions.isEmpty { threadSuggestionsSection.padding(.top, Constants.UI.Padding.normal) }` > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:40-43@0172e2b` — absent until loaded, never an empty-state placeholder.
- **[UI-6]** Three tiers render in fixed order — Recurring, then Suggested (gated `UserPreferences.celestialAwarenessEnabled.value`), then Recent (gated `!historyStore.intentions.isEmpty`) — and the guards are NOT mutually exclusive: all three can stack — `if !threadSuggestions.isEmpty { … } if UserPreferences.celestialAwarenessEnabled.value { celestialSuggestions … } if !historyStore.intentions.isEmpty { historySection …` > `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:40-53@0172e2b`. One merged "suggestions" collection loses both the ordering and the per-tier coloring.
- **[UI-19]** VoiceCard surfaces ONLY `.downloadingModel` — no branch for `.transcribing`, `.failed`, or `.completed`, though it observes the same singleton the summary fully renders — `if case .downloadingModel(let progress) = transcriptionService.state { … }` > `Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift:59-70@0172e2b`. A shared "transcription status banner" composable reused in Settings leaks Transcribing/Failed banners where iOS keeps them absent.
- **[UI-22]** The header's Transcribe affordance appears only when `hasUntranscribedRecordings && !isTranscribing` — it DISAPPEARS entirely (not merely disables) once a batch starts, with `isTranscribing` derived from the shared service state — > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:134-141@0172e2b`. A screen-local `isBusy` boolean desyncs when another screen drives a batch concurrently.
- **[UI-23]** Only three of five states render a banner: `.downloadingModel` → "Downloading model N%", `.transcribing` → "Transcribing current/total", `.failed(message)` → the literal message + a Retry button shown only `if hasUntranscribedRecordings`; `default: EmptyView()` — > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:149-191@0172e2b`. iOS can show a failure with NO Retry once nothing is left untranscribed — always-show-Retry diverges.
- **[UI-24]** The skip banner renders on `transcriptionService.autoTranscriptionSkippedReason == .lowBattery` — a single-case enum, so `== .lowBattery` ≡ `!= nil` TODAY, but the enum shape (vs a Bool) is deliberate extensibility — > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:193-205@0172e2b`; [UI-43 + EDG-55 — consensus] `enum AutoTranscriptionSkipReason { case lowBattery }` with `@MainActor @Published var autoTranscriptionSkippedReason: AutoTranscriptionSkipReason?` — absent = no-skip must stay expressed via nullability, not a `none` case > `Pilgrim/Models/TranscriptionService.swift:511-515@0172e2b`.

### Accessibility

- **[UI-27]** None of the 8 UI files in the slice contain ANY `accessibilityLabel`/`accessibilityHint`/`accessibilityElement`/custom-action modifier (exhaustive grep) — every icon+text pairing relies on SwiftUI defaults. SwiftUI exposes an unlabeled `HStack{Image;Text}` as two UNMERGED VoiceOver elements, while `Label(_:systemImage:)` (Transcribe/Retry) merges icon+text automatically — > `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:196-202@0172e2b`. A Compose `Row{Icon();Text()}` port must deliberately choose merged vs unmerged semantics per affordance rather than copying one pattern everywhere.

---

## Data

### Entities

#### `TranscriptContext` [DAT-1]

"Derived, recomputable linguistic context for one voice recording. Never persisted in CoreStore, never exported, never transmitted." — a file-backed JSON model, explicitly NOT a database row. > `Pilgrim/Models/Threads/TranscriptContext.swift:1-5@0172e2b` — the Android equivalent is a file-backed JSON model too, not a Room entity.

| field | type | nullable? | source |
|---|---|---|---|
| schemaVersion | Int | no | `Pilgrim/Models/Threads/TranscriptContext.swift:1-25@0172e2b` |
| recordingUUID | UUID | no | same |
| transcriptHash | String | no | same |
| languageCode | String? | yes | same |
| wordCount | Int | no | same |
| themes | [Theme] | no | same |
| markers | MarkerPack? | yes — nil for entire non-English recordings, never default-zeroed | same + `Pilgrim/Models/Threads/MarkerAnalyzer.swift:69-72@0172e2b` |

[DAT-2] `currentSchemaVersion = 4` — semantics and bump history rendered under Behavior › Derived context; the constant is business logic, "not just a Codable version tag": bump in lockstep with any change to HOW context is derived, and hide (not merely mark) stale files from every UI-facing reader. > `Pilgrim/Models/Threads/TranscriptContext.swift:6-25@0172e2b`

#### `Theme` / `ThemeMention` [DAT-4]

| field | type | notes | source |
|---|---|---|---|
| Theme.lemma | String | exact-lemma identity (no synonym merging, EDG-18) | `Pilgrim/Models/Threads/ThemeExtractor.swift:4-15@0172e2b` |
| Theme.displayTerm | String | most-frequent surface form, deterministic tie-break | same |
| Theme.mentionCount | Int | — | same |
| Theme.salience | Double | **computed as mentionCount / transcript wordCount at extraction time and FROZEN into the stored JSON** (ThemeExtractor.swift:54) — persist the computed value; recomputing lazily from stored fields silently changes historical dossiers after a stoplist/tokenizer update | same |
| Theme.mentions | [ThemeMention] | — | same |
| ThemeMention.start | Int | character offset into the ORIGINAL transcript (excerpt display); unit-pinning applies | same |
| ThemeMention.length | Int | same | same |

[DAT-5] ThemeExtractor's constants and stoplists (25/6/2, walkingDomain, lightNouns, scaffoldLemmas) directly shape what gets serialized into `Theme` — they are effectively part of the persisted schema's SEMANTICS, which is why the v4 bump exists. > `Pilgrim/Models/Threads/ThemeExtractor.swift:19-29@0172e2b`

#### `MarkerPack` [DAT-7]

| field | type | nullable? | source |
|---|---|---|---|
| wordCount | Int | no | `Pilgrim/Models/Threads/MarkerAnalyzer.swift:4-18@0172e2b` |
| absolutistCount | Int | no | same |
| firstPersonCount | Int | no | same |
| insightCount | Int | no | same |
| causationCount | Int | no | same |
| discrepancyCount | Int | no | same |
| futureCount | Int | no | same |
| pastCount | Int | no | same |
| sentiment | Double? | yes | same |
| modalCounts | [String: Int] | no — but decodes leniently to `[:]` when the key is missing (pre-v3 files) | same + `Pilgrim/Models/Threads/MarkerAnalyzer.swift:37-56@0172e2b` |

#### In-memory-only domain types [DAT-11]

`ThreadAppearance` (recordingUUID: UUID, walkUUID: UUID, date: Date, mentionCount: Int, salience: Double), `WalkThread` (lemma: String, displayTerm: String, appearances: [ThreadAppearance]), `ThreadStatus` (firstTime | recurring(walksInWindow: Int)), `SalienceDirection` (rising | steady | fading) — pure in-memory types with NO persistence, rebuilt every time from `TranscriptContext.themes` + the CoreStore-derived walk index. > `Pilgrim/Models/Threads/ThreadStore.swift:3-26@0172e2b` — adding a Room table for WalkThread would over-persist relative to iOS, where `ThreadStore.build(contexts:walks:)` recomputes from scratch per dossier build. Constants [DAT-12]: `recurrenceWindow = 2592000s (30d)`, `directionFloor = 3`, `directionThreshold = 0.25` (`Pilgrim/Models/Threads/ThreadStore.swift:30-32@0172e2b`).

#### `DossierSensesFetchBundle` [DAT-13] and `SensesAssemblyState` [DAT-65]

`DossierSensesFetchBundle`: walkStart: Date, walkEnd: Date, totalAscent: Double, elevationSeries: [DossierSenses.ElevationSample], photos: [DossierSenses.PhotoPin], walkSnapshots: [DossierSenses.WalkSnapshotRow], recordingTimestamps: [UUID: Date], closedLunation: Lunation, moonName: String — the main-actor-gathered bundle build() consumes off-main; route fixes are deliberately excluded and resolved lazily per-recording. > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:4-17@0172e2b`
`SensesAssemblyState`: walkUUID, recordings: [RecordingContext], contextsByUUID: [UUID: TranscriptContext], threads: [WalkThread], walkIndex, backfillComplete: Bool, moonState: Int? — exists purely to stay under a parameter-count lint gate; no persistence. > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:228-239@0172e2b`
**Out-of-slice caution (both findings):** `DossierSenses.swift` and `RecordingContext`'s own declaration are NOT in this slice's 26-file list — `ElevationSample`/`PhotoPin`/`WalkSnapshotRow`/`RouteFix` field shapes and RecordingContext's full shape are inferred-from-usage only; read those files before porting these bundles (see Open questions).

#### `TranscriptionOutput` [DAT-40]

text: String, wordsPerMinute: Double?, flaggedFragments: [String] — engine-independent by design (fakeable); "Empty for every engine that doesn't produce segment-level quality signals." > `Pilgrim/Models/TranscriptionService.swift:6-14@0172e2b`

#### `MemoKey` [DAT-14]

changeCount: Int, walkUUID: UUID, backfillComplete: Bool, moonState: Int?, lunationIndex: Int?, intention: String? — in-memory only, never persisted; full semantics under Behavior › Dossier builder. > `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:36-43@0172e2b`

### Persistence ops (with dispatcher)

- [DAT-15] `TranscriptContextStore.save(_:)` — the SOLE write path: tombstone-checked → JSON-encoded → `.atomic` write → `_changeCount += 1`. Dispatcher: runs synchronously on the CALLER's thread via `writeQueue.sync` — the store never hops itself; every caller is responsible for being off-Main. > `Pilgrim/Models/Threads/TranscriptContextStore.swift:41-53@0172e2b`
- [DAT-22] `delete(recordingUUIDs:)` — tombstone + remove file per UUID, ONE changeCount increment per call. > `Pilgrim/Models/Threads/TranscriptContextStore.swift:93-101@0172e2b`
- [DAT-23] `insertTombstones(for:)` / `clearTombstones(for:)` — memory-only Set mutations under the write queue; no disk I/O despite living in a file-store class. > `Pilgrim/Models/Threads/TranscriptContextStore.swift:107-111,129-133@0172e2b`
- [DAT-24] `removeContext(for:)` — file removal WITHOUT tombstone (feature-off edits). > `Pilgrim/Models/Threads/TranscriptContextStore.swift:113-123@0172e2b`
- [DAT-25] `deleteAll()` — tombstones every UUID recovered from FILENAMES (not decoded contents — a corrupt/undecodable file still gets tombstoned), removes + recreates the directory, re-applies backup exclusion, one changeCount bump:
  ```swift
  for url in contextFileURLs() {
              UUID(uuidString: url.deletingPathExtension().lastPathComponent)
                  .map { tombstones.insert($0) }
          }
  ```
  > `Pilgrim/Models/Threads/TranscriptContextStore.swift:135-150@0172e2b` — derive the tombstone set the same filename-based way.
- [DAT-3] `context(for:matching:)` — the cache-hit read requires BOTH hash match AND current schemaVersion:
  ```swift
  func context(for recordingUUID: UUID, matching transcriptHash: String) -> TranscriptContext? {
      guard let loaded = load(recordingUUID: recordingUUID),
            loaded.transcriptHash == transcriptHash,
            loaded.schemaVersion == TranscriptContext.currentSchemaVersion else { return nil }
      return loaded
  }
  ```
  > `Pilgrim/Models/Threads/TranscriptContextStore.swift:56-61@0172e2b` — hash-only checking silently serves stale-schema derived data as current.
- [DAT-26 + EDG-43 — consensus] `loadAll()` = `loadAllIncludingStaleVersions()` filtered to current schema; the unfiltered variant is used EXCLUSIVELY by the stale-orphan sweep; both sort by `recordingUUID.uuidString` ascending for determinism:
  ```swift
  func loadAll() -> [TranscriptContext] {
      loadAllIncludingStaleVersions()
          .filter { $0.schemaVersion == TranscriptContext.currentSchemaVersion }
  }

  func loadAllIncludingStaleVersions() -> [TranscriptContext] {
      contextFileURLs()
          .compactMap { try? JSONDecoder().decode(TranscriptContext.self, from: Data(contentsOf: $0)) }
          .sorted { $0.recordingUUID.uuidString < $1.recordingUUID.uuidString }
  }
  ```
  > `Pilgrim/Models/Threads/TranscriptContextStore.swift:76-91@0172e2b` — every consumer (threads/dossier/suggestions) goes through `loadAll()`; downstream byte-identical dossier text depends on the lexicographic-UUID-string sort.
- [DAT-27] `orphans(in:keeping:)` — a pure static, no-I/O filter; callers supply the loaded contexts AND the valid set so exactly one directory read happens per build: `static func orphans(in contexts: [TranscriptContext], keeping valid: Set<UUID>) -> [UUID] { contexts.map(\.recordingUUID).filter { !valid.contains($0) } }` > `Pilgrim/Models/Threads/TranscriptContextStore.swift:152-157@0172e2b` — a similarly-named helper that re-queries storage internally doubles I/O per build.
- [EDG-39] Transcript hash: SHA-256 over UTF-8 bytes, lowercase hex via `"%02x"` per byte, no separator/prefix — `SHA256.hash(data: Data(transcript.utf8)).map { String(format: "%02x", $0) }.joined()` > `Pilgrim/Models/Threads/TranscriptContextStore.swift:29-31@0172e2b` — compared only against itself, but casing/format matter for debug-log comparison and future reconciliation tooling.
- Dispatcher inventory for this store's callers [DAT-16..21 cluster]: the store blocks the calling thread (DAT-16, `Pilgrim/Models/Threads/TranscriptContextStore.swift:16,42-53,93-101,118-123,135-150@0172e2b`). Correct off-main call sites: ThreadsBackfill's detached sweep (DAT-20) and the transcription ON-branch `Task.detached` (BEH-59). ON-Main call sites shipping today: `DataManager.deleteObject`'s completion (DAT-17), `DataManager.deleteAll`'s completion (DAT-18 — explicitly "Kept synchronous on purpose"), and the transcription OFF-branch `removeContext` (DAT-19). `ThreadsDossierBuilder.build` is background-callable by documentation only (DAT-21). All flagged for the port decision in Open questions.

### Network endpoints

| Endpoint | Method | Source | In CLAUDE.md ecosystem? |
|---|---|---|---|
| [DAT-49] `argmaxinc/whisperkit-coreml` (HuggingFace Hub repo id, resolved internally by `WhisperKit.download(variant:from:)` — not a literal URL string) | model download | `Pilgrim/Models/TranscriptionService.swift:251-261@0172e2b` | no — and deliberately so: NOT a pilgrimapp.org endpoint, and N/A for Android (whisper.cpp + a different GGML distribution channel per the CLAUDE.md mapping). Do not hunt for an `argmaxinc` reference Android-side; no ecosystem-table change needed (confirm — Open questions) |

No other network endpoint exists anywhere in this slice — the entire threads engine is on-device.

### File I/O paths

- [DAT-43] `<Application Support>/TranscriptContexts/` — the store's root; NOT Documents; created in init; excluded from backup at init and after every deleteAll. > `Pilgrim/Models/Threads/TranscriptContextStore.swift:9-13,22-26,175-179@0172e2b` — Android analog is a `filesDir` subdirectory (the implementation notes fix `transcript_contexts/`), with exclusion via `data_extraction_rules.xml` in BOTH cloud-backup and device-transfer domains + `fullBackupContent`.
- [DAT-44] One JSON file per recording: `<TranscriptContexts>/<uuid.uuidString>.json`, no sharding; `contextFileURLs()` filters purely on the `.json` extension; `deleteAll()` recovers UUIDs purely from the filename stem. > `Pilgrim/Models/Threads/TranscriptContextStore.swift:159-173@0172e2b` — write-path naming and directory-listing parsing must match exactly or files go invisible to the store's own housekeeping. [EDG-40] Swift's `uuid.uuidString` is UPPERCASE hex; Kotlin's `UUID.toString()` is lowercase — Android picks its own scheme, but any cross-platform reconciliation/debug tooling sees case-mismatched names for the same UUID. > `Pilgrim/Models/Threads/TranscriptContextStore.swift:172@0172e2b`
- [DAT-45] `excludeFromBackup()` sets `isExcludedFromBackup = true` as a URLResourceValue on the directory, `try?`-swallowed. > `Pilgrim/Models/Threads/TranscriptContextStore.swift:175-180@0172e2b`
- [DAT-46] Voice recording audio lives under `<Documents>/Recordings/…` (per-recording relativePath, `.m4a`) — a COMPLETELY separate file root from TranscriptContexts; per-file cleanup prunes empty parent directories; `recordingFileCount()` enumerates strictly on the `.m4a` extension. > `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:30-79@0172e2b` — do not conflate the two roots; a different container format on Android must update the counting filter, not just the location.
- [DAT-47] Imports unzip to `<tmp>/pilgrim-import-<uuid>/` (manifest.json, walks/*.json, optionally photos/*.jpg), removed via `defer` on success AND failure. A Stage-5+ archive's top-level `photos/` directory is extracted but its bytes are DELIBERATELY never read or copied — only walk JSON's `photos[]` metadata imports. > `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:105-117@0172e2b`
- [DAT-48] The downloaded Whisper model path is stored RELATIVE to Documents ("iOS may relocate the app container between launches — an absolute path would strand the model after every update. Absolute values (pre-relative installs) still resolve as-is."). > `Pilgrim/Models/TranscriptionService.swift:105-121@0172e2b` — Android `filesDir` is update-stable so the workaround isn't needed verbatim; the underlying rule (never persist an absolute path that can go invalid) stands.

### DataStore / UserDefaults keys

| key | type | default | source |
|---|---|---|---|
| `threadsBackfillCompletedV6` [DAT-50] | Bool | false when absent (`UserDefaults.bool`) | `Pilgrim/Models/Threads/ThreadsBackfill.swift:8@0172e2b` |
| legacy, removed on every call [DAT-51]: `threadsBackfillCompleted`, `threadsBackfillCompletedV2`, `threadsBackfillCompletedV3`, `threadsBackfillCompletedV4`, `threadsBackfillCompletedV5` | — | — | `Pilgrim/Models/Threads/ThreadsBackfill.swift:34-40@0172e2b` |
| `threadsMoonLineLastLunationIndex` [DAT-52] | Int (lunation index) | absent = never reported | `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:26@0172e2b` |
| `whisperModelPath` [DAT-53] | String (relative or absolute path) | absent = not downloaded | `Pilgrim/Models/TranscriptionService.swift:101-103,113-121@0172e2b` |
| `whisperModelVariant` [DAT-53] | String (e.g. `base`) | absent = not downloaded | same |
| `autoTranscribe` [DAT-54] | Bool | **false** | `Pilgrim/Models/Preferences/UserPreferences.swift:94@0172e2b` |
| `threadsAfterWalks` [DAT-54] | Bool | **true** | `Pilgrim/Models/Preferences/UserPreferences.swift:95@0172e2b` |
| `archivedWalkRegistry` [DAT-55] | [String: Double] (walk UUID string → archivedAt epoch-seconds) | `[:]` | `Pilgrim/Models/Preferences/UserPreferences.swift:83-91@0172e2b` |

Key-lifecycle notes:
- [DAT-52] `threadsMoonLineLastLunationIndex` has exactly THREE touch points that must all wire to the same key: write-on-fire (`defaults.set(reported, forKey: moonLineDefaultsKey)`, ThreadsDossierBuilder.swift:258-260 — enforcing the once-per-lunation budget on the moon line), conditional legacy re-arm (performLegacyHygiene, V3-key-gated), and the Delete-All clear (DataManager.swift:855).
- [DAT-53] Path + variant are read as a PAIR — a stored path is trusted only if the paired variant matches the shipped `modelVariant = "base"` [EDG-57]; pre-variant installs (on `tiny`) resolve nil and re-download rather than silently reusing a stale model. Android whisper.cpp needs the same paired-key discipline for any future variant/quantization bump.
- [DAT-56] Delete-All's clearing set is NARROW (see Behavior › Deletion) — the completed-flag SURVIVES a Delete-All.
- [DAT-57] `UserPreferences.reset()` removes the ENTIRE persistent domain (`UserDefaults.standard.removePersistentDomain(forName: Bundle.main.bundleIdentifier!)`) — wiping every key above in one call; it is called NOWHERE in this slice's file list. > `Pilgrim/Models/Preferences/UserPreferences.swift:106-110@0172e2b`

### Singleton init I/O (test-poisoning audit)

- [DAT-58] `TranscriptContextStore.shared` is an eager static singleton whose init synchronously creates the directory and applies backup exclusion — real FS I/O on first `.shared` touch, on whatever thread that is. > `Pilgrim/Models/Threads/TranscriptContextStore.swift:9-13,22-26@0172e2b` — the exact "init-block I/O poisons unit tests" shape from the Stage 2-E memory; the Android `@Singleton` file store must NOT do directory creation in its constructor — lazy root init (the Stage 5-F pattern the implementation notes cite).
- [DAT-59] By contrast `TranscriptionService.init(engineLoader:)` is I/O-free — it only stores an optional test-injected engine-loader closure ("Singleton in production; internal so tests can construct isolated instances"). > `Pilgrim/Models/TranscriptionService.swift:152-156@0172e2b` — the model to follow.
- [DAT-60] `DataManager.walkMonitor` is a `static let` that eagerly builds a live ListMonitor against `dataStack` — itself a force-unwrapped IUO that traps if `DataManager.setup(...)` hasn't run. > `Pilgrim/Models/Data/DataManager.swift:49,866-871@0172e2b` — prefer DataManager's explicit-setup pattern over static-let-with-side-effects for anything threads-adjacent.

---

## Edge cases & invariants

All 96 edge-cases-lens findings, as a scannable invariant inventory. Rows marked **[consensus]** were independently flagged by 2+ lenses (partner tags listed); full quotes and nuances for those live in the Behavior/Data/UI entries cited by tag. Consensus rows first, then single-lens rows; each block sorted by category, then file.

### Cross-lens consensus rows

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **[consensus]** [EDG-65+BEH-34] `Pilgrim/Models/Threads/ThreadStore.swift:88-92@0172e2b` | `let third = max(1, saliences.count / 3)` … `guard early > 0 else { return .steady }` | trend thirds floored at 1; positive early-third before ratio | plain integer division gives a 0-sized third for 3-4 appearances → empty-average/divide-by-zero |
| **[consensus]** [EDG-54+BEH-58] `Pilgrim/Models/TranscriptionService.swift:418-419@0172e2b` | `if await persistTranscriptionOnce(…) { return true }` ×2; `for _ in 0..<2` | exactly 2 attempts (1 retry) for transcription AND WPM persistence | a generic retry helper defaulting to 3-with-backoff changes failure timing the summary UI depends on |
| **[consensus]** [EDG-10+BEH-3] `Pilgrim/Models/Threads/TranscriptNLP.swift:115-133@0172e2b` | `if a == b { return true }` … `embeddingLock` | exact-equality short-circuit before any embedding work; locked per-language cache | plain Map races under concurrent detached calls; dropping the short-circuit invokes (and can fail) lookup for identical words |
| **[consensus]** [EDG-19+BEH-7/BEH-8] `Pilgrim/Models/Threads/ThemeExtractor.swift:48-58@0172e2b` | `.min { ($0.value, $1.key) > ($1.value, $0.key) }!.key` | tuple-swap = desc by count, ASC by key on ties; `!` safe only via minimumMentions≥2 | `compareByDescending{}.thenBy{}` is right; swapping the tie side reverses it; recurs in ThreadStore/Formatter/Suggestions/Directives |
| **[consensus]** [EDG-28+BEH-10] `Pilgrim/Models/Threads/MarkerLexicons.swift:48-49@0172e2b` | `Six families, each an ordered array (not a Set)⏎so a dominant-word tie always resolves to the same word.` | modal word lists are ORDERED arrays; enum order load-bearing | `Map<Family, Set<String>>` makes dominant-word ties nondeterministic downstream |
| **[consensus]** [EDG-32+BEH-11+DAT-8] `Pilgrim/Models/Threads/MarkerAnalyzer.swift:44-56@0172e2b` | `modalCounts = try container.decodeIfPresent(…) ?? [:]` | missing key decodes to empty map, never throws | strict kotlinx.serialization data class throws on pre-existing records when a field is added |
| **[consensus]** [EDG-44+DAT-45+BEH-21] `Pilgrim/Models/Threads/TranscriptContextStore.swift:176-179@0172e2b` | `var url = directory … try? url.setResourceValues(values)` | value-type mutation persists a REAL on-disk backup-exclusion flag | reads as a no-op; Android mechanism (backup-rules XML) is structurally different — re-derive, don't transliterate |
| **[consensus]** [EDG-48+BEH-22] `Pilgrim/Models/Threads/TranscriptContextAnalyzer.swift:19-20@0172e2b` | `flaggedRanges.isEmpty \|\| theme.mentions.contains { !flaggedRanges.contains { $0.contains(mention.start) } }` | theme KEPT if ≥1 mention falls outside every flagged range | flipping the inner negation / using `allSatisfy` requires ALL mentions flagged before dropping — much more lenient |
| **[consensus]** [EDG-49+BEH-22] `Pilgrim/Models/Threads/TranscriptContextAnalyzer.swift:24-25@0172e2b` | `flaggedFragments.reduce(transcript) { $0.replacingOccurrences(of: $1, with: " ") }` | marker text scrubs ALL occurrences of fragment TEXT (even legitimate matches), sequentially | a range-based excision removes only the flagged span — different marker counts |
| **[consensus]** [EDG-51+BEH-54] `Pilgrim/Models/BatteryGate.swift:11-15@0172e2b` | `let wasMonitoring = … device.isBatteryMonitoringEnabled = wasMonitoring` | monitoring scoped to the read, then restored | leaving an equivalent receiver/registration permanently on is a battery-cost regression |
| **[consensus]** [EDG-61+BEH-26+DAT-38] `Pilgrim/Models/Threads/ThreadsBackfill.swift:83-88@0172e2b` | `let hadV3Key = … != nil` then remove all, `guard hadV3Key` | V3 presence captured BEFORE blanket key removal | check-after-delete always reads false, silently skipping the moon-line re-arm |
| **[consensus]** [EDG-64+BEH-35] `Pilgrim/Models/Threads/ThreadStore.swift:61@0172e2b` | `sorted { ($0.date, $0.recordingUUID.uuidString) < ($1.date, $1.recordingUUID.uuidString) }` | appearances deterministically ordered on ties | date-only sort can flip which appearance is "first" (origin claims) across runs |
| **[consensus]** [EDG-67+BEH-36+DAT-14] `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:36-43@0172e2b` | `MemoKey { changeCount; walkUUID; backfillComplete; moonState; lunationIndex; intention }` | six fields; lunation + intention close changeCount-invisible gaps | changeCount-only cache serves a stale dossier across a moon rollover / intention edit |
| **[consensus]** [EDG-77+UI-40] `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:249-255@0172e2b` | `.sorted { ($0.walks, $1.thread.lemma) > ($1.walks, $0.thread.lemma) }.prefix(maxAbsenceLines)` | quiet lines: walks desc, lemma asc, cap 2; verbatim template | tuple-swap trap again; template wording is prompt content |
| **[consensus]** [EDG-80+BEH-50+UI-31] `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift:39-42@0172e2b` | `.sorted{…}.map { "walk with '\($0.0)'" }.filter { seen.insert($0).inserted }.prefix(limit)` | sort → template → dedup-by-phrase → cap, exactly | Kotlin: `seen.add(it)` IS the predicate; reordering the pipeline drops eligible chips |
| **[consensus]** [EDG-84+BEH-72] `Pilgrim/Models/Prompt/AttentionDirectives.swift:97-105@0172e2b` | `"…'\(surface)' surfaces again…"` (exact tier only) | only the exact-surface tier says "again" | collapsing three templates to one claims literal repetition the design avoids |
| **[consensus]** [EDG-94+BEH-62+DAT-63] `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:233-235@0172e2b` | `(raw as? String).flatMap(UUID.init(uuidString:))` | Optional.flatMap; malformed id → silent row drop, all 4 queries identically | Kotlin `UUID.fromString` THROWS — needs runCatching; per-query divergence reintroduces the inconsistency this helper prevents |
| **[consensus]** [EDG-74+BEH-44] `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:94,102@0172e2b` | `dominantFamily == nil \|\| count > dominantFamily!.count` | force-unwrap safe ONLY via left-to-right `\|\|` short-circuit | reordering the operands (plausible cleanup) crashes on first non-nil comparison |
| **[consensus]** [EDG-12+BEH-4+DAT-6] `Pilgrim/Models/Threads/TranscriptNLP.swift:147-151@0172e2b` | `lightNouns: Set<String> = ["thing", …, "day", "days", "area"]` | 18-word theme-side stoplist incl. the 2026-08-25 additions, verbatim | missing day/days/area reproduces the exact bug that forced schema v3→v4 |
| **[consensus]** [EDG-13+BEH-4+DAT-6] `Pilgrim/Models/Threads/TranscriptNLP.swift:153-168@0172e2b` | `scaffoldLemmas: Set<String> = ["be", …, "ought", "wish"]` | 35-word directive-side stoplist, verbatim; separate consumer from lightNouns | merging the two lists over/under-filters one of the two features; modals route to markers, never themes |
| **[consensus]** [EDG-14+BEH-5] `Pilgrim/Models/Threads/ThemeExtractor.swift:19,33@0172e2b` | `minimumWords = 25` … `guard wordCount >= minimumWords else { return [] }` | <25 words → zero themes, silently | a short note must not yield a degenerate single-word theme |
| **[consensus]** [EDG-15+BEH-5] `Pilgrim/Models/Threads/ThemeExtractor.swift:20@0172e2b` | `static let maxThemes = 6` | ≤6 themes per transcript | cap drift changes dossier line rendering and UI density |
| **[consensus]** [EDG-17+BEH-6] `Pilgrim/Models/Threads/ThemeExtractor.swift:25-29@0172e2b` | `walkingDomain: Set<String> = ["walk", …, "left", "right"]` | 17-word narration-vocabulary suppression, verbatim | without it every walk's #1 theme is the walk itself |
| **[consensus]** [EDG-21+BEH-9] `Pilgrim/Models/Threads/MarkerLexicons.swift:3-13@0172e2b` | `the 19-word dictionary from Al-Mosaiwi & Johnstone 2018 … LIWC's proprietary word lists must never be copied here` | absolutist list verbatim; LIWC prohibition is a LICENSING constraint | substituting a counting library or "equivalent" list is a legal + parity break |
| **[consensus]** [EDG-22+BEH-9] `Pilgrim/Models/Threads/MarkerLexicons.swift:15@0172e2b` | `["i", "me", "my", "mine", "myself"]` | 5-word first-person set, verbatim | forgetting `mine` under-counts self-focus % |
| **[consensus]** [EDG-23+BEH-9] `Pilgrim/Models/Threads/MarkerLexicons.swift:17-22@0172e2b` | `insight: Set<String> = ["realize", …, "sensed"]` | 20-word insight set, verbatim | any add/drop changes insightCount for identical transcripts |
| **[consensus]** [EDG-24+BEH-9] `Pilgrim/Models/Threads/MarkerLexicons.swift:24-27@0172e2b` | `causation: Set<String> = ["because", …, "led"]` | 14-word causation set, verbatim | drives causationCount in the dossier |
| **[consensus]** [EDG-25+BEH-9] `Pilgrim/Models/Threads/MarkerLexicons.swift:29-32@0172e2b` | `discrepancy: Set<String> = ["should", …, "instead"]` | 14-word discrepancy set, verbatim; 7 overlap modalFamilies | "deduplicating" across channels desyncs discrepancyCount |
| **[consensus]** [EDG-26+BEH-9] `Pilgrim/Models/Threads/MarkerLexicons.swift:34-37@0172e2b` | `futureMarkers: Set<String> = ["will", …, "planning"]` | 12-word future set, verbatim; `will` double-counts with modals | feeds temporalLean directly |
| **[consensus]** [EDG-27+BEH-9] `Pilgrim/Models/Threads/MarkerLexicons.swift:39-42@0172e2b` | `pastMarkers: Set<String> = ["was", …, "before"]` | 12-word past set, verbatim; NO present list exists | inventing a "present" lexicon adds a signal iOS never computes |
| **[consensus]** [EDG-29+BEH-10] `Pilgrim/Models/Threads/MarkerLexicons.swift:53-64@0172e2b` | `possibility=[can,could]; obligation=[should,must,ought]; counterfactual=[would]; tentative=[might,may]; intention=[will]; desire=[want,need,wish]` | six families, exact case order + exact array order | Kotlin enum must declare cases in this sequence with ordered Lists |
| **[consensus]** [EDG-37+BEH-13+DAT-9] `Pilgrim/Models/Threads/MarkerAnalyzer.swift:98-99@0172e2b` | `degrades to a near-constant score once⏎the tagged string passes roughly 150 words` | ~150-word NLTagger degradation is WHY sentiment is per-sentence | the constant is engine-specific — re-derive chunking against VADER, don't copy 150 |
| **[consensus]** [EDG-38+BEH-14+DAT-2] `Pilgrim/Models/Threads/TranscriptContext.swift:21-25@0172e2b` | `static let currentSchemaVersion = 4` | v3 added modalCounts; v4 = stoplist tightening; either bump forces re-analysis | treating a stoplist change as "compatible" leaves cached derived data stale forever |
| **[consensus]** [EDG-55+UI-43] `Pilgrim/Models/TranscriptionService.swift:511-515@0172e2b` | `enum AutoTranscriptionSkipReason { case lowBattery }` + optional @Published | absent = no-skip via NULLABILITY, single case today | a Bool or a `none` case breaks extensibility / banner checks |
| **[consensus]** [EDG-57+DAT-53] `Pilgrim/Models/TranscriptionService.swift:101-103@0172e2b` | `modelVariant = "base"` + paired path/variant keys | path trusted only when paired variant matches | a future variant bump silently reuses a stale on-disk model without the pair check |
| **[consensus]** [EDG-58+BEH-25+DAT-50/51] `Pilgrim/Models/Threads/ThreadsBackfill.swift:8,34-40@0172e2b` | `threadsBackfillCompletedV6`; legacy=[…V1…V5] | key RENAME (not value flip) is the re-arm mechanism; legacy chain removed every call | Android versions its OWN flag on its OWN lexicon changes; the pattern, not the literal V6, ports |
| **[consensus]** [EDG-60+BEH-28+DAT-37] `Pilgrim/Models/Threads/ThreadsBackfill.swift:73@0172e2b` | `static let batchSize = 25` | 25-item batches = gate-recheck + yield cadence | a different batch size changes low-battery interruption behavior |
| **[consensus]** [EDG-62+BEH-33/34+DAT-12] `Pilgrim/Models/Threads/ThreadStore.swift:17-20,30-32@0172e2b` | `recurrenceWindow = 30 * 86400; directionFloor = 3; directionThreshold = 0.25` | the three numbers that gate "recurring"/"rising"/"fading" claims | any drift changes which threads speak in the dossier |
| **[consensus]** [EDG-66+DAT-52] `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:26,63,66@0172e2b` | `threadsMoonLineLastLunationIndex`; `// (-1, -1) is the schema's unset sentinel, not a place.` | same key cross-launch; (-1,-1) = no location, nil coordinate | plotting the sentinel puts photos on null island; key must match all three touch points |
| **[consensus]** [EDG-72+BEH-43] `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:5-16@0172e2b` | `densityFloorWords=100; baselineFloorRecordings=5; absenceWindow=30d; minimumAbsenceWalks=2; maxAbsenceLines=2; paceDifferenceThreshold=0.15; modalBaselineFloorWalks=3; modalRemarkableMinCount=10; modalRemarkableRateMultiple=2.0` | all nine formatter thresholds exact | each gates whether/how a dossier clause fires |
| **[consensus]** [EDG-79+BEH-49+UI-28] `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift:10-18@0172e2b` | `pendingFieldGate = false` + `30d / 2 / 2` | false = ENABLED (cleared to ship 2026-08-24) | inverting the gate's sense (or omitting it) silently kills or un-gates the chip surface |
| **[consensus]** [EDG-7+DAT-10] `Pilgrim/Models/Threads/TranscriptNLP.swift:73-79@0172e2b` | `The single tokenizer for every word count and density in the feature` | ONE tokenizer for all denominators | any second split() diverges absolutist %/self-focus %/salience |
| **[consensus]** [EDG-41+BEH-17+DAT-15] `Pilgrim/Models/Threads/TranscriptContextStore.swift:38-44@0172e2b` | `guard !tombstones.contains(…) else { return true }` | tombstone-blocked save = accounted (true); false = real failure only | "honest false" makes backfill accounting retry forever |
| **[consensus]** [EDG-42+BEH-20+DAT-23/24] `Pilgrim/Models/Threads/TranscriptContextStore.swift:93-118@0172e2b` | `tombstones.insert(uuid)` vs `Removes a stored context without tombstoning` | delete()=tombstone+remove; insertTombstones()=tombstone only; removeContext()=remove only | one merged "delete" helper blocks legit re-analysis OR resurrects data mid-bulk-delete |
| **[consensus]** [EDG-43+DAT-26] `Pilgrim/Models/Threads/TranscriptContextStore.swift:79-81@0172e2b` | `loadAll() … .filter { $0.schemaVersion == currentSchemaVersion }` | consumers use loadAll(); unfiltered variant is sweep-only | one "load everything" query either leaks stale data to UI or blinds the sweep |
| **[consensus]** [EDG-47+BEH-23+DAT-42] `Pilgrim/Models/Threads/TranscriptContextAnalyzer.swift:58-59@0172e2b` | `Every occurrence, not just the first — repeated hallucination is the⏎canonical Whisper failure shape.` | find-all loop over each fragment | `indexOf` (first match) leaves later hallucinated repeats unflagged |
| **[consensus]** [EDG-59+BEH-25] `Pilgrim/Models/Threads/ThreadsBackfill.swift:9-33@0172e2b` | `V6 … adds day, days, area to SpokenStoplist.lightNouns (schema v3→v4)` | every stoplist/extractor change pairs with a backfill re-arm | changing the Android stoplist without bumping its own version leaves old analyses stale forever |
| **[consensus]** [EDG-70+BEH-38] `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:154-171@0172e2b` | `preBuildChangeCount + ownDeleteWrite + freshlySaved.count` | memo baseline = own confirmed writes, never a re-read | re-sampling the counter post-build absorbs a concurrent external write → stale cache-hit next call |
| **[consensus]** [EDG-73+UI-33/34/35] `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:18-41@0172e2b` | `"absolutist words %.1f%% over %d words"` … `parts.joined(separator: "; ")` | every template fragment, precision (%.1f vs %.2f), and the `; ` join are prompt content | "cosmetic" wording changes alter what the LLM reads verbatim |
| **[consensus]** [EDG-76+UI-39] `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:184,186,201-214@0172e2b` | `" — \(walks) walk\(walks == 1 ? "" : "s") in the last 30 days"` | switch-plus-optional-appends line assembly; exact plural branch; markdown headers verbatim | `if (walks == 1) "" else "s"` must match exactly; headers are LLM section cues |
| **[consensus]** [EDG-81+BEH-51] `Pilgrim/Models/Threads/ThreadIntentionSuggestions.swift:46-52,66-71@0172e2b` | `changeCount is captured BEFORE loadAll so a mid-read mutation leaves the memo stale` | memo key = (changeCount, day); capture-before-read ordering | changeCount-only key serves yesterday's chips all day |
| **[consensus]** [EDG-88+BEH-75] `Pilgrim/Models/Prompt/PromptAssembler.swift:177-188@0172e2b` | `"…interpret them gently, never produce clinical or diagnostic language…"` | the full conditional contract-line inventory, verbatim | omitting the dossier-gated caution risks clinical-sounding LLM output the design forbids |
| **[consensus]** [EDG-93+DAT-62] `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:177-180,205@0172e2b` | — (quote withheld per no-legacy-reference policy) | recording→walk many-to-one join semantics only; Android uses its own Room FK naming | the frozen legacy identifier and its origin must never appear in Android code/comments/tests/docs/schema |
| **[consensus]** [EDG-1+BEH-2] `Pilgrim/Models/Threads/TranscriptNLP.swift:9@0172e2b` | `static let relatedDistanceCeiling = 0.95` | cosine-distance ceiling for related() | does NOT transfer to the planned synset substrate — recalibrate, don't copy |
| **[consensus]** [EDG-5+BEH-1] `Pilgrim/Models/Threads/TranscriptNLP.swift:53@0172e2b` | `guard surface.count > 2 else { return true }` | strict >2 surface-length floor | `>=2` changes which short words are excluded from every lemma count |
| **[consensus]** [EDG-16+BEH-5] `Pilgrim/Models/Threads/ThemeExtractor.swift:21,44@0172e2b` | `.filter { $0.value.count >= minimumMentions }` | ≥2 mentions per lemma | off-by-one surfaces one-off word choices as themes |
| **[consensus]** [EDG-34+BEH-12] `Pilgrim/Models/Threads/MarkerAnalyzer.swift:69-72@0172e2b` | `guard languageCode == "en" else { return nil }` | exact-string English gate; nil markers otherwise | `en-US` variants either never fire (naive port) or produce data iOS never computes |
| **[consensus]** [EDG-50+BEH-53] `Pilgrim/Models/BatteryGate.swift:16@0172e2b` | `level < 0 \|\| level > 0.2 \|\| .charging \|\| .full` | unknown allows; strict >20%; exactly 20% blocks | `>=` at the boundary, or inverted unknown-polarity, silently changes which walks defer |
| **[consensus]** [EDG-52+BEH-56+DAT-40] `Pilgrim/Models/TranscriptionService.swift:55-62@0172e2b` | `.filter { $0.compressionRatio > 2.4 \|\| $0.noSpeechProb > 0.6 }` | 2.4 / 0.6 thresholds; noSpeechProb branch is DEAD on iOS (WhisperKit hardcodes 0) | whisper.cpp may make the 0.6 branch live — behavior iOS never exercises (Open questions) |
| **[consensus]** [EDG-69+BEH-39+DAT-30] `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:109@0172e2b` | `walkIndex.isEmpty && !all.isEmpty ? Set<UUID>() : …` | empty index + non-empty store = failed read, NOT orphanhood | without it a transient index failure mass-deletes every stored analysis |
| **[consensus]** [EDG-75+BEH-45+UI-36] `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:164-170@0172e2b` | `"modal lean: … '\(word)' ×\(count)"` | count≥10 AND rate≥2.0×own-baseline; `×` is U+00D7 | ASCII `x` is a silent prompt divergence; missing either gate over-fires a "silent by default" signal |
| **[consensus]** [EDG-78+BEH-47+UI-41] `Pilgrim/Models/Threads/ThreadsDossierFormatter.swift:271-279@0172e2b` | `guard restMean > 0 else { return nil }` | non-empty both sides + positive restMean before dividing; two phrasings only | skipping restMean>0 divides by zero; leading comma-space punctuation completes the host sentence |
| **[consensus]** [EDG-86+BEH-71] `Pilgrim/Models/Prompt/AttentionDirectives.swift:24-31,143-144@0172e2b` | `guard context.recordings.count >= 2` … `.prefix(maxDirectives)` | ≥2 recordings; 5 detectors, cap 4 — array order decides the drop | reordering the literal array changes which directive is cut when all five fire |

### Single-lens rows

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| [EDG-9] `Pilgrim/Models/Threads/TranscriptNLP.swift:104-111@0172e2b` | `cursor = range.upperBound` | search cursor advances past each match | `indexOf` without a cursor re-finds the first occurrence of repeated words |
| [EDG-20] `Pilgrim/Models/Threads/ThemeExtractor.swift:59-60@0172e2b` | `.prefix(maxThemes)⏎.map { $0 }` | ArraySlice→Array ceremony, Swift-only | not a bug — Kotlin `.take()` already returns List; a literal port adds a no-op |
| [EDG-46] `Pilgrim/Models/Threads/TranscriptContextAnalyzer.swift:65@0172e2b` | `text.distance(from: text.startIndex, to: range.lowerBound)` | offsets count GRAPHEME CLUSTERS, not UTF-16 units | Kotlin-native indexing yields different Ints for emoji/combining text — misaligned containment checks |
| [EDG-92] `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:31,44,53,65,76@0172e2b` | `.urls(for: .documentDirectory, …).first!` | five sites assume a guaranteed writable app-private dir | use non-null `context.filesDir`, never nullable `getExternalFilesDir`, never `!!` |
| [EDG-53] `Pilgrim/Models/TranscriptionService.swift:139@0172e2b` | same unwrap, recurs at 139/291/382 | same assumption in TranscriptionService | same guidance |
| [EDG-3] `Pilgrim/Models/Threads/TranscriptNLP.swift:27-35@0172e2b` | `classes: Set<NLTag> = [.noun, .verb, .adjective]` | the default parameter IS behavior; ThemeExtractor passes `[.noun]` | inlining one fixed set breaks one of the two callers |
| [EDG-4] `Pilgrim/Models/Threads/TranscriptNLP.swift:39-63@0172e2b` | `Running cursor: … keeps this linear` | linear offset accumulation; grapheme unit | quadratic re-measuring, or a UTF-16 mix, corrupts stored mention offsets |
| [EDG-6] `Pilgrim/Models/Threads/TranscriptNLP.swift:54-55@0172e2b` | `.0?.rawValue.lowercased() ?? surface` | lemma falls back to lowercased surface | null-lemma-as-skip silently drops mentions |
| [EDG-30] `Pilgrim/Models/Threads/MarkerLexicons.swift:57-64@0172e2b` | `.desire: ["want", "need", "wish"]` | cross-channel double-counting is deliberate; inflected discrepancy forms are modal-exempt | "fixing" the overlap shrinks counts vs iOS |
| [EDG-31] `Pilgrim/Models/Threads/MarkerLexicons.swift:66-71@0172e2b` | `modalFamilies.first { $0.value.contains(word) }?.key` | every modal word in exactly ONE family | a two-family word makes lookup iteration-order-dependent |
| [EDG-35] `Pilgrim/Models/Threads/MarkerAnalyzer.swift:76@0172e2b` | `words.reduce(0) { $0 + (lexicon.contains($1) ? 1 : 0) }` | boolean-to-int reduce ≡ count-where | a mis-translated ternary inverts the count |
| [EDG-36] `Pilgrim/Models/Threads/MarkerAnalyzer.swift:81@0172e2b` | `modalCounts[word, default: 0] += 1` | subscript-with-default increment | `map[word]!! + 1` throws on first occurrence — use getOrDefault/merge |
| [EDG-40] `Pilgrim/Models/Threads/TranscriptContextStore.swift:172@0172e2b` | `"\(uuid.uuidString).json"` | Swift uuidString is UPPERCASE hex | Kotlin `UUID.toString()` is lowercase — reconciliation tooling sees mismatched names |
| [EDG-45] `Pilgrim/Models/Threads/TranscriptContextStore.swift:166-169@0172e2b` | `load … Data(contentsOf:)` with no queue | reads are UNSERIALIZED; only mutations lock; `.atomic` prevents torn files | all-methods-Mutex changes concurrency (reads block writes); no-locks-at-all reintroduces races (Open questions) |
| [EDG-63] `Pilgrim/Models/Threads/ThreadStore.swift:59-60@0172e2b` | `.min { … > … }?.key ?? lemma` | max-count/alpha-first display term with a lemma FALLBACK (unlike ThemeExtractor's `!`) | keep both shapes; a naive min/max translation picks a different term on ties |
| [EDG-68] `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:177-185@0172e2b` | `-> String??` | outer optional = cache-present; inner = possibly-nil dossier | Kotlin `String?` can't distinguish not-cached from cached-nil → rebuilds every legitimate-nil walk |
| [EDG-71] `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:126@0172e2b` | `Dictionary(uniqueKeysWithValues:…)` | duplicate recordingUUID contexts TRAP on iOS | `associateBy` keeps last silently, masking a data-integrity bug (Open questions) |
| [EDG-95] `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:81-84,289-307@0172e2b` | `reports false both when the transaction fails AND when the recording row no longer exists` | both collapse to don't-analyze at the trigger site | a sealed NotFound/TransactionError port must still collapse both at THIS call site |
| [EDG-91] `Pilgrim/Models/PromptGenerator.swift:83-91@0172e2b` | `truncatedAtWordBoundary(maxLength: Int = 200)` … `+ "..."` | 200 default; "..." appended with or without a boundary; grapheme-counted | UTF-16 `take(200)` can split a surrogate pair Swift never would |
| [EDG-96] `Pilgrim/Models/Data/DataManager+VoiceRecording.swift:34,38,46-48,55,57-59,78@0172e2b` | `try? FileManager.default.removeItem(at: url)` | cleanup helpers swallow ALL errors, no logging | surfacing exceptions crashes call sites that assume no-throw |
| [EDG-87] `Pilgrim/Models/Prompt/PromptAssembler.swift:106-108@0172e2b` | `Locale(identifier: "en").localizedString(forLanguageCode:)` | language names always English for the LLM | device-default Locale sends "français" into an English prompt |
| [EDG-39] `Pilgrim/Models/Threads/TranscriptContextStore.swift:29-31@0172e2b` | `SHA256 … String(format: "%02x", $0)` | SHA-256, lowercase hex, no delimiter | different algo/casing breaks log comparison + future reconciliation |
| [EDG-8] `Pilgrim/Models/Threads/TranscriptNLP.swift:90-99@0172e2b` | `Offsets for wordTokens' own output — not a second tokenizer.` | offsets derived from the tokenizer's own tokens | two independent passes disagree on combining-mark boundaries |
| [EDG-11] `Pilgrim/Models/Threads/TranscriptNLP.swift:136-139@0172e2b` | `a field-confirmed bug ("was / have / can / think" as real-device themes)` | the stoplists exist because of a shipped bug | omitting them re-ships the bug |
| [EDG-18] `Pilgrim/Models/Threads/ThemeExtractor.swift:35-41@0172e2b` | `Per-transcript synonym merging split cross-walk identity` | exact-lemma identity in v1 — no clustering | an "improved" synonym merge reintroduces false first-time claims |
| [EDG-89] `Pilgrim/Models/Prompt/PromptAssembler.swift:41,60-61@0172e2b` | `"**The walker's intention:** …"` + closing-instruction echo | intention appears TWICE, different phrasing each | porting one occurrence under-weights the intention by design intent |
| [EDG-90] `Pilgrim/Models/Prompt/PromptAssembler.swift:156-169@0172e2b` | `" One clearing was found, reached in the \(timeOfDay)."` | wander/Seek framing; zero/one/many clearing branch | collapsing exactly-one into many yields "1 clearings were found." |
| [EDG-2] `Pilgrim/Models/Threads/TranscriptNLP.swift:23@0172e2b` | `confidence >= 0.5 else { return nil }` | 0.5 language-confidence floor | ML Kit's confidence scale differs — validate, don't blind-copy (plan pins ≥0.5) |
| [EDG-33] `Pilgrim/Models/Threads/MarkerAnalyzer.swift:61-62@0172e2b` | `futureCount >= 3, futureCount >= pastCount * 2` | temporal lean needs floor 3 AND 2× dominance | ratio-only flips low-count transcripts to future/past |
| [EDG-56] `Pilgrim/Models/TranscriptionService.swift:350-353@0172e2b` | `attempted > 0 && transcriptionFailures == attempted` | .failed only on TOTAL transcription failure or any persistence failure | `> 0` instead of `== attempted` shows Retry on partial success |
| [EDG-82] `Pilgrim/Models/Prompt/AttentionDirectives.swift:9-10,44,49,56@0172e2b` | `(0..<movingThreshold).contains(speed)` | 0.3 m/s moving threshold; ≥30 samples; ≥3 min; > explained; range EXCLUDES negative speeds | `speed < 0.3` alone accepts invalid negative GPS speeds |
| [EDG-83] `Pilgrim/Models/Prompt/AttentionDirectives.swift:63-77@0172e2b` | `abs(change) >= 0.2` | ≥30 MOVING samples; first>0; ±20%; mirror templates differ in two words | copy-paste can leave both branches identical |
| [EDG-85] `Pilgrim/Models/Prompt/AttentionDirectives.swift:134-139@0172e2b` | `counts.filter({ $0.value >= 3 })` | recurring-word floor 3; tie-break applied TWICE (lemma, then surface) | tied counts pick a different word without both applications |

---

## Android implementation notes

Reusable utilities verified on Android HEAD `07f1ec47`:

- **DI**: one Hilt module per concern under `di/` — follow `VoicePreferencesModule.kt` for the DataStore-backed `ThreadsPreferences` (new `ThreadsPreferencesModule.kt`) and `TranscriptionModule.kt` naming for a `ThreadsModule.kt`.
- **JSON**: inject the existing configured `Json` (`di/NetworkModule.kt:66 provideJson`, `explicitNulls=false` precedent from Phase 19; `di/PilgrimJsonModule.kt` is the pretty-print variant for `.pilgrim` packages) — do NOT construct a new `Json` in the context store. (The lenient-decode requirement — DAT-8/EDG-32 — maps to `ignoreUnknownKeys` + defaulted fields on the Kotlin model.)
- **Workers**: `audio/TranscriptionScheduler.kt` (scheduler-wrapper pattern) + `audio/OrphanSweeperWorker.kt` + `widget/WidgetRefreshWorker.kt`/`WidgetRefreshScheduler.kt` are the CoroutineWorker precedents; `WorkManagerTranscriptionSchedulerTest` is the mandatory builder-test precedent (Expedited+BatteryNotLow crash class).
- **File I/O**: `data/voice/VoiceRecordingFileSystem.kt` owns filesDir subdirectory discipline — mirror its shape for `transcript_contexts/`; lazy root init, no per-call mkdirs (Stage 5-F lesson). This directly answers the DAT-58 init-I/O trap.
- **Prompt layer**: `core/prompt/AttentionDirectives.kt`, `PromptAssembler.kt`, `PromptGenerator.kt`, `ContextFormatter.kt` exist at v1.10.0 parity — the port UPGRADES these in place (lemma-based v2). `MlKitImageLabelerClient`/`MlKitTextRecognizerClient`/`MlKitFaceDetectorClient` in the same package define the wrapper pattern `MlKitLanguageIdClient` must follow.
- **UI**: `ui/walk/IntentionSettingSheet.kt` has `ChipSection` (Suggested/Recent shelves, empty-field gating, `resetKey` remember pattern); `ui/settings/voice/VoiceCard.kt` has `SettingToggle` (label + REQUIRED description params); `ui/walk/VoiceRecordingsSection.kt` has the `PendingTranscriptionSubstate` per-row state machine (Android-original — the battery-skip banner must reconcile with it, never show Queued while skipped).
- **Entities/DAO**: `VoiceRecording` (uuid unique-indexed, startTimestamp, transcription, wordsPerMinute), `RouteDataSample` (indexed walk_id+timestamp, horizontalAccuracyMeters), `Walk` (intention, weatherCondition 10-value vocab, weatherTemperature), `WalkPhoto` (takenAt + captured coords). DAOs lack cross-walk timestamp-window queries — U7/U9 add projections (`WalkLite`, recording→walk index) per the plan.
- **Location discipline**: `location/FusedLocationSource.kt:206` — 100 m accuracy ceiling already enforced (cite for coordinate hygiene).
- **Battery**: no BatteryGate equivalent exists; a `BatteryManager`-based runtime check is new; the transcription request is EXPEDITED (constraint-based BatteryNotLow forbidden on it — Stage 2-F). This is why iOS's runtime-check-then-set-skip-reason shape (BEH-82) ports as a runtime check, not a WorkManager constraint.
- **Importer**: `data/pilgrim/` package (builder/ + importer area, `ui/settings/data/PilgrimPackageGateway.kt`) — the generation-bump hook (BEH-66/DAT-35) lands at the importer's replace path.
- **Backup rules**: `app/src/main/res/xml/data_extraction_rules.xml` has BOTH cloud-backup and device-transfer domains with an existing narrow exclude precedent (`share_device_token.preferences_pb`) — `transcript_contexts/` must be excluded in BOTH + the `fullBackupContent` document. This is the Android answer to BEH-21/DAT-45's re-apply-on-recreate requirement (a static declaration needs no re-apply, but must cover the real storage path).
- **Moon math**: `core/celestial/MoonCalc.kt` + `MoonPhase.kt` (NOT "LunarPhase" — that's the iOS name); the new-moon epoch is private and needs `internal` promotion for LunationCalendar (U9, out of this spec's slice).

Kotlin-side plan decisions already fixed (do not re-litigate in the spec; noted inline as "Android divergence (planned)" where iOS differs):

- WordNet+Morphy dictionary POS with MANDATORY `scaffoldLemmas` filter on the THEME path (Android-original compensation — iOS filters themes only through walkingDomain+lightNouns because NLTagger is contextual).
- Synset-based `related()` replacing NLEmbedding (the 0.95 ceiling does NOT transfer).
- VADER-lite sentiment (numeric non-parity accepted).
- ML Kit language-id ≥ 0.5.
- Offsets measured in what the plan calls "the single tokenizer" — Swift counts grapheme Characters; Kotlin must pick and pin ONE unit consistently for hash/mention offsets.

Mapping deltas vs `pilgrim-android/CLAUDE.md` base table — delta entries only:

- iOS `NLTagger`/`NLEmbedding`/`NLLanguageRecognizer` (Natural Language framework) → no single Android analogue in the base table: WordNet+Morphy (themes/lemmas) + synsets (relatedness) + ML Kit (language-id) + VADER-lite (sentiment), per the fixed plan decisions above.
- iOS `UIDevice` battery toggle-around-read → Android `BatteryManager` system service, plain read, no monitoring toggle (BEH-54/EDG-51).
- iOS per-directory `isExcludedFromBackup` URLResourceValue → Android `data_extraction_rules.xml` + `fullBackupContent` static declarations (BEH-21).
- iOS WhisperKit segment `compressionRatio`/`noSpeechProb` → whisper.cpp JNI must expose equivalent per-segment signals or `flaggedFragments` is permanently empty (BEH-56; Open questions).
- iOS `dispatchPrecondition(.onQueue(.main))` → no Kotlin analogue; replace with main-confined state or an explicit redesign (BEH-24).

---

## Open questions

Decisions the port must make explicitly — each traced to the finding that raised it:

1. **The dead `noSpeechProb` branch.** iOS ships `noSpeechProb > 0.6` as dead code (WhisperKit 0.16 hardcodes 0; compressionRatio is the only live signal). whisper.cpp may populate a real no_speech_prob — porting both thresholds makes the 0.6 branch live on Android, behavior iOS never exercises; porting compressionRatio-only is stricter parity. Decide and document. (`Pilgrim/Models/TranscriptionService.swift:55-62@0172e2b`, EDG-52/BEH-56.)
2. **Does the whisper.cpp JNI binding expose per-segment quality signals at all?** If not, `flaggedFragments` is permanently `[]` and TranscriptContextAnalyzer's hallucination suppression is a permanent no-op — a real parity gap to surface, not silently accept. (`Pilgrim/Models/TranscriptionService.swift:6-14@0172e2b`, DAT-40.)
3. **`filterLocallyArchived` over-clear.** `clearTombstones` fires for recordings of walks filtered out as already-archived, and — because the success check is on the outer Result — for ALL decoded UUIDs even when some walks individually failed to save. Preserve bug-for-bug or fix? (`Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:82-83,277-287@0172e2b`, BEH-68/DAT-35.)
4. **Read-path synchronization asymmetry.** Mutations serialize through `writeQueue.sync`; reads don't (atomic writes prevent torn files). Kotlin choice: full Mutex (reads block writes — a concurrency change) vs mirrored asymmetry (atomic-write discipline required) — pick deliberately. (`Pilgrim/Models/Threads/TranscriptContextStore.swift:166-169@0172e2b`, EDG-45/DAT-16.)
5. **`changeCount` arithmetic.** One increment per `delete()` CALL (not per UUID) is load-bearing for the dossier memo's `preBuild + ownWrites` math. Replicate exactly, or redesign counter + memo as one unit — never change one without the other. (`Pilgrim/Models/Threads/TranscriptContextStore.swift:93-101@0172e2b` + `Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:154-171@0172e2b`, BEH-18/BEH-38.)
6. **The OFF-branch Main-thread `removeContext`.** The one call site where the same function has one branch correctly off-Main and its sibling inline on Main. Copy faithfully or fix with an IO dispatch? (`Pilgrim/Models/Data/DataManager+VoiceRecording.swift:105-121@0172e2b`, DAT-19.)
7. **Main-thread file I/O in delete completions.** `deleteObject`/`deleteAll` completions run file sweeps + context-store deletes on Main (explicitly "Kept synchronous on purpose" for deleteAll's ordering). Android must preserve the tombstone-before-wipe ORDERING guarantee; the threading is free to change — decide and document. (`Pilgrim/Models/Data/DataManager.swift:801-813,842-856@0172e2b`, DAT-17/DAT-18.)
8. **The completed-flag survives Delete-All.** `threadsBackfillCompletedV6` is NOT cleared by DataManager.deleteAll, so post-wipe recordings never re-sweep until an import or toggle reset. Whether a higher-level "factory reset" UI compensates is outside this slice's files — trace Android's Delete-All entry point, then replicate the survival or close the gap. (`Pilgrim/Models/Data/DataManager.swift:854-855@0172e2b`, DAT-56.)
9. **`UserPreferences.reset()` call site.** The whole-domain wipe is called nowhere in the slice's file list — locate its real caller before assuming Delete-All-my-walks and Reset-all-preferences are the same user action. (`Pilgrim/Models/Preferences/UserPreferences.swift:106-110@0172e2b`, DAT-57.)
10. **The importer's current-schema orphan gap.** `stripHeavyData` deletes VoiceRecording rows without cleaning their contexts; the orphan is invisible to `pruneStaleOrphans` by construction and only swept by a later `build()` for a different walk. Close or reproduce? (`Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:443-465@0172e2b`, DAT-29/DAT-28.)
11. **`performLegacyHygiene` is iOS-history-specific.** The V3-key/moon-line conditional reacts to keys that will never exist on Android — proposed: mark N/A, do not port the conditional; keep only the key-versioning discipline (capture-presence-before-removal if Android ever adopts key renames). Confirm. (`Pilgrim/Models/Threads/ThreadsBackfill.swift:83-88@0172e2b`, BEH-26.)
12. **Tombstone persistence depends on the execution substrate.** In-memory tombstones match iOS only if the Android analysis job is process-bounded (in-process coroutines). If analysis moves to WorkManager (survives process death), tombstones — and a generation/epoch for stale-sweep detection — must be persisted. Decide the substrate first. (`Pilgrim/Models/Threads/TranscriptContextStore.swift:15-17@0172e2b` + `Pilgrim/Models/Threads/ThreadsBackfill.swift:177-189@0172e2b`, BEH-15/DAT-23/DAT-39.)
13. **Three independent 30-day constants.** `ThreadStore.recurrenceWindow`, `ThreadsDossierFormatter.absenceWindow`, `ThreadIntentionSuggestions.recurrenceWindow` are separate literals on iOS. Consolidate (verify all three are semantically the same lookback first) or mirror the triplication (a fix to one must hit all three)? (`Pilgrim/Models/Threads/ThreadStore.swift:30@0172e2b`, BEH-87.)
14. **Stillness template text not captured.** The stillness directive's emitted string was not captured by any lens (thresholds were). Read `Pilgrim/Models/Prompt/AttentionDirectives.swift:44-60@0172e2b` during implementation before writing the Kotlin string — do not invent it. (EDG-82.)
15. **Duplicate-context handling.** iOS traps (`Dictionary(uniqueKeysWithValues:)`) on duplicate stored contexts; Kotlin `associateBy` keeps last silently. Fail-fast vs tolerate-and-log? (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:126@0172e2b`, EDG-71.)
16. **The hardcoded "20%" banner string.** iOS's banner literal quotes a number disconnected from BatteryGate's actual `0.2`. On Android, source the copy from the shared threshold constant (recommended) or reproduce the disconnect? (`Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:193-205@0172e2b` + `Pilgrim/Models/BatteryGate.swift:16@0172e2b`, UI-24/BEH-53.)
17. **Out-of-slice types.** `DossierSenses.swift` (ElevationSample/PhotoPin/WalkSnapshotRow/RouteFix/Input) and `RecordingContext`'s declaring file are not in the 26-file list — field shapes here are inferred from usage; read them before porting `DossierSensesFetchBundle`/`SensesAssemblyState`. (`Pilgrim/Models/Threads/ThreadsDossierBuilder.swift:4-17,228-239@0172e2b`, DAT-13/DAT-65.)
18. **Sentiment scale comparability.** VADER-lite's numeric range vs NLTagger's — non-parity is accepted by plan, but the dossier's `"sentiment %.2f"` clause and any cross-history comparison should stay meaningful; confirm the stored field's scale is documented at write time. (`Pilgrim/Models/Threads/MarkerAnalyzer.swift:98-126@0172e2b`, DAT-9 + plan.)
19. **Endpoint check.** The HuggingFace model repo is the slice's only network touchpoint, is not a Pilgrim-ecosystem endpoint, and is N/A for Android (whisper.cpp + GGML channel) — confirm no CLAUDE.md ecosystem-table change is wanted. (`Pilgrim/Models/TranscriptionService.swift:251-261@0172e2b`, DAT-49.)
20. **Magic UI numbers with no token.** Intention header top `12`; chip `12`/`6`/`250`; skip-banner vertical `4` — port as literals or mint tokens? They deliberately do not resolve to `Constants.UI.Padding` values. (`Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:26-27,151-153@0172e2b`, `Pilgrim/Scenes/WalkSummary/WalkSummaryRecordingsSection.swift:193-205@0172e2b`, UI-2/UI-10/UI-24.)

---

> Spec written. Run `superpowers:writing-plans docs/parity/2026-08-25-threads-engine-port.md` to break into tasks, or `jutsu swarm doc-review docs/parity/2026-08-25-threads-engine-port.md` for a remote QA gate first.
