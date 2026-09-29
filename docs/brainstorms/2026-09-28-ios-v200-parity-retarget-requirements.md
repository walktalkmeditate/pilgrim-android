---
date: 2026-09-28
topic: ios-v200-parity-retarget
---

# iOS v2.0.0 Parity Retarget — Honor

## Summary

Re-pin the Android parity anchor to `pilgrim-ios` v2.0.0 (`7c200bf`) and ship Android 2.0.0 as an exact port of it: **Honor**, the third walking mode — follow a Way from one of your own walks, a shared walk, or an open-pilgrimages stage, with offline maps — plus iOS's all-walk fixes and Android's known parity gaps, released only after a parity gate passes. The work lands in slices on main behind a release flag, opening with a pocket-tested own-walk vertical that proves the Android-only architecture before breadth is built on it.

---

## Problem Frame

Android v1.5.0 (code 733, 2026-08-31) shipped at exact parity with `33b0dbc`; iOS's `v1.11.0` tag now points at `bcdf538` (`33b0dbc` plus a build bump), so Android matched shipped iOS 1.11.0 exactly. iOS then shipped v2.0.0 (tag `7c200bf`): 184 commits, ~10.6K lines of production Swift across 101 files (48 new), and ~7.9K lines of tests (~424 new), across nine PRs. Its headline is Honor, built as three slices over two weeks and released once. iOS's three design specs — `docs/superpowers/specs/2026-09-01-honor-mode-design.md`, `2026-09-03-honor-slice-two-pilgrimage-stages-design.md`, `2026-09-14-honor-slice-three-offline-tiles-design.md` — each list Android parity as out of scope. The product shape is settled; Android's work is translation.

The gap already costs Android users:

- Interactive shares created since early September carry a "walk this" pill pointing at `honor.pilgrimapp.org`. On Android the tap opens a browser page offering Google Play, even when Pilgrim is installed, and there is no way to walk the share.
- An iOS 2.0.0 export of an honor walk loses its mode on Android: the events import as unknown and re-export as `"unknown"`.
- Android-made interactive shares carry no per-recording coordinate, so an iOS walker honoring one hears its voices at positions approximated along the line rather than where they were spoken.

Android also faces problems iOS never had. iOS runs one process that keeps living in the background. Android's walk runs in the `:tracker` foreground-service process while Seek, the voice guide, and the map live in the UI process — whispers were moved into `:tracker` precisely so they survive a UI reclaim. Universal links, the background URL session, iCloud backup exclusion, the Live Activity, CoreHaptics, and Xcode's location simulation all need Android equivalents, and Android has no deep-link handling, clipboard-paste flow, route-projection math, or offline-map code today.

| iOS delta (`33b0dbc..7c200bf`) | Content | Android disposition |
|---|---|---|
| PR #81 (slice one) | Honor mode; the Way; own-walk and shared-walk sources; honor links + paste; media gathering + expiry; the engine (anchor, windowed progress, companion, moments, gates, soft tap, arrival); ghost line, pins, place cards, reply here; summary, journal staffs, seals, prompt lexicon; lock-screen glance; Settings → Ways; share-payload recording coordinates; debug GPX simulation | Port — Stage 21-1 |
| PR #83 | Voice cards quote the transcript's first sentence (own walks carry the transcription; the shared-walk field is parsed, but no client sends it) | Port — Stage 21-1 |
| PR #84 (slice two) | Pilgrimage stages: catalog, route page, one-route package lifecycle, ledger, morning card, arrival reflection + reply, service marks, water ahead, local names, stage language | Port — Stage 21-2 |
| PR #85 | Catalog grouped under its pilgrimage in walking order; install badge replaces the "updated" clause | Port — Stage 21-2 |
| PR #86 (slice three) | Offline maps per stage, end state: z11–14, no terrain DEM, convex-part corridor, pack-count estimate with per-route calibration, launch reconciliation, Settings → Maps | Port — Stage 21-3 |
| PR #87 | Removes the debug "simulate no signal" toggle; test-host fix | Absorbed (the toggle is never built); test fix N/A |
| PR #88 | Walk-screen ornament lift reverted; the stage overview card overlays the map with its height as the map's inset; About keeps the Mapbox/OSM credits | Port the overlay + credits; the ornament lift never existed on Android |
| PR #89 | A failed bounds fit is retried, not recorded as done; skipped while the view has no room; inset clamped | Port into the new overview camera; audit existing map cameras in Stage 21-0 |
| PR #82 | CombineExt `DemandBuffer` lock patch | N/A (iOS pod) — health-check Android's location-pipeline teardown for the same bug class |
| iOS-only | pbxproj, entitlements, Podfile, widget, background-session AppDelegate hook, `ScreenshotDataSeeder` honor walk | N/A |
| After the tag | PR #90 (App Store listing copy for 2.0.0, docs only) | Input to the Play listing (R25) |

