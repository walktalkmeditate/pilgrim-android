---
title: "feat: Walk with Me interactive share — iOS v1.10.0 parity port (Phase 19)"
type: feat
status: draft
date: 2026-08-14
origin: docs/brainstorms/2026-08-14-ios-v1100-parity-retarget-requirements.md
---

# feat: Walk with Me interactive share — iOS v1.10.0 parity port (Phase 19)

> **For agentic workers:** execute unit-by-unit per the house autopilot pattern (one unit ≈ one PR, closing adversarial reviews per stage) or via superpowers:subagent-driven-development. U2's parity spec gates every implementation unit — **the spec's Swift quotes override any behavioral claim in this plan** wherever they disagree. Checkboxes track units, not micro-steps.

**Goal:** Android v1.4.0 ships the "Walk with Me" interactive share at parity with iOS main @ `38ef6b2`: an opt-in Interactive toggle that uploads a walk's voices and hi-res photos so the share page becomes a scrollable story, with consent, caps, trim, transcode, sequential PUTs, and kill-safe repair.

**Architecture:** Everything lands on the existing share stack (`data/share` + `ui/walk/share`). Two Android-original subsystems have no iOS reference: a WAV→AAC transcode prep pipeline (MediaCodec/MediaMuxer) that runs when Interactive toggles on, and a repair-record store whose semantics (resumable, never mis-slotted, honestly reported) replace iOS's background-assertion mechanics. The story page itself is `pilgrim-worker`'s, already live.

**Tech Stack:** Kotlin + Compose, Hilt, OkHttp (existing `@ShareHttpClient`), kotlinx.serialization, DataStore, MediaCodec/MediaMuxer, JUnit4 + Turbine + Robolectric.

## Global Constraints

- Parity pin: `pilgrim-ios` @ `38ef6b2`. Shipped Swift wins over the iOS spec/plan docs (known superseded spec sections listed in origin R3).
- SPDX header only on new files: `// SPDX-License-Identifier: GPL-3.0-or-later`. No OutRun references anywhere.
- Backend contract unchanged: `POST https://walk.pilgrimapp.org/api/share`, `PUT /api/share/{id}/photos/{n}`, `PUT /api/share/{id}/audio/{n}`, header `X-Device-Token`. Client mirrors server caps only for friendly early failure: 12 recordings / 60 MB / 45 min aggregates; 15 MB per audio file; 20 photos; 2 MB per photo; 2 MB JSON payload.
- Platform-object builder rule: any `Request`, `MediaFormat`, `MediaMuxer`, `NotificationChannel`, or `WorkRequest` construction gets at least one Robolectric test calling the real builder on the production class.
- `String.format(Locale.US, ...)` for numeric display; `Locale.ROOT` for wire formats.
- Interactive off = today's share exactly; transcripts never leave the device (payload `transcription` always null, iOS test-pins this in `728c9e1`).
- Prefer `StateFlow`/coroutines; `viewModelScope.launch` defaults to Main — hop to IO at repository seams for file/DB work.

---

## Summary

Port iOS v1.10.0's single headline feature as Phase 19 in nine dependency-ordered units: re-pin + endpoint probes (U1), the `/ios-parity port` spec (U2), tour payload + classification + trim (U3), WAV→AAC transcode prep (U4), hi-res photo export (U5), media uploads + repair record (U6), consent UI (U7), ViewModel orchestration (U8), device QA + release v1.4.0 (U9). U3–U5 are mutually independent once U2 lands; U6–U8 stack on them.

---

## Problem Frame

Android v1.3.0 sits at exact parity with iOS `b4decad` (= v1.9.1). iOS main has moved 42 commits to `38ef6b2` (v1.10.0 candidate, in App Review): one feature — the interactive share — plus chores triaged out. Full delta table, triage, and the resolved reshare decision live in the origin document.

---

## Requirements

Carried from origin (docs/brainstorms/2026-08-14-ios-v1100-parity-retarget-requirements.md):

