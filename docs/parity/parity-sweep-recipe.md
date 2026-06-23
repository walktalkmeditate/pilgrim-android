# Recipe — Android parity sweep for an iOS PR

Repeatable steps to bring Android to behavior-parity with a future `pilgrim-ios` PR. Read-only up to the report-first gate; remediation is a separate, approved phase. First run: iOS PR #45 → `docs/parity/2026-06-22-pr45-v170-parity-matrix.md`.

## 0. Prereqs
- Sibling `../pilgrim-ios` checkout.
- Fetch the PR branch locally, read-only: `git -C ../pilgrim-ios fetch origin pull/<N>/head:pr<N>-parity-readonly`.
- **Pin the sha:** `git -C ../pilgrim-ios rev-parse --short pr<N>-parity-readonly`. Record it in the matrix header. The matrix is valid only against this sha — re-diff if the PR advances.

## 1. Enumerate from iOS's own docs (not the raw diff)
- Read the PR's committed enumeration under `../pilgrim-ios/docs/` (e.g. `docs/brainstorms/*-audit-findings.md`, the requirements doc, the plan). These give stable IDs (`AF1…`), `file:line · lens`, descriptions, fix sketches.
- **⚠️ Reconcile against the commit log**, not just the doc. The doc is frozen at the PR's *first* commit and records *intent*, not shipped state. For each finding, check `git -C ../pilgrim-ios log pr<N>-parity-readonly` for its actual disposition: `fixed` / `reverted` / `deferred` / `partial`. (In #45, AF18's WeatherKit-key removal was reverted — a stale prescription that would have sent us chasing a non-gap.)
- **Spot-check** the list against the raw diff (`gh pr view <N> --json files`) + PR body to catch items the docs under-state. Note the PR body's "required out-of-band" + "not machine-verified" sections.

## 2. Triage every item
- **CP** cross-platform behavior/UX → verify the Android behavior.
- **AN** iOS-analogue (different mechanism, same behavior) → verify the Android equivalent (`AudioFocus`↔AVAudioSession, Room↔CoreStore, whisper.cpp↔WhisperKit, Mapbox-Android↔Mapbox-iOS, Open-Meteo↔WeatherKit, SpeechRecognizer↔intention recorder).
- **IO** iOS-only internal → still do the **R6 health check**: does the Android counterpart have the same *class* of bug or the same optimization opportunity? Record a finding if so; else `n-a` naming the *subsystem inspected + bug-class checked* (not "looks fine").
- Build a cross-seam routing table so an item that spans two subsystems has one primary owner (no double-owning, no dropping).

## 3. Verify Android, per area, with cited evidence
- Split by Android subsystem (data, audio, perf/memory, onboarding/feedback, a11y, design/security) — fan out parallel read-only subagents, each writing its own fragment to avoid shared-file contention.
- Cite `kotlin:file:line` for every verdict. Verdict legend: `match` / `match-by-reading` / `close-the-gap` / `re-justify` / `n-a` / `stale` / `needs-device` (see the matrix header).
- **Race/ordering/interruption/teardown findings:** a plain `match` is forbidden without a deterministic (Turbine/virtual-time) test pinning the invariant → use `match-by-reading` → `needs-device`/F2. Code with a race reads identically to code without one.
- **`match` must name the mechanism** (iOS fix introduced X; Android equivalent is Y, present). **`n-a` for IO must name the subsystem + bug-class checked.** Make verdicts falsifiable.
- Native substitutions (Open-Meteo/Mapbox/whisper.cpp/SpeechRecognizer) can't be `match` — they're `re-justify` per the ledger rule.

## 4. Merge + closing-adversarial pass
- Concatenate fragments into one matrix; normalize verdicts; add a rollup (counts + gap list grouped by area/severity + `needs-device` + `match-by-reading` lists).
- Do a closing-adversarial pass: re-check no `match` is wishful and no IO `n-a` is a rubber-stamp before the gate.

## 5. Scope honesty
- This is a *parity* sweep against the iOS PR's iOS-lens findings — it is **not** a full Android-native audit. Record any Android-only defects spotted opportunistically (`android-native` rows); defer a systematic Android-native pass.

## 6. Report-first gate
- Land the matrix + this recipe. **Stop** — no production code, no Play Console / portal actions. Present the gap summary for review + prioritization. Remediation (test-first, batched by area/severity, no auto-merge) is a separate approved phase.
