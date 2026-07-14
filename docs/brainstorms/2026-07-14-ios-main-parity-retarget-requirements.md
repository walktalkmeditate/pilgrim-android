---
date: 2026-07-14
topic: ios-main-parity-retarget
---

# iOS Main Parity Retarget — Seek Mode + Journal Scenery

## Summary

Re-pin the frozen Android parity anchor from iOS v1.6.0 to iOS main tip `c1745e8` (2026-07-14), then port the two features iOS shipped since: Seek Mode (v1.8.0) as Phase 14, staged along iOS's own U1–U10 plan, and the 1.8.1 journal ink-scroll scenery as Phase 15. Both land together in a single Android v1.2.0 release. The old v1.7.0 parity gate closes with a dated waiver.

---

## Problem Frame

Android's parity scope froze at iOS v1.6.0 (2026-05-13). iOS has kept moving:

| iOS range | Content | Android status |
|---|---|---|
| v1.6.0 → v1.7.0 | Finalization pass (iOS PR #45, #47, onboarding #43) | Already swept — parity matrix + PRs #177–#193 |
| v1.7.0 → `86588b3e` | `.gitmodules` chore + seek-mode requirements/plan docs | Docs only; the plan docs are port input |
| `86588b3e` → v1.8.0 | Seek Mode — ~40 commits in ten planned units (U1–U10) plus post-ship follow-ups | Not ported |
| v1.8.0 → `c1745e8` | 1.8.1 journal ink-scroll scenery (gates, cairns, moons, lanterns, drift) — awaiting Apple approval | Not ported |

Android users are a full headline feature behind, and the frozen CLAUDE.md target actively forbids porting the new work. Meanwhile the v1.7.0 parity gate sits NOT PASSABLE on `unverified` rows (dark-mode/motion re-captures, iOS-sim-infra-blocked rows) plus a needs-device backlog — mostly verification debt that consumes attention without changing any shipping decision (the live phone-call audio probe is the exception; R3 carves it out).

---

## Requirements

**Anchor re-pin**
- R1. The frozen Android parity anchor becomes `pilgrim-ios` @ `c1745e8` (origin/main, 2026-07-14). Update the parity-scope section of `CLAUDE.md`; update the project memory store's `parity_scope_v1_5_0.md` (still pins v1.5.0 @ `db4196e`) and the `ios-parity` skill's pinned anchor; sweep other docs/skill descriptions that still name v1.5.0/v1.6.0 as the target.
- R2. If iOS lands further commits before Android v1.2.0 ships, re-diff `c1745e8..new-tip` and triage. Chores, hotfixes, and incremental refinements to features already in Phase 14/15 scope (e.g., Apple review forces 1.8.1 changes) fold in without a re-brainstorm: route each delta to the phase that owns the touched surface, reopening it if closed; if both phases are closed, the delta becomes a named pre-release stage gated before the v1.2.0 tag; then re-pin. A new headline feature, a revert of already-ported work, or a redesign is not auto-folded — it triggers an explicit re-triage decision with the user.

**v1.7.0 gate closure**
- R3. Record a dated waiver (2026-07-14) in the parity ledger's Gate summary section (`docs/parity/2026-05-15-parity-ledger.md`), with a one-line pointer appended to `docs/parity/gate.md`: the remaining `unverified` re-capture rows are accepted as-is and the old gate no longer blocks anything. Carve-out: the live-behavior items — the PR #47 phone-call LOSS vs LOSS_TRANSIENT probe and the interruption-resume residuals — are not waived; they move into Phase 14's consolidated device QA pass (R12).

**Phase 14 — Seek Mode**
- R4. Port the end-state Swift at `c1745e8`, not the commit history. Stage boundaries mirror iOS's U1–U10 units. Spec sources: iOS `docs/brainstorms/2026-07-06-seek-mode-requirements.md` + `docs/plans/2026-07-06-001-feat-seek-mode-plan.md`, cross-checked against shipped code wherever review fixes diverged from the plan.
- R5. The v1.8.0 seek follow-ups — the post-plan-completion commits in `2574523..v1.8.0` — are Phase 14 scope even where they touch journal/goshuin surfaces. Anchors: goshuin seeking-threshold seals `d476bc3`; journal quick-view mode glyph `d7402d5`; summary provenance/halos `e5c9735` + `8dc2b1f` (seeding `2b88455`); pre-walk options-sheet surfacing `85373c1`; crescent/celestial refinements `28417e8`, `b568654`, `bcafa66`, `174e9e0`, `4ceb0a9`, `5c552c8`, `b98536f`, `7c3d618`; plus the remaining seek fixes in the range (`ece26a7`, `a0624d0`, `1f727ce`, `52ff1a5`) and the post-ship review-fix commits (`cee3a15`, `3993b11`).
- R6. U8 (Live Activity: clearing distance + direction) ports as foreground-service notification content, per the established iOS ↔ Android mapping.
- R7. Bundled seek audio (sonar ping, reveal bowl) is reused from the iOS repo's assets rather than re-generated.

**Phase 15 — Journal Ink-Scroll Scenery**
- R8. Port the `v1.8.0..c1745e8` scenery end-state: torii gates with shimenawa/shide, cairns that grow with findings and cap in winter, real moon phases, lit lanterns, depth/age/touch landscape, seasonal drift, and the seeking gate.
- R9. Phase 15 starts only after Phase 14's persistence vocabulary has landed — the seeking gate, mode glyph, and seeking seals all read seek walk data.

**Acceptance and verification**
- R10. Every stage gets an `/ios-parity port` spec with Swift quotes pinned to `c1745e8` before implementation.
- R11. Unit tests per house rules, including the platform-object builder Robolectric rule for any new WorkRequest/notification/MediaItem construction.
- R12. One consolidated on-device QA pass (OnePlus 13) per feature at the end of each phase — not per stage, and not a blinded sweep matrix. Phase 14 additionally gets a mid-phase device smoke check once the map/audio cluster lands (start a seek walk, observe fog clearing, hear the sonar, arrive at a clearing); its consolidated pass also covers the live-behavior items carved out of the R3 waiver.

**Release**
- R13. Single Android release: v1.2.0 containing both phases. No intermediate seek-only production release.

---

## Acceptance Examples

- AE1. **Covers R2.** Given Apple review sends 1.8.1 back and iOS lands 4 scenery-fix commits on main, when the delta is reviewed, the commits are folded into Phase 15's backlog (reopening it if closed) and `CLAUDE.md` re-pins to the new tip — without reopening this requirements doc. Had the delta instead been a new headline feature, it would go to the user for re-triage.
- AE2. **Covers R9.** Given Phase 14 has not yet shipped its persistence stage, when Phase 15 planning begins, it is blocked and re-sequenced rather than stubbing seek data.

---

## Success Criteria

- Android v1.2.0's Seek Mode and journal scenery match their R10 parity specs (Swift quotes pinned at `c1745e8`), confirmed by the per-feature device QA passes — spec equivalence, not side-by-side cross-platform comparison.
- `CLAUDE.md` names `c1745e8` as the frozen parity target; no doc or skill description still points at v1.5.0/v1.6.0.
- The old gate is closed with a dated waiver — no lingering "NOT PASSABLE" state demanding attention.
- `ce-plan` can start Phase 14 from this doc plus the iOS plan docs without inventing scope, sequencing, or acceptance criteria.

---

## Scope Boundaries

- v1.7.0 verification residue: `unverified`-row re-captures (waived per R3). The live-behavior items from the PR #45/#47 backlog are not waived — they fold into Phase 14's device QA (R3/R12).
- Anything iOS ships after `c1745e8`, except deltas folded in via the R2 re-pin rule.
- iOS-only chores inside the range: `.gitmodules`, CI/simulator fixes, `Package.resolved` pins, Swift 6 warning cleanups, WeatherKit native/REST work (Android uses Open-Meteo).
- Blinded sweep-matrix machinery for the new features — acceptance is spec-anchored + device QA instead.

---

## Key Decisions

- **End-state port over commit replay**: iOS redesigned mid-flight (the "wisp" became the crescent); replaying commits would build then delete dead states. Port what ships at the pin.
- **iOS U1–U10 as Android stage boundaries**: the decomposition is already dependency-ordered (model → engine → persistence → audio → map → wiring → notification → summary → gate) and validated in production; mirroring it keeps 1:1 spec traceability.
- **Single v1.2.0 release**: user choice — Seek Mode waits for scenery so users get both at once.
- **Waive rather than clear the old gate**: verification spend goes to the new features; the old machinery is what produced the unpassable state. Live-behavior probes are carved out into Phase 14 device QA rather than waived.
- **Seek follow-ups belong to Phase 14**: they shipped in v1.8.0 and read seek data, even though they touch journal/goshuin surfaces.

---

## Dependencies / Assumptions

- The iOS plan docs describe the *planned* build; the shipped Swift at `c1745e8` is authoritative where they diverge.
- 1.8.1 is still in Apple review — churn is possible and handled by R2, not treated as a risk to this doc.
- Android v1.1.0's staged rollout (20%) completes/promotes independently of this work.
- Seek audio assets are project assets under the same license and can be copied across repos.

---

## Outstanding Questions

### Deferred to Planning

- [Affects R4][Technical] Final Android stage count — some U-units may merge (U8's notification surface is much smaller on Android than a Live Activity).
- [Affects R6][Technical] What the ongoing notification can actually render for clearing distance/direction (text vs custom RemoteViews) — design during planning.
- [Affects R8][Technical] Whether scenery maps onto the existing ink-scroll Canvas renderer or needs a new drawing layer (iOS extracted `InkScrollView+Scenery`).

---

## Deferred / Open Questions

### From 2026-07-14 review

- **Phase 15 specs pinned to Apple-unapproved code despite free deferral** — Anchor re-pin (R1), Phase 15 (R8–R10) (P1, product-lens + adversarial, confidence 100)

  Every Phase 15 stage spec quotes Swift at a commit Apple may still force to change, so specs, CLAUDE.md's frozen target, and the success criterion can all be invalidated mid-flight — and Android could ship scenery no iOS user ever sees, inverting the parity premise. R9 already blocks Phase 15 until Phase 14's persistence lands, so deferring the Phase 15 pin to the eventual 1.8.1 release tag costs essentially no schedule; R2 handles churn by absorbing rework rather than avoiding it. The doc never weighs this near-free alternative.

  <!-- dedup-key: section="requirements anchor repin r1 phase 15 r8r10" title="phase 15 specs pinned to appleunapproved code despite free deferral" evidence="1.8.1 journal ink-scroll scenery (gates, cairns, moons, lanterns, drift) — awaiting Apple approval" -->

  **Resolved 2026-07-14 (planning):** user pulled Phase 15 into the same implementation plan as Phase 14 — scenery specs pin to `c1745e8` now; the bounded R2 fold-in rule absorbs any Apple-review churn. Split-pin alternative declined.
