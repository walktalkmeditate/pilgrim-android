# Parity Spec: Stage 21-0 groundwork

| field | value |
|---|---|
| **iOS pin** | `v2.0.0` = `7c200bf` |
| **Android HEAD** | `38497e63` |
| **Generated** | 2026-09-29 |
| **Type** | port |
| **Generator** | ios-parity skill: one reader per Stage 21-0 area (five in parallel), since this slice is a set of small cross-cutting behaviors rather than one vertical slice |
| **Plan** | `docs/plans/2026-09-29-001-feat-honor-groundwork-own-shared-walks-plan.md` (U2; feeds U3–U8) |

This spec outranks the plan wherever they disagree. Every iOS claim below carries a Swift quote pinned to `7c200bf`; every Android claim is pinned to `38497e63`.

| Section | Feeds |
|---|---|
| [A. Map camera fits, #219, Mapbox engine line](#a-map-camera-fits-219-and-the-mapbox-engine-line) | U3, U4 |
| [B. Recording coordinates and the interactive caption](#b-recording-coordinates-and-the-interactive-caption) | U5 |
| [C. Honor events in `.pilgrim`, #223](#c-honor-events-in-pilgrim-and-the-223-sittings-single-source) | U6 |
| [D. About credits, #225, #221](#d-about-credits-225-and-221) | U7 |
| [E. U8 audits](#e-u8-audits) | U8 |

---

## Resolutions

The research sections end with open questions. This section settles each one under the parity rule: match iOS as shipped, file iOS defects upstream, and keep an Android-only behavior only when it is a platform equivalent. These resolutions bind U3–U8; where a section's "Delta" bullet offers a choice, the resolution here wins.

### U3 — Mapbox

- Bump to `com.mapbox.maps:android-ndk27:11.23.1`. The artifact and the fallback `11.21.10` both resolve on Mapbox's Maven. The NDK 27 variant pulls a matching `-ndk27` variant of every submodule the app imports.
- iOS CI and release actually build 11.20.0 (workspace lockfile), not 11.23.1 (project lockfile), so the "iOS is on 11.23.1" rationale is a lockfile the build doesn't use. 11.23.1 is still inside R7's "11.20 or later, before 11.24", so the bump stands. Filed upstream: **pilgrim-ios #93**.
- The telemetry opt-out stays: Android already opts out, and iOS PR #92 (after the pin) converges on it. Re-check that `telemetry()` compiles after the bump. Main-process gating needs no change.
- `whisper.cpp` already links with 16 KB pages (`app/src/main/cpp/CMakeLists.txt:9`, NDK 28), so Mapbox's libraries are the only misaligned native code; the 16 KB check runs on the release bundle.

### U4 — Camera fits and gestures

- **Fit rules F1–F8 port exactly** (section A §1), including iOS's exact comparison of the **raw** inset. That comparison re-eases on card-height changes past the clamp, but Android matches it as shipped; filed upstream as **pilgrim-ios #94**.
- **Retry:** use the async `cameraForCoordinates` form, which waits for the map size, with a generation guard that drops a stale result, and re-evaluate the decision when the map size changes. An empty result leaves the last-applied record untouched. The fit counts as applied once a non-empty camera is computed and `easeTo` is issued; nothing waits for the animation to finish.
- **Units:** iOS points map to dp, converted to px at the `EdgeInsets` boundary.
- **Drop `MAX_FIT_ZOOM`** on fit paths (iOS passes `maxZoom: nil`). Reveal and deselect fit the `boundsForRoute`-padded bounds (15% + 0.001°), as iOS does, which keeps a tiny walk off street zoom.
- **Durations and curve:** the default fit ease is 0.4 s, and the reveal is 2.5 s. **Segment taps and deselects also take 2.5 s**, because iOS never resets `cameraDuration` after the reveal. Android's 350 ms and the comment citing an iOS 350 ms go. Asked upstream: **pilgrim-ios #95**. The curve is ease-out `PathInterpolatorCompat.create(0f, 0f, 0.58f, 1f)`.
- **Reduce motion:** the reveal's reduce-motion snap is **removed**, so Android always eases, as iOS does (owner decision, 2026-09-29, strict parity). Filed upstream: **pilgrim-ios #96**. If iOS adds Reduce Motion there, Android re-adds its snap on the next re-pin.
- **Gestures:** disable rotate and pitch gestures (`rotateEnabled = false`, `pitchEnabled = false`), and gate the summary map's pan and pinch on `revealPhase == Revealed`, both matching `PilgrimMapView.swift:145-148@7c200bf`.
- **Initial seed:** zoom 16 for a last-known fix, 14 for the last walk's end, no seed padding, matching `MapCameraSeed.forActiveWalk`. It stays `setCamera`.
- **Follow viewport (2e):** leave it unclamped and untouched; it is already at parity.
- **Accepted platform differences, recorded at the gate:** a plain tap idles the follow viewport on iOS but not on Android (SDK behavior). Logo and attribution placement stay at each SDK's defaults (iOS reverted its ornament lift in `65285a1`, so at the pin neither platform positions them).
- **#219:** fixed in code since v1.4.0 (PR #227). Close it after the U10 device check confirms pan and pinch hold.

### U5 — Recording coordinates and caption

- **Shape:** `tourItems` takes the full-resolution samples and the kept window. The candidate carries its start in **milliseconds** for the nearest-sample pick, while the window check uses iOS's truncated seconds. Compute only inside `SharePayloadBuilder.build` (on `Dispatchers.Default`), never in the Main-thread `candidatesNow()` path.
- **Nearest:** a linear `minByOrNull` over `abs(p.timestamp - rec.startTimestamp)` in ms, which keeps the first minimum on a tie, as Swift's `min(by:)` does. No time-gap cap, as on iOS. An empty sample list omits both keys.
- **Window:** keep lat/lon only when `keptWindow?.contains(startTs) ?: true` (inclusive, epoch seconds), the same test as the waypoint filter. The recording itself always ships.
- `transcription` stays null. The worker needs no change: it has accepted the keys since worker PR #38.
- The caption is the first line inside the Interactive block, visible only while Interactive is on.
- AE7's wording should read "starts in the trimmed doorstep zone, outside the kept window". The plan's U5 test wording is already correct.

### U6 — Honor events and #223

- Add `HONOR_MODE`/`HONOR_ARRIVAL` with the four `when` sites in section C §5, the wire names both ways, and the `HonorPersistence.ARRIVAL_WAYPOINT_ICON` constant plus its collision test. The waypoint icon needs no converter change; add the round-trip test.
- **Single source is `walk_events`:**
  - `computeMeditationSeconds`, the metrics cache, export, the journey viewer, and prompt meditation contexts all derive sittings through `deriveActivityIntervals`.
  - The derivation normalizes first: sort by `(timestamp, END before START)`, merge overlaps, keep the strict `end > start`. The tie-break lives in the pure function, not the DAO, so imports with unsorted activities are covered too.
- **Clamp everywhere, as iOS reads the clamped `meditateDuration`:** the summary card and the share payload's meditation total go through the same clamped `WalkMetricsMath` value as the cache and export.
- **Import:** "meditation" activities become event pairs in the walk's own transaction; everything else stays in `activity_intervals` as WALKING and re-exports as "unknown".
- **Imported total:** Android recomputes it from events rather than storing `stats.meditateDuration` verbatim. For iOS-made packages the two agree to within rounding. The difference (whole seconds; overlaps merged rather than double-counted) is recorded as a gate row.
- **Migration 8→9** follows section C §6:
  - pick the target walks before the first insert;
  - skip rows where `end ≤ start`;
  - mint uuids;
  - null `meditation_seconds` on every finished walk with meditation events or MEDITATING intervals.

  The entity set is unchanged, so 9's identity hash equals 8's. Hoist one shared migrations array. The schema-replay test helper is `T/data/MigrationTestDatabases.kt`.
- **Filed, outside U6:**
  - **#235**: the seal takes no meditation input.
  - **#236**: export `talkDuration` is always 0.
  - **#237**: iOS `lap`/`marker`/`segment` events degrade to "unknown".
  - **#238**: archive-strip stub walks, and NULL caches backfilling to zero.
  - **#239**: two pause-pairing rules.

### U7 — About, #225, #221

- **About:** port iOS's maps paragraph plus the two rows ("© Mapbox", "© OpenStreetMap contributors", `translatable="false"`) after the weather row, with iOS's URLs.
  - The Mapbox row uses `Icons.Outlined.Map`.
  - The OSM row uses `Icons.Outlined.Route` if the pinned icons artifact has it, otherwise `Icons.Outlined.Timeline`.
  - The existing spacing and row-styling drift is filed as **#240** and left for the gate.
  - iOS's duplicate section-appear index is filed as **pilgrim-ios #97** (cosmetic; Android has no stagger).
- **#225:** branch three ways: never shared, active, returned.
  - The returned block follows `WalkSharingButtons.swift:310-344`, using the existing unused strings plus a new `share_journey_was_shared`.
  - Expiry labels stay lowercase.
  - "Share again" opens the fresh share form.
  - The icon is `Icons.AutoMirrored.Outlined.Undo` inside a 1 dp fog circle border, the closest match to `arrow.uturn.backward.circle`; U10 confirms it visually.
- **#221:** add `SettingsViewModel.onPhotoPermissionResult(granted)`.
  - A denial reverts the setting to off and the note survives the revert; a grant writes nothing. This keeps iOS's stale-callback guard.
  - Android persists ON when the switch flips and reverts on denial, where iOS writes only after a grant. What the user sees is the same; the only gap is a process death during the permission dialog, which the summary's Grant prompt already covers.
  - Correct the comment at `SettingsScreen.kt:176-177`.

### U8 — Audits

- All three verdicts need no fix. Whisper behind the guide is **absent**, discard mid-recording was **already fixed** in Stage 9.5-C, and the teardown race is **absent**. U8 adds only the invariant tests named in section E.
- The plan's line "the orphan sweeper deletes the partial file" is corrected: the recording observer deletes it at once, and the sweeper only removes the emptied directory later.
- **Forward note for U22:** "reply here" must record through `VoiceRecorder`, so this observer covers it, and it must clear its origin on discard.
- **Forward note for U18:** the whisper/guide overlap runs both ways and also happens inside the UI process (tap-to-play, placement, the Seek reveal), not only across processes.

### iOS issues filed from this spec

| Issue | Subject | Android stance |
|---|---|---|
| pilgrim-ios #93 | Workspace and project lockfiles pin different Mapbox versions | none needed |
| pilgrim-ios #94 | Bounds fit re-eases past the inset clamp | matched as shipped |
| pilgrim-ios #95 | Segment taps ease over 2.5 s (`cameraDuration` never reset) | matched as shipped |
| pilgrim-ios #96 | Reveal camera ignores Reduce Motion | matched as shipped (snap removed) |
| pilgrim-ios #97 | AboutView duplicate section-appear index | nothing to match |

Not filed: a stale iOS test comment about the worker's null handling (`UnitTests/SharePayloadTourTests.swift:54-55`). It is comment-only, and iOS's behavior is right.

---

## A. Map camera fits, #219, and the Mapbox engine line

### 1. iOS fit semantics after #81 and #89

**Where the rules came from**

- **iOS PR #81** ("Honor — walk in their steps (slice one)", merge `324614a`) included commit `c6fd6f7`. That commit first added change detection to the bounds fit, but it stored the bounds as applied *before* the attempt:

```swift
                let changed = context.coordinator.lastAppliedBounds != bounds
                    || context.coordinator.lastAppliedBoundsInset != bottomInset
                if changed {
                    context.coordinator.lastAppliedBounds = bounds
                    context.coordinator.lastAppliedBoundsInset = bottomInset
                    do {
                        let camera = try mapView.mapboxMap.camera(
                            for: [bounds.sw, bounds.ne],
                            camera: CameraOptions(),
                            coordinatesPadding: UIEdgeInsets(top: 40, left: 30, bottom: 40 + bottomInset, right: 30),
                            maxZoom: nil,
                            offset: nil
                        )
                        mapView.camera.ease(to: camera, duration: cameraDuration)
                    } catch {
                        print("[PilgrimMapView] camera(for:bounds:) failed: \(error)")
                    }
                }
```
> Pilgrim/Views/PilgrimMapView.swift:244-261@c6fd6f7 (an ancestor of the pin, quoted to show the shape before the fix)

- **iOS PR #88** (merge `6ab3134`) included `5975b98` "the overview card lies over the map, not beside it". From then on, the Honor overview passes its measured card height as the map's `bottomInset`:

```swift
                cameraBounds: rendering?.bounds,
                bottomInset: cardHeight,
```
> Pilgrim/Scenes/Honor/HonorOverviewView.swift:130-131@7c200bf

- **iOS PR #89** ("fix(map): a failed bounds fit is retried, not recorded as done", merge `ef5cad1`, commit `aea2456`). The bug in its PR body: "Build 115 shows a globe instead of the route on every Honor overview — pilgrimage stages and shared walks alike." The cause: the bounds were stored before `camera(for:)` ran. When that call throws ("padding taller than a mid-layout map view … On a short screen — an iPhone SE — a tall stage card makes it refuse every time"), no later pass tries again, and "the map keeps Mapbox's default zero-zoom camera. That camera is the globe." The PR lists three fixes: record the bounds only after the fit lands, skip the fit while the view has no room, and clamp the inset.

**Shipped code at the pin**

```swift
            if let bounds = cameraBounds {
                // Only when the bounds actually change, or the inset moved
                // them: `updateUIView` runs on every published tick behind
                // the map (a gathering percentage, say), and easing again on
                // each one yanks a pan the walker just made back to the fit.
                // A first application always eases — `lastAppliedBounds`
                // starts nil.
                let changed = context.coordinator.lastAppliedBounds != bounds
                    || context.coordinator.lastAppliedBoundsInset != bottomInset
                // Padding taller than the map itself makes `camera(for:)`
                // throw, and a view mid-layout is briefly exactly that. The
                // inset is a card lying over the map, so on a short screen it
                // can ask for more room than there is: leave the route a
                // minimum to be drawn in rather than let the fit fail.
                let roomForRoute: CGFloat = 160
                let headroom = max(0, mapView.bounds.height - roomForRoute - 80)
                let padding = UIEdgeInsets(top: 40, left: 30,
                                           bottom: 40 + min(bottomInset, headroom), right: 30)
                let fits = mapView.bounds.height > padding.top + padding.bottom
                    && mapView.bounds.width > padding.left + padding.right
                if changed && fits {
                    do {
                        let camera = try mapView.mapboxMap.camera(
                            for: [bounds.sw, bounds.ne],
                            camera: CameraOptions(),
                            coordinatesPadding: padding,
                            maxZoom: nil,
                            offset: nil
                        )
                        mapView.camera.ease(to: camera, duration: cameraDuration)
                        // Recorded only once the fit actually landed. Set
                        // before the attempt, a single throw would read as
                        // "already applied" for good and strand the map on
                        // the default zero-zoom camera — the globe.
                        context.coordinator.lastAppliedBounds = bounds
                        context.coordinator.lastAppliedBoundsInset = bottomInset
                    } catch {
                        print("[PilgrimMapView] camera(for:bounds:) failed: \(error)")
                    }
                }
```
> Pilgrim/Views/PilgrimMapView.swift:256-295@7c200bf

```swift
        /// Last camera bounds actually eased to, with the inset they were fit
        /// under. `nil` forces the first application through.
        var lastAppliedBounds: MapCameraBounds?
        var lastAppliedBoundsInset: CGFloat = 0
```
> Pilgrim/Views/PilgrimMapView.swift:659-662@7c200bf

```swift
struct MapCameraBounds: Equatable {
    let sw: CLLocationCoordinate2D
    let ne: CLLocationCoordinate2D

    static func == (lhs: MapCameraBounds, rhs: MapCameraBounds) -> Bool {
        lhs.sw.latitude == rhs.sw.latitude
            && lhs.sw.longitude == rhs.sw.longitude
            && lhs.ne.latitude == rhs.ne.latitude
            && lhs.ne.longitude == rhs.ne.longitude
    }
}
```
> Pilgrim/Models/Walk/MapManagement/PilgrimAnnotation.swift:60-70@7c200bf

```swift
    var cameraBounds: MapCameraBounds?
    var cameraDuration: TimeInterval = 0.4
```
> Pilgrim/Views/PilgrimMapView.swift:45-46@7c200bf

The ease curve comes from the SDK default, because the app never passes `curve:`:

```swift
    public func ease(
        to: CameraOptions,
        duration: TimeInterval,
        curve: UIView.AnimationCurve = .easeOut,
        completion: AnimationCompletion? = nil
    ) -> Cancelable {
```
> mapbox-maps-ios@v11.20.0 Sources/MapboxMaps/Camera/CameraAnimationsManager.swift:61-66 (SDK source, not app code)

**The rules, as shipped**

| # | Rule | iOS at `7c200bf` |
|---|---|---|
| F1 | "Bounds or inset changed" | `lastAppliedBounds != bounds \|\| lastAppliedBoundsInset != bottomInset`. The bounds comparison is exact `==` on all four `Double`s, with no tolerance. The inset comparison uses the **raw, unclamped** `bottomInset` with exact `!=`. There is no `> 0.5` tolerance here, unlike the follow path. The first fit always passes because `lastAppliedBounds` starts `nil` (and the inset record starts at `0`). |
| F2 | Padding | `top: 40, left: 30, bottom: 40 + min(bottomInset, headroom), right: 30`, in points. |
| F3 | Inset clamp | `roomForRoute: CGFloat = 160`; `headroom = max(0, mapView.bounds.height - roomForRoute - 80)`. The `80` is the 40 top plus the 40 base bottom. |
| F4 | No-room skip | `fits = height > top + bottom && width > left + right`, computed with the **clamped** padding against `mapView.bounds` at update time. The fit runs only if `changed && fits`. |
| F5 | When the fit counts as applied | Straight after `camera(for:)` returns without throwing **and** `ease` has been called, in the same pass. It does **not** wait for the animation to finish. A later interrupted ease (a pan, a new ease) does not clear the record. |
| F6 | Failure path | A throw prints and leaves the state untouched. The retry happens on the **next `updateUIView` pass**. There is no timer and no layout or size observer. |
| F7 | Animation | `mapView.camera.ease(to: camera, duration: cameraDuration)`. The default `cameraDuration` is `0.4`. The curve is the SDK default `.easeOut` (UIKit's standard ease-out, cubic-bezier control points 0, 0, 0.58, 1). |
| F8 | Max zoom | `maxZoom: nil`, so no zoom clamp. |
| F9 | Passes that never reach the fit | A colour-scheme reload returns before the fit (`PilgrimMapView.swift:222-229@7c200bf`), and follow mode takes the other branch. Neither clears `lastAppliedBounds`, so the fit runs on a later pass if still needed. |

**The no-room arithmetic.** For a non-negative inset:
- When `H ≥ 240`, `top + bottom ≤ H − 160`. The fit always passes vertically and the route keeps at least 160 pt.
- When `H < 240`, `headroom = 0` and the padding is 40/30/40/30, so the fit passes only when `H > 80`.
- Width passes only when `W > 60`.

In practice the skip fires only for a view at most 80 pt tall or 60 pt wide, which means a view still mid-layout.

**Callers and their durations**

```swift
        cameraCenter = coords.first
        cameraZoom = 16
        cameraDuration = 0.1
        revealPhase = .zoomed

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            cameraDuration = 2.5
            cameraCenter = nil
            cameraBounds = boundsForRoute(coords)
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:426-434@7c200bf

```swift
            onSegmentTapped: { start, end in
                if let bounds = boundsForTimeRange(start: start, end: end) {
                    withAnimation { cameraBounds = bounds }
                }
            },
            onSegmentDeselected: {
                if cachedRouteCoordinates.count > 1 {
                    withAnimation { cameraBounds = boundsForRoute(cachedRouteCoordinates) }
                }
            }
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:623-632@7c200bf

`cameraDuration` is written only at lines 428 and 432. SwiftUI's `withAnimation` does not affect the Mapbox ease. So after the reveal, **segment taps and deselects also ease over `2.5` s**.

```swift
        let latPad = (maxLat - minLat) * 0.15 + 0.001
        let lonPad = (maxLon - minLon) * 0.15 + 0.001
        return MapCameraBounds(
            sw: CLLocationCoordinate2D(latitude: minLat - latPad, longitude: minLon - lonPad),
            ne: CLLocationCoordinate2D(latitude: maxLat + latPad, longitude: maxLon + lonPad)
        )
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:855-860@7c200bf

The input to a summary fit is always the padded rectangle from `boundsForRoute`, never the raw points.

```swift
                PilgrimMapView(
                    isInteractive: revealPhase == .revealed,
                    showsUserLocation: false,
                    ...
                    cameraBounds: cameraBounds,
                    cameraDuration: cameraDuration,
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView+Map.swift:52-62@7c200bf (the `...` stands for elided parameters; the map is `.frame(height: 320)` at line 66, and no `bottomInset` is passed, so it is 0)

The summary does not read `reduceMotion` anywhere in `startRevealSequence` (`WalkSummaryView.swift:418-440@7c200bf`). Its only `reduceMotion` uses are at lines 130 and 696, and neither is a camera call.

---

### 2. Android's camera call sites in `PilgrimMap.kt`

**One fact shared by every site: Android uses the *synchronous* `cameraForCoordinates`.** All three calls use the 5-argument overload, which is `@MapboxDelicateApi` (`PilgrimMap.kt:987-993`, `:1691-1697`, `:1714-1720@38497e63`). The file has no `@OptIn`; the opt-in level is `WARNING`. In the SDK, that overload returns an **empty** `CameraOptions` when the map has no size yet, or on any native error. An error includes padding the view cannot hold, which is Android's equivalent of iOS throwing.

```kotlin
  @MapboxDelicateApi
  override fun cameraForCoordinates(
    coordinates: List<Point>,
    camera: CameraOptions,
    coordinatesPadding: EdgeInsets?,
    maxZoom: Double?,
    offset: ScreenCoordinate?
  ): CameraOptions {
    checkNativeMap("cameraForCoordinates")
    if (!nativeMap.sizeSet) {
      return cameraOptions { }
    }
    return nativeMap.cameraForCoordinates(coordinates, camera, coordinatesPadding, maxZoom, offset).getValueOrElse {
```
> mapbox-maps-android@v11.11.0 maps-sdk/src/main/java/com/mapbox/maps/MapboxMap.kt:844-856 (SDK source)

Every Android site then runs `camera.zoom?.coerceAtMost(MAX_FIT_ZOOM) ?: MAX_FIT_ZOOM` (`PilgrimMap.kt:1001`, `:1698`, `:1721@38497e63`). An empty result therefore becomes `zoom = 17` with **no centre**. The camera jumps to street zoom around wherever it already was. On a fresh map that is 0,0: open ocean at zoom 17. This is Android's version of the iOS globe, and nothing detects it as a failure.

#### 2a. The reveal fit path (`LaunchedEffect` at `PilgrimMap.kt:501-557@38497e63`)

- The effect is keyed on `(mapView, revealPhase, points.firstOrNull(), reduceMotion, zoomTargetBounds)` (line 501).
- `Zoomed`: eases to the first point at `REVEAL_ZOOM = 16.0` over `REVEAL_ZOOM_PLANT_MS = 100L` (lines 506-525, `:1867`; `RevealAnimation.kt:74`).
- `Revealed`: the target is `cameraOptionsForBounds(view, zoomTargetBounds, paddingPx)` or `cameraOptionsForFitBounds(view, points, paddingPx)` (lines 533-538). It eases over `SEGMENT_ZOOM_EASE_MS = 350L` or `REVEAL_CAMERA_EASE_MS = 2_500L` (lines 539-543; `RevealAnimation.kt:55,63`).
- Under reduce-motion it calls `setCamera` instead (lines 544-549).

Which iOS rules it breaks:
- **F1**: there is no bounds/inset comparison. The effect re-eases whenever any key changes, including a `reduceMotion` toggle or a new `MapView` (rotation), even when the bounds are the same. It does not key on the full fit input (`points`), only on the first point.
- **F2**: it uses uniform `FIT_PADDING_DP = 32` on all sides (`:221`, `:1869`), not 40/30/40/30.
- **F4**: there is no room or size check before the sync call.
- **F5/F6**: nothing is recorded, and an empty result is not detected. The effect never runs again for the same keys, so a failed fit is **never retried**. A likely route to failure, not verified on a device: a rotation with `revealPhase` restored as `Revealed` gives the new `MapView` its fit before `sizeSet`, and the result is zoom 17 with no centre.
- **F7**:
  - The 2500 ms reveal duration matches iOS's `2.5`.
  - The curve is Android's default `FastOutSlowInInterpolator` (mapbox-maps-android@v11.11.0 `CameraAnimatorsFactory.kt:526`), not `.easeOut`.
  - Segment taps take 350 ms, but iOS takes 2.5 s (§1). The Android doc comment's "iOS uses 350ms (`WalkSummaryView.swift:954`)" (`RevealAnimation.kt:57-62@38497e63`) is not supported at the pin.
  - The reduce-motion snap does not exist on iOS at the pin. The comment at `PilgrimMap.kt:544-546@38497e63` says iOS bypasses the ease; it does not.
- **F8**: `MAX_FIT_ZOOM = 17.0` (`:1868`); iOS has no clamp.
- **Fit input**: the reveal and deselect paths fit the **raw points** (`cameraOptionsForFitBounds`, `:1708-1723`). iOS fits `boundsForRoute` padded by 15% + 0.001°. The segment path already uses padded bounds: `computeBoundsForTimeRange` in `summary/MapCameraBounds.kt:30-51@38497e63` matches iOS.

#### 2b. The update-lambda fit block and its `didFitBounds` flag (`PilgrimMap.kt:986-1007@38497e63`, flag declared at `:468`)

```kotlin
                if (!followLatest && !didFitBounds && revealPhase == null) {
                    val camera = view.mapboxMap.cameraForCoordinates(
                        mapboxPoints,
                        CameraOptions.Builder().build(),
                        EdgeInsets(paddingPx, paddingPx, paddingPx + bottomInsetPx, paddingPx),
                        null,
                        null,
                    )
                    ...
                    view.mapboxMap.setCamera(clamped)
                    didFitBounds = true
                }
```

Which iOS rules it breaks:
- **F1**: it fits once and never again. A later bounds or inset change never re-fits.
- **F3**: the raw inset goes into the padding with no clamp.
- **F4**: there is no room check.
- **F5**: `didFitBounds = true` is set even when the sync result was empty (and became zoom 17). That is the same shape as the iOS bug #89 fixed; the only difference is that Android's failure signal is an empty result, not a throw.
- **F7**: `setCamera` is instant; iOS eases over `0.4`.
- **F2** and **F8** differ as in 2a.

Can production reach it? It is only reached when `routeSegments` is empty (it sits in the `else if (points.size >= 2)` arm at `:932`), `followLatest` is false, and `revealPhase` is null. **No current caller meets all three.** Active Walk passes `followLatest = true` (`ActiveWalkScreen.kt:752@38497e63`). Summary always passes a non-null `revealPhase` (`WalkSummaryScreen.kt:1018`, `:1055@38497e63`). `PilgrimMap` has only two call sites (`ActiveWalkScreen.kt:750`, `WalkSummaryScreen.kt:1051`). The comment at `PilgrimMap.kt:491-493` still names "Walk Share" as a legacy fit-once caller, which is out of date. The block is dead today, but it becomes live for any future Honor-overview-style caller.

#### 2c. `cameraOptionsForBounds` / `cameraOptionsForFitBounds` (`PilgrimMap.kt:1684-1700`, `:1708-1723@38497e63`)

- Both use uniform `EdgeInsets(paddingPx, paddingPx, paddingPx, paddingPx)`.
- Neither takes an inset parameter.
- Both use the sync overload and clamp at 17.
- On failure they return the zoom-17-no-centre camera and give the caller no signal.

They break **F2**, **F3** (no inset at all), **F4**, **F5** (the caller cannot tell "landed" from "failed"), and **F8**.

#### 2d. The initial centre (`PilgrimMap.kt:1008-1036@38497e63`)

This is not a fit, so none of F1–F8 applies. It differs from iOS's seed:

```swift
        if let seed = initialCamera {
            cameraOptions = CameraOptions(center: seed.center, zoom: seed.zoom)
        } else {
            cameraOptions = CameraOptions()
        }
        let mapView = MBMapView(
            frame: .zero,
            mapInitOptions: MapInitOptions(cameraOptions: cameraOptions, styleURI: styleURI)
        )
```
> Pilgrim/Views/PilgrimMapView.swift:122-130@7c200bf

```swift
    static func forActiveWalk() -> Seed? {
        if let current = cachedCurrentLocation() {
            return Seed(center: current, zoom: 16)
        }
        if let lastEnd = lastWalkEndCoordinate() {
            return Seed(center: lastEnd, zoom: 14)
        }
        return nil
    }
```
> Pilgrim/Models/Walk/MapManagement/MapCameraSeed.swift:29-37@7c200bf

How Android does it:
- `MapInitOptions` is built without a camera (`PilgrimMap.kt:798-801`).
- The seed is written from the update lambda, which returns early until `polylineManager` exists, i.e. after style load (`:807`).
- It uses `setCamera` at `FOLLOW_ZOOM` (16) for **both** seed sources, **plus** bottom-inset padding (`:1027-1033`).
- The seed source is `WalkViewModel.kt:720-732@38497e63`: last-known fix, else the last walk's final sample. The zoom is the same for both.

The differences: the zoom-14 fallback for a last-walk-end seed is missing, and iOS's seed carries no padding. Using `setCamera` rather than an ease is right while a follow state owns the camera (the comment at `:1017-1024` explains why), so keep it.

#### 2e. The follow viewport inset (`PilgrimMap.kt:762-786`, `:1832-1858@38497e63`)

This is at parity with iOS's follow branch:

```swift
        if followsUserLocation {
            let padding = UIEdgeInsets(top: 0, left: 0, bottom: bottomInset, right: 0)
            let insetChanged = abs(context.coordinator.lastBottomInset - bottomInset) > 0.5
            if !context.coordinator.isFollowing || insetChanged {
                context.coordinator.isFollowing = true
                context.coordinator.lastBottomInset = bottomInset
                mapView.viewport.transition(
                    to: mapView.viewport.makeFollowPuckViewportState(
                        options: FollowPuckViewportStateOptions(padding: padding, zoom: Self.followPuckZoom)
                    )
                )
            }
        } else {
            context.coordinator.isFollowing = false
            context.coordinator.lastBottomInset = bottomInset
```
> Pilgrim/Views/PilgrimMapView.swift:240-254@7c200bf

- Android re-enters on `!isFollowing || abs(Δ) > FOLLOW_INSET_EPSILON_DP (0.5f)`, measured in dp (`:1851-1858`).
- The padding is `EdgeInsets(0.0, 0.0, bottomInsetPx, 0.0)` with the **raw** inset (`:1834`).
- The follow path uses no fit rules. **iOS does not clamp follow padding**; the #89 clamp covers bounds fits only. U4's `CameraFitDecision` must not be applied here.
- One small difference: Android waits for `styleLoaded` (`:770`), while iOS transitions on the first `updateUIView` whether or not the style has loaded.
- The iOS citations in these comments point to `@2ee1185`, where this block was at lines 210-224. At the pin it is 240-254.

#### Call-site × rule summary

| Site | F1 change | F2 pad | F3 clamp | F4 room | F5 record after landing | F7 ease | F8 no maxZoom |
|---|---|---|---|---|---|---|---|
| 2a reveal | keyed, not compared | 32 uniform | n/a (no inset) | missing | not recorded, not retried | 2.5 s ok; 350 ms segment; FastOutSlowIn; reduce-motion snap | clamps 17 |
| 2b `didFitBounds` | once only | 32 + raw inset | missing | missing | recorded even on empty result | `setCamera` | clamps 17 |
| 2c helpers | — | 32 uniform | no inset | missing | no failure signal | — | clamps 17 |
| 2d seed | n/a | adds inset (iOS none) | n/a | n/a | n/a | `setCamera` (correct) | zoom 16 always (iOS 16/14) |
| 2e follow | parity | parity | must stay unclamped | n/a | n/a | viewport transition | n/a |

**Delta for Android (U4)**
- One pure `CameraFitDecision` should take:
  - the bounds,
  - the raw inset (dp),
  - the view width and height (dp),
  - the last applied `(bounds, inset)`.
- It should return:
  - skip (unchanged),
  - skip (no room),
  - or fit with padding `top 40, left 30, right 30, bottom 40 + min(inset, max(0, H − 160 − 80))` in dp.
- Compare bounds by exact `==` on the four doubles and the inset by exact `!=` on the **raw** value, as shipped.
- Record `(bounds, inset)` only after a **non-empty** `CameraOptions` (`!camera.isEmpty`) has been computed **and** `easeTo` has been issued. Do not wait for the animator to finish (F5).
- An empty result leaves the state alone. Android then needs something that retries, because the reveal `LaunchedEffect` never runs again. One option is the async `cameraForCoordinates(…, result)`, which waits on `whenMapSizeReady`; that needs a guard against a stale callback. The other is re-evaluating on a map-size change. iOS just relies on the next `updateUIView`.
- Route 2a, 2b and 2c through the decision:
  - ease with `MapAnimationOptions` duration from the caller (`0.4` s default, reveal and segment `2.5` s);
  - ease-out interpolator `PathInterpolatorCompat.create(0f, 0f, 0.58f, 1f)`;
  - fit `boundsForRoute`-padded bounds for the reveal and deselect;
  - drop the zoom clamp (`maxZoom = null`).
- Whether to keep the 350 ms segment ease and the reduce-motion snap needs a user decision (see open questions).
- Leave 2e unclamped. Give 2d a zoom of 14 for the last-walk-end seed and drop its padding if exact seed parity is wanted.

---

### 3. #219: the follow camera fighting pan and pinch

**The issue** (`walktalkmeditate/pilgrim-android#219`) is **still OPEN** on GitHub. It was filed from Phase 19 device QA on 2026-08-17: "zooming out on the active-walk map snaps back to zoom 16 within ~a second … `easeTo(center, FOLLOW_ZOOM=16, 800ms)` on every new GPS sample with zero gesture detection anywhere in the file, and no recenter affordance exists."

**The fix commit.** `8b44b023` "feat(walk): live map follows compass heading via follow-puck viewport" merged in PR #227 and is in tags `v1.4.0` and `v1.5.0`. What it did:
- It removed the per-fix `easeTo`, the `(target, inset)` camera key `lastFollowCameraKey`, `FOLLOW_EASE_MS = 800L`, and the one-point follow branch.
- It added a follow-puck transition that runs once when follow engages and again only when the inset changes: the `LaunchedEffect` at `PilgrimMap.kt:762-786@38497e63`, plus `buildFollowPuckOptions` and `shouldEnterFollowViewport`.
- Follow-up commit `c35a5772` fixed the seed comment (`:1017-1024`).
- It added tests `FollowViewportDecisionTest` (7 cases) and `PilgrimMapFollowViewportTest` (5 builder cases).

**Gestures now turn follow off on Android.** I checked this in SDK source; I did not check it on a device. The viewport plugin goes idle when an animator owned by `GESTURES` starts:

```kotlin
  private val cameraAnimationsLifecycleListener = object : CameraAnimationsLifecycleListener {
    override fun onAnimatorStarting(
      type: CameraAnimatorType,
      animator: ValueAnimator,
      owner: String?
    ) {
      when (owner) {
        VIEWPORT_CAMERA_OWNER -> Unit
        MapAnimationOwnerRegistry.GESTURES -> {
          if (options.transitionsToIdleUponUserInteraction) {
            currentCancelable?.cancel()
            currentCancelable = null
            updateStatus(
              ViewportStatus.Idle,
              ViewportStatusChangeReason.USER_INTERACTION
            )
```
> mapbox-maps-android@v11.11.0 plugin-viewport/src/main/kotlin/com/mapbox/maps/plugin/viewport/ViewportPluginImpl.kt:51-66 (SDK; unchanged at v11.23.1)

Pan (and pinch) write the camera through an animator owned by `GESTURES`:

```kotlin
    cameraAnimationsPlugin.easeTo(
      cameraOptions,
      IMMEDIATE_ANIMATION_OPTIONS
    )
```
```kotlin
    private val IMMEDIATE_ANIMATION_OPTIONS = mapAnimationOptions {
      duration(0)
      owner(MapAnimationOwnerRegistry.GESTURES)
    }
```
> mapbox-maps-android@v11.11.0 plugin-gestures/src/main/java/com/mapbox/maps/plugin/gestures/GesturesPluginImpl.kt:1522-1525, 1853-1856 (SDK)

`transitionsToIdleUponUserInteraction` defaults to `true` (`sdk-base/.../viewport/data/ViewportOptions.kt:46@v11.11.0`), and Android does not override it.

**How iOS turns follow off**

```swift
    /// Defaults to `true`.
    public var transitionsToIdleUponUserInteraction: Bool
```
> mapbox-maps-ios@v11.20.0 Sources/MapboxMaps/Viewport/ViewportOptions.swift:9-10 (SDK)

```swift
    @objc private func handleAnyTouchGesture(_ gestureRecognizer: UIGestureRecognizer) {
        guard options.transitionsToIdleUponUserInteraction else {
            return
        }
        switch gestureRecognizer.state {
        case .began:
            idle(invokingCancelable: true, reason: .userInteraction)
```
> mapbox-maps-ios@v11.20.0 Sources/MapboxMaps/Viewport/ViewportManagerImpl.swift:272-278 (SDK)

The app never calls `idle()` and never turns follow back on after a gesture. `isFollowing` stays `true` after the SDK goes idle, so follow comes back only when the inset moves by more than 0.5 or `followsUserLocation` toggles. Android comments that it "re-engages on the next inset change" (`PilgrimMap.kt:751-758@38497e63`). Neither platform has a recenter button.

**Gesture settings on iOS**

```swift
        mapView.gestures.options.panEnabled = isInteractive
        mapView.gestures.options.pinchEnabled = isInteractive
        mapView.gestures.options.rotateEnabled = false
        mapView.gestures.options.pitchEnabled = false
```
> Pilgrim/Views/PilgrimMapView.swift:145-148@7c200bf (pan and pinch are set again on every update at 219-220)

**Follow-puck values**

| Parameter | iOS | Android |
|---|---|---|
| Zoom | `static let followPuckZoom: CGFloat = 16` (`PilgrimMapView.swift:14@7c200bf`) | `FOLLOW_ZOOM = 16.0` (`PilgrimMap.kt:1860@38497e63`) |
| Pitch | Inherited SDK default `pitch: CGFloat? = 45` (mapbox-maps-ios@v11.20.0 `FollowPuckViewportStateOptions.swift:41-44`; same at v11.23.1) | Explicit `FOLLOW_PITCH = 45.0` (`:1866`) |
| Bearing | Inherited `bearing: … = .heading` (same SDK lines) | `FollowPuckViewportStateBearing.SyncWithLocationPuck` (`:1836`) plus `puckBearing = PuckBearing.HEADING` and `puckBearingEnabled = true` (`:746-747`) |
| Padding | `UIEdgeInsets(top: 0, left: 0, bottom: bottomInset, right: 0)`, raw | `EdgeInsets(0.0, 0.0, bottomInsetPx, 0.0)`, raw (`:1834`) |
| Re-entry | `abs(Δ) > 0.5` pt | `abs(Δ) > 0.5f` dp (`:1855-1858`) |
| Idle trigger | Any touch `.began`, even a tap | Only animators owned by `GESTURES`: pan, pinch, rotate, shove, fling, quick and double-tap zoom |
| Rotate / pitch gestures | Disabled | SDK defaults `rotateEnabled = true`, `pitchEnabled = true` (`sdk-base/.../gestures/generated/GesturesSettings.kt:176,200@v11.11.0`). `PilgrimMap.kt` configures no gesture settings. |

**Delta for Android**
- #219's bug is fixed in code and has shipped since v1.4.0, but the issue is still open. Close it once a device check confirms pan and pinch hold.
- Remaining gesture gaps:
  1. Rotate and pitch gestures are on. On Android a twist or shove turns follow off and tilts or turns the map, which iOS cannot do. Matching iOS means `gestures.rotateEnabled = false` and `gestures.pitchEnabled = false`.
  2. The summary map accepts pan and pinch during the reveal. iOS allows them only when `revealPhase == .revealed`. On Android a pan during the reveal cancels the 2.5 s ease through the gestures plugin's `cancelTransitionsIfRequired`.
  3. On iOS a plain tap (for example on a pin) turns follow off; on Android it does not. This is an SDK difference (see open questions).

---

### 4. Mapbox versions, telemetry, main-process init

**iOS: three places name a version, and the two lockfiles disagree**

```
			repositoryURL = "https://github.com/mapbox/mapbox-maps-ios.git";
			requirement = {
				kind = upToNextMajorVersion;
				minimumVersion = 11.0.0;
			};
```
> Pilgrim.xcodeproj/project.pbxproj:4338-4342@7c200bf

```
      "identity" : "mapbox-maps-ios",
      "kind" : "remoteSourceControl",
      "location" : "https://github.com/mapbox/mapbox-maps-ios.git",
      "state" : {
        "revision" : "87ddb192623ea9a615903faff12901052966b724",
        "version" : "11.23.1"
      }
```
> Pilgrim.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved:23-29@7c200bf (also `mapbox-common-ios` 24.23.1 and `mapbox-core-maps-ios` 11.23.1)

```
      "identity" : "mapbox-maps-ios",
      "kind" : "remoteSourceControl",
      "location" : "https://github.com/mapbox/mapbox-maps-ios.git",
      "state" : {
        "revision" : "5d1fb74dc5e4100b7a2284e15f209654f3b5811b",
        "version" : "11.20.0"
      }
```
> Pilgrim.xcworkspace/xcshareddata/swiftpm/Package.resolved:23-29@7c200bf (also `mapbox-common-ios` 24.20.0 and `mapbox-core-maps-ios` 11.20.0)

- Both lockfiles carry the same `originHash`, but the pins differ.
- The workspace lockfile was committed once (`5b74f47`, 2026-03-24).
- `fbdbbe7` (2026-06-01, "pin Mapbox SPM dependencies") wrote 11.23.1 into the **project-embedded** lockfile only.
- CI and release both build the workspace: `xcodebuild test -workspace Pilgrim.xcworkspace` (`.github/workflows/test.yml:66-67@7c200bf`), and `WORKSPACE="Pilgrim.xcworkspace"` … `-workspace "$WORKSPACE"` (`scripts/release.sh:6,150-151@7c200bf`).

So iOS in practice builds against **11.20.0**. That is an inference from how Xcode resolves a workspace build against the workspace's own lockfile. The in-code comments back it up:

```swift
            // Mapbox 11.20.0's sceneDidActivate handler restarts the display
            // link unconditionally, ignoring displayState — so a map paused
```
> Pilgrim/Views/PilgrimMapView.swift:713-714@7c200bf

```swift
        // 2026-09-14, Honor slice three: .readOnly is what a saved tile
        // region needs — the store is checked first and a covering pack is
        // used. The whole TileStoreUsageMode enum is marked deprecated in
        // the 11.20.0 CoreMaps headers with no replacement named; re-read
        // decision 7 of the slice-three spec before any bump past 11.x.
        MapboxMapsOptions.tileStoreUsageMode = .readOnly
```
> Pilgrim/AppDelegate.swift:44-49@7c200bf

**Android today**
- `mapbox = "11.11.0"` (`gradle/libs.versions.toml:27@38497e63`).
- The artifact is `{ group = "com.mapbox.maps", name = "android", version.ref = "mapbox" }` (`:97`).
- It is applied with `implementation(libs.mapbox.maps.android)` (`app/build.gradle.kts:258@38497e63`).
- U3's target, `android-ndk27:11.23.1`, belongs to the `-ndk27` artifact family, which ships 16 KB page-size builds and has been published since 11.7.0 (SDK `CHANGELOG.md` at v11.23.1, lines 5 and 1516-1517).

**SDK behaviour U4 depends on, from 11.11.0 to 11.23.1** (SDK source):
- The sync `cameraForCoordinates` still returns empty when `!nativeMap.sizeSet` (`MapboxMap.kt:880-891@v11.23.1`). It now reduces the input to a bounding box first (`calculateBoundingBox(coordinates)`) and logs failures at warn level instead of error.
- The async overload still waits on `whenMapSizeReady`.
- The `FollowPuckViewportStateOptions.Builder` defaults are unchanged.
- The viewport still goes idle on `GESTURES` animators.
- `MapAttributionDelegate.telemetry()` still exists.

**Telemetry opt-out on Android**

```kotlin
            if (!telemetryOptedOut) {
                try {
                    view.attribution.getMapAttributionDelegate()
                        .telemetry()
                        .setUserTelemetryRequestState(false)
                    telemetryOptedOut = true
                } catch (_: Exception) {
```
> `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:676-682@38497e63` (inside the `loadStyle` success callback, `:589`; the guard flag is `remember`-scoped at `:487`)

- The opt-out runs once for each `MapView`, only after a style load **succeeds**, and swallows any `Exception`.
- iOS at the pin has **no** opt-out. `git grep` for `telemetry` and `MGLMapboxMetricsEnabled` across `7c200bf` finds no Mapbox hit, and `AppDelegate.swift:44-55@7c200bf` sets only tile-store options.
- iOS PR #92 ("fix(privacy): Mapbox telemetry is off on iOS") is **OPEN and after the pin**. Its body says: "Android has opted out since its map work (`setUserTelemetryRequestState(false)` in `PilgrimMap.kt`)."
- So Android already opts out, and iOS is catching up.

**Mapbox init is gated to the main process**

```kotlin
        if (!isMainProcess()) {
            Log.i(TAG, "onCreate: skipping UI inits in non-main process ${getProcessName()}")
            return
        }
        ...
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
```
> `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt:237-247@38497e63`, where `isMainProcess()` is `getProcessName() == packageName` (`:459`). The comment at `:233-236` says `:tracker` never creates a MapView.

**Delta for Android**
- For U3: bump to `com.mapbox.maps:android-ndk27:11.23.1`. This matches iOS's project-embedded lockfile, **not** the workspace lockfile that iOS CI builds (11.20.0).
- Keep the telemetry opt-out, and re-check `telemetry()` compiles after the bump.
- Nothing to change for main-process gating.

---

### 5. Mapbox attribution and logo on iOS (reference)

```swift
        mapView.ornaments.options.scaleBar.visibility = .hidden
        mapView.ornaments.options.compass.visibility = .hidden
        mapView.ornaments.options.attributionButton.position = .bottomLeading
```
> Pilgrim/Views/PilgrimMapView.swift:150-152@7c200bf

The logo is never configured, so it keeps the SDK defaults. The attribution button's default position is overridden:

```swift
    @_spi(Restricted) public var visibility: OrnamentVisibility = .visible
    ...
        position: OrnamentPosition = .bottomTrailing,
        margins: CGPoint = .init(x: 8.0, y: 8.0),
```
> mapbox-maps-ios@v11.20.0 Sources/MapboxMaps/Ornaments/OrnamentOptions.swift:223, 233-234 (the attribution button)

```swift
    @_spi(Restricted) public var visibility: OrnamentVisibility = .visible
    ...
        position: OrnamentPosition = .bottomLeading,
        margins: CGPoint = .init(x: 8.0, y: 8.0)
```
> mapbox-maps-ios@v11.20.0 Sources/MapboxMaps/Ornaments/OrnamentOptions.swift:261, 268-269 (the logo)

- So on iOS the logo and the (i) button are both at `.bottomLeading` with 8/8 margins. `OrnamentsManager` builds each one's constraints independently (lines 211-231), with no code that offsets one from the other.
- History:
  - `1c9fc1c` (2026-09-14) lifted both ornaments above the sheet by setting `margins` to `8 + bottomInset`.
  - `65285a1` (2026-09-15, part of PR #88) reverted that. The reason given: they "collided with the elevation sparkline and crowded the soundscape button. They go back to the SDK's own placement."
  - At the pin, the walk screen's stats sheet covers both ornaments.
  - Visibility is `@_spi(Restricted)`, meaning Mapbox's terms require both to stay on the map.
- On Android, `PilgrimMap.kt` hides only the scale bar and compass (`:664-665@38497e63`) and never positions the logo or attribution. They sit at SDK defaults: the logo at `Gravity.BOTTOM or Gravity.START` with 4f margins, and the attribution at `Gravity.BOTTOM or Gravity.START` with `marginLeft = 92f`, next to the logo (`sdk-base/.../logo/generated/LogoSettings.kt`, `…/attribution/generated/AttributionSettings.kt@v11.11.0`).

---

### iOS defects found

- **Two iOS lockfiles pin different Mapbox versions.**
  - `Pilgrim.xcworkspace/.../Package.resolved` pins `mapbox-maps-ios` 11.20.0 with common 24.20.0 and core 11.20.0.
  - `Pilgrim.xcodeproj/project.xcworkspace/.../Package.resolved` pins 11.23.1 with common 24.23.1 and core 11.23.1.
  - Both carry the same `originHash`.
  - CI and `release.sh` build the workspace, so `fbdbbe7`'s pin to 11.23.1 does not govern CI or release builds. Anyone reading "iOS is on 11.23.1" (a U3 rationale, say) is reading the lockfile the build does not use.
  - Worth filing upstream. It has no effect on Android parity code.
- **Minor: the bounds-fit change test compares the raw, unclamped inset, and exactly.**
  - The test is `lastAppliedBoundsInset != bottomInset` (`PilgrimMapView.swift:263-264@7c200bf`).
  - Once `bottomInset > headroom`, a card-height change leaves the clamped padding the same but still counts as "changed". The map re-eases to the same fit, pulling back a pan the walker just made. That is exactly what the `c6fd6f7` comment says the check exists to prevent.
  - Sub-point jitter in a `GeometryReader` height triggers it the same way, because there is no tolerance (the follow path has `> 0.5`).
  - Android should match this as shipped: compare the raw inset exactly. File upstream.

### Open questions

- **How U4 retries a skipped or failed fit.** iOS retries on the next `updateUIView`, so it relies on any state change. Android's reveal `LaunchedEffect` never runs again for the same keys. Options: the async `cameraForCoordinates` (which waits for map size, but needs a stale-callback guard), or a map-size-change hook. The choice affects how the "count as applied" rule is written.
- **Units.** iOS's 40/30/160/80 are points. The proposal is dp converted to px at the `EdgeInsets` boundary, as `FIT_PADDING_DP` does today. Confirm.
- **Should Android drop `MAX_FIT_ZOOM = 17.0`** to match iOS's `maxZoom: nil`? With `boundsForRoute` padding (the +0.001° floor), a tiny walk no longer reaches street zoom, which is the reason the clamp was added.
- **Segment-tap ease: 350 ms (Android) or 2.5 s (iOS as shipped).** iOS never resets `cameraDuration` after the reveal. That may be unintended on iOS; if so, file it upstream rather than keep Android's 350 ms. The Android comment citing an iOS 350 ms (`RevealAnimation.kt:57-62`) finds no support at the pin, and `cameraDuration`'s history only shows `952ec01` adding it.
- **Reduce-motion.** Android snaps the reveal camera under reduce-motion (`PilgrimMap.kt:517-518`, `:547-548`); iOS at the pin always eases. Keep this accessibility divergence or match iOS? It needs a user decision.
- **Tap turning off follow.** On iOS any touch `.began` turns follow off, including a pin tap; on Android only gestures that drive the camera do. Accept this as an SDK difference?
- **Rotate and pitch gestures.** Disable them on Android (`rotateEnabled`/`pitchEnabled = false`) to match iOS, and gate summary pan and pinch on `revealPhase == Revealed`? Is either in U4's scope or a separate unit?
- **Close #219** after device verification? The fix has shipped since v1.4.0 (PR #227), but the issue is still open.
- **Seed zoom.** Should Android split the seed zoom, 16 for a last-known fix and 14 for the last walk's end, and drop the seed padding to match `MapCameraSeed`?
- **iOS logo and (i) button overlap?** Both are `.bottomLeading` with the same 8/8 margins and no offset logic. They may overlap. Check an iOS screenshot before copying the position to Android.
- **Telemetry.** iOS as shipped at the pin sends Mapbox telemetry; Android opts out, and iOS #92 (open) converges on Android. Confirm this divergence is accepted under the parity rule, since it is privacy-positive and iOS is converging.
- **Rotation risk in 2a.** Does a device rotation on a revealed Summary actually produce the zoom-17-no-centre camera? The `MapView` may be sized before the effect runs. Needs a device check; U4's no-room and empty-result handling would cover it either way.

---

## B. Recording coordinates and the interactive caption

### 1. iOS payload shape

iOS adds two optional fields, `lat` and `lon`, to each tour recording. The JSON keys are the bare names, with no snake_case mapping:

```swift
    struct Tour: Encodable {
        let recordings: [TourRecording]
        let trimM: Int
        /// The walker's own meditation soundscape (cdn URL). nil when the
        /// walker sits in silence — the page then stays silent too.
        let soundscapeUrl: String?

        enum CodingKeys: String, CodingKey {
            case recordings
            case trimM = "trim_m"
            case soundscapeUrl = "soundscape_url"
        }
    }

    struct TourRecording: Encodable {
        let n: Int
        let startTs: Int
        let endTs: Int
        let duration: Double
        let kind: String
        let transcription: String?
        let wpm: Double?
        let sizeBytes: Int
        /// The route sample nearest the recording's start — nil when the walk
        /// carried no route (e.g. an own-walk Way with GPS off).
        let lat: Double?
        let lon: Double?

        enum CodingKeys: String, CodingKey {
            case n, duration, kind, transcription, wpm, lat, lon
            case startTs = "start_ts"
            case endTs = "end_ts"
            case sizeBytes = "size_bytes"
        }
    }

    var tour: Tour? = nil
```
> Pilgrim/Models/Share/SharePayload.swift:89-125@7c200bf

The wire keys for a recording are `n`, `start_ts`, `end_ts`, `duration`, `kind`, `transcription`, `wpm`, `size_bytes`, `lat`, `lon`. The tour's own keys are `recordings`, `trim_m`, `soundscape_url`.

**How absence is encoded.** `SharePayload` has no hand-written `encode(to:)`; a `git grep 'func encode(to'` over `Pilgrim/Models/Share` and `Pilgrim/Scenes/WalkShare` at the pin finds nothing. So the compiler-generated Encodable writes each `Optional` with `encodeIfPresent`, which drops the key when the value is nil. The payload goes through a plain default `JSONEncoder`:

```swift
        let encoder = JSONEncoder()
        guard let body = try? encoder.encode(payload) else {
            throw ShareError.encodingFailed
        }
```
> Pilgrim/Models/Share/ShareService.swift:52-54@7c200bf

A test pins that the key is omitted, not written as null:

```swift
        XCTAssertEqual(recs[0]["lat"] as? Double, 35.68)
        XCTAssertEqual(recs[0]["lon"] as? Double, -105.94)
        XCTAssertEqual(recs[1]["wpm"] as? Double, nil)
        // A recording from a routeless walk must not ship "lat": null — the
        // worker treats the key's presence, not its value, as a place claim.
        XCTAssertNil(recs[1]["lat"])
        XCTAssertNil(recs[1]["lon"])
```
> UnitTests/SharePayloadTourTests.swift:51-57@7c200bf

History: the fields were added in `3aa38c4` ("feat(share): recordings carry the coordinate they were spoken at", 2026-09-02). The kept-window gate came in `c03c31a` ("fix(share,journal): a trimmed doorstep keeps its voices anonymous", 2026-09-02). Both shipped in iOS PR #81, merged 2026-09-03T22:24:24Z.

What the coordinate is for: the iOS 2.0.0 importer uses it as the moment's exact spot. Without it, iOS falls back to a fraction along the shared route:

```swift
            let at: WayCoordinate?
            if let lat = e.lat, let lon = e.lon {
                at = WayCoordinate(lat: lat, lon: lon)
            } else {
                at = nil
            }
```
> Pilgrim/Models/Honor/WayImporter.swift:139-144@7c200bf

```swift
    private func place(of moment: WayMoment) -> CLLocation {
        if let at = moment.at { return CLLocation(latitude: at.lat, longitude: at.lon) }
        let c = geometry.coordinate(atFrac: moment.frac)
        return CLLocation(latitude: c.latitude, longitude: c.longitude)
    }
```
> Pilgrim/Models/Honor/HonorMomentTracker.swift:141-145@7c200bf

**Android today:** `SharePayload.TourRecording` stops at `sizeBytes`; it has no `lat`/`lon` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/share/SharePayload.kt:123-133@38497e63`). `Tour` already matches iOS (`…/data/share/SharePayload.kt:100-115@38497e63`).

**Delta for Android:** add `val lat: Double? = null` and `val lon: Double? = null` after `sizeBytes` in `SharePayload.TourRecording`. Keep the property names `lat` and `lon` with no `@SerialName`, since the wire keys are bare. Refresh the KDoc pins from `3f9f9e8` to `SharePayload.swift:103-123@7c200bf`.

### 2. iOS coordinate rule

**Which samples, nearest by what, and what "start" means.** iOS computes the coordinate in `TourBuilder.candidates(for:)`. It searches the walk's full-resolution `routeData`, not the downsampled or trimmed share route. Recordings are sorted by `startDate`:

```swift
        let sorted = walk.voiceRecordings.sorted { $0.startDate < $1.startDate }
        // Full-resolution samples, not the map's downsampled route: a voice
        // moment deserves the closest fix the phone actually recorded.
        let samples = walk.routeData
        return sorted.enumerated().compactMap { index, rec in
            guard !rec.fileRelativePath.isEmpty else { return nil }
            let url = docs.appendingPathComponent(rec.fileRelativePath)
            let startTs = Int(rec.startDate.timeIntervalSince1970)
```
> Pilgrim/Models/Share/TourBuilder.swift:49-56@7c200bf

"Nearest" means the smallest absolute time difference between a sample's `timestamp` and the recording's `startDate`. Both are full-precision `Date`s, not truncated seconds:

```swift
            let nearest = samples.min { abs($0.timestamp.timeIntervalSince(rec.startDate)) < abs($1.timestamp.timeIntervalSince(rec.startDate)) }
```
> Pilgrim/Models/Share/TourBuilder.swift:71@7c200bf

```swift
                lat: nearest?.latitude,
                lon: nearest?.longitude
```
> Pilgrim/Models/Share/TourBuilder.swift:85-86@7c200bf

- The search is a linear scan, with no cap on the time gap and no accuracy filter.
- On a tie, Swift's `min(by:)` keeps the earlier element in array order.
- With no samples, `min` returns nil, so both coordinates are nil and both keys are omitted.
- The coordinate is computed for every candidate, including unavailable ones, and rides on the candidate itself (`TourBuilder.swift:18-21`).

**The kept-window check, in `tourItems`.** A recording outside the window still ships; only its coordinate is dropped:

```swift
    static func tourItems(
        candidates: [TourRecordingCandidate],
        trimM: Int,
        soundscapeUrl: String? = nil,
        keptWindow: ClosedRange<Int>? = nil
    ) -> (tour: SharePayload.Tour, files: [URL]) {
        let included = candidates.filter { $0.includeInShare && $0.unavailableReason == nil && $0.fileURL != nil }
        let recordings = included.enumerated().map { index, c in
            // The trim's promise covers everything with a coordinate. A voice
            // spoken in the trimmed doorstep zone still travels — the walker
            // consented to the recording — but it must not carry the fix that
            // names the doorstep, exactly as waypointPayload/photoPayload drop
            // theirs. Audio stays, the place does not.
            let inWindow = keptWindow.map { $0.contains(c.startTs) } ?? true
            return SharePayload.TourRecording(
                n: index + 1,
                startTs: c.startTs,
                endTs: c.endTs,
                duration: c.duration,
                kind: c.effectiveKind.rawValue,
                // Transcripts never leave the device: the page renders none, and
                // a 108-minute walk's transcripts would blow the 2MB POST budget.
                // Deliberate — do not wire c.transcription through.
                transcription: nil,
                wpm: c.wpm,
                sizeBytes: c.sizeBytes,
                lat: inWindow ? c.lat : nil,
                lon: inWindow ? c.lon : nil
            )
        }
```
> Pilgrim/Models/Share/TourBuilder.swift:121-150@7c200bf

The check compares `c.startTs` against the window. `c.startTs` is the recording start truncated to epoch seconds (`Int(rec.startDate.timeIntervalSince1970)`, line 56). `ClosedRange.contains` is inclusive at both ends. The check is not made against the chosen sample's time.

**Where the window comes from.** It is the first and last timestamps of the downsampled route after trimming:

```swift
    private func downsampledRoutePoints() -> [SharePayload.RoutePoint] {
        RouteDownsampler.downsample(walk.routeData.map(routePoint))
    }
```
> Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:466-468@7c200bf

```swift
    private func computeInteractiveRoute() -> (route: [SharePayload.RoutePoint], trimM: Int, keptWindow: ClosedRange<Int>?) {
        let downsampled = downsampledRoutePoints()
        guard interactiveEnabled && trimEnabled else { return (downsampled, 0, nil) }

        // Report the trim by OUTCOME, not intent: RouteTrimmer silently no-ops on a route too short to trim, so trimM/keptWindow must reflect what actually happened — never claim a 150m trim while shipping the full, untrimmed route.
        let trimmed = RouteTrimmer.trim(downsampled, meters: Double(Self.trimMeters))
        let didTrim = trimmed.count < downsampled.count
        let trimM = didTrim ? Self.trimMeters : 0
        // Trim's promise covers everything with a coordinate: waypoints and photo metadata outside the kept route window are excluded too — a doorstep photo must not pin the doorstep trim just hid.
        let keptWindow: ClosedRange<Int>? = (didTrim && trimmed.count >= 2)
            ? trimmed.first!.ts...trimmed.last!.ts
            : nil
        return (trimmed, trimM, keptWindow)
    }
```
> Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:477-490@7c200bf

```swift
    @Published var trimEnabled = true

    static let trimMeters = 150
```
> Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:34-36@7c200bf

When no trim is applied, the window is `nil` and every recording keeps its coordinate. That covers three cases:

- the Trim toggle is off;
- Interactive is off (no tour is sent then anyway);
- the route is too short, so `RouteTrimmer` did nothing. It trims 150 m of walked distance off each end, only when the route has more than 3 points and at least 4 × 150 m, and returns at least 2 points whenever it trims (`guard end > start`):

```swift
        guard meters > 0, route.count > 3 else { return route }
        var cumulative: [Double] = [0]
        for i in 1..<route.count {
            cumulative.append(cumulative[i - 1] + haversineMeters(route[i - 1], route[i]))
        }
        let total = cumulative[route.count - 1]
        guard total >= meters * 4 else { return route }

        var start = 0
        while start < route.count - 1 && cumulative[start] < meters { start += 1 }
        var end = route.count - 1
        while end > 0 && total - cumulative[end] < meters { end -= 1 }
        guard end > start else { return route }
        return Array(route[start...end])
```
> Pilgrim/Models/Share/RouteTrimmer.swift:9-22@7c200bf

So whenever `trim_m == 150`, the window is not nil.

The payload build passes the window through:

```swift
    private func applyInteractiveTourAndPauses(to payload: inout SharePayload, trimM: Int, keptWindow: ClosedRange<Int>?) {
        payload.tour = TourBuilder.tourItems(
            candidates: tourCandidates,
            trimM: trimM,
            soundscapeUrl: TourBuilder.soundscapeUrl(
                selectedId: UserPreferences.selectedSoundscapeId.value,
                manifest: AudioManifestService.shared.manifest
            ),
            keptWindow: keptWindow
        ).tour
```
> Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:442-451@7c200bf

The two orchestration calls that pass no window, `TourBuilder.tourItems(candidates: tourCandidates, trimM: 0)` at `WalkShareViewModel+ShareOrchestration.swift:126` and `:250`, are not POSTed. They only feed the upload file list and the repair identity. `failedMediaItem` reads only `audioRecordings[failure.n - 1].startTs` (`:182`). So no coordinate reaches the wire through that path.

**Pinning tests.** Nearest by time, full resolution: a recording at +125 s against 60 s samples takes the +120 s fix, 42.002.

```swift
        let rec = TempVoiceRecording(uuid: nil, startDate: start.addingTimeInterval(125), endDate: start.addingTimeInterval(160),
                                     duration: 35, fileRelativePath: relativePath, transcription: nil)
        let walk = WalkDataFactory.makeWalk(startDate: start, routeData: route, voiceRecordings: [rec])
        let candidate = TourBuilder.candidates(for: walk).first
        XCTAssertEqual(candidate?.lat ?? 0, 42.002, accuracy: 0.0001)
```
> UnitTests/TourBuilderTests.swift:204-208@7c200bf

Window semantics:

```swift
    func testKeptWindowNullsCoordinatesOutsideIt() {
        let inside = candidate(id: 0, startTs: 500, lat: 42.0, lon: -8.0)
        let before = candidate(id: 1, startTs: 100, lat: 42.0, lon: -8.0)
        let after = candidate(id: 2, startTs: 900, lat: 42.0, lon: -8.0)

        let (tour, _) = TourBuilder.tourItems(candidates: [before, inside, after], trimM: 150, keptWindow: 400...600)
```
> UnitTests/TourBuilderTests.swift:214-219@7c200bf

```swift
    func testKeptWindowBoundsAreInclusive() {
        let (tour, _) = TourBuilder.tourItems(
            candidates: [candidate(id: 0, startTs: 400, lat: 42.0, lon: -8.0), candidate(id: 1, startTs: 600, lat: 42.0, lon: -8.0)],
            trimM: 150,
            keptWindow: 400...600
        )
        XCTAssertEqual(tour.recordings.compactMap(\.lat).count, 2, "recordings exactly at either bound stay inside the window")
    }
```
> UnitTests/TourBuilderTests.swift:226-233@7c200bf

End-to-end through the ViewModel: a doorstep recording loses its coordinate, a midpoint one keeps it, and an untrimmed share keeps all of them. See `testInteractiveKeptWindowStripsTrimmedRecordingCoordinates` and `testUntrimmedShareKeepsEveryRecordingCoordinate` in `UnitTests/WalkShareInteractiveTests.swift:225-254@7c200bf`.

The plan's rule holds against iOS: nearest full-resolution sample by time to the recording's start; key absent, never null, outside the inclusive kept window; no window means every recording carries one.

### 3. Android today

**Route data.**
- The full-resolution route is already in the builder's inputs: `ShareInputs.routePoints: List<LocationPoint>` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/share/SharePayloadBuilder.kt:28@38497e63`).
- It is loaded from every `route_data_samples` row for the walk (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/WalkShareViewModel.kt:1390@38497e63`, mapped at `:1411-1417`, passed at `:1444`).
- Rows come back ordered `ORDER BY timestamp ASC, id ASC` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/RouteDataSampleDao.kt:34-35@38497e63`).
- `LocationPoint.timestamp` is epoch millis (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/LocationPoint.kt:8@38497e63`), and so is `VoiceRecording.startTimestamp` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/VoiceRecording.kt:33@38497e63`).

**Downsampling, trim and kept window, in order.**
1. `downsampleRoute` maps `inputs.routePoints` to epoch-second `RoutePoint`s and calls `RouteDownsampler.downsample` (`…/data/share/SharePayloadBuilder.kt:141-154@38497e63`). The cap is `DOWNSAMPLE_TARGET_POINTS = 200` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ShareConfig.kt:22@38497e63`), using RDP with a stride fallback (`app/src/main/java/org/walktalkmeditate/pilgrim/data/share/RouteDownsampler.kt:18-26@38497e63`).
2. `computeInteractiveRoute(downsampled, options)` trims the downsampled route and sets `keptWindow = if (didTrim && trimmed.size >= 2) trimmed.first().ts..trimmed.last().ts else null` (`…/data/share/SharePayloadBuilder.kt:175-192@38497e63`, window at `:190`). The window is a `LongRange` of epoch seconds, inclusive at both ends (`:111-112`), which matches iOS exactly.
3. `build()` runs that route step first (`:220`), then derives candidates (`:228-237`). It filters waypoints by the window with `interactiveRoute.keptWindow?.contains(wp.timestamp / MILLIS_PER_SECOND) ?: true` (`:334`) and builds the tour last (`:354-358`). The tour call passes only `trimM` and `soundscapeUrl`, not the window or the samples:

```kotlin
TourBuilder.tourItems(candidates = candidates, trimM = trimM, soundscapeUrl = options.soundscapeUrl).tour
```
(`…/data/share/SharePayloadBuilder.kt:355@38497e63`)

So nothing needs reordering. The full-resolution `inputs.routePoints` and `interactiveRoute.keptWindow` are both in scope when the tour is built; they just aren't passed in. The photo export uses the same window (`…/ui/walk/share/WalkShareViewModel.kt:1284-1295@38497e63`).

**TourBuilder.**
- `TourRecordingCandidate` has no `lat`/`lon` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/share/TourBuilder.kt:45-61@38497e63`).
- `candidates(...)` takes no samples (`:150-155`), and it keeps only truncated seconds: `val startTs = rec.startTimestamp / MILLIS_PER_SECOND` (`:159`).
- `tourItems(candidates, trimM, soundscapeUrl)` takes no window and writes no coordinate (`:266-285`).
- The only other `SharePayload.TourRecording` constructors are in `SharePayloadTourTest.kt:86,90`.

**Threading.**
- `TourBuilder.candidates` is also called on every UI emission: `tourCandidates` is `stateIn(viewModelScope, …)` over `candidatesNow()` (`…/ui/walk/share/WalkShareViewModel.kt:277-306@38497e63`), which runs on Main.
- The payload build runs off Main: `withContext(Dispatchers.Default) { SharePayloadBuilder.build(inputs, options, photos = photoMeta) }` (`…/ui/walk/share/WalkShareViewModel.kt:792-794@38497e63`).

**Json.**
- `ShareService` injects the unqualified `Json` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ShareService.kt:44@38497e63`) and encodes with it (`:69`).
- That instance is `NetworkModule.provideJson()`: `Json { ignoreUnknownKeys = true; explicitNulls = false }` (`app/src/main/java/org/walktalkmeditate/pilgrim/di/NetworkModule.kt:64-69@38497e63`). `encodeDefaults` is left at the kotlinx default, `false`.
- So a `Double? = null` property is omitted two ways: as a default value, and as a null.

**Tests.**
- `SharePayloadTourTest` builds its `wireJson` to match `provideJson` (`app/src/test/java/org/walktalkmeditate/pilgrim/data/share/SharePayloadTourTest.kt:42-45@38497e63`).
- Key absence is asserted with `assertFalse(…, recs[1].jsonObject.containsKey("wpm"))` (`:108`) and `tourJson.containsKey("soundscape_url")` (`:133-136`).
- A raw-string guard checks `json.contains("\"transcription\"")` (`:251`).
- The waypoint kept-window test uses `computeInteractiveRoute(...).keptWindow!!` and bound-exact fixtures (`:574-608`); a recording-window test should follow it.
- `TourBuilderTest.candidate(...)` has no coordinate parameters (`app/src/test/java/org/walktalkmeditate/pilgrim/data/share/TourBuilderTest.kt:40-60@38497e63`).
- The share gate requires at least 2 route points (`ShareConfig.ROUTE_MIN_POINTS`, `…/ui/walk/share/WalkShareViewModel.kt:1500@38497e63`). So "a walk with no samples" can only happen at the builder level, which is where the test should live.

**Delta for Android. Exactly what must change:**
1. **`SharePayload.kt`:** add `lat: Double? = null` and `lon: Double? = null` to `TourRecording` (section 1).
2. **Nearest-sample rule:**
   - Search `inputs.routePoints`, the full-resolution list, never `PreparedRoute.downsampled` or the trimmed route.
   - Pick the minimum of `abs(p.timestamp - rec.startTimestamp)` in **milliseconds**. The candidate's truncated `startTs` would lose sub-second precision that iOS keeps.
   - Use a linear `minByOrNull`, which keeps the first minimum like Swift's `min(by:)`. If you use a binary search, it must prefer the earlier sample on a tie.
   - Empty samples give null lat/lon.
3. **Window rule:** `lat`/`lon` stay only when `keptWindow?.contains(candidate.startTs) ?: true`, using truncated seconds and an inclusive `LongRange`, exactly like the waypoint filter at `SharePayloadBuilder.kt:334`. The recording itself always stays.
4. **Where to compute.** Two shapes give identical wire output:
   - **iOS shape:** the candidate carries `lat`/`lon`, `candidates()` gains a `samples: List<LocationPoint> = emptyList()` parameter, and `tourItems()` gains `keptWindow: LongRange? = null`.
   - **Plan shape:** only `tourItems` takes `samples` and `keptWindow`. The candidate then needs its start time in millis, via a new field or a uuid lookup into `inputs.voiceRecordings`.

   Either way, pass the samples only from `SharePayloadBuilder.build` (Dispatchers.Default). `candidatesNow()` runs on Main per emission and the rows never render a coordinate.
5. **`SharePayloadBuilder.build`:** pass `inputs.routePoints` and `interactiveRoute.keptWindow` into tour building at `:355`. The classic path is unchanged: `tour` stays null, and `GOLDEN_CLASSIC_PAYLOAD` stays byte-identical.
6. **Tests:**
   - Ports of `testCandidatesCarryTheRecordingCoordinate` (+125 s picks the +120 s fix), `testKeptWindowNullsCoordinatesOutsideIt`, `testKeptWindowBoundsAreInclusive`, `testNoKeptWindowKeepsEveryCoordinate`, and the two ViewModel-level doorstep tests (`WalkShareInteractiveTests.swift:225-254`).
   - The plan's own cases: a full-resolution sample that the 200-point downsample dropped still wins; no samples leaves both keys absent; the wire JSON never contains `"lat":null` or `"lon":null`, checked with `containsKey` like `:108`.

### 4. Worker contract

The worker's working tree is at `1e29c52` and clean.

**Type.** `lat` and `lon` are optional on the recording and on the encounters in tour.json:

```ts
export interface TourRecording {
  n: number;
  start_ts: number;
  end_ts: number;
  duration: number;
  kind: "spoken" | "ambient";
  transcription?: string;
  wpm?: number;
  size_bytes: number;
  lat?: number;
  lon?: number;
}
```
> src/types.ts:86-97@1e29c52

`TourEncounter`'s `voice` and `ambience` variants carry `lat?: number; lon?: number` (`src/types.ts:114-115@1e29c52`).

**Validation.** Both-or-neither, range-checked, and null counts as absent:

```ts
    // JSON has no "undefined" — a client omitting a coordinate serializes
    // it as null, so null must read as absent, same as undefined.
    const hasLat = r.lat != null, hasLon = r.lon != null;
    if (hasLat !== hasLon) return "recording coordinate needs both lat and lon";
    if (hasLat) {
      if (typeof r.lat !== "number" || !Number.isFinite(r.lat) || r.lat < -90 || r.lat > 90) return "recording latitude out of range";
      if (typeof r.lon !== "number" || !Number.isFinite(r.lon) || r.lon < -180 || r.lon > 180) return "recording longitude out of range";
    }
```
> src/handlers/validate-share.ts:166-173@1e29c52

Tests cover valid, out-of-range, half-given, and `null` handled as absent (`test/validate-share.test.ts:124-146@1e29c52`). By contrast, `wpm` and `transcription` are checked with `!== undefined`, so a literal `null` there returns 400 (`src/handlers/validate-share.ts:157-162@1e29c52`). Android's "worker 400s on literal nulls" rule (`SharePayloadTourTest.kt:36-38`) is true for those fields but not for coordinates.

**Storage.** The coordinate is copied as-is into the tour.json encounter:

```ts
        place: recordingPlaces?.[r.n] ?? undefined,
        transcript: tourTranscript(r.transcription),
        lat: r.lat,
        lon: r.lon,
      });
```
> src/generators/tour.ts:46-50@1e29c52

The ambience branch does the same (`src/generators/tour.ts:58-60@1e29c52`). The manifest is written with `JSON.stringify(tourManifest)` to `walks/${id}/tour.json` (`src/handlers/share.ts:216-220@1e29c52`) and served at `/{id}/tour.json` (`src/index.ts:37@1e29c52`).

`JSON.stringify` drops `undefined` but keeps `null`. So an omitted key stays absent in tour.json, while a literal null would persist as `"lat":null`. iOS decodes that as nil (`let lat: Double?`, `Pilgrim/Models/Honor/TourManifest.swift:31-32@7c200bf`), but omission keeps the manifest clean for Android's future importer too.

The worker's street-place labels do not use the recording coordinate. They snap `start_ts` to the nearest point on the shipped, trimmed route (`src/handlers/share.ts:182-202@1e29c52`).

**Since when:**
- `d638bbb` (2026-09-03 10:43 -0500, "feat(tour): every voice knows where it was spoken, every sitting how long") added the fields, the validation and the copy into tour.json.
- `ad21510` (11:01 -0500 the same day) changed `!== undefined` to `!= null`.
- Both merged in worker PR #38 at 2026-09-03T16:35:45Z.

Before `d638bbb`, the worker neither validated nor copied recording `lat`/`lon`; they were silently dropped. So sending them is safe against both the old and the new worker. Deploys are manual (`npm run deploy`), so the repo can't prove the live date. The requirements doc says tour.json's recording coordinates are "present only on shares created since 2026-09-03" (`docs/brainstorms/2026-09-28-ios-v200-parity-retarget-requirements.md:165`).

**Delta for Android:** none on the worker side. The contract already accepts what U5 will send.

### 5. The caption

iOS: the caption is the **first line inside the Interactive disclosure**. It sits directly under the Interactive toggle, before the recordings list or the no-recordings message, and shows only while Interactive is on:

```swift
        VStack(alignment: .leading, spacing: Constants.UI.Padding.small) {
            ShareSectionLabel(text: "Walk with me")

            Toggle(isOn: $viewModel.interactiveEnabled.animation()) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Interactive")
                        .font(Constants.Typography.body)
                        .foregroundColor(.ink)
                    Text("Viewers walk your route on a living map — your recordings play where you made them, photos appear where you took them. Recordings and full-size photos upload over your connection.")
                        .font(Constants.Typography.caption)
                        .foregroundColor(.fog)
                }
            }
            .tint(.moss)
            .onChange(of: viewModel.interactiveEnabled) { _, on in
                if on { viewModel.prepareInteractive() }
            }

            if viewModel.interactiveEnabled {
                // A non-interactive share carries no tour.json — the
                // "walk it there" promise only applies once Interactive is on.
                Text("Anyone with the link can walk it there.")
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)

                if viewModel.hasRecordings {
                    recordingsList
```
> Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:11-37@7c200bf

```swift
        .animation(.easeInOut(duration: 0.2), value: viewModel.interactiveEnabled)
```
> Pilgrim/Scenes/WalkShare/InteractiveShareSection.swift:81@7c200bf

Design tokens:

```swift
            public static let xs: CGFloat = 4
            public static let small: CGFloat = 8
            public static let normal: CGFloat = 16
```
> Pilgrim/Models/Constants.swift:10-12@7c200bf

```swift
        public static let caption: Font = .custom("Lato-Regular", size: 12)
```
> Pilgrim/Models/Constants.swift:69@7c200bf

- **String:** `"Anyone with the link can walk it there."`, the only occurrence under `Pilgrim/` at the pin.
- **Style:** caption font (Lato Regular, 12), colour `.fog`, no extra padding. The only spacing is the parent VStack's `Constants.UI.Padding.small` (8).
- **When it shows:** only while `interactiveEnabled` is true. It does not depend on recordings, trim, photos or the share being in flight. It fades in with the disclosure's 0.2 s ease-in-out.
- **History:** `91559c0` (2026-09-02) first placed it outside the `if`, so it always showed. `df62d9e` (the same day) moved it inside, with the comment above. The shipped placement is inside.

**Android today:** there is no caption; `grep -i 'walk it there'` over `app/src/main` finds nothing. The existing structure:
- The parent `Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small))` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/InteractiveShareSection.kt:257@38497e63`) holds the label (`:258`), the toggle (`:260-264`) and `if (state.interactiveEnabled) {` (`:266`).
- The first child inside that block is `if (state.rows.isNotEmpty())` (`:267`).
- Other captions use `Text(…, style = pilgrimType.caption, color = pilgrimColors.fog)` (`:278`, `:288-292`). The voices warning adds `Modifier.padding(horizontal = PilgrimSpacing.normal)` (`:280-285`); the new caption should not.
- The tokens already match iOS: `PilgrimSpacing.small = 8.dp` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Tokens.kt:10@38497e63`) and caption at 12 sp (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Type.kt:40@38497e63`).
- The interactive animation is deliberately left out (KDoc at `InteractiveShareSection.kt:239-247`), so the caption appears without a transition, like its siblings.
- Strings follow a `share_interactive_<thing>` naming pattern (`app/src/main/res/values/strings.xml:482-497@38497e63`, e.g. `share_interactive_voices_warning` at `:486`). There is only the one `values/` locale.

**Delta for Android:**
1. Add a string, for example `<string name="share_interactive_walk_it_there">Anyone with the link can walk it there.</string>`, next to `share_interactive_voices_warning`.
2. Add `Text(stringResource(R.string.share_interactive_walk_it_there), style = pilgrimType.caption, color = pilgrimColors.fog)` as the first statement inside `if (state.interactiveEnabled) {`, before `if (state.rows.isNotEmpty())`. No new state field is needed.
3. Add tests in `InteractiveShareSectionTest` in the style of `:81-99`: the caption is displayed with Interactive on, for both empty and non-empty rows, and `assertDoesNotExist()` with Interactive off.

### iOS defects found

No behaviour defects.

1. **Stale test comment (documentation only).** `UnitTests/SharePayloadTourTests.swift:54-55@7c200bf` says "the worker treats the key's presence, not its value, as a place claim". The worker has read null as absent since `ad21510` (`src/handlers/validate-share.ts:166-168@1e29c52`). iOS's behaviour, omitting the key, is still right and preferable, because tour.json would otherwise store `"lat":null`. Only the stated reason is wrong. Filing upstream is optional (comment only). Android's `SharePayloadTourTest.kt:36-38` KDoc over-generalizes "worker 400s on literal nulls" the same way.
2. **Sub-second boundary slack (negligible; Android matches, not filed).** The window test uses the recording start's truncated second (`TourBuilder.swift:134`), but the sample is chosen by full-precision time (`:71`). Take a recording that starts in the last kept second after the last kept fix, or in the first kept second before the first kept fix. It can take the neighbouring fix from the trimmed zone, at most one sampling interval (about 1 s of walking) past the kept end, never near the doorstep 150 m away. Android should use the same second-truncated check for parity.

### Open questions

1. **Where Android computes the coordinate.** iOS computes it on the candidate and filters in `tourItems`. The plan puts both in `tourItems`. The wire output is the same either way. The only hard constraint is to keep the sample scan out of the Main-thread `candidatesNow()` path, so pass samples only from `SharePayloadBuilder.build`. Pick one shape in the spec.
2. **Precision.** Confirm the nearest-sample search uses millisecond timestamps (`VoiceRecording.startTimestamp` against `LocationPoint.timestamp`), not the candidate's truncated `startTs`. Choosing by seconds can differ from iOS when two fixes fall within the same second.
3. **AE7 wording.** "starts inside the doorstep-trim window" should read "starts in the trimmed doorstep zone, outside the kept window". iOS drops the coordinate outside the kept window, not inside it.
4. **Time-gap cap.** iOS has no maximum time gap, so a recording made during a long GPS dropout is pinned to the nearest fix in time, which could be far from where it was spoken. The spec should confirm Android copies this as shipped, with no cap of its own.
5. **Worker deploy.** The commits and PR date (2026-09-03) are verified, but the live deploy date isn't provable from the repo. It rests on the requirements doc's "already live". No U5 work depends on it, since older workers silently drop the keys.
6. **Transcripts stay local.** The worker now copies `r.transcription` into tour.json's `transcript` (`src/generators/tour.ts:47@1e29c52`), but iOS 2.0.0 still sends `transcription: nil` (`TourBuilder.swift:141-144@7c200bf`). Android must keep `transcription = null` when it touches `TourRecording`.
7. **Unrelated worker copy bug (out of scope).** `validate-share.ts:178` still says "total audio duration cannot exceed 45 minutes", while `MAX_AUDIO_TOTAL_SECONDS = 6480` (`:12`). Android never shows this message.

---

## C. Honor events in `.pilgrim`, and the #223 sittings single source

Pins: iOS `v2.0.0` = `7c200bf`; Android working tree `38497e63`.

### 1. iOS event wire names

**iOS**

The event enum has eight cases and is persisted as an `Int` raw value (`honorMode` = 5, `honorArrival` = 6). Any raw value it doesn't recognise becomes `.unknown`:

```swift
    public enum EventType: CustomStringConvertible, CustomDebugStringConvertible, RawRepresentable, ImportableAttributeType, Codable {
        case lap, marker, segment, seekMode, seekArrival, honorMode, honorArrival, unknown

        public init(rawValue: Int) {
            switch rawValue {
            case 0:
                self = .lap
            case 1:
                self = .marker
            case 2:
                self = .segment
            case 3:
                self = .seekMode
            case 4:
                self = .seekArrival
            case 5:
                self = .honorMode
            case 6:
                self = .honorArrival
            default:
                self = .unknown
            }
        }
```
> Pilgrim/Models/Data/DataModels/WalkEvent.swift:29-51@7c200bf

On export, every event goes into `workoutEvents`, with no filtering:

```swift
        let workoutEvents = walk.workoutEvents.map { event in
            PilgrimWorkoutEvent(
                timestamp: event.timestamp,
                type: workoutEventTypeString(event.eventType)
            )
        }
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:81-86@7c200bf

Both directions of the name mapping. An unrecognised name imports as `.unknown`, and `.unknown` exports as `"unknown"`:

```swift
    static func workoutEventTypeString(_ type: WalkEvent.EventType) -> String {
        switch type {
        case .lap: return "lap"
        case .marker: return "marker"
        case .segment: return "segment"
        case .seekMode: return "seekMode"
        case .seekArrival: return "seekArrival"
        case .honorMode: return "honorMode"
        case .honorArrival: return "honorArrival"
        case .unknown: return "unknown"
        }
    }

    static func walkEventType(from string: String) -> WalkEvent.EventType {
        switch string {
        case "lap": return .lap
        case "marker": return .marker
        case "segment": return .segment
        case "seekMode": return .seekMode
        case "seekArrival": return .seekArrival
        case "honorMode": return .honorMode
        case "honorArrival": return .honorArrival
        default: return .unknown
        }
    }
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:493-517@7c200bf

On import, every event is kept, never dropped:

```swift
        let workoutEvents = walk.workoutEvents.map { event in
            TempWalkEvent(
                uuid: UUID(),
                eventType: walkEventType(from: event.type),
                timestamp: event.timestamp
            )
        }
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:467-473@7c200bf

The wire shape is just a timestamp and a type string:

```swift
struct PilgrimWorkoutEvent: Codable {
    let timestamp: Date
    let type: String
}
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageModels.swift:326-329@7c200bf

iOS has **no meditation event type**. Sittings exist only as activity intervals (see §3).

**Android today**

- `WalkEventType` has `PAUSED, RESUMED, MEDITATION_START, MEDITATION_END, WAYPOINT_MARKED, SEEK_MODE, SEEK_ARRIVAL, UNKNOWN`. There is no Honor value (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEventType.kt:12-40@38497e63`).
- Events are persisted by enum name. An unknown stored name reads back as `UNKNOWN` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/Converters.kt:20@38497e63`, `app/src/main/java/org/walktalkmeditate/pilgrim/data/Converters.kt:23-24@38497e63`).
- The export mapping `toPilgrimWorkoutEvent` sends `SEEK_MODE → "seekMode"`, `SEEK_ARRIVAL → "seekArrival"`, `UNKNOWN → "unknown"`. `PAUSED/RESUMED/MEDITATION_START/MEDITATION_END/WAYPOINT_MARKED` map to `null` and are omitted (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:489-505@38497e63`). It is called at `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:149@38497e63`.
- The import mapping `walkEventTypeFromWire` only knows `"seekMode"` and `"seekArrival"`; everything else becomes `UNKNOWN` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:512-516@38497e63`). So an iOS 2.0.0 `"honorMode"`/`"honorArrival"` event lands as `UNKNOWN` and re-exports as `"unknown"`. **The Honor identity is lost on the round trip.**
- Imported workout events are merged with the pause events and sorted with a stable `sortedBy { it.timestamp }` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:334-341@38497e63`).
- The KDoc anchors still cite `@c1745e8` line ranges (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:480-482@38497e63`, `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:507-508@38497e63`).

**Delta for Android**

- Add `HONOR_MODE` and `HONOR_ARRIVAL` to `WalkEventType`, before `UNKNOWN`. They are stored by name as `"HONOR_MODE"`/`"HONOR_ARRIVAL"`, so ordinal position doesn't matter.
- Export: `HONOR_MODE → "honorMode"`, `HONOR_ARRIVAL → "honorArrival"`. Import: `"honorMode" → HONOR_MODE`, `"honorArrival" → HONOR_ARRIVAL`. The `else → UNKNOWN` fallback stays.
- `MEDITATION_*`, `PAUSED/RESUMED` and `WAYPOINT_MARKED` stay out of `workoutEvents`.
- Re-anchor the KDoc citations to `PilgrimPackageConverter.swift:493-517@7c200bf`.
- Test: an unknown future name imports as `UNKNOWN` and re-exports as `"unknown"`. iOS loses a future name the same way, so this matches.
- Pre-existing and out of U6 scope: iOS legacy `"lap"`, `"marker"` and `"segment"` collapse to `UNKNOWN` on Android and re-export as `"unknown"`, while iOS round-trips them (see Open questions).

### 2. iOS reserved waypoint icon

**iOS**

```swift
/// The persistence vocabulary for honor walks, shaped like SeekPersistence:
/// a `.honorMode` event at recording start, and on reaching the end of the
/// Way a `.honorArrival` event plus a waypoint with the reserved icon.
enum HonorPersistence {

    /// Must never collide with WaypointMarkingSheet's presets, "mappin", or
    /// SeekPersistence.arrivalWaypointIcon.
    static let arrivalWaypointIcon = "signpost.right.fill"
```
> Pilgrim/Models/Honor/HonorPersistence.swift:3-10@7c200bf

The icon is matched by string alone:

```swift
    static func isArrivalWaypoint(_ waypoint: WaypointInterface) -> Bool {
        waypoint.icon == arrivalWaypointIcon
    }
```
> Pilgrim/Models/Honor/HonorPersistence.swift:26-28@7c200bf

The label format is `"Walked their way: %@"` (`HonorPersistence.swift:42-44`). Write paths: the mode event is written once at recording start, and arrival writes an event plus a waypoint:

```swift
    func writeHonorMarkerEventIfNeeded() {
        guard mode == .honor, way != nil else { return }
        builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .honorMode, timestamp: Date()))
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:42-45@7c200bf

```swift
    /// The persistence commit happens before any ritual effect, as in Seek.
    private func recordHonorArrival(theirSeconds: Double, yourSeconds: Double) {
        guard let way else { return }
        builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .honorArrival, timestamp: Date()))
        addWaypoint(label: HonorPersistence.arrivalWaypointLabel(wayTitle: way.title),
                    icon: HonorPersistence.arrivalWaypointIcon)
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Honor.swift:247-252@7c200bf

Export and import give the icon **no special treatment**. Every waypoint is a generic `Point` feature that carries `icon` verbatim:

```swift
        for waypoint in waypoints {
            let point = GeoJSONFeature(
                geometry: GeoJSONGeometry(
                    type: "Point",
                    coordinates: .point([waypoint.longitude, waypoint.latitude])
                ),
                properties: GeoJSONProperties(
                    markerType: "waypoint",
                    label: waypoint.label,
                    icon: waypoint.icon,
                    timestamp: waypoint.timestamp
                )
            )
            features.append(point)
        }
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:153-167@7c200bf

```swift
            case "Point":
                if case .point(let coord) = feature.geometry.coordinates {
                    let longitude = coord.count > 0 ? coord[0] : 0
                    let latitude = coord.count > 1 ? coord[1] : 0

                    waypoints.append(TempWaypoint(
                        uuid: UUID(),
                        latitude: latitude,
                        longitude: longitude,
                        label: feature.properties.label ?? "",
                        icon: feature.properties.icon ?? "",
                        timestamp: feature.properties.timestamp ?? walk.startDate
                    ))
                }
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:399-412@7c200bf

The other reserved icon is Seek's `"sun.haze"`:

```swift
    /// Reserved SF symbol for arrival waypoints. Must never collide with the
    /// user-pickable icons in `WaypointMarkingSheet` (presets plus the
    /// custom-note "mappin") — summary grouping tells arrivals apart by
    /// exactly this icon string.
    static let arrivalWaypointIcon = "sun.haze"
```
> Pilgrim/Models/Walk/Seek/SeekPersistence.swift:10-14@7c200bf

The keys a user can pick, which both reserved icons must avoid:

```swift
    static let presets: [WaypointChip] = [
        WaypointChip(label: "Peaceful", icon: "leaf"),
        WaypointChip(label: "Beautiful", icon: "eye"),
        WaypointChip(label: "Grateful", icon: "heart"),
        WaypointChip(label: "Resting", icon: "figure.seated.side"),
        WaypointChip(label: "Inspired", icon: "sparkles"),
        WaypointChip(label: "Arrived", icon: "flag.fill"),
    ]
```
> Pilgrim/Scenes/ActiveWalk/WaypointMarkingSheet.swift:8-15@7c200bf

```swift
                    onMark(trimmed, "mappin")
```
> Pilgrim/Scenes/ActiveWalk/WaypointMarkingSheet.swift:107@7c200bf

Who reads the reserved icon — the counts, the own-walk builder's filter, and the summary map:

```swift
        self.foundPlaceCount = walk.waypoints.filter(SeekPersistence.isArrivalWaypoint).count
        self.honorArrivalCount = walk.waypoints.filter(HonorPersistence.isArrivalWaypoint).count
```
> Pilgrim/Models/Seal/SealInput.swift:46-47@7c200bf

```swift
        let userWaypoints = walk.waypoints
            .filter { !SeekPersistence.isArrivalWaypoint($0) && !HonorPersistence.isArrivalWaypoint($0) }
            .sorted { $0.timestamp < $1.timestamp }
```
> Pilgrim/Models/Honor/OwnWalkWayBuilder.swift:63-65@7c200bf

```swift
    /// Honor arrivals ride the generic waypoint branch below: their reserved
    /// icon is already a signpost, so they draw as the stone symbol the
    /// engine chose without a kind of their own.
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:757-759@7c200bf

**Android today**

- The waypoint icon already round-trips byte for byte. Export writes `icon = waypoint.icon` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:195-208@38497e63`). Import stores `icon = feature.properties.icon` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:391-406@38497e63`).
- One difference: Android import skips a `Point` whose `markerType` isn't `"waypoint"` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:392@38497e63`). iOS doesn't check this, but iOS always writes `"waypoint"`, so there's no practical gap.
- The only reserved icon constant is Seek's `ARRIVAL_WAYPOINT_ICON = "sun.haze"` (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekPersistence.kt:24@38497e63`, matcher at `:30`). No Honor constant exists.
- The display mapper has no `"signpost.right.fill"` case. It falls to `LocationOn` and logs `Log.w` every time (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WaypointMarkingSheet.kt:84-103@38497e63`). The presets match iOS (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WaypointMarkingSheet.kt:69-76@38497e63`).
- Arrival counting is Seek-only (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestones.kt:194-197@38497e63`).

**Delta for Android**

- No converter change is needed for the icon. Add a round-trip test (AE6: an iOS 2.0.0 honor walk re-exports the `signpost.right.fill` waypoint unchanged).
- Add `HonorPersistence.ARRIVAL_WAYPOINT_ICON = "signpost.right.fill"` plus `isArrivalWaypoint(icon)`, and extend the existing collision test so it also rejects the presets, `"mappin"` and `"sun.haze"`.
- Mapping the icon for display and counting Honor arrivals belong to U14 per the plan. U6 may optionally map the icon to silence the per-render warning.

### 3. How iOS exports and imports sittings

**iOS**

There are only two activity types, stored as `Int`:

```swift
    public enum ActivityType: RawRepresentable, ImportableAttributeType, Codable {

        case unknown
        case meditation

        public init(rawValue: Int) {
            switch rawValue {
            case 1:
                self = .meditation
            default:
                self = .unknown
            }
        }

        public var rawValue: Int {
            switch self {
            case .unknown:
                return 0
            case .meditation:
                return 1
            }
        }
    }
```
> Pilgrim/Models/Data/DataModels/ActivityInterval.swift:8-30@7c200bf

Sittings are recorded directly as intervals. The in-progress sitting is closed at snapshot time, so finishing while meditating closes it at walk end:

```swift
    private func finalizeMeditation(endDate: Date = Date()) {
        suggestedMeditationMinutes = nil
        guard let start = meditationStartDate else { return }
        let interval = TempActivityInterval(
            uuid: nil,
            activityType: .meditation,
            startDate: start,
            endDate: endDate
        )
        meditationIntervals.append(interval)
        meditationStartDate = nil
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:502-513@7c200bf

```swift
        builder.registerPreSnapshotFlush { [weak self] in
            guard let self else { return }
            self.finalizeMeditation()
            self.builder.flushActivityIntervals(self.meditationIntervals)
        }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:220-224@7c200bf

The meditation total is summed once when the walk is created and clamped to active duration:

```swift
        let meditationIntervals = activityIntervals.filter { $0.activityType == .meditation }
        let rawMeditateDuration = meditationIntervals.reduce(0) { $0 + $1.startDate.distance(to: $1.endDate) }
        let clampedMeditateDuration = min(rawMeditateDuration, durations.activeDuration)
```
> Pilgrim/Models/Data/NewWalk.swift:40-42@7c200bf

The export writes that stored total into `stats`:

```swift
        let stats = PilgrimStats(
            distance: walk.distance,
            steps: walk.steps,
            activeDuration: walk.activeDuration,
            pauseDuration: walk.pauseDuration,
            ascent: walk.ascend,
            descent: walk.descend,
            burnedEnergy: walk.burnedEnergy,
            talkDuration: walk.talkDuration,
            meditateDuration: walk.meditateDuration
        )
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:22-32@7c200bf

The export `activities` array — only two strings, `"meditation"` and `"unknown"`:

```swift
        let activities = walk.activityIntervals.map { interval in
            PilgrimActivity(
                type: interval.activityType == .meditation ? "meditation" : "unknown",
                startDate: interval.startDate,
                endDate: interval.endDate
            )
        }
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:58-64@7c200bf

```swift
struct PilgrimActivity: Codable {
    let type: String
    let startDate: Date
    let endDate: Date
}
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageModels.swift:261-265@7c200bf

Import turns activities back into intervals. Anything other than `"meditation"` becomes `.unknown`:

```swift
        let activities = walk.activities.map { activity in
            TempActivityInterval(
                uuid: UUID(),
                activityType: activity.type == "meditation" ? .meditation : .unknown,
                startDate: activity.startDate,
                endDate: activity.endDate
            )
        }
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:437-444@7c200bf

The imported total is **taken as-is from `stats`**, not recomputed from `activities`:

```swift
            talkDuration: walk.stats.talkDuration,
            meditateDuration: walk.stats.meditateDuration,
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageConverter.swift:349-350@7c200bf

```swift
        walk._talkDuration .= object.talkDuration
        walk._meditateDuration .= object.meditateDuration
```
> Pilgrim/Models/Data/DataManager.swift:263-264@7c200bf

Archived entries strip events and intervals from existing walks but keep surface stats; a stub walk gets its meditation from the archived stats:

```swift
        for waypoint in walk._waypoints.value { transaction.delete(waypoint) }
        for pause in walk._pauses.value { transaction.delete(pause) }
        for event in walk._workoutEvents.value { transaction.delete(event) }
        for interval in walk._activityIntervals.value { transaction.delete(interval) }
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:461-464@7c200bf

```swift
        stub._meditateDuration .= payload.stats.meditateDuration
```
> Pilgrim/Models/Data/PilgrimPackage/PilgrimPackageImporter.swift:490@7c200bf

**Android today**

- The wire strings already match. Export maps `MEDITATING → "meditation"` and `TALKING/WALKING → "unknown"` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:467-478@38497e63`). Import maps `"meditation" → MEDITATING` and everything else to `WALKING` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:412-421@38497e63`), and writes the rows into `activity_intervals` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:298-305@38497e63`).
- The exported `meditateDuration` is `walk.meditationSeconds ?: computeMeditationSeconds(intervals, …)`, which is the same `min(raw, active)` clamp in whole seconds (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:87-94@38497e63`).
- Import **ignores** `stats.meditateDuration`. `convertToImport` never sets `meditationSeconds`, so it stays `null` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:281-295@38497e63`, default at `app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/Walk.kt:31-32@38497e63`), and the backfill recomputes it (§4).

