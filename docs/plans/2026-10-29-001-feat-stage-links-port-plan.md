---
title: "feat: Honor — stage links, iOS main @ 3b78728 parity, Android 2.1.0 (Phase 22)"
type: feat
status: completed
date: 2026-10-29
origin: docs/parity/2026-10-28-stage-links-port.md
---

# Stage Links Port Implementation Plan (Phase 22, Android 2.1.0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A tapped `https://honor.pilgrimapp.org/<routeId>/<n>` link opens that stage the way tapping it on the route page does: its overview when the route is on the phone, otherwise the route page with the stage centred and "Download this route first?" raised; any other honor-host path says "this link needs a newer Pilgrim".

**Architecture:** One parser turns a link into a `HonorLinkDestination` (share, stage, or unrecognised). A pure `StageLinkResolver` names the screen. `HonorImportCoordinator.openStage` runs iOS's catalog loop on the same job as share imports, so the newest link of either kind wins, and publishes either a stage Way id (the existing `fetched` slot) or a `HonorRouteLink` (a new `routeLink` slot), each clearing the other. The nav host's existing landing, which already re-runs on every back-stack change, opens a new dialog destination `honor_route_link/{routeId}?focus={n}` over Path, or parks it behind a summary. No iOS AF60 park slots are ported: one `navigate` with its pops swaps the back stack in a single step, so there is no window while a sheet is mid-close.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 `ModalBottomSheet`), Navigation Compose 2.9.8 `dialog` destinations, Hilt, coroutines + `StateFlow`, JUnit 4 + Robolectric (SDK 34) + Compose UI test.

**Spec:** `docs/parity/2026-10-28-stage-links-port.md` (the approved parity spec, with its "Owner decisions (2026-10-29)"). The 14 approved answers from `docs/parity/.stage-links-answers.md` are folded into § Decisions and into the tasks below, so this plan stands alone.

> **Authority order:** (1) the parity spec, Swift quotes pinned at iOS `3b78728`, including its "Owner decisions (2026-10-29)"; (2) the 14 approved answers, as folded here (Q6 decided as option A: no toast over the walk summary, as iOS); (3) the iOS source at `3b78728` in `../pilgrim-ios`, read-only (`git -C ../pilgrim-ios show 3b78728:<path>`). This plan outranks memory. Where this plan and the Android code disagree with the spec, the spec rules (see § Where the spec and the code disagree).
>
> **Unit numbering:** Phase 22 (Android 2.1.0). This plan is U1–U12. The iOS #91 (temple stamp hours) plan for the same phase starts at U20, so a commit or PR that cites "Phase 22 U5" is unambiguous.
>
> **Path shorthand:** `P/` is `app/src/main/java/org/walktalkmeditate/pilgrim/`, `T/` is `app/src/test/java/org/walktalkmeditate/pilgrim/`, `R/` is `app/src/main/res/`. Android line numbers are at `0ee2872` and drift as earlier tasks land, so each edit also names the symbol it changes; iOS paths are at `3b78728`.

## Global Constraints

- **Toolchain:** JDK 17. If `./gradlew` fails on the Java version, run `export PATH="$HOME/.asdf/shims:$PATH"` first.
- **Commands (the repo's own; there is no formatter task, so match the surrounding style):**
  - one test class: `./gradlew :app:testDebugUnitTest --tests '<fully.qualified.ClassName>'`
  - before every commit: `./gradlew :app:testDebugUnitTest` (docs-only commits excepted: U1 runs `./gradlew :app:processDebugMainManifest`, U12 nothing)
  - before the commit of a task that touches `R/` or a composable (U4, U6, U7, U9, U10, U11): also `./gradlew lintDebug`
  - after U11, the three CI jobs: `./gradlew assembleDebug lintDebug testDebugUnitTest` and `./gradlew compileReleaseUnitTestKotlin testReleaseUnitTest --tests 'org.walktalkmeditate.pilgrim.core.flags.*'`
- **Branch:** `feat/stage-links`, off `main`, checked out in the worktree `/Users/rubberduck/GitHub/momentmaker/pilgrim-android-stage-links`; every command runs there, never in the main checkout. Never push, open a PR, or release without the owner's word.
- **Commits:** one per task. End every message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never `--no-verify`. Never a bare `git stash`.
- **Release flag:** everything sits behind `ReleaseFlags.honor` (`P/core/flags/ReleaseFlags.kt`, `BuildConfig.HONOR`, on in both builds since the 2.0.0 flip `30989b04`). Flag off: the router and the coordinator do nothing, no Honor route is registered, and `LocalHonorLinkToast` is null.
- **Files:** every new Kotlin file starts `// SPDX-License-Identifier: GPL-3.0-or-later`. No OutRun mention anywhere.
- **Citations:** new KDoc cites iOS as `File.swift:lines@3b78728`; existing KDoc keeps its `@7c200bf` pins.
- **Hosts:** `HonorLink.HOSTS = setOf("honor.pilgrimapp.org", "walk.pilgrimapp.org")`; stage links only on `HonorLink.STAGE_HOST = "honor.pilgrimapp.org"`. The host is lowercased; path segments never are.
- **Share id:** `\A[A-Za-z0-9_-]{10}\z` (Kotlin `Regex("[A-Za-z0-9_-]{10}")` with `matches`, unchanged).
- **Stage number:** `\A[1-9][0-9]{0,2}\z` (Kotlin `Regex("[1-9][0-9]{0,2}")` with `matches`, ASCII digits only), and `n <= PilgrimageWayImporter.MAX_STAGE_COUNT` (200) inclusive. `/1` → index 0, `/200` → 199, `/201` → unrecognised. The link counts from 1; the data counts from 0: `index = n - 1`.
- **Route id:** `WayStore.isValidRouteId`, `\A[a-z0-9-]{1,64}\z` (unchanged). Stage Way id: `WayStore.stageWayId(routeId, index)` = `"pilgrimage:<routeId>:<index>"`.
- **Route page identity:** `HonorRouteLink.id = "<entry.id>:<focus or ->"`; focus is in range only when `index in 0 until entry.stageCount`, else null.
- **Catalog loop:** cached in-memory catalog first; `NeedsCatalog` loads unforced; `NotInCatalog` forces exactly one load; "reaching for the route…" before each load. No catalog and the route missing: loads `[false, true]`.
- **Toast:** one at a time, `HonorLinkRouter.TOAST_MILLIS = 5_000L`, each new one cancels the previous expiry, null clears at once (unchanged). It takes no taps.
- **Copy, verbatim (lowercase, `…` is the single character U+2026, the apostrophe is ASCII):**

  | Resource key | Text | iOS |
  |---|---|---|
  | `honor_link_needs_newer_pilgrim` (new) | `this link needs a newer Pilgrim` | `MainCoordinatorView.swift:231@3b78728` |
  | `honor_link_reaching_for_route` (new) | `reaching for the route…` | `MainCoordinatorView.swift:299@3b78728` |
  | `honor_link_route_not_in_pilgrim` (new) | `this route isn't in Pilgrim yet` (XML: `this route isn\'t in Pilgrim yet`) | `MainCoordinatorView.swift:327@3b78728` |
  | `pilgrimage_error_catalog_unreachable` (existing, via `PilgrimageCopy.line`) | `the routes are out of reach right now` | `MainCoordinatorView.swift:307@3b78728` |
  | `honor_link_finish_walk_first` (existing) | `finish this walk first` | `MainCoordinatorView.swift:275@3b78728` |
  | `honor_close` (existing) | `Close`, in `pilgrimType.button`, `pilgrimColors.stone` | `MainTabView.swift:85-87@3b78728` |
  | `pilgrimage_download_first_title` / `_message` (existing) | `Download this route first?` / `Its stages have to be on your phone before you can walk one.` | `PilgrimageRouteView.swift:185-192@3b78728` |

- **Magic numbers stay literal (Q12):** the toast's `RoundedCornerShape(8.dp)` and `alpha = 0.95f` (`P/ui/honor/HonorLinkToastView.kt:67-68`) and "try again"'s `heightIn(min = 44.dp)` (`P/ui/honor/pilgrimage/PilgrimageRouteScreen.kt:373`) are not touched.
- **Accessibility:** add no announcement or `liveRegion`; iOS posts none.

## Review Focus

1. A stage link pasted or tapped with punctuation from the message around it (`…/14.`, `…/14)`, `(…/14)`): the paste opens nothing (the field says so), and a tapped one says "this link needs a newer Pilgrim". Tests in U2.
2. Rotating the phone, or Android restoring the app, after answering "Not now" on the link page's download prompt: the prompt does not come back and the list does not jump again. Test in U9.
3. The same link tapped a second time while its page is up: the page, its scroll and its already-answered prompt stay as they are. Tests in U6 (entry kept) and U9 (latch).
4. A link to a stage the installed, older release doesn't have: the page opens with no prompt and no scroll, and the Update button is the answer. Test in U9.
5. A link that arrives while the walker is deep in another tab (Settings → Data → Maps): the page opens over Path, Close lands on Path, and the Settings tab keeps its place. Test in U6.

---

## Decisions (the 14 approved answers, folded)

The idea behind them: Android needs no port of iOS's AF60 park slots. SwiftUI drops a sheet asked for while another is closing, so iOS parks the request and presents it from the closing sheet's `onDismiss`. On Android one `navigate { popUpTo(...) }` swaps the back stack in a single step. Share links already rely on this (`openStoredWayOverview`, `openFetchedWayOverview`, `P/ui/navigation/PilgrimNavHost.kt:1147-1166`). The one real park is behind a summary (owner decision 5), and it re-runs on every back-stack change (`PilgrimNavHost.kt:985-1006`), so it needs no dismiss callback.

- **Q1, the park slots → U5, U6, U7.** Add `routeLink: StateFlow<HonorRouteLink?>` beside `fetched` in `HonorImportCoordinator`. Publishing either clears the other, so at most one is set and the newest wins. Add `consumeRouteLink(link)`, a compare-and-set. Add `routeLinkLanding(backStack, parkedBehindSummary)`, the same `FetchedWayLanding` values as `fetchedWayLanding` except that a route link never `WAIT`s on the Ways sheet: walk screen up → `DROP`; in setup → `WAIT`; summary up → `PARK`; `parkedBehindSummary && overviewUp` → `DROP`; otherwise `PRESENT`. Add `NavHostController.openLinkedRoutePage(link)`: same route and focus on top → nothing; otherwise pop the overview, `HONOR_WAYS` (with the picker, catalog and route page over it) and any link page, `navigateToTab(PATH)` if needed, then navigate. `openFetchedWayOverview` also pops the link page before its `navigateToTab(PATH)`, or the tab idiom's `saveState` would save a dialog into Path's state. Delete `honorHost?.dropParkedWay()` (`PilgrimNavHost.kt:615`) and `HonorImportHostViewModel.dropParkedWay` (`P/ui/honor/HonorWaysViewModel.kt:147-150`): iOS's `walkAgain` with a nil build now keeps the park (`MainCoordinatorView.swift:420-424@3b78728`), on the share path too.
- **Q2, what promotes a parked route link → U7.** The back-stack change itself, seen by `HonorLinksLanding`'s effect, keyed on `(fetched, routeLink, backStack)`. The summary is one route, `WALK_SUMMARY_PATTERN`, whichever host opened it, so one observer covers every host. `parkedBehindSummary` is `remember` state and reads false after a rotation until the next `PARK`; harmless, since the summary is still on the stack.
- **Q3, replacement vs real close → U5, U6.** Nothing new. `overviewClosed(overview)` already does nothing unless the closing overview is the one showing (`P/honor/HonorImportCoordinator.kt:193-204`). A new link page entry gets a new ViewModel. The same route and focus on top keeps the page, as `showsStoredOverview` keeps a same-id overview.
- **Q4, the link page and its identity → U6, U9.** A separate dialog destination, `Routes.HONOR_ROUTE_LINK_PATTERN = "honor_route_link/{routeId}?focus={focus}"`, `focus` an `IntType` defaulting to `PilgrimageRouteViewModel.NO_FOCUS = -1` (the `NO_HONOR_SOURCE = -1L` precedent). Identity is route id plus focus against the top entry's arguments; a different one pops then navigates, two explicit calls, never `launchSingleTop` (which would keep the entry, its ViewModel and its old focus). Close, swipe and system Back pop only the link page, through `closeRouteLinkPage(entryId)`; `HonorSheetHost(onDismissed = close)` with no `onBack`, so Back dismisses like a swipe. Its leading control is iOS's "Close" (`R.string.honor_close`, `pilgrimType.button`, `pilgrimColors.stone`, as `P/ui/honor/HonorOverviewScreen.kt:195` draws it); the catalog page keeps its back arrow. Choosing a stage: `HONOR_ROUTE_LINK_PATTERN` joins `openStoredWayOverview`'s `replaces`. The pattern joins `HONOR_TRACKED_ROUTES`. Focus: `PilgrimageRouteViewModel` reads `ARG_FOCUS` and runs `applyFocus()` after the opening `loadStagesIfNeeded` and after every `retryStages()`, with iOS's three-part check (a focus exists, not yet applied, stages not empty); `focusApplied` lives in `SavedStateHandle`; a route not installed raises `PilgrimageRouteAlert.DOWNLOAD_FIRST`.
- **Q5, entry and release → U6, U9.** From `PilgrimageCatalogService.catalog`, as today; the route carries only id and focus. Every `load()` publishes into `catalog` (`P/data/honor/pilgrimage/PilgrimageCatalogService.kt:124-145,173-176`), so `openingState()` sees the catalog the resolver used. A link page that ends `Gone` closes itself silently (`back` bound to `closeRouteLinkPage`).
- **Q6, the toast over dialog windows → U11.** Drop the overview exclusion in `linkToastShows` (`PilgrimNavHost.kt:949`): the overview is a `composable` in the Activity window, under the existing `LinkToastHost`. Draw a second `HonorLinkToastView` inside `HonorSheetHost`'s `ModalBottomSheet`, top-aligned, fed through a `LocalHonorLinkToast` provided from `honorHost.linkToast` (null with the flag off). All Honor sheets go through `HonorSheetHost`, the link page included. `linkToastShows` is false when the top route is an Honor dialog, so one toast shows at a time. **The summary (owner, option A):** unchanged; its own sheet window covers the toast, as at iOS `3b78728`, whose summary sheet carries no `LinkToastOverlay` (`MainTabView.swift:49-54@3b78728`).
- **Q7, the tab-switch rule → U8.** Keep Android's carve-outs for both link kinds. `HonorLinkScreen.routeSheetUp` (the link page on the back stack) joins `keepsItsPlace()`, or a second link would set `pathSwitch` and the landing's `navigateToTab(PATH)` would pop the page before it could be swapped. `.Unrecognised` gets its own branch before the walk check: only the toast, no `pathSwitch`, no import call, mid-walk too. Stage links show no "reaching for the walk…" at dispatch. A paste never reaches the router.
- **Q8, where the stage job lives → U5.** In `HonorImportCoordinator`, on the shared `importJob`: cancel it, reset `Fetching` to `Idle`, launch on the coordinator's Main scope, read the installed route id once (`PilgrimagePackageManager.installedRoute()?.routeId`, the read without writes, `P/data/honor/pilgrimage/PilgrimagePackageManager.kt:218`), read the stage Way once and only for the installed route (`WayStore.load` on IO), resolve, run the catalog loop with an `isActive` check after every suspension and `CancellationException` rethrown before `catch (e: Exception)`. Four seams are constructor parameters, as iOS's (`MainCoordinatorView.swift:43-46@3b78728`). Outcomes `ReachingForRoute`, `RouteNotInPilgrim`, `CatalogFailed(PilgrimageError)`; both landings reuse `Listed`. Unlike a share failure these show with the Ways sheet up. The paste dispatches on the destination.
- **Q9, "a Begin in flight wins" → U5, U7.** `walkScreenUp`, no new signal: `screenChanged` cancels the shared job the moment the walk screen appears (`P/honor/HonorLinkRouter.kt:169`), and the landing `DROP`s on `walkScreenUp`. Not `PilgrimageWalkSignals.beginInFlight()`. "reaching for the route…" stays over the walk screen until it expires, as the share path's matched behaviour (pilgrim-ios #113).
- **Q10, the walk guard from the link door → no task.** No wiring: `PilgrimagePackageManager` and `PilgrimageTilesManager` take their walk signals at construction (`PilgrimagePackageManager.kt:120`, `P/di/PilgrimageTilesModule.kt:78`), so iOS's `wirePilgrimageManagers()` call in `openStage` is a no-op here (said in `openStage`'s KDoc). One nav test in U6: the walk screen opening pops the link page.
- **Q11, scroll and centring → U10.** A pure `PilgrimageRouteModel.stageListPosition(page, stageIndex): Int?` (items above the first stage row: `header`, `next` when installed, `stages-header`; `P/ui/honor/pilgrimage/PilgrimageRouteScreen.kt:150-171`). The ViewModel exposes a one-shot `scrollTarget`; the screen runs `LazyListState.animateScrollToCentre(position)`: `animateScrollToItem`, read the item from `layoutInfo`, `animateScrollBy` to its centre. Compose's default spring for both; no duration constant (iOS gives none).
- **Q12, magic numbers → no task.** Stay literal (Global Constraints).
- **Q13, the release target → U1.** Android 2.1.0, with iOS #91, at iOS `main` @ `3b78728`. The four first-parent PRs since `7c200bf`: #90 (`ab3f821`, store copy, no port), #92 (`10540d0`, Mapbox telemetry off, already matched in `P/ui/walk/PilgrimMap.kt:616,789-802`), #91 (`e551b11`, its own plan), #125 (`3b78728`, this plan). Move the pin to iOS's `v2.1.0` tag once it exists. The `versionName` bump belongs to the release step.
- **Q14, CLAUDE.md's backend URLs → U1.** Add the catalog index to the list; put the inbound link shapes on a line under it; fix the manifest comment.

## Where the spec and the code disagree (ruled by the spec)

- `CLAUDE.md:54-58` "Backend URLs" is a bullet list, not the table the answers name; U1 adds a bullet and a line under the list.
- The answers ask to "update the existing test that expects the nil build to drop the park". No such test exists at `0ee2872` (`grep -rn dropParkedWay app/src/test` is empty). U7 adds one, through a small extracted `summaryWalkAgain`.
- `T/honor/HonorLinkUriTest.kt:37-52` pins `null` for honor-host paths (`/abc`, `/Qoi4YmPHLN%0A`, `/Qoi4YmPHLN/audio/1.m4a`, …) that the spec makes `unrecognised`; `T/honor/HonorLinkTest.kt:57` pins `null` for `URI(".../Qoi4YmPHLN%0A")`, which iOS's `testTrailingNewlineIsRejected` makes `.unrecognised`. U2 rewrites both.
- `P/ui/navigation/PilgrimNavHost.kt:938-944` (the `linkToastShows` KDoc) and the overview exclusion at `:949` describe iOS `7c200bf` behaviour this slice changed. U11.
- `P/ui/honor/HonorWaysViewModel.kt:147` and the comment at `PilgrimNavHost.kt:603-607` describe `walkAgain` overwriting the park with nil, changed at `3b78728`. U7.
- `app/src/main/AndroidManifest.xml:112-117` says a path that doesn't parse "opens the app and does nothing". U1.
- `HonorLinkRouterTest.kt:524-535` pins the same silence. U8.
- `T/MainActivityIntentsTest.kt:151-161` pins `/abc` moving to Pilgrim's task without its data, as a link that wouldn't route; it now routes to its toast, so U2 moves it with its data and keeps the bare root as the case that moves without.

## File structure

| File | Responsibility | Tasks |
|---|---|---|
| `CLAUDE.md`, `app/src/main/AndroidManifest.xml` (comment only) | 2.1.0 parity scope, phasing, backend URLs, link shapes | U1 |
| `P/honor/HonorLink.kt` | `HonorLinkDestination` and the one link grammar | U2 |
| `P/honor/StageLink.kt` (new) | `HonorRouteLink`, `StageLinkOutcome`, `StageLinkResolver` (pure) | U3 |
| `P/honor/HonorLinkRouter.kt` | link toast variants and copy mapping (U4); destinations through hold and dispatch, `routeSheetUp` (U8) | U2, U4, U8 |
| `P/honor/HonorImportCoordinator.kt` | stage outcomes (U4); `openStage`, `routeLink`, seams (U5) | U4, U5 |
| `R/values/strings.xml` | the three new lines | U4 |
| `P/ui/honor/HonorLinkToastView.kt` | the lines' text; `LocalHonorLinkToast` (U11) | U4, U11 |
| `P/ui/honor/HonorWaysViewModel.kt` | the paste dispatch (U2, U5); host `routeLink`, no `dropParkedWay` (U7) | U2, U5, U7 |
| `P/ui/navigation/PilgrimNavHost.kt` | link page route, helpers, registration (U6); landing (U7); `routeSheetUp` (U8); toast gate and local (U11) | U6, U7, U8, U11 |
| `P/ui/honor/pilgrimage/PilgrimageRouteScreen.kt` | leading slot, `PilgrimageRouteLinkSheet` (U6); scroll (U10) | U6, U10 |
| `P/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt` | `ARG_FOCUS`/`NO_FOCUS` (U6); `applyFocus`, `scrollTarget` (U9); `stageListPosition` (U10) | U6, U9, U10 |
| `P/ui/honor/HonorWaysSheet.kt` | the toast inside every Honor sheet | U11 |
| `docs/qa/2026-10-29-stage-links-qa.md` (new) | the device checklist | U12 |

Tests: `T/honor/HonorLinkTest.kt`, `T/honor/HonorLinkUriTest.kt`, `T/MainActivityIntentsTest.kt` (U2); `T/honor/StageLinkResolverTest.kt` (new, U3); `T/ui/honor/HonorLinkToastViewTest.kt` (new, U4); `T/honor/HonorLinkRouterTest.kt` (U4, U8); `T/honor/HonorImportCoordinatorStageTest.kt` (new, U5); `T/ui/honor/HonorWaysViewModelTest.kt` (U5); `T/ui/navigation/PilgrimNavHostTest.kt` (U6, U7, U8, U11); `T/ui/honor/pilgrimage/PilgrimageSheetsBackTest.kt` (U6); `T/ui/honor/pilgrimage/PilgrimageRouteFocusTest.kt` (new, U9); `T/ui/honor/pilgrimage/PilgrimageRouteScrollTest.kt` (new, U10); `T/ui/honor/HonorSheetToastTest.kt` (new, U11).

---

## Tasks

### Task U1: Retarget CLAUDE.md for 2.1.0 (docs only; Q13, Q14)

**Files:**
- Modify: `CLAUDE.md:13-23` (Parity scope), `CLAUDE.md:54-58` (Backend URLs), `CLAUDE.md:89` (the last sentence of "Phasing — current state")
- Modify: `app/src/main/AndroidManifest.xml:112-117` (comment only)
- Add to git: `docs/parity/2026-10-28-stage-links-port.md` (untracked today), this plan
- Delete (untracked, folded into § Decisions): `docs/parity/.stage-links-answers.md`

**Interfaces:**
- Consumes: nothing.
- Produces: no code. Later tasks cite iOS as `@3b78728`, which this task makes the documented pin.

- [ ] **Step 1: Check the worktree**

```bash
cd /Users/rubberduck/GitHub/momentmaker/pilgrim-android-stage-links
git branch --show-current   # feat/stage-links (the worktree already has it, off main at 0ee2872e)
git status --short          # only the untracked docs/parity files and this plan
```

- [ ] **Step 2: Rewrite the Parity scope section**

Replace `CLAUDE.md` lines 13-23 (from `## Parity scope (frozen)` through `Comparing to a future iOS HEAD past \`7c200bf\` is fine for context, but parity work targets \`7c200bf\` only.`) with exactly:

````markdown
## Parity scope (frozen)

