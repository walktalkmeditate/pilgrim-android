---
date: 2026-06-22
topic: ios-pr45-v170-parity-sweep
---

# iOS PR #45 (v1.7.0) → Android Parity Sweep

## Summary

A one-time, comprehensive sweep to bring Android to **behavior parity** with iOS PR #45 ("v1.7.0 finalization" — 129 files; 77 audit findings + issues #41/#42/#43 + onboarding/audio/perf/a11y/design-system/security/resilience work). We enumerate every item from iOS's *own* committed audit/plan docs, triage each as cross-platform / iOS-analogue / iOS-only, verify Android's current state with cited evidence, and land a parity matrix. Confirmed gaps are then fixed and shipped like the iOS work. The repeatable steps are captured as a short checklist for future iOS PRs.

---

## Problem Frame

iOS shipped a large finalization pass (PR #45) resolving 3 open issues and remediating 77 confirmed audit findings across resilience, performance, accessibility, design-system, and security. Android is a port of iOS and the team wants behavior parity — but Android's parity target was frozen at iOS v1.6.0, and #45 is post-freeze (the user has explicitly authorized pulling it in). Without a systematic sweep, cross-platform regressions and missing UX (onboarding delight, accessibility, honest failure feedback) silently diverge, and iOS's audit insights — bug classes and optimizations — go unexamined on the Android equivalents. Reverse-engineering 129 diffs by hand is error-prone; iOS's own committed audit doc is a higher-fidelity enumeration to work from.

---

## Actors

- A1. **Parity auditor** (Claude): enumerates iOS items, triages, verifies Android state, writes the matrix.
- A2. **Reviewer** (user): reviews the matrix at the report-first gate, prioritizes gaps, approves fixes/merges.
- A3. **Fix executor** (Claude, post-gate): implements + tests + PRs the confirmed gaps, batched by area/severity.

---

## Key Flows

- F1. **Parity sweep**
  - **Trigger:** iOS PR #45 selected for parity.
  - **Actors:** A1, A2.
  - **Steps:** (1) fetch the iOS PR branch locally, read its committed audit-findings + plan + requirements docs; (2) build the master item list, spot-checked against the raw diff + PR body; (3) triage each item into cross-platform / iOS-analogue / iOS-only; (4) verify Android state per item with cited `file:line` evidence — including an Android-equivalent health check (bug-class + optimization) for iOS-only internals; (5) classify each Done / Partial / Gap / N/A-with-reason; (6) land the parity matrix doc.
  - **Outcome:** a committed, evidence-cited parity matrix; report-first gate reached.
  - **Covered by:** R1–R9b.
- F2. **Gap remediation** (post-gate)
  - **Trigger:** user approves the matrix and prioritizes.
  - **Actors:** A2, A3.
  - **Steps:** batch confirmed gaps by area/severity; implement test-first; verify; PR + CI; await explicit user merge.
  - **Outcome:** gaps closed and shipped like the iOS work.
  - **Covered by:** R10–R12.

---

## Requirements

**Enumeration**
- R1. Use iOS PR #45's committed docs (`docs/brainstorms/2026-06-11-audit-findings.md` + the plan/requirements) as the master item list; fetch the PR branch locally to read them in full.
- R2. Spot-check the master list against the raw 129-file diff + PR body sections to catch items the docs under-state; record any additions.
- R3. Enumerate *every* item — all 77 findings (1 critical / 19 major / 54 minor / 3 trivial), the uncertain + polish dispositioned items, issues #41/#42/#43, and the named features (onboarding delight, audio rebuild, performance, accessibility, design-system, security).

**Triage + verification**
- R4. Classify each item into one of: cross-platform behavior/UX, iOS-analogue (different mechanism / same behavior), or iOS-only internal.
- R5. For cross-platform + iOS-analogue items, verify Android's current state with cited `file:line` evidence and classify Done / Partial / Gap.
- R6. For iOS-only internal items, do **not** skip: examine the Android equivalent subsystem for (a) the same class of bug and (b) the same optimization opportunity; record a finding if Android has either, else N/A-with-reason.
- R7. Deepen high-risk slices beyond a grep — read the Android code paths for audio interruption, crash-recovery / import atomicity, the route pipeline, whisper model lifecycle, and leak/retain-cycle classes.
- R8. Check the Android analogues of iOS's "required out-of-band" items: whether any secret ships in the APK/AAB (e.g., Mapbox tokens), and whether iOS's privacy-manifest expansion implies a Play data-safety re-check.

**Output**
- R9. Produce one committed parity matrix under `docs/parity/`: each row = item | iOS behavior (with Swift `file:line@sha` cite) | triage bucket | Android status (Done/Partial/Gap/N/A) + evidence | recommended action. Flag items needing on-device verification.
- R9b. Capture a short reusable recipe (enumerate from iOS docs → triage → verify Android → act) for future iOS-PR parity sweeps.

**Remediation**
- R10. Report-first gate: land the matrix and pause for user review **before any production code changes**.
- R11. After approval, close confirmed gaps in batches (by area/severity), each test-first, verified, reviewed (`/ce-code-review` where warranted), and PR'd.
- R12. Do not auto-merge; present each PR + summary and await explicit user merge (per project rule).

---

## Acceptance Examples

- AE1. **Covers R4, R6.** Given the iOS finding "WeatherKit ES256 key shipped in IPA," when triaged, it is classed iOS-only (Android uses Open-Meteo, no key) AND the Android-equivalent check confirms whether the APK/AAB ships any secret (Mapbox token) — recording a gap if so, N/A-with-reason if not.
- AE2. **Covers R5.** Given the iOS finding "a phone call leaves the soundscape silent for the rest of the walk," when verified on Android, the `AudioFocus` path is read and classified Done/Partial/Gap with `file:line` evidence.
- AE3. **Covers R6.** Given the iOS "O(n²) → O(1) route pipeline" optimization, when examined, Android's route-sampling pipeline complexity is assessed and a finding recorded if the same inefficiency exists.
- AE4. **Covers R10.** Given the completed matrix, when the sweep finishes, no production code has been changed and the user is asked to review before remediation begins.

---

## Success Criteria

- Every iOS PR #45 item has an explicit Android disposition (Done / Partial / Gap / N/A-with-reason) backed by evidence — nothing left unexamined, mirroring iOS's "nothing known is left unaddressed."
- iOS-only internals each carry an Android-equivalent bug + optimization verdict, not a blanket skip.
- The matrix is concrete enough that `/ce-plan` or autopilot can execute the gaps without re-deriving behavior.
- After remediation, Android matches iOS's user-observable behavior for in-scope items; gaps are shipped and verified.

---

## Scope Boundaries

- Bounded to iOS PR #45; the v1.6.0 freeze stands for all other post-v1.6.0 iOS work.
- iOS-only internals are examined on Android for bug/optimization parity, but the literal Swift fix (e.g., CoreStore probe order, AVAudioSession arbitration code) is not ported.
- iOS-repo / Apple-portal out-of-band actions (revoke WeatherKit key, delete CI secrets, ASC privacy label) are iOS concerns — only their Android analogues are in scope.
- No new reusable tooling (no `ios-parity` "PR mode" build) — a written recipe only.
- Remediation execution is gated behind user review of the matrix; this doc covers the methodology + sweep, not the per-gap implementation plans.

---

## Key Decisions

- **Master list from iOS's committed audit docs**, not reverse-engineered from diffs — faster and higher fidelity; spot-checked against the diff (R2).
- **Behavior parity, not implementation parity** — match user-observable behavior via Android-native mechanisms (`AudioFocus`, Room, whisper.cpp), not literal Swift ports.
- **iOS-only internals get an Android-equivalent health check** (bug + optimization), not a blanket N/A — per explicit user instruction.
- **Report-first gate before code** — matrix → review → batched remediation.

---

## Dependencies / Assumptions

- Local `../pilgrim-ios` checkout with PR #45 branch fetchable (the `ios-parity` skill already assumes this sibling repo).
- iOS PR #45's committed `docs/` artifacts are complete enough to serve as the master list (assumption — validated by the R2 spot-check).
- Some items (VoiceOver/TalkBack, leak/battery over a multi-hour walk, launch-time delta) require on-device verification and will be **flagged, not closed**, by the desk sweep.

---

## Outstanding Questions

- None blocking. Remediation batching (autopilot vs. manual, grouping) is deferred to the report-first gate, once the gap count and shape are known.
