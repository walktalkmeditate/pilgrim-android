---
title: "feat: Thought Threads — iOS v1.11.0 parity port (Phase 20)"
type: feat
status: planned
date: 2026-08-25
origin: docs/brainstorms/2026-08-25-ios-v1110-parity-retarget-requirements.md
---

# feat: Thought Threads — iOS v1.11.0 parity port (Phase 20)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Authority order:** the `/ios-parity port` specs produced in U2 and U8 (Swift quotes pinned at `0172e2b`) outrank this plan wherever they conflict; this plan outranks memory. Requirements doc: `docs/brainstorms/2026-08-25-ios-v1110-parity-retarget-requirements.md` (R1–R18, post ce-doc-review round 1).

**Goal:** Android v1.5.0 ships Thought Threads at net-state parity with the iOS v1.11.0 tag (`0172e2b`): on-device semantic analysis of walk transcripts (themes, markers, per-recording contexts) feeding a richer AI-prompt dossier with eight "senses," plus intention chips and one settings toggle — no card, no thread view, no recap, nothing leaves the device.

**Architecture:** A new `core/threads/` package mirrors iOS's `Models/Threads/` file-for-file. The one substrate divergence: Apple's NaturalLanguage framework is replaced by WordNet-derived lemma/POS/synset assets + a Kotlin Morphy lemmatizer, VADER-lite sentiment, and ML Kit language-id — same semantics, documented mechanical differences, with an Android-only scaffold-lemma filter on the theme path compensating for dictionary (non-contextual) POS. Analysis rides the existing transcription worker; derived data is a recomputable JSON file cache (no Room migration).

**Tech Stack:** Kotlin + coroutines, Room (queries only — no schema change), DataStore Preferences, WorkManager (backfill), whisper.cpp JNI (segment-quality exposure), ML Kit language-id, kotlinx.serialization JSON, JUnit4 + Turbine + Robolectric.

## Global Constraints

- Parity pin: `pilgrim-ios` @ `0172e2b` (the `v1.11.0` tag). Diff with `cd ../pilgrim-ios && git show v1.11.0:<path>`. Shipped Swift outranks iOS design docs.
- SPDX header only (`// SPDX-License-Identifier: GPL-3.0-or-later`); no OutRun references anywhere; package root `org.walktalkmeditate.pilgrim`.
- No Room schema migration in this phase. Derived analysis lives in `filesDir/transcript_contexts/`, excluded from Auto Backup.
- Toggle sovereignty (R12): `threadsAfterWalks` off = no analysis, no backfill, no dossier sections, no senses, no chips. Every unit's work is behind this check.
- Descriptive-never-evaluative strings: no scores, trend words, or wellbeing language in any user-visible surface; trajectory language is dossier-only. Template counts are always substituted, never hardcoded.
- English-only analysis v1 (R5): detected language ≠ English → no themes, no markers; prompts still name the detected language.
- Determinism: same inputs → same outputs everywhere (stable tie-breaks, ordered modal families, no wall-clock reads inside pure modules; time enters as parameters).
- Tests: JUnit4 + Turbine + Robolectric where a real Android runtime matters. Platform-object builder rule (house): any `WorkRequest`/`Intent`/platform builder constructed in production code gets ≥ 1 Robolectric test calling `.build()` on the production class.
- WorkManager: backfill is a plain `OneTimeWorkRequest` + `BatteryNotLow`, KEEP policy — **never** Expedited+BatteryNotLow (Stage 2-F crash). The transcription request stays expedited; its battery gate is a runtime check, not a constraint.
- `viewModelScope.launch` defaults to Main — hop to `Dispatchers.IO` at repository/file seams (Stage 2-E lesson). All file I/O off-main.
- Comment policy: comments state constraints code can't show; no what-comments.
- Suite must be green before every commit: `export PATH="$HOME/.asdf/shims:$PATH" && ./gradlew :app:testDebugUnitTest`.
- Commit style `feat(threads):` / `fix(threads):` / `chore:` / `docs:`; PR per unit-cluster, review per house autopilot conventions.

---

## Summary

iOS shipped Thought Threads in v1.11.0 (89 commits, ~17.6K insertions) and removed its own Stage 3 surfaces before ship after a real-device field verdict. Android ports the net state as Phase 20 in one release (v1.5.0): the analysis engine, the AI-prompt dossier with Dossier Senses II, intention chips under a "Recurring" shelf, one settings toggle, and the debug field report. Two `/ios-parity port` specs (engine slice, senses slice) pin every behavior to Swift quotes before implementation.

## Problem Frame

The delta is exactly v1.10.0 → v1.11.0. Per-PR dispositions live in the requirements doc's table; the salient facts for implementers:

- The card, thread history view, lunation recap UI, and release gesture were **built and then deleted** inside the delta — they are not part of the port. `LunationCalendar` survives (the moon-line sense consumes it).
- The question-density sense was cut at iOS's ship gate; it is never built.
- The final backfill-key semantics at the pin are **V6** (schema-version-aware freshness, prune-before-sweep, all-accounted completion) — mid-delta docs referencing V3 are stale.
- iOS's segment-quality filter is effectively compression-only in production (WhisperKit hardcodes `noSpeechProb` to 0); Android's real no-speech values will exclude strictly more segments — accepted conservative divergence with a QA flag-rate gate.
- The iOS CoreStore UUID-as-string fix and test-harness fixes are N/A on Android.

## Requirements

R1–R18 in `docs/brainstorms/2026-08-25-ios-v1110-parity-retarget-requirements.md` (post-review round 1). Unit→requirement trace:

| Unit | Covers |
|---|---|
| U1 | R1, R2 |
| U2 | R15 (engine slice spec), R3 (shipped-code-wins sourcing) |
| U3 | R5, R6 |
| U4 | R4 (+AE1) |
| U5 | R7 (+AE2), R13 (file hygiene) |
| U6 | R8 (+AE5, AE6 backfill side), R12 (toggle plumbing) |
| U7 | R9, R10 |
| U8 | R15 (senses slice spec), R3 (shipped-code-wins sourcing) |
| U9 | R11 (+AE3), R12 (field report) |
| U10 | R12 (chips, toggle UI), R14 (clipboard hardening) |
| U11 | R16 (golden fixture + integration families), Success Criteria |
| U12 | R17, R18, R1 (verification), AE4/AE7 closure |

## Scope Boundaries

Everything in the requirements doc's Scope Boundaries section — dead surfaces, question-density sense, multilingual extraction, embedding relatedness, worker-side work, iOS release tooling — plus: no new Mapbox/map work, no notification surfaces, no Glance/widget exposure of threads data.