**Parity target: pilgrim-ios `main` @ `3b78728`** (2026-10-29, the stage-links merge), for **Android 2.1.0** (Phase 22, owner decision 2026-10-29). iOS hasn't tagged `v2.1.0` yet; once it does, move this pin to the tag and triage `3b78728..v2.1.0` with the fold-in rule below. The iOS work since the 2.0.0 anchor (`v2.0.0` @ `7c200bf`, 2026-09-16, the Honor release) is four PRs (`git log --first-parent 7c200bf..3b78728`):
- **#91** (`e551b11`, "a temple ahead says it stamps until five", temple stamp hours): ported in 2.1.0, spec `docs/parity/2026-10-28-stamp-closing-port.md` and plan `docs/plans/2026-10-29-002-feat-temple-stamp-hours-port-plan.md`, both committed with that plan's branch.
- **#125** (`3b78728`, stage links): ported in 2.1.0, spec `docs/parity/2026-10-28-stage-links-port.md`, plan `docs/plans/2026-10-29-001-feat-stage-links-port-plan.md`.
- **#92** (`10540d0`, Mapbox telemetry off): no port, Android already matches it (`ui/walk/PilgrimMap.kt`).
- **#90** (`ab3f821`, App Store copy): no port, docs only.

KDoc written for 2.1.0 cites iOS at `@3b78728`; existing KDoc keeps its `@7c200bf` pins. Phase 21 (Android 2.0.0) targeted `v2.0.0` @ `7c200bf`, retargeted from `33b0dbc`, the Android v1.5.0 anchor (iOS later re-pointed its `v1.11.0` tag to `bcdf538`, which is `33b0dbc` plus a build bump). iOS closed `pilgrim-ios` #80 (`intentionEcho`) on 2026-09-16 without a code change, so it stays unfixed on both platforms and Android stays parity-exact. Anything iOS shipped AT OR BEFORE `3b78728` is in scope for the Android port. Anything iOS ships AFTER `3b78728` is OUT OF SCOPE, with one bounded exception, the fold-in rule (R2 in `docs/brainstorms/2026-09-28-ios-v200-parity-retarget-requirements.md`): iOS deltas landing before Android 2.1.0 ships are re-diffed and triaged. Chores, hotfixes, and incremental refinements to a surface this release touches fold in; new headline features, reverts of ported work, or redesigns require explicit user re-triage. The fold-in window closes when the parity gate starts; later iOS deltas are triaged the same way into the next Android release, so the gate runs once against a fixed pin. iOS PR #91 was re-triaged to Android 2.0.1 by the owner on 2026-10-13, then moved into 2.1.0 with stage links on 2026-10-29.

To list in-scope iOS history, and the post-pin deltas the fold-in rule triages:
```bash
cd ../pilgrim-ios && git log --oneline 3b78728
cd ../pilgrim-ios && git fetch && git log --oneline 3b78728..origin/main
```

Comparing to a future iOS HEAD past `3b78728` is fine for context, but parity work targets `3b78728` only.
````

- [ ] **Step 3: Add the catalog index and the inbound link shapes**

In `CLAUDE.md`, after the line `- Voice guide / sound manifests: \`https://pilgrimapp.org/*\``, add:

```markdown
- Pilgrimage catalog: `https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@main/index.json`, with packages under `https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@<release>/routes/<routeId>/ways/`

Inbound app links (honor host only, manifest `autoVerify`): `https://honor.pilgrimapp.org/{shareId}` and `/{routeId}/{n}`, with n counted from 1 and at most 200; any other path on the honor host shows "this link needs a newer Pilgrim", except the bare root, which opens the app and shows nothing; the walk host is never claimed.
```

- [ ] **Step 4: Retarget the phasing line**

In `CLAUDE.md:89`, replace the final sentence `Next: Android 2.0.1, with iOS #91's fold-in (temple stamp hours, \`e551b11\`).` with:

```markdown
**Phase 22 (Android 2.1.0, iOS `main` @ `3b78728` parity)** is next: iOS #91 (temple stamp hours, `e551b11`, spec `docs/parity/2026-10-28-stamp-closing-port.md`, committed with its own plan, units from U20) and #125 (stage links, `3b78728`, plan `docs/plans/2026-10-29-001-feat-stage-links-port-plan.md`, U1–U12); the `versionName` bump to 2.1.0 belongs to the release step.
```

- [ ] **Step 5: Fix the manifest comment**

In `app/src/main/AndroidManifest.xml`, replace lines 112-117 (from `Honor links (plan U27, in every build since the 2.0.0 flip):` through `browser and imports only when pasted (AE4). -->`) with (no `--` inside an XML comment):

```xml
                 Honor links (plan U27, in every build since the 2.0.0 flip):
                 iOS associates only the honor host, every path
                 (`applinks:honor.pilgrimapp.org`, the worker's `"/*"`): a
                 share id or a `/{routeId}/{n}` stage link opens what it
                 names; any other path opens the app and says "this link
                 needs a newer Pilgrim"; the bare root opens the app and
                 does nothing (`HonorLink.swift:22-32@3b78728`). The walk
                 host is never claimed, so a tapped walk link opens the
                 browser and imports only when pasted (AE4). -->
```

- [ ] **Step 6: Verify**

```bash
grep -n "3b78728" CLAUDE.md | head
grep -n "2.0.1" CLAUDE.md          # two hits: the Versioning rule (line 10) and the 2026-10-13 history sentence
grep -n "Pilgrimage catalog\|Inbound app links" CLAUDE.md
./gradlew :app:processDebugMainManifest
```
Expected: the pin and both new lines found; `2.0.1` appears only in the Versioning rule's hotfix example (line 10, unchanged) and in "re-triaged to Android 2.0.1 by the owner on 2026-10-13"; the manifest task ends `BUILD SUCCESSFUL`.

- [ ] **Step 7: Delete the answers file, then commit**

```bash
rm docs/parity/.stage-links-answers.md
git add CLAUDE.md app/src/main/AndroidManifest.xml docs/parity/2026-10-28-stage-links-port.md docs/plans/2026-10-29-001-feat-stage-links-port-plan.md
git commit -m "docs: retarget parity to iOS main @ 3b78728 for Android 2.1.0 (Phase 22 U1)" \
  -m "Stage links (#125) and temple stamp hours (#91) ship together as 2.1.0. The catalog index joins the backend URLs, the inbound link shapes are written down, and the manifest comment stops saying an unparsed honor path does nothing. Adds the stage-links spec and this plan; the 14 answers are folded into the plan." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U2: Links parse into destinations

**Files:**
- Modify: `P/honor/HonorLink.kt` (whole file, 86 lines)
- Modify: `P/honor/HonorLinkRouter.kt:127-133` (`open`), `:264-279` (`honorLinkOf`)
- Modify: `P/ui/honor/HonorWaysViewModel.kt:116-119` (`open`)
- Test: `T/honor/HonorLinkTest.kt`, `T/honor/HonorLinkUriTest.kt` (whole file), `T/MainActivityIntentsTest.kt:151-161` (an unparsed honor path now moves with its data, since it routes to a toast)

**Interfaces:**
- Consumes: `WayStore.isValidRouteId(id: String): Boolean` (`P/data/honor/WayStore.kt:717`), `PilgrimageWayImporter.MAX_STAGE_COUNT = 200` (`P/data/honor/pilgrimage/PilgrimageWayImporter.kt:57`).
- Produces (package `org.walktalkmeditate.pilgrim.honor`):
  - `sealed interface HonorLinkDestination { data class Share(val id: String); data class Stage(val routeId: String, val index: Int); data object Unrecognised }`
  - `HonorLink.parse(uri: android.net.Uri): HonorLinkDestination?`, `HonorLink.parse(url: java.net.URI): HonorLinkDestination?`, `HonorLink.parse(text: String): HonorLinkDestination?` (never `Unrecognised`), `HonorLink.shareId(candidate: String): String?` (unchanged), `const val HonorLink.STAGE_HOST = "honor.pilgrimapp.org"`
  - `internal fun honorLinkOf(intent: Intent?, restored: Boolean): HonorLinkDestination?`
  - Interim, replaced in U8: `HonorLinkRouter.open` routes only `Share`; replaced in U5: `HonorWaysViewModel.open` opens only `Share`.

- [ ] **Step 1: Update the existing JVM parser tests to the new type**

```bash
cd /Users/rubberduck/GitHub/momentmaker/pilgrim-android-stage-links
perl -pi -e 's/, ID, HonorLink\.parse/, SHARE, HonorLink.parse/g; s/assertEquals\(ID, HonorLink\.parse/assertEquals(SHARE, HonorLink.parse/g; s/assertEquals\("qoi4ymphln", HonorLink/assertEquals(HonorLinkDestination.Share("qoi4ymphln"), HonorLink/g' app/src/test/java/org/walktalkmeditate/pilgrim/honor/HonorLinkTest.kt
```

In `T/honor/HonorLinkTest.kt`, replace the trailing-newline test (lines 54-61) with:

```kotlin
    /** `$` matches before a trailing newline, so an id pasted with one used to pass every shape check (`HonorLinkTests.swift:85-95@3b78728`). */
    @Test
    fun `a trailing newline is rejected`() {
        assertEquals(HonorLinkDestination.Unrecognised, HonorLink.parse(URI("https://honor.pilgrimapp.org/Qoi4YmPHLN%0A")))
        assertEquals(HonorLinkDestination.Unrecognised, HonorLink.parse(URI("https://honor.pilgrimapp.org/camino-frances/14%0A")))
        assertFalse(WayImporter.isShareId("Qoi4YmPHLN\n"))
        assertFalse(WayStore.isValidId("share:Qoi4YmPHLN\n"))
        assertFalse(WayStore.isValidId("walk:${UUID.randomUUID()}\n"))
    }
```

Change the companion (lines 144-146) to:

```kotlin
    private companion object {
        const val ID = "Qoi4YmPHLN"
        val SHARE = HonorLinkDestination.Share(ID)
        val STAGE_14 = HonorLinkDestination.Stage(routeId = "camino-frances", index = 13)
    }
```

Update the class KDoc's first line to `Port of iOS \`HonorLinkTests.swift@3b78728\` (its stash test belongs to the link routing), …` and insert, before `private fun cp(`:

```kotlin
    // iOS `testStageLinksCountFromOne`, through `URI`, the paste's parser.
    @Test
    fun `stage links count from one`() {
        listOf(
            "https://honor.pilgrimapp.org/camino-frances/14",
            "https://honor.pilgrimapp.org/camino-frances/14/",
            "https://honor.pilgrimapp.org/camino-frances/14?utm_source=pilgrimages#x",
        ).forEach { assertEquals(it, STAGE_14, HonorLink.parse(URI(it))) }
        assertEquals(HonorLinkDestination.Stage("shikoku-88-tosa", 0), HonorLink.parse(URI("https://honor.pilgrimapp.org/shikoku-88-tosa/1")))
        assertEquals(HonorLinkDestination.Stage("camino-frances", 199), HonorLink.parse(URI("https://honor.pilgrimapp.org/camino-frances/200")))
    }

    // iOS `testMalformedStageLinksOnTheHonorHostAreUnrecognised`, plus the spec's `+14` and a non-ASCII digit row.
    @Test
    fun `a malformed stage link on the honor host is unrecognised`() {
        listOf(
            "https://honor.pilgrimapp.org/camino-frances/0",
            "https://honor.pilgrimapp.org/camino-frances/014",
            "https://honor.pilgrimapp.org/camino-frances/201",
            "https://honor.pilgrimapp.org/camino-frances/+14",
            "https://honor.pilgrimapp.org/camino-frances/%D9%A1%D9%A4",
            "https://honor.pilgrimapp.org/camino-frances/14.",
            "https://honor.pilgrimapp.org/Camino-Frances/14",
            "https://honor.pilgrimapp.org/camino-frances",
            "https://honor.pilgrimapp.org/a/b/c",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/extra",
        ).forEach { assertEquals(it, HonorLinkDestination.Unrecognised, HonorLink.parse(URI(it))) }
    }

    // iOS `testStageLinksLiveOnlyOnTheHonorHost`, `testTheHonorHostRootIsNotALink`.
    @Test
    fun `stage links live only on the honor host, and its root is no link`() {
        assertNull(HonorLink.parse(URI("https://walk.pilgrimapp.org/camino-frances/14")))
        assertNull(HonorLink.parse(URI("https://example.com/camino-frances/14")))
        assertNull(HonorLink.parse(URI("https://honor.pilgrimapp.org/")))
        assertNull(HonorLink.parse(URI("https://honor.pilgrimapp.org")))
    }

    // iOS `testPastedStageLinksAreAccepted`.
    @Test
    fun `pasted stage links are accepted`() {
        assertEquals(STAGE_14, HonorLink.parse(text = "honor.pilgrimapp.org/camino-frances/14"))
        assertEquals(STAGE_14, HonorLink.parse(text = " https://honor.pilgrimapp.org/camino-frances/14\n"))
        assertEquals(STAGE_14, HonorLink.parse(text = "http://HONOR.pilgrimapp.org/camino-frances/14?utm_source=x"))
    }

    // iOS `testPastedTextNeverComesBackUnrecognised`; Review Focus 1, a link pasted with the punctuation around it.
    @Test
    fun `pasted text never comes back unrecognised`() {
        listOf(
            "https://honor.pilgrimapp.org/camino-frances/0",
            "honor.pilgrimapp.org/whatever/else/here",
            "honor.pilgrimapp.org/camino-frances/14.",
            "honor.pilgrimapp.org/camino-frances/14)",
            "(honor.pilgrimapp.org/camino-frances/14)",
            "see honor.pilgrimapp.org/camino-frances/14",
        ).forEach { assertNull(it, HonorLink.parse(text = it)) }
    }
```

- [ ] **Step 2: Rewrite the tapped-link tests**

Replace `T/honor/HonorLinkUriTest.kt` with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HonorLink.parse] over a link the OS hands the app, as an intent's
 * [Uri]: the tapped column of shared-walk spec S2 §11.1 and the stage-links
 * spec's grammar (`HonorLinkTests.swift@3b78728`). The platform's own parse
 * decodes each path segment and drops the empty ones, as Foundation's
 * `pathComponents` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorLinkUriTest {

    @Test
    fun `the tapped share forms iOS opens`() {
        listOf(
            "https://honor.pilgrimapp.org/Qoi4YmPHLN",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN?utm=x#m3",
            "https://HONOR.pilgrimapp.org/Qoi4YmPHLN",
            "https://honor.pilgrimapp.org/%51oi4YmPHLN",
            "https://honor.pilgrimapp.org//Qoi4YmPHLN//",
            "https://u@honor.pilgrimapp.org:8443/Qoi4YmPHLN",
        ).forEach { assertEquals(it, SHARE, HonorLink.parse(Uri.parse(it))) }
    }

    // iOS `testStageLinksCountFromOne`.
    @Test
    fun `a tapped stage link counts from one`() {
        listOf(
            "https://honor.pilgrimapp.org/camino-frances/14",
            "https://honor.pilgrimapp.org/camino-frances/14/",
            "https://honor.pilgrimapp.org/camino-frances/14?utm_source=pilgrimages#x",
            "https://HONOR.pilgrimapp.org/camino-frances/14",
            "https://honor.pilgrimapp.org//camino-frances//14",
        ).forEach { assertEquals(it, HonorLinkDestination.Stage("camino-frances", 13), HonorLink.parse(Uri.parse(it))) }
        assertEquals(HonorLinkDestination.Stage("shikoku-88-tosa", 0), HonorLink.parse(Uri.parse("https://honor.pilgrimapp.org/shikoku-88-tosa/1")))
        assertEquals(HonorLinkDestination.Stage("camino-frances", 199), HonorLink.parse(Uri.parse("https://honor.pilgrimapp.org/camino-frances/200")))
    }

    // iOS `testMalformedStageLinksOnTheHonorHostAreUnrecognised`, `testTrailingNewlineIsRejected`, and every honor path that once did nothing.
    @Test
    fun `any other tapped path on the honor host is unrecognised`() {
        listOf(
            "https://honor.pilgrimapp.org/camino-frances/0",
            "https://honor.pilgrimapp.org/camino-frances/014",
            "https://honor.pilgrimapp.org/camino-frances/201",
            "https://honor.pilgrimapp.org/camino-frances/+14",
            "https://honor.pilgrimapp.org/camino-frances/14.",
            "https://honor.pilgrimapp.org/camino-frances/14)",
            "https://honor.pilgrimapp.org/Camino-Frances/14",
            "https://honor.pilgrimapp.org/camino-frances",
            "https://honor.pilgrimapp.org/a/b/c",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/extra",
            "https://honor.pilgrimapp.org/camino-frances/14%0A",
            "https://honor.pilgrimapp.org/abc",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN%0A",
            "https://honor.pilgrimapp.org/Qoi4%2FYmPHL",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN%",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN/audio/1.m4a",
            "https://honor.pilgrimapp.org/Qoi4YmPHL",
            "https://honor.pilgrimapp.org/Qoi4YmPHLN1",
        ).forEach { assertEquals(it, HonorLinkDestination.Unrecognised, HonorLink.parse(Uri.parse(it))) }
    }

    // iOS `testTheHonorHostRootIsNotALink`, `testStageLinksLiveOnlyOnTheHonorHost`.
    @Test
    fun `the honor root, other hosts, and a stage shape on the walk host are no link`() {
        listOf(
            "https://honor.pilgrimapp.org/",
            "https://honor.pilgrimapp.org",
            "https://honor.pilgrimapp.org/?id=Qoi4YmPHLN",
            "https://walk.pilgrimapp.org/camino-frances/14",
            "https://example.com/camino-frances/14",
            "https://example.com/Qoi4YmPHLN",
            "https://honor.pilgrimapp.org.evil.com/Qoi4YmPHLN",
            "mailto:Qoi4YmPHLN@honor.pilgrimapp.org",
        ).forEach { assertNull(it, HonorLink.parse(Uri.parse(it))) }
    }

    @Test
    fun `the id keeps its case`() {
        assertEquals(HonorLinkDestination.Share("qoi4ymphln"), HonorLink.parse(Uri.parse("https://honor.pilgrimapp.org/qoi4ymphln")))
    }

    private companion object {
        val SHARE = HonorLinkDestination.Share("Qoi4YmPHLN")
    }
}
```

- [ ] **Step 3: An unrecognised path moves to Pilgrim's task with its data**

`ownTaskIntent` carries the link along only when `honorLinkOf` is non-null (`P/MainActivity.kt:287-296`). An unrecognised honor path is now non-null, so it moves with its data and its toast shows in Pilgrim's own task; the bare root stays the path that moves without. In `T/MainActivityIntentsTest.kt`, replace `a link that wouldn't route here moves without it` (lines 151-161) with:

```kotlin
    @Test
    fun `a link that wouldn't route here moves without it`() {
        val restored = ownTaskIntent(context, link(), restored = true)
        val fromRecents = ownTaskIntent(context, link().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), restored = false)
        val bareRoot = ownTaskIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://honor.pilgrimapp.org/")), restored = false)

        listOf(restored, fromRecents, bareRoot).forEach {
            assertNull(it.data)
            assertEquals(ComponentName(context, MainActivity::class.java), it.component)
        }
    }

    /** Stage-links spec: an honor path this build can't read still routes, to its "needs a newer Pilgrim" toast, so it moves too. */
    @Test
    fun `an unrecognised honor path and a stage link move with their data`() {
        listOf("https://honor.pilgrimapp.org/abc", "https://honor.pilgrimapp.org/camino-frances/14").forEach { url ->
            val moved = ownTaskIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), restored = false)
            assertEquals(url, Intent.ACTION_VIEW, moved.action)
            assertEquals(url, Uri.parse(url), moved.data)
        }
    }
```

- [ ] **Step 4: Run the parser tests to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.honor.HonorLinkTest' --tests 'org.walktalkmeditate.pilgrim.honor.HonorLinkUriTest'`
Expected: FAIL at compile, `Unresolved reference 'HonorLinkDestination'`.

- [ ] **Step 5: Write the grammar**

Replace `P/honor/HonorLink.kt` with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.net.Uri
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Locale
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageWayImporter
import org.walktalkmeditate.pilgrim.domain.honor.trimmingWhitespacesAndNewlines

/**
 * Where a link leads: iOS `HonorLinkDestination` (`HonorLink.swift:3-10@3b78728`).
 * [Unrecognised] is an honor-host address this build can't read, most
 * likely a shape a newer Pilgrim knows, so the walker is told instead of
 * met with silence.
 */
sealed interface HonorLinkDestination {
    data class Share(val id: String) : HonorLinkDestination

    /** [index] counts from 0, as the data does; the link counted from 1. */
    data class Stage(val routeId: String, val index: Int) : HonorLinkDestination

    data object Unrecognised : HonorLinkDestination
}

/**
 * The one place a link becomes a destination, for both a link the OS hands
 * the app and text the walker pasted: iOS `HonorLink`
 * (`HonorLink.swift:12-57@3b78728`; the share rules are shared-walk spec
 * S2 §1's, unchanged).
 *
 * - The host is case-folded and checked against both hosts; the scheme
 *   never is, and a port or user info is ignored.
 * - Empty path segments are dropped, and an empty path is no link. Each
 *   segment is percent-decoded before any check, as Foundation's
 *   `pathComponents` decodes, so an encoded newline fails and an escaped
 *   id letter passes. Segments are never case-folded.
 * - One segment that is a whole share id is a share, on either host. Any
 *   other shape on the walk host is no link.
 * - On [STAGE_HOST], a route slug then a stage number from 1 to
 *   [PilgrimageWayImporter.MAX_STAGE_COUNT] is a stage; anything else is
 *   [HonorLinkDestination.Unrecognised].
 * - The query and the fragment are ignored.
 *
 * Text is parsed with `java.net.URI` up to its first `?` or `#`. iOS 18's
 * `URL(string:)` percent-encodes what RFC 3986 doesn't allow rather than
 * refusing it, so a query or a fragment holding a space, a lone `%`, or
 * a brace still parses there, and both platforms ignore what follows the
 * path. In the host or the path such a character still fails: `URI`
 * refuses it, and iOS's decoded segment fails the id. `Uri.parse` refuses
 * nothing, so pasted text never goes through it (S2 §11.1).
 */
object HonorLink {

    val HOSTS: Set<String> = setOf("honor.pilgrimapp.org", "walk.pilgrimapp.org")

    /** Stage links live only where their fallback page does (`HonorLink.swift:17-18@3b78728`). */
    const val STAGE_HOST = "honor.pilgrimapp.org"

    /** `\A[A-Za-z0-9_-]{10}\z`: [Regex.matches] anchors the whole input, so no trailing newline slips past. */
    private val ID = Regex("[A-Za-z0-9_-]{10}")

    /** `\A[1-9][0-9]{0,2}\z` with literal ASCII digits: no leading zero, no sign, no other script's digits. */
    private val STAGE_NUMBER = Regex("[1-9][0-9]{0,2}")

