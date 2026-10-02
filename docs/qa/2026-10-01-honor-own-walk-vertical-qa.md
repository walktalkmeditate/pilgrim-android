# Honor on your own walks: the device pass (U24)

Device: OnePlus 13. Owner: user. Build: the top of the Stage 21-1 stack after U23, debug variant (`org.walktalkmeditate.pilgrim.debug`).

This is plan unit U24. The owner chose one combined device pass at the end of the stage (2026-10-01). So this doc also carries:
- the Stage 21-0 pass (U10, `docs/qa/2026-09-29-stage21-0-map-pass.md`);
- the U20 items the owner waived (`docs/qa/2026-09-30-honor-architecture-proof.md`);
- the visual checks U21–U23 left for a device.

Run it before Seek and shared walks build on the own-walk vertical. The shared-walk and Seek pass (U29) is its own doc. The specs are `docs/parity/2026-09-29-honor-own-walk-port.md` and `docs/parity/2026-10-01-honor-shared-walk-port.md`.

## Setup

Use U20's setup (mock location, the `HONOR_` adb commands, the kill commands, and `exit-info`), from `docs/qa/2026-09-30-honor-architecture-proof.md` § Setup. The replay harness walks a Way from a desk; for the pocket items, a real walk is better.

- **Source walk:** one of your own walks with at least two voice recordings, a photo, a waypoint, a pause of 3 minutes or more, and a sitting, over a route of a few hundred metres or more.
- **Voice guide:** on, with a guide downloaded. **Soundscape:** on.
- **Record** each result in the Result column: date, what you saw, and the log timestamp.

## A. The doors and the overview (U21)

| # | Check | Result |
|---|---|---|
| A1 | Path tab, Honor selected: "HONOR", "walk in their steps", a quote, and the staff glyph. Unselected labels are dimmer, but readable. Start reads "Honor" and opens "Choose a way". | Pass, 2026-10-02. |
| A2 | "Choose a way" → "Walk one of yours again" → "Walk again": your finished walks, newest first. A walk with too little route raises "Can't walk this one again". Path shows behind the sheet and the picker, as iOS's tab view does behind its sheet. | Pass, 2026-10-02. Found: the sheet sat over a bare background; fixed in `c66f19ae`, so Path now shows behind it. |
| A3 | "walk this again" sits centred under the share card on the post-walk, journal, Goshuin and widget summaries, and not in Recordings. | Pass, 2026-10-02. |
| A4 | The overview: the Way fills the map above the card, with margins, and it doesn't rotate, tilt or follow. The Mapbox logo and attribution sit at the map's default bottom position, under the card, as on iOS (owner decision 9, reversed 2026-10-02). Pins tap open the preview: the voice plays, the speed pill cycles, and the scrubber moves. A photo opens whole, and pinch, tap and swipe-down close it. | Pass, 2026-10-02: framing, pins, preview voice and speed, scrub, photo viewer. Found: an overview opened after a walk screen closed sometimes lacked its ghost line, fit, distance and weather; fixed in `c66f19ae` and re-checked. |
| A5 | Light and dark (`adb shell cmd uimode night yes/no`) on the sheet, the picker, the overview and the preview. | Reported good by the owner at close, 2026-10-02 (the pass ran in the Constellation appearance). |

## B. In a pocket (AE1, R16, R17)

| # | Check | Result |
|---|---|---|
| B1 | Begin, then Start. Lock the phone, pocket it, and walk the Way for **10+ minutes**. Each voice plays at its spot, and the soundscape dips under it and comes back. A guide prompt that starts mid-voice pauses the voice first; the voice resumes where it stopped. (Also covers U20 A1 with the screen off.) | Waived by the owner, 2026-10-02. |
| B2 | Mid-voice, turn the soundscape on from the walk options sheet. As on iOS, it isn't ducked under that voice (pilgrim-ios #104, matched). | Waived by the owner, 2026-10-02. |
| B3 | Switch apps for a few minutes, then come back: voices keep their schedule. The notification's Honor line reads "~N m to go", or "almost there" under 100 m. (U20 A2.) | Waived by the owner, 2026-10-02. |
| B4 | A phone call mid-voice: the voice pauses, then resumes after the call. Headphones unplugged mid-voice: it pauses and stays paused until the chip resumes it. (Owner decision 2; U20 D4, D5.) | Waived by the owner, 2026-10-02. Platform handlers unit-tested. |
| B5 | Arrival: one haptic, the arrival card, and "their way, walked" in the notification. | Waived by the owner, 2026-10-02. |