- R1. Re-pin the frozen parity anchor to `38ef6b2`; update `CLAUDE.md`, the `ios-parity` skill anchor + script defaults, stale references.
- R2. Fold-in rule carries forward; pre-release iOS deltas re-diff and triage into Phase 19; headline features/reverts/redesigns go to user re-triage.
- R3. End-state port of the shipped Swift onto the existing share stack; shipped code wins over the (partly superseded) iOS spec docs.
- R4. Consent UI parity: Interactive toggle (default off), recordings disclosure with per-recording exclusion and two unavailable row states ("audio removed", "too large to carry"), photos auto-enable, voices warning, aggregate-caps share gating. First-share-only accepted: a non-expired cached share still short-circuits past the form.
- R5. Payload parity: `tour` (+`trim_m`) and `pauses` (Interactive-on only, `end > start`, cap 200); wpm only with included recordings; `transcription` always null; `Photo.data` omitted on interactive shares; classification per `TourBuilder` end-state.
- R6. Route trim parity: ~150 m path-distance trim before payload build; "Trim start & end" toggle, default on-when-Interactive; `canTrim` + degenerate-short-walk behavior as shipped.
- R7. Photo parity: 1600 px long edge, EXIF-free re-encode, quality ladder 0.8→0.65→0.5→0.35→0.2 to ≤2 MB, PUT to page keys; cancellable prep with deadline + backstop; pre-POST consent pause when export comes up short.
- R8. Upload orchestration parity: photos first then audio, one auto-retry per item, partial state reveals link + "Carry the missing files" identity-verified batch repair, "Carrying your walk… N/M" copy; invariants: form freeze, completeShare lock, consent-decline cancels, excluded-leave-no-trace, talkDuration clamp, kill-safe repair record, stale records cleared.
- R9 (Android-original). WAV→AAC transcode during share preparation — cancellable, progress-surfaced; `size_bytes` and caps judge transcoded artifacts; failure degrades like upload failure; artifacts cached with the repair record.
- R10 (Android-original). Upload lifecycle: repair semantics are the parity bar, mechanism Android-chosen.
- R11. Every implementation unit is preceded by the U2 `/ios-parity port` spec pinned at `38ef6b2`.
- R12. Unit tests per house rules incl. the platform-object builder Robolectric rule; MediaCodec hardware path device-verified.
- R13. One consolidated device QA pass (OnePlus 13) incl. the production-worker contract test, kill/background/airplane interruption paths, Interactive-off regression, already-shared-walk short-circuit.
- R14. Single release: v1.4.0.

---

## Scope Boundaries

From origin, unchanged: no worker-side work; no whispers bundled bootstrap; no iOS release tooling / screenshot seeder; no spec Phase 2/3 surfaces (auto-advance, transcript sync, leave-a-stone client UI); no recording-pipeline changes (capture stays 16 kHz mono WAV for whisper.cpp); no reshare affordance (first-share-only accepted); nothing iOS ships after `38ef6b2` except R2 fold-ins.

---

## Context & Research

### Relevant Code and Patterns (verified on Android HEAD)

- `data/share/ShareService.kt:34-40` — `share(payload): ShareResult` via injected `@ShareHttpClient` OkHttp + `@ShareBaseUrl`. Media PUTs extend this class; the qualifiers already exist.
- `data/share/SharePayloadBuilder.kt:26-64` — `ShareInputs` + `WalkShareOptions` → `build(...)`. Interactive/trim/exclusion options extend `WalkShareOptions`; tour assembly joins here so trim runs before payload build (R6).
- `data/share/SharePayload.kt` — kotlinx.serialization, `@SerialName` snake_case. `Photo.data` is currently non-null `String` — becomes nullable for interactive shares.
- `ui/walk/share/WalkShareViewModel.kt:64-164` — toggle-method pattern (`toggleX(on: Boolean)`), `share()`, `cachedShare` StateFlow whose non-expired emission short-circuits to the Shared state (the first-share-only mechanism — leave intact), `WalkShareUiState`/`WalkShareEvent`.
- `data/share/CachedShareStore.kt` — DataStore-backed per-walk cache; the repair record gets a sibling store, same encode/decode conventions.
- `data/entity/VoiceRecording.kt:26-44` — `uuid, walkId, startTimestamp, endTimestamp, durationMillis, fileRelativePath, transcription?, wordsPerMinute?, isEnhanced`. Everything TourBuilder consumes exists.
- `audio/AudioRecordCapture.kt` — `SAMPLE_RATE_HZ = 16_000`; recordings are 16 kHz mono 16-bit WAV ≈ 1.92 MB/min (a 20-min talk ≈ 38 MB — transcode is mandatory, not optional).
- `audio/OrphanRecordingSweeper.kt` — canonical-path + extension + regular-file guard pattern; the transcode-artifact sweep (U4) mirrors it.
- `core/prompt/PauseContext.kt` — Phase 17's pause-interval derivation; the payload `pauses` join reuses its source data (exact seam pinned by U2).
- `data/voiceguide/` download pipeline — precedent for progress-surfaced background work owned by a store (pattern reference for prep progress, not reused wholesale).

