# Honor architecture proof on device (U20)

Device: OnePlus 13. Owner: user. Build: the top of the Stage 21-1 stack (PR #260 or later), debug variant, `org.walktalkmeditate.pilgrim.debug`.

This is plan unit U20: the checkpoint before any UI breadth. **U21–U23 start only after every item below passes, or its finding is fixed and re-checked, and after the voice-guide decision at the end is recorded.** Findings fold back into U17 (the session) and U18 (the audio arbiter) first. The specs are `docs/parity/2026-09-29-honor-own-walk-port.md` (sections B, C, D) and the plan's U17/U18/U20 units.

## Setup

```sh
P=org.walktalkmeditate.pilgrim.debug
A=org.walktalkmeditate.pilgrim.debug.HONOR_
adb shell pm grant $P android.permission.ACCESS_FINE_LOCATION
adb logcat -c
adb logcat -s HonorDebug WayReplayer HonorSession UiAudioGate WalkTrackingService WalkTrackingWatchdog > honor-proof.log &
```

- **Mock location:** on Developer options → Location → **Select mock location app**, choose Pilgrim. The OnePlus 13 refuses `appops set … mock_location` from the shell, because the shell lacks `MANAGE_APP_OPS_MODES`. Confirm with `adb shell appops get $P android:mock_location`, which should read `allow`.
- **If the phone's location ever seems stuck** at a walk's position after a `:tracker` kill, send `adb shell am broadcast -p $P -a ${A}REPLAY_STOP`. Play services keeps a killed process's mock mode on. Since the 2026-10-01 fix, the harness also releases it at every `:tracker` start.
- **Source walk:** pick one of your own walks with **at least two voice recordings**, a route of a few hundred metres or more, and ideally a sitting. The harness can't make a synthetic one. List candidates with `adb shell am broadcast -p $P -a ${A}LIST` and note its id as `W`.
- **Starting an Honor walk.** Begin needs the app in the foreground. Run `adb shell monkey -p $P 1`, then `adb shell am broadcast -p $P -a ${A}REPLAY_START --es walk $W` and `adb shell am broadcast -p $P -a ${A}BEGIN --es walk $W`.
- **Before a replay ends,** finish the walk: when the replay stops, real GPS takes over and puts you back at the desk.
- **Useful at any point:**
  - `adb shell am broadcast -p $P -a ${A}DUMP --es walk <new walk id>` dumps the session (ids, fracs, phases).
  - `adb shell am broadcast -p $P -a ${A}COMMAND --es cmd toggle|skip|rate|reply [--es moment voice-2]` sends the chip and card commands U22's UI will send. `toggle` and `reply` act on the held voice unless a moment is named.
  - `adb shell ps -A -o PID,NAME | grep pilgrim` gives both process ids. `pidof` misses `:tracker` on the OnePlus.
  - Kill the UI process: `adb shell 'run-as org.walktalkmeditate.pilgrim.debug kill -9 $(pidof org.walktalkmeditate.pilgrim.debug)'`
  - Kill `:tracker`: look up its pid with the `ps` line above, then `adb shell run-as org.walktalkmeditate.pilgrim.debug kill -9 <pid>`
  - Both kill one process of this debuggable app, without root. The single quotes make `pidof` run on the phone.
  - `adb shell dumpsys activity exit-info $P | head -40` shows why a process ended.

Record each result in the Result column: date, what you saw, and the log timestamp.

## A. Voices in a pocket

| # | Check | Result |
|---|---|---|
| A1 | Begin, lock the phone, and leave it screen-off for **10+ minutes** while the replay runs. Each voice plays at its spot; the soundscape dips under it and comes back. | Run 1, 2026-10-01: both voices played to the end at their spots (09:18:28, 09:21:29), heard and FINISHED, but the screen was on. **Redo with the screen off from Begin.** |
| A2 | Mid-walk, switch to another app for a few minutes, then come back. Voices keep playing on schedule, and the notification's Honor line reads "~N m to go" (or "almost there" under 100 m). | |
| A3 | Let the replay reach the end: arrival fires once (haptic, "their way, walked" in the notification), and the dump shows the phase as arrived. | Pass, 2026-10-01. Screen off (Dozing) from 09:37:01. The waypoint (09:37:16) and the sitting (09:50:50) were reached. One HONOR_ARRIVAL at 09:51:44, then the waypoint 'Walked their way: Aug 17, 2026' (signpost.right.fill), with arrival numbers 2,437.94 s / 2,133.66 s. Haptics not yet confirmed by feel. |

## B. The UI process dies

| # | Check | Result |
|---|---|---|
| B1 | Mid-voice, kill the UI process (the setup's command). The voice keeps playing, `:tracker` keeps writing rows (dump), and the next voice plays at its spot. | Pass, 2026-10-01 (between voices): `:tracker` kept its pid through the UI kill, and the session kept writing. |
| B2 | **Record whether and when the UI process restarts on its own** with no activity (poll `pidof $P` every few seconds). Record the delay, and how long until the voice guide speaks again (if it's on). This decides the guide's placement below. | **The UI process restarts by itself in about 2 s**, with no activity (exit-info: SIGNALED, then a new pid 2 s later). The guide's restart time is still to measure, with the guide on. |
| B3 | Open the app after B1. The walk screen shows the walk in progress with the right state. | Pass, 2026-10-01: reopening lands on the walk in progress, with the replayed route, the dot, 20:25 elapsed and 0.85 mi. |

## C. `:tracker` dies and comes back

| # | Check | Result |
|---|---|---|
| C1 | During a walk, send the chip commands (the setup's `COMMAND`): `toggle` twice (pause, then resume), `skip`, and `rate` twice. Also start and stop a recording from the walk screen. Then kill `:tracker` (the setup's command). **Record which path revived it**: the OS's START redelivery (`WalkTrackingService` logs soon after) or the watchdog (`WalkTrackingWatchdog` about a minute later). Also record the exit reason from `exit-info`. | 2026-10-01: killed `:tracker` after arrival. **Revived by the watchdog** (alarm 09:56:52, service start 30 ms later), **about 3 min after the kill**. The OS never restarted the service itself. Exit reason: SIGNALED, status 9. The chip commands still need a run before the kill. |
| C2 | After the revival: **no voice replays, no command replays** (the rate stays as set, and skipped voices stay skipped), and no gate replays. The dump shows the same anchor and progress. The next voice plays at its spot. | Pass, 2026-10-01: revived ARRIVED at 0.997, gate generation 1 → 2, both voices still FINISHED with their original start times, one HONOR_MODE and one HONOR_ARRIVAL. **Found:** mock mode stayed stuck after the kill (Play services keeps a dead client's mock mode); fixed in the harness the same day. |
| C3 | Repeat C1 until you've seen **both** revival paths at least once (if the OS's redelivery always wins, kill `:tracker` again just after it restarts: Android backs off repeated restarts, which gives the watchdog its turn). | |
| C4 | Kill `:tracker` **while a recording is in progress**, with the UI alive. After revival, no Way voice plays until the recording ends (U18's 3 s hold, then the UI's re-sent gate). | |
| C5 | Finish a walk, then start a second Honor walk right away (a cached `:tracker`). The second session starts fresh: no carried voice, no stale rate. | |

## D. Audio against the rest of the phone (R17, AE11)

| # | Check | Result |
|---|---|---|
| D1 | With the voice guide on, wait for a **guide prompt mid-voice**. The voice pauses before the prompt speaks, the soundscape stays dipped, and the voice resumes where it stopped when the prompt ends. | |
| D2 | A guide prompt already playing when you reach a voice's spot: the voice waits, and starts when the prompt ends. | |
| D3 | **Kill the UI mid-prompt.** The prompt gate dies with it (Binder death), and the Way voice resumes. Record how long that takes. | |
| D4 | **Phone call mid-voice** (call the phone). The voice pauses for the call and resumes after it. This is Android's platform handler; iOS goes silent here (pilgrim-ios #102). | |
| D5 | **Unplug headphones mid-voice.** The voice pauses and stays paused until you resume it from the chip. | |
| D6 | A whisper in range while a Way voice plays waits until the voice ends; a whisper pending when a guide prompt starts is dropped. | |

## E. Measurements

| # | Measure | Result |
|---|---|---|
| E1 | `:tracker` memory mid-walk: `adb shell dumpsys meminfo $P:tracker | head -30` (PSS total). | |
| E2 | The redelivery backlog after a long walk with many prompts, recordings and commands: `adb shell dumpsys activity services $P | grep -i -A3 delivered`. Note how many delivered starts pile up (U18's open question). | |
| E3 | How often the 20 m accuracy filter rejects a pocket fix (evidence for U25's Seek feed divergence). **The harness has no counter for this yet.** Skip it, or ask for a debug counter in the dump before this run. | |

## F. Decision for the owner (before U21 starts)

From B2's measured UI-restart behavior, record where the voice guide lives:

- [ ] **Stays in the UI process.** Its gate row at the parity gate becomes a dated re-justify, noting how long the guide is quiet after a UI kill.
- [ ] **Moves into `:tracker`.** A U18 follow-up, before U21. Only U18 depends on this choice.

Decision, date, and reason: **stays in the UI process** (2026-10-01). B2 measured the UI process restarting by itself in about 2 s after a kill, so the guide is quiet only that long; the parity gate records it as a dated re-justify. The owner can still move it into `:tracker`, which would be a U18 follow-up.

Also confirmed in run 1: the clean-finish finalize. The link file holds `walk:a39cff08…` with the arrival numbers, the marker is `CLEAN`, the promoted `way.json` and `accepted.json` are in place, the live rows are deleted and the staging is removed.

## Sign-off

- [x] **Closed 2026-10-01 by the owner.** Passed: A3, B1, B2, B3, C2, and the clean-finish finalize. C1 passed with a note: the watchdog revives after about 3 minutes, and the OS never restarted the service itself on this OnePlus. The owner waived the rest on 2026-10-01: A1 with the screen off (voices did play with the screen on), A2, C3–C5, D1–D6 and E1–E3. The parity gate's pocket-bar rows re-check them on device before 2.0.0.
- [x] Builds: `faa57315` (run 1), then `eadeb07b`. Found and fixed: Play services keeps a killed process's mock mode on (`eadeb07b`).
