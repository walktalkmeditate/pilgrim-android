# Seek Fog + Pulse Ring (U6) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` (U6) · **Requirements:** R4 (iOS Seek U5) + R5 (`a0624d0` self-heal)
> **iOS pin:** `pilgrim-ios` @ `c1745e8` (HEAD of `main`, 2026-07-14). All quotes cite `file:line@c1745e8`. Includes the `a0624d0` self-heal fix ("fix(seek): self-heal fog layers stripped during lock/unlock") — that commit's end-state is what ships in `PilgrimMapView+SeekFog.swift@c1745e8`.
> **Android files:** `domain/seek/SeekFogModel.kt` (pure state), `ui/walk/map/SeekFogRenderer.kt` (style bookkeeping + Mapbox writes), `ui/walk/PilgrimMap.kt` (hook). Domain stays framework-free; every Mapbox touch lives behind an internal style-surface interface (D5).
> **Out of scope here (U7):** the wisp/crescent — iOS keeps `Wisp`, `wispPoint`, `wispSpanDegreesNearToFar`, `SeekSkyLight`, and `SeekWispVisibilityModel` inside `SeekFogModel.swift`, and the crescent renderer in `PilgrimMapView+SeekWisp.swift`. Android ports those in U7 as `SeekCrescentModel` / `SeekSkyLight` / `SeekCrescentRenderer` (plan's wisp→crescent naming rule). Every such deferral is flagged inline (D1).

## B1. Fog state — `SeekFogState` / `FogCircle`

**iOS** (`Pilgrim/Models/Walk/Seek/SeekFogModel.swift:6-49@c1745e8`):

```swift
/// What the map should show for a seek: fog over the active clearing, faint
/// halos over found ones, and nothing at all for unrevealed clearings so the
/// chain's count stays hidden (origin R6).
struct SeekFogState: Equatable {
    struct FogCircle: Equatable {
        let id: String
        let center: SeekPoint
        let radiusMeters: Double
        /// 0 = dissolved (arrived/halo), 1...N = distance buckets, thicker far.
        let opacityBucket: Int
        /// Found clearings keep a faint persistent halo after their reveal.
        let isHalo: Bool
    }
    ...
    let circles: [FogCircle]
    /// Celestial override for the active fog's color (turning or full moon);
    /// nil renders the default fog grey. Fixed per walk. Halos keep dawn.
    let tintHex: String?
    /// Nil when hidden (arrived, revealing, complete, or no walker fix). ...
    let wisp: Wisp?
    ...
    /// The active clearing's current bucket — callers feed this back into
    /// the next `fogState` call so hysteresis has a reference point.
    var activeFogBucket: Int? {
        circles.first { !$0.isHalo }?.opacityBucket
    }
}
```

**Android:** `data class SeekFogState(circles: List<FogCircle>, tintHex: String? = null)` with nested `data class FogCircle(id, center: SeekPoint, radiusMeters: Double, opacityBucket: Int, isHalo: Boolean)`; `val activeFogBucket: Int?` = first non-halo circle's bucket. `data class` gives the structural equality the render early-return keys on (`List<FogCircle>` + `String?` are all structurally comparable — no arrays, no `java.time`). **D1:** the `wisp: Wisp?` field is NOT ported in U6; U7 adds a `crescent` field (walker position + normalized bearing). Until then Android's whole-state equality covers circles + tint only — every U6 render decision iOS makes from `wisp` lives in the U7 renderer.

## B2. Pulse visual — `SeekPulseVisual`

**iOS** (`SeekFogModel.swift:51-60@c1745e8`):

```swift
/// One pulse of the seek heartbeat as the map sees it: the token advances
/// per pulse, and alignment/closeness shape the crescent's flare the same
/// way they already shape the ping and the haptic.
struct SeekPulseVisual: Equatable {
    let token: Int
    let aligned: Bool
    let closeness: Double
    static let none = SeekPulseVisual(token: 0, aligned: false, closeness: 0)
}
```

**Android:** `data class SeekPulseVisual(token: Int, aligned: Boolean, closeness: Double)` + `companion object { val NONE = SeekPulseVisual(0, false, 0.0) }`. U6's renderer consumes only `token` (ring one-shot); `aligned`/`closeness` are carried now so U7's crescent flare takes the same value without a state-shape change.

## B3. Buckets, hysteresis fraction, opacities

**iOS** (`SeekFogModel.swift:62-74@c1745e8`):

```swift
/// Bucket k covers distances below boundary k (ascending); anything at or
/// beyond the last boundary — or with no fix yet — is the thickest bucket.
static let distanceBucketBoundariesMeters: [Double] = [150, 300, 600, 1200]
/// A fix must land this fraction beyond a boundary before an adjacent
/// bucket change applies, so GPS jitter on the line cannot thrash writes.
static let hysteresisFraction = 0.1
static let bucketOpacities: [Double] = [0.25, 0.35, 0.45, 0.55, 0.65]
static let haloOpacity = 0.12
static let dissolvedOpacity = 0.0
static var farthestBucket: Int { distanceBucketBoundariesMeters.count + 1 }
```

**Android claims, one-for-one:** boundaries `[150.0, 300.0, 600.0, 1200.0]`; buckets 1..5 with opacities `[0.25, 0.35, 0.45, 0.55, 0.65]` (thicker far); hysteresis fraction `0.1`; halo `0.12`; dissolved `0.0`; `farthestBucket = boundaries.size + 1 = 5`.

## B4. State derivation — `fogState`

**iOS** (`SeekFogModel.swift:76-125@c1745e8`):

```swift
static func fogState(
    chain: SeekChain, activeIndex: Int, phase: SeekEnginePhase,
    distanceToActiveMeters: Double?, previousActiveBucket: Int? = nil,
    tintHex: String? = nil, walkerPosition: SeekPoint? = nil
) -> SeekFogState {
    let count = chain.clearings.count
    guard count > 0 else { return SeekFogState(circles: []) }
    let clampedActive = min(max(activeIndex, 0), count - 1)
    let haloCount = phase == .complete ? clampedActive + 1 : clampedActive
    var circles: [SeekFogState.FogCircle] = (0..<haloCount).map { index in
        ... opacityBucket: 0, isHalo: true ...
    }
    var wisp: SeekFogState.Wisp?
    if phase != .complete {
        let clearing = chain.clearings[clampedActive]
        let bucket = phase == .guiding
            ? bucketApplyingHysteresis(distanceMeters: distanceToActiveMeters,
                                       currentBucket: previousActiveBucket)
            : 0
        circles.append(... opacityBucket: bucket, isHalo: false ...)
        wisp = wispPoint(...)
    }
    return SeekFogState(circles: circles, tintHex: tintHex, wisp: wisp)
}
```

**Android claims, one-for-one:**
- Empty chain → empty circles (regardless of phase).
- `activeIndex` clamps to `0..count-1`.
- Found clearings (`0 until haloCount`) render as halos with `opacityBucket = 0`; `haloCount = clampedActive + 1` when phase == COMPLETE (the last clearing joins the halos), else `clampedActive`.
- Non-COMPLETE phases append the active circle: bucket from `bucketApplyingHysteresis(distance, previousActiveBucket)` when GUIDING; **bucket 0 (dissolved) when ARRIVED or REVEALING — the dissolve IS the moment**.
- Unrevealed clearings (index > active) produce NO circles at all — never zero-opacity layers; the chain's count stays hidden (origin R6).
- `tintHex` passes through and participates in equality.
- **D1:** `walkerPosition` param and the `wisp` output are dropped in U6; U7 restores them (as `crescent`).

## B5. Bucket + hysteresis math

**iOS** (`SeekFogModel.swift:138-161@c1745e8`):

```swift
static func opacityBucket(forDistanceMeters distance: Double?) -> Int {
    guard let distance else { return farthestBucket }
    for (index, boundary) in distanceBucketBoundariesMeters.enumerated() where distance < boundary {
        return index + 1
    }
    return farthestBucket
}

/// Adjacent-bucket changes only apply once the fix is a margin past the
/// shared boundary; jumps of 2+ buckets (reroll, first fix) apply as-is.
static func bucketApplyingHysteresis(distanceMeters: Double?, currentBucket: Int?) -> Int {
    let raw = opacityBucket(forDistanceMeters: distanceMeters)
    guard let current = currentBucket, (1...farthestBucket).contains(current),
          let distance = distanceMeters, raw != current else {
        return raw
    }
    guard abs(raw - current) == 1 else { return raw }
    let boundary = distanceBucketBoundariesMeters[min(raw, current) - 1]
    let margin = boundary * hysteresisFraction
    if raw < current {
        return distance <= boundary - margin ? raw : current
    }
    return distance >= boundary + margin ? raw : current
}
```

**Android claims, one-for-one:** `null` distance → farthest bucket; strict `<` boundary comparison; hysteresis only for ADJACENT moves with a valid `currentBucket in 1..farthestBucket` and non-null distance — invalid/missing current bucket, equal raw, or 2+ bucket jumps return raw immediately. Shared boundary = `boundaries[min(raw, current) - 1]`; margin = `boundary * 0.1`; moving nearer requires `distance <= boundary - margin` (inclusive), moving farther requires `distance >= boundary + margin` (inclusive).

## B6. Opacity resolution

**iOS** (`SeekFogModel.swift:163-167@c1745e8`):

```swift
static func opacity(forBucket bucket: Int, isHalo: Bool) -> Double {
    if isHalo { return haloOpacity }
    guard bucket >= 1 else { return dissolvedOpacity }
    return bucketOpacities[min(bucket, bucketOpacities.count) - 1]
}
```

**Android:** identical: halo wins first (0.12), bucket < 1 → 0.0, else `bucketOpacities[min(bucket, 5) - 1]` (defensive clamp above 5).

## B7. Layer id vocabulary

**iOS** (`SeekFogModel.swift:180-182@c1745e8`): `static func fogCircleID(forClearingIndex index: Int) -> String { "seek-fog-\(index)" }` and (`PilgrimMapView+SeekFog.swift:283-285@c1745e8`) `fogSourceID(for circleID: String) -> String { "\(circleID)-source" }`.

**Android:** `fogCircleId(index) = "seek-fog-$index"` (domain, it names the state entries), `fogSourceId(circleId) = "$circleId-source"` (renderer file). Circle layer ids double as Mapbox layer ids — a clearing's index is stable for the walk (reroll keeps indices; geometry change is caught by the recreate branch, B12).

## B8. Renderer bookkeeping

**iOS** (`PilgrimMapView+SeekFog.swift:12-40@c1745e8`):

```swift
final class SeekFogRenderer {
    var pendingState: SeekFogState?
    var lastAppliedState: SeekFogState?
    var appliedCircles: [String: SeekFogState.FogCircle] = [:]
    var hasDeferredUpdate = false
    var lastHandledPulseToken = 0
    ... // wisp fields — U7
    /// A style reload wipes every layer; forget what was applied so the next
    /// pass reinstalls from scratch.
    func resetForStyleReload() {
        lastAppliedState = nil
        appliedCircles = [:]
        ...
    }
}
```

**Android:** `class SeekFogRenderer internal constructor(style: SeekFogStyle)` holds the same five fields (wisp fields arrive in U7) plus `resetForStyleReload()`. On Android the class also OWNS the apply/reinstall/flush logic (iOS spreads it across static `PilgrimMapView` extension funcs) so the whole state machine is JVM-testable against a fake style surface (D5).

## B9. Rendering constants

**iOS** (`PilgrimMapView+SeekFog.swift:46-61@c1745e8`):

```swift
enum SeekFogRendering {
    // Fixed palette values (light-mode fog/dawn) — adaptive named colors
    // invert in dark mode and become bright halos on the map.
    static let fogColor = UIColor(hex: "#8A8175")
    static let haloColor = UIColor(hex: "#C4956A")
    static let fogTransitionDuration: TimeInterval = 1.5
    static let fogBlur = 1.0
    static let ringLayerID = "seek-pulse-ring"
    static let ringSourceID = "seek-pulse-ring-source"
    static let ringTransitionDuration: TimeInterval = 1.2
    static let ringStartRadiusPixels = 12.0
    static let ringEndRadiusPixels = 80.0
    static let ringStartOpacity = 0.45
    static let ringBlur = 0.6
    static let metersPerPixelEquatorZ0 = 78271.517
}
```

**Android:** identical values in `SeekFogRendering` (internal object). Fog `#8A8175` and halo `#C4956A` are FIXED hexes on purpose — they must not flip with dark theme (iOS comment: adaptive colors invert on the map and become bright halos), so the renderer never reads a theme; the composable passes explicit params only (project rule: never `isSystemInDarkTheme` in map code — and here even the passed dark flag must NOT retint fog).

## B10. Apply entry — pause queue + pulse swallow

**iOS** (`PilgrimMapView+SeekFog.swift:63-91@c1745e8`):

```swift
static func applySeekFog(_ state: SeekFogState?, pulse: SeekPulseVisual, ...) {
    let renderer = coordinator.seekFogRenderer
    renderer.pendingState = state
    guard coordinator.shouldRender else {
        // Pulses are moments, not state: swallow tokens seen while paused
        // so stale rings never fire on resume. Fog state is queued and
        // flushed instead, like deferred route updates.
        renderer.lastHandledPulseToken = pulse.token
        if renderer.lastAppliedState != state {
            renderer.hasDeferredUpdate = true
        }
        return
    }
    applySeekFogNow(state, on: mapView, renderer: renderer)
    if pulse.token != renderer.lastHandledPulseToken {
        renderer.lastHandledPulseToken = pulse.token
        if state != nil {
            fireSeekPulseRing(on: mapView)
            flareSeekWisp(...)   // U7
        }
    }
}
```

**Android claims:** `apply(state, pulse, reduceMotion, lightColorArgb)` records `pendingState` first; when the style surface reports not-ready, it swallows the pulse token (`lastHandledPulseToken = pulse.token`, NO ring on flush) and marks `hasDeferredUpdate` only if the state actually differs from last applied; otherwise it applies now and fires the ring exactly once per token advance, only with a non-null state. **D2:** iOS's pause condition is `coordinator.shouldRender` (app background / meditation display-link pause). Android's `PilgrimMap` has no rendering-pause mechanism today, so the ported pause condition is `!style.isStyleLoaded()` (the style-load window plus theme-flip reloads). The queue + flush + swallow machinery is ported in full and unit-tested so a future pause hook (U9 or later) only has to widen the condition. **D7:** the ring's color is a parameter (`lightColorArgb`) — iOS computes it at fire time from solar elevation (`PilgrimMapView+SeekWisp.swift:132-144@c1745e8`, `seekLightColor`); Android receives it from the composable (U7's `SeekSkyLight` will compute it; U6 defaults to golden `#C4956A`, "the seek's home light").