## Context & Research

### Relevant Code and Patterns (verified on Android HEAD this session)

- `core/prompt/` — `PromptAssembler.kt`, `AttentionDirectives.kt`, `PromptGenerator.kt`, `ActivityContext.kt`, `ContextFormatter.kt`, `PhotoContextAnalyzer.kt` + `MlKitImageLabelerClient` / `MlKitTextRecognizerClient` / `MlKitFaceDetectorClient` (the wrapper pattern `MlKitLanguageIdClient` must follow). AI prompts surface at `ui/walk/summary/AIPromptsRow.kt` + `PromptDetailDialog.kt` (plain `ClipData.newPlainText` today — U10 hardens).
- `audio/TranscriptionRunner.kt` — transcription persist point (currently blank-text no-speech detection only); `audio/WhisperCppEngine.kt` + vendored `app/src/main/cpp/whisper/` (public API `whisper_full_get_segment_text/t0/t1/no_speech_prob` verified in `include/whisper.h:611-670`); `WhisperModelConfig` ships multilingual `ggml-base.bin`.
- `data/entity/` — `VoiceRecording` (uuid unique-indexed, `startTimestamp`, `transcription`, `wordsPerMinute`), `RouteDataSample` (indexed `(walk_id, timestamp)`, lat/lon, `horizontalAccuracyMeters`, `altitudeMeters`), `Walk` (`intention`, `weatherCondition` — finite 10-value vocabulary, `weatherTemperature`), `WalkPhoto` (`takenAt`, captured coords). All senses' inputs exist; no migration.
- `location/FusedLocationSource.kt:206` — the 100 m accuracy ceiling ("iOS hard ceiling: any horizontalAccuracy >= 100m is rejected") the coordinate-hygiene rule reuses.
- `ui/settings/voice/VoiceCard.kt` + `SettingToggle` (description is a required parameter), `ui/walk/IntentionSettingSheet.kt` (existing `ChipSection` shelves: Suggested, Recent — Recurring renders above both), `ui/walk/summary/` recordings section (battery-skip banner home).
- `OrphanRecordingSweeper` — daily WorkManager job re-enqueueing untranscribed rows; this is the retry loop that makes a runtime battery-gate skip safe (case (d) re-enqueue verified).
- `core/celestial/MoonCalc.kt` + `MoonPhase.kt` (the Android moon math; iOS's `LunarPhase` exists here only in porting comments) — `LunationCalendar` builds beside them, not into them; the private new-moon epoch constant needs promotion to `internal` (same precedent as `SYNODIC_DAYS`) so lunation arithmetic shares the exact epoch and can never disagree with phase math.

### Institutional Learnings (memory, directly applicable)

- Stage 2-F: fakes at the scheduler boundary hid a `WorkRequest.build()` crash for 6 review cycles — hence the platform-builder Robolectric rule on the backfill request.
- Stage 2-D/2-E: `viewModelScope` defaults to Main; file I/O belongs behind IO dispatchers at the seam. KEEP-vs-REPLACE WorkManager policy matters; finish-vs-schedule races need bounded waits.
- Stage 3-D: TOCTOU "write if absent" must be atomic inside `dataStore.edit { }` — applies to the backfill-completion and moon-line keys.
- Stage 5-F: `.value` reads don't subscribe — `WhileSubscribed` StateFlows serving non-suspend readers silently stay cold; prefer `Eagerly` + upstream `.catch` for singleton repositories (threads preferences follow the Stage 3-D pattern).
- CI: UnconfinedTestDispatcher teardown UOEs are real leaks (tracked-VM `cancelAndJoin`), not flakes.

### External References

- iOS specs at the pin: `docs/superpowers/specs/2026-08-22-thought-threads-design.md` (incl. Stage 3 field verdict), `docs/superpowers/specs/2026-08-24-dossier-senses-2-design.md`; plans: `2026-08-22-thought-threads-stages-1-2.md`, `2026-08-24-thought-threads-stage3-4.md`, `2026-08-24-threads-dossier-first.md`, `2026-08-24-dossier-senses-2.md` (all under `docs/superpowers/plans/`).
- Princeton WordNet 3.1 (BSD-style WordNet License) — noun/verb/adj indexes + `Morphy` rules + exception lists; VADER lexicon (MIT); Al-Mosaiwi & Johnstone 2018 Table 1 (open access) for the absolutist dictionary.
- whisper.cpp segment API: `whisper_full_n_segments`, `whisper_full_get_segment_{text,t0,t1,no_speech_prob}`.

## Key Technical Decisions

1. **WordNet substrate with mandatory scaffold compensation.** Dictionary POS admits noun-homograph scaffolding ("think", "have", "will" all have WordNet noun senses — verified), so the Android theme path filters `SpokenStoplist.scaffoldLemmas` *in addition to* iOS's `walkingDomain` + `lightNouns`, and the derivation script drops abbreviation/initialism-only noun entries (e.g. "wa"). AE1 pins the five headline words. Suppression-side asymmetry is accepted and documented.
2. **`related()` = same lemma OR shared synset** — deterministic, traceable; no embeddings, no numeric parity with `NLEmbedding`.
3. **VADER-lite sentiment** into the optional `sentiment` slot; numeric non-parity with Apple accepted; formatter self-omits on null.
4. **Segment quality:** JNI exposes per-segment `text/t0/t1/no_speech_prob` (additive API — the existing full-text path is untouched); compression ratio computed Kotlin-side (UTF-8 byte length ÷ deflate length, matching OpenAI semantics). Thresholds `compressionRatio > 2.4 || noSpeechProb > 0.6` with the QA flag-rate gate before release. Flags are never persisted; the guard applies to initial analysis only (parity with iOS).
5. **Backfill = V6 semantics from day one:** freshness is `analysisVersion`-aware, stale-schema orphans pruned before sweep, completion recorded only when every snapshot item is accounted for; checkpointed batches (25 per pass, matching iOS's batch size) so a mid-run process kill resumes rather than restarts. Single fresh key that stores the `ANALYSIS_VERSION` it completed at (version bump → automatic re-arm) — no legacy hygiene.
6. **Battery gate placement:** runtime check at *enqueue* (the scheduling path — mirroring iOS's `MainCoordinatorView` kickoff site), with `OrphanRecordingSweeper`'s daily re-enqueue as the retry loop; the skipped-reason banner rides walk summary state. The backfill worker also re-checks at run start (cheap, defense in depth).
7. **File cache format:** one gzip'd JSON per recording, `filesDir/transcript_contexts/<uuid>.json.gz`, kotlinx.serialization, `transcriptHash` = SHA-256 of the transcript string, `analysisVersion` int const. Backup exclusion via `res/xml` backup rules (both `fullBackupContent` and `dataExtractionRules` documents).
8. **Asset packaging:** derived WordNet + VADER assets ship as gzip'd flat files under `app/src/main/assets/threads/`, lazily loaded once off-main into an in-memory `WordNetLexicon` on first analysis; derivation script is `tools/threads/derive_nlp_assets.py` (committed, fetches WordNet 3.1 at run time, emits assets + a pinned manifest of counts/hashes that R16's derivation-pin tests assert against).
9. **Window anchors:** `ThreadStore.build(..., anchor)` takes the anchor as a parameter — viewed walk's date for dossier reads, now for chips (per shipped `ThreadIntentionSuggestions`).
10. **Golden fixture is a standing instrument:** transcript corpus + iOS-captured dossier output at `0172e2b` committed under `app/src/test/resources/threads/golden/`; refreshed at every future re-pin.

## Open Questions

### Resolved During Planning

- Battery-gate check placement → enqueue-time runtime check + worker-start re-check (decision 6).
- Stale-orphan prune split → backfill worker owns prune-before-sweep (V6 parity); `ThreadStore` additionally drops unresolvable UUIDs at aggregation (R13). Both, matching iOS.
- Asset packaging → decision 8.
- Golden fixture lifecycle → standing instrument (decision 10).
- Recovery affordance after battery skip → port iOS's transcribe-all affordance alongside the banner; exact copy from the U2 spec (Swift wins).
- NLP substrate field gate → folded into U12 device QA: real-transcript theme-quality read + flagged-segment rates via the field report, with an explicit accept-or-tune decision before release (R17).
- `recurringWord` tie-break convention → pinned verbatim in the U2 parity spec from `AttentionDirectives.swift`; not restated here.
- (Plan-review round) Delete All Data → no such Android surface exists; full-wipe hygiene is an internal tested API that preserves `threadsAfterWalks` + the backfill key and clears the moon-line key + contexts (pin-verified iOS `deleteAll` behavior).
- (Plan-review round) Backfill activation site → the app launch path calls `ensureScheduled()` toggle-gated until completion (U6).
- (Plan-review round) Memo placement → above the I/O in `ThreadsDossierBuilder` and `ThreadIntentionSuggestions`; `ThreadStore.build` stays pure (iOS placement).
- (Plan-review round) Golden-capture harness home → committed as a patch beside the fixture README in this repo (the iOS pin is frozen).

### Deferred to Implementation (pinned by the U2/U8 specs)

- Exact marker lexicon word lists, dossier template strings, handling-note text, prompt section ordering — copied from Swift at the pin, never paraphrased.
- Exact segment-flag thresholds' constants home and the transcribe-all copy.
- `SenseInputs` field-by-field shape (mirrors `DossierSenses.lines` parameters at the pin).
- What's-new / release copy (U12; discloses speech analysis default-ON + first-launch backfill).
- Whether `OrphanRecordingSweeper`'s daily re-enqueue should itself pass the battery gate, or remains intentionally ungated recovery — the U2 spec pins whichever behavior is parity.
- Acoustic language detection (`whisper_full_lang_id`, free from the multilingual model at transcription time) as a documented Android-original improvement over the pin's text-based gate — candidate for a fast-follow, not v1 (v1 stays bug-for-bug with iOS).

## High-Level Technical Design

```
recording (.wav) → TranscriptionRunner (existing worker)
  → WhisperCppEngine.transcribeWithSegments()   [U5: JNI additive API]
  → persist transcription (existing)
  → TranscriptContextAnalyzer.analyzeAndStore(uuid, text, flaggedRanges)   [U5]
       uses TranscriptNlp [U3] + ThemeExtractor/MarkerAnalyzer [U4]
       → TranscriptContextStore (filesDir/transcript_contexts/*.json.gz)  [U5]
            ├→ ThreadsBackfill worker fills history, V6 semantics        [U6]
            ├→ ThreadStore.build(contexts, walks, anchor)                [U7]
            │     ├→ ThreadsDossierBuilder/Formatter → PromptAssembler   [U7]
            │     │     └→ DossierSenses.lines(SenseInputs) + moon line  [U9]
            │     └→ ThreadIntentionSuggestions → Recurring shelf        [U10]
            └→ deletion / Delete All / .pilgrim import invalidation      [U5]
settings toggle (DataStore, default ON) gates every arrow above          [U6/U10]
```

## Implementation Units

### U1. Anchor re-pin + fold-in machinery

**Goal:** The repo and tooling name `0172e2b` as the frozen parity target; the fold-in rule is armed for pre-release iOS deltas.

**Files:**
- Modify: `CLAUDE.md` (parity-scope section: target `0172e2b` = the v1.11.0 tag; phasing note gains Phase 20 in-progress)
- Modify: `~/.claude/skills/ios-parity/SKILL.md` + its pin helpers (anchor refs)
- Modify: memory entries naming `2ee1185` as current (project memory file)

**Interfaces:** Produces the pin every later unit's spec quotes against.

**Steps:**
- [ ] Update CLAUDE.md parity section verbatim: target `0172e2b` (2026-08-25, the shipped v1.11.0 tag); fold-in rule text carries forward with "before Android v1.5.0 ships" scope.
- [ ] Update ios-parity skill anchor + defaults; grep both repos' docs for `2ee1185`-as-current references and update.
- [ ] Commit `chore(parity): re-pin frozen target to 0172e2b (iOS v1.11.0) [skip ci]`.

### U2. Parity spec A — `/ios-parity port` engine slice

**Goal:** A pinned spec with Swift quotes for every behavior U3–U7 and U10 implement: `TranscriptNLP`, `ThemeExtractor` + `SpokenStoplist`, `MarkerLexicons`/`MarkerAnalyzer` (incl. modal families), `TranscriptContext(+Store/Analyzer)` incl. tombstone deletion, `TranscriptionService` trigger + flag thresholds + skipped-reason surface, `ThreadsBackfill` (V6), `BatteryGate`, `ThreadStore`, `ThreadsDossierBuilder/Formatter` (incl. the pace-correlation tuple + memo placement), `AttentionDirectives` v2, `PromptAssembler` sections + handling note + language naming, `UserPreferences` keys, deletion/import hygiene (`DataManager` + `PilgrimPackageImporter` generation), and — because no third spec unit exists — `ThreadIntentionSuggestions` (chip phrase template "walk with '<term>'", dedup-before-cap) plus the chips/toggle surfaces (`IntentionSettingView` Recurring section incl. its async empty-start load, `VoiceCard` toggle row copy + placement).

**Files:**
- Create: `docs/parity/<run-date>-threads-engine-port.md` (path assigned by the ios-parity skill at run time)

**Steps:**
- [ ] Run `/ios-parity port` for the engine slice at `0172e2b`; verify every lexicon word list, threshold, template string, and key name appears as a Swift quote (no paraphrase). Spot-check against the requirements doc's review-round corrections (V6, 25-word scoping, flag semantics) — the spec must agree with shipped code, and the plan defers to the spec.
- [ ] Commit the spec.

### U3. NLP substrate (Android-original)

**Goal:** Deterministic Kotlin NLP primitives with pinned derived assets: tokenizer, Morphy lemmatizer + POS membership, synset relatedness, VADER-lite sentiment, ML Kit language detection.

**Files:**
- Create: `tools/threads/derive_nlp_assets.py`; `app/src/main/assets/threads/` (nouns, verbs, adjectives, exceptions, synsets, vader lexicon — gzip'd; plus `manifest.json` with entry counts + SHA-256s)
- Create: `core/threads/TranscriptNlp.kt`, `core/threads/WordNetLexicon.kt`, `core/threads/VaderSentiment.kt`, `core/prompt/MlKitLanguageIdClient.kt`
- Modify: `gradle/libs.versions.toml` + `app/build.gradle.kts` (`com.google.mlkit:language-id`)
- Test: `TranscriptNlpTest.kt`, `WordNetLexiconTest.kt`, `VaderSentimentTest.kt`, `NlpAssetPinTest.kt`, `MlKitLanguageIdClientTest.kt` (Robolectric, real client construction)

**Interfaces (Produces — later units consume exactly these):**
- `enum class PosClass { NOUN, VERB, ADJECTIVE }`
- `data class LemmaMention(val lemma: String, val surface: String, val start: Int, val length: Int)`
- `data class WordToken(val token: String, val start: Int)`
- `object TranscriptNlp`: `wordTokens(text: String): List<String>`; `wordCount(text: String): Int`; `wordTokenOffsets(text: String): List<WordToken>`; `contentLemmaMentions(text: String, classes: Set<PosClass> = setOf(NOUN, VERB, ADJECTIVE)): List<LemmaMention>`; `related(a: String, b: String, languageCode: String): Boolean` (same lemma or shared synset; English-only in v1, other languages → false unless equal)
- `class WordNetLexicon`: `lemmatize(surface: String, pos: PosClass): String?`; `isListed(lemma: String, pos: PosClass): Boolean`; `synsets(lemma: String): IntArray` — loaded lazily off-main from assets, singleton via Hilt
- `object VaderSentiment`: `score(text: String): Double?` (null on empty/no-coverage)
- `class MlKitLanguageIdClient`: `suspend fun detect(text: String): String?` (null below 0.5 confidence, mirroring iOS)

**Steps:**
- [ ] Write + run the derivation script; commit assets + manifest. Script verifies the downloaded WordNet archive against a checksum pinned in the script itself and sourced independently of the download (a compromised mirror cannot silently seed the committed assets); excludes abbreviation/initialism-only noun entries per decision 1; README-style header documents provenance + licenses; attribution lines land in the About/notices surface.
- [ ] RED: `NlpAssetPinTest` — manifest counts/hashes match committed assets; pinned lemma outcomes (`grieving→grieve` verb, `days→day` noun, `thoughts→thought` noun, `was` lemmatizes into scaffold territory but is NOT admitted as noun "washington"); pinned synset relatedness (`grief`~`sorrow` true, `grief`~`bicycle` false).
- [ ] RED: tokenizer parity fixtures (single tokenizer for counts and offsets — the iOS `wordTokens`/`wordTokenOffsets` contract), `related()` fixtures, VADER exact-score fixtures, language-id gate ≥ 0.5.
- [ ] Implement minimally; suite green; commit `feat(threads): the words find their roots — WordNet substrate, VADER, language id`.

### U4. Markers + themes

**Goal:** `MarkerLexicons` (absolutist 19, self-focus, insight/causation/discrepancy, temporal, six ordered modal families), `MarkerAnalyzer` (computes for EVERY recording regardless of length), `SpokenStoplist`, `ThemeExtractor` (noun-only + scaffold filter, ≥ 25 words for themes only, `minimumMentions = 2`, display terms, deterministic tie-breaks).

**Files:**
- Create: `core/threads/MarkerLexicons.kt`, `core/threads/MarkerAnalyzer.kt`, `core/threads/SpokenStoplist.kt`, `core/threads/ThemeExtractor.kt`
- Test: `MarkerAnalyzerTest.kt`, `ThemeExtractorTest.kt`, `SpokenStoplistTest.kt`

**Interfaces:**
- Consumes: `TranscriptNlp.contentLemmaMentions/wordTokens`, `VaderSentiment.score`
- Produces: `enum class TemporalLean { PAST, PRESENT, FUTURE }`; `data class TranscriptMarkers(wordCount: Int, absolutistCount: Int, firstPersonCount: Int, insightCount: Int, causationCount: Int, discrepancyCount: Int, temporalLean: TemporalLean?, modalCounts: Map<String, Int>, sentiment: Double?)`; `MarkerAnalyzer.compute(text: String, languageCode: String?): TranscriptMarkers`; `data class Theme(lemma: String, displayTerm: String, salience: Int, mentions: List<LemmaMention>)`; `ThemeExtractor.themes(text: String): List<Theme>`; `SpokenStoplist.lightNouns/scaffoldLemmas: Set<String>` (verbatim from the U2 spec, incl. `day`/`days`/`area`)

**Steps:**
- [ ] RED: lexicon fixtures with exact counts per the U2 spec's quoted word lists (absolutist Table-1 verbatim; modal family per-word identity; dominant-word tie stability from ordered arrays).
- [ ] RED: AE1 pinned — pure-scaffold ≥ 25-word transcript (containing "was", "have", "can", "think", "will") → zero themes; "music" ×3 amid scaffolding → exactly `music`. Sub-25-word transcript → zero themes but `MarkerAnalyzer.compute` still returns full markers.
- [ ] RED: determinism (same text twice → identical structures), walkingDomain suppression, display-term cohort sharing.
- [ ] Implement; green; commit `feat(threads): markers and noun-only themes — scaffolding cannot thread on this substrate either`.

### U5. Context pipeline (JNI + analyzer + file store + hygiene)

**Goal:** Per-recording `TranscriptContext` computed at transcription time with segment-quality exclusion, stored hash+version-validated in a backup-excluded file cache, invalidated by edits/versions/imports, deleted with its recording.

**Files:**
- Modify: `app/src/main/cpp/` JNI bridge + `audio/WhisperCppEngine.kt` (additive `transcribeWithSegments` surface exposing `text/t0Ms/t1Ms/noSpeechProb` per segment; existing entry point untouched), `audio/TranscriptionRunner.kt` (post-persist analyzer call, toggle-gated)
- Create: `core/threads/TranscriptContext.kt` (+ `ANALYSIS_VERSION`), `core/threads/TranscriptContextStore.kt`, `core/threads/TranscriptContextAnalyzer.kt`, `core/threads/CompressionRatio.kt`, `core/threads/ThreadsPreferences.kt` (DataStore — created here because U5's own gates consume it: `threadsAfterWalks` default true, `importGeneration`; U6 adds the backfill keys)
- Modify: backup rules XML — exclude `transcript_contexts/` in BOTH `data_extraction_rules.xml` domains (`cloud-backup` AND `device-transfer`, following the existing `share_device_token.preferences_pb` device-transfer exclusion precedent) and in the legacy `fullBackupContent` document; deletion seams that actually exist (single-recording delete, single-walk delete via `WalkRepository`, and the `.pilgrim` importer's replace path) remove context files; the importer bumps `importGeneration`. **Android has no user-facing Delete All Data surface** (verified: `WalkRepository` exposes only per-walk/per-recording deletes; iOS's `DataManager.deleteAll` is `#if DEBUG`-only at the pin) — full-wipe hygiene ships as an internal, unit-tested API (`TranscriptContextStore.deleteAll()` + threads-key reset) so AE5 holds at the API level; matching iOS's shipped `deleteAll`, the wipe preserves `threadsAfterWalks` and the backfill key and clears the moon-line key + contexts
- Test: `TranscriptContextStoreTest.kt`, `TranscriptContextAnalyzerTest.kt`, `CompressionRatioTest.kt`, `WhisperSegmentBridgeTest.kt` (Robolectric Kotlin seam), `ThreadsDeletionHygieneTest.kt`

**Interfaces:**
- Consumes: U3/U4 outputs; `VoiceRecording.uuid/transcription`
- Produces: `data class TranscriptContext(uuid: String, languageCode: String?, wordCount: Int, themes: List<Theme>, markers: TranscriptMarkers, transcriptHash: String, analysisVersion: Int)`; `class TranscriptContextStore`: `read(uuid): TranscriptContext?` (null on hash/version mismatch — caller recomputes), `readRaw(uuid)`, `loadAll(): List<TranscriptContext>` (the one bulk read — consumers memoize above it, per U7), `write(ctx)`, `delete(uuid)`, `deleteAll()`, `allUuids(): List<String>` (throws/returns null-signal on unreadable dir — never "empty world"), `changeCount: StateFlow<Long>` — deletes are **tombstone-backed** (iOS parity: `delete`/`deleteAll` record tombstones that `write` checks, so an in-flight analysis queued before a delete cannot resurrect the context file); `class TranscriptContextAnalyzer`: `suspend analyzeAndStore(uuid: String, transcript: String, flaggedRanges: List<IntRange> = emptyList()): TranscriptContext?` (null when non-English or toggle off)
- `WhisperCppEngine.transcribeWithSegments(...)` returns the engine's existing result type extended with `segments: List<WhisperSegment>`, where `data class WhisperSegment(text: String, t0Ms: Long, t1Ms: Long, noSpeechProb: Float)`

**Steps:**
- [ ] RED: store round-trip, hash-mismatch → null (AE2), version-mismatch → null, unreadable-dir signal ≠ empty, delete paths (single delete + internal full-wipe API + orphan file for vanished uuid), tombstone race (analyzer write racing a delete does not resurrect the file), and a resource-parsing test asserting the `transcript_contexts/` exclude exists in BOTH `data_extraction_rules.xml` domains so the rule cannot silently regress.
- [ ] RED: analyzer — flagged ranges excluded from theme mentions (a theme resting only on flagged segments does not form); flag thresholds per U2 spec; toggle-off → no write; non-English → no write, language still returned for prompt naming.
- [ ] JNI: additive segment API + Kotlin bridge; Robolectric seam test; native path flagged for U12 device verification.
- [ ] Wire `TranscriptionRunner` post-persist call; deletion + import-generation hygiene; backup rules (both domains).
- [ ] Reality probe for R5 (feeds U12): run one real non-English recording through the existing forced-English decoder (`whisper-jni.cpp` pins `wparams.language = "en"` — deliberate iOS parity) and record what ML Kit detects on its output; the language gate's practical coverage is bounded by this shared-with-iOS limitation, and U12's QA expectation is written from the observed behavior, not the idealized one.
- [ ] Green; commit `feat(threads): the context takes shape — segment-gated analysis, hash-true file cache, hygiene everywhere`.

### U6. Backfill, battery gate, toggle plumbing

**Goal:** One-time V6-semantics backfill over history (checkpointed, battery-gated, toggle-gated, re-armable); `BatteryGate` runtime check also gating auto-transcription with the skipped-reason banner + transcribe-all recovery.

**Files:**
- Create: `core/threads/ThreadsBackfill.kt` (worker + state), `core/threads/BatteryGate.kt`
- Modify: `core/threads/ThreadsPreferences.kt` (created in U5; adds the backfill keys — `backfillCompletedAtVersion: Int?` stores the `ANALYSIS_VERSION` the sweep completed at, so a future version bump re-arms automatically (the single-key analogue of iOS's V-rename ladder), plus `backfillCheckpoint`, `moonLineLastLunationIndex`)
- Modify: the app launch site (application entry point / main coordinator) — `ThreadsBackfill.ensureScheduled()` on process start, toggle-gated, until completion (mirrors iOS's `MainCoordinatorView` kickoff; without this call site the backfill never runs and AE6 fails at QA)
- Modify: transcription scheduling path (enqueue-time gate + skip reason), `ui/walk/VoiceRecordingsSection.kt` + its `PendingTranscriptionSubstate` mapper (a battery-skipped recording's row must show an honest skipped state — never `QueuedForProcessing` while the walk-level banner says skipped; the banner + transcribe-all live here, and the transcribe-all button's own states are pinned: hidden/disabled while its batch is in flight, with batch progress feedback), internal full-wipe API from U5 (threads-key clearing joins it)
- Test: `ThreadsBackfillTest.kt` (state machine: fresh sweep, checkpoint resume, version-stale re-sweep, toggle-off inert, re-enable re-arm, all-accounted completion), `BatteryGateTest.kt`, `ThreadsBackfillWorkRequestTest.kt` (Robolectric `.build()` on the production request — BatteryNotLow, KEEP, not expedited), `AutoTranscriptionBatteryGateTest.kt`

**Interfaces:**
- Consumes: `TranscriptContextAnalyzer`, `TranscriptContextStore`, `ThreadsPreferences`
- Produces: `ThreadsBackfill.ensureScheduled(context)`; `BatteryGate.allowsBackgroundWork(context): Boolean` (unknown level → true; > 20% → true; charging → true); worker run-start battery re-check below 20% returns `Result.retry` — `BatteryNotLow`'s system floor (~15%) admits runs the 20% gate refuses, so the 15–20% band is a real, tested path

**Steps:**
- [ ] RED: backfill state machine per U2 spec's V6 quotes (prune stale-version contexts before sweep; snapshot uuids+text; batch 25; checkpoint persisted per batch; completion only when every snapshot item accounted; an `ANALYSIS_VERSION` bump re-arms via `backfillCompletedAtVersion`; re-enable re-arms; the internal full-wipe clears the moon-line key while the toggle and backfill key survive — pin-verified iOS `deleteAll` behavior, corrected from AE5's letter per R3's shipped-code-wins rule).
- [ ] RED: WorkRequest builder Robolectric test; battery-gate truth table incl. the 15–20% band (`Result.retry`); auto-transcription enqueue gate + skip-reason surfaced + `OrphanRecordingSweeper` re-enqueue path untouched; battery-skipped row never renders `QueuedForProcessing`.
- [ ] Implement + banner/transcribe-all UI per spec copy — the banner text carries `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` (existing IntentionSettingSheet countdown precedent) so its appearance and clearing are announced to TalkBack; green; commit `feat(threads): the backfill earns completion — V6 freshness, checkpoints, and a battery gate that says so`.

### U7. ThreadStore + dossier core + AttentionDirectives v2

**Goal:** Aggregation with parameterized anchors and honest memoization; the dossier sections (marker profiles with personal baselines, modal leans, trajectories, pace correlation) in `PromptAssembler` with the handling note; lemma-based attention directives with `related()` echo.

**Files:**
- Create: `core/threads/ThreadStore.kt`, `core/threads/ThreadsDossierBuilder.kt`, `core/threads/ThreadsDossierFormatter.kt`
- Modify: `data/dao/VoiceRecordingDao.kt` (uuid→walk projection for the recording-to-walk index), `data/dao/WalkDao.kt` (`WalkLite` projection query)
- Modify: `core/prompt/AttentionDirectives.kt` (lemma content words, scaffold-skipping recurring word, `related()` intention echo, 4-cap + tie-breaks unchanged), `core/prompt/PromptAssembler.kt` (+ handling note gated on dossier presence; prompts name detected language for all recordings)
- Test: `ThreadStoreTest.kt`, `ThreadsDossierBuilderTest.kt`, `ThreadsDossierFormatterTest.kt` (string-pinned templates incl. small-sample line), `AttentionDirectivesTest.kt` (extended in place)

**Interfaces:**
- Consumes: `TranscriptContextStore.changeCount/read`, walk queries, `ThreadsPreferences`
- Produces: `data class WalkLite(val walkId: Long, val startedAt: Instant, val intention: String?, val weatherCondition: String?)` (projection query, no entity coupling); `data class ActiveThread(val lemma: String, val displayTerm: String, val distinctWalkIds: List<Long>, val mentionsByRecording: Map<String, List<LemmaMention>>)`; `ThreadStore.build(contexts: List<TranscriptContext>, recordingToWalk: Map<String, WalkLite>, anchor: Instant, backfillComplete: Boolean): Threads` — the walks parameter is keyed by **recording uuid** (iOS parity: `walks[context.recordingUUID]` is the join; a `List<WalkLite>` alone cannot produce `distinctWalkIds`); `ThreadStore.build` is **pure and unmemoized** — memoization lives above the I/O in the consumers, matching iOS: `ThreadsDossierBuilder` memoizes the finished dossier against (`changeCount`, walkId, backfillComplete) and `ThreadIntentionSuggestions` keeps its own day-keyed memo above `loadAll()`, so reopening the prompt screen or intention sheet never re-reads the whole context directory; `data class Threads(active: List<ActiveThread>, firstTimeLemmas: Set<String>)`; `ThreadsDossierBuilder.build(walkId: Long): DossierBlock?` (null when toggle off / nothing analyzed) — the builder pairs each context with its recording's `wordsPerMinute` (iOS's `(context, wordsPerMinute)` tuple) to render the themes-vs-pace correlation section at the pin's 0.15 relative-change threshold; `DossierBlock.render(): List<String>`

**Steps:**
- [ ] RED: first-time vs full history (a 31-day-old theme is never "first"); 30-day window respects the caller's anchor (dossier: walk date — old walk stable; chips path: now); origin suppression until backfill completes; salience direction present in dossier output, absent from every other surface; builder memo invalidates on store write; a context whose uuid resolves to no walk is silently excluded from aggregation (orphan prune, R13 — neither crash nor dossier leak); pace-correlation renders at the 0.15 threshold and self-omits below it.
- [ ] RED: formatter string pins — density-with-baseline ≥ 100 words, raw-counts small-sample below, word count always attached, modal lean naming, ≥/register-labeled literature figures secondary; handling note emitted only with dossier.
- [ ] RED: directives — scaffold recurring-word skip ("think" ×4 no fire, "river" fires), lemma inflection unify, synset echo with pairs pinned from the U2 spec's own fixtures, cap + tie-breaks.
- [ ] Implement; green; commit `feat(threads): the dossier speaks — profiles with baselines, threads with anchors, directives with roots`.

### U8. Parity spec B — `/ios-parity port` senses slice

**Goal:** Pinned spec for `DossierSenses` + `DossierSensesTracks` + `LunationCalendar` + the field report + `DataManager+VoiceRecording` query shapes: all eight senses' thresholds, guards, template strings, priority order, cap, dedup, coordinate hygiene, moon-line state machine.

**Files:**
- Create: `docs/parity/<run-date>-threads-senses-port.md` (path assigned by the ios-parity skill at run time)

**Steps:**
- [ ] Run `/ios-parity port` for the senses slice at `0172e2b`; confirm question-density is absent, climate guard is mode-based, and every template string is quoted.
- [ ] Commit the spec.

### U9. Dossier Senses + LunationCalendar + field report

**Goal:** The eight senses as pure functions behind `DossierSenses.lines(...)` (3-line cap, priority, one-theme-one-line), fed by bounded Room queries; the moon line's once-per-lunation state; the debug-only field report.

**Files:**
- Create: `core/threads/DossierSenses.kt`, `core/threads/DossierSensesTracks.kt`, `core/threads/LunationCalendar.kt`, `core/threads/SenseInputs.kt`, and `src/debug/kotlin/.../ThreadsFieldReport.kt` — a **debug-only source set is the sole mechanism** (the class is never compiled into release, satisfying R12's "not merely runtime-flagged" regardless of minification config); explicit developer trigger; ephemeral logging only, no file/preference writes
- Modify: `data/dao/RouteDataSampleDao.kt` (timestamp-window + accuracy-predicate projection), `data/dao/WalkPhotoDao.kt` (per-walk photo coords/timestamps), `data/dao/WalkDao.kt` (in-window intentions/weather projections)
- Modify: `ThreadsDossierBuilder.kt` (gathers `SenseInputs` via bounded queries — route samples by timestamp predicate with accuracy filter, photos, weather, in-window intentions; module purity: `DossierSenses` fetches nothing), weather condition → bucket mapper beside the existing weather layer
- Test: `DossierSensesTest.kt` (per-sense geometry/guard fixtures), `LunationCalendarTest.kt`, `WeatherBucketTest.kt` (drift test: every storable Android condition maps), `SenseInputsQueryTest.kt`, `ThreadsFieldReportTest.kt`

**Interfaces:**
- Consumes: `Threads` from U7, Room entities, `LunarPhase` boundaries, `ThreadsPreferences.moonLineLastLunationIndex`
- Produces: `DossierSenses.lines(inputs: SenseInputs): SenseOutput` where `data class SenseOutput(val lines: List<String>, val reportedLunationIndex: Int?)` (pure; ≤ 3 lines; iOS parity — the moon-line index is reported only when the line actually survives cap and dedup into the emitted block, so the caller persists `moonLineLastLunationIndex` on emission, never on evaluation; a bare `List<String>` return cannot support once-per-lunation); `LunationCalendar.mostRecentClosed(now: Instant, zone: ZoneId): Lunation?` with `data class Lunation(index: Int, monthMoonName: String, start: Instant, end: Instant)`

**Steps:**
- [ ] RED per sense, from the U8 spec's quotes: place resonance (150 m, ≥ 2 walks, strict `spread < baseline/2`, zero-baseline suppressed, cap 4, backfill-gated); moon line (once per closed lunation, most-recent-only, current-walk-words gate, the internal full-wipe re-arms it, and `reportedLunationIndex` is set only when the line survives cap + dedup into the emitted block — a cap-dropped line never burns the lunation); marker coloring (±15 words, ≥ 2×, ≥ 3 tokens, dossier-present gate); intention lineage (≥ 3 walks, scaffold filter — the "want"-only pair must not cluster); climb anchoring (top-decile smoothed gradient, ≥ 20 m gain, < 50 m ascent skip); weather weave (mode-based climate guard incl. ties, total-claim rule, unknown bucket); photo adjacency (75 m + 10 min, nearest pair only); speech shape (first third + > 30 min wordless).
- [ ] RED: block rules — 5 senses firing → exactly 3 lines in priority order; theme named at rank 1 never reappears; string pins with substituted counts ("Both"/"twice" only when literally 2).
- [ ] RED: coordinate hygiene — stale sample (> 90 s) and accuracy ≥ 100 m never participate.
- [ ] Implement + field report seam; green; commit `feat(threads): eight senses, three lines, silence by default`.

### U10. Chips, settings toggle, clipboard hardening

**Goal:** The complete user-visible surface: Recurring shelf above Suggested/Recent, the toggle with ported copy + English-only clause, and `EXTRA_IS_SENSITIVE` on dossier-bearing prompt copies.

**Files:**
- Create: `core/threads/ThreadIntentionSuggestions.kt`
- Modify: `ui/walk/IntentionSettingSheet.kt` (Recurring `ChipSection` first, flush-left, empty-field gated; the shelf loads via a coroutine that starts empty and populates when ready — e.g. `produceState(initialValue = emptyList())` keyed like the existing `resetKey` — so "not yet loaded" renders identically to "genuinely empty" and the sheet's appearance never blocks on disk I/O, mirroring iOS's `.task` load), `ui/settings/voice/VoiceCard.kt` (toggle row wired to `ThreadsPreferences`, inserted immediately after the Auto-transcribe toggle and before the model-download row, matching shipped `VoiceCard.swift` order), `strings.xml` (toggle label/description per U2 spec copy + English-only clause), `ui/walk/summary/PromptDetailDialog.kt` (clip marked sensitive on API 33+ when the prompt carries a dossier — the generated-prompt model gains a hasThreadsDossier signal for this)
- Test: `ThreadIntentionSuggestionsTest.kt` (≥ 2 distinct walks/30 days anchored at now, rank by distinct-walk count then alphabetical, display-term dedup BEFORE the cap — the walker is never offered the same chip twice while a distinct one waits behind it, phrase template pinned "walk with '<term>'", cap 2, toggle-off empty), `IntentionSettingSheetTest.kt` (shelf order + gating + empty-start load), `VoiceCardToggleTest.kt`, `PromptClipboardSensitivityTest.kt` (Robolectric: `ClipDescription` extras on 33+, no-crash below)

**Interfaces:**
- Consumes: `ThreadStore.build(..., anchor = now)`, `ThreadsPreferences`
- Produces: `suspend fun ThreadIntentionSuggestions.current(now: Instant): List<String>` (suspend — it reads the context store and aggregates off-main; day-keyed memo above `loadAll()` per U7's memo-placement rule)

**Steps:**
- [ ] RED per the interfaces above; shelf-order assertion; description-string presence (the `SettingToggle` description parameter is required — copy per U2 spec + R5 clause).
- [ ] Implement; green; commit `feat(threads): walk with what walks with you — chips, one honest toggle, a careful clipboard`.

### U11. Golden fixture + integration hardening

**Goal:** The cross-platform golden dossier fixture and the end-to-end families that make the Success Criteria testable.

**Files:**
- Create: `app/src/test/resources/threads/golden/` (transcript corpus + iOS dossier output captured at `0172e2b` + capture README), `ThreadsGoldenDossierTest.kt`, `ThreadsToggleSovereigntyTest.kt`, `ThreadsEndToEndTest.kt` (transcribe-fake → analyze → dossier assembled)
- Modify: none (test-only unit; production gaps found here are fixed under their owning unit's files)

**Steps:**
- [ ] Author the transcript corpus **synthetically** — hand-written text designed to exercise the pinned thresholds and senses, never sourced from any real recorded walk (including the implementer's own): real reflective speech in git history is a permanent exposure outside every privacy control the app has. The constraint is recorded in the capture README so future refreshes preserve it.
- [ ] Capture golden outputs on the iOS side at the pin and **commit the capture harness itself** (a standalone Swift test file stored as a patch beside the README, with the exact inputs — dates, senses bundle — it uses), since no capture seam exists in the iOS tree at `0172e2b`; the README also records the macOS/Xcode version used (Apple NL models are deterministic only per OS release). Commit corpus + outputs + harness patch.
- [ ] RED→GREEN: section structure, ordering, caps, and template strings match iOS output, with annotated allowances for the three acknowledged divergences (theme sets from the lemma engine, VADER-vs-Apple sentiment values, synset-vs-embedding echo outcomes) — named up front so strict string pins can go green.
- [ ] Toggle-off sweep: prompts byte-identical to a no-threads build; zero context writes; chips render empty after the internal full-wipe (AE5's chips clause, pinned).
- [ ] Green; commit `test(threads): the golden dossier — parity you can run`.

### U12. Device QA + release v1.5.0

**Goal:** R17 on the OnePlus 13, the NLP-substrate field read, then the v1.5.0 release.

**Files:**
- Modify: `app/build.gradle.kts` (versionName 1.5.0; versionCode per pipeline convention at dispatch), release notes/what's-new (discloses speech analysis default-ON + first-launch backfill; store copy per R14 precise form), `CLAUDE.md` phasing note (Phase 20 shipped)
- Create: QA checklist doc under `docs/qa/` per house pattern

**Steps:**
- [ ] Device QA per R17: real recordings → dossier read from a copied prompt; chips after 2 real recurring walks; toggle-off sweep; first-activation backfill with origin suppression observed; deletion hygiene (single-recording + single-walk deletes; the internal full-wipe API exercised via a debug seam); field-report inspection incl. flagged-segment rates with the accept-or-tune decision on the 0.6 no-speech threshold — the decision is the product owner's and release-blocking, and it accounts for whisper.cpp's own native pre-filter (the engine already suppresses windows where `no_speech_prob > 0.6` AND `avg_logprob < -1.0` before segments surface, so the Kotlin filter sees only survivors); theme-quality read on real transcripts (the substrate field gate); the non-English check runs against the U5 probe's *observed* behavior (the decoder forces English — a limitation shared with iOS at the pin — so the expectation is what the probe recorded, with toggle copy presence verified regardless); battery-skip banner + transcribe-all on a < 20% walk incl. the honest per-row state; long-walk transcription regression check (JNI touched the critical path).
- [ ] LLM-readback spot check with a real Android dossier (no clinical/diagnostic language); note the vendor used.
- [ ] Verify Play Data Safety needs no change against current Play guidance (nothing collected/transmitted).
- [ ] Release: version bump, `production.yml` dispatch per house release memory (staged rollout), tag; CLAUDE.md phasing update; fold-in re-diff of any iOS delta since `0172e2b` before tagging (R2/AE7).

## System-Wide Impact

- **Transcription path (critical):** the JNI change is additive (new segment entry point; existing API untouched); U12 explicitly regression-checks a long walk. Analyzer failures are silent by design — a thrown analysis never blocks transcription persist (try/catch at the `TranscriptionRunner` call site, CancellationException re-thrown).
- **APK:** +~2–4 MB (WordNet-derived + VADER assets, ML Kit language-id); measured by the derivation manifest; no other binary growth.
- **Prompt surface:** dossier sections render only when the toggle is on AND contexts exist; prompts otherwise byte-identical (U11 pins this).
- **Backup:** new exclusion rules for `transcript_contexts/`; existing backup behavior otherwise unchanged.
- **DataStore:** five new threads keys; Delete All clears them.

## Risk Analysis & Mitigation

- **Substrate drift (top risk):** dictionary POS vs contextual tagging could shift theme quality despite the scaffold filter → AE1 pins, golden fixture, U12 real-transcript field read with accept-or-tune authority before release.
- **Untuned no-speech branch:** flag-rate inspection in U12 with explicit accept-or-tune; thresholds live in one constants home (U2 spec).
- **Backfill at scale:** checkpointed batches inside a plain worker; 10-minute execution window is ample for dictionary-speed analysis, and resume-not-restart is test-pinned.
- **Sense misfires on real ground:** field report gives the observability channel; U12 records firing rates; any systematic misfire is a documented divergence decision, not a silent re-tune.
- **Play review posture:** on-device-only inference, nothing collected; Data Safety re-verified at release; store copy uses the precise non-absolute form.

## Phased Delivery

Single release (v1.5.0) at the end; internal debug builds throughout; PRs land per unit-cluster (suggested: U1+U2, U3+U4, U5+U6, U7, U8+U9, U10+U11, U12) with the house review cadence. No staged feature flags — the settings toggle is the runtime gate.

## Documentation / Operational Notes

- Derivation script re-runs are dev-time only; assets are committed; manifest pins catch silent drift.
- Golden fixture refresh procedure documented beside the fixture; refresh at every future re-pin.
- Multilingual demand listens at the in-app feedback channel (worker → GitHub issue routing already live); note added to the feedback triage doc at release.

## Sources & References

- Requirements: `docs/brainstorms/2026-08-25-ios-v1110-parity-retarget-requirements.md` (R1–R18, AE1–AE7)
- iOS pin `0172e2b`: `Pilgrim/Models/Threads/*.swift`, `Pilgrim/Models/BatteryGate.swift`, `Pilgrim/Models/TranscriptionService.swift`, `Pilgrim/Models/Prompt/*.swift`, `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift`, `Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift`, specs + four plans under `docs/superpowers/`
- ce-doc-review round 1 record (14 applied findings) — conversation of 2026-08-25
