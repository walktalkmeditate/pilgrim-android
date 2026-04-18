# Stage 3-C: Calligraphy Path Renderer — Design Spec

**Date:** 2026-04-18
**Status:** Draft (awaiting CHECKPOINT 1 approval)
**Prior art:** `../pilgrim-ios/Pilgrim/Scenes/Home/CalligraphyPathRenderer.swift`

---

## Context

The iOS app decorates its "journal" (Home tab) with a continuous, variable-width ink line that threads through a stack of walk cards. Each walk contributes one segment; segments stitch into a river-like ink stroke that meanders horizontally, narrows as walks age, and tints by season. The visual is key to Pilgrim's wabi-sabi identity — the walks form a living scroll, not a flat list.

Stage 3-A shipped the walk list with basic Card rows. Stage 3-E (future) will integrate the renderer into the Home scroll. **Stage 3-C's job is the renderer itself + a preview harness. Nothing on the Home screen changes yet.**

---

## Goals

1. Pure-Kotlin port of the iOS calligraphy algorithm with byte-compatible determinism (same inputs → same ink shape).
2. A reusable `@Composable fun CalligraphyPath(...)` that draws the full thread into a Canvas given a list of per-walk stroke specs.
3. A debug-reachable preview screen (behind a button on HomeScreen, removed in 3-E) so we can eyeball the render on-device without waiting for 3-E.
4. Unit-testable pure math: hash determinism, pace→width curve, taper, Y-layout.

## Non-goals

- **Integration into HomeScreen's walk list** — that's Stage 3-E.
- **Full seasonal HSB shift** — color per stroke is resolved by the *caller*, not the renderer. The renderer is pure drawing. A basic month→colorName mapping helper ships here; the perceptual HSB adjustment lands in Stage 3-D.
- **Lunar / milestone markers** — 3-C draws the ink stroke only. Dots, moon markers, and milestone icons are separate overlays that follow in later stages.
- **Cross-walk animation, ripple effects, idle breathing** — not this stage.
- **LazyColumn integration** — the preview uses a scrolling Column. 3-E will figure out the LazyColumn interop (each walk card needs to align to its dot position).

---

## Algorithm (verbatim port from iOS)

### Inputs — `CalligraphyStrokeSpec`

Everything the renderer needs about a single walk. The caller pre-computes these from the Walk + samples.

```kotlin
@Immutable
data class CalligraphyStrokeSpec(
    val uuid: String,                  // walk.uuid — used as hash seed
    val startMillis: Long,             // walk.startTimestamp — hash seed + seasonal tint
    val distanceMeters: Double,        // haversine sum over samples — hash seed + future use
    val averagePaceSecPerKm: Double,   // computed: durationActiveSec / (distanceMeters / 1000)
    val ink: Color,                    // resolved ink color (caller picks by season; defaults to PilgrimColors.ink)
)
```

**Why pre-computed?** Keeps the renderer testable in isolation. The VM layer handles "what's the walk's pace" or "what color is October moss" — the renderer just draws.

### Geometry — segments as filled polygons between TWO parallel cubic Béziers

This is the subtle part of the iOS algorithm worth calling out. The ink stroke is NOT a single `drawPath(stroke=...)` with variable width — it's a **filled region** bounded by two cubic Béziers offset by `±halfWidth` in X.

For each consecutive pair of dots `(start, end)`:

```
cpOffset = seed(i) * maxMeander * 0.4         // horizontal meander, deterministic
midY     = (start.y + end.y) / 2
cp1      = (start.x + cpOffset, midY - verticalSpacing * 0.2)
cp2      = (end.x   - cpOffset, midY + verticalSpacing * 0.2)

leftStart  = (start.x - halfWidth, start.y)   // upper-left edge of the ribbon
leftCP1    = (cp1.x   - halfWidth, cp1.y)
leftCP2    = (cp2.x   - halfWidth, cp2.y)
leftEnd    = (end.x   - halfWidth, end.y)

rightStart = (start.x + halfWidth, start.y)
rightCP1   = (cp1.x   + halfWidth, cp1.y)
rightCP2   = (cp2.x   + halfWidth, cp2.y)
rightEnd   = (end.x   + halfWidth, end.y)

path.moveTo(leftStart)
path.cubicTo(leftCP1,  leftCP2,  leftEnd)
path.lineTo(rightEnd)
path.cubicTo(rightCP2, rightCP1, rightStart)   // reversed — walk back
path.close()
```

Fill the path with `ink` color + opacity, no stroke. Do NOT apply blur — iOS uses a 0.6pt Gaussian that we're skipping for MVP (Android Compose's blur is more expensive and less necessary at DPI=2–3).