    fun parse(url: URI): HonorLinkDestination? {
        val host = url.host?.lowercase(Locale.ROOT) ?: return null
        val parts = url.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::percentDecoded)
        return destination(host, parts)
    }

    /**
     * A link the OS hands the app, iOS's `parse(_ url:)` for an intent's
     * data. The platform already parsed it: [Uri.getPathSegments] decodes
     * each segment and drops the empty ones, as Foundation's
     * `pathComponents` does, and [Uri.getHost] leaves out the port and the
     * user info.
     */
    fun parse(uri: Uri): HonorLinkDestination? {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return destination(host, uri.pathSegments)
    }

    /** [candidate] itself when it is a whole share id, untrimmed: the install referrer's value (S2 §10.3). */
    fun shareId(candidate: String): String? = candidate.takeIf(::isId)

    /**
     * A bare id, or a link on either host with or without a scheme, trimmed
     * of whitespace first. Text Pilgrim can't open is simply no link, never
     * [HonorLinkDestination.Unrecognised]: the paste field says so itself
     * (`HonorLink.swift:34-43@3b78728`).
     */
    fun parse(text: String): HonorLinkDestination? {
        val trimmed = text.trimmingWhitespacesAndNewlines()
        if (trimmed.isEmpty()) return null
        if (isId(trimmed)) return HonorLinkDestination.Share(trimmed)
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = try {
            URI(withScheme.substringBefore('?').substringBefore('#'))
        } catch (_: URISyntaxException) {
            return null
        }
        return parse(url)?.takeUnless { it == HonorLinkDestination.Unrecognised }
    }

    /** iOS `parse(_ url:)` over a folded host and decoded segments (`HonorLink.swift:22-32@3b78728`). */
    private fun destination(host: String, parts: List<String>): HonorLinkDestination? {
        if (host !in HOSTS || parts.isEmpty()) return null
        if (parts.size == 1 && isId(parts[0])) return HonorLinkDestination.Share(parts[0])
        if (host != STAGE_HOST) return null
        if (parts.size == 2) {
            stageIndex(routeId = parts[0], number = parts[1])?.let { return HonorLinkDestination.Stage(parts[0], it) }
        }
        return HonorLinkDestination.Unrecognised
    }

    /** The link counts stages from 1, as the route page shows them; the data counts from 0 (`HonorLink.swift:49-56@3b78728`). */
    private fun stageIndex(routeId: String, number: String): Int? {
        if (!WayStore.isValidRouteId(routeId) || !STAGE_NUMBER.matches(number)) return null
        val n = number.toInt()
        return if (n <= PilgrimageWayImporter.MAX_STAGE_COUNT) n - 1 else null
    }

    private fun isId(s: String): Boolean = ID.matches(s)

    /**
     * `URLDecoder` alone would read `+` as a space, which a path never
     * means. The charset-name overload: the `Charset` one is API 33.
     */
    private fun percentDecoded(segment: String): String =
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
}
```

- [ ] **Step 6: Keep the two callers compiling (interim; U5 and U8 replace these)**

In `P/honor/HonorLinkRouter.kt`, replace `open` (lines 127-133):

```kotlin
    /** A launch's intent, or a new one; [restored] for an Activity rebuilt from saved state. */
    fun open(intent: Intent?, restored: Boolean) {
        val link = honorLinkOf(intent, restored) ?: return
        if (!honorEnabled) return
        linkTapped = true
        if (link is HonorLinkDestination.Share) route(link.id)
    }
