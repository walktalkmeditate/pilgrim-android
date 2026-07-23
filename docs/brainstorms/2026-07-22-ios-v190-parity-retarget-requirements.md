---
date: 2026-07-22
topic: ios-v190-parity-retarget
---

# iOS v1.9.0 Parity Retarget — Route Rotation + Whisper Base + Map Glyphs

## Summary

Re-pin the frozen Android parity anchor from `c1745e8` to iOS main tip `9a418e4` (2026-07-21, the commit expected to tag v1.9.0), then port the three features iOS shipped since: collective route rotation as Phase 16, the Whisper tiny→base bump plus prompt-pipeline upgrade as Phase 17, and the SVG map glyphs as Phase 18. All three land together in a single Android v1.3.0 release.

---

## Problem Frame

Android v1.2.0 shipped with exact parity at `c1745e8` (2026-07-16 R2 re-diff found iOS main unmoved). iOS has since landed four PRs:

| iOS PR | Content | Android status |
|---|---|---|
| #53 | Collective route rotation — daily route selection, bundled + fetched/cached route catalog, contribution ledger, dataset attribution, live Settings line (~3,400 lines) | Not ported |
| #55 | Whisper model tiny→base + prompt-pipeline upgrade: shared standard preamble, walk-character preamble note, attend-to directives, practice lexicon (wander/seek), response contract, pauses/elevation/light-crossings context (~1,255 lines) | Not ported |
| #56 | SVG map glyphs — eight vector masters, `MapGlyphImageBuilder` rasterizing for Mapbox, glyphs on map whispers/cairns, add-a-stone sheet, cairn detail, whisper mood rows (~1,374 lines) | Not ported |
| #57 | Glyph size doubling follow-up (96/112pt) | Not ported (folds into #56 scope) |

These will ship as iOS v1.9.0. Android users fall three features behind, and the frozen `CLAUDE.md` target actively forbids porting them — new headline features past the pin require explicit user re-triage, which this brainstorm is.

---

## Requirements

**Anchor re-pin**
- R1. The frozen Android parity anchor becomes `pilgrim-ios` @ `9a418e4` (origin/main, 2026-07-21). Update the parity-scope section of `CLAUDE.md`, the `ios-parity` skill's pinned anchor, and any memory/doc references that still name `c1745e8` as the current target.
- R2. The fold-in rule carries forward unchanged: if iOS lands further commits before Android v1.3.0 ships (including the v1.9.0 tag landing on a later build-bump commit, or App Review churn), re-diff `9a418e4..new-tip` and triage — chores, hotfixes, and incremental refinements to Phase 16/17/18 surfaces fold into the owning phase (reopening it if closed; if all phases are closed, the delta becomes a named pre-release stage gated before the v1.3.0 tag), then re-pin. A new headline feature, a revert of ported work, or a redesign triggers explicit user re-triage.

