# Vector Glyph Masters Import (U13) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U13) · **Requirements:** R10
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (tag v1.9.0, 2026-07-21). All Swift/SVG quotes cite `file@9a418e4`.
> **Authority:** SHIPPED assets over plan docs. The eight SVG masters in `Pilgrim/Support Files/Assets.xcassets/glyphs/` are the canonical art (iOS plan R10: "Masters live canonically in this repo; pilgrim-android converts the same files… at parity-port time"). Android converts them by **direct SVG→VectorDrawable translation** (plan Key Technical Decisions — Android Studio / Vector Asset Studio is not installed here; it remains an optional cross-check only). Parity is **pixel-source parity**: same path data, same baked colors, same gradient geometry. Rasterization size/density policy is U14's contract, not this unit's.
> **Android files:** `app/src/main/res/drawable/glyph_whisper_wisp.xml`, `glyph_cairn_faint.xml`, `glyph_cairn_small.xml`, `glyph_cairn_medium.xml`, `glyph_cairn_large.xml`, `glyph_cairn_great.xml`, `glyph_cairn_sacred.xml`, `glyph_cairn_eternal.xml`; test `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/map/GlyphAssetTest.kt`.
> **Android templates:** `res/drawable/ic_notification_*.xml` (tintable-vector convention: white fill + `android:tint`), `ui/etegami/EtegamiSealBitmapRendererTest.kt` (Robolectric bitmap-assertion conventions).

## L1. The eight masters and their names

**iOS** (`git ls-tree 9a418e4 -- 'Pilgrim/Support Files/Assets.xcassets/glyphs/'`): one wisp + seven cairn tiers, each a single-SVG imageset:

```
glyphs/whisperWisp.imageset/whisper.svg
glyphs/cairn-faint.imageset/cairn-faint.svg
glyphs/cairn-small.imageset/cairn-small.svg
glyphs/cairn-medium.imageset/cairn-medium.svg
glyphs/cairn-large.imageset/cairn-large.svg
glyphs/cairn-great.imageset/cairn-great.svg
glyphs/cairn-sacred.imageset/cairn-sacred.svg
glyphs/cairn-eternal.imageset/cairn-eternal.svg
```

Name contract pinned by `UnitTests/GlyphAssetTests.swift@9a418e4` over `CairnTier.glyphAssetName` (`Pilgrim/Models/Walk/MapManagement/CairnTier.swift@9a418e4`):

```swift
case .faint: return "cairn-faint"
case .small: return "cairn-small"
case .medium: return "cairn-medium"
case .large: return "cairn-large"
case .great: return "cairn-great"
case .sacred: return "cairn-sacred"
case .eternal: return "cairn-eternal"
```

**Android claims:**
- Tier vocabulary matches 1:1 — Android's `data/cairn/CairnTier.kt` already names the seven tiers `Faint, Small, Medium, Large, Great, Sacred, Eternal` (same thresholds 3/7/12/42/77/108). Drawable names follow Android resource conventions: `glyph_cairn_<tier>` + `glyph_whisper_wisp`.
- `GlyphAssetTest` pins the mapping the same way iOS's `testTierAssetNameMapping` does: a `CairnTier → R.drawable` map asserted exhaustive over `CairnTier.entries`, and all eight ids must inflate.

## L2. Master inventory (path counts, viewBoxes, palettes)

Source inventory at the pin (raw `<path|polygon|polyline|circle>` counts; "degenerate" = bare single-pair moveto paths that render nothing in SVG — see L5):

| master | viewBox | drawn elements | degenerate | strokes | gradients | distinct colors |
|---|---|---|---|---|---|---|
| `whisper.svg` | `0 0 150 150` | 4 | 0 | 0 | 0 | 1 |
| `cairn-faint.svg` | `0 -40.65 144 144` | 9 | 0 | 2 | 0 | 8 |
| `cairn-small.svg` | `-2.55 0 150 150` | 16 | 0 | 0 | 0 | 11 |
| `cairn-medium.svg` | `0 0 150 150` | 30 | 1 | 0 | 0 | 21 |
| `cairn-large.svg` | `0 0 150 150` | 36 | 2 | 3 | 0 | 21 |
| `cairn-great.svg` | `0 0 150 150` | 135 | 44 | 2 | 0 | 112 |
| `cairn-sacred.svg` | `-45.05 0 246 246` | 85 | 1 | 1 | 1 (radial) | 30 |
| `cairn-eternal.svg` | `0 0 150 150` | 69 | 2 | 2 | 2 (radial + linear) | 35 |

All canvases are **square** (iOS plan Key Technical Decisions: "Square masters, view-side sizing"), three with non-zero viewBox origins.

