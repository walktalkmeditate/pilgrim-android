---
date: 2026-06-22
deepened: 2026-06-22
topic: ios-pr45-v170-parity-sweep
type: feat
status: active
origin: docs/brainstorms/2026-06-22-ios-pr45-v170-parity-sweep-requirements.md
ios_pin: a4f81c3
---

# feat: iOS PR #45 (v1.7.0) → Android Parity Sweep

## Summary

A read-only sweep that brings Android to **behavior parity** with iOS PR #45 ("v1.7.0 finalization" — 129 files; 77 audit findings + issues #41/#42/#43 + onboarding/audio/perf/a11y/design-system/security work). We ingest iOS's *own* committed enumeration docs as the master list, **reconcile them against the PR's commit log / final state**, triage every item (cross-platform / iOS-analogue / iOS-only), verify Android's current state with cited evidence, and land an evidence-backed **parity matrix** in `docs/parity/`. The sweep stops at a **report-first gate** — no production code changes — so the user can review and prioritize before any remediation. Gap remediation (F2) is planned at lighter granularity behind that gate.

(see origin: `docs/brainstorms/2026-06-22-ios-pr45-v170-parity-sweep-requirements.md`)

> **Frozen against iOS sha `a4f81c3`** (HEAD of `pr45-parity-readonly` at audit time). PR #45 is OPEN and moving — the matrix is valid only against this sha and must be re-diffed if #45 advances before F2 remediation. See Dependencies / Assumptions.

---

## Problem Frame

