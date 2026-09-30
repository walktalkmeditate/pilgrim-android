# Stage 21-0 device pass (1.5.1 release gate)

Device: OnePlus 13. Owner: user. Build: the top of the Stage 21-0 stack, debug variant (`org.walktalkmeditate.pilgrim.debug`) unless an item says release candidate. This is plan unit U10: the 1.5.1 dispatch waits on it. The port spec is `docs/parity/2026-09-29-stage21-0-groundwork-port.md`.

## A. Maps on Mapbox `android-ndk27:11.23.1` (U3, U4)

- [ ] **Active walk, follow and gestures (#219).** Start a walk and let the map follow for a minute. Pan, then pinch out. The camera must **stay** where you put it, not snap back to zoom 16. A two-finger twist or a two-finger drag must neither rotate nor tilt the map (both are off now, as on iOS). Close #219 with a note of this result.
- [ ] **Live-walk seed.** Cold-start a walk with a recent fix: the map opens near zoom 16. With location off until after start (seeded from the last walk's end): about zoom 14.
- [ ] **Summary reveal, normal screen.** Finish a walk. The map plants on the first point, then eases (2.5 s, ease-out) to the whole route, with some margin on every side. During the reveal, pan and pinch do nothing; afterwards both work.
- [ ] **Summary reveal, short screen.** Force a short map: split-screen or a large font and display size. The route must still frame, never the zoomed-out globe or an empty zoom-17 ocean. Rotate the device mid-reveal and after it; the route re-frames each time.
- [ ] **Segment taps.** Tap a timeline segment: the map eases to that stretch over 2.5 s (slower than 1.5.0's quick 350 ms, on purpose; iOS parity, pilgrim-ios #95). Deselect it: back to the whole route. Tap a segment with no GPS samples (for example a pause): the map stays put.
- [ ] **Remove animations** (Settings → Accessibility → Remove animations ON): open a summary. It should jump to the framed route rather than play the ease. The in-app reduce-motion snap is gone (pilgrim-ios #96); this checks the OS setting still short-circuits Mapbox's animators. If you see a 2.5 s ease from the globe, file it.
- [ ] **Seek:** fog and crescent render and animate; the arrival glyph draws.
- [ ] **Pins:** whisper, cairn, photo, and waypoint pins draw at their spots, in order, on both the active and summary maps.
- [ ] **Lock/unlock, and a theme flip** (`adb shell cmd uimode night yes/no`) mid-walk and on a summary: routes, pins and fog reinstall, and the camera doesn't jump.
- [ ] **16 KB pages.** On a 16 KB-page emulator image (Android 15+ "16 KB page size" system image), install the release candidate and open a map. It must not crash. On the OnePlus 13 (4 KB), the release candidate opens maps normally.

## B. Sittings, one source (#223, U6)

- [ ] **Upgrade, not fresh install.** Install 1.5.0 from Play (or keep your current install), then install this build over it so migration 8→9 runs.
- [ ] **Totals.** Before upgrading, note the meditation total in Settings and on the journal, and the Longest Meditation seal state. After upgrading and a minute on the home screen (the backfill runs at launch), totals include your Android-recorded sittings. They should grow if you meditate on walks. The Longest Meditation seal can now be earned.
- [ ] **A walk with a sitting:** its summary's meditation figure matches its timeline, and never exceeds the walk's active time.
- [ ] **Export.** Export a `.pilgrim` containing a walk with a sitting. Open the JSON: that walk's `activities` has a `"meditation"` entry, and `stats.meditateDuration` is non-zero.
- [ ] **Round trip.** Import an iOS 2.0.0 export that contains an honor walk (if you have one): it imports, and re-exporting keeps `honorMode`, `honorArrival` and the `signpost.right.fill` waypoint.

## C. Shares (U5, and U6's share fix)

- [ ] **Caption.** In Share Journey, turn on Interactive: the first line reads "Anyone with the link can walk it there." Turn it off: the line is gone.
- [ ] **A walk with a long sitting shares.** Take a walk that is mostly sitting plus a recording, for example 20 minutes with a 10-minute sitting and 3 minutes of talk. Before this fix the worker rejected that with a 400. It must now share, and the page's walking, meditation and talk times must add up to the walk's active time.
- [ ] **A sitting stops the recording.** Mid-walk, start a recording, then start a sitting while it runs. The recording stops and is saved before the sitting begins, as on iOS. After the walk, its row is in the summary's recordings, and the walk shares.
- [ ] **Recording coordinates.** Share a walk with recordings, Interactive and Trim on. Optional cross-platform check: honor that share on an iPhone running iOS 2.0.0. Voices play where they were spoken, and a recording made in the trimmed doorstep has no pin of its own.

## D. Small fixes (U7)

- [ ] **About.** Data Sources shows the maps paragraph, then "© Mapbox" and "© OpenStreetMap contributors", between the weather and routes rows. Each opens its page.
- [ ] **#225, expired share.** You need a share past its expiry. Either use an old share, or share with the shortest option and set the device date forward (Settings → System → Date & time; revert afterwards). The summary shows the returned-to-the-trail block: icon, "This walk has returned to the trail", "Shared for …", a divider, and "Share again". Share again opens a fresh share form. Judge the icon visually (an undo arrow in a thin ring); swap it if it reads wrong.
- [ ] **#221, reliquary.** Revoke photo access for Pilgrim, then turn on Walk Reliquary in Settings and deny the prompt. The switch returns to off and the denied note stays. Grant on the next try: the switch stays on.

## E. Release

- [ ] **Data Safety.** Recording coordinates now ride inside the interactive share, which already carries the route (location shared with the share service). Confirm the Play Data Safety form already declares location shared for the share feature; if it doesn't, update it before dispatch.
- [ ] **Play notes.** `app/src/main/play/release-notes/en-US/whatsnew.txt` reads right, and says totals may grow.
- [ ] **Dispatch** `production.yml` with `version=1.5.1`. U14's migration waits until this release's soak ends: full rollout with no open crash cluster. Record that date here.
