# Parity Spec: stage-links

| field | value |
|---|---|
| **iOS pin** | `3b78728` (main, per CLAUDE.md) |
| **Android HEAD** | `0ee2872` |
| **Generated** | 2026-10-28 |
| **Type** | port |
| **Generator** | ios-parity skill |

> **Pin.** iOS is pinned at `3b78728`, the merge of pilgrim-ios PR #125 (`feat/stage-links`). This is not the usual parity anchor, v2.0.0 at `7c200bf`, because stage links shipped on iOS after 2.0.0. Every commit that touches the nine iOS files below between `7c200bf` and `3b78728` belongs to this slice (`7db3672`, `86e60ef`, `e994d34`, `47c2dc1`, `ec48e06`, `eb62007`, `6afd1a9`), so `git diff 7c200bf 3b78728` over these files contains only this slice. `pilgrim-android/CLAUDE.md` freezes parity at `7c200bf` and sends later iOS deltas to the owner for triage. See Open questions.
>
> **Intent vs authority.** The iOS design spec `pilgrim-ios/docs/superpowers/specs/2026-10-28-stage-links-design.md` gives the intent. Where it differs from the Swift, the Swift at `3b78728` wins. See Lens disagreements → Design spec vs Swift.
>
> **Android shape.** Android navigates with one Compose nav host, where the Honor sheets are `dialog` destinations on a single back stack. It routes links through `HonorLinkRouter` and `HonorLinkScreen`, not through SwiftUI sheets. Three iOS mechanisms have no direct Android analogue: the AF60 park-and-promote slots, promotion from a sheet's `onDismiss`, and the `onDismiss` that fires on an item swap. This spec records the behaviour each one guarantees, and lists how to map it as an Open question. It does not design the Android side.
>
> **Out of scope.** The manifest's `autoVerify` filter already claims every path on `honor.pilgrimapp.org` (`app/src/main/AndroidManifest.xml:112-124@0ee2872`), so the manifest needs no change. The worker's stage page sends no install referrer, so a stage referrer is out of scope.

## iOS source map

