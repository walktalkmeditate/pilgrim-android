---
date: 2026-05-15
topic: ios-android-parity-verification
---

# iOS↔Android Parity Verification — Last-Mile Ship Gate

## Summary

A systematic, repeatable parity-verification pass that mechanically enumerates every screen and state from the iOS source, diffs each one against Android both visually (paired screenshots in a shared seeded state) and behaviorally (Swift quoted next to the Android claim), reviewed by fresh agents blinded to prior "intentional divergence" rationale, producing a screen-indexed ledger with a per-screen ship verdict.

---

## Problem Frame

The Kotlin/Compose port of `../pilgrim-ios` is near complete but bugs keep reaching the user that code review and ad-hoc device QA did not catch. In this session alone: a Mapbox no-token crash that broke *every* Walk Summary surfaced only when the user opened a walk; a two-bug bell defect (download worker rejecting BELL assets + preview ignoring the asset id) where every bell sounded identical and which a prior six review cycles rubber-stamped; a Constellation pill dark-rectangle; a Set-Intention prompt firing after the walk started. Each was real, user-visible, and shippable as-is.

The escapes share one root cause: verification is **memory-driven and confirmation-biased**. The 5-agent code audit found structural gaps but missed runtime behavior. Hand device QA found runtime bugs but skipped screens (the agent re-derived the screen list from memory each pass and missed branches). "Intentional divergence" comments in the code and `CLAUDE.md` let reviewers rubber-stamp deviations without re-litigating them. The cost is a port that *looks* done but ships behavioral and visual drift the team has explicitly decided is unacceptable: the ship bar is pixel **and** behavior parity on every screen, with every divergence — even documented ones — re-justified or closed.

There is no deterministic enumeration of screens, no shared data fixture making the same walk render identically on both platforms, and no review step that is denied the "this is intended" context.

---

## Actors

- A1. Enumerator: derives the exhaustive screen × state list mechanically from the iOS SwiftUI scene/navigation graph (not from memory).
- A2. Capturer: drives both apps into each enumerated state against a shared seed fixture and captures the iOS-sim and Android screenshots.
- A3. Behavior differ: reads the iOS Swift for the screen and records the behavior claim with the Swift quoted inline next to the Android implementation.
- A4. Adversarial reviewer: a fresh agent per screen, blinded to all prior "intentional divergence" rationale, whose job is to find difference and assign the verdict.
- A5. Triage owner (human): reads the completed ledger, decides close-the-gap vs re-justify per row, routes clusters to remediation.

---

## Key Flows

- F1. Per-screen parity pass
  - **Trigger:** A screen/state row exists in the enumerated ledger and is unverified.
  - **Actors:** A2, A3, A4
  - **Steps:** (1) Capturer seeds the shared fixture and drives both platforms into the exact state. (2) Capturer attaches paired iOS↔Android screenshots to the row. (3) Behavior differ attaches the Swift-quoted behavior claim + Android counterpart. (4) Adversarial reviewer — given only the two screenshots and the behavior pair, NOT any divergence rationale — assigns a verdict: `match` / `close-the-gap` / `re-justify`. (5) Row is marked verified with the verdict and evidence.
  - **Outcome:** Every ledger row carries paired evidence and an independent verdict.
  - **Covered by:** R1, R2, R3, R4, R5, R7

- F2. Divergence re-litigation
  - **Trigger:** A screen's behavior or visuals differ from iOS and the difference corresponds to a documented intentional divergence (Mapbox, Open-Meteo, Material nav idiom, stubbed native feature).
  - **Actors:** A4, A5
  - **Steps:** (1) Reviewer records the difference as a raw gap *without* seeing the rationale. (2) Triage owner attaches the known rationale at triage time. (3) Triage owner decides: close to true iOS parity, or re-justify with an explicit, current reason. (4) Verdict and decision recorded on the row.
  - **Outcome:** No divergence ships on "it was documented once" — each is an active, recorded decision.
  - **Covered by:** R6, R8

- F3. Coverage reconciliation
  - **Trigger:** Enumeration pass completes or the iOS source changes.
  - **Actors:** A1, A5
  - **Steps:** (1) Enumerator regenerates the screen × state list from iOS source. (2) Diff against the ledger's existing rows. (3) New/removed screens flagged; unverified new rows block the ship gate.
  - **Outcome:** Ledger completeness is provable against iOS source, not asserted from memory.
  - **Covered by:** R1, R9

---

## Requirements

**Enumeration**
- R1. The screen × state inventory is generated mechanically from the iOS SwiftUI scene/navigation graph and its view-state machines — not from the agent's or author's memory of the app.
- R9. The inventory enumerates not just screens but the distinct *states* of each screen (empty / loading / error / populated / milestone / archived / reduce-motion, etc.) since parity bugs this session were state-specific (no-token map, 108-walk milestone, archived walk).

**Shared fixture**
- R2. A single seed fixture renders the *same* walk history, settings, and edge-state data on both iOS and Android, so a screenshot diff reflects implementation drift, not data drift. Hard-to-reach states (90-minute walk, sacred-number milestone, archived walks, tended import) are reachable via the fixture or a debug affordance.

**Visual diff**
- R3. Every screen row carries a paired iOS-simulator screenshot and Android screenshot captured in the same seeded state, laid out for direct visual comparison.
- R7. Animated screens (welcome ritual, seal reveal, constellation overlay, stats reveal, calligraphy draw) additionally carry a short capture of the motion, not only a still — stills cannot prove animation/timing parity.

**Behavior diff**
- R4. Every screen row carries the relevant iOS Swift quoted inline next to the Android implementation claim, so logic/timing/edge-case drift (the bell-worker type guard, intention-prompt timing, focus-loss handling class) is visible without running the app.

