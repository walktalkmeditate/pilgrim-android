# Seek Crescent + Sky Light + Celestial Tints (U7) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` (U7) · **Requirements:** R4, R5 (crescent cluster `28417e8`..`b98536f`, `7c3d618`, off-screen fix `174e9e0`)
> **iOS pin:** `pilgrim-ios` @ `c1745e8` (HEAD of `main`, 2026-07-14). All quotes cite `file:line@c1745e8`.
> **Android files:** `domain/seek/SeekCrescentModel.kt` (pure span/flare/visibility math), `domain/seek/SeekSkyLight.kt` (hour tokens + celestial tint + seek voice), `ui/walk/map/SeekCrescentRenderer.kt` (bookkeeping + Mapbox writes behind a style seam), `domain/seek/SeekFogModel.kt` (crescent field + `walkerPosition`, per U6 spec D1), `core/celestial/SunCalc.kt` (`solarElevationDegrees`), `ui/walk/PilgrimMap.kt` (wiring).
> **Builds on:** `docs/parity/2026-07-14-port-seek-fog-u6.md` (fog renderer, style seam pattern D5, puck-point cache D3, light-color seam D7).

## Naming table — iOS "wisp" is the shipped CRESCENT; Android names everything SeekCrescent*

| iOS symbol @c1745e8 | Android symbol (U7) |
|---|---|
| `SeekFogState.Wisp` | `SeekFogState.Crescent` |
| `SeekFogState.wisp` | `SeekFogState.crescent` |
| `SeekFogModel.wispPoint` | `SeekCrescentModel.crescentPoint` |
| `SeekFogModel.wispSpanDegreesNearToFar` | `SeekCrescentModel.SPAN_DEGREES_NEAR_TO_FAR` |
| `SeekFogModel.wispSpanDegrees(forBucket:)` | `SeekCrescentModel.spanDegrees(bucket)` |
| `SeekWispVisibilityModel` | `SeekCrescentVisibilityModel` (in `SeekCrescentModel.kt`) |
| `PilgrimMapView+SeekWisp.swift` | `ui/walk/map/SeekCrescentRenderer.kt` |
| `SeekWispRendering` | `SeekCrescentRendering` |
| `SeekWispRendering.layerID = "seek-wisp"` | `SeekCrescentRendering.LAYER_ID = "seek-crescent"` |
| `"seek-wisp-source"` | `"seek-crescent-source"` |
| `imageID "seek-wisp-crescent-{span}-{light}"` | `imageId "seek-crescent-{span}-{light}"` |
| `syncWispLayer` | `SeekCrescentRenderer.sync` |
| `flareSeekWisp` | `SeekCrescentRenderer.flare` |
| `evaluateSeekWispVisibility` | `SeekCrescentRenderer.evaluateVisibility` |
| `fireWispHandoffExhale` | `SeekCrescentRenderer.fireHandoffExhale` |
| `wispCrescentImage` | `renderCrescentBitmap` (+ pure `crescentSegments`) |
| `removeWispLayer` | `SeekCrescentRenderer.remove` |
| `wispRestingOpacity` | `SeekCrescentModel.restingOpacity(reduceMotion)` |
| renderer fields `wispReleased` / `wispFlareGeneration` / `appliedWispImageID` / `lastWispVisibilityCheckUptime` | `released` / `flareGeneration` / `appliedImageId` / `lastVisibilityCheckUptimeMillis` (inside `SeekCrescentRenderer`) |
| `installSeekWispCameraObservers` | camera/idle subscriptions in `PilgrimMap`'s seek `DisposableEffect` |
| `SeekSkyLight` (in `SeekFogModel.swift`) | `SeekSkyLight` (own file, `domain/seek/SeekSkyLight.kt`) |
| `SeekTint` / `SeekSky` / `SeekVoice` (in `SeekGatewayView.swift`) | `SeekTint` / `SeekSky` / `SeekVoice` (in `SeekSkyLight.kt`) |
| `CelestialCalculator.solarElevationDegrees` | `SunCalc.solarElevationDegrees` |

The layer/source/image id strings themselves are renamed (`seek-wisp*` → `seek-crescent*`): they are runtime style ids private to this app, never persisted and never crossing to iOS, so the naming rule wins over byte-parity (D1).

## B1. State — `SeekFogState.Wisp` → `Crescent`, restored `walkerPosition`

**iOS** (`Pilgrim/Models/Walk/Seek/SeekFogModel.swift:21-42@c1745e8`):

```swift
/// The wisp: a dawn crescent hugging the puck's rim on the clearing's
/// side, so a map glance answers "which way" without hunting the fog.
/// Attached to the walker (rotation-only), never a floating marker.
struct Wisp: Equatable {
    let position: SeekPoint
    let bearingDegrees: Double
}
...
/// Nil when hidden (arrived, revealing, complete, or no walker fix).
/// Whether a present wisp is *shown* is the renderer's call: it releases
/// the crescent whenever the fog itself is visible in the viewport.
let wisp: Wisp?

init(circles: [FogCircle], tintHex: String? = nil, wisp: Wisp? = nil) {
```