### Width curve

```kotlin
fun segmentWidth(index: Int, total: Int, pace: Double): Float {
    val paceWidth = if (pace > 0) {
        val clamped = pace.coerceIn(300.0, 900.0)     // 4–12 km/h
        val t = (clamped - 300.0) / (900.0 - 300.0)   // 0..1
        baseWidthPx + (t * (maxWidthPx - baseWidthPx)).toFloat()
    } else {
        baseWidthPx
    }
    if (total <= 1) return paceWidth
    // Taper: oldest walk (index=total-1) renders at 60% of its pace-derived width.
    val taper = 1f - (index.toFloat() / (total - 1).toFloat()) * 0.4f
    return paceWidth * taper
}
```

Constants: `baseWidthPx = 1.5.dp.toPx()`, `maxWidthPx = 4.5.dp.toPx()`.

### Meander (deterministic hash)

Port of Swift's FNV-1a 64-bit with the same constants:

```kotlin
private const val FNV_OFFSET_BASIS: ULong = 14695981039346656037UL
private const val FNV_PRIME:        ULong =      1099511628211UL

fun fnv1aHash(spec: CalligraphyStrokeSpec): Long {
    var h = FNV_OFFSET_BASIS
    spec.uuid.forEach { c ->                 // iterate UTF-16 code units
        h = (h xor c.code.toULong()) * FNV_PRIME
    }
    spec.startMillis.toULong().let { h = (h xor it) * FNV_PRIME }
    spec.distanceMeters.toLong().toULong().let { h = (h xor it) * FNV_PRIME }
    return (h and 0x7FFFFFFFFFFFFFFFUL).toLong()
}
```

> **iOS deviation noted.** The Swift version hashes UUID raw bytes (16 bytes) + Double bit-pattern of distance. We hash the UTF-16 encoding of the UUID string + the long value of the distance (truncated to integer meters). That means **our renderer is NOT pixel-identical to iOS for the same walk.** That's acceptable — the iOS team never wrote a cross-platform determinism test, and the seed's only job is to produce a stable-per-walk meander. The CalligraphyPathRenderer iOS spec notes that "the same walk hash would match across platforms" is a goal for Phase 4 (goshuin seals), not 3-C. We document the divergence and move on.

```kotlin
fun seed(spec: CalligraphyStrokeSpec): Float {
    val h = fnv1aHash(spec)
    return ((h % 2000L) / 1000f) - 1f        // [-1, 1]
}

fun xOffset(spec: CalligraphyStrokeSpec, centerX: Float, maxMeander: Float): Float {
    val h = fnv1aHash(spec)
    val normalizedOffset = (h % 1000L) / 1000f - 0.5f
    return centerX + normalizedOffset * maxMeander * 1.6f
}
```

### Y layout

```kotlin
fun dotY(index: Int, topInsetPx: Float, verticalSpacingPx: Float): Float =
    topInsetPx + index * verticalSpacingPx + verticalSpacingPx / 2f
```

Constants: `topInset = 40.dp`, `verticalSpacing = 90.dp`, `maxMeander = 100.dp`.

### Opacity taper

```kotlin
fun segmentOpacity(index: Int, total: Int): Float =
    if (total <= 1) 0.35f
    else 0.35f - (index.toFloat() / (total - 1).toFloat()) * 0.2f     // [0.15, 0.35]
```

### Month → base color name

For MVP, a tiny helper beside the renderer that picks a PilgrimColors base for a given month. Stage 3-D will wrap this with the HSB shift.

```kotlin
enum class SeasonalInkFlavor { Ink, Moss, Rust, Dawn }

fun seasonalInkFlavor(startMillis: Long, zone: ZoneId = ZoneId.systemDefault()): SeasonalInkFlavor {
    val month = Instant.ofEpochMilli(startMillis).atZone(zone).monthValue
    return when (month) {
        in 3..5  -> SeasonalInkFlavor.Moss   // Mar–May
        in 6..8  -> SeasonalInkFlavor.Rust   // Jun–Aug
        in 9..11 -> SeasonalInkFlavor.Dawn   // Sep–Nov
        else     -> SeasonalInkFlavor.Ink    // Dec–Feb
    }
}

@Composable
fun SeasonalInkFlavor.toColor(): Color = when (this) {
    SeasonalInkFlavor.Ink  -> pilgrimColors.ink
    SeasonalInkFlavor.Moss -> pilgrimColors.moss
    SeasonalInkFlavor.Rust -> pilgrimColors.rust
    SeasonalInkFlavor.Dawn -> pilgrimColors.dawn
}
```

