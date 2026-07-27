# Map Glyph Rasterizer + Annotation Wiring (U14 + U15) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U14, U15) · **Requirements:** R10, R11
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (tag v1.9.0, 2026-07-21). All Swift quotes cite `file@9a418e4`.
> **Authority:** SHIPPED Swift over the iOS plan (`docs/plans/2026-07-21-001-feat-svg-map-glyphs-plan.md` U2/U3). The plan's `12 + tier.rawValue` baseline and 14pt wisp were superseded on-device by the #57 doubling (`525bf5b`) before the tag — the shipped end-state sizes in L4 are the contract.
> **Android files:** create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/map/MapGlyphBitmaps.kt`; modify `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt`; test `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/map/MapGlyphBitmapsTest.kt`.
> **Android templates:** `GlyphAssetTest.kt` (Robolectric `@GraphicsMode(NATIVE)` pixel conventions), `renderWaypointGlyphBitmap` in `PilgrimMap.kt` (the seam being replaced for whispers/cairns only), Stage 13-D snapshot-rebuild gates.

## L1. The builder API and its raster path

**iOS** (`Pilgrim/Views/MapGlyphImageBuilder.swift@9a418e4`) — one entry point over a two-case glyph enum, rasterizing catalog vectors at display scale:

```swift
enum MapGlyph {
    case whisper(tint: UIColor)
    case cairn(tier: CairnTier)
}
...
static func image(for glyph: MapGlyph, size: CGFloat) -> UIImage? {
    let key = "\(cacheKey(for: glyph))-\(size)"
    if let cached = cache[key] { return cached }
    ...
    case .whisper(let tint):
        rendered = self.rendered(assetNamed: whisperAssetName, tint: tint, size: size)
    case .cairn(let tier):
        rendered = self.rendered(assetNamed: tier.glyphAssetName, tint: nil, size: size)
```

```swift
static func rendered(assetNamed name: String, tint: UIColor?, size: CGFloat) -> UIImage? {
    guard var asset = UIImage(named: name) else { return nil }
    if let tint {
        asset = asset.withTintColor(tint, renderingMode: .alwaysOriginal)
    }
    let target = CGSize(width: size, height: size)
    let format = UIGraphicsImageRendererFormat()
    format.scale = UIScreen.main.scale
    format.opaque = false
    return UIGraphicsImageRenderer(size: target, format: format).image { _ in
        asset.draw(in: CGRect(origin: .zero, size: target))
    }
}
```

Square rasters always (`size × size` — the U13 masters are square canvases); missing asset degrades to `nil`; main-thread only ("like all callers").

**Android claims:**
- `MapGlyphBitmaps` (object, `ui/walk/map/`) mirrors the shape: `wisp(context, tintArgb, sizeDp, density)` + `cairn(context, tier, sizeDp, density)`, both `Bitmap?`, both delegating to one internal `rendered(...)`.
- `iOS UIImage(named:)` → `ContextCompat.getDrawable`; `Resources.NotFoundException` → `null` (the U13 name contract is pinned by `GlyphAssetTest`, so this is the same degrade-don't-crash guard as iOS's `testMissingAssetDegradesToNil`).
- iOS `withTintColor` on the template asset → `mutate()` + `setTintMode(SRC_IN)` + `setTint(argb)` on the wisp drawable (`mutate()` first so the tint never contaminates the shared `ConstantState`; the drawable's own `android:tint="#FF06090E"` default is replaced per mood). Cairn path never tints — `.alwaysOriginal` parity for the baked fixed-hex art (U13 L3).
- iOS `draw(in: target)` (never `draw(at:)`) → `setBounds(0, 0, px, px)` always, then `draw(Canvas(bitmap))` — the equivalent guard against intrinsic-size rasterization (the drawables carry a cosmetic 24dp intrinsic size, U13 L4).
- `ARGB_8888`, transparent-background (iOS `format.opaque = false` ↔ `Bitmap.createBitmap` default transparency).
- Main-thread only, like all callers (composition-time `remember`); a plain `HashMap` cache is therefore safe.

## L2. Cache key scheme — what the keys carry and what they deliberately don't

**iOS** — the key is glyph identity + size; scale is implicit (one `UIScreen.main.scale` per device):

```swift
return String(format: "whisper-%02X%02X%02X",
              Int(red * 255), Int(green * 255), Int(blue * 255))
...
return "cairn-\(tier.rawValue)"
```

with `image(for:size:)` appending `-\(size)`. The wisp key hashes **RGB only** (tints are the fixed opaque `WhisperCategory.borderColor` literals; a translucent tint would collide — iOS fail-fasts in DEBUG). The cairn key has **no color or theme component** — the art is baked, so light/dark flips must hit the same cached raster. `testCacheKeysMatchExistingAnnotationNameFormats` pins `"cairn-2"` / `"whisper-FF0000"`.

**Android claims:**
- Wisp key: `whisper-RRGGBB-<sizeDp>-<density>` (RGB-only mask `tintArgb and 0xFFFFFF`, `Locale.US` hex). Cairn key: `cairn-<ordinal>-<sizeDp>-<density>`.
- Density joins the key because Android has no single process-wide scale (multi-display, `adb shell wm density`); it is an input the draw reads, per the Stage 13-D keying rule. Stale-density entries linger unevicted, like iOS ("the key space is small and fixed").
- **No theme/dark-mode component in either key** — the wisp tint is a fixed literal and the cairn art is baked, so a simulated night-configuration context must return the *same cached instance* (pinned by test).
- Android `CairnTier.ordinal` (0..6) ≡ iOS `tier.rawValue` (0..6) — same integers, same tier words (U13 L1).

## L3. Annotation wiring — which branches swap, and what stays

**iOS** (`PilgrimMapView.buildPoints@9a418e4`) — exactly two branches route through the builder; waypoints keep the SF-symbol path, photos keep `PhotoMarkerImageBuilder`:

```swift
case .waypoint(_, let icon):
    ...
    if let image = cachedSymbolImage(icon, size: 18, color: .stone, cacheKey: icon) {
case .whisper(let categoryColor, _):
    let glyph = MapGlyph.whisper(tint: categoryColor)
    if let image = MapGlyphImageBuilder.image(for: glyph, size: 28) {
        point.image = .init(image: image, name: MapGlyphImageBuilder.cacheKey(for: glyph))
    }
    point.iconSize = 1.0
case .cairn(_, let tier):
    let iconSize: CGFloat = 24 + CGFloat(tier.rawValue) * 2
    let glyph = MapGlyph.cairn(tier: tier)
    if let image = MapGlyphImageBuilder.image(for: glyph, size: iconSize) {
```

`point.iconSize = 1.0` on every branch — all sizing lives in the raster. A nil image leaves the annotation icon-less (invisible pin) rather than crashing. The `symbolImageCache` doc comment confirms the boundary: "waypoint icons only, now that whispers and cairns render through MapGlyphImageBuilder".

**Android claims:**
- Both Android rebuild sites swap: the **summary-annotation** branch (`WalkMapAnnotationKind.Whisper` / `.Cairn` inside the `renderedWalkAnnotationsKey`-gated block) and the **live-proximity** branch (`ProximityPinFilter.Pin.Whisper` / `.Cairn` inside the proximity-gated block). Waypoint and photo pins keep `renderWaypointGlyphBitmap` / `CircularPhotoBitmap` untouched (plan: out of scope).
- Bitmaps stay **per-annotation** (`withIconImage(Bitmap)`) — never named style images — per the plan's Key Technical Decision (style-reload trap never exists; the annotation plugin owns image lifecycles).
- `withIconSize(1.0)` on every glyph annotation; the old Android `iconSize = dp/18` ratio hack (`WHISPER_GLYPH_ICON_SIZE`, `cairnGlyphIconSize`) is deleted — all sizing lives in the raster, iOS-identically.
- A null/missing bitmap leaves the annotation icon-less (iOS `if let image` parity); the proximity pin keeps its invisible text routing key so tap handling stays total.
- Summary `WalkMapAnnotationKind.Cairn.tier` is 1-based (`CachedCairn.tier.ordinal + 1`, `MapAnnotations.kt`); the wiring maps it back with a clamped `entries[(tier - 1).coerceIn(0, lastIndex)]`. The proximity pin already carries `CairnTier` directly.

## L4. Shipped size table and the #57 disposition

The #57 "presences deserve presence" sizing landed in three commits between the iOS glyph plan and the v1.9.0 tag; all three are ancestors of `9a418e4`:

| commit | surface | change |
|---|---|---|
| `525bf5b` | **map** + stone sheet | map wisp 14 → **28**pt; map cairn `12 + rawValue` → **`24 + rawValue*2`**; sheet 48/56 → 96/112 |
| `32e1dfa` | detail view (sheet reverted) | "the doubling belongs to the detail view, not the stone sheet": detail `iconSize` 32–68 → **64–136** (step 12), hero 140 → 220, glow 180 → 260; sheet back to 48/56 |
| `a227348` | stone sheet (re-doubled) | "double the add-a-stone glyphs too": sheet 48/56 → **96/112** final |

**Disposition:** the doubling applies to *all three* surfaces in the shipped end-state — `32e1dfa`'s "belongs to the detail view" moved the doubling's *emphasis*, and `a227348` then re-doubled the sheet anyway. The **map** sizes below are U14/U15's contract; the detail-view (64–136) and sheet (96/112) sizes are **U16's** contract, recorded here only to close the disposition question.

Shipped map raster sizes at `9a418e4` (pt on iOS ⇒ dp on Android), `iconSize = 1.0` throughout:

| glyph | size |
|---|---|
| whisper wisp (all moods) | **28** |
| cairn Faint (rawValue 0) | **24** |
| cairn Small (1) | **26** |
| cairn Medium (2) | **28** |
| cairn Large (3) | **30** |
| cairn Great (4) | **32** |
| cairn Sacred (5) | **34** |
| cairn Eternal (6) | **36** |

**Android claims:** `WHISPER_GLYPH_SIZE_DP = 28f` and `cairnGlyphSizeDp(tier) = 24f + ordinal * 2f`, both in `MapGlyphBitmaps.kt`, pinned by a unit test against this table.

## L5. Density semantics — R11 and the documented Android bug being fixed

**iOS R11:** "Map sprites rasterized at display size × screen scale" — `format.scale = UIScreen.main.scale`, and the test pins it: `XCTAssertEqual(image.scale, UIScreen.main.scale, "raster must be display-scale so pins stay crisp")`. A 28pt request produces a 28×scale-px raster that Mapbox draws at 28 display points.

**Android mechanics (verified against Mapbox 11.11.0 bytecode):** the annotation plugin registers `withIconImage(Bitmap)` icons via `ImageUtils.image(imageId, bitmap) {}` with **no scale override**, and `ImageExtensionImpl.bindTo` resolves `scale = builder.scale ?: styleManager.pixelRatio` — the map's pixel ratio, which `MapInitOptions` defaults to the display density. So an icon bitmap renders on-screen at `bitmapPx / density` dp × `iconSize`.

That makes the existing convention the documented bug (plan Key Technical Decisions): `renderWaypointGlyphBitmap` draws at a fixed 72px under `Density(1f)`, which renders as `72/density` dp — ~2× oversized on mdpi, undersized at 640dpi — and the doubled #57 sizes would magnify the spread.

**Android claims:**
- New glyphs rasterize at `round(sizeDp × density)` px (density from `LocalDensity.current.density`, the same source `MapInitOptions` defaults its pixel ratio to), so every device renders the wisp at 28dp and tiers at 24–36dp. This is the exact analogue of iOS's `size × UIScreen.main.scale`.
- Waypoint/photo pins keep the fixed-px path unchanged (plan: "existing waypoint/photo pins are out of scope").

## L6. Refresh behavior — caches, gates, and the style-reload path

**iOS:** "Cache keys deliberately match the formats the annotation managers already use as image names (`whisper-RRGGBB`, `cairn-<tier>`), so the swap from SF Symbols changes pixels, not refresh behavior" (builder doc comment). Point images are re-attached wholesale on every `buildPoints` pass.

**Android claims:**
- `rememberWhisperGlyphBitmaps()` returns `Map<Long, Bitmap>` (keyed by the packed-ARGB long `WalkMapAnnotationKind.Whisper.categoryColor` carries — unchanged lookup contract at both sites) and `rememberCairnGlyphBitmaps()` returns `Map<CairnTier, Bitmap>`; both are `remember(density)`-keyed — density is the only input the draw reads that can change while the composition lives (tints are fixed literals, art is baked, sizes are constants).
- The whisper map also carries the stone fallback tint (`0xFF8B7355`) that `MapAnnotations.kt` packs for an unresolved category, so that lookup cannot miss.
- Both snapshot-equality rebuild gates gain the glyph maps as key components (`renderedWalkAnnotationsKey` list gains `whisperGlyphBitmaps` + `cairnGlyphBitmaps`; the proximity gate becomes `renderedProximityKey = listOf(proximityPins, whisperGlyphBitmaps, cairnGlyphBitmaps)`) — the draw reads the bitmaps, so a re-rasterization must re-fire the rebuild (Stage 13-D rule).
- Both gates reset in the `LaunchedEffect(mapView, styleUri)` teardown and in `onRelease` (the triple-shipped Stage 13 bug class), so a theme flip rebuilds annotations from bitmaps that are now **theme-insensitive** (the old `rememberCairnGlyphBitmap(darkMode)` moss tint is gone with the Material icon).
- The late-style-callback path needs no special handling: annotation managers are created only inside the `loadStyle` success callback; gate state is already nulled before `loadStyle` is issued, so whenever the callback lands (before or after the 3s timeout fade-in) the first `update` pass with non-null managers rebuilds from scratch.

## L7. Test parity map

| iOS (`MapGlyphImageBuilderTests.swift@9a418e4`) | Android (`MapGlyphBitmapsTest.kt`, `@GraphicsMode(NATIVE)`) |
|---|---|
| `testWhisperRendersForEveryMoodColor` | every `WhisperCategory` tint → non-null, pairwise-distinct bitmaps |
| `testCairnRendersForEveryTier` | every `CairnTier` → non-null, pairwise-distinct bitmaps (also catches tier→drawable mapping bugs) |
| `testPixelDimensionsMatchRequestedSize` (+ scale assertion) | output px == dp × density, asserted at two densities for wisp and cairn |
| `testRepeatedRequestReturnsCachedInstance` (`===`) | `assertSame` on identical-key wisp and cairn requests |
| `testCacheKeysMatchExistingAnnotationNameFormats` / `testDistinctTintsAndTiersProduceDistinctKeys` | distinct densities → distinct instances; cairn instance identical across a simulated night-configuration flip (no theme in key) |
| `testMissingAssetDegradesToNil` | invalid drawable id → null, no crash |
| — (sizes tuned on device, iOS) | tier→size table + wisp 28dp pinned against L4 (the U15 pure seam) |

U15 itself ships no further scenarios (plan: "call-site substitution verified on device") beyond the seam test above.

## Divergences (conscious) and resolved ambiguities

- **D1 — tint type:** iOS `UIColor` → Android `Int` ARGB (`Color.toArgb()` at the call sites). Key format keeps iOS's RGB-only hex; alpha never varies (fixed opaque literals — iOS's DEBUG assert becomes a non-issue by construction).
- **D2 — sizing mechanism replaced, not translated:** old Android scaled ONE 72px Material-icon bitmap via `iconSize = dp/18` ratios; new code rasterizes per (glyph, size, density) and pins `iconSize = 1.0`, which is what iOS actually ships. The ratio constants (`WHISPER_GLYPH_ICON_SIZE`, `cairnGlyphIconSize`, `CAIRN_PIN_BASE_DP`) are deleted.
- **D3 — unresolved-category whisper:** iOS has no such path (`categoryColor` is always resolved upstream). Android's `MapAnnotations.kt` packs stone `0xFF8B7355` for a null category; the whisper bitmap map carries that tint so the pin renders as a stone-tinted wisp (previously it fell back to the moss *mountain* glyph — wrong presence).
- **D4 — cache lifetime:** iOS static dictionary, never evicted. Android same (object-level `HashMap`) plus the `remember(density)` maps in composition; density in the key means a `wm density` flip strands a handful of old entries — bounded and accepted, matching iOS's no-eviction stance. `clearCache()` is test-only (iOS `_test_clearCache` analogue; Robolectric sandboxes share statics across methods).
- **D5 — image identity:** iOS names style images with the cache key; Android per-annotation `withIconImage(Bitmap)` generates internal `icon_default_name_<hash>` ids — the no-named-style-images decision (plan KTD) supersedes name parity; refresh behavior is owned by the snapshot gates instead.
- **Resolved — where sizes live:** per-tier size is an Android-side table (`cairnGlyphSizeDp`) mirroring iOS's inline `24 + rawValue * 2`; kept next to the rasterizer so the U15 wiring and the test read one source.
- **Out of scope here (U16):** stone-sheet 96/112, detail-view 64–136 + hero/glow geometry, mood-row ~20dp wisp; ghost/dimmed alpha treatment; dark-basemap legibility remains device QA (R14).
