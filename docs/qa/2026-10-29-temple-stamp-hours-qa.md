# A temple ahead stamps until five: the device pass (U29)

Device: the owner's phone. Owner: user. Build: the top of `feat/temple-stamp-hours`, debug variant (`org.walktalkmeditate.pilgrim.debug`), Honor flag on.

This is plan unit U29 (`docs/plans/2026-10-29-002-feat-temple-stamp-hours-port-plan.md`). The spec is `docs/parity/2026-10-28-stamp-closing-port.md`; each row names the unit it checks.

**Schema 13.** This build's first launch migrates Room 12 → 13 on the phone. From then on schema 13 is frozen: any later change is schema 14 with a migration.

**Run § D first.** It starts from a clean install of the 2.0.0 debug build, and `adb uninstall` wipes the debug app's walks.

## Setup

- `P=org.walktalkmeditate.pilgrim.debug` and `A=org.walktalkmeditate.pilgrim.debug.HONOR_`.
- Logs: `adb logcat -s HonorDebug HonorSession HonorFinalizer WayReplayer WalkTrackingService`.
- Mock location and kills: Phase 21 U20's setup (`docs/qa/2026-09-30-honor-architecture-proof.md` § Setup). `adb shell ps -A -o PID,NAME | grep pilgrim` gives both process ids; kill `:tracker` with `adb shell run-as $P kill -9 <pid>`, the UI with `adb shell 'run-as org.walktalkmeditate.pilgrim.debug kill -9 $(pidof org.walktalkmeditate.pilgrim.debug)'`.
- **The route:** download `shikoku-88-awa` from the catalog ("A pilgrimage"). Its `route.json` states `{"opens":"08:00","closes":"17:00"}`.
- **A stage's temples and their fracs**, and whether its Way carries the hours:
  `adb exec-out run-as $P cat 'no_backup/Ways/pilgrimage:shikoku-88-awa:0/way.json' | python3 -c 'import json,sys; w=json.load(sys.stdin); print("stampHours", w.get("stampHours")); print("km", round(w["totalDistanceMeters"]/1000, 2)); [print(m["id"], round(m["frac"], 4), m.get("text", "")[:12]) for m in w["moments"] if m.get("text", "").startswith("Temple ")]'`
- **A starting frac:** `from = (temple frac) − 3 ÷ (the stage's km)` puts that temple 3 km ahead at the first fix.
- **The walk:** Begin from the stage's page (the morning card's "walk", then Start), then `adb shell am broadcast -p $P -a ${A}REPLAY_START --es way pilgrimage:shikoku-88-awa:0 --ef pace 1.4 --ef from <frac>`. Stop with `${A}REPLAY_STOP` after Finish. A replay dies with a killed `:tracker`; start it again from the progress frac HONOR_DUMP reports.
- **The office's clock is the phone's time zone** (no stage file names one). There is no debug clock, so move the zone and keep the real time, which the downloads need: Settings → System → Date & time → "Set time zone automatically" off, then pick a zone whose local time is the one the row needs (`adb shell date` shows it). Set the zone **before Begin**: the engine reads it when it is built, at Begin and at a `:tracker` revival. Put it back to automatic at the end.
- **State:** `adb shell am broadcast -p $P -a ${A}DUMP` logs `clocks: gap=… water=… temples=…`, `stamp hours: …`, and one `notice STAMP temple-N … m` line per temple spoken.
- **The 24-hour switch:** `adb shell settings put system time_12_24 24` (24-hour) or `12` (12-hour).

## A. Before 15:00 (U24: the window opens at 15:00)

| # | Check | Result |
|---|---|---|
| A1 | Zone set so local time is about 14:50. Begin with a temple 3 km ahead. Until local 15:00: no caption, no tap, and HONOR_DUMP shows no `notice` line and every clock `none`. | — |
| A2 | On the first fix at or after 15:00: "temple N stamps until 5 · x.x km" in the bar's third stat, one soft tap (the water's), gone after 20 s. HONOR_DUMP: one `notice STAMP`, `gap` and `temples` equal, `water=none`. | — |

## B. Between 15:00 and 17:00 (U24, U26)

| # | Check | Result |
|---|---|---|
| B1 | Zone set to about 15:30 local. Each temple speaks once; walking on past it never repeats it. | — |
| B2 | A temple less than 1 km ahead at the moment another would speak is passed over, and the next one is named. | — |
| B3 | Two temples about a kilometre apart (Awa stage 0 from about 15 km: temples 6 and 7): the second speaks no sooner than ten minutes after the first, and before the walker reaches it. Record the two times. | — |
| B4 | Settings → units to miles: the next temple caption reads "· x.x mi". | — |
| B5 | A Camino stage (`camino-frances`) walked at 15:30: no temple caption, no extra tap; its water notices still speak. | — |
| B6 | TalkBack: the caption is read as the "Walk stats" value's third part; no separate announcement. | — |

## C. After 17:00 (U24: the window shuts with the office)

| # | Check | Result |
|---|---|---|
| C1 | Zone set to about 17:05 local. Ten minutes of walking with temples ahead: no caption, no tap; HONOR_DUMP shows no `notice STAMP` and `temples=none`. | — |

## D. A route 2.0.0 installed (U22: the launch hand-down; U23: schema 13)

| # | Check | Result |
|---|---|---|
| D1 | `adb uninstall $P`. Build 2.0.0's debug app: `git worktree add ../pilgrim-android-v200 v2.0.0`, copy `local.properties` into it, `./gradlew :app:assembleDebug` there, `adb install` its APK. Download `shikoku-88-awa`, and save its maps. The stage `way.json` (Setup's one-liner) prints `stampHours None`. | — |
| D2 | Note the mtimes: `adb shell run-as $P stat -c '%Y %n' 'no_backup/Ways/pilgrimage:shikoku-88-awa:0/way.json' 'no_backup/Ways/pilgrimage:shikoku-88-awa:0/accepted.json'`. Install this branch's debug build over it with `adb install -r`, and launch. Every stage's `way.json` now carries `"stampHours":{"closesMinutes":1020,"opensMinutes":480}`; `accepted.json`'s mtime is unchanged. | — |
| D3 | After that launch: the route page shows no "update ready", its maps still read "maps saved", and the journal's walks are all there (schema 13 migrated). | — |
| D4 | A second cold launch writes nothing: the `way.json` mtimes stay as D2 left them. | — |
| D5 | Walk a temple at 15:30 on the upgraded route: it speaks, so the hand-down reaches the walk. Then `git worktree remove ../pilgrim-android-v200`. | — |

## E. Kills (U24, U26)

| # | Check | Result |
|---|---|---|
| E1 | Kill `:tracker` right after a temple speaks. After the revival (logcat `revived`), restart the replay from HONOR_DUMP's frac: that temple never speaks or taps again; HONOR_DUMP's three clocks are what they were before the kill. | — |
| E2 | Kill `:tracker` two minutes after a temple notice, with the next temple more than 1 km ahead: after the revival the next still waits out the ten minutes (B3's spacing holds across the kill). | — |
| E3 | Kill the UI process inside a temple caption's 20 s: on return the rest of the 20 s shows, then nothing. | — |

## F. 12-hour and 24-hour (U25, U26)

| # | Check | Result |
|---|---|---|
| F1 | `time_12_24 12`: "until 5". | — |
| F2 | `time_12_24 24`: "until 17". | — |
| F3 | Flip the switch while a temple caption shows: record whether the hour follows within a second (annex A.11 item 3: drawn when drawn). | — |