> Northern-hemisphere convention for MVP. Southern-hemisphere inversion lands in Stage 3-D alongside the full seasonal engine.

---

## Architecture

### Package layout

New subpackage: `ui/design/calligraphy/`. Sits next to `ui/home/` and `ui/walk/`. Chosen over putting it in `ui/theme/` because the theme package is tight (5 files, all primitive tokens) and `design/` gives us a home for future Canvas work (goshuin seals, etegami, journal thread in 3-E).

```
ui/design/calligraphy/
├── CalligraphyStrokeSpec.kt       // data class + FNV hash + helpers
├── CalligraphyPath.kt             // @Composable fun CalligraphyPath(...)
├── SeasonalInkFlavor.kt           // month → color-name helper (incl. @Composable toColor())
└── CalligraphyPathPreview.kt      // debug preview screen

ui/design/
└── (reserved for future goshuin / etegami renderers)
```

### Public API

```kotlin
@Composable
fun CalligraphyPath(
    strokes: List<CalligraphyStrokeSpec>,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = 90.dp,
    topInset: Dp = 40.dp,
    maxMeander: Dp = 100.dp,
    baseWidth: Dp = 1.5.dp,
    maxWidth: Dp = 4.5.dp,
)
```

The Composable internally computes its height from `topInset + strokes.size * verticalSpacing + verticalSpacing` so parents can put it in a scroll container. It calls `Canvas(modifier.height(computedHeight))` and draws all segments in one pass.

### Preview harness

A debug-only navigation destination `calligraphy_preview`, reachable from a "Calligraphy preview" TextButton on HomeScreen (below the "New walk" CTA, above the battery card, wrapped in `if (BuildConfig.DEBUG)`). Tapping it pushes a screen that:

1. Pulls all finished walks from the repository (no sample-row fetch — samples are not needed for the renderer).
2. Falls back to a hard-coded list of **8 synthetic strokes** spanning all four seasons, with varying paces (fast/slow), to give us something to look at on an empty device.
3. Renders `CalligraphyPath(...)` in a vertical scroll, centered, full width.

This entire preview route is deleted in Stage 3-E when the renderer lands inside `HomeScreen`. The button goes with it.

### Threading / recomposition

- Renderer is pure and uses only `DrawScope` primitives → no side effects.
- Seeds are computed lazily inside the Canvas lambda, so they recompute on size change but not on unrelated Home state changes.
- `List<CalligraphyStrokeSpec>` is passed as-is — callers should supply an immutable list (already do, since it flows from StateFlow).
- No coroutines, no side effects, no services.

---

## Data flow

```
HomeViewModel (later stages) → Stream<List<CalligraphyStrokeSpec>>
                                             │
                                             ▼
                                   CalligraphyPath(strokes)
                                             │
                                             ▼
                                   Canvas.drawPath(...)  ×N
```

For 3-C:
```
CalligraphyPathPreviewViewModel → Stream<List<CalligraphyStrokeSpec>>   (from Room + synthetic fallback)
                                             │
                                             ▼
                                   CalligraphyPath(strokes)
```

Converting `Walk + List<RouteDataSample>` into `CalligraphyStrokeSpec`:

```kotlin
fun Walk.toStrokeSpec(samples: List<RouteDataSample>, seasonalInk: Color): CalligraphyStrokeSpec {
    val distanceMeters = walkDistanceMeters(samples)
    val durationMs     = (endTimestamp ?: startTimestamp) - startTimestamp
    val durationSec    = durationMs / 1000.0
    val pace           = if (distanceMeters > 0.0 && durationSec > 0.0)
        durationSec / (distanceMeters / 1000.0) else 0.0
    return CalligraphyStrokeSpec(uuid, startTimestamp, distanceMeters, pace, seasonalInk)
}
```