- `Pilgrim/Models/Honor/HonorLink.swift` — 61 LOC — the single parser. It turns an OS URL or pasted text into `HonorLinkDestination` (`share`, `stage` or `unrecognised`) or nil. Holds the host allow-list, the share-id pattern, the stage-number pattern and `Notification.Name.pilgrimOpenWay`.
- `Pilgrim/Models/Honor/StageLink.swift` — 37 LOC — new. Holds `HonorRouteLink` (the route sheet's payload, whose identity is route plus focus), `StageLinkOutcome`, and the pure `StageLinkResolver.resolve`.
- `Pilgrim/PilgrimApp.swift` — 65 LOC — app entry. Sends `onOpenURL` and universal-link continuation through `route(_:)`, which stores the destination in the static `pendingLink` and posts it.
- `Pilgrim/Scenes/Root/MainCoordinatorView.swift` — 573 LOC — `MainCoordinator`. Holds the `open(_:)` dispatch, the `openStage` resolution task, the AF60 park slots (`pendingHonorWay`, `pendingRouteLink`), the promote and dismiss handlers, the link toast with its 5 s expiry, and the `HonorLinkToast` view.
- `Pilgrim/Scenes/Root/MainTabView.swift` — 194 LOC — tab host. Drains `pendingLink` and the notification into `open(_:)` under the tab-switch rule. Presents the new route sheet, wires every sheet's dismiss to a promote, and repeats `LinkToastOverlay` inside each cover and sheet.
- `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift` — 437 LOC — the route screen. Gains `focusStage`, centring through `ScrollViewReader`, and a one-shot `applyFocus()` that scrolls to the stage and raises the "Download this route first?" prompt.
- `UnitTests/Honor/HonorLinkTests.swift` — 96 LOC — parser contract: accepted and rejected forms, host case, 1-based numbering, malformed links becoming unrecognised, stage links only on the honor host, root as nil, paste never unrecognised, the pending link drained once, and `%0A` rejected.
- `UnitTests/Honor/StageLinkResolverTests.swift` — 67 LOC — resolver contract, ten cases.
- `UnitTests/Honor/MainCoordinatorHonorTests.swift` — 689 LOC (448 added in this slice) — coordinator contract for stage links: catalog loads and the force sequence, the mid-walk refusal, every park and promote branch, cancellation, and the unrecognised toast.

---

## Behavior

Each entry names the lenses that reported it. **[consensus]** means two or more lenses reported it. **(changed since `7c200bf`)** marks a change to behaviour Android already ported for share links. Android must take these changes even on the share path.

### `Pilgrim/Models/Honor/HonorLink.swift`

- **State machine: link grammar** [consensus: behavior, data, edge-cases]. The host is lowercased and checked against the allow-list. An empty path is nil. A single segment that is a share id is a share link on either host. Any other shape on the walk host is nil. Two segments with a valid route and number on the honor host are a stage link. Anything else on the honor host is `unrecognised`.

  ```swift
  guard let host = url.host?.lowercased(), hosts.contains(host) else { return nil }
  let parts = url.pathComponents.filter { $0 != "/" }
  guard !parts.isEmpty else { return nil }
  if parts.count == 1, isID(parts[0]) { return .share(id: parts[0]) }
  guard host == stageHost else { return nil }
  if parts.count == 2, let index = stageIndex(routeId: parts[0], number: parts[1]) {
      return .stage(routeId: parts[0], index: index)
  }
  return .unrecognised
  ```
  > `Pilgrim/Models/Honor/HonorLink.swift:22-32@3b78728`

- **State machine: hosts** [consensus: behavior, data, edge-cases]. Only `honor.pilgrimapp.org` carries stage links. The walk host carries share links only.

  ```swift
  static let hosts: Set<String> = ["honor.pilgrimapp.org", "walk.pilgrimapp.org"]
  /// Stage links live only where their fallback page does.
  static let stageHost = "honor.pilgrimapp.org"
  ```
  > `Pilgrim/Models/Honor/HonorLink.swift:16-18@3b78728`

- **State machine: stage number to index** [consensus: behavior, data, edge-cases, ui-visual]. The link counts from 1 and the data from 0. The number matches `\A[1-9][0-9]{0,2}\z` (no leading zero, at most three digits) and is at most `PilgrimageWayImporter.maxStageCount` (200), inclusive. The route id must pass `WayStore.isValidRouteId`, which is `\A[a-z0-9-]{1,64}\z`. A failure on the honor host falls through to `unrecognised`.

  ```swift
  guard WayStore.isValidRouteId(routeId),
        number.range(of: stageNumberPattern, options: .regularExpression) != nil,
        let n = Int(number), n <= PilgrimageWayImporter.maxStageCount else { return nil }
  return n - 1
  ```
  > `Pilgrim/Models/Honor/HonorLink.swift:51-55@3b78728`; pattern at `:20@3b78728`; cap at `Pilgrim/Models/Honor/PilgrimageWayImporter.swift:74@3b78728`; route-id regex at `Pilgrim/Models/Honor/WayStore.swift:63-65@3b78728`

- **Transition: pasted text** [consensus: behavior, data, edge-cases]. The text is trimmed of whitespace and newlines. A bare 10-character id is a share. Text without `://` gets `https://` prepended. An `unrecognised` result becomes nil, because the paste field already has its own failure line. Only a URL the OS hands the app can yield `unrecognised`.

  ```swift
  if isID(trimmed) { return .share(id: trimmed) }
  let withScheme = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
  guard let url = URL(string: withScheme), let link = parse(url), link != .unrecognised else { return nil }
  ```
  > `Pilgrim/Models/Honor/HonorLink.swift:36-43@3b78728`

### `Pilgrim/Models/Honor/StageLink.swift`

- **State machine: route-sheet identity** [consensus: behavior, ui-visual, data, edge-cases]. The identity is the entry id plus the focus, with `-` for a nil focus. A second link to another stage of the same route therefore presents the sheet again rather than leaving it where it was.

  `var id: String { "\(entry.id):\(focus.map(String.init) ?? "-")" }`
  > `Pilgrim/Models/Honor/StageLink.swift:11@3b78728`

- **Transition: resolver order** [consensus: behavior, ui-visual, data, edge-cases]. The resolver needs no disk and no network. The checks run in this order:
  1. The installed route is this route and the stage Way exists: `.overview`. No catalog is needed.
  2. No catalog: `.needsCatalog`.
  3. The route is not in `catalog.routes`: `.notInCatalog`.
  4. Otherwise `.routeSheet`. Its focus is nil when the index is outside `0..<entry.stageCount`.

  An installed route whose stage Way is missing (an older release) falls through to the route sheet, as does a route that is not installed or a different installed route.

  ```swift
  if installedRouteId == routeId, stageWayExists {
      return .overview(wayId: WayStore.stageWayId(routeId: routeId, stageIndex: index))
  }
  guard let catalog else { return .needsCatalog }
  guard let entry = catalog.routes.first(where: { $0.id == routeId }) else { return .notInCatalog }
  let focus = (0..<entry.stageCount).contains(index) ? index : nil
  return .routeSheet(HonorRouteLink(entry: entry, release: catalog.release, focus: focus))
  ```
  > `Pilgrim/Models/Honor/StageLink.swift:29-35@3b78728`

### `Pilgrim/PilgrimApp.swift`

- **Transition: one funnel** [behavior]. `onOpenURL` and universal-link continuation both go through `route(_:)`.

  ```swift
  .onOpenURL { url in Self.route(url) }
  .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
      if let url = activity.webpageURL { Self.route(url) }
  }
  ```
  > `Pilgrim/PilgrimApp.swift:47-50@3b78728`

- **Restoration path: one pending slot, newest wins** [consensus: behavior, ui-visual, data, edge-cases]. The slot now holds a destination rather than a share id. A URL that doesn't parse is dropped silently. An `unrecognised` link is stored and posted, so its toast shows.

  ```swift
  @MainActor static var pendingLink: HonorLinkDestination?
  ...
  guard let link = HonorLink.parse(url) else { return }
  pendingLink = link
  NotificationCenter.default.post(name: .pilgrimOpenWay, object: nil, userInfo: ["link": link])
  ```
  > `Pilgrim/PilgrimApp.swift:35,60-64@3b78728`

### `Pilgrim/Scenes/Root/MainTabView.swift`

- **Observer lifetime: a warm link clears the slot** [consensus: behavior, data, edge-cases]. The notification handler clears `pendingLink` before opening the link, so a later `onAppear` can't replay it.

  ```swift
  guard let link = note.userInfo?["link"] as? HonorLinkDestination else { return }
  PilgrimApp.pendingLink = nil
  open(link)
  ```
  > `Pilgrim/Scenes/Root/MainTabView.swift:93-100@3b78728`

- **Restoration path: a cold link is claimed once** [consensus: behavior, edge-cases].

  ```swift
  if let link = PilgrimApp.pendingLink {
      PilgrimApp.pendingLink = nil
      open(link)
  }
  ```
  > `Pilgrim/Scenes/Root/MainTabView.swift:101-107@3b78728`

- **Transition: tab-switch rule** [consensus: behavior, ui-visual, data, edge-cases] (changed since `7c200bf`: the `unrecognised` exemption is new). The app switches to the Path tab only when no walk is active and the link is readable. `coordinator.open` runs either way, so the mid-walk refusal toast still shows.

  ```swift
  private func open(_ link: HonorLinkDestination) {
      if coordinator.activeWalkViewModel == nil, link != .unrecognised { selectedTab = .path }
      coordinator.open(link)
  }
  ```
  > `Pilgrim/Scenes/Root/MainTabView.swift:172-175@3b78728`

- **Transition: paste skips the tab switch** [behavior]. The Ways sheet's paste field calls `coordinator.open` directly. A nil parse does nothing here; the field shows its own error.

  ```swift
  onPaste: { text in
      if let link = HonorLink.parse(text: text) { coordinator.open(link) }
  }
  ```
  > `Pilgrim/Scenes/Root/MainTabView.swift:60-62@3b78728`

- **Transition: every dismiss promotes** [consensus: behavior, edge-cases, ui-visual]. The Ways sheet and the route sheet promote directly. The overview goes through `handleOverviewDismiss` and the summary through `handleSummaryDismiss`. Missing any one of these hooks strands a parked link. The route sheet's hook is new in this slice.

  ```swift
  .sheet(isPresented: $coordinator.honorWaysPresented, onDismiss: coordinator.promotePendingHonorWay)
  .sheet(item: $coordinator.honorOverviewWay, onDismiss: coordinator.handleOverviewDismiss)
  .sheet(item: $coordinator.honorRouteLink, onDismiss: coordinator.promotePendingHonorWay)
  ```
  > `Pilgrim/Scenes/Root/MainTabView.swift:55,66,79@3b78728`

- **Transition: the route sheet** [consensus: behavior, ui-visual]. The sheet receives `focusStage = link.focus`. Its Close button sets `honorRouteLink` to nil. Choosing a stage calls `openOverview`, which parks the Way and closes the route sheet.

  ```swift
  PilgrimageRouteView(entry: link.entry, release: link.release,
                      onChoose: { coordinator.openOverview(for: $0) }, focusStage: link.focus)
  ```
  > `Pilgrim/Scenes/Root/MainTabView.swift:79-92@3b78728`

- **Transition: the toast is repeated inside each cover and sheet** [consensus: behavior, ui-visual, edge-cases]. A cover or sheet sits above the tab view's own overlay, so the answer to a link has to be repeated inside each one or it is never seen.
  > `Pilgrim/Scenes/Root/MainTabView.swift:47,64,77,91,178-193@3b78728`

### `Pilgrim/Scenes/Root/MainCoordinatorView.swift`

- **Transition: one entry point** [consensus: behavior, ui-visual, edge-cases]. `unrecognised` only shows a toast. It never cancels an import in flight.

  ```swift
  case .share(let id): openWay(shareId: id)
  case .stage(let routeId, let index): openStage(routeId: routeId, index: index)
  case .unrecognised: showLinkToast("this link needs a newer Pilgrim")
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:227-233@3b78728`

- **Transition: share path** [behavior, edge-cases] (unchanged since `7c200bf`; shown for context). A share link mid-walk is refused with a toast. Otherwise it cancels the previous import and shows "reaching for the walk…" only when the Ways sheet is closed. On success it resets the toast and state before the Begin guard.

  ```swift
  guard activeWalkViewModel == nil else { showLinkToast("finish this walk first"); return }
  importTask?.cancel()
  honorImportState = .fetching
  if !honorWaysPresented { showLinkToast("reaching for the walk…") }
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:239-242@3b78728`; success at `:250-259@3b78728`; failure at `:260-266@3b78728`

- **Transition: stage entry** [consensus: behavior, ui-visual, data, edge-cases]. A stage link mid-walk is refused before any state changes. Otherwise it cancels the shared `importTask`, so the newest link of either kind wins. It also clears a stranded `.fetching` spinner and wires the pilgrimage managers.

  ```swift
  guard activeWalkViewModel == nil else { showLinkToast("finish this walk first"); return }
  importTask?.cancel()
  if honorImportState == .fetching { honorImportState = .idle }
  wirePilgrimageManagers()
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:275-280@3b78728`

- **Dispatcher: snapshot on Main, read once** [consensus: behavior, data]. The task runs on `@MainActor`. Before any await it reads the installed route id once, and it loads the stage Way only when the installed route is the link's route. Both are synchronous disk reads.

  ```swift
  importTask = Task { @MainActor [weak self] in
      guard let self, !Task.isCancelled else { return }
      let installed = self.installedRouteId()
      let way = installed == routeId
          ? self.loadStageWay(WayStore.stageWayId(routeId: routeId, stageIndex: index))
          : nil
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:281-286@3b78728`

- **Async sync point: catalog loop** [consensus: behavior, ui-visual, data, edge-cases]. The cached in-memory catalog is tried first. `.needsCatalog` loads without forcing. `.notInCatalog` forces exactly one reload before the link is refused. "reaching for the route…" shows again before each load. A route found in the cached catalog never triggers a load. With no catalog and the route missing, the forces run `[false, true]`.

  ```swift
  var outcome = resolve(self.cachedCatalog())
  var forced = false
  while outcome == .needsCatalog || (outcome == .notInCatalog && !forced) {
      let force = outcome == .notInCatalog
      forced = forced || force
      self.showLinkToast("reaching for the route…")
      do {
          let catalog = try await self.loadCatalog(force)
          guard !Task.isCancelled else { return }
          outcome = resolve(catalog)
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:291-303@3b78728`. Rationale comment at `:293-295@3b78728`: "The in-memory catalog is never refreshed and the disk cache can be a day old … One forced load settles it before refusing."

- **Transition: catalog failure** [consensus: behavior, edge-cases]. The cancellation check comes first, so a replaced link stays silent. Otherwise the job is cleared and a toast shows the pilgrimage error line, defaulting to `.catalogUnreachable`.

  ```swift
  guard !Task.isCancelled else { return }
  self.importTask = nil
  self.showLinkToast(PilgrimageCopy.line(for: (error as? PilgrimageError) ?? .catalogUnreachable))
  return
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:304-309@3b78728`

- **Transition: a Begin in flight wins** [consensus: behavior, edge-cases]. After the link resolves, the code checks again for a walk that started or a Begin that was parked in the meantime. If either is set, it clears the toast and presents nothing, with no "finish this walk first".

  ```swift
  guard !Task.isCancelled else { return }
  self.importTask = nil
  guard self.activeWalkViewModel == nil, self.pendingStartWay == nil else {
      self.showLinkToast(nil)
      return
  }
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:311-318@3b78728`

- **Transition: outcome handling** [consensus: behavior, ui-visual, edge-cases]. `.overview` with a nil Way clears the toast and opens nothing. The Way is read once, at the start, and never again.

  ```swift
  case .overview:
      self.showLinkToast(nil)
      if let way { self.openOverview(for: way) }
  case .routeSheet(let link):
      self.showLinkToast(nil)
      self.openRouteLink(link)
  case .notInCatalog, .needsCatalog:
      self.showLinkToast("this route isn't in Pilgrim yet")
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:319-328@3b78728`

- **State machine: `openRouteLink` (AF60)** [consensus: behavior, ui-visual, edge-cases]. A full slot in either park means a sheet is mid-close. The newest request clears the other slot. The route link is parked when the Ways sheet, a summary, an overview or a closing sheet is up. Parking closes the Ways sheet and the overview but not the summary. Otherwise the route link is presented directly. An open route sheet is not in the condition, so it is swapped in place, which works because the id carries the focus.

  ```swift
  let sheetClosing = pendingHonorWay != nil || pendingRouteLink != nil
  pendingHonorWay = nil
  if honorWaysPresented || completedSnapshot != nil || honorOverviewWay != nil || sheetClosing {
      pendingRouteLink = link
      honorWaysPresented = false
      honorOverviewWay = nil
  } else {
      honorRouteLink = link
  }
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:336-348@3b78728`

- **State machine: `openOverview` (AF60)** [consensus: behavior, ui-visual, edge-cases] (changed since `7c200bf`: adds `sheetClosing`, `pendingRouteLink = nil`, the `honorRouteLink != nil` condition and the route-sheet close). Choosing a stage in the route sheet lands here. An open overview is not in the condition, so it is swapped in place.

  ```swift
  let sheetClosing = pendingHonorWay != nil || pendingRouteLink != nil
  pendingRouteLink = nil
  if honorWaysPresented || honorRouteLink != nil || completedSnapshot != nil || sheetClosing {
      pendingHonorWay = way
      honorWaysPresented = false
      honorRouteLink = nil
  } else {
      honorOverviewWay = way
      gather(way)
  }
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:360-369@3b78728`

- **Transition: promote order** [consensus: behavior, edge-cases] (changed since `7c200bf`: the route-link branch is new). A parked Way beats a parked route link. Each setter clears the other slot, so at most one is ever non-nil.

  ```swift
  if let way = pendingHonorWay {
      pendingHonorWay = nil
      honorOverviewWay = way
      gather(way)
  } else {
      promotePendingRouteLink()
  }
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:426-440@3b78728`

- **Transition: summary hosts** [behavior, edge-cases]. A summary shown from the tab sheet and one shown from the Journal host both promote on dismiss.

  ```swift
  homeViewModel.loadWalks()
  promotePendingHonorWay()
  ...
  HomeView(viewModel: coordinator.homeViewModel, onWalkAgain: coordinator.walkAgain, onSummaryDismiss: coordinator.promotePendingHonorWay)
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:196-200,524-528@3b78728`

- **Transition: `walkAgain`** [consensus: behavior, edge-cases] (changed since `7c200bf`: it was `pendingHonorWay = OwnWalkWayBuilder.make(from: walk)`, so a nil build used to clear the park). A nil build now parks nothing and keeps whatever was already parked. A successful build clears a parked route link and parks the Way.

  ```swift
  guard let way = OwnWalkWayBuilder.make(from: walk) else { return }
  pendingRouteLink = nil
  pendingHonorWay = way
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:420-424@3b78728`

- **Transition: Begin** [behavior]. Begin parks the start Way and closes the overview. The walk starts only from the overview's dismiss.

  ```swift
  pendingStartWay = way
  honorOverviewWay = nil
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:442-445@3b78728`

- **Transition: overview dismiss and item swap** [consensus: behavior, edge-cases] (changed since `7c200bf`: Begin now clears both parks, and a real close now promotes). When a link swaps the overview's item, SwiftUI fires `onDismiss` for the outgoing Way. On a swap, the state must not be reset and nothing may be promoted.

  ```swift
  if honorOverviewWay == nil {
      gatheringCancellable = nil
      honorImportState = .idle
  }
  if let way = pendingStartWay {
      pendingStartWay = nil
      pendingHonorWay = nil
      pendingRouteLink = nil
      startWalk(mode: .honor, way: way)
  } else if honorOverviewWay == nil {
      promotePendingHonorWay()
  }
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:447-465@3b78728`

- **Async sync point: starting a walk cancels the link job** [consensus: behavior, edge-cases].

  ```swift
  importTask?.cancel()
  importTask = nil
  gatheringCancellable = nil
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:96-98@3b78728`

- **Async sync point: link toast** [consensus: behavior, edge-cases]. One toast shows at a time. Each new one cancels the previous expiry, nil clears at once, and the literal expiry is 5 seconds.

  ```swift
  linkToastWork?.cancel()
  pendingLinkToast = text
  guard text != nil else { return }
  let work = DispatchWorkItem { [weak self] in self?.pendingLinkToast = nil }
  linkToastWork = work
  DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:469-476@3b78728`

- **Transition: both doors wire the managers** [consensus: behavior, edge-cases]. A stage link reaches the route screen without passing through the Ways sheet, so `openStage` installs the `isWalkActive` closures and the tiles hook just as `chooseWay` does.

  ```swift
  PilgrimagePackageManager.shared.isWalkActive = { [weak self] in self?.activeWalkViewModel != nil }
  PilgrimageTilesManager.shared.isWalkActive = { [weak self] in self?.activeWalkViewModel != nil }
  PilgrimagePackageManager.shared.tiles = PilgrimageTilesManager.shared
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:206-224@3b78728`

- **Leak suspicion: deinit** [edge-cases].

  ```swift
  deinit {
      bannerDismissWork?.cancel()
      linkToastWork?.cancel()
      importTask?.cancel()
  }
  ```
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:78-82@3b78728`

- **Singleton seams** [data]. Four closures can be injected, so tests run without disk or network: `installedRouteId`, `loadStageWay`, `cachedCatalog` and `loadCatalog`.
  > `Pilgrim/Scenes/Root/MainCoordinatorView.swift:43-46@3b78728`

### `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift`

- **First emission: focus applied once, after the stages load** [consensus: behavior, ui-visual, edge-cases]. Three things must hold: a focus exists, it hasn't been applied, and the stage list isn't empty. The scroll and the prompt are independent: the scroll happens only if the focused stage is in the list, and the prompt only if the route isn't installed.

  ```swift
  guard let focusStage, !focusApplied, !stages.isEmpty else { return }
  focusApplied = true
  if stages.contains(where: { $0.index == focusStage }) { scrollTarget = focusStage }
  if !isInstalled { promptDownload = true }
  ```
  > `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:354-359@3b78728`; state at `:76,104-105@3b78728` (`var focusStage: Int?`, `@State private var focusApplied = false`, `@State private var scrollTarget: Int?`)

- **Transition: call sites** [consensus: behavior, ui-visual, edge-cases]. Focus is applied after the initial load and again after a successful "try again". While a preview has failed the stage list is empty, so the guard blocks the prompt.

  ```swift
  .task {
      reload()
      await loadStagesIfNeeded()
      applyFocus()
  }
  ...
  Button("try again") {
      Task {
          await loadStagesIfNeeded(force: true)
          applyFocus()
      }
  }
  ```
  > `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:162-166,299-304@3b78728`

- **Transition: the scroll** [consensus: behavior, ui-visual, edge-cases]. Each row's id is `stage.index`. The scroll is animated and centres the row.

  ```swift
  ScrollViewReader { proxy in
      routeList
          .onChange(of: scrollTarget) { _, target in
              guard let target else { return }
              withAnimation { proxy.scrollTo(target, anchor: .center) }
          }
  }
  ...
  ForEach(stages, id: \.index) { stage in
      Button { open(stage) } label: { stageRow(stage) }
          .id(stage.index)
  ```
  > `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:117-125,283-285@3b78728`

### Test contract (port these as Android tests)

| file | tests (line@3b78728) |
|---|---|
| `UnitTests/Honor/HonorLinkTests.swift` | `testAcceptedForms` 8, `testRejections` 17, `testHostIsCaseInsensitive` 24, `testStageLinksCountFromOne` 29, `testMalformedStageLinksOnTheHonorHostAreUnrecognised` 40, `testStageLinksLiveOnlyOnTheHonorHost` 49, `testTheHonorHostRootIsNotALink` 54, `testPastedStageLinksAreAccepted` 59, `testPastedTextNeverComesBackUnrecognised` 66, `testPendingLinkIsDrainedOnce` 75, `testTrailingNewlineIsRejected` 87 |
| `UnitTests/Honor/StageLinkResolverTests.swift` | `testInstalledWithTheStageOpensTheOverview` 22, `testInstalledWithTheStageNeedsNoCatalog` 27, `testInstalledButTheStageMissingOpensTheRouteFocused` 32, `testNotInstalledOpensTheRouteFocused` 36, `testAnotherRouteInstalledOpensTheRouteFocused` 40, `testTheLastStageOpensTheRouteFocusedOnIt` 45, `testAStagePastTheEndOpensTheRouteUnfocused` 49, `testARouteAbsentFromTheCatalogSaysSo` 53, `testNoCatalogInHandAsksForOne` 57, `testFocusIsPartOfTheSheetIdentity` 61 |
| `UnitTests/Honor/MainCoordinatorHonorTests.swift` | `testStageLinkForAnInstalledStageOpensItsOverview` 248, `testStageLinkForARouteNotOnThePhoneOpensTheRouteFocused` 262, `testStageLinkLoadsTheCatalogWhenNoneIsInHand` 274, `testStageLinkForARouteEvenAFreshCatalogLacksSaysSo` 295, `testStageLinkForARouteAddedSinceTheCachedCatalogForcesOneLoad` 310, `testStageLinkForARouteInTheCachedCatalogNeverLoads` 324, `testStageLinkWithNoCatalogInHandLoadsUnforcedThenForcesOnceIfTheRouteIsMissing` 337, `testStageLinkWithTheCatalogOutOfReachSaysSo` 351, `testStageLinkWhileWalkingSetsTheToast` 362, `testStageLinkOverTheWaysSheetParksUntilItCloses` 376, `testStageLinkOverAnOverviewClosesItAndOpensOnDismiss` 395, `testABeginInFlightWinsOverAStageLink` 415, `testChoosingAStageInsideTheRouteSheetParksTheWay` 431, `testASecondStageLinkSwapsTheOpenRouteSheet` 447, `testStageLinkCancelsAShareImportInFlight` 458, `testStageLinkOverASummaryParksUntilItCloses` 477, `testNewestLinkWinsOverAnOlderShareParkedBehindASummary` 495, `testAnOverviewRequestedWhileARouteLinkIsParkedIsNotStranded` 511, `testAnOverviewItemSwapDoesNotPromoteAParkedRouteLink` 526, `testWalkAgainOnAWalkTooShortToBuildKeepsAParkedRouteLink` 538, `testAWayParkedBehindAWalkAgainOverviewIsNotStranded` 551, `testBeginClearsAWayParkedBehindTheOverview` 570, `testAStageLinkReplacedBeforeItsTaskRunsLandsNothing` 587, `testNewestLinkWinsWhileTheWaysSheetIsStillClosing` 603, `testACancelledStageLinkLandsNothing` 618, `testAnUnrecognisedLinkSaysANewerPilgrimIsNeeded` 638, `testAStageDestinationOpensTheStage` 649 |

---

## UI / Visual

### Layout

| Container | Children | Alignment | Spacing |
|---|---|---|---|
| Route sheet: `.sheet(item: $coordinator.honorRouteLink)` → `NavigationStack` (`MainTabView.swift:79-91@3b78728`) | `PilgrimageRouteView`; toolbar `ToolbarItem(placement: .cancellationAction)` "Close"; `LinkToastOverlay` last | Close on the leading side; title in `.principal`; ellipsis menu in `.primaryAction` when installed (`PilgrimageRouteView.swift:141-160@3b78728`) | — |
| `PilgrimageRouteView.body` (`PilgrimageRouteView.swift:117-125@3b78728`) | `ScrollViewReader { routeList }` | — | — |
| `routeList` `List` (`PilgrimageRouteView.swift:127-138@3b78728`) | header section with footer; a "next" section only when installed; a "Stages" section of rows, each `.id(stage.index)` | — | — |
| `LinkToastOverlay` (`MainTabView.swift:181-193@3b78728`) | `HonorLinkToast(text:)` | `.overlay(alignment: .top)` | — |
| Tab-level overlay (existing, `MainTabView.swift:148-161@3b78728`) | `RecoveryBanner`, then `HonorLinkToast` | top | `Constants.UI.Padding.xs` (4) |

### Dimensions

| Element | iOS value | Token | Notes |
|---|---|---|---|
| Toast inner horizontal padding | 16 | `Constants.UI.Padding.normal` → `PilgrimSpacing.normal` | already in `HonorLinkToastView.kt:69@0ee2872` |
| Toast inner vertical padding | 8 | `Constants.UI.Padding.small` → `PilgrimSpacing.small` | already in `HonorLinkToastView.kt:69@0ee2872` |
| Toast corner radius | `8` | (magic — flag) | already `RoundedCornerShape(8.dp)`, `HonorLinkToastView.kt:67@0ee2872` |
| Toast background opacity | `0.95` | (magic — flag) | already `alpha = 0.95f`, `HonorLinkToastView.kt:68@0ee2872` |
| Toast outer padding | horizontal 16, top 8 | `Padding.normal`, `Padding.small` | already in `HonorLinkToastView.kt:64-65@0ee2872` |
| "try again" min height | `44` | (magic — flag) | unchanged by this slice (`PilgrimageRouteView.swift:307@3b78728`) |
| Scroll target position | `anchor: .center` | — | Compose `animateScrollToItem` aligns to the top by default, so the row needs an offset to sit in the centre |

### Colors

| Role | iOS source | Android Theme.kt mapping |
|---|---|---|
| Route sheet "Close" | `.foregroundColor(.stone)` (`MainTabView.swift:87@3b78728`) | `pilgrimColors.stone` |
| Route title | `.foregroundColor(.ink)` (`PilgrimageRouteView.swift:147@3b78728`) | `pilgrimColors.ink` |
| Toast text | `Color(.ink)` (`MainCoordinatorView.swift:540@3b78728`) | `pilgrimColors.ink` (already) |
| Toast background | `Color(.parchmentSecondary).opacity(0.95)` (`MainCoordinatorView.swift:544@3b78728`) | `pilgrimColors.parchmentSecondary.copy(alpha = 0.95f)` (already) |
| "try again" | `.foregroundColor(.stone)` (`PilgrimageRouteView.swift:306@3b78728`) | `pilgrimColors.stone` |

### Typography

| Role | iOS | Android |
|---|---|---|
| Route sheet "Close" | `Constants.Typography.button` (Lato) | `pilgrimType.button` |
| Route title | `Constants.Typography.heading` | `pilgrimType.heading` |
| Toast | `Constants.Typography.caption` | `pilgrimType.caption` (already) |
| "try again" | `Constants.Typography.caption` | `pilgrimType.caption` |

### Motion

| Animation | iOS duration | Curve | Android equivalent |
|---|---|---|---|
| Toast in/out: `.transition(.move(edge: .top).combined(with: .opacity))`, `.animation(.easeInOut, value: coordinator.pendingLinkToast)` (`MainTabView.swift:189-192@3b78728`) | none given; the ui-visual lens reads the SwiftUI default as 0.35 s | `easeInOut` | existing `TOAST_MOTION_MS = 350`, `EaseInOut` slide plus fade (`HonorLinkToastView.kt:35,56-59@0ee2872`) |
| Scroll to the focused stage: `withAnimation { proxy.scrollTo(target, anchor: .center) }` (`PilgrimageRouteView.swift:122@3b78728`) | none given (default `withAnimation`) | default | `LazyListState.animateScrollToItem` with a centring offset; duration not set (see Open questions) |
| Toast expiry | `.now() + 5` (`MainCoordinatorView.swift:475@3b78728`) | — | existing `TOAST_MILLIS = 5_000L` (`HonorLinkRouter.kt:244@0ee2872`) |

### Conditional render rules

- **Focus gate** at `PilgrimageRouteView.swift:355@3b78728`: `guard let focusStage, !focusApplied, !stages.isEmpty else { return }`. Runs once per presentation, and only once stages are present.
- **Scroll only if the stage is listed** at `PilgrimageRouteView.swift:357@3b78728`: `if stages.contains(where: { $0.index == focusStage }) { scrollTarget = focusStage }`.
- **Prompt only if not installed** at `PilgrimageRouteView.swift:358@3b78728`: `if !isInstalled { promptDownload = true }`. An installed route with a missing stage asks nothing, because its Update button is the answer.
- **Download prompt (existing alert, now raised by focus too)** at `PilgrimageRouteView.swift:185-192@3b78728`. The title is "Download this route first?", the buttons are "Download" (which calls `beginInstall()`, so it becomes the Replace confirmation when another route is installed) and "Not now" (cancel), and the message is "Its stages have to be on your phone before you can walk one."
- **Tab switch** at `MainTabView.swift:173@3b78728`: `if coordinator.activeWalkViewModel == nil, link != .unrecognised { selectedTab = .path }`.
- **Where the toast is attached** at `MainTabView.swift:47,64,77,91@3b78728`: inside the walk cover, the Ways sheet, the overview sheet and the route sheet, plus the tab-level overlay. The summary sheet (`MainTabView.swift:49-54@3b78728`) and the seal share sheet (`:143-147@3b78728`) do not carry it.
- **The toast doesn't take taps** at `MainCoordinatorView.swift:548@3b78728`: `.allowsHitTesting(false)`.

### Copy (verbatim; lowercase; `…` is one character; the apostrophe is ASCII)

| String | Where | Source |
|---|---|---|
| `this link needs a newer Pilgrim` | `.unrecognised` from an OS link | `MainCoordinatorView.swift:231@3b78728` |
| `finish this walk first` | share or stage link mid-walk | `MainCoordinatorView.swift:239,275@3b78728` |
| `reaching for the route…` | before each catalog load | `MainCoordinatorView.swift:299@3b78728` |
| `this route isn't in Pilgrim yet` | still `.notInCatalog` or `.needsCatalog` after the loop | `MainCoordinatorView.swift:327@3b78728` |
| `PilgrimageCopy.line(for: .catalogUnreachable)` | catalog load threw | `MainCoordinatorView.swift:307@3b78728` |
| `reaching for the walk…` | share link, Ways sheet closed (existing) | `MainCoordinatorView.swift:242@3b78728` |
| `Close` | route sheet toolbar | `MainTabView.swift:85@3b78728` |

### Accessibility

- This slice adds no accessibility labels, identifiers or announcements. The toast posts no VoiceOver announcement, so a `liveRegion` on Android would go beyond iOS rather than match it. `Button("Close") { coordinator.honorRouteLink = nil }` (`MainTabView.swift:85@3b78728`). [ui-visual]

---

## Data

### Entities

#### `HonorLinkDestination` (`HonorLink.swift:6-10@3b78728`)

| field | type | nullable? | default | source |
|---|---|---|---|---|
| `share(id:)` | `String` (10 chars, `[A-Za-z0-9_-]`) | no | — | `HonorLink.swift:7@3b78728` |
| `stage(routeId:index:)` | `String`, `Int` (0-based) | no | — | `HonorLink.swift:8@3b78728` |
| `unrecognised` | — | — | — | `HonorLink.swift:9@3b78728` |

On Android this replaces the bare `String?` share id that `HonorLink.parse` returns today.

#### `HonorRouteLink` (`StageLink.swift:6-12@3b78728`)

| field | type | nullable? | default | source |
|---|---|---|---|---|
| `entry` | `PilgrimageCatalogEntry` | no | — | `StageLink.swift:7@3b78728` |
| `release` | `String` (the catalog's release) | no | — | `StageLink.swift:8@3b78728` |
| `focus` | `Int` (0-based stage) | yes | nil when out of range | `StageLink.swift:9,34@3b78728` |
| `id` (computed) | `String` = `"<entry.id>:<focus or ->"` | no | — | `StageLink.swift:11@3b78728` |

#### `StageLinkOutcome` (`StageLink.swift:14-19@3b78728`)

| field | type | nullable? | default | source |
|---|---|---|---|---|
| `overview(wayId:)` | `String` = `"pilgrimage:<route>:<index>"` | no | — | `StageLink.swift:15@3b78728`, `WayStore.swift:67-69@3b78728` |
| `routeSheet(_:)` | `HonorRouteLink` | no | — | `StageLink.swift:16@3b78728` |
| `notInCatalog` | — | — | — | `StageLink.swift:17@3b78728` |
| `needsCatalog` | — | — | — | `StageLink.swift:18@3b78728` |

#### Coordinator and app link state (in-memory only)

| field | type | nullable? | default | source |
|---|---|---|---|---|
| `honorRouteLink` (presented route sheet) | `HonorRouteLink` | yes | nil | `MainCoordinatorView.swift:23@3b78728` |
| `pendingRouteLink` (parked route sheet) | `HonorRouteLink` | yes | nil | `MainCoordinatorView.swift:32@3b78728` |
| `pendingHonorWay` (parked overview) | `Way` | yes | nil | `MainCoordinatorView.swift:28@3b78728` |
| `pendingStartWay` (Begin waiting for the overview to close) | `Way` | yes | nil | `MainCoordinatorView.swift:29@3b78728` |
| `pendingLinkToast` | `String` | yes | nil | `MainCoordinatorView.swift:22@3b78728` |
| `importTask` (shared by share and stage links) | `Task<Void, Never>` | yes | nil | `MainCoordinatorView.swift:281@3b78728` |
| `PilgrimApp.pendingLink` | `HonorLinkDestination` | yes | nil | `PilgrimApp.swift:35@3b78728` |

#### Catalog fields the slice reads (`PilgrimageCatalogService.swift:6-14,55-59@3b78728`)

| field | type | nullable? | default | source |
|---|---|---|---|---|
| `PilgrimageCatalogEntry.id` | `String` | no | — | `PilgrimageCatalogService.swift:6-14@3b78728` |
| `PilgrimageCatalogEntry.stageCount` | `Int` | no | — | `PilgrimageCatalogService.swift:6-14@3b78728` |
| `PilgrimageCatalog.release` | `String` | no | — | `PilgrimageCatalogService.swift:55-59@3b78728` |
| `PilgrimageCatalog.routes` | `[PilgrimageCatalogEntry]` | no | — | `PilgrimageCatalogService.swift:55-59@3b78728` |

### Persistence ops

- `installedRouteId` → `PilgrimagePackageManager.shared.installed()?.routeId` at `MainCoordinatorView.swift:43@3b78728`. Reads `route.json` and `release.txt`. iOS dispatcher: **Main** (a synchronous read inside the `@MainActor` task). Android: `PilgrimagePackageManager.installed()` is `suspend` and confined to its actor (`PilgrimagePackageManager.kt:208@0ee2872`), so it runs off Main.
- `loadStageWay` → `WayStore.shared.load(id:)` at `MainCoordinatorView.swift:44,283-286@3b78728`. Reads `way.json`, and only when `installed == routeId`. iOS dispatcher: **Main**. Android: `WayStore.load(id)` (`WayStore.kt:144@0ee2872`) on **IO**.
- `cachedCatalog` → `PilgrimageCatalogService.shared.catalog` at `MainCoordinatorView.swift:45@3b78728`. In memory and never refreshed. Android: `PilgrimageCatalogService.catalog: StateFlow` (`PilgrimageCatalogService.kt:98@0ee2872`).
- `loadCatalog(force)` → `PilgrimageCatalogService.shared.load(force:)` at `MainCoordinatorView.swift:46@3b78728`. Without force it serves a disk cache under 24 h old. Otherwise it fetches. On a failed fetch it returns any cache silently, and throws `.catalogUnreachable` only when there is none. **A forced load that fails with a cache on disk returns the stale cache, so the result can still be `.notInCatalog`.** (`PilgrimageCatalogService.swift:154-171@3b78728`; `cacheLifetime = 24 * 3600` at `:90@3b78728`.) Android: `suspend fun load(force: Boolean = false)` on `ioDispatcher` (`PilgrimageCatalogService.kt:124@0ee2872`).

### Network endpoints

| Endpoint | Method | Source | In CLAUDE.md ecosystem? |
|---|---|---|---|
| `https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@main/index.json` (not new; read through `load(force:)`) | GET | `PilgrimageCatalogService.swift:86@3b78728` | **no — flag** (already fetched by Android's `PilgrimageCatalogService.kt`) |
| `https://honor.pilgrimapp.org/<route>/<n>[/]` (inbound app link; the path shape is new, the host is not) | — (OS VIEW intent) | `HonorLink.swift:16-32@3b78728` | **no — flag** (the manifest already claims the host) |

### File I/O paths

- `<route dir>/route.json`, `<route dir>/release.txt` at `PilgrimagePackageManager.swift:96-97@3b78728`: read (installed detection).
- `<way dir>/way.json` at `WayStore.swift:104-108@3b78728`: read (stage Way existence).
- `catalog.json` at `PilgrimageCatalogService.swift:362@3b78728`: read and write (24 h catalog cache).

### DataStore / UserDefaults keys

| key | type | source |
|---|---|---|
| none persisted; in-memory notification `userInfo["link"]` (was `"shareId"`) | `HonorLinkDestination` | `PilgrimApp.swift:63@3b78728`, `MainTabView.swift:94@3b78728` |
| `Notification.Name("pilgrimOpenWay")` (in-memory) | `Notification.Name` | `HonorLink.swift:60@3b78728` |

---

## Edge cases & invariants

| citation | quote | invariant | why-could-Android-miss |
|---|---|---|---|
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:281-286@3b78728` | `let installed = self.installedRouteId()` | *dispatcher*: the installed route and the stage Way are read once, before the first await, and the Way is not read again | A direct port on `viewModelScope` (Main) reads files on Main. Moving the reads to IO adds suspension points, so check `isActive` after each hop. |
| **[consensus]** `Pilgrim/Models/Honor/HonorLink.swift:22-32@3b78728` | `guard host == stageHost else { return nil }` | *state-machine*: a share id is accepted on both hosts. A stage link is accepted only on the honor host. Any other shape on the walk host is nil, and on the honor host it is `unrecognised`. | Accepting stage paths on both hosts, or returning nil instead of `unrecognised` on the honor host, which silences the toast. |
| **[consensus]** `Pilgrim/Models/Honor/HonorLink.swift:51-55@3b78728` | `let n = Int(number), n <= PilgrimageWayImporter.maxStageCount else { return nil }` / `return n - 1` | *boundary*: `/1` → 0, `/200` → 199, `/201` → `unrecognised` | Off by one (`n` for `n-1`, or `<` for `<=`) shifts every stage. |
| **[consensus]** `Pilgrim/Models/Honor/HonorLink.swift:36-43@3b78728` | `link != .unrecognised else { return nil }` | *transition*: pasted text never yields `unrecognised` | Surfacing it in the paste field shows the wrong message. |
| **[consensus]** `Pilgrim/Models/Honor/StageLink.swift:11@3b78728` | `"\(entry.id):\(focus.map(String.init) ?? "-")"` | *state-machine*: the route sheet's identity is route plus focus, and a nil focus differs from every index | Keying on the route id alone leaves a second link on the old stage, with no focus and no prompt. |
| **[consensus]** `Pilgrim/Models/Honor/StageLink.swift:29-35@3b78728` | `if installedRouteId == routeId, stageWayExists {` | *transition*: a downloaded stage never waits on the catalog; installed but missing, not installed, or another route installed → route sheet | Checking the catalog first breaks offline opening; an error instead of the route sheet dead-ends the link. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:291-303@3b78728` | `while outcome == .needsCatalog \|\| (outcome == .notInCatalog && !forced) {` | *async*: at most one forced load; the sequence is `[false, true]` when there is no catalog and the route is missing; a hit in the cached catalog never loads | Reloading on every miss hits the network repeatedly. Skipping the forced load refuses routes added since a cache up to 24 h old. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:275-280@3b78728` | `if honorImportState == .fetching { honorImportState = .idle }` | *transition*: share and stage links share one job, so the newest wins, and a replaced share spinner is cleared | A separate job for stage links lets an old share import land on top. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:275@3b78728` | `guard activeWalkViewModel == nil else { showLinkToast("finish this walk first"); return }` | *threshold*: refused mid-walk before any state change | Opening a sheet during a walk, or a toast hidden under the walk screen. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:313-318@3b78728` | `guard self.activeWalkViewModel == nil, self.pendingStartWay == nil else {` | *threshold*: after the await, a walk or a parked Begin wins; the toast is cleared and nothing is presented | The re-check after the await is easy to drop, which races the overview close or interrupts the walk. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:336-348@3b78728` | `let sheetClosing = pendingHonorWay != nil \|\| pendingRouteLink != nil` | *state-machine (AF60)*: never two sheets at once; a full slot means a sheet is closing, so park; the newest request clears the other slot; an open route sheet is swapped | Presenting while a sheet is closing loses one presentation, and a parked item is stranded if nothing promotes it. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:360-369@3b78728` | `if honorWaysPresented \|\| honorRouteLink != nil \|\| completedSnapshot != nil \|\| sheetClosing {` | *state-machine (AF60)*: choosing a stage in the route sheet parks the Way and closes the route sheet; an overview request while a route link is parked is not stranded | Pushing the overview over the route sheet instead. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:426-440@3b78728` | `if let way = pendingHonorWay {` … `promotePendingRouteLink()` | *transition*: a parked Way is promoted before a parked route link | Presenting both. |
| **[consensus]** `Pilgrim/Scenes/Root/MainTabView.swift:55,66,79@3b78728` | `onDismiss: coordinator.promotePendingHonorWay` | *transition*: every sheet that can park promotes on its real close (Ways, route, summary from either host, overview) | One missing hook strands the parked link permanently. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:447-465@3b78728` | `} else if honorOverviewWay == nil {` | *transition*: an item swap is not a close, so there is no reset and no promote | A dismiss callback fired during a replace promotes a parked route link over the incoming overview. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:456-460@3b78728` | `pendingHonorWay = nil` / `pendingRouteLink = nil` | *transition*: Begin clears both parks | A stale parked link appears over the walk. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:420-424@3b78728` | `guard let way = OwnWalkWayBuilder.make(from: walk) else { return }` | *transition*: a nil build keeps the existing park; a successful build replaces the route link | Clearing before the guard loses a parked link when the walk is too short to build a Way. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:96-98@3b78728` | `importTask?.cancel()` | *async*: starting a walk cancels any link job in flight | A stage resolving after Begin presents a route over the walk. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:469-476@3b78728` | `DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)` | *async*: 5 s expiry; each new toast cancels the previous expiry | Without the cancel, a later toast is cleared early. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:218-224@3b78728` | `PilgrimagePackageManager.shared.isWalkActive = {` | *transition*: both entry doors wire the walk guard | Replace, Remove and Update are allowed mid-walk from a route screen opened by a link. |
| **[consensus]** `Pilgrim/Scenes/Root/MainTabView.swift:172-175@3b78728` | `link != .unrecognised { selectedTab = .path }` | *conditional*: no tab switch mid-walk or for an unreadable link | The walker is pulled off their tab for a link that goes nowhere. |
| **[consensus]** `Pilgrim/Scenes/Root/MainTabView.swift:178-193@3b78728` | `.overlay(alignment: .top) { if let toast = coordinator.pendingLinkToast {` | *conditional*: the toast is visible over the walk cover, the Ways sheet, the overview and the route sheet | A toast only on the root is invisible under a dialog. |
| **[consensus]** `Pilgrim/PilgrimApp.swift:60-63@3b78728` | `pendingLink = link` | *restoration*: one slot, newest wins; claimed exactly once by whichever comes first, `onAppear` or the notification | An unclaimed or uncleared slot replays the link a second time. |
| **[consensus]** `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:354-359@3b78728` | `guard let focusStage, !focusApplied, !stages.isEmpty else { return }` | *first-emission*: the focus latch is set only once stages exist, so a failed preview keeps the focus for the retry | Firing from `LaunchedEffect(Unit)` before the stages load, or setting the latch first, uses up the focus. |
| **[consensus]** `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:357-358@3b78728` | `if !isInstalled { promptDownload = true }` | *conditional*: the prompt depends only on install state and is independent of the scroll | Prompting for an installed route, or tying the prompt to the scroll. |
| **[consensus]** `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:122,285@3b78728` | `proxy.scrollTo(target, anchor: .center)` / `.id(stage.index)` | *layout*: the scroll targets `stage.index`, centred | Android rows sit below header items (key `"stage:$position:${stage.index}"`, `PilgrimageRouteScreen.kt:171@0ee2872`), so the raw index is the wrong list position. |
| **[consensus]** `Pilgrim/Scenes/Root/MainCoordinatorView.swift:281-282,301-303,304-308,311-312@3b78728` | `guard !Task.isCancelled else { return }` (four places) | *swallowed-cancellation*: a replaced link lands nothing: no read, no state, no toast, and it never clears the newer job's handle | A Kotlin `catch (e: Exception)` swallows `CancellationException` and shows "catalog unreachable". A missing `isActive` check after `load()` presents a stale route. |
| `Pilgrim/Models/Honor/HonorLink.swift:19@3b78728` | `"\\A[A-Za-z0-9_-]{10}\\z"` | *threshold*: exactly 10 characters, case-sensitive, anchored | `find()` or `$` accepts a trailing `\n` or an 11-character id. |
| `Pilgrim/Models/Honor/HonorLink.swift:20@3b78728` | `"\\A[1-9][0-9]{0,2}\\z"` | *threshold*: `0`, `014` and `+14` are rejected | `toIntOrNull` alone accepts `014`, `+14` and `0`. |
| `Pilgrim/Models/Honor/HonorLink.swift:23@3b78728` | `url.host?.lowercased()` | *idiomatic*: the host is folded, path segments are not (an uppercase route is `unrecognised`) | `Uri.getHost()` keeps case. The Android parser already folds it (`HonorLink.kt:41,56@0ee2872`), and that must not extend to segments. |
| `Pilgrim/Models/Honor/HonorLink.swift:24-25@3b78728` | `guard !parts.isEmpty else { return nil }` | *idiomatic*: the honor root, with or without `/`, is nil and shows no toast; a trailing slash, query and fragment are ignored | Treating the root as `unrecognised` shows "needs a newer Pilgrim" when the site itself is opened. |
| `UnitTests/Honor/HonorLinkTests.swift:87-95@3b78728` | `"https://honor.pilgrimapp.org/camino-frances/14%0A")), .unrecognised)` | *non-obvious*: an encoded newline is decoded into the segment and must fail both shapes | `pathSegments` decodes `%0A`; an unanchored regex accepts it. |
| `Pilgrim/Models/Honor/PilgrimageWayImporter.swift:74@3b78728` | `static let maxStageCount = 200` | *magic-number*: the link cap is the importer's own constant | A separate cap accepts links the importer later rejects. Android has `PilgrimageWayImporter.MAX_STAGE_COUNT = 200` (`PilgrimageWayImporter.kt:57@0ee2872`). |
| `Pilgrim/Models/Honor/WayStore.swift:63-65@3b78728` | `"\\A[a-z0-9-]{1,64}\\z"` | *idiomatic*: the route id is a lowercase slug | A case-insensitive regex routes `Camino-Frances/14`. |
| `Pilgrim/Models/Honor/StageLink.swift:33@3b78728` | `else { return .notInCatalog }` | *idiomatic*: `notInCatalog` is distinct from `needsCatalog` | Merging them skips the forced reload or loops forever. |
| `Pilgrim/Models/Honor/StageLink.swift:34@3b78728` | `(0..<entry.stageCount).contains(index) ? index : nil` | *boundary*: with `stageCount` 33, index 32 is focused and 33 is unfocused, but the sheet still opens | Using `<=` focuses a stage that doesn't exist; refusing dead-ends the link. |
| `Pilgrim/Models/Honor/PilgrimageCatalogService.swift:165-170@3b78728` | `} catch {` / `if let cached {` / `return cached.catalog` | *persistence*: a forced load that fails falls back to the cache, so it can end in `.notInCatalog` rather than an error | Treating a failed forced load as an error shows the wrong toast. |
| `Pilgrim/Scenes/Root/MainCoordinatorView.swift:283-286@3b78728` | `let way = installed == routeId` | *idiomatic*: the stage Way is loaded only for the installed route | Loading a stage from another route's package. |
| `Pilgrim/Scenes/Root/MainCoordinatorView.swift:320-322@3b78728` | `if let way { self.openOverview(for: way) }` | *idiomatic*: `.overview` with no Way opens nothing and clears the toast | Re-reading the Way, or leaving "reaching for the route…" on screen. |
| `Pilgrim/Scenes/Root/MainCoordinatorView.swift:250-259@3b78728` | `self.showLinkToast(nil)` … `guard self.activeWalkViewModel == nil, self.pendingStartWay == nil else { return }` | *threshold*: on the share path the state is reset first and then the link is dropped | Order matters; this is existing behaviour. |
| `Pilgrim/Scenes/Root/MainCoordinatorView.swift:78-82@3b78728` | `importTask?.cancel()` | *leak*: the job and the toast expiry are cancelled on deinit | Android's owner must cancel both when it is torn down. |
| `Pilgrim/Scenes/Honor/PilgrimageRouteView.swift:185-192@3b78728` | `Button("Download") { beginInstall() }` | *conditional*: the link's prompt goes through the Replace gate | Installing directly skips "Replace?". |
| `Pilgrim/Scenes/Root/MainCoordinatorView.swift:548@3b78728` | `.allowsHitTesting(false)` | *ui*: taps pass through the toast | A Compose `Surface` with click semantics would swallow them. |
| `Pilgrim/Scenes/Root/MainTabView.swift:60-62@3b78728` | `if let link = HonorLink.parse(text: text) { coordinator.open(link) }` | *transition*: a paste never switches the tab | Sending a paste through the OS-link funnel. |

---

## Android implementation notes

Reusable pieces found. Paths are under `app/src/main/java/org/walktalkmeditate/pilgrim/` unless marked.

- `honor/HonorLink.kt` (`object HonorLink`, `HonorLink.kt:33-86@0ee2872`) is the share parser. It has `HOSTS`, an anchored `ID` regex, `parse(uri: Uri)` for intents, `parse(url: URI)` with `percentDecoded`, and `parse(text)` through `java.net.URI`, cut at `?`/`#`, all returning `String?`. Extend it into a sealed `HonorLinkDestination` rather than writing a second parser, and keep each entry point's decoding rules. It already lowercases the host (`:41,56`).
- `honor/HonorLinkRouter.kt` (`@Singleton`, Main thread) maps to iOS's `PilgrimApp.route` stash, the `MainTabView` drain and `openWay`'s refusal and toast. Pieces:
  - `open(intent, restored)`, `route(shareId, released)` and `routeReferrer`;
  - `screenChanged`/`screenGone`, `pathSwitch`/`pathSwitchTaken`, `drainIfReady`, `dispatch(shareId)`, `answer` and `showToast`;
  - `held: HeldLink(shareId, released)`, the newest-wins hold through setup (`:154-160,190-197@0ee2872`). Its payload becomes a destination.
  - The stage dispatch sits beside `dispatch(shareId)` (`:199-216@0ee2872`).
  - `TOAST_MILLIS = 5_000L` (`:244@0ee2872`) already matches the 5 s expiry, and `toastExpiry: Job` already gives the cancel-previous behaviour (`:229-237@0ee2872`).
- `HonorLinkScreen` (`HonorLinkRouter.kt:28-39@0ee2872`) has `inSetup`, `atPath`, `walkScreenUp`, `waysSheetUp`, `summaryUp` and `overviewUp`. A `routeSheetUp` flag would be added here.
- `HonorLinkToast` (`HonorLinkRouter.kt:42-48@0ee2872`) has `FinishWalkFirst`, `Reaching` and `Failed(WayError)`. Add variants for "reaching for the route…", "this route isn't in Pilgrim yet", "this link needs a newer Pilgrim" and the catalog failure. `ui/honor/HonorLinkToastView.kt` already matches the iOS visuals: padding, 8 dp radius, 0.95 alpha, caption, ink, and the 350 ms `EaseInOut` slide and fade.
- `data/honor/pilgrimage/PilgrimageModels.kt:27-37@0ee2872` defines `PilgrimageCopy.line(error): @StringRes Int`. Use `PilgrimageError.CATALOG_UNREACHABLE` for the catalog-failure toast; don't add a new string.
- `data/honor/pilgrimage/PilgrimageCatalogService.kt` has `catalog: StateFlow<PilgrimageCatalog?>` (`:98`) and `suspend fun load(force: Boolean = false)` on `ioDispatcher` (`:124`), ported from iOS with the 24 h cache and the cache fallback.
- `data/honor/pilgrimage/PilgrimagePackageManager.kt` has `suspend fun installed()` (`:208`) and `installedRoute()` (`:218`), confined to its actor and off Main.
- `data/honor/WayStore.kt` has `stageWayId(routeId, stageIndex) = "pilgrimage:$routeId:$stageIndex"` (`:720`), `isValidRouteId` (`:717`) and `load(id)` (`:144`).
- `data/honor/pilgrimage/PilgrimageWayImporter.kt:57@0ee2872` has `MAX_STAGE_COUNT = 200`. Reuse it as the link cap.
- `data/honor/pilgrimage/PilgrimageWalkGuard.kt` defines `PilgrimageWalkSignals` with `walkScreenUp()`, `walkActive()`, `beginInFlight()` and `liveSessionWayIds()`. It is the package guard's view of "a walk is on", and its KDoc describes it as built in the UI process for the package manager. See Open questions on whether the link door needs any wiring.
- `ui/navigation/PilgrimNavHost.kt`:
  - `honorSheet` is `dialog(route, …)` (`:1100-1106`).
  - The route page is `HONOR_PILGRIMAGE_PATTERN = "honor_pilgrimage/{routeId}"` (`:150-152`), hosted by `PilgrimageRouteSheet(onBack = closePilgrimageRoute, onClosed = closePilgrimages, onOpenStage = openStoredWayOverview)` (`:1059-1065`).
  - `honorLinkScreen(backStack)` is at `:894-904`.
  - `FetchedWayLanding` `PRESENT`/`WAIT`/`PARK`/`DROP` and `fetchedWayLanding(backStack, parkedBehindSummary)` (`:907-936`) are Android's existing park for share links. `HonorLinksLanding` (`:969-1006`) applies it.
  - `linkToastShows(backStack)` (`:945-952`) is the one app-level toast gate, and `LinkToastHost` (`:696,955-958`) renders it.
- `ui/honor/pilgrimage/PilgrimageRouteViewModel.kt`:
  - `ARG_ROUTE_ID` comes from `SavedStateHandle`.
  - `openingState()` (`:655-688`) reads the entry and release from `catalogs.catalog.value`, giving `Resolving` or `Gone`.
  - `PilgrimageRouteAlert.DOWNLOAD_FIRST` (`:49`) and `retryStages()` (`:421`) exist.
  
  The focus argument, the one-shot prompt and the re-apply after a retry go here. `PilgrimageRouteScreen.kt` renders a `LazyColumn` with `header`, `next` and `stages-header` items above the stage rows (`:150-171`), and a top-bar `IconButton(onClick = actions.onBack)` (`:116`).
- `app/src/main/AndroidManifest.xml:112-124@0ee2872`: the `autoVerify` filter claims every path on the honor host, and only the honor host. Its comment says "a path that doesn't parse opens the app and does nothing". That stops being true once `unrecognised` shows a toast, so update the comment.
- Conventions: cite iOS in KDoc pinned as `File.swift:lines@3b78728` (existing KDoc cites `@7c200bf`). Disk and network reads run on `ioDispatcher` or actors, never on Main. The router is a process singleton that outlives Activity recreation, like iOS's static `pendingLink`.

Mapping deltas vs the `pilgrim-android/CLAUDE.md` base table (delta entries only):

- `.sheet(item:)` identity and `onDismiss` → `dialog` destinations on one `NavController` back stack. There is no per-sheet dismiss callback and no item-swap callback. See Open questions 1–3.
- A `@MainActor Task` with synchronous disk reads → a coroutine whose `installed()` and `WayStore.load` run off Main, with `isActive`/`ensureActive()` after each suspension and `CancellationException` rethrown from every `catch`.
- `NotificationCenter` plus a static `pendingLink` → `HonorLinkRouter.held` with `drainIfReady()`. `onOpenURL` and `onContinueUserActivity` → `open(intent, restored)` for the launch intent and `onNewIntent`.
- The walk host is never claimed on Android (manifest comment), so walk-host links reach the parser only by paste. Its rules (share only, two segments → nil) still apply to `parse(text)`.
- `ScrollViewReader.scrollTo(_, anchor: .center)` → `LazyListState.animateScrollToItem` with a centring offset, mapping `stage.index` to its list position below the header items.

---

## Lens disagreements

- **Conditional render: does a stage outside the route prompt?** at `PilgrimageRouteView.swift:354-359@3b78728` and `StageLink.swift:34@3b78728`. The ui-visual lens (applyFocus finding) says "an out-of-range focus still prompts, with no scroll". The ui-visual outcomes finding and the edge-cases lens say an index ≥ `stageCount` gives `focus = nil`, so `applyFocus` returns early with no scroll and no prompt. The Swift supports both, for two different cases:
  - Index ≥ `entry.stageCount`: the resolver sets `focus` to nil, so there is no prompt and no scroll.
  - Focus non-nil but missing from the loaded stage list (the catalog's `stageCount` disagrees with the preview list): no scroll, and the prompt still shows if the route isn't installed (`:357-358`).

  Why it matters: Android tests should cover both cases separately.
- **Citation for `MainTabView.open`**. The data lens cites `MainTabView.swift:168-171@3b78728`; behavior, ui-visual and edge-cases cite `:172-175`. The function is at 172-175, and 169-171 is its doc comment. Cosmetic.
- **Overview dismiss**. The edge-cases lens (the "EVERY dismiss must promote" finding) lists the overview among sheets that "call promotePendingHonorWay". The behavior lens and the item-swap finding in edge-cases are more precise: the overview calls `handleOverviewDismiss`, which promotes only on a real close (`honorOverviewWay == nil`) and never when Begin parked a start Way (`MainCoordinatorView.swift:447-465@3b78728`). The precise reading is used above.
- **Toast motion duration**. Only ui-visual gives a number ("SwiftUI `.easeInOut` with no duration defaults to 0.35s"). The Swift sets no duration (`MainTabView.swift:192@3b78728`). Android's existing 350 ms is consistent with that reading.
- **Single-lens findings** (only one lens reported each; kept above, listed here for triage):
  - accessibility, none added (ui-visual)
  - toast `allowsHitTesting(false)` (ui-visual)
  - the `onOpenURL`/`onContinueUserActivity` funnel (behavior)
  - the four injectable seams (data)
  - the forced load's cache fallback (data)
  - `%0A` rejection (edge-cases)
  - `deinit` cancellation (edge-cases)
- **Design spec vs Swift (the Swift wins)**:
  - The design names the cap `PilgrimageCatalogService.maxStageCount`; the code uses `PilgrimageWayImporter.maxStageCount` (`HonorLink.swift:54@3b78728`).
  - The design's `routeSheet(entry:focus:)` is `routeSheet(HonorRouteLink)` in code, and it also carries `release` (`StageLink.swift:6-16@3b78728`).
  - The design's tab rule is "switch to the Path tab unless a walk is on". The code adds `link != .unrecognised` (`MainTabView.swift:173@3b78728`).
  - The design §3.2 describes one `load()` on `.needsCatalog`. The code adds exactly one forced load on `.notInCatalog` (`MainCoordinatorView.swift:291-303@3b78728`, commit `6afd1a9`).
  - The design §3.4 promotes the route link from the Ways and summary dismisses. The code also wires the route sheet's own dismiss and the overview's real close (`MainTabView.swift:79@3b78728`, `MainCoordinatorView.swift:461-463@3b78728`), and `walkAgain` keeps a parked link on a nil build.

---

## Open questions

1. **AF60 park slots → Android.** iOS keeps two in-memory slots (`pendingHonorWay`, `pendingRouteLink`, `MainCoordinatorView.swift:28,32@3b78728`), and the rules below must hold:
   - never two Honor sheets at once;
   - a full slot means a sheet is closing, so the next request parks;
   - the newest request clears the other slot;
   - a parked Way beats a parked route link;
   - Begin clears both;
   - `walkAgain` with a nil build keeps the existing park.

   Android's existing analogue for share links is `FetchedWayLanding` plus `parkedBehindSummary` (`PilgrimNavHost.kt:907-1006@0ee2872`), and it has no route-link variant. Decision needed: extend that landing model with a parked route link, or replace the front `dialog` with one `NavController` operation, which removes the mid-close window that the slots exist for. Each option needs a test showing the newest-wins and Begin-clears rules still hold.
2. **Promotion from a sheet's `onDismiss` → Android.** iOS promotes a parked item from the dismiss of the Ways sheet, route sheet, summary (both hosts) and overview (`MainTabView.swift:55,66,79@3b78728`, `MainCoordinatorView.swift:196-200,524-528@3b78728`). Compose `dialog` destinations have no per-destination dismiss callback. Decision needed: what event promotes a parked route link on Android. The back-stack change seen by `HonorLinksLanding`, as `PARK` already relies on, is one candidate. Whatever is chosen, every host that can park must promote.
3. **Item-swap `onDismiss` → Android.** SwiftUI fires `onDismiss` for the outgoing overview when its item is swapped, and iOS guards against it (`MainCoordinatorView.swift:447-465@3b78728`): there must be no reset of gathering or import state and no promote unless `honorOverviewWay == nil`. Android has no such callback, but an overview-to-overview or route-to-route replacement still must not reset the incoming overview's gathering or promote a parked link over it. Decision needed: how a replacement is told apart from a real close on Android (compare `showsStoredOverview`/`gatherShownAgain` in `PilgrimNavHost.kt@0ee2872`).
4. **How the route page opens from a link, and its identity.** On iOS the route screen is its own sheet keyed by `entry.id:focus`, with a leading "Close" (`MainTabView.swift:79-91@3b78728`; `StageLink.swift:11@3b78728`). On Android the route page is `honor_pilgrimage/{routeId}`, a `dialog` normally stacked over the Ways sheet and the catalog. Its `onBack` returns to the catalog, `onClosed` pops both, and its top bar shows a back `IconButton` (`PilgrimNavHost.kt:1059-1065,1118-1125@0ee2872`; `PilgrimageRouteScreen.kt:116@0ee2872`). Decide:
   - how a second link to another stage of the same route re-presents the page and runs the focus again (the route string must differ by focus);
   - what Back, swipe and Close do when no catalog is underneath;
   - whether the link-opened page shows iOS's "Close" in `pilgrimType.button`/`stone`.
5. **Where the route page gets its entry and release.** iOS passes the resolved `entry` and `catalog.release` into the sheet (`StageLink.swift:35@3b78728`). Android's `PilgrimageRouteViewModel.openingState()` reads `catalogs.catalog.value` and yields `Resolving` or `Gone`, where `Gone` "closes back to the catalog" (`PilgrimageRouteViewModel.kt:98,655-688@0ee2872`). Confirm the VM sees the same catalog the resolver used, including after the one forced load, and decide what `Gone` does on a page opened by a link.
6. **Toast coverage over dialog windows.** At `3b78728`, iOS repeats the toast inside the walk cover, the Ways sheet, the overview and the route sheet, but not the summary sheet (`MainTabView.swift:47,49-54,64,77,91@3b78728`). Android renders one `LinkToastHost` in the Activity window (`PilgrimNavHost.kt:696@0ee2872`), and the Honor sheets are `dialog` windows. Android's `linkToastShows` hides the toast over the overview, citing the iOS `7c200bf` behaviour that this slice changed (`PilgrimNavHost.kt:938-952@0ee2872`). Decide how the toast is drawn above dialog destinations and whether the overview exclusion is dropped to match `3b78728`.
7. **Tab-switch rule.** iOS switches to Path whenever no walk is on and the link is readable (`MainTabView.swift:172-175@3b78728`). Android's router switches only when `!atPath && !keepsItsPlace()`, where a summary, the Ways sheet or an overview keeps its place (`HonorLinkRouter.kt:211,239@0ee2872`, a deliberate earlier adaptation). The port must add "`unrecognised` never switches the tab". Decide whether a `routeSheetUp` joins `keepsItsPlace()`, and whether a stage link keeps the share link's carve-outs.
8. **One job for both link kinds.** iOS shares `importTask` between share imports and stage resolution: the newest link of either kind cancels the other, and a stage link resets a stranded `.fetching` (`MainCoordinatorView.swift:275-281@3b78728`). On Android the share import lives in `HonorImportCoordinator`, and the router has its own `routing: Job?` (`HonorLinkRouter.kt:154-160,199-216@0ee2872`). Decision needed: where the stage-resolution job lives, so that a stage link cancels a share import in flight (and resets its state) and a share link or the walk screen opening cancels a stage resolution.
9. **"A Begin in flight wins" on Android.** After the catalog await, iOS checks again for `activeWalkViewModel == nil && pendingStartWay == nil` (`MainCoordinatorView.swift:315@3b78728`). Android's router reads `walkScreenUp || trackerWalking()`, and the package guard also has `beginInFlight()` (`PilgrimageWalkGuard.kt@0ee2872`). Confirm which Android signal stands for `pendingStartWay` in the link path.
10. **Wiring the walk guard from the link door.** iOS wires `isWalkActive` and the tiles hook from both `chooseWay` and `openStage` (`MainCoordinatorView.swift:206-224,280@3b78728`). Android's `PilgrimageWalkSignals` appears to be built once in the UI process, independent of which door was used. Confirm that a route page opened by a link refuses Replace, Remove and Update mid-walk with no extra wiring.
11. **Scroll animation (magic).** iOS uses the default `withAnimation` with `anchor: .center`, with no duration or Constants token (`PilgrimageRouteView.swift:122@3b78728`). Choose the Android duration and curve, and how the centring offset is computed for `animateScrollToItem`.
12. **Magic numbers with no Constants token.** Toast `cornerRadius(8)` and `.opacity(0.95)` (`MainCoordinatorView.swift:544-545@3b78728`; Android already mirrors them), and the "try again" `.frame(minHeight: 44)` (`PilgrimageRouteView.swift:307@3b78728`, unchanged by this slice). Confirm these stay literal.
13. **Release target.** `pilgrim-android/CLAUDE.md` freezes parity at `7c200bf` and triages later iOS deltas into Android 2.0.1. Stage links (`3b78728`) postdate the pin. Confirm with the owner that this slice targets 2.0.1 (alongside the already re-triaged iOS PR #91), not 2.0.0.
14. **Endpoints missing from the CLAUDE.md "Backend URLs" table.** Neither the catalog index (`https://cdn.jsdelivr.net/gh/walktalkmeditate/open-pilgrimages@main/index.json`, `PilgrimageCatalogService.swift:86@3b78728`) nor the inbound `https://honor.pilgrimapp.org/<route>/<n>` app-link shape is listed. Neither is new to Android: the catalog service fetches the index and the manifest claims the host. Confirm whether to add them.

---

> Spec written. Run `superpowers:writing-plans <path>` to break into tasks, or `jutsu swarm doc-review <path>` for a remote QA gate first.

## Owner decisions (2026-10-29)

- **All 14 open questions are answered as drafted** in `docs/parity/.stage-links-answers.md`. The plan copies these answers in, and the answers file is then removed.
- **Q6:** the toast is hidden while the walk summary is open, matching iOS (option A). It shows over the Honor sheets and the link page, through `HonorSheetHost`.
- **Q13:** this port ships in **Android 2.1.0**, together with iOS PR #91, at parity with iOS `main` at `3b78728`. Android already matched iOS #92 (Mapbox telemetry off).