**Delta for Android**

- The wire strings need no change.
- Import changes: each `"meditation"` activity becomes a `MEDITATION_START`/`MEDITATION_END` event pair instead of a MEDITATING row. Everything else (`"unknown"`) is still written to `activity_intervals` as `WALKING` and re-exported as `"unknown"`.
- Export changes: `activities` = sittings derived from `walk_events` (as `"meditation"`) plus the non-MEDITATING rows from `activity_intervals` (as `"unknown"`). MEDITATING rows left in the table must be filtered out so no sitting is emitted twice.
- iOS stores the imported `stats.meditateDuration` as-is; Android recomputes it (whole seconds, clamped). This is a deliberate single-source divergence (see Open questions).

### 4. Android today (#223)

**iOS reference: one source everywhere.** Every surface reads `activityIntervals` or the stored `meditateDuration`. Examples:

```swift
        self.activityIntervals = walk.activityIntervals.map {
            (type: $0.activityType, startDate: $0.startDate, endDate: $0.endDate)
        }
```
> Pilgrim/Models/Seal/SealInput.swift:42-44@7c200bf

```swift
                talkDuration: walk.talkDuration,
                meditateDuration: walk.meditateDuration,
```
> Pilgrim/Scenes/Home/HomeViewModel.swift:137-138@7c200bf

