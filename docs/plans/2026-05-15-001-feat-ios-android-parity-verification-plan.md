---
title: "feat: iOS↔Android parity verification last-mile ship gate"
type: feat
status: active
date: 2026-05-15
origin: docs/brainstorms/2026-05-15-ios-android-parity-verification-requirements.md
---

# feat: iOS↔Android Parity Verification — Last-Mile Ship Gate

## Summary

Stand up a screen-indexed parity ledger driven mechanically from the pinned iOS source, where every screen/state is captured on both platforms against one shared `.pilgrim` seed, diffed visually + behaviorally (Swift quoted inline), and verdicted by a fresh agent blinded to "intentional divergence" rationale. The plan builds the enumeration, the shared fixture, the capture+diff loop, the blinded review, and the triage/gate — not the gap fixes themselves.

---

## Problem Frame

User-visible parity defects keep reaching the user despite code review and ad-hoc device QA (Mapbox whole-surface crash, all-bells-identical, constellation pill band, post-walk intention prompt). Root cause: memory-driven, confirmation-biased verification with no deterministic screen enumeration, no shared cross-platform fixture, and no review step denied the "this is intended" context. See origin for full pain narrative and the ship bar (pixel + behavior parity on every screen; every divergence re-litigated).

---

## Requirements

- R1. Screen×state inventory generated mechanically from iOS source, not memory (origin R1, R9).
- R2. One curated `.pilgrim` seed renders the same data (incl. edge states) on both platforms via the existing importers (origin R2).
- R3. Each screen row carries paired iOS-sim + Android screenshots in the same seeded state (origin R3).
- R4. Each screen row carries the iOS Swift quoted inline next to the Android implementation claim (origin R4).
- R5. Each screen reviewed by a fresh agent given only screenshots + behavior pair, NOT divergence rationale (origin R5, R8).
- R6. Three-verdict ledger (`match` / `close-the-gap` / `re-justify`); stubbed-native features cannot auto-`match` (origin R6).
- R7. Animated screens additionally carry a motion capture, not only a still (origin R7).
- R8. Ship gate = zero unverified rows AND zero unresolved `close-the-gap`/`re-justify` rows (origin R10).
- R9. Ledger is regenerable and reconcilable against iOS source as it changes (origin R1, F3).

**Origin actors:** A1 Enumerator, A2 Capturer, A3 Behavior differ, A4 Adversarial reviewer (blinded), A5 Triage owner (human/user).
**Origin flows:** F1 per-screen parity pass, F2 divergence re-litigation, F3 coverage reconciliation.
**Origin acceptance examples:** AE1 (R5,R8 blinded reviewer on Map fallback), AE2 (R2 108-walk milestone seed), AE3 (R1,R9 new iOS state blocks gate), AE4 (R7 constellation motion capture).

---

## Scope Boundaries

- Fixing gaps is out of scope — this plan finds + adjudicates; remediation is downstream `ce-plan`/`ce-work` per gap cluster (origin).
- Re-architecting the port to erase platform-idiom divergence is not assumed — it is a per-row triage decision, not a precondition (origin).
- Non-screen parity (background services, notifications, Glance widget) is not covered by the screen ledger (origin).

### Deferred to Follow-Up Work

- Permanent CI screenshot-regression harness (full automated nav-walker on every build): separate follow-up after the one-time gate proves the ledger shape.

---

## Context & Research

### Relevant Code and Patterns