## B11. Style-reload reinstall + deferred flush

**iOS** (`PilgrimMapView+SeekFog.swift:95-115@c1745e8`):

```swift
/// Called from `onStyleLoaded` (weak-captured there, AF70): the fresh
/// style has no seek layers, so reinstall from the pending state.
static func reinstallSeekFog(on mapView: MBMapView, coordinator: Coordinator) {
    let renderer = coordinator.seekFogRenderer
    renderer.resetForStyleReload()
    guard coordinator.shouldRender else {
        if renderer.pendingState != nil { renderer.hasDeferredUpdate = true }
        return
    }
    applySeekFogNow(renderer.pendingState, on: mapView, renderer: renderer)
}

static func flushDeferredSeekFog(on mapView: MBMapView, coordinator: Coordinator) {
    let renderer = coordinator.seekFogRenderer
    guard renderer.hasDeferredUpdate else { return }
    // Keep the flag while the style is still loading — clearing it before
    // a guaranteed apply silently drops the deferred update.
    guard mapView.mapboxMap.isStyleLoaded else { return }
    renderer.hasDeferredUpdate = false
    applySeekFogNow(renderer.pendingState, on: mapView, renderer: renderer)
}
```

**Android:** `onStyleReloaded(reduceMotion)` = reset + reinstall from pending (invoked from `PilgrimMap`'s `loadStyle` success callback, AFTER the annotation managers are recreated so the route layer exists for the below-insert, B15); `flushDeferred(reduceMotion)` keeps the flag while the style is loading (same silent-drop guard). iOS wires `flushDeferredSeekFog` from the render-resume path (`PilgrimMapView.swift:652-654@c1745e8`); Android has no such resume today (D2) — the function ships tested, unwired.

## B12. Apply-now — equality early-return + self-heal probe (a0624d0)

**iOS** (`PilgrimMapView+SeekFog.swift:117-170@c1745e8`):

```swift
private static func applySeekFogNow(...) {
    guard mapView.mapboxMap.isStyleLoaded else { return }
    // Equality early-return (AF20): updateUIView runs on every body
    // evaluation; fog rarely changes. `nil == nil` also keeps the whole
    // seek path from ever touching the style on wander walks.
    if renderer.lastAppliedState == state {
        // Trust, but verify: a lock/unlock cycle can strip runtime layers
        // without any style event (field-confirmed on the SE 3), leaving
        // the bookkeeping claiming fog that no longer exists. One layer
        // probe per pass keeps the map self-healing.
        guard let firstCircle = state?.circles.first,
              !mapView.mapboxMap.layerExists(withId: firstCircle.id) else { return }
        renderer.resetForStyleReload()
    }
    guard let state else {
        for id in renderer.appliedCircles.keys { removeFogCircle(id: id, from: mapView) }
        removeRingLayer(from: mapView)
        ...
        renderer.appliedCircles = [:]
        renderer.lastAppliedState = nil
        return
    }
    ...
    for circle in state.circles {
        syncFogCircle(circle, previous: renderer.appliedCircles[circle.id],
                      tintHex: state.tintHex, on: mapView)
        applied[circle.id] = circle
    }
    for id in renderer.appliedCircles.keys where applied[id] == nil {
        removeFogCircle(id: id, from: mapView)
    }
    ...
    renderer.appliedCircles = applied
    renderer.lastAppliedState = state
}
```

**Android claims, one-for-one:**
- Style-not-loaded guard first.
- Equality early-return on the WHOLE state; `null == null` short-circuits before any style read beyond the load check — wander walks never touch the style.
- **Self-heal (a0624d0):** even on unchanged state, one `layerExists(firstCircle.id)` probe per pass; missing layer → `resetForStyleReload()` + fall through to full reinstall. Null/empty state skips the probe (nothing to heal). Rationale ported verbatim: a lock/unlock cycle can strip runtime layers with NO style event; the probe keeps the map self-healing. Android assumes the same class of risk (plan U6) — the probe is one boolean read per pass.
- Null-state branch removes every applied circle + the ring, clears bookkeeping.
- Diff pass: sync each circle against `appliedCircles[id]`; remove ids no longer present; store `applied` + `lastAppliedState`.

**Sync-per-circle** (`PilgrimMapView+SeekFog.swift:172-193@c1745e8`):

```swift
guard let previous else { installFogCircle(...); return }
guard previous != circle else { return }
if previous.center == circle.center,
   previous.radiusMeters == circle.radiusMeters,
   previous.isHalo == circle.isHalo {
    setFogOpacity(circle, on: mapView)
} else {
    // Geometry or role changed (reroll, fog → halo): recreate so the
    // entrance-at-zero write below fades the new circle in.
    removeFogCircle(id: circle.id, from: mapView)
    installFogCircle(circle, tintHex: tintHex, on: mapView)
}
```

**Android:** identical three-way: new → install; identical → no write; opacity-only change → single `setFogOpacity`; geometry/role change (reroll, fog→halo) → remove + reinstall (re-entering the created-at-zero fade-in). Note (matches iOS): a tint-only change on an unchanged circle does NOT retint an installed layer — the tint is fixed per walk by design, so this path is unreachable in practice.

## B13. Install — transitions set once, entrance at zero

**iOS** (`PilgrimMapView+SeekFog.swift:195-233@c1745e8`):

```swift
// Transitions are set once at creation; every later opacity write is
// GPU-eased by them — no timers. Reduce Motion drops them to instant.
let duration = UIAccessibility.isReduceMotionEnabled ? 0 : SeekFogRendering.fogTransitionDuration
...
var layer = CircleLayer(id: circle.id, source: fogSourceID(for: circle.id))
layer.circleColor = .constant(StyleColor(circle.isHalo ? SeekFogRendering.haloColor : fogColor))
layer.circleBlur = .constant(SeekFogRendering.fogBlur)
layer.circlePitchAlignment = .constant(.map)
layer.circleStrokeWidth = .constant(0)
layer.circleRadius = .expression(fogRadiusExpression(...))
layer.circleOpacity = .constant(0)
layer.circleOpacityTransition = StyleTransition(duration: duration, delay: 0)
layer.circleBlurTransition = StyleTransition(duration: duration, delay: 0)
try mapView.mapboxMap.addLayer(layer, layerPosition: fogLayerPosition(on: mapView))
// Entrance: created at opacity 0, target written in the same
// update pass — the opacity transition renders the fade-in.
try mapView.mapboxMap.setLayerProperty(for: circle.id, property: "circle-opacity",
    value: SeekFogModel.opacity(forBucket: circle.opacityBucket, isHalo: circle.isHalo))
```

**Android claims:** one point `GeoJsonSource` + one `CircleLayer` per clearing; halo color `#C4956A`, fog color = `tintHex ?: "#8A8175"`; blur `1.0`; `CirclePitchAlignment.MAP`; stroke width 0; zoom-interpolated radius expression (B14); `circle-opacity` DECLARED 0 with `circleOpacityTransition`/`circleBlurTransition` of 1500 ms (0 under reduce-motion) set ONCE at creation, then the bucket-resolved target written via `setStyleLayerProperty` in the same pass — the transition renders the fade-in, no timers. **D6:** reduce-motion arrives as a composable param (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f`, the `WalkSummaryScreen` pattern), not read inside the renderer. **D8 (defensive divergence):** Android's install removes any pre-existing layer/source with the same ids first — under Compose, `apply` can legally run between the style finishing loading and the `loadStyle` callback's `onStyleReloaded` reset, and a second `addSource` with a live id is a hard error on Android where iOS merely logged the throw. Idempotent install closes the window; behavior is otherwise identical.

## B14. Geographic sizing — radius expression

**iOS** (`PilgrimMapView+SeekFog.swift:260-274@c1745e8`):

```swift
/// Geographic sizing: circle-radius is in pixels, so interpolate
/// exponentially (base 2) over zoom from the meters-per-pixel scale at
/// z0 (78271.517·cos(lat)) up to z20 at ×2²⁰.
private static func fogRadiusExpression(radiusMeters: Double, latitude: Double) -> Exp {
    let metersPerPixelAtZ0 = SeekFogRendering.metersPerPixelEquatorZ0 * cos(latitude * .pi / 180)
    let radiusPixelsAtZ0 = radiusMeters / metersPerPixelAtZ0
    return Exp(.interpolate) {
        Exp(.exponential) { 2.0 }
        Exp(.zoom)
        0
        radiusPixelsAtZ0
        20
        radiusPixelsAtZ0 * pow(2.0, 20.0)
    }
}
```

**Android:** same math via `Expression.interpolate { exponential(2.0); zoom(); stop { literal(0.0); literal(r0) }; stop { literal(20.0); literal(r0 * 2^20) } }`. The scalar `fogRadiusPixelsAtZoomZero(radiusMeters, latitudeDegrees)` is an `internal` pure function with JVM tests (equator identity, cos(lat) scaling, symmetry across hemispheres).

## B15. Layer ordering

**iOS** (`PilgrimMapView+SeekFog.swift:276-281@c1745e8`):

```swift
/// Fog sits under the route line so the walked path stays legible.
private static func fogLayerPosition(on mapView: MBMapView) -> LayerPosition? {
    mapView.mapboxMap.layerExists(withId: "pilgrim-route-casing")
        ? .below("pilgrim-route-casing")
        : nil
}
```

**Android decision (D4):** Android's route is not a hand-built casing+line layer pair — it is a `PolylineAnnotationManager` (`PilgrimMap.kt` loadStyle block). The manager's backing `LineLayer` now carries an EXPLICIT id, `"pilgrim-route-line"`, via `AnnotationConfig(layerId = ROUTE_LINE_LAYER_ID)` — the direct analogue of iOS's named `"pilgrim-route-casing"` — and the fog inserts `below("pilgrim-route-line")`, verified with `styleLayerExists` at install time; missing → add at top (matching iOS's nil-`LayerPosition` fallback). (`AnnotationManagerImpl.associatedLayers` was rejected: it is `@RestrictTo(LIBRARY_GROUP_PREFIX)` and trips lint's RestrictedApi error.) This also lands the fog below every later-created annotation layer (meditation circles, waypoints, pins) since those managers are created after the polyline manager and stack above it. The location puck sits BELOW the fog (the puck layer is enabled before the polyline manager exists — same relative order iOS ends up with, where fog goes immediately below the route group and the puck was installed earlier); acceptable because the walker stands inside a circle only when it is a 0.12 halo or dissolved. Not chosen: inserting below `mapbox-location-indicator-layer` — it would put fog under the puck but also depends on an undocumented layer id and diverges from the iOS anchor (the route).

## B16. Pulse ring

**iOS** (`PilgrimMapView+SeekFog.swift:287-341@c1745e8`):

```swift
/// One-shot: recreate the ring at the puck with small/visible initial
/// paint (initial values don't transition), then immediately write
/// large/transparent — the layer's StyleTransitions ease it out on the
/// GPU. No timers, no display link, no repeatForever.
private static func fireSeekPulseRing(on mapView: MBMapView) {
    guard mapView.mapboxMap.isStyleLoaded,
          !UIAccessibility.isReduceMotionEnabled,
          let coordinate = mapView.location.latestLocation?.coordinate else { return }
    removeRingLayer(from: mapView)
    ...
    // The ring is recreated per pulse, so it picks up the hour's
    // light (and any theme change) on the very next heartbeat.
    var layer = CircleLayer(id: SeekFogRendering.ringLayerID, source: SeekFogRendering.ringSourceID)
    layer.circleColor = .constant(StyleColor(seekLightColor(on: mapView)))
    layer.circleBlur = .constant(SeekFogRendering.ringBlur)
    layer.circleStrokeWidth = .constant(0)
    layer.circleRadius = .constant(SeekFogRendering.ringStartRadiusPixels)
    layer.circleOpacity = .constant(SeekFogRendering.ringStartOpacity)
    layer.circleRadiusTransition = StyleTransition(duration: SeekFogRendering.ringTransitionDuration, delay: 0)
    layer.circleOpacityTransition = StyleTransition(duration: SeekFogRendering.ringTransitionDuration, delay: 0)
    try mapView.mapboxMap.addLayer(layer)
    try mapView.mapboxMap.setLayerProperty(... "circle-radius", value: ringEndRadiusPixels)
    try mapView.mapboxMap.setLayerProperty(... "circle-opacity", value: 0.0)
}
```

**Android claims:** one-shot recreate per pulse token; initial paint 12 px radius / 0.45 opacity / blur 0.6 / stroke 0 with 1.2 s radius+opacity transitions, then immediately written to 80 px / 0.0 — the writes ride the transitions, initial values don't transition. Added with NO layer position (top of stack, above the route — iOS parity). Colored by the caller-provided hour-light color (D7). SKIPPED entirely under reduce-motion (renderer-level guard, before any style write). **D3:** iOS anchors the ring at `mapView.location.latestLocation`; Android's `LocationComponentPlugin` has no synchronous position getter, so `PilgrimMap` registers an `OnIndicatorPositionChangedListener` (only while fog is active — wander walks pay nothing) and the style surface caches the latest puck point; a null cached point skips the ring (iOS parity: nil `latestLocation` skips).

## B17. Error handling

**iOS** wraps every style mutation in `do/catch` + `print` (`PilgrimMapView+SeekFog.swift:230-232,242-244,255-257,325-327,338-340@c1745e8`) — a failed fog write must never crash a walk. **Android:** the Mapbox surface logs `Expected`-style failures via `Log.w("PilgrimMap", ...)` and swallows layer/source exceptions the same way (fog is decorative; the walk pipeline must not feel it).

## Divergence table

| # | iOS @c1745e8 | Android U6 | Why |
|---|---|---|---|
| D1 | `Wisp` field + `wispPoint` + `wispSpanDegreesNearToFar` + `SeekSkyLight` + `SeekWispVisibilityModel` live in `SeekFogModel.swift`; crescent renderer in `PilgrimMapView+SeekWisp.swift` | Not ported in U6; U7 ports as `SeekCrescentModel`/`SeekSkyLight`/`SeekCrescentRenderer`, adding a `crescent` field to `SeekFogState` | Plan unit boundary (U6 fog+ring, U7 crescent); wisp→crescent naming rule |
| D2 | Pause condition = `coordinator.shouldRender` (background/meditation display-link pause); flush wired from render resume | Pause condition = `!isStyleLoaded()`; `flushDeferred` ships tested but unwired | Android `PilgrimMap` has no render-pause mechanism today; machinery ported for U9+ |
| D3 | Ring anchored at `mapView.location.latestLocation` | `OnIndicatorPositionChangedListener` caches the puck point while fog is active | No synchronous puck-position getter in Mapbox Android 11.11.0 |
| D4 | Fog below `"pilgrim-route-casing"` style layer | Route layer explicitly named `"pilgrim-route-line"` via `AnnotationConfig`; fog inserts below it | Android route is an annotation manager; `associatedLayers` is a restricted API |
| D5 | Static `PilgrimMapView` extension funcs touch `mapboxMap` directly | Renderer state machine behind internal `SeekFogStyle` interface + `MapboxSeekFogStyle` impl | JVM-testability of equality gate / deferred queue / self-heal / pulse swallow; Mapbox surface is device-verified |
| D6 | `UIAccessibility.isReduceMotionEnabled` read at install/fire time | `reduceMotion` param threaded from the composable (`ANIMATOR_DURATION_SCALE` pattern) | Project convention — no global reads in map renderers |
| D7 | Ring color computed at fire time via `seekLightColor(on:)` (solar elevation) | `lightColorArgb` param, default golden `#C4956A` | `SeekSkyLight` is U7 scope; parameter keeps U6 free of celestial deps |
| D8 | `installFogCircle` adds source/layer directly; failure logged | Install removes same-id layer/source first (idempotent) | Compose timing can apply between style-load completion and the reload callback; duplicate-add is a hard error |

## Dispatcher note (U9 seam)

`SeekFogModel.fogState` + bucket math run per GPS fix. The plan pins the hop: **U9's orchestrator computes the fog state on `Dispatchers.Default`** and hands the renderer an immutable `SeekFogState` on the main thread; nothing in U6 does its own dispatching. The renderer's `apply` must only ever be called from the main thread (Mapbox style writes are main-thread-only).

## Device smoke-check items (renderer surface not JVM-provable)

1. Fog circle visible over the active clearing at walk start (thickest bucket), geographic size correct across zoom levels (pinch from z12→z18: circle hugs the same ground area).
2. Approach a clearing across a bucket boundary: opacity thins in eased steps (1.5 s), no flicker at the boundary (hysteresis).
3. Arrival: fog dissolves to nothing over 1.5 s; after reveal the clearing keeps a faint dawn halo for the rest of the session.
4. Unrevealed clearings show nothing anywhere on the map.
5. Pulse ring: expands+fades from the puck each heartbeat, colored by the light param; suppressed when animator duration scale is 0.
6. Theme flip (dark↔light) mid-seek: fog + halos reinstall on the new style automatically.
7. Lock/unlock mid-seek: fog still present afterwards (self-heal probe path).
8. Wander walk: zero fog layers in the style (inspect via `adb` + Mapbox debug or absence of visual artifacts).
9. Route line renders ABOVE fog; waypoint/proximity pins render above both.
