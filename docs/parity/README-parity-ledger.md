# Parity Ledger — Regeneration & Reconciliation Recipe

Operational recipe for `2026-05-15-parity-ledger.md`. The ledger's completeness must be **provable from iOS source**, never asserted from memory (plan R1/R9, origin F3).

## Pinned anchor

- iOS source: `../pilgrim-ios` checked out at `fcd2255` (v1.6.0, the `CLAUDE.md` parity target). Verify before any scan: `cd ../pilgrim-ios && git rev-parse --short HEAD` → must be `fcd2255`. If it drifted, `git -C ../pilgrim-ios checkout fcd2255` (or repin the ledger header + re-run reconcile if the user intentionally moved the target).

## Regeneration (U1) — build the screen×state set

1. **Screen surfaces** — enumerate every iOS presentation root:
   - All files under `../pilgrim-ios/Pilgrim/Scenes/**/*.swift`.
   - Presentation modifiers in `Scenes/**` + `Views/**`: `.sheet(`, `.fullScreenCover(`, `.popover(`, `NavigationLink`, `NavigationStack` destinations, `MainTabView` tab roots.
   - `grep -rnE '\.(sheet|fullScreenCover|popover)\(|NavigationLink|NavigationStack' ../pilgrim-ios/Pilgrim/Scenes ../pilgrim-ios/Pilgrim/Views`
2. **States per screen** — for each screen, read its view-state enum / top-level `switch` / `if` ladder and the `@State`/`@Published` flags that change the rendered surface. Enumerate the *distinct rendered states* (loading / empty / error / populated / milestone / archived / paused / reduce-motion / locked-vs-unlocked, etc.). One ledger row per distinct state, never collapse N states into 1.
3. **Appearance cross-cut** — do NOT explode rows ×3. Set the row's `appearance` column to the modes that materially differ (`L/D/C`, or `C only`, or `C(no nebulae)` for ActiveWalk/Summary/Meditation routes). A row is `verified` only when every listed mode is captured+reviewed.
4. **Animation flag** — mark `anim` when the screen has motion that stills can't prove (welcome, seal reveal, constellation, stats reveal, calligraphy draw, breathing logo, dot ripple, FAB cross-fade, greeting fade). Animated rows additionally require a `reduce-motion` capture.
5. **Android cross-check** — for each iOS row, name the Android route/surface (`ui/...` path or `Routes.*`). iOS surface with **no** Android counterpart → keep the row, mark android cell `(NO Android equivalent — gap)`; it captures iOS-only and enters U7 as `close-the-gap`.
6. **Row id** = `<area>.<screen>.<state>` (stable; never renumber — additions take new ids, removals go `stale`).
7. **Seed requirement** — fill from what data the state needs (`none` / `≥N walks` / `archived walk` / `milestone walk` / `live walk` / `walk w/ photos|recordings|route|altitude` / `turning date` / `constellation mode`). Drives the U2 fixture.

Determinism check: re-running steps 1–7 against an unchanged `fcd2255` MUST reproduce an identical ledger (modulo deliberately edited rows). Any non-deterministic diff = the recipe under-specifies enumeration; tighten it, don't hand-patch the ledger.

## Reconciliation (U3, origin F3) — keep completeness provable

Run whenever the iOS parity target changes, or before declaring the gate passable.

1. Regenerate the screen×state id set via the Regeneration steps above against the (possibly new) pinned SHA.
2. Set-diff the regenerated ids against the current ledger's `id` column:
   - **New id** (in source, not in ledger) → insert a row, `verdict = unverified`. New unverified rows **block the ship gate** (R8).
   - **Removed id** (in ledger, not in source) → set `verdict = stale`. Do NOT delete the row — keep its historical evidence/verdict for audit. `stale` rows do not block the gate.
   - **Unchanged id** → leave verdict + evidence intact (no recapture unless the iOS source for that screen changed materially; if it did, reset to `unverified`).
3. Update the Gate summary block in the ledger (counts + PASS/NOT-PASSABLE).
4. Reconcile on an unchanged source MUST be a no-op (zero row delta). A non-empty delta on unchanged source = recipe bug.

## Gate coupling (R8, computed in `gate.md` at U7)

Ship gate **PASS** iff: zero `unverified` rows (including any newly reconciled) **AND** zero open `close-the-gap` rows **AND** every `re-justify` row carries a dated current reason. Anything else → NOT PASSABLE with the blocking rows listed.

## What this recipe is NOT

- Not a runtime crawl. Static-source enumeration is deterministic + regenerable; gated states (108-walk milestone, mid-animation) aren't reachable by a crawler without the U2 seed anyway.
- Not memory-driven. If a row can't be traced to an iOS `Scenes/**` or `Views/**` ref, it does not belong in the ledger.