- `docs/parity/` already holds per-surface audit docs (`2026-05-12-active-walk-path-audit.md`, `2026-05-14-settings-audit.md`, walk-summary v1/v2, home-journal). **These are STALE** — they predate the v1.6.0 port work and this session's bug fixes (Mapbox guard, bell double-fix, constellation pill/logo, intention gating, vignette). Reuse ONLY their per-screen evidence *shape/format* as precedent; do not treat their findings as current parity state. U1 marks each prior audit `superseded-by: 2026-05-15-parity-ledger.md` (header note, not deleted — kept for audit trail). The new ledger is the single source of current parity truth.
- The `ios-parity` skill (`~/.claude/skills/ios-parity/` — subagents, runbooks, scripts) already implements the Swift-quoted per-slice parity-spec mechanism anchored to a pinned iOS SHA. Reuse the *pattern* (Swift quote next to Android claim, pinned-SHA anchor); do not invoke the skill (user constraint on the port; this is verification).
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageImporter.kt` is the Android import entrypoint for the shared `.pilgrim` seed. iOS `PilgrimPackageImporter.swift` is the symmetric carrier. v1.6.0 tended/archived import already round-trips, so the seed can carry archived + tended states.
- iOS nav surfaces enumerable from `pilgrim-ios/Pilgrim/Scenes/**` + `Pilgrim/Views/**` `.sheet` / `.fullScreenCover` / `NavigationStack` declarations; Android from `ui/navigation/PilgrimNavHost.kt` `Routes` + composable graph.
- adb screenshot + `uiautomator dump` flow already exercised this session for Android capture; `xcrun simctl io <udid> screenshot` is the iOS-sim symmetric.

### Institutional Learnings

- Autopilot memory: parity bugs are state-specific (no-token map, 108-walk milestone, archived walk) and escape fakes/mocks — enumeration MUST be state-level, capture MUST hit real seeded states (origin R9; this session's bell + Mapbox escapes).
- `CLAUDE.md` parity target is pinned (iOS v1.6.0, `fcd2255`); reconciliation diffs are stable against that tag.

### External References

- None. Local parity-audit patterns + the ios-parity skill mechanism are sufficient; external research skipped (announced).

---

## Key Technical Decisions

- Enumeration source = static scan of iOS `Scenes/**` + `Views/**` presentation modifiers, cross-checked against Android `Routes`, NOT a runtime crawl: deterministic, regenerable, diffable across iOS changes; a runtime crawl can't reach gated states without the seed anyway. (Runtime-crawl alternative deferred — see Open Questions.)
- Shared fixture = one curated `.pilgrim` imported by both apps (origin Key Decision). States `.pilgrim` cannot encode (live in-progress walk, reduce-motion, transient overlays) are reached per-row at capture time with a documented manual recipe, not a parallel seeder that could itself drift.
- Reviewer blinding enforced structurally: the per-screen review prompt is assembled to contain only the two screenshots + the Swift/Android behavior pair. `CLAUDE.md`, in-code "accept this divergence" comments, and the origin doc are excluded from that prompt. Rationale is attached only at the A5 triage step.
- Ledger lives as one Markdown index in `docs/parity/` (`2026-05-15-parity-ledger.md`) with one row per screen×state: it is the single regenerable artifact `ce-plan` consumes per gap cluster (origin R10 / Deferred-to-Planning resolved here).
- Motion capture = short `adb shell screenrecord` + `xcrun simctl io recordVideo` only for the enumerated animated screens (welcome, seal reveal, constellation overlay, stats reveal, calligraphy draw); stills elsewhere — bounds carrying cost (origin Key Decision).

---

## Open Questions

### Resolved During Planning

- Shared fixture source: curated `.pilgrim` import via existing importers (origin blocking question, resolved in brainstorm).
- Ledger format/location: single regenerable Markdown index in `docs/parity/` (origin Deferred-to-Planning).
- External research: skipped — local patterns sufficient.

### Deferred to Implementation

- Exact static-scan technique for the iOS enumeration (regex over presentation modifiers vs a small Swift-syntax pass): settle when U1 touches real `Scenes/**` files; the enumeration *contract* (every screen×state, regenerable) is fixed regardless.
- Whether some gated iOS states (e.g., 108-walk milestone celebration mid-animation) need an iOS debug affordance to capture deterministically, or a still at the right frame suffices: discovered when U5 captures them.
- Cadence-parity evaluation *method* for motion captures (frame-sample diff vs reviewer side-by-side playback): U6 defines this method; **U5 still owns the per-row capture+review loop and invokes the U6 method for animated rows** — U6 does not run its own parallel review. Method settled when the first animated row is reviewed.

---

## High-Level Technical Design

> *This illustrates the intended approach and is directional guidance for review, not implementation specification. The implementing agent should treat it as context, not code to reproduce.*

```
iOS source (pinned fcd2255)                Android (this branch)
   │  static scan Scenes/** Views/**          │  Routes + composable graph
   ▼                                          ▼
[U1] enumerate screen×state ──────────────► parity-ledger.md  (one row per screen×state, all "unverified")
                                                  │
[U2] curate ONE .pilgrim seed (edge states)       │
        │ import on iOS sim     │ import on Android device
        ▼                       ▼                 │
[U5] per row: drive both into the seeded state ───┤
        ├─ iOS-sim screenshot  ─┐                 │
        ├─ Android screenshot  ─┤ paired evidence │
        ├─ [U6] motion capture (animated rows) ─┐ │
        └─ Swift quote + Android claim ─────────┴►│ row.evidence
                                                  ▼
[U4] blinded reviewer prompt = screenshots + motion + behavior pair ONLY
        (no CLAUDE.md, no "accept divergence" comments, no origin)
        (U6 supplies the cadence-eval method; U5 still owns the loop)
                                                  ▼
                                          verdict ∈ {match, close-the-gap, re-justify}
                                                  ▼
[U7] A5 triage: attach rationale NOW → close vs re-justify (dated) → gate
[U3] reconcile: re-scan iOS → new/removed rows → unverified rows block gate
```

---

## Implementation Units

### U1. iOS-driven screen×state enumeration → empty ledger

**Goal:** Produce `docs/parity/2026-05-15-parity-ledger.md` with one row per iOS screen×state, every row `unverified`, generated by a documented mechanical scan — not memory.

**Requirements:** R1, R9 (AE3)

**Dependencies:** None

**Files:**
- Create: `docs/parity/2026-05-15-parity-ledger.md`
- Create: `docs/parity/README-parity-ledger.md` (the regeneration recipe: which iOS globs are scanned, how states are derived, how to re-run)
- Modify: `docs/parity/2026-05-1{0,1,2,4}-*-audit.md` (prepend a one-line `> SUPERSEDED by docs/parity/2026-05-15-parity-ledger.md — findings are pre-v1.6.0 and stale` banner; do not delete — audit trail)
- Reference (read-only): `../pilgrim-ios/Pilgrim/Scenes/**`, `../pilgrim-ios/Pilgrim/Views/**`, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`

**Approach:**
- Scan iOS for every screen surface (`NavigationStack`/`NavigationLink` destinations, `.sheet`, `.fullScreenCover`, `.popover`, root tab scenes) and, per screen, enumerate the distinct render states from its view-state enum/`switch` (loading/empty/error/populated/milestone/archived/reduce-motion/etc.).
- Cross-check against Android `Routes` so an iOS screen with no Android route is itself a row (a parity gap by absence).
- Row schema: `id | iOS source ref | screen | state | android route | seed requirement | evidence(iOS shot, android shot, motion?) | swift-quote ref | verdict | triage note`.
- Pin the iOS ref column to the parity-target SHA (`fcd2255`, v1.6.0) per `CLAUDE.md`.

**Patterns to follow:** existing `docs/parity/*-audit.md` evidence shape; `ios-parity` skill's pinned-SHA anchoring (pattern only, not invoked).

**Test scenarios:**
- Happy path: every iOS `.fullScreenCover`/`.sheet`/tab scene under `Scenes/**` appears as ≥1 ledger row.
- Edge case: a screen with N render states yields N rows, not 1 (e.g., Goshuin grid → empty / populated / 108-milestone / archived-present).
- Covers AE3: re-running the scan after an artificial iOS screen addition produces a new `unverified` row and the regeneration recipe reproduces the same ledger deterministically (diff-noise-free aside from the new row).
- Test expectation: assertion is the regeneration determinism check above, not a unit test — this unit is a doc+recipe artifact.

**Verification:** Ledger lists every iOS screen×state; re-running the documented recipe regenerates an identical ledger (modulo intentionally-changed iOS source); no row was added from memory without a source ref.

---

### U2. Curated `.pilgrim` seed fixture covering edge states

**Goal:** One `.pilgrim` file that, imported by both iOS and Android, yields the same walk history + settings + edge states for comparable capture.

**Requirements:** R2 (AE2)

**Dependencies:** U1 (the ledger's `seed requirement` column defines which states the seed must encode)

**Files:**
- Create: `docs/parity/fixtures/parity-seed.pilgrim`
- Create: `docs/parity/fixtures/README-seed.md` (what states it encodes; the manual capture recipe for states `.pilgrim` cannot carry)
- Reference (read-only): `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageImporter.kt`, `../pilgrim-ios/Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift`

**Approach:**
- Hand-build a `.pilgrim` containing: ≥108 finished walks (sacred-number milestone), ≥1 archived walk (`manifest.archived[]`), a tended re-import marker (`manifest.modifications[]`), walks with photos/voice recordings/long transcripts, a long-duration walk, and varied weather/celestial dates.
- Verify it round-trips through BOTH importers to an equivalent state (Android `ImportSummary` counts == iOS import result; spot-check a walk renders the same surface stats).
- Document which ledger states the `.pilgrim` cannot encode (live in-progress walk, reduce-motion system setting, transient overlays) and the deterministic manual recipe to reach each at capture time.

**Patterns to follow:** v1.6.0 tended/archived import path already verified this session; reuse the same import entrypoints.

**Test scenarios:**
- Covers AE2: importing the seed on both platforms yields ≥108 finished walks on each, so the Goshuin milestone state is comparable, not absent on one side.
- Happy path: Android `PilgrimPackageImporter.import` returns an `ImportSummary` whose added/replaced/archived counts match the iOS importer's result for the same file.
- Edge case: archived walk imports as a hollow-ring dot on both; tended marker triggers overwrite-by-UUID on both.
- Integration: a seeded walk's surface stats (distance/duration/steps) render identically on the Walk Summary of both platforms.

**Verification:** Both importers consume the seed without error and produce equivalent state; every seed-requiring ledger row has its state reachable from this seed or a documented manual recipe.

---

### U3. Ledger reconciliation recipe (coverage proof, F3)

**Goal:** A repeatable step that re-runs the U1 scan and diffs against the existing ledger so completeness is provable, not asserted.

**Requirements:** R9 (AE3, origin F3)

**Dependencies:** U1

**Files:**
- Modify: `docs/parity/README-parity-ledger.md` (add the reconcile step + how new/removed rows gate)
- Reference: `docs/parity/2026-05-15-parity-ledger.md`

**Approach:**
- Define reconcile = regenerate the screen×state set from iOS source → set-diff against ledger row ids → new ids inserted as `unverified`, removed ids flagged `stale` (not deleted, kept for audit).
- State the gate coupling: any `unverified` (incl. newly reconciled) row blocks ship (R8).

**Patterns to follow:** U1 regeneration recipe.

**Test scenarios:**
- Covers AE3: simulate an iOS source change adding a screen state → reconcile inserts exactly one `unverified` row → gate reports not-passable.
- Edge case: removing an iOS screen marks its rows `stale` without losing their historical verdict/evidence.
- Test expectation: determinism + set-diff correctness check, not a unit test (doc+recipe artifact).

**Verification:** Running reconcile on an unchanged iOS source is a no-op; on a changed source it produces exactly the expected row delta and the gate reflects it.

---

### U4. Blinded per-screen review protocol

**Goal:** A fixed protocol + prompt template guaranteeing the reviewer sees only paired screenshots + the Swift/Android behavior pair — never divergence rationale — and emits exactly one of three verdicts.

**Requirements:** R5, R6 (AE1, origin F1/F2)

**Dependencies:** U1

**Files:**
- Create: `docs/parity/review-protocol.md` (the blinded-reviewer contract: allowed inputs, forbidden inputs, verdict rubric, output row format)

**Approach:**
- Allowed reviewer inputs: iOS screenshot, Android screenshot, motion captures (if any), the Swift quote, the Android implementation claim, the screen+state label.
- Forbidden inputs (must not be in the review prompt): `CLAUDE.md`, in-code "accept/justify divergence" comments, the origin requirements doc, prior ledger triage notes for that row.
- Verdict rubric: `match` = visually + behaviorally indistinguishable from iOS within the state; `close-the-gap` = a difference that should be made to match iOS; `re-justify` = a difference that may be acceptable but needs an explicit current reason. Stubbed-native substitutions (Open-Meteo/Mapbox/whisper-cairn) are structurally barred from `match` (R6) — rubric instructs the reviewer to mark them `close-the-gap` or `re-justify` purely on the observed difference, with no knowledge they were "intentional".
- Reviewer is a fresh agent per row (no carryover of "this was intended" across rows).

**Patterns to follow:** adversarial fresh-reviewer discipline from autopilot memory (closing-adversarial review caught what built-in passes missed).

**Test scenarios:**
- Covers AE1: given the Android "Map unavailable" fallback vs iOS Mapbox map with rationale withheld, the protocol yields `close-the-gap` or `re-justify`, never `match`.
- Edge case: a pixel-identical screen with a documented behavioral divergence still gets the behavioral diff surfaced (Swift quote forces it) → not auto-`match`.
- Error path: a row missing one screenshot is rejected by the protocol as un-reviewable, not silently passed.
- Test expectation: protocol dry-run on 2-3 representative rows (Map fallback, a true match, an animated screen) confirms verdict behavior; no code unit test.

**Verification:** A reviewer following the protocol cannot access forbidden inputs; every reviewed row carries exactly one verdict and the evidence it was based on.

---

### U5. Capture+diff loop over the ledger (F1)

**Goal:** Execute F1 for every ledger row: seed → drive both platforms into the state → attach paired screenshots + Swift/Android behavior pair → run U4 review → record verdict.

**Requirements:** R3, R4, R5 (origin F1)

**Dependencies:** U1, U2, U4; U6 for animated rows

**Files:**
- Modify: `docs/parity/2026-05-15-parity-ledger.md` (fill evidence + verdict per row)
- Create: `docs/parity/evidence/` (paired screenshots, named by row id)
- Create: `docs/parity/capture-recipe.md` (per-platform drive steps: adb + `uiautomator dump` for Android, `xcrun simctl` for iOS sim, device-coord caveat from this session — screenshots are downscaled vs real device coords)

**Approach:**
- For each row: load the U2 seed (or run the documented manual recipe for non-seedable states), navigate both apps to the exact screen+state, capture `iOS-sim shot` + `Android shot`, pull the relevant iOS Swift slice + the Android implementation reference into the behavior pair, run the U4 blinded review, write verdict+evidence back to the row.
- Process in ledger order; a row is not "done" until verdict + both screenshots (+ motion if animated) are attached.
- Carry the real-device-coordinate caveat (this session: screenshot coords ≠ device input coords on the 1080-wide device) into the capture recipe so navigation is reliable.

**Execution note:** Characterization-first — capture and record the *observed* state before forming any judgment; the verdict comes from U4, not the capturer.

**Patterns to follow:** this session's adb screenshot + `uiautomator dump` bounds-driven navigation; `docs/parity/*-audit.md` evidence layout.

**Test scenarios:**
- Happy path: a populated Walk Summary row gets iOS+Android shots in the same seeded walk and a verdict.
- Covers AE2: the Goshuin 108-milestone row is captured with the milestone actually present on both (proves U2 seed adequacy end-to-end).
- Edge case: a non-seedable state (live in-progress walk) is captured via the documented manual recipe and still produces paired evidence.
- Integration: a row whose Android route is absent entirely is recorded as `close-the-gap` with the iOS shot + "no Android surface" as the Android side.
- Test expectation: the loop's correctness is the per-row evidence completeness check, not a code unit test.

**Verification:** Every non-stale ledger row has paired evidence + a U4 verdict; no row marked done without both screenshots.

---

### U6. Motion capture for animated screens (R7)

**Goal:** For the enumerated animated screens, attach a short motion capture to the row AND define the cadence-parity evaluation method the U5 loop invokes. U6 owns the *capture recipe + eval method* for animated rows; it does NOT run a parallel review — U5's loop remains the single review path and calls into the U6 method.

**Requirements:** R7 (AE4)

**Dependencies:** U1 (which rows are flagged animated), U2

**Files:**
- Modify: `docs/parity/2026-05-15-parity-ledger.md` (motion column for flagged rows)
- Create: `docs/parity/evidence/motion/` (recordings by row id)
- Modify: `docs/parity/capture-recipe.md` (add `adb shell screenrecord` + `xcrun simctl io recordVideo` recipe + trim guidance)

**Approach:**
- Flag the animated set at U1: welcome ritual, seal reveal, constellation overlay (stars/nebulae/shooting-star/parallax), stats reveal, calligraphy path draw, breathing logo.
- Record a short clip on each platform from the same trigger; trim to the animation window.
- The U4 reviewer evaluates cadence parity from the paired clips (method — frame-sample diff vs side-by-side playback — settled here on first use; see Deferred to Implementation).

**Test scenarios:**
- Covers AE4: the constellation overlay row carries a motion capture on both platforms; reviewer can judge twinkle/drift/shooting-star cadence, not just a frozen star field.
- Edge case: reduce-motion variant of an animated screen is captured as the static frame (no motion expected) and the row notes the reduce-motion path explicitly.
- Test expectation: capture-completeness check for the animated row subset; no code unit test.

**Verification:** Every U1-flagged animated row has a motion capture on both platforms; reduce-motion variants captured as stills with a note.

---

### U7. Triage + ship gate (F2, R8)

**Goal:** A5 reviews every `close-the-gap`/`re-justify` row WITH rationale attached, decides close-vs-re-justify (dated), and the gate computes pass/fail.

**Requirements:** R6, R8 (origin F2, R10)

**Dependencies:** U5 (verdicts exist), U6 (animated verdicts exist), U3 (reconciliation feeds the gate)

**Files:**
- Modify: `docs/parity/2026-05-15-parity-ledger.md` (triage note + dated decision per non-`match` row; a gate summary block at top)
- Create: `docs/parity/gate.md` (the pass rule + how `ce-plan` consumes the gap clusters)

**Approach:**
- For each non-`match` row: attach the now-relevant rationale (CLAUDE.md / code comment / origin) — this is the FIRST point rationale enters — and record a dated decision: `close` (route to remediation) or `re-justify: <explicit current reason, dated>`.
- Cluster `close` rows by surface (e.g., "Walk Summary map", "bell preview", "constellation chrome") so each cluster is a self-contained `ce-plan` input: screen, observed diff, Swift ref, decision — no further investigation needed.
- Gate rule (R8): PASS iff zero `unverified` (incl. reconciled) AND zero `close` rows still open AND every `re-justify` row has a dated current reason. Anything else = NOT shippable, with the blocking rows listed.

**Test scenarios:**
- Happy path: a fully-`match` ledger with no reconciliation delta reports gate PASS.
- Covers AE1: the Map-fallback row, marked `re-justify` blind in U4, gets a dated explicit reason at triage (or `close`) before the gate can pass.
- Edge case: one open `close` row OR one undated `re-justify` row → gate FAIL with that row named.
- Integration: a reconciliation-inserted `unverified` row (AE3) flips a previously-passing gate to FAIL.
- Test expectation: gate-rule truth-table dry-run across the four cases above; no code unit test (the gate is a documented rule over the ledger).

**Verification:** Gate output unambiguously states PASS or the exact blocking rows; every shipped divergence has a dated current justification; `close` clusters are directly consumable by `ce-plan`.

---

## System-Wide Impact

- **Interaction graph:** Read-only against `../pilgrim-ios` (pinned `fcd2255`) and this Android branch; the only writes are under `docs/parity/`. No app code changes except possibly a documented iOS/Android debug affordance if U5 finds a state un-reachable deterministically (deferred decision).
- **Error propagation:** A capture failure or missing screenshot must mark the row `unverified`/un-reviewable, never silently `match` (U4/U5 reject it) — mirrors the confirmation-bias failure this whole effort exists to kill.
- **State lifecycle risks:** Seed drift is the central risk — if the `.pilgrim` imports to non-equivalent state across platforms, every diff is noise. U2's dual round-trip check is the guard.
- **API surface parity:** N/A (verification artifact, not an API).
- **Integration coverage:** The seed dual-import equivalence check (U2) and the per-row paired-state capture (U5) are the cross-layer proofs that mocks/code-review could not give.
- **Unchanged invariants:** No production behavior changes in this plan; the parity target SHA pin (`CLAUDE.md`, iOS v1.6.0) is explicitly preserved as the enumeration/reconcile anchor.

---

## Risks & Dependencies

| Risk | Mitigation |
|------|------------|
| `.pilgrim` seed imports to divergent state across platforms → all diffs are noise | U2 dual round-trip equivalence check (counts + spot-checked surface stats) before any capture begins |
| iOS simulator not available in env (none booted at plan time) | Capture env setup is a stated prerequisite; if no sim, iOS capture falls back to a device — recipe documents both |
| Reviewer blinding leaks (rationale reaches the prompt) | U4 makes allowed/forbidden inputs an explicit contract; rationale only enters at U7 triage, structurally after the verdict |
| Enumeration misses a screen/state → false "complete" | U3 reconciliation makes completeness regenerable + provable against iOS source, not asserted |
| Gated states (108-walk, mid-animation milestone) unreachable deterministically | U2 documents manual recipes; U5 deferred decision allows a documented debug affordance if a still at the right frame is insufficient |
| Scope creep into fixing gaps | Scope Boundaries: this plan only finds+adjudicates; remediation is a separate `ce-plan` per U7 cluster |
| Stale prior audits read as current truth | Existing `docs/parity/*-audit.md` are pre-v1.6.0; U1 banners them `SUPERSEDED` and the new ledger is the sole current-state artifact — prior audits contribute format precedent only, never findings |

---

## Sources & References

- **Origin document:** [docs/brainstorms/2026-05-15-ios-android-parity-verification-requirements.md](docs/brainstorms/2026-05-15-ios-android-parity-verification-requirements.md)
- Related code: `docs/parity/*-audit.md`, `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageImporter.kt`, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`, `../pilgrim-ios/Pilgrim/Scenes/**`
- Related pattern: `~/.claude/skills/ios-parity/` (Swift-quoted pinned-SHA parity-spec mechanism — pattern reused, skill not invoked)
- Parity target: iOS v1.6.0 `fcd2255` (per `CLAUDE.md`)
