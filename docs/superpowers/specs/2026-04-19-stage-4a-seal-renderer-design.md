# Stage 4-A: Goshuin Seal Renderer — Design Spec

**Date:** 2026-04-19
**Status:** Draft (awaiting CHECKPOINT 1 approval)
**Prior art:**
- `../pilgrim-ios/Pilgrim/Models/Seal/SealRenderer.swift` (444 LoC) + `SealGenerator.swift` + `SealGeometry.swift` + `SealColorPalette.swift` + `SealHashComputer.swift`
- Our own Stage 3-C `CalligraphyPath` (pure-renderer template)
- Our own Stage 3-D `SeasonalColorEngine.Intensity.Full` (tint API)

---

## Context

The goshuin (御朱印) is a Japanese temple stamp — a dense, vermilion-inked circular seal collected as proof of pilgrimage. Pilgrim's equivalent: a procedurally-generated seal per walk, **deterministic** from the walk's inputs, so the same walk always produces the same seal. Stage 4 ships the full goshuin feature across four sub-stages:

- **4-A (this stage):** Pure renderer + debug preview. No integration into walk-finish flow.
- **4-B:** `SealReveal` screen — stamp-down animation shown post-walk.
- **4-C:** Goshuin collection grid (paginated seal gallery).
- **4-D:** Milestone celebrations (temple-bell haptic via `VibrationEffect.Composition`, optional sound).

4-A matches the Stage 3-C / 3-D / 3-E cadence: ship the pure renderer + debug preview, delete the preview when a later stage wires the renderer into production.

---

## Goals