```swift
        let meditations = walk.activityIntervals
            .filter { $0.activityType == .meditation }
            .sorted { $0.startDate < $1.startDate }
            .map { MeditationContext(startDate: $0.startDate, endDate: $0.endDate, duration: $0.duration) }
```
> Pilgrim/Scenes/Prompts/PromptListView.swift:177-180@7c200bf

```swift
        if let longestMed = allWalks.filter({ $0.meditateDuration > 0 })
            .max(by: { $0.meditateDuration < $1.meditateDuration }),
```
> Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift:188-189@7c200bf

```swift
            SummaryCard(title: "Meditate", value: formatDuration(walk.meditateDuration), icon: "brain.head.profile")
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:605@7c200bf

```swift
            meditateDuration: walk.meditateDuration,
```
> Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:363@7c200bf

**Android writers**

- **Nothing writes `activity_intervals` for native walks.** `WalkRepository.recordActivityInterval` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt:264@38497e63`) has no callers; the only mentions are comments at `WalkSummaryViewModel.kt:1868` and `WalkShareViewModel.kt:1394`. The only insert is the importer (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageImporter.kt:423-425@38497e63`).
- **Native sittings live only in `walk_events`.** The reducer emits `MEDITATION_START` (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkReducer.kt:95-100@38497e63`) and `MEDITATION_END` (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkReducer.kt:147-157@38497e63`). Finishing while meditating writes no END (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkReducer.kt:158-167@38497e63`), so a dangling START is closed at walk end on read. The effect is persisted through `repository.recordEvent` (`app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkControllerImpl.kt:518-524@38497e63`).