**Phase 16 — Collective route rotation (iOS PR #53)**
- R3. Port the end-state Swift at the pin: daily route selection, route catalog bundled and registered at launch, network fetch + cache of the catalog, contribution ledger (a contributed walk ends among real pilgrims), dataset attribution, and the Settings line going live. Spec sources: iOS `docs/brainstorms/2026-07-18-daily-rotating-pilgrimage-routes-requirements.md` + `docs/plans/2026-07-18-001-feat-collective-route-rotation-plan.md`, cross-checked against shipped code wherever review fixes diverged.
- R4. The bundled route catalog is reused from the iOS repo's assets (same data, same attribution), not re-generated.
- R5. Android consumes the same backend contract iOS ships against — no Android-specific endpoint changes. When the catalog fetch fails or returns empty, the bundled catalog still resolves the daily route (iOS end-state behavior).
- R5a. Android's daily-route selection passes the same cross-implementation parity vectors iOS pins in `CollectiveRouteCatalogTests` — the 62 webPicks/webLines vectors plus the bundled fixture — so the same catalog and date resolve the same route on Android, iOS, and the web. A divergence here breaks the collective premise invisibly; single-platform tests cannot catch it.

**Phase 17 — Whisper base model + prompt pipeline (iOS PR #55)**
- R6. The transcription model bumps tiny→base at full precision — multilingual `ggml-base`, matching iOS's shipped WhisperKit variant `base` (`openai_whisper-base`) at the pin, not English-only `base.en`. Both ggml files are ~148MB, and multilingual keeps non-English speech transcribing on both platforms. Quality parity is tier-level (whisper.cpp output is not transcript-identical to WhisperKit's CoreML base). No quantized variant at ship, subject to the R14 low-RAM validation gate.
- R7. Model delivery switches from bundled APK asset to on-demand download with a progress surface and variant-keyed storage, mirroring iOS semantics: installs that predate the variant key (bundled-tiny installs) route through a fresh base download rather than silently staying on tiny; the stale tiny copy in app storage is cleaned up. The download starts on first launch of v1.3.0 (fresh install or upgrade), constrained to unmetered networks by default with an explicit user affordance to proceed over cellular; download size and progress are surfaced before and during the transfer.
- R8. When a walk finishes with recordings before the base model is available (fresh install, offline, download in flight), recordings persist and transcription queues until the model lands — iOS semantics, parity spec authoritative. The pending-transcription UI distinguishes a "waiting on model download" substate (network-needed indicator or download progress) from the ordinary "queued for processing" substate; this surface is Android-original, so the parity spec cannot supply it.
- R9. The prompt-pipeline upgrade ports onto the existing `core/prompt` subsystem: shared standard preamble + walk-character preamble note, attend-to directives pointing at this walk's patterns, practice lexicon teaching wander/seek, response contract closing every prompt, and pauses/elevation/light-crossings reaching the prompt context.

**Phase 18 — SVG map glyphs (iOS PRs #56 + #57)**
- R10. Port the eight vector masters from the iOS repo's assets and rasterize them for Mapbox style images (the Android analogue of `MapGlyphImageBuilder`). Spec sources: iOS `docs/brainstorms/2026-07-21-svg-map-glyphs-requirements.md` + `docs/plans/2026-07-21-001-feat-svg-map-glyphs-plan.md` (whose R8 already pins the masters to the flat-vector SVG subset importable by Android Vector Asset Studio), cross-checked against shipped code wherever review fixes diverged.
- R11. Glyph surfaces match iOS at the pin: map whispers and cairns render the vector art, the add-a-stone sheet shows what the stone makes, cairn detail and whisper mood rows carry the art — including the #57 size doubling and the one-threshold-table/visible-tiers review fixes.

**Acceptance and verification**
- R12. Every stage gets an `/ios-parity port` spec with Swift quotes pinned to `9a418e4` before implementation.
- R13. Unit tests per house rules, including the platform-object builder Robolectric rule — the Phase 17 download surface (WorkRequest/notification construction) is expected to hit this.
- R14. One consolidated on-device QA pass (OnePlus 13) per phase. Phase 17's pass additionally exercises the fresh-install offline path (airplane-mode walk with recordings, then network restore), the v1.2.0→v1.3.0 upgrade path, and repeats the transcription-envelope check on a low-RAM device class near the minSdk floor — a flagship-only pass cannot falsify the full-precision-base decision for the devices most likely to break it.

**Release**
- R15. Single Android release: v1.3.0 containing all three phases. No intermediate per-feature production releases. Android versioning stays independent of iOS's 1.9.0.

---

## Acceptance Examples

- AE1. **Covers R7, R8.** Given a fresh v1.3.0 install in airplane mode, when the user finishes a walk with voice recordings, the recordings persist with transcription pending; when the network returns and the base model download completes, the recordings transcribe without user action.
- AE2. **Covers R7.** Given a device upgrading from v1.2.0 (bundled tiny installed into app storage), when v1.3.0 first runs, the app routes through a fresh base download, removes the stale tiny copy, and no walk or recording data is lost.
- AE3. **Covers R2.** Given iOS tags v1.9.0 on a build-bump commit after `9a418e4`, when the delta is re-diffed, it folds in and the anchor re-pins without reopening this doc. Had the delta been a new headline feature, it would go to the user for re-triage.
- AE4. **Covers R5.** Given the route catalog fetch fails on launch day, when the daily route is requested, it resolves from the bundled catalog and the feature degrades invisibly.

---

## Success Criteria

- Android v1.3.0's route rotation, base-model transcription, and map glyphs match their R12 parity specs (Swift quotes pinned at `9a418e4`), confirmed by the per-phase device QA passes.
- `CLAUDE.md` and the `ios-parity` skill name `9a418e4` as the frozen parity target; nothing still points at `c1745e8` as current.
- The APK sheds the bundled 78MB model and transcription quality steps up to base.
- `ce-plan` can start Phase 16 from this doc plus the iOS plan docs without inventing scope, sequencing, or acceptance criteria.

---

## Scope Boundaries

- Anything iOS ships after `9a418e4`, except deltas folded in via R2.
- iOS-only chores inside the range: pbxproj/xcscheme changes, build-number bumps, iOS screenshot-seeder updates.
- Quantized model variants — passed over in favor of full-precision base, conditional on the R14 low-RAM validation passing: if full-precision base misses the performance envelope there, quantized base (`ggml-base-q5_1`/`q8_0`) is the pre-agreed contingency rather than a re-brainstorm.
- Keeping a bundled tiny model as an offline fallback — the download path replaces bundling wholesale.
- WhisperKit/CoreML mechanics — Android stays on whisper.cpp via JNI; parity is behavioral, not architectural.

---

## Key Decisions

- **Download-over-bundle for the base model**: user choice. APK shrinks ~78MB and Android matches iOS's delivery and upgrade semantics (variant-keyed, progress-surfaced), in exchange for building a download/failure surface and accepting that a fresh offline install can't transcribe until the ~148MB download lands — a step down from today's works-offline-out-of-the-box, but identical to iOS.
- **Full-precision multilingual base over quantized**: matches iOS's shipped model tier (WhisperKit `base`, multilingual — parity is tier-level, not transcript-identical); halving the download wasn't worth a quality divergence. If the R14 low-RAM validation fails, quantized base is the pre-agreed contingency.
- **Single v1.3.0 release**: one QA and rollout cycle; users get the whole 1.9.0 experience at once (v1.2.0 precedent).
- **End-state port over commit replay**: established pattern from the Seek Mode retarget — port what ships at the pin.
- **iOS landing order as phase order (16 → 17 → 18)**: the three features are mutually independent; mirroring iOS order keeps spec traceability. Planning may parallelize or reorder if useful.
- **Pin now at the main tip rather than await the v1.9.0 tag**: precedent from the 2026-07-14 retarget (split-pin declined); R2 absorbs any tag-time churn.

---

## Dependencies / Assumptions

- The iOS shipped Swift at `9a418e4` is authoritative where it diverges from the iOS plan docs.
- The route-catalog artifact iOS reads — `https://cdn.pilgrimapp.org/collective/routes.json` (iOS `Config.Collective.routeCatalogURL`) — is live before Android v1.3.0 ships; iOS v1.9.0 itself ships against it, and the walk-worker counter contract is unchanged by PR #53 (the contribution ledger is device-local). Planning verifies by fetching that URL rather than assuming worker-deploy state.
- A stable HTTPS source for `ggml-base.en` exists (host selection deferred to planning); iOS gets its download flow from WhisperKit, so Android needs an explicit source.
- Android's existing download infrastructure (voice-guide pack pipeline: WorkManager + progress + variant-keyed storage) is a reusable pattern for the model download.
- SVG masters and the route catalog are project assets under the same license and can be copied across repos (same basis as the seek audio reuse).
- Android v1.2.0's staged rollout (20%) completes/promotes independently of this work.

---

## Outstanding Questions

### Deferred to Planning

- [Affects R7][Technical] Model download host and integrity story (URL, checksum, resumability) — pilgrimapp.org mirror vs upstream whisper.cpp model host.
- [Affects R7][Technical] Whether the model download reuses the voice-guide WorkManager pipeline or gets a dedicated path.
- [Affects R6][Needs research] Base-model performance envelope via whisper.cpp on device — tiny did 3 recordings in 12s on the OnePlus 13; define the acceptance envelope as latency plus peak memory, measured on both the flagship and a low-RAM device class near the minSdk floor (R14), and confirm base doesn't starve the walk pipeline. If it misses, quantized base is the pre-agreed contingency (see Scope Boundaries).
- [Affects R10][Technical] Android vector asset format for the eight masters (VectorDrawable port vs parsing the SVG path data directly) and rasterization sizes for Mapbox style images.
- [Affects R3][Technical] Where the route catalog cache lives (Room vs files vs DataStore) — follow the iOS end-state shape at planning time.