**Android:** `data class Crescent(val position: SeekPoint, val bearingDegrees: Double)` nested in `SeekFogState`; new field `val crescent: Crescent? = null` (participates in `data class` equality — U6's whole-state early-return now covers it). `fogState` regains `walkerPosition: SeekPoint? = null` (U6 spec D1 closure). Defaults keep every existing U6 call site + all 26 `SeekFogModelTest` cases source-compatible.

**fogState wisp derivation** (`SeekFogModel.swift:101-124@c1745e8`):

```swift
var wisp: SeekFogState.Wisp?
if phase != .complete {
    ...
    circles.append(...)
    wisp = wispPoint(
        walkerPosition: walkerPosition,
        clearingCenter: clearing.center,
        phase: phase
    )
}
return SeekFogState(circles: circles, tintHex: tintHex, wisp: wisp)
```

**Android:** same placement — non-COMPLETE phases compute `crescent = SeekCrescentModel.crescentPoint(walkerPosition, clearing.center, phase)`; COMPLETE leaves it null.

## B2. `wispPoint` → `crescentPoint`

**iOS** (`SeekFogModel.swift:127-136@c1745e8`):

```swift
static func wispPoint(
    walkerPosition: SeekPoint?,
    clearingCenter: SeekPoint,
    phase: SeekEnginePhase
) -> SeekFogState.Wisp? {
    guard phase == .guiding, let walkerPosition else { return nil }
    var bearing = SeekChainGenerator.bearingDegrees(from: walkerPosition, to: clearingCenter)
    if bearing < 0 { bearing += 360 }
    return SeekFogState.Wisp(position: walkerPosition, bearingDegrees: bearing)
}
```

**Android claims, one-for-one:** null unless `phase == GUIDING` AND a walker fix exists (ARRIVED/REVEALING hide the crescent — the fog dissolve owns those moments); bearing from the existing `SeekChainGenerator.bearingDegrees(from, to)` (already ported, atan2 great-circle formula, `SeekChainGenerator.kt:210-217`), normalized from `(-180, 180]` to `[0, 360)` by a single `+360` on negatives; position = the walker (the crescent rides the puck, never floats).

## B3. Span ladder — opens as the fog nears

**iOS** (`SeekFogModel.swift:169-178@c1745e8`):

```swift
/// The crescent opens as the fog nears: a narrow sliver far out, a
/// full curve close in. Keyed to the fog buckets (nearest first) so
/// span changes inherit their boundary hysteresis and never thrash.
static let wispSpanDegreesNearToFar: [Double] = [96, 86, 72, 60, 48]

static func wispSpanDegrees(forBucket bucket: Int?) -> Double {
    guard let bucket else { return wispSpanDegreesNearToFar[wispSpanDegreesNearToFar.count - 1] }
    let clamped = min(max(bucket, 1), wispSpanDegreesNearToFar.count)
    return wispSpanDegreesNearToFar[clamped - 1]
}
```

**Android claims, one-for-one:** `SPAN_DEGREES_NEAR_TO_FAR = [96.0, 86.0, 72.0, 60.0, 48.0]`; `spanDegrees(null) = 48.0` (no fix = farthest sliver); bucket clamps to `1..5` — bucket 0 (dissolved-adjacent) stays fully open at 96°, bucket ≥ 5 stays 48°. The caller keys the span on `SeekFogState.activeFogBucket` (U6), so span transitions inherit the fog's 10% boundary hysteresis for free — no separate span hysteresis exists.

## B4. Rendering constants

**iOS** (`Pilgrim/Views/PilgrimMapView+SeekWisp.swift:12-55@c1745e8`):

```swift
enum SeekWispRendering {
    static let layerID = "seek-wisp"
    static let sourceID = "seek-wisp-source"
    ...
    static let restOpacity = 0.55
    static let steadyOpacity = 0.8
    /// Flare peak grows with closeness; an aligned pulse outshines both.
    static let flarePeakBase = 0.75
    static let flarePeakClosenessSpan = 0.15
    static let alignedFlarePeak = 1.0
    /// One shared transition eases every opacity write — swell, settle,
    /// exhale, and return all breathe at the same pace.
    static let breathDuration: TimeInterval = 1.0
    /// The settle write lands just after the swell completes.
    static let flareHoldSeconds: TimeInterval = 1.05
    static let imageSize = 84.0
    static let arcRadius = 30.0
    static let visibilityThrottleSeconds: TimeInterval = 0.12
}
```

**Android:** `SeekCrescentRendering` internal object, identical values — `LAYER_ID = "seek-crescent"`, `SOURCE_ID = "seek-crescent-source"`, `REST_OPACITY = 0.55`, `STEADY_OPACITY = 0.8`, `FLARE_PEAK_BASE = 0.75`, `FLARE_PEAK_CLOSENESS_SPAN = 0.15`, `ALIGNED_FLARE_PEAK = 1.0`, `BREATH_MILLIS = 1_000L`, `FLARE_HOLD_MILLIS = 1_050L`, `IMAGE_SIZE_DP = 84.0`, `ARC_RADIUS_DP = 30.0`, `VISIBILITY_THROTTLE_MILLIS = 120L`. Opacity/flare constants live in `SeekCrescentModel` (domain) so the flare math is JVM-pure; the renderer file keeps only ids, geometry, and timing.

## B5. The hour's light — `SeekSkyLight`

**iOS** (`SeekFogModel.swift:192-224@c1745e8`):

```swift
enum SeekSkyLight {
    enum Daypart: String { case golden, midday, night }

    /// Golden spans civil twilight into the low sun. No elevation (no fix
    /// yet) stays golden — the seek's home light, matching pre-hour builds.
    static func daypart(solarElevationDegrees elevation: Double?) -> Daypart {
        guard let elevation else { return .golden }
        if elevation < -4 { return .night }
        if elevation < 8 { return .golden }
        return .midday
    }

    static func hex(daypart: Daypart, starlight: Bool) -> String {
        switch (starlight, daypart) {
        case (false, .golden): return "#C4956A"
        case (false, .midday): return "#D2B283"
        case (false, .night): return "#A9AFBC"
        case (true, .golden): return "#D3BCE8"
        case (true, .midday): return "#DAD4F5"
        case (true, .night): return "#C8C0FF"
        }
    }

    /// Cache token for pre-rendered crescent images — one per (span, light).
    static func token(daypart: Daypart, starlight: Bool) -> String {
        "\(starlight ? "star" : "dawn")-\(daypart.rawValue)"
    }
}
```

**Android claims, one-for-one:** `Daypart { GOLDEN, MIDDAY, NIGHT }` with `rawValue`-equivalent lowercase token strings; boundaries EXACT: `elevation < -4 → NIGHT`, `-4 ≤ elevation < 8 → GOLDEN` (both `-4.0` and `0.0` and `7.9` are golden; `8.0` is midday); `null → GOLDEN` (no fix = the seek's home light). Six fixed hexes verbatim; all six hexes and all six tokens distinct (pinned by iOS `SeekWispVisibilityTests:160-165`). `#C8C0FF` (constellation night) is deliberately the constellation puck's exact `stone` (`ui/theme/Color.kt` constellation palette `stone = Color(0xFFC8C0FF)`) — iOS pins this against the puck override (`SeekWispVisibilityTests:148-158`, "the puck's exact starlight"). `#A9AFBC` (dawn-family night) is deliberately the full-moon fog tint — one moonlight vocabulary (`SeekWispVisibilityTests:141-146`).

**Starlight selection** (`PilgrimMapView+SeekWisp.swift:20-31@c1745e8`):

```swift
static var isStarlight: Bool {
    UserPreferences.appearanceMode.value == "constellation"
}
static func imageID(spanDegrees: Double, daypart: SeekSkyLight.Daypart) -> String {
    let light = SeekSkyLight.token(daypart: daypart, starlight: isStarlight)
    return "seek-wisp-crescent-\(Int(spanDegrees.rounded()))-\(light)"
}
```

**Android (D2):** no global preference read in map code (project rule; U6 spec D6 precedent). `PilgrimMap` reads `LocalIsConstellation.current` (the existing composition local for `AppearanceMode.Constellation`) and passes `starlight: Boolean` down. Image id: `"seek-crescent-{span.roundToInt()}-{token}"`.

## B6. Hour computation — `currentSeekDaypart` / `seekLightColor`

**iOS** (`PilgrimMapView+SeekWisp.swift:135-144@c1745e8`):

```swift
/// The hour of the walk, read from the sun's elevation at the walker's
/// position. No location yet falls back to golden — the seek's home
/// light. Shared by the crescent and the pulse ring.
static func currentSeekDaypart(on mapView: MBMapView) -> SeekSkyLight.Daypart {
    let elevation = mapView.location.latestLocation.map {
        CelestialCalculator.solarElevationDegrees(at: $0.coordinate, on: Date())
    }
    return SeekSkyLight.daypart(solarElevationDegrees: elevation)
}

static func seekLightColor(on mapView: MBMapView) -> UIColor {
    SeekWispRendering.lightColor(daypart: currentSeekDaypart(on: mapView))
}
```

**Android (D3):** the walker position is the cached puck point (`MapboxSeekFogStyle.latestPuckPoint`, U6's D3 indicator-position cache — the direct analogue of iOS's `location.latestLocation`; null while no fix → golden). `PilgrimMap` builds provider lambdas — `daypartNow = { SeekSkyLight.daypart(latestPuckPoint?.let { SunCalc.solarElevationDegrees(lat, lon, Instant.now()) }) }` and `starlightNow = { LocalIsConstellation via rememberUpdatedState }` — and hands them to both renderers, so the value is read AT WRITE TIME exactly like iOS (`currentSeekDaypart` per sync/fire), never frozen at composition. This replaces U6's `seekLightColor: Color` parameter on `PilgrimMap` — iOS computes the hour light inside the map layer at fire time, nobody passes it in (U6 spec D7 said "U7's SeekSkyLight will compute it"); a caller-supplied color could drift from the crescent's image tint. `SeekFogRenderer` gains a `lightColorArgb: () -> Int` provider (default golden) evaluated per ring fire; the crescent derives its image tint from the same `daypartNow`/`starlightNow` pair, so ring and crescent always share one light.

## B7. Layer sync — SymbolLayer at the walker, rotation-only

**iOS** (`PilgrimMapView+SeekWisp.swift:62-130@c1745e8`):

```swift
/// The wisp rides the walker's own coordinate and only rotates — a
/// crescent of dawn on the puck's rim, not a floating marker. Screen
/// geometry is constant across zooms (it is an affordance, not
/// geography). Opacity is owned by the flare/visibility writes below;
/// this only manages existence, position, and rotation.
static func syncWispLayer(_ wisp:, previous:, spanDegrees:, renderer:, on mapView:) {
    let daypart = currentSeekDaypart(on: mapView)
    let desiredImageID = SeekWispRendering.imageID(spanDegrees:, daypart:)
    guard wisp != previous || desiredImageID != renderer.appliedWispImageID else { return }
    guard let wisp else { removeWispLayer(from: mapView, renderer: renderer); return }
    do {
        if mapView.mapboxMap.layerExists(withId: layerID), sourceExists(sourceID) {
            try updateGeoJSONSource(... Point(wisp.position.coordinate))
            try setLayerProperty(... "icon-rotate", value: wisp.bearingDegrees)
            if desiredImageID != renderer.appliedWispImageID {
                ensureWispImage(...); try setLayerProperty(... "icon-image", ...)
                renderer.appliedWispImageID = desiredImageID
            }
            return
        }
        removeWispLayer(from: mapView, renderer: renderer)
        ensureWispImage(id: desiredImageID, ...)
        var source = GeoJSONSource(id: sourceID); source.data = .feature(...)
        try mapView.mapboxMap.addSource(source)
        let reduceMotion = UIAccessibility.isReduceMotionEnabled
        var layer = SymbolLayer(id: layerID, source: sourceID)
        layer.iconImage = .constant(.name(desiredImageID))
        layer.iconRotate = .constant(wisp.bearingDegrees)
        layer.iconRotationAlignment = .constant(.map)
        layer.iconAllowOverlap = .constant(true)
        layer.iconIgnorePlacement = .constant(true)
        layer.iconOpacity = .constant(0)
        layer.iconOpacityTransition = StyleTransition(
            duration: reduceMotion ? 0 : SeekWispRendering.breathDuration, delay: 0)
        try mapView.mapboxMap.addLayer(layer)
        renderer.appliedWispImageID = desiredImageID
        // A released wisp reinstalls at zero (style reload with fog on
        // screen) so the handoff exhale never replays.
        writeWispOpacity(renderer.wispReleased ? 0 : wispRestingOpacity(), on: mapView)
    } catch { print("[PilgrimMapView] seek wisp sync failed: \(error)") }
}
```

**Android claims, one-for-one:**
- Early-return when `crescent == previous` AND the desired image id is already applied (position/bearing/span/light all unchanged → zero style reads/writes).
- Null crescent → `remove()` (layer + source + bookkeeping reset).
- Fast path when layer + source both exist: `setStyleGeoJSONSourceData(sourceId, "", GeoJSONSourceData.valueOf(point))` + `setStyleLayerProperty("icon-rotate", bearing)`; image swap only when the id changed (`ensureImage` + `"icon-image"` write + record).
- Install path: remove any stale remnants first, register the image, add a point `GeoJsonSource`, add a `SymbolLayer` with `iconImage`, `iconRotate = bearing`, `iconRotationAlignment = MAP` (rotates with the map so the arc keeps pointing at the clearing when the user rotates), `iconAllowOverlap = true`, `iconIgnorePlacement = true` (must never be collision-culled by the puck), `iconOpacity = 0` DECLARED with a 1000 ms (0 under reduce-motion) `iconOpacityTransition` set once — every later opacity write (swell, settle, exhale, return) is GPU-eased by this single transition, no animators.
- Entrance write in the same pass: `released ? 0 : restingOpacity` — a released crescent reinstalls at zero so the handoff exhale never replays (see B12 divergence note D8: iOS's as-written code defeats this via `removeWispLayer` resetting `wispReleased`; Android implements the documented intent).
- Layer added with NO layer position → top of stack, same as the pulse ring (iOS parity: `addLayer` without position).
- All style mutations caught-and-logged (`Log.w("PilgrimMap", ...)`) — the crescent is decorative; a failed write must never crash a walk (iOS `do/catch + print`, `PilgrimMapView+SeekWisp.swift:127-129,170-172,307-309`).

**Image cache** (`PilgrimMapView+SeekWisp.swift:146-157@c1745e8`):

```swift
private static func ensureWispImage(id:, spanDegrees:, daypart:, on mapView:) {
    guard !mapView.mapboxMap.imageExists(withId: id) else { return }
    try? mapView.mapboxMap.addImage(
        wispCrescentImage(spanDegrees: spanDegrees, color: lightColor(daypart:)), id: id)
}
```

**Android:** `hasStyleImage(id)` guard then `addImage(image(id, bitmap) { scale(pixelRatio) })` — the style itself is the cache (one image per (span-bucket, light-token) survives for the style's lifetime; a style reload wipes images, and the cleared `appliedImageId` forces re-registration). Bitmap is rendered at `IMAGE_SIZE_DP × pixelRatio` pixels and registered with `scale = pixelRatio` so the crescent draws 84 dp on every density (iOS renders in points; `UIGraphicsImageRenderer` bakes the screen scale — `scale` is the Android analogue).

## B8. The crescent bitmap — 24 segments × 3 stacked passes

**iOS** (`PilgrimMapView+SeekWisp.swift:312-353@c1745e8`):

```swift
/// An arc of dawn light drawn pointing north; `icon-rotate` aims it at
/// the clearing. Drawn as short segments whose width and alpha peak at
/// the apex and taper to nothing at the tips, in three stacked passes
/// (wide/faint under narrow/bright) so brightness falls off smoothly —
/// light, not a band with a casing.
private static func wispCrescentImage(spanDegrees: Double, color: UIColor) -> UIImage {
    let size = SeekWispRendering.imageSize                    // 84
    let span = CGFloat(spanDegrees * .pi / 180)
    let base = -CGFloat.pi / 2 - span / 2                     // centered on north
    let center = CGPoint(x: size / 2, y: size / 2)
    let segments = 24
    // Tiny angular overlap hides antialiasing seams between segments.
    let seamCover: CGFloat = 0.008
    let passes: [(widthScale: CGFloat, alphaScale: CGFloat)] = [(3.0, 0.10), (1.9, 0.22), (1.0, 1.0)]
    ...
    for pass in passes {
        for segment in 0..<segments {
            let fractionStart = CGFloat(segment) / CGFloat(segments)
            let fractionEnd = CGFloat(segment + 1) / CGFloat(segments)
            // 0 at the apex (the point that aims at the clearing), 1 at either tip.
            let offApex = abs((fractionStart + fractionEnd) / 2 - 0.5) * 2
            let fade = pow(cos(offApex * .pi / 2), 1.4)
            let width = (1.5 + 3.0 * fade) * pass.widthScale
            let alpha = (0.05 + 0.95 * fade) * pass.alphaScale
            let arc = UIBezierPath(arcCenter: center, radius: 30,
                startAngle: base + span * fractionStart,
                endAngle: base + span * fractionEnd + seamCover, clockwise: true)
            arc.lineWidth = width; arc.lineCapStyle = .butt
            color.withAlphaComponent(min(alpha, 1)).setStroke(); arc.stroke()
        }
    }
}
```

**Android claims, one-for-one:** pure `crescentSegments(spanDegrees): List<CrescentSegment>` computes, per pass × segment, `startAngleDegrees` (base = −90 − span/2, matching Android's `drawArc` convention where 0° = 3 o'clock and positive sweeps clockwise — identical to UIKit's flipped arc space), `sweepAngleDegrees = span/24 + seamCover` (seamCover 0.008 rad ≈ 0.458°, converted once), `widthDp = (1.5 + 3.0·fade)·widthScale`, `alpha = min((0.05 + 0.95·fade)·alphaScale, 1)` where `fade = cos(offApex·π/2)^1.4`, `offApex = |midFraction − 0.5|·2`. Passes stacked wide/faint under narrow/bright: `[(3.0, 0.10), (1.9, 0.22), (1.0, 1.0)]`. The pure function is JVM-tested (apex peaks, tip taper, pass count 72, symmetry); the thin `renderCrescentBitmap` wrapper strokes each segment with `Canvas.drawArc(RectF(center±radius), start, sweep, false, paint)` using `Paint.Style.STROKE`, `Paint.Cap.BUTT`, anti-alias, at `pixelRatio`-scaled radius/width (Stage 3-C lesson: composition proves nothing about draw — extract the geometry and pin it directly; `SealRenderer`'s `sealGeometry` precedent).

## B9. Pulse flare — one breath per pulse, generation-guarded settle

**iOS** (`PilgrimMapView+SeekWisp.swift:181-205@c1745e8`):

```swift
/// One breath per pulse: swell to a peak shaped by closeness (warmer
/// still when aligned), then settle back to rest. Both writes ride the
/// layer's single opacity transition — the only bookkeeping is the
/// generation guard that turns a superseded settle into a no-op.
static func flareSeekWisp(_ pulse: SeekPulseVisual, on mapView:, renderer:) {
    guard !UIAccessibility.isReduceMotionEnabled,
          !renderer.wispReleased,
          mapView.mapboxMap.isStyleLoaded,
          mapView.mapboxMap.layerExists(withId: layerID) else { return }
    renderer.wispFlareGeneration += 1
    let generation = renderer.wispFlareGeneration
    let peak = pulse.aligned
        ? SeekWispRendering.alignedFlarePeak
        : SeekWispRendering.flarePeakBase
            + SeekWispRendering.flarePeakClosenessSpan * min(max(pulse.closeness, 0), 1)
    writeWispOpacity(peak, on: mapView)
    let settle = DispatchTime.now() + SeekWispRendering.flareHoldSeconds
    DispatchQueue.main.asyncAfter(deadline: settle) { [weak mapView, weak renderer] in
        guard let mapView, let renderer,
              renderer.wispFlareGeneration == generation,
              !renderer.wispReleased,
              mapView.mapboxMap.layerExists(withId: layerID) else { return }
        writeWispOpacity(SeekWispRendering.restOpacity, on: mapView)
    }
}
```

**Android claims, one-for-one:** flare guards — reduce-motion (no flares at all), released (a released crescent never flares), style loaded, layer exists. Peak math in `SeekCrescentModel.flarePeak(aligned, closeness)`: `aligned → 1.0`, else `0.75 + 0.15 × closeness.coerceIn(0.0, 1.0)`. Generation bumped BEFORE the peak write; settle scheduled at +1050 ms via the style seam's `postDelayed` (main-thread `Handler` in the Mapbox impl, manually-run in the fake); settle re-checks generation + not-released + layer-exists before writing `REST_OPACITY`. Fired from `SeekFogRenderer.apply`'s once-per-token-advance branch — the SAME branch as the pulse ring (`PilgrimMapView+SeekFog.swift:84-90@c1745e8`: `fireSeekPulseRing` then `flareSeekWisp`), so swallowed tokens (style not ready, U6 B10) swallow the flare too.

## B10. Viewport release — the pointer dissolves when the target is visible

**Pure model** (`SeekFogModel.swift:226-267@c1745e8`):

```swift
/// The crescent is a pointer to something beyond sight: the moment the fog
/// itself is on screen — zoomed out to it or walked near it — the pointer
/// is redundant and releases. Screen-space intersection with a hysteresis
/// band so a fog edge grazing the viewport during a pan cannot flicker it.
enum SeekWispVisibilityModel {
    /// The fog must reach this far *into* the viewport before the crescent
    /// releases, and retreat this far *beyond* the edge before it returns.
    static let releaseInsetPoints: CGFloat = 24
    static let returnOutsetPoints: CGFloat = 24

    /// Returns the new released state given the previous one. `fogCenter`
    /// is nil when the fog cannot be projected onto the screen — Mapbox's
    /// `point(for:)` collapses every off-view coordinate to (-1, -1), so an
    /// unprojectable fog is definitionally not visible: the crescent shows.
    static func shouldRelease(wasReleased: Bool, fogCenter: CGPoint?,
                              fogRadiusPoints: CGFloat, viewSize: CGSize) -> Bool {
        guard let fogCenter else { return false }
        guard viewSize.width > 0, viewSize.height > 0,
              fogCenter.x.isFinite, fogCenter.y.isFinite, fogRadiusPoints.isFinite else {
            return wasReleased
        }
        let bounds = CGRect(origin: .zero, size: viewSize)
        let rect = wasReleased
            ? bounds.insetBy(dx: -returnOutsetPoints, dy: -returnOutsetPoints)
            : bounds.insetBy(dx: releaseInsetPoints, dy: releaseInsetPoints)
        return circleIntersects(center: fogCenter, radius: fogRadiusPoints, rect: rect)
    }

    static func circleIntersects(center: CGPoint, radius: CGFloat, rect: CGRect) -> Bool {
        guard !rect.isNull, !rect.isEmpty else { return false }
        let dx = max(rect.minX - center.x, 0, center.x - rect.maxX)
        let dy = max(rect.minY - center.y, 0, center.y - rect.maxY)
        return dx * dx + dy * dy <= radius * radius
    }
}
```

**Android claims, one-for-one** (`SeekCrescentVisibilityModel`): `RELEASE_INSET_PX = 24.0`, `RETURN_OUTSET_PX = 24.0` (logical/density-independent pixels — see D5); null center → `false` (off-screen fog NEVER releases AND always hands the crescent back — the `174e9e0` regression fix, pinned by iOS `SeekWispVisibilityTests:31-39`); non-positive view or non-finite center/radius → keep `wasReleased` unchanged; release rect = bounds inset by +24, return rect = bounds inset by −24 (outset), so the 48 px band between them is a dead zone where both states hold (no flapping); closest-point circle/rect intersection with `<=` (rim touching counts); degenerate rect (inset larger than the view — tiny views) never intersects.

**Renderer evaluation** (`PilgrimMapView+SeekWisp.swift:232-273@c1745e8`):

```swift
/// Re-decides whether the crescent should be shown, from the active
/// fog's screen-space footprint. Called from camera changes (throttled),
/// map idle, and every fog apply. Cheap guards first: wander maps and
/// seek maps without a wisp exit before touching any projection.
static func evaluateSeekWispVisibility(on mapView:, renderer:, throttled: Bool) {
    guard let state = renderer.lastAppliedState,
          state.wisp != nil,
          let fog = state.circles.first(where: { !$0.isHalo }) else { return }
    let now = CACurrentMediaTime()
    if throttled, now - renderer.lastWispVisibilityCheckUptime < 0.12 { return }
    renderer.lastWispVisibilityCheckUptime = now
    guard mapView.mapboxMap.isStyleLoaded else { return }

    // point(for:) clamps every off-view coordinate to (-1, -1) — pass
    // nil instead so the model reads "off screen", not "a circle just
    // past the top-left corner" (which released the crescent forever).
    let projected = mapView.mapboxMap.point(for: fog.center.coordinate)
    let center: CGPoint? = (projected.x >= 0 && projected.y >= 0) ? projected : nil
    let zoom = Double(mapView.mapboxMap.cameraState.zoom)
    let metersPerPoint = SeekFogRendering.metersPerPixelEquatorZ0
        * cos(fog.center.latitude * .pi / 180) / pow(2.0, zoom)
    guard metersPerPoint > 0 else { return }

    let released = SeekWispVisibilityModel.shouldRelease(
        wasReleased: renderer.wispReleased, fogCenter: center,
        fogRadiusPoints: CGFloat(fog.radiusMeters / metersPerPoint),
        viewSize: mapView.bounds.size)
    guard released != renderer.wispReleased,
          mapView.mapboxMap.layerExists(withId: layerID) else { return }
    renderer.wispReleased = released
    renderer.wispFlareGeneration += 1
    if released { fireWispHandoffExhale(on: mapView, renderer: renderer) }
    else { writeWispOpacity(wispRestingOpacity(), on: mapView) }
}
```

**Android claims, one-for-one:** cheap guards first (no applied state / no crescent / no active non-halo fog circle → exit before any projection — wander maps pay nothing); throttled calls (camera-changed) rate-limited to one check per 120 ms via an injected uptime clock, unthrottled calls (map-idle, fog-apply) always run and refresh the throttle timestamp; style-loaded guard; screen radius from `radiusMeters / (78271.517·cos(lat)/2^zoom)` (reuses `SeekFogRendering.METERS_PER_PIXEL_EQUATOR_Z0`), guard `metersPerPoint > 0`; state transition applies only when `released` actually changed AND the layer exists; generation bump on every transition (cancels in-flight settles); release → handoff exhale, return → resting-opacity write.

**OFF-SCREEN TRAP — VERIFIED on Mapbox Android 11.11.0:** `MapboxMap.pixelForCoordinate` behaves EXACTLY like iOS `point(for:)` — the SDK's `clampScreenCoordinate()` returns `ScreenCoordinate(-1.0, -1.0)` for any projection landing outside `[0, width] × [0, height]` (verified in SDK source, `mapbox-maps-android/maps-sdk/src/main/java/com/mapbox/maps/MapboxMap.kt:1044-1116@v11.11.0`; KDoc: *"If the screen coordinate is outside of the bounds of MapView the returned screen coordinate contains -1 for both coordinates."*). The defensive mapping is therefore identical to iOS: a projected point with `x < 0 || y < 0` → null → "off screen, crescent shows". The check is coded defensively anyway (also rejects NaN via the model's finite guard) and stays on the device smoke-check list (item 6) because the clamp means a fog whose center is just off-view but rim on-view reads as off-screen — same visual behavior as iOS, but worth an eye on-device. Caveat from the same KDoc: under Globe projection `pixelForCoordinate` is a no-op returning the screen center — irrelevant here (stock `Style.LIGHT`/`Style.DARK` at walk zooms are mercator), noted for completeness.

## B11. Handoff exhale

**iOS** (`PilgrimMapView+SeekWisp.swift:277-292@c1745e8`):

```swift
/// The handoff: the fog just entered view, so the crescent gives one
/// final full flare and dissolves into the thing it pointed at.
private static func fireWispHandoffExhale(on mapView:, renderer:) {
    guard !UIAccessibility.isReduceMotionEnabled else {
        writeWispOpacity(0, on: mapView); return
    }
    let generation = renderer.wispFlareGeneration
    writeWispOpacity(SeekWispRendering.alignedFlarePeak, on: mapView)
    let dissolve = DispatchTime.now() + SeekWispRendering.flareHoldSeconds
    DispatchQueue.main.asyncAfter(deadline: dissolve) { [weak mapView, weak renderer] in
        guard let mapView, let renderer,
              renderer.wispFlareGeneration == generation,
              renderer.wispReleased,
              mapView.mapboxMap.layerExists(withId: layerID) else { return }
        writeWispOpacity(0, on: mapView)
    }
}
```

**Android claims, one-for-one:** reduce-motion → straight to 0, no flare; otherwise full flare (1.0) then a +1050 ms scheduled dissolve to 0 guarded on (same generation, still released, layer exists). Note the caller (`evaluateVisibility`) bumped the generation BEFORE calling; the exhale captures the bumped value (iOS ordering: bump at `evaluateSeekWispVisibility:267`, capture at `fireWispHandoffExhale:282`).

## B12. Resting opacity, reduce-motion, style-reload interplay

**iOS** (`PilgrimMapView+SeekWisp.swift:294-298@c1745e8`):

```swift
private static func wispRestingOpacity() -> Double {
    UIAccessibility.isReduceMotionEnabled
        ? SeekWispRendering.steadyOpacity   // 0.8
        : SeekWispRendering.restOpacity     // 0.55
}
```

**Android:** `SeekCrescentModel.restingOpacity(reduceMotion) = if (reduceMotion) 0.8 else 0.55`. Reduce Motion: steady 0.8, no flares, no exhale animation (instant 0 on release), zero-duration opacity transition at install.

**Renderer fields** (`PilgrimMapView+SeekFog.swift:19-39@c1745e8`):

```swift
/// True while the fog is visible in the viewport and the crescent has
/// released. Survives style reloads on purpose: reinstalling a released
/// wisp at zero must not replay the handoff exhale.
var wispReleased = false
/// Cancels in-flight flare/exhale settle closures: any new opacity
/// sequence bumps this, turning stale asyncAfter bodies into no-ops.
var wispFlareGeneration = 0
var lastWispVisibilityCheckUptime: TimeInterval = 0
var appliedWispImageID: String?

func resetForStyleReload() {
    lastAppliedState = nil
    appliedCircles = [:]
    wispFlareGeneration += 1
    appliedWispImageID = nil
}
```

**Android:** same four fields inside `SeekCrescentRenderer` (`released`, `flareGeneration`, `appliedImageId`, `lastVisibilityCheckUptimeMillis`); `SeekFogRenderer.resetForStyleReload()` forwards to `crescent.onStyleReloaded()` = generation bump + `appliedImageId = null` — `released` deliberately survives.

**D8 (intent-over-letter divergence):** iOS's fresh-install path routes through `removeWispLayer` (`PilgrimMapView+SeekWisp.swift:99`), which resets `wispReleased = false` (`:160`) — making the very next line's `renderer.wispReleased ? 0 : rest` write (`:123-126`) always choose rest, and letting the post-apply visibility evaluation replay the handoff exhale after a style reload with fog on screen. That contradicts both the field's doc ("survives style reloads on purpose") and the install comment ("the handoff exhale never replays"). Android implements the DOCUMENTED intent: the reinstall path clears layer/source/image bookkeeping and bumps the generation but does NOT touch `released`; only the hide path (`crescent == null` → `remove()`) resets `released = false` (iOS parity there: `applySeekFogNow`'s null branch calls `removeWispLayer`, `PilgrimMapView+SeekFog.swift:140`). Net user-visible difference: on iOS a theme flip mid-released-crescent replays one exhale flare; on Android it reinstalls silently at zero.

## B13. Hooks into the fog renderer

**iOS** (`PilgrimMapView+SeekFog.swift:84-90,140,146,160-169@c1745e8`):

```swift
// applySeekFog, pulse-advance branch:
if state != nil {
    fireSeekPulseRing(on: mapView)
    flareSeekWisp(pulse, on: mapView, renderer: renderer)
}
// applySeekFogNow, null-state branch:
removeWispLayer(from: mapView, renderer: renderer)
// applySeekFogNow, non-null branch (after the circle diff):
let previousWisp = renderer.lastAppliedState?.wisp
...
syncWispLayer(state.wisp, previous: previousWisp,
    spanDegrees: SeekFogModel.wispSpanDegrees(forBucket: state.activeFogBucket),
    renderer: renderer, on: mapView)
renderer.appliedCircles = applied
renderer.lastAppliedState = state
evaluateSeekWispVisibility(on: mapView, renderer: renderer, throttled: false)
```

**Android:** `SeekFogRenderer` gains an optional `crescent: SeekCrescentRenderer?` constructor param (default null — every existing U6 test remains valid as-is). Hook points, one-for-one: pulse-advance branch calls `crescent?.flare(pulse, reduceMotion)` after `firePulseRing` (both inside the `state != null` guard; note the U6 renderer additionally guards the ring on `!reduceMotion` — the crescent's own flare guard handles reduce-motion internally, matching iOS's guard placement); null-state branch calls `crescent?.remove()`; non-null branch captures `previousCrescent = lastAppliedState?.crescent` before the diff, calls `crescent?.sync(state.crescent, previousCrescent, SeekCrescentModel.spanDegrees(state.activeFogBucket), daypart, starlight, reduceMotion)` after the circle diff, then after committing bookkeeping calls `crescent?.evaluateVisibility(state, throttled = false)`. The self-heal probe path (`resetForStyleReload` on a stripped layer) already flows into the full-reinstall pass, which reinstalls the crescent too (its layer was equally stripped; the per-pass `layerExists` fast-path check in `sync` handles partial strips).

The hour light enters at CONSTRUCTION as provider lambdas, not per-call params: `SeekFogRenderer(style, crescent, lightColorArgb: () -> Int)` (U6's `lightColorArgb: Int` value param on `apply` is removed — `apply(state, pulse, reduceMotion)`), and `SeekCrescentRenderer(style, daypart, starlight, uptimeMillis)`. Every fire/sync reads the providers fresh (B6), so `onStyleReloaded`/`flushDeferred` keep their U6 signatures and still reinstall with the current hour.

## B14. Camera observers

**iOS** (`PilgrimMapView+SeekWisp.swift:213-226@c1745e8`, wired from `makeUIView`, `PilgrimMapView.swift:166@c1745e8`):

```swift
/// Wisp viewport release: camera moves re-decide whether the fog is on
/// screen (throttled — these fire per frame during gestures), and map
/// idle runs the authoritative trailing check. Both exit on the first
/// guard for wander maps. Weak captures per AF70.
static func installSeekWispCameraObservers(on mapView: MBMapView, coordinator: Coordinator) {
    mapView.mapboxMap.onCameraChanged.observe { ... evaluateSeekWispVisibility(..., throttled: true) }
    mapView.mapboxMap.onMapIdle.observe { ... evaluateSeekWispVisibility(..., throttled: false) }
}
```

**Android (D4):** iOS installs the observers unconditionally at map creation and relies on the model's cheap first guard for wander maps. Android already has a seek-gated `DisposableEffect(mapView, seekFogActive)` (U6's puck-point listener) — the camera subscriptions join it: `mapboxMap.subscribeCameraChanged { renderer.evaluateVisibility(throttled = true) }` and `mapboxMap.subscribeMapIdle { renderer.evaluateVisibility(throttled = false) }`, both returning `Cancelable`s cancelled in `onDispose`. Wander walks therefore pay literally nothing (no subscription at all) instead of a cheap guard — strictly less work than iOS, same behavior on seek walks. These renderer-driven evaluations read the last-applied state internally (the composable passes no state), matching iOS's coordinator-owned renderer reads.

## B15. Solar elevation — `CelestialCalculator.solarElevationDegrees`

**iOS** (`Pilgrim/Models/Astrology/CelestialCalculator.swift:414-436@c1745e8`):

```swift
/// The sun's elevation above the horizon in degrees at the given moment
/// and place. Declination and right ascension come from the ecliptic
/// longitude; the hour angle from Greenwich sidereal time and the
/// observer's longitude. Accuracy well under a degree — plenty for
/// classifying golden hour / midday / night.
static func solarElevationDegrees(at coordinate: CLLocationCoordinate2D, on date: Date) -> Double {
    let jd = julianDayNumber(from: date)
    let T = julianCenturies(from: jd)
    let lambda = radians(solarLongitude(T: T))
    let epsilon = radians(23.439291 - 0.0130042 * T)

    let declination = asin(sin(epsilon) * sin(lambda))
    let rightAscension = atan2(cos(epsilon) * sin(lambda), cos(lambda))

    let gmstDegrees = (280.46061837 + 360.98564736629 * (jd - 2451545.0))
        .truncatingRemainder(dividingBy: 360)
    let hourAngle = radians(gmstDegrees + coordinate.longitude) - rightAscension

    let phi = radians(coordinate.latitude)
    let sinElevation = sin(phi) * sin(declination)
        + cos(phi) * cos(declination) * cos(hourAngle)
    return degrees(asin(min(max(sinElevation, -1), 1)))
}
```

**Android:** `SunCalc.solarElevationDegrees(latitudeDegrees, longitudeDegrees, instant)` — same pipeline: `jd = julianDay(instant)` (Android's epoch-based formula `epochMillis/86_400_000 + 2_440_587.5` is numerically identical to iOS's Gregorian decomposition for UTC instants), `T = julianCenturies(jd)`, λ from `SunCalc.solarLongitude(T)`, ε = `23.439291 − 0.0130042·T`, δ = `asin(sin ε · sin λ)`, α = `atan2(cos ε · sin λ, cos λ)`, GMST = `(280.46061837 + 360.98564736629·(jd − 2451545)) % 360` (Kotlin `%` truncates toward zero like Swift's `truncatingRemainder`; a negative remainder for pre-2000 dates is harmless — the hour angle only enters through `cos`), H = `toRadians(gmst + longitude) − α`, `sinElevation` clamped to `[-1, 1]` before `asin`. **D6:** Android's `solarLongitude` returns the APPARENT longitude (Meeus, includes the `−0.00569 − 0.00478·sin(Ω)` aberration/nutation term) while iOS's returns the TRUE longitude (`CelestialCalculator.swift:77-87` — no apparent correction). Reused rather than re-derived (house rule; it is `PlanetCalc`/`SeasonalMarkerTurnings`' shared source of truth): the difference is ≤ 0.006° in λ → well under 0.01° in elevation, invisible against iOS's own "well under a degree" accuracy claim and the ±4°/8° daypart boundaries. Tests pin known sun positions (London midsummer noon ≈ 62°, midnight ≈ −15°; Sydney winter noon ≈ 33°) at ±1.0° (≤ the documented accuracy bound, honoring the ≤5× tolerance rule) plus a cross-check against `SunCalc.sunTimes`: elevation at the computed sunrise instant ≈ −0.833° ± 0.5°.

## B16. Celestial fog tint — `SeekTint` / `SeekSky`

**iOS** (`Pilgrim/Scenes/ActiveWalk/SeekGatewayView.swift:106-141@c1745e8`):

```swift
/// The sky's mark on a seek: a turning or a full moon tints the fog and
/// speaks its own gateway line. Turnings outrank the moon — four days a
/// year beat thirteen nights. Hexes come from the seal palette's turning
/// overrides (fixed values — adaptive colors become halos on the map).
struct SeekTint: Equatable {
    let fogHex: String
    let gatewayLine: String
}

enum SeekSky {
    static func tint(marker: SeasonalMarker?,
                     lunarPhase: CelestialCalculator.LunarPhase) -> SeekTint? {
        if let marker {
            switch marker {
            case .springEquinox:
                return SeekTint(fogHex: "#74B495", gatewayLine: "The year leans toward light.\nSeek with it.")
            case .summerSolstice:
                return SeekTint(fogHex: "#C9A646", gatewayLine: "The sun stands still.\nYou don't have to.")
            case .autumnEquinox:
                return SeekTint(fogHex: "#8B4455", gatewayLine: "The year leans toward dusk.\nSeek while it turns.")
            case .winterSolstice:
                return SeekTint(fogHex: "#2377A4", gatewayLine: "The longest night\nhas the most to hide.")
            case .imbolc, .beltane, .lughnasadh, .samhain:
                // Cross-quarter days keep the ordinary fog; the moon may
                // still speak below.
                break
            }
        }
        if lunarPhase == .full {
            return SeekTint(fogHex: "#A9AFBC", gatewayLine: "Tonight the moon\nseeks with you.")
        }
        return nil
    }
}
```

**Android:** `data class SeekTint(val fogHex: String, @StringRes val gatewayLineRes: Int)` + `object SeekSky { fun tint(marker: SeasonalMarker?, isFullMoon: Boolean): SeekTint? }`. Precedence one-for-one: cardinal turning → its tint (turnings outrank the moon); cross-quarter (Imbolc/Beltane/Lughnasadh/Samhain) falls THROUGH to the moon check (ordinary fog unless the moon speaks); full moon → `#A9AFBC` + moon line; else null. Gateway lines land as string resources (house pattern; strings verbatim incl. the `\n` line break):

| resource id | value (verbatim iOS) |
|---|---|
| `seek_tint_line_spring_equinox` | `The year leans toward light.\nSeek with it.` |
| `seek_tint_line_summer_solstice` | `The sun stands still.\nYou don't have to.` |
| `seek_tint_line_autumn_equinox` | `The year leans toward dusk.\nSeek while it turns.` |
| `seek_tint_line_winter_solstice` | `The longest night\nhas the most to hide.` |
| `seek_tint_line_full_moon` | `Tonight the moon\nseeks with you.` |

**Full moon (D7):** iOS passes `CelestialCalculator.lunarPhaseName(for:)` (elongation-bucketed, `CelestialCalculator.swift:161-186`); Android's `MoonCalc` buckets by synodic age (`MoonCalc.kt:52-70`, name `"Full Moon"`). Both put "full" in a ≈3.7-day window around the true full moon with sub-day disagreement at the bucket edges. `SeekSky.tint` takes a plain `isFullMoon: Boolean` so the U8 call site decides via the existing source of truth (`MoonCalc.moonPhase(now).name == "Full Moon"`) — no re-derived lunar math.

**Computed ONCE per walk, gated on celestial awareness** (`ActiveWalkViewModel.swift:715-724@c1745e8` — U8 lands this call site):

```swift
func beginSeekSetup() {
    guard mode == .seek, seekSetupStage == .verifyingAccuracy else { return }
    if UserPreferences.celestialAwarenessEnabled.value {
        ...
        seekTint = SeekSky.tint(
            marker: CelestialCalculator.snapshot(for: now, system: system).seasonalMarker,
            lunarPhase: CelestialCalculator.lunarPhaseName(for: now))
    }
```

U8 contract: compute at seek setup start, only when the celestial-awareness preference is on; the tint's `fogHex` feeds `SeekFogState.tintHex` (U6 already plumbs it; `ActiveWalkViewModel+Seek.swift:281@c1745e8` `tintHex: seekTint?.fogHex`) and the `gatewayLineRes` overrides the gateway's default line (`SeekSetupFlowModifier.swift:47@c1745e8` `SeekGatewayView(line: viewModel.seekTint?.gatewayLine)`). Note: iOS passes the RAW snapshot marker (northern-named) with no hemisphere correction at this call site; Android's turning surfaces are hemisphere-corrected by house precedent (PR #169/#170, `SeasonalMarker.forSouthernHemisphere`). The tint function is marker-in → tint-out either way; the spec flags the choice to U8 (recommended: hemisphere-corrected, consistent with every other Android turning surface — a June seek in Sydney tints winter-blue, not summer-gold).

## B17. Seek weather greetings — `SeekVoice`

**iOS** (`SeekGatewayView.swift:143-160@c1745e8`):

```swift
/// Seek speaks its own weather: the wander greetings name the path; these
/// name the search.
enum SeekVoice {
    static func greeting(for condition: WeatherCondition) -> String {
        switch condition {
        case .clear: return "A clear day for seeking"
        case .partlyCloudy: return "Seeking under shifting skies"
        case .overcast: return "Soft light on the search"
        case .lightRain: return "The rain joins your seeking"
        case .heavyRain: return "The sky seeks with you"
        case .thunderstorm: return "Thunder over the unknown"
        case .snow: return "Snow over the hidden way"
        case .fog: return "Fog seeking fog"
        case .wind: return "The wind knows the way"
        case .haze: return "The unknown behind its veil"
        }
    }
}
```

**Android:** `object SeekVoice { @StringRes fun greetingRes(condition: WeatherCondition): Int }` mirroring `GreetingOverlay.kt`'s `greetingStringResFor` house pattern. Strings verbatim:

| resource id | value |
|---|---|
| `seek_greeting_weather_clear` | `A clear day for seeking` |
| `seek_greeting_weather_partly_cloudy` | `Seeking under shifting skies` |
| `seek_greeting_weather_overcast` | `Soft light on the search` |
| `seek_greeting_weather_light_rain` | `The rain joins your seeking` |
| `seek_greeting_weather_heavy_rain` | `The sky seeks with you` |
| `seek_greeting_weather_thunderstorm` | `Thunder over the unknown` |
| `seek_greeting_weather_snow` | `Snow over the hidden way` |
| `seek_greeting_weather_fog` | `Fog seeking fog` |
| `seek_greeting_weather_wind` | `The wind knows the way` |
| `seek_greeting_weather_haze` | `The unknown behind its veil` |

U8 contract: on seek walks these REPLACE the wander greeting (`ActiveWalkView.swift:691-693@c1745e8`: `if viewModel.mode == .seek { greeting = SeekVoice.greeting(for: condition) }`) — Android's `WeatherGreetingOverlay` will branch to `SeekVoice.greetingRes` when the walk mode is seek.

## Divergence table

| # | iOS @c1745e8 | Android U7 | Why |
|---|---|---|---|
| D1 | Layer/source/image ids `seek-wisp*` | `seek-crescent*` | Wisp→crescent naming rule; ids are runtime-only, never persisted |
| D2 | `UserPreferences.appearanceMode == "constellation"` read globally at render time | `starlight: Boolean` from `LocalIsConstellation` threaded through `PilgrimMap` | Project rule: no global preference reads in map renderers (U6 D6 precedent) |
| D3 | `seekLightColor(on:)` computed inside the map layer from `location.latestLocation` at fire time; U6-Android had a `seekLightColor: Color` param defaulting golden | Param removed; provider lambdas (`daypartNow` from the cached puck point + `Instant.now()`, `starlightNow` from `LocalIsConstellation`) are injected at renderer construction and read at write time | One light source for ring + crescent (iOS shares `currentSeekDaypart`); a caller-supplied color could drift from the crescent tint |
| D4 | Camera/idle observers installed unconditionally at map creation; wander maps exit on the model's first guard | Subscriptions live in the seek-gated `DisposableEffect`, cancelled when fog deactivates | Wander walks pay zero (no subscription) — strictly less work, same seek behavior |
| D5 | Inset/outset in points (24 pt); projection in points | `MapboxSeekCrescentStyle` divides `pixelForCoordinate`/`getSize()` physical pixels by the display density, so the whole intersection (center, radius via meters-per-LOGICAL-pixel, view size, 24-unit insets) runs in dp — the exact analogue of iOS points | One shared unit end-to-end; 24 dp ≈ 24 pt keeps the release band identical across densities |
| D6 | `solarLongitude` = TRUE longitude (no apparent correction) | Reuses `SunCalc.solarLongitude` = APPARENT longitude (Meeus) | House rule: reuse the celestial source of truth; ≤ 0.006° λ difference → < 0.01° elevation, invisible at ±4°/8° boundaries |
| D7 | `lunarPhase == .full` via elongation bucketing | `isFullMoon: Boolean` param; U8 supplies `MoonCalc.moonPhase(now).name == "Full Moon"` (synodic-age bucketing) | Reuse MoonCalc; both give a ≈3.7-day full window, sub-day edge disagreement |
| D8 | `removeWispLayer` in the reinstall path resets `wispReleased`, defeating the documented "released survives style reload / exhale never replays" intent | Reinstall preserves `released`; only the hide path resets it | The iOS comments + field doc are the spec; the reset is the bug (a theme flip mid-release replays one exhale on iOS, silent-at-zero reinstall on Android) |
| D9 | Wisp state machine spread across static `PilgrimMapView` extension funcs; delayed settles via `DispatchQueue.main.asyncAfter` | `SeekCrescentRenderer` behind an internal `SeekCrescentStyle` seam; settles via the seam's `postDelayed` (main `Handler` in prod, manual in fakes); uptime clock injected | JVM-testability (U6 D5 pattern): flare/settle/release/throttle logic pinned on JVM, Mapbox surface device-verified |
| D10 | `SeekSkyLight`/`SeekTint`/`SeekSky`/`SeekVoice` return `String` literals | `@StringRes` ids in `strings.xml` (hexes stay literals — they are style values, not copy) | House pattern for user-facing copy (`GreetingOverlay`, `SeasonalMarkerTurnings` precedents) |

## Dispatcher note (U9 seam)

Unchanged from U6: `SeekFogModel.fogState` (now including `crescentPoint`'s one atan2) runs per GPS fix on `Dispatchers.Default` in U9's orchestrator; the renderer's `apply`, the camera callbacks, and every scheduled settle run on the main thread (Mapbox style writes are main-thread-only; `subscribeCameraChanged`/`subscribeMapIdle` deliver on main).

## Device smoke-check items (renderer surface not JVM-provable)

Appended to U6's list (mid-phase device smoke check, plan R12):

1. Crescent visible on the puck rim at walk start, aimed at the clearing; rotating the map keeps it aimed (map-aligned rotation).
2. Span opens across bucket boundaries on approach (48° sliver far → 96° curve near), swapping without flicker.
3. Each heartbeat: crescent swells (brighter when closer, full-bright when aligned) and settles ~1 s later, in step with the pulse ring.
4. Hour light: golden near sunrise/sunset, pale gold midday, silver after dark; constellation appearance swaps the whole family to lavender/starlight (flip appearance mid-walk: image + ring retint on the next heartbeat).
5. Zoom out until the fog enters the viewport: one full flare then dissolve (handoff exhale); zoom back in past the outset band: crescent returns at rest; camera-drag jitter at the boundary must not flap it.
6. Off-screen clamp: with the fog far off-view, pan/rotate aggressively — the crescent must never dissolve (the `(-1,-1)` sentinel path; VERIFIED in SDK source but worth the eye).
7. Reduce motion (animator duration scale 0): crescent holds steady at 0.8, no flares, no exhale animation.
8. Theme flip mid-seek: crescent reinstalls on the new style; if it was released (fog on screen), it reinstalls invisible with NO exhale replay (D8).
9. Arrival/reveal: crescent hides (state null from ARRIVED/REVEALING phases) and returns aimed at the next clearing when guiding resumes.