```

and in `honorLinkOf` (lines 264-279) change the KDoc's first line to `The destination a launch or a new intent carries: …` and the signature to `internal fun honorLinkOf(intent: Intent?, restored: Boolean): HonorLinkDestination? {` (the body is unchanged; `intent.data?.let(HonorLink::parse)` now yields a destination).

In `P/ui/honor/HonorWaysViewModel.kt`, replace `open` (lines 116-119):

```kotlin
    /** iOS's `onPaste`: parses again, and opens only what parses. */
    fun open(text: String) {
        (HonorLink.parse(text = text) as? HonorLinkDestination.Share)?.let { imports.openWay(it.id) }
    }
```

and add `import org.walktalkmeditate.pilgrim.honor.HonorLinkDestination`. `MainActivity.ownTaskIntent`'s `honorLinkOf(intent, restored) != null` and `HonorWaysSheet.kt:195`'s `HonorLink.parse(text = pasted) != null` need no change.

- [ ] **Step 7: Run the parser tests, then the Honor packages**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.honor.*' --tests 'org.walktalkmeditate.pilgrim.ui.honor.*' --tests 'org.walktalkmeditate.pilgrim.MainActivityIntentsTest'`
Expected: PASS. `HonorLinkRouterTest`'s `an honor path that doesn't parse opens the app and does nothing` still passes: the interim router routes only shares.

- [ ] **Step 8: Full suite, then commit**

```bash
./gradlew :app:testDebugUnitTest
git add app/src/main/java/org/walktalkmeditate/pilgrim/honor/HonorLink.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/honor/HonorLinkRouter.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorWaysViewModel.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/honor/HonorLinkTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/honor/HonorLinkUriTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/MainActivityIntentsTest.kt
git commit -m "feat(honor): links parse into a share, a stage, or unrecognised (Phase 22 U2)" \
  -m "iOS 3b78728's one grammar: a 1-based stage number up to 200 on the honor host only, any other honor path unrecognised, and a paste never unrecognised. The router and the paste still open shares only until U5 and U8." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U3: The stage-link resolver

**Files:**
- Create: `P/honor/StageLink.kt`
- Test: `T/honor/StageLinkResolverTest.kt` (new)

**Interfaces:**
- Consumes: `PilgrimageCatalog(release: String, routes: List<PilgrimageCatalogEntry>)` (`P/data/honor/pilgrimage/PilgrimageModels.kt:117-125`), `PilgrimageCatalogEntry` (`:84-96`, every field required), `WayStore.stageWayId(routeId: String, stageIndex: Int): String`.
- Produces (package `org.walktalkmeditate.pilgrim.honor`):
  - `data class HonorRouteLink(val entry: PilgrimageCatalogEntry, val release: String, val focus: Int?) { val id: String }`, `id = "${entry.id}:${focus ?: "-"}"`
  - `sealed interface StageLinkOutcome { data class Overview(val wayId: String); data class RouteSheet(val link: HonorRouteLink); data object NotInCatalog; data object NeedsCatalog }`
  - `object StageLinkResolver { fun resolve(routeId: String, index: Int, installedRouteId: String?, stageWayExists: Boolean, catalog: PilgrimageCatalog?): StageLinkOutcome }`

- [ ] **Step 1: Write the failing resolver tests (iOS's ten, names kept)**

Create `T/honor/StageLinkResolverTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry

/** Port of iOS `StageLinkResolverTests.swift@3b78728`, the ten cases with their names. */
class StageLinkResolverTest {

    private val frances = PilgrimageCatalogEntry(
        id = "camino-frances",
        name = "Camino Francés",
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 780.0,
        tradition = "christian",
        stageCount = 33,
        bytes = 1_000,
        placesPerStage = 0.0,
        sparse = false,
    )

    private val catalog = PilgrimageCatalog(release = "v1.12.0", routes = listOf(frances))

    private fun resolve(
        routeId: String = "camino-frances",
        index: Int = 13,
        installed: String? = null,
        wayExists: Boolean = false,
        catalog: PilgrimageCatalog?,
    ): StageLinkOutcome = StageLinkResolver.resolve(routeId, index, installed, wayExists, catalog)

    private fun sheet(focus: Int?) = StageLinkOutcome.RouteSheet(HonorRouteLink(frances, "v1.12.0", focus))

    @Test
    fun `installed with the stage opens the overview`() {
        assertEquals(
            StageLinkOutcome.Overview("pilgrimage:camino-frances:13"),
            resolve(installed = "camino-frances", wayExists = true, catalog = catalog),
        )
    }

    @Test
    fun `installed with the stage needs no catalog`() {
        assertEquals(
            StageLinkOutcome.Overview("pilgrimage:camino-frances:13"),
            resolve(installed = "camino-frances", wayExists = true, catalog = null),
        )
    }

    @Test
    fun `installed but the stage missing opens the route focused`() {
        assertEquals(sheet(focus = 13), resolve(installed = "camino-frances", wayExists = false, catalog = catalog))
    }

    @Test
    fun `not installed opens the route focused`() {
        assertEquals(sheet(focus = 13), resolve(catalog = catalog))
    }

    @Test
    fun `another route installed opens the route focused`() {
        assertEquals(
            "Replace is the route page's question to ask, not the link's",
            sheet(focus = 13),
            resolve(installed = "camino-norte", catalog = catalog),
        )
    }

    @Test
    fun `the last stage opens the route focused on it`() {
        assertEquals(sheet(focus = 32), resolve(index = 32, catalog = catalog))
    }

    @Test
    fun `a stage past the end opens the route unfocused`() {
        assertEquals(sheet(focus = null), resolve(index = 33, catalog = catalog))
    }

    @Test
    fun `a route absent from the catalog says so`() {
        assertEquals(StageLinkOutcome.NotInCatalog, resolve(routeId = "camino-imaginario", catalog = catalog))
    }

    @Test
    fun `no catalog in hand asks for one`() {
        assertEquals(StageLinkOutcome.NeedsCatalog, resolve(catalog = null))
    }

    @Test
    fun `focus is part of the sheet identity`() {
        val fourteen = HonorRouteLink(frances, "v1.12.0", focus = 13)
        val twenty = HonorRouteLink(frances, "v1.12.0", focus = 19)
        assertNotEquals(fourteen.id, twenty.id)
        assertNotEquals(HonorRouteLink(frances, "v1.12.0", focus = null).id, fourteen.id)
        assertEquals("camino-frances:13", fourteen.id)
        assertEquals("camino-frances:-", HonorRouteLink(frances, "v1.12.0", focus = null).id)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.honor.StageLinkResolverTest'`
Expected: FAIL at compile, `Unresolved reference 'StageLinkResolver'`.

- [ ] **Step 3: Write the resolver**

Create `P/honor/StageLink.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry

/**
 * A route page opened from a stage link, and the stage it brings into
 * view: iOS `HonorRouteLink` (`StageLink.swift:3-12@3b78728`). Its [id]
 * carries the focus, so a second link to another stage of the same route
 * opens the page again rather than leaving it where it was. [focus] is
 * 0-based, and null for a stage past the route's end.
 */
data class HonorRouteLink(val entry: PilgrimageCatalogEntry, val release: String, val focus: Int?) {
    val id: String get() = "${entry.id}:${focus?.toString() ?: "-"}"
}

/** iOS `StageLinkOutcome` (`StageLink.swift:14-19@3b78728`). */
sealed interface StageLinkOutcome {
    data class Overview(val wayId: String) : StageLinkOutcome

    data class RouteSheet(val link: HonorRouteLink) : StageLinkOutcome

    data object NotInCatalog : StageLinkOutcome

    data object NeedsCatalog : StageLinkOutcome
}

/**
 * What a stage link does, decided with no disk and no network: iOS
 * `StageLinkResolver` (`StageLink.swift:21-37@3b78728`). It is what tapping
 * that stage on the route page does, so a downloaded stage never waits on
 * the catalog. An installed route whose stage Way is missing (an older
 * release), a route not installed, or another route installed all open the
 * route page; Replace is that page's question.
 */
object StageLinkResolver {

    fun resolve(
        routeId: String,
        index: Int,
        installedRouteId: String?,
        stageWayExists: Boolean,
        catalog: PilgrimageCatalog?,
    ): StageLinkOutcome {
        if (installedRouteId == routeId && stageWayExists) {
            return StageLinkOutcome.Overview(WayStore.stageWayId(routeId, index))
        }
        catalog ?: return StageLinkOutcome.NeedsCatalog
        val entry = catalog.routes.firstOrNull { it.id == routeId } ?: return StageLinkOutcome.NotInCatalog
        val focus = index.takeIf { it in 0 until entry.stageCount }
        return StageLinkOutcome.RouteSheet(HonorRouteLink(entry, catalog.release, focus))
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.honor.StageLinkResolverTest'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Full suite, then commit**

```bash
./gradlew :app:testDebugUnitTest
git add app/src/main/java/org/walktalkmeditate/pilgrim/honor/StageLink.kt app/src/test/java/org/walktalkmeditate/pilgrim/honor/StageLinkResolverTest.kt
git commit -m "feat(honor): the stage-link resolver, iOS's pure decision (Phase 22 U3)" \
  -m "A downloaded stage opens its overview with no catalog; anything else needs the catalog and opens the route page, focused only on a stage the route has. Ports iOS's ten resolver cases." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U4: The stage link's toast lines

**Files:**
- Modify: `P/honor/HonorLinkRouter.kt:41-48` (`HonorLinkToast`), `:217-226` (`answer`)
- Modify: `P/honor/HonorImportCoordinator.kt:217-223` (`HonorImportOutcome`)
- Modify: `R/values/strings.xml` (after `honor_link_finish_walk_first`, line 1287)
- Modify: `P/ui/honor/HonorLinkToastView.kt:92-101` (`honorLinkToastText`)
- Test: `T/ui/honor/HonorLinkToastViewTest.kt` (new), `T/honor/HonorLinkRouterTest.kt`

**Interfaces:**
- Consumes: `PilgrimageCopy.line(error: PilgrimageError): @StringRes Int` (`P/data/honor/pilgrimage/PilgrimageModels.kt:27-37`).
- Produces:
  - `HonorLinkToast.ReachingForRoute`, `HonorLinkToast.RouteNotInPilgrim`, `HonorLinkToast.NeedsNewerPilgrim`, `data class HonorLinkToast.CatalogFailed(val error: PilgrimageError)`
  - `HonorImportOutcome.ReachingForRoute`, `HonorImportOutcome.RouteNotInPilgrim`, `data class HonorImportOutcome.CatalogFailed(val error: PilgrimageError)`
  - `internal fun linkToastFor(outcome: HonorImportOutcome, waysSheetUp: Boolean): HonorLinkToast?` (top level in `HonorLinkRouter.kt`)
  - strings `R.string.honor_link_needs_newer_pilgrim`, `R.string.honor_link_reaching_for_route`, `R.string.honor_link_route_not_in_pilgrim`

- [ ] **Step 1: Write the failing copy test**

Create `T/ui/honor/HonorLinkToastViewTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.honor.HonorLinkToast
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/** The stage-link lines, verbatim from iOS (`MainCoordinatorView.swift:231,275,299,307,327@3b78728`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorLinkToastViewTest {

    @get:Rule val composeRule = createComposeRule()

    private val toast = mutableStateOf<HonorLinkToast?>(null)

    @Test
    fun `each stage-link line is iOS's, verbatim`() {
        composeRule.setContent { PilgrimTheme { HonorLinkToastView(toast = toast.value) } }

        listOf(
            HonorLinkToast.NeedsNewerPilgrim to "this link needs a newer Pilgrim",
            HonorLinkToast.ReachingForRoute to "reaching for the route…",
            HonorLinkToast.RouteNotInPilgrim to "this route isn't in Pilgrim yet",
            HonorLinkToast.CatalogFailed(PilgrimageError.CATALOG_UNREACHABLE) to "the routes are out of reach right now",
            HonorLinkToast.FinishWalkFirst to "finish this walk first",
        ).forEach { (line, text) ->
            composeRule.runOnIdle { toast.value = line }
            composeRule.waitUntil(WAIT_MILLIS) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
```

Add to `T/honor/HonorLinkRouterTest.kt`, before `private fun link(id: String)` (and add `import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError`):

```kotlin
    // iOS's `showLinkToast` per outcome (`MainCoordinatorView.swift:250-266,296-328@3b78728`).
    @Test
    fun `a stage link's lines show even with the Ways sheet up, where a share's failure stays inline`() {
        assertNull(linkToastFor(HonorImportOutcome.Listed, waysSheetUp = false))
        assertEquals(HonorLinkToast.Failed(WayError.NOT_FOUND), linkToastFor(HonorImportOutcome.Failed(WayError.NOT_FOUND), waysSheetUp = false))
        assertNull(linkToastFor(HonorImportOutcome.Failed(WayError.NOT_FOUND), waysSheetUp = true))
        listOf(false, true).forEach { sheetUp ->
            assertEquals(HonorLinkToast.ReachingForRoute, linkToastFor(HonorImportOutcome.ReachingForRoute, sheetUp))
            assertEquals(HonorLinkToast.RouteNotInPilgrim, linkToastFor(HonorImportOutcome.RouteNotInPilgrim, sheetUp))
            assertEquals(
                HonorLinkToast.CatalogFailed(PilgrimageError.CATALOG_UNREACHABLE),
                linkToastFor(HonorImportOutcome.CatalogFailed(PilgrimageError.CATALOG_UNREACHABLE), sheetUp),
            )
        }
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.honor.HonorLinkToastViewTest' --tests 'org.walktalkmeditate.pilgrim.honor.HonorLinkRouterTest'`
Expected: FAIL at compile, `Unresolved reference 'NeedsNewerPilgrim'`.

- [ ] **Step 3: Add the strings**

In `R/values/strings.xml`, after `<string name="honor_link_finish_walk_first">finish this walk first</string>` (line 1287), add:

```xml
    <!-- Phase 22 U4: stage links (stage-links spec, Copy). English Swift literals on iOS
         (`MainCoordinatorView.swift:231,299,327@3b78728`): lowercase, the single-character
         ellipsis, the ASCII apostrophe, no full stop. -->
    <string name="honor_link_needs_newer_pilgrim">this link needs a newer Pilgrim</string>
    <string name="honor_link_reaching_for_route">reaching for the route…</string>
    <string name="honor_link_route_not_in_pilgrim">this route isn\'t in Pilgrim yet</string>
```

- [ ] **Step 4: Add the toast and outcome variants and the mapping**

In `P/honor/HonorLinkRouter.kt`, replace `HonorLinkToast` (lines 41-48) with:

```kotlin
/** What the link toast says (S2 §7.1; stage-links spec, Copy). The overview and the Ways sheet say the rest inline. */
sealed interface HonorLinkToast {
    data object FinishWalkFirst : HonorLinkToast

    data object Reaching : HonorLinkToast

    data class Failed(val error: WayError) : HonorLinkToast

    /** "reaching for the route…", before each catalog load a stage link makes. */
    data object ReachingForRoute : HonorLinkToast

    /** "this route isn't in Pilgrim yet": the catalog, forced once, still lacks the route. */
    data object RouteNotInPilgrim : HonorLinkToast

    /** "this link needs a newer Pilgrim": an honor-host path this build can't read. */
    data object NeedsNewerPilgrim : HonorLinkToast

    /** The catalog load threw: its pilgrimage line. */
    data class CatalogFailed(val error: PilgrimageError) : HonorLinkToast
}
```

Replace `answer` (lines 217-226) with:

```kotlin
    private fun answer(outcome: HonorImportOutcome) {
        showToast(linkToastFor(outcome, waysSheetUp = screen?.waysSheetUp == true))
    }
```

and add at the end of the file:

```kotlin
/**
 * iOS's `showLinkToast` for each way an import or a stage link ends
 * (`MainCoordinatorView.swift:250-266,296-328@3b78728`): a listed Way or a
 * landed route clears the toast; a share's failure shows unless the Ways
 * sheet says it inline; a stage link's lines show wherever the walker is,
 * the sheet included.
 */
internal fun linkToastFor(outcome: HonorImportOutcome, waysSheetUp: Boolean): HonorLinkToast? = when (outcome) {
    HonorImportOutcome.Listed -> null
    is HonorImportOutcome.Failed -> HonorLinkToast.Failed(outcome.error).takeUnless { waysSheetUp }
    HonorImportOutcome.ReachingForRoute -> HonorLinkToast.ReachingForRoute
    HonorImportOutcome.RouteNotInPilgrim -> HonorLinkToast.RouteNotInPilgrim
    is HonorImportOutcome.CatalogFailed -> HonorLinkToast.CatalogFailed(outcome.error)
}
```

Add `import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError` to `HonorLinkRouter.kt`.

In `P/honor/HonorImportCoordinator.kt`, replace `HonorImportOutcome` (lines 217-223) with (and add `import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError`):

```kotlin
/** How an import, or a stage link's resolution, the walker still wanted ended. */
sealed interface HonorImportOutcome {
    /** Its Way is offered through [HonorImportCoordinator.fetched], or its route page through `routeLink`. */
    data object Listed : HonorImportOutcome

    data class Failed(val error: WayError) : HonorImportOutcome

    /** A stage link is about to load the catalog. */
    data object ReachingForRoute : HonorImportOutcome

    /** The catalog, forced once, still lacks the route. */
    data object RouteNotInPilgrim : HonorImportOutcome

    data class CatalogFailed(val error: PilgrimageError) : HonorImportOutcome
}
```

(The `routeLink` slot the KDoc names arrives in U5.)

- [ ] **Step 5: Give each new toast its words**

In `P/ui/honor/HonorLinkToastView.kt`, replace `honorLinkToastText` (lines 92-101) with (and add `import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCopy`):

```kotlin
/** Every line verbatim (S2 §7.1; stage-links spec, Copy): the refusal, the import's own copy, and a stage link's lines. */
@Composable
private fun honorLinkToastText(toast: HonorLinkToast): String {
    val resources = LocalResources.current
    return when (toast) {
        HonorLinkToast.FinishWalkFirst -> resources.getString(R.string.honor_link_finish_walk_first)
        HonorLinkToast.Reaching -> HonorImportCopy.line(resources, HonorImportState.Fetching)
        is HonorLinkToast.Failed -> HonorImportCopy.line(resources, HonorImportState.Failed(toast.error))
        HonorLinkToast.ReachingForRoute -> resources.getString(R.string.honor_link_reaching_for_route)
        HonorLinkToast.RouteNotInPilgrim -> resources.getString(R.string.honor_link_route_not_in_pilgrim)
        HonorLinkToast.NeedsNewerPilgrim -> resources.getString(R.string.honor_link_needs_newer_pilgrim)
        is HonorLinkToast.CatalogFailed -> resources.getString(PilgrimageCopy.line(toast.error))
    }.orEmpty()
}
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.honor.HonorLinkToastViewTest' --tests 'org.walktalkmeditate.pilgrim.honor.HonorLinkRouterTest'`
Expected: PASS.

- [ ] **Step 7: Full suite and lint, then commit**

```bash
./gradlew :app:testDebugUnitTest lintDebug
git add app/src/main/java/org/walktalkmeditate/pilgrim/honor/HonorLinkRouter.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/honor/HonorImportCoordinator.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorLinkToastView.kt \
  app/src/main/res/values/strings.xml \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/honor/HonorLinkToastViewTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/honor/HonorLinkRouterTest.kt
git commit -m "feat(honor): the stage link's toast lines, verbatim from iOS (Phase 22 U4)" \
  -m "Three new lines and the catalog failure's pilgrimage line. Unlike a share's failure they show with the Ways sheet up, as iOS shows them unconditionally." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U5: The coordinator resolves stage links

**Files:**
- Modify: `P/honor/HonorImportCoordinator.kt` (constructor and class KDoc at 30-56, `fetched` at 61-64, `openWay` at 94-122, `consumeFetched` at 134-136; new `openStage`, `routeLink`, `consumeRouteLink`)
- Modify: `P/ui/honor/HonorWaysViewModel.kt:116-119` (`open`, replacing U2's interim)
- Test: `T/honor/HonorImportCoordinatorStageTest.kt` (new), `T/ui/honor/HonorWaysViewModelTest.kt`

**Interfaces:**
- Consumes: U3's `StageLinkResolver.resolve`, `StageLinkOutcome`, `HonorRouteLink`; U4's `HonorImportOutcome.ReachingForRoute | RouteNotInPilgrim | CatalogFailed`; `PilgrimagePackageManager.installedRoute(): Installed?` (suspend, `:218`), `WayStore.load(id: String): Way?` (`:144`), `PilgrimageCatalogService.catalog: StateFlow<PilgrimageCatalog?>` (`:98`), `PilgrimageCatalogService.load(force: Boolean = false): PilgrimageCatalog` (suspend, `:124`).
- Produces:
  - `fun HonorImportCoordinator.openStage(routeId: String, index: Int)`
  - `val HonorImportCoordinator.routeLink: StateFlow<HonorRouteLink?>`, `fun consumeRouteLink(link: HonorRouteLink)` (compare-and-set)
  - publishing `fetched` clears `routeLink` and the reverse
  - internal constructor gains, with test defaults: `installedRouteId: suspend () -> String? = { null }`, `loadStageWay: suspend (wayId: String) -> Way? = { null }`, `cachedCatalog: () -> PilgrimageCatalog? = { null }`, `loadCatalog: suspend (force: Boolean) -> PilgrimageCatalog = { throw PilgrimageException(PilgrimageError.CATALOG_UNREACHABLE) }` (iOS's stub default: a test that reaches the catalog by accident shows a toast)
  - the `@Inject` constructor gains `packages: Provider<PilgrimagePackageManager>`, `wayStore: Provider<WayStore>`, `catalogs: Provider<PilgrimageCatalogService>` (Providers, so nothing is built until a stage link needs it; the `HonorLinkRouter` ↔ `UiPilgrimageWalkSignals` cycle is already broken by a `Provider` at `PilgrimageWalkGuard.kt:65`)

- [ ] **Step 1: Write the failing coordinator tests**

Create `T/honor/HonorImportCoordinatorStageTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.io.IOException
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageException
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * Stage links in the import coordinator: the cases of iOS's
 * `MainCoordinatorHonorTests.swift:248-649@3b78728` that need no routing
 * (each test names its iOS original), on fakes for the four seams. An
 * await a test must hold is a continuation it resumes, as iOS's
 * `AsyncGate` holds it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HonorImportCoordinatorStageTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val heldShares = mutableMapOf<String, Continuation<Way>>()
    private var installed: String? = null
    private var installedReadFails = false
    private var heldInstalledRead: Continuation<String?>? = null
    private var holdInstalledRead = false
    private var stageWay: Way? = null
    private val stageReads = mutableListOf<String>()
    private var cached: PilgrimageCatalog? = null
    private val loads = mutableListOf<Boolean>()
    private var load: suspend (Boolean) -> PilgrimageCatalog = { throw PilgrimageException(PilgrimageError.CATALOG_UNREACHABLE) }
    private val outcomes = mutableListOf<HonorImportOutcome>()
    private lateinit var imports: HonorImportCoordinator

    private fun TestScope.coordinator(honorEnabled: Boolean = true): HonorImportCoordinator {
        imports = HonorImportCoordinator(
            importShare = { id -> suspendCoroutine { heldShares[id] = it } },
            honorEnabled = honorEnabled,
            scope = backgroundScope,
            media = { error("no stage link gathers here") },
            installedRouteId = {
                if (installedReadFails) throw IOException("route.json could not be read")
                if (holdInstalledRead) suspendCoroutine { heldInstalledRead = it } else installed
            },
            loadStageWay = { id ->
                stageReads += id
                stageWay?.takeIf { id == STAGE_WAY_ID }
            },
            cachedCatalog = { cached },
            loadCatalog = { force ->
                loads += force
                load(force)
            },
        )
        backgroundScope.launch { imports.outcomes.collect { outcomes += it } }
        return imports
    }

    // iOS `testStageLinkForAnInstalledStageOpensItsOverview`.
    @Test
    fun `a stage on the phone opens its overview, with no catalog`() = runTest(dispatcher) {
        installed = ROUTE_ID
        stageWay = stage()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(STAGE_WAY_ID, imports.fetched.value)
        assertNull(imports.routeLink.value)
        assertEquals(listOf(HonorImportOutcome.Listed), outcomes)
        assertTrue("a downloaded stage never waits on the catalog", loads.isEmpty())
    }

    // iOS `testStageLinkForARouteNotOnThePhoneOpensTheRouteFocused`.
    @Test
    fun `a route not on the phone opens its page on the stage, and reads no stage Way`() = runTest(dispatcher) {
        cached = catalog()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(link(focus = INDEX), imports.routeLink.value)
        assertNull(imports.fetched.value)
        assertTrue(stageReads.isEmpty())
    }

    @Test
    fun `another route on the phone opens this route's page, and reads none of the other's stages`() = runTest(dispatcher) {
        installed = "camino-norte"
        cached = catalog()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(link(focus = INDEX), imports.routeLink.value)
        assertTrue(stageReads.isEmpty())
    }

    @Test
    fun `a phone whose installed route can't be read opens the route page`() = runTest(dispatcher) {
        installedReadFails = true
        cached = catalog()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(link(focus = INDEX), imports.routeLink.value)
    }

    // iOS `testStageLinkLoadsTheCatalogWhenNoneIsInHand`.
    @Test
    fun `with no catalog in hand it reaches for the route, loads unforced, then opens the page`() = runTest(dispatcher) {
        var heldLoad: Continuation<PilgrimageCatalog>? = null
        load = { _ -> suspendCoroutine { heldLoad = it } }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        assertEquals(listOf(HonorImportOutcome.ReachingForRoute), outcomes)
        assertEquals(listOf(false), loads)
        checkNotNull(heldLoad).resume(catalog())

        assertEquals(link(focus = INDEX), imports.routeLink.value)
        assertEquals(listOf(HonorImportOutcome.ReachingForRoute, HonorImportOutcome.Listed), outcomes)
    }

    // iOS `testStageLinkForARouteEvenAFreshCatalogLacksSaysSo`.
    @Test
    fun `a route even a fresh catalog lacks is refused after exactly one forced load`() = runTest(dispatcher) {
        cached = catalog(routes = emptyList())
        load = { catalog(routes = emptyList()) }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(listOf(true), loads)
        assertEquals(listOf(HonorImportOutcome.ReachingForRoute, HonorImportOutcome.RouteNotInPilgrim), outcomes)
        assertNull(imports.routeLink.value)
    }

    // iOS `testStageLinkForARouteAddedSinceTheCachedCatalogForcesOneLoad`.
    @Test
    fun `a route added since the cached catalog is found by one forced load`() = runTest(dispatcher) {
        cached = catalog(release = "v1.11.0", routes = emptyList())
        load = { catalog() }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(listOf(true), loads)
        assertEquals(link(focus = INDEX), imports.routeLink.value)
    }

    // iOS `testStageLinkForARouteInTheCachedCatalogNeverLoads`.
    @Test
    fun `a route in the cached catalog never loads`() = runTest(dispatcher) {
        cached = catalog()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertTrue(loads.isEmpty())
        assertEquals(listOf(HonorImportOutcome.Listed), outcomes)
    }

    // iOS `testStageLinkWithNoCatalogInHandLoadsUnforcedThenForcesOnceIfTheRouteIsMissing`.
    @Test
    fun `with no catalog in hand and the route missing, it loads unforced, then forced once, then says so`() = runTest(dispatcher) {
        load = { catalog(routes = emptyList()) }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(listOf(false, true), loads)
        assertEquals(
            listOf(HonorImportOutcome.ReachingForRoute, HonorImportOutcome.ReachingForRoute, HonorImportOutcome.RouteNotInPilgrim),
            outcomes,
        )
    }

    // iOS `testStageLinkWithTheCatalogOutOfReachSaysSo`.
    @Test
    fun `a catalog out of reach says so, and any other load failure reads the same`() = runTest(dispatcher) {
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        assertEquals(
            listOf(HonorImportOutcome.ReachingForRoute, HonorImportOutcome.CatalogFailed(PilgrimageError.CATALOG_UNREACHABLE)),
            outcomes,
        )

        outcomes.clear()
        load = { throw IOException("the cache could not be read") }
        imports.openStage(ROUTE_ID, INDEX)

        assertEquals(
            listOf(HonorImportOutcome.ReachingForRoute, HonorImportOutcome.CatalogFailed(PilgrimageError.CATALOG_UNREACHABLE)),
            outcomes,
        )
        assertNull(imports.routeLink.value)
    }

    // iOS `testStageLinkCancelsAShareImportInFlight`.
    @Test
    fun `a stage link cancels a share import in flight and clears its spinner, and the share lands nothing`() = runTest(dispatcher) {
        cached = catalog()
        coordinator()
        imports.openWay(SHARE_ID)
        assertEquals(HonorImportState.Fetching, imports.state.value)

        imports.openStage(ROUTE_ID, INDEX)
        assertEquals(HonorImportState.Idle, imports.state.value)
        heldShares.getValue(SHARE_ID).resume(share(SHARE_ID))

        assertEquals(link(focus = INDEX), imports.routeLink.value)
        assertNull("the cancelled share import must land nothing", imports.fetched.value)
    }

    // iOS `testAStageLinkReplacedBeforeItsTaskRunsLandsNothing`.
    @Test
    fun `a stage link replaced while it reads the phone lands nothing and says nothing`() = runTest(dispatcher) {
        holdInstalledRead = true
        cached = catalog()
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        imports.openWay(SHARE_ID)
        checkNotNull(heldInstalledRead).resume(null)

        assertNull(imports.routeLink.value)
        assertTrue(stageReads.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    // iOS `testACancelledStageLinkLandsNothing`.
    @Test
    fun `a stage link replaced during its catalog load lands nothing`() = runTest(dispatcher) {
        var heldLoad: Continuation<PilgrimageCatalog>? = null
        load = { _ -> suspendCoroutine { heldLoad = it } }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        imports.openWay(SHARE_ID)
        checkNotNull(heldLoad).resume(catalog())

        assertNull("the replaced stage link must land nothing", imports.routeLink.value)
        assertEquals(listOf(HonorImportOutcome.ReachingForRoute), outcomes)
    }

    /** Q8: a `catch (e: Exception)` that swallowed the cancellation would say "the routes are out of reach" for a replaced link. */
    @Test
    fun `a stage link replaced during a load that then fails says nothing`() = runTest(dispatcher) {
        var heldLoad: Continuation<PilgrimageCatalog>? = null
        load = { _ -> suspendCoroutine { heldLoad = it } }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        imports.openWay(SHARE_ID)
        checkNotNull(heldLoad).resumeWithException(PilgrimageException(PilgrimageError.CATALOG_UNREACHABLE))

        assertEquals(listOf(HonorImportOutcome.ReachingForRoute), outcomes)
    }

    // iOS `startWalk` (`MainCoordinatorView.swift:96-98@3b78728`): the walk screen opening cancels the job.
    @Test
    fun `a walk starting cancels a stage resolution, which lands nothing`() = runTest(dispatcher) {
        var heldLoad: Continuation<PilgrimageCatalog>? = null
        load = { _ -> suspendCoroutine { heldLoad = it } }
        coordinator()

        imports.openStage(ROUTE_ID, INDEX)
        imports.cancelImport()
        checkNotNull(heldLoad).resume(catalog())

        assertNull(imports.routeLink.value)
        assertEquals(listOf(HonorImportOutcome.ReachingForRoute), outcomes)
    }

    // iOS `testNewestLinkWinsOverAnOlderShareParkedBehindASummary`, `testNewestLinkWinsWhileTheWaysSheetIsStillClosing`.
    @Test
    fun `the newest request wins - a route link clears a waiting Way, and a Way clears a waiting route link`() = runTest(dispatcher) {
        cached = catalog()
        coordinator()
        imports.openWay(SHARE_ID)
        heldShares.getValue(SHARE_ID).resume(share(SHARE_ID))
        assertEquals("share:$SHARE_ID", imports.fetched.value)

        imports.openStage(ROUTE_ID, INDEX)
        assertEquals(link(focus = INDEX), imports.routeLink.value)
        assertNull(imports.fetched.value)

        imports.openWay(SECOND_ID)
        heldShares.getValue(SECOND_ID).resume(share(SECOND_ID))
        assertEquals("share:$SECOND_ID", imports.fetched.value)
        assertNull(imports.routeLink.value)
    }

    @Test
    fun `consuming a route link takes only the link it names`() = runTest(dispatcher) {
        cached = catalog()
        coordinator()
        imports.openStage(ROUTE_ID, INDEX)

        imports.consumeRouteLink(link(focus = 19))
        assertEquals(link(focus = INDEX), imports.routeLink.value)
        imports.consumeRouteLink(link(focus = INDEX))

        assertNull(imports.routeLink.value)
    }

    // Q3; iOS `testAnOverviewItemSwapDoesNotPromoteAParkedRouteLink`: the outgoing overview's close is no close.
    @Test
    fun `an overview a stage link replaced leaves the stage's state alone as it goes`() = runTest(dispatcher) {
        installed = ROUTE_ID
        stageWay = stage()
        coordinator()
        val outgoing = Any()
        imports.gather(stage().copy(id = "walk:$SOURCE_UUID", source = WaySource.OwnWalk(SOURCE_UUID)), outgoing)

        imports.openStage(ROUTE_ID, INDEX)
        val incoming = Any()
        imports.gather(stage(), incoming)
        imports.overviewClosed(outgoing)

        assertEquals(HonorImportState.Ready, imports.state.value)
    }

    @Test
    fun `with the release flag off a stage link does nothing`() = runTest(dispatcher) {
        cached = catalog()
        coordinator(honorEnabled = false)

        imports.openStage(ROUTE_ID, INDEX)

        assertNull(imports.routeLink.value)
        assertTrue(outcomes.isEmpty())
    }

    private fun entry() = PilgrimageCatalogEntry(
        id = ROUTE_ID,
        name = "Camino Francés",
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 780.0,
        tradition = "christian",
        stageCount = 33,
        bytes = 1_000,
        placesPerStage = 0.0,
        sparse = false,
    )

    private fun catalog(release: String = RELEASE, routes: List<PilgrimageCatalogEntry> = listOf(entry())) =
        PilgrimageCatalog(release, routes)

    private fun link(focus: Int?) = HonorRouteLink(entry(), RELEASE, focus)

    private fun stage() = Way(
        id = STAGE_WAY_ID,
        source = WaySource.Pilgrimage(routeId = ROUTE_ID, stageIndex = INDEX),
        title = "Burgos to Hornillos del Camino",
        departedAt = Instant.parse("2026-08-01T07:00:00Z"),
        tzIdentifier = "Europe/Madrid",
        expires = null,
        route = listOf(WayPoint(42.34, -3.70, 860.0, 0.0), WayPoint(42.33, -3.92, 830.0, 20_000.0)),
        totalDistanceMeters = 20_000.0,
        theirActiveSeconds = 0.0,
        moments = emptyList(),
        weather = null,
    )

    private fun share(id: String) = Way(
        id = "share:$id",
        source = WaySource.Share(id = id, pageUrl = "https://walk.pilgrimapp.org/$id"),
        title = "Rúa do Franco → Obradoiro",
        departedAt = Instant.parse("2026-08-01T07:00:00Z"),
        tzIdentifier = "Europe/Madrid",
        expires = Instant.parse("2099-01-01T00:00:00Z"),
        route = listOf(WayPoint(42.88, -8.545, 250.0, 0.0), WayPoint(42.88, -8.540, 250.0, 400.0)),
        totalDistanceMeters = 408.0,
        theirActiveSeconds = 540.0,
        moments = emptyList(),
        weather = null,
    )

    private companion object {
        const val ROUTE_ID = "camino-frances"
        const val INDEX = 13
        const val STAGE_WAY_ID = "pilgrimage:camino-frances:13"
        const val RELEASE = "v1.12.0"
        const val SHARE_ID = "Qoi4YmPHLN"
        const val SECOND_ID = "Second1234"
        const val SOURCE_UUID = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
    }
}
```

In `T/ui/honor/HonorWaysViewModelTest.kt`, add a field `private val catalogLoads = mutableListOf<Boolean>()` above `imports`, add to the `HonorImportCoordinator(...)` call inside `imports by lazy` (after `media = …`):

```kotlin
            loadCatalog = { force ->
                catalogLoads += force
                awaitCancellation()
            },
```

add `import kotlinx.coroutines.awaitCancellation`, and add after `Open imports only what parses, and the id it parses to`:

```kotlin
    // iOS's `onPaste` (`MainTabView.swift:60-62@3b78728`): a stage link resolves; what the routing would answer opens nothing.
    @Test
    fun `Open on a stage link resolves the stage, and a link the paste can't open opens nothing`() = runTest(dispatcher) {
        val vm = sheet()

        vm.open("honor.pilgrimapp.org/camino-frances/0")
        vm.open("honor.pilgrimapp.org/abc")
        assertTrue(catalogLoads.isEmpty())

        vm.open(" https://honor.pilgrimapp.org/camino-frances/14\n")

        assertEquals("no catalog in hand: one unforced load", listOf(false), catalogLoads)
        assertTrue("no share import", asked.isEmpty())
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.honor.HonorImportCoordinatorStageTest' --tests 'org.walktalkmeditate.pilgrim.ui.honor.HonorWaysViewModelTest'`
Expected: FAIL at compile, `No parameter with name 'installedRouteId' found` and `Unresolved reference 'openStage'`.

- [ ] **Step 3: Add the seams, the slot and `openStage`**

In `P/honor/HonorImportCoordinator.kt`, replace the class KDoc and both constructors (lines 30-56) with:

```kotlin
/**
 * The import half of iOS's `MainCoordinator` (`MainCoordinatorView.swift:20-22,193-303@7c200bf`,
 * shared-walk spec S1 §6.3), in the UI process: the one import state the
 * Ways sheet and the overview both show, the Way a finished import hands
 * to whichever screen opens its overview, and the overview's watch on its
 * media download ([WayMediaDownloader], S4 §8). Since `3b78728` it also
 * resolves stage links ([openStage]) on the same job, so the newest link of
 * either kind wins (`MainCoordinatorView.swift:270-330@3b78728`).
 *
 * A newer link cancels the older fetch, and a cancelled fetch writes no
 * state; its Way may still have been saved (pilgrim-ios #114, matched).
 * The walk-screen refusal and the link toast are the link routing's.
 * Everything runs on the main thread, as iOS's `@MainActor` coordinator
 * does, so a cancel and a resolution never interleave.
 */
@Singleton
class HonorImportCoordinator internal constructor(
    private val importShare: suspend (shareId: String) -> Way,
    private val honorEnabled: Boolean,
    private val scope: CoroutineScope,
    private val media: () -> WayMediaDownloader,
    /** iOS's four stage-link seams (`MainCoordinatorView.swift:43-46@3b78728`); the defaults reach no disk and no network. */
    private val installedRouteId: suspend () -> String? = { null },
    private val loadStageWay: suspend (wayId: String) -> Way? = { null },
    private val cachedCatalog: () -> PilgrimageCatalog? = { null },
    private val loadCatalog: suspend (force: Boolean) -> PilgrimageCatalog = {
        throw PilgrimageException(PilgrimageError.CATALOG_UNREACHABLE)
    },
) {
    @Inject
    constructor(
        importer: Provider<WayImporter>,
        releaseFlags: ReleaseFlags,
        downloader: Provider<WayMediaDownloader>,
        packages: Provider<PilgrimagePackageManager>,
        wayStore: Provider<WayStore>,
        catalogs: Provider<PilgrimageCatalogService>,
    ) : this(
        importShare = { importer.get().importShare(it) },
        honorEnabled = releaseFlags.honor,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        media = downloader::get,
        installedRouteId = { packages.get().installedRoute()?.routeId },
        loadStageWay = { id -> withContext(Dispatchers.IO) { wayStore.get().load(id) } },
        cachedCatalog = { catalogs.get().catalog.value },
        loadCatalog = { force -> catalogs.get().load(force) },
    )
```

After `val fetched` (line 64), add:

```kotlin
    private val _routeLink = MutableStateFlow<HonorRouteLink?>(null)

    /**
     * A route page a stage link resolved to, until the nav host opens it:
     * iOS's `pendingRouteLink`/`honorRouteLink` as one slot. Publishing it
     * clears [fetched] and the reverse, so the newest request wins and at
     * most one is set (`MainCoordinatorView.swift:336-369@3b78728`).
     */
    val routeLink: StateFlow<HonorRouteLink?> = _routeLink.asStateFlow()
```

In `openWay`, replace `_fetched.value = way.id` (line 120) with `publishFetched(way.id)`.

After `consumeFetched` (lines 134-136), add:

```kotlin
    fun consumeRouteLink(link: HonorRouteLink) {
        _routeLink.compareAndSet(link, null)
    }

    /**
     * iOS `openStage(routeId:index:)` (`MainCoordinatorView.swift:270-330@3b78728`):
     * what tapping that stage on the route page does. It cancels the share
     * import or stage resolution in flight, and a share's stranded spinner
     * goes with it. Then, on the main thread, it reads the installed route
     * once and the stage Way once, and only for the installed route;
     * resolves against the catalog in memory; and loads only when it must,
     * unforced for no catalog, forced exactly once for a route the catalog
     * lacks, each load announced "reaching for the route…". A replaced
     * resolution lands nothing and says nothing. The walk screen opening
     * cancels it ([cancelImport]), so a Begin always wins.
     *
     * iOS's `wirePilgrimageManagers()` has nothing to wire here: the package
     * and tiles managers take their walk signals at construction
     * (`PilgrimagePackageManager.kt:120`, `di/PilgrimageTilesModule.kt:78`).
     * A failed read of the installed route reads as none installed, which
     * opens the route page; iOS's read can't fail.
     */
    fun openStage(routeId: String, index: Int) {
        if (!honorEnabled) return
        importJob?.cancel()
        if (_state.value == HonorImportState.Fetching) _state.value = HonorImportState.Idle
        importJob = scope.launch {
            val installed = installedRouteOrNone()
            if (!isActive) return@launch
            val way = if (installed == routeId) loadStageWay(WayStore.stageWayId(routeId, index)) else null
            if (!isActive) return@launch
            fun resolve(catalog: PilgrimageCatalog?): StageLinkOutcome =
                StageLinkResolver.resolve(routeId, index, installed, stageWayExists = way != null, catalog = catalog)
            var outcome = resolve(cachedCatalog())
            var forced = false
            while (outcome == StageLinkOutcome.NeedsCatalog || (outcome == StageLinkOutcome.NotInCatalog && !forced)) {
                val force = outcome == StageLinkOutcome.NotInCatalog
                forced = forced || force
                _outcomes.tryEmit(HonorImportOutcome.ReachingForRoute)
                val catalog = try {
                    loadCatalog(force)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    if (!isActive) return@launch
                    val error = (e as? PilgrimageException)?.error ?: PilgrimageError.CATALOG_UNREACHABLE
                    _outcomes.tryEmit(HonorImportOutcome.CatalogFailed(error))
                    return@launch
                }
                if (!isActive) return@launch
                outcome = resolve(catalog)
            }
            when (outcome) {
                is StageLinkOutcome.Overview -> {
                    _outcomes.tryEmit(HonorImportOutcome.Listed)
                    publishFetched(outcome.wayId)
                }
                is StageLinkOutcome.RouteSheet -> {
                    _outcomes.tryEmit(HonorImportOutcome.Listed)
                    publishRouteLink(outcome.link)
                }
                StageLinkOutcome.NotInCatalog, StageLinkOutcome.NeedsCatalog ->
                    _outcomes.tryEmit(HonorImportOutcome.RouteNotInPilgrim)
            }
        }
    }

    private suspend fun installedRouteOrNone(): String? = try {
        installedRouteId()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (e: Exception) {
        null
    }

    private fun publishFetched(wayId: String) {
        _routeLink.value = null
        _fetched.value = wayId
    }

    private fun publishRouteLink(link: HonorRouteLink) {
        _fetched.value = null
        _routeLink.value = link
    }
```

Add these imports to `HonorImportCoordinator.kt`:

```kotlin
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogService
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageException
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
```

- [ ] **Step 4: The paste dispatches on the destination**

In `P/ui/honor/HonorWaysViewModel.kt`, replace U2's interim `open` with:

```kotlin
    /** iOS's `onPaste` (`MainTabView.swift:60-62@3b78728`): parses again and opens only what parses, never through the link routing. */
    fun open(text: String) {
        when (val link = HonorLink.parse(text = text)) {
            is HonorLinkDestination.Share -> imports.openWay(link.id)
            is HonorLinkDestination.Stage -> imports.openStage(link.routeId, link.index)
            HonorLinkDestination.Unrecognised, null -> Unit
        }
    }
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.honor.*' --tests 'org.walktalkmeditate.pilgrim.ui.honor.*' --tests 'org.walktalkmeditate.pilgrim.MainActivityIntentsTest'`
Expected: PASS, the existing coordinator, router and referrer tests unchanged (they use the defaulted seams).

- [ ] **Step 6: Full suite and the debug build (Hilt checks the new graph), then commit**

```bash
./gradlew :app:testDebugUnitTest assembleDebug
git add app/src/main/java/org/walktalkmeditate/pilgrim/honor/HonorImportCoordinator.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorWaysViewModel.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/honor/HonorImportCoordinatorStageTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/honor/HonorWaysViewModelTest.kt
git commit -m "feat(honor): the coordinator resolves stage links on the import's job (Phase 22 U5)" \
  -m "iOS's openStage: one shared job so the newest link of either kind wins, the catalog loop with exactly one forced load, and a route-link slot that clears the fetched Way and the reverse. A paste of a stage link resolves it too. Ports the coordinator's stage-link cases from MainCoordinatorHonorTests." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U6: The link page, a sheet of its own over Path

**Files:**
- Modify: `P/ui/navigation/PilgrimNavHost.kt` — `Routes` (after line 152), `HONOR_TRACKED_ROUTES` (873-878), `honorRoutes` (after the route-page sheet, 1059-1065), `honorPilgrimageArguments` (1108-1111, add a sibling), new helpers after `closePilgrimageRoute` (1122-1125), `openStoredWayOverview` (1140-1153), `openFetchedWayOverview` (1155-1166)
- Modify: `P/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt:687-690` (companion constants only)
- Modify: `P/ui/honor/pilgrimage/PilgrimageRouteScreen.kt` — `PilgrimageRouteContent` (90-138), `PilgrimageRouteSheet` (474-528)
- Test: `T/ui/navigation/PilgrimNavHostTest.kt`, `T/ui/honor/pilgrimage/PilgrimageSheetsBackTest.kt`

**Interfaces:**
- Consumes: U3's `HonorRouteLink` (`entry.id`, `focus`).
- Produces:
  - `PilgrimageRouteViewModel.ARG_FOCUS = "focus"`, `PilgrimageRouteViewModel.NO_FOCUS = -1` (`const val`, companion)
  - `Routes.HONOR_ROUTE_LINK_PATTERN = "honor_route_link/{routeId}?focus={focus}"`; `Routes.honorRouteLink(routeId: String, focus: Int?): String` (`"honor_route_link/<routeId>?focus=<n>"`, no query for a null focus)
  - `internal val honorRouteLinkArguments: List<NamedNavArgument>` (`routeId` string; `focus` `IntType`, default `NO_FOCUS`)
  - `internal fun NavHostController.openLinkedRoutePage(link: HonorRouteLink)`
  - `internal fun NavController.showsLinkedRoutePage(link: HonorRouteLink): Boolean`
  - `internal fun NavController.closeRouteLinkPage(entryId: String)`
  - `@Composable fun PilgrimageRouteLinkSheet(onClose: () -> Unit, onOpenStage: (wayId: String) -> Unit, viewModel: PilgrimageRouteViewModel = hiltViewModel())`
  - `PilgrimageRouteContent(…, modifier: Modifier = Modifier, leading: @Composable () -> Unit = { RouteBackButton(actions.onBack) })`; `internal fun RouteBackButton(onBack: () -> Unit)`, `internal fun RouteCloseButton(onClose: () -> Unit)`

- [ ] **Step 1: Write the failing nav tests**

In `T/ui/navigation/PilgrimNavHostTest.kt`:
- add imports `org.junit.Assert.assertNotEquals`, `org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry`, `org.walktalkmeditate.pilgrim.honor.HonorRouteLink`;
- in `honorBackStack`'s graph (lines 513-532) add, after the `HONOR_PILGRIMAGE_PATTERN` sheet:

```kotlin
                honorSheet(Routes.HONOR_ROUTE_LINK_PATTERN, arguments = honorRouteLinkArguments) {}
                composable(Routes.SETTINGS) {}
                composable(Routes.OFFLINE_MAPS) {}
```

- in both flag tests (lines 455-475) add `Routes.HONOR_ROUTE_LINK_PATTERN` to the `listOf(...)`;
- add to the companion: 

```kotlin
        val FRANCES = PilgrimageCatalogEntry(
            id = "camino-frances",
            name = "Camino Francés",
            names = emptyMap(),
            country = "ES",
            region = "Europe",
            distanceKm = 780.0,
            tradition = "christian",
            stageCount = 33,
            bytes = 1_000,
            placesPerStage = 0.0,
            sparse = false,
        )
```

- add, before `/** Path → Ways sheet → catalog → a route page. */`:

```kotlin
    // Stage links (stage-links spec Q1, Q4): the link page, a sheet of its own over Path.

    @Test
    fun `a link page names its route and its focus, and no focus when the link has none`() {
        assertEquals("honor_route_link/{routeId}?focus={focus}", Routes.HONOR_ROUTE_LINK_PATTERN)
        assertEquals("honor_route_link/camino-frances?focus=13", Routes.honorRouteLink("camino-frances", 13))
        assertEquals("honor_route_link/camino-frances", Routes.honorRouteLink("camino-frances", null))
    }

    @Test
    fun `a link page opens over the Path tab from another tab, and Close leaves only Path`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.navigateToTab(Routes.HOME) }

        onMain { nav.openLinkedRoutePage(link(13)) }

        onMain {
            val page = nav.currentBackStackEntry!!
            assertEquals(Routes.HONOR_ROUTE_LINK_PATTERN, page.destination.route)
            assertEquals("camino-frances", page.arguments?.getString(PilgrimageRouteViewModel.ARG_ROUTE_ID))
            assertEquals(13, page.arguments?.getInt(PilgrimageRouteViewModel.ARG_FOCUS))
            assertEquals(Routes.PATH, nav.previousBackStackEntry?.destination?.route)
        }
        onMain { nav.closeRouteLinkPage(nav.currentBackStackEntry!!.id) }
        onMain {
            assertEquals(Routes.PATH, nav.currentBackStackEntry?.destination?.route)
            assertNull(nav.previousBackStackEntry)
        }
    }

    @Test
    fun `a link page takes the place of the Ways sheet, the catalog and a route page`() {
        val nav = pilgrimageSheets()

        onMain { nav.openLinkedRoutePage(link(13)) }

        onMain { assertEquals(Routes.HONOR_ROUTE_LINK_PATTERN, nav.currentBackStackEntry?.destination?.route) }
        assertOnlyPathBeneath(nav)
    }

    @Test
    fun `a link page takes the place of an overview up over the Journal`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.navigateToTab(Routes.HOME) }
        onMain { nav.navigate(Routes.walkSummary(7L, walkAgainDoor = true)) }
        onMain { nav.openHonorOverviewFromSummary(7L) }

        onMain { nav.openLinkedRoutePage(link(13)) }

        onMain { assertEquals(Routes.HONOR_ROUTE_LINK_PATTERN, nav.currentBackStackEntry?.destination?.route) }
        assertOnlyPathBeneath(nav)
    }

    // iOS `testASecondStageLinkSwapsTheOpenRouteSheet`; Review Focus 3.
    @Test
    fun `the same link twice keeps the page and its entry, and another stage replaces it`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.openLinkedRoutePage(link(13)) }
        val first = composeRule.runOnIdle { nav.currentBackStackEntry!!.id }

        onMain { nav.openLinkedRoutePage(link(13)) }
        onMain { assertEquals("same entry, same ViewModel", first, nav.currentBackStackEntry!!.id) }

        onMain { nav.openLinkedRoutePage(link(19)) }
        onMain {
            val second = nav.currentBackStackEntry!!
            assertNotEquals(first, second.id)
            assertEquals(19, second.arguments?.getInt(PilgrimageRouteViewModel.ARG_FOCUS))
            assertEquals(Routes.PATH, nav.previousBackStackEntry?.destination?.route)
        }
        onMain { nav.openLinkedRoutePage(link(null)) }
        onMain {
            assertEquals(
                PilgrimageRouteViewModel.NO_FOCUS,
                nav.currentBackStackEntry?.arguments?.getInt(PilgrimageRouteViewModel.ARG_FOCUS),
            )
        }
    }

    /** Q4's risk: a slide-down that finishes after a newer link replaced its page must not close the new one. */
    @Test
    fun `a Close from a page a newer link replaced closes nothing`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.openLinkedRoutePage(link(13)) }
        val stale = composeRule.runOnIdle { nav.currentBackStackEntry!!.id }
        onMain { nav.openLinkedRoutePage(link(19)) }

        onMain { nav.closeRouteLinkPage(stale) }

        onMain { assertEquals(19, nav.currentBackStackEntry?.arguments?.getInt(PilgrimageRouteViewModel.ARG_FOCUS)) }
    }

    // iOS `testChoosingAStageInsideTheRouteSheetParksTheWay`.
    @Test
    fun `choosing a stage on a link page opens its overview in the page's place, over Path`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.openLinkedRoutePage(link(13)) }

        onMain { nav.openStoredWayOverview(STAGE_WAY) }

        onMain { assertEquals(STAGE_WAY, nav.currentBackStackEntry?.arguments?.getString(HonorOverviewViewModel.ARG_WAY_ID)) }
        assertOnlyPathBeneath(nav)
    }

    @Test
    fun `a Way a link fetched takes a link page's place`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.openLinkedRoutePage(link(13)) }

        onMain { nav.openFetchedWayOverview(SHARED_WAY) }

        onMain { assertEquals(SHARED_WAY, nav.currentBackStackEntry?.arguments?.getString(HonorOverviewViewModel.ARG_WAY_ID)) }
        assertOnlyPathBeneath(nav)
    }

    /** Q10: pushing a composable pops dialogs, with no `popUpTo` of its own, so no Replace or Remove is left reachable over a walk. */
    @Test
    fun `the walk screen opening takes a link page away with it`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.openLinkedRoutePage(link(13)) }

        onMain { nav.navigate(Routes.activeWalk(WalkMode.Wander)) }

        onMain {
            assertEquals(Routes.ACTIVE_WALK, nav.currentBackStackEntry?.destination?.route)
            assertFalse(nav.holds(Routes.HONOR_ROUTE_LINK_PATTERN))
        }
    }

    // Review Focus 5.
    @Test
    fun `a link page from deep in Settings opens over Path, and Settings keeps its place`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.navigateToTab(Routes.SETTINGS) }
        onMain { nav.navigate(Routes.OFFLINE_MAPS) }

        onMain { nav.openLinkedRoutePage(link(13)) }
        onMain { assertEquals(Routes.PATH, nav.previousBackStackEntry?.destination?.route) }
        onMain { nav.closeRouteLinkPage(nav.currentBackStackEntry!!.id) }
        onMain { nav.navigateToTab(Routes.SETTINGS) }

        onMain { assertEquals(Routes.OFFLINE_MAPS, nav.currentBackStackEntry?.destination?.route) }
    }

    /** Q1: every landing leaves one Honor surface at most: the Ways sheet's stack, an overview, or a link page. */
    @Test
    fun `after any landing the back stack holds one Honor surface at most`() {
        val nav = honorBackStack(start = Routes.PATH)
        val starts: List<(NavHostController) -> Unit> = listOf(
            { it.navigate(Routes.HONOR_WAYS) },
            {
                it.navigate(Routes.HONOR_WAYS)
                it.navigate(Routes.HONOR_PILGRIMAGES)
                it.navigate(Routes.honorPilgrimage("camino-frances"))
            },
            { it.openStoredWayOverview(SHARED_WAY) },
            { it.openLinkedRoutePage(link(13)) },
        )
        val landings: List<(NavHostController) -> Unit> = listOf(
            { it.openLinkedRoutePage(link(19)) },
            { it.openFetchedWayOverview(SHARED_WAY) },
            { it.openStoredWayOverview(STAGE_WAY) },
        )
        starts.forEach { start ->
            landings.forEach { land ->
                onMain { start(nav) }
                onMain { land(nav) }
                onMain {
                    val up = listOf(Routes.HONOR_WAYS, Routes.HONOR_OVERVIEW_PATTERN, Routes.HONOR_ROUTE_LINK_PATTERN).count { nav.holds(it) }
                    assertEquals(1, up)
                }
                onMain { nav.popBackStack(Routes.PATH, inclusive = false) }
            }
        }
    }

    private fun link(focus: Int?) = HonorRouteLink(FRANCES, release = "v1.12.0", focus = focus)

    private fun NavHostController.holds(route: String): Boolean = try {
        getBackStackEntry(route)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
```

- [ ] **Step 2: Write the failing sheet tests**

In `T/ui/honor/pilgrimage/PilgrimageSheetsBackTest.kt`, add imports `androidx.lifecycle.SavedStateHandle`, `androidx.compose.ui.test.assertCountEquals`, `androidx.compose.ui.test.onAllNodesWithContentDescription`, `org.junit.Assert.assertNull`, `org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness`, `org.walktalkmeditate.pilgrim.ui.navigation.closeRouteLinkPage`, `org.walktalkmeditate.pilgrim.ui.navigation.honorRouteLinkArguments`, and add, after `pressBackOnTheTopWindow`:

```kotlin
    // Stage-links spec Q4: the link page's Close, Back and swipe close it alone, to Path.

    @Test
    fun `a link page shows Close in the back arrow's place, and Close leaves only Path`() {
        val nav = linkPageSheets()
        composeRule.runOnIdle { nav.navigate(Routes.honorRouteLink(PilgrimagePackageHarness.ROUTE_ID, null)) }
        composeRule.waitUntil(WAIT_MILLIS) { nav.currentBackStackEntry?.destination?.route == Routes.HONOR_ROUTE_LINK_PATTERN }
        composeRule.onNodeWithText("Download").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Pilgrimages").assertCountEquals(0)

        composeRule.onNodeWithText("Close").performClick()

        composeRule.waitUntil(WAIT_MILLIS) { nav.currentBackStackEntry?.destination?.route == Routes.PATH }
        composeRule.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    @Test
    fun `system Back on a link page closes it alone, to Path`() {
        val nav = linkPageSheets()
        composeRule.runOnIdle { nav.navigate(Routes.honorRouteLink(PilgrimagePackageHarness.ROUTE_ID, null)) }
        composeRule.waitUntil(WAIT_MILLIS) { nav.currentBackStackEntry?.destination?.route == Routes.HONOR_ROUTE_LINK_PATTERN }
        composeRule.onNodeWithText("Download").assertIsDisplayed()

        pressBackOnTheTopWindow()

        composeRule.waitUntil(WAIT_MILLIS) { nav.currentBackStackEntry?.destination?.route == Routes.PATH }
        composeRule.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    /** Q5: a link page whose route the catalog doesn't list ends Gone and closes itself, silently. */
    @Test
    fun `a link page for a route the catalog doesn't list closes itself to Path`() {
        val nav = linkPageSheets()

        composeRule.runOnIdle { nav.navigate(Routes.honorRouteLink(PilgrimagePackageHarness.NORTE_ID, 0)) }

        composeRule.waitUntil(WAIT_MILLIS) { nav.currentBackStackEntry?.destination?.route == Routes.PATH }
    }

    /** Path and the link page wired as the app's host wires it, the page's ViewModel built on the world. */
    private fun linkPageSheets(): NavHostController {
        world.holdCatalog()
        var nav: NavHostController? = null
        composeRule.setContent {
            PilgrimTheme {
                val controller = rememberNavController()
                SideEffect { nav = controller }
                NavHost(navController = controller, startDestination = Routes.PATH) {
                    composable(Routes.PATH) {}
                    honorSheet(Routes.HONOR_ROUTE_LINK_PATTERN, arguments = honorRouteLinkArguments) { entry ->
                        val routeId = requireNotNull(entry.arguments?.getString(PilgrimageRouteViewModel.ARG_ROUTE_ID))
                        val focus = entry.arguments?.getInt(PilgrimageRouteViewModel.ARG_FOCUS) ?: PilgrimageRouteViewModel.NO_FOCUS
                        PilgrimageRouteLinkSheet(
                            onClose = { controller.closeRouteLinkPage(entry.id) },
                            onOpenStage = {},
                            viewModel = remember(entry.id) {
                                world.routeViewModel(
                                    saved = SavedStateHandle(
                                        mapOf(PilgrimageRouteViewModel.ARG_ROUTE_ID to routeId, PilgrimageRouteViewModel.ARG_FOCUS to focus),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(nav)
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHostTest' --tests 'org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.PilgrimageSheetsBackTest'`
Expected: FAIL at compile, `Unresolved reference 'HONOR_ROUTE_LINK_PATTERN'`.

- [ ] **Step 4: The focus argument's names**

In `P/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt`, replace the companion (lines 687-690) with:

```kotlin
    companion object {
        const val ARG_ROUTE_ID = "routeId"

        /** The 0-based stage a link brings into view; absent on the catalog's page (stage-links spec Q4). */
        const val ARG_FOCUS = "focus"

        /** The `focus` argument's default: no stage to bring into view. */
        const val NO_FOCUS = -1

        internal const val KEY_REDRAW_NOTICE = "showRedrawNotice"
    }
```

- [ ] **Step 5: The route, its arguments, its registration, and the helpers**

In `P/ui/navigation/PilgrimNavHost.kt`, add `import org.walktalkmeditate.pilgrim.honor.HonorRouteLink`, then:

(a) In `Routes`, after `fun honorPilgrimage(...)` (line 152):

```kotlin

    /**
     * A route's page opened by a stage link: a sheet of its own over the
     * Path tab, iOS's route sheet keyed by `entry.id:focus`
     * (`StageLink.swift:11@3b78728`, `MainTabView.swift:79-91@3b78728`).
     * The focus is the 0-based stage to bring into view, absent for none.
     */
    private const val HONOR_ROUTE_LINK_PREFIX = "honor_route_link"
    const val HONOR_ROUTE_LINK_PATTERN =
        "$HONOR_ROUTE_LINK_PREFIX/{${PilgrimageRouteViewModel.ARG_ROUTE_ID}}" +
            "?${PilgrimageRouteViewModel.ARG_FOCUS}={${PilgrimageRouteViewModel.ARG_FOCUS}}"
    fun honorRouteLink(routeId: String, focus: Int?): String =
        "$HONOR_ROUTE_LINK_PREFIX/${Uri.encode(routeId)}" + (focus?.let { "?${PilgrimageRouteViewModel.ARG_FOCUS}=$it" } ?: "")
```

(b) `HONOR_TRACKED_ROUTES` (lines 873-878) becomes:

```kotlin
private val HONOR_TRACKED_ROUTES = listOf(
    Routes.ACTIVE_WALK,
    Routes.HONOR_WAYS,
    Routes.WALK_SUMMARY_PATTERN,
    Routes.HONOR_OVERVIEW_PATTERN,
    Routes.HONOR_ROUTE_LINK_PATTERN,
)
```

(c) In `honorRoutes`, after the `honorSheet(Routes.HONOR_PILGRIMAGE_PATTERN, …) { … }` block (ending line 1065):

```kotlin
    honorSheet(Routes.HONOR_ROUTE_LINK_PATTERN, arguments = honorRouteLinkArguments) { entry ->
        org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.PilgrimageRouteLinkSheet(
            onClose = { navController.closeRouteLinkPage(entry.id) },
            onOpenStage = navController::openStoredWayOverview,
        )
    }
```

(d) After `honorPilgrimageArguments` (line 1111):

```kotlin

/** The link page's route id, required, and its focus, [PilgrimageRouteViewModel.NO_FOCUS] when the link names none. */
internal val honorRouteLinkArguments = listOf(
    navArgument(PilgrimageRouteViewModel.ARG_ROUTE_ID) { type = NavType.StringType },
    navArgument(PilgrimageRouteViewModel.ARG_FOCUS) {
        type = NavType.IntType
        defaultValue = PilgrimageRouteViewModel.NO_FOCUS
    },
)
```

(e) After `closePilgrimageRoute` (line 1125):

```kotlin

/**
 * A stage link's route page over the Path tab (stage-links spec Q1, Q4;
 * iOS `openRouteLink`, `MainCoordinatorView.swift:332-348@3b78728`). The
 * same route at the same focus already on top stays, as iOS keeps a sheet
 * whose item has the same id. Otherwise an overview, the Ways sheet with
 * whatever is over it, and a link page already up give up their place in
 * this one step, so no two Honor surfaces stand together. Two explicit
 * calls, never `launchSingleTop`, which would keep the old entry and its
 * focus.
 */
internal fun NavHostController.openLinkedRoutePage(link: HonorRouteLink) {
    if (showsLinkedRoutePage(link)) return
    popBackStack(Routes.HONOR_OVERVIEW_PATTERN, inclusive = true)
    popBackStack(Routes.HONOR_WAYS, inclusive = true)
    popBackStack(Routes.HONOR_ROUTE_LINK_PATTERN, inclusive = true)
    if (currentDestination?.route != Routes.PATH) navigateToTab(Routes.PATH)
    navigate(Routes.honorRouteLink(link.entry.id, link.focus))
}

/** The page on top is [link]'s route at [link]'s focus: iOS's `HonorRouteLink.id` (`StageLink.swift:11@3b78728`). */
internal fun NavController.showsLinkedRoutePage(link: HonorRouteLink): Boolean {
    val entry = currentBackStackEntry ?: return false
    val arguments = entry.arguments ?: return false
    return entry.destination.route == Routes.HONOR_ROUTE_LINK_PATTERN &&
        arguments.getString(PilgrimageRouteViewModel.ARG_ROUTE_ID) == link.entry.id &&
        arguments.getInt(PilgrimageRouteViewModel.ARG_FOCUS, PilgrimageRouteViewModel.NO_FOCUS) ==
        (link.focus ?: PilgrimageRouteViewModel.NO_FOCUS)
}

/**
 * The link page's Close, swipe and system Back, which land on Path: only
 * [entryId]'s page goes, so a slide-down that ends after a newer link
 * replaced the page closes nothing.
 */
internal fun NavController.closeRouteLinkPage(entryId: String) {
    if (currentBackStackEntry?.id == entryId) popBackStack()
}
```

(f) `openStoredWayOverview` (lines 1140-1153) becomes:

```kotlin
/**
 * A listed Way's overview, from its "Shared with you" row, a route page's
 * stage (the catalog's page or a link's), or a finished import: it takes
 * the place of the Ways sheet (and the picker, or the catalog and route
 * page, over it), of a link page, or of an overview already up, as iOS
 * swaps the overview's Way (S1 §8.16); anywhere else it opens over the
 * screen showing.
 */
internal fun NavController.openStoredWayOverview(wayId: String) {
    val replaces = listOf(Routes.HONOR_WAYS, Routes.HONOR_ROUTE_LINK_PATTERN, Routes.HONOR_OVERVIEW_PATTERN)
        .firstOrNull(::hasBackStackEntry)
    navigate(Routes.honorOverview(HonorWayChoice.Stored(wayId))) {
        replaces?.let { popUpTo(it) { inclusive = true } }
        launchSingleTop = true
    }
}
```

(g) `openFetchedWayOverview` (lines 1155-1166) becomes:

```kotlin
/**
 * A Way a link or a paste fetched opens over the Path tab, so Close lands
 * there, as it does on iOS, whose link switched the tab beneath (S2 §4.1).
 * An overview already up, the Ways sheet with its picker, or a link page
 * gives up its place unsaved, before the tab switch, so `saveState` never
 * saves a dialog into Path's state; any other screen is left as a tab tap
 * leaves it.
 */
internal fun NavHostController.openFetchedWayOverview(wayId: String) {
    popBackStack(Routes.HONOR_OVERVIEW_PATTERN, inclusive = true)
    popBackStack(Routes.HONOR_WAYS, inclusive = true)
    popBackStack(Routes.HONOR_ROUTE_LINK_PATTERN, inclusive = true)
    if (currentDestination?.route != Routes.PATH) navigateToTab(Routes.PATH)
    navigate(Routes.honorOverview(HonorWayChoice.Stored(wayId))) { launchSingleTop = true }
}
```

- [ ] **Step 6: The page's leading slot and the link sheet**

In `P/ui/honor/pilgrimage/PilgrimageRouteScreen.kt`:

(a) `PilgrimageRouteContent`'s signature (lines 99-109) gains a last parameter, and its `HonorSheetFrame` uses it:

```kotlin
@Composable
fun PilgrimageRouteContent(
    state: PilgrimageRouteUiState,
    phase: PilgrimagePackageManager.Phase,
    tilesPhase: PilgrimageTilesManager.Phase,
    mapsRow: PilgrimageMapsRowState?,
    units: UnitSystem,
    alert: PilgrimageRouteAlert?,
    actions: PilgrimageRouteActions,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = { RouteBackButton(actions.onBack) },
) {
    val page = (state as? PilgrimageRouteUiState.Ready)?.page
    val busy = page != null && PilgrimageRouteModel.isBusy(phase, tilesPhase, page.isHeld)
    HonorSheetFrame(
        title = page?.entry?.name.orEmpty(),
        leading = leading,
        trailing = {
            if (page?.isInstalled == true) OverflowMenu(enabled = !busy, onRemove = actions.onRemove)
        },
        modifier = modifier,
        scrollable = false,
    ) {
```

(the rest of the function body is unchanged). Update its KDoc's first sentence to read `…the route's name in the bar, [leading] at the start (Back to the catalog, or a link page's Close), and, for the installed route, the overflow's Remove; …`.

(b) After `PilgrimageRouteContent`, add:

```kotlin
/** iOS's back button on the catalog's route page, titled with the catalog it returns to (P4 §4.7). */
@Composable
internal fun RouteBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.pilgrimage_catalog_title),
            tint = pilgrimColors.stone,
        )
    }
}

/** iOS's "Close" on the route sheet a stage link opens (`MainTabView.swift:85-87@3b78728`), drawn as the overview draws its own. */
@Composable
internal fun RouteCloseButton(onClose: () -> Unit) {
    TextButton(onClick = onClose) {
        Text(stringResource(R.string.honor_close), style = pilgrimType.button, color = pilgrimColors.stone)
    }
}
```

(c) Replace `PilgrimageRouteSheet` (lines 474-528) with:

```kotlin
/**
 * The route page as a second sheet over the catalog (owner decision 8, P4
 * A-1): system Back and the leading control slide it down and return to
 * the catalog; a swipe closes both, as iOS's swipe takes the one sheet
 * holding both ([onClosed]). A stage with its Way slides the page down and
 * opens the stage's overview. A route the catalog no longer lists closes
 * back to the catalog.
 */
@Composable
fun PilgrimageRouteSheet(
    onBack: () -> Unit,
    onClosed: () -> Unit,
    onOpenStage: (wayId: String) -> Unit,
    viewModel: PilgrimageRouteViewModel = hiltViewModel(),
) {
    HonorSheetHost(onDismissed = onClosed, onBack = onBack) { hideThen ->
        PilgrimageRouteSheetBody(viewModel, hideThen, leave = onBack, onOpenStage = onOpenStage) { goBack ->
            RouteBackButton(goBack)
        }
    }
}

/**
 * The route page a stage link opened, iOS's route sheet
 * (`MainTabView.swift:79-91@3b78728`): a sheet of its own over the Path
 * tab, with "Close" leading. Close, a swipe and system Back all close it
 * alone ([onClose]), since nothing lies beneath it but Path. A stage with
 * its Way slides the page down and opens the stage's overview in its
 * place. A route the catalog no longer lists closes it, silently.
 */
@Composable
fun PilgrimageRouteLinkSheet(
    onClose: () -> Unit,
    onOpenStage: (wayId: String) -> Unit,
    viewModel: PilgrimageRouteViewModel = hiltViewModel(),
) {
    HonorSheetHost(onDismissed = onClose) { hideThen ->
        PilgrimageRouteSheetBody(viewModel, hideThen, leave = onClose, onOpenStage = onOpenStage) { close ->
            RouteCloseButton(close)
        }
    }
}

/** Both route sheets' body: one ViewModel, one page; only how the page is left differs. */
@Composable
private fun PilgrimageRouteSheetBody(
    viewModel: PilgrimageRouteViewModel,
    hideThen: (() -> Unit) -> Unit,
    leave: () -> Unit,
    onOpenStage: (wayId: String) -> Unit,
    leading: @Composable (onLeave: () -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val tilesPhase by viewModel.tilesPhase.collectAsStateWithLifecycle()
    val mapsRow by viewModel.mapsRow.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val leaving by rememberUpdatedState(leave)
    val openStage by rememberUpdatedState(onOpenStage)
    val gone = state == PilgrimageRouteUiState.Gone
    LaunchedEffect(viewModel) {
        viewModel.opened.collect { wayId -> hideThen { openStage(wayId) } }
    }
    LaunchedEffect(gone) {
        if (gone) hideThen(leaving)
    }
    val goBack = { hideThen(leaving) }
    PilgrimageRouteContent(
        state = state,
        phase = phase,
        tilesPhase = tilesPhase,
        mapsRow = mapsRow,
        units = units,
        alert = alert,
        actions = PilgrimageRouteActions(
            onBack = goBack,
            onDownload = viewModel::onDownloadTapped,
            onOpenNext = viewModel::openNext,
            onOpenStage = viewModel::open,
            onRetryStages = viewModel::retryStages,
            onRemove = viewModel::onRemoveTapped,
            onConfirmReplace = viewModel::confirmReplace,
            onConfirmRemove = viewModel::confirmRemove,
            onConfirmDownloadFirst = viewModel::confirmDownloadFirst,
            onDismissAlert = viewModel::dismissAlert,
            onSaveMaps = viewModel::onSaveMaps,
            onCancelMaps = viewModel::onCancelMaps,
        ),
        leading = { leading(goBack) },
    )
}
```

- [ ] **Step 7: Run the tests to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHostTest' --tests 'org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.*'`
Expected: PASS, the catalog page's Back test and every semantics test unchanged.

- [ ] **Step 8: Full suite and lint, then commit**

```bash
./gradlew :app:testDebugUnitTest lintDebug
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageRouteScreen.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHostTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageSheetsBackTest.kt
git commit -m "feat(honor): a stage link's route page, a sheet of its own over Path (Phase 22 U6)" \
  -m "honor_route_link/{routeId}?focus={n}: the same route and focus keeps its page, another replaces it, and it takes the place of the Ways sheet, an overview or an older page in one step. Close, swipe and Back close it alone; a stage opens its overview in its place." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U7: The landing opens, parks and drops a route link

**Files:**
- Modify: `P/ui/navigation/PilgrimNavHost.kt` — the summary's `onWalkAgain` (603-621, removing `honorHost?.dropParkedWay()` at 615), `FetchedWayLanding`'s KDoc (906), a new `routeLinkLanding` after `fetchedWayLanding` (927-936), `HonorLinksLanding` (960-1007), a new `summaryWalkAgain` after `openHonorOverviewFromSummary` (1127-1133)
- Modify: `P/ui/honor/HonorWaysViewModel.kt:126-168` (`HonorImportHostViewModel`: add `routeLink`, `consumeRouteLink`; delete `dropParkedWay` at 147-150)
- Test: `T/ui/navigation/PilgrimNavHostTest.kt`

**Interfaces:**
- Consumes: U5's `HonorImportCoordinator.routeLink`, `consumeRouteLink(link)`; U6's `openLinkedRoutePage(link)`; `WalkAgainResult(sourceWalkId: Long, built: Boolean)` (`P/ui/honor/WalkAgainDoor.kt:66`).
- Produces:
  - `internal fun routeLinkLanding(backStack: List<String>, parkedBehindSummary: Boolean = false): FetchedWayLanding`
  - `val HonorImportHostViewModel.routeLink: StateFlow<HonorRouteLink?>`, `fun HonorImportHostViewModel.consumeRouteLink(link: HonorRouteLink)`
  - `internal fun summaryWalkAgain(result: WalkAgainResult, openOverview: (sourceWalkId: Long) -> Unit, closeSummary: () -> Unit)`
  - removed: `HonorImportHostViewModel.dropParkedWay()`

- [ ] **Step 1: Write the failing landing tests**

In `T/ui/navigation/PilgrimNavHostTest.kt`, add `import org.walktalkmeditate.pilgrim.ui.honor.WalkAgainResult`, and add after `a Way parked behind a summary gives way to the overview walk this again opens in its place`:

```kotlin
    // Stage-links spec Q1, Q2: where a route link a stage link resolved to lands.

    @Test
    fun `a route link opens over any screen, the Ways sheet included, parks behind a summary, and a walk drops it`() {
        listOf(
            listOf(Routes.PATH),
            listOf(Routes.PATH, Routes.HOME),
            listOf(Routes.PATH, Routes.HOME, Routes.GOSHUIN),
            listOf(Routes.PATH, Routes.HONOR_WAYS),
            listOf(Routes.PATH, Routes.HONOR_WAYS, Routes.HONOR_OWN_WALKS),
            listOf(Routes.PATH, Routes.HONOR_WAYS, Routes.HONOR_PILGRIMAGES, Routes.HONOR_PILGRIMAGE_PATTERN),
            listOf(Routes.PATH, Routes.HONOR_ROUTE_LINK_PATTERN),
            listOf(Routes.PATH, Routes.HOME, Routes.HONOR_OVERVIEW_PATTERN),
        ).forEach { assertEquals(it.toString(), FetchedWayLanding.PRESENT, routeLinkLanding(it)) }
        listOf(listOf(Routes.WELCOME), listOf(Routes.PERMISSIONS), listOf(Routes.BREATH), emptyList()).forEach {
            assertEquals(it.toString(), FetchedWayLanding.WAIT, routeLinkLanding(it))
        }
        listOf(
            listOf(Routes.PATH, Routes.WALK_SUMMARY_PATTERN),
            listOf(Routes.PATH, Routes.HOME, Routes.GOSHUIN, Routes.WALK_SUMMARY_PATTERN),
            listOf(Routes.PATH, Routes.WALK_SUMMARY_PATTERN, Routes.WALK_SHARE_PATTERN),
        ).forEach { assertEquals(it.toString(), FetchedWayLanding.PARK, routeLinkLanding(it)) }
        assertEquals(FetchedWayLanding.DROP, routeLinkLanding(listOf(Routes.PATH, Routes.ACTIVE_WALK)))
        assertEquals(FetchedWayLanding.DROP, routeLinkLanding(listOf(Routes.PATH, Routes.ACTIVE_WALK, Routes.MEDITATION)))
    }

    // iOS `testStageLinkOverASummaryParksUntilItCloses`, `testAWayParkedBehindAWalkAgainOverviewIsNotStranded`.
    @Test
    fun `a route link parked behind a summary opens once the summary goes, from any host, unless walk this again took its place`() {
        listOf(
            listOf(Routes.PATH),
            listOf(Routes.PATH, Routes.HOME),
            listOf(Routes.PATH, Routes.HOME, Routes.GOSHUIN),
        ).forEach { assertEquals(it.toString(), FetchedWayLanding.PRESENT, routeLinkLanding(it, parkedBehindSummary = true)) }
        assertEquals(
            FetchedWayLanding.DROP,
            routeLinkLanding(listOf(Routes.PATH, Routes.HOME, Routes.HONOR_OVERVIEW_PATTERN), parkedBehindSummary = true),
        )
    }

    // iOS `testWalkAgainOnAWalkTooShortToBuildKeepsAParkedRouteLink` (`MainCoordinatorView.swift:420-424@3b78728`).
    @Test
    fun `walk this again with no Way built only closes the summary, so a link parked behind it still opens`() {
        val opened = mutableListOf<Long>()
        var closed = 0

        summaryWalkAgain(WalkAgainResult(sourceWalkId = 7L, built = false), openOverview = { opened += it }, closeSummary = { closed++ })

        assertEquals(1, closed)
        assertTrue("nothing opens in the summary's place", opened.isEmpty())
        assertEquals(FetchedWayLanding.PRESENT, routeLinkLanding(listOf(Routes.PATH, Routes.HOME), parkedBehindSummary = true))
        assertEquals(FetchedWayLanding.PRESENT, fetchedWayLanding(listOf(Routes.PATH, Routes.HOME), parkedBehindSummary = true))
    }

    @Test
    fun `walk this again with a Way built opens its overview in the summary's place`() {
        val opened = mutableListOf<Long>()
        var closed = 0

        summaryWalkAgain(WalkAgainResult(sourceWalkId = 7L, built = true), openOverview = { opened += it }, closeSummary = { closed++ })

        assertEquals(listOf(7L), opened)
        assertEquals(0, closed)
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHostTest'`
Expected: FAIL at compile, `Unresolved reference 'routeLinkLanding'`.

- [ ] **Step 3: The host's slot, and no more drop**

In `P/ui/honor/HonorWaysViewModel.kt`, add `import org.walktalkmeditate.pilgrim.honor.HonorRouteLink`; in `HonorImportHostViewModel`, after `val fetched` (line 137) add:

```kotlin
    /** A route page a stage link resolved to, no screen having opened it yet (stage-links spec Q1). */
    val routeLink: StateFlow<HonorRouteLink?> = imports.routeLink
```

after `consumeFetched` (lines 143-145) add:

```kotlin
    fun consumeRouteLink(link: HonorRouteLink) {
        imports.consumeRouteLink(link)
    }
```

and delete `dropParkedWay` with its KDoc (lines 147-150). Update the class KDoc's first line to `The app's end of the import and the links: a listed Way or a route page no sheet is up to take, …`.

- [ ] **Step 4: `routeLinkLanding`, the landing, and `summaryWalkAgain`**

In `P/ui/navigation/PilgrimNavHost.kt`, add `import org.walktalkmeditate.pilgrim.ui.honor.WalkAgainResult`, then:

(a) `FetchedWayLanding`'s KDoc (line 906) becomes `/** What a Way an import just listed, or a stage link's route page, does on the screen showing now. */`.

(b) After `fetchedWayLanding` (line 936):

```kotlin

/**
 * iOS `openRouteLink` (`MainCoordinatorView.swift:332-348@3b78728`) over
 * [backStack]: [fetchedWayLanding]'s answers, except that a route link never
 * waits on the Ways sheet, which takes only Ways; the page takes its place.
 * One parked behind a summary gives way to the overview "walk this again"
 * opens in the summary's place, as iOS's `walkAgain` clears it.
 */
internal fun routeLinkLanding(backStack: List<String>, parkedBehindSummary: Boolean = false): FetchedWayLanding {
    val screen = honorLinkScreen(backStack)
    return when {
        screen.walkScreenUp -> FetchedWayLanding.DROP
        screen.inSetup -> FetchedWayLanding.WAIT
        screen.summaryUp -> FetchedWayLanding.PARK
        parkedBehindSummary && screen.overviewUp -> FetchedWayLanding.DROP
        else -> FetchedWayLanding.PRESENT
    }
}
```

(c) Replace `HonorLinksLanding` with its KDoc (lines 960-1007):

```kotlin
/**
 * The app's end of the links and a shared walk's import: the back stack
 * goes to the link routing, which holds a link through setup and cancels
 * an import as the walk screen opens; the Path switch it asks for is made
 * here; a Way the import listed with no Ways sheet up to take it opens its
 * overview over the Path tab, or waits behind a summary, its own overview
 * already showing staying and gathering again; and a stage link's route
 * page opens over Path or waits behind a summary the same way. Each runs
 * again on every back-stack change, so a summary closing from any host is
 * what promotes a parked one (stage-links spec Q2): there is no dismiss
 * callback to forget.
 */
@Composable
private fun HonorLinksLanding(
    navController: NavHostController,
    backStack: List<String>,
    host: HonorImportHostViewModel,
) {
    val screen = honorLinkScreen(backStack)
    LaunchedEffect(screen) { host.screenChanged(screen) }
    DisposableEffect(host) { onDispose { host.screenGone() } }

    val pathSwitch by host.pathSwitch.collectAsState()
    LaunchedEffect(pathSwitch) {
        if (!pathSwitch) return@LaunchedEffect
        host.pathSwitchTaken()
        if (navController.currentDestination?.route != Routes.PATH) navController.navigateToTab(Routes.PATH)
    }

    val fetched by host.fetched.collectAsState()
    val routeLink by host.routeLink.collectAsState()
    var parkedBehindSummary by remember { mutableStateOf(false) }
    LaunchedEffect(fetched, routeLink, backStack) {
        val wayId = fetched
        val link = routeLink
        when {
            wayId != null -> when (fetchedWayLanding(backStack, parkedBehindSummary)) {
                FetchedWayLanding.WAIT -> Unit
                FetchedWayLanding.PARK -> parkedBehindSummary = true
                FetchedWayLanding.DROP -> host.consumeFetched(wayId)
                FetchedWayLanding.PRESENT -> {
                    host.consumeFetched(wayId)
                    if (navController.showsStoredOverview(wayId)) {
                        host.gatherShownAgain(wayId)
                    } else {
                        navController.openFetchedWayOverview(wayId)
                    }
                }
            }
            link != null -> when (routeLinkLanding(backStack, parkedBehindSummary)) {
                FetchedWayLanding.WAIT -> Unit
                FetchedWayLanding.PARK -> parkedBehindSummary = true
                FetchedWayLanding.DROP -> host.consumeRouteLink(link)
                FetchedWayLanding.PRESENT -> {
                    host.consumeRouteLink(link)
                    navController.openLinkedRoutePage(link)
                }
            }
            else -> parkedBehindSummary = false
        }
    }
}
```

(d) After `openHonorOverviewFromSummary` (line 1133):

```kotlin

/**
 * iOS `walkAgain` (`MainCoordinatorView.swift:420-424@3b78728`): a Way
 * built opens its overview in the summary's place, and that overview
 * takes the place of a link parked behind the summary (the landing's
 * `DROP`); a walk too short to build one only closes the summary, and the
 * park opens once the summary has gone. Changed at `3b78728`: a nil build
 * used to drop the park.
 */
internal fun summaryWalkAgain(result: WalkAgainResult, openOverview: (sourceWalkId: Long) -> Unit, closeSummary: () -> Unit) {
    if (result.built) openOverview(result.sourceWalkId) else closeSummary()
}
```

(e) Replace the summary's `onWalkAgain` argument and the comment above it (lines 603-621, from `// iOS \`walkAgain\`: the summary closes, …` through the `} else {\n null\n },` that ends it) with:

```kotlin
                    onWalkAgain = if (hostOffersWalkAgain) {
                        { result ->
                            summaryWalkAgain(
                                result,
                                openOverview = { sourceWalkId ->
                                    sheetScope.launch { sheetState.hide() }.invokeOnCompletion {
                                        navController.openHonorOverviewFromSummary(sourceWalkId)
                                    }
                                },
                                closeSummary = dismissAndDone,
                            )
                        }
                    } else {
                        null
                    },
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHostTest' --tests 'org.walktalkmeditate.pilgrim.ui.honor.*'`
Expected: PASS. `grep -rn dropParkedWay app/src` prints nothing.

- [ ] **Step 6: Full suite and lint, then commit**

```bash
./gradlew :app:testDebugUnitTest lintDebug
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorWaysViewModel.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHostTest.kt
git commit -m "feat(honor): the landing opens a stage link's route page, or parks it behind a summary (Phase 22 U7)" \
  -m "The route link rides the share link's landing: it never waits on the Ways sheet, parks behind a summary from any host, and a walk drops it. Walk this again with no Way built now keeps whatever is parked, as iOS does since 3b78728." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U8: The router takes destinations

**Files:**
- Modify: `P/honor/HonorLinkRouter.kt` — `HonorLinkScreen` (24-39), class KDoc (50-75), `open` (U2's interim at 127-133), `routeReferrer` (135-147), `route` (149-160), `drainIfReady` (190-197), `dispatch` (199-215), `keepsItsPlace` (239), `HeldLink` (241)
- Modify: `P/ui/navigation/PilgrimNavHost.kt:894-904` (`honorLinkScreen`)
- Test: `T/honor/HonorLinkRouterTest.kt`, `T/ui/navigation/PilgrimNavHostTest.kt`

**Interfaces:**
- Consumes: U2's `HonorLinkDestination` and `honorLinkOf`; U4's `HonorLinkToast.NeedsNewerPilgrim`; U5's `HonorImportCoordinator.openStage(routeId, index)`; U6's `Routes.HONOR_ROUTE_LINK_PATTERN` (tracked since U6).
- Produces:
  - `HonorLinkScreen(inSetup, atPath, walkScreenUp, waysSheetUp, summaryUp, overviewUp, routeSheetUp: Boolean)`
  - `fun HonorLinkRouter.route(link: HonorLinkDestination, released: () -> Unit = {})` (was `route(shareId: String, …)`); `routeReferrer(shareId: String, released: () -> Unit)` unchanged

- [ ] **Step 1: Give the router test a stage-capable coordinator and a holdable walk probe**

In `T/honor/HonorLinkRouterTest.kt`, add imports `kotlinx.coroutines.CancellableContinuation`, `kotlinx.coroutines.suspendCancellableCoroutine`, `org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog`, `org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry` and `org.walktalkmeditate.pilgrim.ui.navigation.routeLinkLanding`; add fields after `private var probes = 0` (the probe gate is cancellable, as the Room read it stands for is, so a cancelled routing job never resumes past it):

```kotlin
    private var holdProbe = false
    private var probeGate: CancellableContinuation<Boolean>? = null
    private var cachedCatalog: PilgrimageCatalog? = null
    private val catalogLoads = mutableListOf<Boolean>()
    private var heldLoad: Continuation<PilgrimageCatalog>? = null
```

and make `router()` (lines 59-78):

```kotlin
    private fun TestScope.router(honorEnabled: Boolean = true): HonorLinkRouter {
        imports = HonorImportCoordinator(
            importShare = { id ->
                asked += id
                suspendCoroutine { held[id] = it }
            },
            honorEnabled = honorEnabled,
            scope = backgroundScope,
            media = { error("a link never gathers") },
            cachedCatalog = { cachedCatalog },
            loadCatalog = { force ->
                catalogLoads += force
                suspendCoroutine { heldLoad = it }
            },
        )
        return HonorLinkRouter(
            honorEnabled = honorEnabled,
            imports = imports,
            trackerWalking = {
                probes++
                if (holdProbe) suspendCancellableCoroutine { probeGate = it } else trackerWalking
            },
            scope = backgroundScope,
        )
    }
```

Change the three `route` calls: line 563 `links.route(SECOND)` → `links.route(HonorLinkDestination.Share(SECOND))`; lines 578-579 → `links.route(HonorLinkDestination.Share(FIRST)) { released += FIRST }` and `links.route(HonorLinkDestination.Share(SECOND)) { released += SECOND }`.

- [ ] **Step 2: Rewrite the silent-path test, extend the flag test, add the stage tests**

Replace the test at lines 524-535 (`an honor path that doesn't parse opens the app and does nothing`) with:

```kotlin
    // Stage-links spec Q7: an honor path this build can't read is answered, never met with silence; the bare root still is.
    @Test
    fun `an honor path this build can't read says a newer Pilgrim is needed, and the bare root still does nothing`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HOME)

        links.open(honorPath(""), restored = false)
        assertNull("the site itself opened is no link", links.toast.value)

        links.open(honorPath("abc"), restored = false)

        assertEquals(HonorLinkToast.NeedsNewerPilgrim, links.toast.value)
        assertTrue("no import starts", asked.isEmpty())
        assertTrue("no catalog is read", catalogLoads.isEmpty())
        assertFalse("the walker stays on the Journal", links.pathSwitch.value)
    }
```

In `with the release flag off a link does nothing` (lines 557-570), add after `links.routeReferrer(SECOND) {}`:

```kotlin
        links.open(honorPath("camino-frances/14"), restored = false)
        links.open(honorPath("abc"), restored = false)
        assertTrue(catalogLoads.isEmpty())
```

Add, before `private fun link(id: String)`:

```kotlin
    // Stage links (stage-links spec Q7, Q8): the share's refusal and carve-outs, and no "reaching for the walk…".

    @Test
    fun `a stage link from the Journal switches to Path and reaches for the route, never for the walk`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HOME)

        links.open(honorPath("camino-frances/14"), restored = false)

        assertTrue(links.pathSwitch.value)
        assertEquals(HonorLinkToast.ReachingForRoute, links.toast.value)
        assertEquals(listOf(false), catalogLoads)
        assertTrue("no share import starts", asked.isEmpty())
        checkNotNull(heldLoad).resume(catalog())
        assertNull("the toast goes as the page is offered", links.toast.value)
        assertEquals(HonorRouteLink(FRANCES, RELEASE, focus = 13), imports.routeLink.value)
    }

    @Test
    fun `a stage link with the link page up keeps it in place to be swapped, as iOS swaps its route sheet`() = runTest(dispatcher) {
        cachedCatalog = catalog()
        val links = router()
        links.at(Routes.PATH, Routes.HONOR_ROUTE_LINK_PATTERN)

        links.open(honorPath("camino-frances/20"), restored = false)

        assertFalse(links.pathSwitch.value)
        assertEquals(HonorRouteLink(FRANCES, RELEASE, focus = 19), imports.routeLink.value)
    }

    @Test
    fun `a stage link over a summary keeps the summary's place`() = runTest(dispatcher) {
        cachedCatalog = catalog()
        val links = router()
        links.at(Routes.PATH, Routes.HOME, Routes.WALK_SUMMARY_PATTERN)

        links.open(honorPath("camino-frances/14"), restored = false)

        assertFalse(links.pathSwitch.value)
        assertEquals(HonorRouteLink(FRANCES, RELEASE, focus = 13), imports.routeLink.value)
    }

    // iOS `testStageLinkWhileWalkingSetsTheToast`.
    @Test
    fun `a stage link during a walk is refused, with no switch and nothing resolved`() = runTest(dispatcher) {
        cachedCatalog = catalog()
        val links = router()
        links.at(Routes.PATH, Routes.ACTIVE_WALK)

        links.open(honorPath("camino-frances/14"), restored = false)

        assertEquals(HonorLinkToast.FinishWalkFirst, links.toast.value)
        assertFalse(links.pathSwitch.value)
        assertNull(imports.routeLink.value)
    }

    // iOS `testABeginInFlightWinsOverAStageLink`; Q9: the walk screen opening is Android's Begin in flight.
    @Test
    fun `a Begin landing while the catalog loads wins over the stage link`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HOME, Routes.HONOR_OVERVIEW_PATTERN)
        links.open(honorPath("camino-frances/14"), restored = false)

        links.at(Routes.PATH, Routes.HOME, Routes.ACTIVE_WALK)
        checkNotNull(heldLoad).resume(catalog())

        assertNull("nothing interrupts the walk", imports.routeLink.value)
        assertEquals(FetchedWayLanding.DROP, routeLinkLanding(listOf(Routes.PATH, Routes.HOME, Routes.ACTIVE_WALK)))
    }

    @Test
    fun `a stage link during onboarding waits, then resolves at Path`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.WELCOME)

        links.open(honorPath("camino-frances/14"), restored = false)
        assertTrue(catalogLoads.isEmpty())
        assertNull(links.toast.value)
        links.at(Routes.PATH)

        assertEquals(listOf(false), catalogLoads)
    }

    // iOS `testAnUnrecognisedLinkSaysANewerPilgrimIsNeeded`; `open(.unrecognised)` checks no walk (`MainCoordinatorView.swift:227-233@3b78728`).
    @Test
    fun `an unrecognised link mid-walk still says a newer Pilgrim is needed, and asks nothing of the tracker`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.ACTIVE_WALK)

        links.open(honorPath("abc"), restored = false)

        assertEquals(HonorLinkToast.NeedsNewerPilgrim, links.toast.value)
        assertFalse(links.pathSwitch.value)
        assertEquals(0, probes)
    }

    @Test
    fun `an unrecognised link leaves a share import in flight running`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)
        links.open(link(FIRST), restored = false)

        links.open(honorPath("abc"), restored = false)
        assertEquals(HonorLinkToast.NeedsNewerPilgrim, links.toast.value)
        assertEquals(HonorImportState.Fetching, imports.state.value)
        land(FIRST)

        assertEquals("share:$FIRST", imports.fetched.value)
    }

    /** Ruling on Q8's edge: an unrecognised link never cancels a share's walk probe, as iOS's never touches the import. */
    @Test
    fun `an unrecognised link while a share's walk probe is out leaves the share to open`() = runTest(dispatcher) {
        holdProbe = true
        val links = router()
        links.at(Routes.PATH)
        links.open(link(FIRST), restored = false)

        links.open(honorPath("abc"), restored = false)
        assertEquals(HonorLinkToast.NeedsNewerPilgrim, links.toast.value)
        checkNotNull(probeGate).resume(false)

        assertEquals(listOf(FIRST), asked)
    }

    private fun honorPath(path: String) = Intent(Intent.ACTION_VIEW, Uri.parse("https://honor.pilgrimapp.org/$path"))

    private fun catalog() = PilgrimageCatalog(release = RELEASE, routes = listOf(FRANCES))