1. **Pure `@Composable SealRenderer(spec, modifier)`** that draws a seal into a square Canvas, size-agnostic (all internal dimensions are fractions of the canvas size).
2. **Deterministic seeding.** Same `SealSpec` (uuid + startMillis + distanceMeters + durationSeconds) → same seal geometry every render.
3. **Seasonal tinting via `SeasonalColorEngine.Intensity.Full`.** Base color is `PilgrimColors.rust` (closest to vermilion in our palette); the engine shifts it by walk date + device hemisphere.
4. **Debug-reachable preview screen** behind `BuildConfig.DEBUG` (mirrors Stage 3-C's pattern), so we can eyeball seals on-device before Stage 4-B's reveal animation integrates them.
5. **Unit-testable pure math.** Hash determinism, geometry bounds (ring/line/arc/dot counts), deterministic byte → geometry.
6. **Robolectric smoke test** that exercises a real `android.graphics.Path` via `Path.getBounds()`, matching the Stage 3-C testing convention.

## Non-goals

- **Byte-for-byte iOS parity.** iOS uses SHA-256 over route + durations + date; we'll use the same FNV-1a hash from `CalligraphyStrokeSpec.kt` (uuid + startMillis + integer-meters distance) for continuity with the calligraphy renderer. Accept that the same walk renders different seals on iOS vs Android. Stage 4-C or a future parity effort can tighten if cross-platform collection matters.
- **Weather texture overlay** (rain/snow/wind scattered dots at seal edge) — iOS has this; Android defers to a later stage.
- **Ghost route visualization** (faint walk path fitted inside the seal) — needs route sample plumbing through the VM; defer.
- **Elevation ring** (altitude profile as a rippled concentric ring) — needs altitude data; defer.
- **Curved outer text** (top arc "PILGRIM · SPRING 2026", bottom arc "MORNING WALK") — Compose doesn't natively support text-on-path; manually glyph-positioning along an arc is a sub-feature; defer to 4-D or a dedicated stage.
- **Favicon-based 15-color palette.** iOS splits colors into warm/cool/accent/neutral by walk "favicon" (flame/leaf/star/nil). Android's `Walk.favicon` field exists but is unused today; for 4-A we use a single base (`rust`) shifted by the seasonal engine. Adding the 15-color palette is a future polish pass.
- **Caching.** iOS caches rendered seals as `UIImage` + disk. Android 4-A re-renders every Canvas composition. The seal is pure DrawScope primitives (~dozens of draw calls); caching is premature without a perf measurement.
- **Reveal animation, collection grid, haptics** — explicitly 4-B / 4-C / 4-D.

---

## Algorithm (ported from iOS, simplified)

### Input: `SealSpec`

```kotlin
@Immutable
data class SealSpec(
    val uuid: String,           // walk.uuid — FNV-1a seed
    val startMillis: Long,      // walk.startTimestamp — seasonal tint + seed
    val distanceMeters: Double, // haversine sum over samples — seed
    val durationSeconds: Double, // wall-clock duration — future use, currently unused in seed
    val ink: Color,             // pre-resolved vermilion-adjacent color (caller does the seasonal shift)
)
```

Mirrors `CalligraphyStrokeSpec`'s shape. Keeps the renderer pure — the VM resolves the seasonal color and hands it in.

### Hash seed: FNV-1a 64-bit

Reuse the **same function from `CalligraphyStrokeSpec.kt`**. Copy-paste for 4-A (don't extract to a shared helper yet — the third caller is the "extract" trigger, not the second). The hash output (`Long`) is converted to a **32-byte array** by splitting into 4 little-endian longs mixed via SplitMix64 (iOS uses 32 bytes too, from SHA-256). We don't have 32 bytes from one FNV-1a call, so we iterate:

```kotlin
internal fun sealHashBytes(spec: SealSpec): ByteArray {
    // Seed SplitMix64 with the FNV-1a output, extract 32 bytes.
    // iOS uses SHA-256 directly; we stretch a 64-bit hash to 256 via
    // SplitMix64 iteration so we have the same ~32 bytes of state to
    // drive the byte-by-byte geometry table.
    var state = fnv1aHash(spec).toULong()
    val bytes = ByteArray(32)
    for (i in 0 until 4) {
        state += 0x9E3779B97F4A7C15UL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9UL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBUL
        z = z xor (z shr 31)
        val longValue = z.toLong()
        for (b in 0 until 8) {
            bytes[i * 8 + b] = (longValue shr (b * 8)).toByte()
        }
    }
    return bytes
}
```

SplitMix64 is the same RNG iOS uses for its seeded weather texture; port for continuity.

Helper to grab a byte as an unsigned int in [0, 255]:
```kotlin
internal fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF
```

### Geometry: `SealGeometry`

Pure data struct built once from the 32-byte seed. All dimensions are fractions of a unit canvas `size`, converted to pixels at draw time.

```kotlin
internal data class SealGeometry(
    val rotationDeg: Float,          // 0..360
    val rings: List<Ring>,           // 3..8
    val radialLines: List<Radial>,   // 4..12
    val arcs: List<ArcSegment>,      // 2..4
    val dots: List<Dot>,             // 3..7
)

internal data class Ring(
    val radiusFrac: Float,           // fraction of outer radius, ~0.3..1.0
    val strokeWidthPx: Float,
    val opacity: Float,
    val dashPattern: FloatArray?,    // null = solid
)

internal data class Radial(
    val angleDeg: Float,
    val innerFrac: Float,            // 0.25..0.4
    val outerFrac: Float,            // 0.85..1.0
    val strokeWidthPx: Float,
    val opacity: Float,
)

internal data class ArcSegment(
    val startAngleDeg: Float,
    val sweepDeg: Float,             // 20..80
    val radiusFrac: Float,           // 0.55..0.80
    val strokeWidthPx: Float,
    val opacity: Float,
)

internal data class Dot(
    val angleDeg: Float,
    val distanceFrac: Float,         // 0.3..0.8
    val radiusPx: Float,             // 1..2
    val opacity: Float,
)

internal fun sealGeometry(spec: SealSpec): SealGeometry {
    val b = sealHashBytes(spec)
    val rotation = (b.u(0).toFloat() / 255f) * 360f
    val ringCount = (3 + b.u(1) % 3).coerceAtMost(8)
    val radialCount = (4 + b.u(8) % 5).coerceAtMost(12)
    val arcCount = 2 + b.u(24) % 3
    val dotCount = 3 + b.u(28) % 5
    // ... compute each list per iOS formulas ...
}
```

Exact formulas match iOS's `SealGeometry.swift` (quoted in the Phase 1 understanding report).

### Rendering: draw-order layers

```kotlin
@Composable
fun SealRenderer(
    spec: SealSpec,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(spec) { sealGeometry(spec) }
    Canvas(modifier = modifier.aspectRatio(1f)) {
        val size = size.minDimension
        val center = Offset(size / 2f, size / 2f)
        val outerR = size * 0.44f

        rotate(degrees = geometry.rotationDeg, pivot = center) {
            drawRings(geometry.rings, center, outerR, spec.ink)
            drawRadialLines(geometry.radialLines, center, outerR, spec.ink)
            drawArcs(geometry.arcs, center, outerR, spec.ink)
            drawDots(geometry.dots, center, outerR, spec.ink)
        }
    }
}
```

Five layers, all drawn under the global hash-derived rotation. No curved text, no ghost route, no weather texture (all deferred).

Each layer is a private `DrawScope.drawX(...)` helper — mirrors Stage 3-C's extracted `buildRibbonPath` for testability: we can test the Path construction directly.

### Color resolution

**In the renderer:** `spec.ink` is pre-resolved.

**In the preview VM / future call-sites:** use `SeasonalColorEngine.applySeasonalShift(base = pilgrimColors.rust, intensity = Intensity.Full, date = walkDate, hemisphere = hemisphere)`.

`Intensity.Full` gives the full seasonal shift (winter palette is noticeably bluer/desaturated; summer warmer/saturated). This matches iOS's "different seasons feel visually different" intent even though iOS uses a different mechanism (text labels + 15-color palette).

---

## Architecture

### Package layout

New subpackage `ui/design/seals/` parallel to `ui/design/calligraphy/`:

```
ui/design/seals/
├── SealSpec.kt             // data class + FNV-1a hash + sealHashBytes
├── SealGeometry.kt         // internal data + sealGeometry(spec) builder
├── SealRenderer.kt         // @Composable + DrawScope layer helpers
├── SealPreview.kt          // debug screen
└── SealPreviewViewModel.kt // Hilt VM
```

Matches the Stage 3-C layout exactly.

### Public API

```kotlin
@Composable
fun SealRenderer(
    spec: SealSpec,
    modifier: Modifier = Modifier,
)

@Immutable
data class SealSpec(
    val uuid: String,
    val startMillis: Long,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val ink: Color,
)

// Extension on Walk (like CalligraphyStrokeSpec's `toStrokeSpec`):
fun Walk.toSealSpec(samples: List<RouteDataSample>, ink: Color): SealSpec
```

### Preview screen

Hilt-injected VM observes `WalkRepository.observeAllWalks()`, filters to finished walks, maps each to a `SealSpec` (with placeholder `Color.Transparent` ink; the Composable resolves the real color via `SeasonalColorEngine`). Falls back to eight synthetic seals spanning the year if no walks exist.

The preview screen lays out seals in a simple vertical `Column + verticalScroll + forEach`, each wrapped in a 200dp sized `Box` so the renderer has a stable size. Supports eyeballing 8 synthetic seals side-by-side to verify determinism + seasonal variation.

### Debug button + nav route

Mirrors Stage 3-C:
- `Routes.SEAL_PREVIEW = "seal_preview"`, registered under `if (BuildConfig.DEBUG) composable(...)`
- `HomeScreen` gets an `onEnterSealPreview: () -> Unit` parameter + `if (BuildConfig.DEBUG) TextButton("Seal preview (debug)")` below the existing BatteryExemptionCard
- `PilgrimNavHost` callback also gated: `if (BuildConfig.DEBUG) navController.navigate(Routes.SEAL_PREVIEW) { launchSingleTop = true }` — defense-in-depth per Stage 3-C's pattern

---

## Data flow

```
Walk + samples
  ↓ (VM mapToSpec)
SealSpec(ink = Color.Transparent placeholder)
  ↓ (preview VM exposes List<PreviewSeal(spec, walkDate)>)
SealPreviewScreen
  ↓ (reads pilgrimColors.rust + hemisphere via Compose)
  ↓ SeasonalColorEngine.applySeasonalShift(rust, Full, walkDate, hemisphere)
SealSpec(ink = shifted Color)
  ↓
SealRenderer
  ↓ (Canvas)
DrawScope.drawRings / drawRadialLines / drawArcs / drawDots
```

No Flow-based reactivity inside the renderer. Preview VM uses `observeAllWalks().map { ... }.stateIn(WhileSubscribed)` pattern from Stage 3-C.

---

## Error handling / edge cases

| Edge case | Behavior |
|---|---|
| Empty walks list on preview | Falls through to `synthetic()` — 8 seals spanning the year with fake uuids |
| `distanceMeters = 0` | Hash still works (distance is one of three seed ingredients; zero doesn't break the algorithm). Geometry is deterministic. |
| Walk with no route samples (fresh finish, DB hasn't caught up) | `locationSamplesFor` returns empty; `walkDistanceMeters` returns 0; seal still renders. Visually: the renderer doesn't use distance in any draw decision (hash only), so the seal looks identical to a same-uuid walk with distance. Acceptable. |
| `durationSeconds = 0` (instant walk) | Same — hash input only, no draw-time dependency. |
| Canvas size = 0 | `drawRings` etc. compute radii as `size * 0.44`; zero → no draws. Canvas contributes no pixels. No NaN. |
| Very tall / non-square Box | `Modifier.aspectRatio(1f)` forces square. If the outer constraint is unbounded in one direction, Compose layout throws — the caller is responsible for constraining with `.size(...)` or `.fillMaxWidth()`. |
| Theme switch mid-view | `spec.ink` is a value-class Color; when the theme changes, pilgrimColors updates, `remember(rows, hemisphere)` in the preview invalidates, new specs produced with new inks. Renderer re-renders. |
| Very small canvas (thumbnail 80dp) | All dimensions scale proportionally. Sub-pixel rounding may collapse thin strokes. Acceptable for 4-A; 4-C collection grid will QA thumbnail legibility. |

---

## Testing strategy

**Pure-JVM unit tests** (`app/src/test/.../ui/design/seals/`):

- `SealHashTest.kt`
  - `fnv1aHash(spec)` determinism — same SealSpec → same hash
  - `sealHashBytes(spec)` returns 32 bytes, deterministic
  - Different specs → different byte arrays
  - `ByteArray.u(i)` returns in [0, 255]

- `SealGeometryTest.kt`
  - Ring count in [3, 8]
  - Radial line count in [4, 12]
  - Arc count in [2, 4]
  - Dot count in [3, 7]
  - `rotationDeg` in [0, 360)
  - Same SealSpec → identical SealGeometry (byte-wise)
  - Different uuids → different rotations (high likelihood; sample 100 random specs and check variance > 0)

**Robolectric smoke test** (`app/src/test/.../ui/design/seals/SealRendererTest.kt`):
- Composition smoke with `createComposeRule()` + sized `Box`: renders empty / single / 8-spec lists without crashing
- **Direct Path construction test**: extract a `buildRingPath(radius, strokeWidth, dashPattern): Path` helper from the Canvas draw lambda, call it directly with synthetic numeric args, assert `path.getBounds()` has non-zero bounds and matches expected radius-based dimensions

No preview-VM test. The VM is debug-only (deleted when 4-B/4-C lands) and 4-A preview-VM tests would be cargo-culted from the Stage 3-C/3-D previews we deleted.

---

## Rejected alternatives

1. **Direct port of iOS's SHA-256 + 32-byte hash.** Would give cross-platform seal parity. Rejected: adds a crypto dependency (or `java.security.MessageDigest` juggling), and we already accepted FNV-1a divergence for calligraphy. Consistent divergence story across the port.

2. **Full port of all 9 iOS layers** (weather + ghost route + elevation + rings + lines + arcs + dots + curved outer text + center text). Would match iOS visually. Rejected: 400+ LoC of renderer code is a scope blowup for a single autopilot run. Ship the structural 5 layers (rings, lines, arcs, dots) + Stage 4-D can add the decorative layers. Curved text alone (top + bottom inscriptions) is a multi-day task because Compose has no native text-on-path primitive.

3. **`Canvas` with `Modifier.size(size)` instead of `aspectRatio(1f)`.** Would force a specific pixel size on callers. Rejected: caller should choose sizing (200dp preview vs 80dp collection thumbnail vs 320dp reveal). `aspectRatio(1f)` lets callers set width however they want.

4. **Extract FNV-1a to a shared `ui/design/Fnv1aHash.kt`.** Makes both calligraphy + seal reuse one impl. Rejected for now: the "third caller" heuristic says wait. Copy is fine. Revisit when a 4-B or 4-C caller appears.

5. **Use `android.graphics.Color.HSVToColor` to derive vermilion directly** (hard-coded red-orange). Would produce a brighter, more stereotypically Japanese-seal color. Rejected: breaks with dark mode (no light/dark variant), bypasses the seasonal engine, and muted `rust` + full seasonal shift is more aesthetic-consistent with the rest of Pilgrim's palette.

6. **Cache rendered seals as `ImageBitmap`.** Big perf win if the collection grid shows 100+ seals. Rejected for 4-A: the renderer is pure DrawScope — dozens of draw calls, not thousands. Cache when 4-C measurements say we need to.

7. **Include center text (distance + unit) in 4-A.** Would make the seal feel "complete". Partially rejected: I'll include center text because it's cheap (one `drawText` call via `Canvas.nativeCanvas`) and gives the debug preview more signal. Curved outer text stays deferred.

**Decision update:** include center text in 4-A after all. Revised below.

### Revised center text inclusion

The seal will include a single center-text layer — distance + "km" unit — rendered via `DrawScope.drawIntoCanvas { canvas.nativeCanvas.drawText(...) }`. Fonts are PilgrimFonts.cormorantGaramond (distance) + PilgrimFonts.lato (unit), both already loaded by Stage 3-B. Center text is typography, not a procedural element; its position and size are fixed fractions of the canvas, not hash-driven.

Revised file count:
- `SealSpec.kt` adds a `displayDistance: String` field (pre-formatted by the caller, like the other text fields on HomeWalkRow)
- `SealRenderer.kt` adds a `drawCenterText` helper

This keeps the seal visually identifiable without dragging in curved text's complexity.

---

## Scope & estimate

- 5 new production files (~450 LoC combined — ~60 LoC each for Spec/Geometry, ~200 for Renderer + text, ~100 for preview screen + VM)
- 3 new test files (~250 LoC combined)
- 2 files modified: HomeScreen (add debug button), PilgrimNavHost (add route)

Estimate: medium stage. Slightly larger than Stage 3-C because the seal geometry has more dimensions than the calligraphy path, but the preview screen + VM + debug button are copy-paste from 3-C's template.

---

## Forward-carry for 4-B / 4-C / 4-D

- **4-B (reveal animation)** gets a ready-to-use `SealRenderer(spec, modifier)` Composable. Wraps it in the stamp-down animation (scale 1.2 → 0.95 → 1.0, opacity 0 → 1, shadow on reveal). Haptic on touch-down.
- **4-C (collection grid)** calls `SealRenderer(spec, Modifier.size(80.dp))` inside a LazyGrid cell. No caching in 4-A; 4-C will add `ImageBitmap` caching if scroll perf suffers.
- **4-D (milestone celebrations)** uses `VibrationEffect.Composition` independently; doesn't touch the renderer. Also introduces the deferred iOS-style 15-color palette + curved outer text inscription if on-device feedback demands it.

---

## Open questions for checkpoint

- None blocking. The design inherits Stage 3-C's template wholesale. The big choice points (FNV-1a vs SHA-256, 5 layers vs 9 layers, seasonal engine vs 15-color palette) are all explicitly documented and explained. Approve, revise, or reject.