iOS shipped a large finalization pass (PR #45) resolving 3 open issues and remediating 77 confirmed audit findings. Android is a port and the team wants behavior parity, but Android's parity target was frozen at iOS v1.6.0 — #45 is post-freeze, and the user has explicitly authorized pulling it in for this sweep only. Without a systematic pass, cross-platform regressions and missing UX silently diverge, and iOS's audit insights (bug classes, optimizations) go unexamined on Android's counterparts. Reverse-engineering 129 diffs by hand is error-prone; iOS's committed `audit-findings.md` (77 stable `AF`-IDs with `file:line`, descriptions, fix sketches) is a far higher-fidelity enumeration — **but it is a point-in-time artifact** (see the spine-staleness risk below), so it must be reconciled against the PR's final state, not trusted blindly.

---

## Inputs & Source Material

- **iOS enumeration (read-only),** on the locally-fetched branch `pr45-parity-readonly` in `../pilgrim-ios`, pinned at HEAD `a4f81c3`:
  - `docs/brainstorms/2026-06-11-audit-findings.md` — 77 confirmed `AF`-IDs (1 critical / 19 major / 54 minor / 3 trivial) + **3 uncertain** + **14 polish** + 5 refuted, with `file:line · lens`, fix sketches, dispositions. **⚠️ Frozen at the PR's first commit `ecc7fa0` — it records the audit's *intent* at t=0, NOT the PR's shipped state at HEAD.** At least AF18 (the lone critical, "remove the WeatherKit ES256 key") was implemented then **reverted** (key kept, deferred). This doc must be reconciled against the commit log (U1).
  - `docs/brainstorms/2026-06-11-finalization-pass-requirements.md` — requirements framing.
  - `docs/plans/2026-06-11-001-fix-v170-finalization-pass-plan.md` — the iOS implementation plan (unit grouping; authoritative for which AFs cluster into which subsystem).
  - The **commit log** `git -C ../pilgrim-ios log pr45-parity-readonly` + final tree — the source of truth for each AF's *actual disposition* (implemented / reverted / deferred / partial).
  - The PR #45 body (`gh pr view 45 --repo walktalkmeditate/pilgrim-ios`) — narrative grouping + the "required out-of-band" + "not machine-verified" sections.
- **Android parity conventions to reuse** (`docs/parity/`): `2026-05-15-parity-ledger.md` (verdict legend, appearance-mode cross-cut), `2026-05-18-manual-parity-checklist.md`, `gate.md`, `fixtures/`.

---

## Actors

- A1. **Parity auditor** (Claude): enumerates, reconciles, triages, verifies Android, writes the matrix.
- A2. **Reviewer** (user): reviews the matrix at the report-first gate, prioritizes gaps, approves/merges remediation.
- A3. **Fix executor** (Claude, post-gate): implements + tests + PRs confirmed gaps.

---

## High-Level Technical Design

*Directional guidance for review, not implementation specification.*

```
iOS PR#45 docs @ ecc7fa0 (audit-findings.md: AF1..AF77 + 3 uncertain + 14 polish + issues + features)
        │  ingest
        ▼  RECONCILE each AF vs the commit log + final tree @ a4f81c3
        │     → iOS final disposition: implemented / reverted / deferred / partial
        ▼  spot-check vs raw 129-file diff + PR body (catch under-stated items)
  Master item inventory  ──►  TRIAGE (provisional in U1, re-confirmed per area)
        │                       ├─ cross-platform behavior/UX      → verify Android behavior
        │                       ├─ iOS-analogue (diff mechanism)   → verify Android equivalent (AudioFocus, Room, whisper.cpp)
        │                       └─ iOS-only internal               → Android-equivalent HEALTH CHECK (same bug-class? same optimization?)
        ▼
  Per-area Android verification (read code, cite kotlin:line) — each area writes its OWN fragment file
        │  classify each: match / match-by-reading(timing-unproven) / close-the-gap / re-justify / n-a(reason) / needs-device
        ▼
  U8 merges fragments → parity matrix (docs/parity/, reuses ledger verdict legend + AF-ID + triage + iOS-disposition columns)
        ▼
  ══════════ REPORT-FIRST GATE (no code, no Play Console changes) ══════════
        ▼
  F2 remediation: batch gaps by area/severity → TDD → PR → user merge
```

**Matrix row shape** (one per iOS item):

| AF-ID / item | iOS behavior + `swift:line@a4f81c3` | iOS final disposition | triage bucket | Android verdict + `kotlin:line` evidence | recommended action |

**Verdict vocabulary** (reuses the existing ledger legend; the plan's vocabulary is authoritative — the origin doc's `Done / Partial / Gap / N/A` map as `Done→match`, `Partial/Gap→close-the-gap`, `N/A→n-a`):
- `match` — Android behavior verified equivalent, with the **specific mechanism** named (iOS fix introduced X; Android equivalent is Y at `kotlin:line` — present).
- `match-by-reading (timing-unproven)` — for **race / ordering / interruption / teardown** findings: code reads correct but the invariant is not pinned by a test. **A plain `match` is forbidden on these AFs** unless a deterministic test (Turbine / virtual-time) demonstrates the ordering; otherwise this verdict, which routes to `needs-device` or an F2 test.
- `close-the-gap` — a real Android gap to fix.
- `re-justify` — an intentional Android difference (dated reason).
- `n-a` — iOS-only with no Android relevance — requires the **subsystem inspected + the specific bug-class checked and not found** (not "looks fine").
- `stale` — iOS reverted/removed this; row kept for audit trail (e.g., AF18).
- `unverified` / `needs-device` — desk-unverifiable; flagged, never silently closed. (`unverified`/`stale` are legal interim/terminal verdicts; the Success-Criteria completeness bar applies to the *finalized* matrix.)

Native substitutions (Open-Meteo, Mapbox, whisper.cpp) cannot receive `match` per the existing ledger rule — they enter `re-justify`.

---

## Requirements Traceability

- R1–R3 (enumerate) → U1
- R2 (spot-check vs diff **+ reconcile vs commit log**) → U1
- R4 (triage) → U1 (provisional), U2–U7 (re-confirmed per area)
- R5 (verify cross-platform/analogue) → U2–U7
- R6 (iOS-only Android health check) → U2–U7 (each area carries its iOS-only items)
- R7 (deepen high-risk slices) → U2 (data), U3 (audio), U4 (perf/memory)
- R8 (Android analogues of out-of-band items) → U7 (security/privacy)
- R9 (matrix) → U8 (merged from area fragments)
- R9b (recipe) → U9
- R10 (report-first gate) → U9 (the gate is the terminal step of the sweep, not a remediation step; F2 begins only after U9 completes)
- R11–R12 (batched remediation, no auto-merge) → U10 / F2 (deferred, light)
- AE1 (WeatherKit / APK-secret check) → U7 · AE2 (soundscape-silent-after-call) → U3 · AE3 (O(n²) route pipeline) → U4 · AE4 (no code changed before gate) → U9

---

## Implementation Units

> Phase A = set up the spine + reconciliation. Phase B = per-area Android verification (U2–U7 are mutually independent and run against **per-area fragment files** — no shared-file write contention; U8 merges). Phase C = merge + gate.
>
> **Execution posture:** read-only audit. Units U1–U9 change **no production code** and trigger **no Play Console / portal actions** — they read iOS + Android source and write only Markdown under `docs/parity/`. Test scenarios are therefore `none` for the audit units; behavioral testing lives in F2 remediation.

### Phase A — Spine

### U1. Ingest + reconcile + build the triaged master inventory
- **Goal:** Produce the complete, deduplicated, *reconciled* list of every PR #45 item — each carrying its iOS final disposition, triage bucket, and authoritative area routing — as the matrix's seed rows.
- **Requirements:** R1, R2, R3, R4.
- **Dependencies:** none.
- **Files:** create `docs/parity/2026-06-22-pr45-v170-parity-matrix.md` (header + per-area fragment **stubs**, one section per Phase B area).
- **Approach:**
  1. Read the three iOS docs @ `ecc7fa0`/`a4f81c3` + the PR body. Enumerate **all 77 `AF`-IDs**, the **3 uncertain** items (the audit doc's uncertain section — each gets a row), the **14 polish** items (each enumerated; cross-platform-behavioral ones get a row, the rest a collective `n-a` note in the spot-check section), the **3 issues** (#41/#42/#43), and the named **features**.
  2. **Reconcile each AF against the commit log + final tree** (`git log pr45-parity-readonly`, per-AF disposition from commit messages): record `iOS final disposition` = implemented / reverted / deferred / partial. **AF18 is `reverted` (key kept, deferred) → mark `stale`; do NOT treat key-removal as iOS behavior to mirror.**
  3. Spot-check the reconciled list against the raw 129-file diff + PR body; record anything the docs under-state.
  4. **Pre-bucket** each item (cross-platform / iOS-analogue / iOS-only) using iOS `file:line`+lens as a *provisional* signal — area units re-confirm during verification.
  5. Build an **authoritative cross-seam routing table**: any AF whose iOS path spans two subsystems gets a primary-unit assignment so nothing is double-owned or dropped. Known cross-seam items: **AF77** (intention-recorder transcribe races `recordingURL`) → **U2**; **AF37** (audio-session consumer leak on dir-create failure) → **U3**; **AF51/AF52** (recompute/non-lazy scroll, `lens: performance`) → **U4** (not U6).
- **Patterns to follow:** the existing ledger header + legend in `docs/parity/2026-05-15-parity-ledger.md`; AF-ID format from the iOS `audit-findings.md`.
- **Test scenarios:** `Test expectation: none -- read-only enumeration + reconciliation; output is Markdown.`
- **Verification:** every AF-ID (AF1–AF77), the 3 uncertain, the 14 polish (rows or noted exclusions), every issue, and every named feature appears in the inventory with a triage bucket, an iOS-final-disposition, and a primary-unit assignment; the cross-seam routing table is present; AF18 is marked `stale`.

### Phase B — Per-area Android verification (independent; per-area fragment files)

> Each unit appends only to its **own** fragment section seeded in U1 — no shared-file contention. Each re-confirms the provisional triage for its items. **Race/ordering/interruption/teardown AFs may not be marked plain `match`** without a deterministic test — use `match-by-reading (timing-unproven)` → `needs-device`/F2.

### U2. Verify — Resilience & Data
- **Goal:** Determine Android's state for the data-safety / persistence findings (incl. AF77).
- **Requirements:** R5, R6, R7.
- **Dependencies:** U1.
- **Files:** write the "Resilience & Data" fragment section. Read (no edits): walk crash-recovery / session-guard, `WalkRepository` + Room transaction paths, `.pilgrim` import, the orphan-recording sweeper, audio-file store "clear downloads", intention-recorder transcribe path (AF77).
- **Approach:** Map AF1 (checkpoint deleted before save confirmed), AF2 (orphan sweep races recovery), AF3 (clear-downloads wipes voice-guide packs), import-atomicity, AF77 (transcribe races `recordingURL`) to Android equivalents. Trace the actual recovery + import + sweep ordering (beyond grep). For the race-class AFs (AF2/AF77), apply the timing-unproven verdict rule.
- **Patterns to follow:** `CLAUDE.md` "Long-session reliability"; foreground-service teardown.
- **Test scenarios:** `Test expectation: none -- read-only verification.`
- **Verification:** every data/resilience AF (incl. AF77) has an Android verdict + `kotlin:line` evidence or `n-a`-with-reason; race-class items obey the verdict rule.

### U3. Verify — Audio session & interruptions
- **Goal:** Determine Android's state for audio-arbitration / interruption findings (incl. AF37).
- **Requirements:** R5, R6, R7.
- **Dependencies:** U1.
- **Files:** "Audio" fragment section. Read: `AudioFocusCoordinator`, `VoiceRecorder`, soundscape/voice-guide ExoPlayer paths, `ACTION_AUDIO_BECOMING_NOISY`.
- **Approach:** iOS rebuilt `AVAudioSession` around per-consumer mode arbitration; Android's analogue is `AudioFocus`/`AudioManager`. Verify the *behaviors*: a phone call doesn't leave the soundscape silent for the rest of the walk (AF4/5/6); recordings finalize on interruption (AF11); the mic is released promptly after a voice note; the session-consumer doesn't leak on a recordings-dir-create failure (AF37). The **"two final-review interruption fixes"** are concrete behaviors to check: (a) **coordinator re-entrancy** — a consumer deactivating itself inside an interruption-began/ended callback must not deadlock; (b) **deferred mode application** — no `setActive`/focus thrash *between* interruption-began and -ended (the same-tick soundscape-resume race). Apply the timing-unproven verdict rule to all of these.
- **Patterns to follow:** the audio memory lessons (`AudioFocusCoordinator` single-owner trap, `setWillPauseWhenDucked`, noisy-receiver, `discardPlayer`).
- **Test scenarios:** `Test expectation: none -- read-only verification.`
- **Verification:** every audio AF (incl. AF37 + the two interruption behaviors) has a verdict; race/interruption items use `match-by-reading`/`needs-device` unless a test pins them.

### U4. Verify — Performance & memory/leaks
- **Goal:** Determine Android's state for perf + leak findings (incl. AF51/AF52), including iOS-only optimizations checked against Android counterparts.
- **Requirements:** R5, R6, R7.
- **Dependencies:** U1.
- **Files:** "Perf & memory" fragment section. Read: route-sample pipeline, app/launch init, checkpoint encode path, Mapbox battery-saver/frame-rate, metering/recomposition hot paths, ViewModel/observer lifecycles, whisper.cpp model lifecycle.
- **Approach:** Behavior + optimization checks. Cross-platform: map "battery saver" actually throttles; per-GPS route pipeline not O(n²) (AE3); metering isolated / not over-recomposing (AF51/AF52 analogues). iOS-only-with-health-check (R6): CoreStore 12-version-chain probe (#42) → Android Room single-baseline-schema startup; WhisperKit unload → whisper.cpp model unloaded after batches; iOS retain cycles → Android coroutine-scope / MapView / observer leak classes. Record a bug OR optimization finding, else `n-a`-with-reason. Main-confinement race AFs use the timing-unproven verdict rule.
- **Patterns to follow:** `PilgrimMap.kt` `onRelease` teardown; `WhileSubscribed`/`stateIn` memory patterns; whisper JNI lifecycle entries.
- **Test scenarios:** `Test expectation: none -- read-only verification.`
- **Verification:** every perf/memory AF (incl. AF51/AF52) has a verdict; each iOS-only item carries an explicit bug + optimization disposition (R6).

### U5. Verify — Onboarding delight & honest failure feedback
- **Goal:** Determine Android's state for the #43 onboarding delight + "honest failure feedback" findings.
- **Requirements:** R5.
- **Dependencies:** U1.
- **Files:** "Onboarding & feedback" fragment section. Read: onboarding/permissions flow, the "begin walk" transition, transcription / pack-download / permission result surfaces.
- **Approach:** Cross-platform UX. Verify: Wander Zoom on Begin + a permission-grant ritual (bell + checkmark pulse), both Reduce-Motion-aware, bell once-per-permission persisted; and that transcription/pack/permission failures that "report success" actually surface failures on Android.
- **Patterns to follow:** existing Android onboarding (`PermissionsScreen`, `BreathTransition.kt`) + `LocalReduceMotion` gating.
- **Test scenarios:** `Test expectation: none -- read-only verification.`
- **Verification:** onboarding-delight + honest-failure items each carry a verdict; Reduce-Motion behavior noted.

### U6. Verify — Accessibility sweep
- **Goal:** Determine Android's state across the 16 accessibility findings.
- **Requirements:** R5.
- **Dependencies:** U1.
- **Files:** "Accessibility" fragment section. Read: semantics/`contentDescription`, font-scaling, Reduce-Motion gates, tap-target sizing across the flagged screens.
- **Approach:** The 16 a11y findings are **AF29, AF47–AF50, AF53, AF54, AF57–AF59, AF62–AF66, AF72** (final count confirmed against the audit doc in U1). Map VoiceOver→TalkBack labels/traits, Dynamic Type→sp/font-scale, Reduce Motion→`LocalReduceMotion`, 44pt→48dp. **AF51/AF52 are performance findings routed to U4, not here.** Desk-verify what's checkable; flag the rest `needs-device` (TalkBack / Accessibility Inspector pass).
- **Patterns to follow:** existing `semantics { contentDescription }` + `clearAndSetSemantics` (e.g., the turning-watermark a11y).
- **Test scenarios:** `Test expectation: none -- read-only verification.`
- **Verification:** each of the 16 a11y findings has a verdict or `needs-device` flag; AF51/AF52 are not duplicated here.

### U7. Verify — Design-system & Security/Privacy
- **Goal:** Determine Android's state for design-system conformance + security/privacy findings + the Android analogues of iOS's "required out-of-band" items.
- **Requirements:** R5, R8.
- **Dependencies:** U1.
- **Files:** "Design-system & Security" fragment section. Read: theme/shadow tokens, typography, `fog` contrast, widget (Glance) colors; `app/build.gradle.kts` `buildConfigField`s, `settings.gradle.kts`, `proguard-rules.pro`, `data_extraction_rules.xml`/`backup_rules.xml`, `DeviceTokenStore`, the network services (`CollectiveCounterService`, share/voice-guide/soundscape/feedback), `MapboxRefererInterceptor`.
- **Approach:**
  - **Design-system** (cross-platform): adaptive shadows → fixed (no dark-mode halos — dot shadow already fixed; check the rest), brand typography, `fog` past WCAG floor, widget color reconciliation.
  - **Secrets-in-binary (concrete procedure, AE1):** enumerate every `buildConfigField`; classify each value secret vs non-secret. Distinguish the Mapbox **`pk.*` access token** (ships in the AAB *by design*, referrer-restricted) from the **`sk.*` downloads token** (Gradle-only — must NOT ship). Verify by inspecting a release AAB: `unzip` + `strings` + `grep -E 'sk\.[a-zA-Z0-9]{50,}'` (and the other endpoints' keys). Record a verdict **per token type**, not "Mapbox tokens" as a monolith. (AF18/WeatherKit is `stale` on iOS — Android uses Open-Meteo with no key; the live question is the Mapbox/secret check, not a WeatherKit-key removal.)
  - **Play data-safety (data-flow inventory):** for each network call, list what user data is transmitted and to what endpoint (GPS route → share worker; **device-UUID `X-Device-Token` → walk.pilgrimapp.org on every walk/share**; voice audio; photo EXIF GPS via `ACCESS_MEDIA_LOCATION`; step/activity). Cross-reference the current Play Console data-safety declaration; flag any transmitted type not declared (device-UUID is highest-priority). **Any required Play Console change is recorded as a gap and deferred behind the gate — do NOT touch the Console during the sweep.**
  - **Device-token hygiene:** confirm `DeviceTokenStore` backend (DataStore Preferences ≈ UserDefaults, consistent with iOS's Keychain→UserDefaults downgrade) AND whether `data_extraction_rules.xml` device-transfer rules let the token survive a device migration (which would defeat the rate-limit rationale).
  - **Log hygiene (Android-native catch):** `MapboxRefererInterceptor.Log.d` logs the tile URL incl. the `pk.*` token in release — check `Log.*` calls in network paths are `BuildConfig.DEBUG`-gated or ProGuard-stripped.
- **Patterns to follow:** `Color.kt` tokens; the dot-shadow fix; the Play data-safety memory entry.
- **Test scenarios:** `Test expectation: none -- read-only verification.`
- **Verification:** design-system + security/privacy items each carry a verdict; the secrets-in-AAB (per token type), data-flow-vs-declaration, device-token-transfer, and Log-leak questions are each answered explicitly; portal actions are deferred, not taken.

### Phase C — Merge & gate

### U8. Merge fragments → finalize the parity matrix
- **Goal:** Merge the per-area fragments into one coherent, evidence-cited matrix + a gap summary.
- **Requirements:** R9.
- **Dependencies:** U2, U3, U4, U5, U6, U7.
- **Files:** finalize `docs/parity/2026-06-22-pr45-v170-parity-matrix.md`.
- **Approach:** Concatenate fragments, normalize verdicts, sort by triage bucket then severity, add a top-of-doc rollup (counts per verdict; gap list grouped by area + severity; `needs-device` list; `match-by-reading` list). Cross-link AF-IDs to iOS `@a4f81c3` citations + Android evidence + iOS final disposition. Ensure every AF + uncertain + polish + issue + feature from U1 has a final disposition. **Closing-adversarial second pass** (the project's habit): re-check that no `match` is wishful and no iOS-only `n-a` is a rubber-stamp before the gate.
- **Patterns to follow:** the `2026-05-15-parity-ledger.md` rollup + legend.
- **Test scenarios:** `Test expectation: none -- documentation consolidation.`
- **Verification:** zero items without a verdict; rollup counts reconcile to U1's enumerated total (77 + 3 uncertain + polish + issues + features).

### U9. Reusable recipe + report-first gate
- **Goal:** Capture the repeatable steps and present the gate.
- **Requirements:** R9b, R10.
- **Dependencies:** U8.
- **Files:** create `docs/parity/parity-sweep-recipe.md` (fixed path, findable for the next sweep).
- **Approach:** Write the recipe: fetch iOS PR branch → read its committed audit/plan docs → **reconcile against the commit log + final tree** → **spot-check vs the raw diff + PR body** → enumerate + triage (incl. uncertain/polish) → verify Android per area with cited evidence (race AFs need a test or `needs-device`) → merge fragments → pin the iOS sha → gate. Then present the gap summary and **stop** — no production code, no portal actions. This is the report-first gate.
- **Test scenarios:** `Test expectation: none -- documentation.`
- **Verification:** recipe exists at the fixed path with the reconcile + spot-check steps; user has the gap summary; no code/portal changes made.

### Phase D — Remediation (DEFERRED behind the gate — light granularity)

### U10. Gap remediation (planned post-gate)
- **Goal:** Close confirmed gaps to match iOS behavior.
- **Requirements:** R11, R12.
- **Dependencies:** U9 + explicit user approval at the gate.
- **Files:** TBD — determined by the matrix's gap list.
- **Approach:** Batch confirmed `close-the-gap` items by area/severity. Each batch is its own branch + PR, implemented **test-first**, verified, `/ce-code-review` where warranted, CI green, then **await explicit user merge** (no auto-merge). `needs-device` and `match-by-reading` items are scheduled for an on-device / test-pinning pass, not closed at the desk. Portal/Play-Console actions (if any from U7) are executed here, post-approval. Re-plan each batch at remediation time.
- **Execution note:** test-first; do not start until the user approves the matrix.
- **Test scenarios:** Per batch at remediation time — each behavioral fix gets reproduction + regression tests (the matrix row's expected behavior is the spec). Race/ordering fixes get a deterministic (Turbine/virtual-time) test that pins the invariant. Not enumerated here by design (gated).
- **Verification:** each shipped batch matches the iOS behavior in its matrix rows; CI green; user-merged.

---

## Success Criteria

- Every PR #45 item (AF1–AF77 + the 3 uncertain + the 14 polish + #41/#42/#43 + named features) has an explicit Android disposition (`match` / `match-by-reading` / `close-the-gap` / `re-justify` / `n-a`-with-reason / `stale` / `needs-device`) backed by cited evidence — nothing in *iOS PR #45* left unexamined.
- **Scope honesty:** this is a *parity* sweep against iOS PR #45's findings — it is **not** a full Android-native audit. Android-only defect classes (Compose recomposition, `WhileSubscribed`/coroutine lifecycle, `@Immutable` stability, WorkManager builders) are outside its frame; any spotted opportunistically while reading a subsystem are recorded as `android-native` rows, but a systematic Android-native pass is deferred (see Scope Boundaries).
- iOS-only internals each carry an Android-equivalent **bug + optimization** verdict (subsystem inspected + bug-class checked), not a blanket skip (R6).
- The matrix is concrete enough that `/ce-plan` or autopilot can execute the gaps without re-deriving behavior.
- The sweep changed **no production code and made no Play Console / portal changes**; the user reviews the matrix before remediation.
- A reusable recipe exists at `docs/parity/parity-sweep-recipe.md`.

---

## Scope Boundaries

- Bounded to iOS PR #45 @ `a4f81c3`; the v1.6.0 freeze stands for all other post-v1.6.0 iOS work.
- iOS-only internals are examined on Android for bug/optimization parity, but the literal Swift fix is not ported (behavior parity, not implementation parity).
- iOS-repo / Apple-portal out-of-band actions (revoke WeatherKit key, delete CI secrets, ASC privacy label) are iOS concerns — only their **Android analogues** are in scope.
- No new reusable tooling (no `ios-parity` "PR mode" build) — a written recipe only.

### Deferred to Follow-Up Work

- F2 remediation (U10) — gated behind the report-first gate; re-planned per batch once the gap list is known.
- **Android-native audit pass** — a systematic Compose/coroutine/stability/WorkManager-lens sweep of the same subsystems, surfacing Android-only defects the iOS-lens audit cannot. Optional; decide at the gate whether to fold it in.
- On-device verification passes for `needs-device` / `match-by-reading` items (TalkBack/Accessibility Inspector, leak/battery over a multi-hour walk, launch-time delta, audio-interruption on hardware, race-invariant tests).
- Any Play Console data-safety update surfaced by U7.

---

## Key Technical Decisions

- **Master list from iOS's committed `audit-findings.md`, reconciled against the commit log + final tree** — higher fidelity than reverse-engineering 129 diffs, but the doc is frozen at `ecc7fa0` and records intent, not shipped state; reconciliation (U1) is mandatory so we don't chase reverted fixes (e.g., AF18). (see origin: Key Decisions)
- **Pin the whole sweep to iOS sha `a4f81c3`** — PR #45 is OPEN; the matrix is a point-in-time snapshot and must be re-diffed if #45 advances. Whether to *wait for #45 to merge* before F2 is decided at the gate (remediation is already gated, so snapshot risk is bounded).
- **Behavior parity, not implementation parity** — verify Android-native mechanisms (`AudioFocus`, Room, whisper.cpp), not literal Swift ports. *Exception:* for race/ordering/interruption findings, "behavior" is the timing — desk-reading is insufficient, so those require a test or route to `needs-device` (no plain `match`).
- **iOS-only internals get a falsifiable Android-equivalent health check** (subsystem inspected + specific bug-class checked + optimization assessed), not a blanket N/A — per explicit user instruction.
- **Reuse the existing `docs/parity/` ledger conventions** (verdict legend + native-substitution rule); the plan's verdict vocabulary is authoritative and the origin's `Done/Partial/Gap` map onto it.
- **Per-area fragment files merged in U8** — avoids shared-file write contention and makes the Phase B units genuinely independent.
- **Report-first gate before code or portal actions** — the sweep is read-only; remediation is a separate, approved phase.

---

## Risk Analysis & Mitigation

- **Spine staleness (P0).** `audit-findings.md` is frozen at `ecc7fa0` and records audit *intent*; the PR evolved (AF18 implemented→reverted). → U1 reconciles every AF against the commit log + final tree and records an `iOS final disposition`; AF18 is marked `stale`. Without this, Android would "fix" non-gaps.
- **Auditing an OPEN, moving PR.** → pinned to `a4f81c3`, recorded in the matrix header; re-diff if #45 advances; wait-vs-snapshot decided at the gate.
- **Android-native blind spot.** An iOS-lens audit can't surface Android-only defect classes. → Success Criteria narrowed + honest disclaimer; opportunistic `android-native` rows; systematic Android-native pass deferred (explicit follow-up).
- **False `match` on race/ordering fixes (desk-reading trap).** Code with a race reads identically to code without one. → `match-by-reading (timing-unproven)` verdict; plain `match` forbidden on race/ordering/interruption/teardown AFs without a deterministic test.
- **Verdicts not falsifiable / iOS-only rubber-stamp.** → `match` requires the named iOS-vs-Android mechanism; `n-a` requires subsystem + bug-class; closing-adversarial second pass on the assembled matrix (U8) before the gate.
- **iOS docs under-state an item.** → U1 spot-checks the raw 129-file diff + PR body.
- **Desk-unverifiable behaviors** (a11y, leaks/battery, launch time, interruptions on hardware). → flagged `needs-device`, never silently closed.
- **Scope creep into remediation / portal actions during the sweep.** → hard report-first gate; U1–U9 change no production code and make no Play Console changes.

---

## Dependencies / Assumptions

- `../pilgrim-ios` present; PR #45 fetched locally as branch `pr45-parity-readonly`, pinned at HEAD `a4f81c3` (done).
- The audit doc (frozen at `ecc7fa0`) is a *starting* enumeration; its accuracy vs the shipped PR is established by U1's reconciliation, not assumed.
- PR #45 is OPEN and was updated on the audit date — the matrix is valid only against `a4f81c3`; advancing the PR invalidates row citations until re-diffed.
- Some items require on-device verification / test-pinning and will be flagged, not closed, by the desk sweep.

---

## Outstanding Questions

- **Decided at the report-first gate (not blocking the sweep):** (a) whether to wait for PR #45 to merge before F2 remediation vs. proceed against the `a4f81c3` snapshot; (b) whether to fold in the systematic Android-native audit pass; (c) remediation batching (autopilot vs. manual; grouping).
