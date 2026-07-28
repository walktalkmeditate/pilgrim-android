# Seek Clearing Glyph — the Clearing Grows a Tree (R2 fold-in) — iOS → Android Port Spec

> **Plan:** fold-in delta per R2 (`docs/brainstorms/2026-07-22-ios-v190-parity-retarget-requirements.md`) — incremental refinement to the map-glyph pipeline, owned by Phase 18.
> **iOS pin:** `pilgrim-ios` @ `b4decad` (fold-in merge `3186a39`, feature commit `7dc180a` "feat(glyphs): the clearing grows a tree", 2026-07-27). All Swift/SVG quotes cite `file@b4decad`.
> **Authority:** SHIPPED Swift over comments — `glowCircle` ships `circleRadius = 20` while a stale `buildPoints` comment still says "against `glowCircle`'s 26pt radius"; the code (20) is the contract (see D5).
> **Android files:** create `app/src/main/res/drawable/glyph_seek_clearing.xml`; modify `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/map/MapGlyphBitmaps.kt`, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt`, `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/SeekSummarySection.kt`; tests `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/map/GlyphAssetTest.kt`, `MapGlyphBitmapsTest.kt`.
> **Android templates:** `docs/parity/2026-07-27-port-vector-masters-u13.md` (SVG→VectorDrawable translation table, tintable-vector convention), `docs/parity/2026-07-27-port-map-glyphs-u14-u15.md` (rasterizer + wiring seams, Stage 13-D rebuild-gate rule).

## L1. The ninth master

**iOS** (`Pilgrim/Support Files/Assets.xcassets/glyphs/seekClearing.imageset/seek-clearing.svg@b4decad`) — one imageset, one path, already normalised into the square canvas by its author:

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 150 150">
<!-- Normalised from Arrow viewBox "46 -59.7 188 152.3" to a centred
     150x150 square: MapGlyphImageBuilder draws into a square target and
     UIImage.draw(in:) does not preserve aspect ratio. -->
  <g transform="translate(-18.8298 63.9750) scale(0.670213)">
    <path d="m223 18c1.1-0.4-1.6-3.8-4-3.4…" fill="#0a1624"/>
  </g>
</svg>
```

`Contents.json` marks it a **template** asset, exactly like the wisp:

```json
"properties" : {
  "preserves-vector-representation" : true,
  "template-rendering-intent" : "template"
}
```

**Android claims:**
- `glyph_seek_clearing.xml`: `viewportWidth/Height = 150`, single `<path>` with `pathData` **verbatim** from the master, wrapped in `<group android:translateX="-18.8298" android:translateY="63.975" android:scaleX="0.670213" android:scaleY="0.670213">`. SVG `translate(a b) scale(s)` composes scale-then-translate; VectorDrawable's group matrix with pivot (0,0) is `translate(tx,ty) · scale(s)` — identical composition, no re-derivation of coordinates.
- Template mechanism per U13 D2: path `android:fillColor="#FFFFFFFF"` + root `android:tint="#FF0A1624"` (the authored master ink as the untinted default), runtime `setTint` (SRC_IN) replaces it per light.
- Intrinsic size `24dp` square (cosmetic; rasterization uses explicit bounds — U14 L1).
- The Arrow generator comment is dropped; provenance lives in the drawable's header comment citing master + pin (U13 L5 convention).

## L2. The builder branch — tinted, `clearing-RRGGBB` key space

**iOS** (`Pilgrim/Views/MapGlyphImageBuilder.swift@b4decad`) — third enum case, tinted like the wisp, never baked:

```swift
enum MapGlyph {
    case whisper(tint: UIColor)
    case cairn(tier: CairnTier)
    case seekClearing(tint: UIColor)
}
```

```swift
/// The clearing's tree. A template asset like the wisp — it takes the
/// hour's light from `SeekSkyLight` rather than carrying its own colour.
static let seekClearingAssetName = "seekClearing"
```

```swift
case .seekClearing(let tint):
    rendered = self.rendered(assetNamed: seekClearingAssetName, tint: tint, size: size)
…
case .seekClearing(let tint):
    return "clearing-\(rgbKey(for: tint))"
```