The conversion helper lives in `CalligraphyStrokeSpec.kt` alongside the data class (it's tiny and stateless).

---

## Error handling / edge cases

| Edge case | Behavior |
|---|---|
| Empty list | Canvas draws nothing; height collapses to `topInset + verticalSpacing` (shows blank parchment). |
| Single stroke | No segments drawn (needs ≥2 dots). Still lays out a dot at the computed Y (dot rendering itself is a later stage; for 3-C we just render nothing). |
| Pace = 0 (very short walk, no samples) | Falls through to `baseWidthPx`. No div-by-zero. |
| Distance = 0 | Hash still works (distance is just one of three seed ingredients). |
| Null `endTimestamp` | Caller responsible. Preview VM filters `walk.endTimestamp != null`. |
| Very large list (500+ walks) | One Canvas, one pass, no recomposition — we draw every frame. If we ever hit perf issues, we'd cache the Path into an `ImageBitmap`. Not needed now. |
| Device rotation / config change | Canvas redraws; seeds are deterministic so the pattern stays the same. |

---

## Testing strategy

**Pure-JVM unit tests** (no Robolectric needed for the math — keep it fast):

- `CalligraphyHashTest`
  - `fnv1aHash` deterministic across process runs
  - Different UUIDs → different hashes
  - Same inputs → identical hash every call
- `SegmentWidthTest`
  - Very fast pace (300 sec/km) → `baseWidthPx`
  - Very slow pace (900 sec/km) → `maxWidthPx`
  - Mid-range (600 sec/km) → halfway between
  - Index 0 of 10 → 100% of pace-width
  - Index 9 of 10 → 60% of pace-width (taper factor)
  - Pace=0 fallback → `baseWidthPx`
- `SegmentOpacityTest`
  - Index 0 of N → 0.35
  - Index N-1 of N → 0.15
- `SeasonalInkFlavorTest`
  - Jan/Feb/Dec → Ink
  - Mar/Apr/May → Moss
  - etc. across DST boundaries + timezones

**Robolectric test** (matches project builder-test convention for platform objects):

- `CalligraphyPathComposableTest` — renders into a ComposeRule, no assertions on pixels, just confirms:
  - No crash with 0, 1, 2, 8 strokes
  - Reports a measured height ≥ `topInset + count * verticalSpacing`
  - Height is non-zero with empty list (still shows blank parchment)

That's the minimum to verify the draw path actually executes without exploding on the Compose-side API (which is the risk a pure-JVM test can't catch — Canvas + DrawScope use real `Path` objects at runtime).

No screenshot / pixel-diff tests. Visual verification via the debug preview screen.

---

## Rejected alternatives

1. **Single stroked path with `Stroke.width = variable`**: Compose's `drawPath(style = Stroke(width=X))` uses one width per call. Varying width mid-path would require per-segment draws with different Stroke widths — *possible*, but the iOS algorithm explicitly fills between two offset Béziers for a reason (the offset produces the smooth width transition even when segments abut). Matching iOS verbatim is the safe call; we can revisit if the filled-polygon approach has Compose-specific sharp artifacts. **Rejected for now; revisit on-device.**

2. **Eager color resolution inside the renderer**: Have `CalligraphyPath(strokes, colorResolver: (Long) -> Color)` with seasonal logic baked in. **Rejected** — couples the renderer to the seasonal system we haven't built (Stage 3-D's job). Keeps test surface clean: the renderer takes pre-resolved colors.

3. **Cache the Path into a Picture / ImageBitmap**: Big perf win for static rendering. **Rejected** — premature; we don't know we have a perf problem yet. Add when measurements say we do.

4. **LazyColumn integration right now**: Would require the Canvas to align walk-by-walk with each card row. The math is the same, but the *plumbing* (measuring cards, piping Y positions into the Canvas) is a 3-E-sized problem. Keep 3-C focused.

5. **Southern-hemisphere inversion**: ships with Stage 3-D alongside the full seasonal HSB engine. A one-line hemisphere flip here would just create two places that need updating later. Defer.

6. **Pre-warm the Path objects off the main thread**: Compose's `Path()` is cheap, and each segment is ~4 control points. No warm-up necessary.

---

## Scope & estimate

- 4 new production files (~250 LoC combined)
- 4 new test files (~200 LoC combined)
- 1 HomeScreen edit (add debug button)
- 1 PilgrimNavHost edit (register preview route)
- 1 new ViewModel (~50 LoC)

Implementation: ~1 stage's worth of work. Touches no existing features, blocks nothing upstream.

---

## Forward-carry for Stage 3-D / 3-E

- **3-D (seasonal engine)** will replace `SeasonalInkFlavor.toColor()` with a proper HSB-shifted color. The *shape* of `seasonalInkFlavor(startMillis)` survives; only the color resolution upgrades.
- **3-E (journal integration)** will delete `CalligraphyPathPreview*.kt` + the debug button, then position `CalligraphyPath` inside HomeScreen's scroll. The VM/state shape is already journal-shaped, so 3-E is mostly wiring.
- **Stage 4 (goshuin seals)** will use the FNV-1a hash from `CalligraphyStrokeSpec.kt` if we decide to target cross-platform determinism at that point; we'll then tighten the iOS divergence noted above.

---

## Open questions for checkpoint

- None. The algorithm is prescribed, the package layout is clear, the test surface is small. Approve, revise, or reject.
