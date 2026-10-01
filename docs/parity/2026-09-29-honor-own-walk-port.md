# Parity Spec: Honor, the own-walk slice

| field | value |
|---|---|
| **iOS pin** | `v2.0.0` = `7c200bf` |
| **Android HEAD** | `5ea4029b` |
| **Generated** | 2026-09-29 |
| **Type** | port |
| **Generator** | ios-parity skill: seven topic readers in parallel, each applying all four lenses (behavior, UI and visual, data, edge cases) to one cluster, because the slice spans about 50 iOS files (some 17,000 lines of Swift) |
| **Plan** | `docs/plans/2026-09-29-001-feat-honor-groundwork-own-shared-walks-plan.md` (U11; feeds U13–U23) |
| **Checked** | All 736 cited code blocks were machine-checked: every quoted line appears at its cited lines in the pinned tree. |

This spec outranks the plan wherever they disagree (the plan's authority order). Every iOS claim carries a Swift quote pinned to `7c200bf`; Android claims are pinned to `5ea4029b`. Inside a cluster, "§6" means that cluster's `### 6.` section; "A §6" means section 6 of cluster A.

| Section | Covers | Feeds |
|---|---|---|
| [A. Way model, geometry, builder, store, persistence](#a-way-model-geometry-own-walk-builder-store-and-persistence) | `Way`, `WayGeometry`, `OwnWalkWayBuilder`, `WayStore`, the vocabulary, checkpoint, walk-end save, recovery, deletes | U13, U14, U17 |
| [B. The Honor engine](#b-the-honor-engine) | location feed, state machine, tuning, clocks, arrival, every distance call site, heading | U15, U16 |
| [C. Audio](#c-audio-the-way-voice-the-priority-queue-and-the-other-voices) | the Way voice, the priority rules, whispers, the duck, rates, replies, heard, failures, session, haptics | U18 |
| [D. The walk with Honor](#d-the-walk-with-honor-events-cards-replies-session-and-the-glance) | events, the card queue, replies, the lifecycle, checkpoint, the glance, preferences | U17, U22 |
| [E. On the walk](#e-on-the-walk-map-layers-place-cards-captions) | ghost line, companion, pins, layer order, camera, place card, chip, arrival card, captions, photo viewer, accessibility | U22 |
| [F. Doors and the overview](#f-doors-into-honor-and-the-overview) | the two doors, the mode slot, the Ways sheet, the picker, the overview, Begin | U21 |
| [G. After the walk](#g-after-the-walk-summary-journal-scenery-seal-milestones-prompts) | summary, summary map, journal, scenery, the seal watermark, milestones, the prompt lexicon | U23 |

---

## Resolutions

### Corrections to the plan

The plan was written before these reads. Where it disagrees with iOS, iOS as shipped wins; each item points to its evidence.

1. **Begin only navigates; the walk starts at Start** (F §12, D §3). iOS builds the Way when the walker picks a walk (the picker row or "walk this again"). The overview's Begin parks it and opens the walk screen in its pre-walk state with `mode: .honor`. `HONOR_MODE` and the engine start at the walk screen's Start tap, and no uuid is minted before it. The plan's Begin use case (mint the uuid, stage the Way, start through the existing chain) therefore runs at Start on an Honor walk. Android already has this two-step shape.
2. **The walk-end save overwrites** (A §20). On every clean Finish, iOS rewrites the Way's `way.json` with this honoring's build and keeps only the first `accepted.json`. The plan's "promote by writing the listed Way only if absent" is superseded: overwrite `way.json`, keep the first acceptance. [pilgrim-ios #107](https://github.com/walktalkmeditate/pilgrim-ios/issues/107) asks iOS about the effect on earlier walks.
3. **Replies on a first honoring are lost** (A §21, D §7.3). iOS files a reply into the Way's folder, which an own walk only gets at the end of its first honoring, so the write fails silently. Replies file only into an existing listed Way folder, never into staging. [pilgrim-ios #98](https://github.com/walktalkmeditate/pilgrim-ios/issues/98).
4. **Recovery** (A §22, D §9.2). iOS never resumes a killed walk: it saves it as finished and links the Way only if the Way is already in the store, with no arrival numbers. The plan's first-honoring case is right. Add the repeat case: a crashed repeat honoring links to the previous build. [pilgrim-ios #107](https://github.com/walktalkmeditate/pilgrim-ios/issues/107).
5. **Only two Honor events** (D §2). `HONOR_MODE` at Start, right after the status flips and the start date is stamped. `HONOR_ARRIVAL` at arrival, then the reserved waypoint (`signpost.right.fill`, "Walked their way: %@"), then the card, then the haptic. Neither carries a payload. The arrival numbers reach disk only in the finish-time link.
6. **Geometry uses three distance functions** (A §6, §8; B §16). Haversine at 6,371,000 m for lengths only. Nearest-point projection (anchor, tracking, re-acquire) is an equirectangular plane: 111,320 m per degree, longitude scaled by the fix's latitude. Moment radii (42 and 60 m), the voice drop (300 m) and arrival (30 m) use Apple's undocumented `CLLocation.distance`, which U16 must pin. Android's Seek precedent for that call is haversine (`GeoDistance.kt:9-19@5ea4029b`).
7. **The builder places moments by time** (A §9–§11). Each moment's fraction comes from the full-resolution route sample nearest its time, not from projecting its coordinate onto the line. Photos and waypoints keep their own coordinates. Rounding is half away from zero (`roundToInt()`, never `kotlin.math.round()`).
8. **Arrival on a short loop** (B §11.3). There is no ends-coincide check. On a loop or out-and-back of about 300 m or less, the 300 m tracking window reaches the closing leg at Begin, and arrival can fire within three fixes. AE2 holds on iOS only for loops longer than the window. U15 and U16 use a loop over 300 m for AE2 and match iOS below it. [pilgrim-ios #100](https://github.com/walktalkmeditate/pilgrim-ios/issues/100).
9. **The engine clock includes sittings** (B §7, D §3.5). It is elapsed time minus pauses. Android's `WalkStats.activeWalkingMillis` also subtracts meditation, so it must not feed the engine.
10. **Two engine qualifiers** (B resolutions). Begin re-anchors on "the first on-Way fix *within 300 m of the start*"; a later join goes through the 120 s re-acquire. "A waiting voice is dropped 300 m past its spot unless stationary" also needs "and no unpaused voice is playing". A fix with bad accuracy never reaches the arrival debounce in Honor, because the engine drops it first.
11. **No central arbiter on iOS** (C §2). Each lower player checks the higher ones when it starts. U18's arbiter is Android's cross-process form of the same rules:
    - one parked Way voice behind a guide prompt, the newest winning;
    - one parked whisper behind a voice or a prompt, the newest winning, with no expiry and no distance check;
    - a prompt pauses a playing voice before its first sound, stops an audible whisper and drops a parked one;
    - a voice stops an audible whisper and keeps a parked one;
    - when a prompt ends, the Way voice is released before the whisper.
12. **The Way voice's duck** (C §6). The absolute `voiceGuideDuckLevel`, default 0.15, ramped over 0.5 s. It is applied when a voice starts (never while parked), held through every pause, and restored with a 0.5 s ramp on every exit. Android's current 0.3 × user volume with no ramp is not parity for the Way voice. A soundscape started mid-voice is not ducked on iOS, so U18's "starts ducked" test flips to match. [pilgrim-ios #104](https://github.com/walktalkmeditate/pilgrim-ios/issues/104).
13. **Haptics are not tied to playback** (C §13). Four Honor events vibrate:
    - a reached non-voice moment (light impact);
    - the soft tap (soft impact);
    - water ahead;
    - arrival (Seek's three rising taps).

    None waits for a voice, so the Audio constraint "haptics fire only after playback has actually started" has nothing to attach to in Honor. iOS drops them outside the foreground; Android fires them screen-off (R6, already chosen).
14. **"Heard" is marked at hand-off** (C §9). A voice counts as heard when it's handed to the player, before any sound, even if it then fails. The flag lives in memory only. [pilgrim-ios #106](https://github.com/walktalkmeditate/pilgrim-ios/issues/106).
15. **The card queue** (D §5, E §7–§8). Reached non-voice moments append. A starting voice and a tapped pin move to the front. There's no maximum. The indicator is up to four 5 pt stone dots, read "N more waiting", not "+N more". Only a voice card retires on its own: 20 s after its voice ended naturally or failed to play, and only if untouched. Skipped, dropped, replaced and reply-interrupted voice cards never retire, and non-voice cards never do.
16. **The listening chip has only pause/resume and skip** (E §10). Replay and rate live on the card.
17. **Doors** (F §2–§5). There are two:
    - The Path tab's slot button reads "Honor", not "Choose a way" (renamed in iOS `3e9e67d`). It opens "Choose a way", then "Walk one of yours again", then the "Walk again" picker.
    - "walk this again" on the post-walk and journal summaries, shown when the route has at least two points.

    The picker lists walks whose stored distance is above zero, newest first. A walk it can't use fails on tap into "Can't walk this one again". Begin is not disabled until a Way is chosen, and no "coming soon" state is left on iOS; on Android, "coming soon" survives only as the flag-off state (AE12).
18. **The overview's framing** (F §9). The card's measured height is the map's bottom inset. Padding is 40 top, 30 left and right, and `40 + min(cardHeight, mapHeight − 240)` at the bottom. The ease takes 0.4 s. `maxZoom` is nil, rotate and pitch are off, and the camera never follows.
19. **The seal watermark covers every walk** (G §6). Every seal whose walk has two or more route samples draws the walk's line. An honor seal also draws the Way's line beneath it.
    - The two lines share one fit in raw degrees.
    - They turn with the seal's rotation.
    - The stroke is `canvasSize / 512` in the seal's ink.
    - The Way line is at 0.03 alpha, the walk line at 0.055.
20. **Scenery rank** (G §5). Staffs rank directly *below* the Seek cairn, not level with it. Gates outrank both, so the first Honor arrival (and the 10th/25th/50th/100th) shows a torii.
21. **One prompt form** (G §8). iOS has one Honor lexicon for own and shared walks, not two.
22. **UI-started whispers** (C §5.1). iOS does not queue the placement-sheet preview, and does queue the placement-confirmation play. See the placement table below.
23. **The summary map adds only the ghost line** (G §3): no companion and no Way pins. The arrival waypoint draws as `signpost.right.fill` at 18 pt in stone; Android currently falls back to a map pin.

### By unit

**U13 — Way model, geometry, own-walk builder** (A §1–§11, §24)
- Fields, Codable keys and the `way.json` wire format: A §1–§2 (sorted keys, nil omitted, whole-second ISO 8601 dates, associated-value enums as one-key objects with `_0`).
- Every `WayGeometry` function with its edge cases: A §3–§7. There's no simplification in the own-walk path, only a stride sample.
- Builder inputs and filters, ids (`voice-n`, `photo-n`, `waypoint-n`, `rest-n`, `sit-n`, 1-based per kind in time order), Way id `walk:<UUID>`, and moment order (fraction, then id): A §9–§11.
  - Transcripts are trimmed and capped at 600.
  - Seek and Honor arrival waypoints are excluded.
  - Pauses of 180 s or more become rests.
  - A walk with fewer than 2 samples, a route under 20 m, or no uuid gives no Way.
- Fixture: capture an own-walk `way.json` from an iOS build (the U16 harness), rather than writing one by hand (A open question 3).

**U14 — mode, vocabulary, Ways store, session tables** (A §12–§18, §23, §26; B §2.2)
- Vocabulary: icon `signpost.right.fill`; label "Walked their way: %@"; display names "Honor" and "Way walked"; raw values 5 and 6; wire names `honorMode` and `honorArrival`. `WalkMode` raw values `wander`, `honor`, `seek`, never persisted.
- Store layout: `way.json`, `accepted.json` (first only), `replies.json`, and `media/` (shared walks only). Own walks have no media folder. The plan's per-walk link files replace iOS's single `index.json` and so avoid one of its defects ([pilgrim-ios #107](https://github.com/walktalkmeditate/pilgrim-ios/issues/107)).
- Removing a walk never touches the Ways store. Deleting a Way removes every link to it. This confirms U14's keep-the-links rule.
- The session row should cover the engine state in B §2.2.
- Preferences: `honorVoicesEnabled` (default true) and `honorSoftTapEnabled` (default false, with no writer in the app, so the soft tap is dark; [pilgrim-ios #109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109)).

**U15 — engine, moment tracker, arrival debounce** (B)
- The state machine and the order in which one fix is processed: B §2–§3. All 21 tuning constants with their comparison operators: B §15.1. Every other literal: B §15.2.
- Location filter: a fix passes when accuracy is under 100 m and within an adaptive threshold, 10 or 20 m in practice. The engine also drops anything outside 0–50 m. Its first fix is usually the replayed pre-Begin fix (B §1).
- Clocks: the 120 s re-acquire, its 10 s retry and the 120 s soft tap use wall time, evaluated only when a fix arrives (B §13).
- Pause: iOS has no reachable pause, so Android follows the engine code (B §14). Fixes are still processed, voices wait, the wall-clock timers run, and paused time is excluded from the engine clock. That agrees with the plan's "the companion freezes while paused" test.
- Sitting: the voice pauses and queues, while the clock and the companion keep running (B §14.4).

**U16 — golden traces** (B §16, resolutions 3–4, 7)
- All 14 distance call sites are in B §16.1. The four that call `CLLocation.distance` must be pinned; the harness should log that call next to `WayGeometry.distanceMeters` over the corpus (B open question 1).
- Drive `now()` from the trace on both sides, record clock ticks relative to fixes, and feed the engine directly: the location filter's differences don't touch the goldens.

**U17 — the Honor session, Begin, finalize** (A §19–§23, D §2–§4, §8–§10)
- The lifecycle in order at Start, pause, meditation, Finish and discard: D §3 and §8. Discard writes nothing to the Ways store.
- The checkpoint fields are `wayId`, and `honorProgressFrac` plus `honorArrived` as a pair once joined. The arrival numbers are never checkpointed (A §19).
- The glance: three fields, exact strings, update cadence and staleness, in D §10.
  - "their way, walked"
  - "off the way"
  - "almost there" under 100 m
  - "2 km+ to go" or "1.2 mi+ to go"
  - "~X.X km to go", "~X.X mi to go" and "~N m to go"
  - Not localized, POSIX number format.

**U18 — audio arbitration and the UI gates** (C)
- Priority mechanics, prompt mid-voice, whisper parking, the duck, rates (`[1, 1.25, 1.5, 2]`, labelled "1x", "1.25x", "1.5x", "2x"), `playReply`, "heard", failures, the session, lifecycle and haptics: C resolutions 1–11.
- The rate carry-over is matched as shipped ([pilgrim-ios #105](https://github.com/walktalkmeditate/pilgrim-ios/issues/105)): the Way voice player keeps its rate for the life of its process, while each walk's button starts at 1x. This supersedes the per-walk reading in C resolution 5 and E resolution 18.
- Audio session: `.playback`, mode `.default`, `[.mixWithOthers]`. It's activated at a voice's start and kept through pauses. iOS registers no interruption or route-change handling for the Way voice ([pilgrim-ios #102](https://github.com/walktalkmeditate/pilgrim-ios/issues/102)).
- `playReply` stops the active voice without telling the engine and plays the reply through the same player at full voice-guide volume. The reply is neither the active voice nor heard, and when it ends the engine gets its turn back (C §8).

**U21 — doors and the overview** (F)
- Doors, the mode slot, the Ways sheet and the picker: F §2–§7. The overview's layout tree, strings, framing and states: F §8–§11. The Begin flow: F §12. What blocks Begin: F §13.
- Only an in-flight shared import disables Begin. Distance never does. Location denied gives "Location Required".
- There's no Honor-specific intention step.
- Accessibility labels to pin: F resolution 7 and §17.
- Existing Android drift in the same slot, fixed in U21: the unselected mode label's alpha is 0.3 where iOS uses 0.55, and the start button lacks its "Begin your journey" description.

**U22 — on-walk UI and map layers** (E, D §5)
- The ghost line (E §2): width 4, round cap and join, colour by span (dawn, rust, moss), opacity 0.22 on the light map and 0.4 on the dark, under the route casing.
- The companion (E §3): radius 6, white 1.5 stroke, `#8A8175` at 0.6 on light or `#D9CFBF` at 0.85 on dark, directly above the route line, moving in jumps at most every 2 s. Its position freezes only in a pause.
- Pins, all 22 pt (E §4). A voice pin is fog until heard, then stone.
- Install order (E §5). iOS anchors the companion on `pilgrim-route-layer`, which Android calls `pilgrim-route-line`.
- The camera: a card header tap flies there at zoom 16 in 0.4 s (E §6).
- The place card, the listening chip, the arrival card and the heading tick: E §7–§11 and B §17. The tick is UI-only and uses the device compass with a 3° filter.
- The captions: the stats sheet's "Remaining", "off the way · N m", and the meditation screen's "they sat here N minutes" (E §10, §12).
- The photo viewer (E §13). Accessibility for every surface is tabulated in E §14.

**U23 — summary, journal, scenery, seal, milestones, prompts** (G)
- The summary section's strings and conditions: G §2. "in their steps", the Way title or "a way that has been removed", the delta line, and "N voice(s) along the way · N reply/replies". The voice count covers all of the Way's voices, not just the heard ones.
- The summary map (G §3), the journal (G §4), scenery (G §5), the seal (G §6), milestones (G §7: "First Honor" and "N Ways Walked" at 10/25/50/100, counted from arrival waypoints), and the lexicon (G §8, quoted verbatim).
- Honor is recognised by the `honorMode` event (G §1). iOS has no "Honor unavailable" state, so Android's flag-off rendering is its own (AE12).

### The plan's placement table, row by row

| Row | Verdict | Notes |
|---|---|---|
| Honor engine, moment tracker (`:tracker`) | Holds | Same filtered location stream as Seek (B §1.1); the engine clock is elapsed minus pauses, with sittings included (B §7); heading is not an engine input. |
| Way voice, whisper hold, duck, arbiter, haptics (`:tracker`) | Holds | iOS keeps all three players and the duck in one process; the arbiter reproduces the rules in corrections 11 and 12. Honor haptics aren't tied to playback (correction 13). |
| Voice guide (UI, prompt gate) | Holds, one detail | iOS pauses a playing Way voice before the prompt's first sound, so they never overlap (C §3). The UI should publish the prompt gate before starting the prompt's player; the plan's accepted same-instant race stays as it is. |
| Recorder (UI, reply mapping) | Holds, one detail | A reply files only into an existing listed Way folder (correction 3). "reply here" during an ordinary recording re-targets that recording on iOS ([pilgrim-ios #99](https://github.com/walktalkmeditate/pilgrim-ios/issues/99)). |
| Arrival (`:tracker`, controller mutex) | Holds | The order is event, waypoint, card, haptic. The numbers are persisted only at finish, in the link (D §2.2). |
| Finalize step | Corrected | Overwrite `way.json` on each clean honoring and keep the first acceptance (correction 2). Recovery links only an already-saved Way, with no numbers (correction 4). |
| Notification glance (`:tracker`) | Holds | Strings and cadence in D §10. |
| Begin (UI use case) | Corrected | It runs at the walk screen's Start in Honor mode, not at the overview's Begin (correction 1). |
| Map layers, cards, chip, arrival card, card compass, summary (UI) | Holds | The card compass is the device compass (B §17). |
| Chip and card commands (UI → `:tracker`) | Holds | The chip carries only pause/resume and skip (correction 16). |
| "Your reply" | Holds | Played by the Way voice player; neither active nor heard (C §8). |
| UI-started whispers | Corrected | The placement-sheet preview is not queued; the placement-confirmation play is (correction 22). |
| Media gathering, sweeps | Holds | Own walks gather no media, and the sweep never touches them (A §15). |
| Seek, flag on (U25) | Holds | iOS feeds Seek the same filtered stream as Honor (B §1.1), so U25's dated divergence for Seek's own subscription stands. |

---

## Matched as shipped, and filed upstream

These are iOS defects. Android ports each one exactly as iOS ships it, the parity gate reads it as `match`, and a fix that lands in iOS before the gate folds in. Each linked issue groups the related defects with its evidence. "Cluster refs" are each section's own defect numbers.

| Issue | What | Cluster refs |
|---|---|---|
| [pilgrim-ios #98](https://github.com/walktalkmeditate/pilgrim-ios/issues/98) | A reply on the first honoring of your own walk is silently lost | A-D1, D-1 |
| [pilgrim-ios #99](https://github.com/walktalkmeditate/pilgrim-ios/issues/99) | Reply bookkeeping drifts: keys shift after a recording is deleted; a failed take leaves the origin armed; "reply here" re-targets an ongoing recording; the summary counts every walk's replies; "your reply" has no on-screen control | A-D3, D-4, D-3, E-10, A-D6, G-D1, E-9 |
| [pilgrim-ios #100](https://github.com/walktalkmeditate/pilgrim-ios/issues/100) | A loop or out-and-back of about 300 m or less can arrive at Begin | B-1 |
| [pilgrim-ios #101](https://github.com/walktalkmeditate/pilgrim-ios/issues/101) | A Way voice can start or resume inside a closed gate | C-D1, B-2, C-D6, B-3, C-D3, D-5 |
| [pilgrim-ios #102](https://github.com/walktalkmeditate/pilgrim-ios/issues/102) | Nothing resumes the Way voice, the guide or a whisper after a phone call | C-D8 |
| [pilgrim-ios #103](https://github.com/walktalkmeditate/pilgrim-ios/issues/103) | A parked whisper plays after Finish; the guide's restore may swell the fading soundscape | C-D2, C-D12 |
| [pilgrim-ios #104](https://github.com/walktalkmeditate/pilgrim-ios/issues/104) | A soundscape started mid-voice isn't ducked and is restored to a stale level; the Way voice's volume controls hide under the voice guide | C-D4, C-D10 |
| [pilgrim-ios #105](https://github.com/walktalkmeditate/pilgrim-ios/issues/105) | The Way voice's rate carries into the next walk while the button reads 1x | B-7, C-D5, D-2, E-8 |
| [pilgrim-ios #106](https://github.com/walktalkmeditate/pilgrim-ios/issues/106) | "Heard" counts voices that never played; skipped or dropped voice cards never retire; a failed card can cover the next | C-D7, E-12, B-6, E-11, C-D9 |
| [pilgrim-ios #107](https://github.com/walktalkmeditate/pilgrim-ios/issues/107) | Links after crashes and repeat honorings: first honorings never linked, repeat honorings linked to the previous build, each honoring rewrites the stored Way, one bad index read erases every link | A §22, D §9.2, A-D7, A-D5, A-D8 |
| [pilgrim-ios #108](https://github.com/walktalkmeditate/pilgrim-ios/issues/108) | Accessibility: the card header's label hides its content; pins and previews are unreachable; the journal doesn't name the mode; the chip's buttons are glyph-sized; "Back to where you are" on the wrong card | E-3, F-6, G-D5, E-14, E-4 |
| [pilgrim-ios #109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109) | Copy and units: the off-the-way caption is always metres; "1 minutes" and "0 minutes"; own-walk copy speaks of another walker; the lock screen says "off the way" on the approach; the voices toggle shows on while sounds are off; the overview's temperature ignores units; Settings → Ways says own-walk voices returned to the trail; the soft tap has no switch | E-5, E-6, G-D2, D-6, F-3, F-4, A-D4, A-D2 |
| [pilgrim-ios #110](https://github.com/walktalkmeditate/pilgrim-ios/issues/110) | Doors that lead nowhere: "walk this again" on a route under 20 m; the picker lists archived walks; a failed link's error on an own-walk overview; the preview's hour zone | F-1, F-2, F-7, F-8 |
| [pilgrim-ios #111](https://github.com/walktalkmeditate/pilgrim-ios/issues/111) | On the map: the companion under the route after a theme switch; the fly-to may fight follow; the tick spins the long way; the overview card covers the Mapbox logo; the summary map clips the ghost; a deleted Way's line stays on the seal | E-1, E-2, B-5, E-7, F-5, G-D4, G-D3 |
| [pilgrim-ios #112](https://github.com/walktalkmeditate/pilgrim-ios/issues/112) | Smaller: the latent pause clock; the photo viewer's zoom and pan; the journal gate and goshuin disagreeing on ties; two comments that disagree with the code | B-4, E-13, G-D6, C-D11 |

The two stage-only defects (E-15, E-16) are left for the Stage 21-2 spec.

---

## Owner decisions (decided 2026-09-30)

None of these has iOS behavior to match, or iOS's behavior can't carry over to Android's architecture. On 2026-09-30 the owner accepted every recommendation. Each is a dated Android decision for the parity gate, and the column after each says where it's built.

1. **Pause is an Android-only state** (B §14, D §8.1, E resolution 14). iOS has no reachable pause at the pin.
   - The engine clock and the companion freeze for the whole pause, including the pause in progress. That's the engine's own intent and the plan's test. *Built:* U17 (#253).
   - "Sit?" does nothing while paused, because Android's reducer already ignores a sitting start then. *Lands:* U22's card.
2. **Headphones unplugged, and resuming after a call** (C §11, open questions). iOS has neither. Android keeps its platform handlers as R6 platform equivalents:
   - pause on becoming-noisy;
   - pause on a transient focus loss, then resume;
   - stop on a permanent loss.

   *Built:* U18 (#259). [pilgrim-ios #102](https://github.com/walktalkmeditate/pilgrim-ios/issues/102) asks iOS to resume after a call.
3. **A deleted Way's line on the seal** (G §6, open questions). iOS caches the seal image forever, so the line stays. Android draws seals live, so the line goes when the link does. That's a rendering-architecture difference. *Lands:* U23. [pilgrim-ios #111](https://github.com/walktalkmeditate/pilgrim-ios/issues/111) asks whether the frozen line is intended.
4. **Android-only data gaps** (A open questions 1–2). Android photos can lack a capture time or coordinates, and Android waypoints can lack a label or icon; iOS never has these gaps. *Built:* U13 (#249).
   - A photo with no capture time is skipped.
   - A photo with no coordinates sits at the route sample nearest its time.
   - A waypoint with no icon draws `mappin`, iOS's own fallback.
   - A waypoint with no label shows no kicker.
5. **Android-only hosts and copy** (F open questions). *Lands:* U21, and U23 for the summary door.
   - The widget-opened summary shows "walk this again", following iOS's rule that "hosts that can present the overview show the door".
   - The two Begin refusals, which iOS has no string for, use this copy. The title is iOS's picker alert, so there's one voice for "you can't walk this":

   | Refusal | Title | Body |
   |---|---|---|
   | Staging the Way failed | Can't walk this one again | Pilgrim couldn't get this walk ready to follow. Try again. |
   | The Way (or its source walk) went away while the overview was open | Can't walk this one again | This walk isn't here to follow anymore. Try another. |
6. **The sitting GPS tier** (B open question 3). iOS thins fixes while sitting (100 m accuracy, 50 m filter), which delays arrival and voice drops for a walker who sits. Android keeps full-rate fixes. *Nothing to build:* a gate row records the difference.
7. **Layer order after a theme switch** (E §5). iOS reinstalls the Honor layers before the route on a style reload, so the companion lands under the route until the next flush. Android keeps its standing rule of reinstalling runtime layers after the annotation managers, which restores iOS's first-appearance stack. *Lands:* U22. The glitch is filed as [pilgrim-ios #111](https://github.com/walktalkmeditate/pilgrim-ios/issues/111).
8. **Follow versus fly-to** (E open question 2). *Still open.* It's decided at U22, once iOS's behavior is checked on an iPhone.
9. **The Mapbox logo and attribution on the overview** (F §9). On iOS the overview card covers them, but Mapbox's terms require them to stay visible on the map view. Android keeps them visible above the card. *Lands:* U21. [pilgrim-ios #111](https://github.com/walktalkmeditate/pilgrim-ios/issues/111) asks iOS to lift them.
10. **A failed link write is retried** (added from U17, #253). iOS drops the error (`try?`), so a walk whose link write fails reads "a way that has been removed". Android keeps the walk's live rows and retries the link at the next launch. That's invisible when nothing fails. *Built:* U17 (#253).

## Open questions iOS leaves open

- **What `CLLocation.distance` computes: resolved in U16 (#252).** It uses WGS84 radii of curvature at the pair's mean latitude, with longitudes moved into 0–360°. It caches the radii while the first point's latitude stays within 0.005°, so iOS's own result shifts slightly with call history (about 3 cm at 300 m). Android pins the history-free value, `wgs84MidLatitudeMeters`. That's within 3.9e-16 of Apple's fresh value, and within 7.5 mm of what iOS's engine received over the golden corpus.
- **Which appearance the ghost's colours resolve to on the dark map** (E and G open questions). Keep the ghost on the same three values Android's route line already uses.
- **The overlap order of two Way pins.** iOS sets no sort key, so there's no rule to match (E open question 3).
- **Two platform facts worth one iPhone check before the gate's audio rows are written** (C open questions). First, that the system pauses an `AVAudioPlayer` on a call even under `.mixWithOthers`. Second, that it doesn't pause on a route loss.

## Gate rows recorded during implementation

- **2026-10-01, U21. R6 platform equivalent: the Ways sheet and the picker wear the app's parchment** (F §4.3). iOS sets no background on their `List`, so it shows the system grouped-list colours.
- **2026-10-01, U21. R6 platform equivalent: a denied location permission goes through the walk screen's existing permission flow at Start** (F §12.1, §13.4). That stands in for iOS's "Location Required" alert after Begin.
- **2026-10-01, U21. Drift fixes that ship regardless of the flag** (F §3.6, iOS `cbd24fc`). The unselected mode label is at 0.55, and the start button reads "Begin your journey".
- **2026-10-01, U21. One iPhone check for the final pass: whether the Ways sheet's section headers render in uppercase** (F §4). Android shows them as the source literals are written.
- **2026-10-01, U23. Drift fix that ships regardless of the flag: the walk's own ghost-route line on every seal** (G §6). It closes a Stage 4-A deferral rather than adding an Honor surface; only the Way's line beneath it waits on the flag, with the honor section, staffs, staff glyph, honor milestones, the lexicon, and the arrival signpost on the maps.
- **2026-10-01, U23. R5 window with no iOS counterpart: before the Honor step has run, or after it failed** (G §1, plan U17). The summary section, its ghost line, and the seal's Way line render from the live session row and the Way it names (the staged build on a clean own-walk finish, else the listed Way); the delta joins once the link lands. iOS writes its link before any surface opens.
- **2026-10-01, U23. The journal gate keeps the uuid tie-break the goshuin uses** (G §4, G-D6, pilgrim-ios #112). iOS's journal counts "before" in Core Data fetch order, which leaves walks with the same start date in no defined order; Android's journal already shared the goshuin's order for Seek, and Honor follows it, so the two never disagree.

---

## A. Way model, geometry, own-walk builder, store, and persistence

Pin: `pilgrim-ios` @ `7c200bf`. Android HEAD cited: `5ea4029b`. Scope: the own-walk slice (walking one of your own past walks again). Shared-walk, stage, and offline-map branches are named where own-walk code branches on them, and not specified.

Feeds U13 (model, geometry, builder), U14 (vocabulary, mode, store, delete path), and U17 (the save at walk end, recovery).

Four lenses were applied to every file: behavior (state, transitions, threads, timing), UI/visual (only the user-visible strings this cluster owns), data (entities, keys, file layout, write threads), and edge cases (thresholds, rounding, tricks, boundaries).

### 1. The Way model: types, fields, optionality

`Way` is a plain `Codable` value. Nothing in it is a CoreData entity.

```swift
struct WayCoordinate: Codable, Equatable {
    let lat: Double
    let lon: Double
}

struct WayPoint: Codable, Equatable {
    let lat: Double
    let lon: Double
    let alt: Double?
    /// Seconds since departure. Wall clock: the original walker's pauses
    /// are inside it, so the companion rests where they rested.
    let t: Double
}

enum VoiceKind: String, Codable { case spoken, ambient }

enum WayMedia: Codable, Equatable {
    /// Relative to `Ways/{id}/media/`.
    case file(String)
    /// Own walk: relative to the Documents directory.
    case recording(relativePath: String)
    /// Own walk: PhotoKit asset.
    case photoAsset(localIdentifier: String)
}

enum WayMomentKind: Codable, Equatable {
    case voice(endFrac: Double, duration: Double, kind: VoiceKind, media: WayMedia)
    case photo(media: WayMedia)
    case waypoint(label: String, icon: String)
    case rest(minutes: Int)
    case meditation(minutes: Int, isEstimate: Bool)
}
```
> Pilgrim/Models/Honor/Way.swift:3-34@7c200bf

- `WayPoint.t` is wall-clock seconds since the first route point. Pauses are inside it.
- An own walk uses only `.recording(relativePath:)` for voices and `.photoAsset(localIdentifier:)` for photos. `.file(_)` is the shared-walk case (media under the Way folder).
- `duration` is seconds (`Double`). `minutes` is `Int`.

```swift
struct WayMoment: Codable, Equatable, Identifiable {
    let id: String
    /// Distance fraction along the route: orders moments and gates progress.
    let frac: Double
    /// The true place when the source knows it. Triggers fire on this;
    /// nil falls back to `WayGeometry.coordinate(atFrac:)`.
    let at: WayCoordinate?
    let kind: WayMomentKind
    /// The street or place the sharer's page names for a voice, when the
    /// worker had one. Optional and last: older `way.json` files lack it.
    var place: String?
    /// What they said, when the source transcribed it: the walker's own
    /// recordings carry one; shared walks carry the worker's. Optional and
    /// last, like `place`.
    var transcript: String?
    /// The dataset's description of this place, or a line composed from its
    /// structured fields. Optional and last, like `place`.
    var text: String?
    /// Localized names by language code; the card shows one, in the language
    /// of the place.
    var names: [String: String]?
    /// When the place invites sitting, how long the dataset suggests.
    var sitMinutes: Int?
    /// The waypoint's own coordinate, for the map pin. `at` is its projection
    /// onto the line, so the 60 m trigger fires as the walker passes on the
    /// trail even when the place itself stands well off it.
    var pin: WayCoordinate?
```
> Pilgrim/Models/Honor/Way.swift:36-62@7c200bf

An own-walk builder sets only `id`, `frac`, `at`, `kind`, and (for voices) `transcript`. `place`, `text`, `names`, `sitMinutes`, and `pin` stay nil for own walks (shared and stage fields). Equality is whole-value (`Equatable`); the view model compares moments with `==` (for example `honorCards.contains(moment)`, `activeVoice == moment`).

```swift
    var isVoice: Bool {
        if case .voice = kind { return true }
        return false
    }

    /// The file behind a voice or photo moment; nil for the other kinds.
    var media: WayMedia? {
        switch kind {
        case .voice(_, _, _, let media): return media
        case .photo(let media): return media
        case .waypoint, .rest, .meditation: return nil
        }
    }
```
> Pilgrim/Models/Honor/Way.swift:90-102@7c200bf

```swift
enum WaySource: Codable, Equatable {
    case ownWalk(UUID)
    case share(id: String, pageURL: URL)
    /// One stage of a downloaded pilgrimage route. No page, no expiry: a
    /// route never returns to the trail on its own.
    case pilgrimage(routeId: String, stageIndex: Int)

    /// `PilgrimagePackageManager` writes and rewrites this Way's `way.json`
    /// as packages install and update. Nothing else may save over it: a copy
    /// captured before an Update redrew the stage would be written back on
    /// top of the redrawn one.
    var isPackageOwned: Bool {
        if case .pilgrimage = self { return true }
        return false
    }
}

struct WayWeather: Codable, Equatable {
    let condition: String
    let temperatureC: Double?
}

enum WaySpanKind: String, Codable {
    case meditating, talking
}

/// A stretch of the Way walked in a practice other than walking, by distance
/// fraction. The ghost line colors these the way the walk's own route does.
struct WaySpan: Codable, Equatable {
    let startFrac: Double
    let endFrac: Double
    let kind: WaySpanKind
}

struct Way: Codable, Equatable {
    let id: String
    let source: WaySource
    let title: String
    let departedAt: Date
    let tzIdentifier: String?
    let expires: Date?
    let route: [WayPoint]
    let totalDistanceMeters: Double
    let theirActiveSeconds: Double
    let moments: [WayMoment]
    let weather: WayWeather?
    /// Optional and last so a `way.json` written before spans existed still
    /// decodes (as an all-walking Way) and every call site keeps its shape.
    var spans: [WaySpan]?
    /// Service points, drawn and never triggered. Optional and last, like
    /// `spans`, so a `way.json` written before stages still decodes.
    var marks: [WayMark]?
    /// Present only for a pilgrimage stage.
    var stage: WayStage?

    var voiceCount: Int { moments.filter(\.isVoice).count }
    var photoCount: Int {
        moments.filter { if case .photo = $0.kind { return true } else { return false } }.count
    }

    /// A Way that came from a downloaded route. Honor speaks differently
    /// about one: no other walker, so no companion, no soft tap, no date.
    var isPilgrimageStage: Bool { stage != nil }
}

/// `id` is already the store's folder name, so `.sheet(item:)` can key the
/// overview off the Way itself.
extension Way: Identifiable {}
```
> Pilgrim/Models/Honor/Way.swift:155-222@7c200bf

Field table for `Way` (own-walk values from section 11):

| Field | Swift type | Optional | Own-walk value |
|---|---|---|---|
| `id` | `String` | no | `"walk:" + uuid.uuidString` |
| `source` | `WaySource` | no | `.ownWalk(uuid)` |
| `title` | `String` | no | trimmed walk comment (intention), else medium date |
| `departedAt` | `Date` | no | `walk.startDate` |
| `tzIdentifier` | `String` | yes | `TimeZone.current.identifier` |
| `expires` | `Date` | yes | `nil` (own walks never expire) |
| `route` | `[WayPoint]` | no | full-resolution samples, stride-sampled to 4,000 |
| `totalDistanceMeters` | `Double` | no | full-resolution haversine length |
| `theirActiveSeconds` | `Double` | no | `walk.activeDuration` |
| `moments` | `[WayMoment]` | no | voices, photos, waypoints, rests, sittings |
| `weather` | `WayWeather` | yes | from the walk, when it has a condition |
| `spans` | `[WaySpan]` | yes, `var` | always set (possibly `[]`) |
| `marks` | `[WayMark]` | yes, `var` | nil (stage-only) |
| `stage` | `WayStage` | yes, `var` | nil (stage-only) |

`WayMark`, `WayMarkKind`, `WayStage`, `WayStageHours`, and `WayStagePlace` (`Way.swift:105-153`) are stage-only. Branch point: `isPilgrimageStage` is `stage != nil`; own walks always read false.

Android counterpart: none yet (`P/domain/honor/` holds only `HonorPersistence.kt` at `5ea4029b`). U13 creates `Way.kt`.

### 2. The `way.json` wire format

No type in the Way model declares `CodingKeys` or a custom `init(from:)`. A search of `Pilgrim/Models/Honor` for `CodingKeys` or a Codable extension of these types finds only `TourManifest.swift`. So every key is the Swift property or case name, produced by the compiler's synthesized `Codable`.

The store's encoder and decoder:

```swift
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.sortedKeys]
        return e
    }()
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()
```
> Pilgrim/Models/Honor/WayStore.swift:26-36@7c200bf

Rules that follow from this:

- **Dates** are `.iso8601`: UTC, whole seconds, `Z` suffix (for example `"2026-08-01T07:00:00Z"`). Fractional seconds are dropped on write, and a string with fractional seconds fails `.iso8601` decoding. `departedAt`, `expires`, and `acceptedAt` lose their sub-second part on disk. The store's own comment confirms whole-second precision: "racing `iso8601`'s whole-second precision with real sleeps" (`WayStore.swift:23-24`).
- **Keys are sorted** (`.sortedKeys`). No `.withoutEscapingSlashes`, so `/` is written as `\/`. Both are cosmetic; any JSON reader accepts them. A byte-stable round-trip test across platforms is not achievable; compare decoded values instead.
- **Nil optionals are omitted**, never written as `null` (synthesized `encodeIfPresent`). A missing optional key decodes as nil. iOS pins this for `spans`:

```swift
        let json = try XCTUnwrap(String(data: try encoder.encode(way), encoding: .utf8))
        XCTAssertFalse(json.contains("\"spans\""), "nil spans stay off the wire")
```
> UnitTests/Honor/WayStoreTests.swift:159-160@7c200bf

- **Unknown keys are ignored** by `JSONDecoder` (default). An unknown **enum case or raw value** (for example a new `WaySpanKind`) throws, and `load(id:)` then returns nil for the whole Way (`try?`, section 13).
- **Enums with associated values** use the synthesized form: an object with one key (the case name) whose value is an object of the labeled associated values. An unlabeled associated value is keyed `_0`. iOS's own fixture shows the labeled form for `share` and `waypoint`:

```swift
        let json = """
        {"id":"share:Qoi4YmPHLN",
         "source":{"share":{"id":"Qoi4YmPHLN","pageURL":"https://walk.pilgrimapp.org/Qoi4YmPHLN"}},
         "title":"Rúa do Franco → Obradoiro","departedAt":"2026-08-01T07:00:00Z",
         "expires":"2099-01-01T00:00:00Z",
         "route":[{"lat":42.88,"lon":-8.545,"t":0},{"lat":42.88,"lon":-8.540,"t":400}],
         "totalDistanceMeters":420,"theirActiveSeconds":400,
         "moments":[{"id":"waypoint-1","frac":0.5,"kind":{"waypoint":{"label":"Oak","icon":"leaf"}}}]}
        """
```
> UnitTests/Honor/PilgrimageWayImporterTests.swift:70-78@7c200bf

The own-walk shapes follow the same synthesis (no iOS fixture shows them; U13 should capture one from an iOS build to pin it):

| Swift | JSON |
|---|---|
| `.ownWalk(uuid)` | `{"ownWalk":{"_0":"E621E1F8-C36C-495A-93FC-0C247A3E6E5F"}}` (Swift `UUID` encodes as its uppercase `uuidString`) |
| `.recording(relativePath: p)` | `{"recording":{"relativePath":"Recordings/…/….m4a"}}` |
| `.photoAsset(localIdentifier: s)` | `{"photoAsset":{"localIdentifier":"…"}}` |
| `.file(s)` (shared) | `{"file":{"_0":"audio/1.m4a"}}` |
| `.voice(endFrac:duration:kind:media:)` | `{"voice":{"duration":50,"endFrac":0.47,"kind":"spoken","media":{…}}}` |
| `.photo(media:)` | `{"photo":{"media":{…}}}` |
| `.waypoint(label:icon:)` | `{"waypoint":{"icon":"leaf","label":"Oak"}}` |
| `.rest(minutes:)` | `{"rest":{"minutes":3}}` |
| `.meditation(minutes:isEstimate:)` | `{"meditation":{"isEstimate":false,"minutes":12}}` |
| `VoiceKind` | `"spoken"` / `"ambient"` |
| `WaySpanKind` | `"meditating"` / `"talking"` |

- `WayPoint.alt` nil is omitted (iOS fixture above has no `alt`).
- `Int` fields (`minutes`, and `stageIndex` for stages) are JSON integers. Android should write integers there (a Kotlin `Int`), not rely on a decoder accepting `7.0`.
- Non-finite doubles: `JSONEncoder`'s default strategy throws on NaN or infinity, so such a Way fails to save (and the save's `try?` swallows it, section 20).

**Where `way.json` goes.** Nowhere off the device. The store excludes itself from iCloud backup (section 12), and the `.pilgrim` package code never touches it: a search of `Pilgrim/Models/Data/PilgrimPackage` for `WayStore`, `ways`, or `way.json` finds nothing. iOS compatibility of Android's `way.json` is therefore a fixture convenience (and Stage 21-2's stage files), not a runtime requirement.

Android reuse: kotlinx precedents with `ignoreUnknownKeys = true` and `explicitNulls = false` at `app/src/main/java/org/walktalkmeditate/pilgrim/di/NetworkModule.kt:58-67@5ea4029b`.

### 3. `WayMoment` transcript helpers (the card's one line)

```swift
    /// The first sentence of the transcript, for a card that has one line
    /// to spare. Nil when there is nothing to quote.
    var transcriptLine: String? { WayMoment.firstSentence(of: transcript, maxCharacters: 120) }

    static let maxTranscriptCharacters = 600

    /// Trims, drops the empty, and caps at `maxTranscriptCharacters`.
    static func trimmedTranscript(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(maxTranscriptCharacters))
    }

    static func firstSentence(of transcript: String?, maxCharacters: Int) -> String? {
        guard let transcript = trimmedTranscript(transcript) else { return nil }
        var sentence = transcript
        if let end = transcript.firstIndex(where: { ".!?".contains($0) }) {
            sentence = String(transcript[...end])
        }
        if sentence.count > maxCharacters {
            let cut = sentence.prefix(maxCharacters)
            let atWord = cut.lastIndex(of: " ").map { String(cut[..<$0]) } ?? String(cut)
            return atWord + "…"
        }
        return sentence
    }
```
> Pilgrim/Models/Honor/Way.swift:64-88@7c200bf

Rules, in order:

1. Trim `.whitespacesAndNewlines`. Empty after trim → nil.
2. Cap at 600 characters (`prefix(600)`).
3. The sentence ends at the first `.`, `!`, or `?`, inclusive. None → the whole capped text. A decimal point ends it too ("3.5 km" → "3.").
4. If the sentence is longer than 120 characters: take the first 120, cut back to the last ASCII space (exclusive), and append `…` (U+2026, one character). No space in the first 120 → a hard cut at 120 plus `…`. The result can be 121 characters.

Drift traps: Swift `count` and `prefix(n)` count grapheme clusters; Kotlin `String.length` and `take(n)` count UTF-16 units. Emoji or combining marks near the 120 or 600 boundary cut differently unless the port counts graphemes (`java.text.BreakIterator.getCharacterInstance()`). Only `" "` (U+0020) is a word boundary, not other whitespace.

`trimmedTranscript` runs at build time (section 10), so a stored own-walk `transcript` is already trimmed and at most 600 characters.

### 4. `WayGeometry`: construction and cumulative distance

`WayGeometry` is a pure value type. It reads no clock and no singleton.

```swift
/// The only place Honor does geometry. Cumulative haversine distances over
/// the Way's route; every other consumer talks in fracs (0...1 of the
/// total length) or seconds since departure.
struct WayGeometry {

    let points: [WayPoint]
    /// cumulative[i] = meters from the first point to point i.
    let cumulative: [Double]
    let totalMeters: Double
    let totalSeconds: Double

    init(route: [WayPoint]) {
        points = route
        var running = 0.0
        var cum: [Double] = []
        cum.reserveCapacity(route.count)
        for (index, point) in route.enumerated() {
            if index > 0 {
                running += Self.distanceMeters(from: route[index - 1], to: point)
            }
            cum.append(running)
        }
        cumulative = cum
        totalMeters = running
        totalSeconds = route.last.map { $0.t - (route.first?.t ?? 0) } ?? 0
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:4-29@7c200bf

```swift
    static func distanceMeters(from a: WayPoint, to b: WayPoint) -> Double {
        let r = 6_371_000.0
        let dLat = (b.lat - a.lat) * .pi / 180
        let dLon = (b.lon - a.lon) * .pi / 180
        let h = sin(dLat / 2) * sin(dLat / 2)
            + cos(a.lat * .pi / 180) * cos(b.lat * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:159-166@7c200bf

- Haversine, `r = 6_371_000.0` m.
- Empty route: `cumulative = []`, `totalMeters = 0`, `totalSeconds = 0`.
- One point: `cumulative = [0]`, `totalMeters = 0`, `totalSeconds = 0`.
- Duplicate points add 0 m (a plateau: equal consecutive `cumulative` values).
- `totalSeconds` is last `t` minus first `t`, not the sum of anything.

Android reuse: `haversineMeters` at `app/src/main/java/org/walktalkmeditate/pilgrim/domain/GeoDistance.kt:9-19@5ea4029b` uses the same radius (`EARTH_RADIUS_METERS = 6_371_000.0`). It differs in float order only (`Math.toRadians(lat2 - lat1)` and `R * 2 * atan2` vs `(Δ) * .pi / 180` and `2 * r * atan2`), which is a last-bit difference; goldens should compare fracs with a tolerance, not bit equality.

### 5. `WayGeometry`: frac, coordinate, and time

```swift
    func coordinate(atFrac frac: Double) -> CLLocationCoordinate2D {
        guard let first = points.first else { return CLLocationCoordinate2D(latitude: 0, longitude: 0) }
        guard points.count > 1, totalMeters > 0 else {
            return CLLocationCoordinate2D(latitude: first.lat, longitude: first.lon)
        }
        let target = min(max(frac, 0), 1) * totalMeters
        let (i, u) = segment(atDistance: target)
        let a = points[i], b = points[i + 1]
        return CLLocationCoordinate2D(latitude: a.lat + (b.lat - a.lat) * u,
                                      longitude: a.lon + (b.lon - a.lon) * u)
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:33-43@7c200bf

- Empty → `(0, 0)`. One point, or zero length → the first point.
- `frac` is clamped to `0...1`. Interpolation is linear in degrees (lat and lon separately), not along the great circle.

```swift
    /// The polyline between two fracs: an interpolated point at each end and
    /// every route point strictly inside, so consecutive slices share their
    /// boundary coordinate and draw as one continuous line.
    func slice(fromFrac start: Double, toFrac end: Double) -> [CLLocationCoordinate2D] {
        guard points.count > 1, totalMeters > 0 else {
            return points.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        }
        let a = min(max(min(start, end), 0), 1) * totalMeters
        let b = min(max(max(start, end), 0), 1) * totalMeters
        var coords = [coordinate(atFrac: a / totalMeters)]
        for (index, point) in points.enumerated() where cumulative[index] > a && cumulative[index] < b {
            coords.append(CLLocationCoordinate2D(latitude: point.lat, longitude: point.lon))
        }
        coords.append(coordinate(atFrac: b / totalMeters))
        return coords
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:45-60@7c200bf

- Reversed fracs are swapped (`min`/`max`). Both ends are clamped.
- Inside points are strict (`>` and `<`), so a point exactly at an end is not repeated.
- Degenerate route → every point as-is (possibly empty).
- `start == end` → two identical interpolated points.

```swift
    /// On a stationary plateau (several points at one location), returns the
    /// moment the walker moved on — the end of the pause. This ensures a
    /// companion anchored at a rest spot departs together with the honoring
    /// walker. For holding the dot at rest through the pause, use `frac(atElapsed:)`,
    /// which maps every second of the pause to the same frac.
    func elapsed(atFrac frac: Double) -> Double {
        guard points.count > 1, totalMeters > 0 else { return 0 }
        let (i, u) = segment(atDistance: min(max(frac, 0), 1) * totalMeters)
        let a = points[i], b = points[i + 1]
        return (a.t + (b.t - a.t) * u) - points[0].t
    }

    func frac(atElapsed elapsed: Double) -> Double {
        guard points.count > 1, totalMeters > 0 else { return 1 }
        let t0 = points[0].t
        if elapsed <= 0 { return 0 }
        if elapsed >= totalSeconds { return 1 }
        for i in 0..<(points.count - 1) {
            let ta = points[i].t - t0, tb = points[i + 1].t - t0
            if elapsed >= ta && elapsed <= tb {
                let u = tb > ta ? (elapsed - ta) / (tb - ta) : 0
                let d = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * u
                return d / totalMeters
            }
        }
        return 1
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:62-88@7c200bf

- `elapsed(atFrac:)` on a degenerate route returns **0**; `frac(atElapsed:)` on a degenerate route returns **1** (checked before the `elapsed <= 0` rule). iOS pins this:

```swift
        let single = WayGeometry(route: [WayPoint(lat: 1, lon: 1, alt: nil, t: 0)])
        XCTAssertEqual(single.totalMeters, 0)
        XCTAssertEqual(single.frac(atElapsed: 10), 1)
```
> UnitTests/Honor/WayGeometryTests.swift:61-63@7c200bf

- `frac(atElapsed:)` scans segments linearly from the start. The **first** segment whose closed time range `[ta, tb]` holds `elapsed` wins. A vertex time therefore resolves on the segment that ends at it (`u = 1`).
- Equal timestamps (`tb == ta`) give `u = 0`: the frac of that segment's start.
- Out-of-order timestamps (never produced by the builder, which sorts samples) fall through to `return 1`.
- `elapsed(atFrac:)` lands on the **end** of a distance plateau (the rest's last point), via `segment(atDistance:)`:

```swift
    /// Binary search for the segment containing distance d. The search deliberately
    /// lands on the last segment of equal cumulative distances (a plateau), ensuring
    /// `elapsed(atFrac:)` returns the end of the pause.
    private func segment(atDistance d: Double) -> (index: Int, u: Double) {
        var lo = 0, hi = points.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) / 2
            if cumulative[mid] <= d { lo = mid } else { hi = mid }
        }
        let span = cumulative[hi] - cumulative[lo]
        let u = span > 0 ? (d - cumulative[lo]) / span : 0
        return (lo, min(max(u, 0), 1))
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:145-157@7c200bf

The binary-search predicate is `cumulative[mid] <= d` (not `<`). Changing it moves the plateau answer to the start of the pause. iOS pins the pause behavior:

```swift
        let points = [
            WayPoint(lat: 0, lon: 0, alt: nil, t: 0),
            WayPoint(lat: 0, lon: 0.000898, alt: nil, t: 60),
            WayPoint(lat: 0, lon: 0.000898, alt: nil, t: 120),
            WayPoint(lat: 0, lon: 0.001796, alt: nil, t: 180),
        ]
        let geo = WayGeometry(route: points)
        XCTAssertEqual(geo.elapsed(atFrac: 0.5), 120, accuracy: 0.5)
        XCTAssertEqual(geo.frac(atElapsed: 70), 0.5, accuracy: 0.01)
        XCTAssertEqual(geo.frac(atElapsed: 110), 0.5, accuracy: 0.01)
        XCTAssertEqual(geo.frac(atElapsed: 150), 0.75, accuracy: 0.01)
```
> UnitTests/Honor/WayGeometryTests.swift:73-83@7c200bf

A route that returns to a place it passed earlier (a loop) is not a plateau: `cumulative` keeps rising, so `elapsed` and `frac` map one-to-one on it. Only consecutive equal points form a plateau.

### 6. `WayGeometry`: nearest point, windowed nearest, lowest frac

```swift
    func nearest(
        to coordinate: CLLocationCoordinate2D,
        within window: ClosedRange<Double>?
    ) -> (frac: Double, meters: Double) {
        guard let first = points.first else { return (0, .infinity) }
        guard points.count > 1, totalMeters > 0 else {
            return (0, Self.distanceMeters(from: first, to: WayPoint(lat: coordinate.latitude, lon: coordinate.longitude, alt: nil, t: 0)))
        }
        var best: (frac: Double, meters: Double) = (0, .infinity)
        for i in 0..<(points.count - 1) {
            let fa = cumulative[i] / totalMeters, fb = cumulative[i + 1] / totalMeters
            if let window, fb < window.lowerBound || fa > window.upperBound { continue }
            // Restrict the projection to the part of this segment inside the
            // window, so a window never leaks into a neighbouring segment
            // through a shared endpoint.
            var uRange: ClosedRange<Double> = 0...1
            if let window, fb > fa {
                let lo = max(0, (window.lowerBound - fa) / (fb - fa))
                let hi = min(1, (window.upperBound - fa) / (fb - fa))
                if lo > hi { continue }
                uRange = lo...hi
            }
            let hit = nearest(onSegment: i, to: coordinate, uRange: uRange)
            if hit.meters < best.meters {
                best = hit
            }
        }
        return best
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:95-123@7c200bf

- Empty route → `(0, .infinity)`. The engine clamps infinity before display (its comment: "`nearest` returns .infinity when the window holds no segment (a degenerate Way); clamped so the value stays printable", `HonorEngine.swift:194-197`).
- Degenerate route → frac 0 and the **haversine** distance to the first point.
- A window that excludes every segment returns `(0, .infinity)`, frac 0.
- Ties: strict `<` keeps the **lower-index** segment.
- Zero-length segments (`fb == fa`) inside the window keep `uRange = 0...1`; their projection is the shared point.
- The result frac is always inside the window (clamped by `uRange`). iOS pins the clamp: a probe 250 m along with window `0.0...0.1` returns frac `0.1` and 150 m (`WayGeometryTests.swift:52-58`).

```swift
    /// Closest point to `coordinate` on segment `i`, with the projection
    /// parameter clamped into `uRange`. Local equirectangular projection
    /// (meters) is accurate enough for the tens-of-meters decisions the
    /// engine makes.
    private func nearest(onSegment i: Int, to coordinate: CLLocationCoordinate2D, uRange: ClosedRange<Double>) -> (frac: Double, meters: Double) {
        let fa = cumulative[i] / totalMeters, fb = cumulative[i + 1] / totalMeters
        let cosLat = cos(coordinate.latitude * .pi / 180)
        let a = points[i], b = points[i + 1]
        let ax = (a.lon - coordinate.longitude) * cosLat, ay = a.lat - coordinate.latitude
        let bx = (b.lon - coordinate.longitude) * cosLat, by = b.lat - coordinate.latitude
        let dx = bx - ax, dy = by - ay
        let lengthSq = dx * dx + dy * dy
        let raw = lengthSq > 0 ? -(ax * dx + ay * dy) / lengthSq : 0
        let u = min(max(raw, uRange.lowerBound), uRange.upperBound)
        let px = ax + dx * u, py = ay + dy * u
        return (fa + (fb - fa) * u, sqrt(px * px + py * py) * 111_320)
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:125-141@7c200bf

**This is not haversine.** The segment projection is a local equirectangular plane centred on the query point: longitude scaled by `cos` of the **query's** latitude, then degrees times `111_320` m. The frac is linear in the segment parameter `u` between the segment's end fracs.

```swift
    func lowestFrac(within meters: Double, of coordinate: CLLocationCoordinate2D, from minFrac: Double = 0) -> (frac: Double, meters: Double)? {
        guard points.count > 1, totalMeters > 0 else {
            let hit = nearest(to: coordinate, within: nil)
            return hit.meters <= meters ? (0, hit.meters) : nil
        }
        // Walk the first contiguous run of segments within `meters` and take
        // its closest point. Strict `<` keeps the lower frac on a tie, which
        // is what protects the outbound leg of an out-and-back.
        var best: (frac: Double, meters: Double)?
        for i in 0..<(points.count - 1) {
            let fa = cumulative[i] / totalMeters, fb = cumulative[i + 1] / totalMeters
            if fb < minFrac { continue }
            let uLo = fb > fa ? min(1, max(0, (minFrac - fa) / (fb - fa))) : 0
            let hit = nearest(onSegment: i, to: coordinate, uRange: uLo...1)
            if hit.meters <= meters {
                if let current = best {
                    if hit.meters < current.meters { best = hit }
                } else {
                    best = hit
                }
            } else if best != nil {
                break
            }
        }
        return best
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:178-203@7c200bf

- Scans segments in order from `minFrac`, finds the **first contiguous run** of segments within `meters` (inclusive `<=`), returns that run's closest point, and stops at the first segment out of range after the run (`break`).
- A later, closer run is never considered. This is deliberate (the out-and-back and switchback notes in the doc comment, `WayGeometry.swift:168-177`).
- Degenerate route → `(0, meters)` if within range, else nil. An empty route reaches `nearest`, which returns infinity, so nil.
- `minFrac` clips the first segment it touches (`uLo`), so the result is never below `minFrac`.
- iOS pins the finely sampled case (`WayGeometryTests.swift:88-93`): 501 points 2 m apart, probe at 500 m, `within: 60` → frac 0.5 within 0.003.

### 7. `WayGeometry`: bearing, and the stage-only helpers

```swift
    /// Initial great-circle bearing from `from` to `to`, in degrees clockwise
    /// from true north, 0 ..< 360.
    static func bearing(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double {
        let lat1 = from.latitude * .pi / 180, lat2 = to.latitude * .pi / 180
        let dLon = (to.longitude - from.longitude) * .pi / 180
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let degrees = atan2(y, x) * 180 / .pi
        return (degrees + 360).truncatingRemainder(dividingBy: 360)
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:205-214@7c200bf

Used for the card's direction tick (`ActiveWalkViewModel+Honor.swift:281-285`). `truncatingRemainder` equals Kotlin's `%` on doubles (sign of the dividend), which is non-negative here because `degrees + 360 > 0`.

Stage-only (Stage 21-3 tiles; not needed for own walks): `corridor(around:halfWidthMeters:)` (`:229-262`), `corridorContains` (`:264-266`), `simplified(_:toleranceMeters:)` (Douglas–Peucker, local metres with `111_320` and `cos` of the **first** point's latitude, tolerance `25` m inside `corridor`, `:268-305`), and `ringContains` (ray casting in degrees, `:307-322`). There is **no** route simplification anywhere in the own-walk path; the builder's only reduction is the stride sample (section 9).

### 8. Every distance computation in Honor, with its function

| Where | Purpose | Function |
|---|---|---|
| `WayGeometry.init` (`WayGeometry.swift:22`) | cumulative length, `totalMeters` | haversine, 6,371,000 m |
| `nearest(onSegment:)` (`:129-141`) | projection onto a segment; windowed tracking and `lowestFrac` | equirectangular, `cos(query lat)`, `111_320` m/° |
| `nearest(to:within:)` degenerate branch (`:100-101`) | one-point Way | haversine |
| `OwnWalkWayBuilder.make` (`OwnWalkWayBuilder.swift:22-23,111`) | 20 m floor; `totalDistanceMeters` | haversine (full-resolution `WayGeometry`) |
| `HonorEngine.anchor` (`HonorEngine.swift:167`) | Begin anchor within `onWayMeters` | `lowestFrac` → equirectangular |
| `HonorEngine.track` (`HonorEngine.swift:193`, `:224-225`) | on-Way test, re-acquire | `nearest` / `lowestFrac` → equirectangular |
| `HonorEngine.evaluateArrival` (`HonorEngine.swift:303-304`) | distance to the Way's last point | `CLLocation.distance(from:)` |
| `HonorMomentTracker.update` (`HonorMomentTracker.swift:67`) | moment and voice radii | `CLLocation.distance(from:)` |
| `HonorMomentTracker.update` (`:80`, `:84`) | voice drop | `CLLocation.distance(from:)` |
| `HonorMomentTracker.waterAhead` (`:131`) | stage water mark ahead | frac difference × `totalMeters` (stage-only) |
| `ActiveWalkViewModel.distanceToMoment` (`ActiveWalkViewModel+Honor.swift:273-277`) | card subline "N m" | `CLLocation.distance(from:)` |
| `refreshMarkPinsIfWalkerMoved` (`ActiveWalkViewModel+MarkPins.swift:24-26`) | stage mark refresh | `CLLocation.distance(from:)` (stage-only) |
| `WayMarkPins.pins` (`WayMarkPins.swift:35`) | nearest 40 marks | `CLLocation.distance(from:)` (stage-only) |
| `HonorOverviewView.probeDistance` (`HonorOverviewView.swift:373`) | distance to the Way's start | `CLLocation.distance(from:)` |

The three `CLLocation.distance` rows for moments, voice drop, and arrival are the ones U16 must pin. Apple does not document that function's model. The tracker's `place(of:)` uses `moment.at` when present, else `geometry.coordinate(atFrac: moment.frac)`:

```swift
    private func place(of moment: WayMoment) -> CLLocation {
        if let at = moment.at { return CLLocation(latitude: at.lat, longitude: at.lon) }
        let c = geometry.coordinate(atFrac: moment.frac)
        return CLLocation(latitude: c.latitude, longitude: c.longitude)
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:141-145@7c200bf

The engine's tuning constants and thresholds belong to the engine cluster's spec.

### 9. `OwnWalkWayBuilder`: gates, route, and stride sample

```swift
/// A Way from one of the walker's own walks. Nothing is copied: voices
/// reference their recording files and photos their PhotoKit assets.
enum OwnWalkWayBuilder {

    static let maxRoutePoints = 4000
    static let minRestSeconds = 180.0
    /// Below this a "Way" is a cluster of jitter around one spot: every frac
    /// collapses onto the same place, the companion cannot move, and arrival
    /// is either instant or unreachable. Shared with `WayImporter`.
    static let minLengthMeters = 20.0

    static func make(from walk: WalkInterface) -> Way? {
        let samples = walk.routeData.sorted { $0.timestamp < $1.timestamp }
        guard samples.count >= 2, let first = samples.first, let uuid = walk.uuid else { return nil }
        let t0 = first.timestamp
        let full = samples.map {
            WayPoint(lat: $0.latitude, lon: $0.longitude, alt: $0.altitude,
                     t: $0.timestamp.timeIntervalSince(t0))
        }
        let fullGeometry = WayGeometry(route: full)
        guard fullGeometry.totalMeters >= minLengthMeters else { return nil }
        let route = full.count > maxRoutePoints ? strideSample(full, target: maxRoutePoints) : full
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:3-24@7c200bf

The builder is a pure synchronous function of a saved walk plus one file-system probe per recording (section 10). It returns nil when:

- the walk has fewer than 2 route samples (no route, or one fix);
- the walk has no uuid;
- the full-resolution haversine length is under 20 m (`>=` passes; iOS pins ~8 m → nil and ~40 m → built, `OwnWalkWayBuilderTests.swift:83-92`).

There is **no** check on recordings, photos, or duration: a walk with a route and nothing else builds a Way with no moments except rests and sittings.

The route is **every** saved sample (the samples the walk saved, no further accuracy filter), sorted by timestamp. `t` is seconds since the **first sample**, not since `walk.startDate`. `alt` copies the sample's altitude.

```swift
    private static func strideSample(_ points: [WayPoint], target: Int) -> [WayPoint] {
        let step = Double(points.count - 1) / Double(target - 1)
        var result = (0..<(target - 1)).map { points[Int((Double($0) * step).rounded())] }
        result.append(points[points.count - 1])
        return result
    }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:130-135@7c200bf

- Only when `full.count > 4000`. Output is exactly 4,000 points: indices `round(i × step)` for `i` in `0..<3999`, then the last point.
- `.rounded()` is schoolbook rounding (half away from zero). Kotlin `Math.round` / `roundToInt()` match for these non-negative values; **`kotlin.math.round()` is half-even and does not**.
- `totalDistanceMeters` is the **full-resolution** length (`fullGeometry.totalMeters`, `:111`), while the engine builds its geometry from the stored (downsampled) `route`. On a walk over 4,000 samples the two lengths differ slightly, and every moment's frac was computed on the full geometry. Port this as is.

Android reuse: route samples in `app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/RouteDataSample.kt:33-37@5ea4029b` (`timestamp` ms, `altitudeMeters: Double?` — nullable on Android, so `alt` may be omitted; iOS always has it).

### 10. Builder: placement, and each moment kind

```swift
        var moments: [WayMoment] = []
        // Positions come from the FULL-resolution samples, before any
        // downsampling, so a voice lands where it was spoken.
        func place(_ date: Date) -> (frac: Double, at: WayCoordinate) {
            let nearest = samples.min { abs($0.timestamp.timeIntervalSince(date)) < abs($1.timestamp.timeIntervalSince(date)) } ?? first
            let frac = fullGeometry.frac(atElapsed: nearest.timestamp.timeIntervalSince(t0))
            return (frac, WayCoordinate(lat: nearest.latitude, lon: nearest.longitude))
        }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:26-33@7c200bf

`place(date)`:

- picks the sample with the smallest `|timestamp − date|`; on a tie the **earlier** sample wins (Swift `min(by:)` keeps the first minimum; Kotlin `minByOrNull` does the same);
- takes the frac **by time**, `frac(atElapsed:)` at that sample's own time, not by projecting the coordinate onto the line;
- returns that sample's coordinate as `at`.

A date before the walk or after it lands on the first or last sample.

**Voices.**

```swift
        // Recordings are deletable in-app while their rows stay; a Way must
        // not promise a voice whose file is gone (TourBuilder.candidates
        // makes the same check).
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let present = walk.voiceRecordings
            .filter { !$0.fileRelativePath.isEmpty }
            .filter {
                let size = (try? FileManager.default.attributesOfItem(atPath: docs.appendingPathComponent($0.fileRelativePath).path)[.size]) as? Int
                return (size ?? 0) > 0
            }
            .sorted { $0.startDate < $1.startDate }
        for (n, rec) in present.enumerated() {
            let start = place(rec.startDate)
            let end = place(rec.endDate)
            moments.append(WayMoment(
                id: "voice-\(n + 1)", frac: start.frac, at: start.at,
                kind: .voice(endFrac: max(end.frac, start.frac), duration: rec.duration,
                             kind: TourBuilder.classify(transcription: rec.transcription) == .spoken ? .spoken : .ambient,
                             media: .recording(relativePath: rec.fileRelativePath)),
                transcript: WayMoment.trimmedTranscript(rec.transcription)))
        }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:35-55@7c200bf

- Filter: non-empty `fileRelativePath` **and** a file of size > 0 under Documents. A recovered recording that lost its file (`fileRelativePath` set to `""` by recovery, `WalkSessionGuard+Recovery.swift:45-54`) is excluded.
- Sorted by `startDate`. Ids are `voice-1`, `voice-2`, … over the **present** recordings only (see iOS defect D3).
- `frac` and `at` from `place(startDate)`; `endFrac = max(place(endDate).frac, start.frac)`.
- `duration` is the recording's own duration (seconds), not `end − start`.
- `kind` from `TourBuilder.classify` on the raw transcription:

```swift
    static func classify(transcription: String?) -> TourRecordingKind {
        guard let text = transcription?.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return .spoken
        }
        let wordCount = text.split(whereSeparator: \.isWhitespace).count
        if wordCount < 8 { return .ambient }
        return .spoken
    }
```
> Pilgrim/Models/Share/TourBuilder.swift:38-45@7c200bf

  No transcription → spoken. Fewer than 8 whitespace-separated words (including an empty transcription) → ambient.
- `transcript` = `trimmedTranscript(rec.transcription)` (section 3).
- No voice-count cap (the share's 12-recording cap does not apply).

Android reuse: `TourBuilder.classify` at `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/TourBuilder.kt:115-118@5ea4029b` (same rule; its word count mirrors Swift's Unicode whitespace). File probe: `VoiceRecordingFileSystem.fileSizeBytes` at `app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/VoiceRecordingFileSystem.kt:37-40@5ea4029b` (paths are relative to `filesDir`, as iOS's are to Documents).

**Photos.**

```swift
        for (n, photo) in walk.walkPhotos.sorted(by: { $0.capturedAt < $1.capturedAt }).enumerated() {
            let p = place(photo.capturedAt)
            moments.append(WayMoment(
                id: "photo-\(n + 1)", frac: p.frac,
                at: WayCoordinate(lat: photo.capturedLat, lon: photo.capturedLng),
                kind: .photo(media: .photoAsset(localIdentifier: photo.localIdentifier))))
        }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:56-62@7c200bf

- Every pinned photo, sorted by capture time; no presence check on the asset.
- `frac` from the nearest sample to `capturedAt`; `at` is the photo's **own** captured coordinate, not the sample's.
- iOS's `WalkPhotoInterface` has non-optional `capturedAt`, `capturedLat`, `capturedLng` (`Pilgrim/Protocols/DataInterfaces/WalkPhotoInterface.swift`). Android's are nullable (`app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/WalkPhoto.kt:52,85-88@5ea4029b`: `takenAt: Long?`, `capturedLat: Double?`, `capturedLng: Double?`). See Open questions.
- A photo moment has no playable media: `localMediaURL` returns nil for `.photoAsset` (`ActiveWalkViewModel+Honor.swift:362-363`).

**Waypoints.**

```swift
        let userWaypoints = walk.waypoints
            .filter { !SeekPersistence.isArrivalWaypoint($0) && !HonorPersistence.isArrivalWaypoint($0) }
            .sorted { $0.timestamp < $1.timestamp }
        for (n, wp) in userWaypoints.enumerated() {
            moments.append(WayMoment(
                id: "waypoint-\(n + 1)", frac: place(wp.timestamp).frac,
                at: WayCoordinate(lat: wp.latitude, lon: wp.longitude),
                kind: .waypoint(label: wp.label, icon: wp.icon)))
        }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:63-71@7c200bf

- Excludes the Seek arrival icon `"sun.haze"` (`SeekPersistence.swift:14`) and the Honor arrival icon `"signpost.right.fill"` (section 17). Every other waypoint is kept, label and icon verbatim.
- `frac` from the nearest sample to the waypoint's time; `at` is the waypoint's own coordinate.
- iOS labels and icons are non-optional; Android's `label: String?` and `icon: String?` are nullable (`app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/Waypoint.kt:36-37@5ea4029b`). See Open questions.
- Android reuse: `SeekPersistence.isArrivalWaypoint` and `HonorPersistence.isArrivalWaypoint` (icon-only matches) at `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekPersistence.kt:24-30@5ea4029b` and `app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/HonorPersistence.kt:22-25@5ea4029b`.

**Rests.**

```swift
        let rests = walk.pauses
            .filter { $0.endDate.timeIntervalSince($0.startDate) >= minRestSeconds }
            .sorted { $0.startDate < $1.startDate }
        for (n, pause) in rests.enumerated() {
            let p = place(pause.startDate)
            moments.append(WayMoment(
                id: "rest-\(n + 1)", frac: p.frac, at: p.at,
                kind: .rest(minutes: Int((pause.endDate.timeIntervalSince(pause.startDate) / 60).rounded()))))
        }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:72-80@7c200bf

- Pauses of **180 s or more** (inclusive). Both manual and automatic pauses count: the filter never reads `pauseType` (`WalkPauseInterface` carries one, `WalkPauseInterface.swift`).
- Minutes are `round(duration / 60)`, half away from zero: 200 s → 3 (iOS pins `restMinutes == 3` for a 200 s pause, `OwnWalkWayBuilderTests.swift:39-40,67-69`); 270 s → 5 (4.5 rounds up). Use `roundToInt()`, not `kotlin.math.round()` (half-even gives 4).
- Placed at the pause's start.
- Android has no pause rows; pauses are PAUSED/RESUMED events. Reuse `WalkMetricsMath.pauseSpans` at `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsMath.kt:69-95@5ea4029b` (first PAUSED opens, its RESUMED closes, a trailing PAUSED closes at the walk's end). Its `durationMillis` is coerced to at least 0.

**Sittings.**

```swift
        let sittings = walk.activityIntervals
            .filter { $0.activityType == .meditation }
            .sorted { $0.startDate < $1.startDate }
        for (n, sit) in sittings.enumerated() {
            let p = place(sit.startDate)
            moments.append(WayMoment(
                id: "sit-\(n + 1)", frac: p.frac, at: p.at,
                kind: .meditation(minutes: Int((sit.endDate.timeIntervalSince(sit.startDate) / 60).rounded()),
                                  isEstimate: false)))
        }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:81-90@7c200bf

- Every meditation interval, with **no minimum length** (a 20-second sitting becomes `minutes: 0`).
- Minutes rounded the same way as rests. iOS pins a 12-minute sitting → `minutes == 12`, `isEstimate == false` (`OwnWalkWayBuilderTests.swift:32-34,62-65`).
- `isEstimate` is always false for own walks (true is the shared-walk case).
- On Android the sittings come from `walk_events` (U6, #223): reuse `deriveActivityIntervals(events, walkId, closeAt = walk.endTimestamp)` at `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/ActivityIntervalReplay.kt:36-40@5ea4029b`. It merges overlapping sittings and drops zero-length ones, which iOS's stored intervals never needed.

### 11. Builder: ordering, spans, title, weather, identity

```swift
        moments.sort { $0.frac == $1.frac ? $0.id < $1.id : $0.frac < $1.frac }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:91@7c200bf

Moments are sorted by frac; equal fracs sort by id as a string. String order is plain lexicographic: `photo-` < `rest-` < `sit-` < `voice-` < `waypoint-`, and `voice-10` < `voice-2`. Moments of the same kind at one sample (two photos a second apart) share a frac and fall to this tiebreak.

```swift
        // The same stretches the walk's own route colors: a recording is a
        // talking span, a sitting a meditating span.
        let spans = Self.spans(
            talking: present.map { ($0.startDate, $0.endDate) },
            meditating: sittings.map { ($0.startDate, $0.endDate) },
            frac: { place($0).frac })
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:93-98@7c200bf

```swift
    private static func spans(
        talking: [(start: Date, end: Date)],
        meditating: [(start: Date, end: Date)],
        frac: (Date) -> Double
    ) -> [WaySpan] {
        func spans(_ intervals: [(start: Date, end: Date)], kind: WaySpanKind) -> [WaySpan] {
            intervals.compactMap { interval in
                let start = frac(interval.start), end = frac(interval.end)
                return end > start ? WaySpan(startFrac: start, endFrac: end, kind: kind) : nil
            }
        }
        return (spans(talking, kind: .talking) + spans(meditating, kind: .meditating))
            .sorted { $0.startFrac < $1.startFrac }
    }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:115-128@7c200bf

- Talking spans come from the **present** recordings only (the same filtered list as the voices). Meditating spans from the same sittings as the moments.
- A span is kept only when `endFrac > startFrac` strictly. When both ends snap to the same sample, or to samples with no distance between them (the walker stood still), the fracs are equal and the span is dropped.
- Spans may overlap. The list is talking-then-meditating, then sorted by `startFrac`. Swift's `sorted` is not documented as stable; Kotlin's `sortedBy` is. Equal `startFrac` across kinds is the only case where order could differ.
- `spans` is always an array for an own walk, possibly empty (`"spans":[]` on disk).

```swift
        let title: String
        if let comment = walk.comment?.trimmingCharacters(in: .whitespacesAndNewlines), !comment.isEmpty {
            title = comment
        } else {
            title = DateFormatter.localizedString(from: walk.startDate, dateStyle: .medium, timeStyle: .none)
        }
        let weather = walk.weatherCondition.map { WayWeather(condition: $0, temperatureC: walk.weatherTemperature) }

        return Way(
            id: "walk:\(uuid.uuidString)", source: .ownWalk(uuid), title: title,
            departedAt: walk.startDate, tzIdentifier: TimeZone.current.identifier, expires: nil,
            route: route, totalDistanceMeters: fullGeometry.totalMeters,
            theirActiveSeconds: walk.activeDuration, moments: moments, weather: weather, spans: spans)
    }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:100-113@7c200bf

- **Title**: the walk's `comment`, trimmed, when non-empty; else the walk's start date in the device locale and zone, medium date style, no time (en_US: "May 1, 2026"). On iOS the walk's `comment` is its intention: `snapshot.comment = vm?.intention` (`MainCoordinatorView.swift:96`), and the `.pilgrim` converter maps `intention: walk.comment` (`PilgrimPackageConverter.swift:100`). Android's field is `Walk.intention` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/Walk.kt:26@5ea4029b`), not `notes`. Android formatter precedent: `DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)` at `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/ShareDateFormat.kt:32@5ea4029b` (U13 needs `MEDIUM`).
- The title is computed once, at build time, and frozen in `way.json`. Its locale is the builder's locale.
- **Weather**: present only when the walk has a condition; `temperatureC` is the walk's stored temperature (nil allowed).
- **Id**: `"walk:" + uuid.uuidString`. Swift's `uuidString` is **uppercase**. Android's native `Walk.uuid` is `UUID.randomUUID().toString()`, which is **lowercase** (`Walk.kt:21@5ea4029b`); walks imported from iOS may carry uppercase. The store regex accepts both cases (section 12). Use the walk's uuid string verbatim, and derive the link key from the same string, so one walk never produces two ids.
- **Source**: `.ownWalk(uuid)`. **Expires**: nil.
- **tzIdentifier**: the device's **current** zone at build time, not the zone the walk was recorded in.
- **departedAt**: `walk.startDate` (whole seconds once saved, section 2). Route `t` counts from the first sample, so `departedAt + t` is offset by the gap between walk start and first fix.
- **theirActiveSeconds**: `walk.activeDuration` (seconds, `Double`). Android reuse: `WalkMetricsMath.activeDurationMillis(walk, pauseSpans(walk, events))` at `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsMath.kt:51-56@5ea4029b`, divided by 1000.0 (the `computeActiveDurationSeconds` helper truncates to whole seconds).

**Where the builder runs, and its failure copy.** Two call sites, both in the UI, both at "choose" time, not at Begin:

```swift
    func walkAgain(_ walk: WalkInterface) {
        pendingHonorWay = OwnWalkWayBuilder.make(from: walk)
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:308-310@7c200bf

```swift
                OwnWalkPicker(walks: ownWalks) { walk in
                    guard let way = OwnWalkWayBuilder.make(from: walk) else {
                        unwalkableAlert = true
                        return
                    }
                    onChoose(way)   // dismisses the parent sheet, which takes this nested one with it
                }
                .alert("Can't walk this one again", isPresented: $unwalkableAlert) {
                    Button("OK", role: .cancel) {}
                } message: {
                    Text("This walk doesn't have enough of a route to follow. Try another.")
                }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:94-105@7c200bf

- "walk this again" on a summary: a nil build parks nothing and shows nothing (the doc comment at `MainCoordinatorView.swift:307` says so).
- The Ways sheet's own-walk picker: a nil build shows the alert "Can't walk this one again" / "This walk doesn't have enough of a route to follow. Try another." with an "OK" button.
- The built Way is held in memory from this moment through the overview, Begin, and the walk. It is never rebuilt at Begin, and nothing writes it to disk before the walk ends (section 20).

iOS's builder tests to port first (U13's execution note): `UnitTests/Honor/OwnWalkWayBuilderTests.swift@7c200bf` — moments at fracs with coordinates (`:48-75`), nil without a route (`:77-79`), nil under the floor (`:83-86`), built just over it (`:88-92`), recordings whose file is gone are skipped (`:94-99`), identity and title (`:101-107`), spans follow the recording and the sitting (`:112-121`), the transcription rides onto the voice (`:126-130`).

### 12. The Ways store: location, layout, ids

```swift
struct WayLink: Codable, Equatable {
    let wayId: String
    /// The companion's timeline at arrival, recorded by the engine; nil when
    /// the walk ended before the end of the Way.
    let theirSeconds: Double?
    let yourSeconds: Double?
}

/// Application Support/Ways: one folder per Way, an index from walk UUID to
/// Way id, and the sharer's-promise sweep. The whole tree is excluded from
/// iCloud backup so a restore can never resurrect swept voices.
final class WayStore {

    static let shared: WayStore = {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        return WayStore(baseDirectory: appSupport.appendingPathComponent("Ways", isDirectory: true))
    }()
```
> Pilgrim/Models/Honor/WayStore.swift:3-19@7c200bf

```swift
    init(baseDirectory: URL, now: @escaping () -> Date = Date.init) {
        base = baseDirectory
        self.now = now
        try? fileManager.createDirectory(at: base, withIntermediateDirectories: true)
        var url = base
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }
```
> Pilgrim/Models/Honor/WayStore.swift:40-48@7c200bf

The store's `init` does file I/O (creates the folder, sets the backup flag), both errors swallowed. Android's plan puts the root under `noBackupFilesDir`, which is never backed up or transferred; the singleton-init I/O lesson (Stage 2-E) applies to a Hilt `@Singleton` that creates directories in its constructor.

On-disk layout, from the paths the store builds:

```
Application Support/
  Ways/
    index.json                       walk uuid → WayLink (shared by every Way)
    walk:<UUID>/                     one folder per Way; folder name == Way id
      way.json                       the Way (section 2)
      accepted.json                  {"acceptedAt":"<iso8601>"}, written once
      replies.json                   {"<n>":"<relative path>"}, only after a reply
      media/                         shared walks only; own walks have none
    share:<10 chars>/ …              shared (U26)
    pilgrimage/<routeId>/ …          stage packages (Stage 21-2)
    pilgrimage:<routeId>:<index>/ …  stage Ways (Stage 21-2)
```

The file names are literals in the store:

```swift
        try encoder.encode(way).write(to: dir.appendingPathComponent("way.json"), options: .atomic)
        let accepted = dir.appendingPathComponent("accepted.json")
```
> Pilgrim/Models/Honor/WayStore.swift:97-98@7c200bf

```swift
        guard let data = try? Data(contentsOf: directory(for: id).appendingPathComponent("replies.json")),
```
> Pilgrim/Models/Honor/WayStore.swift:168@7c200bf

```swift
    func mediaDirectory(for id: String) -> URL {
        directory(for: id).appendingPathComponent("media", isDirectory: true)
    }
```
> Pilgrim/Models/Honor/WayStore.swift:136-138@7c200bf

```swift
    private var indexURL: URL { base.appendingPathComponent("index.json") }
```
> Pilgrim/Models/Honor/WayStore.swift:242@7c200bf

Id validation, applied before every path use:

```swift
    /// Ids are built by code (`share:` + a validated share id, `walk:` + a
    /// UUID, `pilgrimage:` + a validated route slug and stage index). The
    /// store still refuses anything else so a stray folder name or a future
    /// caller can never turn an id into a path outside `Ways/`.
    static func isValidId(_ id: String) -> Bool {
        id.range(of: "\\A(share:[A-Za-z0-9_-]{10}|walk:[0-9A-Fa-f-]{36}|pilgrimage:[a-z0-9-]{1,64}:[0-9]{1,3})\\z",
                 options: .regularExpression) != nil
    }
```
> Pilgrim/Models/Honor/WayStore.swift:52-59@7c200bf

```swift
    private func directory(for id: String) -> URL {
        precondition(Self.isValidId(id), "WayStore: invalid way id \(id)")
        return base.appendingPathComponent(id, isDirectory: true)
    }
```
> Pilgrim/Models/Honor/WayStore.swift:237-240@7c200bf

- `walk:` + exactly 36 characters of hex digits (either case) and `-`. It does not check the 8-4-4-4-12 grouping.
- `share:` + exactly 10 of `[A-Za-z0-9_-]`. `pilgrimage:` + a 1–64 character lowercase slug + `:` + 1–3 digits.
- Anchored with `\A` and `\z`. In Kotlin, use `Regex.matches` (whole input) or `\z`; a Java `$` also matches before a trailing `\n`.
- Folder names contain `:` (for example `walk:E621E1F8-…`). Legal on Android internal storage.
- `directory(for:)` traps (`precondition`) on a bad id. Every public entry except the media helpers checks `isValidId` first and returns nil, `[:]`, `false`, `0`, or throws `CocoaError(.fileWriteInvalidFileName)`. iOS pins the refusals: `load(id: "index.json")` is nil, `replies(for: "../x")` is `[:]`, and `link` and `setReply` with `"../x"` throw (`WayStoreTests.swift:112-117`); a stray `..%2Fescape` folder is ignored by `list()` (`:104-110`).
- `pilgrimage` and `index.json` are not valid ids, so `list()` steps over them.

### 13. Store API: save, load, list, delete, media, disk usage

```swift
    func save(_ way: Way) throws {
        guard Self.isValidId(way.id) else { throw CocoaError(.fileWriteInvalidFileName) }
        let dir = directory(for: way.id)
        try fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        try encoder.encode(way).write(to: dir.appendingPathComponent("way.json"), options: .atomic)
        let accepted = dir.appendingPathComponent("accepted.json")
        if !fileManager.fileExists(atPath: accepted.path) {
            try encoder.encode(Accepted(acceptedAt: now())).write(to: accepted, options: .atomic)
        }
    }

    func load(id: String) -> Way? {
        guard Self.isValidId(id) else { return nil }
        guard let data = try? Data(contentsOf: directory(for: id).appendingPathComponent("way.json")) else { return nil }
        return try? decoder.decode(Way.self, from: data)
    }

    func acceptedAt(id: String) -> Date? {
        guard Self.isValidId(id) else { return nil }
        guard let data = try? Data(contentsOf: directory(for: id).appendingPathComponent("accepted.json")),
              let accepted = try? decoder.decode(Accepted.self, from: data) else { return nil }
        return accepted.acceptedAt
    }

    func list() -> [Way] {
        let ids = (try? fileManager.contentsOfDirectory(atPath: base.path)) ?? []
        return ids.filter(Self.isValidId).compactMap { load(id: $0) }
            .sorted { (acceptedAt(id: $0.id) ?? .distantPast) > (acceptedAt(id: $1.id) ?? .distantPast) }
    }

    func delete(id: String) {
        guard Self.isValidId(id) else { return }
        try? fileManager.removeItem(at: directory(for: id))
        var index = loadIndex()
        index = index.filter { $0.value.wayId != id }
        saveIndex(index)
    }
```
> Pilgrim/Models/Honor/WayStore.swift:93-129@7c200bf

- **save** creates the folder if needed, then **always** rewrites `way.json` (atomic replace). It writes `accepted.json` only if absent, so a re-save keeps the first acceptance time. iOS pins both halves (`WayStoreTests.swift:136-149`: a re-save keeps `acceptedAt` and the new title wins). `accepted.json` shape: `private struct Accepted: Codable { let acceptedAt: Date }` (`WayStore.swift:38`).
- **load** returns nil for a missing file, an unreadable file, or any decode error. No error surfaces.
- **list** reads the base folder's entries, keeps valid ids, loads each (dropping unreadable ones), and sorts newest `acceptedAt` first; a missing `accepted.json` sorts last (`.distantPast`). It includes own-walk, shared, and stage Ways; each caller filters (section 16). iOS pins newest-first (`WayStoreTests.swift:129-134`).
- **delete** removes the whole folder (way, accepted, replies, media) and rewrites `index.json` without **every** link that names this Way. A missing folder or failed removal is silent.

```swift
    /// No `isValidId` guard here: only callers that already hold a Way id
    /// loaded from disk or a stored `WayLink` reach these, so `directory(for:)`'s
    /// precondition is the correct last line of defense against a bad id.
    func mediaDirectory(for id: String) -> URL {
        directory(for: id).appendingPathComponent("media", isDirectory: true)
    }

    func mediaURL(for id: String, relative: String) -> URL {
        mediaDirectory(for: id).appendingPathComponent(relative)
    }

    func hasMedia(id: String) -> Bool {
        guard Self.isValidId(id) else { return false }
        let contents = (try? fileManager.contentsOfDirectory(atPath: mediaDirectory(for: id).path)) ?? []
        return !contents.isEmpty
    }

    func deleteMedia(id: String) {
        guard Self.isValidId(id) else { return }
        try? fileManager.removeItem(at: mediaDirectory(for: id))
    }

    func diskUsage(id: String) -> Int {
        guard Self.isValidId(id) else { return 0 }
        return fileManager.sizeOfDirectory(at: directory(for: id)) ?? 0
    }

    func diskUsage(of ways: [Way]) -> Int {
        ways.reduce(0) { $0 + diskUsage(id: $1.id) }
    }
```
> Pilgrim/Models/Honor/WayStore.swift:133-162@7c200bf

- For an own walk there is no `media/` folder: `hasMedia` is false, and `diskUsage` counts only `way.json`, `accepted.json`, and `replies.json` (the recordings live under Documents and are not counted).
- Every store call is synchronous file I/O on the caller's thread. Several iOS callers are on the main thread (the walk-end save, section 20). Android's `viewModelScope` defaults to Main; hop to IO at the store seam.

Android counterpart: none yet (U14 creates `P/data/honor/WayStore.kt`).

### 14. Replies and the walk index (links)

```swift
    func replies(for id: String) -> [Int: String] {
        guard Self.isValidId(id) else { return [:] }
        guard let data = try? Data(contentsOf: directory(for: id).appendingPathComponent("replies.json")),
              let map = try? decoder.decode([String: String].self, from: data) else { return [:] }
        return Dictionary(uniqueKeysWithValues: map.compactMap { key, value in Int(key).map { ($0, value) } })
    }

    func setReply(wayId: String, originN: Int, relativePath: String) throws {
        guard Self.isValidId(wayId) else { throw CocoaError(.fileWriteInvalidFileName) }
        var map = replies(for: wayId)
        map[originN] = relativePath
        let encodable = Dictionary(uniqueKeysWithValues: map.map { (String($0.key), $0.value) })
        try encoder.encode(encodable).write(to: directory(for: wayId).appendingPathComponent("replies.json"), options: .atomic)
    }

    func link(walkUUID: UUID, to wayId: String, arrival: (theirSeconds: Double, yourSeconds: Double)?) throws {
        guard Self.isValidId(wayId) else { throw CocoaError(.fileWriteInvalidFileName) }
        var index = loadIndex()
        index[walkUUID.uuidString] = WayLink(wayId: wayId, theirSeconds: arrival?.theirSeconds, yourSeconds: arrival?.yourSeconds)
        saveIndex(index)
    }

    func wayLink(forWalk uuid: UUID) -> WayLink? { loadIndex()[uuid.uuidString] }

    func wayId(forWalk uuid: UUID) -> String? { wayLink(forWalk: uuid)?.wayId }

    func way(forWalk uuid: UUID) -> Way? { wayId(forWalk: uuid).flatMap(load(id:)) }

    private var walkedIds: Set<String> { Set(loadIndex().values.map(\.wayId)) }
```
> Pilgrim/Models/Honor/WayStore.swift:166-194@7c200bf

```swift
    private func loadIndex() -> [String: WayLink] {
        guard let data = try? Data(contentsOf: indexURL) else { return [:] }
        return (try? decoder.decode([String: WayLink].self, from: data)) ?? [:]
    }

    private func saveIndex(_ index: [String: WayLink]) {
        try? encoder.encode(index).write(to: indexURL, options: .atomic)
    }
```
> Pilgrim/Models/Honor/WayStore.swift:244-251@7c200bf

**Replies** (`replies.json`, per Way, shared by every honoring of it):

- Shape: a JSON object from the decimal string of `originN` to a path relative to Documents, for example `{"1":"Recordings/<walk uuid>/<file>.m4a"}`. Keys are sorted as strings.
- `originN` is the `n` in `voice-n` (`HonorPersistence.stageReflectionOrigin = -1` is the stage case). Non-integer keys are dropped on read.
- `setReply` is read-modify-write: a second reply to the same voice **replaces** the first mapping. It writes into the Way's folder **without creating it** (contrast `save`, which calls `createDirectory`). That matters on a first honoring (section 21).

**Links** (`index.json`, one file for every Way):

- Shape: `{"<WALK UUID, uppercase>":{"theirSeconds":2400,"wayId":"walk:…","yourSeconds":2100}}`; with no arrival, the two seconds keys are omitted.
- `link` **overwrites** the entry for that walk uuid. It throws only on an invalid Way id: the write itself is `try?` inside `saveIndex`, so a failed write is silent.
- A corrupt or unreadable `index.json` reads as empty, and the next `link`, `delete`, or save of the index writes that empty map plus one entry, erasing every other link. (The plan's per-walk link file decision already avoids this; it is recorded here as iOS's behavior.)
- `WayLink.theirSeconds` / `yourSeconds` are the engine's arrival numbers. The summary's delta is `theirSeconds − yourSeconds`, only for non-stage Ways (`HonorSummarySection.swift:33`).

### 15. The sweep: own-walk Ways never expire

```swift
    /// Share Ways past their expiry: unwalked → whole folder; walked → media
    /// only. Own-walk Ways never expire. Returns the ids touched.
    @discardableResult
    func sweepExpired(now: Date) -> [String] {
        let walked = walkedIds
        var touched: [String] = []
        for way in list() {
            guard let expires = way.expires, expires <= now else { continue }
            retire(id: way.id, walked: walked)
            touched.append(way.id)
        }
        return touched
    }
```
> Pilgrim/Models/Honor/WayStore.swift:198-210@7c200bf

Own-walk Ways have `expires: nil`, so the sweep skips them. iOS pins it (`WayStoreTests.swift:62-63,76`: an own-walk Way survives a sweep). Everything else in the sweep (`retire`, `retireMany`, `WayStore.swift:212-235`) is the shared and stage slice (U26, Stage 21-2).

### 16. Who calls the store, and when (own-walk relevant)

From `git grep -n "WayStore" 7c200bf -- Pilgrim`, outside the store and the stage code:

| Call | Where | When | Own-walk effect |
|---|---|---|---|
| `save(way)` | `MainCoordinatorView.swift:117` | clean walk end, after the walk row saves | first write of an own-walk Way; rewrites it on every later honoring |
| `link(walkUUID:to:arrival:)` | `MainCoordinatorView.swift:119` | clean walk end, right after `save` | link with the arrival numbers, or none |
| `link(…, arrival: nil)` | `WalkSessionGuard+Recovery.swift:141` | launch recovery | only if the Way loads (section 22) |
| `setReply` | `ActiveWalkViewModel+Replies.swift:40` | when a reply recording completes | fails silently on a first honoring (section 21) |
| `replies(for:)` | `ActiveWalkViewModel+Replies.swift:48`; `WalkSummaryView.swift:748` | card "your reply"; summary counts | per Way |
| `wayLink(forWalk:)`, `load(id:)` | `WalkSummaryView.swift:746-747` | summary init, honor walks only | title, delta, voices, ghost line |
| `way(forWalk:)` | `SealInput.swift:53`; `PromptListView.swift:231` | seal build; prompt context | the Way's route under the seal; the Way's title |
| `list()` | `HonorWaysSheet.swift:114` | sheet appears | filtered to `.share`: own-walk Ways are **not** offered here |
| `list()`, `delete(id:)` | `WaysListView.swift:92-98`; `DataCard.swift:36-37` | Settings → Ways | own-walk Ways **are** listed and deletable (`listable` drops only package-owned) |
| `sweepExpired` | `AppDelegate.swift:201`; `HonorWaysSheet.swift:113`; `WaysListView.swift:96` | launch (detached, `.utility`); sheet; list | skips own walks |
| `localMediaURL` | `ActiveWalkViewModel+Honor.swift:354-365`; `HonorOverviewView.swift:183` | playing a voice; overview preview | `.recording` resolves under Documents |

```swift
    static func listable(_ ways: [Way]) -> [Way] {
        ways.filter { !$0.source.isPackageOwned }
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:12-14@7c200bf

```swift
                acceptedWays = WayStore.shared.list().filter { if case .share = $0.source { return true } else { return false } }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:114@7c200bf

Consequence: a stored own-walk Way is never walked from disk. Every honoring rebuilds the Way from the source walk (section 11); the stored copy exists for the summary, the seal, prompts, replies, recovery, and Settings → Ways.

Media resolution for an own-walk voice, with its containment check:

```swift
    static func localMediaURL(for media: WayMedia, wayId: String, store: WayStore) -> URL? {
        switch media {
        case .recording(let relativePath):
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            return resolvedMediaURL(docs.appendingPathComponent(relativePath), within: docs)
        case .file(let relative):
            let url = store.mediaURL(for: wayId, relative: relative)
            return resolvedMediaURL(url, within: store.mediaDirectory(for: wayId))
        case .photoAsset:
            return nil
        }
    }

    /// A relative path comes from a Way's own JSON — a share file or a
    /// hand-edited own-walk record — so a `../` component must not be able
    /// to walk it outside its base directory. Standardizing collapses any
    /// such component before the containment check.
    private static func resolvedMediaURL(_ url: URL, within base: URL) -> URL? {
        let resolved = url.standardizedFileURL
        let baseComponents = base.standardizedFileURL.pathComponents
        guard resolved.pathComponents.count > baseComponents.count,
              Array(resolved.pathComponents.prefix(baseComponents.count)) == baseComponents else { return nil }
        return FileManager.default.fileExists(atPath: resolved.path) ? resolved : nil
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:354-377@7c200bf

- The resolved path must sit strictly below the base (more components, same prefix) and the file must exist; otherwise nil. A nil URL means the voice is never started: `startVoice` hands the turn straight back to the engine (`ActiveWalkViewModel+Honor.swift:214-220`).
- Android: the base for `.recording` is `filesDir` (`VoiceRecordingFileSystem.absolutePath`, `app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/VoiceRecordingFileSystem.kt:31-32@5ea4029b`). Use canonical paths for the containment check.

### 17. Persistence vocabulary: events, arrival waypoint, labels

```swift
/// The persistence vocabulary for honor walks, shaped like SeekPersistence:
/// a `.honorMode` event at recording start, and on reaching the end of the
/// Way a `.honorArrival` event plus a waypoint with the reserved icon.
enum HonorPersistence {

    /// Must never collide with WaypointMarkingSheet's presets, "mappin", or
    /// SeekPersistence.arrivalWaypointIcon.
    static let arrivalWaypointIcon = "signpost.right.fill"

    /// Replies are keyed by the `n` in a `voice-n` id. A stage has no
    /// voices, so its arrival reflection is filed under a reserved index no
    /// `voice-n` can ever produce.
    static let stageReflectionOrigin = -1
    static let stageReflectionMomentID = "stage-reflection"
```
> Pilgrim/Models/Honor/HonorPersistence.swift:3-16@7c200bf

```swift
    static func isArrivalWaypoint(_ waypoint: WaypointInterface) -> Bool {
        waypoint.icon == arrivalWaypointIcon
    }

    static func arrivalWaypointLabel(wayTitle: String) -> String {
        String(format: arrivalLabelFormat, wayTitle)
    }

    static let honorModeEventName = NSLocalizedString(
        "honor.event.honor_mode", value: "Honor",
        comment: "Name of the walk event marking a walk as an honor walk.")

    static let honorArrivalEventName = NSLocalizedString(
        "honor.event.arrival", value: "Way walked",
        comment: "Name of the walk event written when the end of a Way is reached.")

    private static let arrivalLabelFormat = NSLocalizedString(
        "honor.arrival.label", value: "Walked their way: %@",
        comment: "Waypoint label at the end of an honored Way; %@ is the Way's title.")
}
```
> Pilgrim/Models/Honor/HonorPersistence.swift:26-45@7c200bf

| Item | Value | Localization key |
|---|---|---|
| Arrival waypoint icon | `"signpost.right.fill"` | — |
| Arrival waypoint label | `"Walked their way: %@"` (the Way's title) | `honor.arrival.label` |
| `.honorMode` display name | `"Honor"` | `honor.event.honor_mode` |
| `.honorArrival` display name | `"Way walked"` | `honor.event.arrival` |
| Stage reflection origin / moment id | `-1` / `"stage-reflection"` (stage-only) | — |

The three `NSLocalizedString` keys are not in `Base.lproj/Localizable.strings` at the pin (a search for `honor.event` and `honor.arrival` in `*.strings` finds nothing), so the English `value:` is what ships. The same English label is written for an own walk: "Walked their way: <your own walk's title>". iOS pins it: `"Walked their way: Rúa do Franco → Obradoiro"` (`HonorPersistenceTests.swift:23-26`).

The arrival icon is matched by icon alone, and must be disjoint from user icons:

```swift
    func testReservedIconIsDisjointFromUserIcons() {
        let userIcons = WaypointChip.presets.map(\.icon) + ["mappin", SeekPersistence.arrivalWaypointIcon]
        XCTAssertFalse(userIcons.contains(HonorPersistence.arrivalWaypointIcon))
```
> UnitTests/Honor/HonorPersistenceTests.swift:14-16@7c200bf

**Event types.** Events carry no payload: `uuid`, `eventType`, `timestamp` only (`WalkEvent.swift:143-150`, `TempWalkEvent(uuid:eventType:timestamp:)`). The integer raw values:

```swift
        case lap, marker, segment, seekMode, seekArrival, honorMode, honorArrival, unknown

        public init(rawValue: Int) {
            switch rawValue {
            case 0:
                self = .lap
            case 1:
                self = .marker
            case 2:
                self = .segment
            case 3:
                self = .seekMode
            case 4:
                self = .seekArrival
            case 5:
                self = .honorMode
            case 6:
                self = .honorArrival
            default:
                self = .unknown
            }
        }
```
> Pilgrim/Models/Data/DataModels/WalkEvent.swift:30-51@7c200bf

`.honorMode` = 5, `.honorArrival` = 6, unknown = -1 (`:69-70`). `debugDescription` is `"HonorMode"` / `"HonorArrival"` (`:108-111`). The `.pilgrim` wire strings:

```swift
        case .honorMode: return "honorMode"
        case .honorArrival: return "honorArrival"
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:500-501@7c200bf

and back (`:513-514`); anything unrecognized reads `.unknown` (`:515`).

When they are written (payload-free, stamped `Date()`):

```swift
    func writeHonorMarkerEventIfNeeded() {
        guard mode == .honor, way != nil else { return }
        builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .honorMode, timestamp: Date()))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:42-45@7c200bf

```swift
    func startRecording() {
        proximityService.resetSession()
        builder.setStatus(.recording)
        writeSeekMarkerEventIfNeeded()
        writeHonorMarkerEventIfNeeded()
        startHonorEngineIfNeeded()
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:380-385@7c200bf

```swift
    /// The persistence commit happens before any ritual effect, as in Seek.
    private func recordHonorArrival(theirSeconds: Double, yourSeconds: Double) {
        guard let way else { return }
        builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .honorArrival, timestamp: Date()))
        addWaypoint(label: HonorPersistence.arrivalWaypointLabel(wayTitle: way.title),
                    icon: HonorPersistence.arrivalWaypointIcon)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:247-252@7c200bf

- One `.honorMode` at recording start, before the engine starts; one `.honorArrival` plus one arrival waypoint when the engine reports arrival, before the haptic (`handleHonorEvent`, `:208-210`).
- The arrival numbers are not in the event. They ride on the in-memory `HonorArrivalCard` into the link at walk end (section 20).

Android status at `5ea4029b`: `HONOR_MODE` and `HONOR_ARRIVAL` exist (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEventType.kt:39,46@5ea4029b`); `HonorPersistence.ARRIVAL_WAYPOINT_ICON = "signpost.right.fill"` and `isArrivalWaypoint` exist (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/HonorPersistence.kt:22-25@5ea4029b`). The label format and the two display names are not ported yet (U14).

### 18. `WalkMode`

```swift
enum WalkMode: String, CaseIterable {
    case wander, honor, seek

    var subtitle: String {
        switch self {
        case .wander: return "walk · talk · meditate"
        case .honor: return "walk in their steps"
        case .seek: return "follow the unknown"
        }
    }

    var buttonLabel: String {
        switch self {
        case .wander: return "Wander"
        case .honor: return "Honor"
        case .seek: return "Seek"
        }
    }

    var isAvailable: Bool { true }

    var quotes: [String] {
        switch self {
        case .wander: return (1...6).map { LS["Welcome.Quote.\($0)"] }
        case .honor: return (1...3).map { LS["Honor.Quote.\($0)"] }
        case .seek: return (1...3).map { LS["Seek.Quote.\($0)"] }
        }
    }
}
```
> Pilgrim/Models/Walk/WalkMode.swift:3-31@7c200bf

```
"Honor.Quote.1" = "Where they walked,\nyou walk";
"Honor.Quote.2" = "Two traveling together";
"Honor.Quote.3" = "Their steps\nare still warm";
```
> Pilgrim/Support Files/Base.lproj/Localizable.strings:165-167@7c200bf

- Raw values `"wander"`, `"honor"`, `"seek"`; order `[.wander, .honor, .seek]` (pinned, `WalkModeTests.swift:6-11`). Honor is the **middle** slot, where Android's `Together` sits today.
- The raw value is never persisted. It is shown uppercased in the mode slot and used as the accessibility label (`WalkStartView.swift:326`, `:337`). A walk's mode is derived from its events (`.seekMode`, `.honorMode`), never stored.
- All three modes are available.

Android at `5ea4029b`: `enum class WalkMode { Wander, Together, Seek }`, `isAvailable` true for Wander and Seek, and `fromWire` matches the enum **name** with a Wander fallback (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkMode.kt:10-25@5ea4029b`). Android's wire values (nav arguments, intent extras) are Android-internal and need not equal iOS's lowercase raw values; iOS writes none.

### 19. Checkpoint fields Honor adds, and how recovery uses them

```swift
struct WalkCheckpoint: Codable {
    /// Shape of the on-disk checkpoint JSON. Bumped whenever `TempWalk` gains or
    /// loses fields in a way that older builds can't round-trip; `WalkSessionGuard`
    /// recovers any version from `minimumRecoverableSchemaVersion` through this one.
    static let currentSchemaVersion = 2

    /// Version 1 carried no Way identity. Everything it did carry decodes
    /// unchanged into this shape — the honor fields simply arrive nil — so a
    /// walk crashed under the previous build still recovers, as a plain walk.
    static let minimumRecoverableSchemaVersion = 1

    let schemaVersion: Int
    let walkUUID: UUID
    let checkpointDate: Date
    let walk: TempWalk
    /// The Way this walk is honoring, so a crash-recovered walk can be bound
    /// back to it. Nil for every walk that isn't an honor walk.
    let wayId: String?
    /// The engine's last word before the crash. The engine dies with the
    /// process, so a recovered stage walk has no other source for its ledger
    /// entry.
    let honorProgressFrac: Double?
    let honorArrived: Bool?

    /// Both halves are written together from the same engine read, so either
    /// one missing means no stage was joined and no ledger entry was earned.
    var honorOutcome: HonorStageOutcome? {
        guard let honorProgressFrac, let honorArrived else { return nil }
        return HonorStageOutcome(progressFrac: honorProgressFrac, arrived: honorArrived)
    }
```
> Pilgrim/Models/Walk/WalkCheckpoint.swift:3-32@7c200bf

Honor adds three optional fields and bumps the schema to 2 (version 1 still recovers, as a plain walk):

| Field | Type | Meaning | Recovery use |
|---|---|---|---|
| `wayId` | `String?` | the honored Way's id | the re-link (section 22) |
| `honorProgressFrac` | `Double?` | engine progress at the last checkpoint | stage ledger only |
| `honorArrived` | `Bool?` | engine phase was arrived | stage ledger only |

What fills them:

```swift
    var honorCheckpointState: (wayId: String, outcome: HonorStageOutcome?)? {
        guard mode == .honor, let way else { return nil }
        guard let engine = honorEngine, engine.isAnchoredOnWay else { return (way.id, nil) }
        return (way.id, HonorStageOutcome(progressFrac: engine.progressFrac,
                                          arrived: engine.phase == .arrived))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:166-171@7c200bf

```swift
        var honor: (wayId: String, outcome: HonorStageOutcome?)?

        if let viewModel {
            let intervals = viewModel.checkpointActivityIntervals()
            snapshot.replaceActivityIntervals(intervals)

            if let inflightTalk = viewModel.voiceRecordingManagement.checkpointVoiceRecording() {
                snapshot.appendVoiceRecordings([inflightTalk])
            }

            honor = viewModel.honorCheckpointState
        }
```
> Pilgrim/Models/Walk/WalkSessionGuard.swift:171-182@7c200bf

- `wayId` is written for every honor walk from the first checkpoint on, even before the engine anchors. The outcome pair is written only once the engine is anchored on the Way (`isAnchoredOnWay` is `startFrac != nil && !anchoredByFallback`, `HonorEngine.swift:45`).
- Cadence: a repeating timer at the power tier's interval, `30` s normal, `60` s meditating, `15` s low battery, `10` s critical (`WalkSessionGuard.swift:48-55`), plus a final checkpoint on app termination (`AppDelegate.swift:262-265`).
- The checkpoint's `walk` snapshot carries the walk's events, so `.honorMode` (and `.honorArrival` plus the arrival waypoint, if arrival fired before the last checkpoint) survive into the recovered walk (`makeRecoveredWalk` copies `workoutEvents` and `waypoints`, `WalkSessionGuard+Recovery.swift:177-203`).
- Nothing about the arrival **numbers** (`theirSeconds`, `yourSeconds`) is checkpointed.
- The file is `Application Support/walk_checkpoint.json` (`WalkSessionGuard.swift:279-282`), written off the main thread on a serial utility queue.

For an own walk, recovery uses only `wayId`. The outcome pair feeds the stage ledger (branch point: `guard let stage = way.stage else { return }`, `WalkSessionGuard+Recovery.swift:142`).

Android's plan replaces the checkpoint file with Room session rows (Key Technical Decisions); this table is what those rows must be able to answer for recovery.

### 20. The own-walk Way save at walk end

```swift
        let vm = ActiveWalkViewModel(mode: mode, way: way)
        vm.onWalkCompleted = { [weak self, weak vm] snapshot in
            snapshot.comment = vm?.intention
            DataManager.saveWalk(object: snapshot) { success, _, walk in
                if success {
                    // The save transaction is confirmed — only now is the
                    // crash-recovery checkpoint safe to discard (AF1). On
                    // failure it stays on disk and recoverIfNeeded re-saves
                    // the walk at next launch.
                    WalkSessionGuard.deleteCheckpointFile()
                }
                guard let self else { return }
                if success {
                    snapshot.uuid = walk?.uuid
                    // The only place a finished walk is bound to its Way:
                    // `save` is idempotent (an own-walk Way is first written
                    // here), and `link` overwrites, so it must not run again
                    // elsewhere. Crash recovery binds too, but only a walk
                    // this path never reached, so the two can't collide.
                    // A packaged stage is skipped — this `way` was captured at
                    // Begin, and an Update that redrew the stage while the
                    // walk was on would be written back over here.
                    if let way, let uuid = walk?.uuid {
                        if !way.source.isPackageOwned { try? WayStore.shared.save(way) }
                        let arrival = vm?.honorArrival.map { (theirSeconds: $0.theirSeconds, yourSeconds: $0.yourSeconds) }
                        try? WayStore.shared.link(walkUUID: uuid, to: way.id, arrival: arrival)
                        self.recordStageWalk(way: way, outcome: vm?.honorStageOutcome)
                    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:94-121@7c200bf

**When it runs.** On a clean Finish only: `stop()` → `builder.setStatus(.ready)` → the snapshot → `onWalkCompleted` (hopped to main, `ActiveWalkViewModel.swift:226-230`) → `DataManager.saveWalk` → its success callback. Not on discard (`cancel()` never snapshots), not on a failed walk save, and not after a crash (recovery has its own rule, section 22).

**What it writes, in order:**

1. The checkpoint file is deleted (walk row is durable).
2. `save(way)` for any non-package Way. For an own walk this is the Way object built at "walk this again" or the picker (section 11), unchanged since.
3. `link(walkUUID:to:arrival:)` keyed by the **saved walk's** uuid, with `(theirSeconds, yourSeconds)` from `vm.honorArrival` when arrival fired, else nil (both keys omitted).
4. `recordStageWalk` (a no-op without a stage: `guard let stage = way?.stage else { return }`, `MainCoordinatorView.swift:292-295`).

**Overwrite.** Yes. `save` always rewrites `way.json` (section 13), so every clean honoring of the same source walk replaces the stored Way with the build that honoring walked, and every earlier honor walk's summary, seal, and prompts then read the newer build. `accepted.json` keeps the first honoring's time. `link` overwrites only this walk's own entry (a new uuid each walk, so in practice it adds).

**Failure handling.** None visible. Both calls are `try?`. A failed `save` still lets `link` run: the summary then finds a link whose Way does not load and shows "a way that has been removed" with the delta still present (`HonorSummarySection.swift:33-36`). A failed index write is silent inside `saveIndex`. No retry, no log.

**Thread.** The callback runs on main (the file's own note: "saveWalk completions land on the main queue (CoreStore's default)", `MainCoordinatorView.swift:367-368`), so these writes are main-thread file I/O on iOS. Android must not copy that.

The Android plan's Begin use case (U17) stages the Way under the Begin-minted uuid and promotes it at finalize "only if absent". iOS has no staging and **overwrites**; see Resolutions, item 6.

### 21. Replies filed before the Way folder exists

```swift
    func replyHere(to voice: WayMoment) {
        pendingReplyOrigin = voice
        if !isRecordingVoice { toggleVoiceRecording() }
        // `isRecordingVoice` only mirrors `voiceRecordingManagement.isRecording`
        // through an async main-queue sink, so it can't be trusted here yet —
        // reading the component directly gives the synchronous answer.
        // Denied permission, an inactive walk, or a recorder that failed to
        // open all leave it false; with nothing now in flight, no completed
        // recording will ever arrive to consume this origin.
        if !voiceRecordingManagement.isRecording {
            pendingReplyOrigin = nil
        }
    }

    /// The reply is filed under the origin voice's own index — the `n` in
    /// the `voice-n` ids `OwnWalkWayBuilder` writes — never its position in
    /// `moments`, which mixes every kind of moment together.
    func recordReplyIfPending(latestRecording: TempVoiceRecording) {
        guard let way, let origin = pendingReplyOrigin, let n = Self.originIndex(of: origin) else { return }
        pendingReplyOrigin = nil
        try? honorSenses.store().setReply(wayId: way.id, originN: n, relativePath: latestRecording.fileRelativePath)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:20-41@7c200bf

```swift
    private static let voiceIDPrefix = "voice-"

    /// The `n` in the `voice-n` ids `OwnWalkWayBuilder` writes — the index a
    /// reply is filed under — or the reserved index the stage's arrival
    /// reflection uses. Nil for any other moment id.
    static func originIndex(of moment: WayMoment) -> Int? {
        if moment.id == HonorPersistence.stageReflectionMomentID { return HonorPersistence.stageReflectionOrigin }
        guard moment.id.hasPrefix(voiceIDPrefix) else { return nil }
        return Int(moment.id.dropFirst(voiceIDPrefix.count))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:79-88@7c200bf

The mapping is written the moment the reply recording completes, from `bindCompletedRecordings` (`ActiveWalkViewModel.swift:593-605`), which outlives `stop()` so a reply still recording at Finish is filed after teardown (`:586-592`).

`setReply` writes `Ways/<way id>/replies.json` without creating the folder (section 14). The only thing that creates an own-walk Way's folder is `save` at walk end (section 20), which runs after `saveWalk` completes. So:

- **First honoring of an own walk**: the folder does not exist during the walk or when a Finish-time reply completes. `Data.write(to:options: .atomic)` into a missing folder throws, `try?` discards it, and the reply mapping is lost. The reply recording itself is kept as an ordinary recording of the honor walk. No iOS test covers this: every reply test calls `try store.save(testWay)` first (`ActiveWalkHonorTests.swift:246-247`, `:304-305`).
- **Later honorings**: the folder exists from the first clean honoring's save, so replies are filed.
- **Shared and stage Ways**: their folders exist from import or install, so replies are filed.

This is iOS defect D1. The Android plan's staging folder (created at Begin) would give replies a home on a first honoring; whether Android files them there is a parity decision (Resolutions, item 6).

### 22. Recovery's re-link rule

```swift
        DataManager.saveWalk(object: recovered) { success, error, saved in
            if success {
                try? FileManager.default.removeItem(at: url)
                rebindWay(checkpoint: checkpoint, walkUUID: saved?.uuid, store: wayStore)
                print("\(tag) RECOVERY SUCCESS — walk from \(walk.startDate) saved, checkpoint deleted")
                sweepGate.noteWalkRecoveryResolved()
                completion(walk.startDate)
            } else {
                print("\(tag) RECOVERY SAVE FAILED: \(String(describing: error)), keeping checkpoint for retry")
                completion(nil)
            }
        }
    }

    /// Everything the walk-end save does for an honor walk that a crash took
    /// instead: the index link the summary reads its stage block off, and the
    /// route's ledger. A recovered walk has never been linked, so this cannot
    /// overwrite a link the normal path wrote. No arrival is recorded — the
    /// engine's arrival numbers died with the process.
    private static func rebindWay(checkpoint: WalkCheckpoint, walkUUID: UUID?, store: WayStore) {
        guard let wayId = checkpoint.wayId, let walkUUID,
              let way = store.load(id: wayId) else { return }
        try? store.link(walkUUID: walkUUID, to: wayId, arrival: nil)
        guard let stage = way.stage else { return }
        PilgrimageLedgerStore(store: store).record(
            stage: stage, outcome: checkpoint.honorOutcome, at: checkpoint.checkpointDate)
    }
```
> Pilgrim/Models/Walk/WalkSessionGuard+Recovery.swift:119-145@7c200bf

The rule:

- Runs once at launch, after the recovered walk row saves. The recovered walk keeps the checkpoint's uuid (`recovered.uuid = checkpoint.walkUUID`, `:201`).
- Links **only** when the checkpoint names a Way **and** `store.load(id:)` returns it. It never saves a Way. The link carries **no arrival** (`arrival: nil`), even if `.honorArrival` is among the recovered events.
- It does not re-save the Way, so an own-walk Way that was never saved stays unsaved.
- Stage branch: records the ledger from `honorOutcome` (Stage 21-2).

**The plan's belief is correct.** A first honoring of an own walk is never linked by recovery: the only writer of its `way.json` is the clean walk-end save (section 20), which a crash never reached, so `load` returns nil and `rebindWay` returns before `link`. The recovered walk still carries `.honorMode` (and possibly `.honorArrival` and the arrival waypoint), so its summary shows the Honor block with the fallback title:

```swift
            wayTitle: way?.title ?? "a way that has been removed",
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:36@7c200bf

with no delta (no link), `voicesAlongTheWay` 0, and no ghost line on the seal (`way(forWalk:)` is nil).

One correction to "recovery links only a Way already listed": a **repeat** honoring of an own walk that crashes **is** linked, to the `way.json` the previous clean honoring saved. That file is the previous honoring's build, not the one this walk walked (the in-memory Way died with the process), and the link has no delta.

iOS's recovery tests cover only stage Ways (`UnitTests/Honor/PilgrimageStageWalkTests+Recovery.swift@7c200bf`); nothing tests an own-walk recovery.

### 23. Deleting walks and Ways: what goes, what stays

**No walk-removal path touches the Ways store.** A search for `WayStore` across `Pilgrim/` (section 16) finds no call in `DataManager`, the replace path, or the `.pilgrim` importer.

**Deleting one walk.** The code exists and removes the walk's recording files, but nothing calls it at the pin:

```swift
    public static func deleteObject<ObjectType: DataTypeProtocol>(object: ObjectType, completion: @escaping (_ success: Bool, _ error: DataManager.DeleteError?) -> Void) {

        let walkUUID = (object as? Walk)?.uuid

        dataStack.perform(asynchronous: { (transaction) -> ([String], [UUID]) in

            var filePaths: [String] = []
            var recordingUUIDs: [UUID] = []
            if let walk = object as? Walk,
               let editable = transaction.edit(walk) {
                filePaths = editable._voiceRecordings.value.compactMap { $0._fileRelativePath.value }
                recordingUUIDs = editable._voiceRecordings.value.compactMap { $0._uuid.value }
            }
            transaction.delete(object)
            return (filePaths, recordingUUIDs)

        }) { (result) in
            switch result {
            case .success(let (filePaths, recordingUUIDs)):
                cleanupRecordingFiles(relativePaths: filePaths)
                transcriptContextStore.delete(recordingUUIDs: recordingUUIDs)
                if let uuid = walkUUID {
                    UserPreferences.unmarkWalkArchived(uuid: uuid)
                }
                completion(true, nil)
```
> Pilgrim/Models/Data/DataManager.swift:785-809@7c200bf

`git grep -n "deleteObject" 7c200bf -- Pilgrim` finds only this definition. `DataManager.deleteAll` is called only from `#if DEBUG` code (`HomeView.swift:82-97`, `AppDelegate.swift:269-280`). So an iOS user at the pin cannot delete a walk in-app. Android can (single delete, delete-by-id), so the Android rule is an extension; iOS's store behavior says what it must keep.

What each removal does to Honor state, by reading the code:

| Removed | Recording files | Its link in `index.json` | The Way folder | Replies filed by it |
|---|---|---|---|---|
| An honor walk (`deleteObject`, unreachable) | deleted | **kept** (orphan) | kept | mapping kept in `replies.json`; its file is gone, so the card reads no reply (`existingReplyURL` → nil, `ActiveWalkViewModel+Replies.swift:43-50`) but the summary still counts it (`repliesMade: replies.count`, `HonorSummarySection.swift:39`) |
| The source walk (`deleteObject`, unreachable) | deleted, so the stored Way's `.recording` paths dangle | n/a | kept | kept |
| Web-editor replace (same uuid re-created, `DataManager+Replace.swift:79-93`) | not deleted; their paths are restored onto the re-inserted rows (`DataManager+Replace.swift:41`) | kept, and still matches (keyed by uuid) | kept | kept |
| Archive strip (`PilgrimPackageImporter.swift:443-474`) | deleted | kept | kept | kept |
| `deleteAll` (debug only) | all deleted | kept | kept | kept |

The archive strip also deletes the walk's events and waypoints:

```swift
        for rec in recordings { transaction.delete(rec) }
        for sample in walk._routeData.value { transaction.delete(sample) }
        for photo in walk._walkPhotos.value { transaction.delete(photo) }
        for rate in walk._heartRates.value { transaction.delete(rate) }
        for waypoint in walk._waypoints.value { transaction.delete(waypoint) }
        for pause in walk._pauses.value { transaction.delete(pause) }
        for event in walk._workoutEvents.value { transaction.delete(event) }
        for interval in walk._activityIntervals.value { transaction.delete(interval) }
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:457-464@7c200bf

So an archived honor walk loses `.honorMode` and no longer reads as an honor walk (the summary requires `.honorMode`, `WalkSummaryView.swift:744`), while its link stays in the index. An archived source walk has no route, so "walk this again" returns nil (`samples.count >= 2` fails).

**Deleting a Way** (Settings → Ways, the only caller of `delete(id:)`):

```swift
            .onDelete { offsets in
                // A walk that already followed this Way keeps its own route and
                // moments; only the shareable Way folder goes away, so the
                // walk's summary falls back to "a way that has been removed".
                for index in offsets { delete(id: ways[index].id) }
                reload()
            }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:51-57@7c200bf

```swift
        .alert("Delete all Ways?", isPresented: $confirmDeleteAll) {
            Button("Delete", role: .destructive) {
                ways.forEach { delete(id: $0.id) }
                reload()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Their voices and photos leave this phone. Your own walks are untouched.")
        }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:76-84@7c200bf

For an own-walk Way this removes `way.json`, `accepted.json`, `replies.json`, and **every** link to it (section 13). It keeps the recordings (they belong to the walks). Every honor walk of that Way then shows "a way that has been removed" with **no delta** (the delta came from the link), loses the Way's line under its seal, and its prompts lose the Way title. The plan's R5 item "Way deletion dropping past deltas" is confirmed by `index.filter { $0.value.wayId != id }` (`WayStore.swift:127`).

The walk's own `.honorMode`, `.honorArrival`, and arrival waypoint are untouched by any Way deletion, so milestones and the journal's honor marks survive.

### 24. `WayGPXExporter` (debug only)

```swift
#if DEBUG
import Foundation

/// Xcode's Core Location simulation reads only <wpt> elements and paces
/// timed waypoints at the speed their timestamps dictate. One <wpt> per
/// route point, in order; moment ids ride on the nearest route point's
/// <name> so nothing hops the simulator off the Way.
enum WayGPXExporter {

    static func gpx(for way: Way) -> Data {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        var names: [Int: [String]] = [:]
        for moment in way.moments {
            let target = moment.frac * Double(max(way.route.count - 1, 0))
            names[Int(target.rounded()), default: []].append(moment.id)
        }
        var lines = [
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<gpx version=\"1.1\" creator=\"Pilgrim\" xmlns=\"http://www.topografix.com/GPX/1/1\">"
        ]
        for (index, point) in way.route.enumerated() {
            lines.append("  <wpt lat=\"\(point.lat)\" lon=\"\(point.lon)\">")
            if let alt = point.alt { lines.append("    <ele>\(Int(alt.rounded()))</ele>") }
            lines.append("    <time>\(formatter.string(from: way.departedAt.addingTimeInterval(point.t)))</time>")
            if let ids = names[index] { lines.append("    <name>\(ids.joined(separator: " "))</name>") }
            lines.append("  </wpt>")
        }
        lines.append("</gpx>")
        return Data(lines.joined(separator: "\n").utf8)
    }
}
#endif
```
> Pilgrim/Models/Honor/WayGPXExporter.swift:1-33@7c200bf

Exact output:

```
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Pilgrim" xmlns="http://www.topografix.com/GPX/1/1">
  <wpt lat="42.1" lon="-8.2">
    <ele>300</ele>
    <time>2023-11-14T22:13:20Z</time>
  </wpt>
  <wpt lat="42.2" lon="-8.3">
    <time>2023-11-14T22:14:50Z</time>
    <name>sit-1</name>
  </wpt>
</gpx>
```

(From iOS's test fixture, `WayGPXExporterTests.swift:7-18`: two points, the second without altitude, one `sit-1` moment at frac 1, departed `1_700_000_000`.)

- Only `<wpt>` elements, one per route point in order. No `<trk>`, `<rte>`, or `<metadata>`. iOS pins "no track" and the waypoint count (`WayGPXExporterTests.swift:13-14`).
- Lines joined with `\n`, no trailing newline. Two-space and four-space indentation. UTF-8.
- `lat` and `lon`: Swift's default `Double` description (shortest round-trip). Kotlin's `Double.toString()` agrees for ordinary coordinates but writes an exponent differently below `1e-3` in magnitude (Swift `1e-05`, Kotlin `1.0E-5`).
- `<ele>`: altitude rounded half away from zero to an integer; omitted when `alt` is nil.
- `<time>`: `departedAt + t`, ISO 8601 UTC with `Z`, whole seconds.
- `<name>`: every moment whose `round(frac × (count − 1))` equals this index, in `way.moments` order, space-separated. No XML escaping (ids are code-generated: `voice-1`, `photo-2`, …). Points without moments carry no `<name>`.
- Where it is offered: a debug-only toolbar menu on the Honor overview, icon `"ladybug"`, item "Export simulation GPX" (`HonorOverviewView.swift:156-164`). The file goes to the temporary directory as the Way id with `:` replaced by `-`, plus `.gpx`, and opens the share sheet:

```swift
    private func exportSimulationGPX() {
        let data = WayGPXExporter.gpx(for: way)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(way.id.replacingOccurrences(of: ":", with: "-")).gpx")
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:213-216@7c200bf

Android's U19 uses a GPX export plus an FLP mock-mode replayer; the export format above is iOS's, tuned for Xcode's simulator (`<wpt>` only). Whether the Android emulator's GPX playback reads `<wpt>` the same way is U19's to confirm.

### 25. `WayMarkPins` (stage-only)

```swift
enum WayMarkPins {

    /// Below this a whole stage's services read as a rash rather than a map.
    static let drawFromZoom: CGFloat = 13
    /// Inside a town a stage can carry over a hundred at zoom 13.
    static let maxPerScreen = 40

    static func symbol(for kind: WayMarkKind) -> String {
        switch kind {
        case .water: return "drop.fill"
        case .food: return "fork.knife"
        case .bed: return "bed.double.fill"
        case .transport: return "bus.fill"
        case .supply: return "bag.fill"
        case .medical: return "cross.case.fill"
        }
    }

    /// The marks worth drawing right now: nothing when zoomed out, otherwise
    /// the nearest `maxPerScreen` to the walker. Without a fix the stage's
    /// own order stands in, so the overview still shows the first stretch.
    static func pins(marks: [WayMark], zoom: CGFloat, near: CLLocationCoordinate2D?) -> [PilgrimAnnotation] {
        guard zoom >= drawFromZoom, !marks.isEmpty else { return [] }
```
> Pilgrim/Models/Honor/WayMarkPins.swift:6-28@7c200bf

Own walks never carry marks (the builder never sets `marks`), and the walk screen returns before calling `pins`:

```swift
    private func applyMarkPins() {
        guard let marks = way?.marks, !marks.isEmpty else {
            if !honorMarkPins.isEmpty { honorMarkPins = [] }
            return
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift:51-55@7c200bf

Nothing for U13–U23 to port. The rest (zoom ≥ 13, nearest 40 by `CLLocation.distance` with an id tiebreak, stage order without a fix, the six SF Symbols) is Stage 21-2's.

### 26. Honor preferences

```swift
    static let honorVoicesEnabled = UserPreference.Required<Bool>(key: "honorVoicesEnabled", defaultValue: true)
    static let honorSoftTapEnabled = UserPreference.Required<Bool>(key: "honorSoftTapEnabled", defaultValue: false)
    static let pilgrimageOfflineNoteShown = UserPreference.Required<Bool>(key: "pilgrimageOfflineNoteShown", defaultValue: false)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:77-79@7c200bf

| Key | Type | Default | Written by | Read by |
|---|---|---|---|---|
| `honorVoicesEnabled` | `Bool` | `true` | the overview toggle "walk with their voice" (`HonorOverviewView.swift:264-271`; hidden for a stage, disabled when the Way has no voices) | engine start (`ActiveWalkViewModel+Honor.swift:58`) |
| `honorSoftTapEnabled` | `Bool` | `false` | **nothing in the app** | engine start (`:57`) |
| `pilgrimageOfflineNoteShown` | `Bool` | `false` | the stage offline note (`HonorOverviewView.swift:385-395`) | stage-only |

Other preferences Honor reads:

```swift
    static let soundsEnabled = UserPreference.Required<Bool>(key: "soundsEnabled", defaultValue: true)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:36@7c200bf

```swift
    static let voiceGuideVolume = UserPreference.Required<Double>(key: "voiceGuideVolume", defaultValue: 0.8)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:57@7c200bf

```swift
        let engine = HonorEngine(
            way: way,
            // A stage has no other walker to be off the way *from*; the soft
            // tap and the companion dot are both about someone else.
            softTapEnabled: UserPreferences.honorSoftTapEnabled.value && !way.isPilgrimageStage,
            voicesEnabled: UserPreferences.honorVoicesEnabled.value && UserPreferences.soundsEnabled.value
        )
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:53-59@7c200bf

```swift
    static func voiceVolume(for kind: VoiceKind) -> Float {
        let base = Float(UserPreferences.voiceGuideVolume.value)
        return kind == .ambient ? base * 0.5 : base
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:342-345@7c200bf

- Voices play only if `honorVoicesEnabled && soundsEnabled`, **read once at Begin** (engine start). Changing either mid-walk has no effect on that walk. This matches the plan's "settings snapshot on the start intent" design.
- Soft tap: `honorSoftTapEnabled && !isPilgrimageStage`, read once at Begin. With no writer, it is always false for users (iOS defect D2).
- Way voice volume: `voiceGuideVolume` (default `0.8`), halved for `ambient`, read at **each** play, so a mid-walk volume change applies to the next voice. A reply plays at the full `voiceGuideVolume` (`ActiveWalkViewModel+Replies.swift:74`).
- `gpsAccuracy` (`Double?`, key `"gpsAccuracy"`, `UserPreferences.swift:32`) shapes the location filter that feeds the engine (engine cluster).
- The keys are iOS `UserDefaults` keys; Android stores them in DataStore, and by the plan's `:tracker` rule the values reach the session on the start intent, never by a `:tracker` DataStore read.

### 27. Drift traps for the Kotlin port

1. **Rounding.** Swift `.rounded()` is half away from zero. Use `roundToInt()` / `Math.round` for the stride sample, rest and sitting minutes, and GPX `<ele>`; never `kotlin.math.round()` (half-even).
2. **Grapheme counts.** `transcriptLine` and `trimmedTranscript` count Swift `Character`s (section 3).
3. **Regex anchors.** Port `\A…\z` exactly, or use `Regex.matches` (section 12).
4. **UUID case.** iOS ids and index keys are uppercase `uuidString`; Android's native uuids are lowercase. Keep one string per walk (section 11).
5. **Dates.** Whole-second UTC `Z` on disk; decode must accept only what Swift's `.iso8601` accepts if iOS fixtures are to round-trip (section 2).
6. **Omit nil.** `explicitNulls = false` for every optional; `Int` fields as JSON integers (section 2).
7. **Frac by time, not projection.** Every own-walk moment's frac comes from `frac(atElapsed:)` at its nearest sample's time (section 10).
8. **Two distance models.** Cumulative length is haversine; nearest-point projection is equirectangular at `111_320` m/° (sections 6, 8).
9. **Plateau predicate.** `segment(atDistance:)` uses `cumulative[mid] <= d` (section 5).
10. **First-run `lowestFrac`.** Stop at the first out-of-range segment after a run; strict `<` on ties (section 6).
11. **Main-thread file I/O.** iOS's walk-end save and several store reads run on main; Android must hop to IO (section 20).
12. **`try?` everywhere.** iOS swallows every store write failure. Parity keeps the walk unaffected by a store failure; Android's plan adds loud refusal only where it says so (Begin, id validation).

### Resolutions for the plan

1. **`Way` fields and the on-disk layout.** Fields, types, and optionality: section 1's table. Keys are the Swift names, from synthesized `Codable`: sorted keys, nil omitted, whole-second ISO 8601 dates, associated-value enums as one-key objects with `_0` for unlabeled values (section 2). Layout: `Application Support/Ways/<way id>/` holding `way.json`, `accepted.json` (written once), `replies.json` (after the first filed reply), and `media/` (shared walks only), plus one shared `Ways/index.json` from walk uuid to `WayLink` (section 12). An own-walk Way's folder is `walk:<UUID>` and has no media: voices point at the walk's recordings, photos at their assets. The store lives in Application Support, excluded from iCloud backup, and never leaves the device (no `.pilgrim` export). `list()` returns every readable Way, newest `acceptedAt` first; the Ways sheet shows only shared ones, Settings → Ways everything but stage packages (section 16). `save` runs at every clean walk end for a non-package Way, and always rewrites `way.json` (sections 13, 20). `delete(id:)` runs only from Settings → Ways and removes the folder and every link to it (sections 13, 23). The sweep never touches own walks (section 15).

2. **`WayGeometry`.** Functions: `init` (cumulative haversine), `coordinate(atFrac:)`, `slice(fromFrac:toFrac:)`, `elapsed(atFrac:)`, `frac(atElapsed:)`, `nearest(to:within:)`, the private `nearest(onSegment:…)` and `segment(atDistance:)`, `distanceMeters` (haversine, 6,371,000 m), `lowestFrac(within:of:from:)`, `bearing(from:to:)`, and the stage-only `corridor`, `corridorContains`, `simplified` (Douglas–Peucker), `ringContains`. Edge cases per function: sections 4–7. **Correction:** "Geometry is haversine at 6,371 km" (U13) holds for lengths only. The projection behind `nearest` and `lowestFrac` (so behind the engine's anchor, tracking, and re-acquire) is an equirectangular plane: `cos` of the query latitude, `111_320` m per degree (section 6). The moment radii, voice drop, and arrival use `CLLocation.distance(from:)`, not `WayGeometry` at all (section 8). There is no simplification in the own-walk path; only a stride sample.

3. **`OwnWalkWayBuilder`.** Inputs: every saved route sample, sorted by time; recordings with a non-empty path and a file larger than 0 bytes, sorted by start; every pinned photo, sorted by capture; every waypoint except the Seek (`"sun.haze"`) and Honor (`"signpost.right.fill"`) arrival icons; pauses of 180 s or more of any type; every meditation interval (Android: derived from `walk_events`). Each moment's frac is the frac **at the time** of the full-resolution sample nearest the moment's time; `at` is that sample for voices, rests, and sittings, and the item's own coordinate for photos and waypoints. Ids are `voice-n`, `photo-n`, `waypoint-n`, `rest-n`, `sit-n`, 1-based per kind in time order; the Way id is `walk:<UUID>`. Transcripts are trimmed and capped at 600. Moments sort by frac, then id. Spans come from the same recordings and sittings, kept only when they have length. No recordings → a Way with no voices and no talking spans, still built. No route (fewer than 2 samples), a route under 20 m, or no uuid → nil (sections 9–11). The Way is built when the walker chooses the walk, not at Begin.

4. **Persistence vocabulary.** Arrival waypoint icon `"signpost.right.fill"`; label `"Walked their way: %@"` (`honor.arrival.label`); event display names `"Honor"` (`honor.event.honor_mode`) and `"Way walked"` (`honor.event.arrival`); raw values `honorMode = 5`, `honorArrival = 6`; `.pilgrim` strings `"honorMode"` and `"honorArrival"`; events carry no payload; one `.honorMode` at recording start, one `.honorArrival` plus one arrival waypoint at arrival, persisted before the haptic (section 17). `WalkMode` raw values `"wander"`, `"honor"`, `"seek"`, in that order, never persisted; Honor's subtitle `"walk in their steps"`, button `"Honor"`, three quotes (section 18).

5. **Checkpoint fields.** `wayId: String?`, `honorProgressFrac: Double?`, `honorArrived: Bool?`; schema 2, minimum 1. `wayId` is written for every honor walk; the pair only once the engine is anchored. Recovery uses `wayId` to re-link, and the pair only for a stage's ledger. The arrival numbers are never checkpointed (section 19).

6. **The own-walk save at walk end.** Runs in the walk-save success callback on a clean Finish only, on the main thread: `save(way)` (skipped for a package stage), then `link` with the engine's arrival numbers or none, then the stage ledger. It **overwrites** `way.json` on every clean honoring and keeps the first `accepted.json`. Failures are swallowed; `link` runs even when `save` failed (section 20). Two points for U17's design:
   - The plan's "promotes it by writing the listed Way only if absent" differs from iOS, which rewrites the listed Way with each honoring's build. Keeping the first build instead changes what earlier walks' summaries read after a later honoring. The owner should choose deliberately (match iOS, or file D5 and diverge).
   - On a first honoring, iOS loses every reply because the folder does not exist yet (section 21, D1). A staging folder created at Begin would give those replies a home; filing them would diverge from iOS. Under the upstream-first rule, match iOS and file D1, unless the owner decides otherwise.

7. **Recovery's re-link rule.** Confirmed: `rebindWay` links only when the checkpoint names a Way **and** `WayStore.load` finds it, always with `arrival: nil`, and never saves a Way. A first honoring of an own walk is therefore never linked; its summary reads "a way that has been removed" with no delta (section 22). **Correction:** a crashed **repeat** honoring is linked, to the build the previous clean honoring saved, with no delta. U17's test "no link (the Way was never listed)" is right for a first honoring; add the repeat case.

8. **Deleting.** Removing a walk never touches the Ways store: the walk's link, the Way folder, and `replies.json` all stay. iOS has no in-app single-walk delete at the pin (`deleteObject` has no caller); its code deletes the walk's recording files, which leaves replies and own-walk voices pointing at missing files (they read as absent). The archive strip also deletes the walk's events, so an archived honor walk stops reading as one while its link stays. Deleting a Way (Settings → Ways) removes its folder, replies, and every link to it; walks keep their events and arrival waypoints, lose the delta and the Way's title, and read "a way that has been removed" (section 23). This supports U14's "keep the link file and marker on every walk-delete path".

9. **`WayGPXExporter` and `WayMarkPins`.** The exact GPX text is in section 24: `<wpt>` elements only, `<ele>` rounded, `<time>` as `departedAt + t` in UTC, moment ids space-joined in `<name>` at `round(frac × (count − 1))`, `\n`-joined, debug-only, offered from the overview's "ladybug" menu. `WayMarkPins` is stage-only; own walks never reach it (section 25).

10. **Honor preferences.** `honorVoicesEnabled` (`Bool`, default `true`), `honorSoftTapEnabled` (`Bool`, default `false`, no writer in the app), `pilgrimageOfflineNoteShown` (`Bool`, default `false`, stage-only). Honor also reads `soundsEnabled` (`true`), `voiceGuideVolume` (`0.8`, halved for ambient, read at each play), and `gpsAccuracy` (`Double?`). The voice and soft-tap switches are read once at Begin (section 26).

Also answered from this cluster:

- **Distance function per call site** (plan, Deferred to Implementation): section 8's table.
- **The Way the session loads** (U17): iOS walks the in-memory Way built at choose time; it never reloads from disk during a walk.

### iOS defects found

Candidate upstream issues. None filed.

**D1. First-honoring replies to your own walk are silently lost.** `setReply` writes `replies.json` into the Way's folder without creating it, and an own-walk Way's folder is created only by the walk-end `save`.

```swift
        try encoder.encode(encodable).write(to: directory(for: wayId).appendingPathComponent("replies.json"), options: .atomic)
```
> Pilgrim/Models/Honor/WayStore.swift:178@7c200bf

```swift
        try? honorSenses.store().setReply(wayId: way.id, originN: n, relativePath: latestRecording.fileRelativePath)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:40@7c200bf

Impact: every reply made on the first re-walk of your own walk is recorded as a plain recording, never filed. Later honorings never offer it as "your reply", and the summary never counts it. The tests hide it, because each one saves the Way first (`ActiveWalkHonorTests.swift:247`, `:305`). A test that calls `setReply` on a fresh store without `save` would confirm it for the upstream issue.

**D2. The soft tap is opt-in with no switch.** The design calls it "opt-in, `UserPreferences.honorSoftTapEnabled`, default off" (`docs/superpowers/specs/2026-09-01-honor-mode-design.md:462@7c200bf`), but nothing in the app writes the preference: `git grep honorSoftTap 7c200bf` outside docs finds the declaration, the engine-start read, and tests only.

```swift
            softTapEnabled: UserPreferences.honorSoftTapEnabled.value && !way.isPilgrimageStage,
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:57@7c200bf

Impact: the soft tap, its haptic, and the "off the way · N m" caption never reach a user. Android matches as shipped: default off, no switch.

**D3. Reply keys shift when an earlier recording is deleted.** Voice ids number only the recordings whose files are present (`OwnWalkWayBuilder.swift:39-50`), while `replies.json` keeps its `n` keys across honorings. A recording file can be deleted in-app (`RecordingsListView.swift:117`, `WalkSummaryRecordingsSection.swift:52`). After you delete the first recording of a walk, the old `voice-2` becomes `voice-1`, and a reply filed under `1` now plays on the wrong voice. Impact: "your reply" answers a different voice than the one you replied to.

**D4. Settings → Ways says an own walk's voices "returned to the trail".** Own-walk Ways never have a `media/` folder, so `hasMedia` is false for every one of them:

```swift
        if way.voiceCount + way.photoCount > 0, !WayStore.shared.hasMedia(id: way.id) {
            return "\(lead) · voices returned to the trail"
        }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:108-110@7c200bf

Impact: every own-walk Way with a voice or photo shows the expired-share line. The "Delete all Ways?" message "Their voices and photos leave this phone" (`:83`) also misdescribes own-walk Ways, whose recordings stay. (U26 owns this screen; recorded here because the cause is the store's shape.)

**D5. Each honoring rewrites the stored own-walk Way under earlier walks.** `save` always rewrites `way.json` (`WayStore.swift:97`), and the walk-end comment treats that as harmless ("`save` is idempotent", `MainCoordinatorView.swift:109`). A later build can differ (recordings deleted, transcripts added), so an earlier honor walk's summary count "voices along the way", seal line, and prompt title can change after the fact. Low impact; it compounds D3.

**D6. Summary reply count is per Way, not per walk.** The summary counts every entry in the Way's `replies.json`, including replies from other honorings and replies whose recording is gone:

```swift
        let replies = link.map { WayStore.shared.replies(for: $0.wayId) } ?? [:]
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:748@7c200bf

```swift
            repliesMade: replies.count,
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:39@7c200bf

Impact: "N replies" on one walk's summary counts replies made on other walks of the same Way. (Summary cluster; noted for the store's shape.)

**D7. Recovery links a crashed repeat honoring to the previous build.** Adds to the plan's listed issue "recovery never linking a first honoring". `rebindWay` links whatever `way.json` is on disk (`WalkSessionGuard+Recovery.swift:139-141`). For a repeat honoring that is the previous honoring's build, not the one walked. Low impact.

**D8. One bad `index.json` read erases every link on the next write.** `loadIndex` returns `[:]` on any read or decode error, and `link`, `delete`, and `saveIndex` then write that map back (`WayStore.swift:183-185`, `:244-251`). Impact: after a single corrupt read, every honor walk loses its delta and Way. (The plan's per-walk link files already avoid it.)

### Open questions

1. **Photos with no time or place (Android-only).** iOS photos always carry `capturedAt`, `capturedLat`, and `capturedLng` (non-optional in `WalkPhotoInterface`). Android's `takenAt`, `capturedLat`, and `capturedLng` are nullable. iOS gives no rule for a photo with no capture time (for `place()`) or no coordinate (for `at`). Candidates: skip it, or fall back to `pinnedAt` and the nearest sample. This is an R6 platform-gap decision.
2. **Waypoints with a null label or icon (Android-only).** iOS's `.waypoint(label: String, icon: String)` requires both, and iOS waypoints always have them. Android's are nullable. The mapping for null is Android's to choose.
3. **An iOS-generated own-walk `way.json` fixture.** iOS's repository has no fixture for `ownWalk`, `recording`, `photoAsset`, `voice`, `rest`, `meditation`, or `spans`. The shapes in section 2 follow Swift's synthesized `Codable`. U13's fixture test should use a file captured from an iOS build (for example through the U16 harness) rather than one written by hand.
## B. The Honor engine

Cluster B of the U11 own-walk port spec. iOS pin `7c200bf` (the v2.0.0 tag). Android HEAD `5ea4029b`.
Files read line by line at the pin: `Pilgrim/Models/Honor/HonorEngine.swift`, `HonorMomentTracker.swift`, `HonorTuning.swift`, `HeadingProvider.swift`, `WayGeometry.swift`; `Pilgrim/Models/Walk/Seek/ArrivalDebounce.swift`, `SeekEngine.swift` (shared pieces only); `Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift`, `LiveStats.swift`, `WalkBuilder.swift`, `WalkBuilder+Status.swift`; `Pilgrim/Models/Walk/WalkSessionGuard.swift` (GPS tiers); `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift`, `ActiveWalkViewModel+Honor.swift`, `+Seek.swift`, `+MarkPins.swift`, `ActiveWalkView+Honor.swift`, `ActiveWalkView.swift`, `WalkStatsSheet.swift`; `Pilgrim/Scenes/Honor/WayMomentHeader.swift`; `Pilgrim/Models/Honor/WayVoicePlayer.swift` (only where it changes what the engine's events do); the iOS tests `UnitTests/Honor/HonorEngineTests.swift`, `HonorMomentTrackerTests.swift`, `ActiveWalkHonorTests.swift`, `UnitTests/Seek/ArrivalDebounceTests.swift`.

Scope is the own-walk slice. Where the engine branches on stages or water, the branch point is named in one line and not specified.

Two facts shape everything below, so they come first:

- **iOS has no reachable walk pause at the pin.** Nothing calls `setStatus(.paused)`, `AutoPauseDetection` is never constructed, and `ActiveWalkViewModel.resume()` has no caller (§14). The engine's pause gate and its "paused time buys no credit" rule are written and unit-tested, but no shipped iOS walk ever enters them. Android can pause (the notification's Pause action), so Android's pause behavior follows the engine's code as written, not any observed iOS behavior.
- **The engine's clock is not "active walking time" in Android's sense.** iOS feeds the engine wall-clock time since the walk started minus *completed* pauses. A sitting is *not* subtracted: meditation is not a builder status (§7, §14). Android's `WalkStats.activeWalkingMillis` subtracts meditation, so it is the wrong source for the engine clock.

---

### 1. The location feed: from GPS to the engine

#### 1.1 Honor and Seek read the same filtered `$currentLocation`

Both engines subscribe to the view model's `@Published currentLocation`. It is fed from `liveStats.currentLocation`, which carries `LocationManagement`'s `currentLocationRelay` through the builder.

```swift
    /// The walker's fixes as `CLLocation`s. Not private: the mark pins in
    /// `ActiveWalkViewModel+MarkPins.swift` follow the same stream rather
    /// than re-deriving coordinates from the raw sample.
    var honorLocationFixes: AnyPublisher<CLLocation, Never> {
        $currentLocation
            .compactMap { sample -> CLLocation? in
                guard let sample else { return nil }
                return CLLocation(
                    coordinate: CLLocationCoordinate2D(latitude: sample.latitude, longitude: sample.longitude),
                    altitude: sample.altitude, horizontalAccuracy: sample.horizontalAccuracy,
                    verticalAccuracy: sample.verticalAccuracy, course: sample.direction,
                    speed: sample.speed, timestamp: sample.timestamp)
            }
            .eraseToAnyPublisher()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:439-453@7c200bf

```swift
    private var seekLocationFixes: AnyPublisher<CLLocation, Never> {
        $currentLocation
            .compactMap { sample -> CLLocation? in
                guard let sample else { return nil }
                return CLLocation(
                    coordinate: CLLocationCoordinate2D(latitude: sample.latitude, longitude: sample.longitude),
                    altitude: sample.altitude,
                    horizontalAccuracy: sample.horizontalAccuracy,
                    verticalAccuracy: sample.verticalAccuracy,
                    course: sample.direction,
                    speed: sample.speed,
                    timestamp: sample.timestamp
                )
            }
            .eraseToAnyPublisher()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Seek.swift:292-306@7c200bf

```swift
        // Locations mirror liveStats.currentLocation (the same feed
        // ProximityDetectionService binds to) via the published mirror, so
        // tests can drive the engine by writing `currentLocation`.
        engine.bind(
            locations: seekLocationFixes,
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Seek.swift:93-97@7c200bf

```swift
        liveStats.currentLocation
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.currentLocation = $0 }
            .store(in: &cancellables)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:345-348@7c200bf

The conversion keeps accuracy, speed, course, and timestamp, so the engine's accuracy and speed gates see the device's own values (`CLLocation.asTemp` copies every field: `Pilgrim/Extensions/CLLocation.swift:54-66@7c200bf`).

Confirmed: iOS feeds Honor and Seek one and the same filtered stream. Android's Seek reads `rawLocationFlow()` (no 20 m gate), which is the divergence the plan already records for U25 (`app/src/main/java/org/walktalkmeditate/pilgrim/location/FusedLocationSource.kt:55-62@5ea4029b`).

#### 1.2 What `LocationManagement` publishes, by walk status

Before the walk records (status `.waiting` or `.ready`), every delegate callback publishes its **last raw location, unfiltered**:

```swift
    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        updateDesiredAccuracy(from: locations)

        let status = builder?.status ?? .waiting

        guard status.isActiveStatus else {
            if let lastLocation = locations.last {
                currentLocationRelay.accept(lastLocation.asTemp)
            }
            let isReady = locations.contains { checkForAppropriateAccuracy($0) }
            let newStatus: WalkBuilderComponentStatus = isReady ? .ready(LocationManagement.self) : .preparing(LocationManagement.self)

            guard readinessRelay.value != newStatus else { return }
            readinessRelay.accept(newStatus)
            return
        }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:261-276@7c200bf

While the walk is active (recording, paused, or auto-paused), each location in the batch passes the accuracy filter unless it is the route's first sample, then is appended and published. Pausing stops only the distance sum, never the publish:

```swift
        let shouldUpdateDistance = !status.isPausedStatus

        for location in locations {
            let isFirst = recordedSamples.isEmpty
            guard isFirst || checkForAppropriateAccuracy(location) else { continue }

            let location = refineLocation(location)
            let sample = location.asTemp
            let previousSample = recordedSamples.last
            appendRouteSample(sample)
            currentLocationRelay.accept(sample)

            guard shouldUpdateDistance, let lastLocation = previousSample else { continue }
            let newDistance = location.distance(from: lastLocation.clLocation) + distanceRelay.value
            distanceRelay.accept(newDistance)
        }
    }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:278-294@7c200bf

```swift
        /// a boolean indicating whether the status is a paused status
        public var isPausedStatus: Bool {
            return [.paused, .autoPaused].contains(self)
        }
        
        /// a boolean indicating whether the status is an active status meaning data is recorded while one of these status is the current one
        public var isActiveStatus: Bool {
            return [.recording, .paused, .autoPaused].contains(self)
        }
```
> Pilgrim/Models/Walk/WalkBuilder/WalkBuilder+Status.swift:74-82@7c200bf

When the walk turns active, the last pre-walk fix becomes the route's first sample. It is not republished. So `isFirst` is usually false for every later fix whenever a pre-walk fix existed. The exception: a delegate batch that lands between the builder's status flip and this main-queue sink sees an empty route and takes the `isFirst` pass itself.

```swift
        builder.statusPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] status in
                guard let self, status.isActiveStatus else { return }
                self.locationManager.startUpdatingLocation()
                if self.recordedSamples.isEmpty, let current = self.currentLocationRelay.value {
                    self.appendRouteSample(current)
                }
            }
            .store(in: &cancellables)
```
> Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:223-232@7c200bf

Consequence for the engine: `$currentLocation` replays its current value on subscribe, so **the engine's first fix is usually the last pre-Begin fix, which never passed the 20 m filter**. Only the engine's own 50 m gate (§3) applies to it. Android's gated collector gives its first fix the same pass (`FusedLocationSource.kt:112-120@5ea4029b`, the `isFirst` port).

#### 1.3 The accuracy filter and its adaptive threshold

```swift
    private func checkForAppropriateAccuracy(_ location: CLLocation) -> Bool {
        guard let desiredAccuracy = self.desiredAccuracy else { return true }
        return location.horizontalAccuracy < 100 && location.horizontalAccuracy <= desiredAccuracy
    }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:55-58@7c200bf

Starting value, set once in `prepare()`: the preference if set, else 20 m; the preference value `-1` ("Off") leaves `desiredAccuracy` nil, and then every fix passes.

```swift
        if UserPreferences.gpsAccuracy.value != -1 {
            self.desiredAccuracy = UserPreferences.gpsAccuracy.value ?? 20
        }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:242-244@7c200bf

Adaptation, run on every delegate batch (before filtering, so rejected fixes count too) and only while the preference is nil:

```swift
    private func updateDesiredAccuracy(from locations: [CLLocation]) {

        guard UserPreferences.gpsAccuracy.value == nil, !locations.isEmpty else { return }

        var averageAccuracy: Double = 0
        for (index, location) in locations.enumerated() {
            let index = Double(index)
            averageAccuracy = ( averageAccuracy * index + location.horizontalAccuracy ) / ( index + 1 )
        }

        let globalCount = Double(min(self.recordedSamples.count, 9))
        let localCount = Double(locations.count)
        
        self.averageAccuracy = (self.averageAccuracy * globalCount + averageAccuracy * localCount) / (globalCount + localCount)
        self.desiredAccuracy = min(self.averageAccuracy.rounded(decimalPlaces: -1, rule: .up), 20)
    }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:64-79@7c200bf

```swift
    func rounded(decimalPlaces: Int = 0, rule: FloatingPointRoundingRule) -> Double {
        
        let roundingFactor = Double(pow(10, Double(decimalPlaces)))
        
        return ( self * roundingFactor ).rounded(rule) / roundingFactor
        
    }
```
> Pilgrim/Extensions/Double.swift:42-48@7c200bf

How it adapts:
- The batch mean is blended with the running mean, weighting the running mean by `min(recordedSamples.count, 9)`. Before any route sample exists the weight is 0, so pre-walk the threshold follows the latest batch alone. From the tenth sample on, with one fix per batch, it is a moving average with weight 1/10 on the new fix.
- `rounded(decimalPlaces: -1, rule: .up)` rounds up to the next multiple of 10 m, then the result is capped at 20 m. In practice the threshold is **10 m** while the mean accuracy is at or under 10 m, and **20 m** otherwise. It never rises above 20 m on its own.
- Because the mean moves slowly, a sudden loss of accuracy under trees drops fixes of 10–20 m accuracy until the mean climbs past 10 m.
- Negative (invalid) accuracies pass `checkForAppropriateAccuracy` (`-1 < 100 && -1 <= 20`); the engine's own `accuracy >= 0` guard drops them (§3).

The GPS-accuracy preference override: `UserPreferences.gpsAccuracy` (key `"gpsAccuracy"`, initial nil) with choices Standard (nil), 20, 30, 50, and Off (-1). A chosen value disables adaptation and fixes the threshold there, with the hard `< 100` ceiling still applied; Off disables the filter.

```swift
    static let gpsAccuracy = UserPreference.Optional<Double>(key: "gpsAccuracy", initialValue: nil)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:32@7c200bf

```swift
                                        selectionSetting(withTitle: LS["Settings.GPSAccuracy.Standard"], value: nil),
                                        selectionSetting(withTitle: LS["Settings.GPSAccuracy.High"], value: 20),
                                        selectionSetting(withTitle: LS["Settings.GPSAccuracy.Acceptable"], value: 30),
                                        selectionSetting(withTitle: LS["Settings.GPSAccuracy.LastResort"], value: 50),
                                        selectionSetting(withTitle: LS["Settings.GPSAccuracy.Off"], value: -1)
```
> Pilgrim/Models/Settings/SettingsModel.swift:189-193@7c200bf

That picker lives only in the legacy `SettingsModel.main`, which nothing at the pin references (`git grep "SettingsModel.main"` finds only its definition at `SettingsModel.swift:81`; no scene constructs a `SettingsModel`). No shipped iOS screen sets the preference, so every iOS walker gets the adaptive path. Android's fixed 20 m gate (`FusedLocationSource.kt:185-199,211-213@5ea4029b`) equals iOS's cap. It accepts 10–20 m fixes that iOS drops while iOS's mean is at or under 10 m.

#### 1.4 Other things that change the fix stream

The session guard's GPS power tiers change the fix rate the engine sees. A sitting switches the location manager to 100 m accuracy with a 50 m distance filter:

```swift
        var gpsAccuracy: CLLocationAccuracy {
            switch self {
            case .normal:     return kCLLocationAccuracyBest
            case .meditation: return 100
            case .low:        return kCLLocationAccuracyNearestTenMeters
            case .critical:   return kCLLocationAccuracyNearestTenMeters
            }
        }

        var distanceFilter: CLLocationDistance {
            switch self {
            case .normal:     return kCLDistanceFilterNone
            case .meditation: return 50
            case .low:        return 10
            case .critical:   return 10
            }
        }
```
> Pilgrim/Models/Walk/WalkSessionGuard.swift:57-73@7c200bf

```swift
        let newTier: PowerTier
        if (batteryLevel >= 0 && batteryLevel <= 0.05) || thermalState == .serious || thermalState == .critical {
            newTier = .critical
        } else if batteryLevel >= 0 && batteryLevel <= 0.20 {
            newTier = .low
        } else if isMeditating {
            newTier = .meditation
        } else {
            newTier = .normal
        }
```
> Pilgrim/Models/Walk/WalkSessionGuard.swift:237-246@7c200bf

So a seated walker receives almost no fixes (50 m distance filter), and those it does receive must still pass the at-most-20 m filter. Android's request is fixed at 2 s / 1 s with no tiers (`FusedLocationSource.kt:231-234@5ea4029b`).

Background suspension never stops a live walk. It applies only when the walk is not active:

```swift
    func didUpdateApplicationState(to state: ApplicationState) {
        self.uiSuspensionRelay.accept(state == .background)
        guard !self.statusRelay.value.isActiveStatus else { return }
        self.suspensionRelay.accept(state == .background)
    }
```
> Pilgrim/Models/Walk/WalkBuilder/WalkBuilder.swift:518-522@7c200bf

#### 1.5 Delivery and threading

Every hop is asynchronous and order-preserving: the location manager delegate on main → `asBackgroundPublisher()` (unbounded buffer, then a shared background queue) into the builder and `LiveStats` → `.receive(on: DispatchQueue.main)` into `currentLocation` → the engine's own `.receive(on: DispatchQueue.main)`.

```swift
    func asBackgroundPublisher() -> AnyPublisher<Output, Failure> {
        return self
            .buffer(size: .max, prefetch: .keepFull, whenFull: .dropOldest)
            .receive(on: sharedBackgroundQueue)
            .eraseToAnyPublisher()
    }
```
> Pilgrim/Extensions/Combine/Publisher.swift:41-46@7c200bf

Each accepted fix reaches `processLocation` exactly once, in order. Nothing in the chain drops or dedups fixes. At walk end the builder resets `currentLocationRelay` to nil (`WalkBuilder.swift:458@7c200bf`), which `compactMap` swallows, and by then `stop()` has already torn the engine down (§12.4).

---
### 2. The engine's inputs, outputs, and state

#### 2.1 Outputs: two phases, eight events, six published values

```swift
enum HonorPhase: Equatable { case walking, arrived }

enum HonorEngineEvent: Equatable {
    case momentReached(WayMoment)
    case voiceStart(WayMoment)
    case voicePause
    case voiceResume
    case voiceDropped(WayMoment)
    case softTap(offWayMeters: Double)
    case markAhead(mark: WayMark, meters: Double)
    case arrived(theirSeconds: Double, yourSeconds: Double)
}
```
> Pilgrim/Models/Honor/HonorEngine.swift:5-16@7c200bf

```swift
    @Published private(set) var progressFrac: Double = 0
    @Published private(set) var distanceRemainingMeters: Double
    @Published private(set) var offWayMeters: Double = 0
    @Published private(set) var isOnWay = false
    @Published private(set) var companionFrac: Double = 0
    @Published private(set) var phase: HonorPhase = .walking

    private(set) var startFrac: Double?
    private(set) var companionT0: Double = 0
    /// Along-Way credit toward arrival: progress earned on the Way through
    /// windowed fixes, plus a re-acquire's jump capped at the Way's own
    /// pace. GPS jitter is credited once, never cumulatively.
    var distanceWalkedMeters: Double {
        walkedFrac * geometry.totalMeters
    }
    /// Whether the walker ever actually joined the Way. Begin's frac-0
    /// fallback (nothing within 60 m) is an approach, not a joining, and a
    /// stage the walker never joined earns no ledger entry.
    var isAnchoredOnWay: Bool { startFrac != nil && !anchoredByFallback }
```
> Pilgrim/Models/Honor/HonorEngine.swift:27-45@7c200bf

Events go out through a `PassthroughSubject`, synchronously, from inside the call that produced them. The engine persists nothing: "Session engine for an honor walk … Persists nothing." (`HonorEngine.swift:18-20@7c200bf`).

#### 2.2 Private state

```swift
    private var progressHighWater: Double = 0
    /// Arrival credit in frac: increments of the high-water mark from
    /// on-Way fixes, plus pace-bounded credit for a re-acquire. Reset at
    /// anchor and re-anchor.
    private var walkedFrac: Double = 0

    private let now: () -> Date
    /// Internal, not private: tests confirm the view model computed the
    /// right value for a stage vs. an own walk without re-deriving it.
    let softTapEnabled: Bool
    private let subject = PassthroughSubject<HonorEngineEvent, Never>()
    private var cancellables: [AnyCancellable] = []
    private var arrival: ArrivalDebounce
    private var moments: HonorMomentTracker
    private var gates = HonorMomentTracker.Gates()
    private var activeDuration: TimeInterval = 0
    /// Begin found nothing within 60 m and fell back to frac 0; the first
    /// on-Way fix becomes the real anchor.
    private var anchoredByFallback = false
    /// The walk's active duration at the moment the Way was (re-)anchored.
    /// Both the companion's clock and `yourSeconds` are measured from here,
    /// so an approach walk to the trailhead neither puts the companion
    /// hundreds of metres ahead nor counts toward the walker's own time.
    private var anchorActiveDuration: TimeInterval = 0
    private var offWaySince: Date?
    /// Active duration when the walker went off the Way, so a re-acquire's
    /// pace credit is earned on walking time — not on time spent paused.
    private var offWayActiveDuration: TimeInterval = 0
    private var lastReacquireAttempt: Date?
    private var softTapSince: Date?
    private var softTapArmed = true
```
> Pilgrim/Models/Honor/HonorEngine.swift:48-78@7c200bf

For U17's session row this is the complete engine state to persist for a revival: `startFrac`, `anchoredByFallback`, `progressFrac`, `progressHighWater`, `walkedFrac`, `anchorActiveDuration`, `companionT0`, `offWaySince`, `offWayActiveDuration`, `lastReacquireAttempt`, `softTapSince`, `softTapArmed`, `phase`, the debounce's `consecutiveInside`, and the tracker's `reached`, `queue`, `playing`, `isVoicePaused`, `firedMarks`, `lastMarkSeconds` (§8). `isOnWay`, `offWayMeters`, `distanceRemainingMeters`, and `companionFrac` are recomputed on the next fix or tick. iOS itself persists none of this: after a crash, the checkpoint keeps only `wayId`, `progressFrac`, and `arrived` (§12.5).

#### 2.3 Construction

```swift
    init(way: Way, softTapEnabled: Bool, voicesEnabled: Bool, now: @escaping () -> Date = { Date() }) {
        self.way = way
        self.geometry = WayGeometry(route: way.route)
        self.now = now
        self.softTapEnabled = softTapEnabled
        self.events = subject.eraseToAnyPublisher()
        self.distanceRemainingMeters = geometry.totalMeters
        self.arrival = ArrivalDebounce(requiredFixes: HonorTuning.arrivalFixCount,
                                       accuracyMeters: HonorTuning.arrivalAccuracyMeters)
        self.moments = HonorMomentTracker(moments: way.moments, marks: way.marks ?? [],
                                          geometry: geometry, voicesEnabled: voicesEnabled)
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:80-91@7c200bf

The view model builds it at Begin, with the two preference-derived flags:

```swift
    func startHonorEngineIfNeeded() {
        guard mode == .honor, let way, honorEngine == nil else { return }
        // Nothing queued before Begin belongs to this walk.
        honorCards.removeAll()
        honorGeneration += 1
        let generation = honorGeneration
        let engine = HonorEngine(
            way: way,
            // A stage has no other walker to be off the way *from*; the soft
            // tap and the companion dot are both about someone else.
            softTapEnabled: UserPreferences.honorSoftTapEnabled.value && !way.isPilgrimageStage,
            voicesEnabled: UserPreferences.honorVoicesEnabled.value && UserPreferences.soundsEnabled.value
        )
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:47-59@7c200bf

```swift
    static let soundsEnabled = UserPreference.Required<Bool>(key: "soundsEnabled", defaultValue: true)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:36@7c200bf

```swift
    static let honorVoicesEnabled = UserPreference.Required<Bool>(key: "honorVoicesEnabled", defaultValue: true)
    static let honorSoftTapEnabled = UserPreference.Required<Bool>(key: "honorSoftTapEnabled", defaultValue: false)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:77-78@7c200bf

Both flags are read once, at Begin, and frozen for the walk, which fits the plan's "settings ride ACTION_START" design. The soft tap is **off by default**. Own walks are never stages, so for this slice `softTapEnabled == honorSoftTapEnabled`.

Called only from `startRecording()`, after the status is set and the marker event is written:

```swift
    func startRecording() {
        proximityService.resetSession()
        builder.setStatus(.recording)
        writeSeekMarkerEventIfNeeded()
        writeHonorMarkerEventIfNeeded()
        startHonorEngineIfNeeded()
        soundManagement.onWalkStart()
        startVoiceGuideIfEnabled()
        WalkActivityManager.shared.start(walkStartDate: Date(), intention: intention)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:380-389@7c200bf

#### 2.4 Inputs: `bind`

```swift
    func bind(
        locations: AnyPublisher<CLLocation, Never>,
        activeDuration: AnyPublisher<TimeInterval, Never>,
        isPaused: AnyPublisher<Bool, Never>,
        isMeditating: AnyPublisher<Bool, Never>,
        isRecordingVoice: AnyPublisher<Bool, Never>,
        externalAudio: AnyPublisher<Bool, Never>
    ) {
        cancellables.removeAll()
        locations.receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.processLocation($0) }.store(in: &cancellables)
        activeDuration.receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.updateActiveDuration($0) }.store(in: &cancellables)
        // combineLatest waits for all four inputs; callers bind @Published
        // projections, which emit on subscribe, so the gates are live at once.
        isPaused.combineLatest(isMeditating, isRecordingVoice, externalAudio)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] paused, meditating, recording, audio in
                self?.setGates(paused: paused, meditating: meditating, recording: recording, externalAudio: audio)
            }
            .store(in: &cancellables)
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:95-116@7c200bf

What the view model binds:

```swift
        engine.bind(
            locations: honorLocationFixes,
            activeDuration: $activeDurationSeconds.eraseToAnyPublisher(),
            isPaused: $status.map { $0 != .recording }.eraseToAnyPublisher(),
            isMeditating: $isMeditating.eraseToAnyPublisher(),
            isRecordingVoice: $isRecordingVoice.eraseToAnyPublisher(),
            externalAudio: AudioPriorityQueue.shared.$isPlayingWhisper.eraseToAnyPublisher()
        )
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:74-81@7c200bf

Gate inputs, exactly:
- `paused` = the view model's status is anything other than `.recording`. Right after Begin the view model's `status` may still read `.ready`, because it arrives through a main-queue hop (`ActiveWalkViewModel.swift:313-316@7c200bf`). So the gates start closed and open a moment later. A sitting leaves status at `.recording`, so **a sitting is not "paused"**.
- `meditating` = `isMeditating` (§14).
- `recording` = the recorder's `isRecording`, mirrored through `bindTimers` (`ActiveWalkViewModel.swift:526-529@7c200bf`).
- `externalAudio` = a community whisper is playing (`AudioPriorityQueue.isPlayingWhisper`).
- **The voice guide is not an engine gate.** The engine will emit `voiceStart` while a guide prompt plays. `WayVoicePlayer` then holds the voice as `pending` until the prompt ends (`WayVoicePlayer.swift:58-64@7c200bf`). For Android that means the guide's gate belongs to the audio arbiter (U18), not to `HonorEngine`.

`@Published` emits on every assignment, even of an equal value, so `setGates` may be called repeatedly with the same gates. §8 shows it is idempotent.

Other entry points: `updateActiveDuration(_:)`, `setGates(...)`, `voiceDidFinish()`, `processLocation(_:)`, `stop()`:

```swift
    func stop() {
        cancellables.removeAll()
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:118-120@7c200bf

```swift
    func setGates(paused: Bool, meditating: Bool, recording: Bool, externalAudio: Bool) {
        gates = HonorMomentTracker.Gates(paused: paused, meditating: meditating,
                                         recording: recording, externalAudio: externalAudio)
        emit(moments.gatesDidChange(gates))
    }

    func voiceDidFinish() {
        emit(moments.voiceDidFinish(gates: gates))
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:138-146@7c200bf

---

### 3. One fix, step by step (the exact order)

```swift
    func processLocation(_ location: CLLocation) {
        let accuracy = location.horizontalAccuracy
        guard accuracy >= 0, accuracy <= HonorTuning.fixAccuracyMeters else { return }
        let coordinate = location.coordinate

        if startFrac == nil { anchor(at: coordinate) }
        track(coordinate)
        distanceRemainingMeters = (1 - progressFrac) * geometry.totalMeters
        evaluateSoftTap()
        evaluateArrival(location)

        let stationary = location.speed >= 0 && location.speed < HonorTuning.stationarySpeed
        emit(moments.update(location: coordinate, progressFrac: progressFrac, gates: gates,
                            isStationary: stationary, activeSeconds: activeDuration, isOnWay: isOnWay))
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:148-162@7c200bf

For each fix, in this order:

1. **Accuracy gate.** Drop the fix unless `0 <= horizontalAccuracy <= 50`. A dropped fix changes nothing: no anchor, no timer, no debounce step. (Android: `horizontalAccuracyMeters == null` has no iOS counterpart. Android Seek treats null as failing, `SeekEngine.kt:314-318@5ea4029b`, and iOS's nearest equivalent, an invalid negative accuracy, fails too.)
2. **Anchor** if nothing is anchored yet (§4).
3. **Track**: progress, off-way state, re-acquire (§5, §6).
4. **Distance remaining** = `(1 - progressFrac) × totalMeters`.
5. **Soft tap**: may emit `.softTap` (§10).
6. **Arrival**: may set `phase = .arrived`, then emit `.arrived` (§11).
7. **Moments**: `stationary` = `speed >= 0 && speed < 0.4`; an unknown speed (`-1`) counts as moving. The tracker's actions are then emitted in this order (§8): every `.momentReached` in frac order (ties by id), then `.voiceDropped` for each queued voice left more than 300 m behind (in queue order), then `.voiceDropped` for the paused current voice, then `.markAhead` (stages only), then at most one `.voiceStart`.

So on the fix that completes arrival, `.softTap` (if due) comes first, then `.arrived`, then that fix's moment events. **Nothing stops after arrival except the soft tap and arrival itself.** Tracking, moments, voices, and the companion all continue.

```swift
    private func emit(_ actions: [HonorMomentTracker.Action]) {
        for action in actions {
            switch action {
            case .reached(let moment): subject.send(.momentReached(moment))
            case .voiceStart(let moment): subject.send(.voiceStart(moment))
            case .voicePause: subject.send(.voicePause)
            case .voiceResume: subject.send(.voiceResume)
            case .voiceDropped(let moment): subject.send(.voiceDropped(moment))
            case .markAhead(let mark, let meters): subject.send(.markAhead(mark: mark, meters: meters))
            }
        }
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:312-323@7c200bf

Re-entrancy: the tracker computes its whole action list before `emit` runs. The view model's `.voiceStart` handler can call `voiceDidFinish()` synchronously when the file is missing (§12.2), and so can the player's `finish(notify: true)` when `AVAudioPlayer.play()` fails (`WayVoicePlayer.swift:167-170@7c200bf`). That nested call runs a fresh `voiceDidFinish` → `startNextIfPossible` inside the outer loop. The outer list is already fixed, so no state is lost, but a nested `.voiceStart` for the next voice can reach the view model before the outer loop's remaining events. Android's session should keep the same order: engine state updated first, then events handled in list order.

---

### 4. Anchoring onto the Way (Begin, the fallback, the re-anchor)

```swift
    private func anchor(at coordinate: CLLocationCoordinate2D) {
        let hit = geometry.lowestFrac(within: HonorTuning.onWayMeters, of: coordinate)?.frac
        anchoredByFallback = hit == nil
        let frac = hit ?? 0
        startFrac = frac
        progressFrac = frac
        progressHighWater = frac
        walkedFrac = 0
        anchorActiveDuration = activeDuration
        companionT0 = geometry.elapsed(atFrac: frac)
        companionFrac = geometry.frac(atElapsed: companionT0)
    }

    private func reanchor(at frac: Double) {
        anchoredByFallback = false
        startFrac = frac
        progressHighWater = frac
        walkedFrac = 0
        anchorActiveDuration = activeDuration
        companionT0 = geometry.elapsed(atFrac: frac)
        companionFrac = geometry.frac(atElapsed: companionT0)
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:166-187@7c200bf

- The first fix that passes the 50 m gate anchors. The anchor is the **lowest frac within 60 m**: the closest point in the first contiguous run of segments within 60 m, scanning from frac 0 (§16.2 has `lowestFrac`). On an out-and-back or a loop this takes the outbound leg.
- If nothing is within 60 m: frac 0, `anchoredByFallback = true`. While the fallback holds, the companion waits at the start (§7), the soft tap is silent (§10), and `isAnchoredOnWay` is false.
- A re-anchor happens (a) on the first on-Way fix inside the tracking window (§5) or (b) on a successful re-acquire (§6), but only while `anchoredByFallback` is true. It resets `startFrac`, the high-water mark, arrival credit (`walkedFrac = 0`), and both clocks (`anchorActiveDuration`, `companionT0`). It does *not* reset `progressFrac`: the caller has already set it.
- Because the fallback's window is `[0, 300 m]`, a walker who joins the Way more than 300 m from its start is still "off the Way" to `track()`. They re-anchor only through a re-acquire, 120 s later (§6).

iOS tests: `testAnchorsAtLowestFracAndFollowsTheOutboundLeg` (anchor 0.05 on the out-and-back, not 0.95), `testFarFromTheWayAnchorsAtZeroAndIsOffWay`, `testNoisyBeginReanchorsOnTheFirstOnWayFix`, `testInaccurateFixesAreIgnored` (a 60 m fix does not anchor; a 20 m one does) — `UnitTests/Honor/HonorEngineTests.swift:37-54,135-164@7c200bf`.

---

### 5. Progress along the Way (the window)

```swift
    private func track(_ coordinate: CLLocationCoordinate2D) {
        let windowSpan = geometry.totalMeters > 0 ? HonorTuning.windowMeters / geometry.totalMeters : 1
        let lower = max(0, progressFrac - HonorTuning.backwardTolerance)
        let upper = min(1, progressFrac + windowSpan)
        let local = geometry.nearest(to: coordinate, within: lower...upper)
        // `nearest` returns .infinity when the window holds no segment (a
        // degenerate Way); clamped so the value stays printable and never
        // reaches `Int(_:)` as an infinity.
        offWayMeters = Self.clampedMeters(local.meters)
        if local.meters <= HonorTuning.onWayMeters {
            isOnWay = true
            offWaySince = nil
            lastReacquireAttempt = nil
            progressFrac = local.frac   // nearest already clamps into the window
            if anchoredByFallback {
                reanchor(at: progressFrac)
            } else {
                walkedFrac += max(0, progressFrac - progressHighWater)
                progressHighWater = max(progressHighWater, progressFrac)
            }
            return
        }
```
> Pilgrim/Models/Honor/HonorEngine.swift:189-210@7c200bf

Rules:
- The window runs from **0.02 of the Way's length behind** the current progress (a frac, not metres: 20 m on a 1 km Way, 400 m on 20 km) to **300 m ahead** (converted to a frac). On a Way of 300 m or less the window reaches the end.
- `nearest` projects onto each segment that overlaps the window, clamped to the part inside the window (§16.2). If the projection is within 60 m, the walker is on the Way and **progress jumps to that projection**, backward (to the window's lower edge at most) or forward (to its upper edge at most).
- Walking backward moves progress back by at most 0.02 per fix, so over several fixes progress follows a walker who turns around. The high-water mark never falls, and `walkedFrac` grows only when a fix beats the high-water mark. Walking back and forth never earns credit twice.
- `offWayMeters` is the distance to the *windowed* nearest point, not to the whole Way.
- When off the Way (more than 60 m), `progressFrac` stays where it was.

```swift
    /// A Way is at most a few hundred kilometres; anything past this is a
    /// sentinel or a bad fix, and every consumer formats it as a number.
    private static let maxReportedOffWayMeters: Double = 100_000

    private static func clampedMeters(_ meters: Double) -> Double {
        meters.isFinite ? min(meters, maxReportedOffWayMeters) : maxReportedOffWayMeters
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:255-261@7c200bf

iOS tests: `testProgressNeverMovesBackBeyondTheTolerance` (one fix 50 m back lands exactly at `0.4 - backwardTolerance`), `testStationaryJitterNeverAdvancesTheWalk` (600 fixes of ±33 m jitter at the 500 m mark earn under 50 m) — `HonorEngineTests.swift:166-200@7c200bf`.

---

### 6. Off the Way and the re-acquire

```swift
        isOnWay = false
        let time = now()
        if offWaySince == nil {
            offWaySince = time
            offWayActiveDuration = activeDuration
        }
        let dueForRetry = lastReacquireAttempt
            .map { time.timeIntervalSince($0) >= HonorTuning.reacquireRetrySeconds } ?? true
        if let since = offWaySince, time.timeIntervalSince(since) >= HonorTuning.reacquireSeconds, dueForRetry {
            lastReacquireAttempt = time
            // Forward first: on an out-and-back the return leg shares the
            // outbound leg's pavement, and the global lowest frac would drag
            // progress back to the outbound leg with arrival then impossible.
            let ahead = geometry.lowestFrac(within: HonorTuning.onWayMeters, of: coordinate, from: lower)
            if let found = ahead ?? geometry.lowestFrac(within: HonorTuning.onWayMeters, of: coordinate) {
                // Credit the jump, but no faster than the Way was walked:
                // an honest walker off-signal through a corner earns the
                // stretch; a car cannot outrun the Way's own pace. When
                // Begin fell back to frac 0, the re-anchor that follows
                // resets the credit: the walk had not really begun.
                if geometry.totalSeconds > 0 {
                    let jump = max(0, found.frac - progressHighWater)
                    // Walking time off the Way, not wall clock: a walk paused
                    // for an hour off-Way must not convert that hour into
                    // arrival credit.
                    let offWayWalking = max(0, activeDuration - offWayActiveDuration)
                    let paceFrac = offWayWalking / geometry.totalSeconds
                    walkedFrac += max(0, min(jump, paceFrac))
                }
                progressFrac = found.frac
                progressHighWater = max(progressHighWater, progressFrac)
                offWayMeters = Self.clampedMeters(found.meters)
                isOnWay = true
                offWaySince = nil
                lastReacquireAttempt = nil
                if anchoredByFallback { reanchor(at: found.frac) }
            }
            // A failed re-acquire retries every 10 s, not on every fix:
            // lowestFrac is a linear scan of the whole Way (up to 4,000 points).
        }
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:211-251@7c200bf

State machine for off-Way:
- First off-Way fix: `offWaySince = now()` (wall clock), `offWayActiveDuration = activeDuration`.
- A re-acquire is tried only on a fix, once `now() - offWaySince >= 120 s`, and then no more than once every 10 s (`lastReacquireAttempt`).
- The search looks forward first, from the window's lower edge (`lowestFrac(..., from: lower)`), then over the whole Way from frac 0. The global fallback can move progress **backward** to an earlier leg; the high-water mark stays.
- Credit for the jump: `min(found.frac - highWater, offWayWalking / totalSeconds)`, at least 0. `offWayWalking` uses the engine clock (`activeDuration`), not the wall clock. `totalSeconds` is the Way's own duration, so the cap is the Way's *average* pace. A Way with `totalSeconds == 0` gets no credit.
- On success: on the Way, all off-Way timers cleared, `offWayMeters` = the found distance; then a re-anchor if the Begin was a fallback, which zeroes the credit just added.
- Returning within 60 m inside the window at any time (§5) also clears `offWaySince` and `lastReacquireAttempt`, with no credit cap: normal windowed progress.

iOS tests: `testReacquiresAfterSustainedOffWay`, `testReacquireOnTheReturnLegNeverFallsBackToTheOutboundLeg`, `testReacquireIsCreditedAtTheWaysOwnPace` (a 600 m jump after 140 s is credited 233 m), `testHonestOffSignalStretchIsCreditedInFull`, `testReacquireCannotOutrunTheWaysPace`, `testPausedTimeOffTheWayEarnsNoPaceCredit` — `HonorEngineTests.swift:117-133,145-156,223-295,386-407@7c200bf`.

---

### 7. The companion clock

```swift
    func updateActiveDuration(_ seconds: TimeInterval) {
        activeDuration = seconds
        // While the fallback anchor (Begin found no Way within 60 m) is
        // still in effect, the companion has nowhere real to walk to yet —
        // it waits at the start until the first on-Way fix re-anchors it.
        guard startFrac != nil, !anchoredByFallback else { return }
        companionFrac = geometry.frac(atElapsed: companionT0 + sinceAnchorSeconds)
    }

    /// Walking time since the Way was anchored. Never negative: the walk's
    /// active duration is monotonic, but a re-anchor could otherwise race a
    /// stale emission.
    private var sinceAnchorSeconds: TimeInterval { max(0, activeDuration - anchorActiveDuration) }
```
> Pilgrim/Models/Honor/HonorEngine.swift:124-136@7c200bf

- `companionT0` = the Way's own elapsed seconds at the anchor frac (`elapsed(atFrac:)`). On a stationary plateau this is the *end* of their pause, so the companion leaves with the walker (`WayGeometry.swift:62-72@7c200bf`, §16.2).
- Companion frac = `frac(atElapsed: companionT0 + (activeDuration - anchorActiveDuration))`. It moves only on `activeDuration` emissions and at an anchor or re-anchor, never on fixes.
- Way timestamps are wall clock, "the original walker's pauses are inside it, so the companion rests where they rested" (`Way.swift:12-14@7c200bf`).

Where `activeDuration` comes from: a 1 Hz main-run-loop timer computing wall-clock time since the walk's start minus the durations of **completed** pauses:

```swift
        Timer.TimerPublisher(interval: 1, runLoop: .main, mode: .common)
            .autoconnect()
            .combineLatest(startDate, pauses)
            .combineLatest(voiceRecordings)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] timerPauses, recordings in
                guard let self else { return }
                let (_, start, pauseList) = timerPauses
                guard let start else { return }
                let pauseDuration = pauseList.map { $0.duration }.reduce(0, +)
                let activeDuration = max(0, start.distance(to: Date()) - pauseDuration)
                self.activeDurationSeconds = activeDuration
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:535-546@7c200bf

A pause is appended to `pausesRelay` only when it ends (on resume or at walk end):

```swift
            case .recording: // starting / resuming walk
                if self.startDateRelay.value == nil {
                    self.startDateRelay.accept(timestamp)
                    
                } else if let lastPause = self.lastPause {
                    self.lastPause = nil
                    if lastPause.type == .automatic, lastPause.startingAt.distance(to: timestamp) < 3 {
                        return // to eliminate short auto pauses
                    }
                    let pause = TempWalkPause(uuid: nil, startDate: lastPause.startingAt, endDate: timestamp, pauseType: lastPause.type)
                    let pauses = self.pausesRelay.value + [pause]
                    self.pausesRelay.accept(pauses)
                }
```
> Pilgrim/Models/Walk/WalkBuilder/WalkBuilder.swift:142-154@7c200bf

So:
- **A sitting is inside `activeDuration`.** The companion keeps walking while you sit, and `yourSeconds` includes the sitting. (The "Walk" readout subtracts meditation separately, `ActiveWalkViewModel.swift:554-559@7c200bf`; the engine never sees that value.)
- **If a pause were reachable,** `activeDuration` would keep rising through it and fall back by the pause's length on resume. The engine comment's claim that "the walk's active duration is monotonic" would then be false (§14, and iOS defects).
- The engine clock is up to 1 s stale relative to fixes.

Android: `WalkStats.activeWalkingMillis` subtracts pauses **and meditation**, including the one in progress (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkStats.kt:19-36@5ea4029b`). The iOS engine clock is wall time since start minus pauses only. See Resolution 4.

iOS tests: `testCompanionRunsOnActiveDurationFromTheAnchor`, `testReanchorRestartsTheCompanionClockAndYourSeconds`, `testCompanionWaitsAtTheStartDuringTheApproachWalk`, `testYourSecondsExcludesTheApproachWalk` — `HonorEngineTests.swift:56-64,302-365@7c200bf`.

---
### 8. Moments: reach, queue, start, pause, resume, drop (`HonorMomentTracker`)

#### 8.1 State and ordering

```swift
    struct Gates: Equatable {
        var paused = false
        var meditating = false
        var recording = false
        var externalAudio = false
        var isClosed: Bool { paused || meditating || recording || externalAudio }
    }

    private let moments: [WayMoment]
    private let geometry: WayGeometry
    private let voicesEnabled: Bool
    private var reached: Set<String> = []
    private var queue: [WayMoment] = []
    private(set) var playing: WayMoment?
    private(set) var isVoicePaused = false
    private let marks: [WayMark]
    private var firedMarks: Set<String> = []
    /// Active seconds at the last water caption; nil means the first is free.
    private var lastMarkSeconds: TimeInterval?

    init(moments: [WayMoment], marks: [WayMark] = [], geometry: WayGeometry, voicesEnabled: Bool) {
        // A tiebreak on id keeps ordering deterministic when two moments
        // share a frac — the same rule `WayImporter` sorts by.
        self.moments = moments.sorted { $0.frac == $1.frac ? $0.id < $1.id : $0.frac < $1.frac }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:19-42@7c200bf

Moments are sorted by `frac`, ties broken by `id` in ascending string order (Swift `String <`, which compares Unicode scalars; for the ASCII ids iOS builds this is Kotlin's `String.compareTo`). The queue is a FIFO list, never a single slot. At most one voice is `playing`.

#### 8.2 Per fix

```swift
    mutating func update(
        location: CLLocationCoordinate2D,
        progressFrac: Double,
        gates: Gates,
        isStationary: Bool,
        activeSeconds: TimeInterval = 0,
        isOnWay: Bool = true
    ) -> [Action] {
        var actions: [Action] = []
        let here = CLLocation(latitude: location.latitude, longitude: location.longitude)

        for moment in moments where !reached.contains(moment.id) {
            guard progressFrac >= moment.frac - HonorTuning.momentFracTolerance else { continue }
            let radius = moment.isVoice ? HonorTuning.voiceRadiusMeters : HonorTuning.momentRadiusMeters
            guard here.distance(from: place(of: moment)) <= radius else { continue }
            reached.insert(moment.id)
            if moment.isVoice {
                if voicesEnabled { queue.append(moment) }
            } else {
                actions.append(.reached(moment))
            }
        }

        // Abandon voices the walker has left far behind, but never while a
        // voice is playing: listening to a long musing carries the walker
        // hundreds of metres, and the next voice must still be waiting.
        if !isStationary, playing == nil || isVoicePaused {
            let dropped = queue.filter { here.distance(from: place(of: $0)) > HonorTuning.voiceDropMeters }
            queue.removeAll { dropped.contains($0) }
            actions += dropped.map { .voiceDropped($0) }
            if let current = playing, isVoicePaused,
               here.distance(from: place(of: current)) > HonorTuning.voiceDropMeters {
                playing = nil
                isVoicePaused = false
                actions.append(.voiceDropped(current))
            }
        }

        actions += waterAhead(progressFrac: progressFrac, activeSeconds: activeSeconds, isOnWay: isOnWay)
        actions += startNextIfPossible(gates: gates)
        return actions
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:53-94@7c200bf

```swift
    private func place(of moment: WayMoment) -> CLLocation {
        if let at = moment.at { return CLLocation(latitude: at.lat, longitude: at.lon) }
        let c = geometry.coordinate(atFrac: moment.frac)
        return CLLocation(latitude: c.latitude, longitude: c.longitude)
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:141-145@7c200bf

Rules:
- **Reach** requires both a frac gate (`progressFrac >= frac - 0.05`) and a straight-line distance from the fix to the moment's place: at most 42 m for a voice, at most 60 m for any other kind. The place is `at` when present, else the line's point at `frac`.
- The 0.05 tolerance is a frac of the Way (50 m on 1 km, 1 km on 20 km). It only blocks early crossings: a walker who has passed a moment still satisfies it.
- Each moment is reached at most once (`reached`). One that is never reached stays reachable for the rest of the walk, whenever the walker comes back within its radius.
- **Gates never block reaching.** Non-voice moments emit `.reached` at once, even during a sitting, a recording, or a pause. Voice moments are queued (not emitted) while gates are closed.
- **Voices off** (`honorVoicesEnabled && soundsEnabled` false): voice moments are marked reached and produce nothing, so no card, no heard mark, no event. Only non-voice cards appear (`testVoicesDisabledStillReachesCardsAndNeverStartsAudio`, `HonorMomentTrackerTests.swift:88-93@7c200bf`).
- **Drop** runs only when the walker is not stationary, and only when no voice is playing or the playing voice is paused. It removes every queued voice whose place is more than 300 m away (straight line from the fix), then the paused current voice if it too is more than 300 m away. A dropped queued voice was never started, so it leaves no card and no heard mark.
- **Start** (`startNextIfPossible`) runs last, after drops.

```swift
    mutating func gatesDidChange(_ gates: Gates) -> [Action] {
        if playing != nil {
            if gates.isClosed, !isVoicePaused {
                isVoicePaused = true
                return [.voicePause]
            }
            if !gates.isClosed, isVoicePaused {
                isVoicePaused = false
                return [.voiceResume]
            }
            return []
        }
        return startNextIfPossible(gates: gates)
    }

    mutating func voiceDidFinish(gates: Gates) -> [Action] {
        playing = nil
        isVoicePaused = false
        return startNextIfPossible(gates: gates)
    }

    private mutating func startNextIfPossible(gates: Gates) -> [Action] {
        guard playing == nil, !gates.isClosed, !queue.isEmpty else { return [] }
        let next = queue.removeFirst()
        playing = next
        return [.voiceStart(next)]
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:96-122@7c200bf

Voice state machine (tracker side):

| From | Trigger | To | Emits |
|---|---|---|---|
| idle (`playing == nil`) | fix or gate change, gates open, queue not empty | playing | `voiceStart(head)` |
| playing | gates close | playing + paused | `voicePause` |
| playing + paused | gates open | playing | `voiceResume` |
| playing + paused | fix, moving, more than 300 m from its place | idle | `voiceDropped(current)` |
| playing (either) | `voiceDidFinish()` | idle, then maybe playing | `voiceStart(next)` if gates open |
| idle | `voiceDidFinish()` | idle | nothing (inert: `testVoiceDidFinishWithNothingPlayingIsInert`) |

A gate change while a voice plays never starts another voice. A voice that is playing and *not* paused is never dropped, however far the walker walks (`testQueuedVoiceIsKeptWhileAnotherVoicePlays`).

The view model calls `voiceDidFinish()` in three places: the player's natural end (`onFinished`), `skipVoice()`, and a voice whose file is missing (§12).

#### 8.3 A walker's own pause is invisible to the tracker

Pausing a voice from the card toggles only the view model and the player:

```swift
    func togglePlayback(of moment: WayMoment) {
        guard let player = wayVoicePlayer else { return }
        if moment == activeVoice {
            if isVoicePaused { player.resume() } else { player.pause() }
            isVoicePaused.toggle()
            return
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:389-395@7c200bf

So while the walker holds a voice paused, the tracker still counts it as playing and unpaused: queued voices are never dropped and never start. The next gate cycle then resumes the voice the walker paused (see iOS defects).

---

### 9. Water ahead (stages only)

Branch point: `waterAhead` returns nothing unless the Way has on-Way water marks. `way.marks` is nil for own walks, so the list is empty:

```swift
        self.marks = marks
            .filter { $0.kind == .water && $0.offLineMeters <= HonorTuning.onWayMeters }
            .sorted { $0.frac < $1.frac }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:46-48@7c200bf

```swift
    private mutating func waterAhead(progressFrac: Double, activeSeconds: TimeInterval, isOnWay: Bool) -> [Action] {
        guard isOnWay, !marks.isEmpty, geometry.totalMeters > 0 else { return [] }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:127-128@7c200bf

The rest (300 m ahead, one per 3,600 active seconds, the first free) belongs to the stage spec. The mark-pin reselection (`HonorTuning.markPinRefreshMeters`, `ActiveWalkViewModel+MarkPins.swift:13-30@7c200bf`) is stage-only too. It rides the same `honorLocationFixes` stream and is a no-op when `way.marks` is empty.

---

### 10. The soft tap

```swift
    private func evaluateSoftTap() {
        // Nothing has been joined yet: Begin found no Way within 60 m and
        // fell back to frac 0, so the walker is still approaching. Tapping
        // them on the shoulder for being "off the way" they have not
        // started is the wrong word at the wrong time.
        guard !anchoredByFallback else { return }
        guard phase == .walking, softTapEnabled else { return }
        if offWayMeters <= HonorTuning.onWayMeters {
            softTapSince = nil
            softTapArmed = true
            return
        }
        guard softTapArmed, offWayMeters > HonorTuning.softTapMeters else {
            if offWayMeters <= HonorTuning.softTapMeters { softTapSince = nil }
            return
        }
        let time = now()
        if softTapSince == nil { softTapSince = time }
        if let since = softTapSince, time.timeIntervalSince(since) >= HonorTuning.softTapSeconds {
            softTapArmed = false
            softTapSince = nil
            subject.send(.softTap(offWayMeters: offWayMeters))
        }
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:263-286@7c200bf

States: **armed** (initial) → **timing** (the first fix beyond 200 m while armed sets `softTapSince = now()`) → **fired** (a fix at least 120 s later, still beyond 200 m, emits `.softTap(offWayMeters)` and disarms). Transitions:
- A fix within 60 m clears the timer and re-arms.
- A fix between 60 m and 200 m clears the timer but keeps the armed state as it was, so the 120 s count restarts on the next fix beyond 200 m.
- Disarmed and beyond 200 m: nothing, until the walker comes back within 60 m.
- Silent while the Begin fallback holds, after arrival, and when the preference is off.
- **Not gated by pause, sitting, recording, or whisper.** It can fire during a sitting.
- It is evaluated only on accepted fixes, so it fires on the first fix after 120 s, not on a timer.
- `offWayMeters` is the windowed distance (§5), or the found distance right after a re-acquire.

iOS tests: `testSoftTapFiresOnceAfterSustainedDriftAndRearms`, `testSoftTapDisabledNeverFires`, `testSoftTapStaysQuietDuringTheApproachWalk`, `testSoftTapStopsAfterArrival` — `HonorEngineTests.swift:86-108,176-187,369-382,439-452@7c200bf`.

---

### 11. Arrival

#### 11.1 `ArrivalDebounce` (shared with Seek)

```swift
/// Consecutive-inside-fix arrival gate shared by Seek and Honor. Fixes worse
/// than the accuracy gate neither advance nor reset the count: a momentary
/// multipath fix must not erase honest progress, and must never fake it.
struct ArrivalDebounce {
    let requiredFixes: Int
    let accuracyMeters: Double
    private(set) var consecutiveInside = 0

    init(requiredFixes: Int, accuracyMeters: Double) {
        self.requiredFixes = requiredFixes
        self.accuracyMeters = accuracyMeters
    }

    /// True on the fix that completes the count.
    mutating func register(distance: Double, radius: Double, accuracy: Double) -> Bool {
        guard accuracy >= 0, accuracy <= accuracyMeters else { return false }
        consecutiveInside = distance <= radius ? consecutiveInside + 1 : 0
        return consecutiveInside >= requiredFixes
    }

    mutating func reset() { consecutiveInside = 0 }
}
```
> Pilgrim/Models/Walk/Seek/ArrivalDebounce.swift:3-24@7c200bf

```swift
    static let arrivalFixCount = 3
    static let arrivalAccuracyMeters = 50.0
```
> Pilgrim/Models/Walk/Seek/SeekEngine.swift:27-28@7c200bf

Seek's use (`SeekEngine.swift:287-293@7c200bf`) registers every guiding fix, and on arrival resets the count (`:295-297`) and on a reroll (`:153`). `register` returns true on the third inside fix and on every later one while the count holds. Both engines stop calling it once arrived. Android's inline copy matches, plus a null-accuracy skip (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekEngine.kt:79-80,313-325@5ea4029b`). iOS tests: `UnitTests/Seek/ArrivalDebounceTests.swift:6-30@7c200bf` (three inside fixes arrive; one outside resets; 120 m and −1 accuracy neither advance nor reset).

In Honor the debounce's 50 m accuracy gate is redundant: `processLocation` has already dropped every fix over 50 m (§3).

#### 11.2 Honor's gates

```swift
    private func evaluateArrival(_ location: CLLocation) {
        guard phase == .walking, let last = geometry.points.last, let start = startFrac else { return }
        // Half of the Way that lay ahead at Begin, along the Way: blocks an
        // arrival at Begin on a loop while letting a mid-Way start finish.
        // The credit is walkedFrac: progress on the Way, plus a
        // re-acquire's jump capped at the Way's pace, so neither a loop's
        // trailhead nor a car ride satisfies it.
        let aheadAtBegin = max(0, 1 - start)
        guard progressFrac >= HonorTuning.arrivalMinFrac,
              walkedFrac >= HonorTuning.arrivalMinDistanceRatio * aheadAtBegin else {
            arrival.reset()
            return
        }
        let end = CLLocation(latitude: last.lat, longitude: last.lon)
        let distance = location.distance(from: end)
        if arrival.register(distance: distance, radius: HonorTuning.arrivalRadiusMeters,
                            accuracy: location.horizontalAccuracy) {
            phase = .arrived
            subject.send(.arrived(theirSeconds: geometry.totalSeconds - companionT0, yourSeconds: sinceAnchorSeconds))
        }
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:290-310@7c200bf

Every gate, in order:
1. `phase == .walking`: arrival fires once per engine.
2. The Way has a last point, and an anchor exists (the fallback anchor counts; `startFrac` is 0 then).
3. **Progress gate:** `progressFrac >= 0.9`.
4. **Distance gate:** `walkedFrac >= 0.5 × max(0, 1 − startFrac)`: half of what lay ahead at the (re-)anchor, earned through windowed progress plus pace-capped re-acquire credit.
5. If 3 or 4 fails, the debounce **resets**. So a fix failing the progress gates breaks a run of inside fixes.
6. **Debounce:** the fix's straight-line distance to the route's **last recorded point** (not a projection) is at most 30 m, on 3 consecutive gate-passing fixes (accuracy at most 50 m).
7. On the completing fix: `phase = .arrived`, then `.arrived(theirSeconds: totalSeconds − companionT0, yourSeconds: activeDuration − anchorActiveDuration)`.

`theirSeconds` is the original walker's time from the anchor point to the end, pauses included. `yourSeconds` is the engine clock since the anchor, sittings included (§7). Arrival is **not** gated by pause, sitting, recording, or whisper. During a sitting, though, the meditation GPS tier's 50 m distance filter starves the fix stream (§1.4), so on iOS a walker who sits within 30 m of the end usually arrives only when they move again. Android has no such tier.

#### 11.3 Loops and AE2

On a loop (first and last points coincide), Begin at the trailhead anchors at the lowest frac, about 0, with `walkedFrac = 0`, so gates 3 and 4 fail and the debounce keeps resetting. This is the only protection. There is no explicit "ends coincide" check.

**It holds only while the 300 m forward window cannot reach the closing leg.** The Begin fix is tracked (§5) in the same call that anchors it, through a window of `[0, 300 m / totalMeters]`. On a loop or out-and-back of about 300 m or less, that window spans the whole Way, including the closing segment that ends at the start. A Begin fix nearer the closing segment than the opening one (GPS noise of a few metres on a shared trailhead; on an out-and-back, the return leg shares the pavement) projects to a frac near 1. Progress and `walkedFrac` both jump to about 1, gates 3 and 4 pass, the fix is within 30 m of the end (the end is the start), and **three such fixes arrive at Begin.** Worked example: a 240 m square loop A→B→C→D→A with 60 m sides, walker standing 3 m north of A on the D→A side. The fix is 3 m from A→B and about 0 m from D→A, so `nearest` returns about 0.99 and three fixes arrive. On a loop small enough that every segment is within 60 m of the walker, the anchor itself can land on the closing leg: `lowestFrac` takes the closest point of the *first contiguous run*, and that run is then the whole loop. Then `aheadAtBegin` is about 0.01, so metre-scale jitter meets the credit gate. No iOS test covers a loop under 300 m: the loop tests use a 1,000 m out-and-back (`HonorEngineTests.swift:10-17,66-84@7c200bf`). See iOS defects.

Other arrival edges:
- A mid-Way Begin needs only half of what lay ahead (`testMidWayBeginCanStillArrive`). A Begin at or near the end of a linear Way can arrive within three fixes.
- A car ride cannot satisfy gate 4 (`testReacquireCannotOutrunTheWaysPace`). An honest off-signal stretch can (`testReacquireIsCreditedAtTheWaysOwnPace`).
- Unknown speed never blocks arrival (`testUnknownSpeedStillReachesArrival`).
- A one-point or zero-length Way never arrives: `progressFrac` stays 0 (§18).

---

### 12. Event routing: what consumes the engine's outputs

#### 12.1 The event sink (synchronous, on main)

```swift
        // The engine's own streams already hop to main, so its events reach
        // this sink on main without another hop.
        engine.events
            .sink { [weak self] event in self?.handleHonorEvent(event) }
            .store(in: &honorCancellables)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:82-86@7c200bf

```swift
    func handleHonorEvent(_ event: HonorEngineEvent) {
        switch event {
        case .momentReached(let moment):
            if !honorCards.contains(moment) { honorCards.append(moment) }
            reachedMomentIDs.insert(moment.id)
            fireHonorHaptic(.waypointDropped)

        case .voiceStart(let moment):
            startVoice(moment)

        case .voicePause:
            isVoicePaused = true
            wayVoicePlayer?.pause()

        case .voiceResume:
            isVoicePaused = false
            wayVoicePlayer?.resume()

        case .voiceDropped(let moment):
            if activeVoice == moment {
                wayVoicePlayer?.stop()
                activeVoice = nil
                isVoicePaused = false
            }

        case .softTap(let meters):
            showSoftTapCaption(meters: meters)
            fireHonorHaptic(.honorOffWay)

        case .markAhead(let mark, let meters):
            showMarkCaption(mark: mark, meters: meters)
            fireHonorHaptic(.honorWaterAhead)

        case .arrived(let theirSeconds, let yourSeconds):
            recordHonorArrival(theirSeconds: theirSeconds, yourSeconds: yourSeconds)
            fireHonorHaptic(.honorArrival)
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:175-212@7c200bf

- A non-voice card is **appended** to the card list when reached. A voice card is **inserted at the front** when its voice starts (§12.2). A tapped pin also jumps the queue (`showCard`, `:334-337`).
- A dropped *queued* voice changes nothing in the view model. A dropped *playing* voice stops the player without `onFinished`, so its card is not scheduled to retire.
- Haptics fire only when the app is active (`fireHonorHaptic`, `:460-463`), and always after the state writes in the same case.

#### 12.2 A voice start, and a missing file

```swift
    /// A voice whose file is gone was never heard: hand the turn straight
    /// back to the engine so the next one can start.
    private func startVoice(_ moment: WayMoment) {
        guard case .voice(_, _, let kind, let media) = moment.kind, let url = mediaURL(for: media) else {
            honorEngine?.voiceDidFinish()
            return
        }
        activeVoice = moment
        isVoicePaused = false
        heardVoiceIDs.insert(moment.id)
        refreshHonorPins()
        wayVoicePlayer?.play(url: url, volume: Self.voiceVolume(for: kind))
        // The voice's own card rises with it — the waveform, the place, the
        // reply — and retires itself after the voice unless the walker
        // touches it, so an unanswered voice never leaves a card to close.
        showCard(for: moment)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:214-230@7c200bf

"Heard" is marked when the engine starts a voice whose file exists, before playback is confirmed. A missing file is never heard and shows no card; the engine moves on at once. A voice held `pending` behind a guide prompt counts as heard already.

```swift
        player.onFinished = { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            let finished = self.activeVoice
            self.activeVoice = nil
            self.isVoicePaused = false
            self.honorEngine?.voiceDidFinish()
            if let finished { self.retireCardLater(finished) }
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:64-71@7c200bf

```swift
    func skipVoice() {
        guard activeVoice != nil else { return }
        wayVoicePlayer?.stop()
        activeVoice = nil
        isVoicePaused = false
        honorEngine?.voiceDidFinish()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:421-427@7c200bf

#### 12.3 Arrival: persist, then ritual

```swift
    /// The persistence commit happens before any ritual effect, as in Seek.
    private func recordHonorArrival(theirSeconds: Double, yourSeconds: Double) {
        guard let way else { return }
        builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .honorArrival, timestamp: Date()))
        addWaypoint(label: HonorPersistence.arrivalWaypointLabel(wayTitle: way.title),
                    icon: HonorPersistence.arrivalWaypointIcon)
        honorArrival = HonorArrivalCard(
            wayTitle: way.title, voicesHeard: heardVoiceIDs.count,
            placesPassed: reachedMomentIDs.count,
            theirSeconds: theirSeconds, yourSeconds: yourSeconds,
            stageName: way.stage?.name,
            distanceWalkedMeters: honorEngine?.distanceWalkedMeters ?? 0,
            closing: way.stage?.closing)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:247-260@7c200bf

Order: event (timestamp = wall clock at handling) → waypoint at the view model's latest `currentLocation` → card → haptic. The waypoint's coordinate is the latest filtered sample, not the arrival fix object itself (they are the same fix in practice, since both are driven by `$currentLocation`).

#### 12.4 Other consumers of engine state

| Output | Consumer | Citation |
|---|---|---|
| `companionFrac` → `geometry.coordinate(atFrac:)` | walk map companion dot (nil for stages) | `ActiveWalkViewModel+Honor.swift:117-120@7c200bf`, `ActiveWalkView+Map.swift:40@7c200bf` |
| `distanceRemainingMeters` | stats bar third stat "Remaining", read at render (the 1 s tick re-renders) | `WalkStatsSheet.swift:417-430@7c200bf` |
| `distanceRemainingMeters`, `isOnWay`, `phase` | Live Activity glance, computed in the 1 Hz timer | `ActiveWalkViewModel+Honor.swift:432-437@7c200bf`, `ActiveWalkViewModel.swift:570-581@7c200bf` |
| `isAnchoredOnWay`, `progressFrac`, `phase` | checkpoint (`wayId`, outcome) and `honorStageOutcome` at teardown | `ActiveWalkViewModel+Honor.swift:128-133,166-171@7c200bf`, `WalkSessionGuard.swift:181-195@7c200bf` |
| `distanceWalkedMeters` | arrival card | `ActiveWalkViewModel+Honor.swift:258@7c200bf` |
| `geometry` | card distance and bearing, moment coordinates | `ActiveWalkViewModel+Honor.swift:273-303@7c200bf` |

Teardown order at Finish: `teardownSeek()` → `teardownHonor()` (outcome captured, generation bumped, subscriptions dropped, `engine.stop()`, player stopped and its `onFinished` cleared, cards, pins, heading stopped, `voiceRate = 1`) → … → `builder.setStatus(.ready)` (`ActiveWalkViewModel.swift:395-415@7c200bf`, `ActiveWalkViewModel+Honor.swift:128-158@7c200bf`). The engine stops **before** the walk ends, so no event lands after the snapshot. Discard (`cancel()`) runs the same `teardownHonor()`. `deinit` stops the engine and player as a safety net (`ActiveWalkViewModel.swift:260-265@7c200bf`).

#### 12.5 View-model timers that follow engine events

```swift
        softTapCaption = "off the way · \(Int(min(meters.isFinite ? meters : 0, 999_999))) m"
        let generation = honorGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.softTapCaptionSeconds) { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            self.softTapCaption = nil
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:239-244@7c200bf

```swift
    /// Not private: the water notice borrows this slot, so it borrows this life.
    static let softTapCaptionSeconds: TimeInterval = 20
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:457-458@7c200bf

```swift
    /// The seconds a finished voice's card stays before retiring on its own.
    static let cardRetireSeconds: TimeInterval = 20
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:305-306@7c200bf

The caption is always metres, integer, clamped to 999,999, with a " · " separator (U+00B7 with spaces); it ignores the units setting. Both timers are generation-guarded one-shots. On an own walk a second soft tap cannot come within 20 s: re-arming needs a return within 60 m, then 120 s beyond 200 m. On a stage, a water caption landing inside those 20 s shares the slot and is cleared by the earlier timer, because each timer checks only the generation, not which caption it set.

---
### 13. Clocks: wall clock or fix time

**The engine never reads a fix's timestamp.** `processLocation` uses only `horizontalAccuracy`, `coordinate`, `speed`, and `CLLocation.distance(from:)` (§3, §11.2). Every timer reads either the injected `now()` or the engine clock.

```swift
    init(way: Way, softTapEnabled: Bool, voicesEnabled: Bool, now: @escaping () -> Date = { Date() }) {
```
> Pilgrim/Models/Honor/HonorEngine.swift:80@7c200bf

| Timer or debounce | Clock | Where |
|---|---|---|
| Re-acquire after 120 s off the Way (`offWaySince`) | `now()` = wall clock `Date()` in production | `HonorEngine.swift:212-219@7c200bf` |
| Re-acquire retry every 10 s (`lastReacquireAttempt`) | `now()` | `HonorEngine.swift:217-220@7c200bf` |
| Soft tap after 120 s beyond 200 m (`softTapSince`) | `now()` | `HonorEngine.swift:279-281@7c200bf` |
| Re-acquire pace credit (`offWayActiveDuration`) | engine clock (`activeDuration`) | `HonorEngine.swift:236-238@7c200bf` |
| Companion position, `yourSeconds`, `anchorActiveDuration` | engine clock | `HonorEngine.swift:124-136,308@7c200bf` |
| Water quiet hour (`markQuietSeconds`, stages) | engine clock (`activeSeconds`) | `HonorMomentTracker.swift:129@7c200bf` |
| Arrival debounce (3 fixes) | fix count, no time | `ArrivalDebounce.swift:17-21@7c200bf` |
| Engine clock itself | wall clock `Date()` since the walk's start date, minus completed pauses, sampled by a 1 s `Timer` on the main run loop in `.common` mode | `ActiveWalkViewModel.swift:535-546@7c200bf` |
| Soft-tap caption 20 s, card retire 20 s | `DispatchQueue.main.asyncAfter(deadline: .now() + 20)` (monotonic dispatch time) | `ActiveWalkViewModel+Honor.swift:241,321@7c200bf` |

Consequences for Android:
- The wall-clock timers are driven by fixes: they are checked only when a fix arrives. If FLP delivers a batch (`result.locations` with several entries, `FusedLocationSource.kt:92@5ea4029b`), every fix in it sees about the same `now()`, just as iOS would for a CoreLocation batch.
- For U16's goldens, iOS's `now` must be driven from the trace (the iOS tests do this: `now: { self.clock }`, `HonorEngineTests.swift:33-35@7c200bf`). The Android goldens must inject the same instant per fix. The fix's own timestamp is the natural choice for both, as long as both sides use it only as `now()`.
- The engine clock ticks at 1 Hz, not per fix. A golden trace must interleave `updateActiveDuration` calls with fixes in a recorded order, because `anchor` copies whatever `activeDuration` holds at that moment.

---

### 14. Pause, sitting ("Sit?"), and resume

#### 14.1 No pause is reachable on iOS at the pin

The builder can represent `.paused` and `.autoPaused`, but no code path in the app enters either:
- `git grep "setStatus("` at the pin finds only `ActiveWalkViewModel.swift:382` (`.recording`, Begin), `:392` (`.recording`, `resume()`), and `:414` (`.ready`, Finish).
- `ActiveWalkViewModel.resume()` (`:391-393`) has no caller.
- `AutoPauseDetection` is defined (`Pilgrim/Models/Walk/WalkBuilder/Components/AutoPauseDetection.swift`) but never constructed (`git grep "AutoPauseDetection("` finds nothing), and it ignores walking workouts anyway:

```swift
            guard !(self.currentStatus == .paused), self.currentStatus.isActiveStatus, location.speed >= 0, ![.walking, .hiking].contains(workoutType) else { return }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/AutoPauseDetection.swift:60@7c200bf

- The stats sheet's controls are Meditate, Record, and End. There is no Pause button:

```swift
            case .recording, .paused, .autoPaused:
                actionButton("Meditate", systemImage: "brain.head.profile", color: .dawn) {
                    onStartMeditation()
                }
                micButton
                actionButton("End", systemImage: "stop.fill", color: .fog) {
                    onRequestEndWalk()
                }
```
> Pilgrim/Scenes/ActiveWalk/WalkStatsSheet.swift:526-533@7c200bf

- `builder.continueWalk(from:)`, which would seed a manual pause, is called only by `WalkCompletionActionHandler`, which nothing constructs.

So in shipped iOS the engine's `paused` gate is true only before the view model sees `.recording` (§2.4). Android can pause from the notification (`WalkNotificationFactory.kt:35-37,48-54@5ea4029b`; `WalkTrackingService.kt:474@5ea4029b`). Android's pause behavior is therefore defined by the engine's code, which handles pause fully:

#### 14.2 While paused (the engine's code; no shipped iOS behavior)

- **Processed as normal:** every fix (the feed keeps publishing while paused, §1.2): anchoring, progress, off-Way, re-acquire, soft tap, arrival, reaching moments, and drops.
- **Gated:** voice starts wait in the queue; a playing voice gets `voicePause`.
- **Engine clock:** the engine's intent is that paused time is not walking time ("Walking time off the Way, not wall clock: a walk paused for an hour off-Way must not convert that hour into arrival credit", `HonorEngine.swift:233-235@7c200bf`; `testPausedTimeOffTheWayEarnsNoPaceCredit` holds `activeDuration` still through a pause, `HonorEngineTests.swift:396-406@7c200bf`). The iOS view model does not deliver that (§7): an in-progress pause keeps adding to `activeDuration` until resume. It is latent because no pause is reachable (see iOS defects).
- **Wall-clock timers keep running:** off-Way time and soft-tap time accumulate during a pause, so a re-acquire or a soft tap can fire on the first fix after a long pause.
- Nothing is reset by pausing or resuming.

#### 14.3 On resume (engine code)

`setGates` with gates open emits `voiceResume` for a gate-paused voice, or `voiceStart` for the head of the queue (§8.2). The companion then continues from the engine clock. If Android's clock excludes paused time (Resolution 5), the companion is frozen through the pause and picks up where it stopped.

#### 14.4 While sitting (reachable on iOS)

```swift
    func startMeditation() {
        guard !isMeditating else { return }
        if isRecordingVoice {
            voiceRecordingManagement.stopRecording()
        }
        meditationStartDate = Date()
        isMeditating = true
        soundManagement.onMeditationStart()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:473-481@7c200bf

```swift
    func startMeditation(minutes: Int) {
        suggestedMeditationMinutes = minutes
        startMeditation()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:379-382@7c200bf

- The `meditating` gate closes: a playing voice gets `voicePause`, and new voices queue (`testSitPausesThePlayingVoiceAndResumesIt`; end to end, `ActiveWalkHonorTests.testMeditationPausesTheVoiceAndResumesAfter`, `UnitTests/Honor/ActiveWalkHonorTests.swift:132-146@7c200bf`).
- The status stays `.recording`, so `paused` stays false, fixes keep flowing (throttled by the meditation GPS tier, §1.4), and the engine clock keeps running. **The companion keeps walking during a sitting**, and `yourSeconds` counts it.
- Cards for non-voice moments still appear. The soft tap and arrival are not gated.
- A voice paused by the sitting can be **dropped** if a fix arrives more than 300 m from its place and the walker is not stationary. An unknown speed (`-1`) counts as moving.
- Ending the sitting (`endMeditationSilently` → `isMeditating = false`) opens the gate: `voiceResume`, or the queue's head starts. `finalizeMeditation` clears `suggestedMeditationMinutes` (`ActiveWalkViewModel.swift:502-503@7c200bf`).
- "Sit?" never ends a sitting on its own. `suggestedMeditationMinutes` feeds only the caption.

#### 14.5 "Sit?" while paused, and while already sitting

The card layer, and with it "Sit?", shows whenever the walk is active, which includes paused:

```swift
        if viewModel.mode == .honor && viewModel.status.isActiveStatus {
            HonorCardHost(viewModel: viewModel, onSit: onSit)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:13-14@7c200bf

```swift
            honorCardLayer(bottomInset: mapBottomInset) { minutes in
                viewModel.startMeditation(minutes: minutes)
                showMeditation = true
            }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:160-163@7c200bf

- `startMeditation()` has no pause guard, so in code a "Sit?" tap on a paused walk would start a sitting with the walk still paused, with both gates closed. The Meditate button is likewise offered for `.paused` and `.autoPaused` (`WalkStatsSheet.swift:526-529@7c200bf`). None of this is reachable on iOS (§14.1).
- A "Sit?" tap while already sitting: `startMeditation(minutes:)` overwrites `suggestedMeditationMinutes` first, then `startMeditation()` returns at its guard, so the caption's minutes change but the sitting does not restart.
- Android's reducer ignores `MeditateStart` while `Paused` (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkReducer.kt:111-140@5ea4029b`; only `Resume`, `Finish`, `Discard` are handled). See Resolution 5.

---
### 15. Every constant and literal

#### 15.1 `HonorTuning`

```swift
/// Every Honor threshold in one place. Values from the spec; on-device
/// tuning candidates, not commitments.
enum HonorTuning {
    static let fixAccuracyMeters = 50.0
    static let onWayMeters = 60.0
    static let windowMeters = 300.0
    static let backwardTolerance = 0.02
    static let momentFracTolerance = 0.05
    static let reacquireSeconds: TimeInterval = 120
    static let reacquireRetrySeconds: TimeInterval = 10
    static let voiceRadiusMeters = 42.0
    static let momentRadiusMeters = 60.0
    static let voiceDropMeters = 300.0
    static let stationarySpeed = 0.4
    static let softTapMeters = 200.0
    static let softTapSeconds: TimeInterval = 120
    static let arrivalRadiusMeters = 30.0
    static let arrivalMinFrac = 0.9
    static let arrivalMinDistanceRatio = 0.5
    static let arrivalFixCount = SeekEngineTuning.arrivalFixCount
    static let arrivalAccuracyMeters = SeekEngineTuning.arrivalAccuracyMeters
    /// How far the walker moves before the nearest-forty mark selection is
    /// recomputed. A resort of every mark on the stage is not a per-fix job.
    static let markPinRefreshMeters = 200.0
    /// How far before an on-way water source the caption rises.
    static let markAheadMeters = 300.0
    /// The Camino Francés carries a median of eight on-way sources per stage
    /// and 23 on its wettest day; without this the caption would be the
    /// day's loudest voice.
    static let markQuietSeconds: TimeInterval = 3600
}
```
> Pilgrim/Models/Honor/HonorTuning.swift:3-33@7c200bf

| Constant | Value | Role | Comparison at the use site |
|---|---|---|---|
| `fixAccuracyMeters` | 50 m | engine drops fixes outside `0...50` (§3) | `accuracy >= 0, accuracy <= 50` |
| `onWayMeters` | 60 m | anchor radius (`lowestFrac`), on-Way test in the window, re-acquire radius, soft-tap re-arm, water-mark filter (stages) | `<= 60` everywhere |
| `windowMeters` | 300 m | forward tracking window, as a frac of `totalMeters`; 1 (whole Way) when `totalMeters == 0` | `min(1, progress + 300/total)` |
| `backwardTolerance` | 0.02 (frac) | window's rear edge; also the re-acquire's forward-search floor | `max(0, progress − 0.02)` |
| `momentFracTolerance` | 0.05 (frac) | reach allowed once progress is within 0.05 before a moment | `progress >= frac − 0.05` |
| `reacquireSeconds` | 120 s (wall) | off-Way time before a re-acquire | `>= 120` |
| `reacquireRetrySeconds` | 10 s (wall) | spacing of failed re-acquires | `>= 10` |
| `voiceRadiusMeters` | 42 m | reach radius for voices | `<= 42` |
| `momentRadiusMeters` | 60 m | reach radius for photo, waypoint, rest, sitting | `<= 60` |
| `voiceDropMeters` | 300 m | queued or paused voice abandoned beyond this | `> 300` |
| `stationarySpeed` | 0.4 m/s | below it (and speed known) the walker is stationary; drops are skipped | `speed >= 0 && speed < 0.4` |
| `softTapMeters` | 200 m | soft tap counts time only beyond this | `> 200` (timer clears at `<= 200`) |
| `softTapSeconds` | 120 s (wall) | time beyond 200 m before the tap | `>= 120` |
| `arrivalRadiusMeters` | 30 m | distance to the route's last point | `<= 30` |
| `arrivalMinFrac` | 0.9 | progress gate | `>= 0.9` |
| `arrivalMinDistanceRatio` | 0.5 | credit gate, times what lay ahead at the anchor | `walkedFrac >= 0.5 × max(0, 1 − startFrac)` |
| `arrivalFixCount` | 3 (`SeekEngineTuning.arrivalFixCount`) | consecutive inside fixes | `>= 3` |
| `arrivalAccuracyMeters` | 50 m (`SeekEngineTuning.arrivalAccuracyMeters`) | debounce accuracy gate (redundant in Honor) | `accuracy >= 0, <= 50` |
| `markPinRefreshMeters` | 200 m | stage mark-pin reselection | stage-only |
| `markAheadMeters` | 300 m | water caption lead | stage-only |
| `markQuietSeconds` | 3,600 s (engine clock) | one water caption per hour | stage-only |

#### 15.2 Other literals the engine, tracker, and wiring use

| Literal | Value | Role | Citation |
|---|---|---|---|
| `maxReportedOffWayMeters` | 100,000 m | clamp for `offWayMeters` (infinity and huge values) | `HonorEngine.swift:257-261@7c200bf` |
| window span fallback | 1 | whole Way when `totalMeters == 0` | `HonorEngine.swift:190@7c200bf` |
| `softTapArmed` initial | `true` | soft tap starts armed | `HonorEngine.swift:78@7c200bf` |
| initial published values | `progressFrac 0`, `offWayMeters 0`, `isOnWay false`, `companionFrac 0`, `phase .walking`, `distanceRemainingMeters = totalMeters` | before the first fix | `HonorEngine.swift:27-32,86@7c200bf` |
| earth radius | 6,371,000 m | haversine for cumulative distances and degenerate Ways | `WayGeometry.swift:159-166@7c200bf` |
| metres per degree | 111,320 | equirectangular projection in `nearest(onSegment:)` | `WayGeometry.swift:140@7c200bf` |
| engine clock tick | 1 s, main run loop, `.common` | `activeDurationSeconds` | `ActiveWalkViewModel.swift:535@7c200bf` |
| soft-tap caption life | 20 s | `softTapCaptionSeconds` | `ActiveWalkViewModel+Honor.swift:458@7c200bf` |
| card retire | 20 s | `cardRetireSeconds` | `ActiveWalkViewModel+Honor.swift:306@7c200bf` |
| caption clamp | 999,999 | `Int(min(meters, 999_999))`, non-finite → 0 | `ActiveWalkViewModel+Honor.swift:239@7c200bf` |
| caption text | `"off the way · \(n) m"` | stats-bar third slot | `ActiveWalkViewModel+Honor.swift:239@7c200bf` |
| ambient voice volume | `base * 0.5` | ambience plays at half the voice level | `ActiveWalkViewModel+Honor.swift:342-345@7c200bf` |
| accuracy filter | `< 100` and `<= desiredAccuracy`; default and cap 20; blend weight `min(count, 9)`; round up to 10 m | route filter feeding the engine | `LocationManagement.swift:55-79,242-244@7c200bf` |
| meditation GPS tier | 100 m accuracy, 50 m distance filter | fix rate while sitting | `WalkSessionGuard.swift:57-73@7c200bf` |
| heading filter | 3° | compass update threshold | `HeadingProvider.swift:27@7c200bf` |
| tick animation | `.easeOut(duration: 0.25)` | direction tick rotation | `WayMomentHeader.swift:37-38@7c200bf` |
| card "here" | `< 30` m | card subline says "here" instead of a distance | `WayMomentHeader.swift:94-101@7c200bf` |

---

### 16. Every distance computation

#### 16.1 Inventory by call site

| # | Call site | Function | Purpose |
|---|---|---|---|
| D1 | `HonorMomentTracker.update`, reach | `CLLocation.distance(from:)`, fix → `place(of:)` | voice radius 42 m, other moments 60 m (`HonorMomentTracker.swift:67@7c200bf`) |
| D2 | `HonorMomentTracker.update`, queued drop | `CLLocation.distance(from:)`, fix → `place(of:)` | `> 300` m (`HonorMomentTracker.swift:80@7c200bf`) |
| D3 | `HonorMomentTracker.update`, paused-current drop | `CLLocation.distance(from:)`, fix → `place(of:)` | `> 300` m (`HonorMomentTracker.swift:83-84@7c200bf`) |
| D4 | `HonorEngine.evaluateArrival` | `CLLocation.distance(from:)`, fix → route's last point | `<= 30` m, fed to `ArrivalDebounce` (`HonorEngine.swift:303-306@7c200bf`) |
| D5 | `HonorEngine.track` | `WayGeometry.nearest(to:within:)`, equirectangular (111,320 m/deg) | on-Way `<= 60`, `offWayMeters` for the soft tap, progress frac (`HonorEngine.swift:193-198@7c200bf`) |
| D6 | `HonorEngine.anchor` | `WayGeometry.lowestFrac(within: 60, of:)`, equirectangular | Begin anchor (`HonorEngine.swift:167@7c200bf`) |
| D7 | `HonorEngine.track`, re-acquire | `lowestFrac(within: 60, of:, from: lower)`, then `lowestFrac(within: 60, of:)`, equirectangular | re-acquire target (`HonorEngine.swift:224-225@7c200bf`) |
| D8 | `WayGeometry.init` | `WayGeometry.distanceMeters` (haversine, R = 6,371,000) | `cumulative`, `totalMeters`, so every frac, `windowSpan`, `distanceRemainingMeters`, `distanceWalkedMeters` (`WayGeometry.swift:15-29@7c200bf`) |
| D9 | `nearest` / `lowestFrac` on a Way with one point or zero length | haversine to the first point | degenerate on-Way test and anchor (`WayGeometry.swift:99-102,179-182@7c200bf`) |
| D10 | `HonorMomentTracker.waterAhead` | along-Way `(mark.frac − progressFrac) × totalMeters` | water lead, stages only (`HonorMomentTracker.swift:131@7c200bf`) |
| D11 | `ActiveWalkViewModel.distanceToMoment` | `CLLocation.distance(from:)`, latest sample → moment | card subline ("here" under 30 m, else "N away") (`ActiveWalkViewModel+Honor.swift:273-277@7c200bf`) |
| D12 | `ActiveWalkViewModel.relativeBearing` | `WayGeometry.bearing` (great-circle initial bearing) | direction tick (`ActiveWalkViewModel+Honor.swift:281-285@7c200bf`) |
| D13 | `refreshMarkPinsIfWalkerMoved` | `CLLocation.distance(from:)` | `>= 200` m reselection, stages only (`ActiveWalkViewModel+MarkPins.swift:22-27@7c200bf`) |
| D14 | `SeekEngine.processLocation` (context) | `CLLocation.distance(from:)`, fix → clearing centre | Seek arrival; Android already ports this as haversine (`SeekEngine.swift:265-266@7c200bf`; `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekChainGenerator.kt:187-188@5ea4029b`) |

D5–D9 and D12 are pure Swift and port verbatim. D1–D4, D11, and D13 use Apple's `CLLocation.distance(from:)`, whose formula Apple does not document; U16 must pin the Android function for these. Android precedent for D14 is haversine at 6,371 km (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/GeoDistance.kt:9-19@5ea4029b`, formula identical to `WayGeometry.distanceMeters`).

Two different metrics meet at the 60 m on-Way line: D5–D7 use a flat projection at 111,320 m/deg, while haversine at 6,371 km is about 111,195 m/deg. So `nearest` reads about 0.11 % longer than haversine for the same pair of points (0.07 m at 60 m). That is harmless unless a golden fix sits within centimetres of a threshold; it is not a porting choice, since both formulas port as written.

#### 16.2 The `WayGeometry` functions the engine calls

```swift
    init(route: [WayPoint]) {
        points = route
        var running = 0.0
        var cum: [Double] = []
        cum.reserveCapacity(route.count)
        for (index, point) in route.enumerated() {
            if index > 0 {
                running += Self.distanceMeters(from: route[index - 1], to: point)
            }
            cum.append(running)
        }
        cumulative = cum
        totalMeters = running
        totalSeconds = route.last.map { $0.t - (route.first?.t ?? 0) } ?? 0
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:15-29@7c200bf

```swift
    func coordinate(atFrac frac: Double) -> CLLocationCoordinate2D {
        guard let first = points.first else { return CLLocationCoordinate2D(latitude: 0, longitude: 0) }
        guard points.count > 1, totalMeters > 0 else {
            return CLLocationCoordinate2D(latitude: first.lat, longitude: first.lon)
        }
        let target = min(max(frac, 0), 1) * totalMeters
        let (i, u) = segment(atDistance: target)
        let a = points[i], b = points[i + 1]
        return CLLocationCoordinate2D(latitude: a.lat + (b.lat - a.lat) * u,
                                      longitude: a.lon + (b.lon - a.lon) * u)
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:33-43@7c200bf

Interpolation is linear in degrees. An empty Way returns (0, 0), which is null island, so on an empty Way a moment without `at` sits there (§18).

```swift
    func elapsed(atFrac frac: Double) -> Double {
        guard points.count > 1, totalMeters > 0 else { return 0 }
        let (i, u) = segment(atDistance: min(max(frac, 0), 1) * totalMeters)
        let a = points[i], b = points[i + 1]
        return (a.t + (b.t - a.t) * u) - points[0].t
    }

    func frac(atElapsed elapsed: Double) -> Double {
        guard points.count > 1, totalMeters > 0 else { return 1 }
        let t0 = points[0].t
        if elapsed <= 0 { return 0 }
        if elapsed >= totalSeconds { return 1 }
        for i in 0..<(points.count - 1) {
            let ta = points[i].t - t0, tb = points[i + 1].t - t0
            if elapsed >= ta && elapsed <= tb {
                let u = tb > ta ? (elapsed - ta) / (tb - ta) : 0
                let d = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * u
                return d / totalMeters
            }
        }
        return 1
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:67-88@7c200bf

Note the asymmetric degenerate returns: `elapsed(atFrac:)` gives 0 and `frac(atElapsed:)` gives **1**, so on a zero-length Way the companion sits at frac 1 (the single point).

```swift
    func nearest(
        to coordinate: CLLocationCoordinate2D,
        within window: ClosedRange<Double>?
    ) -> (frac: Double, meters: Double) {
        guard let first = points.first else { return (0, .infinity) }
        guard points.count > 1, totalMeters > 0 else {
            return (0, Self.distanceMeters(from: first, to: WayPoint(lat: coordinate.latitude, lon: coordinate.longitude, alt: nil, t: 0)))
        }
        var best: (frac: Double, meters: Double) = (0, .infinity)
        for i in 0..<(points.count - 1) {
            let fa = cumulative[i] / totalMeters, fb = cumulative[i + 1] / totalMeters
            if let window, fb < window.lowerBound || fa > window.upperBound { continue }
            // Restrict the projection to the part of this segment inside the
            // window, so a window never leaks into a neighbouring segment
            // through a shared endpoint.
            var uRange: ClosedRange<Double> = 0...1
            if let window, fb > fa {
                let lo = max(0, (window.lowerBound - fa) / (fb - fa))
                let hi = min(1, (window.upperBound - fa) / (fb - fa))
                if lo > hi { continue }
                uRange = lo...hi
            }
            let hit = nearest(onSegment: i, to: coordinate, uRange: uRange)
            if hit.meters < best.meters {
                best = hit
            }
        }
        return best
    }

    /// Closest point to `coordinate` on segment `i`, with the projection
    /// parameter clamped into `uRange`. Local equirectangular projection
    /// (meters) is accurate enough for the tens-of-meters decisions the
    /// engine makes.
    private func nearest(onSegment i: Int, to coordinate: CLLocationCoordinate2D, uRange: ClosedRange<Double>) -> (frac: Double, meters: Double) {
        let fa = cumulative[i] / totalMeters, fb = cumulative[i + 1] / totalMeters
        let cosLat = cos(coordinate.latitude * .pi / 180)
        let a = points[i], b = points[i + 1]
        let ax = (a.lon - coordinate.longitude) * cosLat, ay = a.lat - coordinate.latitude
        let bx = (b.lon - coordinate.longitude) * cosLat, by = b.lat - coordinate.latitude
        let dx = bx - ax, dy = by - ay
        let lengthSq = dx * dx + dy * dy
        let raw = lengthSq > 0 ? -(ax * dx + ay * dy) / lengthSq : 0
        let u = min(max(raw, uRange.lowerBound), uRange.upperBound)
        let px = ax + dx * u, py = ay + dy * u
        return (fa + (fb - fa) * u, sqrt(px * px + py * py) * 111_320)
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:95-141@7c200bf

Details an implementer must keep: ties go to the **earlier** segment (strict `<`); a zero-length segment inside a longer Way projects to its first point (`raw = 0`); the longitude scale uses the **fix's** latitude, not the segment's.

```swift
    private func segment(atDistance d: Double) -> (index: Int, u: Double) {
        var lo = 0, hi = points.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) / 2
            if cumulative[mid] <= d { lo = mid } else { hi = mid }
        }
        let span = cumulative[hi] - cumulative[lo]
        let u = span > 0 ? (d - cumulative[lo]) / span : 0
        return (lo, min(max(u, 0), 1))
    }

    static func distanceMeters(from a: WayPoint, to b: WayPoint) -> Double {
        let r = 6_371_000.0
        let dLat = (b.lat - a.lat) * .pi / 180
        let dLon = (b.lon - a.lon) * .pi / 180
        let h = sin(dLat / 2) * sin(dLat / 2)
            + cos(a.lat * .pi / 180) * cos(b.lat * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:148-166@7c200bf

```swift
    func lowestFrac(within meters: Double, of coordinate: CLLocationCoordinate2D, from minFrac: Double = 0) -> (frac: Double, meters: Double)? {
        guard points.count > 1, totalMeters > 0 else {
            let hit = nearest(to: coordinate, within: nil)
            return hit.meters <= meters ? (0, hit.meters) : nil
        }
        // Walk the first contiguous run of segments within `meters` and take
        // its closest point. Strict `<` keeps the lower frac on a tie, which
        // is what protects the outbound leg of an out-and-back.
        var best: (frac: Double, meters: Double)?
        for i in 0..<(points.count - 1) {
            let fa = cumulative[i] / totalMeters, fb = cumulative[i + 1] / totalMeters
            if fb < minFrac { continue }
            let uLo = fb > fa ? min(1, max(0, (minFrac - fa) / (fb - fa))) : 0
            let hit = nearest(onSegment: i, to: coordinate, uRange: uLo...1)
            if hit.meters <= meters {
                if let current = best {
                    if hit.meters < current.meters { best = hit }
                } else {
                    best = hit
                }
            } else if best != nil {
                break
            }
        }
        return best
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:178-203@7c200bf

`lowestFrac` is a linear scan of the whole Way (up to 4,000 points, per the engine's comment at `HonorEngine.swift:248-249`). It stops at the first segment beyond `meters` after a hit. On a degenerate Way it ignores `minFrac`.

```swift
    static func bearing(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double {
        let lat1 = from.latitude * .pi / 180, lat2 = to.latitude * .pi / 180
        let dLon = (to.longitude - from.longitude) * .pi / 180
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let degrees = atan2(y, x) * 180 / .pi
        return (degrees + 360).truncatingRemainder(dividingBy: 360)
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:207-214@7c200bf

---
### 17. `HeadingProvider` and the card's direction tick

#### 17.1 Source, filter, rate

```swift
protocol HeadingProviding: AnyObject {
    /// True heading in degrees, nil until the compass has settled; on the main queue.
    var headingPublisher: AnyPublisher<Double?, Never> { get }
    func start()
    func stop()
}

/// The compass behind the direction tick on a Way card. Its own manager,
/// not the walk's: heading updates are a separate subscription from
/// location, and this one lives only while a Way is being honored — the
/// engine starts it and teardown stops it.
final class HeadingProvider: NSObject, HeadingProviding, CLLocationManagerDelegate {

    private let manager = CLLocationManager()
    private let subject = CurrentValueSubject<Double?, Never>(nil)
    private var running = false

    var headingPublisher: AnyPublisher<Double?, Never> { subject.eraseToAnyPublisher() }

    override init() {
        super.init()
        manager.delegate = self
        manager.headingFilter = 3
    }

    deinit { manager.stopUpdatingHeading() }

    func start() {
        guard !running, CLLocationManager.headingAvailable() else { return }
        running = true
        manager.startUpdatingHeading()
    }

    func stop() {
        guard running else { return }
        running = false
        manager.stopUpdatingHeading()
        subject.send(nil)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        // A negative accuracy means the compass is not calibrated; the tick
        // hides rather than point somewhere wrong.
        guard newHeading.headingAccuracy >= 0 else { subject.send(nil); return }
        let heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        subject.send(heading)
    }
}
```
> Pilgrim/Models/Honor/HeadingProvider.swift:5-52@7c200bf

- **Source:** the device compass (`CLLocationManager` heading updates) on its own manager, separate from the walk's location manager. This is device heading, not GPS course.
- **Value:** true heading when valid (`trueHeading >= 0`), else magnetic heading; nil when uncalibrated (`headingAccuracy < 0`), before the first update, after `stop()`, and for ever on a device with no compass (`headingAvailable()` false means `start()` is a no-op).
- **Smoothing:** none beyond the OS's own filter. `headingFilter = 3` means updates arrive only when the heading changes by at least 3°.
- **Rate:** event-driven by that 3° filter, with no timer and no throttle. It is delivered on main (the manager was created on main), and the view model hops to main again.
- **Lifetime:** started at Begin, stopped at teardown; it lives exactly as long as the engine.

```swift
        // The compass lives exactly as long as the engine: started here,
        // stopped in teardown with the rest of the honor state.
        let heading = honorSenses.makeHeadingProvider()
        heading.headingPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.headingDegrees = $0 }
            .store(in: &honorCancellables)
        heading.start()
        honorHeading = heading
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:88-96@7c200bf

```swift
        honorHeading?.stop()
        honorHeading = nil
        headingDegrees = nil
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:154-156@7c200bf

#### 17.2 The tick

```swift
    /// Degrees clockwise from the walker's heading to the moment: the
    /// direction tick. Nil until both a fix and a settled compass exist.
    func relativeBearing(to moment: WayMoment) -> Double? {
        guard let here = currentLocation, let heading = headingDegrees, let there = coordinate(of: moment) else { return nil }
        let bearing = WayGeometry.bearing(from: CLLocationCoordinate2D(latitude: here.latitude, longitude: here.longitude), to: there)
        return (bearing - heading + 360).truncatingRemainder(dividingBy: 360)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:279-285@7c200bf

```swift
                if let subline {
                    HStack(spacing: 4) {
                        if let tick {
                            Image(systemName: "location.north.fill")
                                .font(Constants.Typography.caption)
                                .foregroundColor(.stone)
                                .rotationEffect(.degrees(tick))
                                .animation(.easeOut(duration: 0.25), value: tick)
                                .accessibilityHidden(true)
                        }
                        Text(subline).font(Constants.Typography.caption).foregroundColor(.fog)
                    }
                }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:31-43@7c200bf

- Tick angle = great-circle bearing from the latest filtered sample to the moment's place (its `at`, else its frac point), minus the compass heading, normalised to `[0, 360)`. It is recomputed on every view render, so it changes with a new fix, a heading update, or any other re-render.
- The glyph is `location.north.fill` rotated clockwise by the angle, in the stone colour at caption size, with a 0.25 s ease-out, and hidden from VoiceOver.
- Shown only when the header has a subline (distance or place known) **and** the tick is non-nil. The walk card passes `tick: viewModel.relativeBearing(to: moment)` (`ActiveWalkView+Honor.swift:93@7c200bf`).
- Wrap: the value jumps between about 359 and 0 when the target crosses dead ahead, and SwiftUI animates the number, so the arrow spins almost a full turn (see iOS defects).
- iOS test: `testTheDirectionTickTurnsWithTheCompassAndHidesWithoutIt` (east target, heading 30°, tick 60°; nil heading hides it; teardown stops the compass) — `UnitTests/Honor/ActiveWalkHonorTests.swift:578-596@7c200bf`.

Android has no compass provider today. The only heading consumer is the Mapbox puck's own `PuckBearing.HEADING` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:738@5ea4029b`). The tick lives in the UI process (the card is UI), so its compass does not belong in `:tracker`.

---

### 18. Edge cases

| Case | What iOS does | Evidence |
|---|---|---|
| **Empty Way** (no points) | Anchor falls back to frac 0 (`nearest` returns infinity). `offWayMeters` = 100,000 (clamped). Re-acquire finds nothing. No soft tap (fallback). No arrival (`points.last` nil). Moments without `at` sit at (0, 0). No crash. | `WayGeometry.swift:34,99@7c200bf`; `HonorEngine.swift:197,268,291@7c200bf` |
| **One point, or all points identical** (`totalMeters == 0`) | `nearest`/`lowestFrac` use haversine to the first point. Anchor at frac 0 if within 60 m, else fallback. Progress stays 0. Window span 1. Companion at frac 1 (the point). **Never arrives** (`progressFrac >= 0.9` fails). Re-acquire credit skipped when `totalSeconds == 0`. Own-walk Ways under 20 m are never built (plan U13), so this reaches the engine only from shared or hand-edited data. | `WayGeometry.swift:35,68,75,100-102,179-182@7c200bf`; `testEngineOnAWayOfIdenticalPointsDoesNotTrap` `HonorEngineTests.swift:411-425@7c200bf` |
| **Fixes before the first anchor** | Only fixes that pass the 50 m gate count; the first one anchors. Before it: `startFrac` nil, so the companion does not move, no arrival, no soft tap (never reached), `distanceRemainingMeters` = total, `isOnWay` false. The first fix is usually the replayed pre-Begin sample (§1.2). | `HonorEngine.swift:129,150,153,291@7c200bf` |
| **Approach walk** (Begin more than 60 m from the Way) | Fallback anchor at frac 0. The companion waits, the soft tap is silent, `yourSeconds` and credit restart at the real join. Joining within the first 300 m re-anchors on that fix; joining farther along re-anchors only through a re-acquire at 120 s or later. | §4; `HonorEngineTests.swift:302-382@7c200bf` |
| **GPS jump off the Way** (under 120 s) | Off-Way for its duration; progress holds; nothing credited; timers clear on the next on-Way fix. | §5, §6 |
| **GPS jump forward along the Way** (within the window) | Progress jumps up to 300 m ahead and is credited at once. It can then retreat only 0.02 per fix, so a single forward-lying fix is largely sticky. Moments ahead are not reached unless the fix is also within their radius. | `HonorEngine.swift:198-208@7c200bf` |
| **GPS jump past the window** (more than 300 m ahead) | Off-Way; after 120 s a re-acquire moves progress there, credited only at the Way's pace. | §6 |
| **Walking backwards** | Progress follows, up to 0.02 of the Way per fix, while within 60 m. The high-water mark and credit never fall, so no credit is earned twice. `distanceRemainingMeters` grows. Passed moments stay reached; unreached ones behind remain reachable (the frac gate is only a lower bound). | `HonorEngine.swift:191,202-207@7c200bf` |
| **Out-and-back or loop, shared pavement** | Anchor takes the outbound leg (lowest frac, strict `<` ties). The window keeps progress on the current leg; a re-acquire searches forward first. | `WayGeometry.swift:183-185@7c200bf`; `HonorEngine.swift:221-225@7c200bf` |
| **Loop or out-and-back of about 300 m or less** | The Begin fix can project onto the closing leg and arrive within three fixes (§11.3). | iOS defect 1 |
| **A voice passed without being heard** | (a) Never within 42 m (e.g. a gap in fixes, or passing on a parallel street): never reached, no event, no card; it stays reachable. (b) Reached while a gate is closed and then left more than 300 m behind while moving and nothing is playing: dropped silently, with no card and no heard mark. (c) Paused by a gate, then more than 300 m away while moving: `voiceDropped`; the player stops; its card stays up without a retire timer. (d) Reached while another voice plays: kept, never dropped while that voice plays, starts when it ends. | §8.2; `HonorMomentTrackerTests.swift:68-109@7c200bf` |
| **Two voices at one spot** | Both queue in frac/id order; the second starts when the first finishes. | `HonorMomentTrackerTests.swift:43-50@7c200bf` |
| **Voices disabled** | Voice moments produce nothing at all; other cards still appear. | §8.2 |
| **Missing voice file** | Not heard, no card; the engine moves to the next voice immediately. | §12.2 |
| **Unknown speed** (`-1`, Android `null`) | Counts as moving, so drops can run while the walker is actually still. Never blocks arrival. | `HonorEngine.swift:159@7c200bf`; `testUnknownSpeedStillReachesArrival` |
| **Invalid or null accuracy** | Dropped by the engine's `accuracy >= 0` gate. iOS's route filter lets negative accuracy through, but the engine stops it. Android null has no iOS counterpart; treat it as failing, as Android Seek does. | `HonorEngine.swift:149-150@7c200bf`; `SeekEngine.kt:314-318@5ea4029b` |
| **Arrival while sitting** | Not gated, but the meditation tier's 50 m distance filter usually withholds fixes until the walker moves. | §1.4, §11.2 |
| **After arrival** | Tracking, moments, voices, companion, and card events continue; the soft tap and arrival stop. | §3 |
| **Duplicate gate emissions** | Idempotent: no repeat `voicePause`/`voiceResume` (`testSitPausesThePlayingVoiceAndResumesIt` "no repeat"); a repeat with an idle tracker only retries a start that would already have happened. | `HonorMomentTrackerTests.swift:52-59@7c200bf` |
| **Stop, then more input** | `stop()` cancels the subscriptions; nothing moves after it. | `testStopCancelsTheBoundStreams` `HonorEngineTests.swift:454-474@7c200bf` |

---

### 19. Android pieces at `5ea4029b` to reuse or avoid

- Accuracy-gated collector with the first-sample pass: `app/src/main/java/org/walktalkmeditate/pilgrim/location/FusedLocationSource.kt:53,112-120,185-199@5ea4029b`, consumed at `app/src/main/java/org/walktalkmeditate/pilgrim/service/WalkTrackingService.kt:287-290@5ea4029b`. This is the plan's tap point; it matches iOS's 20 m cap but not iOS's 10 m tightening (§1.3).
- Reducer drops fixes while `Paused` or `Meditating` (`WalkReducer.kt:77-88,111-171@5ea4029b`). iOS's feed does not, so the tap must sit before the reducer, as the plan says.
- Seek's inline arrival debounce, the extraction source for U15 (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekEngine.kt:79-80,313-325@5ea4029b`). It already treats null accuracy as "neither advance nor reset".
- Haversine at 6,371 km with iOS's exact formula (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/GeoDistance.kt:9-19@5ea4029b`).
- Injectable `Clock` pattern (`SeekEngine.kt:100@5ea4029b`) for the engine's `now()`.
- **Not** the engine clock: `WalkStats.activeWalkingMillis` (`WalkStats.kt:23-36@5ea4029b`) subtracts meditation.

---
### Resolutions for the plan

1. **State machine; the order of one fix.**
   - Inputs: fixes, the engine clock (1 Hz), four gates (paused = status ≠ recording, meditating, recording, whisper playing), and `voiceDidFinish()`.
   - Outputs: the eight events of §2.1, plus `progressFrac`, `distanceRemainingMeters`, `offWayMeters`, `isOnWay`, `companionFrac`, and `phase`.
   - States: unanchored → anchored-by-fallback or anchored (§4); on or off the Way, with the 120 s re-acquire and 10 s retry (§6); soft tap armed, timing, or fired (§10); voice idle, playing, or paused, with a FIFO queue (§8.2 table); `walking` → `arrived`, once (§11).
   - Per fix, in order: accuracy gate → anchor → track → distance remaining → soft tap → arrival → moments, whose actions go out as reached (frac, then id), drops, paused-current drop, water, and one start (§3).
   - Gate changes emit only `voicePause`, `voiceResume`, or `voiceStart`. `voiceDidFinish` emits only `voiceStart`.
   - Arrival does not stop tracking or moments.
   - The voice guide is not an engine gate; iOS holds a Way voice behind a guide prompt inside the player (§2.4), which on Android is the arbiter's job (U18).
2. **Constants.** §15.1 quotes all 21 `HonorTuning` values with role and the exact comparison operator at each use. §15.2 lists every other literal: the 100 km clamp, the 111,320 m/deg and 6,371 km figures, the 1 s clock, the 20 s caption and card timers, the 999,999 clamp and caption text, the filter's 100/20/9/10 numbers, the meditation GPS tier, the 3° heading filter, the 0.25 s tick animation, and "here" under 30 m. Defaults: soft tap **off**, voices on (`UserPreferences.swift:77-78@7c200bf`).
3. **Location publisher and filter.** Confirmed: Honor and Seek both read the view model's `$currentLocation`, the same filtered stream (§1.1).
   - Before recording, the last raw fix of each batch is published unfiltered, and the engine's first fix is usually that replayed pre-Begin sample (§1.2).
   - During the walk, a fix is kept only if `accuracy < 100 && accuracy <= threshold`, except the route's first sample when no pre-walk fix existed. Publishing continues while paused; only distance stops (§1.2).
   - The threshold starts at 20 m and, with no preference set, follows a moving mean of all fixes (weight `min(count, 9)`), rounded up to 10 m and capped at 20. In practice it is 10 m or 20 m (§1.3). A set preference (20/30/50) fixes it and disables adaptation; Off (−1) disables the filter. No shipped iOS screen sets the preference.
   - The engine then drops anything outside `0...50` m.
   - During a sitting, iOS's GPS tier (100 m accuracy, 50 m distance filter) thins the stream at the OS (§1.4).
   - Android's fixed 20 m gate equals the cap but keeps the 10–20 m fixes iOS drops while its mean is under 10 m. The goldens should feed the engine directly, so this does not touch U16.
4. **Clocks.** No engine rule reads a fix's timestamp (§13).
   - Wall clock through the injected `now()`: the 120 s re-acquire wait, its 10 s retry, and the 120 s soft tap. All three are evaluated only when a fix arrives.
   - Engine clock: the companion, `yourSeconds`, the re-acquire pace credit, and the (stage-only) water hour.
   - The engine clock on iOS is wall time since the walk's start minus completed pauses, ticked at 1 Hz. **Sittings are inside it** (§7). On Android that is elapsed time minus pauses, not `WalkStats.activeWalkingMillis`, which also subtracts meditation.
   - Caption and card timers are 20 s one-shots on monotonic time.
   - For U16, drive `now()` from the trace on both sides, and record the order of clock ticks relative to fixes.
5. **Paused, sitting, resume.**
   - **iOS has no reachable pause at the pin** (§14.1), so Android's pause behavior comes from the engine code (§14.2–14.3). Fixes are processed in full (anchor, progress, re-acquire, soft tap, arrival, reaching, drops); voices wait or pause; the wall-clock timers keep running; nothing resets.
   - The engine's stated rule is that paused time is not walking time (A3). The iOS view model's clock would break that during a pause, but that defect is latent and unreachable, so there is no shipped behavior to match. An Android clock that excludes paused time, including the pause in progress, matches the engine's intent, and the plan's "companion freezes while paused" test agrees with it.
   - **Sitting** (reachable): the gate pauses the voice and queues new ones, and the clock and companion **keep running** (§14.4). Cards, the soft tap, and arrival are ungated. A gate-paused voice can be dropped when more than 300 m away while moving.
   - On resume or at a sitting's end: `voiceResume`, or the queue's head starts.
   - "Sit?" while paused has no iOS behavior (§14.5): iOS code would start a sitting on a paused walk, and Android's reducer ignores `MeditateStart` while paused. See Open question 2.
6. **Arrival gates, in order** (§11.2):
   1. `phase == .walking`.
   2. The Way has a last point, and the engine is anchored (the fallback counts).
   3. `progressFrac >= 0.9`.
   4. `walkedFrac >= 0.5 × max(0, 1 − startFrac)`. If 3 or 4 fails, the debounce resets.
   5. The fix is at most 30 m (straight line) from the route's last point, on 3 consecutive fixes of accuracy up to 50 m.
   6. Then `phase = .arrived`, then `.arrived(totalSeconds − companionT0, activeDuration − anchorActiveDuration)`.

   Loops: there is no explicit ends-coincide check. The progress and credit gates block arrival at Begin only while the 300 m forward window cannot reach the closing leg. **On a loop or out-and-back of about 300 m or less, iOS can arrive at Begin within three fixes** (§11.3, defect 1). So iOS satisfies AE2 only for loops longer than the window. Under R5, U15's AE2 fixture should be a loop over 300 m, which iOS passes, and the short-loop behavior should be decided as shipped-and-filed (defect 1) or re-triaged by the owner.
7. **Distances.** §16.1 has all 14 call sites.
   - Pure Swift, port verbatim: D5–D7 (`nearest` and `lowestFrac`, equirectangular at 111,320 m/deg, longitude scaled by the fix's latitude), D8–D9 (haversine, R = 6,371,000), D10, and D12 (great-circle bearing).
   - Apple's undocumented `CLLocation.distance(from:)`, where U16 must pin the Android function: **D1** reach radii (42/60 m), **D2–D3** voice drop (300 m), **D4** arrival (30 m), plus UI-side D11 (card distance, "here" under 30 m) and stage-only D13.
   - Android precedent for the same Apple call in Seek is haversine at 6,371 km (`GeoDistance.kt:9-19@5ea4029b`).
8. **`HeadingProvider`** (§17).
   - Source: the device compass on a dedicated `CLLocationManager`. True heading if valid, else magnetic; nil when uncalibrated or unavailable.
   - No smoothing, `headingFilter = 3°`, event-driven, delivered on main.
   - Lives from Begin to teardown.
   - Tick = `(bearing(latest fix → moment) − heading + 360) mod 360`, rotating `location.north.fill` with a 0.25 s ease-out, hidden from VoiceOver. Shown only with a subline and a non-nil tick.
   - The plan's "card compass's heading source" is the device compass, not GPS course. It is UI-only.
9. **Edge cases** (§18):
   - Empty Way: inert, no crash.
   - One-point or zero-length Way: can anchor, never progresses, never arrives.
   - Before the first 50 m fix: nothing moves.
   - GPS jumps: off-Way under 120 s changes nothing; a forward jump inside the window is credited and sticky; beyond the window, a pace-capped re-acquire.
   - Walking backwards: progress follows at up to 0.02 per fix; credit never falls.
   - A voice passed unheard: never reached (stays reachable), dropped from the queue silently, or dropped while paused (the card stays).

Also for the plan:
- U15's scenario "a fix with null, negative, or over-50 m accuracy neither advances nor resets the debounce" is true of `ArrivalDebounce` itself. In Honor such fixes never reach it, because `processLocation` drops them first (§3).
- U15's "Begin anchors to the lowest-frac point within 60 m, else frac 0 until the first on-Way fix re-anchors" needs one qualifier: the first on-Way fix *within 300 m of the start*. A later join re-anchors only through the 120 s re-acquire (§4).
- U15's "a waiting voice is dropped 300 m past its spot unless the walker is stationary" also needs "and no unpaused voice is playing" (§8.2).
- The plan's session-row fields (U14) should cover the engine state listed in §2.2.

---

### iOS defects found

Candidates for upstream issues; not filed.

1. **A short loop or out-and-back can arrive at Begin.**
   - What: the Begin fix is anchored at the lowest frac, then tracked in the same call through a window reaching 300 m ahead. When the whole Way is within 300 m, that window contains the closing leg. A fix nearer the closing leg jumps `progressFrac` and `walkedFrac` to about 1, both arrival gates pass, and the end (which is the start) is within 30 m.
   - Evidence: `let upper = min(1, progressFrac + windowSpan)` … `walkedFrac += max(0, progressFrac - progressHighWater)` (`HonorEngine.swift:190-207@7c200bf`) together with `guard progressFrac >= HonorTuning.arrivalMinFrac, walkedFrac >= HonorTuning.arrivalMinDistanceRatio * aheadAtBegin` (`:298-299`). No test covers a loop under 300 m.
   - Impact: on a walk around a block, the arrival card, event, waypoint, and haptic can fire within seconds of Begin, and the arrival delta is meaningless. This is AE2 exactly.
2. **A voice the walker paused resumes by itself after any gate cycle.**
   - What: the card's pause changes only the view model and the player (`togglePlayback`, `ActiveWalkViewModel+Honor.swift:389-395@7c200bf`), so the tracker still believes the voice is playing. A whisper, a reply recording, or a sitting then closes and reopens the gates: `gatesDidChange` emits `.voicePause` and later `.voiceResume` (`HonorMomentTracker.swift:96-109@7c200bf`), and `.voiceResume` calls `wayVoicePlayer?.resume()` (`ActiveWalkViewModel+Honor.swift:189-191@7c200bf`).
   - iOS's own intent is the opposite: "a walker pause landing after a guide pause is never undone when the guide finishes" (`WayVoicePlayer.swift:37-41@7c200bf`).
   - Impact: a voice starts talking again unasked, for example right after the walker finishes recording a reply or ends a sitting. And while the walker holds a voice paused, no waiting voice can start or be dropped.
3. **The card's play button overrides a closed gate.**
   - What: with a voice paused by a gate (for example during a recording), `togglePlayback` resumes the player without consulting the gates (`:391-393`). The tracker still records the voice as paused, so its drop rule (`playing == nil || isVoicePaused`, `HonorMomentTracker.swift:79@7c200bf`) can stop that playing voice mid-sentence once the walker is 300 m away.
   - Impact: minor; a voice plays over a recording, or is cut off.
4. **Latent, unreachable at the pin: the engine clock counts an in-progress pause.**
   - What: `activeDuration = max(0, start.distance(to: Date()) - pauseDuration)` subtracts only completed pauses (`ActiveWalkViewModel.swift:544-546@7c200bf`; pauses are appended on resume, `WalkBuilder.swift:146-153@7c200bf`). So during a pause the companion walks on and pace credit accrues, then both snap back at resume. This contradicts the engine's assumptions: "the walk's active duration is monotonic" (`HonorEngine.swift:133-135`) and "a walk paused for an hour off-Way must not convert that hour into arrival credit" (`:233-235`).
   - The builder's 3 s pause merge also keeps the earlier pause in `pausesRelay` while the new pause restarts from its start (`WalkBuilder.swift:158-160@7c200bf`), double-counting the overlap.
   - Impact: none today (no pause path, §14.1), but a real bug the moment auto-pause or a pause button returns. Also the stats bar's time would count up through a pause and jump back.
5. **The direction tick spins almost a full turn when the target crosses dead ahead.**
   - What: `relativeBearing` wraps to `[0, 360)` (`ActiveWalkViewModel+Honor.swift:284@7c200bf`), and the tick animates the raw number (`.rotationEffect(.degrees(tick))`, `.animation(.easeOut(duration: 0.25), value: tick)`, `WayMomentHeader.swift:37-38@7c200bf`), so going from 359° to 1° animates backward through 358°.
   - Impact: cosmetic flicker on every straight-ahead approach.
6. **A dropped or skipped voice's card never retires.**
   - What: `startVoice` promises the card "retires itself after the voice unless the walker touches it, so an unanswered voice never leaves a card to close" (`ActiveWalkViewModel+Honor.swift:226-228@7c200bf`). But only `onFinished` schedules the retire (`:64-71`); `.voiceDropped` and `skipVoice()` stop the player with `stop()`, which does not notify (`WayVoicePlayer.swift:109-113@7c200bf`).
   - Impact: minor; a stale card waits to be dismissed.
7. **Seen in passing, outside cluster B (voice player): the playback rate outlives the walk.**
   - What: the shared `WayVoicePlayer.shared` keeps `playbackRate` (`WayVoicePlayer.swift:28-29,121-124,165@7c200bf`), while each walk's view model starts at `voiceRate = 1` and teardown resets only that field (`ActiveWalkViewModel.swift:97-98@7c200bf`, `ActiveWalkViewModel+Honor.swift:152@7c200bf`). Nothing calls `setRate(1)`.
   - Impact: the next honor walk plays its voices at the last walk's speed while the chip shows 1×, until the walker taps the rate. This answers the plan's "playback-rate lifetime" question. The voice-player reader should confirm it.

---

### Open questions

1. **What `CLLocation.distance(from:)` computes.** Apple does not document the model (sphere or ellipsoid, and which radius). It feeds D1–D4 (reach 42/60 m, drop 300 m, arrival 30 m). The U16 harness can log `CLLocation.distance` next to `WayGeometry.distanceMeters` at those four call sites over the corpus, to measure the divergence before the Android function is pinned.
2. **"Sit?" on a paused Android walk.** iOS cannot pause, so it has no behavior here. Its code would start a sitting with the walk still paused, and the card, with "Sit?", stays visible while paused. Android's reducer ignores `MeditateStart` while paused. The owner decides; it is an Android-only state (R6 note).
3. **The iOS sitting GPS tier.** iOS thins fixes while sitting (100 m accuracy, 50 m filter), which delays arrival and voice drops during a sitting. Android keeps full-rate fixes. iOS is unambiguous here; what is open is whether the gate records this as a divergence. It changes when arrival fires for a walker who sits at the end.
## C. Audio: the Way voice, the priority queue, and the other voices

Pin: `pilgrim-ios` @ `7c200bf` (every quote below was read with `git show 7c200bf:<path>`). Android citations are at HEAD `5ea4029b`. Scope: the own-walk slice. Where audio code branches on shared walks or stages, the branch point is named in one line.

Files read end to end: `Pilgrim/Models/Honor/WayVoicePlayer.swift`, `Pilgrim/Models/Audio/AudioPriorityQueue.swift`, `Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift`, `Pilgrim/Models/Whisper/WhisperPlayer.swift`, `Pilgrim/Models/Audio/SeekSoundPlayer.swift`, `Pilgrim/Models/Haptics/HapticManager.swift`, `Pilgrim/Models/Audio/SoundscapePlayer.swift`, `Pilgrim/Models/Audio/SoundManagement.swift`, `Pilgrim/Models/Audio/AudioSessionCoordinator.swift`, `Pilgrim/Models/Audio/VoiceGuide/VoiceGuideManagement.swift`, `Pilgrim/Models/Audio/VoiceGuide/MeditationGuideManagement.swift`, `Pilgrim/Models/Honor/HonorEngine.swift`, `Pilgrim/Models/Honor/HonorMomentTracker.swift`, `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift`, `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift`, `Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift`, `Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift`; the audio parts of `ActiveWalkViewModel.swift`, `ActiveWalkView.swift`, `ActiveWalkView+Map.swift`, `WalkStatsSheet.swift`, `MeditationView.swift`, `VoiceGuideSettingsView.swift`, `UserPreferences.swift`, `VoiceGuideScheduler.swift`, `VoiceRecordingManagement.swift`; and the two iOS test files that pin intent (`UnitTests/Honor/WayVoicePlayerTests.swift`, `UnitTests/AudioPriorityQueueTests.swift`).

### 1. The cast: players, consumers, volumes, and levels

There are three speaking players (the Way voice, the whisper queue, the guide), one bed (the soundscape), and the whisper preview on the side. Each is a process-wide singleton, and each names itself to the session coordinator as a "consumer".

**WayVoicePlayer.** One Way voice at a time. Consumer `"honor-voice"`. It is a singleton, so its state outlives a walk (this matters for the rate, §7).

```swift
/// Plays one Way voice at a time. Modeled on AudioPriorityQueue, not on the
/// settings preview player: it ducks the soundscape, waits for a guide
/// prompt to finish before starting, and holds community whispers while
/// it plays. Consumer "honor-voice"; deactivated in every exit path.
final class WayVoicePlayer: NSObject, ObservableObject, WayVoicePlaying, AVAudioPlayerDelegate {

    static let shared = WayVoicePlayer()

    @Published private(set) var isPlayingWayVoice = false
    @Published private(set) var elapsedSeconds: TimeInterval = 0
    /// The walker's chosen speed, kept across the voices of one walk.
    @Published private(set) var playbackRate: Float = 1
    var onFinished: (() -> Void)?

    static let rates: [Float] = [1, 1.25, 1.5, 2]
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:18-32@7c200bf

The Honor view model reaches the player through an injectable seam. Production returns the singleton.

```swift
struct HonorSenses {
    var makeVoicePlayer: () -> WayVoicePlaying = { WayVoicePlayer.shared }
    /// Haptics only render in the foreground; the gate lives here so event
    /// routing can stay in the view model.
    var isAppActive: () -> Bool = { UIApplication.shared.applicationState == .active }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:7-11@7c200bf

**Way voice volume.** A voice plays at the voice guide's volume preference. An ambient recording plays at half of it. There is no Honor-specific volume setting.

```swift
    /// Ambience is the sound of a place, not a voice: it plays once at half
    /// the voice level on entry. A continuous bed inside its span is deferred
    /// (one player at a time, per the resource-safety rules).
    static func voiceVolume(for kind: VoiceKind) -> Float {
        let base = Float(UserPreferences.voiceGuideVolume.value)
        return kind == .ambient ? base * 0.5 : base
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:339-345@7c200bf

**AudioPriorityQueue.** Plays whispers during a walk. Consumer `"whisper"`. Default volume 0.8. It ducks the soundscape to 30% of its current level (relative, not absolute).

```swift
    func playWhisper(url: URL, volume: Float = 0.8) {
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:45@7c200bf

```swift
    private func startWhisperPlayback(url: URL, volume: Float = 0.8) {
        stopWhisper()

        let currentVolume = soundscapePlayer.currentTargetVolume
        preDuckVolume = currentVolume
        let duckLevel = currentVolume * 0.3
        soundscapePlayer.setVolume(duckLevel, animated: true)

        coordinator.activate(for: .playbackOnly, consumer: "whisper")
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:96-104@7c200bf

**VoiceGuidePlayer.** Plays guide prompts (and meditation-guide prompts). Consumer `"voiceguide"`. It ducks to an ABSOLUTE level, the `voiceGuideDuckLevel` preference, and plays at the `voiceGuideVolume` preference.

```swift
        let duckLevel = Float(UserPreferences.voiceGuideDuckLevel.value)
        soundscapePlayer.setVolume(duckLevel, animated: true)

        coordinator.activate(for: .playbackOnly, consumer: "voiceguide")

        do {
            let p = try AVAudioPlayer(contentsOf: url)
            p.delegate = self
            p.volume = Float(UserPreferences.voiceGuideVolume.value)
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift:45-53@7c200bf

**SoundscapePlayer.** The bed. Its "target volume" starts at 0.4. Every duck and restore goes through `setVolume`, which records the target at once and ramps the audible volume over 0.5 s. A muted soundscape records the target but does not move.

```swift
    private var targetVolume: Float = 0.4
    var currentTargetVolume: Float { targetVolume }
```
> Pilgrim/Models/Audio/SoundscapePlayer.swift:14-15@7c200bf

```swift
    func setVolume(_ volume: Float, animated: Bool = true) {
        targetVolume = volume
        guard let player = activePlayer, !isMuted else { return }
        if animated {
            player.setVolume(volume, fadeDuration: 0.5)
        } else {
            player.volume = volume
        }
    }
```
> Pilgrim/Models/Audio/SoundscapePlayer.swift:117-125@7c200bf

Starting a soundscape overwrites the target with the walker's soundscape volume and fades up over 2.0 s. This ignores any duck in force (see §6 and the defects).

```swift
    func play(_ asset: AudioAsset, volume: Float = 0.4, fadeDuration: TimeInterval = 2.0) {
```
> Pilgrim/Models/Audio/SoundscapePlayer.swift:57@7c200bf

```swift
        stop(fadeDuration: 0)
        coordinator.activate(for: .playbackOnly, consumer: "soundscape")
        targetVolume = volume
```
> Pilgrim/Models/Audio/SoundscapePlayer.swift:68-70@7c200bf

**Preferences and their defaults.** Every level above comes from these keys.

```swift
    static let soundsEnabled = UserPreference.Required<Bool>(key: "soundsEnabled", defaultValue: true)
    static let bellHapticEnabled = UserPreference.Required<Bool>(key: "bellHapticEnabled", defaultValue: true)
    static let bellVolume = UserPreference.Required<Double>(key: "bellVolume", defaultValue: 0.7)
    static let soundscapeVolume = UserPreference.Required<Double>(key: "soundscapeVolume", defaultValue: 0.4)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:36-39@7c200bf

```swift
    static let voiceGuideVolume = UserPreference.Required<Double>(key: "voiceGuideVolume", defaultValue: 0.8)
    static let voiceGuideDuckLevel = UserPreference.Required<Double>(key: "voiceGuideDuckLevel", defaultValue: 0.15)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:57-58@7c200bf

```swift
    static let autoPlayWhisperOnProximity = UserPreference.Required<Bool>(key: "autoPlayWhisperOnProximity", defaultValue: true)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:68@7c200bf

```swift
    static let honorVoicesEnabled = UserPreference.Required<Bool>(key: "honorVoicesEnabled", defaultValue: true)
    static let honorSoftTapEnabled = UserPreference.Required<Bool>(key: "honorSoftTapEnabled", defaultValue: false)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:77-78@7c200bf

Voices are enabled for a walk only when both the Honor voices toggle and the master Sounds toggle are on. The value is read once, when the engine starts at Begin.

```swift
            voicesEnabled: UserPreferences.honorVoicesEnabled.value && UserPreferences.soundsEnabled.value
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:58@7c200bf

A disabled voice is never queued (it is still marked reached, but emits nothing):

```swift
            reached.insert(moment.id)
            if moment.isVoice {
                if voicesEnabled { queue.append(moment) }
            } else {
                actions.append(.reached(moment))
            }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:68-73@7c200bf

**Where the two levels are set.** Both sliders live in Voice Guide settings, visible only when the guide is enabled and a pack list exists. The duck slider's label names only the guide, but the Way voice uses it too.

```swift
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Guide Volume")
```
> Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:94-96@7c200bf

```swift
                HStack {
                    Text("Soundscape during guide")
                        .font(Constants.Typography.body)
                        .foregroundColor(.ink)
                    Spacer()
                    Text("\(Int(duckLevel * 100))%")
```
> Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:112-117@7c200bf

```swift
            if enabled {
                if manifestService.isSyncing && manifestService.packs.isEmpty {
                    loadingSection.pilgrimListRow()
                } else if manifestService.packs.isEmpty {
                    emptySection.pilgrimListRow()
                } else {
                    packsSection.pilgrimListRow()
                    volumeSection.pilgrimListRow()
                }
            }
```
> Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift:17-26@7c200bf

So a walker who never turned the guide on hears Way voices at 0.8 with the soundscape ducked to 0.15, and cannot change either. Android has neither preference today (the plan already lists "Android lacks iOS's live voice-guide volume and duck-level preferences" as an issue to file).

Android counterparts at `5ea4029b`: the soundscape ducks to a fraction of the user's volume, instantly, with no ramp (`app/src/main/java/org/walktalkmeditate/pilgrim/audio/soundscape/ExoPlayerSoundscapePlayer.kt:292-299@5ea4029b`, `DUCK_FRACTION = 0.3f` at `:455@5ea4029b`); whispers play at `PLAY_VOLUME = 0.8f` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/whisper/WhisperPlayer.kt:383@5ea4029b`). iOS's Way-voice duck is the absolute 0.15 preference with a 0.5 s ramp, not Android's 0.3 × user volume.

### 2. The priority order, and how a lower voice waits (pin 1)

The order is guide prompt > Way voice > whisper. No central arbiter enforces it. Each lower player checks the higher ones at its own entry point, parks ONE pending request in a single slot, and listens for the higher player to end. The higher player, when it starts, reaches down and interrupts the lower one directly.

**2.1 The Way voice checks the guide.** `play` parks the voice if a guide prompt is audible. The slot holds one voice; a later `play` overwrites it.

```swift
    func play(url: URL, volume: Float) {
        if voiceGuide.isPlaying {
            pending = (url, volume)
            return
        }
        start(url: url, volume: volume)
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:58-64@7c200bf

"Audible" means the guide's `AVAudioPlayer` reports playing. A guide player paused by the system (a call) reads false.

```swift
    var isPlaying: Bool { player?.isPlaying ?? false }
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift:20@7c200bf

The Way voice waits on the guide's end signal, subscribed in its own `init`:

```swift
    override init() {
        super.init()
        voiceGuide.playbackDidFinish
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.guideDidFinish() }
            .store(in: &cancellables)
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:48-54@7c200bf

The guide announces its end on EVERY exit path, not only a natural finish, so nothing held behind it is stranded:

```swift
    func stop() {
        guard player != nil else { return }
        player?.stop()
        player = nil
        restoreAndDeactivate()
        finishPendingCallback()
        // Every exit path announces the end, not just the natural one: a Way
        // voice or a whisper held behind this prompt is otherwise stranded
        // for the rest of the walk. Subscribers re-check `isPlaying` before
        // acting, so the extra emission a replacing `play()` triggers is
        // harmless.
        playbackDidFinish.send()
    }
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift:65-77@7c200bf

```swift
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        DispatchQueue.main.async { [weak self] in
            self?.player = nil
            self?.restoreAndDeactivate()
            let callback = self?.onFinished
            self?.onFinished = nil
            callback?()
            self?.playbackDidFinish.send()
        }
    }
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift:97-106@7c200bf

A decode failure at prompt start also announces the end:

```swift
        } catch {
            print("[VoiceGuidePlayer] Playback error: \(error)")
            restoreAndDeactivate()
            finishPendingCallback()
            playbackDidFinish.send()
        }
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift:57-62@7c200bf

On the end signal the Way voice re-checks (a replacing prompt also emits), then starts the newest pending voice, else resumes a voice the guide held (§3), else does nothing:

```swift
    private func guideDidFinish() {
        guard !voiceGuide.isPlaying else { return }
        // A newer voice queued while the guide spoke supersedes a held one,
        // exactly as it would have superseded a playing one.
        if pending != nil {
            pausedByGuide = false
            startPendingIfNeeded()
            return
        }
        if pausedByGuide {
            pausedByGuide = false
            // `resume()` takes the duck back: the guide restored the
            // soundscape to the walker's level on its way out.
            resume()
            return
        }
        startPendingIfNeeded()
    }

    private func startPendingIfNeeded() {
        guard let pending else { return }
        self.pending = nil
        start(url: pending.url, volume: pending.volume)
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:184-207@7c200bf

The guide never waits for a Way voice. `VoiceGuideManagement` and `VoiceGuideScheduler` hold no reference to the Way voice at the pin (`git grep -n -i "wayvoice\|honor" 7c200bf -- Pilgrim/Models/Audio/VoiceGuide/` finds only the `pauseForGuide()` call in the player). The guide scheduler fires only while the walk is recording, not meditating, and not recording a voice note:

```swift
            guard walkState.status == .recording,
                  !walkState.isRecordingVoice,
                  !walkState.isMeditating,
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuideScheduler.swift:109-111@7c200bf

**2.2 The whisper checks the guide and the Way voice.** Same shape: one pending URL, last writer wins.

```swift
    func playWhisper(url: URL, volume: Float = 0.8) {
        if voiceGuidePlayer.isPlaying || WayVoicePlayer.shared.isPlayingWayVoice {
            pendingWhisperURL = url
            return
        }

        startWhisperPlayback(url: url, volume: volume)
    }
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:45-52@7c200bf

`isPlayingWayVoice` is true from the moment a voice starts until it finishes, is stopped, or fails. It stays TRUE while the voice is paused, by the walker, by an engine gate, or by a guide prompt. It is FALSE while a voice is merely parked behind the guide (then the guide check holds the whisper).

```swift
    /// `isPlayingWayVoice` deliberately stays true either way: a whisper
    /// waiting on this voice must keep waiting.
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:94-95@7c200bf

The whisper waits on two signals: the Way voice's falling edge and the guide's end signal. Each re-checks both higher players before starting.

```swift
        WayVoicePlayer.shared.$isPlayingWayVoice
            .removeDuplicates()
            .filter { !$0 }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.playPendingWhisperIfNeeded()
            }
            .store(in: &cancellables)

        voiceGuidePlayer.playbackDidFinish
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in
                self?.playPendingWhisperIfNeeded()
            }
            .store(in: &cancellables)
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:28-42@7c200bf

```swift
    private func playPendingWhisperIfNeeded() {
        guard let url = pendingWhisperURL, !voiceGuidePlayer.isPlaying, !WayVoicePlayer.shared.isPlayingWayVoice else { return }
        pendingWhisperURL = nil
        startWhisperPlayback(url: url)
    }
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:120-124@7c200bf

**2.3 The ordering rule when a prompt ends.** When a guide prompt ends with both a Way voice and a whisper parked, the Way voice must start first. iOS gets this from subscription order, which it forces on purpose:

```swift
        // Subscribing here forces WayVoicePlayer.shared to exist, which
        // registers its own sink on voiceGuidePlayer.playbackDidFinish
        // before ours below. That ordering matters: when a guide prompt
        // ends, WayVoicePlayer's sink must start the next queued Way voice
        // before our sink below releases a held whisper, or the whisper
        // starts for one tick and is immediately cut by interruptForWayVoice.
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:22-27@7c200bf

Android must reproduce the rule, not the mechanism: on "prompt ended", release a parked or held Way voice before re-checking a parked whisper.

**2.4 Higher players interrupt lower ones.** A starting guide prompt stops an audible whisper AND drops a parked one, then holds the Way voice:

```swift
    private func startPlayback(url: URL, onFinished: (() -> Void)?) {
        AudioPriorityQueue.shared.interruptForVoiceGuide()
        stop()

        self.onFinished = onFinished

        // A Way voice already speaking is held for the length of this prompt,
        // and hands over the level it ducked FROM — otherwise this player
        // would capture that duck as its own "before", and restoring to it
        // would leave the soundscape quiet for the rest of the walk.
        let inherited = WayVoicePlayer.shared.pauseForGuide()
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift:32-42@7c200bf

```swift
    func interruptForVoiceGuide() {
        pendingWhisperURL = nil
        guard isPlayingWhisper else { return }
        player?.stop()
        player = nil
        isPlayingWhisper = false
        restoreAndDeactivate()
    }
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:66-73@7c200bf

A starting Way voice stops an audible whisper but KEEPS a parked one:

```swift
    /// Same interruption as `interruptForVoiceGuide()` but leaves
    /// `pendingWhisperURL` alone — a whisper held across a run of several
    /// Way voices must survive every voice in that run, not just the first.
    func interruptForWayVoice() {
        guard isPlayingWhisper else { return }
        player?.stop()
        player = nil
        isPlayingWhisper = false
        restoreAndDeactivate()
    }
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:75-84@7c200bf

```swift
        AudioPriorityQueue.shared.interruptForWayVoice()
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:153@7c200bf

An interrupted whisper is gone. It is not re-parked and does not resume.

**2.5 The engine's own whisper gate.** The engine-driven path adds a second guard: a Way voice never STARTS while a whisper is audible, and a playing one would pause if a whisper became audible. The whisper flag is one of four gates.

```swift
            externalAudio: AudioPriorityQueue.shared.$isPlayingWhisper.eraseToAnyPublisher()
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:80@7c200bf

```swift
    struct Gates: Equatable {
        var paused = false
        var meditating = false
        var recording = false
        var externalAudio = false
        var isClosed: Bool { paused || meditating || recording || externalAudio }
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:19-25@7c200bf

```swift
    private mutating func startNextIfPossible(gates: Gates) -> [Action] {
        guard playing == nil, !gates.isClosed, !queue.isEmpty else { return [] }
        let next = queue.removeFirst()
        playing = next
        return [.voiceStart(next)]
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:117-122@7c200bf

So a Way voice reached while a whisper plays waits in the engine's queue (not in the player) until the whisper ends, and then starts. The guide is NOT an engine gate; guide waiting happens inside the player (§2.1). §4 lists the gates in full.

**Summary of who checks whom**

| Entry point | Checks | If blocked | Released by |
|---|---|---|---|
| `VoiceGuidePlayer.startPlayback` | nothing | never blocked | — |
| `WayVoicePlayer.play` | guide `isPlaying` | one `pending` slot, newest wins | guide `playbackDidFinish` (any exit) |
| Engine `startNextIfPossible` | paused, meditating, recording, whisper audible | stays in the engine's FIFO queue | the next `gatesDidChange` or `voiceDidFinish` |
| `AudioPriorityQueue.playWhisper` | guide `isPlaying`, `isPlayingWayVoice` | one `pendingWhisperURL`, newest wins | Way voice falling edge, guide `playbackDidFinish` |

### 3. A guide prompt that starts mid-voice (pin 2)

**3.1 The hand-over is synchronous and happens before the prompt makes a sound.** `startPlayback` calls `pauseForGuide()` (quoted in §2.4) before it creates the prompt's player. On iOS a prompt and a Way voice never overlap on this path.

```swift
    /// Hands a starting guide prompt the soundscape level this player ducked
    /// FROM, so the guide's own restore lands on the walker's level instead
    /// of on this duck — exactly one owner of the duck at a time. A voice
    /// that is actually speaking is also held for the length of the prompt
    /// and resumed when it ends; a voice the walker paused is left paused.
    /// `isPlayingWayVoice` deliberately stays true either way: a whisper
    /// waiting on this voice must keep waiting.
    func pauseForGuide() -> Float? {
        guard player != nil, !pausedByGuide else { return nil }
        if player?.isPlaying == true {
            // `pause()` first: it clears `pausedByGuide` unconditionally, so
            // setting the flag has to happen after, not before, that call.
            pause()
            pausedByGuide = true
        }
        let inherited = preDuckVolume
        preDuckVolume = nil
        return inherited
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:89-107@7c200bf

Rules, in order:
1. No voice loaded (`player == nil`, which includes a voice merely parked in `pending`): nothing happens; returns nil.
2. A second prompt while the voice is already held (`pausedByGuide`): nothing happens; returns nil. The guide's own `stop()` of the first prompt already restored the soundscape to the inherited level (§3.3), so the second prompt re-captures that level.
3. The voice is audible: it is paused (`AVAudioPlayer.pause()` keeps the position) and marked `pausedByGuide`.
4. The voice is loaded but not audible (walker-paused or gate-paused): it stays paused and is NOT marked; the guide still takes the duck.
5. In cases 3 and 4 the voice gives up its pre-duck level (`preDuckVolume = nil`) and hands it to the guide.

**3.2 The guide takes over the duck.** It records the inherited level as its own "before", or the soundscape's current target when nothing was inherited, then ducks to the absolute preference.

```swift
        let currentVolume = inherited ?? soundscapePlayer.currentTargetVolume
        preDuckVolume = currentVolume
        let duckLevel = Float(UserPreferences.voiceGuideDuckLevel.value)
        soundscapePlayer.setVolume(duckLevel, animated: true)
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift:43-46@7c200bf

Both players duck to the same `voiceGuideDuckLevel`, so the audible soundscape level does not change across the hand-over.

**3.3 Who restores the duck.** The guide restores to the level it inherited, on every exit (natural finish, `stop()`, decode error):

```swift
    private func restoreAndDeactivate() {
        if let volume = preDuckVolume {
            soundscapePlayer.setVolume(volume, animated: true)
            preDuckVolume = nil
        }
        coordinator.deactivate(consumer: "voiceguide")
    }
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift:108-114@7c200bf

Then its end signal reaches `guideDidFinish()` (quoted in §2.1). A held voice (`pausedByGuide`) is resumed through `resume()`, which takes the duck back from the walker's level:

```swift
    func resume() {
        guard let player else { return }
        // A guide prompt may have taken the duck over while this voice was
        // held; take it back rather than speaking over a soundscape at full
        // volume. While a guide is still speaking, though, it owns the
        // duck — re-ducking here would fight its own restore on the way out.
        if preDuckVolume == nil && !voiceGuide.isPlaying {
            preDuckVolume = soundscape.currentTargetVolume
            soundscape.setVolume(Float(UserPreferences.voiceGuideDuckLevel.value), animated: true)
        }
        player.play()
        startElapsedTimer()
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:75-87@7c200bf

So yes: the voice resumes where it stopped (the paused `AVAudioPlayer` keeps `currentTime`), and the Way voice player re-ducks itself. The soundscape is formally restored and then re-ducked, one main-queue hop apart (the guide's restore runs inside its own `DispatchQueue.main.async`; the Way voice's sink is `.receive(on: DispatchQueue.main)`). Both are 0.5 s ramps; the second replaces the first almost at once, so the level is effectively held at the duck. iOS's test pins the end state (the walker's level after both finish, in either order):

```swift
    /// B3, voice-then-guide: the guide arriving mid-voice used to capture the
    /// voice's duck as its own "before", so whichever ended last left the
    /// soundscape quiet for the rest of the walk.
    func testVoiceThenGuideRestoresTheOriginalSoundscapeLevel() throws {
```
> UnitTests/Honor/WayVoicePlayerTests.swift:145-148@7c200bf

**3.4 A newer voice supersedes a held one.** If a voice lands in `pending` while another is held, the pending one starts when the prompt ends, and the held one is discarded (`start()` stops the old player without notifying). In practice only a walker tap can do this, because the engine never emits a second `voiceStart` while its own `playing` is set (`startNextIfPossible` requires `playing == nil`, §2.5).

**3.5 The walker's pause always wins over a guide hold.** `pause()` clears the guide flag, so a guide ending never resumes a voice the walker (or an engine gate) paused.

```swift
    func pause() {
        // Any pause — the walker's own or a guide-induced one already in
        // effect — is the walker's word from here on: a guide finishing
        // afterward must not resume what this call paused.
        pausedByGuide = false
        player?.pause()
        elapsedTimer?.invalidate()
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:66-73@7c200bf

```swift
    /// The walker pausing lands AFTER `pauseForGuide()` here, not before —
    /// the scenario `testGuideFinishDoesNotResumeAWalkerPausedVoice` above
    /// doesn't cover: `pausedByGuide` was already `true` when the walker's
    /// own `pause()` ran, and that call must still win when the guide ends.
    func testGuideFinishDoesNotResumeAVoiceTheWalkerPausedDuringTheGuide() throws {
```
> UnitTests/Honor/WayVoicePlayerTests.swift:185-189@7c200bf

**3.6 UI during a guide hold.** The guide hold does not touch the view model's `isVoicePaused`. The listening chip keeps saying "listening" and the card keeps its pause icon, with the elapsed clock frozen (the 1 s timer was invalidated by `pause()`). If the walker taps the chip or card during the hold, `togglePlayback` sees `isVoicePaused == false` and calls `pause()`, which converts the guide hold into a walker pause.

```swift
    func togglePlayback(of moment: WayMoment) {
        guard let player = wayVoicePlayer else { return }
        if moment == activeVoice {
            if isVoicePaused { player.resume() } else { player.pause() }
            isVoicePaused.toggle()
            return
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:389-395@7c200bf

**3.7 A voice reached while a prompt is speaking.** The engine emits `voiceStart`, and the view model runs `startVoice` (§9 quotes it). The card rises, the chip appears ("listening 0:00"), and the pin turns heard at once; the audio waits in `pending`. The duck and the session are not touched until `start()` runs at the prompt's end.

### 4. The engine's gates, and plays that bypass them

**4.1 Four gates.** Paused is "any status other than `.recording`", so auto-pause counts. Meditating, recording a voice note, and an audible whisper are the others.

```swift
        engine.bind(
            locations: honorLocationFixes,
            activeDuration: $activeDurationSeconds.eraseToAnyPublisher(),
            isPaused: $status.map { $0 != .recording }.eraseToAnyPublisher(),
            isMeditating: $isMeditating.eraseToAnyPublisher(),
            isRecordingVoice: $isRecordingVoice.eraseToAnyPublisher(),
            externalAudio: AudioPriorityQueue.shared.$isPlayingWhisper.eraseToAnyPublisher()
        )
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:74-81@7c200bf

```swift
        // combineLatest waits for all four inputs; callers bind @Published
        // projections, which emit on subscribe, so the gates are live at once.
        isPaused.combineLatest(isMeditating, isRecordingVoice, externalAudio)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] paused, meditating, recording, audio in
                self?.setGates(paused: paused, meditating: meditating, recording: recording, externalAudio: audio)
            }
            .store(in: &cancellables)
```
> Pilgrim/Models/Honor/HonorEngine.swift:108-115@7c200bf

**4.2 Gate edges pause and resume the engine's current voice.** The engine keeps its own paused flag, separate from the view model's and the player's.

```swift
    mutating func gatesDidChange(_ gates: Gates) -> [Action] {
        if playing != nil {
            if gates.isClosed, !isVoicePaused {
                isVoicePaused = true
                return [.voicePause]
            }
            if !gates.isClosed, isVoicePaused {
                isVoicePaused = false
                return [.voiceResume]
            }
            return []
        }
        return startNextIfPossible(gates: gates)
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:96-109@7c200bf

```swift
        case .voicePause:
            isVoicePaused = true
            wayVoicePlayer?.pause()

        case .voiceResume:
            isVoicePaused = false
            wayVoicePlayer?.resume()
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:185-191@7c200bf

Consequences an implementer must match:
- The engine does not know about the walker's own pause. A voice the walker paused, followed by a gate closing and reopening (pause and resume the walk, sit and stand, record and stop), is resumed by the gate's `voiceResume`. Recorded as an iOS behavior (and a defect candidate, D6).
- `.voicePause` goes to whatever the player holds, including a reply (§8).
- `pause()` does not clear `pending`. A voice parked behind a prompt survives a gate closing and starts when the prompt ends, inside the closed gate (defect D1).

**4.3 Voice drop.** A waiting voice is abandoned once the moving walker is more than 300 m from it. A gate-paused current voice is abandoned the same way. A voice the engine thinks is playing is never dropped, and that includes a walker-paused voice, since the engine does not know about walker pauses.

```swift
        // Abandon voices the walker has left far behind, but never while a
        // voice is playing: listening to a long musing carries the walker
        // hundreds of metres, and the next voice must still be waiting.
        if !isStationary, playing == nil || isVoicePaused {
            let dropped = queue.filter { here.distance(from: place(of: $0)) > HonorTuning.voiceDropMeters }
            queue.removeAll { dropped.contains($0) }
            actions += dropped.map { .voiceDropped($0) }
            if let current = playing, isVoicePaused,
               here.distance(from: place(of: current)) > HonorTuning.voiceDropMeters {
                playing = nil
                isVoicePaused = false
                actions.append(.voiceDropped(current))
            }
        }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:76-89@7c200bf

```swift
    static let voiceRadiusMeters = 42.0
    static let momentRadiusMeters = 60.0
    static let voiceDropMeters = 300.0
    static let stationarySpeed = 0.4
```
> Pilgrim/Models/Honor/HonorTuning.swift:13-16@7c200bf

```swift
        case .voiceDropped(let moment):
            if activeVoice == moment {
                wayVoicePlayer?.stop()
                activeVoice = nil
                isVoicePaused = false
            }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:193-198@7c200bf

**4.4 Walker-started plays bypass every engine gate.** Card and chip taps, scrubbing, and "your reply" call the player directly. Only the player's own guide check applies. A tap while a whisper plays cuts the whisper (`interruptForWayVoice`). A tap while the walk is paused, or while a voice note records on another card, plays anyway.

```swift
        guard case .voice(_, _, let kind, let media) = moment.kind, let url = mediaURL(for: media) else { return }
        player.stop()
        activeVoice = moment
        isVoicePaused = false
        heardVoiceIDs.insert(moment.id)
        refreshHonorPins()
        player.play(url: url, volume: Self.voiceVolume(for: kind))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:396-403@7c200bf

A tapped voice is not the engine's voice. `player.stop()` retires the engine's current voice without calling `onFinished`, so the engine's `playing` stays set until the tapped voice ends; then `onFinished` hands the engine its turn back (§8.3 has the same mechanism for replies).

Skip hands the turn back at once:

```swift
    func skipVoice() {
        guard activeVoice != nil else { return }
        wayVoicePlayer?.stop()
        activeVoice = nil
        isVoicePaused = false
        honorEngine?.voiceDidFinish()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:421-427@7c200bf

Scrubbing a card whose voice is not playing starts that voice first, then seeks. The seek clamps below the end so a scrub never lands on "finished":

```swift
    func seekVoice(_ moment: WayMoment, toFraction fraction: Double) {
        if moment != activeVoice { togglePlayback(of: moment) }
        guard moment == activeVoice else { return }
        wayVoicePlayer?.seek(toFraction: fraction)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:407-411@7c200bf

```swift
    func seek(toFraction fraction: Double) {
        guard let player, player.duration > 0 else { return }
        player.currentTime = min(max(0, fraction), 0.999) * player.duration
        elapsedSeconds = player.currentTime
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:115-119@7c200bf

A scrub on a voice that went to `pending` (a prompt is speaking) is a no-op, because `player` is nil.

**4.5 A voice ending on its own chains to the next, synchronously.** `onFinished` clears the chip before asking the engine, because the engine may answer at once with another `voiceStart`.

```swift
        player.onFinished = { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            let finished = self.activeVoice
            self.activeVoice = nil
            self.isVoicePaused = false
            self.honorEngine?.voiceDidFinish()
            if let finished { self.retireCardLater(finished) }
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:64-71@7c200bf

The engine's event sink has no queue hop, so the next voice's `startVoice` runs inside the finished voice's `onFinished`:

```swift
        // The engine's own streams already hop to main, so its events reach
        // this sink on main without another hop.
        engine.events
            .sink { [weak self] event in self?.handleHonorEvent(event) }
            .store(in: &honorCancellables)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:82-86@7c200bf

### 5. How iOS queues every whisper play (pin 3)

**5.1 Every in-walk whisper play goes through the queue.** `WhisperPlayer.play` hands the file to `AudioPriorityQueue.playWhisper`, whether cached or freshly downloaded:

```swift
    func play(_ whisper: WhisperDefinition, volume: Float = 0.8) {
        if isAvailable(whisper) {
            AudioPriorityQueue.shared.playWhisper(url: localURL(for: whisper), volume: volume)
        } else {
            Task { [weak self] in
                guard let self else { return }
                let remote = remoteURL(for: whisper)
                let local = localURL(for: whisper)
                do {
                    let (data, response) = try await URLSession.shared.data(from: remote)
                    guard Self.isResponseComplete(data: data, response: response) else { return }
                    try data.write(to: local)
                    await MainActor.run {
                        AudioPriorityQueue.shared.playWhisper(url: local, volume: volume)
                    }
```
> Pilgrim/Models/Whisper/WhisperPlayer.swift:142-156@7c200bf

The four call sites of `WhisperPlayer.shared.play` at the pin, all queued:
- proximity autoplay (entering a whisper's radius):

```swift
            if UserPreferences.autoPlayWhisperOnProximity.value,
               UserPreferences.soundsEnabled.value {
                if let cached = GeoCacheService.shared.cachedWhispers.first(where: { $0.id == whisperId }),
                   let category = cached.resolvedCategory,
                   let definition = WhisperManifestService.shared.placeableWhispers(for: category).randomElement() {
                    WhisperPlayer.shared.play(definition)
                }
            }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:886-893@7c200bf

- a tap on a whisper pin (`ActiveWalkView+Map.swift:74@7c200bf`), which also fires the whisper-proximity haptic;
- the placement confirmation, which plays the whisper the walker just left (`ActiveWalkView.swift:777@7c200bf`);
- Seek's reveal whisper (`ActiveWalkViewModel+Seek.swift:22,251@7c200bf`), outside this slice.

The placement sheet's preview does NOT go through the queue. It plays at 0.6 on its own consumer `"whisper-preview"`, does not duck the soundscape, and does not wait for a prompt or a Way voice:

```swift
    func preview(_ whisper: WhisperDefinition, volume: Float = 0.6) {
        stop()
        coordinator.activate(for: .playbackOnly, consumer: "whisper-preview")
```
> Pilgrim/Models/Whisper/WhisperPlayer.swift:164-166@7c200bf

A download-then-play (`Task`) runs `playWhisper` only when the download completes, so it enters the queue at that later moment.

**5.2 The queue rules.**
1. One slot. A later request overwrites an earlier parked one (`pendingWhisperURL = url`, §2.2).
2. A whisper is parked when a guide prompt is audible or a Way voice is loaded (`isPlayingWayVoice`: playing, walker-paused, gate-paused, or guide-held).
3. A new whisper while another is AUDIBLE and nothing higher is playing replaces it: `startWhisperPlayback` begins with `stopWhisper()`, which also clears the slot.
4. A starting guide prompt stops an audible whisper and clears the slot (`interruptForVoiceGuide`, §2.4).
5. A starting Way voice stops an audible whisper and keeps the slot (`interruptForWayVoice`, §2.4). The slot survives a whole run of voices; iOS's test pins this:

```swift
        wait(for: [voiceAFinished], timeout: 5)
        XCTAssertEqual(finishCount, 1)
        XCTAssertEqual(AudioPriorityQueue.shared._test_pendingWhisperURL, whisper2,
                       "a whisper held across a run of Way voices must survive the next voice's start")
```
> UnitTests/AudioPriorityQueueTests.swift:61-64@7c200bf

```swift
        AudioPriorityQueue.shared.interruptForVoiceGuide()
        XCTAssertNil(AudioPriorityQueue.shared._test_pendingWhisperURL,
                     "a guide prompt beginning must still clear any held whisper")
```
> UnitTests/AudioPriorityQueueTests.swift:94-96@7c200bf

6. A parked whisper starts when both higher players are quiet, re-checked on the Way voice's falling edge and on the guide's end signal (§2.2).
7. The parked whisper keeps only its URL. It restarts at the default 0.8 (`startWhisperPlayback(url: url)`, §2.2). Every call site passes the default today, so nothing audible changes.

**5.3 What happens to a parked whisper when its time passes or the walker moves on.** Nothing. The slot has no timestamp, no distance check, and no expiry. A whisper parked behind a long Way voice plays when that voice ends, wherever the walker then is. It is cleared only by a guide prompt starting, a newer whisper, or `stopWhisper()`. `stopWhisper()` has no caller outside the queue and the tests:

```swift
    func stopWhisper() {
        // Cleared above the guard: "stop the whisper" must also drop one that
        // is merely waiting to start, or it surfaces after the walker
        // silenced it.
        pendingWhisperURL = nil
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:54-58@7c200bf

(`git grep -n "stopWhisper" 7c200bf -- Pilgrim` finds only `AudioPriorityQueue.swift`.) So a parked whisper also survives walk pause, meditation, a recording, and walk end. At walk end it plays after the walk has finished (defect D2, §12).

**5.4 Whispers do not honor the engine's gates.** Whisper playback checks only the guide and the Way voice. An audible whisper during a meditation or a paused walk is pre-Honor iOS behavior and outside this slice.

**5.5 Android today.** `WhisperPlayer.play` has no queue. It cancels the previous play job and starts at once (`app/src/main/java/org/walktalkmeditate/pilgrim/data/whisper/WhisperPlayer.kt:126-138@5ea4029b`). Autoplay in `:tracker` calls it straight from the proximity handler (`app/src/main/java/org/walktalkmeditate/pilgrim/service/BackgroundWhisperAutoPlayer.kt:239-249@5ea4029b`). The preview channel (`WhisperPlayer.kt:145-158@5ea4029b`) matches iOS's unqueued preview.

### 6. The soundscape duck for a Way voice (pin 4)

**6.1 Level and ramp.** A Way voice ducks the soundscape to the absolute `voiceGuideDuckLevel` (default 0.15), ramped over 0.5 s (`setVolume(_, animated: true)`, §1). It ducks at `start()`, after interrupting any whisper, so the level it records as "before" is the whisper-restored level:

```swift
    private func start(url: URL, volume: Float) {
        // Between two voices in one run the session stays activated and the
        // soundscape stays ducked from the ORIGINAL level: releasing and
        // re-ducking here was an audible swell plus session churn every few
        // hundred metres.
        pending = nil
        player?.stop()
        finish(notify: false, releaseSession: false)
        AudioPriorityQueue.shared.interruptForWayVoice()
        if preDuckVolume == nil {
            preDuckVolume = soundscape.currentTargetVolume
            soundscape.setVolume(Float(UserPreferences.voiceGuideDuckLevel.value), animated: true)
        }
        coordinator.activate(for: .playbackOnly, consumer: "honor-voice")
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:145-158@7c200bf

The duck is applied even when no soundscape is playing; then only the stored target changes.

**6.2 Restore timing.** The duck is restored in `finish` on every exit except `start()` retiring a still-loaded voice: a natural end, `stop()` (skip, drop, walker switching voices, reply, walk end, discard), a decode error, a failed `play()`.

```swift
    /// `releaseSession: false` is the one-run case — `start()` retiring the
    /// previous voice before the next one begins. Every other exit gives the
    /// soundscape and the session back.
    private func finish(notify: Bool, releaseSession: Bool = true) {
        elapsedTimer?.invalidate()
        elapsedTimer = nil
        player = nil
        elapsedSeconds = 0
        isPlayingWayVoice = false
        pausedByGuide = false
        if releaseSession {
            if let volume = preDuckVolume {
                soundscape.setVolume(volume, animated: true)
                preDuckVolume = nil
            }
            coordinator.deactivate(consumer: "honor-voice")
        }
        if notify { onFinished?() }
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:219-237@7c200bf

Pausing keeps the duck. A walker-paused, gate-paused, or guide-held voice leaves the soundscape low (for a guide hold the guide owns it). The duck is released only when the voice is gone.

Between two voices of an engine run the duck IS restored and then re-applied. Voice A's natural end calls `finish(notify: true)` with the default `releaseSession: true`. It restores, deactivates, and only then calls `onFinished`, which starts voice B in the same call stack (§4.5). B re-captures the just-restored target and ducks again. Both are 0.5 s ramps inside one synchronous stack, so nothing is heard. But the session consumer is released and re-activated. The `start()` comment's "one run" case (no release) covers only a walker tap or engine start that replaces a voice still loaded. Android should keep the soundscape ducked across a chained start; the audible result is the same.

**6.3 Interaction with the whisper's duck.** The two ducks never stack. A whisper cannot start while a Way voice is loaded. A Way voice interrupts an audible whisper first; the whisper restores to its own "before", and only then does the Way voice capture and duck. The whisper duck is relative (current × 0.3); the Way voice duck is absolute.

**6.4 Interaction with the guide's duck.** Exactly one owner at a time (§3.1-3.3). Summary:
- Voice then prompt: the voice hands its "before" to the guide and gives up ownership; the guide ducks to the same level; the guide restores on its way out; a held voice resumes and re-ducks.
- Prompt then voice (voice parked): no duck by the voice until the prompt ends; the guide restores; `start()` then captures the restored level and ducks.
- Voice resumed while a prompt is still speaking (engine `voiceResume` after a pause during the prompt): `resume()` deliberately skips the duck because the guide owns it. When the prompt ends it restores the walker's level, and the voice then keeps speaking over an unducked soundscape until it ends (defect D3).

**6.5 A soundscape started mid-voice is not ducked.** `SoundscapePlayer.play` overwrites the target with the walker's soundscape volume and fades up over 2.0 s (§1). The Way voice does not re-duck. When the voice ends, `finish` restores to the "before" it captured at its own start, which may differ from the level the walker just started (defect D4). Starting points during a walk: the options sheet's toggle and picker (`ActiveWalkView.swift:265,270@7c200bf`) and the meditation start (below).

```swift
                onToggleSoundscape: { viewModel.soundManagement.toggleSoundscape() },
                onSelectSoundscape: { scapeId in
                    UserPreferences.selectedSoundscapeId.value = scapeId
                    if let asset = AudioManifestService.shared.asset(byId: scapeId),
                       AudioFileStore.shared.isAvailable(asset) {
                        SoundscapePlayer.shared.play(asset, volume: Float(UserPreferences.soundscapeVolume.value))
                    }
                },
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:265-272@7c200bf

**6.6 The meditation soundscape.** A sitting starts the soundscape after the start bell, at least 0.5 s later. The sitting closes the engine gate, so any Way voice is paused (not stopped) while the soundscape plays at full level. The paused voice still holds its `preDuckVolume`.

```swift
    func onMeditationStart() {
        cancelPending()
        playBell(id: UserPreferences.meditationStartBellId.value)
        let bellDuration = bellPlayer.currentDuration
        let delay = max(0.5, bellDuration)
        let work = DispatchWorkItem { [weak self] in
            self?.startSoundscape()
        }
        pendingSoundscapeStart = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    func onMeditationEnd() {
        cancelPending()
        stopSoundscape()
        let work = DispatchWorkItem { [weak self] in
            self?.playBell(id: UserPreferences.meditationEndBellId.value)
        }
        pendingEndBell = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5, execute: work)
    }
```
> Pilgrim/Models/Audio/SoundManagement.swift:68-88@7c200bf

The card copy that promises this: "your soundscape holds while you sit" (`WayPlaceCard.swift:139@7c200bf`, quoted in §10.3).

**6.7 Android today.** Android's soundscape ducks itself on any `LOSS_TRANSIENT_CAN_DUCK` and restores on `GAIN` (`app/src/main/java/org/walktalkmeditate/pilgrim/audio/soundscape/ExoPlayerSoundscapePlayer.kt:378-398@5ea4029b`), to 0.3 × the user's volume with no ramp. iOS's Way-voice duck is 0.15 absolute (user-settable) with a 0.5 s ramp. U18 must pick the level source; see Resolutions.

### 7. Playback rate (pin 5)

**7.1 The options.** Four rates, in this order: `static let rates: [Float] = [1, 1.25, 1.5, 2]` (`WayVoicePlayer.swift:32@7c200bf`, quoted in §1).

**7.2 The UI that sets them.** One button on a voice card's transport row cycles 1 → 1.25 → 1.5 → 2 → 1. The chip has no rate control.

```swift
    /// 1× → 1.25× → 1.5× → 2× → 1×, the same ladder as the post-walk player.
    func cycleVoiceRate() {
        let rates = WayVoicePlayer.rates
        let next = rates[((rates.firstIndex(of: voiceRate) ?? 0) + 1) % rates.count]
        voiceRate = next
        wayVoicePlayer?.setRate(next)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:413-419@7c200bf

(The comment is wrong. The post-walk ladder is `[1.0, 1.5, 2.0]`, `Pilgrim/Scenes/WalkSummary/AudioPlayerModel.swift:11@7c200bf`. The Way voice ladder is the one to port.)

```swift
            Button { onTouch(); onCycleRate() } label: {
                Text(rateLabel)
                    .font(Constants.Typography.caption)
                    .foregroundColor(rate > 1 ? .parchment : .stone)
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(rate > 1 ? Color.stone : Color.stone.opacity(0.12))
                    .cornerRadius(4)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Playback speed, \(rateLabel)")
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:193-203@7c200bf

```swift
    private var rateLabel: String {
        rate.truncatingRemainder(dividingBy: 1) == 0 ? String(format: "%.0fx", rate) : String(format: "%gx", rate)
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:246-248@7c200bf

Labels: "1x", "1.25x", "1.5x", "2x" (lowercase x, no space). The button is filled stone with parchment text above 1x, faint stone otherwise. The card reads the view model's `voiceRate`. The button works with no voice playing; the rate then applies to the next voice.

**7.3 How the rate is applied.** `setRate` sets the player's stored rate and the current voice. Each new voice starts at the stored rate. Rate must be enabled before prepare:

```swift
    func setRate(_ rate: Float) {
        playbackRate = rate
        player?.rate = rate
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:121-124@7c200bf

```swift
            // Rate must be enabled before the player is prepared or it stays 1×.
            p.enableRate = true
            p.rate = playbackRate
            p.prepareToPlay()
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:163-166@7c200bf

The rate applies to everything the player plays: engine voices, tapped replays, and replies.

**7.4 Lifetime.** Two copies with different lifetimes:
- The view model's `voiceRate`: per walk. It starts at 1 and `teardownHonor()` resets it to 1.

```swift
    /// The walker's chosen voice speed for this walk; 1× at the start of every walk.
    @Published var voiceRate: Float = 1
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:97-98@7c200bf

```swift
        voiceRate = 1
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:152@7c200bf

- The player's `playbackRate`: per app process. The player is the shared singleton, and nothing resets its rate. `stop()`, `finish()`, and `teardownHonor()` never call `setRate(1)`.

Nothing is persisted (no `UserPreferences` key). A relaunch starts at 1x.

The result: after a walk that ended at 2x, the next honor walk in the same process plays its first voices at 2x while the card says "1x", until the walker taps the rate button (which moves to 1.25x, computed from the view model's 1). This is the plan's deferred question. It is a defect, not behavior to match (D5). The documented intent is "1× at the start of every walk", and the player's own comment says "kept across the voices of one walk" (`WayVoicePlayer.swift:28@7c200bf`).

### 8. `playReply` (pin 6)

**8.1 Confirmed: it gives up the active voice and plays the reply through the same player.**

```swift
    /// The card's "your reply" button comes through here rather than touching
    /// the player directly: one voice plays at a time, so a Way voice must be
    /// given up rather than silently replaced under a chip that still claims
    /// it is playing. The engine gets its turn back when the reply ends,
    /// through the player's `onFinished`.
    func playReply(url: URL) {
        wayVoicePlayer?.stop()
        activeVoice = nil
        isVoicePaused = false
        wayVoicePlayer?.play(url: url, volume: Float(UserPreferences.voiceGuideVolume.value))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:65-75@7c200bf

Step by step:
1. `stop()` drops any parked voice, stops the audible one, and runs `finish(notify: false)`. The soundscape is restored, the session consumer is released, `isPlayingWayVoice` goes false, and `onFinished` does NOT fire, so the engine is not told.
2. `activeVoice = nil`. The chip disappears. The card's transport shows "play" again, because the card's `isPlaying` is `activeVoice == moment`.
3. `play` goes through the guide check. If a prompt is speaking, the reply is parked in `pending` like any voice.
4. The reply plays at the full `voiceGuideVolume`, never the ambient half.
5. `start()` ducks again and re-activates the session. The restore in step 1 and this duck are in one synchronous stack, so nothing is heard.

The buttons that call it: the voice card's "your reply" (accessibility "Play your earlier reply") and the stage arrival card's "your reply" ("Play your reply", stage only).

```swift
            if let existingReply {
                Spacer()
                Button { onTouch(); onPlayReply(existingReply) } label: {
                    Label("your reply", systemImage: "play.circle")
                        .font(Constants.Typography.caption).foregroundColor(.stone)
                        .frame(minHeight: 44).contentShape(Rectangle())
                }
                .accessibilityLabel("Play your earlier reply")
            }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:210-218@7c200bf

The reply URL comes from an earlier honoring's mapping. A mapping whose file is gone reads as no reply, so the button is not shown:

```swift
    /// The walker's earlier reply to `voice`, from a previous honoring of the
    /// same Way. A mapping whose recording is gone reads as no reply at all —
    /// `mediaURL(for:)` returns nil for a file that isn't there.
    func existingReplyURL(for voice: WayMoment) -> URL? {
        guard let way, let n = Self.originIndex(of: voice),
              let relative = honorSenses.store().replies(for: way.id)[n] else { return nil }
        return mediaURL(for: .recording(relativePath: relative))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:43-50@7c200bf

**8.2 Does a reply count as the active voice?**
- For the UI: no. `activeVoice` is nil, so there is no chip and no pause or skip. The walker cannot pause a reply. Tapping the card's play button starts the card's own voice, which replaces the reply (§4.4).
- For whispers: yes. The reply sets `isPlayingWayVoice`, so whispers park behind it.
- For the guide: yes. A prompt pauses and resumes it like any voice (§3).
- For the engine's gates: indirectly. If the engine still has a current voice (the one the reply displaced), a gate edge sends `.voicePause` or `.voiceResume` to the player, which pauses or resumes the reply. The view model's `isVoicePaused` flips, but with no chip nothing shows it.
- For the engine's turn: only if a voice was active. If the engine had a current voice, its `playing` stays set (step 1 never told it), so no new voice starts until the reply ends. If the engine had NO current voice, a voice reached during the reply starts normally, and its `start()` silently replaces the reply (no notify).

**8.3 For "heard": no.** `playReply` does not touch `heardVoiceIDs`. Replies are never counted as heard.

**8.4 What happens when the reply ends.** `onFinished` runs (§4.5). `finished` is nil (activeVoice was cleared), so no card is scheduled to retire, and `honorEngine?.voiceDidFinish()` gives the engine its turn: the next queued voice starts if the gates are open. The Way voice the reply displaced is NOT resumed. It was given up, and it stays heard. A reply that fails to decode ends the same way.

`voiceDidFinish` when the engine had no current voice is harmless: it sets `playing = nil` and tries the queue.

```swift
    mutating func voiceDidFinish(gates: Gates) -> [Action] {
        playing = nil
        isVoicePaused = false
        return startNextIfPossible(gates: gates)
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:111-115@7c200bf

**8.5 Recording a reply.** This is in cluster scope only for its audio edge. Starting a reply toggles the recorder. The recording gate then pauses the engine's current voice (§4.2), and the guide stops any prompt:

```swift
        isRecordingVoicePublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] recording in
                if recording { self?.player.stop() }
                self?.scheduler?.updateIsRecordingVoice(recording)
            }
            .store(in: &walkStateBindings)
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuideManagement.swift:110-116@7c200bf

A reply still recording at walk end is finalized by the builder's pre-snapshot flush. This is why `stop()` leaves the recorder running (`ActiveWalkViewModel.swift:397-402@7c200bf`, §12).

### 9. What "heard" means (pin 7)

**9.1 Heard = the voice was handed to the player with a file on disk. Not started, not a fraction played, not finished.** `startVoice` marks the voice heard BEFORE calling `play`. That covers a voice parked behind a prompt, one that fails to decode, and one whose `play()` returns false:

```swift
    /// A voice whose file is gone was never heard: hand the turn straight
    /// back to the engine so the next one can start.
    private func startVoice(_ moment: WayMoment) {
        guard case .voice(_, _, let kind, let media) = moment.kind, let url = mediaURL(for: media) else {
            honorEngine?.voiceDidFinish()
            return
        }
        activeVoice = moment
        isVoicePaused = false
        heardVoiceIDs.insert(moment.id)
        refreshHonorPins()
        wayVoicePlayer?.play(url: url, volume: Self.voiceVolume(for: kind))
        // The voice's own card rises with it — the waveform, the place, the
        // reply — and retires itself after the voice unless the walker
        // touches it, so an unanswered voice never leaves a card to close.
        showCard(for: moment)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:214-230@7c200bf

A walker-tapped replay marks heard the same way (`togglePlayback`, §4.4). The comment there gives the intent:

```swift
    /// From the card or the chip: pause or resume the active voice, or replay
    /// another voice outside the engine's queue. A heard voice must mean a
    /// played voice, so this is a no-op before the walk has a player at all —
    /// otherwise a pin tap before Begin could mark a voice heard with
    /// nothing behind it to play.
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:384-388@7c200bf

The only thing that keeps a voice un-heard is a file that does not resolve locally (§10.1). A voice that fails to decode or play is still counted (D7, low severity).

**9.2 Where it is recorded.** Only in memory, in the view model:

```swift
    @Published var heardVoiceIDs: Set<String> = []
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:118@7c200bf

It feeds two things:
- The Way's voice pins on the map (a heard pin looks different):

```swift
            case .voice: kind = .wayVoice(id: moment.id, heard: heardVoiceIDs.contains(moment.id))
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:130@7c200bf

- The arrival card's count, read at the moment of arrival: `voicesHeard: heardVoiceIDs.count` (`ActiveWalkViewModel+Honor.swift:254@7c200bf`), shown as

```swift
        if !card.isStage, card.voicesHeard > 0 {
            parts.append(card.voicesHeard == 1 ? "one voice heard" : "\(card.voicesHeard) voices heard")
        }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:422-424@7c200bf

It is not in the walk checkpoint (only `wayId`, `honorProgressFrac`, `honorArrived`, `WalkCheckpoint.swift:20-25@7c200bf`) and not in the link or the summary. The summary counts every voice on the Way instead:

```swift
    /// Every voice the Way carries, not the subset this walk played — the
    /// arrival card's `voicesHeard` is the one that counts what was heard.
    let voicesAlongTheWay: Int
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:8-10@7c200bf

A crash-recovered walk therefore starts with an empty heard set. For Android, heard state that must survive a `:tracker` revival needs a home in the session tables (U14/U17 decide). iOS keeps none.

Non-voice moments have their own set, `reachedMomentIDs`, filled on `.momentReached` (`ActiveWalkViewModel.swift:119-122@7c200bf`).

### 10. When a voice fails to play, and what the place card shows (pin 8)

**10.1 The file does not resolve (missing file, path escaping its folder, or a Photos asset).** `mediaURL(for:)` returns nil when the file is absent:

```swift
    private static func resolvedMediaURL(_ url: URL, within base: URL) -> URL? {
        let resolved = url.standardizedFileURL
        let baseComponents = base.standardizedFileURL.pathComponents
        guard resolved.pathComponents.count > baseComponents.count,
              Array(resolved.pathComponents.prefix(baseComponents.count)) == baseComponents else { return nil }
        return FileManager.default.fileExists(atPath: resolved.path) ? resolved : nil
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:371-377@7c200bf

For an own walk the media is `.recording(relativePath)` under Documents (`ActiveWalkViewModel+Honor.swift:356-358@7c200bf`). It goes missing if the walker deleted that recording. Shared-walk branch point: `.file(relative)` under the Way's media folder is nil until the media download lands (spec B).

- Engine path: `startVoice` hands the turn back at once. No card rises, no chip appears, nothing is marked heard, and no sound or haptic plays (§9.1 quote, lines 216-220).
- Tap path: the card can still be opened from its pin (`showWayCard`). Play is a silent no-op (`togglePlayback`'s `guard ... let url = mediaURL(for: media) else { return }`, §4.4). The waveform slot shows the empty placeholder bar, the clock reads "0:00 / m:ss" from the Way's recorded duration, and the transcript line shows if there is one. No error text exists.

```swift
                if let waveform {
                    WaveformBarView(samples: waveform, progress: duration > 0 ? min(1, elapsed / duration) : 0, isPlaying: playing) { fraction in
                        onTouch(); onSeek(fraction)
                    }
                    .frame(height: 28)
                    .accessibilityLabel("Their voice; drag to move through it")
                } else {
                    RoundedRectangle(cornerRadius: 4).fill(Color.fog.opacity(0.15)).frame(height: 28)
                }
                Text("\(clock(elapsed)) / \(clock(duration))")
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:181-190@7c200bf

**10.2 The file exists but will not open or will not start (decode error, unsupported data, `play()` returns false).** The player reports "finished" at once, with notify:

```swift
        do {
            let p = try AVAudioPlayer(contentsOf: url)
            p.delegate = self
            p.volume = volume
            // Rate must be enabled before the player is prepared or it stays 1×.
            p.enableRate = true
            p.rate = playbackRate
            p.prepareToPlay()
            guard p.play() else {
                finish(notify: true)
                return
            }
            player = p
            isPlayingWayVoice = true
            elapsedSeconds = 0
            startElapsedTimer()
        } catch {
            print("[WayVoicePlayer] playback error: \(error)")
            finish(notify: true)
        }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:159-178@7c200bf

iOS's test pins that a failed start reports finished and releases the session:

```swift
    func testMissingFileReportsFinishedAndReleasesTheSession() {
        let player = WayVoicePlayer()
        let finished = expectation(description: "finished")
        player.onFinished = { finished.fulfill() }
        player.play(url: URL(fileURLWithPath: "/nonexistent/voice.m4a"), volume: 0.8)
        wait(for: [finished], timeout: 1)
        XCTAssertFalse(player.isPlayingWayVoice)
        XCTAssertFalse(AudioSessionCoordinator.shared._test_isConsumerActive("honor-voice"))
    }
```
> UnitTests/Honor/WayVoicePlayerTests.swift:34-42@7c200bf

What the walker sees, in order. All of it runs synchronously inside `startVoice`, because `start()` is synchronous:
1. `startVoice` sets `activeVoice`, marks heard, calls `play`.
2. `finish(notify: true)` restores the duck and releases the session. `onFinished` clears `activeVoice`, gives the engine its turn (the next voice may start now, inside this call), and schedules the failed voice's card to retire in 20 s.
3. Control returns to `startVoice`, which runs `showCard(for: moment)` and puts the failed voice's card on top. If step 2 started another voice, that voice's card is pushed down beneath the failed one.

The failed card shows the play icon (not playing), "0:00 / m:ss", a waveform or the placeholder (the waveform reader returns nil for an unreadable file, `WaveformGenerator.swift:5-8@7c200bf`), and the reply row. It retires by itself after `cardRetireSeconds` unless touched:

```swift
    /// The seconds a finished voice's card stays before retiring on its own.
    static let cardRetireSeconds: TimeInterval = 20
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:305-306@7c200bf

```swift
    func retireIfUntouched(_ moment: WayMoment) {
        guard !touchedCardIDs.contains(moment.id), activeVoice != moment else { return }
        honorCards.removeAll { $0 == moment }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:314-317@7c200bf

Tapping play retries through `togglePlayback`. It fails the same way, and its `onFinished` calls `voiceDidFinish` again.

The same applies to a voice parked behind a prompt: its failure surfaces when `start()` finally runs at the prompt's end.

**10.3 A decode error mid-voice.** The same as a natural end: `finish(notify: true)`, the next voice may start, the card retires in 20 s, and the voice stays heard.

```swift
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        DispatchQueue.main.async { [weak self] in
            guard let self, player === self.player else { return }
            self.finish(notify: true)
        }
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:136-141@7c200bf

Both delegate callbacks are identity-guarded, so a late callback from a replaced or stopped player does nothing:

```swift
    /// The delegate hands back the exact `AVAudioPlayer` it was invoked on;
    /// `stop()`/`start()` always nil or replace `player` first, so a callback
    /// that lands after either is guaranteed to fail the identity check.
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:126-128@7c200bf

**10.4 Audio-session failure.** It never reaches the Way voice. The coordinator logs and swallows every `setCategory` or `setActive` error:

```swift
        } catch {
            print("[AudioSessionCoordinator] Failed to apply mode \(mode): \(error)")
        }
```
> Pilgrim/Models/Audio/AudioSessionCoordinator.swift:223-225@7c200bf

The player then tries anyway. If `play()` returns false, §10.2 applies. During an interruption the coordinator does not even try (§11.3).

**10.5 Other card copy the audio paths touch.** Recording a reply swaps the transport row for "recording your reply here" plus a stop button ("Stop recording your reply"). The sitting row reads "Sit?" and "your soundscape holds while you sit":

```swift
            Text("your soundscape holds while you sit")
                .font(Constants.Typography.caption).foregroundColor(.fog)
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:139-140@7c200bf

Transport accessibility labels: "Pause their voice" / "Play their voice" (`WayPlaceCard.swift:179@7c200bf`). Chip: "paused" / "listening", "Resume their voice" / "Pause their voice", "Skip this voice" (`WayPlaceCard.swift:336-342@7c200bf`).

### 11. Audio session, interruptions, and route changes (pin 9)

**11.1 Category, mode, options.** Every player (Way voice, whisper, guide, soundscape, Seek) asks for `.playbackOnly`. The coordinator joins all live consumers into one session state:

```swift
    /// | live consumer modes                            | session state applied                  |
    /// |------------------------------------------------|----------------------------------------|
    /// | none                                           | inactive (idle)                        |
    /// | playbackOnly only                              | .playback + .mixWithOthers             |
    /// | recordingOnly only                             | .playAndRecord (speaker, BT-HFP)       |
    /// | recordAndPlay, or recordingOnly + playbackOnly | .playAndRecord + all options           |
```
> Pilgrim/Models/Audio/AudioSessionCoordinator.swift:173-178@7c200bf

```swift
            switch mode {
            case .idle:
                try session.setActive(false, options: .notifyOthersOnDeactivation)
            case .playbackOnly:
                try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
                try session.setActive(true, options: [])
            case .recordingOnly:
                try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
                try session.setActive(true, options: [])
            case .recordAndPlay:
                try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP, .mixWithOthers])
                try session.setActive(true, options: [])
            }
```
> Pilgrim/Models/Audio/AudioSessionCoordinator.swift:210-222@7c200bf

So a Way voice plays in `.playback`, mode `.default`, `[.mixWithOthers]`. It never uses `.duckOthers`: other apps' audio (a podcast) is not ducked, and all ducking is manual and in-app. While the walker records, the session is `.playAndRecord` + `[.defaultToSpeaker, .allowBluetoothHFP, .mixWithOthers]`, because a paused Way voice keeps its consumer (§11.2). Background playback is allowed by the `audio` background mode (`Pilgrim/Support Files/Info.plist:52-56@7c200bf`).

Android analogue (plan Global Constraints): `USAGE_MEDIA` with a `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` request per consumer, as the guide and soundscape players already do (`app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/ExoPlayerVoiceGuidePlayer.kt:237-262@5ea4029b`).

**11.2 Activation and deactivation points for `"honor-voice"`.**
- Activated in `start()`, before the `AVAudioPlayer` is created (§6.1 quote, line 158). Never on `play()` into `pending`, never on `resume()`.
- Deactivated in `finish(releaseSession: true)`: natural end, `stop()`, a decode error, a failed start (§6.2).
- NOT deactivated on any pause (walker, gate, guide hold), nor when `start()` retires a still-loaded voice.
- The coordinator's `deactivate` ignores an absent consumer, so an extra `stop()` is safe:

```swift
    func deactivate(consumer: String) {
        queue.sync {
            guard consumerModes.removeValue(forKey: consumer) != nil else { return }
            applyResolvedModeLocked()
        }
    }
```
> Pilgrim/Models/Audio/AudioSessionCoordinator.swift:64-69@7c200bf

**11.3 Interruptions (a phone call, Siri, an alarm).** The coordinator broadcasts `.began` / `.ended(shouldResume:)` only to registered observers. It holds off re-activation until `.ended`, or until a 400 ms fallback after the app becomes active:

```swift
    private let didBecomeActiveFallbackDelay: DispatchTimeInterval = .milliseconds(400)
```
> Pilgrim/Models/Audio/AudioSessionCoordinator.swift:39@7c200bf

```swift
    private func applyResolvedModeLocked() {
        // Between .began and .ended another app owns the session —
        // setActive(true) would fail and must not run. The consumer map keeps
        // tracking the desired state; .ended (or app re-activation when
        // .ended never arrives) applies it.
        guard !isInterrupted else { return }
        apply(resolvedMode())
    }
```
> Pilgrim/Models/Audio/AudioSessionCoordinator.swift:196-203@7c200bf

The registered observers at the pin are exactly four: `SeekSoundPlayer.swift:188`, `SoundscapePlayer.swift:25`, `IntentionVoiceRecorder.swift:35`, and `VoiceRecordingManagement.swift:38` (`git grep -n "addInterruptionObserver" 7c200bf -- Pilgrim` also lists the definition at `AudioSessionCoordinator.swift:75`). **The Way voice player, the whisper queue, and the guide player register none.**

The app's own comment names the platform behavior that follows:

```swift
    /// AVAudioPlayer does not auto-resume after a system interruption (call,
    /// Siri, alarm) — without this, the soundscape stays silent forever while
    /// `isPlaying` claims otherwise and the loop monitor reads a frozen
    /// `currentTime`.
```
> Pilgrim/Models/Audio/SoundscapePlayer.swift:30-33@7c200bf

What a playing Way voice does in a call, derived from the code above:
1. The system pauses the `AVAudioPlayer`. Nothing in the app reacts.
2. `isPlayingWayVoice` stays true, so whispers park for as long as the voice is stuck.
3. `audioPlayerDidFinishPlaying` never fires, so `onFinished` never reaches the engine. The engine's `playing` stays set, and no later voice can start.
4. The chip still reads "listening" with a frozen clock. The view model's `isVoicePaused` is false.
5. After the call nothing resumes the voice. It stays stuck until the walker taps the chip twice (pause, then resume), skips, taps another voice, or a stop path runs (the walk ends; a gate-paused drop does not apply, because the engine thinks the voice is still playing, §4.3).

A gate edge during the stuck state resumes it. `.voicePause` then `.voiceResume` (for example, the walker pauses and resumes the walk) calls `resume()`, which calls `player.play()`. That is an accidental escape. This is defect D8. The soundscape, by contrast, resumes on `.ended(shouldResume: true)` (`SoundscapePlayer.swift:34-55@7c200bf`).

A voice parked in `pending` behind a prompt when a call comes: the guide player is also paused by the system and never finishes, so the parked voice waits until something stops the guide.

Recording and calls (context for the recording gate): the talk recorder stops on a real call via `CXCallObserver`, and a transient interruption does not end a talk:

```swift
    /// A phone call — incoming-ringing, connected, or outgoing — takes the mic,
    /// so the talk must stop. CXCallObserver reports any active call here
    /// (`!hasEnded`), which is the reliable signal; an *unanswered* incoming
    /// call (rings, never connects) is caught this way too.
```
> Pilgrim/Models/Walk/WalkBuilder/Components/VoiceRecordingManagement.swift:342-345@7c200bf

**11.4 Route changes (headphones unplugged, Bluetooth lost).** iOS observes none. `git grep -n "routeChangeNotification\|AVAudioSession.routeChange\|oldDeviceUnavailable" 7c200bf -- Pilgrim` finds nothing. A playing Way voice keeps playing on the new route (the speaker). This is `AVAudioPlayer`'s default; it does not pause itself on route loss. That is platform knowledge, not something the code states. The plan's `BECOMING_NOISY` → pause is therefore an Android convention with no iOS counterpart. The existing Android players already do it: the guide stops, the soundscape stops (`ExoPlayerVoiceGuidePlayer.kt:83-95@5ea4029b`, `ExoPlayerSoundscapePlayer.kt:166-170@5ea4029b`). It needs an R6 record (see Resolutions).

### 12. A playing or queued Way voice at pause, meditation, walk finish, and discard (pin 10)

**12.1 Walk pause (manual `.paused` or `.autoPaused`).** The engine's paused gate closes (`$status.map { $0 != .recording }`, §4.1).
- A playing engine voice gets `.voicePause`. The view model sets `isVoicePaused = true` (chip "paused"); the player pauses and keeps its position, its duck, and its session consumer; whispers stay parked.
- Queued (engine) voices wait. The engine starts nothing while paused. Its fix handler has no pause check (`HonorEngine.swift:148-162@7c200bf`), so a voice reached while paused joins the queue and speaks on resume. The drop rule still runs, so a paused walker who keeps moving can drop voices 300 m behind.
- A voice parked in the player behind a prompt: `pause()` does not clear `pending`. The guide scheduler starts no new prompt while paused, but a prompt already speaking finishes, and then the parked voice STARTS in the paused walk while the chip says "paused" (D1).
- On resume (`.recording`): `.voiceResume` → `resume()` → re-duck if needed → play from the position.
- The guide never starts a prompt while paused (`VoiceGuideScheduler.swift:109@7c200bf`).

**12.2 Meditation start.** `startMeditation` stops an in-flight recording, sets `isMeditating`, and runs the meditation sound ritual:

```swift
    func startMeditation() {
        guard !isMeditating else { return }
        if isRecordingVoice {
            voiceRecordingManagement.stopRecording()
        }
        meditationStartDate = Date()
        isMeditating = true
        soundManagement.onMeditationStart()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:473-481@7c200bf

- The meditating gate pauses the engine's voice exactly as walk pause does. The voice is paused, not stopped, so it keeps its place and resumes when the sitting ends.
- The walk guide pauses its scheduler, but a prompt already speaking is not stopped by that sink:

```swift
                if meditating {
                    // Only remember a pause this sink itself caused — ending
                    // meditation must not force-resume a guide the user
                    // paused by hand.
                    if !self.isPaused {
                        self.pauseGuide()
                        self.wasPausedByMeditation = true
                    }
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuideManagement.swift:122-129@7c200bf

- If the meditation guide is on, `MeditationView` starts `MeditationGuideManagement`, whose `startGuiding` calls `player.stop()` on the shared guide player (`MeditationGuideManagement.swift:14-25@7c200bf`, `MeditationView.swift:585-601@7c200bf`). That emits `playbackDidFinish`, so a Way voice parked in `pending` starts inside the sitting (D1 again).
- Meditation-guide prompts use the same `VoiceGuidePlayer`, so each one calls `pauseForGuide()`. On a gate-paused voice that only hands over the duck (§3.1 rule 4).
- The soundscape starts after the bell (§6.6), un-ducked.
- When the sitting ends, the gate opens → `.voiceResume` → the voice resumes where it stopped.
- A Way voice the walker paused before sitting is resumed when the sitting ends (§4.2, D6).

**12.3 Walk finish (`stop()`).** The order matters.

```swift
    func stop() {
        teardownSeek()
        // A reply still recording here keeps its origin through teardown, and
        // the recorder is deliberately left running: only the pre-snapshot
        // flush inside `builder.setStatus(.ready)` below finalizes an
        // in-flight recording synchronously. Stopping the recorder here would
        // hand that commit to AVAudioRecorder's asynchronous delegate, which
        // lands after the walk is snapshotted — losing the audio entirely.
        teardownHonor()
        cancellables.removeAll()
        proximityService.stopListening()
        // Checkpoint deletion happens in MainCoordinator's saveWalk success
        // callback (AF1) — a failed save must leave the checkpoint on disk
        // so launch recovery can restore the walk.
        sessionGuard?.stop()
        finalizeMeditation()
        soundManagement.onWalkEnd()
        voiceGuideManagement.stopGuiding()
        WalkActivityManager.shared.end()
        builder.setStatus(.ready)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:395-415@7c200bf

`teardownHonor()` is the Honor audio exit:

```swift
    func teardownHonor() {
        guard honorEngine != nil || wayVoicePlayer != nil else { return }
        if let engine = honorEngine, engine.isAnchoredOnWay {
            honorStageOutcome = HonorStageOutcome(progressFrac: engine.progressFrac,
                                                  arrived: engine.phase == .arrived)
        }
        honorGeneration += 1
        honorCancellables.removeAll()
        honorEngine?.stop()
        honorEngine = nil
        wayVoicePlayer?.stop()
        // The player outlives this walk (it's the shared singleton in
        // production); drop the closure here so it can't call back into a
        // torn-down view model once a future walk replaces it.
        wayVoicePlayer?.onFinished = nil
        wayVoicePlayer = nil
        activeVoice = nil
        isVoicePaused = false
        honorCards.removeAll()
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:128-146@7c200bf

Sequence at Finish:
1. The generation bump makes every pending Honor `asyncAfter` (card retire, soft-tap caption) a no-op.
2. The engine stops; nothing more is queued.
3. `wayVoicePlayer.stop()`: the parked voice is dropped, the audible or paused voice stops, the soundscape duck is restored, `"honor-voice"` is released, and `onFinished` does not fire. The voice simply stops, with no fade.
4. `onFinished = nil`, so a late callback cannot reach the torn-down view model.
5. The soundscape stops with its 2.0 s fade, and the walk-end bell plays (`SoundManagement.swift:62-66@7c200bf`).
6. The guide stops (`stopGuiding` → `player.stop()` → `playbackDidFinish`).
7. Whispers are NOT stopped. An audible whisper plays to its end. A parked whisper is released by step 3's falling edge or step 6's end signal, and starts after the walk has ended (D2).

The rate is not reset on the player (§7.4). A reply still recording is finalized by the pre-snapshot flush.

**12.4 Discard (`cancel()`).** The same audio exits, preceded by dropping the reply-in-progress (its file and session go with it). There is no `builder.setStatus(.ready)` and no pre-snapshot flush.

```swift
    func cancel() {
        discardPendingReply()
        teardownSeek()
        teardownHonor()
        cancellables.removeAll()
        proximityService.stopListening()
        sessionGuard?.stopAndCleanup()
        soundManagement.onWalkEnd()
        voiceGuideManagement.stopGuiding()
        WalkActivityManager.shared.end()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:417-427@7c200bf

```swift
    func discardPendingReply() {
        pendingReplyOrigin = nil
        voiceRecordingManagement.discardRecording()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:12-15@7c200bf

D2 applies to discard too.

**12.5 The safety net.** A walk screen dismissed without either path stops the engine and the voice in `deinit`:

```swift
    deinit {
        seekEngine?.stop()
        seekSound?.stop()
        honorEngine?.stop()
        wayVoicePlayer?.stop()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:260-265@7c200bf

**12.6 Walk start.** Order at Begin: status `.recording`, then the Honor marker event, then the engine and player bind, then the start bell, then the guide.

```swift
    func startRecording() {
        proximityService.resetSession()
        builder.setStatus(.recording)
        writeSeekMarkerEventIfNeeded()
        writeHonorMarkerEventIfNeeded()
        startHonorEngineIfNeeded()
        soundManagement.onWalkStart()
        startVoiceGuideIfEnabled()
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:380-387@7c200bf

There is no delay between the start bell and a first voice. A Way voice whose spot is within 42 m of the start can speak over the start bell. The bell is not a gate.

### 13. Haptics (pin 11)

**13.1 Which Honor events vibrate, and the patterns.** Four engine events. None is tied to a Way voice.

```swift
        case .momentReached(let moment):
            if !honorCards.contains(moment) { honorCards.append(moment) }
            reachedMomentIDs.insert(moment.id)
            fireHonorHaptic(.waypointDropped)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:177-180@7c200bf

```swift
        case .softTap(let meters):
            showSoftTapCaption(meters: meters)
            fireHonorHaptic(.honorOffWay)

        case .markAhead(let mark, let meters):
            showMarkCaption(mark: mark, meters: meters)
            fireHonorHaptic(.honorWaterAhead)

        case .arrived(let theirSeconds, let yourSeconds):
            recordHonorArrival(theirSeconds: theirSeconds, yourSeconds: yourSeconds)
            fireHonorHaptic(.honorArrival)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:200-210@7c200bf

`.momentReached` is emitted only for NON-voice moments (photo, rest, meditation, waypoint). A reached voice is queued and emits nothing (§1, `HonorMomentTracker.swift:68-73`). `.voiceStart` fires no haptic.

The patterns:

| Event | Pattern | Detail |
|---|---|---|
| Non-voice moment reached | `.waypointDropped` | `UIImpactFeedbackGenerator(style: .light)`, the manual waypoint haptic |
| Soft tap (off the Way) | `.honorOffWay` | `UIImpactFeedbackGenerator(style: .soft)` |
| Water ahead | `.honorWaterAhead` | one Core Haptics transient, intensity 0.4, sharpness 0.2; fallback `.soft` impact |
| Arrival | `.honorArrival` | Seek's arrival pattern: three transients at 0 / 0.16 / 0.34 s, intensities 0.4 / 0.55 / 0.7, sharpness 0.2; fallback notification `.success` |

```swift
        case .waypointDropped:
            let generator = UIImpactFeedbackGenerator(style: .light)
            generator.prepare()
            generator.impactOccurred()
```
> Pilgrim/Models/Haptics/HapticManager.swift:103-106@7c200bf

```swift
    private func fireHonor() {
        switch self {
        case .honorOffWay:
            let generator = UIImpactFeedbackGenerator(style: .soft)
            generator.prepare()
            generator.impactOccurred()

        case .honorArrival:
            // The bowl is the same bowl: arriving at the end of someone's
            // Way shares Seek's arrival pattern deliberately.
            if !Self.playSeekArrival() {
                let generator = UINotificationFeedbackGenerator()
                generator.prepare()
                generator.notificationOccurred(.success)
            }

        case .honorWaterAhead:
            // One tap at the whisper's intensity: a notice, not an alert.
            if !Self.playHonorWaterAhead() {
                let generator = UIImpactFeedbackGenerator(style: .soft)
                generator.prepare()
                generator.impactOccurred()
            }
```
> Pilgrim/Models/Haptics/HapticManager.swift:201-223@7c200bf

```swift
    private static func playHonorWaterAhead() -> Bool {
        let soft = CHHapticEventParameter(parameterID: .hapticIntensity, value: 0.4)
        let round = CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.2)
        return HapticEngineHost.shared.play(
            [CHHapticEvent(eventType: .hapticTransient, parameters: [soft, round], relativeTime: 0)])
    }
```
> Pilgrim/Models/Haptics/HapticManager.swift:243-248@7c200bf

```swift
    private static func playSeekArrival() -> Bool {
        // Three rising soft taps — warm arrival, distinct from
        // cairnProximity's two firm sharp ones.
        let round = CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.2)
        let steps: [(time: TimeInterval, intensity: Float)] = [(0, 0.4), (0.16, 0.55), (0.34, 0.7)]
```
> Pilgrim/Models/Haptics/HapticManager.swift:300-304@7c200bf

The "bowl" in the arrival comment is a metaphor. Honor arrival plays no sound: `recordHonorArrival` writes the event and waypoint and sets the card, with no audio call (`ActiveWalkViewModel+Honor.swift:248-260@7c200bf`).

Android already has the arrival taps: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/seek/SeekHaptics.kt:69,180-184@5ea4029b`.

**13.2 iOS's foreground rule, exactly.** Every Honor haptic is dropped unless `UIApplication.shared.applicationState == .active`. `.inactive` (Control Center pulled down, a banner, app switching) drops it too. There is no deferral and no replay.

```swift
    private func fireHonorHaptic(_ pattern: HapticPattern) {
        guard honorSenses.isAppActive() else { return }
        pattern.fire()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:460-463@7c200bf

Android deliberately differs (R6): it fires with the screen off.

**13.3 Ordering against persistence and playback.**
- Arrival: the event and the waypoint are written before the haptic ("The persistence commit happens before any ritual effect, as in Seek", `ActiveWalkViewModel+Honor.swift:247@7c200bf`).
- Moment reached: the card is queued and the moment recorded before the haptic.
- Playback: iOS has no Honor haptic that depends on playback, so "fire only after playback has actually started" has nothing to attach to at the pin. If Android adds a voice-start haptic, that is an addition beyond R6's screen-off divergence and must be recorded. Otherwise the plan's rule is vacuous for Honor. The one audio-adjacent ordering is soft tap and water ahead: each sets its caption, then vibrates.

### 14. Seek sounds: no Honor parts at the pin

`SeekSoundPlayer.swift@7c200bf` has no Honor code (`git grep -n -i honor 7c200bf -- Pilgrim/Models/Audio/` finds nothing). Its ping gate checks the whisper and the guide, but not a Way voice:

```swift
    private var canPingOverCurrentAudio: Bool {
        guard !isWhisperPlaying(), !isVoiceGuidePlaying() else { return false }
        switch coordinator.currentMode {
        case .recordingOnly, .recordAndPlay:
            return false
        case .idle, .playbackOnly:
            return true
        }
    }
```
> Pilgrim/Models/Audio/SeekSoundPlayer.swift:129-137@7c200bf

This is moot: a walk is Seek or Honor, never both (`if mode == .seek { bindSeekLifecycle() }`, `ActiveWalkViewModel.swift:238-240@7c200bf`). The only Honor/Seek audio-haptic link is the shared arrival pattern (§13.1). Android: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/seek/SeekSoundPlayer.kt` and the ping gate in `app/src/main/java/org/walktalkmeditate/pilgrim/di/SeekModule.kt` need no Honor change for parity.

### Resolutions for the plan

1. **Priority order and its mechanics.** Guide prompt > Way voice > whisper. It is enforced at each lower player's entry, not by a central arbiter (§2).
   - The Way voice checks the guide in `WayVoicePlayer.play` and parks one voice (newest wins). It waits on the guide's `playbackDidFinish`, which fires on every guide exit.
   - The whisper checks the guide and `isPlayingWayVoice` in `AudioPriorityQueue.playWhisper` and parks one URL (newest wins). It waits on the Way voice's falling edge and the guide's end signal.
   - The engine adds a second whisper guard: a queued voice does not start while a whisper is audible (the `externalAudio` gate, §2.5).
   - Higher players interrupt lower ones on start. A prompt pauses the voice, stops the whisper, and drops the parked whisper. A voice stops the audible whisper and keeps the parked one.
   - On "prompt ended", the Way voice is released before the whisper (§2.3).
   - The plan's "a Way voice never starts while the prompt gate, the recording gate, or a whisper is active" holds for engine-driven voices, except the parked-voice leak (D1). Walker taps (card, chip, scrub, reply) bypass the recording and pause gates, and cut an audible whisper (§4.4).
2. **A prompt that starts mid-voice.** The voice is paused synchronously, before the prompt's player exists (no overlap on iOS), and is marked `pausedByGuide`. It hands its pre-duck level to the guide, which ducks to the same `voiceGuideDuckLevel` and owns the duck. The guide restores the walker's level on exit. Then the Way voice's `resume()` re-takes the duck and plays from the stopped position (§3.1-3.3). The restore and re-duck are one main-queue hop apart with 0.5 s ramps, so the plan's "keeps the soundscape ducked across the hand-over" is the right Android target. A walker pause at any point wins: the guide never resumes it (§3.5). A voice that was already paused is left paused and only hands over the duck.
3. **Whisper queueing.** Every in-walk whisper play (autoplay, pin tap, placement confirmation, Seek reveal) goes through the one-slot queue. The placement-sheet preview does not (§5.1). The rules are in §5.2. A parked whisper has NO expiry and NO distance check: it plays when the voice run or prompt ends, wherever the walker is (§5.3). It is dropped only by a prompt starting or a newer whisper. It is not dropped by walk end, so it plays after Finish (D2). For U18: "UI-started whispers wait" means park-in-one-slot-and-play-later, not drop. The plan's AE11 clause "given a guide prompt playing, a whisper in range waits too" matches iOS. So does U18's "a whisper pending when a prompt starts is dropped".
4. **The soundscape duck for a Way voice.** Level: the absolute `voiceGuideDuckLevel` preference, default 0.15, set only from Voice Guide settings ("Soundscape during guide"). Ramp: 0.5 s (`setVolume(_, animated: true)`). Applied in `start()`, never while parked. Held through every pause. Restored with a 0.5 s ramp on every exit, including skip, drop, reply, Finish, discard, and failure (§6.1-6.2). It never stacks with the whisper duck (§6.3) and has exactly one owner alongside the guide (§6.4). Two plan conflicts:
   - U18's test "the soundscape turned on mid-voice … starts ducked" is not iOS. iOS starts it at full soundscape volume and restores to a stale level after the voice (§6.5, D4). Per the upstream-first rule, match iOS and file D4, or record a divergence.
   - Android's current duck is 0.3 × user volume with no ramp (§6.7). iOS parity for the Way voice is 0.15 absolute with a 0.5 s ramp. Android has no duck-level preference, so the constant default is 0.15 until the preference issue lands.
5. **Playback rates.** `[1, 1.25, 1.5, 2]`, cycled by one button on the voice card, labelled "1x", "1.25x", "1.5x", "2x" (§7.1-7.2). The rate applies to the current voice at once and to every later voice, tapped replay, and reply. The view model's rate is per walk (reset to 1 at teardown). The player singleton's rate is per process and is never reset, and nothing is persisted (§7.4). The cross-walk carry-over is a defect, not behavior to match (D5). Android: per walk, starting at 1x, carried in the session state so a `:tracker` revival keeps it within the walk.
6. **`playReply`.** Confirmed. It stops the active voice (no `onFinished`, so the engine is not told), clears `activeVoice` (no chip), and plays the reply through the same player at full `voiceGuideVolume` (§8.1).
   - Not the active voice for the UI: it cannot be paused or skipped.
   - Yes for whispers (they park) and for the guide (it pauses the reply).
   - Gates reach it only while the engine still has a displaced voice.
   - Not "heard".
   - When it ends, `voiceDidFinish` gives the engine its turn. The displaced voice is abandoned, not resumed.
   - If the engine had no current voice, a newly reached voice can replace the reply mid-play (§8.2-8.4).
7. **"Heard".** Marked when the view model hands a voice with a locally resolvable file to the player, before any sound (§9.1). It includes a voice parked behind a prompt and a voice that then fails to decode or start. It excludes a missing file. It is recorded only in the in-memory `heardVoiceIDs`, which drives the pins and the arrival card's "one voice heard" / "N voices heard". It is not in the checkpoint, the link, or the summary (§9.2). Android matching iOS: mark on hand-off. D7 is filed for the failure case.
8. **A place card whose voice fails.**
   - Missing file: the engine skips it silently. No card, no chip, not heard. From a pin tap the card opens but play is a no-op, with a placeholder waveform (§10.1).
   - Decode or start failure: the voice counts as heard, no chip is drawn for it (`activeVoice` is set and cleared in one synchronous stack), the engine moves on, and the card stays with a play icon and "0:00 / m:ss", retiring after 20 s unless touched. It can land on top of the next voice's card (§10.2, D9).
   - A mid-voice decode error ends like a natural finish (§10.3).
   - Audio-session errors are swallowed; only `play()` returning false surfaces, as above (§10.4).
   - There is no error text anywhere.
9. **Audio session.**
   - Category and activation: `.playback`, mode `.default`, `[.mixWithOthers]`, consumer `"honor-voice"`. Activated in `start()`, released in `finish` on every exit except a replacing start, kept through all pauses (§11.1-11.2).
   - Interruptions: none observed by the Way voice. After a call the voice is stuck until the walker acts (D8).
   - Route changes: none observed anywhere. A voice continues on the speaker (§11.4).
   - The plan's handlers (transient loss → pause then resume on GAIN; permanent loss → stop; BECOMING_NOISY → pause) are Android platform equivalents with no iOS counterpart. Record them at the gate (R6), and file D8 upstream so iOS gains the same resume.
10. **Pause, meditation, finish, discard.**
    - Pause and meditation close the engine gate. A playing voice pauses in place (duck and session kept), queued voices wait, and the voice resumes where it stopped when the gate opens (§12.1-12.2).
    - Exceptions: a parked voice escapes the gate (D1), and a walker-paused voice is resumed by the gate reopening (D6).
    - Finish and discard stop the voice at once, with no fade: the parked voice is dropped, the duck is restored, the session is released, and `onFinished` is not called. They do this before the soundscape fade and the guide stop.
    - Whispers are not stopped, and a parked one plays after the walk (D2) (§12.3-12.4).
11. **Haptics.**
    - Four Honor events vibrate: a non-voice moment reached (`.light` impact), soft tap (`.soft` impact), water ahead (one transient at 0.4 / 0.2), and arrival (Seek's three rising taps at 0 / 0.16 / 0.34 s, 0.4 / 0.55 / 0.7) (§13.1).
    - None is tied to a voice or to playback. A voice's start vibrates nothing, so the plan's "only after playback has actually started" has no iOS event to attach to (§13.3).
    - iOS rule: every Honor haptic is dropped unless `applicationState == .active` (`.inactive` drops it too), with no deferral (§13.2). Android fires screen-off (R6).
    - Persistence precedes the haptic for arrival.

Placement-table check for this cluster (plan High-Level Technical Design): "Way voice, whisper autoplay hold, soundscape duck, arbiter, haptics — `:tracker`" is consistent with iOS's split. On iOS all three players and the duck live in one process, with only the guide's `pauseForGuide()` call crossing into the Way voice player. "UI-started whispers (tap, preview, Seek reveal) — reads the persisted Way-voice state" needs one correction: iOS does not queue the preview (§5.1), and it does queue the placement-confirmation play, which the table does not list.

### iOS defects found

Candidate upstream issues for `pilgrim-ios`. Not filed.

- **D1. A Way voice parked behind a guide prompt starts inside a closed gate.** `pause()` does not touch `pending`, and `guideDidFinish()` starts `pending` without checking the engine's gates.

  ```swift
      func pause() {
          // Any pause — the walker's own or a guide-induced one already in
          // effect — is the walker's word from here on: a guide finishing
          // afterward must not resume what this call paused.
          pausedByGuide = false
          player?.pause()
          elapsedTimer?.invalidate()
      }
  ```
  > Pilgrim/Models/Honor/WayVoicePlayer.swift:66-73@7c200bf

  Three paths:
  - The walker starts recording (for example "reply here" on the card that just rose) while a prompt speaks. The recording stops the prompt (`VoiceGuideManagement.swift:110-116`), so the parked voice starts over the recording.
  - The walker pauses the walk during a prompt; the voice starts when the prompt ends.
  - The walker sits with the meditation guide on. `MeditationGuideManagement.startGuiding` stops the guide player, so the voice starts inside the sitting.

  Impact: a stranger's voice speaks into the walker's reply recording, or during a sitting or a paused walk, while the chip says "paused".

  *Correction from filing ([pilgrim-ios #101](https://github.com/walktalkmeditate/pilgrim-ios/issues/101)):* the recording path does not hold. A recording stops the prompt before the recording gate closes, so the gate pauses the voice a moment after it starts. The sitting path holds. The paused-walk path is latent, since nothing at the pin can pause a walk.

- **D2. A parked whisper plays after the walk ends.** Nothing clears `pendingWhisperURL` at Finish or discard. `stopWhisper()` has no caller outside the queue. `teardownHonor()`'s `wayVoicePlayer.stop()` drops `isPlayingWayVoice`, and `stopGuiding()` emits `playbackDidFinish`; either one releases the parked whisper (§12.3). An audible whisper is not stopped either. Impact: a whisper starts over the walk-end bell and summary, and its duck call likely disturbs the soundscape's 2 s fade-out (the same `setVolume` override as D12).

- **D3. `resume()` during a prompt speaks over the prompt and leaves the soundscape unducked.** Path: a voice is guide-held, the walker pauses and resumes the walk within the prompt. `pause()` clears `pausedByGuide`, and the engine's `.voiceResume` calls `resume()`, which skips the duck because the guide is speaking (§3.3 quote) but still plays. When the prompt ends, the guide restores the walker's level and `guideDidFinish` has nothing to resume, so nothing re-ducks. Impact: a Way voice and a prompt overlap, then the voice finishes over a full-level soundscape.

- **D4. A soundscape started mid-voice is not ducked, and is restored to a stale level.** `SoundscapePlayer.play` overwrites `targetVolume` and fades up (`SoundscapePlayer.swift:68-81`). `finish` later restores the level captured at the voice's start (§6.5). Impact: the voice competes with a full-level bed. At the voice's end the bed can jump to an old level. The plan's U18 test expects the opposite behavior.

- **D5. The playback rate carries into the next walk while the card shows 1x.** `WayVoicePlayer.shared.playbackRate` is never reset. `teardownHonor()` resets only the view model's `voiceRate = 1` (§7.4). Impact: after a 2x walk, the next walk's voices play at 2x under a "1x" label, and the first tap goes to 1.25x.

- **D6. An engine gate reopening resumes a voice the walker paused.** The moment tracker's `isVoicePaused` knows only gate pauses. A walker-paused voice, then a gate close and open (pause and resume the walk, sit and stand, record and stop), gets `.voiceResume` → `resume()` (§4.2). Impact: a voice the walker silenced comes back by itself. A related consequence: a walker-paused voice is never dropped (the tracker thinks it is playing) and holds every later voice until the walker resumes or skips it (§4.3).

- **D7. "Heard" counts voices that never played.** `heardVoiceIDs.insert` runs before `play` (§9.1), so a decode or start failure, or a voice parked and then superseded, is counted. This contradicts the code's own comment "A heard voice must mean a played voice" (`ActiveWalkViewModel+Honor.swift:385-386`). Impact: the arrival card over-counts "voices heard", and pins show heard for silent voices. Low.

- **D8. No interruption handling for the Way voice (and the guide and whisper players).** None registers with `addInterruptionObserver` (§11.3). A call pauses the voice's `AVAudioPlayer`, and nothing resumes it. `isPlayingWayVoice` stays true, `onFinished` never comes, the engine cannot start later voices, and whispers stay parked. Impact: after a phone call on an honor walk, the Way goes silent until the walker happens to tap the chip twice or skip. The chip claims "listening". The soundscape already solved this (`SoundscapePlayer.swift:34-55`).

- **D9. A failed voice's card can sit on top of the voice that replaced it.** `startVoice` calls `showCard` after `play`. A synchronous start failure runs `onFinished`, which can start the next voice and show its card first; the failed card is then inserted above it (§10.2). Impact: the walker sees a dead card over the one that is speaking. Low.

- **D10. The one duck-and-volume control is hidden and mislabelled for Honor.** The Way voice's volume and duck come from the guide's "Guide Volume" and "Soundscape during guide" sliders. Both are shown only when the voice guide is enabled and has packs (`VoiceGuideSettingsView.swift:17-26`). Impact: a walker who does not use the guide cannot change how loud Way voices are, or how far the soundscape dips under them. Low (UX).

- **D11. Code comments that disagree with the code.** `start()`'s comment says the session stays active and the soundscape stays ducked "between two voices in one run" (`WayVoicePlayer.swift:146-149`). The common run, a natural end chaining to the next voice, releases and re-activates both (§6.2), which is session churn when nothing else holds the session. `cycleVoiceRate` claims "the same ladder as the post-walk player", but the post-walk ladder is `[1.0, 1.5, 2.0]` (§7.2). Impact: none audible; misleading for maintainers. Lowest.

- **D12. The walk-end guide restore likely swells a fading soundscape. Needs a device check.** `stop()` runs `soundManagement.onWalkEnd()` (a 2.0 s fade to 0 on the still-set `activePlayer`) BEFORE `voiceGuideManagement.stopGuiding()`. The guide's `restoreAndDeactivate` then calls `setVolume(walkerLevel, animated: true)` on that same player, likely replacing the fade-out with a 0.5 s fade-up until the 2 s cut. This is pre-Honor guide behavior. The Way voice's own restore runs before the fade and is not affected. Impact: an audible swell-then-cut at Finish when a prompt was speaking.

### Open questions

- **Route loss.** iOS has no route-change handling. Whether a Way voice continuing on the loudspeaker after an unplug is intended is not stated anywhere at the pin. The Android pause-on-BECOMING_NOISY needs an owner call: record it as a platform equivalent (R6) or ask iOS to adopt it. After a noisy pause, iOS offers no rule for when the voice resumes. It stays paused until the walker or a gate edge resumes it, and gate edges resume it (§4.2).
- **Resume after an interruption.** Because iOS never resumes (D8), there is no iOS rule for whether a voice should resume after a call, or restart, or be dropped if the walker has since walked past 300 m. Android's GAIN-resume needs the owner's call until iOS fixes D8.
- **Platform facts the code relies on but does not state.** That `AVAudioPlayer` is paused by the system on a call even under `.mixWithOthers` (asserted by the soundscape's comment, §11.3), and that it does not pause on route loss (§11.4). Both are worth one device check on iOS before the gate's audio rows are written.



## D. The walk with Honor: events, cards, replies, session, and the glance

Reader D, plan unit U11, feeding U17 (the `:tracker` session) and U22's logic. iOS read only at the pin `7c200bf` (`git show 7c200bf:<path>`); line numbers match the pin. Android citations are at HEAD `5ea4029b`, with `P/` = `app/src/main/java/org/walktalkmeditate/pilgrim/`.

Scope is the own-walk slice. Where logic branches on a shared Way (`.share` source), a pilgrimage stage (`way.stage`, `way.isPilgrimageStage`, `way.marks`), or offline maps, the branch point is named in one line and not followed.

Lenses applied: behavior (state machine, transitions, async hops, every `asyncAfter` constant, restoration), UI/visual (only the glance is drawn in this cluster), data (events, waypoints, files, preference keys, checkpoint fields), edge cases (thresholds, generation guards, re-entrancy, idiom traps). Drift archetypes A (timing constants), C (Swift idioms), E (locale), F (first-emission), and I (swallowed errors: iOS `try?`) are called out where they bite.

One framing fact first. iOS has no process split and no live restore: the whole Honor session lives in `ActiveWalkViewModel` in one process, and a killed walk is never resumed — it is saved as finished at the next launch (§9.2). Everything below that Android must survive in `:tracker` has no iOS counterpart beyond what is quoted.

### 1. The Honor state on the view model

The view model holds the mode and the Way as constants for the life of the walk. It is built once per walk.

```swift
class ActiveWalkViewModel: ObservableObject, Identifiable {

    let id = UUID()
    let mode: WalkMode
    /// The Way an honor walk follows; nil for every other mode.
    let way: Way?
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:7-12@7c200bf

Every piece of live Honor state, with its comment, as declared:

```swift
    // Honor lifecycle lives in ActiveWalkViewModel+Honor.swift.
    @Published var honorEngine: HonorEngine?
    @Published var honorCards: [WayMoment] = []
    @Published var activeVoice: WayMoment?
    @Published var isVoicePaused = false
    /// The walker's chosen voice speed for this walk; 1× at the start of every walk.
    @Published var voiceRate: Float = 1
    /// A moment the walker asked the map to show; nil means the map follows them.
    @Published var honorFocus: CLLocationCoordinate2D?
    /// True heading from the compass while a Way is honored; nil until it settles.
    @Published var headingDegrees: Double?
    var honorHeading: HeadingProviding?
    @Published var honorArrival: HonorArrivalCard?
    /// The engine's last word on this stage, captured in `teardownHonor()`
    /// and deliberately surviving it — the engine is gone by the time the
    /// snapshot reaches `onWalkCompleted`, and the ledger is written there.
    @Published var honorStageOutcome: HonorStageOutcome?
    /// The arrival card is retired by its own flag, never by clearing
    /// `honorArrival` — `MainCoordinatorView` reads the companion delta off
    /// that card when the walk is saved, which can be long after the walker
    /// tapped "continue".
    @Published var honorArrivalCardDismissed = false
    /// The soft tap's only mark on screen, in place of the minimized bar's
    /// third stat. Nil whenever the walker is on the Way — or always, when
    /// the soft-tap preference is off.
    @Published var softTapCaption: String?
    @Published var heardVoiceIDs: Set<String> = []
    /// Non-voice moments actually reached (`.momentReached`), not every
    /// non-voice moment on the Way — the arrival card's `placesPassed`
    /// must count what the walker passed, not what the Way carries.
    @Published var reachedMomentIDs: Set<String> = []
    /// "they sat here for 12 minutes. Sit?": a static caption for
    /// MeditationView, never a countdown.
    @Published var suggestedMeditationMinutes: Int?
    var pendingReplyOrigin: WayMoment?
    /// Cards the walker has touched; an untouched voice card retires itself
    /// after its voice ends, a touched one waits to be dismissed.
    var touchedCardIDs: Set<String> = []
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:92-129@7c200bf

```swift
    let honorSenses: HonorSenses
    var wayVoicePlayer: WayVoicePlaying?
    /// Invalidates a finished-voice callback that lands after teardown.
    var honorGeneration = 0
    var honorCancellables: [AnyCancellable] = []
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:148-152@7c200bf

The injectable side effects. The voice player is a process-wide singleton in production; haptics fire only in the foreground.

```swift
struct HonorSenses {
    var makeVoicePlayer: () -> WayVoicePlaying = { WayVoicePlayer.shared }
    /// Haptics only render in the foreground; the gate lives here so event
    /// routing can stay in the view model.
    var isAppActive: () -> Bool = { UIApplication.shared.applicationState == .active }
    var store: () -> WayStore = { WayStore.shared }
    var makeHeadingProvider: () -> HeadingProviding = { HeadingProvider() }
}
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:7-14@7c200bf

```swift
    private func fireHonorHaptic(_ pattern: HapticPattern) {
        guard honorSenses.isAppActive() else { return }
        pattern.fire()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:460-463@7c200bf

The arrival card's value type. `theirSeconds` and `yourSeconds` are the numbers the finish path persists (§8.3).

```swift
struct HonorArrivalCard: Equatable {
    let wayTitle: String
    let voicesHeard: Int
    let placesPassed: Int
    /// The engine's numbers on the companion's timeline; persisted into the
    /// index link at save time so the summary can read them back. A stage
    /// carries the package's own active seconds as `theirSeconds` too, but
    /// the summary ignores them for a stage — there is no companion to
    /// compare arrival against.
    let theirSeconds: Double
    let yourSeconds: Double
    /// Set only for a pilgrimage stage; the card then speaks of the stage
    /// rather than of another walker.
    let stageName: String?
    let distanceWalkedMeters: Double
    /// The stage's closing line, present only once arrival fired on a stage.
    /// Optional and last, like `WayMoment.place` and `.transcript`.
    var closing: String?

    var isStage: Bool { stageName != nil }
}
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:16-36@7c200bf

Branch point: `stageName` and `closing` are set only when `way.stage != nil` (stage spec).

Android counterpart: none yet. U17 splits this state: engine-owned facts (moment reached, voice start/end, heard, arrival numbers, anchor) persist from `:tracker`; card order, dismissals, touches, and focus are UI state (the plan's UI-owned card-state table).

### 2. Honor walk events: names, payloads, timing, ordering

iOS writes exactly two Honor walk events, both through `builder.addWorkoutEvent`, which appends to one in-memory array that the snapshot later saves.

```swift
    public func addWorkoutEvent(_ event: TempWalkEvent) {
        workoutEventsRelay.accept(workoutEventsRelay.value + [event])
    }
```
> Pilgrim/Models/Walk/WalkBuilder/WalkBuilder.swift:113-115@7c200bf

Their stored raw values, debug names, and display names:

```swift
        case lap, marker, segment, seekMode, seekArrival, honorMode, honorArrival, unknown
...
            case 5:
                self = .honorMode
            case 6:
                self = .honorArrival
...
            case .honorMode:
                return HonorPersistence.honorModeEventName
            case .honorArrival:
                return HonorPersistence.honorArrivalEventName
...
            case .honorMode:
                return "HonorMode"
            case .honorArrival:
                return "HonorArrival"
```
> Pilgrim/Models/Data/DataModels/WalkEvent.swift:30,44-47,86-89,107-110@7c200bf

The `.pilgrim` wire names are `"honorMode"` and `"honorArrival"`:

```swift
        case .honorMode: return "honorMode"
        case .honorArrival: return "honorArrival"
...
        case "honorMode": return .honorMode
        case "honorArrival": return .honorArrival
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:500-501,513-514@7c200bf

The persistence vocabulary, with localization keys and English values:

```swift
enum HonorPersistence {

    /// Must never collide with WaypointMarkingSheet's presets, "mappin", or
    /// SeekPersistence.arrivalWaypointIcon.
    static let arrivalWaypointIcon = "signpost.right.fill"
...
    static func isArrivalWaypoint(_ waypoint: WaypointInterface) -> Bool {
        waypoint.icon == arrivalWaypointIcon
    }

    static func arrivalWaypointLabel(wayTitle: String) -> String {
        String(format: arrivalLabelFormat, wayTitle)
    }

    static let honorModeEventName = NSLocalizedString(
        "honor.event.honor_mode", value: "Honor",
        comment: "Name of the walk event marking a walk as an honor walk.")

    static let honorArrivalEventName = NSLocalizedString(
        "honor.event.arrival", value: "Way walked",
        comment: "Name of the walk event written when the end of a Way is reached.")

    private static let arrivalLabelFormat = NSLocalizedString(
        "honor.arrival.label", value: "Walked their way: %@",
        comment: "Waypoint label at the end of an honored Way; %@ is the Way's title.")
}
```
> Pilgrim/Models/Honor/HonorPersistence.swift:6-10,26-45@7c200bf

Android already has the icon (`P/domain/honor/HonorPersistence.kt:22@5ea4029b`) and the two event types (`P/domain/WalkEventType.kt:39-46@5ea4029b`). The label format "Walked their way: %@" and the two display names are not yet on Android.

#### 2.1 `HONOR_MODE` — once, at recording start

Payload: event type and `Date()` only (no uuid, no Way id). Guarded on mode and on a Way being present.

```swift
    func writeHonorMarkerEventIfNeeded() {
        guard mode == .honor, way != nil else { return }
        builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .honorMode, timestamp: Date()))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:42-45@7c200bf

It is written inside `startRecording()`, after the status flips to `.recording` (which stamps the walk's start date synchronously in the builder's status sink) and after the Seek marker (the two modes are exclusive, so at most one of them writes):

```swift
    func startRecording() {
        proximityService.resetSession()
        builder.setStatus(.recording)
        writeSeekMarkerEventIfNeeded()
        writeHonorMarkerEventIfNeeded()
        startHonorEngineIfNeeded()
        soundManagement.onWalkStart()
        startVoiceGuideIfEnabled()
        WalkActivityManager.shared.start(walkStartDate: Date(), intention: intention)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:380-389@7c200bf

```swift
            case .recording: // starting / resuming walk
                if self.startDateRelay.value == nil {
                    self.startDateRelay.accept(timestamp)
```
> Pilgrim/Models/Walk/WalkBuilder/WalkBuilder.swift:142-144@7c200bf

So the marker's timestamp is the start instant plus microseconds. It is never written again: restore does not exist on iOS, and `continueWalk(from:)` copies the events it is handed.

```swift
        workoutEventsRelay.accept(snapshot.workoutEvents.map { TempWalkEvent(uuid: $0.uuid, eventType: $0.eventType, timestamp: $0.timestamp) })
```
> Pilgrim/Models/Walk/WalkBuilder/WalkBuilder.swift:482@7c200bf

Ordering against Android's reducer events. The iOS builder writes no pause, resume, or meditation events at all — pauses are `TempWalkPause` rows and sittings are activity intervals (the §2 events above are the only events an honor walk writes). On Android the reducer writes `PAUSED`, `RESUMED`, `MEDITATION_START`, `MEDITATION_END`, and the controller writes waypoints; none of those can precede `HONOR_MODE`, because iOS writes the marker before the walk can be paused or meditate. Android's precedent is the Seek start effect, applied inside `startWalk`'s mutex before the state commit (`P/domain/WalkReducer.kt:61-70@5ea4029b`, `P/walk/WalkControllerImpl.kt:98-122@5ea4029b`). `HONOR_MODE` belongs in the same place with `timestamp = startedAt`.

#### 2.2 `HONOR_ARRIVAL` plus the reserved waypoint — once, on arrival

The engine emits `.arrived` once (it flips `phase` to `.arrived` first, and every later evaluation is guarded on `.walking`):

```swift
    private func evaluateArrival(_ location: CLLocation) {
        guard phase == .walking, let last = geometry.points.last, let start = startFrac else { return }
...
        if arrival.register(distance: distance, radius: HonorTuning.arrivalRadiusMeters,
                            accuracy: location.horizontalAccuracy) {
            phase = .arrived
            subject.send(.arrived(theirSeconds: geometry.totalSeconds - companionT0, yourSeconds: sinceAnchorSeconds))
        }
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:290-291,305-309@7c200bf

The view model persists first, then builds the card, then fires the haptic. Order: event, waypoint, card, haptic.

```swift
        case .arrived(let theirSeconds, let yourSeconds):
            recordHonorArrival(theirSeconds: theirSeconds, yourSeconds: yourSeconds)
            fireHonorHaptic(.honorArrival)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:208-210@7c200bf

```swift
    /// The persistence commit happens before any ritual effect, as in Seek.
    private func recordHonorArrival(theirSeconds: Double, yourSeconds: Double) {
        guard let way else { return }
        builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .honorArrival, timestamp: Date()))
        addWaypoint(label: HonorPersistence.arrivalWaypointLabel(wayTitle: way.title),
                    icon: HonorPersistence.arrivalWaypointIcon)
        honorArrival = HonorArrivalCard(
            wayTitle: way.title, voicesHeard: heardVoiceIDs.count,
            placesPassed: reachedMomentIDs.count,
            theirSeconds: theirSeconds, yourSeconds: yourSeconds,
            stageName: way.stage?.name,
            distanceWalkedMeters: honorEngine?.distanceWalkedMeters ?? 0,
            closing: way.stage?.closing)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:247-260@7c200bf

The waypoint takes the walker's current fix, else the last route coordinate, else it is silently skipped (the event still lands). Its timestamp is `Date()`, separate from the event's.

```swift
    @discardableResult
    func addWaypoint(label: String, icon: String) -> Bool {
        let lat: Double
        let lon: Double
        if let location = currentLocation {
            lat = location.latitude
            lon = location.longitude
        } else if let last = routeCoordinates.last {
            lat = last.latitude
            lon = last.longitude
        } else {
            return false
        }
        let waypoint = TempWaypoint(
            uuid: nil,
            latitude: lat,
            longitude: lon,
            label: label,
            icon: icon,
            timestamp: Date()
        )
        builder.addWaypoint(waypoint)
        waypoints.append(waypoint)
        return true
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:440-464@7c200bf

Payload summary: the event carries only its type and time; the waypoint carries the label `"Walked their way: " + way.title`, the icon `"signpost.right.fill"`, the coordinate, and its own time. `theirSeconds` and `yourSeconds` are held in memory on `honorArrival` and reach disk only at the finish save (§8.3) — never in the event, the waypoint, or the checkpoint.

Arrival can land in any walk state the fix stream reaches, including during meditation (the engine processes every fix; §3.3). iOS never re-checks that the walk is unfinished — it cannot need to, since teardown cancels the engine's subscriptions before the builder snapshots (§8.3). Android's controller mutex (`recordWaypoint` at `P/walk/WalkControllerImpl.kt:240-266@5ea4029b`) is where the plan's compare-and-set lands.

No other Honor event exists. Moments reached, voices started, heard voices, soft taps, and drops are never persisted on iOS: they live in the view model and die with it.

### 3. Begin: what is set up, in what order, and what feeds the engine

#### 3.1 Two steps on iOS: the overview's Begin, then the walk screen's Start

The overview's Begin only parks the Way and closes the sheet; the walk screen is created when the overview has finished closing (AF60: never two sheets at once).

```swift
    func startHonor(way: Way) {
        pendingStartWay = way
        honorOverviewWay = nil
    }

    func handleOverviewDismiss() {
...
        if let way = pendingStartWay {
            pendingStartWay = nil
            startWalk(mode: .honor, way: way)
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:320-338@7c200bf

`startWalk` refuses a second walk and a denied location permission, then builds a fresh view model per walk:

```swift
    func startWalk(mode: WalkMode = .wander, way: Way? = nil) {
        guard activeWalkViewModel == nil else { return }
...
        let locationStatus = CLLocationManager().authorizationStatus
        if locationStatus == .denied || locationStatus == .restricted {
            showLocationDenied = true
            return
        }
        Task { @MainActor in TranscriptionService.shared.autoTranscriptionSkippedReason = nil }
        let vm = ActiveWalkViewModel(mode: mode, way: way)
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:79-94@7c200bf

The view model's init draws the Way at once (before any recording), then starts the session guard (checkpoints, §9) and a weather fetch 2 s later:

```swift
        // The Way is drawn from the moment the walk screen appears, not from
        // the moment the engine starts at Begin.
        if mode == .honor { refreshHonorPins() }

        let guard_ = WalkSessionGuard()
...
        guard_.start()
        self.sessionGuard = guard_

        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
            self?.fetchWeather(retryOnFailure: true)
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:241-254@7c200bf

The walk screen opens in its pre-walk state (`.waiting`, then `.ready` when the components report an accurate fix) and the walker taps "Start":

```swift
            case .ready:
                actionButton("Start", systemImage: "play.fill", color: .moss, isFilled: true) {
                    viewModel.startRecording()
                }
```
> Pilgrim/Scenes/ActiveWalk/WalkStatsSheet.swift:522-525@7c200bf

So iOS's "Begin" that mints Honor state is `startRecording()` (quoted in §2.1). In order: status `.recording` (start date), Seek marker (no-op here), `HONOR_MODE`, engine start (below), soundscape `onWalkStart`, voice guide start, Live Activity start. Android has one step (Start is the walk start: `P/domain/WalkReducer.kt:51-60@5ea4029b` says so for Seek); the plan's Begin use case covers both iOS steps.

iOS mints no walk uuid at Begin. The saved walk's uuid comes back from the save, and the finish path copies it onto the snapshot (§8.3). The plan's Begin-minted uuid is an Android addition with no iOS analogue.

#### 3.2 `startHonorEngineIfNeeded()` — the engine and everything that lives exactly as long as it

```swift
    func startHonorEngineIfNeeded() {
        guard mode == .honor, let way, honorEngine == nil else { return }
        // Nothing queued before Begin belongs to this walk.
        honorCards.removeAll()
        honorGeneration += 1
        let generation = honorGeneration
        let engine = HonorEngine(
            way: way,
            // A stage has no other walker to be off the way *from*; the soft
            // tap and the companion dot are both about someone else.
            softTapEnabled: UserPreferences.honorSoftTapEnabled.value && !way.isPilgrimageStage,
            voicesEnabled: UserPreferences.honorVoicesEnabled.value && UserPreferences.soundsEnabled.value
        )
        let player = honorSenses.makeVoicePlayer()
        // A voice that ends on its own leaves nothing playing, so the chip
        // must clear before the engine is asked for the next one — it may
        // answer immediately with another `.voiceStart`.
        player.onFinished = { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            let finished = self.activeVoice
            self.activeVoice = nil
            self.isVoicePaused = false
            self.honorEngine?.voiceDidFinish()
            if let finished { self.retireCardLater(finished) }
        }
        wayVoicePlayer = player

        engine.bind(
            locations: honorLocationFixes,
            activeDuration: $activeDurationSeconds.eraseToAnyPublisher(),
            isPaused: $status.map { $0 != .recording }.eraseToAnyPublisher(),
            isMeditating: $isMeditating.eraseToAnyPublisher(),
            isRecordingVoice: $isRecordingVoice.eraseToAnyPublisher(),
            externalAudio: AudioPriorityQueue.shared.$isPlayingWhisper.eraseToAnyPublisher()
        )
        // The engine's own streams already hop to main, so its events reach
        // this sink on main without another hop.
        engine.events
            .sink { [weak self] event in self?.handleHonorEvent(event) }
            .store(in: &honorCancellables)
        honorEngine = engine
        // The compass lives exactly as long as the engine: started here,
        // stopped in teardown with the rest of the honor state.
        let heading = honorSenses.makeHeadingProvider()
        heading.headingPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.headingDegrees = $0 }
            .store(in: &honorCancellables)
        heading.start()
        honorHeading = heading
        bindMarkPins()
        refreshHonorPins()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:47-99@7c200bf

Order at engine start, which U17's session start should keep:
1. Clear any card queued before Begin; bump `honorGeneration` (invalidates every earlier `asyncAfter` and `onFinished`).
2. Read the three preferences once (`honorSoftTapEnabled`, `honorVoicesEnabled`, `soundsEnabled`; §11). They are frozen into the engine for the whole walk.
3. Take the voice player, install `onFinished` (generation-guarded, order: clear chip state, then `voiceDidFinish()`, then schedule the card's retire).
4. Bind the engine's six inputs.
5. Subscribe to engine events with no extra hop, so event handling is synchronous with the engine (re-entrancy matters; §4.1).
6. Start the compass. Branch point: `bindMarkPins()` only draws anything when `way.marks` is non-empty, which only stages carry (§4.3).
7. Rebuild the pins.

Note the guard `honorEngine == nil`: a second call is a no-op, and a restarted engine on the same view model is possible only after `teardownHonor()` (the test `testNaturalFinishClearsTheVoiceAndAdvancesTheQueue` does it; production never does).

#### 3.3 The location feed

The engine reads the view model's `$currentLocation`, converted to `CLLocation` with accuracy, speed, course, and the fix's own timestamp. The mark pins read the same stream.

```swift
    /// The walker's fixes as `CLLocation`s. Not private: the mark pins in
    /// `ActiveWalkViewModel+MarkPins.swift` follow the same stream rather
    /// than re-deriving coordinates from the raw sample.
    var honorLocationFixes: AnyPublisher<CLLocation, Never> {
        $currentLocation
            .compactMap { sample -> CLLocation? in
                guard let sample else { return nil }
                return CLLocation(
                    coordinate: CLLocationCoordinate2D(latitude: sample.latitude, longitude: sample.longitude),
                    altitude: sample.altitude, horizontalAccuracy: sample.horizontalAccuracy,
                    verticalAccuracy: sample.verticalAccuracy, course: sample.direction,
                    speed: sample.speed, timestamp: sample.timestamp)
            }
            .eraseToAnyPublisher()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:439-453@7c200bf

`$currentLocation` is fed from the builder's current-location relay. While the walk is active (recording or paused), only fixes that pass the adaptive accuracy check reach it — except the walk's very first recorded sample, which passes regardless:

```swift
        guard status.isActiveStatus else {
            if let lastLocation = locations.last {
                currentLocationRelay.accept(lastLocation.asTemp)
            }
...
        for location in locations {
            let isFirst = recordedSamples.isEmpty
            guard isFirst || checkForAppropriateAccuracy(location) else { continue }

            let location = refineLocation(location)
            let sample = location.asTemp
            let previousSample = recordedSamples.last
            appendRouteSample(sample)
            currentLocationRelay.accept(sample)
```
> Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:266-269,280-288@7c200bf

Two consequences for the Android tap:
- Before Start the relay carries raw, unfiltered fixes. `$currentLocation` is a `@Published`, which replays its current value on subscribe, so the engine's first input at Begin is the last pre-Begin fix (unfiltered), not the next one. The engine's own gate still applies to it (next quote). An Android tap before the reducer should hand the engine the latest known fix at session start, or accept a one-fix delay as a recorded difference.
- The engine is not the accuracy gate's only guard: it drops anything worse than 50 m or with negative accuracy.

```swift
    func processLocation(_ location: CLLocation) {
        let accuracy = location.horizontalAccuracy
        guard accuracy >= 0, accuracy <= HonorTuning.fixAccuracyMeters else { return }
```
> Pilgrim/Models/Honor/HonorEngine.swift:148-150@7c200bf

```swift
    static let fixAccuracyMeters = 50.0
```
> Pilgrim/Models/Honor/HonorTuning.swift:6@7c200bf

Fixes keep flowing while the walk is paused (`isActiveStatus` includes paused) and while meditating. The engine processes every fix; the gates only stop voices from starting (§3.4).

#### 3.4 The gates

The four gates are combined; any one closed holds voices.

```swift
        // combineLatest waits for all four inputs; callers bind @Published
        // projections, which emit on subscribe, so the gates are live at once.
        isPaused.combineLatest(isMeditating, isRecordingVoice, externalAudio)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] paused, meditating, recording, audio in
                self?.setGates(paused: paused, meditating: meditating, recording: recording, externalAudio: audio)
            }
            .store(in: &cancellables)
```
> Pilgrim/Models/Honor/HonorEngine.swift:108-115@7c200bf

```swift
    struct Gates: Equatable {
        var paused = false
        var meditating = false
        var recording = false
        var externalAudio = false
        var isClosed: Bool { paused || meditating || recording || externalAudio }
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:19-25@7c200bf

- `paused` is `status != .recording`, so it is closed before Start (`.waiting`, `.ready`) and in both pause states. The view model's `status` is itself fed through a main-queue hop (`liveStats.status.receive(on: DispatchQueue.main)`, `ActiveWalkViewModel.swift:313-316`), so at bind time the gate may briefly read closed; it opens on the next main-queue turn. First-emission trap (archetype F): the gate's first value is a real state, not a transition, and the tracker treats it as one (`gatesDidChange` either pauses a playing voice or tries to start the next — never "resume" without a pause).
- `recording` is `isRecordingVoice`, which mirrors the recorder through another main-queue hop (`ActiveWalkViewModel.swift:526-529`).
- `externalAudio` is a community whisper actually playing (`AudioPriorityQueue.isPlayingWhisper`). How whispers queue behind a Way voice belongs to the audio reader.
- The voice guide is not a gate here. The Way voice player itself waits for a guide prompt (`play` parks the URL while the guide speaks; `WayVoicePlayer.swift:58-64`), which is the audio reader's cluster.

A gate closing mid-voice pauses it; opening resumes it. With nothing playing, opening tries the next queued voice:

```swift
    mutating func gatesDidChange(_ gates: Gates) -> [Action] {
        if playing != nil {
            if gates.isClosed, !isVoicePaused {
                isVoicePaused = true
                return [.voicePause]
            }
            if !gates.isClosed, isVoicePaused {
                isVoicePaused = false
                return [.voiceResume]
            }
            return []
        }
        return startNextIfPossible(gates: gates)
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:96-109@7c200bf

#### 3.5 The clock: active duration, wall clock, and what it includes

The engine's clock is the view model's `activeDurationSeconds`, recomputed on a 1 s main-run-loop timer as wall-clock time since start minus pauses. Meditation is not subtracted.

```swift
        Timer.TimerPublisher(interval: 1, runLoop: .main, mode: .common)
            .autoconnect()
            .combineLatest(startDate, pauses)
            .combineLatest(voiceRecordings)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] timerPauses, recordings in
                guard let self else { return }
                let (_, start, pauseList) = timerPauses
                guard let start else { return }
                let pauseDuration = pauseList.map { $0.duration }.reduce(0, +)
                let activeDuration = max(0, start.distance(to: Date()) - pauseDuration)
                self.activeDurationSeconds = activeDuration
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:535-546@7c200bf

Drift trap: Android's `WalkStats.activeWalkingMillis` subtracts meditation too (`P/domain/WalkStats.kt:19-36@5ea4029b`). Feeding it to the engine would freeze the companion and shrink `yourSeconds` during every sitting, which iOS does not do. The iOS companion keeps walking while the walker sits. Also note the pause list only grows at resume (`WalkBuilder.swift:146-154`), so mid-pause the iOS clock keeps counting until resume subtracts the span — the companion jumps back at resume. That only matters on Android, where pause is reachable (§8.1); Android's accumulator subtracts the ongoing pause at once, which is the plan's "companion freezes while paused" and the intended reading.

The engine's other timers read `Date()` (wall clock), not fix time: re-acquire after 120 s off the Way, retry every 10 s, and the soft tap after 120 s beyond 200 m.

```swift
        isOnWay = false
        let time = now()
        if offWaySince == nil {
            offWaySince = time
            offWayActiveDuration = activeDuration
        }
        let dueForRetry = lastReacquireAttempt
            .map { time.timeIntervalSince($0) >= HonorTuning.reacquireRetrySeconds } ?? true
        if let since = offWaySince, time.timeIntervalSince(since) >= HonorTuning.reacquireSeconds, dueForRetry {
```
> Pilgrim/Models/Honor/HonorEngine.swift:211-219@7c200bf

```swift
    init(way: Way, softTapEnabled: Bool, voicesEnabled: Bool, now: @escaping () -> Date = { Date() }) {
```
> Pilgrim/Models/Honor/HonorEngine.swift:80@7c200bf

The timers only advance when a fix arrives (they are compared inside `processLocation`), so a blackout delays them until the next accepted fix. Engine thresholds belong to the engine reader; the clock source is answered here because U17 feeds it.

### 4. Engine events and what the view model does with each

The engine's full event vocabulary:

```swift
enum HonorEngineEvent: Equatable {
    case momentReached(WayMoment)
    case voiceStart(WayMoment)
    case voicePause
    case voiceResume
    case voiceDropped(WayMoment)
    case softTap(offWayMeters: Double)
    case markAhead(mark: WayMark, meters: Double)
    case arrived(theirSeconds: Double, yourSeconds: Double)
}
```
> Pilgrim/Models/Honor/HonorEngine.swift:7-16@7c200bf

The router, whole:

```swift
    func handleHonorEvent(_ event: HonorEngineEvent) {
        switch event {
        case .momentReached(let moment):
            if !honorCards.contains(moment) { honorCards.append(moment) }
            reachedMomentIDs.insert(moment.id)
            fireHonorHaptic(.waypointDropped)

        case .voiceStart(let moment):
            startVoice(moment)

        case .voicePause:
            isVoicePaused = true
            wayVoicePlayer?.pause()

        case .voiceResume:
            isVoicePaused = false
            wayVoicePlayer?.resume()

        case .voiceDropped(let moment):
            if activeVoice == moment {
                wayVoicePlayer?.stop()
                activeVoice = nil
                isVoicePaused = false
            }

        case .softTap(let meters):
            showSoftTapCaption(meters: meters)
            fireHonorHaptic(.honorOffWay)

        case .markAhead(let mark, let meters):
            showMarkCaption(mark: mark, meters: meters)
            fireHonorHaptic(.honorWaterAhead)

        case .arrived(let theirSeconds, let yourSeconds):
            recordHonorArrival(theirSeconds: theirSeconds, yourSeconds: yourSeconds)
            fireHonorHaptic(.honorArrival)
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:175-212@7c200bf

Event-to-effect table (haptic names are iOS `HapticPattern` cases; the patterns themselves belong to the audio/haptics reader):

| Event | State written | Sound | Haptic (foreground only) | Card |
|---|---|---|---|---|
| `momentReached` (non-voice only) | `reachedMomentIDs += id` | none | `.waypointDropped` | appended to the back, if not already queued |
| `voiceStart` | `activeVoice`, `isVoicePaused = false`, `heardVoiceIDs += id`, pins rebuilt | `play(url:volume:)` | none | the voice's card jumps to the front |
| `voicePause` | `isVoicePaused = true` | `pause()` | none | unchanged |
| `voiceResume` | `isVoicePaused = false` | `resume()` | none | unchanged |
| `voiceDropped` | clears `activeVoice` only if it is the dropped one | `stop()` | none | unchanged |
| `softTap` | `softTapCaption`, retires after 20 s | none | `.honorOffWay` | none |
| `markAhead` (stages only) | `softTapCaption` (water line), retires after 20 s | none | `.honorWaterAhead` | none |
| `arrived` | event, waypoint, `honorArrival` (§2.2) | none | `.honorArrival` | the arrival card (above all place cards) |

Voices never produce `momentReached`: the tracker enqueues them instead and they surface only at `voiceStart`. With voices disabled, a reached voice is swallowed: it gets no card and is never heard.

```swift
            reached.insert(moment.id)
            if moment.isVoice {
                if voicesEnabled { queue.append(moment) }
            } else {
                actions.append(.reached(moment))
            }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:68-73@7c200bf

Haptics fire right after the state write, with no wait for playback, and only while the app is active (`isAppActive`, §1). The plan's Android divergence (haptics screen-off, only after playback starts) is R6 and U18's.

#### 4.1 `voiceStart`: a missing file hands the turn straight back

```swift
    /// A voice whose file is gone was never heard: hand the turn straight
    /// back to the engine so the next one can start.
    private func startVoice(_ moment: WayMoment) {
        guard case .voice(_, _, let kind, let media) = moment.kind, let url = mediaURL(for: media) else {
            honorEngine?.voiceDidFinish()
            return
        }
        activeVoice = moment
        isVoicePaused = false
        heardVoiceIDs.insert(moment.id)
        refreshHonorPins()
        wayVoicePlayer?.play(url: url, volume: Self.voiceVolume(for: kind))
        // The voice's own card rises with it — the waveform, the place, the
        // reply — and retires itself after the voice unless the walker
        // touches it, so an unanswered voice never leaves a card to close.
        showCard(for: moment)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:214-230@7c200bf

Order: state, heard, pins, play, card. "Heard" means "the player was asked to play", written before playback starts. The volume is the voice guide's volume preference, halved for ambient recordings:

```swift
    /// Ambience is the sound of a place, not a voice: it plays once at half
    /// the voice level on entry. A continuous bed inside its span is deferred
    /// (one player at a time, per the resource-safety rules).
    static func voiceVolume(for kind: VoiceKind) -> Float {
        let base = Float(UserPreferences.voiceGuideVolume.value)
        return kind == .ambient ? base * 0.5 : base
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:339-345@7c200bf

A file exists but fails to open or play: the player finishes with `notify: true` synchronously inside `play`:

```swift
            p.prepareToPlay()
            guard p.play() else {
                finish(notify: true)
                return
            }
...
        } catch {
            print("[WayVoicePlayer] playback error: \(error)")
            finish(notify: true)
        }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:166-170,175-178@7c200bf

So a voice that fails to play has already been marked heard, its pin shows heard, `onFinished` clears the chip and asks the engine for the next voice (which can start synchronously, re-entering `startVoice` from inside `play`), the failed voice's retire is scheduled for 20 s, and only then does `showCard(for: moment)` put the failed voice's card at the front — above the next voice's card, if one started. The card itself is not marked failed; it shows as not playing (`isPlaying: viewModel.activeVoice == moment`, `ActiveWalkView+Honor.swift:77`). A decode error mid-playback takes the async delegate path to the same `finish(notify: true)` (`WayVoicePlayer.swift:136-141`). This answers the plan's "what a place card shows when its voice fails to play" from the view model's side; the card's own rendering is the UI reader's.

Media resolution, including the path-containment check. `.recording` resolves under the app's Documents (own walks); `.file` under the Way's media folder (shared Ways, branch point); `.photoAsset` never plays.

```swift
    static func localMediaURL(for media: WayMedia, wayId: String, store: WayStore) -> URL? {
        switch media {
        case .recording(let relativePath):
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            return resolvedMediaURL(docs.appendingPathComponent(relativePath), within: docs)
        case .file(let relative):
            let url = store.mediaURL(for: wayId, relative: relative)
            return resolvedMediaURL(url, within: store.mediaDirectory(for: wayId))
        case .photoAsset:
            return nil
        }
    }

    /// A relative path comes from a Way's own JSON — a share file or a
    /// hand-edited own-walk record — so a `../` component must not be able
    /// to walk it outside its base directory. Standardizing collapses any
    /// such component before the containment check.
    private static func resolvedMediaURL(_ url: URL, within base: URL) -> URL? {
        let resolved = url.standardizedFileURL
        let baseComponents = base.standardizedFileURL.pathComponents
        guard resolved.pathComponents.count > baseComponents.count,
              Array(resolved.pathComponents.prefix(baseComponents.count)) == baseComponents else { return nil }
        return FileManager.default.fileExists(atPath: resolved.path) ? resolved : nil
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:354-377@7c200bf

The file-exists check runs at every play, so a recording deleted mid-walk simply reads as missing at its spot.

#### 4.2 The soft-tap caption

```swift
    private func showSoftTapCaption(meters: Double) {
        // `Int(_:)` traps on an infinity; the engine already clamps, and this
        // is the last line of defence before the number reaches the screen.
        softTapCaption = "off the way · \(Int(min(meters.isFinite ? meters : 0, 999_999))) m"
        let generation = honorGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.softTapCaptionSeconds) { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            self.softTapCaption = nil
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:236-245@7c200bf

```swift
    /// Not private: the water notice borrows this slot, so it borrows this life.
    static let softTapCaptionSeconds: TimeInterval = 20
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:457-458@7c200bf

- Exact string: `"off the way · N m"` (a middle dot U+00B7 with spaces), metres always, integer-truncated, capped at 999 999. Not localized, and it ignores the unit preference. The test pins it: `XCTAssertEqual(vm.softTapCaption, "off the way · 250 m")` (`UnitTests/Honor/ActiveWalkHonorTests.swift:429`).
- Lifetime 20 s, generation-guarded; not cancelled when a newer caption replaces it, so a second caption set 15 s after the first is cleared 5 s later by the first timer. That is iOS's behavior (archetype A trap: an Android port that cancels the earlier timer diverges).
- Where it shows: the minimized bar's third stat, in place of "Remaining" (the UI reader's `WalkStatsSheet.swift:405-422`).
- Reachability: the engine emits `softTap` only when the preference is on, and nothing in the app turns it on (§11).

#### 4.3 Marks (stages only)

Branch point: `markAhead` and `bindMarkPins()` act only on `way.marks`, which only pilgrimage stages carry (`applyMarkPins` returns early on empty marks, `ActiveWalkViewModel+MarkPins.swift:51-55`). The water caption reuses the soft-tap slot and its 20 s life: `"water in \(WayDistance.string(...))"` (`ActiveWalkViewModel+MarkPins.swift:63-70`). Own walks never reach either.

#### 4.4 `voiceDropped`

The tracker drops queued voices more than 300 m from their spot, and a paused current voice likewise, but never while a voice is playing and never while the walker is stationary. The view model only reacts if the dropped voice is the active one. No card changes and no retire is scheduled for a dropped active voice (its card, if still queued, waits for a dismissal).

```swift
        if !isStationary, playing == nil || isVoicePaused {
            let dropped = queue.filter { here.distance(from: place(of: $0)) > HonorTuning.voiceDropMeters }
            queue.removeAll { dropped.contains($0) }
            actions += dropped.map { .voiceDropped($0) }
            if let current = playing, isVoicePaused,
               here.distance(from: place(of: current)) > HonorTuning.voiceDropMeters {
                playing = nil
                isVoicePaused = false
                actions.append(.voiceDropped(current))
            }
        }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:79-89@7c200bf

### 5. The card queue

The queue is one ordered array, `honorCards: [WayMoment]`, front first. Only the front card renders. The arrival card, while not dismissed, renders instead of it (the place cards stay queued underneath).

```swift
            if let card = viewModel.honorArrival, !viewModel.honorArrivalCardDismissed {
                HonorArrivalCardView(
...
            } else if let moment = viewModel.honorCards.first {
                let isPlaying = viewModel.activeVoice == moment
                WayPlaceCard(
...
                    pendingCount: max(0, viewModel.honorCards.count - 1),
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:62-63,76-90@7c200bf

#### 5.1 What enqueues a card, and where it goes

| Trigger | Placement | Dedupe |
|---|---|---|
| `momentReached` (a place, rest, sitting, photo, waypoint reached) | appended to the back | skipped if already queued (`!honorCards.contains`) |
| `voiceStart` (engine-started voice) | moved or inserted at the front | any earlier copy removed first |
| A tapped Way pin | moved or inserted at the front | any earlier copy removed first |

```swift
    /// A tapped pin jumps the queue; pending cards resume after it.
    func showCard(for moment: WayMoment) {
        honorCards.removeAll { $0 == moment }
        honorCards.insert(moment, at: 0)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:333-337@7c200bf

Pin taps only work during an active walk (the UI's guard, so a pin tapped before Start cannot queue a card):

```swift
    func showWayCard(for annotation: PilgrimAnnotation) {
        // The card LAYER is already gated on an active walk, but the queue
        // behind it is not: a pin tapped on the overview map before Begin
        // used to sit in `honorCards` and ambush the walker with someone
        // else's card the moment they started walking.
        guard viewModel.status.isActiveStatus, let momentID = annotation.kind.wayMomentID else { return }
        guard let moment = viewModel.way?.moments.first(where: { $0.id == momentID }) else { return }
        viewModel.showCard(for: moment)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:26-34@7c200bf

Each moment fires `momentReached` at most once per walk (the tracker's `reached` set), and each voice starts from the engine at most once (it leaves the queue when it starts). So the only source of repeats is pin taps and voice replays, which reorder rather than duplicate. There is no maximum length: every reached place stays queued until dismissed.

The test pins the ordering: reached places append; a starting voice raises its card; a tapped pin jumps ahead.

```swift
        XCTAssertEqual(vm.honorCards.map(\.id), ["voice-1"], "a starting voice raises its own card")
        drive(fix(lon: 500 / 111_320, seconds: 400))
        XCTAssertEqual(vm.honorCards.map(\.id), ["voice-1", "sit-1"])
        vm.dismissTopCard()
        XCTAssertEqual(vm.honorCards.map(\.id), ["sit-1"])
```
> UnitTests/Honor/ActiveWalkHonorTests.swift:123-127@7c200bf

```swift
        XCTAssertEqual(vm.honorCards.map(\.id), ["wp-1", "wp-2"])
        vm.showCard(for: second)
        XCTAssertEqual(vm.honorCards.map(\.id), ["wp-2", "wp-1"])
```
> UnitTests/Honor/ActiveWalkHonorTests.swift:355-357@7c200bf

#### 5.2 Dismissal and retirement

Tap to dismiss always removes the front card and sends the map home:

```swift
    func dismissTopCard() {
        if !honorCards.isEmpty { honorCards.removeFirst() }
        // A card that flew the map somewhere takes the map home when it goes.
        honorFocus = nil
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:327-331@7c200bf

Auto-retire exists only for voice cards, only 20 s after the voice finished on its own (the player's `onFinished`: natural end or play failure), and only if the walker never touched the card and that voice is not playing again:

```swift
    /// The seconds a finished voice's card stays before retiring on its own.
    static let cardRetireSeconds: TimeInterval = 20

    func touchCard(_ moment: WayMoment) {
        touchedCardIDs.insert(moment.id)
    }

    /// A voice card the walker never touched leaves by itself once its voice
    /// has ended; one they touched (played again, replied to) waits for them.
    func retireIfUntouched(_ moment: WayMoment) {
        guard !touchedCardIDs.contains(moment.id), activeVoice != moment else { return }
        honorCards.removeAll { $0 == moment }
    }

    private func retireCardLater(_ moment: WayMoment) {
        let generation = honorGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.cardRetireSeconds) { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            self.retireIfUntouched(moment)
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:305-325@7c200bf

Rules an implementer must keep:
- The retire removes the card wherever it sits in the queue (`removeAll`), not only at the front.
- The retire timer is not cancelled by a later touch; the touch is checked when the timer fires.
- No retire is scheduled for a voice that was skipped (`skipVoice`), stopped by `playReply`, replaced by a manual replay, or dropped. Those cards wait for a dismissal. `stop()` is `finish(notify: false)`, so it never calls `onFinished` (`WayVoicePlayer.swift:109-113`).
- Non-voice cards never retire on their own.
- "Touched" is set by the card's own controls through `onTouch` (the UI reader's `WayPlaceCard` decides which controls count; `ActiveWalkView+Honor.swift:104`). Touch state lives for the walk (`touchedCardIDs`, cleared at teardown).

The plan's U22 phrasing "an untouched voice card retires 20 s after its persisted voice end" matches, provided "voice end" means only a natural end or a failed play (the two `notify: true` paths).

#### 5.3 Queue state at Begin, pause, meditation, and finish

- Begin: the queue is emptied and the generation bumped (§3.2, `ActiveWalkViewModel+Honor.swift:49-52`).
- Pause and meditation: nothing touches the queue. Cards stay; timers keep running on the wall clock (a retire due during a sitting fires during it). `MeditationView` is a full-screen cover over the walk screen, so queued cards are simply out of sight.
- Finish and discard: `teardownHonor()` empties the queue and bumps the generation, which turns every pending retire and caption timer into a no-op (§8.3). The arrival card's data (`honorArrival`) survives.

The card layer only exists in Honor mode during an active walk, and only takes touches when a card is showing:

```swift
        if viewModel.mode == .honor && viewModel.status.isActiveStatus {
            HonorCardHost(viewModel: viewModel, onSit: onSit)
...
                .allowsHitTesting(viewModel.isShowingHonorCard)
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:13-20@7c200bf

```swift
    var isShowingHonorCard: Bool {
        (honorArrival != nil && !honorArrivalCardDismissed) || !honorCards.isEmpty
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:267-269@7c200bf

#### 5.4 Card helpers the view model computes

Distance to a moment (straight line from the last fix, nil before the first fix), the heading tick (nil until both a fix and a settled compass), and the fly-to toggle:

```swift
    func distanceToMoment(_ moment: WayMoment) -> Double? {
        guard let here = currentLocation, let there = coordinate(of: moment) else { return nil }
        return CLLocation(latitude: here.latitude, longitude: here.longitude)
            .distance(from: CLLocation(latitude: there.latitude, longitude: there.longitude))
    }

    /// Degrees clockwise from the walker's heading to the moment: the
    /// direction tick. Nil until both a fix and a settled compass exist.
    func relativeBearing(to moment: WayMoment) -> Double? {
        guard let here = currentLocation, let heading = headingDegrees, let there = coordinate(of: moment) else { return nil }
        let bearing = WayGeometry.bearing(from: CLLocationCoordinate2D(latitude: here.latitude, longitude: here.longitude), to: there)
        return (bearing - heading + 360).truncatingRemainder(dividingBy: 360)
    }

    /// One tap sends the map to the moment, the next brings it home; a
    /// different moment's header jumps straight to that moment.
    func toggleFocus(on moment: WayMoment) {
        guard let there = coordinate(of: moment) else { return }
        if let focus = honorFocus, focus.latitude == there.latitude, focus.longitude == there.longitude {
            honorFocus = nil
        } else {
            honorFocus = there
        }
    }

    /// A moment recorded with its own coordinate uses it; one placed only
    /// along the line borrows the line's point at its frac.
    private func coordinate(of moment: WayMoment) -> CLLocationCoordinate2D? {
        if let at = moment.at { return CLLocationCoordinate2D(latitude: at.lat, longitude: at.lon) }
        return honorEngine?.geometry.coordinate(atFrac: moment.frac)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:273-303@7c200bf

- Heading source: the compass (`HeadingProvider`, true heading), started with the engine and stopped at teardown (test: `XCTAssertEqual(heading.starts, 1, "the compass starts with the engine")`, `ActiveWalkHonorTests.swift:581`). Not the course over ground. `HeadingProvider` internals belong to the UI reader.
- The tick formula keeps a positive remainder (`+ 360` before `truncatingRemainder`); Kotlin's `%` on a negative operand returns a negative, so the `+ 360` must stay.
- Before the engine exists (walk screen before Start), a moment without `at` has no coordinate, so its distance and focus are nil. Own-walk moments always carry `at` (§12).

### 6. The walker's voice controls (card and chip commands)

These are the commands U17 turns into never-redelivered intents. On iOS each is a direct call on the main thread.

#### 6.1 Play/pause, and replaying a voice outside the queue

```swift
    /// From the card or the chip: pause or resume the active voice, or replay
    /// another voice outside the engine's queue. A heard voice must mean a
    /// played voice, so this is a no-op before the walk has a player at all —
    /// otherwise a pin tap before Begin could mark a voice heard with
    /// nothing behind it to play.
    func togglePlayback(of moment: WayMoment) {
        guard let player = wayVoicePlayer else { return }
        if moment == activeVoice {
            if isVoicePaused { player.resume() } else { player.pause() }
            isVoicePaused.toggle()
            return
        }
        guard case .voice(_, _, let kind, let media) = moment.kind, let url = mediaURL(for: media) else { return }
        player.stop()
        activeVoice = moment
        isVoicePaused = false
        heardVoiceIDs.insert(moment.id)
        refreshHonorPins()
        player.play(url: url, volume: Self.voiceVolume(for: kind))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:384-403@7c200bf

- The walker's pause is not an engine gate. The engine keeps believing its voice plays, so it starts nothing else; the walker's pause holds the queue until they resume or skip.
- Replaying a different voice stops whatever plays (no `onFinished`), takes over `activeVoice`, marks the replayed voice heard, and plays it. The engine is not told. If the engine had a voice of its own in flight, it still counts that voice as playing; when the replay ends, `onFinished` calls `voiceDidFinish()`, which releases the engine's voice as finished — the interrupted voice is never resumed.
- Replays ignore the "walk with their voice" preference: a walker who turned voices off can still play one from its pin's card.
- If the engine has nothing in flight, a replay does not hold the queue: the next engine `voiceStart` calls `play` on the shared player, and `start(url:)` stops the replay first (`WayVoicePlayer.swift:150-152`).
- Gates do not reach a replay the engine does not know about: `gatesDidChange` pauses only the engine's own voice (§3.4). §13.5 and defect 5 cover the consequence.

#### 6.2 Scrub

```swift
    /// Scrubbing a card whose voice isn't the one playing starts that voice
    /// first — the walker asked for a spot in it, not for silence.
    func seekVoice(_ moment: WayMoment, toFraction fraction: Double) {
        if moment != activeVoice { togglePlayback(of: moment) }
        guard moment == activeVoice else { return }
        wayVoicePlayer?.seek(toFraction: fraction)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:405-411@7c200bf

The player clamps the fraction to `0...0.999` of the duration (`WayVoicePlayer.swift:115-119`).

#### 6.3 Rate

```swift
    /// 1× → 1.25× → 1.5× → 2× → 1×, the same ladder as the post-walk player.
    func cycleVoiceRate() {
        let rates = WayVoicePlayer.rates
        let next = rates[((rates.firstIndex(of: voiceRate) ?? 0) + 1) % rates.count]
        voiceRate = next
        wayVoicePlayer?.setRate(next)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:413-419@7c200bf

```swift
    /// The walker's chosen speed, kept across the voices of one walk.
    @Published private(set) var playbackRate: Float = 1
...
    static let rates: [Float] = [1, 1.25, 1.5, 2]
...
    func setRate(_ rate: Float) {
        playbackRate = rate
        player?.rate = rate
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:28-32,121-124@7c200bf

- Ladder `[1, 1.25, 1.5, 2]`, wrapping to 1. An unknown current rate (`firstIndex` nil) restarts the ladder at 1.25.
- The rate applies to the playing voice and every later voice (`start` sets `p.rate = playbackRate` after `p.enableRate = true`, `WayVoicePlayer.swift:163-165`).
- Lifetime: the view model resets its own `voiceRate` to 1 at teardown (§8.3), but never tells the player. The player is a process-wide singleton, so its `playbackRate` carries into the next walk while the chip reads 1×. That is a defect (listed below). The plan's pin "iOS's playback rate persisting across walks": it persists by accident; the documented intent on both sides is per walk ("1× at the start of every walk"; "kept across the voices of one walk").

#### 6.4 Skip

```swift
    func skipVoice() {
        guard activeVoice != nil else { return }
        wayVoicePlayer?.stop()
        activeVoice = nil
        isVoicePaused = false
        honorEngine?.voiceDidFinish()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:421-427@7c200bf

Skip needs an active voice, stops without `onFinished` (so no card retire), and hands the engine its turn, which may start the next queued voice at once.

#### 6.5 "your reply" playback (`playReply`)

```swift
    /// The card's "your reply" button comes through here rather than touching
    /// the player directly: one voice plays at a time, so a Way voice must be
    /// given up rather than silently replaced under a chip that still claims
    /// it is playing. The engine gets its turn back when the reply ends,
    /// through the player's `onFinished`.
    func playReply(url: URL) {
        wayVoicePlayer?.stop()
        activeVoice = nil
        isVoicePaused = false
        wayVoicePlayer?.play(url: url, volume: Float(UserPreferences.voiceGuideVolume.value))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:65-75@7c200bf

Answers to the plan's `playReply` pins:
- It stops the active voice (no `onFinished`, no retire) and plays the reply through the same shared player, at the full voice-guide volume (never halved), at the player's current rate.
- The reply is not the active voice: `activeVoice` is nil while it plays, so the chip shows nothing playing and the card's play control does not claim it.
- The reply is never "heard": nothing is inserted into `heardVoiceIDs`.
- For the gates: if the engine had its own voice in flight when the reply started, the engine still counts that voice as playing, so a closing gate emits `voicePause` and the view model pauses the player — which pauses the reply. If the engine had nothing in flight, no gate pauses the reply (§13.5, defect 5).
- When the reply ends on its own, `onFinished` runs with `activeVoice` nil: nothing is retired, and `voiceDidFinish()` releases the engine's interrupted voice as finished and may start the next.
- `play` waits for a guide prompt like any Way voice (`WayVoicePlayer.swift:58-64`).

Android plan check (U17 "the engine getting its turn back when the reply ends", U18 "the next voice waits for the reply to end"): true only when the engine had a voice in flight. With the engine idle, iOS lets the next reached voice cut the reply off (`start` stops the current player), because the engine does not know the reply is playing.

### 7. Replies: recording, filing, linking, and finding them again

#### 7.1 Starting a reply

```swift
    /// Starts a recording answering `voice`. When that recording completes,
    /// `bindCompletedRecordings` has it, and `recordReplyIfPending` writes
    /// the mapping.
    func replyHere(to voice: WayMoment) {
        pendingReplyOrigin = voice
        if !isRecordingVoice { toggleVoiceRecording() }
        // `isRecordingVoice` only mirrors `voiceRecordingManagement.isRecording`
        // through an async main-queue sink, so it can't be trusted here yet —
        // reading the component directly gives the synchronous answer.
        // Denied permission, an inactive walk, or a recorder that failed to
        // open all leave it false; with nothing now in flight, no completed
        // recording will ever arrive to consume this origin.
        if !voiceRecordingManagement.isRecording {
            pendingReplyOrigin = nil
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:17-32@7c200bf

- A reply is an ordinary walk recording (same recorder, same `Recordings/<walk dir>/<uuid>.m4a` path, same Talk time). Only the origin mapping makes it a reply.
- If a plain recording is already running, `replyHere` does not start a new one: the running recording becomes the reply when it completes. (`isRecordingVoice` is the lagging mirror; a take started within the same main-queue turn can be missed and toggled off instead — an iOS race the port need not reproduce but should know.)
- The recording gate closes (`isRecordingVoice` true), so the engine's voice pauses (§3.4) while the walker answers.
- Starting any recording stops the voice guide (`VoiceGuidePlayer.shared.stop()`, `VoiceRecordingManagement.swift:93`).
- Microphone permission denied: `toggleVoiceRecording()` shows the permission alert and returns (`ActiveWalkViewModel.swift:429-436`), and the origin is cleared.

#### 7.2 Filing the reply when the recording completes

The completion listener is held apart from the view model's other subscriptions so it survives `stop()`, which is what lets a reply still recording at walk end be filed.

```swift
    /// Deliberately not stored in `cancellables`: `stop()` empties those
    /// before `builder.setStatus(.ready)` runs the pre-snapshot flush that
    /// finalizes a recording still in flight, so a reply started from the
    /// arrival card — offered at the moment the walker reaches for stop —
    /// would complete into a subscription that no longer existed and lose
    /// its origin mapping. This one lives as long as the view model, which
    /// outlives the walk, and ends when the view model is released.
    private func bindCompletedRecordings() {
        completedRecordingsCancellable = builder.voiceRecordingsPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] recordings in
                guard let self else { return }
                let isNewRecording = recordings.count > self.completedRecordings.count
                self.completedRecordings = recordings
                self.completedRecordingCount = recordings.count
                if isNewRecording, let latest = recordings.last {
                    self.recordReplyIfPending(latestRecording: latest)
                }
            }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:586-605@7c200bf

```swift
    /// The reply is filed under the origin voice's own index — the `n` in
    /// the `voice-n` ids `OwnWalkWayBuilder` writes — never its position in
    /// `moments`, which mixes every kind of moment together.
    func recordReplyIfPending(latestRecording: TempVoiceRecording) {
        guard let way, let origin = pendingReplyOrigin, let n = Self.originIndex(of: origin) else { return }
        pendingReplyOrigin = nil
        try? honorSenses.store().setReply(wayId: way.id, originN: n, relativePath: latestRecording.fileRelativePath)
    }
...
    private static let voiceIDPrefix = "voice-"

    /// The `n` in the `voice-n` ids `OwnWalkWayBuilder` writes — the index a
    /// reply is filed under — or the reserved index the stage's arrival
    /// reflection uses. Nil for any other moment id.
    static func originIndex(of moment: WayMoment) -> Int? {
        if moment.id == HonorPersistence.stageReflectionMomentID { return HonorPersistence.stageReflectionOrigin }
        guard moment.id.hasPrefix(voiceIDPrefix) else { return nil }
        return Int(moment.id.dropFirst(voiceIDPrefix.count))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:34-41,79-88@7c200bf

The rules:
- The trigger is "the builder's recording list grew". Every completion path grows it: the walker's stop and a call-driven stop (via the recorder's delegate), an interruption that stopped the recorder, and the walk-end pre-snapshot flush. An enhancement pass updates a recording in place, so the count does not grow and nothing is re-filed (`VoiceRecordingManagement.swift:233,240-249`).
- The newest recording (`recordings.last`) is filed. The origin is cleared before the write.
- Only `voice-N` moments (and the stage reflection, branch point) have an origin index; a reply to any other moment is dropped silently at completion.
- The mapping is `originN → the recording's relative path`. It is linked to its moment only by `N`, and to the walk only through the recording path (which carries the walk's recordings folder). No walk id, no timestamp, no Way-voice id is stored.
- A failed OS recording (`successfully: false`) appends nothing and leaves the origin set, so the next unrelated recording on this walk is filed as the reply (defects list).
- On Android the three insert sites the plan names are `P/ui/walk/WalkViewModel.kt:1164-1194@5ea4029b` (user stop), `P/ui/walk/WalkViewModel.kt:1134-1150@5ea4029b` (focus-loss interruption), and `P/walk/WalkLifecycleObserver.kt:92-121@5ea4029b` (walk-end auto-stop). iOS files from one listener after any of them; U22 files at each.

#### 7.3 Where the reply is filed, and the folder that may not exist yet

The store writes `replies.json` inside the Way's own folder:

```swift
    func replies(for id: String) -> [Int: String] {
        guard Self.isValidId(id) else { return [:] }
        guard let data = try? Data(contentsOf: directory(for: id).appendingPathComponent("replies.json")),
              let map = try? decoder.decode([String: String].self, from: data) else { return [:] }
        return Dictionary(uniqueKeysWithValues: map.compactMap { key, value in Int(key).map { ($0, value) } })
    }

    func setReply(wayId: String, originN: Int, relativePath: String) throws {
        guard Self.isValidId(wayId) else { throw CocoaError(.fileWriteInvalidFileName) }
        var map = replies(for: wayId)
        map[originN] = relativePath
        let encodable = Dictionary(uniqueKeysWithValues: map.map { (String($0.key), $0.value) })
        try encoder.encode(encodable).write(to: directory(for: wayId).appendingPathComponent("replies.json"), options: .atomic)
    }
```
> Pilgrim/Models/Honor/WayStore.swift:166-179@7c200bf

- File: `Application Support/Ways/<wayId>/replies.json`, a JSON object whose keys are the decimal `N` as strings and whose values are relative paths, sorted keys, written atomically. A later reply to the same `N` overwrites the earlier one (one reply per voice per Way, across every honoring).
- `setReply` does not create the Way's folder. Only `save(_:)` does (`WayStore.swift:93-97`).
- An own-walk Way's folder is first written at the end of its first honoring, after the walk saves:

```swift
                    // The only place a finished walk is bound to its Way:
                    // `save` is idempotent (an own-walk Way is first written
                    // here), and `link` overwrites, so it must not run again
                    // elsewhere.
...
                    if let way, let uuid = walk?.uuid {
                        if !way.source.isPackageOwned { try? WayStore.shared.save(way) }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:108-117@7c200bf

So the answer to the plan's question "where the reply is filed before the Way folder exists": nowhere. On the first honoring of one of the walker's own walks, `Ways/walk:<uuid>/` does not exist during the walk. `.atomic` writes need the parent folder, so `setReply` throws, `try?` swallows it, and the origin is already cleared. The recording survives as a plain walk recording; its reply mapping is lost. A reply still recording at walk end is lost the same way: the pre-snapshot flush emits the recording through a main-queue hop that was enqueued before `onWalkCompleted`'s hop (`ActiveWalkViewModel.swift:226-230`), and the folder is created only in the save completion after that. Every iOS reply test pre-saves the Way (`try store.save(testWay)`, `ActiveWalkHonorTests.swift:247,305`), so the missing-folder path is untested. Shared Ways (saved at import) and stages (package folders) always have the folder, so replies there work; an own Way works from its second honoring on. This is a defect (listed below). The plan's own-walk staging under the Begin-minted uuid gives Android a folder; filing into it differs from iOS as shipped, so R5 applies (file upstream, then match the fix).

#### 7.4 Finding a reply again, and what shows afterwards

```swift
    /// The walker's earlier reply to `voice`, from a previous honoring of the
    /// same Way. A mapping whose recording is gone reads as no reply at all —
    /// `mediaURL(for:)` returns nil for a file that isn't there.
    func existingReplyURL(for voice: WayMoment) -> URL? {
        guard let way, let n = Self.originIndex(of: voice),
              let relative = honorSenses.store().replies(for: way.id)[n] else { return nil }
        return mediaURL(for: .recording(relativePath: relative))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:43-50@7c200bf

- The card resolves its reply when it appears and again whenever the saved-recording count changes, so a reply filed on this walk shows on its card at once (as "your reply"), not only on a later honoring:

```swift
    /// Re-resolves when the card changes, and again once a recording has
    /// finished saving — the completed count, not the recording flag: the
    /// file only becomes readable after the flag has already gone back to
    /// false, so keying on the flag left a fresh reply hidden until the card
    /// was closed and reopened.
    private func lookupKey(for moment: WayMoment) -> String {
        "\(moment.id)|\(viewModel.completedRecordingCount)"
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:113-120@7c200bf

- Only voice cards look one up (`existingReply = moment.isVoice ? viewModel.existingReplyURL(for: moment) : nil`, `ActiveWalkView+Honor.swift:125`).
- A mapping whose file is gone (the honor walk that recorded it was deleted, which deletes its recordings: `DataManager.swift:795-804`) reads as no reply.
- The key is the positional `N` of `voice-N`, which the own-walk builder assigns by counting the source walk's recordings whose files still exist, oldest first (`OwnWalkWayBuilder.swift:39-50`). Deleting one of the source walk's recordings between honorings renumbers the later voices, and an earlier reply then appears on the wrong voice (defects list).
- The summary's reply list is the summary reader's.

### 8. The walk lifecycle with Honor: pause, meditation, finish, discard

#### 8.1 Pause and resume

iOS at the pin has no pause the walker can reach. No code sets `.paused`; the only `setStatus` calls are Start, `resume()`, and finish:

```swift
    func resume() {
        builder.setStatus(.recording)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:391-393@7c200bf

Auto-pause never fires for a walk, because the detector skips the walking and hiking types:

```swift
            guard !(self.currentStatus == .paused), self.currentStatus.isActiveStatus, location.speed >= 0, ![.walking, .hiking].contains(workoutType) else { return }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/AutoPauseDetection.swift:60@7c200bf

What iOS's Honor code would do if paused, which is the semantics Android's reachable pause should carry (`P/domain/WalkReducer.kt:89-94,116-126@5ea4029b`):
- The pause gate closes (`status != .recording`): a playing engine voice pauses (`voicePause`), no new voice starts; it resumes on resume (`voiceResume`).
- Fixes still reach the engine (recording and paused are both active statuses), so moments can still be reached and cards still queue while paused.
- The clock subtracts pauses, so the companion and `yourSeconds` stop advancing for the pause (§3.5 on when iOS subtracts).
- The queue, the heard set, and the chip state are untouched.
- "Sit?" while paused: iOS puts no status guard on `startMeditation(minutes:)` and the card layer shows in any active status, so iOS would start a sitting from a pause if a pause existed. Android's reducer ignores `MeditateStart` while paused (`P/domain/WalkReducer.kt:111-140@5ea4029b`); iOS gives no rule to match, so the Android answer is its reducer's.

#### 8.2 Meditation (including "Sit?")

```swift
    func startMeditation(minutes: Int) {
        suggestedMeditationMinutes = minutes
        startMeditation()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:379-382@7c200bf

```swift
    func startMeditation() {
        guard !isMeditating else { return }
        if isRecordingVoice {
            voiceRecordingManagement.stopRecording()
        }
        meditationStartDate = Date()
        isMeditating = true
        soundManagement.onMeditationStart()
    }

    func endMeditationSilently(endDate: Date = Date()) {
        finalizeMeditation(endDate: endDate)
        isMeditating = false
    }
...
    private func finalizeMeditation(endDate: Date = Date()) {
        suggestedMeditationMinutes = nil
        guard let start = meditationStartDate else { return }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:473-486,502-504@7c200bf

The card's "Sit?" goes through the walk screen so the meditation screen opens too:

```swift
            honorCardLayer(bottomInset: mapBottomInset) { minutes in
                viewModel.startMeditation(minutes: minutes)
                showMeditation = true
            }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:160-163@7c200bf

What Honor does at meditation start and end:
- Start: an in-flight recording (a reply included) is stopped first and completes normally, so a reply is filed as usual (§7.2). Android already does this (`P/ui/walk/WalkViewModel.kt:1543-1558@5ea4029b`).
- The meditation gate closes: a playing engine voice pauses; queued voices wait.
- `suggestedMeditationMinutes` feeds only the static caption in `MeditationView` (`suggestedMinutes: viewModel.suggestedMeditationMinutes`, `ActiveWalkView.swift:220-222`). Nothing ends the sitting at N minutes.
- Order trap: `startMeditation(minutes:)` writes the minutes before the `isMeditating` guard, so "Sit?" during a sitting overwrites the caption's minutes without starting anything.
- The engine keeps processing fixes and the companion keeps moving (§3.5).
- End: `finalizeMeditation` clears the caption; the gate opens and the paused voice resumes (`voiceResume`) or the next queued voice starts. The test pins pause-then-resume across a sitting:

```swift
        vm.startMeditation(minutes: 7)
        settleCombineSchedulers()
        XCTAssertEqual(player.pauses, 1)
        XCTAssertTrue(vm.isVoicePaused)
        XCTAssertEqual(vm.suggestedMeditationMinutes, 7)
        vm.endMeditationSilently()
        settleCombineSchedulers()
        XCTAssertEqual(player.resumes, 1)
        XCTAssertFalse(vm.isVoicePaused)
        XCTAssertNil(vm.suggestedMeditationMinutes)
```
> UnitTests/Honor/ActiveWalkHonorTests.swift:136-145@7c200bf

#### 8.3 Finish (`stop()`) and the save

```swift
    func stop() {
        teardownSeek()
        // A reply still recording here keeps its origin through teardown, and
        // the recorder is deliberately left running: only the pre-snapshot
        // flush inside `builder.setStatus(.ready)` below finalizes an
        // in-flight recording synchronously. Stopping the recorder here would
        // hand that commit to AVAudioRecorder's asynchronous delegate, which
        // lands after the walk is snapshotted — losing the audio entirely.
        teardownHonor()
        cancellables.removeAll()
        proximityService.stopListening()
        // Checkpoint deletion happens in MainCoordinator's saveWalk success
        // callback (AF1) — a failed save must leave the checkpoint on disk
        // so launch recovery can restore the walk.
        sessionGuard?.stop()
        finalizeMeditation()
        soundManagement.onWalkEnd()
        voiceGuideManagement.stopGuiding()
        WalkActivityManager.shared.end()
        builder.setStatus(.ready)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:395-415@7c200bf

The Honor teardown, shared with discard:

```swift
    /// Runs from both `stop()` and `cancel()`; the second call is a no-op.
    /// `honorArrival` deliberately survives — the summary reads it. So does
    /// `pendingReplyOrigin`: a reply still recording at walk end completes
    /// after this teardown, and `recordReplyIfPending` needs the origin to
    /// file it under. `cancel()` clears it itself, since a discarded walk
    /// files no reply.
    func teardownHonor() {
        guard honorEngine != nil || wayVoicePlayer != nil else { return }
        if let engine = honorEngine, engine.isAnchoredOnWay {
            honorStageOutcome = HonorStageOutcome(progressFrac: engine.progressFrac,
                                                  arrived: engine.phase == .arrived)
        }
        honorGeneration += 1
        honorCancellables.removeAll()
        honorEngine?.stop()
        honorEngine = nil
        wayVoicePlayer?.stop()
        // The player outlives this walk (it's the shared singleton in
        // production); drop the closure here so it can't call back into a
        // torn-down view model once a future walk replaces it.
        wayVoicePlayer?.onFinished = nil
        wayVoicePlayer = nil
        activeVoice = nil
        isVoicePaused = false
        honorCards.removeAll()
        honorMarkPins.removeAll()
        markPinAnchor = nil
        reachedMomentIDs.removeAll()
        suggestedMeditationMinutes = nil
        touchedCardIDs.removeAll()
        voiceRate = 1
        honorFocus = nil
        honorHeading?.stop()
        honorHeading = nil
        headingDegrees = nil
        softTapCaption = nil
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:122-158@7c200bf

Finish order, from the tap to disk:
1. `teardownHonor()`: record the stage outcome (branch point, stages only), bump the generation (every pending retire and caption timer dies), cancel the engine's and the compass's subscriptions, stop the engine, stop the voice (no `onFinished`), detach `onFinished`, and clear the live state. Survivors: `honorArrival`, `honorArrivalCardDismissed`, `heardVoiceIDs`, `pendingReplyOrigin`, the Way.
2. The walk's other teardown (session guard stops but keeps the checkpoint; meditation finalized; soundscape end; guide stop; Live Activity end, §10.4).
3. `builder.setStatus(.ready)`: the pre-snapshot flush commits an in-flight recording (a reply is then filed by §7.2's listener, on a later main-queue turn), the snapshot is taken with the `HONOR_MODE` and any `HONOR_ARRIVAL` event and the arrival waypoint, and `onWalkCompleted` is posted to main.
4. The walk saves. On success only:

```swift
                if success {
                    // The save transaction is confirmed — only now is the
                    // crash-recovery checkpoint safe to discard (AF1). On
                    // failure it stays on disk and recoverIfNeeded re-saves
                    // the walk at next launch.
                    WalkSessionGuard.deleteCheckpointFile()
                }
                guard let self else { return }
                if success {
                    snapshot.uuid = walk?.uuid
                    // The only place a finished walk is bound to its Way:
                    // `save` is idempotent (an own-walk Way is first written
                    // here), and `link` overwrites, so it must not run again
                    // elsewhere. Crash recovery binds too, but only a walk
                    // this path never reached, so the two can't collide.
                    // A packaged stage is skipped — this `way` was captured at
                    // Begin, and an Update that redrew the stage while the
                    // walk was on would be written back over here.
                    if let way, let uuid = walk?.uuid {
                        if !way.source.isPackageOwned { try? WayStore.shared.save(way) }
                        let arrival = vm?.honorArrival.map { (theirSeconds: $0.theirSeconds, yourSeconds: $0.yourSeconds) }
                        try? WayStore.shared.link(walkUUID: uuid, to: way.id, arrival: arrival)
                        self.recordStageWalk(way: way, outcome: vm?.honorStageOutcome)
                    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:98-121@7c200bf

5. The Way as captured at Begin is written (`way.json`, plus `accepted.json` only if absent), overwriting an earlier own-walk `way.json`. `replies.json` is not touched by `save`.
6. The link: `index.json[walkUUID] = { wayId, theirSeconds, yourSeconds }`, the two numbers present only if arrival fired (a dismissed arrival card still carries them). The link is written whether or not the walker ever joined the Way.

```swift
struct WayLink: Codable, Equatable {
    let wayId: String
    /// The companion's timeline at arrival, recorded by the engine; nil when
    /// the walk ended before the end of the Way.
    let theirSeconds: Double?
    let yourSeconds: Double?
}
```
> Pilgrim/Models/Honor/WayStore.swift:3-9@7c200bf

7. Branch point: `recordStageWalk` writes the stage ledger (stages only; no-op for own walks, `MainCoordinatorView.swift:290-295`).

Every step in 5–7 is `try?`: a failed Way save or link write is silent, and the walk still reads as finished (archetype I on the iOS side). A failed walk save skips 5–7 entirely and keeps the checkpoint, so recovery (§9) binds instead.

Android mapping: `finishWalkAtomic` inside the FinalizeWalk effect (`P/walk/WalkControllerImpl.kt:526-538@5ea4029b`) is the equivalent of step 4; the plan's Honor finalize step is 5–6; U17's "live rows deleted last" has no iOS analogue (iOS keeps nothing live).

#### 8.4 Discard (`cancel()`)

```swift
    func cancel() {
        discardPendingReply()
        teardownSeek()
        teardownHonor()
        cancellables.removeAll()
        proximityService.stopListening()
        sessionGuard?.stopAndCleanup()
        soundManagement.onWalkEnd()
        voiceGuideManagement.stopGuiding()
        WalkActivityManager.shared.end()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:417-427@7c200bf

```swift
    /// A discarded walk files no reply, so the origin goes before the recorder
    /// does: discarding takes the partial file and the audio session with it,
    /// and a late commit must not be read as an answer to a Way.
    func discardPendingReply() {
        pendingReplyOrigin = nil
        voiceRecordingManagement.discardRecording()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:9-15@7c200bf

Order: the reply origin first, then the recorder discards its partial file (no commit), then the shared teardown, then the checkpoint is deleted. The builder never reaches `.ready`, so no snapshot, no save, no Way write, no link, no ledger. Nothing is written to the Ways store on a discard. Android's discard path: `P/ui/walk/WalkViewModel.kt:1638-1658@5ea4029b` and the `PurgeWalk` effect (`P/walk/WalkControllerImpl.kt:540-545@5ea4029b`); the plan's "a discard removes only the staging" has no iOS counterpart because iOS stages nothing.

#### 8.5 Deinit net

```swift
    deinit {
        seekEngine?.stop()
        seekSound?.stop()
        honorEngine?.stop()
        wayVoicePlayer?.stop()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:260-265@7c200bf

### 9. Checkpoint fields and crash recovery

#### 9.1 What the checkpoint carries for Honor

```swift
struct WalkCheckpoint: Codable {
    /// Shape of the on-disk checkpoint JSON. Bumped whenever `TempWalk` gains or
    /// loses fields in a way that older builds can't round-trip; `WalkSessionGuard`
    /// recovers any version from `minimumRecoverableSchemaVersion` through this one.
    static let currentSchemaVersion = 2

    /// Version 1 carried no Way identity. Everything it did carry decodes
    /// unchanged into this shape — the honor fields simply arrive nil — so a
    /// walk crashed under the previous build still recovers, as a plain walk.
    static let minimumRecoverableSchemaVersion = 1

    let schemaVersion: Int
    let walkUUID: UUID
    let checkpointDate: Date
    let walk: TempWalk
    /// The Way this walk is honoring, so a crash-recovered walk can be bound
    /// back to it. Nil for every walk that isn't an honor walk.
    let wayId: String?
    /// The engine's last word before the crash. The engine dies with the
    /// process, so a recovered stage walk has no other source for its ledger
    /// entry.
    let honorProgressFrac: Double?
    let honorArrived: Bool?
```
> Pilgrim/Models/Walk/WalkCheckpoint.swift:3-25@7c200bf

The values come from one read of the view model at each checkpoint:

```swift
    var honorCheckpointState: (wayId: String, outcome: HonorStageOutcome?)? {
        guard mode == .honor, let way else { return nil }
        guard let engine = honorEngine, engine.isAnchoredOnWay else { return (way.id, nil) }
        return (way.id, HonorStageOutcome(progressFrac: engine.progressFrac,
                                          arrived: engine.phase == .arrived))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:166-171@7c200bf

```swift
        var honor: (wayId: String, outcome: HonorStageOutcome?)?

        if let viewModel {
            let intervals = viewModel.checkpointActivityIntervals()
            snapshot.replaceActivityIntervals(intervals)

            if let inflightTalk = viewModel.voiceRecordingManagement.checkpointVoiceRecording() {
                snapshot.appendVoiceRecordings([inflightTalk])
            }

            honor = viewModel.honorCheckpointState
        }
...
        let checkpoint = WalkCheckpoint(
            walkUUID: resolvedUUID,
            walk: snapshot,
            wayId: honor?.wayId,
            honorProgressFrac: honor?.outcome?.progressFrac,
            honorArrived: honor?.outcome?.arrived
        )
```
> Pilgrim/Models/Walk/WalkSessionGuard.swift:171-195@7c200bf

- `wayId` is written for every honor walk once it has a start date, anchored or not.
- `honorProgressFrac` and `honorArrived` are written together, only once the walker has joined the Way (`isAnchoredOnWay` excludes Begin's frac-0 fallback). Recovery reads them only as a pair, and only for stages (`honorOutcome`, `WalkCheckpoint.swift:27-32`).
- The walk inside the checkpoint already carries the `HONOR_MODE` event, any `HONOR_ARRIVAL` event, and the arrival waypoint, because they sit in the builder's arrays.
- Not in the checkpoint: the arrival numbers, the anchor, the companion, the heard set, reached moments, the queue position, the card queue, the reply origin, the rate. None of it survives a crash.
- Cadence: a repeating timer at the power tier's interval — 30 s normal, 60 s meditating, 15 s low battery, 10 s critical — with the encode and atomic write on a utility queue:

```swift
        var checkpointInterval: TimeInterval {
            switch self {
            case .normal:     return 30
            case .meditation: return 60
            case .low:        return 15
            case .critical:   return 10
            }
        }
```
> Pilgrim/Models/Walk/WalkSessionGuard.swift:48-55@7c200bf

File: `Application Support/walk_checkpoint.json` (`WalkSessionGuard.swift:279-282`). Deleted on a successful save (§8.3) and on discard (§8.4).

#### 9.2 Recovery never resumes a walk

At the next launch, a checkpoint is saved as a finished walk (`finishedRecording: false`), and only after that save succeeds is the Way rebound:

```swift
        DataManager.saveWalk(object: recovered) { success, error, saved in
            if success {
                try? FileManager.default.removeItem(at: url)
                rebindWay(checkpoint: checkpoint, walkUUID: saved?.uuid, store: wayStore)
```
> Pilgrim/Models/Walk/WalkSessionGuard+Recovery.swift:119-122@7c200bf

```swift
    /// Everything the walk-end save does for an honor walk that a crash took
    /// instead: the index link the summary reads its stage block off, and the
    /// route's ledger. A recovered walk has never been linked, so this cannot
    /// overwrite a link the normal path wrote. No arrival is recorded — the
    /// engine's arrival numbers died with the process.
    private static func rebindWay(checkpoint: WalkCheckpoint, walkUUID: UUID?, store: WayStore) {
        guard let wayId = checkpoint.wayId, let walkUUID,
              let way = store.load(id: wayId) else { return }
        try? store.link(walkUUID: walkUUID, to: wayId, arrival: nil)
        guard let stage = way.stage else { return }
        PilgrimageLedgerStore(store: store).record(
            stage: stage, outcome: checkpoint.honorOutcome, at: checkpoint.checkpointDate)
    }
```
> Pilgrim/Models/Walk/WalkSessionGuard+Recovery.swift:133-145@7c200bf

How recovery "restores" Honor, completely:
- The walk row keeps its Honor events and the arrival waypoint from the checkpoint, so it reads as an honor walk (and as arrived, if arrival had landed before the last checkpoint).
- The link is written only if the Way is already in the store, with no arrival numbers. An own Way on its first honoring was never saved (§7.3), so a crash on a first honoring leaves an honor walk with no link and no delta. The plan already lists this upstream issue.
- The own-walk Way itself is never saved by recovery.
- Branch point: the ledger for stages.
- No engine, player, card, or reply state is rebuilt. A reply in flight at the crash is reconnected as a plain recording by the orphan scan (`reconnectOrphanedRecordings`, `WalkSessionGuard+Recovery.swift:215-258`), or blanked if unplayable (`sanitizeRecording`, lines 31-55); its origin mapping is gone.
- Stale Live Activities are ended at app launch (`WalkActivityManager.shared.endAllStaleActivities()`, `AppDelegate.swift:63`).

Android contrast for U17: Android has a live restore (`restoreActiveWalk`, `P/walk/WalkControllerImpl.kt:338-429@5ea4029b`) and the OS's START redelivery, neither of which exists on iOS. The plan's revival rules (rebuild from Room and the staged Way, no replayed voice, adopt by uuid) are Android design, not parity; iOS supplies only the finish-time rule for recovered walks: link a Way only if it is already listed, never with a delta.

### 10. The glance (iOS Live Activity; Android's foreground-service notification)

#### 10.1 The state: three fields, computed in the app

```swift
/// Coarse lock-screen glance for an honor walk, mirroring `SeekGlanceState`'s
/// widget-shared shape: derived in the app process, rendered by the widget
/// with no sensors of its own.
struct HonorGlanceState: Codable, Hashable {
    let distanceRemainingBucketMeters: Int
    let isOnWay: Bool
    let isArrived: Bool
}
```
> Pilgrim/Models/Walk/Seek/SeekGlance.swift:21-28@7c200bf

```swift
    /// Computed here — never in the widget, which has no sensors.
    func currentHonorGlance() -> HonorGlanceState? {
        guard let engine = honorEngine else { return nil }
        return HonorGlanceState(
            distanceRemainingBucketMeters: SeekGlanceModel.distanceBucket(forMeters: engine.distanceRemainingMeters),
            isOnWay: engine.isOnWay, isArrived: engine.phase == .arrived)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:431-437@7c200bf

The bucket is Seek's: floor to 100 m, clamped to `0...2000`.

```swift
    static let bucketWidthMeters = 100.0
    static let maxBucketMeters = 2000
...
    static func distanceBucket(forMeters meters: Double) -> Int {
        let clamped = max(0, meters)
        let bucket = Int((clamped / bucketWidthMeters).rounded(.down)) * Int(bucketWidthMeters)
        return min(bucket, maxBucketMeters)
    }
```
> Pilgrim/Models/Walk/Seek/SeekGlance.swift:46-47,79-83@7c200bf

Engine inputs to the three fields:
- `distanceRemainingMeters` starts at the Way's full length and becomes `(1 - progressFrac) * totalMeters` on each accepted fix (`HonorEngine.swift:86,155`). Along the Way, not straight-line to the end.
- `isOnWay` starts false and is set per accepted fix: within 60 m of the Way inside the tracking window, or after a successful re-acquire (`HonorEngine.swift:30,198-209,243`).
- `isArrived` is `phase == .arrived`, which never reverts.

So the glance is nil before Start (no engine), and from Start until the first accepted fix within 60 m it reads `isOnWay = false` — including a walker approaching the trailhead under Begin's frac-0 fallback.

#### 10.2 The row and its exact strings

The honor row renders on the lock screen only, below the duration/distance row, any timer bar, and any Seek row (never both). It renders whenever `honor` is non-nil, in every walk state (recording, paused, meditating, talking) — there is no state gate.

```swift
            if let honor = context.state.honor {
                HStack(spacing: 6) {
                    Image(systemName: "signpost.right").font(.caption).foregroundColor(Self.stone)
                    Text(honor.isArrived ? "their way, walked"
                         : honor.isOnWay ? honorDistanceText(bucket: honor.distanceRemainingBucketMeters, imperial: context.attributes.isImperial)
                         : "off the way")
                        .font(.system(.caption, design: .serif)).foregroundColor(Self.ink)
                    Spacer()
                }
            }
```
> PilgrimWidget/PilgrimWidgetLiveActivity.swift:190-199@7c200bf

```swift
    /// The seek row reads a bucket as a bare distance, where the two end
    /// buckets are words ("close") or already carry a suffix ("2 km +").
    /// The honor row appends "to go", which turns those into "close to go"
    /// and "2 km + to go". Only the middle buckets compose — the ends get
    /// their own phrasing.
    private func honorDistanceText(bucket: Int, imperial: Bool) -> String {
        if bucket < 100 { return "almost there" }
        if bucket >= 2000 { return imperial ? "1.2 mi+ to go" : "2 km+ to go" }
        return "\(seekDistanceText(bucket: bucket, imperial: imperial)) to go"
    }
```
> PilgrimWidget/PilgrimWidgetLiveActivity.swift:255-264@7c200bf

```swift
    private func seekDistanceText(bucket: Int, imperial: Bool) -> String {
        if bucket >= 2000 { return imperial ? "1.2 mi +" : "2 km +" }
        if bucket < 100 { return "close" }
        if imperial {
            return String(format: "~%.1f mi", Double(bucket) / 1609.344)
        }
        if bucket >= 1000 {
            return String(format: "~%.1f km", Double(bucket) / 1000)
        }
        return "~\(bucket) m"
    }
```
> PilgrimWidget/PilgrimWidgetLiveActivity.swift:243-253@7c200bf

The full ladder, first match wins:

| Condition | Text |
|---|---|
| `isArrived` | `their way, walked` |
| not `isOnWay` | `off the way` |
| bucket `< 100` | `almost there` |
| bucket `>= 2000` | `2 km+ to go` / `1.2 mi+ to go` (no space before `+`, unlike Seek's `2 km +`) |
| imperial, 100–1999 | `~X.X mi to go` (`%.1f` of bucket ÷ 1609.344) |
| metric, 1000–1999 | `~X.X km to go` |
| metric, 100–999 | `~N m to go` |

Details an implementer must keep:
- Icon `signpost.right` (not the waypoint's `.fill` variant), caption size, colour `stone` `Color(red: 0.722, green: 0.592, blue: 0.431)`; text serif caption in `ink` `Color(red: 0.941, green: 0.922, blue: 0.882)` (`PilgrimWidgetLiveActivity.swift:25,29`).
- None of these strings are localized: the `Text` receives a `String` expression, not a literal key. Android's strings.xml entries are new, not translations of an iOS key.
- `String(format:)` here is the POSIX (non-localized) form, so the decimal separator is always `.`. Android's Seek ladder already pins `Locale.US` (`P/service/WalkNotificationFactory.kt:173-177@5ea4029b`); the Honor ladder must too (archetype E).
- Units come from the activity's attributes, fixed when the activity starts (`let isImperial = UserPreferences.distanceMeasurementType.safeValue == .miles`, `WalkActivityManager.swift:47`). A mid-walk units change does not reach the iOS glance; Android's fingerprint already re-renders on a units change (`P/service/WalkTrackingService.kt:980-993@5ea4029b`), a divergence that predates Honor.
- The Dynamic Island shows nothing Honor-specific (`compactTrailingView` checks only meditation, talk, and Seek; `PilgrimWidgetLiveActivity.swift:92-111`). Android has no counterpart surface.
- Branch point: a stage renders the same row, so an arrived stage reads "their way, walked" (the plan's filed-upstream item).

#### 10.3 When it updates

The view model passes the glance on every 1 s timer tick (`honor: self.currentHonorGlance()`, `ActiveWalkViewModel.swift:570-581`). The manager pushes only when something meaningful changed:

```swift
    static let distanceThreshold: Double = 15
    static let timeThreshold: TimeInterval = 15
...
    static func shouldPush(
        movedMeters: Double,
        flagsChanged: Bool,
        seekGlanceChanged: Bool,
        honorGlanceChanged: Bool = false,
        secondsSinceLastPush: TimeInterval
    ) -> Bool {
        movedMeters >= distanceThreshold
            || flagsChanged
            || seekGlanceChanged
            || honorGlanceChanged
            || secondsSinceLastPush >= timeThreshold
    }
```
> Pilgrim/Models/Walk/WalkActivityManager.swift:15-16,27-39@7c200bf

```swift
        guard Self.shouldPush(
            movedMeters: abs(distanceMeters - lastDistanceUpdate),
            flagsChanged: stateChanged,
            seekGlanceChanged: seek != lastSeekGlance,
            honorGlanceChanged: honor != lastHonorGlance,
            secondsSinceLastPush: Date().timeIntervalSince(lastUpdateDate)
        ) else { return }
...
        let staleInterval = seek != nil ? Self.seekStaleInterval : Self.timeThreshold * 3
        let staleDate = Date().addingTimeInterval(staleInterval)
```
> Pilgrim/Models/Walk/WalkActivityManager.swift:101-107,130-131@7c200bf

- A push happens on any change of the three honor fields (structural equality), a walked-distance change of 15 m, a pause/meditation/recording flag flip, or 15 s since the last push. An honor walk keeps the wander cadence (15 m, 15 s) on top of glance changes; Seek's longer 180 s stale net does not apply, so an honor activity goes stale 45 s (15 × 3) after its last push.
- The activity starts inside `startRecording()` after the engine (§2.1) with no honor state (`initialState` has none, `WalkActivityManager.swift:53-62`); the first tick (within 1 s) pushes the glance because it differs from `lastHonorGlance = nil`.
- Android mapping: the fingerprint and the notify gate in `P/service/WalkTrackingService.kt:994-1051@5ea4029b`. iOS's rule is "any field change notifies, plus the 15 m and 15 s arms". Android's Seek arm already drops the distance arm in favour of the glance plus a 15 s floor (`SEEK_NOTIFY_FLOOR_MILLIS`, line 950); an Honor arm built the same way changes only on bucket, on/off-Way, and arrival, with the floor keeping the walked-distance text fresh.
- Android's notification text today renders a glance only in `Active` (`P/service/WalkNotificationFactory.kt:106-132@5ea4029b`: Paused and Meditating are fixed strings). iOS shows the honor row in every state; U17 decides how the Honor arm renders in Paused and Meditating, and iOS's answer is "still shown".

#### 10.4 End

At finish and discard, `WalkActivityManager.shared.end()` ends every activity of the type with a frozen state that carries no honor field (`WalkActivityManager.swift:140-174`: the frozen `ContentState` is built without `seek` or `honor`), dismissed immediately. The final frame therefore drops the honor row.

### 11. Preferences read during an honor walk

```swift
    static let soundsEnabled = UserPreference.Required<Bool>(key: "soundsEnabled", defaultValue: true)
...
    static let voiceGuideVolume = UserPreference.Required<Double>(key: "voiceGuideVolume", defaultValue: 0.8)
    static let voiceGuideDuckLevel = UserPreference.Required<Double>(key: "voiceGuideDuckLevel", defaultValue: 0.15)
...
    static let honorVoicesEnabled = UserPreference.Required<Bool>(key: "honorVoicesEnabled", defaultValue: true)
    static let honorSoftTapEnabled = UserPreference.Required<Bool>(key: "honorSoftTapEnabled", defaultValue: false)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:36,57-58,77-78@7c200bf

| Key | Default | Read where, and when | Frozen for the walk? |
|---|---|---|---|
| `honorVoicesEnabled` | `true` | `startHonorEngineIfNeeded()`, AND-ed with `soundsEnabled` (`ActiveWalkViewModel+Honor.swift:58`) | yes, into the engine at Start |
| `soundsEnabled` | `true` | same line; the master Sounds switch also silences Honor voices | yes |
| `honorSoftTapEnabled` | `false` | same function, AND-ed with "not a stage" (`ActiveWalkViewModel+Honor.swift:57`) | yes |
| `voiceGuideVolume` | `0.8` | every voice start (`voiceVolume(for:)`, halved for ambient) and every `playReply` | no, read per play |
| `voiceGuideDuckLevel` | `0.15` | the Way voice player's soundscape duck at start and resume (`WayVoicePlayer.swift:83,156`; audio reader) | no, read per play |
| `distanceMeasurementType` | (existing) | glance units at Live Activity start (§10.2) | yes |

Who writes them:
- `honorVoicesEnabled` is written by the overview's "walk with their voice" toggle, immediately on change, and persists as the next walk's default. The toggle is hidden for a stage and disabled when the Way has no voices:

```swift
    @State private var voicesEnabled = UserPreferences.honorVoicesEnabled.value
...
            if !way.isPilgrimageStage {
                Toggle(isOn: $voicesEnabled) {
                    Text("walk with their voice")
                        .font(Constants.Typography.body)
                        .foregroundColor(.ink)
                }
                .tint(.stone)
                .onChange(of: voicesEnabled) { _, on in UserPreferences.honorVoicesEnabled.value = on }
                .disabled(way.voiceCount == 0)
            }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:80,263-272@7c200bf

  The engine reads the preference at the walk screen's Start, not at the overview's Begin, but nothing can change it in between (the overview is closed and the walk screen has no toggle), so "fixed at Begin" (the plan's U21 wording) is equivalent.
- `honorSoftTapEnabled` has no writer anywhere in the app at the pin; only tests set it. With the default `false`, the soft tap, its haptic, and the "off the way · N m" caption are unreachable in production. Android should carry the preference with the same default and no UI (or record a divergence if it adds one).

For Android's `:tracker` rules: the three frozen values ride ACTION_START (the plan's settings snapshot). The per-play volume and duck level are live reads on iOS; the plan already records that Android lacks both preferences, so the Honor voice's volume on Android is whatever U18 fixes it to, noted as a divergence against `0.8` × (0.5 for ambient).

### 12. "Walk this again": entering Honor mode from the view model's side

The mode and the Way reach the view model only through its initializer; neither changes during the walk.

```swift
    init(
        mode: WalkMode = .wander,
        way: Way? = nil,
        seekAccuracy: SeekAccuracyProviding = SeekLocationAccuracyProvider(),
        seekSenses: SeekSenses = SeekSenses(),
        honorSenses: HonorSenses = HonorSenses()
    ) {
        self.mode = mode
        self.way = way
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:198-206@7c200bf

The hand-off chain for "walk this again" (the doors themselves are the UI reader's):

```swift
    /// Called from a summary's "walk this again": hold the Way, let the
    /// summary sheet close, then present (AF60: never two sheets at once).
    /// A nil build (OwnWalkWayBuilder.make(from:) rejecting too short a route or a missing uuid) parks nothing and no overview appears; every host wiring `onWalkAgain` must also wire its summary's `onDismiss` to `promotePendingHonorWay`, since the park is global but the promote is per-host.
    func walkAgain(_ walk: WalkInterface) {
        pendingHonorWay = OwnWalkWayBuilder.make(from: walk)
    }

    func promotePendingHonorWay() {
        if let way = pendingHonorWay {
            pendingHonorWay = nil
            honorOverviewWay = way
            gather(way)
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:305-318@7c200bf

Then the overview's Begin calls `startHonor(way:)`, and its dismissal calls `startWalk(mode: .honor, way:)` (§3.1). `gather(way)` for an own Way only sets the import state to ready (`guard case .share = way.source else { self.honorImportState = .ready; return }`, `MainCoordinatorView.swift:270`); media gathering is a shared-Way branch.

What the Way is when it reaches the walk:
- Built fresh from the source walk at the moment the door is used, never loaded from the store, even when an earlier honoring saved one. Its id is `walk:<source walk uuid>` and its source `.ownWalk(uuid)`:

```swift
        return Way(
            id: "walk:\(uuid.uuidString)", source: .ownWalk(uuid), title: title,
            departedAt: walk.startDate, tzIdentifier: TimeZone.current.identifier, expires: nil,
            route: route, totalDistanceMeters: fullGeometry.totalMeters,
            theirActiveSeconds: walk.activeDuration, moments: moments, weather: weather, spans: spans)
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:108-112@7c200bf

- The same id on every honoring of the same source walk, so replies (keyed by Way id) and links accumulate under one folder; `save` at each finish overwrites `way.json` with that walk's rebuild.
- A nil build (route under 20 m, fewer than 2 samples, or no uuid) makes "walk this again" do nothing, silently; the Ways sheet's own-walk door shows an alert instead ("Can't walk this one again" / "This walk doesn't have enough of a route to follow. Try another.", `HonorWaysSheet.swift:95-105`).
- The view model captures the Way by value at Start. Nothing during the walk re-reads the source walk or the store for the Way itself; only media files are re-checked at each play (§4.1).

Android counterpart: the Begin use case (U17) that stages the Way under the Begin-minted uuid. iOS stages nothing and writes the Way only at a clean finish (§8.3).

### 13. Edge cases

#### 13.1 A Way whose source walk is deleted mid-walk

Not reachable through the iOS UI: the walk screen is a full-screen cover over the tab view, so the journal and Settings → Ways sit behind it for the whole walk.

```swift
        .fullScreenCover(item: $coordinator.activeWalkViewModel, onDismiss: {
            coordinator.handleActiveWalkDismiss()
        }) { vm in
            ActiveWalkView(viewModel: vm, onCancel: { coordinator.cancelWalk() })
```
> Pilgrim/Scenes/Root/MainTabView.swift:42-45@7c200bf

What the code would do if the source vanished anyway (Android's UI may allow it, which the plan anticipates):
- The Way is a value captured at Start (§12), so geometry, moments, companion, and arrival are unaffected.
- Deleting a walk deletes its recording files and touches nothing in the Ways store:

```swift
            case .success(let (filePaths, recordingUUIDs)):
                cleanupRecordingFiles(relativePaths: filePaths)
                transcriptContextStore.delete(recordingUUIDs: recordingUUIDs)
                if let uuid = walkUUID {
                    UserPreferences.unmarkWalkArchived(uuid: uuid)
                }
                completion(true, nil)
```
> Pilgrim/Models/Data/DataManager.swift:803-809@7c200bf

- Each voice then fails the file-exists check at its spot: the engine gets its turn back at once, the voice is not heard, and no card rises (§4.1). A voice already playing keeps playing from its open file handle until it ends (AVAudioPlayer holds the file; platform behavior, not code).
- At finish, `save(way)` still writes the captured Way and the link (§8.3). The store then lists a Way whose voices point at deleted recordings.
- The walker's replies are recordings of the honor walk, not the source walk, so they survive.

#### 13.2 A walk with Honor finished before anchoring

Two meanings of "anchoring" exist in the engine. The first accepted fix always sets `startFrac` (at the lowest frac within 60 m, else frac 0 as a fallback); `isAnchoredOnWay` is true only for a real join:

```swift
    /// Whether the walker ever actually joined the Way. Begin's frac-0
    /// fallback (nothing within 60 m) is an approach, not a joining, and a
    /// stage the walker never joined earns no ledger entry.
    var isAnchoredOnWay: Bool { startFrac != nil && !anchoredByFallback }
```
> Pilgrim/Models/Honor/HonorEngine.swift:42-45@7c200bf

A walk ended before a real join:
- Events: `HONOR_MODE` only; no arrival event, no arrival waypoint, `honorArrival` nil.
- Teardown records no stage outcome (branch point; stages only).
- The finish still saves the own Way and still writes the link, with `theirSeconds` and `yourSeconds` nil (§8.3). So on iOS a walk that never touched the Way is still bound to it and reads as an honoring.
- The checkpoint carried `wayId` only.
- The glance read "off the way" for the whole walk (§10.1), while the soft tap stayed silent by design (`guard !anchoredByFallback else { return }`, `HonorEngine.swift:263-268`).
- If no fix ever passed the engine's 50 m gate, `startFrac` stays nil: the companion never moves and arrival cannot fire (`evaluateArrival` guards `let start = startFrac`).

#### 13.3 An app kill mid-voice

- The voice dies with the process. Nothing records that it started, was heard, or was interrupted.
- Nothing is resumed at relaunch (§9.2). The last checkpoint (at most 10–60 s old, by tier) is saved as a finished walk with its Honor events; the Way is linked only if already listed, never with a delta.
- A reply in flight becomes a plain recording (orphan scan) or a metadata-only row with an empty path (unplayable); its mapping is lost.
- The Live Activity is ended at the next launch.
- There is therefore no iOS rule for "does the voice replay after revival". The plan's "no replayed voice" rule for Android revival is Android design; the closest iOS fact is that heard state is never persisted, so the only thing that prevents a replay is that iOS never revives.

#### 13.4 A second Honor walk right after the first

- A new view model per walk: the coordinator refuses a second walk while one exists (`guard activeWalkViewModel == nil`) and clears the first only after its save succeeds (`self.activeWalkViewModel = nil`, `MainCoordinatorView.swift:80,123`). Engine, compass, card queue, heard set, and gates are all new.
- The shared voice player carries over. Teardown stopped it and detached `onFinished`, and every late callback is generation-guarded, so a stale finish cannot touch the new walk (the test replays a first engine's `onFinished` into a second engine and asserts it is inert, `ActiveWalkHonorTests.swift:222-239`).
- The shared player's `playbackRate` also carries over while the new chip reads 1× (§6.3, defect).
- `WalkActivityManager.start` ends any existing activity first (`end()`, `WalkActivityManager.swift:45`) and resets `lastHonorGlance = nil`.
- Honoring the same own walk twice in a row: the first finish created `Ways/walk:<uuid>/`, so the second honoring's replies file correctly where the first one's were lost (§7.3), and its `save` overwrites `way.json`.
- Android's cached-`:tracker` rules (plan Institutional Learnings) are stricter than anything iOS needs, because iOS never reuses a session object.

#### 13.5 Other edges an implementer will meet

- A voice started, replayed, or failed counts in `voicesHeard` on the arrival card (it counts `heardVoiceIDs`); `placesPassed` counts reached non-voice moments only.
- The arrival card hides the place-card queue until "continue" sets `honorArrivalCardDismissed`; queued cards then show again.
- "your reply" or a replayed voice with the engine idle keeps playing through a sitting or a new recording, because the gates only pause the engine's own voice (§6.1, §6.5; defect).
- A failed-to-play voice's card sits above the next voice's card (§4.1).
- Tapping "reply here" while a plain take runs files that take as the reply (§7.1).
- With voices off, voice moments never surface a card and never mark heard; the walker can still play one from its pin's card.
- Overlapping 20 s caption timers are not cancelled (§4.2).
- A Way voice needs an engine: `togglePlayback` is a no-op before Start (`guard let player = wayVoicePlayer`), pinned by `testTogglePlaybackBeforeTheEngineStartsMarksNothingHeard` (`ActiveWalkHonorTests.swift:163-168`).

### Resolutions for the plan

1. **Honor walk events.** Exactly two. `HONOR_MODE` (raw 5, wire `"honorMode"`, display `honor.event.honor_mode` = "Honor"): once, inside `startRecording()`, right after the status flips to recording and the start date is stamped, payload type + `Date()` only (§2.1). `HONOR_ARRIVAL` (raw 6, wire `"honorArrival"`, display `honor.event.arrival` = "Way walked"): once, on the engine's `.arrived`, written before the reserved waypoint (`"signpost.right.fill"`, label `honor.arrival.label` = "Walked their way: %@" with the Way's title, at the current fix or the last route point, own `Date()`), then the in-memory card, then the haptic (§2.2). The arrival numbers are in neither; they reach disk only in the finish-time link. iOS writes no pause, resume, or meditation events, so nothing can precede `HONOR_MODE`; on Android it rides the Start effect inside `startWalk`'s mutex, like `SEEK_MODE` (`P/domain/WalkReducer.kt:61-70@5ea4029b`). Nothing else Honor-related is persisted during the walk.
2. **The card queue.** One ordered list, front renders, arrival card above it (§5). Enqueue: a reached non-voice moment appends (deduped by `contains`); an engine voice start and a pin tap move the moment to the front. No maximum. Dismiss: a tap removes the front and clears the map focus. Auto-retire: only voice cards, 20 s (`cardRetireSeconds`) after a natural end or failed play, only if untouched and not playing again, removed wherever they sit; skip, drop, replay, and `playReply` schedule no retire. No timeout for non-voice cards; no "next card" dismissal. Begin empties the queue; pause and meditation leave it alone (timers keep running on the wall clock); finish and discard empty it and void every pending timer via the generation bump (§5.3).
3. **Replies.** `replyHere(to:)` sets the origin and starts (or adopts) an ordinary walk recording; when the builder's recording list grows, the newest recording is filed as `replies.json[N] = relativePath` in `Ways/<wayId>/`, `N` from the origin's `voice-N` id (§7.1–7.2). Linked to its moment only by `N`; one reply per voice per Way. Before the Way folder exists: iOS files it nowhere — `setReply` needs the folder, which an own Way gets only at the end of its first honoring, so the write throws and `try?` drops it (§7.3, defect 1). Afterwards: the card re-resolves on the saved-recording count and offers "your reply" at once; later honorings find it through `existingReplyURL`, and a mapping whose file is gone reads as none (§7.4).
4. **The lifecycle.** Begin (two iOS steps, §3.1): overview Begin parks the Way; the walk screen's Start runs status → `HONOR_MODE` → engine (clear queue, bump generation, read prefs, player + `onFinished`, bind six inputs, event sink, compass, marks, pins) → soundscape → guide → Live Activity (§3.2). No uuid is minted. Pause (unreachable on iOS): closes the voice gate, keeps fixes and cards flowing, freezes the clock (§8.1). Meditation: stops a recording first, closes the gate (voice pauses, resumes after), sets the caption minutes, companion keeps moving (§8.2). Finish: teardown (keep arrival, heard set, reply origin), flush, snapshot, save, then own Way save + link with any arrival numbers (§8.3). Discard: drop the reply origin and partial file, teardown, delete the checkpoint, write nothing to the Ways store (§8.4).
5. **Checkpoint and recovery.** Fields `wayId` (every honor walk with a start date), `honorProgressFrac` + `honorArrived` (only once really joined, as a pair), schema v2, v1 still readable (§9.1). Written every 30/60/15/10 s by power tier. Recovery restores no Honor state: it saves the walk as finished, keeps its events, and links the Way only if already in the store, with no arrival numbers (§9.2). Everything else live is lost by design.
6. **The glance.** `HonorGlanceState { distanceRemainingBucketMeters (100 m floor, 0…2000), isOnWay, isArrived }`, computed from the engine each 1 s tick, pushed on any field change, 15 m walked, a flag flip, or 15 s (§10.1, §10.3). Strings, first match: "their way, walked" / "off the way" / "almost there" (< 100) / "2 km+ to go" or "1.2 mi+ to go" (≥ 2000) / "~X.X mi to go" / "~X.X km to go" / "~N m to go"; icon `signpost.right`; not localized; POSIX number format; shown in every walk state; units fixed at start; stale after 45 s (§10.2).
7. **Preferences.** `honorVoicesEnabled` (default true) and `soundsEnabled` (true) AND-ed, and `honorSoftTapEnabled` (false, no UI anywhere) — all three read once at Start and frozen. `voiceGuideVolume` (0.8; ambient × 0.5) read at every voice and reply; `voiceGuideDuckLevel` (0.15) by the player; units at glance start (§11).
8. **"Walk this again".** The coordinator builds the Way fresh from the source walk (`walk:<uuid>`, `.ownWalk(uuid)`), parks it until the summary closes, shows the overview, parks again on Begin, and creates the view model with `mode: .honor, way:` as constants (§12). A nil build silently does nothing. The Way is written to the store only at a clean finish, overwriting an earlier `way.json`.
9. **Edge cases.** Source deleted mid-walk: unreachable on iOS; if files vanish, voices are skipped at their spots unheard, and the captured Way is still saved and linked at finish (§13.1). Finished before anchoring: `HONOR_MODE` only, no outcome, yet the Way is saved and linked with nil numbers (§13.2). Kill mid-voice: nothing resumes; recovery saves and links-if-listed; heard state and the reply mapping are lost (§13.3). Second honor walk: fresh view model; the shared player's rate leaks; the same own Way's second honoring is the first whose replies file (§13.4).

Plan pins from U11's Approach that fall in this cluster:
- **The engine's location publisher and accuracy filter:** `$currentLocation` from the builder's current-location relay, which passes only fixes that clear the adaptive accuracy check while the walk is active (the first recorded sample passes regardless), replayed on subscribe so the engine starts from the last pre-Start raw fix; the engine then rejects accuracy < 0 or > 50 m (§3.3).
- **Timers: wall clock or fix time.** Wall clock (`Date()` via the injected `now`), evaluated only when a fix arrives. The companion clock is wall-clock time since start minus pauses, meditation included — not Android's `activeWalkingMillis` (§3.5).
- **"Sit?" while paused:** iOS has no reachable pause and no status guard; Android's reducer answer stands (§8.1).
- **Playback-rate lifetime:** per walk by intent, across walks by accident — a defect (§6.3, defect 2).
- **`playReply`:** stops the active voice, plays the reply through the same player at full voice-guide volume; the reply is neither the active voice nor heard; the gates pause it only if the engine still holds a voice; the engine gets its turn back at the reply's natural end (§6.5).
- **A place card whose voice fails to play:** a missing file raises no card and marks nothing heard; a file that fails to open or decode is marked heard, raises its card (as not playing), and retires 20 s later if untouched (§4.1).
- **Where replies are filed before the Way folder exists:** nowhere; lost (§7.3).

### iOS defects found

Candidate upstream issues (not filed). Plan R5 applies: Android matches iOS as shipped until an upstream fix lands, then folds it in.

1. **A reply on the first honoring of an own walk is silently lost.** `setReply` writes `replies.json` into `Ways/<wayId>/` without creating it, and an own Way's folder is first created by the finish-time `save`. The throw is swallowed.
   ```swift
        try encoder.encode(encodable).write(to: directory(for: wayId).appendingPathComponent("replies.json"), options: .atomic)
   ```
   > Pilgrim/Models/Honor/WayStore.swift:178@7c200bf
   ```swift
        try? honorSenses.store().setReply(wayId: way.id, originN: n, relativePath: latestRecording.fileRelativePath)
   ```
   > Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:40@7c200bf
   Impact: a walker who answers their own past walk the first time they walk it again never sees "your reply" — not on this walk's card, not on the next honoring, not in the summary. The recording remains, unlinked. All reply tests pre-save the Way, so the path is untested. Replies on shared Ways and stages, and on an own Way's second honoring onward, work.

2. **The Way voice rate leaks into the next walk while the chip reads 1×.** Teardown resets the view model's `voiceRate` but not the singleton player's `playbackRate`, which `start` applies to every new voice.
   ```swift
        voiceRate = 1
   ```
   > Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:152@7c200bf
   ```swift
            p.enableRate = true
            p.rate = playbackRate
   ```
   > Pilgrim/Models/Honor/WayVoicePlayer.swift:164-165@7c200bf
   Impact: after a walk at 2×, the next honor walk speaks at 2× under a chip showing 1×, and the first tap on the rate control jumps to 1.25× (the ladder restarts from the view model's 1). Both sides' comments say per walk.

3. **A failed reply recording leaves the origin armed, so the next unrelated take is filed as the reply.** An OS-failed recording (`successfully: false`) appends nothing, and only a new recording, a discard, or `replyHere`'s own open check clears `pendingReplyOrigin`.
   ```swift
        guard flag else {
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            let fileURL = docs.appendingPathComponent(relativePath)
            try? FileManager.default.removeItem(at: fileURL)
            return
        }
   ```
   > Pilgrim/Models/Walk/WalkBuilder/Components/VoiceRecordingManagement.swift:196-201@7c200bf
   Impact: a later plain voice note on the same walk overwrites (or creates) that voice's reply. Low frequency (encoder or route failure).

4. **Own-walk reply keys shift when a source recording is deleted.** Replies are keyed by the positional `N` of `voice-N`, and `N` is recomputed at each honoring over the recordings whose files still exist.
   ```swift
        for (n, rec) in present.enumerated() {
            let start = place(rec.startDate)
            let end = place(rec.endDate)
            moments.append(WayMoment(
                id: "voice-\(n + 1)", frac: start.frac, at: start.at,
   ```
   > Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:46-50@7c200bf
   Impact: delete one recording from the source walk, walk it again, and every later voice shows the reply the walker gave to its neighbour; replaying it plays the wrong answer.

5. **A replayed voice or "your reply" is not paused by a sitting or a recording when the engine holds no voice.** The gates pause only the engine's own voice; manual plays bypass the engine.
   ```swift
    mutating func gatesDidChange(_ gates: Gates) -> [Action] {
        if playing != nil {
   ...
        return startNextIfPossible(gates: gates)
   ```
   > Pilgrim/Models/Honor/HonorMomentTracker.swift:96-97,108@7c200bf
   Impact: tapping "your reply" and then "Sit?" plays the reply over the meditation; tapping "reply here" while a replayed voice plays records it through the speaker into the answer. Whether the recording session change silences playback on a device is unverified.

6. **The lock screen says "off the way" while the walker is still approaching the trailhead.** The engine deliberately keeps the soft tap quiet during Begin's fallback ("the wrong word at the wrong time"), but the glance reads `isOnWay`, which is false from Start until the first fix within 60 m.
   ```swift
        // Nothing has been joined yet: Begin found no Way within 60 m and
        // fell back to frac 0, so the walker is still approaching. Tapping
        // them on the shoulder for being "off the way" they have not
        // started is the wrong word at the wrong time.
        guard !anchoredByFallback else { return }
   ```
   > Pilgrim/Models/Honor/HonorEngine.swift:264-268@7c200bf
   ```swift
                         : "off the way")
   ```
   > PilgrimWidget/PilgrimWidgetLiveActivity.swift:195@7c200bf
   Impact: every honor walk begun away from the Way (and every walk's first seconds before a fix) shows "off the way" on the lock screen, contradicting the app's own rule. Candidate, not certain: iOS may intend the glance to be literal.

Already on the plan's upstream list and confirmed here: recovery never links a first honoring of an own walk (§9.2); an arrived stage reads "their way, walked" (§10.2, stage branch).

### Open questions

1. **`honorSoftTapEnabled` has no writer.** Nothing in the app sets it; the soft tap is dark in production. iOS does not say whether this is a parked feature or a missing Settings row. Android should match (default false, no UI) unless the owner decides otherwise.
2. **When the companion clock subtracts a pause.** iOS's Honor code handles pause fully, but no pause is reachable at the pin, so there is no shipped behavior for when the companion clock subtracts a pause (iOS subtracts it only at resume, §3.5). Android's reachable pause needs its own rule; the plan's "companion freezes while paused" is the natural reading and is not contradicted by anything iOS ships.
## E. On the walk: map layers, place cards, captions

iOS pin: `pilgrim-ios` @ `7c200bf`. Android HEAD cited: `5ea4029b`. Scope: the own-walk slice of the on-walk Honor UI (feeds U22). Stage and shared-walk specifics appear only as named branch points (section 15).

Files read end to end at the pin: `Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift`, `ActiveWalkView.swift`, `ActiveWalkView+Map.swift`, `WayPlaceCard.swift`, `WalkStatsSheet.swift`, `MeditationView.swift` (Honor parts), `Pilgrim/Views/PilgrimMapView+HonorWay.swift`, `PilgrimMapView.swift`, `PilgrimMapView+RouteSource.swift`, `PilgrimMapView+CameraReport.swift`, `Pilgrim/Models/Walk/MapManagement/PilgrimAnnotation.swift`, `Pilgrim/Views/MapGlyphImageBuilder.swift`, `Pilgrim/Scenes/Honor/WayMomentHeader.swift`, `Pilgrim/Scenes/Honor/WayPhotoViewer.swift`, `Pilgrim/Models/Honor/HeadingProvider.swift`. Read for the inputs these views consume: `ActiveWalkViewModel+Honor.swift`, `ActiveWalkViewModel+Replies.swift`, `ActiveWalkViewModel+MarkPins.swift`, the Honor block of `ActiveWalkViewModel.swift`, `HonorEngine.swift` (companion clock), `WayVoicePlayer.swift` (elapsed clock, failure path, rate), `WayGeometry.swift`, `Way.swift`, `HonorPersistence.swift`, `HonorTuning.swift`, `WaveformBarView` (in `RecordingsListView.swift`), the colour assets, and `Constants.swift`.

`Pilgrim/Views/WalkModeFootprints.swift` does draw Honor (a `StaffGlyph`), but only on the journal's ink-scroll view, not on the walk. It belongs to U23's spec.

Token reference used below (all at the pin):

```swift
        public enum Padding {
            public static let xs: CGFloat = 4
            public static let small: CGFloat = 8
            public static let normal: CGFloat = 16
            public static let big: CGFloat = 24
            public static let breathingRoom: CGFloat = 64
        ...
        public enum CornerRadius {
            public static let small: CGFloat = 8
            public static let normal: CGFloat = 12
            public static let big: CGFloat = 20
```
> Pilgrim/Models/Constants.swift:9-20@7c200bf

```swift
    public enum Typography {
        public static let displayLarge: Font = .custom("CormorantGaramond-Light", size: 34)
        public static let displayMedium: Font = .custom("CormorantGaramond-Light", size: 28)
        public static let heading: Font = .custom("CormorantGaramond-SemiBold", size: 17)
        public static let timer: Font = .custom("Lato-Regular", size: 48)
        public static let statValue: Font = .custom("Lato-Regular", size: 20)
        public static let statLabel: Font = .custom("Lato-Regular", size: 12)
        public static let body: Font = .custom("CormorantGaramond-Regular", size: 17)
        public static let button: Font = .custom("Lato-Bold", size: 17)
        public static let caption: Font = .custom("Lato-Regular", size: 12)
```
> Pilgrim/Models/Constants.swift:60-69@7c200bf

Colour assets (sRGB components from `Pilgrim/Support Files/Assets.xcassets/<name>.colorset/Contents.json@7c200bf`, converted to hex): stone `#8B7355` light / `#B8976E` dark; fog `#8A8175` / `#948E88`; ink `#2C2416` / `#F0EBE1`; parchment `#F5F0E8` / `#1C1914`; parchmentSecondary `#EDE6D8` / `#262118`; dawn `#C4956A` / `#D4A87A`; rust `#A0634B` / `#C47E63`; moss `#7A8B6F` / `#95A888`. Android's palette matches all sixteen values (`ui/theme/Color.kt:22-50@5ea4029b`); Android's `pilgrimType` and `PilgrimSpacing` / `PilgrimCornerRadius` carry the same token names (`ui/theme/Type.kt:17-25@5ea4029b`, `ui/theme/Tokens.kt:9-20@5ea4029b`).

No Honor on-walk string goes through `Localizable.strings`. Every string below is a Swift literal (a `Text("…")` literal is an implicit `LocalizedStringKey` with no entry in `Base.lproj`); `Text(variable)` strings are verbatim. The only `NSLocalizedString` keys this cluster touches are the persistence names in section 4.

### 1. Where the Honor UI sits on the walk screen

The walk screen is one `ZStack(alignment: .bottom)`. Declaration order is draw order, back to front: map, weather overlay, top buttons, greetings, ambient overlay, turning watermark, the Honor card layer, then the stats sheet. The card layer is above the ambient overlay and below the sheet.

```swift
        ZStack(alignment: .bottom) {
            // Full-screen map background (ignores safe area to fill entire screen)
            mapSection()
                .ignoresSafeArea()
            ...
            turningWatermark
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, minimizedSheetHeight + 16)
                .allowsHitTesting(viewModel.status.isActiveStatus && sheetState == .minimized)

            // "Sit?" must open the same MeditationView the bottom sheet's own
            // start-meditation path does — the card can start the engine's
            // meditation but only this view can present the UI that clears it.
            honorCardLayer(bottomInset: mapBottomInset) { minutes in
                viewModel.startMeditation(minutes: minutes)
                showMeditation = true
            }

            // Bottom sheet with stats and controls
            bottomSheet
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:113-168@7c200bf

The card's bottom edge follows the measured sheet height in either sheet state, so the card sits directly above the sheet, 8 pt clear of it. The layer exists only on an honor walk that has begun, and it takes touches only while it shows a card.

```swift
    var mapBottomInset: CGFloat {
        sheetState == .minimized ? measuredMinimizedSheetHeight : measuredExpandedSheetHeight
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:72-74@7c200bf

```swift
    @State private var measuredMinimizedSheetHeight: CGFloat = 90
    ...
    @State private var measuredExpandedSheetHeight: CGFloat = 340
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:53,60@7c200bf

```swift
    func honorCardLayer(bottomInset: CGFloat, onSit: @escaping (Int) -> Void) -> some View {
        // Gated on the walk being active, like the ambient overlay and the
        // turning watermark: a pin tap before Begin must not open a card
        // whose play button would mark a voice heard with no player behind it.
        if viewModel.mode == .honor && viewModel.status.isActiveStatus {
            HonorCardHost(viewModel: viewModel, onSit: onSit)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, bottomInset + Constants.UI.Padding.small)
                // The layer spans the screen; with no card on it, every touch
                // belongs to the map underneath.
                .allowsHitTesting(viewModel.isShowingHonorCard)
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:9-21@7c200bf

```swift
    var isShowingHonorCard: Bool {
        (honorArrival != nil && !honorArrivalCardDismissed) || !honorCards.isEmpty
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:267-269@7c200bf

The host pads its card 16 pt from each screen edge:

```swift
        .padding(.horizontal, Constants.UI.Padding.normal)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:110@7c200bf

The Honor inputs to the map come from the view model. The ghost line and the pins exist from the moment the walk screen appears, before Begin. The engine (and so the companion and the compass) starts at Begin.

```swift
        // The Way is drawn from the moment the walk screen appears, not from
        // the moment the engine starts at Begin.
        if mode == .honor { refreshHonorPins() }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:241-243@7c200bf

```swift
        writeHonorMarkerEventIfNeeded()
        startHonorEngineIfNeeded()
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:384-385@7c200bf

Android counterpart: `ActiveWalkScreen.kt:749-800@5ea4029b` composes `PilgrimMap` inside a full-size `Box`, and `WalkStatsSheet` at `ActiveWalkScreen.kt:1119@5ea4029b`. The sheet inset reaches the map as `bottomInsetDp = sheetInsetDp` (`ActiveWalkScreen.kt:778@5ea4029b`). The card layer slots between them, keyed to the same inset.

### 2. The ghost line

**Source geometry.** The ghost is the Way's own `route`, cut into pieces at every span boundary. Each piece carries an `activityType` of `"walking"`, `"talking"`, or `"meditating"`, the same strings the walker's route source uses. Gaps between spans are walking. A span never reaches back over one already drawn (a forward cursor). Any span kind other than `.meditating` draws as `"talking"`.

```swift
    init(way: Way) {
        self.init(
            id: way.id,
            routeCoordinates: way.route.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) },
            segments: PilgrimMapView.HonorWayRendering.segments(route: way.route, spans: way.spans ?? []))
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:28-33@7c200bf

```swift
        static func segments(route: [WayPoint], spans: [WaySpan]) -> [HonorWayState.Segment] {
            let geometry = WayGeometry(route: route)
            guard route.count > 1, geometry.totalMeters > 0 else {
                return [HonorWayState.Segment(kind: "walking", coordinates: route.map {
                    CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
                })]
            }
            var pieces: [HonorWayState.Segment] = []
            var cursor = 0.0
            func add(_ kind: String, from start: Double, to end: Double) {
                guard end > start else { return }
                pieces.append(HonorWayState.Segment(kind: kind, coordinates: geometry.slice(fromFrac: start, toFrac: end)))
            }
            for span in spans.sorted(by: { $0.startFrac < $1.startFrac }) {
                let start = max(min(max(span.startFrac, 0), 1), cursor)
                let end = min(max(span.endFrac, 0), 1)
                guard end > start else { continue }
                add("walking", from: cursor, to: start)
                add(span.kind == .meditating ? "meditating" : "talking", from: start, to: end)
                cursor = end
            }
            add("walking", from: cursor, to: 1)
            return pieces
        }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:94-117@7c200bf

`WaySpanKind` has only two cases, so "any other" means `.talking`:

```swift
enum WaySpanKind: String, Codable {
    case meditating, talking
```
> Pilgrim/Models/Honor/Way.swift:177-178@7c200bf

`slice` interpolates a point at each end and includes every route point strictly inside, so consecutive slices share their boundary coordinate and draw as one line:

```swift
    func slice(fromFrac start: Double, toFrac end: Double) -> [CLLocationCoordinate2D] {
        guard points.count > 1, totalMeters > 0 else {
            return points.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        }
        let a = min(max(min(start, end), 0), 1) * totalMeters
        let b = min(max(max(start, end), 0), 1) * totalMeters
        var coords = [coordinate(atFrac: a / totalMeters)]
        for (index, point) in points.enumerated() where cumulative[index] > a && cumulative[index] < b {
            coords.append(CLLocationCoordinate2D(latitude: point.lat, longitude: point.lon))
        }
        coords.append(coordinate(atFrac: b / totalMeters))
        return coords
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:48-60@7c200bf

The state is built once per Way and never rebuilt; equality is by id, point count, and segment kinds and counts:

```swift
    func refreshHonorPins() {
        guard let way else { return }
        if honorWayState == nil {
            honorWayState = HonorWayState(way: way)
        }
        honorPins = PilgrimMapView.wayPins(for: way, heardVoiceIDs: heardVoiceIDs)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:104-110@7c200bf

```swift
    static func == (lhs: HonorWayState, rhs: HonorWayState) -> Bool {
        lhs.id == rhs.id && lhs.routeCoordinates.count == rhs.routeCoordinates.count && lhs.segments == rhs.segments
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:39-41@7c200bf

**Layer type, ids, width, cap, join.** One `GeoJSONSource` with one `LineString` feature per segment (ids `honor-way-<index>`), drawn by one `LineLayer`. Width 4, round cap, round join. No dash: iOS sets no `lineDasharray`.

```swift
        static let sourceID = "honor-way-source"
        static let lineLayerID = "honor-way-line"
        static let companionSourceID = "honor-companion-source"
        static let companionLayerID = "honor-companion"
        static let lineWidth = 4.0
        static let companionRadius = 6.0
        static let companionUpdateInterval: TimeInterval = 2
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:61-67@7c200bf

```swift
            var source = GeoJSONSource(id: HonorWayRendering.sourceID)
            source.data = .featureCollection(FeatureCollection(features: way.segments.enumerated().map { index, segment in
                var feature = Feature(geometry: .lineString(LineString(segment.coordinates)))
                feature.identifier = .string("honor-way-\(index)")
                feature.properties = ["activityType": .string(segment.kind)]
                return feature
            }))
            try mapView.mapboxMap.addSource(source)
            var layer = LineLayer(id: HonorWayRendering.lineLayerID, source: HonorWayRendering.sourceID)
            layer.lineWidth = .constant(HonorWayRendering.lineWidth)
            layer.lineCap = .constant(.round)
            layer.lineJoin = .constant(.round)
            let style = HonorWayRendering.ghostStyle(for: mapView)
            layer.lineOpacity = .constant(style.lineOpacity)
            // The walk's own palette (see PilgrimMapView+RouteSource), faded
            // by the opacity above so it reads as someone else's trace.
            layer.lineColor = .expression(
                Exp(.match) {
                    Exp(.get) { "activityType" }
                    "meditating"
                    UIColor.dawn
                    "talking"
                    UIColor.rust
                    UIColor.moss
                }
            )
            try mapView.mapboxMap.addLayer(layer, layerPosition: ghostLinePosition(on: mapView))
            renderer.appliedWayID = way.id
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:179-206@7c200bf

**Colour.** The line's colour is the walk palette by span: meditating → `UIColor.dawn`, talking → `UIColor.rust`, anything else → `UIColor.moss`. It does not use the turning-day walking colour the live route uses (the live route's fallback is its `walkingColor` parameter, `PilgrimMapView+RouteSource.swift:141@7c200bf`; the ghost's is always `UIColor.moss`). These three are the same `UIColor` statics the route layer passes into its own `Exp(.match)`, so they resolve the same way the route's do. Android already fixed the route's three to the light values (`RouteSegmentColors.Fixed`, `ui/walk/summary/RevealAnimation.kt:104-118@5ea4029b`); the ghost's three should follow the route's decision.

**Opacity, light and dark.** Only the opacity changes with the map style: 0.22 on the light style, 0.4 on the dark. The style's `color` field is used only by the companion (section 3), not by the line.

```swift
        /// Map layers take fixed colors, so the ghost is chosen per map style
        /// at install time: stone on parchment reads as a faded trace, but the
        /// same stone at 0.35 blends into the dark style's ink ground and
        /// vanishes. An appearance flip reloads the style, which reinstalls
        /// the layers with the other palette.
        static func ghostStyle(dark: Bool) -> GhostStyle {
            dark
                ? GhostStyle(color: UIColor(hex: "#D9CFBF"), lineOpacity: 0.4, companionOpacity: 0.85)
                : GhostStyle(color: UIColor(hex: "#8A8175"), lineOpacity: 0.22, companionOpacity: 0.6)
        }

        static func ghostStyle(for mapView: MBMapView) -> GhostStyle {
            ghostStyle(dark: mapView.traitCollection.userInterfaceStyle == .dark)
        }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:75-88@7c200bf

(The doc comment's "stone" wording predates the palette expression; the line no longer uses the style colour.)

**Z-order.** The ghost goes below the route casing if the casing exists, else below the route line, else on top of the stack. The walker's route layers are `pilgrim-route-casing` (white, width 10, opacity 0.3) and `pilgrim-route-layer` (width 6, opacity 1), created casing first and both at the top of the stack. Android names the route line `pilgrim-route-line` (`ui/walk/PilgrimMap.kt:1734@5ea4029b`) and the casing `pilgrim-route-casing` (`PilgrimMap.kt:1744@5ea4029b`).

```swift
    /// Ghost line sits under the casing (and, transitively, the colored
    /// route line on top of it) so the walker's own live route stays the
    /// legible one — same fallback chain as seek fog's `fogLayerPosition`.
    /// Casing and route layer are always torn down and recreated together
    /// (see `PilgrimMapView+RouteSource.swift`), both landing with no
    /// explicit position — i.e. at the top of the stack — so the ghost line
    /// stays below both even after a walking-color change rebuilds them.
    private static func ghostLinePosition(on mapView: MBMapView) -> LayerPosition? {
        if mapView.mapboxMap.layerExists(withId: "pilgrim-route-casing") {
            return .below("pilgrim-route-casing")
        }
        if mapView.mapboxMap.layerExists(withId: "pilgrim-route-layer") {
            return .below("pilgrim-route-layer")
        }
        return nil
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:212-227@7c200bf

```swift
            var casing = LineLayer(id: "pilgrim-route-casing", source: Self.routeSourceId)
            casing.lineWidth = .constant(10)
            casing.lineCap = .constant(.round)
            casing.lineJoin = .constant(.round)
            casing.lineOpacity = .constant(0.3)
            casing.lineColor = .constant(StyleColor(.white))
            try mapView.mapboxMap.addLayer(casing)

            var layer = LineLayer(id: "pilgrim-route-layer", source: Self.routeSourceId)
            layer.lineWidth = .constant(6)
```
> Pilgrim/Views/PilgrimMapView+RouteSource.swift:121-130@7c200bf

**When it is drawn and hidden.**
- Drawn from the moment the walk screen appears on an honor walk (section 1), before Begin, through the walk, and after arrival.
- Nothing clears `honorWayState` at teardown: `teardownHonor()` (`ActiveWalkViewModel+Honor.swift:128-158@7c200bf`) resets cards, pins, focus, and heading but never `honorWayState` or `honorPins`, so the ghost and the pins stay until the walk screen goes away.
- Removed when the map is handed `nil` (every non-honor map). The nil path skips style calls when nothing was installed.
- Not touched while the map is not rendering (app backgrounded, or meditating): the apply returns early and keeps the state pending, then reinstalls when rendering resumes (section 5).
- Installed once per Way id; later passes are no-ops unless the self-heal probe finds the layer gone.

```swift
    static func applyHonorWay(
        _ way: HonorWayState?,
        companion: CLLocationCoordinate2D?,
        on mapView: MBMapView,
        coordinator: Coordinator
    ) {
        let renderer = coordinator.honorWayRenderer
        renderer.pendingWay = way
        renderer.pendingCompanion = companion
        guard coordinator.shouldRender, coordinator.styleHasLoaded || mapView.mapboxMap.isStyleLoaded else { return }
        applyGhostLine(way, on: mapView, renderer: renderer)
        applyCompanion(companion, on: mapView, renderer: renderer)
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:140-152@7c200bf

```swift
    private static func applyGhostLine(_ way: HonorWayState?, on mapView: MBMapView, renderer: HonorWayRenderer) {
        // Self-heal: a lock/unlock can strip runtime layers without a style event.
        if renderer.appliedWayID != nil, !mapView.mapboxMap.layerExists(withId: HonorWayRendering.lineLayerID) {
            renderer.appliedWayID = nil
        }
        guard let way else {
            // Nothing installed: non-honor maps (summary, journal, wander
            // walks) hit this branch on every updateUIView pass at up to
            // 20 Hz, so skip the remove call rather than issuing throwing
            // style calls for layers that were never added.
            guard renderer.appliedWayID != nil else { return }
            removeGhostLine(from: mapView)
            renderer.appliedWayID = nil
            return
        }
        guard renderer.appliedWayID != way.id else { return }
        removeGhostLine(from: mapView)
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:161-177@7c200bf

```swift
        var shouldRender: Bool {
            !isAppInBackground && !isMeditating
        }
```
> Pilgrim/Views/PilgrimMapView.swift:696-698@7c200bf

Install and removal failures are printed and swallowed; nothing retries except the next apply pass:

```swift
        } catch {
            print("[PilgrimMapView] honor way install failed: \(error)")
        }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:207-209@7c200bf

Android counterpart pattern: `ui/walk/map/SeekFogRenderer.kt:76-83@5ea4029b` (a fakeable `SeekFogStyle` with an existence probe) and `SeekFogRenderer.kt:115-168@5ea4029b` (`resetForStyleReload`, `onStyleReloaded`, `flushDeferred`), wired from `PilgrimMap.kt:685@5ea4029b`.

### 3. The companion

**What it is.** A dot on the Way standing where the original walker was at the same walking time: the Way's recorded clock (`WayPoint.t`, wall clock, "the original walker's pauses are inside it, so the companion rests where they rested"), advanced by the honoring walker's active time since the Way was anchored.

```swift
    /// Seconds since departure. Wall clock: the original walker's pauses
    /// are inside it, so the companion rests where they rested.
    let t: Double
```
> Pilgrim/Models/Honor/Way.swift:12-14@7c200bf

**Position.** The engine publishes `companionFrac`; the view model maps it to a coordinate on every read. Before Begin there is no engine, so no companion. A stage has no companion.

```swift
    var companionCoordinate: CLLocationCoordinate2D? {
        guard way?.isPilgrimageStage != true else { return nil }
        return honorEngine.map { $0.geometry.coordinate(atFrac: $0.companionFrac) }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:117-120@7c200bf

```swift
    func updateActiveDuration(_ seconds: TimeInterval) {
        activeDuration = seconds
        // While the fallback anchor (Begin found no Way within 60 m) is
        // still in effect, the companion has nowhere real to walk to yet —
        // it waits at the start until the first on-Way fix re-anchors it.
        guard startFrac != nil, !anchoredByFallback else { return }
        companionFrac = geometry.frac(atElapsed: companionT0 + sinceAnchorSeconds)
    }

    /// Walking time since the Way was anchored. Never negative: the walk's
    /// active duration is monotonic, but a re-anchor could otherwise race a
    /// stale emission.
    private var sinceAnchorSeconds: TimeInterval { max(0, activeDuration - anchorActiveDuration) }
```
> Pilgrim/Models/Honor/HonorEngine.swift:124-136@7c200bf

```swift
    private func anchor(at coordinate: CLLocationCoordinate2D) {
        let hit = geometry.lowestFrac(within: HonorTuning.onWayMeters, of: coordinate)?.frac
        anchoredByFallback = hit == nil
        let frac = hit ?? 0
        startFrac = frac
        progressFrac = frac
        progressHighWater = frac
        walkedFrac = 0
        anchorActiveDuration = activeDuration
        companionT0 = geometry.elapsed(atFrac: frac)
        companionFrac = geometry.frac(atElapsed: companionT0)
    }
```
> Pilgrim/Models/Honor/HonorEngine.swift:166-177@7c200bf

So, in order:
- From Begin until the first accepted fix, `companionFrac` is its initial `0` (`HonorEngine.swift:31@7c200bf`), so the dot stands at the Way's first point.
- At the first fix it jumps to the anchor frac (the lowest frac within 60 m of the walker), or stays at frac 0 on the fallback until an on-Way fix re-anchors.
- After that it moves along the Way's recorded timing, driven by the walk's active duration.

**The clock it rides.** Active duration is wall time since start minus pauses. Meditation time is inside it; paused time is not. So the companion's position stops during a pause and keeps advancing during a sitting and while the app is in the background.

```swift
                let pauseDuration = pauseList.map { $0.duration }.reduce(0, +)
                let activeDuration = max(0, start.distance(to: Date()) - pauseDuration)
                self.activeDurationSeconds = activeDuration
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:544-546@7c200bf

That tick is a 1 s main-run-loop timer (`Timer.TimerPublisher(interval: 1, runLoop: .main, mode: .common)`, `ActiveWalkViewModel.swift:535@7c200bf`).

**How it is drawn and animated.** A `CircleLayer` on its own `GeoJSONSource`: radius 6, white stroke 1.5, pitch alignment `.map`. Light style: fill `#8A8175` at 0.6. Dark style: fill `#D9CFBF` at 0.85 (section 2's `ghostStyle`). Stroke opacity is the SDK default (iOS sets none). Placed directly above `pilgrim-route-layer` when that layer exists, else on top of the stack.

```swift
        removeCompanion(from: mapView)
        do {
            var source = GeoJSONSource(id: HonorWayRendering.companionSourceID)
            source.data = .feature(feature)
            try mapView.mapboxMap.addSource(source)
            var layer = CircleLayer(id: HonorWayRendering.companionLayerID, source: HonorWayRendering.companionSourceID)
            layer.circleRadius = .constant(HonorWayRendering.companionRadius)
            let style = HonorWayRendering.ghostStyle(for: mapView)
            layer.circleColor = .constant(StyleColor(style.color))
            layer.circleOpacity = .constant(style.companionOpacity)
            layer.circleStrokeColor = .constant(StyleColor(.white))
            layer.circleStrokeWidth = .constant(1.5)
            layer.circlePitchAlignment = .constant(.map)
            let position: LayerPosition? = mapView.mapboxMap.layerExists(withId: "pilgrim-route-layer")
                ? .above("pilgrim-route-layer") : nil
            try mapView.mapboxMap.addLayer(layer, layerPosition: position)
            renderer.companionInstalled = true
            renderer.lastCompanionUpdate = CACurrentMediaTime()
        } catch {
            print("[PilgrimMapView] companion install failed: \(error)")
        }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:246-266@7c200bf

Movement is a jump, not an animation: iOS rewrites the source's point at most once every 2 s (`companionUpdateInterval`), measured on `CACurrentMediaTime()`. No transition is set on the layer. The read happens on every map update pass, which runs at least once a second because of the duration tick, so in practice the dot jumps every 2 to 3 s.

```swift
        let feature = Feature(geometry: .point(Point(companion)))
        if renderer.companionInstalled, mapView.mapboxMap.layerExists(withId: HonorWayRendering.companionLayerID) {
            let now = CACurrentMediaTime()
            guard now - renderer.lastCompanionUpdate >= HonorWayRendering.companionUpdateInterval else { return }
            renderer.lastCompanionUpdate = now
            mapView.mapboxMap.updateGeoJSONSource(withId: HonorWayRendering.companionSourceID, geoJSON: .feature(feature))
            return
        }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:238-245@7c200bf

**When it is shown.**
- Shown on an own or shared honor walk from Begin until teardown (`honorEngine` exists). Hidden before Begin, on a stage, and after `teardownHonor()` sets `honorEngine = nil` (`ActiveWalkViewModel+Honor.swift:137@7c200bf`); a nil companion removes the layer:

```swift
        guard let companion else {
            // Nothing installed: same non-honor-map reasoning as the ghost
            // line's nil branch above — skip the remove call entirely.
            guard renderer.companionInstalled else { return }
            removeCompanion(from: mapView)
            renderer.companionInstalled = false
            return
        }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:230-237@7c200bf

- Not redrawn while the map is not rendering (background, or meditating). The engine's position keeps advancing through a sitting; the dot reappears at its current place when rendering resumes, because the resume path clears `companionInstalled` and reinstalls without the throttle (section 5).
- It keeps walking after arrival: nothing in `updateActiveDuration` checks the phase, and `frac(atElapsed:)` clamps at 1 once the Way's time runs out (`WayGeometry.swift:78@7c200bf`).

Android: the plan's rule "moving at most every 2 s, frozen while paused, backgrounded, or meditating" (plan U22) matches the drawn dot for pause, background, and meditation. It does not match the position: the position is frozen only by a pause. After a sitting or a return from the background, the dot must reappear where the active clock puts it, not where it stopped.

### 4. Way pins

**Kinds.** Five moment kinds, plus a stage-only service mark. Each moment kind carries its moment id; only these five are Way-card pins.

```swift
        /// Moments of a Way being honored: faded versions of the walker's own marks.
        case wayVoice(id: String, heard: Bool)
        case wayPhoto(id: String)
        case wayRest(id: String, minutes: Int)
        case waySit(id: String, minutes: Int)
        case wayWaypoint(id: String, label: String, icon: String)
        /// A service point on a pilgrimage stage. Drawn under the moment
        /// pins, never tappable, hidden when the map is zoomed out.
        case wayMark(id: String, kind: WayMarkKind)
```
> Pilgrim/Models/Walk/MapManagement/PilgrimAnnotation.swift:23-31@7c200bf

```swift
    var wayMomentID: String? {
        switch self {
        case .wayVoice(let id, _), .wayPhoto(let id), .wayRest(let id, _),
             .waySit(let id, _), .wayWaypoint(let id, _, _):
            return id
        default:
            return nil
        }
    }
```
> Pilgrim/Models/Walk/MapManagement/PilgrimAnnotation.swift:41-49@7c200bf

There is no separate "active", "unheard-but-reached", "reply", or "arrival" Way pin. A voice pin has exactly two looks: heard and not heard. The pin of the voice now playing looks heard (it is marked heard when it starts). A reply leaves no pin. Arrival adds an ordinary live waypoint (below), not a Way pin.

**Where each pin stands.** At the moment's `pin` if it has one (stage waypoints only), else its `at`, else the route point at its `frac`:

```swift
    static func wayPins(for way: Way, heardVoiceIDs: Set<String>) -> [PilgrimAnnotation] {
        let geometry = WayGeometry(route: way.route)
        return way.moments.map { moment in
            // `pin` is the place itself; `at` is its projection onto the
            // line, which is where the engine's 60 m trigger fires. The pin
            // must stand where the place does.
            let coordinate = (moment.pin ?? moment.at).map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                ?? geometry.coordinate(atFrac: moment.frac)
            let kind: PilgrimAnnotation.Kind
            switch moment.kind {
            case .voice: kind = .wayVoice(id: moment.id, heard: heardVoiceIDs.contains(moment.id))
            case .photo: kind = .wayPhoto(id: moment.id)
            case .rest(let minutes): kind = .wayRest(id: moment.id, minutes: minutes)
            case .meditation(let minutes, _): kind = .waySit(id: moment.id, minutes: minutes)
            case .waypoint(let label, let icon): kind = .wayWaypoint(id: moment.id, label: label, icon: icon)
            }
            return PilgrimAnnotation(coordinate: coordinate, kind: kind)
        }
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:120-138@7c200bf

One pin per moment, in `way.moments` order. Every moment gets a pin, reached or not.

**Glyph, tint, size per kind.**

| Kind | SF Symbol | Tint | Size (pt) |
|---|---|---|---|
| `wayVoice`, not heard | `waveform` (also for ambient voices; the card header uses `wind` for those) | fog | 22 |
| `wayVoice`, heard | `waveform` | stone | 22 |
| `wayPhoto` | `photo` | stone | 22 |
| `wayRest` | `cup.and.saucer` | stone | 22 |
| `waySit` | `circle.circle` | dawn | 22 |
| `wayWaypoint` | the waypoint's own icon (unknown names draw `mappin`) | stone | 22 |
| `wayMark` (stage only) | `WayMarkPins.symbol(for:)` | stone | 18 |

```swift
    static func wayAnnotationPoint(for pin: PilgrimAnnotation, coordinator: Coordinator) -> PointAnnotation {
        switch pin.kind {
        case .wayVoice(_, let heard):
            return wayPoint(pin, symbol: "waveform", tint: heard ? .stone : .fog, coordinator: coordinator)
        case .wayPhoto:
            return wayPoint(pin, symbol: "photo", tint: .stone, coordinator: coordinator)
        case .wayRest:
            return wayPoint(pin, symbol: "cup.and.saucer", tint: .stone, coordinator: coordinator)
        case .waySit:
            return wayPoint(pin, symbol: "circle.circle", tint: .dawn, coordinator: coordinator)
        case .wayWaypoint(_, _, let icon):
            return wayPoint(pin, symbol: icon, tint: .stone, coordinator: coordinator)
        case .wayMark(_, let kind):
            return wayPoint(pin, symbol: WayMarkPins.symbol(for: kind), tint: .stone,
                            coordinator: coordinator, size: 18)
        default:
            // Unreachable: buildPoints only routes way* kinds here. A plain
            // PointAnnotation at the pin's coordinate is a harmless fallback
            // if that ever changes, rather than a crash.
            return PointAnnotation(coordinate: pin.coordinate)
        }
    }

    /// Faded pin for a Way moment or a service mark, sharing `buildPoints`'
    /// image-caching pattern. Marks draw smaller: they are the map's
    /// background, not its subject.
    private static func wayPoint(_ pin: PilgrimAnnotation, symbol: String, tint: UIColor,
                                 coordinator: Coordinator, size: CGFloat = 22) -> PointAnnotation {
        var point = PointAnnotation(coordinate: pin.coordinate)
        let glyph = MapGlyph.wayMark(symbol: symbol, tint: tint)
        if let image = MapGlyphImageBuilder.image(for: glyph, size: size) {
            point.image = .init(image: image, name: "\(MapGlyphImageBuilder.cacheKey(for: glyph))-\(Int(size))")
        }
        point.iconSize = 1.0
        return point
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:299-334@7c200bf

**The raster.** A light-parchment disc at 0.9 alpha filling the square, with the symbol centered at `size × 0.55` point size, weight medium, drawn at 0.55 alpha. Both the disc and the tint resolve against the LIGHT palette in both appearances, so a dark map still shows a light disc. With the light palette: disc `#F5F0E8` at 0.9; symbol stone `#8B7355`, fog `#8A8175`, or dawn `#C4956A`, each at 0.55.

```swift
    /// Way marks are rasterized against the light palette in both
    /// appearances: on the dark map style a light parchment disc still reads
    /// as a pin, whereas the dark parchment is the ground's own color. It
    /// also keeps the cache key stable across an appearance flip.
    private static let lightTraits = UITraitCollection(userInterfaceStyle: .light)

    /// An SF Symbol name a shared Way supplied is not validated at write
    /// time; anything the system doesn't know falls back to "mappin" rather
    /// than rendering an invisible but still-tappable pin.
    private static func resolvedWayMarkSymbol(_ symbol: String) -> String {
        if let resolved = resolvedSymbols[symbol] { return resolved }
        let resolved = UIImage(systemName: symbol) == nil ? "mappin" : symbol
        resolvedSymbols[symbol] = resolved
        return resolved
    }
```
> Pilgrim/Views/MapGlyphImageBuilder.swift:85-99@7c200bf

```swift
    private static func renderedWayMark(symbol: String, tint: UIColor, size: CGFloat) -> UIImage? {
        let resolvedSymbol = resolvedWayMarkSymbol(symbol)
        let config = UIImage.SymbolConfiguration(pointSize: size * 0.55, weight: .medium)
        guard let symbolImage = UIImage(systemName: resolvedSymbol, withConfiguration: config)?
            .withTintColor(tint.resolvedColor(with: lightTraits), renderingMode: .alwaysOriginal) else { return nil }
        let target = CGSize(width: size, height: size)
        let format = UIGraphicsImageRendererFormat()
        format.scale = UIScreen.main.scale
        format.opaque = false
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            UIColor.parchment.resolvedColor(with: lightTraits).withAlphaComponent(0.9).setFill()
            UIBezierPath(ovalIn: CGRect(origin: .zero, size: target)).fill()
            let origin = CGPoint(
                x: (target.width - symbolImage.size.width) / 2,
                y: (target.height - symbolImage.size.height) / 2
            )
            symbolImage.draw(at: origin, blendMode: .normal, alpha: 0.55)
        }
    }
```
> Pilgrim/Views/MapGlyphImageBuilder.swift:135-153@7c200bf

The image name (and so Mapbox's sprite id) is `way-<resolved symbol>-<RRGGBB of the light tint>-<size>`:

```swift
        case .wayMark(let symbol, let tint):
            // Keyed on what actually renders, not on what was asked for: a
            // shared Way's icon is unvalidated, so every unknown name draws
            // the same "mappin" and must share one entry rather than mint a
            // new one per bad name.
            return "way-\(resolvedWayMarkSymbol(symbol))-\(rgbKey(for: tint.resolvedColor(with: lightTraits)))"
```
> Pilgrim/Views/MapGlyphImageBuilder.swift:76-81@7c200bf

No Way pin draws a circle under it:

```swift
            case .wayVoice, .wayPhoto, .wayRest, .waySit, .wayWaypoint, .wayMark:
                // Way moments and marks render as faded PointAnnotations in
                // `buildPoints` (via MapGlyph.wayMark) — no filled circle
                // underneath.
                continue
```
> Pilgrim/Views/PilgrimMapView.swift:460-464@7c200bf

Android counterparts: SF-symbol-name to vector mapping exists for waypoint icons (`iconKeyToVector`, `ui/walk/WaypointMarkingSheet.kt:91@5ea4029b`, with `"mappin"` as the fallback key at `WaypointMarkingSheet.kt:60@5ea4029b`); glyph rasters are cached in `ui/walk/map/MapGlyphBitmaps.kt:45@5ea4029b`. `waveform`, `photo`, `cup.and.saucer`, and `circle.circle` need Android glyphs.

**When the heard state changes.** A voice counts as heard the moment it starts, from the engine or from a card's play button, and the pins are rebuilt at once:

```swift
        activeVoice = moment
        isVoicePaused = false
        heardVoiceIDs.insert(moment.id)
        refreshHonorPins()
        wayVoicePlayer?.play(url: url, volume: Self.voiceVolume(for: kind))
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:221-225@7c200bf

(The same four lines appear in `togglePlayback`, `ActiveWalkViewModel+Honor.swift:398-402@7c200bf`.) A voice whose file is missing never reaches these lines (section 8), so it stays unheard.

**Z-order.** All Way pins, the walker's own waypoints, and the proximity pins share ONE point annotation manager. The walk map's list order is walker waypoints, whisper and cairn pins, stage marks, then Way pins:

```swift
            pinAnnotations: waypointPins + viewModel.proximityPins + viewModel.honorMarkPins + viewModel.honorPins,
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Map.swift:26@7c200bf

The manager is inserted directly above `pilgrim-route-layer` (so above the route, and above the companion, which is inserted at the same anchor later; section 5 has the full stack), and allows icon overlap:

```swift
        let routeLayerExists = mapView.mapboxMap.layerExists(withId: "pilgrim-route-layer")
        let layerPosition: LayerPosition? = routeLayerExists ? .above("pilgrim-route-layer") : nil
        ...
        if coordinator.pointManager == nil {
            if let pos = layerPosition {
                coordinator.pointManager = mapView.annotations.makePointAnnotationManager(layerPosition: pos)
            } else {
                coordinator.pointManager = mapView.annotations.makePointAnnotationManager()
            }
        }
        if let pointManager = coordinator.pointManager {
            pointManager.annotations = buildPoints(from: pinAnnotations, coordinator: coordinator)
            pointManager.iconAllowOverlap = true
        }
```
> Pilgrim/Views/PilgrimMapView.swift:377-401@7c200bf

iOS sets no symbol sort key. Within that one symbol layer, which overlapping pin draws on top is Mapbox's default `symbol-z-order` behaviour, not the array order. Android today splits pins across several managers (`waypointManager`, `annotationManager`, `proximityManager`, created in that order at `ui/walk/PilgrimMap.kt:639-651@5ea4029b`), so there manager creation order decides.

The manager is rebuilt only when the pin list changes (equality ignores each annotation's random id):

```swift
        if coordinator.circleManager != nil,
           coordinator.pointManager != nil,
           coordinator.lastAppliedAnnotations == pinAnnotations,
           coordinator.lastAppliedActivePhotoID == activePhotoID {
            return
        }
```
> Pilgrim/Views/PilgrimMapView.swift:370-375@7c200bf

**The arrival mark.** Arrival writes an ordinary live waypoint with a reserved icon and a localized label. It draws with the walker's own waypoints: the SF symbol at 18 pt, stone, no disc. It is not tappable.

```swift
    static let arrivalWaypointIcon = "signpost.right.fill"
```
> Pilgrim/Models/Honor/HonorPersistence.swift:10@7c200bf

```swift
    private static let arrivalLabelFormat = NSLocalizedString(
        "honor.arrival.label", value: "Walked their way: %@",
        comment: "Waypoint label at the end of an honored Way; %@ is the Way's title.")
```
> Pilgrim/Models/Honor/HonorPersistence.swift:42-44@7c200bf

```swift
        builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .honorArrival, timestamp: Date()))
        addWaypoint(label: HonorPersistence.arrivalWaypointLabel(wayTitle: way.title),
                    icon: HonorPersistence.arrivalWaypointIcon)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:250-252@7c200bf

```swift
                } else if let image = cachedSymbolImage(icon, size: 18, color: .stone, cacheKey: icon) {
                    point.image = .init(image: image, name: icon)
                }
```
> Pilgrim/Views/PilgrimMapView.swift:522-524@7c200bf

**What tapping a pin does.** The map's tap finds the nearest tappable pin within 25 metres ON THE GROUND (a `CLLocation` distance, not screen points). Way marks and the walker's own waypoints are not in the tappable set.

```swift
            var closest: (annotation: PilgrimAnnotation, distance: CLLocationDistance)?
            for pin in currentPinAnnotations {
                switch pin.kind {
                case .whisper, .cairn, .photo, .wayVoice, .wayPhoto, .wayRest, .waySit, .wayWaypoint:
                    let pinLoc = CLLocation(latitude: pin.coordinate.latitude, longitude: pin.coordinate.longitude)
                    let dist = tapLoc.distance(from: pinLoc)
                    if dist < 25, closest == nil || dist < closest!.distance {
                        closest = (pin, dist)
                    }
                default:
                    break
                }
            }
```
> Pilgrim/Views/PilgrimMapView.swift:777-789@7c200bf

Whisper and cairn taps keep their own handling; everything else goes to the Way card path:

```swift
        default:
            showWayCard(for: annotation)
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Map.swift:84-86@7c200bf

A tap before Begin does nothing. Otherwise the moment's card jumps to the front of the queue; the rest resume after it.

```swift
    func showWayCard(for annotation: PilgrimAnnotation) {
        // The card LAYER is already gated on an active walk, but the queue
        // behind it is not: a pin tapped on the overview map before Begin
        // used to sit in `honorCards` and ambush the walker with someone
        // else's card the moment they started walking.
        guard viewModel.status.isActiveStatus, let momentID = annotation.kind.wayMomentID else { return }
        guard let moment = viewModel.way?.moments.first(where: { $0.id == momentID }) else { return }
        viewModel.showCard(for: moment)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:26-34@7c200bf

```swift
    /// A tapped pin jumps the queue; pending cards resume after it.
    func showCard(for moment: WayMoment) {
        honorCards.removeAll { $0 == moment }
        honorCards.insert(moment, at: 0)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:333-337@7c200bf

A pin tap does not play a voice, does not mark it heard, and does not move the camera. While the arrival card is up, a tapped pin's card waits behind it (section 7). Android: `onProximityPinTap` at `ActiveWalkScreen.kt:787@5ea4029b` is the existing pin-tap seam; Android pins today dispatch through each manager's click listener (`PilgrimMap.kt:643-651@5ea4029b`), which hit-tests the icon in screen space rather than iOS's 25 m radius.

### 5. Install order, style reloads, theme changes, background, meditation

**Per update pass.** Every `updateUIView` applies, in this order: route, annotations, seek fog, then the Honor layers. A colour-scheme change instead reloads the style and returns before any of them.

```swift
        if colorScheme != context.coordinator.currentColorScheme {
            context.coordinator.currentColorScheme = colorScheme
            context.coordinator.routePlanner.reset()
            let newStyle: StyleURI = colorScheme == .dark ? .dark : .light
            context.coordinator.styleHasLoaded = false
            mapView.mapboxMap.loadStyle(newStyle)
            return
        }

        if context.coordinator.shouldRender {
            Self.applyRouteSource(routeSegments, walkingColor: walkingColor, on: mapView, coordinator: context.coordinator)
        } else {
            context.coordinator.hasDeferredRouteUpdate = true
        }
        Self.applyAnnotations(pinAnnotations, activePhotoID: activePhotoID, on: mapView, coordinator: context.coordinator)
        Self.applySeekFog(seekFog, pulse: seekPulse, on: mapView, coordinator: context.coordinator)
        Self.applyHonorWay(honorWay, companion: companion, on: mapView, coordinator: context.coordinator)
```
> Pilgrim/Views/PilgrimMapView.swift:222-238@7c200bf

**On style load (first load, and every reload including a theme flip).** The Honor layers go in FIRST, before the wabi-sabi pass, the route, the annotation managers, and the seek fog:

```swift
        mapView.mapboxMap.onStyleLoaded.observeNext { [weak coordinator = context.coordinator, weak mapView] _ in
            guard let coordinator, let mapView else { return }
            // The honor layers go in before the wabi-sabi pass: that pass adds
            // a terrain source, which flips `isStyleLoaded` back to false for
            // the rest of this callback, and the overview has no later
            // re-render to try again on.
            coordinator.styleHasLoaded = true
            Self.reinstallHonorWay(on: mapView, coordinator: coordinator)
            let mode: PilgrimMapStyle.Mode = coordinator.currentColorScheme == .dark ? .dark : .light
            PilgrimMapStyle.applyWabiSabiStyle(to: mapView.mapboxMap, mode: mode)
            if shouldFade, mapView.alpha < 1 {
                UIView.animate(withDuration: 0.5, delay: 0, options: [.curveEaseOut]) {
                    mapView.alpha = 1
                }
            }
            coordinator.routePlanner.reset()
            coordinator.lastAppliedWalkingColor = nil
            coordinator.lastAppliedAnnotations = nil
            if let old = coordinator.circleManager { mapView.annotations.removeAnnotationManager(withId: old.id) }
            if let old = coordinator.pointManager { mapView.annotations.removeAnnotationManager(withId: old.id) }
            coordinator.circleManager = nil
            coordinator.pointManager = nil
            Self.applyRouteSource(coordinator.pendingSegments, walkingColor: coordinator.walkingColor, on: mapView, coordinator: coordinator)
            Self.applyAnnotations(coordinator.pendingAnnotations, activePhotoID: coordinator.pendingActivePhotoID, on: mapView, coordinator: coordinator)
            Self.reinstallSeekFog(on: mapView, coordinator: coordinator)
        }.store(in: &context.coordinator.cancellables)
```
> Pilgrim/Views/PilgrimMapView.swift:165-190@7c200bf

```swift
    /// Called from `onStyleLoaded` and the foreground flush: layers are gone, reinstall from pending state.
    static func reinstallHonorWay(on mapView: MBMapView, coordinator: Coordinator) {
        let renderer = coordinator.honorWayRenderer
        renderer.resetForStyleReload()
        applyHonorWay(renderer.pendingWay, companion: renderer.pendingCompanion, on: mapView, coordinator: coordinator)
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:154-159@7c200bf

```swift
    func resetForStyleReload() {
        appliedWayID = nil
        companionInstalled = false
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:52-55@7c200bf

The route source creation is gated on `isStyleLoaded` (which the wabi-sabi pass has just flipped false), and when it does run it drops the managers so they are rebuilt above the fresh route:

```swift
    static func applyRouteSource(_ routeSegments: [RouteSegment], walkingColor: UIColor = .moss, on mapView: MBMapView, coordinator: Coordinator) {
        guard mapView.mapboxMap.isStyleLoaded else { return }
```
> Pilgrim/Views/PilgrimMapView+RouteSource.swift:15-16@7c200bf

```swift
            // Recreate the annotation managers above the fresh route layer.
            if let old = coordinator.circleManager { mapView.annotations.removeAnnotationManager(withId: old.id) }
            if let old = coordinator.pointManager { mapView.annotations.removeAnnotationManager(withId: old.id) }
            coordinator.circleManager = nil
            coordinator.pointManager = nil
            coordinator.lastAppliedAnnotations = nil
```
> Pilgrim/Views/PilgrimMapView+RouteSource.swift:147-152@7c200bf

The route source and both route layers are created even for an empty route (`createRouteSourceAndLayers` runs whenever the source is missing, `PilgrimMapView+RouteSource.swift:29-41@7c200bf`), so after the first successful pass they always exist.

**The resulting stack, bottom to top.** Tracing the code above:

1. First appearance (before Begin, no companion): ghost goes in at the top (no route yet) → managers go in at the top → the next pass creates casing and route on top and drops the managers → managers rebuilt above the route. Result: base style, ghost, casing, route, point manager, circle manager. (`.above(route)` twice puts the later-inserted point manager directly above the route, below the circle manager.)
2. At Begin the companion goes in `.above("pilgrim-route-layer")`: ghost, casing, route, **companion**, point manager, circle manager. Way pins draw over the companion.
3. After a style reload mid-walk (a theme flip): the ghost and the companion are both reinstalled first, at the top, before any route layer exists; the route and then the managers are added above them. Result: ghost, **companion**, casing, route, point manager, circle manager. The companion now sits UNDER the walker's route (see iOS defects). It returns to its place above the route at the next background-to-foreground or meditation-to-walk flush below, which removes and reinstalls both Honor layers against the existing route.

Only runtime layers are reinstalled: the Way pins ride the annotation managers, which the same callback rebuilds from `pendingAnnotations`.

**Appearance.** The ghost's opacity and the companion's colours are read from the map view's trait at install time (`ghostStyle(for:)`, section 2). A flip reloads the style (above), which reinstalls both with the other palette. Way-pin rasters do not change with appearance (light palette in both, section 4).

**Background and meditation.** The map stops rendering and cuts touches while backgrounded or meditating; returning reinstalls the Honor layers from pending state:

```swift
        private func refreshRenderState() {
            guard let mapView else { return }
            let wasRendering = !mapView.displayState.isEmpty

            if shouldRender {
                // Order matters: restore the display state before re-enabling
                // touch, so a tap landing mid-transition can't hit Mapbox's
                // touchesBegan while the display link is still paused.
                mapView.displayState = PilgrimMapView.renderingDisplayState
                mapView.preferredFrameRateRange = PilgrimMapView.renderFrameRateRange
                mapView.isUserInteractionEnabled = true
            } else {
                // Disabling gesture handlers is not enough: Mapbox's
                // touchesBegan is a UIResponder override that force-restarts
                // the display link on any raw touch. Cut touch delivery off
                // entirely before pausing.
                mapView.isUserInteractionEnabled = false
                mapView.displayState = []
            }

            if shouldRender && !wasRendering && hasDeferredRouteUpdate {
                PilgrimMapView.applyRouteSource(pendingSegments, walkingColor: walkingColor, on: mapView, coordinator: self)
                hasDeferredRouteUpdate = false
            }
            if shouldRender && !wasRendering {
                PilgrimMapView.flushDeferredSeekFog(on: mapView, coordinator: self)
                PilgrimMapView.reinstallHonorWay(on: mapView, coordinator: self)
            }
        }
```
> Pilgrim/Views/PilgrimMapView.swift:741-769@7c200bf

The meditation flag reaches the map as a binding from the view model (`isMeditating: $viewModel.isMeditating`, `ActiveWalkView+Map.swift:38@7c200bf`). On iOS the meditation screen is a full-screen cover over the live walk screen, so the map view survives the sitting. Android navigates to a separate `MeditationScreen`; the walk map may leave composition, so its return is a fresh style load, and the renderer must rebuild everything from Room-backed state rather than from renderer memory.

**Self-heal.** The ghost line probes `layerExists` on every pass (section 2). The companion path does the same: if its layer is missing, the `companionInstalled` fast path fails and it falls through to a fresh install (`PilgrimMapView+HonorWay.swift:239-246@7c200bf`).

Android: `PilgrimMap.kt:600-690@5ea4029b` is the style-load callback; the managers are created in order casing, route line, meditation circles, waypoints, annotations, proximity, and then `seekFogRenderer?.onStyleReloaded(reduceMotion)` runs "AFTER the annotation managers" (`PilgrimMap.kt:680-685@5ea4029b`). The plan's rule ("reinstall after the annotation managers on every style reload", Global Constraints) is a deliberate improvement on iOS's order: installed after the route line exists, the companion lands above it at once instead of below it until the next flush.

### 6. The camera during an Honor walk

**Follow.** The walk map follows the walker with the follow-puck viewport at zoom 16, padded by the sheet height. Honor changes only one thing: while a card has "flown" the map to a moment (`honorFocus` set), follow is off and the camera eases to that moment. Clearing the focus resumes follow.

```swift
        return PilgrimMapView(
            showsUserLocation: true,
            // A Way card's header can send the camera to its moment; the map
            // follows the walker again the moment that focus clears.
            followsUserLocation: viewModel.honorFocus == nil,
            ...
            cameraCenter: .constant(viewModel.honorFocus),
            cameraZoom: .constant(PilgrimMapView.followPuckZoom),
            bottomInset: mapBottomInset,
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Map.swift:20-34@7c200bf

```swift
    static let followPuckZoom: CGFloat = 16
```
> Pilgrim/Views/PilgrimMapView.swift:14@7c200bf

```swift
        if followsUserLocation {
            let padding = UIEdgeInsets(top: 0, left: 0, bottom: bottomInset, right: 0)
            let insetChanged = abs(context.coordinator.lastBottomInset - bottomInset) > 0.5
            if !context.coordinator.isFollowing || insetChanged {
                context.coordinator.isFollowing = true
                context.coordinator.lastBottomInset = bottomInset
                mapView.viewport.transition(
                    to: mapView.viewport.makeFollowPuckViewportState(
                        options: FollowPuckViewportStateOptions(padding: padding, zoom: Self.followPuckZoom)
                    )
                )
            }
        } else {
            context.coordinator.isFollowing = false
            context.coordinator.lastBottomInset = bottomInset

            if let bounds = cameraBounds {
                ...
            } else if let center = cameraCenter {
                let camera: CameraOptions
                if bottomInset > 0 {
                    let padding = UIEdgeInsets(top: 0, left: 0, bottom: bottomInset, right: 0)
                    camera = CameraOptions(center: center, padding: padding, zoom: cameraZoom)
                } else {
                    camera = CameraOptions(center: center, zoom: cameraZoom)
                }
                mapView.camera.ease(to: camera, duration: cameraDuration)
            }
        }
```
> Pilgrim/Views/PilgrimMapView.swift:240-306@7c200bf

`cameraDuration` is the default 0.4 s (`var cameraDuration: TimeInterval = 0.4`, `PilgrimMapView.swift:46@7c200bf`); the walk map does not pass one. So the fly-to is a 0.4 s ease to the moment at zoom 16, centred in the map area above the sheet (the sheet height is the bottom padding; the card is not counted).

**The focus toggle.** The card header's tap flies to the moment; the same header tapped again (same coordinate) brings the map home; another moment's header jumps straight there. Dismissing the top card also brings the map home.

```swift
    func toggleFocus(on moment: WayMoment) {
        guard let there = coordinate(of: moment) else { return }
        if let focus = honorFocus, focus.latitude == there.latitude, focus.longitude == there.longitude {
            honorFocus = nil
        } else {
            honorFocus = there
        }
    }

    /// A moment recorded with its own coordinate uses it; one placed only
    /// along the line borrows the line's point at its frac.
    private func coordinate(of moment: WayMoment) -> CLLocationCoordinate2D? {
        if let at = moment.at { return CLLocationCoordinate2D(latitude: at.lat, longitude: at.lon) }
        return honorEngine?.geometry.coordinate(atFrac: moment.frac)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:289-303@7c200bf

```swift
    func dismissTopCard() {
        if !honorCards.isEmpty { honorCards.removeFirst() }
        // A card that flew the map somewhere takes the map home when it goes.
        honorFocus = nil
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:327-331@7c200bf

Teardown also clears it (`honorFocus = nil`, `ActiveWalkViewModel+Honor.swift:153@7c200bf`). A card that retires on its own (section 7) does NOT clear the focus; only `dismissTopCard` and teardown do. In practice the card that flew the map never retires itself, because the fly tap also touches it (`Button { onTouch(); onFly() }`, section 8). But a newer card can rise on top while the map is still on the older card's moment. That card's header then reads as focused (its `isFocused` is `viewModel.honorFocus != nil`, any focus), and one tap on it flies to ITS moment rather than home (section 14 and iOS defects).

Notes an implementer needs:
- The focus target is `moment.at`, else the route point at its frac. It is NOT the pin's `pin` coordinate (section 4 uses `pin ?? at`). For own and shared walks `pin` is always nil, so the two agree. On a stage they differ (section 15).
- `coordinate(of:)` returns nil for a moment with no `at` before Begin (no engine), so a header tap then does nothing. The card layer is hidden before Begin anyway.
- While focused, every update pass eases again: the `cameraCenter` branch has no change check (unlike the bounds branch just above it), and the walk screen's body re-runs at least once a second. A pan away from the moment is pulled back within a second.
- iOS never calls `viewport.idle()` when the focus begins. It sets `isFollowing = false` and eases the camera. See Open questions.
- No Way-specific framing exists on the walk: no fit to the Way, no fit to the next moment. Fits to the whole Way belong to the overview (U21) and the summary map (U23).

Android counterparts: `buildFollowPuckOptions` and `shouldEnterFollowViewport` (`ui/walk/PilgrimMap.kt:1820-1843@5ea4029b`), the follow-viewport effect at `PilgrimMap.kt:754-776@5ea4029b`, and `CameraFitApplier.kt` / `CameraFitDecision.kt` in `ui/walk/map/`.

### 7. The card host: which card shows, the queue, retirement

**One card at a time.** The arrival card, while it is up, outranks every place card; otherwise the first card in `honorCards` shows. Queued place cards wait behind the arrival card and appear after "continue".

```swift
        Group {
            // Dismissal retires the card through its own flag: `honorArrival`
            // carries the companion delta the coordinator persists when the
            // walk is saved, which may be long after this tap.
            if let card = viewModel.honorArrival, !viewModel.honorArrivalCardDismissed {
                HonorArrivalCardView(
                    card: card,
                    existingReply: stageReply,
                    isRecordingReply: viewModel.isRecordingVoice
                        && viewModel.pendingReplyOrigin?.id == HonorPersistence.stageReflectionMomentID,
                    onReply: { viewModel.replyToStageReflection() },
                    // The same toggle `WayPlaceCard`'s voice body stops with.
                    onStopReply: { viewModel.toggleVoiceRecording() },
                    onPlayReply: { url in viewModel.playReply(url: url) },
                    onDismiss: { viewModel.honorArrivalCardDismissed = true })
                    .task(id: viewModel.completedRecordingCount) {
                        stageReply = viewModel.stageReflectionReplyURL()
                    }
            } else if let moment = viewModel.honorCards.first {
                let isPlaying = viewModel.activeVoice == moment
                WayPlaceCard(
                    moment: moment,
                    isStage: viewModel.way?.isPilgrimageStage == true,
                    mediaURL: mediaURL,
                    distanceMeters: viewModel.distanceToMoment(moment),
                    isPlaying: isPlaying,
                    isPaused: viewModel.isVoicePaused,
                    // The player's clock is shared across every voice; a card
                    // whose voice isn't the one playing must not show its tick.
                    elapsed: isPlaying ? voicePlayer.elapsedSeconds : 0,
                    waveform: waveform,
                    isRecordingReply: viewModel.isRecordingVoice && viewModel.pendingReplyOrigin == moment,
                    pendingCount: max(0, viewModel.honorCards.count - 1),
                    existingReply: existingReply,
                    rate: viewModel.voiceRate,
                    tick: viewModel.relativeBearing(to: moment),
                    isFocused: viewModel.honorFocus != nil,
                    onFly: { viewModel.toggleFocus(on: moment) },
                    onPlayPause: { viewModel.togglePlayback(of: moment) },
                    onSeek: { fraction in viewModel.seekVoice(moment, toFraction: fraction) },
                    onCycleRate: { viewModel.cycleVoiceRate() },
                    onPlayReply: { url in viewModel.playReply(url: url) },
                    onReply: { viewModel.replyHere(to: moment) },
                    onStopReply: { viewModel.toggleVoiceRecording() },
                    onSit: onSit,
                    onDismiss: { viewModel.dismissTopCard() },
                    onTouch: { viewModel.touchCard(moment) }
                )
                .id(moment.id)
                .task(id: lookupKey(for: moment)) { await resolveFiles(for: moment) }
            }
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:58-108@7c200bf

The arrival card is dismissed by its own flag, never by clearing `honorArrival` (`ActiveWalkViewModel.swift:109-113@7c200bf`), and nothing un-dismisses it. It has no swipe and no close button; "continue" is the only way out (section 11).

**How cards enter the queue.**
- A non-voice moment the engine reaches (`.momentReached`) is appended to the BACK, once, with the `waypointDropped` haptic (foreground only).
- A voice's card is pushed to the FRONT when the voice starts playing (`startVoice` → `showCard`). A voice queued in the engine but not yet started has no card; neither does a queued voice the engine drops.
- A tapped pin pushes its moment to the FRONT (section 4).

```swift
        case .momentReached(let moment):
            if !honorCards.contains(moment) { honorCards.append(moment) }
            reachedMomentIDs.insert(moment.id)
            fireHonorHaptic(.waypointDropped)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:177-180@7c200bf

```swift
        // The voice's own card rises with it — the waveform, the place, the
        // reply — and retires itself after the voice unless the walker
        // touches it, so an unanswered voice never leaves a card to close.
        showCard(for: moment)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:226-229@7c200bf

The engine only reports `.reached` for non-voice moments; voices go to its own queue:

```swift
            reached.insert(moment.id)
            if moment.isVoice {
                if voicesEnabled { queue.append(moment) }
            } else {
                actions.append(.reached(moment))
            }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:68-73@7c200bf

So with the voices toggle off, no voice card ever rises by itself; its pin still opens it, and its play button still plays (the view model's `togglePlayback` is not gated on the toggle).

Begin clears anything queued before it: `honorCards.removeAll()` (`ActiveWalkViewModel+Honor.swift:50@7c200bf`).

**How cards leave.**
- **Dismiss** (the × button or a sideways swipe past 80 pt) removes the TOP card and clears the camera focus (`dismissTopCard`, section 6). Cards are not removed by id here.
- **Self-retire**: when a voice ends on its own, its card retires 20 s later unless the walker touched it or that voice is playing again. The timer is a generation-guarded `asyncAfter`, cancelled only by teardown.

```swift
    /// The seconds a finished voice's card stays before retiring on its own.
    static let cardRetireSeconds: TimeInterval = 20

    func touchCard(_ moment: WayMoment) {
        touchedCardIDs.insert(moment.id)
    }

    /// A voice card the walker never touched leaves by itself once its voice
    /// has ended; one they touched (played again, replied to) waits for them.
    func retireIfUntouched(_ moment: WayMoment) {
        guard !touchedCardIDs.contains(moment.id), activeVoice != moment else { return }
        honorCards.removeAll { $0 == moment }
    }

    private func retireCardLater(_ moment: WayMoment) {
        let generation = honorGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.cardRetireSeconds) { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            self.retireIfUntouched(moment)
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:305-325@7c200bf

The timer is scheduled only from the player's `onFinished`, which fires on a natural end, a decode error, or a failed start:

```swift
        player.onFinished = { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            let finished = self.activeVoice
            self.activeVoice = nil
            self.isVoicePaused = false
            self.honorEngine?.voiceDidFinish()
            if let finished { self.retireCardLater(finished) }
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:64-71@7c200bf

It is NOT scheduled when the voice is skipped (chip), dropped by the engine, replaced by another voice from a card, or stopped for "your reply": each of those calls `stop()`, which finishes with `notify: false` (`WayVoicePlayer.swift:109-113@7c200bf`). Those cards stay until dismissed. Only voice cards self-retire; photo, rest, sitting, and waypoint cards always wait for the walker.

The 20 s runs from the moment the voice ended (wall clock on the main queue), not from any later event. Non-voice cards have no timer.

**Touch.** Any tap anywhere on the card, and every button except ×, marks it touched (section 8). `touchedCardIDs` lives for the walk (cleared at teardown, `ActiveWalkViewModel+Honor.swift:151@7c200bf`), so a card touched once never self-retires later in that walk.

**Per-card file resolution.** The host resolves the card's files off the body, once per card and again after each saved recording. The waveform (150 bars, normalized to the loudest) is read on a detached utility task. `.id(moment.id)` gives each card fresh `@State` (the swipe offset, the replace dialog).

```swift
    /// Re-resolves when the card changes, and again once a recording has
    /// finished saving — the completed count, not the recording flag: the
    /// file only becomes readable after the flag has already gone back to
    /// false, so keying on the flag left a fresh reply hidden until the card
    /// was closed and reopened.
    private func lookupKey(for moment: WayMoment) -> String {
        "\(moment.id)|\(viewModel.completedRecordingCount)"
    }

    private func resolveFiles(for moment: WayMoment) async {
        let url = momentMediaURL(for: moment)
        mediaURL = url
        existingReply = moment.isVoice ? viewModel.existingReplyURL(for: moment) : nil
        // The host outlives each card (only the card carries `.id`), so a
        // stale waveform must be cleared before the next one is read.
        waveform = nil
        guard moment.isVoice, let url else { return }
        let samples = await Task.detached(priority: .utility) { WaveformGenerator.generateSamples(from: url) }.value
        guard !Task.isCancelled else { return }
        waveform = samples
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:113-133@7c200bf

```swift
    static func generateSamples(from url: URL, count: Int = 150) -> [Float]? {
```
> Pilgrim/Models/Audio/WaveformGenerator.swift:5@7c200bf

**Distance, tick, and elapsed inputs.** Distance is straight-line metres from the last fix to `moment.at` (or the route point at its frac); nil before the first fix. The tick is the relative bearing (section 9). `elapsed` is the shared player's clock, shown only on the card whose voice is the active one.

```swift
    func distanceToMoment(_ moment: WayMoment) -> Double? {
        guard let here = currentLocation, let there = coordinate(of: moment) else { return nil }
        return CLLocation(latitude: here.latitude, longitude: here.longitude)
            .distance(from: CLLocation(latitude: there.latitude, longitude: there.longitude))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:273-277@7c200bf

The player's clock updates once a second (a 1 s timer copying `player.currentTime`), and immediately on a seek:

```swift
    private func startElapsedTimer() {
        elapsedTimer?.invalidate()
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            guard let self, let player = self.player else { return }
            self.elapsedSeconds = player.currentTime
        }
        RunLoop.main.add(timer, forMode: .common)
        elapsedTimer = timer
    }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:209-217@7c200bf

**Transitions.** None. `showCard`, `dismissTopCard`, and retirement mutate `honorCards` without `withAnimation`, and the card has no `.transition`, so cards appear and disappear instantly. The only card animations are the swipe spring-back and the recording pulse (section 8).

Android: plan U22 builds the host as `P/ui/walk/HonorWalkViewModel.kt` reading moment rows from Room; the UI-owned card-state table carries the touched and dismissed flags that iOS keeps in `touchedCardIDs` and `honorCards`.

### 8. `WayPlaceCard`

**Layout tree.**

```
VStack(alignment: .leading, spacing: 8)
├─ HStack(alignment: .top)
│  ├─ Button (plain): WayMomentHeader(compact: true, subline, tick)   ← tap: touch + fly
│  ├─ Spacer(minLength: 8)
│  ├─ queue pips (only if pendingCount > 0)
│  └─ Button: "xmark", fog, min 44 × 44                                ← dismiss
└─ body for the moment's kind (voice / photo / rest / sitting / waypoint)
padding 16 · background RoundedRectangle(12) parchmentSecondary · no border, no shadow
```

```swift
    var body: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
            HStack(alignment: .top) {
                // The header is the way to the place: one tap flies the map
                // there, the next brings it back to the walker.
                Button { onTouch(); onFly() } label: {
                    WayMomentHeader(
                        moment: moment,
                        subline: WayMomentHeader.relation(distanceMeters: distanceMeters, place: moment.place),
                        compact: true,
                        tick: tick
                    )
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isFocused ? "Back to where you are" : "Show this place on the map")
                Spacer(minLength: Constants.UI.Padding.small)
                if pendingCount > 0 { queuePips }
                Button(action: onDismiss) {
                    Image(systemName: "xmark").foregroundColor(.fog)
                        .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                .accessibilityLabel("Dismiss")
            }
            body(for: moment.kind)
        }
        .padding(Constants.UI.Padding.normal)
        .background(RoundedRectangle(cornerRadius: Constants.UI.CornerRadius.normal).fill(Color.parchmentSecondary))
        .offset(x: dragOffset)
        .opacity(1 - min(0.6, abs(dragOffset) / 240))
        .gesture(
            DragGesture(minimumDistance: 20)
                .onChanged { value in
                    if abs(value.translation.width) > abs(value.translation.height) { dragOffset = value.translation.width }
                }
                .onEnded { value in
                    if abs(value.translation.width) > Self.swipeToDismissPoints {
                        onDismiss()
                    } else {
                        withAnimation(.easeOut(duration: 0.2)) { dragOffset = 0 }
                    }
                }
        )
        .simultaneousGesture(TapGesture().onEnded { onTouch() })
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:47-91@7c200bf

```swift
    private static let swipeToDismissPoints: CGFloat = 80
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:45@7c200bf

The × glyph gets no explicit font (system default body size). The × does not call `onTouch`.

**Swipe to dismiss.** Either direction. The card follows the finger only while the drag is more horizontal than vertical; it fades to at most 0.6 transparency (opacity `1 - min(0.6, |dx| / 240)`). On release, `|translation.width| > 80` dismisses the top card; the check does not require the drag to be mostly horizontal. Below 80 it springs back with `easeOut(duration: 0.2)`. A dismissed card vanishes at once (no fly-off; section 7).

**Queue pips.** Not a "+N more" label: up to four 5 pt dots, stone at 0.45, 3 pt apart, 8 pt from the top. VoiceOver reads the full count.

```swift
    /// One dot per waiting card, the same faded stone as their pins.
    private var queuePips: some View {
        HStack(spacing: 3) {
            ForEach(0..<min(pendingCount, 4), id: \.self) { _ in
                Circle().fill(Color.stone.opacity(0.45)).frame(width: 5, height: 5)
            }
        }
        .padding(.top, Constants.UI.Padding.small)
        .accessibilityLabel("\(pendingCount) more waiting")
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:93-102@7c200bf

**Bodies by kind.**

```swift
    @ViewBuilder
    private func body(for kind: WayMomentKind) -> some View {
        switch kind {
        case .voice(_, let duration, _, _):
            voiceBody(duration: duration)
        case .photo(let media):
            WayPhotoPlate(media: media, fileURL: mediaURL, maxHeight: 110)
        case .rest:
            Text("A pause in their walk. The companion waits here with you.")
                .font(Constants.Typography.caption).foregroundColor(.fog)
        case .meditation(let minutes, _):
            sitRow(minutes: minutes)
        case .waypoint:
            VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
                // The dataset may send 600 characters; the card does not
                // scroll and sits over the map, so it shows what fits.
                Text(WayMomentHeader.placeCopy(for: moment, isStage: isStage))
                    .font(Constants.Typography.caption).foregroundColor(.fog)
                    .lineLimit(4)
                if let minutes = moment.sitMinutes, minutes > 0 {
                    sitRow(minutes: minutes)
                }
            }
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:104-128@7c200bf

- Photo: the plate at max height 110 (section 13).
- Rest: one caption line, `"A pause in their walk. The companion waits here with you."`, caption, fog.
- Sitting: the sit row, always (no `minutes > 0` guard on this path).
- Waypoint: the place copy (the moment's `text` if non-empty, else `"A place they marked."`, or `"A place on the way."` on a stage), caption, fog, at most 4 lines; plus the sit row when `sitMinutes > 0` (stage data only; section 15).

**The sit row.**

```swift
    private func sitRow(minutes: Int) -> some View {
        HStack(spacing: Constants.UI.Padding.normal) {
            Button { onTouch(); onSit(minutes) } label: {
                Text("Sit?").font(Constants.Typography.button).foregroundColor(.parchment)
                    .padding(.horizontal, Constants.UI.Padding.big).padding(.vertical, Constants.UI.Padding.small)
                    .background(Color.stone).cornerRadius(Constants.UI.CornerRadius.normal)
            }
            .accessibilityLabel("Sit here for \(minutes) minutes")
            Text("your soundscape holds while you sit")
                .font(Constants.Typography.caption).foregroundColor(.fog)
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:131-142@7c200bf

"Sit?" (Lato Bold 17, parchment on a stone pill, 24 × 8 padding, 12 pt corners) touches the card and calls `onSit(minutes)`. The walk screen then starts the sitting and presents the meditation screen (section 1). Starting a sitting stops any recording first, and it is allowed while paused: the card layer shows in every active status (`[.recording, .paused, .autoPaused]`, `WalkBuilder+Status.swift:80-82@7c200bf`), and `startMeditation` checks only that no sitting is running.

```swift
    func startMeditation(minutes: Int) {
        suggestedMeditationMinutes = minutes
        startMeditation()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:379-382@7c200bf

```swift
    func startMeditation() {
        guard !isMeditating else { return }
        if isRecordingVoice {
            voiceRecordingManagement.stopRecording()
        }
        meditationStartDate = Date()
        isMeditating = true
        soundManagement.onMeditationStart()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:473-481@7c200bf

The minutes only feed the meditation caption; nothing ends the sitting at N minutes (section 12). The caption is cleared when the sitting ends (`suggestedMeditationMinutes = nil` in `finalizeMeditation`, `ActiveWalkViewModel.swift:502-503@7c200bf`).

**The voice body.** Two rows (transport, then reply), preceded by the transcript's first sentence in curly quotes when there is one. While the walker records a reply to THIS voice, all of that is replaced by a recording row.

```swift
    @ViewBuilder
    private func voiceBody(duration: Double) -> some View {
        if isRecordingReply {
            HStack(spacing: Constants.UI.Padding.small) {
                RecordingPulse()
                Text("recording your reply here").font(Constants.Typography.body).foregroundColor(.ink)
                Spacer()
                Button { onStopReply() } label: {
                    Image(systemName: "stop.circle.fill").font(Constants.Typography.displayMedium).foregroundColor(.rust)
                }
                .accessibilityLabel("Stop recording your reply")
            }
        } else {
            if let line = moment.transcriptLine {
                Text("“\(line)”")
                    .font(Constants.Typography.body).italic().foregroundColor(.ink)
                    .lineLimit(2)
            }
            transportRow(duration: duration)
            replyRow
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:149-170@7c200bf

The transcript line is the first sentence (up to the first `.`, `!`, or `?`), capped at 120 characters at a word boundary with `…`:

```swift
    var transcriptLine: String? { WayMoment.firstSentence(of: transcript, maxCharacters: 120) }
```
> Pilgrim/Models/Honor/Way.swift:66@7c200bf

```swift
    static func firstSentence(of transcript: String?, maxCharacters: Int) -> String? {
        guard let transcript = trimmedTranscript(transcript) else { return nil }
        var sentence = transcript
        if let end = transcript.firstIndex(where: { ".!?".contains($0) }) {
            sentence = String(transcript[...end])
        }
        if sentence.count > maxCharacters {
            let cut = sentence.prefix(maxCharacters)
            let atWord = cut.lastIndex(of: " ").map { String(cut[..<$0]) } ?? String(cut)
            return atWord + "…"
        }
        return sentence
    }
```
> Pilgrim/Models/Honor/Way.swift:76-88@7c200bf

The recording pulse: a 10 pt rust dot breathing between 0.85× at 0.6 opacity and 1.25× at full, `easeInOut(duration: 0.9)` repeating with autoreverse; hidden from VoiceOver.

```swift
private struct RecordingPulse: View {
    @State private var swelled = false

    var body: some View {
        Circle()
            .fill(Color.rust)
            .frame(width: 10, height: 10)
            .scaleEffect(swelled ? 1.25 : 0.85)
            .opacity(swelled ? 1 : 0.6)
            .animation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true), value: swelled)
            .onAppear { swelled = true }
            .accessibilityHidden(true)
    }
}
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:258-271@7c200bf

**The transport row.**

```swift
    private func transportRow(duration: Double) -> some View {
        let playing = isPlaying && !isPaused
        return HStack(alignment: .center, spacing: Constants.UI.Padding.small) {
            Button { onTouch(); onPlayPause() } label: {
                Image(systemName: playing ? "pause.circle.fill" : "play.circle.fill")
                    .font(Constants.Typography.displayMedium).foregroundColor(.stone)
            }
            .accessibilityLabel(playing ? "Pause their voice" : "Play their voice")
            VStack(alignment: .leading, spacing: 4) {
                if let waveform {
                    WaveformBarView(samples: waveform, progress: duration > 0 ? min(1, elapsed / duration) : 0, isPlaying: playing) { fraction in
                        onTouch(); onSeek(fraction)
                    }
                    .frame(height: 28)
                    .accessibilityLabel("Their voice; drag to move through it")
                } else {
                    RoundedRectangle(cornerRadius: 4).fill(Color.fog.opacity(0.15)).frame(height: 28)
                }
                Text("\(clock(elapsed)) / \(clock(duration))")
                    .font(Constants.Typography.caption).foregroundColor(.fog).monospacedDigit()
            }
            Button { onTouch(); onCycleRate() } label: {
                Text(rateLabel)
                    .font(Constants.Typography.caption)
                    .foregroundColor(rate > 1 ? .parchment : .stone)
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(rate > 1 ? Color.stone : Color.stone.opacity(0.12))
                    .cornerRadius(4)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Playback speed, \(rateLabel)")
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:172-205@7c200bf

```swift
    private var rateLabel: String {
        rate.truncatingRemainder(dividingBy: 1) == 0 ? String(format: "%.0fx", rate) : String(format: "%gx", rate)
    }

    private func clock(_ seconds: Double) -> String {
        let total = Int(max(0, seconds))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:246-253@7c200bf

- Play/pause: 28 pt glyph (the `displayMedium` font sizes the symbol), stone.
- Progress is `elapsed / duration` where `duration` is the Way's recorded duration, not the file's.
- Clock: `m:ss / m:ss`, truncated seconds, caption, fog, monospaced digits. Android must pin `Locale.US` for `%d:%02d`.
- Rate pill: `1x`, `1.25x`, `1.5x`, `2x`. Above 1× it inverts to parchment on stone; at 1× it is stone on stone at 0.12. Padding 6 × 3, corner 4; the 44 × 44 frame is the hit area, not the visible pill.
- The ladder:

```swift
    static let rates: [Float] = [1, 1.25, 1.5, 2]
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:32@7c200bf

```swift
    /// 1× → 1.25× → 1.5× → 2× → 1×, the same ladder as the post-walk player.
    func cycleVoiceRate() {
        let rates = WayVoicePlayer.rates
        let next = rates[((rates.firstIndex(of: voiceRate) ?? 0) + 1) % rates.count]
        voiceRate = next
        wayVoicePlayer?.setRate(next)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:413-419@7c200bf

The rate applies to every later voice in the walk and to "your reply" (same player). The card's label reads the view model's `voiceRate`, which is 1 at the start of every walk; the player's own `playbackRate` is never reset (iOS defects).

The waveform is the shared `WaveformBarView`: 150 bars, unplayed bars fog at 0.4, played bars stone, masked by progress; a drag anywhere on it seeks continuously (minimum distance 0). Its own frame is 32 pt tall inside the card's 28 pt frame.

```swift
                        RoundedRectangle(cornerRadius: barWidth / 2)
                            .fill(Color.fog.opacity(0.4))
                            .frame(width: barWidth, height: max(2, geo.size.height * CGFloat(amp)))
                    }
                }
                HStack(alignment: .center, spacing: 0.5) {
                    ForEach(Array(samples.enumerated()), id: \.offset) { _, amp in
                        RoundedRectangle(cornerRadius: barWidth / 2)
                            .fill(Color.stone)
                            .frame(width: barWidth, height: max(2, geo.size.height * CGFloat(amp)))
                    }
                }
                .mask(alignment: .leading) {
                    Rectangle().frame(width: geo.size.width * CGFloat(progress))
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let fraction = max(0, min(1, Double(value.location.x / geo.size.width)))
                        onSeek?(fraction)
                    }
            )
        }
        .frame(height: 32)
        .accessibilityElement()
        .accessibilityLabel("Playback position")
        .accessibilityValue("\(Int((progress * 100).rounded())) percent")
        .accessibilityAdjustableAction { direction in
```
> Pilgrim/Scenes/Settings/RecordingsListView.swift:542-571@7c200bf

Android already has a waveform view (`ui/walk/AudioWaveformView.kt@5ea4029b`).

A seek on a card whose voice is not the active one starts that voice first; a seek lands at most at 0.999 of the file:

```swift
    func seekVoice(_ moment: WayMoment, toFraction fraction: Double) {
        if moment != activeVoice { togglePlayback(of: moment) }
        guard moment == activeVoice else { return }
        wayVoicePlayer?.seek(toFraction: fraction)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:407-411@7c200bf

```swift
        player.currentTime = min(max(0, fraction), 0.999) * player.duration
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:117@7c200bf

**The reply row.**

```swift
    private var replyRow: some View {
        HStack(spacing: Constants.UI.Padding.small) {
            replyButton
            if let existingReply {
                Spacer()
                Button { onTouch(); onPlayReply(existingReply) } label: {
                    Label("your reply", systemImage: "play.circle")
                        .font(Constants.Typography.caption).foregroundColor(.stone)
                        .frame(minHeight: 44).contentShape(Rectangle())
                }
                .accessibilityLabel("Play your earlier reply")
            }
        }
    }

    @ViewBuilder
    private var replyButton: some View {
        if existingReply == nil {
            Button { onTouch(); onReply() } label: { replyPill("reply here") }
                .accessibilityLabel("Record a reply at this spot")
        } else {
            Button { onTouch(); confirmReplace = true } label: { replyPill("record again") }
                .accessibilityLabel("Record a new reply, replacing your earlier one")
                .confirmationDialog("Replace your earlier reply?", isPresented: $confirmReplace, titleVisibility: .visible) {
                    Button("Replace", role: .destructive, action: onReply)
                    Button("Keep it", role: .cancel) {}
                }
        }
    }

    private func replyPill(_ title: String) -> some View {
        Label(title, systemImage: "mic")
            .font(Constants.Typography.caption).foregroundColor(.stone)
            .padding(.horizontal, Constants.UI.Padding.normal).padding(.vertical, Constants.UI.Padding.small)
            .overlay(Capsule().stroke(Color.stone.opacity(0.5), lineWidth: 1))
            .frame(minHeight: 44)
            .contentShape(Rectangle())
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:207-244@7c200bf

- No earlier reply: a `mic` + `"reply here"` capsule (stone outline at 0.5, 1 pt; padding 16 × 8).
- An earlier reply exists: `"record again"` (asks first: title `"Replace your earlier reply?"`, buttons `"Replace"` destructive and `"Keep it"` cancel), and, trailing, `play.circle` + `"your reply"`.
- "Earlier reply" means a mapping filed under this Way for this voice's index whose recording file still exists; a mapping whose file is gone reads as none (`existingReplyURL`, `ActiveWalkViewModel+Replies.swift:43-50@7c200bf`). On an own walk the Way is the walker's own earlier walk, so the reply comes from a previous honoring of that walk.

Starting a reply sets the origin, then starts the recorder only if it is not already recording; if the recorder still is not running (permission denied, recorder failed), the origin is cleared:

```swift
    func replyHere(to voice: WayMoment) {
        pendingReplyOrigin = voice
        if !isRecordingVoice { toggleVoiceRecording() }
        // `isRecordingVoice` only mirrors `voiceRecordingManagement.isRecording`
        // through an async main-queue sink, so it can't be trusted here yet —
        // reading the component directly gives the synchronous answer.
        // Denied permission, an inactive walk, or a recorder that failed to
        // open all leave it false; with nothing now in flight, no completed
        // recording will ever arrive to consume this origin.
        if !voiceRecordingManagement.isRecording {
            pendingReplyOrigin = nil
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:20-32@7c200bf

"your reply" stops the active voice, clears it, and plays the reply through the same player at the guide's volume:

```swift
    func playReply(url: URL) {
        wayVoicePlayer?.stop()
        activeVoice = nil
        isVoicePaused = false
        wayVoicePlayer?.play(url: url, volume: Float(UserPreferences.voiceGuideVolume.value))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:70-75@7c200bf

With `activeVoice` nil, no card shows as playing and the listening chip hides while the reply plays. When it ends, `onFinished` hands the engine its turn (`voiceDidFinish()`), and there is no card to retire.

**States of a voice card, as the walker sees them.**

| State | Play glyph and label | Clock | Waveform | Notes |
|---|---|---|---|---|
| Not the active voice (never played, finished, skipped, dropped) | `play.circle.fill`, "Play their voice" | `0:00 / m:ss` | bars, empty progress; placeholder bar until read | |
| Playing | `pause.circle.fill`, "Pause their voice" | ticks once a second | fills stone | |
| Paused (walker, or the engine's pause while walk-paused, meditating, recording, or a whisper plays) | `play.circle.fill`, "Play their voice" | frozen | frozen | `isPlaying && isPaused` |
| Waiting on a guide prompt (queued inside the player) | `pause.circle.fill`, "Pause their voice" | `0:00`, not ticking | empty | the player holds it as `pending` until the prompt ends (`WayVoicePlayer.swift:58-64@7c200bf`) |
| Held mid-voice by a guide prompt | `pause.circle.fill`, "Pause their voice" | frozen | frozen | the guide calls `WayVoicePlayer.shared.pauseForGuide()` (`VoiceGuidePlayer.swift:42@7c200bf`); the view model's `isVoicePaused` stays false, so the card still reads as playing |
| File present but it fails to open or play | `play.circle.fill` | `0:00 / m:ss` | bars if the file decodes, else the placeholder | no error text; already counted heard; retires after 20 s if untouched |
| File not on this phone | (no card rises; if opened from its pin) `play.circle.fill`, but the button does nothing | `0:00 / m:ss` | placeholder bar | not heard; see below |
| Recording a reply to this voice | the transport is replaced by the recording row | — | — | |
| Earlier reply exists | normal transport | | | "record again" + "your reply" |

Heard or not heard does not change the card; it shows only on the pin (section 4).

The two failure paths, in the view model:

```swift
    /// A voice whose file is gone was never heard: hand the turn straight
    /// back to the engine so the next one can start.
    private func startVoice(_ moment: WayMoment) {
        guard case .voice(_, _, let kind, let media) = moment.kind, let url = mediaURL(for: media) else {
            honorEngine?.voiceDidFinish()
            return
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:214-220@7c200bf

```swift
        guard case .voice(_, _, let kind, let media) = moment.kind, let url = mediaURL(for: media) else { return }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:396@7c200bf

and in the player:

```swift
        do {
            let p = try AVAudioPlayer(contentsOf: url)
            p.delegate = self
            p.volume = volume
            // Rate must be enabled before the player is prepared or it stays 1×.
            p.enableRate = true
            p.rate = playbackRate
            p.prepareToPlay()
            guard p.play() else {
                finish(notify: true)
                return
            }
            ...
        } catch {
            print("[WayVoicePlayer] playback error: \(error)")
            finish(notify: true)
        }
```
> Pilgrim/Models/Honor/WayVoicePlayer.swift:159-178@7c200bf

A failed start runs `onFinished` synchronously inside `play`, so by the time `startVoice` reaches `showCard` the voice is already inactive, marked heard, and scheduled to retire. A decode error mid-voice takes the same `finish(notify: true)` path (`WayVoicePlayer.swift:136-141@7c200bf`).

Android: plan U22's "a voice that fails to play shows on its card as U11 pins from iOS" resolves to: the card shows the plain not-playing state, with no failure copy (rows above).

### 9. `WayMomentHeader` and the heading tick

The header is shared by the overview's preview (full size) and the walk card (`compact: true`). Compact: a 36 pt parchment disc holding the moment's glyph at body size in stone; beside it, 8 pt away, a column (4 pt spacing) of the kicker (body, ink), the place's local name (caption, fog, one line) when there is one, and the subline row (tick + subline text).

```swift
    var body: some View {
        HStack(alignment: .top, spacing: compact ? Constants.UI.Padding.small : Constants.UI.Padding.normal) {
            ZStack {
                Circle().fill(Color.parchment).frame(width: compact ? 36 : 52, height: compact ? 36 : 52)
                Image(systemName: Self.glyph(for: moment))
                    .font(compact ? Constants.Typography.body : Constants.Typography.heading)
                    .foregroundColor(.stone)
            }
            VStack(alignment: .leading, spacing: Constants.UI.Padding.xs) {
                Text(Self.kicker(for: moment))
                    .font(compact ? Constants.Typography.body : Constants.Typography.heading)
                    .foregroundColor(.ink)
                if let localName = Self.localName(for: moment) {
                    Text(localName)
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                        .lineLimit(1)
                }
                if let subline {
                    HStack(spacing: 4) {
                        if let tick {
                            Image(systemName: "location.north.fill")
                                .font(Constants.Typography.caption)
                                .foregroundColor(.stone)
                                .rotationEffect(.degrees(tick))
                                .animation(.easeOut(duration: 0.25), value: tick)
                                .accessibilityHidden(true)
                        }
                        Text(subline).font(Constants.Typography.caption).foregroundColor(.fog)
                    }
                }
            }
        }
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:13-46@7c200bf

The kicker has no line limit.

**Glyph and kicker per kind.**

```swift
    static func glyph(for moment: WayMoment) -> String {
        switch moment.kind {
        case .voice(_, _, let kind, _): return kind == .ambient ? "wind" : "waveform"
        case .photo: return "photo"
        case .rest: return "cup.and.saucer"
        case .meditation: return "circle.circle"
        case .waypoint(_, let icon): return UIImage(systemName: icon) == nil ? "mappin" : icon
        }
    }

    static func kicker(for moment: WayMoment) -> String {
        switch moment.kind {
        case .voice(_, _, let kind, _): return kind == .ambient ? "the sound of this place" : "spoken here"
        case .photo: return "what they saw here"
        case .rest(let minutes): return "they rested here \(minutes) minutes"
        case .meditation(let minutes, let isEstimate):
            return isEstimate ? "they sat here about \(minutes) minutes" : "they sat here for \(minutes) minutes"
        case .waypoint(let label, _): return label
        }
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:48-67@7c200bf

The rest and sitting kickers have no singular form (iOS defects). Own-walk sittings are never estimates (`isEstimate: false`, `OwnWalkWayBuilder.swift:88-89@7c200bf`), so an own walk always reads "they sat here for N minutes". There is no own-walk copy branch: an own walk says "they" and "their" like a shared one.

**Local name.** The first non-empty name, in this language order, that differs from the kicker. Only stage data carries names (section 15).

```swift
    static let localNameOrder = ["eu", "gl", "es", "fr", "ja", "pt", "it", "de"]

    static func localName(for moment: WayMoment) -> String? {
        guard let names = moment.names else { return nil }
        let label = kicker(for: moment)
        for code in localNameOrder {
            guard let name = names[code], !name.isEmpty, name != label else { continue }
            return name
        }
        return nil
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:72-82@7c200bf

**Subline.** `"here"` under 30 m, else `"<distance> away"` in the walker's unit; then the place name (the worker's street name, shared walks only) after ` · `. Nil when neither exists (before the first fix, with no place), and then no tick shows either.

```swift
    static func relation(distanceMeters: Double?, place: String?) -> String? {
        var parts: [String] = []
        if let distanceMeters {
            parts.append(distanceMeters < 30 ? "here" : "\(WayDistance.string(meters: distanceMeters)) away")
        }
        if let place, !place.isEmpty { parts.append(place) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

/// Walking-scale distances in the unit the walker chose in Settings: metres
/// up to a kilometre, feet up to a tenth of a mile, then one decimal.
enum WayDistance {

    static func string(meters: Double, unit: UnitLength = UserPreferences.distanceMeasurementType.safeValue) -> String {
        let meters = max(0, meters)
        if unit == .miles {
            let miles = meters / 1609.344
            if miles < 0.1 { return "\(Int((meters * 3.28084).rounded())) ft" }
            return String(format: "%.1f mi", miles)
        }
        if meters < 1000 { return "\(Int(meters.rounded())) m" }
        return String(format: "%.1f km", meters / 1000)
    }
}
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:94-118@7c200bf

Android: `%.1f` must be pinned to `Locale.US` to match iOS's `String(format:)` output.

**What the tick points at.** The direction from the walker's last fix to the moment's `at` (else its route point), relative to the walker's compass heading: 0 means straight ahead, 90 to the right. The glyph is `location.north.fill` (an arrow pointing up) rotated clockwise by that angle, caption size, stone.

```swift
    /// Degrees clockwise from the walker's heading to the moment: the
    /// direction tick. Nil until both a fix and a settled compass exist.
    func relativeBearing(to moment: WayMoment) -> Double? {
        guard let here = currentLocation, let heading = headingDegrees, let there = coordinate(of: moment) else { return nil }
        let bearing = WayGeometry.bearing(from: CLLocationCoordinate2D(latitude: here.latitude, longitude: here.longitude), to: there)
        return (bearing - heading + 360).truncatingRemainder(dividingBy: 360)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:279-285@7c200bf

```swift
    /// Initial great-circle bearing from `from` to `to`, in degrees clockwise
    /// from true north, 0 ..< 360.
    static func bearing(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double {
        let lat1 = from.latitude * .pi / 180, lat2 = to.latitude * .pi / 180
        let dLon = (to.longitude - from.longitude) * .pi / 180
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        let degrees = atan2(y, x) * 180 / .pi
        return (degrees + 360).truncatingRemainder(dividingBy: 360)
    }
```
> Pilgrim/Models/Honor/WayGeometry.swift:205-214@7c200bf

**How it reads `HeadingProvider`.** Its own `CLLocationManager`, separate from the walk's location manager, with a 3° heading filter. A negative heading accuracy (uncalibrated) publishes nil, which hides the tick. True heading when valid, else magnetic. It starts with the engine at Begin (only if the device has a compass) and stops at teardown, which publishes nil. Values reach the view model on the main queue as `headingDegrees`, and every update re-renders the card.

```swift
final class HeadingProvider: NSObject, HeadingProviding, CLLocationManagerDelegate {

    private let manager = CLLocationManager()
    private let subject = CurrentValueSubject<Double?, Never>(nil)
    private var running = false

    var headingPublisher: AnyPublisher<Double?, Never> { subject.eraseToAnyPublisher() }

    override init() {
        super.init()
        manager.delegate = self
        manager.headingFilter = 3
    }

    deinit { manager.stopUpdatingHeading() }

    func start() {
        guard !running, CLLocationManager.headingAvailable() else { return }
        running = true
        manager.startUpdatingHeading()
    }

    func stop() {
        guard running else { return }
        running = false
        manager.stopUpdatingHeading()
        subject.send(nil)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        // A negative accuracy means the compass is not calibrated; the tick
        // hides rather than point somewhere wrong.
        guard newHeading.headingAccuracy >= 0 else { subject.send(nil); return }
        let heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        subject.send(heading)
    }
}
```
> Pilgrim/Models/Honor/HeadingProvider.swift:16-52@7c200bf

```swift
        // The compass lives exactly as long as the engine: started here,
        // stopped in teardown with the rest of the honor state.
        let heading = honorSenses.makeHeadingProvider()
        heading.headingPublisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.headingDegrees = $0 }
            .store(in: &honorCancellables)
        heading.start()
        honorHeading = heading
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:88-96@7c200bf

iOS sets no `headingOrientation`, so CoreLocation's default applies: the heading is where the top of the phone points in portrait. The provider runs for the whole walk, whether or not a card is showing, and in the background.

**Animation.** Each new angle eases in over 0.25 s (`easeOut`). The rotation interpolates the raw angle in [0, 360), so crossing straight ahead (359° → 1°) spins the arrow nearly a full turn backwards (iOS defects).

Android has no heading provider today; the card compass source is the U11 pin the plan defers ("the card compass's heading source: U11 checks iOS and matches it").

### 10. The stats sheet on an Honor walk: listening chip, Remaining, soft-tap caption

Three changes, all in the MINIMIZED bar only. The expanded sheet is unchanged on an honor walk: it still shows Distance, Steps, and Ascent, the timer, and the intention line (`WalkStatsSheet.swift:462-511@7c200bf`).

**The listening chip.** While a Way voice is the active voice, the chip sits above the glance row and the intention line hides. It sits outside the glance row's single VoiceOver element. The whole minimized bar, chip included, still expands the sheet when tapped outside a button.

```swift
    private var minimizedContent: some View {
        VStack(spacing: isLargeText ? 4 : 6) {
            // A voice from the Way takes the mantra's place while it plays:
            // it is the thing the walker may want to pause or let go of. It
            // sits outside the glance row's single VoiceOver element so its
            // pause and skip buttons stay reachable.
            if let voice = viewModel.activeVoice {
                HonorListeningChip(
                    elapsed: voicePlayer.elapsedSeconds,
                    isPaused: viewModel.isVoicePaused,
                    onPauseResume: { viewModel.togglePlayback(of: voice) },
                    onSkip: { viewModel.skipVoice() }
                )
            }

            glanceRow
        }
        .padding(.horizontal, Constants.UI.Padding.big)
        .padding(.top, Constants.UI.Padding.small)
        .padding(.bottom, Constants.UI.Padding.small)
        .frame(maxWidth: .infinity)
        // The wander tap-to-expand target: on the padded container, not the
        // inner glance row, so the full minimized bar — including its
        // padding — is tappable, matching the pre-honor target.
        .contentShape(Rectangle())
        .onTapGesture {
            state = .expanded
        }
    }

    private var glanceRow: some View {
        VStack(spacing: isLargeText ? 4 : 6) {
            if viewModel.activeVoice == nil, let intention = viewModel.intention, !intention.isEmpty {
```
> Pilgrim/Scenes/ActiveWalk/WalkStatsSheet.swift:328-360@7c200bf

```swift
struct HonorListeningChip: View {
    let elapsed: TimeInterval
    let isPaused: Bool
    let onPauseResume: () -> Void
    let onSkip: () -> Void

    var body: some View {
        HStack(spacing: Constants.UI.Padding.small) {
            Image(systemName: "waveform").foregroundColor(.stone)
            Text(isPaused ? "paused" : "listening").font(Constants.Typography.caption).foregroundColor(.fog)
            Text("\(Int(elapsed) / 60):\(String(format: "%02d", Int(elapsed) % 60))")
                .font(Constants.Typography.caption).foregroundColor(.ink).monospacedDigit()
            Button(action: onPauseResume) { Image(systemName: isPaused ? "play.fill" : "pause.fill") }
                .accessibilityLabel(isPaused ? "Resume their voice" : "Pause their voice")
            Button(action: onSkip) { Image(systemName: "forward.end.fill") }
                .accessibilityLabel("Skip this voice")
        }
        .foregroundColor(.stone)
        .padding(.horizontal, Constants.UI.Padding.normal).padding(.vertical, 6)
        .background(Capsule().fill(Color.parchmentSecondary))
    }
}
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:327-348@7c200bf

- Layout: `waveform` glyph (stone) · `"listening"` or `"paused"` (caption, fog) · `m:ss` (caption, ink, monospaced) · pause/resume button (`pause.fill` / `play.fill`) · skip button (`forward.end.fill`); 8 pt apart; stone buttons; capsule of parchmentSecondary, padding 16 × 6. The buttons have no frame, so their hit areas are the glyphs' own size.
- The clock is the player's once-a-second `elapsedSeconds` (observed directly: `@ObservedObject private var voicePlayer = WayVoicePlayer.shared`, `WalkStatsSheet.swift:48-50@7c200bf`).
- "paused" reflects `isVoicePaused`: the walker's pause or the engine's gate pause. A guide prompt holding the voice does not flip it (section 8).
- Pause/resume goes through `togglePlayback(of:)` (section 8). Skip:

```swift
    func skipVoice() {
        guard activeVoice != nil else { return }
        wayVoicePlayer?.stop()
        activeVoice = nil
        isVoicePaused = false
        honorEngine?.voiceDidFinish()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:421-427@7c200bf

Neither chip button touches the voice's card, and skip schedules no retirement (section 7). The chip has no replay or rate control; replay and rate live on the card. Plan U22 lists "pause, skip, replay, and rate commands" for the chip; on iOS the chip carries only pause/resume and skip.

**The third glance stat.** Steps on a wander walk; on an honor walk, the Way's distance remaining, labelled `"Remaining"`; while a soft-tap (or, on a stage, a water) caption is up, the caption replaces the stat.

```swift
    /// The bar's third glance stat: steps, except while honoring, where the
    /// walker's question is how much of the Way is left. The soft tap borrows
    /// the slot for its caption — with the preference off, nothing on screen
    /// ever comments on deviation.
    @ViewBuilder
    private var thirdStat: some View {
        if let caption = viewModel.softTapCaption {
            Text(caption)
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
                .lineLimit(2)
                .multilineTextAlignment(.center)
        } else if viewModel.mode == .honor {
            statColumn(value: distanceRemaining, label: "Remaining")
        } else {
            statColumn(value: viewModel.steps, label: "Steps")
        }
    }

    /// Republished by the view model's one-second duration tick rather than
    /// observed off the engine — a metre-by-metre countdown is not what this
    /// bar is for.
    private var distanceRemaining: String {
        guard let meters = viewModel.honorEngine?.distanceRemainingMeters else { return "--" }
        return StatsHelper.string(for: meters, unit: UnitLength.meters, type: .distance)
    }
```
> Pilgrim/Scenes/ActiveWalk/WalkStatsSheet.swift:405-430@7c200bf

- Before Begin (no engine) the value is `"--"`. At Begin it is the Way's full length (`distanceRemainingMeters = geometry.totalMeters`, `HonorEngine.swift:86@7c200bf`), then `(1 - progressFrac) × totalMeters` after each accepted fix (`HonorEngine.swift:155@7c200bf`).
- It is formatted with the app's shared distance formatter (`StatsHelper`), the same one the Distance stat uses; Android's counterpart is `WalkFormat.distance(distanceMeters, units)` (`ui/walk/WalkStatsSheet.kt:380-383@5ea4029b`). The Remaining column uses the same `statColumn` as the others: value in `statValue` (Lato 20, ink, monospaced digits), label in `statLabel` (Lato 12, fog) (`WalkStatsSheet.swift:391-403@7c200bf`).
- Android slot: `MinimizedContent`'s third `StatColumn` at `ui/walk/WalkStatsSheet.kt:384-387@5ea4029b`.

**The soft-tap caption.** Text `"off the way · N m"`, with N the off-way distance when the tap fired, truncated to whole metres and capped at 999 999. Always metres, whatever the unit preference (iOS defects). It retires itself after 20 s. It is generation-guarded against teardown, but a second caption does not reset the first one's timer. It shows only when the soft-tap preference was on at Begin (and never on a stage).

```swift
    private func showSoftTapCaption(meters: Double) {
        // `Int(_:)` traps on an infinity; the engine already clamps, and this
        // is the last line of defence before the number reaches the screen.
        softTapCaption = "off the way · \(Int(min(meters.isFinite ? meters : 0, 999_999))) m"
        let generation = honorGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.softTapCaptionSeconds) { [weak self] in
            guard let self, self.honorGeneration == generation else { return }
            self.softTapCaption = nil
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:236-245@7c200bf

```swift
    /// Not private: the water notice borrows this slot, so it borrows this life.
    static let softTapCaptionSeconds: TimeInterval = 20
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:457-458@7c200bf

```swift
            softTapEnabled: UserPreferences.honorSoftTapEnabled.value && !way.isPilgrimageStage,
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:57@7c200bf

The soft-tap event also fires the `honorOffWay` haptic, foreground only (`ActiveWalkViewModel+Honor.swift:200-202@7c200bf`). The caption is caption font, fog, up to 2 lines, centred.

**The minimized bar's VoiceOver value** carries the third stat too:

```swift
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Walk stats")
        .accessibilityValue(minimizedAccessibilityValue)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Double tap to show full stats and controls")
    }
    ...
    private var minimizedAccessibilityValue: String {
        let third: String
        if let caption = viewModel.softTapCaption {
            third = caption
        } else if viewModel.mode == .honor {
            third = "\(distanceRemaining) remaining"
        } else {
            third = "\(viewModel.steps) steps"
        }
        let stats = "\(viewModel.duration), \(viewModel.distance), \(third)"
        if let intention = viewModel.intention, !intention.isEmpty {
            return "\(intention). \(stats)"
        }
        return stats
    }
```
> Pilgrim/Scenes/ActiveWalk/WalkStatsSheet.swift:378-449@7c200bf

Note the VoiceOver value still prepends the intention while a voice plays, even though the intention text is hidden then.

### 11. The arrival card

Shown when arrival fires, in place of any place card, until "continue". Own walks show a title, the Way's title, and one counting line; the closing line and the reply row are stage-only.

```swift
struct HonorArrivalCardView: View {
    let card: HonorArrivalCard
    /// The stage's reply, when the walker has already recorded one.
    var existingReply: URL?
    var isRecordingReply = false
    var onReply: (() -> Void)?
    var onStopReply: (() -> Void)?
    var onPlayReply: ((URL) -> Void)?
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
            Text(Self.title(for: card)).font(Constants.Typography.heading).foregroundColor(.ink)
            Text(card.stageName ?? card.wayTitle).font(Constants.Typography.body).foregroundColor(.fog)
            Text(Self.line(for: card)).font(Constants.Typography.caption).foregroundColor(.fog)
            if let closing = card.closing {
                Text(closing)
                    .font(Constants.Typography.displayMedium)
                    .foregroundColor(.ink)
                replyRow
            }
            Button("continue", action: onDismiss).font(Constants.Typography.button).foregroundColor(.stone)
        }
        .padding(Constants.UI.Padding.normal)
        .background(RoundedRectangle(cornerRadius: Constants.UI.CornerRadius.normal).fill(Color.parchmentSecondary))
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:350-375@7c200bf

```swift
    static func title(for card: HonorArrivalCard) -> String {
        card.isStage ? "you walked the stage" : "you walked their way"
    }

    /// A stage counts places and kilometres; a shared walk counts voices and
    /// places, because a voice is what the other walker left.
    static func line(for card: HonorArrivalCard) -> String {
        var parts: [String] = []
        if !card.isStage, card.voicesHeard > 0 {
            parts.append(card.voicesHeard == 1 ? "one voice heard" : "\(card.voicesHeard) voices heard")
        }
        if card.placesPassed > 0 {
            parts.append(card.placesPassed == 1 ? "one place passed" : "\(card.placesPassed) places passed")
        }
        if card.isStage {
            parts.append(StatsHelper.string(for: card.distanceWalkedMeters, unit: UnitLength.meters, type: .distance))
        }
        if parts.isEmpty { return card.isStage ? "the whole stage" : "the whole way, in their steps" }
        return parts.joined(separator: " · ")
    }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:414-433@7c200bf

Own walk, top to bottom, 8 pt apart, inside 16 pt padding on a parchmentSecondary card with 12 pt corners (the same card shell as a place card, no swipe, no ×):
1. `"you walked their way"`: heading (Cormorant SemiBold 17), ink.
2. The Way's title: body (Cormorant 17), fog.
3. The counting line: caption, fog. `"one voice heard"` / `"N voices heard"`, then `"one place passed"` / `"N places passed"`, joined by `" · "`; `"the whole way, in their steps"` when both are zero.
4. `"continue"`: a plain text button, Lato Bold 17, stone.

The counts come from the walk's live sets at the moment of arrival: `voicesHeard` is every voice that started (including failed starts, section 8, and card replays), `placesPassed` is the non-voice moments the engine reached (not pin taps).

```swift
        honorArrival = HonorArrivalCard(
            wayTitle: way.title, voicesHeard: heardVoiceIDs.count,
            placesPassed: reachedMomentIDs.count,
            theirSeconds: theirSeconds, yourSeconds: yourSeconds,
            stageName: way.stage?.name,
            distanceWalkedMeters: honorEngine?.distanceWalkedMeters ?? 0,
            closing: way.stage?.closing)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:253-259@7c200bf

Persistence runs before the card: the `.honorArrival` event and the reserved waypoint are written first, then the card, then the `honorArrival` haptic (foreground only):

```swift
        case .arrived(let theirSeconds, let yourSeconds):
            recordHonorArrival(theirSeconds: theirSeconds, yourSeconds: yourSeconds)
            fireHonorHaptic(.honorArrival)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:208-210@7c200bf

The companion delta (`theirSeconds`, `yourSeconds`) is not shown on the walk; the summary reads it (U23). The arrival card has no animation or transition.

### 12. The meditation screen caption

When the sitting was started from a card's "Sit?", the meditation screen shows a static caption under the session timer: `"they sat here N minutes"` (singular `"minute"` for 1). Caption font, fog at 0.4. Not a countdown, and nothing ends the sitting at N.

```swift
    /// How long the walker being honored sat here. A caption under the
    /// elapsed time, never a countdown: this sitting has its own length.
    let suggestedMinutes: Int?
```
> Pilgrim/Scenes/ActiveWalk/MeditationView.swift:7-9@7c200bf

```swift
                    sessionTimer
                        .padding(.top, 8)

                    if let suggestedMinutes {
                        theirSittingCaption(minutes: suggestedMinutes)
                            .padding(.top, 6)
                    }

                    soundscapeLabel
                        .padding(.top, 8)
```
> Pilgrim/Scenes/ActiveWalk/MeditationView.swift:84-93@7c200bf

```swift
    private func theirSittingCaption(minutes: Int) -> some View {
        let text = "they sat here \(minutes) \(minutes == 1 ? "minute" : "minutes")"
        return Text(text)
            .font(Constants.Typography.caption)
            .foregroundColor(Color.fog.opacity(0.4))
            .accessibilityLabel(text)
    }
```
> Pilgrim/Scenes/ActiveWalk/MeditationView.swift:400-406@7c200bf

- Order: breath count (if a rhythm is set) → 8 pt → session timer (statValue, fog) → 6 pt → caption → 8 pt → soundscape label. Hidden during the closing summary (it sits in the non-summary branch, `MeditationView.swift:74-94@7c200bf`).
- The value is the view model's `suggestedMeditationMinutes`, handed to the cover as `suggestedMinutes: viewModel.suggestedMeditationMinutes` (`ActiveWalkView.swift:220-226@7c200bf`). Only the card's `startMeditation(minutes:)` sets it; the sheet's Meditate button calls `startMeditation()` (`ActiveWalkView.swift:553-556@7c200bf`), and every sitting's end clears it (section 8). So the caption shows for card-started sittings only.
- The text differs from the card's kicker for the same moment: the card says `"they sat here for N minutes"` (or `"about"` for estimates), the meditation screen says `"they sat here N minutes"`.
- Android slot: `MeditationScreenContent`, between the timer `Text` (`formatTimer(elapsedSeconds)`) and the `Spacer(Modifier.height(8.dp))` before the soundscape label (`ui/meditation/MeditationScreen.kt:744-749@5ea4029b`).

### 13. The photo plate and `WayPhotoViewer`

**The plate on the card.** Max height 110 on the walk card (the overview's preview uses the 160 default). The image is scaled to fit, full width up to that height, 4 pt corners, inside a 6 pt parchment mat with 6 pt corners. Tap opens the viewer. While loading, or if the image never loads, a plain parchment block 110 pt tall (`min(maxHeight, 120)`) with 6 pt corners stands in; it is not tappable and says nothing to VoiceOver.

```swift
struct WayPhotoPlate: View {
    let media: WayMedia
    let fileURL: URL?
    var maxHeight: CGFloat = 160
    @State private var image: UIImage?
    @State private var enlarged = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: maxHeight).cornerRadius(4)
                    .padding(6).background(Color.parchment).cornerRadius(6)
                    .onTapGesture { enlarged = true }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityLabel("Enlarge photo")
            } else {
                RoundedRectangle(cornerRadius: 6).fill(Color.parchment).frame(height: min(maxHeight, 120))
            }
        }
        // `fileURL` for a `.file` photo is nil on the first frame and filled
        // in by the host's own `.task(id:)` once the disk lookup resolves;
        // `.onAppear` alone would fire before that value ever arrives and
        // never re-fire when it does.
        .task(id: fileURL) { load() }
        .fullScreenCover(isPresented: $enlarged) {
            if let image {
                WayPhotoViewer(image: image) { enlarged = false }
            }
        }
    }

    private func load() {
        switch media {
        case .file:
            if let fileURL, let data = try? Data(contentsOf: fileURL) { image = UIImage(data: data) }
        case .photoAsset(let id):
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil)
            guard let asset = assets.firstObject else { return }
            let options = PHImageRequestOptions()
            options.deliveryMode = .opportunistic
            options.isNetworkAccessAllowed = false
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 900, height: 900),
                                                  contentMode: .aspectFit, options: options) { result, _ in
                guard let result else { return }
                DispatchQueue.main.async { image = result }
            }
        case .recording:
            break
        }
    }
}
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:274-325@7c200bf

- Own walk photos are `.photoAsset` (the walker's library item, `OwnWalkWayBuilder.swift:59-61@7c200bf`): requested at 900 × 900 aspect-fit, opportunistic delivery (a degraded image may arrive first, then the full one), never from the network. A photo since deleted from the library stays a blank plate. Android reads MediaStore/Photo Picker URIs instead (Stage 7-A precedent).
- `.file` photos (shared walks) are read with `Data(contentsOf:)` inside `.task`, which on this view runs on the main actor.
- The enlarged viewer gets the plate's already-loaded image; no higher-resolution reload.

**`WayPhotoViewer`.** A full-screen cover (the system's default slide-up presentation). Black, safe-area-filling ground. The image scaled to fit.

```swift
/// A photo filling the screen. Three ways out, because a full-screen cover
/// has no system dismissal of its own: the close button, a tap, or a
/// swipe down. Pinch to look closer; a tap while zoomed only zooms back.
struct WayPhotoViewer: View {
    let image: UIImage
    let onClose: () -> Void

    @State private var scale: CGFloat = 1
    @State private var dragOffset: CGFloat = 0

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .scaleEffect(scale)
                .offset(y: dragOffset)
                .gesture(
                    MagnificationGesture()
                        .onChanged { scale = max(1, min(4, $0)) }
                        .onEnded { _ in if scale < 1.05 { withAnimation { scale = 1 } } }
                )
                .simultaneousGesture(
                    DragGesture()
                        .onChanged { value in
                            if scale == 1 { dragOffset = max(0, value.translation.height) }
                        }
                        .onEnded { value in
                            if scale == 1, value.translation.height > 100 {
                                onClose()
                            } else {
                                withAnimation { dragOffset = 0 }
                            }
                        }
                )
                .onTapGesture {
                    if scale > 1 { withAnimation { scale = 1 } } else { onClose() }
                }
                .accessibilityAddTraits(.isButton)
                .accessibilityLabel("Close photo")
            Button(action: onClose) {
                Image(systemName: "xmark.circle.fill")
                    .font(Constants.Typography.displayMedium)
                    .foregroundColor(.white.opacity(0.85))
                    .padding(Constants.UI.Padding.normal)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Close photo")
        }
    }
}
```
> Pilgrim/Scenes/Honor/WayPhotoViewer.swift:3-55@7c200bf

Gestures:
- Pinch: zoom 1× to 4×, about the image's centre. Ending below 1.05× snaps back to 1× (SwiftUI default animation). Each pinch starts from the gesture's own 1.0, not from the current zoom (iOS defects).
- Drag down at 1×: the image follows the finger downward only (never up). Releasing past 100 pt closes; otherwise it springs back (default animation). A drag while zoomed does nothing: there is no panning of a zoomed image.
- Tap: zoomed → back to 1× (default animation); at 1× → close.
- Close button: top trailing, `xmark.circle.fill` at 28 pt (displayMedium), white at 0.85, 16 pt padding, 44 × 44 minimum.

Strings: `"Close photo"` (both the image and the button), `"Enlarge photo"` (the plate). There is no caption, title, or share action.

### 14. Accessibility

iOS posts no VoiceOver announcement when a moment is reached, a card rises, a voice starts, the soft-tap caption appears, or arrival lands (no `UIAccessibility.post` anywhere in these files). Nothing on these surfaces uses `accessibilitySortPriority`, a custom rotor, or a named action other than the waveform's adjustable action.

**Walk screen order.** VoiceOver follows the `ZStack` declaration order (section 1): map, top buttons, ambient buttons, turning watermark, then the Honor card, then the sheet. Hidden: the weather overlay, the floating greetings, the sparkline, the gradient (`ActiveWalkView.swift:118-122,452-471,517-541@7c200bf`).

**Place card**, in reading order:

| Element | Label | Value / hint / trait |
|---|---|---|
| Header button | `"Show this place on the map"`, or `"Back to where you are"` whenever any focus is set | button. The label REPLACES the header's content: the kicker, local name, distance, and place are not read (iOS defects). The tick is hidden. |
| Queue pips (when `pendingCount > 0`) | `"N more waiting"` (full count, not capped at 4) | none |
| × | `"Dismiss"` | button |
| Voice: transcript line | the quoted text | static text |
| Voice: play/pause | `"Play their voice"` / `"Pause their voice"` | button |
| Voice: waveform (once read) | `"Their voice; drag to move through it"` (overrides the component's `"Playback position"`) | value `"N percent"`; adjustable ±10 % per swipe (`steppedProgress`, `RecordingsListView.swift:571-593@7c200bf`). The placeholder bar has no label. |
| Voice: clock | `"m:ss / m:ss"` as text | static text |
| Voice: rate | `"Playback speed, 1x"` (etc.) | button |
| Voice: reply | `"Record a reply at this spot"`, or `"Record a new reply, replacing your earlier one"` | button; the second opens the dialog `"Replace your earlier reply?"` with `"Replace"` and `"Keep it"` |
| Voice: your reply | `"Play your earlier reply"` | button |
| Voice, recording a reply | `"recording your reply here"` text; stop button `"Stop recording your reply"` | the pulse is hidden |
| Photo | `"Enlarge photo"` | button trait (added to a tap gesture) |
| Rest | `"A pause in their walk. The companion waits here with you."` | static text |
| Sitting / waypoint sit row | `"Sit here for N minutes"`, then `"your soundscape holds while you sit"` | button, then text |
| Waypoint | the place copy | static text |

The swipe-to-dismiss has no accessibility action; the × button is the accessible path. The card is not grouped (`accessibilityElement(children: .contain)` is not applied), so its elements join the screen's flat order.

**Arrival card**: three static texts (title, Way title, counting line), then the `"continue"` button with its text as its label (no explicit label). Stage-only: the closing text, `"Record a reply to this stage"`, `"Stop recording your reply"`, `"Play your reply"` (`WayPlaceCard.swift:386-410@7c200bf`).

**Listening chip** (minimized sheet, before the stats element): the `waveform` image (no label set, so VoiceOver falls back to the symbol's name), `"listening"` / `"paused"`, the clock text, then buttons `"Pause their voice"` / `"Resume their voice"` and `"Skip this voice"`.

**Minimized stats**: one element, label `"Walk stats"`, value `"<time>, <distance>, <remaining> remaining"` (or the caption text in the third place, or `"<n> steps"` on a wander walk), prefixed by `"<intention>. "` when set; button trait; hint `"Double tap to show full stats and controls"` (section 10).

**Meditation caption**: label equal to its text, `"they sat here N minutes"` / `"… 1 minute"`.

**Photo viewer**: the image as a button labelled `"Close photo"`, then the close button `"Close photo"`.

**Map pins**: Way pins are Mapbox point annotations with no accessibility labels; iOS exposes no VoiceOver route to open a card from a pin. The only non-visual path to a card is the engine raising it.

Android: Robolectric semantics tests are the plan's check for these labels (`T/ui/honor/HonorOnWalkSemanticsTest.kt`, plan U22). The precedent `T/ui/settings/voiceguide/VoiceGuidePickerScreenTest.kt` is named in plan U21.

### 15. Stage and shared-walk branch points (one line each)

- **Card copy, `isStage`**: `WayPlaceCard(isStage: viewModel.way?.isPilgrimageStage == true)` switches only the waypoint place copy to `"A place on the way."` (`ActiveWalkView+Honor.swift:80@7c200bf`, `WayMomentHeader.swift:86-89@7c200bf`).
- **Stage sit offer**: `moment.sitMinutes` is set only by `PilgrimageWayImporter` (`PilgrimageWayImporter.swift:219@7c200bf`), so the waypoint card's sit row is stage-only.
- **Stage local names**: `moment.names` comes from stage data (`PilgrimageWayImporter.swift:218@7c200bf`).
- **Stage pins**: `moment.pin` is set only for stage waypoints (`PilgrimageWayImporter.swift:220@7c200bf`); the pin stands at `pin` while distance, tick, and fly-to use `at`.
- **Stage marks**: `honorMarkPins` and the `"water in <distance>"` caption exist only when `way.marks` is non-empty (`ActiveWalkViewModel+MarkPins.swift:51-70@7c200bf`).
- **No companion and no soft tap on a stage**: `companionCoordinate` returns nil and `softTapEnabled` is false when `way.isPilgrimageStage` (sections 3 and 10).
- **Arrival card**: `card.isStage` switches the title, the counting line, and adds the closing and reply row when `card.closing != nil` (section 11).
- **Stage day sheet**: `WalkOptionsSheet(stageDay: viewModel.way?.stage, …)` (`ActiveWalkView.swift:297-304@7c200bf`).
- **Shared walks**: `moment.place` (the street name in the subline), `moment.transcript` from the worker, `.file` media resolved under the Way's media folder (`ActiveWalkViewModel+Honor.swift:354-365@7c200bf`), and meditation estimates (`"they sat here about N minutes"`) arrive only with shared Ways.

### Resolutions for the plan

1. **Ghost line.** Source: the Way's `route`, cut at span boundaries into `LineString` features tagged `activityType` `"walking"` / `"talking"` / `"meditating"` (gaps are walking; overlapping spans resolved by a forward cursor). One `GeoJSONSource` `honor-way-source`, one `LineLayer` `honor-way-line`, width 4, round cap and join, no dash. Colour by span: dawn (meditating), rust (talking), moss (walking, never the turning colour), resolved the same way as the live route's three (Android: `RouteSegmentColors.Fixed`). Opacity 0.22 on the light map style, 0.4 on the dark. Z-order: below `pilgrim-route-casing`, else below the route line, else top; so under the walker's casing and route, the companion, and every pin. Drawn from the moment the walk screen appears (before Begin) until the screen goes away; never cleared at teardown; skipped while the map is not rendering and reinstalled on resume. (Sections 2, 5.)
2. **Companion.** Where the original walker was at the same active walking time: `geometry.frac(atElapsed: companionT0 + (activeDuration − anchorActiveDuration))`, anchored at the first accepted fix (frac 0 until then, and on the no-Way-within-60 m fallback until an on-Way fix). Rides the walk's active clock (pauses excluded, sittings included). `CircleLayer` `honor-companion`, radius 6, white stroke 1.5, pitch-aligned to the map; fill `#8A8175` at 0.6 (light) or `#D9CFBF` at 0.85 (dark); inserted directly above the route line. Moves by jumps at most every 2 s, no animation. Shown from Begin to teardown on own and shared walks; never on a stage. Not redrawn while backgrounded or meditating, but its position keeps advancing then. (Section 3.)
3. **Pins.** One faded pin per Way moment: voice (`waveform`; fog until heard, stone once heard), photo (`photo`, stone), rest (`cup.and.saucer`, stone), sitting (`circle.circle`, dawn), waypoint (its icon or `mappin`, stone); stage marks at 18 pt. All 22 pt: a light-parchment disc at 0.9 with the symbol at 55 % size and 0.55 alpha, light palette in both appearances. No active, reached, reply, or arrival Way pin exists; arrival adds a live waypoint `signpost.right.fill` (18 pt, stone, no disc) labelled `"Walked their way: %@"` (`honor.arrival.label`). All pins share one point manager above the route line (and above the companion); overlap order is Mapbox's. Tap: nearest tappable pin within 25 m on the ground; during an active walk its card jumps to the front of the queue; before Begin nothing; marks and waypoints are not tappable. (Section 4.)
4. **Install order and reloads.** Per pass: route → annotations → seek fog → Honor. On every style load (first load and each theme flip): Honor layers first, then wabi-sabi, route, managers, seek fog. Resulting stack on first appearance, after Begin: ghost < casing < route < companion < point manager < circle manager. After a mid-walk style reload the companion lands under the casing until the next background or meditation flush reinstalls it (iOS defects 1). The plan's "reinstall after the annotation managers" rule avoids that. A theme flip reinstalls both runtime layers with the other palette; pin rasters do not change. Both runtime layers self-heal through a `layerExists` probe. (Section 5.)
5. **Camera.** Follow-puck at zoom 16 with the sheet height as bottom padding. The only Honor behaviour: a card header tap sets `honorFocus` to the moment's `at` (else its route point); follow turns off and the camera eases there in 0.4 s at zoom 16, re-easing on every update pass while focused. The same header again, dismissing the top card, or teardown clears it and follow resumes. No Way-specific fit on the walk. (Section 6; Open questions 2.)
6. **`WayPlaceCard`.** Full tree, strings, fonts, sizes, and colours in section 8; the host's precedence, queue, 20 s retirement rule, and file resolution in section 7; the listening chip in section 10; the arrival card in section 11. Queue indicator: up to four 5 pt stone dots, not "+N more". Dismiss: × (44 pt) or a sideways swipe past 80 pt. No transitions. The failed-voice state is the plain not-playing state; a missing file's card has an inert play button.
7. **Heading tick.** Points from the walker's last fix to the moment's `at` (else route point), relative to the compass heading: `(bearing − heading + 360) mod 360`. `HeadingProvider`: its own `CLLocationManager`, heading filter 3°, true heading else magnetic, nil (tick hidden) when accuracy is negative, default portrait orientation, started at Begin and stopped at teardown. `location.north.fill`, caption size, stone, rotated by the angle with `easeOut(duration: 0.25)`; shown only when the subline exists. (Section 9.)
8. **Captions.** Stats sheet (minimized bar only): third stat `"Remaining"` with the Way's distance left (`"--"` before Begin); the soft-tap caption `"off the way · N m"` replaces it for 20 s (caption, fog, 2 lines, centred). Meditation screen: `"they sat here N minutes"` / `"… 1 minute"`, caption, fog at 0.4, 6 pt under the session timer, only for card-started sittings. (Sections 10, 12.)
9. **`WayPhotoViewer`.** Full-screen cover on black; pinch 1–4× (snaps back under 1.05×); drag down past 100 pt at 1× closes; tap closes (or un-zooms); close button `xmark.circle.fill` 28 pt, white 0.85, top trailing; label `"Close photo"` on both the image and the button; the card's plate is labelled `"Enlarge photo"`. (Section 13.)
10. **Accessibility.** Every label, value, hint, and trait is tabulated in section 14. No announcements, no grouping, no sort priorities; pins are not accessible.

Plan questions this cluster also settles:

11. **"+N more" (U22 Approach).** iOS shows pips, capped at four, with the VoiceOver label `"N more waiting"` (section 8).
12. **"An untouched voice card retires 20 s after its persisted voice end" (U22).** True only for a voice that ended on its own or failed to start or decode. A skipped, dropped, replaced, or reply-interrupted voice's card never self-retires; non-voice cards never do (section 7).
13. **"A voice that fails to play shows on its card as U11 pins" (U22, U11).** The card shows the ordinary not-playing state, no failure copy; the voice already counts as heard and the card retires after 20 s if untouched (section 8).
14. **"Sit?" while paused (U11, U22).** Allowed: the card shows in `.paused` and `.autoPaused`, and "Sit?" stops any recording, starts the sitting, and opens the meditation screen; the minutes feed only the caption (section 8).
15. **The card compass's heading source (U11).** Resolution 7.
16. **The chip's commands (U22 lists pause, skip, replay, rate).** iOS's chip has only pause/resume and skip; replay (play again) and rate live on the card (section 10).
17. **`playReply` as the active voice (U11), UI side.** During a reply `activeVoice` is nil: the chip hides and no card shows as playing, and the reply does not touch the heard set. It does play through the Way voice player, which sets `isPlayingWayVoice = true` for any file (`WayVoicePlayer.swift:171-172@7c200bf`), so it holds whispers like a voice. When it ends, the engine gets its turn (`voiceDidFinish()`). The gate side belongs to the audio cluster.
18. **Playback-rate lifetime (U11).** The pill resets to 1× at every walk (`@Published var voiceRate: Float = 1`, reset in teardown). The shared player keeps the last rate for the life of the app process and applies it to the next walk's first voice (iOS defects 8). Match the pill's per-walk reset; file the player half upstream.
19. **"Frozen while paused, backgrounded, or meditating" (U22).** The drawn dot, yes. The position freezes only during a pause (section 3).
20. **Route layer naming.** iOS anchors the ghost on `pilgrim-route-casing` (shared by both platforms) and the companion on `pilgrim-route-layer`, which Android calls `pilgrim-route-line`.

### iOS defects found

Candidate upstream issues, not filed.

1. **Companion drawn under the walker's route after a mid-walk style reload.** On style load the Honor layers are reinstalled before the route source exists, so the companion's `.above("pilgrim-route-layer")` falls back to the top of the stack, and the route layers are then added above it.
   ```swift
            Self.reinstallHonorWay(on: mapView, coordinator: coordinator)
            ...
            Self.applyRouteSource(coordinator.pendingSegments, walkingColor: coordinator.walkingColor, on: mapView, coordinator: coordinator)
   ```
   > Pilgrim/Views/PilgrimMapView.swift:172-187@7c200bf

   ```swift
            let position: LayerPosition? = mapView.mapboxMap.layerExists(withId: "pilgrim-route-layer")
                ? .above("pilgrim-route-layer") : nil
   ```
   > Pilgrim/Views/PilgrimMapView+HonorWay.swift:259-260@7c200bf

   Impact: after a light/dark flip during an honor walk, the walker's 6 pt route line (and its 10 pt casing) draws over the companion wherever they overlap, until the next background or meditation round trip reinstalls it. Low.

2. **The fly-to may fight the follow-puck viewport.** Setting `honorFocus` turns `followsUserLocation` off, but iOS only flips `isFollowing` and calls `camera.ease`; it never calls `viewport.idle()`. In the pinned SDK (11.20.0), a follow-puck state keeps writing the camera on every puck render (`CameraViewportState.startUpdatingCamera` → `mapboxMap.setCamera(to:)`), and the viewport goes idle only on a user gesture or an explicit `idle()`.
   ```swift
        } else {
            context.coordinator.isFollowing = false
            context.coordinator.lastBottomInset = bottomInset
   ```
   > Pilgrim/Views/PilgrimMapView.swift:252-254@7c200bf

   Impact: while the walker is moving, "Show this place on the map" may snap back to the walker or jitter between the two targets. Also, while focused, the camera re-eases every second, so the walker cannot pan away. Needs an iPhone check (Open questions 2).

3. **The card header's VoiceOver label hides what the card is.** The header button's label replaces its whole content, so VoiceOver never reads the kicker ("spoken here", "they rested here 12 minutes", a waypoint's label), the local name, the distance ("120 m away", "here"), or the street.
   ```swift
                .accessibilityLabel(isFocused ? "Back to where you are" : "Show this place on the map")
   ```
   > Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:62@7c200bf

   Impact: a VoiceOver walker hears "Show this place on the map, button" with no idea which place or how far. Medium.

4. **"Back to where you are" on a card that did not fly the map.** `isFocused` is "any focus", while the tap compares this moment's coordinate.
   ```swift
                    isFocused: viewModel.honorFocus != nil,
   ```
   > Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:94@7c200bf

   Impact: when a newer card rises while the map shows an older card's moment, its header announces "Back to where you are" but a tap flies to the new moment instead. Low.

5. **The soft-tap caption ignores the distance unit.** It always says metres, while the water caption beside it uses the walker's unit.
   ```swift
        softTapCaption = "off the way · \(Int(min(meters.isFinite ? meters : 0, 999_999))) m"
   ```
   > Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:239@7c200bf

   ```swift
        softTapCaption = "water in \(WayDistance.string(meters: max(0, meters.isFinite ? meters : 0)))"
   ```
   > Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+MarkPins.swift:64@7c200bf

   Impact: a walker set to miles reads "off the way · 250 m". Low to medium.

6. **"1 minutes" and "0 minutes" on rest and sitting moments.** The kicker has no singular, and own-walk sittings have no minimum length, so a sitting under 30 s rounds to 0.
   ```swift
        case .rest(let minutes): return "they rested here \(minutes) minutes"
        case .meditation(let minutes, let isEstimate):
            return isEstimate ? "they sat here about \(minutes) minutes" : "they sat here for \(minutes) minutes"
   ```
   > Pilgrim/Scenes/Honor/WayMomentHeader.swift:62-64@7c200bf

   ```swift
                kind: .meditation(minutes: Int((sit.endDate.timeIntervalSince(sit.startDate) / 60).rounded()),
                                  isEstimate: false)))
   ```
   > Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:88-89@7c200bf

   The sitting card's sit row has no `minutes > 0` guard (the waypoint path does, `WayPlaceCard.swift:123@7c200bf`). Impact: "they sat here for 0 minutes", "Sit here for 0 minutes", and a meditation caption "they sat here 0 minutes"; a 1-minute sitting reads "they sat here for 1 minutes" on the card but "they sat here 1 minute" on the meditation screen. A shared rest can also be 0 (`kind: .rest(minutes: e.minutes ?? 0)`, `WayImporter.swift:163@7c200bf`). Low.

7. **The heading tick spins the long way round.** The angle lives in [0, 360) and is animated as a plain number.
   ```swift
                                .rotationEffect(.degrees(tick))
                                .animation(.easeOut(duration: 0.25), value: tick)
   ```
   > Pilgrim/Scenes/Honor/WayMomentHeader.swift:37-38@7c200bf

   Impact: when the moment passes straight ahead (359° → 1°), the arrow whirls almost a full turn backwards in 0.25 s. Low.

8. **The rate pill says 1× while the voice plays at the last walk's speed.** The view model resets its rate every walk; the shared player never does, and applies its own to each new voice.
   ```swift
        voiceRate = 1
   ```
   > Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:152@7c200bf

   ```swift
            p.rate = playbackRate
   ```
   > Pilgrim/Models/Honor/WayVoicePlayer.swift:165@7c200bf

   Impact: after choosing 2× on one walk, the next honor walk in the same app session plays at 2× under a "1x" pill; the first tap then jumps to 1.25×. Low to medium.

9. **"your reply" plays with no control or state on screen.** `playReply` clears `activeVoice`, which hides the chip and leaves every card showing "not playing".
   ```swift
        wayVoicePlayer?.stop()
        activeVoice = nil
        isVoicePaused = false
        wayVoicePlayer?.play(url: url, volume: Float(UserPreferences.voiceGuideVolume.value))
   ```
   > Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:71-74@7c200bf

   Impact: once started, the reply can be stopped only by playing some voice from a card. Low.

10. **"reply here" during an ordinary recording files that recording as the reply.** If the recorder is already running, `replyHere` re-targets it instead of starting a new take.
    ```swift
        pendingReplyOrigin = voice
        if !isRecordingVoice { toggleVoiceRecording() }
    ```
    > Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:21-22@7c200bf

    Impact: a reflection begun minutes earlier elsewhere is saved as the reply to this voice, and the card at once shows "recording your reply here". Low.

11. **Skipped, dropped, or replaced voices leave a card to close.** Only `onFinished` schedules retirement, and `stop()` finishes without notifying.
    ```swift
    func stop() {
        pending = nil
        player?.stop()
        finish(notify: false)
    }
    ```
    > Pilgrim/Models/Honor/WayVoicePlayer.swift:109-113@7c200bf

    This contradicts the stated intent "so an unanswered voice never leaves a card to close" (`ActiveWalkViewModel+Honor.swift:226-228@7c200bf`). Impact: a voice skipped from the chip, or dropped when the walker has moved 300 m on, keeps its card until dismissed. Low.

12. **A voice that fails to play counts as heard, silently.** `startVoice` marks it heard and turns its pin before `play`; a failed start then finishes and the card rises as an ordinary unplayed voice (section 8). A missing file's card, opened from its pin, has a play button that does nothing (`ActiveWalkViewModel+Honor.swift:396@7c200bf`). Impact: "3 voices heard" on the arrival card can include a voice nobody heard; no message explains a silent play button. Low.

13. **Photo viewer zoom does not accumulate and cannot pan.** Each pinch's value starts at 1.0, and drags are ignored while zoomed.
    ```swift
                        .onChanged { scale = max(1, min(4, $0)) }
    ...
                            if scale == 1 { dragOffset = max(0, value.translation.height) }
    ```
    > Pilgrim/Scenes/Honor/WayPhotoViewer.swift:23,29@7c200bf

    Impact: a second pinch snaps back toward 1× before zooming; a zoomed photo shows only its centre. Low.

14. **The listening chip's buttons are glyph-sized.** No frame or content shape, unlike every other Honor control's 44 pt minimum.
    ```swift
            Button(action: onPauseResume) { Image(systemName: isPaused ? "play.fill" : "pause.fill") }
    ```
    > Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:339@7c200bf

    Impact: a hard target while walking. Low.

15. **Stage only: the meditation caption says "they" on a stage.** A stage waypoint's `sitMinutes` feeds the same caption, though the card's own rule is that a stage "never says 'they'" (`WayPlaceCard.swift:9-11@7c200bf`).
    ```swift
        let text = "they sat here \(minutes) \(minutes == 1 ? "minute" : "minutes")"
    ```
    > Pilgrim/Scenes/ActiveWalk/MeditationView.swift:401@7c200bf

    Impact: "they sat here 20 minutes" for a dataset's suggested sit. Low. (Stage spec.)

16. **Stage only, possibly intended: the card's distance, tick, and fly-to aim at the trail projection, not the pin.** Pins stand at `pin ?? at`; `coordinate(of:)` uses `at` only.
    ```swift
        if let at = moment.at { return CLLocationCoordinate2D(latitude: at.lat, longitude: at.lon) }
    ```
    > Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:301@7c200bf

    Impact: "Show this place on the map" can centre on a spot of trail with no pin, up to hundreds of metres from the pin. Needs the stage spec's judgement.

### Open questions

1. **Which variant of dawn, rust, and moss the ghost gets on the dark style.** iOS passes the adaptive asset colours into the match expression, and Mapbox reads `color.cgColor` at install time (`StyleColor.init(_ color: UIColor)` in the pinned SDK); which appearance that resolves to depends on UIKit's current trait context during the style callback. The route layer has the same property, and Android already pinned the route's three to the light values. Keeping the ghost on the same three values as the route is the consistent choice; iOS does not settle it.
2. **Whether the fly-to holds while walking on an iPhone** (iOS defects 2). The code path says the follow-puck state keeps writing the camera; only a device run shows how visible that is. Android should decide at U22 whether to idle its viewport on focus, and record that as parity or divergence once iOS is checked.
3. **Overlap order among Way pins.** iOS sets no sort key on its single point manager, so which of two overlapping pins draws on top is Mapbox's default symbol ordering. iOS gives no rule to match.

## F. Doors into Honor, and the overview

iOS pin: `pilgrim-ios` @ `7c200bf`. Android HEAD: `5ea4029b`. Scope: the own-walk doors into Honor and the Honor overview (feeds U21). Shared-walk rows, links, and paste import (U26–U28) and the pilgrimage door and stage surfaces (Stage 21-2) are named only at their branch points.

Read in full at the pin: `Pilgrim/Scenes/Honor/HonorWaysSheet.swift`, `Pilgrim/Scenes/Honor/HonorOverviewView.swift`, `Pilgrim/Scenes/Honor/WayMomentPreview.swift`, `Pilgrim/Scenes/Home/WalkStartView.swift`, `Pilgrim/Scenes/Home/HomeView.swift`, `Pilgrim/Scenes/Home/HomeViewModel.swift`, `Pilgrim/Scenes/Root/MainCoordinatorView.swift`, `Pilgrim/Views/WalkModeFootprints.swift`. Read in part: `Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift` (the door), `Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift` (it has no Honor code; see §15).

Read in support, because the cluster's behavior lives there: `Pilgrim/Scenes/Root/MainTabView.swift` (every Honor sheet is presented here, not in `MainCoordinatorView`), `Pilgrim/Models/Walk/WalkMode.swift`, `Pilgrim/Support Files/Base.lproj/Localizable.strings`, `Pilgrim/Scenes/Honor/WayMomentHeader.swift`, `Pilgrim/Scenes/Honor/WayPhotoViewer.swift`, `WayPhotoPlate` in `Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift`, `Pilgrim/Views/PilgrimMapView.swift` (the fit), `Pilgrim/Views/PilgrimMapView+HonorWay.swift` (`wayPins`), `Pilgrim/Models/Walk/MapManagement/PilgrimAnnotation.swift`, `Pilgrim/Models/Honor/HonorImportReducer.swift`, `Pilgrim/Models/Honor/OwnWalkWayBuilder.swift` (rejection rules only), `Pilgrim/Models/Honor/Way.swift`, `Pilgrim/Models/Honor/HonorTuning.swift`, `Pilgrim/Scenes/WalkSummary/AudioPlayerModel.swift`, `WaveformBarView` in `Pilgrim/Scenes/Settings/RecordingsListView.swift`, `Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift`, `ActiveWalkViewModel.swift`, `ActiveWalkViewModel+Honor.swift`, `WalkStatsSheet.swift` (Begin to Start), `Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift` (`settingNavRow`), `Pilgrim/Models/Preferences/UserPreferences.swift`, `Pilgrim/Models/Weather/WeatherService.swift`, `Pilgrim/Models/Formatting/CustomMeasurementFormatting.swift`, `Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift` (the archive strip), `Pilgrim/Scenes/Settings/WaysListView.swift` (confirmed not a door), and the tests `UnitTests/Honor/WalkModeTests.swift`, `HonorOverviewModelTests.swift`, `MainCoordinatorHonorTests.swift`.

Colour names (`.stone`, `.fog`, `.ink`, `.parchment`, `.rust`, `.parchmentSecondary`) are the app's asset colours; Android's `pilgrimColors` carries the same names (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Color.kt:22-31@5ea4029b`).

### 1. Tokens this cluster uses

Every padding, radius, and font below resolves through these. The skill's `design-tokens.md` table is stale for typography; these are the pin's values.

```swift
        public enum Padding {
            public static let xs: CGFloat = 4
            public static let small: CGFloat = 8
            public static let normal: CGFloat = 16
            public static let big: CGFloat = 24
            public static let breathingRoom: CGFloat = 64
        }

        public enum CornerRadius {
            public static let small: CGFloat = 8
            public static let normal: CGFloat = 12
            public static let big: CGFloat = 20
        }
```
> Pilgrim/Models/Constants.swift:9-21@7c200bf

```swift
        public static let displayMedium: Font = .custom("CormorantGaramond-Light", size: 28)
        public static let heading: Font = .custom("CormorantGaramond-SemiBold", size: 17)
        ...
        public static let body: Font = .custom("CormorantGaramond-Regular", size: 17)
        public static let button: Font = .custom("Lato-Bold", size: 17)
        public static let caption: Font = .custom("Lato-Regular", size: 12)
        public static let annotation: Font = .custom("CormorantGaramond-Regular", size: 11)
```
> Pilgrim/Models/Constants.swift:62-70@7c200bf

Android's `pilgrimType` has `displayMedium`, `heading`, `body`, `button`, `caption`, `annotation` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Type.kt:18-26@5ea4029b`).

### 2. The doors, at a glance

There are exactly two doors into Honor for an own walk. Both end at the same overview.

| Door | Where | Label | Condition to show | Leads to |
|---|---|---|---|---|
| Path tab, Honor slot | `WalkStartView` bottom button | `"Honor"` (VoiceOver: `"Begin your journey"`) | always (`isAvailable` is `true`) | the Ways sheet (`"Choose a way"`) → `"Walk one of yours again"` → the picker (`"Walk again"`) → a walk row → the overview |
| Summary | `WalkSummaryView` share card, last | `"walk this again"` + `signpost.right` | the walk's route has ≥ 2 points and the host passed `onWalkAgain` | the overview directly, after the summary closes |

No other surface opens the sheet or the overview. The only callers of `chooseWay()` and the overview presenters:

```swift
                WalkStartView(onStartWalk: { mode in
                    if mode == .honor {
                        coordinator.chooseWay()
                    } else {
                        coordinator.startWalk(mode: mode)
                    }
                })
```
> Pilgrim/Scenes/Root/MainTabView.swift:22-28@7c200bf

```swift
        .sheet(item: $coordinator.completedSnapshot, onDismiss: {
            coordinator.handleSummaryDismiss()
        }) { snapshot in
            WalkSummaryView(walk: snapshot, onWalkAgain: { coordinator.walkAgain($0) })
                .constellationDecorated(nebulae: false)
        }
```
> Pilgrim/Scenes/Root/MainTabView.swift:58-63@7c200bf

```swift
            .sheet(item: $selectedWalk, onDismiss: onSummaryDismiss) { walk in
                WalkSummaryView(walk: walk, onWalkAgain: onWalkAgain)
            }
```
> Pilgrim/Scenes/Home/HomeView.swift:59-61@7c200bf

The Settings → Recordings list hosts a summary with no door:

```swift
            WalkSummaryView(walk: walk)
```
> Pilgrim/Scenes/Settings/RecordingsListView.swift:56@7c200bf

The Settings → Ways list is not a door either: its rows are plain `VStack`s with swipe-to-delete, no tap action (`Pilgrim/Scenes/Settings/WaysListView.swift:45-57@7c200bf`). The journal's ink-scroll quick view has no door; it only wears the mode glyph (§16).

Later-slice doors, named only: a tapped or pasted walk link (`openWay(shareId:)`, U26–U28) and the pilgrimage row inside the Ways sheet (Stage 21-2).

### 3. The Path tab mode slot

#### 3.1 The mode model

Honor is the middle slot. All three modes are available; there is no "coming soon" mode at the pin.

```swift
enum WalkMode: String, CaseIterable {
    case wander, honor, seek

    var subtitle: String {
        switch self {
        case .wander: return "walk · talk · meditate"
        case .honor: return "walk in their steps"
        case .seek: return "follow the unknown"
        }
    }

    var buttonLabel: String {
        switch self {
        case .wander: return "Wander"
        case .honor: return "Honor"
        case .seek: return "Seek"
        }
    }

    var isAvailable: Bool { true }

    var quotes: [String] {
        switch self {
        case .wander: return (1...6).map { LS["Welcome.Quote.\($0)"] }
        case .honor: return (1...3).map { LS["Honor.Quote.\($0)"] }
        case .seek: return (1...3).map { LS["Seek.Quote.\($0)"] }
        }
    }
}
```
> Pilgrim/Models/Walk/WalkMode.swift:3-31@7c200bf

The test pins the order, availability, subtitle, and button label:

```swift
    func testHonorIsTheThirdMode() {
        XCTAssertEqual(WalkMode.allCases, [.wander, .honor, .seek])
        XCTAssertTrue(WalkMode.honor.isAvailable)
        XCTAssertEqual(WalkMode.honor.subtitle, "walk in their steps")
        XCTAssertEqual(WalkMode.honor.buttonLabel, "Honor")
    }
```
> UnitTests/Honor/WalkModeTests.swift:6-11@7c200bf

The button label was `"Choose a way"` until `3e9e67d` ("fix(honor): name the mode on its button, and say what link the field wants", 2026-09-14, an ancestor of the pin), which changed it to `"Honor"`. The pin ships `"Honor"`.

The Honor quote pool (localization keys `Honor.Quote.1`–`Honor.Quote.3`):

```swift
// Honor quotes
"Honor.Quote.1" = "Where they walked,\nyou walk";
"Honor.Quote.2" = "Two traveling together";
"Honor.Quote.3" = "Their steps\nare still warm";
```
> Pilgrim/Support Files/Base.lproj/Localizable.strings:164-167@7c200bf

A quote is drawn at random on appear and on every mode change:

```swift
        .onAppear {
            currentQuote = selectedMode.quotes.randomElement() ?? ""
            ...
        }
        .onChange(of: selectedMode) { _, mode in
            currentQuote = mode.quotes.randomElement() ?? ""
        }
```
> Pilgrim/Scenes/Home/WalkStartView.swift:34-42@7c200bf

#### 3.2 The selector row

Order is `WalkMode.allCases`: WANDER, HONOR, SEEK. Each slot is a button: footprint glyph (60×50), then the uppercased raw value, then a 2 pt underline.

```swift
    private var modeSelector: some View {
        VStack(spacing: Constants.UI.Padding.small) {
            HStack(spacing: Constants.UI.Padding.small) {
                ForEach(WalkMode.allCases, id: \.self) { mode in
                    Button {
                        selectedMode = mode
                    } label: {
                        VStack(spacing: Constants.UI.Padding.small) {
                            footprintForMode(mode)

                            VStack(spacing: Constants.UI.Padding.xs) {
                                Text(mode.rawValue.uppercased())
                                    .font(Constants.Typography.button)
                                    .foregroundColor(mode == selectedMode ? .stone : .fog.opacity(0.55))
                                    .lineLimit(1)
                                trailUnderline(for: mode)
                                    .frame(height: 2)
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityLabel(mode.rawValue)
                    .accessibilityAddTraits(mode == selectedMode ? .isSelected : [])
                }
            }
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)

            Text(selectedMode.isAvailable ? selectedMode.subtitle : "coming soon")
                .font(Constants.Typography.caption)
                .foregroundColor(.fog.opacity(0.5))
                .minimumScaleFactor(0.7)
                .lineLimit(1)
                .contentTransition(.opacity)
                .animation(.easeInOut(duration: 0.3), value: selectedMode)
        }
    }
```
> Pilgrim/Scenes/Home/WalkStartView.swift:315-351@7c200bf

So the Honor slot reads `"HONOR"`, and its VoiceOver label is the lowercase raw value `"honor"` with the Selected trait when chosen. The `"coming soon"` branch is dead at the pin (`isAvailable` is always `true`); it survives only as code.

The Honor underline, shown only when Honor is selected:

```swift
            case .honor:
                LinearGradient(
                    colors: [.stone.opacity(0.3), .stone, .stone.opacity(0.3)],
                    startPoint: .leading,
                    endPoint: .trailing
                )
```
> Pilgrim/Scenes/Home/WalkStartView.swift:363-368@7c200bf

#### 3.3 The Honor footprint (path screen)

One mirrored print and a staff, bottom-aligned, 4 pt apart:

```swift
    /// One print and a staff beside it: dōgyō ninin, two traveling together.
    private var honorFootprints: some View {
        HStack(alignment: .bottom, spacing: 4) {
            FootprintShape()
                .fill(Color.ink.opacity(0.08))
                .frame(width: 16, height: 26)
                .scaleEffect(x: -1)
                .rotationEffect(.degrees(-12))
            StaffGlyph()
                .stroke(Color.ink.opacity(0.10), style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .frame(width: 10, height: 34)
        }
    }
```
> Pilgrim/Scenes/Home/WalkStartView.swift:253-265@7c200bf

The staff shape. The crossbar's `+ 2` / `- 2` are absolute points, not fractions of the frame, so the tilt is 4 pt at every size:

```swift
/// A walking staff: one leaning stroke with a short crossbar near the top.
struct StaffGlyph: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + rect.width * 0.65, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + rect.width * 0.35, y: rect.maxY))
        let barY = rect.minY + rect.height * 0.18
        path.move(to: CGPoint(x: rect.minX, y: barY + 2))
        path.addLine(to: CGPoint(x: rect.maxX, y: barY - 2))
        return path
    }
}
```
> Pilgrim/Scenes/Home/WalkStartView.swift:416-427@7c200bf

The shared frame, scale, and fade rules for every mode's glyph; decorative for VoiceOver:

```swift
        .frame(width: 60, height: 50)
        .scaleEffect(isActive && footprintVisible ? footprintBreathScale : (isActive ? 1.08 : 0.92))
        .opacity(isActive && footprintVisible ? 1.0 : 0.0)
        .accessibilityHidden(true)
```
> Pilgrim/Scenes/Home/WalkStartView.swift:233-236@7c200bf

Honor has no motion of its own (the removed Together glyph drifted; `40ef3fc` deleted that). It shares only the breath (`footprintBreathScale` to `1.01`, `easeInOut(duration: 4.0)` forever, started 1.3 s after entrance, `WalkStartView.swift:405-412@7c200bf`) and the mode-swap cadence:

```swift
        .onChange(of: selectedMode) { _, newMode in
            if UIAccessibility.isReduceMotionEnabled {
                withAnimation(.linear(duration: 0.2)) {
                    activeMode = newMode
                }
                return
            }
            transitionGeneration += 1
            let gen = transitionGeneration
            withAnimation(.easeIn(duration: 0.3)) {
                footprintVisible = false
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                guard transitionGeneration == gen else { return }
                activeMode = newMode
                haptic.impactOccurred()
                withAnimation(.easeOut(duration: 0.3)) {
                    footprintVisible = true
                }
            }
        }
```
> Pilgrim/Scenes/Home/WalkStartView.swift:43-63@7c200bf

#### 3.4 The Honor atmosphere

```swift
    private var modeAtmosphere: some View {
        Group {
            switch selectedMode {
            case .wander:
                Color.clear
            case .honor:
                Color.stone.opacity(0.015)
            case .seek:
                Color.fog.opacity(0.01)
            }
        }
        .animation(.easeInOut(duration: 0.6), value: selectedMode)
    }
```
> Pilgrim/Scenes/Home/WalkStartView.swift:121-133@7c200bf

#### 3.5 The bottom button

With Honor selected it reads `"Honor"`, is enabled, and is stone. Tapping it calls `onStartWalk(.honor)`, which the host routes to `chooseWay()` (§2), not to a walk start.

```swift
            Button(action: { onStartWalk(selectedMode) }) {
                Text(selectedMode.buttonLabel)
                    .font(Constants.Typography.button)
                    .minimumScaleFactor(0.8)
                    .lineLimit(1)
                    .foregroundColor(.parchment)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(selectedMode.isAvailable ? Color.stone : Color.fog.opacity(0.2))
                    .cornerRadius(Constants.UI.CornerRadius.normal)
                    .contentTransition(.interpolate)
            }
            .disabled(!selectedMode.isAvailable)
            .shadow(color: .stone.opacity(selectedMode.isAvailable ? 0.2 : 0), radius: 8, x: 0, y: 3)
            .shadow(color: .stone.opacity(selectedMode.isAvailable ? 0.12 * glowScale : 0), radius: 20 * glowScale, x: 0, y: 0)
            .animation(.easeInOut(duration: 0.3), value: selectedMode.isAvailable)
            .accessibilityLabel("Begin your journey")
            .accessibilityIdentifier("start_walk_button")
```
> Pilgrim/Scenes/Home/WalkStartView.swift:198-215@7c200bf

The VoiceOver label is `"Begin your journey"` for every mode; the visible `"Honor"` is not read.

#### 3.6 Android at `5ea4029b` (for the implementer)

- The slot is `WalkMode.Together`, unavailable: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkMode.kt:10-13@5ea4029b`. Strings: `path_mode_together` "TOGETHER", `path_mode_together_subtitle` "walk with others nearby", `path_button_together` "Walk Together", `path_mode_unavailable_subtitle` "coming soon", quotes `path_quotes_together` (`app/src/main/res/values/strings.xml:577-587,605-609@5ea4029b`).
- The coming-soon gate is `enabled = selectedMode.isAvailable && !isInProgress` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/path/WalkStartScreen.kt:305@5ea4029b`) and the subtitle switch (`WalkStartScreen.kt:406-421@5ea4029b`).
- Existing drift in this same slot, outside Honor: the unselected label is `pilgrimColors.fog.copy(alpha = 0.3f)` (`WalkStartScreen.kt:458@5ea4029b`); iOS moved to `.fog.opacity(0.55)` in `cbd24fc` (shipped since iOS v1.7.0). The start button sets no content description, although `path_start_a11y` "Begin your journey" exists and nothing references it (`strings.xml:587@5ea4029b`). The mode buttons use `Role.Tab` with the visible uppercase text; iOS labels them with the lowercase raw value (§17.1).
- The Together glyph and atmosphere to replace: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/path/PathFootprints.kt:79,104@5ea4029b` and `app/src/main/java/org/walktalkmeditate/pilgrim/ui/path/PathBackgroundLayers.kt:129@5ea4029b` (`dawn` at 0.01; iOS Honor is `stone` at 0.015). The 0.45 s swap is already `MODE_TAP_DISSOLVE_MS = 450L` (`WalkStartScreen.kt:76@5ea4029b`).
- The Android Path button navigates to the active-walk surface today (`onEnterActiveWalk(selectedMode)`, `WalkStartScreen.kt:304@5ea4029b`); for Honor it must open the Ways sheet instead.

### 4. The Ways sheet (`HonorWaysSheet`, "Choose a way")

#### 4.1 Opening it

`chooseWay()` resets the import state and any link toast, then presents the sheet. Its first statement wires the pilgrimage managers (Stage 21-2).

```swift
    func chooseWay() {
        // Downloading a second route, Replace, Update, and Remove are all
        // refused while a walk is on; this is where the manager learns what
        // "on" means.
        Task { @MainActor in
            PilgrimagePackageManager.shared.isWalkActive = { [weak self] in self?.activeWalkViewModel != nil }
            PilgrimageTilesManager.shared.isWalkActive = { [weak self] in self?.activeWalkViewModel != nil }
            PilgrimagePackageManager.shared.tiles = PilgrimageTilesManager.shared
        }
        honorImportState = .idle
        showLinkToast(nil)
        honorWaysPresented = true
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:195-207@7c200bf

The sheet is a plain `.sheet` (default large detent, swipe to dismiss). It receives the journal's walk array as `ownWalks`; its dismissal promotes any parked Way (§6):

```swift
        .sheet(isPresented: $coordinator.honorWaysPresented, onDismiss: coordinator.promotePendingHonorWay) {
            HonorWaysSheet(
                ownWalks: coordinator.homeViewModel.walks,
                importState: coordinator.honorImportState,
                onChoose: { coordinator.openOverview(for: $0) },
                onPaste: { text in
                    if let id = HonorLink.parse(text: text) { coordinator.openWay(shareId: id) }
                }
            )
        }
```
> Pilgrim/Scenes/Root/MainTabView.swift:64-73@7c200bf

`homeViewModel.walks` is every saved walk, newest first:

```swift
            walks = try DataManager.dataStack.fetchAll(
                From<Walk>()
                    .orderBy(.descending(\._startDate))
            )
```
> Pilgrim/Scenes/Home/HomeViewModel.swift:57-60@7c200bf

#### 4.2 Sections, in order

The sheet is a `NavigationStack { List { … } }` with four sections. Only the second belongs to the own-walk slice.

| # | Header | Contents | Footer | Slice |
|---|---|---|---|---|
| 1 | `"Shared with you"` | accepted shared Ways, or the empty copy | — | U28 (rows); the empty copy shows for any walker with no accepted shares |
| 2 | `"Your own walks"` | one row, `"Walk one of yours again"` | — | **U21** |
| 3 | `"A pilgrimage"` | one row, `"Walk a pilgrimage"` | `"A route from the open-pilgrimages dataset, walked one stage at a time."` | Stage 21-2 |
| 4 | `"From a shared walk"` | paste field, `"Open"`, import line | `"A walk someone shared with you, from walk.pilgrimapp.org."` | U28 |

```swift
        NavigationStack {
            List {
                Section {
                    if acceptedWays.isEmpty {
                        Text("no ways yet. Accept a shared walk, or walk one of yours again.")
                            .font(Constants.Typography.caption)
                            .foregroundColor(.fog)
                    }
                    ForEach(acceptedWays, id: \.id) { way in
                        Button { onChoose(way) } label: { wayRow(way) }
                    }
                } header: {
                    Text("Shared with you").font(Constants.Typography.caption)
                }

                Section {
                    Button { showOwnWalks = true } label: {
                        settingNavRow(label: "Walk one of yours again")
                    }
                } header: {
                    Text("Your own walks").font(Constants.Typography.caption)
                }

                Section {
                    Button { showPilgrimages = true } label: {
                        settingNavRow(label: "Walk a pilgrimage")
                    }
                } header: {
                    Text("A pilgrimage").font(Constants.Typography.caption)
                } footer: {
                    Text("A route from the open-pilgrimages dataset, walked one stage at a time.")
                        .font(Constants.Typography.caption)
                }

                Section {
                    TextField("paste a walk link", text: $pasted)
                    ...
                } header: {
                    Text("From a shared walk").font(Constants.Typography.caption)
                } footer: {
                    Text("A walk someone shared with you, from walk.pilgrimapp.org.")
                        .font(Constants.Typography.caption)
                }
            }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:23-78@7c200bf

Branch points for later slices: section 1's rows are `acceptedWays`, filtered to `.share` sources on appear (U28); section 3 opens `PilgrimageCatalogView` (Stage 21-2); section 4 and the `importState` line are U28. The `onAppear` also sweeps expired shares and cancels their downloads (U28):

```swift
            .onAppear {
                for id in WayStore.shared.sweepExpired(now: Date()) { WayMediaDownloader.shared.cancel(wayId: id) }
                acceptedWays = WayStore.shared.list().filter { if case .share = $0.source { return true } else { return false } }
                withMedia = Set(acceptedWays.filter { WayStore.shared.hasMedia(id: $0.id) }.map(\.id))
            }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:112-116@7c200bf

A saved own-walk Way (`source: .ownWalk`, written at walk end, §12) is never listed in section 1. Walking an own walk again always goes through the picker, which rebuilds the Way from the walk.

The own-walk row uses the settings nav row: label left, chevron right.

```swift
func settingNavRow(label: String, detail: String? = nil) -> some View {
    HStack {
        Text(label)
            .font(Constants.Typography.body)
            .foregroundColor(.ink)
        Spacer()
        if let detail {
            ...
        }
        Image(systemName: "chevron.right")
            .font(Constants.Typography.caption)
            .foregroundColor(.fog)
    }
}
```
> Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:74-91@7c200bf

Android counterpart: `SettingNavRow` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsCardStyle.kt:236@5ea4029b`).

#### 4.3 Toolbar and chrome

```swift
            .navigationTitle("Choose a way")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("Choose a way")
                        .font(Constants.Typography.heading)
                        .foregroundColor(.ink)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .font(Constants.Typography.button)
                        .foregroundColor(.stone)
                }
            }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:79-92@7c200bf

No background modifier is set on the `List`, and the app installs no global `UITableView`/`UICollectionView` appearance (a grep for `appearance()` under `Pilgrim/` at the pin finds none). So this sheet and the picker render in the system grouped-list colours, not parchment. The accent comes from `MainTabView`'s trailing `.tint(.stone)` (`MainTabView.swift:160-164@7c200bf`).

#### 4.4 The nested picker

Tapping `"Walk one of yours again"` presents the picker as a second sheet on top of this one. The unwalkable alert is attached to the picker, so the picker stays up after `"OK"`.

```swift
            .sheet(isPresented: $showOwnWalks) {
                OwnWalkPicker(walks: ownWalks) { walk in
                    guard let way = OwnWalkWayBuilder.make(from: walk) else {
                        unwalkableAlert = true
                        return
                    }
                    onChoose(way)   // dismisses the parent sheet, which takes this nested one with it
                }
                .alert("Can't walk this one again", isPresented: $unwalkableAlert) {
                    Button("OK", role: .cancel) {}
                } message: {
                    Text("This walk doesn't have enough of a route to follow. Try another.")
                }
            }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:93-106@7c200bf

### 5. The own-walk picker (`OwnWalkPicker`, "Walk again")

#### 5.1 Which walks it lists, and in what order

The filter is the stored `distance`, not the route. Order is the array's order: newest first (§4.1). The filter runs once in `init` so the empty state never flashes.

```swift
    /// Computed in `init` from the passed array's stored `distance`
    /// attribute, not in `onAppear`: faulting every walk's `routeData` per
    /// body pass is the O(walks) main-thread cost `HomeViewModel` already
    /// avoids with bulk queries, and `onAppear` ran a frame late, flashing
    /// the empty state before the filter landed.
    private let eligible: [Walk]

    init(walks: [Walk], onPick: @escaping (Walk) -> Void) {
        eligible = walks.filter { $0.distance > 0 }
        self.onPick = onPick
    }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:148-158@7c200bf

No other filter applies: Honor walks, Seek walks, and archived walks all appear when their distance is above zero. An archived walk keeps its distance but loses its route (§5.4).

#### 5.2 Layout and strings

```swift
        NavigationStack {
            List {
                if eligible.isEmpty {
                    Text("walk somewhere first. Any walk with a route can be walked again.")
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                }
                ForEach(eligible, id: \.id) { walk in
                    Button { onPick(walk) } label: { walkRow(walk) }
                }
            }
            .navigationTitle("Walk again")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("Walk again")
                        .font(Constants.Typography.heading)
                        .foregroundColor(.ink)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .font(Constants.Typography.button)
                        .foregroundColor(.stone)
                }
            }
        }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:161-186@7c200bf

Each row is two lines, 2 pt apart: the title (the walk's intention, trimmed, else its date in medium style) and the distance in the walker's unit.

```swift
    private func walkRow(_ walk: Walk) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title(for: walk))
                .font(Constants.Typography.body)
                .foregroundColor(.ink)
            Text(StatsHelper.string(for: walk.distance, unit: UnitLength.meters, type: .distance))
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
        }
    }

    private func title(for walk: Walk) -> String {
        if let comment = walk.comment?.trimmingCharacters(in: .whitespacesAndNewlines), !comment.isEmpty {
            return comment
        }
        return DateFormatter.localizedString(from: walk.startDate, dateStyle: .medium, timeStyle: .none)
    }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:190-206@7c200bf

The row shows no date when the walk has an intention. The distance formatter converts to the walker's unit and rounds to 0.01 with `unitOptions = .providedUnit`, so it is always kilometres or miles (for example `"0.05 km"`), never metres:

```swift
        let formatter = MeasurementFormatter()
        formatter.unitOptions = .providedUnit
        ...
        case .twoDigits:
            formatter.numberFormatter.roundingIncrement = 0.01
        ...
        case .distance:
            return safeFormattedString(formatter, measurement: measurement, to: UserPreferences.distanceMeasurementType.safeValue)
```
> Pilgrim/Models/Formatting/CustomMeasurementFormatting.swift:28-55@7c200bf

```swift
    static let distanceMeasurementType = MeasurementUserPreference<UnitLength>(key: "distanceMeasurementType", possibleValues: [.kilometers, .miles])
```
> Pilgrim/Models/Preferences/UserPreferences.swift:104@7c200bf

Android's house distance formatter is `WalkFormat.distance` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormat.kt:53@5ea4029b`); it switches to metres below 100 m, where iOS's `StatsHelper` does not. That is the app-wide convention already, not a new decision here.

#### 5.3 Tapping a row

The tap runs `OwnWalkWayBuilder.make(from:)` synchronously on the main thread (the builder stats every recording file, `OwnWalkWayBuilder.swift:38-45@7c200bf`). A nil Way raises the alert (§4.4); a Way goes to `openOverview(for:)`, which parks it and closes the Ways sheet; the nested picker closes with it (§6.1).

The builder returns nil in exactly three cases:

```swift
    static let minLengthMeters = 20.0

    static func make(from walk: WalkInterface) -> Way? {
        let samples = walk.routeData.sorted { $0.timestamp < $1.timestamp }
        guard samples.count >= 2, let first = samples.first, let uuid = walk.uuid else { return nil }
        ...
        let fullGeometry = WayGeometry(route: full)
        guard fullGeometry.totalMeters >= minLengthMeters else { return nil }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:12-23@7c200bf

So a listed walk is rejected when it has fewer than two route samples, no uuid, or a route under 20 m. The builder's other rules belong to cluster A.

The Way the overview shows takes its title the same way the row does, and its id from the walk:

```swift
        let title: String
        if let comment = walk.comment?.trimmingCharacters(in: .whitespacesAndNewlines), !comment.isEmpty {
            title = comment
        } else {
            title = DateFormatter.localizedString(from: walk.startDate, dateStyle: .medium, timeStyle: .none)
        }
        let weather = walk.weatherCondition.map { WayWeather(condition: $0, temperatureC: walk.weatherTemperature) }

        return Way(
            id: "walk:\(uuid.uuidString)", source: .ownWalk(uuid), title: title,
            departedAt: walk.startDate, tzIdentifier: TimeZone.current.identifier, expires: nil,
            route: route, totalDistanceMeters: fullGeometry.totalMeters,
            theirActiveSeconds: walk.activeDuration, moments: moments, weather: weather, spans: spans)
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:100-112@7c200bf

Recordings whose file is missing or empty are dropped at build time, so an own-walk Way never promises a voice it cannot play:

```swift
        // Recordings are deletable in-app while their rows stay; a Way must
        // not promise a voice whose file is gone (TourBuilder.candidates
        // makes the same check).
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let present = walk.voiceRecordings
            .filter { !$0.fileRelativePath.isEmpty }
            .filter {
                let size = (try? FileManager.default.attributesOfItem(atPath: docs.appendingPathComponent($0.fileRelativePath).path)[.size]) as? Int
                return (size ?? 0) > 0
            }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:35-44@7c200bf

#### 5.4 Archived walks reach the picker and always fail

Archiving (a web-editor import) deletes a walk's route but keeps its distance:

```swift
        for rec in recordings { transaction.delete(rec) }
        for sample in walk._routeData.value { transaction.delete(sample) }
        ...
        walk._comment .= nil
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:457-466@7c200bf

So an archived walk passes `distance > 0`, is listed under its date, and every tap ends in `"Can't walk this one again"`. Filed below as a candidate iOS defect. Android's archive strip is in `PilgrimPackageImporter.kt` (plan U14 names it).

### 6. The summary door ("walk this again")

#### 6.1 Where it sits and when it shows

It is the last element of the summary's scroll column, directly after the share buttons. It shows when the cached route has at least two coordinates and the host passed `onWalkAgain`. It shows on every kind of walk, Honor and Seek included.

```swift
    @ViewBuilder
    private var shareCard: some View {
        WalkSharingButtons(walk: walk, pinnedPhotos: photoCandidates.filter(\.isPinned), onShare: markSharedAndReveal)
        // The route is already cached (AF17) — a body must never re-fault
        // `routeData` to find out whether there is a Way to walk.
        if cachedRouteCoordinates.count >= 2, let onWalkAgain {
            Button {
                onWalkAgain(walk)
                dismiss()
            } label: {
                Label("walk this again", systemImage: "signpost.right")
                    .font(Constants.Typography.button)
                    .foregroundColor(.stone)
            }
        }
    }
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:676-691@7c200bf

```swift
                    shareCard
                }
                .padding(Constants.UI.Padding.normal)
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:132-134@7c200bf

The button has no background, no card, and no frame; it inherits the column's `VStack(spacing: Constants.UI.Padding.normal)` (`WalkSummaryView.swift:86@7c200bf`). The route cache is every stored sample:

```swift
    static func computeRouteCoordinates(for walk: WalkInterface) -> [CLLocationCoordinate2D] {
        walk.routeData.map {
            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
        }
    }
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:817-821@7c200bf

Which hosts pass `onWalkAgain`:

```swift
    /// Set by the hosts that can present the Honor overview afterwards; the
    /// recordings list, which has nowhere to send the Way, leaves it nil.
    let onWalkAgain: ((WalkInterface) -> Void)?
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:9-11@7c200bf

- The post-walk summary (`MainTabView.swift:58-63@7c200bf`): yes.
- The journal summary, reached from the ink scroll or from the Goshuin grid inside the journal (`HomeView.swift:59-69@7c200bf`): yes.
- The Recordings list (`RecordingsListView.swift:56@7c200bf`): no.

#### 6.2 What a tap does

The tap builds the Way, parks it, and closes the summary. The overview appears only once the summary has finished closing:

```swift
    /// Called from a summary's "walk this again": hold the Way, let the
    /// summary sheet close, then present (AF60: never two sheets at once).
    /// A nil build (OwnWalkWayBuilder.make(from:) rejecting too short a route or a missing uuid) parks nothing and no overview appears; every host wiring `onWalkAgain` must also wire its summary's `onDismiss` to `promotePendingHonorWay`, since the park is global but the promote is per-host.
    func walkAgain(_ walk: WalkInterface) {
        pendingHonorWay = OwnWalkWayBuilder.make(from: walk)
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:305-310@7c200bf

The door skips the Ways sheet and the picker entirely. A nil build (route under 20 m) closes the summary and shows nothing: no alert, unlike the picker. Filed below as a candidate iOS defect.

The journal host wires its summary's dismissal to the promote:

```swift
    /// The journal's summary sheet is the second road into the Honor
    /// overview, so it owes the coordinator the same promote-on-dismiss the
    /// post-walk summary does (AF60).
    let onSummaryDismiss: () -> Void
```
> Pilgrim/Scenes/Home/HomeView.swift:8-11@7c200bf

```swift
        HomeView(
            viewModel: coordinator.homeViewModel,
            onWalkAgain: coordinator.walkAgain,
            onSummaryDismiss: coordinator.promotePendingHonorWay
        )
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:397-401@7c200bf

The post-walk host reaches the same promote through `handleSummaryDismiss`:

```swift
    func handleSummaryDismiss() {
        Task { @MainActor in TranscriptionService.shared.autoTranscriptionSkippedReason = nil }
        homeViewModel.loadWalks()
        promotePendingHonorWay()
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:185-189@7c200bf

The tab does not change. A journal summary's overview opens over the Journal tab; the walk that Begin starts then covers the whole tab view (§12).

Android: the share card is `WalkSharingButtons` at `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt:802-861@5ea4029b`; the door goes after it. Android's summary is one dialog route reached from Home, Goshuin, the post-walk finish, the Recordings list, and the widget deep link (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt:229,272,374,544,672@5ea4029b`), so hiding the door for the Recordings list needs a host signal. iOS has no widget-to-summary link (its `onOpenURL` routes only walk links, `Pilgrim/PilgrimApp.swift:47,60-64@7c200bf`); see Open questions.

### 7. Routing: park, promote, gather (`MainCoordinator`)

#### 7.1 One sheet at a time

Every road into the overview goes through `openOverview(for:)` or `promotePendingHonorWay()`. A Way chosen while the Ways sheet or a summary is up is parked, and the sheet's dismissal presents it:

```swift
    /// From the Ways sheet: park the Way, close the sheet, promote on dismiss.
    /// From a link with no sheet open: present directly (one change).
    func openOverview(for way: Way) {
        // A summary sheet is a presented sheet too: presenting the overview
        // over it drops the overview on the floor and dead-ends the link.
        // `handleSummaryDismiss` promotes the park when the summary closes,
        // exactly as the Ways sheet's dismiss does.
        if honorWaysPresented || completedSnapshot != nil {
            pendingHonorWay = way
            honorWaysPresented = false
        } else {
            honorOverviewWay = way
            gather(way)
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:244-258@7c200bf

```swift
    func promotePendingHonorWay() {
        if let way = pendingHonorWay {
            pendingHonorWay = nil
            honorOverviewWay = way
            gather(way)
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:312-318@7c200bf

The tests pin both: `testOpenOverviewFromTheWaysSheetParksWithoutPresenting`, `testPromotePendingHonorWayPresentsTheParkedWay`, `testOpenOverviewOverASummarySheetParksInsteadOfPresenting`, `testWalkAgainParksTheWayBuiltFromTheWalk` (`UnitTests/Honor/MainCoordinatorHonorTests.swift:25,37,110,201@7c200bf`). The last asserts the own-walk id shape:

```swift
        coordinator.walkAgain(makeWalk(uuid: uuid))

        XCTAssertEqual(coordinator.pendingHonorWay?.id, "walk:\(uuid.uuidString)")
        XCTAssertNil(coordinator.honorOverviewWay, "the summary sheet is still closing")
```
> UnitTests/Honor/MainCoordinatorHonorTests.swift:205-208@7c200bf

This parking exists for SwiftUI's sheet race (AF60). Android's navigation has no such race; the observable order to keep is: the sheet or summary is gone before the overview shows, and Back from the overview does not land on the Ways sheet or the summary.

#### 7.2 `gather` for an own walk

For any source that is not a share, `gather` sets the state to `.ready` at once and installs nothing. The download branch is U28.

```swift
    func gather(_ way: Way) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            // A dismiss or an item swap that lands before this hop must not
            // install a sink for a Way that is no longer showing.
            guard self.honorOverviewWay?.id == way.id else { return }
            guard case .share = way.source else { self.honorImportState = .ready; return }
            let downloader = WayMediaDownloader.shared
            ...
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:264-271@7c200bf

`.ready` has no copy, so an own-walk overview shows no import line:

```swift
enum HonorImportState: Equatable {
    case idle, fetching, gathering(progress: Double), ready, mediaMissing([String]), failed(WayError)
}
...
enum HonorImportCopy {
    static func line(for state: HonorImportState) -> String? {
        switch state {
        case .idle, .ready: return nil
        case .fetching: return "reaching for the walk…"
        case .gathering(let p): return "gathering their voices · \(Int((p * 100).rounded()))%"
        case .mediaMissing: return "some voices didn't arrive"
        case .failed(.notFound): return "couldn't find that walk. Check the link, or it may have returned to the trail."
        case .failed(.returnedToTrail): return "This walk has returned to the trail"
        case .failed(.unavailable): return "couldn't reach the walk"
        case .failed(.diskFull): return "not enough space on this phone to save these voices"
        }
    }
}
```
> Pilgrim/Models/Honor/HonorImportReducer.swift:3-37@7c200bf

The import state is global, not per Way. A link tapped while an own-walk overview is open sets it to `.fetching`, which disables that overview's Begin and shows `"reaching for the walk…"` on it (§13.1). That interplay is U28's to port; it is named here because the own-walk overview reads the same state.

#### 7.3 The overview sheet

```swift
        .sheet(item: $coordinator.honorOverviewWay, onDismiss: coordinator.handleOverviewDismiss) { way in
            NavigationStack {
                HonorOverviewView(
                    way: way,
                    importState: coordinator.honorImportState,
                    onBegin: { coordinator.startHonor(way: way) },
                    onClose: { coordinator.honorOverviewWay = nil },
                    onRetryMedia: { coordinator.retryMedia(for: way) },
                    onWalkWithoutMissing: coordinator.walkWithoutMissingVoices
                )
            }
        }
```
> Pilgrim/Scenes/Root/MainTabView.swift:74-85@7c200bf

A plain `.sheet(item:)`: default large detent, swipe to dismiss, which is the same as Close. `onRetryMedia` and `onWalkWithoutMissing` are U28.

A real close resets the import state:

```swift
    func handleOverviewDismiss() {
        // A link arriving while the overview is open swaps the sheet's item
        // rather than closing it, and this fires for the outgoing Way after
        // the incoming one's gathering has already begun. Reset only on a
        // real close.
        if honorOverviewWay == nil {
            gatheringCancellable = nil
            honorImportState = .idle
        }
        if let way = pendingStartWay {
            pendingStartWay = nil
            startWalk(mode: .honor, way: way)
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:325-338@7c200bf

### 8. The overview: layout tree

```
NavigationStack                                  (MainTabView)
└─ HonorOverviewView
   ├─ nav bar, inline, no title
   │  ├─ leading (cancellationAction): "Close"
   │  └─ trailing (DEBUG only): ladybug menu → "Export simulation GPX"   (U19)
   └─ ZStack(alignment: .bottom)
      ├─ PilgrimMapView   full height; card height passed as bottomInset
      │   pins: markPins (stage only) + Way moment pins; tap → WayMomentPreview sheet
      └─ card  VStack(alignment: .leading, spacing: 8), padding 16, parchment
         1. title
         2. stage line, or the departure date and time
         3. distance · duration · counts
         4. import line (+ "try again" / "walk without the missing voices")   (U28)
         5. weather line                     (only when the Way has weather)
         6. distance-to-start line           (only when a fix is known)
         7. offline note                     (stage only; Stage 21-2)
         8. Toggle "walk with their voice"   (hidden on a stage)
         9. Begin
```

The screen's own summary:

```swift
/// The map fit to the whole Way, a card, and Begin. The camera never follows the puck here.
struct HonorOverviewView: View {
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:62-63@7c200bf

The body:

```swift
        ZStack(alignment: .bottom) {
            PilgrimMapView(
                isInteractive: true,
                showsUserLocation: true,
                followsUserLocation: false,
                pinAnnotations: markPins + (rendering?.pins ?? []),
                onAnnotationTap: { pin in
                    guard let id = pin.kind.wayMomentID else { return }
                    previewMoment = way.moments.first { $0.id == id }
                },
                cameraBounds: rendering?.bounds,
                bottomInset: cardHeight,
                isMeditating: $isMeditating,
                honorWay: rendering?.state,
                onCameraChanged: { center, zoom in
                    liveCenter = center
                    liveZoom = zoom
                    refreshMarkPins()
                }
            )

            card
                .background(
                    GeometryReader { proxy in
                        Color.clear.preference(key: CardHeightKey.self, value: proxy.size.height)
                    }
                )
        }
        .onPreferenceChange(CardHeightKey.self) { cardHeight = $0 }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Close", action: onClose)
                    .font(Constants.Typography.button)
                    .foregroundColor(.stone)
            }
            #if DEBUG
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Export simulation GPX", action: exportSimulationGPX)
                } label: {
                    Image(systemName: "ladybug")
                }
            }
            #endif
        }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:120-165@7c200bf

The card's view:

```swift
    private var card: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
            Text(way.title)
                .font(Constants.Typography.heading)
                .foregroundColor(.ink)
            Text(WayStageLine.line(for: way)
                 ?? DateFormatter.localizedString(from: way.departedAt, dateStyle: .long, timeStyle: .short))
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
            HStack {
                Text(StatsHelper.string(for: way.totalDistanceMeters, unit: UnitLength.meters, type: .distance))
                Text("·")
                Text(durationText(way.theirActiveSeconds))
                Text("·")
                Text(HonorOverviewModel.countsLine(way: way))
            }
            .font(Constants.Typography.body)
            .foregroundColor(.ink)
            importLine
            if let line = HonorOverviewModel.weatherLine(theirs: way.weather, today: todayCondition) {
                Text(line)
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
            }
            if let status = HonorOverviewModel.statusLine(distanceToStartMeters: distanceToStart) {
                Text(status)
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
            }
            if let offlineNote {
                Text(offlineNote)
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
            }
            // A stage carries no recordings, so "walk with their voice" would
            // be a switch over nothing — and would say "their" besides.
            if !way.isPilgrimageStage {
                Toggle(isOn: $voicesEnabled) {
                    Text("walk with their voice")
                        .font(Constants.Typography.body)
                        .foregroundColor(.ink)
                }
                .tint(.stone)
                .onChange(of: voicesEnabled) { _, on in UserPreferences.honorVoicesEnabled.value = on }
                .disabled(way.voiceCount == 0)
            }

            Button {
                if way.isPilgrimageStage { showMorningCard = true } else { onBegin() }
            } label: {
                Text("Begin")
                    .font(Constants.Typography.button)
                    .foregroundColor(.parchment)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(isGathering ? Color.fog : Color.stone)
                    .cornerRadius(Constants.UI.CornerRadius.normal)
            }
            .accessibilityLabel(way.isPilgrimageStage ? "Walk this stage" : "Begin honoring this way")
            .disabled(isGathering)
        }
        .padding(Constants.UI.Padding.normal)
        .background(Color.parchment)
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:227-290@7c200bf

Visual facts that are easy to miss:
- The card is a flat parchment rectangle: no corner radius, no shadow, no drag handle, not scrollable. It spans the sheet's width.
- The card sits over the map; the map keeps its full height (§9).
- The stage branch points are the stage line (row 2), the offline note (row 7), the hidden toggle (row 8), Begin opening the morning card, and Begin's `"Walk this stage"` label. All belong to Stage 21-2.
- The stats row is a plain `HStack` of five `Text`s with no line limit, so on a narrow screen each piece wraps inside its own column.

### 9. The overview: map framing

#### 9.1 What the map is asked to do

Interactive (pan and pinch), the walker's puck shown, the camera never following the puck. The Way is computed once per Way id, off the per-tick body:

```swift
    /// The three O(route) derivations the map needs. `importState` changes on
    /// every gathering tick and re-runs `body`, so these are computed once per
    /// Way in `.task(id:)` rather than three times per tick.
    private struct WayRendering {
        let pins: [PilgrimAnnotation]
        let bounds: MapCameraBounds?
        let state: HonorWayState
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:96-103@7c200bf

```swift
        .task(id: way.id) {
            rendering = WayRendering(
                pins: PilgrimMapView.wayPins(for: way, heardVoiceIDs: []),
                bounds: HonorOverviewModel.bounds(of: way),
                state: HonorWayState(way: way)
            )
            refreshMarkPins()
            refreshStageMapsSaved()
        }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:168-176@7c200bf

The fit target is the bounding box of the Way's route points (the pins are not included):

```swift
    static func bounds(of way: Way) -> MapCameraBounds? {
        let lats = way.route.map(\.lat), lons = way.route.map(\.lon)
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max() else { return nil }
        return MapCameraBounds(sw: CLLocationCoordinate2D(latitude: minLat, longitude: minLon),
                               ne: CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon))
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:53-59@7c200bf

The pins are every moment, in the Way's own order, standing at the place itself (`pin`), else its projection (`at`), else the line at its fraction. None is heard before Begin. Pin styling belongs to cluster E.

```swift
    static func wayPins(for way: Way, heardVoiceIDs: Set<String>) -> [PilgrimAnnotation] {
        let geometry = WayGeometry(route: way.route)
        return way.moments.map { moment in
            // `pin` is the place itself; `at` is its projection onto the
            // line, which is where the engine's 60 m trigger fires. The pin
            // must stand where the place does.
            let coordinate = (moment.pin ?? moment.at).map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                ?? geometry.coordinate(atFrac: moment.frac)
            let kind: PilgrimAnnotation.Kind
            switch moment.kind {
            case .voice: kind = .wayVoice(id: moment.id, heard: heardVoiceIDs.contains(moment.id))
            case .photo: kind = .wayPhoto(id: moment.id)
            case .rest(let minutes): kind = .wayRest(id: moment.id, minutes: minutes)
            case .meditation(let minutes, _): kind = .waySit(id: moment.id, minutes: minutes)
            case .waypoint(let label, let icon): kind = .wayWaypoint(id: moment.id, label: label, icon: icon)
            }
            return PilgrimAnnotation(coordinate: coordinate, kind: kind)
        }
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:120-138@7c200bf

Every moment pin is tappable (`wayMomentID` is non-nil for all five way kinds; only stage service marks are not):

```swift
    var wayMomentID: String? {
        switch self {
        case .wayVoice(let id, _), .wayPhoto(let id), .wayRest(let id, _),
             .waySit(let id, _), .wayWaypoint(let id, _, _):
            return id
        default:
            return nil
        }
    }
```
> Pilgrim/Models/Walk/MapManagement/PilgrimAnnotation.swift:41-49@7c200bf

For an own walk `way.marks` is nil, so `markPins` is always empty (`HonorOverviewView.swift:356-362@7c200bf`); the zoom-gated service marks are Stage 21-2.

#### 9.2 The card height as the bottom inset

The card measures itself with a preference key; the map reads it as `bottomInset`:

```swift
    /// The card's measured height, so the map can run beneath it and still
    /// fit the whole stage into the part of itself that stays uncovered.
    @State private var cardHeight: CGFloat = 0
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:112-114@7c200bf

```swift
/// How tall the overview's card is, so the map beneath it knows how much of
/// itself the card covers.
private struct CardHeightKey: PreferenceKey {
    static var defaultValue: CGFloat { 0 }
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:411-418@7c200bf

The card's height changes when the weather or the distance line arrives, so the inset changes, and the map refits.

#### 9.3 The fit itself

With `followsUserLocation: false` and bounds present, the map fits the bounds with fixed padding plus the inset, capped so at least 160 pt of map stays for the route. It refits only when the bounds or the inset change, eases over `cameraDuration`, and records the fit only when it landed:

```swift
            if let bounds = cameraBounds {
                // Only when the bounds actually change, or the inset moved
                // them: `updateUIView` runs on every published tick behind
                // the map (a gathering percentage, say), and easing again on
                // each one yanks a pan the walker just made back to the fit.
                // A first application always eases — `lastAppliedBounds`
                // starts nil.
                let changed = context.coordinator.lastAppliedBounds != bounds
                    || context.coordinator.lastAppliedBoundsInset != bottomInset
                // Padding taller than the map itself makes `camera(for:)`
                // throw, and a view mid-layout is briefly exactly that. The
                // inset is a card lying over the map, so on a short screen it
                // can ask for more room than there is: leave the route a
                // minimum to be drawn in rather than let the fit fail.
                let roomForRoute: CGFloat = 160
                let headroom = max(0, mapView.bounds.height - roomForRoute - 80)
                let padding = UIEdgeInsets(top: 40, left: 30,
                                           bottom: 40 + min(bottomInset, headroom), right: 30)
                let fits = mapView.bounds.height > padding.top + padding.bottom
                    && mapView.bounds.width > padding.left + padding.right
                if changed && fits {
                    do {
                        let camera = try mapView.mapboxMap.camera(
                            for: [bounds.sw, bounds.ne],
                            camera: CameraOptions(),
                            coordinatesPadding: padding,
                            maxZoom: nil,
                            offset: nil
                        )
                        mapView.camera.ease(to: camera, duration: cameraDuration)
                        // Recorded only once the fit actually landed. Set
                        // before the attempt, a single throw would read as
                        // "already applied" for good and strand the map on
                        // the default zero-zoom camera — the globe.
                        context.coordinator.lastAppliedBounds = bounds
                        context.coordinator.lastAppliedBoundsInset = bottomInset
                    } catch {
                        print("[PilgrimMapView] camera(for:bounds:) failed: \(error)")
                    }
                }
```
> Pilgrim/Views/PilgrimMapView.swift:256-295@7c200bf

```swift
    var cameraDuration: TimeInterval = 0.4
    /// Bottom padding (in points) reserved for an overlay sheet. The map
    /// shifts its content so the user puck / fit bounds appear above this
    /// region instead of being hidden under a bottom sheet.
    var bottomInset: CGFloat = 0
```
> Pilgrim/Views/PilgrimMapView.swift:46-50@7c200bf

In numbers: padding top 40, left 30, right 30, bottom `40 + min(cardHeight, mapHeight − 240)` (never below 40). `maxZoom: nil`, so a very short Way zooms in as far as the style allows. The overview passes no `cameraDuration`, so every fit and refit eases over 0.4 s. A pan the walker made survives later body passes; it is undone only by a new inset (the card growing).

#### 9.4 Gestures and ornaments

```swift
        mapView.gestures.options.panEnabled = isInteractive
        mapView.gestures.options.pinchEnabled = isInteractive
        mapView.gestures.options.rotateEnabled = false
        mapView.gestures.options.pitchEnabled = false

        mapView.ornaments.options.scaleBar.visibility = .hidden
        mapView.ornaments.options.compass.visibility = .hidden
        mapView.ornaments.options.attributionButton.position = .bottomLeading
```
> Pilgrim/Views/PilgrimMapView.swift:145-152@7c200bf

The inset pads only the camera; the ornaments keep their bottom-leading position, under the card. So the Mapbox logo and the attribution button are covered on the overview. The plan's divergence (keep them visible, lifted above the card) stands; this is the quote for the iOS issue.

Android: the fit is U4's pure camera decision helper; the ghost line is cluster E's renderer (`HonorWayState`), reinstalled on style reload.

### 10. The overview: every string on the card

#### 10.1 Row 1, the title

`way.title`: for an own walk, the source walk's intention, trimmed, else its start date in medium style (§5.3). Heading font, ink.

#### 10.2 Row 2, the departure line

For an own walk `WayStageLine.line(for:)` is nil (no stage), so the row is the departure date in long style plus the time in short style, for example `"September 14, 2026 at 8:41 AM"` in en-US. Caption, fog. The stage line (`"stage 1 of 33 · 24 km · hard"`, `WayMomentHeader.swift:120-135@7c200bf`) is Stage 21-2.

#### 10.3 Row 3, distance · duration · counts

Body font, ink. The distance is `StatsHelper` in the walker's unit (§5.2) over `way.totalDistanceMeters` (the full-resolution route length). The duration is the source walk's active seconds:

```swift
    private func durationText(_ seconds: Double) -> String {
        // Int(_:) traps on an out-of-range Double; clamp so a Way from any source stays safe.
        let clamped = Int(min(max(seconds, 0), 999_999_999))
        let hours = clamped / 3600, minutes = (clamped % 3600) / 60
        return hours > 0 ? "\(hours)h \(minutes)m" : "\(minutes)m"
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:346-351@7c200bf

So `"1h 5m"`, `"42m"`, and `"0m"` under a minute; minutes truncate, seconds never show.

The counts:

```swift
    static func countsLine(way: Way) -> String {
        var parts: [String] = []
        if way.voiceCount > 0 { parts.append(way.voiceCount == 1 ? "1 voice" : "\(way.voiceCount) voices") }
        if way.photoCount > 0 { parts.append(way.photoCount == 1 ? "1 photo" : "\(way.photoCount) photos") }
        return parts.isEmpty ? "a quiet way" : parts.joined(separator: " · ")
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:7-12@7c200bf

```swift
    var voiceCount: Int { moments.filter(\.isVoice).count }
    var photoCount: Int {
        moments.filter { if case .photo = $0.kind { return true } else { return false } }.count
    }
```
> Pilgrim/Models/Honor/Way.swift:210-213@7c200bf

Waypoints, rests, and sittings are not counted. Pinned by test:

```swift
        XCTAssertEqual(HonorOverviewModel.countsLine(way: way(voices: 9, photos: 4, weather: nil)), "9 voices · 4 photos")
        XCTAssertEqual(HonorOverviewModel.countsLine(way: way(voices: 1, photos: 0, weather: nil)), "1 voice")
        XCTAssertEqual(HonorOverviewModel.countsLine(way: way(voices: 0, photos: 0, weather: nil)), "a quiet way")
```
> UnitTests/Honor/HonorOverviewModelTests.swift:30-32@7c200bf

#### 10.4 Row 4, the import line

Nil for an own walk in `.idle` or `.ready` (§7.2). The `.mediaMissing` pair `"try again"` / `"walk without the missing voices"` (`HonorOverviewView.swift:320-334@7c200bf`) is U28.

#### 10.5 Row 5, the weather comparison

Shown only when the Way has weather; for an own walk, only when the source walk recorded a condition (§5.3).

```swift
    static func weatherLine(theirs: WayWeather?, today: String?) -> String? {
        guard let theirs else { return nil }
        var line = "they walked this in \(spoken(theirs.condition))"
        // Int(_:) traps on an out-of-range Double AND on a NaN or infinity,
        // which no clamp catches; a Way from any source stays safe.
        if let t = theirs.temperatureC, t.isFinite { line += " at \(Int(min(max(t.rounded(), -1000), 1000)))°" }
        line += "."
        if let today { line += " Today is \(spoken(today))." }
        return line
    }

    /// Conditions travel as `WeatherCondition` raw values ("lightRain"), which
    /// would otherwise reach the reader camel-cased. Strings from outside that
    /// vocabulary pass through untouched.
    private static func spoken(_ condition: String) -> String {
        WeatherCondition(rawValue: condition)?.label.lowercased() ?? condition
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:26-42@7c200bf

The spoken words are the lowercased labels:

```swift
    var label: String {
        switch self {
        case .clear: return "Clear"
        case .partlyCloudy: return "Partly cloudy"
        case .overcast: return "Overcast"
        case .lightRain: return "Light rain"
        case .heavyRain: return "Heavy rain"
        case .thunderstorm: return "Thunderstorm"
        case .snow: return "Snow"
        case .fog: return "Foggy"
        case .wind: return "Windy"
        case .haze: return "Hazy"
        }
    }
```
> Pilgrim/Models/Weather/WeatherService.swift:25-38@7c200bf

So `"they walked this in light rain at 9°. Today is clear."`. The temperature is Celsius, rounded, with a bare `°`, whatever the walker's units (the plan's iOS issue list already has this; §Defects). There is no own-walk wording: the line says "they" about your own walk. Pinned:

```swift
        let theirs = WayWeather(condition: "rain", temperatureC: 9)
        XCTAssertEqual(HonorOverviewModel.weatherLine(theirs: theirs, today: "clear"),
                       "they walked this in rain at 9°. Today is clear.")
        XCTAssertEqual(HonorOverviewModel.weatherLine(theirs: theirs, today: nil), "they walked this in rain at 9°.")
        XCTAssertNil(HonorOverviewModel.weatherLine(theirs: nil, today: "clear"))
```
> UnitTests/Honor/HonorOverviewModelTests.swift:43-47@7c200bf

(`"rain"` is not a `WeatherCondition` raw value, so it passes through untouched; the test exercises the fallback.)

Today's condition comes from one fetch at the phone's last known fix, silent when there is none or the fetch fails:

```swift
    /// "Today is clear": the same service the walk uses, on the walker's
    /// current fix; silent when offline or without a fix.
    private func fetchToday() async {
        guard let here = CLLocationManager().location,
              let snapshot = await WeatherService.shared.fetchCurrent(for: here) else { return }
        todayCondition = snapshot.condition.rawValue
        todayWeather = snapshot
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:337-344@7c200bf

It runs once per appearance (`.task { await fetchToday() }`, `HonorOverviewView.swift:178@7c200bf`). Until it lands, the line reads without the "Today is" sentence, then grows. Android counterpart: `OpenMeteoClient.fetchCurrent(latitude:longitude:)` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/weather/OpenMeteoClient.kt:30@5ea4029b`).

#### 10.6 Row 6, the distance to the start

```swift
    static func statusLine(distanceToStartMeters: Double?) -> String? {
        guard let meters = distanceToStartMeters else { return nil }
        if meters <= HonorTuning.onWayMeters { return "you're on the way" }
        let imperial = UserPreferences.distanceMeasurementType.safeValue == .miles
        if imperial {
            let miles = meters / 1609.344
            return miles < 0.2 ? "\(Int(meters * 3.28084)) ft from the start"
                : String(format: "%.1f mi from the start", miles)
        }
        return meters < 1000 ? "\(Int(meters)) m from the start" : String(format: "%.1f km from the start", meters / 1000)
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:14-24@7c200bf

```swift
    static let onWayMeters = 60.0
```
> Pilgrim/Models/Honor/HonorTuning.swift:7@7c200bf

Rules: at or under 60 m, `"you're on the way"`. Metric: under 1000 m, whole metres truncated (`"650 m from the start"`); else one decimal (`"2.3 km from the start"`). Imperial: under 0.2 mi (about 322 m), whole feet truncated; else one decimal of miles. `String(format:)` here takes no locale, so the decimal separator is always `.`; Android should pin `Locale.US`. These thresholds differ from `WayDistance` (0.1 mi, rounded, `WayMomentHeader.swift:106-118@7c200bf`); the overview uses its own. Pinned:

```swift
        XCTAssertEqual(HonorOverviewModel.statusLine(distanceToStartMeters: 40), "you're on the way")
        XCTAssertEqual(HonorOverviewModel.statusLine(distanceToStartMeters: 2300), "2.3 km from the start")
        XCTAssertEqual(HonorOverviewModel.statusLine(distanceToStartMeters: 650), "650 m from the start")
        XCTAssertNil(HonorOverviewModel.statusLine(distanceToStartMeters: nil))
```
> UnitTests/Honor/HonorOverviewModelTests.swift:36-39@7c200bf

The distance is one straight-line probe, on appear, from the phone's last known fix to the Way's first route point. It never updates while the overview is open:

```swift
        .onAppear { probeDistance(); checkConnectivity() }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:166@7c200bf

```swift
    private func probeDistance() {
        guard let first = way.route.first, let here = CLLocationManager().location else { return }
        distanceToStart = here.distance(from: CLLocation(latitude: first.lat, longitude: first.lon))
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:371-374@7c200bf

With no fix (or no permission), the row is absent. The line only informs: nothing reads `distanceToStart` except this row (§13).

#### 10.7 Row 7, the offline note

Stage only; `checkConnectivity()` returns at once for any Way that is not a stage (`HonorOverviewView.swift:382-385@7c200bf`). Stage 21-2 (and the plan's stale-note iOS issue).

#### 10.8 Row 8, "walk with their voice"

```swift
    @State private var voicesEnabled = UserPreferences.honorVoicesEnabled.value
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:80@7c200bf

```swift
    static let honorVoicesEnabled = UserPreference.Required<Bool>(key: "honorVoicesEnabled", defaultValue: true)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:77@7c200bf

- The toggle starts from a global preference, default on, and writes it back on every change. It is sticky across walks and overviews, not per Way.
- It is disabled when the Way has no voices, but keeps showing its stored value (a quiet way can show a disabled, switched-on toggle).
- Tint is stone; the label is body font, ink.
- The engine reads the preference at Start, together with the app-wide sounds switch, so the toggle alone does not guarantee voices:

```swift
            voicesEnabled: UserPreferences.honorVoicesEnabled.value && UserPreferences.soundsEnabled.value
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:58@7c200bf

With sounds off, the toggle shows on and no voice plays. Filed below as a candidate iOS defect.

#### 10.9 Row 9, Begin

Visible `"Begin"`, button font, parchment text, full width, 12 pt vertical padding, stone background (fog while `isGathering`), corner radius 12. VoiceOver label `"Begin honoring this way"`. Disabled only while `isGathering` (§13.1). Tap → `onBegin()` (§12).

### 11. The overview: loading, empty, and error states

The overview is never shown without a Way: the sheet is keyed on a built Way (`sheet(item: $coordinator.honorOverviewWay)`), and a nil build never reaches it (§5.3, §6.2). There is no loading screen, spinner, or error screen for an own walk. What each piece does before or without its data:

| Piece | Before / without data | Source |
|---|---|---|
| Map, ghost line, pins | Nothing drawn until `.task(id: way.id)` sets `rendering` (one frame); then the fit eases in over 0.4 s | `HonorOverviewView.swift:168-176@7c200bf` |
| Fit | No route points → `bounds` nil → no fit (camera stays where the map opened) | `HonorOverviewView.swift:53-59@7c200bf` |
| Import line | `.ready` → none | `HonorImportReducer.swift:27@7c200bf` |
| Weather row | No source weather → row absent; no today fix → sentence absent | `HonorOverviewView.swift:26-35,337-344@7c200bf` |
| Distance row | No last-known fix → row absent | `HonorOverviewView.swift:371-374@7c200bf` |
| Counts | No voices or photos → `"a quiet way"` | `HonorOverviewView.swift:11@7c200bf` |
| Toggle | No voices → disabled | `HonorOverviewView.swift:271@7c200bf` |
| Begin | Enabled | `HonorOverviewView.swift:296-301@7c200bf` |

The one own-walk "error" is upstream of the overview: `"Can't walk this one again"` (picker) or nothing (summary door).

The overview does not re-read the source walk. The Way is built at the tap and carried by value to Begin (§12.2). If the walk is deleted while the overview is open, the overview and Begin still work from the captured Way; iOS has no refusal for that case. Android's U21 test "the Way is deleted while the overview is open → Begin is refused gracefully" goes beyond iOS; for an own walk the Way lives only in memory until walk end, so iOS has nothing to refuse (see Resolutions, pin 5).

### 12. Begin, up to the walk starting

#### 12.1 The hand-off

Begin parks the Way and closes the overview. The walk screen opens only after the overview has finished closing:

```swift
    func startHonor(way: Way) {
        pendingStartWay = way
        honorOverviewWay = nil
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:320-323@7c200bf

`handleOverviewDismiss` then calls `startWalk(mode: .honor, way: way)` (§7.3). Pinned:

```swift
        coordinator.startHonor(way: way)

        XCTAssertNil(coordinator.honorOverviewWay)
        XCTAssertEqual(coordinator.pendingStartWay?.id, way.id)
        XCTAssertNil(coordinator.activeWalkViewModel, "the walk must wait for the overview to finish closing")
```
> UnitTests/Honor/MainCoordinatorHonorTests.swift:64-68@7c200bf

```swift
        coordinator.handleOverviewDismiss()

        let vm = try XCTUnwrap(coordinator.activeWalkViewModel)
        XCTAssertEqual(vm.mode, .honor)
        XCTAssertEqual(vm.way?.id, way.id)
        XCTAssertNil(coordinator.pendingStartWay)
```
> UnitTests/Honor/MainCoordinatorHonorTests.swift:81-86@7c200bf

`startWalk` refuses a second walk silently, drops any import observation, and stops on a denied or restricted location permission:

```swift
    func startWalk(mode: WalkMode = .wander, way: Way? = nil) {
        guard activeWalkViewModel == nil else { return }
        // The overview is gone by the time a walk starts, so nothing is left
        // to render an import's progress — only the OBSERVATION is dropped.
        // The background transfers keep running, and a file that lands
        // mid-walk becomes playable like any other.
        importTask?.cancel()
        importTask = nil
        gatheringCancellable = nil
        let locationStatus = CLLocationManager().authorizationStatus
        if locationStatus == .denied || locationStatus == .restricted {
            showLocationDenied = true
            return
        }
        Task { @MainActor in TranscriptionService.shared.autoTranscriptionSkippedReason = nil }
        let vm = ActiveWalkViewModel(mode: mode, way: way)
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:79-94@7c200bf

```swift
        .alert("Location Required", isPresented: $coordinator.showLocationDenied) {
            Button("Settings", action: coordinator.openSettings)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Pilgrim needs location access to track your route. Please enable it in Settings.")
        }
```
> Pilgrim/Scenes/Root/MainTabView.swift:114-119@7c200bf

On that path the Way is dropped: the overview is already closed, and the walker must come back through a door. An undetermined permission passes; the walk screen handles it.

#### 12.2 What is handed over

Only the mode and the Way value. Nothing else rides along: the voices toggle and the soft-tap preference are read from `UserPreferences` when the engine starts (§12.3). The Way is not saved to the store at Begin; an own-walk Way is first written when the walk saves:

```swift
                    // The only place a finished walk is bound to its Way:
                    // `save` is idempotent (an own-walk Way is first written
                    // here), and `link` overwrites, so it must not run again
                    // elsewhere. Crash recovery binds too, but only a walk
                    // this path never reached, so the two can't collide.
                    ...
                    if let way, let uuid = walk?.uuid {
                        if !way.source.isPackageOwned { try? WayStore.shared.save(way) }
                        let arrival = vm?.honorArrival.map { (theirSeconds: $0.theirSeconds, yourSeconds: $0.yourSeconds) }
                        try? WayStore.shared.link(walkUUID: uuid, to: way.id, arrival: arrival)
                        self.recordStageWalk(way: way, outcome: vm?.honorStageOutcome)
                    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:108-121@7c200bf

The save, the link, and recovery are cluster A's and G's.

#### 12.3 Nothing is confirmed; the walk starts at Start, not at Begin

There is no confirmation dialog for an own walk (the stage morning card is Stage 21-2). Begin opens the walk screen in its pre-walk state. The Honor marker event and the engine start only when the walker taps Start on that screen:

```swift
            case .ready:
                actionButton("Start", systemImage: "play.fill", color: .moss, isFilled: true) {
                    viewModel.startRecording()
                }
```
> Pilgrim/Scenes/ActiveWalk/WalkStatsSheet.swift:522-525@7c200bf

```swift
    func startRecording() {
        proximityService.resetSession()
        builder.setStatus(.recording)
        writeSeekMarkerEventIfNeeded()
        writeHonorMarkerEventIfNeeded()
        startHonorEngineIfNeeded()
        soundManagement.onWalkStart()
        startVoiceGuideIfEnabled()
        WalkActivityManager.shared.start(walkStartDate: Date(), intention: intention)
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:380-389@7c200bf

```swift
    func startHonorEngineIfNeeded() {
        guard mode == .honor, let way, honorEngine == nil else { return }
        // Nothing queued before Begin belongs to this walk.
        honorCards.removeAll()
        honorGeneration += 1
        let generation = honorGeneration
        let engine = HonorEngine(
            way: way,
            // A stage has no other walker to be off the way *from*; the soft
            // tap and the companion dot are both about someone else.
            softTapEnabled: UserPreferences.honorSoftTapEnabled.value && !way.isPilgrimageStage,
            voicesEnabled: UserPreferences.honorVoicesEnabled.value && UserPreferences.soundsEnabled.value
        )
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:47-59@7c200bf

Before Start, the walk screen already draws the Way:

```swift
        // The Way is drawn from the moment the walk screen appears, not from
        // the moment the engine starts at Begin.
        if mode == .honor { refreshHonorPins() }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:241-243@7c200bf

(The engine comment says "Begin", but the call is `startRecording`, the Start tap.) Android already has the same two-step shape: the Path button opens the active-walk surface, and the walk starts at its Start button (`WalkStartScreen.kt:297-304@5ea4029b`, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt:1152-1155@5ea4029b`, its location-permission launcher at `ActiveWalkScreen.kt:436-445@5ea4029b`).

### 13. What blocks Begin

#### 13.1 The button's own gate

Begin is disabled only while an import is fetching or gathering:

```swift
    /// `.fetching` too, not only `.gathering`: a link tapped for a different
    /// Way while this overview is up can still swap the sheet out from under
    /// a Begin tap, so Begin stays disabled for the whole window a fetch or a
    /// download could still land.
    private var isGathering: Bool {
        switch importState {
        case .gathering, .fetching: return true
        default: return false
        }
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:292-301@7c200bf

For an own walk the state is `.ready` (§7.2), so Begin is enabled, except in the window after a walk link is tapped while this overview is open (`openWay` sets the global state to `.fetching`, `MainCoordinatorView.swift:212-216@7c200bf`). If that fetch fails, the failure line stays on the own-walk overview in rust until it closes, and Begin is enabled again (`.failed` is not gathering). That interplay ports with U26–U28.

#### 13.2 Distance never blocks

`distanceToStart` feeds only the status row (§10.6). There is no "too far" state and no confirmation.

#### 13.3 Another walk active

`startWalk` returns silently when a walk exists (§12.1). In practice the overview cannot be reached during a walk: the walk is a full-screen cover over the whole tab view, so neither the Path tab nor a journal summary is reachable:

```swift
        .fullScreenCover(item: $coordinator.activeWalkViewModel, onDismiss: {
            coordinator.handleActiveWalkDismiss()
        }) { vm in
            ActiveWalkView(viewModel: vm, onCancel: { coordinator.cancelWalk() })
```
> Pilgrim/Scenes/Root/MainTabView.swift:42-45@7c200bf

The link path's mid-walk refusal (`"finish this walk first"`, `MainCoordinatorView.swift:213@7c200bf`) is U27.

#### 13.4 Permissions

Location denied or restricted: the `"Location Required"` alert after the overview closes, and the Way is dropped (§12.1). No other permission is checked at Begin. Microphone, notifications, and motion are the walk screen's, as for every mode.

#### 13.5 Missing media

For an own walk, voices whose recording file is missing or empty are omitted when the Way is built (§5.3); nothing blocks. Photos reference PhotoKit assets; a deleted or cloud-only asset simply fails to load in the preview (§14.4). The `.mediaMissing` choice (`"try again"` / `"walk without the missing voices"`) exists only for shared Ways (U28).

### 14. The moment preview (`WayMomentPreview`)

#### 14.1 Presentation

A tapped moment pin opens a half-height sheet over the overview:

```swift
        .sheet(item: $previewMoment) { moment in
            WayMomentPreview(
                way: way,
                moment: moment,
                mediaURL: moment.media.flatMap { ActiveWalkViewModel.localMediaURL(for: $0, wayId: way.id, store: WayStore.shared) }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:179-187@7c200bf

For an own walk, a voice's media is `.recording(relativePath:)` and resolves under Documents; a photo is `.photoAsset`, which has no URL (the plate loads it from PhotoKit):

```swift
    static func localMediaURL(for media: WayMedia, wayId: String, store: WayStore) -> URL? {
        switch media {
        case .recording(let relativePath):
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            return resolvedMediaURL(docs.appendingPathComponent(relativePath), within: docs)
        case .file(let relative):
            let url = store.mediaURL(for: wayId, relative: relative)
            return resolvedMediaURL(url, within: store.mediaDirectory(for: wayId))
        case .photoAsset:
            return nil
        }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:354-365@7c200bf

The preview's frame:

```swift
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Constants.UI.Padding.normal) {
                header
                content
            }
            .padding(Constants.UI.Padding.normal)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color.parchment)
        .onDisappear { player.stop() }
        .task(id: mediaURL) { await loadWaveform() }
    }
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:25-37@7c200bf

No title bar and no close button: the drag indicator and swipe close it. Playback stops when it closes.

#### 14.2 The header

`WayMomentHeader` in its full (non-compact) size: a 52 pt parchment disc with the glyph in heading size, stone; then the kicker, the local name, and the subline. The compact size and the heading tick belong to the walk's cards (cluster E).

```swift
        HStack(alignment: .top, spacing: compact ? Constants.UI.Padding.small : Constants.UI.Padding.normal) {
            ZStack {
                Circle().fill(Color.parchment).frame(width: compact ? 36 : 52, height: compact ? 36 : 52)
                Image(systemName: Self.glyph(for: moment))
                    .font(compact ? Constants.Typography.body : Constants.Typography.heading)
                    .foregroundColor(.stone)
            }
            VStack(alignment: .leading, spacing: Constants.UI.Padding.xs) {
                Text(Self.kicker(for: moment))
                    .font(compact ? Constants.Typography.body : Constants.Typography.heading)
                    .foregroundColor(.ink)
                if let localName = Self.localName(for: moment) {
                    Text(localName)
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                        .lineLimit(1)
                }
                if let subline {
                    HStack(spacing: 4) {
                        if let tick {
                            ...
                        }
                        Text(subline).font(Constants.Typography.caption).foregroundColor(.fog)
                    }
                }
            }
        }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:14-45@7c200bf

The disc is parchment on a parchment sheet, so it is invisible there; only the glyph shows.

Glyph and kicker per kind:

```swift
    static func glyph(for moment: WayMoment) -> String {
        switch moment.kind {
        case .voice(_, _, let kind, _): return kind == .ambient ? "wind" : "waveform"
        case .photo: return "photo"
        case .rest: return "cup.and.saucer"
        case .meditation: return "circle.circle"
        case .waypoint(_, let icon): return UIImage(systemName: icon) == nil ? "mappin" : icon
        }
    }

    static func kicker(for moment: WayMoment) -> String {
        switch moment.kind {
        case .voice(_, _, let kind, _): return kind == .ambient ? "the sound of this place" : "spoken here"
        case .photo: return "what they saw here"
        case .rest(let minutes): return "they rested here \(minutes) minutes"
        case .meditation(let minutes, let isEstimate):
            return isEstimate ? "they sat here about \(minutes) minutes" : "they sat here for \(minutes) minutes"
        case .waypoint(let label, _): return label
        }
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:48-67@7c200bf

An own walk's sittings are built with `isEstimate: false` (`OwnWalkWayBuilder.swift:88-89@7c200bf`), so they read `"they sat here for N minutes"`. The local name needs `moment.names`, which own walks never carry, so it never shows here.

The subline (a stage drops the hour and says "along the stage"; Stage 21-2):

```swift
    /// "1.2 km along their way · 8:41 AM · Rúa do Franco": the moment's place
    /// on the line, the hour it happened in the walk's own time zone, and the
    /// street the sharer's page names when it has one. A stage keeps the
    /// distance and drops the hour: its clock is synthesized by the build.
    private static func alongTheWay(way: Way, moment: WayMoment) -> String {
        let distance = StatsHelper.string(for: moment.frac * way.totalDistanceMeters, unit: UnitLength.meters, type: .distance)
        var parts: [String] = []
        if way.isPilgrimageStage {
            parts.append("\(distance) along the stage")
        } else {
            let elapsed = WayGeometry(route: way.route).elapsed(atFrac: moment.frac)
            let formatter = DateFormatter()
            formatter.timeStyle = .short
            formatter.timeZone = way.tzIdentifier.flatMap(TimeZone.init(identifier:)) ?? .current
            parts.append("\(distance) along their way")
            parts.append(formatter.string(from: way.departedAt.addingTimeInterval(elapsed)))
        }
        if let place = moment.place, !place.isEmpty { parts.append(place) }
        return parts.joined(separator: " · ")
    }
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:45-64@7c200bf

For an own walk: `"<distance> along their way · <h:mm a>"`. There is no place (own walks carry none), and the zone is the one the phone was in when the Way was built (`tzIdentifier: TimeZone.current.identifier`, §5.3). The distance uses `StatsHelper` (always km or mi, two decimals).

#### 14.3 The body, per kind

```swift
        switch moment.kind {
        case .voice(_, let duration, let kind, _):
            voiceContent(duration: duration, kind: kind)
        case .photo(let media):
            VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
                WayPhotoPlate(media: media, fileURL: mediaURL, maxHeight: 360)
                Text("tap the photo to see it whole").font(Constants.Typography.caption).foregroundColor(.fog)
            }
        case .waypoint:
            VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
                Text(WayMomentHeader.placeCopy(for: moment, isStage: way.isPilgrimageStage))
                    .font(Constants.Typography.body).foregroundColor(.ink)
                if let minutes = moment.sitMinutes, minutes > 0 {
                    Text("When you walk it, the way will offer you \(minutes) minutes of sitting here.")
                        .font(Constants.Typography.caption).foregroundColor(.fog)
                } else {
                    Text("When you walk it, it rises as a card as you reach it.")
                        .font(Constants.Typography.caption).foregroundColor(.fog)
                }
            }
        case .rest:
            Text("A pause in their walk. The companion will wait here with you.")
                .font(Constants.Typography.body).foregroundColor(.ink)
        case .meditation:
            Text("A sitting. When you walk it, the way will offer you the same sitting here.")
                .font(Constants.Typography.body).foregroundColor(.ink)
        }
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:70-96@7c200bf

```swift
    static func placeCopy(for moment: WayMoment, isStage: Bool) -> String {
        if let text = moment.text, !text.isEmpty { return text }
        return isStage ? "A place on the way." : "A place they marked."
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:86-89@7c200bf

Own-walk waypoints carry neither `text` nor `sitMinutes`, so a waypoint reads `"A place they marked."` then `"When you walk it, it rises as a card as you reach it."`

The voice body:

```swift
    private func voiceContent(duration: Double, kind: VoiceKind) -> some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
            if let transcript = moment.transcript {
                Text("“\(transcript)”")
                    .font(Constants.Typography.body).italic().foregroundColor(.ink)
                    .padding(.bottom, Constants.UI.Padding.xs)
            }
            if let mediaURL {
                HStack(spacing: Constants.UI.Padding.small) {
                    Button { player.toggle(url: mediaURL) } label: {
                        Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(Constants.Typography.displayMedium).foregroundColor(.stone)
                    }
                    .accessibilityLabel(player.isPlaying ? "Pause their voice" : "Play their voice")
                    VStack(alignment: .leading, spacing: 2) {
                        Text(kind == .ambient ? "as it sounded" : "in their own voice")
                            .font(Constants.Typography.body).foregroundColor(.ink)
                        Text(clock(duration)).font(Constants.Typography.caption).foregroundColor(.fog)
                    }
                    Spacer()
                    Button { player.cycleSpeed() } label: {
                        Text(speedLabel)
                            .font(Constants.Typography.caption)
                            .foregroundColor(player.playbackSpeed > 1 ? .parchment : .stone)
                            .padding(.horizontal, 6).padding(.vertical, 3)
                            .background(player.playbackSpeed > 1 ? Color.stone : Color.stone.opacity(0.12))
                            .cornerRadius(4)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("Playback speed, \(speedLabel)")
                }
                if let waveform {
                    WaveformBarView(samples: waveform, progress: player.progress, isPlaying: player.isPlaying) { fraction in
                        if player.currentPath != mediaURL.path { player.play(url: mediaURL) }
                        player.seek(to: fraction)
                    }
                } else {
                    RoundedRectangle(cornerRadius: 4).fill(Color.fog.opacity(0.15)).frame(height: 32)
                }
                HStack {
                    Text(clock(player.currentTime)).monospacedDigit()
                    Spacer()
                    Text(clock(player.totalDuration > 0 ? player.totalDuration : duration)).monospacedDigit()
                }
                .font(Constants.Typography.caption).foregroundColor(.fog)
            } else {
                HStack(spacing: Constants.UI.Padding.small) {
                    Image(systemName: "waveform.slash").foregroundColor(.fog)
                    Text("their voice is still on its way here").font(Constants.Typography.body).foregroundColor(.fog)
                }
            }
            Text(kind == .ambient
                 ? "When you walk it, this plays once, softly, as you pass."
                 : "When you walk it, this plays where they stood to say it.")
                .font(Constants.Typography.caption).foregroundColor(.fog)
        }
    }

    private var speedLabel: String {
        player.playbackSpeed.truncatingRemainder(dividingBy: 1) == 0
            ? String(format: "%.0fx", player.playbackSpeed)
            : String(format: "%gx", player.playbackSpeed)
    }

    private func clock(_ seconds: Double) -> String {
        let total = Int(max(0, seconds))
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    private func loadWaveform() async {
        guard case .voice = moment.kind, let mediaURL else { return }
        waveform = await Task.detached(priority: .utility) {
            WaveformGenerator.generateSamples(from: mediaURL)
        }.value
    }
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:99-174@7c200bf

- The transcript sits in curly quotes (U+201C, U+201D), italic body. It is the full trimmed transcript (capped at 600 characters, `Way.swift:68-74@7c200bf`), not the 120-character card line.
- Spoken vs ambient comes from the builder's transcript classifier (cluster A).
- The player is a private `AudioPlayerModel` per preview, speeds 1, 1.5, 2, labelled `"1x"`, `"1.5x"`, `"2x"`; its rate dies with the preview:

```swift
    private static let speeds: [Float] = [1.0, 1.5, 2.0]
```
> Pilgrim/Scenes/WalkSummary/AudioPlayerModel.swift:11@7c200bf

  This is not the Way voice player that plays on the walk (`static let rates: [Float] = [1, 1.25, 1.5, 2]`, `Pilgrim/Models/Honor/WayVoicePlayer.swift:32@7c200bf`; cluster C). Android's summary recordings already cycle the same three speeds (`SPEED_CYCLE`, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListViewModel.kt:42@5ea4029b`).
- The waveform is computed off the main thread at utility priority, with a 32 pt placeholder bar (radius 4, fog at 0.15) until it lands. Tapping the waveform before play starts playback, then seeks.
- The waveform view itself (bars fog at 0.4, progress stone, 32 pt tall) is `WaveformBarView` (`Pilgrim/Scenes/Settings/RecordingsListView.swift:529-594@7c200bf`).
- A voice with no local file shows `"their voice is still on its way here"`. For an own walk this happens only if the recording was deleted after the Way was built.

#### 14.4 The photo plate and viewer

```swift
struct WayPhotoPlate: View {
    let media: WayMedia
    let fileURL: URL?
    var maxHeight: CGFloat = 160
    @State private var image: UIImage?
    @State private var enlarged = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: maxHeight).cornerRadius(4)
                    .padding(6).background(Color.parchment).cornerRadius(6)
                    .onTapGesture { enlarged = true }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityLabel("Enlarge photo")
            } else {
                RoundedRectangle(cornerRadius: 6).fill(Color.parchment).frame(height: min(maxHeight, 120))
            }
        }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:274-293@7c200bf

An own-walk photo loads from PhotoKit at 900×900, never from the network:

```swift
        case .photoAsset(let id):
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil)
            guard let asset = assets.firstObject else { return }
            let options = PHImageRequestOptions()
            options.deliveryMode = .opportunistic
            options.isNetworkAccessAllowed = false
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 900, height: 900),
                                                  contentMode: .aspectFit, options: options) { result, _ in
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:310-317@7c200bf

A deleted or cloud-only photo leaves the 120 pt parchment placeholder, with the caption `"tap the photo to see it whole"` still under it. The full-screen viewer (black, pinch 1–4×, tap or a 100 pt swipe down to close, `"Close photo"`) is `Pilgrim/Scenes/Honor/WayPhotoViewer.swift:6-55@7c200bf`; it is shared with the walk's cards (cluster E).

### 15. The intention step on an Honor walk

There is no Honor-specific intention step. `IntentionSettingView` contains no Honor code: a search of the file for `honor`, `way`, `mode`, and `seek` finds only the Seek-only `allowsSkip` flag and the unrelated suggestion `"Honor the stillness"` (`Pilgrim/Scenes/ActiveWalk/IntentionSettingView.swift:7-9,211@7c200bf`).

An Honor walk takes the Wander path: the sheet is skippable and swipe-dismissable, because only Seek is special-cased:

```swift
        .sheet(isPresented: $showIntention) {
            IntentionSettingView(
                historyStore: intentionHistory,
                allowsSkip: viewModel.mode != .seek,
                onSet: { intention in
                    viewModel.intention = intention
                    showIntention = false
                    viewModel.advanceSeekSetupIntentionSet()
                },
                onDismiss: { showIntention = false }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationBackground(Color.parchment.opacity(0.95))
            .interactiveDismissDisabled(viewModel.mode == .seek)
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:321-336@7c200bf

It auto-opens 0.5 s after the walk screen appears, before Start, when "begin with intention" is on (default off) and no intention is set, exactly as for Wander:

```swift
            // Seek drives the intention step from its own stage machine —
            // the wander-only auto-present would double-fire the sheet.
            if viewModel.mode != .seek,
               UserPreferences.beginWithIntention.value && viewModel.intention == nil {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    showIntention = true
                }
            }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:428-435@7c200bf

```swift
    static let beginWithIntention = UserPreference.Required<Bool>(key: "beginWithIntention", defaultValue: false)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:70@7c200bf

Nothing pre-fills the intention from the Way. The new walk's intention is saved as its `comment` when it finishes (`snapshot.comment = vm?.intention`, `MainCoordinatorView.swift:96@7c200bf`), and that comment becomes the title of any Way later built from this walk (§5.3). The Way's own title stays on Honor surfaces; the new intention does not replace it.

Android: the pre-walk auto-intention already mirrors iOS for every non-Seek mode, with the same 500 ms delay and the same Seek-only exclusion (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt:127,588-639,685-690@5ea4029b`; Seek's ritual is `ActiveWalkScreen.kt:648-652@5ea4029b`). An Honor mode takes the Wander branch with no change.

### 16. The journal glyph (`WalkModeFootprints`)

The ink scroll's expanded quick view wears a mode miniature. Honor wins over Seek:

```swift
                            WalkModeFootprints(
                                mode: snapshot.isHonor ? .honor : (snapshot.isSeek ? .seek : .wander),
                                color: seasonColor.opacity(0.3)
                            )
```
> Pilgrim/Scenes/Home/InkScrollView.swift:354-357@7c200bf

An archived walk shows a stroked print instead, whatever its mode (`InkScrollView.swift:349-353@7c200bf`). `isHonor` is one bulk query for `.honorMode` events:

```swift
        let seekWalkIDs = fetchWalkIDs(withEvent: .seekMode)
        let honorWalkIDs = fetchWalkIDs(withEvent: .honorMode)
```
> Pilgrim/Scenes/Home/HomeViewModel.swift:91-92@7c200bf

```swift
                isHonor: walk.uuid.map(honorWalkIDs.contains) ?? false,
```
> Pilgrim/Scenes/Home/HomeViewModel.swift:145@7c200bf

The miniature, static and decorative. Unlike the path glyph, the row is centre-aligned with 2 pt spacing, and the staff stroke is 1 pt with no round cap:

```swift
struct WalkModeFootprints: View {
    let mode: WalkModeGlyph
    let color: Color

    var body: some View {
        HStack(spacing: 2) {
            FootprintShape()
                .fill(color)
                .frame(width: 10, height: 16)
                .scaleEffect(x: -1)
                .rotationEffect(.degrees(-12))
            switch mode {
            case .seek:
                dissolvingDots
                    .frame(width: 10, height: 18)
                    .rotationEffect(.degrees(12))
            case .honor:
                StaffGlyph()
                    .stroke(color, lineWidth: 1)
                    .frame(width: 8, height: 14)
            case .wander:
                FootprintShape()
                    .fill(color.opacity(0.75))
                    .frame(width: 10, height: 16)
                    .rotationEffect(.degrees(12))
            }
        }
        .accessibilityHidden(true)
    }
```
> Pilgrim/Views/WalkModeFootprints.swift:14-42@7c200bf

At 8×14 the staff's absolute ±2 pt crossbar offset (§3.3) is a steep tilt relative to the frame; Android must draw it in dp, not as a fraction.

Android: `WalkModeFootprints(isSeek:color:)` takes a Boolean today and clears semantics (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/WalkModeFootprints.kt:69-78@5ea4029b`); an Honor branch needs a mode parameter. The staffs scenery (`honorArrivals`) and the snapshot mode are cluster G's.

### 17. Accessibility, per surface

Across the whole cluster iOS sets **no accessibility hints** and **no custom grouping** (`accessibilityElement(children:)`), apart from the reused waveform view. Where no label is set, VoiceOver reads the visible text of the element or of the button's label contents. The quotes below are every explicit accessibility modifier in these files.

#### 17.1 Path tab slot

- Mode buttons: label = raw value, lowercase (`"wander"`, `"honor"`, `"seek"`); Button trait, plus Selected on the chosen one (`WalkStartView.swift:337-338@7c200bf`, §3.2).
- Footprint glyphs: hidden (`.accessibilityHidden(true)`, `WalkStartView.swift:236@7c200bf`).
- Subtitle: plain text, `"walk in their steps"` for Honor.
- Start button: label `"Begin your journey"`, identifier `"start_walk_button"`; the visible `"Honor"` is not spoken (§3.5).
- The selector row caps Dynamic Type at xxxLarge (`WalkStartView.swift:341@7c200bf`). At accessibility2 and above the page shrinks the logo to 60 and drops the moon:

```swift
    private var isHomeLargeText: Bool {
        homeTypeSize >= .accessibility2
    }
```
> Pilgrim/Scenes/Home/WalkStartView.swift:139-141@7c200bf

- Order: logo, quote, moon, WANDER, HONOR, SEEK, subtitle, start button.

#### 17.2 Ways sheet

- Navigation title `"Choose a way"`; `"Close"` button.
- Section headers `"Shared with you"`, `"Your own walks"`, `"A pilgrimage"`, `"From a shared walk"`, exposed by the system list as section headers.
- The own-walk row is a Button whose spoken label comes from its contents, `"Walk one of yours again"`; no explicit label, hint, or value (§4.2).
- The empty copy is plain text.
- Order: Close, then sections top to bottom.

#### 17.3 Own-walk picker

- Navigation title `"Walk again"`; `"Close"` button.
- Each row is a Button read from its two texts, title then distance (`HonorWaysSheet.swift:168-170,190-199@7c200bf`).
- Empty copy is plain text.
- Alert: title `"Can't walk this one again"`, message `"This walk doesn't have enough of a route to follow. Try another."`, button `"OK"` (cancel role).

#### 17.4 Summary door

- A Button built from `Label("walk this again", systemImage: "signpost.right")`; spoken as its title. No hint (§6.1).

#### 17.5 Overview

- Explicit modifiers: only Begin's label.

```swift
            .accessibilityLabel(way.isPilgrimageStage ? "Walk this stage" : "Begin honoring this way")
            .disabled(isGathering)
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:285-286@7c200bf

- `"Close"`: plain Button text.
- The map: no label. The Way pins are Mapbox point annotations, drawn as style layers (`mapView.annotations.makePointAnnotationManager(...)`, `PilgrimMapView.swift:393-395@7c200bf`), not accessibility elements, so a VoiceOver user cannot open a moment preview. No accessibility modifiers exist in `Pilgrim/Views/PilgrimMapView*.swift` at the pin beyond Reduce Motion checks for Seek.
- The card has no grouping, so every `Text` is its own element, the two `"·"` separators in the stats row included.
- The toggle: label `"walk with their voice"`, the system switch value (on or off), dimmed when disabled.
- Begin: label `"Begin honoring this way"`, Button trait, dimmed while gathering.
- Reduce Motion: the fit's 0.4 s ease is not gated on it (`PilgrimMapView.swift:285@7c200bf`).
- Order: Close, (DEBUG ladybug), map, title, departure line, distance, `"·"`, duration, `"·"`, counts, (import line), (weather), (distance to start), toggle, Begin.

#### 17.6 Moment preview

- Header: the glyph image is **not** hidden (only the walk card's heading tick is, `WayMomentHeader.swift:39@7c200bf`), so VoiceOver may announce the SF Symbol; then the kicker; the local name; the subline.
- Voice: transcript text; the play button; `"in their own voice"` or `"as it sounded"`; the duration; the speed button; the waveform; the two clocks; the footer caption.

```swift
                    .accessibilityLabel(player.isPlaying ? "Pause their voice" : "Play their voice")
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:112@7c200bf

```swift
                    .accessibilityLabel("Playback speed, \(speedLabel)")
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:129@7c200bf

  The preview's speed button has no hint; the recordings list's copy of the same button adds `"Double tap to cycle speed"` (`RecordingsListView.swift:247-248@7c200bf`). The preview does not.

  The waveform is one adjustable element:

```swift
        .accessibilityElement()
        .accessibilityLabel("Playback position")
        .accessibilityValue("\(Int((progress * 100).rounded())) percent")
        .accessibilityAdjustableAction { direction in
```
> Pilgrim/Scenes/Settings/RecordingsListView.swift:568-571@7c200bf

  Swipe up and down step it by 10%, clamped to 0–100% (`RecordingsListView.swift:583-593@7c200bf`). Before the waveform lands, the placeholder bar is not an element.
- Missing voice: the `"waveform.slash"` image (not hidden), then `"their voice is still on its way here"`.
- Photo: the plate is `"Enlarge photo"` with the Button trait (`WayPlaceCard.swift:288-289@7c200bf`); the placeholder is not an element. The viewer's image and its close button are both `"Close photo"`, the image with the Button trait (`WayPhotoViewer.swift:42-43,52@7c200bf`).
- Waypoint, rest, sitting: plain texts in order.

#### 17.7 Journal glyph

Hidden (`WalkModeFootprints.swift:41@7c200bf`); Android already clears its semantics (`WalkModeFootprints.kt:78@5ea4029b`).

### Resolutions for the plan

1. **Every own-walk door.** Two doors, both ending at the overview (§2).
   - Path tab, Honor slot: visible `"Honor"`, VoiceOver `"Begin your journey"`, always enabled. It opens the Ways sheet `"Choose a way"`, then the row `"Walk one of yours again"` (chevron), then the picker `"Walk again"`, then a walk row, then the overview (§3.5, §4, §5).
   - Summary: `"walk this again"` with the `signpost.right` symbol, stone, button font, last in the scroll column after the share buttons. It shows when the walk's route has at least two points and the host can present the overview: the post-walk summary and the journal summary (ink scroll and Goshuin), not the Recordings list. It skips the sheet and picker and opens the overview once the summary has closed (§6).
   - Neither door needs recordings, photos, or any moment; a Way with none reads `"a quiet way"`.
   - No other surface is a door: not the Settings Ways list, not the ink-scroll quick view (§2).
   - The plan's "walks with routes" for the picker is looser at the pin: the picker lists walks whose stored distance is above zero, newest first. The route test runs only on tap, and fails into the alert `"Can't walk this one again"` (§5.1, §5.3).
2. **The mode picker.** Order WANDER, HONOR, SEEK. Honor takes Together's old middle slot (`40ef3fc`), and every mode is available (§3.1).
   - Honor slot: label `"HONOR"`, VoiceOver `"honor"` with the Selected trait. Subtitle `"walk in their steps"`. Quotes `Honor.Quote.1–3`. The footprint is one print plus a staff, 60×50 frame. Underline is stone at 0.3, 1, 0.3. The atmosphere is stone at 0.015 (§3.2–§3.4).
   - The bottom button reads `"Honor"`. The plan's "Begin reads 'Choose a way' until a Way is chosen" is stale: `3e9e67d`, before the pin, renamed it. U21's scenario "Begin stays disabled until a Way is chosen" is not iOS behavior either: the Path button is enabled and opens the sheet, and the overview's Begin is enabled for own walks (§3.5, §13.1).
   - No "coming soon" state remains on iOS. The `"coming soon"` subtitle is dead code (`isAvailable` is always `true`). On Android it survives only as the flag-off state (AE12).
   - Two pre-existing Android drifts sit in the same slot: the unselected label alpha (0.3 vs iOS 0.55), and the start button's unused `"Begin your journey"` description (§3.6).
3. **`HonorWaysSheet`.** Four sections in this order: `"Shared with you"` (U28 rows), `"Your own walks"` (U21), `"A pilgrimage"` (Stage 21-2), and `"From a shared walk"` (U28 paste field) (§4.2).
   - The own-walk section is one nav row, `"Walk one of yours again"`.
   - There are two empty copies. The sheet's first section shows `"no ways yet. Accept a shared walk, or walk one of yours again."` to every walker with no accepted shares. The picker shows `"walk somewhere first. Any walk with a route can be walked again."`
   - Picker rows are the title (intention, else the medium date) and the distance, in body/ink and caption/fog, 2 pt apart (§5.2).
   - A row tap builds the Way on the spot. A nil build gives the alert, and the picker stays up. A Way closes both sheets, then presents the overview (§4.4, §7.1).
   - Saved own-walk Ways are never listed; the picker always rebuilds from the walk (§4.2).
   - Whether U21 ships section 1 with its empty copy, or starts the sheet at "Your own walks", is the plan's staging call. iOS always shows section 1 first.
4. **`HonorOverviewView`.**
   - Layout tree §8. Strings and formats §10: title, long-date departure line, `distance · Nh Mm · counts`, weather line, distance-to-start line, toggle `"walk with their voice"`, and `"Begin"` (VoiceOver `"Begin honoring this way"`).
   - Framing §9. The card's measured height goes in as `bottomInset`. The fit pads 40 (top), 30 (left and right), and `40 + min(cardHeight, mapHeight − 240)` (bottom). It refits only when the bounds or inset change, eases over 0.4 s, and records the fit only once it lands. `maxZoom` is nil; rotate and pitch are off; the camera never follows the puck. The ornaments stay under the card on iOS.
   - States §11. An own walk has no loading, error, or empty screen. Missing weather or fix drops that row, and no voices gives `"a quiet way"` and a disabled toggle.
   - Begin flow §12. Begin parks the Way, closes the overview, then opens the walk screen in its pre-walk state with `mode: .honor` and the Way value. Nothing else is handed over, and nothing is confirmed.
   - The walk itself (the Honor marker event, the engine, voices) starts at the walk screen's **Start** tap (`startRecording`), not at Begin. So iOS's counterpart of the plan's "Begin mints the uuid and starts through `WalkViewModel.startWalk`" is the Start tap. The overview's Begin only navigates. Android already has this two-step shape (§12.3).
   - The toggle writes the global `honorVoicesEnabled` preference at once, default on and sticky across walks. The engine reads it at Start, ANDed with the app-wide sounds switch (§10.8). So "the toggle's value rides the start intent" matches iOS only if the sounds switch is folded in too.
   - An own-walk Way is first saved to the store when the walk saves (§12.2). Android's staging at Begin is the plan's own decision (KTD); iOS has no staging.
5. **What blocks Begin** (§13).
   - Begin disables only while a shared import is fetching or gathering. For an own walk that happens only if a walk link is tapped while the overview is open (U28 interplay).
   - Distance never blocks.
   - A second walk is refused silently in `startWalk`, but it is unreachable: the walk's full-screen cover hides every door.
   - Location denied or restricted gives the `"Location Required"` alert after the overview closes, and the Way is dropped.
   - Missing own-walk recordings are omitted at build time, so nothing blocks on media. The `"try again"` / `"walk without the missing voices"` pair is shared-Way only.
   - iOS has no refusal for "staging failed" or "the Way was deleted while the overview was open". For an own walk the Way lives in memory until walk end, so iOS has nothing to refuse. U21's two refusal tests are Android hardening with no iOS copy (see Open questions).
6. **The intention step.** Nothing Honor-specific (§15).
   - An Honor walk takes the Wander path: it auto-opens 0.5 s after the walk screen appears when "begin with intention" is on (default off). It is skippable and swipe-dismissable.
   - Nothing is pre-filled from the Way. The Way's title stays on Honor surfaces.
   - Android's existing non-Seek auto-intention covers Honor unchanged.
7. **Accessibility** (§17). No hints and no custom grouping anywhere in the cluster. The explicit labels to pin in `HonorOverviewSemanticsTest` and the slot and picker tests:
   - `"honor"` (Selected) and `"Begin your journey"` on the Path tab.
   - `"walk this again"` on the summary.
   - `"Begin honoring this way"` and the toggle's `"walk with their voice"` with its state on the overview.
   - In the preview: `"Play their voice"` / `"Pause their voice"`, `"Playback speed, 1x"` (the value in the label), `"Playback position"` with the value `"N percent"` (adjustable, ±10%), `"Enlarge photo"` (Button), and `"Close photo"`.
   - VoiceOver order per surface is in §17.1–§17.6. The overview's order is Close, map, then the card top to bottom, each `Text` its own element.
   - Way pins are not reachable with VoiceOver on iOS (a defect below). Android should not copy the gap silently; the gate decides (R5/R6).

### iOS defects found

Candidates for upstream issues; none filed.

1. **"walk this again" silently does nothing on a short route.** The button shows for any route of two or more points, but the builder rejects routes under 20 m. The tap closes the summary and nothing opens. The picker shows an alert for the same case.

```swift
        if cachedRouteCoordinates.count >= 2, let onWalkAgain {
            Button {
                onWalkAgain(walk)
                dismiss()
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:681-684@7c200bf

```swift
    /// A nil build (OwnWalkWayBuilder.make(from:) rejecting too short a route or a missing uuid) parks nothing and no overview appears; every host wiring `onWalkAgain` must also wire its summary's `onDismiss` to `promotePendingHonorWay`, since the park is global but the promote is per-host.
    func walkAgain(_ walk: WalkInterface) {
        pendingHonorWay = OwnWalkWayBuilder.make(from: walk)
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:307-310@7c200bf

   Impact: a walker taps the button, the summary vanishes, and there is no feedback. It hits every short walk (a walk to the mailbox, a sitting with little movement).

2. **The picker offers walks that can never be walked again.** It filters on stored distance only. Archived walks keep their distance but lose their route, so they are listed and every tap ends in `"Can't walk this one again"`. The same goes for any walk whose route is under 20 m.

```swift
        eligible = walks.filter { $0.distance > 0 }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:156@7c200bf

```swift
        for sample in walk._routeData.value { transaction.delete(sample) }
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:458@7c200bf

   Impact: dead rows. A walker who archived many walks through the web editor sees a list that mostly fails.

3. **"walk with their voice" shows on while the app's sounds are off.** The toggle reads and writes only `honorVoicesEnabled`; the engine also requires `soundsEnabled`.

```swift
    @State private var voicesEnabled = UserPreferences.honorVoicesEnabled.value
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:80@7c200bf

```swift
            voicesEnabled: UserPreferences.honorVoicesEnabled.value && UserPreferences.soundsEnabled.value
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:58@7c200bf

   Impact: the walker switches voices on, walks the whole Way, and hears nothing, with no explanation.

4. **The overview's temperature ignores the unit setting** (already on the plan's iOS list). It prints rounded Celsius with a bare degree sign.

```swift
        if let t = theirs.temperatureC, t.isFinite { line += " at \(Int(min(max(t.rounded(), -1000), 1000)))°" }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:31@7c200bf

   Impact: a Fahrenheit walker reads "at 9°" for a 48 °F morning.

5. **The overview card covers the Mapbox logo and attribution** (already on the plan's list). The inset pads only the camera; the ornaments stay bottom-leading under the card.

```swift
        mapView.ornaments.options.attributionButton.position = .bottomLeading
```
> Pilgrim/Views/PilgrimMapView.swift:152@7c200bf

   Impact: Mapbox's terms require the ornaments to stay visible on the map view.

6. **The moment previews are unreachable with VoiceOver, and the card reads its separators.** The Way pins are point annotations with no accessibility element, so the only way into a preview is a visual tap. The stats row's `"·"` texts are separate elements. The preview header's glyph is not hidden.

```swift
                coordinator.pointManager = mapView.annotations.makePointAnnotationManager(layerPosition: pos)
```
> Pilgrim/Views/PilgrimMapView.swift:393@7c200bf

```swift
            HStack {
                Text(StatsHelper.string(for: way.totalDistanceMeters, unit: UnitLength.meters, type: .distance))
                Text("·")
                Text(durationText(way.theirActiveSeconds))
                Text("·")
                Text(HonorOverviewModel.countsLine(way: way))
            }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:236-242@7c200bf

   Impact: a VoiceOver walker cannot hear what a Way holds before walking it, and the card reads with stray separators.

7. **A failed walk link leaves its error on an own-walk overview.** The import state is global. A link tapped while an own-walk overview is open sets it to `.fetching`, and a failure sets `.failed`. The own-walk overview renders that line in rust, and nothing resets it, because `gather` installs no sink for an own walk.

```swift
                self.honorImportState = .failed(failure)
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:237@7c200bf

```swift
        if let line = HonorImportCopy.line(for: importState) {
            Text(line)
                .font(Constants.Typography.caption)
                .foregroundColor(isTrouble ? .rust : .fog)
        }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:315-319@7c200bf

   Impact: low. The own walk's card says `"couldn't find that walk…"` about a different walk until the overview closes; Begin still works.

8. **The preview's hour is not in "the walk's own time zone" for an own walk.** The preview's comment promises the walk's zone, but the own-walk builder stamps the zone the phone is in when the Way is built.

```swift
            departedAt: walk.startDate, tzIdentifier: TimeZone.current.identifier, expires: nil,
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:110@7c200bf

   Impact: low. A walk recorded in another time zone previews its moments at shifted hours. iOS walks store no zone, so the fix may be the comment rather than the code.

### Open questions

1. **Android-only summary hosts.** Android opens a summary from the widget deep link (`PilgrimNavHost.kt:672@5ea4029b`); iOS has no such host. iOS's rule is "hosts that can present the overview show the door". Whether the widget-opened summary counts is not answered by iOS.
2. **Copy for Android's Begin refusals.** U21 tests a Begin refused because staging failed, or because the Way was deleted while the overview was open. iOS has neither case and so no string. The nearest iOS copy is the picker alert (`"Can't walk this one again"` / `"This walk doesn't have enough of a route to follow. Try another."`), which does not fit either cause.
## G. After the walk: summary, journal, scenery, seal, milestones, prompts

iOS pin: `7c200bf` (pilgrim-ios v2.0.0). Android reference: HEAD `5ea4029b`. Feeds plan unit U23.

Scope: the own-walk slice. Where iOS branches on a pilgrimage stage (`way?.stage != nil`) or on a shared Way, the branch point is named in one line and left for the later specs.

Lenses applied: behavior (state, ordering, when data is read), UI/visual (layout, colour, size, type, stroke), data (what is read from events, waypoints, the Way store, and the seal cache), edge cases (thresholds, precedence, degenerate input, missing data).

Token lookups used below (iOS `Constants.swift`), so later quotes can name them without re-quoting:

```swift
        public enum Padding {
            public static let xs: CGFloat = 4
            public static let small: CGFloat = 8
            public static let normal: CGFloat = 16
            public static let big: CGFloat = 24
            public static let breathingRoom: CGFloat = 64
        }

        public enum CornerRadius {
            public static let small: CGFloat = 8
            public static let normal: CGFloat = 12
            public static let big: CGFloat = 20
        }
```
> Pilgrim/Models/Constants.swift:9-21@7c200bf

```swift
        public static let displayMedium: Font = .custom("CormorantGaramond-Light", size: 28)
        public static let heading: Font = .custom("CormorantGaramond-SemiBold", size: 17)
        ...
        public static let body: Font = .custom("CormorantGaramond-Regular", size: 17)
        public static let button: Font = .custom("Lato-Bold", size: 17)
        public static let caption: Font = .custom("Lato-Regular", size: 12)
        public static let annotation: Font = .custom("CormorantGaramond-Regular", size: 11)
        public static let micro: Font = .custom("Lato-Regular", size: 9)
```
> Pilgrim/Models/Constants.swift:62-71@7c200bf

Android already carries these as `PilgrimSpacing`, `PilgrimCornerRadius`, `pilgrimType.{displayMedium,heading,body,caption}`, and `pilgrimColors.{ink,fog,stone,moss,rust,dawn,parchmentSecondary}` (for example `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Color.kt:22-32@5ea4029b`).

### 1. How a finished walk is recognised as Honor

**The vocabulary.** An honor walk carries one `.honorMode` event, written at recording start. Reaching the end of the Way adds one `.honorArrival` event and one waypoint with a reserved icon.

```swift
/// The persistence vocabulary for honor walks, shaped like SeekPersistence:
/// a `.honorMode` event at recording start, and on reaching the end of the
/// Way a `.honorArrival` event plus a waypoint with the reserved icon.
enum HonorPersistence {

    /// Must never collide with WaypointMarkingSheet's presets, "mappin", or
    /// SeekPersistence.arrivalWaypointIcon.
    static let arrivalWaypointIcon = "signpost.right.fill"
    ...
    static func isArrivalWaypoint(_ waypoint: WaypointInterface) -> Bool {
        waypoint.icon == arrivalWaypointIcon
    }

    static func arrivalWaypointLabel(wayTitle: String) -> String {
        String(format: arrivalLabelFormat, wayTitle)
    }

    static let honorModeEventName = NSLocalizedString(
        "honor.event.honor_mode", value: "Honor",
        comment: "Name of the walk event marking a walk as an honor walk.")

    static let honorArrivalEventName = NSLocalizedString(
        "honor.event.arrival", value: "Way walked",
        comment: "Name of the walk event written when the end of a Way is reached.")

    private static let arrivalLabelFormat = NSLocalizedString(
        "honor.arrival.label", value: "Walked their way: %@",
        comment: "Waypoint label at the end of an honored Way; %@ is the Way's title.")
}
```
> Pilgrim/Models/Honor/HonorPersistence.swift:3-45@7c200bf

The three localization keys (`honor.event.honor_mode`, `honor.event.arrival`, `honor.arrival.label`) have no entry in `Base.lproj/Localizable.strings` at the pin, so the English `value:` is what ships.

The event is written only when the walk is in Honor mode and has a Way, at `startRecording`:

```swift
    func writeHonorMarkerEventIfNeeded() {
        guard mode == .honor, way != nil else { return }
        builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .honorMode, timestamp: Date()))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:42-45@7c200bf

```swift
    func startRecording() {
        proximityService.resetSession()
        builder.setStatus(.recording)
        writeSeekMarkerEventIfNeeded()
        writeHonorMarkerEventIfNeeded()
        startHonorEngineIfNeeded()
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:380-385@7c200bf

Arrival writes the event first, then the waypoint, then the card:

```swift
    /// The persistence commit happens before any ritual effect, as in Seek.
    private func recordHonorArrival(theirSeconds: Double, yourSeconds: Double) {
        guard let way else { return }
        builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .honorArrival, timestamp: Date()))
        addWaypoint(label: HonorPersistence.arrivalWaypointLabel(wayTitle: way.title),
                    icon: HonorPersistence.arrivalWaypointIcon)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:247-252@7c200bf

Raw and wire values. The Core Data raw values are `5` and `6`; the `.pilgrim` strings are `"honorMode"` and `"honorArrival"`:

```swift
        case lap, marker, segment, seekMode, seekArrival, honorMode, honorArrival, unknown
        ...
            case 5:
                self = .honorMode
            case 6:
                self = .honorArrival
```
> Pilgrim/Models/Data/DataModels/WalkEvent.swift:30,44-47@7c200bf

```swift
        case .honorMode: return "honorMode"
        case .honorArrival: return "honorArrival"
        ...
        case "honorMode": return .honorMode
        case "honorArrival": return .honorArrival
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:500-501,513-514@7c200bf

**The derivation.** Every after-the-walk surface asks the same question: does the walk carry a `.honorMode` event? No surface reads a mode column, the Way link, or the arrival event to decide "is this Honor". But each surface reads a different second signal, and those differ in ways an implementer must copy:

| Surface | "Is Honor" test | Second signal it needs |
|---|---|---|
| Summary section | `.honorMode` event and a walk uuid | none; shows even with no Way (section 2) |
| Summary ghost line | `.honorMode` event | the Way loaded through the walk's link |
| Journal footprints | `.honorMode` event (bulk fetch) | none |
| Journal staffs and cairn haptic | `.honorMode` event | at least one arrival **waypoint** (reserved icon) |
| Seal's Way line | `.honorMode` event | the Way loaded through the walk's link |
| Milestones | not asked | arrival **waypoints** only, counted on any walk |
| Prompt lexicon | `.honorMode` event | `.honorArrival` **event** for "reached" |

```swift
    static func computeHonorState(for walk: WalkInterface)
        -> (data: HonorSummaryData, way: HonorWayState?)? {
        guard walk.workoutEvents.contains(where: { $0.eventType == .honorMode }),
              let uuid = walk.uuid else { return nil }
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:742-745@7c200bf

```swift
    /// Seek walks are marked by their `.seekMode` event (origin R18), honor
    /// walks by `.honorMode`. One bulk fetch per mode — the event count
    /// equals the walk count — instead of faulting every walk's event list
    /// while building snapshots.
    private func fetchWalkIDs(withEvent eventType: WalkEvent.EventType) -> Set<UUID> {
        do {
            let events = try DataManager.dataStack.fetchAll(
                From<WalkEvent>().where(\._eventType == eventType)
            )
            return Set(events.compactMap { $0.workout?.uuid })
        } catch {
            print("[HomeViewModel] Failed to fetch \(eventType) events:", error.localizedDescription)
            return []
        }
    }
```
> Pilgrim/Scenes/Home/HomeViewModel.swift:153-167@7c200bf

```swift
        let isHonor = walk.workoutEvents.contains { $0.eventType == .honorMode }
        self.wayPoints = isHonor
            ? walk.uuid.flatMap(store.way(forWalk:))?.route.map { (lat: $0.lat, lon: $0.lon) }
            : nil
```
> Pilgrim/Models/Seal/SealInput.swift:51-54@7c200bf

```swift
    static func practice(
        events: [(type: WalkEvent.EventType, timestamp: Date)]
    ) -> (mode: PracticeMode, seekStory: SeekStoryContext?, honorStory: HonorStoryContext?) {
        if events.contains(where: { $0.type == .honorMode }) {
            let arrived = events.contains { $0.type == .honorArrival }
            return (.honor, nil, HonorStoryContext(wayTitle: nil, arrived: arrived))
        }
        guard events.contains(where: { $0.type == .seekMode }) else {
            return (.wander, nil, nil)
        }
```
> Pilgrim/Models/Prompt/ActivityContext.swift:43-52@7c200bf

**Honor versus Seek on one walk.** iOS never writes both, but a hand-edited `.pilgrim` can. The surfaces resolve it differently, and each must be copied as is:

- Prompts: Honor wins (the `.honorMode` check runs before `.seekMode`, quoted above).
- Journal footprints: Honor wins: `mode: snapshot.isHonor ? .honor : (snapshot.isSeek ? .seek : .wander)` (section 4).
- Journal scenery and haptic: a Seek cairn wins over the Honor staffs (section 5).
- Summary: both sections render, Seek first (section 2).
- Milestones: both families count; on a full tie Seek wins (section 7).

**Where the link lives.** The walk-to-Way link is a separate index in the Way store, keyed by the walk's uuid. It carries the arrival numbers. It is written once, after the walk saves, and before the seal reveal and the summary open:

```swift
struct WayLink: Codable, Equatable {
    let wayId: String
    /// The companion's timeline at arrival, recorded by the engine; nil when
    /// the walk ended before the end of the Way.
    let theirSeconds: Double?
    let yourSeconds: Double?
}
```
> Pilgrim/Models/Honor/WayStore.swift:3-9@7c200bf

```swift
                    if let way, let uuid = walk?.uuid {
                        if !way.source.isPackageOwned { try? WayStore.shared.save(way) }
                        let arrival = vm?.honorArrival.map { (theirSeconds: $0.theirSeconds, yourSeconds: $0.yourSeconds) }
                        try? WayStore.shared.link(walkUUID: uuid, to: way.id, arrival: arrival)
                        self.recordStageWalk(way: way, outcome: vm?.honorStageOutcome)
                    }
                    self.pendingSnapshot = snapshot
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:116-122@7c200bf

```swift
    func handleActiveWalkDismiss() {
        if let snapshot = pendingSnapshot {
            pendingSnapshot = nil
            sealRevealWalk = snapshot
            showSealReveal = true
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:147-151@7c200bf

So on iOS no after-the-walk surface ever runs before the link exists. The one exception is a failed link write, which `try?` swallows; the walk then reads as honoring a removed Way. Android's plan (U23) has a window where the summary opens before its Honor marker lands; iOS has no such window to copy.

Deleting a Way removes its links, so the walk loses its Way line, its title, and its delta together:

```swift
    func delete(id: String) {
        guard Self.isValidId(id) else { return }
        try? fileManager.removeItem(at: directory(for: id))
        var index = loadIndex()
        index = index.filter { $0.value.wayId != id }
        saveIndex(index)
    }
```
> Pilgrim/Models/Honor/WayStore.swift:123-129@7c200bf

Android counterparts: `WalkEventType.HONOR_MODE` / `HONOR_ARRIVAL` exist (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEventType.kt:33-46@5ea4029b`). `walkModeFromEvents` reads a HONOR_MODE walk as Wander today (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEventReplay.kt:82-91@5ea4029b`). `HonorPersistence.ARRIVAL_WAYPOINT_ICON` is already `"signpost.right.fill"` (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/HonorPersistence.kt:22@5ea4029b`).

### 2. The summary section: `HonorSummarySection`

**Where it sits.** Directly after the Seek section and before the elevation profile. The order is map, reliquary, intention card, Seek section, Honor section, elevation profile. Neither story section has a reveal fade; both render at once.

```swift
                VStack(spacing: Constants.UI.Padding.normal) {
                    mapSection
                    PhotoReliquarySection(
                        walk: walk,
                        candidates: $photoCandidates,
                        activePhotoID: $activePhotoID
                    )
                    intentionCard
                    if let seekSummary = cachedSeekSummary {
                        SeekSummarySection(data: seekSummary)
                    }
                    if let honorSummary = cachedHonorSummary {
                        HonorSummarySection(data: honorSummary)
                    }
                    elevationProfile
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:86-100@7c200bf

Android's matching slot is after the Seek section at `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt:473-482@5ea4029b` (spacing `PilgrimSpacing.normal`, no reveal alpha, like Seek).

**When the data is read.** Once, in the view's `init`, with three store reads (the link, the Way, the Way's replies; a stage adds the ledger). The body never reaches the store. Nothing re-reads it while the summary is open.

```swift
    /// The honored Way's story, or nil for every walk that was not an honor —
    /// computed once per walk identity like the seek story above.
    private let cachedHonorSummary: HonorSummaryData?
    /// Drops `private` so `WalkSummaryView+Map.swift` can lay the Way's ghost
    /// line under the walk's own ink.
    let cachedHonorWay: HonorWayState?
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:18-23@7c200bf

```swift
        let honor = Self.computeHonorState(for: walk)
        self.cachedHonorSummary = honor?.data
        self.cachedHonorWay = honor?.way
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:35-37@7c200bf

```swift
    /// Three Way-store reads, in `init` and only for a walk whose events say
    /// it honored a Way — a body must never reach the store.
    static func computeHonorState(for walk: WalkInterface)
        -> (data: HonorSummaryData, way: HonorWayState?)? {
        guard walk.workoutEvents.contains(where: { $0.eventType == .honorMode }),
              let uuid = walk.uuid else { return nil }
        let link = WayStore.shared.wayLink(forWalk: uuid)
        let way = link.flatMap { WayStore.shared.load(id: $0.wayId) }
        let replies = link.map { WayStore.shared.replies(for: $0.wayId) } ?? [:]
        let ledger = way?.stage.flatMap { PilgrimageLedgerStore().load(routeId: $0.routeId) }
        guard let data = HonorSummaryModel.summaryData(
            for: walk, way: way, link: link, replies: replies, ledger: ledger
        ) else { return nil }
        let wayState = way.map { HonorWayState(way: $0) }
        return (data, wayState)
    }
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:740-755@7c200bf

**The model.** Every field and its source:

```swift
struct HonorSummaryData: Equatable {
    let wayTitle: String
    /// Positive when the honoring walker arrived before the companion. Nil
    /// for a stage: there is no companion to arrive before.
    let arrivedBeforeTheirsSeconds: Double?
    /// Every voice the Way carries, not the subset this walk played — the
    /// arrival card's `voicesHeard` is the one that counts what was heard.
    let voicesAlongTheWay: Int
    let repliesMade: Int
    /// Carried explicitly, never inferred from `stageProgressLine`: a stage
    /// walk that earned no ledger entry (the walker never joined the line) is
    /// still a stage walk, and must not be told it walked in someone's steps.
    let isPilgrimageStage: Bool
    /// "14 of 24 km of the stage", from the ledger this walk just wrote.
    let stageProgressLine: String?
    /// The stage's closing line, present only when arrival actually fired.
    let closing: String?
    /// The walker's reply to it, relative to Documents.
    let replyRelativePath: String?
}
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:3-22@7c200bf

```swift
    static func summaryData(for walk: WalkInterface, way: Way?, link: WayLink?,
                            replies: [Int: String], ledger: PilgrimageLedger?) -> HonorSummaryData? {
        let types = walk.workoutEvents.map(\.eventType)
        guard types.contains(.honorMode) else { return nil }
        let stage = way?.stage
        // The dot walked the companion's timeline; the summary reads the
        // numbers the engine recorded at arrival, never a recomputation.
        var delta: Double?
        if stage == nil, let theirs = link?.theirSeconds, let yours = link?.yourSeconds { delta = theirs - yours }
        let arrived = types.contains(.honorArrival)
        return HonorSummaryData(
            wayTitle: way?.title ?? "a way that has been removed",
            arrivedBeforeTheirsSeconds: delta,
            voicesAlongTheWay: way?.voiceCount ?? 0,
            repliesMade: replies.count,
            isPilgrimageStage: stage != nil,
            stageProgressLine: stage.flatMap { stageProgressLine(stage: $0, ledger: ledger) },
            closing: arrived ? stage?.closing : nil,
            replyRelativePath: replies[HonorPersistence.stageReflectionOrigin])
    }
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:25-44@7c200bf

Key facts:

- The section appears for every honor walk with a uuid, whether or not the Way still exists.
- The title falls back to the literal `"a way that has been removed"` when the link or the Way cannot be loaded.
- The delta is `theirSeconds - yourSeconds`, taken from the link. It exists only when the link carries both numbers, which happens only when the engine fired arrival during the walk. It is never recomputed. The `.honorArrival` event is not consulted for the delta; it only gates the stage's closing line.
- `voicesAlongTheWay` is the Way's own voice count, not the voices heard:

```swift
    var voiceCount: Int { moments.filter(\.isVoice).count }
```
> Pilgrim/Models/Honor/Way.swift:210@7c200bf

- `repliesMade` is the size of the **Way's** reply map (`replies.json` in the Way's folder), keyed by origin voice index. It is not filtered to this walk. See the defect in "iOS defects found".

```swift
    func replies(for id: String) -> [Int: String] {
        guard Self.isValidId(id) else { return [:] }
        guard let data = try? Data(contentsOf: directory(for: id).appendingPathComponent("replies.json")),
              let map = try? decoder.decode([String: String].self, from: data) else { return [:] }
        return Dictionary(uniqueKeysWithValues: map.compactMap { key, value in Int(key).map { ($0, value) } })
    }

    func setReply(wayId: String, originN: Int, relativePath: String) throws {
        guard Self.isValidId(wayId) else { throw CocoaError(.fileWriteInvalidFileName) }
        var map = replies(for: wayId)
        map[originN] = relativePath
```
> Pilgrim/Models/Honor/WayStore.swift:166-176@7c200bf

Stage branch point: `stage != nil` (from `way?.stage`) suppresses the delta and turns on `stageProgressLine`, `closing`, and `replyRelativePath`. All three are nil on an own walk, so the stage reply button never shows on an own walk.

**The view.** A leading-aligned column on a rounded `parchmentSecondary` card:

```swift
    var body: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
            Text(Self.kicker(for: data)).font(Constants.Typography.caption).foregroundColor(.fog)
            Text(data.wayTitle).font(Constants.Typography.heading).foregroundColor(.ink)
            if let stageProgressLine = data.stageProgressLine {
                Text(stageProgressLine).font(Constants.Typography.caption).foregroundColor(.fog)
            }
            if let delta = data.arrivedBeforeTheirsSeconds {
                Text(deltaLine(delta)).font(Constants.Typography.caption).foregroundColor(.fog)
            }
            if let countsLine {
                Text(countsLine).font(Constants.Typography.caption).foregroundColor(.fog)
            }
            if let closing = data.closing {
                Text(closing)
                    .font(Constants.Typography.displayMedium)
                    .foregroundColor(.ink)
                    .padding(.top, Constants.UI.Padding.xs)
            }
            if let replyRelativePath = data.replyRelativePath, let url = replyURL(replyRelativePath) {
                Button { player.toggle(url: url) } label: {
                    Label(player.isPlaying ? "pause" : "your reply",
                          systemImage: player.isPlaying ? "pause.circle" : "play.circle")
                        .font(Constants.Typography.caption).foregroundColor(.stone)
                        .frame(minHeight: 44).contentShape(Rectangle())
                }
                .accessibilityLabel("Play your reply to this stage")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Constants.UI.Padding.normal)
        .background(RoundedRectangle(cornerRadius: Constants.UI.CornerRadius.normal).fill(Color.parchmentSecondary))
        .onDisappear { player.stop() }
    }
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:60-93@7c200bf

Row by row, for an own walk:

| # | Row | Font | Colour | Shown when |
|---|---|---|---|---|
| 1 | Kicker `"in their steps"` | `caption` (Lato 12) | `fog` | always |
| 2 | Way title or `"a way that has been removed"` | `heading` (Cormorant SemiBold 17) | `ink` | always |
| 3 | Delta line | `caption` | `fog` | link carries both arrival numbers |
| 4 | Counts line | `caption` | `fog` | voice count > 0 or reply count > 0 |

Spacing between rows is `Padding.small` (8). Card padding is `Padding.normal` (16) on all sides. Corner radius is `CornerRadius.normal` (12). The card spans the full width. The title has no line limit, so a long Way title wraps.

For an own-walk Way the title is the source walk's trimmed intention, or its medium-style date when there is none:

```swift
        let title: String
        if let comment = walk.comment?.trimmingCharacters(in: .whitespacesAndNewlines), !comment.isEmpty {
            title = comment
        } else {
            title = DateFormatter.localizedString(from: walk.startDate, dateStyle: .medium, timeStyle: .none)
        }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:100-105@7c200bf

So an own-walk honor summary typically reads "in their steps" over the old walk's intention or a date such as "Sep 12, 2026".

Stage rows (row order as in the code): the stage progress line sits between the title and the delta; the closing line (`displayMedium`, `ink`, `Padding.xs` top padding) and the "your reply" / "pause" button (`caption`, `stone`, `play.circle` / `pause.circle`, min height 44) come last.

**Every string.**

```swift
    static func kicker(for data: HonorSummaryData) -> String {
        data.isPilgrimageStage ? "the stage you walked" : "in their steps"
    }

    private var countsLine: String? {
        var parts: [String] = []
        if data.voicesAlongTheWay > 0 {
            parts.append("\(data.voicesAlongTheWay) \(data.voicesAlongTheWay == 1 ? "voice" : "voices") along the way")
        }
        if data.repliesMade > 0 {
            parts.append("\(data.repliesMade) \(data.repliesMade == 1 ? "reply" : "replies")")
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func deltaLine(_ delta: Double) -> String {
        let minutes = Int(abs(delta) / 60)
        if minutes == 0 { return "you arrived together" }
        let unit = minutes == 1 ? "minute" : "minutes"
        return delta > 0 ? "they arrived \(minutes) \(unit) after you" : "they arrived \(minutes) \(unit) before you"
    }
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:98-118@7c200bf

- Kicker: `"in their steps"` on an own or shared walk. It says "their" even when the Way is the walker's own earlier walk.
- Counts line: `"1 voice along the way"`, `"N voices along the way"`, `"1 reply"`, `"N replies"`, joined by `" · "` (space, middle dot U+00B7, space). Voices come first. With both zero the line is absent.
- Delta line: minutes are `Int(abs(delta) / 60)`, which truncates toward zero. Under 60 s either way reads `"you arrived together"`. A positive delta (their time longer than yours) reads `"they arrived N minute(s) after you"`; a negative one reads `"they arrived N minute(s) before you"`. `"minute"` only for exactly 1.
- All strings are plain Swift `String` literals passed to `Text(String)`, so none is localized. No localization key exists for any of them.

Examples: delta `+59.9` → `"you arrived together"`; `+60` → `"they arrived 1 minute after you"`; `-150` → `"they arrived 2 minutes before you"`.

**What it links to.** The section itself links nowhere and has no tap target on an own walk. The only button in it is the stage reply. The summary's one Honor door is the `"walk this again"` button under the share buttons, which is offered for any walk with at least two route points when the host wires `onWalkAgain`. On an honor walk it builds a new own-walk Way from **this** walk's route, not from the honored Way.

```swift
    @ViewBuilder
    private var shareCard: some View {
        WalkSharingButtons(walk: walk, pinnedPhotos: photoCandidates.filter(\.isPinned), onShare: markSharedAndReveal)
        // The route is already cached (AF17) — a body must never re-fault
        // `routeData` to find out whether there is a Way to walk.
        if cachedRouteCoordinates.count >= 2, let onWalkAgain {
            Button {
                onWalkAgain(walk)
                dismiss()
            } label: {
                Label("walk this again", systemImage: "signpost.right")
                    .font(Constants.Typography.button)
                    .foregroundColor(.stone)
            }
        }
    }
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:676-691@7c200bf

```swift
    func walkAgain(_ walk: WalkInterface) {
        pendingHonorWay = OwnWalkWayBuilder.make(from: walk)
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:308-310@7c200bf

The door itself (its hosts and the overview it opens) belongs to U21's spec; this spec records only that an honor walk's summary offers it too.

### 3. The summary map for an Honor walk

**What is drawn.** The summary map gets the Way's ghost line and nothing else from the Way. It passes no companion, no Way moment pins, and no service marks. The walk's own pins are unchanged; the arrival waypoint is one of them.

```swift
                PilgrimMapView(
                    isInteractive: revealPhase == .revealed,
                    showsUserLocation: false,
                    routeSegments: cachedSegments,
                    pinAnnotations: combinedAnnotations,
                    onAnnotationTap: handleAnnotationTap,
                    activePhotoID: activePhotoID,
                    cameraCenter: $cameraCenter,
                    cameraZoom: $cameraZoom,
                    cameraBounds: cameraBounds,
                    cameraDuration: cameraDuration,
                    walkingColor: walkTurning?.uiColor ?? .moss,
                    honorWay: cachedHonorWay
                )
                .frame(height: 320)
                .mask(
                    RadialGradient(
                        gradient: Gradient(colors: [.white, .white, .white.opacity(0)]),
                        center: .center,
                        startRadius: 80,
                        endRadius: 180
                    )
                )
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView+Map.swift:52-74@7c200bf

`companion` defaults to nil in the initializer (`companion: CLLocationCoordinate2D? = nil`, `Pilgrim/Views/PilgrimMapView.swift:88@7c200bf`). The ghost is masked by the same radial fade as the walk (full to 80 pt, gone at 180 pt). The whole map, ghost included, only renders when the walk has route data; otherwise the summary shows the `"No route data"` placeholder (`WalkSummaryView+Map.swift:76-85@7c200bf`), so an honor walk with no route shows no ghost even when its Way exists.

The live walk, for contrast, passes Way pins, mark pins, and the companion:

```swift
            pinAnnotations: waypointPins + viewModel.proximityPins + viewModel.honorMarkPins + viewModel.honorPins,
            ...
            honorWay: viewModel.honorWayState,
            companion: viewModel.companionCoordinate,
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Map.swift:26,39-40@7c200bf

**The ghost line's geometry.** One GeoJSON line feature per segment. The Way's route is cut at every span boundary so each piece carries the activity it was walked in (walking, talking, meditating). A Way with no spans is one walking segment.

```swift
    init(way: Way) {
        self.init(
            id: way.id,
            routeCoordinates: way.route.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) },
            segments: PilgrimMapView.HonorWayRendering.segments(route: way.route, spans: way.spans ?? []))
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:28-33@7c200bf

```swift
        static func segments(route: [WayPoint], spans: [WaySpan]) -> [HonorWayState.Segment] {
            let geometry = WayGeometry(route: route)
            guard route.count > 1, geometry.totalMeters > 0 else {
                return [HonorWayState.Segment(kind: "walking", coordinates: route.map {
                    CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
                })]
            }
            var pieces: [HonorWayState.Segment] = []
            var cursor = 0.0
            func add(_ kind: String, from start: Double, to end: Double) {
                guard end > start else { return }
                pieces.append(HonorWayState.Segment(kind: kind, coordinates: geometry.slice(fromFrac: start, toFrac: end)))
            }
            for span in spans.sorted(by: { $0.startFrac < $1.startFrac }) {
                let start = max(min(max(span.startFrac, 0), 1), cursor)
                let end = min(max(span.endFrac, 0), 1)
                guard end > start else { continue }
                add("walking", from: cursor, to: start)
                add(span.kind == .meditating ? "meditating" : "talking", from: start, to: end)
                cursor = end
            }
            add("walking", from: cursor, to: 1)
            return pieces
        }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:94-117@7c200bf

Edge cases: spans sort by `startFrac`; each span start clamps to [0, 1] and to the cursor, so a span never reaches back over one already drawn; a span with `end <= start` after clamping is skipped; gaps are walking; any span kind other than `.meditating` draws as talking.

**The ghost line's style.** Identical on the summary and the live walk; they share one renderer.

```swift
        static let sourceID = "honor-way-source"
        static let lineLayerID = "honor-way-line"
        static let companionSourceID = "honor-companion-source"
        static let companionLayerID = "honor-companion"
        static let lineWidth = 4.0
        static let companionRadius = 6.0
        static let companionUpdateInterval: TimeInterval = 2
        ...
        static func ghostStyle(dark: Bool) -> GhostStyle {
            dark
                ? GhostStyle(color: UIColor(hex: "#D9CFBF"), lineOpacity: 0.4, companionOpacity: 0.85)
                : GhostStyle(color: UIColor(hex: "#8A8175"), lineOpacity: 0.22, companionOpacity: 0.6)
        }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:61-84@7c200bf

```swift
            var layer = LineLayer(id: HonorWayRendering.lineLayerID, source: HonorWayRendering.sourceID)
            layer.lineWidth = .constant(HonorWayRendering.lineWidth)
            layer.lineCap = .constant(.round)
            layer.lineJoin = .constant(.round)
            let style = HonorWayRendering.ghostStyle(for: mapView)
            layer.lineOpacity = .constant(style.lineOpacity)
            // The walk's own palette (see PilgrimMapView+RouteSource), faded
            // by the opacity above so it reads as someone else's trace.
            layer.lineColor = .expression(
                Exp(.match) {
                    Exp(.get) { "activityType" }
                    "meditating"
                    UIColor.dawn
                    "talking"
                    UIColor.rust
                    UIColor.moss
                }
            )
            try mapView.mapboxMap.addLayer(layer, layerPosition: ghostLinePosition(on: mapView))
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:187-205@7c200bf

- Width 4, round cap, round join.
- Colour by segment: meditating `dawn`, talking `rust`, anything else `moss`. The ghost's walking colour is always `moss`; it never takes the turning-day colour the walk's own route may take (`walkingColor: walkTurning?.uiColor ?? .moss`).
- Opacity 0.22 on the light map style, 0.4 on the dark one, chosen from the map view's trait at install time. An appearance flip reloads the style, which reinstalls the line with the other opacity.
- The `GhostStyle.color` (`#8A8175` light, `#D9CFBF` dark) is used only by the companion dot. It never colours the line. The summary has no companion, so on the summary it is unused.

**Layering.** The ghost goes under the walk's casing, falling back to under the route line, falling back to the top of the stack. The walk's route layers are always recreated at the top, so the ghost stays under them.

```swift
    private static func ghostLinePosition(on mapView: MBMapView) -> LayerPosition? {
        if mapView.mapboxMap.layerExists(withId: "pilgrim-route-casing") {
            return .below("pilgrim-route-casing")
        }
        if mapView.mapboxMap.layerExists(withId: "pilgrim-route-layer") {
            return .below("pilgrim-route-layer")
        }
        return nil
    }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:219-227@7c200bf

Android's route layers are `pilgrim-route-casing` / `pilgrim-route-line` (plan, "Map" under Relevant Code). The walk's casing on iOS is white, width 10, opacity 0.3 (`Pilgrim/Views/PilgrimMapView+RouteSource.swift:121-127@7c200bf`), so where the walk overlaps its Way the ghost sits under a pale halo.

**Lifecycle.** Installed once per Way id; reinstalled on style load before the wabi-sabi terrain pass; self-heals when a lock/unlock strips runtime layers; a nil Way on a map that never installed one issues no style calls.

```swift
            coordinator.styleHasLoaded = true
            Self.reinstallHonorWay(on: mapView, coordinator: coordinator)
```
> Pilgrim/Views/PilgrimMapView.swift:171-172@7c200bf

```swift
        // Self-heal: a lock/unlock can strip runtime layers without a style event.
        if renderer.appliedWayID != nil, !mapView.mapboxMap.layerExists(withId: HonorWayRendering.lineLayerID) {
            renderer.appliedWayID = nil
        }
        guard let way else {
            ...
            guard renderer.appliedWayID != nil else { return }
            removeGhostLine(from: mapView)
            renderer.appliedWayID = nil
            return
        }
        guard renderer.appliedWayID != way.id else { return }
```
> Pilgrim/Views/PilgrimMapView+HonorWay.swift:162-176@7c200bf

A failed install only prints `"[PilgrimMapView] honor way install failed: \(error)"`; the summary shows no error.

**Camera.** The reveal fits the walk's own route only. The Way is not part of the fit, so a Way that runs beyond the walk (a walk that left early) is cut off at the map's edge and faded by the mask.

```swift
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            cameraDuration = 2.5
            cameraCenter = nil
            cameraBounds = boundsForRoute(coords)
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:431-434@7c200bf

where `coords` is `cachedRouteCoordinates`, the walk's samples (`WalkSummaryView.swift:419@7c200bf`).

**Pins after the walk.** The arrival waypoint has no annotation kind of its own. It draws through the generic waypoint branch as its reserved SF Symbol at 18 pt in `stone`, with no circle and no glow. This is the same mark the live walk draws for it.

```swift
    /// Honor arrivals ride the generic waypoint branch below: their reserved
    /// icon is already a signpost, so they draw as the stone symbol the
    /// engine chose without a kind of their own.
    static func computeAnnotations(for walk: WalkInterface) -> [PilgrimAnnotation] {
    ...
            } else {
                pins.append(PilgrimAnnotation(coordinate: coordinate, kind: .waypoint(label: waypoint.label, icon: waypoint.icon)))
            }
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:757-760,797-799@7c200bf

```swift
            case .waypoint(_, let icon):
                var point = PointAnnotation(coordinate: pin.coordinate)
                if icon == SeekPersistence.arrivalWaypointIcon {
                    ...
                } else if let image = cachedSymbolImage(icon, size: 18, color: .stone, cacheKey: icon) {
                    point.image = .init(image: image, name: icon)
                }
                point.iconSize = 1.0
                points.append(point)
```
> Pilgrim/Views/PilgrimMapView.swift:510-526@7c200bf

```swift
            case .waypoint, .whisper, .cairn, .seekArrival:
                // The clearing's core is its tree, drawn as a PointAnnotation
                // in `buildPoints`; the halo above still carries the hour's
                // light, so the two-part reading survives the glyph swap.
                continue
```
> Pilgrim/Views/PilgrimMapView.swift:455-459@7c200bf

The waypoint's label (`"Walked their way: <title>"`) is not drawn on the map. Android today maps an unknown waypoint icon key to the `"mappin"` glyph (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:1030@5ea4029b`), so an arrival currently draws as a map pin, not a signpost.

**Summary versus live walk, in one table.**

| Element | Live walk | Summary |
|---|---|---|
| Ghost line | yes, same style | yes, same style |
| Way moment pins, 22 pt glyphs: voice `waveform` (`stone` if heard, `fog` if not), photo `photo` `stone`, rest `cup.and.saucer` `stone`, sit `circle.circle` `dawn`, waypoint its own icon `stone` | yes (`Pilgrim/Views/PilgrimMapView+HonorWay.swift:299-334@7c200bf`) | no |
| Service marks (stage, 18 pt) | yes | no |
| Companion dot (radius 6, `GhostStyle.color`, opacity 0.6 / 0.85, white 1.5 stroke, above the route, moved at most every 2 s) | yes | no |
| Arrival signpost (18 pt, `stone`) | yes, once arrived | yes |
| Camera | follows the walker | fits the walk's own route |

### 4. The journal (ink scroll)

**The snapshot fields.** Two Honor fields: whether the walk honored a Way, and how many arrival waypoints it carries.

```swift
    /// Honor walks carry two staffs rather than a single walker's pair.
    let isHonor: Bool
    /// Ways walked to their end on this walk — an arrival raises the staffs
    /// on the ink scroll.
    let honorArrivals: Int
```
> Pilgrim/Scenes/Home/HomeViewModel.swift:25-29@7c200bf

**How they are built.** One bulk event fetch per mode, one waypoint pass for both arrival counts, then a chronological loop (oldest first) that also decides the walk's gate:

```swift
    private func buildSnapshots() {
        let seekWalkIDs = fetchWalkIDs(withEvent: .seekMode)
        let honorWalkIDs = fetchWalkIDs(withEvent: .honorMode)
        let (arrivalCounts, honorArrivalCounts) = GoshuinMilestones.arrivalAndHonorCounts(for: walks)
        let reversed = walks.reversed()
        var cumulative: Double = 0
        var arrivalsBefore = 0
        var honorArrivalsBefore = 0
        var snapshots: [WalkSnapshot] = []

        for (chronologicalIndex, walk) in reversed.enumerated() {
            ...
            let walkNumber = chronologicalIndex + 1
            let foundPlaces = walk.uuid.flatMap { arrivalCounts[$0] } ?? 0
            let honorArrivals = walk.uuid.flatMap { honorArrivalCounts[$0] } ?? 0
            // Mystery outranks routine: a tenth walk that also found its
            // first unknown stands at a seeking gate. An honor threshold
            // stands at the same gate — both are the walk meeting something
            // it did not choose.
            let crossedSeeking = !GoshuinMilestones.seekingMilestones(
                arrivalsInWalk: foundPlaces, arrivalsBefore: arrivalsBefore
            ).isEmpty
            let crossedHonor = !GoshuinMilestones.honorMilestones(
                arrivalsInWalk: honorArrivals, arrivalsBefore: honorArrivalsBefore
            ).isEmpty
            let threshold: WalkThreshold?
            if crossedSeeking || crossedHonor {
                threshold = .seeking
            } else if walkNumber == 1 || walkNumber % 10 == 0 {
                threshold = .practice
            } else {
                threshold = nil
            }
            arrivalsBefore += foundPlaces
            honorArrivalsBefore += honorArrivals
            ...
                isHonor: walk.uuid.map(honorWalkIDs.contains) ?? false,
                honorArrivals: honorArrivals
```
> Pilgrim/Scenes/Home/HomeViewModel.swift:90-146@7c200bf

Consequences:

- A walk that crosses an Honor threshold (its first arrival ever, or the lifetime count crossing 10, 25, 50, or 100) stands at a **seeking** gate, the same gate a Seek threshold earns. This outranks the practice gate (first walk, every tenth walk).
- `honorArrivals` counts reserved-icon waypoints on the walk, whether or not the walk has a `.honorMode` event. `isHonor` comes only from the event.
- "Before" here is the fetch order (`orderBy(.descending(\._startDate))`, reversed), not the uuid tie-break the goshuin uses (section 7).

Android's snapshot has `isSeek`, `foundPlaces`, and `threshold` today, but no Honor fields (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/WalkSnapshot.kt:41-62@5ea4029b`).

**The glyph.** The expand card (a tapped dot's quick view) opens with the mode miniature. Honor wins over Seek:

```swift
                        if isExpandedArchived {
                            FootprintShape()
                                .stroke(Color.fog, lineWidth: 1)
                                .frame(width: 12, height: 18)
                        } else {
                            WalkModeFootprints(
                                mode: snapshot.isHonor ? .honor : (snapshot.isSeek ? .seek : .wander),
                                color: seasonColor.opacity(0.3)
                            )
                        }
```
> Pilgrim/Scenes/Home/InkScrollView.swift:349-358@7c200bf

An archived ("Released") walk shows a plain stroked print instead, whatever its mode.

```swift
/// Static miniature of the path screen's mode language, for compact rows
/// (the ink-scroll quick view). Wander: the grounded pair. Honor: one print
/// beside the staff of the walker whose Way is being followed. Seek: one
/// print beside a trail of dots dissolving upward into the unknown. No
/// animation — these are glances, not scenes; the drifting versions live on
/// the path screen only.
struct WalkModeFootprints: View {
    let mode: WalkModeGlyph
    let color: Color

    var body: some View {
        HStack(spacing: 2) {
            FootprintShape()
                .fill(color)
                .frame(width: 10, height: 16)
                .scaleEffect(x: -1)
                .rotationEffect(.degrees(-12))
            switch mode {
            case .seek:
                dissolvingDots
                    .frame(width: 10, height: 18)
                    .rotationEffect(.degrees(12))
            case .honor:
                StaffGlyph()
                    .stroke(color, lineWidth: 1)
                    .frame(width: 8, height: 14)
            case .wander:
                FootprintShape()
                    .fill(color.opacity(0.75))
                    .frame(width: 10, height: 16)
                    .rotationEffect(.degrees(12))
            }
        }
        .accessibilityHidden(true)
    }
```
> Pilgrim/Views/WalkModeFootprints.swift:8-42@7c200bf

Honor: the same left print as every mode (filled, 10×16, mirrored, rotated −12°), then 2 pt gap, then a staff stroked at width 1 in a 8×14 frame, not rotated. The colour is the dot's seasonal colour at opacity 0.3 for both parts (no 0.75 second step as wander has).

The staff shape (defined beside the path screen; the journal reuses it):

```swift
struct StaffGlyph: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + rect.width * 0.65, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + rect.width * 0.35, y: rect.maxY))
        let barY = rect.minY + rect.height * 0.18
        path.move(to: CGPoint(x: rect.minX, y: barY + 2))
        path.addLine(to: CGPoint(x: rect.maxX, y: barY - 2))
        return path
    }
}
```
> Pilgrim/Scenes/Home/WalkStartView.swift:417-427@7c200bf

Two strokes: a shaft from (0.65w, top) to (0.35w, bottom), and a cross-bar from (0, 0.18h + 2) to (w, 0.18h − 2). The ±2 is absolute points, not a fraction. No cap style is set, so caps are butt.

Android's miniature is Boolean today (`isSeek`), with no Honor branch and no staff glyph anywhere in the app (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/WalkModeFootprints.kt:69-80@5ea4029b`).

**Scenery.** Section 5.

**The scroll haptic.** An Honor walk with at least one arrival waypoint gets the cairn's soft haptic when scrolled past, unless it stands at a gate:

```swift
        hapticState.dotKinds = snapshots.map { snap in
            if snap.threshold != nil { return .gate }
            if snap.isSeek && snap.foundPlaces > 0 { return .cairn }
            if snap.isHonor && snap.honorArrivals > 0 { return .cairn }
            return .plain
        }
```
> Pilgrim/Scenes/Home/InkScrollView.swift:692-697@7c200bf

Android's lockstep function stops at the Seek line (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt:802-805@5ea4029b`).

**What the journal does not change.** The dot itself, its colour, size, and label carry no Honor mark. No Way title, companion delta, or voice count appears in the journal. The expand card's text rows (date, distance, duration, pace, activity bar and pills, "View details") are the same as a wander walk's.

### 5. Scenery for an Honor walk: the staffs

**The type.** `staffs` is the tenth scenery type: tint `stone`, parallax weight 9 (the cairn's and lantern's). It is not in the lottery's weight table, so only the deterministic branch can place it.

```swift
enum SceneryType: CaseIterable {
    case tree, lantern, butterfly, mountain, grass, torii, moon, cairn, drift, staffs
    ...
        case .staffs: AnyShape(StaffsShape())
    ...
        case .cairn: "stone"
        case .drift: "fog"
        case .staffs: "stone"
    ...
        case .cairn: 9
        case .staffs: 9
```
> Pilgrim/Models/SceneryGenerator.swift:7-8,21,34-36,50-51@7c200bf

```swift
    private static let weights: [(SceneryType, Double)] = [
        (.tree, 0.27),
        (.lantern, 0.18),
        (.grass, 0.22),
        (.butterfly, 0.14),
        (.mountain, 0.11),
        (.drift, 0.05),
        (.moon, 0.03),
    ]
```
> Pilgrim/Models/SceneryGenerator.swift:86-94@7c200bf

**When it is chosen.** After the gate and after the Seek cairn, before the 35% lottery. It needs both the `.honorMode` event and at least one arrival waypoint. An honor walk left before its end rolls the ordinary lottery.

```swift
    static func scenery(for snapshot: WalkSnapshot) -> SceneryPlacement? {
        let seed = deterministicSeed(for: snapshot)
        let roll3 = seededRandom(seed: seed, salt: 3)
        let side: ScenerySide = roll3 < 0.5 ? .left : .right
        let roll4 = seededRandom(seed: seed, salt: 4)
        let offset = CGFloat(roll4 * 15 - 7.5)

        // Meaning outranks the lottery: threshold walks stand at a gate, a
        // seek that found places raises a cairn, and a Way walked to its end
        // raises two staffs.
        if let threshold = snapshot.threshold {
            return SceneryPlacement(type: .torii, side: side, offset: offset, gateKind: threshold)
        }
        if snapshot.isSeek && snapshot.foundPlaces > 0 {
            return SceneryPlacement(
                type: .cairn,
                side: side,
                offset: offset,
                stones: min(2 + snapshot.foundPlaces, 5)
            )
        }
        if snapshot.isHonor && snapshot.honorArrivals > 0 {
            return SceneryPlacement(type: .staffs, side: side, offset: offset)
        }
```
> Pilgrim/Models/SceneryGenerator.swift:96-119@7c200bf

Precedence, highest first:

1. A gate (`threshold != nil`) → torii. Because an Honor threshold sets `.seeking` (section 4), **the walk of the first Honor arrival ever, and the walks crossing 10, 25, 50, and 100, show a weathered-stone torii, not staffs.** So do walk #1 and every tenth walk (practice gate, rust torii).
2. A Seek with found places → cairn.
3. An Honor with at least one arrival → staffs.
4. Otherwise the lottery (35% chance).

Side and offset come from salts 3 and 4 of the same seed every placement uses; the seed hashes the snapshot's id, start time, distance, and duration only, so the Honor fields never move an item:

```swift
    private static func deterministicSeed(for snapshot: WalkSnapshot) -> UInt64 {
        var h: UInt64 = 14695981039346656037
        func mix(_ v: UInt64) { h = (h ^ v) &* 1099511628211 }
        withUnsafeBytes(of: snapshot.id) { $0.forEach { mix(UInt64($0)) } }
        mix(UInt64(bitPattern: Int64(snapshot.startDate.timeIntervalSince1970)))
        mix(UInt64(bitPattern: Int64(snapshot.distance * 100)))
        mix(UInt64(bitPattern: Int64(snapshot.duration)))
        return h
    }
```
> Pilgrim/Models/SceneryGenerator.swift:149-157@7c200bf

The placement's tint name is the type's (`stone`); only a practice torii overrides it:

```swift
    var tintColorName: String {
        if type == .torii, gateKind == .practice { return "rust" }
        return type.tintColorName
    }
```
> Pilgrim/Models/SceneryGenerator.swift:72-75@7c200bf

**Placement in the scroll.** The same host code as every item: seasonal `stone` at full intensity, weather-adjusted; size 32 to 56 pt from the id hash; 40 pt plus half the size out from the dot on the chosen side, plus the offset, 4 pt up; age fade with the dot (only a seeking gate refuses it); parallax by weight; hidden from accessibility.

```swift
        let baseTint = Color(uiColor: SeasonalColorEngine.seasonalColor(
            named: placement.tintColorName,
            intensity: .full,
            on: snapshot.startDate
        ))
        let tintColor = Self.weatherAdjustedColor(baseTint, condition: snapshot.weatherCondition)

        let baseSize: CGFloat = 32
        var h: UInt64 = 14695981039346656037
        withUnsafeBytes(of: snapshot.id) { $0.forEach { h = (h ^ UInt64($0)) &* 1099511628211 } }
        let sizeVariation = CGFloat(h % 20) / 20.0
        let size = baseSize + sizeVariation * 24

        let xOffset: CGFloat = placement.side == .left ? -40 - size / 2 : 40 + size / 2
        ...
        let parallax = placement.type.parallaxWeight
        // Seeking gates refuse the age fade — old stone grows older,
        // not fainter. Everything else dims with its walk.
        let sceneryOpacity = placement.gateKind == .seeking ? 1.0 : opacity
        return AnyView(
            SceneryItemView(
                type: placement.type,
                tintColor: tintColor,
                size: size,
                walkDate: snapshot.startDate,
                stones: placement.stones,
                gateKind: placement.gateKind
            )
            .opacity(sceneryOpacity)
            .offset(x: xOffset + placement.offset, y: -4)
            ...
            .accessibilityHidden(true)
```
> Pilgrim/Scenes/Home/InkScrollView+Scenery.swift:12-58@7c200bf

**The drawing.** Two staffs leaning together with a small ring where they meet. The shape returns an already-stroked outline, so callers `.fill` it.

```swift
/// Two staffs leaning together: dōgyō ninin, the ink-scroll mark of a walk
/// that honored a Way to its end.
struct StaffsShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let w = rect.width, h = rect.height
        path.move(to: CGPoint(x: rect.minX + w * 0.20, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + w * 0.55, y: rect.minY + h * 0.08))
        path.move(to: CGPoint(x: rect.minX + w * 0.80, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + w * 0.45, y: rect.minY + h * 0.08))
        path.addEllipse(in: CGRect(x: rect.minX + w * 0.42, y: rect.minY, width: w * 0.16, height: h * 0.10))
        return path.strokedPath(StrokeStyle(lineWidth: max(1, w * 0.08), lineCap: .round))
    }
}
```
> Pilgrim/Views/Scenery/StaffsShape.swift:3-15@7c200bf

Geometry, as fractions of the frame:

- Left staff: (0.20w, h) to (0.55w, 0.08h).
- Right staff: (0.80w, h) to (0.45w, 0.08h). The two cross just below their tops.
- Knot: an ellipse at x 0.42w, y 0, width 0.16w, height 0.10h. It is stroked like the staffs, so it draws as a ring, not a filled dot.
- Stroke: width `max(1, 0.08w)`, round caps, default (miter) join.

```swift
    // MARK: - Staffs — two leaning together, raised by a walk that honored a
    // Way to its end. Static, like the cairn: dōgyō ninin stands, it does
    // not sway.

    private var staffsView: some View {
        ZStack {
            StaffsShape()
                .fill(tintColor.opacity(0.1))
                .frame(width: size * 1.06, height: size * 1.06)
                .offset(x: 1.5, y: 1.5)
                .blur(radius: 1.2)

            StaffsShape()
                .fill(tintColor.opacity(0.35))
                .frame(width: size, height: size)
        }
    }
```
> Pilgrim/Views/Scenery/SceneryItemView.swift:178-194@7c200bf

Two layers, back to front:

1. Shadow: the shape at 1.06× size, tint at opacity 0.1, offset (1.5, 1.5) pt, blur radius 1.2. Its stroke width follows from its own larger frame (`0.08 × 1.06 size`).
2. Body: the shape at size, tint at opacity 0.35.

Static: no `TimelineView`, no animation, so Reduce Motion changes nothing. Unlike the cairn, the staffs have no winter snow cap and no dawn halo:

```swift
            if isWinter {
                Ellipse()
                    .fill(.white.opacity(0.35))
                    .frame(width: size * 0.30, height: size * 0.10)
                    .offset(x: size * 0.02, y: -size * 0.46)
                    .blur(radius: 0.5)
            }

            // A trace of the dawn halo the clearing wore on the map.
            Circle()
                .fill(Color(red: 0.77, green: 0.58, blue: 0.42).opacity(0.10))
```
> Pilgrim/Views/Scenery/SceneryItemView.swift:162-172@7c200bf (cairn only)

Android counterparts: the generator's precedence block stops at the cairn (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryGenerator.kt:43-55@5ea4029b`); the type enum has nine members (`SceneryGenerator.kt:139-140@5ea4029b`); the cairn's two-layer renderer is the nearest model (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/CairnScenery.kt@5ea4029b`, dispatched from `SceneryItem.kt:102@5ea4029b`).

### 6. The seal's ghost-route watermark

Android never ported this layer (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealRenderer.kt:40-42@5ea4029b` records the Stage 4-A deferral). Everything below is needed; nothing is optional.

**Which walks get which line.**

- **Every** walk with two or more route samples gets its own line. This is not an Honor feature: wander and seek seals carry it too.
- A walk also gets the Way's line when all of these hold: it has a `.honorMode` event, a uuid, a link in the Way store, a loadable Way, and a Way route of two or more points.
- A walk with fewer than two route samples gets neither line, even when its Way exists.

```swift
    /// The Way this walk honored, for the second ghost line under the seal.
    /// Nil for every walk that was not an honor.
    let wayPoints: [(lat: Double, lon: Double)]?

    init(walk: WalkInterface, store: WayStore = .shared) {
        ...
        self.routePoints = walk.routeData.map { (lat: $0.latitude, lon: $0.longitude) }
        ...
        // Two disk reads — the walk index, then the Way itself — and only
        // for a walk whose events say it honored a Way; every other seal is
        // built without touching the store.
        let isHonor = walk.workoutEvents.contains { $0.eventType == .honorMode }
        self.wayPoints = isHonor
            ? walk.uuid.flatMap(store.way(forWalk:))?.route.map { (lat: $0.lat, lon: $0.lon) }
            : nil
    }
```
> Pilgrim/Models/Seal/SealInput.swift:24-55@7c200bf

```swift
    func way(forWalk uuid: UUID) -> Way? { wayId(forWalk: uuid).flatMap(load(id:)) }
```
> Pilgrim/Models/Honor/WayStore.swift:192@7c200bf

```swift
            routePoints: input.routePoints.count > 1 ? input.routePoints : nil,
            wayPoints: (input.wayPoints?.count ?? 0) > 1 ? input.wayPoints : nil,
```
> Pilgrim/Models/Seal/SealGenerator.swift:73-74@7c200bf

The walk's points are every stored route sample, unsampled. The Way's points are the Way's `route` as stored in `way.json` (for an own walk, whatever the builder wrote).

**Layer order.** The watermark is the second layer drawn, inside the seal's hash rotation, under the elevation ring, the rings, the spokes, the arcs, and the dots. Only the weather texture lies beneath it. The curved text and the centre text are drawn after, unrotated.

```swift
            ctx.saveGState()
            ctx.translateBy(x: cx, y: cy)
            ctx.rotate(by: geo.rotation * .pi / 180)
            ctx.translateBy(x: -cx, y: -cy)

            drawWeatherTexture(ctx: ctx, input: input, size: size)
            drawGhostRoute(ctx: ctx, input: input, size: size)
            drawElevationRing(ctx: ctx, input: input, size: size)
            drawRings(ctx: ctx, input: input)
            drawRadialLines(ctx: ctx, input: input)
            drawArcSegments(ctx: ctx, input: input)
            drawDots(ctx: ctx, input: input)

            ctx.restoreGState()

            drawCurvedText(ctx: ctx, input: input, size: size)
            drawCenterText(ctx: ctx, input: input, size: size)
```
> Pilgrim/Models/Seal/SealRenderer.swift:31-47@7c200bf

So the route is **rotated with the seal**: north is not up. The rotation is `(bytes[0] / 255) × 360` degrees:

```swift
        let cx = size / 2
        let cy = size / 2
        let outerR = size * 0.44

        self.center = CGPoint(x: cx, y: cy)
        self.outerRadius = outerR
        self.rotation = (CGFloat(bytes[0]) / 255.0) * 360.0
```
> Pilgrim/Models/Seal/SealGeometry.swift:41-47@7c200bf

Android already rotates its four layers by the same hash-derived angle around the centre, with `outerR = canvasSize * 0.44f` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealRenderer.kt:93-101@5ea4029b`). Android has no weather layer, so the watermark becomes the first draw inside that `rotate` block, before `drawRings`.

**Geometry and normalisation.** The full function:

```swift
    private static func drawGhostRoute(ctx: CGContext, input: Input, size: CGFloat) {
        guard let points = input.routePoints, points.count >= 2 else { return }
        let way = (input.wayPoints?.count ?? 0) >= 2 ? input.wayPoints : nil

        let geo = input.geometry
        let cx = geo.center.x
        let cy = geo.center.y
        let fitRadius = geo.outerRadius * 0.7

        // One fit for both lines: the drift between the Way and the walk that
        // honored it is the whole story, and separate fits would erase it.
        let fitted = points + (way ?? [])
        let lats = fitted.map { $0.lat }
        let lons = fitted.map { $0.lon }
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max() else { return }

        let latSpan = maxLat - minLat
        let lonSpan = maxLon - minLon
        let span = max(latSpan, lonSpan, 0.0001)

        let midLat = (minLat + maxLat) / 2
        let midLon = (minLon + maxLon) / 2

        let scale = fitRadius * 2 / CGFloat(span)

        func stroke(_ line: [(lat: Double, lon: Double)], alpha: CGFloat) {
            ctx.setAlpha(alpha)
            for (i, point) in line.enumerated() {
                let x = cx + CGFloat(point.lon - midLon) * scale
                let y = cy - CGFloat(point.lat - midLat) * scale
                if i == 0 {
                    ctx.move(to: CGPoint(x: x, y: y))
                } else {
                    ctx.addLine(to: CGPoint(x: x, y: y))
                }
            }
            ctx.strokePath()
        }

        ctx.saveGState()
        ctx.setStrokeColor(input.color.cgColor)
        ctx.setLineWidth(1.0)
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)

        if let way {
            stroke(way, alpha: 0.03)
        }
        stroke(points, alpha: 0.055)
        ctx.restoreGState()
    }
```
> Pilgrim/Models/Seal/SealRenderer.swift:113-164@7c200bf

Step by step:

1. **One shared fit.** The bounding box covers the walk's points plus the Way's points together. Separate fits are explicitly rejected, so the offset between the two lines is preserved.
2. **Square, degree-space fit.** `span = max(latSpan, lonSpan, 0.0001)` in raw degrees. There is no cos(latitude) correction: a degree of longitude is drawn the same length as a degree of latitude, so routes stretch east–west away from the equator. The `0.0001°` floor stops a divide by zero on a walk that never moved.
3. **Centre.** The box's midpoint (`midLat`, `midLon`) maps to the seal's centre (`cx`, `cy` = `size / 2`).
4. **Scale.** `scale = 2 × fitRadius / span`, with `fitRadius = 0.7 × outerRadius = 0.7 × 0.44 × size = 0.308 × size`. The longer side of the box spans `0.616 × size`.
5. **Axes.** `x = cx + (lon − midLon) × scale`; `y = cy − (lat − midLat) × scale`. North is up before the seal's rotation is applied.
6. **Paths.** Each line is one path: `move` to the first point, `addLine` through the rest, one `strokePath`. Stroking each line as a single path means its own self-crossings do not darken. The Way and the walk are two separate strokes, so where they overlap their alphas do compound.

**Stroke, colour, opacity.**

| Property | Value |
|---|---|
| Colour | the seal's ink, `input.color` (the same colour as the rings and text) |
| Width | `1.0` pt at the render size (see below) |
| Cap / join | round / round |
| Way line alpha | `0.03`, drawn first (beneath) |
| Walk line alpha | `0.055`, drawn second (on top) |

`ctx.setAlpha` sets the context's global alpha, which multiplies the colour's own alpha. The seal palette colours are opaque hex values (for example `static let rust = SealColor(light: UIColor(hex: "#A0634B"), dark: UIColor(hex: "#C47E63"), cssVar: "--seal-rust")`, `Pilgrim/Models/Seal/SealColorPalette.swift:13@7c200bf`), so the effective opacities are exactly 3% and 5.5%. The colour comes from the same `SealColorPalette.uiColor(for: input)` call as the rest of the seal (`SealGenerator.swift:43@7c200bf`); the watermark has no colour of its own.

**Width at scale.** The width is an absolute `1.0` in the render's own units, not a fraction. Seals are rendered as bitmaps and cached. The seal reveal and the share button render at 512; the goshuin thumbnail is that 512 image downsampled to 128:

```swift
            let image = SealGenerator.generate(from: input, size: 512)
```
> Pilgrim/Scenes/SealReveal/SealRevealView.swift:93@7c200bf

```swift
            let thumb = seal.preparingThumbnail(of: CGSize(width: 128, height: 128)) ?? seal
```
> Pilgrim/Models/Seal/SealCache.swift:86@7c200bf

So in practice the stroke is `1/512` of the seal's width (`0.001953 × size`). Android's renderer is size-agnostic and expresses strokes as canvas fractions (`strokePx = canvasSize * ring.strokeWidthFrac`, `SealRenderer.kt:128@5ea4029b`), so the watermark's width is `canvasSize / 512` there. The etegami asks for 160 (`Pilgrim/Models/Etegami/EtegamiGenerator.swift:21@7c200bf`) but gets the cached 512 image when one exists (see edge cases).

**Caching.** The seal image is cached by walk uuid only, with no expiry. The only evictions are the cache's own caps (50 MB on disk, 256 entries in memory); no code clears it:

```swift
        let diskConfig = DiskConfig(
            name: "SealCache",
            expiry: .never,
            maxSize: 50_000_000
        )
```
> Pilgrim/Models/Seal/SealCache.swift:32-36@7c200bf

```swift
        if let cached = SealCache.shared.seal(for: uuid) {
            return cached
        }
```
> Pilgrim/Models/Seal/SealGenerator.swift:30-32@7c200bf

No call site clears it (`git grep "SealCache.shared.clear"` finds nothing at the pin). The first render after a walk is the seal reveal, which runs after the link is written (section 1), so a fresh honor seal includes the Way's line. From then on the image is frozen until a size-cap eviction forces a re-render: deleting the Way does not remove its line from that seal. A seal first rendered where the link is absent (an imported walk) does not gain the line if a link appears later.

Android draws seals live from a `SealSpec` in Compose (`SealRenderer.kt:47-115@5ea4029b`), plus a bitmap path for the etegami that reuses the same geometry (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/etegami/EtegamiSealBitmapRenderer.kt:85-91@5ea4029b`). Both paths need the watermark. Android has no seal cache, so without extra work its Way line would disappear when the Way is deleted, where iOS's stays.

**Every place a seal appears.** All go through `SealGenerator` and therefore carry the watermark: the seal reveal, the goshuin book's thumbnails, the goshuin FAB, the goshuin share image, the walk share button's seal image, and the etegami's inset seal.

```swift
7c200bf:Pilgrim/Models/Etegami/EtegamiGenerator.swift:21:        let sealImage = SealGenerator.generate(from: input, size: 160)
7c200bf:Pilgrim/Scenes/Goshuin/GoshuinFAB.swift:43:            SealGenerator.thumbnail(from: input)
7c200bf:Pilgrim/Scenes/Goshuin/GoshuinPageView.swift:134:            SealGenerator.thumbnail(from: input)
7c200bf:Pilgrim/Scenes/Goshuin/GoshuinShareRenderer.swift:379:        return SealGenerator.generate(from: input, size: sealSize)
7c200bf:Pilgrim/Scenes/SealReveal/SealRevealView.swift:93:            let image = SealGenerator.generate(from: input, size: 512)
7c200bf:Pilgrim/Views/WalkSharingButtons.swift:75:                    let image = SealGenerator.generate(from: input, size: 512)
```
> `git grep -n "SealGenerator.generate\|SealGenerator.thumbnail" 7c200bf -- Pilgrim` (call sites at the pin)

**Edge cases.**

- A walk whose samples are all one point: span floors at `0.0001°`, every point maps to the centre, and the path is a zero-length line. With round caps it can draw as a dot of width 1 at alpha 0.055 (effectively invisible).
- A Way far from the walk (a shared Way walked elsewhere, or a walk that never joined the line): the shared fit shrinks both lines to fit the combined box, so each can become small. iOS accepts this ("the drift ... is the whole story").
- Antimeridian crossings are not handled; longitudes are subtracted raw.
- The cache key is the uuid, not the size. Whichever size renders first is what every later caller gets. The reveal (512) almost always runs first; if an etegami (160) ran first, the 1-unit stroke would be `1/160` of that image, about three times heavier relative to the seal, and that small image would then serve the goshuin and shares. Pre-existing, not Honor-specific.
- The watermark does not affect the seal's hash, colour, or geometry. The hash reads the walk's route points but not `wayPoints`:

```swift
    static func computeHashFromInput(_ input: SealInput) -> String {
        ...
            routePoints: input.routePoints,
```
> Pilgrim/Models/Seal/SealHashComputer.swift:57,63@7c200bf

### 7. Milestones touched by Honor

**The two milestones and their labels.**

```swift
        /// Honor thresholds: the walk that carried the first Way walked to
        /// its end, and the walks whose arrivals crossed a lifetime count.
        case firstHonor
        case honorsWalked(Int)
    }
    ...
    /// Lifetime Way-arrival counts that earn a seal.
    static let honorThresholds = [10, 25, 50, 100]
```
> Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift:15-25@7c200bf

```swift
        case .firstHonor: return "First Honor"
        case .honorsWalked(let n): return "\(n) Ways Walked"
```
> Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift:354-355@7c200bf

Labels: `"First Honor"`, `"10 Ways Walked"`, `"25 Ways Walked"`, `"50 Ways Walked"`, `"100 Ways Walked"`. Plain string literals, not localized.

**What is counted.** Arrival waypoints (the reserved icon), not `.honorArrival` events, and not `.honorMode`. A walk with two arrival waypoints counts two.

```swift
    static func arrivalAndHonorCounts(for walks: [WalkInterface]) -> (arrivals: [UUID: Int], honorArrivals: [UUID: Int]) {
        var arrivals: [UUID: Int] = [:]
        var honorArrivals: [UUID: Int] = [:]
        for walk in walks {
            guard let uuid = walk.uuid else { continue }
            var arrivalCount = 0
            var honorCount = 0
            for waypoint in walk.waypoints {
                if SeekPersistence.isArrivalWaypoint(waypoint) { arrivalCount += 1 }
                if HonorPersistence.isArrivalWaypoint(waypoint) { honorCount += 1 }
            }
            if arrivalCount > 0 { arrivals[uuid] = arrivalCount }
            if honorCount > 0 { honorArrivals[uuid] = honorCount }
        }
        return (arrivals, honorArrivals)
    }
```
> Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift:100-115@7c200bf

```swift
        self.honorArrivalCount = walk.waypoints.filter(HonorPersistence.isArrivalWaypoint).count
```
> Pilgrim/Models/Seal/SealInput.swift:47@7c200bf

**The rule.** Awarded to the walk that crosses. A walk with no arrival earns nothing. `firstHonor` needs zero arrivals strictly before; each threshold needs `before < t <= before + inWalk`, so one walk can cross several at once.

```swift
    static func honorMilestones(arrivalsInWalk: Int, arrivalsBefore: Int) -> Set<Milestone> {
        guard arrivalsInWalk > 0 else { return [] }
        var milestones: Set<Milestone> = []
        if arrivalsBefore == 0 {
            milestones.insert(.firstHonor)
        }
        let total = arrivalsBefore + arrivalsInWalk
        for threshold in honorThresholds where arrivalsBefore < threshold && total >= threshold {
            milestones.insert(.honorsWalked(threshold))
        }
        return milestones
    }
```
> Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift:148-159@7c200bf

"Before" is strict, with a uuid string tie-break for equal start dates:

```swift
    static func isOrderedBefore(
        _ lhsDate: Date, _ lhsID: String?,
        _ rhsDate: Date, _ rhsID: String?
    ) -> Bool {
        if lhsDate != rhsDate { return lhsDate < rhsDate }
        return (lhsID ?? "") < (rhsID ?? "")
    }
```
> Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift:120-126@7c200bf

The seal-input variant (goshuin share image) runs Seek and Honor crossings together and skips archived walks:

```swift
    private static func crossings(for input: SealInput, among allInputs: [SealInput]) -> Set<Milestone> {
        guard input.foundPlaceCount > 0 || input.honorArrivalCount > 0 else { return [] }
        let earlier = allInputs.filter {
            $0.uuid != input.uuid
                && isOrderedBefore($0.startDate, $0.uuid, input.startDate, input.uuid)
        }
        ...
        if input.honorArrivalCount > 0 {
            milestones.formUnion(
                honorMilestones(
                    arrivalsInWalk: input.honorArrivalCount,
                    arrivalsBefore: earlier.reduce(0) { $0 + $1.honorArrivalCount }
                )
            )
        }
```
> Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift:316-340@7c200bf

```swift
        if let uuid = input?.uuid,
           UserPreferences.isArchivedWalk(uuidString: uuid) {
            return []
        }
```
> Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift:259-262@7c200bf

The book's walk variant uses the same count map and the same ordering (`GoshuinMilestones.swift:224-232,237-251@7c200bf`).

**Ordering against every other milestone, and the tie-break against Seek.**

```swift
    static func primaryMilestone(of milestones: Set<Milestone>) -> Milestone? {
        milestones.min { lhs, rhs in
            (displayPriority(lhs), -intraPriority(lhs), subPriority(lhs))
                < (displayPriority(rhs), -intraPriority(rhs), subPriority(rhs))
        }
    }

    private static func displayPriority(_ milestone: Milestone) -> Int {
        switch milestone {
        case .firstWalk: return 0
        case .firstUnknown, .firstHonor: return 1
        case .unknownsFound, .honorsWalked: return 2
        case .nthWalk: return 3
        case .firstOfSeason: return 4
        case .longestWalk: return 5
        case .longestMeditation: return 6
        }
    }

    private static func intraPriority(_ milestone: Milestone) -> Int {
        switch milestone {
        case .nthWalk(let n), .unknownsFound(let n), .honorsWalked(let n): return n
        default: return 0
        }
    }

    /// Seek before honor when displayPriority and intraPriority both tie.
    private static func subPriority(_ milestone: Milestone) -> Int {
        switch milestone {
        case .firstHonor, .honorsWalked: return 1
        default: return 0
        }
    }
```
> Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift:35-67@7c200bf

The caption shows the minimum of `(displayPriority, −n, subPriority)`:

1. `"First Walk"` beats everything.
2. `"First Unknown"` and `"First Honor"` (priority 1): tie → Seek wins → `"First Unknown"`.
3. `"N Unknowns"` and `"N Ways Walked"` (priority 2): the larger `n` wins; equal `n` → Seek wins.
4. Every threshold crossing beats `"10th Walk"` and the rest.

Examples: `{firstHonor, nthWalk(10)}` → `"First Honor"`; `{firstHonor, honorsWalked(10)}` (a walk with ten arrivals and none before) → `"First Honor"`; `{unknownsFound(10), honorsWalked(25)}` → `"25 Ways Walked"`; `{firstUnknown, firstHonor}` → `"First Unknown"`.

Android's comparator has the first two keys only, and no Honor cases (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestones.kt:227-243@5ea4029b`).

**How a milestone shows.** Any milestone (Honor included) draws the dawn ring and the caption under the seal cell; archived walks show `"Archived"` instead.

```swift
                if isMilestone && !isArchived {
                    Circle()
                        .stroke(Color.dawn.opacity(0.5), lineWidth: 2)
                        .frame(width: 136, height: 136)
                }
                ...
            if isArchived {
                Text("Archived")
                    .font(Constants.Typography.caption)
                    .foregroundStyle(Color.fog.opacity(0.7))
            } else if let milestone = GoshuinMilestones.primaryMilestone(of: milestones) {
                Text(GoshuinMilestones.label(for: milestone))
                    .font(Constants.Typography.caption)
                    .foregroundStyle(Color.fog)
            }
```
> Pilgrim/Scenes/Goshuin/GoshuinPageView.swift:50-72@7c200bf

**Other Honor effects of a milestone.** An Honor crossing also puts the walk at a seeking gate in the journal, which replaces the staffs with a torii (sections 4 and 5). The summary's own milestone callout (`computeMilestone`, `WalkSummaryView.swift:487-527@7c200bf`) has no Honor line.

**Stage branch point.** Stages write the same arrival waypoint, so stage arrivals count towards `"First Honor"` and `"N Ways Walked"` too; nothing in the milestone code tests `way.stage`.

### 8. The prompt lexicon for Honor walks

**The model.** A third practice mode and a story context:

```swift
/// How the walk was undertaken — each mode carries its own ritual grammar,
/// explained to the downstream model by the practice lexicon.
enum PracticeMode {
    case wander
    case seek
    case honor
}
```
> Pilgrim/Models/Prompt/ActivityContext.swift:3-9@7c200bf

```swift
/// What this honor held: whose Way was followed, and whether its end was
/// reached. The title is nil until a caller that can reach the Way store
/// fills it — the event stream alone does not carry it. `routeName` and
/// `stageLabel` are set only for a pilgrimage stage, where there is no other
/// walker for the lexicon to speak of.
struct HonorStoryContext {
    let wayTitle: String?
    let arrived: Bool
    var routeName: String?
    var stageLabel: String?
```
> Pilgrim/Models/Prompt/ActivityContext.swift:17-26@7c200bf

`ActivityContext` gains `let honorStory: HonorStoryContext?` beside `seekStory` (`ActivityContext.swift:77-79@7c200bf`).

**Who fills it.** The prompt screen, while it builds the context (never in a body). `arrived` comes from the `.honorArrival` event; `wayTitle` from the Way loaded through the link (nil if the Way is gone); `routeName` and `stageLabel` only for a stage.

```swift
    /// Reached only from `buildActivityContext()`, never from a body — the
    /// Way title costs a disk read, and only for an honor walk.
    private var practice: (mode: PracticeMode, seekStory: SeekStoryContext?, honorStory: HonorStoryContext?) {
        let practice = WalkPracticeModel.practice(events: walk.workoutEvents.map { ($0.eventType, $0.timestamp) })
        guard let story = practice.honorStory, let uuid = walk.uuid else { return practice }
        let way = WayStore.shared.way(forWalk: uuid)
        return (practice.mode, practice.seekStory,
                HonorStoryContext(wayTitle: way?.title, arrived: story.arrived,
                                  routeName: routeName(for: way),
                                  stageLabel: way?.stage.map { "stage \($0.index + 1) of \($0.count)" }))
    }
```
> Pilgrim/Scenes/Prompts/PromptListView.swift:226-236@7c200bf

`routeName(for:)` returns nil unless `way?.stage` exists (`PromptListView.swift:241-246@7c200bf`). So on an own or shared walk `routeName` is nil, which selects the shared-walk form below. If the walk has no uuid, the story keeps `wayTitle: nil` from the model.

**Where the lexicon goes.** The "**About this practice:**" paragraph always sits in the context dossier, after the celestial block and before the intention (`PromptAssembler.swift:54-62@7c200bf`):

```swift
        if let celestial = context.celestial {
            sections += "\n\n\(ContextFormatter.formatCelestial(celestial))"
        }

        sections += "\n\n\(practiceLexicon(context: context))"
```
> Pilgrim/Models/Prompt/PromptAssembler.swift:54-58@7c200bf

**The Honor lexicon, every line.**

```swift
        case .honor:
            guard let story = context.honorStory else {
                return sharedWalkBaseText
            }
            return story.routeName == nil ? sharedWalkLexicon(story) : stageLexicon(story)
        }
    }

    /// Sole source for the shared-walk opening sentence — the no-story guard
    /// above and `sharedWalkLexicon` both start from it, so a copy edit can
    /// never desync one from the other.
    private static let sharedWalkBaseText = "**About this practice:** This walk was an Honor. The walker followed a Way another walker laid down, hearing their voices where they were spoken. Two traveling together; the line was traced, not raced."

    /// A stage has no other walker: the route itself is the company, and the
    /// lexicon must never put a voice where there is none.
    private static func stageLexicon(_ story: HonorStoryContext) -> String {
        var text = "**About this practice:** This walk was an Honor on a pilgrimage route. The walker followed one day's stage of a route walked for centuries, guided by the route's own places rather than by a companion's voice. The line was traced, not raced."
        if let route = story.routeName { text += " The route: \(route)." }
        if let stage = story.stageLabel { text += " The stage: \(stage)." }
        if let title = story.wayTitle { text += " Named: \(title)." }
        text += story.arrived
            ? " The end of the stage was reached."
            : " The stage was left before its end, which the practice honors too."
        return text
    }

    private static func sharedWalkLexicon(_ story: HonorStoryContext) -> String {
        var text = sharedWalkBaseText
        if let title = story.wayTitle { text += " The Way: \(title)." }
        text += story.arrived ? " The end of the Way was reached." : " The Way was left before its end, which the practice honors too."
        return text
    }
```
> Pilgrim/Models/Prompt/PromptAssembler.swift:170-201@7c200bf

The own-walk and shared-walk form (iOS has one form for both; there is no own-walk wording):

1. Always: `**About this practice:** This walk was an Honor. The walker followed a Way another walker laid down, hearing their voices where they were spoken. Two traveling together; the line was traced, not raced.`
2. If the Way's title is known: ` The Way: <title>.` (leading space; the title is inserted raw, then a full stop).
3. Then exactly one of:
   - `.honorArrival` present: ` The end of the Way was reached.`
   - absent: ` The Way was left before its end, which the practice honors too.`

With no story at all (unreachable from `WalkPracticeModel`, which always makes one for Honor) the paragraph is sentence 1 alone.

Assembled examples, for tests:

- Own walk, Way present, arrived: `**About this practice:** This walk was an Honor. The walker followed a Way another walker laid down, hearing their voices where they were spoken. Two traveling together; the line was traced, not raced. The Way: Morning loop. The end of the Way was reached.`
- Way deleted, not arrived: `**About this practice:** This walk was an Honor. The walker followed a Way another walker laid down, hearing their voices where they were spoken. Two traveling together; the line was traced, not raced. The Way was left before its end, which the practice honors too.`

Stage branch point: `story.routeName != nil` (only when `way.stage` exists) selects `stageLexicon`, which adds ` The route: …`, ` The stage: stage N of M.`, ` Named: …`, and a stage-worded ending. Out of scope here.

**Other Honor text the prompt carries.** The arrival waypoint is a waypoint, so it is printed in the "Waypoints marked during walk" block with its label, `Walked their way: <title>`, its time, and its GPS:

```swift
        if !context.waypoints.isEmpty {
            let lines = context.waypoints.map { wp in
                "[\(ContextFormatter.timeFormatter.string(from: wp.timestamp)), GPS: \(ContextFormatter.formatCoord(wp.coordinate.lat, wp.coordinate.lon))] \(wp.label)"
            }.joined(separator: "\n")
            sections += "\n\n**Waypoints marked during walk:**\n\(lines)"
        }
```
> Pilgrim/Models/Prompt/PromptAssembler.swift:80-85@7c200bf

```swift
        let waypointContexts = walk.waypoints.map { wp in
            WaypointContext(
                label: wp.label, icon: wp.icon, timestamp: wp.timestamp,
                coordinate: (lat: wp.latitude, lon: wp.longitude)
            )
        }
```
> Pilgrim/Scenes/Prompts/PromptListView.swift:182-187@7c200bf

No other prompt file reads the mode: `git grep -i "honor\|context.mode"` across `Pilgrim/Models/Prompt` and `Pilgrim/Scenes/Prompts` finds only the three files quoted here, so `WalkCharacter`, `AttentionDirectives`, the preamble, and the response contract are unchanged by Honor. Replies are ordinary voice recordings of the walk, so their transcriptions appear in the walking transcription like any other recording, with no reply marker.

Android counterparts: `PracticeMode` is `{ Wander, Seek }` (`app/src/main/java/org/walktalkmeditate/pilgrim/core/prompt/ActivityContext.kt:21@5ea4029b`); `WalkPracticeModel` routes through `walkModeFromEvents`, which cannot return Honor today (`ActivityContext.kt:39-50@5ea4029b`); the lexicon `when` is at `app/src/main/java/org/walktalkmeditate/pilgrim/core/prompt/PromptAssembler.kt:280-287@5ea4029b`. iOS's prompt derivation tests `.honorMode` before `.seekMode` (section 1), so a walk carrying both reads as Honor in prompts.

### 9. The reliquary's Honor parts

There are none at the pin. `PhotoCarouselView.swift` has no Honor code; its one match for "honor" is the verb in a Reduce Motion comment:

```swift
            .scaleEffect(isActive ? Self.activeScale : 1.0)
            // Honor Reduce Motion: when enabled, the activation state still
            // flips (centered pin button appears, scale changes) but without
            // the spring animation. Same pattern WalkSummaryView uses for
            // its share-card reveal.
```
> Pilgrim/Scenes/WalkSummary/Reliquary/PhotoCarouselView.swift:113-117@7c200bf

`git grep -n -i "honor\|WayStore" 7c200bf -- Pilgrim/Scenes/WalkSummary/Reliquary` returns only that line. The folder's last change at the pin predates Honor (`20142de feat: Constellation appearance mode ...`). The reliquary sits second in the summary, above the Honor section (section 2). Its candidates are the walker's own library photos matched to this walk, as on any walk. The Way's own photos are never shown in the summary: the summary map receives no Way pins (section 3), and nothing in the summary reads the Way's media folder. Pinned photos draw as map pins above the ghost line, like every pin.

### 10. An imported iOS honor walk, and Honor being unavailable

**What a `.pilgrim` carries.** The events (`"honorMode"`, `"honorArrival"`) and the arrival waypoint travel with the walk; the Way, its media, its replies, and the walk-to-Way link do not. Nothing in `Pilgrim/Models/Data/PilgrimPackage` references `WayStore` or `WayLink` at the pin. The walk keeps its uuid on import:

```swift
            uuid: walk.id,
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:332@7c200bf

So a re-import on the same iPhone finds its old link (keyed by uuid) and looks exactly as before. An import on another iPhone has events and waypoint but no link. iOS then renders it as below. This is iOS's only "Way missing" state; a deleted Way or a failed link write produce the same result:

| Surface | Rendering with `.honorMode` present and no link |
|---|---|
| Summary section | shows: `"in their steps"`, `"a way that has been removed"`, no delta line, no counts line (voices 0, replies 0) |
| Summary map | the walk only, no ghost line; the arrival signpost if the waypoint came along |
| Journal | the Honor footprint (staff); staffs scenery and the cairn haptic if the arrival waypoint came along (unless a gate outranks them) |
| Seal | the walk's own line only |
| Milestones | the arrival waypoint counts towards `"First Honor"` and `"N Ways Walked"` |
| Prompts | the shared-walk form without ` The Way: …`; "reached" from the `.honorArrival` event |

The two summary rows come straight from the model's fallbacks: `wayTitle: way?.title ?? "a way that has been removed"` and `voicesAlongTheWay: way?.voiceCount ?? 0` (`HonorSummarySection.swift:36-38@7c200bf`), with `replies` defaulting to `[:]` when there is no link (`WalkSummaryView.swift:748@7c200bf`).

**Honor unavailable.** iOS has no such state at the pin. Honor is always offered:

```swift
    var isAvailable: Bool { true }
```
> Pilgrim/Models/Walk/WalkMode.swift:22@7c200bf

So iOS has nothing to copy for a build where Honor is off. Every Honor branch above keys only on stored data (the event, the waypoint, the link). The plan's rule that a flag-off Android build draws imported honor walks as plain walks is Android's own decision, not iOS behavior. Android at HEAD already does that for the mode (`walkModeFromEvents` ignores HONOR_MODE, `WalkEventReplay.kt:82-91@5ea4029b`). The arrival waypoint stays a stored waypoint either way: it prints in prompts as `"Walked their way: <title>"` and draws on Android's map with the fallback pin (section 3).

### 11. Accessibility

iOS gives the Honor after-the-walk surfaces almost no accessibility of their own. What exists, surface by surface:

**Summary section.** No container grouping, no header trait, no labels on the text rows. VoiceOver reads each `Text` as its own element, in visual order: the kicker, the title, the delta line, the counts line. The strings read as written (for example "in their steps", "Morning loop", "they arrived 2 minutes before you", "3 voices along the way · 1 reply"). The only explicit label is on the stage-only reply button:

```swift
                .accessibilityLabel("Play your reply to this stage")
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:86@7c200bf

That label replaces the visible title ("your reply" / "pause") and does not change while playing; the button has no hint. It never shows on an own walk.

**"walk this again".** A plain `Button` with `Label("walk this again", systemImage: "signpost.right")` (`WalkSummaryView.swift:686@7c200bf`). VoiceOver reads "walk this again", button. No hint.

**Summary map.** No accessibility on the ghost line or on the arrival pin. The map is a Mapbox `UIViewRepresentable`; the ghost is a style layer, not an element.

**Journal.** The mode miniature is hidden (`.accessibilityHidden(true)`, `WalkModeFootprints.swift:41@7c200bf`). Scenery, the staffs included, is hidden (`.accessibilityHidden(true)`, `InkScrollView+Scenery.swift:58@7c200bf`). The dot's label does not mention the mode:

```swift
    private var accessibilityText: String {
        let dateStr = Self.dateFormatter.string(from: snapshot.startDate)
        let distance = Measurement(value: snapshot.distance, unit: UnitLength.meters)
        let distanceStr = Self.measurementFormatter.string(from: distance)
        let duration = Self.formatDuration(snapshot.duration)
        var text = "Walk on \(dateStr), \(distanceStr), \(duration)"
        if snapshot.hasTalk { text += ", \(Self.formatDuration(snapshot.talkDuration)) talking" }
        if snapshot.hasMeditate { text += ", \(Self.formatDuration(snapshot.meditateDuration)) meditating" }
        return text
    }
```
> Pilgrim/Scenes/Home/WalkDotView.swift:195-204@7c200bf

```swift
                .accessibilityLabel(accessibilityText)
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Opens the walk detail card")
```
> Pilgrim/Scenes/Home/WalkDotView.swift:45-47@7c200bf

So a VoiceOver user cannot tell an Honor walk from a wander walk in the journal. The scroll haptic is also off under Reduce Motion (`guard !UIAccessibility.isReduceMotionEnabled else { return }`, `Pilgrim/Scenes/Home/ScrollHapticEngine.swift:35@7c200bf`).

**Seal and goshuin.** The seal image and its watermark carry no accessibility label anywhere. The goshuin cell (`GoshuinPageView.swift:44-73@7c200bf`) has no label, trait, or hint; its thumbnail is an unlabelled `Image(uiImage:)`, and its tap is a bare `onTapGesture`. The only text VoiceOver can read there is the caption (`"First Honor"`, `"10 Ways Walked"`, or `"Archived"`).

**Prompts.** No new UI; the lexicon is prompt text.

**Reliquary.** Unchanged (section 9): `"Photo, captured <date>"` / `"Photo, captured <date>, pinned to map"`, hint `"Shows photo at full size"`, action `"Pin to map"` / `"Unpin from map"` (`PhotoCarouselView.swift:135-148@7c200bf`).

Android: the journal miniature already clears semantics (`WalkModeFootprints.kt:78@5ea4029b`), matching iOS's `.accessibilityHidden(true)`. For U23's semantics test, iOS at the pin gives only the four plain text rows and the stage-only reply label to assert.

### Resolutions for the plan

1. **`HonorSummarySection`.** A full-width, leading-aligned card on `parchmentSecondary`, corner 12, padding 16, row spacing 8, placed right after the Seek section and before the elevation profile, with no reveal fade. Own-walk rows: `"in their steps"` (caption, fog); the Way title or `"a way that has been removed"` (heading, ink); the delta line (`"you arrived together"` / `"they arrived N minute(s) after you"` / `"… before you"`, minutes truncated, only when the link holds both arrival numbers); the counts line (`"N voice(s) along the way"` and `"N reply/replies"`, joined by `" · "`, only when either is above zero). `voiceCount` is the Way's total voices, not those heard. The section links nowhere on an own walk; the summary's Honor door is the separate `"walk this again"` button, offered on any walk with two or more route points. No string is localized. Section 2.
2. **The summary map.** Only the Way's ghost line is added: no companion, no Way moment pins, no marks. The ghost is the live walk's renderer unchanged: width 4, round cap and join, colour by span (meditating `dawn`, talking `rust`, else `moss`, never the turning colour), opacity 0.22 light / 0.4 dark, under the walk's casing and route line, inside the radial mask. The camera fits the walk only. The arrival waypoint draws as `signpost.right.fill`, 18 pt, `stone`, with no halo, the same as live. Section 3.
3. **The journal.** Honor walks get the staff miniature in the expand card (left print plus `StaffGlyph` stroked at 1 in 8×14, seasonal colour at 0.3), which wins over Seek's. Walks with arrival waypoints get the cairn scroll haptic and the staffs scenery (unless a gate outranks them). An Honor threshold puts the walk at a seeking gate. The dot, its label, and the card's text are unchanged. Section 4.
4. **Scenery.** `staffs`, tint `stone`, parallax 9, chosen when `isHonor && honorArrivals > 0`, **after** the gate and the Seek cairn and before the lottery. The first Honor arrival ever, and the 10th/25th/50th/100th crossings, show a seeking torii instead, as do practice-gate walks. Drawing: two stroked staffs `(0.20w,h)→(0.55w,0.08h)` and `(0.80w,h)→(0.45w,0.08h)` with a stroked ring knot `(0.42w, 0, 0.16w, 0.10h)`, width `max(1, 0.08w)`, round caps; a 1.06× shadow at tint 0.1, offset 1.5, blur 1.2, under the body at tint 0.35; static; no snow, no halo. Section 5.
5. **The seal watermark.** Every seal whose walk has 2+ route samples draws the walk's line; an honor seal whose Way resolves (2+ points) also draws the Way's line beneath it. One shared square fit in raw degrees (`span = max(latSpan, lonSpan, 0.0001)`, no cos-latitude), centred on the combined box's midpoint, longer side spanning `2 × 0.7 × 0.44 × size`; `y` inverted so north is up before the seal's hash rotation, which the watermark shares. Stroke in the seal's own ink, width 1 at a 512 render (`canvasSize / 512`), round cap and join, each line one path; Way alpha 0.03 drawn first, walk alpha 0.055 on top. It is the lowest layer after weather, under the elevation ring and rings. iOS caches the rendered image by uuid with no expiry, so the lines are fixed at first render. Section 6.
6. **Milestones.** `"First Honor"` and `"N Ways Walked"` at 10, 25, 50, 100, counted from arrival waypoints (not events), awarded to the crossing walk with a strict, uuid-tie-broken "before". Caption priority: First Walk, then First Unknown/First Honor, then N Unknowns/N Ways Walked (larger n first), then the rest; a full tie goes to Seek. Any Honor milestone draws the dawn ring and also makes the journal gate. Section 7.
7. **The prompt lexicon.** `PracticeMode.honor` when `.honorMode` exists (checked before Seek). One form for own and shared walks: the base sentence `**About this practice:** This walk was an Honor. The walker followed a Way another walker laid down, hearing their voices where they were spoken. Two traveling together; the line was traced, not raced.`, then ` The Way: <title>.` when the Way loads, then ` The end of the Way was reached.` when `.honorArrival` exists, else ` The Way was left before its end, which the practice honors too.` The plan's "own-walk and shared-walk forms" are one form on iOS. The arrival waypoint also prints under "Waypoints marked during walk" as `Walked their way: <title>`. The stage form is selected by `routeName != nil` and is out of scope. Section 8.
8. **The reliquary.** No Honor parts at the pin. Section 9.
9. **Classification and imports.** Honor means a `.honorMode` event (raw 5, wire `"honorMode"`), written at `startRecording` only when the mode is Honor and a Way is present; arrival adds `.honorArrival` (raw 6) and the reserved-icon waypoint. Each surface pairs that test with a different second signal (table in section 1). A `.pilgrim` carries events and waypoint but no Way or link, so an iOS honor walk imported onto another device shows the removed-way section, no ghost, the walk-only seal, the staff footprint, the milestone, and the untitled lexicon. iOS has no "Honor unavailable" state (`isAvailable` is always true); Android's flag-off rendering is its own decision. Sections 1 and 10.
10. **Accessibility.** iOS adds almost nothing: the summary's rows are plain texts read one by one, with no grouping or header; the only label is the stage-only `"Play your reply to this stage"`; the journal's miniature and staffs are hidden; the dot's label omits the mode; the seal, its watermark, and the goshuin cell have no labels. Section 11.

Further plan statements checked against iOS:

- U23 "staffs scenery for walks with an arrival, **at the cairn's priority**": the staffs rank directly **below** the cairn, and gates rank above both (resolution 4).
- U23 "the delta line appears when the marker lands" and "a summary opened before the marker lands": iOS has no such window; its link is written before the seal reveal, and the summary reads it once at open (section 1).
- U23 "a recovered walk (no delta) → no delta line": matches iOS, where the delta needs both link numbers. On iOS a recovered walk with no link at all reads as `"a way that has been removed"` (the plan already lists the `rebindWay` issue).
- U23 "an arrival label built from a 163-character Way title matches iOS's label": the label is `String(format: "Walked their way: %@", wayTitle)` with no truncation (`HonorPersistence.swift:30-32,42-44@7c200bf`).
- U23 "the Way's line **at iOS's alpha**": 0.03, under the walk's 0.055 (resolution 5).

### iOS defects found

**D1. The summary's reply count is the Way's lifetime count, not this walk's.**
`repliesMade` is `replies.count`, and `replies` is the Way's whole `replies.json`, one entry per origin voice, overwritten by each new reply to that voice:

```swift
        let replies = link.map { WayStore.shared.replies(for: $0.wayId) } ?? [:]
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:748@7c200bf

```swift
            repliesMade: replies.count,
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:39@7c200bf

```swift
        var map = replies(for: wayId)
        map[originN] = relativePath
```
> Pilgrim/Models/Honor/WayStore.swift:175-176@7c200bf

The walk's own path treats a mapping whose recording is gone as no reply (`/// A mapping whose recording is gone reads as no reply at all`, `ActiveWalkViewModel+Replies.swift:43-45@7c200bf`), but the summary counts it anyway.
User impact: honor the same Way twice (for an own walk, "walk this again" on the same source walk always yields the same Way id, `"walk:\(uuid.uuidString)"`, `OwnWalkWayBuilder.swift:109@7c200bf`) and each summary shows the union of both walks' replies. An earlier walk's summary grows "N replies" after a later walk. A reply whose recording file is gone still counts. (On a stage the reflection reply, key −1, is also counted.)

**D2. Own-walk Honor copy speaks of another walker.**
iOS knows an own-walk Way (`case ownWalk(UUID)`, `Pilgrim/Models/Honor/Way.swift:156@7c200bf`), but no after-the-walk text branches on it. The summary kicker splits only on stage (`data.isPilgrimageStage ? "the stage you walked" : "in their steps"`, `HonorSummarySection.swift:98-100@7c200bf`). The lexicon splits only on `routeName` and tells the model `The walker followed a Way another walker laid down, hearing their voices where they were spoken. Two traveling together` (`PromptAssembler.swift:181@7c200bf`). The arrival waypoint says `"Walked their way: %@"` (`HonorPersistence.swift:42-44@7c200bf`).
User impact: after "walk this again" on their own walk, the walker reads "in their steps", and their reflection prompts tell the model a second person laid the Way and spoke the voices. Because the context itself asserts the companion, the contract's closing line ("never invent details, events, or memories that are not in the context above", `PromptAssembler.swift:219@7c200bf`) cannot stop the model from speaking of one.

**D3. A deleted Way's line stays on the seal, while the summary says the Way is gone.**
The seal is rendered once and cached by uuid with `expiry: .never`; no code clears it, and only the cache's size caps can evict it (`SealCache.swift:32-36@7c200bf`; no `SealCache.shared.clear` call site). Deleting the Way removes its links (`WayStore.swift:123-129@7c200bf`), so the summary shows `"a way that has been removed"` and no ghost, but the goshuin seal and every seal share keep the Way's line. The reverse also holds: a seal first rendered without a link would not gain the line if a link appeared later.
User impact: small, visible inconsistency between the summary and the seal for the same walk.

**D4. The summary map does not frame the Way.**
The reveal fits `boundsForRoute(coords)` with `coords = cachedRouteCoordinates`, the walk only (`WalkSummaryView.swift:419-434@7c200bf`). A walk left before the Way's end, or one that strayed, shows the ghost clipped at the map's edge and faded by the 80–180 pt mask.
User impact: the ghost the summary adds is often only partly visible. Possibly intended (the summary is about the walk); worth confirming upstream.

**D5. The journal gives VoiceOver no sign of an Honor (or Seek) walk.**
The dot's label is date, distance, duration, talk, meditation only (`WalkDotView.swift:195-204@7c200bf`); the miniature and the staffs are hidden (`WalkModeFootprints.swift:41@7c200bf`, `InkScrollView+Scenery.swift:58@7c200bf`).
User impact: a VoiceOver user cannot find their honor walks or the walks that reached a Way's end. Shared with Seek.

**D6. The journal gate and the goshuin milestone can disagree on which walk crossed.**
The journal counts "before" in fetch order (`walks` sorted by `_startDate` descending, reversed; `HomeViewModel.swift:94-128@7c200bf`), with no tie-break. The goshuin uses `isOrderedBefore` with a uuid tie-break (`GoshuinMilestones.swift:117-126@7c200bf`).
User impact: two walks with the same start date (an import) can put the torii on one walk and `"First Honor"` on the other. Rare; shared with Seek.

### Open questions

- **Which appearance the ghost's colours resolve to.** The line's `UIColor.dawn` / `.rust` / `.moss` are asset-catalogue colours with light and dark variants, handed to Mapbox inside a match expression at install time (`PilgrimMapView+HonorWay.swift:195-204@7c200bf`). iOS does not state whether they resolve to the dark variants on the dark map; its comment covers only the opacity (`an appearance flip reloads the style, which reinstalls the layers with the other palette`). The walk's own route line uses the same conversion (`PilgrimMapView+RouteSource.swift:134-141@7c200bf`), so whatever Android's route line resolves to today is the nearest parity reference.
- **Whether the frozen seal is intended.** iOS's seal cache is permanent by design:

```swift
        // revisit. 256 covers ~40 pages of 6 thumbnails. No expiry while the
        // book is open — eviction is what caused the scroll churn.
```
> Pilgrim/Models/Seal/SealCache.swift:40-41@7c200bf

  plus `expiry: .never` on disk (`SealCache.swift:34@7c200bf`). Nothing says whether a Way line surviving the Way's deletion (D3) is wanted. Android draws seals live, so it cannot copy the frozen image without a decision.
