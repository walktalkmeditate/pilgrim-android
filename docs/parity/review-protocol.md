# Blinded Per-Screen Review Protocol (U4)

The verdict step of the parity sweep. Exists to kill the confirmation bias that let the bell double-bug pass six review cycles and the Mapbox crash reach the user: the reviewer must not see *why* a divergence was "intended" until after it has independently recorded the difference.

## Reviewer

A **fresh agent per ledger row**. No carryover of "this was intended" or "this matched last row" across rows. One row in, one verdict out.

## Allowed inputs (the ONLY things in the review prompt)

- The row's `screen / state` label and `appearance` mode being judged
- iOS screenshot for that state+mode
- Android screenshot for that state+mode
- Motion captures (both platforms) when the row is `anim`
- The iOS Swift slice for the screen (quoted)
- The Android implementation reference for the screen (quoted)

## Forbidden inputs (MUST NOT appear in the review prompt)

- `CLAUDE.md` (contains parity-target + "accept this divergence" framing)
- Any in-code comment containing "accept this divergence", "iOS parity", "intentional", "stub", "deferred", "fallback rationale", or equivalent justification
- The origin requirements doc / this plan / the brainstorm
- Prior ledger triage notes or verdicts for this or any other row
- The list of known stubbed-native substitutions

Rationale is attached **only at U7 triage**, structurally after the verdict exists. The capturer assembles the review prompt and is responsible for excluding the forbidden set.

## Verdict rubric (exactly one)

- **`match`** — within this state+mode, the Android surface is visually AND behaviorally indistinguishable from iOS: layout, spacing, type, color, iconography, copy, and (for `anim` rows) motion cadence all correspond; the Swift/Android behavior pair shows no logic/timing/edge divergence.
- **`close-the-gap`** — any difference the reviewer judges should be made to match iOS (visual drift, missing element, behavioral divergence, missing Android surface entirely).
- **`re-justify`** — a difference that *might* be acceptable to ship but only with an explicit, dated, current reason supplied at triage. The reviewer assigns this when the difference looks deliberate/structural but they were (correctly) not told why.

### Hard rule: stubbed-native substitutions cannot be `match`

If the observed Android surface is a substitute for an iOS-native capability (weather, map, any "unavailable/fallback" surface, server-pin-backed content the reviewer sees as absent/different), the reviewer marks it `close-the-gap` or `re-justify` **purely on the observed difference**. The reviewer is not told it was intentional — the rubric simply forbids `match` for an observable substitution. This is what makes "re-litigate every divergence" enforceable (origin R6, plan Key Decision).

## Reject conditions (not a verdict — un-reviewable)

- A required screenshot (iOS or Android for a listed appearance mode) is missing → row stays `unverified`, reason `missing-evidence:<mode>`. Never default a missing capture to `match`.
- An `anim` row missing a motion capture on either platform → `unverified`, reason `missing-motion`.

## Output (written back to the ledger row by the capturer)

- `verdict` ∈ {`match`, `close-the-gap`, `re-justify`}
- `evidence` — links to the iOS/Android shots (+ motion) the verdict was based on, per appearance mode
- `observed-diff` — one-line description of the difference (empty for `match`) — this is the ONLY thing U7 triage and downstream `ce-plan` need; it must stand alone without the reviewer having seen rationale

A row is `verified` only when every appearance mode listed in its `appearance` column has a verdict (a row may carry per-mode verdicts; the row's effective verdict is the worst of them — `close-the-gap` > `re-justify` > `match`).

## Dry-run acceptance (U4 done-check)

Exercise the protocol on three representative rows before declaring U4 complete:
1. `summary.map.no-token` — Android "Map unavailable" vs iOS Mapbox map, rationale withheld → expect `close-the-gap` or `re-justify`, never `match` (origin AE1).
2. A genuinely matching row (e.g. `settings.appearance` light mode) → expect `match`.
3. An `anim` row (`overlay.constellation`) → expect the reviewer to demand motion, not verdict a still.