**Derivation and cache**

- `computeMeditationSeconds` sums **only MEDITATING `ActivityInterval` rows**, clamped to active duration in whole seconds (`app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsMath.kt:30-41@38497e63`). Active duration is wall clock minus `pauseSpans` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsMath.kt:47-52@38497e63`). `pauseSpans` keeps the first of two consecutive PAUSED events (`app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsMath.kt:71@38497e63`); `replayWalkEventTotals` keeps the last (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEventReplay.kt:40@38497e63`).
- `WalkMetricsCache.computeAndPersist` feeds `activityIntervalsFor(walkId)` into that function and writes both columns with `updateAggregates` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsCache.kt:46-57@38497e63`; SQL at `app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkDao.kt:74-78@38497e63`). It runs at finalize (`app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserver.kt:216-217@38497e63`). **Every native walk therefore caches `meditation_seconds = 0`, not NULL.**
- The backfill fills **only NULLs**: `endTimestamp != null && (distanceMeters == null || meditationSeconds == null)`, one walk per emission (`app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsBackfillCoordinator.kt:63-71@38497e63`). It starts at app launch (`app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt:355@38497e63`). A cached 0 is never recomputed.
- `deriveActivityIntervals` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/ActivityIntervalReplay.kt:42-83@38497e63`):
  - It **does not sort**; it walks `events` in the order given (`:49`), relying on the DAO's `ORDER BY timestamp ASC`, which has no tiebreaker (`app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkEventDao.kt:15@38497e63`). "Events sort by timestamp alone" is confirmed.
  - **The last START wins**: `MEDITATION_START -> pendingStart = event.timestamp` overwrites any open start (`:51`). An END needs `event.timestamp > start`, strictly (`:54`), and an END with no open start is ignored.
  - A trailing START closes at `closeAt` only when `closeAt > start` (`:73-81`).
  - Consequence: back-to-back sittings `[t0,t1]`, `[t1,t2]` whose tie sorts `START t1` before `END t1` collapse to **zero** sittings. Overlapping sittings collapse to the later START.
  - `replayWalkEventTotals` uses the same last-START-wins pairing (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEventReplay.kt:45-49@38497e63`).

**Readers of the dead table**

- Export: `activityIntervals = walkRepository.activityIntervalsFor(walk.id)` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageBuilder.kt:80@38497e63`), then `activities = bundle.activityIntervals.map { … }` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:137@38497e63`). The converter's KDoc claims meditation "already ride[s] as … `activities`" (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:47-51@38497e63`). For native walks that is false: the list is empty.
- Journey viewer: same bundle shape (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/data/JourneyViewerViewModel.kt:86@38497e63`).
- Prompts: `repository.activityIntervalsFor(walkId)` (`app/src/main/java/org/walktalkmeditate/pilgrim/core/prompt/PromptsCoordinator.kt:199@38497e63`) becomes `meditationContexts` (`app/src/main/java/org/walktalkmeditate/pilgrim/core/prompt/PromptsCoordinator.kt:228-236@38497e63`). The issue cited `:146`, which has moved.
- Metrics cache: `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsCache.kt:51@38497e63`.

**Readers already on `walk_events`**

- Summary: the total comes from `replayWalkEventTotals(events, closeAt = walk.endTimestamp)` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt:1787@38497e63`); intervals from `deriveActivityIntervals` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt:1876-1880@38497e63`). Neither is clamped.
- Share: intervals from `deriveActivityIntervals(…, closeAt = endTs)` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/WalkShareViewModel.kt:1399@38497e63`); the total is an unclamped sum of those intervals (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/WalkShareViewModel.kt:1437-1439@38497e63`). iOS sends the clamped `walk.meditateDuration`.
- Live meditation windows during the walk: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt:901@38497e63`.

**Readers of the cached `meditation_seconds` (0 for native walks)**

- Settings practice total (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt:337@38497e63`).
- Goshuin header total (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModel.kt:120@38497e63`).
- Goshuin milestone inputs (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModel.kt:98@38497e63`).
- Journal `activitySumsFor` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt:85-86@38497e63`), consumed at `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModel.kt:231@38497e63` and summed at `:267`, `:281`, `:283`.
- Summary milestones for past walks (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt:2035@38497e63`, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt:2122-2126@38497e63`).
- Export fallback (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:88@38497e63`).

**Importer**

- Activities are inserted as rows (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageImporter.kt:423-425@38497e63`). Events go in one by one, in the stable-sorted list order (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageImporter.kt:419-421@38497e63`).
- A tended file replaces the walk: `walkDao.deleteByUuids` (children cascade), then a re-insert, in one top-level transaction per walk (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageImporter.kt:300-330@38497e63`). The new row has NULL caches, so the backfill recomputes it.
- The archive strip deletes events and intervals (and route, waypoints, voice, photos) but leaves the cached columns alone (`app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageImporter.kt:375-387@38497e63`).
- Despite the KDoc (`:353-356`), no stub walk is created for an archived entry that doesn't exist locally. The `else` branch is missing (`:376-388`); iOS creates one (`PilgrimPackageImporter.swift:415`).

**Which user-visible numbers are wrong today**

| Surface | Native walk (events only) | iOS-imported walk (MEDITATING rows, no events) |
|---|---|---|
| Summary meditation total, pins, segments (`WalkSummaryViewModel.kt:1787`, `:1876`) | correct (unclamped) | **0 / none** |
| Share payload sittings and total (`WalkShareViewModel.kt:1399`, `:1437`) | correct (unclamped) | **0 / none** |
| Settings total, Goshuin total, journal meditate pill and `meditatorCount` | **0** | correct (recomputed, clamped) |
| Goshuin-grid LongestMeditation seal (`GoshuinMilestones.kt:119-125`) | **never awarded** | awarded among imports only |
| Summary reveal milestone (`WalkSummaryViewModel.kt:2122-2126`): current walk = live replay, past walks = cache | **fires LongestMeditation spuriously** on nearly any meditating walk (every past native walk is 0) | the current imported walk counts as 0 |
| Summary "longest meditation" callout (`WalkSummaryCalloutProse.kt:50`, needs a non-zero past max) | **never fires** unless imports exist | — |
| `.pilgrim` export `activities` / `stats.meditateDuration` | **`[]` / 0** | correct |
| Journey viewer | **no sittings** | correct |
| Prompt context `meditations` | **empty** | correct |

Seal *artwork* is not on this list. Android's `toSealSpec` takes no meditation input at all (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealSpec.kt:108-130@38497e63`), while iOS feeds a meditate ratio into the seal (see Open questions).

**Delta for Android**

- **Single source:** `walk_events`.
  - `computeMeditationSeconds` takes the `deriveActivityIntervals` output (with `closeAt = walk.endTimestamp`) instead of table rows.
  - `WalkMetricsCache`, export and `JourneyViewerViewModel` derive sittings the same way, as does `PromptsCoordinator.meditationContexts`, sorted by start as iOS does.
  - `activityIntervalsFor` stays only to carry non-MEDITATING rows to export.
- **Normalise inside the derivation:**
  - sort by `(timestamp, END-before-START)`, so a stable order doesn't depend on SQL tie behaviour;
  - merge overlapping sittings;
  - keep the strict `end > start` rule.
- Consider pointing the summary and share totals at the same clamped value (iOS reads the clamped `walk.meditateDuration`), so the summary card, share, cache and export agree.
- Importer: `"meditation"` activities become event pairs inside the same per-walk transaction. Non-meditation activities stay as `WALKING` rows.

### 5. Exhaustive `when` sites

**iOS**

Every iOS switch over the enum is exhaustive over the same eight cases:

```swift
        case lap, marker, segment, seekMode, seekArrival, honorMode, honorArrival, unknown
```
> Pilgrim/Models/Data/DataModels/WalkEvent.swift:30@7c200bf

**Android today**

Exhaustive `when` over `WalkEventType`, with no `else`. These are compile-enforced and must gain `HONOR_MODE` and `HONOR_ARRIVAL`:

1. `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEventReplay.kt:39@38497e63` (point-marker group at `:51-55`). Add both as `-> Unit`.
2. `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/ActivityIntervalReplay.kt:50@38497e63` (no-op group at `:64-70`). Add both as `-> Unit`.
3. `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:490@38497e63` (`toPilgrimWorkoutEvent`, arms at `:491-499`). Add `"honorMode"` and `"honorArrival"`.

Not compile-enforced, but must change:

4. `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:512@38497e63` (`walkEventTypeFromWire`, a `when` over `String` with `else` at `:515`). Add the two wire names.

These have an `else`, need no change, but should be reviewed:

- `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkMetricsMath.kt:70@38497e63` (else at `:80`)
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/builder/PilgrimPackageConverter.kt:429@38497e63` (`computePauses`, else at `:450`)
- `app/src/main/java/org/walktalkmeditate/pilgrim/walk/UiWalkController.kt:331@38497e63` (bell triggers, else at `:336`)

Other consumers that aren't `when` expressions:

- `Converters.stringToWalkEventType` resolves through `entries`, so new values are covered automatically (`app/src/main/java/org/walktalkmeditate/pilgrim/data/Converters.kt:24@38497e63`). `ConvertersTest` iterates `WalkEventType.entries` (`app/src/test/java/org/walktalkmeditate/pilgrim/data/ConvertersTest.kt:15@38497e63`).
- `walkModeFromEvents` only tests `SEEK_MODE`, so `HONOR_MODE` reads as Wander, as the plan wants (`app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEventReplay.kt:87-88@38497e63`). The prompt practice model routes through it (`app/src/main/java/org/walktalkmeditate/pilgrim/core/prompt/ActivityContext.kt:41-50@38497e63`).
- No test source has a `when` over `WalkEventType`; the `SEEK_ARRIVAL,` matches in tests are list literals.

**Delta for Android**

Update sites 1-4. Sites 1 and 2 treat both values as point markers, exactly like `SEEK_MODE`/`SEEK_ARRIVAL`.

### 6. Room facts for migration 8→9

**iOS**

iOS persists event types as `Int` raw values. Android persists names, so iOS numbering never reaches Room:

```swift
        public var rawValue: Int {
            switch self {
            case .lap:
                return 0
            case .marker:
                return 1
            case .segment:
                return 2
            case .seekMode:
                return 3
            case .seekArrival:
                return 4
            case .honorMode:
                return 5
            case .honorArrival:
                return 6
            case .unknown:
                return -1
            }
        }
```
> Pilgrim/Models/Data/DataModels/WalkEvent.swift:53-72@7c200bf

**Android today**

- `@Database(version = 8, exportSchema = true)`, with one `AutoMigration(1→2)` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabase.kt:38-42@38497e63`).
- Manual migrations are `val MIGRATION_N_M: Migration = object : Migration(N, M) { override fun migrate(db) { db.execSQL(…) } }` in the companion. The latest is `MIGRATION_7_8` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabase.kt:184-189@38497e63`). The house rule is "No manual transaction wrapper here — Room's RoomOpenHelper already wraps `migrate()` in a transaction" (`app/src/main/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabase.kt:122-123@38497e63`).
- Registration is an inline `.addMigrations(MIGRATION_2_3 … MIGRATION_7_8)`. There is no shared array yet (`app/src/main/java/org/walktalkmeditate/pilgrim/di/DatabaseModule.kt:36-43@38497e63`). `enableMultiInstanceInvalidation()` is on because `:tracker` shares the file (`:53`).
- Schema is exported to `$projectDir/schemas` (`app/build.gradle.kts:206@38497e63`). `8.json` has `identityHash` `b0893928e8dcad9a56ac3723f7693056`, plus `setupQueries` that create and populate `room_master_table` (`app/schemas/org.walktalkmeditate.pilgrim.data.PilgrimDatabase/8.json@38497e63`).

Tables and columns from `8.json` that the repair touches:

| Table | Columns | Indices | Enum stored as |
|---|---|---|---|
| `walks` | `id` INTEGER PK AUTOINCREMENT, `uuid` TEXT NOT NULL, `start_timestamp` INTEGER NOT NULL, `end_timestamp` INTEGER (nullable = in progress), `distance_meters` REAL, `meditation_seconds` INTEGER | `index_walks_uuid` UNIQUE, `index_walks_end_timestamp` | — |
| `walk_events` | `id` INTEGER PK AUTOINCREMENT, `uuid` TEXT NOT NULL, `walk_id` INTEGER NOT NULL (FK → `walks.id` ON DELETE CASCADE), `timestamp` INTEGER NOT NULL, `event_type` TEXT NOT NULL | `index_walk_events_uuid` **UNIQUE**, `index_walk_events_walk_id`, `index_walk_events_walk_id_timestamp` | enum **name**, e.g. `'MEDITATION_START'`, `'MEDITATION_END'` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/Converters.kt:20@38497e63`) |
| `activity_intervals` | `id` INTEGER PK AUTOINCREMENT, `uuid` TEXT NOT NULL, `walk_id` INTEGER NOT NULL (FK CASCADE), `start_timestamp` INTEGER NOT NULL, `end_timestamp` INTEGER NOT NULL, `activity_type` TEXT NOT NULL | `index_activity_intervals_uuid` UNIQUE, `…_walk_id`, `…_walk_id_start_timestamp` | enum **name**: `'MEDITATING'`, `'WALKING'`, `'TALKING'` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/Converters.kt:27@38497e63`) |

Entity cross-checks: `app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/WalkEvent.kt:13-34@38497e63`, `app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/ActivityInterval.kt:12-35@38497e63`, `app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/Walk.kt:24-32@38497e63`.

- Kotlin mints `uuid` as `UUID.randomUUID().toString()` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/WalkEvent.kt:28@38497e63`). Nothing parses `walk_events.uuid`; the only `UUID.fromString` in `main` parses a walk uuid (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryGenerator.kt:123@38497e63`).
- The existing migration test builds old versions by hand. It uses no `MigrationTestHelper`, because Robolectric can't load the schema assets (`app/src/test/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabaseMigrationTest.kt:20-29@38497e63`). `openV2Shape()` opens a raw `FrameworkSQLiteOpenHelper` at `user_version 2` holding only a minimal `walks` table (`app/src/test/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabaseMigrationTest.kt:49-76@38497e63`). Each test calls `PilgrimDatabase.MIGRATION_X_Y.migrate(db)` directly and asserts with `PRAGMA table_info/index_list/foreign_key_list` or raw inserts (e.g. `:217-238`).
- **Nothing ever opens the migrated database through `Room.databaseBuilder`**, so Room's identity check is never exercised. Tests exist for 2→3, 3→4, 4→5 and 5→6 only; 6→7 and 7→8 have none (a repo-wide grep finds no test reference). The runner is `RobolectricTestRunner`, `@Config(sdk = [34])` (`:30-31`).

**Delta for Android**

- **Version:** bump to `version = 9`. The migration is data-only, so the entity set and identity hash are unchanged; `9.json` is `8.json` with the version bumped. Hoist one shared `MIGRATIONS` array that `DatabaseModule` and the tests both use.

- **`MIGRATION_8_9` step 1 — insert the missing events:**
  - Target: walks that have `activity_type = 'MEDITATING'` rows and **no** `event_type IN ('MEDITATION_START','MEDITATION_END')`. Insert a `'MEDITATION_START'` at `start_timestamp` and a `'MEDITATION_END'` at `end_timestamp` for each such row. Never read `'WALKING'` or `'TALKING'` rows.
  - Pitfall: work out the target walks **before** the first `INSERT`, using a temp table or a single `INSERT … SELECT … UNION ALL`. A per-statement `NOT EXISTS` would see the START rows it just inserted and skip every END.
  - Skip `end_timestamp <= start_timestamp` rows; the derivation drops them anyway.
  - `uuid` must be unique and non-null. SQL can mint one with `lower(hex(randomblob(16)))`, or with dashes to keep the canonical 36-character form. Alternatively, do the repair in Kotlin inside `migrate()` over a cursor.

- **Step 2 — null the stale caches:**
  `UPDATE walks SET meditation_seconds = NULL WHERE end_timestamp IS NOT NULL AND (EXISTS meditation events OR EXISTS 'MEDITATING' intervals)`.
  - The backfill then recomputes both columns: its predicate is an OR, and `computeAndPersist` rewrites distance too.
  - Archive-stripped walks have neither events nor intervals, so their surviving surface stats aren't touched.

- **Idempotency:** a second run inserts nothing (the target walks now have events) and only re-nulls values the backfill recomputes to the same number.

- **Test helper** (`T/data/MigrationTestDatabases.kt`, per the plan):
  - replay `8.json`'s `createSql` (with `${TABLE_NAME}` substituted), `indices[].createSql` and `setupQueries`, at `user_version = 8`;
  - seed rows: native events-only with a cached 0, intervals-only MEDITATING, WALKING-only, mixed, and an archived stub;
  - open through `Room.databaseBuilder(...).addMigrations(*MIGRATIONS)` so the identity check runs;
  - then assert the events and the NULLed caches.

### iOS defects found

None in this slice. Two iOS behaviours look odd but are intentional, and Android matches them rather than filing them:

- An event name iOS doesn't know imports as `.unknown` and re-exports as `"unknown"` (`PilgrimPackageConverter.swift:493-517`), so an older iOS permanently loses future event names. This is forward-compat by design; Android behaves the same.
- iOS import stores `stats.meditateDuration` as-is (`PilgrimPackageConverter.swift:349-350`, `DataManager.swift:263-264`) and never reconciles it with `activities`. For web-editor (tended) files that only come from iOS's own editor, this is by design.

### Open questions

1. **Imported meditation total.** iOS keeps `stats.meditateDuration` verbatim; Android recomputes from events (whole seconds, clamped, overlaps merged). Should the importer seed `meditation_seconds` from the package stats for strict iOS parity, or keep the single-source recompute? They differ on fractional seconds, on overlapping activities (iOS's `NewWalk` sum double-counts overlaps before clamping; the planned merge doesn't), and on tended files whose stats disagree with their activities.
2. **Summary and share clamp.** iOS's summary card and share payload read the clamped `walk.meditateDuration` (`WalkSummaryView.swift:605`, `WalkShareViewModel.swift:363`). Android's are unclamped replays (`WalkSummaryViewModel.kt:1787`, `WalkShareViewModel.kt:1437-1439`). Should U6 route them through `WalkMetricsMath` so every surface shows the same number?
3. **Two pause-pairing rules.** `pauseSpans` keeps the first PAUSED (`WalkMetricsMath.kt:71`) while `replayWalkEventTotals` keeps the last (`WalkEventReplay.kt:40`). With a duplicate PAUSED, the clamp's active duration and the summary's pause total diverge. Fold this into the normalisation, or leave it?
4. **Archived walks.** The strip keeps the cached columns as the only surviving stats. Any stripped walk whose cache is still NULL (for example, archived before the backfill reached it) will backfill to distance 0 and meditation 0. Separately, Android never creates the archived stub its KDoc promises (`PilgrimPackageImporter.kt:353-356` vs `:376-388`). Both are outside U6; file them?
5. **Legacy event names.** iOS `"lap"`, `"marker"` and `"segment"` degrade to `"unknown"` through an Android round trip, while iOS keeps them. Do we add passthrough values, or accept the loss?
6. **Out of scope, noted.** Android exports `talkDuration` from TALKING intervals, which are always empty, so it's always 0 (`PilgrimPackageConverter.kt:78`). iOS derives it from voice recordings, clamped (`NewWalk.swift:37-38`). And Android's seal takes no meditation input, while iOS computes `meditateRatio = activeDuration > 0 ? input.meditateDuration / activeDuration : 0` (`SealGenerator.swift:38`) and hashes `meditateDuration` into the seal (`SealHashComputer.swift:24`). Should either be tracked as its own parity item?
7. **Tie-break location.** The plan wants END-before-START on ties. Put it in `deriveActivityIntervals` (a pure function, covering the summary, share, live and cache) or in the DAO (`ORDER BY timestamp ASC, id ASC`)? Only the in-code sort also covers imports whose activities arrive unsorted.

---

## D. About credits, #225, and #221

iOS pin: `v2.0.0` = `7c200bf`. Android: `38497e63`. In the pin range (`33b0dbc..7c200bf`), only two iOS commits touch the in-scope files, and both are About attribution: `1c9fc1c` ("credit Mapbox and OSM, and stop the sheet burying them") and `9698b0f` ("drop our prefixes so the attribution rows fit one line"). `WalkSharingButtons.swift`, `ShareService.swift` and `PracticeCard.swift` are unchanged since the last anchor. So #221 and #225 are older parity gaps, not new iOS work.

---

### 1. About credits: "© Mapbox" and "© OpenStreetMap contributors"

#### iOS (7c200bf)

Section order in the About body. Data Sources comes after Open Source and before the motto:

```swift
                openSource
                divider
                dataSources
                divider
                motto
```
> Pilgrim/Scenes/Settings/AboutView.swift:30-34@7c200bf

The whole Data Sources section, in order. **Verified:** the two new credit rows sit between the weather row and the routes paragraph. They are **preceded by a new body paragraph** that the plan's U7 Approach line leaves out.

```swift
    private var dataSources: some View {
        VStack(alignment: .leading, spacing: Constants.UI.Padding.normal - Constants.UI.Padding.xs) {
            Text("DATA SOURCES")
                .font(Constants.Typography.caption)
                .tracking(2)
                .foregroundColor(.stone.opacity(0.6))
                .padding(.top, Constants.UI.Padding.big)

            Text("Weather conditions and temperature recorded with each walk are fetched at the start of the walk from Apple's WeatherKit service.")
                .font(Constants.Typography.body)
                .foregroundColor(.ink)

            linkRow(
                icon: "cloud.sun",
                label: "\u{F8FF} Weather — Legal attribution",
                url: URL(string: "https://weatherkit.apple.com/legal-attribution.html")!
            )

            Text("The maps you walk on are drawn by Mapbox from OpenStreetMap, whose roads and paths are surveyed and kept current by people who walk them.")
                .font(Constants.Typography.body)
                .foregroundColor(.ink)

            linkRow(
                icon: "map",
                label: "© Mapbox",
                url: URL(string: "https://www.mapbox.com/about/maps/")!
            )

            // "© OpenStreetMap contributors" is the credit OSM's terms ask
            // for, word for word — the copyright is the contributors', not a
            // company's. Our own "Map data —" prefix was what wrapped the
            // line, so that went instead.
            linkRow(
                icon: "point.topleft.down.curvedto.point.bottomright.up",
                label: "© OpenStreetMap contributors",
                url: URL(string: "https://www.openstreetmap.org/copyright")!
            )

            Text("The pilgrimage routes named alongside the collective's distance — their lengths, their seasons, and how many people walk them each year — come from the open-pilgrimages dataset, shared under the Open Database License.")
                .font(Constants.Typography.body)
                .foregroundColor(.ink)

            linkRow(
                icon: "signpost.right",
                label: "Pilgrimage routes — open-pilgrimages",
                url: URL(string: "https://github.com/walktalkmeditate/open-pilgrimages")!
            )

            linkRow(
                icon: "doc.text",
                label: "Open Database License (ODbL 1.0)",
                url: URL(string: "https://opendatacommons.org/licenses/odbl/1-0/")!
            )
        }
        .padding(.bottom, Constants.UI.Padding.big)
        .frame(maxWidth: .infinity, alignment: .leading)
        .sectionAppear(index: 4, appeared: appeared, reduceMotion: reduceMotion)
    }
```
> Pilgrim/Scenes/Settings/AboutView.swift:306-363@7c200bf

The ordered Data Sources children are:
1. header "DATA SOURCES"
2. weather paragraph
3. weather row
4. **maps paragraph (new)**
5. **"© Mapbox" row (new)**
6. **"© OpenStreetMap contributors" row (new)**
7. routes paragraph
8. routes row
9. ODbL row

Neither new row has a subtitle. The label is the whole credit. `9698b0f` dropped the earlier prefixes ("Maps — © Mapbox" / "Map data — © OpenStreetMap contributors") so each row fits on one line.

Row styling is the shared `linkRow` helper. Each row opens an in-app Safari sheet and ends in a chevron:

```swift
    private func linkRow(icon: String, label: String, url: URL) -> some View {
        Button {
            safariURL = IdentifiableURL(url: url)
        } label: {
            HStack(spacing: Constants.UI.Padding.small) {
                Image(systemName: icon)
                    .font(.system(size: 14))
                    .frame(width: 24, alignment: .center)
                Text(label)
                    .font(Constants.Typography.body)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12))
                    .opacity(Constants.UI.Opacity.medium)
            }
            .foregroundColor(.stone)
            .padding(.vertical, Constants.UI.Padding.small + Constants.UI.Padding.xs)
        }
    }