Baked palettes (R9: fixed hexes, never adaptive):
- **wisp**: monochrome `#06090E` across all four paths (see L3).
- **faint**: pale weathered greys/bones — `#C8CAC7 #CBC3B8 #ADA79C` bodies, `#908B80 #90897E` hairline strokes (0.75w), `#051A0D` @ 0.1 alpha shadows, `#FFFDF8`/`#FFFFFF` @ 0.2 alpha highlights.
- **small**: cool dark greys `#3A3B3C…#797874`, highlight `#ADA7A5 #D6D4D4`.
- **medium**: mid greys/warm tans, 21 hexes `#656462…#B5B2AB`.
- **large**: blue-greys `#626468…#ABADB2` + first moss accents `#8DA457 #878F47` + snow strokes `#EEEEE3` (3.4764w polylines).
- **great**: fullest palette (112 hexes): stone greys, tans `#B49473 #C6A884`, moss/lichen greens (`#89975D #8A9750 #929F53 #93966E …`), snow `#DCCEAF #CBCBB0`, hairline strokes `#BFBDA3` (0.25w).
- **sacred**: greys + moss `#8E915C #9CA26D #CECAA6`, snow `#F1EEE4`, warm radial halo `#FFDBAC` (stop-opacity 0.25 → 0).
- **eternal**: greys + moss `#8E9160 #9FA070 #CCCCAE`, snow strokes `#F5F3EB` (3.6628w), warm radial glow `#EDCB9B` (stop-opacity 0.95 → 0) on a centered circle, plus a subtle linear body gradient `#958A7D → #9F9588`.

**Android claims:**
- Every drawn element ports with its authored geometry and color; nothing is recolored, simplified, or re-authored. Distinct-color counts above are the drift check for any future re-export.
- Cairn fills are baked as opaque `#FFRRGGBB` literals (or `fillAlpha` where the SVG carries `opacity`/`fill-opacity`) — **never** theme attributes or `?attr` references. Dark-basemap legibility is device QA (plan U13 verification), exactly the iOS U3 concern ("the baked fixed hexes lose the adaptive-`.moss` safety net").

## L3. The wisp's tintability contract

**iOS** (`glyphs/whisperWisp.imageset/Contents.json@9a418e4`):

```json
"properties" : {
  "preserves-vector-representation" : true,
  "template-rendering-intent" : "template"
}
```

The seven cairn imagesets carry **no** `template-rendering-intent` (original rendering). Tint application at raster time (`Pilgrim/Views/MapGlyphImageBuilder.swift@9a418e4`):

```swift
case .whisper(let tint):
    rendered = self.rendered(assetNamed: whisperAssetName, tint: tint, size: size)
case .cairn(let tier):
    rendered = self.rendered(assetNamed: tier.glyphAssetName, tint: nil, size: size)
```

**Android claims:**
- `glyph_whisper_wisp.xml` is the template analogue: all four paths `android:fillColor="#FFFFFFFF"` with `android:tint="#FF06090E"` on the `<vector>` root — the authored master ink as the default so an untinted render matches the iOS master, while runtime `Drawable.setTint(...)` (SRC_IN) replaces it per mood. This is the repo's tintable-vector convention (`ic_notification_*.xml`: white fill + root tint).
- The cairn drawables carry **no** tint attribute and are never tinted by callers; their art is `.alwaysOriginal` parity. (Ghost/dimmed states in U16 use view alpha, since tints no-op semantically on baked art — iOS U4 note.)
- The 8 mood tint sources stay `WhisperCategory` fixed colors (already ported); tint-at-raster-time is U14's job.

## L4. Direct SVG→VectorDrawable translation mapping

Per plan Key Technical Decisions ("the flat-vector subset maps 1:1"):

| SVG construct (as found) | VectorDrawable |
|---|---|
| `viewBox="minX minY w h"` | `viewportWidth="w"` `viewportHeight="h"`; non-zero origin (faint `0 -40.65`, small `-2.55 0`, sacred `-45.05 0`) → wrapping `<group android:translateX="-minX" android:translateY="-minY">` |
| `<path d="…">` | `android:pathData` **verbatim** (identical grammar) |
| `<polygon points>` / `<polyline points>` | `pathData` `M…L…Z` / `M…L…` |
| `<circle cx cy r>` (eternal glow) | two-arc `pathData` |
| `fill="#hex"` | `android:fillColor="#FFhex"` |
| `opacity` × `fill-opacity` | `android:fillAlpha` (multiplied) |
| `stroke` + `stroke-width/-linecap/-linejoin/-miterlimit` | `android:strokeColor/strokeWidth/strokeLineCap/strokeLineJoin/strokeMiterLimit` |
| `radialGradient`/`linearGradient` (`userSpaceOnUse`) + `fill="url(#id)"` | inline `<aapt:attr name="android:fillColor"><gradient android:type="radial|linear" …>` with `<item>` stops |
| `stop-color` + `stop-opacity` | stop alpha folded into the item's ARGB color |
| `gradientTransform="matrix(1 0 0 -1 0 150)"` (eternal) | affine applied to gradient coordinates: radial center `(75.7, 75.15)` → `(75.7, 74.85)` (radius invariant under flip); linear `y 79.6` → `70.4` |
| `enable-background`, empty `style=""`, XML comments | dropped (non-rendering) |
| degenerate bare-moveto paths | dropped (L5) |

