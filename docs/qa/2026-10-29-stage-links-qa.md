# Stage links: the device pass (Phase 22 U12)

Device: the owner's Android phone. Build: the top of `feat/stage-links`, debug variant (`org.walktalkmeditate.pilgrim.debug`). Spec: `docs/parity/2026-10-28-stage-links-port.md`. Plan: `docs/plans/2026-10-29-001-feat-stage-links-port-plan.md`. An iPhone on a build with iOS #125 helps for side-by-side rows but isn't required.

## Setup

- Install: `./gradlew :app:installDebug`.
- Links verified: `adb shell pm get-app-links org.walktalkmeditate.pilgrim.debug` shows `honor.pilgrimapp.org: verified`.
- Send a link: `adb shell am start -a android.intent.action.VIEW -d https://honor.pilgrimapp.org/<route>/<n>`. If the browser or a chooser opens instead, add `-p org.walktalkmeditate.pilgrim.debug`.
- Routes, from `../open-pilgrimages/index.json` at `v1.13.0`:
  - `kumano-kodo-nakahechi`, 4 stages, about 65 KB: the one to download.
  - `camino-frances`, 33 stages: not downloaded.
  - `shikoku-88-tosa`, 15 stages.
  - `camino-ingles`: listed in open-pilgrimages but with no stages package, so Pilgrim's catalog leaves it out.
- Before row A1, no route is downloaded.

## A. Opening a link

| # | Check | Result |
|---|---|---|
| A1 | **Cold start, route not on the phone.** `adb shell am force-stop org.walktalkmeditate.pilgrim.debug`, then `…/camino-frances/14`. The app opens on Path; "reaching for the route…" may show briefly; the Camino Francés route page opens as a sheet over Path with "Close" at its top left (no back arrow), stage 14 in the middle of the list, and "Download this route first?" over it. "Not now" leaves the page. | |
| A2 | **Warm start from another tab.** With the app on the Journal tab, send the app home, then `…/camino-frances/20`. The app switches to Path and the page opens on stage 20, with the prompt. The scroll reads as one motion. | |
| A3 | **Close, swipe, Back.** On the page from A2: "Close" lands on Path; send the link again and swipe the sheet down: Path; again and press system Back: Path. Nothing is left behind that blocks taps on Path. | |
| A4 | **Choosing a stage.** Open `…/kumano-kodo-nakahechi/1`, answer "Download", wait for "On your phone", tap stage 3: its overview opens over Path; Close lands on Path. | |
| A5 | **A route on the phone.** With Nakahechi downloaded, `…/kumano-kodo-nakahechi/3`: stage 3's overview opens directly, with no route page. Repeat in airplane mode: the same, as a downloaded stage never waits on the catalog. | |
| A6 | **Another route on the phone.** With Nakahechi downloaded, `…/shikoku-88-tosa/3`: the Tosa page opens on stage 3 with the prompt; "Download" asks "Replace?" naming Nakahechi; "Keep it" leaves both as they were. | |
| A7 | **Not in the catalog.** `…/camino-ingles/1`: "reaching for the route…", then "this route isn't in Pilgrim yet"; nothing opens; the tab switches to Path. | |
| A8 | **Catalog out of reach.** `adb shell run-as org.walktalkmeditate.pilgrim.debug rm files/Pilgrimages/catalog.json`, force-stop, turn airplane mode on, `…/camino-frances/14`: "reaching for the route…", then "the routes are out of reach right now". Airplane mode off, the same link opens the page. | |
| A9 | **Swipe-to-close on the link page.** Open `…/camino-frances/14` (route not downloaded) so the page shows over Path. Swipe the sheet down: the page closes to Path and nothing else changes (tab, scroll, toasts). Then open it again and press system Back: the same. No unit test covers the swipe. | |
| A10 | **A link to a route whose download is already running.** From the catalog's route page, start downloading `kumano-kodo-nakahechi`. While it runs, send `…/kumano-kodo-nakahechi/2`. Expected, matching iOS: the link page shows the running download and may still ask "Download this route first?". Tapping Download shows a failure line while the first download carries on. Record what you see. This is a known iOS-shared edge; the owner decides whether Android should guard it. | |

## B. Where the walker is