---

## Requirements

**Anchor, versioning, fold-in**

- R1. The frozen parity anchor becomes `pilgrim-ios` @ `7c200bf` (the v2.0.0 tag). Update the parity-scope and phasing sections of `CLAUDE.md` (Phase 21 = Honor, target Android 2.0.0), the `ios-parity` skill's pinned anchor, and memory entries that name `33b0dbc` as current. Record `intentionEcho` accurately: iOS closed #80 on 2026-09-16 with no code change, so it stays unfixed on both platforms and Android stays parity-exact.
- R2. The fold-in rule carries forward: iOS commits landing before Android 2.0.0 ships are re-diffed and triaged. Chores, hotfixes, and incremental refinements to Honor or any surface this release touches fold into Phase 21 (reopening it if closed; once 2.0.0 is code-complete and awaiting release, the delta becomes a named pre-release stage gated before the tag), then re-pin. A new headline feature, a revert of ported work, or a redesign triggers explicit user re-triage. The fold-in window closes when the R24 gate starts: iOS deltas merging after that are triaged by the same rules into Android 2.0.1, so the gate runs once against a fixed pin and 2.0.0 stays at parity with an iOS 2.0.x build. Known at writing: PR #91 (a temple ahead says it stamps until five) folds in if it merges first; PR #92 (Mapbox telemetry off on iOS) becomes a check of Android's existing opt-out; PR #90 feeds R25.
- R3. Android ships as **2.0.0**, deliberately matching iOS. `CLAUDE.md`'s "do not mirror iOS version numbers" rule is replaced: Android's major.minor tracks the iOS release it is at parity with, and patch numbers are per-platform (an Android-only hotfix is 2.0.1 whatever iOS does). versionCode follows the release pipeline's convention.

**Parity policy**

- R4. Port the end-state Swift at the pin. Spec sources: the three iOS Honor specs, their plans (`docs/superpowers/plans/2026-09-01-honor-mode-slice-one.md`, `2026-09-04-honor-slice-two-ios.md`, `2026-09-14-honor-slice-three-offline-tiles.md`), and the bodies of PRs #81–#89, cross-checked against shipped code wherever the journey diverged — shipped code wins. Known superseded content, never built: the debug offline toggle; the single offset-ring corridor; tile ranges from z0 and the terrain DEM; the per-tile size estimate; the catalog's "updated" clause; a mode subtitle for stages; stage Ways listed in Settings → Ways; a camera seed from the resume point; the 2026-03-23 route-packages design.
- R5. Exact means exact: every user-visible string (accessibility labels and content descriptions included), threshold, cap, default, timing, and ordering comes from the Swift at the pin rather than being re-derived (for example: voices trigger at 42 m and other moments at 60 m; the soft tap is off by default and fires after 120 s beyond 200 m; voices default on; the Honor seals press at 10, 25, 50, and 100 Ways). When the port finds an iOS defect, Android matches it as shipped, files a `pilgrim-ios` issue, and folds in any fix that lands before the gate starts (R2). Known already, to file at port time: the stage lock-screen glance reads "their way, walked"; stages keep the "map tiles need a connection" note after maps are saved; the overview temperature ignores the unit setting; deleting a Way also drops the arrival delta from past summaries; the worker's 60 MB per-share total is not enforced on the phone.
- R6. Every divergence is either a platform equivalent or a deliberate addition, and each is recorded at the gate (R24) with a dated reason. Platform equivalents: verified App Links for universal links; the walk notification's glance line for the Live Activity; Android background work for the background URL session, with the same caps, retry, disk-full, and deleted-Way semantics; exclusion from cloud backup and device-to-device transfer for iCloud exclusion; vibration compositions for CoreHaptics patterns; R20's simulator for Xcode's location simulation. The deliberate additions: the install-referrer handoff (R19), and haptics that fire with the screen off (R16) — iOS returns from every Honor haptic unless the app is in the foreground, and Android Seek already vibrates in the pocket by design (the 2026-07-14 Seek plan), so Honor extends that precedent.