```

Add to the companion object:

```kotlin
        const val RELEASE = "v1.12.0"
        val FRANCES = PilgrimageCatalogEntry(
            id = "camino-frances",
            name = "Camino Francés",
            names = emptyMap(),
            country = "ES",
            region = "Europe",
            distanceKm = 780.0,
            tradition = "christian",
            stageCount = 33,
            bytes = 1_000,
            placesPerStage = 0.0,
            sparse = false,
        )
```

In `T/ui/navigation/PilgrimNavHostTest.kt`, add after `the link routing reads setup, the walk screen, the sheet, a summary, and an overview off the back stack`:

```kotlin
    @Test
    fun `the link routing reads a link page off the back stack, and the catalog's route page is not one`() {
        assertTrue(honorLinkScreen(listOf(Routes.PATH, Routes.HONOR_ROUTE_LINK_PATTERN)).routeSheetUp)
        assertFalse(
            honorLinkScreen(listOf(Routes.PATH, Routes.HONOR_WAYS, Routes.HONOR_PILGRIMAGES, Routes.HONOR_PILGRIMAGE_PATTERN)).routeSheetUp,
        )
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.honor.HonorLinkRouterTest' --tests 'org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHostTest'`
Expected: FAIL at compile, `Unresolved reference 'routeSheetUp'`, and a type mismatch at `links.route(HonorLinkDestination.Share(...))`.

- [ ] **Step 4: Route destinations**

In `P/honor/HonorLinkRouter.kt`:

(a) Add to `HonorLinkScreen`, after `val overviewUp: Boolean,`:

```kotlin
    /** A route page a stage link opened: a second link swaps it in place, as iOS swaps its route sheet. */
    val routeSheetUp: Boolean,
```

(b) Replace the class KDoc (lines 50-75) with:

```kotlin
/**
 * Honor links in the UI process: iOS's `PilgrimApp.route` stash, the
 * `MainTabView` drain and handler, and `MainCoordinator.open`'s refusal
 * and toast (`PilgrimApp.swift:35,47-64`, `MainTabView.swift:93-107,172-175`,
 * `MainCoordinatorView.swift:227-242,274-275@3b78728`; shared-walk spec
 * S2 §2–§7). The fetch and the stage resolution are
 * [HonorImportCoordinator]'s, and where their results land is the nav host's.
 *
 * - A link waits in memory, the last one winning, until a nav host
 *   first stands past setup (correction 4). This singleton outlives an
 *   Activity's recreation and dies with the process, as iOS's static
 *   does. Nothing shows while it waits.
 * - The install referrer's id routes like a held tap, unless the walker
 *   tapped a link in this process: the tap wins.
 * - An unrecognised link only says "this link needs a newer Pilgrim",
 *   mid-walk too; it switches no tab and cancels nothing.
 * - With the walk screen up, or `:tracker` walking with no walk screen
 *   yet (a cold start), a share or stage link is answered "finish this
 *   walk first" and dropped (correction 3).
 * - Otherwise the tab switches to Path, unless a summary, the Ways sheet,
 *   an overview, or a link's route page is up (they keep their place: the
 *   summary parks what comes, the sheet takes a Way, the overview and the
 *   page are replaced by it). A share says "reaching for the walk…" unless
 *   the Ways sheet is up and starts its import; a stage starts its
 *   resolution, which says its own lines (correction 2).
 * - The walk screen opening cancels either in flight, silently.
 *
 * Main thread only, as iOS's `@MainActor` coordinator is.
 */
```

(c) Replace `open`, `routeReferrer` and `route` (U2's interim `open` and lines 135-160) with:

```kotlin
    /** A launch's intent, or a new one; [restored] for an Activity rebuilt from saved state. */
    fun open(intent: Intent?, restored: Boolean) {
        val link = honorLinkOf(intent, restored) ?: return
        if (!honorEnabled) return
        linkTapped = true
        route(link)
    }

    /**
     * The install referrer's id, routed as a tap held through setup would
     * be. A link the walker tapped in this process wins: the referrer is
     * then [released] at once, unrouted (R19's "once" either way).
     */
    fun routeReferrer(shareId: String, released: () -> Unit) {
        if (!honorEnabled) return
        if (linkTapped) {
            released()
            return
        }
        route(HonorLinkDestination.Share(shareId), released)
    }

    /**
     * iOS `route`: [link] waits for setup if it has to, then routes.
     * [released] runs once it stops waiting, routed or replaced by a newer
     * link. An unrecognised link leaves a share's walk probe running, as
     * iOS's `open(.unrecognised)` leaves the import.
     */
    fun route(link: HonorLinkDestination, released: () -> Unit = {}) {
        if (!honorEnabled) return
        if (link != HonorLinkDestination.Unrecognised) routing?.cancel()
        held?.released?.invoke()
        held = HeldLink(link, released)
        drainIfReady()
    }
```

(d) Replace `drainIfReady` and `dispatch` (lines 190-215) with:

```kotlin
    private fun drainIfReady() {
        val now = screen ?: return
        if (now.inSetup) return
        val link = held ?: return
        held = null
        link.released()
        dispatch(link.destination)
    }

    private fun dispatch(link: HonorLinkDestination) {
        if (link == HonorLinkDestination.Unrecognised) {
            showToast(HonorLinkToast.NeedsNewerPilgrim)
            return
        }
        routing = scope.launch {
            val walking = screen?.walkScreenUp == true || trackerWalking()
            val now = screen
            if (now == null) {
                held = HeldLink(link) {}
                return@launch
            }
            if (walking || now.walkScreenUp) {
                showToast(HonorLinkToast.FinishWalkFirst)
                return@launch
            }
            if (!now.atPath && !now.keepsItsPlace()) _pathSwitch.value = true
            when (link) {
                is HonorLinkDestination.Share -> {
                    if (!now.waysSheetUp) showToast(HonorLinkToast.Reaching)
                    imports.openWay(link.id)
                }
                is HonorLinkDestination.Stage -> imports.openStage(link.routeId, link.index)
                HonorLinkDestination.Unrecognised -> Unit
            }
        }
    }
```

(e) Replace `keepsItsPlace` and `HeldLink` (lines 239-241) with:

```kotlin
    private fun HonorLinkScreen.keepsItsPlace(): Boolean = summaryUp || waysSheetUp || overviewUp || routeSheetUp

    private class HeldLink(val destination: HonorLinkDestination, val released: () -> Unit)
```

(f) In `P/ui/navigation/PilgrimNavHost.kt`, `honorLinkScreen` (lines 894-904) gains, after `overviewUp = …,`:

```kotlin
        routeSheetUp = Routes.HONOR_ROUTE_LINK_PATTERN in backStack,
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.honor.*' --tests 'org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHostTest' --tests 'org.walktalkmeditate.pilgrim.MainActivityIntentsTest'`
Expected: PASS, the share-link router tests and `InstallReferrerHandoffTest` unchanged.

- [ ] **Step 6: Full suite, then commit**

```bash
./gradlew :app:testDebugUnitTest
git add app/src/main/java/org/walktalkmeditate/pilgrim/honor/HonorLinkRouter.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/honor/HonorLinkRouterTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHostTest.kt
git commit -m "feat(honor): tapped stage links route, and an unreadable honor path says so (Phase 22 U8)" \
  -m "A held link is now a destination. A stage link is refused mid-walk and keeps the share link's carve-outs, the link page among them; an unrecognised one only shows its toast, mid-walk too, and cancels nothing. The bare root still does nothing." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U9: The link page brings its stage into view, once

**Files:**
- Modify: `P/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt` — `routeId` (309, add `focus` beside it), after `opened` (343-346, add `scrollTarget`), `init` (350-362), `retryStages` (421-423), a new `applyFocus` and `consumeScrollTarget`, companion (U6's, add `KEY_FOCUS_APPLIED`), class KDoc (244-270)
- Test: `T/ui/honor/pilgrimage/PilgrimageRouteFocusTest.kt` (new)

**Interfaces:**
- Consumes: U6's `PilgrimageRouteViewModel.ARG_FOCUS`, `NO_FOCUS`; the existing `PilgrimageRouteAlert.DOWNLOAD_FIRST`, `confirmDownloadFirst()` (→ `beginInstall`, so "Replace?" still asks), `retryStages()`.
- Produces:
  - `val PilgrimageRouteViewModel.scrollTarget: StateFlow<Int?>` (a stage index, one-shot), `fun consumeScrollTarget()`
  - `internal const val PilgrimageRouteViewModel.KEY_FOCUS_APPLIED = "focusApplied"` (a `SavedStateHandle` key)

- [ ] **Step 1: Write the failing focus tests**

Create `T/ui/honor/pilgrimage/PilgrimageRouteFocusTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.NORTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.ROUTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.waitUntil

/**
 * A link page's focus (stage-links spec Q4; iOS `applyFocus`,
 * `PilgrimageRouteView.swift:354-359@3b78728`) against the real manager and
 * catalog service: once, only once stages exist, the scroll and the prompt
 * independent, the prompt through the button's gate, and the latch kept in
 * the saved state. The world's route has two stages, 0 and 1. Waits are
 * wall-clock, as the page reads off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageRouteFocusTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var world: PilgrimageScreensWorld

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        world = PilgrimageScreensWorld(folder.root)
    }

    @After
    fun tearDown() {
        world.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a focus on a route not on the phone scrolls to its stage and asks to download first`() {
        world.holdCatalog()

        val vm = world.routeViewModel(saved = linkState(focus = 1))

        waitUntil("the focus lands") { vm.scrollTarget.value == 1 }
        assertEquals(PilgrimageRouteAlert.DOWNLOAD_FIRST, vm.alert.value)
    }

    /** Q5: the resolver's catalog is the service's, so the page is ready on its first frame. */
    @Test
    fun `a link page opened with the catalog in hand is ready at once`() {
        world.holdCatalog()

        val vm = world.routeViewModel(saved = linkState(focus = 0))

        assertTrue(vm.state.value is PilgrimageRouteUiState.Ready)
    }

    // iOS's guard waits for the stage list (`PilgrimageRouteView.swift:350-355@3b78728`).
    @Test
    fun `a failed preview keeps the focus, and try again applies it once`() {
        world.harness.stubs.unstub(world.harness.path("route.json"))
        world.holdCatalog()
        val saved = linkState(focus = 1)
        val vm = world.routeViewModel(saved = saved)
        waitUntil("the preview fails") {
            val page = (vm.state.value as? PilgrimageRouteUiState.Ready)?.page
            page != null && page.stagesFailure != null && !page.isLoadingStages && !page.isHeld
        }
        assertNull("no prompt over a failed preview", vm.alert.value)
        assertNull(vm.scrollTarget.value)
        assertNull(saved.get<Boolean>(PilgrimageRouteViewModel.KEY_FOCUS_APPLIED))

        world.harness.stub("route.json", PilgrimagePackageHarness.fixture("route.json"))
        vm.retryStages()

        waitUntil("try again applies the focus") { vm.scrollTarget.value == 1 }
        assertEquals(PilgrimageRouteAlert.DOWNLOAD_FIRST, vm.alert.value)
        vm.dismissAlert()
        vm.consumeScrollTarget()
        vm.retryStages()
        waitUntil("the second try settles") { (vm.state.value as? PilgrimageRouteUiState.Ready)?.page?.isLoadingStages == false }
        assertNull("once", vm.alert.value)
        assertNull(vm.scrollTarget.value)
    }

    // Review Focus 4's neighbour: the installed route's Update button is the answer, not a prompt.
    @Test
    fun `an installed route asks nothing, and scrolls even to a stage whose Way is missing`() {
        world.install()
        File(world.harness.waysDir, WayStore.stageWayId(ROUTE_ID, 1)).deleteRecursively()
        world.holdCatalog()

        val vm = world.routeViewModel(saved = linkState(focus = 1))

        waitUntil("the focus lands") { vm.scrollTarget.value == 1 }
        assertNull(vm.alert.value)
    }

    // Review Focus 4; the spec's "Lens disagreements": a focus the list doesn't hold.
    @Test
    fun `a stage the installed release doesn't list gets no scroll and no prompt`() {
        world.install()
        world.holdCatalog()
        val saved = linkState(focus = 5)

        val vm = world.routeViewModel(saved = saved)

        applied(saved)
        assertNull(vm.scrollTarget.value)
        assertNull(vm.alert.value)
    }

    @Test
    fun `a stage the list doesn't hold still asks to download a route not on the phone`() {
        world.holdCatalog()
        val saved = linkState(focus = 5)

        val vm = world.routeViewModel(saved = saved)

        applied(saved)
        assertNull(vm.scrollTarget.value)
        assertEquals(PilgrimageRouteAlert.DOWNLOAD_FIRST, vm.alert.value)
    }

    @Test
    fun `no focus, the catalog's page or a link past the route's end, scrolls nowhere and asks nothing`() {
        world.holdCatalog()
        val catalogPage = world.routeViewModel()
        val unfocused = world.routeViewModel(saved = linkState(focus = null))

        listOf(catalogPage, unfocused).forEach { vm ->
            settled(vm)
            assertNull(vm.alert.value)
            assertNull(vm.scrollTarget.value)
        }
    }

    // `PilgrimageRouteView.swift:185-192@3b78728`: "Download" is `beginInstall()`, so "Replace?" still asks.
    @Test
    fun `the prompt goes through the button's gate, so another route on the phone still asks Replace`() {
        world.stubIndex(PilgrimageScreensWorld.index(routes = listOf(ROUTE_ID to "Francés", NORTE_ID to "Norte")))
        world.harness.stubNorte()
        world.install(NORTE_ID)
        world.holdCatalog()
        val vm = world.routeViewModel(saved = linkState(focus = 0))
        waitUntil("the prompt") { vm.alert.value == PilgrimageRouteAlert.DOWNLOAD_FIRST }

        vm.confirmDownloadFirst()

        assertEquals(PilgrimageRouteAlert.REPLACE, vm.alert.value)
    }

    // Review Focus 2.
    @Test
    fun `a rotation or a restore after the prompt was answered never asks again`() {
        world.holdCatalog()
        val saved = linkState(focus = 1)
        val first = world.routeViewModel(saved = saved)
        waitUntil("the prompt") { first.alert.value == PilgrimageRouteAlert.DOWNLOAD_FIRST }
        first.dismissAlert()

        val restored = world.routeViewModel(saved = saved)
        settled(restored)

        assertNull(restored.alert.value)
        assertNull(restored.scrollTarget.value)
    }

    /** Q5's risk: a focus for a page that ends Gone raises nothing. */
    @Test
    fun `a focus for a page the catalog doesn't list raises nothing`() {
        world.holdCatalog()

        val vm = world.routeViewModel(saved = linkState(routeId = NORTE_ID, focus = 0))

        assertEquals(PilgrimageRouteUiState.Gone, vm.state.value)
        assertNull(vm.alert.value)
    }

    private fun linkState(routeId: String = ROUTE_ID, focus: Int?) = SavedStateHandle(
        mapOf(
            PilgrimageRouteViewModel.ARG_ROUTE_ID to routeId,
            PilgrimageRouteViewModel.ARG_FOCUS to (focus ?: PilgrimageRouteViewModel.NO_FOCUS),
        ),
    )

    /** The latch is written last, so a test that sees it sees the scroll and the prompt decided. */
    private fun applied(saved: SavedStateHandle) =
        waitUntil("the focus is applied") { saved.get<Boolean>(PilgrimageRouteViewModel.KEY_FOCUS_APPLIED) == true }

    private fun settled(vm: PilgrimageRouteViewModel) = waitUntil("the page settles") {
        val page = (vm.state.value as? PilgrimageRouteUiState.Ready)?.page
        page != null && page.route != null && !page.isLoadingStages && !page.isHeld
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.PilgrimageRouteFocusTest'`
Expected: FAIL at compile, `Unresolved reference 'scrollTarget'`.

- [ ] **Step 3: Apply the focus**

In `P/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt`:

(a) After `private val routeId: String? = savedState[ARG_ROUTE_ID]` (line 309):

```kotlin

    /** The stage a link brings into view, 0-based: null on the catalog's page and for a link past the route's end. */
    private val focus: Int? = savedState.get<Int>(ARG_FOCUS)?.takeIf { it != NO_FOCUS }
```

(b) After `val opened: SharedFlow<String> = _opened.asSharedFlow()` (line 346):

```kotlin

    private val _scrollTarget = MutableStateFlow<Int?>(null)

    /**
     * The stage index the link's focus scrolls to, until the screen has
     * scrolled ([consumeScrollTarget]): iOS's `scrollTarget`
     * (`PilgrimageRouteView.swift:105,117-125@3b78728`).
     */
    val scrollTarget: StateFlow<Int?> = _scrollTarget.asStateFlow()
```

(c) In `init` (lines 350-362), after `loadStagesIfNeeded(force = false)` add `applyFocus()`, so the block ends:

```kotlin
            loadStagesIfNeeded(force = false)
            applyFocus()
        }
    }
```

(d) Replace `retryStages` (lines 421-423) with:

```kotlin
    fun retryStages() {
        viewModelScope.launch {
            loadStagesIfNeeded(force = true)
            applyFocus()
        }
    }

    fun consumeScrollTarget() {
        _scrollTarget.value = null
    }
```

(e) After `loadStagesIfNeeded` (ends line 652):

```kotlin

    /**
     * iOS `applyFocus()` (`PilgrimageRouteView.swift:354-359@3b78728`): once
     * per page, and only once it has stages, so a failed preview keeps the
     * focus for "try again". The prompt and the scroll are independent:
     * "Download this route first?" only for a route not on the phone (an
     * installed route whose stage is missing asks nothing, its Update button
     * being the answer), through the button's own gate; the scroll only to a
     * stage the list holds. The latch is kept in the saved state, so neither
     * a rotation nor a restore asks again.
     */
    private fun applyFocus() {
        val focus = focus ?: return
        val page = page ?: return
        if (savedState.get<Boolean>(KEY_FOCUS_APPLIED) == true || page.stages.isEmpty()) return
        if (!page.isInstalled) _alert.value = PilgrimageRouteAlert.DOWNLOAD_FIRST
        if (page.stages.any { it.index == focus }) _scrollTarget.value = focus
        savedState[KEY_FOCUS_APPLIED] = true
    }
```

(f) In the companion, after `internal const val KEY_REDRAW_NOTICE = "showRedrawNotice"`:

```kotlin
        internal const val KEY_FOCUS_APPLIED = "focusApplied"
```

Update the class KDoc (lines 244-270) by adding, after its first paragraph: `A page a stage link opened also carries a focus ([ARG_FOCUS]), applied once its stages are in (stage-links spec Q4).`

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.*'`
Expected: PASS, `PilgrimageRouteViewModelTest` unchanged (its pages have no focus).

- [ ] **Step 5: Full suite and lint, then commit**

```bash
./gradlew :app:testDebugUnitTest lintDebug
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageRouteFocusTest.kt
git commit -m "feat(honor): a link page brings its stage into view and asks to download, once (Phase 22 U9)" \
  -m "iOS's applyFocus: only once stages exist, so a failed preview keeps the focus for try again; the prompt only for a route not on the phone, through the Replace gate; the latch in the saved state, so a rotation never asks again." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U10: The focused stage is centred

**Files:**
- Modify: `P/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt` — `PilgrimageRouteModel` (120-242, add `stageListPosition`)
- Modify: `P/ui/honor/pilgrimage/PilgrimageRouteScreen.kt` — `PilgrimageRouteContent` (U6's signature), `RouteSections` (140-184), `PilgrimageRouteSheetBody` (U6), a new `animateScrollToCentre`
- Test: `T/ui/honor/pilgrimage/PilgrimageRouteScrollTest.kt` (new)

**Interfaces:**
- Consumes: U9's `scrollTarget`, `consumeScrollTarget()`.
- Produces:
  - `fun PilgrimageRouteModel.stageListPosition(page: PilgrimageRoutePage, stageIndex: Int): Int?`
  - `internal suspend fun LazyListState.animateScrollToCentre(position: Int)`
  - `PilgrimageRouteContent(…, modifier, leading, scrollTarget: Int? = null, onScrollConsumed: () -> Unit = {}, listState: LazyListState = rememberLazyListState())`

- [ ] **Step 1: Write the failing scroll tests**

Create `T/ui/honor/pilgrimage/PilgrimageRouteScrollTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRoute
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRouteStage
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * The focused stage's scroll (stage-links spec Q11; iOS
 * `proxy.scrollTo(target, anchor: .center)`, `PilgrimageRouteView.swift:117-125@3b78728`):
 * the row's list position below the header items, the centring, and the
 * route page scrolling once and handing the target back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp")
class PilgrimageRouteScrollTest {

    @get:Rule val composeRule = createComposeRule()

    private val entry = PilgrimageCatalogEntry(
        id = ROUTE_ID,
        name = "Camino de Santiago (Francés)",
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 764.0,
        tradition = "christian",
        stageCount = STAGES,
        bytes = 1_000_000,
        placesPerStage = 0.0,
        sparse = false,
    )

    private val route = PilgrimageRoute(
        id = ROUTE_ID,
        name = entry.name,
        names = emptyMap(),
        country = "ES",
        region = "Europe",
        distanceKm = 764.0,
        stageCount = STAGES,
        tradition = "christian",
        summary = null,
        stages = (0 until STAGES).map { PilgrimageRouteStage(it, "stage $it", 20.0, 300.0, WayStageHours(5.0, 6.0), "moderate") },
    )

    private fun page(installed: Boolean) = PilgrimageRoutePage(
        entry = entry,
        release = RELEASE,
        installed = if (installed) PilgrimagePackageManager.Installed(ROUTE_ID, RELEASE, route) else null,
        route = route,
    )

    @Test
    fun `a stage's row sits below the header, the next row on the installed route, and the stages header`() {
        assertEquals(2, PilgrimageRouteModel.stageListPosition(page(installed = false), stageIndex = 0))
        assertEquals(27, PilgrimageRouteModel.stageListPosition(page(installed = false), stageIndex = 25))
        assertEquals(3, PilgrimageRouteModel.stageListPosition(page(installed = true), stageIndex = 0))
        assertNull(PilgrimageRouteModel.stageListPosition(page(installed = false), stageIndex = STAGES))
    }

    @Test
    fun `the centring puts the row's centre on the viewport's, and stops where the list ends`() {
        val state = LazyListState()
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            scope = rememberCoroutineScope()
            LazyColumn(state = state, modifier = Modifier.height(300.dp)) {
                items(40) { Box(Modifier.fillMaxWidth().height(50.dp)) }
            }
        }

        composeRule.runOnIdle { scope.launch { state.animateScrollToCentre(20) } }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val info = state.layoutInfo
            val row = info.visibleItemsInfo.first { it.index == 20 }
            val off = row.offset + row.size / 2 - (info.viewportStartOffset + info.viewportEndOffset) / 2
            assertTrue("off centre by $off px", abs(off) <= 1)
        }

        composeRule.runOnIdle { scope.launch { state.animateScrollToCentre(39) } }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse("the last row clamps at the end, as `.center` does", state.canScrollForward) }
    }

    @Test
    fun `a focused stage is scrolled into view once, and the target is handed back`() {
        var consumed = 0
        composeRule.setContent {
            PilgrimTheme {
                PilgrimageRouteContent(
                    state = PilgrimageRouteUiState.Ready(page(installed = false)),
                    phase = PilgrimagePackageManager.Phase.Idle,
                    tilesPhase = PilgrimageTilesManager.Phase.Idle,
                    mapsRow = null,
                    units = UnitSystem.Metric,
                    alert = null,
                    actions = PilgrimageRouteActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
                    scrollTarget = 25,
                    onScrollConsumed = { consumed++ },
                )
            }
        }

        composeRule.waitUntil(WAIT_MILLIS) { consumed == 1 }

        composeRule.onNodeWithText("26. stage 25").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, consumed) }
    }

    private companion object {
        const val ROUTE_ID = "camino-frances"
        const val RELEASE = "v1.12.0"
        const val STAGES = 30
        const val WAIT_MILLIS = 10_000L
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.PilgrimageRouteScrollTest'`
Expected: FAIL at compile, `Unresolved reference 'stageListPosition'`.

- [ ] **Step 3: The row's position**

In `P/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt`, inside `object PilgrimageRouteModel`, after `nextIndex` (line 153):

```kotlin

    /**
     * Where [stageIndex]'s row sits in the route page's list, which iOS
     * scrolls to by `.id(stage.index)` (`PilgrimageRouteView.swift:283-285@3b78728`):
     * after the header, the next row on the installed route, and the
     * stages header (`RouteSections`), at the stage's place in the list.
     * Null for a stage the list doesn't hold.
     */
    fun stageListPosition(page: PilgrimageRoutePage, stageIndex: Int): Int? {
        val position = page.stages.indexOfFirst { it.index == stageIndex }
        if (position < 0) return null
        return position + if (page.isInstalled) ROWS_ABOVE_INSTALLED_STAGES else ROWS_ABOVE_STAGES
    }

    private const val ROWS_ABOVE_STAGES = 2
    private const val ROWS_ABOVE_INSTALLED_STAGES = 3
```

- [ ] **Step 4: The centring scroll, wired to the page**

In `P/ui/honor/pilgrimage/PilgrimageRouteScreen.kt`, add imports `androidx.compose.foundation.gestures.animateScrollBy`, `androidx.compose.foundation.lazy.LazyListState`, `androidx.compose.foundation.lazy.rememberLazyListState`, then:

(a) In `PilgrimageRouteContent`, replace everything from `modifier: Modifier = Modifier,` through the `val busy = …` line (U6's version) with:

```kotlin
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = { RouteBackButton(actions.onBack) },
    scrollTarget: Int? = null,
    onScrollConsumed: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val page = (state as? PilgrimageRouteUiState.Ready)?.page
    val busy = page != null && PilgrimageRouteModel.isBusy(phase, tilesPhase, page.isHeld)
    val shownPage by rememberUpdatedState(page)
    val scrolled by rememberUpdatedState(onScrollConsumed)
    LaunchedEffect(scrollTarget) {
        val target = scrollTarget ?: return@LaunchedEffect
        shownPage?.let { PilgrimageRouteModel.stageListPosition(it, target) }?.let { listState.animateScrollToCentre(it) }
        scrolled()
    }
```

and its call `RouteSections(page, phase, tilesPhase, mapsRow, units, busy, actions)` becomes `RouteSections(page, phase, tilesPhase, mapsRow, units, busy, actions, listState)`.

(b) `RouteSections` gains a last parameter `listState: LazyListState`, and its list becomes `LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {`.

(c) After `RouteCloseButton`:

```kotlin

/**
 * SwiftUI's `scrollTo(_, anchor: .center)` (`PilgrimageRouteView.swift:117-125@3b78728`):
 * the row brought into view, then its centre moved onto the viewport's,
 * both with Compose's default spring, as iOS's `withAnimation` names no
 * duration. A row near the end stops where the list does, as `.center`
 * clamps.
 */
internal suspend fun LazyListState.animateScrollToCentre(position: Int) {
    animateScrollToItem(position)
    val row = layoutInfo.visibleItemsInfo.firstOrNull { it.index == position } ?: return
    val viewportCentre = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    animateScrollBy((row.offset + row.size / 2 - viewportCentre).toFloat())
}
```

(d) In `PilgrimageRouteSheetBody`, add `val scrollTarget by viewModel.scrollTarget.collectAsStateWithLifecycle()` beside the other states, and pass to `PilgrimageRouteContent`, after `leading = …`:

```kotlin
        scrollTarget = scrollTarget,
        onScrollConsumed = viewModel::consumeScrollTarget,
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.*'`
Expected: PASS.

- [ ] **Step 6: Full suite and lint, then commit**

```bash
./gradlew :app:testDebugUnitTest lintDebug
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageRouteViewModel.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageRouteScreen.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/honor/pilgrimage/PilgrimageRouteScrollTest.kt
git commit -m "feat(honor): a link page centres its focused stage (Phase 22 U10)" \
  -m "iOS scrolls to the stage's row with anchor .center and the default animation. The row's list position counts the header items above it; the scroll then centres the row, clamped at the list's end, with Compose's default spring." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U11: The toast shows over the overview and inside every Honor sheet

**Files:**
- Modify: `P/ui/honor/HonorLinkToastView.kt` (add `LocalHonorLinkToast`)
- Modify: `P/ui/honor/HonorWaysSheet.kt:407-455` (`HonorSheetHost`)
- Modify: `P/ui/navigation/PilgrimNavHost.kt:234` (the `CompositionLocalProvider`), `:938-952` (`linkToastShows` and its KDoc, removing the overview exclusion at 949)
- Test: `T/ui/navigation/PilgrimNavHostTest.kt:156-167` (rewrite), `T/ui/honor/HonorSheetToastTest.kt` (new)

**Interfaces:**
- Consumes: `HonorImportHostViewModel.linkToast: StateFlow<HonorLinkToast?>`; U6's `Routes.HONOR_ROUTE_LINK_PATTERN`.
- Produces: `val LocalHonorLinkToast: ProvidableCompositionLocal<StateFlow<HonorLinkToast?>?>` (package `org.walktalkmeditate.pilgrim.ui.honor`); `linkToastShows` false over any Honor dialog, true over the overview.

- [ ] **Step 1: Rewrite the toast-gate test, and write the in-sheet test**

In `T/ui/navigation/PilgrimNavHostTest.kt`, replace `the one link toast shows on every tab and the walk screen, but not over a sitting or an overview` (lines 156-167) with:

```kotlin
    // Stage-links spec Q6: the overview shows the Activity's toast now; each Honor sheet draws its own.
    @Test
    fun `the one link toast shows on every tab, the walk screen and an overview, but not over a sitting or an Honor sheet`() {
        assertTrue(linkToastShows(listOf(Routes.PATH, Routes.ACTIVE_WALK)))
        assertFalse(linkToastShows(listOf(Routes.PATH, Routes.ACTIVE_WALK, Routes.MEDITATION)))
        assertFalse(linkToastShows(listOf(Routes.PATH, Routes.ACTIVE_WALK, Routes.MEDITATION, Routes.SOUNDSCAPE_PICKER)))
        assertTrue("under the recovery banner, in the same host", linkToastShows(listOf(Routes.PATH)))
        assertTrue(linkToastShows(listOf(Routes.PATH, Routes.HOME)))
        assertTrue(linkToastShows(listOf(Routes.PATH, Routes.SETTINGS)))
        assertTrue(
            "the overview is a screen in this window, as iOS repeats the toast in its overview sheet",
            linkToastShows(listOf(Routes.PATH, Routes.HONOR_OVERVIEW_PATTERN)),
        )
        assertTrue("under the summary's own window (Q6, option A)", linkToastShows(listOf(Routes.PATH, Routes.WALK_SUMMARY_PATTERN)))
        listOf(
            Routes.HONOR_WAYS,
            Routes.HONOR_OWN_WALKS,
            Routes.HONOR_PILGRIMAGES,
            Routes.HONOR_PILGRIMAGE_PATTERN,
            Routes.HONOR_ROUTE_LINK_PATTERN,
        ).forEach { assertFalse("the sheet draws its own: $it", linkToastShows(listOf(Routes.PATH, it))) }
        assertFalse(linkToastShows(emptyList()))
    }
```

Create `T/ui/honor/HonorSheetToastTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.honor.HonorLinkToast
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * The link toast inside an Honor sheet's own window (stage-links spec Q6;
 * iOS's `LinkToastOverlay` in each sheet, `MainTabView.swift:64,77,91,178-193@3b78728`):
 * it shows over the sheet's content, and taps pass through it to the sheet,
 * as iOS's `.allowsHitTesting(false)` lets them (`MainCoordinatorView.swift:548@3b78728`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorSheetToastTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `a link toast set while an Honor sheet is up shows inside it, and taps reach the sheet beneath`() {
        val toast = MutableStateFlow<HonorLinkToast?>(null)
        var tapped = 0
        composeRule.setContent {
            PilgrimTheme {
                CompositionLocalProvider(LocalHonorLinkToast provides toast) {
                    HonorSheetHost(onDismissed = {}) {
                        Box(Modifier.fillMaxWidth().height(240.dp).clickable { tapped++ })
                    }
                }
            }
        }

        composeRule.runOnIdle { toast.value = HonorLinkToast.NeedsNewerPilgrim }
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodesWithText(NEWER).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(NEWER).assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(1, tapped) }
    }

    @Test
    fun `with no toast provided, as with Honor off, a sheet draws none`() {
        composeRule.setContent {
            PilgrimTheme {
                HonorSheetHost(onDismissed = {}) { Box(Modifier.fillMaxWidth().height(240.dp)) }
            }
        }

        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(NEWER).fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    private companion object {
        const val NEWER = "this link needs a newer Pilgrim"
        const val WAIT_MILLIS = 5_000L
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHostTest' --tests 'org.walktalkmeditate.pilgrim.ui.honor.HonorSheetToastTest'`
Expected: FAIL at compile, `Unresolved reference 'LocalHonorLinkToast'`.

- [ ] **Step 3: The local, the in-sheet toast, the gate**

(a) In `P/ui/honor/HonorLinkToastView.kt`, add imports `androidx.compose.runtime.staticCompositionLocalOf` and `kotlinx.coroutines.flow.StateFlow`, and after `TOAST_MOTION_MS`:

```kotlin

/**
 * The app's link toast, for a window that covers the Activity's toast host,
 * an Honor sheet's: iOS repeats its overlay inside each sheet
 * (`MainTabView.swift:64,77,91,178-193@3b78728`). Null with Honor off, or
 * outside the nav host.
 */
val LocalHonorLinkToast = staticCompositionLocalOf<StateFlow<HonorLinkToast?>?> { null }
```

(b) In `P/ui/honor/HonorWaysSheet.kt`, add `import androidx.compose.runtime.collectAsState`; in `HonorSheetHost`, replace the `ModalBottomSheet`'s content lambda through `HonorSheetHost`'s own closing brace (lines 451-455):

```kotlin
    ) {
        if (onBack != null) BackHandler { hideThen(onBack) }
        Box(Modifier.fillMaxWidth()) {
            content(hideThen)
            if (onTop) SheetLinkToast(Modifier.align(Alignment.TopCenter))
        }
    }
}

/** The link toast at the top of a sheet's own window, which covers the Activity's (stage-links spec Q6). */
@Composable
private fun SheetLinkToast(modifier: Modifier) {
    val toasts = LocalHonorLinkToast.current ?: return
    val toast by toasts.collectAsState()
    HonorLinkToastView(toast = toast, modifier = modifier)
}
```

and add to `HonorSheetHost`'s KDoc, as its last paragraph: `While it is on top, the link toast shows at the sheet's top, inside its window, which covers the Activity's toast (stage-links spec Q6); a sheet under another draws none, so one toast shows at a time.`

(c) In `P/ui/navigation/PilgrimNavHost.kt`, add `import org.walktalkmeditate.pilgrim.ui.honor.LocalHonorLinkToast`; the line `CompositionLocalProvider(LocalAppHazeState provides appHazeState) {` (line 234 at `0ee2872e`; lower once U6 lands) becomes:

```kotlin
    CompositionLocalProvider(LocalAppHazeState provides appHazeState, LocalHonorLinkToast provides honorHost?.linkToast) {
```

and replace `linkToastShows` with its KDoc (lines 938-952) with:

```kotlin
/** The Honor routes that are dialogs: each sheet draws the link toast inside its own window (stage-links spec Q6). */
private val HONOR_SHEET_ROUTES = setOf(
    Routes.HONOR_WAYS,
    Routes.HONOR_OWN_WALKS,
    Routes.HONOR_PILGRIMAGES,
    Routes.HONOR_PILGRIMAGE_PATTERN,
    Routes.HONOR_ROUTE_LINK_PATTERN,
)

/**
 * Whether the app-wide toast shows over [backStack]'s screen. During a
 * walk only the walk screen shows it: not meditation (owner decision 3,
 * matching iOS's toast hidden under the meditation cover), nor anything
 * over it. An Honor sheet draws its own inside its window, so this one
 * hides behind it and one toast shows at a time. The overview, a screen in
 * this window, shows it, as iOS repeats the toast inside its overview
 * sheet (`MainTabView.swift:77@3b78728`). The summary's sheet, a window of
 * its own, covers it, as iOS's summary sheet carries none (Q6, option A).
 */
internal fun linkToastShows(backStack: List<String>): Boolean {
    val current = backStack.lastOrNull() ?: return false
    return when {
        Routes.ACTIVE_WALK in backStack -> current == Routes.ACTIVE_WALK
        current in HONOR_SHEET_ROUTES -> false
        else -> true
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHostTest' --tests 'org.walktalkmeditate.pilgrim.ui.honor.*'`
Expected: PASS, every sheet test unchanged (the `Box` passes the sheet's constraints through).

- [ ] **Step 5: The three CI jobs, then commit**

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
./gradlew compileReleaseUnitTestKotlin testReleaseUnitTest --tests 'org.walktalkmeditate.pilgrim.core.flags.*'
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorLinkToastView.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/ui/honor/HonorWaysSheet.kt \
  app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHostTest.kt \
  app/src/test/java/org/walktalkmeditate/pilgrim/ui/honor/HonorSheetToastTest.kt
git commit -m "feat(honor): the link toast shows over the overview and inside every Honor sheet (Phase 22 U11)" \
  -m "A dialog's window covers the Activity's toast, so each Honor sheet, the link page among them, draws the toast in its own window, and the Activity's copy hides behind it. The overview exclusion goes, as iOS 3b78728 shows the toast in its overview. The summary stays as iOS has it: no toast over it." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task U12: The device checklist

**Files:**
- Create: `docs/qa/2026-10-29-stage-links-qa.md`

**Interfaces:**
- Consumes: U1–U11 on `feat/stage-links`, a debug install (`./gradlew :app:installDebug`, package `org.walktalkmeditate.pilgrim.debug`).
- Produces: a checklist the owner runs on a phone, in one sitting.

- [ ] **Step 1: Write the checklist**

Create `docs/qa/2026-10-29-stage-links-qa.md` with exactly:

````markdown
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
````

- [ ] **Step 2: Commit, and hand the checklist to the owner**

```bash
git add docs/qa/2026-10-29-stage-links-qa.md
git commit -m "docs(qa): the stage-links device checklist (Phase 22 U12)" \
  -m "Cold and warm starts, routes installed, not installed and not in the catalog, a link mid-walk and over the summary, a second link on an open page, unreadable paths and stage numbers past the end, all with real routes from open-pilgrimages v1.13.0." \
  -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

The owner runs it on a phone and fills in the Result column. Nothing is pushed, no PR is opened, and nothing is released without the owner's word.

---

## Self-review

**Spec coverage.** Each spec behaviour and its task:

| Spec item | Task |
|---|---|
| Link grammar, hosts, stage number to index, pasted text, `%0A`, host case, root nil | U2 |
| Route-sheet identity, resolver order, focus range | U3 |
| One funnel, one pending slot, cold and warm drain once | existing `HonorLinkRouter.open`/`held`, carried by U8 |
| Tab-switch rule with the `unrecognised` exemption, paste skips it | U8, U5 |
| `open(.unrecognised)` only toasts, cancels nothing | U8 |
| Stage entry: mid-walk refusal, shared job, `Fetching` reset | U8, U5 |
| Snapshot read once, stage Way only for the installed route | U5 |
| Catalog loop `[false, true]`, cached hit never loads, failure line | U5 |
| A Begin in flight wins | U5 (`cancelImport`), U7 (`DROP`) |
| Outcome handling, `Listed` clears the toast | U4, U5 |
| AF60 `openRouteLink` / `openOverview` / promote / item swap / Begin clears / `walkAgain` | U5 (slot), U6 (one-step swaps), U7 (landing, `walkAgain`) |
| Every dismiss promotes | U7 (the back-stack-driven landing) |
| Toast: 5 s, one at a time, repeated inside sheets, not over the summary, no hit testing | existing expiry; U11 |
| Both doors wire the managers | Q10: nothing to wire, said in U5's KDoc, pinned by U6's walk-screen test |
| `deinit` cancels the job and the toast expiry | nothing to port: the coordinator and the router are process singletons, never torn down, as iOS's static `pendingLink` outlives its views |
| Focus once after stages load; scroll and prompt independent; try again | U9 |
| Scroll to `stage.index`, centred | U10 |
| Copy table, `Close` styling | U4, U6 |
| Test contract: `HonorLinkTests` (11) | U2 (the drain-once test is the router's existing `a held link opens once`) |
| Test contract: `StageLinkResolverTests` (10) | U3 |
| Test contract: `MainCoordinatorHonorTests` stage cases (27) | U5 (resolution, races, newest wins), U6 (swap, choose a stage), U7 (summary, walk again), U8 (while walking, a Begin in flight, unrecognised, `open(.stage)`) |
| Owner decisions: 2.1.0, Q6 option A, answers folded | U1, U11, § Decisions |

**Placeholder scan.** No "TBD", "TODO", "similar to", or step without its code. Every type a task uses is defined in a task before it or in the current tree, cited by line.

**Type consistency.** `HonorLinkDestination.Share(id)`, `.Stage(routeId, index)`, `.Unrecognised` (U2) are what U5's paste and U8's router match on. `HonorRouteLink(entry, release, focus)` and its `id` (U3) are what U5 publishes, U6 navigates by (`entry.id`, `focus`), U7 consumes, and U8's tests compare. `StageLinkOutcome.Overview(wayId)`, `.RouteSheet(link)`, `.NotInCatalog`, `.NeedsCatalog` (U3) are U5's `when`. `HonorImportOutcome.ReachingForRoute`, `.RouteNotInPilgrim`, `.CatalogFailed(error)` and `HonorLinkToast.ReachingForRoute`, `.RouteNotInPilgrim`, `.NeedsNewerPilgrim`, `.CatalogFailed(error)` (U4) are U5's emissions, U8's toasts, and U11's test. `ARG_FOCUS`, `NO_FOCUS` (U6) are U9's reads; `KEY_FOCUS_APPLIED`, `scrollTarget`, `consumeScrollTarget` (U9) are U10's. `openLinkedRoutePage(link: HonorRouteLink)`, `closeRouteLinkPage(entryId: String)` (U6) are U7's and the registration's. `routeLinkLanding` returns the existing `FetchedWayLanding`.

**Review Focus.** Each line has its test: 1 in U2 (`pasted text never comes back unrecognised`, and the `14.`/`14)` rows of both parser tests); 2 in U9 (`a rotation or a restore after the prompt was answered never asks again`); 3 in U6 (`the same link twice keeps the page and its entry, …`) and U9 (the latch); 4 in U9 (`a stage the installed release doesn't list gets no scroll and no prompt`); 5 in U6 (`a link page from deep in Settings opens over Path, and Settings keeps its place`).

