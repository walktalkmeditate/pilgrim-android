# Parity Spec: Honor, the shared-walk slice

| field | value |
|---|---|
| **iOS pin** | `v2.0.0` = `7c200bf` |
| **Worker** | `pilgrim-worker` `main` @ `2a4f5d0` (read-only; the deployed share worker) |
| **Android HEAD** | `636cf5ce` |
| **Generated** | 2026-10-01 |
| **Type** | port |
| **Generator** | ios-parity skill: four topic readers in parallel, each applying all four lenses (behavior, UI and visual, data, edge cases) to one cluster |
| **Plan** | `docs/plans/2026-09-29-001-feat-honor-groundwork-own-shared-walks-plan.md` (U26; feeds U27, U28, and the shared-walk branches of U21–U23) |
| **Companion** | `docs/parity/2026-09-29-honor-own-walk-port.md`, cited as "own-walk spec A §n" and so on. This spec doesn't repeat it. |
| **Checked** | All 251 cited code blocks were machine-checked: every quoted line appears at its cited lines in the pinned tree (239 from iOS at `7c200bf`, 12 from the worker at `2a4f5d0`). |

This spec outranks the plan wherever they disagree (the plan's authority order). Every iOS claim carries a Swift quote pinned to `7c200bf`; worker claims are pinned to `2a4f5d0` and Android claims to `636cf5ce`. Inside a cluster, "§6" means that cluster's `### 6.` section; "S3 §6" means section 6 of cluster S3. Each cluster numbers its own defect candidates (S1-D1 and so on); the table further down maps them to the upstream issues.

| Section | Covers | Feeds |
|---|---|---|
| [S1. Shared walks in](#s1-shared-walks-in-the-tour-manifest-the-importer-and-the-import-states) | `TourManifest`, the fetch, all 29 validation bounds, the manifest-to-`Way` build, the import states and their copy, sharer text | U28, U27 |
| [S2. Honor links](#s2-honor-links-tapped-pasted-held-and-routed) | `HonorLink`, the App Links filter, the setup hold, the routing table, the walk-active refusal, the toast, paste, the install referrer's outcome | U27 |
| [S3. Media, the sweep, the store](#s3-shared-walk-media-the-expiry-sweep-and-the-store) | `WayMediaDownloader` (paths, caps, ceilings, retry, disk full, the deleted-Way guard), the shared store layout, the expiry sweep and its triggers | U28 |
| [S4. Shared walks on screen](#s4-shared-walks-on-screen-the-ways-list-the-overview-while-gathering-and-the-shared-branches) | Settings → Ways and the Data card row, the Ways sheet's shared sections, the overview's gathering states, the shared branches of the walk and after-walk screens | U28; U21–U23 |

---

## Resolutions

### Corrections to the plan and the requirements

The plan and the requirements were written before these reads. Where they disagree with iOS, iOS as shipped wins; each item points to its evidence.

1. **The manifest is imported when the link resolves, not when the overview opens** (S1 §1, §3.4; S2 §4.5). A tapped link, a paste, a link held through setup (and, on Android, the install referrer) all fetch, validate and save the Way at once, writing `way.json` and the first `accepted.json`. The share is listed under "Shared with you" from then on, even if no overview ever shows. Media gathers only when the overview presents. Nothing ever auto-Begins. Correct U28's "import starts only on an explicit overview open" and the Risk table's "import only on an explicit overview open".
2. **A fresh link doesn't open the overview fetching** (S2 §4.4). iOS switches to the Path tab, shows the toast `reaching for the walk…`, fetches, then opens the overview (which starts gathering) or shows the failure line as a toast. An overview shows the fetching line and a disabled Begin only if it is already open when the link arrives, because the import state is global. Reword R18's and AE4's "opens the Honor overview, fetching", U27's "otherwise open the overview, fetching", and AE5's "the overview for that share opens, fetching". The gate's AE4 row tests the toast-then-overview order.
3. **"finish this walk first" applies from the moment the walk screen opens** (S2 §6). The window runs from the walk screen in its pre-walk state (any mode, before Start), through recording, pause and meditation, until the finished walk's save completes or the walk is discarded. A Room probe alone misses the pre-Start screen. U27's "a synchronous Room probe, a pending Seek session, or a Begin in flight" becomes "the walk screen is up, or `:tracker` is walking" (the cold UI start with `:tracker` walking is Android-only and maps here). Starting any walk silently cancels an in-flight link import, and a manifest that resolves once the walk screen is up opens no overview.
4. **The setup hold ends at the first arrival at `PATH`** (S2 §3). iOS holds the last valid id in memory until the tab view first appears, which comes after the breath. On Android that's the first `PATH` arrival, not the `onboardingComplete` write (made before the breath). The hold covers `WELCOME`, `PERMISSIONS` and `BREATH`, and a set-up launch stopped on `PERMISSIONS`. The last link wins; nothing shows while it's held.
5. **Parking behind sheets** (S2 §4.3). A link closes the Ways sheet itself, then the overview presents. Behind the post-walk summary it waits until the walker closes the summary. iOS defines nothing for its other summary hosts (S2 open questions 1–2; owner decision 5 below).
6. **Begin is never gated on missing voices** (S1 §6.5; S4 §8). Begin is disabled only while fetching or gathering. In "some voices didn't arrive" Begin is already enabled; "walk without the missing voices" hides that line and both buttons and stops observing the downloads, and doesn't unlock anything. Disk full shows `not enough space on this phone to save these voices` in rust, with no buttons, and Begin enabled, even while other files are still downloading. U28's overview tests change to match.
7. **No screen names the missing voices** (S1 §6.6; S4 §8). A partial gather shows exactly `some voices didn't arrive` and the two buttons. The only per-moment sign is the preview's `their voice is still on its way here` for any absent voice. Drop U28's "a partial gather shows which voices are missing".
8. **AE3's place-card clause isn't iOS behavior** (S3 §15; S4 §10.3). No walk screen ever reads `voices returned to the trail`: a missing voice is skipped silently, a pin-opened card shows an empty waveform, and a missing photo shows its parchment plate. The words appear only in the Ways sheet row and the Settings → Ways row. AE3's summary clause holds, and the summary keeps more than it says: title, delta, ghost line, voice count and replies. AE3 should read: "if it was walked, its summary keeps its title, ghost line, delta and replies, and both Ways lists read 'voices returned to the trail'".
9. **A sweep never touches a Way a live walk is honoring** (S3 §13). iOS can't sweep during a walk at all. The plan's "the walked set includes live sessions and Begins in flight" would class such a Way as walked and delete its media mid-walk. Instead, skip any Way named by a live Honor session row or a Begin in flight entirely. Once finalize links it, it's walked, and the next sweep removes media only. U28's mid-walk sweep test asserts the media survives too.
10. **The launch sweep's order is a divergence to record** (S3 §14). iOS runs it with nothing ordering it against crash recovery, so a crash-recovered honoring of a just-expired share usually loses its Way. Android runs it at the end of `runAtLaunch`, after recovery and the finalize retry (the plan's order), which is the outcome iOS's own rule intends. A dated R5 divergence at the gate, filed upstream. The sweep's other triggers match iOS: the Ways sheet appearing, Settings → Ways loading, and after each delete there. The Data card doesn't sweep.
11. **Cross-host redirects are an Android addition** (S1 §3.2; S3 §2). iOS follows HTTPS redirects to any host, for the manifest and for media. U28's "refuses redirects to another host" is R6 hardening, not parity. So is refusing a media file's declared `Content-Length` over the cap before reading (iOS cuts that stream at the cap: the same bound, reached earlier; the manifest's declared-length check is iOS's own, S1 §3.3) and re-checking a media path on every delivery (iOS re-checks only on a relaunch rebuild). None changes what a genuine share does. Record them at the gate.
12. **Disk full keeps the files that landed** (S3 §8). Only the interrupted file is removed. U28's "partial files removed" holds only for that file, and the walker can Begin with the rest.
13. **The 60 MB total isn't enforced on receipt** (S3 §4). Only the sharing side (`TourBuilder`), the design doc and the worker state it. The downloader caps each file (15 MiB audio, 2 MiB photo) and each kind (12 audio, 20 photos), so a share's worst case is 220 MiB. U28's "unenforced, matching iOS" holds; its "upstream issue filed" was not true until this spec (see the table below).
14. **Settings → Ways' place and visibility** (S4 §2). The row is on the Data card, between "Export & Import" and "Maps", always shown, including `0 ways · 0.0 MB`. U28's change to `DataSettingsScreen.kt` has nothing to carry. The plan's "hidden while a walk is active or awaiting its Honor finalize step" has no iOS counterpart: Settings can't be reached during an iOS walk, and a finalize step doesn't exist there. It's R6 hardening on Android, where finalize outlives the walk screen.
15. **No sharer name, expiry or page link on any screen** (S4 §1). The manifest carries no sharer name; nothing shows the expiry or the page link. "They" stays anonymous. Don't build a "by <sharer>" or "expires in N days" line.
16. **The import's HTTP client is its own** (S1 §3.2, §9). 15 s connect and read, 30 s for the whole call, no retry, no cache. Android's shared 10/30/45 s client is wrong for it.
17. **The manifest version `v` is never checked** (S1 §2.5). Android must not add a version gate.
18. **A reply on the first honoring of a share is saved** (S4 §11). The share's folder exists from acceptance, so `setReply` succeeds where an own walk's first honoring fails (own-walk correction 3). Android's `setReply` already reproduces the split; pin it with a shared-Way test.

### By unit

**U27 — links and the install referrer** (S2)
- Parser: port `HonorLink` exactly (S2 §1), adding four ported tests: the scheme is never checked; percent-escapes are decoded before the id check; the query and fragment are ignored; the id is case-sensitive and only the host is folded. Whole-input matching (`Regex.matches`), never `find` with `$`. Port `HonorLinkTests` as `HonorLinkTest.kt`: the seven accepted forms, the seven rejections, both uppercase-host cases, and `%0A`.
- App Links: `honor.pilgrimapp.org` only, every path; `walk.pilgrimapp.org` is never claimed (AE4). A path that doesn't parse opens the app and does nothing. Release keeps the filter out until the flip.
- Routing: the hold (correction 4), parking (correction 5), the walk-screen refusal (correction 3), and the toast-then-overview order (correction 2). Consume once: `setIntent` after handling, as the widget precedent does (`MainActivity.kt:139-151@636cf5ce`). Owner decision 5's switch to the Path tab is U27's too: U28 parks behind the summary from every host, but its `openStoredWayOverview` opens the overview over whatever other screen is showing, and Close returns there.
- The toast (S2 §7): one at a time, 5 s, replaced or cleared by the next. Caption ink on `parchmentSecondary` at 0.95, radius 8, inner padding 16 × 8, outer 16 at the sides and 8 at the top. It slides in from the top and fades, and taps pass through it. It sits under the recovery banner, 4 apart, and shows on the walk screen during a walk. Opening the Ways sheet clears it and resets the state, but leaves the import running (matched as shipped).
- Paste (S2 §8; S4 §7): the Ways sheet's last section. Placeholder `paste a walk link`; `Open`, enabled only while the text parses and nothing is fetching; Return does nothing; the line under the field in fog while fetching, rust for failures; the field stays editable after a failure. The clipboard is never read. A link tapped while the sheet is open shows inline, with no toast.
- The install referrer (S2 §10): iOS has no counterpart. The outcome to mirror is a tapped link held through setup: at the first `PATH`, the Path tab, `reaching for the walk…`, then the overview or the failure toast; never an automatic Begin; once.

**U28 — import, media, sweep, Settings → Ways** (S1, S3, S4)
- Manifest: the shape, required fields without defaults, unknown keys ignored, `v` unchecked (S1 §2). The fetch, its client and its failure order (S1 §3): a malformed id is `notFound` before the network; a 404 is `notFound`; an expiry not later than now is `returnedToTrail`, checked before every count and range bound; everything else, offline included, is `unavailable`. There's no offline copy.
- Validation: the 29 bounds in S1 §4.3, each with its outcome, in iOS's check order, each with a rejecting test. Every encounter is validated, including kinds that are then skipped. Nothing is clamped except free text.
- The build (S1 §5): the `share:<id>` id, the title (places joined with ` → `, each cut to 80 graphemes, with a date fallback in the device's locale and zone, frozen at import), moments ordered by `(frac, id)`, sitting estimates from the first matching segment, both ends inclusive.
- Text (S1 §7): places and transcripts are trimmed with Swift's whitespace set (Kotlin's `trim()` differs in six code points: U+001C–U+001F, U+0085, U+200B) and cut by grapheme count (80, 600). Labels and the weather condition are cut (80, 64) but not trimmed. Icons are cut (64) and default to `mappin` only when absent. `tz_identifier` is kept whole.
- Import states and copy (S1 §6): the table in S1 §6.1, every string exact, all of it English and unlocalized on iOS. One import state for the app, shared by the sheet and the overview. A newer link cancels the older fetch, and a cancelled fetch writes no state (iOS's save can still land: matched as shipped).
- Media (S3 §2–§8): the path pattern `\A(?:audio/[0-9]{1,5}\.m4a|photos/[0-9]{1,5}\.jpg)\z`; files deduplicated, in moment order; caps of 15,728,640 bytes for `audio/` and 2,097,152 for everything else, refused only when strictly greater, counting a resumed partial; ceilings of 12 audio and 20 photos, the excess reported as failures; any 2xx accepted. One immediate retry per file per round, inside the worker, not WorkManager's backoff. Disk full is final per file only. `NetworkType.CONNECTED`, with no storage, battery or expedited settings. Unique work per Way: `KEEP` for a gather, `REPLACE` for "try again". Progress is `1 − unfinished/accepted`, failures counted as done, seeded from the files on disk, shown rounded half away from zero.
- Overview states (S4 §8.3): the full contract per state (line, colour, buttons, Begin). The first frame must not show an enabled Begin before the first gathering state.
- The sweep (S3 §12–§14): an expired share nobody walked is deleted whole; an expired walked share loses only `media/`; own-walk Ways are never touched; a Way a live walk is honoring is skipped (correction 9). Every sweep cancels the gathers of every id it touched.
- Settings → Ways (S4 §3–§5; S3 §15): one plain list, `no ways yet` when empty; own and shared Ways (stages excluded), newest acceptance first; rows `<medium date> · N.N MB` or `<medium date> · voices returned to the trail`; then `Delete all Ways`. Swipe to delete is unconfirmed. Delete all is confirmed: `Delete all Ways?`, `Their voices and photos leave this phone. Your own walks are untouched.`, `Delete` and `Cancel`. The Data card reads `N way(s) · N.N MB`. Sizes are decimal MB to one decimal under `Locale.US`; dates are medium style in the phone's zone. Each delete cancels the Way's downloads, then removes its folder and every link to it; the walks' reply recordings and honor events stay.
- The Ways sheet's shared sections (S4 §6–§7): "Shared with you" lists shared Ways, newest acceptance first, swept on every appearance, with the empty copy `no ways yet. Accept a shared walk, or walk one of yours again.` Each row is a button: the title (body, ink) over `<medium date> · <counts or 'voices returned to the trail'>` (caption, fog), with no chevron. A row hands over the stored Way with no fetch. The paste section is in U27's notes; its footer is `A walk someone shared with you, from walk.pilgrimapp.org.`.
- Accessibility (S4 §13): the labels and reading order per surface. iOS adds no hints and announces no import line.
- Logging: nothing from the manifest, the id or a decode exception (kotlinx messages quote input). iOS prints its decode error; Android doesn't.

**Shared-walk branches of U21 — the overview** (S4 §9)
- The title is `<start> → <end>`. The departure line is the long date and short time in the phone's zone; the preview's hours use the Way's zone (matched as shipped, filed).
- The distance is the shared route's length; the duration falls back to the route's span.
- The voices toggle is enabled whenever the Way declares voices, downloaded or not.
- The preview subline appends the street name. A missing voice reads `their voice is still on its way here`; a missing photo shows the plate above `tap the photo to see it whole`.

**Shared-walk branches of U22 — on the walk** (S4 §10)
- The card subline appends the street name: `here · <place>`, `<distance> away · <place>`, or `<place>` alone before the first fix.
- An estimated sitting reads `they sat here about N minutes` on the card; the meditation screen drops "about" (matched as shipped, filed).
- An unlabelled shared waypoint has an empty kicker (the importer stores `""`, not nil, so owner decision 4's "no kicker" for Android's own nil labels doesn't apply); a missing or unknown icon draws `mappin`; the body is always `A place they marked.`
- A missing rest length reads `they rested here 0 minutes`; a missing voice length reads `0:00 / 0:00`.
- Missing media is silent. A card resolves its media once per appearance at the top of the queue. The arrival card's voices count only what played.

**Shared-walk branches of U23 — after the walk** (S4 §12)
- The summary's three cases (stored, expired and walked, deleted) are in S4 §12.1. `N voices along the way` counts declared voices.
- The seal keeps the Way's line through expiry and loses it on deletion (owner decision 3 of the own-walk spec).
- One prompt lexicon for own and shared walks (confirms own-walk correction 21). The summary, journal, scenery, milestones and seal have no other shared branch.
- The arrival label `Walked their way: <title>` is stored uncut, from a title that can reach 163 characters (matched as shipped, filed; see S1-D1).

### Android additions to record at the gate

Each is a dated R5 or R6 row, with this spec as the evidence:
- the launch sweep ordered after recovery and the finalize retry (correction 10, R5);
- cross-host redirect refusal for the manifest and media, the media download's early `Content-Length` refusal, and the per-delivery path re-check (correction 11, R6). The manifest's declared-length refusal is not among them: it is iOS's own check (S1 §3.3, row 6; `WayImporter.swift:62@7c200bf`);
- Settings → Ways hidden while a walk or its finalize step is pending (correction 14, R6);
- WorkManager resuming a gather after a force-stop, where iOS waits for the next overview open (S3 open question 3, R6 platform equivalent);
- the media download's timeouts, which iOS leaves at platform defaults (S3 open question 4).

Recorded during U28:
- **2026-10-01, U28. Amends the row above: the redirect refusal and the path re-check, as built.** A redirect off the walk host is refused before anything connects to it (OkHttp's own following is off, and a hop on the walk host is followed by hand), so no DNS lookup, socket, or handshake reaches the other host. The path re-check is stricter than iOS's `mediaPathPattern` (`WayMediaDownloader.swift:98@7c200bf`, `[0-9]{1,5}`): Android rebuilds each path from its integer index (`WayMediaRules.canonicalPath`), so it also refuses a leading zero such as `audio/007.m4a`, which iOS admits. The importer never writes one, so no genuine share reaches the difference.
- **2026-10-01, U28. Two unreachable date-parser differences, of S1 §4.4's three** (open question 2, recommended: accept `java.time`'s set). Foundation's `isoDate` reads an offset without a colon (`2026-08-01T09:00:00+0200`) and rolls an impossible day on (`2026-02-30` as March 2); Android refuses both. The third matches: Android's parser is case-sensitive and refuses a lower-case `t` or `z`, as iOS does. No app and not the worker writes any of these. Pinned by `WayImporterTest` "strings neither app writes do not parse".
- **2026-10-01, U28. Three unreachable decode differences from Foundation's `JSONDecoder`** (S1 §2.4; open questions 3–5). kotlinx reads a quoted number (`"ts":"1000"`), which iOS refuses; kotlinx refuses an integer written with a fraction (`1000.0`), which iOS reads; a repeated key keeps its last value on Android and its first on iOS. `JSON.stringify` writes none of them. One row of S1 §2.4's table was wrong: kotlinx reads an integer written with an exponent (`1e3` as 1000), as iOS does, so that one matches. Pinned by `WayImporterTest`'s "Decode differences from Foundation's" tests.
- **2026-10-01, U28. R6 platform equivalent: Settings → Ways wears the app's settings card, and its swipe is Material's** (S4 §3–§5). iOS's `WaysListView` is a system grouped `List`; Android's rows sit on the parchment card the other Settings screens use. A row deletes on a trailing swipe past half its width, at once and unconfirmed, as `.onDelete` deletes; there is no reveal-then-tap "Delete" button, and TalkBack offers "Delete" as the row's action. This extends U21's row for the Ways sheet and the picker (own-walk spec, "Gate rows recorded during implementation").

Recorded during U27:
- **2026-10-01, U27. R6 platform equivalent: a link leaves a summary, the Ways sheet or an overview where it stands.** iOS switches to the Path tab beneath any sheet (`MainTabView.swift:95,103`); Android's sheets are routes, and switching beneath one would close it. Every success opens over Path, so the difference shows only when a link fails under an overview opened from the Journal or Goshuin: its Close returns there, where iOS's lands on Path (S2 §5 row 7).
- **2026-10-01, U27. R6 platform equivalent: a link always lands in Pilgrim's own task, in its one `MainActivity`.** iOS has one scene, which every link reaches. An Android app may open a link without a new task, which starts `MainActivity` in that app's task; and a link opened over another activity in Pilgrim's task (a Custom Tab, the photo picker, the share sheet) starts a second `MainActivity` over it. Either instance finishes before it draws and hands the link to the `MainActivity` in Pilgrim's own task (`FLAG_ACTIVITY_NEW_TASK | CLEAR_TOP | SINGLE_TOP`, `ownTaskIntent` in `MainActivity.kt`), which takes it through `onNewIntent`, or starts that task if none runs. Whatever Pilgrim's task showed over `MainActivity` closes, where an iOS link leaves an in-app Safari view presented. The widget, the walk notification and the launcher are untouched. The router also keeps each live nav host's screen, so one Activity going hands the screen back to the other instead of holding a link. The device pass checks a link from a messenger that opens without a new task, and one over a Custom Tab.
- **2026-10-01, U27. A link for the share whose overview is open keeps that overview: matched** (S2 §5 rows 8–9). The route stays, so its scroll and an open moment preview stay, and the Way gathers again (`HonorImportCoordinator.gatherShownAgain`), a running download carrying on. One difference remains: the open overview keeps the Way it loaded, where iOS's sheet takes the re-imported value (`Way.swift:222`). A share's manifest doesn't change, so only a title rebuilt under a new locale or zone could differ, until the overview next opens.
- **2026-10-01, U27. A "walk this again" that builds no Way drops a link's Way parked behind the summary: matched as shipped.** iOS's `walkAgain` overwrites the park with nil (`MainCoordinatorView.swift:308-309`), so the link's Way never opens; Android drops it the same way before the summary closes. It belongs with [pilgrim-ios #113](https://github.com/walktalkmeditate/pilgrim-ios/issues/113) (a link that loses its overview); the comment adding it there is still to post.
- **2026-10-01, U27. The toast and the recovery banner share one host, over every screen: matched, with two differences** (S2 §7.2). A link from another tab slides its toast in once and keeps it through the switch to Path, and a new line on a showing toast crossfades as its box resizes (350 ms ease-in-out). The differences:
  - Under an overview or a sitting, iOS's sheet or cover simply covers the toast. Android's host draws above its routes, so the toast slides out there, and slides back in if the walker returns within its 5 s, where iOS's is revealed in place.
  - The recovery banner, now in the same host, still shows only over the Path tab, as it did before U27. iOS's overlay shows it over every tab. It's armed at launch, which lands on Path, and it leaves after 4 s.
- **2026-10-01, U27. R19 details, Android-only.** A link the walker tapped in the same process wins over the install referrer: the referrer is marked read and opens nothing. If the Play Store never answers during the first launch's setup, a later launch reads it again even once setup is done: the first read began during setup, so it isn't an updater's. A later Activity in the same process counts as a later launch. Either way the referrer opens once at most, and "read" is saved before the id is routed.
- **2026-10-01, U27. Owner decisions 3 and 5 built as recommended.** No link toast or import line is announced, and the toast isn't hosted on the meditation screen (decision 3). A link parks behind the summary from every host; from any other screen it switches to the Path tab and opens there (decision 5). The owner's calls are still to record above.

---

## Matched as shipped, and filed upstream

Android ports each of these exactly as iOS ships it, the parity gate reads it as `match`, and an iOS fix that lands before the gate folds in. "Cluster refs" are each cluster's own defect numbers.

| Issue | What | Cluster refs |
|---|---|---|
| [pilgrim-ios #113](https://github.com/walktalkmeditate/pilgrim-ios/issues/113) | Honor links can lose their overview or their answer: under an untracked sheet (needs an iPhone check); a failure under the post-walk summary; the toast riding onto a walk; the pre-walk refusal (a question); the Ways sheet opened mid-fetch; a second link not holding Begin | S2-D1, S2-D4, S2-D2, S2-D7, S2-D5 = S1-D9, S4-D4 |
| [pilgrim-ios #114](https://github.com/walktalkmeditate/pilgrim-ios/issues/114) | Imports refetch held shares, save cancelled ones, and show the wrong failure copy: a link never consults the store (and an expired held share); a superseded import still saves; a failed save reads "couldn't reach the walk"; a tourless share reads as a wrong link | S1-D4, S2 open question 4, S1-D5, S1-D7 = S2-D6 = S4-D3, S1-D8 |
| [pilgrim-ios #115](https://github.com/walktalkmeditate/pilgrim-ios/issues/115) | The launch sweep races crash recovery; an expired walked share gathers again and its preview says the voices are on their way; a late delivery brings swept media back; the Data card counts unswept Ways | S3-D1, S3-D2 = S4-D2, S3-D4, S3-D7, S3-D10 = S4-D6 |
| [pilgrim-ios #116](https://github.com/walktalkmeditate/pilgrim-ios/issues/116) | Media downloads: no share total; any 2xx body becomes the file; disk full has no way forward; "some voices didn't arrive" covers photos and offers a retry that can't help | S3-D5, S3-D6, S3-D9, S1-D6 = S3-D8 = S4-D9 |
| [pilgrim-ios #117](https://github.com/walktalkmeditate/pilgrim-ios/issues/117) | The arrival label can break the walker's next share at the worker's 100-character limit; the worker accepts shares the importer refuses | S1-D1, S1-D3 |
| [pilgrim-ios #118](https://github.com/walktalkmeditate/pilgrim-ios/issues/118) | Sharer text: no control or bidirectional character stripped; uneven trimming ("they walked this in ."); `tz_identifier` uncut; an empty kicker on an unlabelled waypoint | S1-D2, S4-D10 |
| [pilgrim-ios #108](https://github.com/walktalkmeditate/pilgrim-ios/issues/108) | (comment) The link toasts and the refusal under the sitting are never announced; the import lines are silent; the shared row reads its "·"; the card header hides the street and "about" | S2-D3, S4-D8 |
| [pilgrim-ios #109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109) | (comment) "voices returned to the trail" keyed on the media folder misfires for shares too; the meditation screen drops "about" | S3-D3 = S4-D1, S4-D7 |
| [pilgrim-ios #110](https://github.com/walktalkmeditate/pilgrim-ios/issues/110) | (comment) The shared overview's departure line uses the phone's zone while its preview uses the Way's | S4-D5 |

Android differs on two items, each recorded at the gate: #115 item 1 can't arise, because Android sweeps only after recovery has written its links (correction 10); and #113 item 1 waits on its iPhone check (owner decision 5). If the owner chooses an alternative in decisions 1–3, the matching issue gets a comment saying so.

---

## Owner decisions

None of these has iOS behavior Android can simply carry over. Each has a recommendation; the owner's call is recorded here when made.

1. **Sharer text** (S1 §7.5; S4 resolution 8). iOS strips no control or bidirectional characters from a share's places, labels, transcripts or weather, and neither does the worker. No Android-only screen shows the text.
   - *Recommended:* parity, plus the upstream issue on sharer text. If iOS adds a filter, it folds in, applied once at import so all eight display sites agree, including the stored arrival label.
   - *Alternative:* isolate the text at display time now, as a dated R6 addition.
2. **Re-gathering an expired, walked share** (S3-D2, S4-D2). iOS's gather has no expiry check. Opening such a share from the Ways sheet downloads the sharer's voices again, for as long as the worker serves them.
   - *Recommended:* match and file (upstream-first).
   - *Alternative:* refuse a gather once `expires <= now`. That diverges, but keeps the sharer's promise.
3. **Announcing link toasts and import lines** (S2 open questions 5–6; S4 open question 4). iOS announces none of them to VoiceOver, and its refusal is invisible under the meditation cover.
   - *Recommended:* match, and add both to pilgrim-ios #108. The toast isn't hosted on Android's meditation screen, which matches the invisible refusal.
   - *Alternative:* a polite live region on Android, recorded at the gate.
4. **Ways' size** (S4 §2.3, open question 1). iOS reports allocated bytes; Android's `WayStore.diskUsage` adds up file lengths. On a full share the difference can flip the first decimal of `N.N MB`.
   - *Recommended:* match iOS with `Os.stat(...).st_blocks × 512`. It's cheap and exact.
5. **A link while another screen is up** (S2 open questions 1–2, S2-D1). iOS parks a link only behind its post-walk summary. Under its other sheets (the journal's summary, Goshuin, Settings) the code suggests the overview never appears. That needs an iPhone check. Android's summary is one route reached from Home, Goshuin, the post-walk finish, Recordings and the widget, and its other screens are routes, not sheets.
   - *Recommended:* park behind the summary from every host, which is the intent iOS states in its own comment. From any other screen, switch to the Path tab and open as iOS does from the tab view. Settle iOS's actual behavior with the iPhone check before the gate.

## Open questions iOS leaves open

- **Does iOS's ephemeral session cache `tour.json` in memory?** (S1 open question 1.) The worker sends `Cache-Control: public, max-age=3600`, and an ephemeral `URLSession` keeps an in-memory cache. If iOS re-opens a share offline within the hour, Android (no cache) can't. One iPhone run settles it before U28.
- **Foundation's three date-parser quirks** (S1 §4.4). None is reachable from either app or the worker. Recommended: accept `java.time`'s set and record three unreachable differences at the gate, rather than hand-write a parser.
- **Unreachable decode differences** (S1 open questions 3–5): quoted numbers, spans with the same `startFrac`, duplicate keys. U28 pins each with a test.
- ~~**Task restore after process death during onboarding** (S2 open question 3).~~ Resolved in U27 (2026-10-01): suppressed, matching R18. A restored or Recents-relaunched task never replays its link. The device check hasn't run; it's in the combined device pass.
- **Does the worker serve an expired share's media before its 03:00 UTC cleanup?** (S3 open question 2; S4 open question 3.) It decides whether re-gathering an expired walked share re-downloads or dead-ends. One device check, after a share expires and before the cron runs.
- **The seal reveal with a link** (S2 open question 7), and **SwiftUI's double delivery** of one universal link (S2 open question 8): untraced, benign for Android.
- **Does an offline gather fail at once on iOS, or wait?** (added 2026-10-01, U28 review.) S3 §16 says a gather started offline ends in `.mediaMissing` at once, but iOS's download session is a background `URLSessionConfiguration`, which Apple documents as always waiting for connectivity. Android's work waits under `NetworkType.CONNECTED`, so its overview reads `gathering their voices · N%` with Begin held until a network returns. One iPhone check at the gate: open a partly gathered share offline. If iOS waits too, correct S3 §16's row; if it fails at once, record Android's wait as a divergence.

---

## S1. Shared walks in: the tour manifest, the importer, and the import states

| field | value |
|---|---|
| **iOS pin** | `v2.0.0` = `7c200bf` |
| **Android HEAD** | `636cf5ce` |
| **Worker** | `pilgrim-worker` `main` @ `2a4f5d0` (read-only, current) |
| **Feeds** | U27 (link and paste entry into the import), U28 (importer, validation, import states and copy) |
| **Lenses** | behavior, UI/visual (strings and where each shows), data, edge cases, all applied to every file below |

iOS files read in full at the pin: `Pilgrim/Models/Honor/TourManifest.swift`, `WayImporter.swift`, `HonorImportReducer.swift` (it also holds `HonorImportState` and `HonorImportCopy`), `HonorLink.swift`; the import half of `Pilgrim/Scenes/Root/MainCoordinatorView.swift` and `MainTabView.swift`; the import-line parts of `Pilgrim/Scenes/Honor/HonorWaysSheet.swift` and `HonorOverviewView.swift`; the published sets of `WayMediaDownloader.swift` that drive the reducer; the tests `UnitTests/Honor/WayImporterTests.swift`, `HonorImportReducerTests.swift`, and the import-race tests in `MainCoordinatorHonorTests.swift`. Worker files read: `src/types.ts`, `src/generators/tour.ts`, `src/index.ts` (the `tour.json` route and `serveAsset`), `src/handlers/share.ts`, `src/handlers/validate-share.ts`, `src/handlers/expiry.ts`, `wrangler.toml`.

The Way model, `way.json`, `WayStore` (ids, `save`, `accepted.json`, the sweep) are specified in the own-walk spec, cited here as "own-walk spec A §n". The media download itself (`WayMediaDownloader`: caps, retry, disk-full, the deleted-Way guard) belongs to another S-cluster; this cluster quotes only the four sets the import reducer reads.

Where a claim rests on Foundation's runtime behavior rather than on Pilgrim source, it says so and gives the result of a scratch probe run on macOS 26 Foundation (Swift 6.3.3). The iOS deployment target is 18.0 (`IPHONEOS_DEPLOYMENT_TARGET = 18.0` in `Pilgrim.xcodeproj/project.pbxproj@7c200bf`), which already uses the same rewritten `JSONDecoder`, so the probe stands in for iOS. The JVM side was probed with JDK 17.

### 1. The path a share takes, end to end

1. A link or a pasted id becomes a share id (`HonorLink.parse`, U27's cluster) and reaches `MainCoordinator.openWay(shareId:)`.
2. `openWay` refuses during a walk, otherwise sets the import state to `.fetching` and runs the injected `importShare`, which is `WayImporter().importShare(id:)` in production:

```swift
    var importShare: (String) async throws -> Way = { try await WayImporter().importShare(id: $0) }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:33@7c200bf

3. `importShare` validates the id, fetches `https://walk.pilgrimapp.org/<id>/tour.json`, decodes a `TourManifest`, builds a `Way` with `way(from:shareId:now:)`, and **saves it to the store before returning** (§3). A successful import is the acceptance: `accepted.json` is written then, not at Begin.
4. On success the coordinator opens the overview (or parks it behind a sheet), and `gather` starts the media download and maps the downloader's sets into the import state through `HonorImportReducer` (§6).

Nothing in this path reads the store before fetching. A link to a share that is already accepted always goes to the network again (§8.5).

### 2. The manifest's shape (`TourManifest`)

#### 2.1 What iOS declares

```swift
/// Mirrors `pilgrim-worker/src/types.ts` `TourManifest` (and `RoutePoint`,
/// `TourEncounter`) exactly enough for `WayImporter` to build a `Way` — only
/// the fields it reads are declared, and every field the worker marks
/// optional (or has promised to add later) stays `Optional` here so an old
/// or a not-yet-widened manifest still decodes.
struct TourManifest: Decodable {

    struct RoutePoint: Decodable, Equatable {
        let lat: Double
        let lon: Double
        let alt: Double
        let ts: Int

        enum CodingKeys: String, CodingKey { case lat, lon, alt, ts }
    }

    /// `type` decodes as a plain `String` (not an enum) so an encounter kind
    /// the worker adds after this build ships still decodes instead of
    /// failing the whole manifest; `WayImporter` skips kinds it doesn't know.
    struct Encounter: Decodable, Equatable {
        let type: String
        let frac: Double
        let end_frac: Double?
        let n: Int?
        let duration: Double?
        let label: String?
        let icon: String?
        let minutes: Int?
        let lat: Double?
        let lon: Double?
        let place: String?
        let transcript: String?

        enum CodingKeys: String, CodingKey {
            case type, frac, end_frac, n, duration, label, icon, minutes, lat, lon, place, transcript
        }
    }

    struct Sitting: Decodable, Equatable {
        let start_frac: Double
        let end_frac: Double
        let duration: Double?

        enum CodingKeys: String, CodingKey { case start_frac, end_frac, duration }
    }

    struct Stats: Decodable, Equatable {
        let active_duration: Double?

        enum CodingKeys: String, CodingKey { case active_duration }
    }

    /// `kind` is a plain `String` for the same reason `Encounter.type` is.
    struct ActivitySegment: Decodable, Equatable {
        let kind: String
        let start_frac: Double
        let end_frac: Double

        enum CodingKeys: String, CodingKey { case kind, start_frac, end_frac }
    }

    let v: Int
    let place_start: String?
    let place_end: String?
    let weather_condition: String?
    let weather_temperature: Double?
    let start_date: String
    let tz_identifier: String?
    let expires: String
    let route: [RoutePoint]
    let encounters: [Encounter]
    let meditation: [Sitting]
    let activity_segments: [ActivitySegment]?
    let stats: Stats?

    enum CodingKeys: String, CodingKey {
        case v, place_start, place_end, weather_condition, weather_temperature,
             start_date, tz_identifier, expires, route, encounters, meditation, activity_segments, stats
    }
}
```
> Pilgrim/Models/Honor/TourManifest.swift:3-82@7c200bf

It is decoded by a plain `JSONDecoder()` (`WayImporter.swift:78`): default key strategy (keys exactly as written, snake_case), no date strategy (dates stay strings until `isoDate`, §4.2).

#### 2.2 Field table

"Required" means a missing key or a JSON `null` fails the whole decode, which `importShare` reports as `.unavailable` ("couldn't reach the walk").

| Key | Swift type | Required | Read by the importer | Worker emits (`tour.ts`) |
|---|---|---|---|---|
| `v` | `Int` | yes | **never** (no version check) | always `1` |
| `place_start`, `place_end` | `String?` | no (null or absent is nil) | title | always present, `string` or `null` |
| `weather_condition` | `String?` | no | weather | only when the sharer's stats carry one |
| `weather_temperature` | `Double?` | no | weather, validated | same |
| `start_date` | `String` | yes | `departedAt`, title fallback | the sharer app's string, verbatim |
| `tz_identifier` | `String?` | no | `tzIdentifier`, unvalidated, uncapped | when the sharer sent one |
| `expires` | `String` | yes | `expires`, the returned-to-trail check | always (`Date.toISOString()`, milliseconds, `Z`) |
| `route[]` | `[RoutePoint]` | yes | route | the sharer's route, verbatim |
| `route[].lat`, `.lon`, `.alt` | `Double` | yes, all three | route, validated | yes |
| `route[].ts` | `Int` | yes | `t`, validated, ordered | yes |
| `encounters[]` | `[Encounter]` | yes | moments | always, `departure` first and `arrival` last |
| `encounters[].type` | `String` | yes | the kind switch | `departure`, `voice`, `ambience`, `photo`, `waypoint`, `rest`, `arrival` |
| `encounters[].frac` | `Double` | yes | `frac`, validated | yes |
| `.end_frac`, `.n`, `.duration`, `.label`, `.icon`, `.minutes`, `.lat`, `.lon`, `.place`, `.transcript` | optional | no | per kind (§5.4) | per kind (below) |
| `meditation[]` | `[Sitting]` | yes | sittings | always (since the first manifest, 2026-08-10) |
| `meditation[].start_frac`, `.end_frac` | `Double` | yes | `start_frac` only (both validated) | yes |
| `meditation[].duration` | `Double?` | no | sitting minutes | since 2026-09-03 |
| `activity_segments[]` | `[ActivitySegment]?` | no | spans | always on current workers |
| `stats` | `Stats?` | no | `theirActiveSeconds` | always an object, possibly `{}` |
| `stats.active_duration` | `Double?` | no | `theirActiveSeconds`, validated | only when the sharer toggled "duration" |

Keys the worker writes that iOS does not declare, and so ignores: `theme`, `time_bucket`, `units`, `journal`, `total_distance_m`, `encounters[].dwell`, `stats.distance`, `stats.steps`, `stats.elevation_ascent`. `JSONDecoder` ignores undeclared keys. `total_distance_m` in particular is never read: the Way's length is recomputed from the route (§5.2).

#### 2.3 The worker's side of the contract

```ts
export type TourEncounter =
  | { type: "departure"; frac: 0 }
  | { type: "voice"; frac: number; end_frac: number; n: number; duration: number; dwell: number; place?: string; transcript?: string; lat?: number; lon?: number }
  | { type: "ambience"; frac: number; end_frac: number; n: number; duration: number; place?: string; lat?: number; lon?: number }
  | { type: "photo"; frac: number; n: number; dwell: number; lat?: number; lon?: number }
  | { type: "waypoint"; frac: number; label: string; icon: string; dwell: number; lat?: number; lon?: number }
  | { type: "rest"; frac: number; minutes: number; dwell: number }
  | { type: "arrival"; frac: 1 };

export interface TourManifest {
  v: 1;
  theme: "light" | "dark";
  time_bucket: string;
  place_start: string | null;
  place_end: string | null;
  weather_condition?: string;
  weather_temperature?: number;
  units: "metric" | "imperial";
  journal?: string;
  start_date: string;
  tz_identifier?: string;
  expires: string;
  route: RoutePoint[];
  total_distance_m: number;
  encounters: TourEncounter[];
  // Optional: stored manifests from before this field existed predate it.
  meditation: { start_frac: number; end_frac: number; duration?: number }[];
  activity_segments: { kind: "meditation" | "talk"; start_frac: number; end_frac: number }[];
  stats: {
    distance?: number;
    active_duration?: number;
    steps?: number;
    elevation_ascent?: number;
  };
}
```
> pilgrim-worker src/types.ts:112-146@2a4f5d0

The worker's "Optional" comment refers to `duration` inside `meditation`, not to the array. `git log -S` on `src/generators/tour.ts` dates each optional field. These dates are what R11's "shares made before the worker's 2026-09-03 deploy" means:

| Field | First in `tour.json` |
|---|---|
| `meditation[]`, `encounters[].end_frac`, `rest` encounters, `tz_identifier`, `stats` | 2026-08-10 (`b7efce4`, the first manifest) |
| `activity_segments` | 2026-08-10 (`1560ca8`) |
| `voice`/`ambience` `place` | 2026-08-10 (`ebab4fc`) |
| `lat`/`lon` on voices, ambience, photos, waypoints; `meditation[].duration` | 2026-09-03 (`d638bbb`) |
| `meditation[].duration` clamped to 6480 s | 2026-09-03 (`ad21510`) |
| `voice` `transcript` | 2026-09-03 (`bedfed0`) |

How the worker fills each field (the importer trusts none of it, but the values bound what a real share can contain):

```ts
  for (const r of payload.tour?.recordings ?? []) {
    if (r.kind === "spoken") {
      encounters.push({
        type: "voice",
        frac: toFrac(r.start_ts),
        end_frac: toFrac(r.end_ts),
        n: r.n,
        duration: r.duration,
        dwell: Math.min(Math.max(Math.round(r.duration), VOICE_DWELL_MIN), VOICE_DWELL_MAX),
        place: recordingPlaces?.[r.n] ?? undefined,
        transcript: tourTranscript(r.transcription),
        lat: r.lat,
        lon: r.lon,
      });
    } else {
      encounters.push({
        type: "ambience",
        frac: toFrac(r.start_ts),
        end_frac: toFrac(r.end_ts),
        n: r.n,
        duration: r.duration,
        place: recordingPlaces?.[r.n] ?? undefined,
        lat: r.lat,
        lon: r.lon,
      });
    }
  }

  (payload.photos ?? []).forEach((photo, i) => {
    encounters.push({ type: "photo", frac: toFrac(photo.ts), n: i + 1, dwell: PHOTO_DWELL, lat: photo.lat, lon: photo.lon });
  });

  for (const wp of payload.waypoints ?? []) {
    encounters.push({
      type: "waypoint",
      frac: toFrac(wp.ts ?? route[0].ts),
      label: wp.label,
      icon: wp.icon,
      dwell: WAYPOINT_DWELL,
      lat: wp.lat,
      lon: wp.lon,
    });
  }

  for (const pause of payload.pauses ?? []) {
    const seconds = pause.end_ts - pause.start_ts;
    if (seconds < MIN_REST_SECONDS) continue;
    encounters.push({
      type: "rest",
      frac: toFrac(pause.start_ts),
      minutes: Math.round(seconds / 60),
      dwell: REST_DWELL,
    });
  }

  encounters.sort((a, b) => ("frac" in a ? a.frac : 0) - ("frac" in b ? b.frac : 0));
```
> pilgrim-worker src/generators/tour.ts:37-92@2a4f5d0

```ts
    encounters: [
      { type: "departure", frac: 0 },
      ...encounters,
      { type: "arrival", frac: 1 },
    ],
    meditation: payload.activity_intervals
      .filter((iv) => iv.type === "meditation")
      .map((iv) => ({
        start_frac: toFrac(iv.start_ts),
        end_frac: toFrac(iv.end_ts),
        duration: Math.min(MAX_SITTING_SECONDS, Math.max(0, Math.round(iv.end_ts - iv.start_ts))),
      })),
    activity_segments: payload.activity_intervals.map((iv) => ({
      kind: iv.type,
      start_frac: toFrac(iv.start_ts),
      end_frac: toFrac(iv.end_ts),
    })),
    stats: {
      distance: toggled.has("distance") ? payload.stats.distance : undefined,
      active_duration: toggled.has("duration") ? payload.stats.active_duration : undefined,
      steps: toggled.has("steps") ? payload.stats.steps : undefined,
      elevation_ascent: toggled.has("elevation") ? payload.stats.elevation_ascent : undefined,
    },
```
> pilgrim-worker src/generators/tour.ts:115-137@2a4f5d0

Facts that follow:

- Voice and ambience `n` is the recording's 1-based index; the worker's share validation enforces `r.n === i + 1` and at most 12 recordings (`validate-share.ts:9,137-145@2a4f5d0`). Photo `n` is the photo's 1-based position in the payload (`n: i + 1`), at most 20 photos (`validate-share.ts:6,81`).
- `rest` carries no `lat`/`lon` and no `n`. `ambience` carries no `dwell` and no `transcript`.
- `JSON.stringify` drops `undefined`, so an absent optional is an absent key, never `null`. `place_start`/`place_end` are the exception: `payload.place_start ?? null` writes `null`.
- `expires` comes from `meta.expires`, a `toISOString()` value (`share.ts:49-57,90@2a4f5d0`), so it always carries milliseconds and `Z`.
- `start_date`, the labels, icons, `place_start`/`place_end`, `weather_condition`, and `tz_identifier` are whatever the sharer's app posted. The worker checks `start_date` only for presence (`if (!p.start_date)`, `validate-share.ts:20`), caps waypoint labels at 100 and icons at 50 (`:4-5,68-73`), and checks nothing about the places, the weather condition, or the zone.
- A static (non-interactive) share has no `tour.json` at all: `buildTourWrites` returns nothing when `payload.tour` is absent (`share.ts:212@2a4f5d0`), so its id answers 404 (§8.9).

#### 2.4 Decoding strictness, measured

`JSONDecoder` is stricter than its declared types suggest in some places and looser in others. Probe results (scratch Swift script against macOS 26 Foundation; not Pilgrim source):

| JSON input | Swift result | JVM/kotlinx expectation for the port |
|---|---|---|
| `Int` field given `1.0`, `1e3`, `1E2` | decodes as `1`, `1000`, `100` | kotlinx rejects a fraction for `Int`/`Long`, and reads an exponent as iOS does (`1e3` as 1000; measured in U28, 2026-10-01) |
| `Int` field given `1.5` | throws | throws |
| `Int` field given `"5"` (quoted) | throws | kotlinx's lexer accepts quoted numbers by default (unverified here; check with a test) |
| `Int` field given `9223372036854775808` | throws | throws |
| `Double` field given `1e400` | throws (the whole decode fails) | parses to `Infinity`, then every range check rejects it (§4) |
| `String` field given `5`, `true`, or `null` | throws | throws (default, non-lenient `Json`) |
| optional given `null`, or absent | nil | null with `explicitNulls = false` |
| duplicate key `{"v":"a","v":"b"}` | **first** value wins (`"a"`) | not pinned; avoid relying on it |

What reaches Android in practice: `JSON.stringify` never writes an integral number with a fraction or exponent below 1e21, never quotes a number, and never repeats a key, so none of the differing rows can come from the worker. They matter only for hand-written fixtures and hostile manifests. Every one of them ends in "couldn't reach the walk" on both platforms except two: an integral number written with a fraction, which iOS accepts and Android refuses, and a quoted number, which Android accepts and iOS refuses (both measured in U28).

The one Kotlin trap that does matter: a `TourManifest` property with a default value turns a missing required key into that default instead of a failure. Required keys (`v`, `start_date`, `expires`, `route`, `encounters`, `meditation`, `RoutePoint.lat/lon/alt/ts`, `Encounter.type/frac`, `Sitting.start_frac/end_frac`, `ActivitySegment.kind/start_frac/end_frac`) must have no default. Precedent for the house `Json` (`ignoreUnknownKeys = true`, `explicitNulls = false`): `app/src/main/java/org/walktalkmeditate/pilgrim/di/NetworkModule.kt:66-69@636cf5ce`, and `WayJson`'s own instance at `domain/honor/WayJson.kt:43-46@636cf5ce`.

#### 2.5 Versioning

There is none on the reading side. `v` is required, so a manifest without it fails, but its value is never compared: a `"v":2` manifest imports exactly like a `"v":1`. Forward compatibility rests on three things only: undeclared keys are ignored, `Encounter.type` and `ActivitySegment.kind` are strings so new kinds decode and are skipped (§5.4, §5.6), and every new field is optional. A new encounter kind that lacks `frac` would fail the whole decode, since `frac` is required for every encounter.

### 3. Fetching the manifest (`WayImporter.importShare`)

#### 3.1 The errors, the constants, and the id check

```swift
enum WayError: Error, Equatable { case notFound, returnedToTrail, unavailable, diskFull }

struct WayImporter {

    static let maxRoutePoints = 2000
    static let maxEncounters = 200
    static let maxManifestBytes = 2 * 1024 * 1024
    static let baseURL = URL(string: "https://walk.pilgrimapp.org")!
```
> Pilgrim/Models/Honor/WayImporter.swift:3-10@7c200bf

```swift
    /// The importer enforces the id shape itself; it must never depend on a
    /// UI-layer parser having run first.
    static func isShareId(_ id: String) -> Bool {
        id.range(of: "\\A[A-Za-z0-9_-]{10}\\z", options: .regularExpression) != nil
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:25-29@7c200bf

- Four errors. The importer throws only the first three; `.diskFull` comes from the media downloader through the reducer (§6.2).
- The id is exactly 10 characters of `[A-Za-z0-9_-]`, anchored `\A…\z` (no trailing newline). `HonorLink` uses the same pattern (`HonorLink.swift:8@7c200bf`), but the importer checks again on its own. Android: `Regex.matches` gives whole-input anchoring, as `WayStore.isValidId` already does (`data/honor/WayStore.kt:380@636cf5ce`).
- The fetch host is always `walk.pilgrimapp.org`, also for a link that came in on `honor.pilgrimapp.org`.

#### 3.2 The session: its own, ephemeral, 15 s and 30 s

```swift
    /// The overview's toast promises a quick answer ("reaching for the
    /// walk…") and expires at 5 s — `.shared`'s default multi-minute
    /// timeouts would leave a hung request outliving both the copy and the
    /// toast, so a share fetch gets its own short-lived, tightly-timed
    /// session instead.
    private static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()
```
> Pilgrim/Models/Honor/WayImporter.swift:31-41@7c200bf

- `timeoutIntervalForRequest = 15` is URLSession's idle timeout: it restarts whenever data arrives. `timeoutIntervalForResource = 30` caps the whole fetch. The nearest OkHttp shape is a 15 s connect and read timeout with a 30 s call timeout. Android's shared client is 10 / 30 / 45 s (`di/NetworkModule.kt:162-164@636cf5ce`), so the import needs its own client, as the weather client already has (`:166-168`).
- `.ephemeral`: no cookies or credentials persist, and any HTTP cache lives in memory for the life of the process. The session is a `static let`, so one session serves every import (§8.13 on what that means for the worker's `Cache-Control`).
- No delegate is set, so URLSession follows redirects with its default policy, to any HTTPS host. The app declares no App Transport Security exception (a search for `NSAppTransportSecurity` and `NSAllowsArbitraryLoads` at the pin finds nothing), so a redirect to plain `http` fails. No code at the pin implements `willPerformHTTPRedirection`. The worker serves `tour.json` from R2 with a 200 or a 404 and never redirects (§3.6). See Resolutions on the plan's "redirects to another host are refused".
- There is no retry. One request per `openWay`.

#### 3.3 The fetch, the status mapping, and the byte cap

```swift
    func importShare(id: String) async throws -> Way {
        guard Self.isShareId(id) else { throw WayError.notFound }
        let url = Self.baseURL.appendingPathComponent(id).appendingPathComponent("tour.json")
        let data: Data
        do {
            let (bytes, response) = try await session.bytes(from: url)
            // Checked before draining: a 404 or an oversized declared length
            // must not cost a full download first.
            guard let http = response as? HTTPURLResponse else { throw WayError.unavailable }
            if http.statusCode == 404 { throw WayError.notFound }
            guard http.statusCode == 200 else { throw WayError.unavailable }
            guard http.expectedContentLength <= Int64(Self.maxManifestBytes) else { throw WayError.unavailable }
            // Streamed with a cap: a manifest is tens of kilobytes; anything
            // approaching the cap is not a manifest and must not be buffered.
            var buffer = Data()
            for try await byte in bytes {
                buffer.append(byte)
                if buffer.count > Self.maxManifestBytes { throw WayError.unavailable }
            }
            data = buffer
        } catch let error as WayError {
            throw error
        } catch {
            throw WayError.unavailable
        }
        let manifest: TourManifest
        do {
            manifest = try JSONDecoder().decode(TourManifest.self, from: data)
        } catch {
            print("[WayImporter] manifest decode failed: \(error)")
            throw WayError.unavailable
        }
        let way = try Self.way(from: manifest, shareId: id, now: now())
        try store.save(way)
        return way
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:51-86@7c200bf

In order:

| Step | Condition | Outcome |
|---|---|---|
| 1 | id fails `isShareId` | `.notFound`, before any network (pinned: `importShare(id: "../etc")` → `.notFound`, `WayImporterTests.swift:55-65@7c200bf`) |
| 2 | URL | `https://walk.pilgrimapp.org/<id>/tour.json` (probe: `appendingPathComponent` twice yields exactly this) |
| 3 | response is not HTTP | `.unavailable` |
| 4 | status `404` | `.notFound` |
| 5 | any other status but `200` (including `206`, `304`, `410`, `5xx`, and a final `3xx` the session did not follow) | `.unavailable` |
| 6 | declared `Content-Length` over 2,097,152 | `.unavailable`, before reading the body. An unknown length is `-1` and passes. |
| 7 | body grows past 2,097,152 bytes while streaming | `.unavailable`. Exactly 2,097,152 bytes is allowed (`>`, not `>=`). |
| 8 | any thrown non-`WayError` during the fetch: offline, DNS, TLS, the 15 s or 30 s timeout, task cancellation | `.unavailable` |
| 9 | decode fails | `.unavailable`, after a `print` of the decoding error |
| 10 | `way(from:)` throws | its own `WayError` (§4) |
| 11 | `store.save` throws (disk full, I/O) | the store's `CocoaError`, which the coordinator maps to `.unavailable` (§6.4) |
| 12 | success | the saved `Way` is returned |

- Offline has no state or copy of its own: it is `.unavailable`, "couldn't reach the walk".
- The status checks run on the response head, before the body is drained. Android's equivalent is checking `response.code` and `body.contentLength()` before reading, then reading through a counting cap. OkHttp reports an unknown length as `-1` too.
- The decode `print` writes the Swift `DecodingError` description to stdout. Such descriptions can quote input: the probe's out-of-range `Int` prints `Number 9223372036854775808 is not representable in Swift.`, and a type mismatch prints the key path. The plan's logging rule forbids that on Android, so the port logs nothing there. Stdout is not persisted on a device, so this is a hardening with no user-visible difference (Resolutions).
- `save` runs before `return` with no cancellation check between the decode and the write. An import whose task is cancelled after the body arrives still lands in the store (§8.10).

#### 3.4 What `save` does here

`store.save(way)` is the own-walk store's `save` (own-walk spec A §13): it creates `Ways/share:<id>/`, rewrites `way.json` every time, and writes `accepted.json` only if absent. So:

- The first successful import is the acceptance time that orders "Shared with you" (`list()` sorts newest `acceptedAt` first).
- Every later successful import of the same id overwrites `way.json` with the freshly fetched manifest's Way and keeps the first `acceptedAt`.
- No `media/` folder is created here. The downloader creates it file by file when the overview gathers (§6.5).

Android counterpart: `WayStore.save` already has this shape (`data/honor/WayStore.kt:86-95@636cf5ce`), with dates truncated to whole seconds on write (`domain/honor/WayJson.kt:81@636cf5ce`), as iOS's `.iso8601` encoder truncates (probe: `1000.999 s` encodes as `…00:16:40Z`).

#### 3.5 Threading

`importShare` is `async` and runs on the cooperative pool. The coordinator's task is `@MainActor`, so the `await` suspends off the main thread for the network, but `JSONDecoder().decode`, `way(from:)`, and `store.save` (synchronous file I/O) run on whatever executor `importShare` resumes on. `WayImporter` is a plain struct with no actor isolation, so they run off the main actor. Android: run the fetch, decode, build, and save on `Dispatchers.IO` (Global Constraints: hop to IO at file seams).

#### 3.6 The worker's `tour.json` route

```ts
  { re: /^\/([a-zA-Z0-9_-]{10})\/tour\.json$/, key: (m) => `walks/${m[1]}/tour.json`, contentType: "application/json", cacheMaxAge: 3600 },
```
> pilgrim-worker src/index.ts:37@2a4f5d0

```ts
  const rangeHeader = range ? request.headers.get("Range") : null;
  if (!rangeHeader) {
    const obj = await bucket.get(key);
    if (!obj) return new Response("Not Found", { status: 404 });
    if (range) {
      baseHeaders["Accept-Ranges"] = "bytes";
      baseHeaders["Content-Length"] = String(obj.size);
    }
    return new Response(obj.body, { headers: baseHeaders });
  }
```
> pilgrim-worker src/index.ts:273-282@2a4f5d0

- `tour.json` is not a range route, so the worker sets no `Content-Length` itself and returns 200 with the object, or 404. It never checks `expires` when serving.
- `Cache-Control: public, max-age=3600`.
- The worker deletes an expired share's whole R2 prefix in a daily cron at 03:00 UTC:

```ts
async function cleanExpiredWalks(env: Env): Promise<void> {
  const now = new Date();
  let cursor: string | undefined;

  do {
    const listed = await env.WALKS_BUCKET.list({ prefix: "walks/", delimiter: "/", cursor });

    for (const prefix of listed.delimitedPrefixes) {
      const id = prefix.replace("walks/", "").replace("/", "");
      const metaObj = await env.WALKS_BUCKET.get(`walks/${id}/meta.json`);
      if (!metaObj) continue;

      try {
        const meta: WalkMeta = await metaObj.json();
        if (new Date(meta.expires) >= now) continue;

        await deleteWalkObjects(env.WALKS_BUCKET, id);
      } catch {
        continue;
      }
    }

    cursor = listed.truncated ? listed.cursor : undefined;
  } while (cursor);
}
```
> pilgrim-worker src/handlers/expiry.ts:37-61@2a4f5d0

```toml
crons = ["0 3 * * *"]
```
> pilgrim-worker wrangler.toml:7@2a4f5d0

So an expired share answers in one of two ways, depending on when it is asked: between its expiry and the next 03:00 UTC sweep (plus up to an hour of edge cache), the worker still serves the manifest, and the importer's own date check says `.returnedToTrail`; after the sweep, it is a 404 and the importer says `.notFound`. Both are tested on the Android side by the same manifest-level and status-level cases iOS has.

(The app target builds in Swift 5 mode without `SWIFT_APPROACHABLE_CONCURRENCY`; only the widget and screenshot-test targets set it, `project.pbxproj:4179,4216,4242,4268@7c200bf`. So `importShare`, a nonisolated `async` function, runs on the global executor, not on the calling main actor.)

### 4. Validation: every bound, and what failing it does

#### 4.1 The bounds

```swift
    static let maxAltitudeMeters = 100_000.0
    static let maxUnixSeconds = 4_102_444_800  // year 2100
    static let maxVoiceDurationSeconds: Double = 108 * 60  // the app's 108-minute voice cap
    static let maxRestMinutes = 1440
    static let maxEncounterN = 10_000
    static let maxActiveDurationSeconds: Double = 7 * 24 * 3600
    static let maxTitlePlaceCharacters = 80
    /// Free-text fields from an untrusted manifest reach a map callout, a
    /// card, and the summary. Bounded here, once, so no consumer has to.
    static let maxLabelCharacters = 80
    static let maxIconCharacters = 64
    static let maxWeatherConditionCharacters = 64
```
> Pilgrim/Models/Honor/WayImporter.swift:12-23@7c200bf

```swift
    /// The manifest is untrusted input from a public share link: `Int(_:)` traps on
    /// an out-of-range `Double`, and `Int` subtraction traps on overflow, so every
    /// field that later feeds either operation is range-checked here first.
    private static func validate(_ m: TourManifest) -> Bool {
        func inLat(_ v: Double) -> Bool { (-90...90).contains(v) }
        func inLon(_ v: Double) -> Bool { (-180...180).contains(v) }
        func inFrac(_ v: Double) -> Bool { (0...1).contains(v) }

        guard m.route.allSatisfy({ point in
            inLat(point.lat) && inLon(point.lon) && abs(point.alt) < maxAltitudeMeters
                && (0...maxUnixSeconds).contains(point.ts)
        }) else { return false }
        for i in m.route.indices.dropFirst() where m.route[i].ts < m.route[i - 1].ts { return false }

        for e in m.encounters {
            guard inFrac(e.frac) else { return false }
            if let v = e.end_frac, !inFrac(v) { return false }
            if let v = e.duration, !(0...maxVoiceDurationSeconds).contains(v) { return false }
            if let v = e.minutes, !(0...maxRestMinutes).contains(v) { return false }
            if let v = e.n, !(1...maxEncounterN).contains(v) { return false }
            if let v = e.lat, !inLat(v) { return false }
            if let v = e.lon, !inLon(v) { return false }
        }

        for sit in m.meditation {
            guard inFrac(sit.start_frac), inFrac(sit.end_frac) else { return false }
            if let v = sit.duration, !(0...maxVoiceDurationSeconds).contains(v) { return false }
        }
        for seg in m.activity_segments ?? [] {
            guard inFrac(seg.start_frac), inFrac(seg.end_frac) else { return false }
        }

        if let v = m.stats?.active_duration, !(0...maxActiveDurationSeconds).contains(v) { return false }
        if let v = m.weather_temperature, !(-100...100).contains(v) { return false }

        return true
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:88-124@7c200bf

#### 4.2 The order of the checks in `way(from:)`

```swift
    static func way(from m: TourManifest, shareId: String, now: Date) throws -> Way {
        guard let expires = isoDate(m.expires), let departed = isoDate(m.start_date) else { throw WayError.unavailable }
        guard expires > now else { throw WayError.returnedToTrail }
        guard m.route.count >= 2, m.route.count <= maxRoutePoints,
              m.encounters.count <= maxEncounters, m.meditation.count <= maxEncounters else { throw WayError.unavailable }
        guard validate(m) else { throw WayError.unavailable }
        let ts0 = m.route[0].ts
        let route = m.route.map { WayPoint(lat: $0.lat, lon: $0.lon, alt: $0.alt, t: Double($0.ts - ts0)) }
        let geometry = try validGeometry(for: route)
```
> Pilgrim/Models/Honor/WayImporter.swift:126-134@7c200bf

```swift
    /// A route with no real length is not a Way anyone can follow: the same
    /// floor `OwnWalkWayBuilder` applies to the walker's own walks.
    private static func validGeometry(for route: [WayPoint]) throws -> WayGeometry {
        let geometry = WayGeometry(route: route)
        guard geometry.totalMeters >= OwnWalkWayBuilder.minLengthMeters else { throw WayError.unavailable }
        return geometry
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:210-216@7c200bf

```swift
    static let minLengthMeters = 20.0
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:12@7c200bf

```swift
    /// The worker writes `expires` through Date.toISOString(), which always
    /// carries milliseconds; iOS writes `start_date` without them. Try both.
    static func isoDate(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:242-250@7c200bf

The order matters because each step has its own outcome, and an earlier step hides a later one:

1. `expires` or `start_date` does not parse → `.unavailable`.
2. `expires <= now` → `.returnedToTrail`. This runs **before** every count and range check, so an expired manifest that is also malformed reads "This walk has returned to the trail", not "couldn't reach the walk". The boundary is strict: `expires == now` is returned to the trail, which agrees with the sweep's `expires <= now` (own-walk spec A §15).
3. Counts → `.unavailable`.
4. `validate` → `.unavailable`.
5. Route length under 20 m → `.unavailable`.

`now` is injected (`WayImporter.init(... now: @escaping () -> Date = { Date() })`, `WayImporter.swift:47`) and read once per import (`now()` at `:83`). It is the device's wall clock, so a device clock that runs behind lets an expired share import (§8.12).

#### 4.3 The full table

Every bound rejects the **whole share** with `.unavailable` unless the row says otherwise. Nothing is clamped at import except free text, which is cut to length (§7). Nothing drops a single bad moment: an out-of-range field anywhere fails everything.

| # | Field | Bound | Comparison | Failing it |
|---|---|---|---|---|
| 1 | share id | `\A[A-Za-z0-9_-]{10}\z` | regex | `.notFound`, before the network |
| 2 | HTTP status | `404` | `==` | `.notFound` |
| 3 | HTTP status | `200` | `==` (anything else) | `.unavailable` |
| 4 | declared length | 2,097,152 bytes | `expectedContentLength <= max` (−1 passes) | `.unavailable`, before reading |
| 5 | body | 2,097,152 bytes | `buffer.count > max` fails | `.unavailable`, mid-stream |
| 6 | JSON shape | §2.2 required keys and types | decode | `.unavailable` |
| 7 | `expires`, `start_date` | RFC 3339 per `ISO8601DateFormatter` (§4.4) | parse | `.unavailable` |
| 8 | `expires` | later than now | `expires > now` | `.returnedToTrail` |
| 9 | `route.count` | 2…2000 | `>= 2`, `<= 2000` | `.unavailable` |
| 10 | `encounters.count` | ≤ 200, **including** `departure`, `arrival`, and unknown kinds | `<= 200` | `.unavailable` |
| 11 | `meditation.count` | ≤ 200 (the same `maxEncounters`) | `<= 200` | `.unavailable` |
| 12 | `route[].lat` | −90…90 | closed | `.unavailable` |
| 13 | `route[].lon` | −180…180 | closed | `.unavailable` |
| 14 | `route[].alt` | below 100,000 m in magnitude | `abs(alt) < 100_000` (strict) | `.unavailable` |
| 15 | `route[].ts` | 0…4,102,444,800 (2100-01-01) | closed | `.unavailable` |
| 16 | `route[].ts` order | non-decreasing | `ts[i] < ts[i-1]` fails; equal passes | `.unavailable` |
| 17 | `encounters[].frac` | 0…1 | closed | `.unavailable` |
| 18 | `encounters[].end_frac` | 0…1 when present | closed | `.unavailable` |
| 19 | `encounters[].duration` | 0…6480 s when present | closed | `.unavailable` |
| 20 | `encounters[].minutes` | 0…1440 when present | closed | `.unavailable` |
| 21 | `encounters[].n` | 1…10,000 when present | closed | `.unavailable` |
| 22 | `encounters[].lat`, `.lon` | as 12–13, each checked alone | closed | `.unavailable` |
| 23 | `meditation[].start_frac`, `.end_frac` | 0…1 | closed | `.unavailable` |
| 24 | `meditation[].duration` | 0…6480 s when present | closed | `.unavailable` |
| 25 | `activity_segments[].start_frac`, `.end_frac` | 0…1 | closed | `.unavailable` |
| 26 | `stats.active_duration` | 0…604,800 s (7 days) when present | closed | `.unavailable` |
| 27 | `weather_temperature` | −100…100 when present | closed | `.unavailable` |
| 28 | route length | ≥ 20 m (haversine, own-walk spec A §4) | `totalMeters >= 20` | `.unavailable` |
| 29 | `store.save` | the write succeeds | throws | `.unavailable` (§6.4) |

Notes on the table:

- **Rows 17–22 apply to every encounter, whatever its type**, including the `departure`, `arrival`, and unknown kinds that are later skipped. A `beacon` encounter with `frac: 1.5` fails the whole share.
- **No ordering checks** beyond row 16. A voice with `end_frac < frac` passes, as does a sitting with `end_frac < start_frac`, a segment with `end_frac <= start_frac` (dropped later, §5.6), and encounters in any frac order.
- `lat` and `lon` are checked independently, and the moment gets a coordinate only when both are present (§5.4). A lone `lat` passes validation and is ignored.
- **Not checked:** `v`, `place_start`, `place_end`, `weather_condition` length (cut later), `tz_identifier` (neither checked nor cut), `label`, `icon`, `place`, `transcript` (all cut later), `encounters[].type` values, `activity_segments[].kind` values.
- **Kotlin conversions do not trap.** The comment's reason for the bounds is that Swift's `Int(_:)` and `Int` subtraction trap. Kotlin's `Double.toInt()` saturates and `Long` arithmetic wraps, so a missed bound on Android yields garbage instead of a crash. The bounds are still parity, and each one needs its own rejecting test (the plan already says so).
- The 2 MiB cap counts bytes, not characters. `Data.count` is bytes, so Android counts bytes read from the body, not decoded characters.
- iOS's own tests pin a subset: the overflow pair `ts0: Int.min, ts1: Int.max`, a sitting `duration` of `1e300`, `weather_temperature` of `1e300`, route `lat` 91, encounter `frac` 1.5, and `n` 0 (`WayImporterTests.swift:105-122@7c200bf`); 201 sittings (`:124-136`); 306 encounters (`:67-72`); a 0.00001° route under 20 m (`:171-180`); a segment `end_frac` of 1.5 (`:199-209`); an expired manifest (`:49-53`); a malformed id (`:55-65`). Android should port these and add one rejecting test for each remaining row.

#### 4.4 Which date strings parse

`isoDate` tries `[.withInternetDateTime, .withFractionalSeconds]`, then `[.withInternetDateTime]`. Probe results for `ISO8601DateFormatter`, beside `java.time.OffsetDateTime.parse(s, ISO_OFFSET_DATE_TIME)`:

| Input | iOS `isoDate` | JVM `ISO_OFFSET_DATE_TIME` |
|---|---|---|
| `2026-08-01T07:00:00Z` | parses | parses |
| `2026-08-01T07:00:00.000Z` (the worker's `expires`) | parses | parses |
| `…00.1Z`, `…00.12Z`, `…00.123456Z`, `…00.123456789Z` | parse; precision kept to the millisecond (`.123456` reads as `.123`) | parse; nanoseconds kept |
| `2026-08-01T09:00:00+02:00`, `…00.123+02:00` (Android's `start_date` shape) | parse | parse |
| `2026-08-01T09:00:00+0200` (offset without a colon) | **parses** | **throws** |
| `2026-08-01t07:00:00z` (lower case) | **nil** | **parses** (the ISO formatters are case-insensitive) |
| `2026-02-30T07:00:00Z` | **parses, as 2026-03-02** | **throws** (strict resolver) |
| `2026-08-01T07:00:60Z` | nil | throws |
| `2026-08-01T07:00Z`, `2026-08-01T07:00:00` (no zone), `2026-08-01 07:00:00Z`, `20260801T070000Z`, `…00.Z` | nil | throws |

Both platforms' real `start_date` strings parse on both: iOS writes `ISO8601DateFormatter()`'s default, whole seconds in UTC (`WalkShareViewModel.swift:379,388@7c200bf`), and Android writes `ISO_OFFSET_DATE_TIME` in the walk's zone, with a fraction when the milliseconds are not zero (`data/share/SharePayloadBuilder.kt:383,407-408@636cf5ce`). The three bold rows differ only for strings neither app writes. To match iOS exactly, the port's parser needs to accept `+HHMM`, reject lower-case `t`/`z`, and roll an out-of-range day the way Foundation does; or it can accept the shared subset and record the three as unreachable. Open question 2.

### 5. How a manifest becomes a `Way`

#### 5.1 The Way's fields

```swift
        let spans = spans(from: m.activity_segments ?? [])

        let wayTitle = title(placeStart: m.place_start, placeEnd: m.place_end, departed: departed)
        return Way(
            id: "share:\(shareId)",
            source: .share(id: shareId, pageURL: baseURL.appendingPathComponent(shareId)),
            title: wayTitle, departedAt: departed, tzIdentifier: m.tz_identifier, expires: expires,
            route: route, totalDistanceMeters: geometry.totalMeters,
            theirActiveSeconds: m.stats?.active_duration ?? geometry.totalSeconds,
            moments: moments,
            weather: m.weather_condition.map { WayWeather(condition: capped($0, maxWeatherConditionCharacters), temperatureC: m.weather_temperature) },
            spans: spans)
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:182-194@7c200bf

| `Way` field | Shared-walk value |
|---|---|
| `id` | `"share:" + shareId`, e.g. `share:Qoi4YmPHLN` (a valid store id, own-walk spec A §12) |
| `source` | `.share(id: shareId, pageURL: https://walk.pilgrimapp.org/<shareId>)`. The page URL is always the `walk.` host. Nothing at the pin reads `pageURL` after this (`git grep pageURL` finds only the enum case and this line). Wire form `{"share":{"id":"…","pageURL":"…"}}` (own-walk spec A §2; Android `WaySource.Share`, `domain/honor/Way.kt:233-237@636cf5ce`). |
| `title` | §5.3 |
| `departedAt` | `isoDate(start_date)`; stored to the whole second |
| `tzIdentifier` | `tz_identifier` verbatim: not validated, not trimmed, not cut |
| `expires` | `isoDate(expires)`; stored to the whole second, truncated |
| `route` | every manifest point, unsampled, `t = ts − ts₀` (§5.2) |
| `totalDistanceMeters` | `WayGeometry(route:).totalMeters`, the haversine length of the manifest route; `total_distance_m` is ignored |
| `theirActiveSeconds` | `stats.active_duration` when present, else `geometry.totalSeconds` (last `t` − first `t`, which includes the sharer's pauses). The worker sends `active_duration` only when the sharer toggled the "duration" stat on. Own walks use the walk's active duration instead (own-walk spec A §11), so the two sources disagree on pauses. |
| `moments` | §5.4–§5.5 |
| `weather` | present iff `weather_condition` is present: `WayWeather(condition: first 64 characters, temperatureC: weather_temperature)`. A temperature without a condition is dropped. An empty condition `""` still makes a `WayWeather`, which the overview renders as `they walked this in .` (S1-D2). |
| `spans` | always set, possibly `[]` (§5.6) |
| `marks`, `stage` | nil |

#### 5.2 The route

```swift
        let ts0 = m.route[0].ts
        let route = m.route.map { WayPoint(lat: $0.lat, lon: $0.lon, alt: $0.alt, t: Double($0.ts - ts0)) }
```
> Pilgrim/Models/Honor/WayImporter.swift:132-133@7c200bf

- `t` is whole seconds since the first point (an `Int` difference made `Double`), never negative (row 16). `alt` is always set, since it is required.
- No stride sample and no simplification: the route is used as received, at most 2000 points. Both apps send at most 200 (iOS `RouteDownsampler.downsample(... maxPoints: Int = 200)`, `Pilgrim/Models/Share/RouteDownsampler.swift:5-7@7c200bf`, called at `WalkShareViewModel.swift:467`; Android `ShareConfig.DOWNSAMPLE_TARGET_POINTS = 200`, `data/share/ShareConfig.kt:22@636cf5ce`).
- The worker computes every `frac` over the same route with its own haversine (`R = 6371000`, `2R·asin(√h)`, `tour.ts:165-174@2a4f5d0`). The importer never recomputes a frac from a coordinate; it takes the manifest's.
- Duplicate consecutive points are allowed. They give zero-length segments, which matter to the sitting estimate (§5.5).

#### 5.3 The title

```swift
    /// Drops place strings that are empty after trimming and caps each at
    /// `maxTitlePlaceCharacters`, falling back to the departure date when
    /// neither place survived.
    private static func title(placeStart: String?, placeEnd: String?, departed: Date) -> String {
        let places = [placeStart, placeEnd].compactMap { place -> String? in
            guard let trimmed = place?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
            return String(trimmed.prefix(maxTitlePlaceCharacters))
        }
        return places.isEmpty ? DateFormatter.localizedString(from: departed, dateStyle: .medium, timeStyle: .none)
            : places.joined(separator: " → ")
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:230-240@7c200bf

- Both places: `"<start> → <end>"`, joined by space, U+2192, space. One place: that place alone, with no arrow. iOS pins the two-place form: `"Rúa do Franco → Obradoiro"` (`WayImporterTests.swift:29@7c200bf`).
- Each place is trimmed and cut to 80 characters (grapheme clusters) **after** trimming. The longest title is 80 + 3 + 80 = 163 characters.
- Neither place: the departure date, medium style, no time, in the **importing device's** locale and current zone at import time (not the sharer's `tz_identifier`), e.g. "Aug 1, 2026" for en_US (probe; `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)` gives the same string for `Locale.US`). The string is frozen into `way.json`; a later locale change does not re-render it.
- **Every Android-made share falls back to the date**, because Android posts `placeStart = null` and `placeEnd = null` (`data/share/SharePayloadBuilder.kt:386-387@636cf5ce`), so the worker writes `null` for both.

#### 5.4 Moments from encounters

```swift
        var moments: [WayMoment] = []
        var voiceN = 0, photoN = 0, waypointN = 0, restN = 0
        for e in m.encounters {
            let at: WayCoordinate?
            if let lat = e.lat, let lon = e.lon {
                at = WayCoordinate(lat: lat, lon: lon)
            } else {
                at = nil
            }
            switch e.type {
            case "voice", "ambience":
                guard let n = e.n else { continue }
                voiceN += 1
                moments.append(WayMoment(id: "voice-\(voiceN)", frac: e.frac, at: at,
                    kind: .voice(endFrac: e.end_frac ?? e.frac, duration: e.duration ?? 0,
                                 kind: e.type == "voice" ? .spoken : .ambient, media: .file("audio/\(n).m4a")),
                    place: trimmedPlace(e.place), transcript: WayMoment.trimmedTranscript(e.transcript)))
            case "photo":
                guard let n = e.n else { continue }
                photoN += 1
                moments.append(WayMoment(id: "photo-\(photoN)", frac: e.frac, at: at, kind: .photo(media: .file("photos/\(n).jpg"))))
            case "waypoint":
                waypointN += 1
                moments.append(WayMoment(id: "waypoint-\(waypointN)", frac: e.frac, at: at,
                    kind: .waypoint(label: capped(e.label, maxLabelCharacters), icon: capped(e.icon, maxIconCharacters, or: "mappin"))))
            case "rest":
                restN += 1
                moments.append(WayMoment(id: "rest-\(restN)", frac: e.frac, at: at, kind: .rest(minutes: e.minutes ?? 0)))
            default:
                continue
            }
        }
```
> Pilgrim/Models/Honor/WayImporter.swift:136-167@7c200bf

```swift
    /// Bounds one free-text field from an untrusted manifest.
    private static func capped(_ value: String?, _ max: Int, or fallback: String = "") -> String {
        String((value ?? fallback).prefix(max))
    }

    /// A street name the sharer's page reverse-geocoded, or nothing: blank
    /// after trimming reads as no place rather than an empty subline.
    private static func trimmedPlace(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(maxTitlePlaceCharacters))
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:218-228@7c200bf

Per encounter `type` (the match is exact and case-sensitive):

| `type` | Skipped when | Moment id | `kind` | `at` | `place` | `transcript` |
|---|---|---|---|---|---|---|
| `voice` | `n` absent (no id consumed) | `voice-<k>` | `.voice(endFrac: end_frac ?? frac, duration: duration ?? 0, kind: .spoken, media: .file("audio/<n>.m4a"))` | both `lat` and `lon` present, else nil | trimmed, blank → nil, cut to 80 | `WayMoment.trimmedTranscript`: trimmed, blank → nil, cut to 600 |
| `ambience` | `n` absent | `voice-<k>` (shares the voice counter) | as `voice` with `.ambient` | same | same | same (the worker never sends one; iOS would keep it) |
| `photo` | `n` absent | `photo-<k>` | `.photo(media: .file("photos/<n>.jpg"))` | same | nil | nil |
| `waypoint` | never | `waypoint-<k>` | `.waypoint(label: first 80 of label ?? "", icon: first 64 of icon ?? "mappin")` | same | nil | nil |
| `rest` | never | `rest-<k>` | `.rest(minutes: minutes ?? 0)` | same (the worker never sends one) | nil | nil |
| `departure`, `arrival`, anything else | always | none | | | | |

- **Ids** count per kind in manifest order, 1-based, and only for moments actually made. `voice` and `ambience` share one counter. The worker sorts encounters by frac, so ids run in frac order for worker manifests.
- **Media paths** are built from the manifest's `n`, not from the moment's counter: `audio/<n>.m4a` and `photos/<n>.jpg`. `n` is 1…10,000 (row 21). Wire form `{"file":{"_0":"audio/1.m4a"}}` (own-walk spec A §2). The downloader later accepts only paths matching `\A(?:audio/[0-9]{1,5}\.m4a|photos/[0-9]{1,5}\.jpg)\z` (`WayMediaDownloader.swift:98@7c200bf`), which every importer-built path does.
- **`frac`** is the manifest's, unchanged. The voice's `endFrac` falls back to its `frac`.
- **`at`** needs both coordinates. Shares from before 2026-09-03 have none, so their voices trigger at `WayGeometry.coordinate(atFrac:)` (own-walk spec A §1), the R11 "place voices by fraction" case. iOS pins both: `voice-1` carries `(42.8801, -8.5401)` and `voice-2` has nil, "older shares carry no coordinate" (`WayImporterTests.swift:34,39@7c200bf`).
- **`duration`** absent gives a 0 s voice.
- **`minutes`** absent gives a 0-minute rest ("they rested here 0 minutes", own-walk spec E's card copy).
- **Waypoint text is cut but not trimmed.** A whitespace-only label stays as it is; an absent label is `""`. The icon falls back to `"mappin"` only when the key is absent. An empty `icon: ""`, which Android shares send for a waypoint with no icon (`icon = it.icon.orEmpty()`, `data/share/SharePayloadBuilder.kt:343@636cf5ce`), stays `""`; the header then draws `mappin` because `UIImage(systemName: "")` is nil (`WayMomentHeader.swift:54@7c200bf`). Android's map already falls back the same way (`ui/walk/PilgrimMap.kt:1030@636cf5ce`).
- `text`, `names`, `sitMinutes`, `pin` stay nil (stage fields).

Android already has `WayMoment.trimmedTranscript` with the 600 cap and grapheme counting (`domain/honor/Way.kt:128,137-141,305-315@636cf5ce`). Its `trim()` differs from Swift's set (§7.2).

#### 5.5 Sittings

```swift
        for (index, sit) in m.meditation.enumerated() {
            let minutes: Int
            let isEstimate: Bool
            if let seconds = sit.duration {
                minutes = Int((seconds / 60).rounded()); isEstimate = false
            } else {
                minutes = Int((gapSeconds(around: sit.start_frac, geometry: geometry) / 60).rounded()); isEstimate = true
            }
            moments.append(WayMoment(id: "sit-\(index + 1)", frac: sit.start_frac, at: nil,
                                     kind: .meditation(minutes: minutes, isEstimate: isEstimate)))
        }
```
> Pilgrim/Models/Honor/WayImporter.swift:168-178@7c200bf

```swift
    /// Time between the two route points bracketing `frac`: a sitting collapses
    /// to a single frac on a downsampled route, so the gap holds the sit plus
    /// whatever walking the RDP pass folded into that segment. Rendered "about".
    static func gapSeconds(around frac: Double, geometry: WayGeometry) -> Double {
        let points = geometry.points
        guard points.count > 1, geometry.totalMeters > 0 else { return 0 }
        let target = frac * geometry.totalMeters
        for i in 0..<(points.count - 1) where geometry.cumulative[i] <= target && target <= geometry.cumulative[i + 1] {
            return points[i + 1].t - points[i].t
        }
        return 0
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:252-263@7c200bf

- Every sitting becomes a moment; none is skipped. The id is `sit-<array index + 1>`. The frac is `start_frac`; `end_frac` is validated and then unused. `at` is always nil.
- With `duration`: `minutes = round(duration / 60)`, `isEstimate = false`. Swift's `.rounded()` is half away from zero (probe: `2.5 → 3`). Since the value is never negative, Kotlin's `roundToInt()` matches (own-walk spec correction 7).
- Without `duration` (shares before 2026-09-03): the time across the first route segment whose cumulative range contains `frac × totalMeters`, both ends inclusive, rounded the same way, `isEstimate = true`. "The first" matters at a vertex: a frac exactly on point *k* takes segment *k−1…k*, not *k…k+1*, even when *k…k+1* is the zero-length segment that holds the actual sit. The port must iterate in the same order with the same inclusive comparisons. Since `totalMeters ≥ 20` here, the `return 0` guards are unreachable from the importer, and the trailing `return 0` is reachable only through floating-point rounding at `frac == 1`.
- The estimate has no cap. A long gap gives a large minute count (the 6480 s bound applies only to a declared `duration`).
- iOS pins both paths with its fixture: route `ts` 1000, 1400, 1600 over three points; sitting 1 has `duration: 720` → `.meditation(minutes: 12, isEstimate: false)`; sitting 2 at frac 0.75 has none → the 200 s gap of the second leg → `.meditation(minutes: 3, isEstimate: true)` (`WayImporterTests.swift:42-46@7c200bf`).
- The same `Int(...)` conversion that traps on iOS on an out-of-range value is bounded by row 24 for a declared duration, and by row 15 for the gap.

#### 5.6 Spans

```swift
    /// Unknown kinds are skipped, like unknown encounter types.
    private static func spans(from segments: [TourManifest.ActivitySegment]) -> [WaySpan] {
        segments.compactMap { seg in
            let kind: WaySpanKind
            switch seg.kind {
            case "meditation": kind = .meditating
            case "talk": kind = .talking
            default: return nil
            }
            guard seg.end_frac > seg.start_frac else { return nil }
            return WaySpan(startFrac: seg.start_frac, endFrac: seg.end_frac, kind: kind)
        }.sorted { $0.startFrac < $1.startFrac }
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:196-208@7c200bf

- `"meditation"` → `.meditating`, `"talk"` → `.talking`, anything else skipped. Pinned: `talk`, `meditation`, `dance` → `[.talking, .meditating]` (`WayImporterTests.swift:185-197@7c200bf`).
- A segment with `end_frac <= start_frac` is dropped, not rejected (but a frac outside 0…1 rejects the share, row 25).
- Sorted by `startFrac` ascending. Overlaps are kept. Swift's `sort` is not documented as stable, so the order of two spans with the same `startFrac` is unspecified on iOS. Kotlin's `sortedBy` is stable. Only an overlap between different kinds that start at the same frac could look different, and the ghost line would draw one over the other (Open question 4).
- `spans` is never nil for a share, so `way.json` always carries `"spans":[…]`.

#### 5.7 Ordering

```swift
        // A tiebreak on id keeps ordering deterministic when two moments share a frac.
        moments.sort { $0.frac == $1.frac ? $0.id < $1.id : $0.frac < $1.frac }
```
> Pilgrim/Models/Honor/WayImporter.swift:179-180@7c200bf

- By `frac` ascending, then by `id` as a plain string comparison. The tiebreak is lexicographic, so at one frac `photo-1 < rest-1 < sit-1 < voice-1 < waypoint-1`, and `voice-10 < voice-2` (probe; the JVM's `String.compareTo` agrees for these ASCII ids). The moment tracker sorts by the same rule (`HonorMomentTracker.swift:41@7c200bf`, own-walk spec B §8.1).
- Ids are unique within a Way (each kind has its own counter and prefix), so the order is total and stability does not matter.

#### 5.8 A worked example (iOS's fixture)

The fixture at `WayImporterTests.swift:7-24@7c200bf`, imported as `Qoi4YmPHLN`, gives:

- `id` `share:Qoi4YmPHLN`; `title` `Rúa do Franco → Obradoiro`; route `t` `[0, 400, 600]`; `theirActiveSeconds` 540; `weather` `("rain", 9)`.
- Moments: `voice-1` (spoken, `audio/1.m4a`, at `(42.8801, -8.5401)`), `voice-2` (ambient, `audio/2.m4a`, no `at`), `photo-1` (`photos/1.jpg`), `rest-1` (4 minutes), `sit-1` (12, not an estimate), `sit-2` (3, an estimate). `departure` and `arrival` make nothing. Six moments in all, which the unknown-type test also pins (`:74-81`).

The plan's U28 test file should load this fixture verbatim, as the plan says ("with iOS's tour.json fixture").

### 6. The import states

#### 6.1 The states and the copy

```swift
enum HonorImportState: Equatable {
    case idle, fetching, gathering(progress: Double), ready, mediaMissing([String]), failed(WayError)
}

/// Pure mapping from the downloader's published sets to the overview state,
/// so the state machine is testable without a session.
enum HonorImportReducer {
    static func state(
        wayId: String,
        progress: [String: Double],
        active: Set<String>,
        failures: [String: [String]],
        diskFull: Set<String>
    ) -> HonorImportState {
        if diskFull.contains(wayId) { return .failed(.diskFull) }
        if active.contains(wayId) { return .gathering(progress: progress[wayId] ?? 0) }
        if let missing = failures[wayId], !missing.isEmpty { return .mediaMissing(missing) }
        return .ready
    }
}

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

Every string, exactly. None is localized: `HonorImportCopy` returns plain `String` literals, every view shows them through `Text(line)` with a `String` (not a `LocalizedStringKey`), and none appears in `Pilgrim/Support Files/Base.lproj/Localizable.strings@7c200bf`. So there are no localization keys. Android should still put them in `strings.xml` (house convention), with the English values below.

| State | Line | Notes |
|---|---|---|
| `.idle` | none | |
| `.fetching` | `reaching for the walk…` | ends in U+2026, a single ellipsis character |
| `.gathering(p)` | `gathering their voices · N%` | U+00B7 middle dot with a space either side; `N = Int((p × 100).rounded())`, half away from zero (pinned: `0.456` → `46%`, `HonorImportReducerTests.swift:23-25@7c200bf`) |
| `.ready` | none | |
| `.mediaMissing(_)` | `some voices didn't arrive` | the list is never shown (§6.6) |
| `.failed(.notFound)` | `couldn't find that walk. Check the link, or it may have returned to the trail.` | the only line with a final period, and two sentences |
| `.failed(.returnedToTrail)` | `This walk has returned to the trail` | the only line that starts with a capital; no final period |
| `.failed(.unavailable)` | `couldn't reach the walk` | also covers offline, timeouts, malformed manifests, and a failed save |
| `.failed(.diskFull)` | `not enough space on this phone to save these voices` | only from the media download, never from the manifest fetch |

All apostrophes are ASCII U+0027. The link path's mid-walk refusal, `finish this walk first`, is a toast, not an import state (§6.3).

#### 6.2 The reducer's precedence

First match wins:

1. `diskFull` holds the Way → `.failed(.diskFull)`, even while other files are still downloading (pinned: "disk full outranks everything", `HonorImportReducerTests.swift:13-14@7c200bf`).
2. `active` holds the Way → `.gathering(progress[wayId] ?? 0)`.
3. `failures[wayId]` is non-empty → `.mediaMissing(failures)`.
4. Otherwise `.ready`.

The reducer never produces `.idle`, `.fetching`, or `.failed` other than `.diskFull`; the coordinator sets those directly. The inputs are the downloader's four published sets:

```swift
    @Published private(set) var progress: [String: Double] = [:]
    @Published private(set) var failures: [String: [String]] = [:]
    @Published private(set) var active: Set<String> = []
    /// Way ids whose download hit a full disk; the coordinator names the problem instead of offering a retry.
    @Published private(set) var diskFull: Set<String> = []
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:13-17@7c200bf

and the way `download` seeds them decides the first state the overview shows:

```swift
    func download(_ way: Way) {
        guard case .share(let shareId, _) = way.source else { return }
        // A second `gather` from a reopened overview must not double-enqueue
        // a Way that's already downloading.
        guard !active.contains(way.id) else { return }
        // The ceilings are applied to everything the Way declares, not just
        // the files still missing, so a partly-fetched hostile manifest can't
        // walk past them one `download` at a time.
        let (declared, refused) = Self.withinCeilings(Self.mediaFiles(for: way))
        let files = declared.filter { !FileManager.default.fileExists(atPath: store.mediaURL(for: way.id, relative: $0).path) }
        failures[way.id] = refused.isEmpty ? nil : refused
        diskFull.remove(way.id)
        guard !files.isEmpty else {
            progress[way.id] = 1
            totals[way.id] = nil
            return
        }
        active.insert(way.id)
        pending[way.id] = Set(files)
        totals[way.id] = declared.count
        // Seeded from what's already on disk, not 0, so progress is monotonic
        // across repeated `download()` calls for the same Way.
        progress[way.id] = 1 - Double(files.count) / Double(declared.count)
        for relative in files { enqueue(wayId: way.id, shareId: shareId, relative: relative) }
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:120-144@7c200bf

- `progress` is files done over files declared (within the ceilings), not bytes. It starts at the fraction already on disk, so a reopened half-gathered Way starts at, say, `gathering their voices · 50%`.
- A Way whose every file is already on disk, or which declares none, goes straight to `.ready` (or to `.mediaMissing` if the ceilings refused some).
- Files refused by the per-kind ceilings (12 audio, 20 photos; `WayMediaDownloader.swift:75-76@7c200bf`) are entered in `failures` at once, so such a Way ends in `.mediaMissing` however the downloads go, and "try again" refuses them again. The download cluster owns the ceilings; for this cluster, the consequence is a `.mediaMissing` that no retry clears.

#### 6.3 Transitions (the coordinator)

The state lives on `MainCoordinator`, one value for the whole app, shared by the Ways sheet and the overview:

```swift
    @Published var honorImportState: HonorImportState = .idle
    /// The only feedback a link has when no Honor sheet is open to carry it.
    @Published var pendingLinkToast: String?
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:20-22@7c200bf

**Opening the sheet** resets it:

```swift
    /// Resets the import state and any toast left over from a previous link
    /// so a stale failure line never greets the next opening of the sheet.
    func chooseWay() {
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:193-195@7c200bf

```swift
        honorImportState = .idle
        showLinkToast(nil)
        honorWaysPresented = true
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:204-207@7c200bf

**A link or a paste** starts an import:

```swift
    /// Fetch happens wherever the user is: inside the Ways sheet the state
    /// renders inline beside the paste field (a not-found error keeps the
    /// field editable); from a link with no sheet open, a toast carries it.
    func openWay(shareId: String) {
        guard activeWalkViewModel == nil else { showLinkToast("finish this walk first"); return }
        importTask?.cancel()
        honorImportState = .fetching
        if !honorWaysPresented { showLinkToast("reaching for the walk…") }
        let fetch = importShare
        importTask = Task { @MainActor [weak self] in
            do {
                let way = try await fetch(shareId)
                // A cancelled import belongs to a link the walker has already
                // replaced; neither its Way nor its error may land on top of
                // the newer one's state.
                guard let self, !Task.isCancelled else { return }
                self.showLinkToast(nil)
                self.honorImportState = .idle
                self.importTask = nil
                // A Begin already in flight (a walk starting, or parked to
                // start once the overview closes) wins — presenting this Way
                // now would race it for the overview sheet or interrupt the
                // walk that's already beginning. Drop it silently.
                guard self.activeWalkViewModel == nil, self.pendingStartWay == nil else { return }
                self.openOverview(for: way)      // AF60-safe: parks or presents, never both
            } catch {
                guard let self, !Task.isCancelled else { return }
                let failure = (error as? WayError) ?? .unavailable
                self.honorImportState = .failed(failure)
                self.showLinkToast(self.honorWaysPresented ? nil : HonorImportCopy.line(for: .failed(failure)))
                self.importTask = nil
            }
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:209-242@7c200bf

**The toast** always expires after 5 s:

```swift
    /// One toast at a time, always with a cancellable expiry: a link that
    /// never resolves must not leave "reaching for the walk…" on screen.
    private func showLinkToast(_ text: String?) {
        linkToastWork?.cancel()
        pendingLinkToast = text
        guard text != nil else { return }
        let work = DispatchWorkItem { [weak self] in self?.pendingLinkToast = nil }
        linkToastWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:340-349@7c200bf

**Opening the overview** after a success, and **gathering**:

```swift
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
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:246-258@7c200bf

```swift
    func gather(_ way: Way) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            // A dismiss or an item swap that lands before this hop must not
            // install a sink for a Way that is no longer showing.
            guard self.honorOverviewWay?.id == way.id else { return }
            guard case .share = way.source else { self.honorImportState = .ready; return }
            let downloader = WayMediaDownloader.shared
            downloader.download(way)
            self.honorImportState = HonorImportReducer.state(
                wayId: way.id, progress: downloader.progress, active: downloader.active,
                failures: downloader.failures, diskFull: downloader.diskFull)
            self.gatheringCancellable = downloader.$progress
                .combineLatest(downloader.$active, downloader.$failures, downloader.$diskFull)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] progress, active, failures, diskFull in
                    self?.honorImportState = HonorImportReducer.state(
                        wayId: way.id, progress: progress, active: active, failures: failures, diskFull: diskFull)
                }
        }
    }

    func retryMedia(for way: Way) {
        Task { @MainActor in WayMediaDownloader.shared.retry(way) }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:264-288@7c200bf

**Walking without** the missing files:

```swift
    /// "walk without the missing voices": the sink is dropped first, so a
    /// later change from another Way's download can't recompute this one back
    /// into `.mediaMissing`. The absent files simply never play.
    func walkWithoutMissingVoices() {
        gatheringCancellable = nil
        honorImportState = .ready
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:297-303@7c200bf

**Closing** the overview resets it only on a real close (`handleOverviewDismiss`, `MainCoordinatorView.swift:325-338@7c200bf`, quoted in own-walk spec F §7.3), and **starting a walk** drops the import and the sink without touching the state:

```swift
        guard activeWalkViewModel == nil else { return }
        // The overview is gone by the time a walk starts, so nothing is left
        // to render an import's progress — only the OBSERVATION is dropped.
        // The background transfers keep running, and a file that lands
        // mid-walk becomes playable like any other.
        importTask?.cancel()
        importTask = nil
        gatheringCancellable = nil
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:80-87@7c200bf

The state machine, from those quotes:

| From | Event | To | Side effects |
|---|---|---|---|
| any | the Path tab's Honor door (`chooseWay`) | `.idle` | toast cleared; the sheet opens |
| any | `openWay` while a walk is active | unchanged | toast `finish this walk first` (5 s); nothing fetched (pinned: state stays `.idle`, `MainCoordinatorHonorTests.swift:98-108@7c200bf`) |
| any | `openWay` otherwise | `.fetching` | the previous import task is cancelled; toast `reaching for the walk…` (5 s) only when the sheet is not open |
| `.fetching` | import succeeds, task not cancelled | `.idle` | toast cleared; if a walk is active or a Begin is parked, the Way is dropped silently (it is already saved, §8.10); else `openOverview` |
| `.fetching` | import fails, task not cancelled | `.failed(e)` | `e` is the `WayError`, or `.unavailable` for anything else; the failure line as a toast only when the sheet is not open |
| `.fetching` | the task was cancelled (a newer link, or a walk started) | unchanged by this task | nothing; the newer link owns the state |
| `.idle` | `openOverview` with the sheet or a summary showing | `.idle` | the Way is parked and the sheet closes; the sheet's or summary's dismiss promotes it and calls `gather` |
| `.idle` | `openOverview` otherwise | (via `gather`) | the overview presents |
| any | `gather` for a share | reducer output | download started; sink installed; first state computed at once |
| any | `gather` for an own walk | `.ready` | no sink |
| any | a downloader set changes | reducer output | through the sink, on the main queue |
| `.mediaMissing` | "try again" | reducer output | `retry`: cancel then `download` (the downloader cluster) |
| `.mediaMissing` | "walk without the missing voices" | `.ready` | the sink is dropped, so later download changes no longer move this overview's state |
| any | the overview really closes | `.idle` | sink dropped; downloads continue in the background |
| any | a walk starts | unchanged | import task cancelled; sink dropped; downloads continue. A `.fetching` left this way stays until the next `chooseWay` or `gather` replaces it; no surface shows it in between, since the walk covers everything. |

Timing constants on this path: the toast's 5 s (`asyncAfter(deadline: .now() + 5)`), the session's 15 s idle and 30 s total (§3.2), and `gather`'s `Task { @MainActor … }` hop, which runs after the current main-actor turn (so the first reducer state lands one hop after the overview's item is set; pinned by `testGatherForAWayNoLongerShowingInstallsNoSink`, `MainCoordinatorHonorTests.swift:175-193@7c200bf`). The manifest fetch has no retry and no backoff: the walker taps the link again.

Two races are pinned by iOS tests and should be ported as tests:

- An import that resolves after Begin presents no overview and leaves the state `.idle` (`MainCoordinatorHonorTests.swift:137-154@7c200bf`).
- An import that resolves after a walk starts presents no overview (`:156-172`). There, `startWalk` has already cancelled the task, so it is the `!Task.isCancelled` guard that drops the result.

#### 6.4 Error mapping at the coordinator

`(error as? WayError) ?? .unavailable` (`MainCoordinatorView.swift:236`) folds every non-`WayError` into "couldn't reach the walk". The only non-`WayError` that `importShare` can throw is the store's write error from `save` (§3.3 step 11), so a full disk while writing `way.json` reads "couldn't reach the walk", not the disk-full line.

#### 6.5 Where each line shows

**In the Ways sheet**, under the paste field:

```swift
                Section {
                    TextField("paste a walk link", text: $pasted)
                        .font(Constants.Typography.body)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Open") { onPaste(pasted) }
                        .font(Constants.Typography.button)
                        .disabled(HonorLink.parse(text: pasted) == nil || importState == .fetching)
                    // The field above stays editable in every state, so a
                    // mistyped link is corrected where it was typed.
                    if let line = HonorImportCopy.line(for: importState) {
                        Text(line)
                            .font(Constants.Typography.caption)
                            .foregroundColor(isFailure ? .rust : .fog)
                    }
                } header: {
                    Text("From a shared walk").font(Constants.Typography.caption)
                } footer: {
                    Text("A walk someone shared with you, from walk.pilgrimapp.org.")
                        .font(Constants.Typography.caption)
                }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:57-77@7c200bf

```swift
    private var isFailure: Bool {
        if case .failed = importState { return true }
        return false
    }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:120-123@7c200bf

```swift
                onPaste: { text in
                    if let id = HonorLink.parse(text: text) { coordinator.openWay(shareId: id) }
                }
```
> Pilgrim/Scenes/Root/MainTabView.swift:69-71@7c200bf

- Placeholder `paste a walk link`; button `Open`; header `From a shared walk`; footer `A walk someone shared with you, from walk.pilgrimapp.org.` The footer names only `walk.` even though `honor.` links paste too.
- "Open" is disabled while the text does not parse or while the state is `.fetching`. The field itself is never disabled.
- The line is caption type, `rust` for any `.failed`, `fog` otherwise. The sheet and the overview are never up together and the gathering sink runs only for the overview, so in practice the sheet shows only `reaching for the walk…` (fog) or a failure (rust).

**On the overview**, as the card's import row (own-walk spec F §10.4), with the two buttons:

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

    private var isTrouble: Bool {
        switch importState {
        case .failed, .mediaMissing: return true
        default: return false
        }
    }

    /// The one place the import speaks on this screen: its line under the
    /// counts, and — only when files are actually missing — the choice
    /// between waiting for them and walking without them.
    @ViewBuilder
    private var importLine: some View {
        if let line = HonorImportCopy.line(for: importState) {
            Text(line)
                .font(Constants.Typography.caption)
                .foregroundColor(isTrouble ? .rust : .fog)
        }
        if case .mediaMissing = importState {
            // Vertical, not the section's usual horizontal pairing: the
            // second label is long enough to clip on an SE width at large
            // accessibility type sizes if it has to share a row.
            VStack(alignment: .leading, spacing: Constants.UI.Padding.xs) {
                Button("try again", action: onRetryMedia)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                Button("walk without the missing voices", action: onWalkWithoutMissing)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .font(Constants.Typography.caption)
            .foregroundColor(.stone)
        }
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:292-335@7c200bf

- Begin is disabled in `.fetching` and `.gathering` only. In `.mediaMissing` and every `.failed`, including `.failed(.diskFull)`, Begin is enabled: the walker can always walk with whatever landed. Disk full therefore has no buttons, only the rust line, and Begin.
- The line is `rust` for `.failed` and `.mediaMissing`, `fog` for `.fetching` and `.gathering`.
- The two buttons appear only in `.mediaMissing`: `try again` and `walk without the missing voices`, caption type in `stone`, stacked vertically with `Constants.UI.Padding.xs` between them, each at least 44 pt tall with its whole frame tappable.
- The overview re-renders on every progress tick; iOS computes its map derivations once per Way for that reason (`HonorOverviewView.swift:96-98@7c200bf`). Android should keep the per-tick state out of the map's inputs.

**As a toast**, when no sheet carries the line: `HonorLinkToast`, caption type in `ink`, centered, on `parchmentSecondary` at 0.95, corner radius 8, `Constants.UI.Padding.normal` horizontal and `.small` vertical padding inside, `normal` horizontal and `small` top padding outside, not hit-testable (`MainCoordinatorView.swift:406-422@7c200bf`); it slides in from the top with a fade (`.move(edge: .top).combined(with: .opacity)`, default `.easeInOut`), at the top of the tab view under the recovery banner (`MainTabView.swift:146-159@7c200bf`) and repeated over the active walk's cover (`:47-56`). Sheets present above the tab view's overlay, so a toast raised while the overview is open sits behind the sheet. The toast placement is U27's routing cluster; listed here because it carries this cluster's copy.

#### 6.6 What `.mediaMissing`'s list is for

Nothing reads it. `git grep mediaMissing 7c200bf -- Pilgrim` finds the enum, the reducer, the copy, and the two pattern matches in `HonorOverviewView`, none of which binds the list. The overview says only `some voices didn't arrive`; no view names which voices or photos are missing, and the list includes photos and ceiling-refused files as well as failed voices. See Resolutions on the plan's "a partial gather shows which voices are missing".

### 7. Sharer text: what iOS does to it (pin 5)

#### 7.1 Every free-text field, and its treatment

| Manifest field | Who controls it | Worker check (`validate-share.ts@2a4f5d0`) | iOS treatment at import | Becomes |
|---|---|---|---|---|
| `place_start`, `place_end` | the sharer's app | none | trim, drop if blank, first 80 characters | the Way's `title` |
| `encounters[].place` (voice, ambience) | the worker's reverse geocoder, from the sharer's coordinates | none | trim, drop if blank, first 80 | `WayMoment.place` |
| `encounters[].transcript` (voice) | the sharer's transcription | ≤ 60,000 (`:13,157-158`); the worker trims and cuts to 600 (`tour.ts:18-23`) | trim, drop if blank, first 600 | `WayMoment.transcript` |
| `encounters[].label` (waypoint) | the sharer | ≤ 100 (`:4,68-70`) | first 80, **not trimmed**; absent → `""` | the waypoint's label |
| `encounters[].icon` (waypoint) | the sharer | ≤ 50 (`:5,71-73`) | first 64, not trimmed; absent → `"mappin"` | an SF Symbol name |
| `weather_condition` | the sharer | none | first 64, not trimmed | `WayWeather.condition` |
| `tz_identifier` | the sharer | none | **nothing**: kept whole | `Way.tzIdentifier` |
| `start_date` | the sharer | presence only (`:20`) | parsed (§4.4) | `departedAt` |

"Characters" are Swift `Character`s, i.e. extended grapheme clusters. Android's `prefixCharacters` counts with `BreakIterator.getCharacterInstance(Locale.ROOT)` (`domain/honor/Way.kt:297-315@636cf5ce`); the own-walk port already uses it for transcripts and should reuse it for the 80 and 64 caps.

#### 7.2 What "trim" removes on each side

iOS trims with `CharacterSet.whitespacesAndNewlines`. Kotlin's `String.trim()` trims `Char.isWhitespace()`, which on the JVM is `Character.isWhitespace(c) || Character.isSpaceChar(c)`. Android's `WayMoment.trimmedTranscript` uses `trim()` (`domain/honor/Way.kt:137-141@636cf5ce`). Probed membership:

| Code point | Swift trims | Kotlin `trim()` trims |
|---|---|---|
| U+0009–U+000D, U+0020, U+00A0, U+1680, U+2007, U+2028, U+2029, U+202F, U+205F, U+3000 | yes | yes |
| U+001C–U+001F (information separators) | **no** | **yes** |
| U+0085 (next line) | **yes** | **no** |
| U+200B (zero-width space) | **yes** | **no** |
| U+FEFF, U+180E | no | no |

So a title place, a voice place, or a transcript that starts or ends with U+0085, U+200B, or U+001C–U+001F trims differently. A place made only of U+200B is dropped on iOS and kept on Android. To match, the port trims with Swift's set (the rows above), not `trim()`. The existing own-walk `trimmedTranscript` has the same gap for own-walk transcripts.

#### 7.3 No control or bidirectional character is stripped, anywhere

- The importer's only text operations are `trimmingCharacters(in: .whitespacesAndNewlines)` and `prefix(_:)` (§5.3, §5.4). `whitespacesAndNewlines` contains none of the bidirectional controls, so they survive trimming even at the ends. Probe: `"\u{202E}abc\u{202C}  "` trims to `202E 0061 0062 0063 202C`.
- A search of the whole app at the pin, `git grep -n -i "bidi\|controlCharacters\|illegalCharacters\|202E\|202A\|2066\|200F\|200E\|isControl\|properties.generalCategory\|unicodeScalars.filter\|sanitiz" 7c200bf -- Pilgrim`, finds no text sanitizing: only astronomy timestamps that contain "2066", a "Bidirectional conversion" doc comment, a photo-filename sanitizer for `.pilgrim` packages, and recording-file sanitizers in crash recovery.
- The worker strips nothing either: `tourTranscript` only trims and cuts (`tour.ts:19-23@2a4f5d0`), and `validate-share.ts` checks only lengths and types.
- Nothing downstream isolates the text. Each sink below renders the stored string as it is, mostly through `Text(String)`.

So on iOS a sharer can put U+202E (right-to-left override), U+2066–U+2069 (isolates), U+200E/U+200F, C0 controls such as U+0000 or U+001B, or line breaks inside a place, label, transcript, or weather condition, and they reach every sink unchanged, within the length caps (each control counts as one character toward 80 or 600). `tz_identifier` has no cap at all; it is only ever passed to `TimeZone(identifier:)`, which returns nil for anything unknown (`WayMomentPreview.swift:58@7c200bf`; §8.14).

#### 7.4 Where sharer text goes

The title is the widest-reaching field:

```swift
    static func arrivalWaypointLabel(wayTitle: String) -> String {
        String(format: arrivalLabelFormat, wayTitle)
    }
```
> Pilgrim/Models/Honor/HonorPersistence.swift:30-32@7c200bf

```swift
    private static let arrivalLabelFormat = NSLocalizedString(
        "honor.arrival.label", value: "Walked their way: %@",
        comment: "Waypoint label at the end of an honored Way; %@ is the Way's title.")
```
> Pilgrim/Models/Honor/HonorPersistence.swift:42-44@7c200bf

```swift
        addWaypoint(label: HonorPersistence.arrivalWaypointLabel(wayTitle: way.title),
                    icon: HonorPersistence.arrivalWaypointIcon)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:251-252@7c200bf

```swift
            .map { wp in
                SharePayload.Waypoint(
                    lat: wp.latitude,
                    lon: wp.longitude,
                    label: wp.label,
                    icon: wp.icon,
                    ts: Int(wp.timestamp.timeIntervalSince1970)
                )
            }
```
> Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:413-421@7c200bf

```ts
      if (typeof wp.label !== "string" || wp.label.length > MAX_WAYPOINT_LABEL_LEN) {
        return `waypoint label must be a string of ${MAX_WAYPOINT_LABEL_LEN} chars or fewer`;
      }
```
> pilgrim-worker src/handlers/validate-share.ts:68-70@2a4f5d0

```swift
        if let title = story.wayTitle { text += " The Way: \(title)." }
```
> Pilgrim/Models/Prompt/PromptAssembler.swift:198@7c200bf

| Sink | Field | Reaches outside the device? |
|---|---|---|
| the overview's header, the Ways sheet row, Settings → Ways, the arrival card, the summary's heading (`HonorOverviewView.swift:229`, `HonorWaysSheet.swift:128`, `WaysListView.swift:47`, `WayPlaceCard.swift:363`, `HonorSummarySection.swift:63`, all `@7c200bf`) | title | no |
| the arrival waypoint label, `Walked their way: <title>`, stored on the walk | title | **yes**: the `.pilgrim` export carries waypoint labels (`PilgrimPackageConverter.swift:161@7c200bf`), and a later share of that walk posts the label verbatim, uncut, to the worker, which renders it on a public page |
| the AI prompt, `" The Way: <title>."` (and `" Named: <title>."` for stages, `PromptAssembler.swift:189`) | title | yes, when the walker copies the prompt into an outside assistant |
| the waypoint card's kicker and the map callout (`WayMomentHeader.swift:65`, `PilgrimMapView+HonorWay.swift:134`) | label | no |
| the waypoint glyph (`UIImage(systemName: icon) == nil ? "mappin" : icon`, `WayMomentHeader.swift:54`) | icon | no; an unknown name draws `mappin` |
| the card subline and the preview line (`WayMomentHeader.swift:94-101`, `WayMomentPreview.swift:62`) | place | no |
| the card's one line and the preview (own-walk spec A §3) | transcript | no |
| the overview's weather line, `they walked this in <condition>…`, where an unknown condition "passes through untouched" (`HonorOverviewView.swift:26-42@7c200bf`) | weather condition | no |
| the preview's clock time zone | `tz_identifier` | no |

Two consequences that are defects in their own right, independent of control characters:

- **The arrival label can break a later share.** A title can be 163 characters, so the label can be 18 + 163 = 181. The worker rejects any waypoint label over 100 UTF-16 units, and that rejection fails the whole share POST. A walker who honored a shared walk with long place names, then tries to share their own walk with waypoints included, cannot. (The plan's deferred list already names this; iOS defect 1 gives the evidence.)
- **The prompt carries sharer text into an outside assistant**, unescaped, after "The Way:". The sharer can write instructions there. Low impact, since the walker reads the prompt before pasting it.

#### 7.5 Decision: is stripping parity, or a platform hardening?

Evidence: iOS strips nothing (§7.3), the worker strips nothing, and no Android platform sink exists that iOS lacks. The Honor notification glance carries no title (its strings are fixed, own-walk spec D §10.2), and logging of shared content is already forbidden on Android by the plan's Global Constraints, so the two Android-only places where sharer text might have surfaced are closed already. Compose `Text`, like SwiftUI `Text`, resolves bidirectional text per paragraph, so an override's reach on screen is the same on both.

Recommendation: **parity, plus an upstream issue.** Android stores and shows sharer text exactly as iOS does (trim with Swift's set, cut by grapheme count, nothing stripped), and files a `pilgrim-ios` issue proposing one shared rule for sharer text at the importer: strip C0 and C1 control characters and the bidirectional embeddings, overrides, and isolates (U+202A–U+202E, U+2066–U+2069) from every sharer string, and cut `tz_identifier` as the pilgrimage importer already does (`PilgrimageWayImporter.swift:193@7c200bf`, 80 characters). If iOS adopts it before the gate, Android folds it in under R2. Reasons:

1. The owner's standing rule for this port: an iOS defect is matched as shipped and fixed upstream first, and Android never diverges alone. Stripping is not a platform equivalent under R6 (it answers no platform difference), so it could only enter as a deliberate addition the owner explicitly chooses.
2. Stripping on one platform makes the persisted arrival label differ between platforms for the same honored share, and that label leaves the device in `.pilgrim` files and shares. Parity keeps those bytes identical until both platforms change together.
3. Order matters if anyone does strip: iOS cuts at 80 characters with the controls counted. Stripping before the cut admits more visible characters than iOS, and stripping after it gives a shorter result. A shared upstream rule settles that once.

If the owner prefers to harden now anyway, the narrowest form that keeps stored and exported bytes identical is display-time isolation (wrap each sharer string in U+2068…U+2069, or render through `BidiFormatter.unicodeWrap`) rather than import-time stripping. It changes only how a hostile string looks, and it would be recorded at the gate as a dated R6 addition.

### 8. Edge cases

#### 8.1 A share with no voices

A manifest whose encounters are only `departure` and `arrival` (or only waypoints and rests) imports normally. `voiceCount + photoCount == 0`, so the overview's counts line reads `a quiet way` (own-walk spec F §10.3), and the Ways sheet row shows the counts line rather than `voices returned to the trail`, because the sheet tests `withMedia.contains(way.id) || way.voiceCount + way.photoCount == 0` (`HonorWaysSheet.swift:135-136@7c200bf`). `gather` → `download` finds no files, sets `progress` to 1 without going active, and the state is `.ready` at once (§6.2). Begin is enabled immediately.

#### 8.2 No route, or too little route

Fewer than 2 points, or more than 2000: `.unavailable` (row 9). Two or more points totalling under 20 m: `.unavailable` (row 28; pinned by `testRouteShorterThanTheFloorIsUnavailable`). The worker requires at least 2 points (`validate-share.ts:18@2a4f5d0`) but checks no length, so a share of a walk that never moved is accepted by the worker and refused by the app as "couldn't reach the walk".

#### 8.3 A huge route, or a huge manifest

Both apps send at most 200 points (§5.2), so 2000 is reached only by a hand-made or hostile manifest. Past it, the whole share is `.unavailable`; the importer never samples down. A body over 2 MiB stops mid-stream (row 5). A manifest just under 2 MiB that passes every check is held in memory whole, decoded, and written to `way.json`; nothing else bounds it. The 200-encounter and 200-sitting caps bound the moment list.

#### 8.4 Duplicate moments

Nothing deduplicates. Two identical encounters become two moments with consecutive ids at the same frac, ordered by id. Two voice encounters with the same `n` become two voice moments that point at the same `audio/<n>.m4a`; the downloader fetches that file once, since `mediaFiles` drops repeated paths (`WayMediaDownloader.swift:44-60@7c200bf`), and both moments play it. Duplicate route points are kept (§5.2).

#### 8.5 An already-accepted share, re-opened

- **By link or paste:** the coordinator never consults the store. It refetches. On success, `save` overwrites `way.json` with the new build and keeps the first `acceptedAt`, so the Way keeps its place in "Shared with you" (§3.4); then the overview opens and gathers only the missing files. Offline, the walker sees `couldn't reach the walk` although the Way, and possibly all its media, is on the phone. If the worker has since dropped the share, the walker sees the not-found line about a walk that is still listed.
- **From "Shared with you":** `onChoose` → `openOverview` straight from the stored Way, with no network fetch of the manifest. `gather` downloads whatever is missing.
- **While its overview is already open:** the success sets the same Way again. `download` returns early because the Way is `active` (`WayMediaDownloader.swift:124@7c200bf`), so nothing is enqueued twice.
- **After the walker deleted it in Settings → Ways:** a fresh import, a new `acceptedAt`, a full media download. Deleting the Way removed every walk's link to it (own-walk spec A §13), and re-importing does not restore them, so earlier honor walks of it still read "a way that has been removed".

#### 8.6 A share whose expiry has passed at import

The importer checks `expires > now` before anything else in the manifest (§4.2). If the worker still serves it (before the daily 03:00 UTC cron, or from the edge cache's hour), the result is `.failed(.returnedToTrail)`: `This walk has returned to the trail`, in rust under the paste field when the sheet is open, otherwise as a 5 s toast. If the cron has deleted it, the result is a 404: `couldn't find that walk. Check the link, or it may have returned to the trail.` Nothing is saved in either case, so a walked share that the sweep already retired is not overwritten. iOS pins the manifest half (`testExpiredManifestIsReturnedToTrail`, `WayImporterTests.swift:49-53@7c200bf`).

#### 8.7 A share that expires after import

The local sweep handles it (own-walk spec A §15; the sweep cluster for U28): an unwalked share goes whole, a walked one loses `media/`. The importer is not involved.

#### 8.8 A retired, walked share chosen from the list

After the sweep, a walked expired share stays listed as `voices returned to the trail`. Choosing it opens the overview, and `gather` → `download` enqueues every missing file again, because `download` checks neither `expires` nor whether the media were retired. If the worker still has the files (before its cron, or from the 86,400 s edge cache on audio and photos, `index.ts:32,36@2a4f5d0`), they come back until the next sweep; otherwise every file fails and the overview ends in `.mediaMissing`. Reported to the download cluster; the import states it produces are the ordinary ones.

#### 8.9 A link to a static share

A share made without the interactive tour has a page but no `tour.json` (§2.3), so its link imports as a 404: `couldn't find that walk. Check the link, or it may have returned to the trail.` The link was right and the walk has not expired, so both suggestions in the copy are wrong.

#### 8.10 An import cancelled after its body arrived

A newer `openWay` or a walk start cancels the task, but `importShare` has no cancellation check between the decode and `store.save` (§3.3). If the cancel lands after the body is read, the superseded share is still saved and appears in "Shared with you", though no overview ever opened for it. The same happens when the import succeeds after Begin or during a walk: the coordinator drops the result "silently", but the Way is already in the store (`MainCoordinatorView.swift:228-232@7c200bf`).

#### 8.11 Opening the Ways sheet while a link is still importing

`chooseWay` sets the state to `.idle` and clears the toast but does not cancel the import task (§6.3). So the sheet opens with no line, the "Open" button is enabled during the fetch, and when the import resolves: a success parks the Way and **closes the sheet the walker just opened**, then the overview appears; a failure shows its line under the paste field.

#### 8.12 The device clock

`now` is the phone's clock, while the worker expires and deletes on its own. A phone running ahead reads a live share as `This walk has returned to the trail`. A phone running behind imports a share the worker considers expired, as long as the worker still serves it, and its media then 404 from the worker after the cron.

#### 8.13 The worker's cache headers

`tour.json` is served with `max-age=3600`. Cloudflare's edge may serve a cached copy for up to an hour, including after the cron deletes the object. On the device, the importer's session is `.ephemeral`, which keeps an in-memory `URLCache`; whether it answers a second import of the same id inside the hour without the network is not settled by source (Open question 1). Android's OkHttp client has no cache unless one is configured, so it always goes to the network.

#### 8.14 Time zone identifiers

`tz_identifier` is used only to format the preview's clock time (`WayMomentPreview.swift:58@7c200bf`, filed as part of [pilgrim-ios #110](https://github.com/walktalkmeditate/pilgrim-ios/issues/110)), through `TimeZone(identifier:)` with the device's zone as the fallback. Probe: `TimeZone(identifier:)` accepts `Europe/Madrid`, `UTC`, `GMT+9`, and `EST`, and returns nil for `garbage`, `europe/madrid`, and `+09:00`. `java.time.ZoneId.of` throws for `garbage`, `europe/madrid`, and `EST`, and accepts `+09:00` and `GMT+9`. The port must catch the exception and fall back to the device zone; the two platforms still differ on `EST` and `+09:00`. Neither app writes those (both send a region id: `TimeZone.current.identifier` at `WalkShareViewModel.swift:389@7c200bf`, `zoneId.id` at `data/share/SharePayloadBuilder.kt:384@636cf5ce`).

#### 8.15 Shares the worker accepts and the app refuses

- **Encounter count.** The worker allows 12 recordings, 20 photos, 100 waypoints, and 200 pauses (`validate-share.ts:3,6,9,15@2a4f5d0`; iOS sends at most 200 pauses, `WalkShareViewModel.swift:458@7c200bf`). With `departure` and `arrival`, a manifest can carry 2 + 12 + 20 + 100 + (pauses of 3 minutes or more) encounters, which passes 200 once a walk with many waypoints has about 67 long pauses. The app then says `couldn't reach the walk`.
- **Rest minutes.** The worker writes `Math.round(seconds / 60)` with no ceiling (`tour.ts:81-90@2a4f5d0`), and the app refuses any `minutes` over 1440 (row 20). One pause of 86,430 s (24 h 0 min 30 s) or more rounds to 1441 and makes the share unimportable. The worker clamps sittings to 6480 s for exactly this reason (`tour.ts:11-13`) but not rests.
- **Route length.** Under 20 m (§8.2).

All three fail the whole share rather than dropping the one bad part.

#### 8.16 A link tapped while an overview is open

The global state goes to `.fetching`, so the open overview (own walk or shared) shows `reaching for the walk…` and disables Begin (own-walk spec F §13.1). The toast is raised too, behind the sheet. On success the state passes through `.idle` and the overview's item is replaced by the new Way, which gathers. On failure the old overview keeps the rust failure line, with Begin enabled, until it closes.

#### 8.17 The store cannot write

A failed `save` (a full disk, a write error) reads `couldn't reach the walk` (§6.4), and nothing is listed. The disk-full line is reserved for media.

### 9. Drift traps for the Kotlin port

1. **Defaults on required fields.** Give no default to any required manifest property (§2.4), or a missing key silently becomes a value instead of "couldn't reach the walk".
2. **`trim()` is not Swift's trim.** Use the Swift set for title places and voice places (§7.2), and fix the own-walk `trimmedTranscript` the same way.
3. **Count graphemes, not chars.** `prefix(80)` and `prefix(64)` are grapheme counts; reuse `prefixCharacters` (§7.1). The 2 MiB cap is bytes.
4. **Check order.** Expiry before counts and ranges (§4.2); status before the body; the id before the network.
5. **Validate skipped encounters too** (rows 17–22).
6. **Saturation instead of traps.** Every bound gets its own rejecting test, since a missed one in Kotlin produces a wrong value rather than a crash.
7. **`"mappin"` only for an absent icon.** An empty `icon` stays empty in the model; the renderer falls back.
8. **The waypoint label and the weather condition are cut but not trimmed.**
9. **Sitting estimates take the first matching segment, both ends inclusive** (§5.5).
10. **Moment order is `(frac, id)` with a plain string compare** (§5.7).
11. **The title's date fallback uses the device's locale and zone at import and is frozen** (§5.3).
12. **One import state for the app**, shared by the sheet and the overview, reset by opening the sheet and by a real overview close (§6.3). A newer link cancels the older fetch, and a cancelled fetch must not write state.
13. **IO off the main thread.** The fetch, decode, build, and save run on `Dispatchers.IO`; the state updates on Main.
14. **Never log** the manifest, the decode exception (kotlinx messages quote input), the id, or any text from it (Global Constraints). iOS prints the decode error; Android does not.
15. **A dedicated OkHttp client**, 15 s connect and read, 30 s call, no cache; the shared 10/30/45 s client is wrong for this.

### Resolutions for the plan

1. **Acceptance happens at import, not at the overview or Begin** (§1, §3.4). iOS saves the Way, writing `accepted.json`, inside `importShare`, as soon as a link, a paste, or a held link is opened and the manifest passes. The overview opens only after that, and the media gather starts when the overview is shown (§6.3). Closing the overview or starting a walk drops only the observation; downloads continue. The plan's "import starts only on an explicit overview open" should read: the manifest import starts when a link is opened (tap, paste, the held link after setup, or the install referrer landing "like a tap"); the media gather starts when the overview shows; nothing ever auto-Begins.
2. **Redirects** (§3.2). iOS follows HTTPS redirects to any host: no session delegate, no redirect handler at the pin; ATS blocks `http`. The plan's "refuses redirects to another host" is therefore an Android addition, not parity. Against the real worker, which never redirects `tour.json`, and against a captive portal, whose HTML fails to decode, both give the same outcome, `couldn't reach the walk`. Record it at the gate as a dated R6 addition (it is already a plan decision) or drop it; it cannot be iOS parity. Android's default cleartext policy (targetSdk 36, no `usesCleartextTraffic`) matches ATS for `http`.
3. **The failure mapping holds, with its order** (§3.3, §4.2): a malformed id is `notFound` before the network; a 404 is `notFound`; any other non-200 status, an oversized declared length, an oversized body, any transport error (offline included), a decode failure, every count and range failure, a short route, and a failed save are all `unavailable`; only an expiry not later than now is `returnedToTrail`, and it is checked before every count and range bound. There is no offline state or copy.
4. **The bound list is 29 rows** (§4.3). The plan's list maps onto it; add the ones it does not name: `encounters[].n` 1…10,000, `end_frac` and segment fracs 0…1, non-decreasing `ts`, `|alt| < 100,000` (strict), `ts` 0…4,102,444,800, the 2000-point ceiling, both 200 caps (encounters counting `departure`, `arrival`, and unknown kinds), and the store write. Every encounter is validated, including the kinds that are then skipped.
5. **No list of missing voices** (§6.6). iOS shows `some voices didn't arrive` and the two buttons, and nothing names which files are missing. U28's "a partial gather shows which voices are missing" and its partial-state test should assert the iOS copy and the two buttons, not a list.
6. **The overview's trouble states** (§6.5). `try again` and `walk without the missing voices` appear only in `.mediaMissing`. Disk full shows only `not enough space on this phone to save these voices` in rust, with no buttons. Begin is enabled in both, and disabled only in `.fetching` and `.gathering`. U28's disk-full test ("Begin still possible without the missing voices") holds as Begin enabled, not as a button.
7. **"walk without the missing voices" sets `.ready` and stops observing** (§6.3): later download changes no longer move that overview's state.
8. **The import's session** (§3.2): its own client, 15 s idle, 30 s total, no retry, no cache. The link toast lasts 5 s (§6.3). Android's shared 10/30/45 s client must not be used.
9. **Copy is English and unlocalized on iOS** (§6.1): no localization keys exist for any import string. Android puts the seven import lines and `finish this walk first` (§6.1), the sheet's `paste a walk link`, `Open`, `From a shared walk`, and footer, and the overview's `try again` and `walk without the missing voices` (§6.5) in `strings.xml` with these exact English values.
10. **Text treatment** (§5.3–§5.4, §7.1–§7.2): places and transcripts are trimmed with Swift's whitespace set and cut by grapheme count (80, 600); labels and the weather condition are cut (80, 64) but not trimmed; icons are cut (64) and default to `mappin` only when absent; `tz_identifier` is kept whole. Android's `trim()` differs from Swift's set in six code points (U+001C–U+001F, U+0085, U+200B) and needs the Swift set.
11. **Sharer-text stripping: iOS parity plus an upstream issue** (§7.5). iOS strips nothing, and no Android-only sink needs it. File the issue (defect 2 below); fold in any iOS fix under R2. If the owner wants hardening before iOS moves, prefer display-time isolation, recorded at the gate as an R6 addition, over import-time stripping.
12. **Logging** (§3.3). iOS `print`s the decode error, which can quote input. Android logs nothing from the import, as the plan says. This is invisible to users (stdout is not kept on a device) and needs no gate row beyond the logging constraint itself.
13. **`v` is never checked** (§2.5). Android must not add a version gate.
14. **Required fields carry no defaults** in `TourManifest.kt` (§2.4); the plan's institutional learning on `encodeDefaults` applies on the decode side too.
15. **Dates** (§4.4): every `start_date` and `expires` either app or the worker writes parses on both sides. The three strings where Foundation and `java.time` disagree (`+HHMM`, lower case, an out-of-range day) are unreachable; Open question 2 asks whether to match them anyway.
16. **The title's date fallback is in the device's locale and zone, at import** (§5.3), and every Android-made share takes it, because Android posts no place names.
17. **A cancelled or dropped import may still have saved the Way** (§8.10). Match as shipped; defect 5 below.

### iOS defects found

Filed on 2026-10-01 as listed in "Matched as shipped, and filed upstream" at the top of this spec. Numbered for this cluster (S1-D*n*).

1. **S1-D1. The arrival label can make a later share fail.** `Walked their way: <title>` is stored uncut (`HonorPersistence.swift:30-32`, `ActiveWalkViewModel+Honor.swift:251-252`), a shared Way's title can be 163 characters (`WayImporter.swift:233-240`), and the share payload posts waypoint labels verbatim (`WalkShareViewModel.swift:413-421`), which the worker rejects over 100 (`validate-share.ts:68-70@2a4f5d0`), failing the whole share. The plan's deferred iOS list already names it; this is the evidence. Medium.
2. **S1-D2. Sharer text is never sanitized.** Control and bidirectional characters in places, labels, transcripts, and the weather condition reach the overview, the cards, the map, the summary, the arrival label (and from there `.pilgrim` exports and public share pages), and the AI prompt (§7.3–§7.4). `tz_identifier` is stored uncut, while the pilgrimage importer cuts the same field to 80 (`PilgrimageWayImporter.swift:193`). Treatment is also uneven: places and transcripts are trimmed, labels and the weather condition are not, and an empty `weather_condition` makes a weather line reading `they walked this in .` (`HonorOverviewView.swift:26-35`). Medium (hostile shares only).
3. **S1-D3. The worker accepts shares the app then refuses.** Up to 334 encounters against the app's 200; rest minutes with no ceiling against the app's 1440; routes under 20 m (§8.15, §8.2). Each makes the share `couldn't reach the walk` for every recipient, and the sharer is never told. Fix on either side: the worker clamps rests as it clamps sittings and caps encounters, or the app drops the excess instead of refusing. Low (rare walks), affects both platforms.
4. **S1-D4. A link never consults the store.** Re-opening an accepted share by link refetches; offline it reads `couldn't reach the walk`, and after the worker drops the share it reads `couldn't find that walk…`, while the Way sits in "Shared with you" (§8.5). Low.
5. **S1-D5. A superseded import can still land.** `importShare` saves after decoding with no cancellation check, so a cancelled import, or one that resolves after Begin or during a walk, puts its Way in "Shared with you" though no overview opened (§8.10). Low.
6. **S1-D6. Missing-media copy names voices when photos are missing, and offers a retry that cannot help.** `.mediaMissing` covers failed photos and files refused by the 12/20 ceilings; the copy is `some voices didn't arrive` and the buttons are `try again` and `walk without the missing voices`, and a retry refuses the same ceiling files again (§6.2, §6.6). Low.
7. **S1-D7. A full disk while saving the manifest reads "couldn't reach the walk".** The store's write error is folded into `.unavailable` (`MainCoordinatorView.swift:236`), so the disk-full line is never shown for it (§6.4). Low.
8. **S1-D8. A static share's link says the link is wrong.** A walk page made without the interactive tour has no `tour.json`, so its valid link reads `couldn't find that walk. Check the link, or it may have returned to the trail.` (§8.9). Low.
9. **S1-D9. Opening the Ways sheet during a link import.** `chooseWay` resets the state but leaves the import running, so "Open" is enabled mid-fetch and a late success closes the sheet the walker just opened (§8.11). Low.

### Open questions

1. **Does the ephemeral session cache `tour.json` in memory?** The worker sends `Cache-Control: public, max-age=3600`, and `URLSessionConfiguration.ephemeral` keeps an in-memory `URLCache`. If URLSession serves a second import of the same id from that cache within the hour, iOS can re-open a share offline that Android (no OkHttp cache) cannot. Settle with one iPhone run (import, airplane mode, re-tap the link) before U28 decides whether Android needs a matching memory cache.
2. **Match Foundation's three date quirks, or accept the shared subset?** (§4.4) None is reachable from either app or the worker. Matching them means a hand-written parser; accepting `java.time`'s set means three dated unreachable differences at the gate.
3. **kotlinx and quoted numbers.** If kotlinx accepts `"ts":"1000"` (iOS refuses it, §2.4), Android imports a manifest iOS rejects. Unreachable from the worker; U28 should pin the behavior with a test either way.
4. **Spans with the same `startFrac`.** iOS's `sort` is not documented as stable (§5.6); Kotlin's is. Unreachable from the worker unless two segments start at the same instant, but the ghost-line order of overlapping spans could differ.
5. **Duplicate JSON keys.** Apple's decoder keeps the first value (§2.4). Unreachable from `JSON.stringify`; noted for fixture authors.
6. **The owner's call on sharer text** (§7.5): parity plus the upstream issue, or display-time isolation now as a dated R6 addition.

---

## S2. Honor links: tapped, pasted, held, and routed

iOS pin: `pilgrim-ios` @ `7c200bf`. Android HEAD: `636cf5ce`. Feeds U27 (links, the hold, the routing, the install referrer). The paste field and its import line are shared with U28.

Read in full at the pin:
- `Pilgrim/Models/Honor/HonorLink.swift`
- `Pilgrim/PilgrimApp.swift`
- `Pilgrim/AppDelegate.swift`
- `Pilgrim/Scenes/Root/MainTabView.swift`
- `Pilgrim/Scenes/Root/MainCoordinatorView.swift`
- `Pilgrim/Scenes/Root/RootCoordinatorView.swift`
- `Pilgrim/Scenes/Root/RootCoordinatorViewModel.swift`
- `Pilgrim/Scenes/Setup/SetupCoordinatorView.swift`
- `Pilgrim/Pilgrim.entitlements`
- `Pilgrim/Models/Honor/HonorImportReducer.swift`
- `UnitTests/Honor/HonorLinkTests.swift`
- `UnitTests/Honor/MainCoordinatorHonorTests.swift`

Read in part: `Pilgrim/Scenes/Honor/HonorWaysSheet.swift` (the paste section), `Pilgrim/Models/Honor/WayImporter.swift` (the id check, the fetch URL, the error mapping), `Pilgrim/Models/Honor/WayStore.swift` (id validation, `save`), `Pilgrim/Models/Honor/WayMediaDownloader.swift` (`download`'s guard), `Pilgrim/Scenes/Honor/HonorOverviewView.swift` (the import line and Begin's gate), `Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift` (the meditation cover), `Pilgrim/Scenes/Home/HomeView.swift` (its sheets), `Pilgrim/Support Files/Info.plist` (checked for URL schemes), and `Pilgrim.xcodeproj/project.pbxproj` (checked for product types).

Checked: all 62 quoted code blocks (Swift, the plist, and the worker's TypeScript) were machine-checked. Each matches its cited lines exactly, at the cited commit.

Context only, not iOS: `pilgrim-worker` @ `2a4f5d0` (the honor page, the iOS and Android association files, `tour.json` serving). The worker isn't parity source. It's quoted only where iOS's behavior depends on what the server sends.

Own-walk spec F already covers the doors, the Ways sheet layout, the park-and-promote rules and the overview. Those are cited here as "own-walk spec F §n", not re-derived.

### 1. The parser: `HonorLink`

#### 1.1 The whole file

One type turns a URL or pasted text into a share id. Two hosts, one id shape:

```swift
/// The one place a share link becomes a share id, for both a universal link
/// the OS hands us and text the walker pasted.
enum HonorLink {

    static let hosts: Set<String> = ["honor.pilgrimapp.org", "walk.pilgrimapp.org"]
    private static let idPattern = "\\A[A-Za-z0-9_-]{10}\\z"

    static func parse(_ url: URL) -> String? {
        guard let host = url.host?.lowercased(), hosts.contains(host) else { return nil }
        let parts = url.pathComponents.filter { $0 != "/" }
        guard parts.count == 1, isID(parts[0]) else { return nil }
        return parts[0]
    }

    static func parse(text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if isID(trimmed) { return trimmed }
        let withScheme = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        guard let url = URL(string: withScheme) else { return nil }
        return parse(url)
    }

    private static func isID(_ s: String) -> Bool {
        s.range(of: idPattern, options: .regularExpression) != nil
    }
}

extension Notification.Name {
    static let pilgrimOpenWay = Notification.Name("pilgrimOpenWay")
}
```
> Pilgrim/Models/Honor/HonorLink.swift:3-33@7c200bf

The rules this encodes:

- **Hosts.** Exactly `honor.pilgrimapp.org` and `walk.pilgrimapp.org`, compared after `lowercased()`. Subdomains, look-alikes and a trailing dot are rejected, because it's a set lookup, not a suffix match. `url.host` excludes any port and userinfo, so `https://x@walk.pilgrimapp.org:8443/Qoi4YmPHLN` passes. That's harmless, because the fetch never uses the link's host (§1.3).
- **The scheme is never checked.** `parse(_ url:)` reads only host and path. For a tapped link the OS only ever delivers `https` (§2). For pasted text, any `scheme://` passes through unchanged (`ftp://walk.pilgrimapp.org/Qoi4YmPHLN` parses), and text without `://` gets `https://` prepended.
- **The path.** It must be exactly one non-`/` component, and that component must be the whole id. Foundation's `pathComponents` returns `"/"` as the root component and percent-decodes each component. Query and fragment are not in the path, so they're ignored.
- **The id.** `\A[A-Za-z0-9_-]{10}\z`: ten characters from that set, anchored at both ends with `\A` and `\z`, so a trailing newline can't slip past the way it can with `$`. The id is case-sensitive and never folded.
- **Text form.** Whitespace and newlines are trimmed from both ends first. A bare id is accepted as-is. Anything else must parse as a URL with one of the two hosts.

#### 1.2 The pinned cases

```swift
    func testAcceptedForms() {
        for s in ["https://honor.pilgrimapp.org/Qoi4YmPHLN", "https://honor.pilgrimapp.org/Qoi4YmPHLN/",
                  "https://honor.pilgrimapp.org/Qoi4YmPHLN?utm=x#m3", "https://walk.pilgrimapp.org/Qoi4YmPHLN",
                  "walk.pilgrimapp.org/Qoi4YmPHLN", "Qoi4YmPHLN", "  Qoi4YmPHLN\n"] {
            XCTAssertEqual(HonorLink.parse(text: s), "Qoi4YmPHLN", s)
        }
        XCTAssertEqual(HonorLink.parse(URL(string: "https://honor.pilgrimapp.org/Qoi4YmPHLN")!), "Qoi4YmPHLN")
    }

    func testRejections() {
        for s in ["https://example.com/Qoi4YmPHLN", "https://walk.pilgrimapp.org/", "https://walk.pilgrimapp.org/short",
                  "https://walk.pilgrimapp.org/Qoi4YmPHLN/audio/1.m4a", "Qoi4YmPHL", "Qoi4YmPHLN1", ""] {
            XCTAssertNil(HonorLink.parse(text: s), s)
        }
    }

    func testHostIsCaseInsensitive() {
        XCTAssertEqual(HonorLink.parse(text: "https://HONOR.pilgrimapp.org/Qoi4YmPHLN"), "Qoi4YmPHLN")
        XCTAssertEqual(HonorLink.parse(URL(string: "https://HONOR.pilgrimapp.org/Qoi4YmPHLN")!), "Qoi4YmPHLN")
    }
```
> UnitTests/Honor/HonorLinkTests.swift:6-25@7c200bf

```swift
    /// `$` in a regex matches before a trailing newline, so an id pasted with
    /// one used to pass every shape check in the pipeline.
    func testTrailingNewlineIsRejected() {
        // Pasted text is trimmed first, so the newline has to arrive by a
        // path that does not trim — an encoded one inside the URL itself.
        XCTAssertNil(HonorLink.parse(URL(string: "https://honor.pilgrimapp.org/Qoi4YmPHLN%0A")!))
        XCTAssertFalse(WayImporter.isShareId("Qoi4YmPHLN\n"))
        XCTAssertFalse(WayStore.isValidId("share:Qoi4YmPHLN\n"))
        XCTAssertFalse(WayStore.isValidId("walk:\(UUID().uuidString)\n"))
    }
```
> UnitTests/Honor/HonorLinkTests.swift:41-50@7c200bf

The `%0A` case shows that `pathComponents` decodes percent-escapes before the id check. So an id spelled with escapes (`%51oi4YmPHLN` → `Qoi4YmPHLN`) is accepted, and an escaped slash (`%2F`) decodes into a character the pattern rejects. Android's `Uri.getPathSegments()` also decodes, and also drops empty segments, as Foundation does for `//`. Both of those cases follow from Foundation's behavior, not from a test; see §11.

#### 1.3 The id is checked twice more downstream, and the fetch ignores the link's host

The importer re-checks the shape with the same anchored pattern. It always fetches from the walk host, whichever host the link named:

```swift
    static let baseURL = URL(string: "https://walk.pilgrimapp.org")!
```
> Pilgrim/Models/Honor/WayImporter.swift:10@7c200bf

```swift
    /// The importer enforces the id shape itself; it must never depend on a
    /// UI-layer parser having run first.
    static func isShareId(_ id: String) -> Bool {
        id.range(of: "\\A[A-Za-z0-9_-]{10}\\z", options: .regularExpression) != nil
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:25-29@7c200bf

```swift
    func importShare(id: String) async throws -> Way {
        guard Self.isShareId(id) else { throw WayError.notFound }
        let url = Self.baseURL.appendingPathComponent(id).appendingPathComponent("tour.json")
```
> Pilgrim/Models/Honor/WayImporter.swift:51-53@7c200bf

The store refuses any Way id that isn't `share:` plus the same shape:

```swift
    static func isValidId(_ id: String) -> Bool {
        id.range(of: "\\A(share:[A-Za-z0-9_-]{10}|walk:[0-9A-Fa-f-]{36}|pilgrimage:[a-z0-9-]{1,64}:[0-9]{1,3})\\z",
                 options: .regularExpression) != nil
    }
```
> Pilgrim/Models/Honor/WayStore.swift:56-59@7c200bf

Android counterpart: nothing at HEAD (`P/honor/HonorLink.kt` is a U27 create). The house precedent for whole-input matching is `WayStore.isValidId`, which uses `Regex.matches` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/WayStore.kt:380,389-390@636cf5ce`). That's the trap to avoid: in `java.util.regex`, `$` matches before a final line terminator, as it does in ICU, so a `find()` against `^…$` reintroduces the bug this test pins.

### 2. How a tapped link reaches the app

#### 2.1 Only the honor host is associated

```xml
	<key>com.apple.developer.associated-domains</key>
	<array>
		<string>applinks:honor.pilgrimapp.org</string>
	</array>
```
> Pilgrim/Pilgrim.entitlements:7-10@7c200bf

`walk.pilgrimapp.org` is not associated, so a tapped walk link opens Safari on the walk page, even though `HonorLink.hosts` accepts it. The walk host reaches the parser only by paste (§8). That is AE4's "A `walk.pilgrimapp.org` link opens the browser when tapped but imports when pasted."

The app registers no URL scheme: a search of `Pilgrim/Support Files/Info.plist` at the pin finds no `CFBundleURLTypes`. So the only URLs the OS hands Pilgrim are `https://honor.pilgrimapp.org/…` URLs.

Context from the worker: the association file claims every path on the honor host, so iOS opens the app for any honor URL, and the parser decides what happens.

```ts
export const AASA_BODY =
  '{"applinks":{"details":[{"appIDs":["YCF2TGZAX8.org.walktalkmeditate.pilgrim"],"components":[{"/":"/*"}]}]}}';
```
> pilgrim-worker/src/handlers/honor.ts:12-13@2a4f5d0

So `https://honor.pilgrimapp.org/` or `https://honor.pilgrimapp.org/abc` opens Pilgrim and does nothing else (§2.3: `route` returns before any state change).

#### 2.2 Two entry points, one router

```swift
            .onOpenURL { url in Self.route(url) }
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                if let url = activity.webpageURL { Self.route(url) }
            }
```
> Pilgrim/PilgrimApp.swift:47-50@7c200bf

- `onContinueUserActivity(NSUserActivityTypeBrowsingWeb)` is the universal-link path: a tap in Mail, Messages, Notes, or a cross-domain link in Safari (the walk page's "walk this" pill points at the honor host; §10.2).
- `onOpenURL` is reached by the Smart App Banner's "Open" on the honor page, which hands the app its `app-argument` URL (§10.2).

`AppDelegate.swift` (read in full) has no URL or user-activity callback. Its only URL-adjacent method is the background URL session handler for Way media (`AppDelegate.swift:234-245@7c200bf`, U28's).

#### 2.3 `route`: parse, stash, broadcast

```swift
    /// A link tapped on a cold launch arrives before `MainTabView` exists to
    /// hear the notification below — held here so its `onAppear` can claim
    /// it once the launch gate (`RootCoordinatorView`'s `appLaunchState`)
    /// finally mounts the tab view.
    @MainActor static var pendingShareId: String?
```
> Pilgrim/PilgrimApp.swift:31-35@7c200bf

```swift
    /// Broadcast rather than routed directly: `MainTabView` owns the
    /// coordinator, and a link arriving before setup finishes has no
    /// `MainTabView` mounted to hear it. Stashing the id in `pendingShareId`
    /// too means that cold-launch case isn't just broadcast into silence —
    /// `MainTabView.onAppear` drains it once mounted.
    @MainActor
    private static func route(_ url: URL) {
        guard let id = HonorLink.parse(url) else { return }
        pendingShareId = id
        NotificationCenter.default.post(name: .pilgrimOpenWay, object: nil, userInfo: ["shareId": id])
    }
```
> Pilgrim/PilgrimApp.swift:54-64@7c200bf

Every valid link does both things at once:
- it overwrites `pendingShareId`, so the last link wins;
- it posts `pilgrimOpenWay`, which only a mounted `MainTabView` hears.

An invalid link does neither: no toast, no tab switch, no state change. Whichever of the two consumers runs first clears the stash (§3.2, §4.1).

Android counterparts at HEAD:
- `MainActivity` is `exported="true"` and `launchMode="singleTop"`, with no VIEW filter (`app/src/main/AndroidManifest.xml:94-111@636cf5ce`). The MAIN/LAUNCHER filter lives on the nine icon aliases (`AndroidManifest.xml:120-132@636cf5ce` for the default), and only one alias is enabled at a time.
- The debug manifest carries no honor filter yet (`app/src/debug/AndroidManifest.xml:19-64@636cf5ce`).
- The release build is pinned to claim no honor link (`app/src/testRelease/java/org/walktalkmeditate/pilgrim/core/flags/ReleaseBuildContentsTest.kt:64-75@636cf5ce`).
- The cold and warm entries map to `onCreate` and `onNewIntent` (`app/src/main/java/org/walktalkmeditate/pilgrim/MainActivity.kt:76,180-184@636cf5ce`), the same pair the widget link uses.

### 3. Holding a link until the tab view exists (cold start, onboarding)

#### 3.1 The launch gate

`MainTabView`, which owns the coordinator and the link handlers, exists only once the store is open and setup is done:

```swift
    var body: some View {
        switch appDelegate.appLaunchState {
        case .loading:
            LaunchLoadingView()
        case .migration:
            SwiftUI.ProgressView("Migrating data...")
        case .done:
            switch viewModel.rootState {
            case .setup:
                SetupCoordinatorView()
            case .main:
                MainTabView()
            }
        }
    }
```
> Pilgrim/Scenes/Root/RootCoordinatorView.swift:8-22@7c200bf

```swift
    init() {
        self.rootState = RootState(isAppSetUp: UserPreferences.isSetUp.value)
        // sink + [weak self] instead of assign(to:on:), which retains self
        // strongly inside its own cancellables — a retain cycle (AF61).
        UserPreferences.isSetUp.publisher
            .map { RootState(isAppSetUp: $0) }
            .sink { [weak self] in self?.rootState = $0 }
            .store(in: &cancellables)
    }
```
> Pilgrim/Scenes/Root/RootCoordinatorViewModel.swift:30-38@7c200bf

```swift
    static let isSetUp = UserPreference.Required<Bool>(key: "isSetUp", defaultValue: false)
```
> Pilgrim/Models/Preferences/UserPreferences.swift:26@7c200bf

Setup is three phases: threshold, permissions, then the breath. `isSetUp` flips only at the end of the breath:

```swift
            case .breathTransition:
                BreathTransitionView {
                    UserPreferences.applyUnitSystem(metric: Locale.current.measurementSystem == .metric)
                    UserPreferences.isSetUp.value = true
                }
```
> Pilgrim/Scenes/Setup/SetupCoordinatorView.swift:32-36@7c200bf

Once `isSetUp` is true, setup never shows again. Nothing at the pin sets it back to false.

#### 3.2 The drain

```swift
        .onAppear {
            // A link tapped on a cold launch posted before this view existed
            // to receive it (see `PilgrimApp.route`) — claim it exactly once.
            if let id = PilgrimApp.pendingShareId {
                PilgrimApp.pendingShareId = nil
                if coordinator.activeWalkViewModel == nil { selectedTab = .path }
                coordinator.openWay(shareId: id)
            }
        }
```
> Pilgrim/Scenes/Root/MainTabView.swift:98-106@7c200bf

```swift
    /// A cold-launch link is stashed on `PilgrimApp` for `MainTabView.onAppear`
    /// to claim once mounted; reading it must also clear it, so the same id
    /// can't be replayed a second time.
    @MainActor
    func testPendingShareIdIsDrainedOnce() {
        PilgrimApp.pendingShareId = "Qoi4YmPHLN"

        let drained = PilgrimApp.pendingShareId
        PilgrimApp.pendingShareId = nil

        XCTAssertEqual(drained, "Qoi4YmPHLN")
        XCTAssertNil(PilgrimApp.pendingShareId)
    }
```
> UnitTests/Honor/HonorLinkTests.swift:27-39@7c200bf

What "held" means on iOS, read from the code above:

| Question | iOS answer | Evidence |
|---|---|---|
| Where is it held? | A `static var` on the app type, in memory only | `PilgrimApp.swift:35` |
| Through what? | `.loading`, `.migration`, and all three setup phases, including any time the app sits in the background mid-setup | `RootCoordinatorView.swift:9-20` |
| When does it open? | When `MainTabView` first appears, which is just after the breath writes `isSetUp`, or at once on a launch that's already set up | `MainTabView.swift:98-106`, `SetupCoordinatorView.swift:35` |
| Two links before then? | The last one wins; earlier ids are overwritten, not queued | `PilgrimApp.swift:62` |
| The process dies first? | Lost: nothing is written to disk | `PilgrimApp.swift:35` |
| Replayed later? | No: cleared on the drain, and on every warm delivery (§4.1) | `MainTabView.swift:91,102` |
| Any feedback while held? | None: no toast, no banner, nothing in setup | no setup file reads `pendingShareId` (it's referenced only in `PilgrimApp.swift`, `MainTabView.swift` and the test) |

After the drain, the link goes down the same `openWay` path as a warm link (§4.2). On a fresh install that's the Path tab, the "reaching for the walk…" toast, and then the overview.

Android counterparts at HEAD:
- **Setup.** Onboarding is the `WELCOME` → `PERMISSIONS` → `BREATH` → `PATH` routes (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt:175,180-208@636cf5ce`). Two flags gate it: `welcomeCompleted`, which picks the start destination, and `onboardingComplete`, which is written at the Permissions screen's Continue, *before* the breath (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/onboarding/PermissionsScreen.kt:271-272@636cf5ce`, `app/src/main/java/org/walktalkmeditate/pilgrim/permissions/PermissionsRepository.kt:29-37@636cf5ce`).
- **Every launch revisits Permissions.** Unlike iOS's one-time `isSetUp`, Android passes through `PERMISSIONS` on every launch. It auto-navigates to `PATH` only when onboarding is complete and the minimum permissions are granted (`PilgrimNavHost.kt:595-605@636cf5ce`).
- **The widget link's hold is narrower than iOS's.** It waits only while the route is `PERMISSIONS` (`PilgrimNavHost.kt:626-633@636cf5ce`), so it doesn't wait through `WELCOME` or `BREATH`. iOS's hold covers the whole of setup.

The iOS trigger is the tab view appearing, so the Android analogue is arriving at `PATH`, not the `onboardingComplete` write. See Resolutions.

### 4. Routing a link once the tab view exists

#### 4.1 The warm handler: clear, maybe switch tab, open

```swift
        .onReceive(NotificationCenter.default.publisher(for: .pilgrimOpenWay)) { note in
            guard let id = note.userInfo?["shareId"] as? String else { return }
            // Consumed here too so a warm-launch link (this view already
            // mounted) doesn't sit in `pendingShareId` for a later onAppear
            // to replay a second time.
            PilgrimApp.pendingShareId = nil
            // Mid-walk `openWay` refuses and says so with a toast, so the tab
            // must not switch out from under the walker for a link that is
            // about to go nowhere.
            if coordinator.activeWalkViewModel == nil { selectedTab = .path }
            coordinator.openWay(shareId: id)
        }
```
> Pilgrim/Scenes/Root/MainTabView.swift:86-97@7c200bf

- The tab switches to Path whenever no walk is active, even if the link is about to park behind a sheet. The switch happens beneath any presented sheet (§5, rows 6, 10 and 14).
- The overview, the Ways sheet and the post-walk summary are all presented from the tab view itself (`MainTabView.swift:58-85@7c200bf`, own-walk spec F §7). So the tab only decides what lies under them and where the toast shows.
- Close on an overview opened by a link lands on the Path tab.

#### 4.2 `openWay`: refuse, or fetch with a toast

```swift
    /// Fetch happens wherever the user is: inside the Ways sheet the state
    /// renders inline beside the paste field (a not-found error keeps the
    /// field editable); from a link with no sheet open, a toast carries it.
    func openWay(shareId: String) {
        guard activeWalkViewModel == nil else { showLinkToast("finish this walk first"); return }
        importTask?.cancel()
        honorImportState = .fetching
        if !honorWaysPresented { showLinkToast("reaching for the walk…") }
        let fetch = importShare
        importTask = Task { @MainActor [weak self] in
            do {
                let way = try await fetch(shareId)
                // A cancelled import belongs to a link the walker has already
                // replaced; neither its Way nor its error may land on top of
                // the newer one's state.
                guard let self, !Task.isCancelled else { return }
                self.showLinkToast(nil)
                self.honorImportState = .idle
                self.importTask = nil
                // A Begin already in flight (a walk starting, or parked to
                // start once the overview closes) wins — presenting this Way
                // now would race it for the overview sheet or interrupt the
                // walk that's already beginning. Drop it silently.
                guard self.activeWalkViewModel == nil, self.pendingStartWay == nil else { return }
                self.openOverview(for: way)      // AF60-safe: parks or presents, never both
            } catch {
                guard let self, !Task.isCancelled else { return }
                let failure = (error as? WayError) ?? .unavailable
                self.honorImportState = .failed(failure)
                self.showLinkToast(self.honorWaysPresented ? nil : HonorImportCopy.line(for: .failed(failure)))
                self.importTask = nil
            }
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:209-242@7c200bf

The order, step by step:

1. **Refusal.** With a walk active, the "finish this walk first" toast shows, and nothing else changes: no cancel, no state change, no fetch (§6).
2. **A new link replaces the old.** Any import in flight is cancelled. Its result, success or failure, is discarded by the `!Task.isCancelled` guards.
3. **Global state.** `honorImportState = .fetching`. This one value is read by the Ways sheet's paste line and by whichever overview is open (§5 rows 6 to 8; own-walk spec F §7.2, §13.1).
4. **Feedback.**
   - With the Ways sheet *not* presented, the toast reads "reaching for the walk…" (U+2026).
   - With the sheet presented, there's no toast; the sheet's inline line carries the state (§8).
5. **Success.**
   - The toast is cleared, the state goes to `.idle`, and the task is released.
   - If a walk has started, or a Begin is parked (`pendingStartWay`), the Way is dropped silently.
   - Otherwise `openOverview(for:)` either parks the Way or presents it.
6. **Failure.**
   - Any error that isn't a `WayError` becomes `.unavailable`, and the state becomes `.failed(failure)`.
   - The toast shows the failure copy only if the Ways sheet isn't presented *at failure time*.

The importer is injected, so the tests can hold a fetch open across the race:

```swift
    var importShare: (String) async throws -> Way = { try await WayImporter().importShare(id: $0) }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:33@7c200bf

#### 4.3 `openOverview`: park behind the Ways sheet or the post-walk summary, else present

```swift
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
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:246-258@7c200bf

The two parks behave differently:

| What's up | What the link does | When the overview shows |
|---|---|---|
| Ways sheet (`honorWaysPresented`) | closes it (`honorWaysPresented = false`) | right after the sheet finishes closing (`promotePendingHonorWay` on its dismiss, own-walk spec F §7.1) |
| Post-walk summary (`completedSnapshot`) | leaves it open; `honorWaysPresented = false` is a no-op here | only when the walker closes the summary (`handleSummaryDismiss` → promote, `MainCoordinatorView.swift:185-189@7c200bf`) |

```swift
    func testOpenOverviewOverASummarySheetParksInsteadOfPresenting() throws {
        let coordinator = MainCoordinator()
        let way = try makeWay()
        coordinator.completedSnapshot = makeWalk()

        coordinator.openOverview(for: way)

        XCTAssertNil(coordinator.honorOverviewWay, "an overview presented over the summary sheet would never appear")
        XCTAssertEqual(coordinator.pendingHonorWay?.id, way.id)

        coordinator.completedSnapshot = nil
        coordinator.handleSummaryDismiss()
        XCTAssertEqual(coordinator.honorOverviewWay?.id, way.id, "the summary's dismiss promotes the park")
    }
```
> UnitTests/Honor/MainCoordinatorHonorTests.swift:110-123@7c200bf

Only these two sheets are tracked. The journal's summary (`HomeView`'s own `selectedWalk` sheet), the Goshuin sheet, Settings sheets and the seal share sheet are not known to the coordinator. A link opened under one of them takes the `else` branch (§5 row 14, defect 1).

`gather` runs only on the present branch, or at promotion. That's when media starts downloading for a shared Way (`MainCoordinatorView.swift:264-284@7c200bf`; the gathering itself is U28's).

#### 4.4 "Opens the overview, fetching": what the walker actually sees

R18 and AE4 say a link "opens the Honor overview, fetching". On iOS, a fresh link does not open an overview in a fetching state:

1. **During the fetch** there's no overview yet, only the tab view (now on Path) with "reaching for the walk…" at the top.
2. **Once the manifest lands** the overview presents. `gather` then moves the state from `.idle` to `.gathering` or `.ready` on its main-actor hop.
3. **On failure** no overview appears. The failure copy replaces the toast for 5 s (§7).

The overview shows "reaching for the walk…" (and disables Begin) only when an overview is *already open* when a link arrives, because the state is global:

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

#### 4.5 The tap imports and accepts; the overview only gathers

The manifest is fetched, validated and **saved to the Ways store at the tap**, before any overview appears:

```swift
        let way = try Self.way(from: manifest, shareId: id, now: now())
        try store.save(way)
        return way
```
> Pilgrim/Models/Honor/WayImporter.swift:83-85@7c200bf

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
```
> Pilgrim/Models/Honor/WayStore.swift:93-102@7c200bf

So a successful fetch is an acceptance:
- the Way gets `way.json` and its first `accepted.json`;
- it's listed under "Shared with you" from then on (`HonorWaysSheet.swift:112-116@7c200bf`);
- this is true even if the overview never shows, or shows and is closed without Begin.

Media waits for the overview (§4.3). The plan's U28 line "import starts only on an explicit overview open" therefore splits on iOS: the *manifest import* starts on the tap, and the *media gather* starts on the overview open. See Resolutions.

A save that throws (a `CocoaError`, for example disk full) isn't a `WayError`, so `openWay` maps it to `.unavailable`: "couldn't reach the walk" (§11).

### 5. The routing table

Every row is a valid link arriving in that state. An invalid link does nothing in every row (§2.3).

"Toast" means the tab view's top overlay (§7), which every sheet covers. "Inline" means the import line inside the Ways sheet or the overview.

| # | State when the link arrives | Tab | What shows during the fetch | On success | On failure | Evidence |
|---|---|---|---|---|---|---|
| 1 | Cold start, set up, no walk | stays Path (default) | toast "reaching for the walk…" over Path, under any recovery banner | overview presents, then gathers | failure toast, 5 s | `MainTabView.swift:9,98-106`; §4.2 |
| 2 | Cold start, not set up | (setup shows) | nothing until setup ends; then as row 1 | as row 1, after the breath | as row 1 | §3 |
| 3 | Warm, app in the background mid-setup | (setup shows) | nothing; the id is stashed, last link wins | as row 2 | as row 2 | `PilgrimApp.swift:60-64` |
| 4 | Warm, tab view, nothing presented, no walk | switches to Path | toast "reaching for the walk…" | overview presents directly (`openOverview` else branch), then gathers | failure toast, 5 s | `MainTabView.swift:86-97`; §4.2–§4.3 |
| 5 | Walk screen up: any mode, the pre-walk screen before Start included; recording; sitting; the save in flight; the "Save Failed" alert | not switched | toast "finish this walk first", on the walk screen's own overlay | — (no fetch) | — | §6 |
| 6 | Ways sheet up (or its nested picker or pilgrimage catalog on top) | switches to Path, beneath | no toast; inline "reaching for the walk…" under the paste field, Open disabled | parks, closes the Ways sheet (and the nested sheet with it), overview after the dismiss | no toast; inline failure line in rust, field still editable | `openWay` `honorWaysPresented` branches; `openOverview` park branch; §8 |
| 7 | An overview up for a different Way (own walk or another share) | switches to Path, beneath | the open overview's inline "reaching for the walk…", Begin disabled; the toast too, but under the sheet | the sheet's item swaps to the new Way (old dismissed, new presented); the import state is not reset by the outgoing dismiss; new gather | the open overview shows the failure line in rust and Begin re-enables; the failure toast is under the sheet | `handleOverviewDismiss` (`MainCoordinatorView.swift:325-338`); own-walk spec F §7.2–§7.3, §13.1, F defect 7 |
| 8 | The overview up for the *same* share | switches to Path, beneath | as row 7 | same `id`, so no re-present (`extension Way: Identifiable {}`, `Way.swift:222`); the Way value is replaced; `gather` → `download` is a no-op while that Way is already downloading | as row 7 | `WayMediaDownloader.swift:120-124` |
| 9 | A moment preview over the overview | switches to Path, beneath | as row 7, hidden under the preview | as row 7 or 8; a swap takes the preview down with the outgoing overview | as row 7 | own-walk spec F §14.1 |
| 10 | Post-walk summary up | switches to Path, beneath | toast under the summary sheet | parked; the summary stays open; overview only once the walker closes the summary | failure toast under the summary sheet; nothing visible | `openOverview` park branch; test at `MainCoordinatorHonorTests.swift:110-123` |
| 11 | Between Begin and the walk screen (the overview closing, `pendingStartWay` set) | switches to Path, beneath | toast "reaching for the walk…" | if it lands before the cover: dropped silently; otherwise the walk start cancels it, and the toast rides onto the walk screen until its 5 s end | if before the cover: failure toast (it carries onto the cover); otherwise cancelled silently | `openWay` success guard; `startWalk` `importTask?.cancel()` (§6.2); test at `MainCoordinatorHonorTests.swift:137-154`; defect 2 |
| 12 | Another link's fetch already in flight | as its own row | the toast restarts its 5 s | the newer link wins; the older one's result is discarded | the older one's failure is discarded | `importTask?.cancel()` + `!Task.isCancelled` |
| 13 | The seal reveal up (after Finish, before the summary) | switches to Path, beneath the overlay | toast, drawn *above* the seal overlay (attached later in the modifier chain: `MainTabView.swift:120` vs `:146`) | overview presents over the seal reveal (a sheet over an overlay) | failure toast | what follows a Begin from that overview, with the seal reveal still pending, is not traced |
| 14 | An untracked sheet up: the journal summary (`HomeView`'s `selectedWalk`), Goshuin, any Settings sheet, the Recordings summary, the seal share sheet | switches to Path, beneath | toast under the sheet | `openOverview` takes the present branch while another sheet is up. By the code's own comment that "drops the overview on the floor and dead-ends the link"; needs an iPhone check. `gather` still runs, so media starts downloading | failure toast under the sheet | `MainCoordinatorView.swift:247-251`; `HomeView.swift:59,62`; `MainTabView.swift:141-145`; defect 1 |
| 15 | *Android only:* the UI process cold-starts while `:tracker` is walking | — | — | — | — | no iOS state: iOS never resumes a killed walk (own-walk spec, correction 4), so the nearest iOS row is 5 |

Pinned by tests at the pin:
- row 5: `testOpenWayWhileWalkingSetsTheToast`;
- row 10: `testOpenOverviewOverASummarySheetParksInsteadOfPresenting`;
- row 11's drop: `testImportResolvingAfterBeginPresentsNoOverview`;
- a fetch resolving during a walk (rows 5 and 11): `testImportResolvingDuringAWalkPresentsNoOverview`.

```swift
    @MainActor
    func testImportResolvingDuringAWalkPresentsNoOverview() async throws {
        let coordinator = MainCoordinator()
        addTeardownBlock { coordinator.cancelWalk() }
        let way = try makeWay()
        let held = heldImport(way)
        coordinator.importShare = held.fetch

        coordinator.openWay(shareId: "Qoi4YmPHLN")
        coordinator.startWalk()
        _ = try XCTUnwrap(coordinator.activeWalkViewModel)
        held.release()
        await coordinator.waitForImport()

        XCTAssertNil(coordinator.honorOverviewWay, "nothing interrupts a walk already under way")
        XCTAssertNil(coordinator.pendingHonorWay)
    }
```
> UnitTests/Honor/MainCoordinatorHonorTests.swift:156-172@7c200bf

```swift
    @MainActor
    func testImportResolvingAfterBeginPresentsNoOverview() async throws {
        let coordinator = MainCoordinator()
        addTeardownBlock { coordinator.cancelWalk() }
        let way = try makeWay()
        let held = heldImport(way)
        coordinator.importShare = held.fetch

        coordinator.openWay(shareId: "Qoi4YmPHLN")
        // Begin lands while the fetch is still in the air.
        coordinator.startHonor(way: way)
        held.release()
        await coordinator.waitForImport()

        XCTAssertNil(coordinator.honorOverviewWay, "a Begin already in flight wins the sheet")
        XCTAssertNil(coordinator.pendingHonorWay)
        XCTAssertEqual(coordinator.honorImportState, .idle)
    }
```
> UnitTests/Honor/MainCoordinatorHonorTests.swift:137-154@7c200bf

The untracked sheets in row 14:

```swift
            .sheet(item: $selectedWalk, onDismiss: onSummaryDismiss) { walk in
                WalkSummaryView(walk: walk, onWalkAgain: onWalkAgain)
            }
            .sheet(isPresented: $showGoshuin) {
```
> Pilgrim/Scenes/Home/HomeView.swift:59-62@7c200bf

```swift
        .sheet(item: $sealShareURL, onDismiss: {
            coordinator.handleSealShareDismiss()
        }) { url in
            ShareSheet(items: [url])
        }
```
> Pilgrim/Scenes/Root/MainTabView.swift:141-145@7c200bf

Android counterparts at HEAD:
- **Widget links are dropped silently during a walk.** The widget's deep link already drops a passive link while the route is `ACTIVE_WALK` or `MEDITATION` (`PilgrimNavHost.kt:658-665@636cf5ce`). That's the shape of row 5, but with no toast.
- **No sheet race.** Android's overview will be a route (U21), so presenting over a summary or any screen has no AF60 race to work around. What to keep from rows 6 and 10 is iOS's observable order: the Ways sheet closes itself, while the post-walk summary waits for the walker.

### 6. "finish this walk first"

#### 6.1 The copy, and what the refusal touches

```swift
        guard activeWalkViewModel == nil else { showLinkToast("finish this walk first"); return }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:213@7c200bf

```swift
    func testOpenWayWhileWalkingSetsTheToast() throws {
        let coordinator = MainCoordinator()
        addTeardownBlock { coordinator.cancelWalk() }
        coordinator.startWalk()
        _ = try XCTUnwrap(coordinator.activeWalkViewModel, "the guard under test only means something with a walk running")

        coordinator.openWay(shareId: "Qoi4YmPHLN")

        XCTAssertEqual(coordinator.pendingLinkToast, "finish this walk first")
        XCTAssertEqual(coordinator.honorImportState, .idle, "no import may start underneath a walk")
    }
```
> UnitTests/Honor/MainCoordinatorHonorTests.swift:98-108@7c200bf

- **The copy** is exactly `finish this walk first`: lowercase, no trailing punctuation.
- **What happens.** The link is dropped: no fetch, no import state, no park, no tab switch (§4.1), and nothing is remembered for after the walk.
- **Lifetime.** The toast lasts 5 s (§7.1). A second link during the walk replaces it and restarts the 5 s.

#### 6.2 What counts as "a walk": the walk screen, from the moment it opens

The guard is `activeWalkViewModel != nil`, which is set when the walk screen is *presented*, before the walker taps Start:

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
        activeWalkViewModel = vm
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:137-138@7c200bf

The walk screen opens in its pre-walk state, and recording starts only at its Start button (own-walk spec F §12.3, `WalkStatsSheet.swift:522-525@7c200bf`). The Path tab's non-Honor modes take this route too (`MainTabView.swift:22-28@7c200bf`). So a link is refused on the pre-walk screen of a Wander, Seek or Honor walk before anything is recorded.

It is cleared in exactly three places. The first is the save succeeding:

```swift
                if success {
                    snapshot.uuid = walk?.uuid
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:106-107@7c200bf

```swift
                    self.pendingSnapshot = snapshot
                    self.activeWalkViewModel = nil
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:122-123@7c200bf

The second is the walk being cancelled:

```swift
    func cancelWalk() {
        activeWalkViewModel?.cancel()
        activeWalkViewModel = nil
        pendingSnapshot = nil
        Task { @MainActor in TranscriptionService.shared.autoTranscriptionSkippedReason = nil }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:140-145@7c200bf

The third is the "Save Failed" alert's Dismiss:

```swift
        .alert("Save Failed", isPresented: $coordinator.showSaveError) {
            Button("Dismiss") {
                coordinator.activeWalkViewModel = nil
            }
        } message: {
            Text("Your walk could not be saved. Please try again.")
        }
```
> Pilgrim/Scenes/Root/MainTabView.swift:107-113@7c200bf

So the refusal window runs from the walk screen opening until one of these three. It covers the save in flight and a "Save Failed" alert that hasn't been dismissed.

As soon as the save succeeds, links route again: the seal reveal is row 13 and the post-walk summary is row 10 of §5.

#### 6.3 Where the refusal shows

The walk screen is a full-screen cover over the whole tab view, so the cover repeats the toast:

```swift
        .fullScreenCover(item: $coordinator.activeWalkViewModel, onDismiss: {
            coordinator.handleActiveWalkDismiss()
        }) { vm in
            ActiveWalkView(viewModel: vm, onCancel: { coordinator.cancelWalk() })
                .constellationDecorated(nebulae: false)
                // A cover sits above the tab view's own overlay, so the
                // "finish this walk first" answer to a link tapped mid-walk
                // has to be repeated here to be seen at all.
                .overlay(alignment: .top) {
                    if let toast = coordinator.pendingLinkToast {
                        HonorLinkToast(text: toast)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                }
                .animation(.easeInOut, value: coordinator.pendingLinkToast)
        }
```
> Pilgrim/Scenes/Root/MainTabView.swift:42-57@7c200bf

The overlay sits on `ActiveWalkView` itself. The meditation screen is a second full-screen cover presented *from* `ActiveWalkView`:

```swift
        .fullScreenCover(isPresented: $showMeditation, onDismiss: {
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:204@7c200bf

So while the walker sits, the refusal toast is drawn under the meditation screen and isn't seen. The same goes for any of the walk screen's sheets tall enough to reach its top: options, intention, waypoint, whisper, stone, cairn, turning card or stage day (`ActiveWalkView.swift:229-388@7c200bf`; their detents weren't checked). The link is still dropped. This is candidate defect 3.

The cover's overlay mirrors whatever `pendingLinkToast` holds. Row 11's "reaching for the walk…", or a failure toast set just before a walk starts, therefore rides onto the walk screen for the rest of its 5 s.

Android counterparts at HEAD:
- The Path screen sends a walker who is mid-walk straight back to the walk screen (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/path/WalkStartScreen.kt:167-183@636cf5ce`).
- The walk screen is the `ACTIVE_WALK` route (`PilgrimNavHost.kt:84@636cf5ce`), and before Start it shows the walk screen's pre-walk state, with no walk row in Room yet.
- Meditation is its own route, `MEDITATION` (`PilgrimNavHost.kt:89@636cf5ce`).

So "a walk is active" by a Room probe alone misses iOS's pre-Start window. See Resolutions.

### 7. The link toast

#### 7.1 State and lifetime

```swift
    /// The only feedback a link has when no Honor sheet is open to carry it.
    @Published var pendingLinkToast: String?
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:21-22@7c200bf

```swift
    /// One toast at a time, always with a cancellable expiry: a link that
    /// never resolves must not leave "reaching for the walk…" on screen.
    private func showLinkToast(_ text: String?) {
        linkToastWork?.cancel()
        pendingLinkToast = text
        guard text != nil else { return }
        let work = DispatchWorkItem { [weak self] in self?.pendingLinkToast = nil }
        linkToastWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:340-349@7c200bf

- **One toast at a time.** Each new text cancels the pending expiry and starts a fresh **5 s** one. `nil` clears the toast at once.
- **Every text it can carry:**
  - `finish this walk first` (§6);
  - `reaching for the walk…` (§4.2);
  - the failure line for the error, from the import copy below.
- **Opening the Ways sheet clears it.** `chooseWay()` clears any toast and resets the import state to `.idle`. It does not cancel an import in flight:

```swift
    /// Resets the import state and any toast left over from a previous link
    /// so a stale failure line never greets the next opening of the sheet.
    func chooseWay() {
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:193-195@7c200bf

```swift
        honorImportState = .idle
        showLinkToast(nil)
        honorWaysPresented = true
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:204-207@7c200bf

The failure copy:

```swift
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
> Pilgrim/Models/Honor/HonorImportReducer.swift:24-37@7c200bf

Only three failures reach a link toast:
- `.notFound`, on a bad id shape or a 404;
- `.returnedToTrail`, when the manifest's `expires` has passed;
- `.unavailable`, for anything else, including a save error (§4.5).

`.diskFull` comes only from the media downloader (U28).

The capitals are uneven: "This walk has returned to the trail" starts with a capital and has no period. "couldn't find that walk. Check the link, …" starts lowercase, with an inner capital. They are verbatim.

These toast strings reach `Text` as a `String` variable (`Text(text)`, `MainCoordinatorView.swift:411`), so SwiftUI doesn't look them up in `Localizable.strings`.

The 5 s toast is shorter than the fetch can run. The importer's session allows 15 s per request and 30 s per resource:

```swift
    /// The overview's toast promises a quick answer ("reaching for the
    /// walk…") and expires at 5 s — `.shared`'s default multi-minute
    /// timeouts would leave a hung request outliving both the copy and the
    /// toast, so a share fetch gets its own short-lived, tightly-timed
    /// session instead.
    private static let defaultSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()
```
> Pilgrim/Models/Honor/WayImporter.swift:31-41@7c200bf

So on a slow network the toast disappears at 5 s and nothing is shown until the overview arrives or a failure toast appears, up to 30 s later.

#### 7.2 Look and layering

```swift
/// What a link says when no Honor sheet is open to say it inline.
struct HonorLinkToast: View {

    let text: String

    var body: some View {
        Text(text)
            .font(Constants.Typography.caption)
            .foregroundColor(Color(.ink))
            .multilineTextAlignment(.center)
            .padding(.horizontal, Constants.UI.Padding.normal)
            .padding(.vertical, Constants.UI.Padding.small)
            .background(Color(.parchmentSecondary).opacity(0.95))
            .cornerRadius(8)
            .padding(.horizontal, Constants.UI.Padding.normal)
            .padding(.top, Constants.UI.Padding.small)
            .allowsHitTesting(false)
    }
}
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:405-423@7c200bf

It resolves through the tokens in own-walk spec F §1:

| Property | Value |
|---|---|
| Font | caption (Lato-Regular 12) |
| Text colour | ink |
| Alignment | centred, multi-line |
| Inner padding | 16 horizontal, 8 vertical |
| Background | `parchmentSecondary` at 0.95 |
| Corner radius | 8, a literal (`CornerRadius.small` is also 8, but it isn't referenced) |
| Outer padding | 16 horizontal, 8 top |
| Touch | not hit-testable: taps pass through, and it has no tap or swipe to dismiss |

On the tab view, the toast shares the top overlay with the recovery banner: the banner first, the toast under it, 4 pt apart.

```swift
        .overlay(alignment: .top) {
            VStack(spacing: Constants.UI.Padding.xs) {
                if let date = coordinator.recoveredWalkDate {
                    RecoveryBanner(date: date)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
                if let toast = coordinator.pendingLinkToast {
                    HonorLinkToast(text: toast)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
        }
        .animation(.easeInOut, value: coordinator.recoveredWalkDate != nil)
        .animation(.easeInOut, value: coordinator.pendingLinkToast)
```
> Pilgrim/Scenes/Root/MainTabView.swift:146-159@7c200bf

- **Motion.** It slides in from the top edge and fades, using `.easeInOut` with no duration given (SwiftUI's default). Because the animation is keyed on the text, a change of text (for example from "reaching for the walk…" to a failure line) animates too.
- **Layering.** It's an overlay on the tab view, so it shows above the tab content and the seal reveal, but under every sheet and cover. During a walk only the cover's copy (§6.3) can be seen.
- **Accessibility.** There's no announcement: no `UIAccessibility.post` in `MainCoordinatorView.swift` or `MainTabView.swift`. A VoiceOver user hears the toast only by moving focus onto it, within its 5 s.

### 8. Paste: the "From a shared walk" section

#### 8.1 Where it lives

Paste lives in the fourth and last section of the Ways sheet ("Choose a way"), which the Path tab's Honor button opens (own-walk spec F §2, §4.2). It is the only paste surface: Settings → Ways, the overview and the journal have none.

```swift
    @State private var pasted = ""
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:12@7c200bf

```swift
                Section {
                    TextField("paste a walk link", text: $pasted)
                        .font(Constants.Typography.body)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Open") { onPaste(pasted) }
                        .font(Constants.Typography.button)
                        .disabled(HonorLink.parse(text: pasted) == nil || importState == .fetching)
                    // The field above stays editable in every state, so a
                    // mistyped link is corrected where it was typed.
                    if let line = HonorImportCopy.line(for: importState) {
                        Text(line)
                            .font(Constants.Typography.caption)
                            .foregroundColor(isFailure ? .rust : .fog)
                    }
                } header: {
                    Text("From a shared walk").font(Constants.Typography.caption)
                } footer: {
                    Text("A walk someone shared with you, from walk.pilgrimapp.org.")
                        .font(Constants.Typography.caption)
                }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:57-77@7c200bf

```swift
    private var isFailure: Bool {
        if case .failed = importState { return true }
        return false
    }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:120-123@7c200bf

```swift
                onPaste: { text in
                    if let id = HonorLink.parse(text: text) { coordinator.openWay(shareId: id) }
                }
```
> Pilgrim/Scenes/Root/MainTabView.swift:69-71@7c200bf

#### 8.2 The clipboard is never read by the app

The field is a plain `TextField`. The walker pastes with the system edit menu, or types. There's no `PasteButton`, no `UIPasteboard` read, no paste on appear, and no paste command: across `Pilgrim/` at the pin, `UIPasteboard` appears only as a *write* (`.string = …`), in six places (prompt, recordings, walk share preview, voice row, light reading card, sharing buttons). So iOS never shows the "Pilgrim pasted from …" banner for Honor.

#### 8.3 What it accepts, and the controls' states

**What it accepts.** Whatever `HonorLink.parse(text:)` accepts (§1):
- either host, with any scheme or none;
- a bare 10-character id;
- surrounding whitespace and newlines, which are trimmed.

The footer names only `walk.pilgrimapp.org`, but an `honor.` link pastes just as well.

**The field.**
- Placeholder `paste a walk link`, in the body font.
- No auto-capitalization and no autocorrection, so an id's case survives typing.
- No `.onSubmit`, so Return doesn't open; only the button does.
- Default keyboard and content type.

**The `Open` button.**
- Button font, in the sheet's stone tint (own-walk spec F §4.3).
- Enabled only while the current text parses *and* the import state isn't `.fetching`. The parse runs again on every keystroke, through the body.
- Not disabled by `.failed`: tapping Open after a failure fetches again.

**The line under the button** comes from `HonorImportCopy`, in the caption font:

| Import state | Line | Colour |
|---|---|---|
| `.idle` (the sheet's opening state, from `chooseWay`) | none | — |
| `.fetching` | `reaching for the walk…` | fog |
| `.failed(.notFound)` | `couldn't find that walk. Check the link, or it may have returned to the trail.` | rust |
| `.failed(.returnedToTrail)` | `This walk has returned to the trail` | rust |
| `.failed(.unavailable)` | `couldn't reach the walk` | rust |

`.gathering`, `.ready` and `.mediaMissing` can't be showing while the sheet is open: the sheet opens at `.idle`, and a success parks the Way and closes the sheet.

**A successful open.**
- Both sheets close: the Ways sheet, and any nested sheet with it.
- The overview presents after the dismiss (§4.3).
- The pasted text goes with the sheet's `@State`, so the next opening starts empty.

**A failed open.** The text stays in the field, still editable, with the rust line under it, and no toast (§4.2).

**A link tapped while the sheet is open** behaves exactly like a paste: the state goes into the same inline line, with no toast (§5 row 6). The `pasted` text is unchanged by it.

**Accessibility.** No custom labels. The placeholder names the field, and the button reads "Open".

Android counterparts: the Ways sheet and its paste field don't exist at HEAD (`P/ui/honor/HonorWaysSheet.kt` is a U21 create, and the paste field is U28's). The plan's U27 line "the clipboard is never read programmatically" matches iOS.

### 9. What maps to App Links, and what has no Android equivalent

#### 9.1 The mapping

| iOS fact (pin) | Android counterpart | Evidence |
|---|---|---|
| `applinks:honor.pilgrimapp.org` is the only associated domain | a verified (`autoVerify`) VIEW filter for `honor.pilgrimapp.org` only; the walk host is never claimed, so a tapped walk link opens the browser (AE4) | `Pilgrim.entitlements:7-10`; R18; plan U9 ("The honor host stays the only auto-verify host") |
| The association claims every path (`"/":"/*"`) | the filter takes the host's paths broadly; the parser decides, and an unparseable path opens the app and does nothing | worker `honor.ts:12-13@2a4f5d0`; `PilgrimApp.swift:61` |
| Two OS entry points, `onContinueUserActivity` and `onOpenURL`, into one `route` | `onCreate` (cold) and `onNewIntent` (warm), into one router; `MainActivity` is already `singleTop` | `PilgrimApp.swift:47-50`; `MainActivity.kt:76,180-184@636cf5ce` |
| `route` parses with the same `HonorLink` the paste uses, so a URL for either host parses | one shared parser for intent data and paste. An explicit VIEW intent naming the walk host (`MainActivity` is exported) would parse as on iOS's `onOpenURL`; only the manifest filter keeps taps to the honor host | `HonorLink.swift:7,10-15` |
| Stash plus broadcast, drained once (`pendingShareId = nil` on both paths) | the link is consumed once. Activity recreation (theme, locale or dark-mode change) must not replay it, which is the plan's "clear the intent data once consumed" | `MainTabView.swift:91,102`; `MainActivity.kt:139-151@636cf5ce` (the widget precedent) |
| Held in a process-lifetime static: it survives backgrounding and is lost with the process | held across Activity recreation, gone with the process (R18). Whether a task restored after process death replays the launch intent is an Android question; see Open questions | `PilgrimApp.swift:35` |
| No release flag | Android gates the filter (debug manifest until the flip) and the router on the release flag | plan Global Constraints; `ReleaseBuildContentsTest.kt:64-75@636cf5ce` |

#### 9.2 iOS behavior with no Android equivalent

- **The Smart App Banner's `app-argument`.** On the honor page, Safari's banner hands the app the honor URL through `onOpenURL` once the app is installed (§10.2). Android browsers have no such banner; the install referrer (R19) is Android's own route to the same outcome.
- **Handoff.** `onContinueUserActivity(NSUserActivityTypeBrowsingWeb)` also receives a Safari page handed off from another Apple device. If that page is an honor URL, it routes like a tap. Android has nothing comparable, and nothing needs porting.
- **The user's per-domain choice.** Long-press → "Open in Safari" makes iOS stop opening the app for that domain. Android's equivalent is the system's "Open by default" setting. Both belong to the OS; Pilgrim has no code for either.
- **The launch gate.** iOS holds the link through `.loading` and `.migration` as well as setup (§3.1). Android has no loading or migration screen in front of the nav graph, so there's nothing extra to hold through.
- **Two delivery callbacks for one tap.** Which SwiftUI handler fires for a universal link, when both are registered, is SwiftUI's choice. If both fired, `route` would run twice. The second `openWay` would cancel the first and refetch, which is harmless, and the toast would restart (§4.2). Android delivers one intent per tap.

### 10. The install referrer (R19): iOS has nothing to match

#### 10.1 Nothing in the iOS app

At the pin, iOS has no deferred deep link of any kind:
- no install attribution, App Clip, or SKAdNetwork code. The project's four product types are one application, one app extension (the widget), one unit-test bundle and one UI-test bundle, with no on-demand-install (App Clip) target (`Pilgrim.xcodeproj/project.pbxproj@7c200bf`);
- no first-launch clipboard sniff (§8.2);
- nothing in `AppDelegate` or setup that reads a link (§2.2, §3).

A fresh install launched from the home screen opens nothing. The handoff in R19 is Android-only, a deliberate addition recorded under R6.

#### 10.2 What a fresh iOS install from the honor page does

Context from the worker, which serves the page an iPhone without Pilgrim lands on:

```ts
<meta name="apple-itunes-app" content="app-id=6760921056, app-argument=${here}">
```
> pilgrim-worker/src/generators/honor-page.ts:34@2a4f5d0

```ts
  <div class="for-ios">
    <a class="open" href="${env.APP_STORE_URL}">Get Pilgrim on the App Store</a>
    <p class="then">Once it's installed, tap <strong>Open</strong> in the banner above, or go back to the walk and tap <em>walk this</em> again.</p>
  </div>
```
> pilgrim-worker/src/generators/honor-page.ts:61-64@2a4f5d0

`here` is `${HONOR_ORIGIN}/${id}` (`honor-page.ts:24@2a4f5d0`). After installing from the App Store, the iOS walker gets the Way only through a second, explicit action of their own:

1. **The banner's Open** on the honor page, still in Safari. It launches Pilgrim with the honor URL through `onOpenURL` (§2.2).
2. **Or a tap on "walk this" again** on the walk page. That pill links to the honor host (`pilgrim-worker/src/generators/html-template.ts:2016@2a4f5d0`), so it opens as a universal link.
3. **Either way**, `route` stashes the id. First launch is unset-up, so the id is held through setup (§3) and lost if the process dies first.
4. **Once the breath ends**, the tab view mounts and the link routes as row 1 of §5: the Path tab, "reaching for the walk…", then the overview on success, or the failure toast.

Opening Pilgrim any other way after the install (the App Store's own Open button, the home screen) opens nothing.

The outcome U27 mirrors:
- The Way's overview appears once, after setup completes, by the same route a tap takes: the Path tab, the "reaching for the walk…" toast, then the overview. There's no automatic Begin.
- A failure shows the failure toast and nothing else.
- Nothing reopens it later.

Android's additions over iOS are in R19:
- no second tap;
- the hold survives a process death;
- an updater sees nothing.

#### 10.3 The worker's referrer format (Android-only context)

```ts
export function playStoreURL(base: string, id: string): string {
  return `${base}${base.includes("?") ? "&" : "?"}referrer=${encodeURIComponent(`honor=${id}`)}`;
}
```
> pilgrim-worker/src/honor-constants.ts:7-9@2a4f5d0

The Play link carries `referrer=honor%3D<id>`, and the page only builds it for an id matching `^[a-zA-Z0-9_-]{10}$` (`honor-page.ts:5,23@2a4f5d0`). The plan already says the referrer string is decoded once and then parsed by the same whole-id parser. That id check is the iOS `isID` shape (§1.1).

### 11. Edge cases

"Test" means a case pinned by `HonorLinkTests` (§1.2). "Derived" means it follows from the quoted code plus Foundation's documented URL behavior, with no test at the pin.

#### 11.1 Shapes

| Input | Tapped (honor host) | Pasted | Basis |
|---|---|---|---|
| `https://honor.pilgrimapp.org/Qoi4YmPHLN` | opens | opens | test |
| `https://walk.pilgrimapp.org/Qoi4YmPHLN` | never reaches the app; Safari opens the walk page | opens | entitlement; test |
| `walk.pilgrimapp.org/Qoi4YmPHLN`, `honor.pilgrimapp.org/Qoi4YmPHLN` (no scheme) | — | opens (`https://` prepended) | test (walk host); derived (honor host) |
| `Qoi4YmPHLN`, `  Qoi4YmPHLN\n` (a bare id) | — | opens; the text form trims first | test |
| `https://HONOR.pilgrimapp.org/Qoi4YmPHLN` (uppercase host) | opens | opens | test |
| `HTTPS://…`, `http://…`, `ftp://walk.pilgrimapp.org/Qoi4YmPHLN` | the OS delivers only `https` honor URLs | opens: the scheme is never checked | derived (`HonorLink.swift:10-15`) |
| `https://honor.pilgrimapp.org/qoi4ymphln` (the id's case changed) | parses as a *different* id, then 404 → "couldn't find that walk. …" | same | derived: the id is never folded |
| `…/Qoi4YmPHLN/` (one trailing slash) | opens | opens | test |
| `…//Qoi4YmPHLN` or `…/Qoi4YmPHLN//` | opens, if Foundation collapses the empty components | same | derived; not pinned |
| `…/Qoi4YmPHLN?utm=x#m3` (query, fragment) | opens; both ignored | opens | test |
| `https://honor.pilgrimapp.org/?id=Qoi4YmPHLN` (id in the query) | the app opens and nothing happens | Open stays disabled | derived: the path has no component |
| `…/Qoi4YmPHLN%0A` (encoded newline) | the app opens and nothing happens | Open stays disabled | test |
| `…/%51oi4YmPHLN` (escaped id characters) | opens as `Qoi4YmPHLN` | same | derived: `pathComponents` decodes |
| `…/Qoi4%2FYmPHL` (escaped slash) | the app opens and nothing happens | Open stays disabled | derived: the decoded `/` fails the pattern |
| `Qoi4YmPHL` (9), `Qoi4YmPHLN1` (11), `short`, an empty string | the app opens and nothing happens | Open stays disabled | test |
| `…/Qoi4YmPHLN/audio/1.m4a` (extra segments) | the app opens and nothing happens | Open stays disabled | test |
| `https://honor.pilgrimapp.org/` or `/abc` | the app opens (the association claims `/*`) and nothing happens | Open stays disabled | test (walk host's `/`); worker `honor.ts:12-13@2a4f5d0` |
| `https://example.com/…`, `walk.pilgrimapp.org.evil.com/…`, `walk.pilgrimapp.org./…` | — | Open stays disabled: set lookup | test (`example.com`); derived |
| `https://u@walk.pilgrimapp.org:8443/Qoi4YmPHLN` (userinfo, port) | — | opens; the fetch still goes to `https://walk.pilgrimapp.org/<id>/tour.json` | derived (`WayImporter.swift:10,53`) |
| `see https://walk.pilgrimapp.org/Qoi4YmPHLN`, `https://…/Qoi4YmPHLN thanks` (extra words) | — | Open stays disabled: either `URL(string:)` fails, or, where it percent-encodes the space, the host or the path component no longer matches | derived; not pinned |

The Android traps on the same table:
- Kotlin `String.trim()` and Foundation's `.whitespacesAndNewlines` aren't the same set. NBSP and U+2028 are in both. U+0085 (NEL) is in Foundation's set and not Kotlin's; U+001C–U+001F are in Kotlin's and not Foundation's. Both are from the platform documentation, not tested here.
- `Uri.parse` never fails, so the "extra words" rows must be rejected by the host and segment checks, not by a parse failure.
- `$` in `java.util.regex` matches before a final newline (§1.3).

#### 11.2 Outcomes once parsed

| Case | iOS behavior | Evidence |
|---|---|---|
| The share is live | imported, saved and accepted at the tap, then the overview (§4.5) | `WayImporter.swift:83-85`; `WayStore.swift:93-102` |
| The share expired, and the worker still serves its `tour.json` | `"This walk has returned to the trail"` | `WayImporter.swift:126-128` (quoted below) |
| The share expired, and the worker has removed it | 404 → `"couldn't find that walk. Check the link, or it may have returned to the trail."` | `WayImporter.swift:60`; the worker serves `tour.json` straight from storage with no expiry check, and a missing object is a 404 (`pilgrim-worker/src/index.ts:37,191-206@2a4f5d0`) |
| A share id that never existed | 404 → the same not-found copy | `WayImporter.swift:60` |
| Any other status, no network, a timeout, an oversized or invalid manifest | `"couldn't reach the walk"` | `WayImporter.swift:59-62,71-75`; validation bounds are S1's |
| The manifest can't be saved (for example disk full) | `"couldn't reach the walk"`, not the disk-full line | §4.5; candidate defect 6 |
| A link to a share already accepted and still live | fetched again: `way.json` is rewritten from the fresh manifest, and the first `accepted.json` is kept, so its place in "Shared with you" (newest acceptance first) doesn't move | `WayStore.swift:97-101,117-121` (quoted below) |
| A link to a share already accepted (or walked) that has since expired | fetched again and fails with one of the expired-share lines. The stored copy is not opened, though it stays listed under "Shared with you" until the sweep removes it (U28) | `openWay` has no store lookup (§4.2); Open question 4 |
| The same link twice, the first still fetching | the first fetch is cancelled, the second runs, and the toast restarts | §5 row 12 |
| The same link with its overview open | Begin is disabled during the refetch, and the sheet stays with the new value | §5 row 8 |
| The same link twice during onboarding | one id, opened once | §3.2 |
| The same link twice during a walk | two refusals, each restarting the 5 s | §6.1 |

```swift
    static func way(from m: TourManifest, shareId: String, now: Date) throws -> Way {
        guard let expires = isoDate(m.expires), let departed = isoDate(m.start_date) else { throw WayError.unavailable }
        guard expires > now else { throw WayError.returnedToTrail }
```
> Pilgrim/Models/Honor/WayImporter.swift:126-128@7c200bf

```swift
    func list() -> [Way] {
        let ids = (try? fileManager.contentsOfDirectory(atPath: base.path)) ?? []
        return ids.filter(Self.isValidId).compactMap { load(id: $0) }
            .sorted { (acceptedAt(id: $0.id) ?? .distantPast) > (acceptedAt(id: $1.id) ?? .distantPast) }
    }
```
> Pilgrim/Models/Honor/WayStore.swift:117-121@7c200bf

Which expired-share line a walker sees depends on whether the worker's nightly cleanup has run yet. "returned to the trail" comes only from the client-side `expires` check.

### Resolutions for the plan

1. **The parser ports as is** (§1). The plan's U27 parsing line holds. It needs four additions, each a ported test:
   - the scheme is never checked;
   - percent-escapes are decoded before the id check;
   - the query and fragment are ignored;
   - the id is case-sensitive, and only the host is folded.

   Port `HonorLinkTests` as `T/honor/HonorLinkTest.kt`:
   - the seven accepted forms, the seven rejections, both uppercase-host cases, and the `%0A` case;
   - the derived §11.1 rows that differ between platforms (whitespace set, `Uri.parse` leniency).

   Whole-input matching (`Regex.matches`), never `find` with `$`.
2. **The App Links filter.**
   - **Hosts.** `honor.pilgrimapp.org` only, and every path. The walk host is never claimed (AE4).
   - **A path that doesn't parse** opens the app and does nothing: no toast, no tab change, no state (§2.3).
   - **The release build** keeps the filter out until the flip (`ReleaseBuildContentsTest`). The router checks the flag as well.
3. **The hold** (§3). iOS holds the *last* valid id in memory from its first delivery until the tab view first appears. It's consumed once, and nothing is shown while it's held. iOS's trigger is the tab view mounting, which comes *after* the breath writes `isSetUp`. So:
   - **"Setup completes" means the first arrival at `PATH`.** On a first launch that's after `BREATH`; on a set-up launch, after `PERMISSIONS` auto-skips. It is not the `onboardingComplete` write, which Android makes before the breath (`PermissionsScreen.kt:271-272@636cf5ce`).
   - **Hold through every route in front of `PATH`:** `WELCOME`, `PERMISSIONS` and `BREATH`. The widget link's `PERMISSIONS`-only wait (`PilgrimNavHost.kt:629-633@636cf5ce`) is not the model.
   - **A set-up Android launch that stops on `PERMISSIONS`** (permissions revoked) also holds. iOS never re-enters setup once `isSetUp`, so it has no such case; this is Android's onboarding shape, not a divergence.
   - **Lifetime.** The hold survives Activity recreation and is gone with the process (R18). See Open question 3 on task restore.
4. **The walk-active refusal** (§6). Show `finish this walk first` and drop the link: no fetch, no tab change, nothing remembered.
   - **The window** is "the walk screen is up". It runs from the `ACTIVE_WALK` route opening in its pre-walk state (any mode, before Start), through recording, pause and meditation, until the finished walk's save completes or the walk is discarded.
   - **A Room probe alone isn't enough.** It misses the pre-Start screen, where iOS already refuses (`activeWalkViewModel` exists from the cover's presentation).
   - **The cold UI start** with `:tracker` walking (the plan's edge case) is Android-only and maps here.
   - **iOS's brief window between Begin and the walk cover** (§5 row 11) is an AF60 sheet artifact that Android's navigation doesn't have. The plan's "Begin in flight" is covered by the walk-screen rule.
5. **"Opens the overview, fetching" is reworded** (§4.4). For a fresh link:
   - switch to the Path tab if no walk is active;
   - show `reaching for the walk…`;
   - fetch;
   - then open the overview (it gathers), or show the failure copy.

   The overview shows the fetching line and a disabled Begin only when it is *already open* as the link arrives, because the import state is global. R18's and AE4's "opens the Honor overview, fetching", and U27's "otherwise open the overview, fetching", should read this way. The parity gate's AE4 row tests the toast-then-overview order.
6. **Parks** (§4.3).
   - The Ways sheet: the link closes it, then the overview presents.
   - The post-walk summary: it stays up, and the overview presents only once the walker closes it.

   The plan's "while a summary shows, park it" matches the post-walk summary. iOS defines nothing for the other summary hosts; see Open question 2.
7. **The manifest import runs when the link resolves, not when the overview opens** (§4.5). The tap, the paste and (on Android) the referrer all fetch, validate and save the Way at once, writing `way.json` and the first `accepted.json`, so it's listed under "Shared with you". Media gathers only when the overview presents or is promoted. Correct both:
   - U28's "import starts only on an explicit overview open";
   - the Risk table's "import only on an explicit overview open".

   They should say: the manifest is imported on an explicit link, paste or referrer, and media is gathered on the overview open. "A new link cancels the import in flight" holds.
8. **Walk starts cancel link imports** (§5 rows 5 and 11; §6.2).
   - Starting any walk cancels an in-flight link import, silently.
   - A manifest that resolves once the walk screen is up is dropped with no overview (`testImportResolvingDuringAWalkPresentsNoOverview`, `testImportResolvingAfterBeginPresentsNoOverview`). Port both as router tests.
9. **The toast** (§7).
   - **Copy.** One of: `finish this walk first`, `reaching for the walk…`, or the three failure lines verbatim.
   - **Lifetime.** One at a time, 5 s each, replaced or cleared by the next.
   - **Look.** Caption ink on `parchmentSecondary` at 0.95, radius 8, padding 16 × 8 inside, 16 sides and 8 top outside.
   - **Motion and touch.** It slides in from the top and fades; taps pass through it.
   - **Stacking.** Under the recovery banner, 4 pt apart.
   - **During a walk** it shows on the walk screen.
   - **Clearing.** Opening the Ways sheet clears it and resets the import state, but leaves the import running (candidate defect 5).
10. **Paste** (§8). Paste is the Ways sheet's last section:
    - placeholder `paste a walk link`;
    - `Open`, enabled only while the text parses and the state isn't fetching;
    - Return does nothing;
    - the inline line in fog for fetching and rust for failures;
    - the field stays editable after a failure.

    The clipboard is never read by the app. A link tapped while the sheet is open shows inline, exactly like a paste, with no toast.
11. **The install referrer** (§10). iOS has no counterpart. The outcome to mirror is a tapped link held through setup. Once `PATH` first appears: the Path tab, `reaching for the walk…`, then the overview on success, or the failure toast; never an automatic Begin, and only once. The decoded referrer id goes through the same whole-id check (`honor-page.ts:5,23@2a4f5d0` only ever emits that shape).
12. **Consume once** (§9.1). iOS clears the stash on both delivery paths. Android's "clear the intent data once consumed" (`setIntent`) is that rule; the widget precedent is at `MainActivity.kt:139-151@636cf5ce`.
13. **Out of S2's scope.** Stripping control and bidirectional characters from the sharer's text concerns manifest fields (S1/U28). A link carries only the id, which the pattern already limits to `[A-Za-z0-9_-]`.

### iOS defects found

Filed on 2026-10-01 as listed in "Matched as shipped, and filed upstream" at the top of this spec. Own-walk spec F defect 7 (a failed link's error left on an own-walk overview) is already in [pilgrim-ios #110](https://github.com/walktalkmeditate/pilgrim-ios/issues/110) and isn't repeated here.

1. **A link opened under an untracked sheet may never show its overview** (§5 row 14). `openOverview` parks only for the Ways sheet and the post-walk summary. Under the journal's summary, the Goshuin grid, a Settings sheet, the Recordings summary or the seal share sheet, it presents the overview from the tab view while another sheet is up. The code's own comment says that "drops the overview on the floor and dead-ends the link". Meanwhile:
   - the tab switches to Path beneath the sheet;
   - the toast is drawn under the sheet;
   - `gather` starts the media download for an overview nobody sees.

   *Needs an iPhone check before filing.*

```swift
        // A summary sheet is a presented sheet too: presenting the overview
        // over it drops the overview on the floor and dead-ends the link.
        // `handleSummaryDismiss` promotes the park when the summary closes,
        // exactly as the Ways sheet's dismiss does.
        if honorWaysPresented || completedSnapshot != nil {
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:247-251@7c200bf

   Impact: a walker reading an old walk's summary taps a friend's link and nothing appears.

2. **"reaching for the walk…" rides onto the walk screen, and the import is cancelled silently** (§5 row 11). When a walk starts (a Begin, or any Path start) while a link is fetching, `startWalk` cancels the import, but the toast stays. The walk screen's overlay mirrors it for the rest of its 5 s. If the fetch failed just before, the failure line rides on instead.

```swift
        importTask?.cancel()
        importTask = nil
        gatheringCancellable = nil
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:85-87@7c200bf

   Impact: low. The walk screen promises a walk that will never arrive.

3. **The refusal can't be seen while sitting, and no link toast is announced** (§6.3, §7.2). The walk screen's toast overlay sits under the meditation cover, and under any walk sheet tall enough to reach the top. No toast posts an accessibility announcement.

```swift
                // A cover sits above the tab view's own overlay, so the
                // "finish this walk first" answer to a link tapped mid-walk
                // has to be repeated here to be seen at all.
                .overlay(alignment: .top) {
```
> Pilgrim/Scenes/Root/MainTabView.swift:47-50@7c200bf

   Impact: a link tapped during a sitting is dropped without a word, and VoiceOver users miss every link toast unless they find it within 5 s.

4. **A failed link under the post-walk summary gives no feedback** (§5 row 10). The failure toast is drawn on the tab view, under the summary sheet, and expires in 5 s. A success is parked and appears when the summary closes, but a failure leaves nothing.

   Impact: low. The walker closes the summary expecting the walk, and nothing opens.

5. **Opening the Ways sheet mid-fetch resets the state but not the import** (§7.1). `chooseWay()` sets `.idle` and clears the toast, which also re-enables Open, but the import keeps running. When it resolves:
   - a failure lands inline under a paste field the walker never used;
   - a success closes the sheet they just opened and jumps to the overview.

```swift
        honorImportState = .idle
        showLinkToast(nil)
        honorWaysPresented = true
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:204-206@7c200bf

   Impact: low; confusing.

6. **A manifest that can't be saved reads "couldn't reach the walk"** (§4.5). `importShare` saves before returning. A save error (for example disk full) isn't a `WayError`, so `openWay` maps it to `.unavailable` and never uses the disk-full line that `HonorImportCopy` has.

```swift
                let failure = (error as? WayError) ?? .unavailable
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:236@7c200bf

   Impact: low; the copy points at the network.

7. **"finish this walk first" on a walk that hasn't started** (§6.2). The refusal covers the pre-walk screen before Start, where nothing is recorded yet. The copy reads oddly there, and closing the pre-walk screen doesn't bring the link back. This may be intended.

   Impact: low.

### Open questions

1. **Row 14 on an iPhone.** Does an overview requested under an untracked sheet ever appear: never, or once that sheet closes? The answer decides whether defect 1 is filed and what Android matches for the journal, Goshuin, widget and Recordings summary hosts.
2. **Android's other summary hosts.** iOS parks only behind the post-walk summary. Android's summary route is reached from Home, Goshuin, the post-walk finish, Recordings and the widget (own-walk spec F §6.2). Whether a link parks behind each (iOS's post-walk rule) or follows iOS's row 14 depends on question 1.
3. **Task restore after process death.** If the process dies during onboarding, Android may restore the task and re-deliver the link that launched it. That would hold a link iOS loses (R18: "lost if the app process dies first, as on iOS"). A device check in U27 decides whether a replay is suppressed for parity or recorded as a divergence.
   - **Resolved in U27 (2026-10-01): suppressed, matched.** A restored or Recents-relaunched task never replays its link. An Activity rebuilt from saved state routes no link from its intent, and neither does an intent carrying `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, which Recents sets when it starts a task again from its first intent after a reboot or a force stop (`honorLinkOf`). A configuration change finds the link and its extras already stripped from the intent. The device check hasn't run; it's in the combined device pass.
4. **A link to a share already held that has expired.** iOS fetches again and shows an expired-share line rather than opening the stored copy, even though the copy is still listed under "Shared with you" (§11.2). The question for the owner is whether that is intended (the share has "returned to the trail") or a defect to file.
5. **Toast accessibility on Android.** A Compose toast with a live region would be announced by TalkBack, and iOS's is not (defect 3). Matching iOS's silence or announcing is a choice the gate should record.
6. **The toast during an Android sitting.** iOS's refusal is invisible under the meditation cover. Android's `MEDITATION` is its own route, so the toast's host decides whether it shows there; a choice to record with question 5.
7. **The seal reveal with a link** (§5 row 13). The overview presents over the seal reveal. What a Begin from that overview does to the pending seal and summary isn't traced; that needs an iPhone check.
8. **SwiftUI's double delivery** (§9.2). Whether both `onOpenURL` and `onContinueUserActivity` fire for one universal link is unverified. It's benign either way, and Android needs nothing for it.

---

## S3. Shared-walk media, the expiry sweep, and the store

Pin: `pilgrim-ios` @ `7c200bf`. Android HEAD cited: `636cf5ce`. Feeds U28 (media gathering, the sweep, Settings → Ways). Four lenses applied to every file: behavior (state, transitions, threads, timing), UI and visual (only the strings this cluster owns), data (layout, keys, write threads), edge cases (bounds, boundaries, tricks).

Files read end to end at the pin: `Pilgrim/Models/Honor/WayMediaDownloader.swift`, `Pilgrim/Models/Honor/WayStore.swift`, `Pilgrim/Models/Honor/HonorImportReducer.swift`, `Pilgrim/Scenes/Honor/HonorWaysSheet.swift`, `Pilgrim/Scenes/Settings/WaysListView.swift`, `Pilgrim/Scenes/Settings/SettingsCards/DataCard.swift`, `Pilgrim/AppDelegate.swift` (launch tasks), the `gather`/`retryMedia`/`walkWithoutMissingVoices` part of `Pilgrim/Scenes/Root/MainCoordinatorView.swift`, `Pilgrim/Scenes/Honor/WayMomentPreview.swift`, `Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift`, and the tests `UnitTests/Honor/WayMediaDownloaderTests.swift`, `WayStoreTests.swift`, `WaysListModelTests.swift`, `HonorImportReducerTests.swift`. `WayImporter.swift` and `TourManifest.swift` were read only for the media paths they write; their validation belongs to another reader.

Own-walk spec A §12–§16 and §23 already cover the store's ids, `save`/`load`/`list`/`delete`, `accepted.json`, replies, and the link index. This section confirms them for shared Ways and adds what only a shared Way exercises: `media/`, the downloader, and the sweep.

### 1. Storage layout for a shared Way

A shared Way's folder is `share:<10 chars>`, the id the importer builds from the share id:

```swift
        return Way(
            id: "share:\(shareId)",
            source: .share(id: shareId, pageURL: baseURL.appendingPathComponent(shareId)),
            title: wayTitle, departedAt: departed, tzIdentifier: m.tz_identifier, expires: expires,
```
> Pilgrim/Models/Honor/WayImporter.swift:185-188@7c200bf

The importer writes every media reference as one of two relative paths, each built from the manifest's integer `n`:

```swift
                moments.append(WayMoment(id: "voice-\(voiceN)", frac: e.frac, at: at,
                    kind: .voice(endFrac: e.end_frac ?? e.frac, duration: e.duration ?? 0,
                                 kind: e.type == "voice" ? .spoken : .ambient, media: .file("audio/\(n).m4a")),
```
> Pilgrim/Models/Honor/WayImporter.swift:149-151@7c200bf

```swift
                moments.append(WayMoment(id: "photo-\(photoN)", frac: e.frac, at: at, kind: .photo(media: .file("photos/\(n).jpg"))))
```
> Pilgrim/Models/Honor/WayImporter.swift:156@7c200bf

`n` is bounded by the importer's validation before it reaches a path:

```swift
            if let v = e.n, !(1...maxEncounterN).contains(v) { return false }
```
> Pilgrim/Models/Honor/WayImporter.swift:107@7c200bf

```swift
    static let maxEncounterN = 10_000
```
> Pilgrim/Models/Honor/WayImporter.swift:16@7c200bf

`.file` paths are relative to the Way's `media/` folder:

```swift
enum WayMedia: Codable, Equatable {
    /// Relative to `Ways/{id}/media/`.
    case file(String)
```
> Pilgrim/Models/Honor/Way.swift:19-21@7c200bf

```swift
    func mediaDirectory(for id: String) -> URL {
        directory(for: id).appendingPathComponent("media", isDirectory: true)
    }

    func mediaURL(for id: String, relative: String) -> URL {
        mediaDirectory(for: id).appendingPathComponent(relative)
    }
```
> Pilgrim/Models/Honor/WayStore.swift:136-142@7c200bf

The full shared layout, from the paths the store and downloader build:

```
Application Support/Ways/                    excluded from iCloud backup (WayStore.swift:44-47)
  index.json                                 walk uuid → WayLink, shared by every Way (own-walk spec A §14)
  share:<id>/                                id = "share:" + [A-Za-z0-9_-]{10}
    way.json                                 written at import; rewritten at every clean walk end
    accepted.json                            {"acceptedAt": …}, written once, at the first import
    replies.json                             only after a reply is filed
    media/                                   created by the first delivered file, never by import
      audio/<n>.m4a                          n in 1…10000 from the importer; voice and ambience share the folder
      photos/<n>.jpg
```

Facts the layout depends on:

- **Import writes no `media/` folder.** `importShare` ends with `try store.save(way)` (`WayImporter.swift:84`), and `save` creates only the Way folder (`WayStore.swift:93-102`). `media/` and its `audio/` and `photos/` subfolders come into being only inside the downloader's `move`, which creates the destination's parent before moving the file in (§9).
- **`accepted.json` is the import time.** It is written once by the first `save` and kept by every later `save` (own-walk spec A §13). A re-import of the same share and the clean walk-end re-save of a shared Way (`if !way.source.isPackageOwned { try? WayStore.shared.save(way) }`, `MainCoordinatorView.swift:118`) both keep it, so a shared Way keeps its place in the newest-first `list()`.
- **Voice and ambience files share `audio/`.** Both encounter types map to `audio/<n>.m4a` (`case "voice", "ambience":`, `WayImporter.swift:146`).
- **Duplicate `n`.** Two encounters with the same `n` and folder name one file; the downloader fetches it once (§2).
- **Backup.** The whole tree is excluded from iCloud backup at store init (own-walk spec A §12). Android's `noBackupFilesDir` root is the platform equivalent.

Android at `636cf5ce`: `WayStore.kt` already ports the media helpers (`mediaDirectory`, `mediaFile`, `hasMedia`, `deleteMedia`, `diskUsage`, `app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/WayStore.kt:128-157@636cf5ce`). It keeps one link file per walk under `links/` (`:58`, `:190-196`) and own-walk staging under `staging/` (`:59`, `:214-246`). `mediaFile` adds a canonical-path containment check that iOS applies only at read time, through `resolvedMediaURL` (own-walk spec A §16). The store has no sweep yet.

### 2. Which files a Way asks for, and where they come from

```swift
    nonisolated static func mediaFiles(for way: Way) -> [String] {
        var seen: Set<String> = []
        var files: [String] = []
        for moment in way.moments {
            let media: WayMedia?
            switch moment.kind {
            case .voice(_, _, _, let m): media = m
            case .photo(let m): media = m
            default: media = nil
            }
            if case .file(let relative)? = media, !seen.contains(relative) {
                seen.insert(relative)
                files.append(relative)
            }
        }
        return files
    }

    nonisolated static func remoteURL(baseURL: URL = WayImporter.baseURL, shareId: String, relative: String) -> URL {
        baseURL.appendingPathComponent(shareId).appendingPathComponent(relative)
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:44-64@7c200bf

```swift
    static let baseURL = URL(string: "https://walk.pilgrimapp.org")!
```
> Pilgrim/Models/Honor/WayImporter.swift:10@7c200bf

- **Order.** Files follow `way.moments` order. The importer sorts moments by frac, then id (`WayImporter.swift:180`), so the first file fetched is the voice or photo nearest the start, and every later rule that says "in manifest order" means this order.
- **Dedup.** The `seen` set keeps the first occurrence of each relative path. Only `.file` media count; own-walk `.recording` and `.photoAsset` never reach the downloader.
- **URL shape.** `https://walk.pilgrimapp.org/<shareId>/audio/<n>.m4a` and `…/photos/<n>.jpg`. iOS pins it: `"https://walk.pilgrimapp.org/aaaaaaaaaa/audio/1.m4a"` (`UnitTests/Honor/WayMediaDownloaderTests.swift:60-61@7c200bf`). The host is CLAUDE.md's share worker, but the table lists only the uploads (`PUT /api/share/{id}/{photos|audio}/{n}`) and the pages (`/{id}`). These public GET routes (`/{id}/audio/{n}.m4a`, `/{id}/photos/{n}.jpg`) are new to Android, and worth a line in that table (info only). The worker matches them as `^\/([a-zA-Z0-9_-]{10})\/audio\/(\d+)\.m4a$`, with Range support, and the photos equivalent (`pilgrim-worker` `src/index.ts:32,36`, outside the pin).
- **No shape check on the forward path.** `download` does not match `mediaPathPattern` against the Way's relative paths (§6). It trusts the Way, which was built by the importer in this process or loaded from the app's own `way.json`. The pattern is applied only when a delivery is rebuilt from a URL after a relaunch (§10).

**The path pattern** (the "index of at most 5 digits" the own-walk spec notes):

```swift
    /// What `WayImporter.way(from:shareId:now:)` writes into every `media:
    /// .file(...)`: an index of at most 5 digits under the matching folder.
    /// A prefix check alone can't be trusted here — see `entry(from:)`.
    nonisolated private static let mediaPathPattern = "\\A(?:audio/[0-9]{1,5}\\.m4a|photos/[0-9]{1,5}\\.jpg)\\z"
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:95-98@7c200bf

- Exactly two shapes: `audio/` + 1–5 ASCII digits + `.m4a`, or `photos/` + 1–5 digits + `.jpg`. The extension must match the folder (`testEntryFromURLRejectsMismatchedExtension`, `WayMediaDownloaderTests.swift:178-181`).
- `0` and leading zeros (`audio/007.m4a`) match the pattern, though the importer never writes them (`n` starts at 1 and is formatted by Swift string interpolation). The pattern admits up to `99999`, while the importer caps `n` at `10_000`.
- Anchored `\A…\z`. In Kotlin, use `Regex.matches` (whole input), as `WayStore.kt:380` already does for ids.

**The same-host rule.** It lives only in `entry(from:)`, the relaunch rebuild:

```swift
    nonisolated static func entry(from url: URL) -> (wayId: String, relative: String)? {
        guard url.host == WayImporter.baseURL.host else { return nil }
        let components = url.pathComponents.filter { $0 != "/" }
        guard components.count >= 2, !components.contains("..") else { return nil }
        let shareId = components[0]
        guard WayImporter.isShareId(shareId) else { return nil }
        let relative = components.dropFirst().joined(separator: "/")
        guard relative.range(of: mediaPathPattern, options: .regularExpression) != nil else { return nil }
        return (wayId: "share:\(shareId)", relative: relative)
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:109-118@7c200bf

- The host must equal `"walk.pilgrimapp.org"` exactly (a string compare on `URL.host`).
- The first path component must be a share id (`\A[A-Za-z0-9_-]{10}\z`, `WayImporter.swift:27-29`), and the rest, joined with `/`, must match the pattern.
- Any `..` component is refused before the pattern runs, and a percent-encoded `/` (`audio%2F..%2F..%2Fx.m4a`) decodes into one component that the pattern then rejects. The comment above it explains why (`WayMediaDownloader.swift:100-108`).
- iOS pins every rejection: foreign host, short id, `video/1.mp4`, `../../etc/passwd`, `audio%2F..%2F..%2Fx.m4a`, `audio/%2E%2E/x.m4a`, `audio/12.jpg`, and an id with a trailing `%0A` (`WayMediaDownloaderTests.swift:131-186@7c200bf`).

**Redirects.** The delegate implements no `urlSession(_:task:willPerformHTTPRedirection:newRequest:completionHandler:)` (the extension at `WayMediaDownloader.swift:251-325` has four callbacks only), so the system's default applies and a redirect is followed to whatever host it names. The relaunch rebuild reads `downloadTask.originalRequest?.url` (`:259`), the URL the app built, so the same-host rule never sees a redirect target. iOS has no media redirect rule to match. A refusal on Android would be hardening (Resolutions 3).

### 3. Per-file and per-Way bounds

**Per-file byte caps:**

```swift
    nonisolated static func byteCap(for relative: String) -> Int {
        relative.hasPrefix("audio/") ? 15 * 1024 * 1024 : 2 * 1024 * 1024
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:66-68@7c200bf

- Audio: `15 * 1024 * 1024` = 15,728,640 bytes. Everything else (photos): `2 * 1024 * 1024` = 2,097,152 bytes. Pinned at `WayMediaDownloaderTests.swift:62-63`.
- The prefix test is `hasPrefix("audio/")`; any path that does not start with `audio/` gets the photo cap.

**Per-Way count ceilings**, applied to everything the Way declares:

```swift
    /// The sharing side ships at most 12 recordings and 20 photos, so a
    /// manifest declaring more never came from a walk page this app made.
    /// `WayImporter.maxEncounters` alone would let one share id pull 200
    /// files (3 GB at the audio cap) onto the disk; refusing the excess
    /// before anything is enqueued bounds that at 12x15 MB + 20x2 MB.
    static let maxAudioFilesPerWay = 12
    static let maxPhotoFilesPerWay = 20

    /// Splits the files to fetch into the ones within the per-kind ceilings
    /// and the ones beyond them, preserving manifest order so the same
    /// manifest always refuses the same files.
    private static func withinCeilings(_ files: [String]) -> (accepted: [String], refused: [String]) {
        var accepted: [String] = []
        var refused: [String] = []
        var audio = 0, photos = 0
        for relative in files {
            let isAudio = relative.hasPrefix("audio/")
            let room = isAudio ? audio < maxAudioFilesPerWay : photos < maxPhotoFilesPerWay
            guard room else { refused.append(relative); continue }
            if isAudio { audio += 1 } else { photos += 1 }
            accepted.append(relative)
        }
        return (accepted, refused)
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:70-93@7c200bf

- The 13th and later distinct audio paths, and the 21st and later photo paths, in manifest order, are refused. "Distinct" because `mediaFiles` has already deduplicated.
- The ceilings apply to the **declared** list, not the files still missing (`WayMediaDownloader.swift:125-128`). A partly fetched hostile manifest can't pass them over several gathers.
- **Refused files become failures.** `failures[way.id] = refused.isEmpty ? nil : refused` (`:130`). The overview then reads `.mediaMissing`, with "some voices didn't arrive" and "try again", and "try again" refuses the same files again (§11). iOS pins it: 14 audio plus 23 photos gives exactly 5 refused, including `audio/14.m4a` and `photos/23.jpg` and not `audio/1.m4a` (`WayMediaDownloaderTests.swift:253-270`).

**Streaming cap.** The cap is enforced as bytes arrive, but only for a task this process enqueued:

```swift
    /// The byte cap is enforced while the bytes arrive, not after: an oversized
    /// object is cancelled as soon as it crosses its cap.
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                                totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        MainActor.assumeIsolated {
            guard let entry = self.tasks[downloadTask.taskIdentifier] else { return }
            if totalBytesWritten > Int64(entry.cap) { downloadTask.cancel() }
        }
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:298-306@7c200bf

- Strictly greater: a file of exactly the cap passes.
- `totalBytesExpectedToWrite` (the declared `Content-Length`) is ignored, so nothing is refused before the first byte. An Android check of `Content-Length` before reading writes no byte past the cap either, so it is the same observable bound, reached earlier.
- The cancel ends the task with `URLError.cancelled`, which `didCompleteWithError` classes as not retryable (§7). An oversized file is therefore refused once, with no retry.

**Delivery cap and status gate:**

```swift
        // A resumed transfer finishes as 206, not 200. A relaunch-derived
        // target carries no remembered byte cap, so it falls back to the cap
        // its folder implies — never to "uncapped".
        guard (200...299).contains(status),
              known.map({ size <= $0.cap }) ?? (size <= Self.byteCap(for: target.relative)) else {
            finish(taskId: taskId, success: false)
            return
        }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:282-289@7c200bf

- Any 2xx is accepted. The "206" is the system's own resumption of a background transfer; the app never sends a `Range` header and never uses resume data (no `resumeData` or `cancel(byProducingResumeData:)` anywhere in the file).
- `size` is read from the delivered temp file, `(try? FileManager.default.attributesOfItem(atPath: location.path)[.size] as? Int) ?? 0` (`:258`). An unreadable size reads as `0`, which passes.
- A failed status or size goes to `finish(success: false)` with `retryable` at its default `true`, so it gets one retry (§7).
- **Nothing checks the content.** No `Content-Type`, magic bytes, or decode check exists. A 2xx HTML error page, or an empty 200, lands as `audio/<n>.m4a` (§16 and defect D6).

### 4. The 60 MB total: stated on the sharing side, not enforced on receipt

The 60 MB figure appears in three places at the pin, and none of them is the downloader.

The sharing side caps the total of recordings it uploads:

```swift
    static let maxRecordings = 12
    static let maxFileBytes = 15 * 1024 * 1024
    static let maxTotalBytes = 60 * 1024 * 1024
```
> Pilgrim/Models/Share/TourBuilder.swift:28-30@7c200bf

```swift
        if bytes > maxTotalBytes { return "Recordings total \(bytes / 1_048_576) MB — the page carries at most 60 MB." }
```
> Pilgrim/Models/Share/TourBuilder.swift:101@7c200bf

The Honor design doc says the worker bounds what the downloader receives:

```
Sizes are bounded by the worker: at most 12 recordings, 15 MB each, 60 MB
total; at most 20 photos, 2 MB each.
```
> docs/superpowers/specs/2026-09-01-honor-mode-design.md:356-357@7c200bf

The worker enforces it on the sizes the sharer declares (outside the pin; `pilgrim-worker` at `2a4f5d0`, `src/handlers/validate-share.ts:11` `MAX_AUDIO_TOTAL_BYTES = 60 * 1024 * 1024` and `:177` `"total audio size cannot exceed 60MB"`).

The downloader states the bound it does enforce, and it is not 60 MB:

```swift
    /// files (3 GB at the audio cap) onto the disk; refusing the excess
    /// before anything is enqueued bounds that at 12x15 MB + 20x2 MB.
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:73-74@7c200bf

- Nothing in `WayMediaDownloader.swift` sums bytes across a Way: `tasks` holds a per-file `cap` (`:25`), and no total is tracked or compared. The manifest declares no sizes (`TourManifest.Encounter` has no size field, `TourManifest.swift:22-39`), so the app couldn't check a total up front.
- The receiving app's worst case per shared Way is 12 × 15 MiB + 20 × 2 MiB = 180 + 40 = **220 MiB**, from a manifest the worker would never have produced. A genuine share is at most 60 MiB of audio plus 40 MiB of photos.
- The plan's "the share total stays unenforced, matching iOS" is confirmed. A search of pilgrim-ios issues on 2026-10-01 found none about it, so the plan's "(upstream issue filed)" is not yet true (candidate D5).

### 5. The transport: one background session, every file at once, any network

```swift
/// Downloads a Way's voices and photos on a background URLSession so a
/// locked phone finishes the job. Delegate-based by necessity: background
/// sessions reject async and completion-handler task APIs. Task ids map to
/// (wayId, relative file); the delivered temp file is moved atomically.
@MainActor
final class WayMediaDownloader: NSObject, ObservableObject {

    static let shared = WayMediaDownloader(store: .shared, sessionIdentifier: "org.walktalkmeditate.pilgrim.ways")
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:4-11@7c200bf

```swift
    init(store: WayStore, sessionIdentifier: String, baseURL: URL = WayImporter.baseURL) {
        self.store = store
        self.baseURL = baseURL
        super.init()
        let config = URLSessionConfiguration.background(withIdentifier: sessionIdentifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        // delegateQueue: .main is why every `MainActor.assumeIsolated` below
        // is sound — callbacks are guaranteed to land on the main queue.
        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:32-42@7c200bf

```swift
    private func enqueue(wayId: String, shareId: String, relative: String) {
        let task = session.downloadTask(with: Self.remoteURL(baseURL: baseURL, shareId: shareId, relative: relative))
        tasks[task.taskIdentifier] = (wayId, relative, Self.byteCap(for: relative))
        task.resume()
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:182-186@7c200bf

| Property | iOS value | Source |
|---|---|---|
| Session identifier | `"org.walktalkmeditate.pilgrim.ways"` | `:11` |
| Runs while locked or suspended | yes (background session) | `:4-5`, `:36` |
| Relaunches the app to deliver | yes, `sessionSendsLaunchEvents = true` | `:38` |
| Deferred for power or Wi-Fi | no, `isDiscretionary = false` | `:37` |
| Cellular | allowed: `allowsCellularAccess` is never set, so the default (`true`) holds | `:36-41` |
| Request and resource timeouts | never set: platform defaults (contrast the importer's `15` and `30` s, `WayImporter.swift:36-41`) | `:36-41` |
| Concurrency | every missing file is enqueued in one loop (`for relative in files { enqueue(…) }`, `:143`); `httpMaximumConnectionsPerHost` is not set, so the system schedules them | `:143` |
| Callback thread | main (`delegateQueue: .main`); bookkeeping and the file move run on main | `:39-41`, `:260-262` |
| Backoff | none: a retry is enqueued at once, from the failure callback (§7) | `:199-202` |

The app delegate hands the session's completion handler to the downloader when the system wakes the app for it:

```swift
    func application(_ application: UIApplication,
                     handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == "org.walktalkmeditate.pilgrim.ways" else { completionHandler(); return }
        // This delegate method already runs on main, so the handler is
        // installed synchronously: a Task hop could let the session's own
        // completion callbacks fire before the handler is in place, and the
        // system would never be told the work was finished.
        MainActor.assumeIsolated {
            WayMediaDownloader.shared.backgroundCompletionHandler = completionHandler
        }
    }
```
> Pilgrim/AppDelegate.swift:234-245@7c200bf

```swift
    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            self.backgroundCompletionHandler?()
            self.backgroundCompletionHandler = nil
        }
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:319-324@7c200bf

Touching `WayMediaDownloader.shared` there constructs the singleton, which recreates the session under the same identifier and so reattaches the delegate to transfers that outlived the process.

Android mapping (platform equivalents, R6):

- **Background session → WorkManager in the UI process.** The `:tracker` rule allows no WorkManager enqueues there (plan Global Constraints). WorkManager persists work across process death, as the background session persists transfers.
- **Network.** iOS allows cellular and ignores power, so the parity constraint is `NetworkType.CONNECTED`. The whisper model's default `UNMETERED` (`app/src/main/java/org/walktalkmeditate/pilgrim/audio/model/WhisperModelDownloadScheduler.kt:124@636cf5ce`) would diverge: Wi-Fi-only gathering would leave "gathering their voices" stuck on a phone with only cellular data.
- **No storage constraint.** iOS has no low-storage gate; a full disk surfaces as the disk-full copy (§8). The voice guide's and soundscape's `setRequiresStorageNotLow(true)` (`data/voiceguide/VoiceGuideDownloadScheduler.kt:67@636cf5ce`, `data/soundscape/SoundscapeDownloadScheduler.kt:69@636cf5ce`) would hold the job enqueued with no copy. The whisper scheduler's comment already names that trap (`WhisperModelDownloadScheduler.kt:125-127@636cf5ce`).
- **No expedited work, no battery constraint.** This follows from `isDiscretionary = false` and also avoids the `Expedited + BatteryNotLow` crash class (CLAUDE.md).
- **Concurrency is not observable** beyond progress order. Android may fetch serially, in manifest order, or in parallel; progress and failures are per file either way (§6).

### 6. The downloader's state machine

State. The published sets drive the overview; the private ones are in-memory bookkeeping, lost with the process:

```swift
    @Published private(set) var progress: [String: Double] = [:]
    @Published private(set) var failures: [String: [String]] = [:]
    @Published private(set) var active: Set<String> = []
    /// Way ids whose download hit a full disk; the coordinator names the problem instead of offering a retry.
    @Published private(set) var diskFull: Set<String> = []
    var backgroundCompletionHandler: (() -> Void)?

    private let store: WayStore
    /// Injectable so a spec can resume real tasks against an unroutable host
    /// instead of the live one; production always gets `WayImporter.baseURL`.
    private let baseURL: URL
    private var session: URLSession!
    private var tasks: [Int: (wayId: String, relative: String, cap: Int)] = [:]
    private var pending: [String: Set<String>] = [:]
    private var retried: [String: Set<String>] = [:]
    /// File count per Way at the moment `download(_:)` was called: `finish`
    /// reads this instead of reloading the Way from disk on every completion.
    private var totals: [String: Int] = [:]
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:13-30@7c200bf

| Field | Key | Meaning |
|---|---|---|
| `progress` | Way id | fraction done, `0…1` |
| `failures` | Way id | relative paths that won't arrive this round (refused, failed after retry, cancelled for size, disk full) |
| `active` | Way id | a gather is in flight |
| `diskFull` | Way id | a file hit a full disk |
| `tasks` | task id | `(wayId, relative, cap)` for every task this process enqueued |
| `pending` | Way id | relative paths not yet finished, success or failure |
| `retried` | Way id | relative paths that have used their one retry |
| `totals` | Way id | declared file count within the ceilings, for the progress denominator |

**`download(_:)`**, the only entry that starts work:

```swift
    func download(_ way: Way) {
        guard case .share(let shareId, _) = way.source else { return }
        // A second `gather` from a reopened overview must not double-enqueue
        // a Way that's already downloading.
        guard !active.contains(way.id) else { return }
        // The ceilings are applied to everything the Way declares, not just
        // the files still missing, so a partly-fetched hostile manifest can't
        // walk past them one `download` at a time.
        let (declared, refused) = Self.withinCeilings(Self.mediaFiles(for: way))
        let files = declared.filter { !FileManager.default.fileExists(atPath: store.mediaURL(for: way.id, relative: $0).path) }
        failures[way.id] = refused.isEmpty ? nil : refused
        diskFull.remove(way.id)
        guard !files.isEmpty else {
            progress[way.id] = 1
            totals[way.id] = nil
            return
        }
        active.insert(way.id)
        pending[way.id] = Set(files)
        totals[way.id] = declared.count
        // Seeded from what's already on disk, not 0, so progress is monotonic
        // across repeated `download()` calls for the same Way.
        progress[way.id] = 1 - Double(files.count) / Double(declared.count)
        for relative in files { enqueue(wayId: way.id, shareId: shareId, relative: relative) }
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:120-144@7c200bf

In order:

1. A non-share Way is a no-op.
2. A Way already `active` is a no-op, and this check runs **before** `failures` and `diskFull` are reset (see §16, "reopened during a disk-full gather").
3. The ceilings split the declared list into accepted and refused.
4. **"Missing" means no file at the final path.** `fileExists` only, with no size or integrity check, so whatever landed counts as present forever (D6).
5. Refused files replace any earlier failures, and `diskFull` is cleared.
6. Nothing missing gives `progress = 1`, with `active` untouched. With refused files the overview then reads `.mediaMissing`; without, `.ready`. iOS pins the all-present case (`testDownloadWithEverythingAlreadyOnDiskCompletesImmediately`, `WayMediaDownloaderTests.swift:69-95`).
7. Otherwise the Way is marked active, the denominator is the accepted count (files already present included), progress is seeded at `1 − missing/accepted`, and every missing file is enqueued.

**`finish`**, called once per task outcome:

```swift
    private func finish(taskId: Int, success: Bool, retryable: Bool = true, diskFull: Bool = false) {
        // With the entry already gone — cancelled by `cancel(wayId:)`, or
        // never present because this task predates the current process —
        // there is nothing left to record.
        guard let entry = tasks.removeValue(forKey: taskId) else { return }
        if diskFull { self.diskFull.insert(entry.wayId) }
        if !success {
            if retryable {
                let shareId = store.load(id: entry.wayId).flatMap { way -> String? in
                    if case .share(let id, _) = way.source { return id } else { return nil }
                }
                if let shareId, !(retried[entry.wayId]?.contains(entry.relative) ?? false) {
                    retried[entry.wayId, default: []].insert(entry.relative)
                    enqueue(wayId: entry.wayId, shareId: shareId, relative: entry.relative)
                    return
                }
            }
            failures[entry.wayId, default: []].append(entry.relative)
        }
        pending[entry.wayId]?.remove(entry.relative)
        let total = Double(totals[entry.wayId] ?? 0)
        let left = Double(pending[entry.wayId]?.count ?? 0)
        progress[entry.wayId] = total > 0 ? 1 - left / total : 1
        if left == 0 {
            active.remove(entry.wayId)
            totals[entry.wayId] = nil
            retried[entry.wayId] = nil
        }
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:188-216@7c200bf

- **Progress counts failures as done.** `left` shrinks on success and on final failure alike, so a gather where every file 404s still climbs to 100% before `.mediaMissing` shows.
- A retry re-enqueues and returns **before** `pending` changes, so a retried file isn't counted done twice.
- At `left == 0` the Way leaves `active`, and `totals` and `retried` are cleared. `failures`, `progress`, and `diskFull` stay for the overview to read until the next `download`, `retry`, or `cancel`.

**`retry(_:)`** (the overview's "try again"):

```swift
    func retry(_ way: Way) {
        // A retry tapped while the last failure is still hopping to the main
        // actor would find the Way still `active` and silently do nothing.
        // Clearing the bookkeeping first makes the retry unconditional: the
        // in-flight `finish` then finds no entry of its own and returns.
        cancel(wayId: way.id)
        download(way)
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:146-153@7c200bf

iOS pins it: a retry during an active gather leaves the Way active again (`testRetryRestartsEvenWhileTheWayIsStillActive`, `WayMediaDownloaderTests.swift:274-289`). It gives every missing file a fresh retry allowance, because `cancel` clears `retried`.

**`cancel(wayId:)`**:

```swift
    func cancel(wayId: String) {
        let ids = Set(tasks.filter { $0.value.wayId == wayId }.keys)
        for id in ids { tasks.removeValue(forKey: id) }
        active.remove(wayId)
        diskFull.remove(wayId)
        pending[wayId] = nil
        progress[wayId] = nil
        totals[wayId] = nil
        failures[wayId] = nil
        retried[wayId] = nil
        // Cancel only what was claimed above: a task that shows up here
        // after the snapshot belongs to a `download()` issued after this
        // cancellation, not to it.
        session.getAllTasks { allTasks in
            MainActor.assumeIsolated {
                for task in allTasks where ids.contains(task.taskIdentifier) { task.cancel() }
            }
        }
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:155-173@7c200bf

- Every per-Way entry is cleared synchronously (pinned, `testCancelClearsEveryPerWayEntry`, `WayMediaDownloaderTests.swift:100-122`).
- The network cancel is asynchronous and reaches only tasks in this process's `tasks` map. A transfer that outlived an earlier process has no entry, so `cancel` can't stop it (§10, D7).
- A cancelled task's `didCompleteWithError(.cancelled)` then finds no entry in `finish` and records nothing.

**Transitions:**

| From | Event | To | Code |
|---|---|---|---|
| (none) | `download`, files missing | `active`, `progress = 1 − missing/accepted` | `:137-143` |
| (none) | `download`, nothing missing | not active, `progress = 1`, `failures` = refused or nil | `:132-136` |
| `active` | `download` again | unchanged (no-op) | `:124` |
| `active` | a file lands | `progress` rises; at the last file, not active | `:207-215` |
| `active` | a file fails, retry unused, Way on disk | the file is re-enqueued; nothing visible changes | `:195-203` |
| `active` | a file fails finally | `failures += file`; `progress` rises | `:205-210` |
| `active` | disk full | `diskFull += Way`, `failures += file`, other files continue | `:193`, `:205` |
| any | `retry` | `cancel`, then `download` | `:151-152` |
| any | `cancel` | every entry cleared | `:155-164` |

Android: none of this exists yet (U28 creates `P/data/honor/WayMediaDownloadWorker.kt` and its scheduler). WorkManager's `WorkInfo` state and progress plus file presence can stand in for `active`/`progress`. `failures` and `diskFull` need a home the overview can read after the work ends: output data, or a small per-Way state the worker writes.

### 7. Retry and failure classification

Every way a file can end, and what the downloader does:

| Outcome | Where | `success` | `retryable` | Result |
|---|---|---|---|---|
| Delivered, 2xx, size ≤ cap, moved | `deliver` → `.moved` | true | — | lands |
| Delivered, non-2xx (404, 410, 5xx…) | `deliver` status gate | false | true | one retry, then failure |
| Delivered, size > cap | `deliver` size gate | false | true | one retry, then failure |
| Delivered, move failed (not disk full) | `deliver` → `.failed` | false | true | one retry, then failure |
| Delivered, move failed with disk full | `deliver` → `.diskFull` | false | false | failure + `diskFull` |
| Transport error (offline, timeout, TLS…) | `didCompleteWithError` | false | true | one retry, then failure |
| Transport error, disk full | `didCompleteWithError` | false | false | failure + `diskFull` |
| Cancelled for the byte cap | `didCompleteWithError`, `.cancelled` | false | false | failure, no retry |
| Cancelled by `cancel(wayId:)` | `didCompleteWithError`, `.cancelled` | — | — | no entry, nothing recorded |
| Delivered for a Way no longer on disk | `deliver` guard | — | — | entry dropped, nothing recorded (§9) |

```swift
    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        let taskId = task.taskIdentifier
        let cancelled = (error as? URLError)?.code == .cancelled
        // Disk-full is reported here, mid-transfer, not only on the
        // same-volume rename in `didFinishDownloadingTo` — both paths exist
        // because either can be where the OS actually surfaces it.
        let diskFull = Self.isDiskFull(error)
        Task { @MainActor in self.finish(taskId: taskId, success: false, retryable: !cancelled && !diskFull, diskFull: diskFull) }
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:308-317@7c200bf

The retry rule (`:194-204`, quoted in §6):

- **One retry per file per gather round.** `retried` is keyed by Way and relative path. It is cleared when the round ends (`left == 0`, `:214`) and by `cancel`, so the next `download` or "try again" starts every file with a fresh retry.
- **Immediate.** The retry is enqueued inside the failure callback; there is no delay, jitter, or backoff.
- **The retry reloads the Way from disk** for its share id (`store.load(id:)`, `:196`). A Way deleted or swept whole since gets no retry; its failure is recorded under an id that no list shows.
- **An HTTP error is a delivery, not an error.** A download task hands over the response body for any status, so a 404 arrives through `deliver` with `status == 404` and no error (`didCompleteWithError` returns at `guard let error`). The worker answers 404 once its daily cleanup has removed an expired share's objects (`pilgrim-worker` `wrangler.toml:7` `crons = ["0 3 * * *"]`; `src/handlers/expiry.ts:37-61`).

Android: WorkManager's own `Result.retry()` backoff (30 s exponential by default) is not iOS's shape. To match, a worker retries a failed file once, immediately and in-process, then records the failure and returns success with the failures in its output. `Result.retry()` would also re-run files that already landed. That is harmless, because the "missing" check skips them, but it delays the overview's `.mediaMissing` by the backoff.

### 8. Disk full

```swift
    /// A full disk can surface as a `URLError` on the transfer itself or as
    /// an `NSCocoaErrorDomain` write error on the eventual file operation
    /// (directly, or wrapped as the underlying error).
    /// Shared with `PilgrimagePackageManager`: a full disk surfaces the same
    /// three ways whichever transfer hit it.
    nonisolated static func isDiskFull(_ error: Error) -> Bool {
        if (error as? URLError)?.code == .cannotWriteToFile { return true }
        let nsError = error as NSError
        if nsError.domain == NSCocoaErrorDomain && nsError.code == NSFileWriteOutOfSpaceError { return true }
        if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError { return isDiskFull(underlying) }
        return false
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:237-248@7c200bf

- **Three detections**: `URLError.cannotWriteToFile` from the transfer, `NSFileWriteOutOfSpaceError` from a file operation, and either one nested as an underlying error (recursively).
- **Terminal per file.** `retryable: false` at both sites (`:293`, `:316`).
- **Not terminal per Way.** The Way's other tasks keep running; nothing cancels them. Each later file may land or fail on its own.
- **It wins the overview immediately.** The reducer checks `diskFull` before `active` (§11), so "not enough space on this phone to save these voices" shows while other files are still in flight, and stays after they finish.
- **No "try again" and no "walk without the missing voices".** Those two buttons render only for `.mediaMissing` (`HonorOverviewView.swift:320-334`). Begin is **enabled** under `.failed(.diskFull)`, because only `.gathering` and `.fetching` disable it (`HonorOverviewView.swift:296-301`). So the walker can Begin with what landed, without a button saying so.
- **Cleared** only by the next `download` (`:131`, unless the Way is still active) or a `cancel` (`:159`). Closing the overview resets the coordinator's displayed state to `.idle` (`MainCoordinatorView.swift:330-333`), but not the downloader's set.
- **What stays on disk.** Every file that already landed stays. The failed file never reaches its final path, because the transfer's temp file belongs to the system and is discarded. A failed `move` can leave an empty `media/audio/` or `media/photos/` folder behind (§9).

Android: the counterpart signals are `ErrnoException` with `ENOSPC` (or an `IOException` whose cause is one) during the stream or the rename. A `StatFs` precheck like the whisper worker's (`app/src/main/java/org/walktalkmeditate/pilgrim/audio/model/WhisperModelDownloadWorker.kt:168-177@636cf5ce`, terminal `REASON_STORAGE`) is an acceptable earlier detection of the same condition. The plan's U28 test "disk full at file 3 → iOS's disk-full copy, partial files removed, Begin still possible" reads correctly only if "partial files" means the interrupted file's partial bytes. iOS keeps files 1 and 2.

### 9. Delivery, the deleted-Way guard, and what a partial download leaves

```swift
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The temp file is deleted when this returns: resolve and move it
        // synchronously, still inside the isolated block that follows.
        let taskId = downloadTask.taskIdentifier
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 0
        let size = (try? FileManager.default.attributesOfItem(atPath: location.path)[.size] as? Int) ?? 0
        let requestURL = downloadTask.originalRequest?.url
        MainActor.assumeIsolated {
            self.deliver(taskId: taskId, requestURL: requestURL, status: status, size: size, location: location)
        }
    }

    /// The delivery decision, lifted out of the delegate callback so a spec
    /// can drive it without fabricating a `URLSessionDownloadTask`.
    func deliver(taskId: Int, requestURL: URL?, status: Int, size: Int, location: URL) {
        let known = tasks[taskId]
        guard let target = known.map({ (wayId: $0.wayId, relative: $0.relative) })
            ?? requestURL.flatMap(Self.entry(from:)) else { return }
        // Nothing may land for a Way that is gone — deleted by the walker or
        // swept as expired — whether or not an in-memory entry still
        // remembers the transfer. `move` creates the folder it writes into,
        // so a delivery past this point would resurrect a directory no list
        // can see and no delete can reach. Drop the bookkeeping rather than
        // record a failure: the Way it would be recorded against no longer
        // exists.
        guard store.load(id: target.wayId) != nil else {
            tasks.removeValue(forKey: taskId)
            return
        }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:253-281@7c200bf

```swift
        let dest = store.mediaURL(for: target.wayId, relative: target.relative)
        switch Self.move(from: location, to: dest) {
        case .moved: finish(taskId: taskId, success: true)
        case .diskFull: finish(taskId: taskId, success: false, retryable: false, diskFull: true)
        case .failed: finish(taskId: taskId, success: false)
        }
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:290-296@7c200bf

```swift
    private enum MoveOutcome { case moved, diskFull, failed }

    /// Both steps report failure so a missing directory can't be mistaken
    /// for a failed move.
    nonisolated private static func move(from location: URL, to dest: URL) -> MoveOutcome {
        do {
            try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        } catch {
            return isDiskFull(error) ? .diskFull : .failed
        }
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: location, to: dest)
            return .moved
        } catch {
            return isDiskFull(error) ? .diskFull : .failed
        }
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:218-235@7c200bf

The order inside one delivery:

1. **Target.** An in-memory entry, else a rebuild from the request URL (§10), else drop.
2. **Deleted-Way guard.** `store.load(id:)` must return a Way: `way.json` present and decodable. Otherwise the entry is removed and nothing is recorded (no failure, no progress). iOS pins both halves. A delivery after `store.delete` neither lands nor recreates the folder and records nothing (`testDeliveryForADeletedWayNeitherLandsNorRecreatesTheFolder`, `WayMediaDownloaderTests.swift:190-212`); a live Way's file lands (`:214-229`).
3. **Status and size gate** (§3).
4. **Move.** Create the parent folder (`media/audio/` or `media/photos/`, and `media/` with it), remove any existing file at the destination, then move the system's temp file in. Same volume, so it is a rename. The destination is replaced, not merged.

What the guard does and does not cover:

- **It reads `way.json`, not `media/`.** A walked Way that the sweep retired (media only) still loads, so a late delivery for it **lands** and recreates `media/` (§13, D7).
- **The guard and the move are not one step.** Both run on main, but nothing locks the store, so a removal on another thread can land between them. Settings deletes and the sheet's and list's sweeps run on main, so they can't interleave with a delivery. The launch sweep runs detached (`AppDelegate.swift:200`) and can.
- **A deleted Way's in-flight state.** At iOS's two delete sites, `cancel(wayId:)` runs first (`WaysListView.swift:90-93`, quoted in §15), which clears `active`. If a delivery was dropped by the guard without that cancel, `pending` would keep the file and the Way would stay `active` until relaunch.

**What a partial download leaves.** No partial bytes ever sit at a final path. The transfer writes to a temp file the system owns, and only a complete delivery is moved into `media/`. A cancelled, failed, or capped transfer leaves nothing at the destination. Two leftovers are possible:

- an **empty** `media/audio/` or `media/photos/` folder, when `createDirectory` succeeded and the `moveItem` then failed;
- an **old file removed**, when `removeItem(at: dest)` succeeded and the move then failed. In practice the destination was missing anyway, since only missing files are enqueued.

The empty folder matters because `hasMedia` counts any entry in `media/`, folders included (§13).

Android: a streamed download writes to a temp file inside the Way's folder (the store's own temp-file convention is `.<name>.<uuid>.tmp`, `WayStore.kt:349`), then renames. The guard must be re-checked just before the rename, and the rename must not create `media/` for a Way whose `way.json` is gone (plan U28). Two Android-specific notes:

- `WayStore.sweepTempFiles` sweeps temp files only in `links/` and each Way folder's top level (`WayStore.kt:276-285`), not in `media/audio/`. A media temp placed deeper would need its own sweep.
- A Range resume across worker runs keeps partial bytes on disk between runs. That is Android's analogue of the system's resumable transfer, and it has to count toward the byte cap (plan U28: "counting a resumed partial").

### 10. Across launches: what resumes, what doesn't

**System-terminated while suspended.** The transfer continues in the system's daemon. When it completes, the system relaunches the app in the background: `didFinishLaunching` runs (including the launch sweep, §13), then `handleEventsForBackgroundURLSession` reattaches the session (§5). Deliveries then arrive for task ids this new process never registered:

- `deliver` rebuilds the target with `entry(from:)`: host, share id, and path shape are checked, and the cap falls back to the folder's (`:282-286`). iOS pins the fallback: a relaunch delivery one byte over the photo cap is refused (`testRelaunchDeliveryIsStillCapped`, `WayMediaDownloaderTests.swift:233-249`).
- The guard (§9) runs, then the move. `finish` finds no entry and returns: the file lands, but nothing is recorded, and there is no retry and no failure. A relaunch-era failure is silent.
- `didWriteData` finds no entry (`:303`), so there is **no streaming cap** for these tasks: the system writes the whole body to its temp file before the after-the-fact size check refuses it.

**Force-quit by the user.** The system cancels the session's transfers. Nothing resumes at launch: no launch task calls `download`. The next `gather` (the walker reopening that Way's overview) re-enqueues whatever is missing.

**In-memory state is lost either way.** `progress`, `active`, `failures`, `diskFull`, `retried`, `totals`, and `tasks` start empty. A reopened overview runs `download` afresh: progress is reseeded from the files on disk, refused files are recomputed, and the retry allowance is fresh.

**Two downloads of one file after a relaunch.** `active` is empty after a relaunch, so a reopened overview enqueues every missing file again, even while a transfer from the previous process is still running for the same file. Both may deliver. The second `move` removes the first file and moves its own in (`:228-230`). The result is wasteful but correct, and progress counts only the new tasks.

Android: WorkManager persists enqueued work across process death and reboot. A gather enqueued before a kill resumes on its own, which iOS does only for transfers the system was already carrying. That is an R6 platform equivalent and invisible to the walker, except that media can finish arriving without the overview being reopened, which iOS also does for system-carried transfers. Two parity points to keep:

- A relaunch-era delivery is checked against the same id, shape, cap, and Way-exists rules as a live one. Android's worker re-validates each relative path from its input data and builds the URL from the integer index (plan U28), which covers it.
- A unique work name per Way (`enqueueUniqueWork`, as the existing schedulers do, `VoiceGuideDownloadScheduler.kt:73-77@636cf5ce`) gives Android the `active` guard across processes that iOS has only within one. `KEEP` for a gather and `REPLACE` for "try again" mirror `download` and `retry`, as the voice guide's `enqueue`/`retry` pair already does (`VoiceGuideDownloadScheduler.kt:46-48@636cf5ce`).

### 11. Who starts and stops a gather, and what the overview reads

| Caller | Downloader call | When | Source |
|---|---|---|---|
| `gather(way)` | `download(way)` | an overview opens for a shared Way (link, paste, sheet row) | `MainCoordinatorView.swift:264-284` |
| `retryMedia(for:)` | `retry(way)` | "try again" | `:286-288` |
| `walkWithoutMissingVoices()` | none: the sink is dropped and the state set `.ready` | "walk without the missing voices" | `:300-303` |
| `startWalk` | none: only the observation is dropped | Begin → walk start | `:81-87` |
| `handleOverviewDismiss` | none: the sink is dropped and the state reset `.idle` on a real close | overview closed | `:325-333` |
| Settings → Ways delete | `cancel(wayId:)` before `store.delete` | one row or "Delete all" | `WaysListView.swift:90-93` |
| every sweep | `cancel(wayId:)` for each touched id | launch, the Ways sheet, Settings → Ways | §13 |

```swift
    func gather(_ way: Way) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            // A dismiss or an item swap that lands before this hop must not
            // install a sink for a Way that is no longer showing.
            guard self.honorOverviewWay?.id == way.id else { return }
            guard case .share = way.source else { self.honorImportState = .ready; return }
            let downloader = WayMediaDownloader.shared
            downloader.download(way)
            self.honorImportState = HonorImportReducer.state(
                wayId: way.id, progress: downloader.progress, active: downloader.active,
                failures: downloader.failures, diskFull: downloader.diskFull)
            self.gatheringCancellable = downloader.$progress
                .combineLatest(downloader.$active, downloader.$failures, downloader.$diskFull)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] progress, active, failures, diskFull in
                    self?.honorImportState = HonorImportReducer.state(
                        wayId: way.id, progress: progress, active: active, failures: failures, diskFull: diskFull)
                }
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:264-284@7c200bf

- **No expiry check.** `gather` calls `download` for any shared Way, expired or not (§15, D2).
- **Closing the overview does not cancel.** Transfers keep running; the sheet row and Settings show them once they land.
- **The walk does not cancel either:**

```swift
        // The overview is gone by the time a walk starts, so nothing is left
        // to render an import's progress — only the OBSERVATION is dropped.
        // The background transfers keep running, and a file that lands
        // mid-walk becomes playable like any other.
        importTask?.cancel()
        importTask = nil
        gatheringCancellable = nil
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:81-87@7c200bf

A voice resolves its file at the moment it would start (`localMediaURL`, own-walk spec A §16), so a file that lands mid-walk plays when its spot is reached, and a missing one hands the turn back to the engine (`ActiveWalkViewModel+Honor.swift:214-220`). On Android the voice plays in `:tracker` and the file lands from the UI process's worker. The rename makes the file appear whole, so the tracker's existence check at voice start is enough, with no cross-process signal.

**The overview's state, from the downloader's sets:**

```swift
    static func state(
        wayId: String,
        progress: [String: Double],
        active: Set<String>,
        failures: [String: [String]],
        diskFull: Set<String>
    ) -> HonorImportState {
        if diskFull.contains(wayId) { return .failed(.diskFull) }
        if active.contains(wayId) { return .gathering(progress: progress[wayId] ?? 0) }
        if let missing = failures[wayId], !missing.isEmpty { return .mediaMissing(missing) }
        return .ready
    }
```
> Pilgrim/Models/Honor/HonorImportReducer.swift:10-21@7c200bf

```swift
        case .gathering(let p): return "gathering their voices · \(Int((p * 100).rounded()))%"
        case .mediaMissing: return "some voices didn't arrive"
```
> Pilgrim/Models/Honor/HonorImportReducer.swift:29-30@7c200bf

```swift
        case .failed(.diskFull): return "not enough space on this phone to save these voices"
```
> Pilgrim/Models/Honor/HonorImportReducer.swift:34@7c200bf

- **Precedence:** disk full, then gathering, then missing, then ready. Pinned: "disk full outranks everything" (`HonorImportReducerTests.swift:13-14`).
- **Percent:** `Int((p * 100).rounded())`, half away from zero, so `0.456` gives `"46%"` (pinned, `HonorImportReducerTests.swift:23-25`). Kotlin's `roundToInt()` matches for non-negative values. The `·` is U+00B7.
- **`.mediaMissing` names nothing on screen.** It carries the missing paths, but the overview shows only the one line and the two buttons, `"try again"` and `"walk without the missing voices"` (`HonorOverviewView.swift:325-330`). The plan's "a partial gather shows which voices are missing" has no iOS counterpart beyond the voice preview's per-moment line (§15). The design doc's greyed pin and "this voice didn't arrive." card (`docs/superpowers/specs/2026-09-01-honor-mode-design.md:361-362`) never shipped: `git grep "didn.t arrive"` finds only the reducer's line.
- The overview's layout, Begin gating, and copy beyond these lines belong to the overview reader. They are named here because the downloader drives them.

### 12. The expiry sweep: the rule

```swift
    private var walkedIds: Set<String> { Set(loadIndex().values.map(\.wayId)) }

    // MARK: - Sweep

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

    /// The sweep's rule, applied to Ways a route no longer carries. A walk in
    /// the journal still names its stage, so a walked Way keeps `way.json`,
    /// its replies, and its index link and loses only its media; an unwalked
    /// one goes whole. Batched because `delete(id:)` rewrites `index.json` on
    /// every call and a two-hundred-stage route would pay that two hundred
    /// times — here the index is read once and, since retiring never drops a
    /// link, written not at all.
    func retireMany(ids: [String]) {
        let walked = walkedIds
        for id in ids { retire(id: id, walked: walked) }
    }

    // MARK: - Private

    private func retire(id: String, walked: Set<String>) {
        guard Self.isValidId(id) else { return }
        if walked.contains(id) {
            deleteMedia(id: id)
        } else {
            // An id no link names is absent from the index by definition, so
            // the folder is the whole of it.
            try? fileManager.removeItem(at: directory(for: id))
        }
    }
```
> Pilgrim/Models/Honor/WayStore.swift:194-235@7c200bf

| Way | `expires` | Walked (named by any link in `index.json`) | Action | Kept |
|---|---|---|---|---|
| share | `<= now` | no | the whole folder removed | nothing; the index is untouched, since no link names it |
| share | `<= now` | yes | `media/` removed | `way.json`, `accepted.json`, `replies.json`, every link |
| share | `> now` | either | nothing | everything |
| own walk (`walk:`) | `nil` | either | nothing | everything |
| stage (`pilgrimage:`) | `nil` | either | nothing (stages retire through `retireMany`, Stage 21-2) | everything |

- **"Walked" is the link index and nothing else**: every `wayId` value in `index.json`, read **once** at the start of the sweep. A walk whose link was never written isn't walked: a first honoring that crashed before relaunch, or a walk whose clean-finish `link` failed silently (own-walk spec A §20). A link left by a deleted walk still counts, since no walk-removal path touches the index (own-walk spec A §23).
- **Boundary.** Swept when `expires <= now`, so exactly at expiry. The importer uses the same boundary from the other side: `guard expires > now else { throw WayError.returnedToTrail }` (`WayImporter.swift:128`).
- **`now`** is the caller's `Date()`, the device clock. A wrong clock sweeps early or late, and nothing compares against server time.
- **Order and errors.** `list()` order (newest acceptance first), with every removal `try?`. A failed removal is silent, and the next sweep tries again.
- **`touched` includes walked Ways already retired.** An expired, walked Way stays in `list()` and still has `expires <= now`, so every later sweep retires it again (`deleteMedia` on a missing folder is a silent no-op) and returns it again. Every sweep therefore cancels any gather of an expired, walked Way (§13).
- **Unwalked removal uses `removeItem` directly**, not `delete(id:)`. The index isn't rewritten, which is correct because no link names that id.
- **Walked removal keeps the transcripts.** `way.json` keeps every voice moment's `transcript` and `place`, and the sharer's route. The plan's risk table already records this ("Walked shares keep the sharer's transcripts past expiry (iOS behavior)").

iOS pins the table: two expired shares (one linked), one live share, one own walk; the sweep touches exactly the two expired, the unwalked loses `way.json`, the walked keeps it and loses media, the live share keeps media, the own walk survives (`testSweepFollowsTheThreeRowTable`, `UnitTests/Honor/WayStoreTests.swift:56-77@7c200bf`). Media deletion keeps links and replies (`testLinkAndRepliesSurviveMediaDeletion`, `:38-54`).

Android at `636cf5ce`: no sweep. The walked set's source is the per-walk link files (`linkFiles()`, `WayStore.kt:308-312`). Android's delete keeps a deleted walk's link, as iOS's does (plan Key Technical Decisions), so "walked" means the same thing.

### 13. When the sweep runs

Three triggers, and no others (`git grep sweepExpired 7c200bf -- Pilgrim`):

**1. Launch**, detached, at utility priority, once per process:

```swift
    private func runPostDoneLaunchTasks() {
        #if DEBUG
        // Ship-gate harness (Task 10): only ever produces output under
        // `--senses-field-report` — `runIfRequested` carries that guard
        // itself. The Task hop keeps the @MainActor call clean from this
        // non-actor-annotated completion closure.
        Task { @MainActor in DossierSensesFieldReport.runIfRequested() }
        #endif
        startLaunchRecordingCleanup()
        sweepExpiredWays()
        reconcileTilesAtLaunch()
    }
```
> Pilgrim/AppDelegate.swift:163-174@7c200bf

```swift
    /// Skipped under XCTest for the same reason `startLaunchRecordingCleanup`
    /// is: the shared-singleton sweep touches the real Application Support
    /// tree, which would race a unit test writing fixtures into it. Runs
    /// detached because it decodes every Way's `way.json` from disk.
    private func sweepExpiredWays() {
        guard NSClassFromString("XCTestCase") == nil else { return }
        Task.detached(priority: .utility) {
            let swept = WayStore.shared.sweepExpired(now: Date())
            guard !swept.isEmpty else { return }
            // A transfer still in flight for a swept Way would recreate the
            // folder the sweep just removed; the downloader is main-isolated,
            // so the cancel hops back.
            await MainActor.run {
                for id in swept { WayMediaDownloader.shared.cancel(wayId: id) }
            }
        }
    }
```
> Pilgrim/AppDelegate.swift:194-210@7c200bf

`runPostDoneLaunchTasks` runs in `DataManager.setup`'s completion, right after `appLaunchState = .done` (`AppDelegate.swift:98-101`). That covers every launch, including the system's background relaunch to deliver a transfer (§10).

**2. The Ways sheet appears**, on main, before it lists:

```swift
            .onAppear {
                for id in WayStore.shared.sweepExpired(now: Date()) { WayMediaDownloader.shared.cancel(wayId: id) }
                acceptedWays = WayStore.shared.list().filter { if case .share = $0.source { return true } else { return false } }
                withMedia = Set(acceptedWays.filter { WayStore.shared.hasMedia(id: $0.id) }.map(\.id))
            }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:112-116@7c200bf

**3. Settings → Ways loads**, on main, at every `reload()` (on appear, and after each delete):

```swift
    private func reload() {
        for id in WayStore.shared.sweepExpired(now: Date()) { WayMediaDownloader.shared.cancel(wayId: id) }
        let all = WayStore.shared.list()
```
> Pilgrim/Scenes/Settings/WaysListView.swift:95-97@7c200bf

Not a trigger: the Settings **Data card** reads `list()` without sweeping (`DataCard.swift:32-38`, quoted in §15), so its count can include an expired share that no sweep has reached yet in this process. The launch sweep usually has by then. Not a trigger either: an import, a gather, the overview, a walk's start or end, and recovery.

**Threads.** The launch sweep's file removals run on a detached utility task; the two UI sweeps run on main. Every `cancel` runs on main.

**Never during a walk on iOS.** The walk is a full-screen cover over the tab view:

```swift
        .fullScreenCover(item: $coordinator.activeWalkViewModel, onDismiss: {
            coordinator.handleActiveWalkDismiss()
        }) { vm in
            ActiveWalkView(viewModel: vm, onCancel: { coordinator.cancelWalk() })
```
> Pilgrim/Scenes/Root/MainTabView.swift:42-45@7c200bf

The Path tab's Honor button (which opens the sheet) and the Settings tab are both under it, so neither UI sweep can run mid-walk. The process that runs a walk launched before it, so the launch sweep can't either, and iOS never resumes a walk after a kill (own-walk spec A §22). On iOS, then, a sweep never meets a live walk. That is the observable behavior Android must keep, even though Android has a way to reach the case iOS can't: the UI process relaunches mid-walk while `:tracker` walks on (Resolutions 6).

### 14. The sweep and crash recovery: no ordering

The launch sweep and crash recovery start in the same launch with nothing ordering them.

- **The sweep** is dispatched in `runPostDoneLaunchTasks` (§13), the moment `.done` is set.
- **Recovery** starts when `MainTabView` first builds its `@StateObject` coordinator (`MainTabView.swift:11`), whose `init` calls it:

```swift
    init() {
        checkForRecovery()
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:43-44@7c200bf

```swift
    private func checkForRecovery() {
        WalkSessionGuard.recoverIfNeeded { [weak self] date in
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:51-52@7c200bf

- **The link is written last.** Recovery decodes the checkpoint on the calling thread, saves the walk through CoreStore asynchronously, and only in that save's success callback writes the link:

```swift
        DataManager.saveWalk(object: recovered) { success, error, saved in
            if success {
                try? FileManager.default.removeItem(at: url)
                rebindWay(checkpoint: checkpoint, walkUUID: saved?.uuid, store: wayStore)
```
> Pilgrim/Models/Walk/WalkSessionGuard+Recovery.swift:119-122@7c200bf

```swift
    private static func rebindWay(checkpoint: WalkCheckpoint, walkUUID: UUID?, store: WayStore) {
        guard let wayId = checkpoint.wayId, let walkUUID,
              let way = store.load(id: wayId) else { return }
        try? store.link(walkUUID: walkUUID, to: wayId, arrival: nil)
```
> Pilgrim/Models/Walk/WalkSessionGuard+Recovery.swift:138-141@7c200bf

The launch code gates a different sweep on recovery, and explicitly so:

```swift
    /// Launch cleanup ordering (AF2): the orphan sweep runs only once BOTH
    /// path recovery has finished AND any crashed-walk checkpoint has been
    /// recovered (WalkSessionGuard resolves the gate from MainCoordinator).
```
> Pilgrim/AppDelegate.swift:212-214@7c200bf

The Ways sweep has no such gate (`OrphanSweepGate` is never consulted in `sweepExpiredWays`).

**The case that breaks.** A walker accepts a share and begins honoring it before its expiry. The process dies mid-walk (OOM, crash, battery), and the walker relaunches after the expiry.

- The Way is unlinked, because the crashed walk never reached the clean-finish `link` and recovery hasn't run yet.
- If the sweep runs first (likely: it is a detached task dispatched first, while recovery waits for a view to build and for a CoreStore save), the share reads as unwalked and **the whole folder goes**. `rebindWay` then finds no Way and writes no link. The recovered walk keeps its `.honorMode` event and shows "a way that has been removed", with no ghost line, no Way title, and no replies (own-walk spec A §22). Replies filed during that walk are lost with the folder's `replies.json`.
- If recovery's link lands while the sweep is mid-loop, the sweep has already read `walkedIds`. It still removes the folder, now leaving a **dangling link**: the summary reads "a way that has been removed", and the link counts the Way as walked for nothing.
- Only if recovery's link lands before the sweep reads `walkedIds` does iOS do what the table intends (walked: media only).

This is the candidate defect the plan names ("the launch expiry sweep running before crash recovery re-links"), confirmed (D1). The plan's U28 order, "on launch after recovery and the finalize retry", gives Android the intended outcome every time. Android already runs recovery (`runBlocking`) before `HonorFinalizer.runAtLaunch` (`app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt:402-429@636cf5ce`), whose `finalizePending` writes recovered links (`walk/honor/HonorFinalizer.kt:160-162@636cf5ce`). A sweep appended after `finalizePending` therefore sees every link recovery can write. That deterministic order is a dated R5 divergence for the gate, filed upstream with D1 (Resolutions 5).

### 15. What an expired share shows, surface by surface

"Voices returned to the trail" is computed from `hasMedia`, never from `expires`:

```swift
    func hasMedia(id: String) -> Bool {
        guard Self.isValidId(id) else { return false }
        let contents = (try? fileManager.contentsOfDirectory(atPath: mediaDirectory(for: id).path)) ?? []
        return !contents.isEmpty
    }
```
> Pilgrim/Models/Honor/WayStore.swift:144-148@7c200bf

`contentsOfDirectory` lists any entry in `media/`, folders included, and doesn't recurse. So an empty `media/audio/` folder reads as "has media" (§9), and a `media/` folder that was never created reads as "no media", whether the share has expired or its files simply haven't arrived (D3).

**The Ways sheet** ("Choose a way" → "Shared with you"):

```swift
    @ViewBuilder
    private func wayRow(_ way: Way) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(way.title)
                .font(Constants.Typography.body)
                .foregroundColor(.ink)
            HStack {
                Text(WayStageLine.line(for: way)
                     ?? DateFormatter.localizedString(from: way.departedAt, dateStyle: .medium, timeStyle: .none))
                Text("·")
                Text(withMedia.contains(way.id) || way.voiceCount + way.photoCount == 0
                     ? HonorOverviewModel.countsLine(way: way) : "voices returned to the trail")
            }
            .font(Constants.Typography.caption)
            .foregroundColor(.fog)
        }
    }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:125-141@7c200bf

```swift
    static func countsLine(way: Way) -> String {
        var parts: [String] = []
        if way.voiceCount > 0 { parts.append(way.voiceCount == 1 ? "1 voice" : "\(way.voiceCount) voices") }
        if way.photoCount > 0 { parts.append(way.photoCount == 1 ? "1 photo" : "\(way.photoCount) photos") }
        return parts.isEmpty ? "a quiet way" : parts.joined(separator: " · ")
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:7-12@7c200bf

The row is the title, then `<date, medium style> · <counts or "voices returned to the trail">`. The returned line shows when the Way has at least one voice or photo **and** no entry in `media/`. A Way with neither shows its counts line, `"a quiet way"`. The `·` separators are U+00B7, and the row is a button that opens the overview.

**Settings → Data → Ways:**

```swift
    private func detail(for way: Way) -> String {
        let lead = WayStageLine.line(for: way)
            ?? DateFormatter.localizedString(from: way.departedAt, dateStyle: .medium, timeStyle: .none)
        if way.voiceCount + way.photoCount > 0, !WayStore.shared.hasMedia(id: way.id) {
            return "\(lead) · voices returned to the trail"
        }
        let mb = String(format: "%.1f MB", Double(WayStore.shared.diskUsage(id: way.id)) / 1_000_000)
        return "\(lead) · \(mb)"
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:105-113@7c200bf

```swift
    /// The store stays UI-free, so cancelling the transfers that would
    /// otherwise land in a folder nothing can see or remove belongs here, at
    /// the delete site.
    private func delete(id: String) {
        WayMediaDownloader.shared.cancel(wayId: id)
        WayStore.shared.delete(id: id)
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:87-93@7c200bf

The other strings on that screen (title `"Ways"`, empty state `"no ways yet"`, `"Delete all Ways"`, the alert `"Delete all Ways?"` with `"Delete"`/`"Cancel"` and `"Their voices and photos leave this phone. Your own walks are untouched."`) are at `WaysListView.swift:43`, `:59`, `:66-84`, and own-walk spec A §23 quotes them.

- The detail is `<date> · voices returned to the trail`, or `<date> · <N.N> MB`. MB here is decimal (`/ 1_000_000`) with one decimal place.
- `String(format:)` with no locale uses a `.` decimal on iOS. Android needs `Locale.US` (Archetype E), as Settings' other numeric strings already use (for example `String.format(Locale.US, "%.1f km", …)`, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:599@636cf5ce`).
- The list includes own-walk Ways (`listable` drops only package-owned stages, `WaysListView.swift:12-14`). An own walk with a voice or photo therefore always reads "voices returned to the trail", which is filed as [pilgrim-ios #109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109) (own-walk spec A-D4).

**The Data card's row:**

```swift
        .onAppear {
            // Counted the way the list counts: package stages are managed on
            // their route page, and a row that counted them said "5 ways"
            // above a list of one.
            let listed = WaysListModel.listable(WayStore.shared.list())
            waysDetail = WaysListModel.rowDetail(count: listed.count, bytes: WayStore.shared.diskUsage(of: listed))
            reloadMapsDetail()
        }
```
> Pilgrim/Scenes/Settings/SettingsCards/DataCard.swift:32-39@7c200bf

```swift
    static func rowDetail(count: Int, bytes: Int) -> String {
        "\(count) \(count == 1 ? "way" : "ways") · \(String(format: "%.1f MB", Double(bytes) / 1_000_000))"
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:18-20@7c200bf

Pinned: `"1 way · 2.3 MB"`, `"3 ways · 12.0 MB"`, `"0 ways · 0.0 MB"` (`UnitTests/Honor/WaysListModelTests.swift:25-29@7c200bf`). Bytes are `diskUsage` over each listed Way's whole folder: `way.json`, `accepted.json`, `replies.json`, and `media/`.

**The overview**, opened from an expired, walked Way's sheet row. Nothing checks expiry:

- The counts line still reads from the Way (`"3 voices · 2 photos"`), because counts come from `way.json`, not from the files.
- `gather` → `download` finds every file missing and enqueues them all (§11). One of two things follows:
  - The worker has already removed the share (its daily cron at 03:00 UTC). Each file 404s, retries once, and fails, giving `"gathering their voices · N%"`, then `"some voices didn't arrive"` with `"try again"` (which can never succeed) and `"walk without the missing voices"`.
  - The worker or its CDN still serves the files. The media worker routes carry `cacheMaxAge: 86400` (`pilgrim-worker` `src/index.ts:32,36`), and the cron runs only daily. The sharer's voices **download again** past the sharer's expiry, and the sheet row flips back to the counts line until the next sweep (D2).
- Begin is enabled once the gather settles. A walk then plays nothing from the share, or plays the re-downloaded files.

**The overview's moment preview** (a pin tapped on the overview map):

```swift
            } else {
                HStack(spacing: Constants.UI.Padding.small) {
                    Image(systemName: "waveform.slash").foregroundColor(.fog)
                    Text("their voice is still on its way here").font(Constants.Typography.body).foregroundColor(.fog)
                }
            }
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:145-150@7c200bf

For a voice whose file is absent, swept or not yet arrived, the preview shows the transcript (if any), then `waveform.slash` and `"their voice is still on its way here"`, then the "When you walk it…" line (`:151-153`). A swept Way's voice is never "on its way" (D4). A photo with no file shows an empty parchment plate (`WayPlaceCard.swift:290-292`, `WayPhotoPlate`).

**On the walk** (an expired, walked share walked again). Nothing says "returned":

- A reached voice with no file never starts. `startVoice` hands the turn back before `showCard` (`ActiveWalkViewModel+Honor.swift:214-220`), so no voice card rises on its own, and the pin stays unheard (fog).
- A tapped voice pin raises the card: the transcript line, a play button that does nothing (`togglePlayback` returns at its `mediaURL` guard, `ActiveWalkViewModel+Honor.swift:396`), a flat waveform placeholder, and `0:00 / m:ss`. If the walker replied on an earlier honoring, `"your reply"` is offered, because replies survive the sweep.
- A photo card shows the empty parchment plate.
- Rests, sittings, waypoints, the ghost line, the companion, and arrival all work, since they need no media.

**After the walk**, unchanged by the sweep. The summary reads only `way.json`, the link, and `replies.json`:

```swift
        let link = WayStore.shared.wayLink(forWalk: uuid)
        let way = link.flatMap { WayStore.shared.load(id: $0.wayId) }
        let replies = link.map { WayStore.shared.replies(for: $0.wayId) } ?? [:]
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:746-748@7c200bf

```swift
        if stage == nil, let theirs = link?.theirSeconds, let yours = link?.yourSeconds { delta = theirs - yours }
        let arrived = types.contains(.honorArrival)
        return HonorSummaryData(
            wayTitle: way?.title ?? "a way that has been removed",
            arrivedBeforeTheirsSeconds: delta,
            voicesAlongTheWay: way?.voiceCount ?? 0,
            repliesMade: replies.count,
```
> Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift:33-39@7c200bf

So a walked, swept share keeps its title, its delta, its "N voices along the way" (counted from `way.json`, not from files), its reply count, the ghost line (`HonorWayState(way:)`, `WalkSummaryView.swift:753`), the seal's Way line, and the prompt title (`way(forWalk:)`, own-walk spec A §16). A walker's own reply recordings live under Documents and stay playable.

**The full table**:

| Surface | Expired, never walked (after a sweep) | Expired, walked (after a sweep) | Source |
|---|---|---|---|
| Ways sheet, "Shared with you" | absent | `<date> · voices returned to the trail` (when it had media to lose) | `HonorWaysSheet.swift:135-136` |
| Settings → Ways row | absent | `<date> · voices returned to the trail` | `WaysListView.swift:108-110` |
| Settings Data card | not counted | counted; bytes = its JSON files | `DataCard.swift:36-37` |
| A link or paste for it | the importer's `"This walk has returned to the trail"` while the worker serves `tour.json`, else `"couldn't find that walk. Check the link, or it may have returned to the trail."` | the same, and the stored Way is untouched (the importer throws before `save`) | `HonorImportReducer.swift:31-32`, `WayImporter.swift:83-84,128` |
| Overview | can't be reached | reachable; counts unchanged; gathers again (D2) | §11 |
| Overview voice preview | — | `"their voice is still on its way here"` | `WayMomentPreview.swift:148` |
| On-walk place cards | — | no "returned" copy; the voice never starts; the photo plate is empty | `WayPlaceCard.swift:104-110` |
| Summary of the walk that walked it | — (never walked) | unchanged: title, delta, voices count, replies, ghost line | `HonorSummarySection.swift:33-39` |
| Seal, prompts, journal | — | unchanged | own-walk spec A §16 |

**Correction to AE3.** AE3 says a walked, expired share's "place cards read 'voices returned to the trail'". That is the design doc's sentence:

```
A swept Way lists as "voices returned to the trail" and its summary place
cards say the same. The ghost line survives because geometry is the honoring
walker's own record.
```
> docs/superpowers/specs/2026-09-01-honor-mode-design.md:372-374@7c200bf

The shipped app writes the string in exactly two places: the Ways sheet row and the Settings → Ways row (`git grep -n "returned to the trail" 7c200bf -- Pilgrim` finds `HonorWaysSheet.swift:136`, `WaysListView.swift:109`, the two import-failure lines, and the unrelated share-card copy). No place card, preview, or summary shows it. Under R4 (shipped Swift outranks iOS design docs), AE3's place-card clause is wrong. What AE3 can test is the list rows, plus the summary keeping its ghost line and replies. The plan's U28 test "an expired, walked one keeps its line and replies" stands.

### 16. Edge cases

| Case | iOS behavior | Where |
|---|---|---|
| A file 404s | delivered as a body with status 404 → `finish(success: false)` → one immediate retry → 404 again → `failures += path`. Progress still reaches 100%; the overview reads `"some voices didn't arrive"` | §3, §7 |
| A 5xx or another non-2xx | the same as a 404 | `:285` |
| A transport error (offline, TLS, timeout) | one immediate retry, then failure. Offline, the retry fails the same way, so a gather started offline ends in `.mediaMissing` at once (or once the system gives up) | `:316` |
| The server sends the wrong type (HTML, JSON, an image as `.m4a`) | **accepted** if 2xx and within the cap; it lands at the final path, counts as present on every later gather, and never re-downloads. A voice then fails at the player; a photo plate stays empty (`UIImage(data:)` nil) | §3 (D6) |
| A 200 with an empty body | accepted (`size` 0 ≤ cap); lands as a 0-byte file | `:258`, `:286` |
| The server sends more than the cap | cancelled once `totalBytesWritten > cap` → `.cancelled` → failure, **no retry** | `:304`, `:316` |
| The cap is crossed only on the last write, or the task predates this process | caught after the fact by `size <= cap` in `deliver` → failure with one retry (in-process) or silently dropped (relaunch) | `:285-288` |
| A declared `Content-Length` over the cap | not checked; the stream is cut when bytes cross the cap | `:300-305` |
| A redirect | followed to any host; status and cap apply to the final response | §2 |
| More than 12 audio or 20 photo files declared | the excess is refused before any enqueue and reported as failures (`.mediaMissing`); "try again" refuses it again | §3 |
| The same path named twice | fetched once | §2 |
| The device fills mid-download | that file fails as disk full, with no retry; the overview shows `"not enough space on this phone to save these voices"` at once; other files continue; landed files stay; Begin is enabled; no "try again" button | §8 |
| The Way is deleted mid-download (Settings → Ways) | `cancel(wayId:)` first: bookkeeping cleared, then tasks cancelled asynchronously. A delivery that slips past the cancel hits the guard (`load` nil) and is dropped; the folder isn't recreated | §9 |
| The Way is swept whole mid-download | the same as a delete (the sweep cancels after it removes) | §13 |
| A walked Way is swept (media only) mid-download | `load` still succeeds, so a delivery landing before the cancel, or from a previous process that `cancel` can't reach, **recreates `media/`** and lands its file, until the next sweep removes it again | §9, §10 (D7) |
| The app is suspended or system-killed mid-download | transfers continue; deliveries after a relaunch land through `entry(from:)` with the folder cap; nothing is recorded; the progress UI is lost until the overview is reopened | §10 |
| The app is force-quit mid-download | transfers cancelled by the system; nothing resumes until the overview is reopened; the partial bytes are gone | §10 |
| Two gathers of one Way in one process | the second is a no-op (`active` guard) | `:124` |
| Two gathers across a relaunch | both may fetch the same file; the second move replaces the first; correct, but double the bytes | §10 |
| "try again" while a gather is still active | `cancel` then `download`: unconditional (pinned) | `:146-153` |
| The overview reopened while a disk-full gather's other files still run | `download` returns at the `active` guard **before** clearing `diskFull`, so the overview still reads disk full; it clears only after the round ends and the overview is reopened | `:124`, `:131` |
| The overview closed mid-gather | transfers continue; only the observation is dropped | §11 |
| A walk begun mid-gather | transfers continue; a file landing mid-walk plays when its spot is reached | `MainCoordinatorView.swift:81-84` |
| The share expires mid-walk | nothing happens during the walk (no sweep can run, §13); at a clean finish the walk is linked, so the next sweep removes media only | §12–§13 |
| The share expires, then a crash-recovered walk of it relaunches | sweep and recovery race; usually the whole folder goes and the walk loses its Way (D1) | §14 |
| A walked, expired Way reopened from the sheet | gathers again, with no expiry check (D2) | §15 |
| A share's media never arrived, with the share live | the sheet and Settings rows read `"voices returned to the trail"` (D3) | §15 |
| Re-import (link or paste) of an accepted, live share | the importer `save`s: `way.json` rewritten, first `accepted.json` kept, `media/` untouched; the overview's gather enqueues only what's missing | `WayImporter.swift:84`, §6 |
| Re-import of an accepted share now expired | the importer throws `returnedToTrail` (or `notFound` once the worker has removed it) before `save`; the stored Way is untouched; a never-walked one is removed by the next sweep | `WayImporter.swift:128` |
| A clean walk end on a shared Way | `save(way)` rewrites `way.json` from the in-memory Way and keeps `accepted.json`; `link` then makes it walked | `MainCoordinatorView.swift:117-120` |
| The device clock is set ahead | expiry sweeps run early (the device's `Date()`, no server time) | §12 |

### 17. Android counterparts at `636cf5ce`

| iOS | Android today | Gap for U28 |
|---|---|---|
| `WayStore` media helpers (`mediaDirectory`, `mediaURL`, `hasMedia`, `deleteMedia`, `diskUsage`) | ported: `WayStore.kt:128-157` (with `mediaFile`'s canonical containment check, `:135-139`) | none; `hasMedia`'s non-recursive "any entry" semantics already match (`:141-144`) |
| `walkedIds` (the index's values) | per-walk link files, `linkFiles()` `WayStore.kt:308-312` | a walked-set read over the link files, plus the Android-only live-session and Begin exclusions (Resolutions 6) |
| `sweepExpired`, `retire` | none | new; runs from the UI process |
| `retireMany` | none | Stage 21-2 |
| `WayMediaDownloader` | none | `P/data/honor/WayMediaDownloadWorker.kt` + scheduler (plan U28) |
| Background session, relaunch delivery | WorkManager precedents: `VoiceGuideDownloadScheduler.kt:64-78` (CONNECTED, unique work, KEEP/REPLACE), `WhisperModelDownloadWorker.kt:95-110` (Range + If-Range, 206 appends, 200 restarts, 416 discards) and `:168-177` (StatFs precheck, terminal storage) | reuse the shapes; drop `setRequiresStorageNotLow` and `UNMETERED` (§5) |
| Launch sweep (`AppDelegate.sweepExpiredWays`) | the launch hook exists: `PilgrimApp.kt:426-429` → `HonorFinalizer.runAtLaunch` (`walk/honor/HonorFinalizer.kt:100-109`), which finalizes pending walks, then sweeps staging | append the expiry sweep after `finalizePending` (Resolutions 5) |
| Settings → Ways (`WaysListView`, `DataCard` row) | `ui/settings/data/DataCard.kt` has no Ways row | new screen and row (plan U28) |

### Resolutions for the plan

1. **Layout (U28; confirms own-walk spec A §12).** A shared Way is `share:<id>/` with `way.json`, `accepted.json`, `replies.json`, and `media/audio/<n>.m4a` and `media/photos/<n>.jpg` (§1).
   - Import writes no `media/`; the first delivered file creates it.
   - `accepted.json` is the first import's time, kept by re-imports and by the clean walk-end re-save, so a shared Way keeps its place in the list.
   - Android's `WayStore.kt` already has this shape and the media helpers, so U28 adds no store fields. A media temp file must sit where a launch sweep can find it (§9).
2. **Downloader bounds, bound for bound (U28)** (§2–§4):
   - the path pattern `\A(?:audio/[0-9]{1,5}\.m4a|photos/[0-9]{1,5}\.jpg)\z`;
   - files deduplicated, in moment order;
   - per-file caps of 15,728,640 (`audio/` prefix) and 2,097,152 bytes (everything else), refused only when strictly greater, counting a resumed partial;
   - per-Way ceilings of 12 audio and 20 photos over the declared, deduplicated list, with the excess reported as failures;
   - any 2xx accepted.
   No share total: the plan's "unenforced, matching iOS" is right, and the worst case is 220 MiB. No content check (D6), and "missing" means no file at the final path. Each bound gets a rejecting test, as U28 already plans.
3. **Hardening, recorded at the gate (R6), not parity:**
   - refusing a redirect to another host for media (iOS follows any redirect, §2);
   - refusing on a declared `Content-Length` over the cap before reading (iOS cuts the stream at the cap: the same bound, reached earlier, §3);
   - the per-delivery re-check of the path shape on the forward path (iOS checks it only on a relaunch rebuild, §2).
   None changes what a genuine share does.
4. **Retry, failure, and network (U28)** (§5, §7–§8):
   - One retry per file per gather round, immediately, inside the worker. Don't use `Result.retry()` with WorkManager's backoff, which delays the overview's `.mediaMissing`.
   - A retry reloads the Way and is skipped if it's gone. A byte-cap cancel is never retried.
   - Disk full is terminal per file only; other files continue.
   - The overview's precedence is disk full, then gathering, then missing, then ready, with iOS's three strings. Disk full offers no buttons and leaves Begin enabled.
   - Constraints: `NetworkType.CONNECTED`; no `setRequiresStorageNotLow`; no expedited work; no battery constraint. The Robolectric builder test the plan lists covers the `.build()` call.
   - Unique work per Way: `KEEP` for a gather, `REPLACE` for "try again".
   - Progress is `1 − unfinished/accepted`, failures counted as done, seeded from the files on disk.
5. **Launch sweep order (U28) is an R5 divergence.** iOS runs the launch sweep with nothing ordering it against recovery, so a crash-recovered honoring of a just-expired share usually loses its Way (§14, D1). Android runs it after recovery and after `HonorFinalizer.finalizePending`, at the end of `runAtLaunch`. That is the plan's order, and the outcome iOS's own rule intends. Record it at the gate as a dated divergence, file D1, and fold in iOS's fix when it lands. The other two triggers match iOS: the Ways sheet appearing, and Settings → Ways loading (and after each delete). The Data card doesn't sweep. Every sweep cancels the gather of every id it touched, including walked Ways it already retired.
6. **A sweep must never touch a Way a live walk is honoring.** iOS can't sweep during a walk at all: the walk's full-screen cover hides both UI triggers, and the launch sweep can't coincide with a live walk (§13). The plan's "the walked set includes live sessions and Begins in flight" would class such a Way as walked and delete its media mid-walk: the voices ahead would go silent, which iOS never does.
   - Instead, skip any Way named by a live Honor session row or a Begin in flight, entirely: neither its folder nor its media goes.
   - After finalize, its link makes it walked, and the next sweep removes media only. That reproduces iOS's observable outcome.
   - The U28 test "a Way linked to a live walk survives a mid-walk sweep" should assert that its media survives too.
7. **AE3's place-card clause is wrong (R4).** The shipped app shows "voices returned to the trail" only on the Ways sheet row and the Settings → Ways row (§15). Place cards, previews, and summaries never do. The clause comes from the design doc (`honor-mode-design.md:372-373`). AE3 should test:
   - the two rows;
   - the summary keeping its title, delta, voice count, replies, and ghost line;
   - a never-walked expired share being absent from both lists.
   Correct the requirements line.
8. **What the overview says about missing media (U28).** iOS shows one line, "some voices didn't arrive", with "try again" and "walk without the missing voices". The voice preview says "their voice is still on its way here" for any voice whose file is absent. The plan's "a partial gather shows which voices are missing" has no iOS counterpart beyond that preview line, and the greyed pin and "this voice didn't arrive." card in the design doc never shipped (§11).
9. **The disk-full test (U28).** "Partial files removed" holds only for the interrupted file. Files that already landed stay, and the walker can Begin with them (§8).
10. **Settings → Ways strings and formats (U28)** (§15):
    - row details `"<date> · voices returned to the trail"` or `"<date> · N.N MB"`, and the Data card's `"N way(s) · N.N MB"`;
    - decimal MB (`/ 1_000_000`) with one decimal, formatted with `Locale.US`;
    - every other screen string as in own-walk spec A §23.
    Hiding the screen during a walk is an R6 platform equivalent of iOS's full-screen cover, not a divergence.
11. **Re-gathering an expired, walked share (D2).** iOS's `gather` has no expiry check. Under the upstream-first rule Android matches it and files D2, but this one fetches a sharer's voices past their stated expiry whenever the worker still serves them, so the owner should confirm (Open question 1).
12. **Logging.** Relative paths carry only an index, but the share id is in every URL and Way id, so the worker logs neither (plan logging rule).

### iOS defects found

Filed on 2026-10-01 as listed in "Matched as shipped, and filed upstream" at the top of this spec. Before that, no open pilgrim-ios issue covered them ([#107](https://github.com/walktalkmeditate/pilgrim-ios/issues/107) covers own-walk crash links; [#109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109) covers the own-walk "returned" row).

**D1. The launch expiry sweep races crash recovery.** `sweepExpiredWays` starts a detached task as soon as `.done` is set (`AppDelegate.swift:172`, `:200-201`). Recovery links a crashed honor walk only after an asynchronous walk save (`WalkSessionGuard+Recovery.swift:119-122`, `:138-141`). Nothing orders the two, though the same file gates the recording sweep on recovery (`AppDelegate.swift:212-214`).

- **Impact:** a walker whose phone died mid-honoring and who relaunches after the share expired usually loses the Way whole: no ghost line, no title, no replies ("a way that has been removed"). If the link lands mid-sweep, a dangling link is left behind.
- **To confirm:** a test with an unlinked expired share, a checkpoint naming it, and the sweep run before `rebindWay`.

**D2. An expired, walked share gathers its media again.**

```swift
            guard case .share = way.source else { self.honorImportState = .ready; return }
            let downloader = WayMediaDownloader.shared
            downloader.download(way)
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:270-272@7c200bf

- The sheet lists the swept Way as "voices returned to the trail", and tapping it opens the overview, whose gather re-enqueues every file.
- While the worker or its CDN still serves them (daily cleanup at 03:00 UTC, media `cacheMaxAge: 86400`), the sharer's voices download again past their expiry. Afterwards, every file 404s into "some voices didn't arrive", and its "try again" can never succeed.

**D3. "Voices returned to the trail" reads `hasMedia`, not expiry.** `HonorWaysSheet.swift:135-136` and `WaysListView.swift:108-110` show it for any Way with no entry in `media/`. That includes a live share whose files never arrived: all 404s, refused, a force-quit before the first file, or a gather never opened. An empty `media/audio/` left by a failed move reads as "has media" (§9). The own-walk half is [#109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109).

**D4. The preview promises a swept voice is coming.** `WayMomentPreview.swift:148` says "their voice is still on its way here" for any absent file, including one the sweep removed because the share returned to the trail.

**D5. No share total on receipt.** The design doc says the downloader's sizes are bounded at "60 MB total" (`honor-mode-design.md:356-357`), and the sharing side enforces 60 MiB (`TourBuilder.swift:30`). The downloader enforces only per-file caps and per-kind counts, so its worst case is 220 MiB per share (`WayMediaDownloader.swift:73-76`). The plan says this is "filed upstream"; no such issue exists yet.

**D6. Any 2xx body becomes the file, and a present file is never fetched again.** `deliver` checks only the status and size (`WayMediaDownloader.swift:285-286`), and `download` treats any file at the final path as present (`:129`). An HTML error page or an empty 200 lands as `audio/<n>.m4a`. That voice then fails at the player on every walk, and no gather replaces it, because only deleting the Way clears it.

**D7. A swept walked Way's media can come back.** `cancel(wayId:)` reaches only this process's tasks (`:156`), and the deleted-Way guard checks `way.json`, which a media-only retire keeps (`:278`). A transfer from a previous process, or one landing before the launch sweep's cancel hop (`AppDelegate.swift:206-208`), recreates `media/` and lands its file until the next sweep. Those relaunch-era tasks also have no streaming cap (`:303`). Low impact: after the worker's cleanup such a transfer would 404.

**D8. Files over the ceilings read as "some voices didn't arrive" with a "try again" that can't fetch them.** Refused paths are stored as failures (`:130`), and `retry` refuses them again. Only a manifest the worker would never produce reaches this. Low.

**D9. Disk-full has no way forward on the overview.** It renders no button (`HonorOverviewView.swift:320`), and while other files of the round still run, reopening the overview keeps the stale state, because `download` returns at the active guard before `diskFull.remove` (`WayMediaDownloader.swift:124`, `:131`). Begin works but nothing says so. Low.

**D10. The Data card counts expired Ways nobody has swept.** `DataCard.swift:36-37` lists without sweeping. Usually invisible, because the launch sweep has run first. Low.

### Open questions

1. **Owner: re-gathering an expired, walked share (D2).** Match iOS as shipped (the upstream-first rule) and file D2? That lets Android re-download a sharer's voices past their expiry for as long as the worker serves them. Or refuse a gather once `expires <= now`, recorded as a divergence? Matching keeps parity. Refusing keeps the sharer's promise.
2. **Does the worker serve an expired share's media before its 03:00 UTC cleanup?** It decides whether D2 re-downloads or dead-ends. Both answers come from outside the pin (`pilgrim-worker` `src/handlers/expiry.ts:37-61`, `src/index.ts:32,36`): media routes are a plain R2 get with a one-day cache. One device check settles it, after the share expires and before the cron runs.
3. **Resuming after a force-stop.** WorkManager resumes a gather at the next app start after a force-stop. iOS's background session does not resume after a force-quit; the next overview open re-enqueues instead. Recommend treating automatic resumption as an R6 platform equivalent. The walker-visible difference is only that media can arrive without reopening the overview, which iOS also does for transfers the system carried.
4. **Timeouts.** iOS sets none on the media session, so the background session's platform defaults apply. Android's OkHttp defaults have no iOS number to match. Recommend recording them as platform defaults rather than inventing a parity value.
5. **Where `failures` and `diskFull` live on Android.** iOS holds them in memory and recomputes them at the next gather after a relaunch: refused files at once, fetch failures after the retry. Android can match by recomputing at each gather (file presence, plus a re-run of the missing files) instead of persisting the last round's failures. Persisting them would show "some voices didn't arrive" on a reopened overview before any fetch, which iOS never does after a relaunch.

---

## S4. Shared walks on screen: the Ways list, the overview while gathering, and the shared branches

iOS pin: `pilgrim-ios` @ `7c200bf` (v2.0.0). Android HEAD: `636cf5ce`. Feeds U28 (Settings → Ways, the Ways sheet's shared sections, the overview's gathering states) and the shared-walk branches of U21–U23.

Read in full at the pin: `Pilgrim/Scenes/Settings/WaysListView.swift`, `Pilgrim/Scenes/Settings/SettingsCards/DataCard.swift`, `Pilgrim/Scenes/Honor/HonorWaysSheet.swift`, `Pilgrim/Scenes/Honor/HonorOverviewView.swift`, `Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift`, `Pilgrim/Scenes/WalkSummary/HonorSummarySection.swift`, `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift`, `Pilgrim/Models/Honor/HonorImportReducer.swift`, `Pilgrim/Models/Honor/HonorLink.swift`, `Pilgrim/Models/Honor/WayMediaDownloader.swift`, `Pilgrim/Scenes/Honor/WayMomentPreview.swift`, `Pilgrim/Scenes/Honor/WayMomentHeader.swift`, `Pilgrim/Scenes/Root/MainTabView.swift`.

Read in part, for the behavior these surfaces depend on: `Pilgrim/Scenes/Root/MainCoordinatorView.swift` (Honor routing), `Pilgrim/Models/Honor/WayImporter.swift` (which fields a shared Way carries), `Pilgrim/Models/Honor/TourManifest.swift`, `Pilgrim/Models/Honor/WayStore.swift`, `Pilgrim/Models/Honor/Way.swift`, `Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift` (the card host), `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift`, `Pilgrim/Scenes/ActiveWalk/MeditationView.swift` (the sitting caption), `Pilgrim/Models/Prompt/PromptAssembler.swift`, `Pilgrim/Models/Prompt/ActivityContext.swift`, `Pilgrim/Scenes/Prompts/PromptListView.swift`, `Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift` (`computeHonorState`), `Pilgrim/Models/Seal/SealInput.swift`, `Pilgrim/Scenes/Settings/SettingsView.swift`, `Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift`, `Pilgrim/Extensions/FileManager.swift`, `Pilgrim/Extensions/URL.swift`, `Pilgrim/AppDelegate.swift` (the launch sweep), and the tests `UnitTests/Honor/WaysListModelTests.swift`, `HonorImportReducerTests.swift`, `MainCoordinatorHonorTests.swift`.

All four lenses were applied to every file: behavior (states, transitions, threads, timing), UI and visual (layout, type, colour, every string), data (what is read from the store and when), and edge cases (thresholds, boundaries, misfires).

Cross-references: "own-walk spec F §4" means section 4 of cluster F in `docs/parity/2026-09-29-honor-own-walk-port.md`. Tokens (`Constants.UI.Padding.*`, `Constants.UI.CornerRadius.*`, `Constants.Typography.*`) resolve as in own-walk spec F §1: padding xs 4, small 8, normal 16, big 24; corner radius normal 12; `heading` Cormorant SemiBold 17, `body` Cormorant Regular 17, `button` Lato Bold 17, `caption` Lato Regular 12, `displayMedium` Cormorant Light 28.

**Localization.** Every string in this cluster is a Swift literal. None goes through `LS[...]`, `NSLocalizedString`, or `Localizable.strings`, so there are no localization keys to carry. The one exception the cluster touches is the arrival waypoint label `"Walked their way: %@"` (key `honor.arrival.label`, `Pilgrim/Models/Honor/HonorPersistence.swift:43@7c200bf`, own-walk spec A §17). Android puts each literal in `strings.xml` as usual; the English text must match character for character, including the `…` (U+2026) and `·` (U+00B7) characters.

### 1. What a shared Way carries that an own walk doesn't

Every visible difference between a shared Way and an own walk comes from the data. No view branches on `way.source` except three: the Ways sheet's filter (§6), `gather` (§8), and the media downloader. The importer decides what a shared Way holds:

```swift
        for e in m.encounters {
            let at: WayCoordinate?
            if let lat = e.lat, let lon = e.lon {
                at = WayCoordinate(lat: lat, lon: lon)
            } else {
                at = nil
            }
            switch e.type {
            case "voice", "ambience":
                guard let n = e.n else { continue }
                voiceN += 1
                moments.append(WayMoment(id: "voice-\(voiceN)", frac: e.frac, at: at,
                    kind: .voice(endFrac: e.end_frac ?? e.frac, duration: e.duration ?? 0,
                                 kind: e.type == "voice" ? .spoken : .ambient, media: .file("audio/\(n).m4a")),
                    place: trimmedPlace(e.place), transcript: WayMoment.trimmedTranscript(e.transcript)))
            case "photo":
                guard let n = e.n else { continue }
                photoN += 1
                moments.append(WayMoment(id: "photo-\(photoN)", frac: e.frac, at: at, kind: .photo(media: .file("photos/\(n).jpg"))))
            case "waypoint":
                waypointN += 1
                moments.append(WayMoment(id: "waypoint-\(waypointN)", frac: e.frac, at: at,
                    kind: .waypoint(label: capped(e.label, maxLabelCharacters), icon: capped(e.icon, maxIconCharacters, or: "mappin"))))
            case "rest":
                restN += 1
                moments.append(WayMoment(id: "rest-\(restN)", frac: e.frac, at: at, kind: .rest(minutes: e.minutes ?? 0)))
            default:
                continue
            }
        }
        for (index, sit) in m.meditation.enumerated() {
            let minutes: Int
            let isEstimate: Bool
            if let seconds = sit.duration {
                minutes = Int((seconds / 60).rounded()); isEstimate = false
            } else {
                minutes = Int((gapSeconds(around: sit.start_frac, geometry: geometry) / 60).rounded()); isEstimate = true
            }
            moments.append(WayMoment(id: "sit-\(index + 1)", frac: sit.start_frac, at: nil,
                                     kind: .meditation(minutes: minutes, isEstimate: isEstimate)))
        }
```
> Pilgrim/Models/Honor/WayImporter.swift:138-178@7c200bf

```swift
        let wayTitle = title(placeStart: m.place_start, placeEnd: m.place_end, departed: departed)
        return Way(
            id: "share:\(shareId)",
            source: .share(id: shareId, pageURL: baseURL.appendingPathComponent(shareId)),
            title: wayTitle, departedAt: departed, tzIdentifier: m.tz_identifier, expires: expires,
            route: route, totalDistanceMeters: geometry.totalMeters,
            theirActiveSeconds: m.stats?.active_duration ?? geometry.totalSeconds,
            moments: moments,
            weather: m.weather_condition.map { WayWeather(condition: capped($0, maxWeatherConditionCharacters), temperatureC: m.weather_temperature) },
            spans: spans)
```
> Pilgrim/Models/Honor/WayImporter.swift:184-193@7c200bf

```swift
    private static func title(placeStart: String?, placeEnd: String?, departed: Date) -> String {
        let places = [placeStart, placeEnd].compactMap { place -> String? in
            guard let trimmed = place?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
            return String(trimmed.prefix(maxTitlePlaceCharacters))
        }
        return places.isEmpty ? DateFormatter.localizedString(from: departed, dateStyle: .medium, timeStyle: .none)
            : places.joined(separator: " → ")
    }
```
> Pilgrim/Models/Honor/WayImporter.swift:233-240@7c200bf

What follows for the screens in this cluster:

| Field | Shared Way | Own walk (own-walk spec A) | Where it shows |
|---|---|---|---|
| `title` | `"<start place> → <end place>"`, one place alone, or the departure date in medium style (device zone, frozen at import) | the intention, else the medium start date | everywhere a title shows |
| `moment.place` | the worker's street name on a **voice** only, trimmed, capped at 80 | never set | card subline (§10), preview subline (§9.3) |
| `.meditation(isEstimate:)` | `true` when the manifest's sitting has no `duration` (shares made before the worker's 2026-09-03 deploy) | always `false` | card kicker (§10) |
| media | `.file("audio/<n>.m4a")`, `.file("photos/<n>.jpg")` under the Way's `media/` folder, present only once downloaded | `.recording(...)`, `.photoAsset(...)` | every player and photo plate |
| waypoint `label` | `""` when the encounter has none; `icon` falls back to `"mappin"` | always set | card kicker |
| rest `minutes` | `0` when the encounter has none | from the pause | kicker `"they rested here 0 minutes"` |
| voice `duration` | `0` when the encounter has none | from the recording | card clock `"0:00 / 0:00"` |
| `expires` | the manifest's expiry | `nil` | never shown (§6.4) |
| `tzIdentifier` | the manifest's `tz_identifier` | the phone's zone at build | preview hour only (§9.3) |
| `theirActiveSeconds` | `stats.active_duration`, else the route's wall-clock span | the walk's active time | overview duration |
| `totalDistanceMeters` | length of the manifest's route (the sharer's page downsamples it) | full-resolution route | overview, Ways sheet row (none), picker (none) |

There is no sharer name anywhere. The manifest carries none:

```swift
    let v: Int
    let place_start: String?
    let place_end: String?
    let weather_condition: String?
    let weather_temperature: Double?
    let start_date: String
    let tz_identifier: String?
    let expires: String
    let route: [RoutePoint]
    let encounters: [Encounter]
    let meditation: [Sitting]
    let activity_segments: [ActivitySegment]?
    let stats: Stats?
```
> Pilgrim/Models/Honor/TourManifest.swift:64-76@7c200bf

So no surface shows a "by <sharer>" line. Every Honor string says "they" and "their" of an unnamed walker. `Way.source`'s `pageURL` is stored and never displayed (`git grep -n pageURL 7c200bf -- Pilgrim/Scenes Pilgrim/Views` finds nothing). `expires` is never displayed either (`git grep -n expires 7c200bf -- Pilgrim/Scenes Pilgrim/Views` finds only a whisper-cache field).

Android at `636cf5ce`: the model already carries every field above (`WaySource.Share(id, pageUrl)`, `WayMoment.place`, `.transcript`, `Meditation(minutes, isEstimate)`, `Way.expires`, `Way.tzIdentifier`: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/honor/Way.kt:93-108,234-237,273-279@636cf5ce`). There is no importer yet (U28).

### 2. Settings → Data card: the "Ways" row

#### 2.1 Where it is

The row lives on the Settings screen's Data card, between "Export & Import" and "Maps". It is not inside the Export & Import screen: `Pilgrim/Scenes/Settings/DataSettingsView.swift` has no Way code at the pin (`git grep -n -i way` on it finds nothing).

```swift
struct DataCard: View {

    @ObservedObject private var tiles = PilgrimageTilesManager.shared
    @State private var waysDetail: String = ""
    @State private var mapsDetail: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
            cardHeader(title: "Data", subtitle: "Your walk archive")

            NavigationLink {
                DataSettingsView()
            } label: {
                settingNavRow(label: "Export & Import")
            }

            NavigationLink {
                WaysListView()
            } label: {
                settingNavRow(label: "Ways", detail: waysDetail)
            }

            NavigationLink {
                OfflineMapsView()
            } label: {
                settingNavRow(label: "Maps", detail: mapsDetail)
            }
        }
        .settingsCard()
        .onAppear {
            // Counted the way the list counts: package stages are managed on
            // their route page, and a row that counted them said "5 ways"
            // above a list of one.
            let listed = WaysListModel.listable(WayStore.shared.list())
            waysDetail = WaysListModel.rowDetail(count: listed.count, bytes: WayStore.shared.diskUsage(of: listed))
            reloadMapsDetail()
        }
```
> Pilgrim/Scenes/Settings/SettingsCards/DataCard.swift:3-39@7c200bf

- Order: header `"Data"` / `"Your walk archive"`, then `"Export & Import"`, `"Ways"`, `"Maps"`. The `"Maps"` row is Stage 21-3 (offline maps).
- The `"Ways"` row is always shown, with or without Ways, and with or without a walk (Settings is unreachable during a walk on iOS: the walk is a full-screen cover over the whole tab view, `MainTabView.swift:42-45@7c200bf`).
- The detail is computed on appear, on the main thread: one directory listing, one `way.json` decode per Way, and one size walk per Way. It starts as `""`, so the first frame shows an empty detail.
- The card does not sweep expired Ways before counting. The list does (§3.3). A share that expires during the session, unwalked, is counted on the card and gone from the list. The launch sweep (`AppDelegate.swift:198-210@7c200bf`) covers expiries before launch.

The row's layout is `settingNavRow` with a detail: label body/ink, `Spacer`, detail caption/fog scaled down to 0.7 on one line, chevron caption/fog.

```swift
func settingNavRow(label: String, detail: String? = nil) -> some View {
    HStack {
        Text(label)
            .font(Constants.Typography.body)
            .foregroundColor(.ink)
        Spacer()
        if let detail {
            Text(detail)
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
                .minimumScaleFactor(0.7)
                .lineLimit(1)
        }
        Image(systemName: "chevron.right")
            .font(Constants.Typography.caption)
            .foregroundColor(.fog)
    }
}
```
> Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:74-91@7c200bf

The Data card sits sixth on the Settings screen: summary header, Practice, Atmosphere, Voice, Permissions, **Data**, Connect, About (`Pilgrim/Scenes/Settings/SettingsView.swift:21-41@7c200bf`, entrance delay 0.5 s).

#### 2.2 The detail string

```swift
    /// The Data card's row, over the Ways the list shows: counted over the
    /// whole store it said "5 ways" above a list of one.
    static func rowDetail(count: Int, bytes: Int) -> String {
        "\(count) \(count == 1 ? "way" : "ways") · \(String(format: "%.1f MB", Double(bytes) / 1_000_000))"
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:16-20@7c200bf

```swift
    func testTheRowCountsAndSizesTheWaysItIsGiven() {
        XCTAssertEqual(WaysListModel.rowDetail(count: 1, bytes: 2_340_000), "1 way · 2.3 MB")
        XCTAssertEqual(WaysListModel.rowDetail(count: 3, bytes: 12_000_000), "3 ways · 12.0 MB")
        XCTAssertEqual(WaysListModel.rowDetail(count: 0, bytes: 0), "0 ways · 0.0 MB")
    }
```
> UnitTests/Honor/WaysListModelTests.swift:25-29@7c200bf

- Singular only at exactly 1: `"1 way"`, `"0 ways"`, `"2 ways"`.
- Decimal megabytes (divide by 1,000,000), one decimal, `.` separator whatever the locale (`String(format:)` with no locale). Android pins `Locale.US`.
- Counted over `listable`, which keeps own-walk and shared Ways and drops only pilgrimage stages:

```swift
    static func listable(_ ways: [Way]) -> [Way] {
        ways.filter { !$0.source.isPackageOwned }
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:12-14@7c200bf

```swift
        XCTAssertEqual(WaysListModel.listable([shared] + stages + [own]).map(\.id), [shared.id, own.id])
```
> UnitTests/Honor/WaysListModelTests.swift:22@7c200bf

So every own walk that was honored at least once counts as a Way here (its folder exists from the first clean finish, own-walk spec A §20).

#### 2.3 What "bytes" means: allocated size

`diskUsage(id:)` sums `sizeOfDirectory`, which adds each file's **allocated** size, not its length:

```swift
    public func sizeOfDirectory(at url: URL) -> Int? {
        
        guard let enumerator = self.enumerator(at: url, includingPropertiesForKeys: [], options: [], errorHandler: { (_, error) -> Bool in
            print(error)
            return false
        }) else {
            return nil
        }
        
        var size = 0
        
        for case let url as URL in enumerator {
            size += url.fileSize ?? 0
        }
        
        return size
        
    }
```
> Pilgrim/Extensions/FileManager.swift:31-48@7c200bf

```swift
    var fileSize: Int? {
        
        do {
            
            let file = try self.resourceValues(forKeys: [.totalFileAllocatedSizeKey, .fileAllocatedSizeKey])
            return file.totalFileAllocatedSize ?? file.fileAllocatedSize
            
        } catch {
```
> Pilgrim/Extensions/URL.swift:27-34@7c200bf

- Each small JSON file costs one filesystem block (typically 4 KB). An own-walk Way (three small files) is about 12 KB, so it reads `"0.0 MB"`.
- The enumerator also visits the `media/`, `media/audio/`, and `media/photos/` directory entries. On APFS a directory reports no allocated size of its own, so in practice this adds nothing (not checked on a device).
- Android's `WayStore.diskUsage` sums `File.length()` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/honor/WayStore.kt:151-157@636cf5ce`). That is the logical size, so it runs up to one block per file below iOS. A full share (12 voices, 20 photos, 3 JSON files) can be about 140 KB lower, which is enough to flip the first decimal. It is a small difference, and the gate should record it either way (Open question 1).

#### 2.4 Android at `636cf5ce`

`DataCard` has one row, `"Export & Import"` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/DataCard.kt:15-31@636cf5ce`; strings `settings_data_title` "Data", `settings_data_subtitle` "Your walk archive", `settings_data_export_import` "Export &amp; Import", `app/src/main/res/values/strings.xml:921-923@636cf5ce`). The Ways row is inserted after it.

`SettingNavRow` already takes `detail` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsCardStyle.kt:236@636cf5ce`). It caps the detail at `widthIn(max = 120.dp)` and ellipsizes it (`:285`), where iOS shrinks it to 0.7 and never truncates. `"12 ways · 123.4 MB"` should fit in 120 dp at 12 sp, so the difference only matters at large font scales.

### 3. Settings → Ways: the screen

#### 3.1 Layout, top to bottom

```swift
    var body: some View {
        List {
            if ways.isEmpty {
                Text("no ways yet").font(Constants.Typography.caption).foregroundColor(.fog)
            }
            ForEach(ways, id: \.id) { way in
                VStack(alignment: .leading, spacing: 2) {
                    Text(way.title).font(Constants.Typography.body).foregroundColor(.ink)
                    Text(details[way.id] ?? "").font(Constants.Typography.caption).foregroundColor(.fog)
                }
            }
            .onDelete { offsets in
                // A walk that already followed this Way keeps its own route and
                // moments; only the shareable Way folder goes away, so the
                // walk's summary falls back to "a way that has been removed".
                for index in offsets { delete(id: ways[index].id) }
                reload()
            }
            if !ways.isEmpty {
                Button("Delete all Ways", role: .destructive) { confirmDeleteAll = true }
                    .font(Constants.Typography.button)
            }
            if let packageFooter {
                Text(packageFooter).font(Constants.Typography.caption).foregroundColor(.fog)
            }
        }
        .navigationTitle("Ways")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("Ways")
                    .font(Constants.Typography.heading)
                    .foregroundColor(.ink)
            }
        }
        .onAppear(perform: reload)
        .alert("Delete all Ways?", isPresented: $confirmDeleteAll) {
            Button("Delete", role: .destructive) {
                ways.forEach { delete(id: $0.id) }
                reload()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Their voices and photos leave this phone. Your own walks are untouched.")
        }
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:40-85@7c200bf

| # | Row | Shown when | Type, colour |
|---|---|---|---|
| — | nav bar title `"Ways"`, inline | always | heading, ink |
| 1 | `"no ways yet"` | the list is empty | caption, fog |
| 2..n | one row per Way: the title, then the detail line 2 pt below | per listed Way | title body/ink; detail caption/fog |
| n+1 | `"Delete all Ways"` button | the list is not empty | button font, destructive role (the system's red) |
| n+2 | the package footer | stages on the phone with an installed route (Stage 21-2) | caption, fog |

- One plain `List` with no sections, headers, or footers. Every item, the empty copy and the button included, is a list row. There's no background modifier, so it renders in the system's grouped-list colours, as the Ways sheet does (own-walk spec F §4.3).
- The empty copy is `"no ways yet"`, not the sheet's longer `"no ways yet. Accept a shared walk, or walk one of yours again."` (§6.2).
- The rows have no tap action: they are not doors (own-walk spec F §2). The only gesture on a row is swipe to delete.
- The title has no line limit, so a long shared title (up to 80 + 3 + 80 characters) wraps.

The package footer (Stage 21-2) reads `"the <route> keeps its <N> stage(s) on its route page"`:

```swift
    static func packageFooter(routeName: String?, stageCount: Int) -> String? {
        guard let routeName, stageCount > 0 else { return nil }
        return "the \(routeName) keeps its \(stageCount) \(stageCount == 1 ? "stage" : "stages") on its route page"
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:25-28@7c200bf

#### 3.2 Order

The list is `WayStore.list()` with stages removed: newest acceptance first, a Way with no `accepted.json` last (own-walk spec A §13, `WayStore.swift:117-121@7c200bf`). Own-walk and shared Ways interleave by acceptance time:
- A share is accepted when its link is first fetched, not when it is walked (§6.3).
- An own walk is accepted at its first clean honoring.
- Re-fetching a share keeps its first acceptance, so it keeps its place.

#### 3.3 Reload

```swift
    private func reload() {
        for id in WayStore.shared.sweepExpired(now: Date()) { WayMediaDownloader.shared.cancel(wayId: id) }
        let all = WayStore.shared.list()
        ways = WaysListModel.listable(all)
        // The stages the list just hid, named by the route that owns them.
        packageFooter = WaysListModel.packageFooter(routeName: PilgrimagePackageManager.shared.installed()?.route.name,
                                                    stageCount: all.count - ways.count)
        details = Dictionary(uniqueKeysWithValues: ways.map { ($0.id, detail(for: $0)) })
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:95-103@7c200bf

Order of work on every appear and after every delete:
1. The sweep runs: expired shares never walked lose their whole folder; expired shares that were walked lose their `media/`. Their downloads are cancelled.
2. The list is read.
3. The footer is computed.
4. Each row's detail is computed once and cached in `details`.

All of it runs synchronously on the main thread. The detail is cached because `hasMedia` and `diskUsage` are file-system calls:

```swift
    /// Computed alongside `ways` in `reload()`, not read live from `WayStore`
    /// in the row: `body` re-runs on every list mutation, and `diskUsage`/
    /// `hasMedia` are filesystem stats per call.
    @State private var details: [String: String] = [:]
```
> Pilgrim/Scenes/Settings/WaysListView.swift:33-36@7c200bf

A download that lands while the list is open does not update its row's size: nothing re-runs `reload` until the next appear or delete. (It can't happen in practice, because nothing downloads unless an overview opened, and an overview is not reachable from Settings. A transfer from an overview closed moments ago can still land.)

### 4. The detail line, and "voices returned to the trail"

```swift
    private func detail(for way: Way) -> String {
        let lead = WayStageLine.line(for: way)
            ?? DateFormatter.localizedString(from: way.departedAt, dateStyle: .medium, timeStyle: .none)
        if way.voiceCount + way.photoCount > 0, !WayStore.shared.hasMedia(id: way.id) {
            return "\(lead) · voices returned to the trail"
        }
        let mb = String(format: "%.1f MB", Double(WayStore.shared.diskUsage(id: way.id)) / 1_000_000)
        return "\(lead) · \(mb)"
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:105-113@7c200bf

```swift
    func hasMedia(id: String) -> Bool {
        guard Self.isValidId(id) else { return false }
        let contents = (try? fileManager.contentsOfDirectory(atPath: mediaDirectory(for: id).path)) ?? []
        return !contents.isEmpty
    }
```
> Pilgrim/Models/Honor/WayStore.swift:144-148@7c200bf

- The lead is the departure date in medium style with no time, in the phone's current zone (not the Way's `tzIdentifier`), for example `"Sep 14, 2026"` in en-US. A stage has a stage line instead (Stage 21-2).
- `"voices returned to the trail"` replaces the size whenever the Way has at least one voice or photo **and** its `media/` folder is missing or empty. `hasMedia` checks only that the folder has some entry, and it doesn't recurse. The downloader creates `media/audio/` or `media/photos/` just before moving a file in (`WayMediaDownloader.swift:222-235@7c200bf`), so one landed file is enough, and so is an empty subfolder left by a failed move (S3 §15 has the same reading).

Every case, for a shared Way:

| Shared Way's state | `voiceCount + photoCount` | `media/` | Detail |
|---|---|---|---|
| Expired and walked: the sweep took its media (the case the line is for, AE3) | > 0 | removed | `"<date> · voices returned to the trail"` |
| Expired and never walked | — | — | not listed: the sweep removed the whole Way |
| Accepted, overview closed before any file landed (offline, a fast Close, every download failed) | > 0 | absent | `"<date> · voices returned to the trail"` (**misfire**) |
| Partly downloaded (11 of 12 voices failed) | > 0 | some files | `"<date> · 1.4 MB"`: no hint that voices are missing |
| Fully downloaded | > 0 | all files | `"<date> · 23.5 MB"` |
| A quiet share (waypoints, rests, or sittings only) | 0 | absent | `"<date> · 0.0 MB"` |

The own-walk row reads `"voices returned to the trail"` for every own walk with a voice or photo, because own walks never have a `media/` folder. That is already filed as A-D4 ([pilgrim-ios #109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109)). The shared-walk misfire in row 3 has the same cause (the line keys on the folder, not on expiry) and belongs on the same issue (§Defects 1).

`"0.0 MB"` shows for any Way under 50,000 bytes (rounded to one decimal).

Android at `636cf5ce`: `WayStore.hasMedia` and `diskUsage` exist with iOS's semantics apart from the size unit (`WayStore.kt:141-157@636cf5ce`). There is no list screen yet (U28 creates `ui/settings/data/WaysListScreen.kt`).

### 5. Deleting Ways: one by swipe, all by "Delete all Ways"

#### 5.1 The two paths

- **One Way:** the list's `.onDelete` (§3.1, `WaysListView.swift:51-57@7c200bf`). This is the system's trailing swipe, with a red `"Delete"` action that a full swipe also triggers. There is **no confirmation**. There is no Edit button, so swipe is the only way in. VoiceOver reaches the same action through the row's actions (system-provided; iOS sets nothing).
- **All Ways:** `"Delete all Ways"` (button font, destructive role), shown under the rows whenever the list isn't empty. It opens the alert:

| Part | Text | Role |
|---|---|---|
| Title | `"Delete all Ways?"` | — |
| Message | `"Their voices and photos leave this phone. Your own walks are untouched."` | — |
| Button | `"Delete"` | destructive |
| Button | `"Cancel"` | cancel |

"Delete all" walks the `ways` array the list shows (§2.2's `listable`), so it takes every own-walk and shared Way and never a pilgrimage stage:

```swift
    /// A stage's Way is one file of an installed package: taking it here would
    /// leave `route.json` and `release.txt` behind, and the route screen would
    /// still say the route is on your phone with no stages under it. The route
    /// screen removes a package whole, so this list never offers one — and
    /// "Delete all Ways", which walks this same array, cannot reach one either.
```
> Pilgrim/Scenes/Settings/WaysListView.swift:7-11@7c200bf

Both paths run the same per-Way delete. The download is cancelled first, then the store removes the Way:

```swift
    /// The store stays UI-free, so cancelling the transfers that would
    /// otherwise land in a folder nothing can see or remove belongs here, at
    /// the delete site.
    private func delete(id: String) {
        WayMediaDownloader.shared.cancel(wayId: id)
        WayStore.shared.delete(id: id)
    }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:87-93@7c200bf

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

Then `reload()` re-sweeps and re-reads (§3.3). Everything is synchronous on the main thread. "Delete all" rewrites `index.json` once per Way. A failure to remove is silent (`try?`), so a Way whose folder could not be removed reappears on reload.

The downloader also refuses any late delivery for a Way that is gone, so a cancelled transfer that still completes cannot recreate the folder:

```swift
        guard store.load(id: target.wayId) != nil else {
            tasks.removeValue(forKey: taskId)
            return
        }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:278-281@7c200bf

#### 5.2 What deleting a shared Way removes, and what it keeps

| Item | After delete |
|---|---|
| `way.json`, `accepted.json` | removed (the whole `Ways/share:<id>/` folder) |
| `media/` (downloaded voices and photos) | removed |
| `replies.json` (which reply answers which voice) | removed |
| Every walk's link to the Way (`index.json` entries with this `wayId`) | removed, including their arrival numbers |
| In-flight media downloads | cancelled first; a late delivery is dropped |
| The walker's reply recordings (`Documents/Recordings/<walk>/…`) | **kept**: they are the walks' own voice recordings and stay in each walk's recordings list |
| The honor walks: their route, `.honorMode` and `.honorArrival` events, and the arrival waypoint `"Walked their way: <title>"` | kept (own-walk spec A §23) |

What each honor walk of the deleted share then shows:
- **Summary:** kicker `"in their steps"`, title `"a way that has been removed"`, no delta line, and no counts line. With no link, replies read as `[:]`, and with no Way, `voiceCount` reads 0 (§12.1).
- **Summary map:** no ghost line.
- **Prompts:** the lexicon drops the `" The Way: <title>."` sentence (§12.3).
- **Seal:** iOS's cached seal image keeps the Way's line. Android drops it with the link (owner decision 3).
- **Journal glyph, scenery staff, milestones:** unchanged, because they read the walk's events and waypoints.
- **Arrival waypoint label:** keeps the old title.

Opening the same share link again later re-fetches it (§7). If the share hasn't expired, it imports as a new acceptance, with a fresh `accepted.json`, at the top of both lists. The old walks are **not** re-linked, because their links are gone, and their old replies are no longer offered as "your reply", because `replies.json` is gone. If it has expired, the fetch fails with `"This walk has returned to the trail"`.

The alert's message is accurate for shared Ways. For own-walk Ways it's half right: own-walk Ways have no voices or photos of their own (the recordings belong to the walks and stay), and "Your own walks are untouched" is true of the walks while their own-walk Ways do go. That copy issue is already part of A-D4 ([pilgrim-ios #109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109)).

Android at `636cf5ce`: `WayStore.delete` removes the folder and every per-walk link naming the Way (`WayStore.kt:116-122@636cf5ce`), which matches. There's no downloader yet to cancel.

### 6. The Ways sheet: "Shared with you"

#### 6.1 Where the section sits

It's the first of the sheet's four sections, above "Your own walks" (own-walk spec F §4.2). Header `"Shared with you"` in caption.

```swift
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
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:25-36@7c200bf

#### 6.2 Which Ways it lists, in what order

```swift
            .onAppear {
                for id in WayStore.shared.sweepExpired(now: Date()) { WayMediaDownloader.shared.cancel(wayId: id) }
                acceptedWays = WayStore.shared.list().filter { if case .share = $0.source { return true } else { return false } }
                withMedia = Set(acceptedWays.filter { WayStore.shared.hasMedia(id: $0.id) }.map(\.id))
            }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:112-116@7c200bf

- Shared Ways only. Own-walk Ways and stages never appear here (own-walk spec F §4.2).
- Newest acceptance first (`list()`'s order). A re-fetched share keeps its place.
- The sweep runs first, as in Settings: an expired share that was never walked disappears, and an expired share that was walked stays, without its media.
- Computed once per appearance, on the main thread. Nothing refreshes the section while the sheet is up, so a share accepted from a link tapped while the sheet is open appears only on the next opening (in practice it can't be seen anyway, because a successful fetch closes the sheet, §7.4).
- `withMedia` is cached for the same reason as Settings' details:

```swift
    /// Computed alongside `acceptedWays` in `onAppear`, not read live from
    /// `WayStore` in `wayRow`: the `TextField` above re-evaluates this body
    /// on every keystroke, and `hasMedia` is a filesystem stat per call.
    @State private var withMedia: Set<String> = []
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:16-19@7c200bf

The empty copy `"no ways yet. Accept a shared walk, or walk one of yours again."` (caption, fog) shows whenever no **shared** Way is stored, even if own-walk Ways exist.

#### 6.3 What "accepted" means

A share is accepted the moment its manifest fetch succeeds. The importer saves it before returning, before any overview, Begin, or download:

```swift
        let way = try Self.way(from: manifest, shareId: id, now: now())
        try store.save(way)
        return way
```
> Pilgrim/Models/Honor/WayImporter.swift:83-85@7c200bf

So every link the walker opened, whether tapped or pasted, that fetched successfully is listed here until it is deleted in Settings or expires unwalked. Closing the overview without Begin does not un-accept it. Each re-fetch rewrites `way.json` and keeps the first `accepted.json` (own-walk spec A §13).

#### 6.4 The row

```swift
    @ViewBuilder
    private func wayRow(_ way: Way) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(way.title)
                .font(Constants.Typography.body)
                .foregroundColor(.ink)
            HStack {
                Text(WayStageLine.line(for: way)
                     ?? DateFormatter.localizedString(from: way.departedAt, dateStyle: .medium, timeStyle: .none))
                Text("·")
                Text(withMedia.contains(way.id) || way.voiceCount + way.photoCount == 0
                     ? HonorOverviewModel.countsLine(way: way) : "voices returned to the trail")
            }
            .font(Constants.Typography.caption)
            .foregroundColor(.fog)
        }
    }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:125-141@7c200bf

- Line 1: the Way's title, body/ink, no line limit.
- Line 2, caption/fog, three `Text`s in an `HStack` with default spacing:
  - the departure date, medium style, the phone's current zone (a stage would show its stage line);
  - `"·"`;
  - the counts (`"9 voices · 4 photos"`, `"1 voice"`, `"a quiet way"`, own-walk spec F §10.3), **or** `"voices returned to the trail"`.
- The counts line wins when the Way has at least one media file on disk, or has no voices and no photos. Otherwise it's `"voices returned to the trail"`. That's the same folder test as Settings (§4), with the same misfire: a share accepted but never downloaded reads as returned to the trail (§Defects 1).
- The counts count every voice (`ambience` included) and photo the Way declares, not how many are on the phone. A share with 1 of 12 voices downloaded reads `"12 voices"`.
- The row is the whole `Button` label. It has no chevron (unlike the own-walk row's `settingNavRow`) and no explicit accessibility label (§13.2).
- Nothing on the row shows the sharer, the expiry, how many days are left, or the page URL (§1).

#### 6.5 Tapping a row

`onChoose(way)` → `coordinator.openOverview(for:)` (`MainTabView.swift:68@7c200bf`). The sheet is presented, so the Way is parked and the sheet closes. Its dismissal promotes the Way, which presents the overview and calls `gather` (own-walk spec F §7.1). The row hands over the **stored** Way, with no network fetch. That is the only way to reach the overview of an expired, walked share: its link now fails with `"This walk has returned to the trail"` (§7.3), but its row still opens it. On that overview `gather` tries to download the missing media again (§8.6).

### 7. The Ways sheet: "From a shared walk", the paste field

#### 7.1 Layout

```swift
                Section {
                    TextField("paste a walk link", text: $pasted)
                        .font(Constants.Typography.body)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Open") { onPaste(pasted) }
                        .font(Constants.Typography.button)
                        .disabled(HonorLink.parse(text: pasted) == nil || importState == .fetching)
                    // The field above stays editable in every state, so a
                    // mistyped link is corrected where it was typed.
                    if let line = HonorImportCopy.line(for: importState) {
                        Text(line)
                            .font(Constants.Typography.caption)
                            .foregroundColor(isFailure ? .rust : .fog)
                    }
                } header: {
                    Text("From a shared walk").font(Constants.Typography.caption)
                } footer: {
                    Text("A walk someone shared with you, from walk.pilgrimapp.org.")
                        .font(Constants.Typography.caption)
                }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:57-77@7c200bf

```swift
    private var isFailure: Bool {
        if case .failed = importState { return true }
        return false
    }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:120-123@7c200bf

Last of the four sections, after "A pilgrimage". Three rows and a footer:
1. A text field with the placeholder `"paste a walk link"`, body font, no auto-capitalization, no autocorrect. It starts empty: the clipboard is never read, and nothing pre-fills it.
2. A button, `"Open"`, button font, tinted stone by `MainTabView`'s `.tint(.stone)` (`MainTabView.swift:160-164@7c200bf`), system-dimmed when disabled.
3. The import line, caption: rust for a failure, fog otherwise. Absent when there's no line.
4. Footer, caption: `"A walk someone shared with you, from walk.pilgrimapp.org."` The system footer colour applies; no colour is set.

The field stays editable in every state. `pasted` is never cleared, either on success (the sheet closes) or on failure (the text stays for correction).

#### 7.2 When "Open" is enabled

Enabled when `HonorLink.parse(text: pasted)` returns an id **and** the import state is not `.fetching`:

```swift
    static func parse(text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if isID(trimmed) { return trimmed }
        let withScheme = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        guard let url = URL(string: withScheme) else { return nil }
        return parse(url)
    }
```
> Pilgrim/Models/Honor/HonorLink.swift:17-24@7c200bf

```swift
    static let hosts: Set<String> = ["honor.pilgrimapp.org", "walk.pilgrimapp.org"]
    private static let idPattern = "\\A[A-Za-z0-9_-]{10}\\z"

    static func parse(_ url: URL) -> String? {
        guard let host = url.host?.lowercased(), hosts.contains(host) else { return nil }
        let parts = url.pathComponents.filter { $0 != "/" }
        guard parts.count == 1, isID(parts[0]) else { return nil }
        return parts[0]
    }
```
> Pilgrim/Models/Honor/HonorLink.swift:7-15@7c200bf

The rule is re-checked on every keystroke, live: a bare 10-character id, or a link on either host with exactly one path segment (the parsing rules themselves are S2 §1). There's no inline "that isn't a link" message. An unparseable text just leaves `"Open"` dimmed. The button stays disabled during `.fetching` even if the text changes. It is **not** disabled during `.gathering` (which the sheet never sees, §7.4).

The tap parses again and calls `openWay` with the id:

```swift
                onPaste: { text in
                    if let id = HonorLink.parse(text: text) { coordinator.openWay(shareId: id) }
                }
```
> Pilgrim/Scenes/Root/MainTabView.swift:69-71@7c200bf

#### 7.3 The fetch and its states

```swift
    func openWay(shareId: String) {
        guard activeWalkViewModel == nil else { showLinkToast("finish this walk first"); return }
        importTask?.cancel()
        honorImportState = .fetching
        if !honorWaysPresented { showLinkToast("reaching for the walk…") }
        let fetch = importShare
        importTask = Task { @MainActor [weak self] in
            do {
                let way = try await fetch(shareId)
                // A cancelled import belongs to a link the walker has already
                // replaced; neither its Way nor its error may land on top of
                // the newer one's state.
                guard let self, !Task.isCancelled else { return }
                self.showLinkToast(nil)
                self.honorImportState = .idle
                self.importTask = nil
                // A Begin already in flight (a walk starting, or parked to
                // start once the overview closes) wins — presenting this Way
                // now would race it for the overview sheet or interrupt the
                // walk that's already beginning. Drop it silently.
                guard self.activeWalkViewModel == nil, self.pendingStartWay == nil else { return }
                self.openOverview(for: way)      // AF60-safe: parks or presents, never both
            } catch {
                guard let self, !Task.isCancelled else { return }
                let failure = (error as? WayError) ?? .unavailable
                self.honorImportState = .failed(failure)
                self.showLinkToast(self.honorWaysPresented ? nil : HonorImportCopy.line(for: .failed(failure)))
                self.importTask = nil
            }
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:212-242@7c200bf

```swift
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
> Pilgrim/Models/Honor/HonorImportReducer.swift:24-37@7c200bf

With the sheet up, the paste field shows:

| State | Line under "Open" | Colour | "Open" | Cause |
|---|---|---|---|---|
| `.idle` | none | — | enabled if the text parses | every opening of the sheet (`chooseWay` resets it, `MainCoordinatorView.swift:195-207@7c200bf`); also the instant after a successful fetch |
| `.fetching` | `"reaching for the walk…"` | fog | disabled | after "Open", until the fetch ends |
| `.failed(.notFound)` | `"couldn't find that walk. Check the link, or it may have returned to the trail."` | rust | enabled if the text parses | HTTP 404 |
| `.failed(.returnedToTrail)` | `"This walk has returned to the trail"` | rust | enabled | the manifest's `expires` is at or before now |
| `.failed(.unavailable)` | `"couldn't reach the walk"` | rust | enabled | any other status, offline, timeout (15 s request / 30 s resource), oversized or undecodable manifest, any validation bound, **or a failed save to disk** |

- The importer's error mapping is S1's. One detail matters for this screen: `store.save(way)` throws a Cocoa error, not a `WayError`, so `(error as? WayError) ?? .unavailable` turns a full disk during the manifest save into `"couldn't reach the walk"` (§Defects 3).
- Casing is as shipped: `"This walk has returned to the trail"` is capitalized with no full stop. The others are lower-case, and two of them end with a full stop.
- A failure line stays until the next "Open", the next opening of the sheet, or a successful fetch. Editing the text doesn't clear it.
- No toast while the sheet is up: `openWay` shows the "reaching" toast only when the sheet isn't presented, and the failure toast is nil when it is.
- `.gathering`, `.mediaMissing`, `.ready`, and `.failed(.diskFull)` are never seen here. They're set only by `gather`, which runs only for an overview, and an overview and this sheet are never up together (own-walk spec F §7.1).

#### 7.4 After a successful fetch

1. The state goes to `.idle`, so the line disappears.
2. `openOverview` parks the Way and sets `honorWaysPresented = false`, so the sheet slides away.
3. The sheet's `onDismiss` promotes the Way: the overview presents and `gather` runs (§8).

Unless a Begin is already in flight or a walk is on, the fetch result is dropped silently (`MainCoordinatorView.swift:232@7c200bf`). From the sheet that can't happen, since no Begin is reachable while the sheet is up.

Edge cases:
- **Close mid-fetch.** The walker pastes, taps "Open", then closes the sheet. The fetch carries on. On success, `openOverview` finds no sheet and presents the overview directly. On failure, a toast shows the failure copy for 5 s (`showLinkToast`, `MainCoordinatorView.swift:342-349@7c200bf`). No "reaching" toast appears after the close; it was decided when "Open" was tapped.
- **A row tapped mid-fetch.** The walker taps a "Shared with you" row while a paste is fetching. The row's Way parks, the sheet closes, and its overview opens with `gather` overwriting the `.fetching` state. When the fetch lands, `openOverview` presents the fetched Way by swapping the overview's item (no sheet is up), and the overview changes Ways under the walker. The Begin gate (§8.4) covers only the window in which the state still reads `.fetching`.
- **A second "Open".** It cancels the first import (`importTask?.cancel()`), and the first one's result is dropped.

Android at `636cf5ce`: no sheet yet (U21 creates `ui/honor/HonorWaysSheet.kt`; U28 adds these two sections).

### 8. The overview for a shared Way: gathering, missing voices, and Begin

#### 8.1 How gathering starts

The overview never fetches. It appears only with a Way in hand (a fetched link or a stored row), so it has no `.fetching` state of its own (that comes only from a *second* link, §8.5). On presentation, `gather` hops to the main actor and, for a share, starts the downloader and subscribes to it:

```swift
    func gather(_ way: Way) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            // A dismiss or an item swap that lands before this hop must not
            // install a sink for a Way that is no longer showing.
            guard self.honorOverviewWay?.id == way.id else { return }
            guard case .share = way.source else { self.honorImportState = .ready; return }
            let downloader = WayMediaDownloader.shared
            downloader.download(way)
            self.honorImportState = HonorImportReducer.state(
                wayId: way.id, progress: downloader.progress, active: downloader.active,
                failures: downloader.failures, diskFull: downloader.diskFull)
            self.gatheringCancellable = downloader.$progress
                .combineLatest(downloader.$active, downloader.$failures, downloader.$diskFull)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] progress, active, failures, diskFull in
                    self?.honorImportState = HonorImportReducer.state(
                        wayId: way.id, progress: progress, active: active, failures: failures, diskFull: diskFull)
                }
        }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:264-284@7c200bf

The three entries, and what the overview shows in its first frame:

| Entry | State before `gather`'s hop | First frame |
|---|---|---|
| A tapped link, no sheet open | `.idle` (set by the successful fetch, §7.3) | no import line, Begin **enabled** and stone |
| "Open" in the Ways sheet | `.idle`; the overview waits for the sheet to close (§7.4) | same |
| A "Shared with you" row | `.idle` (reset by `chooseWay`) | same |

So for one main-actor hop, the overview of a share with everything still to download reads `.idle`: no line, and an enabled Begin. On iOS the hop lands long before the sheet's presentation animation ends, so no walker can tap into it. Android should not let the first composed frame show an enabled Begin before the first gathering state is known (a ViewModel that starts in `.idle` and emits after a dispatcher hop would do exactly that). Then the state settles as below.

`download` decides the first real state (`WayMediaDownloader.swift:120-144@7c200bf`):
- Every declared file already on disk: progress 1, nothing enqueued → `.ready` (or `.mediaMissing` if the manifest declared files beyond the ceilings, below).
- Otherwise: the Way becomes `active`, and progress is **seeded from what's already on disk**, `1 − missing / declared`, so a reopened overview resumes at its old percentage, not 0%.
- A second `gather` for a Way that is still downloading enqueues nothing (`guard !active.contains(way.id)`) and just re-subscribes.
- The per-kind ceilings (12 audio, 20 photo files) are applied to everything the Way declares. Files beyond them are recorded as failures before anything downloads. (The downloader's rules are S3 §3 and §6.)

#### 8.2 The state machine

```swift
enum HonorImportState: Equatable {
    case idle, fetching, gathering(progress: Double), ready, mediaMissing([String]), failed(WayError)
}

/// Pure mapping from the downloader's published sets to the overview state,
/// so the state machine is testable without a session.
enum HonorImportReducer {
    static func state(
        wayId: String,
        progress: [String: Double],
        active: Set<String>,
        failures: [String: [String]],
        diskFull: Set<String>
    ) -> HonorImportState {
        if diskFull.contains(wayId) { return .failed(.diskFull) }
        if active.contains(wayId) { return .gathering(progress: progress[wayId] ?? 0) }
        if let missing = failures[wayId], !missing.isEmpty { return .mediaMissing(missing) }
        return .ready
    }
}
```
> Pilgrim/Models/Honor/HonorImportReducer.swift:3-22@7c200bf

Precedence: disk full beats gathering, gathering beats missing, missing beats ready. iOS pins all four transitions and the copy:

```swift
    func testTransitions() {
        let id = "share:abc"
        XCTAssertEqual(HonorImportReducer.state(wayId: id, progress: [id: 0.4], active: [id], failures: [:], diskFull: []),
                       .gathering(progress: 0.4))
        XCTAssertEqual(HonorImportReducer.state(wayId: id, progress: [id: 1], active: [], failures: [:], diskFull: []), .ready)
        XCTAssertEqual(HonorImportReducer.state(wayId: id, progress: [id: 1], active: [], failures: [id: ["audio/2.m4a"]], diskFull: []),
                       .mediaMissing(["audio/2.m4a"]))
        XCTAssertEqual(HonorImportReducer.state(wayId: id, progress: [:], active: [id], failures: [:], diskFull: [id]),
                       .failed(.diskFull), "disk full outranks everything")
    }
```
> UnitTests/Honor/HonorImportReducerTests.swift:6-15@7c200bf

```swift
    func testCopyGatheringRoundsToWholePercent() {
        XCTAssertEqual(HonorImportCopy.line(for: .gathering(progress: 0.456)), "gathering their voices · 46%")
    }

    func testCopyMediaMissing() {
        XCTAssertEqual(HonorImportCopy.line(for: .mediaMissing(["audio/2.m4a"])), "some voices didn't arrive")
    }
```
> UnitTests/Honor/HonorImportReducerTests.swift:23-29@7c200bf

Port these as `HonorImportReducerTest` verbatim (U28's `HonorOverviewViewModelTest` gathering states).

#### 8.3 The import line on the card

It is row 4 of the card, between the stats row and the weather line (own-walk spec F §8):

```swift
    private var isTrouble: Bool {
        switch importState {
        case .failed, .mediaMissing: return true
        default: return false
        }
    }

    /// The one place the import speaks on this screen: its line under the
    /// counts, and — only when files are actually missing — the choice
    /// between waiting for them and walking without them.
    @ViewBuilder
    private var importLine: some View {
        if let line = HonorImportCopy.line(for: importState) {
            Text(line)
                .font(Constants.Typography.caption)
                .foregroundColor(isTrouble ? .rust : .fog)
        }
        if case .mediaMissing = importState {
            // Vertical, not the section's usual horizontal pairing: the
            // second label is long enough to clip on an SE width at large
            // accessibility type sizes if it has to share a row.
            VStack(alignment: .leading, spacing: Constants.UI.Padding.xs) {
                Button("try again", action: onRetryMedia)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                Button("walk without the missing voices", action: onWalkWithoutMissing)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .font(Constants.Typography.caption)
            .foregroundColor(.stone)
        }
    }
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:303-335@7c200bf

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

Every state on a shared overview:

| State | Import line (caption) | Colour | Buttons under it | Begin |
|---|---|---|---|---|
| `.idle` | none | — | — | enabled, stone |
| `.gathering(p)` | `"gathering their voices · N%"` with N = `Int((p × 100).rounded())` | fog | — | **disabled**, fog background |
| `.ready` | none | — | — | enabled, stone |
| `.mediaMissing(_)` | `"some voices didn't arrive"` | rust | `"try again"`, then `"walk without the missing voices"`, stacked, 4 pt apart, each at least 44 pt tall, caption, stone | **enabled**, stone |
| `.failed(.diskFull)` | `"not enough space on this phone to save these voices"` | rust | none | enabled, stone |
| `.fetching` (a second link, §8.5) | `"reaching for the walk…"` | fog | — | **disabled**, fog |
| `.failed(.notFound / .returnedToTrail / .unavailable)` (a second link that failed) | the §7.3 copy | rust | none | enabled, stone |

Points the plan must carry:
- **Begin is never gated on missing voices.** It is disabled only while a file can still land (`.gathering`) or a fetch is in flight (`.fetching`). With `.mediaMissing` showing, Begin is already enabled. `"walk without the missing voices"` doesn't unlock it; it only clears the line and the two buttons (§8.4).
- **The line never names the missing voices.** `.mediaMissing` carries the failed relative paths, but nothing displays them. There's no per-voice indicator on the card, the pins, or the preview list. The only per-moment sign is the preview's `"their voice is still on its way here"` on a missing voice (§9.3).
- **The percentage counts files, not bytes.** It moves by `1 / declared` per file, photos included, although the copy says "voices". For example, 12 voices and 20 photos step by about 3% per file.
- **Disk full has no retry and no "walk without"**, per the downloader's own comment, `/// Way ids whose download hit a full disk; the coordinator names the problem instead of offering a retry.` (`WayMediaDownloader.swift:16@7c200bf`). The other transfers keep going, and the state stays `.failed(.diskFull)` until the overview closes, since no retry is offered. Disk full outranks gathering in the reducer, so **Begin enables while the Way's other files are still downloading**. That's the one ordinary path to a walk that starts mid-transfer.
- **Gathering has no cancel and no skip.** The only way out is `"Close"`. The downloads keep running in the background session after the close, and the next opening of the same Way picks up at its seeded percentage.
- The card's height changes when the line or the two 44 pt buttons appear and disappear. The map's bottom inset follows it, so the map refits, undoing a pan the walker made (own-walk spec F §9.3: a refit runs when the inset changes). Each percentage tick re-runs the body but doesn't change the height, so it doesn't refit.

#### 8.4 "try again" and "walk without the missing voices"

```swift
                    onRetryMedia: { coordinator.retryMedia(for: way) },
                    onWalkWithoutMissing: coordinator.walkWithoutMissingVoices
```
> Pilgrim/Scenes/Root/MainTabView.swift:81-82@7c200bf

```swift
    func retryMedia(for way: Way) {
        Task { @MainActor in WayMediaDownloader.shared.retry(way) }
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:286-288@7c200bf

```swift
    func retry(_ way: Way) {
        // A retry tapped while the last failure is still hopping to the main
        // actor would find the Way still `active` and silently do nothing.
        // Clearing the bookkeeping first makes the retry unconditional: the
        // in-flight `finish` then finds no entry of its own and returns.
        cancel(wayId: way.id)
        download(way)
    }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:146-153@7c200bf

- **"try again"** cancels the Way's bookkeeping, then downloads every declared file that's still missing. The sink is still installed, so the line goes back to `"gathering their voices · N%"` (seeded from what's on disk) and Begin disables again, then settles as before.
  - Files the manifest declared beyond the ceilings are refused again on every retry. A manifest with 13 audio files therefore always ends in `"some voices didn't arrive"`, however often "try again" is tapped.
  - For an expired share whose files the worker no longer serves, the retry fails the same way each time. That depends on the worker (Open question 3).
- **"walk without the missing voices"** drops the sink and sets `.ready`:

```swift
    /// "walk without the missing voices": the sink is dropped first, so a
    /// later change from another Way's download can't recompute this one back
    /// into `.mediaMissing`. The absent files simply never play.
    func walkWithoutMissingVoices() {
        gatheringCancellable = nil
        honorImportState = .ready
    }
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:297-303@7c200bf

  The line and both buttons disappear, Begin stays enabled, and nothing else changes. The walker still has to tap Begin; the button doesn't start the walk. The overview stops tracking downloads for the rest of its life. Missing files stay missing: on the walk, a missing voice is skipped silently (§10.4).

#### 8.5 A second link while a shared overview is open

`openWay` sets the global state to `.fetching` (`"reaching for the walk…"`, Begin disabled). It does **not** clear `gatheringCancellable`:
- **The second fetch succeeds.** `openOverview` swaps the overview's item to the new Way and `gather` re-subscribes for it. The old Way's transfers keep running unobserved. The outgoing Way's `handleOverviewDismiss` doesn't reset the state, because the item isn't nil (`MainCoordinatorView.swift:325-333@7c200bf`).
- **It fails.** The failure line shows in rust, and Begin is enabled.
- **Either way, while the fetch is in flight,** the old sink is still live. Any downloader change for **any** Way re-runs the reducer for the shown Way and overwrites `.fetching` (or a `.failed` line). If the shown Way's last file lands mid-fetch, the state becomes `.ready` and Begin re-enables while a fetch "could still land", which is what the `isGathering` comment says must not happen. iOS's backstop is the fetch's own `pendingStartWay == nil` guard: a Begin tapped then wins, and the fetched Way is dropped silently. Low (§Defects 4).

The same global state means an **own-walk** overview shows these second-link lines too (own-walk spec F §13.1, F-7, [pilgrim-ios #110](https://github.com/walktalkmeditate/pilgrim-ios/issues/110)).

#### 8.6 Closing, Begin, and what happens to the downloads

- **Close** (or swipe down): `handleOverviewDismiss` drops the sink and resets the state to `.idle` (own-walk spec F §7.3). The downloads continue in the background session. The share stays accepted and listed (§6.3).
- **Begin**: the overview closes, then `startWalk` drops the observation (`importTask?.cancel()`, `gatheringCancellable = nil`) and keeps the transfers:

```swift
        // The overview is gone by the time a walk starts, so nothing is left
        // to render an import's progress — only the OBSERVATION is dropped.
        // The background transfers keep running, and a file that lands
        // mid-walk becomes playable like any other.
        importTask?.cancel()
        importTask = nil
        gatheringCancellable = nil
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:81-87@7c200bf

  Begin is disabled while gathering, and `"try again"` puts the state back into gathering (the two buttons show only in `.mediaMissing`). So a walk starts with transfers still running only after a disk-full failure (§8.3) or inside a second link's fetch window (§8.5). Otherwise, Begin means every file has landed or failed.
- **An expired, walked share reopened from its row** (§6.5): `gather` calls `download`. Its media folder was swept, so every declared file is missing, and they are all enqueued against `walk.pilgrimapp.org/<id>/audio/<n>.m4a` and `/photos/<n>.jpg` again. Nothing checks `way.expires` first. What the walker sees depends on the worker:
  - If it no longer serves them: `"gathering their voices · 0%"`, then (after one retry per file) `"some voices didn't arrive"`.
  - If it still does: the files land, and the next sweep (the next opening of the sheet or the list, or the next launch) deletes them again.

  Either way, there's no "returned to the trail" copy on the overview (§Defects 2, Open question 3).

### 9. The rest of a shared overview, and the moment preview

#### 9.1 The card's other rows for a shared Way

The card's layout, fonts, and framing are own-walk spec F §8–§10. For a share:

| Row | Shared value | Note |
|---|---|---|
| 1 Title | `"<start> → <end>"`, one place, or the import-time medium date (§1) | heading, ink; no line limit, so a long pair wraps |
| 2 Departure | `DateFormatter.localizedString(from: way.departedAt, dateStyle: .long, timeStyle: .short)`, for example `"September 14, 2026 at 8:41 AM"` | in the **phone's** zone; the preview's hours use the **Way's** `tzIdentifier` (§9.3, §Defects 5) |
| 3 Stats | `StatsHelper` distance of `totalDistanceMeters`, `"·"`, `"Nh Mm"` or `"Mm"` of `theirActiveSeconds`, `"·"`, counts | the distance is the shared route's length, which the sharer's page downsampled, so it can read shorter than the sharer's own walk; the duration falls back to the route's wall-clock span when the manifest has no `stats.active_duration` |
| 4 Import line | §8.3 | — |
| 5 Weather | `"they walked this in <condition>[ at N°]. [Today is <today>.]"` | the condition is the manifest's string, capped at 64. A `WeatherCondition` raw value is spoken as its lower-cased label; anything else passes through untouched (own-walk spec F §10.5). Temperatures are validated to −100…100 °C at import (`WayImporter.swift:121@7c200bf`) |
| 6 Distance to start | `"you're on the way"`, `"650 m from the start"`, … | own-walk spec F §10.6 |
| 8 Toggle | `"walk with their voice"` | disabled only when the Way declares no voices. It is **enabled** when every voice is missing from the phone |
| 9 Begin | `"Begin"`, VoiceOver `"Begin honoring this way"` | §8.3 gate |

No row shows the sharer, the expiry, the days left, or the page link (§1).

#### 9.2 What the map shows

The same as an own walk (own-walk spec F §9). A shared moment's pin stands at `pin` (never set for a share), else `at` (the encounter's own coordinate when the worker sent one), else the line at its fraction. Sittings have `at: nil`, so they always sit on the line. Pins are drawn whether or not their media has landed. A pin for a missing voice looks the same as any other.

#### 9.3 The moment preview on a shared Way

The preview's media URL is resolved in the sheet's content closure:

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

For a `.file` medium, `localMediaURL` returns a URL only when the file exists inside the Way's `media/` folder (`ActiveWalkViewModel+Honor.swift:354-377@7c200bf`). The closure is re-evaluated whenever the overview's body re-runs, which happens on every gathering tick. So a preview opened while its voice is still downloading should switch from the missing row to the player once the file lands, with `.task(id: mediaURL)` then reading the waveform (inferred from SwiftUI's sheet semantics; not checked on a device).

The subline adds the street name, and the hour uses the Way's zone:

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

- On a share: `"1.2 km along their way · 8:41 AM · Rúa do Franco"`. The street shows only on voices, the only kind the worker names. An unknown or missing `tz_identifier` falls back to the phone's zone.
- A voice whose file isn't on the phone (still gathering, failed, or swept after expiry):

```swift
            } else {
                HStack(spacing: Constants.UI.Padding.small) {
                    Image(systemName: "waveform.slash").foregroundColor(.fog)
                    Text("their voice is still on its way here").font(Constants.Typography.body).foregroundColor(.fog)
                }
            }
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:145-150@7c200bf

  The copy is the same whatever the cause, including for an expired share's swept voice, which is not on its way anywhere (§Defects 2). The transcript (when the worker sent one) still shows above it, and the footer `"When you walk it, this plays where they stood to say it."` (spoken) or `"When you walk it, this plays once, softly, as you pass."` (ambient) still shows below.
- A photo whose file isn't on the phone: the plate's parchment placeholder, `min(360, 120)` = 120 pt tall, then the unchanged caption `"tap the photo to see it whole"`, with nothing to tap (`WayMomentPreview.swift:73-77@7c200bf`, `WayPlaceCard.swift:290-292@7c200bf`).
- A shared waypoint: kicker = its label (possibly `""`), body `"A place they marked."` (no dataset text on a share), then `"When you walk it, it rises as a card as you reach it."`.
- A shared sitting: kicker `"they sat here about N minutes"` when estimated, `"they sat here for N minutes"` otherwise; body `"A sitting. When you walk it, the way will offer you the same sitting here."`.

### 10. On the walk: every place a shared Way looks different

Nothing on the walk screen branches on `way.source`. The place card, the chip, the arrival card, the stats sheet, the glance, and the map layers take the own-walk paths in own-walk spec D and E. The differences below come from the shared Way's data (§1). The only screen-level branch is the stage one (`isStage`, `companionCoordinate`, `softTapEnabled`), and a share is never a stage. So a shared walk has the companion and the (dark, own-walk spec A-D2) soft tap exactly as an own walk does:

```swift
    var companionCoordinate: CLLocationCoordinate2D? {
        guard way?.isPilgrimageStage != true else { return nil }
        return honorEngine.map { $0.geometry.coordinate(atFrac: $0.companionFrac) }
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:117-120@7c200bf

#### 10.1 The street name in the card's subline

```swift
                Button { onTouch(); onFly() } label: {
                    WayMomentHeader(
                        moment: moment,
                        subline: WayMomentHeader.relation(distanceMeters: distanceMeters, place: moment.place),
                        compact: true,
                        tick: tick
                    )
                    .contentShape(Rectangle())
                }
```
> Pilgrim/Scenes/ActiveWalk/WayPlaceCard.swift:52-60@7c200bf

```swift
    static func relation(distanceMeters: Double?, place: String?) -> String? {
        var parts: [String] = []
        if let distanceMeters {
            parts.append(distanceMeters < 30 ? "here" : "\(WayDistance.string(meters: distanceMeters)) away")
        }
        if let place, !place.isEmpty { parts.append(place) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:94-101@7c200bf

| Condition (shared voice card) | Subline |
|---|---|
| fix known, under 30 m, `place` set | `"here · Rúa do Franco"` |
| fix known, 30 m or more, `place` set | `"120 m away · Rúa do Franco"` (`WayDistance`, own-walk spec E §9) |
| no fix yet, `place` set | `"Rúa do Franco"` |
| `place` nil or blank (older shares, any photo, waypoint, rest, or sitting) | as an own walk: `"here"`, `"120 m away"`, or no subline |

The subline is caption/fog on one line with no line limit, beside the heading tick when there is one. A street name of up to 80 characters wraps.

#### 10.2 Kickers and body copy that only a share produces

```swift
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
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:58-67@7c200bf

```swift
    static func placeCopy(for moment: WayMoment, isStage: Bool) -> String {
        if let text = moment.text, !text.isEmpty { return text }
        return isStage ? "A place on the way." : "A place they marked."
    }
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:86-89@7c200bf

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
```
> Pilgrim/Scenes/Honor/WayMomentHeader.swift:48-56@7c200bf

| Moment on a share | Condition | Exact text on the card |
|---|---|---|
| Sitting | the manifest's sitting has no `duration` (`isEstimate`) | kicker `"they sat here about N minutes"` (N from the route gap around it, rounded half away from zero) |
| Sitting | it has a `duration` | `"they sat here for N minutes"` (as an own walk) |
| Sitting, after "Sit?" | either | the meditation screen's caption `"they sat here N minute"` / `"… minutes"`, which **drops "about"** (below) |
| Waypoint | the encounter had no `label` | kicker `""`: an empty `Text` in the kicker slot, then `"A place they marked."` |
| Waypoint | the encounter's `icon` is missing, or isn't an SF Symbol name iOS knows | glyph `mappin` |
| Waypoint | any share | body `"A place they marked."` (a share carries no dataset `text`) |
| Rest | the encounter had no `minutes` | `"they rested here 0 minutes"` |
| Voice | the encounter had no `duration` | clock `"0:00 / 0:00"`, and the waveform never shows progress (`duration > 0 ? … : 0`, `WayPlaceCard.swift:182@7c200bf`) |

The meditation screen's caption has no estimate form:

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

It receives only the minutes (`suggestedMeditationMinutes = minutes`, `ActiveWalkViewModel+Honor.swift:380@7c200bf`). So an estimated "about 12 minutes" card leads to a meditation screen that says `"they sat here 12 minutes"` (§Defects 7). The singular and zero cases (`"1 minutes"`, `"0 minutes"`) are own-walk spec E-6 ([pilgrim-ios #109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109)). An estimate can round to 0 or 1 on a share too, giving `"they sat here about 0 minutes"`.

Transcripts (the italic first sentence in curly quotes, two lines) and ambient voices (glyph `wind`, kicker `"the sound of this place"`, half volume) are **not** shared-only: own walks carry both (`OwnWalkWayBuilder.swift:52-54@7c200bf`). On a share the transcript is the worker's and the ambient flag is the worker's `"ambience"` type.

#### 10.3 Media that isn't on the phone

A shared voice or photo plays or shows only if its file has landed in `media/`. Files can be missing because gathering failed (the walker then tapped Begin, with or without `"walk without the missing voices"`), because a full disk enabled Begin with transfers still running, or because the sweep took them after expiry.

- **The engine reaching a missing voice** hands the turn straight back. There's no card, no chip, no sound, no haptic, and the voice isn't marked heard:

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

- **A pin tapped for a missing voice** opens its card. It shows the transcript if any, the empty waveform bar, `"0:00 / m:ss"`, and the reply row. Play does nothing (own-walk spec C §10.1).
- **A reached photo whose file is missing** rises as a card, with the light haptic, and its plate shows only the parchment placeholder: `min(110, 120)` = 110 pt tall, no caption, not tappable (`WayPlaceCard.swift:109-110,290-292@7c200bf`).
- **No text explains the gap.** The words `"voices returned to the trail"` appear nowhere on the walk screen. They are only on the Ways sheet row and the Settings list (`git grep "returned to the trail"` at the pin finds only those two views, the import copy, and the own-share card in `WalkSharingButtons.swift`).
- **A file that lands mid-walk** plays when the engine next reaches that voice. `startVoice` resolves the URL at that moment (the downloads keep running, §8.6). The card host resolves a card's media once per card and again after each finished recording:

```swift
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
> Pilgrim/Scenes/ActiveWalk/ActiveWalkView+Honor.swift:118-133@7c200bf

  So a photo card already showing its placeholder doesn't fill in when the photo lands. It does when the card next comes to the top.
- **The arrival card** counts only the voices that played (`voicesHeard: heardVoiceIDs.count`, `ActiveWalkViewModel+Honor.swift:254@7c200bf`), so a share walked without its voices arrives with `"one place passed"`, `"N places passed"`, or `"the whole way, in their steps"` (`WayPlaceCard.swift:420-433@7c200bf`). The title is `"you walked their way"` and the second line is the share's title, as for an own walk.

#### 10.4 What is the same on a share

The ghost line, the companion, the pins (fog until heard, then stone), the camera, the card queue and its retirement, the listening chip, the stats sheet's `"Remaining"`, the glance strings (own-walk spec D §10), the arrival waypoint (`"Walked their way: <shared title>"`, `signpost.right.fill`), and every haptic. Own-walk spec E and D apply unchanged. The arrival label carries the share's title as the importer built it: trimmed of whitespace and capped at 80 per place, with no other filtering (§Resolutions 8).

### 11. Replies to a shared Way

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

    /// The walker's earlier reply to `voice`, from a previous honoring of the
    /// same Way. A mapping whose recording is gone reads as no reply at all —
    /// `mediaURL(for:)` returns nil for a file that isn't there.
    func existingReplyURL(for voice: WayMoment) -> URL? {
        guard let way, let n = Self.originIndex(of: voice),
              let relative = honorSenses.store().replies(for: way.id)[n] else { return nil }
        return mediaURL(for: .recording(relativePath: relative))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:20-50@7c200bf

The code path is the same as for an own walk (own-walk spec D §7). Four things differ in effect:

1. **A reply on the first honoring of a share is filed.** The share's folder exists from the moment it was accepted (`store.save(way)` at import, §6.3), so `setReply`'s write into an existing folder succeeds. This is the opposite of the own-walk first honoring, where the folder doesn't exist yet and the reply is lost (own-walk spec A-D1, [pilgrim-ios #98](https://github.com/walktalkmeditate/pilgrim-ios/issues/98)). Android's `WayStore.setReply` already refuses to create the folder (`WayStore.kt:166-181@636cf5ce`), which gives exactly this split. A shared-walk test should pin that the first honoring's reply is filed.
2. **The voice index.** For a share, `voice-<n>` numbers the manifest's voice and ambience encounters that carry a media index, in **manifest order**, before the moments are sorted by fraction (`voiceN += 1`, `WayImporter.swift:147-149@7c200bf`). It is not the manifest's own `n` (that names the file, `audio/<n>.m4a`). A share's manifest is fixed once shared, so the keys stay stable across re-imports of the same link. The own-walk drift A-D3 (keys shifting after a recording is deleted) has no shared counterpart.
3. **Expiry doesn't touch replies.** An expired share that was walked keeps `way.json`, `replies.json`, and its links; the sweep removes only `media/` (`WayStore.swift:226-235@7c200bf`, S3). Reply recordings live in `Documents/Recordings`, not in `media/`, so `"your reply"` and `"record again"` are still offered when the walker re-walks an expired share without its voices. A pin-opened card for a swept voice therefore shows a dead play button beside a live `"your reply"`.
4. **Deleting the share removes the mapping**, but not the recordings (§5.2).

Replies never leave the phone. The only readers of `replies.json` are the card (`ActiveWalkViewModel+Replies.swift:48@7c200bf`) and the summary (`WalkSummaryView.swift:748@7c200bf`). Nothing sends a reply back to the sharer.

The reply controls' strings and labels (`"reply here"`, `"record again"`, `"Replace your earlier reply?"` with `"Replace"` / `"Keep it"`, `"your reply"`, `"recording your reply here"`, and the four VoiceOver labels) are own-walk spec E §8 (`WayPlaceCard.swift:207-244@7c200bf`). They have no shared branch.

### 12. After the walk: summary, journal, seal, prompts

#### 12.1 The summary section

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

There's no shared branch: one section, own-walk spec G §2. For a walk that honored a share:

| The share's state when the summary opens | Kicker | Title | Delta line | Counts line | Ghost line on the map |
|---|---|---|---|---|---|
| Stored (any media state) | `"in their steps"` | the share's title, for example `"Rúa Nova → Praza do Obradoiro"` | `"they arrived N minute(s) after you"` / `"… before you"` / `"you arrived together"`, only if the walk arrived | `"N voice(s) along the way"` and/or `"N reply/replies"` | yes |
| Expired and walked (media swept) | same | same | same (the link survives the sweep) | same: `voiceCount` counts the Way's declared voices whether or not their files were ever downloaded or have been swept | yes |
| Deleted in Settings | `"in their steps"` | `"a way that has been removed"` | none (the link went with it) | none (no Way → 0 voices; no link → no replies) | no |

- **"N voices along the way" counts declared voices, not heard or downloaded ones.** A share walked with all twelve voices missing still reads `"12 voices along the way"` (the arrival card's `voicesHeard` is the heard count, §10.3).
- **"N replies" counts every entry in the Way's `replies.json`.** That includes replies made on other walks of the same share (own-walk spec A-D6, [pilgrim-ios #99](https://github.com/walktalkmeditate/pilgrim-ios/issues/99)). It applies to shares in full, since their first honoring files replies (§11).
- The summary reads the store three times in `init` (`WalkSummaryView.swift:742-755@7c200bf`). Android hops to IO.

#### 12.2 Journal, scenery, milestones, seal

- **Journal glyph, scenery staffs, milestones.** These read only the walk's own events and waypoints (own-walk spec G §4, §5, §7), so a shared honoring and an own honoring look identical. Deleting or sweeping the share changes nothing.
- **Seal watermark.** It draws the Way's line from the store, through the walk's link:

```swift
        let isHonor = walk.workoutEvents.contains { $0.eventType == .honorMode }
        self.wayPoints = isHonor
            ? walk.uuid.flatMap(store.way(forWalk:))?.route.map { (lat: $0.lat, lon: $0.lon) }
            : nil
```
> Pilgrim/Models/Seal/SealInput.swift:51-54@7c200bf

  An expired, walked share keeps `way.json` and its link, so its line stays. A deleted share loses the link. Android drops the line then, while iOS's cached seal keeps it (owner decision 3, [pilgrim-ios #111](https://github.com/walktalkmeditate/pilgrim-ios/issues/111)).

#### 12.3 The prompt lexicon: one form for own and shared walks (confirmed)

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
```
> Pilgrim/Models/Prompt/PromptAssembler.swift:170-181@7c200bf

```swift
    private static func sharedWalkLexicon(_ story: HonorStoryContext) -> String {
        var text = sharedWalkBaseText
        if let title = story.wayTitle { text += " The Way: \(title)." }
        text += story.arrived ? " The end of the Way was reached." : " The Way was left before its end, which the practice honors too."
        return text
    }
```
> Pilgrim/Models/Prompt/PromptAssembler.swift:196-201@7c200bf

```swift
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
> Pilgrim/Scenes/Prompts/PromptListView.swift:228-236@7c200bf

- The only split is stage against non-stage (`story.routeName == nil`). The function is named `sharedWalkLexicon`, but it serves own walks and shares alike. That confirms own-walk spec G §8 and correction 21: there is one Honor lexicon for both.
- A share's title enters verbatim, arrow and all: `" The Way: Rúa Nova → Praza do Obradoiro."`. With the share deleted, the sentence is dropped, leaving the base text plus the arrival sentence.
- An expired, walked share keeps its title in prompts.
- The prompt never names the sharer, because there's no name to give (§1).
- Android has no Honor lexicon yet (`app/src/main/java/org/walktalkmeditate/pilgrim/core/prompt/PromptAssembler.kt:283-287@636cf5ce` has wander and seek). U23 adds the one form.

### 13. Accessibility

The surfaces this cluster adds carry no accessibility modifier at all: `git grep -n -i accessib` on `HonorWaysSheet.swift`, `WaysListView.swift`, `DataCard.swift`, and `SettingsCardStyle.swift` finds nothing. On the overview the only one is Begin's label (`HonorOverviewView.swift:285@7c200bf`, own-walk spec F §17.5). There are **no hints, no custom grouping, and no announcements**. Where no label is set, VoiceOver reads the visible text, or a button's label contents.

#### 13.1 Data card row

- A `NavigationLink` whose label is the `settingNavRow`. It reads as `"Ways"` plus its detail (for example `"2 ways · 3.4 MB"`), with no explicit label, value, or hint.
- The detail starts as `""` and fills in on appear.
- Android's `SettingNavRow` sets `onClickLabel = label` (`SettingsCardStyle.kt:256@636cf5ce`), so TalkBack adds "double tap to Ways". That's the existing Android pattern for every Settings row.

#### 13.2 Settings → Ways

- Navigation title `"Ways"`.
- Each Way row is two plain `Text`s (title, detail). Rows have no button trait, because they aren't tappable. The detail's `"·"` is inside one string, so it is read inline.
- Delete is the system swipe action from `.onDelete`, which VoiceOver offers as the row's "Delete" action. iOS adds no label to it.
- `"Delete all Ways"`: a Button with the destructive role. The alert's title, message, `"Delete"` and `"Cancel"` are system-accessible.
- `"no ways yet"` and the package footer are plain text.
- Order: title, the empty copy or the rows top to bottom, `"Delete all Ways"`, the footer.

#### 13.3 Ways sheet, "Shared with you"

- Section header `"Shared with you"`.
- Each row is a Button whose label is its contents: title, date, `"·"`, counts or `"voices returned to the trail"`. The `"·"` is a separate `Text` inside the label (VoiceOver may voice it), as on the overview's stats row (own-walk spec F-6, [pilgrim-ios #108](https://github.com/walktalkmeditate/pilgrim-ios/issues/108)).
- There's no chevron and no hint that the row opens an overview.
- The empty copy is plain text.

#### 13.4 Ways sheet, "From a shared walk"

- Section header `"From a shared walk"`, then footer `"A walk someone shared with you, from walk.pilgrimapp.org."`.
- The text field: the placeholder `"paste a walk link"` serves as its label (SwiftUI's `TextField` title), with no hint about the accepted forms.
- `"Open"`: a Button, dimmed while the text doesn't parse or a fetch is in flight. There's no explanation of why.
- The import line: plain text. **Its appearance and changes are not announced.** A VoiceOver walker who taps "Open" hears nothing for `"reaching for the walk…"` or for a failure; they have to find the line by swiping (§Defects 8).
- Order: header, field, Open, (line), footer.

#### 13.5 The overview while gathering

- The import line: plain text, never announced. `"gathering their voices · N%"` changes without notice, as does the switch to `"some voices didn't arrive"`.
- `"try again"` and `"walk without the missing voices"`: Buttons labelled by their titles, each at least 44 pt tall, no hints.
- Begin: `"Begin honoring this way"`, dimmed while gathering or fetching, with no reason given. The visible cue is the fog background.
- The order is own-walk spec F §17.5, with the line and its two buttons after the counts.

#### 13.6 Preview and walk

- **Preview of a missing voice.** The `"waveform.slash"` image is not hidden, then `"their voice is still on its way here"`.
- **Walk card.** The header Button's label is `"Show this place on the map"` or `"Back to where you are"` (`WayPlaceCard.swift:62@7c200bf`). It replaces the header's contents, so VoiceOver never reads the kicker or the subline. On a share that means the street name (§10.1) and the "about N minutes" estimate (§10.2) are never spoken. That's own-walk spec E-3 ([pilgrim-ios #108](https://github.com/walktalkmeditate/pilgrim-ios/issues/108)), with a shared-only loss of content.
- **Meditation caption.** `accessibilityLabel(text)`: `"they sat here N minutes"`, without the estimate.
- **Summary.** As own-walk spec G §11. `"a way that has been removed"` is plain heading text.

Android adds no live region where iOS announces nothing. Whether TalkBack should hear the import line is a gate decision (R5/R6), and the gate decides it with own-walk spec F resolution 7's posture: don't copy a gap silently (§Open questions 4).

### 14. Android at `636cf5ce`, in one place

| iOS surface | Android today | Unit |
|---|---|---|
| Data card `"Ways"` row | absent; `DataCard` has only `"Export & Import"` (`ui/settings/data/DataCard.kt:15-31`) | U28 |
| Settings → Ways list | absent | U28 (`ui/settings/data/WaysListScreen.kt`) |
| `WaysListModel` (`listable`, `rowDetail`, `packageFooter`) | absent | U28; `packageFooter` is Stage 21-2 |
| `WayStore.list/delete/hasMedia/diskUsage/replies/setReply` | present (`data/honor/WayStore.kt:109-181`); size is logical, not allocated (§2.3) | U14, done |
| Ways sheet, both shared sections | absent; `ui/honor/` doesn't exist at this HEAD | U21 (sheet), U28 (sections) |
| `HonorImportState`, `HonorImportReducer`, `HonorImportCopy` | absent | U28 |
| Overview import line, retry, walk-without | absent | U28 |
| Card subline with `place`; estimate kicker | the model fields exist (`domain/honor/Way.kt:93-108`); no card yet | U22 |
| Honor prompt lexicon | absent (`core/prompt/PromptAssembler.kt:283-287` has wander and seek) | U23 |
| `honor_arrival_label` "Walked their way: %1$s" | present (`res/values/strings.xml:1187`) | U14, done |

Paths are relative to `app/src/main/java/org/walktalkmeditate/pilgrim/` (`res/` to `app/src/main/`), all `@636cf5ce`.

### Resolutions for the plan

1. **Where the Ways row lives** (§2). It's a row on the Settings screen's Data card, between `"Export & Import"` and `"Maps"`, reading `"Ways"` with the detail `"N way(s) · N.N MB"`. iOS's Export & Import screen has no Way content, so U28's change to `DataSettingsScreen.kt` has nothing to carry. The row is always shown, including `"0 ways · 0.0 MB"`.
2. **Settings → Ways** (§3–§5).
   - One plain list: `"no ways yet"` when empty; one row per own-walk or shared Way (stages excluded), newest acceptance first, each with its title and `"<medium date> · N.N MB"` or `"<medium date> · voices returned to the trail"`; then `"Delete all Ways"`; then the stage footer (Stage 21-2).
   - Every appearance and every delete sweeps first, then re-reads.
   - Swipe-to-delete is **unconfirmed**. "Delete all" is confirmed: `"Delete all Ways?"`, `"Their voices and photos leave this phone. Your own walks are untouched."`, `"Delete"` (destructive) and `"Cancel"`.
   - Each delete cancels the Way's downloads, then removes its folder and every link to it. The walks' reply recordings and their honor events stay (§5.2).
   - The size is decimal MB with one decimal under `Locale.US`, and the dates are medium style in the phone's zone.
3. **The plan's "hidden while a walk is active or awaiting its Honor finalize step" has no iOS counterpart.** iOS never hides the row or the list. Settings simply can't be reached during a walk (the walk is a full-screen cover, §2.1), and a finalize step doesn't exist. On Android it's platform hardening: record it at the gate under R6, with this section as the evidence. The safety it buys, never deleting a Way an unfinished walk still needs, is real on Android, because its finalize step outlives the walk screen.
4. **AE3's place-card clause is not iOS behavior** (§10.3, §8.6). On iOS no walk surface ever reads `"voices returned to the trail"`:
   - A voice whose file is gone is skipped silently.
   - A pin-opened card shows an empty waveform and a play button that does nothing.
   - A photo shows its parchment placeholder.

   The words appear only in the Ways sheet's row and the Settings list (§4, §6.4). AE3's summary clause holds, and more is kept than it says: an expired, walked share's summary keeps its ghost line, its replies count, its title, its delta, and its "N voices along the way" (§12.1). U28's AE3 test should assert:
   - the two list rows;
   - the summary;
   - a silent skip on the walk (no card, not heard).

   It should not assert card copy.
5. **The overview's shared states** (§8). The table in §8.3 is the full contract, line, colour, buttons and Begin, for `.idle`, `.gathering`, `.ready`, `.mediaMissing`, `.failed(.diskFull)`, and a second link's `.fetching` and `.failed(...)`. Corrections to U28's approach:
   - **Begin is disabled only while gathering or fetching.** With `"some voices didn't arrive"` showing, Begin is already enabled. `"walk without the missing voices"` doesn't unlock it; it clears the line and both buttons and stops observing the downloads. The test "walk without the missing voices lets Begin proceed" should instead pin "Begin is enabled in `.mediaMissing`, before and after the tap", and "the tap hides the line and both buttons".
   - **No state shows which voices are missing.** The plan's "a partial gather shows which voices are missing" is not iOS. A partial gather shows exactly `"some voices didn't arrive"` plus the two buttons. The only per-moment sign is the preview's `"their voice is still on its way here"`.
   - **"try again" cancels and re-downloads what's missing**, going back to `"gathering their voices · N%"` from the seeded percentage. Ceiling-refused files always fail again.
   - **Disk full** shows `"not enough space on this phone to save these voices"` in rust, with no buttons. Begin is enabled even while the Way's other files are still downloading.
   - **The percentage counts files** (photos included) and rounds half away from zero.
   - **Gathering has no cancel.** Close stops the observation, not the transfers.
   - Android's first frame must not show an enabled Begin before the first gathering state (§8.1).
6. **The Ways sheet's shared sections** (§6–§7).
   - **"Shared with you":** shared Ways only, newest acceptance first, swept on every appearance. The empty copy is `"no ways yet. Accept a shared walk, or walk one of yours again."`.
   - **Each row** is a button: the title (body, ink), then `"<medium date> · <counts or 'voices returned to the trail'>"` (caption, fog). No chevron.
   - **A share is accepted the moment its fetch succeeds**, whether or not it is ever walked, and stays listed until it's deleted or expires unwalked.
   - **A row hands over the stored Way with no fetch**, so an expired, walked share is still walkable from its row.
   - **"From a shared walk":** the field `"paste a walk link"` (body font, no auto-capitalization, no autocorrect, never pre-filled, never cleared); `"Open"`, enabled when the text parses and no fetch is in flight; the line under it (fog `"reaching for the walk…"`, or the rust failure copy of §7.3); and the footer `"A walk someone shared with you, from walk.pilgrimapp.org."`.
   - **No toast** while the sheet is up. On success the sheet closes, then the overview opens.
7. **No sharer, no expiry, no link on any screen** (§1). The plan must not invent a `"by <sharer>"` line or an `"expires in N days"` line; iOS has neither, and the manifest carries no sharer name. "They" stays anonymous everywhere.
   - The prompt lexicon is **one form** for own and shared walks, split only from the stage form (§12.3). That confirms own-walk spec G §8 and correction 21.
   - The summary, journal, scenery, milestones, and seal have no shared branch (§12).
8. **The sharer's title, unfiltered, at every display site** (for the plan's open decision on control and bidirectional characters, which is S1's to make).
   - The title is built once at import: trimmed of whitespace and newlines, capped at 80 characters per place, joined with `" → "`.
   - Nothing downstream filters it. It reaches the Ways sheet row, the Settings row, the overview title, the arrival card, the summary title, the prompt text, and, persisted into the walk itself, the arrival waypoint label `"Walked their way: <title>"`. The label outlives deleting the Way.
   - Whatever Android decides, filtering once at import keeps all eight sites in agreement. Filtering at any one display site would leave the persisted waypoint label unfiltered.
9. **Replies to a share file on its first honoring** (§11). The share's folder exists from acceptance, so `setReply` succeeds where an own walk's first honoring fails (A-D1). Android's `setReply` already refuses to create a folder, which reproduces the split, and U28 (or U22) should pin it with a shared-Way test. Expiry keeps replies, and deletion removes the mapping but not the recordings.
10. **Shared-walk branches for U22** (§10).
    - The card subline appends the street name: `"here · <place>"`, `"<distance> away · <place>"`, or `"<place>"` alone before the first fix.
    - An estimated sitting reads `"they sat here about N minutes"`, while the meditation screen reads `"they sat here N minute(s)"` without "about".
    - An unlabelled shared waypoint has an empty kicker; a missing or unknown icon draws `mappin`. Its body is always `"A place they marked."`.
    - A missing rest length reads `"they rested here 0 minutes"`, and a missing voice length reads `"0:00 / 0:00"`.
    - Missing media is silent.
    - A card resolves its media once per appearance at the top of the queue.
    - The arrival card's voices count only what played.
11. **Shared-walk branches for U21** (§9).
    - The overview title is `"<start> → <end>"`. The departure line is long date and short time in the **phone's** zone; the preview's hours use the **Way's** zone.
    - The distance is the shared route's length. The duration falls back to the route's span.
    - The voices toggle is enabled whenever the Way declares voices, downloaded or not.
    - The preview subline appends the street name. A missing voice reads `"their voice is still on its way here"`, and a missing photo shows the placeholder above `"tap the photo to see it whole"`.
12. **Shared-walk branches for U23** (§12).
    - The summary's three cases (stored, expired-and-walked, deleted) are in §12.1, and `"N voices along the way"` counts declared voices.
    - The seal keeps the Way's line through expiry and loses it on deletion (Android).

### iOS defects found

Filed on 2026-10-01 as listed in "Matched as shipped, and filed upstream" at the top of this spec. Where an existing issue already covers the family, the candidate is marked as an addition to it.

1. **"voices returned to the trail" is keyed on the media folder, not on expiry, so it misfires for shares too** (addition to [pilgrim-ios #109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109), next to A-D4). A share accepted but never downloaded, because the walker closed the overview early, was offline, or every transfer failed, reads as returned to the trail on both lists. A share with one file of thirty-two downloaded reads as complete.

```swift
                Text(withMedia.contains(way.id) || way.voiceCount + way.photoCount == 0
                     ? HonorOverviewModel.countsLine(way: way) : "voices returned to the trail")
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:135-136@7c200bf

```swift
        if way.voiceCount + way.photoCount > 0, !WayStore.shared.hasMedia(id: way.id) {
            return "\(lead) · voices returned to the trail"
        }
```
> Pilgrim/Scenes/Settings/WaysListView.swift:108-110@7c200bf

   Impact: a walker who accepted a share yesterday is told its voices are gone when they were never fetched. Meanwhile a share missing most of its voices looks whole.

2. **An expired, walked share has no "returned to the trail" copy where the walker actually meets it, and iOS re-downloads its swept media.** The row still opens the overview (§6.5). `gather` → `download` enqueues every swept file again, with no `expires` check, so the overview shows `"gathering their voices · 0%"`. What follows depends on the worker: either `"some voices didn't arrive"`, or the files land and the next sweep deletes them. The preview says `"their voice is still on its way here"`.

```swift
        let files = declared.filter { !FileManager.default.fileExists(atPath: store.mediaURL(for: way.id, relative: $0).path) }
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:129@7c200bf

```swift
                    Text("their voice is still on its way here").font(Constants.Typography.body).foregroundColor(.fog)
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:148@7c200bf

   Impact: the walker is offered a retry for voices the sharer withdrew, and possibly re-fetches them against the sharer's expiry. The voices aren't "on their way here".

3. **A full disk while saving a fetched manifest reads as "couldn't reach the walk".** The importer's `store.save(way)` throws a Cocoa error, which `openWay` maps to `.unavailable`.

```swift
        try store.save(way)
```
> Pilgrim/Models/Honor/WayImporter.swift:84@7c200bf

```swift
                let failure = (error as? WayError) ?? .unavailable
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:236@7c200bf

   Impact: low. The walker checks their connection when the phone is full; the media path names the disk-full case correctly.

4. **A second link's fetch window doesn't hold Begin disabled on a shared overview.** `openWay` sets `.fetching` but leaves the shown Way's download sink live, so any downloader change for any Way recomputes the state, overwriting `.fetching` and any failure line from that fetch.

```swift
        importTask?.cancel()
        honorImportState = .fetching
```
> Pilgrim/Scenes/Root/MainCoordinatorView.swift:214-215@7c200bf

   The comment it contradicts is `HonorOverviewView.swift:292-295@7c200bf` (§8.3). Impact: low. A Begin tapped in the window wins over the fetch, which is then dropped silently (`MainCoordinatorView.swift:232@7c200bf`). A second link's failure line can vanish before it's read.

5. **The overview's departure line and the preview's hours use different time zones.** The card formats `departedAt` in the phone's zone; the preview formats each moment's hour in the Way's `tzIdentifier`.

```swift
            Text(WayStageLine.line(for: way)
                 ?? DateFormatter.localizedString(from: way.departedAt, dateStyle: .long, timeStyle: .short))
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:232-233@7c200bf

```swift
            formatter.timeZone = way.tzIdentifier.flatMap(TimeZone.init(identifier:)) ?? .current
```
> Pilgrim/Scenes/Honor/WayMomentPreview.swift:58@7c200bf

   Impact: low. A share walked in Lisbon and opened in Tokyo says it departed at 4:41 PM, while its first voice was spoken "8:41 AM". The Ways sheet and Settings dates use the phone's zone too. (Own-walk spec F-8 is the converse case for own walks.)

6. **The Data card counts Ways the list is about to sweep.** The card reads `list()` without sweeping, and the list sweeps on appear.

```swift
            let listed = WaysListModel.listable(WayStore.shared.list())
```
> Pilgrim/Scenes/Settings/SettingsCards/DataCard.swift:36@7c200bf

   Impact: low. A share that expired unwalked during the session shows on the card ("1 way") above a list without it, which is the mismatch the card's own comment set out to prevent.

7. **The meditation screen drops "about" from an estimated sitting.** The card says `"they sat here about 12 minutes"` and the screen it opens says `"they sat here 12 minutes"`.

```swift
        let text = "they sat here \(minutes) \(minutes == 1 ? "minute" : "minutes")"
```
> Pilgrim/Scenes/ActiveWalk/MeditationView.swift:401@7c200bf

   Impact: low. An estimate from a downsampled route gap is presented as the sharer's actual sitting. (A copy addition to [pilgrim-ios #109](https://github.com/walktalkmeditate/pilgrim-ios/issues/109).)

8. **The shared surfaces are quiet to VoiceOver** (addition to [pilgrim-ios #108](https://github.com/walktalkmeditate/pilgrim-ios/issues/108)).
   - The paste field's result line and the overview's gathering and failure lines appear and change without an announcement. A walker who taps "Open" gets no spoken result.
   - The shared row's label includes a separate `"·"`.
   - On the walk card the header's button label hides the street name and the "about" estimate (E-3).

```swift
                    if let line = HonorImportCopy.line(for: importState) {
                        Text(line)
                            .font(Constants.Typography.caption)
                            .foregroundColor(isFailure ? .rust : .fog)
                    }
```
> Pilgrim/Scenes/Honor/HonorWaysSheet.swift:67-71@7c200bf

   Impact: a VoiceOver walker can't tell whether a pasted link worked, or when a gathering finished, without hunting for the line.

9. **"try again" can never recover files refused by the ceilings.** `download` re-applies the 12-audio and 20-photo ceilings to the whole manifest and records the excess as failures on every retry, so `"some voices didn't arrive"` and `"try again"` return each time.

```swift
        let (declared, refused) = Self.withinCeilings(Self.mediaFiles(for: way))
        let files = declared.filter { !FileManager.default.fileExists(atPath: store.mediaURL(for: way.id, relative: $0).path) }
        failures[way.id] = refused.isEmpty ? nil : refused
```
> Pilgrim/Models/Honor/WayMediaDownloader.swift:128-130@7c200bf

   Impact: low. Only a manifest no Pilgrim share page makes (the ceilings match the sharing side's own caps) gets here. The button promises something it can't do.

10. **An unlabelled shared waypoint shows an empty kicker.** The importer turns a missing label into `""`, not nil, and the header renders it as an empty `Text` above the place copy.

```swift
                    kind: .waypoint(label: capped(e.label, maxLabelCharacters), icon: capped(e.icon, maxIconCharacters, or: "mappin"))))
```
> Pilgrim/Models/Honor/WayImporter.swift:160@7c200bf

    Impact: low. The card's first line is blank. Android's owner decision 4 ("a waypoint with no label shows no kicker") is about Android's own nullable labels; for a shared `""`, matching iOS means rendering the same empty slot.

### Open questions

1. **Ways' size: allocated or logical?** iOS reports allocated bytes (§2.3); Android's `WayStore.diskUsage` reports file lengths. The difference is at most one block per file, which on a full share can flip the first decimal of `"N.N MB"`. Should Android match allocated size (`Os.stat(...).st_blocks × 512`), or record the difference at the gate?
2. **Whether Android's Settings is reachable while a walk is active or its finalize step is pending.** That decides whether the plan's hide (Resolution 3) is needed or merely defensive. iOS can't tell us; it has no such state.
3. **Does the worker still serve an expired share's media files?** That decides what an expired, walked share's overview shows after `"gathering their voices · 0%"`: a lasting `"some voices didn't arrive"`, or files that land and are swept again (§8.6, §Defects 2). It's a worker fact, outside the iOS pin. S3 may answer it from the worker side.
4. **TalkBack and the import line.** iOS announces nothing (§13.4–§13.5). R5/R6 should decide whether Android matches the silence or adds a polite live region, and record the choice at the gate. Own-walk spec F resolution 7 set the posture for the same kind of gap: don't copy it silently.