- Intrinsic size is `24dp` square on all eight (repo drawable convention) — cosmetic only; U14 rasterizes at explicit dp × density bounds.
- Gradient coordinates live in the same user space as `pathData`, so they stay verbatim inside translate groups (sacred's halo center `77.79, 115.2` is untouched by the `translateX="45.05"` group).
- No `fill-rule`/`clip-rule`/`stroke-dasharray`/`stroke-opacity` appear anywhere at the pin; SVG's default nonzero fill rule = VectorDrawable's default `fillType`.
- Translation was scripted (deterministic, order-preserving); the script is dev-time tooling, not shipped.

## L5. Dropped constructs (deliberate, render-identical)

The quiver.ai exports contain artifacts that render nothing in SVG and are omitted from the drawables:

- **Degenerate paths** — a moveto with a single coordinate pair and no draw commands (e.g. `cairn-great.svg`: `<path fill="#8A857C" d="m104.1 89.4"/>`; `cairn-large.svg`: `<path fill="#74767B" d="m106.9 100z"/>`). Counts: medium 1, large 2, great 44, sacred 1, eternal 2. Multi-pair movetos (implicit linetos) and multi-subpath `d` strings are **kept** — only zero-extent paths are dropped.
- The `<!-- SVG created with Arrow, by QuiverAI -->` generator comments (provenance recorded in each drawable's header comment instead, citing master + pin).

Nothing visible was simplified; path data for every drawn element is byte-identical to the master (modulo the polygon/polyline/circle conversions in L4, which are exact geometry).

## L6. R8 subset compliance — no violations, one wording note

iOS brainstorm R8 (`docs/brainstorms/2026-07-21-svg-map-glyphs-requirements.md@9a418e4`):

> R8. SVG masters use the flat-vector subset importable by both Xcode asset catalogs and Android Vector Asset Studio: paths and groups only — no filters, masks, embedded text, or CSS.

**Finding:** none of the hard exclusions appear (no filters, masks, text, CSS, defs/use, clip paths). The shipped masters do exceed R8's literal "paths and groups only" wording with: simple linear/radial gradients (sacred, eternal), `circle`/`polygon`/`polyline` primitives, and stroked paths. All of these are inside the importable subset the iOS plan itself documented ("static SVG (paths/groups/simple gradients)") and translate losslessly to VectorDrawable — **no simplification was needed**. Recorded here so a future re-export that leans further (e.g. gradient-on-stroke, masks) is caught against the promise rather than silently hand-waved.

## L7. Test parity map

| iOS (`GlyphAssetTests.swift@9a418e4`) | Android (`GlyphAssetTest.kt`) |
|---|---|
| `testAllGlyphAssetsResolve` — `UIImage(named:)` non-nil for all 8 | `all eight glyph drawables inflate` — `context.getDrawable` non-null for all 8 ids |
| `testTierAssetNameMapping` — tier → asset-name table | `every tier maps to a drawable` — `CairnTier → R.drawable` map exhaustive over `entries` |
| — (tint verified on device) | `wisp renders differently under two tints` — two tinted rasterizations differ (template behavior pinned in JVM) |
| — (tier progression verified on device, U3) | `each cairn tier rasterizes non-blank at map size` (24 px) + `adjacent cairn tiers produce distinct bitmaps` (48 px) |

Android's render assertions require `@GraphicsMode(GraphicsMode.Mode.NATIVE)` — this project's Robolectric default is LEGACY shadows whose Canvas draws nothing, so every pixel assertion would vacuously fail on blank bitmaps without it.

## Divergences (conscious) and resolved ambiguities

- **D1 — naming**: `whisperWisp`/`cairn-<tier>` asset-catalog strings become `glyph_whisper_wisp`/`glyph_cairn_<tier>` resource names (Android lowercase_underscore requirement). The tier *words* are identical; `GlyphAssetTest` pins the mapping.
- **D2 — template mechanism**: iOS template-rendering-intent + `withTintColor` ↔ Android white-fill + root `android:tint` default + runtime `setTint`. Same contract (one monochrome master, tint decided by caller), platform-native mechanism.
- **D3 — degenerate-path pruning**: 50 zero-extent paths across five masters are omitted (L5). Render-identical; iOS ships them only because Xcode ingests the SVG bytes wholesale.
- **D4 — default wisp ink**: Android's untinted wisp renders `#06090E` (the authored master fill). iOS never renders the wisp untinted (every surface tints); Android callers won't either after U14/U16, but the default keeps an accidental untinted render matching the master instead of invisible white.
- **Resolved**: gradient support needs no fallback — VectorDrawable's linear/radial gradients (API 24+; minSdk 28) express both halos and the eternal body gradient exactly, including stop alpha.
- **Out of scope here (U14–U16)**: rasterization at dp × density, per-annotation bitmaps, mood-color tint table, tier size progression + #57 doubling, sheet/detail/mood-row surfaces, on-device dark-basemap legibility QA.
