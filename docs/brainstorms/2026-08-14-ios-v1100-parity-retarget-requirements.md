---
date: 2026-08-14
topic: ios-v1100-parity-retarget
---

# iOS v1.10.0 Parity Retarget — Walk with Me Interactive Share

## Summary

Re-pin the frozen Android parity anchor from `b4decad` (the commit iOS tagged v1.9.1) to iOS main tip `38ef6b2` (2026-08-13, the v1.10.0 candidate awaiting App Store approval), then port the one feature iOS shipped since: the "Walk with Me" interactive share, as Phase 19. Ships as Android v1.4.0, single release.

---

## Problem Frame

Android v1.3.0 shipped at exact parity with `b4decad`; iOS's v1.9.1 tag sits on that same commit, so the delta is exactly v1.9.1 → main tip: 42 commits, `38ef6b2` at the head, submitted for App Store review as v1.10.0 (untagged until approval).

| iOS delta | Content | Android disposition |
|---|---|---|
| PR #58 (`feat/walk-with-me-interactive`) | Interactive share: opt-in tour payload, spoken/ambient classification, route trimming, hi-res photo export, sequential media PUTs with repair, consent UI (~2,900 lines) | Port — Phase 19 |
| PR #59 (`fix/classifier-wpm-gate`) | Classification refinement: slow speech is still speech — wpm ambience gate removed | Absorbed by Phase 19 (end-state port) |
| `38ef6b2` whispers-bootstrap v5 (wonder-4) | New bundled whisper + bootstrap refresh | No-op: Android's `WhisperManifestService` is CDN-fetch-only (the bundled bootstrap was explicitly deferred at port time), so wonder-4 arrives via the remote manifest with no app change |
| PR #54 (`chore/bootstrap-freshness-check`), build bumps, screenshot-seeder demo audio | iOS release tooling and chores | Out of scope (standing precedent) |

The story page itself — chapters, map canvas, audio engine, expiry — lives in `pilgrim-worker`, is already deployed serving iOS, and has evolved past the spec (tour-v3, arrival cairns). Android ports only the client side: what the app builds, uploads, and shows.

---

## Requirements

**Anchor re-pin**
- R1. The frozen parity anchor becomes `pilgrim-ios` @ `38ef6b2` (main tip, 2026-08-13). Update the parity-scope section of `CLAUDE.md`, the `ios-parity` skill's pinned anchor (SKILL.md still cites `9a418e4`; the `ios-pin.sh`/`lib.sh` default still says `c1745e8`), and memory/doc references that name `b4decad` as the current target.
- R2. The fold-in rule carries forward unchanged: iOS commits landing before Android v1.4.0 ships (including the v1.10.0 tag landing on a later build-bump commit, or App Review churn) are re-diffed and triaged — chores, hotfixes, and incremental refinements to the share surface fold into Phase 19 (reopening it if closed; once v1.4.0 is code-complete and awaiting release, the delta becomes a named pre-release stage gated before the tag), then re-pin. A new headline feature, a revert of ported work, or a redesign triggers explicit user re-triage.

