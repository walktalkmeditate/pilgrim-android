# Offline maps: the device pass (U48)

Device: OnePlus 13. Owner: user. Build: the top of the Stage 21-3 stack, debug variant (`org.walktalkmeditate.pilgrim.debug`), with the Honor flag on. An iPhone on iOS 2.0.0 is needed for the rows in § I.

This is plan unit U48 (`docs/plans/2026-10-06-001-feat-honor-offline-maps-plan.md`), the stage's one combined device pass, as the owner prefers. The spec is `docs/parity/2026-10-06-honor-offline-maps-port.md` ("spec D"); every row below names the unit and the spec section it checks. It also carries the offline-maps half of R23's airplane-mode row, which Stage 21-2's pass (`docs/qa/2026-10-04-honor-pilgrimage-stages-qa.md`) left to this stage.

**No schema change.** This stage adds no Room state; schema 12 stays as it is on the phone.

**Downloads are real.** A full Camino Francés save is about 250 MB. Use wifi for the Francés rows, and keep cellular for the one cellular row on the small Nakahechi.

## Setup

- `P=org.walktalkmeditate.pilgrim.debug` and `A=org.walktalkmeditate.pilgrim.debug.HONOR_`.
- Logs: `adb logcat -s HonorDebug HonorFinalizer PilgrimageTiles WayReplayer HonorSession WalkTrackingService`.
- **Clear Mapbox's two caches** (the map's disk cache and the tile store's ambient cache) so a stage that renders offline is proved to come from its saved region, not from tiles seen earlier online. Run it before every airplane-mode row. It's refused while a save runs:
  `adb shell am broadcast -p $P -a ${A}TILES_CLEAR_CACHE`
- **The tiles report** logs each saved region (id, complete, counts, bytes, hash), the style packs, and the summed bytes:
  `adb shell am broadcast -p $P -a ${A}TILES_REPORT`
- **On-disk sizes:** `adb shell run-as $P du -sk files/.mapbox/tile_store files/.mapbox/map_data`
- **Replaying a stage from the desk:** Stage 21-2's harness (`docs/qa/2026-10-04-honor-pilgrimage-stages-qa.md` § Setup): Begin on the overview, Start, then `adb shell am broadcast -p $P -a ${A}REPLAY_START --es way pilgrimage:<route>:<index> --ef pace 6`; stop with `${A}REPLAY_STOP`.
- **Routes:** `kumano-kodo-nakahechi` (small: the seed estimate is about "~12 MB", 3 packs) and `camino-frances` (long: about "~248 MB", 62 packs). One route is installed at a time; Replace swaps them.

## A. The store and the backup rule (U45; spec C3 §2, §10)

| # | Check | Result |
|---|---|---|
| A1 | After a first save: `adb shell run-as $P ls -la files/.mapbox/` shows `tile_store/` and `map_data/`, and nothing Mapbox-related sits elsewhere under `files/` or `no_backup/`. | Pass, 2026-10-13: before any save `files/.mapbox/` held `tile_store/` (462 KB, ambient) and `map_data/` (7.3 MB); after the Nakahechi save `tile_store` was 17.5 MB. Nothing Mapbox-related elsewhere under `files/` or `no_backup/`. |
| A2 | Device transfer leaves `.mapbox/` behind (owner decision 7). One-phone check, if the local transport takes device-transfer mode on this phone: `adb shell bmgr transport com.android.localtransport/.LocalTransport`; `adb shell settings put secure backup_local_transport_parameters is_device_transfer=true`; `adb shell bmgr backupnow $P`; clear the app's data; `adb shell bmgr restore $P`; then `run-as … ls -la files/`: the other files are back and `.mapbox/` isn't. Reset the transport and the setting afterwards. If the transport refuses device-transfer mode, waive this row; the rules test is then the proof, as Stage 21-2's G2 was. | Waived, 2026-10-13 (owner: "everything is good for release"). The rules test (`WaysBackupRulesTest`) is the proof, as Stage 21-2's G2 was. |

## B. Sizing (U43, U44, U46; spec C1 §8–§10, C3 §14.4, defect D2)