The key space grows by exactly the sky palette (builder doc): *"8 whisper mood colors, 7 cairn tiers, and the 6 daypart hexes a clearing can be found under."* The RGB-only key keeps the wisp's fixed-opaque-tint assumption (`rgbKey` asserts RGB-convertible; alpha never hashed).

**Android claims:**
- `MapGlyphBitmaps.seekClearing(context, tintArgb, sizeDp, density): Bitmap?` — delegates to the shared `rendered(...)`, tinting `R.drawable.glyph_seek_clearing`.
- Key `String.format(Locale.US, "clearing-%06X-%s-%s", tintArgb and 0xFFFFFF, sizeDp, density)` — same `clearing-` prefix so wisp/clearing keys cannot collide under the same tint (iOS `testTintedGlyphCacheKeysDoNotCollide`), same RGB-only + size + density scheme as `wisp` (U14 L2).
- Same opaque-tint `require` as `wisp` (the iOS `rgbKey` assert analogue): all callers hand fixed opaque literals — `SeekSkyLight.hex` values or the theme-resolved stone.
- `seekClearingLightHexes()` (internal, beside the builder) enumerates the 6-hex key space — `SeekSkyLight.hex(daypart, starlight)` over 3 dayparts × 2 families — so the prebuilt bitmap map in L3 provably covers every hex `MapAnnotations` can emit.

## L3. Summary map — the tree replaces the halo's core; the halo tightens

**iOS** (`Pilgrim/Views/PilgrimMapView.swift@b4decad`). The bright 4pt core circle is **deleted** from `buildCircles`:

```swift
case .waypoint, .whisper, .cairn, .seekArrival:
    // The clearing's core is its tree, drawn as a PointAnnotation
    // in `buildPoints`; the halo above still carries the hour's
    // light, so the two-part reading survives the glyph swap.
    continue
```

The glow halo stays but tightens 26 → 20 (`glowCircle`):

```swift
case .seekArrival(_, let lightHex):
    // Tightened from 26 when the clearing gained its tree: the halo
    // is light *around* the mark now, not the mark itself. Wider and
    // the 30pt tree floats in it.
    var glow = CircleAnnotation(centerCoordinate: pin.coordinate)
    glow.circleRadius = 20
    glow.circleColor = StyleColor(UIColor(hex: lightHex))
    glow.circleOpacity = 0.28
    glow.circleStrokeWidth = 0
```

The tree is a new `buildPoints` branch — 30pt, tinted the record's hour light:

```swift
case .seekArrival(_, let lightHex):
    // The tree standing in the clearing, tinted with the sky it
    // was found under. …
    var point = PointAnnotation(coordinate: pin.coordinate)
    let glyph = MapGlyph.seekClearing(tint: UIColor(hex: lightHex))
    if let image = MapGlyphImageBuilder.image(for: glyph, size: 30) {
        point.image = .init(image: image, name: MapGlyphImageBuilder.cacheKey(for: glyph))
    }
    point.iconSize = 1.0
    points.append(point)
```

**Composition answer:** the tree glyph does NOT replace the halo — it replaces only the halo's **bright core dot**. The two-part reading survives: hour-lit glow circle (radius 20, opacity 0.28, `lightHex`) underneath + the 30pt tree PointAnnotation (tinted the same `lightHex`) on top.