**Independent review**
- R5. Each screen is reviewed by a fresh agent that is given only the paired screenshots and the behavior pair. The reviewer is NOT given any "intentional divergence" rationale from code comments or `CLAUDE.md` for that screen.
- R8. The known divergence rationale is attached only at triage time (A5), after the reviewer has independently recorded the raw difference — so "documented as intentional" cannot pre-empt re-litigation.

**Ledger + gate**
- R6. The ledger assigns each screen one of exactly three verdicts: `match`, `close-the-gap`, `re-justify`. Stubbed/replaced native features (Open-Meteo weather, Mapbox map, whisper/cairn pins) cannot receive `match` by virtue of being documented — they enter triage as `close-the-gap` or `re-justify`.
- R10. The ship gate is: zero unverified rows AND zero `close-the-gap`/`re-justify` rows left unresolved by the triage owner.

---

## Acceptance Examples

- AE1. **Covers R5, R8.** Given the Android Walk Summary renders an Open-Meteo-backed "Map unavailable" fallback where iOS shows a Mapbox map, when the adversarial reviewer evaluates the screen, the reviewer records a visual+behavior difference and assigns `re-justify` (or `close-the-gap`) — because the divergence rationale was withheld from them — and the triage owner later decides its disposition with the rationale in hand.
- AE2. **Covers R2, R9.** Given a parity row for "Goshuin grid at the 108-walk milestone," when the capturer drives both platforms, the shared fixture seeds exactly 108 finished walks on both, so the milestone glow/celebration is comparable rather than absent on one side.
- AE3. **Covers R1, R9.** Given iOS adds a new screen state after the ledger was built, when F3 reconciliation runs, the new state appears as an unverified row and blocks the ship gate until verified.
- AE4. **Covers R7.** Given the Constellation overlay animates stars/nebulae, when its row is verified, a motion capture (not only a still) is attached so drift in twinkle/drift cadence is reviewable.

---

## Success Criteria

- The set of user-visible parity defects found *after* this process completes is approximately zero — specifically, no recurrence of the class of escape seen this session (whole-surface crash, all-variants-identical, prompt-timing, decorative-band).
- Ledger completeness is provable: every screen/state in the iOS source maps to a verified ledger row, and the mapping is regenerable, not asserted.
- A downstream `ce-plan` can consume the `close-the-gap`/`re-justify` clusters directly — each gap row states the screen, the observed difference, the Swift reference, and the triage decision, with no further investigation needed to begin remediation.
- Every divergence that ships has an explicit, dated, current justification on its row — not a stale code comment.

---

## Scope Boundaries

- Fixing the gaps is out of scope. This process *finds and adjudicates* parity defects; remediation is a downstream `ce-plan` per gap cluster.
- A permanent CI screenshot-regression harness (the fully-automated nav-walker on every build) is out of scope for the last-mile gate — it is a worthwhile follow-up, but the gate itself is the one-time exhaustive sweep plus its reconciliation step.
- Re-architecting the port to remove platform-idiom divergence (e.g., rebuilding Compose nav to mimic UIKit pixel-for-pixel) is not assumed by this doc — whether to do so is a per-row triage decision (R6/R8), not a precondition.
- Non-screen parity (background services, notification behavior, widget/Glance) is not covered by the screen ledger; if it matters for ship it is a separate sweep, noted here so its absence is intentional.

---

## Key Decisions

- Mechanical enumeration over memory-driven: memory-driven QA demonstrably skipped screens this session; the inventory must be regenerable from iOS source so completeness is provable.
- Reviewer blinded to divergence rationale: the "re-litigate every divergence" bar is unenforceable if the document asserting "this is intentional" is in front of the reviewer; rationale attaches at triage, after the independent verdict.
- Stills-first, motion-capture only for animated screens: full video parity on every screen is disproportionate carrying cost for a one-time gate; motion capture is targeted to screens where animation is the content.
- Stubbed-native features cannot auto-pass: per the user's explicit bar, Open-Meteo/Mapbox/whisper-cairn substitutions enter triage as unresolved, which likely forces real research/build before ship, not just bug-fixing.
- Shared fixture is a curated `.pilgrim` file imported by both apps via the existing importer: reuses shipping import code, adds no debug surface, and is cross-platform by construction (the importer is already the cross-device carrier). States the `.pilgrim` format cannot carry (a live in-progress walk, reduce-motion) are handled per-row at capture time, not by a parallel seeder that could itself drift.

---

## Dependencies / Assumptions

- The curated `.pilgrim` seed file is a hard dependency and a build item *inside* this process — without identical imported data on both platforms every screenshot diff is noise. It must encode the edge states (long walk, sacred-number milestone, archived walks, tended import) and round-trip through both importers to a comparable state.
- iOS simulator capture is available in the environment (Xcode/simulator) alongside the connected Android device or emulator.
- `../pilgrim-ios` is pinned to the parity target while the sweep runs (assume iOS v1.6.0 unless the user repins), so reconciliation diffs are stable.
- Some iOS states require app changes to reach deterministically on Android (debug seeder / fixture loader); building that affordance is in scope as a means, not a shipped feature.

---

## Outstanding Questions

### Deferred to Planning

- [Affects R1][Needs research] What is the most reliable mechanical source for the iOS screen×state enumeration — SwiftUI `NavigationStack`/`.sheet`/`.fullScreenCover` static scan, the scene graph, or a runtime UI-test crawl?
- [Affects R7][Technical] Motion-capture mechanism for animated screens (screen recording vs frame-sampled stills) and how the reviewer evaluates cadence parity.
- [Affects R10][Technical] Ledger format and where it lives (`docs/parity/` table, generated index) so `ce-plan` can consume gap clusters directly.