| # | Check | Result |
|---|---|---|
| B1 | **The Nakahechi.** Clear the caches. Before the tap, the row reads "Save maps for the way · ~N MB": record N. Save. Afterwards record the row's "maps saved · N MB", the tiles report's summed region bytes and summed pack bytes, and `du -sk` of `tile_store`. | Pass, 2026-10-13: before the tap "~12 MB" (3 packs × the 4 MB seed); after it "maps saved · 18 MB". Tiles report: 4 regions complete, summed 18,345,945 bytes; packs light 5,213,174 + dark 5,213,461 = 10,426,635 bytes; `tile_store` 17,538,868 bytes on disk. |
| B2 | **The Francés:** the same four figures. | Estimate only, 2026-10-13: "Save maps for the way · ~248 MB" (62 packs × 4 MB). The ~250 MB save itself was waived. |
| B3 | **D2's verdict:** if the summed region bytes clearly exceed (`tile_store` size − pack bytes), shared packs are double-counted. File D2 upstream with these figures; if they match within a few percent, record it refuted. | **D2 confirmed**, 2026-10-13: summed region bytes 18.35 MB against (`tile_store` 17.54 MB − packs 10.43 MB) = 7.11 MB of tiles on disk, about 2.6×, so packs shared between stages are counted once per stage. "18 MB" lands near the store's total only because the style packs it leaves out fill the gap. Filed upstream on pilgrim-ios #124. |
| B4 | **Calibration:** after "Delete maps" (F3), the Nakahechi's row reads a new "~N MB" from the route's measured bytes per pack, not the seed's. | Calibration written, 2026-10-13: 6,115,315 bytes per pack for the Nakahechi (the inflated figure D2 predicts; about 2.4 MB is real). The re-shown estimate after Delete wasn't read; waived. |

## C. The route page's row (U46, U44; spec C4 §1, C2 §5–§7)