## C. On the walk screen (U22)

| # | Check | Result |
|---|---|---|
| C1 | Before Start, the pre-walk screen draws the Way: the ghost line and pins. | Pass, 2026-10-02: the pre-walk screen draws the Way (a shared Way, Austin). |
| C2 | Walking: the ghost line sits under your route, and the companion dot is above it, moving in steps. Pause: the dot stops (one catch-up step, then still). Sit: it doesn't move while you sit. | Waived by the owner, 2026-10-02. |
| C3 | Lock/unlock, and a theme flip mid-walk: the ghost line, companion and pins come back, with the companion still above the route (owner decision 7). | Waived by the owner, 2026-10-02. |
| C4 | Cards: reached moments queue with "N more waiting" dots. A tapped pin's card jumps the queue. A voice card you don't touch leaves 20 s after its voice ends. Dismiss advances. | Waived by the owner, 2026-10-02. |
| C5 | A card header tap flies the map to the moment, and a second tap comes back. **Owner decision 8:** while walking, does follow pull the map back during the fly-to? Record what you see. | Waived by the owner, 2026-10-02. |
| C6 | The chip: pause/resume and skip respond at once. The rate on the card cycles 1x, 1.25x, 1.5x, 2x. | Waived by the owner, 2026-10-02. |
| C7 | "reply here" records a reply. On a second honoring of the same walk, that card offers "your reply", and it plays. On a first honoring of your own walk the reply isn't kept (pilgrim-ios #98, matched). | Waived by the owner, 2026-10-02. |
| C8 | "Sit?" while recording stops the recording and opens the sitting, with "they sat here N minutes". While paused, it does nothing. | Waived by the owner, 2026-10-02. |
| C9 | Kill the UI process mid-walk (U20 setup): reopening shows the heard pins, the cards still in their window, the chip, and a landed arrival card. | Waived by the owner, 2026-10-02. |
| C10 | A guide prompt mid-voice: the chip still reads "listening", but its clock stops until the prompt ends, and never reads past the voice's length. | Waived by the owner, 2026-10-02. |
| C11 | The card's heading tick follows the phone's compass (calibrate if it spins). The card sits just above the sheet in both sheet states, and swipes away cleanly. "reply here" asks for the microphone the first time. | Waived by the owner, 2026-10-02. |

## D. `:tracker` (U20's waived items)

| # | Check | Result |
|---|---|---|
| D1 | Send the chip's commands, then kill `:tracker`. After the revival (the watchdog, about 3 minutes, on this phone), no voice, command or gate replays. (U20 C1, C2.) | Waived by the owner, 2026-10-02. |
| D2 | Kill `:tracker` while recording, with the UI alive. After the revival, no Way voice plays until the recording ends. (U20 C4.) | Waived by the owner, 2026-10-02. |
| D3 | Finish, then start a second honor walk right away, in a cached `:tracker`: no carried voice or rate. (U20 C5.) | Waived by the owner, 2026-10-02. |
| D4 | A guide prompt already playing at a voice's spot: the voice waits. Kill the UI mid-prompt: the voice resumes; note how long that takes. A whisper waits behind a voice, and is dropped behind a prompt. (U20 D1–D3, D6.) | Waived by the owner, 2026-10-02. |
| D5 | Measurements: `:tracker` memory mid-walk (`dumpsys meminfo $P:tracker`), and the delivered-start backlog after a long walk. (U20 E1, E2.) | Waived by the owner, 2026-10-02. |

