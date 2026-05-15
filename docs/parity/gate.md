# Parity Ship Gate (U7)

The pass/fail rule over `2026-05-15-parity-ledger.md`, and how triaged gaps feed downstream remediation.

> **Status:** rule defined; not yet evaluable — depends on U5/U6 verdicts, which are blocked on the iOS capture environment. Current ledger = 86/86 `unverified` ⇒ **NOT PASSABLE**.

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
