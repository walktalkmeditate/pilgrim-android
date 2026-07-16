# Port spec — 1.8.1 scenery renderers: cairns, drift, real moons, lit lanterns, gate treatments (U15)

- **iOS anchor:** `pilgrim-ios` @ `c1745e88494d7677c4be8770ab6ceed1a61f3f6f` (c1745e8)
- **Source commits:** `b1f7638` ("the scroll remembers — gates, cairns, real
  moons, lit lanterns"), `35dcea8` ("two kinds of gates, and drift"),
  `1d94f5d` (cairn growth), `eed14d1` (shimenawa under-arch geometry fix),
  `6e80a91` **partial** (five-moss creep + 0.45 seeking fill are in scope;
  the InkScrollView age-fade *exemption* for seeking gates is U16 — this
  unit only guarantees the hook, i.e. `gateKind` visible at the fade site).
  This spec ports the **end-state renderer layer** at c1745e8.
- **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` § U15
- **Depends on:** U14 model (`SceneryPlacement.stones` / `gateKind`,
  `SceneryType.Cairn` / `Drift`, placement-level `tintTokenName` resolving
  practice-rust — `docs/parity/2026-07-15-port-scenery-model-u14.md`).

## 0. Android ground — what already exists vs what this unit adds

| Behavior | iOS origin | Android pre-U15 | U15 action |
|---|---|---|---|
| Lantern winter glow-color shift (dawn vs stone) | pre-1.8.1 | `lanternGlowColor` in `SceneryItem.kt` | none (already ported) |
| Grass winter color shift, morning dew | pre-1.8.1 | `grassSeasonalColor`, `GrassScenery` | none |
| Mountain snow triangles (Nov–Mar) | pre-1.8.1 | `MountainScenery` | none |
| Lantern **lit only when the walk met the dark** | `b1f7638` | missing — always lit | add hour gate |
| Moon **real lunar phase carve** | `b1f7638` | fake `days % 30` phase-scale wiggle | replace |
| Cairn renderer (+ its winter snow cap, `1d94f5d`) | `b1f7638`/`1d94f5d` | `// U15 renders these` passthrough | create |
| Drift renderer (four seasonal faces) | `35dcea8` | passthrough | create |
| Torii gate-kind fill + seeking moss | `35dcea8`/`6e80a91` | missing (`ToriiScenery` has no `gateKind`) | add |
| Shimenawa/shide under-arch geometry | `eed14d1` | **partially wrong**: rope y at 0.28 (iOS fix: 0.33), shide at 0.38/0.50/0.62 (iOS fix: 0.37/0.49/0.61); endpoints 0.28/0.72 already correct | fix |

The Android center-origin translation of the pre-fix iOS code accidentally
avoided iOS's top-left-frame bug (the rope never flew off to the corner),
but its derived fractions drifted from the values eed14d1 pinned. U15
re-pins them to the fixed frame fractions.

Animation idiom: all Android scenery shares `sceneryTimeSeconds()`
(`TreeScenery.kt:153`) — a 300 s linear `rememberInfiniteTransition` that
returns a constant `0f` under `LocalReduceMotion`. That constant-frame
freeze is the Android analogue of iOS pausing `TimelineView`
(`SceneryItemView.swift:14-23@c1745e8`); iOS's per-type fps caps (15/20/30)
are not modeled (pre-existing divergence, unchanged). U15 extends the
driver with a `paused: Boolean` parameter for the unlit lantern
(iOS: `paused: reduceMotion || !isLit`, `SceneryItemView.swift:362@c1745e8`).

## 1. Cairn — `CairnStonesShape.swift@c1745e8` + `cairnView` (SceneryItemView.swift:146-175)

```swift
let count = max(2, min(stones, 5))
let rowHeight = rect.height / CGFloat(count)
for index in 0..<count {
    // index 0 = top stone (narrowest), last = base (widest).
    let fraction = count == 1 ? 1.0 : CGFloat(index) / CGFloat(count - 1)
    let stoneWidth = w * (0.38 + 0.44 * fraction)
    let stoneHeight = rowHeight * 1.05
    let lean = index == count - 1
        ? 0
        : (index.isMultiple(of: 2) ? w * 0.05 : -w * 0.06)
    path.addEllipse(in: CGRect(
        x: rect.midX - stoneWidth / 2 + lean,
        y: rect.minY + CGFloat(index) * rowHeight,
        width: stoneWidth, height: stoneHeight))
}
```

Pinned: count clamps to 2–5; stone widths ascend top→base
(`0.38w`→`0.82w`); stone height `h/count × 1.05` (5% overlap); non-base
stones lean alternately `+0.05w` (even index) / `−0.06w` (odd index), base
stone no lean. Android: `cairnStoneRects(size, stones): List<Rect>` pure
geometry (Stage 3-C rule — Robolectric Canvas is a stub) +
`cairnStonesPath` oval-adder in `SceneryShapes.kt`.

`cairnView` layers (SceneryItemView.swift:150-174, ZStack bottom→top):

1. Ghost stones — `1.06×` frame, `tint.opacity(0.1)`, offset `(1.5, 1.5)`,
   blur 1.2 (Android idiom drops sub-2px blurs, as every other ghost layer).
2. Main stones — `tint.opacity(0.35)`, `size × size`.
3. Winter cap (`month == 12 || month <= 2`) — white ellipse
   `0.30×0.10 · size` at offset `(0.02·size, −0.46·size)`, opacity 0.35,
   blur 0.5 (dropped).
4. **Dawn halo on top** — `Circle` `1.5 × size`,
   `Color(red: 0.77, green: 0.58, blue: 0.42).opacity(0.10)`, blur 7
   ("A trace of the dawn halo the clearing wore on the map",
   SceneryItemView.swift:169-173). Android: exact literal `0xFFC4946B`
   as a radial gradient fading to transparent (the `LanternScenery`
   glow precedent — flat discs read as disks without iOS's blur-7).

**Deliberately static** — the cairn is the only standing scenery with no
time-driven term (iOS `cairnView` has no `TimelineView`). No
`sceneryTimeSeconds()` subscription at all.

## 2. Drift — `driftView` (SceneryItemView.swift:44-140)

```swift
let month = Calendar.current.component(.month, from: walkDate)
let hour = Calendar.current.component(.hour, from: walkDate)
let metTheDark = hour >= 17 || hour < 6
...
switch month {
case 3...5: petalDrift(time: time)
case 6...8: fireflies(time: time, lit: metTheDark)
case 9...11: dragonflies(time: time)
default: snowFlurry(time: time)
}
```

One type, four faces, chosen by the walk's month; the only scenery whose
elements **travel through** the frame. Android: pure
`driftFace(month): DriftFace` + shared `walkMetTheDark(hour)` (also the
lantern's gate, § 4), 20 fps TimelineView → shared `sceneryTimeSeconds()`.

- **Spring petals** (:62-78) — 5 petals `(phase, speed, r)`:
  `(0.0,0.09,0.055) (2.1,0.13,0.045) (4.0,0.07,0.06) (1.2,0.11,0.04)
  (5.3,0.15,0.05)`; `progress = ((t·speed + phase) mod 1.6) / 1.6`;
  ellipse `2r·s × 1.3r·s`, pink `(1.0, 0.75, 0.82)` → `0xFFFFBFD1`,
  alpha `0.35·(1 − progress·0.5)`, rotation `progress·220 + phase·40` deg,
  offset `x = s·(−0.45 + progress·0.9) + sin(t·0.8 + phase)·3`,
  `y = s·(−0.35 + progress·0.75)` — falling diagonally through the frame.
- **Summer fireflies** (:80-96) — 3 motes `(phase, fx, fy)`:
  `(0.0,0.31,0.23) (2.4,0.19,0.37) (4.7,0.27,0.17)`; glow
  `(0.95, 0.87, 0.55)` → `0xFFF2DE8C`;
  `pulse = lit ? (sin(t·1.7 + phase·2)+1)/2 : 0`; circle `0.07s` diameter,
  fill `lit ? glow.opacity(0.12 + pulse·0.38) : tint.opacity(0.14)`
  (unlit = dim static motes in the type tint, fog); wander
  `x = sin(t·fx + phase)·0.4s`, `y = cos(t·fy + phase·1.3)·0.32s`.
  `lit = walkMetTheDark(hour)` — the walk must have met the dark.
- **Autumn dragonflies** (:98-123) — 2, body `(0.72, 0.30, 0.22)` →
  `0xFFB84D38`; `phase = i·2.6`; hover-with-dart
  `x = sin(t·0.4+φ)·0.32s + sin(t·2.3+φ)·0.06s`,
  `y = cos(t·0.7+φ)·0.2s + sin(t·3.1+φ)·2`; capsule body
  `0.16s × 0.028s` alpha 0.4 + two white wing ellipses `0.09s × 0.03s`
  alpha 0.25, offsets `(−0.01s, ∓0.02s)`, rotated `∓24°`; whole body
  rotated `sin(t·0.9+φ)·14` deg.
- **Winter flurry** (:125-140) — 6 flakes `(phase, speed, x, r)`:
  `(0.0,0.10,−0.3,0.030) (1.7,0.14,0.1,0.022) (3.2,0.08,0.35,0.026)
  (4.5,0.12,−0.12,0.020) (2.6,0.09,0.24,0.028) (5.5,0.13,−0.38,0.018)`;
  `progress = ((t·speed + phase) mod 1.4) / 1.4`; white circle `2r·s`
  diameter, alpha `0.32·(1 − progress·0.35)`,
  `x = s·x + sin(t·0.6 + phase)·2.5`, `y = s·(−0.4 + progress·0.85)`.

## 3. Moon — real phase carve (`moonView`, SceneryItemView.swift:453-520)

```swift
// Hoisted out of the timeline closure — astronomy once, not per frame.
let T = CelestialCalculator.julianCenturies(from: ...)
let illumination = CGFloat(CelestialCalculator.lunarIllumination(T: T))
let phase = CelestialCalculator.lunarPhaseName(for: walkDate)
let waxing: Bool
switch phase {
case .new, .waxingCrescent, .firstQuarter, .waxingGibbous: waxing = true
default: waxing = false
}
let phaseScale: CGFloat = 0.9
...
.mask(
    ZStack {
        Circle()
        Circle()
            .offset(x: (waxing ? -1 : 1) * max(illumination, 0.08) * size * phaseScale)
            .blendMode(.destinationOut)
    }
    .compositingGroup()
    .frame(width: size * phaseScale, height: size * phaseScale)
)
```

Pinned semantics:

- `phaseScale` is a **constant 0.9** — the pre-1.8.1 day-varying scale
  (`abs(days) % 30 / 100 + 0.85`, currently on Android) is retired.
- Shadow-disc carve offset `(waxing ? −1 : 1) · max(illumination, 0.08) ·
  size · 0.9`: waxing slides the shadow **left** (lit limb right), waning
  right (lit limb left); at full the shadow has left entirely; the `0.08`
  floor keeps a hairline crescent at new moon.
- The carve masks all three `MoonShape` layers (ghost / main / highlight);
  rays, stars, clouds, and the halo stay un-carved.
- Astronomy hoisted **outside** the animation closure — once per view.

Android mapping: `MoonCalc.moonPhase(instant)` supplies both inputs —
`illumination` and `isWaxing` (`MoonPhase.kt:35-37`, `ageInDays <
SYNODIC_DAYS / 2`) — computed in a `remember(walkDateMs)`. **No new
astronomy port is needed.** Carve = pure
`moonCarveOffsetFraction(illumination, waxing)` + a two-circle
`PathOperation.Difference` path used as `clipPath` around the three
crescent layers.

## 4. Lantern — lit only when the walk met the dark (SceneryItemView.swift:350-394)

```swift
// The lantern remembers the hour: lit for walks that met the dark
// (same plain-hour idiom as the grass's morning dew), unlit and
// quiet for daylight walks.
let hour = Calendar.current.component(.hour, from: walkDate)
let isLit = hour >= 17 || hour < 6
...
TimelineView(.animation(minimumInterval: 1.0 / 20.0, paused: reduceMotion || !isLit)) { ... }
let glowIntensity = isLit ? 0.35 + flicker1 + flicker2 + flicker3 : 0
...
if isLit { Circle()...glow }        // outer glow only when lit
LanternWindowShape()
    .fill(isLit ? glowColor.opacity(glowIntensity) : tintColor.opacity(0.12))
```

Pinned: `isLit = hour >= 17 || hour < 6` (shared `walkMetTheDark`);
daylight → **no** outer glow, window flat `tint.opacity(0.12)`, animation
**paused** (Android: `sceneryTimeSeconds(paused = !isLit)`). Flicker math
(3.7/5.3/7.1 sines) unchanged, already ported.

## 5. Torii — gate-kind treatments (SceneryItemView.swift:648-764)

- Fill: `tintColor.opacity(gateKind == .seeking ? 0.45 : 0.35)` (:686-688).
  Practice tint is already rust via U14's placement-level `tintTokenName`.
- `seekingMoss` (:702-726) — five ellipses, fixed green
  `Color(red: 0.45, green: 0.52, blue: 0.35)` → `0xFF738559`, creeping
  **up** the pillars, heavier on the left (three left, two right), drawn
  between the main gate and the rope. Center-relative geometry
  `(w, h, x, y, alpha)` in `size` fractions:

  | # | w | h | x | y | alpha |
  |---|---|---|---|---|---|
  | 1 | 0.20 | 0.08 | −0.29 | +0.45 | 0.50 |
  | 2 | 0.09 | 0.14 | −0.24 | +0.36 | 0.38 |
  | 3 | 0.06 | 0.09 | −0.29 | +0.26 | 0.26 |
  | 4 | 0.15 | 0.06 | +0.31 | +0.46 | 0.44 |
  | 5 | 0.07 | 0.11 | +0.27 | +0.38 | 0.30 |

- `ropeAndShide` (:728-764) with the eed14d1 fix baked in:

```swift
// Path views draw from their frame's top-left, so this layer gets the
// same size×size frame as ToriiGateShape and uses its geometry:
// pillar inner edges at 0.27/0.73, nuki crossbeam at y 0.30–0.34.
let ropeY = size * 0.33
let leftX = size * 0.28
let rightX = size * 0.72
let shidePositions: [CGFloat] = [0.37, 0.49, 0.61]
```

  Rope: quad curve `(0.28s, 0.33s) → (0.72s, 0.33s)`, control
  `(0.5s, 0.33s + 0.06s)` (0.06 sag), stroked `tint.opacity(0.2)` width 1 —
  hanging **under the nuki**. Three 5-segment zig-zag shide strips at frame
  x fractions `0.37/0.49/0.61`, white 0.2 stroke width 0.8, flutter
  `sin(t·2 + i·1.2)·2.5` with the existing 0.3/0.5/0.4 segment multipliers.
  Android pins all fractions as `ShimenawaGeometry` constants (regression
  guard for the eed14d1 bug class) and fixes its drifted values
  (rope y 0.28→0.33, shide 0.38/0.50/0.62→0.37/0.49/0.61).

## 6. Wiring — `SceneryItem.kt`

Replace the U15 passthrough: `Cairn → CairnScenery(sizeDp, tint,
walkDateMs, placement.stones)`, `Drift → DriftScenery(sizeDp, tint,
walkDateMs)`, and `Torii` gains `gateKind = placement.gateKind`. Tints
stay hoisted at the `SceneryItem` layer (iOS P4 hoisting analogue); the
moon's astronomy lives in a `remember(walkDateMs)` inside `MoonScenery`.
All scenery remains decorative — the HomeScreen host box already carries
`semantics { contentDescription = "" }`; per-type composables add none.

**U16 hooks left ready:** `placement.gateKind` is available at the
HomeScreen scenery-alpha site (`perSceneryAlpha` graphicsLayer) for the
seeking-gate age-fade exemption (iOS `InkScrollView.swift:659-661@c1745e8`);
`SceneryType` is available for the parallax weight table.

## 7. Deliberate non-goals / divergences

- **Carve mechanism**: iOS uses a destination-out compositing mask; Android
  uses `clipPath` of a two-circle `PathOperation.Difference` — identical
  hard-edge geometry, no saveLayer, and the codebase already carves the
  crescent with `PathOperation.Difference` (`MoonScenery.drawCrescent`).
- **Waxing boundary**: iOS derives `waxing` from the 45°-bucketed phase
  *name* (`CelestialCalculator.swift:174-190@c1745e8`), so elongation
  157.5°–180° (waxing side of the "full" bucket) reads waning and
  337.5°–360° (waning side of "new") reads waxing. Android's
  `MoonPhase.isWaxing` splits exactly at the synodic midpoint. In the
  divergent windows the carve is either nearly gone (≥96% illumination) or
  a floored hairline (≤4%), so the visible difference is a hairline's side
  at most — Android keeps its astronomically-exact convention.
- **Illumination source**: iOS `lunarIllumination` uses ecliptic-longitude
  elongation; Android `MoonCalc` uses the synodic cosine (±5% drift,
  accepted since Stage 6-A for contemplative UI). Both are
  `(1 − cos θ)/2`.
- **Blur-free layers**: iOS blur radii ≤2 dropped, radius-7 halo rendered
  as a radial gradient — the established Android scenery idiom.
- **Frame-rate caps**: iOS caps drift/lantern/torii at 20 fps, moon at
  15 fps; Android's shared `sceneryTimeSeconds()` runs at display rate
  (pre-existing divergence, unchanged by U15).
- **`driftFace(month)` + `walkMetTheDark(hour)`** are two pure functions
  rather than one `(month, hour)` selector — the hour only gates firefly
  glow, never the face.
- **Dragonfly wing rotation anchor**: iOS's `.offset().rotationEffect()`
  ordering rotates the wing about the un-offset layout center; Android
  rotates each wing about its own center — at wing sizes of ~2–3 px the
  difference is sub-pixel decorative noise.

## 8. Test parity map

iOS has no renderer-level unit tests for these (SwiftUI view bodies);
Android pins the geometry through pure functions (Stage 3-C rule):

| Pinned behavior | Android test |
|---|---|
| Cairn count clamp 2–5, widths ascend base-down, heights `h/count·1.05` | `CairnSceneryTest` |
| Cairn alternating lean `+0.05w/−0.06w`, base no-lean | `CairnSceneryTest` |
| Cairn winter cap Dec/Jan/Feb only | `CairnSceneryTest` (month table) |
| Drift face by month (3-5/6-8/9-11/rest) | `DriftSceneryTest` (12-month table) |
| Firefly met-the-dark gate, 16:59 vs 17:00 boundary | `DriftSceneryTest` + `LanternSceneryTest` |
| Moon carve direction (waxing → −, waning → +), 0.08 floor, full-moon exit | `MoonSceneryTest` |
| Lantern lit-window boundaries (17:00, 05:59, 06:00, 16:59) | `LanternSceneryTest` |
| Seeking torii: 5 moss patches, pinned alphas, heavier left, 0.45 fill | `ToriiSceneryTest` |
| Practice/no-kind torii: no moss, 0.35 fill | `ToriiSceneryTest` |
| Shimenawa rope y 0.33, endpoints 0.28/0.72, shide 0.37/0.49/0.61 (eed14d1 guard) | `ToriiSceneryTest` |
| Cairn/drift/seeking-torii compose (incl. reduce-motion static frame) | `JournalScreenIntegrationTest` |
