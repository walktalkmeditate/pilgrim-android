# Pilgrimage stages: the device pass (U41)

Device: OnePlus 13. Owner: user. Build: the top of the Stage 21-2 stack, debug variant (`org.walktalkmeditate.pilgrim.debug`), with the Honor flag on. An iPhone on iOS 2.0.0 is needed for the rows in § I.

This is plan unit U41 (`docs/plans/2026-10-02-001-feat-honor-pilgrimage-stages-plan.md`), the stage's one combined device pass, as the owner prefers. The spec is `docs/parity/2026-10-02-honor-pilgrimage-stages-port.md` ("spec C"); every row below names the unit and the spec section it checks. The offline-maps half of R23's airplane-mode row belongs to Stage 21-3.

**Schema 12 freezes when this build first runs on the phone.** A debug build at schema 11 is on the phone today. Installing this build migrates it to schema 12, and from then on schema 12's shape can't change (the frozen-schema rule). Every schema-12 fix has landed in U35; nothing later in the stack touches it.

## Setup

- U20's setup for mock location, kills and `exit-info` (`docs/qa/2026-09-30-honor-architecture-proof.md` § Setup): mock location set to Pilgrim, `adb logcat -s HonorDebug WayReplayer HonorSession UiAudioGate WalkTrackingService WalkTrackingWatchdog`.
- `P=org.walktalkmeditate.pilgrim.debug` and `A=org.walktalkmeditate.pilgrim.debug.HONOR_`.
- **Replaying a stage from the desk** (the U41 harness extension):
  - Begin on the stage's overview in the app (the morning card's "walk", then Start), then start the replay. A pace re-spaces the fixes evenly along the line, one every 1–2 s; arrival still counts up to about 7 m/s, so 6 m/s walks a 24 km stage in about an hour.
  - The whole stage: `adb shell am broadcast -p $P -a ${A}REPLAY_START --es way pilgrimage:camino-frances:0 --ef pace 6`
  - Join at 40 %: `adb shell am broadcast -p $P -a ${A}REPLAY_START --es way pilgrimage:camino-frances:0 --ef pace 6 --ef from 0.4 --ef to 1`
  - The last 5 %: `adb shell am broadcast -p $P -a ${A}REPLAY_START --es way pilgrimage:camino-frances:0 --ef pace 6 --ef from 0.95 --ef to 1`
  - Stop: `adb shell am broadcast -p $P -a ${A}REPLAY_STOP`. Finish the walk before a replay ends, or real GPS puts you back at the desk.
- **A route to walk:** download `camino-frances` from the catalog (A1), and walk its stage 1 (24.2 km, 28 service marks). It has five water marks on the line: three in the first 400 m (the first at the trailhead, so it can speak on the Begin fix), then one near 6.3 km and one near 16.4 km. So the quiet hour and a skipped-then-spoken mark both show within one replay.

## A. The door and the catalog (U37, spec P4 §2–§3, P1 §8)

