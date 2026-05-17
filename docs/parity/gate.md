# Parity Ship Gate (U7)

The pass/fail rule over `2026-05-15-parity-ledger.md`, and how triaged gaps feed downstream remediation.

> **Status (2026-05-16, U7 re-evaluated after close-cluster remediation):** Result: **NOT PASSABLE** (one blocking category remains: `unverified`). **The open-`close` category is now CLEARED** — every `close`/`close-the-gap` cluster has been remediated and code-shipped: onboarding (branded splash, permissions copy + mic card + badges + button labels), settings.appearance (grouped card + iOS-verbatim copy), settings.about (hero tree removed), settings.data-detail (bare-action rows + journey chevrons), journal.inkscroll (iOS-literal plurals), settings.sound (storageSection download-progress row), goshuin.populated (filter bar + Share Goshuin + 1080×1920 GoshuinShareRenderer), goshuin.share-render (Android port shipped). setup.welcome.entrance + presentation-model + stats-header-placement = dated `re-justify` (platform-idiomatic / asset-identical, not defects). All on-device verified on the API-35 emulator; full unit suite 2015 tests green (1 known pre-existing Robolectric batch-isolation flake — `WalkSummaryViewModelLightReadingGateTest` passes 3/0/0 solo — unrelated to remediation code). **Remaining gate blocker:** `unverified` rows only — the D/C + motion variants of the remediated rows (mechanical re-capture, appearance-cross-cut recipe proven) plus the deep-state/iOS-sim-infra-blocked rows (`pilgrim-ios` is the frozen reference and can't be instrumented for deep nav). Path to PASS: D/C+motion re-capture of remediated rows → flip blinded verdicts to `match`; iOS deep-capture infra (or A5 waiver) → clear the infra-blocked unverified rows.

## Triage (origin F2, A5 = user/triage owner)

For every row whose effective verdict is `close-the-gap` or `re-justify`:

1. **Attach rationale now** — this is the FIRST point the withheld context enters: relevant `CLAUDE.md` line, in-code "accept divergence" comment, or origin doc passage.
2. **Decide, dated:**
   - `close` → route to remediation (a difference we will fix to match iOS).
   - `re-justify: <explicit current reason> (YYYY-MM-DD)` → ships as a divergence with a live, dated justification. A stale code comment is NOT sufficient — the reason is re-stated here, dated, by a human.
3. Record the decision in the row's `triage note`.

## Gate rule (R8 / origin R10)

**PASS** iff ALL hold:

- Zero `unverified` rows (including any inserted by reconciliation — `README-parity-ledger.md`).
- Zero `close-the-gap`/`close` rows still open (each either fixed → re-captured → `match`, or explicitly re-decided `re-justify`).
- Every `re-justify` row carries a dated current reason.

Otherwise **NOT PASSABLE** — output the exact blocking row ids by category (unverified / open-close / undated-re-justify).

`stale` rows never block.

## Downstream consumption (origin success criterion)

`close` rows are clustered by surface so each cluster is a self-contained `ce-plan` input — no re-investigation needed:

| Cluster (example) | Member rows feed `ce-plan` with |
|---|---|
| Walk Summary map | row id · `observed-diff` · iOS Swift ref · triage decision |
| Bell / sound | same shape |
| Constellation chrome | same shape |
| Goshuin (share renderer, page indicators) | same shape |
| Missing Android surface (`prompts.*`, `goshuin.share-render`, `walk.active.sparkline`) | iOS-only evidence + "no Android surface" |

Each cluster → its own `ce-plan` → `ce-work`. This gate plan only finds + adjudicates; it does not fix (plan Scope Boundaries).

## Gate summary block

Mirror the computed result into the ledger's "Gate summary" section on every reconcile / triage pass: total · unverified · match · close-the-gap · re-justify · stale, and PASS / NOT PASSABLE with blocking ids.