### Institutional Learnings (from memory, directly applicable)

- Fakes at the scheduler/builder boundary hide `build()` crashes for six review cycles (Stage 2-F) — hence the Robolectric builder rule on `MediaFormat`/`MediaMuxer`/`Request`.
- `viewModelScope.launch` defaults to Main (Stages 2-E, 5-D) — prep transcode and repair I/O hop to IO at the store seam.
- "Dedup concurrent calls" flags must flip via `compareAndSet` before `scope.launch` (Stage 5-C) — the completeShare lock uses this.
- Long-lived `collect {}` loops defend every suspend call, re-throw `CancellationException` (Stage 5-D) — upload loop + prep loop.
- Delete operations drive path computation through the same function the write path used (Stage 5-D) — artifact cleanup uses the transcoder's own path function.
- `WhileSubscribed(5s)` is a stale-cache trap for nav-driving StateFlows (Stage 5-G) — status/progress flows stay hot or VM-owned.

### External References

- iOS at the pin: `Pilgrim/Models/Share/{TourBuilder,RouteTrimmer,TourPhotoExporter,SharePayload,ShareService}.swift`, `Pilgrim/Scenes/WalkShare/{WalkShareView,InteractiveShareSection,ShareStatusSection,WalkShareViewModel,WalkShareViewModel+ShareOrchestration}.swift`, `UnitTests/{TourBuilderTests,RouteTrimmerTests,SharePayloadTourTests,ShareMediaUploadTests,TourPhotoExporterTests,WalkShareInteractiveTests}.swift`.
- iOS docs (context only; partly superseded): `docs/superpowers/specs/2026-08-09-walk-with-me-tour-design.md`, `docs/superpowers/plans/2026-08-11-walk-with-me-tour-ios.md`.
- Share review-round commits the U2 spec must read: `7ed84dc`, `055305a`, `abdacc8`, `ed66aca`, `62b2b5f`.

---

## Key Technical Decisions

1. **Transcode target: AAC-LC mono, 64 kbps, source sample rate (16 kHz), M4A container.** Speech-appropriate; 45 min ≈ 21.6 MB (under the 60 MB aggregate), one file hits 15 MB at ≈ 31 min. 16 kHz AAC-LC is a standard sample-rate index; the U1 probe proves worker + browser playback before any code exists. If the probe fails on 16 kHz, the pre-agreed contingency is resampling to 32 kHz during encode (MediaCodec consumes the same PCM; only `KEY_SAMPLE_RATE` and an interpolation pass change).
2. **Prep timing: transcode starts when Interactive toggles on** — sequential over included recordings, per-row "preparing…" until its real size lands, Share button gated until every included artifact is ready (satisfies AE4 with no estimated-sizes state), cancelled by toggle-off; excluding a recording cancels/deletes its artifact.
3. **Artifact hygiene (closes the review's FYI gaps):** artifacts live in `cacheDir/share-prep/<walkUuid>/<recordingUuid>.m4a`, written and deleted through the same path function. Deleted on: toggle-off, exclusion, share-screen exit without an active repair record, and repair-record clearing. A startup sweep (OrphanRecordingSweeper pattern) removes artifact dirs with no matching active repair record. A repair retry that finds a missing artifact re-encodes from the WAV rather than failing.
4. **Upload lifecycle: VM-scoped coroutine + persisted repair record; no WorkManager in v1.4.0.** Matches the pin's foreground semantics (uploads run while the share screen lives; re-entry offers repair for the share's life). Honest paused reporting: process death or scope cancellation leaves the repair record authoritative; the pin's pre-fail-doomed-PUTs gate translates to marking the untried tail as pending-in-record before cancellation completes. iOS has a background-URLSession rework flagged as a fast-follow — R2 handles it if it lands pre-release; do not pre-build for it.
5. **Hi-res photo source: the same MediaStore URI the classic embedder reads**, full-resolution decode with EXIF orientation applied before re-encode (re-encode drops EXIF metadata by construction). Android can hit the short-export case (deleted/unresolvable URIs) — the pre-POST consent pause ports unconditionally.
6. **First-share-only preserved:** the `cachedShare` short-circuit is untouched; the R13 QA list pins it.