```
> Pilgrim/Scenes/Settings/AboutView.swift:284-302@7c200bf

Token values:

```swift
        public enum Padding {
            public static let xs: CGFloat = 4
            public static let small: CGFloat = 8
            public static let normal: CGFloat = 16
            public static let big: CGFloat = 24
            public static let breathingRoom: CGFloat = 64
        }
```
> Pilgrim/Models/Constants.swift:9-15@7c200bf

```swift
        public enum Opacity {
            public static let subtle: Double = 0.06
            public static let light: Double = 0.12
            public static let medium: Double = 0.3
        }
```
> Pilgrim/Models/Constants.swift:29-33@7c200bf

```swift
        public static let body: Font = .custom("CormorantGaramond-Regular", size: 17)
        public static let button: Font = .custom("Lato-Bold", size: 17)
        public static let caption: Font = .custom("Lato-Regular", size: 12)
        public static let annotation: Font = .custom("CormorantGaramond-Regular", size: 11)
        public static let micro: Font = .custom("Lato-Regular", size: 9)
```
> Pilgrim/Models/Constants.swift:67-71@7c200bf

So a row is: stone foreground; a 14pt SF glyph in a 24pt-wide slot; the label in Cormorant 17; a trailing `chevron.right` at 12pt and 0.3 opacity; 8pt HStack spacing; 12pt vertical padding. Children of the section's VStack are spaced 12pt apart.

None of these strings are keyed. `Base.lproj/Localizable.strings` at the pin has no entry for any of them, so the literals above are the shipped copy.

#### Android today (38497e63)

- Section order matches iOS: `OpenSourceSection()` → `SectionDivider()` → `DataSourcesSection()` → `SectionDivider()` → `MottoSection()` at `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt:127@38497e63` through `:131`.
- `DataSourcesSection()` (`AboutScreen.kt:339@38497e63` through `:401`) has the header, the weather paragraph, the weather row (Open-Meteo, a deliberate substitute for WeatherKit, `:360-370`), then `Spacer(8.dp)` (`:371`), the routes paragraph (`:372-376`), `Spacer(8.dp)` (`:377`), the routes row (`:378-388`) and the ODbL row (`:389-399`). **There is no maps paragraph, no Mapbox row and no OSM row.**
- The existing credit-with-link pattern is `OpenSourceLinkRow(icon, label, external = false, onClick = { CustomTabs.launch(context, Uri.parse("…")) })`, for example the weather row at `AboutScreen.kt:360@38497e63`. `external = false` gives the `KeyboardArrowRight` chevron, which is the iOS `chevron.right` analogue. `CustomTabs.launch` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/util/CustomTabs.kt:18@38497e63`) plays the role of iOS's in-app `SafariView` sheet.
- Row composable: `OpenSourceLinkRow` at `AboutScreen.kt:452@38497e63` through `:490`. It uses stone tint, `pilgrimType.body`, `spacedBy(8.dp)` and `padding(vertical = 12.dp)`, matching iOS. Two pre-existing drifts: the leading icon renders at `size(24.dp)` (`:471`) where iOS draws a 14pt glyph in a 24pt slot, and the chevron alpha is `0.5f` (`:486`) where iOS uses 0.3.
- Strings: `about_data_sources_*` at `app/src/main/res/values/strings.xml:1131@38497e63` through `:1136`. There are no Mapbox or OSM strings. The only Mapbox/OSM credit anywhere in the repo is the Play listing text: `app/src/main/play/listings/en-US/full-description.txt:35@38497e63` ("© Mapbox, © OpenStreetMap.").
- The iOS maps paragraph is accurate for Android as written. Android renders Mapbox-hosted styles (`val styleUri = if (darkMode) Style.DARK else Style.LIGHT`, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt:204@38497e63`), and those are drawn from OSM data.
- Tests: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/about/` holds only `AboutSeasonHelpersTest.kt` and `AboutViewModelTest.kt`. There is no Compose test of the About sections.

#### Delta for Android

1. **Strings** (`strings.xml`, next to `:1131-1136`):
   - `about_data_sources_maps_body` = `The maps you walk on are drawn by Mapbox from OpenStreetMap, whose roads and paths are surveyed and kept current by people who walk them.`
   - `about_data_sources_mapbox_link` = `© Mapbox`. Suggest `translatable="false"`, following the precedent of `share_journey_footer_url` at `strings.xml:540@38497e63`.
   - `about_data_sources_osm_link` = `© OpenStreetMap contributors`. Keep it word for word. The iOS comment at `AboutView.swift:334-337` records that OSM's terms require exactly this credit. Suggest `translatable="false"`.
2. **`DataSourcesSection()`**: insert right after the weather row (after `AboutScreen.kt:370`, before the existing `Spacer` at `:371`), following the section's own spacing pattern:
   - `Spacer(8.dp)`
   - maps body `Text` (`pilgrimType.body`, `pilgrimColors.ink`)
   - `Spacer(8.dp)`
   - `OpenSourceLinkRow(icon = <map glyph>, label = …mapbox_link, external = false, onClick = { CustomTabs.launch(context, Uri.parse("https://www.mapbox.com/about/maps/")) })`
   - `OpenSourceLinkRow(icon = <route glyph>, label = …osm_link, external = false, onClick = { CustomTabs.launch(context, Uri.parse("https://www.openstreetmap.org/copyright")) })`

   The routes paragraph and rows that follow stay as they are.
3. **Icons**: iOS uses `map` and `point.topleft.down.curvedto.point.bottomright.up`. `material-icons-extended` is already a dependency (`gradle/libs.versions.toml:73`), so `Icons.Outlined.Map` is the direct match. For OSM, see Open questions.
4. **Test**: add a Robolectric Compose test. The simplest route is to make `DataSourcesSection` `internal` and render it directly, since `AboutScreen` needs `hiltViewModel()` (`AboutScreen.kt:78-81`). Assert:
   - the exact texts "© Mapbox" and "© OpenStreetMap contributors" exist;
   - they come after the Open-Meteo row and before "Pilgrimage routes — open-pilgrimages", by comparing node `positionInRoot().y`;
   - tapping each row launches its URL. `CustomTabsTest.kt` (`app/src/test/java/org/walktalkmeditate/pilgrim/ui/util/CustomTabsTest.kt`) is the pattern for capturing the launched intent.

---

### 2. #225: expired share, "This walk has returned to the trail" + "Share again"

#### iOS (7c200bf)

The summary's share card is `WalkSharingButtons`:

```swift
    @ViewBuilder
    private var shareCard: some View {
        WalkSharingButtons(walk: walk, pinnedPhotos: photoCandidates.filter(\.isPinned), onShare: markSharedAndReveal)
```
> Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:676-678@7c200bf

It has three states:

```swift
    @ViewBuilder
    private var journeySection: some View {
        if let cached = cachedShare {
            if cached.isExpired {
                returnedSection(cached)
            } else {
                activeShareSection(cached)
            }
        } else {
            neverSharedSection
        }
    }

    private var neverSharedSection: some View {
        VStack(spacing: Constants.UI.Padding.xs) {
            Button {
                showJourneySheet = true
            } label: {
                HStack(spacing: Constants.UI.Padding.small) {
                    Image(systemName: "square.and.arrow.up")
                        .font(Constants.Typography.body)
                    Text("Share Journey")
                        .font(Constants.Typography.button)
                }
                .foregroundColor(.stone)
            }

            Text("Create a web page")
                .font(Constants.Typography.micro)
                .foregroundColor(.fog)

            Text("walk.pilgrimapp.org")
                .font(Constants.Typography.micro)
                .foregroundColor(.fog)
                .tracking(1.0)
        }
    }
```
> Pilgrim/Views/WalkSharingButtons.swift:148-184@7c200bf

The expired ("returned") layout, with its full copy and the fallback second line:

```swift
    private func labelForOption(_ option: String?) -> String? {
        switch option {
        case "moon": return "1 moon"
        case "season": return "1 season"
        case "cycle": return "1 cycle"
        default: return nil
        }
    }

    // MARK: - Returned to Trail

    private func returnedSection(_ cached: ShareService.CachedShare) -> some View {
        VStack(spacing: Constants.UI.Padding.xs) {
            Image(systemName: "arrow.uturn.backward.circle")
                .font(.system(size: 24))
                .foregroundColor(.fog)

            Text("This walk has returned to the trail")
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
                .italic()

            if let label = labelForOption(cached.expiryOption) {
                Text("Shared for \(label)")
                    .font(Constants.Typography.micro)
                    .foregroundColor(.fog)
            } else {
                Text("This walk was shared")
                    .font(Constants.Typography.micro)
                    .foregroundColor(.fog)
            }

            Rectangle()
                .fill(Color.fog.opacity(0.15))
                .frame(height: 0.5)
                .padding(.horizontal, Constants.UI.Padding.big)

            Button {
                showJourneySheet = true
            } label: {
                Text("Share again")
                    .font(Constants.Typography.caption)
                    .foregroundColor(.stone)
            }
        }
    }
```
> Pilgrim/Views/WalkSharingButtons.swift:299-344@7c200bf

The returned block, top to bottom:
1. `arrow.uturn.backward.circle` at 24pt, fog
2. "This walk has returned to the trail" (caption 12, fog, italic)
3. either "Shared for 1 moon|1 season|1 cycle" (micro 9, fog, label in lowercase) or, with no option, "This walk was shared"
4. a 0.5pt fog/0.15 rule, inset 24 on each side
5. "Share again" (caption 12, stone)

The VStack spacing is 4. There is no kanji watermark and no URL. The block sits under the card's image-share row and divider, exactly where the other two states go.

**"Share again" action:** `showJourneySheet = true`. That is the same sheet as "Share Journey", and dismissing it bumps `shareVersion` and fires `onShare` (`markSharedAndReveal`):

```swift
            .id(shareVersion)
            .sheet(isPresented: $showJourneySheet, onDismiss: {
                shareVersion += 1
                onShare?()
            }) {
                WalkShareView(walk: walk, pinnedPhotos: pinnedPhotos)
            }
```
> Pilgrim/Views/WalkSharingButtons.swift:46-52@7c200bf

Because the cache is expired, the sheet opens on a fresh share form. Its VM treats an expired cache as "no share":

```swift
    var hasExistingShare: Bool {
        guard let uuid = walk.uuid else { return false }
        guard let cached = ShareService.cachedShare(for: uuid) else { return false }
        return !cached.isExpired
    }
```
> Pilgrim/Scenes/WalkShare/WalkShareViewModel.swift:150-154@7c200bf

**How expiry is decided:** the stored `expiry` date is compared with the wall clock using `<=`, at the moment the body is evaluated. Nothing ticks while the screen is open. The value is re-read on each body pass, and `.id(shareVersion)` forces one after the sheet closes.

```swift
    struct CachedShare {
        let url: String
        let id: String
        let expiry: Date
        let shareDate: Date?
        let expiryOption: String?
        var isExpired: Bool { expiry <= Date() }
    }
```
> Pilgrim/Models/Share/ShareService.swift:35-42@7c200bf

```swift
    private var cachedShare: ShareService.CachedShare? {
        guard let uuid = walk.uuid else { return nil }
        return ShareService.cachedShare(for: uuid)
    }
```
> Pilgrim/Views/WalkSharingButtons.swift:25-28@7c200bf

Expiry is set at share time as today plus N days:

```swift
    static func cacheShare(_ result: ShareResult, walkID: UUID, expiryDays: Int, expiryOption: String?) {
        let now = Date()
        let expiry = Calendar.current.date(byAdding: .day, value: expiryDays, to: now) ?? now
```
> Pilgrim/Models/Share/ShareService.swift:119-121@7c200bf

iOS never deletes a `share:<uuid>` entry. The only write is the one below, and a `git grep '"share:'` at the pin finds no removal. So an expired record stays, and the returned block keeps rendering indefinitely.

```swift
        UserDefaults.standard.set(dict, forKey: "share:\(walkID.uuidString)")
```
> Pilgrim/Models/Share/ShareService.swift:131@7c200bf

#### Android today (38497e63)

- **This is where the collapse happens:**
  `val activeCachedShare = cachedShare?.takeIf { !it.isExpiredAt() }` at `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt:191@38497e63`. The comment at `:186-189` says outright that "a null cache OR an expired one both fall back to the plain button". It is passed on as `activeCachedShare = activeCachedShare` at `WalkSummaryScreen.kt:905@38497e63`.
- `WalkSharingButtons` then branches only two ways: `if (activeCachedShare != null) WalkSharingBlock(…) else JourneyFooter(…)` at `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSharingButtons.kt:112@38497e63` through `:126`. Its KDoc records the scope-out deliberately (`WalkSharingButtons.kt:49-58@38497e63`): "Android does NOT port Swift's separate "returned to the trail" expired-state layout … an expired cached share is treated the same as never-shared."
- Model: `CachedShare.isExpiredAt(nowEpochMs: Long = Instant.now().toEpochMilli()) = expiryEpochMs <= nowEpochMs` at `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/CachedShare.kt:23@38497e63`. This matches iOS `<=` on the wall clock exactly.
- Expired records persist, as on iOS. `CachedShareStore.clear` (`app/src/main/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStore.kt:93@38497e63`) has **no production callers**, and `observe` (`:48`) emits the record whether or not it has expired. So nothing upstream needs to change for the returned block to appear.
- `ExpiryOption.label` values are already the iOS lowercase labels (`"1 moon"`, `"1 season"`, `"1 cycle"`) at `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/ExpiryOption.kt:16@38497e63` through `:18`.
- **The copy already exists but nothing uses it.** Stage 8-A (#45, `a28cf4d6`) added these and nothing references them today:
  - `share_journey_returned` = "This walk has returned to the trail" (`app/src/main/res/values/strings.xml:439@38497e63`)
  - `share_journey_shared_for` = "Shared for %1$s" (`strings.xml:440`)
  - `share_journey_share_again` = "Share again" (`strings.xml:441`)

  **Missing:** "This walk was shared", the fallback when `expiryOption` is null.
- The share modal already treats an expired cache as unshared (`val activeShare = cached?.takeIf { !it.isExpiredAt() }`, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/share/WalkShareScreen.kt:148@38497e63`). So wiring "Share again" to the existing `onWalkJourneyShare` (`WalkSummaryScreen.kt:901-904@38497e63`: `onShareJourney()` + `viewModel.markCurrentWalkShared()`) opens the fresh form, as on iOS.
- Typography tokens: `pilgrimType.caption` is 12sp and `pilgrimType.micro` is 9sp (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Type.kt:40@38497e63`, `:42`). They correspond to iOS caption and micro.
- Tests:
  - `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSharingButtonsTest.kt:168@38497e63` through `:198` (`expiredCachedShare_fallsBackToPlainButton`) **pins the collapse** and must be rewritten.
  - `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSharingBlockLogicTest.kt:17@38497e63` through `:25` is pure logic only (watermark formula, kanji mapping, copy-toast generation). It covers none of the branching.

#### Delta for Android

1. **Stop filtering at the call site.** At `WalkSummaryScreen.kt:190-191`, pass the unfiltered `cachedShare` into `WalkSharingButtons` and rewrite the comment at `:186-189`.
2. **Branch three ways in `WalkSharingButtons`.** Replace `activeCachedShare: CachedShare?` (`WalkSharingButtons.kt:68`) with `cachedShare: CachedShare?` plus `nowEpochMs: Long = Instant.now().toEpochMilli()`. That default is the same injectable-clock pattern `WalkSharingBlock` already uses at `WalkSharingBlock.kt:97`. The branch becomes:
   - `null` → `JourneyFooter`
   - `isExpiredAt(nowEpochMs)` → new returned block
   - otherwise → `WalkSharingBlock`

   Update the KDoc at `:49-58`.
3. **New composable, `WalkSharingReturnedBlock(cachedShare, onShareAgain)`**, in `WalkSharingBlock.kt` or beside `JourneyFooter`. Mapped from `WalkSharingButtons.swift:310-344`:
   - `Column(horizontalAlignment = CenterHorizontally, verticalArrangement = spacedBy(PilgrimSpacing.xs))`, test tag `share-returned-block`
   - `Icon` at 24dp, tint `pilgrimColors.fog`, `contentDescription = null`
   - `Text(share_journey_returned)`: `pilgrimType.caption`, fog, `FontStyle.Italic`
   - `Text(stringResource(share_journey_shared_for, option.label))` when `expiryOption != null`, else `Text(share_journey_was_shared)`: `pilgrimType.micro`, fog. **Use `label` as is (lowercase).** Do not reuse the `uppercase(Locale.ROOT)` from `WalkSharingBlock.kt:141`.
   - `HorizontalDivider(color = fog.copy(alpha = 0.15f), thickness = 0.5.dp, modifier = padding(horizontal = PilgrimSpacing.big))`. This is the same shape as `WalkSharingButtons.kt:107-111`.
   - "Share again" `Text(share_journey_share_again)`: `pilgrimType.caption`, stone, clickable → `onWalkJourneyShare`, test tag `share-returned-share-again`
4. **String**: add `share_journey_was_shared` = `This walk was shared`.
5. **Tests**:
   - Rewrite `WalkSharingButtonsTest.kt:168-198`. An expired share with `ExpiryOption.Moon` shows "This walk has returned to the trail", "Shared for 1 moon" and "Share again", and shows neither `share-active-block` nor `share-button-walk-journey`.
   - Add: an expired share with a null option shows "This walk was shared".
   - Add: with `expiryEpochMs == nowEpochMs`, the returned block renders (the `<=` boundary).
   - Add: tapping Share again invokes `onWalkJourneyShare`.
   - Keep the existing active and null cases (`:128-166`).
   - If the implementer pulls the three-way decision out as a pure function, its boundary cases belong in `WalkSharingBlockLogicTest.kt`, the file the plan names. Otherwise the Robolectric `WalkSharingButtonsTest.kt` is where the coverage lives.

Adjacent drift in the same file, not required for #225: `JourneyFooter` renders iOS's `micro` lines ("Create a web page" / "walk.pilgrimapp.org", `WalkSharingButtons.swift:175-182`) with `pilgrimType.statLabel` (12sp, `WalkSharingButtons.kt:214`, `:219`), and it drops iOS's `.tracking(1.0)` on the URL. See Open questions.

---

### 3. #221: reliquary toggle and photo permission

#### iOS (7c200bf)

```swift
    @State private var walkReliquary = UserPreferences.walkReliquaryEnabled.value
    @State private var showPhotosDeniedNote = false
```
> Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:11-12@7c200bf

```swift
            settingToggle(
                label: "Gather walk photos",
                description: "Find photos you took along each walk and pin them to the route. Photos stay in Apple Photos — never copied or uploaded.",
                isOn: $walkReliquary
            ) { newValue in
                if newValue {
                    // Clear any lingering denial note for this attempt.
                    showPhotosDeniedNote = false
                    PermissionManager.standard.checkPhotosPermission { granted in
                        // Guard against a stale callback: if the user has since toggled off,
                        // don't resurrect the ON state.
                        guard walkReliquary else {
                            UserPreferences.walkReliquaryEnabled.value = false
                            return
                        }
                        if granted {
                            UserPreferences.walkReliquaryEnabled.value = true
                        } else {
                            walkReliquary = false
                            UserPreferences.walkReliquaryEnabled.value = false
                            showPhotosDeniedNote = true
                        }
                    }
                } else {
                    // The denial path above also programmatically flips `walkReliquary` to
                    // false, re-entering this branch. Persist the OFF state but do NOT clear
                    // `showPhotosDeniedNote` — letting the note survive the revert is the
                    // whole point of showing it. The note naturally clears on the next
                    // successful ON attempt (where this branch is skipped entirely).
                    UserPreferences.walkReliquaryEnabled.value = false
                }
            }

            if showPhotosDeniedNote {
                Text("Photo access was declined. To enable the reliquary, grant Photo Library access in iOS Settings → Pilgrim.")
                    .font(Constants.Typography.caption)
                    .foregroundColor(.fog)
                    .padding(.top, 4)
            }
```
> Pilgrim/Scenes/Settings/SettingsCards/PracticeCard.swift:78-116@7c200bf

The toggle helper fires `onChange` on every change to the bound value, programmatic ones included. That is why the revert re-enters the OFF branch.

```swift
    Toggle(isOn: isOn) {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(Constants.Typography.body)
                .foregroundColor(.ink)
            Text(description)
                .font(Constants.Typography.caption)
                .foregroundColor(.fog)
        }
    }
    .tint(.stone)
    .onChange(of: isOn.wrappedValue) { _, newValue in onChange(newValue) }
```
> Pilgrim/Scenes/Settings/SettingsCards/SettingsCardStyle.swift:38-49@7c200bf

The permission call. It prompts only when the status is `.notDetermined`. Limited access counts as granted. A previously denied or restricted status answers `false` immediately.

```swift
    func checkPhotosPermission(closure: @escaping (Bool) -> Void) {
        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized, .limited:
            DispatchQueue.main.async { closure(true) }
        case .notDetermined:
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                DispatchQueue.main.async {
                    closure(status == .authorized || status == .limited)
                }
            }
        default:
            DispatchQueue.main.async { closure(false) }
        }
    }
```
> Pilgrim/Models/PermissionManager.swift:145-158@7c200bf

iOS behaviour, in order:
1. **User turns it ON.** The switch flips visually at once (`@State`). The note clears. The preference is **not** written yet.
2. **Granted.** The preference is written `true`.
3. **Denied**, whether just now or earlier. The switch **snaps back OFF**, the preference is written `false`, and the note appears with this text: "Photo access was declined. To enable the reliquary, grant Photo Library access in iOS Settings → Pilgrim." (caption, fog, 4pt top padding). The note survives the revert and clears only on the next ON attempt.
4. **Stale callback.** If the user already switched it off, the preference is written `false` and the switch stays off.

#### Android today (38497e63)

- Toggle handler, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt:175@38497e63` through `:187`. It persists ON **before** the permission result is known. The in-code comment at `SettingsScreen.kt:176-177@38497e63` is wrong about iOS:
  ```kotlin
                          // Persist the preference regardless (iOS keeps
                          // the toggle on and shows the denied note).
                          viewModel.setWalkReliquaryEnabled(enabled)
  ```
- The launcher callback only sets the note flag and never reverts the setting (`SettingsScreen.kt:110@38497e63` through `:112`):
  ```kotlin
      val photoPermLauncher = rememberLauncherForActivityResult(photoPermContract) {
          showPhotosDeniedNote = !isPhotosPermissionGranted(context)
      }
  ```
  After a denial, the switch therefore stays ON (it is driven by `viewModel.walkReliquaryEnabled`, `SettingsScreen.kt:91`) and the note shows beneath an ON switch.
- The note state is screen-level: `var showPhotosDeniedNote by rememberSaveable { mutableStateOf(false) }` at `SettingsScreen.kt:105@38497e63`. The OFF branch clears it: `!enabled -> showPhotosDeniedNote = false` at `SettingsScreen.kt:180@38497e63`.
- Rendering matches iOS: `SettingToggle(label = settings_walk_reliquary_label, …)` followed by `AnimatedVisibility(showPhotosDeniedNote)` with a 200ms fade, caption, fog, `padding(top = 4.dp)`, at `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/practice/PracticeCard.kt:177@38497e63` through `:198`. The copy is adapted for Android (`app/src/main/res/values/strings.xml:315@38497e63` through `:318`): "Photo access was declined. To enable the reliquary, grant photo access in Android Settings → Apps → Pilgrim → Permissions."
- Permission check: `isPhotosPermissionGranted(context)` (`app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/reliquary/PhotoReliquarySection.kt:60@38497e63` through `:101`). It treats API 34 partial access (`READ_MEDIA_VISUAL_USER_SELECTED`) as granted, which matches iOS `.limited`, and on Q+ it also requires `ACCESS_MEDIA_LOCATION`. The request set comes from `photoPermissionsToRequest()` (`PhotoReliquarySection.kt:111`). A permanently denied permission comes back from the launcher at once as denied, the same as the iOS `default:` branch.
- ViewModel: `walkReliquaryEnabled` is at `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt:112@38497e63`. `setWalkReliquaryEnabled(value)` launches a DataStore write that swallows errors, at `SettingsViewModel.kt:172@38497e63` through `:177`. There is no permission-result entry point. The whole decision lives in the composable, which is why it has no tests.
- Existing tests:
  - `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModelPracticeTest.kt:197@38497e63` through `:211` covers reflect and delegate; `:274-281` covers the swallow case.
  - `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/practice/PracticeCardTest.kt:151@38497e63` through `:165` checks the toggle fires its setter; `:228-243` checks note visibility.
  - Nothing tests the denial revert.

#### Delta for Android

1. **Move the decision into the ViewModel** so it can be tested. Add `fun onPhotoPermissionResult(granted: Boolean)` to `SettingsViewModel`. On `!granted` it calls `setWalkReliquaryEnabled(false)`. On `granted` it writes **nothing**.
   - The ON value was already persisted when the toggle flipped.
   - Writing `true` here would break the iOS stale-callback guard at `PracticeCard.swift:89-92`: a switch the user turned off must not come back on.
2. **Launcher callback** (`SettingsScreen.kt:110-112`): read the result once, then act on it:
   ```kotlin
   val granted = isPhotosPermissionGranted(context)
   viewModel.onPhotoPermissionResult(granted)
   showPhotosDeniedNote = !granted
   ```
   The revert goes through the ViewModel, not the `onSetWalkReliquary` lambda, so the lambda's `!enabled -> showPhotosDeniedNote = false` (`:180`) does not run. The note therefore survives the revert, as iOS intends (`PracticeCard.swift:102-106`). Keep `:180`. Once the fix is in, the switch can only be ON after a grant, so clearing the note there cannot be observed.
3. **Correct the comment** at `SettingsScreen.kt:176-177`. iOS reverts the switch to OFF on denial and keeps the note (`PracticeCard.swift:95-98`). Android persists ON first and reverts in the permission callback.
4. **Tests to extend: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModelPracticeTest.kt`**. Use `FakePracticePreferencesRepository` (`app/src/test/java/org/walktalkmeditate/pilgrim/data/practice/FakePracticePreferencesRepository.kt`) and the existing `buildVm(practiceRepo = …)` helper.
   - `onPhotoPermissionResult denied reverts walkReliquaryEnabled to false`: set true, deny, expect false in both the VM and the repo.
   - `onPhotoPermissionResult granted leaves walkReliquaryEnabled on`: set true, grant, expect true.
   - `granted callback after toggling off does not resurrect`: set false, grant, expect false.
   - The note flag is screen state, and `PracticeCardTest.kt:228-243` already pins how it renders. No change is needed there.

   The issue says Android's summary-side affordance (Grant plus Open settings, `strings.xml:320-323`) is better than iOS's, so no change there.

---

### iOS defects found

- **None material in U7's three surfaces.** Each behaviour ported here is internally consistent at `7c200bf`.
- Minor cosmetic nit, likely not worth its own issue: `AboutView` gives Data Sources (`AboutView.swift:362`) and the motto (`AboutView.swift:377`) the same `sectionAppear(index: 4, …)`, so they fade in together rather than staggered. The index was probably meant to be 5. Android's About has no section-appear stagger at all, so there is nothing to match. If filed, it could ride along with the other R5 iOS issues.

### Open questions

1. **OSM row icon.** iOS uses `point.topleft.down.curvedto.point.bottomright.up`, a curved path between two points. The candidate is `Icons.Filled.Route` / `Icons.Outlined.Route`, if the pinned `material-icons-extended` includes it; I could not check the artifact locally. The fallback is `Icons.Outlined.Timeline`. The implementer should confirm it compiles.
2. **Returned-block icon.** iOS uses `arrow.uturn.backward.circle`. No Android precedent exists. Candidates: `Icons.AutoMirrored.Outlined.Undo` inside a 1dp fog `CircleShape` border (closest shape), or `Icons.Outlined.Replay` (simplest). Needs a visual call in U10.
3. **Is the optimistic persist acceptable?** iOS writes the preference only after a grant. Android writes ON first and reverts on denial, as the #221 fix sketch proposes. What the user sees is identical, because the switch flips immediately on both platforms. The only difference is that a process death during the permission dialog leaves ON persisted without permission, and the summary's Grant prompt covers that case. The alternative, a local optimistic switch state with no write until grant, is a closer match but more code.
4. **Pre-existing About spacing and styling drift** (outside U7's ask; fold in or defer?):
   - Section spacing: iOS uses a uniform 12pt VStack spacing with no top padding. Android uses `spacedBy(8.dp)`, an explicit `Spacer(8.dp)` (giving 24dp between a paragraph and its row), and `padding(vertical = 24.dp)` with a 16dp header top.
   - Row styling: `OpenSourceLinkRow` draws its icon at 24dp where iOS draws a 14pt glyph in a 24pt slot, and its chevron alpha is 0.5 where iOS uses 0.3.
5. **Pre-existing `JourneyFooter` / `ImageShareButton` subtitle size.** iOS `micro` (9pt, plus `tracking(1.0)` on the URL) is rendered as `statLabel` (12sp) at `WalkSharingButtons.kt:177`, `:214` and `:219`. Before "fixing" it inside the #225 PR, check whether this was a deliberate readability choice.
6. **Map ornaments, adjacent to item 1 but outside U7.** The same iOS commit `1c9fc1c` also raised the Mapbox logo and attribution button above the active-walk sheet (`PilgrimMapView.applyOrnamentMargins`, which feeds the sheet's `bottomInset` into the ornament margins). The Phase 21 plan covers ornaments only for the Honor overview. Whether Android's active-walk sheet covers its ornaments has not been checked. It should be assigned to a unit (U3 or U4?) or explicitly deferred.

---

## E. U8 audits

### Audit A: a whisper waiting behind a voice-guide prompt

**iOS bug class and fix.** iOS holds a whisper that arrives while a guide prompt (or, since Honor, a Way voice) is speaking. It sits in a single pending slot, and a `playbackDidFinish` subscription releases it:

```swift
        voiceGuidePlayer.playbackDidFinish
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in
                self?.playPendingWhisperIfNeeded()
            }
            .store(in: &cancellables)
    }

    func playWhisper(url: URL, volume: Float = 0.8) {
        if voiceGuidePlayer.isPlaying || WayVoicePlayer.shared.isPlayingWayVoice {
            pendingWhisperURL = url
            return
        }
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:37-49@7c200bf

At the previous pin, `stop()` never announced the end, so only the natural-completion delegate did. A whisper held behind a prompt that was stopped, or that failed to start, therefore waited for the rest of the walk:

```swift
    func stop() {
        guard player != nil else { return }
        player?.stop()
        player = nil
        restoreAndDeactivate()
        finishPendingCallback()
    }
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift:56-62@33b0dbc

Commit `4335163` fixed both halves (PR #81, "a held voice is never stranded"; items B2 and B7 in its message). B2: every exit path now sends `playbackDidFinish`. B7: `stopWhisper()` now clears the slot before its `player != nil` guard.

```swift
        } catch {
            print("[VoiceGuidePlayer] Playback error: \(error)")
            restoreAndDeactivate()
            finishPendingCallback()
            playbackDidFinish.send()
        }
    }

    func stop() {
        guard player != nil else { return }
        player?.stop()
        player = nil
        restoreAndDeactivate()
        finishPendingCallback()
        // Every exit path announces the end, not just the natural one: a Way
        // voice or a whisper held behind this prompt is otherwise stranded
        // for the rest of the walk. Subscribers re-check `isPlaying` before
        // acting, so the extra emission a replacing `play()` triggers is
        // harmless.
        playbackDidFinish.send()
    }
```
> Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift:57-77@7c200bf

```swift
    func stopWhisper() {
        // Cleared above the guard: "stop the whisper" must also drop one that
        // is merely waiting to start, or it surfaces after the walker
        // silenced it.
        pendingWhisperURL = nil
        guard player != nil else { return }
```
> Pilgrim/Models/Audio/AudioPriorityQueue.swift:54-59@7c200bf

iOS pins B2 with `testStoppingTheGuideReleasesAPendingWayVoice` (`UnitTests/Honor/WayVoicePlayerTests.swift:100-119@7c200bf`).

**Android subsystem checked.**
- **Whisper side:** `data/whisper/WhisperPlayer.kt`, which has two channels and reference-counts its audio focus. Its callers:
  - `service/BackgroundWhisperAutoPlayer.kt`: proximity auto-play in `:tracker`.
  - `ui/walk/WalkViewModel.kt`: tap-on-pin, placement confirm and preview, in the UI process.
  - `di/SeekModule.kt:187`: the seek reveal whisper, in the UI process.
- **Guide side:** `audio/voiceguide/ExoPlayerVoiceGuidePlayer.kt`, plus the orchestrator and scheduler. These run only in the UI process: they start at `PilgrimApp.kt:291@38497e63`, after the main-process check at `:237`.
- **Paths traced:**
  - Proximity: `Entered` → `handleEntered` → `WhisperPlayer.play` → `playJob`, which downloads and then calls `startMediaPlayer`. Guide state is never read.
  - Guide: `play` → its own `AudioFocusRequest`. Whisper state is never read.

**Verdict: absent.** There is no pending-whisper slot on Android, and nothing holds a whisper behind a prompt:
- `WhisperPlayer.play()` never checks the guide.
- The voice-guide package never refers to a whisper.
- The only coupling is OS audio focus, and neither side accepts delayed focus gain, so nothing waits for focus to come back.

With nothing held, B2 and B7 have nothing to act on.

**Evidence.**
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/whisper/WhisperPlayer.kt:126-135@38497e63`: `play()` goes straight to download and start.
  ```kotlin
      open fun play(definition: WhisperDefinition) {
          if (!soundsPreferences.soundsEnabled.value) return
          playJob?.cancel()
          playJob = scope.launch {
              playMutex.withLock {
                  val file = ensureCached(definition.audioFileName) ?: return@withLock
                  stopPlay()
                  startMediaPlayer(file, PLAY_VOLUME) { player ->
  ```
- `WhisperPlayer.kt:233-240@38497e63`: the whisper asks for `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with no change listener and no delayed gain, so its request is never deferred.
- `app/src/main/java/org/walktalkmeditate/pilgrim/service/BackgroundWhisperAutoPlayer.kt:239-249@38497e63`: `handleEntered` ends in `whisperPlayer.play(definition)` with no check on the guide. The KDoc at `:54-73` records that the two processes are not coordinated.
- `grep -rni whisper app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/` returns nothing.
- `app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/ExoPlayerVoiceGuidePlayer.kt@38497e63`: the guide already reports completion on every exit, which is Android's version of B2's "every exit announces the end":
  - `:242`: `.setAcceptsDelayedFocusGain(false)`.
  - `:148-153`: focus denied → `fireCompletionOnce()`.
  - `:107-122`: playback ended or errored → completion.
  - `:164-170`: `stop()` → completion.
  - Tests: `ExoPlayerVoiceGuidePlayerTest.kt:88, :97, :114, :124`.
- `WhisperPlayer.kt:166-171@38497e63`: `stop()` cancels `playJob` and `previewJob` before tearing down the players. An in-flight download is Android's only "pending" whisper, and cancelling it is B7's equivalent. No production code calls `stop()`; the placement sheet calls `stopPreviewOnly()` (`WalkViewModel.kt:1700`).

**Overlap confirmed as a separate gap.** This is not the bug class above; it is the fifth R9 gap, which R17's arbitration closes in U18. The evidence widens its scope in two ways:
- **Both directions overlap.**
  - *Whisper starting over a prompt:* the whisper's MAY_DUCK request sends the guide `LOSS_TRANSIENT_CAN_DUCK`, which the guide ignores on purpose (`ExoPlayerVoiceGuidePlayer.kt:243-253`). Both players use `CONTENT_TYPE_SPEECH`, and stock AOSP does not auto-duck speech players, so on a device they most likely play at full volume together. Device QA should confirm.
  - *Prompt starting over a whisper:* iOS stops the whisper (`interruptForVoiceGuide`, `Pilgrim/Models/Audio/AudioPriorityQueue.swift:66-73@7c200bf`). Android's whisper request has no focus listener (`WhisperPlayer.kt:236-238`), so the whisper keeps playing under the prompt.
- **It is not only cross-process.** Tap-to-play (`WalkViewModel.kt:1669`), placement confirm (`:1823`) and the seek reveal whisper (`SeekModule.kt:187`) all run in the UI process, next to the guide.

**Low-severity residual (also outside the class).** Cancellation is cooperative. If `stop()` or `stopPreviewOnly()` lands while the job is inside the non-suspending `startMediaPlayer` (`prepare()` at `WhisperPlayer.kt:346`, `start()` at `:349`, `bindPlayer` at `:350`), the whisper still starts, because `stopPlay()` (`:184-195`) saw `playPlayer == null`. The window is a few milliseconds, the time to prepare a local file. `stop()` has no production caller. With `stopPreviewOnly()`, the preview could sound while `_isPlaying` reads false. No R7 fix is needed; U18 should keep this in mind if it adds a real "stop whispers" call.

**Invariant tests to add (U8).**
- **New `app/src/test/java/org/walktalkmeditate/pilgrim/data/whisper/WhisperPlayerTest.kt`.** Run it under Robolectric. Give `OkHttpClient` an interceptor that holds each request on a latch, because `CDN_BASE_URL` is a constant and can't be pointed at a test server. Enable sounds on the `SoundsPreferencesRepository` fake.
  1. `stop while a whisper download is in flight never starts it`:
     - Call `play(def)`, then `stop()`, then release the latch with a valid body, and drain.
     - Assert `isAnyChannelPlaying.value == false`.
     - Assert `shadowOf(audioManager).lastAudioFocusRequest == null`.
     - This pins B7's invariant on Android's only pending phase.
  2. `stopPreviewOnly while a preview download is in flight never starts it`: the same test on the preview channel, asserting `isPlaying.value == false`.
- **`app/src/test/java/org/walktalkmeditate/pilgrim/audio/voiceguide/ExoPlayerVoiceGuidePlayerTest.kt`.** Add `focus denied fires onFinished exactly once`:
  - Call `shadowOf(audioManager).setNextFocusRequestResponse(AUDIOFOCUS_REQUEST_FAILED)`, then `play`.
  - Assert exactly one callback and `State.Error`.
  - The focus-denied branch at `:148-153` is the one exit path not yet pinned.

### Audit B: discarding a walk mid-recording

**iOS bug class and fix.** At the previous pin, `cancel()` never touched the recorder, so a discard left the recorder running, the partial file on disk, and the microphone session held:

```swift
    func cancel() {
        teardownSeek()
        cancellables.removeAll()
        proximityService.stopListening()
        sessionGuard?.stopAndCleanup()
        soundManagement.onWalkEnd()
        voiceGuideManagement.stopGuiding()
        WalkActivityManager.shared.end()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:325-333@33b0dbc

PR #84 fixed this in two commits:
- **`50cbd01`** made `cancel()` call `discardPendingReply()` first. At that point it only called `stopRecording()`.
- **`cecf089`** changed it to `discardRecording()`. Its message says stopping alone "left the microphone session held for a commit that a discarded walk never makes".

```swift
    func cancel() {
        discardPendingReply()
        teardownSeek()
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift:417-419@7c200bf

```swift
    func discardPendingReply() {
        pendingReplyOrigin = nil
        voiceRecordingManagement.discardRecording()
    }
```
> Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Replies.swift:12-15@7c200bf

```swift
    public func discardRecording() {
        guard isRecording else { return }
        stopMetering()
        isRecording = false
        recordingStartDate = nil
        audioRecorder?.delegate = nil
        audioRecorder?.stop()
        audioRecorder = nil
        commitRecording(successfully: false)
    }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/VoiceRecordingManagement.swift:151-160@7c200bf

```swift
        defer {
            currentRecordingStart = nil
            currentRecordingRelativePath = nil
            deactivateAudioSession()
        }

        guard flag else {
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            let fileURL = docs.appendingPathComponent(relativePath)
            try? FileManager.default.removeItem(at: fileURL)
            return
        }
```
> Pilgrim/Models/Walk/WalkBuilder/Components/VoiceRecordingManagement.swift:190-201@7c200bf

`UnitTests/VoiceRecordingDiscardTests.swift:18-47@7c200bf` pins two cases: the session is released and the partial file is gone; and a discard with no recording open leaves another consumer's session alone.

**Android subsystem checked.** The discard path, end to end (all paths under `app/src/main/java/org/walktalkmeditate/pilgrim/`, `@38497e63`):
1. **UI tap:** `WalkViewModel.discardWalk()` (`ui/walk/WalkViewModel.kt:1633-1643`) → `UiWalkController.discardWalk()` → `actionPublisher.discard()` (`walk/UiWalkController.kt:438-441`, `walk/WalkActionPublisher.kt:70`).
2. **`:tracker` service:** `ACTION_DISCARD` (`service/WalkTrackingService.kt:496`) → `WalkControllerImpl.discardWalk()` (`walk/WalkControllerImpl.kt:178-192`).
3. **Reducer:** `Discard` → `Idle + PurgeWalk` from Active, Paused or Meditating (`domain/WalkReducer.kt:106-107`, `:137-138`, `:168-169`).
4. **Purge:** `deleteWalkById` (`WalkControllerImpl.kt:540-545`), which cascades to `voice_recordings` (`data/entity/VoiceRecording.kt:14-18`, `onDelete = ForeignKey.CASCADE`).
5. **Back in the UI process:** Room invalidation → `UiWalkController` finds the row gone (`getWalk` returns null) → `WalkState.Idle` (`UiWalkController.kt:198-209`).
6. **Recorder cleanup:** the main-process `WalkLifecycleObserver` (instantiated at `PilgrimApp.kt:346`) → `handleVoiceStop(commitRow = false)` → `VoiceRecorder.stop()` → `finalizeSession` → `OrphanRecordingSweeper.deleteRecordingIfSafe` (`audio/OrphanRecordingSweeper.kt:150-156`).

**Verdict: already fixed.** Android has handled this since Stage 9.5-C, and all four obligations hold.

- **The recorder stops.** The observer calls `stop()` on every Idle after its first emission. It has no in-progress latch that conflation could skip.
  ```kotlin
                  when (state) {
                      is WalkState.Finished -> scope.launch { handleVoiceStop(commitRow = true) }
                      WalkState.Idle -> scope.launch { handleVoiceStop(commitRow = false) }
                      else -> Unit
                  }
  ```
  > `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserver.kt:92-96@38497e63`

  The capture loop's `finally` calls `audioCapture.stop()` (`audio/VoiceRecorder.kt:283`).
- **Audio focus is released.** `finalizeSession` abandons focus unconditionally after the drain, on both the user-stop and the focus-loss paths:
  ```kotlin
          s.doneLatch.await()
          audioFocus.abandon()
          _audioLevel.value = 0f
  ```
  > `app/src/main/java/org/walktalkmeditate/pilgrim/audio/VoiceRecorder.kt:202-204@38497e63`
- **The partial file is deleted.**
  - The observer deletes it directly (`WalkLifecycleObserver.kt:122-131`).
  - A take with no audio is deleted inside `finalizeSession` (`VoiceRecorder.kt:212-215`); the observer's `else` branch then only logs (`:158-160`).
- **No row is inserted.** The `commitRow = false` branch never calls `repository.recordVoice` (`WalkLifecycleObserver.kt:122-150`).

**Correction to the plan's wording.** The plan says the sweeper deletes the partial file. It doesn't: the observer deletes it immediately through the sweeper's guarded helper. The daily `sweepAll` case (e) later removes the emptied `recordings/<walkUuid>/` directory (`OrphanRecordingSweeper.kt:134-139`, `:189-209`). Case (e) is also the backstop when a user tap on stop races the discard and the insert fails on the foreign key: that leaves an orphan WAV (`WalkViewModel.kt:1162-1190`), which case (e) collects.

**Differences from iOS that are not defects.**
- **Timing.** The stop is asynchronous: it waits for the cross-process purge and the Room round trip, normally well under a second. iOS stops synchronously inside `cancel()`. Audio captured in that window is deleted with the file.
- **Forward note for Honor (Phase B).** iOS's defect was in the reply path. When "reply here" is ported, the reply must record through `VoiceRecorder` so this observer covers it. Its origin must also be cleared on discard, the equivalent of `pendingReplyOrigin = nil`.

**Missing test assertions.** The two discard tests (`app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserverTest.kt:168-202` and `:204-233`) assert only `audioLevel == 0` and `voiceRecordingsFor(walkId).isEmpty()`. The gaps:
1. **The wait fires too early.** `audioLevel.first { it == 0f }` (`:190-192`, `:224-226`) is first satisfied by the capture loop's `finally` (`VoiceRecorder.kt:286`). That happens before `abandon()` (`:203`) and before the observer's delete. New assertions need to wait until the file is gone, under the existing 30 s failsafe.
2. **The partial file is never checked.** `startLiveRecordingFor` throws away the `Path` that `start()` returns (`:305-308`).
3. **Abandoning focus is never tested with a recorder**, here or in `VoiceRecorderTest`. Only `AudioFocusCoordinatorTest.kt:95` tests `abandon()`, directly.
4. **Session release is not checked:**
   - `isRecording.value == false`
   - `recordingStartedAtMillis == null`
   - a second `stop()` returning `NoActiveRecording`
   - `fakeAudioCapture.stopCallCount == 1`
5. **The no-row check can't tell the difference.** The walk row is deleted first, so a wrongly attempted insert would also fail on the foreign key and read as empty.
6. **Untested cases:**
   - discard from Meditating;
   - discard before any audio was captured;
   - a discard with no recording open leaving another holder's focus alone (iOS's second case).

**Invariant tests to add (U8)** in `WalkLifecycleObserverTest.kt`. Keep the `AudioManager` and the `AudioFocusCoordinator` from `setUp` as fields, and have `startLiveRecordingFor` return the `Path`.
- **a. `discard mid-recording deletes the partial file and abandons focus`.**
  - Record, then go Active → `deleteWalkById` → Idle, and wait until `!Files.exists(path)`.
  - Assert `shadowOf(audioManager).lastAbandonedAudioFocusRequest` is the same instance as `shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest`.
  - Assert `voiceRecorder.isRecording.value == false`.
  - Assert `voiceRecorder.stop()` fails with `NoActiveRecording`.
  - Assert `fakeAudioCapture.stopCallCount.get() == 1`, and that no row exists.
- **b. `Idle never commits a row even while the parent walk still exists`.** The same flow without `deleteWalkById`, so an insert would succeed if one were attempted. Assert no row and the file gone. This is the check that actually discriminates.
- **c. `Meditating to Idle (discard from Meditating) cleans up like Active`.** The assertions from (a).
- **d. `discard before any audio was captured leaves no file and releases focus`.** Use `FakeAudioCapture(bursts = emptyList())`, so `read()` returns -1 and no bytes are written. Assert the file is gone (deleted by `finalizeSession` as an empty recording) and focus was abandoned.
- **e. `discard with no recording open leaves another holder's focus alone`** (the Android counterpart of `VoiceRecordingDiscardTests.swift:36-47@7c200bf`).
  - Call `audioFocus.requestMediaPlayback()` on the shared coordinator, then go Active → Idle.
  - Assert `lastAbandonedAudioFocusRequest == null`. This holds because `stop()` returns before `finalizeSession` (`VoiceRecorder.kt:179-182`).

### Audit C: location teardown race (iOS's CombineExt `DemandBuffer` patch)

**iOS bug class and fix.** CombineExt 1.8.0 checked `completion` with a `precondition` outside its lock:

```swift
    func buffer(value: S.Input) -> Subscribers.Demand {
        precondition(self.completion == nil,
                     "How could a completed publisher sent values?! Beats me 🤷‍♂️")
        lock.lock()
        defer { lock.unlock() }
```
> Pods/CombineExt/Sources/Common/DemandBuffer.swift:43-47@324614a (parent of the fix)

The race: a relay is cancelled on one thread while another thread is still delivering a value. On iOS that is "ending a walk with a location sample in flight on the shared background queue". The walk's location relays go through `asBackgroundPublisher()` (`Pilgrim/Models/Walk/WalkBuilder/Components/LocationManagement.swift:201-207@7c200bf`, `Pilgrim/Extensions/Combine/Publisher.swift:41-46@7c200bf`). The bad interleaving trapped instead of dropping the value.

The fix is `159745a` (PR #82). It moves the check under the lock and drops a late value, and it also locks `complete()` and ignores a second completion:

```swift
    func buffer(value: S.Input) -> Subscribers.Demand {
        lock.lock()
        defer { lock.unlock() }

        // Pilgrim patch (see Podfile post_install): `completion` used to be
        // read here OUTSIDE the lock and trapped when non-nil. A relay
        // cancelled on one thread while another is still delivering a value
        // — ending a walk with a location sample in flight — races that
        // read. A value that arrives after completion is dropped instead.
        guard self.completion == nil else { return .none }
```
> Pods/CombineExt/Sources/Common/DemandBuffer.swift:43-52@7c200bf

```swift
    func complete(completion: Subscribers.Completion<S.Failure>) {
        lock.lock()
        defer { lock.unlock() }

        // Pilgrim patch: a second completion is ignored rather than trapped.
        guard self.completion == nil else { return }
```
> Pods/CombineExt/Sources/Common/DemandBuffer.swift:70-75@7c200bf

Two tests pin it (`UnitTests/CombineExtDemandBufferTests.swift:13-42@7c200bf`): 40 rounds of cancelling while another thread floods 20,000 values, and a direct "a value after completion is dropped" case.

**Android subsystem checked.** All paths below are under `app/src/main/java/org/walktalkmeditate/pilgrim/`, `@38497e63`.
- **Source:** `location/FusedLocationSource.kt:65-136`, a `callbackFlow` fused with `.buffer(Channel.UNLIMITED)`. `DefaultLocationCallbackBinder` delivers on the main Looper (`:236-246`).
- **Collectors:**
  - Walk pipeline: `service/WalkTrackingService.kt:287-297`, in a service scope on `Dispatchers.Main.immediate` (`:74`), cancelled in `onDestroy` (`:162`).
  - Seek engine: `walk/seek/SeekOrchestrator.kt:479-488`, on `@SeekScope`, which is `Dispatchers.Default.limitedParallelism(1)` (`:50-58`).
  - Seek setup: `ui/seek/SeekSetupViewModel.kt:272-274`, which ends the flow with `.first()`.
- **Downstream:** `WalkControllerImpl.recordLocation` (`walk/WalkControllerImpl.kt:194-195`) → `dispatch`, which runs under `dispatchMutex` (`:431-437`) → `WalkReducer`.

**Verdict: absent.** Four reasons:
1. **A late value can't crash anything.** The callback uses `trySend`, which returns a `ChannelResult` and never throws on a closed or cancelled channel. There is no `send`, `offer` or `trySendBlocking` in `location/` or `service/`. FLP's `removeLocationUpdates` is asynchronous (it returns a Task), so a callback already queued can still run after `awaitClose`. When it does, `trySend` returns closed and the point is dropped, which is exactly the behavior iOS patched in.
   ```kotlin
                      hasEmitted.set(true)
                      trySend(point)
   ```
   > `app/src/main/java/org/walktalkmeditate/pilgrim/location/FusedLocationSource.kt:119-120@38497e63`
   ```kotlin
          callbackBinder.register(callback)

          awaitClose {
              Log.i(TAG, "removeLocationUpdates (flow cancelled)")
              callbackBinder.unregister(callback)
          }
      }.buffer(Channel.UNLIMITED)
   ```
   > `app/src/main/java/org/walktalkmeditate/pilgrim/location/FusedLocationSource.kt:130-136@38497e63`
2. **No completion state is read unsynchronized.** Whether the channel is closed is tracked inside kotlinx's thread-safe channel. The only other state the callback touches is `hasEmitted`, an `AtomicBoolean` scoped to one collection (`FusedLocationSource.kt:72`).
3. **On the walk pipeline, the race can't happen.**
   - FLP delivers on the main Looper (`FusedLocationSource.kt:241`).
   - The callbackFlow producer, including its `awaitClose` block, runs in the collector's context, which is the service scope on `Dispatchers.Main.immediate`.
   - So delivery and teardown take turns on one thread.

   The two seek collectors do cross threads: a single Default thread against the main Looper, and `.first()` cancelling right after a value. Reason 1 covers them.
4. **A late value can't write into a finished or discarded walk.**
   - Values already buffered can reach `recordLocation` between Finish or Discard and `onDestroy`.
   - They are serialized under `dispatchMutex` with `FinalizeWalk` and `PurgeWalk`, and the reducer turns them into no-ops once the walk has left Active:
     ```kotlin
         private fun reduceIdle(action: WalkAction): Pair<WalkState, WalkEffect> =
             when (action) {
                 is WalkAction.Start -> startFresh(action) to startEffect(action)
                 else -> WalkState.Idle to WalkEffect.None
             }
     ```
     > `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkReducer.kt:21-25@38497e63` (Finished is the same at `:33-40`)
   - As a result, no route sample is written for a purged walk (so no foreign-key failure), and no state comes back.
   - Once `scope.cancel()` runs (`WalkTrackingService.kt:162`), the channel is cancelled and anything still buffered is dropped.

**Existing coverage.**
- `app/src/test/java/org/walktalkmeditate/pilgrim/location/FusedLocationSourceTest.kt:133-151` asserts that cancelling unregisters the callback (`:139-141`). But its fake removes the callback on unregister (`:186-188`), so `fire()` after a cancel never reaches it. No test delivers a late callback.
- `WalkReducerTest.kt:184-199` covers a sample arriving while Finished. There is no Idle case, and no controller-level `recordLocation` test after finish or discard.

**Invariant tests to add (U8).**
- **`app/src/test/java/org/walktalkmeditate/pilgrim/location/FusedLocationSourceTest.kt`.** First, have `FakeLocationCallbackBinder` keep `lastRegistered: LocationCallback?` after unregister.
  1. `a callback delivered after cancellation neither throws nor emits`:
     - Collect, fire one anchor sample, assert `results.size == 1`, then cancel the job and assert `activeCallbackCount == 0`.
     - Call `lastRegistered.onLocationResult(LocationResult.create(listOf(point(5f))))` directly.
     - Assert no exception and `results.size == 1`.
     - Repeat for `rawLocationFlow()`.
  2. `cancelling mid-flood from another thread never throws or emits after cancel` (the Android counterpart of `CombineExtDemandBufferTests.swift:13-32@7c200bf`):
     - Collect on `Dispatchers.Default.limitedParallelism(1)` (the seek topology), inside `runBlocking`.
     - A worker thread calls `lastRegistered.onLocationResult` in a loop; cancel the collector after a random 50–500 µs, then join the worker.
     - Assert the worker caught no `Throwable`, and that the count taken right after `job.join()` hasn't changed once the flood ends.
     - Keep the flood modest (for example 20 rounds × 2,000): every callback calls `Log.i`, and Robolectric's `ShadowLog` keeps each line in memory.
- **`app/src/test/java/org/walktalkmeditate/pilgrim/domain/WalkReducerTest.kt`:** `location sample ignored when idle (after discard)`. `reduce(Idle, LocationSampled)` returns `Idle` and `WalkEffect.None`.
- **`app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerDiscardTest.kt`:** `recordLocation after discardWalk is a no-op and writes no sample`. Start, record one sample, discard, then record a late one. Assert state `Idle`, no exception, and `locationSamplesFor(walkId)` empty.
- **`app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerTest.kt`:** `recordLocation after finishWalk leaves Finished and writes no sample`. Assert the state is still `Finished` with unchanged distance and the sample count is unchanged.

### Summary table

| Audit | Verdict | Fix needed? | Tests to add (U8) |
|---|---|---|---|
| A. Whisper waiting behind a guide prompt (iOS `4335163`, PR #81, B2/B7) | **Absent.** There is no pending-whisper slot; `WhisperPlayer.play()` never reads guide state; the guide already reports completion on every exit. | No. The overlap in both directions, including within the UI process, is R9's fifth gap and closes in U18. The millisecond `stop()`-during-`prepare()` window is a low note for U18. | New `WhisperPlayerTest.kt`: stop, or stopPreviewOnly, during an in-flight download never starts playback or requests focus. `ExoPlayerVoiceGuidePlayerTest.kt`: focus denied fires onFinished once. |
| B. Discard mid-recording (iOS `50cbd01` + `cecf089`, PR #84) | **Already fixed** (Stage 9.5-C). `WalkLifecycleObserver` stops the recorder on every Idle; `finalizeSession` abandons focus; the observer deletes the WAV; `commitRow = false` never inserts. The sweeper only removes the emptied directory later. | No. Keep "reply here" on this path when Honor lands. | `WalkLifecycleObserverTest.kt`: (a) file deleted, focus abandoned, session released, mic stopped; (b) no row even with the walk row still present; (c) discard from Meditating; (d) discard with no audio captured; (e) a no-recording discard leaves another holder's focus alone. The wait must be on file deletion, not `audioLevel == 0`. |
| C. Location teardown race (iOS `159745a`, PR #82, CombineExt `DemandBuffer`) | **Absent.** `trySend` returns a closed result instead of throwing; the walk pipeline delivers and tears down on one main thread; the seek collectors cross threads but the channel is thread-safe; the reducer drops late samples in Idle/Finished under `dispatchMutex`. | No | `FusedLocationSourceTest.kt`: a late callback after cancel neither throws nor emits (both flows); a cross-thread cancel-mid-flood stress test. `WalkReducerTest.kt`: sample in Idle. `WalkControllerDiscardTest.kt` / `WalkControllerTest.kt`: `recordLocation` after discard or finish is a no-op and writes no sample. |