| # | Check | Result |
|---|---|---|
| C1 | **Every face, with TalkBack on:** the estimate before a first save; "maps · stage d of n" with "cancel" while saving; "maps saved · N MB" with the moss check (TalkBack: "maps saved, N MB. Tap to save again"); "Save maps for the way · s of m saved" after a cancel. | Pass in part, 2026-10-13: the estimate face, "maps · stage 0 of 4  cancel" while the packs loaded, and "✓ maps saved · 18 MB" seen. TalkBack on each face waived. |
| C2 | **Cancel and resume:** cancel mid-save; the stages done stay ("s of m saved"); a re-tap resumes at the first gap (progress starts past the saved stages). | Waived, 2026-10-13 (owner: "everything is good for release"). |
| C3 | **A walk started mid-save:** start a save, Begin a walk. The save stops at its next step; the regions already done stay; afterwards the route page reads "finish your walk first" in rust until the next save, a cancel or a Remove, on any route page (C4-1, matched). | Waived, 2026-10-13 (owner: "everything is good for release"). |
| C4 | **Lock the phone mid-save** for a minute, then unlock: record whether it carried on, ended "the download didn't finish", or sits in "saving" (recovery: cancel, then a re-tap). | Waived, 2026-10-13 (owner: "everything is good for release"). |
| C5 | **Background the app mid-save** (home, then another app) for over a minute during the Francés save, then return: record the same three outcomes. | Waived, 2026-10-13 (owner: "everything is good for release"). |
| C6 | **Cellular:** with wifi off, a Nakahechi save runs (no network restriction); the morning card still says "save on wifi" (D9, intended copy). | Pass, 2026-10-13: the Nakahechi save ran with wifi off (no network restriction). |
| C7 | **A fully current re-save:** tap "maps saved · N MB"; nothing downloads. Record whether "maps · stage k of n" flashes for a frame (A8, Android only) and whether anything else changes (D7, matched). | Waived, 2026-10-13 (owner: "everything is good for release"). |
| C8 | **The row is held** while a package download or Update runs, and live during its own save (its "cancel" works). The download button and Remove are held while a save runs, on the installed route's page and on any other route's page. | Waived, 2026-10-13 (owner: "everything is good for release"). |
| C9 | **Right after a UI kill** (`adb shell am kill $P` with the app in the background, then reopen the route page): no "~N MB" flash on a saved route; the row appears once the store has answered. | Waived, 2026-10-13 (owner: "everything is good for release"). |
| C10 | **The cold-start packs race:** open the route page right after several cold starts. Any "Save maps for the way · n of n saved" with both packs on the phone is D4's race, matched; record whether it shows. | Waived, 2026-10-13 (owner: "everything is good for release"). |
| C11 | **The saved face's tap target** is its content's height, as on iOS; note whether it's comfortable to hit. | Waived, 2026-10-13 (owner: "everything is good for release"). |
| C12 | **A route page opened mid-download** (#121 item 5, matched): after a first download the row stays hidden until the page is reopened; after an Update a page opened during it reads the old counts. | Waived, 2026-10-13 (owner: "everything is good for release"). |

## D. Airplane mode (R23; U45, U47; spec C3 §3, §15)

Clear the caches first, every time.

| # | Check | Result |
|---|---|---|
| D1 | **With saved maps:** in airplane mode, the stage overview's map renders the basemap along the stage, in light and in dark (each style has its own pack). | Pass, 2026-10-13: airplane mode on, caches cleared first. The Nakahechi stage 1 overview drew the full basemap from the saved maps (roads, rivers, "NAKAHECHICHO KAWAI", "Mt. Iimori", "TERANOKUCHI") in dark and in light. |
| D2 | **Replay the stage in airplane mode** from the desk: the walk map follows the walker with the basemap drawn at z16 (overzoomed from z14), and shows a bare line below z11. | Pass, 2026-10-13, pre-Start rather than a replay: in airplane mode the walk map drew the saved corridor and left everything outside it blank ("everywhere else is white except the saved map area"). Zoomed fully in, roads and the line still drew, overzoomed from z14; zoomed far out, the corridor alone. |
| D3 | **Without saved maps** (after Delete, F3): the same stage in airplane mode draws the line over an empty basemap. | Not run separately; D2 shows the unsaved ground as blank. Waived. |
| D4 | **Online over a saved corridor at z15–16:** note whether the map looks coarser than just off the corridor (11.23.1's overzoom, fixed in 11.29). | Waived, 2026-10-13 (owner: "everything is good for release"). |
| D5 | **The offline note** (D1, matched): the first stage overview opened offline still says "map tiles need a connection; the way itself is on your phone." even with its maps saved. | Not re-checkable on this install: the note shows once ever, and it was spent in Stage 21-2's C2 row (matched as shipped, D1). |

## E. The morning card's line (U47; spec C4 §2)

| # | Check | Result |
|---|---|---|
| E1 | **From the overview:** Begin on a saved stage, and the card's last line reads "maps saved for today"; on an unsaved stage, "no offline maps for today — save on wifi". The line changes live if the maps are deleted while the card is open. | Pass, 2026-10-13: Begin on the saved stage in airplane mode; the morning card ends "maps saved for today" in caption fog after the facts line (no weather line offline). |
| E2 | **"the day"** before Start and mid-walk reads the same line, once per opening. | Pass, 2026-10-13: "the day" before Start reads "maps saved for today". |
| E3 | **Right after a UI kill mid-walk:** "the day" opened at once reads "maps saved for today" on a saved stage. A "the day" sheet left open across the kill shows its line when it comes back. | Waived, 2026-10-13 (owner: "everything is good for release"). |
| E4 | **AE10, if a release with a redraw is available:** after an Update that redraws a saved stage, that stage's card reads "no offline maps for today — save on wifi". Otherwise waive; unit tests cover it. | Waived, 2026-10-13 (owner: "everything is good for release"). |

## F. Settings → Data → Maps (U47; spec C4 §3)

| # | Check | Result |
|---|---|---|
| F1 | **The Data card row** reads "Maps" after "Ways": "none saved" with nothing saved, "<route> · N MB" after a save, and ticks up during a save. With TalkBack, the row reads as a button. | Waived, 2026-10-13 (owner: "everything is good for release"). |
| F2 | **The Maps screen:** title "Maps"; the route name and "N MB · S of T stages" (always "stages", C4-3); "no maps saved" when empty, with no Delete and no link to save. | Waived, 2026-10-13 (owner: "everything is good for release"). |
| F3 | **Delete:** "Delete maps?" / "Removes the saved basemap. The route's stages stay on your phone." "Cancel" keeps the maps; "Delete" removes them and the screen reads "no maps saved". Record `du -sk` before and after (C3-D6: how much stays, as ambient cache and style packs), then run the clear-cache command and `du -sk` again. | Pass, 2026-10-13 (owner): Delete removes the maps. Measured: right after it, 0 regions and `tile_store` still 17.5 MB (nothing freed); the clear-cache command then freed 7,786,064 bytes of ambient cache (the deleted regions' tiles), leaving 10.4 MB, the two style packs, which nothing removes (C3-D6 and D5, matched, #124 item 3). |
| F4 | **Delete during a save** cancels the save (the route page then reads "~N MB" or "s of m saved"). | Waived, 2026-10-13 (owner: "everything is good for release"). |
| F5 | **Mid-walk:** the Maps row is hidden, and an open Maps screen leaves when a walk starts (owner decision 6). | Waived, 2026-10-13 (owner: "everything is good for release"). |
| F6 | **Right after a UI kill,** Settings shows the saved figure, never a flash of "none saved". | Waived, 2026-10-13 (owner: "everything is good for release"). |

## G. Packages take their maps (U45, U44; spec C2 §9–§10)

| # | Check | Result |
|---|---|---|
| G1 | **Remove** the route with maps saved: the tiles report shows no regions for it, and Settings reads "none saved". | Waived, 2026-10-13 (owner: "everything is good for release"). |
| G2 | **Replace** the Nakahechi (maps saved) with the Francés: the Nakahechi's regions go. | Pass in part, 2026-10-13: Replace (Francés → Nakahechi) swapped the routes and kept the Francés's walked stages. No maps were saved at the time, so the taking of maps was waived. |
| G3 | **A kill mid-Replace** (kill the app during the swap's commit), then relaunch: the abandoned route's regions are swept in that same launch, once the store answers (the tiles report). | Waived, 2026-10-13 (owner: "everything is good for release"). |
| G4 | **Update shrinking the route,** if a release is available: the retired stages' regions go and nothing downloads. Otherwise waive; unit tests cover AE10. | Waived, 2026-10-13 (owner: "everything is good for release"). |

## H. Launch and processes (U45; spec C3 §11–§12, C2 §10)

| # | Check | Result |
|---|---|---|
| H1 | **The first answer after a UI kill:** time from reopening to the row's appearance (logcat), against the 5 s bound. | Waived, 2026-10-13 (owner: "everything is good for release"). |
| H2 | **Background starts** with the flag on (a widget refresh, a WorkManager wake) show no Mapbox errors in logcat. | Waived, 2026-10-13 (owner: "everything is good for release"). |
| H3 | **`:tracker` never opens the store:** during a replayed stage walk, no Mapbox tile-store log lines come from the `:tracker` process (`adb shell ps -A | grep $P` for its pid, then filter logcat by it). | Waived, 2026-10-13 (owner: "everything is good for release"). |
| H4 | **A save started right after a cold start,** with no map shown yet: note only, for the gate's #92 row (telemetry is turned off when a map style loads). | Waived, 2026-10-13 (owner: "everything is good for release"). |
| H5 | **The flag off** (a flag-off build): no Maps row, no Maps destination, no route-page row, and no tiles launch work in logcat. | Waived, 2026-10-13 (owner: "everything is good for release"). |

## I. iPhone checks (iOS behaviour Android must match or record)

| # | Check | Result |
|---|---|---|
| I1 | On iOS 2.0.0, with an update ready, does one tap on "Save maps for the way" also start the Update (and one on "Update" also start a save)? Files C4-4 upstream if so. | Open (needs an iPhone). |
| I2 | What VoiceOver reads for the saved row and the hidden check, and whether the held row looks dimmed during an Update. | Open (needs an iPhone). |
| I3 | After "Delete maps" on iOS, how much the app's storage figure drops against the saved figure (C3-D6). | Open (needs an iPhone). |

## Sign-off

Signed off by the owner on 2026-10-13: "we can say everything is good for release". The rows above that ran passed; the rest are waived. As with Stages 21-1 and 21-2, the parity gate re-checks the waived walk-based rows before 2.0.0. iOS PR #91 is re-triaged to Android 2.0.1 by the owner the same day ("we can skip the ios #91 for now").

## Record

- Device: OnePlus 13 (CPH2655). Build: debug, from main `a4fba6bf` (the merged Stage 21-3 stack), Honor flag on. App data backed up before install.
- The phone carried a Camino Francés install from Stage 21-2's pass; it was replaced by the Nakahechi for the save rows.
- Defects measured: D2 confirmed (filed on pilgrim-ios #124); C3-D6 and D5 measured (17.5 MB stays after Delete until the ambient cache is cleared; 10.4 MB of style packs stay for good).