| # | Check | Result |
|---|---|---|
| B1 | **During a walk.** Start a walk, then `…/camino-frances/14`: "finish this walk first" over the walk screen; nothing opens, during or after the walk. Then `…/abc`: "this link needs a newer Pilgrim", the walk untouched. In a sitting (meditation) over the walk: no toast at all. | |
| B2 | **Over the summary (Q6, option A).** Finish a walk; with its summary up, `…/camino-frances/14`: no toast shows over the summary, and the summary stays. Tap Done: the page opens over Path on stage 14. Repeat closing the summary with a swipe, and with Back. | |
| B3 | **A summary from the Journal and from the Goshuin.** Open a past walk's summary from the Journal, send `…/camino-frances/14`, close it: the page opens over Path. The same from a Goshuin seal's summary. | |
| B4 | **"Walk this again" with a link parked.** With a summary up, send `…/camino-frances/14`, then tap "walk this again": the walk's overview opens in the summary's place and the page does not. (A walk the door shows for but that builds no Way is unit-tested in U7.) | |
| B5 | **Over the Ways sheet.** Open the Ways sheet ("Choose a way"), send `…/abc`: the toast shows at the top of the sheet, readable, with no second copy behind it where the sheet meets the status bar; the sheet's rows under the toast still take taps. Send `…/camino-frances/14`: the sheet goes and the page opens. | |
| B6 | **Over an overview.** With any overview up, send `…/abc`: the toast shows over the overview. | |
| B7 | **Deep in Settings.** Open Settings → Data → Maps, send `…/camino-frances/14`: the page opens over Path; Close; tap the Settings tab: Maps is still showing. | |
| B8 | **The toast hand-off when a sheet opens over a toast already showing.** Cause a toast (for example `…/camino-ingles/1`, a route not in Pilgrim), then open the Ways sheet within 5 s. Expected: one toast stays readable. The app-level copy fades out under the scrim while the sheet's copy shows at the sheet's top. On closing the sheet, the app-level copy slides back in if the toast is still live. Note anything that looks like two toasts at once for longer than a moment. | |

## C. A second link

| # | Check | Result |
|---|---|---|
| C1 | **Another stage of the same route.** With the page on stage 14 up, `…/camino-frances/20`: the page opens again on stage 20, with the prompt again. | |
| C2 | **The same link again.** With the page on stage 20 up and its prompt answered "Not now", `…/camino-frances/20` again: the page stays where it is, no new prompt, no scroll. | |
| C3 | **Another route.** With the Francés page up, `…/shikoku-88-tosa/5`: the Tosa page replaces it. | |
| C4 | **Rotation.** On a page whose prompt was answered "Not now", rotate the phone twice: the prompt doesn't come back and the list doesn't jump. | |

## D. Paths this build can't open

| # | Check | Result |
|---|---|---|
| D1 | **An unrecognised path.** Each of `…/abc`, `…/camino-frances/0`, `…/camino-frances/014`, `…/Camino-Frances/14`, `…/a/b/c`: "this link needs a newer Pilgrim"; the tab doesn't change; nothing opens. | |
| D2 | **The bare site.** `https://honor.pilgrimapp.org/`: the app opens and shows nothing. | |
| D3 | **A stage number too large.** `…/camino-frances/201`: "this link needs a newer Pilgrim". `…/camino-frances/34` (under 200, past the route's 33): the page opens at its top with no stage centred and no prompt. The same for `…/kumano-kodo-nakahechi/9`. | |
| D4 | **Pasting.** In "Choose a way", paste `honor.pilgrimapp.org/camino-frances/14`: "Open" is enabled and opens the page. Paste `honor.pilgrimapp.org/camino-frances/0` and `honor.pilgrimapp.org/camino-frances/14.`: "Open" stays disabled. | |

## E. Look and feel

| # | Check | Result |
|---|---|---|
| E1 | The toast in light and dark: caption ink on parchment, readable over the sheet and over the overview. | |
| E2 | TalkBack on the page: "Close" reads as a button; the toast is reachable but announces nothing, as on iOS. | |
| E3 | The centring on the last stages (`…/camino-frances/33`): the list stops at its end rather than leaving a gap. | |
