# Shared walks and Seek on `:tracker`: the device pass (U29)

Device: OnePlus 13. Owner: user. Build: the top of the Stage 21-1 stack, debug variant (`org.walktalkmeditate.pilgrim.debug`). An iPhone on iOS 2.0.0 is needed for the cross-platform rows.

This is plan unit U29, run in the same sitting as U24 (`docs/qa/2026-10-01-honor-own-walk-vertical-qa.md`), as the owner asked (2026-10-01). The specs are `docs/parity/2026-10-01-honor-shared-walk-port.md` (shared walks) and the gate rows at the end of `docs/parity/2026-09-29-honor-own-walk-port.md` (Seek on `:tracker`).

## Setup

- U20's setup for mock location, kills and `exit-info` (`docs/qa/2026-09-30-honor-architecture-proof.md` § Setup).
- **Two shares to walk:**
  - one made on iOS with recordings and photos;
  - one made on this phone, with Walk with Me's interactive tour on.
- **Seek:** a seek walk with sonar on and a known clearing nearby.

## A. Links (U27)

| # | Check | Result |
|---|---|---|
| A1 | App Links verification on a clean debug install: `adb shell pm get-app-links org.walktalkmeditate.pilgrim.debug` shows `honor.pilgrimapp.org` verified. `walk.pilgrimapp.org` is never claimed: tapping one opens the browser. | |
| A2 | A honor link tapped with the app closed: the Path tab shows "reaching for the walk…", then the overview opens and starts gathering. Nothing begins on its own. | |
| A3 | Tapped during onboarding: nothing shows until the Path tab first appears after the breath, then the overview opens. The last link wins. Kill the app during onboarding: the link is gone (R18), and no task restore replays it. | |
| A4 | Tapped during a walk, including the pre-walk screen before Start: "finish this walk first", and the link is dropped. During a sitting, nothing shows (matched as shipped, pilgrim-ios #113). | |
| A5 | Tapped while a summary is open: the overview opens only once you close the summary. Tapped with the Ways sheet open: the line shows inline, the sheet closes on success, and no toast appears. | |
| A6 | Paste a `walk.pilgrimapp.org` link into "From a shared walk": Open is enabled only while the text parses. It imports, the sheet closes and the overview opens. The clipboard is never read on its own. | |
| A7 | Malformed ids, an expired share, and a share made without the interactive tour: the failure lines read exactly as in the spec. | |
| A7a | Open a honor link from a messaging app (WhatsApp, Signal, Messages) and from a browser. It opens in Pilgrim's own task, not inside the messenger's, and Back from Path leaves Pilgrim. | |
| A7b | The widget still opens the walk summary and Home, with the flag on. (Glance tap intents carry a data URI, so a link check that only looks for data would break them.) | |
| A7c | Open a link, then reopen Pilgrim from Recents after a force stop: the link doesn't open again. | |
| A8 | The install referrer needs a build delivered through Play with the flag on. That check moves to the 2.0.0 release candidate (the release plan), as plan U29 says. | deferred |

## B. Import and media (U28)

| # | Check | Result |
|---|---|---|
| B1 | "Shared with you" lists the share at once, newest first. A row reopens the stored Way with no fetch. | |
| B2 | Gathering shows "gathering their voices · N%", then the ready overview. Begin stays disabled only while gathering. | |
| B3 | Airplane mode mid-gather: "some voices didn't arrive", with "try again" and "walk without the missing voices". Begin is enabled. "try again" with the network back gathers the rest. | |
| B4 | Airplane mode after gathering: the overview, the walk, and every voice and photo work offline. | |
| B5 | Fill the disk, if practical: the disk-full line in rust, no buttons, Begin enabled, and the files that landed stay. | |
| B6 | Shared voices show their waveform bars and can be scrubbed, both in the preview and on the walk's cards. | |

## C. Walking a share, both ways (R23)

| # | Check | Result |
|---|---|---|
| C1 | An **iOS share honored on Android**: voices play at the iOS walker's spots; the street names appear in the card sublines; sittings read "about N minutes" on the card where estimated; the summary shows the Way's title, delta and voices. | |
| C2 | An **Android share honored on iOS**: voices play where they were spoken on Android, with exact placement; recordings made in the trimmed doorstep have no pin. | |
| C3 | A reply on the first honoring of a share is kept (unlike an own walk's), and a second honoring offers "your reply". | |

## D. The sweep and Settings → Ways (U28)

| # | Check | Result |
|---|---|---|
| D1 | Settings → Data shows "Ways" between Export & Import and Maps, with "N ways · N.N MB". The list shows own and shared Ways, newest acceptance first. | |
| D2 | A share that expires unwalked leaves both lists, with its media, at the next launch or list opening. One that expires after you walked it keeps its summary (title, ghost line, delta, replies), and both lists read "voices returned to the trail". No walk screen ever says it. | |
| D3 | The sweep never touches a Way while you're walking it, nor your own walks' Ways. | |
| D4 | Swipe-to-delete removes a Way without asking. "Delete all Ways" asks, with iOS's copy. Afterwards, summaries of walks that followed them read "a way that has been removed". The reply recordings stay in the walks. | |
| D5 | Settings → Ways is hidden while a walk runs, or while it waits for its Honor step. | |
| D6 | A device-to-device transfer with your usual tool: Way media stays behind (R11), and an unfinished honor walk recovers once, without a link. | |
| D7 | Whether the worker still serves an expired share's media before its cleanup (03:00 UTC, or a page view). Re-open a walked share just after it expires and watch the gather (shared-walk spec, S3 open question 2). | |

## E. Seek on `:tracker` (U25, AE14)

| # | Check | Result |
|---|---|---|
| E1 | A pocketed seek walk, screen off, with a UI kill mid-walk: sonar and haptics keep coming, and a reached clearing records once. | |
| E2 | Kill `:tracker` mid-walk, once revived by the OS redelivering its start and once by the watchdog. Each time the chain resumes without re-seeding, and no seek-anew or setting replays. | |
| E3 | Begin: the first ping lands once, on time, with no gap and no double. Right after a revival, a due ping waits for the UI (at most 3 s), then plays once. | |
| E4 | The crescent follows you on every fix. The fog holds through Begin with no flash. | |
| E5 | Toggle sonar, its volume, and Sounds mid-walk: each takes effect at once, and the reveal whisper follows Sounds. A whisper you tap holds the sonar while it plays. | |
| E6 | A chain that locks after Start still gets guidance. A Seek Anew tapped right at Begin is kept. | |
| E7 | `:tracker` memory and audio focus with Seek running. | |

## F. iPhone checks (iOS behavior Android must match or record)

- [ ] **S1 open question 1:** within the hour, does an iPhone reopen a share offline after importing it (the cached `tour.json`)?
- [ ] **S2 open question 1 (pilgrim-ios #113, item 1):** does a link opened under the journal's summary, Goshuin or Settings ever show its overview?
- [ ] **S2 open question 7:** a link tapped during the seal reveal, then Begin from that overview: what happens to the pending seal and summary?

## Sign-off

- [ ] Date, build SHA, and anything filed.