**Stage 21-0 — groundwork (unflagged)**

- R7. iOS's all-walk fixes, ported:
  - Map cameras ease only when the bounds or inset actually change, skip a fit while the map has no room, clamp the inset, and record a fit as applied only after it lands. Every existing Android map camera is audited against this (iOS #81/#89).
  - A whisper waiting behind a voice-guide prompt is released when the prompt stops or errors, and stopping whispers clears any pending one — audited on Android, fixed where the bug class exists.
  - Discarding a walk mid-recording stops the recorder, deletes the partial file, and releases audio focus — audited, fixed where the bug class exists.
  - Interactive shares carry each recording's coordinate (the route sample nearest its start), omitted — not null — when the recording starts outside the doorstep-trim window. The interactive share caption reads "Anyone with the link can walk it there."
  - About gains the "© Mapbox" and "© OpenStreetMap contributors" credits.
  - Android's location-pipeline teardown is audited for the bug class iOS's CombineExt `DemandBuffer` patch fixed (a value delivered after a cancel racing the completion read), fixed where it exists.
  - Mapbox Android moves to iOS's engine line — 11.20 or later, and before 11.24's one-tile-store-per-process change — with the exact version pinned in planning.
- R8. `.pilgrim` round-trips keep Honor: the Honor mode and arrival events import and export under their iOS names, so an iOS 2.0.0 export keeps its mode on Android and survives re-export. Ways and ledgers are not exported, as on iOS.
- R9. Android's known parity gaps close — the four filed issues and one the review found:
  - #219: the active-walk camera follows the walker without fighting pan or pinch, disengaging on a gesture as iOS's does.
  - #221: the reliquary toggle reverts when photo permission is denied, as on iOS.
  - #223: activity intervals get one source of truth, so exports carry them and imports restore them — and Honor's own-walk sittings read the same source.
  - #225: an expired share shows "This walk has returned to the trail" with Share again.
  - Whisper over guide: today a whisper in `:tracker` ducks and plays over a UI-process guide prompt, where iOS holds it; it closes on R17's cross-process arbitration.

**Stage 21-1 — Honor with your own walks and shared walks (iOS #81, #83)**

- R10. The mode and its doors: Honor takes the Together slot (staff glyph, "walk in their steps", "Honor", iOS's quotes verbatim), and Begin reads "Choose a way" until a Way is chosen. The Honor sheet offers one of my walks, a shared walk (with the paste field), and — from Stage 21-2 — a pilgrimage, each with iOS's empty-state copy. "walk this again" appears on every summary with a route, at iOS's shipped threshold. The overview fits the whole Way, never the puck; lays its card over the map with the card's height as the map's inset; shows the weather comparison, voice and photo counts, the "walk with their voice" toggle, and the distance to the start; and never blocks Begin on distance.
- R11. Shared walks: an honor link (R18) or a pasted link or id imports the share's manifest with iOS's validation and error copy (not found, returned to the trail, couldn't reach). Media gathers in the background after acceptance within iOS's caps, with one retry, disk-full handling, and nothing landing for a Way deleted mid-transfer; the overview shows gathering progress and, on failure, offers "try again" and "walk without the missing voices". The expiry sweep follows iOS's table: an expired share never walked is removed whole; an expired share that was walked keeps its line and replies but loses its media ("voices returned to the trail"); own-walk Ways are never swept. Shares made before the worker's 2026-09-03 deploy place voices by fraction and estimate sittings, as on iOS. Like iOS's backup-excluded Ways tree, everything a Way stores — its manifest, downloaded voices and photos, and reply links — is excluded from cloud backup and device-to-device transfer, whatever storage shape planning picks, so a restore or a new phone can never resurrect swept voices.
- R12. On the walk and after, the full slice-one experience at the pin: the ghost line beneath the live route; the companion dot at the sharer's pace on the walker's active clock; moments firing once by place, with iOS's gates (recording, meditating, paused, a whisper or guide prompt playing), pause-not-stop, and the 300 m drop with its stationary exemption; place cards one at a time with "+N more"; the listening chip; the opt-in soft tap; arrival with iOS's progress gates and the arrival debounce shared with Seek (Seek's behavior pinned by tests before the extraction); reply here, with "your reply" offered on a later honoring; the summary's "in their steps" block and ghost line; journal staffs and footprints; the "First Honor" and "N Ways Walked" seals with the Way's ghost under the seal; the Honor practice lexicon in prompts; the walk notification's Honor glance; and Settings → Data → Ways with delete and a confirmed "Delete all Ways", unreachable mid-walk.

**Stage 21-2 — pilgrimage stages (iOS #84, #85)**

- R13. Catalog, route, packages, ledger: the catalog reads the open-pilgrimages index from its main branch (24 h cache, falling back to the cache on failure), lists only packaged routes grouped under their pilgrimage in walking order (every route in exactly one group), marks the installed route and a waiting update each with its own badge, and says "few places marked yet" for sparse routes. The route page offers the stage list with a next/continue row and Download, Update, Replace, and Remove with iOS's confirmations — one route at a time, all-or-nothing, pinned to the index's release, and all refused mid-walk with "finish your walk first". Index, route, and stage files are validated before use with iOS's bounds checks and its package-to-request identity cross-check, failing with iOS's "this route isn't walkable yet" copy rather than crashing on a malformed or mismatched package. The per-route ledger records a stage only when the engine anchored on its Way, carries kilometres across a redraw with its one-time notice, and survives Replace and Remove.
- R14. Walking a stage: no companion dot, no soft tap, and a stage line where a date would be. Cards carry the dataset's words, a local name in iOS's fixed language order, and "Sit?" where offered. Services draw as quiet, non-tappable marks from zoom 13, nearest 40 first. "water in 280 m" rides the caption slot with a soft haptic — on-way sources only, at most once per mark and once per hour of walking, the first free. The morning card opens at Begin and reopens as "the day"; the arrival card carries the stage's closing line with reply here, echoed on the summary beneath "X of Y km of the stage". Stage surfaces speak of the stage, never "they" or "their" — except where iOS itself ships it (R5).

**Stage 21-3 — offline maps (iOS #86)**

- R15. One opt-in "Save maps for the way · ~N MB" on the route page, sized before the tap, saves every stage's corridor for both the light and dark styles at iOS's end-state band (z11–14, no terrain DEM). Progress reads stage by stage, cancel keeps what is done, and a re-tap resumes at the first gap, skipping only stages whose saved region is complete and still matches the stage's current line. The estimate counts packs and is calibrated per route after that route's first save. The morning card says whether today's maps are saved, and Settings → Data → Maps shows what is saved and deletes it after confirmation. Nothing is orphaned: Remove and Replace take the maps, Update removes retired stages' maps and downloads nothing, and a launch reconciliation sweeps anything the installed route does not own. Saves run while the app is open and resume on the next tap (iOS parity), are refused mid-walk, allow cellular (the size is the guardrail), and are excluded from cloud backup and device-to-device transfer.

**Android-specific behavior**

- R16. The pocket bar is iOS's: Honor's place-triggered behaviors — voices, the card queue, captions, haptics (a deliberate divergence: iOS only vibrates in the foreground, R6), arrival — keep working for the whole walk with the screen off, in a pocket, across app switches, and when Android reclaims the UI process mid-walk. Whatever happens to the UI, the finished walk lands linked to its Way (and, for a stage, in its ledger), matching the outcome of iOS's crash recovery.
- R17. Audio arbitration is iOS's, across processes, in iOS's priority order — a guide prompt outranks a Way voice, which outranks a whisper: a guide prompt that starts mid-voice pauses the Way voice and takes over its duck, and the voice resumes where it stopped when the prompt ends; a Way voice does not start over a guide prompt or a whisper; a whisper never plays over a Way voice or a guide prompt; the soundscape ducks for a Way voice and restores after; and a voice paused by any gate resumes where it stopped — whether one or both of Android's processes are alive.
- R18. Honor links: verified App Links for `honor.pilgrimapp.org` only; `walk.pilgrimapp.org` links are accepted by paste, as on iOS. A tapped link routes as shipped iOS does: tapped before setup finishes, it is held in memory and opens once setup completes (lost if the app process dies first, as on iOS); a walk active → "finish this walk first"; otherwise the Honor overview opens, fetching. The worker serves the Android verification file carrying the Play app-signing, upload, and debug fingerprints, deployed and confirmed before 2.0.0 reaches users. Until then, the honor page's Android block replaces its install-then-tap-again line with one saying that walking a shared walk arrives with Pilgrim 2.0 for Android — a Stage 21-0 worker change — and the current block returns when the flag flips.
- R19. Install-referrer handoff (an Android-only addition, R6): on the first launch after an install from the honor page, the Play install referrer's honor id is read once, held through first setup, and — when setup completes — opens that Way's overview exactly as a tapped link would. The pending handoff survives the app process dying during onboarding (setup sends the walker into system Settings), so an interrupted setup still completes it on a later launch. It fires only when setup has not yet completed at the first referrer read: on an install that is already set up — someone who installed 1.5.x from the honor page and later updates — the first read marks the referrer consumed and opens nothing. An absent or invalid referrer does nothing, and the handoff never fires again.
- R20. Debug builds can walk a Way at its recorded pace without walking it — a GPX export for emulator playback and an on-device playback for the test phone — so every device scenario runs from a desk first. None of it ships in release builds.

**Delivery, verification, release**

- R21. Delivery: Stage 21-0; then Stage 21-1, opening with the own-walk vertical — a Way walked in a pocket with the ghost line, the companion, a voice heard through a screen-off app switch alongside a live guide and soundscape (including a guide prompt that starts mid-voice), arrival, and a UI kill that still lands the Way link — proven on device. Right after the vertical proves the pattern, a named unit moves Seek onto the same process mechanism as the Honor engine so Seek meets R16's pocket bar; then the rest of 21-1 (shared walks); then 21-2, 21-3, and the gate. Everything merges to main. One release flag keeps Honor invisible — the picker keeps the Together slot's "coming soon" state, "walk this again" is absent, no Ways or Maps rows appear, and the install referrer is not read — until the gate passes and the flag flips for 2.0.0. No build before 2.0.0 claims honor links: the App Links filter is declared only in debug builds and the flag-on 2.0.0 build, so a tap keeps opening the honor page until then. Stage 21-0 is unflagged and ships as its own 1.5.x release, after a device pass over every map screen, before Stage 21-1 merges to main — so the Mapbox jump and camera changes soak with real users, and later hotfixes carry only flag-dark code.
- R22. Every stage gets `/ios-parity port` specs with Swift quotes pinned to `7c200bf` before implementation. iOS's tests port alongside as each unit's parity tests; iOS's fixtures are reused verbatim (the pilgrimage index, route, and stage fixtures; the tour manifest; the share-payload key-absence goldens); platform-object builder Robolectric tests follow house rules. The engine also gets golden traces: a committed corpus of Ways and GPS traces run through iOS's engine at the pin (the Thought Threads capture-harness precedent), asserted event-for-event on Android — anchor, progress, moment fires and drops, soft tap, water ahead, arrival. Before the traces are captured, planning pins which distance function each of iOS's `CLLocation.distance` call sites ports to (moment radii, the voice drop, arrival), measures its divergence from iOS over the golden corpus, and sets the assertion tolerance at threshold crossings — annotated allowances, as the Thought Threads goldens have.
- R23. Device milestones on the OnePlus 13, one per stage, each with a QA doc in the Phase 19/20 pattern: the vertical's pocket pass (screen off for 10+ minutes, an app switch, a UI kill); a Seek pocket pass with the same UI kill once Seek's unit lands; shared walks in both directions (an iOS share honored on Android; an Android share honored on iOS with exact voice placement); a simulated stage walked in airplane mode with and without saved maps; App Links verification on a clean install; a check that Way media and saved maps stay out of a device-to-device transfer; and the referrer handoff from a Play-track install.
- R24. Parity gate before the flag flips: a matrix over every surface this release touches — the delta, the known gaps, and accessibility across Honor's new surfaces — with verdicts of match, close-the-gap, or a dated re-justify. Two UI-process-only surfaces get pocket-bar rows. Seek fails R16's UI-reclaim clause today by design (the Phase 14 topology keeps its engine and session in the UI process, so a UI death ends guidance); its row verifies the Stage 21-1 unit (R21). The voice guide is equally UI-process-only; its row is adjudicated at the gate as close-the-gap or a dated re-justify. PASS requires zero open close-the-gap rows and zero undated re-justify rows.
- R25. Release: Android 2.0.0 through the normal production dispatch with a staged rollout. The Play listing (description, what's new, screenshots) is updated for Honor, following iOS's 2.0.0 listing copy with platform-correct substitutions (no Live Activity, WhisperKit, WeatherKit, or Apple Photos wording). Play Data Safety is re-verified as unchanged. The R2 re-diff runs when the gate starts; anything iOS merges after that rides Android 2.0.1.

---

## Acceptance Examples

- AE1. **Covers R12, R16.** Given an own walk with three recordings, when the walker begins "walk this again", locks the phone, switches to another app for 15 minutes, and Android reclaims the UI process, each voice still plays at its spot and arrival still fires; reopening the app shows the finished walk's summary with the ghost line and "in their steps".
- AE2. **Covers R12.** Given a loop Way whose first and last points coincide, when the walker begins at the start, arrival does not fire at Begin; it fires only once the progress gates are met near the end.
- AE3. **Covers R11.** Given an accepted share whose expiry passes: if it was never walked, it leaves the Ways list with its media; if it was walked, its summary keeps the ghost line and replies while its place cards read "voices returned to the trail". Own-walk Ways are never swept.
- AE4. **Covers R18.** Given verified App Links, a honor link tapped during onboarding is held and opens the Honor overview once setup completes; tapped during an active walk it shows "finish this walk first" and is dropped; otherwise it opens the Honor overview, fetching. A `walk.pilgrimapp.org` link opens the browser when tapped but imports when pasted.
- AE5. **Covers R19.** Given a phone without Pilgrim, when the walker opens a honor page, installs from its Play button, and completes setup, the overview for that share opens, fetching — even if the app was killed while setup sat in system Settings; no later launch reopens it, an install from a plain Play search opens nothing, and someone who installed 1.5.x from the honor page and then updates to 2.0.0 sees nothing open.
- AE6. **Covers R8.** Given an iOS 2.0.0 `.pilgrim` export containing an honor walk, when it is imported into Android and re-exported, the Honor mode and arrival events are present under their iOS names.
- AE7. **Covers R7.** Given an interactive share whose third recording starts inside the doorstep-trim window, the uploaded payload carries coordinates for the other recordings and omits the coordinate key — rather than sending null — for that one.
- AE8. **Covers R13, R16.** Given a stage begun more than 60 m from its line with nothing to anchor on, the walk records normally with no cards, captions, arrival, or ledger entry. Given a stage anchored midway and ended early, the ledger offers "continue from where you stopped" — including when the UI process died before the walk ended.
- AE9. **Covers R14.** Given a stage with nine on-way water marks, the first announces within 300 m ahead; marks passed within the next hour of walking stay silent pins; a fountain 250 m off the line never announces.
- AE10. **Covers R15.** Given maps saved for all 33 stages, when an Update redraws stage 12 and shortens the route to 30 stages, the maps for stages 30–32 are removed, nothing downloads, the route page reads "Save maps for the way · 29 of 30 saved", and stage 12's morning card says there are no offline maps for today.
- AE11. **Covers R17, R9.** Given a guide prompt playing when the walker reaches a voice's spot, the voice waits and starts when the prompt ends; given a guide prompt that starts mid-voice, the voice pauses and resumes where it stopped after the prompt; given a Way voice playing when a whisper comes into range, the whisper waits until the voice finishes; given a guide prompt playing, a whisper in range waits too; and the soundscape sits ducked under the voice and restores after. The whisper and soundscape clauses hold after the UI process has been reclaimed as well; the guide clauses hold while the UI process is alive (the guide's own pocket-bar verdict is R24's).
- AE12. **Covers R21, R8.** Given the flag off, a release build from main shows Together's "coming soon", offers no "walk this again", never claims honor links (a tap still opens the honor page, as today), never reads the install referrer, and shows no Ways or Maps rows — while an imported iOS honor walk still keeps its Honor events on re-export.
- AE13. **Covers R5, R2.** Given the port finds the stage lock-screen glance reads "their way, walked", Android ships the same string and a `pilgrim-ios` issue is filed; if iOS fixes it before the gate starts, the fix folds in without reopening this doc; if the fix lands after, it rides Android 2.0.1.
- AE14. **Covers R21, R24.** Given a seek walk with the phone pocketed after Seek's Stage 21-1 unit lands, when the walker switches apps and Android reclaims the UI process, the sonar and haptics keep arriving and a reached clearing still records; the gate's Seek row verifies this rather than discovering the gap.

---

## Success Criteria

- Android 2.0.0 ships Honor at exact parity with `7c200bf` plus any folded-in deltas, with the parity gate's PASS recorded and every divergence carrying a dated reason.
- Cross-platform works both ways: an iOS share walks on Android and an Android share walks on iOS with voices at their true spots, and iOS 2.0.0 `.pilgrim` exports round-trip on Android without losing Honor.
- In the OnePlus pocket pass, Honor behaves as it does on iOS — voices, cards, captions, arrival — through screen-off, app switches, and a UI reclaim, and the walk lands linked to its Way; Seek keeps guiding through the same pass.
- `CLAUDE.md` and the `ios-parity` skill name `7c200bf` and the new versioning rule; nothing still points at `33b0dbc` as current.
- Planning can start Stage 21-0 and Stage 21-1 from this doc plus the iOS specs and plans without inventing scope, sequencing, or acceptance criteria.

---

## Scope Boundaries

- iOS's slice four — practices at places, the credencial keepsake, a collective honor counter, the share-lineage field with call-and-response and "walked by", voices of the Way — unbuilt on iOS too.
- Everything iOS deferred inside the three slices: a rolling next-few-stages map window, a wifi-only mode, automatic refresh of expired regions, offline whispers and cairns, the saved corridor drawn on the route page, saving maps automatically, walkable route variants, reverse-direction stages, background catalog refresh, tappable service marks, elevation profiles, a companion dot for stages, route cover images.
- Android extras not chosen: an "Open by default" settings nudge, map downloads that continue with the app closed, persisting a tapped link across a process death during onboarding (iOS holds it in memory only).
- Android-original fixes of iOS 2.0.0 defects — upstream first instead (R5).
- A whole-app parity sweep beyond the surfaces this release touches.
- App Links for `walk.pilgrimapp.org`.
- Populating the shared-walk transcript: it is parsed for parity, but no client sends it, and changing that is iOS's and the worker's call.
- iOS-only machinery: the CombineExt patch, the test-host fix, pbxproj/entitlements/Podfile/widget churn, the screenshot seeder's demo honor walk, the debug offline-maps toggle.

---

## Key Decisions

- Parity itself is the driver; exact parity with iOS 2.0.0 is the bar for Android 2.0.0.
- Android 2.0.0 matches iOS's version, and the match becomes the versioning rule going forward (R3), not a one-off.
- Reach is the delta, the known gaps (the four filed issues plus whisper-over-guide), and a gate over touched surfaces. Delta-only would ship known gaps under an "exact" claim; a whole-app sweep is the heaviest option with no evidence of drift beyond the touched surfaces.
- Upstream first for iOS defects: the platforms converge through iOS fixes and fold-ins, and Android never diverges on its own (precedent: the port raised #76–#78, iOS fixed them in PR #79).
- iOS's pocket bar for Honor, rejecting Seek's UI-only topology for it: Honor's value is voices heard with the phone in a pocket, and whispers already prove the tracking-process pattern. Seek moves onto the same mechanism in Stage 21-1, because the gate holds it to the same bar and it fails today by design; building one mechanism for both beats discovering Seek's gap at the gate. Planning picks the mechanism.
- Two deliberate Android additions, each dated at the gate: the install-referrer handoff (the worker already sends the referrer, and it closes the fresh-install gap on the platform that can) and screen-off haptics (iOS drops haptics in the background; Android Seek already vibrates in the pocket by design).
- Execution is slices to main behind a release flag, opening with a proving vertical. The Android-only risks (process topology, cross-process audio, Mapbox layering) are retired before ~10K lines depend on them. Stage 21-0 ships on its own first, so main stays shippable for any 1.5.x hotfix and no release before 2.0.0 claims honor links.
- Mapbox Android moves to iOS's engine line in Stage 21-0 — 11.20 or later, and before 11.24's one-tile-store-per-process change; planning pins the exact version. Offline maps then port 1:1 onto the engine iOS measured (the pack-format and tile-store eviction fixes landed after 11.11), and every map screen gets soak time before Honor builds on it.
- Verification leans on executable parity — ported iOS tests, reused fixtures, golden engine traces — so "exact" is checked rather than read.

---

## Dependencies / Assumptions

- **pilgrim-worker:** the Android verification file on `honor.pilgrimapp.org` is missing today (404). It needs the Play app-signing SHA-256 (from Play Console), the upload key, and a debug entry — a worker PR and a manual deploy — confirmed through Google's Digital Asset Links API before release, and it must be live before users install 2.0.0, since Android verifies at install and update. Stage 21-0 also carries the interim Android copy on the honor page (R18), another manual deploy. Already live: the honor host and its fallback page (with an Android Play button carrying `referrer=honor={id}`), and tour.json's recording coordinates, sitting durations, and transcript field — present only on shares created since 2026-09-03.
- **open-pilgrimages:** the `@main` index names v1.12.0 today, with 8 packaged routes. Route ids have been renamed twice without redirects, so none may be hard-coded. The index is served with a 7-day HTTP max-age that must not defeat the app's own 24 h cache. The data is ODbL, attributed to "© OpenStreetMap contributors".
- **Mapbox:** Android's offline APIs mirror iOS's on the same engine (checked against the 11.11 docs); Android 14+ stalls work in a backgrounded app, so saves are foreground-only exactly as on iOS; offline usage is assumed billing-included, as iOS assumes (unverified there too).
- **pilgrim-ios:** a read-only reference. The port files issues there; iOS fixes land at the owner's discretion and fold in by R2.
- **Owner actions:** the Play Console fingerprint lookup, the worker deploy, the OnePlus 13 device milestones, the Play listing, and the release dispatch.
- The install referrer needs Google's install-referrer library, a new dependency (Apache-2.0, GPL-compatible).
- There is no fixed date; R2 absorbs iOS drift during the build, until the gate starts.

---

## Outstanding Questions

### Deferred to Planning

- [Affects R16, R17, R21][Technical] How engine state reaches the map and sheet, and how arbitration reaches the UI-process voice guide — including iOS's synchronous hand-over when a starting guide prompt pauses a Way voice and takes over its duck. The placement itself is settled by R16: the Honor engine, the moment tracker, the Way voice player, and the Way-link and ledger writes must not depend on the UI process being alive — they live with the walk, as the whisper auto-player and soundscape already do in `:tracker` — and Seek's unit (R21) rides the same mechanism.
- [Affects R21][Technical] The release-flag mechanism and its coverage: a runtime half (picker, summary button, settings rows, referrer read) and a build-time half, since the App Links filter must be absent from every flag-off release build.
- [Affects R10][Needs research] Mapbox's terms for covering its logo and attribution with the overview card: iOS covers them and credits Mapbox in About (#88); confirm Android's placement complies.
- [Affects R12][User decision at the gate] Lock-screen visibility of the walk notification's glance: iOS's Live Activity shows it on the lock screen, while Android's walk notification hides its content on a secure lock screen (Seek today). Adjudicate for Seek and Honor together at the gate.
- [Affects R15][Needs research] Whether iOS's 4 MB-per-pack seed holds on Android's tile-pack format, where the tile store lives, and how it is excluded from device-to-device transfer.
- [Affects R11, R13][Technical] The Ways storage shape: Ways are never exported, so Android may mirror iOS's per-Way files or use a separate database, as long as behavior matches — but the store must be excludable from device-to-device transfer (R11), which rules out the main Room database as it stands, since transfer includes it whole.
- [Affects R9][Technical] The blast radius of #219's follow-camera change across the reveal, fit-bounds, and initial-center camera paths.
- [Affects R22][Needs research] The iOS capture harness for golden engine traces — applied locally to the iOS checkout and never committed there, per the Thought Threads precedent.
- [Affects R20][Technical] The on-device playback mechanism and how it passes the location pipeline's accuracy filters.