**Phase 19 — Walk with Me interactive share (iOS PRs #58 + #59)**
- R3. Port the end-state Swift at the pin onto the existing `data/share` + `ui/walk/share` stack. Spec sources: iOS `docs/superpowers/specs/2026-08-09-walk-with-me-tour-design.md` + `docs/superpowers/plans/2026-08-11-walk-with-me-tour-ios.md`, cross-checked against shipped code wherever review fixes diverged — shipped code wins (the spec's wpm gate no longer exists at the pin).
- R4. Consent UI parity: "Interactive" toggle (default off) with explanatory subtitle; recordings disclosure when the walk has recordings — one row per recording (index title, duration, start time, transcript preview), included by default, individually excludable, deleted-file rows grayed "audio removed"; auto-enable of the photos toggle when pinned photos exist; voices warning mirroring the photos one; caps copy disabling the share button when the selection exceeds ≤12 recordings, ≤15 MB per audio file, ≤60 MB / ≤45 min audio total, ≤20 photos, ≤2 MB per photo. Interactive off = today's share exactly; nothing new leaves the device.
- R5. Payload parity: `tour` object (per-recording `n/start_ts/end_ts/duration/kind/transcription/wpm/size_bytes` + `trim_m`) and `pauses`, sent per the pin's semantics; transcription/wpm ride only with included recordings; `Photo.data` omitted on interactive shares; classification per `TourBuilder` end-state (word-count floor retained, wpm gate removed, empty-vs-absent transcription distinction as shipped).
- R6. Route trim parity: ~150 m path-distance trim off both ends before payload build, so the static map, og image, and tour all inherit it; "Soften start & end" toggle default on when Interactive is on, off otherwise; `canTrim` derivation and degenerate-short-walk behavior as shipped.
- R7. Photo parity: interactive shares export at high resolution — 1600 px long edge, q0.7, EXIF-free by re-encode, ≤2 MB quality ladder — uploaded via PUT to the same keys the page already references; the classic 600×600 base64 path is unchanged when Interactive is off. Photo prep is cancellable with a wall-clock deadline and a backstop, per the pin.
- R8. Upload orchestration parity: after `POST /api/share` returns `{id}`, sequential `PUT /api/share/{id}/audio/{n}` then `/photos/{n}` with `X-Device-Token`; per-file failure offers Retry/Skip (skipped audio degrades to transcript-only on the page); progress copy ("Uploading voice 2 of 5"); link revealed when uploads complete, no finalize handshake. Behavioral invariants from the iOS review rounds carry over: form frozen while a share is in flight; completeShare claims the lock; consent decline cancels; excluded recordings leave no trace (talk intervals and stats follow consent); kill-safe repair record with media identity verification (wrong-slot uploads impossible); stale repair records cleared.
- R9 (Android-original). WAV→AAC transcode: Android records WAV, while iOS uploads its already-AAC `.m4a` files untranscoded — that rationale doesn't transfer. Transcode included recordings to AAC-LC mono in an M4A container at source sample rate during share preparation — cancellable and progress-surfaced — so `size_bytes` and the client-side caps are computed from the actual artifacts that will be PUT. A transcode failure degrades exactly like an upload failure (skip → transcript-only). Transcoded files cache alongside the repair record so retries don't re-encode; cleanup follows the repair record's lifecycle.
- R10 (Android-original). Upload lifecycle: iOS holds an expiring background assertion across uploads; Android chooses its mechanism at planning (scoped coroutine vs WorkManager continuation). The parity bar is the repair semantics — interrupted uploads are resumable, never mis-slotted, and honestly reported — not the mechanism.

**Acceptance and verification**
- R11. Phase 19 stages get `/ios-parity port` specs with Swift quotes pinned to `38ef6b2` before implementation.
- R12. Unit tests per house rules, including the platform-object builder Robolectric rule — the PUT `Request` builders, `MediaFormat`/`MediaMuxer` construction, and any notification/WorkRequest surface qualify. The MediaCodec hardware encode path is device-verified (Robolectric cannot exercise real codecs).
- R13. One consolidated on-device QA pass (OnePlus 13) including: a real end-to-end interactive share against the production worker verifying the story page renders voice and photo chapters from an Android-created payload (the cross-platform contract test); interrupted-upload repair (process kill mid-upload, relaunch, resume); airplane-mode failure paths; and an Interactive-off regression share.

**Release**
- R14. Single Android release: v1.4.0 containing Phase 19 and the re-pin. Android versioning stays independent of iOS's 1.10.0.

---

## Acceptance Examples

- AE1. **Covers R4.** Given Interactive off, when the user shares a walk with recordings, the payload carries no tour object and no transcripts, no audio uploads occur, and the rendered share page is today's classic page.
- AE2. **Covers R8, R9, R10.** Given the app is killed mid-upload after voice 2 of 5, when the user returns to the share screen, the repair path resumes from the repair record without re-encoding, and no file can land in another file's slot.
- AE3. **Covers R5, R8.** Given a walk with three recordings where the user excludes the second, the payload carries transcripts/wpm for recordings 1 and 3 only, talk intervals and stats reflect the exclusion, and recording 2's audio never leaves the device.
- AE4. **Covers R9.** Given a 20-minute recording whose WAV exceeds the 15 MB per-file cap, when share prep transcodes it to AAC, the artifact fits the cap and the caps copy judges the transcoded size, not the WAV size.
- AE5. **Covers R2.** Given App Review approves and the v1.10.0 tag lands on a build-bump commit after `38ef6b2`, when the delta is re-diffed, it folds in and the anchor re-pins without reopening this doc.
- AE6. **Covers R13.** Given an interactive share created on the OnePlus 13 against the production worker, when its URL opens in a browser, the story page renders with the walk's voice chapters audible and hi-res photos full-bleed.

---

## Success Criteria

- Android v1.4.0's interactive share matches its R11 parity specs (Swift quotes pinned at `38ef6b2`), confirmed by the R13 device QA pass including the production contract test.
- `CLAUDE.md` and the `ios-parity` skill name `38ef6b2` as the frozen parity target; nothing still points at `b4decad` (or the stale `9a418e4`/`c1745e8` skill defaults) as current.
- An Interactive-off share sends no audio or transcripts and renders today's classic page.
- Planning can start Phase 19 from this doc plus the iOS spec/plan docs without inventing scope, sequencing, or acceptance criteria.

---

## Scope Boundaries

- Worker-side work of any kind — the tour engine, story page, expiry, and their post-spec evolution (tour-v3, arrival cairns) belong to `pilgrim-worker`, already live and serving both platforms.
- The whispers bundled bootstrap — a pre-existing deferred Android item, not part of this delta; wonder-4 arrives via the CDN manifest.
- iOS release tooling (PR #54's `scripts/release.sh`), build bumps, pbxproj churn, screenshot-seeder demo audio.
- Spec Phase 2/3 surfaces ("walk it for me" auto-advance, transcript sync highlighting, leave-a-stone client UI) — not in the iOS client at the pin.
- Recording-pipeline changes: capture stays WAV for whisper.cpp; transcode is upload-side only.
- Anything iOS ships after `38ef6b2`, except deltas folded in via R2.

---

## Key Decisions

- **Pin now at the main tip rather than await the v1.10.0 tag**: user directive ("the latest main is the version we want to target"), matching both prior retargets; R2 absorbs tag-time churn.
- **Single v1.4.0 release**: user choice; one QA and rollout cycle (v1.2.0/v1.3.0 precedent).
- **End-state port over commit replay**: established pattern; this delta's own history proves it — the spec's wpm gate was built, then deleted by PR #59; replay would port both.
- **Transcode at share prep, not at PUT time**: `size_bytes` and cap validation must reflect the real artifacts, and prepared artifacts give the repair record stable files to resume from.
- **Repair semantics as the lifecycle parity bar**: iOS's background-assertion mechanism has no clean Android analogue; what must survive translation is resumability, slot integrity, and honest reporting.

---

## Dependencies / Assumptions

- The production worker already accepts the tour contract (`POST /api/share` with `tour`/`pauses`, media PUTs, Range serving) — iOS v1.10.0 was submitted against it. Planning verifies by exercising the endpoints with a disposable share rather than assuming deploy state.
- Worker caps and endpoints are server-side constants mirrored client-side only for friendly early failure; the worker's page-side evolution doesn't change the client contract.
- MediaCodec AAC-LC mono encode at source sample rate lands typical recordings comfortably under the caps (bitrate chosen at planning; the 15 MB per-file cap bounds a single recording's practical length the same way it does on iOS).
- Pause intervals are derivable from existing walk data (the Phase 17 prompt-context work already computes pauses).
- Android v1.3.0's staged rollout promotes independently of this work.
- iOS shipped Swift at `38ef6b2` is authoritative where it diverges from the iOS spec/plan docs.

---

## Outstanding Questions

### Deferred to Planning

- [Affects R9][Technical] Transcode bitrate/profile and prep UX — blocking prep step vs background prep with the share button gated; where prep artifacts live (cacheDir vs alongside the repair record).
- [Affects R10][Technical] Upload lifecycle mechanism — VM-scoped coroutine with repair record vs WorkManager continuation; how Android reports "uploads paused" honestly without an iOS-style background budget.
- [Affects R5][Technical] Exact `pauses` semantics at the pin (threshold, whether sent when Interactive is off) and the Android source subsystem for pause intervals.
- [Affects R7][Technical] Hi-res export source on Android (MediaStore original vs stored copy) and the quality-ladder implementation.
- [Affects R4][Technical] How the share flow handles a walk shared while transcription is still pending (untranscribed recordings' kind/preview at the pin's semantics).