**Android claims (PilgrimMap.kt update-lambda, summary branch):**
- `SeekArrival` in the `meditationCircleManager` flatMap drops the second (4px @ 0.9) circle and tightens the glow `withCircleRadius(26.0)` → `20.0`; opacity stays 0.28, stroke 0.
- `SeekArrival` leaves the point-pin filter (only `Meditation` remains circle-only) and gains a bitmap branch: `seekClearingGlyphBitmaps[k.lightHex]`, `iconSize 1.0`, null → icon-less pin (iOS `if let image` degrade).
- Z-order holds without new work: `meditationCircleManager` is created before `annotationManager` in the loadStyle callback, so point pins already draw above circles — tree over halo, same as iOS's circle-manager-below-point-manager stack.
- `seekClearingGlyphBitmaps = rememberSeekClearingGlyphBitmaps()` — `Map<String, Bitmap>` keyed by the exact hex string `SeekArrival.lightHex` carries, prebuilt over `seekClearingLightHexes()` at `remember(density)` (the wisp map's pattern; tints are fixed literals, the summary size is a constant, density is the only live input).
- **Rebuild-gate re-key (Stage 13-D rule):** the walk-annotation snapshot key gains `seekClearingGlyphBitmaps` (the draw now reads it); both teardown sites (`loadStyle` re-key reset and `onRelease`) already null `renderedWalkAnnotationsKey` — verified unchanged-but-sufficient.
- Summary raster size pinned as `SEEK_CLEARING_GLYPH_SIZE_DP = 30f` beside `WHISPER_GLYPH_SIZE_DP`.

## L4. Live map — the arrival waypoint wears the tree in stone

**iOS** (`PilgrimMapView.buildPoints`, `.waypoint` case):

```swift
if icon == SeekPersistence.arrivalWaypointIcon {
    // A clearing reached mid-walk. Live walks keep `.waypoint`
    // (their halo is the fog layer), but the mark must not
    // change under the walker — same tree as the summary map,
    // wearing the stone every live waypoint wears. The hour's
    // light belongs to the record.
    let glyph = MapGlyph.seekClearing(tint: .stone)
    if let image = MapGlyphImageBuilder.image(for: glyph, size: 22) {
        point.image = .init(image: image, name: MapGlyphImageBuilder.cacheKey(for: glyph))
    }
} else if let image = cachedSymbolImage(icon, size: 18, color: .stone, cacheKey: icon) {
    point.image = .init(image: image, name: icon)
}
```

So live arrivals: clearing glyph at **22**, tinted the **adaptive stone** (not the hour hex), no halo change (the fog layer is the live halo). Other waypoints keep the 18pt symbol path.

**Android claims (PilgrimMap.kt live waypoint sync):**
- The waypoint pin loop branches first on `SeekPersistence.isArrivalWaypoint(wp.icon)` → `rememberSeekClearingLiveBitmap(stone)` (22dp, `pilgrimColors.stone` ARGB, `remember(stoneArgb, density)` — stone is theme/seasonal-resolved, so a flip re-rasterizes; RGB-keyed cache entries per stone value cannot collide). Null bitmap → icon-less pin (iOS degrade), NOT the `mappin` fallback — the reserved icon must never render as a user pin mark.
- Non-arrival waypoints keep `waypointBitmaps[wp.icon] ?: waypointBitmaps.getValue("mappin")` unchanged.
- **Rebuild-gate re-key (Stage 13-D rule):** the live sync gate was `renderedWaypoints != waypoints` — the draw now also reads two bitmap inputs, so the gate becomes a snapshot key `listOf(waypoints, waypointBitmaps, seekClearingLiveBitmap)` (`renderedWaypointsKey`), and BOTH teardown sites (loadStyle re-key, `onRelease`) reset it to null. This also closes the pre-existing latent stale-tint on constellation flips that don't change `styleUri`.
- Live raster size pinned as `SEEK_CLEARING_LIVE_GLYPH_SIZE_DP = 22f`.
- Pre-delta note: Android's live arrival pins fell back to the `mappin` glyph (`sun.haze` has no vector mapping); iOS rendered the actual `sun.haze` symbol. The tree supersedes both — this delta is also the first faithful live-arrival mark on Android.

## L5. Seek summary header — the asset replaces the symbol

**iOS** (`Pilgrim/Scenes/WalkSummary/SeekSummarySection.swift@b4decad`):

```swift
Image(MapGlyphImageBuilder.seekClearingAssetName)
    .resizable()
    .renderingMode(.template)
    .scaledToFit()
    .frame(width: 22, height: 22)
    .foregroundColor(.stone)
    .accessibilityHidden(true)
```

(was `Image(systemName: SeekPersistence.arrivalWaypointIcon).foregroundColor(.stone)`).

**Android claims (`SeekSummarySection.kt` header row):**
- `Icon(painterResource(R.drawable.glyph_seek_clearing), contentDescription = null, tint = pilgrimColors.stone, modifier = Modifier.size(22.dp))` — replaces the `Icons.Outlined.WbTwilight` stand-in (that U11 divergence D4 dissolves: the real asset now exists on Android). `contentDescription = null` is the `accessibilityHidden(true)` analogue; 18dp → 22dp follows the explicit iOS frame.

## L6. Test parity map

| iOS (`GlyphAssetTests.swift@b4decad`) | Android |
|---|---|
| `allGlyphNames` grows to 9 (`tintedGlyphNames = [whisper, seekClearing]` + 7 tiers); `testAllGlyphAssetsResolve` | `GlyphAssetTest`: `allGlyphDrawables` grows to 9; `all nine glyph drawables inflate` |
| `testTintedGlyphsAreTemplateAssets` — `renderingMode == .alwaysTemplate` for wisp + clearing | `clearing renders differently under two tints` (template behavior pinned by pixels, same as the wisp's existing test — Android has no renderingMode; tint response IS the contract) |
| `testClearingGlyphGeometrySurvivesTheAssetPipeline` — 150pt raster, ink bounds via alpha > 8: width 75.7% ± 4, height 57.9% ± 4, centred ± 4 (guards a dropped `<g transform>`) | `clearing ink geometry survives the translation` — same reference fractions, same alpha > 8 ink-bounds walk, at 150px (guards a mistranslated `<group>` matrix) |
| `testEveryDaypartProducesADistinctClearingKey` — 6 daypart×starlight tints → 6 keys | `every daypart and starlight pairing caches a distinct clearing` — render all 6 hexes → `cacheSize() == 6`; plus `seekClearingLightHexes` covers every hex `MapAnnotations` emits (the pure wiring seam) |
| `testTintedGlyphCacheKeysDoNotCollide` — whisper vs clearing keys differ under one colour | `clearing and wisp cache separately under the same tint` — both rendered at one tint/size/density → distinct instances, `cacheSize() == 2` |
| — (sizes verified on device) | `size table matches the shipped iOS map sizes` extended: clearing 30f summary / 22f live; `clearing output is dp times density`; coverage-fraction floor extended to the ninth master; `translucent clearing tint is rejected` |

## Divergences (conscious) and resolved ambiguities

- **D1 — naming**: `seekClearing` asset-catalog string → `glyph_seek_clearing` resource (Android lowercase_underscore; U13 D1 pattern).
- **D2 — template mechanism**: template-rendering-intent + `withTintColor` ↔ white fill + root `android:tint="#FF0A1624"` default + runtime `setTint` (U13 D2 pattern; default = authored master ink per U13 D4).
- **D3 — prebuilt maps vs on-demand builder**: iOS rasterizes lazily per (key, size); Android prebuilds the 6-hex summary map + 1 stone live bitmap at composition time (the U14/U15 wiring pattern). `seekClearingLightHexes()` + its coverage test guarantee the prebuilt map cannot miss a hex the annotations carry.
- **D4 — live degrade**: on a missing drawable iOS leaves the arrival pin icon-less; Android matches (bypasses the `mappin` fallback for the reserved icon) — a silent absent mark beats a wrong mark.
- **D5 — stale iOS comment**: `buildPoints` says the tree sits "against `glowCircle`'s 26pt radius" but `glowCircle` ships 20. Shipped code wins; Android pins 20.0 and does not copy the stale number.
- **Resolved — halo units**: Mapbox `circleRadius` is screen-density-independent (pt on iOS, logical px ≈ dp on Android) — 20.0/0.28 transfer verbatim, same as the existing 26.0 did at U11.
- **Resolved — dawn family is deliberate**: the summary annotation site hardcodes `starlight = false` on BOTH platforms (decided, not an oversight: "the record keeps the sky palette" — the hour's light at arrival, never restyled by the viewer's appearance mode). The prebuilt bitmap map still spans all 6 hexes (`seekClearingLightHexes()`), matching the iOS builder's documented key space, so a future family flip cannot silently miss the lookup.
- **Resolved — geometry-test tolerance**: iOS ships `accuracy: 0.04` on all four ink-bounds assertions; a tighter ±3% was floated during fold-in review. Android mirrors the shipped 0.04 — rasterizer AA differences across platforms make the shipped tolerance the safer shared contract.
- **Out of scope**: live seek fog/pulse visuals (unchanged), `MapAnnotations` hex computation (unchanged), on-device dark-basemap legibility of the stone-tinted tree (device QA).