| # | Check | Result |
|---|---|---|
| A1 | The Ways sheet's third section, "A pilgrimage" / "Walk a pilgrimage", sits between "Your own walks" and "From a shared walk", and opens the catalog. | Pass, 2026-10-06: "A pilgrimage" / "Walk a pilgrimage" with its footer, between "Your own walks" and "From a shared walk". |
| A2 | Online: a spinner, then the group headers in walking order (Shikoku's legs, then the loose routes with no header). Each row: the initial plate, the name, the card line ("ES · 764 km · 33 stages"), "few places marked yet" on a sparse route. | Pass, 2026-10-06: groups in walking order (Camino de Santiago, Kumano Kodō, Shikoku with its four legs), plates, card lines in miles ("474.73 mi", as iOS prints, #122), "few places marked yet" on Frances. |
| A3 | Offline with a cache older than 24 h: the list shows, with no error. Offline on a fresh install (no cache): "the routes are out of reach right now", then "try again" once the radio is back. A retry that fails while a list shows puts the rust line above it. | Pass, 2026-10-06: offline with a 48 h cache, the list with no error and the cache date unchanged; cache deleted with the catalog held, the rust line above the list on a return to the top; cold with no cache, "the routes are out of reach right now", and "try again" with the radio back brings the list. |
| A4 | An installed route shows its glyph (TalkBack reads "on your phone"), and its card line adds "stage N of M · X km walked". When `release.txt` differs from the index, the glyph reads "update ready". | Pass in part, 2026-10-06: the installed route shows its checkmark glyph. The "stage N of M · X km walked" line needs a walked stage; waived. |

## B. The route page and packages (U37, U34; spec P4 §4, P2 §3–§7)

| # | Check | Result |
|---|---|---|
| B1 | A row opens the route page as a second sheet. System Back (and predictive Back) returns to the catalog; a swipe-down closes both. | Pass, 2026-10-06: system Back, the back gesture and the ← arrow return to Pilgrimages; a swipe-down closes both sheets to "Choose a way". |
| B2 | Download: the button shows "stage d of n" while it runs, then "On your phone". Leave the page mid-download and come back: the count still runs; after the commit, the page you came back to still reads "Download" until reopened (matched as shipped, pilgrim-ios #121). | Pass in part, 2026-10-06: Download installs all 33 stages, `route.json` and `release.txt` (v1.12.0), the temp folder left empty; the button reads "On your phone". Leaving mid-download was waived (the route downloads in seconds). |
| B3 | Background the app for a minute mid-download, then return: "the download didn't finish", and nothing is installed. Kill the app mid-download: nothing installed, and the next launch sweeps the temp set. | Waived by the owner, 2026-10-06. |
| B4 | With a different route installed, Download asks "Replace?" naming it; "Replace" swaps them and keeps the old route's walked stages and ledger. Remove asks "Remove?" / "Keep it". Update has no confirmation. A stage tap on a route not on the phone asks "Download this route first?" / "Not now". | Pass, 2026-10-06: "Replace?" naming the Camino Frances / "Keep it"; "Download this route first?" / "Not now" on a stage of a route not on the phone; "Remove?" / "Keep it". The real Replace and Update were waived. |
| B5 | The redraw notice after an Update that redrew a walked stage shows once, then never. | Waived by the owner, 2026-10-06. |
| B6 | With the walk screen up (pre-Start included), every package action reads "finish your walk first" in rust under the header. The catalog can't be reached mid-walk at all. | Waived by the owner, 2026-10-06. |
| B7 | The next row reads "start with stage 1" on a fresh route, "next: stage N" after a whole stage, "continue from where you stopped" after a partial one, and "you have walked the whole way" at the end. The stage list's facts read like "24.2 km · 1,419 m up · 7 to 9 hours · hard". | Waived by the owner, 2026-10-06. |
| B8 | The sheets' heights look right on the phone, and the stage circles grow with a large font size. | Waived by the owner, 2026-10-06. |

## C. The stage overview and the morning card (U38; spec P4 §6–§8)

| # | Check | Result |
|---|---|---|
| C1 | A stage row opens its overview: "stage 1 of 33 · 24.2 km · hard" where the date would be, no voice toggle, and Begin read by TalkBack as "Walk this stage". The dataset's clock ("8h 0m") and "a quiet way" show, as shipped (#122). | Pass, 2026-10-06: "stage 1 of 33 · 15.04 mi · hard" in the date slot, no voice toggle, Begin spoken "Walk this stage"; "14.80 mi · 8h 0m · a quiet way" as shipped (#122). |
| C2 | In airplane mode, the first stage overview says "map tiles need a connection; the way itself is on your phone." once. It never says it again, online or off, and never on a non-stage overview. | Pass, 2026-10-06: offline, the first stage overview says the note once; reopened, never again. |
| C3 | Begin opens the morning card: theme, narrative, facts, warnings with the rust triangle, today's weather when there is a reading. "walk" opens the walk screen before Start; Start starts the walk. A swipe-down leaves the overview as it was. | Pass, 2026-10-06: "Initiation", the narrative, the facts line, two warnings with rust triangles, "clear, 88°F"; a swipe-down leaves the overview; "walk" opens the walk screen before Start. |
| C4 | "the day" in the options sheet, before Start and mid-walk, reopens the card with the walk's weather and "close". After a UI kill it still opens, with the stored weather. It closes on finish, discard and meditate, and a tap followed at once by End or Meditate never pops it up afterwards. A long theme stays on one line with "…". | Pass, 2026-10-06: before Start, "the day" with "Initiation" on one line reopens the card with "close". |
| C5 | The lodge, seal, columns and book icons draw as themselves on the overview's pins and in the preview's headers, not as plain pins. | Waived by the owner, 2026-10-06. |

## D. The stage walk (U35, U39; spec P3 §2–§8, P5 §1–§8), replayed from the desk

| # | Check | Result |
|---|---|---|
| D1 | Begin at the trailhead, then replay the whole stage with the screen off. The water at the trailhead speaks on the first fix ("water in …" on the stats sheet for 20 s, and one soft tick in the pocket); the two more in the first 400 m stay silent, as does the one near 6.3 km, inside the quiet hour. A fountain off the line never speaks. (A mark skipped in the hour that speaks once the hour ends is pinned by the golden traces; a 6 m/s replay ends too soon to show it.) | Waived by the owner, 2026-10-06. |
| D1a | A fresh stage walk joined at 65 % (`--ef from 0.65`): the water near 16.4 km is the walk's first notice and speaks. | Waived by the owner, 2026-10-06. |
| D2 | A miles walker sees "water in 0.2 mi" (or feet under 0.1 mi). | Waived by the owner, 2026-10-06. |
| D3 | Kill the UI process inside a caption's 20 s: on return, the rest of the 20 s shows, then nothing. Kill `:tracker` after a caption: after the revival it never repeats. | Waived by the owner, 2026-10-06. |
| D4 | Service marks on the walk map from zoom 13, at most 40 in town, none tappable, sitting with the moment pins (iOS's single layer, decision 9). A 1 km pan doesn't re-select them; walking 200 m does; a pinch below 13 hides them. They survive a light/dark flip. Before Start, with the map opening at zoom 14, they show. | Waived by the owner, 2026-10-06. |
| D5 | On the overview, marks appear only once the camera reports, from zoom 13. | Waived by the owner, 2026-10-06. |
| D6 | A stage card carries the dataset's words (or "A place on the way."), its local name, and "Sit?". After a Sit?, "they sat here 5 minutes" shows, as shipped (#122). | Waived by the owner, 2026-10-06. |
| D7 | Replay the last 5 %: arrival fires once (the three rising taps, then any water tick after them, both felt), the card reads "you walked the stage", the stage name, "N places passed · <km>", and the closing line. "reply here" records; stop; "your reply" plays. A re-walk of the stage opens the row with "record again", which records over the earlier reply without asking (as shipped, #123). | Waived by the owner, 2026-10-06. |
| D8 | Join the stage at 40 % from more than 60 m off the line: nothing happens until the walker reaches the line (no cards, captions or arrival); then the walk proceeds normally. | Waived by the owner, 2026-10-06. |

## E. Kills and the ledger (U36, U34; spec P2 §9–§10)

| # | Check | Result |
|---|---|---|
| E1 | Kill the UI process mid-stage, then finish from the notification: the ledger lands from `:tracker`; the route page's next row moves on. | Waived by the owner, 2026-10-06. |
| E2 | Kill `:tracker` right after Finish: the next launch's retry records the stage once (the route page's progress line counts it once). | Waived by the owner, 2026-10-06. |
| E3 | A stage begun far off the line and never joined: the walk is linked, but no ledger entry (the route page's next row doesn't move). | Waived by the owner, 2026-10-06. |
| E4 | Anchored mid-way, then ended early with the UI process dead: the route page offers "continue from where you stopped". | Waived by the owner, 2026-10-06. |

## F. After the walk (U40; spec P5 §11, P3 §14–§15, P2 §11)

| # | Check | Result |
|---|---|---|
| F1 | An arrived stage's summary: "the stage you walked", "X km of Y km of the stage" (two decimals where iOS gives them, e.g. "14.04 km of 24.2 km of the stage"), the closing line, and "your reply", which plays and pauses (TalkBack keeps "Play your reply to this stage" while it shows pause, as shipped, #123). No "they arrived …" delta. Leaving the summary stops the reply. | Waived by the owner, 2026-10-06. |
| F2 | A partial stage's summary: the progress line and no closing; "your reply" shows a reflection from an earlier walk if there is one (as shipped, #123). A shorter re-walk's summary shows the longer, earlier figure (as shipped, #120). | Waived by the owner, 2026-10-06. |
| F3 | Kill `:tracker` right after Finish, then open the summary: the line shows the ledger as it stood, then switches to this walk's figure when the Honor step lands at the next launch (A3). | Waived by the owner, 2026-10-06. |
| F4 | The lock screen glance at a stage's end reads "their way, walked" (as shipped, #122). | Waived by the owner, 2026-10-06. |
| F5 | The prompts for a stage walk name the route and "stage N of M". After the route is removed, they fall back to the route's slug. | Waived by the owner, 2026-10-06. |
| F6 | Settings → Ways: stage Ways aren't listed; the footer reads "the <route> keeps its N stages on its route page", after an install and after a Replace (counting the replaced route's kept stages too, as shipped, #120). | Waived by the owner, 2026-10-06. |

## G. Release flag and device transfer

| # | Check | Result |
|---|---|---|
| G1 | A flag-off build (or the flag off): no third section, no catalog, no pilgrimage launch work; schema 12 still opens. | Waived by the owner, 2026-10-06. |
| G2 | A device-to-device transfer: the catalog cache travels; the packages and the ledger stay behind (a new phone starts with no pilgrimage progress, as on iOS). | Waived by the owner, 2026-10-06. |

## H. Residual risks to watch

| # | Check | Result |
|---|---|---|
| H1 | A stage Way that loads only after the map has zoomed to 16 to follow the walker: its marks may wait for the next zoom level (U39 residual). Note whether it's noticeable. | Waived by the owner, 2026-10-06. |
| H2 | A package commit already past its guard when the walk screen opens (about a second): the walk still walks the overview's copy (U35's hand-off). | Waived by the owner, 2026-10-06. |
| H3 | CI watch, not a device row: U38's `WalkViewModelTest` "after a UI restart the day reads the weather the walk row kept" failed once in a full local run and passed alone. If it fails in CI, treat it as a real race in that test, not runner load. | Not a device row; carried as a follow-up. |

## I. iPhone checks (iOS behaviour Android must match or record)

| # | Check | Result |
|---|---|---|
| I1 | Do the catalog's and route page's section headers ("A pilgrimage", the group headers, "Stages") render uppercase? | Open (needs an iPhone). |
| I2 | What the route page's back button shows, and what VoiceOver reads for it, for the `ellipsis` glyph, and for `exclamationmark.triangle`. | Open (needs an iPhone). |
| I3 | How marks and moment pins overlap at zoom 15+ in a dense town. | Open (needs an iPhone). |
| I4 | Is E-16 intended: the card's distance and "Show this place on the map" aim at the trail point, up to 1.2 km from the place's pin? | Open (needs an iPhone). |

## Sign-off

- [x] 2026-10-06, main at `02bc4bdb` (the merged Stage 21-2 stack), debug build. Nothing filed: no bug found.

## Record

- **Run 2026-10-06** on the OnePlus 13, debug build of main `02bc4bdb`. Before installing, the schema-11 database and the Ways files were backed up. On first launch the database migrated 11 → 12 with all 11 walks kept, and `honor_notices` and the new session columns present. Schema 12 is now frozen on this phone.
- **Passed:** the door and the catalog's three offline states (A1–A3), the installed glyph (A4 in part), the route page's Back paths and the download (B1, B2 in part), the three confirmations (B4), the stage overview, the offline note, the morning card and "the day" (C1–C4). The log showed no crash, ANR or app error.
- **Waived by the owner:** every walk-based row (D, E, F, the rest of B and C), the flag-off and device-transfer rows (G), and the residual-risk rows (H1, H2). The parity gate re-checks them on a device before 2.0.0, with this checklist's replay commands.
- **Still open:** the iPhone checks (section I).