---

## Open Questions

### Resolved During Planning

- Prep UX (origin R9 question) → Decision 2. Blocking-modal prep rejected: it forbids adjusting exclusions while encoding.
- Upload mechanism (origin R10 question) → Decision 4.
- `pauses` semantics (origin R5 question) → resolved at pin, folded into R5.
- Hi-res source + short-export applicability (origin R7 question) → Decision 5.
- Transcode-artifact lifetime (review FYI) → Decision 3 (cache-as-optimization; eviction re-encodes).

### Deferred to Implementation (pinned by the U2 spec)

- Exact repair-record identity mechanism (what iOS hashes/compares so wrong-slot uploads are impossible) — port the shipped scheme.
- Exact user-facing copy strings (toggle subtitle, warnings, status card, repair-unavailable explanation) — quote from Swift at the pin.
- Pending-transcription disclosure presentation (untranscribed rows' preview + kind at the pin) — must preserve informed per-recording exclusion.
- The exact `pauses`→payload join (which persisted events feed it) — follow the pin's data path against `PauseContext`'s source.

---

## High-Level Technical Design

Toggle on → prep pipeline transcodes included WAVs to cached M4As (sizes stream into disclosure rows; Share gated until ready) → `share()` claims the lock, freezes the form, clamps talkDuration, builds trimmed route + tour + pauses via `SharePayloadBuilder` → photo export (1600 px ladder), short-export → consent pause → `POST /api/share` → repair record written `{id, token-hash-input, slots: photos[n]→uri+identity, audio[n]→recordingUuid+identity, status}` → sequential PUTs photos-then-audio, one auto-retry each, per-item status into the record → terminal: all-landed (cache share, clear record + artifacts) or partial (cache share with failed count; record persists; status card offers "Carry the missing files") → re-entry with a record present goes to repair, not re-POST. Interactive off bypasses every new seam.

---

## Implementation Units

### U1. Anchor re-pin, endpoint probes, fold-in machinery

- [ ] **Status: Not Started**

**Goal:** The repo, skill tooling, and docs all name `38ef6b2`; the worker contract and CDN assumptions are proven before implementation starts.

**Requirements:** R1, R2, plus origin Dependencies (worker probe, wonder-4 CDN check).

**Dependencies:** none.

**Files:**
- Modify: `CLAUDE.md` (parity-scope section: `b4decad` → `38ef6b2`, dated 2026-08-13, v1.10.0 candidate; fold-in rule references Phase 19 / v1.4.0)
- Modify (out-of-repo, operational): `~/.claude/skills/ios-parity/SKILL.md` (both `9a418e4` citations) and `~/.claude/skills/ios-parity/scripts/lib.sh` + `ios-pin.sh` header (default `c1745e8` → `38ef6b2`)
- Modify: memory index entry for the parity target (pilgrim_android_project.md pointer)

**Approach:**
- Mechanical re-pin, mirroring U1 of the v1.9.0 plan.
- Probe the production worker with a disposable share: `POST /api/share` with a minimal payload carrying `tour` (one recording entry, `transcription: null`) and `pauses`; then `PUT .../photos/1` with a small JPEG and `PUT .../audio/1` with a locally generated 16 kHz mono 64 kbps AAC-LC M4A (ffmpeg from a real walk WAV); then fetch the page and `GET .../audio/1.m4a` with a `Range` header; play the audio in a desktop browser. This is the review's "first artifact of its kind" de-risk.
- `curl` the whispers CDN manifest and confirm `version >= 5` / wonder-4 present (closes the triage assumption; if absent, note it — still a no-op for Android, but tell the user).

**Verification:** grep shows no `b4decad`/`9a418e4`/`c1745e8` as current anywhere; probe share renders a story page with an audible voice chapter; probe share left to expire (shortest expiry).

---

### U2. Parity spec — `/ios-parity port` for the interactive share slice

- [ ] **Status: Not Started**

**Goal:** A pinned spec with Swift quotes for every behavior U3–U8 implement — the single source of truth that outranks this plan.

**Requirements:** R11, R3.

**Dependencies:** U1 (pin updated so citations render `@38ef6b2`).

**Files:**
- Create: `docs/parity/2026-08-XX-walk-share-interactive-port.md` (skill output)

**Approach:**
- Run `/ios-parity port` with explicit scenes: `Pilgrim/Models/Share` + `Pilgrim/Scenes/WalkShare` (the resolver's multi-folder form).
- Direct the lens readers at the share review-round commits (`7ed84dc`, `055305a`, `abdacc8`, `ed66aca`, `62b2b5f`) — the doc-review proved the iOS *spec docs* are stale in five places; only shipped Swift counts.
- The spec must pin, at minimum: TourBuilder classification rules + constants and `unavailableReason` strings; validation aggregates; RouteTrimmer math + `canTrim`; payload field-by-field encoding incl. `size_bytes` and `trim_m`; photo export ladder + deadline + backstop + `.photosDropped`; uploadAllMedia order/retry/partial contract; repair-record schema + identity verification + stale clearing; every user-facing copy string; talkDuration clamp; the cachedShare short-circuit behavior.

**Verification:** spec exists with `@38ef6b2` citations on every claim; the five previously-stale details (transcription-null, photos-first, auto-retry/partial UX, ladder 0.8→0.2, "Trim start & end") appear with direct quotes.

---

### U3. Tour payload: TourBuilder, RouteTrimmer, pauses

- [ ] **Status: Not Started**

**Goal:** A pure, test-pinned Kotlin layer that builds the exact tour/pauses payload iOS sends — before any UI or I/O exists.

**Requirements:** R5, R6.

**Dependencies:** U2.

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/TourBuilder.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/RouteTrimmer.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/SharePayload.kt` (add `tour: Tour?`, `pauses: List<Pause>?`; make `Photo.data` nullable)
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/SharePayloadBuilder.kt` (`WalkShareOptions` gains `interactive`, `trimEnabled`, `excludedRecordingUuids`; trim applies to the route before every downstream consumer)
- Test: `TourBuilderTest.kt`, `RouteTrimmerTest.kt`, `SharePayloadTourTest.kt` (mirror the iOS test files' scenarios at the pin)

**Approach:**
- TourBuilder consumes `VoiceRecording` + artifact sizes (from U4's store; in this unit, sizes are plain `Long?` inputs) and produces candidates with `unavailableReason` (deleted file → "audio removed" analogue; `size > 15 MB` → "too large to carry"), included-set renumbering `n = 1..N`, and `validationError` from the three aggregates only (count > 12, bytes > 60 MB, seconds > 45 min).
- Classification at the pin: `transcription` present-but-blank or wordCount < 8 → `ambient`; untranscribed → `spoken`; no wpm gate. Payload `transcription` field is **always null** (iOS `728c9e1` invariant); wpm rides only on included entries.
- `pauses`: only when interactive; `end > start` on truncated-integer timestamps; cap 200; source data per the spec's pinned join (the same persisted pause data `PauseContext` reads).
- RouteTrimmer: ~150 m path-distance off both ends, `trim_m` recorded in the tour, `canTrim` false for degenerate short walks (exact threshold per spec) — runs inside `SharePayloadBuilder.build` so map/og/tour inherit.

**Test scenarios:**
- Classification: blank-transcription → ambient; 7-word → ambient; 8-word slow speech → spoken (the PR #59 regression); untranscribed → spoken; every payload entry has null transcription (the privacy invariant, mirrored from iOS's pin test).
- Caps: 13 included → validationError; one 16 MB artifact → that row unavailable, no validationError, share still valid; 61 MB / 46 min totals → validationError; renumbering skips excluded and unavailable rows.
- Trim: point-count and endpoint assertions on a synthetic route; `trim_m` present only when trimming ran; degenerate 200 m walk → `canTrim` false, route untouched.
- Pauses: non-interactive build → null; zero-length span dropped; 201 spans → 200.
- Payload: interactive build omits `Photo.data`; classic build byte-identical to pre-Phase-19 output (regression fixture).

**Verification:** all scenarios green; classic-payload regression fixture unchanged.

---

### U4. WAV→AAC transcode prep pipeline (Android-original)

- [ ] **Status: Not Started**

**Goal:** Included recordings become cached M4A artifacts with real sizes, produced when Interactive toggles on, cancellable, with watertight cleanup.

**Requirements:** R9, R12.

**Dependencies:** U2 (constants), independent of U3.

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/ShareAudioTranscoder.kt` (interface + `MediaCodecShareAudioTranscoder`: WAV PCM → AAC-LC mono 64 kbps @ source rate via `MediaCodec` + `MediaMuxer`)
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/SharePrepStore.kt` (per-walk prep state: per-recording `Preparing/Ready(sizeBytes)/Failed`; artifact path function; cleanup + orphan sweep)
- Test: `MediaCodecShareAudioTranscoderTest.kt` (Robolectric: real `MediaFormat`/`MediaMuxer` construction per the builder rule), `SharePrepStoreTest.kt`, `FakeShareAudioTranscoder` for VM tests

**Approach:**
- Transcoder is a suspend function IO-bound at the seam; reads the WAV via the existing `VoiceRecordingFileSystem` path convention; honors cancellation between buffers; deletes its partial output on cancellation/failure.
- Artifacts at `cacheDir/share-prep/<walkUuid>/<recordingUuid>.m4a`; write and delete through one path function (Stage 5-D lesson); Decision 3's cleanup triggers + startup orphan sweep mirroring `OrphanRecordingSweeper`'s guards.
- Failure marks the recording `Failed` — downstream treats it exactly like an upload failure (excluded from tour with the row explaining itself; spec pins the copy).
- Robolectric cannot run real codecs (R12): the Robolectric test exercises format/muxer construction and the WAV-header parse; a real 10-second encode is a U9 device-QA line item.

**Test scenarios:** happy path state stream Preparing→Ready with real size; cancellation deletes partial artifact; exclusion mid-encode cancels + deletes; re-toggle re-uses existing Ready artifact without re-encoding; orphan sweep removes dirs with no active repair record and spares active ones; `MediaFormat`/`MediaMuxer` builders construct on the production class.

**Verification:** unit suite green; a dev-machine ffmpeg decode of one emitted artifact plays (documented command in the PR).

---

### U5. Hi-res tour photo export

- [ ] **Status: Not Started**

**Goal:** Interactive shares export 1600 px EXIF-free JPEGs under 2 MB with the pin's ladder, deadline, and short-export detection.

**Requirements:** R7, R12.

**Dependencies:** U2; independent of U3/U4.

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/TourPhotoExporter.kt`
- Test: `TourPhotoExporterTest.kt` (Robolectric NATIVE graphics mode — precedent `GlyphAssetTest`)

**Approach:**
- Same MediaStore URI source as the classic embedder (Decision 5); full decode with EXIF orientation applied, long edge scaled to 1600 px, then quality ladder 0.8→0.65→0.5→0.35→0.2 until ≤ 2 MB (re-encode drops EXIF by construction).
- `prefix(20)` on the export list; wall-clock deadline + backstop per the spec's pinned values; per-photo failure/unresolvable URI counts toward the short-export result `{exported, requested}` that U8 turns into the consent pause.
- Cancellable between photos.

**Test scenarios:** oversized synthetic bitmap steps down the ladder until it fits; EXIF-rotated fixture comes out upright with no EXIF block; unresolvable URI → counted short, others still export; 25 pinned photos → 20 exported; deadline elapsing mid-batch returns partial + short.

**Verification:** suite green in NATIVE graphics mode.

---

### U6. Media uploads and the repair record

- [ ] **Status: Not Started**

**Goal:** Sequential PUTs with the pin's order/retry/partial contract, backed by a kill-safe repair record that makes wrong-slot uploads impossible.

**Requirements:** R8, R10, R12.

**Dependencies:** U2; consumes U4's artifact paths and U5's export output types.

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ShareService.kt` (add `uploadMedia(shareId, photos, audio, onProgress): MediaUploadResult` — photos first in index order, then audio; one automatic retry per item; `Content-Type: image/jpeg` / `audio/mp4`; `X-Device-Token` from the existing token source)
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ShareRepairStore.kt` (DataStore sibling of `CachedShareStore`: share id, per-slot identity per the spec's pinned mechanism, per-slot status; stale-clearing per the pin)
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ShareResult.kt` (partial outcome: url + failed counts + repairability)
- Test: `ShareMediaUploadTest.kt` (MockWebServer: order, retry, partial accumulation, token header; Robolectric `Request`-builder coverage), `ShareRepairStoreTest.kt`

**Approach:**
- Order is load-bearing (photos gate the worker's keepsake render; audio degrades to "voice unavailable") — assert it in tests, not just comments.
- Record written after POST returns `{id}`, before the first PUT; every slot outcome lands in the record as it happens (kill-safe); identity verification per spec so a repair PUT can never land in another slot; on cancellation, untried slots are marked pending first (the pin's doomed-PUT gate translated).
- Repair path re-uses `uploadMedia` scoped to failed/pending slots; a missing artifact re-encodes via U4 (Decision 3); repair-unavailable when identities no longer resolve, with the spec's explanation copy.

**Test scenarios:** photos PUT before audio, index order; each item retried exactly once then accumulated as failed; partial result still carries the share url; process-death simulation (drop service mid-batch, rebuild from record) resumes only unfinished slots; identity mismatch refuses the slot; stale record cleared per pinned condition; every `Request` built on the production class.

**Verification:** suite green incl. MockWebServer flows.

---

### U7. Interactive consent UI

- [ ] **Status: Not Started**

**Goal:** The share screen grows the Interactive section and status card, pixel-parity with the pin's structure and copy.

**Requirements:** R4, R6, R7 (surface), R9 (surface).

**Dependencies:** U2; state shapes from U3/U4 (compiles against fakes).

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/InteractiveShareSection.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/ShareStatusSection.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/WalkShareScreen.kt`
- Test: `InteractiveShareSectionTest.kt`, `ShareStatusSectionTest.kt` (Robolectric Compose)

**Approach:**
- Interactive toggle (default off) + subtitle; recordings disclosure rows (index title, duration, start time, transcript preview; included-by-default switch; grayed "audio removed" and "too large to carry" states; "preparing…" until U4 sizes land); photos toggle auto-enable; voices warning mirroring photos; aggregate-caps copy gating the Share button; "Trim start & end" toggle with `canTrim` subtitle states. All copy strings verbatim from the spec's quotes.
- Status card: "Carrying your walk… N/M" + "keep Pilgrim open while your walk uploads"; partial card with failed count + "Carry the missing files"; repair-unavailable explanation; photosDropped pause dialog ("Share without them" / "Don't share yet").
- Accessibility: rows expose toggle semantics; status announces progress (TalkBack pass in U9).

**Test scenarios:** toggle off hides everything new; rows render all four states (included/excluded/audio-removed/too-large); Share disabled under each aggregate cap with its copy; trim toggle defaults on-when-interactive and respects `canTrim`; status card renders uploading/partial/repair states from fake state.

**Verification:** Compose tests green; screenshot of each state attached to the PR.

---

### U8. Orchestration: WalkShareViewModel interactive flow

- [ ] **Status: Not Started**

**Goal:** The full state machine — prep, consent, POST, uploads, partial, repair — with every R8 invariant, wired end-to-end behind the Interactive toggle.

**Requirements:** R4, R8, R9, R10.

**Dependencies:** U3–U7.

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/WalkShareViewModel.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/WalkShareOrchestration.kt` (extension-file split mirroring iOS if the VM would exceed its current shape; fold in if small)
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/di/ShareModule.kt` (bind transcoder, exporter, repair store)
- Test: extend `WalkShareViewModelTest.kt` + new `WalkShareInteractiveTest.kt` (Turbine; fakes from U4/U5/U6)

**Approach:**
- Interactive toggle drives U4 prep (launch on toggle-on, cancel + clean on toggle-off); exclusion updates cancel that row's encode; Share gates on all-included-Ready plus no aggregate validationError.
- `share()` invariants: `completeShare` lock claimed via `compareAndSet` before launch; form frozen while in flight; photo short-export pauses pre-POST for consent, decline cancels wholly; excluded recordings leave no trace (talk intervals and stats recomputed over included only); `talkDuration = min(Σ included durations, walk.talkDuration)`; partial outcome caches the share and leaves the record; success clears record + artifacts; stale-record clearing on load per pin.
- `cachedShare` short-circuit untouched (first-share-only); re-entry with a repair record present surfaces the repair card instead of re-POSTing.
- Every new suspend call inside long-lived collectors defended, CE re-thrown (house rule).

**Test scenarios:** toggle-on→prep→ready→share happy path ends Shared with record cleared; kill-after-voice-2 simulation resumes via record without re-encode (AE2); exclusion mid-prep never uploads that recording and stats follow (AE3); consent-decline cancels with nothing sent; double-tap share launches once (lock); talk clamp caps a pause-spanning recording set (worker-400 regression); interactive-off share byte-matches the U3 regression fixture (AE1); partial outcome reveals url + repair offer.

**Verification:** Turbine suite green; manual emulator smoke of the happy path.

---

### U9. Device QA and release v1.4.0

- [ ] **Status: Not Started**

**Goal:** R13's QA pass on hardware against production, then the v1.4.0 release.

**Requirements:** R13, R14, R2.

**Dependencies:** U1–U8.

**Files:**
- Modify: `app/build.gradle.kts` (versionName 1.4.0, versionCode per release infra)
- Modify: `CLAUDE.md` (phasing note: Phase 19 shipped), plan status → completed
- Create: QA notes under `docs/qa/` per house pattern

**Approach — QA checklist (OnePlus 13, production worker):**
- Contract test: real interactive share with ≥2 voices + ≥2 photos; story page renders voice chapters audible + photos full-bleed in a desktop browser and Android Chrome (AE6).
- Real transcode envelope: a 20+ min recording encodes under the per-file cap with acceptable latency (AE4); note wall-clock.
- Kill mid-upload after voice 2 → relaunch → repair resumes, no re-encode, no mis-slot (AE2).
- Background-without-kill mid-upload past the background-execution window → return → resumed or honestly reported paused.
- Airplane mode at POST and mid-PUT → failure paths + later repair.
- Interactive-off regression share → classic page unchanged (AE1).
- Already-shared walk re-entry → Shared state short-circuit (first-share-only).
- TalkBack pass over the new section + status card.
- Pre-tag: re-diff `38ef6b2..ios-main`, triage per R2 (fold-in or user re-triage), re-pin if folded.
- Release per house infra: one production.yml dispatch; staged rollout; memory/docs updated.

**Verification:** every checklist line has a written result in the QA notes; release live.

---

## System-Wide Impact

- `SharePayload` gains optional fields — classic (interactive-off) encoding must stay byte-identical (U3 regression fixture guards it; worker tolerates the new optionals by design).
- New cache surface (`share-prep/`) with its own sweeper — cannot orphan-accumulate (Decision 3).
- ShareService gains long-running sequential I/O on a VM scope — no service/notification in v1.4.0 (Decision 4); the R13 background scenario is the honesty check.
- No schema/DB changes; no new permissions; APK size unchanged (no bundled assets).

## Risk Analysis & Mitigation

- **16 kHz AAC playback on the page** — probed in U1 before any code; contingency: 32 kHz resample (Decision 1).
- **Spec-vs-shipped drift** (bit five details in the origin doc already) — U2 spec re-derives everything from Swift; plan defers to it by construction.
- **MediaCodec device variance** — builder-rule Robolectric tests + U9 hardware encode; encoder is AAC-LC mono, the most-supported profile on Android.
- **Repair-record edge states** (record without artifacts, artifacts without record) — orphan sweep + re-encode-on-miss close both directions.
- **iOS fast-follow (background-URLSession) landing pre-release** — R2 re-diff at U9 catches it; Decision 4 explicitly avoids pre-building.

## Phased Delivery

Single phase (19), single release (v1.4.0). Order: U1 → U2 → {U3, U4, U5 in any order/parallel} → U6 → U7 → U8 → U9. Each unit is one PR with the house closing-adversarial review; no PR merges without user instruction.

## Documentation / Operational Notes

- U1's skill-anchor edits are outside the repo (`~/.claude/skills/ios-parity/`) — note them in the U1 PR description since they won't appear in the diff.
- The disposable probe share (U1) should use the shortest expiry and its URL recorded in the PR for cleanup verification.
- Play Store listing: consider a "share walks as interactive stories" line at release (U9, optional).

## Sources & References

- Origin requirements (incl. full delta triage + resolved decisions): `docs/brainstorms/2026-08-14-ios-v1100-parity-retarget-requirements.md`
- ce-doc-review round 1 findings (11 applied): commits `995dd063`, `c8a731e8`
- Prior retarget plan (structure precedent): `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md`
- iOS pin: `pilgrim-ios` @ `38ef6b2` (2026-08-13)