## E. After the walk (U23)

| # | Check | Result |
|---|---|---|
| E1 | The summary: "in their steps", the Way's title, the delta line, "N voices along the way · N replies", and the ghost line on the map, under your route. The arrival waypoint draws as a signpost. | Pass, 2026-10-02, on the U20 walk: "in their steps", "Aug 17, 2026", "they arrived 5 minutes after you", "2 voices along the way", the signpost. |
| E2 | The journal: the walk's card shows the staff, and the scenery shows staffs. | Reported good by the owner at close, 2026-10-02. |
| E3 | Seals: your walk's line is watermarked, with the Way's line fainter beneath it. "First Honor" is pressed. Check that the faint lines read on the Goshuin thumbnails and the journal button, not only the big seal. | Reported good by the owner at close, 2026-10-02. |
| E3a | Finish an honor walk and watch the summary: the section shows at once, and the delta line appears a moment later when the Honor step lands. The arrival signpost on the map is 18 dp, a little smaller than the other waypoint glyphs. | Waived by the owner, 2026-10-02. |
| E3b | Goshuin with many walks scrolls smoothly, and memory stays reasonable (`dumpsys meminfo $P`). | Waived by the owner, 2026-10-02. |
| E4 | Delete the source walk's Way (Settings → Ways, once U28 lands; until then skip): the summary reads "a way that has been removed", and the seal loses the Way's line (owner decision 3). | Pass, 2026-10-02: after the Way was deleted the summary reads "a way that has been removed". |

## F. Battery exemption

| # | Check | Result |
|---|---|---|
| F1 | With the exemption granted, a 30-minute pocket walk runs to the end. | Waived by the owner, 2026-10-02. |
| F2 | With it revoked, OxygenOS kills the backgrounded walk after about 10 minutes, and recovery finishes it. As on iOS, the walk and its honor events are kept. A first honoring gets no link and no delta, so its summary reads "a way that has been removed" (pilgrim-ios #107). | Waived by the owner, 2026-10-02. |

## G. Carried in from Stage 21-0 (U10)

- [ ] Run `docs/qa/2026-09-29-stage21-0-map-pass.md` in full, and record it there.

## H. iPhone checks (iOS behavior Android must match or record)

These need an iPhone on iOS 2.0.0. Each answer goes into the spec it came from, and into the parity gate.

- [ ] **Own-walk E open question 2, decision 8:** does the fly-to hold while walking, or does follow pull it back?
- [ ] **U21 V1:** do the Ways sheet's section headers render uppercase ("SHARED WITH YOU")?
- [ ] **Own-walk C open questions:** does the system pause an `AVAudioPlayer` for a call under `.mixWithOthers`? Does it carry on through a route loss?

## Sign-off

- [ ] Date, build SHA, and anything filed.

## Record

- **Run 2026-10-02** on the OnePlus 13, builds `e95005d4` then `c66f19ae`. Installed over U20's build: schema 10 migrated to 11 with all 11 walks intact. App Links for `honor.pilgrimapp.org` verified.
- **Found and fixed in `c66f19ae`, re-checked on the phone:**
  - the debug replayer emptied the cached location at every `:tracker` start, so the overview lacked its distance and weather;
  - the ghost line was skipped while Mapbox reported the style not loaded (iOS's style-loaded latch ported);
  - the camera fit raced Mapbox's default style;
  - the Ways sheet sat over a bare background.
- **Owner decision 9 reversed:** the overview card covers the Mapbox logo, as on iOS (#111 item 4 withdrawn).
- **Waived by the owner:** the walk-based rows (B, C2–C11, D, E3a–b, F), the replay walk, the phone call and the headphones. The parity gate's pocket rows re-check them on a device before 2.0.0.
- **Noted for later:** Mapbox logs six "Style object … should not be stored" warnings at each map's first style load, from the location puck binding before plugins receive the style. It's harmless, since the puck draws, and it predates this stage.
